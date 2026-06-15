/**
 * Tests key attestation validation against trust policies and certificate chains.
 */

package di.swallet.wpb.ka.validation

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.util.Base64
import di.swallet.wpb.config.OpenId4VciProperties
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.issuance.domain.KaStatusReference
import di.swallet.wpb.issuance.domain.KeyAttestation
import di.swallet.wpb.ka.trust.CertificateChainValidator
import di.swallet.wpb.ka.trust.KaCertificateFingerprint
import di.swallet.wpb.openid4vci.protocol.CredentialConfigurationDescriptor
import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import di.swallet.wpb.service.StatusListService
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.io.DefaultResourceLoader
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Date

class DefaultKeyAttestationValidationServiceTrustTest {

    /** OpenId4VciProperties with ka issuer, trust mode, and optional trust-anchor PEM paths preset for trust tests. */
    private fun properties(mode: String, anchors: String = "") = OpenId4VciProperties().apply {
        ka.issuer = "did:web:test.wpb"
        ka.trustMode = mode
        ka.trustAnchorPemPaths = anchors
    }

    /** Validation service with mocked non-revoked status list and a real certificate chain validator. */
    private fun service(properties: OpenId4VciProperties): DefaultKeyAttestationValidationService {
        val status = Mockito.mock(StatusListService::class.java).also {
            Mockito.`when`(it.isRevoked(Mockito.anyInt())).thenReturn(false)
        }
        return DefaultKeyAttestationValidationService(
            properties,
            status,
            CertificateChainValidator(DefaultResourceLoader()),
        )
    }

    /**
     * Self-signed x5c chain with valid ES256 JWT passes validateTrust under relaxed mode without configured anchors.
     */
    @Test
    fun `relaxed trust mode accepts self signed chain with valid JWS`() {
        val kp = keyPair()
        val certB64 = certBase64(kp)
        val props = properties(mode = "relaxed")
        val service = service(props)
        val att = attestation(jwt = signedJwt(kp, certB64, props, "pid_jwt", "https://issuer.example"), x5c = listOf(certB64))
        val config = CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC)
        val metadata = ResolvedIssuerMetadata("https://issuer.example")
        assertDoesNotThrow { service.validateTrust(att, config, metadata) }
    }

    /**
     * Strict mode without trust anchor PEM paths rejects the same self-signed attestation.
     */
    @Test
    fun `strict trust mode requires configured trust anchors`() {
        val kp = keyPair()
        val certB64 = certBase64(kp)
        val props = properties(mode = "strict")
        val service = service(props)
        val att = attestation(jwt = signedJwt(kp, certB64, props, "pid_jwt", "https://issuer.example"), x5c = listOf(certB64))
        val config = CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC)
        val metadata = ResolvedIssuerMetadata("https://issuer.example")
        assertThrows(KeyAttestationValidationException::class.java) {
            service.validateTrust(att, config, metadata)
        }
    }

    /**
     * Strict mode with leaf certificate written to a temp anchor PEM file accepts validateTrust.
     */
    @Test
    fun `strict trust mode accepts chain when trust anchor is configured`() {
        val kp = keyPair()
        val certB64 = certBase64(kp)
        val tempFile = Files.createTempFile("ka-anchor", ".pem")
        Files.writeString(
            tempFile,
            "-----BEGIN CERTIFICATE-----\n$certB64\n-----END CERTIFICATE-----\n",
        )

        val props = properties(mode = "strict", anchors = tempFile.toAbsolutePath().toString())
        val service = service(props)
        val att = attestation(jwt = signedJwt(kp, certB64, props, "pid_jwt", "https://issuer.example"), x5c = listOf(certB64))
        val config = CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC)
        val metadata = ResolvedIssuerMetadata("https://issuer.example")
        assertDoesNotThrow { service.validateTrust(att, config, metadata) }
    }

    /**
     * JWT iss claim does not match configured ka.issuer; validateTrust throws ka_iss_invalid.
     */
    @Test
    fun `trust validation fails on issuer mismatch`() {
        val kp = keyPair()
        val certB64 = certBase64(kp)
        val props = properties(mode = "relaxed")
        val service = service(props)
        val jwt = signedJwt(kp, certB64, props.copyForIssuer("did:web:wrong"), "pid_jwt", "https://issuer.example")
        val att = attestation(jwt = jwt, x5c = listOf(certB64))
        val config = CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC)
        val metadata = ResolvedIssuerMetadata("https://issuer.example")
        val ex = assertThrows(KeyAttestationValidationException::class.java) {
            service.validateTrust(att, config, metadata)
        }
        assertTrue(ex.code == "ka_iss_invalid")
    }

    /**
     * JWT credential_configuration_id does not match the requested configuration; throws ka_configuration_mismatch.
     */
    @Test
    fun `trust validation fails on credential configuration mismatch`() {
        val kp = keyPair()
        val certB64 = certBase64(kp)
        val props = properties(mode = "relaxed")
        val service = service(props)
        val att = attestation(jwt = signedJwt(kp, certB64, props, "pid_jwt", "https://issuer.example"), x5c = listOf(certB64))
        val ex = assertThrows(KeyAttestationValidationException::class.java) {
            service.validateTrust(att, CredentialConfigurationDescriptor("mdl", IssuanceCredentialFormat.SD_JWT_VC), ResolvedIssuerMetadata("https://issuer.example"))
        }
        assertTrue(ex.code == "ka_configuration_mismatch")
    }

    /**
     * JWT aud claim does not match issuer metadata credentialIssuerId; throws ka_aud_invalid.
     */
    @Test
    fun `trust validation fails on audience mismatch`() {
        val kp = keyPair()
        val certB64 = certBase64(kp)
        val props = properties(mode = "relaxed")
        val service = service(props)
        val att = attestation(jwt = signedJwt(kp, certB64, props, "pid_jwt", "https://issuer.example"), x5c = listOf(certB64))
        val ex = assertThrows(KeyAttestationValidationException::class.java) {
            service.validateTrust(att, CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC), ResolvedIssuerMetadata("https://other.example"))
        }
        assertTrue(ex.code == "ka_aud_invalid")
    }

    /**
     * Flipping a signature byte invalidates the JWS; validateTrust throws ka_signature_invalid.
     */
    @Test
    fun `trust validation fails when signature is tampered`() {
        val kp = keyPair()
        val certB64 = certBase64(kp)
        val props = properties(mode = "relaxed")
        val service = service(props)
        val jwt = signedJwt(kp, certB64, props, "pid_jwt", "https://issuer.example")
        val parts = jwt.split('.').toMutableList()
        val sig = java.util.Base64.getUrlDecoder().decode(parts[2])
        sig[0] = (sig[0].toInt() xor 0x01).toByte()
        parts[2] = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
        val tampered = parts.joinToString(".")
        val att = attestation(jwt = tampered, x5c = listOf(certB64))
        val ex = assertThrows(KeyAttestationValidationException::class.java) {
            service.validateTrust(att, CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC), ResolvedIssuerMetadata("https://issuer.example"))
        }
        assertTrue(ex.code == "ka_signature_invalid")
    }

    /**
     * JWT signed without x5c header and empty attestation x5c list; validateTrust throws ka_x5c_missing.
     */
    @Test
    fun `trust validation fails when x5c is missing`() {
        val kp = keyPair()
        val certB64 = certBase64(kp)
        val props = properties(mode = "relaxed")
        val service = service(props)
        val att = attestation(
            jwt = signedJwt(kp, certB64, props, "pid_jwt", "https://issuer.example", includeX5c = false),
            x5c = emptyList(),
        )
        val ex = assertThrows(KeyAttestationValidationException::class.java) {
            service.validateTrust(att, CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC), ResolvedIssuerMetadata("https://issuer.example"))
        }
        assertTrue(ex.code == "ka_x5c_missing")
    }

    /**
     * JWT iat set 600 seconds in the future; validateTrust throws ka_iat_invalid.
     */
    @Test
    fun `trust validation fails when iat is in the future`() {
        val kp = keyPair()
        val certB64 = certBase64(kp)
        val props = properties(mode = "relaxed")
        val service = service(props)
        val att = attestation(
            jwt = signedJwt(kp, certB64, props, "pid_jwt", "https://issuer.example", iat = Instant.now().plusSeconds(600), exp = Instant.now().plusSeconds(3600)),
            x5c = listOf(certB64),
        )
        val ex = assertThrows(KeyAttestationValidationException::class.java) {
            service.validateTrust(att, CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC), ResolvedIssuerMetadata("https://issuer.example"))
        }
        assertTrue(ex.code == "ka_iat_invalid")
    }

    /**
     * Malformed attestation JWT string; validateTrust throws ka_jwt_invalid.
     */
    @Test
    fun `trust validation fails for malformed JWT`() {
        val props = properties(mode = "relaxed")
        val service = service(props)
        val att = attestation(jwt = "invalid.jwt", x5c = emptyList())
        val ex = assertThrows(KeyAttestationValidationException::class.java) {
            service.validateTrust(att, CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC), ResolvedIssuerMetadata("https://issuer.example"))
        }
        assertTrue(ex.code == "ka_jwt_invalid")
    }

    /**
     * JWT payload omits iat; validateTrust throws ka_iat_missing.
     */
    @Test
    fun `trust validation fails when iat is missing`() {
        val kp = keyPair()
        val certB64 = certBase64(kp)
        val props = properties(mode = "relaxed")
        val service = service(props)
        val jwt = signedJwtWithClaims(
            kp,
            certB64,
            mapOf(
                "iss" to props.ka.issuer,
                "sub" to "holder-1",
                "aud" to "https://issuer.example",
                "exp" to Instant.now().plusSeconds(3600).epochSecond,
                "credential_configuration_id" to "pid_jwt",
            ),
        )
        val ex = assertThrows(KeyAttestationValidationException::class.java) {
            service.validateTrust(attestation(jwt, listOf(certB64)), CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC), ResolvedIssuerMetadata("https://issuer.example"))
        }
        assertTrue(ex.code == "ka_iat_missing")
    }

    /**
     * JWT payload omits exp; validateTrust throws ka_exp_missing.
     */
    @Test
    fun `trust validation fails when exp is missing`() {
        val kp = keyPair()
        val certB64 = certBase64(kp)
        val props = properties(mode = "relaxed")
        val service = service(props)
        val jwt = signedJwtWithClaims(
            kp,
            certB64,
            mapOf(
                "iss" to props.ka.issuer,
                "sub" to "holder-1",
                "aud" to "https://issuer.example",
                "iat" to Instant.now().epochSecond,
                "credential_configuration_id" to "pid_jwt",
            ),
        )
        val ex = assertThrows(KeyAttestationValidationException::class.java) {
            service.validateTrust(attestation(jwt, listOf(certB64)), CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC), ResolvedIssuerMetadata("https://issuer.example"))
        }
        assertTrue(ex.code == "ka_exp_missing")
    }

    /**
     * allowedX5cFingerprints contains the leaf cert SHA-256; validateTrust succeeds under relaxed mode.
     */
    @Test
    fun `fingerprint allow-list accepts matching leaf certificate`() {
        val kp = keyPair()
        val certB64 = certBase64(kp)
        val cert = parseCertificate(certB64)
        val props = properties(mode = "relaxed").apply {
            ka.allowedX5cFingerprints = KaCertificateFingerprint.sha256Hex(cert)
        }
        val service = service(props)
        val att = attestation(jwt = signedJwt(kp, certB64, props, "pid_jwt", "https://issuer.example"), x5c = listOf(certB64))
        val config = CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC)
        val metadata = ResolvedIssuerMetadata("https://issuer.example")
        assertDoesNotThrow { service.validateTrust(att, config, metadata) }
    }

    /**
     * allowedX5cFingerprints does not match the leaf cert; validateTrust throws ka_x5c_untrusted.
     */
    @Test
    fun `fingerprint allow-list rejects unknown leaf certificate`() {
        val kp = keyPair()
        val certB64 = certBase64(kp)
        val props = properties(mode = "relaxed").apply {
            ka.allowedX5cFingerprints = "DEADBEEF"
        }
        val service = service(props)
        val att = attestation(jwt = signedJwt(kp, certB64, props, "pid_jwt", "https://issuer.example"), x5c = listOf(certB64))
        val config = CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC)
        val metadata = ResolvedIssuerMetadata("https://issuer.example")
        val ex = assertThrows(KeyAttestationValidationException::class.java) {
            service.validateTrust(att, config, metadata)
        }
        assertTrue(ex.code == "ka_x5c_untrusted")
    }

    /**
     * Non-demo profile with enforceProductionTrustPolicy upgrades relaxed to strict; self-signed chain throws ka_pkix_untrusted.
     */
    @Test
    fun `production profile upgrades relaxed trust to strict without anchors`() {
        val kp = keyPair()
        val certB64 = certBase64(kp)
        val props = OpenId4VciProperties().apply {
            demoMode = false
            ka.issuer = "did:web:test.wpb"
            ka.trustMode = "relaxed"
            ka.enforceProductionTrustPolicy = true
        }
        val service = service(props)
        val att = attestation(jwt = signedJwt(kp, certB64, props, "pid_jwt", "https://issuer.example"), x5c = listOf(certB64))
        val config = CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC)
        val metadata = ResolvedIssuerMetadata("https://issuer.example")
        val ex = assertThrows(KeyAttestationValidationException::class.java) {
            service.validateTrust(att, config, metadata)
        }
        assertTrue(ex.code == "ka_pkix_untrusted")
    }

    /**
     * JWT header carries invalid x5c encoding; validateTrust throws ka_x5c_invalid.
     */
    @Test
    fun `trust validation fails on invalid x5c encoding`() {
        val kp = keyPair()
        val props = properties(mode = "relaxed")
        val service = service(props)
        val jwt = signedJwtWithClaims(
            kp,
            null,
            mapOf(
                "iss" to props.ka.issuer,
                "sub" to "holder-1",
                "aud" to "https://issuer.example",
                "iat" to Instant.now().epochSecond,
                "exp" to Instant.now().plusSeconds(3600).epochSecond,
                "credential_configuration_id" to "pid_jwt",
            ),
            customX5c = listOf("!!invalid!!"),
        )
        val ex = assertThrows(KeyAttestationValidationException::class.java) {
            service.validateTrust(attestation(jwt, emptyList()), CredentialConfigurationDescriptor("pid_jwt", IssuanceCredentialFormat.SD_JWT_VC), ResolvedIssuerMetadata("https://issuer.example"))
        }
        assertTrue(ex.code == "ka_x5c_invalid")
    }

    /** Wraps [jwt] and [x5c] into a KeyAttestation with placeholder metadata and one-hour validity windows. */
    private fun attestation(jwt: String, x5c: List<String>): KeyAttestation {
        val now = Instant.now()
        return KeyAttestation(
            jwt = jwt,
            keyId = "proof-key-1",
            keyStorage = "iso_18045_high",
            certification = "test-cert",
            attestedJkt = "proof-jkt",
            status = KaStatusReference("PRIMARY_LIST", 1, "/api/v1/wallet/status-lists/PRIMARY_LIST"),
            tokenExpiresAt = now.plusSeconds(3600),
            statusExpiresAt = now.plusSeconds(3600),
            issuedAt = now,
            x5c = x5c,
        )
    }

    /** Signs a keyattestation+jwt with standard iss/sub/aud/iat/exp and credential_configuration_id claims for [properties]. */
    private fun signedJwt(
        keyPair: KeyPair,
        certB64: String,
        properties: OpenId4VciProperties,
        configurationId: String,
        audience: String,
        iat: Instant = Instant.now(),
        exp: Instant = Instant.now().plusSeconds(3600),
        includeX5c: Boolean = true,
    ): String {
        val claims = mapOf(
            "iss" to properties.ka.issuer,
            "sub" to "holder-1",
            "aud" to audience,
            "iat" to iat.epochSecond,
            "exp" to exp.epochSecond,
            "credential_configuration_id" to configurationId,
        )
        return signedJwtWithClaims(
            keyPair = keyPair,
            certB64 = if (includeX5c) certB64 else null,
            claims = claims,
        )
    }

    /** Signs an ES256 keyattestation+jwt from arbitrary [claims], optionally embedding x5c in the header. */
    private fun signedJwtWithClaims(
        keyPair: KeyPair,
        certB64: String?,
        claims: Map<String, Any>,
        customX5c: List<String>? = null,
    ): String {
        val headerBuilder = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("keyattestation+jwt"))
            .keyID("signing-key")
        if (customX5c != null) {
            headerBuilder.x509CertChain(customX5c.map(::Base64))
        } else if (certB64 != null) {
            headerBuilder.x509CertChain(listOf(Base64(certB64)))
        }
        val header = headerBuilder.build()
        val jws = JWSObject(header, Payload(claims))
        jws.sign(ECDSASigner(keyPair.private as ECPrivateKey))
        return jws.serialize()
    }

    /** Generates a secp256r1 EC key pair for signing test KA JWTs. */
    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    /** Decodes a standard Base64 DER certificate into an X509Certificate. */
    private fun parseCertificate(base64Der: String): java.security.cert.X509Certificate {
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(
            java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(base64Der)),
        ) as java.security.cert.X509Certificate
    }

    /** Issues a self-signed EC certificate for [keyPair] and returns its DER encoding as standard Base64. */
    private fun certBase64(keyPair: KeyPair): String {
        val subject = X500Name("CN=KA-Test")
        val now = Date()
        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.currentTimeMillis()),
            now,
            Date(now.time + 24L * 3600 * 1000),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))
        return java.util.Base64.getEncoder().encodeToString(cert.encoded)
    }

    /** Clones ka trust settings from this properties instance while overriding ka.issuer to [issuer]. */
    private fun OpenId4VciProperties.copyForIssuer(issuer: String): OpenId4VciProperties =
        OpenId4VciProperties().also {
            it.ka.issuer = issuer
            it.ka.trustMode = this.ka.trustMode
            it.ka.trustAnchorPemPaths = this.ka.trustAnchorPemPaths
        }
}
