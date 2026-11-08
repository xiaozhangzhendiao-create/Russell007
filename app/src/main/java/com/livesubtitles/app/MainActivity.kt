package com.livesubtitles.app

import android.app.Activity
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

        langSpinner = findViewById(R.id.lang_spinner)
        statusText = findViewById(R.id.status_text)
        langSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.lang_en), getString(R.string.lang_ja)),
        )

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
            statusText.text = "状态：运行中（首次加载模型需要一些时间，请耐心等待）"
        } else {
            Toast.makeText(this, "未授予屏幕录制权限，无法内录视频声音", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_CAPTURE = 1001
    }
}
