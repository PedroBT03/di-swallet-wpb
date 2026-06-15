/**
 * Shared test helpers for signed issuer metadata.
 */

package di.swallet.wpb.issuance.trust

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Date

object SignedIssuerMetadataTestSupport {
    data class IssuerSigningMaterial(
        val keyPair: KeyPair,
        val certificate: X509Certificate,
    )

    /** Generates an ephemeral EC key pair and a self-signed X.509 certificate for signing issuer metadata JWTs. */
    /** Generates an EC key pair and self-signed X509 certificate for signing issuer metadata JWTs. */
    fun generateIssuerSigningMaterial(commonName: String = "OID4VCI Test Issuer"): IssuerSigningMaterial {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val subject = X500Name("CN=$commonName")
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.nanoTime()),
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(3600)),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return IssuerSigningMaterial(keyPair, certificate)
    }

    /** Signs an oauth-authz-req+jwt metadata token for [issuerId], optionally mutating claims before signing. */
    /** Builds and signs an ES256 oauth-authz-req+jwt for the given issuer, optionally mutating claims before signing. */
    fun signedMetadataJwt(
        issuerId: String,
        signingMaterial: IssuerSigningMaterial,
        mutateClaims: (JWTClaimsSet.Builder) -> Unit = {},
    ): String {
        val claimsBuilder = JWTClaimsSet.Builder()
            .issuer(issuerId)
            .claim("credential_issuer", issuerId)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
        mutateClaims(claimsBuilder)
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("oauth-authz-req+jwt"))
            .x509CertChain(listOf(Base64URL.encode(signingMaterial.certificate.encoded)))
            .build()
        val signed = SignedJWT(header, claimsBuilder.build())
        signed.sign(ECDSASigner(signingMaterial.keyPair.private as ECPrivateKey))
        return signed.serialize()
    }
}
