package de.openspri.simulator

import de.openspri.domain.BusinessCase
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/** One row of the KFT test case list (Testfaelle_SPRIv4X_20250523.xlsx). */
data class KftTestCase(
    val id: String,
    val sheet: String,
    val businessCase: BusinessCase,
    val obligation: String,
    val description: String,
    val sequence: String,
    val hint: String,
    /** BA = X: the test case starts with an inventory build-up (NEU with the standard chain). */
    val inventory: Boolean = false,
) {
    /** Konnektivitätsauftrag test cases (S.1.1.25 ff.). */
    val connectivity: Boolean get() = "Konnektivität" in description

    /** Blatt "Testfälle Partner": we are Auftraggeber (or abgebender EKP), the KFT simulator is Leistungserbringer. */
    val partnerSheet: Boolean get() = sheet == KftTestCaseList.PARTNER

    /** Name for test reports, e.g. `S_1_1_07_TAM_und_pos_TV` (AUFGABE 10.1). */
    val testName: String get() = (id.replace('.', '_') + "_" + description)
        .replace(Regex("[^A-Za-z0-9ÄÖÜäöüß_]+"), "_").trim('_').replace(Regex("_+"), "_")
}

object KftTestCaseList {
    const val PARTNER = "Testfälle Partner"
    const val SUPPLIER = "Testfälle Supplier"
    val SHEETS = listOf(PARTNER, SUPPLIER)

    /** Location of the xlsx: system property `openspri.kft.testCases` or environment `OPENSPRI_KFT_TEST_CASES`. */
    fun configuredPath(): Path? =
        (System.getProperty("openspri.kft.testCases") ?: System.getenv("OPENSPRI_KFT_TEST_CASES"))?.let(Path::of)

    /** The test case list at [configuredPath]; fails with a hint if it is not configured. */
    val all: List<KftTestCase> by lazy {
        val path = requireNotNull(configuredPath()) { "KFT-Testfallliste nicht konfiguriert (openspri.kft.testCases bzw. OPENSPRI_KFT_TEST_CASES)" }
        Files.newInputStream(path).use(::load)
    }

    fun load(input: InputStream): List<KftTestCase> {
        val format = DataFormatter()
        return XSSFWorkbook(input).use { workbook ->
            SHEETS.flatMap { name ->
                val sheet = requireNotNull(workbook.getSheet(name)) { "sheet '$name' missing" }
                var group = ""
                sheet.drop(1).mapNotNull { row ->
                    fun cell(i: Int) = row.getCell(i)?.let(format::formatCellValue)?.trim().orEmpty()
                    if (cell(2).isNotEmpty()) group = cell(2).lineSequence().first().trim()
                    val id = cell(3)
                    if (!id.startsWith("S.") && !id.startsWith("Z.")) return@mapNotNull null
                    KftTestCase(id, name, businessCase(group, cell(8)), cell(5), cell(8), cell(9), cell(10), inventory = cell(6).equals("X", ignoreCase = true))
                }
            }
        }
    }

    private fun businessCase(group: String, description: String): BusinessCase = when {
        group.startsWith("GET/SET") -> if ("SET" in description) BusinessCase.DIAG_SET else BusinessCase.DIAG_GET
        else -> BusinessCase.fromCode(group.substringBefore(' '))
    }
}
