package openspri.testapps

// Deliberately outside de.openspri: the component scan of OpenSpriApplication must not pick these up.

import de.openspri.soap.InMemoryInboundStore
import de.openspri.soap.InMemoryOutboundArchive
import de.openspri.soap.InMemoryPartnerDirectory
import de.openspri.soap.config.SpriSoapConfiguration
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import

private const val DATASOURCE = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
private const val FLYWAY = "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"

/** REST layer only, without database: used to compare the served OpenAPI specification. */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = [DATASOURCE, FLYWAY])
@ComponentScan("de.openspri.rest")
class RestOnlyTestApplication

/** SOAP interface with in-memory ports instead of the database (runs without Docker). */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = [DATASOURCE, FLYWAY])
@Import(SpriSoapConfiguration::class)
class SoapInMemoryTestApplication {
    @Bean fun partnerDirectory() = InMemoryPartnerDirectory()
    @Bean fun inboundStore() = InMemoryInboundStore()
    @Bean fun outboundArchive() = InMemoryOutboundArchive()
    @Bean fun orderRepository() = de.openspri.soap.InMemoryOrderRepository()
    @Bean fun messageCodeStore() = de.openspri.soap.InMemoryMessageCodeStore()
}
