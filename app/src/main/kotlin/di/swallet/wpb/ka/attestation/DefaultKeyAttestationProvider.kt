/**
 * Default Key Attestation (KA) JWT issuer for device-bound issuance.
 */

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
import di.swallet.wpb.ka.trust.KaSigningCertificateResolver
import di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.Base64

/** Builds and signs key attestation JWTs with status list and attested key claims. */
@Component
class DefaultKeyAttestationProvider(
    private val walletKeyRepository: WalletKeyRepository,
    private val statusManagementService: KaStatusManagementService,
    private val jwsSigningService: JwsSigningService,
    private val signingCertificateResolver: KaSigningCertificateResolver,
    private val properties: OpenId4VciProperties,
) : KeyAttestationProvider {
    private companion object {
        const val PROVISIONING_CONFIGURATION_ID = "wallet_provisioning"
    }

    /** Issues a KA JWT with attested_keys, key_storage_status, and signing x5c chain. */
    override fun issue(
        holderId: String,
        issuerId: String?,
        metadata: ResolvedIssuerMetadata,
        configuration: CredentialConfigurationDescriptor,
        proofPublicKey: ECPublicKey,
        proofKeyId: String,
    ): KeyAttestation =
        build(
            holderId = holderId,
            audience = issuerId ?: metadata.credentialIssuerId,
            configurationId = configuration.id,
            issuerScope = if (properties.ka.reusePerIssuer) issuerId else null,
            proofPublicKey = proofPublicKey,
            proofKeyId = proofKeyId,
        )

    /** Issues a provisioning KA attesting the holder key with no issuer audience or configuration. */
    override fun issueForProvisioning(
        holderId: String,
        keyAlias: String,
        proofPublicKey: ECPublicKey,
    ): KeyAttestation =
        build(
            holderId = holderId,
            audience = null,
            configurationId = PROVISIONING_CONFIGURATION_ID,
            issuerScope = null,
            proofPublicKey = proofPublicKey,
            proofKeyId = keyAlias,
        )

    /** Shared KA assembly for both issuance-time and provisioning-time attestations. */
    private fun build(
        holderId: String,
        audience: String?,
        configurationId: String,
        issuerScope: String?,
        proofPublicKey: ECPublicKey,
        proofKeyId: String,
    ): KeyAttestation {
        val now = Instant.now()
        val tokenExp = now.plusSeconds(properties.ka.tokenTtlSeconds.coerceAtMost(7 * 24 * 60 * 60))
        val statusExp = now.plusSeconds(properties.ka.minStatusMaintenanceDays * 24 * 60 * 60)
        val walletKey = walletKeyRepository.findByUserId(holderId)
            .orElseThrow { IllegalStateException("wallet key not found for holder '$holderId'") }
        val attestedJkt = Rfc7638JwkThumbprint.fromEcPublicKey(proofPublicKey)
        val attestationFingerprint = fingerprint(holderId, issuerScope, configurationId, attestedJkt)
        val status = statusManagementService.getOrAllocateStatus(holderId, issuerScope, attestationFingerprint)
        val payload = buildMap<String, Any> {
            put("iss", properties.ka.issuer)
            put("sub", holderId)
            audience?.let { put("aud", it) }
            put("iat", now.epochSecond)
            put("exp", tokenExp.epochSecond)
            put("jti", "ka-$attestationFingerprint")
            put("key_storage", properties.ka.keyStorage)
            put("user_authentication", properties.ka.userAuthentication)
            put(
                "certification",
                mapOf(
                    "scheme" to properties.ka.certificationScheme,
                    "assurance_level" to properties.ka.certificationAssuranceLevel,
                    "details" to properties.ka.certificationInfo,
                ),
            )
            put(
                "key_storage_status",
                mapOf(
                    "status" to mapOf(
                        "status_list" to mapOf(
                            "idx" to status.index,
                            "uri" to status.uri,
                        ),
                    ),
                    "exp" to statusExp.epochSecond,
                ),
            )
            put(
                "attested_keys",
                listOf(
                    mapOf(
                        "jwk" to rfc7638PublicJwk(proofPublicKey),
                        "proof_type" to "jwt",
                    ),
                ),
            )
            put("credential_configuration_id", configurationId)
        }
        val x5c = signingCertificateResolver.resolveSigningChain(walletKey)
        if (properties.ka.requireX5c && x5c.isEmpty()) {
            throw IllegalStateException("key attestation requires x5c but no certificate chain is available")
        }
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

    /** Signs a KA JWT with ES256 and embeds the resolved x5c certificate chain. */
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

    /** Derives a stable URL-safe fingerprint for status list reuse per attestation context. */
    private fun fingerprint(holderId: String, issuerId: String?, configurationId: String, attestedJkt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val material = "$holderId|${issuerId ?: "*"}|$configurationId|$attestedJkt"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(material.toByteArray()))
    }

    /** Builds a minimal RFC 7638 EC public JWK map for attested_keys claims. */
    private fun rfc7638PublicJwk(publicKey: ECPublicKey): Map<String, String> {
        val fieldSize = (publicKey.params.curve.field.fieldSize + 7) / 8
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return mapOf(
            "kty" to "EC",
            "crv" to "P-256",
            "x" to encoder.encodeToString(publicKeyCoordinate(publicKey.w.affineX, fieldSize)),
            "y" to encoder.encodeToString(publicKeyCoordinate(publicKey.w.affineY, fieldSize)),
        )
    }

    /** Left-pads or truncates an EC coordinate to the P-256 field width. */
    private fun publicKeyCoordinate(value: java.math.BigInteger, size: Int): ByteArray {
        val rawInput = value.toByteArray()
        val raw = if (rawInput.size > size) rawInput.copyOfRange(rawInput.size - size, rawInput.size) else rawInput
        if (raw.size == size) return raw
        return ByteArray(size - raw.size) + raw
    }
}
