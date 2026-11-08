'use strict';
/* 实时字幕：视频音频 → Vosk 流式识别(16k) → OPUS-MT 翻译 → 字幕叠加/画中画 */

const $ = (id) => document.getElementById(id);
const video = $('video'), langSel = $('lang'), statusDot = $('status-dot');
const startBtn = $('start-btn'), pipBtn = $('pip-btn');
const fileInput = $('file-input'), urlInput = $('url-input'), loadUrlBtn = $('load-url');
const overlay = $('subtitle-overlay'), subOriginal = $('sub-original'), subZh = $('sub-zh');
const progressText = $('progress-text'), progressBar = $('progress-bar'), progressFill = $('progress-fill');
const errorArea = $('error-area'), historyEl = $('history');
const showOriginalCb = $('show-original'), showHistoryCb = $('show-history');

const state = {
  lang: 'en',
  asrModel: null,
  asrLang: null,
  recognizer: null,
  audioCtx: null,
  sourceNode: null,   // MediaElementAudioSourceNode 只能对同一元素创建一次
  workletNode: null,
  workletLoaded: false,
  mtWorker: null,
  mtSeq: 0,
  pendingTranslate: new Map(),
  preloadWait: null,
  running: false,
  loading: false,
  pipStream: null,
};

window.__subLog = [];
function log(...args) {
  const line = args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' ');
  window.__subLog.push(`[${new Date().toISOString().slice(11, 23)}] ${line}`);
  if (window.__subLog.length > 500) window.__subLog.shift();
  console.log('[rtsub]', ...args);
}

function setStatus(kind) { statusDot.className = 'dot dot-' + kind; }
function showError(msg) { errorArea.textContent = '⚠ ' + msg; errorArea.classList.remove('hidden'); log('ERROR', msg); }
function clearError() { errorArea.classList.add('hidden'); }
function setProgress(text, ratio) {
  progressText.textContent = text;
  if (ratio == null) progressBar.classList.add('hidden');
  else { progressBar.classList.remove('hidden'); progressFill.style.width = Math.min(100, Math.max(0, ratio * 100)).toFixed(1) + '%'; }
}
function fmtMB(n) { return (n / 1048576).toFixed(1) + 'MB'; }

/* ============ 识别模型（vosk zip，带进度 + CacheStorage 缓存） ============ */
const VOSK_ZIPS = {
  en: './models/vosk-model-small-en-us-0.15.zip',
  ja: './models/vosk-model-small-ja-0.22.zip',
};

async function getVoskModelBlob(lang) {
  const url = VOSK_ZIPS[lang];
  // 非安全上下文（如局域网 IP 的 http 页面）没有 CacheStorage，降级为无缓存
  let cache = null;
  try { if (typeof caches !== 'undefined') cache = await caches.open('rtsub-models-v1'); }
  catch (e) { log('CacheStorage unavailable, skip model cache'); }
  if (cache) {
    const hit = await cache.match(url);
    if (hit) {
      setProgress('读取已缓存的识别模型…', null);
      return hit.blob();
    }
  }
  const resp = await fetch(url);
  if (!resp.ok) throw new Error(`识别模型下载失败 (HTTP ${resp.status})`);
  const total = +(resp.headers.get('content-length') || 0);
  const reader = resp.body.getReader();
  const chunks = []; let loaded = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value); loaded += value.length;
    if (total) setProgress(`下载识别模型… ${fmtMB(loaded)} / ${fmtMB(total)}`, loaded / total);
  }
  const blob = new Blob(chunks, { type: 'application/zip' });
  if (cache) { try { await cache.put(url, new Response(blob.slice(0))); } catch (e) { log('cache put fail', e); } }
  return blob;
}

function withTimeout(promise, ms, msg) {
  return Promise.race([
    promise,
    new Promise((_, rej) => setTimeout(() => rej(new Error(msg)), ms)),
  ]);
}

async function loadAsr(lang) {
  const blob = await getVoskModelBlob(lang);
  setProgress('解包识别模型…', null);
  const blobUrl = URL.createObjectURL(blob);
  try {
    return await withTimeout(
      Vosk.createModel(blobUrl),
      180000,
      '识别模型加载超时，请刷新页面重试',
    );
  } finally {
    URL.revokeObjectURL(blobUrl);
  }
}

/* ============ 翻译 worker ============ */
function ensureMtWorker() {
  if (state.mtWorker) return state.mtWorker;
  const w = new Worker('./mt-worker.js', { type: 'module' });
  w.onmessage = (e) => {
    const d = e.data;
    if (d.type === 'progress') {
      setProgress(`下载翻译模型… ${fmtMB(d.loaded)} / ${fmtMB(d.total)}`, d.total ? d.loaded / d.total : null);
    } else if (d.type === 'preloaded') {
      log('mt preloaded', d.model);
    } else if (d.type === 'preload-done') {
      if (state.preloadWait) { const r = state.preloadWait.ok; state.preloadWait = null; r(); }
    } else if (d.type === 'preload-error') {
      const rej = state.preloadWait && state.preloadWait.reject;
      state.preloadWait = null;
      if (rej) rej(new Error(d.error));
    } else if (d.type === 'result') {
      const cb = state.pendingTranslate.get(d.id);
      state.pendingTranslate.delete(d.id);
      if (cb) cb({ zh: d.zh, en: d.en });
    } else if (d.type === 'error') {
      const cb = state.pendingTranslate.get(d.id);
      state.pendingTranslate.delete(d.id);
      if (cb) cb({ zh: '', en: '', error: d.error });
    }
  };
  w.onerror = (e) => showError('翻译引擎加载失败: ' + (e.message || 'worker error'));
  state.mtWorker = w;
  return w;
}

function preloadMt(lang) {
  return new Promise((resolve, reject) => {
    state.preloadWait = { ok: resolve, reject };
    ensureMtWorker().postMessage({ type: 'preload', lang });
  });
}

function translate(text, lang) {
  return new Promise((resolve) => {
    const id = ++state.mtSeq;
    state.pendingTranslate.set(id, resolve);
    state.mtWorker.postMessage({ type: 'translate', id, text, lang });
  });
}

/* ============ 音频管线 ============ */
function ensureAudioGraph() {
  if (!state.audioCtx) state.audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  if (!state.sourceNode) state.sourceNode = state.audioCtx.createMediaElementSource(video);
  return state.audioCtx;
}

function feedAudio(f32) {
  if (!state.running || !state.recognizer || video.paused || video.ended) return;
  try { state.recognizer.acceptWaveformFloat(f32, 16000); }
  catch (e) { log('feed error', e); }
}

/* ============ 字幕渲染 ============ */
function onPartial(text) {
  if (!state.running) return;
  if (!text) return;
  subZh.textContent = text;
  subZh.classList.add('pending');
  subOriginal.textContent = '';
  drawPip(text, true);
  log('partial', text);
}

function onFinal(text) {
  if (!state.running) return;
  text = (text || '').trim();
  if (!text) return;
  subOriginal.textContent = showOriginalCb.checked ? text : '';
  subZh.textContent = '…';
  subZh.classList.add('pending');
  log('final', text);
  translate(text, langSel.value).then(({ zh, en, error }) => {
    if (error) {
      log('mt error', error);
      subZh.textContent = '[翻译失败]';
      subZh.classList.remove('pending');
      return;
    }
    if (subOriginal.textContent !== text && showOriginalCb.checked) subOriginal.textContent = text;
    subZh.textContent = zh;
    subZh.classList.remove('pending');
    drawPip(zh, false);
    addHistory(text, zh, en);
    log('translated', zh);
  });
}

function addHistory(orig, zh, en) {
  const div = document.createElement('div');
  div.className = 'history-item';
  const o = document.createElement('div');
  o.className = 'h-original';
  o.textContent = en && en !== orig ? `${orig}  →  ${en}` : orig;
  const z = document.createElement('div');
  z.className = 'h-zh';
  z.textContent = zh;
  div.append(o, z);
  historyEl.prepend(div);
  while (historyEl.children.length > 60) historyEl.lastChild.remove();
}

function clearSubs() {
  subZh.textContent = '';
  subOriginal.textContent = '';
  historyEl.innerHTML = '';
}

/* ============ 画中画字幕 ============ */
function setupPip() {
  const canvas = $('pip-canvas'), pv = $('pip-video');
  if (!state.pipStream) {
    state.pipStream = canvas.captureStream(12);
    pv.srcObject = state.pipStream;
    pv.play().catch(() => {});
  }
  pipBtn.disabled = false;
}

function wrapText(g, text, maxWidth) {
  const lines = [];
  let line = '';
  for (const ch of String(text)) {
    if (g.measureText(line + ch).width > maxWidth && line) { lines.push(line); line = ch; }
    else line += ch;
  }
  if (line) lines.push(line);
  return lines.length ? lines : [''];
}

function drawPip(text, pending) {
  const canvas = $('pip-canvas'), g = canvas.getContext('2d');
  const W = canvas.width, H = canvas.height;
  g.fillStyle = '#000'; g.fillRect(0, 0, W, H);
  if (!text) return;
  g.textAlign = 'center';
  g.shadowColor = '#000'; g.shadowBlur = 6;
  g.fillStyle = pending ? 'rgba(255,255,255,.6)' : '#fff';
  g.font = 'bold 28px system-ui, "Microsoft YaHei", sans-serif';
  const lines = wrapText(g, text, W - 28).slice(0, 3);
  const lh = 38;
  let y = H / 2 - ((lines.length - 1) * lh) / 2 + 10;
  for (const l of lines) { g.fillText(l, W / 2, y); y += lh; }
}

pipBtn.addEventListener('click', async () => {
  const pv = $('pip-video');
  try {
    if (document.pictureInPictureElement) await document.exitPictureInPicture();
    else {
      if (pv.paused) await pv.play().catch(() => {});
      await pv.requestPictureInPicture();
    }
  } catch (e) { showError('画中画不可用：' + (e.message || e)); }
});

/* ============ 主流程 ============ */
startBtn.addEventListener('click', async () => {
  if (state.loading) return;
  if (!video.src) { showError('请先选择视频文件或加载视频链接'); return; }
  clearError();
  state.loading = true;
  startBtn.disabled = true;
  setStatus('loading');
  const lang = langSel.value;
  try {
    // 1) 识别模型
    if (!state.asrModel || state.asrLang !== lang) {
      if (state.asrModel) { try { state.asrModel.terminate(); } catch (e) {} state.asrModel = null; state.recognizer = null; }
      state.asrModel = await loadAsr(lang);
      state.asrLang = lang;
    }
    // 2) 翻译模型
    setProgress('准备翻译模型…', null);
    await preloadMt(lang);
    setStatus('ready');
    setProgress('模型就绪', null);
    // 3) 音频图（AudioWorklet 需要安全上下文；局域网 http 访问时降级 ScriptProcessorNode）
    const ctx = ensureAudioGraph();
    if (ctx.state === 'suspended') await ctx.resume();
    if (state.workletNode) { try { state.workletNode.disconnect(); } catch (e) {} state.workletNode = null; }
    if (state.spNode) { try { state.spNode.disconnect(); } catch (e) {} state.spNode.onaudioprocess = null; state.spNode = null; }
    const forceSp = params.get('force-sp') === '1'; // 调试：强制走降级路径
    if (ctx.audioWorklet && !forceSp) {
      if (!state.workletLoaded) {
        await ctx.audioWorklet.addModule('./worklet.js');
        state.workletLoaded = true;
      }
      state.workletNode = new AudioWorkletNode(ctx, 'resample-16k');
      state.workletNode.port.onmessage = (e) => feedAudio(e.data);
      state.sourceNode.connect(state.workletNode);
    } else {
      const sp = ctx.createScriptProcessor(4096, 1, 1);
      const ratio = ctx.sampleRate / 16000;
      let phase = 0;
      sp.onaudioprocess = (ev) => {
        const input = ev.inputBuffer.getChannelData(0);
        const output = ev.outputBuffer.getChannelData(0);
        const out = [];
        let pos = phase;
        while (pos < input.length) {
          const i0 = pos | 0;
          const i1 = i0 + 1 < input.length ? i0 + 1 : input.length - 1;
          const t = pos - i0;
          out.push(input[i0] * (1 - t) + input[i1] * t);
          pos += ratio;
        }
        phase = pos - input.length;
        output.set(input); // 直通，保证播放声音正常
        if (out.length) feedAudio(new Float32Array(out));
      };
      state.sourceNode.connect(sp);
      sp.connect(ctx.destination);
      state.spNode = sp;
    }
    // 4) 识别器
    if (state.recognizer) { try { state.recognizer.remove(); } catch (e) {} }
    state.recognizer = new state.asrModel.KaldiRecognizer(16000);
    state.recognizer.on('result', (msg) => onFinal(msg.result ? msg.result.text : ''));
    state.recognizer.on('partialresult', (msg) => onPartial(msg.result ? msg.result.partial : ''));
    state.running = true;
    overlay.classList.remove('hidden');
    setupPip();
    startBtn.textContent = '重新开始识别';
    setProgress('识别运行中 ▶ 播放视频即可看到字幕', null);
    await video.play().catch(() => {});
    log('started, lang =', lang);
  } catch (err) {
    setStatus('error');
    showError(String((err && err.message) || err));
    log('start failed', err && err.stack || err);
  } finally {
    state.loading = false;
    startBtn.disabled = false;
  }
});

/* ============ 视频源 ============ */
fileInput.addEventListener('change', () => {
  const f = fileInput.files && fileInput.files[0];
  if (!f) return;
  video.removeAttribute('crossorigin');
  video.src = URL.createObjectURL(f);
  clearSubs();
  log('file selected', f.name, fmtMB(f.size));
});

loadUrlBtn.addEventListener('click', () => {
  const u = urlInput.value.trim();
  if (!u) return;
  if (/^https?:/i.test(u)) video.crossOrigin = 'anonymous';
  video.src = u;
  clearSubs();
  video.load();
  log('url loaded', u);
});

video.addEventListener('error', () => {
  if (video.src && /^https?:/i.test(video.currentSrc)) {
    showError('视频加载失败：该网站可能禁止跨域引用，请下载后用「选择视频文件」加载');
  }
});

video.addEventListener('pause', () => {
  // 暂停时强制结算未完成的识别结果
  if (state.recognizer && state.running) {
    try { state.recognizer.retrieveFinalResult(); } catch (e) {}
  }
});

showOriginalCb.addEventListener('change', () => { if (!showOriginalCb.checked) subOriginal.textContent = ''; });
showHistoryCb.addEventListener('change', () => historyEl.classList.toggle('hidden', !showHistoryCb.checked));

langSel.addEventListener('change', () => {
  if (state.running || state.loading) setProgress('语言已切换，点击「开始识别」应用新语言', null);
});

/* ============ 初始化 ============ */
historyEl.classList.toggle('hidden', !showHistoryCb.checked);

/* ============ URL 参数（便于自动化测试） ============ */
const params = new URLSearchParams(location.search);
if (params.get('src')) video.src = params.get('src');
if (params.get('lang')) langSel.value = params.get('lang');

// ?mttext=... 直接测试翻译链路（不经过语音识别）
if (params.get('mttext')) {
  const t = params.get('mttext');
  const wl = langSel.value;
  (async () => {
    try {
      setStatus('loading');
      setProgress('加载翻译模型…', null);
      await preloadMt(wl);
      const r = await translate(t, wl);
      overlay.classList.remove('hidden');
      subOriginal.textContent = t;
      subZh.textContent = r.zh || '[翻译失败]';
      subZh.classList.remove('pending');
      addHistory(t, r.zh || '', r.en || '');
      setStatus('ready');
      setProgress('翻译测试完成', null);
      log('mttext result', r);
    } catch (e) {
      setStatus('error');
      showError('翻译测试失败: ' + ((e && e.message) || e));
    }
  })();
}

log('ready');
