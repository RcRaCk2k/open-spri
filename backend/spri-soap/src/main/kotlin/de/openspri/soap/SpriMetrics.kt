package de.openspri.soap

import de.openspri.domain.exchange.SoapOperation
import java.time.Duration

/**
 * Observations of the S/PRI adapter for monitoring (NF-11): TEQ latency of inbound requests and the outcome of
 * outbound attempts per partner. The implementation with Micrometer lives in the Spring configuration.
 */
interface SpriMetrics {

    /**
     * An inbound request was answered. [teq] is the TEQ code (`OK`, `0995`, …) or `forbidden` for a refused address;
     * [duration] runs from receipt to the stored answer.
     */
    fun inbound(partnerId: Long?, operation: SoapOperation?, teq: String, duration: Duration)

    /** An outbound attempt ended with [result] (`acknowledged`, `no_answer`, `not_reached`, `invalid`). */
    fun outbound(partnerId: Long, operation: SoapOperation, result: String, teq: String?, duration: Duration)

    companion object {
        val NONE = object : SpriMetrics {
            override fun inbound(partnerId: Long?, operation: SoapOperation?, teq: String, duration: Duration) = Unit
            override fun outbound(partnerId: Long, operation: SoapOperation, result: String, teq: String?, duration: Duration) = Unit
        }
    }
}
