package di.swallet.wpb

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface WalletCredentialRepository : JpaRepository<WalletCredential, Long> {
    fun findByUserId(userId: String): List<WalletCredential>
}