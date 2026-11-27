package com.netquality.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DnsAnswer(val type: String, val value: String, val ttl: Long)
data class DnsResult(val type: String, val status: Int?, val answers: List<DnsAnswer>, val error: String? = null)

object DnsLookup {
    private val recordTypes = listOf("A", "AAAA", "MX", "NS", "TXT", "CNAME", "SOA", "SRV", "CAA")
    fun supportedTypes(): List<String> = recordTypes

    fun query(domain: String, type: String): DnsResult {
        val clean = domain.trim().trimEnd('.').lowercase()
        if (clean.isBlank() || clean.any { it.isWhitespace() }) return DnsResult(type, null, emptyList(), "نام دامنه معتبر نیست")
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val endpoints = listOf(
            "https://cloudflare-dns.com/dns-query?name=$encoded&type=$type",
            "https://dns.google/resolve?name=$encoded&type=$type"
        )
        var lastError = "DNS server unavailable"
        for ((index, endpoint) in endpoints.withIndex()) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", if (index == 0) "application/dns-json" else "application/json")
                    setRequestProperty("User-Agent", "NetQuality/2.3")
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalStateException("HTTP $code")
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val json = JSONObject(body)
                val status = json.optInt("Status", -1)
                val arr = json.optJSONArray("Answer")
                val answers = buildList {
                    if (arr != null) for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        add(DnsAnswer(type, item.optString("data", "—"), item.optLong("TTL", 0)))
                    }
                }
                return DnsResult(type, status, answers)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            } finally {
                conn?.disconnect()
            }
        }
        return DnsResult(type, null, emptyList(), "خطای هر دو سرویس DNS: $lastError")
    }

    fun queryAll(domain: String): List<DnsResult> = recordTypes.map { query(domain, it) }
}
