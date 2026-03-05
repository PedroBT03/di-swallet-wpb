package di.swallet.wpb

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus

/**
 * Data Transfer Object for signing requests.
 * Helps Swagger UI define the correct JSON schema.
 */
data class SignRequest(
    val data: String
)

@RestController
@RequestMapping("/api/v1/wallet")
@Tag(name = "Wallet Management", description = "Endpoints for user wallet and key lifecycle")
class WalletController(private val hsmService: HsmService) {

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
     * The input 'data' must be provided in the JSON body.
     */
    @PostMapping("/sign/{userId}")
    @Operation(summary = "Remote Signature", description = "Triggers a signing operation inside the secure boundary of the HSM")
    fun sign(
        @PathVariable userId: String, 
        @RequestBody request: SignRequest // Use the DTO here
    ): Map<String, String> {
        
        // Execute the signing operation using the data from the DTO
        val signatureBytes = hsmService.signData(userId, request.data.toByteArray())
        val signatureBase64 = java.util.Base64.getEncoder().encodeToString(signatureBytes)

        return mapOf(
            "userId" to userId,
            "signature" to signatureBase64,
            "algorithm" to "SHA256withECDSA"
        )
    }
}