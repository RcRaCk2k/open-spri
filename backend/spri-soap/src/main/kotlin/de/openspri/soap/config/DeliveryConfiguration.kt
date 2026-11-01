package de.openspri.soap.config

import de.openspri.domain.delivery.DeliveryOperations
import de.openspri.domain.delivery.DeliveryService
import de.openspri.domain.event.EventLog
import de.openspri.domain.delivery.Notifier
import de.openspri.domain.delivery.OutageRegistry
import de.openspri.domain.delivery.Outbox
import de.openspri.domain.delivery.OutboxEntry
import de.openspri.domain.delivery.PartnerOutage
import de.openspri.domain.delivery.RetryPolicy
import de.openspri.domain.order.OrderDrafts
import de.openspri.domain.order.OrderRepository
import de.openspri.domain.order.OutboundOrderService
import de.openspri.domain.order.Transactions
import de.openspri.domain.partner.AgreementRules
import de.openspri.domain.partner.MaintenanceAnnouncement
import de.openspri.domain.partner.MaintenanceAnnouncements
import de.openspri.domain.partner.MaintenanceService
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerAdministration
import de.openspri.domain.partner.PartnerDirectory
import de.openspri.domain.partner.PartnerId
import de.openspri.domain.query.ArchiveQueries
import de.openspri.domain.query.DeliveryQueries
import de.openspri.domain.query.OrderQueries
import de.openspri.soap.OrderCommands
import de.openspri.soap.OutboundPayloads
import de.openspri.soap.OutboxSender
import de.openspri.soap.SigningKeyHolder
import de.openspri.soap.SpriSchema
import de.openspri.soap.SpriClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Outbound delivery (M3, AUFGABE chapter 8.3): outbox, retries, outage notification.
 * Prefix `openspri.outbox` and `openspri.notifications`.
 */
@ConfigurationProperties("openspri.outbox")
data class OutboxProperties(
    /** Retry interval for requests without TEQ (11.2.3.2: "in regelmäßigen Abständen"). */
    val retryInterval: Duration = Duration.ofMinutes(5),
    /** Attempts before an entry becomes UNDELIVERABLE. */
    val maxAttempts: Int = 12,
    /** Wait after TEQ 0991 (interface overloaded). */
    val overloadedInterval: Duration = Duration.ofMinutes(1),
    /** Number of sequence keys sent in parallel. Each one keeps a database connection while sending. */
    val parallelism: Int = 4,
    /** At most one outage notification per partner and interval (11.2.3.2: once a day). */
    val outageNotificationInterval: Duration = Duration.ofDays(1),
)

@ConfigurationProperties("openspri.notifications")
data class NotificationProperties(
    /** Sender address; mails are only sent if spring.mail.host is configured. */
    val from: String = "open-spri@localhost",
    /** Own operations team, receives a copy of every notification. */
    val operatorEmail: String? = null,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties::class, NotificationProperties::class)
class DeliveryConfiguration {

    @Bean
    fun notifier(mail: ObjectProvider<JavaMailSender>, partners: PartnerDirectory, properties: NotificationProperties): Notifier =
        // An empty spring.mail.host (e.g. an unset variable in compose) means: no mail server
        MailNotifier(mail.ifAvailable?.takeUnless { (it as? org.springframework.mail.javamail.JavaMailSenderImpl)?.host.isNullOrBlank() }, partners, properties)

    @Bean
    fun deliveryService(outbox: Outbox, sender: OutboxSender, outages: OutageRegistry, notifier: Notifier, properties: OutboxProperties, events: EventLog) =
        DeliveryService(
            outbox, sender, outages, notifier,
            RetryPolicy(properties.retryInterval, properties.maxAttempts, properties.overloadedInterval),
            properties.outageNotificationInterval,
            events = events,
        )

    @Bean
    fun deliveryOperations(outbox: Outbox, deliveries: DeliveryQueries, transactions: Transactions, events: EventLog) =
        DeliveryOperations(outbox, deliveries, transactions, events)

    @Bean
    fun outboxSender(partners: PartnerDirectory, client: SpriClient) = OutboxSender(partners, client)

    @Bean
    fun outboundOrderService(
        orders: OrderRepository,
        outbox: Outbox,
        codes: MessageCodeCatalogHolder,
        transactions: Transactions,
        events: EventLog,
        drafts: OrderDrafts,
        agreements: AgreementRules,
        inventory: de.openspri.domain.order.InventoryBook,
    ) = OutboundOrderService(orders, outbox, codes::get, transactions, events = events, drafts = drafts, agreements = agreements, inventory = inventory)

    @Bean
    fun maintenanceService(partners: PartnerAdministration, notifier: Notifier, store: MaintenanceAnnouncements) =
        MaintenanceService(partners, notifier, store)

    @Bean
    fun outboundPayloads(schema: SpriSchema, keys: SigningKeyHolder) = OutboundPayloads(schema, keys::current)

    @Bean
    fun orderCommands(
        payloads: OutboundPayloads,
        service: OutboundOrderService,
        orders: OrderQueries,
        deliveries: DeliveryQueries,
        partners: PartnerDirectory,
        codes: MessageCodeCatalogHolder,
        archive: ArchiveQueries,
    ) = OrderCommands(payloads, service, orders, deliveries, partners, codes::get, archive)

    @Bean
    fun deliveryScheduler(service: DeliveryService, properties: OutboxProperties) = DeliveryScheduler(service, properties.parallelism)

    /** Starts [parallelism] senders per tick; each one sends until nothing is due. Ordering is ensured by the outbox. */
    class DeliveryScheduler(private val service: DeliveryService, private val parallelism: Int) : AutoCloseable {
        private val log = LoggerFactory.getLogger(javaClass)
        private val executor = Executors.newVirtualThreadPerTaskExecutor()

        @Scheduled(fixedDelayString = "\${openspri.outbox.poll-interval:1s}", initialDelayString = "\${openspri.outbox.initial-delay:5s}")
        fun tick() {
            val workers = (1..parallelism).map {
                executor.submit {
                    try {
                        service.drain()
                    } catch (e: Exception) {
                        log.error("Outbox delivery failed", e)
                    }
                }
            }
            workers.forEach { it.get() }
        }

        override fun close() = executor.close()
    }
}

/** E-mail notifications (specification 11.2.3.2). Without mail configuration they are only logged. */
class MailNotifier(
    private val mail: JavaMailSender?,
    private val partners: PartnerDirectory,
    private val properties: NotificationProperties,
) : Notifier {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun partnerInterfaceDown(partnerId: PartnerId, outage: PartnerOutage, lastError: String?) {
        val partner = partners.findById(partnerId)
        send(
            listOfNotNull(partner?.technicalContactEmail, properties.operatorEmail),
            "S/PRI: Ihre Schnittstelle ist nicht erreichbar",
            """
            |Guten Tag,
            |
            |unsere Meldungen an Ihre S/PRI-Schnittstelle (${partner?.endpointUrl ?: "unbekannt"}) können seit ${outage.since}
            |nicht zugestellt werden. Wir wiederholen den Versand automatisch.
            |
            |Letzter Fehler: ${lastError ?: "-"}
            |
            |Diese Nachricht wird je Ausfall höchstens einmal täglich versendet (S/PRI-Spezifikation 11.2.3.2).
            """.trimMargin(),
        )
    }

    override fun undeliverable(entry: OutboxEntry) {
        val partner = partners.findById(entry.partnerId)
        send(
            listOfNotNull(properties.operatorEmail),
            "S/PRI: Nachricht nicht zustellbar (${entry.externalOrderNumber ?: entry.id})",
            """
            |Die ${entry.operation.wsdlName}-Nachricht ${entry.id} zum Auftrag ${entry.externalOrderNumber} an ${partner?.name}
            |konnte nach ${entry.attempts} Versuchen nicht zugestellt werden.
            |
            |Letzter Fehler: ${entry.lastError ?: "-"}
            |
            |Nachfolgende Nachrichten zu diesem Auftrag warten, bis die Nachricht erneut gesendet, per E-Mail übermittelt
            |oder verworfen wurde (Zustellmonitor).
            """.trimMargin(),
        )
    }

    override fun maintenance(partner: Partner, announcement: MaintenanceAnnouncement): Boolean {
        val contact = partner.technicalContactEmail ?: return false
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val format = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        send(
            listOfNotNull(contact, properties.operatorEmail),
            "S/PRI: ${announcement.subject}",
            """
            |Guten Tag,
            |
            |wir kündigen eine Wartung unserer S/PRI-Schnittstelle an:
            |
            |Beginn: ${format.format(announcement.start.atZone(zone))} Uhr
            |Ende:   ${format.format(announcement.end.atZone(zone))} Uhr
            |
            |${announcement.text}
            |
            |In dieser Zeit ist die Schnittstelle nicht oder nur eingeschränkt erreichbar.
            |Diese Ankündigung erfolgt gemäß S/PRI-Spezifikation 11.5 (5 Werktage Vorlauf).
            """.trimMargin(),
        )
        return true
    }

    private fun send(to: List<String>, subject: String, text: String) {
        if (mail == null || to.isEmpty()) {
            log.warn("Notification (no mail configured or no recipient): {} - {}", subject, text.lines().firstOrNull { it.isNotBlank() })
            return
        }
        try {
            mail.send(SimpleMailMessage().apply { from = properties.from; setTo(*to.toTypedArray()); this.subject = subject; this.text = text })
        } catch (e: Exception) {
            log.error("Notification could not be sent to {}: {}", to, e.toString())
        }
    }
}
