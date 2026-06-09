package di.swallet.wpb.conformance.support

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.format.sdjwt.SdJwtService
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.PresentationTestSupport
import di.swallet.wpb.presentation.domain.ClaimPath
import di.swallet.wpb.service.format.DisclosureCipherService
import di.swallet.wpb.config.WalletProperties

object ConformanceCredentialFactory {
    private val objectMapper = ObjectMapper()
    private val sdJwtService = SdJwtService(objectMapper)
    private val disclosureCipher = DisclosureCipherService(WalletProperties())

    fun walletCredentialsForRequest(
        request: ResolvedAuthorizationRequest,
        holderId: String,
        credentialId: Long = 1L,
    ): List<WalletCredential> {
        val queries = request.requirements.credentialQueries
        if (queries.isEmpty()) {
            return listOf(PresentationTestSupport.sdJwtCredential(credentialId, holderId, "given_name", "Pedro"))
        }
        val disclosures = linkedSetOf<String>()
        queries.flatMap { it.requestedClaimPaths }.forEach { path ->
            disclosures.addAll(disclosuresForPath(path))
        }
        if (disclosures.isEmpty()) {
            disclosures.add(sdJwtService.createDisclosure("given_name", "Pedro"))
        }
        return listOf(
            WalletCredential(
                id = credentialId,
                userId = holderId,
                credentialType = "PID",
                encodedData = "HEAD.PAYLOAD.SIG",
                encryptedDisclosures = disclosureCipher.encrypt(disclosures.toList()),
            ),
        )
    }

    private fun disclosuresForPath(path: ClaimPath): List<String> = when {
        path.segments.size == 1 && path.segments[0] is di.swallet.wpb.presentation.domain.ClaimPathSegment.Key -> {
            val key = (path.segments[0] as di.swallet.wpb.presentation.domain.ClaimPathSegment.Key).name
            listOf(sdJwtService.createDisclosure(key, sampleValue(key)))
        }
        path.toDotNotation().contains('.') -> {
            listOf(sdJwtService.createDisclosure(path.toDotNotation(), sampleValue(path.leafKey())))
        }
        path.segments.any { it is di.swallet.wpb.presentation.domain.ClaimPathSegment.Wildcard } ||
            path.segments.any { it is di.swallet.wpb.presentation.domain.ClaimPathSegment.Index } -> {
            listOf(sdJwtService.createDisclosure("nationalities", listOf("PT", "ES")))
        }
        else -> listOf(sdJwtService.createDisclosure(path.toDotNotation(), "Pedro"))
    }

    private fun sampleValue(key: String): Any = when (key) {
        "given_name" -> "Pedro"
        "locality" -> "Lisbon"
        "country" -> "PT"
        "nationalities" -> listOf("PT", "ES")
        else -> "test"
    }
}
