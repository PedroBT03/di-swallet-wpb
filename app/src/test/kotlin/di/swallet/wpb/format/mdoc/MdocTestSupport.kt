package di.swallet.wpb.format.mdoc

import com.authlete.cose.COSEEC2Key
import di.swallet.wpb.config.MdocProperties
import di.swallet.wpb.domain.WalletKey
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.issuance.proof.ProofMaterial
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.core.io.DefaultResourceLoader
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Optional

object MdocTestSupport {
    fun properties(transcriptMode: String = "legacy-aud-nonce"): MdocProperties =
        MdocProperties().apply {
            issuerKeyPemPath = "classpath:mdoc/dev-issuer-key.pem"
            sessionTranscriptMode = transcriptMode
            requireHolderKeyAlias = true
        }

    data class HolderKeyBinding(
        val alias: String,
        val keyPair: KeyPair,
        val walletKey: WalletKey,
        val deviceCoseKey: COSEEC2Key,
        val proof: ProofMaterial,
    )

    fun holderBinding(alias: String = "mdoc-holder-key"): HolderKeyBinding {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey
        val publicKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.encoded)
        return HolderKeyBinding(
            alias = alias,
            keyPair = keyPair,
            walletKey = WalletKey(
                userId = "test-user",
                keyAlias = alias,
                publicKeyBase64 = publicKeyBase64,
                revocationIndex = 1,
            ),
            deviceCoseKey = MdocCoseKeyMaterial.toCoseEc2PublicKey(publicKey),
            proof = ProofMaterial(keyId = alias, publicKey = publicKey, algorithm = "ES256"),
        )
    }

    fun walletKeyRepository(keys: Collection<WalletKey>): WalletKeyRepository {
        val repository = mock(WalletKeyRepository::class.java)
        keys.forEach { key ->
            `when`(repository.findByKeyAlias(key.keyAlias)).thenReturn(Optional.of(key))
        }
        return repository
    }

    data class MdocTestStack(
        val runtime: MdocIsoRuntimeService,
        val codec: MdocCredentialCodec,
        val deviceSigner: InMemoryMdocDeviceAuthSigner,
        val properties: MdocProperties,
        val walletKeyRepository: WalletKeyRepository,
    )

    fun stack(
        transcriptMode: String = "legacy-aud-nonce",
        holderBindings: Collection<HolderKeyBinding> = emptyList(),
    ): MdocTestStack {
        val props = properties(transcriptMode)
        val deviceSigner = InMemoryMdocDeviceAuthSigner()
        holderBindings.forEach { binding ->
            deviceSigner.register(binding.alias, binding.keyPair.private as ECPrivateKey)
        }
        val walletKeyRepository = walletKeyRepository(holderBindings.map { it.walletKey })
        val resourceLoader = DefaultResourceLoader()
        val issuerKeyStore = MdocIssuerKeyStore(props, resourceLoader)
        val credentialVerifier = MdocCredentialVerifier()
        val sessionTranscriptBuilder = MdocSessionTranscriptBuilder(props)
        val runtime = MdocIsoRuntimeService(
            properties = props,
            issuerKeyStore = issuerKeyStore,
            credentialVerifier = credentialVerifier,
            deviceAuthSigner = deviceSigner,
            sessionTranscriptBuilder = sessionTranscriptBuilder,
            walletKeyRepository = walletKeyRepository,
        )
        return MdocTestStack(
            runtime = runtime,
            codec = MdocCredentialCodec(runtime),
            deviceSigner = deviceSigner,
            properties = props,
            walletKeyRepository = walletKeyRepository,
        )
    }

    fun handover(
        clientId: String = "verifier-demo-client",
        nonce: String = "nonce-123",
        responseUri: String? = null,
    ): MdocOpenId4VpHandover = MdocOpenId4VpHandover(
        clientId = clientId,
        nonce = nonce,
        audience = clientId,
        responseUri = responseUri,
    )
}
