package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface UserDeviceRepository : JpaRepository<UserDevice, Long> {
    fun findByUserId(userId: String): List<UserDevice>
    fun findByCredentialId(credentialId: String): Optional<UserDevice>
}