package di.swallet.wpb.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import com.nimbusds.jwt.JWTClaimsSet
import java.util.*
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.service.MockIssuerService
import di.swallet.wpb.domain.WalletKey

/**
 * Data Transfer Object for signing requests.
 */
data class SignRequest(
    val data: String
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
    private val mockIssuerService: MockIssuerService // 1. Inject the service here
) {

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
     * Endpoint to perform a digital signature operation inside the HSM.
     */
    @PostMapping("/sign/{userId}")
    @Operation(summary = "Remote Signature", description = "Triggers a signing operation inside the secure boundary of the HSM")
    fun sign(
        @PathVariable userId: String, 
        @RequestBody request: SignRequest
    ): Map<String, String> {
        val signatureBytes = hsmService.signData(userId, request.data.toByteArray())
        val signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes)

        return mapOf(
            "userId" to userId,
            "signature" to signatureBase64,
            "algorithm" to "SHA256withECDSA"
        )
    }

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
        
        // Build the JWT Claims
        val claims = JWTClaimsSet.Builder()
            .issuer("https://pt-mock-issuer.gov.pt")
            .subject(userId)
            .issueTime(Date())
            .expirationTime(Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365)) // 1 year
            .claim("vc", mapOf(
                "type" to listOf("VerifiableCredential", credentialType),
                "credentialSubject" to userData
            ))
            .build()

        // Sign the JWT using the HSM logic (Format Engine)
        val signedJwt = hsmService.signJwt(userId, claims)

        return mapOf(
            "userId" to userId,
            "credentialType" to credentialType,
            "format" to "JWT",
            "encoded" to signedJwt
        )
    }
}