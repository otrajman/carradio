// Deploy helper for pushing static assets to the public 'app' bucket with the
// service role. Deployed as a 410 stub when not in use — redeploy this source,
// PUT the file, then redeploy the stub (see pwa/README.md).
import { createClient } from "npm:@supabase/supabase-js@2";

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
);

Deno.serve(async (req) => {
  if (req.method !== "PUT") return new Response("PUT only", { status: 405 });
  const body = await req.arrayBuffer();
  if (body.byteLength < 1000 || body.byteLength > 5_000_000) {
    return new Response("bad size", { status: 400 });
  }
  const { error } = await supabase.storage
    .from("app")
    .upload("index.html", body, { contentType: "text/html", upsert: true });
  if (error) return new Response(error.message, { status: 500 });
  return new Response("ok");
});
