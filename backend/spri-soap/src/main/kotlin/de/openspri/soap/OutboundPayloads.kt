package de.openspri.soap

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.InterfaceVersion
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.MessageInfo
import de.openspri.domain.order.Order
import de.openspri.domain.order.OrderData
import de.openspri.domain.order.RequestKind
import de.openspri.rules.AttributeValidator
import de.telekom.spri.oss.v4.enm.AenderungskennzeichenType
import de.telekom.spri.oss.v4.envelope.AnnehmenAuftragRequestType
import de.telekom.spri.oss.v4.envelope.AnnehmenMeldungRequestType
import de.telekom.spri.oss.v4.envelope.ObjectFactory
import de.telekom.spri.oss.v4.envelope.SignaturIdType
import de.telekom.spri.oss.v4.envelope.WholesaleRequestType
import de.telekom.spri.oss.v4.message.MeldungType
import de.telekom.spri.oss.v4.order.AuftragType
import org.w3c.dom.Element
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.GregorianCalendar
import javax.xml.datatype.DatatypeFactory

/** Our own request is not valid; [errors] name the problems (XSD, Anlage 1, JSON). REST: 400. */
class PayloadInvalidException(val errors: List<String>) : IllegalArgumentException(errors.joinToString(" | "))

/** A built request: the payload element (unsigned) and its business content. */
class BuiltPayload(val element: Element, val bytes: ByteArray)

/**
 * Builds our outbound orders and messages from JSON (REST API, M4). The control header (timestamp, version,
 * signaturId of the current key) is always set by open-spri. Every result is checked against the XSD and
 * Anlage 1 before it may be queued.
 */
class OutboundPayloads(
    private val schema: SpriSchema,
    private val keys: () -> SigningCredentials,
    private val attributes: AttributeValidator = AttributeValidator(),
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val factory = ObjectFactory()
    private val datatypes = DatatypeFactory.newInstance()
    private val json = JsonNodeFactory.instance

    /** A new order (we are Auftraggeber). [auftrag] is the XSD element `auftrag` as JSON. */
    fun order(auftrag: JsonNode): Pair<BuiltPayload, OrderData> {
        val request = AnnehmenAuftragRequestType().apply {
            control = control()
            this.auftrag = convert { SpriJson.fromJson(auftrag, AuftragType::class.java, "$.auftrag") }
        }
        val built = checked(SpriJaxb.marshal(factory.createAnnehmenAuftragRequest(request)), order = true)
        return built to SpriPayloadMapper.order(built.element)
    }

    /**
     * Storno or Terminverschiebung of our order: the original order with the new change indicator (5.1.3/5.1.4)
     * and, for a Terminverschiebung, `terminNeu`. A Reklamation (EST, 6.1.5) is a new fault order with the
     * element `reklamation`; it needs [auftrag].
     */
    fun change(originalPayload: ByteArray, kind: RequestKind, newDate: LocalDate?, auftrag: JsonNode? = null): Pair<BuiltPayload, OrderData> {
        if (kind == RequestKind.REKLAMATION) {
            val built = order(auftrag ?: throw PayloadInvalidException(listOf("Eine Reklamation braucht den Entstörauftrag mit reklamation (Feld auftrag)")))
            if (!built.second.complaint) throw PayloadInvalidException(listOf("Der Auftrag enthält kein Element reklamation (6.1.5)"))
            return built
        }
        val original = SpriJaxb.unmarshal(SecureXml.parse(originalPayload).documentElement, AnnehmenAuftragRequestType::class.java)
        original.control = control()
        original.auftrag.aenderungskennzeichen = when (kind) {
            RequestKind.STORNO -> AenderungskennzeichenType.STORNO
            RequestKind.TERMINVERSCHIEBUNG -> AenderungskennzeichenType.TERMINVERSCHIEBUNG
            RequestKind.REKLAMATION -> error("handled above")
        }
        original.auftrag.terminNeu = if (kind == RequestKind.TERMINVERSCHIEBUNG) {
            datatypes.newXMLGregorianCalendar((newDate ?: throw PayloadInvalidException(listOf("Terminverschiebung ohne newDate"))).toString())
        } else null
        val built = checked(SpriJaxb.marshal(factory.createAnnehmenAuftragRequest(original)), order = true)
        return built to SpriPayloadMapper.order(built.element)
    }

    /**
     * A message for [order]. The message header (auftragstyp), external order number and Auftraggebernummer
     * are taken from the order; [attributes] adds further `meldungsattribute` of the XSD type
     * `Meldungsattribute<Typ>Type` (e.g. `verbindlicherLiefertermin` for ABM). Code texts come from the message
     * code table unless given.
     */
    fun message(
        order: Order,
        type: MessageType,
        changeIndicator: ChangeIndicator,
        codes: List<Pair<String, String?>>,
        attributes: JsonNode?,
        catalog: MessageCodeCatalog,
    ): Pair<BuiltPayload, MessageInfo> {
        val typeName = "Meldungstyp${type.code}Type"
        // TBK-LE and TBK-AG share MeldungsattributeTBKType (message.xsd)
        val attributesType = "Meldungsattribute${if (type == MessageType.TBK_LE || type == MessageType.TBK_AG) "TBK" else type.code}Type"
        val attributeNode: ObjectNode = (attributes?.deepCopy() as? ObjectNode) ?: json.objectNode()
        attributeNode.put("@type", attributesType)
        val allowed = SpriJson.elementsOf(attributesType)
        if ("externeAuftragsnummer" in allowed && !attributeNode.has("externeAuftragsnummer")) attributeNode.put("externeAuftragsnummer", order.data.externalOrderNumber)
        if ("auftraggebernummer" in allowed && !attributeNode.has("auftraggebernummer")) order.data.clientNumber?.let { attributeNode.put("auftraggebernummer", it) }
        if ("vertragsnummer" in allowed && !attributeNode.has("vertragsnummer")) order.data.contractNumber?.let { attributeNode.put("vertragsnummer", it) }

        val meldungstyp = json.objectNode().put("@type", typeName)
        meldungstyp.set("meldungsattribute", attributeNode)
        val positions = meldungstyp.putObject("meldungspositionen").putArray("position")
        codes.forEach { (code, text) ->
            positions.addObject().put("meldungscode", code).put("meldungstext", text ?: catalog.find(code)?.text?.take(255) ?: code)
        }
        val meldung = json.objectNode()
        meldung.putObject("auftragstyp")
            .put("geschaeftsfall", order.data.businessCase.wireCode)
            .put("aenderungsKennzeichen", changeIndicator.code.takeIf { changeIndicator != ChangeIndicator.REKLAMATION } ?: "Standard")
            .put("geschaeftsfallart", art(order.data.businessCase))
        meldung.set("meldungstyp", meldungstyp)

        val request = AnnehmenMeldungRequestType().apply {
            control = control()
            this.meldung = convert { SpriJson.fromJson(meldung, MeldungType::class.java, "$.meldung") }
        }
        val built = checked(SpriJaxb.marshal(factory.createAnnehmenMeldungRequest(request)), order = false)
        return built to requireNotNull(SpriPayloadMapper.message(built.element))
    }

    /**
     * `meldungsattribute` of a RUEM-PV: the identifying fields and `anschluss` of the AKM-PV ([announcement]:
     * its `meldungsattribute` as JSON) and the answer `abgebenderProvider`.
     */
    fun providerChangeReply(announcement: JsonNode, reply: ProviderChangeReply): ObjectNode {
        val source = announcement
        val node = json.objectNode()
        listOf("vertragsnummer", "externeAuftragsnummer", "auftraggebernummer", "vorabstimmungId", "anschluss").forEach { name ->
            source.get(name)?.let { node.set(name, it.deepCopy()) }
        }
        val answer = node.putObject("abgebenderProvider")
        reply.providerName?.let { answer.put("providername", it) }
        answer.put("zustimmungProviderwechsel", if (reply.consent) "J" else "N")
        reply.answerCode?.let { answer.put("antwortcode", it) }
        reply.answerText?.let { answer.put("antworttext", it) }
        return node
    }

    private fun art(case: BusinessCase) = when (case) {
        BusinessCase.NEU -> "Bereitstellung"
        BusinessCase.KUE_AG, BusinessCase.KUE_LE -> "Kuendigung"
        BusinessCase.AEN_LMAE, BusinessCase.LAE -> "Aenderung"
        BusinessCase.EST -> "Entstoerung"
        BusinessCase.PV -> "Endkundenanbieterwechsel"
        BusinessCase.DIAG_GET, BusinessCase.DIAG_SET -> "Diagnose"
    }

    private fun control() = WholesaleRequestType().apply {
        zeitstempel = datatypes.newXMLGregorianCalendar(GregorianCalendar.from(ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS)))
        majorRelease = InterfaceVersion.CURRENT.major.toString()
        minorRelease = InterfaceVersion.CURRENT.minor.toString()
        val certificate = keys().certificate
        signaturId = SignaturIdType().apply {
            issuer = certificate.issuerX500Principal.name
            serial = certificate.serialNumber.toString()
        }
    }

    private fun <T> convert(block: () -> T): T = try {
        block()
    } catch (e: SpriJsonException) {
        throw PayloadInvalidException(listOf(e.message ?: e.path))
    }

    private fun checked(element: Element, order: Boolean): BuiltPayload {
        val bytes = SecureXml.serialize(element)
        val reparsed = SecureXml.parse(bytes).documentElement
        val xsd = schema.validate(reparsed)
        if (xsd.isNotEmpty()) throw PayloadInvalidException(xsd.map { "XSD: $it" })
        val violations = if (order) attributes.validateOrder(reparsed) else attributes.validateMessage(reparsed)
        if (violations.isNotEmpty()) throw PayloadInvalidException(violations.map { "Anlage 1: $it" })
        return BuiltPayload(reparsed, bytes)
    }
}
