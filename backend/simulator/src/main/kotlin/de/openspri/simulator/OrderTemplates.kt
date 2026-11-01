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

    /** Besteller of an order in the name of a third party (4.2, R-11); the power of attorney is set up by [ScenarioRunner.prepare]. */
    val ORDERER = Parties("B4712", "BL0816")

    /** Products and features the templates order (catalog of our products in [ScenarioRunner.prepare]). */
    val PRODUCTS = listOf("FTTH 100", "FTTH 250", "Feste IP-Adresse")

    /** Projektkenner agreed with the simulator (R-12), set up by [ScenarioRunner.prepare]. */
    const val PROJECT_ID = "PRJ-KFT-1"

    /** The existing contract an order refers to (inventory build-up, BA = X). */
    data class Inventory(val contractNumber: String, val lineId: String)

    /** Business cases with a template. KUE-LE has no order (it starts with KDLE). */
    val supported = BusinessCase.entries.toSet() - BusinessCase.KUE_LE

    /** Business cases that need an existing contract (Vertragsnummer and LineID in the order). */
    val needsInventory = setOf(BusinessCase.KUE_AG, BusinessCase.AEN_LMAE, BusinessCase.LAE, BusinessCase.EST, BusinessCase.DIAG_GET, BusinessCase.DIAG_SET)

    /**
     * @param houseNumber makes the address unique, so that test orders do not collide with R-10 (open order at the same site)
     * @param inventory contract and LineID for business cases on an existing line
     * @param variants special content of the test case (Kenner, Besteller, VorabstimmungsID, HomeID, Konnektivität)
     * @param owner Konnektivitätsauftrag with owner data (Eigentümerdaten, Variante 1 of Anlage 1 row 129)
     */
    fun order(
        case: BusinessCase,
        externalOrderNumber: String,
        desiredDate: LocalDate,
        houseNumber: Int = 1,
        inventory: Inventory? = null,
        parties: Parties = Parties(),
        variants: Set<Variant> = emptySet(),
        owner: Boolean = false,
    ): ObjectNode {
        require(case in supported) { "keine Auftragsvorlage für ${case.code}" }
        if (case in needsInventory) requireNotNull(inventory) { "${case.code} braucht einen Bestandsvertrag" }
        val auftrag = json.objectNode()
        auftrag.put("externeAuftragsnummer", externalOrderNumber)
        auftrag.putObject("auftraggeber").put("auftraggebernummer", parties.clientNumber).put("leistungsnummer", parties.serviceNumber)
        if (Variant.THIRD_PARTY in variants) {
            auftrag.putObject("besteller").put("auftraggebernummer", ORDERER.clientNumber).put("leistungsnummer", ORDERER.serviceNumber)
        }
        if (Variant.PROJECT in variants || Variant.ORDER_MARKER in variants) {
            val kenner = auftrag.putObject("kenner")
            if (Variant.PROJECT in variants) kenner.putObject("projektId").put("projektkenner", PROJECT_ID)
            // "Der Wert der Auftragsklammer darf nur einmalig verwendet werden" (Anlage 1 row 166)
            if (Variant.ORDER_MARKER in variants) {
                kenner.putObject("auftragskenner").put("auftragsklammer", externalOrderNumber.filter { it.isDigit() }.takeLast(10)).put("anzahlAuftraege", 1)
            }
        }
        val geschaeftsfall = auftrag.putObject("geschaeftsfall")
        when (case) {
            BusinessCase.NEU -> {
                geschaeftsfall.put("@type", "BereitstellungType")
                if (Variant.PRE_AGREEMENT in variants) geschaeftsfall.put("vorabstimmungId", preAgreementId(externalOrderNumber))
                contact(geschaeftsfall)
                desired(geschaeftsfall, desiredDate)
                val position = geschaeftsfall.putObject("auftragsposition")
                product(position, "FTTH 100")
                val fttx = position.putObject("geschaeftsfallProdukt").put("@type", "FTTXBereitstellungType")
                fttx.set("standortA", address(houseNumber))
                if (Variant.HOME_ID in variants) {
                    // the HomeID is part of the Vormieter (Anlage 1 rows 113-118)
                    fttx.putObject("vormieter").putObject("homeId").put("homeIdNummer", "HID" + externalOrderNumber.filter { it.isDigit() }.takeLast(12))
                }
                // KFT S.1.2.7/S.8.2.7: a valid product with the invalid feature "TEST"
                if (Variant.INVALID_FEATURE in variants) product(position.putObject("position"), "TEST")
                if (Variant.CONNECTIVITY in variants) {
                    // Konnektivitätsauftrag: product position "Konnektivität" (Anlage 1 row 154)
                    product(position.putObject("position"), "Konnektivität")
                    if (owner) {
                        val versand = fttx.putObject("standortVersand")
                        versand.putObject("person").put("anrede", "2").put("vorname", "Otto").put("nachname", "Eigentümer")
                        versand.putObject("strasse").put("strassenname", "Vermieterweg").put("hausnummer", "5")
                        versand.put("postleitzahl", "12345")
                        versand.putObject("ort").put("ortsname", "Musterstadt")
                        val montage = fttx.putObject("montageleistung")
                        montage.putObject("ansprechpartner").put("anrede", "2").put("nachname", "Eigentümer").put("telefonnummer", "0123 111222")
                        montage.put("montagehinweis", "EtFestnetz#0123111222#")
                    }
                }
            }
            BusinessCase.KUE_AG -> {
                geschaeftsfall.put("@type", "KuendigungType")
                line(geschaeftsfall, inventory!!)
                if (Variant.PRE_AGREEMENT in variants) geschaeftsfall.put("vorabstimmungId", preAgreementId(externalOrderNumber))
                contact(geschaeftsfall)
                desired(geschaeftsfall, desiredDate)
                // no Auftragsposition: Anlage 1 row 65 does not allow it for KUE-AG (optional in the XSD)
            }
            BusinessCase.AEN_LMAE -> {
                geschaeftsfall.put("@type", "LeistungsmerkmalAenderungType")
                line(geschaeftsfall, inventory!!)
                contact(geschaeftsfall)
                desired(geschaeftsfall, desiredDate)
                val position = geschaeftsfall.putObject("auftragsposition")
                product(position, "FTTH 100")
                // a feature is an additional position with action code A (add)
                val feature = position.putObject("position")
                // KFT Z.4.2.02/Z.11.2.02: the invalid feature "TEST"
                product(feature, if (Variant.INVALID_FEATURE in variants) "TEST" else "Feste IP-Adresse")
                feature.put("aktionscode", "A")
            }
            BusinessCase.LAE -> {
                geschaeftsfall.put("@type", "LeistungsaenderungType")
                line(geschaeftsfall, inventory!!)
                contact(geschaeftsfall)
                desired(geschaeftsfall, desiredDate)
                // KFT Z.5.2.02/Z.12.2.02: the product "Dummy" that is technically not possible
                product(geschaeftsfall.putObject("auftragsposition"), if (Variant.INVALID_PRODUCT in variants) "Dummy" else "FTTH 250")
            }
            BusinessCase.PV -> {
                geschaeftsfall.put("@type", "ProviderwechselType")
                geschaeftsfall.put("lineId", inventory?.lineId ?: "DEU.SIMPV.${houseNumber.toString().padStart(6, '0')}")
                geschaeftsfall.put("vorabstimmungId", preAgreementId(externalOrderNumber))
                contact(geschaeftsfall)
                desired(geschaeftsfall, desiredDate)
                val position = geschaeftsfall.putObject("auftragsposition")
                product(position, "FTTH 100")
                position.putObject("geschaeftsfallProdukt").put("@type", "FTTXProviderwechselType").set("standortA", address(houseNumber))
            }
            BusinessCase.EST -> {
                geschaeftsfall.put("@type", "EntstoerungType")
                line(geschaeftsfall, inventory!!)
                contact(geschaeftsfall)
                geschaeftsfall.putObject("kontaktEndkunde")
                    .put("anrede", "1").put("vorname", "Erika").put("nachname", "Musterfrau").put("telefonnummer", "0123 987654")
                    .put("kontaktaufnahmeErwuenscht", true)
                desired(geschaeftsfall, desiredDate)
                geschaeftsfall.put("stoerungsNrAuftraggeber", "ST-$externalOrderNumber".take(30))
                geschaeftsfall.put("vorpruefungErfolgt", true)
                geschaeftsfall.put("stoerungsbeschreibung", "Kein Sync am ONT (Testfall)")
            }
            BusinessCase.DIAG_GET, BusinessCase.DIAG_SET -> {
                geschaeftsfall.put("@type", if (case == BusinessCase.DIAG_GET) "DiagnoseGetType" else "DiagnoseSetType")
                line(geschaeftsfall, inventory!!)
                val operations = geschaeftsfall.putObject("auftragsposition").putObject("parameter").putArray("operation")
                // test cases Z.15.1.01/Z.15.1.02: GET with "BBdown", "BBup", SET with "RESET"
                if (case == BusinessCase.DIAG_GET) operations.add("BBdown").add("BBup") else operations.add("RESET")
            }
            BusinessCase.KUE_LE -> error("KUE-LE starts with KDLE")
        }
        // KFT Z.5.2.01/Z.12.2.01: "Diagnose" instead of "Änderung"; Z.4.2.01/Z.11.2.01: "Storno" instead of "Standard"
        auftrag.put("geschaeftsfallArt", if (Variant.WRONG_BUSINESS_CASE in variants) "Diagnose" else art(case))
        auftrag.put("aenderungskennzeichen", if (Variant.WRONG_CHANGE_INDICATOR in variants) "Storno" else "Standard")
        return auftrag
    }

    /** Reklamation of a completed fault repair (6.1.5): a new EST order with `reklamation`. */
    fun complaint(externalOrderNumber: String, desiredDate: LocalDate, inventory: Inventory, faultNumber: String): ObjectNode {
        val auftrag = order(BusinessCase.EST, externalOrderNumber, desiredDate, inventory = inventory)
        (auftrag.get("geschaeftsfall") as ObjectNode).putObject("reklamation")
            .put("stoerungsNrLeistungserbringer", faultNumber).put("reklamationsgrund", "Störung besteht weiter (Testfall)")
        return auftrag
    }

    /** VorabstimmungsID in the format of the WBCI (16-21 characters), derived from the order number. */
    fun preAgreementId(externalOrderNumber: String) = ("DEU.SIM.V" + externalOrderNumber.filter { it.isLetterOrDigit() }).take(21).padEnd(16, '0')

    private fun art(case: BusinessCase) = when (case) {
        BusinessCase.NEU -> "Bereitstellung"
        BusinessCase.KUE_AG, BusinessCase.KUE_LE -> "Kuendigung"
        BusinessCase.AEN_LMAE, BusinessCase.LAE -> "Aenderung"
        BusinessCase.EST -> "Entstoerung"
        BusinessCase.PV -> "Endkundenanbieterwechsel"
        BusinessCase.DIAG_GET, BusinessCase.DIAG_SET -> "Diagnose"
    }

    private fun line(geschaeftsfall: ObjectNode, inventory: Inventory) {
        geschaeftsfall.put("vertragsnummer", inventory.contractNumber).put("lineId", inventory.lineId)
    }

    private fun contact(geschaeftsfall: ObjectNode) {
        geschaeftsfall.putObject("ansprechpartner").putObject("auftragsmanagement")
            .put("anrede", "9").put("nachname", "Auftragsmanagement").put("telefonnummer", "0123 456789")
    }

    private fun desired(geschaeftsfall: ObjectNode, date: LocalDate) {
        geschaeftsfall.putObject("termine").putObject("auftraggeberWunschtermin").put("datum", date.toString())
    }

    private fun product(position: ObjectNode, identifier: String) {
        position.putObject("produkt").put("@type", "ProduktFTTXType").put("bezeichner", identifier)
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
