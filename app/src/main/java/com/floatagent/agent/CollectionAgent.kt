package com.floatagent.agent

import android.content.Context
import android.util.Log
import com.floatagent.BuildConfig
import com.floatagent.analyzer.GaodeSearcher
import com.floatagent.model.Category
import com.floatagent.model.SavedPlace
import com.floatagent.model.ScreenData
import com.floatagent.service.ScreenCaptureService
import com.floatagent.storage.CollectionStorage
import com.floatagent.ui.FloatingResultCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object CollectionAgent {

    private const val TAG = "FloatAgent_Collection"
    private val API_KEY = BuildConfig.ANTHROPIC_API_KEY
    private const val API_URL = "https://api.anthropic.com/v1/messages"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private sealed class ExtractionResult {
        data class Success(
            val places: List<SavedPlace>,
            val reason: String,
            val usedVision: Boolean
        ) : ExtractionResult()

        data class Failure(
            val userMessage: String,
            val logReason: String
        ) : ExtractionResult()
    }

    fun collect(context: Context, screenData: ScreenData) {
        val textCount = screenData.allTexts.size
        Log.d(TAG, "开始收藏，屏幕文字数量: $textCount，包名: ${screenData.packageName}")
        if (textCount > 0) {
            Log.d(TAG, "前10条文字: ${screenData.allTexts.take(10)}")
        }

        if (textCount == 0) {
            FloatingResultCard.showMessage(context, "未能读取屏幕内容\n请确认已开启无障碍服务")
            return
        }

        FloatingResultCard.showLoading(context, "正在提取场所信息...")

        CoroutineScope(Dispatchers.IO).launch {
            val extraction = extractPlaces(context, screenData)

            withContext(Dispatchers.Main) {
                when (extraction) {
                    is ExtractionResult.Failure -> {
                        Log.w(TAG, "收藏失败: ${extraction.logReason}")
                        FloatingResultCard.showMessage(context, extraction.userMessage)
                    }

                    is ExtractionResult.Success -> {
                        val existingNames = CollectionStorage.getAll(context)
                            .map { normalizePlaceName(it.name) }
                            .toMutableSet()
                        val savedPlaces = mutableListOf<SavedPlace>()
                        val duplicateNames = mutableListOf<String>()

                        extraction.places.forEach { place ->
                            val normalizedName = normalizePlaceName(place.name)
                            if (normalizedName.isBlank() || normalizedName in existingNames) {
                                duplicateNames.add(place.name)
                                return@forEach
                            }

                            CollectionStorage.save(context, place)
                            existingNames.add(normalizedName)
                            savedPlaces.add(place)
                        }

                        when {
                            savedPlaces.isNotEmpty() -> {
                                Log.d(
                                    TAG,
                                    "收藏成功: ${savedPlaces.joinToString { it.name }}; " +
                                        "vision=${extraction.usedVision}; duplicates=${duplicateNames.size}"
                                )
                                FloatingResultCard.showCollectionSaved(
                                    context,
                                    savedPlaces,
                                    duplicateNames.distinct().size
                                )
                            }

                            duplicateNames.isNotEmpty() -> {
                                val distinctNames = duplicateNames.distinct()
                                val msg = if (distinctNames.size == 1) {
                                    "「${distinctNames.first()}」已在收藏中 ★"
                                } else {
                                    "${distinctNames.size} 个场所已在收藏中 ★"
                                }
                                Log.d(TAG, "全部重复收藏，跳过: $distinctNames")
                                FloatingResultCard.showMessage(context, msg)
                            }

                            else -> {
                                Log.w(TAG, "提取成功但没有有效结果: ${extraction.reason}")
                                FloatingResultCard.showMessage(context, "未找到明确的店名或场所信息")
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun extractPlaces(context: Context, screenData: ScreenData): ExtractionResult {
        val source = when (screenData.packageName) {
            "com.xingin.xhs" -> "小红书"
            "com.zhiliaoapp.musically" -> "抖音"
            "com.douyin.discover" -> "抖音"
            else -> "未知"
        }
        val visibleTexts = screenData.allTexts.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(120)
            .toList()
        val screenshotBase64 = ScreenCaptureService.captureScreen(context)

        val prompt = """
            以下是用户手机屏幕上的帖子内容，来自$source。
            注意：OCR 文字可能不完整，如果同时提供了截图，请优先以截图正文内容为准。

            屏幕文字：
            ${visibleTexts.joinToString("\n")}

            请提取帖子中提到的可收藏场所，支持单个或多个，返回严格 JSON，不要有其他文字：
            {
              "found": true,
              "reason": "一句话说明提取依据；如果没找到也说明原因",
              "places": [
                {
                  "name": "场所名称（餐厅/饮品店/spa店等，要完整准确）",
                  "category": "RESTAURANT 或 DRINKS 或 SPA 或 CAFE 或 OTHER",
                  "address": "地址（城市+街道，尽量完整，没有则空字符串）",
                  "items": ["推荐菜品或饮品或服务1", "推荐菜品2"],
                  "note": "一句话概括这家店的特色"
                }
              ]
            }

            规则：
            1. 如果是榜单、地图、合集帖子，把帖子明确提到的所有店铺/场所都写进 places（按帖子出现顺序），不要遗漏。
            2. 只提取明确的门店或场所，不要提取菜名、作者名、评论、城市标签。
            3. 如果没有明确店名或场所名，found 返回 false，places 返回空数组。
            4. 不要猜测图片外或屏幕外的信息。
        """.trimIndent()

        val content = JSONArray().apply {
            if (screenshotBase64 != null) {
                put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", "image/jpeg")
                        put("data", screenshotBase64)
                    })
                })
            }
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
        }

        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-6")
            put("max_tokens", 6000)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", content)
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
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "Claude 请求失败: ${response.code} $rawBody")
                return ExtractionResult.Failure(
                    userMessage = "场所提取服务暂时不可用，请稍后重试",
                    logReason = "http_${response.code}"
                )
            }

            val json = JSONObject(if (rawBody.isBlank()) "{}" else rawBody)
            val text = json.optJSONArray("content")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()

            if (text.isBlank()) {
                Log.e(TAG, "Claude 返回空内容: $rawBody")
                return ExtractionResult.Failure(
                    userMessage = "场所提取结果为空，请重试",
                    logReason = "empty_response_body"
                )
            }

            Log.d(TAG, "Claude 提取结果: $text")

            val result = JSONObject(cleanJsonText(text))
            val reason = result.optString("reason")
            val places = parsePlaces(result, source).map(GaodeSearcher::searchCoordinate)

            if (!result.optBoolean("found", false) && places.isEmpty()) {
                return ExtractionResult.Failure(
                    userMessage = "未找到明确的店名或场所信息",
                    logReason = "model_found_false: $reason"
                )
            }

            if (places.isEmpty()) {
                return ExtractionResult.Failure(
                    userMessage = "场所提取结果不完整，请重试",
                    logReason = "no_valid_places: $reason"
                )
            }

            ExtractionResult.Success(
                places = places,
                reason = reason,
                usedVision = screenshotBase64 != null
            )
        } catch (e: Exception) {
            Log.e(TAG, "提取失败: ${e.message}")
            ExtractionResult.Failure(
                userMessage = "场所提取失败，请检查网络后重试",
                logReason = e.message ?: e.javaClass.simpleName
            )
        }
    }

    private fun parsePlaces(result: JSONObject, source: String): List<SavedPlace> {
        val placesArray = result.optJSONArray("places") ?: JSONArray().apply {
            if (result.optString("name").isNotBlank()) put(result)
        }

        return (0 until placesArray.length()).mapNotNull { index ->
            val item = placesArray.optJSONObject(index) ?: return@mapNotNull null
            val name = cleanPlaceName(item.optString("name"))
            if (name.isBlank()) return@mapNotNull null

            val items = item.optJSONArray("items")?.let { arr ->
                (0 until arr.length())
                    .mapNotNull { arr.optString(it).trim().takeIf(String::isNotBlank) }
                    .distinct()
                    .take(5)
            } ?: emptyList()

            SavedPlace(
                name = name,
                category = Category.fromLabel(item.optString("category", "OTHER")),
                address = item.optString("address").trim(),
                items = items,
                note = item.optString("note").trim(),
                source = source
            )
        }.distinctBy { normalizePlaceName(it.name) }
    }

    private fun cleanJsonText(text: String): String =
        text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .trimStart()
            .let { if (it.endsWith("```")) it.dropLast(3).trimEnd() else it }

    private fun cleanPlaceName(name: String): String =
        name.trim()
            .replace(Regex("^(?:#?\\d+|[０-９]+|[①-⑩])(?:[.、:：\\-\\s]+)?"), "")
            .trim()

    private fun normalizePlaceName(name: String): String =
        cleanPlaceName(name).lowercase().replace(Regex("\\s+"), "")
}
