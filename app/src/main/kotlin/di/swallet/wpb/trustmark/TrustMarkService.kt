package di.swallet.wpb.trustmark

import di.swallet.wpb.config.TrustMarkProperties
import org.springframework.stereotype.Service

@Service
class TrustMarkService(
    private val properties: TrustMarkProperties,
    private val resourceClient: TrustMarkResourceProvider,
    private val urlResolver: TrustMarkUrlResolver,
    private val localizationService: TrustMarkLocalizationService,
    private val validator: TrustMarkResourceValidator,
) {
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

    fun refresh(): TrustMarkView {
        resourceClient.invalidate()
        resourceClient.getResource(forceRefresh = true)
        return getView(language = null)
    }

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

    private fun validateHttps(url: String, label: String, warnings: MutableList<String>) {
        if (url.isNotBlank() && !url.startsWith("https://", ignoreCase = true)) {
            warnings.add("$label should use HTTPS")
        }
    }
}
