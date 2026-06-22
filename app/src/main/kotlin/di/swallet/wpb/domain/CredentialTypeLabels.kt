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
        if (isMdlType(storedType)) return "Driving licence"
        return humanizeIdentifier(storedType)
    }

    /** Canonical value persisted on [WalletCredential.credentialType]. */
    fun canonicalWalletType(configurationOrStoredType: String): String {
        if (configurationOrStoredType.isBlank()) return configurationOrStoredType
        if (isPidType(configurationOrStoredType)) return "PID"
        if (isMdlType(configurationOrStoredType)) return "MDL"
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

    fun isMdlType(type: String): Boolean {
        val normalized = type.lowercase().replace('-', '_')
        return normalized.contains("mdl") ||
            normalized.contains("mdoc_mdl") ||
            normalized.contains("driving_licence") ||
            normalized.contains("driver_license") ||
            normalized == "mdl"
    }

    /** True when two stored types represent the same logical document (one PID or one mDL per holder). */
    fun sameDocumentFamily(left: String, right: String): Boolean {
        if (isPidType(left) && isPidType(right)) return true
        if (isMdlType(left) && isMdlType(right)) return true
        return left.equals(right, ignoreCase = true)
    }

    private fun humanizeIdentifier(value: String): String =
        value.split('_', '-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { char -> char.uppercase() } }
}
