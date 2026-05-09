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
[Conventions §Substrate-adapter shipping convention](../spec/Conventions.md#substrate-adapter-shipping-convention)
(rf2-0hxm). Each subdirectory carries its own `deps.edn`; the top-level
`deps.edn` and `shadow-cljs.edn` are build coordinators that pull both
artefacts onto a single classpath for the cross-substrate builds
(browser tests, elision probe, examples).

```
implementation/
  deps.edn                   Top-level coordinator: :local/root deps for both artefacts.
  package.json               npm deps for the CLJS test targets (shadow-cljs, react, playwright).
  shadow-cljs.edn            Top-level shadow build: pulls core/ and reagent/ src+test paths
                             plus ../examples for the cross-substrate test and example bundles.

  core/                      day8/re-frame-2 — the core artefact.
    deps.edn                 Core's own deps (clojure, clojurescript, reagent, malli).
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
      schemas.cljc           Malli runtime validation.
      machines.cljc          Hierarchical FSM, :always, :after, :invoke / spawn / destroy.
      flows.cljc             reg-flow, topo-sort, dirty-check + hot-reload.
      routing.cljc           reg-route, 6-rule rank cascade, query coercion, nav protocol.
      ssr.cljc               Hiccup → HTML5 emitter, :rf/hydrate, :rf.server/* fx, error projector.
      conformance.cljc       DSL interpreter for fixture handler bodies.
      views.cljs             reg-view*, React frame-context bridge (CLJS-only).
      views_macros.clj       reg-view, with-frame, bound-fn macros.
      substrate/
        adapter.cljc         The 9-fn adapter contract.
        plain_atom.cljc      Plain-atom adapter (JVM, SSR, headless).
      core.cljc              Public API surface (re-frame.core).
    test/re_frame/           JVM tests + the substrate-agnostic CLJS tests
                             (conformance, hash-check, elision-probe).

  reagent/                   day8/re-frame-2-reagent — the Reagent adapter artefact.
    deps.edn                 :local/root dep on ../core.
    src/re_frame/substrate/
      reagent.cljs           The Reagent substrate adapter.
    test/re_frame/           CLJS tests that exercise the Reagent adapter end-to-end
                             (cross-spec, events, hot-reload, http-managed, machines,
                             nine-states, realworld, render-key, routing, runtime,
                             schemas).
```

## Status by spec area

| Spec | Status | Conformance fixtures |
|------|--------|----------------------|
| 001 Registration | Done | (covered transitively) |
| 002 Frames | Done | dispatch/envelope, drain/depth-limit, frame/{lifecycle,multi-instance}, fx/{db-first,ordering-source-order,override-by-id} |
| 003 App-db | Done | (covered transitively) |
| 004 Views | Done (JVM-runnable + CLJS via Reagent) | covered via reg-view in SSR fixtures |
| 005 State Machines | Done | machine/transition, hierarchical-{compound,cross-level,parent-fallthrough}, always-{single-microstep,depth-exceeded}, after-{single-delay,stale-detection,hierarchy}, invoke-spawn-on-entry-destroy-on-exit |
| 006 Reactive Substrate | Done (Reagent + plain-atom) | sub/chain |
| 008 Testing | Done | dispatch-sync, conformance harness |
| 009 Instrumentation | Done | error/{handler-exception,fx-handler-exception,sub-exception,no-such-handler,override-fallthrough} |
| 010 Schemas | Done | error/schema-failure |
| 011 SSR | Done | ssr/{render-to-string,hydrate,hydration-mismatch,head-emits,head-hydration,error-known-mapping,error-sanitisation,cookie,redirect,set-status}, fx/platforms |
| 012 Routing | Done | routing/{match-url,navigate,fragment-change,navigation-blocked,ranking-precedence,stale-nav-token-suppression} |
| 013 Flows | Done | (covered via smoke + flow-recomputes) |

## Running tests

**Per-artefact JVM** (no setup beyond Clojure CLI):

```sh
# core artefact
cd implementation/core
clojure -M:test

# reagent artefact (CLJS-only — JVM run is a classpath probe; 0 tests is normal)
cd implementation/reagent
clojure -M:test
```

The core run executes the full JVM suite — smoke, conformance, drain,
schemas, SSR end-to-end, etc. — loading every `.edn` in
`../../spec/conformance/fixtures/` and running the runnable subset
against this implementation.

**CLJS** (one-time `npm install` at the implementation/ root, then iterate):

```sh
cd implementation
npm install
npm run test:cljs       # node-test build, both core + reagent test trees
npm run test:browser    # browser-test build, headless Chromium via Playwright
npm run test:elision    # production-elision contract (Spec 009 §Production builds)
npm run test:examples   # example-app browser tests
```

`npm run test:cljs` builds the `:node-test` target via shadow-cljs and
runs `cljs.test` under Node. Both artefact subtrees are on the
classpath, so the core's substrate-agnostic CLJS tests AND the Reagent
artefact's adapter-coupled tests run together.

`npm run test:browser` builds the `:browser-test` target into
`out/browser-test/`, serves it on `http://localhost:8021` via
`http-server`, then drives headless Chromium to the runner page and
parses the `cljs.test` summary (`Ran N tests containing M assertions.`).
Exits 0 on green, 1 on red. Use this when verifying anything that
depends on a real DOM, real browser timing, or React's DOM-rendering
pipeline.

## What's not in scope

- The migration agent (re-frame v1 → v2). That's a separate
  AI-driven task per `../spec/MIGRATION.md`.
