package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface AttestedKeyRecordRepository : JpaRepository<AttestedKeyRecord, Long> {
    fun findByKeyId(keyId: String): Optional<AttestedKeyRecord>
    fun findByKeyAlias(keyAlias: String): Optional<AttestedKeyRecord>
    fun findByKeyAttestationId(keyAttestationId: Long): List<AttestedKeyRecord>
}
