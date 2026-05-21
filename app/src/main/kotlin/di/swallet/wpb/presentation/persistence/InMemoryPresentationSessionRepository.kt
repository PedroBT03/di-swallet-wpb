package di.swallet.wpb.presentation.persistence

import di.swallet.wpb.presentation.domain.PresentationSession
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Repository
class InMemoryPresentationSessionRepository : PresentationSessionRepository {
    private val sessions = ConcurrentHashMap<UUID, PresentationSession>()

    override fun create(session: PresentationSession): PresentationSession {
        val sessionId = session.sessionMeta.sessionId
        val existing = sessions.putIfAbsent(sessionId, session)
        require(existing == null) { "PresentationSession $sessionId already exists" }
        return session
    }

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

    override fun findById(sessionId: UUID): PresentationSession? = sessions[sessionId]
}
