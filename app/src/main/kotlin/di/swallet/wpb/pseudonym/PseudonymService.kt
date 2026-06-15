/**
 * Creates and manages per-RP pseudonym credentials with server-side WebAuthn registration and authentication.
 */

package di.swallet.wpb.pseudonym

import di.swallet.wpb.config.PseudonymProperties
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.transactionlog.service.TransactionLogger
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * Orchestrates pseudonym lifecycle, WebAuthn ceremonies, and HSM-backed signing for holder passkeys.
 */
@Service
class PseudonymService(
    private val properties: PseudonymProperties,
    private val repository: PseudonymCredentialRepository,
    private val rpIdPolicy: RpIdPolicy,
    private val challengeStore: PseudonymChallengeStore,
    private val hsmService: HsmService,
    private val coseKeyMaterial: PseudonymCoseKeyMaterial,
    private val transactionMapper: PseudonymTransactionMapper,
    private val transactionLogger: TransactionLogger,
) {
    private val random = SecureRandom()
    private val timeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    /**
     * Rejects requests when pseudonym support is disabled in configuration.
     */
    fun ensureEnabled() {
        if (!properties.enabled) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Pseudonym support is disabled")
        }
    }

    /**
     * Creates a pending pseudonym for a holder and relying party, enforcing per-RP limits.
     */
    @Transactional
    fun create(request: CreatePseudonymRequest): PseudonymView {
        ensureEnabled()
        val holderId = request.holderId.trim()
        val rpId = request.rpId.trim().lowercase()
        rpIdPolicy.validateRpId(rpId)
        if (repository.countByHolderIdAndRpId(holderId, rpId) >= properties.maxPerRp) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "pseudonym_limit_reached")
        }
        val userHandle = randomBase64Url(properties.userHandleEntropyBytes)
        val credential = repository.save(
            PseudonymCredential(
                holderId = holderId,
                rpId = rpId,
                userHandle = userHandle,
                alias = request.alias?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
        return toView(credential)
    }

    /**
     * Lists pseudonyms for a holder, optionally filtered by relying party ID.
     */
    fun list(holderId: String, rpId: String?): List<PseudonymView> {
        ensureEnabled()
        val items = if (rpId.isNullOrBlank()) {
            repository.findByHolderId(holderId.trim())
        } else {
            repository.findByHolderIdAndRpId(holderId.trim(), rpId.trim().lowercase())
        }
        return items.map(::toView)
    }

    /**
     * Updates the optional display alias on an existing pseudonym credential.
     */
    @Transactional
    fun updateAlias(id: UUID, holderId: String, alias: String?): PseudonymView {
        ensureEnabled()
        val credential = loadForHolder(id, holderId)
        credential.alias = alias?.trim()?.takeIf { it.isNotEmpty() }
        return toView(repository.save(credential))
    }

    /**
     * Deletes a pseudonym, removes its HSM key, and logs a TS10 deletion transaction.
     */
    @Transactional
    fun delete(id: UUID, holderId: String) {
        ensureEnabled()
        val credential = loadForHolder(id, holderId)
        val publicKeyCose = credential.keyAlias?.let { coseKeyMaterial.publicKeyCoseBase64UrlFromAlias(it) }
            ?: encodePlaceholderValue(credential)
        credential.keyAlias?.let { hsmService.deleteDedicatedKey(it) }
        repository.delete(credential)
        transactionLogger.logPseudonymDeletion(
            holderId = holderId,
            transaction = transactionMapper.toDeletion(credential, publicKeyCose),
        )
    }

    /**
     * Issues WebAuthn registration options with a fresh challenge for a pending pseudonym.
     */
    fun registrationOptions(id: UUID, holderId: String, request: RegistrationOptionsRequest): RegistrationOptionsResponse {
        ensureEnabled()
        val credential = loadPending(id, holderId)
        validateOriginForRp(request.origin, credential.rpId)
        val challenge = randomBase64Url(32)
        challengeStore.store(id, challenge, properties.challengeTtlSeconds)
        return RegistrationOptionsResponse(
            challenge = challenge,
            rpId = credential.rpId,
            rpName = credential.rpId,
            userHandle = credential.userHandle,
            timeout = properties.challengeTtlSeconds * 1000,
            pubKeyCredParams = listOf(mapOf("type" to "public-key", "alg" to -7)),
        )
    }

    /**
     * Completes WebAuthn registration by generating an HSM key and marking the pseudonym as registered.
     */
    @Transactional
    fun finishRegistration(id: UUID, holderId: String, request: RegistrationFinishRequest): RegistrationFinishResponse {
        ensureEnabled()
        val credential = loadPending(id, holderId)
        validateOriginForRp(request.origin, credential.rpId)
        val parsed = PseudonymWebAuthnCodec.decodeClientData(request.clientDataJSON)
        if (parsed["type"] != "webauthn.create") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid WebAuthn ceremony type")
        }
        if (!challengeStore.consume(id, parsed["challenge"]!!)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired challenge")
        }

        val credentialIdBytes = randomBytes(32)
        val credentialId = Base64.getUrlEncoder().withoutPadding().encodeToString(credentialIdBytes)
        val keyAlias = "pseudonym-${credential.id}"
        val publicKey = hsmService.generateDedicatedEcKey(keyAlias)

        val authData = PseudonymWebAuthnCodec.buildRegistrationAuthData(
            rpId = credential.rpId,
            credentialId = credentialIdBytes,
            publicKey = publicKey,
            signCount = 0,
        )
        val attestationObject = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(PseudonymWebAuthnCodec.buildAttestationObject(authData))
        val publicKeyCose = coseKeyMaterial.encodeCosePublicKey(publicKey)

        credential.credentialId = credentialId
        credential.keyAlias = keyAlias
        credential.status = PseudonymStatus.REGISTERED
        credential.signCount = 0
        repository.save(credential)

        transactionLogger.logPseudonymGeneration(
            holderId = holderId,
            transaction = transactionMapper.toGeneration(credential, publicKeyCose),
        )

        return RegistrationFinishResponse(
            credentialId = credentialId,
            attestationObject = attestationObject,
            clientDataJSON = request.clientDataJSON,
            publicKeyCose = publicKeyCose,
        )
    }

    /**
     * Issues WebAuthn authentication options with a fresh challenge for a registered pseudonym.
     */
    fun authenticationOptions(id: UUID, holderId: String, request: AuthenticationOptionsRequest): AuthenticationOptionsResponse {
        ensureEnabled()
        val credential = loadRegistered(id, holderId)
        validateOriginForRp(request.origin, credential.rpId)
        val challenge = randomBase64Url(32)
        challengeStore.store(id, challenge, properties.challengeTtlSeconds)
        return AuthenticationOptionsResponse(
            challenge = challenge,
            rpId = credential.rpId,
            credentialId = credential.credentialId!!,
            timeout = properties.challengeTtlSeconds * 1000,
        )
    }

    /**
     * Completes WebAuthn authentication by signing assertion data with the pseudonym HSM key.
     */
    @Transactional
    fun finishAuthentication(id: UUID, holderId: String, request: AuthenticationFinishRequest): AuthenticationFinishResponse {
        ensureEnabled()
        val credential = loadRegistered(id, holderId)
        validateOriginForRp(request.origin, credential.rpId)
        val parsed = PseudonymWebAuthnCodec.decodeClientData(request.clientDataJSON)
        if (parsed["type"] != "webauthn.get") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid WebAuthn ceremony type")
        }
        if (!challengeStore.consume(id, parsed["challenge"]!!)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired challenge")
        }

        val nextCount = credential.signCount + 1
        val authData = PseudonymWebAuthnCodec.buildAssertionAuthData(credential.rpId, nextCount)
        val clientDataJson = String(Base64.getUrlDecoder().decode(request.clientDataJSON.trim()))
        val signatureInput = PseudonymWebAuthnCodec.assertionSignatureInput(authData, clientDataJson)
        val signature = hsmService.signEs256WithDedicatedAlias(credential.keyAlias!!, signatureInput)

        credential.signCount = nextCount
        credential.lastUsedAt = Instant.now()
        repository.save(credential)

        val publicKeyCose = coseKeyMaterial.publicKeyCoseBase64UrlFromAlias(credential.keyAlias!!)
        transactionLogger.logPseudonymousAuthentication(
            holderId = holderId,
            transaction = transactionMapper.toAuthentication(credential, publicKeyCose),
        )

        return AuthenticationFinishResponse(
            credentialId = credential.credentialId!!,
            authenticatorData = Base64.getUrlEncoder().withoutPadding().encodeToString(authData),
            clientDataJSON = request.clientDataJSON,
            signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature),
        )
    }

    /**
     * Loads a pseudonym credential and verifies it belongs to the given holder.
     */
    private fun loadForHolder(id: UUID, holderId: String): PseudonymCredential =
        repository.findById(id)
            .filter { it.holderId == holderId.trim() }
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Pseudonym not found") }

    /**
     * Loads a pseudonym that is still pending WebAuthn registration.
     */
    private fun loadPending(id: UUID, holderId: String): PseudonymCredential {
        val credential = loadForHolder(id, holderId)
        if (credential.status != PseudonymStatus.PENDING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Pseudonym is already registered")
        }
        return credential
    }

    /**
     * Loads a pseudonym that has completed registration and has an HSM key alias.
     */
    private fun loadRegistered(id: UUID, holderId: String): PseudonymCredential {
        val credential = loadForHolder(id, holderId)
        if (credential.status != PseudonymStatus.REGISTERED || credential.keyAlias.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Pseudonym is not registered")
        }
        return credential
    }

    /**
     * Verifies that the request origin host matches the relying party ID or is a subdomain of it.
     */
    private fun validateOriginForRp(origin: String, rpId: String) {
        val host = runCatching { URI(origin.trim()).host?.lowercase() }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid origin")
        if (host != rpId && !host.endsWith(".$rpId")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Origin does not match rpId")
        }
    }

    /**
     * Maps a stored credential entity to an API-facing view with formatted timestamps.
     */
    private fun toView(credential: PseudonymCredential): PseudonymView =
        PseudonymView(
            id = credential.id,
            holderId = credential.holderId,
            rpId = credential.rpId,
            alias = credential.alias,
            status = credential.status,
            credentialId = credential.credentialId,
            createdAt = timeFormatter.format(credential.createdAt),
            lastUsedAt = credential.lastUsedAt?.let { timeFormatter.format(it) },
        )

    /**
     * Generates cryptographically random bytes and encodes them as a Base64URL string.
     */
    private fun randomBase64Url(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(bytes))

    /**
     * Fills a byte array with secure random data.
     */
    private fun randomBytes(bytes: Int): ByteArray =
        ByteArray(bytes).also { random.nextBytes(it) }

    /**
     * Builds a placeholder COSE key value for pseudonyms deleted before registration completed.
     */
    private fun encodePlaceholderValue(credential: PseudonymCredential): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("pending:${credential.id}".toByteArray())
}
