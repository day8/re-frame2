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
  **Retry-recover** button exercises the canned-stub seam, which lets
  the example demonstrate the success/retry contract without needing a
  stub HTTP server.
- **`:rf.http/managed-abort`** — cancelling an in-flight request by
  `:request-id`, demonstrated for real. **Start long** records a
  genuine request-id-keyed handle in the framework in-flight registry
  (the same atom the live transport's `run-attempt!` records into),
  whose `:abort-fn` dispatches the canonical `:rf.http/aborted` reply
  and clears the slot. **Cancel** fires the LIVE `:rf.http/managed-abort`
  fx, which resolves that handle and fires its `:abort-fn` — so the
  cancel path exercises the actual abort semantics, in-flight registry
  cleanup, and aborted classification end-to-end. Seeding a
  deterministic pending handle (rather than a real Fetch against a
  static dev-http server, which resolves instantly leaving nothing
  observably in-flight) is the proven testbed pattern — see
  [`tools/xray/testbeds/managed_http/core.cljs`](../../../tools/xray/testbeds/managed_http/core.cljs).
- **Substrate** — stock Reagent (`re-frame.adapter.reagent`), like the rest of the `examples/reagent/` catalogue.

## Why this shape

The heavy Spec 014 contract testing lives in the JVM smoke
(`re-frame.http-managed-test`) and the conformance fixtures
(`spec/conformance/fixtures/http-managed-*.edn`). This example's role
is the cross-substrate sanity check: the same fx, the same reply
shape, end-to-end through Reagent + Fetch in a compact, browseable
demo.

The five buttons (success / failure / retry / start-long / cancel)
cover the full managed-HTTP surface — small enough to keep in your
head, complete enough to drive every code path, including a real abort
of a real in-flight request.

## Files

```
managed_http_counter/
  core.cljs       — events, sub, view, mount, canned-stub seam, and the
                    seed-in-flight + live-abort demo.
  index.html      — minimal host page.
  api/inc.json    — `{"delta": 1}` static asset the +1 happy path fetches.
```

The **+1** button issues a REAL `GET api/inc.json`, so that file is a
runtime asset the served output dir must carry — it is fetched by the
running app, not referenced from `index.html`, so the static asset gate
(`examples/scripts/check-examples-assets.cjs`, which walks `index.html`
references) does not cover it. Stage it alongside `main.js` (see
[How to run](#how-to-run)).

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/managed-http-counter
```

The watch build emits `main.js` into
`out/examples/managed-http-counter/`; copy this folder's hand-written
[`index.html`](index.html) (and the shared assets it references under
[`../../_shared/`](../../_shared/)) alongside it, **and copy this
folder's [`api/`](api/) directory to
`out/examples/managed-http-counter/api/`** so the **+1** button's real
`GET api/inc.json` resolves (without it the headline success path 404s
and the example demonstrates the failure branch instead). Then serve
`out/examples/managed-http-counter/` over HTTP.
(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).) Examples are
test-free per [`examples/README.md`](../../README.md); managed-HTTP
contract coverage lives in `implementation/http/test/` and
`spec/conformance/fixtures/http-managed-*.edn`.

## Cross-references

- [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — the normative spec.
- [`spec/014-HTTPRequests.md` §Testing](../../../spec/014-HTTPRequests.md) — the canned-stub seam this example uses.
- [`spec/conformance/`](../../../spec/conformance/) — managed-HTTP fixtures.
- [`examples/reagent/login/`](../login/) — uses `:rf.http/managed` inside a state-machine feature.
