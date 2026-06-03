package di.swallet.wpb.format.sdjwt

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.util.Base64URL
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.domain.CredentialBindingFormat
import di.swallet.wpb.presentation.domain.SelectedCredential
import di.swallet.wpb.service.CredentialBindingValidationService
import di.swallet.wpb.service.format.DisclosureCipherService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.Base64

/**
 * Produces a HAIP-aligned SD-JWT presentation for a single selected credential.
 *
 * The output respects the SD-JWT VC format:
 *
 *   <issuer-signed-jwt>~<d1>~<d2>~...~<kb-jwt>
 *
 * Where:
 *  - `<issuer-signed-jwt>` is the previously issued SD-JWT (no disclosures).
 *  - `<dN>` are only the disclosures whose claim names match the verifier
 *    request (or all disclosures when the request did not list any).
 *  - `<kb-jwt>` is signed inside the HSM, binding the presentation to the
 *    verifier `nonce`, `aud` and to the canonical SD-JWT digest (`sd_hash`).
 */
@Service
class SdJwtVpBuilder(
    private val walletCredentialRepository: WalletCredentialRepository,
    private val disclosureCipherService: DisclosureCipherService,
    private val keyBindingJwtSigner: KeyBindingJwtSigner,
    private val objectMapper: ObjectMapper,
    private val credentialBindingValidationService: CredentialBindingValidationService? = null,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun build(
        selected: SelectedCredential,
        verifierAudience: String,
        verifierNonce: String,
    ): SdJwtVpResult {
        val credentialId = selected.credentialId
            ?: return demoFallback(selected, verifierAudience, verifierNonce)

        val credential = walletCredentialRepository.findById(credentialId)
            .orElseThrow { IllegalStateException("Selected credential $credentialId not found") }
        credentialBindingValidationService?.requireBinding(credentialId, CredentialBindingFormat.SD_JWT)

        val signedJwt = credential.encodedData.substringBefore('~')
        val storedDisclosures = if (credential.encodedData.contains('~')) {
            credential.encodedData.split('~').drop(1).filter { it.isNotBlank() }
        } else {
            disclosureCipherService.decrypt(credential.encryptedDisclosures)
        }

        val filteredDisclosures = filterDisclosures(storedDisclosures, selected.requestedClaims)

        val canonicalSdJwt = buildString {
            append(signedJwt)
            filteredDisclosures.forEach { d -> append('~').append(d) }
            append('~')
        }

        val sdHash = sha256Base64Url(canonicalSdJwt)
        val kbJwtPayload = mapOf(
            "iat" to (System.currentTimeMillis() / 1000),
            "aud" to verifierAudience,
            "nonce" to verifierNonce,
            "sd_hash" to sdHash,
        )
        val keyAlias = runCatching { credential.walletKey?.keyAlias }.getOrNull()
        val kbJwt = if (!keyAlias.isNullOrBlank()) {
            keyBindingJwtSigner.signKeyBindingJwtForKeyAlias(keyAlias, kbJwtPayload)
        } else {
            keyBindingJwtSigner.signKeyBindingJwt(credential.userId, kbJwtPayload)
        }
        val presentation = "$canonicalSdJwt$kbJwt"

        logger.info(
            "vp.built credential={} disclosed={} aud={} hashed=true",
            credentialId,
            filteredDisclosures.size,
            verifierAudience,
        )
        return SdJwtVpResult(presentation = presentation, disclosuresIncluded = filteredDisclosures.size, isDemo = false)
    }

    /**
     * Demo fallback for synthetic candidates (no `credentialId`). The output
     * is *not* a verifiable presentation; it is a clearly marked diagnostic
     * payload to exercise the dispatch path against the local emulator.
     */
    private fun demoFallback(
        selected: SelectedCredential,
        verifierAudience: String,
        verifierNonce: String,
    ): SdJwtVpResult {
        val payload = "demo-vp:${selected.candidateId}:aud=$verifierAudience:nonce=$verifierNonce"
        logger.warn("vp.built using demo fallback for synthetic candidate {}", selected.candidateId)
        return SdJwtVpResult(presentation = payload, disclosuresIncluded = 0, isDemo = true)
    }

    private fun filterDisclosures(stored: List<String>, requestedClaims: List<String>): List<String> {
        if (requestedClaims.isEmpty()) return stored
        val requestedSet = requestedClaims.toSet()
        return stored.filter { disclosure ->
            val claimName = readClaimName(disclosure) ?: return@filter false
            claimName in requestedSet
        }
    }

    private fun readClaimName(base64UrlDisclosure: String): String? {
        return try {
            val decoded = String(Base64URL(base64UrlDisclosure).decode())
            val asList = objectMapper.readValue(decoded, List::class.java)
            asList.getOrNull(1) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun sha256Base64Url(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

data class SdJwtVpResult(
    val presentation: String,
    val disclosuresIncluded: Int,
    val isDemo: Boolean,
)
