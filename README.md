# DualCamPiP

后置摄像头全屏、前置摄像头画中画，合成**一个** H.264 + AAC 的 MP4，保存到本机相册。界面是简体中文。

这不是“所有 Android 手机都能用”的双摄录像机。同时打开前后摄取决于厂商是否实现 Android 11 的并发相机能力，很多机型做不到。做不到时应用会停掉双摄录制并说明原因，而不是崩溃或假装录到了两路画面。

优先考虑的机型是 Pixel、三星旗舰，以及部分小米旗舰。没有在这些机器上逐台验过的版本，不应写成“已支持”。API 界限、探测方式和不支持时的界面见 [docs/feasibility.md](docs/feasibility.md)。

## 环境

| 项 | 值 |
| --- | --- |
| 语言 | Kotlin |
| Android Gradle Plugin | 9.5.1（自带 Kotlin 2.2.10） |
| Gradle | 9.6.0 |
| compileSdk / targetSdk | 37（Android 17） |
| minSdk | 30（Android 11） |
| CameraX | 1.6.2 |
| JDK | 17 或更高（本仓库在 JDK 21 上编译） |

### 为什么 minSdk 是 30

`CameraManager.getConcurrentCameraIds()` 从 API 30 才是平台契约。CameraX 的 `ConcurrentCamera` 也建立在这套并发相机会话上。更低版本里前后摄同开只是厂商私有行为，没有稳定的查询接口，所以不在支持范围内。

## 用 Android Studio 构建

1. 安装当前稳定版 Android Studio（需能使用 AGP 9.5 与 Android SDK 37）。
2. 用 Android Studio 打开本目录，让它生成 `local.properties` 里的 `sdk.dir`。
3. 安装 SDK Platform 37 和 Build-Tools 36（AGP 9.5 默认构建工具）。
4. 运行 `app`，或在终端执行：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

调试包：`app/build/outputs/apk/debug/app-debug.apk`。

## 怎么用

1. 授予相机和麦克风。
2. 应用检测这台设备能否同时打开前置和后置。
3. 可以的话，取景框里是后置全屏加前置画中画。取景框外的按钮不会进成片；框内的构图和成片一致。
4. 点红色按钮开始，再点一次停止。文件写入 `Movies/DualCamPiP/`，文件名类似 `DualCam_20261107_152830_128.mp4`。
5. “画中画”里可以改角落、宽度（默认约为画面宽度的 25%）和圆角。录制过程中不能改。

视频只通过 MediaStore 写在本机。应用没有网络权限，也不会上传。

## 架构

- `camera/DualCameraSession`：运行时探测并发能力。先用 CameraX `ConcurrentCamera` 各绑一路 `Preview`；绑定失败或设备只在 Camera2 里报告了前后摄组合时，改用 Camera2 同时 `openCamera`。两种都不行就只开后置预览，并禁用录制。
- `render/GlCompositor`：同一套着色器把后置画满、前置画成圆角画中画。预览 Surface 和编码器 Surface 共用几何计算（`render/PipGeometry`、`render/FrameTransform`）。
- `encode/CompositeRecorder`：MediaCodec 把合成画面编成 H.264，麦克风编成 AAC，再复用进一个 MP4。
- `gallery/GallerySaver`：以 `IS_PENDING` 写入 MediaStore，成功后再发布。失败则删除这条未完成记录。
- `ui`：Compose 取景、权限和画中画设置。Activity 只负责窗口。

成片固定为竖屏 720×1280、约 4 Mbps、30 fps、单声道 AAC。并发相机会话在 CameraX 文档里的上限就是 720p 这一档，所以没有追 1080p。

## 已知限制

- 不能在录制中对调前后摄，也不能在录制中改画中画位置、大小或圆角。
- 离开界面会停止并尽量保存。从最近任务里直接划掉进程，可能来不及写完文件。
- 没有前台服务。录制跟当前界面走。
- 前置画面按预览习惯做了镜像，成片和取景一致。
- 手机方向锁在竖屏。Android 17 在大屏上可能忽略这个锁，布局仍把成片框保持为 9:16。
- 音频和视频时间戳都从录制开始对齐，长录像的漂移没有单独校正。
- 单元测试覆盖的是画中画几何、纹理坐标、并发相机配对和文件名，不是真机出图。

## 真机记录

下面留给实际拿手机跑过的人填。空着就表示还没验。

| 设备 | 系统 | 前后摄同开 | 成片 | 备注 |
| --- | --- | --- | --- | --- |
|  |  |  |  |  |

看日志：`adb logcat -s DualCamPiP`

## 这次不做

录制中切换画中画摄像头、录制中改布局、滤镜、云同步、iOS。
