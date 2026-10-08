# VideoShrink v0.2

安卓本机批量视频压缩工具（原型）。

## 已实现

- 本机处理，不上传视频
- 系统文件选择器一次选择多个视频
- H.264 / H.265（HEVC）
- 1080P / 720P / 480P 三档目标分辨率
- 省空间 / 均衡 / 高画质 三档码率
- 顺序批量队列 + 单视频实时进度
- 压缩结果直接写入系统 `Movies/VideoShrink`，避免二次复制
- 前台 `mediaProcessing` 服务：退到后台或锁屏后继续批量压缩
- CPU 唤醒锁：编码期间防止深度休眠中断
- 通知栏显示批次/单片进度并支持取消
- 任务列表状态持久化，重新打开 App 可看到上次结果
- 压缩完成后可通过 Android 系统确认框批量删除原视频
- 不会自动静默删除原片

## 环境

- Kotlin
- Android Gradle Plugin 8.13.2
- Kotlin 2.3.21
- compileSdk / targetSdk 36
- minSdk 29
- Media3 Transformer 1.11.1
- Java 17

## 构建 APK

仓库带有 `.github/workflows/build-apk.yml`。推送到 GitHub 后，Actions 会自动构建 debug APK，并上传名为 `VideoShrink-v0.2-debug-apk` 的 artifact。

也可以用 Android Studio 打开项目，等待 Gradle Sync 完成后运行 `assembleDebug`。
