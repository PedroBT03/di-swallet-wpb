package di.swallet.wpb.ka.attestation

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.util.Base64 as NimbusBase64
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.issuance.crypto.JwsSigningService
import di.swallet.wpb.issuance.crypto.Rfc7638JwkThumbprint
import di.swallet.wpb.issuance.domain.KeyAttestation
import di.swallet.wpb.ka.status.KaStatusManagementService
import di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import di.swallet.wpb.service.HsmService
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.Base64

@Component
class DefaultKeyAttestationProvider(
    private val walletKeyRepository: WalletKeyRepository,
    private val statusManagementService: KaStatusManagementService,
    private val jwsSigningService: JwsSigningService,
    private val hsmService: HsmService,
    private val properties: OpenId4VciProperties,
) : KeyAttestationProvider {
    override fun issue(
        holderId: String,
        issuerId: String?,
        metadata: ResolvedIssuerMetadata,
        configuration: CredentialConfigurationDescriptor,
        proofPublicKey: ECPublicKey,
        proofKeyId: String,
    ): KeyAttestation {
        val now = Instant.now()
        val tokenExp = now.plusSeconds(properties.ka.tokenTtlSeconds.coerceAtMost(7 * 24 * 60 * 60))
        val statusExp = now.plusSeconds(properties.ka.minStatusMaintenanceDays * 24 * 60 * 60)
        val walletKey = walletKeyRepository.findByUserId(holderId)
            .orElseThrow { IllegalStateException("wallet key not found for holder '$holderId'") }
        val attestedJkt = Rfc7638JwkThumbprint.fromEcPublicKey(proofPublicKey)
        val issuerScope = if (properties.ka.reusePerIssuer) issuerId else null
        val attestationFingerprint = fingerprint(holderId, issuerScope, configuration.id, attestedJkt)
        val status = statusManagementService.getOrAllocateStatus(holderId, issuerScope, attestationFingerprint)
        val payload = mapOf(
            "iss" to properties.ka.issuer,
            "sub" to holderId,
            "aud" to (issuerId ?: metadata.credentialIssuerId),
            "iat" to now.epochSecond,
            "exp" to tokenExp.epochSecond,
            "jti" to "ka-$attestationFingerprint",
            "key_storage" to properties.ka.keyStorage,
            "user_authentication" to properties.ka.userAuthentication,
            "certification" to mapOf(
                "scheme" to properties.ka.certificationScheme,
                "assurance_level" to properties.ka.certificationAssuranceLevel,
                "details" to properties.ka.certificationInfo,
            ),
            "key_storage_status" to mapOf(
                "status" to mapOf(
                    "status_list" to mapOf(
                        "idx" to status.index,
                        "uri" to status.uri,
                    ),
                ),
                "exp" to statusExp.epochSecond,
            ),
            "attested_keys" to listOf(
                mapOf(
                    "jwk" to ecPublicJwk(proofPublicKey, proofKeyId),
                    "proof_type" to "jwt",
                ),
            ),
            "credential_configuration_id" to configuration.id,
        )
        val x5c = properties.ka.signingX5cChain().ifEmpty { hsmService.certificateChainBase64(walletKey) }
        require(x5c.isNotEmpty()) { "key attestation requires a non-empty x5c chain" }
        val jwt = signJwt(
            walletKey = walletKey,
            typ = "keyattestation+jwt",
            payload = payload,
            x5c = x5c,
        )
        return KeyAttestation(
            jwt = jwt,
            keyId = proofKeyId,
            keyStorage = properties.ka.keyStorage,
            certification = properties.ka.certificationInfo,
            attestedJkt = attestedJkt,
            status = status,
            tokenExpiresAt = tokenExp,
            statusExpiresAt = statusExp,
            issuedAt = now,
            issuerScope = issuerScope,
            x5c = x5c,
        )
    }

    private fun signJwt(
        walletKey: di.swallet.wpb.domain.WalletKey,
        typ: String,
        payload: Map<String, Any?>,
        x5c: List<String>,
    ): String {
        val builder = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType(typ))
            .keyID(walletKey.keyAlias)
            .x509CertChain(x5c.map(::NimbusBase64))
        val header = builder.build()
        return jwsSigningService.signJws(walletKey, header, payload)
    }

    private fun fingerprint(holderId: String, issuerId: String?, configurationId: String, attestedJkt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val material = "$holderId|${issuerId ?: "*"}|$configurationId|$attestedJkt"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(material.toByteArray()))
    }

    private fun ecPublicJwk(publicKey: ECPublicKey, keyId: String): Map<String, String> {
        val fieldSize = (publicKey.params.curve.field.fieldSize + 7) / 8
        val x = publicKeyCoordinate(publicKey.w.affineX, fieldSize)
        val y = publicKeyCoordinate(publicKey.w.affineY, fieldSize)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return mapOf(
            "kty" to "EC",
            "crv" to "P-256",
            "kid" to keyId,
            "x" to encoder.encodeToString(x),
            "y" to encoder.encodeToString(y),
            "alg" to "ES256",
        )
    }

    private fun publicKeyCoordinate(value: java.math.BigInteger, size: Int): ByteArray {
        val rawInput = value.toByteArray()
        val raw = if (rawInput.size > size) rawInput.copyOfRange(rawInput.size - size, rawInput.size) else rawInput
        if (raw.size == size) return raw
        return ByteArray(size - raw.size) + raw
    }
}
