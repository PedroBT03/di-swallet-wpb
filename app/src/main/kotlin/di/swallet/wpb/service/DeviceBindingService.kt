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
import di.swallet.wpb.ka.attestation.KeyAttestationProvider
import di.swallet.wpb.security.WscaSciBootstrap
import di.swallet.wpb.wia.attestation.WalletAttestationProvider
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDateTime
import java.util.Base64

/** Input for creating a wallet unit and binding the bootstrap device key in one step. */
data class WalletInitCommand(
    val holderId: String?,
    val devicePubJwk: String,
    val platform: String,
    val userDeviceId: Long? = null,
)

/**
 * Outcome of wallet provisioning: wallet ID, lifecycle state, DPoP binding presence, and the
 * Wallet Unit Attestation issued at init (its two parts: the WIA and the KA).
 */
data class WalletBindingResult(
    val walletId: String,
    val state: WalletUnitState,
    val dpopBound: Boolean,
    val wiaJwt: String? = null,
    val kaJwt: String? = null,
)

/**
 * Creates wallet units and records DPoP device-key thumbprint bindings to authorized devices.
 */
@Service
class DeviceBindingService(
    private val walletUnitRepository: WalletUnitRepository,
    private val deviceWalletBindingRepository: DeviceWalletBindingRepository,
    private val userDeviceRepository: UserDeviceRepository,
    private val walletUnitLifecycleService: WalletUnitLifecycleService,
    private val walletBindingProperties: WalletBindingProperties,
    private val hsmService: HsmService,
    private val walletAttestationProvider: WalletAttestationProvider,
    private val keyAttestationProvider: KeyAttestationProvider,
    private val keyBindingRuntimeService: KeyBindingRuntimeService,
) {
    private val logger = LoggerFactory.getLogger(DeviceBindingService::class.java)

    /**
     * Provisions a wallet unit: binds the bootstrap device key, generates the holder's remote HSM
     * key, and issues the Wallet Unit Attestation (WIA + KA). The unit stays CANDIDATE (anonymous)
     * until identity is established at the CMD step, which promotes it to VALID.
     */
    fun initWallet(command: WalletInitCommand): WalletBindingResult {
        val userDevice = resolveUserDevice(command)
        val walletUnit = walletUnitLifecycleService.createCandidate(command.holderId)
        bind(walletUnit, DeviceBindingType.DPOP, command.devicePubJwk, userDevice)
        val attestations = issueProvisioningAttestation(command.holderId, walletUnit)
        return WalletBindingResult(
            walletId = walletUnit.walletId,
            state = walletUnit.state,
            dpopBound = true,
            wiaJwt = attestations.wiaJwt,
            kaJwt = attestations.kaJwt,
        )
    }

    private data class ProvisioningAttestations(val wiaJwt: String?, val kaJwt: String?)

    /**
     * Generates the holder HSM key and issues the WUA (WIA + KA) during provisioning. SCI checks
     * are bypassed because init is the bootstrap moment (no holder session yet, anonymous wallet).
     * Returns nulls for anonymous-only inits that carry no holder id.
     */
    private fun issueProvisioningAttestation(holderId: String?, walletUnit: WalletUnit): ProvisioningAttestations {
        if (holderId.isNullOrBlank()) return ProvisioningAttestations(null, null)
        return WscaSciBootstrap.allow {
            val walletKey = hsmService.ensureKeyForUser(holderId, walletUnit)
            val wiaJwt = runCatching {
                walletAttestationProvider.issue(
                    holderId = holderId,
                    walletInstanceId = holderId,
                    issuerId = null,
                ).jwt
            }.onFailure { logger.warn("WIA issuance at init failed for holder '$holderId': ${it.message}") }
                .getOrNull()
            val kaJwt = runCatching {
                val publicKey = decodeEcPublicKey(walletKey.publicKeyBase64)
                val attestation = keyAttestationProvider.issueForProvisioning(
                    holderId = holderId,
                    keyAlias = walletKey.keyAlias,
                    proofPublicKey = publicKey,
                )
                keyBindingRuntimeService.registerKeyAttestation(holderId, attestation)
                attestation.jwt
            }.onFailure { logger.warn("KA issuance at init failed for holder '$holderId': ${it.message}") }
                .getOrNull()
            ProvisioningAttestations(wiaJwt, kaJwt)
        }
    }

    /** Decodes a Base64URL X.509 EC public key (holder HSM key) into an ECPublicKey. */
    private fun decodeEcPublicKey(publicKeyBase64: String): ECPublicKey {
        val bytes = Base64.getUrlDecoder().decode(publicKeyBase64)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as ECPublicKey
    }

    /**
     * Adds or refreshes the DPoP device key binding for an existing wallet unit.
     */
    fun bindDpop(walletId: String, devicePubJwk: String, userDeviceId: Long? = null): WalletBindingResult {
        val walletUnit = walletUnitRepository.findByWalletId(walletId)
            .orElseThrow { IllegalArgumentException("wallet '$walletId' not found") }
        val userDevice = resolveOptionalUserDevice(userDeviceId, walletUnit.holderId)
        bind(walletUnit, DeviceBindingType.DPOP, devicePubJwk, userDevice)
        return currentBindingStatus(walletUnit)
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
                devicePublicJwk = jwk,
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
        return WalletBindingResult(
            walletId = walletUnit.walletId,
            state = walletUnit.state,
            dpopBound = dpop,
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
