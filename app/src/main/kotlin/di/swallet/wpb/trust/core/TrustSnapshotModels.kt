package di.swallet.wpb.trust.core

import java.security.cert.X509Certificate
import java.time.Instant

/**
 * Neutral trust key/value rule to avoid protocol-specific coupling.
 *
 * Example keys:
 * - client_id
 * - san_dns
 * - san_uri
 * - subject_cn
 * - cert_sha256
 */
data class TrustBindingRule(
    val key: String,
    val value: String,
)

/**
 * Protocol-agnostic trusted entity.
 */
data class TrustedEntity(
    val entityId: String,
    val bindings: Set<TrustBindingRule> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Reusable trust snapshot that can be shared across VP, VCI, WIA, and KA.
 */
data class TrustSnapshot(
    val trustAnchors: List<X509Certificate>,
    val entities: Map<String, TrustedEntity>,
    val source: String,
    val loadedAt: Instant,
    val validUntil: Instant? = null,
)

sealed interface TrustSnapshotAvailability {
    data class Available(val snapshot: TrustSnapshot) : TrustSnapshotAvailability

    data class Unavailable(val reason: String) : TrustSnapshotAvailability
}
