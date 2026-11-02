package de.openspri.replikat

import de.openspri.domain.availability.Location
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Meta file of a Replikat (Replikat 3.1): schema version, file name, format, checksum (md5), time of provision,
 * carrier code. The specification gives an example but no schema; open-spri writes exactly these elements.
 */
data class ReplikatMeta(
    val schemaVersion: String,
    val fileName: String,
    val format: String,
    val md5: String,
    val providedAt: LocalDateTime,
    val carrierCode: String,
) {
    fun toXml(): ByteArray = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<replikat_meta>
        |  <schemaversion>${xml(schemaVersion)}</schemaversion>
        |  <dateiname>${xml(fileName)}</dateiname>
        |  <bereitstellungsformat>${xml(format)}</bereitstellungsformat>
        |  <pruefsumme algorithmus="md5">${xml(md5)}</pruefsumme>
        |  <bereitstellungszeitpunkt>${providedAt.withNano(0)}</bereitstellungszeitpunkt>
        |  <carriercode>${xml(carrierCode)}</carriercode>
        |</replikat_meta>
        |""".trimMargin().toByteArray()

    companion object {
        /** Reads a meta file; tolerant regarding namespaces and order, like the example in the specification. */
        fun parse(bytes: ByteArray): ReplikatMeta {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
            fun text(name: String) = root.getElementsByTagName(name).item(0)?.textContent?.trim()
                ?: throw IllegalArgumentException("Metadatei: $name fehlt")
            val algorithm = (root.getElementsByTagName("pruefsumme").item(0) as? org.w3c.dom.Element)?.getAttribute("algorithmus")?.lowercase()
            require(algorithm.isNullOrEmpty() || algorithm == "md5") { "Metadatei: Prüfsummenalgorithmus $algorithm, erwartet md5" }
            return ReplikatMeta(
                text("schemaversion"), text("dateiname"), text("bereitstellungsformat"), text("pruefsumme").lowercase(),
                LocalDateTime.parse(text("bereitstellungszeitpunkt").take(19)), text("carriercode"),
            )
        }

        private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

/** A Replikat as provided for download: the ZIP with the XML and its meta file. */
class ReplikatPackage(val fileName: String, val content: ByteArray, val meta: ReplikatMeta) {
    val metaFileName: String get() = ReplikatFiles.metaName(fileName)
}

/**
 * File names, packaging and checksum (Replikat 3.1):
 * - Replikat `<CarrierCode>_<YYYYMMDD>.xml`, packed as `<CarrierCode>_<YYYYMMDD>.zip` (ZIP, deflate)
 * - meta file `<CarrierCode>_<YYYYMMDD>_meta.xml`
 * - a delta Replikat of the same day gets the suffix `_delta` (`<CarrierCode>_<YYYYMMDD>_delta.zip`); the
 *   specification does not name it (SA-016)
 */
object ReplikatFiles {
    private val DAY = DateTimeFormatter.BASIC_ISO_DATE
    const val FORMAT = "zip"

    fun baseName(carrierCode: String, date: LocalDate, delta: Boolean) = "${carrierCode}_${DAY.format(date)}" + if (delta) "_delta" else ""

    fun metaName(fileName: String) = fileName.substringBeforeLast('.') + "_meta.xml"

    /** Writes, packs and checks a Replikat: the result is valid against Replikat.xsd 1.7. */
    fun pack(header: ReplikatHeader, records: Sequence<Location>, providedAt: Instant, zone: ZoneId): ReplikatPackage {
        val base = baseName(header.carrierCode, header.date, header.delta)
        val xml = ByteArrayOutputStream().also { ReplikatXml.write(it, header, records) }.toByteArray()
        val errors = ReplikatXml.validate(ByteArrayInputStream(xml))
        if (errors.isNotEmpty()) throw ReplikatInvalidException(errors)
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            out.setLevel(Deflater.DEFAULT_COMPRESSION)
            out.putNextEntry(ZipEntry("$base.xml").apply { method = ZipEntry.DEFLATED })
            out.write(xml)
            out.closeEntry()
        }
        val content = zip.toByteArray()
        val meta = ReplikatMeta(ReplikatXml.SCHEMA_VERSION, "$base.$FORMAT", FORMAT, md5(content), LocalDateTime.ofInstant(providedAt, zone), header.carrierCode)
        return ReplikatPackage("$base.$FORMAT", content, meta)
    }

    /** The XML of a downloaded Replikat: unpacks a ZIP (first .xml entry), otherwise the content itself. */
    fun xml(content: ByteArray): InputStream {
        val zip = content.size > 4 && content[0] == 'P'.code.toByte() && content[1] == 'K'.code.toByte()
        if (!zip) return ByteArrayInputStream(content)
        val input = ZipInputStream(ByteArrayInputStream(content))
        while (true) {
            val entry = input.nextEntry ?: throw IllegalArgumentException("ZIP enthält keine XML-Datei")
            if (!entry.isDirectory && entry.name.endsWith(".xml", ignoreCase = true)) return input
        }
    }

    fun md5(content: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content))
}
