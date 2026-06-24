/**
 * Default Wallet Instance Attestation (WIA) JWT issuer.
 */

package di.swallet.wpb.wia.attestation

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.util.Base64 as NimbusBase64
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.domain.DeviceBindingType
import di.swallet.wpb.domain.DeviceWalletBindingRepository
import di.swallet.wpb.domain.DeviceWalletBindingState
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.issuance.crypto.JwsSigningService
import di.swallet.wpb.issuance.crypto.Rfc7638JwkThumbprint
import di.swallet.wpb.issuance.domain.WalletInstanceAttestation
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.wia.status.WiaStatusManagementService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/** Builds and signs WIA and PoP JWTs with wallet metadata and status list references. */
@Component
class DefaultWalletAttestationProvider(
    private val walletKeyRepository: WalletKeyRepository,
    private val statusManagementService: WiaStatusManagementService,
    private val jwsSigningService: JwsSigningService,
    private val hsmService: HsmService,
    private val walletUnitRepository: WalletUnitRepository,
    private val deviceWalletBindingRepository: DeviceWalletBindingRepository,
    private val properties: OpenId4VciProperties,
) : WalletAttestationProvider {
    private val logger = LoggerFactory.getLogger(DefaultWalletAttestationProvider::class.java)
    private val objectMapper = ObjectMapper()

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

        val cnf = resolveCnfKey(holderId, key)
        val cnfJkt = cnf.thumbprint
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
            "cnf" to cnf.claim,
        )
        val wiaJwt = signJwt(
            walletKey = key,
            typ = "oauth-client-attestation+jwt",
            payload = payload,
            x5c = x5c,
        )
        // PoP is signed client-side with the device (cnf) private key during OAuth preparation.
        return WalletInstanceAttestation(
            jwt = wiaJwt,
            popJwt = "",
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

    /** WIA cnf claim and its RFC 7638 thumbprint. */
    private data class CnfKey(val claim: Map<String, Any?>, val thumbprint: String)

    /**
     * Resolves the WIA cnf key. The wallet instance proves possession of its device (DPoP) key at
     * the token endpoint, so the WIA attests that device key here, keeping it distinct from the
     * holder HSM key attested by the KA. Falls back to the HSM key when no device JWK is stored
     * (e.g. wallets provisioned before device JWK persistence).
     */
    private fun resolveCnfKey(holderId: String, hsmKey: WalletKey): CnfKey {
        val deviceJwk = resolveDeviceJwk(holderId)
        if (deviceJwk != null) {
            return CnfKey(
                claim = mapOf("jwk" to objectMapper.readValue(deviceJwk, Map::class.java)),
                thumbprint = Rfc7638JwkThumbprint.fromJwkJson(deviceJwk),
            )
        }
        logger.warn("No device JWK bound for holder '$holderId'; WIA cnf falls back to the HSM key")
        return CnfKey(
            claim = mapOf("jkt" to Rfc7638JwkThumbprint.fromPublicKeyBase64(hsmKey.publicKeyBase64)),
            thumbprint = Rfc7638JwkThumbprint.fromPublicKeyBase64(hsmKey.publicKeyBase64),
        )
    }

    /** Returns the active DPoP device public JWK bound to the holder's wallet unit, if any. */
    private fun resolveDeviceJwk(holderId: String): String? {
        val walletUnit = walletUnitRepository.findFirstByHolderId(holderId).orElse(null) ?: return null
        val walletUnitId = walletUnit.id ?: return null
        return deviceWalletBindingRepository
            .findByWalletUnitIdAndBindingType(walletUnitId, DeviceBindingType.DPOP)
            .firstOrNull { it.state == DeviceWalletBindingState.ACTIVE && !it.devicePublicJwk.isNullOrBlank() }
            ?.devicePublicJwk
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
