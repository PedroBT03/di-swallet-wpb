package di.swallet.wpb.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface WalletKeyRepository : JpaRepository<WalletKey, Long> {
    fun findByUserId(userId: String): Optional<WalletKey>
    fun findByKeyAlias(keyAlias: String): Optional<WalletKey>
}