package di.swallet.wpb.issuance.persistence

import di.swallet.wpb.issuance.domain.IssuanceSession
import java.util.UUID

interface IssuanceSessionRepository {
    fun create(session: IssuanceSession): IssuanceSession
    fun update(session: IssuanceSession): IssuanceSession
    fun findById(sessionId: UUID): IssuanceSession?
}
