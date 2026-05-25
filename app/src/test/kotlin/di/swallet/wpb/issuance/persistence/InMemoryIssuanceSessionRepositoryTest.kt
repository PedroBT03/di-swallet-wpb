package di.swallet.wpb.issuance.persistence

import di.swallet.wpb.issuance.domain.IssuanceSession
import di.swallet.wpb.issuance.domain.IssuanceSessionMetadata
import di.swallet.wpb.issuance.domain.IssuanceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryIssuanceSessionRepositoryTest {

    private fun newSession(state: IssuanceState = IssuanceState.OFFER_RECEIVED, version: Long = 0L): IssuanceSession {
        val now = Instant.now()
        return IssuanceSession(
            sessionMeta = IssuanceSessionMetadata(
                sessionId = UUID.randomUUID(),
                holderId = "holder-1",
                correlationId = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now,
                expiresAt = now.plusSeconds(60),
                version = version,
            ),
            state = state,
        )
    }

    @Test
    fun `create and findById round-trip`() {
        val repo = InMemoryIssuanceSessionRepository()
        val session = newSession()
        repo.create(session)
        val found = repo.findById(session.sessionMeta.sessionId)
        assertNotNull(found)
        assertEquals(IssuanceState.OFFER_RECEIVED, found?.state)
    }

    @Test
    fun `findById returns null for unknown id`() {
        val repo = InMemoryIssuanceSessionRepository()
        assertNull(repo.findById(UUID.randomUUID()))
    }

    @Test
    fun `update bumps version optimistically`() {
        val repo = InMemoryIssuanceSessionRepository()
        val session = newSession()
        repo.create(session)
        val updated = repo.update(session.copy(state = IssuanceState.OFFER_RESOLVED))
        assertEquals(1L, updated.sessionMeta.version)
        assertEquals(IssuanceState.OFFER_RESOLVED, updated.state)
    }

    @Test
    fun `create rejects duplicate session id`() {
        val repo = InMemoryIssuanceSessionRepository()
        val session = newSession()
        repo.create(session)
        val ex = assertThrows(IllegalArgumentException::class.java) { repo.create(session) }
        assertTrue(ex.message!!.contains("already exists"))
    }

    @Test
    fun `update rejects version mismatch`() {
        val repo = InMemoryIssuanceSessionRepository()
        val session = newSession()
        repo.create(session)
        val updatedOnce = repo.update(session.copy(state = IssuanceState.OFFER_RESOLVED))
        // Caller still has the stale (version=0) session
        val ex = assertThrows(IllegalArgumentException::class.java) {
            repo.update(session.copy(state = IssuanceState.AUTHORIZED))
        }
        assertTrue(ex.message!!.contains("version conflict"))
        // The valid sequence works:
        val next = repo.update(updatedOnce.copy(state = IssuanceState.AUTHORIZATION_PREPARED))
        assertEquals(2L, next.sessionMeta.version)
    }

    @Test
    fun `update throws when session missing`() {
        val repo = InMemoryIssuanceSessionRepository()
        assertThrows(NoSuchElementException::class.java) { repo.update(newSession()) }
    }
}
