package de.openspri.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Downloads a file of the AK S&P (which must not be committed, see docs/AUFGABE-SPRI.md, chapter 14)
 * and verifies its SHA-256 checksum. Lookup order:
 * 1. local copy in docs/spec-original/ (e.g. loaded with scripts/fetch-spec.sh),
 * 2. cache in the Gradle user home (shared across builds and CI runs),
 * 3. download from ak-spri.de.
 * A file with a different checksum is rejected: a changed specification must be reviewed deliberately.
 */
@DisableCachingByDefault(because = "Downloads are cached in the Gradle user home")
abstract class VerifiedDownload : DefaultTask() {

    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Internal
    abstract val localCopyDirectory: DirectoryProperty

    @get:Internal
    abstract val cacheDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val expected = sha256.get().lowercase()
        val fileName = url.get().substringAfterLast('/')
        val target = outputFile.get().asFile.toPath()
        val cached = cacheDirectory.get().asFile.toPath().resolve("$expected-$fileName")
        val local = localCopyDirectory.get().asFile.toPath().resolve(fileName)

        val source = listOf(local, cached).firstOrNull { Files.exists(it) && checksum(it) == expected }
            ?: fetch(expected, cached)
        Files.createDirectories(target.parent)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun fetch(expected: String, cached: Path): Path {
        logger.lifecycle("Downloading ${url.get()}")
        Files.createDirectories(cached.parent)
        val temp = Files.createTempFile(cached.parent, "download", ".tmp")
        val connection = URI(url.get()).toURL().openConnection().apply {
            // ak-spri.de drops connections with the default "Java/<version>" user agent.
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        connection.getInputStream().use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
        val actual = checksum(temp)
        if (actual != expected) {
            Files.delete(temp)
            throw GradleException(
                "Checksum mismatch for ${url.get()}: expected $expected, got $actual. " +
                    "The AK S&P may have published a new version - review it and update the checksum deliberately.",
            )
        }
        Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING)
        return cached
    }

    private companion object {
        const val USER_AGENT = "open-spri-build (+https://github.com/RcRaCk2k/open-spri)"
    }

    private fun checksum(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
}
