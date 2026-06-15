/**
 * TS10 schema data classes for wallet transaction log, export, and migration payloads.
 */

package di.swallet.wpb.transactionlog.domain

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/** TS10 identifier with type URI and value. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10Identifier(
    val type: String,
    val identifier: String,
)

/** Localized string with optional language tag. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10MultiLangString(
    val lang: String? = null,
    val content: String,
)

/** Reference to a privacy or policy document URI. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10Policy(
    val type: String,
    @JsonProperty("policyURI") val policyUri: String,
)

/** Credential identifier with list of claim names requested or presented. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10ClaimInfo(
    val credentialIdentifier: String,
    val claims: List<String>,
)

/** TS10 Presentation transaction payload with RP and DPA metadata. */
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
    /** WPB extension: dNSName from WRPAC when available (RPT_DPA_08 substantiation). */
    val rpDnsName: String? = null,
    val listOfClaimsRequested: List<Ts10ClaimInfo> = emptyList(),
    val listOfClaimsPresented: List<Ts10ClaimInfo> = emptyList(),
    val reasonOfNoncompletion: String? = null,
)

/** TS10 CredentialIssuance transaction payload. */
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

/** TS10 CredentialDeletion transaction payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10CredentialDeletion(
    val credentialIdentifier: String,
    val credentialIssuerIdentifier: Ts10Identifier? = null,
    val credentialIssuerName: String? = null,
)

/** TS10 SigningSealing transaction payload with content hash. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10SigningSealing(
    val contentHash: String,
    val hashAlgorithm: String,
    val signatureAlgorithm: String? = null,
    val reasonOfNoncompletion: String? = null,
)

/** TS10 DPAReport transaction payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10DpaReport(
    val dpaName: String? = null,
    val dpaCountry: String? = null,
    /** WPB extension for RPT_DPA_05a (channel used to initiate the report). */
    val reportChannel: String? = null,
    /** WPB extension for RPT_DPA_05a (contact URI/value used). */
    val reportContact: String? = null,
)

/** TS10 DataDeletionRequest transaction payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10DataDeletionRequest(
    val interactingPartyIdentifier: Ts10Identifier? = null,
    val interactingPartyName: String? = null,
    val listOfClaims: List<Ts10ClaimInfo> = emptyList(),
)

/** TS10 OtherTransaction payload for retention warnings and misc events. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10OtherTransaction(
    val description: String,
)

/** Pseudonym value (COSE public key) with optional user alias. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10Pseudonym(
    val value: String,
    val alias: String? = null,
)

/** TS10 PseudonymGeneration transaction payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10PseudonymGeneration(
    val pseudonym: Ts10Pseudonym,
)

/** TS10 PseudonymDeletion transaction payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10PseudonymDeletion(
    val pseudonym: Ts10Pseudonym,
)

/** TS10 PseudonymousAuthentication transaction payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10PseudonymousAuthentication(
    val interactingPartyIdentifier: Ts10Identifier? = null,
    val interactingPartyType: String = "ServiceProvider",
    val interactingPartyName: String? = null,
    val pseudonym: Ts10Pseudonym,
    val reasonOfNoncompletion: String? = null,
)

/** Normative TS10 transaction types. */
enum class Ts10TransactionType {
    Presentation,
    CredentialIssuance,
    CredentialDeletion,
    SigningSealing,
    DataDeletionRequest,
    DPAReport,
    PseudonymGeneration,
    PseudonymDeletion,
    PseudonymousAuthentication,
    OtherTransaction,
}

/** TS10 transaction completion outcome. */
enum class Ts10TransactionResult {
    Completed,
    NotCompleted,
}

/** Root TS10 transaction object stored in the encrypted log. */
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
    val dataDeletionRequest: Ts10DataDeletionRequest? = null,
    val dpaReport: Ts10DpaReport? = null,
    val pseudonymGeneration: Ts10PseudonymGeneration? = null,
    val pseudonymDeletion: Ts10PseudonymDeletion? = null,
    val pseudonymousAuthentication: Ts10PseudonymousAuthentication? = null,
    val otherTransaction: Ts10OtherTransaction? = null,
)

/** JWE export wrapper for a list of transactions. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10TransactionLogExport(
    @get:JsonProperty("TransactionLog")
    @param:JsonProperty("TransactionLog")
    val transactionLog: List<Ts10Transaction>,
)

/** Credential metadata entry in migration exports (no raw payload). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10CredentialInfo(
    val credentialIdentifier: String,
    val format: String,
    val issuerName: String? = null,
    val issuerIdentifier: Ts10Identifier? = null,
    val issuerType: String? = null,
    val supplyPointURL: String? = null,
)

/** Raw credential payload for non-device-bound credentials in migration. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10NonDeviceBoundCredential(
    val format: String,
    val credential: String,
)

/** Full migration export combining transaction log and credential data. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Ts10MigrationData(
    val transactionLog: List<Ts10Transaction>,
    val listOfCredentials: List<Ts10CredentialInfo>,
    val nonDeviceBoundCredentials: List<Ts10NonDeviceBoundCredential> = emptyList(),
)
