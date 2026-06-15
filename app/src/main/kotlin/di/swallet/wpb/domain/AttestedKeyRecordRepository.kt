/**
 * Spring Data repository for attested key record lookups.
 */

package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Loads attested keys by logical key ID, HSM alias, or parent key attestation.
 */
interface AttestedKeyRecordRepository : JpaRepository<AttestedKeyRecord, Long> {
    /** Finds an attested key by its generated key ID. */
    fun findByKeyId(keyId: String): Optional<AttestedKeyRecord>

    /** Finds an attested key by its HSM key alias. */
    fun findByKeyAlias(keyAlias: String): Optional<AttestedKeyRecord>

    /** Returns all keys attested under one key attestation record. */
    fun findByKeyAttestationId(keyAttestationId: Long): List<AttestedKeyRecord>
}
