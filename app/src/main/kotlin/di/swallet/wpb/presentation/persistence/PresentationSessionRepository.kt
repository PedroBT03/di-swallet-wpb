/**
 * Persistence port for OpenID4VP presentation sessions.
 */

package di.swallet.wpb.presentation.persistence

import di.swallet.wpb.presentation.domain.PresentationSession
import java.util.UUID

/**
 * Stores and retrieves presentation session snapshots with optimistic versioning.
 */
interface PresentationSessionRepository {
    /**
     * Persists a new session and fails if the session id already exists.
     */
    fun create(session: PresentationSession): PresentationSession

    /**
     * Updates an existing session and bumps its version on success.
     */
    fun update(session: PresentationSession): PresentationSession

    /**
     * Returns the session for the given id, or null when not found.
     */
    fun findById(sessionId: UUID): PresentationSession?
}
