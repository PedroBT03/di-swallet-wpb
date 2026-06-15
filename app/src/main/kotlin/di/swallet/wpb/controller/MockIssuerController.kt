/**
 * Development-only mock credential issuance endpoints for local demos.
 */

package di.swallet.wpb.controller

import com.nimbusds.jwt.JWTClaimsSet
import di.swallet.wpb.domain.CredentialBindingFormat
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.format.sdjwt.SdJwtService
import di.swallet.wpb.revocation.WpCredentialStatusAllocator
import di.swallet.wpb.security.AuthenticatedHolderGuard
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.service.KeyBindingRuntimeService
import di.swallet.wpb.service.LegacySdJwtIssuanceSupport
import di.swallet.wpb.service.MockIssuerService
import di.swallet.wpb.service.format.DisclosureCipherService
import di.swallet.wpb.transactionlog.service.TransactionLogger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.Date

/**
 * Development-only mock PID issuance. Production issuance must use OID4VCI (Phase 2).
 */
@RestController
@RequestMapping("/api/v1/wallet")
@Profile("dev")
@Tag(name = "Mock Issuer (dev)", description = "Simulates a government PID Provider for local demos")
class MockIssuerController(
    private val hsmService: HsmService,
    private val mockIssuerService: MockIssuerService,
    private val sdJwtService: SdJwtService,
    private val disclosureCipherService: DisclosureCipherService,
    private val credentialRepository: WalletCredentialRepository,
    private val keyBindingRuntimeService: KeyBindingRuntimeService,
    private val legacySdJwtIssuanceSupport: LegacySdJwtIssuanceSupport,
    private val wpCredentialStatusAllocator: WpCredentialStatusAllocator,
    private val transactionLogger: TransactionLogger,
    private val authenticatedHolderGuard: AuthenticatedHolderGuard,
) {
    /**
     * Issues a plain signed JWT credential for local testing without persisting it.
     */
    @PostMapping("/credentials/issue/{userId}")
    @Operation(summary = "Issue Mock PID", description = "Simulates the issuance of a Person Identification Data credential signed by the HSM")
    fun issueCredential(
        @PathVariable userId: String,
        @RequestParam credentialType: String = "PID",
    ): Map<String, Any> {
        authenticatedHolderGuard.requireSelf(userId)
        val userData = mockIssuerService.fetchUserData(userId)

        val claims = JWTClaimsSet.Builder()
            .issuer("https://pt-mock-issuer.gov.pt")
            .subject(userId)
            .issueTime(Date())
            .expirationTime(Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365))
            .claim(
                "vc",
                mapOf(
                    "type" to listOf("VerifiableCredential", credentialType),
                    "credentialSubject" to userData,
                ),
            )
            .build()

        val signedJwt = hsmService.signJwt(userId, claims)

        return mapOf(
            "userId" to userId,
            "credentialType" to credentialType,
            "format" to "JWT",
            "encoded" to signedJwt,
        )
    }

    /**
     * Issues an SD-JWT PID, persists it in the wallet, and binds it to the holder key.
     */
    @PostMapping("/credentials/issue-sd/{userId}")
    @Operation(summary = "Issue and Store SD-JWT", description = "Generates an SD-JWT and persists it in the database.")
    fun issueSdCredential(@PathVariable userId: String): WalletCredential {
        authenticatedHolderGuard.requireSelf(userId)
        val walletKey = hsmService.getUserKey(userId)
        legacySdJwtIssuanceSupport.ensureKaForHolderKey(userId, walletKey.keyAlias, walletKey.publicKeyBase64)
        val userData = mockIssuerService.fetchUserData(userId)

        val issued = sdJwtService.disclosuresFromClaimMap(userData)
        val statusAllocation = wpCredentialStatusAllocator.allocate()

        val sdPayload = mutableMapOf<String, Any>(
            "iss" to "https://pt-mock-issuer.gov.pt",
            "sub" to userId,
            "iat" to System.currentTimeMillis() / 1000,
            "_sd" to issued.digests,
            "_sd_alg" to "sha-256",
            "credentialStatus" to wpCredentialStatusAllocator.buildCredentialStatusClaim(statusAllocation),
        )

        val signedJwt = hsmService.signSdJwt(userId, sdPayload)
        val encryptedDisclosures = disclosureCipherService.encrypt(issued.disclosures)

        val credential = WalletCredential(
            userId = userId,
            credentialType = "PID",
            encodedData = signedJwt,
            encryptedDisclosures = encryptedDisclosures,
            walletKey = walletKey,
            statusListId = statusAllocation.listId,
            statusListIndex = statusAllocation.index,
            deviceBound = true,
        )

        val saved = credentialRepository.save(credential)
        val credentialId = saved.id ?: error("WalletCredential persisted without id")
        keyBindingRuntimeService.bindCredentialToKey(
            credentialId = credentialId,
            keyAlias = walletKey.keyAlias,
            format = CredentialBindingFormat.SD_JWT,
        )
        transactionLogger.logLegacyIssuance(
            holderId = userId,
            credentialType = "PID",
            issuerName = "pt-mock-issuer.gov.pt",
            issuerId = "PLKRS.0000123456",
        )
        return saved
    }
}
