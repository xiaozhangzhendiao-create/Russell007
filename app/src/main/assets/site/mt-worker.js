// 翻译工作器：transformers.js + OPUS-MT 量化模型，全部在本地执行（module worker）
import { pipeline, env } from './vendor/transformers.min.js';

env.allowLocalModels = true;
env.allowRemoteModels = false;
env.localModelPath = './models/';
// 非安全上下文没有 CacheStorage，显式关闭浏览器缓存开关（HTTP 缓存仍然生效）
env.useBrowserCache = typeof caches !== 'undefined';
env.backends.onnx.wasm.wasmPaths = './vendor/';
// 跨域隔离（COOP/COEP）可用时启用 WASM 多线程，翻译明显提速；否则单线程兜底
env.backends.onnx.wasm.numThreads = self.crossOriginIsolated
  ? Math.max(1, Math.min(4, (self.navigator.hardwareConcurrency || 4) - 1))
  : 1;

const pipelines = {};
const queue = [];
let busy = false;
let currentLang = null;
const MAX_QUEUE = 2; // 只保留最新两句待翻译，避免积压导致延迟无限累积

async function getPipeline(name) {
  if (pipelines[name]) return pipelines[name];
  const files = {}; // file -> {loaded, total}
  const p = await pipeline('translation', name, {
    quantized: true,
    progress_callback: (ev) => {
      if (ev && ev.status === 'progress' && ev.file) {
        files[ev.file] = { loaded: ev.loaded || 0, total: ev.total || 0 };
        let loaded = 0, total = 0;
        for (const f of Object.values(files)) { loaded += f.loaded; total += f.total; }
        self.postMessage({ type: 'progress', model: name, loaded, total });
      }
    },
  });
  pipelines[name] = p;
  return p;
}

async function run() {
  if (busy) return;
  busy = true;
  while (queue.length) {
    const job = queue.shift();
    try {
      let en = null;
      if (job.lang === 'ja') {
        const pJa = await getPipeline('opus-mt-ja-en');
        const r1 = await pJa(job.text, { max_new_tokens: 256 });
        en = (Array.isArray(r1) ? r1[0] : r1).translation_text;
      }
      const pZh = await getPipeline('opus-mt-en-zh');
      const r2 = await pZh(en || job.text, { max_new_tokens: 256 });
      const zh = (Array.isArray(r2) ? r2[0] : r2).translation_text;
      job.resolve({ zh, en });
    } catch (err) {
      job.reject(String(err && err.stack || err));
    }
  }
  busy = false;
}

self.onmessage = async (e) => {
  const data = e.data;
  if (data.type === 'translate') {
    if (data.lang && data.lang !== currentLang) {
      currentLang = data.lang;
    }
    // 积压丢弃：队列过长时丢最旧的，优先保证最新内容
    while (queue.length >= MAX_QUEUE) {
      const stale = queue.shift();
      try { stale.dropped(); } catch (err) {}
    }
    queue.push({
      text: data.text,
      lang: data.lang,
      resolve: (out) => self.postMessage({ type: 'result', id: data.id, zh: out.zh, en: out.en }),
      dropped: () => self.postMessage({ type: 'result', id: data.id, zh: '', en: '', dropped: true }),
      reject: (err) => self.postMessage({ type: 'error', id: data.id, error: err }),
    });
    run();
  } else if (data.type === 'preload') {
    // 预热翻译管线，通过 progress 上报下载进度
    const names = data.lang === 'ja' ? ['opus-mt-ja-en', 'opus-mt-en-zh'] : ['opus-mt-en-zh'];
    try {
      for (const name of names) {
        await getPipeline(name); // progress 由 files 聚合后经 'progress' 消息发出
        self.postMessage({ type: 'preloaded', model: name });
      }
      // 预热一次推理，避免第一句字幕额外等待编译/JIT 开销
      try {
        const warmName = data.lang === 'ja' ? 'opus-mt-ja-en' : 'opus-mt-en-zh';
        const warm = pipelines[warmName];
        if (warm) await warm('Hello.', { max_new_tokens: 16 });
      } catch (err) {}
      self.postMessage({ type: 'preload-done' });
    } catch (err) {
      self.postMessage({ type: 'preload-error', error: String(err && err.stack || err) });
    }
  }
};
