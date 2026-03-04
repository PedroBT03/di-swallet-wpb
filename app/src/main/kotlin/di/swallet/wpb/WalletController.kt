package di.swallet.wpb

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
}