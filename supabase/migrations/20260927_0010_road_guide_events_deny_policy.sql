-- road_guide_events: RLS enabled with no policies = implicit deny. Make the deny explicit
-- (same posture as shadowbans, migration 0005) so the linter's INFO clears and intent is
-- documented: only edge functions (service role) ever touch this table.
create policy road_guide_events_no_client_access on public.road_guide_events
  as restrictive for all to anon, authenticated using (false) with check (false);
