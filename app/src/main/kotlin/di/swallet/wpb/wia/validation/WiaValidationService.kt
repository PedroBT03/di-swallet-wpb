package di.swallet.wpb.wia.validation

import di.swallet.wpb.issuance.domain.WalletInstanceAttestation
import org.springframework.stereotype.Component
import java.time.Instant

class WiaValidationException(
    val code: String,
    message: String,
) : RuntimeException(message)

interface WiaValidationService {
    fun validateTechnical(attestation: WalletInstanceAttestation)
    fun validateAccessTokenBinding(wiaCnfJkt: String?, accessTokenCnfJkt: String?)
}

@Component
class DefaultWiaValidationService : WiaValidationService {
    override fun validateTechnical(attestation: WalletInstanceAttestation) {
        if (attestation.tokenExpiresAt.isBefore(Instant.now())) {
            throw WiaValidationException("wia_expired", "WIA token has expired")
        }
        if (attestation.clientStatusExpiresAt.isBefore(Instant.now())) {
            throw WiaValidationException("wia_status_expired", "WIA client_status has expired")
        }
    }

    override fun validateAccessTokenBinding(wiaCnfJkt: String?, accessTokenCnfJkt: String?) {
        if (wiaCnfJkt.isNullOrBlank() || accessTokenCnfJkt.isNullOrBlank()) {
            throw WiaValidationException("wia_binding_missing", "WIA/AT cnf thumbprint is missing")
        }
        if (wiaCnfJkt != accessTokenCnfJkt) {
            throw WiaValidationException("wia_binding_mismatch", "Access token cnf.jkt does not match WIA cnf key")
        }
    }
}

