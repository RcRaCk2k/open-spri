package de.openspri.soap

import de.openspri.domain.InterfaceVersion
import de.openspri.domain.exchange.ArchivedExchange
import de.openspri.domain.exchange.Direction
import de.openspri.domain.exchange.InboundMessage
import de.openspri.domain.exchange.InboundStore
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.exchange.TeqCode
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerDirectory
import de.telekom.spri.oss.v4.envelope.WholesaleMessageType
import de.openspri.rules.AttributeValidator
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import java.math.BigInteger
import java.net.InetAddress
import java.security.cert.X509Certificate
import java.time.Clock
import javax.security.auth.x500.X500Principal

/**
 * Synchronous entry check for every inbound S/PRI request (AUFGABE chapter 5.3, specification 10.4 and
 * 11.2.3.1). Every request is answered with a signed TEQ, the checks run in this order:
 *
 * | # | Check                                                      | Negative TEQ |
 * |---|------------------------------------------------------------|--------------|
 * | 0 | sender IP belongs to a partner (also enforced by a filter)  | HTTP 403     |
 * | 1 | well-formed SOAP 1.1 envelope with exactly one payload      | 0995         |
 * | 2 | WS-Security signature (see [WsSecurityVerifier])           | 0999         |
 * | 3 | payload valid against the S/PRI XSD                          | 0995         |
 * | 4 | major release = 4, version fields well-formed               | 0993         |
 * | 4b| attributes according to Anlage 1 ([AttributeValidator])     | 0995 (enforce) / archived finding (report) |
 * | 5 | signaturId in the control header matches the signing cert  | 0999         |
 * | 6 | archived and queued for business processing                 | 0999 on error|
 *
 * Checks on attribute level (Anlage 1), duplicates and the business checks follow in M2 and are
 * answered by ABBM, not by TEQ (specification 10.4).
 */
class InboundProcessor(
    private val partners: PartnerDirectory,
    private val store: InboundStore,
    private val schema: SpriSchema,
    private val verifier: WsSecurityVerifier,
    private val responder: TeqResponder,
    private val clock: Clock = Clock.systemUTC(),
    private val attributes: AttributeValidator? = null,
    private val attributeMode: AttributeMode = AttributeMode.REPORT,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    sealed interface Result {
        /** Request not allowed from this address: answer with HTTP 403 and no SOAP content. */
        data object Forbidden : Result

        data class Answered(val teq: TeqCode, val response: ByteArray) : Result
    }

    fun process(request: ByteArray, remoteAddress: InetAddress, soapAction: String?): Result {
        val started = clock.instant()
        val candidates = partners.findBySourceAddress(remoteAddress)
        if (candidates.isEmpty()) {
            log.warn("S/PRI request from {} rejected: address not allowlisted for any partner", remoteAddress.hostAddress)
            return Result.Forbidden
        }

        val context = Context(operationFromSoapAction(soapAction))
        val code = try {
            check(request, candidates, context)
        } catch (e: Rejected) {
            context.reason = e.message
            e.code
        } catch (e: Exception) {
            log.error("S/PRI request from {} failed technically", remoteAddress.hostAddress, e)
            context.reason = "technical error: ${e.javaClass.simpleName}: ${e.message}"
            TeqCode.TECHNICAL_ERROR
        }

        val operation = context.operation ?: SoapOperation.ANNEHMEN_AUFTRAG
        val response = responder.respond(operation, code, context.externalOrderNumber, context.clientNumber)
        val exchange = ArchivedExchange(
            direction = Direction.INBOUND,
            operation = context.operation,
            partnerId = context.partner?.id ?: candidates.singleOrNull()?.id,
            remoteAddress = remoteAddress.hostAddress,
            startedAt = started,
            durationMillis = clock.millis() - started.toEpochMilli(),
            request = request,
            response = response,
            teqCode = code,
            rejectionReason = context.reason,
            signerSubject = context.signer?.subjectX500Principal?.name,
            signerSerial = context.signer?.serialNumber?.toString(),
            externalOrderNumber = context.externalOrderNumber,
            findings = context.findings,
        )
        return try {
            if (code.isPositive) {
                store.storeAccepted(
                    exchange,
                    InboundMessage(context.partner!!.id, context.operation!!, context.externalOrderNumber, context.payloadXml!!),
                )
                Result.Answered(code, response)
            } else {
                store.storeRejected(exchange)
                log.info("S/PRI request from {} answered with TEQ {}: {}", remoteAddress.hostAddress, code.code, context.reason)
                Result.Answered(code, response)
            }
        } catch (e: Exception) {
            // Not archived means not accepted: the partner must be able to resend (specification 11.2.3.1).
            log.error("S/PRI request from {} could not be stored", remoteAddress.hostAddress, e)
            val failure = responder.respond(operation, TeqCode.TECHNICAL_ERROR, context.externalOrderNumber, context.clientNumber)
            Result.Answered(TeqCode.TECHNICAL_ERROR, failure)
        }
    }

    private fun check(request: ByteArray, candidates: List<Partner>, context: Context): TeqCode {
        // 1 - envelope
        val document = try {
            SecureXml.parse(request)
        } catch (e: Exception) {
            throw Rejected(TeqCode.STRUCTURE_INVALID, "not well-formed XML: ${e.message}")
        }
        val payload = SoapEnvelope.payload(document)
            ?: throw Rejected(TeqCode.STRUCTURE_INVALID, "no SOAP 1.1 envelope with exactly one payload element")
        context.operation = operationFromPayload(payload)
            ?: throw Rejected(TeqCode.STRUCTURE_INVALID, "unknown payload {${payload.namespaceURI}}${payload.localName}")
        context.externalOrderNumber = SecureXml.findText(payload, "externeAuftragsnummer")
        context.clientNumber = SecureXml.findText(payload, "auftraggebernummer")
        // Keep an unmodified copy: WSS4J processing below adds attributes to the DOM.
        context.payloadXml = SecureXml.serialize(payload)

        // 2 - signature (the partner is identified by source address and signing certificate)
        val failures = mutableListOf<String>()
        for (partner in candidates) {
            try {
                context.signer = verifier.verify(document, partner.signatureCertificates)
                context.partner = partner
                break
            } catch (e: SignatureRejectedException) {
                failures += "${partner.name}: ${e.message}"
            }
        }
        if (context.partner == null) throw Rejected(TeqCode.TECHNICAL_ERROR, "signature rejected (${failures.joinToString("; ")})")

        // 3 - schema
        val schemaPayload = SecureXml.parse(context.payloadXml!!).documentElement
        val errors = schema.validate(schemaPayload)
        if (errors.isNotEmpty()) throw Rejected(TeqCode.STRUCTURE_INVALID, "XSD: ${errors.joinToString(" | ")}")

        // 4 - version
        val control = controlHeader(schemaPayload, context.operation!!)
        val version = try {
            InterfaceVersion.parse(control.majorRelease.trim(), control.minorRelease.trim())
        } catch (e: IllegalArgumentException) {
            throw Rejected(TeqCode.VERSION_INVALID, e.message ?: "invalid version")
        }
        if (!version.isCompatibleWith(InterfaceVersion.CURRENT)) {
            throw Rejected(TeqCode.VERSION_INVALID, "major release ${version.major} not supported (implemented: ${InterfaceVersion.CURRENT})")
        }

        // 4b - attributes (Anlage 1, specification 10.4 checks 4 and 5)
        attributes?.let { validator ->
            val violations = when (context.operation!!) {
                SoapOperation.ANNEHMEN_AUFTRAG -> validator.validateOrder(schemaPayload)
                SoapOperation.ANNEHMEN_MELDUNG -> validator.validateMessage(schemaPayload)
            }
            if (violations.isNotEmpty()) {
                val summary = "Anlage 1: " + violations.joinToString(" | ")
                if (attributeMode == AttributeMode.ENFORCE) throw Rejected(TeqCode.STRUCTURE_INVALID, summary)
                context.findings = summary
                log.info("S/PRI request {} violates Anlage 1 (report mode): {}", context.externalOrderNumber, summary)
            }
        }

        // 5 - signaturId
        if (!matches(control, context.signer!!)) {
            throw Rejected(
                TeqCode.TECHNICAL_ERROR,
                "signaturId (${control.signaturId.issuer} / ${control.signaturId.serial}) does not match the signing certificate",
            )
        }
        return TeqCode.OK
    }

    private fun controlHeader(payload: Element, operation: SoapOperation): WholesaleMessageType = when (operation) {
        SoapOperation.ANNEHMEN_AUFTRAG ->
            SpriJaxb.unmarshal(payload, de.telekom.spri.oss.v4.envelope.AnnehmenAuftragRequestType::class.java).control
        SoapOperation.ANNEHMEN_MELDUNG ->
            SpriJaxb.unmarshal(payload, de.telekom.spri.oss.v4.envelope.AnnehmenMeldungRequestType::class.java).control
    }

    /**
     * Compares the control header (Anlage 1: issuer of the certificate, serial number) with the certificate
     * that actually signed the message. Issuer as X.500 name, serial number decimal (docs/spec-abweichungen.md, SA-005).
     */
    private fun matches(control: WholesaleMessageType, certificate: X509Certificate): Boolean {
        val id = control.signaturId ?: return false
        val issuer = runCatching { X500Principal(id.issuer.trim()) }.getOrNull() ?: return false
        val serial = id.serial.trim().toBigIntegerOrNull() ?: return false
        return issuer == certificate.issuerX500Principal && serial == certificate.serialNumber
    }

    private fun String.toBigIntegerOrNull(): BigInteger? = runCatching { BigInteger(this) }.getOrNull()

    private fun operationFromPayload(payload: Element): SoapOperation? {
        if (payload.namespaceURI != SecureXml.ENVELOPE_NS) return null
        return when (payload.localName) {
            "annehmenAuftragRequest" -> SoapOperation.ANNEHMEN_AUFTRAG
            "annehmenMeldungRequest" -> SoapOperation.ANNEHMEN_MELDUNG
            else -> null
        }
    }

    private fun operationFromSoapAction(soapAction: String?): SoapOperation? =
        soapAction?.trim('"', ' ')?.substringAfterLast('/')?.let { SoapOperation.fromWsdlName(it) }

    private class Context(var operation: SoapOperation?) {
        var partner: Partner? = null
        var signer: X509Certificate? = null
        var externalOrderNumber: String? = null
        var clientNumber: String? = null
        var payloadXml: ByteArray? = null
        var reason: String? = null
        var findings: String? = null
    }

    private class Rejected(val code: TeqCode, message: String) : Exception(message)
}

/** How violations of Anlage 1 are handled (ADR-0006). */
enum class AttributeMode {
    /** Reject: TEQ 0995 for inbound requests, not sent for outbound requests. */
    ENFORCE,
    /** Accept, log and archive the finding. */
    REPORT,
}
