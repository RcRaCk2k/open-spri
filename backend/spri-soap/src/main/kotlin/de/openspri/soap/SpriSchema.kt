package de.openspri.soap

import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import javax.xml.XMLConstants
import javax.xml.transform.dom.DOMSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory

/**
 * The S/PRI XML schema (Anlage 3, XSD 4.00.03), loaded from the classpath (module spri-schema).
 * Entry point is oss-envelope.xsd, which imports all other schema files.
 */
class SpriSchema private constructor(private val schema: Schema) {

    /** Validates [payload] (e.g. annehmenAuftragRequest). Returns the validation errors, empty if valid. */
    fun validate(payload: Element): List<String> {
        val errors = mutableListOf<String>()
        val validator = schema.newValidator().apply {
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            errorHandler = object : ErrorHandler {
                override fun warning(exception: SAXParseException) = Unit
                override fun error(exception: SAXParseException) { if (errors.size < MAX_ERRORS) errors += exception.message.orEmpty() }
                override fun fatalError(exception: SAXParseException) { errors += exception.message.orEmpty() }
            }
        }
        validator.validate(DOMSource(payload))
        return errors
    }

    companion object {
        private const val MAX_ERRORS = 20
        private const val ENTRY_POINT = "spri/v4/wsdl/oss-envelope.xsd"

        fun load(): SpriSchema {
            val url = SpriSchema::class.java.classLoader.getResource(ENTRY_POINT)
                ?: error("$ENTRY_POINT not found on the classpath - module spri-schema missing?")
            val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                // The schema files import each other relatively; only local (jar/file) imports are allowed.
                setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "jar,file")
                setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            }
            return SpriSchema(factory.newSchema(url))
        }
    }
}
