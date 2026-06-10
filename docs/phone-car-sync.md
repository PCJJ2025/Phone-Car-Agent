# 手机 ↔ 车机 路线流转（原型）

在手机上规划好路线后，通过云端中转把**完整路线**发送到「车机」端，车机展示路线并**逐点推进**，进度双向实时同步。

当前为原型阶段：用两台 Android 设备模拟「手机 → 车机」，云端用 Firebase Firestore，到达判定为**手动按钮**。

---

## 架构

```
手机端 (ItineraryActivity)              Firebase Firestore              车机端 (CarReceiverActivity)
规划路线 → 生成 6 位配对码      ──上传──>   trips/{pairCode}      ──实时监听──>  展示完整路线 + 高亮当前点
                                         { stops[], currentIndex,                「已到达」→ currentIndex+1
                                           version, status }       <──写回────   （进度回流，手机端可见）
```

同一个 APK 装在两台设备上，靠 MainActivity 的两个入口区分角色：
- **规划今日行程** → 手机端
- **车机模式** → 车机端

### 云端数据契约 `trips/{pairCode}`
| 字段 | 含义 |
|---|---|
| `stops` | 有序途经点 `[{name, lat, lng, kind}]`，kind = start/waypoint/end |
| `currentIndex` | 逐点推进指针：车机当前正前往的点 |
| `version` | 手机每次发送刷新（时间戳），车机据此判断行程是否更新 |
| `status` | `active` / `done` |

> 这套契约定稳后，将来对接真车 HiCar/OEM 只需替换**车机端消费逻辑**，手机端与云端不动。

---

## 相关文件
- 共享层：`sync/Trip.kt`、`sync/TripRepository.kt`（上传 / 监听 / 推进）
- 手机端：`ItineraryActivity.kt`（「发送到车机」按钮 → 生成配对码 → 上传）
- 车机端：`CarReceiverActivity.kt` + `res/layout/activity_car_receiver.xml`
- 入口：`MainActivity.kt`（「车机模式」按钮）
- 构建：`build.gradle` / `app/build.gradle`（google-services + Firebase BoM 33.1.2）

---

## Firebase 配置（已完成 / 维护备忘）
1. 控制台建项目 `phone-car`，加 Android App（包名 `com.floatagent`），`google-services.json` 放在 `app/`。
2. **Firestore**：测试模式启动。
3. **Authentication → 匿名登录**：已启用（代码用 `signInAnonymously()`）。

⚠️ **测试模式规则 2026-07-10 到期**，之后会全部拒绝读写、原型连不上。到期前把规则改为：
```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /trips/{code} {
      allow read, write: if request.auth != null;
    }
  }
}
```

> Firebase BoM 锁 33.1.2：34.x 用 Kotlin 2.x 编译，与本项目 Kotlin 1.9.23 不兼容。升级项目 Kotlin 后可再升 BoM。

---

## 测试步骤（两台设备）
1. 两台都安装 debug APK。
2. A 机：规划今日行程 → 「发送到车机 🚗」→ 记下弹窗里的 6 位配对码。
3. B 机：车机模式 🚗 → 输入配对码 → 连接 → 看到完整路线，第一个点高亮。
4. B 机点「已到达，下一个」→ 高亮跳到下一点，A/B 进度实时同步；到终点显示「行程已完成」。

---

## 已知边界（原型够用，正式需补）
- **坐标系**：全程高德 GCJ-02，车机端也用高德渲染，无偏移；真车若用其他导航需做坐标转换（参考 `analyzer/GaodeSearcher.kt`）。
- **到达判定**：当前为手动按钮；真车应换成 GPS 地理围栏自动推进。
- **配对**：6 位明文配对码 + 匿名登录；正式版应改为账号绑定 / 不可猜测 ID。
- **国内网络**：Firebase 服务器在境外，若改在国内测试建议换腾讯云 CloudBase / LeanCloud。
