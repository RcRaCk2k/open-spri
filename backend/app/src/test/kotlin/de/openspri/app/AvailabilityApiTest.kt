package de.openspri.app

import de.openspri.auth.ApiTokens
import de.openspri.auth.Role
import de.openspri.domain.InMemorySettings
import de.openspri.domain.availability.Location
import de.openspri.domain.availability.ProductAvailability
import de.openspri.domain.availability.TechnologyAvailability
import de.openspri.domain.settings.CompanySettings
import de.openspri.replikat.ReplikatFiles
import de.openspri.replikat.ReplikatHeader
import de.openspri.replikat.ReplikatMeta
import de.openspri.replikat.ReplikatXml
import openspri.testapps.SoapInMemoryTestApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Own availabilities, Replikat release, download and online query for partners (M6, AUFGABE 5.4). */
@SpringBootTest(
    classes = [SoapInMemoryTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["openspri.inbox.initial-delay=1h", "openspri.outbox.initial-delay=1h"],
)
class AvailabilityApiTest(@Autowired private val tokens: ApiTokens, @Autowired private val settings: InMemorySettings) {
    @LocalServerPort
    private var port = 0

    private val admin by lazy { tokens.issue("admin", Role.ADMIN, null, null, null, "test", Instant.now()).plainText }

    private fun call(method: String, path: String, body: ByteArray? = null, token: String? = admin, type: String = "application/json", auth: String? = null): HttpResponse<ByteArray> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                .method(method, body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", type)
                .apply { (auth ?: token?.let { "Bearer $it" })?.let { header("Authorization", it) } }
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun text(response: HttpResponse<ByteArray>) = String(response.body())

    @BeforeEach
    fun company() {
        settings.save(CompanySettings("Testnetz GmbH", carrierCode = "TSTNZ", contactEmail = "noc@testnetz.example", roleAuftraggeber = true, roleLeistungserbringer = true))
    }

    private fun partner(name: String, network: String): Long {
        val body = """{"name":"$name","carrierCode":"${name.uppercase().take(6)}","allowedSourceNetworks":["$network"]}"""
        return Regex("\"id\":(\\d+)").find(text(call("POST", "/api/v1/partners", body.toByteArray())))!!.groupValues[1].toLong()
    }

    @Test
    fun `own sites via REST, CSV and Replikat import, checked against the XSD`() {
        val ds = """{"aid":"A-1","plz":"12345","o":"Musterstadt","s":"Musterstraße","h":"1","pl":[{"produkt":"FTTH 100","technologie":"FTTH"}],
            "tl":[{"technologie":"FTTH","ausbaustatus":"homesConnected","auftragsart":"STANDARD","hidl":["HID1"]}]}"""
        val created = call("POST", "/api/v1/availability/sites", ds.toByteArray())
        assertEquals(201, created.statusCode(), text(created))
        assertEquals(409, call("POST", "/api/v1/availability/sites", ds.toByteArray()).statusCode(), "same location twice")
        val invalid = call("POST", "/api/v1/availability/sites", ds.replace("\"12345\"", "\"ABCDE\"").replace("A-1", "A-9").toByteArray())
        assertEquals(400, invalid.statusCode())
        assertContains(text(invalid), "errors")
        assertEquals(400, call("POST", "/api/v1/availability/sites", ds.replace("\"aid\"", "\"adresse\"").toByteArray()).statusCode())
        assertContains(text(call("GET", "/api/v1/availability/sites?hid=HID1")), "\"aid\":\"A-1\"")

        val csv = "plz;o;s;h;produkt;technologie;ausbaustatus\n12345;Musterstadt;Musterweg;2;FTTH 100;FTTH;homesPassed\n12345;Musterstadt;Musterweg;2;FTTH 250;FTTH;homesPassed\n"
        val merged = call("POST", "/api/v1/availability/sites/import?mode=merge", csv.toByteArray(), type = "text/csv")
        assertEquals(200, merged.statusCode(), text(merged))
        assertContains(text(merged), "\"added\":1")
        assertContains(text(call("GET", "/api/v1/availability/sites?s=Musterweg")), "FTTH 250")
        val badCsv = call("POST", "/api/v1/availability/sites/import", "plz;o;s;h;produkt\nABC;Ort;Str;1;P\n".toByteArray(), type = "text/csv")
        assertEquals(400, badCsv.statusCode())
        assertContains(text(badCsv), "Zeile 2")

        val replikat = ByteArrayOutputStream().also {
            ReplikatXml.write(it, ReplikatHeader(LocalDate.now(), "TSTNZ", false), sequenceOf(location("10"), location("11")))
        }.toByteArray()
        val replaced = call("POST", "/api/v1/availability/sites/import", replikat, type = "application/xml")
        assertEquals(200, replaced.statusCode(), text(replaced))
        assertContains(text(replaced), "\"total\":2")
        assertEquals("[]", text(call("GET", "/api/v1/availability/sites?s=Musterweg")), "replace removes what is not in the file")
    }

    @Test
    fun `release, daily Replikat, download and online query only for the partner from its addresses`() {
        call("POST", "/api/v1/availability/sites/import", ByteArrayOutputStream().also {
            ReplikatXml.write(it, ReplikatHeader(LocalDate.now(), "TSTNZ", false), sequenceOf(location("20"), location("21")))
        }.toByteArray(), type = "application/xml")
        val allowed = partner("Abnehmer", "127.0.0.1/32")
        val elsewhere = partner("Fremd", "203.0.113.0/24")
        assertEquals(400, call("PUT", "/api/v1/replikat/releases/$allowed", """{"partnerId":$allowed,"omittedFields":["plz"]}""".toByteArray()).statusCode())
        assertEquals(200, call("PUT", "/api/v1/replikat/releases/$allowed", """{"partnerId":$allowed,"omittedFields":["geo"]}""".toByteArray()).statusCode())
        val files = text(call("POST", "/api/v1/replikat/releases/$allowed/run"))
        val day = LocalDate.now().toString().replace("-", "")
        assertContains(files, "TSTNZ_$day.zip")
        assertContains(files, "TSTNZ_${day}_delta.zip")

        val partnerToken = tokens.issue("Abnehmer", Role.PARTNER, null, null, null, "test", Instant.now(), partnerId = allowed).plainText
        assertEquals(401, call("GET", "/replikat/", token = null).statusCode())
        assertEquals(403, call("GET", "/replikat/", token = admin).statusCode(), "no partner token")
        assertContains(text(call("GET", "/replikat/", token = partnerToken)), "TSTNZ_${day}_meta.xml")

        val zip = call("GET", "/replikat/TSTNZ_$day.zip", token = partnerToken)
        assertEquals(200, zip.statusCode())
        val basic = "Basic " + Base64.getEncoder().encodeToString("abnehmer:$partnerToken".toByteArray())
        val meta = ReplikatMeta.parse(call("GET", "/replikat/TSTNZ_${day}_meta.xml", token = null, auth = basic).body())
        assertEquals(ReplikatFiles.md5(zip.body()), meta.md5)
        assertEquals("TSTNZ_$day.zip", meta.fileName)
        assertEquals(emptyList(), ReplikatXml.validate(ReplikatFiles.xml(zip.body())))
        assertEquals(404, call("GET", "/replikat/TSTNZ_19990101.zip", token = partnerToken).statusCode())

        val foreignToken = tokens.issue("Fremd", Role.PARTNER, null, null, null, "test", Instant.now(), partnerId = elsewhere).plainText
        assertEquals(403, call("GET", "/replikat/", token = foreignToken).statusCode(), "source address not allowlisted")

        val online = call("GET", "/api/v1/availability?plz=12345&s=Musterstraße&h=20", token = partnerToken)
        assertEquals(200, online.statusCode(), text(online))
        assertContains(text(online), "\"h\":\"20\"")
        assertTrue("\"geo\"" !in text(online), "geo is not agreed with this partner")
        assertEquals(400, call("GET", "/api/v1/availability?o=Musterstadt", token = partnerToken).statusCode())
        assertEquals(403, call("GET", "/api/v1/availability?plz=12345&s=Muster", token = admin).statusCode())
    }

    private fun location(house: String) = Location(
        addressId = "AID-$house", longitude = "13.3", latitude = "52.5", postalCode = "12345", city = "Musterstadt", street = "Musterstraße", houseNumber = house,
        products = listOf(ProductAvailability("FTTH 100", "FTTH", downstream = "100", upstream = "40")),
        technologies = listOf(TechnologyAvailability("FTTH", expansionStatus = "homesConnected")),
    )
}
