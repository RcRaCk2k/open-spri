package de.openspri.rules.generator

import jakarta.xml.bind.annotation.XmlElement
import jakarta.xml.bind.annotation.XmlSeeAlso
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import kotlin.system.exitProcess

/**
 * Generates `anlage1-rules.tsv` from Anlage 1 of the S/PRI specification.
 *
 * Anlage 1 lists every attribute as an indented tree of German labels with a cardinality per business case
 * and product group (orders) or per message type (messages). The generator resolves each label to the XML
 * element of the S/PRI schema by walking the generated JAXB classes:
 * 1. explicit entry in `anlage1-mapping.txt` (committed, commented), otherwise
 * 2. the child element whose normalized name matches the normalized label.
 * Rows that cannot be resolved stop the build with a list - nothing is skipped silently.
 *
 * Usage: Anlage1Generator <Anlage1.xlsx> <output.tsv>
 */
fun main(args: Array<String>) {
    val (input, output) = args.map(::File)
    val mapping = Mapping.load("/anlage1-mapping.txt")
    val conditions = Mapping.load("/anlage1-conditions.txt")
    val problems = mutableListOf<String>()
    val rules = XSSFWorkbook(input).use { workbook ->
        listOf(
            Structure("Auftragsstruktur", "ORDER", mapOf("Kontrollattribute" to Root("control", "de.telekom.spri.oss.v4.envelope.WholesaleRequestType"), "Auftrag" to Root("auftrag", "de.telekom.spri.oss.v4.order.AuftragType"))),
            Structure("Meldungsstruktur", "MESSAGE", mapOf("Kontrollattribute" to Root("control", "de.telekom.spri.oss.v4.envelope.WholesaleRequestType"), "Meldung" to Root("meldung", "de.telekom.spri.oss.v4.message.MeldungType"), "Quittung" to Root("quittung", "de.telekom.spri.oss.v4.complex.MessageTEQType"))),
        ).flatMap { structure ->
            Generator(structure, requireNotNull(workbook.getSheet(structure.sheet)) { "sheet ${structure.sheet} missing" }, mapping, conditions, problems)
                .let { generator -> generator.run().also { generator.fuzzy.forEach { println("prefix match: $it") } } }
        }
    }
    val unused = mapping.unused() + conditions.unused()
    if (unused.isNotEmpty()) problems += unused.map { "mapping/condition entry not used (label changed in Anlage 1?): $it" }
    if (problems.isNotEmpty()) {
        System.err.println("Anlage 1 could not be mapped completely (${problems.size} problems). Fix anlage1-mapping.txt:")
        problems.forEach { System.err.println("  $it") }
        exitProcess(1)
    }
    output.parentFile.mkdirs()
    output.writeText(Rule.HEADER + "\n" + rules.joinToString("\n") { it.tsv() } + "\n")
    println("Anlage 1: ${rules.size} rules written to $output")
}

data class Root(val element: String, val type: String)

data class Structure(val sheet: String, val kind: String, val roots: Map<String, Root>)

data class Rule(
    val kind: String,
    val row: Int,
    val labelPath: String,
    val xmlPath: String,
    val cardinalities: Map<String, String>,
    val condition: String = "",
) {
    fun tsv() = listOf(kind, row.toString(), labelPath, xmlPath, cardinalities.entries.joinToString(";") { "${it.key}=${it.value}" }, condition)
        .joinToString("\t")

    companion object {
        const val HEADER = "# kind\tanlage1Row\tlabelPath\txmlPath\tcardinalities (column=value; empty value = attribute not allowed)\tcondition"
    }
}

/** Committed, reviewed exceptions to the name matching: `<sheet>:<label path> = <element> | - | ignore <reason>`. */
class Mapping(private val entries: Map<String, String>) {
    private val used = mutableSetOf<String>()

    fun lookup(key: String): String? = entries[key]?.also { used += key }
    fun unused() = entries.keys - used

    companion object {
        fun load(resource: String): Mapping {
            val text = requireNotNull(Mapping::class.java.getResourceAsStream(resource)) { "$resource missing" }
                .bufferedReader().readText()
            val entries = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.associate { line ->
                val (key, value) = line.split("=", limit = 2).map { it.trim() }
                key to value
            }
            return Mapping(entries)
        }
    }
}

private class Generator(
    private val structure: Structure,
    private val sheet: Sheet,
    private val mapping: Mapping,
    private val conditions: Mapping,
    private val problems: MutableList<String>,
) {
    private val format = DataFormatter()

    /** Labels resolved by prefix matching only; printed for review. */
    val fuzzy = mutableListOf<String>()

    /** One resolved level of the label tree. */
    private data class Node(
        val labelPath: String,
        val xmlPath: List<String>,
        val classes: Set<Class<*>>,
        /** For an XSD choice ("a|b"): the classes of each alternative. */
        val alternatives: Map<String, Set<Class<*>>> = emptyMap(),
    )

    fun run(): List<Rule> {
        val header0 = sheet.getRow(0)
        val header1 = sheet.getRow(1)
        val cardinalityColumns = columns(header0, header1)
        val stack = ArrayList<Node>()
        val rules = mutableListOf<Rule>()
        for (row in sheet.drop(2)) {
            val cells = (0 until 26).map { row.getCell(it)?.let(format::formatCellValue)?.trim().orEmpty() }
            val level = cells.indexOfFirst { it.isNotEmpty() }
            if (level < 0) continue
            val label = cells[level].replace(Regex("\\s+"), " ")
            val depth = level / 2
            while (stack.size >= depth) stack.removeAt(stack.size - 1)
            val parent = stack.lastOrNull()
            val labelPath = (parent?.labelPath?.let { "$it/" } ?: "") + label
            val key = "${structure.sheet}:$labelPath"
            val node = resolve(key, label, parent) ?: run {
                problems += "$key (row ${row.rowNum + 1}): no XML element found among ${candidates(parent?.classes.orEmpty()).keys.sorted()}"
                Node(labelPath, (parent?.xmlPath ?: emptyList()) + "?", emptySet())
            }
            stack += node
            if (node.xmlPath.lastOrNull() == IGNORED) continue
            val cardinalities = cardinalityColumns.associate { (index, column) ->
                column to row.getCell(index)?.let(format::formatCellValue)?.trim().orEmpty()
            }
            // structural rows (choice) carry no own element; their cardinality applies to the alternatives
            if (node.xmlPath != parent?.xmlPath) {
                val condition = conditions.lookup(key).orEmpty()
                if (condition.isNotEmpty() && !CONDITION.matches(condition)) problems += "$key: unknown condition '$condition'"
                // an alternative of a choice is optional by itself; the choice row checks "one of them"
                val effective = if (condition == "choice") cardinalities.mapValues { (_, v) -> v.replace(Regex("^1(\\.\\.|$)"), "0$1").let { if (it == "0") "0..1" else it } } else cardinalities
                rules += Rule(structure.kind, row.rowNum + 1, labelPath, node.xmlPath.joinToString("/"), effective, condition.takeIf { it != "choice" }.orEmpty())
            }
        }
        return rules
    }

    private fun resolve(key: String, label: String, parent: Node?): Node? {
        val labelPath = (parent?.labelPath?.let { "$it/" } ?: "") + label
        if (parent == null) {
            val root = structure.roots[label] ?: return null
            return Node(labelPath, listOf(root.element), setOf(Class.forName(root.type)))
        }
        val explicit = mapping.lookup(key)
        if (explicit != null) {
            return when {
                explicit == "-" -> Node(labelPath, parent.xmlPath, parent.classes) // structural level, e.g. "choice"
                explicit.startsWith("ignore") -> Node(labelPath, parent.xmlPath + IGNORED, emptySet())
                // "a|b": XSD choice - the row's cardinality applies to "one of a, b"; children are found in both
                '|' in explicit -> {
                    val options = candidates(parent.classes)
                    val alternatives = explicit.split('|')
                    val missing = alternatives.filter { it !in options }
                    if (missing.isNotEmpty()) { problems += "$key: alternatives $missing are not children of ${parent.xmlPath.joinToString("/")}"; null }
                    else Node(
                        labelPath, parent.xmlPath + explicit, alternatives.flatMap { options.getValue(it) }.toSet(),
                        alternatives.associateWith { options.getValue(it) },
                    )
                }
                // "a/b": Anlage 1 skips an XML level (e.g. Termin/Datum = termine/auftraggeberWunschtermin/datum)
                else -> explicit.split('/').fold<String, Node?>(Node(labelPath, parent.xmlPath, parent.classes)) { node, segment ->
                    node?.let { current -> candidates(current.classes)[segment]?.let { Node(labelPath, current.xmlPath + segment, it) } }
                } ?: run { problems += "$key: mapped to '$explicit', which is not below ${parent.xmlPath.joinToString("/")}"; null }
            }
        }
        val wanted = normalize(label)
        val options = candidates(parent.classes)
        val match = options.keys.firstOrNull { normalize(it) == wanted }
            ?: options.keys.singleOrNull { normalize(it).startsWith(wanted) || wanted.startsWith(normalize(it)) }
                ?.also { fuzzy += "$key -> $it" }
            ?: return null
        return Node(labelPath, restrictAlternatives(parent, match) + match, options.getValue(match))
    }

    /** Below a choice "a|b", keep only the alternatives that actually define [child] (e.g. person, not firma). */
    private fun restrictAlternatives(parent: Node, child: String): List<String> {
        if (parent.alternatives.isEmpty()) return parent.xmlPath
        val defining = parent.alternatives.filter { (_, classes) -> child in candidates(classes) }.keys
        return parent.xmlPath.dropLast(1) + defining.joinToString("|")
    }

    /** Child elements of the given JAXB classes, including super- and subclasses (xsi:type), by element name. */
    private fun candidates(classes: Set<Class<*>>): Map<String, Set<Class<*>>> {
        val result = linkedMapOf<String, MutableSet<Class<*>>>()
        hierarchy(classes).forEach { type ->
            type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.forEach { field ->
                result.getOrPut(elementName(field)) { mutableSetOf() } += elementTypes(field)
            }
        }
        return result
    }

    private fun hierarchy(classes: Set<Class<*>>): Set<Class<*>> {
        val all = mutableSetOf<Class<*>>()
        fun add(type: Class<*>) {
            if (type == Any::class.java || !all.add(type)) return
            type.superclass?.let(::add)
            type.getAnnotation(XmlSeeAlso::class.java)?.value?.forEach { add(it.java) }
        }
        classes.forEach(::add)
        return all
    }

    private fun elementName(field: Field): String =
        field.getAnnotation(XmlElement::class.java)?.name?.takeIf { it != "##default" } ?: field.name

    private fun elementTypes(field: Field): Set<Class<*>> {
        val generic = field.genericType
        val type = if (generic is ParameterizedType) generic.actualTypeArguments.first() as? Class<*> else field.type
        return setOfNotNull(type?.takeIf { it.name.startsWith("de.telekom.spri") })
    }

    private fun columns(header0: org.apache.poi.ss.usermodel.Row, header1: org.apache.poi.ss.usermodel.Row): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        var group = ""
        for (index in 26 until header0.lastCellNum) {
            val top = header0.getCell(index)?.let(format::formatCellValue)?.trim().orEmpty()
            if (top.isNotEmpty()) group = top
            if (group in setOf("Datentyp", "Beschreibung", "Anmerkung")) continue
            if (group == "Datenformat") break
            val sub = header1.getCell(index)?.let(format::formatCellValue)?.trim().orEmpty()
            val productGroup = when {
                sub.startsWith("FTTH") -> "FTTH"
                sub == "FTTB" || sub == "FTTC" || sub == "Konnektivität" -> sub
                sub == "Alle" || sub.isEmpty() -> null
                else -> sub
            }
            result += index to (if (productGroup == null) group else "$group/$productGroup")
        }
        return result
    }

    private fun normalize(value: String): String {
        var s = value.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("[^a-z0-9]"), "")
        s = s.replace("nummer", "nr").replace("nr", "nummer")
        return s
    }

    companion object {
        const val IGNORED = "#ignored"
        val CONDITION = Regex("""choice|(only|not) GF [A-Z,-]+|only AKZ [A-Za-z,]+""")
    }
}
