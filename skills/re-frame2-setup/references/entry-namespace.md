# Entry namespace

The canonical shape of `your-app/core.cljs` — the entry namespace shadow-cljs's `:init-fn` points at. This file is where re-frame2 wires up to the substrate (Reagent) and to the DOM.

## Contents

- The skeleton
- Order of operations
- Why `rf/init!` exists (and why it's explicit)
- The Reagent root pattern (`defonce` + `rdc/create-root`)
- Where everything else goes
- UIx / Helix greenfield
- Differences from re-frame v1

---

## The skeleton

```clojure
(ns your-app.core
  (:require [reagent.dom.client       :as rdc]
            [re-frame.core            :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- mount point ------------------------------------------------------------

;; Namespace load does no DOM work — the root is created lazily inside init.
(defonce react-root (atom nil))

;; -- entry ------------------------------------------------------------------

;; shadow-cljs's :browser target re-runs :init-fn (init) after EACH hot
;; reload, so init IS the re-render path — no separate ^:dev/after-load hook.
(defn ^:export init []
  (rf/init! reagent-adapter/adapter)             ;; install the Reagent adapter (no frame yet)
  (rf/reg-frame :rf/default {})                   ;; register the app frame (surgical hot-reload update)
  (rf/with-frame :rf/default                      ;; under a live frame scope:
    (rf/dispatch-sync [:your-app/initialise]))    ;;   seed app-db synchronously — the reset boundary
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (when-not @react-root
      (reset! react-root (rdc/create-root el)))
    ;; frame-provider's {:frame …} SCOPE shape scopes the already-registered
    ;; :rf/default frame into the tree (it creates / destroys nothing).
    (rdc/render @react-root
                [rf/frame-provider {:frame :rf/default}
                 [counter-app]])))
```

That's the whole entry namespace. Events / subs / views go above the mount point — in this file for a tiny app, or in their own namespaces (`your-app.events` / `.subs` / `.views`) `:require`d here for anything larger.

`counter-app` is the top-level registered view. `:rf/default` is the generator template's frame id — under EP-0002 (the carried-frame invariant) it is **not** auto-registered; you register it with `reg-frame` and scope it at the root with `frame-provider`'s `{:frame …}` SCOPE-only shape (see §Order of operations). Any id works — keep it consistent across `reg-frame`, `with-frame`, and the provider.

The entry symbol is `init`, matching the generator template's `:init-fn {{namespace}}.core/init`. (The repo's `examples/core/counter/core.cljs` names its entry fn `run` and uses a different boot shape — the lazy-root + `frame-provider {:id … :initial-events …}` ENSURE form; see [`boot-and-mount-an-app.md`](../../../docs/core/how-to/boot-and-mount-an-app.md). Pick any entry-symbol name and keep `:init-fn` pointing at it; this skill uses `init`.)

## Order of operations

`init` must do these things, **in this order**, every time:

1. **`(rf/init! reagent-adapter/adapter)`** — install the substrate adapter. (`init!` installs the adapter and runtime capabilities only; it creates **no** frame.)
2. **`(rf/reg-frame :rf/default {})`** — register the app frame. On a hot reload this is a **surgical update** that preserves the existing app-db / sub-cache / queue and only replaces metadata/config.
3. **`(rf/with-frame :rf/default (rf/dispatch-sync [:your-app/initialise]))`** — seed the app-db under a live frame scope, synchronously, before render. This explicit `dispatch-sync` is the **reset boundary**: it re-seeds the demo/initial state on **every** hot reload (the surgical update in step 2 does not rerun any `:initial-events`). Some apps instead seed lazily on first interaction; either way the frame must be registered before render. **A frame-local schema attach belongs in this same scope, before the seed** — `reg-app-schema` targets a frame and cannot register at ns-load (`:rf.error/no-frame-context`); see [`first-counter.md` §Schema](first-counter.md) for the full shape.
4. **`(rdc/render react-root [rf/frame-provider {:frame :rf/default} [counter-app]])`** — mount the React tree **wrapped in `frame-provider`'s `{:frame …}` SCOPE-only shape** so every bare `dispatch` / `subscribe` under it resolves against `:rf/default`. (The frame already exists from step 2's `reg-frame`, so you scope it with `{:frame …}` rather than ensure it; `frame-provider`'s other shape, `{:id …}`, *ensures* a named frame — for a view-owned frame, not the app root.)

If you render *without* the provider (or before `reg-frame`), every `subscribe` / `dispatch` in the tree raises `:rf.error/no-frame-context` — the runtime refuses to guess a frame. If you render before `rf/init!`, the views call `subscribe` against an uninstalled adapter and you get `:rf.error/no-adapter-installed`.

## Why `rf/init!` exists (and why it's explicit)

re-frame2 splits **the registry** (the process-wide handler / sub / fx map your `reg-*` forms write to) from **the substrate** (Reagent / UIx / Helix / plain atom), supplied at boot via an *adapter map*. `rf/init!` is when that adapter map + the runtime capabilities are **installed**, before any frame exists. (A single-frame app never spells an *image* — that layering is the `re-frame2` skill's territory; here the ordinary `reg-*` path writes the default registrations and your one frame resolves them.)

Three consequences:

- **Adapters are values, not magic.** `re-frame.adapter.reagent/adapter` is a regular CLJS var holding a map. `rf/init!` takes that value directly — no global registration, no name-based lookup. Swap it for `re-frame.adapter.uix/adapter` or `re-frame.adapter.helix/adapter` and you have a UIx / Helix app.
- **`rf/init!` is idempotent — safe to call more than once.** `init` re-runs on **every** hot reload, so `rf/init!` runs again each save; that is safe — a second `init!` re-installs the substrate config only when none is seated, and creates no frame. Leave it unguarded in `init` (don't wrap it in a `defonce`); the idempotence is what makes the per-reload re-run harmless. (`reg-frame` on the same id is likewise a surgical, hot-reload-safe update.)
- **No implicit boot.** No default adapter — multi-substrate support and the per-frame substrate-config model (Spec 006) mean the runtime must know *which* adapter you want before any subscription resolves, so you supply it at boot via the explicit `init!`.

**You don't construct the adapter map — you require the namespace and pass its exported `adapter` var.** App authors never assemble it by hand; the map implements the reactive-substrate adapter contract of [Spec 006](../../../spec/006-ReactiveSubstrate.md), and constructing or extending it is the **`re-frame2-implementor`** skill's territory, not greenfield's.

## The Reagent root pattern (`defonce` + `rdc/create-root`)

The skeleton holds the React root in a `defonce` atom, created lazily inside `init`. Two contractual bits:

- **`defonce`** — `core.cljs` reloads on every save; without `defonce`, each reload calls `create-root` again on the same DOM node and React 19 complains loudly (or silently mounts two fighting roots). `defonce` creates the root exactly once per page load.
- **`(js/document.getElementById "app")`** — must match the `id` in `index.html`. Mismatch here is the most common cause of a blank page with no console error.

`rdc/render` is called against `react-root`, not the DOM node. Both `create-root` and `render` come from `reagent.dom.client` — the React 19 client-Root surface for Reagent 2.x.

## Where everything else goes

In a tiny app, all of this fits in `your-app/core.cljs` above the mount point:

- **Events** — `(rf/reg-event ...)` (the one event-registration form)
- **Subscriptions** — `(rf/reg-sub ...)`; **Effects** — `(rf/reg-fx ...)` (per-app fx)
- **Views** — `(reg-view <name> [...] <body>)` (the `reg-view` macro from `re-frame.core`)
- **`(rf/dispatch-sync [:your-app/initialise])`** in `init` — the initial seed event

For anything beyond the first counter, split:

```
src/your_app/
├── core.cljs        ; the entry ns (this file)
├── events.cljs      ; reg-event
├── subs.cljs        ; reg-sub
└── views.cljs       ; reg-view-defined views
```

then `(:require [your-app.events] [your-app.subs] [your-app.views :as views])` in `core.cljs` so the registrations happen at load time, and use `[views/counter-app]` in `rdc/render`. The folder shape is a convention; re-frame2 has no opinion about it.

When you split this way, the `reg-view` forms move to `views.cljs`, so split `core.cljs` requires neither `[re-frame.views]` nor the `reg-view` macro (only the side-effecting `[your-app.views :as views]`). The generator template's `views.cljs` requires just `[re-frame.core :as rf]` and calls `rf/reg-view` **fully qualified** — that is the template's literal shape; this skill's `:refer [reg-view]` skeleton (above) is an equivalent alternative. The single-file counter in `first-counter.md` keeps the view requires in `core.cljs` because the views live there.

## UIx / Helix greenfield

This skill scaffolds against **Reagent** (the default reference substrate). For a UIx or Helix greenfield the wiring is the same shape with substitutions in three places — deps.edn, entry ns, and views; everything else is identical:

- **deps.edn** — swap `day8/re-frame2-reagent` for `day8/re-frame2-uix` (or `-helix`), drop the `reagent/reagent` pin, and add the substrate's Maven deps **at the exact versions the generator template pins** (the template is the source of truth — do not chase latest or invent a version, same discipline as the Reagent/React/shadow pins in [`deps-versions.md`](deps-versions.md)). Verified against the template's `_uix/deps.edn` / `_helix/deps.edn`:

  > **Pre-publish coordinate shape.** The `day8/re-frame2-uix` / `-helix` **framework** coords below show `:mvn/version "<VERSION>"` (the post-publish shape); pre-publish they take the `:git/sha` / `:local/root` form like the Reagent day-one set — see [`deps-versions.md` §Choosing the coordinate](deps-versions.md#choosing-the-coordinate-publication-state-decides-the-shape). The `com.pitch/uix.*` / `lilactown/helix` substrate deps are on Clojars and keep `:mvn/version`.

  ```clojure
  ;; UIx — replace the reagent line with (framework coord post-publish shape; pre-publish use :git/sha):
  day8/re-frame2-uix {:mvn/version "<VERSION>"}
  com.pitch/uix.core {:mvn/version "1.4.4"}
  com.pitch/uix.dom  {:mvn/version "1.4.4"}

  ;; Helix — replace the reagent line with (framework coord post-publish shape; pre-publish use :git/sha):
  day8/re-frame2-helix {:mvn/version "<VERSION>"}
  lilactown/helix      {:mvn/version "0.2.2"}
  ```

  > **Heads-up on the UIx version target.** `spec/006-ReactiveSubstrate.md` names **UIx 2.x** (hooks-based) as the design target, but the generator template pins **`com.pitch/uix` `1.4.4`** — the **known-good, tested** set. Use the template pin; treat UIx 2.x as an unverified manual override to test before relying on it. (Pins read off the template's `_uix/deps.edn`; re-check if either bumps.)
- **entry ns** — require `[re-frame.adapter.uix :as uix-adapter]` (or `re-frame.adapter.helix`), pass `uix-adapter/adapter` to `rf/init!`, register the app frame, and mount with the substrate's own root API — **wrapping the tree in the adapter's `frame-provider` `{:frame …}` SCOPE shape** (a `$`-element native component; scope-only since the frame already exists from `reg-frame`). For UIx that's `uix.dom/create-root` + `uix.dom/render-root`, view wrapped in `$` from `uix.core`:
  ```clojure
  (ns your-app.core
    (:require [uix.core             :refer [$]]
              [uix.dom              :as uix-dom]
              [re-frame.core        :as rf]
              [re-frame.adapter.uix :as uix-adapter]
              [your-app.views       :as views]))

  (defonce react-root (uix-dom/create-root (js/document.getElementById "app")))

  (defn ^:export init []
    (rf/init! uix-adapter/adapter)
    (rf/reg-frame :rf/default {})
    (rf/with-frame :rf/default
      (rf/dispatch-sync [:counter/initialise]))   ;; reset boundary — re-seeds each hot reload
    (uix-dom/render-root
      ($ uix-adapter/frame-provider {:frame :rf/default}
        ($ views/counter-app))
      react-root))
  ```
  (Helix mounts with `(.render react-root ($ helix-adapter/frame-provider {:frame :rf/default} ($ views/counter-app)))` against a `react-dom/client` root (`$` from `helix.core`), same `reg-frame` + `with-frame` / `dispatch-sync` boot — see the template's `_helix/core.cljs`. Every adapter's `frame-provider` scopes the already-registered frame for its subtree; rendering without it raises `:rf.error/no-frame-context` on the first subscribe.)
- **views** — the substitution `first-counter.md` does **not** cover. Reagent's `reg-view` auto-injects `dispatch`/`subscribe`; UIx and Helix have **no auto-injection** — components read subs through the adapter's `use-subscribe` hook and dispatch through `(:dispatch (rf/capture-frame))`, captured once per render (the closed-over `dispatch` still targets that frame from an async callback). UIx uses `defui` + `$`; Helix uses `defnc` + `helix.dom`. Copy the matching `views.cljs` verbatim (from the template's `_uix/views.cljs` / `_helix/views.cljs`):

  ```clojure
  ;; UIx — src/your_app/views.cljs
  (ns your-app.views
    (:require [uix.core             :refer [$ defui]]
              [re-frame.core        :as rf]
              [re-frame.adapter.uix :as uix-adapter]))

  (defui counter-buttons []
    (let [value    (uix-adapter/use-subscribe [:counter/value])
          dispatch (:dispatch (rf/capture-frame))]
      ($ :div
         ($ :button {:on-click #(dispatch [:counter/increment])} "+1")
         ($ :span {:style #js {:margin "0 1em"}} value))))

  (defui counter-app []
    ($ :div
       ($ :h1 "your-app")
       ($ counter-buttons)))
  ```

  ```clojure
  ;; Helix — src/your_app/views.cljs
  (ns your-app.views
    (:require [helix.core             :refer [$ defnc]]
              [helix.dom              :as d]
              [re-frame.core          :as rf]
              [re-frame.adapter.helix :as helix-adapter]))

  (defnc counter-buttons []
    (let [value    (helix-adapter/use-subscribe [:counter/value])
          dispatch (:dispatch (rf/capture-frame))]
      (d/div
        (d/button {:on-click #(dispatch [:counter/increment])} "+1")
        (d/span {:style {:margin "0 1em"}} value))))

  (defnc counter-app []
    (d/div
      (d/h1 "your-app")
      ($ counter-buttons)))
  ```
- everything else (events, subs, schemas, Xray wiring, `dispatch-sync` seed, `:init-fn ...core/init`) is identical across substrates. **Views are not** — do not reach for the Reagent `reg-view` first-counter leaf on a UIx/Helix app; use the substrate views above (or take the complete generator route below).

The fastest path for a non-Reagent greenfield is the **generator template**, which ships complete `_uix/` and `_helix/` variants. This is a **user-run** route — the **author** invokes `clojure -Tnew create … :substrate :uix` (or `:helix`) in their own shell; **this skill does not run `clojure -Tnew`** (its `allowed-tools` cover only the manual scaffold). Pre-publish caveat + the working `:local/root` dev route: [`SKILL.md` cardinal rule 5](../SKILL.md) and [the generator-template section](../README.md#relationship-to-the-generator-template).

## Differences from re-frame v1

For a v1 dev starting fresh, three things changed at the entry-namespace layer: the render surface is now `reagent.dom.client` (`rdc/render` against a `create-root` root — React 19); boot is **explicit** (`(rf/init! reagent-adapter/adapter)` is mandatory — no self-install); and there is **no implicit global `app-db`** — you register one app frame (`reg-frame`) and scope it at the root with `frame-provider {:frame …}` (the runtime never infers a default; multi-frame apps add more providers). Views are registered with the `reg-view` macro (v1 used plain `defn`).

The full v1→v2 story is the migration skill's territory (`migration/from-re-frame-v1/`, via `SKILL-REDIRECT.md`); this skill is greenfield-only.
