/**
 * Holder consent submission for OID4VCI issuance sessions.
 */

package di.swallet.wpb.openid4vci.protocol

import kotlinx.serialization.Serializable

/** Consent decision submitted by the holder for an issuance session. */
@Serializable
data class IssuanceConsentSubmission(
    val sessionId: String,
    val holderId: String,
    val granted: Boolean,
    val reason: String? = null,
)
