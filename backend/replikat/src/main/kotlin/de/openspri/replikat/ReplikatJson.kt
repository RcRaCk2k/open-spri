package de.openspri.replikat

import de.openspri.domain.availability.ChangeMark
import de.openspri.domain.availability.Location
import de.openspri.domain.availability.OrderKind
import de.openspri.domain.availability.ProductAvailability
import de.openspri.domain.availability.TechnologyAvailability
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * A data record as JSON with the element names of Replikat.xsd 1.7 (REST API and storage; AUFGABE 5.4: the online
 * query answers "1:1 mit den Feldern eines Replikat-Datensatzes"). Lists are arrays: `pl` holds the products
 * (`pl/p`), `tl` the technologies (`tl/t`), `hidl` and `zl` the HomeIDs and time windows.
 */
object ReplikatJson {
    private val json = JsonNodeFactory.instance
    private val mapper = JsonMapper.builder().build()

    private val LOCATION = setOf("ak", "aid", "geo", "plz", "o", "ot", "s", "h", "hz", "gebaeudeteil", "anzNutzungseinheiten", "anzGeschaeftseinheiten", "pl", "tl")
    private val PRODUCT = setOf("produkt", "technologie", "technologieCpe", "netzabschlussKunde", "erwarteteBandbreite", "realisierbarkeitProdukt", "bereitstellungszeit", "hausanschlussprodukt")
    private val TECHNOLOGY = setOf(
        "technologie", "kont", "ane", "hidl", "ausbaustatus", "ausbauzeitraum", "eigentuemerdaten", "geeErforderlich", "geeVorhanden", "foerdergebiet",
        "auftragsart", "negativeAusbauentscheidung", "zl", "netzverteilerZuordnung", "migration", "ausbauvariante", "locationNetzabschlussKunde",
        "schwellwertvermarktung", "vermarktungszeitraum", "sollwertSchwellwertvermarktung", "istwertSchwellwertvermarktung", "ausbauPhase",
    )

    /** Optional elements a release may omit for a partner (bilateral agreement, AUFGABE 5.4). */
    val OPTIONAL_FIELDS: Set<String> = (LOCATION - setOf("plz", "o", "s", "h", "pl", "ak")) + (PRODUCT - "produkt") + (TECHNOLOGY - "technologie")

    fun toJson(location: Location): ObjectNode {
        val node = json.objectNode()
        location.change?.let { node.put("ak", it.name) }
        location.addressId?.let { node.put("aid", it) }
        if (location.longitude != null && location.latitude != null) node.putObject("geo").put("long", location.longitude).put("lat", location.latitude)
        node.put("plz", location.postalCode).put("o", location.city)
        location.district?.let { node.put("ot", it) }
        node.put("s", location.street).put("h", location.houseNumber)
        location.houseNumberSuffix?.let { node.put("hz", it) }
        location.buildingPart?.let { node.put("gebaeudeteil", it) }
        location.units?.let { node.put("anzNutzungseinheiten", it) }
        location.businessUnits?.let { node.put("anzGeschaeftseinheiten", it) }
        val products = node.putArray("pl")
        location.products.forEach { p ->
            val product = products.addObject().put("produkt", p.product)
            p.technology?.let { product.put("technologie", it) }
            p.cpeTechnology?.let { product.put("technologieCpe", it) }
            p.networkTermination?.let { product.put("netzabschlussKunde", it) }
            p.downstream?.let { down -> product.putObject("erwarteteBandbreite").put("downBB", down).also { bb -> p.upstream?.let { bb.put("upBB", it) } } }
            if (p.availableFrom != null || p.availableUntil != null) {
                val period = product.putObject("realisierbarkeitProdukt")
                p.availableFrom?.let { period.put("realisierbarAb", it.toString()) }
                p.availableUntil?.let { period.put("realisierbarBis", it.toString()) }
            }
            p.provisioningDays?.let { product.put("bereitstellungszeit", it) }
            p.houseConnectionProduct?.let { product.put("hausanschlussprodukt", it) }
        }
        if (location.technologies.isNotEmpty()) {
            val technologies = node.putArray("tl")
            location.technologies.forEach { t ->
                val technology = technologies.addObject().put("technologie", t.technology)
                t.quota?.let { technology.put("kont", it) }
                t.lastMileOperator?.let { technology.put("ane", it) }
                if (t.homeIds.isNotEmpty()) technology.putArray("hidl").also { a -> t.homeIds.forEach(a::add) }
                t.expansionStatus?.let { technology.put("ausbaustatus", it) }
                period(technology, "ausbauzeitraum", t.expansionStart, t.expansionEnd)
                t.ownerDataRequired?.let { technology.put("eigentuemerdaten", it) }
                t.landownerDeclarationRequired?.let { technology.put("geeErforderlich", it) }
                t.landownerDeclarationPresent?.let { technology.put("geeVorhanden", it) }
                t.fundedArea?.let { technology.put("foerdergebiet", it) }
                t.orderKind?.let { technology.put("auftragsart", it.name) }
                t.negativeDecision?.let { technology.put("negativeAusbauentscheidung", it) }
                if (t.timeWindows.isNotEmpty()) technology.putArray("zl").also { a -> t.timeWindows.forEach(a::add) }
                t.distributionPoint?.let { technology.put("netzverteilerZuordnung", it) }
                t.migration?.let { technology.put("migration", it) }
                t.expansionVariant?.let { technology.put("ausbauvariante", it) }
                t.terminationLocation?.let { technology.put("locationNetzabschlussKunde", it) }
                t.thresholdMarketing?.let { technology.put("schwellwertvermarktung", it) }
                period(technology, "vermarktungszeitraum", t.marketingStart, t.marketingEnd)
                t.thresholdTarget?.let { technology.put("sollwertSchwellwertvermarktung", it) }
                t.thresholdActual?.let { technology.put("istwertSchwellwertvermarktung", it) }
                t.expansionPhase?.let { technology.put("ausbauPhase", it) }
            }
        }
        return node
    }

    /** Reads a record; unknown fields and wrong types are errors with JSON path (like the order JSON). */
    fun fromJson(node: JsonNode, path: String = "$"): Location {
        fields(node, LOCATION, path)
        val geo = node.get("geo")
        return Location(
            change = text(node, "ak", path)?.let { value -> ChangeMark.entries.firstOrNull { it.name == value } ?: fail("$path.ak", "erlaubt sind ADD, MOD, DEL, UCH") },
            addressId = text(node, "aid", path),
            longitude = geo?.let { text(it, "long", "$path.geo") },
            latitude = geo?.let { text(it, "lat", "$path.geo") },
            postalCode = required(node, "plz", path),
            city = required(node, "o", path),
            district = text(node, "ot", path),
            street = required(node, "s", path),
            houseNumber = required(node, "h", path),
            houseNumberSuffix = text(node, "hz", path),
            buildingPart = text(node, "gebaeudeteil", path),
            units = text(node, "anzNutzungseinheiten", path),
            businessUnits = text(node, "anzGeschaeftseinheiten", path),
            products = array(node, "pl", path).mapIndexed { i, p ->
                val at = "$path.pl[$i]"
                fields(p, PRODUCT, at)
                val bandwidth = p.get("erwarteteBandbreite")
                val period = p.get("realisierbarkeitProdukt")
                ProductAvailability(
                    product = required(p, "produkt", at),
                    technology = text(p, "technologie", at),
                    cpeTechnology = text(p, "technologieCpe", at),
                    networkTermination = text(p, "netzabschlussKunde", at),
                    downstream = bandwidth?.let { text(it, "downBB", "$at.erwarteteBandbreite") },
                    upstream = bandwidth?.let { text(it, "upBB", "$at.erwarteteBandbreite") },
                    availableFrom = period?.let { date(it, "realisierbarAb", "$at.realisierbarkeitProdukt") },
                    availableUntil = period?.let { date(it, "realisierbarBis", "$at.realisierbarkeitProdukt") },
                    provisioningDays = text(p, "bereitstellungszeit", at),
                    houseConnectionProduct = bool(p, "hausanschlussprodukt", at),
                )
            }.ifEmpty { fail("$path.pl", "mindestens ein Produkt") },
            technologies = array(node, "tl", path).mapIndexed { i, t ->
                val at = "$path.tl[$i]"
                fields(t, TECHNOLOGY, at)
                TechnologyAvailability(
                    technology = required(t, "technologie", at),
                    quota = text(t, "kont", at),
                    lastMileOperator = text(t, "ane", at),
                    homeIds = array(t, "hidl", at).map { it.asString() },
                    expansionStatus = text(t, "ausbaustatus", at),
                    expansionStart = t.get("ausbauzeitraum")?.let { date(it, "start", "$at.ausbauzeitraum") },
                    expansionEnd = t.get("ausbauzeitraum")?.let { date(it, "ende", "$at.ausbauzeitraum") },
                    ownerDataRequired = bool(t, "eigentuemerdaten", at),
                    landownerDeclarationRequired = bool(t, "geeErforderlich", at),
                    landownerDeclarationPresent = bool(t, "geeVorhanden", at),
                    fundedArea = bool(t, "foerdergebiet", at),
                    orderKind = text(t, "auftragsart", at)?.let { value -> OrderKind.entries.firstOrNull { it.name == value } ?: fail("$at.auftragsart", "erlaubt sind STANDARD, KONNEKTIVITAET") },
                    negativeDecision = text(t, "negativeAusbauentscheidung", at),
                    timeWindows = array(t, "zl", at).map { it.asString() },
                    distributionPoint = text(t, "netzverteilerZuordnung", at),
                    migration = bool(t, "migration", at),
                    expansionVariant = text(t, "ausbauvariante", at),
                    terminationLocation = text(t, "locationNetzabschlussKunde", at),
                    thresholdMarketing = bool(t, "schwellwertvermarktung", at),
                    marketingStart = t.get("vermarktungszeitraum")?.let { date(it, "start", "$at.vermarktungszeitraum") },
                    marketingEnd = t.get("vermarktungszeitraum")?.let { date(it, "ende", "$at.vermarktungszeitraum") },
                    thresholdTarget = text(t, "sollwertSchwellwertvermarktung", at),
                    thresholdActual = text(t, "istwertSchwellwertvermarktung", at),
                    expansionPhase = text(t, "ausbauPhase", at),
                )
            },
        )
    }

    fun write(location: Location): String = mapper.writeValueAsString(toJson(location))
    fun read(text: String): Location = fromJson(mapper.readTree(text))

    /** Checks a record against Replikat.xsd 1.7 (as part of a one-record Replikat); returns the errors. */
    fun validate(location: Location): List<String> {
        val xml = ByteArrayOutputStream().also { ReplikatXml.write(it, ReplikatHeader(LocalDate.now(), "CHECK", false), sequenceOf(location)) }.toByteArray()
        return ReplikatXml.validate(ByteArrayInputStream(xml)).map { it.substringAfter(": ") }
    }

    /** [location] without the elements in [omitted] (release of a partner). */
    fun without(location: Location, omitted: Set<String>): Location {
        if (omitted.isEmpty()) return location
        val node = toJson(location)
        strip(node, omitted)
        node.get("pl").forEach { strip(it as ObjectNode, omitted) }
        node.get("tl")?.forEach { strip(it as ObjectNode, omitted) }
        return fromJson(node)
    }

    private fun strip(node: ObjectNode, omitted: Set<String>) = omitted.forEach { node.remove(it) }

    private fun period(node: ObjectNode, name: String, start: LocalDate?, end: LocalDate?) {
        if (start == null && end == null) return
        val period = node.putObject(name)
        start?.let { period.put("start", it.toString()) }
        end?.let { period.put("ende", it.toString()) }
    }

    private fun fields(node: JsonNode, allowed: Set<String>, path: String) {
        if (!node.isObject) fail(path, "Objekt erwartet")
        node.propertyNames().forEach { if (it !in allowed) fail("$path.$it", "unbekanntes Feld; zulässig: ${allowed.sorted().joinToString()}") }
    }

    private fun text(node: JsonNode, name: String, path: String): String? {
        val value = node.get(name) ?: return null
        if (value.isNull) return null
        if (!value.isValueNode) fail("$path.$name", "Wert erwartet")
        return value.asString()
    }

    private fun required(node: JsonNode, name: String, path: String) = text(node, name, path)?.takeIf { it.isNotBlank() } ?: fail("$path.$name", "Pflichtfeld")

    private fun bool(node: JsonNode, name: String, path: String): Boolean? {
        val value = node.get(name) ?: return null
        if (value.isNull) return null
        if (!value.isBoolean) fail("$path.$name", "true oder false erwartet")
        return value.booleanValue()
    }

    private fun date(node: JsonNode, name: String, path: String): LocalDate? =
        text(node, name, path)?.let { runCatching { LocalDate.parse(it) }.getOrElse { _ -> fail("$path.$name", "Datum JJJJ-MM-TT erwartet") } }

    private fun array(node: JsonNode, name: String, path: String): List<JsonNode> {
        val value = node.get(name) ?: return emptyList()
        if (value.isNull) return emptyList()
        // a single element is accepted for a list, like in the order JSON
        return if (value.isArray) value.toList() else if (value.isObject || value.isValueNode) listOf(value) else fail("$path.$name", "Liste erwartet")
    }

    private fun fail(path: String, message: String): Nothing = throw IllegalArgumentException("$path: $message")
}
