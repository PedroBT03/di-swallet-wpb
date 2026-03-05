package di.swallet.wpb.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import di.swallet.wpb.security.AuthorizationInterceptor

@Configuration
class WebConfig(private val authInterceptor: AuthorizationInterceptor) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        // Apply security only to the wallet API endpoints
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/api/v1/wallet/**")
    }
}