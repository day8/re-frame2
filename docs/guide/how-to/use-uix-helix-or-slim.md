# Use UIx, Helix, or reagent-slim

You're adopting re-frame2, but your team writes React function components in UIx or Helix, not Reagent's hiccup. Or you're already on Reagent and the shipped bundle has grown too big for comfort. Either way, this page shows you how to run the same app on a different **substrate** — the React-rendering layer underneath your views — without touching events, subscriptions, effects, or `app-db`. By the end you'll be able to boot one app on any of four substrates, know exactly which line changes, and write UIx/Helix views that read subs and dispatch correctly.

> **Same app, four substrates — the only line that changes is `init!`.** Events, subscriptions, effects, and `app-db` never learn which React wrapper renders them. The boot call names the substrate. Only the view bodies speak its notation. That's the whole story; the rest of this page is detail.

> **Coming from Redux?** The adapter plays react-redux's role — `frame-provider` is `<Provider>`, `use-subscribe` is `useSelector` — with two differences. The binding is a value you pass explicitly at boot rather than a package you import, and exactly one is ever installed per runtime. No hidden default, no autowiring.

## What an adapter actually owns

Everything upstream of rendering is value-shuffling over Clojure maps. That's the registry, the dispatch loop, your handlers (the functions that compute new state from an event), your subscriptions (the queries that read state for a view), and your effects (the descriptions of side-effects to run). None of it imports React, which is precisely why none of it cares about the substrate.

The adapter, then, is the one piece that does care. It's a small map of functions sitting at the single boundary where re-frame2 touches a rendering library. It provides the reactive container that `app-db` — your app's single state map — lives in. It notices when a subscription's value changed and schedules the dependent components to re-render. And it mounts the tree. That's the entire job description.

What the adapter does *not* know is just as important: it has no idea what events are, what your handlers do, or what `app-db` looks like. It's a render-side power adapter, not a brain. You'll almost never write one — you pick one of the four that ship, name it at boot, and forget it exists. If you ever do want the full contract — ten entries (six required + three optional + one lifecycle), the invalidation semantics, the list of things an adapter must never do — it's all in [Spec 006](../../../spec/006-ReactiveSubstrate.md).

## The one line that changes

The boot shape is the one from the [quick start](../quickstart.md). The substrate decision is its very first line:

```clojure
(defn run []
  (rf/init! reagent-adapter/adapter)   ;; ← the substrate decision, all of it
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:app/initialise]))
  ;; ... mount the root inside a frame-provider ...
  )
```

Each adapter namespace exports an `adapter` Var. Require the namespace and pass that Var to `init!`:

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

reagent-slim is the exception that proves the rule: its require and `init!` are byte-identical to stock Reagent's. There, the choice lives in your `deps.edn` coordinate instead of the import line (more on that below).

There's deliberately no registry and no auto-install here, because the project holds boot to one rule: it must name its substrate in plain sight. `(rf/init!)` with no argument doesn't even compile — that arity doesn't exist. A keyword or `nil` raises `:rf.error/no-adapter-specified`. So you can open any app's boot function and read its substrate straight off the page, no spelunking required.

One adapter per runtime. A build *may* carry two on its classpath, but `init!` installs exactly one. Each adapter is its own artefact next to the core, so an app bundles only the one it depends on:

| Substrate | Coordinate | View library |
|---|---|---|
| Reagent | `day8/re-frame2-reagent` | `reagent` (hiccup) |
| UIx | `day8/re-frame2-uix` | `com.pitch/uix.core` (UIx 2 publishes as Maven 1.x) |
| Helix | `day8/re-frame2-helix` | `lilactown/helix` (0.2.x) |
| reagent-slim | `day8/reagent-slim` | `reagent2` (ships inside it) |

> **Coordinates are not published yet.** re-frame2 is pre-alpha; these coordinates publish with the first public release. Inside the repo the adapters build from [`implementation/adapters/`](../../../implementation/adapters/).

## UIx and Helix: the React-hooks pair

Here's the part people are usually nervous about, and it turns out to be the easy part: your dataflow layer ports without a single edit. Not one. Only the view layer changes, and only to match the substrate's own idiom. Here is the counter's button row written in UIx:

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

Three rules govern every UIx and Helix component, and once they click you won't think about them again:

- **Read subs with `use-subscribe`.** It's a React hook built on `useSyncExternalStore`, which *is* the substrate's native "re-render when this changes" mechanism — so a re-frame2 subscription behaves like any other hook your team already trusts. If you've written a `useSelector`, you've written this.
- **Take `dispatch` off `(rf/frame-handle)` at render time.** Here's the catch worth understanding. The click fires *later*, outside render, where no frame context exists. But the handle captured the frame back when the component rendered, so the closed-over `dispatch` still targets the right one. That's why you grab it during render and never reach for a bare `rf/dispatch` inside a callback — a bare `dispatch` has no frame to aim at by the time the user clicks.
- **There is no `reg-view` macro here.** That sugar is Reagent-only. UIx components are plain `defui`, Helix components plain `defnc`. (`rf/reg-view*` exists for the rare component that needs a registry id, but you'll reach for it about as often as you reach for `forwardRef`.)

Mount the root inside the adapter's `frame-provider-existing`, which scopes the already-registered frame for the subtree, using idiomatic `$` trailing children:

```clojure
;; react-root is your (uix-dom/create-root (js/document.getElementById "app"))
(uix-dom/render-root
  ($ uix-adapter/frame-provider-existing {:frame :rf/default}
     ($ counter-app))
  react-root)
```

> **A missing provider fails loud, on purpose.** A tree rendered with no provider raises `:rf.error/no-frame-context` at the first `use-subscribe`. That's deliberate — re-frame2 never *infers* a frame from absence, because a guessed-wrong frame is a debugging nightmare and a thrown error is a one-line fix. See [Frames](../concepts/frames.md) for why the frame is always explicit.

Helix is the same decisions in Helix notation: `defnc` components built with `helix.dom`, the same `use-subscribe` (this time from `re-frame.adapter.helix`), the same `frame-handle` dispatch, and the same `($ helix-adapter/frame-provider-existing {:frame ...} ...)` mount — here over `react-dom/client`'s `createRoot`. If you want to see it side by side, compare [`examples/helix/counter_helix/`](../../../examples/helix/counter_helix/) line-for-line with [`examples/uix/counter_uix/`](../../../examples/uix/counter_uix/); the diff is notation, nothing more. All three adapters read the *same* React context object for frame routing, which means a provider chain even composes across substrates — a Reagent provider wrapping a UIx subtree resolves correctly.

## reagent-slim: kilobytes for capability

Slim isn't a fourth view paradigm — that trips people up, so let's be clear up front. It's plain Reagent with a decade of legacy surface removed, aimed at client-only apps where ship-size is a *measured* problem. Think of it as Reagent on a diet, not a different language. Here's the trade you're making:

- **Payoff:** roughly 7–10 KB gzipped off a typical app (25–33% of the Reagent layer), and up to ~22–27 KB for apps using Reagent's HTML-export path. These are analytical estimates pending build-measured validation. Runtime speed is marginally better at best, so go slim for kilobytes, not frame rate — it's a download-size lever, not a performance one.
- **Constraints:** React 19 only. No `react-dom/server` — HTML export is handled by a small pure-CLJS serializer in `reagent2.dom.server` instead. The class-component escape hatch is capped to seven lifecycle keys (which is six more than most apps use).

Mechanically it's a small swap: change your `reagent.*` requires to `reagent2.*` (for example, `reagent2.dom.client` for `reagent.dom.client`). You pick slim by deps coordinate, not by import line — the published adapter namespace is `re-frame.adapter.reagent`, exactly the same string as stock. So your app depends on exactly one of `{day8/re-frame2-reagent, day8/reagent-slim}`, and the boot code reads identically either way. The worked twin is [`examples/reagent-slim/counter_slim_and_fast/`](../../../examples/reagent-slim/counter_slim_and_fast/), whose events, subs, and views are byte-for-byte the stock Reagent counter's.

> **Planning to server-render? Stay on stock.** Slim drops `react-dom/server`. If you might server-render someday, stay on stock Reagent — switching back later is more disruptive than the kilobytes you'd save now. Slim is for apps that *know* they're client-only.

## What carries over, what doesn't

| Surface | Reagent / slim | UIx | Helix |
|---|---|---|---|
| Events, subs, fx, `app-db` | identical | identical | identical |
| Read a sub in a view | `@(subscribe [:q])` | `(uix-adapter/use-subscribe [:q])` | `(helix-adapter/use-subscribe [:q])` |
| Dispatch from a callback | `dispatch` injected by `reg-view` | `(:dispatch (rf/frame-handle))` | `(:dispatch (rf/frame-handle))` |
| View form | `reg-view` + hiccup | `defui` + `$` | `defnc` + `helix.dom` |
| `frame-provider-existing` (scope) | `[rf/frame-provider-existing {:frame f} [app]]` | `($ uix-adapter/frame-provider-existing {:frame f} ($ app))` | `($ helix-adapter/frame-provider-existing {:frame f} ($ app))` |

There's one Reagent footgun that doesn't port at all, and that's good news: the lazy-seq deref trap — the "Reactive deref not supported in lazy seq, it should be wrapped in doall" warning. It exists because Reagent tracks derefs *during* render, and a lazy seq can defer a deref until after render has finished. Hooks, by contrast, capture their dependency at call time, so UIx and Helix are immune to that whole class of bug by construction. One fewer thing to teach a new hire.

> **Why this matters.** The promise of substrate independence is only worth as much as your ability to *trust* it. If you'd rather verify the "same app" claim than take it on faith, here's the receipt: port the app, run it, and open Xray. The epoch ledger and event rows are indistinguishable from the Reagent run, because the instrumentation reads the core, and the core never knew which substrate was rendering. Same events, same state transitions, same trace — different pixels.

## Which substrate, and what ships for it

Reagent is the canonical substrate. It has the full example set, and it's this guide's notation throughout, so it's the path of least resistance unless you have a reason to leave it. Reach for UIx or Helix when your team or host codebase is *already* React-function-component native — that's the case where their notation feels like home rather than a detour, and where the impedance match with the surrounding React code pays for itself. Each carries a curated example set rather than a full mirror: counter + login (the cross-substrate parity pair) plus one design-led app. For UIx that's an analytics dashboard ([`examples/uix/dashboard_uix/`](../../../examples/uix/dashboard_uix/)); for Helix a process monitor ([`examples/helix/process_monitor_helix/`](../../../examples/helix/process_monitor_helix/)). And slim is just stock Reagent minus kilobytes — reach for it once you've measured that those kilobytes actually matter, not before.

The decision, then, collapses to one question with a default: stay on Reagent unless your host code is React-hooks-native (then UIx or Helix) or your bundle is provably too big and you'll never server-render (then slim). Whichever you land on, the line that encodes it is the argument to `init!` — and everything above that boundary is the app you already wrote.
