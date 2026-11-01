package de.openspri.app

import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.exchange.TeqCode
import de.openspri.domain.partner.IpNetwork
import de.openspri.persistence.JdbcPartnerDirectory
import de.openspri.soap.SigningCredentials
import de.openspri.soap.SpriClient
import de.openspri.soap.TestData
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Boots the full application against a real MariaDB. Skipped automatically without Docker. */
@SpringBootTest(
    classes = [OpenSpriApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["openspri.inbox.initial-delay=0s", "openspri.inbox.poll-interval=200ms", "openspri.webhooks.initial-delay=1h"],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ApplicationIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val partners: JdbcPartnerDirectory,
    @Autowired private val client: SpriClient,
    @Autowired private val keys: de.openspri.soap.SigningKeyHolder,
    @Autowired private val orderRepository: de.openspri.persistence.JdbcOrderRepository,
    @Autowired private val outbox: de.openspri.persistence.JdbcOutbox,
    @Autowired private val inbox: de.openspri.persistence.JdbcInboxQueue,
    @Autowired private val archive: de.openspri.persistence.JdbcMessageArchive,
    @Autowired private val events: de.openspri.persistence.JdbcEvents,
    @Autowired private val idempotency: de.openspri.persistence.JdbcIdempotencyStore,
    @Autowired private val drafts: de.openspri.persistence.JdbcOrderDrafts,
    @Autowired private val agreements: de.openspri.persistence.JdbcPartnerAgreements,
    @Autowired private val products: de.openspri.persistence.JdbcProductCatalog,
    @Autowired private val maintenance: de.openspri.persistence.JdbcMaintenanceAnnouncements,
    @Autowired private val inventory: de.openspri.persistence.JdbcInventory,
) {
    @LocalServerPort
    private var port = 0

    private val credentials: SigningCredentials get() = keys.current()

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mariadb = MariaDBContainer("mariadb:11.8")
    }

    @Test
    fun `flyway baseline creates the default tenant`() {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tenant", Int::class.java))
        assertEquals(false, jdbc.queryForObject("SELECT completed FROM setup_state WHERE tenant_id = 1", Boolean::class.java))
    }

    @Test
    fun `health and about endpoints respond`() {
        mockMvc.get("/health").andExpect { status { isOk() } }
        mockMvc.get("/health/readiness").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/about").andExpect {
            status { isOk() }
            jsonPath("$.spriVersion") { value("4.4") }
            jsonPath("$.certifiedBuild") { value(false) }
        }
    }

    @Test
    fun `S-PRI roundtrip is archived in both directions and queued`() {
        val id = partners.create(
            name = "Selbsttest",
            carrierCode = "DEU.SELFIT",
            certificates = listOf(credentials.certificate),
            networks = listOf(IpNetwork.parse("127.0.0.1"), IpNetwork.parse("::1")),
        )
        val partner = partners.enabledPartners().single { it.id == id }
        val outcome = client.send(
            partner, URI("http://localhost:$port/spri/v4"), SoapOperation.ANNEHMEN_AUFTRAG,
            TestData.payload("neu-auftrag.xml", credentials), "TEST-0001",
        )
        assertEquals(TeqCode.OK, assertIs<SpriClient.Outcome.Acknowledged>(outcome, outcome.toString()).teq)

        val archived = jdbc.queryForList(
            "SELECT direction, teq_code FROM soap_archive WHERE partner_id = ? ORDER BY direction", id.value,
        )
        assertEquals(listOf(mapOf("direction" to "INBOUND", "teq_code" to "OK"), mapOf("direction" to "OUTBOUND", "teq_code" to "OK")), archived)
        assertEquals(
            "TEST-0001",
            jdbc.queryForObject("SELECT external_order_number FROM inbox_message WHERE partner_id = ?", String::class.java, id.value),
        )

        // M2: the scheduled inbox worker turns the accepted order into an order of the Leistungserbringer
        val deadline = System.currentTimeMillis() + 15_000
        var orders = 0
        while (orders == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spri_order WHERE partner_id = ? AND role = 'LEISTUNGSERBRINGER' AND phase = 'SUBMITTED'", Int::class.java, id.value,
            )!!
        }
        assertEquals(1, orders)
        assertEquals("PROCESSED", jdbc.queryForObject("SELECT status FROM inbox_message WHERE partner_id = ?", String::class.java, id.value))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM spri_order_history WHERE partner_id = ? AND kind = 'ORDER'", Int::class.java, id.value))
    }

    @Test
    fun `query ports for the REST API work on MariaDB`() {
        val partner = partners.create(name = "Abfrage", carrierCode = "DEU.QUERY", certificates = listOf(credentials.certificate), networks = emptyList())
        val now = java.time.Instant.now()
        val data = de.openspri.domain.order.OrderData("QRY_%-1", de.openspri.domain.BusinessCase.NEU, lineId = "DEU.QUERY.L1")
        val order = orderRepository.save(
            de.openspri.domain.order.Order(null, partner, de.openspri.domain.order.OrderRole.AUFTRAGGEBER, data,
                de.openspri.domain.order.OrderLifecycle.submitted(data.businessCase), now, now, sequenceKey = "k"),
        )
        orderRepository.append(
            de.openspri.domain.order.OrderHistoryEntry(order.id, partner, null, de.openspri.domain.order.OrderHistoryEntry.Kind.ORDER, findings = listOf("a", "b"), at = now, direction = de.openspri.domain.exchange.Direction.OUTBOUND),
        )
        outbox.enqueue(
            de.openspri.domain.delivery.OutboxEntry(null, partner, order.id, de.openspri.domain.delivery.RequestType.ORDER, SoapOperation.ANNEHMEN_AUFTRAG,
                data.externalOrderNumber, "k", "<a/>".toByteArray(), nextAttemptAt = now, createdAt = now),
        )

        // LIKE wildcards in the search text are matched literally
        assertEquals(listOf(order.id), orderRepository.search(de.openspri.domain.query.OrderFilter(text = "QRY_%")).map { it.id })
        assertEquals(emptyList(), orderRepository.search(de.openspri.domain.query.OrderFilter(text = "QRYX")))
        assertEquals(listOf(order.id), orderRepository.search(de.openspri.domain.query.OrderFilter(partnerId = partner, openOnly = true, text = "DEU.QUERY")).map { it.id })
        assertEquals(listOf("a", "b"), orderRepository.history(order.id!!).single().findings)
        assertEquals("<a/>", String(outbox.originalOrderPayload(order.id!!)!!))
        assertEquals(1, outbox.list(de.openspri.domain.delivery.DeliveryStatus.PENDING, order.id, null, 10).size)
        inbox.list(null, null, 10)

        val archiveId = archive.storeRejected(
            de.openspri.domain.exchange.ArchivedExchange(
                de.openspri.domain.exchange.Direction.INBOUND, null, partner, "127.0.0.1", now, 3, "<x/>".toByteArray(), null,
                TeqCode.STRUCTURE_INVALID, "test", null, null, null,
            ),
        )
        assertEquals(TeqCode.STRUCTURE_INVALID, archive.get(archiveId)!!.teqCode)
    }

    @Test
    fun `drafts change every column, are deleted with their content, handover filter works`() {
        val partner = partners.create(name = "Entwurf", carrierCode = "DEU.DRAFT", certificates = listOf(credentials.certificate), networks = emptyList())
        val now = java.time.Instant.now()
        val data = de.openspri.domain.order.OrderData("DRF-1", de.openspri.domain.BusinessCase.NEU)
        val draft = orderRepository.save(
            de.openspri.domain.order.Order(null, partner, de.openspri.domain.order.OrderRole.AUFTRAGGEBER, data,
                de.openspri.domain.order.OrderLifecycle.draft(data.businessCase), now, now),
        )
        drafts.put(draft.id!!, "{\"a\":1}".toByteArray())
        drafts.put(draft.id!!, "{\"a\":2}".toByteArray())
        assertEquals("{\"a\":2}", String(drafts.get(draft.id!!)!!))

        val changed = data.copy(externalOrderNumber = "DRF-2", homeId = "HOME-1", products = listOf("P1"), projectId = "PRJ")
        orderRepository.save(draft.copy(data = changed))
        val stored = orderRepository.get(draft.id!!)!!
        assertEquals(listOf("DRF-2", "HOME-1", "PRJ"), listOf(stored.data.externalOrderNumber, stored.data.homeId, stored.data.projectId))
        assertEquals(de.openspri.domain.order.Phase.DRAFT, stored.state.phase)

        assertEquals(true, orderRepository.deleteDraft(draft.id!!))
        assertEquals(null, orderRepository.get(draft.id!!))
        assertEquals(null, drafts.get(draft.id!!))

        val pv = orderRepository.save(
            de.openspri.domain.order.Order(null, partner, de.openspri.domain.order.OrderRole.ABGEBENDER_EKP,
                de.openspri.domain.order.OrderData("PV-1", de.openspri.domain.BusinessCase.PV),
                de.openspri.domain.order.OrderLifecycle.handoverAnnounced().copy(handover = de.openspri.domain.order.HandoverPhase.ANNOUNCED), now, now),
        )
        assertEquals(false, orderRepository.deleteDraft(pv.id!!), "only drafts can be deleted")
        assertEquals(listOf(pv.id), orderRepository.search(de.openspri.domain.query.OrderFilter(partnerId = partner, handover = de.openspri.domain.order.HandoverPhase.ANNOUNCED)).map { it.id })
        assertEquals(emptyList(), orderRepository.search(de.openspri.domain.query.OrderFilter(partnerId = partner, handover = de.openspri.domain.order.HandoverPhase.REPLIED)))
    }

    @Test
    fun `agreements, products, change matrix and maintenance announcements on MariaDB`() {
        val partner = partners.create(name = "Stammdaten", carrierCode = "DEU.MASTER", certificates = listOf(credentials.certificate), networks = emptyList())
        val other = partners.create(name = "Andere", carrierCode = "DEU.OTHER", certificates = listOf(credentials.certificate), networks = emptyList())
        val today = java.time.LocalDate.now()
        val validity = de.openspri.domain.partner.Validity(today, today.plusDays(10))

        val contract = agreements.saveContract(de.openspri.domain.partner.FrameworkContract(null, partner, de.openspri.domain.order.OrderRole.LEISTUNGSERBRINGER, "AG1", null, "R", validity))
        agreements.saveContract(contract.copy(serviceNumber = "LN1"))
        assertEquals(listOf("LN1"), agreements.contracts(partner).map { it.serviceNumber })
        assertEquals(validity, agreements.contracts(partner).single().validity)
        org.junit.jupiter.api.assertThrows<NoSuchElementException> { agreements.saveContract(contract.copy(partnerId = other)) }
        assertEquals(false, agreements.deleteContract(other, contract.id!!))

        val power = agreements.savePowerOfAttorney(de.openspri.domain.partner.PowerOfAttorney(null, partner, "B1", "BL1", "AG1", "LN1"))
        assertEquals(true, agreements.saveDocument(partner, power.id!!, de.openspri.domain.partner.AgreementDocument("v.pdf", "application/pdf", byteArrayOf(1, 2, 3))))
        assertEquals(false, agreements.saveDocument(other, power.id!!, de.openspri.domain.partner.AgreementDocument("v.pdf", "application/pdf", byteArrayOf(1))))
        assertEquals("v.pdf", agreements.powersOfAttorney(partner).single().documentName)
        assertEquals(listOf<Byte>(1, 2, 3), agreements.document(partner, power.id!!)!!.content.toList())
        assertEquals(null, agreements.document(other, power.id!!))

        agreements.saveProject(de.openspri.domain.partner.ProjectAgreement(null, partner, "P1"))
        assertEquals(listOf("P1"), agreements.projects(partner).map { it.projectId })

        val global = products.save(de.openspri.domain.partner.Product(null, null, de.openspri.domain.order.OrderRole.LEISTUNGSERBRINGER, "FTTH 100"))
        products.save(de.openspri.domain.partner.Product(null, other, de.openspri.domain.order.OrderRole.LEISTUNGSERBRINGER, "Nur Andere"))
        assertEquals(listOf("FTTH 100"), products.list(partner, de.openspri.domain.order.OrderRole.LEISTUNGSERBRINGER).map { it.identifier })
        products.save(global.copy(active = false))
        assertEquals(false, products.get(global.id!!)!!.active)

        val lae = de.openspri.domain.partner.ProductChange(null, "FTTH 10", "FTTH 20", de.openspri.domain.BusinessCase.LAE)
        products.replaceChanges(null, listOf(lae))
        products.replaceChanges(partner, listOf(lae.copy(partnerId = partner, businessCase = de.openspri.domain.BusinessCase.AEN_LMAE)))
        products.replaceChanges(null, listOf(lae))
        assertEquals(listOf(lae), products.changes(null))
        assertEquals(de.openspri.domain.BusinessCase.AEN_LMAE, products.changes(partner).single().businessCase)

        val now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val saved = maintenance.save(de.openspri.domain.partner.MaintenanceAnnouncement(null, now, now.plusSeconds(3600), "W", "T", listOf(partner, other), now, "user:admin", 1, listOf("a", "b")))
        val listed = maintenance.list(1).single()
        assertEquals(saved.id, listed.id)
        assertEquals(listOf(partner, other), listed.partnerIds)
        assertEquals(listOf("a", "b"), listed.warnings)
    }

    @Test
    fun `inventory contracts and the abgebender EKP of an order on MariaDB`() {
        val partner = partners.create(name = "Bestand", carrierCode = "DEU.INVENT", certificates = listOf(credentials.certificate), networks = emptyList())
        val now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val le = de.openspri.domain.order.OrderRole.LEISTUNGSERBRINGER
        val first = inventory.add(de.openspri.domain.order.InventoryContract(null, le, partner, "V1", "DEU.INV.1", "AG1", "LN1", listOf("FTTH 100", "Konnektivität"), null, now))
        assertEquals(listOf("FTTH 100", "Konnektivität"), inventory.active(le, "DEU.INV.1")!!.products)
        assertEquals(0, inventory.end(le, null, null, now))
        assertEquals(1, inventory.end(le, "DEU.INV.1", null, now))
        assertEquals(null, inventory.active(le, "DEU.INV.1"))
        inventory.add(first.copy(id = null, contractNumber = "V2"))
        assertEquals(1, inventory.end(le, null, "V2", now))
        assertEquals(2, inventory.search(de.openspri.domain.order.InventoryFilter(lineId = "DEU.INV.1", activeOnly = false)).size)
        assertEquals(emptyList(), inventory.search(de.openspri.domain.order.InventoryFilter(partnerId = partner)))

        val data = de.openspri.domain.order.OrderData("HO-1", de.openspri.domain.BusinessCase.PV, lineId = "DEU.INV.1",
            handover = de.openspri.domain.order.HandoverParty(partner, "V1", "AG1"))
        val order = orderRepository.save(
            de.openspri.domain.order.Order(null, partner, le, data, de.openspri.domain.order.OrderLifecycle.submitted(data.businessCase), now, now),
        )
        assertEquals(data.handover, orderRepository.get(order.id!!)!!.data.handover)
    }

    @Test
    fun `events become visible after the delay, webhooks advance, idempotency keys are unique`() {
        val partner = partners.create(name = "Ereignis", carrierCode = "DEU.EVENT", certificates = listOf(credentials.certificate), networks = emptyList())
        val start = events.latestId()
        val now = java.time.Instant.now()
        events.publish(de.openspri.domain.event.BusinessEvent(null, de.openspri.domain.event.EventType.MESSAGE_RECEIVED, partner, null, null, "EVT-1", null, null,
            messageType = de.openspri.domain.message.MessageType.TAM, codes = listOf("0000"), at = now))
        assertEquals(emptyList(), events.after(start, 10), "not visible within the delay")

        val webhook = events.create(de.openspri.domain.event.WebhookSubscription(null, "https://hook.example.invalid", byteArrayOf(1, 2), emptySet(), true, null, start, nextAttemptAt = now, createdAt = now))
        Thread.sleep(de.openspri.persistence.JdbcEvents.VISIBILITY_DELAY_MS + 500L)
        val visible = events.after(start, 10).single()
        assertEquals(de.openspri.domain.message.MessageType.TAM, visible.messageType)
        assertEquals(listOf("0000"), visible.codes)
        assertEquals(emptyList(), events.after(start, 10, setOf(de.openspri.domain.event.EventType.ORDER_RECEIVED)))

        assertEquals(true, events.deliverNext(java.time.Instant.now()) { it.copy(lastEventId = visible.id!!) })
        assertEquals(visible.id, events.get(webhook.id!!)!!.lastEventId)
        assertEquals(false, events.deliverNext(java.time.Instant.now()) { it }, "nothing new")

        val claim = idempotency.claim("token:a", "k1", "h1", now)
        assertIs<de.openspri.domain.event.IdempotencyClaim.Claimed>(claim)
        assertEquals(de.openspri.domain.event.IdempotencyClaim.InProgress, idempotency.claim("token:a", "k1", "h1", now))
        assertEquals(de.openspri.domain.event.IdempotencyClaim.Mismatch, idempotency.claim("token:a", "k1", "h2", now))
        idempotency.complete(claim.id, de.openspri.domain.event.StoredResponse(202, "{}".toByteArray(), "application/json"))
        assertEquals(202, assertIs<de.openspri.domain.event.IdempotencyClaim.Replay>(idempotency.claim("token:a", "k1", "h1", now)).response.status)
        assertIs<de.openspri.domain.event.IdempotencyClaim.Claimed>(idempotency.claim("token:b", "k1", "h1", now), "keys are per actor")
    }
}
