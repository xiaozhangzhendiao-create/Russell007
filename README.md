# 实时字幕 Android App（英/日 → 中文）

网页版同款引擎（Vosk 流式识别 + OPUS-MT 翻译）的 Android 混合封装：
原生内录其他 App 的媒体音频（MediaProjection），悬浮透明 WebView 出中文字幕。

## 打包流程（GitHub Actions 自动出 APK）

1. 在 GitHub 上新建**空仓库**（不要勾 README/.gitignore）
2. 本地推送本目录（见下方命令）
3. 打开仓库 → Actions 页 → 等 "Build APK" 跑绿 → 下载 artifact `live-subtitles-apk`
4. 手机安装 APK → 按启动页提示授权（悬浮窗 + 屏幕录制）→ 打开视频 App 播放即出字幕

## 首次使用

模型不打包进 APK，按需下载到手机私有目录（`/models/**` 由内置代理从 hf-mirror 拉取，
中国网络可达）：

- 英语模式：识别 ~40MB + 英→中翻译 ~108MB
- 日语模式：另加 识别 ~47MB + 日→英翻译 ~104MB

下载一次后离线可用（卸载重装会重新下载）。

## 架构

```
MainActivity（控制）
   └─ SubtitleService（前台服务, mediaProjection 类型）
        ├─ AudioPlaybackCapture → 16kHz mono float → 每 0.5s base64
        ├─ evaluateJavascript → 悬浮透明 WebView（assets/site + app-bridge.js）
        │    └─ vosk-browser 流式识别 → transformers.js OPUS-MT → 字幕渲染
        └─ shouldInterceptRequest /models/** → filesDir 缓存 → hf-mirror
```

## 目录

- `app/src/main/java/com/livesubtitles/app/MainActivity.kt` — 控制界面（授权流程）
- `app/src/main/java/com/livesubtitles/app/SubtitleService.kt` — 内录 + 悬浮窗 + 模型代理
- `app/src/main/assets/site/` — 网页版代码快照（`app-bridge.js` 为 App 专用桥接）
- `.github/workflows/build.yml` — CI 打包配置

## 系统要求

Android 10（API 29）及以上，建议 Chrome 内核 WebView 较新的设备。
Netflix 等 App 会禁止内录其音频（`allowedCapturePolicy=BLOCK`），此类源无法识别。
