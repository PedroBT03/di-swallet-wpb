package di.swallet.wpb.security

/**
 * Allows HSM bootstrap operations (device registration, wallet init) without SCI/FIDO2.
 * Must only wrap code paths that are intentionally public pre-authentication.
 */
object WscaSciBootstrap {
    private val depth = ThreadLocal.withInitial { 0 }

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

    fun isActive(): Boolean = depth.get() > 0
}
