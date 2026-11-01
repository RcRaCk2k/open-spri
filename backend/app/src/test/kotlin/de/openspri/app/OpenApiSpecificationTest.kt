package de.openspri.app

import openspri.testapps.SoapInMemoryTestApplication
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.SerializationFeature
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * API-first check (CLAUDE.md, principle 4): the committed docs/api/openapi.json must match what the
 * application actually serves. Regenerate with `./gradlew :app:test -PupdateOpenApi`.
 */
@SpringBootTest(classes = [SoapInMemoryTestApplication::class], properties = ["openspri.inbox.initial-delay=1h", "openspri.outbox.initial-delay=1h"])
@AutoConfigureMockMvc
class OpenApiSpecificationTest(@Autowired private val mockMvc: MockMvc) {

    private val mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()

    @Test
    fun `committed OpenAPI specification is up to date`() {
        val served = mockMvc.get("/api/v1/openapi.json").andReturn().response.contentAsString
        val normalized = mapper.writeValueAsString(mapper.readTree(served)) + "\n"
        val file = Path.of(System.getProperty("openspri.repoRoot", "../.."), "docs/api/openapi.json")

        if (System.getProperty("openspri.updateOpenApi").toBoolean()) {
            Files.createDirectories(file.parent)
            Files.writeString(file, normalized)
        }
        assertEquals(
            if (Files.exists(file)) Files.readString(file) else "<missing>",
            normalized,
            "docs/api/openapi.json is outdated - run ./gradlew :app:test -PupdateOpenApi",
        )
    }
}
