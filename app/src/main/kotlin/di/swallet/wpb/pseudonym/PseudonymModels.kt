/**
 * Request and response models for pseudonym credential management and WebAuthn ceremonies.
 */

package di.swallet.wpb.pseudonym

import java.util.UUID

/**
 * Input for creating a new per-RP pseudonym for a wallet holder.
 */
data class CreatePseudonymRequest(
    val holderId: String,
    val rpId: String,
    val alias: String? = null,
)

/**
 * API view of a pseudonym credential including status and registration metadata.
 */
data class PseudonymView(
    val id: UUID,
    val holderId: String,
    val rpId: String,
    val alias: String?,
    val status: PseudonymStatus,
    val credentialId: String?,
    val createdAt: String,
    val lastUsedAt: String?,
)

/**
 * Input for updating the optional display alias on an existing pseudonym.
 */
data class UpdatePseudonymAliasRequest(
    val alias: String?,
)

/**
 * Input for starting a WebAuthn registration ceremony, including the caller origin.
 */
data class RegistrationOptionsRequest(
    val holderId: String,
    val origin: String,
)

/**
 * WebAuthn registration options returned to the client for credential creation.
 */
data class RegistrationOptionsResponse(
    val challenge: String,
    val rpId: String,
    val rpName: String,
    val userHandle: String,
    val timeout: Long,
    val pubKeyCredParams: List<Map<String, Any>>,
)

/**
 * Client payload for completing WebAuthn registration of a pseudonym passkey.
 */
data class RegistrationFinishRequest(
    val holderId: String,
    val origin: String,
    val clientDataJSON: String,
)

/**
 * Server-built WebAuthn registration artifacts after successful pseudonym key creation.
 */
data class RegistrationFinishResponse(
    val credentialId: String,
    val attestationObject: String,
    val clientDataJSON: String,
    val publicKeyCose: String,
)

/**
 * Input for starting a WebAuthn authentication ceremony for a registered pseudonym.
 */
data class AuthenticationOptionsRequest(
    val holderId: String,
    val origin: String,
)

/**
 * WebAuthn authentication options returned to the client for assertion signing.
 */
data class AuthenticationOptionsResponse(
    val challenge: String,
    val rpId: String,
    val credentialId: String,
    val timeout: Long,
)

/**
 * Client payload for completing WebAuthn authentication with a pseudonym passkey.
 */
data class AuthenticationFinishRequest(
    val holderId: String,
    val origin: String,
    val clientDataJSON: String,
)

/**
 * Server-built WebAuthn assertion artifacts after successful pseudonym authentication.
 */
data class AuthenticationFinishResponse(
    val credentialId: String,
    val authenticatorData: String,
    val clientDataJSON: String,
    val signature: String,
)
