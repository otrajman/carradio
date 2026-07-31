-- Guard must be SECURITY INVOKER: under DEFINER, current_user is the function
-- owner (postgres) and the check always passed. Trigger functions fire
-- regardless of EXECUTE privilege, so invoker is safe here.
create or replace function public.spatial_ref_sys_write_guard()
returns trigger
language plpgsql
security invoker
set search_path to 'pg_catalog'
as $$
begin
  if current_user not in ('postgres', 'supabase_admin') then
    raise insufficient_privilege using message = 'spatial_ref_sys is read-only';
  end if;
  return null;
end $$;
