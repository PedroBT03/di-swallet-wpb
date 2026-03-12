package di.swallet.wpb.service

import di.swallet.wpb.domain.StatusList
import di.swallet.wpb.domain.StatusListRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

/**
 * Service managing the persistent Status List Bitstring.
 * Implements the W3C/eIDAS 2.0 pattern where revocation is tracked via bits.
 * Bit 0 = ACTIVE, Bit 1 = REVOKED.
 */
@Service
class StatusListService(private val statusListRepository: StatusListRepository) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var internalBitset: BitSet
    private val listId = "PRIMARY_LIST"

    /**
     * Initializes the service by loading the bitstring from the database on startup.
     * This ensures the revocation state survives server restarts.
     */
    @PostConstruct
    fun init() {
        val storedList = statusListRepository.findById(listId)
        if (storedList.isPresent) {
            val list = storedList.get()
            // Convert the binary data from the DB back into a BitSet
            internalBitset = BitSet.valueOf(list.bitstring)
            logger.info("StatusList: Loaded existing list from database. Current bitset size: ${internalBitset.length()}")
        } else {
            // Create a new list if it's the first time running
            internalBitset = BitSet(100000)
            syncToDatabase(0)
            logger.info("StatusList: No list found. Created new primary status list in database.")
        }
    }

    /**
     * Allocates the next available index for a new wallet key.
     * Increments and persists the counter in the database.
     */
    @Transactional
    fun getNextRevocationIndex(): Int {
        val list = statusListRepository.findById(listId).orElseThrow { 
            RuntimeException("Status list not initialized") 
        }
        val index = list.nextIndex
        list.nextIndex = index + 1
        statusListRepository.save(list)
        return index
    }

    /**
     * Revokes a specific index by setting its bit to 1.
     * Persists the updated bitstring to the database.
     */
    @Transactional
    fun revoke(index: Int) {
        internalBitset.set(index, true)
        syncToDatabase()
        logger.info("StatusList: Bit at index $index set to 1 (REVOKED) and saved to database.")
    }

    /**
     * Checks if a key index is marked as revoked in the current bitset.
     */
    fun isRevoked(index: Int): Boolean {
        return internalBitset.get(index)
    }

    /**
     * Returns the Base64 encoded version of the raw bitstring.
     */
    fun getEncodedStatusList(): String {
        return Base64.getEncoder().encodeToString(internalBitset.toByteArray())
    }

    /**
     * Internal helper to save the current memory state into the PostgreSQL BYTEA column.
     */
    private fun syncToDatabase(nextIndexOverride: Int? = null) {
        val list = statusListRepository.findById(listId).orElse(StatusList(id = listId))
        list.bitstring = internalBitset.toByteArray()
        if (nextIndexOverride != null) {
            list.nextIndex = nextIndexOverride
        }
        statusListRepository.save(list)
    }
}