package di.swallet.wpb.transactionlog.service

import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.PresentationState
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.mapper.CredentialDeletionTransactionMapper
import di.swallet.wpb.transactionlog.mapper.IssuanceTransactionMapper
import di.swallet.wpb.transactionlog.mapper.PresentationTransactionMapper
import di.swallet.wpb.ops.metrics.WpbMetrics
import di.swallet.wpb.transactionlog.mapper.SigningTransactionMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Normative TS10 transaction logger. Independent from in-memory SessionEvent/IssuanceEvent stores.
 */
@Component
class TransactionLogger(
    private val transactionLogRecorder: TransactionLogRecorder,
    private val presentationMapper: PresentationTransactionMapper,
    private val issuanceMapper: IssuanceTransactionMapper,
    private val deletionMapper: CredentialDeletionTransactionMapper,
    private val signingMapper: SigningTransactionMapper,
    private val wpbMetrics: WpbMetrics,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun logPresentationIfTerminal(context: PresentationContext) {
        val holderId = context.sessionMeta.holderId ?: return
        if (context.state !in TERMINAL_STATES) return
        val dedupeKey = "presentation:${context.sessionMeta.sessionId}:${context.state}"
        val transaction = presentationMapper.fromContext(context) ?: return
        runCatching { transactionLogRecorder.record(holderId, transaction, dedupeKey) }
            .onFailure { logger.warn("Failed to persist presentation transaction log for session {}", context.sessionMeta.sessionId, it) }
        wpbMetrics.recordPresentationTerminal(context)
    }

    fun logLegacyPresentation(
        holderId: String,
        credentialType: String,
        claimsRequested: List<String>,
        claimsPresented: List<String>,
        completed: Boolean,
        reason: String? = null,
    ) {
        val transaction = presentationMapper.fromLegacyPresentation(
            holderId = holderId,
            credentialType = credentialType,
            claimsRequested = claimsRequested,
            claimsPresented = claimsPresented,
            completed = completed,
            reason = reason,
        )
        runCatching { transactionLogRecorder.record(holderId, transaction) }
            .onFailure { logger.warn("Failed to persist legacy presentation transaction for holder {}", holderId, it) }
    }

    fun logIssuanceIfTerminal(context: IssuanceContext, issued: List<IssuedCredential> = context.issuedCredentials) {
        val holderId = context.sessionMeta.holderId ?: return
        if (!context.state.isTerminal && issued.isEmpty()) return
        val dedupeKey = "issuance:${context.sessionMeta.sessionId}:${context.state}:${issued.size}"
        val transaction = issuanceMapper.fromContext(context, issued) ?: return
        runCatching { transactionLogRecorder.record(holderId, transaction, dedupeKey) }
            .onFailure { logger.warn("Failed to persist issuance transaction log for session {}", context.sessionMeta.sessionId, it) }
        wpbMetrics.recordIssuanceTerminal(context)
    }

    fun logLegacyIssuance(holderId: String, credentialType: String, issuerName: String, issuerId: String) {
        val transaction = issuanceMapper.fromLegacyIssuance(holderId, credentialType, issuerName, issuerId)
        runCatching { transactionLogRecorder.record(holderId, transaction) }
            .onFailure { logger.warn("Failed to persist legacy issuance transaction for holder {}", holderId, it) }
    }

    fun logCredentialDeletion(credential: WalletCredential) {
        val transaction = deletionMapper.fromCredential(credential)
        runCatching { transactionLogRecorder.record(credential.userId, transaction) }
            .onFailure { logger.warn("Failed to persist credential deletion transaction for credential {}", credential.id, it) }
    }

    fun logSigning(holderId: String, payload: ByteArray, algorithm: String, completed: Boolean, reason: String? = null) {
        val transaction = signingMapper.fromSignOperation(payload, algorithm, completed, reason)
        runCatching { transactionLogRecorder.record(holderId, transaction) }
            .onFailure { logger.warn("Failed to persist signing transaction for holder {}", holderId, it) }
    }

    fun logDataDeletionRequest(holderId: String, transaction: Ts10Transaction) {
        runCatching { transactionLogRecorder.record(holderId, transaction) }
            .onFailure { logger.warn("Failed to persist data deletion request transaction for holder {}", holderId, it) }
    }

    fun logDpaReport(holderId: String, transaction: Ts10Transaction) {
        runCatching { transactionLogRecorder.record(holderId, transaction) }
            .onFailure { logger.warn("Failed to persist DPA report transaction for holder {}", holderId, it) }
    }

    fun logPseudonymGeneration(holderId: String, transaction: Ts10Transaction) {
        runCatching { transactionLogRecorder.record(holderId, transaction) }
            .onFailure { logger.warn("Failed to persist pseudonym generation transaction for holder {}", holderId, it) }
    }

    fun logPseudonymDeletion(holderId: String, transaction: Ts10Transaction) {
        runCatching { transactionLogRecorder.record(holderId, transaction) }
            .onFailure { logger.warn("Failed to persist pseudonym deletion transaction for holder {}", holderId, it) }
    }

    fun logPseudonymousAuthentication(holderId: String, transaction: Ts10Transaction) {
        runCatching { transactionLogRecorder.record(holderId, transaction) }
            .onFailure { logger.warn("Failed to persist pseudonymous authentication transaction for holder {}", holderId, it) }
    }

    companion object {
        private val TERMINAL_STATES = setOf(
            PresentationState.DISPATCHED,
            PresentationState.FAILED,
            PresentationState.REJECTED,
            PresentationState.EXPIRED,
        )
    }
}
