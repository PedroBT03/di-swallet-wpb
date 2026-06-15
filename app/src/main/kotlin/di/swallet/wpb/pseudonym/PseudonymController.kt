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

@RestController
@RequestMapping("/api/v1/wallet/pseudonyms")
@Tag(name = "Pseudonyms", description = "WebAuthn passkey pseudonyms for Relying Parties (Topic 11 / Use Case A)")
class PseudonymController(
    private val service: PseudonymService,
    private val authenticatedHolderGuard: AuthenticatedHolderGuard,
) {
    @GetMapping
    @Operation(summary = "List pseudonyms for a holder, optionally filtered by rpId")
    fun list(
        @RequestParam holderId: String,
        @RequestParam(required = false) rpId: String?,
    ): List<PseudonymView> {
        authenticatedHolderGuard.requireSelf(holderId)
        return service.list(holderId, rpId)
    }

    @PostMapping
    @Operation(summary = "Create a pseudonym slot for an RP")
    fun create(@RequestBody request: CreatePseudonymRequest): PseudonymView {
        authenticatedHolderGuard.requireSelf(request.holderId)
        return service.create(request)
    }

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

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a pseudonym and its HSM key material")
    fun delete(
        @PathVariable id: UUID,
        @RequestParam holderId: String,
    ) {
        authenticatedHolderGuard.requireSelf(holderId)
        service.delete(id, holderId)
    }

    @PostMapping("/{id}/registration/options")
    @Operation(summary = "Begin WebAuthn registration ceremony")
    fun registrationOptions(
        @PathVariable id: UUID,
        @RequestBody request: RegistrationOptionsRequest,
    ): RegistrationOptionsResponse {
        authenticatedHolderGuard.requireSelf(request.holderId)
        return service.registrationOptions(id, request.holderId, request)
    }

    @PostMapping("/{id}/registration/finish")
    @Operation(summary = "Complete WebAuthn registration and log PseudonymGeneration")
    fun finishRegistration(
        @PathVariable id: UUID,
        @RequestBody request: RegistrationFinishRequest,
    ): RegistrationFinishResponse {
        authenticatedHolderGuard.requireSelf(request.holderId)
        return service.finishRegistration(id, request.holderId, request)
    }

    @PostMapping("/{id}/authentication/options")
    @Operation(summary = "Begin WebAuthn authentication ceremony")
    fun authenticationOptions(
        @PathVariable id: UUID,
        @RequestBody request: AuthenticationOptionsRequest,
    ): AuthenticationOptionsResponse {
        authenticatedHolderGuard.requireSelf(request.holderId)
        return service.authenticationOptions(id, request.holderId, request)
    }

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
