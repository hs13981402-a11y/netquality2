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

    /** اندازه‌گیری پاسخ‌گویی پروب‌های UDP مبتنی بر DNS/53؛ وابسته به سرور و سرویس مقصد است. */
    fun udpLossTest(server: String, domain: String, count: Int): UdpLossResult {
        var received = 0
        repeat(count) {
            val result = dnsQuery(server, domain, 1200)
            if (result != null && result >= 0) received++
            if (it < count - 1) Thread.sleep(120)
        }
        return UdpLossResult(count, received)
    }

    /** آپلود آزمایشی HTTPS به endpoint عمومی Cloudflare؛ مقدار خروجی Mbps است. */
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
