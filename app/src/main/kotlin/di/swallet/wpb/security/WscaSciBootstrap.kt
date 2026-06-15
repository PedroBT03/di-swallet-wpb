/**
 * Thread-local marker for pre-authentication bootstrap paths that bypass SCI enforcement.
 */

package di.swallet.wpb.security

/**
 * Allows HSM bootstrap operations (device registration, wallet init) without SCI/FIDO2.
 * Must only wrap code paths that are intentionally public pre-authentication.
 */
object WscaSciBootstrap {
    private val depth = ThreadLocal.withInitial { 0 }

    /**
     * Runs the block while SCI boundary checks are temporarily disabled.
     */
    fun <T> allow(block: () -> T): T {
        depth.set(depth.get() + 1)
        return try {
            block()
        } finally {
            val next = depth.get() - 1
            if (next <= 0) {
                depth.remove()
            } else {
                depth.set(next)
            }
        }
    }

    /**
     * Returns true when the current thread is inside a bootstrap allow block.
     */
    fun isActive(): Boolean = depth.get() > 0
}
