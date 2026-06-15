/**
 * Persistence port for OID4VCI issuance session state.
 */

package di.swallet.wpb.issuance.persistence

import di.swallet.wpb.issuance.domain.IssuanceSession
import java.util.UUID

/** Stores and retrieves in-progress OID4VCI issuance sessions. */
interface IssuanceSessionRepository {
    /** Inserts a new session; the session id must not already exist. */
    fun create(session: IssuanceSession): IssuanceSession

    /** Persists changes with optimistic locking on the session version. */
    fun update(session: IssuanceSession): IssuanceSession

    /** Returns the session for the given id, or null when none exists. */
    fun findById(sessionId: UUID): IssuanceSession?
}
