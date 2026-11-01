package de.openspri.soap.config

import de.openspri.domain.event.EventLog
import de.openspri.domain.exchange.InboxQueue
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageCodeStore
import de.openspri.domain.order.InboundOrderService
import de.openspri.domain.order.InventoryBook
import de.openspri.domain.order.InventoryStore
import de.openspri.domain.partner.AgreementRules
import de.openspri.domain.partner.PartnerAgreements
import de.openspri.domain.partner.ProductCatalog
import de.openspri.domain.order.OrderRepository
import de.openspri.soap.InboxWorker
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** The active message code table, replaced atomically on import (startup or REST, M4). */
class MessageCodeCatalogHolder(initial: MessageCodeCatalog) {
    private val current = AtomicReference(initial)
    fun get(): MessageCodeCatalog = current.get()
    fun set(catalog: MessageCodeCatalog) = current.set(catalog)
}

/**
 * Business processing of inbound orders and messages (M2): message code table, [InboundOrderService] and
 * the scheduled [InboxWorker].
 *
 * Message code table: if `openspri.message-codes.file` points to an AK export (or neutral CSV), it is
 * imported at startup when its version is new. Otherwise the active version from the database is used.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class OrderProcessingConfiguration {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun messageCodeCatalogHolder(store: MessageCodeStore, @Value("\${openspri.message-codes.file:}") file: String): MessageCodeCatalogHolder {
        val path = file.takeIf { it.isNotBlank() }?.let(Path::of)
        if (path != null && !Files.isRegularFile(path)) {
            log.error(
                "Message code table {} (openspri.message-codes.file) not found - in Docker the path inside the container is " +
                    "/etc/openspri/<file> (host: ./config/). Using the last imported version.", path,
            )
        }
        val catalog = when {
            path != null && Files.isRegularFile(path) -> store.importAndActivate(Files.readAllBytes(path), path.fileName.toString())
                .also { log.info("Message code table {} imported: {} codes, sha256 {}", path, it.codes.size, it.sha256) }
            else -> store.active()
        } ?: MessageCodeCatalog("none", "", emptyList()).also {
            log.warn(
                "No message code table available (openspri.message-codes.file). Message codes cannot be checked (R-13). " +
                    "Export the table from the AK Confluence, see docs/betrieb.md.",
            )
        }
        return MessageCodeCatalogHolder(catalog)
    }

    @Bean
    fun inboundOrderService(orders: OrderRepository, codes: MessageCodeCatalogHolder, events: EventLog, agreements: AgreementRules, inventory: InventoryBook) =
        InboundOrderService(orders, codes::get, events = events, agreements = agreements, inventory = inventory)

    @Bean
    fun inventoryBook(store: InventoryStore) = InventoryBook(store)

    @Bean
    fun agreementRules(agreements: PartnerAgreements, products: ProductCatalog) = AgreementRules(agreements, products)

    @Bean
    fun inboxWorker(queue: InboxQueue, service: InboundOrderService) = InboxWorker(queue, service)

    @Bean
    fun inboxScheduler(worker: InboxWorker) = InboxScheduler(worker)

    class InboxScheduler(private val worker: InboxWorker) {
        private val log = LoggerFactory.getLogger(javaClass)

        @Scheduled(fixedDelayString = "\${openspri.inbox.poll-interval:1s}", initialDelayString = "\${openspri.inbox.initial-delay:5s}")
        fun drain() {
            try {
                worker.drain()
            } catch (e: Exception) {
                log.error("Inbox processing failed", e)
            }
        }

        @EventListener(ApplicationReadyEvent::class)
        fun ready() = log.info("Inbox processing active")
    }
}
