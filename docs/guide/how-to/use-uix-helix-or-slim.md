# Use UIx, Helix, or reagent-slim

You're adopting re-frame2, but your team writes React function components in UIx or Helix, not Reagent's hiccup. Or you're already on Reagent and the shipped bundle has grown too big for comfort. Either way, this page shows you how to run the same app on a different **substrate** — the React-rendering layer underneath your views — without touching events, subscriptions, effects, or `app-db`. By the end you'll be able to boot one app on any of four substrates, know exactly which line changes, write UIx/Helix views that read subs and dispatch correctly, scope frames into a React subtree, and recognise the handful of errors the framework throws when you get the boundary wrong.

> **Same app, four substrates — the only line that changes is `init!`.** Events, subscriptions, effects, and `app-db` never learn which React wrapper renders them. The boot call names the substrate. Only the view bodies speak its notation. That's the whole story; the rest of this page is detail.

> **Coming from Redux?** The adapter plays react-redux's role — `frame-provider` is `<Provider>`, `use-subscribe` is `useSelector` — with two differences. The binding is a value you pass explicitly at boot rather than a package you import, and exactly one is ever installed per runtime. No hidden default, no autowiring.

## What an adapter actually owns

Everything upstream of rendering is value-shuffling over Clojure maps. That's the registry, the dispatch loop, your handlers (the functions that compute new state from an event), your subscriptions (the queries that read state for a view), and your effects (the descriptions of side-effects to run). None of it imports React, which is precisely why none of it cares about the substrate.

The adapter, then, is the one piece that does care. It's a small map of functions sitting at the single boundary where re-frame2 touches a rendering library. It provides the reactive container that `app-db` — your app's single state map — lives in. It notices when a subscription's value changed and schedules the dependent components to re-render. And it mounts the tree. That's the entire job description.

What the adapter does *not* know is just as important: it has no idea what events are, what your handlers do, or what `app-db` looks like. It's a render-side power adapter, not a brain. You'll almost never write one — you pick one of the four that ship, name it at boot, and forget it exists. If you ever do want the full contract — ten entries (six required + three optional + one lifecycle), the invalidation semantics, the list of things an adapter must never do — it's all in [Spec 006](../../../spec/006-ReactiveSubstrate.md).

> **The ten entries, named once.** Required: `make-state-container`, `read-container`, `replace-container!`, `make-derived-value`, `render`, `render-to-string`. Optional (the core falls back when absent): `subscribe-container`, `register-context-provider`, `flush-render!`. Lifecycle: `dispose-adapter!`. You never call these — they're the contract the four shipped adapters satisfy on your behalf. They're listed here so that when [Spec 006](../../../spec/006-ReactiveSubstrate.md) says "the universal half" (the first four + dispose) versus "the React-shaped half" (`render` / `render-to-string` / `register-context-provider`), you know which functions it means.

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

There's deliberately no registry and no auto-install here, because the project holds boot to one rule: it must name its substrate in plain sight. `(rf/init!)` with no argument doesn't even compile — that arity doesn't exist. A keyword, a `nil`, or anything that isn't the adapter spec map raises `:rf.error/no-adapter-specified`, whose message says it plainly: *"rf/init! takes the adapter spec map directly — there is no keyword form, no nil form, and no default-adapter registry."* So you can open any app's boot function and read its substrate straight off the page, no spelunking required.

> **Install once, install one.** Calling `init!` a second time after an adapter is already installed raises `:rf.error/adapter-already-installed` — the runtime won't silently swap substrates underneath a running app. One adapter, installed once, for the life of the runtime.

A build *may* carry two adapters on its classpath, but `init!` installs exactly one. Each adapter is its own artefact next to the core, so an app bundles only the one it depends on:

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

- **Read subs with `use-subscribe`.** It's a React hook built on `useSyncExternalStore`, which *is* the substrate's native "re-render when this changes" mechanism — so a re-frame2 subscription behaves like any other hook your team already trusts. If you've written a `useSelector`, you've written this. It resolves the frame from the surrounding provider; the 2-arg form `(use-subscribe frame-id [:q …])` pins the read to an explicit frame instead (the same explicit-frame escape hatch Reagent's `@(rf/subscribe frame-id [:q])` gives you).
- **Take `dispatch` off `(rf/frame-handle)` at render time.** Here's the catch worth understanding. The click fires *later*, outside render, where no frame context exists. But the handle captured the frame back when the component rendered, so the closed-over `dispatch` still targets the right one. That's why you grab it during render and never reach for a bare `rf/dispatch` inside a callback — a bare `dispatch` has no frame to aim at by the time the user clicks.
- **There is no `reg-view` macro here.** That sugar is Reagent-only. UIx components are plain `defui`, Helix components plain `defnc`. (`rf/reg-view*` exists for the rare component that needs a registry id, but you'll reach for it about as often as you reach for `forwardRef`.)

### The frame-handle is a whole operation bundle

`(rf/frame-handle)` isn't a dispatch function — it's a small map of *every* frame-locked operation, captured the instant you call it:

```clojure
(rf/frame-handle)
;; =>
{:frame         :rf/default
 :dispatch      (fn ([event]) ([event opts]))   ;; async-dispatch into the captured frame
 :dispatch-sync (fn ([event]) ([event opts]))   ;; synchronous variant — drains before returning
 :subscribe     (fn [query-v])}                 ;; one-shot read (not reactive — see below)
```

You'll most often destructure `:dispatch`, but all four entries are there. `:dispatch-sync` is the one you want when an event must settle before the next line runs (initialisation, a confirm-then-read flow); `:subscribe` is a plain frame-locked *read* of a sub's current value — handy inside a callback where you need to peek at state without making the component reactive on it. (For *reactive* reads that re-render the component, use the `use-subscribe` hook, not the handle's `:subscribe`.) The captured frame is authoritative: a per-call `:frame` in the dispatch opts can't override it — the handle is locked to one frame for life.

The no-arg `(rf/frame-handle)` captures the *ambient* frame at call time, which is exactly what you want inside a component render (the surrounding provider's frame). Outside any provider — an async callback, a tool, a test — there's no ambient frame to capture, so the no-arg form raises `:rf.error/no-frame-context`. For those cases reach for the 1-arg `(rf/frame-handle frame-id)`, which locks the bundle to a named frame with no surrounding scope required:

```clojure
;; A WebSocket handler fires long after render, outside any frame scope.
;; Lock a handle to the frame by name at setup time, then dispatch from it.
(let [{:keys [dispatch]} (rf/frame-handle :rf/default)]
  (ws/on-message (fn [msg] (dispatch [:ws/incoming msg]))))
```

> **Why a bundle and not a global `dispatch`?** Because re-frame2 never *infers* a frame from absence. A bare `rf/dispatch` inside a callback has no ambient frame by the time it fires (the render scope is long gone) and would raise `:rf.error/no-frame-context` — which is the *good* failure, far better than a guessed-wrong frame. The handle is how you carry the right frame across that async gap. See [Frames](../concepts/frames.md) for the full story on why the frame is always explicit.

### Mounting: scope an existing frame into the subtree

Mount the root inside the adapter's `frame-provider-existing`, which scopes the already-registered frame for the subtree, using idiomatic `$` trailing children:

```clojure
;; react-root is your (uix-dom/create-root (js/document.getElementById "app"))
(uix-dom/render-root
  ($ uix-adapter/frame-provider-existing {:frame :rf/default}
     ($ counter-app))
  react-root)
```

Children ride the native `$` trailing-args channel — `($ frame-provider-existing {:frame :f} ($ a) ($ b))` — exactly the shape every other UIx/Helix component uses. There's no `:children` prop-map key to remember (forgetting it used to silently drop the subtree; that footgun is gone by construction).

> **A missing provider fails loud, on purpose.** A tree rendered with no provider raises `:rf.error/no-frame-context` at the first `use-subscribe`. And `frame-provider-existing` itself is strict: its `:frame` is **required** and must be a keyword. A missing or `nil` `:frame` raises `:rf.error/no-frame-context`; a non-`nil` but non-keyword `:frame` (a string, a number) raises the more specific `:rf.error/bad-frame-provider-arg`. That's all deliberate — re-frame2 never *infers* a frame from absence, because a guessed-wrong frame is a debugging nightmare and a thrown error is a one-line fix.

Helix is the same decisions in Helix notation: `defnc` components built with `helix.dom`, the same `use-subscribe` (this time from `re-frame.adapter.helix`), the same `frame-handle` dispatch, and the same `($ helix-adapter/frame-provider-existing {:frame ...} ...)` mount — here over `react-dom/client`'s `createRoot`. If you want to see it side by side, compare [`examples/helix/counter_helix/`](../../../examples/helix/counter_helix/) line-for-line with [`examples/uix/counter_uix/`](../../../examples/uix/counter_uix/); the diff is notation, nothing more. All three adapters read the *same* React context object for frame routing, which means a provider chain even composes across substrates — a Reagent provider wrapping a UIx subtree resolves correctly.

### Two providers, one family: scope vs own

There are *two* members of the `frame-provider` name family, and picking the wrong one is the most common mount-time stumble. They differ in one word: **ownership**.

- **`frame-provider-existing`** — *scope only*. The frame already exists (you called `reg-frame` at boot, or a parent owns it); this provider merely makes its id available to the subtree. It creates nothing, destroys nothing. It takes exactly one opt: `:frame` (a required keyword id). This is the scope-into-React counterpart to the lexical `rf/with-frame`.
- **`frame-provider`** — *own the lifetime*. It **creates** a frame on mount, **provides** its id to descendants, and **destroys** it on unmount. It takes the same construction opts as `rf/make-frame`: `:id` (a required keyword), `:images`, and `:initial-events` (the ordered setup events dispatched synchronously right after creation). Reach for this when a subtree owns a frame's whole lifetime — a modal, a tab, a per-tenant panel that should clean up after itself.

```clojure
;; OWN a frame's lifetime: create on mount, run its setup, destroy on unmount.
($ uix-adapter/frame-provider
   {:id :checkout
    :images [checkout-image]
    :initial-events [[:rf/set-db {}] [:checkout/initialise]]}
   ($ checkout-app))
```

> **Gotcha: `:frame` vs `:id`.** These are not interchangeable, and the framework will tell you so. `frame-provider` (the owning one) wants `:id`; pass it a `:frame` key with no `:id` and it raises `:rf.error/owned-frame-provider-missing-id` — the message points you straight at the fix (use `:id`, or switch to `frame-provider-existing` if you only meant to scope). Conversely, hand a lifecycle opt (`:id` / `:images` / `:initial-events`) to the *scope-only* `frame-provider-existing` and it raises `:rf.error/frame-provider-existing-lifecycle-opt`, because a scope-only provider neither creates nor owns a frame. The two errors are a matched pair: each one names the provider you probably meant to call.

> **Idempotent re-mount is safe.** Re-mounting a `frame-provider` under the same `:id` — hot reload, React StrictMode's dev double-invoke, a Story re-evaluation — does **not** destroy durable state. `make-frame` is idempotent replacement and the destroy-on-unmount is deferred and cancelled by a re-acquire. You don't have to special-case dev tooling.

> **Note on `:initial-events`.** Frames always start with `app-db = {}` — there's no `:db` config key. Seeding initial state is itself an event, `[:rf/set-db {…}]`, dispatched as the first `:initial-events` step. If you were looking for the retired `:initial-db` or `:on-create` keys, this is where they went: setup is now ordinary events through the ordinary dispatch pipeline. See [Frames](../concepts/frames.md) for the full init surface.

## reagent-slim: kilobytes for capability

Slim isn't a fourth view paradigm — that trips people up, so let's be clear up front. It's plain Reagent with a decade of legacy surface removed, aimed at client-only apps where ship-size is a *measured* problem. Think of it as Reagent on a diet, not a different language. Here's the trade you're making:

- **Payoff:** roughly 7–10 KB gzipped off a typical app (25–33% of the Reagent layer), and up to ~22–27 KB for apps using Reagent's HTML-export path. These are analytical estimates pending build-measured validation. Runtime speed is marginally better at best, so go slim for kilobytes, not frame rate — it's a download-size lever, not a performance one.
- **Constraints:** React 19 only. No `react-dom/server` — HTML export is handled by a small pure-CLJS serializer in `reagent2.dom.server` instead. The class-component escape hatch is capped to seven lifecycle keys (which is six more than most apps use).

Mechanically it's a small swap: change your `reagent.*` requires to `reagent2.*` (for example, `reagent2.dom.client` for `reagent.dom.client`). You pick slim by deps coordinate, not by import line — the published adapter namespace is `re-frame.adapter.reagent`, exactly the same string as stock. So your app depends on exactly one of `{day8/re-frame2-reagent, day8/reagent-slim}`, and the boot code reads identically either way. Concretely, the migration is three line-edits: `react`/`react-dom` to 19.x in `package.json`; `reagent.dom/render` → `reagent2.dom.client/{create-root, render}`; and `reagent.dom/unmount-component-at-node` → `reagent2.dom.client/unmount`. (If you have a `r/dom-node` call, it moves to a `:ref` callback — `findDOMNode` is gone in React 19.) The worked twin is [`examples/reagent-slim/counter_slim_and_fast/`](../../../examples/reagent-slim/counter_slim_and_fast/), whose events, subs, and views are byte-for-byte the stock Reagent counter's.

> **The imperative escape hatch survives the diet.** Most views are Form-1 / Form-2 and don't notice slim at all. The small fraction that genuinely own a piece of host-DOM lifecycle — a charting library, a map widget — use Reagent's Form-3 class-component shape via `reagent2.core/create-class`, registered through `reg-view*`. Slim caps that to **seven** lifecycle keys (`:component-did-mount`, `:component-did-update`, `:component-will-unmount`, `:should-component-update`, `:get-derived-state-from-props`, `:get-snapshot-before-update`, `:component-did-catch`) — the React-19-era set, six more than the typical app uses. The rule for dispatching from inside a lifecycle callback is the same as everywhere else: capture `(:dispatch (rf/frame-handle))` at *render* time, then call it from the callback — the callback fires after commit, but the closure was established while the frame scope was live.

### Server-rendering and the two HTML paths

There are two different "render to HTML" jobs, and slim treats them differently — worth knowing before you commit:

- **Static HTML export** (clipboard exports, report HTML, anything that leaves the React lifecycle) — `render-to-static-markup`, shipped by slim under `reagent2.dom.server` as a pure-CLJS tree walk. No `react-dom/server`, no hydration attributes. This is the path slim *keeps*, and it's where the ~22–27 KB HTML-export saving comes from (stock Reagent's `render-to-string` pulls in the ~50 KB `react-dom/server` module; the pure-CLJS serializer is ~3–4 KB).
- **Hydrate-able SSR** (server-render markup that the client `hydrate-root`s) — slim does **not** ship this. It lives in the `day8/re-frame2-ssr` seam (per [Spec 011](../../../spec/011-SSR.md)), which the adapter wires through a late-bind hook.

> **Planning to hydrate-able server-render? Weigh the seam first.** Slim drops `react-dom/server`, so the stock `reagent.dom.server/render-to-string` path isn't there. If your SSR is hydrate-able, you'll route it through `day8/re-frame2-ssr` rather than the adapter. If you're not sure you'll ever server-render, the simplest call is to stay on stock Reagent — switching back later is more disruptive than the kilobytes you'd save now. Slim is for apps that *know* they're client-only (or only need offline static-markup export).

## What carries over, what doesn't

| Surface | Reagent / slim | UIx | Helix |
|---|---|---|---|
| Events, subs, fx, `app-db` | identical | identical | identical |
| Read a sub in a view | `@(subscribe [:q])` | `(uix-adapter/use-subscribe [:q])` | `(helix-adapter/use-subscribe [:q])` |
| Read a sub from an explicit frame | `@(subscribe f [:q])` | `(uix-adapter/use-subscribe f [:q])` | `(helix-adapter/use-subscribe f [:q])` |
| Dispatch from a callback | `dispatch` injected by `reg-view` | `(:dispatch (rf/frame-handle))` | `(:dispatch (rf/frame-handle))` |
| View form | `reg-view` + hiccup | `defui` + `$` | `defnc` + `helix.dom` |
| Registry-keyed view (when needed) | `reg-view` | `(rf/reg-view* id render-fn)` | `(rf/reg-view* id render-fn)` |
| Scope an existing frame | `[rf/frame-provider-existing {:frame f} [app]]` | `($ uix-adapter/frame-provider-existing {:frame f} ($ app))` | `($ helix-adapter/frame-provider-existing {:frame f} ($ app))` |
| Own a frame's lifetime | `[rf/frame-provider {:id f :images […]} [app]]` | `($ uix-adapter/frame-provider {:id f :images […]} ($ app))` | `($ helix-adapter/frame-provider {:id f :images […]} ($ app))` |
| Flush renders in a test | Reagent's own `r/flush!` / `act` harness | `(uix-adapter/flush-views!)` | `(helix-adapter/flush-views!)` |

There's one Reagent footgun that doesn't port at all, and that's good news: the lazy-seq deref trap — the "Reactive deref not supported in lazy seq, it should be wrapped in doall" warning. It exists because Reagent tracks derefs *during* render, and a lazy seq can defer a deref until after render has finished. On Reagent the fix is to realise the seq inside the render fn — `(doall (for …))`, `(mapv child @sub)`, or `(into [:<>] (map child) @sub)`. Hooks, by contrast, capture their dependency at call time, so UIx and Helix are immune to that whole class of bug by construction — `use-subscribe` registers the dependency at hook-call time regardless of when any surrounding seq realises. One fewer thing to teach a new hire.

> **Flushing renders in tests.** When a test dispatches against a UIx- or Helix-mounted tree and then wants to read the resulting DOM, the React `useSyncExternalStore` updates haven't settled yet. Call `(uix-adapter/flush-views!)` (or `(helix-adapter/flush-views!)`) after the dispatch — it wraps React's `act()` and settles pending effects. Note this is a *test* helper, deliberately per-adapter-require (you reach for it from test code, not app code). It's distinct from the production-grade `flush-render!` contract function the adapter implements for headless tooling; you won't call that one directly. See [Spec 008 §Adapter-aware test helpers](../../../spec/008-Testing.md) for the rationale.

> **Why this matters.** The promise of substrate independence is only worth as much as your ability to *trust* it. If you'd rather verify the "same app" claim than take it on faith, here's the receipt: port the app, run it, and open Xray. The epoch ledger and event rows are indistinguishable from the Reagent run, because the instrumentation reads the core, and the core never knew which substrate was rendering. Same events, same state transitions, same trace — different pixels.

## Which substrate, and what ships for it

Reagent is the canonical substrate. It has the full example set, and it's this guide's notation throughout, so it's the path of least resistance unless you have a reason to leave it. Reach for UIx or Helix when your team or host codebase is *already* React-function-component native — that's the case where their notation feels like home rather than a detour, and where the impedance match with the surrounding React code pays for itself. Each carries a curated example set rather than a full mirror: counter + login (the cross-substrate parity pair) plus one design-led app. For UIx that's an analytics dashboard ([`examples/uix/dashboard_uix/`](../../../examples/uix/dashboard_uix/)); for Helix a process monitor ([`examples/helix/process_monitor_helix/`](../../../examples/helix/process_monitor_helix/)). And slim is just stock Reagent minus kilobytes — reach for it once you've measured that those kilobytes actually matter, not before.

UIx and Helix differ from each other mostly in surface area, not in how you'd use them here. UIx ships a richer, more instrumented hook layer; Helix is the deliberately *minimal* React wrapper — a smaller surface, no hook auto-instrumentation. For re-frame2's purposes the view-author-facing trio (`use-subscribe`, `frame-handle` dispatch, `frame-provider-existing` mount) is byte-identical between them, so the choice between UIx and Helix is the choice you'd make for any React-CLJS project, not a re-frame2-specific one.

The decision, then, collapses to one question with a default: stay on Reagent unless your host code is React-hooks-native (then UIx or Helix) or your bundle is provably too big and you'll never hydrate-able-server-render (then slim). Whichever you land on, the line that encodes it is the argument to `init!` — and everything above that boundary is the app you already wrote.
