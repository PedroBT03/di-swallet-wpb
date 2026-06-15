/**
 * Default Wallet Instance Attestation (WIA) JWT issuer.
 */

package di.swallet.wpb.wia.attestation

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.util.Base64 as NimbusBase64
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.issuance.crypto.JwsSigningService
import di.swallet.wpb.issuance.crypto.Rfc7638JwkThumbprint
import di.swallet.wpb.issuance.domain.WalletInstanceAttestation
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.wia.status.WiaStatusManagementService
import org.springframework.stereotype.Component
import java.time.Instant

/** Builds and signs WIA and PoP JWTs with wallet metadata and status list references. */
@Component
class DefaultWalletAttestationProvider(
    private val walletKeyRepository: WalletKeyRepository,
    private val statusManagementService: WiaStatusManagementService,
    private val jwsSigningService: JwsSigningService,
    private val hsmService: HsmService,
    private val properties: OpenId4VciProperties,
) : WalletAttestationProvider {

    /** Issues attestation and proof JWTs with cnf.jkt and client_status list binding. */
    override fun issue(
        holderId: String,
        walletInstanceId: String,
        issuerId: String?,
    ): WalletInstanceAttestation {
        val now = Instant.now()
        val tokenExp = now.plusSeconds(properties.wia.tokenTtlSeconds.coerceAtMost(24 * 60 * 60 - 1))
        val statusExp = now.plusSeconds(properties.wia.minStatusMaintenanceDays * 24 * 60 * 60)
        val key = walletKeyRepository.findByUserId(walletInstanceId)
            .orElseThrow { IllegalStateException("wallet key not found for wallet instance '$walletInstanceId'") }

        val statusRef = statusManagementService.getOrAllocateStatus(
            holderId = holderId,
            issuerId = if (properties.wia.reusePerIssuer) issuerId else null,
        )

        val cnfJkt = Rfc7638JwkThumbprint.fromPublicKeyBase64(key.publicKeyBase64)
        val x5c = resolveSigningX5cChain(key)
        val payload = mapOf(
            "sub" to walletInstanceId,
            "wallet_name" to properties.wia.walletName,
            "wallet_version" to properties.wia.walletVersion,
            "wallet_link" to properties.wia.walletLink,
            "wallet_solution_certification_information" to properties.wia.walletSolutionCertificationInformation,
            "iat" to now.epochSecond,
            "exp" to tokenExp.epochSecond,
            "client_status" to mapOf(
                "status" to mapOf(
                    "status_list" to mapOf(
                        "idx" to statusRef.index,
                        "uri" to statusRef.uri,
                    ),
                ),
                "exp" to statusExp.epochSecond,
            ),
            "cnf" to mapOf("jkt" to cnfJkt),
        )
        val wiaJwt = signJwt(
            walletKey = key,
            typ = "oauth-client-attestation+jwt",
            payload = payload,
            x5c = x5c,
        )
        val popJwt = signJwt(
            walletKey = key,
            typ = "oauth-client-attestation-pop+jwt",
            payload = mapOf(
                "iss" to walletInstanceId,
                "iat" to now.epochSecond,
                "exp" to now.plusSeconds(300).epochSecond,
                "aud" to (issuerId ?: "issuer"),
                "cnf" to mapOf("jkt" to cnfJkt),
            ),
            x5c = emptyList(),
        )

        return WalletInstanceAttestation(
            jwt = wiaJwt,
            popJwt = popJwt,
            walletInstanceId = walletInstanceId,
            walletName = properties.wia.walletName,
            walletVersion = properties.wia.walletVersion,
            walletLink = properties.wia.walletLink.takeIf { it.isNotBlank() },
            walletSolutionCertificationInformation = properties.wia.walletSolutionCertificationInformation,
            cnfJkt = cnfJkt,
            clientStatus = statusRef,
            tokenExpiresAt = tokenExp,
            clientStatusExpiresAt = statusExp,
            issuedAt = now,
            issuerScope = if (properties.wia.reusePerIssuer) issuerId else null,
        )
    }

    /** Prefers configured x5c chain, falling back to the HSM certificate chain. */
    private fun resolveSigningX5cChain(walletKey: WalletKey): List<String> =
        properties.wia.signingX5cChain().ifEmpty { hsmService.certificateChainBase64(walletKey) }

    /** Signs a JWT with ES256 via the HSM, optionally embedding an x5c header. */
    private fun signJwt(
        walletKey: WalletKey,
        typ: String,
        payload: Map<String, Any?>,
        x5c: List<String>,
    ): String {
        val builder = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType(typ))
            .keyID(walletKey.keyAlias)
        if (x5c.isNotEmpty()) {
            builder.x509CertChain(x5c.map(::NimbusBase64))
        }
        val header = builder.build()
        return jwsSigningService.signJws(walletKey, header, payload)
    }
}
