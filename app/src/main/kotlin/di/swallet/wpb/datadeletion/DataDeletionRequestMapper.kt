package di.swallet.wpb.datadeletion

import di.swallet.wpb.transactionlog.domain.Ts10ClaimInfo
import di.swallet.wpb.transactionlog.domain.Ts10DataDeletionRequest
import di.swallet.wpb.transactionlog.domain.Ts10Identifier
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.Ts10TransactionResult
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

@Component
class DataDeletionRequestMapper {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

    fun toTransaction(
        rpIdentifier: String?,
        rpName: String?,
        claims: List<Ts10ClaimInfo>,
        now: Instant = Instant.now(),
    ): Ts10Transaction =
        Ts10Transaction(
            transactionIdentifier = UUID.randomUUID().toString(),
            time = formatter.format(now),
            transactionType = Ts10TransactionType.DataDeletionRequest.name,
            transactionResult = Ts10TransactionResult.Completed.name,
            dataDeletionRequest = Ts10DataDeletionRequest(
                interactingPartyIdentifier = rpIdentifier?.let {
                    Ts10Identifier(type = "http://data.europa.eu/eudi/id/EUID", identifier = it)
                },
                interactingPartyName = rpName,
                listOfClaims = claims,
            ),
        )
}
