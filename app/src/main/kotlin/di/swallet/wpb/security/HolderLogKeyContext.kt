package di.swallet.wpb.security

import di.swallet.wpb.transactionlog.crypto.TransactionLogDekMode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException

@Component
class HolderLogKeyContext {

    fun currentKey(): ByteArray? =
        currentRequest()?.getAttribute(WalletSecurityAttributes.HOLDER_LOG_KEY) as? ByteArray

    fun requireKey(dekMode: TransactionLogDekMode): ByteArray {
        if (dekMode != TransactionLogDekMode.HOLDER) {
            throw IllegalStateException("Holder log key is only required in holder dek mode")
        }
        return currentKey()
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "X-Wallet-Log-Key header is required when wpb.transaction-log.dek-mode=holder",
            )
    }

    private fun currentRequest() =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
}
