package com.floatagent.sync

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机↔车机的路线云端中转（Firestore）。
 *
 * - 手机端：[uploadTrip] 把规划好的路线写到 trips/{pairCode}
 * - 车机端：[listenTrip] 实时监听该文档；[advance] 推进 currentIndex
 *
 * 将来对接真车 HiCar/OEM 时，只需替换车机端消费逻辑，这里的数据契约不动。
 */
object TripRepository {

    private val db get() = FirebaseFirestore.getInstance()
    private val auth get() = FirebaseAuth.getInstance()
    private fun trips() = db.collection("trips")

    /** 匿名登录（Firestore 安全规则通常要求已登录）。幂等，可重复调用。 */
    suspend fun ensureSignedIn() {
        if (auth.currentUser == null) auth.signInAnonymously().await()
    }

    /** 生成 6 位配对码（车机端据此连接）。 */
    fun newPairCode(): String = (100000..999999).random().toString()

    /** 手机端：上传/覆盖一条行程。stopsJson 为现有 [{name,lat,lng,kind}] 格式。 */
    suspend fun uploadTrip(pairCode: String, stopsJson: String) {
        ensureSignedIn()
        val now = System.currentTimeMillis()
        val data = hashMapOf(
            "stops" to parseStops(stopsJson),
            "currentIndex" to 0L,
            "version" to now,    // 用时间戳当版本号：手机每次发送都更新
            "status" to "active",
            "updatedAt" to now
        )
        trips().document(pairCode).set(data).await()
    }

    /** 车机端：实时监听一条行程。返回可取消的注册句柄（在 onDestroy 里 remove）。 */
    fun listenTrip(
        pairCode: String,
        onUpdate: (Trip?) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration =
        trips().document(pairCode).addSnapshotListener { snap, e ->
            if (e != null) { onError(e); return@addSnapshotListener }
            onUpdate(snap?.let(::fromSnapshot))
        }

    /** 车机端：推进到下一个点；到末点则标记完成。 */
    suspend fun advance(pairCode: String, newIndex: Int, done: Boolean) {
        ensureSignedIn()
        trips().document(pairCode).update(
            mapOf(
                "currentIndex" to newIndex.toLong(),
                "status" to if (done) "done" else "active",
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    /** 把 Trip 的 stops 还原成地图渲染需要的 JSON。 */
    fun stopsToJson(stops: List<TripStop>): String {
        val arr = JSONArray()
        stops.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name).put("lat", it.lat)
                    .put("lng", it.lng).put("kind", it.kind)
            )
        }
        return arr.toString()
    }

    // ---- 序列化 ----

    private fun parseStops(json: String): List<Map<String, Any>> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            mapOf(
                "name" to o.optString("name"),
                "lat" to o.optDouble("lat"),
                "lng" to o.optDouble("lng"),
                "kind" to o.optString("kind", "waypoint")
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fromSnapshot(snap: DocumentSnapshot): Trip? {
        if (!snap.exists()) return null
        val rawStops = snap.get("stops") as? List<Map<String, Any?>> ?: emptyList()
        val stops = rawStops.map {
            TripStop(
                name = it["name"] as? String ?: "",
                lat = (it["lat"] as? Number)?.toDouble() ?: 0.0,
                lng = (it["lng"] as? Number)?.toDouble() ?: 0.0,
                kind = it["kind"] as? String ?: "waypoint"
            )
        }
        return Trip(
            stops = stops,
            currentIndex = (snap.getLong("currentIndex") ?: 0L).toInt(),
            version = snap.getLong("version") ?: 0L,
            status = snap.getString("status") ?: "active"
        )
    }
}
