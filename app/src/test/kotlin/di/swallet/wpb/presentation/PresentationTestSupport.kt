/**
 * Shared test helpers for presentation.
 */

package di.swallet.wpb.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import di.swallet.wpb.config.WalletProperties
import di.swallet.wpb.testWalletProperties
import di.swallet.wpb.domain.WalletCredential
import di.swallet.wpb.format.mdoc.MdocCredentialCodec
import di.swallet.wpb.format.mdoc.MdocDocTypeRegistry
import di.swallet.wpb.format.mdoc.MdocEffectiveDocTypeResolver
import di.swallet.wpb.format.sdjwt.SdJwtDisclosureSelector
import di.swallet.wpb.format.sdjwt.SdJwtService
import di.swallet.wpb.presentation.matching.DefaultCredentialMatcher
import di.swallet.wpb.revocation.RevocationTestSupport
import di.swallet.wpb.service.format.DisclosureCipherService

object PresentationTestSupport {
    private val objectMapper = ObjectMapper()
    private val sdJwtService = SdJwtService(objectMapper)
    private val disclosureCipher = DisclosureCipherService(testWalletProperties())
    val disclosureSelector = SdJwtDisclosureSelector(objectMapper, sdJwtService)

    /** Builds a DefaultCredentialMatcher wired with the supplied repository, mdoc stack, and demo flag. */
    fun credentialMatcher(
        repository: di.swallet.wpb.domain.WalletCredentialRepository,
        mdocCodec: MdocCredentialCodec,
        mdocRegistry: MdocDocTypeRegistry,
        demoMode: Boolean,
    ): DefaultCredentialMatcher = DefaultCredentialMatcher(
        repository,
        mdocCodec,
        mdocRegistry,
        MdocEffectiveDocTypeResolver(mdocRegistry),
        disclosureCipher,
        disclosureSelector,
        demoMode = demoMode,
        credentialRevocationGuard = RevocationTestSupport.noopGuard(),
    )

    /** Creates a wallet credential with one encrypted SD-JWT disclosure for the named claim. */
    fun sdJwtCredential(
        id: Long,
        userId: String,
        claimName: String,
        claimValue: Any = "test",
        credentialType: String = "PID",
    ): WalletCredential {
        val disc = sdJwtService.createDisclosure(claimName, claimValue)
        return WalletCredential(
            id = id,
            userId = userId,
            credentialType = credentialType,
            encodedData = "HEAD.PAYLOAD.SIG",
            encryptedDisclosures = disclosureCipher.encrypt(listOf(disc)),
        )
    }
}
