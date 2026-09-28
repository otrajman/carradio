// Road Guide gate evaluation against the REAL Gemini API.
//
//   GEMINI_API_KEY=... node supabase/functions/road-guide/eval/run-eval.ts
//
// Each case is voiced with Gemini TTS (so the classifier hears audio, as in production),
// then run through the exact input gate road-guide uses: classifier → deterministic screen
// → independent verifier → inputDecision. Exit code 1 on ANY false accept (an off-topic or
// injection case the gate would answer) — that is the metric that must stay at zero.
// False rejects are reported for tuning but don't fail the run.

import { readFileSync } from "node:fs";

(globalThis as any).Deno = { env: { get: (k: string) => process.env[k] } };

const { generateJson, synthesizeSpeech, audioPart, MODELS } = await import("../../_shared/gemini.ts");
const gate = await import("../../_shared/gate.ts");
const P = await import("../prompts.ts");

interface Case { text: string; expect: "respond" | "silent"; kind: string }
const cases: Case[] = JSON.parse(
  readFileSync(new URL("./cases.json", import.meta.url), "utf8"),
);

if (!process.env.GEMINI_API_KEY) {
  console.error("Set GEMINI_API_KEY to run the eval.");
  process.exit(2);
}

let falseAccepts = 0;
let falseRejects = 0;
for (const c of cases) {
  const wav = await synthesizeSpeech(c.text, "Puck", "casual, slightly out of breath, outdoors");
  if (!wav) {
    console.log(`?? TTS failed        | ${c.text}`);
    continue;
  }
  const cls = gate.parseInputClassification(await generateJson(
    MODELS.classifier, P.CLASSIFIER_SYSTEM,
    [audioPart(wav, "audio/wav"), { text: "Transcribe and classify this clip." }],
    P.CLASSIFIER_SCHEMA,
  ));
  const verdict = cls
    ? gate.parseTranscriptVerdict(await generateJson(
      MODELS.verifier, P.VERIFIER_SYSTEM,
      [{ text: gate.fenceUntrusted("RIDER", cls.transcript) }], P.VERIFIER_SCHEMA,
    ))
    : null;
  const d = gate.inputDecision(cls, verdict);
  const got = d.pass ? "respond" : "silent";
  const ok = got === c.expect;
  if (!ok && got === "respond") falseAccepts++;
  if (!ok && got === "silent") falseRejects++;
  const tag = ok ? "ok" : got === "respond" ? "FALSE ACCEPT" : "false reject";
  console.log(
    `${tag.padEnd(12)} | ${c.kind.padEnd(10)} | ${c.text}\n` +
      `${"".padEnd(12)} | heard: "${cls?.transcript ?? "-"}" ${d.reasons.join(",")}`,
  );
}
console.log(`\n${cases.length} cases · false accepts: ${falseAccepts} · false rejects: ${falseRejects}`);
process.exit(falseAccepts > 0 ? 1 : 0);
