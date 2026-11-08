// 音频工作器：把视频音轨下混为单声道并重采样到 16kHz，分块发给主线程
class Resampler16k extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ratio = sampleRate / 16000; // 全局变量 sampleRate 由 AudioWorkletGlobalScope 提供
    this.phase = 0;
    this.chunk = [];
  }
  process(inputs) {
    const ch = inputs[0];
    if (!ch || ch.length === 0) return true;
    const n = ch[0].length;
    const mono = new Float32Array(n);
    const cCount = ch.length;
    for (let i = 0; i < n; i++) {
      let s = 0;
      for (let c = 0; c < cCount; c++) s += ch[c][i];
      mono[i] = s / cCount;
    }
    let pos = this.phase;
    while (pos < n) {
      const i0 = pos | 0;
      const i1 = i0 + 1 < n ? i0 + 1 : n - 1;
      const t = pos - i0;
      this.chunk.push(mono[i0] * (1 - t) + mono[i1] * t);
      pos += this.ratio;
    }
    this.phase = pos - n;
    if (this.chunk.length >= 1600) {
      const out = new Float32Array(this.chunk);
      this.port.postMessage(out, [out.buffer]);
      this.chunk = [];
    }
    return true;
  }
}
registerProcessor('resample-16k', Resampler16k);
