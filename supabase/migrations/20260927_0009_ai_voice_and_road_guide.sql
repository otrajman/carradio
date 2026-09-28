-- AI voices (PROTOCOL §12 update) and the Road Guide copilot (PROTOCOL §17).
--
-- 1. `synthetic_voice` bucket: server-rendered speech (Gemini TTS for synthetic nodes,
--    Gemini Live answers for the Road Guide). Public read by URL like voice_bursts, but
--    NO client insert policy: only edge functions (service role) write here. WAV is
--    larger than rider bursts, hence a separate bucket instead of raising the 256 KB
--    voice_bursts cap that anon uploads are held to.
-- 2. `road_guide_events`: one row per gate evaluation — the audit trail for tuning the
--    verification gate AND the source of truth for per-trip rate limits. Clients have no
--    access at all (RLS on, no policies). Transcripts are retained 24 h like voice.
-- 3. The hourly cleanup job gains both.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'synthetic_voice', 'synthetic_voice', true,
  2097152, -- 2 MB: ~40 s of 24 kHz mono 16-bit WAV
  array['audio/wav', 'audio/x-wav']
)
on conflict (id) do nothing;

create table public.road_guide_events (
  id bigint generated always as identity primary key,
  trip_id uuid not null references public.trips(id) on delete cascade,
  message_id uuid references public.messages(id) on delete set null,
  -- 'responded' | 'gated' | 'rate_limited' | 'error'
  outcome text not null check (outcome in ('responded', 'gated', 'rate_limited', 'error')),
  -- machine-readable gate reasons, e.g. {input_off_topic, verifier_disagrees}
  reasons text[] not null default '{}',
  transcript text check (char_length(transcript) <= 1000),
  category text,
  response_text text check (char_length(response_text) <= 1000),
  response_audio_path text,
  created_at timestamptz not null default now()
);
create index road_guide_events_trip_idx on public.road_guide_events (trip_id, created_at);

alter table public.road_guide_events enable row level security;
-- Deliberately no policies: service role only (same posture as shadowbans).
revoke all on public.road_guide_events from anon, authenticated;

select cron.unschedule('carradio-cleanup');
select cron.schedule(
  'carradio-cleanup',
  '15 * * * *',
  $$
  delete from public.messages m
    where m.created_at < now() - interval '24 hours'
    and not exists (select 1 from public.reports r where r.message_id = m.id);
  delete from public.messages m
    where m.created_at < now() - interval '30 days';
  delete from storage.objects o
    where o.bucket_id = 'voice_bursts'
    and o.created_at < now() - interval '24 hours'
    and not exists (
      select 1 from public.messages m
      join public.reports r on r.message_id = m.id
      where m.audio_path = 'voice_bursts/' || o.name
    );
  delete from storage.objects o
    where o.bucket_id = 'synthetic_voice'
    and o.created_at < now() - interval '24 hours';
  delete from public.road_guide_events where created_at < now() - interval '24 hours';
  delete from public.reports where created_at < now() - interval '30 days';
  delete from public.mute_events where created_at < now() - interval '24 hours';
  delete from public.shadowbans where banned_until < now() - interval '24 hours';
  delete from public.trips t
    where t.created_at < now() - interval '48 hours'
    and not exists (select 1 from public.messages m where m.trip_id = t.id)
    and not exists (select 1 from public.reports r
                    where r.reporter_trip_id = t.id or r.reported_trip_id = t.id);
  $$
);
