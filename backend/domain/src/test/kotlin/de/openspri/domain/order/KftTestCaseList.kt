package de.openspri.domain.order

import de.openspri.domain.BusinessCase
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/** One row of the KFT test case list (Testfaelle_SPRIv4X_20250523.xlsx). */
data class KftTestCase(
    val id: String,
    val sheet: String,
    val businessCase: BusinessCase,
    val obligation: String,
    val description: String,
    val sequence: String,
    val hint: String,
) {
    /** Konnektivitätsauftrag test cases (S.1.1.25 ff.). */
    val connectivity: Boolean get() = "Konnektivität" in description
}

object KftTestCaseList {
    private const val RESOURCE = "/ak/Testfaelle_SPRIv4X.xlsx"
    val SHEETS = listOf("Testfälle Partner", "Testfälle Supplier")

    val all: List<KftTestCase> by lazy { load() }

    private fun load(): List<KftTestCase> {
        val input = requireNotNull(KftTestCaseList::class.java.getResourceAsStream(RESOURCE)) { "$RESOURCE missing (Gradle task downloadKftTestCases)" }
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
                    KftTestCase(id, name, businessCase(group, cell(8)), cell(5), cell(8), cell(9), cell(10))
                }
            }
        }
    }

    private fun businessCase(group: String, description: String): BusinessCase = when {
        group.startsWith("GET/SET") -> if ("SET" in description) BusinessCase.DIAG_SET else BusinessCase.DIAG_GET
        else -> BusinessCase.fromCode(group.substringBefore(' '))
    }
}
