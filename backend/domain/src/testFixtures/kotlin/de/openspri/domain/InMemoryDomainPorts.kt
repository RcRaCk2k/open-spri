package de.openspri.domain

import de.openspri.domain.delivery.DeliveryStatus
import de.openspri.domain.delivery.Notifier
import de.openspri.domain.delivery.OutageRegistry
import de.openspri.domain.delivery.Outbox
import de.openspri.domain.delivery.OutboxEntry
import de.openspri.domain.delivery.PartnerOutage
import de.openspri.domain.order.Order
import de.openspri.domain.order.OrderHistoryEntry
import de.openspri.domain.order.OrderId
import de.openspri.domain.order.OrderRepository
import de.openspri.domain.order.OrderRole
import de.openspri.domain.order.Transactions
import de.openspri.domain.partner.PartnerId
import de.openspri.domain.query.DeliveryQueries
import de.openspri.domain.query.OrderFilter
import de.openspri.domain.query.OrderQueries
import de.openspri.domain.delivery.RequestType
import java.time.Duration
import java.time.Instant

/** In-memory ports with the same semantics as the JDBC implementations; shared by all test modules. */
class InMemoryOrderRepository : OrderRepository, OrderQueries {
    val orders = mutableListOf<Order>()
    val history = mutableListOf<OrderHistoryEntry>()

    override fun find(partnerId: PartnerId, role: OrderRole, externalOrderNumber: String) =
        orders.firstOrNull { it.partnerId == partnerId && it.role == role && it.data.externalOrderNumber == externalOrderNumber }

    override fun findAnyPartner(role: OrderRole, externalOrderNumber: String, businessCase: BusinessCase) =
        orders.filter { it.role == role && it.data.externalOrderNumber == externalOrderNumber && it.data.businessCase == businessCase }

    override fun hasOpenOrder(role: OrderRole, infrastructureKey: String, except: OrderId?) =
        orders.any { it.role == role && it.data.infrastructureKey == infrastructureKey && !it.state.finished && it.id != except }

    override fun save(order: Order): Order {
        val stored = if (order.id == null) order.copy(id = OrderId(orders.size + 1L)) else order
        orders.removeIf { it.id == stored.id }
        orders += stored
        return stored
    }

    override fun append(entry: OrderHistoryEntry) { history += entry }

    override fun get(id: OrderId) = orders.firstOrNull { it.id == id }

    override fun search(filter: OrderFilter) = orders.asSequence()
        .filter { filter.role == null || it.role == filter.role }
        .filter { filter.partnerId == null || it.partnerId == filter.partnerId }
        .filter { filter.businessCase == null || it.data.businessCase == filter.businessCase }
        .filter { filter.phase == null || it.state.phase == filter.phase }
        .filter { !filter.openOnly || !it.state.finished }
        .filter { filter.beforeId == null || it.id!!.value < filter.beforeId }
        .filter { o -> filter.text.isNullOrBlank() || listOfNotNull(o.data.externalOrderNumber, o.data.lineId, o.data.contractNumber).any { it.startsWith(filter.text) } }
        .sortedByDescending { it.id!!.value }
        .take(filter.limit.coerceIn(1, 500))
        .toList()

    override fun history(id: OrderId) = history.filter { it.orderId == id }
}

/** Outbox with the ordering semantics of the JDBC outbox: per key, only the oldest unfinished entry is due. */
class InMemoryOutbox : Outbox, DeliveryQueries {
    val entries = mutableListOf<OutboxEntry>()

    override fun enqueue(entry: OutboxEntry) = entry.copy(id = entries.size + 1L).also { entries += it }

    override fun dispatchNext(now: Instant, send: (OutboxEntry) -> OutboxEntry): Boolean {
        val due = entries.filter { it.status == DeliveryStatus.PENDING && !it.nextAttemptAt.isAfter(now) }
            .firstOrNull { candidate -> entries.none { it.sequenceKey == candidate.sequenceKey && it.id!! < candidate.id!! && it.status.blocksKey } }
            ?: return false
        val updated = send(due)
        entries[entries.indexOfFirst { it.id == due.id }] = updated
        return true
    }

    override fun get(id: Long) = entries.firstOrNull { it.id == id }

    override fun list(status: DeliveryStatus?, orderId: OrderId?, beforeId: Long?, limit: Int) = entries
        .filter { (status == null || it.status == status) && (orderId == null || it.orderId == orderId) && (beforeId == null || it.id!! < beforeId) }
        .sortedByDescending { it.id }
        .take(limit.coerceIn(1, 500))

    override fun originalOrderPayload(orderId: OrderId) = entries.firstOrNull { it.orderId == orderId && it.type == RequestType.ORDER }?.payload

    private fun change(id: Long, from: Set<DeliveryStatus>, to: (OutboxEntry) -> OutboxEntry): Boolean {
        val index = entries.indexOfFirst { it.id == id && it.status in from }
        if (index < 0) return false
        entries[index] = to(entries[index])
        return true
    }

    override fun retry(id: Long, now: Instant) =
        change(id, setOf(DeliveryStatus.UNDELIVERABLE, DeliveryStatus.UNCLEAR)) { it.copy(status = DeliveryStatus.PENDING, attempts = 0, nextAttemptAt = now) }

    override fun markSentByEmail(id: Long, now: Instant) =
        change(id, setOf(DeliveryStatus.UNDELIVERABLE, DeliveryStatus.UNCLEAR)) { it.copy(status = DeliveryStatus.SENT_BY_EMAIL, finishedAt = now) }

    override fun cancel(id: Long, now: Instant) =
        change(id, setOf(DeliveryStatus.PENDING, DeliveryStatus.UNDELIVERABLE, DeliveryStatus.UNCLEAR)) { it.copy(status = DeliveryStatus.CANCELLED, finishedAt = now) }
}

class InMemoryOutageRegistry : OutageRegistry {
    val outages = mutableMapOf<PartnerId, PartnerOutage>()

    override fun failed(partnerId: PartnerId, now: Instant, notifyEvery: Duration): PartnerOutage? {
        val outage = outages[partnerId] ?: PartnerOutage(partnerId, now, null)
        val due = outage.lastNotifiedAt == null || !outage.lastNotifiedAt.plus(notifyEvery).isAfter(now)
        outages[partnerId] = if (due) outage.copy(lastNotifiedAt = now) else outage
        return if (due) outages[partnerId] else null
    }

    override fun succeeded(partnerId: PartnerId) { outages.remove(partnerId) }
}

class RecordingNotifier : Notifier {
    val outages = mutableListOf<PartnerOutage>()
    val undeliverable = mutableListOf<OutboxEntry>()
    override fun partnerInterfaceDown(partnerId: PartnerId, outage: PartnerOutage, lastError: String?) { outages += outage }
    override fun undeliverable(entry: OutboxEntry) { undeliverable += entry }
}

object DirectTransactions : Transactions {
    override fun <T> run(block: () -> T): T = block()
}

class InMemorySettings : de.openspri.domain.settings.SettingsRepository {
    var settings: de.openspri.domain.settings.CompanySettings? = null
    var completed = false
    override fun load() = settings
    override fun save(settings: de.openspri.domain.settings.CompanySettings) { this.settings = settings }
    override fun setupCompleted() = completed
    override fun markSetupCompleted(at: Instant) { completed = true }
}

class InMemorySigningKeys : de.openspri.domain.settings.SigningKeyRepository {
    val keys = mutableListOf<de.openspri.domain.settings.StoredSigningKey>()
    override fun active() = keys.lastOrNull()
    override fun activate(key: de.openspri.domain.settings.StoredSigningKey) = key.copy(id = keys.size + 1L).also { keys += it }
}

/** Partner administration (the SOAP tests use their own in-memory PartnerDirectory). */
class InMemoryPartners : de.openspri.domain.partner.PartnerAdministration {
    val partners = mutableListOf<de.openspri.domain.partner.Partner>()
    private val certificateIds = mutableMapOf<Long, Pair<PartnerId, java.security.cert.X509Certificate>>()
    override fun list() = partners.toList()
    override fun get(id: PartnerId) = partners.firstOrNull { it.id == id }
    override fun certificates(id: PartnerId) = certificateIds.filter { it.value.first == id }.map { de.openspri.domain.partner.PartnerCertificate(it.key, it.value.second) }
    override fun create(partner: de.openspri.domain.partner.Partner): PartnerId {
        val id = PartnerId(partners.size + 1L)
        partners += partner.copy(id = id, signatureCertificates = emptyList())
        partner.signatureCertificates.forEach { addCertificate(id, it) }
        return id
    }
    override fun update(partner: de.openspri.domain.partner.Partner) { replace(partner.id) { partner.copy(signatureCertificates = it.signatureCertificates, allowedSourceNetworks = it.allowedSourceNetworks) } }
    override fun addCertificate(id: PartnerId, certificate: java.security.cert.X509Certificate): Long {
        val certId = certificateIds.size + 1L
        certificateIds[certId] = id to certificate
        replace(id) { it.copy(signatureCertificates = it.signatureCertificates + certificate) }
        return certId
    }
    override fun removeCertificate(id: PartnerId, certificateId: Long): Boolean {
        val removed = certificateIds.remove(certificateId) ?: return false
        replace(id) { it.copy(signatureCertificates = it.signatureCertificates - removed.second) }
        return true
    }
    override fun replaceNetworks(id: PartnerId, networks: List<de.openspri.domain.partner.IpNetwork>) { replace(id) { it.copy(allowedSourceNetworks = networks) } }
    private fun replace(id: PartnerId, change: (de.openspri.domain.partner.Partner) -> de.openspri.domain.partner.Partner) {
        val i = partners.indexOfFirst { it.id == id }
        if (i >= 0) partners[i] = change(partners[i])
    }
}

/** Events, feed and webhooks in memory; events are visible immediately. */
class InMemoryEvents : de.openspri.domain.event.EventLog, de.openspri.domain.event.EventFeed, de.openspri.domain.event.WebhookStore {
    val events = mutableListOf<de.openspri.domain.event.BusinessEvent>()
    val webhooks = mutableListOf<de.openspri.domain.event.WebhookSubscription>()

    @Synchronized
    override fun publish(event: de.openspri.domain.event.BusinessEvent) { events += event.copy(id = events.size + 1L) }

    @Synchronized
    override fun after(id: Long, limit: Int, types: Set<de.openspri.domain.event.EventType>?) =
        events.filter { it.id!! > id && (types.isNullOrEmpty() || it.type in types) }.take(limit)

    override fun latestId() = events.size.toLong()
    override fun purgeBefore(time: Instant) = 0

    override fun list() = webhooks.toList()
    override fun get(id: Long) = webhooks.firstOrNull { it.id == id }
    override fun create(subscription: de.openspri.domain.event.WebhookSubscription) = subscription.copy(id = webhooks.size + 1L).also { webhooks += it }
    override fun update(id: Long, url: String, types: Set<de.openspri.domain.event.EventType>, enabled: Boolean, description: String?): Boolean {
        val i = webhooks.indexOfFirst { it.id == id }
        if (i < 0) return false
        webhooks[i] = webhooks[i].copy(url = url, types = types, enabled = enabled, description = description); return true
    }
    override fun delete(id: Long) = webhooks.removeIf { it.id == id }

    @Synchronized
    override fun deliverNext(now: Instant, deliver: (de.openspri.domain.event.WebhookSubscription) -> de.openspri.domain.event.WebhookSubscription): Boolean {
        val i = webhooks.indexOfFirst { it.enabled && !it.nextAttemptAt.isAfter(now) && events.any { e -> e.id!! > it.lastEventId } }
        if (i < 0) return false
        webhooks[i] = deliver(webhooks[i]); return true
    }
}

class InMemoryIdempotencyStore : de.openspri.domain.event.IdempotencyStore {
    private data class Row(val id: Long, val hash: String, var response: de.openspri.domain.event.StoredResponse?)
    private val rows = mutableMapOf<Pair<String, String>, Row>()

    @Synchronized
    override fun claim(actor: String, key: String, requestSha256: String, now: Instant): de.openspri.domain.event.IdempotencyClaim {
        val row = rows[actor to key] ?: return Row(rows.size + 1L, requestSha256, null).also { rows[actor to key] = it }
            .let { de.openspri.domain.event.IdempotencyClaim.Claimed(it.id) }
        return when {
            row.hash != requestSha256 -> de.openspri.domain.event.IdempotencyClaim.Mismatch
            row.response == null -> de.openspri.domain.event.IdempotencyClaim.InProgress
            else -> de.openspri.domain.event.IdempotencyClaim.Replay(row.response!!)
        }
    }
    @Synchronized
    override fun complete(id: Long, response: de.openspri.domain.event.StoredResponse) { rows.values.first { it.id == id }.response = response }
    @Synchronized
    override fun release(id: Long) { rows.entries.removeIf { it.value.id == id } }
    override fun purgeBefore(time: Instant) = 0
}
