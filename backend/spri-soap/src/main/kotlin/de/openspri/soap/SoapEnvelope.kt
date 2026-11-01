package de.openspri.soap

import org.w3c.dom.Document
import org.w3c.dom.Element

/** Minimal SOAP 1.1 envelope handling (specification chapter 10: SOAP 1.1, document/literal). */
object SoapEnvelope {

    /** Creates `Envelope/Body/<payload>` with an importable copy of [payload]. */
    fun wrap(payload: Element): Document {
        val document = SecureXml.newDocument()
        val envelope = document.createElementNS(SecureXml.SOAP11_NS, "soapenv:Envelope")
        document.appendChild(envelope)
        envelope.appendChild(document.createElementNS(SecureXml.SOAP11_NS, "soapenv:Header"))
        val body = document.createElementNS(SecureXml.SOAP11_NS, "soapenv:Body")
        envelope.appendChild(body)
        body.appendChild(document.importNode(payload, true))
        return document
    }

    /** The Body element of a SOAP 1.1 envelope, or null if [document] is not one. */
    fun body(document: Document): Element? {
        val envelope = document.documentElement ?: return null
        if (envelope.localName != "Envelope" || envelope.namespaceURI != SecureXml.SOAP11_NS) return null
        return SecureXml.childElement(envelope, SecureXml.SOAP11_NS, "Body")
    }

    /** The single payload element inside the Body. More than one element is not allowed (specification 3.2). */
    fun payload(document: Document): Element? {
        val body = body(document) ?: return null
        val first = SecureXml.firstChildElement(body) ?: return null
        var sibling = first.nextSibling
        while (sibling != null) {
            if (sibling is Element) return null
            sibling = sibling.nextSibling
        }
        return first
    }
}
