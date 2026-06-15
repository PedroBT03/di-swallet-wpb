/**
 * Spring Data repository for wallet unit lookup by public ID and holder.
 */

package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Loads wallet units by wallet ID or holder ID.
 */
interface WalletUnitRepository : JpaRepository<WalletUnit, Long> {
    /** Finds a wallet unit by its public wallet identifier. */
    fun findByWalletId(walletId: String): Optional<WalletUnit>

    /** Returns the first wallet unit associated with a holder, if any. */
    fun findFirstByHolderId(holderId: String): Optional<WalletUnit>
}
