package de.openspri.simulator

import com.sun.net.httpserver.HttpServer
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.exchange.ArchivedExchange
import de.openspri.domain.exchange.InboundMessage
import de.openspri.domain.exchange.InboundStore
import de.openspri.domain.exchange.OutboundArchive
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.exchange.TeqCode
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.MessageInfo
import de.openspri.domain.order.Order
import de.openspri.domain.order.OrderData
import de.openspri.domain.order.OrderLifecycle
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.RequestKind
import de.openspri.domain.partner.IpNetwork
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerDirectory
import de.openspri.domain.partner.PartnerId
import de.openspri.rules.AttributeValidator
import de.openspri.soap.AttributeMode
import de.openspri.soap.InboundProcessor
import de.openspri.soap.OutboundPayloads
import de.openspri.soap.SecureXml
import de.openspri.soap.SigningCredentials
import de.openspri.soap.SpriClient
import de.openspri.soap.SpriPayloadMapper
import de.openspri.soap.SpriSchema
import de.openspri.soap.TeqResponder
import de.openspri.soap.WsSecurityVerifier
import org.w3c.dom.Element
import tools.jackson.databind.JsonNode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** Something the simulator received from us: an order (with [order]) or a message (with [message]). */
class Received(
    val operation: SoapOperation,
    val externalOrderNumber: String?,
    val payload: ByteArray,
    val order: OrderData?,
    val message: MessageInfo?,
    val at: Instant,
) {
    val element: Element get() = SecureXml.parse(payload).documentElement
}

/**
 * The counterpart of our instance in a test case (AUFGABE 10.3): a S/PRI participant with its own signing key
 * and endpoint. Inbound requests run through the same strict [InboundProcessor] as in open-spri (XSD, Anlage 1
 * in enforce mode, signature, version) and are answered with a signed TEQ, like the KFT simulator. Outbound
 * requests are built with [OutboundPayloads] and checked the same way before they are sent.
 *
 * @param us our instance as seen by the simulator: its signing certificate and S/PRI endpoint
 */
class SimulatedPartner(
    val name: String,
    val credentials: SigningCredentials,
    private val schema: SpriSchema,
    private val us: () -> Pair<X509Certificate, URI>,
    private val catalog: () -> MessageCodeCatalog,
) : AutoCloseable {
    private val received = CopyOnWriteArrayList<Received>()

    /** One-off TEQ for the next request instead of the checks, e.g. 0991 "überlastet" (TF S.8.2.10). */
    @Volatile var nextTeq: TeqCode? = null

    private val directory = object : PartnerDirectory {
        override fun findBySourceAddress(address: InetAddress) = listOf(ourPartner())
        override fun findById(id: PartnerId) = ourPartner().takeIf { it.id == id }
    }
    private val store = object : InboundStore {
        override fun storeAccepted(exchange: ArchivedExchange, message: InboundMessage): Long {
            val element = SecureXml.parse(message.payload).documentElement
            received += Received(
                message.operation, message.externalOrderNumber, message.payload,
                if (message.operation == SoapOperation.ANNEHMEN_AUFTRAG) SpriPayloadMapper.order(element) else null,
                if (message.operation == SoapOperation.ANNEHMEN_MELDUNG) SpriPayloadMapper.message(element) else null,
                Instant.now(),
            )
            return received.size.toLong()
        }

        override fun storeRejected(exchange: ArchivedExchange): Long {
            rejections += "${exchange.teqCode}: ${exchange.rejectionReason}"
            return -1
        }
    }

    /** Requests of our instance the simulator rejected with a negative TEQ (should stay empty). */
    val rejections = CopyOnWriteArrayList<String>()

    private val verifier = WsSecurityVerifier()
    private val responder = TeqResponder(credentials)
    private val processor = InboundProcessor(
        directory, store, schema, verifier, responder, attributes = AttributeValidator(), attributeMode = AttributeMode.ENFORCE,
    )
    private val client = SpriClient(credentials, schema, verifier, object : OutboundArchive { override fun store(exchange: ArchivedExchange) = 0L })
    val payloads = OutboundPayloads(schema, { credentials })
    private var server: HttpServer? = null

    /** Our instance as partner of the simulator; allowed from every address (the simulator runs in tests and on the dev stack). */
    private fun ourPartner(): Partner = Partner(
        PartnerId(1), "open-spri", "OPENSPRI", listOf(us().first),
        listOf(IpNetwork.parse("0.0.0.0/0"), IpNetwork.parse("::/0")), endpointUrl = us().second.toString(),
    )

    /** Starts the S/PRI endpoint on [port] (0 = free port) and returns its URL. */
    fun start(port: Int = 0, host: String = "127.0.0.1"): URI {
        val http = HttpServer.create(InetSocketAddress(host, port), 0)
        register(http)
        http.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        http.start()
        server = http
        return URI("http://$host:${http.address.port}$PATH")
    }

    /** Adds the S/PRI endpoint ([PATH]) to an existing server, e.g. next to the control API of [SimulatorMain]. */
    fun register(http: HttpServer) {
        http.createContext(PATH) { exchange ->
            val body = exchange.requestBody.readAllBytes()
            val action = exchange.requestHeaders.getFirst("SOAPAction")
            val forced = nextTeq?.also { nextTeq = null }
            val result = if (forced != null) {
                val operation = if (action?.contains("annehmenAuftrag") == true) SoapOperation.ANNEHMEN_AUFTRAG else SoapOperation.ANNEHMEN_MELDUNG
                InboundProcessor.Result.Answered(forced, responder.respond(operation, forced, null, null))
            } else {
                processor.process(body, exchange.remoteAddress.address, action)
            }
            when (result) {
                InboundProcessor.Result.Forbidden -> exchange.sendResponseHeaders(403, -1)
                is InboundProcessor.Result.Answered -> {
                    val response = result.response
                    exchange.responseHeaders.add("Content-Type", "text/xml; charset=utf-8")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
            }
            exchange.close()
        }
    }

    fun received(): List<Received> = received.toList()

    // --- as Auftraggeber -------------------------------------------------------------------------

    /** Sends an order (XSD element `auftrag` as JSON). Returns the TEQ and the sent payload for later changes. */
    fun sendOrder(auftrag: JsonNode): Pair<TeqCode?, ByteArray> {
        val (built, data) = payloads.order(auftrag)
        return send(SoapOperation.ANNEHMEN_AUFTRAG, built.element, data.externalOrderNumber) to built.bytes
    }

    /** Storno or Terminverschiebung of an order sent before ([original] = its payload). */
    fun sendChange(original: ByteArray, kind: RequestKind, newDate: LocalDate?, auftrag: JsonNode? = null): TeqCode? {
        val (built, data) = payloads.change(original, kind, newDate, auftrag)
        return send(SoapOperation.ANNEHMEN_AUFTRAG, built.element, data.externalOrderNumber)
    }

    // --- messages (as Leistungserbringer, as Auftraggeber or as abgebender EKP) ---------------------

    /** A message about [data] (the order as the simulator knows it). [role] is the simulator's role. */
    fun sendMessage(
        data: OrderData,
        role: OrderRole,
        type: MessageType,
        changeIndicator: ChangeIndicator,
        codes: List<String>,
        attributes: JsonNode?,
    ): TeqCode? {
        val order = Order(null, PartnerId(1), role, data, OrderLifecycle.announcedByLeistungserbringer(), Instant.EPOCH, Instant.EPOCH)
        val (built, _) = payloads.message(order, type, changeIndicator, codes.map { it to null }, attributes, catalog())
        return send(SoapOperation.ANNEHMEN_MELDUNG, built.element, data.externalOrderNumber)
    }

    private fun send(operation: SoapOperation, payload: Element, externalOrderNumber: String?): TeqCode? {
        val (_, endpoint) = us()
        return when (val outcome = client.send(ourPartner(), endpoint, operation, payload, externalOrderNumber)) {
            is SpriClient.Outcome.Acknowledged -> outcome.teq
            is SpriClient.Outcome.NotAcknowledged -> throw IllegalStateException("$name: keine TEQ von open-spri: ${outcome.reason}")
            is SpriClient.Outcome.NotSent -> throw IllegalStateException("$name: Anlage 1 verletzt: ${outcome.violations}")
        }
    }

    override fun close() {
        server?.stop(0)
    }

    companion object {
        const val PATH = "/spri/v4"
    }
}
