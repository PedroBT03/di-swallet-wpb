package di.swallet.wpb.service

import di.swallet.wpb.domain.DeviceBindingType
import di.swallet.wpb.domain.DeviceWalletBinding
import di.swallet.wpb.domain.DeviceWalletBindingRepository
import di.swallet.wpb.domain.WalletUnit
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.domain.WalletUnitState
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime

data class WalletInitCommand(
    val holderId: String?,
    val devicePubJwk: String,
    val pidPubJwk: String? = null,
    val platform: String,
)

data class WalletBindingResult(
    val walletId: String,
    val state: WalletUnitState,
    val dpopBound: Boolean,
    val pidKeyBound: Boolean,
)

@Service
class DeviceBindingService(
    private val walletUnitRepository: WalletUnitRepository,
    private val deviceWalletBindingRepository: DeviceWalletBindingRepository,
) {
    fun initWallet(command: WalletInitCommand): WalletBindingResult {
        val walletUnit = WalletUnit(
            holderId = command.holderId,
            state = WalletUnitState.OPERATIONAL,
        ).let(walletUnitRepository::save)

        bind(walletUnit, DeviceBindingType.DPOP, command.devicePubJwk)
        if (!command.pidPubJwk.isNullOrBlank()) {
            bind(walletUnit, DeviceBindingType.PID_KEY, command.pidPubJwk)
        }
        return WalletBindingResult(
            walletId = walletUnit.walletId,
            state = walletUnit.state,
            dpopBound = true,
            pidKeyBound = !command.pidPubJwk.isNullOrBlank(),
        )
    }

    fun bindDpop(walletId: String, devicePubJwk: String): WalletBindingResult {
        val walletUnit = walletUnitRepository.findByWalletId(walletId)
            .orElseThrow { IllegalArgumentException("wallet '$walletId' not found") }
        bind(walletUnit, DeviceBindingType.DPOP, devicePubJwk)
        return currentBindingStatus(walletUnit)
    }

    fun bindPidKey(walletId: String, pidPubJwk: String): WalletBindingResult {
        val walletUnit = walletUnitRepository.findByWalletId(walletId)
            .orElseThrow { IllegalArgumentException("wallet '$walletId' not found") }
        bind(walletUnit, DeviceBindingType.PID_KEY, pidPubJwk)
        return currentBindingStatus(walletUnit)
    }

    private fun bind(walletUnit: WalletUnit, type: DeviceBindingType, jwk: String) {
        val thumbprint = thumbprint(jwk)
        val existing = deviceWalletBindingRepository.findByDeviceKeyThumbprint(thumbprint).orElse(null)
        if (existing != null) return
        deviceWalletBindingRepository.save(
            DeviceWalletBinding(
                walletUnit = walletUnit,
                bindingType = type,
                deviceKeyThumbprint = thumbprint,
                state = di.swallet.wpb.domain.DeviceWalletBindingState.ACTIVE,
                boundAt = LocalDateTime.now(),
            ),
        )
    }

    private fun currentBindingStatus(walletUnit: WalletUnit): WalletBindingResult {
        val all = deviceWalletBindingRepository.findByWalletUnitId(walletUnit.id!!)
        val dpop = all.any { it.bindingType == DeviceBindingType.DPOP }
        val pid = all.any { it.bindingType == DeviceBindingType.PID_KEY }
        return WalletBindingResult(
            walletId = walletUnit.walletId,
            state = walletUnit.state,
            dpopBound = dpop,
            pidKeyBound = pid,
        )
    }

    private fun thumbprint(jwk: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(jwk.trim().toByteArray(StandardCharsets.UTF_8))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
