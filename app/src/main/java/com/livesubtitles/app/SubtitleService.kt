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
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 前台服务：
 * 1) MediaProjection 内录其他 App 的媒体音频，重采样为 16kHz 单声道
 * 2) 悬浮透明 WebView（不可触摸），运行与网页版同一套识别+翻译+字幕代码
 * 3) /models/ 路径请求代理：本地缓存优先，未命中从 hf-mirror 下载（中国网络可达）
 */
class SubtitleService : Service() {

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var webView: WebView? = null
    private var mainHandler: Handler? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val resultCode = intent.getIntExtra("resultCode", -1)
        val data: Intent = intent.getParcelableExtra("data") ?: run { stopSelf(); return START_NOT_STICKY }
        val lang = intent.getStringExtra("lang") ?: "en"

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
            stopSelf(); return START_NOT_STICKY
        }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, mainHandler)

        startCapture()
        return START_NOT_STICKY
    }

    /* ---------------- 悬浮字幕 WebView ---------------- */

    private fun setupOverlayWebView(lang: String) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val h = resources.displayMetrics.heightPixels
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (h * 0.24).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.BOTTOM or Gravity.START
        params.x = 0
        params.y = 0

        val wv = WebView(this)
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
        wv.settings.allowFileAccess = false

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
                    return try { serveModelRel(rel) } catch (e: Exception) { notFound() }
                }
                return loader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                mainHandler?.post {
                    view.evaluateJavascript(
                        "window.__appStart && window.__appStart('$lang')", null,
                    )
                }
            }
        }

        wm.addView(wv, params)
        webView = wv
        wv.loadUrl("https://appassets.androidplatform.net/assets/site/index.html?lang=$lang&app=1")
    }

    /* ---------------- 音频内录 ---------------- */

    private fun startCapture() {
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
                byteBuf.clear()
                for (i in 0 until total) byteBuf.putFloat(floatBuf[i])
                val b64 = Base64.encodeToString(byteBuf.array(), Base64.NO_WRAP)
                mainHandler?.post {
                    webView?.evaluateJavascript(
                        "window.__feedAudio && window.__feedAudio('$b64')", null,
                    )
                }
            }
        }.also { it.start() }
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
            downloadTo(url, file)
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
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try {
            mainHandler?.post {
                webView?.let { wv ->
                    try {
                        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
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
    }
}
