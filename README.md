# SplitPlay

> Android 本地音视频双路播放器

一款专注于**本地媒体**的播放器,差异点是音频与视频可以走两条独立的播放/输出链路 —— 让用户自己决定音频从哪里出、视频在哪里显示。

## 主要特性

- 🎵 **音视频分离路径**:同一文件可由两个独立 Player 承载,音频/视频互不干扰
- 📺 **本地视频画廊**:基于 MediaStore,封面缩略图秒开
- 🎬 **流畅播放体验**:全屏手势(下滑关闭 / 多击 ±10s / 边缘长按 2× / 双击切全屏)、画面比例自适应、续播位置自动记忆、画中画
- 🎥 **相机录制**:1080p 拍摄、点按对焦、曝光调节、自动 EIS 防抖、单 pass GL 美颜、轻触 / 长按双录制模式
- ⚙️ **可插拔播放引擎**:ExoPlayer / IjkPlayer / 自定义,UI 一键切换
- 💾 **网络流式缓存**:HTTP 播放边下边播,无需本地代理

## 平台要求

- Android 7.0(API 24)及以上
- 应用 ID:`com.abei.splitplay`

## 构建

```bash
# Windows
.\gradlew.bat :app:assembleDebug

# Unix / macOS
./gradlew :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/`。

工具链:AGP 9.2.1 · Kotlin 2.1.0 · JVM 11 · compileSdk 36。

## 项目结构

```
:app          Compose 主入口、画廊、相机录制
:player-ui    可复用的 Compose 视频播放器组件
:media        播放引擎抽象 + ExoPlayer 实现
:core         DataStore 持久化(续播位置、用户偏好)
```

## License

详见 [LICENSE](./LICENSE)。
