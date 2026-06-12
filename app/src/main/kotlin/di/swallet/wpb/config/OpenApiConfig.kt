package di.swallet.wpb.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration class for OpenAPI (Swagger) documentation.
 * Defines global security schemes and metadata for the DI-Swallet API.
 */
@Configuration
@ConditionalOnProperty(prefix = "wpb.swagger", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OpenApiConfig {

    @Bean
    fun customOpenAPI(): OpenAPI {
        val securitySchemeName = "WalletAuth"
        
        return OpenAPI()
            .info(Info()
                .title("DI-Swallet WPB API")
                .version("1.0")
                .description("Wallet Provider Backend (WPB) with Remote HSM (WSCD) integration. " +
                             "Aligned with eIDAS 2.0 and ARF standards."))
            .addSecurityItem(SecurityRequirement().addList(securitySchemeName))
            .components(Components()
                // Define the API Key security scheme for the mandatory authorization header
                .addSecuritySchemes(securitySchemeName, SecurityScheme()
                    .name("X-Wallet-Authorization")
                    .type(SecurityScheme.Type.APIKEY)
                    .`in`(SecurityScheme.In.HEADER)
                    .description("Simulated FIDO2/WebAuthn assertion for Sole Control validation.")
                )
            )
    }
}