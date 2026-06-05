package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface WiaStatusIndexRepository : JpaRepository<WiaStatusIndex, Long> {
    fun findByHolderIdAndIssuerScope(holderId: String, issuerScope: String): Optional<WiaStatusIndex>
    fun findAllByHolderId(holderId: String): List<WiaStatusIndex>
}
