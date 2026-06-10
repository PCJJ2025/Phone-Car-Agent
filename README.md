# 版本更新：较之前上传的 Android Agent那个项目
这个项目增加了车机功能，在手机上规划路线，在车载设备上的“车机端”执行路线，并通过云端实时同步行程进度。
车机端：接收完整路线、逐点推进、回写进度
这次更新的重点，是把路线规划真正打通到“车上执行”这最后一公里。

### 1. 完整路线可以整体发送到车机
之前导航更像是“一次只处理一个终点”。现在手机上排好的多点路线会作为一个整体上传，车机端一次性拿到起点、途经点和终点，不需要每到一站再重新输入下一站。

### 2. 车机端支持逐点推进
车机页面会高亮当前正前往的点。到达后点击“已到达”，系统会自动推进到下一个目标点，整条路线可以按顺序执行下去。

### 3. 手机和车机进度双向实时同步
车机推进到第几个点，手机端可以看到；手机重新发送路线后，车机端也会感知到更新。两端围绕同一份云端行程状态协同工作。

一句话总结：  
之前它主要是一个在手机上出路线结果的工具；现在它已经具备“手机规划、车上执行、进度同步”的闭环能力。

## 工作方式

当前是原型阶段，用两台 Android 设备模拟“手机 -> 车机”，中间通过 Firebase Firestore 做路线中转。

```text
手机端 (ItineraryActivity)              Firebase Firestore              车机端 (CarReceiverActivity)
规划路线 -> 生成 6 位配对码      -->    trips/{pairCode}        -->    实时监听并展示完整路线
                                         { stops[], currentIndex,       高亮当前点
                                           version, status }     <--    点击“已到达”后推进 currentIndex
```

同一个 APK 装在两台设备上，通过首页两个入口区分角色：
- `规划今日行程`：手机端入口
- `车机模式`：车机端入口

## 云端数据契约

Firestore 中每条行程使用 `trips/{pairCode}` 表示，核心字段如下：

| 字段 | 含义 |
| --- | --- |
| `stops` | 有序途经点列表，格式为 `[{name, lat, lng, kind}]` |
| `currentIndex` | 当前推进到第几个点 |
| `version` | 手机端每次重新发送行程时刷新 |
| `status` | 行程状态，`active` / `done` |

这层契约稳定后，将来如果接入真实车机生态，例如 HiCar 或车厂 SDK，只需要替换“车机端消费逻辑”，手机端和云端结构可以继续复用。

## 关键文件

- `app/src/main/java/com/floatagent/ItineraryActivity.kt`
  手机端发送路线到云端
- `app/src/main/java/com/floatagent/CarReceiverActivity.kt`
  车机端接收路线、展示地图、推进进度
- `app/src/main/java/com/floatagent/sync/Trip.kt`
  手机和车机共享的行程数据模型
- `app/src/main/java/com/floatagent/sync/TripRepository.kt`
  Firestore 上传、监听和推进逻辑
- `app/src/main/res/layout/activity_car_receiver.xml`
  车机模式页面
- `docs/phone-car-sync.md`
  更详细的原型说明和维护备忘

## 快速开始

### 环境

- Android Studio
- JDK 17
- 两台 Android 设备，或两套可独立运行 APK 的 Android 环境

### Firebase

项目当前使用 Firebase Firestore 作为手机和车机之间的路线中转层。

已接入内容：
- `app/google-services.json`
- Firestore
- Anonymous Auth

如果你要切换到自己的 Firebase 项目：
1. 在 Firebase 控制台创建 Android App，包名使用 `com.floatagent`
2. 替换 `app/google-services.json`
3. 启用 Firestore
4. 启用 Authentication 中的匿名登录

建议的 Firestore 规则：

```text
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /trips/{code} {
      allow read, write: if request.auth != null;
    }
  }
}
```

注意：当前测试模式规则会在 `2026-07-10` 到期，到期后如果不修改规则，原型将无法继续读写。

### 运行原型

1. 编译并安装 APK 到两台设备
2. A 设备进入 `规划今日行程`
3. 规划好路线后点击“发送到车机 🚗”
4. 记录弹出的 6 位配对码
5. B 设备进入 `车机模式`
6. 输入配对码并连接
7. 车机端看到完整路线后，可通过“已到达，下一个”逐点推进

## 当前边界

- 到达判定还是手动按钮，不是 GPS 自动触发
- 配对方式还是 6 位明文配对码，正式版需要更安全的绑定方式
- 当前依赖 Firebase，国内网络环境下稳定性和可达性需要评估
- 车机端目前是 Android 原型页面，还没有直接接入真实车机系统

## 后续演进方向

- 把“已到达”替换为 GPS / 地理围栏自动推进
- 接入真实车机能力，如 HiCar 或 OEM SDK
- 强化账号体系和设备绑定，替代明文配对码
- 处理不同地图或导航 SDK 的坐标系转换问题

## 补充说明

这个版本的重点不是“做一个完整量产方案”，而是先验证一件关键事情：  
路线不只是在手机上被规划出来，而且可以稳定地流转到车端，并随着驾驶进度持续推进和同步。
