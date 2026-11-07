# DualCamPiP 可行性说明

结论：前后摄同时预览并合成一个 MP4，在 **Android 11（API 30）及以上、且厂商真正开放了前后摄并发** 的旗舰上可行。这不是全机型能力。查不到或打不开并发组合时，界面用中文说明原因，只保留后置预览，**双摄录制按钮保持禁用**。不会用一路画面冒充双摄。

## API 实际能做什么

平台从 Android 11 起才提供并发相机查询：`CameraManager.getConcurrentCameraIds()`。CameraX 的 `ConcurrentCamera`（本仓库用 1.6.2）建立在这之上。

- 查询：`ProcessCameraProvider.availableConcurrentCameraInfos`。每个子列表是一组可以同时开的相机。里面经常是两颗后置（主摄 + 超广角），**不能**把“有并发列表”当成“能前后同开”。
- 打开：`bindToLifecycle(List<SingleCameraConfig>)`，每路一个独立的 `Preview`。CameraX 写明并发会话分辨率上限大约是 720p，所以成片定为竖屏 720×1280，而不是 1080p。
- 合成：不用系统多轨，也不用 CameraX 的 `CompositionSettings` 去拼画面。两路预览进 `GlCompositor`，预览和 `MediaCodec` 编码器共用同一套画中画几何，再和 AAC 复用成一个 MP4。

Camera2 是后备，不是第二条产品路径。只有 CameraX 没有前后组合，或 `bindToLifecycle` / 相机会话报错时，才在 `getConcurrentCameraIds()` 确认存在一前一后的前提下，同时 `openCamera`。会话 `onConfigureFailed`、设备 `onError` 都算失败，接着走不支持界面。

`android.hardware.camera.concurrent` 在清单里是 `required="false"`。不支持的手机也能安装，安装之后再探测，而不是在商店里假装所有机型可用。

## 系统与机型界限

| 范围 | 判断 |
| --- | --- |
| minSdk 30 | API 30 以下没有稳定的并发查询，不做 |
| 优先机型 | Pixel、三星旗舰、部分小米旗舰 |
| 不承诺 | 中低端、旧系统、以及任何未实测的旗舰 |
| 并发上限 | 约 720p；同时开两路可能更热、帧率更低 |

Android 17 在大屏上可能忽略竖屏锁定。成片框仍是 9:16。这不增加双摄可用机型。

即便是上述旗舰，也要看具体系统版本和相机 HAL。**旗舰 ≠ 一定支持前后同开。**

## 运行时探测

授权相机之后、真正 `open` 之前：

1. 在 CameraX 并发组里找 `LENS_FACING_BACK` + `LENS_FACING_FRONT`，较小的组优先（专门的前后对，而不是更大的多摄集合）。
2. 用 Camera2 的 `concurrentCameraIds` 和 `LENS_FACING` 做同样的配对。
3. 两边都没有前后组合：后置预览 + 文案「这台设备没有报告可同时打开的前置与后置摄像头」，录制禁用。
4. 有 CameraX 组合就先绑定。绑定抛错或之后 `CameraState` 报错，再试 Camera2。
5. Camera2 打开或配流失败：文案「无法同时打开前置与后置摄像头」，同样只留后置预览。
6. 两路都绑定成功后，还要等前后预览都出第一帧，录制按钮才可用。避免只收到一路就写成“双摄文件”。

日志标签：`adb logcat -s DualCamPiP`。

## 建议真机

下表是建议的验证机，不是已通过名单。仓库 README 里的实测表在有人跑过之前保持空白。

| 优先拿来测 | 看什么 |
| --- | --- |
| Pixel 8 / 8 Pro、Pixel 9 / 9 Pro（或当前 Tensor 旗舰），Android 15–17 | CameraX 并发路径、预览与成片是否一致 |
| 三星 Galaxy S24 / S25 Ultra（或当前 S 系列旗舰） | CameraX 失败时 Camera2 后备能否打开 |
| 小米 14 / 15 Ultra（或同代旗舰） | 并发列表里是不是只有后置多摄 |

每台至少看三件事：探测结果、取景框里的画中画、相册里的单个 MP4 是否同时有后置全屏和前置小窗。

## 不支持时的界面

不崩溃，不录制。标题是「无法双摄同录」。说明写明双摄已停用，避免生成只有一路画面的视频；后置预览可以继续看。状态行是「仅后置预览」。红色录制按钮保持不可用。没有第二路假画面，也不会把后置复制进画中画再当成前置。

本环境没有双摄真机。`assembleDebug` 和单元测试只证明工程能编过、几何和配对逻辑正确，**不能**当成机型支持证明。
