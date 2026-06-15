/**
 * Tests rp registry resolver.
 */

package di.swallet.wpb.presentation.registry

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import di.swallet.wpb.config.OpenId4VpProperties
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.CredentialQuery
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.Date

class RpRegistryResolverTest {

    /**
     * Fake client returns signed registry and check-intended-use JWTs for a matching SD-JWT query.
     * resolveAndValidate accepts with intendedUseChecked true and supervisoryAuthority in the record.
     */
    @Test
    fun `accepts signed registry record and official intended use check`() {
        val signerKeys = ecKeyPair()
        val verifierKeyPath = writePublicKeyPem(signerKeys)
        val props = OpenId4VpProperties().apply {
            registry.enabled = true
            registry.verificationKeyPemPaths = verifierKeyPath
            registry.expectedAudience = "wpb-wallet"
            registry.preferCheckIntendedUseEndpoint = true
        }
        val client = FakeRegistryClient(
            recordJwt = signRegistryJwt(
                signerKeys,
                data = registryRecordData(claimPath = "given_name"),
                audience = "wpb-wallet",
            ),
            checkJwt = signRegistryJwt(
                signerKeys,
                data = mapOf("isRegistered" to true),
                audience = "wpb-wallet",
            ),
        )
        val resolver = RpRegistryResolver(props, client, RpRegistrySignatureVerifier(props))

        val result = resolver.resolveAndValidate(
            rpIdentifier = "rp-123",
            credentialQueries = listOf(
                CredentialQuery(
                    id = "pid",
                    format = CredentialFormat.SD_JWT,
                    requestedClaimPaths = listOf(di.swallet.wpb.presentation.domain.ClaimPath.key("given_name")),
                ),
            ),
        )

        assertTrue(result is RegistryResolution.Accepted)
        val accepted = result as RegistryResolution.Accepted
        assertTrue(accepted.intendedUseChecked)
        assertTrue(accepted.record.rawDataJson.contains("supervisoryAuthority"))
    }

    /**
     * Registry record JWT is signed with a key not trusted by the configured verifier.
     * resolveAndValidate returns Rejected with a signature-related reason.
     */
    @Test
    fun `rejects when TS5 response signature is invalid`() {
        val trustedSigner = ecKeyPair()
        val badSigner = ecKeyPair()
        val verifierKeyPath = writePublicKeyPem(trustedSigner)
        val props = OpenId4VpProperties().apply {
            registry.enabled = true
            registry.verificationKeyPemPaths = verifierKeyPath
            registry.expectedAudience = "wpb-wallet"
        }
        val client = FakeRegistryClient(
            recordJwt = signRegistryJwt(
                badSigner,
                data = registryRecordData(claimPath = "given_name"),
                audience = "wpb-wallet",
            ),
            checkJwt = null,
        )
        val resolver = RpRegistryResolver(props, client, RpRegistrySignatureVerifier(props))

        val result = resolver.resolveAndValidate(
            rpIdentifier = "rp-123",
            credentialQueries = listOf(
                CredentialQuery(
                    "pid",
                    CredentialFormat.SD_JWT,
                    requestedClaimPaths = listOf(di.swallet.wpb.presentation.domain.ClaimPath.key("given_name")),
                ),
            ),
        )
        assertTrue(result is RegistryResolution.Rejected)
        val rejected = result as RegistryResolution.Rejected
        assertTrue(rejected.reason.contains("signature", ignoreCase = true))
    }

    /**
     * Local registry record matches the query but check-intended-use returns isRegistered false.
     * resolveAndValidate rejects with not registered and cites the check-intended-use endpoint.
     */
    @Test
    fun `official check-intended-use false prevails over local match`() {
        val signerKeys = ecKeyPair()
        val verifierKeyPath = writePublicKeyPem(signerKeys)
        val props = OpenId4VpProperties().apply {
            registry.enabled = true
            registry.verificationKeyPemPaths = verifierKeyPath
            registry.expectedAudience = "wpb-wallet"
            registry.preferCheckIntendedUseEndpoint = true
        }
        val client = FakeRegistryClient(
            recordJwt = signRegistryJwt(
                signerKeys,
                data = registryRecordData(claimPath = "given_name"),
                audience = "wpb-wallet",
            ),
            checkJwt = signRegistryJwt(
                signerKeys,
                data = mapOf("isRegistered" to false),
                audience = "wpb-wallet",
            ),
        )
        val resolver = RpRegistryResolver(props, client, RpRegistrySignatureVerifier(props))

        val result = resolver.resolveAndValidate(
            rpIdentifier = "rp-123",
            credentialQueries = listOf(
                CredentialQuery(
                    "pid",
                    CredentialFormat.SD_JWT,
                    requestedClaimPaths = listOf(di.swallet.wpb.presentation.domain.ClaimPath.key("given_name")),
                ),
            ),
        )
        assertTrue(result is RegistryResolution.Rejected)
        val rejected = result as RegistryResolution.Rejected
        assertTrue(rejected.reason.contains("not registered", ignoreCase = true))
        assertTrue(rejected.sourceEndpoint?.contains("check-intended-use") == true)
    }

    private class FakeRegistryClient(
        private val recordJwt: String,
        private val checkJwt: String?,
    ) : RpRegistryClient {
        /** Returns the configured record JWT as a successful registry lookup response. */
        override fun getByIdentifier(identifier: String): RegistryHttpResponse =
            RegistryHttpResponse(
                endpoint = "https://registry.example/wrp/$identifier",
                statusCode = 200,
                body = recordJwt,
                contentType = "application/jwt",
            )

        /** Returns the same record JWT for query-by-identifier registry lookups. */
        override fun queryByIdentifier(identifier: String): RegistryHttpResponse =
            RegistryHttpResponse(
                endpoint = "https://registry.example/wrp?identifier=$identifier",
                statusCode = 200,
                body = recordJwt,
                contentType = "application/jwt",
            )

        /** Returns the check-intended-use JWT when configured, or null to skip the official check. */
        override fun checkIntendedUse(
            rpIdentifier: String,
            intendedUseIdentifier: String?,
            credentialFormat: String?,
            claimPath: String?,
            credentialMeta: String?,
            policyUrl: String?,
        ): RegistryHttpResponse? {
            val body = checkJwt ?: return null
            return RegistryHttpResponse(
                endpoint = "https://registry.example/wrp/check-intended-use?rpidentifier=$rpIdentifier",
                statusCode = 200,
                body = body,
                contentType = "application/jwt",
            )
        }
    }

    /** Builds the TS5 registry record data map with intended use covering the given claim path. */
    private fun registryRecordData(claimPath: String): Map<String, Any> = mapOf(
        "identifier" to listOf(mapOf("identifier" to "rp-123", "type" to "http://data.europa.eu/eudi/id/EUID")),
        "tradeName" to "RP Example",
        "registryURI" to "https://registry.example",
        "supportURI" to listOf("https://rp.example/support"),
        "entitlements" to listOf("https://uri.etsi.org/19475/Entitlement/Service_Provider"),
        "supervisoryAuthority" to mapOf(
            "name" to "DPA PT",
            "country" to "PT",
            "email" to listOf("dpa@example.org"),
        ),
        "intendedUse" to listOf(
            mapOf(
                "intendedUseIdentifier" to "iu-1",
                "purpose" to listOf(mapOf("lang" to "en", "content" to "KYC flow")),
                "privacyPolicy" to listOf(mapOf("policyURI" to "https://rp.example/privacy", "type" to "Privacy Statement")),
                "credential" to listOf(
                    mapOf(
                        "format" to "dc+sd-jwt",
                        "meta" to "pid",
                        "claim" to listOf(mapOf("path" to claimPath)),
                    ),
                ),
            ),
        ),
    )

    /** Signs a registry JWT with ES256 embedding the data claim and expected audience. */
    private fun signRegistryJwt(
        keyPair: KeyPair,
        data: Any,
        audience: String,
    ): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer("https://registry.example")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(120)))
            .notBeforeTime(Date.from(now.minusSeconds(5)))
            .audience(audience)
            .jwtID("jti-${System.nanoTime()}")
            .claim("data", data)
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType.JWT)
            .build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(ECDSASigner(keyPair.private as ECPrivateKey))
        return jwt.serialize()
    }

    /** Generates an EC P-256 key pair for signing fake registry JWT responses. */
    private fun ecKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    /** Writes the public key to a temp PEM file and returns a file: URI for registry verification config. */
    private fun writePublicKeyPem(keyPair: KeyPair): String {
        val encoded = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val pem = "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----\n"
        val file = Files.createTempFile("registry-key", ".pem")
        Files.writeString(file, pem)
        return "file:${file.toAbsolutePath()}"
    }
}
