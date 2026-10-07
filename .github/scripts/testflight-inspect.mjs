// Prints each app's TestFlight beta groups (public link state) and latest builds via the
// App Store Connect API. Read-only. Env: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_P8 (PEM).
import { createPrivateKey, sign } from "node:crypto";

const { ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_P8 } = process.env;
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
const now = Math.floor(Date.now() / 1000);
const unsigned = `${b64({ alg: "ES256", kid: ASC_KEY_ID, typ: "JWT" })}.${b64({
  iss: ASC_ISSUER_ID, iat: now, exp: now + 600, aud: "appstoreconnect-v1",
})}`;
const sig = sign("sha256", Buffer.from(unsigned), {
  key: createPrivateKey(ASC_KEY_P8), dsaEncoding: "ieee-p1363",
}).toString("base64url");
const jwt = `${unsigned}.${sig}`;

async function get(path) {
  const r = await fetch(`https://api.appstoreconnect.apple.com/v1${path}`, {
    headers: { Authorization: `Bearer ${jwt}` },
  });
  if (!r.ok) throw new Error(`${r.status} ${path}: ${(await r.text()).slice(0, 300)}`);
  return r.json();
}

const apps = await get("/apps?fields[apps]=bundleId,name");
for (const app of apps.data) {
  console.log(`\n===== ${app.attributes.name} (${app.attributes.bundleId}) =====`);
  const groups = await get(`/apps/${app.id}/betaGroups?fields[betaGroups]=name,isInternalGroup,publicLinkEnabled,publicLink,publicLinkLimit`);
  for (const g of groups.data) {
    const a = g.attributes;
    console.log(`group "${a.name}": internal=${a.isInternalGroup} publicLinkEnabled=${a.publicLinkEnabled} publicLink=${a.publicLink ?? "-"} limit=${a.publicLinkLimit ?? "-"}`);
  }
  const builds = await get(`/builds?filter[app]=${app.id}&sort=-uploadedDate&limit=3&fields[builds]=version,uploadedDate,processingState,expired`);
  for (const b of builds.data) {
    const a = b.attributes;
    console.log(`build ${a.version}: ${a.processingState} uploaded=${a.uploadedDate} expired=${a.expired}`);
  }
}
