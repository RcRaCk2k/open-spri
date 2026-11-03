package de.openspri.soap.config

import de.openspri.soap.InboundProcessor
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.ByteArrayInputStream
import java.net.InetAddress

/**
 * Guarantees a TEQ for every S/PRI request (specification 11.2.3.1, ADR-0005), also when CXF cannot read it.
 *
 * CXF parses the SOAP envelope before [de.openspri.soap.SpriEndpoint] is called. A request with a DTD, broken XML or
 * an unknown SOAPAction would end in a SOAP fault (HTTP 500), unarchived. This filter reads the request once, hands
 * the bytes to CXF and, if CXF failed before our endpoint ran, lets [InboundProcessor] answer instead: the request is
 * archived and answered with the matching TEQ (usually 0995). Requests above [maxBytes] are not read further; their
 * beginning is archived and answered with 0995.
 */
class SpriRequestFilter(private val processor: InboundProcessor, private val maxBytes: Int) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest) = request.method != "POST"

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val input = request.inputStream
        val body = input.readNBytes(maxBytes + 1)
        val remote = InetAddress.getByName(request.remoteAddr)
        val soapAction = request.getHeader("SOAPAction")
        if (body.size > maxBytes) {
            log.warn("S/PRI request from {} exceeds {} bytes", request.remoteAddr, maxBytes)
            return write(response, processor.process(body.copyOf(ARCHIVED_PREFIX), remote, soapAction, oversizedLimit = maxBytes))
        }

        val buffered = ContentCachingResponseWrapper(response)
        chain.doFilter(CachedBodyRequest(request, body), buffered)
        if (request.getAttribute(PROCESSED) == null && buffered.status >= 400) {
            log.info("S/PRI request from {} not readable by CXF (HTTP {}); answered by the entry check", request.remoteAddr, buffered.status)
            buffered.resetBuffer()
            return write(response, processor.process(body, remote, soapAction))
        }
        buffered.copyBodyToResponse()
    }

    private fun write(response: HttpServletResponse, result: InboundProcessor.Result) {
        when (result) {
            is InboundProcessor.Result.Answered -> {
                response.reset()
                response.status = HttpServletResponse.SC_OK
                response.contentType = "text/xml; charset=UTF-8"
                response.setContentLength(result.response.size)
                response.outputStream.write(result.response)
            }
            InboundProcessor.Result.Forbidden -> {
                response.reset()
                response.status = HttpServletResponse.SC_FORBIDDEN
            }
        }
    }

    private class CachedBodyRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
        override fun getContentLength() = body.size
        override fun getContentLengthLong() = body.size.toLong()
        override fun getInputStream(): ServletInputStream {
            val input = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read() = input.read()
                override fun read(b: ByteArray, off: Int, len: Int) = input.read(b, off, len)
                override fun isFinished() = input.available() == 0
                override fun isReady() = true
                override fun setReadListener(listener: ReadListener) = throw UnsupportedOperationException()
            }
        }
    }

    companion object {
        /** Request attribute set by the endpoint once [InboundProcessor] has handled the request. */
        const val PROCESSED = "openspri.spri.processed"

        /** How much of an oversized request is archived. */
        const val ARCHIVED_PREFIX = 64 * 1024
    }
}
