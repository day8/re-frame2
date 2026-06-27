# Boot and mount an app

You've written events, subscriptions, and views, and now you need the few lines that turn them into a running page — the entry point your build calls once at load. What you want is an app that mounts on first load, survives a hot reload with its [app-db](../glossary.md#app-db) intact, and re-renders your edited views without re-booting. This page is the recipe for exactly that: the canonical boot shape, split into the three small fns every example uses.

One term carries the page: a [**frame**](../glossary.md#frame) is one isolated, running instance of your app — its own [app-db](../glossary.md#app-db), event queue, and subscription cache. Booting an app is, in the end, *creating a frame and rendering a view into it*. The shape below does that in a way that's safe to run again — on a reload, on a co-required test host — without stacking duplicates or wiping state.

> **Installing the adapter does not create a frame.** `rf/init!` installs the [substrate adapter](../glossary.md#substrate) — the layer that drives your chosen React renderer (Reagent, UIx, Helix). It's a process-wide install, run once, and it makes *no* frame. The frame is created later, by the render root, on the first mount. Keep the two steps distinct in your head: **adapter is process-wide; frame is per-mount.**

> **Coming from React?** This is your `createRoot(...).render(<App/>)` — except the render root carries the app's *state container* with it. In React you'd reach for a `<Provider>` (Redux) or a context to seed and hold state; here the [`frame-provider`](../glossary.md#frame) component *is* that provider, and it creates the frame, seeds it once, and reuses it across reloads. There's no separate store to wire.

## 1. The shape: `boot!`, `mount!`, `run`

Three fns, called in order by one entry point. Here's the whole thing — the Reagent counter, the minimal worked version (UIx and Helix do the identical dance, only the `create-root`/`render` calls differ):

```clojure
(ns counter.core
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [counter.events]
            [counter.subs]
            [counter.views :refer [counter-app]]))

;; ns-load does NO DOM work. This atom holds the React root once mount! makes it.
(defonce react-root (atom nil))

(def app-frame :rf/default)            ; an id we pick; no framework privilege

;; ^:dev/after-load: shadow re-runs this after every reload. The defonce root +
;; lazy create-root mean the root is made once and reused across reloads.
(defn ^:dev/after-load mount! []
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                ;; frame-provider creates + seeds the frame on first mount,
                ;; reuses it (no re-seed) on reload — so app-db survives.
                [rf/frame-provider {:id             app-frame
                                    :initial-events [[:counter/initialise]]}
                 [counter-app]])))

(defn- boot! []                        ; install adapter + listeners, once
  (rf/init! reagent-adapter/adapter))

(defn run []                           ; shadow :init-fn — runs once, at page load
  (boot!)
  (mount!))
```

`run` is the entry point. Wire it as your build's `:init-fn` (e.g. `:init-fn counter.core/run` in `shadow-cljs.edn`); shadow resolves the symbol inside the compiled bundle, so it needs no `^:export`. The rest of the page is the *why* behind each of the four moves in that shape.

## 2. `boot!` — install the adapter, once

`boot!` runs once, before the first render, and does the process-wide setup:

```clojure
(defn- boot! []
  (rf/init! reagent-adapter/adapter))
```

That single `init!` installs the substrate adapter and nothing more. It does **not** create a frame — there's no app-db yet, no view mounted. If your app has host listeners to install (a `popstate`/`hashchange` listener, a `storage` listener), `boot!` is where they go, and you make them **idempotent** — remove-then-add the same Var — so a repeated `run` (a co-required test host calls it) or a reload that redefines the Var never stacks two listeners on the same event:

```clojure
(defn- boot! []
  (rf/init! reagent-adapter/adapter)
  (doto js/window
    (.removeEventListener "hashchange" on-hashchange)   ; remove-then-add
    (.addEventListener "hashchange" on-hashchange)))     ; → idempotent
```

## 3. `mount!` — create the frame with the `frame-provider` ensure form

`mount!` renders, and the render root is a [`frame-provider`](../glossary.md#frame) — the component that *creates* the frame. The merged **ensure form** carries the id and the seed in one map:

```clojure
[rf/frame-provider {:id             app-frame
                    :initial-events [[:counter/initialise]]}
 [counter-app]]
```

It does three things, in order, exactly once each over the life of the page:

- **Creates the frame on first mount.** The `:id` names it; the frame — its app-db, queue, sub cache — comes into being the first time this provider renders.
- **Applies the config.** Any frame-level keys on the map (`:url-bound?`, `:drain-depth`, `:observability`, …) configure the new frame as it's created.
- **Seeds once via `:initial-events`.** The vector of events runs, in order, against the fresh app-db — `[:counter/initialise]` here folds the starting state in. This is the *only* place the seed runs.

The payoff is the fourth, implicit thing: on a **remount** — which is what a hot reload triggers — the provider finds the frame already exists for that `:id`, **reuses it, and skips re-seeding**. So your app-db survives the reload untouched; the seed events don't fire a second time and clobber the state you were looking at. "Ensure" is the right verb: it ensures the frame exists and is seeded, whether that's the first render or the hundredth.

> **Why a vector of events, not a literal db value?** Seeding through `:initial-events` means the starting state is built the same way every other state change is — by dispatching events through the normal cascade — so it runs your real [handlers](../glossary.md#event-handler), [coeffects](../glossary.md#coeffect), and schema checks. The frame's first app-db is produced by the same machinery as its thousandth, with no special "initial value" back door.

## 4. The hot-reload pair: a `defonce` root and a `^:dev/after-load` remount

Two annotations make the reload work, and they work together:

```clojure
(defonce react-root (atom nil))        ; the root, made once, kept across reloads

(defn ^:dev/after-load mount! []        ; shadow re-runs this after every reload
  ...)
```

- **`defonce` React root.** The root is stored in a `defonce` atom, so it's created exactly once and the *same* root persists across every reload. React 18's `create-root` rejects a second call on a live DOM node — a `defonce` is what keeps you from making that second root and crashing the reload.
- **`^:dev/after-load mount!`.** shadow-cljs re-runs every `^:dev/after-load` fn after it recompiles. So on each save, shadow re-runs `mount!`, which re-renders your *edited* views against the surviving root and the surviving frame. Boot is **not** re-run — `run` fired only at page load — so the adapter isn't reinstalled and the frame isn't recreated.

Put together: edit a view, save, and shadow re-renders the new view into the same root and the same frame, with app-db exactly as you left it. You see the visual change without losing the state you were debugging. That's the whole point of the pair — re-render without re-boot.

## 5. Lazy root creation — no DOM side effect at ns-load

One placement detail makes the whole shape safe: the `create-root` call lives **inside `mount!`**, guarded by `when-not @react-root`, not at the top level of the namespace.

```clojure
(defn ^:dev/after-load mount! []
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app")))) ; lazy
    ...))
```

Loading the namespace therefore performs *no* DOM work — it only `def`s the empty atom and registers handlers, subs, and views. The root is created lazily, the first time `mount!` actually runs. This matters because the namespace gets loaded in places that have no DOM: a test wrapper that `:require`s your `core` ns to drive its events and subs headlessly on Node would crash on a top-level `create-root` (there's no `js/document`). Keeping the DOM touch inside `mount!`, behind the `(exists? js/document)` guard, lets the same namespace load cleanly in a browser and in a Node test. (The examples lean on exactly this — see [`examples/TESTING.md`](../../../examples/TESTING.md) for the test-host side of the contract.)

## The worked examples

The shape above is the minimal version. Two real examples bracket the range:

- [`examples/reagent/counter/core.cljs`](../../../examples/reagent/counter/core.cljs) — the minimal worked boot, essentially the snippet on this page.
- [`examples/reagent/todomvc/core.cljs`](../../../examples/reagent/todomvc/core.cljs) — the fullest version: it adds an idempotent `hashchange` listener in `boot!` and marks the frame `:url-bound? true` in the `frame-provider` so it owns the address bar, seeding with two events (`[:todo/initialise]` then `[:rf.route/handle-url-change …]`).

The UIx and Helix counters do the identical three-fn dance; only the substrate's `create-root`/`render` calls change.

> **From re-frame v1.** v1's `mount-root` did a `reagent/render` and you re-ran it from a `^:dev/after-load` hook by hand; the app's state lived in a single global `app-db` ratom that a reload left alone by luck of it being a `defonce`. The moves are the same — render once, re-render on reload — but the *state container* is now an explicit [frame](../glossary.md#frame) the `frame-provider` creates and owns, seeded declaratively through `:initial-events` rather than by a `dispatch-sync` you remembered to call before the first render. The reload-survives-state property is now a property of the frame, not an accident of a top-level ratom.

The frame lifecycle and the `frame-provider` contract in full are in [Frames](../concepts/frames.md) and [`spec/002-Frames.md`](../../../spec/002-Frames.md).
