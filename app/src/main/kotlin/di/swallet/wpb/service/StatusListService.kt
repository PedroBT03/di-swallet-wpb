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
 * Service managing the persistent Status List Bitstring.
 * Implements the W3C/eIDAS 2.0 pattern where revocation is tracked via bits.
 * Bit 0 = ACTIVE, Bit 1 = REVOKED.
 *
 * Random index allocation within a fixed capacity (VCR_17).
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
     * Allocates a random index within the fixed capacity (VCR_17).
     * Retries on collision until a free slot is found.
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

    @Transactional
    fun revoke(index: Int) {
        requireIndexInRange(index)
        allocatedBitset.set(index)
        revocationBitset.set(index, true)
        syncToDatabase()
        logger.info("StatusList: Bit at index $index set to REVOKED and saved.")
    }

    fun isRevoked(index: Int): Boolean {
        if (index < 0 || index >= getCapacity()) return false
        return revocationBitset.get(index)
    }

    fun isAllocated(index: Int): Boolean {
        if (index < 0 || index >= getCapacity()) return false
        return allocatedBitset.get(index)
    }

    fun getRawBitstringBytes(): ByteArray = revocationBitset.toByteArray()

    fun getEncodedStatusList(): String =
        Base64.getEncoder().encodeToString(revocationBitset.toByteArray())

    fun getListId(): String = listId

    fun getCapacity(): Int =
        statusListRepository.findById(listId).map { it.capacity }.orElse(properties.capacity)

    fun getCurrentNextIndex(): Int =
        statusListRepository.findById(listId)
            .orElseThrow { RuntimeException("Status list not initialized") }
            .nextIndex

    fun getAllocatedCount(): Int = allocatedBitset.cardinality()

    private fun requireIndexInRange(index: Int) {
        require(index in 0 until getCapacity()) { "Index $index out of range [0, ${getCapacity()})" }
    }

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
