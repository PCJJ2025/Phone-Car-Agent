package com.floatagent.sync

/** 单个途经点，与现有 routeStopsJson 的 {name,lat,lng,kind} 一一对应 */
data class TripStop(
    val name: String,
    val lat: Double,
    val lng: Double,
    val kind: String   // start | waypoint | end
)

/** 手机↔车机之间流转的一条行程（Firestore 文档 trips/{pairCode}） */
data class Trip(
    val stops: List<TripStop>,
    val currentIndex: Int,   // 逐点推进指针：车机当前正前往的点
    val version: Long,       // 手机每次发送都刷新，车机据此判断行程是否更新
    val status: String       // active | done
)
