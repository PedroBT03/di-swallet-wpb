package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface KaStatusIndexRepository : JpaRepository<KaStatusIndex, Long> {
    fun findByHolderIdAndIssuerScopeAndAttestationFingerprint(
        holderId: String,
        issuerScope: String,
        attestationFingerprint: String,
    ): Optional<KaStatusIndex>

    fun findAllByHolderId(holderId: String): List<KaStatusIndex>
}
