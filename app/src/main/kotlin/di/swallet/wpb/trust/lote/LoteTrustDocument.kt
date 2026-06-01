package di.swallet.wpb.trust.lote

import java.time.Instant

data class LoteTrustDocument(
    val entities: List<LoteTrustEntityDocument>,
    val trustAnchorsPem: List<String> = emptyList(),
    val sequenceNumber: String? = null,
    val issueDate: Instant? = null,
    val validUntil: Instant? = null,
    val listType: String? = null,
    val schemeType: String? = null,
)

data class LoteTrustEntityDocument(
    val entityId: String? = null,
    val clientIds: List<String> = emptyList(),
    val sanDns: List<String> = emptyList(),
    val sanUri: List<String> = emptyList(),
    val subjectCn: List<String> = emptyList(),
    val certSha256: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)
