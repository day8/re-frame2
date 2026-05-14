# 19 — Adapters

## TL;DR

You want to use re-frame2 with Reagent, UIx, or Helix — or you're choosing between them. This page explains how the substrate-agnostic runtime wires into each, and why the events/subs/fx primitives don't care which you pick.

re-frame2's runtime — the registry, the dispatch loop, events, fx, subs, machines — is **substrate-agnostic**. The primitives in `app-db`, event handlers, and subs are identical regardless of which substrate is rendering. What differs is how the view layer is wired into React.

## The three substrate adapters

Three adapters are available today:

- **Reagent** — the canonical CLJS reference. Hiccup-shaped views, deref-tracking subscriptions (`@(subscribe ...)`), the substrate the guide uses end-to-end. Pick this if you have no other constraint.
- **UIx** — a CLJS adapter targeting React function components and hooks idiomatically. Useful if you're integrating with a JS-side codebase that expects React fn-components, or if you want UIx's compile-time JSX-style ergonomics.
- **Helix** — same shape as UIx in spirit; pick it if your team already uses Helix.

Each ships as its own Maven artefact alongside core (per Strategy B — see [`spec/MIGRATION.md`](../../spec/MIGRATION.md)):

```clojure
;; deps.edn — Reagent (the canonical "first app" stack)
{:deps {day8/re-frame2          {:mvn/version "2.0.0"}
        day8/re-frame2-reagent  {:mvn/version "2.0.0"}
        reagent                   {:mvn/version "2.0.0"}}}

;; deps.edn — UIx
{:deps {day8/re-frame2          {:mvn/version "2.0.0"}
        day8/re-frame2-uix      {:mvn/version "2.0.0"}
        com.pitch/uix.core       {:mvn/version "..."}}}

;; deps.edn — Helix
{:deps {day8/re-frame2          {:mvn/version "2.0.0"}
        day8/re-frame2-helix    {:mvn/version "2.0.0"}
        lilactown/helix          {:mvn/version "..."}}}
```

Per the [feature-opt-in story](../../spec/MIGRATION.md), core ships with **none** of the substrate adapters baked in — you add the artefact for the substrate you've picked. The same pattern applies to optional capabilities (state machines, routing, HTTP, schemas, SSR, time-travel) — each ships as its own artefact, and an app that doesn't use a feature doesn't bundle its code.

The `dispatch`, `subscribe`, and `reg-view` primitives are identical across substrates; the difference shows up in the mount call (`reagent.dom.client/render` vs `uix.dom/render-root` vs Helix's mount fn) and in how the view body composes — Reagent uses hiccup, UIx and Helix use their own component DSLs. The pattern survives.

## `init!` and how the adapter gets wired

The line `(rf/init! reagent-adapter/adapter)` in chapter 03's `run` deserves a closer look — it's where the adapter is bound to the runtime.

The call shape is fixed:

```clojure
;; Pass the adapter you want — explicit, always.
(rf/init! reagent-adapter/adapter)
```

Each adapter namespace exports an `adapter` Var (the nine-fn spec map Spec 006 documents). You require the namespace and pass the Var. **Explicit at the call site, every time.** Reading any app's `run` function tells you exactly which adapter the runtime is wired to, with no ns-load side-effects to chase.

Calling `(rf/init!)` with no args raises an `ArityException` at compile / load time (the no-arg arity is not defined). Calling `(rf/init! :reagent)` (or any non-map value) and `(rf/init! nil)` raise `:rf.error/no-adapter-specified` at runtime — the only legal call shape is `(rf/init! adapter-map)`. The runtime error message points back at the adapter-ns + adapter-Var pattern so the recovery path is obvious.

The same call shape applies to every adapter — pick the right `adapter` Var and pass it:

```clojure
(require '[re-frame.adapter.uix :as uix])
(rf/init! uix/adapter)

(require '[re-frame.adapter.helix :as helix])
(rf/init! helix/adapter)

(require '[re-frame.ssr :as ssr])   ;; JVM-side server bootstrap
(rf/init! ssr/adapter)
```

For a mixed-substrate app — say a build that imports both Reagent and UIx — pick the active adapter by passing the right Var. There is no multi-adapter ambiguity to resolve at boot: only one adapter is ever installed, and the call site names it.

## A slim Reagent for ship-size

For apps where bundle size matters, the `day8/reagent-slim` artefact wires re-frame2 to a Reagent rewrite that omits server-rendering and large-runtime parts (no `reagent.impl.*`, no `react-dom/server`). The published adapter ns is `re-frame.adapter.reagent` — the same path as the canonical adapter — so downstream apps pick the adapter by their `deps.edn` coord, not by import line. Depend on exactly one of `{day8/re-frame2-reagent, day8/reagent-slim}`. The same counter mounted on the slim adapter lives at [`examples/reagent/counter_slim_and_fast/`](../../examples/reagent/counter_slim_and_fast/) — the event handlers, subs, and views are byte-for-byte identical to the canonical example; only the requires (`reagent2.dom.client` instead of `reagent.dom.client`) and the adapter Var passed to `init!` change. (Note: the in-tree adapter ns in this monorepo is `re-frame.adapter.reagent-slim` so it coexists with the bridge on the same shadow-cljs classpath; the publication-time rename to `re-frame.adapter.reagent` happens on the release runner. See [`implementation/adapters/reagent-slim/README.md`](../../implementation/adapters/reagent-slim/README.md) for details.) Reach for it when you've measured a ship-size problem and confirmed the missing capabilities aren't on your hot path.

## Where next

If you've finished the chapters, [20 — Where to go next](20-where-next.md) is the one-screen exit pointer — examples, pattern docs, the API ref, the runtime companion docs, the spec. The reference for the adapter contract itself is [Spec 006 — Reactive substrate](../../spec/006-ReactiveSubstrate.md).
