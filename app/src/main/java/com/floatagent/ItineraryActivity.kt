package com.floatagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.floatagent.agent.ItineraryAgent
import com.floatagent.agent.ItineraryResult
import com.floatagent.analyzer.DayWeather
import com.floatagent.analyzer.GaodeSearcher
import com.floatagent.model.Category
import com.floatagent.model.SavedPlace
import com.floatagent.storage.CollectionStorage
import com.floatagent.sync.TripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ItineraryActivity : AppCompatActivity() {

    private lateinit var etStart: EditText
    private lateinit var etRequest: EditText
    private lateinit var etStartTime: EditText
    private lateinit var etEnd: EditText
    private lateinit var btnPlan: Button
    private lateinit var btnRouteMap: Button
    private lateinit var btnSendCar: Button
    private lateinit var tvResult: TextView
    private lateinit var weatherScroll: View
    private lateinit var weatherBar: LinearLayout

    private val prefs by lazy { getSharedPreferences("float_agent_route", Context.MODE_PRIVATE) }
    private val beijingCenter = 39.9042 to 116.4074
    private var routeStopsJson: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_itinerary)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        etStart = findViewById(R.id.etStart)
        etRequest = findViewById(R.id.etRequest)
        etStartTime = findViewById(R.id.etStartTime)
        etEnd = findViewById(R.id.etEnd)
        btnPlan = findViewById(R.id.btnPlan)
        btnRouteMap = findViewById(R.id.btnRouteMap)
        btnSendCar = findViewById(R.id.btnSendCar)
        tvResult = findViewById(R.id.tvResult)
        weatherScroll = findViewById(R.id.weatherScroll)
        weatherBar = findViewById(R.id.weatherBar)

        etStartTime.setText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
        loadWeather()
        // 回填上次填过的出发/终点
        etStart.setText(prefs.getString("start_addr", ""))
        etEnd.setText(prefs.getString("end_addr", ""))

        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1
        )

        btnPlan.setOnClickListener { doPlan() }
        btnRouteMap.setOnClickListener {
            routeStopsJson?.let {
                startActivity(Intent(this, RouteMapActivity::class.java).putExtra("stops", it))
            }
        }
        btnSendCar.setOnClickListener { sendToCar() }
    }

    /** 生成配对码，把当前路线上传到云端，弹窗显示配对码供车机端连接 */
    private fun sendToCar() {
        val stops = routeStopsJson ?: return
        val pairCode = TripRepository.newPairCode()
        btnSendCar.isEnabled = false
        btnSendCar.text = "正在发送…"
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) { TripRepository.uploadTrip(pairCode, stops) }
                androidx.appcompat.app.AlertDialog.Builder(this@ItineraryActivity)
                    .setTitle("已发送到云端")
                    .setMessage("在车机端「车机模式」输入配对码：\n\n$pairCode\n\n车机连接后会显示完整路线，可逐点推进。")
                    .setPositiveButton("知道了", null)
                    .show()
            } catch (e: Exception) {
                tvResult.visibility = View.VISIBLE
                tvResult.text = "发送失败：${e.message}"
            } finally {
                btnSendCar.isEnabled = true
                btnSendCar.text = "发送到车机 🚗"
            }
        }
    }

    private fun doPlan() {
        val request = etRequest.text.toString().trim()
        if (request.isEmpty()) {
            tvResult.visibility = View.VISIBLE
            tvResult.text = "请先描述你想做什么"
            return
        }
        val startTime = etStartTime.text.toString().trim().ifEmpty { "12:00" }
        val startAddr = etStart.text.toString().trim()
        val endAddr = etEnd.text.toString().trim()

        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etRequest.windowToken, 0)

        btnPlan.isEnabled = false
        btnRouteMap.visibility = View.GONE
        btnSendCar.visibility = View.GONE
        tvResult.visibility = View.VISIBLE
        tvResult.text = "正在规划行程..."

        val places = CollectionStorage.getAll(this)

        CoroutineScope(Dispatchers.Main).launch {
            // 出发地：填了就地理编码，没填用 GPS/收藏中心兜底
            val startLabel = startAddr.ifEmpty { "当前位置" }
            val start = if (startAddr.isNotEmpty()) resolveLocation("start", startAddr)
                        else currentLocationOr(centroidOf(places))
            // 终点
            val endLabel = endAddr.ifEmpty { "终点" }
            val end = if (endAddr.isNotEmpty()) resolveLocation("end", endAddr) else (0.0 to 0.0)

            val result = withContext(Dispatchers.IO) {
                ItineraryAgent.plan(
                    context = this@ItineraryActivity,
                    userRequest = request,
                    startTime = startTime,
                    startName = startLabel,
                    currentLat = start.first, currentLng = start.second,
                    endName = endLabel,
                    homeLat = end.first, homeLng = end.second
                )
            }
            btnPlan.isEnabled = true
            renderResult(result, startLabel, startTime, endLabel)
            buildStops(result, startLabel, start, endLabel, end)
        }
    }

    private fun buildStops(
        result: ItineraryResult,
        startLabel: String, start: Pair<Double, Double>,
        endLabel: String, end: Pair<Double, Double>
    ) {
        val stops = JSONArray()
        fun add(name: String, lat: Double, lng: Double, kind: String) {
            if (lat == 0.0 && lng == 0.0) {
                android.util.Log.w("FloatAgent_Itinerary", "跳过点 $name ($kind)：坐标为 0")
                return
            }
            stops.put(JSONObject().put("name", name).put("lat", lat).put("lng", lng).put("kind", kind))
            android.util.Log.d("FloatAgent_Itinerary", "加入点 $name ($kind) at $lat,$lng")
        }
        add(startLabel, start.first, start.second, "start")
        result.steps.forEach { step ->
            if (step.place == null) {
                android.util.Log.w(
                    "FloatAgent_Itinerary",
                    "跳过途经点 ${step.placeName}：未匹配到收藏（agent 输出的名称对不上）"
                )
                return@forEach
            }
            add(step.place.name, step.place.lat, step.place.lng, "waypoint")
        }
        add(endLabel, end.first, end.second, "end")

        if (stops.length() >= 2) {
            routeStopsJson = stops.toString()
            btnRouteMap.visibility = View.VISIBLE
            btnSendCar.visibility = View.VISIBLE
        }
    }

    private fun renderResult(
        result: ItineraryResult,
        startLabel: String, startTime: String, endLabel: String
    ) {
        if (result.steps.isEmpty()) {
            tvResult.text = result.summary.ifEmpty { "未能规划出行程，换个说法或先收藏更多场所试试" }
            return
        }

        val routeByTo = result.routes.associateBy { it.to }
        val sb = StringBuilder()
        if (result.summary.isNotBlank()) sb.append(result.summary).append("\n\n")

        sb.append("🗺 行程路线\n──────────────\n")
        sb.append("🚩 出发：$startLabel  $startTime\n")

        result.steps.forEach { step ->
            routeByTo[step.placeName]?.let { seg ->
                sb.append("    ↓ ${"%.1f".format(seg.distanceKm)}km · ${seg.durationMin}分钟\n")
            }
            val emoji = step.place?.category?.emoji ?: "📍"
            sb.append("${step.arrivalTime}  $emoji ${step.placeName}\n")
            val dur = if (step.estimatedDuration > 0) " · 约${step.estimatedDuration}分钟" else ""
            sb.append("        ${step.activity}$dur\n")
        }

        routeByTo[endLabel]?.let { seg ->
            sb.append("    ↓ ${"%.1f".format(seg.distanceKm)}km · ${seg.durationMin}分钟\n")
        }
        sb.append("🏁 终点：$endLabel\n")

        if (result.routes.isNotEmpty()) {
            val totalKm = result.routes.sumOf { it.distanceKm }
            val totalMin = result.routes.sumOf { it.durationMin }
            sb.append("──────────────\n")
            sb.append("🚗 全程约 ${"%.1f".format(totalKm)}km，路上约 $totalMin 分钟")
        }

        tvResult.text = sb.toString()
    }

    private suspend fun resolveLocation(key: String, addr: String): Pair<Double, Double> {
        val savedAddr = prefs.getString("${key}_addr", "")
        if (addr == savedAddr) {
            val lat = prefs.getFloat("${key}_lat", 0f).toDouble()
            val lng = prefs.getFloat("${key}_lng", 0f).toDouble()
            if (lat != 0.0 || lng != 0.0) return lat to lng
        }
        val located = withContext(Dispatchers.IO) {
            GaodeSearcher.searchCoordinate(
                SavedPlace(name = addr, category = Category.OTHER, address = addr)
            )
        }
        prefs.edit()
            .putString("${key}_addr", addr)
            .putFloat("${key}_lat", located.lat.toFloat())
            .putFloat("${key}_lng", located.lng.toFloat())
            .apply()
        return located.lat to located.lng
    }

    private fun loadWeather() {
        val (lat, lng) = centroidOf(CollectionStorage.getAll(this))
        CoroutineScope(Dispatchers.Main).launch {
            val days = withContext(Dispatchers.IO) { GaodeSearcher.fetchWeather(lat, lng) }
            if (days.isEmpty()) return@launch
            weatherBar.removeAllViews()
            days.forEachIndexed { i, d -> weatherBar.addView(buildWeatherCard(d, i)) }
            weatherScroll.visibility = View.VISIBLE
        }
    }

    private fun buildWeatherCard(d: DayWeather, index: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        val dayLabel = when (index) {
            0 -> "今天"
            1 -> "明天"
            else -> weekLabel(d.week)
        }
        card.addView(TextView(this).apply {
            text = dayLabel
            textSize = 12f
            setTextColor(0xFF555555.toInt())
        })
        card.addView(TextView(this).apply {
            text = weatherEmoji(d.dayWeather)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        })
        card.addView(TextView(this).apply {
            text = d.dayWeather
            textSize = 11f
            setTextColor(0xFF333333.toInt())
        })
        card.addView(TextView(this).apply {
            text = "${d.nightTemp}~${d.dayTemp}°"
            textSize = 12f
            setTextColor(0xFF1A73E8.toInt())
            setPadding(0, dp(2), 0, 0)
        })
        return card
    }

    private fun weekLabel(week: String): String = when (week) {
        "1" -> "周一"; "2" -> "周二"; "3" -> "周三"; "4" -> "周四"
        "5" -> "周五"; "6" -> "周六"; "7" -> "周日"; else -> ""
    }

    private fun weatherEmoji(text: String): String = when {
        text.contains("雪") -> "❄️"
        text.contains("雷") -> "⛈️"
        text.contains("雨") -> "🌧️"
        text.contains("多云") -> "⛅"
        text.contains("阴") -> "☁️"
        text.contains("晴") -> "☀️"
        else -> "🌤️"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun currentLocationOr(fallback: Pair<Double, Double>): Pair<Double, Double> {
        return try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (loc != null) loc.latitude to loc.longitude else fallback
            } else fallback
        } catch (e: Exception) {
            fallback
        }
    }

    private fun centroidOf(places: List<SavedPlace>): Pair<Double, Double> {
        val located = places.filter { it.lat != 0.0 && it.lng != 0.0 }
        if (located.isEmpty()) return beijingCenter
        return located.map { it.lat }.average() to located.map { it.lng }.average()
    }
}
