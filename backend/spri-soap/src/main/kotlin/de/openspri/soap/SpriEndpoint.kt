package de.openspri.soap

import jakarta.servlet.http.HttpServletRequest
import jakarta.xml.ws.BindingType
import jakarta.xml.ws.Provider
import jakarta.xml.ws.Service
import jakarta.xml.ws.ServiceMode
import jakarta.xml.ws.WebServiceProvider
import jakarta.xml.ws.soap.SOAPBinding
import org.apache.cxf.binding.soap.SoapMessage
import org.apache.cxf.binding.soap.interceptor.AbstractSoapInterceptor
import org.apache.cxf.helpers.IOUtils
import org.apache.cxf.interceptor.Fault
import org.apache.cxf.message.Message
import org.apache.cxf.phase.AbstractPhaseInterceptor
import org.apache.cxf.phase.Phase
import org.apache.cxf.phase.PhaseInterceptorChain
import org.apache.cxf.transport.http.AbstractHTTPDestination
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import javax.xml.namespace.QName
import javax.xml.transform.Source
import javax.xml.transform.stream.StreamSource

/**
 * CXF transport for the S/PRI service (WSDL Anlage 3: service SpriService, port spri, operations
 * annehmenAuftrag and annehmenMeldung). CXF handles SOAP 1.1 over HTTP and publishes the WSDL (?wsdl).
 * The actual checks are done by [InboundProcessor] on the exact bytes received, so that every error can
 * be answered with a TEQ instead of a SOAP fault (specification 11.2.3.1).
 */
@WebServiceProvider(
    serviceName = "SpriService",
    portName = "spri",
    targetNamespace = SpriClient.NAMESPACE,
    wsdlLocation = "classpath:spri/v4/wsdl/spri.wsdl",
)
@ServiceMode(Service.Mode.MESSAGE)
@BindingType(SOAPBinding.SOAP11HTTP_BINDING)
class SpriEndpoint(private val processor: InboundProcessor) : Provider<Source> {

    override fun invoke(request: Source): Source {
        val message = PhaseInterceptorChain.getCurrentMessage()
        val raw = message.exchange.inMessage[RawRequestInterceptor.RAW_REQUEST] as ByteArray
        val http = message[AbstractHTTPDestination.HTTP_REQUEST] as HttpServletRequest
        val soapAction = http.getHeader("SOAPAction")
        return when (val result = processor.process(raw, InetAddress.getByName(http.remoteAddr), soapAction)) {
            is InboundProcessor.Result.Answered -> StreamSource(ByteArrayInputStream(result.response))
            // Normally caught by the IP filter before CXF; kept as a second line of defence.
            InboundProcessor.Result.Forbidden -> {
                message.exchange.outMessage?.put(Message.RESPONSE_CODE, 403)
                throw Fault(SecurityException("forbidden"))
            }
        }
    }
}

/** Keeps the exact request bytes for signature verification and the archive (NF-09). */
class RawRequestInterceptor(private val maxBytes: Int) : AbstractPhaseInterceptor<Message>(Phase.RECEIVE) {

    override fun handleMessage(message: Message) {
        val input = message.getContent(InputStream::class.java) ?: return
        val bytes = input.readNBytes(maxBytes + 1)
        if (bytes.size > maxBytes) throw Fault(IllegalArgumentException("request exceeds $maxBytes bytes"))
        message[RAW_REQUEST] = bytes
        message.setContent(InputStream::class.java, ByteArrayInputStream(bytes))
        IOUtils.consume(input)
    }

    companion object {
        const val RAW_REQUEST = "openspri.rawRequest"
    }
}

/**
 * Declares the wsse:Security header as understood. Without this, CXF would reject signed requests with a
 * mustUnderstand fault, because the signature is verified by [InboundProcessor] and not by a CXF interceptor.
 */
class SecurityHeaderUnderstoodInterceptor : AbstractSoapInterceptor(Phase.PRE_PROTOCOL) {
    override fun getUnderstoodHeaders(): Set<QName> = setOf(WSSE_SECURITY)
    override fun handleMessage(message: SoapMessage) = Unit

    companion object {
        val WSSE_SECURITY = QName("http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd", "Security")
    }
}
