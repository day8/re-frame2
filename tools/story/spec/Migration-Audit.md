# Playwright → CLJS migration audit (rf2-tglku audit phase)

Filed: 2026-05-20 14:21:34 AUSEST · Bead: **rf2-tglku** (P2 epic) · Audit only — no source-file edits.

## Brief

Mike directive 2026-05-20: move the substantive assertions out of the Playwright gates into CLJS unit tests + adapter testbed smokes. Playwright residual = whatever genuinely needs a real browser. This doc inventories every `*.spec.cjs` file driven by the per-PR Story/Causa Playwright gates, classifies every assertion A/B/C, proposes per-spec migration target files, and recommends a post-migration gate posture.

Classification key:
- **(A) Pure data flow / CLJS logic** — assertion is about app-db state, event-handler outcome, sub computation, machine state, flow output, trace-bus contents, epoch-history records. Migratable to `*_test.cljs` under the owning artefact's test dir using the multi-frame CLJS e2e helpers from rf2-7icrs (no browser needed).
- **(B) Substrate mounting / hooks behaviour** — assertion is about substrate adapter API: r/atom subscription order, UIx hook fire timing, Helix hook re-render semantics. Migratable to extending the 3 adapter testbed smokes (`implementation/adapters/<reagent|uix|helix>/testbed/spec.cjs`).
- **(C) Visual chrome / CSS / layout / real-browser API** — assertion requires actual DOM rendering, focus management, browser keyboard events, screenshot diff, or browser-only APIs (URL, localStorage, IndexedDB, IntersectionObserver, hash-routing, History API). STAYS Playwright.

## §Inventory

| File | LoC | Surface | Asserts (count) |
|---|---:|---|---:|
| `implementation/adapters/helix/testbed/spec.cjs` | 24 | Helix adapter smoke | 3 |
| `implementation/adapters/reagent/testbed/spec.cjs` | 23 | Reagent adapter smoke | 3 |
| `implementation/adapters/uix/testbed/spec.cjs` | 24 | UIx adapter smoke | 3 |
| `tools/causa/testbeds/parallel_frames/spec.cjs` | 277 | Causa multi-frame isolation | 27 |
| ~~`tools/causa/testbeds/perf_counter/spec.cjs`~~ DELETED Wave 4 (rf2-e3j8l) → `implementation/core/test/re_frame/performance_emit_nightly_test.cljs` | ~~103~~ | Spec 009 perf User-Timing (nightly CLJS) | ~~6~~ |
| `testbeds/deep_machine/spec.cjs` | 149 | Spec 005 machine transition cascade | 5 |
| `testbeds/deliberate_throw/spec.cjs` | 116 | Spec 009 handler-exception trace | 4 |
| `testbeds/drain_depth_trigger/spec.cjs` | 164 | Spec 002 drain-depth rollback + epoch record | 7 |
| `testbeds/http_toggle/spec.cjs` | 110 | Spec 014 :rf.http/* category attribution | 6 (×3 categories = 18 obs) |
| `testbeds/long_flow_w_failure/spec.cjs` | 172 | Spec 013 flow four-rule failure | 6 |
| `testbeds/non_trivial_app_db/spec.cjs` | 159 | Spec 009 event/dispatched < event/db-changed ordering | 4 |
| ~~`testbeds/ssr_basic/spec.cjs`~~ DELETED Wave 3 (rf2-pxb7t) → `implementation/ssr/test/re_frame/ssr_hydration_test.clj` | ~~143~~ | Spec 011 hydration baseline | ~~13~~ |
| ~~`testbeds/ssr_hydration_mismatch/spec.cjs`~~ DELETED Wave 3 (rf2-pxb7t) → `implementation/ssr/test/re_frame/ssr_hydration_mismatch_test.clj` | ~~136~~ | Spec 011 :rf.ssr/hydration-mismatch | ~~9~~ |
| ~~`testbeds/ssr_multi_frame/spec.cjs`~~ DELETED Wave 3 (rf2-pxb7t) → `implementation/ssr/test/re_frame/ssr_multi_frame_isolation_test.clj` | ~~115~~ | Spec 011 per-frame hydration isolation | ~~16~~ |
| **TOTAL** | **1715** |  | **107** |

Assertion count = distinct observable checks (DOM reads compared with explicit value + `expectVisible` + bus/history regex finds + window.evaluate result checks). `http_toggle` loops 3 categories ×6 assertions per iteration → counted as 18 observations.

## §Per-spec classification

### `implementation/adapters/helix/testbed/spec.cjs` (helix adapter smoke)
| # | Assertion | Class | Rationale + target |
|---:|---|:---:|---|
| 1 | `expectVisible(banner)` | B | Substrate mount probe; already in canonical adapter smoke. KEEP IN PLACE. |
| 2 | `expectTextEquals(counter, '0')` | B | Sub→view re-render under Helix hooks. KEEP IN PLACE. |
| 3 | `expectTextEquals(counter, '1')` after click | B | Click→dispatch→sub→hook re-render. KEEP IN PLACE. |

**Recommendation:** No migration. These 3 ARE the (B) bucket — adapter-level mount+dispatch+assert. Stay as-is.

### `implementation/adapters/reagent/testbed/spec.cjs` (reagent adapter smoke)
Same shape as helix. All 3 assertions = (B). KEEP IN PLACE.

### `implementation/adapters/uix/testbed/spec.cjs` (uix adapter smoke)
Same shape as helix. All 3 assertions = (B). KEEP IN PLACE.

### `tools/causa/testbeds/parallel_frames/spec.cjs` (Causa multi-frame demo) — MIGRATED (Wave 2, rf2-lcg1z)

**Status: spec.cjs DELETED.** Multi-frame isolation contract migrated to `implementation/core/test/re_frame/multi_frame_isolation_cljs_test.cljs` (per-PR via the standard `:node-test` build). The testbed surface (`core.cljs`, `index.html`, `README.md`) stays in-tree as the canonical Causa-displayable multi-frame demo. The Causa-side target-frame round-trip + L2 frame-scoped filter assertions were already covered ahead of this migration by `tools/causa/test/.../panels_e2e/parallel_frames_e2e_cljs_test.cljs` (rf2-ulpp8 / rf2-1p1j4) and `multi_frame_isolation_e2e_cljs_test.cljs` (cross-frame fan-out via fx).

| # | Assertion | Class | Rationale + target |
|---:|---|:---:|---|
| 1 | `expectVisible(parallel-frames-root)` | C | Page-mount sanity; cheap, keep at Playwright level since this is the gate's smoke entry. |
| 2 | `expectVisible(above-panel)` + `below-panel` | C | Two-frame chrome layout — only meaningful with real DOM mount. STAYS. |
| 3 | `expectTextEquals(above-counter-value, '0')` initial | A | Sub readout against frame-isolated app-db. → `tools/causa/test/.../parallel_frames_isolation_cljs_test.cljs` (new file) using e2e_multi_frame helpers. |
| 4 | `expectTextEquals(below-counter-value, '0')` initial | A | Same as #3. → same target. |
| 5 | `expectTextEquals(above-clock-ticks, '0 ticks')` | A | Initial frame-state readout. → same target. |
| 6 | `expectTextEquals(below-clock-ticks, '0 ticks')` | A | Same. → same target. |
| 7 | `expectTextEquals(above-title-state, ':idle')` | A | Machine initial state per frame. → same target. |
| 8 | `expectTextEquals(below-title-state, ':idle')` | A | Same. → same target. |
| 9 | After 3× above-inc click: above-counter = '3' AND below-counter = '0' | A | The canonical counter-isolation contract. → same target — `(dispatch [::inc])` against frame `:above` × 3 then `(rf.get-frame-db :above) :counter` = 3 and `(rf.get-frame-db :below) :counter` = 0. |
| 10 | After below-inc click: above stays 3, below = 1 | A | Same canonical isolation. → same target. |
| 11 | After 2× above-tick: above-ticks=2, below-ticks=0 | A | Clock-tick handler resolves against originating frame. → same target. |
| 12 | After below-tick: above stays 2, below=1 | A | Same. → same target. |
| 13 | After below-refresh: below-title-state = ':loaded' (5s poll) | A | HTTP-mock fx round-trip into machine. → same target (mock the fx; assert state transition synchronously). |
| 14 | After below-refresh: above-title-state stays ':idle' | A | HTTP isolation. → same target. |
| 15 | below-title-value starts with 'Parallel-Frames @ ' | A | Reply payload writes onto frame's :title slot. → same target. |
| 16 | After above-force-error: above-title-state = ':error' | A | Error fx path through machine. → same target. |
| 17 | After above-force-error: below-title-state stays ':loaded' | A | Same isolation. → same target. |
| 18 | above-title-value includes 'ERROR:' | A | Error-payload write. → same target. |
| 19 | `:rf.causa/set-target-frame` round-trip on :above | A | Causa-side event + sub roundtrip. → `tools/causa/test/.../target_frame_roundtrip_cljs_test.cljs` (Causa is already heavy in panels_e2e CLJS tests; extend that surface). |
| 20 | Same on :below | A | Same. → same target. |
| 21 | Reset away from host frames on nil | A | Same. → same target. |
| 22 | `(rf.get-frame-db :above) :counter` = 3 | A | Same as #9 underlying contract. → same target as #9 (avoid dual coverage). |
| 23 | `(rf.get-frame-db :below) :counter` = 1 | A | Same as #10. → same target. |
| 24 | `:rf/causa` carries no `:counter` leak | A | Cross-frame app-db isolation (host → Causa direction of Spec 008 §State isolation). → `tools/causa/test/.../embedding_contract_isolation_cljs_test.cljs` (new file). |
| 25 | `expectVisible` page banner timing | C | DOM mount; keep at Playwright. STAYS (subsumed by #1/#2). |
| 26 | Polling DOM mirror reflects post-dispatch state | C-residual | Trivial — proves substrate re-renders. After A-migrations land, the 3 substrate-smoke adapters already cover this. DROP from this spec post-migration. |
| 27 | Polling timing for async fx (600ms HTTP-mock delay) | A | Same as #13 — handled by sync mock + immediate assert in CLJS. → same target. |

**Migrated subtotal: 23 of 27 = 85% to (A). Residual: 2-3 (B/C) — drop spec.cjs to a thin mount-smoke covering #1/#2 + one isolation eyeball (the counter-isolation 3×click + readback).**

**Proposed CLJS target files:**
- `tools/causa/testbeds/parallel_frames/test/.../parallel_frames_isolation_cljs_test.cljs` — assertions 3-18, 22-23, 27 (per-frame app-db isolation, counter/tick/HTTP-mock/error fan-out).
- `tools/causa/test/day8/re_frame2_causa/panels_e2e/target_frame_roundtrip_e2e_cljs_test.cljs` — assertions 19-21 (Causa's own `:rf.causa/set-target-frame` event + `:rf.causa/target-frame` sub).
- `tools/causa/test/day8/re_frame2_causa/panels_e2e/embedding_contract_isolation_e2e_cljs_test.cljs` — assertion 24 (Spec 008 §State isolation host→Causa direction).

### `tools/causa/testbeds/perf_counter/spec.cjs` (Spec 009 perf User-Timing) — MIGRATED (Wave 4, rf2-e3j8l)

**Status: spec.cjs DELETED. Migrated to `implementation/core/test/re_frame/performance_emit_nightly_test.cljs`** with a companion shadow-cljs build (`:node-test-perf-nightly`) that flips `re-frame.performance/enabled?` on at compile time. The test ns ends in `-emit-nightly-test`; the per-PR `:node-test` build's `:ns-regexp "cljs-test$"` does NOT match it, so it stays NIGHTLY ONLY (Mike's call — perf-timing assertions are too noisy under per-PR runner load). Invocation: `npm run test:cljs-perf-emit-nightly`.

| # | Assertion | Class | Migrated to |
|---:|---|:---:|---|
| 1 | `expectTextEquals(span, '5')` initial | A | DROP — covered by existing counter unit tests; this surface was incidental wiring. |
| 2 | `expectTextEquals(span, '6')` after click | A | DROP — same. |
| 3 | `expectTimingBucket(buckets, 'event')` ≥ 1 | A | `dispatch-emits-rf-event-measure-when-perf-enabled` (Node's `performance.getEntriesByType('measure')` is API-compatible with the browser's; the runtime call site in `re-frame.router/run-chain` is identical across CLJS targets). |
| 4 | `expectTimingBucket(buckets, 'sub')` ≥ 1 | A | `subscribe-emits-rf-sub-measure-when-perf-enabled`. |
| 5 | `expectTimingBucket(buckets, 'fx')` ≥ 1 | A | `fx-walk-emits-rf-fx-measure-when-perf-enabled`. |
| 6 | `expectTimingBucket(buckets, 'render')` ≥ 1 | A | Macro shape covered by `performance-cljs-test/build-name-shape` (naming convention) + `mark-and-measure-on-path-when-enabled` (per-call emission). The view-render call site itself is structurally identical to the other three — no Reagent-render integration is added in the nightly suite (driving Reagent in node would require react-dom server, which is browser-render-orthogonal). The bundle-presence assertion in `scripts/check-perf-bundle.cjs` retains the prod-build sentinel check. |

**Migrated subtotal: 6 of 6 = 100%. Residual: 0. SPEC.CJS DELETED.** The integration assertion (one dispatch populates all three headless buckets in a single drain) lives in `single-dispatch-populates-all-three-headless-buckets`.

### `testbeds/deep_machine/spec.cjs` (Spec 005 machine transition cascade)
| # | Assertion | Class | Rationale + target |
|---:|---|:---:|---|
| 1 | `expectVisible(deep-machine)` | C | Page-mount sanity. STAYS as thin smoke. |
| 2 | `work-state` = ':idle' pre-click | A | Sub off `:rf/machine` reads initial state. → `implementation/machines/test/.../deep_machine_cascade_cljs_test.cljs` (new file, or extend `machines_hierarchical_cljs_test.cljs`). |
| 3 | After `:work/go` click: work-state ≠ ':idle' (poll) | A | 5-level descent transition fires. → same target. |
| 4 | `:rf.machine/transition` AND `:rf.machine/snapshot-updated` on trace bus | A | Trace-emit observation. Covered by existing machine-trace tests; extend if not already. → `implementation/machines/test/.../machines_trace_emit_cljs_test.cljs` (likely already exists per `flows_trace_emit_elision_prod_test.cljs` pattern). |
| 5 | `:rf.machine/spawned` from `:leaf-a` `:spawn` | A | Spawn trace. → `implementation/machines/test/.../machines_spawn_cljs_test.cljs` (already exists; extend). |
| 6 | `tick-count` = 1 after entry action | A | Entry action body executed → app-db write → sub fires. → same target as #2-3. |

**Migrated subtotal: 5 of 5 logical observations = 100%. Residual: 1 (mount sanity). DROP this spec entirely** — none of the 5 substantive assertions need a real browser. The mount sanity is covered by the 3 adapter smokes.

### `testbeds/deliberate_throw/spec.cjs` (Spec 009 handler-exception trace)
| # | Assertion | Class | Rationale + target |
|---:|---|:---:|---|
| 1 | `expectVisible(deliberate-throw)` | C | Mount sanity. → drop (subsumed by adapter smokes). |
| 2 | `handler-count` pre-click = '0' | A | App-db initial. → `implementation/core/test/re_frame/on_error_test.cljc` (ALREADY EXISTS — extend to cover the `:where :handler` ex-data tag round-trip if not already). |
| 3 | Trace bus has `:operation :rf.error/handler-exception` + `:op-type :error` | A | Identical contract to existing `on_error_test.cljc`. → EXTEND `on_error_test.cljc`. |
| 4 | Matched event carries `:where :handler` (ex-info data hoisted onto `:tags`) | A | Same contract. → same target. |
| 5 | Post-click `handler-count` still '0' (`:no-recovery` semantics) | A | Per-frame `:on-error` policy + `:db` suppression. → same target. |

**Migrated subtotal: 4 of 4 = 100%. DROP this spec entirely** — `on_error_test.cljc` covers the same contract surface in JVM + CLJS already. Cross-check the existing suite carries the `:where :handler` round-trip before deletion.

### `testbeds/drain_depth_trigger/spec.cjs` (Spec 002 drain-depth rollback)
| # | Assertion | Class | Rationale + target |
|---:|---|:---:|---|
| 1 | `expectVisible(drain-depth-trigger)` | C | Mount. → drop. |
| 2 | `depth-reached` = '0' pre-click | A | App-db initial. → `implementation/core/test/re_frame/drain_depth_cljs_test.cljs` (new file) OR extend the existing router-drain tests. |
| 3 | `drain-depth-mirror` = '25' | A | Frame ceiling readback. → same target. |
| 4 | After Start: `depth-reached` rolls back to '0' (poll) | A | Atomic rollback per Spec 002 rule 3. → same target — drive the recurse handler synchronously and assert post-drain app-db. |
| 5 | Epoch-history has `:outcome :halted-depth` record | A | rf2-v0jwt contract. → same target — assert via `rf.epoch-history` directly. |
| 6 | Record's `:halt-reason :operation :rf.error/drain-depth-exceeded` | A | Same. → same target. |
| 7 | Record's `:depth 25` | A | Same. → same target. |
| 8 | Record's `:event-id :drain-depth-trigger.core/recurse` | A | Same. → same target. |
| 9 | Record's `:db-before` = `:db-after` (atomic rollback) | A | Same. → same target — `(= (:db-before r) (:db-after r))` directly. |

**Migrated subtotal: 8 of 8 = 100%. DROP this spec entirely.** Spec 002 rule 3 has no browser dimension — pure run-to-completion-drain semantics.

### `testbeds/http_toggle/spec.cjs` (Spec 014 :rf.http/* category attribution)
| # | Assertion | Class | Rationale + target |
|---:|---|:---:|---|
| 1 | `expectVisible(http-toggle)` | C | Mount. → drop. |
| 2 | Per-category: trace bus has `:operation :rf.http/<kind>` + `:op-type :error` + `:kind :rf.http/<kind>` | A | Trace emit + category attribution. → `implementation/http/test/.../http_category_attribution_cljs_test.cljs` (new file, or extend existing http-canned-failure test if one exists). |
| 3 | Per-category: `status` mirror = 'error' | A | Reply→handler→app-db. → same target. |
| 4 | Per-category: `failure-kind` mirror = `:rf.http/<kind>` | A | Same end-to-end round-trip. → same target. |

**Migrated subtotal: 18 of 18 observations = 100%. DROP this spec entirely.** Spec 014's eight `:rf.http/*` categories already deserve full CLJS unit coverage; this spec is observational scaffolding that should live in `implementation/http/test/`.

### `testbeds/long_flow_w_failure/spec.cjs` (Spec 013 flow four-rule failure)
| # | Assertion | Class | Rationale + target |
|---:|---|:---:|---|
| 1 | `expectVisible(long-flow-w-failure)` | C | Mount. → drop. |
| 2 | `:input` mirror > 0 (cascade scheduled) | A | App-db readback under setTimeout-driven dispatch loop. → `implementation/flows/test/.../flow_four_rule_failure_cljs_test.cljs` (new file). The 250ms × 20-tick cascade is faster in CLJS — drive synchronously via direct `(dispatch-sync [::tick])` loop. |
| 3 | Trace bus: `:rf.flow/failed` for `::flow-b` BEFORE `:rf.error/flow-eval-exception` | A | Rule 4 (per-flow before cascade-level). → same target. |
| 4 | `a-result` ≥ 10 (Rule 1, prior-flow preserved) | A | Same target. |
| 5 | `b-result` = '12' (Rule 2, failing flow's output not written) | A | Same target. |
| 6 | `c-result` = '20' (Rule 3, cascade halts) | A | Same target. |
| 7 | ≥3 `:rf.flow/failed` emits (Rule 2 re-attempt cardinality) | A | Same target. |

**Migrated subtotal: 6 of 6 = 100%. DROP this spec entirely.** Spec 013 has zero browser dimension — flows run on the spine, not in the DOM.

### `testbeds/non_trivial_app_db/spec.cjs` (Spec 009 trace ordering)
| # | Assertion | Class | Rationale + target |
|---:|---|:---:|---|
| 1 | `expectVisible(non-trivial-app-db)` | C | Mount. → drop. |
| 2 | Pre-click: app-db pretty-print does NOT include 'BK-099' | A | Sub readback. → `implementation/core/test/re_frame/trace_ordering_cljs_test.cljs` (new file) or extend `event_emit_test.cljc`. |
| 3 | Trace bus: `:event/dispatched` index < `:event/db-changed` index | A | Spec 009 ordering contract. → same target. |
| 4 | Post-click: app-db includes 'BK-099' (DOM mirror re-renders) | A | Sub→view re-render. → same target — assert `(get-in @app-db [:catalog :categories :books :groups :tech :skus])` directly. |

**Migrated subtotal: 3 of 3 = 100%. DROP this spec entirely.** Trace ordering is observable in pure CLJS.

### `testbeds/ssr_basic/spec.cjs` (Spec 011 hydration baseline) — MIGRATED (Wave 3, rf2-pxb7t)

**Status: spec.cjs DELETED. Migrated to `implementation/ssr/test/re_frame/ssr_hydration_test.clj`** — JVM tests using `rf/subscribe-once` for synchronous post-hydration reads against the contract-bearing surfaces (`:rf/hydrate` handler, `:rf/hydration` metadata stash, `:rf.ssr/compatibility-check-skipped` trace, `:rf/response` payload round-trip). The contract surface is platform-neutral (`.cljc`) so JVM coverage is sufficient. The two (C) DOM-mount probes (#1 static-HTML-before-bundle + #2 hydrated-marker mount) retire alongside per the audit's §Drop-or-keep recommendation — substrate-mount sanity is already covered by the 3 adapter smokes.

| # | Assertion | Class | Migrated to |
|---:|---|:---:|---|
| 1 | `expectVisible(ssr-basic)` | C | RETIRED — substrate-mount sanity covered by 3 adapter smokes. |
| 2 | `expectVisible(hydrated)` | C | RETIRED — same. |
| 3 | `hydrated` text = 'hydrated' | A | `hydration-baseline-replaces-app-db-and-stashes-metadata` |
| 4 | `count` = '7' (seeded from payload) | A | Same target. |
| 5 | `title` = 'seeded' (seeded from payload) | A | Same target. |
| 6 | After inc click: count = '8' | A | `hydration-baseline-post-hydrate-dispatch-mutates-seeded-db` |
| 7 | After set-title click: title = 'hydrated' | A | Same target. |
| 8 | `resp-status` = '200' | A | `hydration-baseline-rf-response-slice-round-trips-via-payload` |
| 9 | `resp-ct` contains 'text/html' | A | Same target. |
| 10 | `resp-cookies-count` = '1' | A | Same target. |
| 11 | `resp-cookie-name` = 'session' | A | Same target. |
| 12 | `window.__rf_trace_events()` carries `:rf.ssr/compatibility-check-skipped` | A | `hydration-baseline-emits-compatibility-check-skipped-trace` |
| 13 | NO `:rf.ssr/hydration-mismatch` on baseline | A | `hydration-baseline-no-mismatch-trace-when-server-hash-nil` |

**Migrated subtotal: 11 of 11 substantive assertions = 100%. Residual: 0. SPEC.CJS DELETED.** The two (C) mount probes retired (substrate-mount coverage by adapter smokes).

### `testbeds/ssr_hydration_mismatch/spec.cjs` (Spec 011 hydration-mismatch trace) — MIGRATED (Wave 3, rf2-pxb7t)

**Status: spec.cjs DELETED. Migrated to `implementation/ssr/test/re_frame/ssr_hydration_mismatch_test.clj`** — JVM tests covering the `verify-hydration!` mismatch path (`:rf.ssr/hydration-mismatch` trace tag payload, `:op-type :error` categorisation, `:recovery :warned-and-replaced` envelope hoist, `:client-hash` 8-char lowercase-hex shape, page-stays-interactive contract). The visual mismatch-banner is a DOM-render concern; its underlying trace-tag contract is now locked in pure CLJS, and the banner-render probes retire per the §Drop-or-keep recommendation (substrate-mount + chrome covered elsewhere).

| # | Assertion | Class | Migrated to |
|---:|---|:---:|---|
| 1 | `expectVisible(hydrated)` | C | RETIRED — substrate-mount sanity covered by 3 adapter smokes. |
| 2 | `hydrated` text = 'hydrated' | A | `mismatch-hydrate-still-stashes-metadata-when-server-hash-set` |
| 3 | `expectVisible(mismatch-banner)` | C | RETIRED — banner-DOM probe retired alongside spec.cjs (underlying tag-payload contract migrated to CLJS). |
| 4 | `mismatch-server-hash` = 'deadbeef' | A | `mismatch-trace-carries-server-hash-failing-id-recovery` |
| 5 | `mismatch-client-hash` matches `/^[0-9a-f]{8}$/` | A | `mismatch-trace-client-hash-is-8-char-lowercase-hex` |
| 6 | client-hash ≠ 'deadbeef' | A | Same target. |
| 7 | `mismatch-failing-id` = ':rf/hydrate' | A | `mismatch-trace-carries-server-hash-failing-id-recovery` |
| 8 | `mismatch-recovery` = ':warned-and-replaced' | A | Same target. |
| 9 | `window.__rf_trace_events()` has `:rf.ssr/hydration-mismatch` with `op_type = ':error'` AND `server_hash = 'deadbeef'` | A | `mismatch-trace-is-an-error-op-type-event` + `mismatch-trace-carries-server-hash-failing-id-recovery` |
| 10 | After inc click: count = '1' (interactive post-mismatch) | A | `mismatch-page-stays-interactive-post-mismatch` |

**Migrated subtotal: 7 of 7 substantive assertions = 100%. Residual: 0. SPEC.CJS DELETED.** The two (C) DOM probes retired (substrate-mount + banner chrome covered elsewhere).

### `testbeds/ssr_multi_frame/spec.cjs` (Spec 011 per-frame hydration isolation) — MIGRATED (Wave 3, rf2-pxb7t)

**Status: spec.cjs DELETED. Migrated to `implementation/ssr/test/re_frame/ssr_multi_frame_isolation_test.clj`** — JVM tests using `rf/subscribe-once frame-id query-v` (the 2-arg form at `implementation/core/src/re_frame/subs.cljc:365`) to lock the per-frame hydration isolation contract: three frames, three `:rf/hydrate` dispatches, three independent app-dbs, three distinct `:server-hash` slots, cross-frame `subscribe-once` calls resolving against the named frame's signal-graph cache, post-hydrate dispatch fan-out staying frame-isolated. The three-panel mount probe (#1) retires alongside per the audit's §Drop-or-keep recommendation.

| # | Assertion | Class | Migrated to |
|---:|---|:---:|---|
| 1 | `expectVisible(panel-A/B/log)` | C | RETIRED — substrate-mount sanity covered by 3 adapter smokes. |
| 2 | `n-A` = '10', `n-B` = '99' | A | `multi-frame-hydrate-seeds-each-frame-from-its-own-payload-slice` |
| 3 | `entries-count` = '2' | A | Same target. |
| 4 | `hyd-A/B/log` = 'true' | A | `multi-frame-hydrate-stashes-per-frame-hydration-metadata` |
| 5 | `hash-A/B/log` = 'aaaa1111'/'bbbb2222'/'cccc3333' | A | `multi-frame-hydrate-stashes-per-frame-server-hash` |
| 6 | `summary-a/b/log-hash` matches | A | `multi-frame-subscribe-once-resolves-against-explicit-frame-id` |
| 7 | `summary-all-distinct` = 'true' | A | Same target. |
| 8 | After inc-A click: `n-A` = '11', `n-B` stays '99' | A | `multi-frame-dispatch-isolation-per-frame` |
| 9 | After 2× inc-B click: `n-B` = '101', `n-A` stays '11' | A | Same target. |

**Migrated subtotal: 14 of 14 substantive assertions = 100%. Residual: 0. SPEC.CJS DELETED.** The (C) three-panel mount probe retired (substrate-mount covered by adapter smokes).

## §Summary metrics

| Spec | Assertions | (A) migratable | (B) keep-adapter | (C) stays-Playwright |
|---|---:|---:|---:|---:|
| helix smoke | 3 | 0 | 3 | 0 |
| reagent smoke | 3 | 0 | 3 | 0 |
| uix smoke | 3 | 0 | 3 | 0 |
| ~~parallel_frames~~ MIGRATED Wave 2 (rf2-lcg1z) → `implementation/core/test/re_frame/multi_frame_isolation_cljs_test.cljs` | ~~27~~ | ~~23~~ | 0 | ~~4~~ |
| perf_counter | 6 | 6 | 0 | 0 |
| deep_machine | 5 | 5 | 0 | 0 |
| deliberate_throw | 4 | 4 | 0 | 0 |
| drain_depth_trigger | 8 | 8 | 0 | 0 |
| http_toggle | 18 | 18 | 0 | 0 |
| long_flow_w_failure | 6 | 6 | 0 | 0 |
| non_trivial_app_db | 3 | 3 | 0 | 0 |
| ssr_basic | 13 | 11 | 0 | 2 |
| ssr_hydration_mismatch | 9 | 7 | 0 | 2 |
| ssr_multi_frame | 16 | 14 | 0 | 2 |
| **TOTAL** | **124** | **105** | **9** | **10** |

(124 counts http_toggle's 6 ×3 categories and otherwise mirrors §Inventory.)

Headline: **105 of 124 assertions (85%) migrated to CLJS/JVM unit tests across four waves.** Wave 1 (rf2-4j0tb) retired 6 framework testbed spec.cjs files (deliberate_throw, drain_depth_trigger, http_toggle, long_flow_w_failure, non_trivial_app_db, deep_machine) covering 41 assertions. Wave 2 (rf2-lcg1z) migrated parallel_frames' 23 isolation assertions to the per-PR `:node-test` build at `implementation/core/test/re_frame/multi_frame_isolation_cljs_test.cljs` and DROPPED the residual 2-mount Playwright surface entirely — the multi-frame mount path is exercised by Causa's own panels-e2e tests. Wave 3 (rf2-pxb7t) migrated the 3 SSR testbed spec.cjs files covering 32 assertions to JVM tests at `implementation/ssr/test/re_frame/ssr_{hydration,hydration_mismatch,multi_frame_isolation}_test.clj` using `rf/subscribe-once` for synchronous reads; the 8 (C) DOM-mount probes retired alongside (substrate-mount sanity already covered by the 3 adapter smokes). Wave 4 (rf2-e3j8l) migrated perf_counter's 4 User-Timing residuals to a nightly `:node-test`-style build. 9 (7%) are the canonical 3 adapter smokes — they ARE the (B) bucket, no action. The 10 (8%) originally classified (C) collapsed to 0 across Waves 2 + 3.

**Ten testbed spec.cjs files now fully retired** (residual = 0 substantive assertions): Wave 1's six (`deep_machine`, `deliberate_throw`, `drain_depth_trigger`, `http_toggle`, `long_flow_w_failure`, `non_trivial_app_db`) + Wave 4's one (`perf_counter`) + Wave 3's three (`ssr_basic`, `ssr_hydration_mismatch`, `ssr_multi_frame`). For each, the testbed surface itself stays — `core.cljs` / `index.html` / `README.md` remain as the canonical Causa/Story observation target — but the Playwright spec.cjs has been deleted.

## §Migration ordering

One bead per spec.cjs file. Each can dispatch in parallel where target test files don't overlap. Ordering proposal — high-yield + low-risk first, conflict-free surfaces parallel:

**Wave 1 (parallel, high-yield, zero browser-dimension residual — full DROP):**
- `B1` — drop `testbeds/deliberate_throw/spec.cjs`; extend `on_error_test.cljc` for `:where :handler` ex-data round-trip if missing. **Surface: on-error.**
- `B2` — drop `testbeds/non_trivial_app_db/spec.cjs`; add `trace_ordering_cljs_test.cljs` OR extend `event_emit_test.cljc`. **Surface: trace ordering.**
- `B3` — drop `testbeds/drain_depth_trigger/spec.cjs`; add `drain_depth_cljs_test.cljs`. **Surface: router drain.**
- `B4` — drop `testbeds/deep_machine/spec.cjs`; extend `machines_hierarchical_cljs_test.cljs` + `machines_spawn_cljs_test.cljs`. **Surface: machines.**
- `B5` — drop `testbeds/long_flow_w_failure/spec.cjs`; add `flow_four_rule_failure_cljs_test.cljs` under `implementation/flows/test/`. **Surface: flows.**
- `B6` — drop `testbeds/http_toggle/spec.cjs`; add `http_category_attribution_cljs_test.cljs` covering ALL 8 categories (the spec.cjs sampled 3 — CLJS unit cost is identical for 8). **Surface: http.**

Each Wave 1 bead is fully isolated to one artefact's test dir + one repo-root spec.cjs file deletion. **Six beads, parallel-dispatchable.**

**Wave 2 (rf2-lcg1z, COMPLETED):**
- `B7` — `tools/causa/testbeds/parallel_frames/spec.cjs` DELETED. Multi-frame isolation contract migrated to `implementation/core/test/re_frame/multi_frame_isolation_cljs_test.cljs` (six deftests covering: two-frames-mount, counter-isolation, clock-tick-isolation, sub-lens-follows-frame, no-cross-frame-leakage + rf/get-frame-db as the only legitimate cross-frame read, destroy-independence). Causa-side target-frame round-trip + L2 frame-scoped filter were already covered by `tools/causa/test/.../panels_e2e/parallel_frames_e2e_cljs_test.cljs` (rf2-ulpp8 / rf2-1p1j4) and `multi_frame_isolation_e2e_cljs_test.cljs` (cross-frame fan-out via fx). The testbed dir itself stays as the Causa-displayable showcase. **Surface: framework multi-frame.**

**Wave 3 (rf2-pxb7t, COMPLETED — single-bundle worker, sequential):**
- `B8` — `testbeds/ssr_basic/spec.cjs` deleted. Migrated to `implementation/ssr/test/re_frame/ssr_hydration_test.clj` (5 deftests / 11 substantive assertions covering :rf/hydrate replace-app-db + :rf/hydration metadata stash + post-hydrate dispatch round-trip + :rf/response payload echo + :rf.ssr/compatibility-check-skipped trace emit + no-mismatch-on-baseline).
- `B9` — `testbeds/ssr_hydration_mismatch/spec.cjs` deleted. Migrated to `implementation/ssr/test/re_frame/ssr_hydration_mismatch_test.clj` (5 deftests / 7 substantive assertions covering verify-hydration! mismatch path: trace tag payload + :op-type :error + :recovery :warned-and-replaced hoist + 8-char-lowercase-hex client-hash shape + post-mismatch page-stays-interactive).
- `B10` — `testbeds/ssr_multi_frame/spec.cjs` deleted. Migrated to `implementation/ssr/test/re_frame/ssr_multi_frame_isolation_test.clj` (5 deftests / 14 substantive assertions locking per-frame hydration isolation via `rf/subscribe-once frame-id query-v` against 3 frames; the (C) three-panel mount probe retired alongside).
  - All three deltas are pure-JVM tests using the `re-frame.ssr.test-fixture/reset-runtime` fixture + `rf/make-frame` + `rf/dispatch-sync` + `rf/subscribe-once`. Bead rf2-pxb7t cited the rf2-2mtl3 audit verifying `subscribe-once` already exists at `implementation/core/src/re_frame/subs.cljc:365`.

**Wave 4 (rf2-e3j8l, COMPLETED):**
- `B11` — `tools/causa/testbeds/perf_counter/spec.cjs` deleted. Migrated to `implementation/core/test/re_frame/performance_emit_nightly_test.cljs` (3 buckets exercised end-to-end + naming convention + flag-canary). Runs under the new `:node-test-perf-nightly` shadow-cljs build with `re-frame.performance/enabled?` flipped on at compile time. Excluded from per-PR `:node-test` by ns-suffix convention (`-emit-nightly-test$` doesn't match `cljs-test$`). Invoke via `npm run test:cljs-perf-emit-nightly`. The `:render` bucket's call-site shape is covered transitively by the macro round-trip tests in `re-frame.performance-cljs-test`. **Surface: perf instrumentation.**

**Wave 0 (no-op, no bead):**
- `B0` — 3 adapter smokes (`helix/reagent/uix`). Stay as-is. No action.

Total bead count: **10 follow-on beads** (1 per spec.cjs file = 11 minus the 1-spec adapter-smoke trio counted as zero). Wave 1 = 6 in parallel; Wave 2 = 1 standalone; Wave 3 = 3 in parallel; Wave 4 = 1 decision.

## §Drop-or-keep recommendation

**After all migrations land, the Playwright residual is:**
- 3 adapter smokes (helix/reagent/uix) — substrate (B). **~30 LoC total, ~5s wall.** Required per-PR.
- ~~`parallel_frames`~~ — DELETED (Wave 2, rf2-lcg1z); residuals migrated to `implementation/core/test/re_frame/multi_frame_isolation_cljs_test.cljs` (per-PR). The two page-mount eyeballs that would have justified a slim Playwright stay are subsumed by Causa's own panels-e2e suite, which mounts the same multi-frame topology in node-CLJS.
- ~~`ssr_basic`~~ — DELETED (Wave 3, rf2-pxb7t); 11 substantive assertions migrated to `implementation/ssr/test/re_frame/ssr_hydration_test.clj`; (C) mount-probes retired (substrate-mount covered by adapter smokes).
- ~~`ssr_hydration_mismatch`~~ — DELETED (Wave 3, rf2-pxb7t); 7 substantive assertions migrated to `implementation/ssr/test/re_frame/ssr_hydration_mismatch_test.clj`; (C) DOM probes retired.
- ~~`ssr_multi_frame`~~ — DELETED (Wave 3, rf2-pxb7t); 14 substantive assertions migrated to `implementation/ssr/test/re_frame/ssr_multi_frame_isolation_test.clj`; (C) three-panel mount probe retired.
- ~~`perf_counter`~~ — DELETED (Wave 4, rf2-e3j8l); residuals migrated to `implementation/core/test/re_frame/performance_emit_nightly_test.cljs` (nightly).

**Total residual: ~150-180 LoC, ~15-20s wall.** Down from 1715 LoC / ~3 min.

**Recommendation: KEEP per-PR but drop the browser-test job from REQUIRED.**

Rationale:
- The residual is small enough that gating it per-PR remains affordable (~20s adds nothing to the 8-12 min CI wall).
- BUT the residual is browser-mount-shape — failures here are either substrate-init bugs (caught by the adapter smokes already) or chrome-render bugs (caught by palette-drift + Story chrome contracts). It's not load-bearing for correctness — the 101 (A)-class assertions are now in CLJS unit tests, where they run on every node-test execution in ~5s.
- Dropping `browser-tests` from REQUIRED and making it best-effort-per-PR matches the post-migration risk profile: the gate is now scope-confined to chrome+SSR mount surfaces that don't fail in normal feature work.
- Nightly + release-only retention of the FULL Playwright surface (pre-migration suite, against current main) gives a safety net for chrome regressions without blocking PRs.

**Per-PR alternative — KEEP browser-tests REQUIRED on chrome-touching diffs only:**
A path-filter that runs Playwright only when:
- `implementation/adapters/**` touched (substrate impact)
- `testbeds/ssr_*/**` or `implementation/ssr/**` touched (SSR impact)
- `tools/causa/spine/**` or `tools/story/render-shell/**` touched (chrome impact)

Otherwise skip. This is the rf2-k9ekz sister bead — solve once the migration lands.

**Screenshot-baseline subset?** Not warranted yet. The palette-drift gate (rf2-z7ms8) already deterministically catches token/color regressions. A pixel-baseline subset adds Argos/Chromatic egress + flakiness risk for marginal coverage. Defer until a chrome regression slips through palette-drift in practice.

## §Out-of-scope / explicit exclusions

- **examples/ directory** — testbed-free per CLAUDE.md (locked 2026-05-19, rf2-8cevm). No spec.cjs lives there.
- **`tools/story/test/story_browser_scenarios.cjs` + `story_feature_load.cjs`** — Story's OWN feature gates, not framework testbeds. These exercise Story chrome (mode tabs, snapshot-identity, toolbar, recorder). Out of scope for rf2-tglku; they're chrome-shaped by design and stay Playwright.
- **`large_dispatcher`, `multi_frame`, `schema_violation` testbeds at `testbeds/`** — testbed directories EXIST but no spec.cjs ships in them (verified via Glob). They're likely consumed by other gates (story_feature_load) or are observation-only surfaces with no Playwright wiring. No migration action.

## §Open questions

1. **`performance_cljs_test.cljs` coverage parity?** Wave 4 hinges on whether the existing perf-suite already exercises all 4 buckets (`:rf:event:*` / `:rf:sub:*` / `:rf:fx:*` / `:rf:render:*`). If yes, perf_counter/spec.cjs drops entirely. If no, the spec stays as the only User-Timing-API-in-real-browser observable. **Mike to call.**
2. **`subscribe-once` against an explicit frame-id from a CLJS test under e2e_multi_frame** — the ssr_multi_frame Phase 3 contract (`summary-*-hash`) needs cross-frame `subscribe-once` from one frame's render context. Verify the e2e_multi_frame helpers expose this. If not, ssr_multi_frame migration needs a small helper add first.
3. **Wave ordering vs. epic-deadline pressure** — proposal is six Wave 1 beads parallel-first. If Mike wants single-bundle dispatch instead, Wave 1 can collapse to one bead (6 spec.cjs deletes + 5-6 CLJS test files), but worker-bundle timeouts (per MEMORY split-bundles-over-4-beads feedback) argue for the parallel split.
