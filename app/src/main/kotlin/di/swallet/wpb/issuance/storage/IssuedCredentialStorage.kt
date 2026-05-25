package di.swallet.wpb.issuance.storage

import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.openid4vci.protocol.IssuedCredential
import di.swallet.wpb.service.format.DisclosureCipherService
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
 * Non-SD-JWT formats are persisted with the raw payload in `encodedData`
 * and an empty disclosure set so the row is non-null. They are not yet
 * consumable by the Phase 1 presentation builder (deferred to Phase 7).
 */
interface IssuedCredentialStorage {
    fun store(holderId: String, issued: IssuedCredential, walletKey: WalletKey? = null): Long
}

@Component
class JpaIssuedCredentialStorage(
    private val repository: WalletCredentialRepository,
    private val disclosureCipher: DisclosureCipherService,
) : IssuedCredentialStorage {
    override fun store(
        holderId: String,
        issued: IssuedCredential,
        walletKey: WalletKey?,
    ): Long {
        val (encoded, encryptedDisclosures) = when (issued.format) {
            IssuanceCredentialFormat.SD_JWT_VC -> splitSdJwt(issued.rawPayload)
            else -> issued.rawPayload to disclosureCipher.encrypt(emptyList())
        }

        val credentialType = inferCredentialType(issued.credentialConfigurationId, issued.format)

        val entity = WalletCredential(
            userId = holderId,
            credentialType = credentialType,
            encodedData = encoded,
            encryptedDisclosures = encryptedDisclosures,
            walletKey = walletKey,
        )
        return repository.save(entity).id ?: error("WalletCredential persisted without id")
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
}
