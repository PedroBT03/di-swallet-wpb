/**
 * In-memory presentation session store for development and tests.
 */

package di.swallet.wpb.presentation.persistence

import di.swallet.wpb.presentation.domain.PresentationSession
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [PresentationSessionRepository] with optimistic versioning.
 */
@Repository
class InMemoryPresentationSessionRepository : PresentationSessionRepository {
    private val sessions = ConcurrentHashMap<UUID, PresentationSession>()

    /** Inserts a new session or fails when the session id is already present. */
    override fun create(session: PresentationSession): PresentationSession {
        val sessionId = session.sessionMeta.sessionId
        val existing = sessions.putIfAbsent(sessionId, session)
        require(existing == null) { "PresentationSession $sessionId already exists" }
        return session
    }

    /** Replaces a session only when its version matches the stored copy. */
    override fun update(session: PresentationSession): PresentationSession {
        val sessionId = session.sessionMeta.sessionId
        while (true) {
            val current = sessions[sessionId] ?: throw NoSuchElementException("PresentationSession $sessionId not found")
            require(current.sessionMeta.version == session.sessionMeta.version) {
                "PresentationSession $sessionId version conflict: expected ${current.sessionMeta.version}, got ${session.sessionMeta.version}"
            }
            val bumped = session.copy(
                sessionMeta = session.sessionMeta.copy(
                    version = session.sessionMeta.version + 1,
                    updatedAt = Instant.now(),
                ),
            )
            if (sessions.replace(sessionId, current, bumped)) {
                return bumped
            }
        }
    }

    /** Returns the stored session, or null when it does not exist. */
    override fun findById(sessionId: UUID): PresentationSession? = sessions[sessionId]
}
