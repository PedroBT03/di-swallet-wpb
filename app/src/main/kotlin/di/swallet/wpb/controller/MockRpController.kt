/**
 * Non-production mock relying party endpoint for verifying SD-JWT presentations.
 */

package di.swallet.wpb.controller

import di.swallet.wpb.service.verification.VerificationService
import di.swallet.wpb.service.HsmService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*

/** Request body containing an SD-JWT presentation and the holder whose key should verify it. */
data class VerificationRequest(
    val sdJwt: String,
    val userId: String // The Verifier needs to know whose key to check
)

/**
 * Simulates an external verifier that checks SD-JWT signatures and disclosed claims.
 */
@RestController
@RequestMapping("/api/v1/mock-rp")
@Tag(name = "Mock Relying Party", description = "Simulates an external verifier checking a presentation")
class MockRpController(
    private val verificationService: VerificationService,
    private val hsmService: HsmService
) {

    /**
     * Verifies the presentation signature and returns the disclosed claim values.
     */
    @PostMapping("/verify")
    @Operation(summary = "Verify Presentation", description = "Acts as an RP to verify the signature and disclosures of an SD-JWT.")
    fun verify(@RequestBody request: VerificationRequest): Map<String, Any> {
        // In a real scenario, the Verifier would get the public key from a Trust List or DID
        // Here, we retrieve it from our HSM Service for the simulation.
        val walletKey = hsmService.getUserKey(request.userId)
        
        val verifiedData = verificationService.verifyPresentation(request.sdJwt, walletKey.publicKeyBase64)

        return mapOf(
            "status" to "VALID",
            "verifiedClaims" to verifiedData,
            "message" to "The integrity and authenticity of the disclosed attributes are verified."
        )
    }
}
