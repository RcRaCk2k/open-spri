package de.openspri.soap

import de.openspri.domain.exchange.ArchivedExchange
import de.openspri.domain.exchange.Direction
import de.openspri.domain.exchange.OutboundArchive
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.exchange.TeqCode
import de.openspri.domain.partner.Partner
import jakarta.xml.ws.BindingProvider
import jakarta.xml.ws.Dispatch
import jakarta.xml.ws.Service
import jakarta.xml.ws.soap.SOAPBinding
import org.apache.cxf.endpoint.Client
import org.apache.cxf.transport.http.HTTPConduit
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy
import de.openspri.rules.AttributeValidator
import de.openspri.rules.AttributeViolation
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Clock
import java.time.Duration
import javax.xml.namespace.QName
import javax.xml.transform.Source
import javax.xml.transform.stream.StreamSource

/**
 * Sends one S/PRI request (order or message) to a partner and evaluates the synchronous TEQ
 * (specification 11.2.3). Retries, ordering and the outbox follow in M3; this class performs exactly one attempt.
 */
class SpriClient(
    credentials: () -> SigningCredentials,
    private val schema: SpriSchema,
    private val verifier: WsSecurityVerifier,
    private val archive: OutboundArchive,
    private val settings: Settings = Settings(),
    private val clock: Clock = Clock.systemUTC(),
) {
    constructor(
        credentials: SigningCredentials,
        schema: SpriSchema,
        verifier: WsSecurityVerifier,
        archive: OutboundArchive,
        settings: Settings = Settings(),
        clock: Clock = Clock.systemUTC(),
    ) : this({ credentials }, schema, verifier, archive, settings, clock)

    data class Settings(
        val connectTimeout: Duration = Duration.ofSeconds(10),
        /** Specification 11.4: max. 30 s without attachments, up to 120 s with attachments. */
        val receiveTimeout: Duration = Duration.ofSeconds(30),
        /** policy.xml requires signed responses; can be relaxed per installation if a partner does not sign. */
        val requireSignedResponse: Boolean = true,
        /** Optional egress proxy with a fixed outbound IP (AUFGABE chapter 7.6), e.g. "http://proxy:3128". */
        val outboundProxy: URI? = null,
        /** Anlage 1 check of our own requests before sending (ADR-0006). */
        val attributeMode: AttributeMode = AttributeMode.ENFORCE,
    )

    sealed interface Outcome {
        val operation: SoapOperation

        /** The partner answered with a valid TEQ (positive or negative). */
        data class Acknowledged(
            override val operation: SoapOperation,
            val teq: TeqCode?,
            val teqCode: String,
            val teqText: String,
        ) : Outcome

        /**
         * No valid TEQ (timeout, transport error, invalid or unsigned response).
         * @property reached false if the request certainly did not reach the partner's application (connection
         *   refused, unknown host, HTTP 403/404/502/503/504); true if the partner may have processed it.
         */
        data class NotAcknowledged(override val operation: SoapOperation, val reason: String, val reached: Boolean = true) : Outcome

        /** Not sent: our own request violates Anlage 1 (enforce mode). Nothing went over the wire. */
        data class NotSent(override val operation: SoapOperation, val violations: List<AttributeViolation>) : Outcome
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val signer = CachingSigner(credentials)
    private val attributes = AttributeValidator()

    /** [payload] is the annehmenAuftragRequest or annehmenMeldungRequest element. */
    fun send(partner: Partner, endpoint: URI, operation: SoapOperation, payload: Element, externalOrderNumber: String?): Outcome {
        val violations = when (operation) {
            SoapOperation.ANNEHMEN_AUFTRAG -> attributes.validateOrder(payload)
            SoapOperation.ANNEHMEN_MELDUNG -> attributes.validateMessage(payload)
        }
        var findings: String? = null
        if (violations.isNotEmpty()) {
            if (settings.attributeMode == AttributeMode.ENFORCE) {
                log.warn("S/PRI {} {} not sent, violates Anlage 1: {}", operation.wsdlName, externalOrderNumber, violations)
                return Outcome.NotSent(operation, violations)
            }
            findings = "Anlage 1: " + violations.joinToString(" | ")
        }
        val started = clock.instant()
        val request = SecureXml.serialize(signer.sign(SoapEnvelope.wrap(payload)))
        var response: ByteArray? = null
        val outcome = try {
            response = post(endpoint, operation, request, partner.useOutboundProxy)
            evaluate(partner, operation, response)
        } catch (e: Exception) {
            val reason = causes(e)
            log.warn("S/PRI {} to {} ({}) failed: {}", operation.wsdlName, partner.name, endpoint, reason)
            Outcome.NotAcknowledged(operation, "transport: $reason", reached = !certainlyNotReached(e))
        }
        archive.store(
            ArchivedExchange(
                direction = Direction.OUTBOUND,
                operation = operation,
                partnerId = partner.id,
                remoteAddress = endpoint.toString(),
                startedAt = started,
                durationMillis = clock.millis() - started.toEpochMilli(),
                request = request,
                response = response,
                teqCode = (outcome as? Outcome.Acknowledged)?.teq,
                rejectionReason = when (outcome) {
                    is Outcome.NotAcknowledged -> outcome.reason
                    is Outcome.Acknowledged -> outcome.takeIf { it.teq != TeqCode.OK }?.let { "TEQ ${it.teqCode}: ${it.teqText}" }
                    is Outcome.NotSent -> null
                },
                signerSubject = null,
                signerSerial = null,
                externalOrderNumber = externalOrderNumber,
                findings = findings,
            ),
        )
        return outcome
    }

    /** True if the error proves that the partner did not receive the request (safe to send again). */
    private fun certainlyNotReached(error: Throwable): Boolean = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.any { cause ->
        cause is java.net.ConnectException || cause is java.net.UnknownHostException || cause is java.net.NoRouteToHostException ||
            (cause is org.apache.cxf.transport.http.HTTPException && cause.responseCode in setOf(403, 404, 502, 503, 504))
    }

    /** CXF wraps transport errors several times; the root cause (e.g. "HTTP 403") is what operators need. */
    private fun causes(error: Throwable): String =
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
            .map { "${it.javaClass.simpleName}: ${it.message}" }
            .distinct().joinToString(" <- ")

    private fun evaluate(partner: Partner, operation: SoapOperation, response: ByteArray): Outcome {
        val document = SecureXml.parse(response)
        if (settings.requireSignedResponse) {
            try {
                verifier.verify(document, partner.signatureCertificates)
            } catch (e: SignatureRejectedException) {
                return Outcome.NotAcknowledged(operation, "response signature rejected: ${e.message}")
            }
        }
        val payload = SoapEnvelope.payload(document) ?: return Outcome.NotAcknowledged(operation, "response is not a SOAP envelope")
        val clean = SecureXml.parse(SecureXml.serialize(payload)).documentElement
        val errors = schema.validate(clean)
        if (errors.isNotEmpty()) return Outcome.NotAcknowledged(operation, "response violates the XSD: ${errors.first()}")
        val quittung = SecureXml.childElement(clean, null, "quittung")
            ?: return Outcome.NotAcknowledged(operation, "response contains no quittung")
        val code = SecureXml.findText(quittung, "meldungscode").orEmpty()
        val text = SecureXml.findText(quittung, "meldungstext").orEmpty()
        return Outcome.Acknowledged(operation, TeqCode.fromCode(code), code, text)
    }

    private fun post(endpoint: URI, operation: SoapOperation, request: ByteArray, viaProxy: Boolean): ByteArray {
        val service = Service.create(SERVICE)
        service.addPort(PORT, SOAPBinding.SOAP11HTTP_BINDING, endpoint.toString())
        val dispatch: Dispatch<Source> = service.createDispatch(PORT, Source::class.java, Service.Mode.MESSAGE)
        dispatch.requestContext[BindingProvider.SOAPACTION_USE_PROPERTY] = true
        dispatch.requestContext[BindingProvider.SOAPACTION_URI_PROPERTY] = "$NAMESPACE/${operation.wsdlName}"
        configureConduit(dispatch, viaProxy)
        val result = dispatch.invoke(StreamSource(ByteArrayInputStream(request)))
        return SecureXml.serialize(SecureXml.parse(sourceBytes(result)))
    }

    private fun configureConduit(dispatch: Dispatch<Source>, viaProxy: Boolean) {
        val client: Client = (dispatch as org.apache.cxf.jaxws.DispatchImpl<*>).client
        val conduit = client.conduit as HTTPConduit
        conduit.client = HTTPClientPolicy().apply {
            connectionTimeout = settings.connectTimeout.toMillis()
            receiveTimeout = settings.receiveTimeout.toMillis()
            isAllowChunking = false
            settings.outboundProxy?.takeIf { viaProxy }?.let {
                proxyServer = it.host
                proxyServerPort = if (it.port > 0) it.port else 3128
            }
        }
        // TLS: the JVM default trust store (public CAs) is used, no certificate pinning (AK security document 2.4).
    }

    private fun sourceBytes(source: Source): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        javax.xml.transform.TransformerFactory.newInstance().apply {
            setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }.newTransformer().transform(source, javax.xml.transform.stream.StreamResult(out))
        return out.toByteArray()
    }

    companion object {
        const val NAMESPACE = "http://spri.telekom.de/oss/v4/spri"
        val SERVICE = QName(NAMESPACE, "SpriService")
        val PORT = QName(NAMESPACE, "spri")
    }
}
