package di.swallet.wpb

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration class for OpenAPI (Swagger) documentation.
 * Defines global security schemes and metadata for the DI-Swallet API.
 */
@Configuration
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
            // FIX: Use addSecurityItem instead of addSecurityRequirement
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