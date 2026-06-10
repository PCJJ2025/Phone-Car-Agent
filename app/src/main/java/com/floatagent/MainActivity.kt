package com.floatagent

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.floatagent.service.FloatingBallService
import com.floatagent.service.ScreenCaptureService

class MainActivity : AppCompatActivity() {

    private val REQUEST_MEDIA_PROJECTION = 1001
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        val btnOverlay = findViewById<Button>(R.id.btnOverlay)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnCollection = findViewById<Button>(R.id.btnCollection)

        btnOverlay.setOnClickListener {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnCollection.setOnClickListener {
            startActivity(Intent(this, CollectionListActivity::class.java))
        }

        findViewById<Button>(R.id.btnItinerary).setOnClickListener {
            startActivity(Intent(this, ItineraryActivity::class.java))
        }

        findViewById<Button>(R.id.btnCarMode).setOnClickListener {
            startActivity(Intent(this, CarReceiverActivity::class.java))
        }

        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                tvStatus.text = "请先开启悬浮窗权限"
                return@setOnClickListener
            }
            // 请求截图权限
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // 先启动前台服务，再初始化 MediaProjection（Android 14+ 要求）
                val serviceIntent = Intent(this, FloatingBallService::class.java)
                startForegroundService(serviceIntent)
                // 等服务启动后再初始化截图
                serviceIntent.putExtra("resultCode", resultCode)
                serviceIntent.putExtra("data", data)
                ScreenCaptureService.initialize(this, resultCode, data)
                tvStatus.text = "悬浮球已启动，支持文字+图片识别"
            } else {
                startForegroundService(Intent(this, FloatingBallService::class.java))
                tvStatus.text = "悬浮球已启动（仅文字识别，未授权截图）"
            }
        }
    }
}
