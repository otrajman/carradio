-- Advisor pass 2 (2026-07-28): RLS lint fixes.

-- spatial_ref_sys (lint ERROR): owned by supabase_admin, so RLS cannot be
-- enabled from the postgres role. Next best: pull it off the client API
-- surface entirely. (No-op in practice — the anon/authenticated grants were
-- issued by supabase_admin and postgres cannot revoke them; see 0006 for the
-- trigger guard that actually blocks writes.)
revoke all on table public.spatial_ref_sys from anon, authenticated;

-- shadowbans: RLS enabled with no policies = implicit deny. Make the deny
-- explicit so intent is auditable; the service role bypasses RLS and
-- is_shadowbanned() is the only client-facing read path.
create policy shadowbans_deny_clients on public.shadowbans
  for select to anon, authenticated using (false);

-- Replace always-true INSERT checks with each table's real invariants.
-- Handles are "Adjective Animal" from fixed word lists (docs/handles.json);
-- allow up to four words of slack for future list changes.
drop policy trips_insert on public.trips;
create policy trips_insert on public.trips
  for insert to anon, authenticated
  with check (phonetic_handle ~ '^[A-Za-z]+( [A-Za-z]+){1,3}$');

drop policy mute_events_insert on public.mute_events;
create policy mute_events_insert on public.mute_events
  for insert to anon, authenticated
  with check (muter_trip_id <> muted_trip_id);

drop policy reports_insert on public.reports;
create policy reports_insert on public.reports
  for insert to anon, authenticated
  with check (reporter_trip_id <> reported_trip_id);

-- Internal event-trigger function off the RPC surface. get_breadcrumbs and
-- is_shadowbanned stay anon-callable by design: they ARE the read API
-- (SECURITY DEFINER so they can see messages/shadowbans; inputs are capped).
revoke execute on function public.rls_auto_enable() from anon, authenticated, public;
