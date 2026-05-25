package di.swallet.wpb.wia.attestation

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.impl.ECDSA
import com.nimbusds.jose.util.Base64URL
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.issuance.domain.WalletInstanceAttestation
import di.swallet.wpb.wia.status.WiaStatusManagementService
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/**
 * MVP WIA provider.
 *
 * - Uses a persistent wallet key per wallet instance (`WalletKey.userId`)
 * - Builds a simplified JWT payload with WIA required claims.
 * - Signs attestation and PoP using deterministic pseudo-signature in demo mode
 *   to keep tests local; production signing wiring lands with real SDK+HSM chain.
 */
@Component
class DefaultWalletAttestationProvider(
    private val walletKeyRepository: WalletKeyRepository,
    private val statusManagementService: WiaStatusManagementService,
    private val properties: OpenId4VciProperties,
) : WalletAttestationProvider {

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

        val cnfJkt = thumbprintFromWalletKey(key.publicKeyBase64, key.keyAlias)
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
            typ = "oauth-client-attestation+jwt",
            kid = key.keyAlias,
            payload = payload,
            x5c = properties.wia.signingX5c.takeIf { it.isNotBlank() },
        )
        val popJwt = signJwt(
            typ = "oauth-client-attestation-pop+jwt",
            kid = key.keyAlias,
            payload = mapOf(
                "iss" to walletInstanceId,
                "iat" to now.epochSecond,
                "exp" to now.plusSeconds(300).epochSecond,
                "aud" to (issuerId ?: "issuer"),
                "cnf" to mapOf("jkt" to cnfJkt),
            ),
            x5c = null,
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

    private fun thumbprintFromWalletKey(publicKeyBase64: String, keyAlias: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val material = "$keyAlias:$publicKeyBase64".toByteArray()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(material))
    }

    private fun signJwt(
        typ: String,
        kid: String,
        payload: Map<String, Any?>,
        x5c: String?,
    ): String {
        val builder = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType(typ))
            .keyID(kid)
        if (!x5c.isNullOrBlank()) {
            // Kept as raw string in MVP (not parsed cert chain) for lightweight transport simulation.
            builder.customParam("x5c", listOf(x5c))
        }
        val header = builder.build()
        val jwsObject = JWSObject(header, Payload(payload))
        val pseudoSignature = pseudoSign(jwsObject.signingInput)
        val b64Sig = Base64URL.encode(pseudoSignature)
        return "${header.toBase64URL()}.${jwsObject.payload.toBase64URL()}.$b64Sig"
    }

    private fun pseudoSign(input: ByteArray): ByteArray {
        // Deterministic pseudo-signature for local/tests. Real HSM signing is wired
        // in the dedicated SDK adapter increment.
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return ECDSA.transcodeSignatureToConcat(
            byteArrayOf(0x30, 0x44, 0x02, 0x20) + digest.copyOfRange(0, 32) +
                byteArrayOf(0x02, 0x20) + digest.copyOfRange(0, 32),
            64,
        )
    }
}

