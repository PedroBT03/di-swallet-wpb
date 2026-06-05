package di.swallet.wpb.format.mdoc

import com.authlete.cose.COSEEC2Key
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import java.io.StringReader
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object MdocCoseKeyMaterial {
    fun toCoseEc2PublicKey(publicKey: ECPublicKey): COSEEC2Key {
        val x = toFixed(publicKey.w.affineX.toByteArray(), 32)
        val y = toFixed(publicKey.w.affineY.toByteArray(), 32)
        return com.authlete.cose.COSEKeyBuilder()
            .ktyEC2()
            .ec2CrvP256()
            .ec2X(x)
            .ec2Y(y)
            .buildEC2Key()
    }

    fun toCoseEc2Key(privateKey: ECPrivateKey, publicKey: ECPublicKey): COSEEC2Key {
        val x = toFixed(publicKey.w.affineX.toByteArray(), 32)
        val y = toFixed(publicKey.w.affineY.toByteArray(), 32)
        val d = toFixed(privateKey.s.toByteArray(), 32)
        return com.authlete.cose.COSEKeyBuilder()
            .ktyEC2()
            .ec2CrvP256()
            .ec2X(x)
            .ec2Y(y)
            .ec2D(d)
            .buildEC2Key()
    }

    fun decodeEcPublicKey(publicKeyBase64: String): ECPublicKey {
        val bytes = Base64.getUrlDecoder().decode(publicKeyBase64.trim())
        val spec = X509EncodedKeySpec(bytes)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    fun loadEcKeyPairFromPem(pemBundle: String): java.security.KeyPair {
        val privateKeyType = when {
            pemBundle.contains("-----BEGIN EC PRIVATE KEY-----") -> "EC PRIVATE KEY"
            pemBundle.contains("-----BEGIN PRIVATE KEY-----") -> "PRIVATE KEY"
            else -> null
        }
        val privatePem = extractPemBlock(pemBundle, "EC PRIVATE KEY", "PRIVATE KEY")
        val certPem = extractPemBlock(pemBundle, "CERTIFICATE", null)
        val privateKey = if (privatePem != null && privateKeyType != null) {
            loadEcPrivateKeyFromPemBlock(privateKeyType, privatePem)
        } else {
            throw IllegalArgumentException("PEM bundle must contain an EC private key")
        }
        val publicKey = if (certPem != null) {
            val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
            val cert = certFactory.generateCertificate(Base64.getDecoder().decode(certPem).inputStream())
            cert.publicKey as ECPublicKey
        } else {
            throw IllegalArgumentException("PEM bundle must contain a certificate for the public key")
        }
        return java.security.KeyPair(publicKey, privateKey)
    }

    private fun loadEcPrivateKeyFromPemBlock(type: String, b64Body: String): ECPrivateKey {
        val pem = buildString {
            append("-----BEGIN $type-----\n")
            append(b64Body.chunked(64).joinToString("\n"))
            append("\n-----END $type-----\n")
        }
        val converter = JcaPEMKeyConverter()
        PEMParser(StringReader(pem)).use { parser ->
            return when (val parsed = parser.readObject()) {
                is PEMKeyPair -> converter.getPrivateKey(parsed.privateKeyInfo) as ECPrivateKey
                is PrivateKeyInfo -> converter.getPrivateKey(parsed) as ECPrivateKey
                is ECPrivateKey -> parsed
                else -> {
                    val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64Body))
                    KeyFactory.getInstance("EC").generatePrivate(spec) as ECPrivateKey
                }
            }
        }
    }

    private fun extractPemBlock(pem: String, type: String, alternateType: String?): String? {
        val pattern = Regex("-----BEGIN $type-----([\\s\\S]*?)-----END $type-----")
        val match = pattern.find(pem) ?: alternateType?.let {
            Regex("-----BEGIN $it-----([\\s\\S]*?)-----END $it-----").find(pem)
        }
        return match?.groupValues?.get(1)?.replace("\\s".toRegex(), "")
    }

    private fun toFixed(raw: ByteArray, size: Int): ByteArray {
        if (raw.size == size) return raw
        if (raw.size > size) return raw.copyOfRange(raw.size - size, raw.size)
        return ByteArray(size - raw.size) + raw
    }
}
