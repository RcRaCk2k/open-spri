package de.openspri.soap

import de.openspri.domain.InterfaceVersion
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.exchange.TeqCode
import de.telekom.spri.oss.v4.complex.MeldungspositionOhneAttributeType
import de.telekom.spri.oss.v4.complex.MessageTEQType
import de.telekom.spri.oss.v4.enm.MeldungstypType
import de.telekom.spri.oss.v4.envelope.AnnehmenAuftragResponseType
import de.telekom.spri.oss.v4.envelope.AnnehmenMeldungResponseType
import de.telekom.spri.oss.v4.envelope.ObjectFactory
import de.telekom.spri.oss.v4.envelope.SignaturIdType
import de.telekom.spri.oss.v4.envelope.WholesaleResponseType
import java.time.Clock
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.GregorianCalendar
import javax.xml.datatype.DatatypeFactory

/**
 * Builds the signed SOAP response with the technical acknowledgement (TEQ), specification chapter 8.1
 * and 11.2.3. The response is signed with our key (policy.xml: RecipientToken "Always").
 */
class TeqResponder(
    private val credentials: SigningCredentials,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val signer = WsSecuritySigner(credentials)
    private val factory = ObjectFactory()
    private val datatypes = DatatypeFactory.newInstance()

    fun respond(operation: SoapOperation, code: TeqCode, externalOrderNumber: String?, clientNumber: String?): ByteArray {
        val teq = MessageTEQType().apply {
            meldungsTyp = MeldungstypType.TEQ
            externeAuftragsnummer = externalOrderNumber?.take(20)
            auftraggebernummer = clientNumber?.take(10)
            meldungspositionen = MessageTEQType.Meldungspositionen().apply {
                position += MeldungspositionOhneAttributeType().apply {
                    meldungscode = code.code
                    meldungstext = code.text
                }
            }
        }
        val payload = when (operation) {
            SoapOperation.ANNEHMEN_AUFTRAG -> factory.createAnnehmenAuftragResponse(
                AnnehmenAuftragResponseType().apply { control = control(); quittung = teq },
            )
            SoapOperation.ANNEHMEN_MELDUNG -> factory.createAnnehmenMeldungResponse(
                AnnehmenMeldungResponseType().apply { control = control(); quittung = teq },
            )
        }
        val envelope = SoapEnvelope.wrap(SpriJaxb.marshal(payload))
        return SecureXml.serialize(signer.sign(envelope))
    }

    private fun control() = WholesaleResponseType().apply {
        zeitstempel = datatypes.newXMLGregorianCalendar(
            GregorianCalendar.from(ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS)),
        )
        majorRelease = InterfaceVersion.CURRENT.major.toString()
        minorRelease = InterfaceVersion.CURRENT.minor.toString()
        signaturId = SignaturIdType().apply {
            issuer = credentials.certificate.issuerX500Principal.name
            serial = credentials.certificate.serialNumber.toString()
        }
    }
}
