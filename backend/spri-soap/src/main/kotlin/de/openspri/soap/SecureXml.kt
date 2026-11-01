package de.openspri.soap

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Hardened XML parsing (AUFGABE chapter 7.3): no DTDs, no external entities, secure processing limits.
 * Every inbound SOAP message is parsed exclusively through this class.
 */
object SecureXml {

    const val SOAP11_NS = "http://schemas.xmlsoap.org/soap/envelope/"
    const val ENVELOPE_NS = "http://spri.telekom.de/oss/v4/envelope"

    private val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }

    private val transformerFactory = TransformerFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
    }

    fun parse(bytes: ByteArray): Document =
        documentBuilderFactory.newDocumentBuilder().apply { setErrorHandler(null) }.parse(ByteArrayInputStream(bytes))

    fun newDocument(): Document = documentBuilderFactory.newDocumentBuilder().newDocument()

    fun serialize(node: Node): ByteArray {
        val out = ByteArrayOutputStream()
        transformerFactory.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, if (node is Document) "no" else "yes")
        }.transform(DOMSource(node), StreamResult(out))
        return out.toByteArray()
    }

    /** First child element, ignoring whitespace and comments. */
    fun firstChildElement(parent: Node): Element? {
        var child = parent.firstChild
        while (child != null) {
            if (child is Element) return child
            child = child.nextSibling
        }
        return null
    }

    fun childElement(parent: Element, namespace: String?, localName: String): Element? {
        var child = parent.firstChild
        while (child != null) {
            if (child is Element && child.localName == localName && child.namespaceURI == namespace) return child
            child = child.nextSibling
        }
        return null
    }

    /** Text of the first descendant element with [localName] (any namespace), e.g. externeAuftragsnummer. */
    fun findText(root: Element, localName: String): String? =
        root.getElementsByTagNameNS("*", localName).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
