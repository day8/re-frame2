# Use UIx or reagent-slim

This recipe moves an app's views to UIx, for a team that writes React function components, or to reagent-slim, for a Reagent app whose bundle is too large. Either way you swap the [substrate](../glossary.md#substrate), the layer that drives React underneath your [views](../glossary.md#view), and leave every [event](../glossary.md#event), [subscription](../glossary.md#subscription), [effect](../glossary.md#effect), and [app-db](../glossary.md#app-db) untouched. The boot call ([`init!`](../glossary.md#init)) names the substrate, and only the view code uses its notation.

??? info "Coming from Redux?"

    The [adapter](../glossary.md#adapter) plays react-redux's role (`frame-provider` is `<Provider>`, `use-sub` is `useSelector`), except that it is a value you pass explicitly at boot, and exactly one is installed per runtime.

## Which substrate

Reagent is the default. It has the full example set and is the notation used throughout this guide. Choose UIx when your team or host codebase already writes React function components; its examples are the counter and login (which mirror the Reagent versions) and an analytics dashboard ([`examples/substrates/uix/dashboard/`](../../../examples/substrates/uix/dashboard)). Choose slim when you have measured that the bundle is too large.

## 1. The one line that changes

In the boot shape from [Boot and mount an app](boot-and-mount-an-app.md), the substrate is chosen by the first line of `run`:

```clojure
(defn run []
  (rf/init! reagent-adapter/adapter)   ;; the substrate decision
  (mount!))
```

To switch substrates, change that argument. Each adapter namespace exports a value named `adapter`; require the namespace and pass that value to `init!`:

```clojure
;; Reagent — the canonical pick
(require '[re-frame.adapter.reagent :as reagent-adapter])
(rf/init! reagent-adapter/adapter)

;; UIx
(require '[re-frame.adapter.uix :as uix-adapter])
(rf/init! uix-adapter/adapter)
```

The published reagent-slim artefact puts its adapter at the same `re-frame.adapter.reagent` namespace, so its boot line is identical to stock Reagent's:

```clojure
;; reagent-slim: the same require and init! line as stock Reagent
(require '[re-frame.adapter.reagent :as reagent-adapter])
(rf/init! reagent-adapter/adapter)
```

Moving from stock Reagent to slim therefore changes your `deps.edn` coordinate and your `reagent.*` view requires ([step 6](#6-switch-to-reagent-slim)), not your boot call.

!!! note "Using the repository instead of the published jar?"

    In this repository the slim adapter is at `re-frame.adapter.reagent-slim`, because both Reagent adapters share one classpath there; publishing renames it to `re-frame.adapter.reagent`. A `:git/sha` dependency therefore requires `re-frame.adapter.reagent-slim`. The rest of this page assumes the published name.

There is no adapter registry and no automatic install; the substrate is always named in your boot code:

- `(rf/init!)` with no argument is an arity error.
- A keyword, `nil`, or anything other than an adapter map raises `:rf.error/no-adapter-specified`.
- Calling `init!` again with the adapter already installed does nothing, which keeps hot reload safe. Calling it with a different adapter raises `:rf.error/adapter-already-installed`; to switch, call `(rf/destroy-adapter!)` first.

??? note "What an adapter does"

    The [registrar](../glossary.md#registrar), the [event pipeline](../glossary.md#event-pipeline), and your handlers, subscriptions, and effects never touch React, which is why none of them care about the substrate. The [adapter](../glossary.md#adapter) is a small map of functions at the one point where re-frame2 meets a rendering library: it provides the reactive container app-db lives in, re-renders components when a subscription's value changes, and mounts the tree. Its contract has ten entries: the required `make-state-container`, `read-container`, `replace-container!`, `make-derived-value`, `render`, and `render-to-string`; the optional `subscribe-container`, `register-context-provider`, and `flush-render!`; and the `dispose-adapter!` lifecycle hook. You never call these yourself.

### One adapter per build (the coordinate table)

Each adapter is its own artefact beside the core, so an app bundles only the one it depends on, and `init!` installs exactly one:

| Substrate | Coordinate | View library |
|---|---|---|
| Reagent | `day8/re-frame2-reagent` | `reagent` (hiccup) |
| UIx | `day8/re-frame2-uix` | `com.pitch/uix.core` (UIx 2 publishes as Maven 1.x); `com.pitch/uix.dom` only if you drive a React root yourself |
| reagent-slim | `day8/reagent-slim` | `reagent2` (ships inside it) |

A UIx app needs three coordinates:

```clojure
;; deps.edn for a UIx app
{:deps {day8/re-frame2     {:mvn/version "<latest>"}   ; core
        day8/re-frame2-uix {:mvn/version "<latest>"}   ; the UIx adapter
        com.pitch/uix.core {:mvn/version "1.4.4"}}}    ; defui / $ / hooks
```

`<latest>` is the released re-frame2 version; every re-frame2 artefact ships at the same version. The adapter mounts through its own `client-root` / `render!` ([step 4](#4-mount-it-scope-a-frame-into-the-subtree)), so the app doesn't need `com.pitch/uix.dom`. Add it, at the same version as `uix.core`, only if something drives a React root by hand, as the component-test recipe in [Testing views](../testing/views.md#4-uix-hook-components-mount-it-for-real) does.

!!! note "Coordinates are not published yet"

    re-frame2 is pre-alpha; these coordinates publish with the first public release. In the repository the adapters build from [`implementation/adapters/`](../../../implementation/adapters).

## 2. Write a UIx view

Your events, subs, and effects port without edits; only the views change. Here is the counter in UIx:

```clojure
;; cf. examples/substrates/uix/counter/core.cljs
(ns my-app.views
  (:require [uix.core :refer [$ defui]]
            [re-frame.adapter.uix :as uix-adapter]))

(defui counter []
  (let [value              (uix-adapter/use-sub [:value])
        {:keys [dispatch]} (uix-adapter/use-frame)]
    ($ :div
       ($ :button {:on-click #(dispatch [:dec])} "-")
       ($ :span {:style #js {:margin "0 1em"}} value)
       ($ :button {:on-click #(dispatch [:inc])} "+"))))
```

Three rules for UIx components:

- **Read subs with `use-sub`.** It is a React hook built on `useSyncExternalStore`, so a subscription re-renders the component like any other hook. It uses the [frame](../glossary.md#frame) from the surrounding provider; `(use-sub [:q …] {:frame f})`, the same opts form `subscribe` takes, reads from an explicit frame instead.
- **Dispatch through `use-frame`.** `(use-frame)` is a hook that returns the [frame api](../glossary.md#capture-frame), the map `{:frame :dispatch :dispatch-sync :subscribe}` for the surrounding provider's frame, which is exactly what `(rf/capture-frame)` returns. Take `dispatch` from it during render and close over it; never call a bare `rf/dispatch` in a callback ([step 3](#3-why-callbacks-dispatch-off-the-frame-api) explains why).
- **There is no `reg-view` for UIx.** UIx components are plain `defui`. `rf/reg-view*` exists for the rare component that needs a registry id.

??? info "For JavaScript developers"

    `use-sub` is `useSelector`: a hook over `useSyncExternalStore`, the primitive react-redux uses. The explicit-frame form is spelled the same way as Reagent's: `(use-sub [:q] {:frame f})` beside `@(rf/subscribe [:q] {:frame f})`.

## 3. Why callbacks dispatch off the frame api

A click handler runs after render, with no frame in scope, so a bare `rf/dispatch` in it raises `:rf.error/no-frame-context` ([Frames](../frames.md#the-async-boundary-capture-the-frame)). `use-frame` is UIx's spelling of [`capture-frame`](../glossary.md#capture-frame): take the frame api during render and close over it.

The frame api holds every frame-bound operation:

```clojure
(use-frame)   ;; identical to (rf/capture-frame) for the ambient provider frame
;; =>
{:frame         :app
 :dispatch      (fn ([event]) ([event opts]))   ;; async-dispatch into the captured frame
 :dispatch-sync (fn ([event]) ([event opts]))   ;; synchronous variant — drains before returning
 :subscribe     (fn [query-v])}                 ;; frame-locked reaction — deref it (see below)
```

Usually you take only `:dispatch` from it, as the view above does. The other entries:

- `:dispatch-sync` processes the event before returning, for when it must settle before the next line runs (initialisation, a confirm-then-read flow).
- `:subscribe` returns the frame-bound reaction for a query; deref it for the current value. Use it to read state inside a callback without making the component re-render on it. For a one-off value with no reaction at all, use `rf/subscribe-once`; for reads that should re-render the component, use `use-sub`.

The captured frame can't be overridden: a `:frame` in the dispatch opts is ignored in favour of the frame the map was captured for.

Hooks run only during render. Outside any component (an async setup function, a tool, a test), call `(rf/capture-frame frame-id)` to get the same map for a named frame:

```clojure
;; A WebSocket message arrives long after render, outside any component.
;; Capture the frame by name at setup time and dispatch through it.
(let [{:keys [dispatch]} (rf/capture-frame :app)]
  (ws/on-message (fn [msg] (dispatch [:todo/synced msg]))))
```

??? info "From re-frame v1"

    v1's global `re-frame.core/dispatch` worked anywhere because there was one implicit app. re-frame2 can run many frames and never guesses one, so the frame api is how a callback keeps the right frame ([Frames](../frames.md#the-one-rule-frame-identity-is-carried-not-found)).

## 4. Mount it: scope a frame into the subtree

Mount the root inside a `frame-root`, which creates the frame on first mount and makes it the frame every `use-sub` and `use-frame` below it uses. This is the Reagent boot shape from [Boot and mount an app](boot-and-mount-an-app.md) with the UIx adapter:

```clojure
;; cf. examples/substrates/uix/counter/core.cljs
;; The entry namespace requires [re-frame.core :as rf], [uix.core :refer [$]]
;; and [re-frame.adapter.uix :as uix-adapter], plus the registration namespaces.
;; client-root does no DOM work, so this is safe at namespace load;
;; defonce keeps one React root across hot reloads.
(defonce app-root (uix-adapter/client-root))

(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (uix-adapter/render! app-root
      ($ uix-adapter/frame-root {:id             :app
                                 :initial-events [[:initialise 0]]}
         ($ counter))
      el)))

(defn run []
  (rf/init! uix-adapter/adapter)
  (mount!))
```

The `client-root` / `render!` / `unmount!` trio works as it does for Reagent: the first `render!` creates the React root (or hydrates one, with `{:hydrate? true}`), every later call updates that root, and `unmount!` releases it and is safe to repeat. The difference is the tree: UIx's `render!` takes a React element built with `$`, and passing it hiccup throws `:rf.error/hiccup-on-element-render-slot`. Children are ordinary `$` trailing arguments, `($ frame-root {:id :f} ($ a) ($ b))`.

??? info "For JavaScript developers"

    `frame-root` and `frame-provider` play the role of react-redux's `<Provider>`: the `use-sub` hooks below them find their frame through React context, as `useSelector` finds its store.

!!! note "A missing or wrong frame throws"

    A tree rendered with no `frame-root` or `frame-provider` above it raises `:rf.error/no-frame-context` at the first `use-sub`. re-frame2 never guesses a frame.

## 5. Ensure a view's own frame

`frame-root {:id …}` creates its frame on first mount, taking any `make-frame` option (`:images`, `:initial-events`, `:url-bound?`, …), and reuses it on remount; `frame-provider {:frame …}` scopes a frame that already exists. [Frames](../frames.md#frame-provider-and-frame-root) covers the split. In UIx:

```clojure
;; A panel that brings its own frame: created on first mount, reused on remount.
($ uix-adapter/frame-root
   {:id :todos/work
    :images [todo-image]                ;; your image for this frame
    :initial-events [[:todo/initialise]]}
   ($ todo-app))

;; A frame created elsewhere: scope only.
($ uix-adapter/frame-provider {:frame :todos/work}
   ($ todo-app))
```

All the React adapters use the same React context for frames, so frame components compose across substrates: a Reagent `frame-root` above a UIx subtree works.

A `frame-provider`'s `:frame` of `nil` raises `:rf.error/no-frame-context`; a string or number raises `:rf.error/bad-frame-provider-arg`.

## 6. Switch to reagent-slim

reagent-slim is plain Reagent with legacy surface removed, for apps where bundle size is a measured problem. It is not a different way of writing views. The trade:

- **Payoff:** an estimated 7–10 KB gzipped off a typical app (25–33% of the Reagent layer), and up to about 22–27 KB for apps using Reagent's HTML-export path. These figures are estimates, not build measurements. Runtime speed is at best marginally better, so choose slim for download size, not frame rate.
- **Constraints:** React 19 only. No `react-dom/server`; HTML export uses a small ClojureScript serializer in `reagent2.dom.server`. `create-class` accepts only seven keys (below).

Your app depends on exactly one of `day8/re-frame2-reagent` and `day8/reagent-slim`. The migration:

1. Change the deps coordinate to `day8/reagent-slim`. The `re-frame.adapter.reagent` require and the `init!` line stay as they are ([step 1](#1-the-one-line-that-changes)).
2. Set `react` and `react-dom` to 19.x in `package.json`.
3. Change `reagent.*` requires in your views to `reagent2.*` (for example `reagent2.core` for `reagent.core`).
4. If you call Reagent's DOM API directly rather than the adapter's `client-root` / `render!`, replace `reagent.dom/render` with `reagent2.dom.client/create-root` plus `render`, and `reagent.dom/unmount-component-at-node` with `reagent2.dom.client/unmount`.

A `r/dom-node` call becomes a `:ref` callback, since React 19 removed `findDOMNode`. The worked example is [`examples/substrates/reagent_slim/counter/`](../../../examples/substrates/reagent_slim/counter), whose events, subs, and views are identical to the stock Reagent counter's.

!!! note "Form-3 components under slim"

    Most views are Form-1 or Form-2 and don't notice slim. Views that own a piece of DOM lifecycle, such as a chart or map widget, use Reagent's Form-3 shape through `reagent2.core/create-class`, registered with `rf/reg-view*`. Slim accepts seven `create-class` keys: `:reagent-render`, `:component-did-mount`, `:component-did-update`, `:component-will-unmount`, `:get-snapshot-before-update`, `:component-did-catch` (React 19 error boundaries), and `:display-name`. Any other key throws `:rf.error/create-class-key-unsupported`, naming the key and listing the supported set. The deprecated `will-*` lifecycles, `:should-component-update`, and `:get-derived-state-from-props` are not supported; [FORM-3.md](../../../implementation/adapters/reagent-slim/FORM-3.md) has a migration recipe for each. To dispatch from a lifecycle callback, capture `(:dispatch (rf/capture-frame))` during render and call it from the callback.

### Server-rendering and the two HTML paths

Slim handles two kinds of "render to HTML" differently:

- **Static HTML export** (clipboard exports, report HTML, anything outside the React lifecycle) uses `render-to-static-markup` in `reagent2.dom.server`, a ClojureScript tree walk with no `react-dom/server` and no hydration attributes. This is where the HTML-export saving comes from: stock Reagent's `render-to-string` pulls in the roughly 50 KB `react-dom/server` module, while the serializer is about 3–4 KB.
- **SSR that the client hydrates** works under slim as it does under stock Reagent. The server render is `day8/re-frame2-ssr`'s ([Server-side rendering](../../ssr/concepts.md)), not the adapter's, and the client adopts it with `render!` and `{:hydrate? true}`. As for Reagent, pass `ssr/hydrate!` a `:render-tree-fn` so it can check the server's render-tree hash.

## What carries over, what doesn't

Only the view code changes between substrates:

| Surface | Reagent / slim | UIx |
|---|---|---|
| Events, subs, fx, app-db | identical | identical |
| Read a sub in a view | `@(subscribe [:q])` | `(uix-adapter/use-sub [:q])` |
| Read a sub from an explicit frame | `@(subscribe [:q] {:frame f})` | `(uix-adapter/use-sub [:q] {:frame f})` |
| Dispatch from a callback | `dispatch` injected by `reg-view` | `(:dispatch (use-frame))` |
| View form | `reg-view` + hiccup | `defui` + `$` |
| Registry-keyed view (when needed) | `reg-view` | `(rf/reg-view* id render-fn)` |
| Scope an existing frame | `[rf/frame-provider {:frame f} [app]]` | `($ uix-adapter/frame-provider {:frame f} ($ app))` |
| Ensure a named frame | `[rf/frame-root {:id f :images […]} [app]]` | `($ uix-adapter/frame-root {:id f :images […]} ($ app))` |
| Flush renders in a test | `(reagent-adapter/flush-views!)` (slim's adapter has its own) | `(uix-adapter/flush-views!)` |

Reagent's lazy-seq warning (*"Reactive deref not supported in lazy seq, it should be wrapped in doall"*) doesn't apply under UIx. Reagent records the derefs that happen during render, and a lazy seq can delay a deref until after render, so on Reagent you realise the seq inside the render function: `(doall (for …))`, `(mapv child @sub)`, or `(into [:<>] (map child) @sub)`. A UIx `use-sub` registers its dependency when the hook is called, whenever the surrounding seq is realised.

!!! note "Flushing renders in tests"

    After a test `dispatch-sync`s against a UIx-mounted tree, React hasn't committed the update yet. Wrap the dispatch as `(uix-adapter/flush-views! #(rf/dispatch-sync …))`, which uses React's `act()` to commit the re-render before returning. A real click's `dispatch` is queued instead; wait for it with a bounded `poll-until`. [Test a view](../testing/views.md#4-uix-hook-components-mount-it-for-real) has the full component test. `flush-views!` is for test code only.

To confirm the port, run the app and open [Xray](../glossary.md#xray): the event rows and [epochs](../glossary.md#epoch) match the Reagent run, because the instrumentation reads the core, which doesn't know which substrate renders.

## Advanced

Frame-component errors, `frame-root`'s commit-time creation, remounting and owning a frame's whole lifetime work as described in [Frames](../frames.md#frame-provider-and-frame-root); the UIx components behave identically.

!!! warning "Gotcha: a captured frame can outlive a destroyed frame"

    After you `destroy-frame!` a frame, something that captured it earlier with `capture-frame` (a slow HTTP reply, a `setTimeout`, a late WebSocket message) can still call its `dispatch` or `subscribe`. Nothing is corrupted: the call raises `:rf.error/frame-destroyed`, and a commit that reaches the frame after it is gone is dropped and reported as `:rf.error/write-after-destroy` (recovery `:ignored`). Both are reported in production too. Cancel in-flight work when you destroy the frame, or keep data that must outlast the widget in a longer-lived frame.
