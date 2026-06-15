/**
 * Spring Data repository for device-to-wallet binding queries.
 */

package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Loads device wallet bindings by wallet unit, thumbprint, or binding type.
 */
interface DeviceWalletBindingRepository : JpaRepository<DeviceWalletBinding, Long> {
    /** Returns all bindings for a wallet unit. */
    fun findByWalletUnitId(walletUnitId: Long): List<DeviceWalletBinding>

    /** Finds a binding by RFC 7638 JWK thumbprint. */
    fun findByDeviceKeyThumbprint(deviceKeyThumbprint: String): Optional<DeviceWalletBinding>

    /** Returns bindings of a specific type for one wallet unit. */
    fun findByWalletUnitIdAndBindingType(walletUnitId: Long, bindingType: DeviceBindingType): List<DeviceWalletBinding>
}
