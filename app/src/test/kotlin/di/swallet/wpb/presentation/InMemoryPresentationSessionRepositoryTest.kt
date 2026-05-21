package di.swallet.wpb.presentation

import di.swallet.wpb.presentation.domain.*
import di.swallet.wpb.presentation.persistence.InMemoryPresentationSessionRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryPresentationSessionRepositoryTest {
    @Test
    fun `create and update should work and bump version`() {
        val repo = InMemoryPresentationSessionRepository()
        val id = UUID.randomUUID()
        val now = Instant.now()
        val meta = SessionMetadata(id, createdAt = now, updatedAt = now, expiresAt = now.plusSeconds(60))
        val session = PresentationSession(sessionMeta = meta, state = PresentationState.RECEIVED)

        val created = repo.create(session)
        assertEquals(0, created.sessionMeta.version)

        val updated = repo.update(created.copy(state = PresentationState.REQUEST_RESOLVED))
        assertEquals(1, updated.sessionMeta.version)
    }

    @Test
    fun `optimistic locking conflict should throw`() {
        val repo = InMemoryPresentationSessionRepository()
        val id = UUID.randomUUID()
        val now = Instant.now()
        val meta = SessionMetadata(id, createdAt = now, updatedAt = now, expiresAt = now.plusSeconds(60))
        val session = PresentationSession(sessionMeta = meta, state = PresentationState.RECEIVED)

        val created = repo.create(session)

        val staleCopy = created.copy(sessionMeta = created.sessionMeta.copy(version = created.sessionMeta.version))

        // perform a real update to bump the version
        val updated = repo.update(created.copy(state = PresentationState.REQUEST_RESOLVED))
        assertEquals(1, updated.sessionMeta.version)

        // attempt to update with stale copy (version 0) should fail
        try {
            repo.update(staleCopy.copy(state = PresentationState.POLICY_EVALUATED))
            fail("Expected version conflict")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
