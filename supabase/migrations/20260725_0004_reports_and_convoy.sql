-- Reporting (Play UGC policy), report-driven moderation, convoy mode, and
-- retention bounds per PROTOCOL §8/§14/§15.

create table public.reports (
  id bigint generated always as identity primary key,
  reporter_trip_id uuid not null references public.trips(id) on delete cascade,
  reported_trip_id uuid not null references public.trips(id) on delete cascade,
  message_id uuid references public.messages(id) on delete set null,
  reason text check (char_length(reason) <= 200),
  created_at timestamptz not null default now(),
  check (reporter_trip_id <> reported_trip_id)
);
create index reports_reported_idx on public.reports (reported_trip_id, created_at);
create index reports_message_idx on public.reports (message_id);

alter table public.reports enable row level security;
create policy reports_insert on public.reports
  for insert to anon, authenticated with check (true);

-- 2 distinct reporters within 24 h => 24 h shadowban (stronger than the mute rule).
create or replace function public.check_report_shadowban() returns trigger
language plpgsql security definer set search_path = public as $$
declare cnt int;
begin
  select count(distinct reporter_trip_id) into cnt
  from reports
  where reported_trip_id = new.reported_trip_id
    and created_at > now() - interval '24 hours';
  if cnt >= 2 then
    insert into shadowbans (trip_id, banned_until)
    values (new.reported_trip_id, now() + interval '24 hours')
    on conflict (trip_id) do update
      set banned_until = greatest(shadowbans.banned_until, excluded.banned_until);
  end if;
  return new;
end $$;

create trigger trg_check_report_shadowban
after insert on public.reports
for each row execute function public.check_report_shadowban();

revoke execute on function public.check_report_shadowban() from anon, authenticated, public;

-- Convoy mode: hashed tag on breadcrumbs.
alter table public.messages add column convoy_tag text
  check (convoy_tag is null or convoy_tag ~ '^[0-9a-f]{16}$');

-- get_breadcrumbs gains p_convoy (null = public traffic only). Signature changes,
-- so drop + recreate; existing clients calling with named args still match
-- because the new parameter has a default.
drop function public.get_breadcrumbs(uuid, double precision, double precision, double precision, int, int);
create function public.get_breadcrumbs(
  p_trip_id uuid,
  p_lat double precision,
  p_lng double precision,
  p_radius_m double precision default 1600,
  p_since_hours int default 24,
  p_limit int default 10,
  p_convoy text default null
) returns table (
  id uuid,
  trip_id uuid,
  handle text,
  kind text,
  audio_path text,
  text text,
  lat double precision,
  lng double precision,
  heading double precision,
  speed double precision,
  created_at timestamptz
)
language sql stable security definer set search_path = public as $$
  select m.id, m.trip_id, t.phonetic_handle, m.kind, m.audio_path, m.text,
         st_y(m.location), st_x(m.location), m.heading, m.speed, m.created_at
  from messages m
  join trips t on t.id = m.trip_id
  where m.created_at > now() - make_interval(hours => least(p_since_hours, 24))
    and m.trip_id is distinct from p_trip_id
    and (m.convoy_tag is not distinct from p_convoy or m.kind = 'system')
    and st_dwithin(
      m.location::geography,
      st_setsrid(st_makepoint(p_lng, p_lat), 4326)::geography,
      least(p_radius_m, 8000)
    )
  order by m.created_at desc
  limit least(p_limit, 25);
$$;
grant execute on function public.get_breadcrumbs(uuid, double precision, double precision, double precision, int, int, text)
  to anon, authenticated;

-- Retention (PROTOCOL §15): reported messages/audio held 30 days as evidence,
-- everything else keeps its 24 h bound.
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
