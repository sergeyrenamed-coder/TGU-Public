-- Avoid PL/pgSQL ambiguity between the OUT parameter device_key and
-- the public.devices.device_key column in the ON CONFLICT target.
CREATE OR REPLACE FUNCTION public.register_tgu_device(
    p_device_key uuid,
    p_device_name text DEFAULT NULL,
    p_app_version text DEFAULT NULL
)
RETURNS TABLE(
    id uuid,
    device_key uuid,
    device_name text,
    platform text,
    app_version text,
    last_seen_at timestamptz,
    revoked_at timestamptz
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
        user_id,
        device_key,
        device_name,
        platform,
        app_version,
        auth_session_id,
        last_seen_at,
        revoked_at
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
    ON CONFLICT ON CONSTRAINT devices_user_id_device_key_key DO UPDATE
    SET
        device_name = COALESCE(EXCLUDED.device_name, stored.device_name),
        platform = 'android',
        app_version = COALESCE(EXCLUDED.app_version, stored.app_version),
        auth_session_id = EXCLUDED.auth_session_id,
        last_seen_at = now(),
        revoked_at = NULL;

    RETURN QUERY
    SELECT
        d.id,
        d.device_key,
        d.device_name,
        d.platform,
        d.app_version,
        d.last_seen_at,
        d.revoked_at
    FROM public.devices AS d
    WHERE d.user_id = v_user_id
      AND d.device_key = p_device_key
    LIMIT 1;
END;
$function$;
