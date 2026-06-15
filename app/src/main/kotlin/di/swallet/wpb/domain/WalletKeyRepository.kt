/**
 * Spring Data repository for wallet key lookup by holder and HSM alias.
 */

package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Loads wallet key records by holder user ID or HSM key alias.
 */
@Repository
interface WalletKeyRepository : JpaRepository<WalletKey, Long> {
    /** Returns the wallet key registered for a holder, if present. */
    fun findByUserId(userId: String): Optional<WalletKey>

    /** Returns the wallet key metadata for a given HSM alias. */
    fun findByKeyAlias(keyAlias: String): Optional<WalletKey>
}
