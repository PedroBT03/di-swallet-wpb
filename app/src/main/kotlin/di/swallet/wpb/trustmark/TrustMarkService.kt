/**
 * Assembles the EUDI Wallet Trust Mark view from configuration and cached remote resources.
 */

package di.swallet.wpb.trustmark

import di.swallet.wpb.config.TrustMarkProperties
import org.springframework.stereotype.Service

/** Builds the Trust Mark view, resolves localization, and validates HTTPS links. */
@Service
class TrustMarkService(
    private val properties: TrustMarkProperties,
    private val resourceClient: TrustMarkResourceProvider,
    private val urlResolver: TrustMarkUrlResolver,
    private val localizationService: TrustMarkLocalizationService,
    private val validator: TrustMarkResourceValidator,
) {
    /** Returns the Trust Mark view for the requested language, or a disabled notice when unconfigured. */
    fun getView(language: String?): TrustMarkView {
        if (!properties.isConfigured()) {
            return TrustMarkView(
                enabled = false,
                userNotice = properties.disabledNotice,
            )
        }

        val warnings = mutableListOf<String>()
        val information = buildInformation(warnings)
        val cached = resourceClient.getResource(forceRefresh = false)
        val resourceView = cached?.let { buildResourceView(it, language, warnings) }

        return TrustMarkView(
            enabled = true,
            walletSolutionId = properties.walletSolutionId.takeIf { it.isNotBlank() },
            information = information,
            resource = resourceView,
            actions = buildActions(warnings),
            fetchedAt = cached?.fetchedAt,
            cacheSource = cached?.cacheSource,
            warnings = warnings,
        )
    }

    /** Invalidates the cache, refetches the remote resource, and returns a fresh view. */
    fun refresh(): TrustMarkView {
        resourceClient.invalidate()
        resourceClient.getResource(forceRefresh = true)
        return getView(language = null)
    }

    /** Builds static Trust Mark URLs and records HTTPS warnings for misconfigured links. */
    private fun buildInformation(warnings: MutableList<String>): TrustMarkInformation {
        validateHttps(properties.trustMarkResourceUrl, "TrustMarkResourceURL", warnings)
        validateHttps(properties.listOfCertifiedWalletsUrl, "ListOfCertifiedWalletsURL", warnings)
        validateHttps(properties.walletSolutionInfoPageUrl, "WalletSolutionInfoPageURL", warnings)
        return TrustMarkInformation(
            trustMarkResourceUrl = properties.trustMarkResourceUrl.trim(),
            listOfCertifiedWalletsUrl = properties.listOfCertifiedWalletsUrl.trim(),
            walletSolutionInfoPageUrl = properties.walletSolutionInfoPageUrl.trim(),
            walletSolutionId = properties.walletSolutionId.takeIf { it.isNotBlank() },
        )
    }

    /** Validates and localizes the cached TrustMarkResource payload for client display. */
    private fun buildResourceView(
        cached: CachedTrustMarkResource,
        language: String?,
        warnings: MutableList<String>,
    ): TrustMarkResourceView? {
        val validation = validator.validate(cached.payload)
        warnings.addAll(validation.warnings)
        if (!validation.valid) return null
        val imageUrl = urlResolver.resolveAgainstBase(
            properties.trustMarkResourceUrl,
            cached.payload.image?.url,
        )
        if (imageUrl != null && !imageUrl.startsWith("https://", ignoreCase = true)) {
            warnings.add("Resolved TrustMark image URL is not HTTPS")
        }
        val localizations = cached.payload.text?.localizations.orEmpty()
        val (resolvedLang, text) = localizationService.select(
            localizations = localizations,
            requestedLanguage = language,
            defaultLanguage = properties.defaultLanguage,
        )
        return TrustMarkResourceView(
            imageUrl = imageUrl,
            imageName = cached.payload.image?.name,
            localizedText = text.takeIf { it.isNotBlank() },
            language = resolvedLang,
            availableLanguages = localizations.keys.sorted(),
        )
    }

    /** Builds external Trust Mark action links and warns about non-HTTPS URLs. */
    private fun buildActions(warnings: MutableList<String>): List<TrustMarkAction> {
        val actions = mutableListOf<TrustMarkAction>()
        val listUrl = properties.listOfCertifiedWalletsUrl.trim()
        if (listUrl.isNotBlank()) {
            actions.add(TrustMarkAction(TrustMarkActionType.CERTIFIED_WALLETS_LIST, listUrl))
        }
        val infoUrl = properties.walletSolutionInfoPageUrl.trim()
        if (infoUrl.isNotBlank()) {
            actions.add(TrustMarkAction(TrustMarkActionType.WALLET_SOLUTION_INFO, infoUrl))
        }
        actions.forEach { action ->
            if (!action.uri.startsWith("https://", ignoreCase = true)) {
                warnings.add("Action ${action.type} URL is not HTTPS")
            }
        }
        return actions
    }

    /** Adds a warning when a configured URL is present but not HTTPS. */
    private fun validateHttps(url: String, label: String, warnings: MutableList<String>) {
        if (url.isNotBlank() && !url.startsWith("https://", ignoreCase = true)) {
            warnings.add("$label should use HTTPS")
        }
    }
}
