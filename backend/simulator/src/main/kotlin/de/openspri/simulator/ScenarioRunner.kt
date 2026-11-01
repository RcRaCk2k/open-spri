package de.openspri.simulator

import de.openspri.domain.ChangeIndicator
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.exchange.TeqCode
import de.openspri.domain.message.CodeCheck
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.OrderData
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.RequestKind
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/** Result of one test case: [transcript] lists every step as it happened, [failure] is null if it passed. */
data class ScenarioResult(val scenario: Scenario, val transcript: List<String>, val failure: String?, val millis: Long) {
    val passed: Boolean get() = failure == null
}

class ScenarioFailure(message: String) : AssertionError(message)

/**
 * Plays a [Scenario] between our instance (REST, [us]) and the [simulator] (SOAP) and checks the message
 * sequence including the message codes (AUFGABE 10.1 item 5):
 * - every request of the simulator is answered with TEQ OK, every request of ours passes the simulator's
 *   strict entry check (XSD, Anlage 1, signature),
 * - every message the simulator sends is accepted by our lifecycle without finding,
 * - every message we send arrives at the simulator with the expected type and codes,
 * - the order is finished at the end.
 *
 * @param partnerId the simulator as partner of our instance
 */
class ScenarioRunner(
    private val us: OpenSpriClient,
    private val partnerId: Long,
    private val simulator: SimulatedPartner,
    private val catalog: () -> MessageCodeCatalog,
    private val timeout: Duration = Duration.ofSeconds(20),
) {
    private val counter = AtomicInteger()

    /** Why a test case cannot run yet; null = supported. Shrinks to nothing during M5 (coverage gate). */
    fun unsupported(case: KftTestCase): String? = when {
        case.businessCase !in OrderTemplates.supported -> "Auftragsvorlage für ${case.businessCase.code} fehlt"
        case.inventory -> "Bestandsaufbau (BA = X) fehlt"
        TestCaseVariants.missing(case).isNotEmpty() -> TestCaseVariants.missing(case).joinToString { it.label } + " fehlt"
        else -> null
    }

    fun run(scenario: Scenario): ScenarioResult {
        val started = System.currentTimeMillis()
        val run = Run(scenario)
        val failure = try {
            unsupported(scenario.case)?.let { throw ScenarioFailure("nicht unterstützt: $it") }
            scenario.steps.forEach(run::execute)
            run.finish()
            null
        } catch (e: Throwable) {
            run.log("FEHLER: ${e.message}")
            e.message ?: e.javaClass.simpleName
        }
        return ScenarioResult(scenario, run.transcript, failure, System.currentTimeMillis() - started)
    }

    /** State of one run. */
    private inner class Run(val scenario: Scenario) {
        val transcript = mutableListOf<String>()
        val case = scenario.case
        val number = "KFT" + case.id.filter { it.isDigit() }.take(8) + "-" + counter.incrementAndGet() + "-" + (System.nanoTime() % 1000)
        val desiredDate: LocalDate = LocalDate.now().plusDays(30)
        val lineId = "DEU.SIMLE." + number.filter { it.isDigit() }.takeLast(10)
        var ourOrderId: Long? = null
        /** the order as the simulator knows it */
        var simOrder: OrderData? = null
        var simOriginal: ByteArray? = null
        var receivedSeen = simulator.received().size
        var historySeen = 0
        val houseNumber = counter.get()
        var reschedules = 0
        val contract = "V" + number.filter { it.isDigit() }.takeLast(9)

        /** Date of the next Terminverschiebung: always a new one (R-02). */
        fun nextDate(): LocalDate = desiredDate.plusDays(7L * ++reschedules)

        /** The Leistungserbringer assigns the contract number with its first message that requires one (ABM, or ENTM after a Storno). */
        fun carriesContract(type: MessageType) = type in MessageDefaults.CONTRACT_REQUIRED

        fun log(line: String) { transcript += line }

        val simRole get() = when (scenario.role) {
            OrderRole.AUFTRAGGEBER, OrderRole.ABGEBENDER_EKP -> OrderRole.LEISTUNGSERBRINGER
            OrderRole.LEISTUNGSERBRINGER -> OrderRole.AUFTRAGGEBER
        }

        fun execute(step: ScenarioStep) {
            when (val action = step.action) {
                Action.PlaceOrder -> if (step.side == Side.US) ourOrder() else simulatorOrder()
                is Action.Request -> if (step.side == Side.US) ourRequest(action.kind) else simulatorRequest(action.kind)
                is Action.Message -> if (step.side == Side.US) ourMessage(action) else simulatorMessage(action)
            }
        }

        // --- orders and requests --------------------------------------------------------------

        fun ourOrder() {
            val auftrag = OrderTemplates.order(case.businessCase, number, desiredDate, houseNumber)
            val body = us.objectNode().put("partnerId", partnerId)
            body.set("auftrag", auftrag)
            ourOrderId = us.post("/api/v1/orders?send=true", body).path("order").path("id").longValue()
            log("open-spri → Auftrag ${case.businessCase.code} $number")
            simOrder = awaitReceived(SoapOperation.ANNEHMEN_AUFTRAG) { it.order?.changeIndicator == ChangeIndicator.STANDARD }.order
            historySeen = us.history(ourOrderId!!).size
        }

        fun simulatorOrder() {
            val (teq, payload) = simulator.sendOrder(OrderTemplates.order(case.businessCase, number, desiredDate, houseNumber))
            expectOk(teq, "Auftrag")
            simOriginal = payload
            log("Simulator → Auftrag ${case.businessCase.code} $number (TEQ OK)")
            ourOrderId = await("Auftrag $number bei open-spri") { us.findOrders("LEISTUNGSERBRINGER", number).firstOrNull()?.path("id")?.longValue() }
            simOrder = OrderData(number, case.businessCase, clientNumber = OrderTemplates.Parties().clientNumber, serviceNumber = OrderTemplates.Parties().serviceNumber)
            awaitHistory("ORDER", null)
        }

        fun ourRequest(kind: RequestKind) {
            val id = requireNotNull(ourOrderId)
            when (kind) {
                RequestKind.STORNO -> us.post("/api/v1/orders/$id/cancel")
                RequestKind.TERMINVERSCHIEBUNG -> us.post("/api/v1/orders/$id/reschedule", us.objectNode().put("newDate", nextDate().toString()))
                RequestKind.REKLAMATION -> throw ScenarioFailure("Reklamation noch nicht unterstützt")
            }
            log("open-spri → ${kind.changeIndicator.code}")
            awaitReceived(SoapOperation.ANNEHMEN_AUFTRAG) { it.order?.changeIndicator == kind.changeIndicator }
            historySeen = us.history(id).size
        }

        fun simulatorRequest(kind: RequestKind) {
            val original = requireNotNull(simOriginal) { "kein ursprünglicher Auftrag des Simulators" }
            expectOk(simulator.sendChange(original, kind, if (kind == RequestKind.TERMINVERSCHIEBUNG) nextDate() else null), kind.changeIndicator.code)
            log("Simulator → ${kind.changeIndicator.code} (TEQ OK)")
            awaitHistory("REQUEST", null)
        }

        // --- messages --------------------------------------------------------------------------

        fun simulatorMessage(message: Action.Message) {
            val data = requireNotNull(simOrder) { "Simulator kennt den Auftrag nicht" }
            val codes = codesFor(message)
            val attributes = MessageDefaults.attributes(message.type, MessageDefaults.Context(lineId, dateFor(message)))
            if (simRole == OrderRole.LEISTUNGSERBRINGER && data.contractNumber == null && carriesContract(message.type)) {
                attributes.put("vertragsnummer", contract)
                simOrder = data.copy(contractNumber = contract)
            }
            expectOk(simulator.sendMessage(simOrder!!, simRole, message.type, message.changeIndicator, codes, attributes), message.type.code)
            log("Simulator → ${message.type.code} (${codes.joinToString()})${indicator(message)}")
            awaitHistory("MESSAGE", message.type)
        }

        fun ourMessage(message: Action.Message) {
            val id = requireNotNull(ourOrderId)
            val codes = codesFor(message)
            if (message.repeated) {
                // nothing to do for us: the outbox repeats the message after TEQ 0991 (specification 11.2.3.2)
                val again = awaitReceived(SoapOperation.ANNEHMEN_MELDUNG) { it.message?.type == message.type }.message!!
                log("open-spri wiederholt ${message.type.code} nach TEQ 0991 (${again.codes.joinToString()})")
                historySeen = us.history(id).size
                return
            }
            if (message.overloaded) simulator.nextTeq = TeqCode.OVERLOADED
            val order = us.order(id)
            val attributes = MessageDefaults.attributes(message.type, MessageDefaults.Context(lineId, dateFor(message)))
            if (order.path("contractNumber").isNull && scenario.role == OrderRole.LEISTUNGSERBRINGER && carriesContract(message.type)) {
                attributes.put("vertragsnummer", contract)
            }
            val body = us.objectNode().put("type", message.type.code).put("changeIndicator", message.changeIndicator.code)
            body.set("codes", us.codes(codes))
            body.set("attributes", attributes)
            us.post("/api/v1/orders/$id/messages", body)
            log("open-spri → ${message.type.code} (${codes.joinToString()})${indicator(message)}")
            if (message.overloaded) {
                await("TEQ 0991 an open-spri") { if (simulator.nextTeq == null) true else null }
                log("Simulator → TEQ 0991 (Schnittstelle überlastet)")
                return
            }
            val received = awaitReceived(SoapOperation.ANNEHMEN_MELDUNG) { it.message?.type == message.type }.message!!
            // the simulator as Auftraggeber learns contract number and LineID from our messages (ABM)
            simOrder = simOrder?.let { known ->
                known.copy(contractNumber = known.contractNumber ?: received.contractNumber, lineId = known.lineId ?: received.lineId)
            }
            if (received.codes != codes) throw ScenarioFailure("${message.type.code}: Simulator erhielt Codes ${received.codes}, erwartet $codes")
            if (received.changeIndicator != message.changeIndicator && message.changeIndicator != ChangeIndicator.REKLAMATION) {
                throw ScenarioFailure(
                    "${message.type.code}: Änderungskennzeichen ${received.changeIndicator.code}, erwartet ${message.changeIndicator.code}; beim Simulator: " +
                        simulator.received().filter { it.externalOrderNumber == number }.joinToString { "${it.message?.type?.code ?: "Auftrag"}/${(it.message?.changeIndicator ?: it.order?.changeIndicator)?.code}" },
                )
            }
            historySeen = us.history(id).size
        }

        fun finish() {
            val id = requireNotNull(ourOrderId) { "kein Auftrag" }
            val order = us.order(id)
            if (!order.path("finished").booleanValue()) throw ScenarioFailure("Auftrag nicht abgeschlossen: Phase ${order.path("phase").stringValue()}")
            if (simulator.rejections.isNotEmpty()) throw ScenarioFailure("Simulator wies ab: ${simulator.rejections}")
            log("Auftrag abgeschlossen (${order.path("phase").stringValue()})")
        }

        // --- helpers ---------------------------------------------------------------------------

        fun indicator(message: Action.Message) = if (message.changeIndicator == ChangeIndicator.STANDARD) "" else " zu ${message.changeIndicator.code}"

        /** Explicit codes of the test case, otherwise the code of the hint (ABBM) or any allowed code (`*2`). */
        fun codesFor(message: Action.Message): List<String> = when {
            message.codes.isNotEmpty() -> message.codes
            message.type == MessageType.ABBM && Variant.TWO_CODES in TestCaseVariants.of(case) -> listOf(anyCode(message.type), FREE_TEXT)
            message.type == MessageType.ABBM && TestCaseVariants.hintCode(case) != null -> listOf(TestCaseVariants.hintCode(case)!!)
            else -> listOf(anyCode(message.type))
        }

        /** Date of an ABM: the desired date, or a later one for "->neuer Termin" (hint: at least one day later). */
        fun dateFor(message: Action.Message): LocalDate = if (message.newDate) desiredDate.plusDays(3) else desiredDate

        /** A code the message code table allows for this type and business case (`*2`). */
        fun anyCode(type: MessageType): String =
            catalog().codes.keys.sorted().firstOrNull { it != FREE_TEXT && catalog().check(it, type, case.businessCase) is CodeCheck.Allowed }
                ?: throw ScenarioFailure("kein zulässiger Meldungscode für ${type.code}/${case.businessCase.code}")

        fun expectOk(teq: TeqCode?, what: String) {
            if (teq != TeqCode.OK) throw ScenarioFailure("$what: TEQ ${teq ?: "unbekannt"} von open-spri")
        }

        /** Waits for the next request of ours at the simulator that matches [match]. */
        fun awaitReceived(operation: SoapOperation, match: (Received) -> Boolean): Received {
            val found = await("${operation.wsdlName} beim Simulator") {
                simulator.received().drop(receivedSeen).firstOrNull { it.operation == operation && it.externalOrderNumber == number && match(it) }
            }
            receivedSeen = simulator.received().indexOf(found) + 1
            if (simulator.rejections.isNotEmpty()) throw ScenarioFailure("Simulator wies ab: ${simulator.rejections}")
            return found
        }

        /** Waits until our instance has processed the simulator's request; fails on lifecycle or code findings. */
        fun awaitHistory(kind: String, type: MessageType?) {
            val id = ourOrderId ?: await("Auftrag $number bei open-spri") {
                us.findOrders(ourRoleName(), number).firstOrNull()?.path("id")?.longValue()
            }.also { ourOrderId = it }
            val entry: JsonNode = await("${type?.code ?: kind} verarbeitet") {
                us.history(id).drop(historySeen).firstOrNull { it.path("direction").stringValue() == "INBOUND" && it.path("kind").stringValue() == kind }
            }
            historySeen = us.history(id).indexOfFirst { it == entry } + 1
            val findings = entry.path("findings").items().map { it.stringValue() }
            if (findings.isNotEmpty()) throw ScenarioFailure("${type?.code ?: kind}: Befunde bei open-spri: $findings")
            if (type != null && entry.path("messageType").stringValue() != type.code) {
                throw ScenarioFailure("erwartet ${type.code}, open-spri verarbeitete ${entry.path("messageType").stringValue()}")
            }
        }

        fun ourRoleName() = scenario.role.name

        fun <T : Any> await(what: String, probe: () -> T?): T {
            val deadline = System.nanoTime() + timeout.toNanos()
            while (true) {
                probe()?.let { return it }
                if (System.nanoTime() > deadline) throw ScenarioFailure("Zeitüberschreitung: $what")
                Thread.sleep(50)
            }
        }
    }


    private companion object {
        /** Meldungscode Freitext (TF S.1.2.6: one of the two positions of the ABBM). */
        const val FREE_TEXT = "1312"
    }
}
