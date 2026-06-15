/**
 * Loads JSON conformance fixtures referenced by catalog scenarios.
 */

package di.swallet.wpb.conformance.fixture

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import di.swallet.wpb.openid4vp.protocol.DcqlSupport
import di.swallet.wpb.openid4vp.protocol.PresentationResponseMode
import di.swallet.wpb.openid4vp.protocol.ResolvedAuthorizationRequest
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.conformance.catalog.ConformanceCatalogLoader
import di.swallet.wpb.presentation.domain.PresentationRequirements

object ConformanceFixtureLoader {
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    /** Reads a JSON fixture from the conformance test resources classpath subtree. */
    fun loadFixture(resourcePath: String): JsonNode {
        val stream = javaClass.classLoader.getResourceAsStream("conformance/$resourcePath")
            ?: error("Fixture not found: conformance/$resourcePath")
        return objectMapper.readTree(stream)
    }

    /** Converts a fixture JSON node into a ResolvedAuthorizationRequest by parsing its embedded DCQL block. */
    fun toAuthorizationRequest(fixture: JsonNode, requestUri: String = "http://verifier/conformance"): ResolvedAuthorizationRequest {
        val dcqlJson = fixture.get("dcql")?.let { objectMapper.writeValueAsString(it) } ?: "{}"
        val queries = DcqlSupport.parse(dcqlJson)
        val queryIds = queries.map { it.id }.ifEmpty { listOf("pid") }
        val formats = queries.map { it.format }.toSet().ifEmpty { setOf(CredentialFormat.SD_JWT) }
        return ResolvedAuthorizationRequest(
            requestToken = "rt-${fixture.path("state").asText("conformance")}",
            requestUri = requestUri,
            clientId = fixture.path("client_id").asText("verifier-demo-client"),
            responseMode = PresentationResponseMode.DIRECT_POST,
            nonce = fixture.path("nonce").asText("nonce-conformance"),
            state = fixture.path("state").asText("conformance-state"),
            responseUri = fixture.path("response_uri").asText("http://127.0.0.1:8081/direct_post"),
            verifierDisplayName = "Conformance Verifier",
            requirements = PresentationRequirements(
                dcqlQueryJson = dcqlJson,
                credentialQueryIds = queryIds,
                requestedFormats = formats,
                credentialQueries = queries,
            ),
        )
    }

    /** Loads all non-negative openid4vp catalog scenarios that reference a fixture into DcqlConformanceCase entries. */
    fun loadDcqlFixtures(): List<DcqlConformanceCase> =
        ConformanceCatalogLoader.load().scenarios
            .filter { it.fixture != null && it.protocol == "openid4vp" && !it.negative }
            .map { scenario ->
                val fixture = loadFixture(scenario.fixture!!)
                DcqlConformanceCase(
                    scenarioId = scenario.id,
                    hlr = scenario.hlr,
                    fixtureName = scenario.fixture.substringAfterLast('/'),
                    request = toAuthorizationRequest(fixture),
                )
            }
}

data class DcqlConformanceCase(
    val scenarioId: String,
    val hlr: List<String>,
    val fixtureName: String,
    val request: ResolvedAuthorizationRequest,
)
