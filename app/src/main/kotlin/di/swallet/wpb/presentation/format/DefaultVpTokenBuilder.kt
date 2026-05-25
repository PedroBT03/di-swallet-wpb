package di.swallet.wpb.presentation.format

import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.SelectedCredential
import di.swallet.wpb.presentation.domain.VpToken
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Builds the OpenID4VP `vp_token` map keyed by DCQL query identifier.
 *
 *  - SD-JWT credentials are encoded as full SD-JWT VC presentations including
 *    the Key Binding JWT (HAIP).
 *  - MDOC is declared in the configuration but not implemented at runtime
 *    (Phase 7); selecting an MDOC candidate will fail explicitly so the
 *    verifier receives an error rather than a malformed VP.
 */
@Service
class DefaultVpTokenBuilder(
    private val sdJwtVpBuilder: SdJwtVpBuilder,
) : VpTokenBuilder {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun build(context: PresentationContext): PresentationContext {
        val selected = context.selectedCredentials
        if (selected.isEmpty()) {
            throw IllegalStateException("Cannot build VP: no credentials selected for session ${context.sessionMeta.sessionId}")
        }

        val request = context.authorizationRequest
            ?: throw IllegalStateException("Cannot build VP: authorization request missing")

        val perQueryPresentations: Map<String, List<String>> = selected
            .groupBy { it.queryId }
            .mapValues { (_, items) ->
                items.map { buildSingle(it, request.clientId, request.nonce) }
            }

        val format = selected.firstOrNull()?.format ?: CredentialFormat.SD_JWT

        return context.copy(
            vpToken = VpToken(
                presentationsByQueryId = perQueryPresentations,
                format = format,
                rawValue = null,
            ),
        )
    }

    private fun buildSingle(
        selected: SelectedCredential,
        verifierAudience: String,
        verifierNonce: String,
    ): String = when (selected.format) {
        CredentialFormat.SD_JWT -> sdJwtVpBuilder.build(selected, verifierAudience, verifierNonce).presentation
        CredentialFormat.MDOC -> {
            logger.error("MDOC presentations are not implemented in Phase 1 (selected={})", selected.candidateId)
            throw UnsupportedOperationException("MDOC presentations are not implemented in Phase 1 (deferred to Phase 7)")
        }
    }
}
