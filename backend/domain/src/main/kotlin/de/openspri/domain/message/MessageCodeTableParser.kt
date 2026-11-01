package de.openspri.domain.message

import java.security.MessageDigest

/**
 * Reads the message code table in two formats:
 *
 * 1. **AK export** from Confluence (Anlage 5): UTF-16 with BOM, tab-separated, quoted cells, list values
 *    separated by line breaks inside a cell; column "Page" contains `=HYPERLINK("url";"code")`.
 * 2. **Neutral CSV** (UTF-8, `;`-separated, list values separated by `|`) with the header
 *    `code;text;messageTypes;businessCases;productGroups;interfaces;validFrom`.
 */
object MessageCodeTableParser {

    private val HYPERLINK = Regex("""HYPERLINK\(".*?";\s*"([^"]+)"\)""")

    fun parse(bytes: ByteArray, source: String): MessageCodeCatalog {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val utf16 = bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
        val codes = if (utf16) parseAkExport(String(bytes, Charsets.UTF_16)) else parseNeutral(String(bytes, Charsets.UTF_8))
        require(codes.isNotEmpty()) { "Die Meldungscodetabelle '$source' enthält keine Einträge" }
        return MessageCodeCatalog(source, sha256, codes)
    }

    private fun parseAkExport(text: String): List<MessageCode> {
        val rows = Csv.parse(text.removePrefix("﻿"), '\t')
        val header = rows.first().map { it.trim() }
        fun column(name: String) = header.indexOfFirst { it.startsWith(name) }.also {
            require(it >= 0) { "Spalte '$name' fehlt in der Meldungscodetabelle (gefunden: $header)" }
        }
        val page = column("Page")
        val text_ = column("Meldungstext")
        val types = column("Meldungstyp")
        val cases = column("Geschäftsfall")
        val interfaces = column("Schnittstelle")
        val validFrom = column("Gültig ab")
        val groups = column("Produktgruppe")
        return rows.drop(1).filter { it.size > groups }.map { row ->
            val rawCode = row[page].trim()
            MessageCode(
                code = HYPERLINK.find(rawCode)?.groupValues?.get(1) ?: rawCode,
                text = row[text_].trim(),
                messageTypes = list(row[types], '\n'),
                businessCases = list(row[cases], '\n'),
                productGroups = list(row[groups], '\n'),
                interfaces = list(row[interfaces], '\n'),
                validFrom = row[validFrom].trim().ifEmpty { null },
            )
        }
    }

    private fun parseNeutral(text: String): List<MessageCode> {
        val rows = Csv.parse(text.removePrefix("﻿"), ';')
        val header = rows.first().map { it.trim() }
        require(header.take(2) == listOf("code", "text")) { "Unbekanntes Format der Meldungscodetabelle (Kopfzeile: $header)" }
        fun cell(row: List<String>, name: String) = header.indexOf(name).takeIf { it >= 0 }?.let { row.getOrNull(it) }.orEmpty()
        return rows.drop(1).filter { it.any(String::isNotBlank) }.map { row ->
            MessageCode(
                code = cell(row, "code").trim(),
                text = cell(row, "text").trim(),
                messageTypes = list(cell(row, "messageTypes"), '|'),
                businessCases = list(cell(row, "businessCases"), '|'),
                productGroups = list(cell(row, "productGroups"), '|'),
                interfaces = list(cell(row, "interfaces"), '|'),
                validFrom = cell(row, "validFrom").trim().ifEmpty { null },
            )
        }
    }

    private fun list(value: String, separator: Char): Set<String> =
        value.split(separator).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

}

/** Minimal RFC 4180 reader: quoted cells, doubled quotes, line breaks inside quotes. */
internal object Csv {
    fun parse(text: String, separator: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                !quoted && c == separator -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = mutableListOf()
                }
                else -> cell.append(if (c == '\r') '\n' else c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }
}
