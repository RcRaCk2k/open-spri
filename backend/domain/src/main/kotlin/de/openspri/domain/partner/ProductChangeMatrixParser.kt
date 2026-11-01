package de.openspri.domain.partner

import de.openspri.domain.BusinessCase

/**
 * Reads the product change matrix in the layout of Anlage 6 (CSV export of the table): a header row with the
 * target products (first cell empty), then one row per current product ("Bestand") with the business case in
 * each cell (`LAE`, `AEN-LMAE`, empty = no change). Rows before the header and after the matrix (title,
 * legend, notes) are ignored.
 *
 * Anlage 6 names the targets technology-neutral (`FTTx 50`) because a change is only possible within one base
 * technology (note in Anlage 6). `FTTx` in a target is therefore replaced by the technology of the current
 * product (`FTTB 25` → `FTTx 50` = `FTTB 50`), see docs/spec-abweichungen.md SA-013.
 */
object ProductChangeMatrixParser {

    private val CASES = setOf(BusinessCase.LAE, BusinessCase.AEN_LMAE)

    fun parse(csv: String): List<ProductChange> {
        val rows = csv.lineSequence().map(::split).toList()
        val headerIndex = rows.indexOfFirst { row -> row.firstOrNull().isNullOrBlank() && row.drop(1).count { it.isNotBlank() } >= 2 }
        require(headerIndex >= 0) { "Keine Kopfzeile mit Zielprodukten gefunden (erste Zelle leer, danach die Produkte)" }
        val targets = rows[headerIndex].drop(1).map { it.trim() }
        val result = mutableListOf<ProductChange>()
        for ((offset, row) in rows.drop(headerIndex + 1).withIndex()) {
            val from = row.firstOrNull()?.trim().orEmpty()
            val cells = row.drop(1).map { it.trim() }
            if (from.isEmpty() || cells.none { cell -> CASES.any { it.code == cell } }) break
            val technology = from.substringBefore(' ')
            cells.forEachIndexed { i, cell ->
                if (cell.isEmpty()) return@forEachIndexed
                val case = CASES.firstOrNull { it.code == cell }
                    ?: throw IllegalArgumentException("Zeile ${headerIndex + offset + 2}: '$cell' ist kein Geschäftsfall (erlaubt: LAE, AEN-LMAE)")
                val target = targets.getOrNull(i)?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("Zeile ${headerIndex + offset + 2}: Spalte ${i + 2} hat kein Zielprodukt")
                result += ProductChange(null, from, target.replace(Regex("^FTTx\\b"), technology), case)
            }
        }
        require(result.isNotEmpty()) { "Die Matrix enthält keine Wechsel" }
        return result
    }

    /** One CSV line with `,` or `;` as separator and `"` quoting. */
    private fun split(line: String): List<String> {
        val separator = if (line.count { it == ';' } > line.count { it == ',' }) ';' else ','
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == separator && !quoted -> { cells += cell.toString(); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        cells += cell.toString()
        return cells
    }
}
