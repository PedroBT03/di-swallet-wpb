/**
 * Formats instants as UTC TS10 timestamps (yyyy-MM-dd'T'HH:mm:ss'Z').
 */

package di.swallet.wpb.transactionlog

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** UTC timestamp formatter for TS10 transaction `time` fields. */
object Ts10InstantFormatter {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /** Formats an instant as a TS10 UTC timestamp string. */
    fun format(instant: Instant = Instant.now()): String = formatter.format(instant)
}
