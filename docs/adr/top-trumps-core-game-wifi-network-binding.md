# Sockets are created through injected factories bound to the Wi-Fi network

**Status:** Proposed
**Date:** 2026-07-30
**Feature:** [`top-trumps-core-game`](../features/top-trumps-core-game/TDD.md)

## Context

The PRD lists the risks to mDNS reliability as "client isolation, some mesh systems, and VPNs". It omits the failure most likely to be hit in an ordinary house.

A phone with both Wi-Fi and mobile data, where the Wi-Fi network has no internet — a captive portal, a misconfigured extender, or simply Android's "avoid bad Wi-Fi" heuristic deciding the connection is poor — sets the **default network to cellular**. A plain `Socket("192.168.1.42", port)` is then routed over the mobile interface and fails or hangs. Discovery may break the same way. An active VPN on either device produces the same class of failure, which is why the PRD's VPN risk has no mitigation beyond "use the manual fallback".

This interacts directly with the decision to put TCP in the pure-JVM `:core:session` module: pinning a socket to a specific `Network` is an Android-only operation.

## Decision

`:core:session` never constructs sockets directly. It accepts **injected `javax.net.SocketFactory` and `javax.net.ServerSocketFactory`**.

These are pure-JVM interfaces that exist unchanged on Android, so no Android type crosses the module boundary and the JVM-plugin discipline survives intact.

- **Production:** `:platform:net` obtains the Wi-Fi `Network` via `ConnectivityManager.registerNetworkCallback` with a `TRANSPORT_WIFI` request (needs only `ACCESS_NETWORK_STATE`, a normal permission) and supplies `wifiNetwork.socketFactory` — which already *is* a `javax.net.SocketFactory` — plus a `ServerSocketFactory` bound to the Wi-Fi link address.
- **Tests:** supply the platform defaults and use loopback.

On API 33+ the same `Network` is passed to `discoverServices`, pinning discovery too.

The manual-connect fallback (story 10) reads the device's own IPv4 from `ConnectivityManager.getLinkProperties(wifiNetwork).linkAddresses` — no `ACCESS_WIFI_STATE`, no location gate. Deprecated `WifiManager.getConnectionInfo().ipAddress` is not used.

## Alternatives considered

**Construct sockets directly in `:core:session`.** Simplest, and what the architecture review originally implied. Rejected because it makes the most likely connection failure unfixable — there is no way to reach the `Network` object from a module that cannot import `android.*`.

**Move all socket code into an Android module.** Would allow direct binding. Rejected because it surrenders the significant benefit of JVM-testable socket lifecycle and length-prefix framing against `127.0.0.1` — exactly where the classic partial-read and interleaved-write bugs live, and exactly what manual testing on a fast LAN fails to catch.

**`ConnectivityManager.bindProcessToNetwork(wifiNetwork)` for the whole process.** Simpler than threading factories through — and harmless here, since the app does no other networking. Kept as a viable fallback, but rejected as the primary mechanism because it is a global side effect that would be surprising to a future contributor, and because the factory approach keeps the binding explicit at the point of use. (Worth verifying whether it needs any additional permission before relying on it.)

## Consequences

**Easier:** the most probable connection failure is designed out rather than deferred to the manual fallback; the VPN risk the PRD raised is mitigated by the same mechanism; `:core:session` keeps both its socket logic and its Android-freedom.

**Harder:** two more constructor parameters and a `:platform:net` module that must resolve the Wi-Fi `Network` before a match can start — including handling the case where there is no Wi-Fi at all, which needs a clear error rather than a hang.

**Related socket rules this decision does not cover but which must accompany it:** `accept()` and `read()` do not respond to coroutine cancellation, so sockets must be closed from outside via `job.invokeOnCompletion`; writes must be serialised through a single writer coroutine, since two coroutines sharing an `OutputStream` will interleave frames; and `TCP_NODELAY` should be set, because Nagle plus delayed ACK adds 40ms+ per exchange to ~100-byte messages for no benefit.
