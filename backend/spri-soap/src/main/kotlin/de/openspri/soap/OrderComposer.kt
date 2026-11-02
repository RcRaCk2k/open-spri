package de.openspri.soap

import de.openspri.domain.BusinessCase
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate

/**
 * Builds the `auftrag` of a business case in the JSON structure of the AK XSD (the format of `POST /orders`) from a
 * few plain fields, so that a client does not need to know the XSD types (`@type`) and nesting of every business case.
 *
 * Only what is given ends up in the order; [Composed.missing] lists what the business case still needs. The result is
 * not checked here: `POST /orders` checks it against the XSD and Anlage 1 like every other order.
 */
object OrderComposer {
    private val json = JsonNodeFactory.instance

    /** Business cases with an order of the Auftraggeber (KUE-LE starts with the KDLE of the Leistungserbringer). */
    val SUPPORTED = BusinessCase.entries - BusinessCase.KUE_LE

    /** Business cases on an existing line: contract number and LineID. */
    val ON_LINE = setOf(BusinessCase.KUE_AG, BusinessCase.AEN_LMAE, BusinessCase.LAE, BusinessCase.EST, BusinessCase.DIAG_GET, BusinessCase.DIAG_SET)

    /** A person (`anrede` 1 Herr, 2 Frau, 9 keine) or, with [company], a company (`anrede` 4 Firma). */
    data class Party(
        val salutation: String? = null,
        val firstName: String? = null,
        val lastName: String? = null,
        val company: String? = null,
        val phone: String? = null,
    )

    data class Address(
        val street: String? = null,
        val houseNumber: String? = null,
        val houseNumberSuffix: String? = null,
        val postalCode: String? = null,
        val city: String? = null,
        val district: String? = null,
        val buildingPart: String? = null,
    )

    data class Fault(
        val number: String? = null,
        val description: String? = null,
        val preChecked: Boolean = false,
        val customer: Party? = null,
        val contactWanted: Boolean = false,
    )

    data class Input(
        val businessCase: BusinessCase,
        val externalOrderNumber: String? = null,
        val clientNumber: String? = null,
        val serviceNumber: String? = null,
        val desiredDate: LocalDate? = null,
        val contact: Party? = null,
        val contractNumber: String? = null,
        val lineId: String? = null,
        val preAgreementId: String? = null,
        /** the first is the product of the position, the others are further positions (AEN-LMAE: features added with `aktionscode` A) */
        val products: List<String> = emptyList(),
        val siteParty: Party? = null,
        val site: Address? = null,
        val homeId: String? = null,
        val fault: Fault? = null,
        /** DIAG: `operation` values, e.g. BBdown, BBup (GET) or RESET (SET) */
        val operations: List<String> = emptyList(),
        /** `zeitfenster` of the desired date (1 to 20, e.g. from the Replikat) */
        val timeWindow: String? = null,
    )

    data class Composed(val auftrag: ObjectNode, val missing: List<String>)

    fun compose(input: Input): Composed {
        val case = input.businessCase
        require(case in SUPPORTED) { "${case.code}: Die Kündigung durch den Leistungserbringer beginnt mit der KDLE (POST /terminations)" }
        val missing = mutableListOf<String>()
        fun need(value: String?, name: String) = value?.trim()?.takeIf { it.isNotEmpty() }.also { if (it == null) missing += name }

        val auftrag = json.objectNode()
        need(input.externalOrderNumber, "externeAuftragsnummer")?.let { auftrag.put("externeAuftragsnummer", it) }
        val auftraggeber = auftrag.putObject("auftraggeber")
        need(input.clientNumber, "auftraggeber/auftraggebernummer")?.let { auftraggeber.put("auftraggebernummer", it) }
        need(input.serviceNumber, "auftraggeber/leistungsnummer")?.let { auftraggeber.put("leistungsnummer", it) }

        val gf = auftrag.putObject("geschaeftsfall").put("@type", xsdType(case))
        if (case in ON_LINE) {
            need(input.contractNumber, "geschaeftsfall/vertragsnummer")?.let { gf.put("vertragsnummer", it) }
            need(input.lineId, "geschaeftsfall/lineId")?.let { gf.put("lineId", it) }
        }
        if (case == BusinessCase.PV) {
            need(input.lineId, "geschaeftsfall/lineId")?.let { gf.put("lineId", it) }
            need(input.preAgreementId, "geschaeftsfall/vorabstimmungId")?.let { gf.put("vorabstimmungId", it) }
        } else if (case in setOf(BusinessCase.NEU, BusinessCase.KUE_AG)) {
            input.preAgreementId?.trim()?.takeIf { it.isNotEmpty() }?.let { gf.put("vorabstimmungId", it) }
        }
        if (case !in setOf(BusinessCase.DIAG_GET, BusinessCase.DIAG_SET)) {
            val contact = input.contact
            if (contact?.lastName.isNullOrBlank()) missing += "geschaeftsfall/ansprechpartner/auftragsmanagement"
            else {
                // AnsprechpartnerType requires the phone number (XSD)
                if (contact.phone.isNullOrBlank()) missing += "geschaeftsfall/ansprechpartner/auftragsmanagement/telefonnummer"
                gf.putObject("ansprechpartner").set("auftragsmanagement", person(contact, withPhone = true))
            }
        }
        if (case == BusinessCase.EST) {
            val fault = input.fault ?: Fault()
            fault.customer?.takeIf { !it.lastName.isNullOrBlank() }?.let {
                // KontaktEndkundeType requires the first name (XSD)
                if (it.firstName.isNullOrBlank()) missing += "geschaeftsfall/kontaktEndkunde/vorname"
                gf.set("kontaktEndkunde", person(it, withPhone = true).put("kontaktaufnahmeErwuenscht", fault.contactWanted))
            }
        }
        if (case !in setOf(BusinessCase.DIAG_GET, BusinessCase.DIAG_SET)) {
            if (input.desiredDate == null) missing += "geschaeftsfall/termine/auftraggeberWunschtermin/datum"
            else gf.putObject("termine").putObject("auftraggeberWunschtermin").put("datum", input.desiredDate.toString())
                .also { w -> input.timeWindow?.trim()?.takeIf { it.isNotEmpty() }?.let { w.put("zeitfenster", it) } }
        }
        when (case) {
            BusinessCase.NEU, BusinessCase.PV -> {
                val position = gf.putObject("auftragsposition")
                products(position, input.products, missing, feature = false)
                val fttx = position.putObject("geschaeftsfallProdukt").put("@type", if (case == BusinessCase.NEU) "FTTXBereitstellungType" else "FTTXProviderwechselType")
                site(fttx, input, missing)
                input.homeId?.trim()?.takeIf { it.isNotEmpty() }?.let { fttx.putObject("vormieter").putObject("homeId").put("homeIdNummer", it) }
            }
            BusinessCase.AEN_LMAE -> products(gf.putObject("auftragsposition"), input.products, missing, feature = true)
            BusinessCase.LAE -> products(gf.putObject("auftragsposition"), input.products, missing, feature = false)
            BusinessCase.EST -> {
                val fault = input.fault ?: Fault()
                need(fault.number, "geschaeftsfall/stoerungsNrAuftraggeber")?.let { gf.put("stoerungsNrAuftraggeber", it) }
                gf.put("vorpruefungErfolgt", fault.preChecked)
                need(fault.description, "geschaeftsfall/stoerungsbeschreibung")?.let { gf.put("stoerungsbeschreibung", it) }
            }
            BusinessCase.DIAG_GET, BusinessCase.DIAG_SET -> {
                val operations = input.operations.map { it.trim() }.filter { it.isNotEmpty() }
                if (operations.isEmpty()) missing += "geschaeftsfall/auftragsposition/parameter/operation"
                else gf.putObject("auftragsposition").putObject("parameter").putArray("operation").also { a -> operations.forEach { a.add(it) } }
            }
            BusinessCase.KUE_AG, BusinessCase.KUE_LE -> Unit // KUE-AG has no position (Anlage 1 row 65)
        }
        auftrag.put("geschaeftsfallArt", case.kind)
        auftrag.put("aenderungskennzeichen", "Standard")
        return Composed(auftrag, missing)
    }

    fun xsdType(case: BusinessCase): String = when (case) {
        BusinessCase.NEU -> "BereitstellungType"
        BusinessCase.KUE_AG -> "KuendigungType"
        BusinessCase.AEN_LMAE -> "LeistungsmerkmalAenderungType"
        BusinessCase.LAE -> "LeistungsaenderungType"
        BusinessCase.PV -> "ProviderwechselType"
        BusinessCase.EST -> "EntstoerungType"
        BusinessCase.DIAG_GET -> "DiagnoseGetType"
        BusinessCase.DIAG_SET -> "DiagnoseSetType"
        BusinessCase.KUE_LE -> error("KUE-LE has no order")
    }

    private fun products(position: ObjectNode, products: List<String>, missing: MutableList<String>, feature: Boolean) {
        val names = products.map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) { missing += "geschaeftsfall/auftragsposition/produkt"; return }
        product(position, names.first())
        if (names.size > 1) {
            val positions = position.putArray("position")
            names.drop(1).forEach { name ->
                val p = positions.addObject()
                product(p, name)
                if (feature) p.put("aktionscode", "A")
            }
        }
    }

    private fun product(position: ObjectNode, name: String) {
        position.putObject("produkt").put("@type", "ProduktFTTXType").put("bezeichner", name)
    }

    private fun site(fttx: ObjectNode, input: Input, missing: MutableList<String>) {
        val party = input.siteParty
        val address = input.site ?: Address()
        val standort = fttx.putObject("standortA")
        when {
            party?.company?.isNotBlank() == true -> standort.set("firma", company(party))
            party?.lastName?.isNotBlank() == true -> standort.set("person", person(party, withPhone = true))
            else -> missing += "geschaeftsfall/auftragsposition/geschaeftsfallProdukt/standortA/person oder firma"
        }
        val street = address.street?.trim().orEmpty()
        val house = address.houseNumber?.trim().orEmpty()
        if (street.isEmpty() || house.isEmpty()) missing += "standortA/strasse"
        else standort.putObject("strasse").put("strassenname", street).put("hausnummer", house)
            .also { s -> address.houseNumberSuffix?.trim()?.takeIf { it.isNotEmpty() }?.let { s.put("hausnummernZusatz", it) } }
        address.buildingPart?.trim()?.takeIf { it.isNotEmpty() }?.let { standort.putObject("gebaeudeteil").put("gebaeudeteilName", it) }
        address.postalCode?.trim()?.takeIf { it.isNotEmpty() }?.let { standort.put("postleitzahl", it) } ?: run { missing += "standortA/postleitzahl" }
        val city = address.city?.trim().orEmpty()
        if (city.isEmpty()) missing += "standortA/ort"
        else standort.putObject("ort").put("ortsname", city).also { o -> address.district?.trim()?.takeIf { it.isNotEmpty() }?.let { o.put("ortsteil", it) } }
    }

    private fun person(p: Party, withPhone: Boolean): ObjectNode {
        val node = json.objectNode().put("anrede", p.salutation?.takeIf { it in setOf("1", "2", "9") } ?: "9")
        p.firstName?.trim()?.takeIf { it.isNotEmpty() }?.let { node.put("vorname", it) }
        node.put("nachname", p.lastName!!.trim())
        if (withPhone) p.phone?.trim()?.takeIf { it.isNotEmpty() }?.let { node.put("telefonnummer", it) }
        return node
    }

    private fun company(p: Party): ObjectNode {
        val node = json.objectNode().put("anrede", "4").put("firmenname", p.company!!.trim())
        p.phone?.trim()?.takeIf { it.isNotEmpty() }?.let { node.put("telefonnummer", it) }
        return node
    }
}
