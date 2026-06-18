/**
 * Human-readable wallet credential type labels for UI and storage.
 */

package di.swallet.wpb.domain

/** Maps OID4VCI configuration ids and stored types to consistent wallet labels. */
object CredentialTypeLabels {

    /** Returns a holder-facing label (e.g. pid_jwt → PID). */
    fun displayLabel(storedType: String): String {
        if (storedType.isBlank()) return storedType
        if (isPidType(storedType)) return "PID"
        return humanizeIdentifier(storedType)
    }

    /** Canonical value persisted on [WalletCredential.credentialType]. */
    fun canonicalWalletType(configurationOrStoredType: String): String {
        if (configurationOrStoredType.isBlank()) return configurationOrStoredType
        if (isPidType(configurationOrStoredType)) return "PID"
        return configurationOrStoredType
    }

    /** True for PID SD-JWT configuration ids and stored aliases. */
    fun isPidType(type: String): Boolean {
        val normalized = type.lowercase().replace('-', '_')
        return normalized == "pid_jwt" ||
            normalized == "pid" ||
            normalized == "eu.europa.ec.eudi.pid_jwt_vc_json" ||
            normalized.contains("pid_jwt")
    }

    private fun humanizeIdentifier(value: String): String =
        value.split('_', '-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { char -> char.uppercase() } }
}
