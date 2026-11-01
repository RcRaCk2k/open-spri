package de.openspri.domain.partner

import de.openspri.domain.delivery.Notifier
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * "Wartung ankündigen" (NF-08, specification 11.5): partners are informed by e-mail five working days ahead.
 * A shorter notice is possible (e.g. an urgent fix) but reported as a warning. Working days are Monday to
 * Friday; public holidays are not considered because they differ between federal states.
 */
class MaintenanceService(
    private val partners: PartnerAdministration,
    private val notifier: Notifier,
    private val store: MaintenanceAnnouncements,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun announce(start: Instant, end: Instant, subject: String, text: String, partnerIds: List<PartnerId>?, actor: String): MaintenanceAnnouncement {
        require(end.isAfter(start)) { "end: das Ende muss nach dem Beginn liegen" }
        require(subject.isNotBlank() && text.isNotBlank()) { "subject und text dürfen nicht leer sein" }
        val recipients = partnerIds?.map { id -> partners.get(id) ?: throw NoSuchElementException("Partner ${id.value} existiert nicht") }
            ?: partners.list().filter { it.enabled }
        val warnings = mutableListOf<String>()
        val today = LocalDate.now(clock)
        val startDay = LocalDate.ofInstant(start, clock.zone)
        if (workingDaysBetween(today, startDay) < NOTICE_WORKING_DAYS) {
            warnings += "NF-08: Die Wartung beginnt in weniger als $NOTICE_WORKING_DAYS Werktagen (Spezifikation 11.5)"
        }
        val draft = MaintenanceAnnouncement(null, start, end, subject.trim(), text.trim(), recipients.map { it.id }, clock.instant(), actor)
        var sent = 0
        recipients.forEach { partner ->
            if (notifier.maintenance(partner, draft)) sent++ else warnings += "Partner ${partner.name} hat keine Kontaktadresse"
        }
        return store.save(draft.copy(recipients = sent, warnings = warnings))
    }

    companion object {
        const val NOTICE_WORKING_DAYS = 5

        /** Monday to Friday strictly between [from] and [to]. */
        fun workingDaysBetween(from: LocalDate, to: LocalDate): Int =
            generateSequence(from.plusDays(1)) { it.plusDays(1) }.takeWhile { it.isBefore(to) }
                .count { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
    }
}
