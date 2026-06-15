/**
 * Spring Data repository for the primary status list persistence row.
 */

package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * Loads and saves the singleton primary status list entity by list ID.
 */
@Repository
interface StatusListRepository : JpaRepository<StatusList, String>
