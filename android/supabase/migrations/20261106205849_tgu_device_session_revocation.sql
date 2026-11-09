-- Bind every RNMD ID device record to the Supabase Auth session that registered it.
-- Deleting a device session invalidates refresh tokens; RLS also blocks its still-live
-- access token from reading account data after the session row has been removed.

ALTER TABLE public.devices
    ADD COLUMN IF NOT EXISTS auth_session_id uuid
    REFERENCES auth.sessions(id) ON DELETE SET NULL;

CREATE OR REPLACE FUNCTION public.tgu_auth_session_is_active()
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

REVOKE ALL ON FUNCTION public.tgu_auth_session_is_active() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.tgu_auth_session_is_active() TO authenticated;

CREATE OR REPLACE FUNCTION public.register_tgu_device(
    p_device_key uuid,
    p_device_name text DEFAULT NULL::text,
    p_app_version text DEFAULT NULL::text
)
RETURNS TABLE(
    id uuid,
    device_key uuid,
    device_name text,
    platform text,
    app_version text,
    last_seen_at timestamp with time zone,
    revoked_at timestamp with time zone
)
LANGUAGE plpgsql
SET search_path TO ''
AS $function$
DECLARE
    v_user_id uuid;
    v_session_id uuid;
BEGIN
    v_user_id := auth.uid();
    v_session_id := NULLIF(auth.jwt() ->> 'session_id', '')::uuid;

    IF v_user_id IS NULL OR v_session_id IS NULL THEN
        RAISE EXCEPTION 'Authenticated session required' USING errcode = '28000';
    END IF;

    IF p_device_key IS NULL THEN
        RAISE EXCEPTION 'Device key is required' USING errcode = '22023';
    END IF;

    INSERT INTO public.devices AS stored (
        user_id, device_key, device_name, platform, app_version,
        auth_session_id, last_seen_at, revoked_at
    )
    VALUES (
        v_user_id,
        p_device_key,
        NULLIF(LEFT(BTRIM(COALESCE(p_device_name, '')), 120), ''),
        'android',
        NULLIF(LEFT(BTRIM(COALESCE(p_app_version, '')), 32), ''),
        v_session_id,
        now(),
        NULL
    )
    ON CONFLICT (user_id, device_key) DO UPDATE
    SET
        device_name = COALESCE(EXCLUDED.device_name, stored.device_name),
        platform = 'android',
        app_version = COALESCE(EXCLUDED.app_version, stored.app_version),
        auth_session_id = EXCLUDED.auth_session_id,
        last_seen_at = now(),
        revoked_at = NULL;

    RETURN QUERY
    SELECT d.id, d.device_key, d.device_name, d.platform,
           d.app_version, d.last_seen_at, d.revoked_at
    FROM public.devices AS d
    WHERE d.user_id = v_user_id
      AND d.device_key = p_device_key
    LIMIT 1;
END;
$function$;

CREATE OR REPLACE FUNCTION public.revoke_tgu_device(p_device_id uuid)
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

REVOKE ALL ON FUNCTION public.register_tgu_device(uuid, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.register_tgu_device(uuid, text, text) TO authenticated;
REVOKE ALL ON FUNCTION public.revoke_tgu_device(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.revoke_tgu_device(uuid) TO authenticated;

DROP POLICY IF EXISTS profiles_select_own ON public.profiles;
CREATE POLICY profiles_select_own ON public.profiles
FOR SELECT TO authenticated
USING (id = (SELECT auth.uid()) AND public.tgu_auth_session_is_active());

DROP POLICY IF EXISTS devices_select_own ON public.devices;
CREATE POLICY devices_select_own ON public.devices
FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()) AND public.tgu_auth_session_is_active());

DROP POLICY IF EXISTS devices_insert_own ON public.devices;
CREATE POLICY devices_insert_own ON public.devices
FOR INSERT TO authenticated
WITH CHECK (user_id = (SELECT auth.uid()) AND public.tgu_auth_session_is_active());

DROP POLICY IF EXISTS devices_update_own ON public.devices;
CREATE POLICY devices_update_own ON public.devices
FOR UPDATE TO authenticated
USING (user_id = (SELECT auth.uid()) AND public.tgu_auth_session_is_active())
WITH CHECK (user_id = (SELECT auth.uid()) AND public.tgu_auth_session_is_active());

DROP POLICY IF EXISTS orders_select_own ON public.orders;
CREATE POLICY orders_select_own ON public.orders
FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()) AND public.tgu_auth_session_is_active());

DROP POLICY IF EXISTS entitlements_select_own ON public.entitlements;
CREATE POLICY entitlements_select_own ON public.entitlements
FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()) AND public.tgu_auth_session_is_active());
