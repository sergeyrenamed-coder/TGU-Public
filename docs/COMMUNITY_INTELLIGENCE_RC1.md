# TGU Community Intelligence — Android test build 0.9.7-rc1

This is an **unreleased test branch**. No public GitHub Release should be created until device/operator checks are complete.

## What changed

- Sending probe results is **off until explicitly enabled in Settings**. The legacy `feedback_enabled=true` default from older builds is ignored (even if that preference is present). An existing installation must give new explicit consent to start sending.
- Enabling feedback sends only catalog proxy IDs, protocol, test result, coarse error/latency, platform/app version, coarse network kind (Wi-Fi/cellular/Ethernet/unknown), a random installation UUID and a scan UUID. It does not send Telegram credentials, messages, phone number, operator identity, or device model. The API can observe the sender's network IP at request time; the feedback table uses an HMAC hash of the installation ID instead of storing that raw UUID.
- Foreground manual selection and background auto-selection/failover both contribute completed first-pass scan reports **only if the user explicitly opted in**. Turning consent off cancels queued feedback requests.
- When the catalog API supplies enough recent aggregate evidence (at least 3 independent reporters, 5 reports and 80% success), TGU first locally probes at most 20 strong candidates and verifies finalists again before applying an upstream.
- If this quick phase finds no stable route, or the catalog lacks trustworthy community fields, the original full-catalog scan remains available. Community results never skip local protocol verification.

## Scope and limitations

- The first prototype uses **global 24-hour community aggregates**, not per-operator or per-device network matching. Do not claim it knows which proxy works for a particular provider.
- It uses the existing RNMDSTORE Netlify Community API, backed by the TGU Proxy Directory catalog. If that API or its build-time URL is absent, GitHub Raw fallback remains available but no community fast path is activated.
- Background feedback does not start independent recurring scans. It only reports scans already performed by the existing selector.
- Fast-pass failures are not uploaded twice when the subsequent full scan retests the same proxies.
- Test branch versionCode 26 / versionName 0.9.7-rc1. The eventual production release must use a strictly higher versionCode.

## Device test checklist

1. Install the signed Android test APK from this branch's **TGU Android CI** workflow artifact (not the public Releases tab).
2. Upgrade from an older 0.9.6 installation; verify that the Community Feedback switch is **off** without user action.
3. Select/scan proxies with feedback off; confirm no POST to `?route=feedback`.
4. Turn feedback on manually, scan again and verify accepted reports in the API or development logs. Disable and repeat; ensure uploads stop.
5. Test Wi-Fi and cellular separately, including screen-off/background operation and recovery after network switching.
6. Confirm that a catalog without community fields triggers the full scan and that a failed community quick phase falls back to the full scan.
7. Verify Telegram connectivity, automatic failover, session recovery and battery use.

**Publishing rule:** `main` pushes affecting `android/**` trigger the existing TGU Releases workflow. Keep this branch unmerged while testing.
