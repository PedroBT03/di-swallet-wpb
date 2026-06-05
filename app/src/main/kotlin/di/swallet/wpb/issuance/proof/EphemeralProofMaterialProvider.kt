package di.swallet.wpb.issuance.proof

import di.swallet.wpb.openid4vci.protocol.ResolvedIssuerMetadata
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 2 MVP provider that emits an ephemeral EC key pair per holder.
 *
 * The wallet's hardware-backed signer is the long-term target for proof of
 * possession (Phase 3). For now the orchestrator uses ephemeral material so
 * the SDK adapter can still construct an issuance proof envelope without
 * requiring a working HSM session.
 *
 * The same `keyId` is reused per holder for the lifetime of the JVM so that
 * resumed deferred issuance keeps a stable PoP key.
 *
 * Disabled by default; enable only for isolated unit tests via
 * `wpb.openid4vci.proof.ephemeral-fallback=true`.
 */
@Component
@ConditionalOnProperty(
    prefix = "wpb.openid4vci.proof",
    name = ["ephemeral-fallback"],
    havingValue = "true",
)
class EphemeralProofMaterialProvider : ProofMaterialProvider {

    private val perHolder = ConcurrentHashMap<String, ProofMaterial>()

    override fun provide(holderId: String, metadata: ResolvedIssuerMetadata?): ProofMaterial =
        perHolder.computeIfAbsent(holderId) { generate() }

    private fun generate(): ProofMaterial {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return ProofMaterial(
            keyId = "ephemeral-${UUID.randomUUID()}",
            publicKey = keyPair.public as ECPublicKey,
            algorithm = "ES256",
        )
    }
}
