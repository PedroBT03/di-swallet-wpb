package di.swallet.wpb.transactionlog

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object Ts10InstantFormatter {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun format(instant: Instant = Instant.now()): String = formatter.format(instant)
}
