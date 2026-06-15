/**
 * Narrow port for signing SD-JWT key-binding JWTs without the full HSM stack.
 */

package di.swallet.wpb.format.sdjwt

/**
 * Signs a Key Binding JWT (KB-JWT) for SD-JWT presentations.
 *
 * Extracted as a narrow interface so the VP builder can be unit-tested without
 * starting the full HSM stack (PKCS#11 / SoftHSM2).
 */
interface KeyBindingJwtSigner {
    /** Signs a KB-JWT for the holder identified by user id. */
    fun signKeyBindingJwt(userId: String, payload: Map<String, Any>): String

    /** Signs a KB-JWT with an explicit HSM key alias when credential binding is known. */
    fun signKeyBindingJwtForKeyAlias(keyAlias: String, payload: Map<String, Any>): String =
        signKeyBindingJwt(keyAlias, payload)
}
