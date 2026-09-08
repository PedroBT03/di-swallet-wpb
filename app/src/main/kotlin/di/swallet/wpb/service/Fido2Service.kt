/**
 * FIDO2/WebAuthn relying-party logic for device registration and sole-control authentication.
 */

package di.swallet.wpb.service

import com.yubico.webauthn.*
import com.yubico.webauthn.data.*
import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.config.WalletProperties
import di.swallet.wpb.domain.UserDevice
import di.swallet.wpb.domain.UserDeviceRepository
import di.swallet.wpb.domain.DeviceWalletBindingRepository
import di.swallet.wpb.security.ChallengeService
import di.swallet.wpb.security.Fido2ChallengeSupport
import di.swallet.wpb.security.WscaSciBootstrap
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*

/**
 * Verifies WebAuthn assertions and manages registered FIDO2 devices as the wallet relying party.
 */
@Service
class Fido2Service(
    private val userDeviceRepository: UserDeviceRepository,
    private val deviceWalletBindingRepository: DeviceWalletBindingRepository,
    private val challengeService: ChallengeService,
    private val walletProperties: WalletProperties,
    private val objectMapper: ObjectMapper,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private lateinit var rp: RelyingParty

    /**
     * Initializes the WebAuthn Relying Party from application properties.
     *
     * TODO: PRODUCTION - Phishing Protection.
     * Set WALLET_RP_ID and WALLET_ORIGINS to the registered Wallet Provider domain (e.g., wpb.diswallet.eu).
     *
     * TODO: PRODUCTION - Hardware Attestation.
     * Configure MDS (FIDO Metadata Service) to verify hardware root of trust (LoA High).
     */
    @PostConstruct
    fun init() {
        val rpIdentity = RelyingPartyIdentity.builder()
            .id(walletProperties.rp.id)
            .name(walletProperties.rp.name)
            .build()

        val origins = walletProperties.origins.split(",").map { it.trim() }.toSet()

        rp = RelyingParty.builder()
            .identity(rpIdentity)
            .credentialRepository(Fido2CredentialBridge(userDeviceRepository))
            .origins(origins)
            .allowUntrustedAttestation(walletProperties.allowUntrustedAttestation)
            .build()

        logger.info("FIDO2: RelyingParty initialized - rpId='${walletProperties.rp.id}', origins=$origins, untrustedAttestation=${walletProperties.allowUntrustedAttestation}")
    }

    /**
     * Registers a new physical device for the user.
     *
     * TODO: PRODUCTION - Verified Onboarding.
     * This binding must be preceded by a High LoA identity check (e.g., via CMD or Citizen Card).
     */
    fun registerDevice(
        userId: String,
        credentialId: String,
        publicKeyBase64: String,
        replaceExisting: Boolean = false,
    ): UserDevice =
        WscaSciBootstrap.allow {
            if (replaceExisting) {
                userDeviceRepository.findByUserId(userId)
                    .filter { it.credentialId != credentialId }
                    .filter { device ->
                        val deviceId = device.id ?: return@filter true
                        !deviceWalletBindingRepository.existsByUserDevice_Id(deviceId)
                    }
                    .forEach { stale ->
                        logger.info(
                            "FIDO2: Removing unbound stale credential {} for user {}",
                            stale.credentialId,
                            userId,
                        )
                        userDeviceRepository.delete(stale)
                    }
            }
            val existing = userDeviceRepository.findByCredentialId(credentialId)
            if (existing.isPresent) return@allow existing.get()

            val keyBytes = try {
                Base64.getUrlDecoder().decode(publicKeyBase64.trim())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("FIDO2: publicKeyBase64 is not valid Base64URL for user $userId")
            }
            if (keyBytes.isEmpty() || keyBytes[0] != 0xa5.toByte()) {
                throw IllegalArgumentException("FIDO2: publicKeyBase64 does not appear to be a valid COSE EC2 key for user $userId")
            }

            val device = UserDevice(
                userId = userId,
                credentialId = credentialId,
                publicKeyBase64 = publicKeyBase64,
                userHandle = Base64.getUrlEncoder().withoutPadding().encodeToString(userId.toByteArray()),
            )
            logger.info("FIDO2: Binding hardware credential $credentialId to user $userId")
            userDeviceRepository.save(device)
        }

    /**
     * Starts the standard WebAuthn ceremony by generating an AssertionRequest.
     * When [credentialId] is set, the ceremony is narrowed to that passkey so the browser
     * cannot pick a different registered device for the same holder.
     */
    fun startAuthentication(userId: String, credentialId: String? = null): AssertionRequest {
        val request = rp.startAssertion(
            StartAssertionOptions.builder()
                .username(userId)
                .build(),
        )
        val narrowed = narrowAssertionRequest(request, userId, credentialId)
        challengeService.storeRequest(userId, narrowed)
        return narrowed
    }

    private fun narrowAssertionRequest(
        request: AssertionRequest,
        userId: String,
        credentialId: String?,
    ): AssertionRequest {
        if (credentialId.isNullOrBlank()) {
            return request
        }
        val device = userDeviceRepository.findByCredentialId(credentialId)
        if (device.isPresent && device.get().userId != userId) {
            throw IllegalArgumentException("FIDO2: credential $credentialId is not registered for user $userId")
        }
        val targetId = com.yubico.webauthn.data.ByteArray.fromBase64Url(credentialId)
        val narrowedOptions = request.publicKeyCredentialRequestOptions.toBuilder()
            .allowCredentials(
                listOf(
                    PublicKeyCredentialDescriptor.builder()
                        .id(targetId)
                        .type(PublicKeyCredentialType.PUBLIC_KEY)
                        .build(),
                ),
            )
            .userVerification(UserVerificationRequirement.PREFERRED)
            .build()
        return request.toBuilder()
            .publicKeyCredentialRequestOptions(narrowedOptions)
            .username(userId)
            .build()
    }

    /**
     * Verifies the standard WebAuthn Assertion Response using the Yubico engine.
     * The challenge is always consumed after this call, whether it succeeds or fails,
     * to prevent replay attacks.
     */
    fun verifyStandardAssertion(
        userId: String,
        credentialId: String,
        clientDataJSON: String,
        authenticatorData: String,
        signature: String
    ): Boolean {
        val challengeKey = try {
            Fido2ChallengeSupport.extractChallengeKey(clientDataJSON, objectMapper)
        } catch (e: Exception) {
            logger.warn("SecurityPolicy: Could not extract FIDO2 challenge from clientDataJSON: ${e.message}")
            return false
        }
        val assertionRequest = challengeService.getRequest(userId, challengeKey) ?: return false

        return try {
            // Build the response components as defined in the W3C WebAuthn specification
            val authResponse = AuthenticatorAssertionResponse.builder()
                .authenticatorData(com.yubico.webauthn.data.ByteArray.fromBase64Url(authenticatorData))
                .clientDataJSON(com.yubico.webauthn.data.ByteArray.fromBase64Url(clientDataJSON))
                .signature(com.yubico.webauthn.data.ByteArray.fromBase64Url(signature))
                .build()

            val response = PublicKeyCredential.builder<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs>()
                .id(com.yubico.webauthn.data.ByteArray.fromBase64Url(credentialId))
                .response(authResponse)
                .clientExtensionResults(ClientAssertionExtensionOutputs.builder().build())
                .build()

            // Perform the full WebAuthn verification algorithm
            val result: AssertionResult = rp.finishAssertion(
                FinishAssertionOptions.builder()
                    .request(assertionRequest)
                    .response(response)
                    .build()
            )

            if (result.isSuccess) {
                userDeviceRepository.findByCredentialId(credentialId).ifPresent {
                    it.signatureCount = result.signatureCount
                    userDeviceRepository.save(it)
                }
                logger.info("SecurityPolicy: FIDO2 Standard assertion verified for user $userId")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            logger.error("SecurityPolicy: WebAuthn verification failed: ${e.message}")
            false
        } finally {
            challengeService.removeRequest(userId, challengeKey)
        }
    }

    /**
     * Adapts persisted user devices to the Yubico WebAuthn credential repository interface.
     */
    private class Fido2CredentialBridge(private val repo: UserDeviceRepository) : CredentialRepository {

        /**
         * Returns all registered credential descriptors for the given wallet user ID.
         */
        override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> {
            return repo.findByUserId(username).map {
                PublicKeyCredentialDescriptor.builder()
                    .id(com.yubico.webauthn.data.ByteArray.fromBase64Url(it.credentialId))
                    .type(PublicKeyCredentialType.PUBLIC_KEY)
                    .build()
            }.toSet()
        }

        /**
         * Returns the WebAuthn user handle bytes derived from the wallet user ID.
         */
        override fun getUserHandleForUsername(username: String): Optional<com.yubico.webauthn.data.ByteArray> {
            return Optional.of(com.yubico.webauthn.data.ByteArray(username.toByteArray()))
        }

        /**
         * Resolves the wallet user ID from a WebAuthn user handle byte array.
         */
        override fun getUsernameForUserHandle(userHandle: com.yubico.webauthn.data.ByteArray): Optional<String> {
            return Optional.of(String(userHandle.bytes))
        }

        /**
         * Loads a registered credential when both credential ID and user handle match a stored device.
         */
        override fun lookup(
            credentialId: com.yubico.webauthn.data.ByteArray,
            userHandle: com.yubico.webauthn.data.ByteArray
        ): Optional<RegisteredCredential> {
            val username = String(userHandle.bytes)
            return repo.findByCredentialId(credentialId.base64Url)
                .filter { it.userId == username }
                .map {
                    RegisteredCredential.builder()
                        .credentialId(credentialId)
                        .userHandle(userHandle)
                        .publicKeyCose(com.yubico.webauthn.data.ByteArray.fromBase64Url(it.publicKeyBase64))
                        .signatureCount(it.signatureCount)
                        .build()
                }
        }

        /**
         * Returns all registered credentials that share the given credential ID.
         */
        override fun lookupAll(credentialId: com.yubico.webauthn.data.ByteArray): Set<RegisteredCredential> {
            return repo.findByCredentialId(credentialId.base64Url).map {
                RegisteredCredential.builder()
                    .credentialId(credentialId)
                    .userHandle(com.yubico.webauthn.data.ByteArray(it.userId.toByteArray()))
                    .publicKeyCose(com.yubico.webauthn.data.ByteArray.fromBase64Url(it.publicKeyBase64))
                    .signatureCount(it.signatureCount)
                    .build()
            }.map { setOf(it) }.orElse(emptySet())
        }
    }
}