-- Advisor-driven hardening: no listing of the public bucket (objects remain
-- fetchable by exact public URL only), and internal functions off the RPC surface.
drop policy voice_bursts_read on storage.objects;

revoke execute on function public.check_shadowban() from anon, authenticated, public;
