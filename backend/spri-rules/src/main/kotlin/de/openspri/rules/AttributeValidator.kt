package de.openspri.rules

import org.w3c.dom.Element

/** A violation of Anlage 1; [row] points to the source row. */
data class AttributeViolation(val row: Int, val labelPath: String, val xmlPath: String, val expected: String, val found: Int) {
    override fun toString() =
        "$labelPath ($xmlPath): ${if (expected == "-") "nicht zulässig" else "erwartet $expected"}, gefunden $found (Anlage 1, Zeile $row)"
}

/**
 * Checks orders and messages against Anlage 1 (specification 10.4, checks 4 and 5): which attributes must,
 * may or must not be present for the combination of business case and product group (orders) or for the
 * message type (messages). Type and value checks are done by the XSD beforehand.
 *
 * Counting rule: for every occurrence of the parent element, the number of child elements is compared with
 * the cardinality. Rules whose parent does not occur are not evaluated (an optional structure that is absent
 * cannot have missing children).
 */
class AttributeValidator(private val rules: AttributeRules = AttributeRules.load()) {

    private val orderRules = rules.of(RuleKind.ORDER)
    private val messageRules = rules.of(RuleKind.MESSAGE)

    /** [payload] is annehmenAuftragRequest. The column is derived from the order itself. */
    fun validateOrder(payload: Element): List<AttributeViolation> {
        val columns = OrderColumns.of(payload) ?: return emptyList()
        val auftrag = payload.child("auftrag")
        val context = mapOf(
            Condition.Field.GF to columns.first().substringBefore('/'),
            Condition.Field.AKZ to auftrag?.child("aenderungskennzeichen")?.textContent?.trim(),
        )
        return check(orderRules, payload, columns, context)
    }

    /** [payload] is annehmenMeldungRequest; the column is the message type (QEB, ABM, …). */
    fun validateMessage(payload: Element): List<AttributeViolation> {
        val meldung = payload.child("meldung")
        val type = meldung?.child("meldungstyp")?.xsiType()?.removePrefix("Meldungstyp")?.removeSuffix("Type") ?: return emptyList()
        val auftragstyp = meldung.child("auftragstyp")
        val context = mapOf(
            Condition.Field.GF to auftragstyp?.child("geschaeftsfall")?.textContent?.trim(),
            Condition.Field.AKZ to auftragstyp?.child("aenderungsKennzeichen")?.textContent?.trim(),
        )
        return check(messageRules, payload, listOf(type), context)
    }

    /**
     * With several candidate columns (product group unknown) a violation is only reported if the order
     * violates the rule in every candidate column - we never reject what Anlage 1 might allow.
     */
    private fun check(
        rules: List<AttributeRule>,
        payload: Element,
        columns: List<String>,
        context: Map<Condition.Field, String?>,
    ): List<AttributeViolation> =
        rules.mapNotNull { rule ->
            val conditionMet = rule.condition?.fulfilled(context) ?: true
            val applicable = columns.filter { it in rule.cardinalities }
            if (applicable.isEmpty()) return@mapNotNull null
            val parents = rule.path.dropLast(1).fold(listOf(payload)) { elements, segment -> elements.flatMap { it.children(segment) } }
            parents.firstNotNullOfOrNull { parent ->
                val count = parent.children(rule.path.last()).size
                val violated = applicable.all { column ->
                    val cardinality = if (conditionMet) rule.cardinalities.getValue(column) else null
                    if (cardinality == null) count > 0 else !cardinality.accepts(count)
                }
                if (!violated) null else AttributeViolation(
                    row = rule.row,
                    labelPath = rule.labelPath,
                    xmlPath = rule.path.joinToString("/") { it.joinToString("|") },
                    expected = applicable.map { (if (conditionMet) rule.cardinalities.getValue(it) else null)?.toString() ?: "-" }
                        .distinct().joinToString(" / ") + (rule.condition?.let { if (conditionMet) "" else " (Bedingung: $it)" } ?: ""),
                    found = count,
                )
            }
        }
}

/** Columns of Anlage 1 that apply to an order: business case and product group. */
internal object OrderColumns {
    private val BUSINESS_CASES = mapOf(
        "BereitstellungType" to "NEU",
        "KuendigungType" to "KUE-AG",
        "LeistungsmerkmalAenderungType" to "AEN-LMAE",
        "LeistungsaenderungType" to "LAE",
        "EntstoerungType" to "EST",
        "ProviderwechselType" to "PV",
        "DiagnoseGetType" to "GET",
        "DiagnoseSetType" to "SET",
    )
    private val PRODUCT_GROUPS = listOf("FTTH", "FTTB", "FTTC")

    fun of(payload: Element): List<String>? {
        val auftrag = payload.child("auftrag") ?: return null
        val case = BUSINESS_CASES[auftrag.child("geschaeftsfall")?.xsiType()] ?: return null
        val products = auftrag.getElementsByTagNameNS("*", "bezeichner").let { list -> (0 until list.length).map { list.item(it).textContent.trim() } }
        if (case == "NEU" && products.any { it.equals("Konnektivität", ignoreCase = true) }) return listOf("NEU/Konnektivität")
        // KOAX and HFC use the FTTH column (Anlage 1: "FTTH, KOAX, HFC")
        val group = products.firstNotNullOfOrNull { product ->
            when {
                product.startsWith("KOAX", ignoreCase = true) || product.startsWith("HFC", ignoreCase = true) -> "FTTH"
                else -> PRODUCT_GROUPS.firstOrNull { product.startsWith(it, ignoreCase = true) }
            }
        }
        return if (group != null) listOf("$case/$group") else PRODUCT_GROUPS.map { "$case/$it" }
    }
}

private const val XSI = "http://www.w3.org/2001/XMLSchema-instance"

internal fun Element.child(name: String): Element? = children(setOf(name)).firstOrNull()

internal fun Element.children(names: Set<String>): List<Element> {
    val result = mutableListOf<Element>()
    var node = firstChild
    while (node != null) {
        if (node is Element && (node.localName ?: node.nodeName) in names) result += node
        node = node.nextSibling
    }
    return result
}

internal fun Element.xsiType(): String? = getAttributeNS(XSI, "type").takeIf { it.isNotEmpty() }?.substringAfter(':')
