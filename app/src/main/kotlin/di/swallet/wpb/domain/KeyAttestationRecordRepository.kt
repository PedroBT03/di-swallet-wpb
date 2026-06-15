/**
 * Spring Data repository for key attestation record lookups.
 */

package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Loads key attestation records by attestation ID or wallet unit.
 */
interface KeyAttestationRecordRepository : JpaRepository<KeyAttestationRecord, Long> {
    /** Finds a key attestation by its stable attestation identifier. */
    fun findByAttestationId(attestationId: String): Optional<KeyAttestationRecord>

    /** Returns all key attestations issued for a wallet unit. */
    fun findByWalletUnitId(walletUnitId: Long): List<KeyAttestationRecord>
}
