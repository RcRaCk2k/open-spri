package de.openspri.rest.setup

import de.openspri.domain.settings.SettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Protects the setup wizard of a fresh installation (AUFGABE 5.10): until the setup is completed, the
 * setup endpoints require the one-time setup token from the container log. Nobody who reaches a freshly
 * started instance can take it over without access to the log.
 */
@Component
class SetupGuard(private val settings: SettingsRepository) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var token: String? = null

    @EventListener(ApplicationReadyEvent::class)
    fun announce() {
        if (settings.setupCompleted()) return
        val generated = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))
        token = generated
        log.warn(
            "\n\n================================================================================\n" +
                " open-spri: setup not completed.\n" +
                " Open the web interface and enter this one-time setup token:\n\n" +
                "     $generated\n\n" +
                " (REST: header X-Setup-Token, see docs/api/setup.md)\n" +
                "================================================================================\n",
        )
    }

    fun completed(): Boolean = settings.setupCompleted()

    /** Constant-time comparison; false after the setup is completed. */
    fun accepts(candidate: String?): Boolean {
        val expected = token ?: return false
        if (candidate == null || completed()) return false
        return MessageDigest.isEqual(expected.toByteArray(), candidate.toByteArray())
    }

    fun invalidate() {
        token = null
    }

    /** Only for tests: the token without reading the log. Not exposed over HTTP. */
    fun currentTokenForTests(): String? = token
}
