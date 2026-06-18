/**
 * Servlet-thread holder binding for FIDO2-authenticated requests.
 */

package di.swallet.wpb.security

/**
 * Binds the authenticated holder id on the request thread during interceptor preHandle.
 * Suspend MVC can resume on a pool thread without RequestContextHolder; the guard runs
 * on the servlet thread before any coroutine suspension when controllers are blocking.
 */
object AuthenticatedHolderRequestBinder {
    private val HOLDER_ID = ThreadLocal<String?>()

    /** Stores the holder id for the current servlet worker thread. */
    fun bind(holderId: String) {
        HOLDER_ID.set(holderId)
    }

    /** Returns the holder id bound on this thread, if any. */
    fun currentHolderId(): String? = HOLDER_ID.get()

    /** Clears the binding when the HTTP request completes. */
    fun clear() {
        HOLDER_ID.remove()
    }
}
