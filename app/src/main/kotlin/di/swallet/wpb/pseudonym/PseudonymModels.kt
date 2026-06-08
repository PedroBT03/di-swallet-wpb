package di.swallet.wpb.pseudonym

import java.util.UUID

data class CreatePseudonymRequest(
    val holderId: String,
    val rpId: String,
    val alias: String? = null,
)

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

data class UpdatePseudonymAliasRequest(
    val alias: String?,
)

data class RegistrationOptionsRequest(
    val holderId: String,
    val origin: String,
)

data class RegistrationOptionsResponse(
    val challenge: String,
    val rpId: String,
    val rpName: String,
    val userHandle: String,
    val timeout: Long,
    val pubKeyCredParams: List<Map<String, Any>>,
)

data class RegistrationFinishRequest(
    val holderId: String,
    val origin: String,
    val clientDataJSON: String,
)

data class RegistrationFinishResponse(
    val credentialId: String,
    val attestationObject: String,
    val clientDataJSON: String,
    val publicKeyCose: String,
)

data class AuthenticationOptionsRequest(
    val holderId: String,
    val origin: String,
)

data class AuthenticationOptionsResponse(
    val challenge: String,
    val rpId: String,
    val credentialId: String,
    val timeout: Long,
)

data class AuthenticationFinishRequest(
    val holderId: String,
    val origin: String,
    val clientDataJSON: String,
)

data class AuthenticationFinishResponse(
    val credentialId: String,
    val authenticatorData: String,
    val clientDataJSON: String,
    val signature: String,
)
