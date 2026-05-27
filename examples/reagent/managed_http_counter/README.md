# managed_http_counter — Spec 014 managed-HTTP example

A counter where each button issues a managed HTTP request and the
reply lands back in `app-db` via the default reply-addressing path.
The compact worked example for [Spec 014
§`:rf.http/managed`](../../../spec/014-HTTPRequests.md).

## What this demonstrates

- **`:rf.http/managed`** — the canonical managed-HTTP fx. Each click
  dispatches an event whose `:fx` carries `:rf.http/managed` with a
  request envelope; the runtime owns the request lifecycle (in-flight
  tracking, abort, retry, reply addressing).
- **Default reply addressing** — successful responses land back in
  `app-db` at the default path with no per-call wiring required; the
  view subscribes to that path.
- **Mixed real / stubbed traffic** — the **+1** and **Fail** buttons
  exercise a REAL round-trip (Fetch hits a static asset or 404s); the
  **Retry-recover** and **Cancel** buttons exercise the canned-stub
  seam, which lets the example demonstrate the contract without
  needing a stub HTTP server.
- **`:rf.http/managed-abort`** — cancelling an in-flight request by
  `:request-id`.
- **Substrate** — reagent-slim (the catalogue default).

## Why this shape

The heavy Spec 014 contract testing lives in the JVM smoke
(`re-frame.http-managed-test`) and the conformance fixtures
(`spec/conformance/fixtures/http-managed-*.edn`). This example's role
is the cross-substrate sanity check: the same fx, the same reply
shape, end-to-end through Reagent + Fetch in a compact, browseable
demo.

The four buttons (success / failure / retry / cancel) cover the full
managed-HTTP surface in ~50 lines of dispatch logic — small enough to
keep in your head, complete enough to drive every code path.

## Files

```
managed_http_counter/
  core.cljs    — events, sub, view, mount, canned-stub override.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/managed-http-counter
```

Run `npm run test:examples` once first so
`out/examples/managed-http-counter/index.html` is staged. Examples are
test-free per [`examples/README.md`](../../README.md); managed-HTTP
contract coverage lives in `implementation/http/test/` and
`spec/conformance/fixtures/http-managed-*.edn`.

## Cross-references

- [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — the normative spec.
- [`spec/014-HTTPRequests.md` §Testing](../../../spec/014-HTTPRequests.md) — the canned-stub seam this example uses.
- [`spec/conformance/`](../../../spec/conformance/) — managed-HTTP fixtures.
- [`examples/reagent/login/`](../login/) — uses `:rf.http/managed` inside a state-machine feature.
