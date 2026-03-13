package di.swallet.wpb.service

import di.swallet.wpb.domain.UserDevice
import di.swallet.wpb.domain.UserDeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.*

@Service
class Fido2Service(private val userDeviceRepository: UserDeviceRepository) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun registerDevice(userId: String, credentialId: String, publicKeyBase64: String): UserDevice {
        val existing = userDeviceRepository.findByCredentialId(credentialId)
        if (existing.isPresent) {
            return existing.get()
        }
        
        val device = UserDevice(userId = userId, credentialId = credentialId, publicKeyBase64 = publicKeyBase64)
        logger.info("FIDO2: Registered new device $credentialId for user $userId")
        return userDeviceRepository.save(device)
    }

    /**
     * Performs ECDSA signature verification using the Java Cryptography Architecture (JCA).
     */
    fun verifyDeviceSignature(userId: String, credentialId: String, challenge: String, signatureBase64: String): Boolean {
        val device = userDeviceRepository.findByCredentialId(credentialId)
            .filter { it.userId == userId }
            .orElse(null) ?: return false

        return try {
            val decoder = Base64.getUrlDecoder()
            
            // 1. Load the Public Key
            val keyBytes = decoder.decode(device.publicKeyBase64.trim())
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey = keyFactory.generatePublic(keySpec)

            // 2. Initialize the Verifier
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(challenge.toByteArray())

            // 3. Verify the signature
            val signatureBytes = decoder.decode(signatureBase64.trim())
            val isVerified = verifier.verify(signatureBytes)

            if (isVerified) {
                device.signatureCount++
                userDeviceRepository.save(device)
                logger.info("SecurityPolicy: Cryptographic signature verified for device $credentialId")
            }
            isVerified
        } catch (e: Exception) {
            logger.error("SecurityPolicy: Signature verification failed: ${e.message}")
            false
        }
    }
}