# Entry namespace

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
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- mount point ------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

;; -- entry ------------------------------------------------------------------

(defn ^:export init []
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame :rf/default {})
  ;; Seed under a live frame scope, synchronously, so the first render
  ;; sees the seeded app-db. This explicit dispatch-sync is the reset
  ;; boundary the generator ships: the reg-frame above is a surgical
  ;; (app-db-preserving) update on a hot reload, and this re-seeds the
  ;; demo state each time. (Seeding via :initial-events would only run on
  ;; the first registration.)
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:your-app/initialise]))
  (rdc/render react-root
    [rf/frame-provider {:frame :rf/default}
     [counter-app]]))
```

That's the whole entry namespace. Events / subs / views go above the mount point, in this same file for a tiny app or in their own namespaces (`your-app.events`, `your-app.subs`, `your-app.views`) and `:require`d here for any non-trivial app.

`counter-app` is the top-level registered view (the name the generator template and `first-counter.md` use). See `first-counter.md` for what it looks like. **Note the `frame-provider` wrap:** under EP-0002 (the carried-frame invariant), the runtime never infers a frame — you register one explicitly with `reg-frame` and scope it at the root with `frame-provider`'s `{:frame …}` SCOPE-only shape (it provides an already-created frame's id to descendants and creates / destroys nothing — the frame was already created by `reg-frame`). Inside that subtree every bare `dispatch` / `subscribe` resolves against `:rf/default`; a render *without* a provider would make every subscription raise `:rf.error/no-frame-context`. (`:rf/default` is the generator template's frame id — it is **not** auto-registered; you register and provide it explicitly, never inferred. Any other id works too; keep it consistent across `reg-frame`, `with-frame`, and the provider.)

The entry symbol is `init`, matching the generator template's `:init-fn {{namespace}}.core/init`. (The repo's worked example in `examples/reagent/counter/core.cljs` happens to call its entry fn `run` — same shape, different name. Pick one and keep `shadow-cljs.edn`'s `:init-fn` pointing at whatever you choose; this skill uses `init` so the manual route matches the template.)

## Order of operations

`init` must do these things, **in this order**, every time:

1. **`(rf/init! reagent-adapter/adapter)`** — install the substrate adapter. (`init!` installs the adapter and runtime capabilities only; it creates **no** frame.)
2. **`(rf/reg-frame :rf/default {})`** — register the app frame. On a hot reload this is a **surgical update** that preserves the existing app-db / sub-cache / queue and only replaces metadata/config.
3. **`(rf/with-frame :rf/default (rf/dispatch-sync [:your-app/initialise]))`** — seed the app-db under a live frame scope, synchronously, before render. This explicit `dispatch-sync` is the **reset boundary**: it re-seeds the demo/initial state on **every** hot reload (the surgical update in step 2 does not rerun any `:initial-events`). Some apps instead seed lazily on first interaction; either way the frame must be registered before render. **A frame-local schema attach belongs in this same scope** — `(register-schema!)` (calling `reg-app-schema`) runs here, before the seed, because app-db schemas target a frame and cannot register at ns-load (`:rf.error/no-frame-context`); see [`first-counter.md` §Schema](first-counter.md) for the full shape the generator ships.
4. **`(rdc/render react-root [rf/frame-provider {:frame :rf/default} [counter-app]])`** — mount the React tree **wrapped in `frame-provider`'s `{:frame …}` SCOPE-only shape** so every bare `dispatch` / `subscribe` under it resolves against `:rf/default`. (The frame already exists from step 2's `reg-frame`, so you scope it with `{:frame …}` rather than ensure it. `frame-provider`'s other shape, `{:id …}`, *ensures* a named frame — create-if-absent, reuse-no-reseed, no destroy-on-unmount — the right tool for a view-owned frame, e.g. a comparison page or Story canvas, not the app root.)

If you render *without* the provider (or before `reg-frame`), every `subscribe` / `dispatch` in the tree raises `:rf.error/no-frame-context` — the runtime refuses to guess a frame. If you render before `rf/init!`, the views call `subscribe` against an uninstalled adapter and you get `:rf.error/no-adapter-installed`.

## Why `rf/init!` exists (and why it's explicit)

re-frame2 splits **the registry** (the handler / sub / fx map) from **the substrate** (Reagent / UIx / Helix / plain atom). The registry is the process-wide registration source your `reg-*` forms write to; the substrate is supplied at boot via an *adapter map*. `rf/init!` is the moment that adapter map and the runtime capabilities are **installed**, before any frame exists (it does not create a frame). A frame, once registered, resolves registrations through its *image* — the selected registration set it runs (the public model is `image -> frame -> event stream`; see the `re-frame2` skill's `references/fundamentals/frames.md`) — but a single-frame app never spells an image: the ordinary `reg-*` path writes the default registration source and your one frame resolves the default image over it.

Three consequences:

- **Adapters are values, not magic.** `re-frame.adapter.reagent/adapter` is a regular CLJS var holding a map. `rf/init!` takes that value directly — no global registration, no name-based lookup. Swap it for `re-frame.adapter.uix/adapter` or `re-frame.adapter.helix/adapter` and you have a UIx / Helix app.
- **`rf/init!` is idempotent — safe to call more than once.** Under shadow-cljs's `:browser` target `init` re-runs on **every** hot reload (the module `:init-fn` is wired as both the startup entry and the default after-load hook — see [`shadow-cljs.md` §`:devtools` block](shadow-cljs.md)), so `rf/init!` is called again on each save. That is safe: a second `init!` re-installs the substrate config only when none is seated and creates no frame. So leave `rf/init!` unguarded in `init` (don't wrap it in a `defonce`); the idempotence is what makes the per-reload re-run harmless. (`reg-frame` on the same id is likewise a surgical, hot-reload-safe update.)
- **No implicit boot.** Unlike re-frame v1 (where `re-frame.core` had no boot step), re-frame2 requires the explicit `init!`. The reason: multi-substrate support and the per-frame substrate-config model (Spec 006) need to know *which* adapter you want before any subscription resolves. There is no default.

**You don't construct the adapter map — you require the namespace and pass its exported `adapter` var.** App authors never assemble it by hand. (For the record, the contract it implements is the reactive-substrate adapter of [Spec 006](../../../spec/006-ReactiveSubstrate.md): required fns `:make-state-container`, `:read-container`, `:replace-container!`, `:make-derived-value`, `:render`, `:render-to-string`; optional `:subscribe-container`, `:register-context-provider`, `:flush-render!`; lifecycle `:dispose-adapter!`; plus a `:kind` discriminator. The physical container `:make-state-container` holds is **frame-state** — the two-partition value `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`, not a bare app-db map — so app subs read the `:rf.db/app` projection and framework subs read `:rf.db/runtime`.) Constructing or extending that contract is the **`re-frame2-implementor`** skill's territory, not greenfield's.

## The Reagent root pattern (`defonce` + `rdc/create-root`)

```clojure
(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))
```

Two contractual bits:

- **`defonce`** — under shadow-cljs hot-reload, `core.cljs` reloads on every save. Without `defonce`, every reload calls `create-root` again on the same DOM node, and React 19 complains loudly (or, worse, silently mounts two roots that fight each other). `defonce` ensures the root is created exactly once per page load.
- **`(js/document.getElementById "app")`** — must match the `id` in `index.html`. Mismatch here is the most common cause of a blank page with no console error.

In `init`, `rdc/render` is called against `react-root` (not against the DOM node directly). Both `create-root` and `render` come from `reagent.dom.client` — that's the React 19 client-Root entry surface for Reagent 2.x.

## Where everything else goes

In a tiny app, all of this fits in `your-app/core.cljs` above the mount point:

- **Events** — `(rf/reg-event ...)` (the one event-registration form; a handler takes the coeffects map + event and returns `{:db <next-app-db>}`, adding `:fx [...]` when it also needs effects)
- **Subscriptions** — `(rf/reg-sub ...)`
- **Effects** — `(rf/reg-fx ...)` (per-app fx)
- **Views** — `(reg-view <name> [...] <body>)` (via the `reg-view` macro from `re-frame.core`)
- **`(rf/dispatch-sync [:your-app/initialise])`** in `init` — the initial seed event

For anything beyond the first counter, split:

```
src/your_app/
├── core.cljs        ; the entry ns (this file)
├── events.cljs      ; reg-event
├── subs.cljs        ; reg-sub
└── views.cljs       ; reg-view-defined views
```

then `(:require [your-app.events] [your-app.subs] [your-app.views :as views])` in `core.cljs` so the registrations happen at load time. Use `[views/counter-app]` in `rdc/render` (`counter-app` being the top-level view defined in `views.cljs`). The folder shape is a convention; re-frame2 has no opinion about it.

When you split this way, the `[re-frame.views]` require **and** the `(:require-macros [re-frame.core :refer [reg-view]])` move into `views.cljs` (they belong wherever the `reg-view` forms live, not in `core.cljs`). This matches the template, whose `core.cljs` requires neither — only the side-effecting `[your-app.views :as views]` — while `views.cljs` carries `[re-frame.views]` + the `reg-view` macro-require. The single-file counter in `first-counter.md` keeps all three in `core.cljs` because the views live there.

## UIx / Helix greenfield

This skill scaffolds against **Reagent** (the default reference substrate). For a UIx or Helix greenfield app the wiring is the same shape with two substitutions (plus a third "everything else stays identical" claim):

- **deps.edn** — swap `day8/re-frame2-reagent` for `day8/re-frame2-uix` (or `-helix`), drop the `reagent/reagent` pin, and add the substrate's Maven deps **at the exact versions the generator template pins** (the template is the source of truth — do not chase latest or invent a version, same discipline as the Reagent/React/shadow pins in [`deps-versions.md`](deps-versions.md)). Verified against the template's `_uix/deps.edn` / `_helix/deps.edn`:

  > **Pre-publish coordinate shape.** The `day8/re-frame2-uix` / `day8/re-frame2-helix` **framework** coords shown below use `:mvn/version "<VERSION>"` — the **post-publish** shape. re-frame2 is not on Clojars yet, so today the framework adapter coord takes the **`:git/sha` / `:local/root`** form (one shared `<SHA>`, the same way as the Reagent day-one set) — see [`deps-versions.md` §Choosing the coordinate](deps-versions.md#choosing-the-coordinate-publication-state-decides-the-shape). The `com.pitch/uix.*` / `lilactown/helix` substrate deps below are published on Clojars and keep `:mvn/version`.

  ```clojure
  ;; UIx — replace the reagent line with (framework coord post-publish shape; pre-publish use :git/sha):
  day8/re-frame2-uix {:mvn/version "<VERSION>"}
  com.pitch/uix.core {:mvn/version "1.4.4"}
  com.pitch/uix.dom  {:mvn/version "1.4.4"}

  ;; Helix — replace the reagent line with (framework coord post-publish shape; pre-publish use :git/sha):
  day8/re-frame2-helix {:mvn/version "<VERSION>"}
  lilactown/helix      {:mvn/version "0.2.2"}
  ```

  > **Heads-up on the UIx version target.** `spec/006-ReactiveSubstrate.md` §UIx version target names **UIx 2.x** (hooks-based) as the design target, but the shipped generator template pins **`com.pitch/uix.core` / `com.pitch/uix.dom` `1.4.4`** today. The template pin is the **known-good, tested** set — use it. If you have a reason to track UIx 2.x, treat it as an unverified manual override and test the adapter against it before relying on it. (Both pins read off `tools/template/resources/day8/re_frame2_template/_uix/deps.edn` and `spec/006-ReactiveSubstrate.md`; re-check both if either bumps.)
- **entry ns** — require `[re-frame.adapter.uix :as uix-adapter]` (or `re-frame.adapter.helix`), pass `uix-adapter/adapter` to `rf/init!`, register the app frame, and mount with the substrate's own root API instead of `reagent.dom.client` — **wrapping the tree in the adapter's `frame-provider` (its `{:frame …}` SCOPE-only shape)** (a `$`-element, since UIx/Helix providers are native components; scope-only, since the frame already exists from `reg-frame`). For UIx that's `uix.dom/create-root` + `uix.dom/render-root`, and the view is wrapped in the `$` element macro from `uix.core`:
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
  (Helix uses `(.render react-root ($ helix-adapter/frame-provider {:frame :rf/default} ($ views/counter-app)))` against a `react-dom/client` root, with `$` from `helix.core`, and the same `reg-frame :rf/default {}` + `with-frame` / `dispatch-sync` boot — see the template's `_helix/core.cljs`. Every adapter's `frame-provider` (its `{:frame …}` SCOPE-only shape) scopes the already-registered carried frame for its subtree; rendering without it raises `:rf.error/no-frame-context` on the first subscribe.)
- **views** — this is the substitution `first-counter.md` does **not** cover. The Reagent first-counter uses `reg-view` with auto-injected `dispatch`/`subscribe`; UIx and Helix have **no auto-injection** — components read subs through the adapter's `use-subscribe` hook and dispatch through `(:dispatch (rf/frame-handle))`, captured once per render (the handle closes over the render-time frame, so a closed-over `dispatch` still targets that frame from an async callback). UIx uses `defui` + `$`; Helix uses `defnc` + `helix.dom`. The events and subs are the same `reg-event` / `reg-sub` forms as the Reagent counter — only the view layer differs. Copy the matching `views.cljs` verbatim (verified against the template's `_uix/views.cljs` / `_helix/views.cljs`):

  ```clojure
  ;; UIx — src/your_app/views.cljs
  (ns your-app.views
    (:require [uix.core             :refer [$ defui]]
              [re-frame.core        :as rf]
              [re-frame.adapter.uix :as uix-adapter]))

  (defui counter-buttons []
    (let [value    (uix-adapter/use-subscribe [:counter/value])
          dispatch (:dispatch (rf/frame-handle))]
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
          dispatch (:dispatch (rf/frame-handle))]
      (d/div
        (d/button {:on-click #(dispatch [:counter/increment])} "+1")
        (d/span {:style {:margin "0 1em"}} value))))

  (defnc counter-app []
    (d/div
      (d/h1 "your-app")
      ($ counter-buttons)))
  ```
- everything else (events, subs, schemas, Xray wiring, `dispatch-sync` seed, `:init-fn ...core/init`) is identical across substrates. **Views are not** — do not reach for the Reagent `reg-view` first-counter leaf on a UIx/Helix app; use the substrate views above (or take the complete generator route below).

The fastest path for a non-Reagent greenfield is the **generator template**, which ships complete `_uix/` and `_helix/` variants. This is a **user-run** route — the **author** invokes `clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app :substrate :uix` (or `:helix`) in their own shell and gets a working UIx/Helix counter without hand-wiring the substitutions above; **this skill does not run `clojure -Tnew` itself** (its `allowed-tools` cover the manual scaffold — `clojure -Stree`, npm, `shadow-cljs` — not the generator). **Pre-split:** the standalone `day8/re-frame2-template` repo isn't published yet (the template still lives in-monorepo under `tools/template/`; see [`005-Repo-Split.md`](../../../tools/template/spec/005-Repo-Split.md) §4), so that `io.github.day8/…` invocation can't auto-resolve today; pre-release, the author runs the `:local/root` dev route against a checkout of this repo (`clojure -Sdeps '{:deps {day8/re-frame2-template {:local/root "tools/template"}}}' -Tnew create :template day8/re-frame2-template :name acme/my-app :substrate :uix`), or this skill hand-wires the two substitutions above. See [the generator-template section](../README.md#relationship-to-the-generator-template).

## Differences from re-frame v1

If the author is coming from re-frame v1 (re-frame's first version), three things changed at the entry-namespace layer:

| v1 | v2 |
|---|---|
| `(:require [reagent.core :as r])` then `(r/render ...)` | `(:require [reagent.dom.client :as rdc])` then `(rdc/render react-root [view])` — React 19 client-Root surface |
| No explicit boot — `re-frame.core` was self-installing against Reagent | `(rf/init! reagent-adapter/adapter)` is mandatory; adapter is a value the app supplies |
| `defn` views — re-frame v1 had no view registration | `reg-view` macro registers views in a per-app registry; auto-injects `dispatch` / `subscribe` |
| Implicit single global `app-db` | You establish one app frame explicitly (`reg-frame` + a root `frame-provider {:frame …}`); the runtime never infers a default. Multi-frame apps are first-class via additional `frame-provider`s (scope an existing frame with `{:frame …}`, or ensure a named frame with `{:id …}`) |

The full v1→v2 migration story lives under `migration/from-re-frame-v1/` (linked from `SKILL-REDIRECT.md` at the repo root). This skill is greenfield-only; if the author has a v1 codebase, point them at migration instead.
