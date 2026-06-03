package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface KeyAttestationRecordRepository : JpaRepository<KeyAttestationRecord, Long> {
    fun findByAttestationId(attestationId: String): Optional<KeyAttestationRecord>
    fun findByWalletUnitId(walletUnitId: Long): List<KeyAttestationRecord>
}
