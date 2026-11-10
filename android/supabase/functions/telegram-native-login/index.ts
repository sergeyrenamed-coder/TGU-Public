import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const TELEGRAM_CLIENT_ID = "9135700460";
const TELEGRAM_REDIRECT_URI = "https://app2482922030-login.tg.dev/tglogin";
const TELEGRAM_CROSSAPP = "https://oauth.telegram.org/crossapp";
const TELEGRAM_TOKEN = "https://oauth.telegram.org/token";

const responseHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Cache-Control": "no-store",
  "Content-Type": "application/json; charset=utf-8",
};

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: responseHeaders });
}

function fail(message: string, status: number): Response {
  return json({ error: message }, status);
}

function validChallenge(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_-]{43,128}$/.test(value);
}

function validVerifier(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9._~-]{43,128}$/.test(value);
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: responseHeaders });
  if (req.method !== "POST") return fail("Method not allowed.", 405);

  const contentLength = Number(req.headers.get("content-length") ?? "0");
  if (Number.isFinite(contentLength) && contentLength > 16_384) {
    return fail("Request is too large.", 413);
  }

  let body: Record<string, unknown>;
  try {
    const parsed: unknown = await req.json();
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return fail("Invalid request body.", 400);
    }
    body = parsed as Record<string, unknown>;
  } catch {
    return fail("Invalid JSON.", 400);
  }

  if (body.action === "start") {
    if (!validChallenge(body.code_challenge)) return fail("Invalid PKCE challenge.", 400);

    const url = new URL(TELEGRAM_CROSSAPP);
    url.searchParams.set("client_id", TELEGRAM_CLIENT_ID);
    url.searchParams.set("response_type", "code");
    url.searchParams.set("scope", "openid profile");
    url.searchParams.set("redirect_uri", TELEGRAM_REDIRECT_URI);
    url.searchParams.set("android_sdk", "1");
    url.searchParams.set("code_challenge", body.code_challenge);
    url.searchParams.set("code_challenge_method", "S256");

    try {
      const upstream = await fetch(url, {
        headers: { "Accept": "application/json" },
        redirect: "error",
        signal: AbortSignal.timeout(12_000),
      });
      const upstreamText = await upstream.text();
      if (!upstream.ok) {
        console.warn("Telegram cross-app endpoint returned HTTP", upstream.status);
        return fail("Telegram could not start the native login flow.", 502);
      }

      let payload: Record<string, unknown>;
      try {
        payload = JSON.parse(upstreamText);
      } catch {
        return fail("Telegram returned an invalid login response.", 502);
      }
      const result = payload.result && typeof payload.result === "object"
        ? payload.result as Record<string, unknown>
        : {};
      const loginUrl = typeof payload.url === "string"
        ? payload.url
        : typeof result.url === "string"
          ? result.url
          : "";
      if (!loginUrl || loginUrl.length > 4_096) {
        return fail("Telegram did not provide a native login link.", 502);
      }

      let parsedUrl: URL;
      try {
        parsedUrl = new URL(loginUrl);
      } catch {
        return fail("Telegram returned an invalid native login link.", 502);
      }
      if (parsedUrl.protocol !== "tg:") {
        return fail("Telegram did not return an in-app Telegram deep link.", 502);
      }

      return json({ url: loginUrl });
    } catch (cause) {
      console.warn("Telegram cross-app request failed:", cause instanceof Error ? cause.name : "unknown");
      return fail("Telegram native login service is temporarily unreachable.", 502);
    }
  }

  if (body.action === "finish") {
    if (typeof body.code !== "string" || body.code.length < 1 || body.code.length > 8_192) {
      return fail("Invalid authorization code.", 400);
    }
    if (!validVerifier(body.code_verifier)) return fail("Invalid PKCE verifier.", 400);

    const form = new URLSearchParams({
      grant_type: "authorization_code",
      client_id: TELEGRAM_CLIENT_ID,
      code: body.code,
      redirect_uri: TELEGRAM_REDIRECT_URI,
      code_verifier: body.code_verifier,
    });

    try {
      const upstream = await fetch(TELEGRAM_TOKEN, {
        method: "POST",
        headers: {
          "Accept": "application/json",
          "Content-Type": "application/x-www-form-urlencoded",
        },
        body: form.toString(),
        redirect: "error",
        signal: AbortSignal.timeout(12_000),
      });
      const upstreamText = await upstream.text();
      let payload: Record<string, unknown>;
      try {
        payload = JSON.parse(upstreamText);
      } catch {
        console.warn("Telegram token endpoint returned non-JSON HTTP", upstream.status);
        return fail("Telegram could not complete the login exchange.", 502);
      }

      if (!upstream.ok) {
        console.warn("Telegram token exchange returned HTTP", upstream.status);
        return fail("Telegram rejected the login exchange. Please sign in again.", 401);
      }

      const idToken = payload.id_token;
      if (
        typeof idToken !== "string" ||
        idToken.split(".").length !== 3 ||
        idToken.length > 16_384
      ) {
        return fail("Telegram did not return a valid ID token.", 502);
      }

                                                                                                 
      return json({ id_token: idToken });
    } catch (cause) {
      console.warn("Telegram token exchange failed:", cause instanceof Error ? cause.name : "unknown");
      return fail("Telegram native login service is temporarily unreachable.", 502);
    }
  }

  return fail("Unsupported action.", 400);
});
