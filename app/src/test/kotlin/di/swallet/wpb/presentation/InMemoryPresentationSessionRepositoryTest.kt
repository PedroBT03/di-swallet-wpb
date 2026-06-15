/**
 * Tests in memory presentation session repository.
 */

package di.swallet.wpb.presentation

import di.swallet.wpb.presentation.domain.PresentationSession
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.presentation.domain.SessionMetadata
import di.swallet.wpb.presentation.persistence.InMemoryPresentationSessionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryPresentationSessionRepositoryTest {

    /** Builds a PresentationSession in RECEIVED state with a fresh UUID and one-minute expiry. */
    private fun newSession(): PresentationSession {
        val now = Instant.now()
        val meta = SessionMetadata(
            sessionId = UUID.randomUUID(),
            correlationId = UUID.randomUUID().toString(),
            createdAt = now,
            updatedAt = now,
            expiresAt = now.plusSeconds(60),
        )
        return PresentationSession(sessionMeta = meta, state = PresentationState.RECEIVED)
    }

    /**
     * A new session is created then updated with a new state.
     * Version starts at 0 and increments to 1 on update.
     */
    @Test
    fun `create and update bumps version`() {
        val repo = InMemoryPresentationSessionRepository()
        val created = repo.create(newSession())
        assertEquals(0, created.sessionMeta.version)

        val updated = repo.update(created.copy(state = PresentationState.REQUEST_RESOLVED))
        assertEquals(1, updated.sessionMeta.version)
    }

    /**
     * One copy of the session is updated while another retains the original version.
     * The stale update throws IllegalArgumentException.
     */
    @Test
    fun `optimistic locking rejects stale update`() {
        val repo = InMemoryPresentationSessionRepository()
        val created = repo.create(newSession())
        val stale = created.copy()

        repo.update(created.copy(state = PresentationState.REQUEST_RESOLVED))

        try {
            repo.update(stale.copy(state = PresentationState.POLICY_EVALUATED))
            fail<Unit>("Expected stale version to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
