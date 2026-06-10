package com.floatagent.agent

import android.content.Context
import android.util.Log
import com.floatagent.BuildConfig
import com.floatagent.analyzer.GaodeSearcher
import com.floatagent.model.SavedPlace
import com.floatagent.storage.CollectionStorage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ItineraryAgent {

    private const val TAG = "FloatAgent_Itinerary"
    private val API_KEY = BuildConfig.ANTHROPIC_API_KEY
    private const val API_URL = "https://api.anthropic.com/v1/messages"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun plan(
        context: Context,
        userRequest: String,       // "我想吃火锅，喝杯奶茶，做个按摩"
        startTime: String,         // "18:00"
        startName: String,         // 出发地点名称
        currentLat: Double,        // 出发地点坐标
        currentLng: Double,
        endName: String,           // 终点名称
        homeLat: Double,           // 终点坐标
        homeLng: Double
    ): ItineraryResult {
        val savedPlaces = CollectionStorage.getAll(context)

        val placesDesc = savedPlaces.joinToString("\n") {
            "${it.category.emoji}${it.name}（${it.category.label}）- ${it.address}" +
            if (it.items.isNotEmpty()) " | 推荐：${it.items.take(3).joinToString("、")}" else ""
        }

        val prompt = """
            用户的需求：$userRequest
            出发时间：$startTime
            出发地点：$startName
            终点：$endName

            用户收藏的场所列表：
            $placesDesc

            请根据用户需求，从收藏列表中挑选合适的途经场所，按时间顺序规划从「出发地点」到「终点」之间要去的地方。
            返回严格 JSON 格式，不要有其他文字：
            {
              "itinerary": [
                {
                  "placeName": "场所名称（必须来自收藏列表）",
                  "activity": "在这里做什么",
                  "estimatedDuration": 90,
                  "arrivalTime": "12:30"
                }
              ],
              "summary": "一段话描述整个行程安排"
            }

            注意：
            1. itinerary 只放途中要去的收藏场所，不要把出发地点和终点写进去
            2. 只从收藏列表中选择途经点，不要编造不存在的场所
            3. 按用户描述的时间顺序安排（如中午、下午、晚上）
            4. estimatedDuration 单位为分钟，arrivalTime 为预计到达时间（24小时制 HH:mm）
        """.trimIndent()

        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-6")
            put("max_tokens", 600)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }.toString()

        return try {
            val request = Request.Builder()
                .url(API_URL)
                .header("x-api-key", API_KEY)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            val text = json.getJSONArray("content").getJSONObject(0).getString("text")
            Log.d(TAG, "行程规划结果: $text")

            val cleanText = text.trim()
                .removePrefix("```json").removePrefix("```")
                .trimStart()
                .let { if (it.endsWith("```")) it.dropLast(3).trimEnd() else it }
            val result = JSONObject(cleanText)
            val items = result.getJSONArray("itinerary")
            val steps = (0 until items.length()).map {
                val item = items.getJSONObject(it)
                val name = item.getString("placeName")
                // 名称匹配：精确匹配优先，失败则尝试子串匹配（应对 Claude 输出名称略有出入）
                val matched = savedPlaces.firstOrNull { it.name == name }
                    ?: savedPlaces.firstOrNull { it.name.contains(name) || name.contains(it.name) }
                Log.d(
                    TAG,
                    "步骤 \"$name\" → " +
                        (matched?.let { "匹配[${it.name}] coords=${it.lat},${it.lng}" } ?: "未匹配收藏")
                )
                ItineraryStep(
                    placeName = name,
                    activity = item.getString("activity"),
                    estimatedDuration = item.getInt("estimatedDuration"),
                    arrivalTime = item.getString("arrivalTime"),
                    place = matched
                )
            }

            // 计算路线：出发地 → 各途经点 → 终点
            val destinations = steps.mapNotNull { it.place }.toMutableList()
            if (homeLat != 0.0 || homeLng != 0.0) {
                destinations.add(
                    SavedPlace(
                        name = endName,
                        category = com.floatagent.model.Category.OTHER,
                        lat = homeLat,
                        lng = homeLng
                    )
                )
            }
            val routes = GaodeSearcher.planRoute(currentLat, currentLng, destinations)

            ItineraryResult(
                steps = steps,
                routes = routes,
                summary = result.optString("summary")
            )
        } catch (e: Exception) {
            Log.e(TAG, "行程规划失败: ${e.message}")
            ItineraryResult(emptyList(), emptyList(), "规划失败：${e.message}")
        }
    }
}

data class ItineraryStep(
    val placeName: String,
    val activity: String,
    val estimatedDuration: Int,
    val arrivalTime: String,
    val place: SavedPlace?
)

data class ItineraryResult(
    val steps: List<ItineraryStep>,
    val routes: List<com.floatagent.analyzer.RouteSegment>,
    val summary: String
)
