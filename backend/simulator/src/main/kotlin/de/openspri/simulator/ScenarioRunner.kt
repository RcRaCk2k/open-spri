package de.openspri.simulator

import de.openspri.domain.BusinessCase
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
data class ScenarioResult(val scenario: Scenario, val transcript: List<String>, val failure: String?, val millis: Long, val orderId: Long? = null) {
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
        case.businessCase !in OrderTemplates.supported && case.businessCase != BusinessCase.KUE_LE -> "Auftragsvorlage für ${case.businessCase.code} fehlt"
        TestCaseVariants.missing(case).isNotEmpty() -> TestCaseVariants.missing(case).joinToString { it.label } + " fehlt"
        else -> null
    }

    /**
     * Agreements a KFT needs in our instance (the operator maintains them before the test): the Projektvereinbarung
     * for the Projektkenner (R-12) and the power of attorney for orders in the name of a third party (R-11).
     */
    fun prepare() {
        val path = "/api/v1/partners/$partnerId"
        if (us.get("$path/projects").items().none { it.path("projectId").stringValue() == OrderTemplates.PROJECT_ID }) {
            us.post("$path/projects", us.objectNode().put("projectId", OrderTemplates.PROJECT_ID).put("description", "KFT-Szenarien"))
        }
        val parties = OrderTemplates.Parties()
        us.post(
            "$path/powers-of-attorney",
            us.objectNode().put("ordererClientNumber", OrderTemplates.ORDERER.clientNumber).put("ordererServiceNumber", OrderTemplates.ORDERER.serviceNumber)
                .put("clientNumber", parties.clientNumber).put("serviceNumber", parties.serviceNumber).put("description", "KFT-Szenarien"),
        )
    }

    fun run(scenario: Scenario): ScenarioResult {
        val started = System.currentTimeMillis()
        val run = Run(scenario)
        val failure = try {
            unsupported(scenario.case)?.let { throw ScenarioFailure("nicht unterstützt: $it") }
            if (needsInventory(scenario)) run.inventory = buildInventory(run)
            scenario.steps.forEach(run::execute)
            run.finish()
            null
        } catch (e: Throwable) {
            run.log("FEHLER: ${e.message}")
            e.message ?: e.javaClass.simpleName
        }
        return ScenarioResult(scenario, run.transcript, failure, System.currentTimeMillis() - started, run.ourOrderId)
    }

    /** BA = X, and every business case on an existing line (KUE, AEN-LMAE, LAE, EST, DIAG, KUE-LE, AKM-PV). */
    private fun needsInventory(scenario: Scenario): Boolean {
        val first = (scenario.steps.firstOrNull()?.action as? Action.Message)?.type
        return scenario.case.inventory || scenario.case.businessCase in OrderTemplates.needsInventory ||
            first == MessageType.KDLE || first == MessageType.AKM_PV
    }

    /**
     * Bestandsaufbau (test case list, BA = X): a NEU order with the standard chain between the same parties, in the
     * same roles. Its contract number and LineID are the inventory the test case works on.
     */
    private fun buildInventory(run: Run): OrderTemplates.Inventory {
        val case = run.case
        val ba = KftTestCase(case.id, case.sheet, BusinessCase.NEU, case.obligation, "Bestandsaufbau", STANDARD_CHAIN, "")
        val result = run(ScenarioPlanner.plan(ba))
        run.log("Bestandsaufbau: " + result.transcript.joinToString(" | "))
        if (!result.passed) throw ScenarioFailure("Bestandsaufbau fehlgeschlagen: ${result.failure}")
        val order = us.order(requireNotNull(result.orderId))
        val inventory = OrderTemplates.Inventory(
            order.path("contractNumber").stringValue(null) ?: throw ScenarioFailure("Bestandsaufbau ohne Vertragsnummer"),
            order.path("lineId").stringValue(null) ?: throw ScenarioFailure("Bestandsaufbau ohne LineID"),
        )
        run.log("Bestand: Vertrag ${inventory.contractNumber}, LineID ${inventory.lineId}")
        return inventory
    }

    /** State of one run. */
    private inner class Run(val scenario: Scenario) {
        val transcript = mutableListOf<String>()
        val case = scenario.case
        val number = "KFT" + case.id.filter { it.isDigit() }.take(8) + "-" + counter.incrementAndGet() + "-" + (System.nanoTime() % 1000)
        val desiredDate: LocalDate = LocalDate.now().plusDays(30)
        private val newLineId = "DEU.SIMLE." + number.filter { it.isDigit() }.takeLast(10)
        var inventory: OrderTemplates.Inventory? = null
        val lineId get() = inventory?.lineId ?: newLineId
        val faultNumber = "LE-ST-" + number.takeLast(10)
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
                is Action.Message -> if (step.side == Side.US) ourMessage(action, step.thirdParty) else simulatorMessage(action, step.thirdParty)
            }
        }

        // --- orders and requests --------------------------------------------------------------

        fun ourOrder() {
            val auftrag = OrderTemplates.order(case.businessCase, number, desiredDate, houseNumber, inventory, variants = TestCaseVariants.of(case), owner = TestCaseVariants.withOwner(case))
            val body = us.objectNode().put("partnerId", partnerId)
            body.set("auftrag", auftrag)
            ourOrderId = us.post("/api/v1/orders?send=true", body).path("order").path("id").longValue()
            log("open-spri → Auftrag ${case.businessCase.code} $number")
            simOrder = awaitReceived(SoapOperation.ANNEHMEN_AUFTRAG) { it.order?.changeIndicator == ChangeIndicator.STANDARD }.order
            historySeen = us.history(ourOrderId!!).size
        }

        fun simulatorOrder() {
            val (teq, payload) = simulator.sendOrder(OrderTemplates.order(case.businessCase, number, desiredDate, houseNumber, inventory, variants = TestCaseVariants.of(case), owner = TestCaseVariants.withOwner(case)))
            expectOk(teq, "Auftrag")
            simOriginal = payload
            log("Simulator → Auftrag ${case.businessCase.code} $number (TEQ OK)")
            ourOrderId = await("Auftrag $number bei open-spri") { us.findOrders("LEISTUNGSERBRINGER", number).firstOrNull()?.path("id")?.longValue() }
            simOrder = OrderData(
                number, case.businessCase, clientNumber = OrderTemplates.Parties().clientNumber, serviceNumber = OrderTemplates.Parties().serviceNumber,
                contractNumber = inventory?.contractNumber, lineId = inventory?.lineId,
            )
            awaitHistory("ORDER", null)
        }

        fun ourRequest(kind: RequestKind) {
            val id = requireNotNull(ourOrderId)
            when (kind) {
                RequestKind.STORNO -> us.post("/api/v1/orders/$id/cancel")
                RequestKind.TERMINVERSCHIEBUNG -> us.post("/api/v1/orders/$id/reschedule", us.objectNode().put("newDate", nextDate().toString()))
                RequestKind.REKLAMATION -> {
                    val body = us.objectNode()
                    body.set("auftrag", OrderTemplates.complaint(number, desiredDate, requireNotNull(inventory), faultNumber))
                    us.post("/api/v1/orders/$id/complaint", body)
                }
            }
            log("open-spri → ${kind.changeIndicator.code}")
            awaitReceived(SoapOperation.ANNEHMEN_AUFTRAG) {
                if (kind == RequestKind.REKLAMATION) it.order?.complaint == true else it.order?.changeIndicator == kind.changeIndicator
            }
            historySeen = us.history(id).size
        }

        fun simulatorRequest(kind: RequestKind) {
            val original = requireNotNull(simOriginal) { "kein ursprünglicher Auftrag des Simulators" }
            val complaint = if (kind == RequestKind.REKLAMATION) OrderTemplates.complaint(number, desiredDate, requireNotNull(inventory), faultNumber) else null
            expectOk(simulator.sendChange(original, kind, if (kind == RequestKind.TERMINVERSCHIEBUNG) nextDate() else null, complaint), kind.changeIndicator.code)
            log("Simulator → ${kind.changeIndicator.code} (TEQ OK)")
            awaitHistory("REQUEST", null)
        }

        // --- messages --------------------------------------------------------------------------

        /**
         * The order as the abgebender EKP knows it (provider change, `<…>`): his contract on the line from the
         * inventory build-up, the external order number of the aufnehmender EKP (Anlage 1).
         */
        fun handoverData() = OrderData(
            number, BusinessCase.PV, clientNumber = OrderTemplates.Parties().clientNumber,
            contractNumber = requireNotNull(inventory) { "Providerwechsel ohne Bestand" }.contractNumber, lineId = inventory!!.lineId,
        )

        fun simulatorMessage(message: Action.Message, thirdParty: Boolean = false) {
            if (thirdParty) {
                val codes = codesFor(message)
                expectOk(simulator.sendMessage(handoverData(), OrderRole.ABGEBENDER_EKP, message.type, message.changeIndicator, codes, MessageDefaults.attributes(message.type, context(message))), message.type.code)
                log("Simulator (abgebender EKP) → ${message.type.code} (${codes.joinToString()})")
                awaitHistory("MESSAGE", message.type)
                return
            }
            // KUE-LE starts with the KDLE, the view of the abgebender EKP with the AKM-PV: about the inventory contract
            val data = simOrder ?: OrderData(
                number, if (message.type == MessageType.KDLE) BusinessCase.KUE_LE else case.businessCase,
                clientNumber = OrderTemplates.Parties().clientNumber, serviceNumber = OrderTemplates.Parties().serviceNumber,
                contractNumber = inventory?.contractNumber, lineId = inventory?.lineId,
            ).also { simOrder = it }
            val codes = codesFor(message)
            val attributes = MessageDefaults.attributes(message.type, context(message))
            if (message.type == MessageType.KDLE) attributes.put("abschaltungstermin", desiredDate.toString()).put("abschaltungsgrund", "Testkündigung")
            if (simRole == OrderRole.LEISTUNGSERBRINGER && data.contractNumber == null && carriesContract(message.type)) {
                attributes.put("vertragsnummer", contract)
                simOrder = data.copy(contractNumber = contract)
            }
            expectOk(simulator.sendMessage(simOrder!!, simRole, message.type, message.changeIndicator, codes, attributes), message.type.code)
            log("Simulator → ${message.type.code} (${codes.joinToString()})${indicator(message)}")
            awaitHistory("MESSAGE", message.type)
        }

        fun ourMessage(message: Action.Message, thirdParty: Boolean = false) {
            val codes = codesFor(message)
            if (message.type == MessageType.KDLE) return ourTermination(codes)
            val id = requireNotNull(ourOrderId)
            if (message.type == MessageType.RUEM_PV) return ourProviderChangeReply(id, codes)
            if (message.repeated) {
                // nothing to do for us: the outbox repeats the message after TEQ 0991 (specification 11.2.3.2)
                val again = awaitReceived(SoapOperation.ANNEHMEN_MELDUNG) { it.message?.type == message.type }.message!!
                log("open-spri wiederholt ${message.type.code} nach TEQ 0991 (${again.codes.joinToString()})")
                historySeen = us.history(id).size
                return
            }
            if (message.overloaded) simulator.nextTeq = TeqCode.OVERLOADED
            val order = us.order(id)
            val attributes = MessageDefaults.attributes(message.type, context(message))
            if (!thirdParty && order.path("contractNumber").isNull && scenario.role == OrderRole.LEISTUNGSERBRINGER && carriesContract(message.type)) {
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
            val received = checkReceived(message.type, message.changeIndicator, codes)
            if (thirdParty) {
                // Anlage 1: to the abgebender EKP with his contract number, not ours
                val expected = inventory?.contractNumber
                if (received.contractNumber != expected) throw ScenarioFailure("${message.type.code} an den abgebenden EKP mit Vertragsnummer ${received.contractNumber}, erwartet $expected")
                log("  … an den abgebenden EKP mit Bestandsvertrag $expected")
            }
            historySeen = us.history(id).size
        }

        /** KUE-LE: our KDLE without a prior order (POST /terminations). */
        fun ourTermination(codes: List<String>) {
            val inventory = requireNotNull(inventory)
            val body = us.objectNode().put("partnerId", partnerId).put("externalOrderNumber", number)
                .put("contractNumber", inventory.contractNumber).put("clientNumber", OrderTemplates.Parties().clientNumber)
                .put("shutdownDate", desiredDate.toString()).put("reason", "Testkündigung").put("lineId", inventory.lineId)
            body.set("codes", us.codes(codes))
            ourOrderId = us.post("/api/v1/terminations", body).path("order").path("id").longValue()
            log("open-spri → KDLE (${codes.joinToString()})")
            simOrder = OrderData(number, BusinessCase.KUE_LE, clientNumber = OrderTemplates.Parties().clientNumber, contractNumber = inventory.contractNumber, lineId = inventory.lineId)
            checkReceived(MessageType.KDLE, ChangeIndicator.STANDARD, codes)
            historySeen = us.history(ourOrderId!!).size
        }

        /** RUEM-PV as abgebender EKP (POST /pv-requests/{id}/reply); negative for "neg. RUEM-PV". */
        fun ourProviderChangeReply(id: Long, codes: List<String>) {
            val consent = "neg. RUEM-PV" !in case.description
            val body = us.objectNode().put("consent", consent).put("providerName", "open-spri")
            if (!consent) body.put("answerCode", "N01").put("answerText", "Kunde hat nicht gekündigt (Testfall)")
            body.set("codes", us.codes(codes))
            us.post("/api/v1/pv-requests/$id/reply", body)
            log("open-spri → RUEM-PV (${codes.joinToString()}, Zustimmung ${if (consent) "J" else "N"})")
            checkReceived(MessageType.RUEM_PV, ChangeIndicator.STANDARD, codes)
            historySeen = us.history(id).size
        }

        /** Our message arrived at the simulator with type, change indicator and codes. */
        fun checkReceived(type: MessageType, changeIndicator: ChangeIndicator, codes: List<String>): de.openspri.domain.order.MessageInfo {
            val received = awaitReceived(SoapOperation.ANNEHMEN_MELDUNG) { it.message?.type == type }.message!!
            // the simulator as Auftraggeber learns contract number and LineID from our messages (ABM)
            simOrder = simOrder?.let { known ->
                known.copy(contractNumber = known.contractNumber ?: received.contractNumber, lineId = known.lineId ?: received.lineId)
            }
            if (received.codes != codes) throw ScenarioFailure("${type.code}: Simulator erhielt Codes ${received.codes}, erwartet $codes")
            if (received.changeIndicator != changeIndicator && changeIndicator != ChangeIndicator.REKLAMATION) {
                throw ScenarioFailure("${type.code}: Änderungskennzeichen ${received.changeIndicator.code}, erwartet ${changeIndicator.code}")
            }
            return received
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

        fun context(message: Action.Message) = MessageDefaults.Context(case.businessCase, lineId, dateFor(message), faultNumber)

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

        /** Bestandsaufbau: "NEU-Auftrag mit Standardkette (QEB, ABM, ERLM, ENTM)", codes as in S.1.1.01. */
        const val STANDARD_CHAIN = "QEB (0000), ABM (*2), ERLM (0010), ENTM (0010)"
    }
}
