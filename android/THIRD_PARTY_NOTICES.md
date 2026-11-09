# Third-party notices

## TG WS Proxy Android — MTProto/WebSocket engine components

- Project: https://github.com/crim50n/tg-ws-proxy-android
- Copyright: Copyright (c) 2026 crim50n
- License: MIT
- Upstream revision: `558ffd6fe8206457aa15965e4f6e89f7c776a9c2`

Selected source components are adapted under `app/src/main/java/com/rnmd/tgu/proxy/`. TGU changes package names, Android service integration, encrypted local settings, diagnostic logging, DoH JSON parsing, and TLS verification. The upstream trust-all TLS configuration is removed; TGU uses Android’s system trust store with HTTPS hostname verification.

The MIT license text is included in `third_party/licenses/tg-ws-proxy-android-MIT.txt`.