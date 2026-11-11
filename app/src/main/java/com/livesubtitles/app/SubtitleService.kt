package com.livesubtitles.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
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
 * 1) MediaProjection 内录其他 App 的媒体音频，重采样为 16kHz 单声道
 * 2) 悬浮窗 = 原生调试条(顶部) + 透明 WebView(下方)，WebView 运行网页版同款识别+翻译+字幕
 * 3) /models/ 路径请求代理：本地缓存优先，未命中从 hf-mirror 下载（中国网络可达）
 *
 * 调试条显示最近 3 条内部日志（页面加载/JS 控制台/音频喂数/探针结果），用于远程排错。
 */
class SubtitleService : Service() {

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var webView: WebView? = null
    private var mainHandler: Handler? = null
    private var debugView: TextView? = null
    private var logFile: File? = null
    private val logLines = ArrayDeque<String>()
    @Volatile private var running = false
    @Volatile private var chunkCount = 0L

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun log(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 60) logLines.removeFirst()
        }
        try { logFile?.appendText(line + "\n") } catch (_: Exception) {}
        android.util.Log.i("LiveSub", line)
        mainHandler?.post {
            debugView?.text = synchronized(logLines) { logLines.takeLast(3).joinToString("\n") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val resultCode = intent.getIntExtra("resultCode", -1)
        val data: Intent = intent.getParcelableExtra("data") ?: run { stopSelf(); return START_NOT_STICKY }
        val lang = intent.getStringExtra("lang") ?: "en"

        logFile = File(filesDir, "live-subtitles.log")
        log("service start, lang=$lang, sdk=${android.os.Build.VERSION.SDK_INT}")

        // API 34+ 要求：必须在 getMediaProjection 之前进入 mediaProjection 类型的前台服务
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
        setupOverlayWebView(lang)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            log("getMediaProjection FAILED: $e")
            stopSelf(); return START_NOT_STICKY
        }
        log("media projection acquired")
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { log("projection stopped by system"); stopSelf() }
        }, mainHandler)

        startCapture()
        return START_NOT_STICKY
    }

    /* ---------------- 悬浮窗：调试条 + WebView ---------------- */

    private fun setupOverlayWebView(lang: String) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val h = resources.displayMetrics.heightPixels
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (h * 0.26).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.BOTTOM or Gravity.START
        params.x = 0
        params.y = 0

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB3222222.toInt())
        }
        val tv = TextView(this).apply {
            setTextColor(0xFF7CFFB2.toInt())
            textSize = 9f
            setPadding(10, 4, 10, 4)
            maxLines = 3
        }
        box.addView(tv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val wv = WebView(this)
        wv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
        wv.settings.allowFileAccess = false
        wv.settings.javaScriptCanOpenWindowsAutomatically = true
        box.addView(wv)

        wm.addView(box, params)
        debugView = tv
        webView = wv
        log("overlay window added (${wv.settings.userAgentString.take(40)})")

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
                // 页面在 /assets/site/ 下，相对路径 ./models/... 会解析为
                // /assets/site/models/...；统一按 "最后一个 /models/ 之后" 提取
                if (path.contains("/models/")) {
                    val rel = path.substringAfter("/models/").trimStart('/')
                    val resp = try { serveModelRel(rel) } catch (e: Exception) {
                        log("model serve FAILED $rel: $e")
                        notFound()
                    }
                    if (resp != null) log("model hit: $rel")
                    return resp
                }
                return loader.shouldInterceptRequest(request.url)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                log("page started: ${url.take(80)}")
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                log("page finished: ${url.take(80)}")
                mainHandler?.post {
                    view.evaluateJavascript(
                        "window.__appStart && window.__appStart('$lang')", null,
                    )
                }
                // 探针：确认页面内部状态（0s/6s/16s 各一次）
                listOf(0L, 6000L, 16000L).forEach { d ->
                    mainHandler?.postDelayed({
                        webView?.evaluateJavascript(PROBE_JS) { r ->
                            log("probe: " + r.take(280))
                        }
                    }, d)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    log("page ERROR: ${error.description} ${request.url}")
                }
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                log("JS[${message.messageLevel()}] ${message.message().take(160)} @${message.lineNumber()}")
                return true
            }
        }

        log("loading page…")
        wv.loadUrl("https://appassets.androidplatform.net/assets/site/index.html?lang=$lang&app=1")
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
            log("audio capture started, state=${record.recordingState}")

            captureThread = Thread {
                val chunkFloats = 8000 // 0.5 秒 @16kHz
                val floatBuf = FloatArray(chunkFloats)
                val byteBuf = ByteBuffer.allocate(chunkFloats * 4).order(ByteOrder.LITTLE_ENDIAN)
                var silentMax = 0f
                while (running) {
                    var total = 0
                    while (total < chunkFloats && running) {
                        val n = record.read(floatBuf, total, chunkFloats - total, AudioRecord.READ_BLOCKING)
                        if (n <= 0) break
                        total += n
                    }
                    if (total <= 0) break
                    chunkCount++
                    if (chunkCount % 40L == 0L) { // 每 ~20 秒报告一次
                        for (i in 0 until total step 7) {
                            val v = Math.abs(floatBuf[i])
                            if (v > silentMax) silentMax = v
                        }
                        log("fed ${chunkCount} chunks, peak≈${"%.3f".format(silentMax)}")
                        silentMax = 0f
                    }
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
            tail.endsWith(".onnx") || tail.endsWith(".spm") -> "application/octet-stream"
            else -> "application/octet-stream"
        }
        return serveCached(entry.value + repoPath, rel, mime)
    }

    private fun serveCached(url: String, rel: String, mime: String): WebResourceResponse {
        val file = File(filesDir, "models/$rel")
        if (!file.exists() || file.length() == 0L) {
            log("model download start: $rel")
            downloadTo(url, file)
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

    private fun downloadTo(url: String, dest: File) {
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
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out, 1 shl 16) }
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
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try {
            mainHandler?.post {
                webView?.let { wv ->
                    try {
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv.destroy()
                    } catch (_: Exception) {}
                }
                webView = null
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "live_subtitle"
        private const val NOTIF_ID = 100

        // 探针：读取页面内部状态（__appReady/__appError/桥接函数是否就位）
        private const val PROBE_JS = (
            "(function(){try{return JSON.stringify({" +
                "ready:!!window.__appReady," +
                "started:!!window.__appStarted," +
                "err:window.__appError||null," +
                "vosk:typeof Vosk," +
                "feed:typeof window.__feedAudio," +
                "sub:" + "(function(){var z=document.getElementById('sub-zh');return z?z.textContent.slice(0,40):'no-el'})()" +
            "})}catch(e){return 'probe-err:'+e}})()"
        )
    }
}
