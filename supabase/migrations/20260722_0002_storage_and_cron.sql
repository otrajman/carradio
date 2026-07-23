-- Public bucket for voice bursts; reads are by public URL, writes are insert-only.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'voice_bursts', 'voice_bursts', true,
  262144, -- 256 KB hard cap per burst
  array['audio/webm', 'audio/ogg', 'audio/mp4', 'audio/mpeg', 'audio/aac', 'audio/x-m4a']
)
on conflict (id) do nothing;

create policy voice_bursts_insert on storage.objects
  for insert to anon, authenticated
  with check (bucket_id = 'voice_bursts');

create policy voice_bursts_read on storage.objects
  for select to anon, authenticated
  using (bucket_id = 'voice_bursts');

-- Ephemerality: hard-delete everything older than 24 h, hourly.
select cron.schedule(
  'carradio-cleanup',
  '15 * * * *',
  $$
  delete from public.messages where created_at < now() - interval '24 hours';
  delete from storage.objects where bucket_id = 'voice_bursts' and created_at < now() - interval '24 hours';
  delete from public.mute_events where created_at < now() - interval '24 hours';
  delete from public.shadowbans where banned_until < now() - interval '24 hours';
  delete from public.trips t
    where t.created_at < now() - interval '48 hours'
    and not exists (select 1 from public.messages m where m.trip_id = t.id);
  $$
);
