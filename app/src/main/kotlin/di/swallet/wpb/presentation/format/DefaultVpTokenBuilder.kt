package di.swallet.wpb.presentation.format

import di.swallet.wpb.format.sdjwt.SdJwtVpBuilder
import di.swallet.wpb.presentation.domain.CredentialFormat
import di.swallet.wpb.presentation.domain.PresentationContext
import di.swallet.wpb.presentation.domain.SelectedCredential
import di.swallet.wpb.presentation.domain.VpToken
import org.springframework.stereotype.Service

/**
 * Builds the OpenID4VP `vp_token` map keyed by DCQL query identifier.
 *
 *  - SD-JWT credentials are encoded as full SD-JWT VC presentations including
 *    the Key Binding JWT (HAIP).
 *  - MDOC credentials are encoded via [MdocVpBuilder] and carried as opaque
 *    mdoc payload strings through the adapter boundary.
 */
@Service
class DefaultVpTokenBuilder(
    private val sdJwtVpBuilder: SdJwtVpBuilder,
    private val mdocVpBuilder: MdocVpBuilder,
) : VpTokenBuilder {

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
        CredentialFormat.MDOC -> mdocVpBuilder.build(selected, verifierAudience, verifierNonce).presentation
    }
}
