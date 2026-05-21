package di.swallet.wpb.presentation.persistence

import di.swallet.wpb.presentation.domain.PresentationSession
import java.util.UUID

interface PresentationSessionRepository {
    fun create(session: PresentationSession): PresentationSession

    fun update(session: PresentationSession): PresentationSession

    fun findById(sessionId: UUID): PresentationSession?
}
