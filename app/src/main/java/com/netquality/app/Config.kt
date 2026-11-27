package com.netquality.app

import android.content.Context
import java.net.URL

data class PingTarget(val host: String, val port: Int)
data class Site(val name: String, val url: String, val internal: Boolean)

/** تنظیمات قابل ویرایش از داخل اپ (هر خط یک مورد، خطوط شروع‌شده با # نادیده گرفته می‌شوند) */
object Config {
    private const val PREFS = "netq_cfg"

    val DEFAULTS = mapOf(
        "ping" to "8.8.8.8:53\n1.1.1.1:53",
        "dns_servers" to "8.8.8.8\n1.1.1.1",
        "dns_domains" to "google.com\ndigikala.com",
        "sites_intl" to "https://www.gstatic.com/generate_204\nhttps://www.cloudflare.com/cdn-cgi/trace",
        "sites_local" to "https://www.digikala.com\nhttps://www.aparat.com",
        "speed" to "https://speed.cloudflare.com/__down?bytes=25000000\nhttp://cachefly.cachefly.net/10mb.test\nhttps://proof.ovh.net/files/10Mb.dat"
    )

    fun get(ctx: Context, key: String): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, DEFAULTS[key]) ?: ""

    fun put(ctx: Context, key: String, value: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    fun reset(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply()
    }

    fun lines(ctx: Context, key: String): List<String> =
        get(ctx, key).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** "host" یا "host:port" */
    fun parseTarget(s: String): PingTarget {
        val i = s.lastIndexOf(':')
        if (i > 0) {
            val p = s.substring(i + 1)
            if (p.isNotEmpty() && p.all { it.isDigit() }) return PingTarget(s.substring(0, i), p.toInt())
        }
        return PingTarget(s, 443)
    }

    fun normUrl(u: String): String = if (u.contains("://")) u else "https://$u"

    fun siteName(u: String): String = try {
        URL(normUrl(u)).host.removePrefix("www.")
    } catch (e: Exception) {
        u
    }
}
