package di.swallet.wpb.transactionlog.export

import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.issuance.domain.IssuanceCredentialFormat
import di.swallet.wpb.transactionlog.domain.Ts10CredentialInfo
import di.swallet.wpb.transactionlog.domain.Ts10MigrationData
import di.swallet.wpb.transactionlog.domain.Ts10NonDeviceBoundCredential
import di.swallet.wpb.transactionlog.domain.Ts10Transaction
import di.swallet.wpb.transactionlog.CredentialIssuerResolver
import di.swallet.wpb.transactionlog.service.TransactionLogService
import org.springframework.stereotype.Component

@Component
class MigrationObjectBuilder(
    private val credentialRepository: WalletCredentialRepository,
    private val transactionLogService: TransactionLogService,
    private val credentialIssuerResolver: CredentialIssuerResolver,
) {
    fun build(holderId: String, includeNonDeviceBound: Boolean): Ts10MigrationData {
        val credentials = credentialRepository.findByUserId(holderId)
        val transactionLog = transactionLogService.list(holderId)
            .map { transactionLogService.get(holderId, it.transactionId) }

        return Ts10MigrationData(
            transactionLog = transactionLog,
            listOfCredentials = credentials.map { toCredentialInfo(it) },
            nonDeviceBoundCredentials = if (includeNonDeviceBound) {
                credentials.filter { !it.deviceBound }.mapNotNull { toNonDeviceBound(it) }
            } else {
                emptyList()
            },
        )
    }

    private fun toCredentialInfo(credential: WalletCredential): Ts10CredentialInfo {
        val issuer = credentialIssuerResolver.resolve(credential)
        return Ts10CredentialInfo(
            credentialIdentifier = credential.credentialType,
            format = inferFormat(credential),
            issuerName = issuer.name,
            issuerIdentifier = issuer.identifier,
            issuerType = if (credential.issuerStatusUri != null) "EAAProvider" else "PIDProvider",
            supplyPointURL = credential.issuerStatusUri,
        )
    }

    private fun toNonDeviceBound(credential: WalletCredential): Ts10NonDeviceBoundCredential? {
        val raw = credential.encodedData
        if (raw.isBlank()) return null
        return Ts10NonDeviceBoundCredential(
            format = inferFormat(credential),
            credential = raw,
        )
    }

    private fun inferFormat(credential: WalletCredential): String = when {
        credential.credentialType.contains("mdoc", ignoreCase = true) ||
            credential.credentialType.contains("mso", ignoreCase = true) -> "mso_mdoc"
        credential.encodedData.contains("~") || credential.encryptedDisclosures.isNotBlank() -> "dc+sd-jwt"
        else -> "jwt_vc_json"
    }
}
