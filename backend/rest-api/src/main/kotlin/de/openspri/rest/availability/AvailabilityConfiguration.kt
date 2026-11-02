package de.openspri.rest.availability

import de.openspri.domain.availability.AvailabilitySites
import de.openspri.domain.availability.ReplikatExportState
import de.openspri.domain.availability.ReplikatReleases
import de.openspri.domain.availability.ReplikatSnapshots
import de.openspri.domain.settings.SettingsRepository
import de.openspri.replikat.ReplikatExporter
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock

/**
 * Replikat export (AUFGABE 5.4): daily at `openspri.replikat.export-cron` (default 03:00 local time), so that the
 * Replikat is ready for download before 06:00 (Replikat 3.1). The carrier code is the one of our company (setup).
 */
@Configuration(proxyBeanMethods = false)
class AvailabilityConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemDefaultZone()

    @Bean
    fun replikatExporter(
        sites: AvailabilitySites,
        releases: ReplikatReleases,
        snapshots: ReplikatSnapshots,
        state: ReplikatExportState,
        settings: SettingsRepository,
        clock: Clock,
    ) = ReplikatExporter(sites, releases, snapshots, state, { settings.load()?.carrierCode ?: error("Firmendaten fehlen (Ersteinrichtung)") }, clock)

    @Bean
    fun replikatScheduler(exporter: ReplikatExporter) = ReplikatScheduler(exporter)

    class ReplikatScheduler(private val exporter: ReplikatExporter) {
        private val log = LoggerFactory.getLogger(javaClass)

        @Scheduled(cron = "\${openspri.replikat.export-cron:0 0 3 * * *}")
        fun export() {
            val results = exporter.exportAll()
            results.filter { it.error != null }.forEach { log.error("Replikat für Partner {} nicht erzeugt: {}", it.partnerId.value, it.error) }
        }
    }
}
