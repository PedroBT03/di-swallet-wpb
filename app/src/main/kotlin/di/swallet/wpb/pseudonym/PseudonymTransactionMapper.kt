package di.swallet.wpb.pseudonym

import di.swallet.wpb.config.PseudonymProperties
import di.swallet.wpb.transactionlog.domain.Ts10Identifier
import di.swallet.wpb.transactionlog.domain.Ts10Pseudonym
import di.swallet.wpb.transactionlog.domain.Ts10PseudonymDeletion
import di.swallet.wpb.transactionlog.domain.Ts10PseudonymGeneration
import di.swallet.wpb.transactionlog.domain.Ts10PseudonymousAuthentication
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.domain.Ts10TransactionResult
import di.swallet.wpb.transactionlog.domain.Ts10TransactionType
import di.swallet.wpb.transactionlog.Ts10InstantFormatter
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class PseudonymTransactionMapper(
    private val properties: PseudonymProperties,
) {
    fun toGeneration(credential: PseudonymCredential, publicKeyCose: String, now: Instant = Instant.now()): Ts10Transaction =
        Ts10Transaction(
            transactionIdentifier = UUID.randomUUID().toString(),
            time = Ts10InstantFormatter.format(now),
            transactionType = Ts10TransactionType.PseudonymGeneration.name,
            transactionResult = Ts10TransactionResult.Completed.name,
            pseudonymGeneration = Ts10PseudonymGeneration(
                pseudonym = ts10Pseudonym(credential, publicKeyCose),
            ),
        )

    fun toDeletion(credential: PseudonymCredential, publicKeyCose: String, now: Instant = Instant.now()): Ts10Transaction =
        Ts10Transaction(
            transactionIdentifier = UUID.randomUUID().toString(),
            time = Ts10InstantFormatter.format(now),
            transactionType = Ts10TransactionType.PseudonymDeletion.name,
            transactionResult = Ts10TransactionResult.Completed.name,
            pseudonymDeletion = Ts10PseudonymDeletion(
                pseudonym = ts10Pseudonym(credential, publicKeyCose),
            ),
        )

    fun toAuthentication(
        credential: PseudonymCredential,
        publicKeyCose: String,
        completed: Boolean = true,
        reason: String? = null,
        now: Instant = Instant.now(),
    ): Ts10Transaction =
        Ts10Transaction(
            transactionIdentifier = UUID.randomUUID().toString(),
            time = Ts10InstantFormatter.format(now),
            transactionType = Ts10TransactionType.PseudonymousAuthentication.name,
            transactionResult = if (completed) {
                Ts10TransactionResult.Completed.name
            } else {
                Ts10TransactionResult.NotCompleted.name
            },
            pseudonymousAuthentication = Ts10PseudonymousAuthentication(
                interactingPartyIdentifier = Ts10Identifier(type = "dns", identifier = credential.rpId),
                interactingPartyType = "ServiceProvider",
                interactingPartyName = credential.rpId,
                pseudonym = ts10Pseudonym(credential, publicKeyCose),
                reasonOfNoncompletion = reason,
            ),
        )

    private fun ts10Pseudonym(credential: PseudonymCredential, publicKeyCose: String): Ts10Pseudonym =
        Ts10Pseudonym(
            value = publicKeyCose,
            alias = if (properties.logIncludeAliasInExport) credential.alias else null,
        )
}
