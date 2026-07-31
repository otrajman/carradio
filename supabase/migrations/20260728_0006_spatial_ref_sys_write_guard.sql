-- spatial_ref_sys is extension-owned (supabase_admin): postgres can neither
-- enable RLS on it nor revoke the anon/authenticated write grants that
-- supabase_admin's default privileges handed out. postgres does hold TRIGGER
-- privilege, so guard writes with a statement trigger instead. Reads stay
-- open (it is public reference data). supabase_admin/postgres are exempt so
-- PostGIS upgrades keep working.
create function public.spatial_ref_sys_write_guard()
returns trigger
language plpgsql
security definer
set search_path to 'pg_catalog'
as $$
begin
  if current_user not in ('postgres', 'supabase_admin') then
    raise insufficient_privilege using message = 'spatial_ref_sys is read-only';
  end if;
  return null;
end $$;

revoke execute on function public.spatial_ref_sys_write_guard() from anon, authenticated, public;

create trigger spatial_ref_sys_guard
  before insert or update or delete on public.spatial_ref_sys
  for each statement execute function public.spatial_ref_sys_write_guard();

create trigger spatial_ref_sys_guard_truncate
  before truncate on public.spatial_ref_sys
  for each statement execute function public.spatial_ref_sys_write_guard();
