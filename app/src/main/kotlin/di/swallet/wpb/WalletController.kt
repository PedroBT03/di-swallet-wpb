package di.swallet.wpb

import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus

/**
 * Wallet Provider Interface (WPI) implementation.
 * This controller exposes the REST endpoints used by the User Domain (Mobile App)
 * to interact with the Wallet Provider Backend.
 */
@RestController
@RequestMapping("/api/v1/wallet")
class WalletController(private val hsmService: HsmService) {

    /**
     * Endpoint to generate a hardware-backed cryptographic key for a specific user.
     * The private key is generated and stored inside the Remote WSCD (HSM).
     */
    @PostMapping("/keys/{userId}")
    fun createKey(@PathVariable userId: String): WalletKey {
        return hsmService.generateKeyForUser(userId)
    }

    /**
     * Endpoint to retrieve the metadata and public key of an existing user wallet.
     */
    @GetMapping("/keys/{userId}")
    fun getKey(@PathVariable userId: String): WalletKey {
        return hsmService.getUserKey(userId)
    }

    /**
     * Endpoint to perform a digital signature operation inside the HSM.
     * This simulates the "Sign Document" functional goal of the EUDI Wallet.
     * It requires a valid authorization context (handled by the Security Interceptor).
     */
    @PostMapping("/sign/{userId}")
    fun sign(
        @PathVariable userId: String, 
        @RequestBody payload: Map<String, String>
    ): Map<String, String> {
        // Extract the data to be signed from the request body
        val data = payload["data"] 
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing 'data' field in request body")
        
        // Execute the signing operation within the secure boundary of the HSM
        val signatureBytes = hsmService.signData(userId, data.toByteArray())
        
        // Return the Base64 encoded signature
        val signatureBase64 = java.util.Base64.getEncoder().encodeToString(signatureBytes)

        return mapOf(
            "userId" to userId,
            "signature" to signatureBase64,
            "algorithm" to "SHA256withECDSA"
        )
    }
}