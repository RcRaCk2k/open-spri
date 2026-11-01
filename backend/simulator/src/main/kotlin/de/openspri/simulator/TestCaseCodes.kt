package de.openspri.simulator

import de.openspri.domain.message.MessageCode
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.OrderEvent

/**
 * A message code table derived from the test case list: every code the list names explicitly, for the message
 * types it is used with. The scenarios run against it where the AK table (Confluence export, not part of the
 * repository) is not available, e.g. in CI. The codes are not restricted to business cases, because the list
 * often only says `*2` (e.g. ERLM of a diagnosis); the check per business case (R-13) needs the AK table.
 */
object TestCaseCodes {

    fun catalog(cases: List<KftTestCase>): MessageCodeCatalog {
        val types = mutableMapOf<String, MutableSet<String>>()
        cases.forEach { case ->
            // codes the hints name for the ABBM of the Leistungserbringer (e.g. 1010 Auftragsdublette, 1312 Freitext)
            Regex("""\b(\d{4})\b""").findAll(case.hint).map { it.value }.filter { "Meldungscode" in case.hint || "MC" in case.hint || "z.B." in case.hint }
                .forEach { code ->
                    types.getOrPut(code) { sortedSetOf() } += MessageType.ABBM.code
                }
            KftSequenceParser.parse(case.sequence).events.filterIsInstance<OrderEvent.Message>().forEach { message ->
                message.codes.forEach { code ->
                    types.getOrPut(code) { sortedSetOf() } += message.type.code
                }
            }
        }
        val codes = types.keys.sorted().map { code ->
            MessageCode(code, "Testtext $code (aus der KFT-Testfallliste abgeleitet)", types.getValue(code), emptySet(), setOf("FTTH", "FTTB", "FTTC"), setOf("S/PRI"))
        }
        return MessageCodeCatalog("KFT-Testfallliste (abgeleitet)", "derived", codes)
    }
}
