package de.openspri.soap.config

import de.openspri.domain.exchange.InboundStore
import de.openspri.domain.exchange.OutboundArchive
import de.openspri.domain.partner.PartnerDirectory
import de.openspri.soap.InboundProcessor
import de.openspri.soap.RawRequestInterceptor
import de.openspri.soap.SecurityHeaderUnderstoodInterceptor
import de.openspri.soap.SigningCredentials
import de.openspri.soap.SpriClient
import de.openspri.soap.SpriEndpoint
import de.openspri.soap.SpriSchema
import de.openspri.soap.TeqResponder
import de.openspri.soap.WsSecurityVerifier
import de.openspri.rules.AttributeValidator
import jakarta.xml.ws.Endpoint
import org.apache.cxf.Bus
import org.apache.cxf.jaxws.EndpointImpl
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path

/**
 * Wires the S/PRI SOAP interface. The endpoint is published under `<cxf.path>/v4`, i.e. `/spri/v4`.
 * Ports (partners, archive) are implemented by module persistence.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpriSoapProperties::class)
class SpriSoapConfiguration {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun spriSchema(): SpriSchema = SpriSchema.load()

    @Bean
    fun signingCredentials(properties: SpriSoapProperties): SigningCredentials {
        val keystore = properties.signingKeystore?.takeIf { it.isNotBlank() }
        if (keystore == null) {
            log.warn(
                "No WS-Security signing key configured (openspri.soap.signing-keystore). A temporary key is used; " +
                    "partners cannot verify our signatures after a restart. Do not use this in production.",
            )
            return SigningCredentials.generateSelfSigned("open-spri temporary key")
        }
        return Files.newInputStream(Path.of(keystore)).use {
            SigningCredentials.fromPkcs12(it, properties.signingKeystorePassword.orEmpty().toCharArray(), properties.signingKeyAlias)
        }.requireAkCompliant()
    }

    @Bean
    fun wsSecurityVerifier(properties: SpriSoapProperties) = WsSecurityVerifier(properties.minimumPartnerKeyBits)

    @Bean
    fun inboundProcessor(
        partners: PartnerDirectory,
        store: InboundStore,
        schema: SpriSchema,
        verifier: WsSecurityVerifier,
        credentials: SigningCredentials,
        properties: SpriSoapProperties,
    ) = InboundProcessor(
        partners, store, schema, verifier, TeqResponder(credentials),
        attributes = AttributeValidator(), attributeMode = properties.attributeRules.inbound,
    )

    @Bean
    fun spriClient(
        credentials: SigningCredentials,
        schema: SpriSchema,
        verifier: WsSecurityVerifier,
        archive: OutboundArchive,
        properties: SpriSoapProperties,
    ) = SpriClient(
        credentials, schema, verifier, archive,
        SpriClient.Settings(
            connectTimeout = properties.client.connectTimeout,
            receiveTimeout = properties.client.receiveTimeout,
            requireSignedResponse = properties.client.requireSignedResponse,
            outboundProxy = properties.client.outboundProxyUri(),
            attributeMode = properties.attributeRules.outbound,
        ),
    )

    @Bean
    fun spriEndpoint(bus: Bus, processor: InboundProcessor, properties: SpriSoapProperties): Endpoint =
        EndpointImpl(bus, SpriEndpoint(processor)).apply {
            inInterceptors.add(RawRequestInterceptor(properties.maxRequestBytes))
            inInterceptors.add(SecurityHeaderUnderstoodInterceptor())
            properties.publishedEndpointUrl?.takeIf { it.isNotBlank() }?.let { publishedEndpointUrl = it }
            publish("/v4")
        }

    @Bean
    fun spriSourceAddressFilter(partners: PartnerDirectory): FilterRegistrationBean<SourceAddressFilter> =
        FilterRegistrationBean(SourceAddressFilter(partners)).apply {
            addUrlPatterns("/spri/*")
            order = 0
        }
}
