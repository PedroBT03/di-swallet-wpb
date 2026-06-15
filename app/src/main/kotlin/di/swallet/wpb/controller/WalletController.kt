package di.swallet.wpb.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.service.StatusListService
import di.swallet.wpb.service.Fido2Service
import di.swallet.wpb.service.DeviceBindingService
import di.swallet.wpb.service.WalletInitCommand
import di.swallet.wpb.service.format.PresentationService
import di.swallet.wpb.service.format.DisclosureCipherService
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.domain.UserDevice
import di.swallet.wpb.security.AuthenticatedHolderGuard
import di.swallet.wpb.revocation.CredentialRevocationGuard
import di.swallet.wpb.revocation.WalletRevocationService
import di.swallet.wpb.transactionlog.service.TransactionLogger
import di.swallet.wpb.transactionlog.service.WalletCredentialDeletionService
import java.util.Base64

/**
 * Data Transfer Object for signing requests.
 */
data class SignRequest(
    @Schema(example = "Data to be signed by the HSM", description = "Raw string data to sign")
    val data: String
)

/**
 * Data Transfer Object for selective disclosure presentation requests.
 */
data class PresentationRequest(
    @Schema(example = "[\"given_name\", \"nationality\"]", description = "List of claim names to reveal to the Verifier")
    val claimsToDisclose: List<String>
)

data class WalletInitRequest(
    val holderId: String? = null,
    val platform: String,
    val devicePubJwk: String,
    val pidPubJwk: String? = null,
    val userDeviceId: Long? = null,
)

data class DpopBindRequest(
    val walletId: String,
    val devicePubJwk: String,
)

data class PidKeyBindRequest(
    val walletId: String,
    val pidPubJwk: String,
)

/**
 * Wallet Provider Interface (WPI) implementation.
 * Manages keys and credential issuance flows.
 */
@RestController
@RequestMapping("/api/v1/wallet")
@Tag(name = "Wallet Management", description = "Endpoints for user wallet and key lifecycle")
class WalletController(
    private val hsmService: HsmService,
    private val presentationService: PresentationService,
    private val disclosureCipherService: DisclosureCipherService,
    private val credentialRepository: WalletCredentialRepository,
    private val statusListService: StatusListService,
    private val fido2Service: Fido2Service,
    private val deviceBindingService: DeviceBindingService,
    private val credentialRevocationGuard: CredentialRevocationGuard,
    private val walletRevocationService: WalletRevocationService,
    private val transactionLogger: TransactionLogger,
    private val walletCredentialDeletionService: WalletCredentialDeletionService,
    private val authenticatedHolderGuard: AuthenticatedHolderGuard,
) {

    // --- SECTION 1: AUTHENTICATION & ONBOARDING ---

    /**
     * Registers a new device for FIDO2 authorization.
     * 
     * REAL-WORLD NOTE: In a production EUDI Wallet, this registration must be preceded 
     * by a High Level of Assurance (LoA High) authentication, such as using the 
     * Portuguese 'Chave Móvel Digital' or 'Cartão de Cidadão'. Once the identity 
     * is verified via an official IdP, this endpoint binds that identity to the 
     * user's physical device hardware.
     */
    @PostMapping("/auth/register/{userId}")
    @Operation(summary = "Register Device (FIDO2 Simulation)")
    fun registerDevice(
        @PathVariable userId: String,
        @RequestParam credentialId: String,
        @RequestParam publicKeyBase64: String
    ): UserDevice {
        return fido2Service.registerDevice(userId, credentialId, publicKeyBase64)
    }

    /**
     * Generates a unique cryptographic challenge (nonce) to be signed by the user's physical device.
     * This is the initial step for any operation requiring Strong User Authentication (SUA).
     */
    @GetMapping("/auth/challenge/{userId}")
    @Operation(summary = "Get Auth Challenge", description = "Generates a unique nonce for FIDO2/WebAuthn authorization.")
    fun getChallenge(@PathVariable userId: String): Map<String, String> {
        val assertionRequest = fido2Service.startAuthentication(userId)
        
        val challenge = assertionRequest.publicKeyCredentialRequestOptions.challenge.base64Url
        
        return mapOf(
            "userId" to userId,
            "challenge" to challenge,
            "info" to "Sign this challenge using your device to authorize the next operation."
        )
    }

    @PostMapping("/init")
    @Operation(summary = "Initialize wallet unit and bind bootstrap keys")
    fun walletInit(@RequestBody request: WalletInitRequest): Map<String, Any> {
        val result = deviceBindingService.initWallet(
            WalletInitCommand(
                holderId = request.holderId,
                platform = request.platform,
                devicePubJwk = request.devicePubJwk,
                pidPubJwk = request.pidPubJwk,
                userDeviceId = request.userDeviceId,
            ),
        )
        return mapOf(
            "walletId" to result.walletId,
            "state" to result.state.name,
            "dpopBound" to result.dpopBound,
            "pidKeyBound" to result.pidKeyBound,
        )
    }

    @PostMapping("/auth/dpop/bind")
    @Operation(summary = "Bind DPoP device key to wallet")
    fun bindDpop(@RequestBody request: DpopBindRequest): Map<String, Any> {
        authenticatedHolderGuard.requireWalletUnitOwned(request.walletId)
        val result = deviceBindingService.bindDpop(request.walletId, request.devicePubJwk)
        return mapOf(
            "walletId" to result.walletId,
            "state" to result.state.name,
            "dpopBound" to result.dpopBound,
            "pidKeyBound" to result.pidKeyBound,
        )
    }

    @PostMapping("/auth/pid_key/bind")
    @Operation(summary = "Bind PID key to wallet")
    fun bindPidKey(@RequestBody request: PidKeyBindRequest): Map<String, Any> {
        authenticatedHolderGuard.requireWalletUnitOwned(request.walletId)
        val result = deviceBindingService.bindPidKey(request.walletId, request.pidPubJwk)
        return mapOf(
            "walletId" to result.walletId,
            "state" to result.state.name,
            "dpopBound" to result.dpopBound,
            "pidKeyBound" to result.pidKeyBound,
        )
    }

    // --- SECTION 2: HARDWARE KEY MANAGEMENT ---

    /**
     * Endpoint to generate a hardware-backed cryptographic key for a specific user.
     */
    @PostMapping("/keys/{userId}")
    @Operation(summary = "Generate Hardware-backed Key", description = "Creates an EC KeyPair inside the Remote HSM for the user")
    fun createKey(@PathVariable userId: String): WalletKey {
        authenticatedHolderGuard.requireSelf(userId)
        return hsmService.generateKeyForUser(userId)
    }

    /**
     * Endpoint to retrieve the metadata and public key of an existing user wallet.
     */
    @GetMapping("/keys/{userId}")
    @Operation(summary = "Get Wallet Metadata", description = "Retrieves the public key and status of a user's wallet")
    fun getKey(@PathVariable userId: String): WalletKey {
        authenticatedHolderGuard.requireSelf(userId)
        return hsmService.getUserKey(userId)
    }

    /**
     * Revokes a user's key by setting its bit to 1 in the Status List.
     */
    @PostMapping("/keys/{userId}/revoke")
    @Operation(summary = "Revoke Key", description = "Sets the revocation bit to 1 for this user's key index.")
    fun revokeKey(@PathVariable userId: String): Map<String, String> {
        authenticatedHolderGuard.requireSelf(userId)
        val key = hsmService.getUserKey(userId)
        statusListService.revoke(key.revocationIndex)
        return mapOf("status" to "REVOKED", "index" to key.revocationIndex.toString())
    }

    // --- SECTION 3: CREDENTIAL MANAGEMENT ---

    @DeleteMapping("/credentials/{credentialId}")
    @Operation(summary = "Delete credential from wallet", description = "User-initiated deletion (DASH_05a), distinct from revocation.")
    fun deleteCredential(@PathVariable credentialId: Long): Map<String, Any> {
        authenticatedHolderGuard.requireCredentialOwned(credentialId)
        walletCredentialDeletionService.deleteCredential(credentialId)
        return mapOf("credentialId" to credentialId, "status" to "DELETED")
    }

    @PostMapping("/credentials/{credentialId}/revoke")
    @Operation(summary = "Revoke WP-managed credential", description = "Sets the credential status bit and marks it REVOKED.")
    fun revokeCredential(@PathVariable credentialId: Long): Map<String, Any> {
        authenticatedHolderGuard.requireCredentialOwned(credentialId)
        walletRevocationService.revokeCredential(credentialId)
        return mapOf("credentialId" to credentialId, "status" to "REVOKED")
    }

    @PostMapping("/units/{walletId}/revoke")
    @Operation(summary = "Revoke wallet unit", description = "Revokes WIA/KA indexes, wallet keys, and WP-managed credentials.")
    fun revokeWalletUnit(@PathVariable walletId: String): Map<String, Any> {
        authenticatedHolderGuard.requireWalletUnitOwned(walletId)
        walletRevocationService.revokeWalletUnit(walletId)
        return mapOf("walletId" to walletId, "status" to "REVOKED")
    }

    // --- SECTION 4: CREDENTIAL USAGE & SIGNING ---

    /**
     * Endpoint to filter a stored SD-JWT and create a minimized presentation.
     */
    @PostMapping("/credentials/{credentialId}/presentation")
    @Operation(summary = "Create Minimized Presentation", description = "Reveals only specific attributes from a stored SD-JWT as authorized by the user.")
    fun createPresentation(
        @PathVariable credentialId: Long,
        @RequestBody request: PresentationRequest
    ): Map<String, Any> {
        authenticatedHolderGuard.requireCredentialOwned(credentialId)
        val credential = credentialRepository.findById(credentialId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Credential not found") }

        credentialRevocationGuard.requirePresentable(credential)

        // Backward-compatible support for legacy records that still contain inline disclosures.
        val fullSdJwt = if (credential.encodedData.contains("~")) {
            credential.encodedData
        } else {
            val disclosures = disclosureCipherService.decrypt(credential.encryptedDisclosures)
            val rebuilt = StringBuilder(credential.encodedData)
            disclosures.forEach { rebuilt.append("~").append(it) }
            rebuilt.append("~")
            rebuilt.toString()
        }

        // Filter the multipart token to include only requested disclosures.
        val minimizedSdJwt = presentationService.createSelectivePresentation(fullSdJwt, request.claimsToDisclose)

        transactionLogger.logLegacyPresentation(
            holderId = credential.userId,
            credentialType = credential.credentialType,
            claimsRequested = request.claimsToDisclose,
            claimsPresented = request.claimsToDisclose,
            completed = true,
        )

        return mapOf(
            "userId" to credential.userId,
            "credentialType" to credential.credentialType,
            "format" to "SD-JWT",
            "presentation" to minimizedSdJwt,
            "revealedClaims" to request.claimsToDisclose
        )
    }

    /**
     * Endpoint to perform a digital signature operation inside the HSM.
     */
    @PostMapping("/sign/{userId}")
    @Operation(summary = "Remote Signature", description = "Triggers a signing operation inside the secure boundary of the HSM")
    fun sign(
        @PathVariable userId: String, 
        @RequestBody request: SignRequest
    ): Map<String, String> {
        authenticatedHolderGuard.requireSelf(userId)
        val key = hsmService.getUserKey(userId)

        // Validate key state before cryptographic execution
        hsmService.validateKeyStatus(key)

        val payload = request.data.toByteArray()
        val signatureBytes = hsmService.signData(userId, payload)
        val signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes)

        transactionLogger.logSigning(
            holderId = userId,
            payload = payload,
            algorithm = "SHA256withECDSA",
            completed = true,
        )

        return mapOf(
            "userId" to userId,
            "signature" to signatureBase64,
            "algorithm" to "SHA256withECDSA"
        )
    }

    // --- SECTION 5: DATA RETRIEVAL ---

    /**
     * Endpoint to retrieve all issued credentials for a user.
     */
    @GetMapping("/credentials/{userId}")
    @Operation(summary = "List User Credentials", description = "Retrieves all issued credentials for the user.")
    fun getCredentials(@PathVariable userId: String): List<WalletCredential> {
        authenticatedHolderGuard.requireSelf(userId)
        return credentialRepository.findByUserId(userId)
    }
}