package de.openspri.simulator

import de.openspri.domain.BusinessCase
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate

/**
 * Orders of the test cases as JSON in the structure of the AK XSD (the format of `POST /api/v1/orders`).
 * All personal data is fictitious (principle 13).
 */
object OrderTemplates {
    private val json = JsonNodeFactory.instance

    data class Parties(val clientNumber: String = "AG4711", val serviceNumber: String = "LN0815")

    /** Business cases with a template; the others follow with the scenarios of their test cases. */
    val supported = setOf(BusinessCase.NEU)

    /** [houseNumber] makes the address unique, so that test orders do not collide with R-10 (open order at the same site). */
    fun order(case: BusinessCase, externalOrderNumber: String, desiredDate: LocalDate, houseNumber: Int = 1, parties: Parties = Parties()): ObjectNode {
        require(case in supported) { "keine Auftragsvorlage für ${case.code}" }
        val auftrag = json.objectNode()
        auftrag.put("externeAuftragsnummer", externalOrderNumber)
        auftrag.putObject("auftraggeber").put("auftraggebernummer", parties.clientNumber).put("leistungsnummer", parties.serviceNumber)
        val geschaeftsfall = auftrag.putObject("geschaeftsfall").put("@type", "BereitstellungType")
        geschaeftsfall.putObject("ansprechpartner").putObject("auftragsmanagement")
            .put("anrede", "9").put("nachname", "Auftragsmanagement").put("telefonnummer", "0123 456789")
        geschaeftsfall.putObject("termine").putObject("auftraggeberWunschtermin").put("datum", desiredDate.toString())
        val position = geschaeftsfall.putObject("auftragsposition")
        position.putObject("produkt").put("@type", "ProduktFTTXType").put("bezeichner", "FTTH 100")
        val product = position.putObject("geschaeftsfallProdukt").put("@type", "FTTXBereitstellungType")
        product.set("standortA", address(houseNumber))
        auftrag.put("geschaeftsfallArt", "Bereitstellung")
        auftrag.put("aenderungskennzeichen", "Standard")
        return auftrag
    }

    private fun address(houseNumber: Int): ObjectNode {
        val standort = json.objectNode()
        standort.putObject("person").put("anrede", "1").put("vorname", "Erika").put("nachname", "Musterfrau")
        standort.putObject("strasse").put("strassenname", "Musterstraße").put("hausnummer", houseNumber.toString())
        standort.put("postleitzahl", "12345")
        standort.putObject("ort").put("ortsname", "Musterstadt")
        return standort
    }
}
