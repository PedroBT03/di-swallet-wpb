/**
 * Captures redacted request/response excerpts for the dissertation demonstration.
 *
 * Run with:
 *   ./gradlew :app:test --tests di.swallet.wpb.ops.EvaluationDemoCaptureTest
 *
 * Output directory: evaluation.demo.dir (default: build/reports/evaluation-demo).
 */

package di.swallet.wpb.ops

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.consent.IssuanceConsentTestSupport
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.issuance.domain.IssuanceContext
import di.swallet.wpb.issuance.orchestration.IssuanceFlowOrchestrator
import di.swallet.wpb.openid4vci.protocol.IssuanceRequest
import di.swallet.wpb.service.DeviceBindingService
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.service.WalletUnitLifecycleService
import di.swallet.wpb.wallet.WalletTestSupport
import di.swallet.wpb.wia.validation.WiaPopTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

class EvaluationDemoCaptureTest : BaseIntegrationTest() {

    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var orchestrator: IssuanceFlowOrchestrator
    @Autowired lateinit var walletUnitRepository: WalletUnitRepository
    @Autowired lateinit var walletUnitLifecycleService: WalletUnitLifecycleService
    @Autowired lateinit var deviceBindingService: DeviceBindingService
    @Autowired lateinit var hsmService: HsmService

    @Test
    fun `capture demonstration artefacts for chapter 6`() {
        val out = Paths.get(System.getProperty("evaluation.demo.dir") ?: "build/reports/evaluation-demo")
        Files.createDirectories(out)
        val holder = "eval-demo-${UUID.randomUUID()}"

        val initBody = mapOf(
            "holderId" to holder,
            "platform" to "web",
            "devicePubJwk" to WalletTestSupport.ecPublicJwk(),
        )
        val init = restTemplate.postForEntity("/api/v1/wallet/init", initBody, Map::class.java)
        assertThat(init.statusCode).isEqualTo(HttpStatus.OK)
        writeJson(out.resolve("register_wallet.json"), mapOf(
            "request" to mapOf(
                "method" to "POST",
                "path" to "/api/v1/wallet/init",
                "body" to mapOf(
                    "holderId" to holder,
                    "platform" to "web",
                    "devicePubJwk" to "(P-256 JWK omitted)",
                ),
            ),
            "response" to redact(init.body),
        ))

        val headers = getDynamicHeaders(holder)
        val summary = restTemplate.exchange(
            "/api/v1/wallet/summary/$holder",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
        assertThat(summary.statusCode).isEqualTo(HttpStatus.OK)
        writeJson(out.resolve("wallet_summary_after_register.json"), redact(summary.body))

        val issueHolder = "eval-issue-${UUID.randomUUID()}"
        val deviceKey = WalletTestSupport.bootstrapHolderWithDeviceKey(
            deviceBindingService,
            walletUnitRepository,
            hsmService,
            issueHolder,
        )
        val pidCtx = completeSimulatedIssuance(issueHolder, deviceKey, "pid_jwt")
        writeJson(out.resolve("issue_pid_openid4vci.json"), mapOf(
            "path" to "OpenID4VCI orchestrator (simulated issuer)",
            "offer" to "pid_jwt / SD-JWT VC",
            "sessionId" to pidCtx.sessionMeta.sessionId.toString(),
            "state" to pidCtx.state.name,
            "format" to pidCtx.issuedCredentials.firstOrNull()?.format?.name,
            "credentialConfigurationId" to pidCtx.issuedCredentials.firstOrNull()?.credentialConfigurationId,
        ))

        val eaaCtx = completeSimulatedIssuance(issueHolder, deviceKey, "org.iso.18013.5.1.mDL")
        writeJson(out.resolve("request_eaa_mdl_openid4vci.json"), mapOf(
            "path" to "OpenID4VCI orchestrator (simulated issuer)",
            "offer" to "org.iso.18013.5.1.mDL / mdoc EAA",
            "sessionId" to eaaCtx.sessionMeta.sessionId.toString(),
            "state" to eaaCtx.state.name,
            "format" to eaaCtx.issuedCredentials.firstOrNull()?.format?.name,
            "credentialConfigurationId" to eaaCtx.issuedCredentials.firstOrNull()?.credentialConfigurationId,
        ))

        val resolveHttp = restTemplate.postForEntity(
            "/openid4vci/offer/resolve",
            HttpEntity(
                mapOf(
                    "offerUri" to """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["pid_jwt"]}""",
                    "holderId" to holder,
                ),
                getDynamicHeaders(holder),
            ),
            Map::class.java,
        )
        writeJson(out.resolve("wpi_offer_resolve.json"), mapOf(
            "request" to mapOf(
                "method" to "POST",
                "path" to "/openid4vci/offer/resolve",
                "body" to mapOf(
                    "offerUri" to "openid-credential-offer://credential_offer={...pid_jwt...}",
                    "holderId" to holder,
                ),
            ),
            "httpStatus" to resolveHttp.statusCode.value(),
            "response" to redact(resolveHttp.body),
        ))

        val issueSd = restTemplate.postForEntity(
            "/api/v1/wallet/credentials/issue-sd/$holder",
            HttpEntity<String>(getDynamicHeaders(holder)),
            Map::class.java,
        )
        assertThat(issueSd.statusCode).isEqualTo(HttpStatus.OK)
        val credentialId = (issueSd.body?.get("id") as Number).toLong()
        writeJson(out.resolve("mock_pid_issue_sd.json"), mapOf(
            "request" to mapOf("method" to "POST", "path" to "/api/v1/wallet/credentials/issue-sd/{holderId}"),
            "httpStatus" to issueSd.statusCode.value(),
            "response" to redact(issueSd.body),
        ))

        val present = restTemplate.postForEntity(
            "/api/v1/wallet/credentials/$credentialId/presentation",
            HttpEntity(
                mapOf("claimsToDisclose" to listOf("given_name")),
                getDynamicHeaders(holder),
            ),
            Map::class.java,
        )
        assertThat(present.statusCode).isEqualTo(HttpStatus.OK)
        writeJson(out.resolve("present_credentials.json"), mapOf(
            "request" to mapOf(
                "method" to "POST",
                "path" to "/api/v1/wallet/credentials/{id}/presentation",
                "body" to mapOf("claimsToDisclose" to listOf("given_name")),
            ),
            "httpStatus" to present.statusCode.value(),
            "response" to redact(present.body),
        ))

        val wallet = walletUnitRepository.findFirstByHolderId(holder).orElseThrow()
        if (wallet.state != di.swallet.wpb.domain.WalletUnitState.VALID) {
            WalletTestSupport.markHolderWalletValid(
                walletUnitRepository,
                walletUnitLifecycleService,
                holder,
            )
        }
        val sign = restTemplate.postForEntity(
            "/api/v1/wallet/sign/$holder",
            HttpEntity(
                mapOf("data" to "evaluation-demo-payload"),
                getDynamicHeaders(holder),
            ),
            Map::class.java,
        )
        assertThat(sign.statusCode).isEqualTo(HttpStatus.OK)
        writeJson(out.resolve("sign_document.json"), mapOf(
            "request" to mapOf(
                "method" to "POST",
                "path" to "/api/v1/wallet/sign/{holderId}",
                "body" to mapOf("data" to "evaluation-demo-payload"),
            ),
            "note" to "Remote ES256 signature under SUA. Not a qualified document signature (PAdES/CAdES) and not a QSCD.",
            "httpStatus" to sign.statusCode.value(),
            "response" to redact(sign.body),
        ))

        val loginHeaders = getDynamicHeaders(holder)
        val loginSummary = restTemplate.exchange(
            "/api/v1/wallet/summary/$holder",
            HttpMethod.GET,
            HttpEntity<Void>(loginHeaders),
            Map::class.java,
        )
        assertThat(loginSummary.statusCode).isEqualTo(HttpStatus.OK)
        writeJson(out.resolve("recover_wallet_access.json"), mapOf(
            "note" to "A fresh FIDO2 assertion on the same holder restores access to server-side wallet state. This is not lost-authenticator recovery.",
            "httpStatus" to loginSummary.statusCode.value(),
            "response" to redact(loginSummary.body),
        ))

        val revoke = restTemplate.postForEntity(
            "/api/v1/wallet/credentials/$credentialId/revoke",
            HttpEntity<String>(getDynamicHeaders(holder)),
            Map::class.java,
        )
        assertThat(revoke.statusCode).isEqualTo(HttpStatus.OK)
        val blocked = restTemplate.postForEntity(
            "/api/v1/wallet/credentials/$credentialId/presentation",
            HttpEntity(
                mapOf("claimsToDisclose" to listOf("given_name")),
                getDynamicHeaders(holder),
            ),
            Map::class.java,
        )
        writeJson(out.resolve("revoke_blocks_presentation.json"), mapOf(
            "revoke" to mapOf(
                "path" to "/api/v1/wallet/credentials/{id}/revoke",
                "httpStatus" to revoke.statusCode.value(),
                "response" to redact(revoke.body),
            ),
            "presentationAfterRevoke" to mapOf(
                "httpStatus" to blocked.statusCode.value(),
                "response" to redact(blocked.body),
            ),
        ))
        assertThat(blocked.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val unitId = (init.body?.get("walletId") as? String)
            ?: walletUnitRepository.findFirstByHolderId(holder).orElseThrow().walletId
        val revokeUnit = restTemplate.postForEntity(
            "/api/v1/wallet/units/$unitId/revoke",
            HttpEntity<String>(getDynamicHeaders(holder)),
            Map::class.java,
        )
        writeJson(out.resolve("revoke_wallet_unit.json"), mapOf(
            "request" to mapOf("method" to "POST", "path" to "/api/v1/wallet/units/{walletId}/revoke"),
            "httpStatus" to revokeUnit.statusCode.value(),
            "response" to redact(revokeUnit.body),
        ))
    }

    private fun completeSimulatedIssuance(
        holderId: String,
        deviceKey: WiaPopTestSupport.DeviceKeyMaterial,
        configurationId: String,
    ): IssuanceContext {
        var ctx = orchestrator.resolveOffer(
            offerUri = """openid-credential-offer://credential_offer={"credential_issuer":"https://issuer.example","credential_configuration_ids":["$configurationId"]}""",
            holderId = holderId,
        )
        ctx = orchestrator.prepareAuthorization(ctx.sessionMeta.sessionId)
        val popJwt = WiaPopTestSupport.signPop(
            privateKey = deviceKey.privateKey,
            walletInstanceId = holderId,
            cnfJkt = ctx.wia!!.attestation!!.cnfJkt,
            audience = "https://issuer.example",
        )
        ctx = orchestrator.prepareAuthorization(ctx.sessionMeta.sessionId, popJwt)
        ctx = orchestrator.completeAuthorizationCode(
            ctx.sessionMeta.sessionId,
            "auth-code",
            ctx.preparedAuthorization!!.state,
        )
        ctx = orchestrator.requestCredential(
            ctx.sessionMeta.sessionId,
            IssuanceRequest(credentialConfigurationId = configurationId),
        )
        return IssuanceConsentTestSupport.approveStorageIfPending(orchestrator, ctx, holderId)
    }

    private fun writeJson(path: Path, value: Any?) {
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(redact(value)))
    }

    private fun redact(value: Any?): JsonNode {
        val node = objectMapper.valueToTree<JsonNode>(value ?: objectMapper.createObjectNode())
        return redactNode(node)
    }

    private fun redactNode(node: JsonNode): JsonNode {
        when (node) {
            is ObjectNode -> {
                val keys = node.fieldNames().asSequence().toList()
                for (key in keys) {
                    val child = node.get(key)
                    if (sensitiveKey(key) && child.isTextual) {
                        node.set<JsonNode>(key, TextNode(abbreviate(child.asText())))
                    } else {
                        node.set<JsonNode>(key, redactNode(child))
                    }
                }
                return node
            }
            is ArrayNode -> {
                for (i in 0 until node.size()) {
                    node.set(i, redactNode(node.get(i)))
                }
                return node
            }
            else -> {
                if (node.isTextual) {
                    val text = node.asText()
                    if (looksLikeToken(text)) {
                        return TextNode(abbreviate(text))
                    }
                }
                return node
            }
        }
    }

    private fun sensitiveKey(key: String): Boolean {
        val k = key.lowercase()
        return k.contains("jwt") || k.contains("token") || k.contains("signature") ||
            k.contains("encoded") || k.contains("presentation") || k.contains("wia") ||
            k.contains("attestation") || k == "ka" || k.contains("secret") ||
            k.contains("pin") || k.contains("assertion")
    }

    private fun looksLikeToken(text: String): Boolean =
        text.length > 80 && (text.count { it == '.' } >= 2 || text.contains("~"))

    private fun abbreviate(text: String): String {
        if (text.length <= 24) return text
        return text.take(16) + "...(" + text.length + " chars)"
    }
}
