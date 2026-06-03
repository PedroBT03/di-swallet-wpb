package di.swallet.wpb.issuance.storage

import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.format.mdoc.MdocCredentialCodec
import di.swallet.wpb.format.mdoc.MdocCredentialDocument
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import di.swallet.wpb.service.format.DisclosureCipherService
import di.swallet.wpb.service.KeyBindingRuntimeService
import di.swallet.wpb.domain.CredentialBindingFormat
import org.springframework.stereotype.Component

/**
 * Persists credentials issued through OID4VCI back into the existing
 * [WalletCredentialRepository] so they become available to the
 * Phase 1 presentation flow.
 *
 * For Phase 2 MVP the storage performs a best-effort split of the
 * SD-JWT VC value `<issuer-jwt>~<d1>~...~<dN>` into:
 *   - `encodedData`: the issuer-signed JWT (without disclosures)
 *   - `encryptedDisclosures`: the AES-GCM ciphertext of the disclosure list
 *
 * Non-SD-JWT formats are persisted with empty disclosure sets; for mdoc the
 * payload is normalized to a structured runtime envelope consumed by the
 * Phase 7 presentation path.
 */
interface IssuedCredentialStorage {
    fun store(
        holderId: String,
        issued: IssuedCredential,
        walletKey: WalletKey? = null,
        keyAliasHint: String? = null,
    ): Long
}

@Component
class JpaIssuedCredentialStorage(
    private val repository: WalletCredentialRepository,
    private val walletKeyRepository: WalletKeyRepository,
    private val disclosureCipher: DisclosureCipherService,
    private val mdocCredentialCodec: MdocCredentialCodec,
    private val mdocDocTypeRegistry: MdocDocTypeRegistry,
    private val keyBindingRuntimeService: KeyBindingRuntimeService,
) : IssuedCredentialStorage {
    override fun store(
        holderId: String,
        issued: IssuedCredential,
        walletKey: WalletKey?,
        keyAliasHint: String?,
    ): Long {
        val (encoded, encryptedDisclosures) = when (issued.format) {
            IssuanceCredentialFormat.SD_JWT_VC -> splitSdJwt(issued.rawPayload)
            IssuanceCredentialFormat.MSO_MDOC -> normalizeMdoc(issued) to disclosureCipher.encrypt(emptyList())
            IssuanceCredentialFormat.UNKNOWN -> issued.rawPayload to disclosureCipher.encrypt(emptyList())
        }

        val credentialType = inferCredentialType(issued.credentialConfigurationId, issued.format)

        val resolvedKey = walletKey
            ?: keyAliasHint?.let { walletKeyRepository.findByKeyAlias(it).orElse(null) }

        val entity = WalletCredential(
            userId = holderId,
            credentialType = credentialType,
            encodedData = encoded,
            encryptedDisclosures = encryptedDisclosures,
            walletKey = resolvedKey,
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

    private fun splitSdJwt(raw: String): Pair<String, String> {
        val parts = raw.split("~").filter { it.isNotBlank() }
        if (parts.isEmpty()) return raw to disclosureCipher.encrypt(emptyList())
        val issuerJwt = parts.first()
        val disclosures = if (parts.size > 1) parts.drop(1) else emptyList()
        return issuerJwt to disclosureCipher.encrypt(disclosures)
    }

    private fun inferCredentialType(configurationId: String, format: IssuanceCredentialFormat): String {
        if (configurationId.isNotBlank()) return configurationId
        return when (format) {
            IssuanceCredentialFormat.SD_JWT_VC -> "SdJwtVc"
            IssuanceCredentialFormat.MSO_MDOC -> "MsoMdoc"
            IssuanceCredentialFormat.UNKNOWN -> "Unknown"
        }
    }

    private fun normalizeMdoc(issued: IssuedCredential): String {
        if (mdocCredentialCodec.isEncodedMdoc(issued.rawPayload)) return issued.rawPayload
        throw IllegalArgumentException(
            "Invalid mdoc payload for '${issued.credentialConfigurationId}'. " +
                "Wallet storage preserves issuer artifacts and does not rebuild IssuerSigned silently.",
        )
    }
}
