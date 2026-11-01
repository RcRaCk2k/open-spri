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
        private const val BASE = "spri/v4/"
        private const val ENTRY_POINT = "wsdl/oss-envelope.xsd"
        private const val SCHEME = "classpath:/"

        /**
         * Loads the schema exclusively from the classpath. All external access is disabled; the relative
         * imports between the schema files are resolved by [ClasspathResolver]. This works the same in tests
         * (directories) and in the Spring Boot jar (nested jar URLs).
         */
        fun load(): SpriSchema {
            val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                resourceResolver = ClasspathResolver
            }
            val systemId = "$SCHEME$BASE$ENTRY_POINT"
            return SpriSchema(factory.newSchema(javax.xml.transform.stream.StreamSource(open(systemId), systemId)))
        }

        private fun open(systemId: String): java.io.InputStream {
            require(systemId.startsWith("$SCHEME$BASE")) { "schema reference outside the S/PRI schema: $systemId" }
            val path = systemId.removePrefix(SCHEME)
            return SpriSchema::class.java.classLoader.getResourceAsStream(path)
                ?: error("$path not found on the classpath - module spri-schema missing?")
        }

        /** Resolves `schemaLocation` imports relative to the importing file, within classpath:/spri/v4/ only. */
        private object ClasspathResolver : org.w3c.dom.ls.LSResourceResolver {
            private val ls = org.w3c.dom.bootstrap.DOMImplementationRegistry.newInstance()
                .getDOMImplementation("LS") as org.w3c.dom.ls.DOMImplementationLS

            override fun resolveResource(
                type: String?, namespaceURI: String?, publicId: String?, systemId: String?, baseURI: String?,
            ): org.w3c.dom.ls.LSInput {
                val resolved = java.net.URI(baseURI ?: "$SCHEME$BASE$ENTRY_POINT").resolve(requireNotNull(systemId)).toString()
                return ls.createLSInput().apply {
                    this.systemId = resolved
                    byteStream = open(resolved)
                }
            }
        }
    }
}
