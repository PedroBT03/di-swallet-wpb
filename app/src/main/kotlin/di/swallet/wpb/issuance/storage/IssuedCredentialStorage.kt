/**
 * Persists OID4VCI-issued credentials into the wallet credential store.
 */

package di.swallet.wpb.issuance.storage

import di.swallet.wpb.domain.CredentialTypeLabels
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.format.mdoc.MdocCredentialCodec
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import di.swallet.wpb.service.format.DisclosureCipherService
import di.swallet.wpb.revocation.CredentialStatusParser
import di.swallet.wpb.service.KeyBindingRuntimeService
import di.swallet.wpb.domain.CredentialBindingFormat
import org.springframework.stereotype.Component

/**
 * Persists credentials issued through OID4VCI back into the existing
 * [WalletCredentialRepository] so they become available to the
 * presentation flow.
 *
 * The storage performs a best-effort split of the
 * SD-JWT VC value `<issuer-jwt>~<d1>~...~<dN>` into:
 *   - `encodedData`: the issuer-signed JWT (without disclosures)
 *   - `encryptedDisclosures`: the AES-GCM ciphertext of the disclosure list
 *
 * Non-SD-JWT formats are persisted with empty disclosure sets; for mdoc the
 * payload is normalized to a structured runtime envelope consumed by the
 * presentation path.
 */
interface IssuedCredentialStorage {
    /** Normalizes and saves an issued credential, returning the wallet row id. */
    fun store(
        holderId: String,
        issued: IssuedCredential,
        walletKey: WalletKey? = null,
        keyAliasHint: String? = null,
        deviceBound: Boolean = true,
    ): Long
}

/** JPA-backed storage that splits SD-JWT disclosures and binds credentials to keys. */
@Component
class JpaIssuedCredentialStorage(
    private val repository: WalletCredentialRepository,
    private val walletKeyRepository: WalletKeyRepository,
    private val disclosureCipher: DisclosureCipherService,
    private val mdocCredentialCodec: MdocCredentialCodec,
    private val mdocDocTypeRegistry: MdocDocTypeRegistry,
    private val keyBindingRuntimeService: KeyBindingRuntimeService,
    private val credentialStatusParser: CredentialStatusParser,
    private val supersessionService: IssuedCredentialSupersessionService,
) : IssuedCredentialStorage {
    /** Persists format-specific payload parts and registers key binding when a key is known. */
    override fun store(
        holderId: String,
        issued: IssuedCredential,
        walletKey: WalletKey?,
        keyAliasHint: String?,
        deviceBound: Boolean,
    ): Long {
        val (encoded, encryptedDisclosures) = when (issued.format) {
            IssuanceCredentialFormat.SD_JWT_VC -> splitSdJwt(issued.rawPayload)
            IssuanceCredentialFormat.MSO_MDOC -> normalizeMdoc(issued) to disclosureCipher.encrypt(emptyList())
            IssuanceCredentialFormat.UNKNOWN -> issued.rawPayload to disclosureCipher.encrypt(emptyList())
        }

        val credentialType = inferCredentialType(issued.credentialConfigurationId, issued.format)
        supersessionService.supersedeActiveOfSameFamily(holderId, credentialType)

        val resolvedKey = walletKey
            ?: keyAliasHint?.let { walletKeyRepository.findByKeyAlias(it).orElse(null) }

        val issuerStatus = when (issued.format) {
            IssuanceCredentialFormat.SD_JWT_VC -> credentialStatusParser.parseFromSdJwt(issued.rawPayload)
            else -> null
        }

        val entity = WalletCredential(
            userId = holderId,
            credentialType = credentialType,
            encodedData = encoded,
            encryptedDisclosures = encryptedDisclosures,
            walletKey = resolvedKey,
            issuerStatusUri = issuerStatus?.listUri,
            issuerStatusIndex = issuerStatus?.listIndex,
            deviceBound = deviceBound,
        )
        val savedId = repository.save(entity).id ?: error("WalletCredential persisted without id")
        resolvedKey?.let {
            keyBindingRuntimeService.bindCredentialToKey(
                credentialId = savedId,
                keyAlias = it.keyAlias,
                format = when (issued.format) {
                    IssuanceCredentialFormat.MSO_MDOC -> CredentialBindingFormat.MDOC
                    else -> CredentialBindingFormat.SD_JWT
                },
            )
        }
        return savedId
    }

    /** Separates the issuer JWT from trailing disclosures in an SD-JWT VC string. */
    private fun splitSdJwt(raw: String): Pair<String, String> {
        val parts = raw.split("~").filter { it.isNotBlank() }
        if (parts.isEmpty()) return raw to disclosureCipher.encrypt(emptyList())
        val issuerJwt = parts.first()
        val disclosures = if (parts.size > 1) parts.drop(1) else emptyList()
        return issuerJwt to disclosureCipher.encrypt(disclosures)
    }

    /** Falls back to a format label when the credential configuration id is blank. */
    private fun inferCredentialType(configurationId: String, format: IssuanceCredentialFormat): String {
        if (configurationId.isNotBlank()) {
            return CredentialTypeLabels.canonicalWalletType(configurationId)
        }
        return when (format) {
            IssuanceCredentialFormat.SD_JWT_VC -> "SdJwtVc"
            IssuanceCredentialFormat.MSO_MDOC -> "MsoMdoc"
            IssuanceCredentialFormat.UNKNOWN -> "Unknown"
        }
    }

    /** Accepts only already-encoded mdoc artifacts; silent rebuild is intentionally rejected. */
    private fun normalizeMdoc(issued: IssuedCredential): String {
        if (mdocCredentialCodec.isEncodedMdoc(issued.rawPayload)) return issued.rawPayload
        throw IllegalArgumentException(
            "Invalid mdoc payload for '${issued.credentialConfigurationId}'. " +
                "Wallet storage preserves issuer artifacts and does not rebuild IssuerSigned silently.",
        )
    }
}
