package di.swallet.wpb.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import com.nimbusds.jwt.JWTClaimsSet
import java.util.*
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.service.MockIssuerService
import di.swallet.wpb.service.StatusListService
import di.swallet.wpb.service.Fido2Service
import di.swallet.wpb.service.format.SdJwtService
import di.swallet.wpb.service.format.PresentationService
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.domain.WalletCredentialRepository
import di.swallet.wpb.domain.UserDevice
import di.swallet.wpb.security.ChallengeService

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

/**
 * Wallet Provider Interface (WPI) implementation.
 * Manages keys and credential issuance flows.
 */
@RestController
@RequestMapping("/api/v1/wallet")
@Tag(name = "Wallet Management", description = "Endpoints for user wallet and key lifecycle")
class WalletController(
    private val hsmService: HsmService,
    private val mockIssuerService: MockIssuerService,
    private val sdJwtService: SdJwtService,
    private val presentationService: PresentationService,
    private val credentialRepository: WalletCredentialRepository,
    private val challengeService: ChallengeService,
    private val statusListService: StatusListService,
    private val fido2Service: Fido2Service
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

    // --- SECTION 2: HARDWARE KEY MANAGEMENT ---

    /**
     * Endpoint to generate a hardware-backed cryptographic key for a specific user.
     */
    @PostMapping("/keys/{userId}")
    @Operation(summary = "Generate Hardware-backed Key", description = "Creates an EC KeyPair inside the Remote HSM for the user")
    fun createKey(@PathVariable userId: String): WalletKey {
        return hsmService.generateKeyForUser(userId)
    }

    /**
     * Endpoint to retrieve the metadata and public key of an existing user wallet.
     */
    @GetMapping("/keys/{userId}")
    @Operation(summary = "Get Wallet Metadata", description = "Retrieves the public key and status of a user's wallet")
    fun getKey(@PathVariable userId: String): WalletKey {
        return hsmService.getUserKey(userId)
    }

    /**
     * Revokes a user's key by setting its bit to 1 in the Status List.
     */
    @PostMapping("/keys/{userId}/revoke")
    @Operation(summary = "Revoke Key", description = "Sets the revocation bit to 1 for this user's key index.")
    fun revokeKey(@PathVariable userId: String): Map<String, String> {
        val key = hsmService.getUserKey(userId)
        statusListService.revoke(key.revocationIndex)
        return mapOf("status" to "REVOKED", "index" to key.revocationIndex.toString())
    }

    // --- SECTION 3: CREDENTIAL ISSUANCE ---

    /**
     * Simulates the issuance of a Verifiable Credential (PID) using a Mock Issuer.
     * The resulting JWT is signed by the User's Private Key inside the HSM.
     */
    @PostMapping("/credentials/issue/{userId}")
    @Operation(summary = "Issue Mock PID", description = "Simulates the issuance of a Person Identification Data credential signed by the HSM")
    fun issueCredential(
        @PathVariable userId: String,
        @RequestParam credentialType: String = "PID"
    ): Map<String, Any> {
        val userData = mockIssuerService.fetchUserData(userId)
        
        val claims = JWTClaimsSet.Builder()
            .issuer("https://pt-mock-issuer.gov.pt")
            .subject(userId)
            .issueTime(Date())
            .expirationTime(Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365))
            .claim("vc", mapOf(
                "type" to listOf("VerifiableCredential", credentialType),
                "credentialSubject" to userData
            ))
            .build()

        val signedJwt = hsmService.signJwt(userId, claims)

        return mapOf(
            "userId" to userId,
            "credentialType" to credentialType,
            "format" to "JWT",
            "encoded" to signedJwt
        )
    }

    /**
     * Endpoint to issue an SD-JWT credential, which includes the hashing and salting of claims.
     * The final SD-JWT is signed by the User's Private Key inside the HSM and stored in the database.
     */
    @PostMapping("/credentials/issue-sd/{userId}")
    @Operation(summary = "Issue and Store SD-JWT", description = "Generates an SD-JWT and persists it in the database.")
    fun issueSdCredential(@PathVariable userId: String): WalletCredential {
        val walletKey = hsmService.getUserKey(userId)
        val userData = mockIssuerService.fetchUserData(userId)

        // Process each attribute with salted hashing to enable selective disclosure
        val disclosures = mutableListOf<String>()
        val hashedDisclosures = mutableListOf<String>()
        userData.forEach { (key, value) ->
            val disclosure = sdJwtService.createDisclosure(key, value)
            disclosures.add(disclosure)
            hashedDisclosures.add(sdJwtService.hashDisclosure(disclosure))
        }

        val sdPayload = mapOf(
            "iss" to "https://pt-mock-issuer.gov.pt",
            "sub" to userId,
            "iat" to System.currentTimeMillis() / 1000,
            "_sd" to hashedDisclosures.sorted(),
            "_sd_alg" to "sha-256"
        )

        val signedJwt = hsmService.signSdJwt(userId, sdPayload)
        val finalSdJwt = StringBuilder(signedJwt)
        disclosures.forEach { finalSdJwt.append("~").append(it) }
        finalSdJwt.append("~")

        val credential = WalletCredential(
            userId = userId,
            credentialType = "PID",
            encodedData = finalSdJwt.toString(),
            walletKey = walletKey
        )

        return credentialRepository.save(credential)
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
        val credential = credentialRepository.findById(credentialId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Credential not found") }

        // Check revocation status before presentation
        val walletKey = credential.walletKey ?: throw RuntimeException("No key associated with credential")
        hsmService.validateKeyStatus(walletKey)

        // Filter the multipart string to include only requested disclosures
        val minimizedSdJwt = presentationService.createSelectivePresentation(
            credential.encodedData, 
            request.claimsToDisclose
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
        val key = hsmService.getUserKey(userId)

        // Validate key state before cryptographic execution
        hsmService.validateKeyStatus(key)

        val signatureBytes = hsmService.signData(userId, request.data.toByteArray())
        val signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes)

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
        return credentialRepository.findByUserId(userId)
    }
}