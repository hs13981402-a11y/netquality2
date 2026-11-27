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
        val encoded = URLEncoder.encode(domain, "UTF-8")
        val conn = (URL("https://cloudflare-dns.com/dns-query?name=$encoded&type=$type")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/dns-json")
            setRequestProperty("User-Agent", "NetQuality/2.1")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return DnsResult(type, null, emptyList(), "HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val status = json.optInt("Status", -1)
            val arr = json.optJSONArray("Answer")
            val answers = buildList {
                if (arr != null) for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(DnsAnswer(type, item.optString("data", "—"), item.optLong("TTL", 0)))
                }
            }
            DnsResult(type, status, answers)
        } catch (e: Exception) {
            DnsResult(type, null, emptyList(), e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    fun queryAll(domain: String): List<DnsResult> =
        recordTypes.map { query(domain, it) }
}
