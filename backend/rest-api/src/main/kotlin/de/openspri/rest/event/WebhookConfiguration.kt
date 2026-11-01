package de.openspri.rest.event

import de.openspri.domain.event.EventFeed
import de.openspri.domain.event.IdempotencyStore
import de.openspri.domain.event.WebhookDispatcher
import de.openspri.domain.event.WebhookStore
import de.openspri.domain.event.WebhookSubscription
import de.openspri.domain.event.BusinessEvent
import de.openspri.soap.config.MasterKey
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sends one event as JSON (body = [EventDto]) and signs it:
 * `X-OpenSpri-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<body>")>`. Only 2xx counts as delivered;
 * redirects are not followed.
 */
class WebhookPoster(private val masterKey: MasterKey, private val json: JsonMapper, private val timeout: Duration = Duration.ofSeconds(10)) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()

    fun post(subscription: WebhookSubscription, event: BusinessEvent): String? {
        val encryption = masterKey.encryption ?: return "OPENSPRI_MASTER_KEY fehlt"
        val secret = encryption.decrypt(subscription.encryptedSecret)
        val body = json.writeValueAsBytes(EventDto.of(event))
        val timestamp = Instant.now().epochSecond
        val request = HttpRequest.newBuilder(URI(subscription.url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("User-Agent", "open-spri-webhook")
            .header("X-OpenSpri-Event", event.type.wireName)
            .header("X-OpenSpri-Event-Id", event.id.toString())
            .header("X-OpenSpri-Signature", "t=$timestamp,v1=${signature(secret, timestamp, body)}")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        return if (response.statusCode() in 200..299) null else "HTTP ${response.statusCode()}"
    }

    companion object {
        fun signature(secret: ByteArray, timestamp: Long, body: ByteArray): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
            mac.update("$timestamp.".toByteArray())
            return mac.doFinal(body).joinToString("") { "%02x".format(it) }
        }
    }
}

@Configuration(proxyBeanMethods = false)
class WebhookConfiguration {

    @Bean
    fun webhookPoster(masterKey: MasterKey, json: JsonMapper) = WebhookPoster(masterKey, json)

    @Bean
    fun webhookDispatcher(store: WebhookStore, feed: EventFeed, poster: WebhookPoster) = WebhookDispatcher(store, feed, poster::post)

    @Bean
    fun webhookScheduler(
        dispatcher: WebhookDispatcher,
        idempotency: IdempotencyStore,
        feed: EventFeed,
        @Value("\${openspri.idempotency.retention:24h}") idempotencyRetention: Duration,
        @Value("\${openspri.events.retention:90d}") eventRetention: Duration,
    ) = WebhookScheduler(dispatcher, idempotency, feed, idempotencyRetention, eventRetention)

    class WebhookScheduler(
        private val dispatcher: WebhookDispatcher,
        private val idempotency: IdempotencyStore,
        private val feed: EventFeed,
        private val idempotencyRetention: Duration,
        private val eventRetention: Duration,
    ) {
        private val log = LoggerFactory.getLogger(javaClass)

        @Scheduled(fixedDelayString = "\${openspri.webhooks.poll-interval:2s}", initialDelayString = "\${openspri.webhooks.initial-delay:10s}")
        fun deliver() {
            try {
                dispatcher.drain()
            } catch (e: Exception) {
                log.error("Webhook delivery failed", e)
            }
        }

        @Scheduled(fixedDelayString = "PT1H", initialDelayString = "\${openspri.webhooks.initial-delay:10s}")
        fun purge() {
            try {
                idempotency.purgeBefore(Instant.now().minus(idempotencyRetention))
                feed.purgeBefore(Instant.now().minus(eventRetention))
            } catch (e: Exception) {
                log.error("Purging idempotency keys or events failed", e)
            }
        }
    }
}
