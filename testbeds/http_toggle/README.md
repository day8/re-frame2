# `testbeds/http-toggle`

A single Reagent button + outcome dropdown that drives a
`:rf.http/managed` request through one of the failure categories
in Spec 014 (plus the success path). One click + one selection emits
the corresponding `:rf.http/*` event(s) on the trace stream so a
consumer (Xray, Story, re-frame2-pair-mcp) verifies category attribution
end-to-end.

| Outcome | `data-testid` (option) | Strategy | Reply `:status` / `:error :kind` |
|---|---|---|---|
| 200 success | `:success` | Live Fetch against `api/success.json` (static asset shipped under the testbed dir). | `:status :ok`, `:value {:ok true :value "http-toggle success"}` |
| 4xx | `:rf.http/http-4xx` | Stub route answers `404` with a raw HTML body. | `:status :error`, `:error :kind :rf.http/http-4xx`, `:http-status 404`, `:body "<html>not found</html>"` |
| 5xx | `:rf.http/http-5xx` | Stub route answers `500` with a raw HTML body. | `:status :error`, `:error :kind :rf.http/http-5xx`, `:http-status 500`, `:body "<html>server error</html>"` |
| timeout | `:rf.http/timeout` | Stub route holds the request past its `:timeout-ms 1000`. | `:status :error`, `:error :kind :rf.http/timeout`, `:limit-ms 1000` |
| aborted | `:rf.http/aborted` | Stub route holds the request, so the spec can observe `:status :loading` and click Cancel, whose live `:rf.http/managed-abort` aborts it. | `:status :cancelled`, `:error :kind :rf.http/aborted` |
| transport | `:rf.http/transport` | Stub route fails as the network would. | `:status :error`, `:error :kind :rf.http/transport`, `:message "Network unreachable"`, `:cause "ECONNREFUSED"` |
| decode-failure | `:rf.http/decode-failure` | Stub route answers a 2xx whose body is not JSON. | `:status :error`, `:error :kind :rf.http/decode-failure`, `:body-text "<<not-json>>"` |
| cross-origin transport | `:http-toggle/cross-origin` | Stub route fails as Fetch does. The browser reports a CORS refusal and a network drop against another origin as the same Fetch `TypeError`, so both are `:rf.http/transport` with the CLJS-only `:cross-origin?` hint. | `:status :error`, `:error :kind :rf.http/transport`, `:message "Failed to fetch"`, `:cause "TypeError"`, `:cross-origin? true` |

## Why a stub transport for seven of eight

The seven failure outcomes go out through `failure-backend`, a stub
transport ([spec/014 §Testing](../../spec/014-HTTPRequests.md)). It
replaces only the network hop: each route answers the way a server or the
network would for its category, and the real `:rf.http/managed`
classifies it and delivers the canonical reply to the request's
`:reply-to` target. For a testbed whose job is to drive each category
deterministically — across CI environments that may not allow outbound
CORS or 503-flaky-endpoint requests — that is the canonical move, and the
reply shape consumers read (`:status :error`,
`:error :kind :rf.http/<category>`, the category-specific tags) is the
live one.

Selecting a failure outcome installs `failure-backend` as the frame's
transport, and selecting `:success` uninstalls it. Go is a plain dispatch
queued behind the selection, so it issues the selected outcome through
that outcome's backend however soon after the selection it is clicked.

The live success path is exercised because it's the simplest possible
real round-trip: an HTTP GET against a static asset under the same
origin. No CI environment refuses it.

## Trace shape per click (per [spec/009 §:op-type vocabulary](../../spec/009-Instrumentation.md))

Every failure click emits one category-attributed trace event
whose `:operation` is the failure `:kind`, from the live failure path in
`re-frame.http.transport`. It is an error row for every kind except the
aborted click, whose `:rf.http/aborted` row is `:info`:

```
:operation :rf.http/<:kind>    ;; :op-type :error, e.g. :rf.http/http-4xx
                               ;; (:op-type :info for :rf.http/aborted)
                               ;; :tags {:kind :rf.http/<kind>
                               ;;        :request-id ...
                               ;;        :url ...
                               ;;        :recovery :no-recovery
                               ;;        + category-specific tags}
```

The successful `:success` outcome emits no error trace.

The category-attribution scenario in rf2-fe84r reads: "HTTP failure
cascade visible as ordered `:rf.http/*` with category attribution" —
this testbed walks one outcome per dropdown entry, so the consumer's
spec can iterate the dropdown values and assert each emits its expected
`:kind` tag directly on the trace bus.

## What's deliberately missing

- no retry policy. The testbed pins each click to one attempt; the
  `:retry` slot is exercised by tools-side fixtures that need to assert
  on the multi-attempt cascade
- no `:accept` projection. The reply lands at `:value` (success path)
  or `:error` (failure paths) verbatim; consumers reading the
  envelope shape don't need `:accept` to mutate it
- no URL parameterisation. Every outcome uses a hard-coded URL so the
  trace's `:request :url` slot is deterministic across runs

## Test scenarios from rf2-fe84r this surface enables

Xray (26):
- HTTP failure cascade visible as ordered `:rf.http/*` with category attribution — the bread-and-butter scenario this surface exists to drive
- time-travel scrub forward/back mutates visible UI — every outcome produces a `:status` and `:reply` mutation observable on `data-testid="status"` and `data-testid="reply-status"`

Cross-cutting (6):
- HTTP failure cascade visible as ordered `:rf.http/*` with category attribution (the canonical cross-cutting scenario)

Story (18):
- recorder captures click → records `:play` → replays identically. The stub transport is deterministic; the live `:success` outcome may be skipped from a recorder replay (or stub-overridden) depending on the recording mode
- a11y panel surfaces violations on known-bad variant — out of scope here; covered by `tools/story/testbeds/counter_with_stories/` via the `:story.counter-matrix/a11y-known-bad` / `/a11y-known-good` variants (rf2-9jfo1.1 retired the standalone Tier-4 surface)

## Running

No gate runs this page. The derived compile sweep
(`node scripts/check-examples-compile.cjs` from `implementation/`)
compiles the `testbeds/http-toggle` build, and nothing stages or
serves it: the build has no `:dev-http` entry. To view it by hand,
from `implementation/`:

```bash
npm run dev -- :testbeds/http-toggle
```

The output lands in `implementation/out/testbeds/http-toggle/`. Serve
that `main.js` from one directory with this directory's `index.html`
and `api/`: the page loads `main.js` beside itself, and the `:success`
outcome fetches `api/success.json` from the same origin.

## Cross-references

- [`spec/014-HTTPRequests.md` §Failure categories](../../spec/014-HTTPRequests.md) — the closed-for-v1 vocabulary the dropdown enumerates.
- [`spec/014-HTTPRequests.md` §Classification order](../../spec/014-HTTPRequests.md) — status-before-decode is why `:rf.http/http-4xx` carries the raw `:body` and decode is skipped.
- [`spec/014-HTTPRequests.md` §Testing](../../spec/014-HTTPRequests.md) — the stub transport this testbed leans on for the seven failure outcomes.
- [`examples/core/managed_http_counter/`](../../examples/core/managed_http_counter/) — the tutorial-shaped companion to this testbed; demonstrates the canonical reply-addressing pattern in tutorial-grade prose.
