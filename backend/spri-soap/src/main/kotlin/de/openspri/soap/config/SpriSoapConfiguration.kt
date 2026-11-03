package de.openspri.soap.config

import de.openspri.domain.exchange.InboundStore
import de.openspri.domain.exchange.OutboundArchive
import de.openspri.domain.partner.PartnerDirectory
import de.openspri.soap.InboundProcessor
import de.openspri.soap.RawRequestInterceptor
import de.openspri.soap.SecurityHeaderUnderstoodInterceptor
import de.openspri.domain.settings.SigningKeyRepository
import de.openspri.soap.KeyEncryption
import de.openspri.soap.SigningCredentials
import de.openspri.soap.SigningKeyHolder
import de.openspri.soap.SigningKeyService
import de.openspri.soap.SpriClient
import de.openspri.soap.SpriEndpoint
import de.openspri.soap.SpriMetrics
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
/** The master key encryption, or null if OPENSPRI_MASTER_KEY is not set. */
class MasterKey(val encryption: KeyEncryption?)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpriSoapProperties::class)
@org.springframework.context.annotation.Import(OrderProcessingConfiguration::class, DeliveryConfiguration::class, MetricsConfiguration::class, RetentionConfiguration::class)
class SpriSoapConfiguration {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun spriSchema(): SpriSchema = SpriSchema.load()

    /**
     * Master key for the stored signing key (OPENSPRI_MASTER_KEY), optional. Wrapped in [MasterKey]: an
     * Optional parameter would be resolved by Spring as "optional KeyEncryption bean" instead.
     */
    @Bean
    fun masterKey(@org.springframework.beans.factory.annotation.Value("\${openspri.master-key:}") masterKey: String) =
        MasterKey(masterKey.takeIf { it.isNotBlank() }?.let(::KeyEncryption))

    /**
     * Our signing key: keystore file (OPENSPRI_SOAP_SIGNING_KEYSTORE) if configured, otherwise the key stored by
     * the setup wizard, otherwise a temporary key (warning in the log).
     */
    @Bean
    fun signingKeyHolder(properties: SpriSoapProperties, keys: SigningKeyRepository, master: MasterKey): SigningKeyHolder {
        val file = properties.signingKeystore?.takeIf { it.isNotBlank() }?.let { keystore ->
            Files.newInputStream(Path.of(keystore)).use {
                SigningCredentials.fromPkcs12(it, properties.signingKeystorePassword.orEmpty().toCharArray(), properties.signingKeyAlias)
            }.requireAkCompliant()
        }
        return SigningKeyService.load(file, keys, master.encryption)
    }

    @Bean
    fun signingKeyService(holder: SigningKeyHolder, keys: SigningKeyRepository, master: MasterKey) =
        SigningKeyService(holder, keys, master.encryption)

    @Bean
    fun wsSecurityVerifier(properties: SpriSoapProperties) = WsSecurityVerifier(properties.minimumPartnerKeyBits)

    @Bean
    fun inboundProcessor(
        partners: PartnerDirectory,
        store: InboundStore,
        schema: SpriSchema,
        verifier: WsSecurityVerifier,
        keys: SigningKeyHolder,
        properties: SpriSoapProperties,
        metrics: SpriMetrics,
    ) = InboundProcessor(
        partners, store, schema, verifier, TeqResponder(keys::current),
        attributes = AttributeValidator(), attributeMode = properties.attributeRules.inbound, metrics = metrics,
    )

    @Bean
    fun spriClient(
        keys: SigningKeyHolder,
        schema: SpriSchema,
        verifier: WsSecurityVerifier,
        archive: OutboundArchive,
        properties: SpriSoapProperties,
    ) = SpriClient(
        keys::current, schema, verifier, archive,
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

    /** After the address filter: every POST gets a TEQ, also when CXF cannot read it. */
    @Bean
    fun spriRequestFilter(processor: InboundProcessor, properties: SpriSoapProperties): FilterRegistrationBean<SpriRequestFilter> =
        FilterRegistrationBean(SpriRequestFilter(processor, properties.maxRequestBytes)).apply {
            addUrlPatterns("/spri/*")
            order = 1
        }

    @Bean
    fun spriSourceAddressFilter(partners: PartnerDirectory, metrics: SpriMetrics): FilterRegistrationBean<SourceAddressFilter> =
        FilterRegistrationBean(SourceAddressFilter(partners, metrics)).apply {
            addUrlPatterns("/spri/*")
            order = 0
        }
}
