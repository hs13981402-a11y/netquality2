package com.netquality.app

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Random

data class DnsAnswer(val type: String, val value: String, val ttl: Long)
data class DnsResult(val type: String, val status: Int?, val answers: List<DnsAnswer>, val error: String? = null)

object DnsLookup {
    private val types = linkedMapOf("A" to 1, "NS" to 2, "CNAME" to 5, "SOA" to 6, "MX" to 15, "TXT" to 16, "AAAA" to 28, "SRV" to 33, "CAA" to 257)
    fun supportedTypes(): List<String> = types.keys.toList()

    fun query(server: String, domain: String, type: String): DnsResult {
        val clean = domain.trim().trimEnd('.').lowercase()
        val qtype = types[type] ?: return DnsResult(type, null, emptyList(), "نوع رکورد پشتیبانی نمی‌شود")
        if (clean.isBlank() || clean.split('.').any { it.isBlank() || it.length > 63 })
            return DnsResult(type, null, emptyList(), "نام دامنه معتبر نیست")
        return try {
            val id = Random().nextInt(65536)
            val packet = buildQuery(clean, qtype, id)
            val address = InetAddress.getByName(server)
            val response = try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 2500
                    socket.send(DatagramPacket(packet, packet.size, address, 53))
                    val buf = ByteArray(4096)
                    val reply = DatagramPacket(buf, buf.size)
                    socket.receive(reply)
                    buf.copyOf(reply.length)
                }
            } catch (e: Exception) { tcpQuery(address, packet) }
            parse(response, id, type)
        } catch (e: Exception) {
            DnsResult(type, null, emptyList(), e.message ?: e.javaClass.simpleName)
        }
    }

    private fun buildQuery(name: String, type: Int, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeShort(id); d.writeShort(0x0100); d.writeShort(1); d.writeShort(0); d.writeShort(0); d.writeShort(0)
        name.split('.').forEach { val b = it.toByteArray(Charsets.US_ASCII); d.writeByte(b.size); d.write(b) }
        d.writeByte(0); d.writeShort(type); d.writeShort(1)
        return out.toByteArray()
    }

    private fun tcpQuery(address: InetAddress, query: ByteArray): ByteArray {
        Socket().use { s ->
            s.connect(InetSocketAddress(address, 53), 2500); s.soTimeout = 2500
            val out = DataOutputStream(s.getOutputStream()); out.writeShort(query.size); out.write(query); out.flush()
            val input = DataInputStream(s.getInputStream()); val len = input.readUnsignedShort()
            return ByteArray(len).also { input.readFully(it) }
        }
    }

    private fun parse(b: ByteArray, id: Int, type: String): DnsResult {
        if (b.size < 12) throw IllegalStateException("پاسخ DNS ناقص است")
        val bb = ByteBuffer.wrap(b)
        val rid = bb.getShort().toInt() and 0xffff
        if (rid != id) throw IllegalStateException("شناسه پاسخ DNS معتبر نیست")
        val flags = bb.getShort().toInt() and 0xffff
        val qd = bb.getShort().toInt() and 0xffff
        val an = bb.getShort().toInt() and 0xffff
        bb.short; bb.short
        repeat(qd) { skipName(b, bb); bb.short; bb.short }
        val answers = ArrayList<DnsAnswer>()
        repeat(an) {
            skipName(b, bb)
            if (bb.remaining() < 10) throw IllegalStateException("رکورد ناقص")
            val rt = bb.short.toInt() and 0xffff; bb.short
            val ttl = bb.int.toLong() and 0xffffffffL
            val len = bb.short.toInt() and 0xffff
            val start = bb.position()
            if (bb.remaining() < len) throw IllegalStateException("داده ناقص")
            val value = when (rt) {
                1 -> if (len == 4) (0 until 4).joinToString(".") { (b[start+it].toInt() and 255).toString() } else "A"
                28 -> if (len == 16) InetAddress.getByAddress(b.copyOfRange(start,start+len)).hostAddress ?: "AAAA" else "AAAA"
                2,5,12 -> decodeName(b, start)
                15 -> if (len >= 3) "priority=${((b[start].toInt() and 255) shl 8) or (b[start+1].toInt() and 255)} ${decodeName(b,start+2)}" else "MX"
                16 -> String(b.copyOfRange(start+1,start+len), Charsets.UTF_8)
                else -> b.copyOfRange(start,start+len).joinToString("") { "%02x".format(it) }
            }
            if (rt == types[type] || type == "ANY") answers.add(DnsAnswer(type, value, ttl))
            bb.position(start + len)
        }
        return DnsResult(type, flags and 15, answers)
    }

    private fun skipName(b: ByteArray, bb: ByteBuffer) {
        while (bb.hasRemaining()) {
            val v = bb.get().toInt() and 255
            if (v == 0) return
            if (v and 0xC0 == 0xC0) { bb.get(); return }
            if (v and 0xC0 != 0 || bb.remaining() < v) throw IllegalStateException("نام DNS نامعتبر")
            bb.position(bb.position()+v)
        }
    }

    private fun decodeName(b: ByteArray, offset: Int): String {
        val parts = ArrayList<String>(); var p=offset; var jumps=0
        while (p < b.size && jumps++ < 32) {
            val n=b[p].toInt() and 255
            if (n==0) break
            if (n and 0xC0 == 0xC0) { p=((n and 0x3f) shl 8) or (b[p+1].toInt() and 255); continue }
            if (p+1+n>b.size) break
            parts.add(String(b,p+1,n,Charsets.US_ASCII)); p+=n+1
        }
        return parts.joinToString(".")
    }
}
