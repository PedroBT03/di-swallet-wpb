package di.swallet.wpb.observability

import java.util.UUID

interface SessionEventStore {
    fun record(sessionId: UUID, event: String)
    fun getEvents(sessionId: UUID): List<String>
}
