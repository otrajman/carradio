-- Car Radio core schema (v1)
create extension if not exists postgis;
create extension if not exists pg_cron;

-- Ephemeral identities: one row per drive session. No PII, ever.
create table public.trips (
  id uuid primary key default gen_random_uuid(),
  phonetic_handle text not null check (char_length(phonetic_handle) between 3 and 40),
  created_at timestamptz not null default now()
);

-- Audio breadcrumbs. Live location is never stored; each row is a point-in-time burst.
create table public.messages (
  id uuid primary key default gen_random_uuid(),
  trip_id uuid not null references public.trips(id) on delete cascade,
  kind text not null default 'voice' check (kind in ('voice', 'system')),
  audio_path text,
  text text check (char_length(text) <= 600),
  h3_r9 text not null check (h3_r9 ~ '^[0-9a-f]{15}$'),
  location geometry(Point, 4326) not null,
  heading double precision not null default 0 check (heading >= 0 and heading < 360),
  speed double precision not null default 0 check (speed >= 0 and speed < 150),
  created_at timestamptz not null default now(),
  check (audio_path is not null or text is not null)
);
create index messages_location_gist on public.messages using gist (location);
create index messages_h3_idx on public.messages (h3_r9);
create index messages_created_idx on public.messages (created_at);

create table public.mute_events (
  id bigint generated always as identity primary key,
  muter_trip_id uuid not null references public.trips(id) on delete cascade,
  muted_trip_id uuid not null references public.trips(id) on delete cascade,
  created_at timestamptz not null default now(),
  check (muter_trip_id <> muted_trip_id)
);
create index mute_events_muted_idx on public.mute_events (muted_trip_id, created_at);

create table public.shadowbans (
  trip_id uuid primary key references public.trips(id) on delete cascade,
  banned_until timestamptz not null,
  created_at timestamptz not null default now()
);

-- Community karma: 3 distinct muters within 5 minutes => broadcast radius 0 for 1 hour.
create or replace function public.check_shadowban() returns trigger
language plpgsql security definer set search_path = public as $$
declare cnt int;
begin
  select count(distinct muter_trip_id) into cnt
  from mute_events
  where muted_trip_id = new.muted_trip_id
    and created_at > now() - interval '5 minutes';
  if cnt >= 3 then
    insert into shadowbans (trip_id, banned_until)
    values (new.muted_trip_id, now() + interval '1 hour')
    on conflict (trip_id) do update set banned_until = excluded.banned_until;
  end if;
  return new;
end $$;

create trigger trg_check_shadowban
after insert on public.mute_events
for each row execute function public.check_shadowban();

create or replace function public.is_shadowbanned(p_trip_id uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from shadowbans where trip_id = p_trip_id and banned_until > now()
  );
$$;

-- Cold-start breadcrumb query: recent bursts near a point, newest first.
create or replace function public.get_breadcrumbs(
  p_trip_id uuid,
  p_lat double precision,
  p_lng double precision,
  p_radius_m double precision default 1600,
  p_since_hours int default 24,
  p_limit int default 10
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
    and st_dwithin(
      m.location::geography,
      st_setsrid(st_makepoint(p_lng, p_lat), 4326)::geography,
      least(p_radius_m, 8000)
    )
  order by m.created_at desc
  limit least(p_limit, 25);
$$;

-- RLS: demo posture — anonymous clients may create trips/messages/mutes, never read
-- tables directly (reads go through security-definer RPCs), never update or delete.
alter table public.trips enable row level security;
alter table public.messages enable row level security;
alter table public.mute_events enable row level security;
alter table public.shadowbans enable row level security;

create policy trips_insert on public.trips
  for insert to anon, authenticated with check (true);
create policy trips_select on public.trips
  for select to anon, authenticated using (true);

create policy messages_insert on public.messages
  for insert to anon, authenticated with check (kind = 'voice');

create policy mute_events_insert on public.mute_events
  for insert to anon, authenticated with check (true);

grant execute on function public.is_shadowbanned(uuid) to anon, authenticated;
grant execute on function public.get_breadcrumbs(uuid, double precision, double precision, double precision, int, int) to anon, authenticated;
