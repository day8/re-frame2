# re-frame.fresco.substrate

Use this namespace to run a Fresco app on Fresco's own substrate adapter. It
provides the `adapter` you pass to `rf/init!` at boot, before anything mounts; with
it, a Fresco app depends on core and Fresco alone, instead of adding a Reagent or
UIx adapter just for its reactive container.

It ships in `day8/re-frame2-fresco` as an optional namespace: nothing else in
Fresco requires it, so a build that installs Reagent or UIx instead carries none of
it, and `re-frame.fresco` does not re-export it.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.fresco :as h]
          [re-frame.fresco.substrate :as substrate])
```

```clojure
(rf/init! substrate/adapter)

(defonce app-root (h/client-root))

(h/render! app-root [h/frame-root {:id :app/main} [app]]
           (js/document.getElementById "app"))
```

Fresco renders through `react-dom/client` and interprets its own hiccup, so it
needs no renderer from an adapter. What the adapter supplies is the reactive half:
the container `app-db` lives in, and derived values that notify when it changes.

Reagent, reagent-slim and UIx adapters also work under a Fresco tree: every React
adapter writes the same frame context, so a Fresco subtree and a UIx subtree
resolve the same frame. So use this adapter when Fresco is the app's only view
layer. An app that already installs a Reagent or UIx adapter for its other views
needs no second one: its Fresco views run on that adapter.
[Installation](../core/fresco/00-installation.md#fresco-needs-a-substrate-adapter)
teaches the boot line, and [Use UIx or reagent-slim](../core/how-to/use-uix-or-slim.md)
lists the other adapters and their coordinates.

## Adapter spec

### `adapter`

- **Kind**: var (map)
- **Signature**:
  ```clojure
  {:kind                      :rf.adapter/fresco
   :make-state-container      …
   :read-container            …
   :replace-container!        …
   :subscribe-container       …
   :make-derived-value        …
   :render                    …
   :render-to-string          …
   :register-context-provider …
   :flush-render!             …
   :dispose-adapter!          …}
  ```
- **Description**: The adapter map you pass to `rf/init!` to install Fresco's own
  adapter as the substrate; `(:kind (rf/current-adapter))` reads
  `:rf.adapter/fresco`. Install it before the first frame exists.
    - Installation is explicit and there is no default adapter, so an app that
      installs Reagent or UIx instead never loads this namespace.
    - Asking for a state container before `init!` — as mounting a `frame-root` does
      — throws `:rf.error/no-adapter-installed`.
    - Its derived values notify watchers from the moment they are created, which a
      live Fresco view needs. The headless `re-frame.substrate.plain-atom` adapter's
      derived values register no watch, so a view under it paints once and never
      updates: that adapter suits an SSR render, not a live view.
    - It adds no dependency: Fresco already requires `react`, and the rest of the
      adapter is in core.
- **Example**:
  ```clojure
  (rf/init! substrate/adapter)   ;; install the substrate once, at boot
  ```

## See also

- [`re-frame.adapter.reagent`](re-frame.adapter.reagent.md) and
  [`re-frame.adapter.uix`](re-frame.adapter.uix.md) — the other adapters, on the
  same contract.
- [`re-frame.fresco`](re-frame.fresco.md) — `client-root`, `render!` and the frame
  heads.
