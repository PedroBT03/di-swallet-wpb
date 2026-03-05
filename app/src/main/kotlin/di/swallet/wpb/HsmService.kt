package di.swallet.wpb

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.*

/**
 * WSCA (Wallet Secure Cryptographic Application) implementation.
 * Manages the interaction with the hardware security module (Remote WSCD).
 */
@Service
class HsmService(
    private val walletKeyRepository: WalletKeyRepository,
    private val hsmProperties: HsmProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var pkcs11Provider: Provider = Security.getProvider("SunPKCS11")
        ?: throw RuntimeException("SunPKCS11 provider not found")
    private val pin = "1234"

    /**
     * Initializes the SunPKCS11 provider using the library path defined in configuration.
     * Also registers BouncyCastle for certificate utilities.
     */
    init {
        Security.addProvider(BouncyCastleProvider())

        val config = """
            --name = SoftHSM2
            library = ${hsmProperties.library}
            slotListIndex = 0
        """.trimIndent()

        try {
            val baseProvider = Security.getProvider("SunPKCS11")
                ?: throw RuntimeException("SunPKCS11 provider not found")
            
            pkcs11Provider = baseProvider.configure(config)
            Security.addProvider(pkcs11Provider)
            logger.info("WSCA: HSM initialized using library: ${hsmProperties.library}")
        } catch (e: Exception) {
            logger.error("WSCA: Critical error initializing HSM: ${e.message}")
            throw e
        }
    }

    /**
     * Generates an EC KeyPair inside the HSM and stores its metadata.
     * The private key is linked to a self-signed certificate for HSM storage compatibility.
     */
    fun generateKeyForUser(userId: String): WalletKey {
        try {
            val keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider)
            keyStore.load(null, pin.toCharArray())

            val alias = "key-$userId-${System.currentTimeMillis()}"

            // 1. Generate KeyPair inside HSM
            val keyPairGen = KeyPairGenerator.getInstance("EC", pkcs11Provider)
            keyPairGen.initialize(ECGenParameterSpec("secp256r1"))
            val keyPair = keyPairGen.generateKeyPair()

            // 2. Generate required certificate chain
            val chain = arrayOf(generateSelfSignedCertificate(keyPair))

            // 3. Persist key handle in HSM
            keyStore.setKeyEntry(alias, keyPair.private, null, chain)

            // 4. Save metadata to database
            val pubKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
            val walletKey = WalletKey(userId = userId, keyAlias = alias, publicKeyBase64 = pubKeyBase64)

            logger.info("WSCA: Key created for user $userId with alias $alias")
            return walletKeyRepository.save(walletKey)
        } catch (e: Exception) {
            logger.error("WSCA: Key generation failed for user $userId: ${e.message}")
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "HSM Error: ${e.message}")
        }
    }

    /**
     * Performs a digital signature on the provided data using the user's private key in the HSM.
     */
    fun signData(userId: String, dataToSign: ByteArray): ByteArray {
        val walletKey = walletKeyRepository.findByUserId(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Key not found") }

        try {
            val keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider)
            keyStore.load(null, pin.toCharArray())

            val privateKey = keyStore.getKey(walletKey.keyAlias, null) as? PrivateKey
                ?: throw RuntimeException("Key alias ${walletKey.keyAlias} not found in HSM")

            val signature = Signature.getInstance("SHA256withECDSA", pkcs11Provider)
            signature.initSign(privateKey)
            signature.update(dataToSign)

            logger.info("WSCA: Document signed inside HSM boundary for user $userId")
            return signature.sign()
        } catch (e: Exception) {
            logger.error("WSCA: Signing failed: ${e.message}")
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Signing failed: ${e.message}")
        }
    }

    /**
     * Finds the wallet metadata for a specific user ID.
     */
    fun getUserKey(userId: String): WalletKey {
        return walletKeyRepository.findByUserId(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User wallet not found") }
    }

    /**
     * Generates a temporary X.509 certificate to allow the KeyStore to index the private key.
     */
    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val issuer = X500Name("CN=DI-Swallet-Internal")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + (365L * 24 * 60 * 60 * 1000))

        val certBuilder = JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, issuer, keyPair.public
        )

        // The HSM performs the signing operation for the certificate
        val signer = JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider(pkcs11Provider)
            .build(keyPair.private)

        return JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))
    }
}