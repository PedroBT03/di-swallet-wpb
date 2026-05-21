package di.swallet.wpb.observability

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class InMemorySessionEventStore : SessionEventStore {
    private val store = ConcurrentHashMap<UUID, MutableList<String>>()

    override fun record(sessionId: UUID, event: String) {
        val list = store.computeIfAbsent(sessionId) { mutableListOf() }
        list.add("${Instant.now()}: $event")
    }

    override fun getEvents(sessionId: UUID): List<String> = store[sessionId]?.toList() ?: emptyList()
}
