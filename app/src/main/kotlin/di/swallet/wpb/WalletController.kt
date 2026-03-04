package di.swallet.wpb

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PathVariable

@RestController
@RequestMapping("/api/v1/wallet")
class WalletController(private val hsmService: HsmService) {

    @PostMapping("/keys")
    fun createKey(): Map<String, String> {
        val publicKey = hsmService.generateKey()
        return mapOf(
            "status" to "success",
            "publicKey" to publicKey,
            "info" to "Private key is securely stored in Remote WSCD"
        )
    }

    @PostMapping("/keys/{userId}")
    fun createKey(@PathVariable userId: String): WalletKey {
        return hsmService.generateKeyForUser(userId)
    }

    @GetMapping("/keys/{userId}")
    fun getKey(@PathVariable userId: String): WalletKey {
        return hsmService.getUserKey(userId)
    }
}