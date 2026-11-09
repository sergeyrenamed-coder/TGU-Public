-- Move auth-session SECURITY DEFINER helpers out of PostgREST's exposed public schema.
-- Keep only a narrow SECURITY INVOKER RPC wrapper public, and index the session FK.

CREATE SCHEMA IF NOT EXISTS private;
REVOKE ALL ON SCHEMA private FROM PUBLIC, anon;
GRANT USAGE ON SCHEMA private TO authenticated;

CREATE OR REPLACE FUNCTION private.tgu_auth_session_is_active()
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path TO ''
AS $function$
    SELECT EXISTS (
        SELECT 1
        FROM auth.sessions AS active_session
        WHERE active_session.id = NULLIF(auth.jwt() ->> 'session_id', '')::uuid
          AND active_session.user_id = auth.uid()
    );
$function$;

REVOKE ALL ON FUNCTION private.tgu_auth_session_is_active() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.tgu_auth_session_is_active() TO authenticated;

DROP POLICY IF EXISTS profiles_select_own ON public.profiles;
CREATE POLICY profiles_select_own ON public.profiles
FOR SELECT TO authenticated
USING (id = (SELECT auth.uid()) AND private.tgu_auth_session_is_active());

DROP POLICY IF EXISTS devices_select_own ON public.devices;
CREATE POLICY devices_select_own ON public.devices
FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()) AND private.tgu_auth_session_is_active());

DROP POLICY IF EXISTS devices_insert_own ON public.devices;
CREATE POLICY devices_insert_own ON public.devices
FOR INSERT TO authenticated
WITH CHECK (user_id = (SELECT auth.uid()) AND private.tgu_auth_session_is_active());

DROP POLICY IF EXISTS devices_update_own ON public.devices;
CREATE POLICY devices_update_own ON public.devices
FOR UPDATE TO authenticated
USING (user_id = (SELECT auth.uid()) AND private.tgu_auth_session_is_active())
WITH CHECK (user_id = (SELECT auth.uid()) AND private.tgu_auth_session_is_active());

DROP POLICY IF EXISTS orders_select_own ON public.orders;
CREATE POLICY orders_select_own ON public.orders
FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()) AND private.tgu_auth_session_is_active());

DROP POLICY IF EXISTS entitlements_select_own ON public.entitlements;
CREATE POLICY entitlements_select_own ON public.entitlements
FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()) AND private.tgu_auth_session_is_active());

CREATE OR REPLACE FUNCTION private.revoke_tgu_device(p_device_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
    v_user_id uuid;
    v_current_session_id uuid;
    v_target_session_id uuid;
BEGIN
    v_user_id := auth.uid();
    v_current_session_id := NULLIF(auth.jwt() ->> 'session_id', '')::uuid;

    IF v_user_id IS NULL OR v_current_session_id IS NULL THEN
        RAISE EXCEPTION 'Authenticated session required' USING errcode = '28000';
    END IF;

    IF p_device_id IS NULL THEN
        RAISE EXCEPTION 'Device ID is required' USING errcode = '22023';
    END IF;

    SELECT d.auth_session_id
    INTO v_target_session_id
    FROM public.devices AS d
    WHERE d.id = p_device_id
      AND d.user_id = v_user_id
      AND d.revoked_at IS NULL
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN false;
    END IF;

    IF v_target_session_id = v_current_session_id THEN
        RAISE EXCEPTION 'The current device cannot revoke its own session'
            USING errcode = '22023';
    END IF;

    UPDATE public.devices
    SET revoked_at = now()
    WHERE id = p_device_id
      AND user_id = v_user_id
      AND revoked_at IS NULL;

    IF v_target_session_id IS NOT NULL THEN
        DELETE FROM auth.sessions AS stored_session
        WHERE stored_session.id = v_target_session_id
          AND stored_session.user_id = v_user_id;
    END IF;

    RETURN true;
END;
$function$;

REVOKE ALL ON FUNCTION private.revoke_tgu_device(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.revoke_tgu_device(uuid) TO authenticated;

CREATE OR REPLACE FUNCTION public.revoke_tgu_device(p_device_id uuid)
RETURNS boolean
LANGUAGE sql
SECURITY INVOKER
SET search_path TO ''
AS $function$
    SELECT private.revoke_tgu_device(p_device_id);
$function$;

REVOKE ALL ON FUNCTION public.revoke_tgu_device(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.revoke_tgu_device(uuid) TO authenticated;

DROP FUNCTION IF EXISTS public.tgu_auth_session_is_active();

CREATE INDEX IF NOT EXISTS devices_auth_session_id_idx
ON public.devices(auth_session_id);
