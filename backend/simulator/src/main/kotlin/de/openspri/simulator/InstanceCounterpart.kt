package de.openspri.simulator

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.exchange.TeqCode
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.MessageInfo
import de.openspri.domain.order.OrderData
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.RequestKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * A second open-spri instance as counterpart (self-test AG ↔ LE, AUFGABE 10.1 item 6), driven only through its
 * REST API like our instance. What it received is read from its order history; a finding there counts as rejection.
 * Its requests are sent by its outbox; [sendOrder] and friends wait for the TEQ of our instance in its delivery monitor.
 *
 * Faulty orders of the negative test cases need the override of its own rules (ADR-0008): every order is sent with
 * all overrides, which only take effect if a rule finds something. Its token needs `orders:override`.
 *
 * @param partnerId our instance as partner of the counterpart
 */
class InstanceCounterpart(
    override val name: String,
    private val instance: OpenSpriClient,
    private val partnerId: Long,
    private val timeout: Duration = Duration.ofSeconds(20),
) : Counterpart {
    private val json = JsonMapper.builder().build()
    private val findings = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** Findings of the counterpart about our requests (its order history). */
    override fun rejections(externalOrderNumber: String): List<String> = findings[externalOrderNumber].orEmpty()
    override val canOverload = false
    override val canAnnounceProviderChange = false
    override val overloadPending = false
    override fun overloadNext() = throw UnsupportedOperationException("$name kann keine TEQ 0991 erzwingen")

    override fun prepare() = prepareInstance(instance, partnerId)

    override fun received(externalOrderNumber: String): List<Received> {
        val found = mutableListOf<String>()
        val received = instance.findOrders(null, externalOrderNumber).flatMap { order ->
            val case = BusinessCase.fromCode(order.path("businessCase").stringValue())
            instance.history(order.path("id").longValue()).filter { it.path("direction").stringValue() == "INBOUND" }.map { entry ->
                found += entry.path("findings").items().map { it.stringValue() }
                val indicator = ChangeIndicator.entries.first { it.code == entry.path("changeIndicator").stringValue() }
                val at = Instant.parse(entry.path("at").stringValue())
                if (entry.path("kind").stringValue() == "MESSAGE") {
                    val message = MessageInfo(
                        MessageType.fromCode(entry.path("messageType").stringValue())!!, case, indicator, externalOrderNumber,
                        entry.path("codes").items().map { it.stringValue() },
                        contractNumber = order.path("contractNumber").stringValue(null), lineId = order.path("lineId").stringValue(null),
                    )
                    Received(SoapOperation.ANNEHMEN_MELDUNG, externalOrderNumber, null, null, message, at)
                } else {
                    val data = OrderData(
                        externalOrderNumber, case,
                        changeIndicator = if (indicator == ChangeIndicator.REKLAMATION) ChangeIndicator.STANDARD else indicator,
                        complaint = indicator == ChangeIndicator.REKLAMATION,
                    )
                    Received(SoapOperation.ANNEHMEN_AUFTRAG, externalOrderNumber, null, data, null, at)
                }
            }
        }.sortedBy { it.at }
        findings[externalOrderNumber] = found
        return received
    }

    override fun sendOrder(auftrag: JsonNode): Pair<TeqCode?, ByteArray> {
        val body = instance.objectNode().put("partnerId", partnerId)
        body.set("auftrag", auftrag)
        val id = instance.post("/api/v1/orders?send=true&override=$ALL_OVERRIDES", body).path("order").path("id").longValue()
        return delivered(id) to json.writeValueAsBytes(auftrag)
    }

    override fun sendChange(original: ByteArray, kind: RequestKind, newDate: LocalDate?, complaint: JsonNode?): TeqCode? {
        val number = json.readTree(original).path("externeAuftragsnummer").stringValue()
        val id = order(number, OrderRole.AUFTRAGGEBER)
        when (kind) {
            RequestKind.STORNO -> instance.post("/api/v1/orders/$id/cancel")
            RequestKind.TERMINVERSCHIEBUNG -> instance.post("/api/v1/orders/$id/reschedule", instance.objectNode().put("newDate", newDate.toString()))
            RequestKind.REKLAMATION -> instance.post("/api/v1/orders/$id/complaint", instance.objectNode().also { it.set("auftrag", complaint) })
        }
        return delivered(id)
    }

    override fun sendMessage(
        data: OrderData,
        role: OrderRole,
        type: MessageType,
        changeIndicator: ChangeIndicator,
        codes: List<String>,
        attributes: JsonNode?,
    ): TeqCode? {
        val codeNodes = instance.codes(codes)
        val id = when (type) {
            MessageType.KDLE -> {
                val body = instance.objectNode().put("partnerId", partnerId).put("externalOrderNumber", data.externalOrderNumber)
                    .put("contractNumber", data.contractNumber).put("clientNumber", data.clientNumber).put("lineId", data.lineId)
                    .put("shutdownDate", attributes!!.path("abschaltungstermin").stringValue())
                body.set("codes", codeNodes)
                instance.post("/api/v1/terminations", body).path("order").path("id").longValue()
            }
            MessageType.RUEM_PV -> {
                val id = order(data.externalOrderNumber, OrderRole.ABGEBENDER_EKP)
                val consent = attributes?.path("abgebenderProvider")?.path("zustimmungProviderwechsel")?.stringValue() != "N"
                val body = instance.objectNode().put("consent", consent)
                body.set("codes", codeNodes)
                instance.post("/api/v1/pv-requests/$id/reply", body)
                id
            }
            else -> {
                val id = order(data.externalOrderNumber, role)
                val body = instance.objectNode().put("type", type.code).put("changeIndicator", changeIndicator.code)
                body.set("codes", codeNodes)
                attributes?.let { body.set("attributes", it) }
                instance.post("/api/v1/orders/$id/messages", body)
                id
            }
        }
        return delivered(id)
    }

    /** The order of the counterpart with this number in [role]; it may still be in its inbox. */
    private fun order(number: String, role: OrderRole): Long = await("Auftrag $number ($role) bei $name") {
        instance.findOrders(role.name, number).firstOrNull()?.path("id")?.longValue()
    }

    /** TEQ of our instance for the newest request of the counterpart's order (delivery monitor). */
    private fun delivered(orderId: Long): TeqCode? {
        val newest = instance.get("/api/v1/deliveries?orderId=$orderId&limit=1").items().first().path("id").longValue()
        val delivery = await("Zustellung $newest von $name") {
            instance.get("/api/v1/deliveries/$newest").takeIf { it.path("status").stringValue() != "PENDING" }
        }
        return delivery.path("teqCode").stringValue(null)?.let(TeqCode::fromCode)
    }

    private fun <T : Any> await(what: String, probe: () -> T?): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            probe()?.let { return it }
            if (System.nanoTime() > deadline) throw ScenarioFailure("Zeitüberschreitung: $what")
            Thread.sleep(50)
        }
    }

    private companion object {
        const val ALL_OVERRIDES = "R-05,GESCHAEFTSFALLART,AENDERUNGSKENNZEICHEN"
    }
}
