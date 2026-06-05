package di.swallet.wpb.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Wallet unit lifecycle and device-binding policy.
 */
@ConfigurationProperties(prefix = "wpb.wallet")
class WalletBindingProperties {
    /**
     * When true, [POST /api/v1/wallet/init] requires a registered FIDO2 [UserDevice] id.
     * Disabled in demo/test profiles for emulator flows.
     */
    var requireUserDeviceOnInit: Boolean = true
}
