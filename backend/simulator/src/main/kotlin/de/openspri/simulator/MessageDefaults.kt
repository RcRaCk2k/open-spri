package de.openspri.simulator

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

    /** The order data the message refers to. */
    data class Context(val lineId: String, val date: LocalDate, val today: LocalDate = LocalDate.now())

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
        when (type) {
            MessageType.ABM -> {
                node.putObject("verbindlicherLiefertermin").put("datum", context.date.toString())
                node.putObject("anschluss")
                    .put("a10nsp", "A10NSP-SIM").put("port", "1-1-1").put("lineId", context.lineId).put("uebergabepunktKunde", "TAE")
            }
            MessageType.ERLM -> node.put("erledigungstermin", context.today.toString())
            MessageType.ENTM -> node.put("entgelttermin", context.today.toString())
            MessageType.VZM -> node.put("verzoegerungstermin", context.date.toString()).put("verzoegerungsgrund", "Testverzögerung")
            MessageType.ZWM_LE, MessageType.ZWM_AG -> node.put("nachricht", "Testnachricht")
            MessageType.TBK_LE, MessageType.TBK_AG -> node.putObject("termin").put("datum", context.date.toString())
            MessageType.ERLM_PV -> node.put("erledigungstermin", context.today.toString())
            MessageType.ENTM_PV -> node.put("entgelttermin", context.today.toString())
            else -> Unit
        }
        return node
    }
}
