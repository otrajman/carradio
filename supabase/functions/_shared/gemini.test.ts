// Protocol-handling tests for gemini.ts against faked fetch/WebSocket (no network, no key).
// Run: node --test supabase/functions/_shared/*.test.ts
import { afterEach, beforeEach, test } from "node:test";
import assert from "node:assert/strict";

const envVars: Record<string, string> = {};
(globalThis as any).Deno = { env: { get: (k: string) => envVars[k] } };

const { generateJson, synthesizeSpeech, liveAnswer, audioMimeForPath } = await import("./gemini.ts");
const { pcm16ToWav, bytesToBase64 } = await import("./wav.ts");

const realFetch = globalThis.fetch;
let calls: Array<{ url: string; body: any }> = [];

function fakeFetch(responder: (body: any, n: number) => { status: number; json?: unknown }) {
  globalThis.fetch = (async (url: string, init: any) => {
    const body = JSON.parse(init.body);
    calls.push({ url, body });
    const r = responder(body, calls.length);
    return new Response(JSON.stringify(r.json ?? {}), { status: r.status });
  }) as typeof fetch;
}

const textResponse = (text: string) => ({ candidates: [{ content: { parts: [{ text }] } }] });

beforeEach(() => {
  calls = [];
  envVars.GEMINI_API_KEY = "test-key-1234567890";
});
afterEach(() => {
  globalThis.fetch = realFetch;
});

test("no key → every call is silent (null) without touching the network", async () => {
  delete envVars.GEMINI_API_KEY;
  fakeFetch(() => { throw new Error("must not be called"); });
  assert.equal(await generateJson("m", "sys", [{ text: "x" }], {}), null);
  assert.equal(await synthesizeSpeech("hi", "Kore", "calm"), null);
  assert.equal(await liveAnswer({ systemInstruction: "s", userText: "u", voice: "Kore" }), null);
  assert.equal(calls.length, 0);
});

test("generateJson uses responseFormat, parses JSON, sends key header not query", async () => {
  fakeFetch(() => ({ status: 200, json: textResponse('{"on_topic":true}') }));
  const out = await generateJson("gemini-x", "sys", [{ text: "hello" }], { type: "object" });
  assert.deepEqual(out, { on_topic: true });
  assert.equal(calls.length, 1);
  assert.ok(calls[0].url.endsWith("/models/gemini-x:generateContent"));
  assert.ok(!calls[0].url.includes("key="));
  assert.deepEqual(calls[0].body.generationConfig.responseFormat.text.schema, { type: "object" });
  assert.equal(calls[0].body.generationConfig.temperature, 0);
});

test("generateJson falls back to the legacy schema fields on 400", async () => {
  fakeFetch((_b, n) => n === 1 ? { status: 400 } : { status: 200, json: textResponse('{"a":1}') });
  assert.deepEqual(await generateJson("m", "s", [{ text: "x" }], { type: "object" }), { a: 1 });
  assert.equal(calls.length, 2);
  assert.equal(calls[1].body.generationConfig.responseMimeType, "application/json");
  assert.deepEqual(calls[1].body.generationConfig.responseJsonSchema, { type: "object" });
});

test("generateJson fails closed on errors and non-JSON text", async () => {
  fakeFetch(() => ({ status: 500 }));
  assert.equal(await generateJson("m", "s", [{ text: "x" }], {}), null);
  fakeFetch(() => ({ status: 200, json: textResponse("sure! here you go") }));
  assert.equal(await generateJson("m", "s", [{ text: "x" }], {}), null);
  fakeFetch(() => ({ status: 400 }));
  assert.equal(await generateJson("m", "s", [{ text: "x" }], {}), null);
});

test("TTS: WAV passes through; raw PCM gets a 24 kHz WAV header", async () => {
  const pcm = new Uint8Array([1, 0, 2, 0, 3, 0, 4, 0]);
  const wav = pcm16ToWav(pcm, 24_000);
  const audioJson = (b: Uint8Array) => ({
    candidates: [{ content: { parts: [{ inlineData: { mimeType: "audio/wav", data: bytesToBase64(b) } }] } }],
  });

  fakeFetch(() => ({ status: 200, json: audioJson(wav) }));
  assert.deepEqual(await synthesizeSpeech("hi", "Charon", "calm"), wav);
  assert.deepEqual(calls[0].body.generationConfig.responseModalities, ["AUDIO"]);
  assert.equal(
    calls[0].body.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName, "Charon",
  );

  fakeFetch(() => ({ status: 200, json: audioJson(pcm) }));
  const wrapped = await synthesizeSpeech("hi", "Charon", "calm");
  assert.ok(wrapped);
  assert.equal(new TextDecoder().decode(wrapped!.subarray(0, 4)), "RIFF");
  assert.equal(new DataView(wrapped!.buffer).getUint32(24, true), 24_000);
  assert.equal(wrapped!.byteLength, 44 + pcm.byteLength);
});

// --- Live -------------------------------------------------------------------------------

type Script = (ws: FakeWS, sent: any) => void;
class FakeWS {
  static script: Script = () => {};
  static last: FakeWS | null = null;
  url: string;
  binaryType = "blob";
  sent: any[] = [];
  closed = false;
  onopen: (() => void) | null = null;
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  onerror: (() => void) | null = null;
  onclose: (() => void) | null = null;
  constructor(url: string) {
    this.url = url;
    FakeWS.last = this;
    queueMicrotask(() => this.onopen?.());
  }
  send(data: string) {
    const msg = JSON.parse(data);
    this.sent.push(msg);
    queueMicrotask(() => FakeWS.script(this, msg));
  }
  emit(obj: unknown, binary = false) {
    const s = JSON.stringify(obj);
    this.onmessage?.({ data: binary ? new TextEncoder().encode(s).buffer : s });
  }
  close() {
    this.closed = true;
  }
}
(globalThis as any).WebSocket = FakeWS;

const pcmChunk = (n: number) => bytesToBase64(new Uint8Array(n).fill(7));

test("Live: setup → text turn → collects audio + transcript until turnComplete", async () => {
  FakeWS.script = (ws, msg) => {
    if (msg.setup) return ws.emit({ setupComplete: {} }, true); // binary JSON frame
    if (msg.clientContent) {
      ws.emit({ serverContent: { modelTurn: { parts: [{ inlineData: { data: pcmChunk(4) } }] } } });
      ws.emit({ serverContent: { outputTranscription: { text: "That's Lake " } } });
      ws.emit({ serverContent: { modelTurn: { parts: [{ inlineData: { data: pcmChunk(6) } }] } } });
      ws.emit({ serverContent: { outputTranscription: { text: "Tahoe." } } });
      ws.emit({ serverContent: { generationComplete: true } });
      ws.emit({ serverContent: { turnComplete: true } });
    }
  };
  const out = await liveAnswer({ systemInstruction: "SYS", userText: "what lake", voice: "Kore" });
  assert.ok(out);
  assert.equal(out!.text, "That's Lake Tahoe.");
  assert.equal(out!.wav.byteLength, 44 + 10);
  const ws = FakeWS.last!;
  assert.ok(ws.url.includes("BidiGenerateContent?key=test-key-1234567890"));
  assert.equal(ws.sent[0].setup.model, "models/gemini-3.8-live");
  assert.deepEqual(ws.sent[0].setup.generationConfig.responseModalities, ["AUDIO"]);
  assert.deepEqual(ws.sent[0].setup.tools, [{ googleSearch: {} }]);
  assert.deepEqual(ws.sent[0].setup.outputAudioTranscription, {});
  assert.equal(ws.sent[1].clientContent.turns[0].parts[0].text, "what lake");
  assert.equal(ws.sent[1].clientContent.turnComplete, true);
  assert.ok(ws.closed);
});

test("Live: interrupted, audio-less, or early-closed turns yield null", async () => {
  FakeWS.script = (ws, msg) => {
    if (msg.setup) return ws.emit({ setupComplete: {} });
    ws.emit({ serverContent: { modelTurn: { parts: [{ inlineData: { data: pcmChunk(4) } }] } } });
    ws.emit({ serverContent: { interrupted: true } });
  };
  assert.equal(await liveAnswer({ systemInstruction: "s", userText: "u", voice: "Kore" }), null);

  FakeWS.script = (ws, msg) => {
    if (msg.setup) return ws.emit({ setupComplete: {} });
    ws.emit({ serverContent: { turnComplete: true } });
  };
  assert.equal(await liveAnswer({ systemInstruction: "s", userText: "u", voice: "Kore" }), null);

  FakeWS.script = (ws) => ws.onclose?.();
  assert.equal(await liveAnswer({ systemInstruction: "s", userText: "u", voice: "Kore" }), null);
});

test("Live: a silent server times out to null", async () => {
  FakeWS.script = () => {};
  const t0 = Date.now();
  assert.equal(
    await liveAnswer({ systemInstruction: "s", userText: "u", voice: "Kore", timeoutMs: 50 }),
    null,
  );
  assert.ok(Date.now() - t0 < 1000);
  assert.ok(FakeWS.last!.closed);
});

test("burst containers map to Gemini-accepted audio types (never audio/mp4)", () => {
  assert.equal(audioMimeForPath("t/m.ogg"), "audio/ogg");
  assert.equal(audioMimeForPath("t/m.m4a"), "audio/m4a");
  assert.equal(audioMimeForPath("t/m.webm"), "audio/webm");
  assert.equal(audioMimeForPath("t/m.mp4"), "audio/m4a");
  assert.equal(audioMimeForPath("t/m.exe"), null);
});
