'use strict';
/* Android 混合模式桥接：
 * - 隐藏网页专用控件，只保留字幕渲染区（整页透明，作为悬浮窗）
 * - 提供 __feedAudio(base64)：接收原生内录音频（16kHz 单声道 float32 LE）
 * - 提供 __appStart(lang)：加载识别+翻译模型并启动管线（复用 app.js 的全局函数）
 */
(function () {
  const params = new URLSearchParams(location.search);
  console.log('[bridge] init, app=' + params.get('app') + ', lang=' + params.get('lang') + ', vosk=' + typeof Vosk);
  if (params.get('app') !== '1') return;

  // App 模式下把进度/错误直接显示在字幕区（悬浮窗里没有其他可见 UI）
  function showAppStatus(text) {
    const zh = document.getElementById('sub-zh');
    if (!zh) return;
    if (text) {
      zh.textContent = text;
      zh.classList.remove('pending');
      zh.style.fontSize = '13px';
      zh.style.opacity = '0.75';
    } else {
      zh.textContent = '';
      zh.style.fontSize = '';
      zh.style.opacity = '';
    }
  }
  window.__showAppStatus = showAppStatus;

  // progress-area 在悬浮窗里被隐藏，轮询其文本镜像到字幕区（setProgress 是 const，无法劫持）
  setInterval(() => {
    if (window.__appReady) return;
    const pt = document.getElementById('progress-text');
    if (pt && pt.textContent && !pt.textContent.startsWith('识别运行中')) {
      const cur = document.getElementById('sub-zh');
      if (cur && cur.textContent !== pt.textContent) showAppStatus(pt.textContent);
    }
  }, 800);

  window.addEventListener('DOMContentLoaded', () => console.log('[bridge] chrome applied'));

  function applyChrome() {
    document.documentElement.style.background = 'transparent';
    if (document.body) {
      document.body.style.background = 'transparent';
      document.body.style.overflow = 'hidden';
    }
    // 只留字幕层，其余全部隐藏
    const hideSelectors = [
      'header', '#source-controls', '#actions', '#progress-area',
      'footer', '#error-area', '#history', '#player-wrap',
    ];
    for (const sel of hideSelectors) {
      const el = document.querySelector(sel);
      if (el) el.style.display = 'none';
    }
    const ov = document.getElementById('subtitle-overlay');
    if (ov) {
      ov.classList.remove('hidden');
      ov.style.bottom = '8px';
      ov.style.left = '8px';
      ov.style.right = '8px';
    }
    // 悬浮窗很矮，隐藏原文小字以省空间（可用 ?orig=1 打开）
    if (params.get('orig') !== '1') {
      const so = document.getElementById('sub-original');
      if (so) so.style.display = 'none';
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', applyChrome);
  } else {
    applyChrome();
  }

  // 原生 → JS：喂入内录音频（base64 of float32 little-endian）
  window.__feedAudio = function (b64) {
    const rec = window.__appRec;
    if (!rec) return;
    try {
      const bin = atob(b64);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      const n = bytes.length >> 2;
      const f32 = new Float32Array(n);
      const dv = new DataView(bytes.buffer);
      for (let i = 0; i < n; i++) f32[i] = dv.getFloat32(i * 4, true);
      rec.acceptWaveformFloat(f32, 16000);
    } catch (e) { /* 忽略喂数据错误 */ }
  };

  // 原生 → JS：启动识别+翻译管线（复用 app.js 全局：loadAsr/preloadMt/onFinal/onPartial）
  window.__appStart = async function (lang) {
    try {
      if (window.__appStarted) return;
      window.__appStarted = true;
      console.log('[bridge] appStart begin, lang=' + lang);
      langSel.value = lang === 'ja' ? 'ja' : 'en';
      setStatus('loading');
      showAppStatus('加载识别模型（首次约 40~50MB）…');
      if (!state.asrModel || state.asrLang !== langSel.value) {
        state.asrModel = await loadAsr(langSel.value);
        state.asrLang = langSel.value;
      }
      console.log('[bridge] asr model loaded');
      showAppStatus('加载翻译模型（首次约 110~210MB）…');
      await preloadMt(langSel.value);
      console.log('[bridge] mt preloaded');
      if (state.recognizer) { try { state.recognizer.remove(); } catch (e) {} }
      const rec = new state.asrModel.KaldiRecognizer(16000);
      rec.on('result', (msg) => onFinal(msg.result ? msg.result.text : ''));
      rec.on('partialresult', (msg) => onPartial(msg.result ? msg.result.partial : ''));
      state.recognizer = rec;
      window.__appRec = rec; // __feedAudio 从这里取识别器
      state.running = true;
      overlay.classList.remove('hidden');
      applyChrome();
      setStatus('ready');
      setProgress('识别运行中', null);
      showAppStatus(''); // 就绪后清除状态字
      window.__appReady = true;
      console.log('[bridge] READY');
    } catch (e) {
      window.__appError = String((e && e.message) || e);
      console.error('[bridge] appStart FAILED: ' + window.__appError);
      showAppStatus('⚠ 字幕引擎启动失败: ' + window.__appError);
      setStatus('error');
    }
  };
})();
