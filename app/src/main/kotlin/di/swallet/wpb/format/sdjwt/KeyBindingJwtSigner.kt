package di.swallet.wpb.format.sdjwt

/**
 * Signs a Key Binding JWT (KB-JWT) for SD-JWT presentations.
 *
 * Extracted as a narrow interface so the VP builder can be unit-tested without
 * starting the full HSM stack (PKCS#11 / SoftHSM2).
 */
interface KeyBindingJwtSigner {
    fun signKeyBindingJwt(userId: String, payload: Map<String, Any>): String

    fun signKeyBindingJwtForKeyAlias(keyAlias: String, payload: Map<String, Any>): String =
        signKeyBindingJwt(keyAlias, payload)
}
