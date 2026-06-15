/**
 * REST API for managing WebAuthn passkey pseudonyms and running registration or authentication ceremonies.
 */

package di.swallet.wpb.pseudonym

import di.swallet.wpb.security.AuthenticatedHolderGuard
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Holder-scoped pseudonym lifecycle and WebAuthn ceremony endpoints. */
@RestController
@RequestMapping("/api/v1/wallet/pseudonyms")
@Tag(name = "Pseudonyms", description = "WebAuthn passkey pseudonyms for Relying Parties (Topic 11 / Use Case A)")
class PseudonymController(
    private val service: PseudonymService,
    private val authenticatedHolderGuard: AuthenticatedHolderGuard,
) {
    /** Lists pseudonyms for a holder, optionally filtered by relying party ID. */
    @GetMapping
    @Operation(summary = "List pseudonyms for a holder, optionally filtered by rpId")
    fun list(
        @RequestParam holderId: String,
        @RequestParam(required = false) rpId: String?,
    ): List<PseudonymView> {
        authenticatedHolderGuard.requireSelf(holderId)
        return service.list(holderId, rpId)
    }

    /** Creates a pending pseudonym slot for a relying party. */
    @PostMapping
    @Operation(summary = "Create a pseudonym slot for an RP")
    fun create(@RequestBody request: CreatePseudonymRequest): PseudonymView {
        authenticatedHolderGuard.requireSelf(request.holderId)
        return service.create(request)
    }

    /** Updates the user-friendly alias shown for a pseudonym. */
    @PatchMapping("/{id}/alias")
    @Operation(summary = "Update the user-friendly alias for a pseudonym")
    fun updateAlias(
        @PathVariable id: UUID,
        @RequestParam holderId: String,
        @RequestBody request: UpdatePseudonymAliasRequest,
    ): PseudonymView {
        authenticatedHolderGuard.requireSelf(holderId)
        return service.updateAlias(id, holderId, request.alias)
    }

    /** Deletes a pseudonym and its dedicated HSM key material. */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a pseudonym and its HSM key material")
    fun delete(
        @PathVariable id: UUID,
        @RequestParam holderId: String,
    ) {
        authenticatedHolderGuard.requireSelf(holderId)
        service.delete(id, holderId)
    }

    /** Starts a WebAuthn registration ceremony for a pending pseudonym. */
    @PostMapping("/{id}/registration/options")
    @Operation(summary = "Begin WebAuthn registration ceremony")
    fun registrationOptions(
        @PathVariable id: UUID,
        @RequestBody request: RegistrationOptionsRequest,
    ): RegistrationOptionsResponse {
        authenticatedHolderGuard.requireSelf(request.holderId)
        return service.registrationOptions(id, request.holderId, request)
    }

    /** Completes WebAuthn registration and logs a PseudonymGeneration transaction. */
    @PostMapping("/{id}/registration/finish")
    @Operation(summary = "Complete WebAuthn registration and log PseudonymGeneration")
    fun finishRegistration(
        @PathVariable id: UUID,
        @RequestBody request: RegistrationFinishRequest,
    ): RegistrationFinishResponse {
        authenticatedHolderGuard.requireSelf(request.holderId)
        return service.finishRegistration(id, request.holderId, request)
    }

    /** Starts a WebAuthn authentication ceremony for a registered pseudonym. */
    @PostMapping("/{id}/authentication/options")
    @Operation(summary = "Begin WebAuthn authentication ceremony")
    fun authenticationOptions(
        @PathVariable id: UUID,
        @RequestBody request: AuthenticationOptionsRequest,
    ): AuthenticationOptionsResponse {
        authenticatedHolderGuard.requireSelf(request.holderId)
        return service.authenticationOptions(id, request.holderId, request)
    }

    /** Completes WebAuthn authentication and logs a PseudonymousAuthentication transaction. */
    @PostMapping("/{id}/authentication/finish")
    @Operation(summary = "Complete WebAuthn authentication and log PseudonymousAuthentication")
    fun finishAuthentication(
        @PathVariable id: UUID,
        @RequestBody request: AuthenticationFinishRequest,
    ): AuthenticationFinishResponse {
        authenticatedHolderGuard.requireSelf(request.holderId)
        return service.finishAuthentication(id, request.holderId, request)
    }
}
