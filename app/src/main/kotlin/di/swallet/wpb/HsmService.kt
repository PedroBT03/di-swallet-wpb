package di.swallet.wpb

import org.springframework.stereotype.Service
import java.security.*
import java.security.spec.ECGenParameterSpec
import org.slf4j.LoggerFactory
import di.swallet.wpb.WalletKeyRepository

@Service
class HsmService(private val walletKeyRepository: WalletKeyRepository) {
    private val logger = LoggerFactory.getLogger(javaClass)
    // We initialize it directly or ensure the init block is "bulletproof"
    private val pkcs11Provider: Provider
    private val userPin = "1234"

    init {
        val config = """
            --name = SoftHSM2
            library = /usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so
            slotListIndex = 0
        """.trimIndent()

        try {
            val baseProvider = Security.getProvider("SunPKCS11")
                ?: throw RuntimeException("SunPKCS11 provider not found")
            
            pkcs11Provider = baseProvider.configure(config)
            Security.addProvider(pkcs11Provider)
            logger.info("WSCA: SunPKCS11 provider initialized")
        } catch (e: Exception) {
            logger.error("WSCA: Critical error initializing HSM: ${e.message}")
            throw e // Rethrowing ensures the app doesn't start with a broken HSM
        }
    }

    fun generateKey(): String {
        val keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider)
        keyStore.load(null, userPin.toCharArray())

        val keyPairGen = KeyPairGenerator.getInstance("EC", pkcs11Provider)
        keyPairGen.initialize(ECGenParameterSpec("secp256r1"))

        val keyPair = keyPairGen.generateKeyPair()
        logger.info("WSCA: New key generated inside HSM")
        
        // Return public key in Base64 for the API response
        return java.util.Base64.getEncoder().encodeToString(keyPair.public.encoded)
    }

    fun generateKeyForUser(userId: String): WalletKey {
        val keyStore = KeyStore.getInstance("PKCS11", pkcs11Provider)
        keyStore.load(null, "1234".toCharArray())

        // Use a unique alias for this user's key in the HSM
        val alias = "key-$userId-${System.currentTimeMillis()}"

        val keyPairGen = KeyPairGenerator.getInstance("EC", pkcs11Provider)
        keyPairGen.initialize(ECGenParameterSpec("secp256r1"))

        val keyPair = keyPairGen.generateKeyPair()
        val pubKeyBase64 = java.util.Base64.getEncoder().encodeToString(keyPair.public.encoded)

        // Save metadata to DB
        val walletKey = WalletKey(
            userId = userId,
            keyAlias = alias,
            publicKeyBase64 = pubKeyBase64
        )

        logger.info("WSCA: Saving metadata for key alias $alias in database")
        return walletKeyRepository.save(walletKey)
    }
}