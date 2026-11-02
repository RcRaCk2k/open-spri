package de.openspri.rest.availability

import de.openspri.domain.availability.AvailabilitySites
import de.openspri.domain.availability.ReplikatExportState
import de.openspri.domain.availability.ReplikatReleases
import de.openspri.domain.availability.ReplikatSnapshots
import de.openspri.domain.settings.SettingsRepository
import de.openspri.domain.availability.AvailabilityProvider
import de.openspri.domain.availability.AvailabilitySearch
import de.openspri.domain.availability.AvailabilitySource
import de.openspri.domain.availability.AvailabilitySources
import de.openspri.domain.availability.ImportStatus
import de.openspri.domain.availability.PartnerLocations
import de.openspri.domain.availability.ReplikatImports
import de.openspri.domain.availability.SourceKind
import de.openspri.domain.partner.PartnerAdministration
import de.openspri.replikat.OnlineProvider
import de.openspri.replikat.ReplikatExporter
import de.openspri.replikat.ReplikatImporter
import de.openspri.replikat.ReplikatProvider
import de.openspri.soap.config.MasterKey
import java.util.Base64
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
    fun replikatImporter(locations: PartnerLocations, imports: ReplikatImports, clock: Clock) = ReplikatImporter(locations, imports, clock = clock)

    @Bean
    fun sourceSecrets(masterKey: MasterKey) = SourceSecrets(masterKey)

    /** AUFGABE 5.4: "Replikat (lokal)" and "Online-Abfrage gegen eine Instanz dieser Software"; further adapters as beans. */
    @Bean
    fun replikatProvider(locations: PartnerLocations): AvailabilityProvider = ReplikatProvider(locations)

    @Bean
    fun onlineProvider(): AvailabilityProvider = OnlineProvider()

    @Bean
    fun availabilitySearch(sources: AvailabilitySources, partners: PartnerAdministration, providers: List<AvailabilityProvider>, secrets: SourceSecrets) =
        AvailabilitySearch(DecryptingSources(sources, secrets), partners::get, providers)

    @Bean
    fun replikatScheduler(exporter: ReplikatExporter, importer: ReplikatImporter, sources: AvailabilitySources, partners: PartnerAdministration, secrets: SourceSecrets) =
        ReplikatScheduler(exporter, importer, sources, partners, secrets)

    class ReplikatScheduler(
        private val exporter: ReplikatExporter,
        private val importer: ReplikatImporter,
        private val sources: AvailabilitySources,
        private val partners: PartnerAdministration,
        private val secrets: SourceSecrets,
    ) {
        private val log = LoggerFactory.getLogger(javaClass)

        @Scheduled(cron = "\${openspri.replikat.export-cron:0 0 3 * * *}")
        fun export() {
            val results = exporter.exportAll()
            results.filter { it.error != null }.forEach { log.error("Replikat für Partner {} nicht erzeugt: {}", it.partnerId.value, it.error) }
        }

        /** Import of the partners' Replikate (Auftraggeber); they are due at 06:00 (Replikat 3.1). */
        @Scheduled(cron = "\${openspri.replikat.import-cron:0 0 7 * * *}")
        fun import() {
            sources.list().filter { it.enabled && it.kind == SourceKind.REPLIKAT }.forEach { source ->
                val partner = partners.get(source.partnerId)?.takeIf { it.enabled } ?: return@forEach
                val result = importer.run(partner, secrets.plain(source))
                if (result.status == ImportStatus.FAILED) log.error("Replikat von {} nicht importiert: {}", partner.name, result.error)
            }
        }
    }

    /** Credentials of the sources are stored encrypted with the master key (like webhook secrets). */
    class SourceSecrets(private val masterKey: MasterKey) {
        fun encrypt(plain: String): String {
            val encryption = masterKey.encryption ?: throw IllegalStateException("Zugangsdaten von Quellen brauchen OPENSPRI_MASTER_KEY")
            return Base64.getEncoder().encodeToString(encryption.encrypt(plain.toByteArray()))
        }

        fun plain(source: AvailabilitySource): AvailabilitySource = source.copy(
            secret = source.secret?.let { stored ->
                val encryption = masterKey.encryption ?: throw IllegalStateException("OPENSPRI_MASTER_KEY fehlt")
                String(encryption.decrypt(Base64.getDecoder().decode(stored)))
            },
        )
    }

    /** The search gets the sources with their credentials in plain text, only in memory. */
    class DecryptingSources(private val sources: AvailabilitySources, private val secrets: SourceSecrets) : AvailabilitySources by sources {
        override fun list() = sources.list().map(secrets::plain)
    }
}
