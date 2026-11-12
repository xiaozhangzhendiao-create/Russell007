package com.livesubtitles.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 前台服务：
 * 1) MediaProjection 内录其他 App 的媒体音频 → 16kHz 单声道
 * 2) 隐形 WebView 只做"计算引擎"（Vosk 识别 + OPUS-MT 翻译），
 *    结果经 JS 桥推送到【原生 TextView 字幕】——不依赖 WebView 渲染，保证可见
 * 3) 控制条窗：拖动 / 字号 / 透明度 / 中英模式 / 关闭（全部原生实现），3.5s 无操作自动淡出
 * 4) /models/ 代理：本地缓存优先，未命中从 hf-mirror 下载
 */
class SubtitleService : Service() {

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var webView: WebView? = null
    private var mainHandler: Handler? = null
    private var subtitleRoot: View? = null
    private var controlRoot: View? = null
    private var subParams: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var subText: TextView? = null
    private var subPill: GradientDrawable? = null
    private var logFile: File? = null
    private var lastZh = ""
    private var lastOrig = ""
    @Volatile private var running = false
    @Volatile private var chunkCount = 0L
    @Volatile private var gotAnyPush = false
    @Volatile private var engineReady = false
    @Volatile private var translationOnly = true
    @Volatile private var bilingual = false
    private var fontSize = 20   // sp
    private var bgOpacity = 40  // 百分比
    private val fadeHandler = Handler(mainLooper)
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun log(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        try { logFile?.appendText(line + "\n") } catch (_: Exception) {}
        android.util.Log.i("LiveSub", line)
    }

    /* ---------- 原生字幕渲染 ---------- */

    private fun renderSubtitle() {
        mainHandler?.post {
            val tv = subText ?: return@post
            if (lastZh.startsWith("✅ READY")) engineReady = true
            if (lastZh.isNotEmpty()) gotAnyPush = true
            val sb = SpannableStringBuilder(lastZh)
            if (bilingual && lastOrig.isNotEmpty() && lastOrig != lastZh) {
                sb.append('\n').append(lastOrig)
                val st = sb.length - lastOrig.length
                sb.setSpan(RelativeSizeSpan(0.55f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(0xFFB9C2D4.toInt()), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (sb.isEmpty()) { tv.visibility = View.INVISIBLE; return@post }
            tv.text = sb
            tv.visibility = View.VISIBLE
        }
    }

    private fun showStatus(text: String) {
        mainHandler?.post {
            val tv = subText ?: return@post
            if (text.isNotEmpty()) gotAnyPush = true
            tv.text = text
            tv.visibility = View.VISIBLE
        }
    }

    /** JS 桥 */
    inner class SubtitleBridge {
        @JavascriptInterface
        fun onSubtitle(zh: String) {
            lastZh = zh ?: ""
            renderSubtitle()
        }

        @JavascriptInterface
        fun onSubtitleOrig(orig: String) {
            lastOrig = orig ?: ""
            renderSubtitle()
        }

        @JavascriptInterface
        fun onStatus(text: String) { showStatus(text) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val resultCode = intent.getIntExtra("resultCode", -1)
        val data: Intent = intent.getParcelableExtra("data") ?: run { stopSelf(); return START_NOT_STICKY }
        val lang = intent.getStringExtra("lang") ?: "en"

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        fontSize = prefs.getInt("fontSize", 20)
        bgOpacity = prefs.getInt("bgOpacity", 40)
        translationOnly = prefs.getBoolean("translationOnly", true)
        bilingual = !translationOnly

        logFile = File(filesDir, "live-subtitles.log")
        log("service start, lang=$lang, font=$fontSize, opacity=$bgOpacity, translationOnly=$translationOnly, sdk=${android.os.Build.VERSION.SDK_INT}")

        createNotificationChannel()
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("实时字幕运行中")
            .setContentText("正在识别视频声音并翻译成中文")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        startForeground(
            NOTIF_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        mainHandler = Handler(mainLooper)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlays(lang)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            showStatus("⚠ 屏幕录制授权失败")
            log("getMediaProjection FAILED: $e")
            stopSelf(); return START_NOT_STICKY
        }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, mainHandler)

        startCapture()
        return START_NOT_STICKY
    }

    /* ---------------- 悬浮窗 ---------------- */

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setupOverlays(lang: String) {
        val windowManager = wm ?: return
        val h = resources.displayMetrics.heightPixels
        val subH = (h * 0.24).toInt()

        // —— 字幕窗：原生 TextView，触摸穿透 ——
        subParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            subH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        subParams!!.gravity = Gravity.BOTTOM or Gravity.START
        subParams!!.x = 0
        subParams!!.y = 0

        val pill = GradientDrawable().apply {
            setColor(Color.argb(bgOpacity * 255 / 100, 8, 10, 18))
            cornerRadius = dp(16).toFloat()
        }
        subPill = pill

        val tv = TextView(this).apply {
            background = pill
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(6f, 1f, 1f, Color.BLACK)
            setPadding(dp(18), dp(10), dp(18), dp(12))
            maxLines = 3
            text = "⏳ 正在启动字幕引擎…"
            visibility = View.VISIBLE
        }
        subText = tv

        val subBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(Color.TRANSPARENT)
        }
        subBox.addView(tv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(24), 0, dp(24), dp(10))
        })

        // —— 控制条：与字幕同窗，天然零间距贴靠 ——
        val bar = buildControlBar(subBox)
        val barWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        barWrap.addView(bar)
        // 控制条插在字幕框下方（同窗内 LinearLayout 顺序布局，间距=barWrap 的 0 边距）
        subBox.addView(barWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        })
        controlRoot = bar

        // —— 隐形计算引擎 WebView（1px 挂在视图树里） ——
        val wv = createEngineWebView(lang)
        webView = wv
        subBox.addView(wv, LinearLayout.LayoutParams(1, 1))

        windowManager.addView(subBox, subParams)
        subtitleRoot = subBox

        log("overlays added; loading page…")
        wv.loadUrl("https://appassets.androidplatform.net/assets/site/index.html?lang=$lang&app=1")
        pokeControls()
    }

    private fun createEngineWebView(lang: String): WebView {
        val wv = WebView(this)
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
        wv.settings.allowFileAccess = false
        wv.addJavascriptInterface(SubtitleBridge(), "AndroidBridge")

        val loader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val path = request.url.path ?: return null
                if (path.contains("/models/")) {
                    val rel = path.substringAfter("/models/").trimStart('/')
                    return try { serveModelRel(rel) } catch (e: Exception) {
                        log("model serve FAILED $rel: $e")
                        notFound()
                    }
                }
                return loader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                log("page finished")
                mainHandler?.post {
                    view.evaluateJavascript(
                        "window.__translationOnly = $translationOnly; " +
                            "window.__appStart && window.__appStart('$lang')", null,
                    )
                }
                // 看门狗：引擎无任何推送时，把探针结果显示在字幕上
                listOf(12000L, 25000L).forEach { d ->
                    mainHandler?.postDelayed({
                        if (gotAnyPush) return@postDelayed
                        webView?.evaluateJavascript(PROBE_JS) { r ->
                            log("probe: " + r.take(200))
                            if (!gotAnyPush) {
                                showStatus("⏳ 引擎状态: " + r.replace("\"", "").take(60))
                            }
                        }
                    }, d)
                }
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                log("JS[${message.messageLevel()}] ${message.message().take(140)}")
                return true
            }
        }
        return wv
    }

    private fun buildControlBar(parent: ViewGroup): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.control_bar_bg)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        // 触碰控制条即恢复不透明并重置淡出计时
        bar.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) pokeControls()
            false // 不消费，按钮/拖动照常工作
        }

        fun mkBtn(text: String, onClick: (TextView) -> Unit): TextView {
            val tv = TextView(this)
            tv.text = text
            tv.setTextColor(0xFFE8ECF5.toInt())
            tv.textSize = 15f
            tv.setPadding(dp(12), dp(6), dp(12), dp(6))
            tv.setOnClickListener { onClick(tv) }
            return tv
        }

        // 拖动把手：整组（字幕+控制条）上下移动
        val handle = TextView(this).apply {
            text = "⠿"
            setTextColor(0xFF8B96AD.toInt())
            textSize = 18f
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        var downRawY = 0f
        var startY = 0
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = ev.rawY
                    startY = subParams?.y ?: 0
                    pokeControls()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val sp = subParams ?: return@setOnTouchListener true
                    val dy = (downRawY - ev.rawY).toInt()
                    val maxY = (resources.displayMetrics.heightPixels * 0.72).toInt()
                    val ny = (startY + dy).coerceIn(0, maxY)
                    if (sp.y != ny) {
                        sp.y = ny
                        try { subtitleRoot?.let { wm?.updateViewLayout(it, sp) } } catch (_: Exception) {}
                    }
                    true
                }
                else -> false
            }
        }
        bar.addView(handle)

        bar.addView(mkBtn("A−") {
            fontSize = (fontSize - 2).coerceAtLeast(14)
            savePrefs(); applyNativeStyle()
        })
        bar.addView(mkBtn("A+") {
            fontSize = (fontSize + 2).coerceAtMost(36)
            savePrefs(); applyNativeStyle()
        })
        bar.addView(mkBtn("◐") {
            bgOpacity = when (bgOpacity) { 40 -> 15; 15 -> 60; else -> 40 }
            savePrefs(); applyNativeStyle()
        })
        bar.addView(mkBtn(if (translationOnly) "双" else "中") { btn ->
            translationOnly = !translationOnly
            bilingual = !translationOnly
            btn.text = if (translationOnly) "双" else "中"
            savePrefs()
            webView?.evaluateJavascript("window.__translationOnly = $translationOnly", null)
            renderSubtitle()
            pokeControls()
        })
        bar.addView(mkBtn("✕") { stopSelf() })
        return bar
    }

    private fun applyNativeStyle() {
        mainHandler?.post {
            subText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
            subPill?.alpha = (bgOpacity * 255 / 100)
        }
    }

    private fun savePrefs() {
        getSharedPreferences("settings", MODE_PRIVATE)
            .edit()
            .putInt("fontSize", fontSize)
            .putInt("bgOpacity", bgOpacity)
            .putBoolean("translationOnly", translationOnly)
            .apply()
    }

    /* ---------------- 自动淡出 ---------------- */

    private fun pokeControls() {
        fadeHandler.removeCallbacksAndMessages(null)
        controlRoot?.animate()?.alpha(1f)?.setDuration(150)?.start()
        subPill?.alpha = (bgOpacity * 255 / 100)
        fadeHandler.postDelayed({
            controlRoot?.animate()?.alpha(0.25f)?.setDuration(400)?.start()
            subPill?.alpha = ((bgOpacity * 255 / 100) / 3).coerceAtLeast(18)
        }, 3500)
    }

    /* ---------------- 音频内录 ---------------- */

    private fun startCapture() {
        try {
            val proj = projection ?: return
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
            )
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf * 4, 65536))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            audioRecord = record
            record.startRecording()
            running = true
            log("audio capture started")

            captureThread = Thread {
                val chunkFloats = 8000 // 0.5 秒 @16kHz
                val floatBuf = FloatArray(chunkFloats)
                val byteBuf = ByteBuffer.allocate(chunkFloats * 4).order(ByteOrder.LITTLE_ENDIAN)
                while (running) {
                    var total = 0
                    while (total < chunkFloats && running) {
                        val n = record.read(floatBuf, total, chunkFloats - total, AudioRecord.READ_BLOCKING)
                        if (n <= 0) break
                        total += n
                    }
                    if (total <= 0) break
                    chunkCount++
                    if (chunkCount % 120L == 0L) log("fed ${chunkCount} chunks (~${chunkCount / 2}s)")
                    byteBuf.clear()
                    for (i in 0 until total) byteBuf.putFloat(floatBuf[i])
                    val b64 = Base64.encodeToString(byteBuf.array(), Base64.NO_WRAP)
                    mainHandler?.post {
                        webView?.evaluateJavascript(
                            "window.__feedAudio && window.__feedAudio('$b64')", null,
                        )
                    }
                }
                log("capture thread exited")
            }.also { it.start() }
        } catch (e: Exception) {
            log("startCapture FAILED: $e")
            showStatus("⚠ 音频采集失败: ${e.message}")
        }
    }

    /* ---------------- 模型代理（filesDir 缓存 → hf-mirror） ---------------- */

    private val mtSources = mapOf(
        "opus-mt-en-zh/" to "https://hf-mirror.com/Xenova/opus-mt-en-zh/resolve/main/",
        "opus-mt-ja-en/" to "https://hf-mirror.com/Xenova/opus-mt-ja-en/resolve/main/",
    )
    private val zipSources = mapOf(
        "vosk-model-small-en-us-0.15.zip" to
            "https://hf-mirror.com/localstack/vosk-models/resolve/main/vosk-model-small-en-us-0.15.zip",
        "vosk-model-small-ja-0.22.zip" to
            "https://hf-mirror.com/localstack/vosk-models/resolve/main/vosk-model-small-ja-0.22.zip",
    )

    private fun serveModelRel(rel: String): WebResourceResponse? {
        if (rel.isEmpty()) return notFound()

        zipSources[rel]?.let { url ->
            return serveCached(url, rel, "application/zip")
        }

        val entry = mtSources.entries.firstOrNull { rel.startsWith(it.key) } ?: return notFound()
        val tail = rel.removePrefix(entry.key)
        if (tail.isEmpty() || tail.contains("..")) return notFound()
        val repoPath = when {
            Regex("^onnx/[\\w.-]+\\.onnx$").matches(tail) -> tail
            Regex("^[\\w.-]+\\.onnx$").matches(tail) -> "onnx/$tail"
            Regex("^[\\w.-]+$").matches(tail) -> tail
            else -> return notFound()
        }
        val mime = when {
            tail.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
        return serveCached(entry.value + repoPath, rel, mime)
    }

    private fun serveCached(url: String, rel: String, mime: String): WebResourceResponse {
        val file = File(filesDir, "models/$rel")
        if (!file.exists() || file.length() == 0L) {
            log("model download start: $rel")
            showStatus("⬇ 下载模型: ${shortName(rel)}")
            downloadTo(url, file) { done, total ->
                if (total > 0) {
                    showStatus("⬇ ${shortName(rel)} ${done / 1048576}/${total / 1048576}MB")
                } else {
                    showStatus("⬇ ${shortName(rel)} ${done / 1048576}MB")
                }
            }
            log("model download done: $rel (${file.length() / 1048576}MB)")
        }
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cross-Origin-Opener-Policy" to "same-origin",
            "Cross-Origin-Embedder-Policy" to "require-corp",
            "Cache-Control" to "public, max-age=86400",
        )
        return WebResourceResponse(mime, null, 200, "OK", headers, file.inputStream())
    }

    private fun shortName(rel: String): String = when {
        rel.contains("en-us") && rel.endsWith(".zip") -> "英文识别模型"
        rel.contains("ja") && rel.endsWith(".zip") -> "日文识别模型"
        rel.startsWith("opus-mt-en-zh") -> "英中翻译模型"
        rel.startsWith("opus-mt-ja-en") -> "日英翻译模型"
        else -> rel.take(20)
    }

    private fun downloadTo(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")
        var lastErr: IOException? = null
        for (attempt in 1..3) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 180000
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 512 * 1024) { // 每 0.5MB 汇报一次
                                lastReport = done
                                onProgress(done, total)
                            }
                        }
                    }
                }
                if (!tmp.renameTo(dest)) throw IOException("rename failed")
                return
            } catch (e: IOException) {
                lastErr = e
                tmp.delete()
            }
        }
        throw lastErr ?: IOException("download failed: $url")
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, "not found".byteInputStream())

    /* ---------------- 生命周期 ---------------- */

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "实时字幕", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onDestroy() {
        running = false
        log("service destroy")
        fadeHandler.removeCallbacksAndMessages(null)
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        mainHandler?.post {
            subtitleRoot?.let { v -> try { wm?.removeView(v) } catch (_: Exception) {} }
            controlRoot?.let { v -> try { wm?.removeView(v) } catch (_: Exception) {} }
            webView?.let { wv -> try { wv.destroy() } catch (_: Exception) {} }
            subtitleRoot = null; controlRoot = null; webView = null
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "live_subtitle"
        private const val NOTIF_ID = 100

        private const val PROBE_JS = (
            "(function(){try{return JSON.stringify({" +
                "ready:!!window.__appReady," +
                "started:!!window.__appStarted," +
                "err:window.__appError||null," +
                "vosk:typeof Vosk," +
                "feed:typeof window.__feedAudio" +
            "})}catch(e){return 'probe-err:'+e}})()"
        )
    }
}
