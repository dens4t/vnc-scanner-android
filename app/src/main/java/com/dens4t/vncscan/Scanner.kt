package com.dens4t.vncscan

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class ScanTarget(val ip: String, val port: Int)

data class ScanResult(
    val ip: String,
    val port: Int,
    val banner: String?,
    val latencyMs: Long,
    val vncConfirmed: Boolean,
    val hostname: String? = null
)

data class ScanProgress(val done: Int, val total: Int, val found: Int)

class VncScanner {
    suspend fun scan(
        targets: List<ScanTarget>,
        connectTimeoutMs: Int,
        concurrency: Int,
        retries: Int,
        resolveNames: Boolean,
        onFound: (ScanResult) -> Unit = {},
        onProgress: (ScanProgress) -> Unit = {}
    ): List<ScanResult> {
        if (targets.isEmpty()) return emptyList()

        val total = targets.size
        val workers = concurrency.coerceIn(1, 512)
        val semaphore = Semaphore(workers)
        val done = AtomicInteger(0)
        val found = AtomicInteger(0)
        val results = Collections.synchronizedList(mutableListOf<ScanResult>())
        val dispatcher = Executors.newFixedThreadPool(workers).asCoroutineDispatcher()

        try {
            coroutineScope {
                val jobs = targets.map { target ->
                    async(dispatcher) {
                        semaphore.withPermit {
                            val result = probe(target, connectTimeoutMs, retries)
                            if (result != null) {
                                results.add(result)
                                found.incrementAndGet()
                                onFound(result)
                            }
                            val current = done.incrementAndGet()
                            if (current == 1 || current % 16 == 0 || current == total) {
                                onProgress(ScanProgress(current, total, found.get()))
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
        } finally {
            dispatcher.close()
        }

        val sorted = results.sortedWith(
            compareBy({ runCatching { IpMath.ipToLong(it.ip) }.getOrDefault(0L) }, { it.port })
        )

        if (!resolveNames) return sorted

        val uniqueIps = sorted.map { it.ip }.distinct()
        val names = Collections.synchronizedMap(mutableMapOf<String, String>())
        val nameDispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        try {
            coroutineScope {
                uniqueIps.map { ip ->
                    async(nameDispatcher) {
                        NetworkDetector.reverseLookup(ip)?.let { names[ip] = it }
                    }
                }.awaitAll()
            }
        } finally {
            nameDispatcher.close()
        }

        return sorted.map { result ->
            names[result.ip]?.let { result.copy(hostname = it) } ?: result
        }
    }

    private fun probe(target: ScanTarget, timeoutMs: Int, retries: Int): ScanResult? {
        val timeout = timeoutMs.coerceIn(50, 5000)
        val attempts = retries.coerceIn(0, 3) + 1

        repeat(attempts) {
            val started = System.nanoTime()
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(target.ip, target.port), timeout)
                    val latency = (System.nanoTime() - started) / 1_000_000
                    val banner = readBanner(socket)
                    return ScanResult(
                        ip = target.ip,
                        port = target.port,
                        banner = banner,
                        latencyMs = latency,
                        vncConfirmed = banner?.startsWith("RFB") == true
                    )
                }
            } catch (_: Exception) {
                // Port closed, filtered, or timed out. Retry if attempts remain.
            }
        }
        return null
    }

    private fun readBanner(socket: Socket): String? {
        return try {
            socket.soTimeout = 450
            val buffer = ByteArray(32)
            val read = socket.getInputStream().read(buffer)
            if (read > 0) {
                String(buffer, 0, read, Charsets.US_ASCII)
                    .replace(Regex("[^\\x20-\\x7E]"), "")
                    .trim()
                    .ifBlank { null }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
