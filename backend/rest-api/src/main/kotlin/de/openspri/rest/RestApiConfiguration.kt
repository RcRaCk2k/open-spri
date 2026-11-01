package de.openspri.rest

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.servers.Server
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CertificationProperties::class)
class RestApiConfiguration {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("open-spri REST-API")
                .version("v1")
                .description(
                    "Öffentliche REST-API von open-spri. Das Web-Frontend nutzt ausschließlich diese API. " +
                        "Ausführliche Beschreibung mit Beispielen: docs/api/ im Repository.",
                )
                .license(License().name("MIT").url("https://github.com/RcRaCk2k/open-spri/blob/main/LICENSE")),
        )
        // Relative server URL keeps the exported specification independent of the host.
        .servers(listOf(Server().url("/").description("diese Instanz")))
}
