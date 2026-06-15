/**
 * Wallet Instance Attestation (WIA) validation for issuance authorization.
 */

package di.swallet.wpb.wia.validation

import di.swallet.wpb.issuance.domain.WalletInstanceAttestation
import di.swallet.wpb.service.StatusListService
import org.springframework.stereotype.Component
import java.time.Instant

/** Raised when WIA technical checks or access-token binding validation fails. */
class WiaValidationException(
    val code: String,
    message: String,
) : RuntimeException(message)

/** Validates WIA freshness, revocation status, and access-token cnf binding. */
interface WiaValidationService {
    /** Checks token expiry, client_status expiry, and revocation index state. */
    fun validateTechnical(attestation: WalletInstanceAttestation)

    /** Ensures WIA and access token cnf.jkt thumbprints match. */
    fun validateAccessTokenBinding(wiaCnfJkt: String?, accessTokenCnfJkt: String?)
}

/** Default WIA validator using the wallet status list service. */
@Component
class DefaultWiaValidationService(
    private val statusListService: StatusListService,
) : WiaValidationService {
    /** Rejects expired tokens, expired client_status, or revoked status indices. */
    override fun validateTechnical(attestation: WalletInstanceAttestation) {
        if (attestation.tokenExpiresAt.isBefore(Instant.now())) {
            throw WiaValidationException("wia_expired", "WIA token has expired")
        }
        if (attestation.clientStatusExpiresAt.isBefore(Instant.now())) {
            throw WiaValidationException("wia_status_expired", "WIA client_status has expired")
        }
        if (statusListService.isRevoked(attestation.clientStatus.index)) {
            throw WiaValidationException("wia_revoked", "WIA client_status index is revoked")
        }
    }

    /** Requires matching non-blank cnf.jkt values on WIA and access token. */
    override fun validateAccessTokenBinding(wiaCnfJkt: String?, accessTokenCnfJkt: String?) {
        if (wiaCnfJkt.isNullOrBlank() || accessTokenCnfJkt.isNullOrBlank()) {
            throw WiaValidationException("wia_binding_missing", "WIA/AT cnf thumbprint is missing")
        }
        if (wiaCnfJkt != accessTokenCnfJkt) {
            throw WiaValidationException("wia_binding_mismatch", "Access token cnf.jkt does not match WIA cnf key")
        }
    }
}
