package di.swallet.wpb.ops

object BundledSigningKeyPolicy {
    fun violations(
        statusListSigningKeyPemPath: String,
        statusListAutoGenerate: Boolean,
        mdocIssuerKeyPemPath: String,
        mdocAutoGenerate: Boolean,
    ): List<String> {
        val out = mutableListOf<String>()
        if (statusListAutoGenerate) {
            out += "wpb.status-list.auto-generate-signing-key-if-missing"
        }
        if (isBundledDevSigningKeyPath(statusListSigningKeyPemPath, WeakSecretDefaults.KNOWN_DEV_STATUS_LIST_SIGNING_KEY_PATH)) {
            out += "wpb.status-list.signing-key-pem-path"
        }
        if (mdocAutoGenerate) {
            out += "wpb.mdoc.auto-generate-issuer-key-if-missing"
        }
        if (isBundledDevSigningKeyPath(mdocIssuerKeyPemPath, WeakSecretDefaults.KNOWN_DEV_MDOC_ISSUER_KEY_PATH)) {
            out += "wpb.mdoc.issuer-key-pem-path"
        }
        return out
    }

    private fun isBundledDevSigningKeyPath(path: String, knownDevClasspathPath: String): Boolean {
        val normalized = path.trim()
        if (normalized.isBlank()) return true
        if (normalized == knownDevClasspathPath) return true
        if (normalized.startsWith("classpath:") && normalized.contains("/dev-")) return true
        if (normalized.startsWith("classpath:")) return true
        return false
    }
}
