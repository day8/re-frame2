# re-frame2 — reference implementation

The v2 reference implementation, generated from the specification corpus in
`../docs/specification/`. The acceptance test for the spec is:
*"an AI can one-shot the implementation from the spec alone."* This
directory is that one-shot.

## Status

**JVM:**
- 43 / 43 conformance fixtures pass (full corpus green).
- 20 smoke tests / 51 assertions pass.

**CLJS** (Reagent adapter, shadow-cljs `:node-test` build):
- 14 smoke tests / 29 assertions pass.

Beads tracking known gaps live in `../.beads/`. The four open beads are
all design decisions or different-infrastructure items, not
implementation work.

## Layout

```
implementation/
  deps.edn                   Clojure deps (clojure, clojurescript, reagent, malli)
  package.json               npm deps for the CLJS test target (shadow-cljs, react)
  shadow-cljs.edn            :node-test build that runs cljs.test under node
  src/re_frame/
    interop.clj              JVM host primitives
    interop.cljs             CLJS host primitives
    registrar.cljc           (kind, id) → metadata + replacement-hooks
    frame.cljc               Frame container, reg-frame, destroy-frame
    router.cljc              Per-frame FIFO router + drain scheduling
                             + :rf.error/dispatch-sync-in-handler guard
    fx.cljc                  Effect interpreter + per-call fx-overrides
                             + :rf.fx/skipped-on-platform / no-such-fx
    events.cljc              reg-event-db / -fx / -ctx
    subs.cljc                Sub cache with ref-counting + hot-reload eviction
    interceptor.cljc         Interceptor chain runtime
    std_interceptors.cljc    path, unwrap, inject-cofx, ->interceptor primitive
    cofx.cljc                reg-cofx + standard cofx
    trace.cljc               Trace event emission + listener API
    schemas.cljc             Malli runtime validation (validate-app-db! after :db)
    machines.cljc            Hierarchical FSM, :always microsteps,
                             :after delayed transitions with epoch-stale,
                             declarative :invoke / spawn / destroy
    flows.cljc               reg-flow, topo-sort, dirty-check + hot-reload
    routing.cljc             reg-route, 6-rule rank cascade, query coercion,
                             nav-token + can-leave + pending-nav protocol
    ssr.cljc                 Hiccup → HTML5 emitter, :rf/hydrate +
                             hydration-mismatch, :rf.server/* fx,
                             default error projector
    conformance.cljc         DSL interpreter for fixture handler bodies
    views.cljs               reg-view*, React frame-context bridge
    views_macros.clj         reg-view, with-frame, bound-fn, h macros
    substrate/
      adapter.cljc           Adapter contract (the 9 functions)
      reagent.cljs           Reagent adapter
      plain_atom.cljc        Plain-atom adapter (JVM, SSR, headless)
    core.cljc                Public API surface (re-frame.core equivalent)
  test/re_frame/
    smoke_test.clj           JVM smoke tests
    runtime_cljs_test.cljs   CLJS smoke tests (Reagent adapter)
    conformance_test.clj     Loads + runs every fixture in
                             ../docs/specification/conformance/fixtures/
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

**JVM** (no setup beyond Clojure CLI):

```sh
cd implementation
clojure -M:test
```

This runs the smoke tests in `test/re_frame/smoke_test.clj` plus the
conformance fixture runner in `test/re_frame/conformance_test.clj`,
which loads every `.edn` in `../docs/specification/conformance/fixtures/`
and runs the runnable subset against this implementation.

**CLJS** (one-time `npm install`, then iterate):

```sh
cd implementation
npm install
npx shadow-cljs compile node-test && node out/node-test.js
```

The shadow-cljs config builds an `:node-test` target that runs
`cljs.test` under Node, exercising the Reagent adapter end-to-end.

## What's not in scope

- The migration agent (re-frame v1 → v2). That's a separate
  AI-driven task per `../docs/specification/MIGRATION.md`.
- Browser-mounted CLJS tests (e.g. for `frame-provider` /
  React-context resolution). The `:node-test` build covers all
  non-DOM behaviour; verifying the React context bridge needs a
  browser harness.
