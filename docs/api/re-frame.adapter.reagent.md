# re-frame.adapter.reagent

`re-frame.adapter.reagent` binds re-frame2's substrate-agnostic core to Reagent, the browser-default reactive substrate. Requiring it gives you four things:

- the `adapter` spec you pass to `(rf/init! …)` at boot;
- the `with-resource-lease` mount-lifecycle component;
- `flush-views!`, the test helper;
- `set-hiccup-emitter!`, the SSR render-to-string seam.

There is no per-substrate hook surface here. The substrate-agnostic ergonomic surface (`capture-frame`, `with-frame`, `with-new-frame`, `frame-provider`) and the `reg-view` registry live in [`re-frame.core`](re-frame.core.md). They compose across every substrate. The dependency is one-way: this adapter depends on `re-frame.core`; core never depends on it.

The adapter ships in two artefacts: `day8/re-frame2-reagent` (full) and `day8/reagent-slim` (slim). See the variant table under [`adapter`](#adapter). For substrate choice, see [Use UIx, Helix, or reagent-slim](../core/how-to/use-uix-helix-or-slim.md).

```clojure
(:require [re-frame.adapter.reagent :as reagent-adapter])
```

## The adapter spec

### `adapter`

- **Kind**: Var (map)
- **Signature**:
  ```clojure
  {:make-state-container …
   :render …
   :dispose-adapter! …}
  ```
- **Description**: The Reagent adapter map: the substrate spec you pass to `(rf/init! ...)` to install the browser-default Reagent substrate (stock `reagent.core` / `reagent.dom.client`).
  - There is no default-adapter registry and no keyword form. Require the adapter ns and pass its `adapter` Var explicitly at the call site.
  - When this adapter is installed, `current-adapter` (in [`re-frame.core`](re-frame.core.md)) returns `:rf.adapter/reagent`.
  - The Reagent `frame-provider` is the substrate-agnostic provider from [`re-frame.core`](re-frame.core.md); children stay trailing-positional hiccup (`[rf/frame-provider {:frame …} & children]`).
- **Example**:
  ```clojure
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter])

  (rf/init! reagent-adapter/adapter)   ;; install the substrate once, at boot
  ```

Reagent ships in two variants, full and slim. Both publish their adapter at the same canonical ns, `re-frame.adapter.reagent`, so the require and the `init!` line are identical. You select the variant by the Maven coordinate you depend on, not by the boot line:

| Variant | Maven coordinate | Adapter ns (require) | Includes | Use when |
|---|---|---|---|---|
| Full | `day8/re-frame2-reagent` | `re-frame.adapter.reagent` | stock Reagent (`reagent.core`, `reagent.dom.client`, `reagent.dom.server`) | client apps that may also render to a string on the JVM |
| Slim | `day8/reagent-slim` | `re-frame.adapter.reagent` (renamed from the in-tree `-slim` ns at publication) | the `reagent2` rewrite; static HTML export via a pure-CLJS `reagent2.dom.server`, no `react-dom/server` | browser-only bundles; ~7–10 KB gzipped smaller (up to ~22–27 KB where the HTML-export path was in play) |

A build depends on exactly one variant, so the adapter ns is single-source per app. You select slim vs full through your `deps.edn` coordinate.

In-repo `:git/sha` consumers require `re-frame.adapter.reagent-slim` directly, because the monorepo carries both adapters on one classpath. The publication step renames it to `re-frame.adapter.reagent` before packaging the jar.

The slim variant is bundle-isolated: a dedicated isolation gate verifies that stock Reagent / `react-dom/server` don't leak into builds that select it.

The migration (a four-line swap) is in [Use UIx, Helix, or reagent-slim](../core/how-to/use-uix-helix-or-slim.md).

## Components

### `with-resource-lease`

- **Kind**: component (Reagent Form-3 class)
- **Signature**:
  ```clojure
  [with-resource-lease descriptor body-thunk]
  [with-resource-lease descriptor opts body-thunk]
  ```
- **Description**: A Reagent component that holds a resource liveness lease for its mounted lifetime. It is the Reagent counterpart of the UIx / Helix `use-resource-lease` hook.

  Arguments:
  - `descriptor` — the resource-instance identity `{:resource … :scope … :params …}`.
  - `opts` — an optional map between the descriptor and the body thunk. `:cause` is recorded on the ensure for observability (defaults to `[:lease :mount]`). `:frame` pins the lease to an explicit frame id, bypassing ambient resolution.
  - `body-thunk` — a 0-arg fn returning the hiccup rendered while the lease is held.

  Behaviour:
  - On mount, dispatches `:rf.resource/ensure` with a per-instance `[:lease token]` owner; on unmount, dispatches `:rf.resource/release-owner` for that owner into the same frame.
  - Frame resolution tries, in order: the explicit `:frame` opt, the surrounding `frame-provider` context, then the dynamic frame binding. If none is in scope, it raises `:rf.error/no-frame-context`.
  - The lease token is minted once per mounted instance, so a hot-reload re-mount settles to exactly one held lease.
  - Under `render-to-string` (SSR) lifecycle methods do not run, so the acquire/release is a no-op.
- **Example**:
  ```clojure
  [reagent-adapter/with-resource-lease
   {:resource :my/feed :scope :rf.scope/global :params {:page 0}}
   (fn [] [feed-view])]

  ;; With opts — record a cause, pin the frame:
  [reagent-adapter/with-resource-lease
   {:resource :my/feed :scope :rf.scope/global :params {:page 0}}
   {:cause :dashboard-widget :frame :session}
   (fn [] [feed-view])]
  ```

## Test helpers

### `flush-views!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-views!)
  (flush-views! f)
  ```
- **Description**: Wraps React's `act()` for tests. Flushes pending Reagent renders synchronously and returns nil.
  - 0-arity: drains the queued renders and effects.
  - 1-arity: runs the thunk `f`, then the synchronous render drain, inside `act()`.
  - When `act()` is unreachable in the current React build, this degrades to a plain synchronous flush. `f` still runs and the render queue still drains, just without the `act()` wrapper.
  - Surfaced identically across all substrates: same name, same adapter-ns location, same nil-return.
- **Example**:
  ```clojure
  ;; Test-only: flush pending renders synchronously, returns nil.
  (reagent-adapter/flush-views!)               ;; 0-arity: drain queued renders + effects
  (reagent-adapter/flush-views! (fn [] nil))   ;; 1-arity: run the thunk inside act()
  ```

## Server-side rendering

### `set-hiccup-emitter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-hiccup-emitter! f)
  ```
- **Description**: Install a render-tree → HTML fn: the hiccup → HTML emitter used by render-to-string.
  - Last call wins; pass `nil` to reset.
  - Normally you don't call this directly. Requiring [`re-frame.ssr`](re-frame.ssr.md) resolves the late-bind hook and wires the emitter for you.
  - It is the Reagent-side late-bind seam for SSR, matching the parallel seam on the UIx and Helix adapters.
- **Example**:
  ```clojure
  ;; SSR: install a render-tree → HTML emitter (normally wired for you by
  ;; requiring re-frame.ssr). Pass nil to reset.
  (reagent-adapter/set-hiccup-emitter! (fn [tree _opts] (str tree)))
  (reagent-adapter/set-hiccup-emitter! nil)
  ```

## See also

- [`re-frame.core`](re-frame.core.md) — the substrate-agnostic surface (`capture-frame`, `with-frame`, `with-new-frame`, `frame-provider`, `reg-view`) and the lifecycle surface (`init!`, `install-adapter!`, `destroy-adapter!`, `current-adapter`).
- [`re-frame.adapter.uix`](re-frame.adapter.uix.md) / [`re-frame.adapter.helix`](re-frame.adapter.helix.md) — the hooks-first React substrates and their parallel adapter surfaces.
- [`re-frame.ssr`](re-frame.ssr.md) — server-side rendering; wires `set-hiccup-emitter!` for you.
- [`re-frame.resources`](re-frame.resources.md) — the resource runtime `with-resource-lease` leases into (`:rf.resource/ensure` / `:rf.resource/release-owner`).
- [Use UIx, Helix, or reagent-slim](../core/how-to/use-uix-helix-or-slim.md) — the substrate-choice how-to, including the slim swap.
- [Views](../core/views.md) — why the substrate only shows up in the view body.
- [Adapter](../core/glossary.md#adapter) and [substrate](../core/glossary.md#substrate) — the seam, and the thing it binds to, defined.
