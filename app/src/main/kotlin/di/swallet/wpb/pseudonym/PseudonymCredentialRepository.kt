package di.swallet.wpb.pseudonym

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PseudonymCredentialRepository : JpaRepository<PseudonymCredential, UUID> {
    fun findByHolderId(holderId: String): List<PseudonymCredential>
    fun findByHolderIdAndRpId(holderId: String, rpId: String): List<PseudonymCredential>
    fun countByHolderIdAndRpId(holderId: String, rpId: String): Long
}
