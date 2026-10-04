-- Road Guide audit rows record per-stage latency (ms) so slow answers can be diagnosed.
alter table public.road_guide_events add column if not exists timings jsonb;
