package de.openspri.soap

import de.openspri.domain.exchange.ArchivedExchange
import de.openspri.domain.exchange.InboundMessage
import de.openspri.domain.exchange.InboundStore
import de.openspri.domain.exchange.InboxQueue
import de.openspri.domain.exchange.PendingInboundMessage
import de.openspri.domain.message.MessageCodeCatalog
import de.openspri.domain.message.MessageCodeStore
import de.openspri.domain.message.MessageCodeTableParser
import de.openspri.domain.exchange.OutboundArchive
import de.openspri.domain.partner.Partner
import de.openspri.domain.partner.PartnerDirectory
import java.net.InetAddress

class InMemoryPartnerDirectory(vararg partners: Partner) : PartnerDirectory {
    val partners = partners.toMutableList()
    override fun findBySourceAddress(address: InetAddress) = partners.filter { p -> p.enabled && p.allowedSourceNetworks.any { address in it } }
    override fun findById(id: de.openspri.domain.partner.PartnerId) = partners.firstOrNull { it.id == id }
}

class InMemoryInboundStore : InboundStore, InboxQueue {
    val accepted = mutableListOf<Pair<ArchivedExchange, InboundMessage>>()
    private var processed = 0
    val findings = mutableListOf<List<String>>()

    /** Empties the store including the inbox position (tests run in any order). */
    fun clear() {
        accepted.clear(); rejected.clear(); findings.clear(); processed = 0
    }

    override fun processNext(handler: (PendingInboundMessage) -> List<String>): Boolean {
        if (processed >= accepted.size) return false
        val (_, message) = accepted[processed]
        processed++
        findings += handler(PendingInboundMessage(processed.toLong(), processed.toLong(), message))
        return true
    }
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

/** Starts with the test table samples/message-codes.csv (own texts, neutral CSV format). */
class InMemoryMessageCodeStore : MessageCodeStore {
    var active: MessageCodeCatalog? = MessageCodeTableParser.parse(TestData.sample("message-codes.csv"), "message-codes.csv")
    override fun importAndActivate(content: ByteArray, source: String) = MessageCodeTableParser.parse(content, source).also { active = it }
    override fun active() = active
}
