# TGU Desktop v0.4.0 — RNMD ID (Telegram)

Android and Desktop use the same Supabase project and `custom:telegram` OIDC provider.
The Desktop app does not use Android's `app2482922030-login.tg.dev` App Link.
It starts a temporary HTTP callback on `127.0.0.1:18427` and opens system browser
against the existing Supabase OAuth endpoint using PKCE (S256).

## Required one-time configuration
In Supabase Dashboard for project `hscsswcevngohjdyetjo`:
**Authentication → URL Configuration → Redirect URLs → Add URL**

```
http://127.0.0.1:18427/callback
```

Use the *exact* URL. Keep existing Android `tgu://auth/callback` and Telegram
OIDC provider callback settings unchanged. Do not set the Desktop loopback
callback as Site URL. Do not add this address to Telegram BotFather: Telegram's
provider callback remains the existing Supabase `/auth/v1/callback`.

## Security and behavior
- TGU Free remains fully functional without an account.
- Browser uses `custom:telegram` with a locally generated, single-use PKCE S256 verifier.
- The callback listener binds to loopback only, has a three-minute deadline and
  checks the state if Supabase passes one; PKCE is the definitive binding.
- Supabase access/refresh tokens and expiry are stored in Windows Credential
  Manager (separate from proxy profiles), not React/localStorage.
- On refresh, the refresh token is rotated and stored; on sign-out local token
  storage is cleared, even if the server is offline.
- RLS-protected profile, devices, orders and entitlements are read with the
  current user's JWT and the public publishable Supabase key, never service_role.
- Usernames and avatars come from the same `profiles` rows as Android.
- No account tokens, authorization codes or PKCE verifiers should be logged.

## Smoke-test on Windows
1. Open RNMD ID icon (user silhouette) from the collapsed sidebar.
2. Continue with Telegram; browser should open and redirect back to loopback.
3. Verify name, profile, devices, purchases and TGU+ status.
4. Restart app: session must still be present and refresh correctly.
5. Sign out, restart app: account must be logged out; proxy/engine settings remain.
6. Switch networks: TGU still operates without RNMD ID.

If Supabase refuses redirection, verify the exact Redirect URL above. If the
browser cannot reach `127.0.0.1:18427`, inspect another process already owning
the port or the loopback firewall rule. The existing Android login is unaffected.
