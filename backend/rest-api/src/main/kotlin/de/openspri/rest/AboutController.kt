package de.openspri.rest

import de.openspri.domain.InterfaceVersion
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Version and build information, including whether this instance runs a certified build (chapter 3.2). */
@RestController
@RequestMapping("/api/v1/about")
@Tag(name = "Betrieb", description = "Version, Build und Zustand der Instanz")
class AboutController(
    private val buildProperties: ObjectProvider<BuildProperties>,
    private val certification: CertificationProperties,
) {

    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Version und Build der Instanz",
        description = "Liefert Softwareversion, implementierte S/PRI-Version und ob die Instanz in einem " +
            "zertifizierten Build läuft. Der Aufruf ist ohne Anmeldung möglich.",
    )
    fun about(): AboutResponse {
        val digest = certification.buildDigest?.takeIf { it.isNotBlank() }
        return AboutResponse(
            name = "open-spri",
            version = buildProperties.ifAvailable?.version ?: "unknown",
            spriVersion = InterfaceVersion.CURRENT.toString(),
            buildDigest = digest,
            certifiedBuild = digest != null && digest in certification.certifiedDigests,
        )
    }
}

@Schema(description = "Version und Build der Instanz")
data class AboutResponse(
    @field:Schema(description = "Produktname", example = "open-spri", requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,
    @field:Schema(description = "Softwareversion (SemVer)", example = "1.0.0", requiredMode = Schema.RequiredMode.REQUIRED)
    val version: String,
    @field:Schema(description = "Implementierte S/PRI-Version (Major.Minor)", example = "4.4", requiredMode = Schema.RequiredMode.REQUIRED)
    val spriVersion: String,
    @field:Schema(description = "Digest des laufenden Container-Images, falls bekannt", example = "sha256:3f1c…")
    val buildDigest: String?,
    @field:Schema(
        description = "true, wenn buildDigest zu einem Build gehört, der den Konformitätstest bestanden hat",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val certifiedBuild: Boolean,
)
