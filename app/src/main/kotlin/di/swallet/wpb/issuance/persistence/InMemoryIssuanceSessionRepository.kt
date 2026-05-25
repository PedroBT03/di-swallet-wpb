package di.swallet.wpb.issuance.persistence

import di.swallet.wpb.issuance.domain.IssuanceSession
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory issuance session repository with optimistic locking semantics.
 *
 * Mirrors [di.swallet.wpb.presentation.persistence.InMemoryPresentationSessionRepository]
 * so the lifecycle/state-machine guarantees remain consistent across phases.
 */
@Repository
class InMemoryIssuanceSessionRepository : IssuanceSessionRepository {
    private val sessions = ConcurrentHashMap<UUID, IssuanceSession>()

    override fun create(session: IssuanceSession): IssuanceSession {
        val sessionId = session.sessionMeta.sessionId
        val existing = sessions.putIfAbsent(sessionId, session)
        require(existing == null) { "IssuanceSession $sessionId already exists" }
        return session
    }

    override fun update(session: IssuanceSession): IssuanceSession {
        val sessionId = session.sessionMeta.sessionId
        while (true) {
            val current = sessions[sessionId]
                ?: throw NoSuchElementException("IssuanceSession $sessionId not found")
            require(current.sessionMeta.version == session.sessionMeta.version) {
                "IssuanceSession $sessionId version conflict: expected ${current.sessionMeta.version}, got ${session.sessionMeta.version}"
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

    override fun findById(sessionId: UUID): IssuanceSession? = sessions[sessionId]
}
