package di.swallet.wpb.trustmark

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

data class TrustMarkInformation(
    val trustMarkResourceUrl: String,
    @JsonProperty("ListOfCertifiedWalletsURL")
    val listOfCertifiedWalletsUrl: String,
    val walletSolutionInfoPageUrl: String,
    val walletSolutionId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TrustMarkResourcePayload(
    val image: TrustMarkImageResource? = null,
    val text: TrustMarkTextResource? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TrustMarkImageResource(
    val name: String? = null,
    val url: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TrustMarkTextResource(
    val name: String? = null,
    val localizations: Map<String, String> = emptyMap(),
)

data class TrustMarkResourceView(
    val imageUrl: String?,
    val imageName: String?,
    val localizedText: String?,
    val language: String,
    val availableLanguages: List<String>,
)

enum class TrustMarkActionType {
    CERTIFIED_WALLETS_LIST,
    WALLET_SOLUTION_INFO,
}

data class TrustMarkAction(
    val type: TrustMarkActionType,
    val uri: String,
)

data class TrustMarkView(
    val enabled: Boolean,
    val walletSolutionId: String? = null,
    val information: TrustMarkInformation? = null,
    val resource: TrustMarkResourceView? = null,
    val actions: List<TrustMarkAction> = emptyList(),
    val fetchedAt: Instant? = null,
    val cacheSource: String? = null,
    val warnings: List<String> = emptyList(),
    val userNotice: String? = null,
)

/** Raw JSON from EC may use the schema typo (Cerfified). */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class RemoteTrustMarkInformationJson(
    @JsonProperty("TrustMarkResourceURL")
    val trustMarkResourceUrl: String? = null,
    @JsonProperty("ListOfCertifiedWalletsURL")
    @JsonAlias("ListOfCerfifiedWalletsURL")
    val listOfCertifiedWalletsUrl: String? = null,
    @JsonProperty("WalletSolutionInfoPageURL")
    val walletSolutionInfoPageUrl: String? = null,
)
