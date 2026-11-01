package de.openspri.simulator

import de.openspri.domain.ChangeIndicator
import de.openspri.domain.message.MessageType
import de.openspri.domain.order.OrderEvent
import de.openspri.domain.order.RequestKind

/**
 * Translates the notation of the column "Bemerkung / Meldungstext" of the KFT test case list into events:
 *
 * | Notation                          | Meaning                                                       |
 * |-----------------------------------|---------------------------------------------------------------|
 * | `QEB (0000)`                       | message with message code(s); `*2` = any allowed code          |
 * | `TV (pos.)`, `TV (neg)`            | Terminverschiebung; the next message answers it               |
 * | `ST (pos.: ERLM (0012), …)`        | Storno; the messages inside the brackets answer it            |
 * | `<AKM-PV (0020), …>`               | messages with the third party (provider change): [Step.thirdParty] |
 * | `Reklamation`                      | complaint of a completed fault repair (EST)                   |
 * | `TEQ(991) …`, `t>Timeout:`         | technical acknowledgement / waiting time: no business event   |
 */
object KftSequenceParser {

    private val TYPES = MessageType.entries.sortedByDescending { it.code.length } // ABM-PV before ABM
    private val CODE = Regex("""\b\d{4}\b""")
    private val NOISE = listOf("wird vom AG gesendet", "(wird vom LE wiederholt)", "t>Timeout:", "2 zutreffende MC")

    /** One event; [thirdParty] = inside `<…>`, i.e. exchanged with the other provider, not with the order's partner. */
    data class Step(val event: OrderEvent, val thirdParty: Boolean)

    data class Result(val steps: List<Step>, val unparsed: List<String>) {
        val events: List<OrderEvent> get() = steps.map { it.event }
    }

    fun parse(sequence: String): Result {
        var text = sequence
        NOISE.forEach { text = text.replace(it, " ") }
        val events = mutableListOf<Step>()
        var angle = 0
        val unparsed = mutableListOf<String>()
        // Storno/TV with colon: answers until the matching closing bracket; without colon: the next message.
        val contexts = ArrayDeque<Pair<ChangeIndicator, Int>>() // indicator to bracket depth at which it ends
        var pending: ChangeIndicator? = null
        var depth = 0
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            val request = Regex("""^(ST|TV)\s*\(\s*(pos|neg)\.?\s*(:)?""").find(rest)
            when {
                request != null -> {
                    val indicator = if (request.groupValues[1] == "ST") ChangeIndicator.STORNO else ChangeIndicator.TERMINVERSCHIEBUNG
                    events += Step(OrderEvent.Request(if (indicator == ChangeIndicator.STORNO) RequestKind.STORNO else RequestKind.TERMINVERSCHIEBUNG), angle > 0)
                    depth++
                    if (request.groupValues[3] == ":") contexts.addLast(indicator to depth) else pending = indicator
                    i += request.value.length
                }
                rest.startsWith("Reklamation") -> { events += Step(OrderEvent.Request(RequestKind.REKLAMATION), angle > 0); i += "Reklamation".length }
                rest.startsWith("TEQ") -> i += skipCall(rest)
                TYPES.any { rest.startsWith(it.code) } -> {
                    val type = TYPES.first { rest.startsWith(it.code) }
                    var length = type.code.length
                    val args = Regex("""^\s*\(([^()]*)\)""").find(rest.substring(length))
                    val codes = args?.groupValues?.get(1)?.let { a -> CODE.findAll(a).map { it.value }.toList() }.orEmpty()
                    if (args != null) length += args.value.length
                    val indicator = contexts.lastOrNull()?.first ?: pending?.also { pending = null } ?: ChangeIndicator.STANDARD
                    events += Step(OrderEvent.Message(type, indicator, codes), angle > 0)
                    i += length
                }
                rest[0] == '(' -> { depth++; i++ }
                rest[0] == ')' -> {
                    if (contexts.lastOrNull()?.second == depth) contexts.removeLast()
                    if (depth > 0) depth--
                    i++
                }
                rest[0] == '<' -> { angle++; i++ }
                rest[0] == '>' -> { if (angle > 0) angle--; i++ }
                rest[0].isWhitespace() || rest[0] in ",.:" -> i++
                else -> {
                    val word = Regex("""^\S+""").find(rest)!!.value
                    unparsed += word
                    i += word.length
                }
            }
        }
        return Result(events, unparsed)
    }

    private fun skipCall(rest: String): Int = Regex("""^\w+\s*(\([^()]*\))?""").find(rest)!!.value.length
}
