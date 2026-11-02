package de.openspri.replikat

import de.openspri.domain.availability.ChangeMark
import de.openspri.domain.availability.Location
import de.openspri.domain.availability.OrderKind
import de.openspri.domain.availability.ProductAvailability
import de.openspri.domain.availability.TechnologyAvailability
import de.openspri.replikat.jaxb.Replikat
import jakarta.xml.bind.JAXBContext
import jakarta.xml.bind.JAXBElement
import jakarta.xml.bind.Marshaller
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import javax.xml.XMLConstants
import javax.xml.datatype.DatatypeFactory
import javax.xml.datatype.XMLGregorianCalendar
import javax.xml.namespace.QName
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

/** Head of a Replikat (Replikat 3.2): creation date, carrier code of the Leistungserbringer, full or delta. */
data class ReplikatHeader(val date: LocalDate, val carrierCode: String, val delta: Boolean)

/** A Replikat that does not conform to Replikat.xsd 1.7; [errors] with line numbers. */
class ReplikatInvalidException(val errors: List<String>) : IllegalArgumentException(errors.joinToString(" | "))

/**
 * Reading and writing Replikat files (Replikat.xsd 1.7, namespace `http://ak-spri.de/wp-content/uploads/`). Both
 * work as a stream, record by record, so that a Replikat with many locations does not have to fit into memory.
 * XML is read hardened (no DTD, no external entities).
 */
object ReplikatXml {
    const val NAMESPACE = "http://ak-spri.de/wp-content/uploads/"
    const val SCHEMA_VERSION = "1.7"
    private const val SCHEMA = "replikat/v1_7/Replikat.xsd"
    private const val MAX_ERRORS = 20

    private val schema by lazy {
        val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        val url = requireNotNull(ReplikatXml::class.java.classLoader.getResource(SCHEMA)) { "$SCHEMA fehlt (Gradle-Task :replikat:unpackReplikatXsd)" }
        factory.newSchema(url)
    }
    private val context by lazy { JAXBContext.newInstance(Replikat::class.java) }
    private val datatypes = DatatypeFactory.newInstance()
    private val DS = QName(NAMESPACE, "ds")

    /** Validates against Replikat.xsd 1.7 (streaming); returns the errors, empty if valid. */
    fun validate(input: InputStream): List<String> {
        val errors = mutableListOf<String>()
        val validator = schema.newValidator()
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        validator.errorHandler = object : ErrorHandler {
            override fun warning(e: SAXParseException) = Unit
            override fun error(e: SAXParseException) { if (errors.size < MAX_ERRORS) errors += "Zeile ${e.lineNumber}: ${e.message}" }
            override fun fatalError(e: SAXParseException) { errors += "Zeile ${e.lineNumber}: ${e.message}"; throw e }
        }
        try {
            validator.validate(StreamSource(input))
        } catch (e: SAXParseException) {
            // already recorded
        }
        return errors
    }

    /** Reads a Replikat; calls [record] for every data record in file order. The file must have been validated. */
    fun read(input: InputStream, record: (Location) -> Unit): ReplikatHeader {
        val reader = inputFactory().createXMLStreamReader(input)
        val unmarshaller = context.createUnmarshaller()
        var date: LocalDate? = null
        var carrierCode: String? = null
        var delta = false
        var event = reader.eventType
        while (true) {
            if (event == XMLStreamConstants.START_ELEMENT && reader.localName == "ds") {
                // the unmarshaller consumes the element and leaves the reader on the event after it, which may
                // already be the next ds: do not advance again
                record(toLocation(unmarshaller.unmarshal(reader, Replikat.Datensatzliste.Ds::class.java).value))
                event = reader.eventType
                continue
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                when (reader.localName) {
                    "datum" -> date = LocalDate.parse(reader.elementText.trim().take(10))
                    "carrierCode" -> carrierCode = reader.elementText.trim()
                    "deltareplikat" -> delta = reader.elementText.trim().let { it == "true" || it == "1" }
                }
            }
            if (!reader.hasNext()) break
            event = reader.next()
        }
        reader.close()
        return ReplikatHeader(requireNotNull(date) { "datum fehlt" }, requireNotNull(carrierCode) { "carrierCode fehlt" }, delta)
    }

    /** Writes a Replikat with [header] and the [records] (UTF-8). */
    fun write(output: OutputStream, header: ReplikatHeader, records: Sequence<Location>) {
        val writer = XMLOutputFactory.newInstance().createXMLStreamWriter(output, "UTF-8")
        val marshaller = context.createMarshaller().apply {
            setProperty(Marshaller.JAXB_FRAGMENT, true)
            setProperty(Marshaller.JAXB_ENCODING, "UTF-8")
        }
        writer.writeStartDocument("UTF-8", "1.0")
        writer.setDefaultNamespace(NAMESPACE)
        writer.writeStartElement(NAMESPACE, "replikat")
        writer.writeDefaultNamespace(NAMESPACE)
        element(writer, "datum", header.date.toString())
        element(writer, "carrierCode", header.carrierCode)
        element(writer, "deltareplikat", header.delta.toString())
        writer.writeStartElement(NAMESPACE, "datensatzliste")
        records.forEach { location ->
            marshaller.marshal(JAXBElement(DS, Replikat.Datensatzliste.Ds::class.java, toDs(location)), writer)
        }
        writer.writeEndElement()
        writer.writeEndElement()
        writer.writeEndDocument()
        writer.flush()
        writer.close()
    }

    private fun element(writer: javax.xml.stream.XMLStreamWriter, name: String, value: String) {
        writer.writeStartElement(NAMESPACE, name)
        writer.writeCharacters(value)
        writer.writeEndElement()
    }

    private fun inputFactory() = XMLInputFactory.newInstance().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }

    // --- JAXB <-> domain ---------------------------------------------------------------------------

    fun toLocation(ds: Replikat.Datensatzliste.Ds) = Location(
        change = ds.ak?.let { ChangeMark.valueOf(it) },
        addressId = ds.aid,
        longitude = ds.geo?.long,
        latitude = ds.geo?.lat,
        postalCode = ds.plz,
        city = ds.o,
        district = ds.ot,
        street = ds.s,
        houseNumber = ds.h,
        houseNumberSuffix = ds.hz,
        buildingPart = ds.gebaeudeteil,
        units = ds.anzNutzungseinheiten,
        businessUnits = ds.anzGeschaeftseinheiten,
        products = ds.pl.p.map { p ->
            ProductAvailability(
                product = p.produkt,
                technology = p.technologie,
                cpeTechnology = p.technologieCpe,
                networkTermination = p.netzabschlussKunde,
                downstream = p.erwarteteBandbreite?.downBB,
                upstream = p.erwarteteBandbreite?.upBB,
                availableFrom = date(p.realisierbarkeitProdukt?.realisierbarAb),
                availableUntil = date(p.realisierbarkeitProdukt?.realisierbarBis),
                provisioningDays = p.bereitstellungszeit,
                houseConnectionProduct = p.isHausanschlussprodukt,
            )
        },
        technologies = ds.tl?.t.orEmpty().map { t ->
            TechnologyAvailability(
                technology = t.technologie,
                quota = t.kont,
                lastMileOperator = t.ane,
                homeIds = t.hidl?.hid.orEmpty(),
                expansionStatus = t.ausbaustatus,
                expansionStart = date(t.ausbauzeitraum?.start),
                expansionEnd = date(t.ausbauzeitraum?.ende),
                ownerDataRequired = t.isEigentuemerdaten,
                landownerDeclarationRequired = t.isGeeErforderlich,
                landownerDeclarationPresent = t.isGeeVorhanden,
                fundedArea = t.isFoerdergebiet,
                orderKind = t.auftragsart?.let { OrderKind.valueOf(it) },
                negativeDecision = t.negativeAusbauentscheidung,
                timeWindows = t.zl?.z.orEmpty(),
                distributionPoint = t.netzverteilerZuordnung,
                migration = t.isMigration,
                expansionVariant = t.ausbauvariante,
                terminationLocation = t.locationNetzabschlussKunde,
                thresholdMarketing = t.isSchwellwertvermarktung,
                marketingStart = date(t.vermarktungszeitraum?.start),
                marketingEnd = date(t.vermarktungszeitraum?.ende),
                thresholdTarget = t.sollwertSchwellwertvermarktung,
                thresholdActual = t.istwertSchwellwertvermarktung,
                expansionPhase = t.ausbauPhase,
            )
        },
    )

    fun toDs(location: Location) = Replikat.Datensatzliste.Ds().apply {
        ak = location.change?.name
        aid = location.addressId
        if (location.longitude != null && location.latitude != null) {
            geo = Replikat.Datensatzliste.Ds.Geo().apply { long = location.longitude; lat = location.latitude }
        }
        plz = location.postalCode
        o = location.city
        ot = location.district
        s = location.street
        h = location.houseNumber
        hz = location.houseNumberSuffix
        gebaeudeteil = location.buildingPart
        anzNutzungseinheiten = location.units
        anzGeschaeftseinheiten = location.businessUnits
        pl = Replikat.Datensatzliste.Ds.Pl().apply {
            location.products.forEach { product ->
                p += Replikat.Datensatzliste.Ds.Pl.P().apply {
                    produkt = product.product
                    technologie = product.technology
                    technologieCpe = product.cpeTechnology
                    netzabschlussKunde = product.networkTermination
                    if (product.downstream != null) {
                        erwarteteBandbreite = Replikat.Datensatzliste.Ds.Pl.P.ErwarteteBandbreite().apply { downBB = product.downstream; upBB = product.upstream }
                    }
                    if (product.availableFrom != null || product.availableUntil != null) {
                        realisierbarkeitProdukt = Replikat.Datensatzliste.Ds.Pl.P.RealisierbarkeitProdukt().apply {
                            realisierbarAb = xml(product.availableFrom)
                            realisierbarBis = xml(product.availableUntil)
                        }
                    }
                    bereitstellungszeit = product.provisioningDays
                    isHausanschlussprodukt = product.houseConnectionProduct
                }
            }
        }
        if (location.technologies.isNotEmpty()) {
            tl = Replikat.Datensatzliste.Ds.Tl().apply {
                location.technologies.forEach { technology ->
                    t += Replikat.Datensatzliste.Ds.Tl.T().apply {
                        technologie = technology.technology
                        kont = technology.quota
                        ane = technology.lastMileOperator
                        if (technology.homeIds.isNotEmpty()) hidl = Replikat.Datensatzliste.Ds.Tl.T.Hidl().apply { hid += technology.homeIds }
                        ausbaustatus = technology.expansionStatus
                        if (technology.expansionStart != null || technology.expansionEnd != null) {
                            ausbauzeitraum = Replikat.Datensatzliste.Ds.Tl.T.Ausbauzeitraum().apply {
                                start = xml(technology.expansionStart)
                                ende = xml(technology.expansionEnd)
                            }
                        }
                        isEigentuemerdaten = technology.ownerDataRequired
                        isGeeErforderlich = technology.landownerDeclarationRequired
                        isGeeVorhanden = technology.landownerDeclarationPresent
                        isFoerdergebiet = technology.fundedArea
                        auftragsart = technology.orderKind?.name
                        negativeAusbauentscheidung = technology.negativeDecision
                        if (technology.timeWindows.isNotEmpty()) zl = Replikat.Datensatzliste.Ds.Tl.T.Zl().apply { z += technology.timeWindows }
                        netzverteilerZuordnung = technology.distributionPoint
                        isMigration = technology.migration
                        ausbauvariante = technology.expansionVariant
                        locationNetzabschlussKunde = technology.terminationLocation
                        isSchwellwertvermarktung = technology.thresholdMarketing
                        if (technology.marketingStart != null || technology.marketingEnd != null) {
                            vermarktungszeitraum = Replikat.Datensatzliste.Ds.Tl.T.Vermarktungszeitraum().apply {
                                start = xml(technology.marketingStart)
                                ende = xml(technology.marketingEnd)
                            }
                        }
                        sollwertSchwellwertvermarktung = technology.thresholdTarget
                        istwertSchwellwertvermarktung = technology.thresholdActual
                        ausbauPhase = technology.expansionPhase
                    }
                }
            }
        }
    }

    private fun date(value: XMLGregorianCalendar?): LocalDate? = value?.let { LocalDate.of(it.year, it.month, it.day) }
    private fun xml(value: LocalDate?): XMLGregorianCalendar? = value?.let { datatypes.newXMLGregorianCalendar(it.toString()) }
}
