package de.openspri.app

import de.openspri.auth.ApiTokens
import de.openspri.auth.Role
import openspri.testapps.SoapInMemoryTestApplication
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Agreements, products, change matrix and maintenance announcements via REST (AUFGABE 5.7, 5.9, 8.5, NF-08). */
@SpringBootTest(
    classes = [SoapInMemoryTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["openspri.inbox.initial-delay=1h", "openspri.outbox.initial-delay=1h"],
)
class MasterDataApiTest(@Autowired private val tokens: ApiTokens) {
    @LocalServerPort
    private var port = 0

    private val admin by lazy { tokens.issue("admin", Role.ADMIN, null, null, null, "test", Instant.now()).plainText }
    private val viewer by lazy { tokens.issue("viewer", Role.VIEWER, null, null, null, "test", Instant.now()).plainText }

    private fun call(method: String, path: String, body: String? = null, token: String = admin, type: String = "application/json"): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", type).header("Authorization", "Bearer $token").build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun id(body: String) = Regex("\"id\":(\\d+)").find(body)!!.groupValues[1].toLong()

    private fun partner(name: String, contact: String? = null): Long {
        val mail = contact?.let { ""","technicalContactEmail":"$it"""" } ?: ""
        val created = call("POST", "/api/v1/partners", """{"name":"$name","carrierCode":"${name.uppercase()}"$mail}""")
        assertEquals(201, created.statusCode(), created.body())
        return id(created.body())
    }

    @Test
    fun `contracts, powers of attorney with document, projects`() {
        val p = partner("Vertrag")
        val base = "/api/v1/partners/$p"

        val contract = call("POST", "$base/contracts", """{"role":"LEISTUNGSERBRINGER","clientNumber":"AG4711","serviceNumber":"LN1","validFrom":"2026-01-01"}""")
        assertEquals(201, contract.statusCode(), contract.body())
        val contractId = id(contract.body())
        assertEquals(400, call("POST", "$base/contracts", """{"role":"LEISTUNGSERBRINGER","clientNumber":"ZU-LANG-12345"}""").statusCode())
        assertEquals(400, call("POST", "$base/contracts", """{"role":"X","clientNumber":"A"}""").statusCode())
        assertEquals(400, call("POST", "$base/contracts", """{"role":"AUFTRAGGEBER","clientNumber":"A","validFrom":"2026-02-01","validTo":"2026-01-01"}""").statusCode())
        assertEquals(200, call("PUT", "$base/contracts/$contractId", """{"role":"LEISTUNGSERBRINGER","clientNumber":"AG4711","description":"Rahmen"}""").statusCode())
        assertContains(call("GET", "$base/contracts", token = viewer).body(), "\"description\":\"Rahmen\"")
        assertEquals(403, call("POST", "$base/contracts", """{"role":"AUFTRAGGEBER","clientNumber":"A"}""", viewer).statusCode())
        assertEquals(404, call("GET", "/api/v1/partners/9999/contracts").statusCode())
        assertEquals(404, call("PUT", "$base/contracts/9999", """{"role":"AUFTRAGGEBER","clientNumber":"A"}""").statusCode())

        val power = call("POST", "$base/powers-of-attorney", """{"ordererClientNumber":"B1","ordererServiceNumber":"BL1","clientNumber":"AG4711","serviceNumber":"LN1"}""")
        assertEquals(201, power.statusCode(), power.body())
        val powerId = id(power.body())
        val document = "%PDF-1.4 Testvollmacht"
        assertEquals(204, call("PUT", "$base/powers-of-attorney/$powerId/document?name=vollmacht.pdf", document, type = "application/pdf").statusCode())
        assertEquals(415, call("PUT", "$base/powers-of-attorney/$powerId/document", document, type = "text/html").statusCode())
        assertContains(call("GET", "$base/powers-of-attorney").body(), "\"documentName\":\"vollmacht.pdf\"")
        val download = call("GET", "$base/powers-of-attorney/$powerId/document")
        assertEquals(200, download.statusCode())
        assertEquals(document, download.body())
        assertContains(download.headers().firstValue("Content-Disposition").orElse(""), "vollmacht.pdf")
        assertEquals(204, call("DELETE", "$base/powers-of-attorney/$powerId").statusCode())
        assertEquals(404, call("GET", "$base/powers-of-attorney/$powerId/document").statusCode())

        assertEquals(201, call("POST", "$base/projects", """{"projectId":"PRJ-1","validTo":"2027-12-31"}""").statusCode())
        assertEquals(409, call("POST", "$base/projects", """{"projectId":"PRJ-1"}""").statusCode())
        assertContains(call("GET", "$base/projects").body(), "\"projectId\":\"PRJ-1\"")
        assertEquals(204, call("DELETE", "$base/contracts/$contractId").statusCode())
        assertEquals(404, call("DELETE", "$base/contracts/$contractId").statusCode())
    }

    @Test
    fun `products and the product change matrix with import and lookup`() {
        val p = partner("Produkt")
        val created = call("POST", "/api/v1/products", """{"role":"LEISTUNGSERBRINGER","identifier":"FTTH 100","productGroup":"FTTH"}""")
        assertEquals(201, created.statusCode(), created.body())
        val productId = id(created.body())
        assertEquals(201, call("POST", "/api/v1/products", """{"partnerId":$p,"role":"AUFTRAGGEBER","identifier":"Partnerprodukt"}""").statusCode())
        assertEquals(400, call("POST", "/api/v1/products", """{"role":"AUFTRAGGEBER","identifier":" "}""").statusCode())
        val forPartner = call("GET", "/api/v1/products?partnerId=$p", token = viewer).body()
        assertContains(forPartner, "FTTH 100")
        assertContains(forPartner, "Partnerprodukt")
        assertTrue("Partnerprodukt" !in call("GET", "/api/v1/products?role=LEISTUNGSERBRINGER").body())
        assertEquals(200, call("PUT", "/api/v1/products/$productId", """{"role":"LEISTUNGSERBRINGER","identifier":"FTTH 100","active":false}""").statusCode())
        assertContains(call("GET", "/api/v1/products/$productId").body(), "\"active\":false")

        val csv = ",FTTx 10,FTTx 20\nFTTH 10,,LAE\nFTTH 20,AEN-LMAE,\n"
        val imported = call("POST", "/api/v1/product-change-matrix/import", csv, type = "text/csv")
        assertEquals(200, imported.statusCode(), imported.body())
        assertContains(imported.body(), """{"from":"FTTH 10","to":"FTTH 20","businessCase":"LAE"}""")
        assertEquals(400, call("POST", "/api/v1/product-change-matrix/import", ",A,B\nFTTH 10,LAE,NEU", type = "text/csv").statusCode())

        assertContains(call("GET", "/api/v1/product-change-matrix/lookup?from=FTTH%2010&to=FTTH%2020").body(), "\"source\":\"DEFAULT\"")
        val own = call("PUT", "/api/v1/product-change-matrix?partnerId=$p", """[{"from":"FTTH 10","to":"FTTH 20","businessCase":"AEN-LMAE"}]""")
        assertEquals(200, own.statusCode(), own.body())
        val lookup = call("GET", "/api/v1/product-change-matrix/lookup?partnerId=$p&from=ftth%2010&to=FTTH%2020").body()
        assertContains(lookup, "\"businessCase\":\"AEN-LMAE\"")
        assertContains(lookup, "\"source\":\"PARTNER\"")
        assertContains(call("GET", "/api/v1/product-change-matrix/lookup?partnerId=$p&from=FTTH%2020&to=FTTB%2010").body(), "\"source\":\"NONE\"")
        assertEquals(400, call("PUT", "/api/v1/product-change-matrix", """[{"from":"A","to":"B","businessCase":"NEU"}]""").statusCode())
        assertEquals(400, call("PUT", "/api/v1/product-change-matrix", """[{"from":"A","to":"B","businessCase":"LAE"},{"from":"a","to":"b","businessCase":"LAE"}]""").statusCode())
        assertEquals(204, call("DELETE", "/api/v1/products/$productId").statusCode())
    }

    @Test
    fun `maintenance announcement goes to partners with contact and warns about short notice (NF-08)`() {
        val withContact = partner("Wartung", "noc@wartung.example")
        val start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val body = """{"start":"$start","end":"${start.plus(2, ChronoUnit.HOURS)}","subject":"Wartung","text":"Update auf neue Version","partnerIds":[$withContact]}"""
        assertEquals(403, call("POST", "/api/v1/maintenance-announcements", body, viewer).statusCode())
        val announced = call("POST", "/api/v1/maintenance-announcements", body)
        assertEquals(201, announced.statusCode(), announced.body())
        assertContains(announced.body(), "\"recipients\":1")
        assertContains(announced.body(), "NF-08")
        assertContains(call("GET", "/api/v1/maintenance-announcements", token = viewer).body(), "Update auf neue Version")
        assertEquals(400, call("POST", "/api/v1/maintenance-announcements", body.replace("\"end\":\"${start.plus(2, ChronoUnit.HOURS)}\"", "\"end\":\"$start\"")).statusCode())
        assertEquals(404, call("POST", "/api/v1/maintenance-announcements", body.replace("[$withContact]", "[9999]")).statusCode())
    }
}
