# SplitPlay —— Android 本地音视频双路播放器

`com.abei.splitplay` —— 一款面向**本地媒体重度用户**的 Android 播放器,核心差异点是**音频与视频走两条互不干扰的播放/输出链路**:同一文件可由两个独立 Player 承载,音频路由到指定输出设备(扬声器 / 蓝牙 / USB DAC),视频送到指定 Display(主屏 / 外接屏 / HDMI),并对硬解 / 软解策略做透明展示与手动控制。

> 完整产品需求与设计文档:[`docs/PRD.md`](./docs/PRD.md) · [`docs/DESIGN.md`](./docs/DESIGN.md) · [`docs/ROADMAP.md`](./docs/ROADMAP.md)
> v2(视频语言学习模式)规划:[`docs/PRD-v2.md`](./docs/PRD-v2.md)

---

## 当前进度

按 v1 路线图(M0~M9)粗略对应:

- **已落地**:M0 多模块脚手架 + M1 单 Player 视频 MVP(ExoPlayer);M2 / M5 / M9 提前部分完成 —— 引擎层重构、`CacheDataSource` 流式缓存、画中画(PIP)
- **路线外已交付**:MediaStore 视频画廊、CameraX 录像器(1080p ViewPort + 点按对焦 + 曝光滑块 + 自动 EIS + GL 美颜 + TAP/HOLD 录制模式)、设置弹窗(引擎选择 + 服务器 URL)、完整手势播放器(下滑关闭、多击 ±10s、边缘长按 2×)、全屏画面比例覆写、续播位置持久化
- **待办**:Audio/Video 双引擎拆分、`:native` FFmpeg 软解模块、音频路由 / 外接屏路由 UI、能力探测矩阵

`docs/` 是权威规范,但**与代码部分脱节** —— 有歧义时以代码为准。`ROADMAP.md` 末尾的「实施快照」与 `DESIGN.md` 的「v1.1 实施偏离」记录了偏差。

---

## 模块架构

四个 Gradle 模块(根项目名 `SplitPlay`):

| 模块 | 角色 |
|---|---|
| `:media` | 播放引擎核心。`PlayerEngine` 接口(**不暴露 Media3 `Player`**,仅 `setVideoSurface(Surface?)`)+ 不可变 `PlaybackState`;`SinglePlayerEngine` 是 Media3 实现;`PlayerEngineFactory` + `Registry` 支持 `EXOPLAYER` / `IJK` / `CUSTOM` 三类;`MediaCache` 单例提供 `SimpleCache` + `CacheDataSource` 渐进式 HTTP 缓存 |
| `:core` | 领域 + 持久化。DataStore 实现的 `ResumeStore`(URI → 续播位置)与 `EnginePrefs`(引擎选型 + 服务器地址)。无 Media3 依赖 |
| `:player-ui` | Compose 视频播放器 UI(`PlayerScreen` / `PlayerViewModel` / `PlayerSettings`),独立 Android library,可被其他 app 直接复用 |
| `:app` | Compose 根:`MainActivity` / `AppNavHost` / 视频画廊 / CameraX 录像器(`BeautyEffect` —— EGL14 + OES 单 pass shader 美颜管线) |

> 依赖只在 `gradle/libs.versions.toml` 集中管理,通过 `libs.*` alias 引用。**注意**:AGP 9.x 自带 Kotlin 支持,**不要 apply `org.jetbrains.kotlin.android`** —— 会与自动注册的 `kotlin` 扩展冲突。

**IjkPlayer 状态**:`IjkPlayerEngineFactory.create()` 当前抛 `NotImplementedError`(bilibili maven 不稳定,依赖已注释)。选择该引擎时 `PlayerViewModel` 会回退到 ExoPlayer 跑完本次会话,但**不会**改写用户偏好。

---

## 构建与测试

Gradle wrapper;Windows 用 `.\gradlew.bat`。

```bash
./gradlew :app:assembleDebug            # 构建 debug APK
./gradlew test                           # 全部单元测试
./gradlew testDebugUnitTest              # debug variant 单元测试
./gradlew testDebugUnitTest --tests "com.abei.splitplay.SomeTest"   # 指定测试类/方法
./gradlew connectedDebugAndroidTest      # 仪器测试(需连设备/模拟器)
./gradlew lint
```

**工具链**:AGP 9.2.1 · Kotlin 2.1.0 · JVM target 11 · minSdk 24 · targetSdk 36 · compileSdk 36.1。

---

## 关键约定(开发前请读)

- **状态流**:`PlayerEngine.state: StateFlow<PlaybackState>`。位置不会自推,`SinglePlayerEngine` 用 250ms 协程轮询 —— 扩展引擎时务必保留该机制。
- **MVVM**:ViewModel 是 `AndroidViewModel`,对外暴露只读 `StateFlow<XxxUiState>`;Composable 全部无状态,通过事件 lambda 上抛,导航决策放在 `AppNavHost.kt`。
- **视频输出**:绝不重新引入 `PlayerSurface(player)` —— 会重新耦合到 Media3 并破坏 IjkPlayer/Custom 路径。统一通过 `EngineSurface` + `engine.setVideoSurface(...)`。
- **画面比例**:详情页固定 `FIT_AUTO`;全屏读 `state.fitMode`,用 `fitModifier(mode, w, h)` 传给 `EngineSurface`,不要给 `fillMaxSize()`(会拉伸)。
- **SAF 权限**:`takePersistableUriPermission` 只能在 `ACTION_OPEN_DOCUMENT` URI 上调用(用 `DocumentsContract.isDocumentUri` 守门),MediaStore / PhotoPicker URI 会抛 `SecurityException`。
- **导航转场**:`Routes.PLAYER` 关闭了进入/退出动画 —— 默认 700ms 淡入会让 SurfaceView 闪一下,只要路由内含 SurfaceView 都应关掉。
- **相机美颜生命周期**:`BeautyEffect` 持有 EGL context + GL 线程,必须 `remember` 一次,`DisposableEffect.onDispose { release() }` 释放;不要按 `useFrontCamera` 重建,否则泄漏 EGL。
- **相机分辨率**:必须在 `Preview` 上挂 `ResolutionSelector(1080×1920, FALLBACK_CLOSEST_HIGHER_THEN_LOWER)` + 9:16 `ViewPort`,Preview 与 VideoCapture 共享一份裁剪流(所见即所录)。EIS 是设备相关能力,要先用 `getPreviewCapabilities` / `getVideoCapabilities` 查询再开,否则 `bindToLifecycle` 抛 `IllegalArgumentException`(常见于前置相机)。
- **录制中切换前后置已被故意禁用**:`LaunchedEffect(useFrontCamera)` 会 `unbindAll()` 进而 finalize `Recording`。翻转按钮 `enabled = recording == null`。如果将来要做,正路是 `Recording.stop()` → 起新文件 → `MediaMuxer` 拼接。
- 代码风格:显式 import、4 空格缩进、尾随逗号。部分 UI 文案为中文。

---

## 明确不做(out of scope,见 PRD §6)

网络流媒体(HLS/DASH/RTMP/RTSP) · 无线投屏(Cast/DLNA/Miracast/AirPlay) · DRM · 云盘/WebDAV · 转码/剪辑/导出 · 直播推流。
