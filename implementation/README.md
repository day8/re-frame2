# re-frame2 — CLJS reference implementation (one-shot attempt)

> **Status:** First-pass attempt at the one-shot CLJS reference implementation, generated from the specification corpus in `../docs/specification/`. **Not yet verified against the conformance corpus.** Expect rough edges; expect missing pieces. Beads filed in `../.beads/` for known gaps.

This is the v2 implementation Mike's spec corpus has been written *toward*. The acceptance test for the spec is: "an AI can one-shot the implementation from `docs/specification/` alone." This directory is that attempt.

## Layout

```
implementation/
  deps.edn                    Clojure deps (clojure, clojurescript, reagent, malli)
  src/re_frame_2/
    interop.clj               JVM host primitives (next-tick, atoms, clock, after-render)
    interop.cljs              CLJS host primitives (goog.async.nextTick, Reagent ratoms)
    registrar.cljc            (kind, id) → metadata lookup
    frame.cljc                Frame container, reg-frame, make-frame, destroy-frame
    router.cljc               Per-frame FIFO router + drain scheduling
    fx.cljc                   Effect interpreter (do-fx) + reserved fx-ids
    events.cljc               reg-event-db / -fx / -ctx
    subs.cljc                 Sub registration + sub-cache
    interceptor.cljc          Interceptor chain runtime
    std_interceptors.cljc     path, unwrap, inject-cofx, ->interceptor primitive
    cofx.cljc                 reg-cofx + standard cofx
    trace.cljc                Trace event emission + listener API
    schemas.cljc              :spec attachment (Malli, dev-only validation)
    machines.cljc             create-machine-handler, machine-transition, :always, :after
    flows.cljc                reg-flow, clear-flow, the post-handler interceptor
    routing.cljc              reg-route, match-url, :route slice, navigation events
    ssr.cljc                  Server frame lifecycle + render-to-string
    views.cljs                reg-view, frame-provider, h
    substrate/
      adapter.cljc            Adapter contract (the 9 functions)
      reagent.cljs            Reagent adapter
      plain_atom.cljc         Plain-atom adapter (JVM, SSR, headless)
    core.cljc                 Public API surface (re-frame.core equivalent)
  test/re_frame_2/
    ...                       Unit tests + conformance fixture runner (TODO)
```

## Status of each spec area

| Spec | Status |
|---|---|
| 001 Registration | First pass |
| 002 Frames | First pass (drain loop, dispatch envelope, override seam) |
| 004 Views | First pass (reg-view, h, frame-provider — CLJS only) |
| 005 State Machines | Partial — flat FSM + :always; hierarchical/`:after`/`:invoke` stubbed |
| 006 Reactive Substrate | First pass (Reagent + plain-atom adapters) |
| 008 Testing | Not yet implemented (test fixtures, dispatch-sync) |
| 009 Instrumentation | Partial — trace event emission; listener API; structured errors |
| 010 Schemas | First pass (Malli, dev-only) |
| 011 SSR | Stubbed |
| 012 Routing | Partial — match-url + :route slice; navigation events |
| 013 Flows | First pass |

See `../.beads/` for filed gaps.

## Caveats

- **No conformance fixtures run yet.** The harness is TODO; until it lands, none of these claims are verified.
- **CLJS-only for browser parts.** Pure-data parts target `.cljc`.
- **Migration agent (re-frame v1 → re-frame2) not implemented.** That's a separate AI-driven task per `MIGRATION.md`.
