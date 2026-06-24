/**
 * Verifies the WIA attests the device (DPoP) key while the KA attests the holder HSM key,
 * so the two attestations bind to distinct keys.
 */

package di.swallet.wpb.wallet

import com.nimbusds.jwt.SignedJWT
import di.swallet.wpb.BaseIntegrationTest
import di.swallet.wpb.domain.WalletKeyRepository
import di.swallet.wpb.issuance.crypto.Rfc7638JwkThumbprint
import di.swallet.wpb.service.DeviceBindingService
import di.swallet.wpb.service.WalletInitCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class WiaKaKeySeparationTest : BaseIntegrationTest() {

    @Autowired
    lateinit var deviceBindingService: DeviceBindingService

    @Autowired
    lateinit var walletKeyRepository: WalletKeyRepository

    @Test
    fun `wia cnf attests device key while ka attests holder hsm key`() {
        val holderId = "wia-ka-sep-${UUID.randomUUID()}"
        val deviceJwk = WalletTestSupport.ecPublicJwk()
        val deviceThumbprint = Rfc7638JwkThumbprint.fromJwkJson(deviceJwk)

        val result = deviceBindingService.initWallet(
            WalletInitCommand(holderId = holderId, platform = "test", devicePubJwk = deviceJwk),
        )

        val wiaJwt = assertNotNull(result.wiaJwt).let { result.wiaJwt!! }
        val kaJwt = assertNotNull(result.kaJwt).let { result.kaJwt!! }

        val hsmKey = walletKeyRepository.findByUserId(holderId).orElseThrow()
        val hsmThumbprint = Rfc7638JwkThumbprint.fromPublicKeyBase64(hsmKey.publicKeyBase64)

        @Suppress("UNCHECKED_CAST")
        val wiaCnf = SignedJWT.parse(wiaJwt).jwtClaimsSet.getClaim("cnf") as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val wiaCnfJwk = wiaCnf["jwk"] as Map<String, Any?>
        val wiaCnfThumbprint = Rfc7638JwkThumbprint.fromJwkJson(
            """{"kty":"EC","crv":"P-256","x":"${wiaCnfJwk["x"]}","y":"${wiaCnfJwk["y"]}"}""",
        )

        @Suppress("UNCHECKED_CAST")
        val attestedKeys = SignedJWT.parse(kaJwt).jwtClaimsSet.getClaim("attested_keys") as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val kaJwk = attestedKeys.first()["jwk"] as Map<String, Any?>
        val kaThumbprint = Rfc7638JwkThumbprint.fromJwkJson(
            """{"kty":"EC","crv":"P-256","x":"${kaJwk["x"]}","y":"${kaJwk["y"]}"}""",
        )

        // WIA attests the device key; KA attests the holder HSM key; the two keys differ.
        assertEquals(deviceThumbprint, wiaCnfThumbprint)
        assertEquals(hsmThumbprint, kaThumbprint)
        assertNotEquals(wiaCnfThumbprint, kaThumbprint)
    }
}
