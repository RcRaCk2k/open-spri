package de.openspri.simulator

import de.openspri.domain.BusinessCase
import de.openspri.domain.message.MessageType
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate

/**
 * `meldungsattribute` the scenarios send per message type, beyond what open-spri takes from the order
 * (externe Auftragsnummer, Auftraggebernummer, Vertragsnummer). Like the KFT simulator (footnote *1 of the test
 * case list) the optional attributes are filled as well, so that the receiver has to process them.
 */
object MessageDefaults {
    private val json = JsonNodeFactory.instance

    /**
     * The order data the message refers to.
     * @property faultNumber Störungsnummer of the Leistungserbringer (EST, QEB)
     */
    data class Context(
        val businessCase: BusinessCase,
        val lineId: String,
        val date: LocalDate,
        val faultNumber: String = "LE-ST-1",
        val providerName: String = "KFT-Simulator",
        val today: LocalDate = LocalDate.now(),
    )

    /**
     * Message types whose attributes require the Vertragsnummer (message.xsd). The Leistungserbringer assigns it
     * with the first of these messages; afterwards open-spri takes it from the order.
     */
    val CONTRACT_REQUIRED = setOf(
        MessageType.ABM, MessageType.ENTM, MessageType.TAM, MessageType.MTAM, MessageType.ZWM_LE, MessageType.ZWM_AG,
        MessageType.TBK_LE, MessageType.TBK_AG, MessageType.ERLM_K, MessageType.KDLE, MessageType.AKM_PV, MessageType.RUEM_PV,
        MessageType.ABM_PV, MessageType.ERLM_PV, MessageType.ENTM_PV,
    )

    fun attributes(type: MessageType, context: Context): ObjectNode {
        val node = json.objectNode()
        val diagnosis = context.businessCase == BusinessCase.DIAG_GET || context.businessCase == BusinessCase.DIAG_SET
        when (type) {
            MessageType.QEB -> if (context.businessCase == BusinessCase.EST) node.put("stoerungsNrLeistungserbringer", context.faultNumber)
            MessageType.ABM -> {
                node.putObject("verbindlicherLiefertermin").put("datum", context.date.toString())
                node.putObject("anschluss")
                    .put("a10nsp", "A10NSP-SIM").put("port", "1-1-1").put("lineId", context.lineId).put("uebergabepunktKunde", "TAE")
            }
            MessageType.ERLM -> {
                node.put("erledigungstermin", context.today.toString())
                if (context.businessCase == BusinessCase.EST) {
                    node.putObject("entstoerErgebnis").put("ergebnis", "FehlerBeseitigt").put("entstoerZeitpunkt", context.today.toString())
                }
                if (diagnosis) {
                    // test cases Z.15.1.01/Z.15.1.02: values for the requested parameters
                    val results = node.putArray("dignoseergebnis")
                    if (context.businessCase == BusinessCase.DIAG_GET) {
                        results.addObject().put("name", "BBdown").put("wert", "100000")
                        results.addObject().put("name", "BBup").put("wert", "40000")
                    } else {
                        results.addObject().put("name", "RESET").put("wert", "OK")
                    }
                }
            }
            MessageType.ENTM -> node.put("entgelttermin", context.today.toString())
            MessageType.VZM -> node.put("verzoegerungstermin", context.date.toString()).put("verzoegerungsgrund", "Testverzögerung")
            MessageType.ZWM_LE, MessageType.ZWM_AG -> node.put("nachricht", "Testnachricht")
            MessageType.TBK_LE, MessageType.TBK_AG -> node.putObject("termin").put("datum", context.date.toString()).put("zeitfenster", "1")
            MessageType.AKM_PV -> {
                node.putObject("anschluss").put("lineId", context.lineId)
                node.putObject("aufnehmenderProvider").put("providername", context.providerName)
                    .put("uebernahmedatumGeplant", context.date.toString()).put("antwortfrist", context.today.plusDays(5).toString())
            }
            MessageType.ABM_PV -> {
                node.putObject("anschluss").put("lineId", context.lineId)
                node.putObject("aufnehmenderProvider").put("providername", context.providerName).put("uebernahmedatumVerbindlich", context.date.toString())
            }
            MessageType.ERLM_PV -> node.put("erledigungstermin", context.today.toString())
            MessageType.ENTM_PV -> node.put("entgelttermin", context.today.toString())
            else -> Unit
        }
        return node
    }
}
