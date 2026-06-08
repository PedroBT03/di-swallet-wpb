package di.swallet.wpb.transactionlog.domain

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10Identifier(
    val type: String,
    val identifier: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10MultiLangString(
    val lang: String? = null,
    val content: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10Policy(
    val type: String,
    @JsonProperty("policyURI") val policyUri: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10ClaimInfo(
    val credentialIdentifier: String,
    val claims: List<String>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10Presentation(
    val interactingPartyIdentifier: Ts10Identifier? = null,
    val interactingPartyType: String = "ServiceProvider",
    val interactingPartyName: String? = null,
    val interactingPartyContact: List<String> = emptyList(),
    val isIntermediary: String = "FALSE",
    val intermediaryIdentifier: Ts10Identifier? = null,
    val intermediaryName: String? = null,
    val intermediaryContact: List<String>? = null,
    val registrarURL: String? = null,
    val purpose: List<Ts10MultiLangString> = emptyList(),
    val privacyPolicy: Ts10Policy? = null,
    val dpaName: String? = null,
    val dpaCountry: String? = null,
    val dpaContact: List<String> = emptyList(),
    val listOfClaimsRequested: List<Ts10ClaimInfo> = emptyList(),
    val listOfClaimsPresented: List<Ts10ClaimInfo> = emptyList(),
    val reasonOfNoncompletion: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10CredentialIssuance(
    val interactingPartyIdentifier: Ts10Identifier? = null,
    val interactingPartyType: String = "PIDProvider",
    val interactingPartyName: String? = null,
    val interactingPartyContact: List<String> = emptyList(),
    val credentialNumberRequested: Int = 1,
    val credentialNumberIssued: Int = 0,
    val credentialIdentifier: List<String> = emptyList(),
    val isUserTriggered: String = "TRUE",
    val reasonOfNoncompletion: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10CredentialDeletion(
    val credentialIdentifier: String,
    val credentialIssuerIdentifier: Ts10Identifier? = null,
    val credentialIssuerName: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10SigningSealing(
    val contentHash: String,
    val hashAlgorithm: String,
    val signatureAlgorithm: String? = null,
    val reasonOfNoncompletion: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10OtherTransaction(
    val description: String,
)

enum class Ts10TransactionType {
    Presentation,
    CredentialIssuance,
    CredentialDeletion,
    SigningSealing,
    OtherTransaction,
}

enum class Ts10TransactionResult {
    Completed,
    NotCompleted,
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10Transaction(
    val transactionIdentifier: String,
    val time: String,
    val transactionType: String,
    val transactionResult: String,
    val presentation: Ts10Presentation? = null,
    val credentialIssuance: Ts10CredentialIssuance? = null,
    val credentialDeletion: Ts10CredentialDeletion? = null,
    val signingSealing: Ts10SigningSealing? = null,
    val otherTransaction: Ts10OtherTransaction? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10TransactionLogExport(
    @get:JsonProperty("TransactionLog")
    @param:JsonProperty("TransactionLog")
    val transactionLog: List<Ts10Transaction>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10CredentialInfo(
    val credentialIdentifier: String,
    val format: String,
    val issuerName: String? = null,
    val issuerIdentifier: Ts10Identifier? = null,
    val issuerType: String? = null,
    val supplyPointURL: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10NonDeviceBoundCredential(
    val format: String,
    val credential: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10MigrationData(
    val transactionLog: List<Ts10Transaction>,
    val listOfCredentials: List<Ts10CredentialInfo>,
    val nonDeviceBoundCredentials: List<Ts10NonDeviceBoundCredential> = emptyList(),
)
