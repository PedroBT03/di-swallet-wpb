/**
 * Request-scoped access to the holder-supplied transaction log encryption key.
 */

package di.swallet.wpb.security

import di.swallet.wpb.transactionlog.crypto.TransactionLogDekMode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException

/**
 * Reads the optional X-Wallet-Log-Key header bound during FIDO2 authentication.
 */
@Component
class HolderLogKeyContext {

    /**
     * Returns the holder log key from the current request, if the header was supplied.
     */
    fun currentKey(): ByteArray? =
        currentRequest()?.getAttribute(WalletSecurityAttributes.HOLDER_LOG_KEY) as? ByteArray

    /**
     * Requires a 32-byte holder log key when transaction logs use holder DEK mode.
     */
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

    /**
     * Returns the current servlet request when running inside a web request context.
     */
    private fun currentRequest() =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
}
