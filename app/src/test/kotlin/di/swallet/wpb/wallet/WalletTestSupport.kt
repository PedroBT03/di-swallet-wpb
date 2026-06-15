/**
 * Shared helpers for wallet initialization, EC JWK generation, and issuance bootstrap.
 */

package di.swallet.wpb.wallet

import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.service.DeviceBindingService
import di.swallet.wpb.service.HsmService
import di.swallet.wpb.service.WalletInitCommand
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

object WalletTestSupport {
    /**
     * Generates a fresh P-256 EC key pair and returns its public coordinates as a compact
     * JSON JWK string for wallet init requests in tests.
     */
    fun ecPublicJwk(): String {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val fieldSize = (publicKey.params.curve.field.fieldSize + 7) / 8
        /** Pads or truncates a curve coordinate to fieldSize bytes and base64url-encodes it. */
        fun coordinate(value: java.math.BigInteger): String {
            val rawInput = value.toByteArray()
            val raw = if (rawInput.size > fieldSize) rawInput.copyOfRange(rawInput.size - fieldSize, rawInput.size) else rawInput
            val sized = if (raw.size == fieldSize) raw else ByteArray(fieldSize - raw.size) + raw
            return encoder.encodeToString(sized)
        }
        return """{"kty":"EC","crv":"P-256","x":"${coordinate(publicKey.w.affineX)}","y":"${coordinate(publicKey.w.affineY)}"}"""
    }

    /**
     * Initialises an operational wallet for the holder via DeviceBindingService and returns
     * the persisted wallet unit id.
     */
    fun initOperationalWallet(deviceBindingService: DeviceBindingService, holderId: String): String {
        val result = deviceBindingService.initWallet(
            WalletInitCommand(
                holderId = holderId,
                platform = "test",
                devicePubJwk = ecPublicJwk(),
            ),
        )
        return result.walletId
    }

    /**
     * Creates an operational wallet for the holder and ensures an HSM user key exists,
     * generating one bound to the wallet unit when absent.
     */
    fun bootstrapHolderForIssuance(
        deviceBindingService: DeviceBindingService,
        walletUnitRepository: WalletUnitRepository,
        hsmService: HsmService,
        holderId: String,
    ) {
        initOperationalWallet(deviceBindingService, holderId)
        val walletUnit = walletUnitRepository.findFirstByHolderId(holderId).orElseThrow()
        runCatching { hsmService.getUserKey(holderId) }
            .getOrElse { hsmService.generateKeyForUser(holderId, walletUnit) }
    }
}
