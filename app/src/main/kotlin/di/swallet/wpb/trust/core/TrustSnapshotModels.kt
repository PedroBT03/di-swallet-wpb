/**
 * Protocol-agnostic trust snapshot models shared across VP, VCI, WIA, and KA flows.
 */

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

/** Trusted entity with binding rules and optional metadata. */
data class TrustedEntity(
    val entityId: String,
    val bindings: Set<TrustBindingRule> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
)

/** Loaded trust anchors, trusted entities, and snapshot freshness metadata. */
data class TrustSnapshot(
    val trustAnchors: List<X509Certificate>,
    val entities: Map<String, TrustedEntity>,
    val source: String,
    val loadedAt: Instant,
    val validUntil: Instant? = null,
)

/** Result of attempting to obtain a current trust snapshot. */
sealed interface TrustSnapshotAvailability {
    /** A trust snapshot is available for use. */
    data class Available(val snapshot: TrustSnapshot) : TrustSnapshotAvailability

    /** No trust snapshot could be loaded, with a human-readable reason. */
    data class Unavailable(val reason: String) : TrustSnapshotAvailability
}
