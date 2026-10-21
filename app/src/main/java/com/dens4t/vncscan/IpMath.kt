package com.dens4t.vncscan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

data class LocalNetwork(
    val ip: String,
    val prefix: Int,
    val start: Long,
    val end: Long,
    val interfaceName: String?
) {
    val cidr: String get() = "$ip/$prefix"
    val hostCount: Long get() = (end - start + 1).coerceAtLeast(0)
    val startIp: String get() = IpMath.longToIp(start)
    val endIp: String get() = IpMath.longToIp(end)
}

object IpMath {
    fun ipToLong(ip: String): Long {
        val parts = ip.trim().split('.')
        require(parts.size == 4) { "IP tidak valid: $ip" }
        var value = 0L
        for (part in parts) {
            val octet = part.toInt()
            require(octet in 0..255) { "IP tidak valid: $ip" }
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    fun longToIp(value: Long): String {
        return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }

    fun parseCidr(input: String): Pair<Long, Long>? {
        val text = input.trim()
        val match = Regex("^(\\d{1,3}(?:\\.\\d{1,3}){3})(?:/(\\d{1,2}))?$").find(text) ?: return null
        val ip = runCatching { ipToLong(match.groupValues[1]) }.getOrNull() ?: return null
        val prefix = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 32
        if (prefix !in 0..32) return null
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = ip and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        var start = network + 1
        var end = broadcast - 1
        if (prefix >= 31) {
            start = ip
            end = ip
        }
        if (end < start) return null
        return start to end
    }

    fun parseRange(input: String): Pair<Long, Long>? {
        val text = input.trim()
        val full = Regex("^(\\d{1,3}(?:\\.\\d{1,3}){3})\\s*-\\s*(\\d{1,3}(?:\\.\\d{1,3}){3})$").find(text)
        if (full != null) {
            val a = runCatching { ipToLong(full.groupValues[1]) }.getOrNull() ?: return null
            val b = runCatching { ipToLong(full.groupValues[2]) }.getOrNull() ?: return null
            if (a > b) return null
            return a to b
        }
        val short = Regex("^(\\d{1,3}(?:\\.\\d{1,3}){3})\\s*-\\s*(\\d{1,3})$").find(text)
        if (short != null) {
            val base = short.groupValues[1]
            val last = short.groupValues[2].toIntOrNull() ?: return null
            if (last !in 0..255) return null
            val prefix = base.substringBeforeLast('.') + "."
            val a = runCatching { ipToLong(base) }.getOrNull() ?: return null
            val b = runCatching { ipToLong(prefix + last) }.getOrNull() ?: return null
            if (a > b) return null
            return a to b
        }
        val single = runCatching { ipToLong(text) }.getOrNull() ?: return null
        return single to single
    }

    fun parseRangeOrCidr(input: String): Pair<Long, Long>? {
        val text = input.trim()
        if (text.isEmpty()) return null
        return if (text.contains('/')) parseCidr(text) else parseRange(text)
    }

    fun parsePorts(input: String): List<Int>? {
        val result = sortedSetOf<Int>()
        val tokens = input.split(',', ' ', '\n').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        for (raw in tokens) {
            val token = raw.trim()
            if (token.contains('-')) {
                val pieces = token.split('-', limit = 2)
                val start = pieces[0].trim().toIntOrNull() ?: return null
                val end = pieces[1].trim().toIntOrNull() ?: return null
                if (start !in 1..65535 || end !in 1..65535 || start > end || end - start > 64) return null
                for (port in start..end) result.add(port)
            } else {
                val port = token.toIntOrNull() ?: return null
                if (port !in 1..65535) return null
                result.add(port)
            }
        }
        if (result.isEmpty() || result.size > 64) return null
        return result.toList()
    }
}

object NetworkDetector {
    fun detect(context: Context): LocalNetwork? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = manager.activeNetwork ?: return null
        val properties = manager.getLinkProperties(network) ?: return null
        val capabilities = manager.getNetworkCapabilities(network)
        val preferWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val candidates = properties.linkAddresses.mapNotNull { link ->
            val address = link.address
            if (address !is Inet4Address) return@mapNotNull null
            if (address.isLoopbackAddress || address.isLinkLocalAddress) return@mapNotNull null
            val ip = address.hostAddress ?: return@mapNotNull null
            val prefix = link.prefixLength
            val range = runCatching {
                val value = IpMath.ipToLong(ip)
                val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                val start = if (prefix >= 31) value else (value and mask) + 1
                val end = if (prefix >= 31) value else (value or (mask.inv() and 0xFFFFFFFFL)) - 1
                start to end
            }.getOrNull() ?: return@mapNotNull null
            if (range.second < range.first) return@mapNotNull null
            LocalNetwork(ip, prefix, range.first, range.second, properties.interfaceName)
        }

        if (candidates.isEmpty()) return null
        if (preferWifi) {
            candidates.firstOrNull { it.ip.startsWith("192.168.") || it.ip.startsWith("10.") || it.ip.startsWith("172.") }
                ?.let { return it }
        }
        return candidates.firstOrNull { !it.ip.startsWith("100.") } ?: candidates.first()
    }

    fun reverseLookup(ip: String): String? {
        return runCatching {
            val host = InetAddress.getByName(ip).canonicalHostName
            host.takeIf { it.isNotBlank() && it != ip }
        }.getOrNull()
    }
}
