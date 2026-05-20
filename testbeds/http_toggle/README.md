# `testbeds/http-toggle`

A single Reagent button + outcome dropdown that drives a
`:rf.http/managed` request through one of the eight failure categories
in Spec 014 (plus the success path). One click + one selection emits
the corresponding `:rf.http/*` event(s) on the trace stream so a
consumer (Causa, Story, re-frame2-pair-mcp) verifies category attribution
end-to-end.

| Outcome | `data-testid` (option) | Strategy | Reply `:kind` / `:failure :kind` |
|---|---|---|---|
| 200 success | `:success` | Live Fetch against `api/success.json` (static asset shipped under the testbed dir). | `:kind :success`, `:value {:ok true :value "http-toggle success"}` |
| 4xx | `:rf.http/http-4xx` | Canned-failure stub with `:status 404`, raw HTML body. | `:kind :failure`, `:failure :kind :rf.http/http-4xx`, `:status 404`, `:body "<html>not found</html>"` |
| 5xx | `:rf.http/http-5xx` | Canned-failure stub with `:status 500`, raw HTML body. | `:kind :failure`, `:failure :kind :rf.http/http-5xx`, `:status 500`, `:body "<html>server error</html>"` |
| timeout | `:rf.http/timeout` | Canned-failure stub. | `:kind :failure`, `:failure :kind :rf.http/timeout`, `:elapsed-ms 5000`, `:limit-ms 5000` |
| aborted | `:rf.http/aborted` | Per-testbed `:http-toggle/deferred-abortable` stub defers a canned reply by 500ms so the spec can observe `:status :loading` and click Cancel. The Cancel button fires the live `:rf.http/managed-abort` fx. | `:kind :failure`, `:failure :kind :rf.http/aborted`, `:request-id ::in-flight`, `:reason :user` |
| transport | `:rf.http/transport` | Canned-failure stub. | `:kind :failure`, `:failure :kind :rf.http/transport`, `:message "Network unreachable"`, `:cause "ECONNREFUSED"` |
| decode-failure | `:rf.http/decode-failure` | Canned-failure stub (live decode-failure requires a JSON endpoint that 2xxs with invalid JSON; the stub preserves the reply envelope). | `:kind :failure`, `:failure :kind :rf.http/decode-failure`, `:body-text "<<not-json>>"`, `:cause "SyntaxError: ..."` |
| CORS | `:rf.http/cors` | Canned-failure stub. CLJS-only; the JVM never emits this category. | `:kind :failure`, `:failure :kind :rf.http/cors`, `:url "https://other.example/api/cors"` |

## Why canned-failure for seven of eight

The framework-shipped `:rf.http/managed-canned-failure` fx synthesises
the same `:rf/reply` envelope a live request would on each category
(per [spec/014 §Testing](../../spec/014-HTTPRequests.md) and the
JVM smokes in `re-frame.http-managed-test`). For a testbed whose job
is to drive each category deterministically — across CI environments
that may not allow outbound CORS or 503-flaky-endpoint requests —
the canned-stub seam is the canonical move. The reply shape consumers
read (`:kind :failure`, `:failure :kind :rf.http/<category>`, the
category-specific tags) is identical.

The live success path is exercised because it's the simplest possible
real round-trip: an HTTP GET against a static asset under the same
origin. No CI environment refuses it.

## Trace shape per click (per [spec/009 §:op-type vocabulary](../../spec/009-Instrumentation.md))

Every failure click emits one category-attributed error-trace event
whose `:operation` is the failure `:kind`, matching the live failure
path's emit from `re-frame.http-transport/finalise-failure!`:

```
:operation :rf.http/<:kind>    ;; :op-type :error, e.g. :rf.http/http-4xx
                               ;; :tags {:kind :rf.http/<kind>
                               ;;        :request-id ...
                               ;;        :url ...
                               ;;        :recovery :no-recovery
                               ;;        + category-specific tags}
```

For the seven canned-failure outcomes, the per-testbed
`:http-toggle/canned-failure-with-trace` fx replays this emit before
delegating to the framework's `:rf.http/managed-canned-failure` stub
(see [rf2-3g16l](../../.beads) — the framework stub bypasses
`trace/emit-error!`, so the testbed wraps it to preserve the live
path's contract). The successful `:success` outcome rides the live
transport path and emits no error trace.

The category-attribution scenario in rf2-fe84r reads: *"HTTP failure
cascade visible as ordered `:rf.http/*` with category attribution"* —
this testbed walks one outcome per dropdown entry, so the consumer's
spec can iterate the dropdown values and assert each emits its expected
`:kind` tag directly on the trace bus.

## What's deliberately *missing*

- No retry policy. The testbed pins each click to one attempt; the
  `:retry` slot is exercised by tools-side fixtures that need to assert
  on the multi-attempt cascade.
- No `:accept` projection. The reply lands at `:value` (success path)
  or `:failure` (failure paths) verbatim; consumers reading the
  envelope shape don't need `:accept` to mutate it.
- No URL parameterisation. Every outcome uses a hard-coded URL so the
  trace's `:request :url` slot is deterministic across runs.

## Test scenarios from rf2-fe84r this surface enables

**Causa (26)**:
- HTTP failure cascade visible as ordered `:rf.http/*` with category attribution — the bread-and-butter scenario this surface exists to drive.
- Time-travel scrub forward/back mutates visible UI — every outcome produces a `:status` and `:reply` mutation observable on `data-testid="status"` and `data-testid="reply-kind"`.

**Cross-cutting (6)**:
- HTTP failure cascade visible as ordered `:rf.http/*` with category attribution (the canonical cross-cutting scenario).

**Story (18)**:
- Recorder captures click → records `:play` → replays identically. The canned-stub seam is deterministic; the live `:success` outcome may be skipped from a recorder replay (or stub-overridden) depending on the recording mode.
- A11y panel surfaces violations on known-bad variant — out of scope here; covered by `tools/story/testbeds/counter_with_stories/` via the `:story.counter-matrix/a11y-known-bad` / `/a11y-known-good` variants (rf2-9jfo1.1 retired the standalone Tier-4 surface).

## Running

From `implementation/`:

```bash
shadow-cljs watch testbeds/http-toggle
# Or full orchestrator:
npm run test:examples
```

The shadow-cljs build id is `testbeds/http-toggle`; output lands in
`implementation/out/testbeds/http-toggle/`. The `api/success.json`
asset is staged next to `main.js` by the orchestrator (one
`extraFiles` entry in `serve-and-run-examples-tests.cjs`).

## Cross-references

- [`spec/014-HTTPRequests.md` §Failure categories](../../spec/014-HTTPRequests.md) — the closed-for-v1 vocabulary the dropdown enumerates.
- [`spec/014-HTTPRequests.md` §Classification order](../../spec/014-HTTPRequests.md) — status-before-decode is why `:rf.http/http-4xx` carries the raw `:body` and decode is skipped.
- [`spec/014-HTTPRequests.md` §Testing](../../spec/014-HTTPRequests.md) — the canned-failure stub seam this testbed leans on for the seven failure outcomes.
- [`examples/reagent/managed_http_counter/`](../../examples/reagent/managed_http_counter/) — the tutorial-shaped companion to this testbed; demonstrates the canonical reply-addressing pattern in tutorial-grade prose.
