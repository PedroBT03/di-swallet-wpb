package di.swallet.wpb.dpareport

import di.swallet.wpb.transactionlog.domain.Ts10DpaReport
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.Ts10TransactionResult
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

@Component
class DpaReportMapper {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

    fun toTransaction(
        dpaName: String?,
        dpaCountry: String?,
        reportChannel: DpaActionChannel?,
        reportContact: String?,
        now: Instant = Instant.now(),
    ): Ts10Transaction =
        Ts10Transaction(
            transactionIdentifier = UUID.randomUUID().toString(),
            time = formatter.format(now),
            transactionType = Ts10TransactionType.DPAReport.name,
            transactionResult = Ts10TransactionResult.Completed.name,
            dpaReport = Ts10DpaReport(
                dpaName = dpaName,
                dpaCountry = dpaCountry,
                reportChannel = reportChannel?.name,
                reportContact = reportContact,
            ),
        )
}
