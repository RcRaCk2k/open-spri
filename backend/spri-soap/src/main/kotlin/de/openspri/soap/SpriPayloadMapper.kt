package de.openspri.soap

import de.openspri.domain.BusinessCase
import de.openspri.domain.ChangeIndicator
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.MessageInfo
import de.openspri.domain.order.OrderData
import de.openspri.domain.order.OrderRules
import org.w3c.dom.Element
import java.time.LocalDate

/**
 * Translates schema-valid S/PRI payloads into the domain model. Element names are those of the XSD
 * (Anlage 3); the payload has already passed the XSD check, so missing mandatory elements cannot occur.
 */
object SpriPayloadMapper {

    private const val XSI = "http://www.w3.org/2001/XMLSchema-instance"

    /** xsi:type of `geschaeftsfall` (order) to business case. KUE-LE has no order (it starts with KDLE). */
    private val ORDER_TYPES = mapOf(
        "BereitstellungType" to BusinessCase.NEU,
        "KuendigungType" to BusinessCase.KUE_AG,
        "LeistungsmerkmalAenderungType" to BusinessCase.AEN_LMAE,
        "LeistungsaenderungType" to BusinessCase.LAE,
        "ProviderwechselType" to BusinessCase.PV,
        "EntstoerungType" to BusinessCase.EST,
        "DiagnoseGetType" to BusinessCase.DIAG_GET,
        "DiagnoseSetType" to BusinessCase.DIAG_SET,
    )

    /** [payload] is annehmenAuftragRequest. */
    fun order(payload: Element): OrderData {
        val auftrag = payload.child("auftrag")
        val geschaeftsfall = auftrag.child("geschaeftsfall")
        val type = geschaeftsfall.getAttributeNS(XSI, "type").substringAfter(':')
        val businessCase = ORDER_TYPES[type] ?: throw IllegalArgumentException("unknown business case type $type")
        val kenner = auftrag.childOrNull("kenner")
        val standortA = geschaeftsfall.descendantOrNull("standortA")
        val lineId = geschaeftsfall.text("lineId")
        val homeId = geschaeftsfall.text("homeId")
        return OrderData(
            externalOrderNumber = auftrag.child("externeAuftragsnummer").textContent.trim(),
            businessCase = businessCase,
            changeIndicator = changeIndicator(auftrag.child("aenderungskennzeichen").textContent),
            clientNumber = auftrag.child("auftraggeber").text("auftraggebernummer"),
            serviceNumber = auftrag.child("auftraggeber").text("leistungsnummer"),
            ordererClientNumber = auftrag.childOrNull("besteller")?.text("auftraggebernummer"),
            ordererServiceNumber = auftrag.childOrNull("besteller")?.text("leistungsnummer"),
            desiredDate = geschaeftsfall.descendantOrNull("auftraggeberWunschtermin")?.text("datum")?.let(::date),
            newDate = auftrag.childOrNull("terminNeu")?.textContent?.let(::date),
            contractNumber = geschaeftsfall.text("vertragsnummer"),
            lineId = lineId,
            preAgreementId = geschaeftsfall.text("vorabstimmungId"),
            homeId = homeId,
            products = geschaeftsfall.texts("bezeichner"),
            projectId = kenner?.text("projektkenner"),
            couplingId = kenner?.text("kopplungskenner"),
            bracketId = kenner?.text("auftragsklammer"),
            bracketSize = kenner?.text("anzahlAuftraege")?.toIntOrNull(),
            ontSerial = geschaeftsfall.text("seriennummerONT"),
            complaint = businessCase == BusinessCase.EST && geschaeftsfall.descendantOrNull("reklamation") != null,
            declaredKind = auftrag.childOrNull("geschaeftsfallArt")?.textContent?.trim(),
            infrastructureKey = OrderRules.infrastructureKey(
                lineId, homeId,
                standortA?.let { listOf(it.text("postleitzahl"), it.text("strassenname"), it.text("hausnummer")) }.orEmpty() +
                    listOfNotNull(standortA?.text("hausnummernZusatz")),
            ),
        )
    }

    /** [payload] is annehmenMeldungRequest. Returns null for message types that S/PRI V4.4 does not use. */
    fun message(payload: Element): MessageInfo? {
        val meldung = payload.child("meldung")
        val auftragstyp = meldung.child("auftragstyp")
        val meldungstyp = meldung.child("meldungstyp")
        val typeCode = meldungstyp.getAttributeNS(XSI, "type").substringAfter(':').removePrefix("Meldungstyp").removeSuffix("Type")
        val type = MessageType.fromCode(typeCode) ?: return null
        val attributes = meldungstyp.childOrNull("meldungsattribute")
        return MessageInfo(
            type = type,
            businessCase = BusinessCase.fromWireCode(auftragstyp.child("geschaeftsfall").textContent.trim()),
            changeIndicator = changeIndicator(auftragstyp.child("aenderungsKennzeichen").textContent),
            externalOrderNumber = attributes?.text("externeAuftragsnummer").orEmpty(),
            codes = meldungstyp.childOrNull("meldungspositionen")?.texts("meldungscode").orEmpty(),
            contractNumber = attributes?.text("vertragsnummer"),
            lineId = attributes?.text("lineId"),
        )
    }

    private fun changeIndicator(value: String) = when (value.trim()) {
        "Storno" -> ChangeIndicator.STORNO
        "Terminverschiebung" -> ChangeIndicator.TERMINVERSCHIEBUNG
        else -> ChangeIndicator.STANDARD
    }

    private fun date(value: String): LocalDate = LocalDate.parse(value.trim().take(10))

    private fun Element.childOrNull(name: String): Element? = SecureXml.childElement(this, null, name)
    private fun Element.child(name: String): Element = childOrNull(name) ?: throw IllegalArgumentException("element $name missing in $localName")
    private fun Element.descendantOrNull(name: String): Element? = getElementsByTagNameNS("*", name).item(0) as Element?
    private fun Element.text(name: String): String? = SecureXml.findText(this, name)
    private fun Element.texts(name: String): List<String> =
        getElementsByTagNameNS("*", name).let { list -> (0 until list.length).map { list.item(it).textContent.trim() } }
}
