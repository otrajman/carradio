-- Relocate PostGIS from public to the extensions schema. This clears the
-- spatial_ref_sys RLS advisor ERROR and the postgis-in-public warnings at the
-- root instead of papering over them: once the extension's objects leave
-- public, the linter no longer sees an RLS-less table in an exposed schema.
-- PostGIS does not support ALTER EXTENSION ... SET SCHEMA, so the move is a
-- drop/recreate. The only app data of the geometry type is messages.location;
-- it is stashed as WKT across the drop (SRID is fixed at 4326 by the column
-- type, so plain WKT is lossless here).

drop index public.messages_location_gist;

alter table public.messages
  alter column location type text using st_astext(location);

drop extension postgis cascade;

create extension postgis with schema extensions;

alter table public.messages
  alter column location type extensions.geometry(Point, 4326)
  using extensions.st_geomfromtext(location, 4326);

create index messages_location_gist on public.messages using gist (location);

-- The RPC resolved st_* from public; now they live in extensions.
alter function public.get_breadcrumbs(uuid, double precision, double precision, double precision, integer, integer, text)
  set search_path = public, extensions;

-- supabase_admin's default privileges may hand anon/authenticated write grants
-- on the recreated spatial_ref_sys just as they did in public (postgres cannot
-- revoke another role's grants). If so, re-arm the statement-trigger guard on
-- the new table; otherwise the guard function has no remaining callers.
do $$
begin
  if has_table_privilege('anon', 'extensions.spatial_ref_sys', 'INSERT, UPDATE, DELETE')
     or has_table_privilege('authenticated', 'extensions.spatial_ref_sys', 'INSERT, UPDATE, DELETE') then
    create trigger spatial_ref_sys_guard
      before insert or update or delete on extensions.spatial_ref_sys
      for each statement execute function public.spatial_ref_sys_write_guard();
    create trigger spatial_ref_sys_guard_truncate
      before truncate on extensions.spatial_ref_sys
      for each statement execute function public.spatial_ref_sys_write_guard();
  else
    drop function public.spatial_ref_sys_write_guard();
  end if;
end $$;
