package com.floatagent

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.floatagent.sync.Trip
import com.floatagent.sync.TripRepository
import com.floatagent.sync.TripStop
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 车机模式：输入手机端配对码 → 实时监听云端行程 → 展示完整路线、高亮当前目标点
 * → 点「已到达」推进 currentIndex（写回云端，手机端可见进度）。
 */
class CarReceiverActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var tvCurrentTarget: TextView
    private lateinit var btnArrived: Button

    private var listener: ListenerRegistration? = null
    private var pairCode: String? = null
    private var trip: Trip? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_car_receiver)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        webView = findViewById(R.id.carWebView)
        tvStatus = findViewById(R.id.tvCarStatus)
        tvCurrentTarget = findViewById(R.id.tvCurrentTarget)
        btnArrived = findViewById(R.id.btnArrived)
        val etPairCode = findViewById<EditText>(R.id.etPairCode)
        val btnConnect = findViewById<Button>(R.id.btnConnect)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.webViewClient = WebViewClient()

        btnConnect.setOnClickListener {
            val code = etPairCode.text.toString().trim()
            if (code.length != 6) {
                tvStatus.text = "请输入 6 位配对码"
                return@setOnClickListener
            }
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(etPairCode.windowToken, 0)
            connect(code)
        }

        btnArrived.setOnClickListener { arrived() }
    }

    private fun connect(code: String) {
        listener?.remove()
        pairCode = code
        tvStatus.text = "连接中…"
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) { TripRepository.ensureSignedIn() }
            } catch (e: Exception) {
                tvStatus.text = "登录失败：${e.message}"
                return@launch
            }
            listener = TripRepository.listenTrip(
                code,
                onUpdate = { t ->
                    if (t == null || t.stops.isEmpty()) {
                        tvStatus.text = "配对码 $code 暂无行程（确认手机端已发送）"
                        return@listenTrip
                    }
                    trip = t
                    render(t)
                },
                onError = { e -> tvStatus.text = "监听失败：${e.message}" }
            )
        }
    }

    private fun render(t: Trip) {
        val done = t.status == "done" || t.currentIndex >= t.stops.size
        val total = t.stops.size
        if (done) {
            tvStatus.text = "✅ 行程已完成（共 $total 个点）"
            tvCurrentTarget.text = "全部到达"
            btnArrived.visibility = View.GONE
        } else {
            tvStatus.text = "进度 ${t.currentIndex + 1} / $total · 已连接"
            val target = t.stops[t.currentIndex]
            tvCurrentTarget.text = "当前前往：${target.name}"
            btnArrived.visibility = View.VISIBLE
            btnArrived.text =
                if (t.currentIndex == total - 1) "已到达终点，完成 🏁" else "已到达，下一个 ➜"
        }
        webView.loadDataWithBaseURL(
            "https://webapi.amap.com",
            buildHtml(t.stops, t.currentIndex),
            "text/html", "utf-8", null
        )
    }

    private fun arrived() {
        val t = trip ?: return
        val code = pairCode ?: return
        val next = t.currentIndex + 1
        val done = next >= t.stops.size
        btnArrived.isEnabled = false
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) { TripRepository.advance(code, next, done) }
                // 进度变化会通过监听器回流并触发 render，这里无需手动刷新
            } catch (e: Exception) {
                tvStatus.text = "推进失败：${e.message}"
            } finally {
                btnArrived.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        listener?.remove()
        super.onDestroy()
    }

    /** 渲染完整路线：已过的点变灰，当前点放大高亮，未到的点正常。 */
    private fun buildHtml(stops: List<TripStop>, currentIndex: Int): String {
        val valid = stops.filter { it.lat != 0.0 || it.lng != 0.0 }
        if (valid.size < 2) return "<html><body style='font-family:sans-serif;padding:24px'>路线信息不足</body></html>"

        val center = valid.getOrElse(currentIndex.coerceIn(0, valid.lastIndex)) { valid.first() }
        val allPath = valid.joinToString(",") { "[${it.lng},${it.lat}]" }

        val markersJs = valid.mapIndexed { i, s ->
            val isCurrent = i == currentIndex
            val passed = i < currentIndex
            val (label, color) = when {
                i == 0 -> "起" to "#34A853"
                i == valid.lastIndex -> "终" to "#EA4335"
                else -> "$i" to "#FF8F00"
            }
            val bg = if (passed) "#BDBDBD" else color
            val size = if (isCurrent) 38 else 28
            val ring = if (isCurrent) "box-shadow:0 0 0 4px rgba(26,115,232,0.45),0 1px 4px rgba(0,0,0,0.4)" else "box-shadow:0 1px 4px rgba(0,0,0,0.4)"
            val safeName = s.name.replace("'", "\\'")
            """
            (function() {
                var marker = new AMap.Marker({
                    position: [${s.lng}, ${s.lat}],
                    content: '<div style="background:$bg;color:#fff;border-radius:50%;width:${size}px;height:${size}px;line-height:${size}px;text-align:center;font-size:13px;font-weight:bold;border:2px solid #fff;$ring">$label</div>',
                    offset: new AMap.Pixel(-${size / 2}, -${size / 2}),
                    zIndex: ${if (isCurrent) 300 else 200}
                });
                var info = new AMap.InfoWindow({
                    content: '<div style="padding:6px 10px;font-size:13px">$safeName</div>',
                    offset: new AMap.Pixel(0, -${size / 2 + 2})
                });
                marker.on('click', function() { info.open(map, marker.getPosition()); });
                map.add(marker);
            })();
            """.trimIndent()
        }.joinToString("\n")

        val start = valid.first()
        val end = valid.last()
        val waypoints = if (valid.size > 2) valid.subList(1, valid.size - 1) else emptyList()
        val waypointsJs = waypoints.joinToString(",") { "[${it.lng},${it.lat}]" }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
                <style>html,body,#container{margin:0;padding:0;width:100%;height:100%;}</style>
            </head>
            <body>
                <div id="container"></div>
                <script src="https://webapi.amap.com/maps?v=2.0&key=89713d2ac746f8b895e0b5b2363e181a&plugin=AMap.Driving"></script>
                <script>
                    var map = new AMap.Map('container', { zoom: 12, center: [${center.lng}, ${center.lat}] });
                    var allPath = [$allPath];

                    function drawFallback() {
                        var pl = new AMap.Polyline({
                            path: allPath, strokeColor: '#1A73E8',
                            strokeWeight: 6, strokeOpacity: 0.85, lineJoin: 'round'
                        });
                        map.add(pl);
                        map.setFitView();
                    }

                    AMap.plugin('AMap.Driving', function() {
                        try {
                            var driving = new AMap.Driving({ map: map, hideMarkers: true, autoFitView: true });
                            driving.search(
                                [${start.lng}, ${start.lat}],
                                [${end.lng}, ${end.lat}],
                                { waypoints: [$waypointsJs] },
                                function(status, result) {
                                    if (status !== 'complete') drawFallback();
                                    addMarkers();
                                }
                            );
                        } catch (e) {
                            drawFallback();
                            addMarkers();
                        }
                    });

                    function addMarkers() {
                        $markersJs
                        map.setFitView();
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
