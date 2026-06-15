/**
 * SD-JWT disclosure salting, hashing, and nested object bundling utilities.
 */

package di.swallet.wpb.format.sdjwt

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.util.Base64URL
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Service responsible for SD-JWT (Selective Disclosure) logic.
 * Implements salting, hashing, and nested object bundling per SD-JWT VC.
 */
@Service
class SdJwtService(
    private val objectMapper: ObjectMapper,
) {

    private val secureRandom = SecureRandom()

    /** Container for disclosures and their digests produced from a claim map. */
    data class IssuedDisclosures(
        val disclosures: List<String>,
        val digests: List<String>,
    )

    /** Base64URL disclosure: `[salt, claim_name, claim_value]` (JSON-encoded). */
    fun createDisclosure(claimName: String, claimValue: Any): String {
        val salt = randomSaltBase64()
        val json = objectMapper.writeValueAsString(listOf(salt, claimName, claimValue))
        return Base64URL.encode(json.toByteArray()).toString()
    }

    /** Returns the base64url SHA-256 digest of a disclosure string. */
    fun hashDisclosure(disclosure: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(disclosure.toByteArray())
        return Base64URL.encode(hash).toString()
    }

    /**
     * Builds child disclosures plus a parent container disclosure whose value is
     * `{ "_sd": [<child digests>], "_sd_alg": "sha-256" }`.
     */
    fun createNestedObjectDisclosures(
        objectClaimName: String,
        children: Map<String, Any>,
    ): IssuedDisclosures {
        val childDisclosures = children.map { (name, value) -> createDisclosure(name, value) }
        val childDigests = childDisclosures.map { hashDisclosure(it) }.sorted()
        val containerValue = mapOf(
            "_sd" to childDigests,
            "_sd_alg" to "sha-256",
        )
        val parent = createDisclosure(objectClaimName, containerValue)
        return IssuedDisclosures(
            disclosures = childDisclosures + parent,
            digests = (childDigests + hashDisclosure(parent)).sorted(),
        )
    }

    /**
     * Flattens a claim map into SD-JWT disclosures: nested maps become object containers;
     * scalars and arrays become leaf disclosures.
     */
    fun disclosuresFromClaimMap(claims: Map<String, Any>): IssuedDisclosures {
        val all = mutableListOf<String>()
        val digests = mutableListOf<String>()
        claims.forEach { (key, value) ->
            when (value) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val nested = createNestedObjectDisclosures(key, value as Map<String, Any>)
                    all.addAll(nested.disclosures)
                    digests.addAll(nested.digests)
                }
                else -> {
                    val disc = createDisclosure(key, value)
                    all.add(disc)
                    digests.add(hashDisclosure(disc))
                }
            }
        }
        return IssuedDisclosures(all, digests.sorted())
    }

    /** Generates a random 16-byte salt encoded as standard base64. */
    private fun randomSaltBase64(): String {
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        return Base64.getEncoder().encodeToString(salt)
    }
}
