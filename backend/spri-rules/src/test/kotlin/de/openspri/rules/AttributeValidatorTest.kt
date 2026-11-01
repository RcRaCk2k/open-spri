package de.openspri.rules

import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttributeValidatorTest {

    private val rules = AttributeRules.load()
    private val validator = AttributeValidator(rules)

    private fun parse(xml: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(xml.byteInputStream()).documentElement

    private fun sample(name: String) = parse(requireNotNull(javaClass.getResource("/samples/$name")).readText())

    @Test
    fun `all 268 rows of Anlage 1 are in the rule table`() {
        assertEquals(268, rules.rules.size)
        assertTrue(rules.rules.all { it.path.isNotEmpty() })
    }

    @Test
    fun `cardinality notation`() {
        assertEquals(Cardinality(1, 1), Cardinality.parse("1"))
        assertEquals(Cardinality(0, null), Cardinality.parse("0..*"))
        assertEquals(Cardinality(1, 5), Cardinality.parse("1..5"))
        assertEquals(null, Cardinality.parse(""))
    }

    @Test
    fun `the sample NEU order complies with Anlage 1`() {
        assertEquals(emptyList(), validator.validateOrder(sample("neu-auftrag.xml")))
    }

    @Test
    fun `the sample QEB complies with Anlage 1`() {
        assertEquals(emptyList(), validator.validateMessage(sample("qeb-meldung.xml")))
    }

    private fun edited(name: String, edit: (org.w3c.dom.Document) -> Unit): Element {
        val element = sample(name)
        edit(element.ownerDocument)
        return element
    }

    private fun org.w3c.dom.Document.first(name: String) = getElementsByTagNameNS("*", name).item(0) as Element

    @Test
    fun `missing mandatory attribute is reported with its Anlage 1 row`() {
        val violations = validator.validateOrder(edited("neu-auftrag.xml") { d -> d.first("nachname").let { it.parentNode.removeChild(it) } })
        val violation = violations.single()
        assertTrue("Nachname" in violation.labelPath, violation.toString())
        assertEquals(0, violation.found)
    }

    @Test
    fun `Termin Neu is only allowed - and then mandatory - for Terminverschiebung (row 160)`() {
        val withDate = edited("neu-auftrag.xml") { d ->
            val auftrag = d.first("auftrag")
            auftrag.appendChild(d.createElementNS(null, "terminNeu")).textContent = "2026-12-15"
        }
        assertTrue(validator.validateOrder(withDate).single().labelPath.endsWith("Termin Neu"))

        val reschedule = edited("neu-auftrag.xml") { d -> d.first("aenderungskennzeichen").textContent = "Terminverschiebung" }
        assertTrue(validator.validateOrder(reschedule).single().labelPath.endsWith("Termin Neu"), "TV without terminNeu must be reported")
    }

    @Test
    fun `attributes of another business case are not allowed in a message (row 22)`() {
        val violations = validator.validateMessage(edited("qeb-meldung.xml") { d ->
            val attributes = d.first("meldungsattribute")
            attributes.appendChild(d.createElementNS(null, "stoerungsNrLeistungserbringer")).textContent = "4711"
        })
        assertTrue(violations.any { "Störungsnr" in it.labelPath }, violations.toString())
    }

    @Test
    fun `a company instead of a person satisfies the choice of StandortA`() {
        val company = edited("neu-auftrag.xml") { d ->
            val person = d.getElementsByTagNameNS("*", "standortA").item(0).firstChildElement()
            val firma = d.createElementNS(null, "firma")
            firma.appendChild(d.createElementNS(null, "anrede")).textContent = "4"
            firma.appendChild(d.createElementNS(null, "firmenname")).textContent = "Beispiel GmbH"
            person.parentNode.replaceChild(firma, person)
        }
        assertEquals(emptyList(), validator.validateOrder(company).filter { "StandortA" in it.labelPath })
    }

    private fun org.w3c.dom.Node.firstChildElement(): Element {
        var node = firstChild
        while (node !is Element) node = node.nextSibling
        return node
    }
}
