# entry-namespace

The canonical shape of `your-app/core.cljs` — the entry namespace shadow-cljs's `:init-fn` points at. This file is where re-frame2 wires up to the substrate (Reagent) and to the DOM.

## Contents

- The skeleton
- Order of operations
- Why `rf/init!` exists (and why it's explicit)
- The Reagent root pattern (`defonce` + `rdc/create-root`)
- Where everything else goes
- Differences from re-frame v1

---

## The skeleton

```clojure
(ns your-app.core
  (:require [reagent.dom.client       :as rdc]
            [re-frame.core            :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

;; -- mount point ------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

;; -- entry ------------------------------------------------------------------

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (rdc/render react-root [root-view]))
```

That's the whole entry namespace. Events / subs / views go above the mount point, in this same file for a tiny app or in their own namespaces (`your-app.events`, `your-app.subs`, `your-app.views`) and `:require`d here for any non-trivial app.

`root-view` is the top-level registered view. See `first-counter.md` for what it looks like.

## Order of operations

`run` must do these three things, **in this order**, every time:

1. **`(rf/init! reagent-adapter/adapter)`** — install the substrate adapter into the default frame.
2. **(optional) `(rf/dispatch-sync [:your-app/initialise])`** — seed app-db synchronously before the first render. Most apps want this; some let the views render against an empty app-db and seed on first user interaction.
3. **`(rdc/render react-root [root-view])`** — mount the React tree.

If you flip 1 and 3, you'll get either a crash (the views call `subscribe` against an uninitialised registry) or — more confusingly — a silent failure where the first render shows nothing and clicking does nothing.

If you flip 2 and 3, the first render sees an empty app-db; usually fine, sometimes triggers a flash of unstyled state.

## Why `rf/init!` exists (and why it's explicit)

re-frame2 splits **the registry** (a process-global handler / sub / fx map) from **the substrate** (Reagent / UIx / Helix / plain atom). The substrate is supplied at boot via an *adapter map*; `rf/init!` is the moment that adapter map becomes the default frame's substrate.

Three consequences:

- **Adapters are values, not magic.** `re-frame.adapter.reagent/adapter` is a regular CLJS var holding a map. `rf/init!` takes that value directly — no global registration, no name-based lookup. Swap it for `re-frame.adapter.uix/adapter` or `re-frame.adapter.helix/adapter` and you have a UIx / Helix app.
- **`rf/init!` is idempotent in dev but not redundant.** Hot-reload of `core.cljs` will re-invoke `run`; calling `rf/init!` again is safe and resets the default frame's substrate config. Don't try to "guard" it with `defonce` — let it run.
- **No implicit boot.** Unlike re-frame v1 (where `re-frame.core` had no boot step), re-frame2 requires the explicit `init!`. The reason: multi-substrate support and the per-frame substrate-config model (Spec 006) need to know *which* adapter you want before any subscription resolves. There is no default.

The adapter map carries the substrate's `state-container`, `read-container`, `replace-container!`, `subscribe`, `render`, and hot-reload hooks. You don't construct it; you require the namespace and pass its `adapter` var.

## The Reagent root pattern (`defonce` + `rdc/create-root`)

```clojure
(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))
```

Two contractual bits:

- **`defonce`** — under shadow-cljs hot-reload, `core.cljs` reloads on every save. Without `defonce`, every reload calls `create-root` again on the same DOM node, and React 18 complains loudly (or, worse, silently mounts two roots that fight each other). `defonce` ensures the root is created exactly once per page load.
- **`(js/document.getElementById "app")`** — must match the `id` in `index.html`. Mismatch here is the most common cause of a blank page with no console error.

In `run`, `rdc/render` is called against `react-root` (not against the DOM node directly). Both `create-root` and `render` come from `reagent.dom.client` — that's the React 18 entry surface for Reagent 2.x.

## Where everything else goes

In a tiny app, all of this fits in `your-app/core.cljs` above the mount point:

- **Events** — `(rf/reg-event-db ...)`, `(rf/reg-event-fx ...)`
- **Subscriptions** — `(rf/reg-sub ...)`
- **Effects** — `(rf/reg-fx ...)` (per-app fx)
- **Views** — `(reg-view <name> [...] <body>)` (via the `reg-view` macro from `re-frame.views-macros`)
- **`(rf/dispatch-sync [:your-app/initialise])`** in `run` — the initial seed event

For anything beyond the first counter, split:

```
src/your_app/
├── core.cljs        ; the entry ns (this file)
├── events.cljs      ; reg-event-db / reg-event-fx
├── subs.cljs        ; reg-sub
└── views.cljs       ; reg-view-defined views
```

then `(:require [your-app.events] [your-app.subs] [your-app.views :as views])` in `core.cljs` so the registrations happen at load time. Use `[views/root-view]` in `rdc/render`. The folder shape is a convention; re-frame2 has no opinion about it.

## Differences from re-frame v1

If the author is coming from re-frame v1 (re-frame's first version), three things changed at the entry-namespace layer:

| v1 | v2 |
|---|---|
| `(:require [reagent.core :as r])` then `(r/render ...)` | `(:require [reagent.dom.client :as rdc])` then `(rdc/render root [view])` — React 18 surface |
| No explicit boot — `re-frame.core` was self-installing against Reagent | `(rf/init! reagent-adapter/adapter)` is mandatory; adapter is a value the app supplies |
| `defn` views — re-frame v1 had no view registration | `reg-view` macro registers views in a per-app registry; auto-injects `dispatch` / `subscribe` |
| Implicit single global `app-db` | One default frame; multi-frame apps are first-class via `frame-provider` |

The full v1→v2 migration story lives in `MIGRATION.md` (linked from `SKILL-REDIRECT.md` at the repo root). This skill is greenfield-only; if the author has a v1 codebase, point them at migration instead.
