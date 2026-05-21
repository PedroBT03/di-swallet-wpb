package di.swallet.wpb.config

import eu.europa.ec.eudi.openid4vp.ErrorDispatchPolicy
import eu.europa.ec.eudi.openid4vp.OpenId4VPConfig
import eu.europa.ec.eudi.openid4vp.OpenId4Vp
import eu.europa.ec.eudi.openid4vp.ResponseEncryptionConfiguration
import eu.europa.ec.eudi.openid4vp.SupportedClientIdPrefix
import eu.europa.ec.eudi.openid4vp.VPConfiguration
import eu.europa.ec.eudi.openid4vp.VpFormatsSupported
import eu.europa.ec.eudi.openid4vp.CoseAlgorithm
import eu.europa.ec.eudi.openid4vp.PreregisteredClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.security.cert.X509Certificate
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.EncryptionMethod

@Configuration
class OpenId4VpIntegrationConfig {

    @Value("\${wpb.openid4vp.demo-mode:false}")
    private var demoMode: Boolean = false

    @Bean(destroyMethod = "close")
    fun openId4VpHttpClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        expectSuccess = true
    }

    @Bean
    fun openId4VpConfig(): OpenId4VPConfig = OpenId4VPConfig(
        supportedClientIdPrefixes = buildList {
            if (demoMode) {
                add(
                    SupportedClientIdPrefix.Preregistered(
                        PreregisteredClient("verifier-demo-client", "Demo Verifier"),
                    ),
                )
            }
            add(SupportedClientIdPrefix.RedirectUri)
            add(SupportedClientIdPrefix.DecentralizedIdentifier { _ -> null })
            add(SupportedClientIdPrefix.X509SanDns { _: List<X509Certificate> -> true })
            add(SupportedClientIdPrefix.X509Hash { _: List<X509Certificate> -> true })
        },
        responseEncryptionConfiguration = ResponseEncryptionConfiguration.Supported(
            supportedAlgorithms = listOf(JWEAlgorithm.ECDH_ES),
            supportedMethods = listOf(EncryptionMethod.A256GCM),
        ),
        vpConfiguration = VPConfiguration(
            vpFormatsSupported = VpFormatsSupported(
                VpFormatsSupported.SdJwtVc.HAIP,
                VpFormatsSupported.MsoMdoc(
                    issuerAuthAlgorithms = listOf(CoseAlgorithm(-7)),
                    deviceAuthAlgorithms = listOf(CoseAlgorithm(-7)),
                ),
            ),
        ),
        errorDispatchPolicy = ErrorDispatchPolicy.AllClients,
    )

    @Bean
    fun openId4Vp(openId4VpConfig: OpenId4VPConfig, openId4VpHttpClient: HttpClient): OpenId4Vp =
        OpenId4Vp(openId4VpConfig, openId4VpHttpClient)
}
