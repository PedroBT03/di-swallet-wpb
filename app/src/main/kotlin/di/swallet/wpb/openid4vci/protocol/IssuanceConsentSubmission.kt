package di.swallet.wpb.openid4vci.protocol

import kotlinx.serialization.Serializable

@Serializable
data class IssuanceConsentSubmission(
    val sessionId: String,
    val holderId: String,
    val granted: Boolean,
    val reason: String? = null,
)
