/**
 * Validates Wallet Instance Attestation Proof-of-Possession (PoP) JWTs per ts3 §2.2.1.2.
 */

package di.swallet.wpb.wia.validation

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import di.swallet.wpb.issuance.domain.WalletInstanceAttestation
import org.springframework.stereotype.Component
import java.time.Instant

/** Raised when PoP technical checks fail. */
class WiaPopValidationException(
    val code: String,
    message: String,
) : RuntimeException(message)

/** Verifies that a PoP JWT is signed by the WIA cnf (device/DPoP) key. */
interface WiaPopValidationService {
    fun validate(popJwt: String, attestation: WalletInstanceAttestation)
}

@Component
class DefaultWiaPopValidationService(
    private val objectMapper: ObjectMapper,
) : WiaPopValidationService {
    override fun validate(popJwt: String, attestation: WalletInstanceAttestation) {
        if (popJwt.isBlank()) {
            throw WiaPopValidationException("wia_pop_missing", "Wallet attestation PoP is required")
        }
        val pop = runCatching { SignedJWT.parse(popJwt) }
            .getOrElse { throw WiaPopValidationException("wia_pop_invalid", "PoP is not a valid JWT") }
        val typ = pop.header.type?.type ?: ""
        if (typ != "oauth-client-attestation-pop+jwt") {
            throw WiaPopValidationException("wia_pop_typ", "PoP typ must be oauth-client-attestation-pop+jwt")
        }
        val claims = pop.jwtClaimsSet
        val exp = claims.expirationTime?.toInstant()
        if (exp == null || exp.isBefore(Instant.now())) {
            throw WiaPopValidationException("wia_pop_expired", "PoP has expired")
        }
        if (claims.issuer != attestation.walletInstanceId) {
            throw WiaPopValidationException("wia_pop_iss", "PoP iss must match wallet instance id")
        }
        @Suppress("UNCHECKED_CAST")
        val popCnf = claims.getClaim("cnf") as? Map<String, Any?>
        val popJkt = popCnf?.get("jkt")?.toString()
        if (popJkt.isNullOrBlank() || popJkt != attestation.cnfJkt) {
            throw WiaPopValidationException("wia_pop_cnf", "PoP cnf.jkt must match WIA cnf key")
        }
        val devicePublicKey = resolveCnfPublicKey(attestation)
        if (!pop.verify(ECDSAVerifier(devicePublicKey))) {
            throw WiaPopValidationException("wia_pop_signature", "PoP signature does not verify under WIA cnf key")
        }
    }

    /** Reads cnf.jwk from the WIA JWT payload; falls back to jkt-only attestation metadata. */
    private fun resolveCnfPublicKey(attestation: WalletInstanceAttestation): java.security.interfaces.ECPublicKey {
        val wia = SignedJWT.parse(attestation.jwt)
        @Suppress("UNCHECKED_CAST")
        val cnf = wia.jwtClaimsSet.getClaim("cnf") as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val jwk = cnf?.get("jwk") as? Map<String, Any?>
        if (jwk != null) {
            return ECKey.parse(objectMapper.writeValueAsString(jwk)).toECPublicKey()
        }
        throw WiaPopValidationException("wia_pop_cnf_jwk", "WIA cnf.jwk is required to verify PoP")
    }
}
