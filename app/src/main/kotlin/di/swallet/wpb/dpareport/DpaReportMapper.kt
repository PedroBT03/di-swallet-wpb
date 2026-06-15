package di.swallet.wpb.dpareport

import di.swallet.wpb.transactionlog.domain.Ts10DpaReport
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.Ts10TransactionResult
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import di.swallet.wpb.transactionlog.Ts10InstantFormatter
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class DpaReportMapper {
    fun toTransaction(
        dpaName: String?,
        dpaCountry: String?,
        reportChannel: DpaActionChannel?,
        reportContact: String?,
        now: Instant = Instant.now(),
    ): Ts10Transaction =
        Ts10Transaction(
            transactionIdentifier = UUID.randomUUID().toString(),
            time = Ts10InstantFormatter.format(now),
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
