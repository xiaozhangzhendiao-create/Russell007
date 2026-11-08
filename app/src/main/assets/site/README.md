# 实时字幕（网页版）· 英/日 → 中文

在浏览器里播放视频，实时生成中文悬浮字幕。**识别与翻译全部在本机离线完成**，音频不上传任何服务器。

## 使用方法

1. 手机或电脑浏览器打开本页（建议 Chrome / Edge，安卓 10+）
2. 选择源语言（英语→中文 或 日语→中文）
3. 点击「选择视频文件」加载本地视频（推荐），或粘贴视频直链
4. 点击「开始识别」——首次使用会下载模型（见下表），之后有缓存秒开
5. 播放视频，字幕会实时叠加在画面下方；也可点「画中画字幕」把字幕窗口悬浮到桌面

## 模型（首次按需下载，之后缓存）

| 用途 | 模型 | 大小 |
|---|---|---|
| 英文识别 | vosk-model-small-en-us-0.15 | ~40MB |
| 日文识别 | vosk-model-small-ja-0.22 | ~47MB |
| 英→中翻译 | OPUS-MT en-zh（int8 量化） | ~110MB |
| 日→英翻译（日语模式） | OPUS-MT ja-en（int8 量化） | ~104MB |

日语模式总下载约 260MB，英语模式约 150MB。模型缓存存在浏览器本地（Cache Storage），不清缓存即长期有效。

## 技术架构

```
<video> 音轨
  → AudioWorklet（下混单声道 + 重采样 16kHz）
  → vosk-browser (WASM Kaldi 流式识别)
  → [日语时: opus-mt-ja-en → opus-mt-en-zh 两级翻译]
  → 字幕叠加层 + 画中画悬浮窗 (canvas.captureStream → PiP)
```

- 识别：[vosk-browser](https://github.com/ccoreilly/vosk-browser) 0.0.8（Kaldi WASM，内部 Web Worker，流式出字）
- 翻译：[transformers.js](https://github.com/huggingface/transformers.js) 2.17.2 + onnxruntime-web 1.14.0（WASM 单线程 SIMD）
- 全部静态文件，无后端、无 API 密钥

## 已知限制

- 翻译为离线小模型，"看懂大意"级别，长难句和专业名词效果一般
- 日语经英语中转（无直接 ja→zh 开源小模型），延迟比英文模式多 1~2 秒
- 浏览器无法捕获其他 App 的声音：只能识别**本页面内**播放的视频（本地文件最稳）
- 部分网站的视频直链有跨域限制，加载后画面正常但识别无声——请改用本地文件
- 需要现代浏览器（Chrome 80+ / Edge / Safari 15+），微信内置浏览器可能不支持

## 目录结构

```
site/
├── index.html        # 页面
├── app.js            # 主逻辑（音频管线、字幕渲染、画中画）
├── mt-worker.js      # 翻译 Web Worker（transformers.js）
├── worklet.js        # 音频重采样 AudioWorklet
├── styles.css
├── vendor/           # vosk.js、transformers.min.js、ort wasm（本地化，不依赖国外CDN）
├── models/           # 识别 zip + 翻译模型（onnx 量化）
└── test/             # 测试音频
```

## 本地开发

```
node scripts/serve.js   # http://localhost:8931
```

调试参数：`?src=test/test-en.wav&lang=en`（自动加载测试音频）、`?mttext=こんにちは&lang=ja`（直测翻译链路）。
