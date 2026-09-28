// AudioWorklet for the PelotonCB VOX meter: ~20 ms frames (960 samples at 48 kHz) posted
// to the main thread, where the shared VoxDetector turns levels into snippet boundaries.
class VoxMeter extends AudioWorkletProcessor {
  constructor() {
    super();
    this.buf = new Float32Array(960);
    this.n = 0;
  }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (!ch) return true;
    for (let i = 0; i < ch.length; i++) {
      this.buf[this.n++] = ch[i];
      if (this.n === this.buf.length) {
        this.port.postMessage(this.buf.slice());
        this.n = 0;
      }
    }
    return true;
  }
}
registerProcessor("vox-meter", VoxMeter);
