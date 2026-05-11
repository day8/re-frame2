# re-frame2 — reference implementation

The v2 reference implementation, generated from the specification corpus in
`../spec/`. The acceptance test for the spec is:
*"an AI can one-shot the implementation from the spec alone."* This
directory is that one-shot.

## Status

The full conformance corpus runs green on every commit; the JVM and
CLJS suites are required CI checks. Open implementation beads live in
`../.beads/`.

## Layout

The implementation is split into per-Maven-artefact subdirectories per
[Conventions §Adapter shipping convention](../spec/Conventions.md#adapter-shipping-convention)
(rf2-0hxm) — extended to per-feature artefacts under rf2-p7va (schemas),
rf2-xbtj (machines), rf2-k682 (routing), rf2-tfw3 (flows), rf2-5kpd
(http), rf2-uo7v (ssr), and rf2-lt4e (epoch). Each subdirectory carries
its own `deps.edn`; the top-level `deps.edn` and `shadow-cljs.edn` are
build coordinators that pull every artefact onto a single classpath
for the cross-substrate builds (browser tests, elision probe, examples).

Two top-level groupings:

- **`adapters/`** — substrate adapters (rf2-zha9 directory introduction;
  rf2-0imy canonical naming, "adapters" not "substrates"). One directory
  per adapter (`reagent`, `uix`, `helix`, `reagent-slim`).
- **Per-feature artefacts** — one flat directory per feature
  (`schemas`, `machines`, `routing`, `flows`, `http`, `ssr`, `epoch`),
  each plugged into core via the `re-frame.late-bind` hook table per
  [Conventions §Independence rule](../spec/Conventions.md#independence-rule).

```
implementation/
  deps.edn                   Top-level coordinator: :local/root deps for every artefact.
  package.json               npm deps for the CLJS test targets (shadow-cljs, react, playwright).
  shadow-cljs.edn            Top-level shadow build: pulls every artefact's src+test paths
                             plus ../examples for the cross-substrate test and example bundles.

  core/                      day8/re-frame2 — the core artefact.
    deps.edn                 Core's own deps (clojure, clojurescript, reagent).
    src/re_frame/
      interop.{clj,cljs}     JVM / CLJS host primitives.
      registrar.cljc         (kind, id) → metadata + replacement-hooks.
      frame.cljc             Frame container, reg-frame, destroy-frame.
      router.cljc            Per-frame FIFO router + drain + dispatch-sync-in-handler guard.
      fx.cljc                Effect interpreter + fx-overrides + :rf.fx/skipped-on-platform.
      events.cljc            reg-event-db / -fx / -ctx.
      subs.cljc              Sub cache with ref-counting + hot-reload eviction.
      interceptor.cljc       Interceptor chain runtime.
      std_interceptors.cljc  path, unwrap, inject-cofx, ->interceptor primitive.
      cofx.cljc              reg-cofx + standard cofx.
      trace.cljc             Trace event emission + listener API.
      late_bind.cljc         Late-binding hook table for cross-artefact references.
      performance.cljc       Per-cascade duration capture + budget warnings (Spec 009).
      source_coords.cljc     Source-coord capture + format helpers (Spec 009).
      test_support.cljc      Test fixtures, dispatch-sync helpers, reset-runtime (Spec 008).
      conformance.cljc       DSL interpreter for fixture handler bodies.
      views.cljs             reg-view*, React frame-context bridge (CLJS-only).
      views_macros.clj       reg-view, with-frame, bound-fn macros.
      adapter/
        context.cljs         The shared React frame Context object every React-shaped
                             adapter consumes (rf2-3yij Decision 2).
      substrate/
        adapter.cljc         The 9-fn adapter contract.
        plain_atom.cljc      Plain-atom adapter (JVM, SSR, headless).
      core.cljc              Public API surface (re-frame.core).
    test/re_frame/           JVM tests + the substrate-agnostic CLJS tests
                             (conformance, hash-check, elision-probe).

  adapters/                  Substrate adapters live here per rf2-zha9 (renamed from
                             substrates/ per rf2-0imy) — one directory per adapter
                             (reagent, uix, helix, reagent-slim). Per-feature artefacts
                             (schemas, machines, ...) stay flat under implementation/.
    reagent/                 day8/re-frame2-reagent — the Reagent adapter artefact.
      deps.edn               :local/root dep on ../../core.
      src/re_frame/adapter/
        reagent.cljs         The Reagent adapter.
      test/re_frame/         CLJS tests that exercise the Reagent adapter end-to-end
                             (cross-spec, events, hot-reload, http-managed, machines,
                             nine-states, realworld, render-key, routing, runtime,
                             schemas).
    uix/                     day8/re-frame2-uix — the UIx adapter artefact (rf2-3yij).
      deps.edn               :local/root dep on ../../core; pulls com.pitch/uix.{core,dom}.
      src/re_frame/adapter/uix.cljs
                             The UIx adapter (use-subscribe, flush-views!, etc.).
    helix/                   day8/re-frame2-helix — the Helix adapter artefact (rf2-2qit).
      deps.edn               :local/root dep on ../../core; pulls lilactown/helix.
      src/re_frame/adapter/helix.cljs
                             The Helix adapter.
    reagent-slim/            day8/reagent-slim — Reagent rewrite for React 19
                             (rf2-5djt; Stage 4 rf2-6hyy). Stage 4-A landed: reactive
                             primitives in src/reagent2/ratom.{clj,cljs}. Subsequent
                             sub-stages add render scheduler, component-shape detection,
                             hiccup translation, render-to-static-markup, throw-on-call
                             shims, examples + MIGRATION.

  schemas/                   day8/re-frame2-schemas — schemas (Spec 010, rf2-p7va).
    deps.edn                 :local/root dep on ../core; pulls Malli for runtime validation.
    src/re_frame/schemas.cljc      Malli runtime validation.
    test/re_frame/                 JVM + CLJS schema tests.

  machines/                  day8/re-frame2-machines — state machines (Spec 005, rf2-xbtj).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/machines.cljc     Hierarchical FSM, :always, :after, :invoke / spawn / destroy.
    test/re_frame/                 CLJS machine tests.

  routing/                   day8/re-frame2-routing — routing (Spec 012, rf2-k682).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/routing.cljc      reg-route, 6-rule rank cascade, query coercion,
                                   nav protocol, :rf.route/* events, :rf.nav/* fxs,
                                   :rf/route reg-sub family.
    test/re_frame/                 JVM routing tests.

  flows/                     day8/re-frame2-flows — flows (Spec 013, rf2-tfw3).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/flows.cljc        reg-flow, topo-sort, dirty-check + hot-reload.
    test/re_frame/                 JVM flows tests.

  http/                      day8/re-frame2-http — managed HTTP (Spec 014, rf2-5kpd).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/http_managed.cljc :rf.http/managed args-map, decode pipeline, retry,
                                   abort, frame-aware reply addressing.
    test/re_frame/                 JVM + CLJS http-managed tests.

  ssr/                       day8/re-frame2-ssr — SSR (Spec 011, rf2-uo7v).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/ssr.cljc          Hiccup → HTML5 emitter, :rf/hydrate, :rf.server/* fx,
                                   error projector.
    test/re_frame/                 JVM SSR end-to-end tests + source-coord parity tests.

  epoch/                     day8/re-frame2-epoch — per-frame epoch history
                             (Tool-Pair §Time-travel, rf2-lt4e).
    deps.edn                 :local/root dep on ../core.
    src/re_frame/epoch.cljc        Per-frame :rf/epoch-record ring buffer + projection
                                   walker for sub-runs / renders / effects.
    test/re_frame/                 JVM epoch tests.
```

## Status by spec area

| Spec | Status | Conformance fixtures |
|------|--------|----------------------|
| 001 Registration | Done | (covered transitively) |
| 002 Frames | Done | dispatch/envelope, drain/depth-limit, frame/{lifecycle,multi-instance}, fx/{db-first,ordering-source-order,override-by-id} |
| 003 — | Reserved (no `003-*.md`; held open per [`spec/README.md`](../spec/README.md) for future cross-frame composition work) | — |
| 004 Views | Done (JVM-runnable + CLJS via Reagent) | covered via reg-view in SSR fixtures |
| 005 State Machines | Done | machine/transition, hierarchical-{compound,cross-level,parent-fallthrough}, always-{single-microstep,depth-exceeded}, after-{single-delay,stale-detection,hierarchy}, invoke-spawn-on-entry-destroy-on-exit |
| 006 Reactive Substrate | Done (Reagent + plain-atom; UIx + Helix smoke-tested) | sub/chain |
| 007 Stories | Out of scope for the CLJS reference (no canonical implementation; the spec defines the Story / Variant / Workspace contract for tooling consumers) | — |
| 008 Testing | Done | dispatch-sync, conformance harness |
| 009 Instrumentation | Done | error/{handler-exception,fx-handler-exception,sub-exception,no-such-handler,override-fallthrough} |
| 010 Schemas | Done | error/schema-failure |
| 011 SSR | Done | ssr/{render-to-string,hydrate,hydration-mismatch,head-emits,head-hydration,error-known-mapping,error-sanitisation,cookie,redirect,set-status}, fx/platforms |
| 012 Routing | Done | routing/{match-url,navigate,fragment-change,navigation-blocked,ranking-precedence,stale-nav-token-suppression} |
| 013 Flows | Done | (covered via smoke + flow-recomputes) |
| 014 HTTP requests | Done | (covered via the `http_managed_test` suites + the managed-HTTP example smoke spec) |

The Reagent adapter is the canonical adapter — every test target (every
`clojure -M:test` run, every `node-test` build, every `:browser-test`
run, every `examples` run, every conformance fixture) executes against
it. UIx and Helix adapters are smoke-tested via the counter + login
pair per [Conventions §Adapter test matrix policy](../spec/Conventions.md#adapter-test-matrix-policy).
The `reagent-slim` adapter is in active development (Stage 4-A landed —
reactive primitives only); it does not yet participate in the test
matrix.

## Running tests

**Per-artefact JVM** (no setup beyond Clojure CLI):

```sh
# core artefact
cd implementation/core
clojure -M:test

# reagent artefact (CLJS-only — JVM run is a classpath probe; 0 tests is normal)
cd implementation/adapters/reagent
clojure -M:test

# schemas artefact
cd implementation/schemas
clojure -M:test

# machines artefact (CLJS-only — JVM run is a classpath probe; 0 tests is normal)
cd implementation/machines
clojure -M:test

# routing artefact
cd implementation/routing
clojure -M:test

# flows artefact
cd implementation/flows
clojure -M:test

# http artefact
cd implementation/http
clojure -M:test

# ssr artefact
cd implementation/ssr
clojure -M:test

# epoch artefact
cd implementation/epoch
clojure -M:test
```

The core run executes the full JVM suite — smoke, conformance, drain,
SSR end-to-end, etc. — loading every `.edn` in
`../../spec/conformance/fixtures/` and running the runnable subset
against this implementation.

**CLJS** (one-time `npm install` at the implementation/ root, then iterate):

```sh
cd implementation
npm install
npm run test:cljs       # node-test build, every artefact's CLJS test tree
npm run test:browser    # browser-test build, headless Chromium via Playwright
npm run test:elision    # production-elision contract (Spec 009 §Production builds)
npm run test:examples   # example-app browser tests
```

`npm run test:cljs` builds the `:node-test` target via shadow-cljs and
runs `cljs.test` under Node. Every artefact's source and test trees
are on the classpath, so the core's substrate-agnostic CLJS tests AND
each adapter / per-feature artefact's CLJS tests run together.

`npm run test:browser` builds the `:browser-test` target into
`out/browser-test/`, serves it on `http://localhost:8021` via
`http-server`, then drives headless Chromium to the runner page and
parses the `cljs.test` summary (`Ran N tests containing M assertions.`).
Exits 0 on green, 1 on red. Use this when verifying anything that
depends on a real DOM, real browser timing, or React's DOM-rendering
pipeline.

If port 8021 is already in use (e.g. another local repo's dev server),
the harness logs a warning and falls back to a free OS-chosen port.
Set `BROWSER_TEST_PORT` to pin a specific port (CI determinism); the
harness still falls back if that port is busy too.

## What's not in scope

- The migration agent (re-frame v1 → v2). That's a separate
  AI-driven task per `../spec/MIGRATION.md`.
