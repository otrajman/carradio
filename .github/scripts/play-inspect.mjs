// Prints internal-track state + tester lists for each package via the Play Developer API.
// Run from CI with PLAY_SERVICE_ACCOUNT_JSON; nothing is modified (edit is never committed).
import { GoogleAuth } from "google-auth-library";

const auth = new GoogleAuth({
  credentials: JSON.parse(process.env.PLAY_SERVICE_ACCOUNT_JSON),
  scopes: ["https://www.googleapis.com/auth/androidpublisher"],
});
const client = await auth.getClient();
const base = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";

async function call(method, url, body) {
  const res = await client.request({ method, url, data: body });
  return res.data;
}

for (const pkg of process.argv.slice(2)) {
  console.log(`\n===== ${pkg} =====`);
  try {
    const edit = await call("POST", `${base}/${pkg}/edits`, {});
    const id = edit.id;
    const tracks = await call("GET", `${base}/${pkg}/edits/${id}/tracks`);
    for (const t of tracks.tracks ?? []) {
      console.log(`track ${t.track}:`);
      for (const r of t.releases ?? []) {
        console.log(`  release ${r.name ?? "?"} status=${r.status} versionCodes=${JSON.stringify(r.versionCodes)} countries=${JSON.stringify(r.countryTargeting ?? "all")}`);
      }
    }
    for (const track of ["internal", "alpha"]) {
      try {
        const testers = await call("GET", `${base}/${pkg}/edits/${id}/testers/${track}`);
        console.log(`testers(${track}): groups=${JSON.stringify(testers.googleGroups ?? [])}`);
      } catch (e) {
        console.log(`testers(${track}): ${e.response?.status ?? e.message}`);
      }
    }
    const details = await call("GET", `${base}/${pkg}/edits/${id}/details`);
    console.log(`details: defaultLanguage=${details.defaultLanguage} contactEmail=${details.contactEmail ?? "-"}`);
    await call("DELETE", `${base}/${pkg}/edits/${id}`);
  } catch (e) {
    console.log(`ERROR ${e.response?.status ?? ""}: ${JSON.stringify(e.response?.data ?? e.message)}`);
  }
}
