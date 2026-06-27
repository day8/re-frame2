# Use UIx, Helix, or reagent-slim

You're adopting re-frame2, but your team writes React function components in UIx or Helix, not Reagent's [hiccup](../glossary.md#hiccup). Or you're already on Reagent and the shipped bundle has grown a little too round for comfort. Either way, this page shows you how to run the *same* app on a different [substrate](../glossary.md#substrate) — the React-rendering layer underneath your [views](../glossary.md#view) — without touching a single [event](../glossary.md#event), [subscription](../glossary.md#subscription), [effect](../glossary.md#effect), or your [app-db](../glossary.md#app-db).

A word on the term *substrate*, since the rest of the page leans on it. A re-frame2 app is two layers stacked. On top sits your dataflow — events, subscriptions, effects, and the app-db map they all read and write — and it's plain Clojure data and functions, with no idea React exists. Underneath sits the [substrate](../glossary.md#substrate): the thin layer that actually drives a React renderer, turning your views into pixels. Reagent is one such substrate; UIx, Helix, and reagent-slim are three more. The whole trick of this page is that you can swap the bottom layer and the top layer never notices.

We'll build it up one step at a time: first the single line that picks a substrate, then a working UIx view, then how its callbacks [dispatch](../glossary.md#dispatch), then how you mount and scope it, and finally how Helix and reagent-slim fit the same mould. By the end you'll be able to boot one app on any of four substrates, write UIx/Helix views that read subs and dispatch correctly, and recognise the handful of errors the framework throws when you get the boundary wrong.

> **Same app, four substrates — the only line that changes is `init!`.** Events, subscriptions, effects, and app-db never learn which React wrapper renders them. The boot call ([`init!`](../glossary.md#init)) names the substrate. Only the view bodies speak its notation. That's the whole story; the rest of this page is detail.

> **Coming from Redux?** The [adapter](../glossary.md#adapter) plays react-redux's role — `frame-provider` is `<Provider>`, `use-subscribe` is `useSelector` — with two differences. The binding is a value you pass explicitly at boot rather than a package you import, and exactly one is ever installed per runtime. No hidden default, no autowiring.

## Step 1 — The one line that changes

Start with the boot shape from the [quick start](../quickstart.md). The substrate decision is its very first line:

```clojure
(defn run []
  (rf/init! reagent-adapter/adapter)   ;; ← the substrate decision, all of it
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:app/initialise]))
  ;; ... mount the root inside a frame-provider ...
  )
```

That's it. To switch substrates, you change that one argument. Each adapter lives in its own namespace and exports a single binding named `adapter` — a plain value. Require the namespace, pass that value to `init!`:

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

reagent-slim follows the same explicit shape — it ships its own adapter binding at `re-frame.adapter.reagent-slim`, and you name it at `init!` exactly like the others:

```clojure
;; reagent-slim
(require '[re-frame.adapter.reagent-slim :as reagent-slim])
(rf/init! reagent-slim/adapter)
```

What makes slim *feel* like stock Reagent isn't the boot line — it's that the view layer is the same Reagent you already write. The two real differences are downstream: you depend on `day8/reagent-slim` in your `deps.edn` instead of `day8/re-frame2-reagent`, and your `reagent.*` view imports become `reagent2.*` (we'll get to both at the end).

There's deliberately no registry and no auto-install here, because the project holds boot to one rule: it must name its substrate in plain sight. Open any app's boot function and you can read its substrate straight off the page — no spelunking required. The framework enforces this strictly:

- `(rf/init!)` with no argument is an arity error — `init!` has exactly one arity (it takes the adapter spec map), so there's no zero-arg form to fall through to.
- A keyword, a `nil`, or anything that isn't the adapter spec map raises `:rf.error/no-adapter-specified`, whose message says it plainly: *"rf/init! takes the adapter spec map directly — there is no keyword form, no nil form, and no default-adapter registry."*

> **Install once, install one.** Calling `init!` a second time after an adapter is already installed raises `:rf.error/adapter-already-installed` — the runtime won't silently swap substrates underneath a running app. One adapter, installed once, for the life of the runtime.

> **Going deeper: what is an adapter, really?** Everything upstream of rendering — the [registrar](../glossary.md#registrar), the dispatch loop, your [event handlers](../glossary.md#event-handler), your subscriptions, your effects — is value-shuffling over Clojure maps. None of it imports React, which is precisely why none of it cares about the substrate. The [adapter](../glossary.md#adapter) is the one piece that does: a small map of functions sitting at the single boundary where re-frame2 touches a rendering library. It provides the reactive container app-db lives in, notices when a subscription's value changed and schedules dependent components to re-render, and mounts the tree. That's the entire job description — a render-side power adapter, not a brain. It has no idea what events are, what your handlers do, or what app-db looks like. The full contract is ten entries (six required + three optional + one lifecycle): the required `make-state-container`, `read-container`, `replace-container!`, `make-derived-value`, `render`, `render-to-string`; the optional `subscribe-container`, `register-context-provider`, `flush-render!` (the core falls back when these are absent); and the `dispose-adapter!` lifecycle hook. You never call these — they're the contract the four shipped adapters satisfy on your behalf. See [Spec 006](../../../spec/006-ReactiveSubstrate.md) for the invalidation semantics and the list of things an adapter must never do.

### One adapter per build (the coordinate table)

A build *may* carry two adapters on its classpath, but `init!` installs exactly one. Each adapter is its own artefact next to the core, so an app bundles only the one it depends on:

| Substrate | Coordinate | View library |
|---|---|---|
| Reagent | `day8/re-frame2-reagent` | `reagent` (hiccup) |
| UIx | `day8/re-frame2-uix` | `com.pitch/uix.core` (UIx 2 publishes as Maven 1.x) |
| Helix | `day8/re-frame2-helix` | `lilactown/helix` (0.2.x) |
| reagent-slim | `day8/reagent-slim` | `reagent2` (ships inside it) |

> **Coordinates are not published yet.** re-frame2 is pre-alpha; these coordinates publish with the first public release. Inside the repo the adapters build from [`implementation/adapters/`](../../../implementation/adapters/).

## Step 2 — Write a UIx view

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
        dispatch (:dispatch (rf/capture-frame))]
    ($ :div
       ($ :button {:on-click #(dispatch [:counter/dec])} "-")
       ($ :span {:style #js {:margin "0 1em"}} count)
       ($ :button {:on-click #(dispatch [:counter/inc])} "+"))))
```

Three rules govern every UIx and Helix component, and once they click you won't think about them again:

- **Read subs with `use-subscribe`.** It's a React hook built on `useSyncExternalStore`, which *is* the substrate's native "re-render when this changes" mechanism — so a re-frame2 subscription behaves like any other hook your team already trusts. It resolves the [frame](../glossary.md#frame) from the surrounding provider; the 2-arg form `(use-subscribe frame-id [:q …])` pins the read to an explicit frame instead.
- **Take `dispatch` off `(rf/capture-frame)` at render time.** Grab it during render; never reach for a bare `rf/dispatch` inside a callback. (The next step explains exactly why.)
- **There is no `reg-view` macro here.** That sugar is Reagent-only. UIx components are plain `defui`, Helix components plain `defnc`. (`rf/reg-view*` exists for the rare component that needs a registry id, but you'll reach for it about as often as you reach for `forwardRef`.)

> **For JavaScript developers.** `use-subscribe` *is* `useSelector`. If you've written a `useSelector`, you've written this — it's a hook over `useSyncExternalStore`, the same primitive react-redux uses under the hood. The 2-arg explicit-frame form is the same escape hatch Reagent gives you with `@(rf/subscribe frame-id [:q])`.

## Step 3 — Why callbacks dispatch off the frame api

Step 2's second rule said: grab `dispatch` off `(rf/capture-frame)` during render, never reach for a bare `rf/dispatch` inside a callback. Here's the reason, and what the frame api gives you.

It's the async-boundary rule from [Frames](../concepts/frames.md#the-async-boundary-capture-the-frame): a click handler fires *after* render, on a frameless stack, so a bare `rf/dispatch` inside it has no frame to aim at and raises `:rf.error/no-frame-context`. [`(rf/capture-frame)`](../glossary.md#capture-frame), called *during* render while the provider's frame is in scope, captures that frame as a value the callback closes over — [carried, not found](../glossary.md#frame-identity-is-carried-not-found). In Reagent `reg-view` injects `dispatch` for you; UIx/Helix have no such injection, so you pull it off the frame api yourself.

And the frame api gives you more than just a dispatch function — it's a small map of *every* frame-locked operation, captured the instant you call it:

```clojure
(rf/capture-frame)
;; =>
{:frame         :rf/default
 :dispatch      (fn ([event]) ([event opts]))   ;; async-dispatch into the captured frame
 :dispatch-sync (fn ([event]) ([event opts]))   ;; synchronous variant — drains before returning
 :subscribe     (fn [query-v])}                 ;; one-shot read (not reactive — see below)
```

You'll most often pull `:dispatch` straight off that map (that's what `(:dispatch (rf/capture-frame))` in the view above is doing), but all four entries are there:

- `:dispatch-sync` is the one you want when an event must settle before the next line runs (initialisation, a confirm-then-read flow).
- `:subscribe` is a plain frame-locked *read* of a sub's current value — handy inside a callback where you need to peek at state without making the component reactive on it. (For *reactive* reads that re-render the component, use the `use-subscribe` hook from Step 2, not the frame api's `:subscribe`.)

The captured frame is authoritative: a per-call `:frame` in the dispatch opts can't override it — the frame api is locked to one frame for life.

The no-arg `(rf/capture-frame)` captures the *ambient* frame at call time, which is exactly what you want inside a component render (the surrounding provider's frame). Outside any provider — an async callback, a tool, a test — there's no ambient frame to capture, so the no-arg form raises `:rf.error/no-frame-context`. For those cases reach for the 1-arg `(rf/capture-frame frame-id)`, which locks the bundle to a named frame with no surrounding scope required:

```clojure
;; A WebSocket handler fires long after render, outside any frame scope.
;; Lock a handle to the frame by name at setup time, then dispatch from it.
;; ({:keys [dispatch]} just pulls the :dispatch entry out of the frame api map.)
(let [{:keys [dispatch]} (rf/capture-frame :rf/default)]
  (ws/on-message (fn [msg] (dispatch [:ws/incoming msg]))))
```

> **From re-frame v1.** v1's global `re-frame.core/dispatch` worked anywhere because there was one implicit app; re-frame2 has many frames and never infers one from absence, so the frame api is how you carry the right frame across the async gap. [Frames](../concepts/frames.md#the-one-rule-frame-identity-is-carried-not-found) covers why a guessed default would be a trap.

## Step 4 — Mount it: scope a frame into the subtree

Step 3 said the surrounding provider supplies a view's ambient frame. This is that provider. [`frame-provider`](../glossary.md#frame-provider) — in its `{:frame …}` scope shape — wraps a chunk of your React tree and declares "everything rendered below me reads from *this* frame" — so the `use-subscribe` hooks underneath it know which world to read, and the `capture-frame` captures underneath it know which world to dispatch into. (It's the React-context counterpart to the lexical `rf/with-frame` you may have met elsewhere: same idea, scoped through the component tree instead of through a `let`.)

So the last move is to mount the root inside it. The scope shape takes a `:frame` opt naming an already-registered frame, with the subtree as idiomatic `$` trailing children:

```clojure
;; react-root is your (uix-dom/create-root (js/document.getElementById "app"))
(uix-dom/render-root
  ($ uix-adapter/frame-provider {:frame :rf/default}
     ($ counter-app))
  react-root)
```

Children ride the native `$` trailing-args channel — `($ frame-provider {:frame :f} ($ a) ($ b))` — exactly the shape every other UIx/Helix component uses. There's no `:children` prop-map key to remember (forgetting it used to silently drop the subtree; that footgun is gone by construction).

> **For JavaScript developers.** `frame-provider {:frame …}` is your `<Provider store={...}>`. Same job as react-redux's `<Provider>` — make a store (here, a frame) available to everything rendered beneath it — except this shape never *creates* the store; it just scopes an existing one. The `use-subscribe` hooks below it resolve their frame through this provider, exactly as `useSelector` reads through `<Provider>`.

> **A missing provider fails loud, on purpose.** A tree rendered with no provider raises `:rf.error/no-frame-context` at the first `use-subscribe`. And the scope shape is itself strict: its `:frame` is **required** and must be a keyword. A `nil` `:frame` raises `:rf.error/no-frame-context`; a non-`nil` but non-keyword `:frame` (a string, a number) raises the more specific `:rf.error/bad-frame-provider-arg`; and naming a `:frame` that was never created (or has been destroyed) raises `:rf.error/frame-provider-frame-absent`. That's all deliberate — re-frame2 never *infers* a frame from absence, because a guessed-wrong frame is a debugging nightmare and a thrown error is a one-line fix.

That's a complete UIx app: pick the substrate at boot (Step 1), write `defui` views that read with `use-subscribe` (Step 2) and dispatch off the handle (Step 3), and mount inside `frame-provider {:frame …}` (Step 4). Everything from here builds on those four moves.

## Step 5 — Ensure a view's own frame

The `{:frame …}` scope shape from Step 4 scopes a frame that already exists. The same `frame-provider`'s other shape, `{:id …}`, instead *ensures* one — a modal, a tab, a per-tenant panel that brings its frame into being on first mount. The two config shapes are detailed in [Frames — Two config shapes](../concepts/frames.md#two-config-shapes-scope-an-existing-frame-or-ensure-a-named-one); picking the wrong key is the most common mount-time stumble, so here are the opts each shape takes and the matched errors when you cross them:

- **`{:frame …}` (scope)** — Step 4. One opt: `:frame` (a required keyword id). Creates and destroys nothing.
- **`{:id …}` (ensure)** — brings the frame into being. Takes the same construction opts as `rf/make-frame`: `:id` (a required keyword), `:images` (the [image](../glossary.md#image) — events, subs, effects — the new frame is born with), and `:initial-events` (the ordered setup events dispatched synchronously right after creation). Creates the frame if absent, reuses it without re-seeding if present; **no destroy-on-unmount**.

```clojure
;; ENSURE a view's frame: create on first mount, run its setup, reuse on remount.
($ uix-adapter/frame-provider
   {:id :checkout
    :images [checkout-image]
    :initial-events [[:rf/set-db {}] [:checkout/initialise]]}
   ($ checkout-app))
```

> **Gotcha — the prop map selects the shape.** A `:frame` key selects scope; *anything else* selects ensure, which **requires** a keyword `:id`. So if you mean to ensure a frame but forget `:id` (or pass an empty `{}`), the provider reads it as an ensure shape with no id and raises `:rf.error/ensure-frame-provider-missing-id`. The fix is in the message: pass `:id` to ensure a frame, or `:frame` to scope an existing one.

> **Idempotent re-mount is safe.** Re-mounting the ensure shape under the same `:id` — hot reload, React StrictMode's dev double-invoke, a Story re-evaluation — does **not** destroy durable state or replay `:initial-events`. `make-frame` is idempotent replacement: a remount refreshes config and the image while preserving `app-db`, the sub-cache, and the queue. You don't have to special-case dev tooling.

> **True ownership is explicit.** The ensure shape deliberately does *not* destroy the frame on unmount — a genuine unmount leaves the frame live, and a remount reuses it. When a component should own a frame's whole lifetime (a modal that wants its world torn down on close), make that explicit: `rf/make-frame` + `rf/destroy-frame!` inside a `create-class`, where the component declares it owns both the birth and the death.

> **Gotcha — a captured handle can outlive a destroyed frame.** If you *do* take explicit ownership and `destroy-frame!` a frame, a handle you captured against it (a `capture-frame :that-id` you stashed at setup) can fire its `dispatch` or `subscribe` *after* the teardown — a slow HTTP reply, a `setTimeout`, a WebSocket message that lands late. The framework won't corrupt anything: a `dispatch` / `subscribe` against a frame that's been torn down raises `:rf.error/frame-destroyed`, and the deeper case where a scheduled commit reaches the container *after* it's already gone no-ops behind a guard and emits `:rf.error/write-after-destroy` (recovery `:ignored`). Both are **always-on** errors — they survive production and land in your error listeners, not just the dev trace. The fix is ownership-shaped: cancel the in-flight work when you destroy the frame, or hold the data in a longer-lived frame if it must outlast the widget.

> **From re-frame v1.** There's no `:db` / `:initial-db` / `:on-create` here — a frame always starts `app-db = {}` and you seed it with `[:rf/set-db {…}]` as the first `:initial-events` step, as the `:checkout` example does. [Frames — Seeding initial state](../concepts/frames.md#seeding-initial-state) is the full init surface.

## Step 6 — Helix is the same moves, different notation

Helix is the same decisions in Helix notation: `defnc` components built with `helix.dom`, the same `use-subscribe` (this time from `re-frame.adapter.helix`), the same `capture-frame` dispatch, and the same `($ helix-adapter/frame-provider {:frame ...} ...)` mount — here over `react-dom/client`'s `createRoot`. If you want to see it side by side, compare [`examples/helix/counter_helix/`](../../../examples/helix/counter_helix/) line-for-line with [`examples/uix/counter_uix/`](../../../examples/uix/counter_uix/); the diff is notation, nothing more.

All three React-shaped adapters read the *same* React context object for frame routing, which means a provider chain even composes across substrates — a Reagent provider wrapping a UIx subtree resolves correctly.

> **For JavaScript developers.** UIx and Helix differ from each other the way they would in any React-CLJS project, not in any re-frame2-specific way. UIx ships a richer, more instrumented hook layer; Helix is the deliberately *minimal* React wrapper — a smaller surface, no hook auto-instrumentation. For re-frame2's purposes the view-author-facing trio (`use-subscribe`, `capture-frame` dispatch, `frame-provider {:frame …}` mount) is byte-identical between them.

## Step 7 — reagent-slim: kilobytes for capability

Slim isn't a fourth view paradigm — that trips people up, so let's be clear up front. It's plain Reagent with a decade of legacy surface removed, aimed at client-only apps where ship-size is a *measured* problem. Think of it as Reagent on a diet, not a different language. Here's the trade you're making:

- **Payoff:** roughly 7–10 KB gzipped off a typical app (25–33% of the Reagent layer), and up to ~22–27 KB for apps using Reagent's HTML-export path. These are analytical estimates pending build-measured validation. Runtime speed is marginally better at best, so go slim for kilobytes, not frame rate — it's a download-size lever, not a performance one.
- **Constraints:** React 19 only. No `react-dom/server` — HTML export is handled by a small pure-CLJS serializer in `reagent2.dom.server` instead. The class-component escape hatch is capped to seven `create-class` keys (more than most apps ever touch).

Mechanically it's a small swap: your app depends on exactly one of `{day8/re-frame2-reagent, day8/reagent-slim}`, you boot the matching adapter (`reagent-adapter/adapter` vs `reagent-slim/adapter`), and you change your `reagent.*` requires to `reagent2.*` (for example, `reagent2.dom.client` for `reagent.dom.client`). Slim deliberately ships a *distinct* adapter namespace — `re-frame.adapter.reagent-slim`, the sibling of stock's `re-frame.adapter.reagent` — so the two artefacts can sit side by side and you choose between them by which `adapter` you pass to `init!`. Concretely, the migration is four line-edits:

1. swap the deps coordinate to `day8/reagent-slim`, and point `init!` at `re-frame.adapter.reagent-slim`'s `adapter`;
2. `react` / `react-dom` to 19.x in `package.json`;
3. `reagent.dom/render` → `reagent2.dom.client/{create-root, render}`;
4. `reagent.dom/unmount-component-at-node` → `reagent2.dom.client/unmount`.

(If you have a `r/dom-node` call, it moves to a `:ref` callback — `findDOMNode` is gone in React 19.) The worked twin is [`examples/reagent-slim/counter_slim_and_fast/`](../../../examples/reagent-slim/counter_slim_and_fast/), whose events, subs, and views are byte-for-byte the stock Reagent counter's.

> **The imperative escape hatch survives the diet.** Most views are Form-1 / Form-2 and don't notice slim at all. The small fraction that genuinely own a piece of host-DOM lifecycle — a charting library, a map widget — use Reagent's Form-3 class-component shape via `reagent2.core/create-class`, registered through `rf/reg-view*`. Slim caps `create-class` to **seven** keys: the render fn `:reagent-render`, the four lifecycle hooks `:component-did-mount` / `:component-did-update` / `:component-will-unmount` / `:get-snapshot-before-update`, the React-19 error-boundary callback `:component-did-catch`, and the compile-time `:display-name`. That's exactly the set the real-world Day8 codebases use (re-com, re-frame-10x, and two internal apps), and nothing else — pass any other key and it fails loud at `create-class` time with `:rf.error/create-class-key-unsupported`, naming the offending key and listing the supported set. The deprecated `will-*` lifecycles, `:should-component-update`, and `:get-derived-state-from-props` aren't in the cap because nobody uses them under React 19; the FORM-3 doc carries a migration recipe for each. The rule for dispatching from inside a lifecycle callback is the same as everywhere else: capture `(:dispatch (rf/capture-frame))` at *render* time, then call it from the callback.

### Server-rendering and the two HTML paths

There are two different "render to HTML" jobs, and slim treats them differently — worth knowing before you commit:

- **Static HTML export** (clipboard exports, report HTML, anything that leaves the React lifecycle) — `render-to-static-markup`, shipped by slim under `reagent2.dom.server` as a pure-CLJS tree walk. No `react-dom/server`, no hydration attributes. This is the path slim *keeps*, and it's where the ~22–27 KB HTML-export saving comes from (stock Reagent's `render-to-string` pulls in the ~50 KB `react-dom/server` module; the pure-CLJS serializer is ~3–4 KB).
- **Hydrate-able SSR** (server-render markup that the client `hydrate-root`s) — slim does **not** ship this. It lives in the `day8/re-frame2-ssr` seam (per [Spec 011](../../../spec/011-SSR.md)), which the adapter wires through a late-bind hook.

> **Planning to hydrate-able server-render? Weigh the seam first.** Slim drops `react-dom/server`, so the stock `reagent.dom.server/render-to-string` path isn't there. If your SSR is hydrate-able, you'll route it through `day8/re-frame2-ssr` rather than the adapter. If you're not sure you'll ever server-render, the simplest call is to stay on stock Reagent — switching back later is more disruptive than the kilobytes you'd save now. Slim is for apps that *know* they're client-only (or only need offline static-markup export).

## What carries over, what doesn't

Once you've seen all four substrates, the whole port collapses to one table. The dataflow rows are identical everywhere; only the view-author surface changes:

| Surface | Reagent / slim | UIx | Helix |
|---|---|---|---|
| Events, subs, fx, app-db | identical | identical | identical |
| Read a sub in a view | `@(subscribe [:q])` | `(uix-adapter/use-subscribe [:q])` | `(helix-adapter/use-subscribe [:q])` |
| Read a sub from an explicit frame | `@(subscribe f [:q])` | `(uix-adapter/use-subscribe f [:q])` | `(helix-adapter/use-subscribe f [:q])` |
| Dispatch from a callback | `dispatch` injected by `reg-view` | `(:dispatch (rf/capture-frame))` | `(:dispatch (rf/capture-frame))` |
| View form | `reg-view` + hiccup | `defui` + `$` | `defnc` + `helix.dom` |
| Registry-keyed view (when needed) | `reg-view` | `(rf/reg-view* id render-fn)` | `(rf/reg-view* id render-fn)` |
| Scope an existing frame | `[rf/frame-provider {:frame f} [app]]` | `($ uix-adapter/frame-provider {:frame f} ($ app))` | `($ helix-adapter/frame-provider {:frame f} ($ app))` |
| Ensure a named frame | `[rf/frame-provider {:id f :images […]} [app]]` | `($ uix-adapter/frame-provider {:id f :images […]} ($ app))` | `($ helix-adapter/frame-provider {:id f :images […]} ($ app))` |
| Flush renders in a test | Reagent's own `r/flush!` / `act` harness | `(uix-adapter/flush-views!)` | `(helix-adapter/flush-views!)` |

There's one Reagent footgun that doesn't port at all, and that's good news: the lazy-seq deref trap — the *"Reactive deref not supported in lazy seq, it should be wrapped in doall"* warning. It exists because Reagent tracks derefs *during* render, and a lazy seq can defer a deref until after render has finished. On Reagent the fix is to realise the seq inside the render fn — `(doall (for …))`, `(mapv child @sub)`, or `(into [:<>] (map child) @sub)`.

> **Going deeper: why hooks are immune to the lazy-seq trap.** Hooks capture their dependency at call time, so UIx and Helix sidestep that whole class of bug by construction — `use-subscribe` registers the dependency at hook-call time regardless of when any surrounding seq realises. Reagent's reactivity, by contrast, is *render-tracked*: it records every deref that happens during the render pass, so a deref deferred into an unrealised lazy seq escapes the tracking window. The hook model trades render-tracking for an explicit dependency edge, and that edge doesn't care about evaluation order. One fewer thing to teach a new hire.

> **Flushing renders in tests.** When a test dispatches against a UIx- or Helix-mounted tree and then wants to read the resulting DOM, the React `useSyncExternalStore` updates haven't settled yet. Call `(uix-adapter/flush-views!)` (or `(helix-adapter/flush-views!)`) after the dispatch — it wraps React's `act()` and settles pending effects. This is a *test* helper, deliberately per-adapter-require (you reach for it from test code, not app code). It's distinct from the production-grade `flush-render!` contract function the adapter implements for headless tooling; you won't call that one directly. See [Spec 008 §Adapter-aware test helpers](../../../spec/008-Testing.md) for the rationale.

> **Why this matters.** The promise of substrate independence is only worth as much as your ability to *trust* it. If you'd rather verify the "same app" claim than take it on faith, here's the receipt: port the app, run it, and open [Xray](../glossary.md#xray). The [epoch](../glossary.md#epoch) ledger and event rows are indistinguishable from the Reagent run, because the instrumentation reads the core, and the core never knew which substrate was rendering. Same events, same state transitions, same trace — different pixels.

## Which substrate, and what ships for it

Reagent is the canonical substrate. It has the full example set, and it's this guide's notation throughout, so it's the path of least resistance unless you have a reason to leave it. Reach for UIx or Helix when your team or host codebase is *already* React-function-component native — that's the case where their notation feels like home rather than a detour, and where the impedance match with the surrounding React code pays for itself. Each carries a curated example set rather than a full mirror: counter + login (the cross-substrate parity pair) plus one design-led app. For UIx that's an analytics dashboard ([`examples/uix/dashboard_uix/`](../../../examples/uix/dashboard_uix/)); for Helix a process monitor ([`examples/helix/process_monitor_helix/`](../../../examples/helix/process_monitor_helix/)). And slim is just stock Reagent minus kilobytes — reach for it once you've measured that those kilobytes actually matter, not before.

The decision, then, collapses to one question with a default: stay on Reagent unless your host code is React-hooks-native (then UIx or Helix) or your bundle is provably too big and you'll never hydrate-able-server-render (then slim). Whichever you land on, the line that encodes it is the argument to `init!` — and everything above that boundary is the app you already wrote.
