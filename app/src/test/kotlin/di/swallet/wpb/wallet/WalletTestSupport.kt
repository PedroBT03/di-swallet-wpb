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
    fun ecPublicJwk(): String {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val fieldSize = (publicKey.params.curve.field.fieldSize + 7) / 8
        fun coordinate(value: java.math.BigInteger): String {
            val rawInput = value.toByteArray()
            val raw = if (rawInput.size > fieldSize) rawInput.copyOfRange(rawInput.size - fieldSize, rawInput.size) else rawInput
            val sized = if (raw.size == fieldSize) raw else ByteArray(fieldSize - raw.size) + raw
            return encoder.encodeToString(sized)
        }
        return """{"kty":"EC","crv":"P-256","x":"${coordinate(publicKey.w.affineX)}","y":"${coordinate(publicKey.w.affineY)}"}"""
    }

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
