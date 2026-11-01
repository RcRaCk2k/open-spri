package de.openspri.soap

import de.telekom.spri.oss.v4.envelope.AnnehmenAuftragRequestType
import de.telekom.spri.oss.v4.envelope.AnnehmenMeldungRequestType
import de.telekom.spri.oss.v4.envelope.ObjectFactory
import de.telekom.spri.oss.v4.order.AuftragType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SpriJsonTest {

    private val mapper = JsonMapper.builder().build()
    private val schema = SpriSchema.load()

    private fun roundtrip(xml: ByteArray, type: Class<*>): Pair<String, String> {
        val original = SecureXml.parse(xml).documentElement
        val jaxb = SpriJaxb.unmarshal(original, type)
        val json = SpriJson.toJson(jaxb!!)
        val back = SpriJson.fromJson(mapper.readTree(json.toString()), type)
        val element = when (back) {
            is AnnehmenAuftragRequestType -> SpriJaxb.marshal(ObjectFactory().createAnnehmenAuftragRequest(back))
            is AnnehmenMeldungRequestType -> SpriJaxb.marshal(ObjectFactory().createAnnehmenMeldungRequest(back))
            else -> error("unexpected")
        }
        assertEquals(emptyList(), schema.validate(SecureXml.parse(SecureXml.serialize(element)).documentElement))
        return json.toString() to String(SecureXml.serialize(element))
    }

    @Test
    fun `order XML to JSON and back is schema-valid and keeps the content`() {
        val (json, xml) = roundtrip(TestData.sample("neu-auftrag.xml"), AnnehmenAuftragRequestType::class.java)
        assertContains(json, "\"@type\":\"BereitstellungType\"")
        assertContains(json, "\"externeAuftragsnummer\":\"TEST-0001\"")
        assertContains(xml, "FTTH L2 ETH 100.40")
        assertContains(xml, "Musterstraße")
    }

    @Test
    fun `message with XSD type names containing a hyphen`() {
        val (json, _) = roundtrip(TestData.sample("qeb-meldung.xml"), AnnehmenMeldungRequestType::class.java)
        assertContains(json, "\"@type\":\"MeldungstypQEBType\"")
        assertEquals(setOf("meldungsattribute", "meldungspositionen"), SpriJson.elementsOf("MeldungstypZWM-LEType").intersect(setOf("meldungsattribute", "meldungspositionen")))
    }

    @Test
    fun `helpful errors for unknown fields, missing type and invalid values`() {
        val unknown = assertThrows<SpriJsonException> { SpriJson.fromJson(mapper.readTree("""{"externeAuftragsnr":"1"}"""), AuftragType::class.java) }
        assertContains(unknown.message!!, "externeAuftragsnummer")

        val abstract = assertThrows<SpriJsonException> { SpriJson.fromJson(mapper.readTree("""{"geschaeftsfall":{}}"""), AuftragType::class.java) }
        assertContains(abstract.message!!, "BereitstellungType")

        val enum = assertThrows<SpriJsonException> { SpriJson.fromJson(mapper.readTree("""{"aenderungskennzeichen":"Sofort"}"""), AuftragType::class.java) }
        assertContains(enum.message!!, "Terminverschiebung")
        assertEquals("$.aenderungskennzeichen", enum.path)
    }
}
