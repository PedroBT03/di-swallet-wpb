package di.swallet.wpb.service.format

import com.nimbusds.jose.util.Base64URL
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

/**
 * Service responsible for SD-JWT (Selective Disclosure) logic.
 * Implements the salting and hashing of claims as required by the EUDI Wallet standards.
 */
@Service
class SdJwtService {

    private val secureRandom = SecureRandom()

    /**
     * Creates an SD-JWT Disclosure for a specific claim.
     * A disclosure is a Base64URL encoded JSON array: [salt, claim_name, claim_value]
     */
    fun createDisclosure(claimName: String, claimValue: Any): String {
        // 1. Generate a 128-bit random salt
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        val saltBase64 = Base64.getEncoder().encodeToString(salt)

        // 2. Format the disclosure: [salt, name, value]
        // This follows the IETF SD-JWT specification
        val disclosureArray = "[\"$saltBase64\", \"$claimName\", \"$claimValue\"]"
        
        // 3. Return the Base64URL encoded version
        return Base64URL.encode(disclosureArray).toString()
    }

    /**
     * Computes the SHA-256 hash of a disclosure.
     * This hash is what gets placed inside the signed JWT.
     */
    fun hashDisclosure(disclosure: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(disclosure.toByteArray())
        return Base64URL.encode(hash).toString()
    }
}