package com.netquality.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object DnsLookup {
    fun query(domain: String, type: String): String {
        val encoded = URLEncoder.encode(domain, "UTF-8")
        val connection = (URL("https://cloudflare-dns.com/dns-query?name=$encoded&type=$type")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("Accept", "application/dns-json")
            setRequestProperty("User-Agent", "NetQuality/2.0")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) return "سرور DNS پاسخ HTTP $code برگرداند.\n$body"
            val json = JSONObject(body)
            val status = json.optInt("Status", -1)
            val answers = json.optJSONArray("Answer")
            return buildString {
                append("دامنه: $domain\nنوع رکورد: $type\nDNS Status: $status")
                if (status == 0 && answers != null && answers.length() > 0) {
                    append("\n\nپاسخ‌ها:\n")
                    for (i in 0 until answers.length()) {
                        val item = answers.getJSONObject(i)
                        append("• ").append(item.optString("data", "—"))
                        append("   TTL: ").append(item.optLong("TTL", 0)).append("s\n")
                    }
                } else if (status == 0) append("\n\nبرای این نوع رکورد پاسخی ثبت نشده است.")
                else append("\n\nپاسخی برای این درخواست دریافت نشد.")
                append("\n\nمنبع: Cloudflare DNS-over-HTTPS")
            }
        } finally { connection.disconnect() }
    }
}
