package de.openspri.domain.message

import de.openspri.domain.BusinessCase
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessageCodeCatalogTest {

    /** Same structure as the AK export: UTF-16 with BOM, tabs, multi-line cells, HYPERLINK formula. */
    private val akExport = (
        "\"Page\"\t\"Meldungstext\"\t\"Meldungstyp (legacy)\"\t\"Geschäftsfall (legacy)\"\t\"Schnittstelle (legacy)\"\t\"Gültig ab (legacy)\"\t\"Prozessphase\"\t\"Produktgruppe\"\n" +
            "\"=HYPERLINK(\"\"https://example.invalid/0010\"\";\"\"0010\"\")\"\t\"Der Auftrag wurde erledigt.\"\t\"ERLM\nENTM\"\t\"NEU\nLAE\nVBL\"\t\"S/PRI\nWITA OSS\"\t\"Änderung - 20.06.2024\"\t\"Abschluss\"\t\"FTTH\nFTTB\nTAL\"\n" +
            "\"=HYPERLINK(\"\"https://example.invalid/7012\"\";\"\"7012\"\")\"\t\"Reklamation abgewiesen.\"\t\"ABBM\"\t\"REK\"\t\"S/PRI\"\t\"\"\t\"\"\t\"FTTH\"\n"
        ).toByteArray(Charsets.UTF_16)

    @Test
    fun `parses the AK export format`() {
        val catalog = MessageCodeTableParser.parse(akExport, "test")
        val erlm = catalog.find("0010")!!
        assertEquals("Der Auftrag wurde erledigt.", erlm.text)
        assertEquals(setOf("ERLM", "ENTM"), erlm.messageTypes)
        assertEquals(setOf(BusinessCase.NEU, BusinessCase.LAE), erlm.spriBusinessCases)
        assertEquals(setOf("FTTH", "FTTB", "TAL"), erlm.productGroups)
        assertEquals(64, catalog.sha256.length)
    }

    @Test
    fun `parses the neutral CSV format`() {
        val csv = "code;text;messageTypes;businessCases;productGroups;interfaces;validFrom\n0000;\"Auftrag eingegangen; ok\";QEB|ABM;NEU;FTTH|FTTB;S/PRI;\n"
        val code = MessageCodeTableParser.parse(csv.toByteArray(), "test").find("0000")!!
        assertEquals("Auftrag eingegangen; ok", code.text)
        assertEquals(setOf(MessageType.QEB, MessageType.ABM), code.spriMessageTypes)
    }

    @Test
    fun `R-13 checks message type and business case`() {
        val catalog = MessageCodeTableParser.parse(akExport, "test")
        assertEquals(CodeCheck.Allowed, catalog.check("0010", MessageType.ERLM, BusinessCase.NEU))
        assertIs<CodeCheck.Rejected>(catalog.check("0010", MessageType.ABM, BusinessCase.NEU))
        assertIs<CodeCheck.Rejected>(catalog.check("0010", MessageType.ERLM, BusinessCase.EST))
        assertIs<CodeCheck.Rejected>(catalog.check("4711", MessageType.ERLM, BusinessCase.NEU))
        // REK in the table means: EST with complaint
        assertEquals(CodeCheck.Allowed, catalog.check("7012", MessageType.ABBM, BusinessCase.EST))
    }

    @Test
    fun `local additions for codes missing in the AK table (SA-001)`() {
        val catalog = MessageCodeTableParser.parse(akExport, "test")
        assertTrue(catalog.find("1089")!!.localAddition)
        assertEquals(CodeCheck.Allowed, catalog.check("7501", MessageType.ABBM, BusinessCase.DIAG_GET))
    }

    @Test
    fun `a local extension does not restrict a code that applies to all business cases (SA-008)`() {
        val unrestricted = MessageCodeCatalog("test", "x", listOf(MessageCode("6050", "Termin", setOf("TAM"), emptySet(), emptySet(), setOf("S/PRI"))))
        assertEquals(CodeCheck.Allowed, unrestricted.check("6050", MessageType.TAM, BusinessCase.EST))
        assertEquals(CodeCheck.Allowed, unrestricted.check("6050", MessageType.MTAM, BusinessCase.NEU))
        val restricted = MessageCodeCatalog("test", "x", listOf(MessageCode("6050", "Termin", setOf("TAM"), setOf("EST"), emptySet(), setOf("S/PRI"))))
        assertEquals(CodeCheck.Allowed, restricted.check("6050", MessageType.MTAM, BusinessCase.NEU))
        assertIs<CodeCheck.Rejected>(restricted.check("6050", MessageType.TAM, BusinessCase.KUE_AG))
    }

    /** Runs only where the real export exists locally (docs/spec-original, not part of the repository). */
    @Test
    fun `real AK table can be imported`() {
        val file = Path.of(System.getProperty("user.dir"), "../../docs/spec-original/SPRI_Meldungscodetabelle.csv").normalize()
        assumeTrue(Files.exists(file), "AK message code table not available")
        val catalog = MessageCodeTableParser.parse(Files.readAllBytes(file), file.fileName.toString())
        assertTrue(catalog.codes.size >= 214, "expected at least 214 codes, got ${catalog.codes.size}")
        assertEquals("OK.", catalog.find("OK")!!.text)
        assertEquals(CodeCheck.Allowed, catalog.check("0000", MessageType.QEB, BusinessCase.NEU))
        assertEquals(CodeCheck.Allowed, catalog.check("6022", MessageType.TAM, BusinessCase.NEU))
    }
}
