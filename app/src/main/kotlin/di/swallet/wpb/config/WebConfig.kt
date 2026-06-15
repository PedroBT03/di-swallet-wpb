/**
 * Spring MVC configuration for wallet security interceptors.
 */

package di.swallet.wpb.config

import di.swallet.wpb.security.AuthorizationInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Registers the FIDO2 authorization interceptor on protected wallet and OID4 routes.
 */
@Configuration
class WebConfig(private val authInterceptor: AuthorizationInterceptor) : WebMvcConfigurer {

    /**
     * Applies sole-control authentication to wallet API and OID4 consent/session paths.
     */
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(authInterceptor)
            .addPathPatterns(
                "/api/v1/wallet/**",
                "/openid4vp/consent",
                "/openid4vci/consent",
                "/openid4vp/session/**",
                "/openid4vci/session/**",
            )
    }
}
