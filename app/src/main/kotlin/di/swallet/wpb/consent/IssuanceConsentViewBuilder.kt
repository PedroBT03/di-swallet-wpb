/**
 * Builds the holder consent preview shown before storing issued credentials.
 */

package di.swallet.wpb.consent

import di.swallet.wpb.config.ConsentProperties
import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.domain.IssuanceState
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Assembles issuer identity, format, and claim preview for issuance storage consent.
 */
@Component
class IssuanceConsentViewBuilder(
    private val consentProperties: ConsentProperties,
    private val pendingCredentialStore: PendingCredentialStore,
    private val previewParser: IssuedCredentialPreviewParser,
) {

    /**
     * Decrypts the pending credential payload and returns the consent view for the WPI.
     */
    fun build(context: IssuanceContext): IssuanceConsentView {
        if (!consentProperties.issuance.enabled) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Issuance consent UX is disabled")
        }
        if (context.state != IssuanceState.ISSUANCE_CONSENT_PENDING) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Session is not awaiting issuance consent (state=${context.state})",
            )
        }

        val encrypted = context.pendingCredentialsEncrypted
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "No pending credential payload for session")
        val pending = pendingCredentialStore.decrypt(encrypted)
        if (pendingCredentialStore.isExpired(pending)) {
            throw ResponseStatusException(HttpStatus.GONE, "Pending credential preview has expired")
        }

        val issued = pending.credentials.firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Pending credential payload is empty")

        val metadata = context.issuerMetadata
        val configuration = metadata?.credentialConfigurations?.firstOrNull {
            it.id == issued.credentialConfigurationId
        }
        val deviceBound = configuration?.keyAttestationRequired == true ||
            configuration?.proofTypesSupported?.any { it.equals("attestation", ignoreCase = true) } == true

        return IssuanceConsentView(
            sessionId = context.sessionMeta.sessionId,
            state = context.state,
            holderId = context.sessionMeta.holderId,
            issuer = IssuerConsentInfo(
                credentialIssuerId = context.credentialIssuerId ?: metadata?.credentialIssuerId,
                displayName = metadata?.credentialIssuerId,
            ),
            credentialConfigurationId = issued.credentialConfigurationId,
            format = issued.format,
            deviceBound = deviceBound,
            claimPreview = previewParser.parse(issued, deviceBound),
        )
    }
}
