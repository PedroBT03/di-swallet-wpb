/**
 * Builds wallet credentials for OpenID conformance scenarios.
 */

package di.swallet.wpb.conformance.support

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.format.sdjwt.SdJwtService
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.PresentationTestSupport
import di.swallet.wpb.presentation.domain.ClaimPath
import di.swallet.wpb.service.format.DisclosureCipherService
import di.swallet.wpb.testWalletProperties

object ConformanceCredentialFactory {
    private val objectMapper = ObjectMapper()
    private val sdJwtService = SdJwtService(objectMapper)
    private val disclosureCipher = DisclosureCipherService(testWalletProperties())

    /**
     * Builds wallet credentials whose encrypted disclosures cover every claim path requested
     * in the authorization request, defaulting to a single given_name disclosure when none are declared.
     */
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

    /** Maps a DCQL claim path shape to one or more SD-JWT disclosures with representative sample values. */
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

    /** Returns a fixture-appropriate sample claim value for the given disclosure key name. */
    private fun sampleValue(key: String): Any = when (key) {
        "given_name" -> "Pedro"
        "locality" -> "Lisbon"
        "country" -> "PT"
        "nationalities" -> listOf("PT", "ES")
        else -> "test"
    }
}
