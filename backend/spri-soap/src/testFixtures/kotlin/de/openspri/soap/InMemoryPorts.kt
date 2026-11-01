package de.openspri.soap

import de.openspri.domain.exchange.ArchivedExchange
import de.openspri.domain.exchange.InboundMessage
import de.openspri.domain.exchange.InboundStore
import de.openspri.domain.exchange.OutboundArchive
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerDirectory
import java.net.InetAddress

class InMemoryPartnerDirectory(vararg partners: Partner) : PartnerDirectory {
    val partners = partners.toMutableList()
    override fun findBySourceAddress(address: InetAddress) = partners.filter { p -> p.enabled && p.allowedSourceNetworks.any { address in it } }
}

class InMemoryInboundStore : InboundStore {
    val accepted = mutableListOf<Pair<ArchivedExchange, InboundMessage>>()
    val rejected = mutableListOf<ArchivedExchange>()
    var failure: Exception? = null

    override fun storeAccepted(exchange: ArchivedExchange, message: InboundMessage): Long {
        failure?.let { throw it }
        accepted += exchange to message
        return accepted.size.toLong()
    }

    override fun storeRejected(exchange: ArchivedExchange): Long {
        rejected += exchange
        return 1000L + rejected.size
    }
}

class InMemoryOutboundArchive : OutboundArchive {
    val exchanges = mutableListOf<ArchivedExchange>()
    override fun store(exchange: ArchivedExchange): Long { exchanges += exchange; return exchanges.size.toLong() }
}
