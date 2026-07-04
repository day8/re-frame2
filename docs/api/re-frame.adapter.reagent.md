# re-frame.adapter.reagent

`re-frame.adapter.reagent` is the seam between re-frame2's substrate-agnostic core and Reagent, the browser-default reactive substrate. Requiring it gives you exactly three things: the `adapter` spec you pass to `(rf/init! ...)` at boot, plus two operational helpers — `flush-views!` for tests and `set-hiccup-emitter!` for the SSR render-to-string seam. There is deliberately no per-substrate hook surface here — Reagent's idiom is "views are plain functions returning hiccup", so the substrate-agnostic ergonomic surface (`capture-frame`, `with-frame`, `with-new-frame`, `frame-provider`) and the `reg-view` registry all live in [`re-frame.core`](re-frame.core.md) and compose across every substrate. The dependency direction is one-way: this adapter depends on `re-frame.core`; core never depends on it.

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
- **Description**: The Reagent adapter map — the substrate spec passed to `(rf/init! ...)` to install the browser-default Reagent substrate (stock `reagent.core` / `reagent.dom.client`). There is no default-adapter registry and no keyword form: require the adapter ns and pass its `adapter` Var explicitly at the call site. When this adapter is installed, `current-adapter` (in [`re-frame.core`](re-frame.core.md)) returns `:rf.adapter/reagent`. The Reagent `frame-provider` is the substrate-agnostic provider from [`re-frame.core`](re-frame.core.md), and keeps its children as trailing-positional hiccup (`[provider frame-kw & children]`) — Reagent's idiomatic shape.
- **Example**:
  ```clojure
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter])

  (rf/init! reagent-adapter/adapter)   ;; install the substrate once, at boot
  ```

Reagent ships in two variants. The full adapter lives here; the slim adapter is a sibling artefact that drops React's server-rendering tax for browser-only bundles. Both publish their adapter at the **same** canonical ns — `re-frame.adapter.reagent` — so the require and `init!` line are identical; what differs is the Maven coordinate you depend on:

| Variant | Maven coordinate | Adapter ns (require) | Includes | Use when |
|---|---|---|---|---|
| Full | `day8/re-frame2-reagent` | `re-frame.adapter.reagent` | stock Reagent (`reagent.core`, `reagent.dom`, `reagent.dom.server`) | client apps that may also render to a string on the JVM |
| Slim | `day8/reagent-slim` | `re-frame.adapter.reagent` (renamed from the in-tree `-slim` ns at publication) | Reagent without `reagent.dom.server` | browser-only bundles; drops ~30 KB by excluding `react-dom/server` |

The two artefacts sit side by side; a build depends on exactly one of them, so the adapter ns is single-source per app and you select slim vs full through your `deps.edn` coordinate, not through the boot line. (In-repo `:git/sha` consumers require `re-frame.adapter.reagent-slim` directly, because the monorepo carries both adapters on one classpath — the publication step renames it to `re-frame.adapter.reagent` before packaging the jar.) The slim variant is bundle-isolated — a dedicated isolation gate verifies that stock Reagent / `react-dom/server` don't leak into builds that select it. The full migration (a four-line swap) is in [Use UIx, Helix, or reagent-slim](../core/how-to/use-uix-helix-or-slim.md).

## Test helpers

### `flush-views!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-views!)
  (flush-views! f)
  ```
- **Description**: Wraps React's `act()` for tests. Flushes pending Reagent renders synchronously: the 0-arity form drains the queued renders and effects; the 1-arity form runs the thunk `f` and then the synchronous render drain inside `act()`. Returns nil, and is a no-op when `act()` is unreachable in the current React build. Surfaced identically across all substrates (same name, same adapter-ns location, same nil-return), so a test suite ports across substrates touching only the `init!` Var.
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
- **Description**: Install a render-tree → HTML fn — the hiccup → HTML emitter used by render-to-string. Last call wins; pass `nil` to reset. You normally don't call this directly: requiring [`re-frame.ssr`](re-frame.ssr.md) resolves the late-bind hook and wires the emitter for you. It is the Reagent-side late-bind seam for SSR, matching the parallel seam on the UIx and Helix adapters.
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
- [Use UIx, Helix, or reagent-slim](../core/how-to/use-uix-helix-or-slim.md) — the substrate-choice how-to, including the slim swap.
- [Views](../core/concepts/views.md) — why the substrate only shows up in the view body.
- [Adapter](../core/glossary.md#adapter) and [substrate](../core/glossary.md#substrate) — the seam, and the thing it binds to, defined.
