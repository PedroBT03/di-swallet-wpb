package di.swallet.wpb.service

import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Service managing the Revocation Bitstring.
 * Bit 0 = Active, Bit 1 = Revoked.
 */
@Service
class StatusListService {

    // A BitSet represents the bitstring efficiently in memory
    private val revocationBitstring = BitSet(10000) 
    private val nextIndex = AtomicInteger(0)

    /**
     * Assigns the next available index in the bitstring to a new key.
     */
    fun getNextRevocationIndex(): Int {
        return nextIndex.getAndIncrement()
    }

    /**
     * Revokes a key by setting its bit to 1.
     */
    fun revoke(index: Int) {
        revocationBitstring.set(index, true)
    }

    /**
     * Checks if a key is revoked. Returns true if bit is 1.
     */
    fun isRevoked(index: Int): Boolean {
        return revocationBitstring.get(index)
    }

    /**
     * Returns the raw bitstring as a Base64 string.
     * This is what a Verifier would download to check statuses.
     */
    fun getRawBitstring(): String {
        return Base64.getEncoder().encodeToString(revocationBitstring.toByteArray())
    }
}