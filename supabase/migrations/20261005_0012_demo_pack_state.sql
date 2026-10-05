-- PROTOCOL §16.7 demo pack: one row paces the synthetic riders' chatter across all clients
-- that are in the DEMO pack. Service role only (edge function); no client access.
create table if not exists public.demo_pack_state (
  id            int primary key default 1 check (id = 1),
  last_burst_at timestamptz,
  next_index    int not null default 0
);
insert into public.demo_pack_state (id) values (1) on conflict do nothing;
alter table public.demo_pack_state enable row level security;
