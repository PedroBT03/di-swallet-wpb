/**
 * Known weak default secrets used only in dev and test profiles.
 */

package di.swallet.wpb.ops

/**
 * Known weak defaults shipped for local development. Must not be used in staging/production.
 */
object WeakSecretDefaults {
    const val KNOWN_WEAK_HSM_PIN = "1234"
    const val KNOWN_WEAK_DB_PASSWORD = "tese2026"
    const val KNOWN_WEAK_DISCLOSURE_KEY = "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY="
    const val KNOWN_WEAK_TX_ENC_KEY = "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE="
    const val KNOWN_WEAK_TX_INT_KEY = "YmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmI="
    const val KNOWN_DEV_STATUS_LIST_SIGNING_KEY_PATH = "classpath:status-list/dev-signing-key.pem"
    const val KNOWN_DEV_MDOC_ISSUER_KEY_PATH = "classpath:mdoc/dev-issuer-key.pem"
}
