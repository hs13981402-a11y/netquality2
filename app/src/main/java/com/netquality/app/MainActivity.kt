package com.netquality.app

import android.Manifest
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
import android.widget.ArrayAdapter
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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
    private lateinit var etFilterTestName: EditText
    private lateinit var etDnsServer: EditText
    private lateinit var spDnsServer: Spinner
    private lateinit var dnsChartsContainer: LinearLayout
    private lateinit var cbSpeedTest: CheckBox
    private lateinit var tvFilterResult: TextView
    private lateinit var filterProgress: ProgressBar
    private lateinit var btnFilterCheck: Button
    private lateinit var panelDns: View
    private lateinit var panelPacketLoss: View
    private lateinit var tvUdpLossResult: TextView
    private lateinit var tvUploadLossResult: TextView
    private lateinit var etIperfHost: EditText
    private lateinit var etIperfPort: EditText
    private lateinit var cbAutoIperfServer: CheckBox
    private lateinit var spManualIperfServer: Spinner
    private lateinit var manualIperfActions: LinearLayout
    private data class ManualIperfServer(val name: String, val host: String, val port: Int)
    private val manualIperfServers = mutableListOf<ManualIperfServer>()
    private var selectedManualIperfServer = 0
    private lateinit var etDnsLookup: EditText
    private lateinit var spDnsType: Spinner
    private lateinit var tvDnsLookupResult: TextView
    private lateinit var btnDnsLookup: Button

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnStart: Button
    private lateinit var btnHistory: Button
    private lateinit var progress: ProgressBar
    private lateinit var cbAuto: CheckBox
    private lateinit var cbUploadTest: CheckBox

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
    private var dnsJob: Job? = null
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
        val appearancePrefs = getSharedPreferences("netq", MODE_PRIVATE)
        val languageTag = appearancePrefs.getString("ui_language", "fa") ?: "fa"
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != languageTag) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
        }
        val darkTheme = appearancePrefs.getString("ui_theme", "light") == "dark"
        AppCompatDelegate.setDefaultNightMode(
            if (darkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
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
        etFilterTestName = findViewById(R.id.etFilterTestName)
        etDnsServer = findViewById(R.id.etDnsServer)
        spDnsServer = findViewById(R.id.spDnsServer)
        dnsChartsContainer = findViewById(R.id.dnsChartsContainer)
        cbSpeedTest = findViewById(R.id.cbSpeedTest)
        tvFilterResult = findViewById(R.id.tvFilterResult)
        filterProgress = findViewById(R.id.filterProgress)
        btnFilterCheck = findViewById(R.id.btnFilterCheck)
        panelDns = findViewById(R.id.panelDns)
        panelPacketLoss = findViewById(R.id.panelPacketLoss)
        tvUdpLossResult = findViewById(R.id.tvUdpLossResult)
        tvUploadLossResult = findViewById(R.id.tvUploadLossResult)
        etIperfHost = findViewById(R.id.etIperfHost)
        etIperfPort = findViewById(R.id.etIperfPort)
        cbAutoIperfServer = findViewById(R.id.cbAutoIperfServer)
        val iperfPrefs = getSharedPreferences("netq", MODE_PRIVATE)
        etIperfHost.setText(iperfPrefs.getString("iperf_host", "speedtest.uztelecom.uz"))
        etIperfPort.setText(iperfPrefs.getInt("iperf_port", 5200).toString())
        cbAutoIperfServer.isChecked = iperfPrefs.getBoolean("iperf_auto", true)
        cbAutoIperfServer.setOnCheckedChangeListener { _, checked ->
            iperfPrefs.edit().putBoolean("iperf_auto", checked).apply()
            refreshManualIperfUi()
        }
        setupManualIperfServerManager()
        etDnsLookup = findViewById(R.id.etDnsLookup)
        spDnsType = findViewById(R.id.spDnsType)
        tvDnsLookupResult = findViewById(R.id.tvDnsLookupResult)
        btnDnsLookup = findViewById(R.id.btnDnsLookup)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        btnStart = findViewById(R.id.btnStart)
        btnHistory = findViewById(R.id.btnHistory)
        progress = findViewById(R.id.progress)
        cbAuto = findViewById(R.id.cbAuto)
        cbUploadTest = findViewById(R.id.cbUploadTest)
        cbUploadTest.isChecked = getSharedPreferences("netq", MODE_PRIVATE).getBoolean("upload_test_enabled", false)
        cbUploadTest.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("netq", MODE_PRIVATE).edit().putBoolean("upload_test_enabled", checked).apply()
        }

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
        findViewById<Button>(R.id.tabPacketLoss).setOnClickListener { showPanel(6) }
        findViewById<Button>(R.id.btnUdpLoss).setOnClickListener { runStandaloneUdpLoss() }
        findViewById<Button>(R.id.btnUploadLoss).setOnClickListener { runUploadUdpLoss() }
        btnDnsLookup.setOnClickListener { runDnsLookup() }
        findViewById<Button>(R.id.btnAddDnsServer).setOnClickListener { addDnsServer() }
        refreshDnsServers()
        spDnsServer.setOnLongClickListener {
            manageDnsServer()
            true
        }
        findViewById<Button>(R.id.btnCompareFilter).setOnClickListener { compareFilterTests() }
        findViewById<Button>(R.id.btnShareFilter).setOnClickListener {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            share("گزارش بررسی دسترسی\nزمان: $stamp\n\n${tvFilterResult.text}")
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
        setupAppearanceSettings()
        applyUiLanguage()
        applyAppearanceColors()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1
            )
        }
        cbAuto.isChecked = getSharedPreferences("netq", MODE_PRIVATE).getBoolean("auto_test", false)
        cbAuto.setOnCheckedChangeListener { _, checked -> getSharedPreferences("netq", MODE_PRIVATE).edit().putBoolean("auto_test", checked).apply() }
        cm.registerDefaultNetworkCallback(netCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cm.unregisterNetworkCallback(netCallback)
        } catch (_: Exception) {
        }
    }


    private fun setupAppearanceSettings() {
        val scroll = panelSettings as? ScrollView ?: return
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            setBackgroundResource(R.drawable.panel_card)
        }
        val title = TextView(this).apply {
            text = "زبان و ظاهر برنامه / Language & appearance"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }
        val languageLabel = TextView(this).apply {
            text = "زبان برنامه / App language"
            textSize = 14f
            setPadding(0, 12, 0, 4)
        }
        val language = Spinner(this)
        language.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            listOf("فارسی", "English")).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val themeLabel = TextView(this).apply {
            text = "قالب رنگی / Theme"
            textSize = 14f
            setPadding(0, 12, 0, 4)
        }
        val theme = Spinner(this)
        theme.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            listOf("روشن / Light", "تیره / Dark")).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        section.addView(title)
        section.addView(languageLabel)
        section.addView(language)
        section.addView(themeLabel)
        section.addView(theme)
        content.addView(section, 0)
        val prefs = getSharedPreferences("netq", MODE_PRIVATE)
        language.setSelection(if ((prefs.getString("ui_language", "fa") ?: "fa") == "en") 1 else 0)
        theme.setSelection(if ((prefs.getString("ui_theme", "light") ?: "light") == "dark") 1 else 0)
        language.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = if (position == 1) "en" else "fa"
                if ((prefs.getString("ui_language", "fa") ?: "fa") != selected) {
                    prefs.edit().putString("ui_language", selected).apply()
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selected))
                }
            }
        }
        theme.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = if (position == 1) "dark" else "light"
                if ((prefs.getString("ui_theme", "light") ?: "light") != selected) {
                    prefs.edit().putString("ui_theme", selected).apply()
                    AppCompatDelegate.setDefaultNightMode(
                        if (selected == "dark") AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                    )
                }
            }
        }
    }

    private fun applyUiLanguage() {
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags().startsWith("en")) {
            val translations = mapOf(
                "◉  تست کامل" to "◉  Full test",
                "⌁  تریس / پلاتر" to "⌁  Trace / Plotter",
                "▥  نمودار" to "▥  Charts",
                "◈  فیلترینگ" to "◈  Access check",
                "⚙  تنظیمات" to "⚙  Settings",
                "برای شروع، دکمه‌ی تست را بزنید" to "Press Start to begin network tests",
                "اجرای خودکار پس از اتصال مجدد (اختیاری)" to "Run automatically after reconnect (optional)",
                "شروع تست" to "Start test",
                "مقایسه دو تست آخر (قبل و بعد از تغییر اینترنت)" to "Compare the last two tests",
                "ارسال گزارش" to "Share report",
                "گزارش‌های ذخیره‌شده" to "Saved reports",
                "هاست یا IP مقصد" to "Target host or IP",
                "Trace (ICMP)" to "Trace (ICMP)",
                "TCP Ping" to "TCP Ping",
                "پورت (فقط برای حالت TCP)" to "Port (TCP only)",
                "فاصله به‌روزرسانی نمودار (ثانیه)" to "Chart refresh interval (seconds)",
                "عدد بین ۱ تا ۶۰ ثانیه" to "Enter a value from 1 to 60 seconds",
                "شروع" to "Start",
                "پایش زنده کیفیت ارتباط" to "Live connection quality monitoring",
                "تشخیص محدودیت دسترسی" to "Access restriction diagnostics",
                "دامنه‌های آماده" to "Preset domains",
                "یا دامنه دلخواه مثل example.com" to "Or enter a domain, e.g. example.com",
                "اپراتوری که همین حالا به اینترنت آن متصل هستید" to "Current internet provider",
                "شروع بررسی دسترسی" to "Start access check",
                "نام‌گذاری و مقایسه تست‌ها" to "Name and compare tests",
                "انتخاب و مقایسه تست‌ها" to "Select and compare tests",
                "اشتراک‌گذاری گزارش بررسی" to "Share diagnostic report",
                "◎  DNS" to "◎  DNS",
                "▤  Packet Loss" to "▤  Packet Loss",
                "DNS Explorer" to "DNS Explorer",
                "دامنه؛ مثال example.com" to "Domain, e.g. example.com",
                "DNS Server انتخابی" to "Selected DNS server",
                "IP سرور DNS مثل 192.168.1.1" to "DNS server IP, e.g. 192.168.1.1",
                "افزودن" to "Add",
                "شروع پایش DNS" to "Start DNS monitoring",
                "Packet Loss Lab" to "Packet Loss Lab",
                "سرور iperf3 (سرور باید از قبل در حالت Server فعال باشد)" to "iperf3 server (must be running in server mode)",
                "انتخاب خودکار سرور رایگان و جایگزین در صورت قطعی" to "Auto-select public servers and try alternatives",
                "در حالت خودکار، سرورها به‌ترتیب بررسی می‌شوند. برای استفاده از سرور خودتان، این گزینه را خاموش کنید." to "Auto mode checks servers in sequence. Turn this off to use your own server.",
                "آدرس IP یا دامنه سرور" to "Server IP address or hostname",
                "پورت iperf3 (پیش‌فرض 5201)" to "iperf3 port (default 5201)",
                "۱. Packet Loss در UDP" to "1. UDP packet loss",
                "اجرای تست UDP" to "Run UDP test",
                "۲. افت پروب UDP حین Upload" to "2. UDP probe loss during upload",
                "اجرای تست UDP زیر بار آپلود" to "Run UDP test during upload",
                "هر خط یک مورد. خطی که با # شروع شود نادیده گرفته می‌شود." to "One item per line. Lines starting with # are ignored.",
                "اهداف پینگ (هر خط یک مورد؛ host یا host:port برای حالت TCP)" to "Ping targets (one per line; host or host:port for TCP)",
                "سرورهای DNS برای تست (هر خط یک IP)" to "DNS servers for testing (one IP per line)",
                "دامنه‌های تست DNS" to "DNS test domains",
                "سایت‌های بین‌المللی برای تست دسترسی" to "International websites for access checks",
                "سایت‌های داخلی برای تست دسترسی" to "Local websites for access checks",
                "آدرس فایل‌های تست سرعت دانلود" to "Download speed test file URLs",
                "اجرای تست سرعت دانلود در تست کامل" to "Run download speed test during full test",
                "در حالت خاموش، تست کامل بدون دانلود فایل حجیم اجرا می‌شود." to "When disabled, the full test skips large downloads.",
                "اجرای تست آپلود و پایش UDP حین آپلود در تست کامل" to "Run upload test and UDP monitoring during full test",
                "در صورت فعال‌سازی، تست آپلود به‌همراه پایش UDP اجرا می‌شود." to "When enabled, upload testing runs with UDP monitoring.",
                "ذخیره" to "Save",
                "بازگشت به پیش‌فرض" to "Restore defaults",
                "هر سه نمودار هم‌زمان به‌روزرسانی می‌شوند. فاصله Refresh را از تب «تریس / پلاتر» تنظیم کنید." to "All three charts refresh together. Set the interval in Trace / Plotter.",
                "آزمون مرحله‌ای DNS، TCP، TLS/SNI، HTTPS و شناسایی CDN؛ هر لایه جداگانه نتیجه‌گیری می‌شود و تشخیص فیلترینگ قطعی نیست." to "Layered DNS, TCP, TLS/SNI, HTTPS and CDN checks. Each layer is assessed separately; results are not definitive proof of filtering.",
                "نام‌گذاری و مقایسه تست‌ها" to "Name and compare tests",
                "هر تست با نام و زمان خودش ذخیره می‌شود؛ از فهرست می‌توانید دو یا چند تست را برای مقایسه انتخاب کنید." to "Each test is saved with its name and timestamp. Select multiple tests to compare.",
                "استعلام مستقیم از DNS انتخاب‌شده، پایش زمان پاسخ و نمایش وضعیت رکوردهای مختلف." to "Query the selected DNS server, monitor response time and inspect record types.",
                "نمودار زنده زمان پاسخ DNS · پایش پیوسته" to "Live DNS response-time chart",
                "آزمون‌های واقعی UDP با موتور iperf3؛ آمار افت بسته و Jitter از گزارش سرور دریافت می‌شود." to "Real UDP tests powered by iperf3. Packet loss and jitter are read from the server report."
            )
            val root = findViewById<View>(android.R.id.content)
            root.layoutDirection = View.LAYOUT_DIRECTION_LTR
            fun walk(view: View) {
                if (view is TextView) {
                    val original = view.text?.toString()
                    if (original != null) translations[original]?.let { view.text = it }
                    if (view is EditText) {
                        val hint = view.hint?.toString()
                        if (hint != null) translations[hint]?.let { view.hint = it }
                    }
                }
                if (view is android.view.ViewGroup) {
                    for (i in 0 until view.childCount) walk(view.getChildAt(i))
                }
            }
            walk(root)
        }
    }

    private fun applyAppearanceColors() {
        val dark = getSharedPreferences("netq", MODE_PRIVATE).getString("ui_theme", "light") == "dark"
        val root = findViewById<View>(android.R.id.content)
        root.setBackgroundColor(Color.parseColor(if (dark) "#0B1220" else "#F3F6FA"))
        val panels = intArrayOf(R.id.panelTest, R.id.panelPlot, R.id.panelSettings, R.id.panelChart, R.id.panelFilter, R.id.panelDns, R.id.panelPacketLoss)
        panels.forEach { findViewById<View>(it)?.setBackgroundColor(Color.parseColor(if (dark) "#0B1220" else "#F3F6FA")) }
        val tabBar = findViewById<View>(R.id.tabTest).parent?.parent as? View
        tabBar?.setBackgroundColor(Color.parseColor(if (dark) "#070E1B" else "#101B35"))
        if (dark) {
            val lightText = Color.parseColor("#E6EDF7")
            val rootView = findViewById<View>(android.R.id.content)
            fun recolor(view: View) {
                if (view is TextView) {
                    view.setTextColor(lightText)
                    if (view is EditText) view.setHintTextColor(Color.parseColor("#91A4BB"))
                }
                if (view is android.view.ViewGroup) {
                    for (i in 0 until view.childCount) recolor(view.getChildAt(i))
                }
            }
            recolor(rootView)
            intArrayOf(R.id.tvStatus, R.id.tvLog, R.id.tvFilterResult, R.id.tvDnsLookupResult, R.id.tvUdpLossResult, R.id.tvUploadLossResult, R.id.tvTable, R.id.tvPlotInfo).forEach {
                findViewById<View>(it)?.setBackgroundColor(Color.parseColor("#162338"))
            }
        }
    }

    private fun showPanel(i: Int) {
        panelTest.visibility = if (i == 0) View.VISIBLE else View.GONE
        panelPlot.visibility = if (i == 1) View.VISIBLE else View.GONE
        panelSettings.visibility = if (i == 2) View.VISIBLE else View.GONE
        panelChart.visibility = if (i == 3) View.VISIBLE else View.GONE
        panelFilter.visibility = if (i == 4) View.VISIBLE else View.GONE
        panelDns.visibility = if (i == 5) View.VISIBLE else View.GONE
        panelPacketLoss.visibility = if (i == 6) View.VISIBLE else View.GONE

        val tabIds = intArrayOf(R.id.tabTest, R.id.tabPlot, R.id.tabSettings, R.id.tabChart, R.id.tabFilter, R.id.tabDns, R.id.tabPacketLoss)
        tabIds.forEachIndexed { index, id ->
            val tab = findViewById<Button>(id)
            val selected = index == i
            tab.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (selected) "#0E9F9A" else "#263858"))
            tab.setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#DCE8F4"))
        }
    }

    private data class PublicIperfServer(val host: String, val port: Int, val location: String)

    // فهرست اولیه سرورهای عمومی که در فهرست‌های عمومی، پشتیبانی UDP برای آن‌ها ذکر شده است.
    // وضعیت دسترسی هر سرور در زمان اجرای آزمون دوباره بررسی می‌شود.
    private val publicIperfServers = listOf(
        PublicIperfServer("iperf.online.net", 5200, "فرانسه - پاریس"),
        PublicIperfServer("ping.online.net", 5200, "فرانسه - ویتری"),
        PublicIperfServer("iperf3-vie-at.alwyzon.net", 5201, "اتریش - وین"),
        PublicIperfServer("185.180.12.40", 5201, "اتریش - وین"),
        PublicIperfServer("37.19.203.1", 5201, "بلغارستان - صوفیه"),
        PublicIperfServer("138.199.14.66", 5201, "فرانسه - مارسی"),
        PublicIperfServer("speedtest.uninet.az", 5201, "آذربایجان - باکو")
    )


    private fun setupManualIperfServerManager() {
        val prefs = getSharedPreferences("netq", MODE_PRIVATE)
        manualIperfServers.clear()
        val raw = prefs.getString("iperf_manual_servers", null)
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    val host = item.optString("host").trim()
                    val port = item.optInt("port", -1)
                    if (name.isNotBlank() && host.isNotBlank() && port in 1..65535) {
                        manualIperfServers.add(ManualIperfServer(name, host, port))
                    }
                }
            } catch (_: Exception) { }
        }
        if (manualIperfServers.isEmpty()) {
            val host = prefs.getString("iperf_host", "speedtest.uninet.az") ?: "speedtest.uninet.az"
            val port = prefs.getInt("iperf_port", 5200).coerceIn(1, 65535)
            manualIperfServers.add(ManualIperfServer("My first server", host, port))
            persistManualIperfServers()
        }
        selectedManualIperfServer = prefs.getInt("iperf_manual_selected", 0).coerceIn(0, manualIperfServers.lastIndex)

        val container = etIperfPort.parent as? LinearLayout ?: return
        val index = container.indexOfChild(etIperfPort) + 1
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        spManualIperfServer = Spinner(this)
        panel.addView(spManualIperfServer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()
        ))
        manualIperfActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val add = Button(this).apply { text = "Add server" }
        val edit = Button(this).apply { text = "Edit" }
        val remove = Button(this).apply { text = "Delete" }
        listOf(add, edit, remove).forEach { button ->
            manualIperfActions.addView(button, LinearLayout.LayoutParams(0, (46 * resources.displayMetrics.density).toInt(), 1f))
        }
        panel.addView(manualIperfActions)
        container.addView(panel, index)

        add.setOnClickListener { showManualIperfDialog(null) }
        edit.setOnClickListener {
            manualIperfServers.getOrNull(selectedManualIperfServer)?.let { showManualIperfDialog(it) }
        }
        remove.setOnClickListener { removeManualIperfServer() }
        spManualIperfServer.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position !in manualIperfServers.indices) return
                selectedManualIperfServer = position
                val selected = manualIperfServers[position]
                etIperfHost.setText(selected.host)
                etIperfPort.setText(selected.port.toString())
                prefs.edit().putInt("iperf_manual_selected", position).apply()
            }
        }
        refreshManualIperfUi()
    }

    private fun persistManualIperfServers() {
        val arr = JSONArray()
        manualIperfServers.forEach { server ->
            arr.put(JSONObject().put("name", server.name).put("host", server.host).put("port", server.port))
        }
        getSharedPreferences("netq", MODE_PRIVATE).edit()
            .putString("iperf_manual_servers", arr.toString())
            .putInt("iperf_manual_selected", selectedManualIperfServer.coerceIn(0, maxOf(0, manualIperfServers.lastIndex)))
            .apply()
    }

    private fun refreshManualIperfUi() {
        if (!::spManualIperfServer.isInitialized) return
        val manual = !cbAutoIperfServer.isChecked
        spManualIperfServer.visibility = if (manual) View.VISIBLE else View.GONE
        manualIperfActions.visibility = if (manual) View.VISIBLE else View.GONE
        etIperfHost.visibility = if (manual) View.GONE else View.VISIBLE
        etIperfPort.visibility = if (manual) View.GONE else View.VISIBLE
        spManualIperfServer.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            manualIperfServers.map { it.name + " — " + it.host + ":" + it.port }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        if (manualIperfServers.isNotEmpty()) {
            selectedManualIperfServer = selectedManualIperfServer.coerceIn(0, manualIperfServers.lastIndex)
            spManualIperfServer.setSelection(selectedManualIperfServer, false)
            val selected = manualIperfServers[selectedManualIperfServer]
            etIperfHost.setText(selected.host)
            etIperfPort.setText(selected.port.toString())
        }
    }

    private fun showManualIperfDialog(existing: ManualIperfServer?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val name = EditText(this).apply { hint = "Server name"; setText(existing?.name ?: "") }
        val host = EditText(this).apply {
            hint = "Host / IP"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.host ?: "")
        }
        val port = EditText(this).apply {
            hint = "Port (default 5201)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(existing?.port?.toString() ?: "5201")
        }
        form.addView(name); form.addView(host); form.addView(port)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add iperf3 server" else "Edit iperf3 server")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                val h = host.text.toString().trim()
                val p = port.text.toString().trim().toIntOrNull()
                if (n.isBlank() || h.isBlank() || p == null || p !in 1..65535) {
                    Toast.makeText(this, "Enter a name, valid host and port (1–65535).", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (existing == null) {
                    manualIperfServers.add(ManualIperfServer(n, h, p))
                    selectedManualIperfServer = manualIperfServers.lastIndex
                } else {
                    val oldIndex = manualIperfServers.indexOf(existing)
                    if (oldIndex >= 0) {
                        manualIperfServers[oldIndex] = ManualIperfServer(n, h, p)
                        selectedManualIperfServer = oldIndex
                    }
                }
                persistManualIperfServers()
                refreshManualIperfUi()
                spManualIperfServer.setSelection(selectedManualIperfServer)
            }.show()
    }

    private fun removeManualIperfServer() {
        if (manualIperfServers.size <= 1) {
            Toast.makeText(this, "At least one manual server must remain.", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = manualIperfServers.getOrNull(selectedManualIperfServer) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete server")
            .setMessage("Delete " + selected.name + "?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                manualIperfServers.removeAt(selectedManualIperfServer)
                selectedManualIperfServer = selectedManualIperfServer.coerceAtMost(manualIperfServers.lastIndex)
                persistManualIperfServers()
                refreshManualIperfUi()
            }.show()
    }

    private fun manualIperfFailure(error: Exception, label: String): String {
        if (cbAutoIperfServer.isChecked) return label + ": " + (error.message ?: "Unknown error")
        val selected = manualIperfServers.getOrNull(selectedManualIperfServer)
        return label + " failed on selected manual server.\nServer: " +
            (selected?.host ?: etIperfHost.text.toString()) + ":" +
            (selected?.port?.toString() ?: etIperfPort.text.toString()) +
            "\nError: " + (error.message ?: "Unknown error") +
            "\nManual mode: no other server and no DNS fallback were attempted."
    }

    private suspend fun runIperfUdpWithFallback(bitrate: String, durationSeconds: Int): Net.IperfUdpResult {
        if (!cbAutoIperfServer.isChecked) {
            val (host, port) = iperfServer()
            return withContext(Dispatchers.IO) { Net.iperfUdpTest(this@MainActivity, host, port, bitrate, durationSeconds) }
        }

        val errors = ArrayList<String>()
        for (server in publicIperfServers) {
            val tcp = withContext(Dispatchers.IO) { Net.tcpOnce(server.host, server.port, 2200) }
            if (tcp == null) {
                errors.add("${server.location} (${server.host}:${server.port}): پورت TCP در دسترس نیست")
                continue
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    Net.iperfUdpTest(this@MainActivity, server.host, server.port, bitrate, durationSeconds)
                }
                etIperfHost.setText(server.host)
                etIperfPort.setText(server.port.toString())
                getSharedPreferences("netq", MODE_PRIVATE).edit()
                    .putString("iperf_host", server.host).putInt("iperf_port", server.port).apply()
                return result
            } catch (e: Exception) {
                errors.add("${server.location} (${server.host}:${server.port}): ${e.message ?: "خطای اتصال"}")
            }
        }
        throw IllegalStateException("هیچ‌یک از سرورهای عمومی در این اتصال آزمون iperf3 را کامل نکردند.\n" +
            errors.joinToString("\n") + "\nحالت خودکار را خاموش کنید و آدرس سرور اختصاصی خود را وارد کنید.")
    }
    private fun iperfServer(): Pair<String, Int> {
        if (!cbAutoIperfServer.isChecked) {
            val selected = manualIperfServers.getOrNull(selectedManualIperfServer)
                ?: throw IllegalStateException("Select a manual iperf3 server.")
            require(selected.host.isNotBlank() && selected.port in 1..65535) { "Invalid selected server settings." }
            return selected.host to selected.port
        }
        val host = etIperfHost.text.toString().trim()
        val port = etIperfPort.text.toString().trim().toIntOrNull()
        require(host.isNotBlank()) { "آدرس سرور iperf3 را وارد کنید." }
        require(port != null && port in 1..65535) { "پورت باید بین 1 تا 65535 باشد." }
        getSharedPreferences("netq", MODE_PRIVATE).edit().putString("iperf_host", host).putInt("iperf_port", port).apply()
        return host to port
    }

    private suspend fun runDnsUdpFallback(): Pair<String, Net.UdpLossResult> = withContext(Dispatchers.IO) {
        val servers = listOf(
            "5.200.200.200" to "DNS مخابرات",
            "2.189.44.44" to "DNS زیرساخت",
            "1.1.1.1" to "Cloudflare DNS",
            "8.8.8.8" to "Google DNS"
        )
        val results = servers.map { (host, label) ->
            val result = Net.udpLossTest(host, "example.com", 20, 1200)
            Triple(host, label, result)
        }
        val best = results.maxByOrNull { it.third.received }
            ?: throw IllegalStateException("هیچ DNS برای آزمون UDP انتخاب نشد.")
        if (best.third.received == 0) {
            throw IllegalStateException("iperf3 در دسترس نبود و هیچ‌یک از DNSها نیز از طریق UDP/53 پاسخ ندادند. احتمال محدودیت UDP/53 وجود دارد.")
        }
        best.first to best.third
    }

    private fun formatDnsUdpFallback(host: String, result: Net.UdpLossResult, purpose: String): String {
        return "روش جایگزین: UDP/53 DNS Request-Response\n" +
            "سرور پاسخ‌گو: $host:53\n" +
            "درخواست‌ها: ${result.sent}\nپاسخ‌های دریافت‌شده: ${result.received}\n" +
            "عدم دریافت پاسخ: ${"%.2f".format(Locale.US, result.loss)}٪\n" +
            "میانگین RTT پاسخ‌ها: ${"%.2f".format(Locale.US, result.avgRttMs)} ms\n" +
            "Jitter پاسخ‌ها: ${"%.2f".format(Locale.US, result.jitterMs)} ms\n\n" +
            "توجه: این روش دسترسی و کیفیت تبادل DNS روی UDP/53 را می‌سنجد و جایگزین اندازه‌گیری عمومی iperf3 یا Packet Loss تمام ترافیک UDP نیست. " +
            "برای آزمون $purpose یک سرور iperf3 اختصاصی لازم است."
    }

    private fun runStandaloneUdpLoss() {
        val button = findViewById<Button>(R.id.btnUdpLoss)
        button.isEnabled = false
        tvUdpLossResult.text = "آزمون واقعی iperf3 UDP با نرخ 1 Mbps در حال اجراست..."
        lifecycleScope.launch {
            try {
                val result = runIperfUdpWithFallback("1M", 10)
                tvUdpLossResult.text = "سرور: ${result.host}:${result.port}\nنرخ هدف: ${result.bitrate}\nبسته‌های گزارش‌شده: ${result.sentPackets}\nبسته‌های ازدست‌رفته: ${result.lostPackets}\nPacket Loss: ${"%.2f".format(Locale.US, result.lossPercent)}%\nJitter: ${"%.2f".format(Locale.US, result.jitterMs)} ms\nنرخ واقعی: ${"%.2f".format(Locale.US, result.throughputMbps)} Mbps"
            } catch (e: Exception) {
                tvUdpLossResult.text = if (cbAutoIperfServer.isChecked) {
                    try {
                        val (host, result) = runDnsUdpFallback()
                        "سرورهای عمومی iperf3 در دسترس نبودند.\n\n" + formatDnsUdpFallback(host, result, "Packet Loss")
                    } catch (fallback: Exception) {
                        "آزمون ناموفق بود.\niperf3: " + (e.message ?: "خطا") + "\nآزمون جایگزین: " + (fallback.message ?: "خطا")
                    }
                } else manualIperfFailure(e, "Packet Loss")
            } finally { button.isEnabled = true }
        }
    }

    private fun runUploadUdpLoss() {
        val button = findViewById<Button>(R.id.btnUploadLoss)
        button.isEnabled = false
        tvUploadLossResult.text = "آزمون UDP Upload با نرخ هدف 10 Mbps در حال اجراست..."
        lifecycleScope.launch {
            try {
                val result = runIperfUdpWithFallback("10M", 15)
                tvUploadLossResult.text = "آزمون UDP Upload به سرور ${result.host}:${result.port}\nنرخ هدف ارسال: ${result.bitrate}\nبسته‌های ارسالی/گزارش‌شده: ${result.sentPackets}\nبسته‌های ازدست‌رفته: ${result.lostPackets}\nPacket Loss در مسیر Upload: ${"%.2f".format(Locale.US, result.lossPercent)}%\nJitter: ${"%.2f".format(Locale.US, result.jitterMs)} ms\nنرخ دریافت‌شده در سرور: ${"%.2f".format(Locale.US, result.throughputMbps)} Mbps\n\nاین آزمون افت بسته‌های UDP ارسالی از گوشی به سرور را می‌سنجد؛ آزمون اشباع هم‌زمان TCP نیست."
            } catch (e: Exception) {
                tvUploadLossResult.text = if (cbAutoIperfServer.isChecked) {
                    try {
                        val (host, result) = runDnsUdpFallback()
                        "سرورهای عمومی iperf3 در دسترس نبودند.\n\n" +
                            formatDnsUdpFallback(host, result, "Upload") +
                            "\n\nاین نتیجه Packet Loss آپلود را نشان نمی‌دهد؛ برای سنجش یک‌طرفه آپلود باید سرور iperf3 اختصاصی در دسترس باشد."
                    } catch (fallback: Exception) {
                        "آزمون ناموفق بود.\niperf3: " + (e.message ?: "خطا") + "\nآزمون جایگزین: " + (fallback.message ?: "خطا")
                    }
                } else manualIperfFailure(e, "UDP Upload")
            } finally { button.isEnabled = true }
        }
    }

    private fun checkFilterAccess() {
        val presets = listOf("facebook.com", "instagram.com", "youtube.com", "t.me", "x.com", "whatsapp.com", "google.com")
        val custom = etFilterDomain.text.toString().trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
        val domain = (if (custom.isNotBlank()) custom else presets.getOrElse(spFilterSite.selectedItemPosition) { presets.first() }).trim().trimEnd('.').lowercase(Locale.ROOT)
        if (domain.isBlank() || !domain.contains(".") || domain.any { it.isWhitespace() }) {
            Toast.makeText(this, "یک دامنه معتبر وارد کنید", Toast.LENGTH_SHORT).show(); return
        }
        btnFilterCheck.isEnabled = false
        filterProgress.visibility = View.VISIBLE
        val steps = linkedMapOf("DNS سیستم" to "در انتظار", "DNS جایگزین 1.1.1.1" to "در انتظار", "DNS جایگزین 8.8.8.8" to "در انتظار", "DNS مخابرات 5.200.200.200" to "در انتظار", "DNS زیرساخت 2.189.44.44" to "در انتظار", "TCP/443 مقصد" to "در انتظار", "TCP/443 کنترلی" to "در انتظار", "TLS با SNI" to "در انتظار", "HTTPS" to "در انتظار", "CDN و اطلاعات IP" to "در انتظار")
        fun renderProgress() {
            tvFilterResult.text = buildString {
                append("بررسی مرحله‌ای دامنه: $domain\n")
                append("✓ یعنی آزمون همان لایه موفق شده است؛ موفقیت یک لایه سلامت همه لایه‌ها را ثابت نمی‌کند.\n\n")
                steps.forEach { (name, status) ->
                    val mark = when (status) { "موفق" -> "✓"; "ناموفق" -> "✕"; "در حال اجرا" -> "…"; else -> "○" }
                    append("$mark  $name — $status\n")
                }
            }
        }
        fun setStep(name: String, status: String) { steps[name] = status; renderProgress() }
        renderProgress()
        lifecycleScope.launch {
            try {
                setStep("DNS سیستم", "در حال اجرا")
                val dnsMs = withContext(Dispatchers.IO) { try { val t = System.nanoTime(); InetAddress.getAllByName(domain); (System.nanoTime() - t) / 1_000_000 } catch (_: Exception) { null } }
                setStep("DNS سیستم", if (dnsMs != null) "موفق" else "ناموفق")
                setStep("DNS جایگزین 1.1.1.1", "در حال اجرا")
                val dnsCf = withContext(Dispatchers.IO) { Net.dnsQuery("1.1.1.1", domain, 3000) }
                setStep("DNS جایگزین 1.1.1.1", if (dnsCf != null && dnsCf >= 0) "موفق" else "ناموفق")
                setStep("DNS جایگزین 8.8.8.8", "در حال اجرا")
                val dnsGoogle = withContext(Dispatchers.IO) { Net.dnsQuery("8.8.8.8", domain, 3000) }
                setStep("DNS جایگزین 8.8.8.8", if (dnsGoogle != null && dnsGoogle >= 0) "موفق" else "ناموفق")
                setStep("DNS مخابرات 5.200.200.200", "در حال اجرا")
                val dnsMokhaberat = withContext(Dispatchers.IO) { Net.dnsQuery("5.200.200.200", domain, 3500) }
                setStep("DNS مخابرات 5.200.200.200", if (dnsMokhaberat != null && dnsMokhaberat >= 0) "موفق" else "ناموفق")
                setStep("DNS زیرساخت 2.189.44.44", "در حال اجرا")
                val dnsInfrastructure = withContext(Dispatchers.IO) { Net.dnsQuery("2.189.44.44", domain, 3500) }
                setStep("DNS زیرساخت 2.189.44.44", if (dnsInfrastructure != null && dnsInfrastructure >= 0) "موفق" else "ناموفق")
                setStep("TCP/443 مقصد", "در حال اجرا")
                val tcp = withContext(Dispatchers.IO) { Net.tcpOnce(domain, 443, 5000) }
                setStep("TCP/443 مقصد", if (tcp != null) "موفق" else "ناموفق")
                setStep("TCP/443 کنترلی", "در حال اجرا")
                val control = withContext(Dispatchers.IO) { Net.tcpOnce("1.1.1.1", 443, 5000) }
                setStep("TCP/443 کنترلی", if (control != null) "موفق" else "ناموفق")
                setStep("TLS با SNI", "در حال اجرا")
                val tls = withContext(Dispatchers.IO) { Net.tlsOnce(domain, 6000) }
                setStep("TLS با SNI", if (tls != null) "موفق" else "ناموفق")
                setStep("HTTPS", "در حال اجرا")
                val https = withContext(Dispatchers.IO) { Net.httpCheck("https://$domain", false) }
                setStep("HTTPS", if (https.code != null) "موفق" else "ناموفق")
                setStep("CDN و اطلاعات IP", "در حال اجرا")
                val infra = withContext(Dispatchers.IO) { Net.inspectSiteInfrastructure(domain) }
                setStep("CDN و اطلاعات IP", if (infra.ips.isNotEmpty() || infra.cname != null || infra.evidence.isNotEmpty()) "موفق" else "نامشخص")
                val verdict = when {
                    https.code != null && https.code in 200..399 -> "سایت از مسیر فعلی قابل دسترس است"
                    listOf(dnsMs, dnsCf, dnsGoogle, dnsMokhaberat, dnsInfrastructure).all { it == null || it < 0 } && control != null -> "احتمال اختلال DNS؛ هیچ‌یک از DNSهای آزمایش‌شده پاسخ معتبر ندادند"
                    tcp == null && control != null -> "احتمال محدودیت یا اختلال اختصاصی در مسیر مقصد؛ اتصال کنترلی برقرار است"
                    tcp != null && tls == null -> "TCP برقرار است اما TLS/SNI ناموفق است؛ احتمال اختلال TLS، گواهی یا بازرسی مسیر"
                    https.code == 403 || https.code == 451 -> "سرور پاسخ HTTP محدودکننده داده؛ به‌تنهایی اثبات فیلترینگ شبکه نیست"
                    else -> "نتیجه نامشخص؛ شواهد کافی برای اعلام فیلترینگ قطعی وجود ندارد"
                }
                val report = buildString {
                    append("گزارش مرحله‌ای دسترسی\nدامنه: $domain\nنتیجه: $verdict\n\nوضعیت آزمون‌ها:\n")
                    steps.forEach { (name, status) -> append((if (status == "موفق") "✓" else if (status == "ناموفق") "✕" else "—") + "  $name: $status\n") }
                    append("\nDNS سیستم: ").append(dnsMs?.let { "موفق ($it ms)" } ?: "ناموفق").append("\n")
                    append("DNS 1.1.1.1: ").append(dnsCf?.let { if (it >= 0) "موفق ($it ms)" else "پاسخ نامعتبر" } ?: "بدون پاسخ").append("\n")
                    append("DNS 8.8.8.8: ").append(dnsGoogle?.let { if (it >= 0) "موفق ($it ms)" else "پاسخ نامعتبر" } ?: "بدون پاسخ").append("\n")
                    append("DNS مخابرات 5.200.200.200: ").append(dnsMokhaberat?.let { if (it >= 0) "موفق ($it ms)" else "پاسخ نامعتبر" } ?: "بدون پاسخ").append("\n")
                    append("DNS زیرساخت 2.189.44.44: ").append(dnsInfrastructure?.let { if (it >= 0) "موفق ($it ms)" else "پاسخ نامعتبر" } ?: "بدون پاسخ").append("\n")
                    append("TCP/443 مقصد: ").append(tcp?.let { "موفق (${f0(it)} ms)" } ?: "ناموفق").append("\n")
                    append("TCP کنترلی 1.1.1.1: ").append(control?.let { "موفق (${f0(it)} ms)" } ?: "ناموفق").append("\n")
                    append("TLS/SNI: ").append(tls?.let { "موفق ($it ms)" } ?: "ناموفق").append("\n")
                    append("HTTPS: ").append(https.code?.toString() ?: "بدون پاسخ (${https.err ?: "خطا"})").append(" | زمان: ${https.ms} ms\n")
                    append("\n── اطلاعات CDN و IP ──\nCDN: ${infra.cdn}\n")
                    append("IPهای قابل مشاهده: ").append(infra.ips.joinToString(", ").ifBlank { "دریافت نشد" }).append("\n")
                    infra.cname?.let { append("CNAME: $it\n") }
                    infra.asn?.let { append("ASN: $it\n") }
                    infra.org?.let { append("مالک شبکه: $it\n") }
                    infra.country?.let { append("کشور تقریبی IP لبه: $it\n") }
                    infra.city?.let { append("شهر تقریبی IP لبه: $it\n") }
                    infra.serverHeader?.let { append("Server: $it\n") }
                    infra.cfRay?.let { append("CF-Ray: $it\n") }
                    infra.colo?.let { append("Cloudflare PoP: $it\n") }
                    if (infra.evidence.isNotEmpty()) { append("شواهد CDN:\n"); infra.evidence.forEach { append("• $it\n") } }
                    append("\n${infra.geoNote ?: ""}\n")
                    append("\nموفقیت DNS فقط پاسخ‌گویی DNS را نشان می‌دهد و به‌تنهایی نبود فیلترینگ در TCP، TLS یا HTTP را ثابت نمی‌کند.")
                    append("\nDNSهای جایگزین از همین اتصال آزمایش شده‌اند و ممکن است دسترسی مستقیم به آن‌ها مسدود باشد.")
                    append("\nاپراتور ثبت‌شده: ").append(spFilterOperator.selectedItem.toString())
                }
                tvFilterResult.text = report
                val prefs = getSharedPreferences("netq", MODE_PRIVATE)
                val history = try { JSONArray(prefs.getString("filter_history", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                val opName = spFilterOperator.selectedItem.toString()
                val testName = etFilterTestName.text.toString().trim().ifBlank { "$opName - $domain - $stamp" }
                val item = JSONObject().apply {
                    put("name", testName); put("time", stamp); put("operator", opName)
                    put("network", opName); put("domain", domain); put("report", report)
                }
                val next = JSONArray().put(item)
                for (i in 0 until minOf(history.length(), 19)) next.put(history.getJSONObject(i))
                prefs.edit().putString("filter_history", next.toString())
                    .putString("filter_result_$opName", report).apply()
            } catch (e: Exception) {
                tvFilterResult.text = "خطا در اجرای آزمون: ${e.message ?: e.javaClass.simpleName}"
            } finally { filterProgress.visibility = View.GONE; btnFilterCheck.isEnabled = true }
        }
    }

    private fun refreshDnsServers() {
        val prefs = getSharedPreferences("netq", MODE_PRIVATE)
        val servers = (prefs.getString("custom_dns_servers", "8.8.8.8\n1.1.1.1") ?: "")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val list = if (servers.isEmpty()) listOf("8.8.8.8") else servers
        spDnsServer.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list)
    }

    private fun addDnsServer() {
        val ip = etDnsServer.text.toString().trim()
        if (ip.isBlank()) { Toast.makeText(this, "IP سرور را وارد کنید", Toast.LENGTH_SHORT).show(); return }
        try { InetAddress.getByName(ip) } catch (_: Exception) {
            Toast.makeText(this, "آدرس IP معتبر نیست", Toast.LENGTH_SHORT).show(); return
        }
        val prefs = getSharedPreferences("netq", MODE_PRIVATE)
        val current = (prefs.getString("custom_dns_servers", "8.8.8.8\n1.1.1.1") ?: "").lines()
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (ip !in current) prefs.edit().putString("custom_dns_servers", (current + ip).joinToString("\n")).apply()
        refreshDnsServers()
        val items = (0 until spDnsServer.count).map { spDnsServer.getItemAtPosition(it).toString() }
        spDnsServer.setSelection(items.indexOf(ip).coerceAtLeast(0))
        etDnsServer.text.clear()
        Toast.makeText(this, "DNS Server اضافه شد", Toast.LENGTH_SHORT).show()
    }


    private fun manageDnsServer() {
        val selected = spDnsServer.selectedItem?.toString() ?: return
        AlertDialog.Builder(this)
            .setTitle("مدیریت $selected")
            .setItems(arrayOf("ویرایش آدرس", "حذف سرور")) { _, which ->
                val prefs = getSharedPreferences("netq", MODE_PRIVATE)
                val current = (prefs.getString("custom_dns_servers", "8.8.8.8\n1.1.1.1") ?: "")
                    .lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (which == 0) {
                    val input = EditText(this).apply {
                        setText(selected)
                        setSelection(text.length)
                        hint = "آدرس IP جدید"
                        setSingleLine(true)
                        textDirection = View.TEXT_DIRECTION_LTR
                    }
                    AlertDialog.Builder(this).setTitle("ویرایش DNS Server").setView(input)
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("ذخیره") { _, _ ->
                            val updated = input.text.toString().trim()
                            if (!isValidDnsAddress(updated)) {
                                Toast.makeText(this, "آدرس IP معتبر نیست", Toast.LENGTH_SHORT).show()
                            } else {
                                val revised = current.map { if (it == selected) updated else it }.distinct()
                                prefs.edit().putString("custom_dns_servers", revised.joinToString("\n")).apply()
                                refreshDnsServers()
                                val index = (0 until spDnsServer.count).indexOfFirst { spDnsServer.getItemAtPosition(it).toString() == updated }
                                if (index >= 0) spDnsServer.setSelection(index)
                            }
                        }.show()
                } else {
                    AlertDialog.Builder(this).setTitle("حذف سرور DNS")
                        .setMessage("سرور $selected از فهرست حذف شود؟")
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("حذف") { _, _ ->
                            prefs.edit().putString("custom_dns_servers", current.filterNot { it == selected }.joinToString("\n")).apply()
                            refreshDnsServers()
                        }.show()
                }
            }.show()
    }

    private fun isValidDnsAddress(value: String): Boolean {
        if (value.isBlank() || value.any { it.isWhitespace() }) return false
        return try {
            val address = InetAddress.getByName(value)
            address.hostAddress != null && (value.contains(':') ||
                (value.matches(Regex("[0-9]{1,3}(\\\\.[0-9]{1,3}){3}")) && value.split('.').all { it.toIntOrNull() in 0..255 }))
        } catch (_: Exception) { false }
    }

    private fun runDnsLookup() {
        if (dnsJob?.isActive == true) { dnsJob?.cancel(); return }
        val domain = etDnsLookup.text.toString().trim().trimEnd('.')
        if (domain.isBlank() || !domain.contains(".")) {
            Toast.makeText(this, "دامنه معتبر وارد کنید", Toast.LENGTH_SHORT).show(); return
        }
        val server = spDnsServer.selectedItem?.toString() ?: return
        btnDnsLookup.text = "توقف پایش زنده"
        dnsChartsContainer.removeAllViews()

        val serverHistory = ArrayList<Double>()
        val aHistory = ArrayList<Double>()
        val serverChart = DnsRecordGraphView(this).apply { recordName = "Ping سرور DNS ($server)" }
        val aChart = DnsRecordGraphView(this).apply { recordName = "زمان پاسخ رکورد A برای $domain" }
        val chartParams = LinearLayout.LayoutParams(-1, (155 * resources.displayMetrics.density).toInt()).apply {
            bottomMargin = (10 * resources.displayMetrics.density).toInt()
        }
        dnsChartsContainer.addView(serverChart, chartParams)
        dnsChartsContainer.addView(aChart, chartParams)

        tvDnsLookupResult.text = "در حال دریافت رکوردهای دامنه $domain از DNS $server ..."
        dnsJob = lifecycleScope.launch {
            try {
                val recordTypes = DnsLookup.supportedTypes().filter { it != "ANY" }
                val initialRecords = coroutineScope {
                    recordTypes.map { type -> async(Dispatchers.IO) { type to DnsLookup.query(server, domain, type) } }.map { it.await() }
                }
                val recordsText = buildString {
                    append("رکوردهای دامنه: $domain\nDNS Server: $server:53\n\n")
                    initialRecords.forEach { (type, result) ->
                        append("━━ رکورد $type ━━\n")
                        if (result.answers.isEmpty()) {
                            append(if (result.error != null) "خطا: ${result.error}" else "رکوردی برنگشت (RCODE=${result.status})")
                        } else result.answers.forEach { append("• ${it.value}    TTL: ${it.ttl}\n") }
                        append("\n")
                    }
                    append("━━━━━━━━━━━━━━━━━━━━\nنمودارهای پایین به‌صورت زنده به‌روزرسانی می‌شوند.")
                }
                tvDnsLookupResult.text = recordsText
                while (true) {
                    val (serverPing, aResult) = coroutineScope {
                        val pingTask = async(Dispatchers.IO) { ping(server, 53, 1) }
                        val aTask = async(Dispatchers.IO) {
                            val began = System.nanoTime()
                            val result = DnsLookup.query(server, domain, "A")
                            val elapsed = (System.nanoTime() - began) / 1_000_000.0
                            Pair(result, elapsed)
                        }
                        Pair(pingTask.await(), aTask.await())
                    }
                    val pingValue = if (serverPing.received > 0) serverPing.avg else -1.0
                    val aValue = if (aResult.first.error == null && aResult.first.status == 0) aResult.second else -1.0
                    serverHistory.add(pingValue); aHistory.add(aValue)
                    if (serverHistory.size > 120) serverHistory.removeAt(0)
                    if (aHistory.size > 120) aHistory.removeAt(0)
                    serverChart.latencySamples = serverHistory.toList()
                    aChart.latencySamples = aHistory.toList()
                    delay(1000)
                }
            } catch (_: CancellationException) {
                tvDnsLookupResult.append("\n\nپایش زنده متوقف شد.")
            } catch (e: Exception) {
                tvDnsLookupResult.append("\n\nخطا در پایش: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                btnDnsLookup.text = "شروع پایش DNS"
            }
        }
    }

    private fun compareFilterTests() {
        val prefs = getSharedPreferences("netq", MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("filter_history", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        if (arr.length() < 2) {
            AlertDialog.Builder(this).setTitle("مقایسه تست‌ها")
                .setMessage("برای مقایسه ابتدا حداقل دو تست فیلترینگ اجرا کنید.")
                .setPositiveButton("متوجه شدم", null).show(); return
        }
        val labels = Array(arr.length()) { i ->
            val item = arr.getJSONObject(i)
            "${item.optString("name").ifBlank { item.optString("operator") }} | ${item.optString("domain")} | ${item.optString("time")}"
        }
        val selected = BooleanArray(arr.length())
        val dialog = AlertDialog.Builder(this).setTitle("انتخاب تست‌ها برای مقایسه")
            .setMultiChoiceItems(labels, selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton("مقایسه", null)
            .setNeutralButton("تغییر نام") { _, _ ->
                val chosen = selected.indices.filter { selected[it] }
                if (chosen.size == 1) renameFilterTest(arr, chosen[0])
                else Toast.makeText(this, "برای تغییر نام فقط یک تست را انتخاب کنید.", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("بستن", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chosen = selected.indices.filter { selected[it] }
                if (chosen.size < 2) Toast.makeText(this, "حداقل دو تست انتخاب کنید.", Toast.LENGTH_SHORT).show()
                else { dialog.dismiss(); showSelectedTestsComparison(arr, chosen) }
            }
        }
        dialog.show()
    }

    private fun renameFilterTest(arr: JSONArray, index: Int) {
        val item = arr.optJSONObject(index) ?: return
        val field = EditText(this).apply { setSingleLine(true); setText(item.optString("name")); setSelection(text.length) }
        AlertDialog.Builder(this).setTitle("تغییر نام تست").setView(field)
            .setPositiveButton("ذخیره") { _, _ ->
                val value = field.text.toString().trim()
                if (value.isNotBlank()) {
                    item.put("name", value)
                    getSharedPreferences("netq", MODE_PRIVATE).edit().putString("filter_history", arr.toString()).apply()
                    Toast.makeText(this, "نام تست ذخیره شد.", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("انصراف", null).show()
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
        cbSpeedTest.isChecked = getSharedPreferences("netq", MODE_PRIVATE).getBoolean("speed_test_enabled", false)
        cbUploadTest.isChecked = getSharedPreferences("netq", MODE_PRIVATE).getBoolean("upload_test_enabled", false)
    }

    private fun saveSettings() {
        for ((k, id) in cfgFields) Config.put(this, k, findViewById<EditText>(id).text.toString())
        getSharedPreferences("netq", MODE_PRIVATE).edit().putBoolean("speed_test_enabled", cbSpeedTest.isChecked).putBoolean("upload_test_enabled", cbUploadTest.isChecked).apply()
        Toast.makeText(this, "تنظیمات ذخیره شد", Toast.LENGTH_SHORT).show()
    }

    private fun resetSettings() {
        for ((k, _) in cfgFields) Config.reset(this, k)
        getSharedPreferences("netq", MODE_PRIVATE).edit().putBoolean("speed_test_enabled", false).putBoolean("upload_test_enabled", false).apply()
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
        val uploadEnabled = cbUploadTest.isChecked

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

                // ۴) Packet Loss در پروب‌های UDP/DNS
                val udpServer = dnsServers.firstOrNull() ?: "1.1.1.1"
                tvStatus.text = "⏳ در حال بررسی Packet Loss در UDP…"
                val udpBaseline = withContext(Dispatchers.IO) {
                    Net.udpLossTest(udpServer, "google.com", 8)
                }
                add("📦 Packet Loss در UDP (پروب DNS/53)\nسرور: $udpServer | ارسال: ${udpBaseline.sent} | پاسخ: ${udpBaseline.received} | افت پاسخ: ${f1(udpBaseline.loss)}٪")
                if (udpBaseline.loss >= 25.0) {
                    issue(1, "در پروب‌های UDP/DNS افت پاسخ ${f1(udpBaseline.loss)}٪ ثبت شد؛ نتیجه به سرور و مسیر DNS/53 وابسته است.")
                }

                // ۵) دسترسی سایت‌ها + Captive Portal
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

                // ۶) سرعت دانلود
                val reachable = inetOk || siteRes.any { it.ok }
                val speedEnabled = getSharedPreferences("netq", MODE_PRIVATE).getBoolean("speed_test_enabled", false)
                if (speedEnabled && reachable && !captivePortal && speedUrls.isNotEmpty()) {
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
                } else if (!speedEnabled) {
                    speedText = "غیرفعال (طبق تنظیمات)"
                    add("⬇️ تست سرعت: اجرا نشد؛ این گزینه در تنظیمات غیرفعال است.")
                }

                // ۷) تست آپلود اختیاری و Packet Loss UDP حین آپلود
                if (uploadEnabled && reachable && !captivePortal) {
                    tvStatus.text = "⏳ در حال تست آپلود و پایش UDP…"
                    val uploadJob = async(Dispatchers.IO) { Net.uploadTest() }
                    val lossJob = async(Dispatchers.IO) { Net.udpLossTest(udpServer, "google.com", 12) }
                    val uploadMbps = uploadJob.await()
                    val uploadUdp = lossJob.await()
                    if (uploadMbps != null) {
                        add("⬆️ سرعت آپلود: ${f1(uploadMbps)} Mbps")
                    } else {
                        add("⬆️ سرعت آپلود: ناموفق؛ سرور تست یا مسیر آپلود پاسخ نداد.")
                        issue(1, "تست آپلود ناموفق بود.")
                    }
                    add("📦 Packet Loss UDP حین آپلود (پروب DNS/53)\nارسال: ${uploadUdp.sent} | پاسخ: ${uploadUdp.received} | افت پاسخ: ${f1(uploadUdp.loss)}٪\nاین شاخص افت پاسخ پروب UDP هنگام اشغال مسیر آپلود را نشان می‌دهد و Packet Loss واقعی TCP آپلود نیست.")
                    if (uploadUdp.loss >= 25.0) {
                        issue(1, "هنگام آپلود، افت پاسخ پروب‌های UDP/DNS برابر ${f1(uploadUdp.loss)}٪ بود.")
                    }
                } else if (uploadEnabled) {
                    add("⬆️ تست آپلود اجرا نشد؛ اتصال اینترنت قابل استفاده نیست یا صفحه ورود شبکه فعال است.")
                } else {
                    add("⬆️ تست آپلود: اجرا نشد؛ گزینه اختیاری آپلود فعال نشده است.")
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
                saveHistory("$time | $net | ping $pingText | loss $lossText | speed $speedText | ${verdict.take(2)}")
                saveFullSnapshot(time, "—", net, pingText, lossText, speedText, verdict, log.toString())
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
            put("name", operator + " - " + network + " - " + time)
        }
        val next = JSONArray().put(item)
        for (i in 0 until minOf(old.length(), 9)) next.put(old.getJSONObject(i))
        prefs.edit().putString("full_history", next.toString()).apply()
    }

    private fun compareLastTwoTests() {
        val prefs = getSharedPreferences("netq", MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("full_history", "[]") ?: "[]") }
            catch (_: Exception) { JSONArray() }
        if (arr.length() < 2) {
            AlertDialog.Builder(this).setTitle("مقایسه تست‌ها")
                .setMessage("برای مقایسه، ابتدا حداقل دو تست کامل اجرا کنید.")
                .setPositiveButton("متوجه شدم", null).show()
            return
        }
        fun title(item: JSONObject): String = item.optString("name").ifBlank {
            listOf(item.optString("operator"), item.optString("network"), item.optString("time"))
                .filter { it.isNotBlank() }.joinToString(" - ")
        }
        val labels = Array(arr.length()) { i -> title(arr.getJSONObject(i)) + " | " + arr.getJSONObject(i).optString("time") }
        val checked = BooleanArray(arr.length())
        val dialog = AlertDialog.Builder(this).setTitle("انتخاب تست‌ها برای مقایسه")
            .setMultiChoiceItems(labels, checked) { _, which, value -> checked[which] = value }
            .setPositiveButton("مقایسه", null)
            .setNeutralButton("نام‌گذاری تست") { _, _ ->
                val chosen = checked.indices.filter { checked[it] }
                if (chosen.size == 1) renameSavedTest(arr, chosen[0])
                else Toast.makeText(this, "برای نام‌گذاری فقط یک تست را انتخاب کنید.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("بستن", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chosen = checked.indices.filter { checked[it] }
                if (chosen.size < 2) Toast.makeText(this, "حداقل دو تست را انتخاب کنید.", Toast.LENGTH_SHORT).show()
                else { dialog.dismiss(); showSelectedTestsComparison(arr, chosen) }
            }
        }
        dialog.show()
    }

    private fun renameSavedTest(arr: JSONArray, index: Int) {
        val item = arr.optJSONObject(index) ?: return
        val field = EditText(this).apply {
            setSingleLine(true)
            hint = "مثلاً BRAS Feiz"
            setText(item.optString("name"))
            setSelection(text.length)
        }
        AlertDialog.Builder(this).setTitle("نام‌گذاری تست")
            .setMessage("نام دلخواه تست را وارد کنید.")
            .setView(field)
            .setPositiveButton("ذخیره") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotBlank()) {
                    item.put("name", name)
                    getSharedPreferences("netq", MODE_PRIVATE).edit().putString("full_history", arr.toString()).apply()
                    Toast.makeText(this, "نام تست ذخیره شد.", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("انصراف", null).show()
    }

    private fun showSelectedTestsComparison(arr: JSONArray, indices: List<Int>) {
        data class Row(val section: String, val label: String, val values: List<String>)
        fun extract(item: JSONObject): LinkedHashMap<String, Pair<String, String>> {
            val map = LinkedHashMap<String, Pair<String, String>>()
            var section = "اطلاعات عمومی"
            item.optString("report").lines().forEach { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.startsWith("━━")) return@forEach
                if (line.startsWith("◆")) { section = line.removePrefix("◆").trim(); return@forEach }
                if (line.contains("jitter", true) && line.contains("loss", true) && line.contains("|")) {
                    val parts = line.split("|").map { it.trim() }
                    val first = parts.firstOrNull().orEmpty()
                    val target = first.substringBefore(":").trim().ifBlank { "مقصد نامشخص" }
                    val pingValue = first.substringAfter(":", "").trim()
                    val method = Regex("\\((ICMP|TCP)\\)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: "روش نامشخص"
                    val dest = target + " (" + method + ")"
                    if (pingValue.isNotBlank()) map[section + "|Ping به " + dest] = section to ("Ping: " + pingValue)
                    parts.drop(1).forEach { part ->
                        if (part.contains("jitter", true)) map[section + "|Jitter به " + dest] = section to part
                        if (part.contains("loss", true)) map[section + "|Packet Loss به " + dest] = section to part
                    }
                    return@forEach
                }
                line.split("|").map { it.trim() }.filter { it.isNotBlank() }.forEachIndexed { n, part ->
                    val label = when {
                        part.contains("→") -> part.substringBefore("→").trim() + " (DNS)"
                        part.contains(":") -> part.substringBefore(":").trim()
                        else -> part.take(48)
                    }.ifBlank { "جزئیات " + (n + 1) }
                    // خلاصه وضعیت DNS در ابتدای گزارش، دوباره در بخش جزئیات DNS آمده است؛
                    // فقط ردیف‌های جزئیات را نگه می‌داریم تا در مقایسه تکراری دیده نشوند.
                    if (Regex("^[✓✕—]\\s*DNS", RegexOption.IGNORE_CASE).containsMatchIn(line)) return@forEach
                    val key = section + "|" + label
                    // اگر یک شاخص دوباره آمده باشد، مقدار نهایی جایگزین شود؛ ردیف تکراری نساز.
                    map[key] = section to part
                }
            }
            return map
        }
        val tests = indices.map { arr.getJSONObject(it) }
        val maps = tests.map { extract(it) }
        val keys = LinkedHashSet<String>()
        maps.forEach { keys.addAll(it.keys) }
        val names = tests.map { it.optString("name").ifBlank { it.optString("operator") + " - " + it.optString("time") } }
        val rows = keys.map { key ->
            val first = maps.firstNotNullOfOrNull { it[key] }
            Row(first?.first ?: "سایر موارد", key.substringAfterLast("|"), maps.map { it[key]?.second ?: "در این تست ثبت نشده" })
        }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun rounded(fill: Int, stroke: Int = 0x00000000, radius: Int = 16): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(fill); cornerRadius = dp(radius).toFloat()
                if (stroke != 0x00000000) setStroke(dp(1), stroke)
            }
        val palette = listOf(0xFF087E8B.toInt(), 0xFF5367C5.toInt(), 0xFF16845B.toInt(), 0xFFB56B25.toInt())
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(0xFF123B50.toInt(), radius = 18)
        }
        hero.addView(TextView(this).apply {
            text = "مقایسه تست‌های فیلترینگ"
            textSize = 17f; setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        hero.addView(TextView(this).apply {
            text = tests.size.toString() + " تست انتخاب‌شده  •  " + rows.size + " شاخص قابل مقایسه"
            textSize = 12f; setTextColor(0xFFD4E8EF.toInt())
            setPadding(0, dp(5), 0, 0)
        })
        body.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val testsHeader = TextView(this).apply {
            text = "تست‌های انتخاب‌شده"
            textSize = 13f; setTextColor(0xFF526579.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), 0, dp(2), dp(7))
        }
        body.addView(testsHeader)
        tests.forEachIndexed { i, item ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(0xFFFFFFFF.toInt(), 0xFFE1E8F0.toInt(), 12)
            }
            val dot = TextView(this).apply {
                text = "●"; textSize = 11f; setTextColor(palette[i % palette.size])
                setPadding(0, 0, dp(7), 0)
            }
            chip.addView(dot)
            chip.addView(TextView(this).apply {
                text = names[i]; textSize = 12f; setTextColor(0xFF243B53.toInt())
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            chip.addView(TextView(this).apply {
                text = "#" + (i + 1); textSize = 10f; setTextColor(0xFF718096.toInt())
            })
            body.addView(chip, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        body.addView(TextView(this).apply {
            text = "مقادیر هر شاخص به تفکیک تست نمایش داده شده‌اند. مقصدها و روش‌های متفاوت Ping در ردیف‌های جداگانه قرار می‌گیرند."
            textSize = 11f; setTextColor(0xFF718096.toInt())
            setPadding(dp(2), dp(5), dp(2), dp(12))
        })

        rows.groupBy { it.section }.forEach { (section, group) ->
            val sectionBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(9), dp(11), dp(9))
                background = rounded(0xFFE4F1F4.toInt(), radius = 10)
            }
            sectionBar.addView(TextView(this).apply {
                text = section; textSize = 13f; setTextColor(0xFF087E8B.toInt())
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            sectionBar.addView(TextView(this).apply {
                text = group.size.toString() + " شاخص"; textSize = 10f
                setTextColor(0xFF527783.toInt())
            })
            body.addView(sectionBar, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(9); bottomMargin = dp(8)
            })
            group.forEach { row ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(13), dp(12), dp(13), dp(12))
                    background = rounded(0xFFFFFFFF.toInt(), 0xFFE5EAF0.toInt(), 14)
                    elevation = dp(1).toFloat()
                }
                card.addView(TextView(this).apply {
                    text = row.label; textSize = 13f; setTextColor(0xFF17324D.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                })
                names.forEachIndexed { i, name ->
                    val missing = row.values[i] == "در این تست ثبت نشده"
                    val valueRow = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(10), dp(7), dp(10), dp(7))
                        background = rounded(if (missing) 0xFFFFF7E9.toInt() else 0xFFF3F7FA.toInt(), radius = 9)
                    }
                    valueRow.addView(TextView(this).apply {
                        text = name; textSize = 10f
                        setTextColor(palette[i % palette.size])
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    valueRow.addView(TextView(this).apply {
                        text = row.values[i]; textSize = 12f
                        setTextColor(if (missing) 0xFF9A5B20.toInt() else 0xFF34495E.toInt())
                        setPadding(0, dp(2), 0, 0)
                    })
                    card.addView(valueRow, LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(7)
                    })
                }
                body.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(8)
                })
            }
        }
        val report = buildString {
            append("مقایسه کامل " + tests.size + " تست\\n")
            names.forEachIndexed { i, name -> append((i + 1).toString() + ". " + name + "\\n") }
            append("\\n")
            rows.groupBy { it.section }.forEach { (section, group) ->
                append("【" + section + "】\\n")
                group.forEach { row ->
                    append(row.label + "\\n")
                    names.forEachIndexed { i, name -> append("  " + name + ": " + row.values[i] + "\\n") }
                }
                append("\\n")
            }
        }
        val scroll = ScrollView(this).apply {
            addView(body)
            isFillViewport = true
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        AlertDialog.Builder(this).setTitle("مقایسه " + tests.size + " تست")
            .setView(scroll)
            .setPositiveButton("اشتراک‌گذاری گزارش کامل") { _, _ -> share(report) }
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
        rows.add(String.format(Locale.US, "%-3s %-16s %7s %7s %7s", "Hop", "IP / مقصد", "Loss %", "Avg ms", "Last ms"))
        rows.add("────────────────────────────────────────────────────")
        stats.forEachIndexed { i, s ->
            val ip = (s.ip ?: "—").take(16)
            val last = if (s.recv == 0) "—" else f0(s.last)
            val avg = if (s.recv == 0) "—" else f0(s.avg)
            rows.add(String.format(Locale.US, "%-3d %-16s %7s %7s %7s",
                i + 1, ip, f1(s.loss), avg, last))
        }
        val table = rows.joinToString("\n")
        val sent = stats.lastOrNull()?.sent ?: 0
        tvTable.typeface = Typeface.MONOSPACE
        tvTable.textSize = 9f
        tvTable.setLineSpacing(0f, 0.92f)
        tvTable.text = table
        tvPlotInfo.text = "$header | نمونه: $sent | Loss هر ردیف در ستون Loss %"
        val selected = stats.last()
        val latestPing = selected.hist.lastOrNull()?.takeIf { it >= 0 }
        val validJitter = selected.jitterHist.filter { it >= 0 }
        val avgJitter = if (validJitter.isEmpty()) 0.0 else validJitter.average()
        val latestJitter = selected.jitterHist.lastOrNull()?.takeIf { it >= 0 }
        val lastPing = (latestPing?.let { "آخرین ${f1(it)} ms" } ?: "آخرین بدون پاسخ") +
            "  |  میانگین ${f1(selected.avg)} ms  |  بیشینه ${f1(selected.max)} ms"
        val lastJitter = (latestJitter?.let { "آخرین ${f1(it)} ms" } ?: "آخرین —") +
            "  |  میانگین ${f1(avgJitter)} ms  |  نمونه ${validJitter.size}"
        val lastLoss = "از دست‌رفته ${f1(selected.loss)}%  |  دریافت ${selected.recv} از ${selected.sent}"
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
