package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface WalletUnitRepository : JpaRepository<WalletUnit, Long> {
    fun findByWalletId(walletId: String): Optional<WalletUnit>
    fun findFirstByHolderId(holderId: String): Optional<WalletUnit>
}
