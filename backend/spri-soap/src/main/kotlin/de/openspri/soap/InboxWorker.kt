package de.openspri.soap

import de.openspri.domain.exchange.InboxQueue
import de.openspri.domain.exchange.PendingInboundMessage
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.order.InboundOrderService
import org.slf4j.LoggerFactory

/**
 * Business processing of the inbound queue: maps each accepted order/message to the domain and hands it to
 * [InboundOrderService]. Runs single-threaded in arrival order (multi-instance operation follows in M3).
 */
class InboxWorker(private val queue: InboxQueue, private val service: InboundOrderService) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Processes all pending items; returns the number processed. */
    fun drain(limit: Int = 500): Int {
        var processed = 0
        while (processed < limit && queue.processNext(::handle)) processed++
        return processed
    }

    private fun handle(item: PendingInboundMessage): List<String> {
        val payload = SecureXml.parse(item.message.payload).documentElement
        val result = when (item.message.operation) {
            SoapOperation.ANNEHMEN_AUFTRAG -> service.receiveOrder(item.message.partnerId, SpriPayloadMapper.order(payload), item.archiveId)
            SoapOperation.ANNEHMEN_MELDUNG -> {
                val message = SpriPayloadMapper.message(payload) ?: return listOf("Meldungstyp wird von S/PRI V4.4 nicht verwendet")
                service.receiveMessage(item.message.partnerId, message, item.archiveId)
            }
        }
        if (result.findings.isNotEmpty()) log.info("Inbox {}: {}", item.id, result.findings)
        return result.findings
    }
}
