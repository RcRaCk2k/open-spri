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
import java.time.Duration
import java.time.Instant

/** In-memory ports with the same semantics as the JDBC implementations; shared by all test modules. */
class InMemoryOrderRepository : OrderRepository {
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
}

/** Outbox with the ordering semantics of the JDBC outbox: per key, only the oldest unfinished entry is due. */
class InMemoryOutbox : Outbox {
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
