# TGU Desktop

Windows desktop client for Telegram-only proxy routing.

## Stack
- Tauri 2 + Rust
- React + TypeScript + Vite
- Live TGU Proxy Directory snapshot with a local last-known-good JSON cache
- Windows Credential Manager for the local MTProto secret

## Development
Requirements: Node.js 20+, Rust stable, and the Tauri 2 prerequisites for Windows.

```bash
npm install
npm run tauri dev
```

## Current milestone — 0.3.0: local MTProto relay

- Starts a loopback-only MTProto endpoint at `127.0.0.1:1443`; it does not require administrator rights or create a system-wide VPN adapter.
- Creates a random 16-byte local MTProxy secret and stores it in Windows Credential Manager. The same local `tg://proxy` link remains valid after restarting TGU.
- Parses Telegram's obfuscated2 handshake, extracts the data-center and media flags, and maintains separate AES-CTR streams for the local client and upstream.
- Supports classic MTProto upstream profiles, including the `dd` padded-intermediate marker, and SOCKS5 with optional username/password authentication.
- Uses Telegram WebSocket (`/apiws`) for supported direct redirect routes, then a refreshed Cloudflare WebSocket domain pool with DNS-over-HTTPS fallback, then direct Telegram DC TCP.
- A selected external MTProto or SOCKS5 upstream is exclusive. TGU does not silently send that session over a different route if the selected upstream fails.
- Public catalogue endpoints and DNS results are screened to reject loopback, private, link-local, reserved and local-only destinations before TGU opens outbound sockets.
- Runs device-side Telegram DC2 protocol verification before adding an upstream to the local relay.
- Auto-selection scans the supported catalogue from this computer in batches of 120, with at most 24 simultaneous probes. If a batch has no reachable candidates or none passes the repeat-stability check, the next batch is tested; up to five fastest successful candidates are rechecked before selection.
- The selected-upstream health monitor checks every 60 seconds. After three consecutive failures it searches up to 48 cached alternatives with bounded concurrency and switches new Telegram sessions to the fastest verified replacement.
- Existing TCP/WebSocket sessions are not transparently migrated. When an upstream changes or fails, Telegram may need to reconnect; active sessions are not guaranteed to survive a failover.
- Responds to WebSocket PING frames with PONG and splits MTProto packets before sending WebSocket binary frames.
- Keeps listener readiness, upstream health and Telegram DC response evidence separate. A Telegram DC response is not proof that a Telegram account has logged in.

## Routing behavior and limitations

**AUTO transport route:** direct Telegram WebSocket for the currently supported redirect DCs (DC2/DC4), then the Cloudflare WebSocket domain pool, then direct TCP to the requested Telegram DC. Each new Telegram session evaluates this chain independently.

**Auto-selected upstream:** TGU scans supported MTProto/SOCKS5 catalogue entries from the current network, rechecks the best candidates and stores the selected profile in process memory. If TGU is fully restarted, run auto-selection again or select a profile manually; the local MTProto secret itself remains stored in Windows Credential Manager.

**Selected upstream:** a selected classic MTProto or SOCKS5 profile is used exclusively. If it fails three health checks, TGU searches the local catalogue for a verified replacement. The failover changes the route for new sessions; existing Telegram sessions may reconnect.

- FakeTLS/WEB MTProto secrets beginning with `ee` are not supported yet.
- Public proxy availability is volatile and depends on the user's ISP and current network. A catalogue server-side check is not a guarantee that the same endpoint works from this computer.
- If all available proxy and WebSocket routes are blocked by the network, TGU cannot manufacture connectivity.
- TGU is a Telegram-only proxy relay, not a system-wide VPN. Other applications are not routed through it.
- Persistent selected-profile storage, SQLite history/favorites, system tray lifecycle, background startup configuration and installer signing remain future work.

**Status semantics:** `Engine Ready` means the loopback listener is bound. `Upstream Healthy` means the most recent upstream route handshake/health check succeeded. `Telegram DC Responding` is set only after data arrives from the Telegram upstream; it is not proof of account login or chat connectivity.

## Third-party notices
The WebSocket bridge behavior was ported and adapted from the TGU Android implementation. The MIT notice for the included `tg-ws-proxy-android` component is preserved in [third_party/licenses/tg-ws-proxy-android-MIT.txt](third_party/licenses/tg-ws-proxy-android-MIT.txt).

The Cloudflare WebSocket domain pool is refreshed from the public Flowseal `tg-ws-proxy` domain list. TGU validates domain syntax and uses the list only as connection destinations; it does not execute downloaded code or configuration.

## Brand asset source
The app icon is based on the approved TGU Android icon in [Figma](https://www.figma.com/design/KK0sJomhlFXfcwOhPqAt8O/Untitled?node-id=0-1&t=9booWnLjQ6gKG5UA-1).

## TGU Community Network

- Loads the community proxy catalog from the RNMDSTORE Netlify API first, with the canonical GitHub Raw catalog as a fallback.
- Community health is supplemental to local proxy checks; the desktop client keeps probing routes locally and only uses community ranking when enough distinct reporters are available.
- Anonymous community feedback is opt-in and disabled by default. When enabled, the client may submit proxy probe outcomes; it does not submit Telegram messages, account credentials, or phone numbers.
- The API base URL is supplied to CI through the `TGU_COMMUNITY_API_BASE_URL` repository variable.
