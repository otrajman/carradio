// Puts an already-uploaded bundle on another Play track (e.g. internal → beta, which is
// "open testing" in the console). The bundle must exist in the app (uploaded earlier).
// Usage: node play-promote.mjs <package> <track> <versionCode>
import { GoogleAuth } from "google-auth-library";

const [pkg, track, versionCode] = process.argv.slice(2);
if (!pkg || !track || !versionCode) {
  console.error("usage: play-promote.mjs <package> <track> <versionCode>");
  process.exit(2);
}
const auth = new GoogleAuth({
  credentials: JSON.parse(process.env.PLAY_SERVICE_ACCOUNT_JSON),
  scopes: ["https://www.googleapis.com/auth/androidpublisher"],
});
const client = await auth.getClient();
const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${pkg}/edits`;
const call = async (method, url, data) => (await client.request({ method, url, data })).data;

const { id } = await call("POST", base, {});
const bundles = await call("GET", `${base}/${id}/bundles`);
if (!(bundles.bundles ?? []).some((b) => String(b.versionCode) === String(versionCode))) {
  console.error(`versionCode ${versionCode} is not uploaded to ${pkg}`);
  process.exit(1);
}
await call("PUT", `${base}/${id}/tracks/${track}`, {
  track,
  releases: [{ versionCodes: [String(versionCode)], status: "completed" }],
});
await call("POST", `${base}/${id}:commit`, {});
console.log(`${pkg}: versionCode ${versionCode} is now live on track "${track}"`);
