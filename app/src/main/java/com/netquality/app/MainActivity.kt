package com.netquality.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    data class NetInfo(
        val connected: Boolean, val type: String, val ssid: String,
        val rssi: Int?, val freq: Int?, val link: Int?, val gateway: String?
    )

    private class HopStat {
        var ip: String? = null
        var sent = 0
        var recv = 0
        var last = 0.0
        var sum = 0.0
        var min = Double.MAX_VALUE
        var max = 0.0
        val hist = ArrayList<Double>() // Ping history; -1 = no reply
        val jitterHist = ArrayList<Double>()
        val lossHist = ArrayList<Double>()
        private var previousRtt: Double? = null

        fun add(rtt: Double?, ipSeen: String?) {
            sent++
            if (ipSeen != null) ip = ipSeen
            if (rtt == null) {
                hist.add(-1.0)
                jitterHist.add(-1.0)
            } else {
                recv++
                last = rtt
                sum += rtt
                if (rtt < min) min = rtt
                if (rtt > max) max = rtt
                hist.add(rtt)
                val prev = previousRtt
                jitterHist.add(if (prev == null) 0.0 else kotlin.math.abs(rtt - prev))
                previousRtt = rtt
            }
            lossHist.add(loss)
            if (hist.size > 300) hist.removeAt(0)
            if (jitterHist.size > 300) jitterHist.removeAt(0)
            if (lossHist.size > 300) lossHist.removeAt(0)
        }

        val avg: Double get() = if (recv == 0) 0.0 else sum / recv
        val loss: Double get() = if (sent == 0) 0.0 else (sent - recv) * 100.0 / sent
    }

    // ───────── Views ─────────
    private lateinit var panelTest: View
    private lateinit var panelPlot: View
    private lateinit var panelSettings: View
    private lateinit var panelChart: View
    private lateinit var panelFilter: View
    private lateinit var etFilterDomain: EditText
    private lateinit var spFilterSite: Spinner
    private lateinit var spFilterOperator: Spinner
    private lateinit var spFilterCompareA: Spinner
    private lateinit var spFilterCompareB: Spinner
    private lateinit var dnsGraph: DnsGraphView
    private lateinit var tvFilterResult: TextView
    private lateinit var filterProgress: ProgressBar
    private lateinit var btnFilterCheck: Button
    private lateinit var panelDns: View
    private lateinit var etDnsLookup: EditText
    private lateinit var spDnsType: Spinner
    private lateinit var tvDnsLookupResult: TextView
    private lateinit var btnDnsLookup: Button
    private lateinit var spOperator: Spinner

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnStart: Button
    private lateinit var btnHistory: Button
    private lateinit var progress: ProgressBar
    private lateinit var cbAuto: CheckBox

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var rbTcp: RadioButton
    private lateinit var btnPlot: Button
    private lateinit var tvPlotInfo: TextView
    private lateinit var tvTable: TextView
    private lateinit var graph: GraphView
    private lateinit var graphLarge: GraphView
    private lateinit var graphJitter: GraphView
    private lateinit var graphLoss: GraphView
    private lateinit var etRefreshSeconds: EditText

    private lateinit var cm: ConnectivityManager
    private var running = false
    private var plotJob: Job? = null
    private var lastPlotReport = ""
    private val log = StringBuilder()

    private val cfgFields = listOf(
        "ping" to R.id.etPing,
        "dns_servers" to R.id.etDnsServers,
        "dns_domains" to R.id.etDnsDomains,
        "sites_intl" to R.id.etSitesIntl,
        "sites_local" to R.id.etSitesLocal,
        "speed" to R.id.etSpeed
    )

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                if (cbAuto.isChecked && !running) {
                    lifecycleScope.launch {
                        delay(2500)
                        if (!running && cbAuto.isChecked) runTest()
                    }
                }
            }
        }

        override fun onLost(network: Network) {
            runOnUiThread { if (!running) tvStatus.text = "❌ اتصال شبکه قطع شد" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        panelTest = findViewById(R.id.panelTest)
        panelPlot = findViewById(R.id.panelPlot)
        panelSettings = findViewById(R.id.panelSettings)
        panelChart = findViewById(R.id.panelChart)
        panelFilter = findViewById(R.id.panelFilter)
        etFilterDomain = findViewById(R.id.etFilterDomain)
        spFilterSite = findViewById(R.id.spFilterSite)
        spFilterOperator = findViewById(R.id.spFilterOperator)
        spFilterCompareA = findViewById(R.id.spFilterCompareA)
        spFilterCompareB = findViewById(R.id.spFilterCompareB)
        dnsGraph = findViewById(R.id.dnsGraph)
        tvFilterResult = findViewById(R.id.tvFilterResult)
        filterProgress = findViewById(R.id.filterProgress)
        btnFilterCheck = findViewById(R.id.btnFilterCheck)
        panelDns = findViewById(R.id.panelDns)
        etDnsLookup = findViewById(R.id.etDnsLookup)
        spDnsType = findViewById(R.id.spDnsType)
        tvDnsLookupResult = findViewById(R.id.tvDnsLookupResult)
        btnDnsLookup = findViewById(R.id.btnDnsLookup)
        spOperator = findViewById(R.id.spOperator)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        btnStart = findViewById(R.id.btnStart)
        btnHistory = findViewById(R.id.btnHistory)
        progress = findViewById(R.id.progress)
        cbAuto = findViewById(R.id.cbAuto)

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        rbTcp = findViewById(R.id.rbTcp)
        btnPlot = findViewById(R.id.btnPlot)
        tvPlotInfo = findViewById(R.id.tvPlotInfo)
        tvTable = findViewById(R.id.tvTable)
        graph = findViewById(R.id.graph)
        graphLarge = findViewById(R.id.graphLarge)
        graphJitter = findViewById(R.id.graphJitter)
        graphLoss = findViewById(R.id.graphLoss)
        graph.chartTitle = "Ping زنده"
        graph.chartUnit = "ms"
        graphLarge.chartTitle = "Ping"
        graphLarge.chartUnit = "ms"
        graphJitter.chartTitle = "Jitter"
        graphJitter.chartUnit = "ms"
        graphLoss.chartTitle = "Packet Loss"
        graphLoss.chartUnit = "%"
        etRefreshSeconds = findViewById(R.id.etRefreshSeconds)

        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        tvLog.setLineSpacing(resources.displayMetrics.density * 5f, 1.05f)
        tvStatus.setLineSpacing(resources.displayMetrics.density * 5f, 1.05f)

        findViewById<Button>(R.id.tabTest).setOnClickListener { showPanel(0) }
        findViewById<Button>(R.id.tabPlot).setOnClickListener { showPanel(1) }
        findViewById<Button>(R.id.tabSettings).setOnClickListener { showPanel(2) }
        findViewById<Button>(R.id.tabFilter).setOnClickListener { showPanel(4) }
        findViewById<Button>(R.id.tabDns).setOnClickListener { showPanel(5) }
        findViewById<Button>(R.id.btnCompareOperators).setOnClickListener { showOperatorComparison() }
        btnDnsLookup.setOnClickListener { runDnsLookup() }
        findViewById<Button>(R.id.btnCompareFilter).setOnClickListener { compareFilterOperators() }
        findViewById<Button>(R.id.btnShareFilter).setOnClickListener {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            share("گزارش بررسی دسترسی\nاپراتور: ${spOperator.selectedItem}\nزمان: $stamp\n\n${tvFilterResult.text}")
        }
        btnFilterCheck.setOnClickListener { checkFilterAccess() }
        findViewById<Button>(R.id.tabChart).setOnClickListener { showPanel(3) }

        btnStart.setOnClickListener { runTest() }
        btnHistory.setOnClickListener { showHistory() }
        findViewById<Button>(R.id.btnCompareTests).setOnClickListener { compareLastTwoTests() }
        findViewById<Button>(R.id.btnShareTest).setOnClickListener {
            share(tvStatus.text.toString() + "\n\n" + tvLog.text.toString())
        }

        btnPlot.setOnClickListener { togglePlot() }
        findViewById<Button>(R.id.btnSharePlot).setOnClickListener { share(lastPlotReport) }

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetSettings() }
        loadSettings()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1
            )
        }
        cm.registerDefaultNetworkCallback(netCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cm.unregisterNetworkCallback(netCallback)
        } catch (_: Exception) {
        }
    }

    private fun showPanel(i: Int) {
        panelTest.visibility = if (i == 0) View.VISIBLE else View.GONE
        panelPlot.visibility = if (i == 1) View.VISIBLE else View.GONE
        panelSettings.visibility = if (i == 2) View.VISIBLE else View.GONE
        panelChart.visibility = if (i == 3) View.VISIBLE else View.GONE
        panelFilter.visibility = if (i == 4) View.VISIBLE else View.GONE
        panelDns.visibility = if (i == 5) View.VISIBLE else View.GONE
    }

    private fun checkFilterAccess() {
        val presets = listOf("facebook.com", "instagram.com", "youtube.com", "t.me", "x.com", "whatsapp.com", "google.com")
        val custom = etFilterDomain.text.toString().trim()
            .removePrefix("https://").removePrefix("http://").substringBefore("/")
        val domain = if (custom.isNotBlank()) custom else presets.getOrElse(spFilterSite.selectedItemPosition) { presets.first() }
        if (domain.isBlank() || !domain.contains(".")) {
            Toast.makeText(this, "یک دامنه معتبر وارد کنید", Toast.LENGTH_SHORT).show()
            return
        }
        btnFilterCheck.isEnabled = false
        filterProgress.visibility = View.VISIBLE
        tvFilterResult.text = "در حال بررسی $domain …\nلطفاً تا پایان آزمون صبر کنید."
        lifecycleScope.launch {
            try {
                val dnsMs = withContext(Dispatchers.IO) {
                    try {
                        val start = System.nanoTime()
                        InetAddress.getAllByName(domain)
                        (System.nanoTime() - start) / 1_000_000
                    } catch (_: Exception) { null }
                }
                val targetTcp = withContext(Dispatchers.IO) { Net.tcpOnce(domain, 443, 5000) }
                val controlTcp = withContext(Dispatchers.IO) { Net.tcpOnce("1.1.1.1", 443, 5000) }
                val https = withContext(Dispatchers.IO) { Net.httpCheck("https://$domain", false) }
                val verdict = when {
                    https.code != null && https.code in 200..399 -> "قابل دسترس"
                    dnsMs == null && controlTcp != null -> "احتمال اختلال یا محدودیت DNS"
                    targetTcp == null && controlTcp != null -> "احتمال محدودیت اختصاصی مقصد یا مسیر دسترسی"
                    targetTcp != null && https.code == null -> "TCP برقرار است؛ مشکل در TLS یا لایه HTTPS محتمل است"
                    https.code == 403 || https.code == 451 -> "پاسخ محدودکننده HTTP دریافت شد؛ علت می‌تواند سیاست سرور یا فیلترینگ باشد"
                    else -> "نتیجه نامشخص؛ شواهد برای تشخیص فیلترینگ کافی نیست"
                }
                val colorHint = when {
                    https.code != null && https.code in 200..399 -> "وضعیت: قابل دسترس"
                    dnsMs == null || targetTcp == null -> "وضعیت: نیازمند بررسی"
                    else -> "وضعیت: اختلال در لایه بالاتر"
                }
                tvFilterResult.text = buildString {
                    append("دامنه: $domain\n\n")
                    append("نتیجه: $verdict\n\n")
                    append("DNS: ").append(dnsMs?.let { "موفق (${it}ms)" } ?: "ناموفق").append("\n")
                    append("TCP/443 مقصد: ").append(targetTcp?.let { "موفق (${f0(it)}ms)" } ?: "ناموفق").append("\n")
                    append("TCP/443 مقصد کنترلی 1.1.1.1: ").append(controlTcp?.let { "موفق (${f0(it)}ms)" } ?: "ناموفق").append("\n")
                    append("HTTPS: ").append(https.code?.toString() ?: "بدون پاسخ (${https.err ?: "خطا"})").append("\n")
                    append("زمان پاسخ HTTPS: ${https.ms}ms\n\n")
                    append("تفسیر: $colorHint\n\n")
                    append("توجه: موفق بودن Ping یا TCP به معنی نبود فیلترینگ نیست. این آزمون فقط از اتصال فعلی گوشی استفاده می‌کند. برای مقایسه، آن را روی اینترنت مخابرات، همراه اول و سایر اپراتورها جداگانه اجرا کنید. نتیجه قطعی فیلترینگ فقط با مقایسه چند مسیر و بررسی دقیق‌تر قابل اعلام است.")
                    append("\n\nاپراتور ثبت‌شده: ").append(spFilterOperator.selectedItem.toString())
                }
                getSharedPreferences("netq", MODE_PRIVATE).edit()
                    .putString("filter_result_${spFilterOperator.selectedItem}", tvFilterResult.text.toString())
                    .apply()
            } catch (e: Exception) {
                tvFilterResult.text = "خطا در اجرای آزمون: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                filterProgress.visibility = View.GONE
                btnFilterCheck.isEnabled = true
            }
        }
    }


    private fun showOperatorComparison() {
        val entries = (getSharedPreferences("netq", MODE_PRIVATE).getString("history", "") ?: "")
            .lines().filter { it.isNotBlank() }
        val grouped = entries.mapNotNull { line ->
            val p = line.split(" | ")
            if (p.size < 7) null else p
        }.groupBy { it[1] }
        if (grouped.isEmpty()) {
            AlertDialog.Builder(this).setTitle("مقایسه اپراتورها")
                .setMessage("ابتدا روی هر اپراتور جداگانه تست کامل اجرا کنید و نام اپراتور را انتخاب کنید.")
                .setPositiveButton("متوجه شدم", null).show()
            return
        }
        val report = buildString {
            append("مقایسه بر اساس گزارش‌های واقعی ذخیره‌شده\n\n")
            grouped.forEach { (operator, rows) ->
                append("● $operator (${rows.size} گزارش)\n")
                append("آخرین نتیجه: ${rows.first().drop(2).joinToString(" | ")}\n\n")
            }
            append("برای مقایسه معتبر، تست‌ها را در زمان نزدیک و با نوع اتصال یکسان انجام دهید.")
        }
        AlertDialog.Builder(this).setTitle("مقایسه اپراتورها").setMessage(report)
            .setPositiveButton("اشتراک‌گذاری") { _, _ -> share(report) }
            .setNegativeButton("بستن", null).show()
    }

    private fun runDnsLookup() {
        val domain = etDnsLookup.text.toString().trim().trimEnd('.')
        if (domain.isBlank() || !domain.contains(".")) {
            Toast.makeText(this, "دامنه معتبر وارد کنید", Toast.LENGTH_SHORT).show()
            return
        }
        btnDnsLookup.isEnabled = false
        tvDnsLookupResult.text = "در حال دریافت رکوردها از DNS-over-HTTPS…"
        dnsGraph.values = emptyMap()
        lifecycleScope.launch {
            try {
                val selected = spDnsType.selectedItem.toString()
                val results = withContext(Dispatchers.IO) {
                    if (selected == "تمام رکوردها") DnsLookup.queryAll(domain)
                    else listOf(DnsLookup.query(domain, selected))
                }
                val counts = results.associate { it.type to it.answers.size }
                dnsGraph.values = counts
                tvDnsLookupResult.text = buildString {
                    append("دامنه: $domain\n")
                    append("حالت استعلام: $selected\n")
                    append("زمان دریافت: ").append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())).append("\n")
                    results.forEach { result ->
                        append("\n━━━━━━━━ ").append(result.type).append(" ━━━━━━━━\n")
                        when {
                            result.error != null -> append("خطا: ").append(result.error).append("\n")
                            result.status == 0 && result.answers.isEmpty() -> append("رکوردی برای این نوع پیدا نشد.\n")
                            result.answers.isNotEmpty() -> result.answers.forEach { answer ->
                                append("• ").append(answer.value).append("\n")
                                append("  TTL: ").append(answer.ttl).append(" ثانیه\n")
                            }
                            else -> append("DNS Status: ").append(result.status).append("\n")
                        }
                    }
                    append("\nمنبع: Cloudflare DNS-over-HTTPS")
                }
            } catch (e: Exception) {
                tvDnsLookupResult.text = "خطا در استعلام DNS: ${e.message ?: e.javaClass.simpleName}"
            } finally { btnDnsLookup.isEnabled = true }
        }
    }

    private fun compareFilterOperators() {
        val a = spFilterCompareA.selectedItem.toString()
        val b = spFilterCompareB.selectedItem.toString()
        if (a == b) {
            Toast.makeText(this, "دو اپراتور متفاوت انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = getSharedPreferences("netq", MODE_PRIVATE)
        val ra = prefs.getString("filter_result_$a", null)
        val rb = prefs.getString("filter_result_$b", null)
        val report = buildString {
            append("مقایسه بررسی دسترسی دو اپراتور\n\n")
            append("اپراتور اول: $a\n")
            append(ra ?: "برای این اپراتور هنوز تستی ذخیره نشده است.")
            append("\n\n────────────────────\n\n")
            append("اپراتور دوم: $b\n")
            append(rb ?: "برای این اپراتور هنوز تستی ذخیره نشده است.")
            append("\n\nتوجه: تست‌ها باید روی اتصال واقعی همان اپراتور اجرا شده باشند؛ انتخاب نام اپراتور به‌تنهایی مسیر اینترنت را تغییر نمی‌دهد.")
        }
        AlertDialog.Builder(this).setTitle("مقایسه اپراتورها").setMessage(report)
            .setPositiveButton("اشتراک‌گذاری") { _, _ -> share(report) }
            .setNegativeButton("بستن", null).show()
    }

    private fun share(text: String) {
        if (text.isBlank()) return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(i, "ارسال گزارش"))
    }

    // ───────── تنظیمات ─────────
    private fun loadSettings() {
        for ((k, id) in cfgFields) findViewById<EditText>(id).setText(Config.get(this, k))
    }

    private fun saveSettings() {
        for ((k, id) in cfgFields) Config.put(this, k, findViewById<EditText>(id).text.toString())
        Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
    }

    private fun resetSettings() {
        for ((k, _) in cfgFields) Config.reset(this, k)
        loadSettings()
        Toast.makeText(this, "به پیش‌فرض برگشت", Toast.LENGTH_SHORT).show()
    }

    // ════════════════════════════════════════════
    //                 تست کامل
    // ════════════════════════════════════════════
    private fun add(section: String) {
        val parts = section.split("\n", limit = 2)
        val title = parts.first().trim()
        val body = parts.getOrNull(1)?.trim().orEmpty()
        log.append("\n━━━━━━━━━━━━━━━━━━━━━━━━\n")
        log.append("◆  ").append(title).append("\n")
        if (body.isNotBlank()) log.append(body).append("\n")
        val plain = log.toString().trimStart()
        val styled = SpannableStringBuilder(plain)
        var start = 0
        plain.split("\n").forEach { line ->
            if (line.startsWith("◆  ")) {
                styled.setSpan(StyleSpan(Typeface.BOLD), start, start + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                styled.setSpan(ForegroundColorSpan(0xFF087E8B.toInt()), start, start + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            start += line.length + 1
        }
        tvLog.text = styled
    }

    private fun runTest() {
        if (running) return
        running = true
        btnStart.isEnabled = false
        progress.visibility = View.VISIBLE
        log.setLength(0)
        tvLog.text = ""
        tvStatus.text = "⏳ در حال تست… (حدود ۳۰ ثانیه)"

        val targets = Config.lines(this, "ping").map { Config.parseTarget(it) }
        val dnsServers = Config.lines(this, "dns_servers")
        val dnsDomains = Config.lines(this, "dns_domains")
        val sites = Config.lines(this, "sites_intl").map { Site(Config.siteName(it), Config.normUrl(it), false) } +
                Config.lines(this, "sites_local").map { Site(Config.siteName(it), Config.normUrl(it), true) }
        val speedUrls = Config.lines(this, "speed").map { Config.normUrl(it) }

        lifecycleScope.launch {
            var severity = 0
            val issues = ArrayList<String>()
            fun issue(sev: Int, msg: String) {
                if (sev > severity) severity = sev
                issues.add((if (sev >= 2) "❌ " else "⚠️ ") + msg)
            }
            fun bad(v: Long?) = v == null || v < 0

            var info: NetInfo? = null
            var pingText = "-"
            var lossText = "-"
            var speedText = "-"

            try {
                // ۱) اطلاعات اتصال
                val ni = withContext(Dispatchers.IO) { readNetInfo() }
                info = ni
                val sb = StringBuilder("📶 اتصال\nنوع: ${ni.type}")
                if (ni.ssid.isNotEmpty()) sb.append("\nشبکه: ${ni.ssid}")
                ni.rssi?.let { sb.append("\nقدرت سیگنال: $it dBm (${rssiLabel(it)})") }
                ni.freq?.let { sb.append("\nفرکانس: $it MHz، کانال ${channel(it)}") }
                ni.link?.let { sb.append("\nسرعت لینک Wi-Fi: $it Mbps") }
                ni.gateway?.let { sb.append("\nGateway: $it") }
                add(sb.toString())

                if (!ni.connected) {
                    issue(2, "به هیچ شبکه‌ای وصل نیستید.")
                    return@launch
                }
                ni.rssi?.let {
                    if (it < -80) {
                        issue(2, "سیگنال Wi-Fi خیلی ضعیف است ($it dBm). به مودم نزدیک‌تر شوید.")
                    } else if (it < -70) {
                        issue(1, "سیگنال Wi-Fi ضعیف است ($it dBm).")
                    }
                }

                // ۲) پینگ / Jitter / Packet Loss
                val gwDef = ni.gateway?.let { gw -> async { ping(gw, 80, 8) } }
                val tDefs = targets.map { t -> async { ping(t.host, t.port, 8) } }
                val gwRes = gwDef?.await()
                val inet = tDefs.map { it.await() }

                val ps = StringBuilder("🏓 پینگ، Jitter و Packet Loss")
                gwRes?.let { ps.append("\n").append(fmtPing("روتر (Gateway)", it)) }
                targets.forEachIndexed { i, t -> ps.append("\n").append(fmtPing(t.host, inet[i])) }
                add(ps.toString())

                val inetOk = inet.any { it.received > 0 }
                if (gwRes != null) {
                    if (gwRes.received == 0 && !inetOk && targets.isNotEmpty()) {
                        issue(2, "نه روتر جواب می‌دهد نه اینترنت؛ مشکل از Wi-Fi یا خود روتر است.")
                    } else if (gwRes.received > 0 && !inetOk && targets.isNotEmpty()) {
                        issue(2, "روتر در دسترس است ولی اینترنت نیست؛ مشکل از لینک اینترنت (WAN/ISP) است.")
                    } else if (gwRes.received > 0 && (gwRes.avg > 20 || gwRes.loss >= 5)) {
                        issue(1, "تاخیر/افت بین گوشی و روتر بالاست (${f0(gwRes.avg)}ms)؛ مشکل از Wi-Fi داخلی (سیگنال یا تداخل) است.")
                    }
                } else if (!inetOk && targets.isNotEmpty()) {
                    issue(2, "هیچ‌کدام از هدف‌ها پینگ نشدند؛ اینترنت قطع است یا مسدود شده.")
                }
                targets.forEachIndexed { i, t ->
                    val r = inet[i]
                    if (r.received > 0) {
                        if (r.loss >= 5) issue(1, "افت بسته (Packet Loss) به ${t.host}: ${f0(r.loss)}٪")
                        if (r.avg > 150) issue(1, "تاخیر بالا به ${t.host}: ${f0(r.avg)}ms")
                        if (r.jitter > 30) issue(1, "نوسان تاخیر (Jitter) بالا به ${t.host}: ${f0(r.jitter)}ms")
                    }
                }
                val okRes = inet.filter { it.received > 0 }
                if (okRes.isNotEmpty()) pingText = f0(okRes.map { it.avg }.average()) + "ms"
                if (inet.isNotEmpty()) lossText = f0(inet.map { it.loss }.average()) + "%"

                // ۳) تست DNS
                if (dnsDomains.isNotEmpty()) {
                    val rows = dnsDomains.map { d -> async { dnsRow(d, dnsServers) } }.map { it.await() }
                    val ds = StringBuilder("🔎 تست DNS")
                    dnsDomains.forEachIndexed { i, d ->
                        val (s, r) = rows[i]
                        ds.append("\n$d → سیستم ${fmtDns(s)}")
                        dnsServers.forEachIndexed { j, sv -> ds.append(" | $sv ${fmtDns(r[j])}") }
                    }
                    add(ds.toString())

                    val (s0, r0) = rows[0]
                    val anySrvOk = r0.any { !bad(it) }
                    if (bad(s0) && anySrvOk) {
                        issue(2, "DNS پیش‌فرض این شبکه جواب نمی‌دهد ولی DNS عمومی کار می‌کند؛ DNS ارائه‌دهنده مشکل دارد (می‌توانید DNS را روی 8.8.8.8 یا 1.1.1.1 بگذارید).")
                    } else if (bad(s0) && r0.isNotEmpty() && !anySrvOk) {
                        if (inetOk) issue(2, "هیچ DNS ای جواب نمی‌دهد؛ احتمالاً پورت 53 مسدود است.")
                    } else if (s0 != null && s0 > 300) {
                        issue(1, "پاسخ DNS کند است (${s0}ms).")
                    }
                    dnsServers.forEachIndexed { j, sv ->
                        if (bad(r0[j]) && (!bad(s0) || anySrvOk)) issue(1, "DNS سرور $sv جواب نمی‌دهد.")
                    }
                }

                // ۴) دسترسی سایت‌ها + Captive Portal
                val capDef = async(Dispatchers.IO) { Net.httpCheck("http://connectivitycheck.gstatic.com/generate_204", false) }
                val siteDefs = sites.map { s -> async(Dispatchers.IO) { Net.httpCheck(s.url, true) } }
                val cap = capDef.await()
                val siteRes = siteDefs.map { it.await() }

                if (sites.isNotEmpty()) {
                    val hs = StringBuilder("🌐 دسترسی سایت‌ها")
                    sites.forEachIndexed { i, s ->
                        val r = siteRes[i]
                        val kind = if (s.internal) "داخلی" else "بین‌المللی"
                        hs.append("\n${s.name} ($kind): ")
                        if (r.ok) hs.append("✅ ${r.code} (${r.ms}ms)") else hs.append("❌ ${r.err ?: "خطا"}")
                    }
                    add(hs.toString())
                }

                val cc = cap.code
                val captivePortal = cc != null && cc != 204 && cc in 200..399
                if (captivePortal) {
                    issue(2, "این شبکه صفحه‌ی ورود (Captive Portal / هات‌اسپات) دارد و تا لاگین نکنید اینترنت نمی‌دهد.")
                } else {
                    val intlIdx = sites.indices.filter { !sites[it].internal }
                    val locIdx = sites.indices.filter { sites[it].internal }
                    val intlOk = intlIdx.count { siteRes[it].ok }
                    val locOk = locIdx.count { siteRes[it].ok }
                    if (intlIdx.isNotEmpty() && locIdx.isNotEmpty()) {
                        if (intlOk == 0 && locOk > 0) {
                            issue(2, "اینترنت بین‌المللی قطع یا فیلتر است؛ فقط سایت‌های داخلی باز می‌شوند.")
                        } else if (intlOk > 0 && locOk == 0) {
                            issue(1, "سایت‌های داخلی باز نمی‌شوند (مشکل DNS داخلی یا مسدودی).")
                        }
                    }
                    if (sites.isNotEmpty() && intlOk == 0 && locOk == 0 && inetOk) {
                        issue(2, "پینگ هست ولی هیچ سایتی باز نمی‌شود؛ احتمال مشکل DNS، فیلتر یا پورتال.")
                    }
                    siteRes.forEachIndexed { i, r ->
                        if (r.ok && r.ms > 3000) issue(1, "بازشدن ${sites[i].name} کند است (${r.ms}ms).")
                    }
                }

                // ۵) سرعت دانلود
                val reachable = inetOk || siteRes.any { it.ok }
                if (reachable && !captivePortal && speedUrls.isNotEmpty()) {
                    tvStatus.text = "⏳ در حال تست سرعت دانلود…"
                    var mbps: Double? = null
                    for (u in speedUrls) {
                        mbps = withContext(Dispatchers.IO) { Net.speedTest(u) }
                        if (mbps != null) break
                    }
                    if (mbps != null) {
                        speedText = f1(mbps) + " Mbps"
                        add("⬇️ سرعت دانلود: $speedText")
                        if (mbps < 1.0) {
                            issue(2, "سرعت دانلود بسیار پایین است (${f1(mbps)} Mbps).")
                        } else if (mbps < 5.0) {
                            issue(1, "سرعت دانلود پایین است (${f1(mbps)} Mbps).")
                        }
                    } else {
                        speedText = "ناموفق"
                        add("⬇️ سرعت دانلود: تست انجام نشد (سرور تست در دسترس نیست)")
                        issue(1, "تست سرعت انجام نشد؛ سرورهای تست مسدود یا در دسترس نیستند.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                issues.add("خطای غیرمنتظره: ${e.message}")
            } finally {
                val verdict = when (severity) {
                    0 -> "✅ کیفیت اینترنت خوب است"
                    1 -> "⚠️ مشکلات جزئی وجود دارد"
                    else -> "❌ مشکل جدی وجود دارد"
                }
                tvStatus.text = verdict + if (issues.isEmpty()) "" else "\n\n" + issues.joinToString("\n\n")
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                val net = (info?.type ?: "-") + (info?.ssid?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
                saveHistory("$time | ${spOperator.selectedItem} | $net | ping $pingText | loss $lossText | speed $speedText | ${verdict.take(2)}")
                saveFullSnapshot(time, spOperator.selectedItem.toString(), net, pingText, lossText, speedText, verdict, log.toString())
                progress.visibility = View.GONE
                btnStart.isEnabled = true
                running = false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readNetInfo(): NetInfo {
        val none = NetInfo(false, "بدون اتصال", "", null, null, null, null)
        val net = cm.activeNetwork ?: return none
        val caps = cm.getNetworkCapabilities(net) ?: return none
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "موبایل"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "نامشخص"
        }
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return NetInfo(true, type, "", null, null, null, null)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wi = wm.connectionInfo
        val ssid = wi?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" && it.isNotEmpty() } ?: ""
        val g = wm.dhcpInfo?.gateway ?: 0
        val gw = if (g != 0) String.format(
            Locale.US, "%d.%d.%d.%d",
            g and 0xff, (g shr 8) and 0xff, (g shr 16) and 0xff, (g shr 24) and 0xff
        ) else null
        return NetInfo(
            true, type, ssid,
            wi?.rssi?.takeIf { it > -127 },
            wi?.frequency?.takeIf { it > 0 },
            wi?.linkSpeed?.takeIf { it > 0 },
            gw
        )
    }

    private fun rssiLabel(r: Int) = when {
        r >= -55 -> "عالی"
        r >= -67 -> "خوب"
        r >= -75 -> "متوسط"
        r >= -85 -> "ضعیف"
        else -> "خیلی ضعیف"
    }

    private fun channel(f: Int): Int = when {
        f == 2484 -> 14
        f in 2412..2472 -> (f - 2407) / 5
        f in 5170..5895 -> (f - 5000) / 5
        f in 5955..7115 -> (f - 5950) / 5
        else -> 0
    }

    private suspend fun ping(host: String, tcpPort: Int, count: Int): Net.PingResult =
        withContext(Dispatchers.IO) {
            val icmp = Net.icmpPing(host, count)
            if (icmp != null && icmp.received > 0) return@withContext icmp
            val tcp = Net.tcpPing(host, tcpPort, count)
            if (tcp.received > 0) {
                tcp.copy(method = if (icmp != null) "TCP، ICMP مسدود است" else "TCP")
            } else {
                icmp ?: tcp
            }
        }

    private fun fmtPing(name: String, r: Net.PingResult): String =
        if (r.received == 0) "$name: ❌ بدون پاسخ"
        else "$name: ${f0(r.avg)}ms | jitter ${f0(r.jitter)} | loss ${f0(r.loss)}% (${r.method})"

    private suspend fun dnsRow(d: String, servers: List<String>): Pair<Long?, List<Long?>> =
        coroutineScope {
            val s = async(Dispatchers.IO) { Net.sysResolve(d, 4000) }
            val r = servers.map { sv -> async(Dispatchers.IO) { Net.dnsQuery(sv, d) } }
            Pair(s.await(), r.map { it.await() })
        }

    private fun fmtDns(v: Long?): String = when {
        v == null -> "timeout"
        v < 0 -> "نامعتبر"
        else -> "${v}ms"
    }

    // ───────── تاریخچه ─────────
    private fun saveHistory(line: String) {
        val sp = getSharedPreferences("netq", MODE_PRIVATE)
        val old = sp.getString("history", "") ?: ""
        val lines = (listOf(line) + old.split("\n").filter { it.isNotBlank() }).take(40)
        sp.edit().putString("history", lines.joinToString("\n")).apply()
    }

    private fun saveFullSnapshot(time: String, operator: String, network: String,
                                 ping: String, loss: String, speed: String,
                                 verdict: String, report: String) {
        val prefs = getSharedPreferences("netq", MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString("full_history", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val item = JSONObject().apply {
            put("time", time); put("operator", operator); put("network", network)
            put("ping", ping); put("loss", loss); put("speed", speed)
            put("verdict", verdict); put("report", report)
        }
        val next = JSONArray().put(item)
        for (i in 0 until minOf(old.length(), 9)) next.put(old.getJSONObject(i))
        prefs.edit().putString("full_history", next.toString()).apply()
    }

    private fun compareLastTwoTests() {
        val arr = try {
            JSONArray(getSharedPreferences("netq", MODE_PRIVATE).getString("full_history", "[]") ?: "[]")
        } catch (_: Exception) { JSONArray() }
        if (arr.length() < 2) {
            AlertDialog.Builder(this).setTitle("مقایسه دو تست")
                .setMessage("برای مقایسه، ابتدا تست کامل را روی اینترنت اول اجرا کنید؛ سپس اینترنت را دستی تغییر دهید و تست دوم را اجرا کنید.")
                .setPositiveButton("متوجه شدم", null).show()
            return
        }
        val newer = arr.getJSONObject(0)
        val older = arr.getJSONObject(1)
        fun numeric(value: String): Double? = Regex("-?\\d+(?:\\.\\d+)?").find(value)?.value?.toDoubleOrNull()
        fun delta(label: String, key: String, unit: String, lowerBetter: Boolean): String {
            val a = numeric(older.optString(key))
            val b = numeric(newer.optString(key))
            if (a == null || b == null) return "$label: داده کافی برای محاسبه اختلاف وجود ندارد"
            val diff = b - a
            val direction = when {
                kotlin.math.abs(diff) < 0.0001 -> "بدون تغییر"
                (diff < 0 && lowerBetter) || (diff > 0 && !lowerBetter) -> "بهبود"
                else -> "افت"
            }
            return "$label: ${"%.1f".format(Locale.US, a)} → ${"%.1f".format(Locale.US, b)} $unit  |  اختلاف: ${if (diff > 0) "+" else ""}${"%.1f".format(Locale.US, diff)} $unit ($direction)"
        }
        val report = buildString {
            append("مقایسه دو تست کامل اینترنت\n")
            append("تست اول: ${older.optString("operator")} | ${older.optString("time")} | ${older.optString("network")}\n")
            append("تست دوم: ${newer.optString("operator")} | ${newer.optString("time")} | ${newer.optString("network")}\n\n")
            append("خلاصه شاخص‌های عددی\n")
            append(delta("میانگین Ping", "ping", "ms", true)).append("\n")
            append(delta("Packet Loss", "loss", "%", true)).append("\n")
            append(delta("سرعت دانلود", "speed", "Mbps", false)).append("\n\n")
            append("نتیجه تست اول: ${older.optString("verdict")}\n")
            append("نتیجه تست دوم: ${newer.optString("verdict")}\n\n")
            append("گزارش کامل تست اول\n${older.optString("report")}\n\n")
            append("════════════════════════════════\n")
            append("گزارش کامل تست دوم\n${newer.optString("report")}\n\n")
            append("توجه: تست‌ها باید با مقصدها و تنظیمات یکسان و در فاصله زمانی نزدیک اجرا شوند. اعداد مربوط به دو اجرای آخر هستند.")
        }
        AlertDialog.Builder(this).setTitle("مقایسه کامل دو تست")
            .setMessage(report)
            .setPositiveButton("اشتراک‌گذاری") { _, _ -> share(report) }
            .setNegativeButton("بستن", null).show()
    }

    private fun showHistory() {
        val sp = getSharedPreferences("netq", MODE_PRIVATE)
        val entries = (sp.getString("history", "") ?: "").lines().filter { it.isNotBlank() }
        if (entries.isEmpty()) {
            AlertDialog.Builder(this).setTitle("گزارش‌های ذخیره‌شده")
                .setMessage("هنوز گزارشی ذخیره نشده است. پس از اجرای تست کامل، نتیجه‌ها اینجا ثبت می‌شوند.")
                .setPositiveButton("متوجه شدم", null).show()
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 8, 18, 8)
        }
        entries.forEachIndexed { index, entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 14, 16, 14)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.report_card)
            }
            val title = TextView(this).apply {
                text = "گزارش ${entries.size - index}"
                textSize = 14f
                setTextColor(0xFF087E8B.toInt())
                setTypeface(typeface, Typeface.BOLD)
            }
            val detail = TextView(this).apply {
                text = entry.replace(" | ", "\n")
                textSize = 13f
                setTextColor(0xFF263746.toInt())
                setLineSpacing(5f, 1.05f)
                setPadding(0, 8, 0, 0)
            }
            card.addView(title)
            card.addView(detail)
            container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
        }
        val scroll = ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this)
            .setTitle("گزارش‌های ذخیره‌شده (${entries.size})")
            .setView(scroll)
            .setPositiveButton("بستن", null)
            .setNeutralButton("اشتراک‌گذاری همه") { _, _ -> share("گزارش‌های NetQuality\n\n" + entries.joinToString("\n\n")) }
            .setNegativeButton("پاک کردن همه") { _, _ ->
                AlertDialog.Builder(this).setMessage("همه گزارش‌های ذخیره‌شده پاک شوند؟")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("پاک شود") { _, _ ->
                        sp.edit().remove("history").apply()
                        Toast.makeText(this, "گزارش‌ها پاک شدند", Toast.LENGTH_SHORT).show()
                    }.show()
            }.show()
    }

    // ════════════════════════════════════════════
    //            تریس / پلاتر (مثل PingPlotter)
    // ════════════════════════════════════════════
    private fun togglePlot() {
        if (plotJob?.isActive == true) {
            plotJob?.cancel()
            return
        }
        val host = etHost.text.toString().trim()
        if (host.isEmpty()) return
        val tcp = rbTcp.isChecked
        val port = etPort.text.toString().trim().toIntOrNull() ?: 443
        btnPlot.text = "توقف"
        plotJob = lifecycleScope.launch {
            try {
                if (tcp) runTcpPlot(host, port) else runIcmpPlot(host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tvPlotInfo.text = "خطا: ${e.message}"
            } finally {
                btnPlot.text = "شروع"
            }
        }
    }

    private suspend fun runIcmpPlot(host: String) {
        tvPlotInfo.text = "⏳ در حال پیدا کردن مسیر…"
        tvTable.text = ""
        graph.data = emptyList()
        graphLarge.data = emptyList()
        graphJitter.data = emptyList()
        graphLoss.data = emptyList()

        val ip = withContext(Dispatchers.IO) {
            try {
                InetAddress.getByName(host).hostAddress
            } catch (e: Exception) {
                null
            }
        }
        if (ip == null) {
            tvPlotInfo.text = "❌ آدرس پیدا نشد (DNS جواب نداد)."
            return
        }
        val base = withContext(Dispatchers.IO) { Net.baseline() }

        // کشف مسیر: همه‌ی TTL ها هم‌زمان
        val maxTtl = 30
        val probes = coroutineScope {
            (1..maxTtl).map { t -> async(Dispatchers.IO) { Net.probeTtl(ip, t, base) } }
                .map { it.await() }
        }
        val destIdx = probes.indexOfFirst { it.reached }
        val lastReply = probes.indexOfLast { it.ip != null } + 1
        if (destIdx < 0 && lastReply == 0) {
            tvPlotInfo.text = "❌ هیچ پاسخی نیامد. یا ICMP روی این شبکه بسته است یا گوشی از ping با TTL پشتیبانی نمی‌کند. حالت TCP Ping را امتحان کنید."
            return
        }
        val hops = if (destIdx >= 0) destIdx + 1 else lastReply
        val stats = List(hops) { HopStat() }
        for (i in 0 until hops) stats[i].add(probes[i].rtt, probes[i].ip)

        val note = if (destIdx >= 0) "" else "\n⚠️ مقصد به ICMP جواب نمی‌دهد؛ مسیر تا آخرین هاپ پاسخ‌دهنده نشان داده شده."
        while (true) {
            render(stats, "مقصد: $host ($ip) | هاپ: $hops$note")
            val t0 = System.currentTimeMillis()
            val res = coroutineScope {
                (1..hops).map { t -> async(Dispatchers.IO) { Net.probeTtl(ip, t, base) } }
                    .map { it.await() }
            }
            res.forEachIndexed { i, p -> stats[i].add(p.rtt, p.ip) }
            val el = System.currentTimeMillis() - t0
            delay(maxOf(0L, refreshIntervalMs() - el))
        }
    }

    private suspend fun runTcpPlot(host: String, port: Int) {
        tvTable.text = ""
        graph.data = emptyList()
        graphLarge.data = emptyList()
        graphJitter.data = emptyList()
        graphLoss.data = emptyList()
        val s = HopStat()
        s.ip = "$host:$port"
        while (true) {
            val rtt = withContext(Dispatchers.IO) { Net.tcpOnce(host, port, 2000) }
            s.add(rtt, null)
            render(listOf(s), "TCP Ping به $host پورت $port")
            delay(refreshIntervalMs())
        }
    }

    private fun refreshIntervalMs(): Long {
        val seconds = etRefreshSeconds.text.toString().toLongOrNull()?.coerceIn(1L, 60L) ?: 1L
        return seconds * 1000L
    }

    private fun render(stats: List<HopStat>, header: String) {
        val rows = ArrayList<String>()
        rows.add("HOP | IP ADDRESS | PACKET LOSS | LAST ms | AVG ms | MIN ms | MAX ms | HISTORY")
        stats.forEachIndexed { i, s ->
            val ip = s.ip ?: "*"
            val last = if (s.recv == 0) "—" else f0(s.last)
            val avg = if (s.recv == 0) "—" else f0(s.avg)
            val mn = if (s.recv == 0) "—" else f0(s.min)
            val mx = if (s.recv == 0) "—" else f0(s.max)
            rows.add(String.format(Locale.US,
                "%02d | %-39s | %5.1f%% | %6s | %6s | %6s | %6s | %s",
                i + 1, ip, s.loss, last, avg, mn, mx, spark(s.hist)))
        }
        val table = rows.joinToString("\n")
        val sent = stats.lastOrNull()?.sent ?: 0
        tvTable.text = table
        tvPlotInfo.text = "$header | نمونه: $sent | زمان‌ها میلی‌ثانیه (برای هاپ‌های میانی تقریبی)"
        val selected = stats.last()
        val lastPing = selected.hist.lastOrNull()?.takeIf { it >= 0 }?.let { "آخرین: ${f0(it)} ms  |  میانگین: ${f0(selected.avg)} ms" } ?: "آخرین: بدون پاسخ  |  میانگین: ${f0(selected.avg)} ms"
        val lastJitter = selected.jitterHist.lastOrNull()?.takeIf { it >= 0 }?.let { "آخرین: ${f0(it)} ms" } ?: "آخرین: —"
        val lastLoss = "مقدار فعلی: ${f1(selected.loss)}%  |  بسته‌های ارسالی: ${selected.sent}"
        graph.summaryText = lastPing
        graphLarge.summaryText = lastPing
        graphJitter.summaryText = lastJitter
        graphLoss.summaryText = lastLoss
        graph.data = selected.hist.toList()
        graphLarge.data = selected.hist.toList()
        graphJitter.data = selected.jitterHist.toList()
        graphLoss.data = selected.lossHist.toList()
        lastPlotReport = header + "\n" + table + "\nPing: ${f0(selected.avg)} ms | Jitter لحظه‌ای: ${f0(selected.jitterHist.lastOrNull() ?: 0.0)} ms | Packet Loss: ${f1(selected.loss)}%"
    }

    private fun spark(h: List<Double>): String {
        val last = h.takeLast(14)
        val mx = (last.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        val chars = "▁▂▃▄▅▆▇█"
        return last.joinToString("") { v ->
            if (v < 0) "×" else chars[minOf(7, (v / mx * 7.999).toInt())].toString()
        }
    }

    private fun f0(d: Double) = String.format(Locale.US, "%.0f", d)
    private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)
}
