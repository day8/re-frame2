# Use UIx, Helix, or reagent-slim

You're adopting re-frame2 but your team writes React function components — UIx or Helix, not Reagent's hiccup. Or you're on Reagent and the shipped bundle is a measured problem. This page is the recipe for running the same app on a different substrate:

> **Same app, four substrates — the only line that changes is `init!`.** Events, subscriptions, effects, and `app-db` never learn which React wrapper renders them; the boot call names the substrate, and only the view bodies speak its notation.

> **Coming from Redux?** The adapter plays react-redux's role — `frame-provider` is `<Provider>`, `use-subscribe` is `useSelector` — except the binding is a value you pass explicitly at boot, and exactly one is ever installed.

## What an adapter actually owns

Everything upstream of rendering — registry, dispatch loop, handlers, subscriptions, effects — is value-shuffling over Clojure maps, and none of it imports React. The **adapter** is a small map of functions at the one boundary where re-frame2 touches a rendering library: it provides the reactive container `app-db` sits in, notices when a subscription's value changed and schedules dependent components to re-render, and mounts the tree. It does *not* know what events are, what your handlers do, or what `app-db` looks like. You almost never write one — you pick one of the four that ship, name it at boot, and forget it. The full contract (nine functions, the invalidation semantics, what an adapter must never do) is [Spec 006](../../../spec/006-ReactiveSubstrate.md).

## The one line that changes

The boot shape is the one from the [quick start](../quickstart.md). The substrate decision is the first line of it:

```clojure
(defn run []
  (rf/init! reagent-adapter/adapter)   ;; ← the substrate decision, all of it
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:app/initialise]))
  ;; ... mount the root inside a frame-provider ...
  )
```

Each adapter namespace exports an `adapter` Var; you require the namespace and pass the Var:

```clojure
;; Reagent — the canonical pick
(require '[re-frame.adapter.reagent :as reagent-adapter])
(rf/init! reagent-adapter/adapter)

;; UIx
(require '[re-frame.adapter.uix :as uix-adapter])
(rf/init! uix-adapter/adapter)

;; Helix
(require '[re-frame.adapter.helix :as helix-adapter])
(rf/init! helix-adapter/adapter)
```

reagent-slim's require and `init!` are byte-identical to stock Reagent's — your deps.edn coordinate decides which you get (see below).

There is no registry and no auto-install. `(rf/init!)` with no argument doesn't compile (the arity doesn't exist), and a keyword or `nil` raises `:rf.error/no-adapter-specified` — any app's boot function names its substrate in plain sight. One adapter per runtime: a build may carry two on its classpath, but `init!` installs exactly one. Each adapter is its own artefact next to the core; an app bundles only the one it depends on:

| Substrate | Coordinate | View library |
|---|---|---|
| Reagent | `day8/re-frame2-reagent` | `reagent` (hiccup) |
| UIx | `day8/re-frame2-uix` | `com.pitch/uix.core` (UIx 2 publishes as Maven 1.x) |
| Helix | `day8/re-frame2-helix` | `lilactown/helix` (0.2.x) |
| reagent-slim | `day8/reagent-slim` | `reagent2` (ships inside it) |

re-frame2 is pre-alpha: these coordinates publish with the first public release; inside the repo the adapters build from [`implementation/adapters/`](../../../implementation/adapters/).

## UIx and Helix: the React-hooks pair

Your dataflow layer ports without edits. The view layer uses the substrate's own idiom — here is the counter's button row in UIx:

```clojure
;; Adapted from examples/uix/counter_uix/core.cljs
(ns my-app.views
  (:require [uix.core :refer [$ defui]]
            [uix.dom  :as uix-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.uix :as uix-adapter]))

(defui counter-buttons []
  (let [count    (uix-adapter/use-subscribe [:counter/value])
        dispatch (:dispatch (rf/frame-handle))]
    ($ :div
       ($ :button {:on-click #(dispatch [:counter/dec])} "-")
       ($ :span {:style #js {:margin "0 1em"}} count)
       ($ :button {:on-click #(dispatch [:counter/inc])} "+"))))
```

Three rules govern every UIx and Helix component:

- **Read subs with `use-subscribe`** — a React hook (over `useSyncExternalStore`), the substrate's native "re-render when this changes" idiom.
- **Take `dispatch` off `(rf/frame-handle)` at render time.** The click fires later, outside render, where no frame context exists — but the handle captured the frame when the component rendered, so the closed-over `dispatch` still targets it. Never call a bare `rf/dispatch` from a callback.
- **There is no `reg-view` macro here.** That sugar is Reagent-only; UIx components are plain `defui`, Helix components plain `defnc`. (`rf/reg-view*` exists for the rare component that needs a registry id.)

Mount the root inside the adapter's `frame-provider`, with idiomatic `$` trailing children:

```clojure
;; react-root is your (uix-dom/create-root (js/document.getElementById "app"))
(uix-dom/render-root
  ($ uix-adapter/frame-provider {:frame :rf/default}
     ($ counter-app))
  react-root)
```

A tree rendered with no provider raises `:rf.error/no-frame-context` at the first `use-subscribe` — deliberate; the frame is never inferred ([Frames](../concepts/frames.md)).

Helix is the same decisions in Helix notation: `defnc` components built with `helix.dom`, the same `use-subscribe` (from `re-frame.adapter.helix`), the same `frame-handle` dispatch, the same `($ helix-adapter/frame-provider {:frame ...} ...)` mount over `react-dom/client`'s `createRoot` — compare [`examples/helix/counter_helix/`](../../../examples/helix/counter_helix/) line-for-line with [`examples/uix/counter_uix/`](../../../examples/uix/counter_uix/). All three adapters read the *same* React context object for frame routing, so a provider chain even composes across substrates.

## reagent-slim: kilobytes for capability

Slim is not a fourth view paradigm — it's Reagent with a decade of legacy surface removed, for client-only apps where ship-size is a *measured* problem. The trade:

- **Payoff:** roughly 7–10 KB gzipped off a typical app (25–33% of the Reagent layer), up to ~22–27 KB for apps using Reagent's HTML-export path. These are analytical estimates pending build-measured validation — and runtime speed is marginally better at best. Go slim for kilobytes, not frame rate.
- **Constraints:** React 19 only; no `react-dom/server` (HTML export is a small pure-CLJS serializer in `reagent2.dom.server` instead); the class-component escape hatch is capped to seven lifecycle keys.

Mechanically: swap your `reagent.*` requires to `reagent2.*` (e.g. `reagent2.dom.client` for `reagent.dom.client`) and pick slim **by deps coordinate, not by import line** — the published adapter namespace is `re-frame.adapter.reagent`, same as stock, so your app depends on exactly one of `{day8/re-frame2-reagent, day8/reagent-slim}` and the code reads identically either way. The worked twin is [`examples/reagent-slim/counter_slim_and_fast/`](../../../examples/reagent-slim/counter_slim_and_fast/), whose events, subs, and views are byte-for-byte the stock Reagent counter's. If you might server-render someday, stay on stock.

## What carries over, what doesn't

| Surface | Reagent / slim | UIx | Helix |
|---|---|---|---|
| Events, subs, fx, `app-db` | identical | identical | identical |
| Read a sub in a view | `@(subscribe [:q])` | `(uix-adapter/use-subscribe [:q])` | `(helix-adapter/use-subscribe [:q])` |
| Dispatch from a callback | `dispatch` injected by `reg-view` | `(:dispatch (rf/frame-handle))` | `(:dispatch (rf/frame-handle))` |
| View form | `reg-view` + hiccup | `defui` + `$` | `defnc` + `helix.dom` |
| `frame-provider` | `[rf/frame-provider {:frame f} [app]]` | `($ uix-adapter/frame-provider {:frame f} ($ app))` | `($ helix-adapter/frame-provider {:frame f} ($ app))` |

One Reagent footgun doesn't port at all: the lazy-seq deref trap (the "wrapped in doall" console warning) exists because Reagent tracks derefs during render — hooks capture their dependency at call time, so UIx and Helix are immune by construction.

To verify the claim rather than trust it: port the app, run it, and open Xray. The epoch ledger and event rows are indistinguishable from the Reagent run — the instrumentation reads the core, and the core never knew which substrate was rendering.

## Which substrate, and what ships for it

Reagent is the canonical substrate — the full example set, and this guide's notation throughout. Reach for UIx or Helix when your team or host codebase is React-function-component native; each carries a **curated** example set rather than a full mirror: counter + login (the cross-substrate parity pair) plus one design-led app — an analytics dashboard for UIx ([`examples/uix/dashboard_uix/`](../../../examples/uix/dashboard_uix/)), a process monitor for Helix ([`examples/helix/process_monitor_helix/`](../../../examples/helix/process_monitor_helix/)). Slim is stock Reagent minus kilobytes, when you've measured that they matter.

---

**You can now:**

- Boot the same app on Reagent, UIx, Helix, or reagent-slim by swapping one require and the `init!` Var.
- Write UIx/Helix views with `use-subscribe` and a frame-carrying `dispatch` taken off `(rf/frame-handle)` at render time.
- Choose slim-vs-stock by measurement, knowing exactly what slim trades away.
- Say what an adapter owns — the reactive container, change-tracking, and mounting — and what it never touches.

**Next:** [Views: pure functions of data](../concepts/views.md) · [Configure dev and production builds](configure-dev-and-prod.md)
