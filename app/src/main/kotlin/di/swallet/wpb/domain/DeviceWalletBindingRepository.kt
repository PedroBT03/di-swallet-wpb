package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface DeviceWalletBindingRepository : JpaRepository<DeviceWalletBinding, Long> {
    fun findByWalletUnitId(walletUnitId: Long): List<DeviceWalletBinding>
    fun findByDeviceKeyThumbprint(deviceKeyThumbprint: String): Optional<DeviceWalletBinding>
    fun findByWalletUnitIdAndBindingType(walletUnitId: Long, bindingType: DeviceBindingType): List<DeviceWalletBinding>
}
