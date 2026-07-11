# re-frame2 UI: blank-slate React substrate

Status: design proposal, 2026-07-11. No implementation is claimed.

This suite proposes a new library, provisionally named **re-frame2 UI**:

- artefact: `day8/re-frame2-ui`
- primary namespace: `re-frame.ui`
- React floor: patched React 19.2.4
- compatibility posture: clean break; no Reagent, UIx, or Helix compatibility layer

The name is intentionally descriptive. The interesting thing should be the programming model, not a second brand users have to remember.

## Decision in one paragraph

Use Clojure-shaped literal UI syntax, but treat it as a **compile-time template language**, not runtime Hiccup. `defview` lowers native elements directly to `react/jsx-runtime`, compiles props to JavaScript objects, hoists static subtrees, generates stable frame-bound event handlers, and emits a second JVM form from the same template for re-frame2 SSR. A reactive view owns one commit-safe **ViewCell** and one `useSyncExternalStore` subscription regardless of how many re-frame2 subscriptions it reads. `ui/sub` is not a React Hook: it can be used in branches, and the ViewCell reconciles the dependencies of the render that actually commits. Source notifications never execute prop-dependent selectors; they only mark the cell stale. Development builds retain a rich compiler manifest and exact render causes. Advanced production builds erase that instrumentation and retain direct React calls plus the minimum capability-specific runtime.

## Why this is a new substrate

Reagent has the best read ergonomics but pays for runtime tree interpretation and a separate reaction/component scheduler. UIx has the best existing compilation strategy and linter, but re-frame2 remains an add-on hook and each read is a separate external-store integration. Helix has the cleanest raw-React boundary but leaves re-frame2 ergonomics, dependency safety, and observability to application code.

Combining their public APIs would preserve all three compromises. The clean design instead makes the re-frame2 derivation graph, frame identity, event router, resource leases, epoch drain, and Xray metadata first-class compiler inputs.

## The core shape

```clojure
(ns example.counter
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui]))

(rf/reg-event ::increment
  (fn [{:keys [db]} _]
    {:db (update db :count (fnil inc 0))}))

(rf/reg-sub ::count
  (fn [db _]
    (:count db 0)))

(ui/defview counter [{:keys [label]}]
  (let [n (ui/sub [::count])]
    [:button.counter
     {:on-click [::increment]}
     label ": " n]))
```

That source compiles to a named, memoized React function component. The `:button` is a direct JSX-runtime call. The query site is stable. The event vector becomes one stable function that dispatches into the frame of the committed view instance and carries its source site into tracing. There is no client-side Hiccup walker and no closure allocation on each render for the click handler.

## Documents

1. [Design posture and invariants](01-posture-and-invariants.md)
2. [Comparative analysis](02-comparative-analysis.md)
3. [Programming model and compiler](03-programming-model-and-compiler.md)
4. [ViewCell reactivity](04-view-cell-reactivity.md)
5. [re-frame2 integration](05-re-frame2-integration.md)
6. [Debugging and observability](06-debugging-and-observability.md)
7. [Lifecycle, interop, SSR, and HMR](07-lifecycle-interop-ssr-hmr.md)
8. [Production performance contract](08-production-performance.md)
9. [API, delivery plan, and risks](09-api-delivery-and-risks.md)
10. [Research sources](sources.md)

The [user guide](guide/README.md) is written as if the proposed API existed. This is deliberate: examples are an API design test, and disagreements between the architecture and the manual are defects in the proposal.

## What is deliberately absent

- A runtime Hiccup interpreter in browser bundles.
- Reagent Form-1/Form-2/Form-3 detection.
- Automatic compatibility with existing Reagent/UIx/Helix components. React interop is explicit and small.
- One React Hook per re-frame2 subscription or resource lease.
- A second state manager, query cache, actor runtime, form runtime, or signal graph.
- Suspense as re-frame2 loading state.
- Proxy-based property tracking.
- A promise that arbitrary dynamic UI data will be optimized. Dynamic element and prop escape hatches are explicit cost boundaries.

## Relationship to current re-frame2 specifications

Most contracts survive unchanged: frames are carried, events remain data, subscriptions stay passive, resource loading remains explicit, views remain pure, loading remains inspectable state, JVM SSR remains supported, and debug data still elides in production.

One current requirement needs an explicit amendment rather than wordplay. [Spec 004](../../../spec/004-Views.md) currently requires the client render result itself to be serializable data. Direct JSX-runtime output is a React element and is not serializable Clojure data. This proposal moves portability to the stronger boundary: **one serializable compile-time template AST, with deterministic client and JVM code generators**. The JVM result remains a serializable render tree; the client result is direct React output. Hydration parity is checked from the shared AST. The change removes a mandatory client interpreter without sacrificing one-source SSR.

[Spec 006](../../../spec/006-ReactiveSubstrate.md) needs one narrow private observation port: an owner-free subscription probe followed by commit-time acquire/read/release. The new adapter also gives its existing derivation epoch an internal final phase that flushes dirty ViewCells once; this is not a new public callback or generic adapter plug-in seam. Both contracts are formalized in the integration document.

## Masterpiece test

The proposal earns that posture only if a prototype demonstrates all of the following:

- a literal view compiles within 10% of equivalent hand-written React JSX runtime cost;
- a component reading many subscriptions still owns one external-store hook and receives at most one notification per re-frame2 epoch;
- an abandoned render retains zero subscription refs and zero resource leases;
- an Activity-hidden tree retains local UI state but owns zero live re-frame2 subscriptions/resources, then reconnects without duplicates;
- a production bundle contains no runtime tag parser, Hiccup walker, source paths, debug manifests, or trace-building code;
- Xray can answer “why did this instance render?” with the exact prop, subscription, local-state, frame, or HMR cause;
- JVM output and hydrated client output pass structural parity fixtures generated from the same template AST.

If those gates fail, the design changes. The word “masterpiece” is a quality bar, not an exemption from measurement.
