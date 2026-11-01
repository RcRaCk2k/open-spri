package de.openspri.domain.order

import de.openspri.domain.ChangeIndicator.STANDARD
import de.openspri.domain.ChangeIndicator.STORNO
import de.openspri.domain.ChangeIndicator.TERMINVERSCHIEBUNG
import de.openspri.domain.message.MessageType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class KftSequenceParserTest {

    private fun msg(type: MessageType, indicator: de.openspri.domain.ChangeIndicator = STANDARD, vararg codes: String) =
        OrderEvent.Message(type, indicator, codes.toList())

    @Test
    fun `negative and positive Terminverschiebung with stray brackets (S_1_1_08)`() {
        val result = KftSequenceParser.parse(
            "QEB (0000), ABM (*2), TAM (*2), MTAM (6050), TV (neg.: VZM (0070), ABBM (*2)), (TV (pos.), ABM(*2), ERLM (0010), ENTM (0010)",
        )
        assertEquals(emptyList(), result.unparsed)
        assertEquals(
            listOf(
                msg(MessageType.QEB, STANDARD, "0000"), msg(MessageType.ABM), msg(MessageType.TAM), msg(MessageType.MTAM, STANDARD, "6050"),
                OrderEvent.Request(RequestKind.TERMINVERSCHIEBUNG), msg(MessageType.VZM, TERMINVERSCHIEBUNG, "0070"), msg(MessageType.ABBM, TERMINVERSCHIEBUNG),
                OrderEvent.Request(RequestKind.TERMINVERSCHIEBUNG), msg(MessageType.ABM, TERMINVERSCHIEBUNG),
                msg(MessageType.ERLM, STANDARD, "0010"), msg(MessageType.ENTM, STANDARD, "0010"),
            ),
            result.events,
        )
    }

    @Test
    fun `Storno answers and handover messages (Z_6_1_04)`() {
        val result = KftSequenceParser.parse("QEB (0000), <AKM-PV (0020), RUEM-PV (0021)>, ST (pos.: ERLM(0012), ENTM(0010)), <ABBM-PV (0040)>")
        assertEquals(
            listOf(
                msg(MessageType.QEB, STANDARD, "0000"), msg(MessageType.AKM_PV, STANDARD, "0020"), msg(MessageType.RUEM_PV, STANDARD, "0021"),
                OrderEvent.Request(RequestKind.STORNO), msg(MessageType.ERLM, STORNO, "0012"), msg(MessageType.ENTM, STORNO, "0010"),
                msg(MessageType.ABBM_PV, STANDARD, "0040"),
            ),
            result.events,
        )
    }

    @Test
    fun `technical acknowledgement and timeout are not business events`() {
        val result = KftSequenceParser.parse("QEB(0000), TEQ(991) wird vom AG gesendet, QEB(0000) (wird vom LE wiederholt), ABM(0000)")
        assertEquals(listOf(MessageType.QEB, MessageType.QEB, MessageType.ABM), result.events.map { (it as OrderEvent.Message).type })
        assertEquals(listOf(MessageType.QEB, MessageType.TAM, MessageType.ABBM),
            KftSequenceParser.parse("QEB (0000), TAM (6009), t>Timeout: ABBM (7015)").events.map { (it as OrderEvent.Message).type })
    }
}
