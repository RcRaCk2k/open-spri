package de.openspri.replikat

import de.openspri.domain.availability.Location
import de.openspri.domain.availability.OrderKind
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * A NEU order prefilled from a Replikat record (AUFGABE 5.4, Replikat 1.7 chapter 4): the address of StandortA, the
 * HomeID (Vormieter), the product (more products as positions), the time window and, if the location needs a
 * Konnektivitätsauftrag (`auftragsart` KONNEKTIVITAET), the position "Konnektivität". Everything the Replikat cannot
 * know is listed in [Result.missing]; the result is a template for `POST /api/v1/orders`, not a valid order yet.
 */
object ReplikatOrderTemplate {
    private val json = JsonNodeFactory.instance

    data class Result(val auftrag: ObjectNode, val missing: List<String>, val hints: List<String>)

    /** S/PRI time windows are 1 to 20 (ZeitfensterType); other values of the Replikat are not taken over. */
    private val TIME_WINDOWS = (1..20).map { it.toString() }.toSet()

    fun of(location: Location, products: List<String> = emptyList(), homeId: String? = null): Result {
        val chosen = products.ifEmpty { listOf(location.products.first().product) }
        val offered = location.products.map { it.product }.toSet()
        chosen.filter { it !in offered }.takeIf { it.isNotEmpty() }?.let { throw IllegalArgumentException("produkt: am Standort nicht verfügbar: $it") }
        homeId?.let { require(it in location.homeIds) { "hid: $it wird an diesem Standort nicht bedient" } }
        val technology = location.technologies.firstOrNull { t -> location.products.any { it.product == chosen.first() && it.technology == t.technology } }
            ?: location.technologies.firstOrNull()

        val auftrag = json.objectNode()
        val geschaeftsfall = auftrag.putObject("geschaeftsfall").put("@type", "BereitstellungType")
        val termin = geschaeftsfall.putObject("termine").putObject("auftraggeberWunschtermin")
        technology?.timeWindows?.firstOrNull { it in TIME_WINDOWS }?.let { termin.put("zeitfenster", it) }
        val position = geschaeftsfall.putObject("auftragsposition")
        position.putObject("produkt").put("@type", "ProduktFTTXType").put("bezeichner", chosen.first())
        val fttx = position.putObject("geschaeftsfallProdukt").put("@type", "FTTXBereitstellungType")
        val standort = fttx.putObject("standortA")
        standort.putObject("strasse").put("strassenname", location.street).put("hausnummer", location.houseNumber)
            .also { s -> location.houseNumberSuffix?.let { s.put("hausnummernZusatz", it) } }
        location.buildingPart?.let { standort.putObject("gebaeudeteil").put("gebaeudeteilName", it) }
        standort.put("postleitzahl", location.postalCode)
        standort.putObject("ort").put("ortsname", location.city).also { o -> location.district?.let { o.put("ortsteil", it) } }
        val hid = homeId ?: location.homeIds.singleOrNull()
        hid?.let { fttx.putObject("vormieter").putObject("homeId").put("homeIdNummer", it) }
        // further products and the Konnektivität position (Replikat chapter 4: Position/Produkt/Bezeichner)
        val positions = chosen.drop(1) + listOfNotNull("Konnektivität".takeIf { technology?.orderKind == OrderKind.KONNEKTIVITAET })
        if (positions.isNotEmpty()) {
            val list = position.putArray("position")
            positions.forEach { list.addObject().putObject("produkt").put("@type", "ProduktFTTXType").put("bezeichner", it) }
        }
        auftrag.put("geschaeftsfallArt", "Bereitstellung").put("aenderungskennzeichen", "Standard")

        val missing = listOf(
            "externeAuftragsnummer", "auftraggeber/auftraggebernummer", "auftraggeber/leistungsnummer", "geschaeftsfall/ansprechpartner/auftragsmanagement",
            "geschaeftsfall/termine/auftraggeberWunschtermin/datum", "geschaeftsfall/auftragsposition/geschaeftsfallProdukt/standortA/person oder firma",
        ) + listOfNotNull("geschaeftsfall/auftragsposition/geschaeftsfallProdukt/vormieter/homeId (mehrere HomeIDs: hid angeben)".takeIf { hid == null && location.homeIds.size > 1 })
        val hints = listOfNotNull(
            "Konnektivitätsauftrag (auftragsart KONNEKTIVITAET)".takeIf { technology?.orderKind == OrderKind.KONNEKTIVITAET },
            "Eigentümerdaten erforderlich (standortVersand, Montagehinweis)".takeIf { technology?.ownerDataRequired == true },
            "Grundstückseigentümererklärung erforderlich (Anlage)".takeIf { technology?.landownerDeclarationRequired == true },
            technology?.expansionStatus?.let { "Ausbaustatus $it" },
            location.products.firstOrNull { it.product == chosen.first() }?.availableFrom?.let { "frühestmöglicher Termin $it (realisierbarAb)" },
        )
        return Result(auftrag, missing, hints)
    }
}
