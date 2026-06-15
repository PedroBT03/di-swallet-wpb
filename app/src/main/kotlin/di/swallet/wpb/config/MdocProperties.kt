/**
 * Configuration properties for mDoc issuance and session transcript handling.
 */

package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Binds `wpb.mdoc.*` settings for mDoc issuer keys and presentation transcript mode. */
@ConfigurationProperties(prefix = "wpb.mdoc")
class MdocProperties {
    /**
     * PEM path (classpath: or file:) for the simulator issuer signing key + cert.
     * Real PID Providers supply their own issuerAuth; this key is dev/CI only.
     */
    var issuerKeyPemPath: String = "classpath:mdoc/dev-issuer-key.pem"

    /**
     * When true and [issuerKeyPemPath] file is missing, generate a dev key pair at that path.
     * Never enable in production.
     */
    var autoGenerateIssuerKeyIfMissing: Boolean = false

    /**
     * Session transcript mode:
     * - `legacy-aud-nonce` — simplified map (CI default until HAIP verifier is ready)
     * - `openid4vp` — ISO 18013-5 / OID4VP OpenID4VPHandover
     * - `hybrid` — openid4vp when responseUri is present, else legacy
     */
    var sessionTranscriptMode: String = "legacy-aud-nonce"

    /** Fail presentation when holder HSM key alias is missing (non-demo paths). */
    var requireHolderKeyAlias: Boolean = true

    /**
     * Normalizes [sessionTranscriptMode] to lowercase for consistent comparisons.
     */
    fun sessionTranscriptModeNormalized(): String = sessionTranscriptMode.trim().lowercase()
}
