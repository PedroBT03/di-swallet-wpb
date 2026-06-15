/**
 * Spring Data repository for KA status index mappings.
 */

package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Loads KA status-list index rows by holder, issuer scope, and attestation fingerprint.
 */
interface KaStatusIndexRepository : JpaRepository<KaStatusIndex, Long> {
    /** Finds the status index for a holder, issuer scope, and attestation fingerprint. */
    fun findByHolderIdAndIssuerScopeAndAttestationFingerprint(
        holderId: String,
        issuerScope: String,
        attestationFingerprint: String,
    ): Optional<KaStatusIndex>

    /** Returns all KA status index rows for a holder. */
    fun findAllByHolderId(holderId: String): List<KaStatusIndex>
}
