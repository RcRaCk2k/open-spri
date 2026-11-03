package de.openspri.soap.config

import de.openspri.domain.privacy.PersonalDataStore
import de.openspri.domain.privacy.RetentionService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration

/**
 * Retention of end customer data (NF-10, ADR-0010): `OPENSPRI_RETENTION_PERSONAL_DATA` (e.g. `730d`) after completion,
 * message contents are removed daily at `OPENSPRI_RETENTION_CRON`. `0` (default) keeps everything.
 */
@Configuration(proxyBeanMethods = false)
class RetentionConfiguration {

    @Bean
    fun retentionJob(
        store: ObjectProvider<PersonalDataStore>,
        @Value("\${openspri.retention.personal-data:0}") retention: Duration,
    ) = RetentionJob(store.ifAvailable?.let { RetentionService(it, retention) })

    class RetentionJob(val service: RetentionService?) {
        private val log = LoggerFactory.getLogger(javaClass)

        init {
            if (service?.enabled != true) log.info("Retention of end customer data is disabled (OPENSPRI_RETENTION_PERSONAL_DATA)")
        }

        @Scheduled(cron = "\${openspri.retention.cron:0 30 2 * * *}")
        fun run() {
            try {
                val result = service?.run() ?: return
                log.info(
                    "Retention: removed {} archived requests, {} inbox and {} outbox payloads, {} drafts, {} address keys",
                    result.archivedRequests, result.inboxPayloads, result.outboxPayloads, result.drafts, result.orders,
                )
            } catch (e: Exception) {
                log.error("Retention run failed", e)
            }
        }
    }
}
