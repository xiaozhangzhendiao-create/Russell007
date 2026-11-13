package com.livesubtitles.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var langSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        installCrashHandler()

        langSpinner = findViewById(R.id.lang_spinner)
        statusText = findViewById(R.id.status_text)
        langSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.lang_en), getString(R.string.lang_ja)),
        )

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 200)
            } catch (_: Exception) {}
        }

        findViewById<Button>(R.id.start_btn).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先允许「显示在其他应用上层」（悬浮字幕窗口需要）", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
                return@setOnClickListener
            }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        }

        findViewById<Button>(R.id.stop_btn).setOnClickListener {
            stopService(Intent(this, SubtitleService::class.java))
            statusText.text = getString(R.string.status_idle)
        }

        findViewById<Button>(R.id.log_btn).setOnClickListener { showLogs() }
    }

    /** 未捕获异常写入文件，便于远程排错 */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val f = java.io.File(filesDir, "crash.log")
                f.appendText(
                    "\n===== ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date())} =====\n" +
                        throwable.stackTraceToString() + "\n",
                )
            } catch (_: Exception) {}
            prev?.uncaughtException(thread, throwable)
        }
    }

    private fun readTail(file: java.io.File, maxBytes: Int = 6000): String {
        return try {
            if (!file.exists()) return "(无)"
            val text = file.readText()
            if (text.length <= maxBytes) text else "…(前略)\n" + text.takeLast(maxBytes)
        } catch (e: Exception) { "(读取失败: ${e.message})" }
    }

    private fun showLogs() {
        val crash = readTail(java.io.File(filesDir, "crash.log"), 4000)
        val run = readTail(java.io.File(filesDir, "live-subtitles.log"), 6000)
        val msg = "【崩溃日志】\n$crash\n\n【运行日志】\n$run"
        AlertDialog.Builder(this)
            .setTitle("诊断日志（可截图反馈）")
            .setMessage(msg)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空") { _, _ ->
                try {
                    java.io.File(filesDir, "crash.log").delete()
                    java.io.File(filesDir, "live-subtitles.log").delete()
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == Activity.RESULT_OK && data != null) {
            val lang = if (langSpinner.selectedItemPosition == 1) "ja" else "en"
            val svc = Intent(this, SubtitleService::class.java)
                .putExtra("resultCode", resultCode)
                .putExtra("data", data)
                .putExtra("lang", lang)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
                else startService(svc)
                statusText.text = "状态：运行中（首次加载模型需要一些时间，请耐心等待）"
            } catch (e: Exception) {
                statusText.text = "启动失败：${e.message}"
            }
        } else {
            Toast.makeText(this, "未授予屏幕录制权限，无法内录视频声音", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_CAPTURE = 1001
    }
}
