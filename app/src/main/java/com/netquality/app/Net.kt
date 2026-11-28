package com.netquality.app

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.json.JSONObject
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/** توابع شبکه (همه blocking هستند؛ از Dispatchers.IO صدا بزنید) */
object Net {

    data class PingResult(
        val host: String, val sent: Int, val received: Int,
        val avg: Double, val min: Double, val max: Double,
        val jitter: Double, val method: String
    ) {
        val loss: Double get() = if (sent == 0) 100.0 else (sent - received) * 100.0 / sent
    }

    data class HttpRes(val code: Int?, val ms: Long, val err: String?) {
        val ok: Boolean get() = code != null
    }

    data class HopProbe(val ip: String?, val rtt: Double?, val reached: Boolean)

    data class UdpLossResult(val sent: Int, val received: Int) {
        val loss: Double get() = if (sent == 0) 100.0 else (sent - received) * 100.0 / sent
    }

    // ───────── پینگ ─────────
    fun stats(host: String, sent: Int, t: List<Double>, method: String): PingResult {
        if (t.isEmpty()) return PingResult(host, sent, 0, 0.0, 0.0, 0.0, 0.0, method)
        var j = 0.0
        for (i in 1 until t.size) j += abs(t[i] - t[i - 1])
        j = if (t.size > 1) j / (t.size - 1) else 0.0
        return PingResult(host, sent, t.size, t.average(), t.min(), t.max(), j, method)
    }

    fun icmpPing(host: String, count: Int): PingResult? {
        return try {
            val p = ProcessBuilder(
                "/system/bin/ping", "-c", "$count", "-W", "2", "-w", "${count + 6}", host
            ).redirectErrorStream(true).start()
            val times = ArrayList<Double>()
            val re = Regex("time=([0-9.]+)")
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { l -> re.find(l)?.let { times.add(it.groupValues[1].toDouble()) } }
            }
            p.waitFor()
            stats(host, count, times, "ICMP")
        } catch (e: Exception) {
            null
        }
    }

    fun tcpOnce(host: String, port: Int, timeoutMs: Int): Double? {
        return try {
            Socket().use { s ->
                val t0 = System.nanoTime()
                s.connect(InetSocketAddress(host, port), timeoutMs)
                (System.nanoTime() - t0) / 1e6
            }
        } catch (e: Exception) {
            null
        }
    }

    fun tcpPing(host: String, port: Int, count: Int): PingResult {
        val times = ArrayList<Double>()
        repeat(count) {
            tcpOnce(host, port, 2000)?.let { times.add(it) }
            Thread.sleep(200)
        }
        return stats(host, count, times, "TCP")
    }

    // ───────── Traceroute (ICMP با TTL) ─────────
    /** سربار اجرای پروسه‌ی ping (برای کم کردن از زمان‌سنجی هاپ‌ها) */
    fun baseline(): Double {
        var best = Double.MAX_VALUE
        repeat(3) {
            try {
                val t0 = System.nanoTime()
                val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", "127.0.0.1")
                    .redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText()
                p.waitFor()
                val w = (System.nanoTime() - t0) / 1e6
                if (w < best) best = w
            } catch (_: Exception) {
            }
        }
        return if (best == Double.MAX_VALUE) 0.0 else best
    }

    private val ipRe = Regex("from ([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+)", RegexOption.IGNORE_CASE)
    private val timeRe = Regex("time=([0-9.]+)")

    /** یک پروب با TTL مشخص؛ rtt برای هاپ‌های میانی تقریبی است */
    fun probeTtl(ip: String, ttl: Int, baseline: Double): HopProbe {
        return try {
            val t0 = System.nanoTime()
            val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "2", "-t", "$ttl", ip)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            val wall = (System.nanoTime() - t0) / 1e6
            val tm = timeRe.find(out)
            val from = ipRe.find(out)?.groupValues?.get(1)
            if (tm != null) {
                HopProbe(from ?: ip, tm.groupValues[1].toDouble(), true)
            } else if (from != null) {
                HopProbe(from, max(0.1, wall - baseline), false)
            } else {
                HopProbe(null, null, false)
            }
        } catch (e: Exception) {
            HopProbe(null, null, false)
        }
    }

    // ───────── DNS ─────────
    /** null = timeout ، -1 = پاسخ نامعتبر ، غیر از این‌ها = میلی‌ثانیه */
    fun sysResolve(domain: String, timeoutMs: Long): Long? {
        var res: Long? = null
        val t = Thread {
            res = try {
                val t0 = System.nanoTime()
                InetAddress.getByName(domain)
                (System.nanoTime() - t0) / 1_000_000
            } catch (e: Exception) {
                -1L
            }
        }
        t.isDaemon = true
        t.start()
        t.join(timeoutMs)
        return if (t.isAlive) null else res
    }

    fun dnsQuery(server: String, domain: String, timeoutMs: Int = 2500): Long? {
        return try {
            val id = Random().nextInt(0xFFFF)
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeShort(id)
            dos.writeShort(0x0100)
            dos.writeShort(1)
            dos.writeShort(0)
            dos.writeShort(0)
            dos.writeShort(0)
            for (part in domain.split(".")) {
                dos.writeByte(part.length)
                dos.writeBytes(part)
            }
            dos.writeByte(0)
            dos.writeShort(1)
            dos.writeShort(1)
            val data = baos.toByteArray()
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                val t0 = System.nanoTime()
                s.send(DatagramPacket(data, data.size, InetAddress.getByName(server), 53))
                val buf = ByteArray(512)
                s.receive(DatagramPacket(buf, buf.size))
                val ms = (System.nanoTime() - t0) / 1_000_000
                val rid = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                val rcode = buf[3].toInt() and 0x0F
                val an = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
                if (rid == id && rcode == 0 && an > 0) ms else -1L
            }
        } catch (e: Exception) {
            null
        }
    }


    /** TLS handshake with explicit SNI; null means handshake/connection failed. */
    fun tlsOnce(domain: String, timeoutMs: Int = 6000): Long? {
        return try {
            (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use { socket ->
                socket.connect(InetSocketAddress(domain, 443), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.sslParameters = socket.sslParameters.apply {
                    serverNames = listOf(SNIHostName(domain))
                }
                val started = System.nanoTime()
                socket.startHandshake()
                (System.nanoTime() - started) / 1_000_000
            }
        } catch (_: Exception) { null }
    }

    data class SiteInfra(
        val ips: List<String>, val cdn: String, val serverHeader: String?,
        val cfRay: String?, val colo: String?, val country: String?,
        val city: String?, val org: String?, val geoNote: String?,
        val cname: String?, val asn: String?, val evidence: List<String>
    )

    private fun dohLookup(domain: String, type: Int): List<String> {
        return try {
            val c = URL("https://dns.google/resolve?name=$domain&type=$type").openConnection() as HttpURLConnection
            c.connectTimeout = 4500
            c.readTimeout = 4500
            c.setRequestProperty("Accept", "application/dns-json")
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            c.disconnect()
            val answers = json.optJSONArray("Answer") ?: return emptyList()
            (0 until answers.length()).mapNotNull { i ->
                answers.optJSONObject(i)?.optString("data")?.trim()?.trimEnd('.')
                    ?.takeIf { it.isNotBlank() }
            }.distinct()
        } catch (_: Exception) { emptyList() }
    }

    /** Broader CDN fingerprinting using DNS, HTTP headers and ASN/organization evidence. */
    fun inspectSiteInfrastructure(domain: String): SiteInfra {
        val ips = try { InetAddress.getAllByName(domain).mapNotNull { it.hostAddress }.distinct() } catch (_: Exception) { emptyList() }
        val chain = ArrayList<String>()
        var current = domain
        repeat(8) {
            val next = dohLookup(current, 5).firstOrNull() ?: return@repeat
            if (next.equals(current, true) || chain.any { it.equals(next, true) }) return@repeat
            chain.add(next); current = next
        }
        val ns = dohLookup(domain, 2)
        var server: String? = null; var cfRay: String? = null
        var via = ""; var cache = ""; var cfId = ""; var azureRef = ""; var imperva = ""; var served = ""
        try {
            val c = URL("https://$domain").openConnection() as HttpURLConnection
            c.connectTimeout = 6500; c.readTimeout = 6500; c.instanceFollowRedirects = true
            c.requestMethod = "GET"; c.setRequestProperty("Range", "bytes=0-0")
            c.setRequestProperty("User-Agent", "NetQuality/2.5")
            try { c.responseCode } catch (_: Exception) { }
            server = c.getHeaderField("Server"); cfRay = c.getHeaderField("CF-Ray")
            via = c.getHeaderField("Via") ?: ""; cache = c.getHeaderField("X-Cache") ?: ""
            served = c.getHeaderField("X-Served-By") ?: ""; cfId = c.getHeaderField("X-Amz-Cf-Id") ?: ""
            azureRef = c.getHeaderField("X-Azure-Ref") ?: ""; imperva = c.getHeaderField("X-Iinfo") ?: ""
            c.disconnect()
        } catch (_: Exception) { }
        val ip = ips.firstOrNull { it.contains(".") } ?: ips.firstOrNull()
        var country: String? = null; var city: String? = null; var org: String? = null; var asn: String? = null
        if (ip != null) try {
            val c = URL("https://ipwho.is/$ip").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            if (j.optBoolean("success", false)) {
                country = j.optString("country").takeIf { it.isNotBlank() }; city = j.optString("city").takeIf { it.isNotBlank() }
                val con = j.optJSONObject("connection")
                org = con?.optString("org")?.takeIf { it.isNotBlank() }; asn = con?.optString("asn")?.takeIf { it.isNotBlank() }
            }
            c.disconnect()
        } catch (_: Exception) { }
        val cnameText = chain.joinToString(" ").lowercase(Locale.ROOT)
        val nsText = ns.joinToString(" ").lowercase(Locale.ROOT)
        val orgText = org.orEmpty().lowercase(Locale.ROOT); val asnText = asn.orEmpty().lowercase(Locale.ROOT)
        val signals = listOf(cnameText, nsText, orgText, server.orEmpty(), via, cache, served, cfId, azureRef, imperva)
            .joinToString(" ").lowercase(Locale.ROOT)
        val rules = listOf(
            Triple("Cloudflare", listOf("cloudflare"), listOf("13335")),
            Triple("Amazon CloudFront", listOf("cloudfront.net", "cloudfront"), listOf("16509")),
            Triple("Akamai", listOf("akamai", "edgekey.net", "edgesuite.net", "akamaized.net"), listOf("20940", "16625", "35994")),
            Triple("Fastly", listOf("fastly.net", "fastlylb.net", "x-served-by"), listOf("54113")),
            Triple("Microsoft Azure Front Door/CDN", listOf("azureedge.net", "azurefd.net", "trafficmanager.net", "x-azure-ref"), listOf("8075")),
            Triple("Google Cloud CDN", listOf("googleusercontent.com", "googlehosted.com", "gcdn"), listOf("15169")),
            Triple("Imperva / Incapsula", listOf("incapdns.net", "imperva", "incapsula", "x-iinfo"), listOf("19551")),
            Triple("Gcore", listOf("gcore", "gcdn.co", "gcorelabs.net"), listOf("199524")),
            Triple("Bunny CDN", listOf("b-cdn.net", "bunnycdn", "bunny.net"), listOf("200373")),
            Triple("KeyCDN", listOf("kxcdn.com", "keycdn"), listOf("16625")),
            Triple("Sucuri", listOf("sucuri", "cloudproxy"), listOf("30148")),
            Triple("Edgio / Limelight", listOf("edgio", "llnwd.net", "limelight"), listOf("22822")),
            Triple("CacheFly", listOf("cachefly"), listOf("30081")),
            Triple("Alibaba Cloud CDN", listOf("alikunlun", "kunlun", "aliyuncs"), listOf("45102")),
            Triple("Tencent Cloud CDN", listOf("dnsv1.com", "tencent", "qcloud"), listOf("45090"))
        )
        val found = rules.mapNotNull { (name, patterns, asns) ->
            if (patterns.any { signals.contains(it) } || (asns.any { asnText.contains(it) } && orgText.isNotBlank())) name else null
        }.distinct()
        val cdn = when {
            found.size == 1 -> found.first()
            found.size > 1 -> found.joinToString(" / ") + " (چند نشانه)"
            chain.isNotEmpty() -> "CDN یا سرویس واسط محتمل؛ ارائه‌دهنده مشخص نشد"
            cache.contains("hit", true) || served.contains("cache", true) -> "وجود Cache محتمل؛ ارائه‌دهنده مشخص نشد"
            else -> "CDN از شواهد فعلی تأیید نشد"
        }
        val evidence = ArrayList<String>()
        if (chain.isNotEmpty()) evidence.add("زنجیره CNAME: " + chain.joinToString(" → "))
        if (ns.isNotEmpty()) evidence.add("NS: " + ns.take(5).joinToString(", "))
        if (cfRay != null) evidence.add("هدر اختصاصی CF-Ray")
        if (cfId.isNotBlank()) evidence.add("هدر X-Amz-Cf-Id")
        if (azureRef.isNotBlank()) evidence.add("هدر X-Azure-Ref")
        if (imperva.isNotBlank()) evidence.add("هدر X-Iinfo")
        if (server != null) evidence.add("Server: $server")
        if (via.isNotBlank()) evidence.add("Via: $via")
        if (cache.isNotBlank() || served.isNotBlank()) evidence.add("Cache/Served-By: " + listOf(cache, served).filter { it.isNotBlank() }.joinToString(" / "))
        if (asn != null || org != null) evidence.add("مالک شبکه IP: " + listOfNotNull(asn, org).joinToString(" / "))
        if (found.isNotEmpty()) evidence.add("ارائه‌دهنده محتمل بر اساس شواهد: " + found.joinToString(", "))
        var colo: String? = null
        if ("Cloudflare" in found) try {
            val c = URL("https://$domain/cdn-cgi/trace").openConnection() as HttpURLConnection
            c.connectTimeout = 4500; c.readTimeout = 4500
            colo = c.inputStream.bufferedReader().use { it.readText() }.lineSequence().firstOrNull { it.startsWith("colo=") }?.substringAfter("=")
            c.disconnect()
        } catch (_: Exception) { }
        val note = if (found.isNotEmpty()) "تشخیص بر اساس نشانه‌های DNS، هدر یا ASN است. IP و GeoIP معمولاً مربوط به لبه CDN/شبکه واسط هستند، نه سرور مبدأ."
            else "نبود نشانه به معنی نبود CDN نیست؛ برخی سرویس‌ها CNAME و هدرها را پنهان می‌کنند. GeoIP فقط تخمین موقعیت IP پاسخ‌گو است."
        return SiteInfra(ips, cdn, server, cfRay, colo, country, city, org, note, chain.firstOrNull(), asn, evidence)
    }

    data class IperfUdpResult(
        val host: String, val port: Int, val bitrate: String,
        val sentPackets: Long, val lostPackets: Long, val lossPercent: Double,
        val jitterMs: Double, val throughputMbps: Double
    )

    fun iperfUdpTest(context: android.content.Context, host: String, port: Int, bitrate: String, durationSeconds: Int = 10): IperfUdpResult {
        val executable = java.io.File(context.filesDir, "iperf3")
        if (!executable.exists()) {
            context.assets.open("iperf3-arm64").use { input ->
                executable.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!executable.setExecutable(true, false) && !executable.canExecute()) {
            throw IllegalStateException("اجازه اجرای موتور iperf3 داده نشد.")
        }
        val outputFile = java.io.File(context.cacheDir, "iperf3-result.json")
        val process = ProcessBuilder(
            executable.absolutePath, "-c", host, "-p", port.toString(),
            "-u", "-b", bitrate, "-l", "1200", "-t", durationSeconds.toString(), "-J"
        ).redirectErrorStream(true).redirectOutput(outputFile).start()
        if (!process.waitFor((durationSeconds + 25).toLong(), java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("زمان آزمون iperf3 به پایان رسید.")
        }
        val output = outputFile.readText()
        outputFile.delete()
        if (process.exitValue() != 0) {
            throw IllegalStateException(output.takeLast(700).ifBlank { "اتصال به سرور iperf3 برقرار نشد." })
        }
        val json = org.json.JSONObject(output)
        val end = json.optJSONObject("end") ?: throw IllegalStateException("خروجی سرور iperf3 ناقص است.")
        val summary = end.optJSONObject("sum") ?: end.optJSONObject("sum_received")
            ?: end.optJSONObject("sum_sent") ?: throw IllegalStateException("آمار UDP در خروجی موجود نیست.")
        val sent = summary.optLong("packets", 0L)
        val lost = summary.optLong("lost_packets", 0L)
        val loss = summary.optDouble("lost_percent", if (sent > 0) lost * 100.0 / sent else 0.0)
        val jitter = summary.optDouble("jitter_ms", 0.0)
        val mbps = summary.optDouble("bits_per_second", 0.0) / 1_000_000.0
        if (sent <= 0) throw IllegalStateException("سرور آمار بسته‌های UDP را برنگرداند.")
        return IperfUdpResult(host, port, bitrate, sent, lost, loss, jitter, mbps)
    }

    fun udpLossTest(server: String, domain: String, count: Int): UdpLossResult {
        var received = 0
        repeat(count) {
            val result = dnsQuery(server, domain, 1200)
            if (result != null && result >= 0) received++
            if (it < count - 1) Thread.sleep(120)
        }
        return UdpLossResult(count, received)
    }

    fun uploadTest(): Double? {
        var connection: HttpURLConnection? = null
        return try {
            val payload = ByteArray(4 * 1024 * 1024)
            Random().nextBytes(payload)
            connection = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 7000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "NetQuality/1.0")
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(payload.size)
            val start = System.nanoTime()
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            val seconds = (System.nanoTime() - start) / 1e9
            if (code in 200..299 && seconds > 0) payload.size * 8.0 / seconds / 1_000_000.0 else null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ───────── HTTP و سرعت ─────────
    fun httpCheck(url: String, follow: Boolean): HttpRes {
        val t0 = System.nanoTime()
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.instanceFollowRedirects = follow
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = c.responseCode
            c.disconnect()
            HttpRes(code, (System.nanoTime() - t0) / 1_000_000, null)
        } catch (e: Exception) {
            HttpRes(null, (System.nanoTime() - t0) / 1_000_000, e.javaClass.simpleName)
        }
    }

    fun speedTest(url: String, maxMs: Long = 8000): Double? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.inputStream.use { ins ->
                val buf = ByteArray(32 * 1024)
                var total = 0L
                val t0 = System.nanoTime()
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if ((System.nanoTime() - t0) / 1_000_000 > maxMs) break
                }
                val sec = (System.nanoTime() - t0) / 1e9
                if (total < 100_000 || sec <= 0) null else total * 8 / sec / 1e6
            }
        } catch (e: Exception) {
            null
        }
    }
}
