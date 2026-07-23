// Baked demo defaults; override via Vite env (.env / .env.local) without code changes.
export const SUPABASE_URL =
  import.meta.env.VITE_SUPABASE_URL ?? "https://trstelgemjdeqqdlasgw.supabase.co";
export const SUPABASE_ANON_KEY =
  import.meta.env.VITE_SUPABASE_ANON_KEY ??
  "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InRyc3RlbGdlbWpkZXFxZGxhc2d3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQ3NzcwNTQsImV4cCI6MjEwMDM1MzA1NH0.kSfNycRV422WIRSdINM22vkEHM0HZ8QrvsqCVdmvk24";

export const FEATURE_WAKEWORD =
  (import.meta.env.VITE_FEATURE_WAKEWORD ?? "true") === "true";
export const SYNTHETIC_NODES =
  (import.meta.env.VITE_SYNTHETIC_NODES ?? "true") === "true";

export const BUCKET = "voice_bursts";

export function publicAudioUrl(audioPath: string): string {
  return `${SUPABASE_URL}/storage/v1/object/public/${audioPath}`;
}
