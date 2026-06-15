/**
 * Manages the persistent revocation bitstring and random index allocation for credentials and keys.
 */

package di.swallet.wpb.service

import di.swallet.wpb.config.StatusListProperties
import di.swallet.wpb.domain.StatusList
import di.swallet.wpb.domain.StatusListRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.security.SecureRandom
import java.util.*

/**
 * Tracks revocation and allocation bits in a fixed-capacity status list persisted to the database.
 */
@Service
class StatusListService(
    private val statusListRepository: StatusListRepository,
    private val properties: StatusListProperties,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var revocationBitset: BitSet
    private lateinit var allocatedBitset: BitSet
    private val listId = "PRIMARY_LIST"
    private val secureRandom = SecureRandom()

    /**
     * Loads or creates the primary status list bitstrings from the database on application startup.
     */
    @PostConstruct
    fun init() {
        val capacity = properties.capacity
        val storedList = statusListRepository.findById(listId)
        if (storedList.isPresent) {
            val list = storedList.get()
            revocationBitset = BitSet.valueOf(list.bitstring)
            allocatedBitset = if (list.allocatedBitstring.isNotEmpty()) {
                BitSet.valueOf(list.allocatedBitstring)
            } else {
                BitSet(capacity)
            }
            if (list.capacity < capacity) {
                list.capacity = capacity
                syncToDatabase()
            }
            logger.info(
                "StatusList: Loaded existing list. capacity={}, allocated={}, revokedBits={}",
                list.capacity,
                allocatedBitset.cardinality(),
                revocationBitset.cardinality(),
            )
        } else {
            revocationBitset = BitSet(capacity)
            allocatedBitset = BitSet(capacity)
            syncToDatabase(nextIndexOverride = 0, capacityOverride = capacity)
            logger.info("StatusList: Created new primary status list with capacity={}", capacity)
        }
    }

    /**
     * Allocates a random free index within the configured capacity, retrying on collisions.
     */
    @Transactional
    fun allocateRandomIndex(): Int {
        val capacity = getCapacity()
        val maxAttempts = minOf(capacity, 10_000)
        repeat(maxAttempts) {
            val candidate = secureRandom.nextInt(capacity)
            if (!allocatedBitset.get(candidate)) {
                allocatedBitset.set(candidate)
                syncToDatabase()
                return candidate
            }
        }
        throw ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Status list capacity exhausted (capacity=$capacity)",
        )
    }

    /** @deprecated Use [allocateRandomIndex]; kept for transitional callers. */
    @Transactional
    fun getNextRevocationIndex(): Int = allocateRandomIndex()

    /**
     * Marks an index as allocated and revoked, then persists the updated bitstrings.
     */
    @Transactional
    fun revoke(index: Int) {
        requireIndexInRange(index)
        allocatedBitset.set(index)
        revocationBitset.set(index, true)
        syncToDatabase()
        logger.info("StatusList: Bit at index $index set to REVOKED and saved.")
    }

    /**
     * Returns true when the revocation bit is set for the given index.
     */
    fun isRevoked(index: Int): Boolean {
        if (index < 0 || index >= getCapacity()) return false
        return revocationBitset.get(index)
    }

    /**
     * Returns true when the index has been allocated to a credential or key.
     */
    fun isAllocated(index: Int): Boolean {
        if (index < 0 || index >= getCapacity()) return false
        return allocatedBitset.get(index)
    }

    /**
     * Returns the raw revocation bitstring bytes used to build status list JWTs.
     */
    fun getRawBitstringBytes(): ByteArray = revocationBitset.toByteArray()

    /**
     * Returns the revocation bitstring encoded as standard Base64.
     */
    fun getEncodedStatusList(): String =
        Base64.getEncoder().encodeToString(revocationBitset.toByteArray())

    /**
     * Returns the fixed identifier of the primary status list.
     */
    fun getListId(): String = listId

    /**
     * Returns the configured or stored capacity of the status list.
     */
    fun getCapacity(): Int =
        statusListRepository.findById(listId).map { it.capacity }.orElse(properties.capacity)

    /**
     * Returns the legacy next-index counter stored with the status list row.
     */
    fun getCurrentNextIndex(): Int =
        statusListRepository.findById(listId)
            .orElseThrow { RuntimeException("Status list not initialized") }
            .nextIndex

    /**
     * Returns how many indices are currently marked as allocated.
     */
    fun getAllocatedCount(): Int = allocatedBitset.cardinality()

    /**
     * Throws when an index is outside the current status list capacity range.
     */
    private fun requireIndexInRange(index: Int) {
        require(index in 0 until getCapacity()) { "Index $index out of range [0, ${getCapacity()})" }
    }

    /**
     * Persists the in-memory revocation and allocation bitstrings to the database.
     */
    private fun syncToDatabase(nextIndexOverride: Int? = null, capacityOverride: Int? = null) {
        val list = statusListRepository.findById(listId).orElse(StatusList(id = listId))
        list.bitstring = revocationBitset.toByteArray()
        list.allocatedBitstring = allocatedBitset.toByteArray()
        if (nextIndexOverride != null) {
            list.nextIndex = nextIndexOverride
        }
        if (capacityOverride != null) {
            list.capacity = capacityOverride
        }
        statusListRepository.save(list)
    }
}
