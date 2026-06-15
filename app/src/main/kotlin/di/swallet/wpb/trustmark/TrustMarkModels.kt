/**
 * API and persistence models for the EUDI Wallet Trust Mark view.
 */

package di.swallet.wpb.trustmark

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/** Static Trust Mark URLs and wallet solution identifier exposed to clients. */
data class TrustMarkInformation(
    val trustMarkResourceUrl: String,
    @JsonProperty("ListOfCertifiedWalletsURL")
    val listOfCertifiedWalletsUrl: String,
    val walletSolutionInfoPageUrl: String,
    val walletSolutionId: String? = null,
)

/** Parsed TrustMarkResource JSON payload from the remote resource URL. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TrustMarkResourcePayload(
    val image: TrustMarkImageResource? = null,
    val text: TrustMarkTextResource? = null,
)

/** Image metadata from the TrustMarkResource payload. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TrustMarkImageResource(
    val name: String? = null,
    val url: String? = null,
)

/** Localized text metadata from the TrustMarkResource payload. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TrustMarkTextResource(
    val name: String? = null,
    val localizations: Map<String, String> = emptyMap(),
)

/** Client-facing Trust Mark image and localized text after URL resolution. */
data class TrustMarkResourceView(
    val imageUrl: String?,
    val imageName: String?,
    val localizedText: String?,
    val language: String,
    val availableLanguages: List<String>,
)

/** Types of external Trust Mark links shown in the wallet UI. */
enum class TrustMarkActionType {
    CERTIFIED_WALLETS_LIST,
    WALLET_SOLUTION_INFO,
}

/** One external Trust Mark link with its action type. */
data class TrustMarkAction(
    val type: TrustMarkActionType,
    val uri: String,
)

/** Full Trust Mark view returned to wallet clients, including warnings and cache metadata. */
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
