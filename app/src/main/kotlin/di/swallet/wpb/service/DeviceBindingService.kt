/**
 * Binds device public keys to wallet units during initialization and subsequent DPoP or PID-key setup.
 */

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

/** Input for creating a wallet unit and binding initial device keys in one step. */
data class WalletInitCommand(
    val holderId: String?,
    val devicePubJwk: String,
    val pidPubJwk: String? = null,
    val platform: String,
    val userDeviceId: Long? = null,
)

/** Summary of wallet ID, lifecycle state, and which device binding types are active. */
data class WalletBindingResult(
    val walletId: String,
    val state: WalletUnitState,
    val dpopBound: Boolean,
    val pidKeyBound: Boolean,
)

/**
 * Creates wallet units and records DPoP and PID-key thumbprint bindings to authorized devices.
 */
@Service
class DeviceBindingService(
    private val walletUnitRepository: WalletUnitRepository,
    private val deviceWalletBindingRepository: DeviceWalletBindingRepository,
    private val userDeviceRepository: UserDeviceRepository,
    private val walletUnitLifecycleService: WalletUnitLifecycleService,
    private val walletBindingProperties: WalletBindingProperties,
) {
    /**
     * Initializes a wallet unit, binds device keys, and activates the unit when bindings succeed.
     */
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

    /**
     * Adds or refreshes the DPoP device key binding for an existing wallet unit.
     */
    fun bindDpop(walletId: String, devicePubJwk: String, userDeviceId: Long? = null): WalletBindingResult {
        val walletUnit = walletUnitRepository.findByWalletId(walletId)
            .orElseThrow { IllegalArgumentException("wallet '$walletId' not found") }
        val userDevice = resolveOptionalUserDevice(userDeviceId, walletUnit.holderId)
        bind(walletUnit, DeviceBindingType.DPOP, devicePubJwk, userDevice)
        val activated = maybeActivate(walletUnit)
        return currentBindingStatus(activated)
    }

    /**
     * Adds a PID holder public key binding to an operational wallet unit.
     */
    fun bindPidKey(walletId: String, pidPubJwk: String): WalletBindingResult {
        val walletUnit = walletUnitRepository.findByWalletId(walletId)
            .orElseThrow { IllegalArgumentException("wallet '$walletId' not found") }
        walletUnitLifecycleService.requireOperational(walletUnit)
        bind(walletUnit, DeviceBindingType.PID_KEY, pidPubJwk, userDevice = null)
        return currentBindingStatus(walletUnit)
    }

    /**
     * Activates a candidate wallet unit after its first binding when still in CANDIDATE state.
     */
    private fun maybeActivate(walletUnit: WalletUnit): WalletUnit =
        if (walletUnit.state == WalletUnitState.CANDIDATE) {
            walletUnitLifecycleService.activate(walletUnit)
        } else {
            walletUnit
        }

    /**
     * Stores a device key thumbprint binding when the same thumbprint is not already registered.
     */
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

    /**
     * Reports current DPoP and PID-key binding presence for a wallet unit.
     */
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

    /**
     * Resolves the FIDO2 user device when wallet init requires an authorized device record.
     */
    private fun resolveUserDevice(command: WalletInitCommand): UserDevice? {
        if (!walletBindingProperties.requireUserDeviceOnInit) return null
        val userDeviceId = command.userDeviceId
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "userDeviceId is required for wallet init")
        return loadUserDevice(userDeviceId, command.holderId)
    }

    /**
     * Loads an optional FIDO2 user device when a device ID is supplied with a binding request.
     */
    private fun resolveOptionalUserDevice(userDeviceId: Long?, holderId: String?): UserDevice? =
        userDeviceId?.let { loadUserDevice(it, holderId) }

    /**
     * Loads a user device and verifies it belongs to the expected holder when holderId is provided.
     */
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
