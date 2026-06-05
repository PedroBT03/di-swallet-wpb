package di.swallet.wpb.service

import di.swallet.wpb.config.WalletBindingProperties
import di.swallet.wpb.domain.DeviceBindingType
import di.swallet.wpb.domain.DeviceWalletBinding
import di.swallet.wpb.domain.DeviceWalletBindingRepository
import di.swallet.wpb.domain.UserDevice
import di.swallet.wpb.domain.UserDeviceRepository
import di.swallet.wpb.domain.WalletUnit
import di.swallet.wpb.domain.WalletUnitRepository
import di.swallet.wpb.domain.WalletUnitState
import di.swallet.wpb.issuance.crypto.Rfc7638JwkThumbprint
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

data class WalletInitCommand(
    val holderId: String?,
    val devicePubJwk: String,
    val pidPubJwk: String? = null,
    val platform: String,
    val userDeviceId: Long? = null,
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
    private val userDeviceRepository: UserDeviceRepository,
    private val walletUnitLifecycleService: WalletUnitLifecycleService,
    private val walletBindingProperties: WalletBindingProperties,
) {
    fun initWallet(command: WalletInitCommand): WalletBindingResult {
        val userDevice = resolveUserDevice(command)
        val walletUnit = walletUnitLifecycleService.createCandidate(command.holderId)
        bind(walletUnit, DeviceBindingType.DPOP, command.devicePubJwk, userDevice)
        if (!command.pidPubJwk.isNullOrBlank()) {
            bind(walletUnit, DeviceBindingType.PID_KEY, command.pidPubJwk, userDevice)
        }
        val activated = walletUnitLifecycleService.activate(walletUnit)
        return WalletBindingResult(
            walletId = activated.walletId,
            state = activated.state,
            dpopBound = true,
            pidKeyBound = !command.pidPubJwk.isNullOrBlank(),
        )
    }

    fun bindDpop(walletId: String, devicePubJwk: String, userDeviceId: Long? = null): WalletBindingResult {
        val walletUnit = walletUnitRepository.findByWalletId(walletId)
            .orElseThrow { IllegalArgumentException("wallet '$walletId' not found") }
        val userDevice = resolveOptionalUserDevice(userDeviceId, walletUnit.holderId)
        bind(walletUnit, DeviceBindingType.DPOP, devicePubJwk, userDevice)
        val activated = maybeActivate(walletUnit)
        return currentBindingStatus(activated)
    }

    fun bindPidKey(walletId: String, pidPubJwk: String): WalletBindingResult {
        val walletUnit = walletUnitRepository.findByWalletId(walletId)
            .orElseThrow { IllegalArgumentException("wallet '$walletId' not found") }
        walletUnitLifecycleService.requireOperational(walletUnit)
        bind(walletUnit, DeviceBindingType.PID_KEY, pidPubJwk, userDevice = null)
        return currentBindingStatus(walletUnit)
    }

    private fun maybeActivate(walletUnit: WalletUnit): WalletUnit =
        if (walletUnit.state == WalletUnitState.CANDIDATE) {
            walletUnitLifecycleService.activate(walletUnit)
        } else {
            walletUnit
        }

    private fun bind(
        walletUnit: WalletUnit,
        type: DeviceBindingType,
        jwk: String,
        userDevice: UserDevice?,
    ) {
        val thumbprint = Rfc7638JwkThumbprint.fromJwkJson(jwk)
        val existing = deviceWalletBindingRepository.findByDeviceKeyThumbprint(thumbprint).orElse(null)
        if (existing != null) return
        deviceWalletBindingRepository.save(
            DeviceWalletBinding(
                walletUnit = walletUnit,
                bindingType = type,
                deviceKeyThumbprint = thumbprint,
                state = di.swallet.wpb.domain.DeviceWalletBindingState.ACTIVE,
                boundAt = LocalDateTime.now(),
                userDevice = userDevice,
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

    private fun resolveUserDevice(command: WalletInitCommand): UserDevice? {
        if (!walletBindingProperties.requireUserDeviceOnInit) return null
        val userDeviceId = command.userDeviceId
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "userDeviceId is required for wallet init")
        return loadUserDevice(userDeviceId, command.holderId)
    }

    private fun resolveOptionalUserDevice(userDeviceId: Long?, holderId: String?): UserDevice? =
        userDeviceId?.let { loadUserDevice(it, holderId) }

    private fun loadUserDevice(userDeviceId: Long, holderId: String?): UserDevice {
        val userDevice = userDeviceRepository.findById(userDeviceId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "UserDevice $userDeviceId not found") }
        if (!holderId.isNullOrBlank() && holderId != userDevice.userId) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "holderId '$holderId' does not match FIDO2 user '${userDevice.userId}'",
            )
        }
        return userDevice
    }
}
