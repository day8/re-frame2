# Entry namespace

The **boot lifecycle** of `core.cljs` — the entry namespace shadow-cljs's `:init-fn` points at, where re-frame2 wires up to the substrate (Reagent by default) and to the DOM. The file itself is in [`first-counter.md`](first-counter.md) (`src/acme/my_app/core.cljs`); this leaf explains the ceremony it performs so you understand each line rather than pasting it blind, and carries the three files an explicit **UIx** request swaps in.

## Contents

- The entry-namespace lifecycle
- Order of operations
- Why `rf/init!` exists (and why it's explicit)
- The React root pattern (`defonce` + `client-root`)
- Where everything else goes
- UIx greenfield
- Differences from re-frame v1

---

## The entry-namespace lifecycle

`core.cljs` is the `ns` form, a `defonce` client-root handle, and **two** fns: the exported `init` shadow-cljs calls once, and a `^:dev/after-load mount!` the hot reload calls. Reading it top to bottom:

1. `(rf/init! rf.adapter.reagent/adapter)` — install the Reagent adapter. No frame exists yet.
2. `(mount!)` — `(rf.adapter.reagent/render! app-root … el)` the tree wrapped in `[rf/frame-root {:id app-frame :initial-events [[:counter/initialise]]} …]` into `#app`. The first call creates the React root; every later call renders into that same root. `frame-root` is the ENSURE element: it **creates** the frame the first time (running the `:initial-events` seed synchronously, so the first render sees the seeded app-db), **reuses** the live frame without re-seeding on every later render, and provides it so every bare `dispatch` / `subscribe` under it resolves against that frame.

**Why the mount is its own fn: the `^:dev/after-load` metadata on `mount!` is what gives you hot reload.** shadow-cljs calls the module `:init-fn` once, at bundle load. A reload loads the new code and then calls the build's `^:dev/after-load` hooks — it does not call `:init-fn` again, and with no hook configured it says so (`reloading code but no :after-load hooks are configured!`) while the page goes on painting the old view. Splitting the entry keeps the one-time ceremony (`init!`) out of the reload path while `mount!` re-renders the edited views. `defonce` keeps the same handle across saves, so the adapter never creates a second root, and the re-rendered `frame-root` finds the frame already live and leaves your state alone — a hot reload is a **no-op for app state**; the `:initial-events` seed runs once, at frame creation (refresh the tab to re-seed).

`app-frame` is `:rf/default`, the generator template's frame id — under EP-0002 (the carried-frame invariant) it is **not** auto-registered; the view's `frame-root` creates it at mount. Any id works; keep the one `def` and the `:id` in step. The entry symbol is `init`, matching the template's `:init-fn acme.my-app.core/init`; the name is yours as long as `:init-fn` points at it. (The repo's `examples/core/counter/core.cljs` names its entry fn `run` — the same lazy-root + `frame-root` ENSURE boot; see [`boot-and-mount-an-app.md`](https://github.com/day8/re-frame2/blob/main/docs/core/how-to/boot-and-mount-an-app.md).)

## Order of operations

`init` must do these two things, **in this order**, every time:

1. **`(rf/init! rf.adapter.reagent/adapter)`** — install the substrate adapter. `init!` installs the adapter and runtime capabilities only; it creates **no** frame.
2. **`(mount!)`** — the `^:dev/after-load` fn whose body renders `[rf/frame-root {:id app-frame :initial-events [[:counter/initialise]]} [views/counter-app]]` into the retained root. Step 1 is the one-time ceremony and stays in `init`; step 2 is the only one a hot reload re-runs. `frame-root` creates the frame the first time — running the seed **synchronously at frame creation** — then reuses it without re-seeding, so a hot reload never clobbers state and editing what `:counter/initialise` writes changes what a fresh mount seeds. (`frame-provider` is the SCOPE-only sibling: it provides an **already-created** frame, e.g. one built programmatically with `rf/make-frame`, and fails loud given `{:id …}`.)

If you render *without* `frame-root` (or a `frame-provider` around an existing frame), every `subscribe` / `dispatch` in the tree raises `:rf.error/no-frame-context` — the runtime refuses to guess a frame. If you render before `rf/init!`, `frame-root`'s frame creation finds no substrate adapter and you get `:rf.error/no-adapter-installed`.

## Why `rf/init!` exists (and why it's explicit)

re-frame2 splits **the registry** (the process-wide handler / sub / fx map your `reg-*` forms write to) from **the substrate** (Reagent / UIx / plain atom), supplied at boot via an *adapter map*. `rf/init!` is when that adapter map + the runtime capabilities are **installed**, before any frame exists. Three consequences:

- **Adapters are values, not magic.** `re-frame.adapter.reagent/adapter` is a regular CLJS var holding a map. `rf/init!` takes that value directly — no global registration, no name-based lookup. Swap it for `re-frame.adapter.uix/adapter` and you have a UIx app.
- **`rf/init!` is idempotent for the adapter it seated — safe to call more than once.** A hot reload re-runs `mount!`, not `init`, so in the ordinary loop `rf/init!` runs exactly once per page load. A namespace reload that re-evaluates `init`, a REPL call, or a second entry point all re-enter it safely — a second `init!` with the *same* adapter installs nothing and creates no frame. Leave it unguarded in `init` (don't wrap it in a `defonce`). A second `init!` with a **different** adapter raises `:rf.error/adapter-already-installed` rather than being ignored; to swap substrates, `(rf/destroy-adapter!)` first. "Same adapter" means the same canonical `:rf.adapter/*` `:kind`, which is a stable token that survives a namespace reload re-evaluating the adapter Var — so every shipped adapter reloads cleanly. A **custom** adapter map carrying no canonical kind is compared by object identity instead, so re-evaluating *its* Var and re-calling `init!` does raise: hold that map in a `defonce`, or call `rf/destroy-adapter!` in the after-load fn.
- **No implicit boot.** No default adapter — multi-substrate support and the per-frame substrate-config model (Spec 006) mean the runtime must know *which* adapter you want before any subscription resolves, so you supply it at boot via the explicit `init!`.

**You don't construct the adapter map — you require the namespace and pass its exported `adapter` var.** The map implements the reactive-substrate adapter contract of [Spec 006](https://github.com/day8/re-frame2/blob/main/spec/006-ReactiveSubstrate.md); constructing or extending it is the **`re-frame2-implementor`** skill's territory, not greenfield's.

## The React root pattern (`defonce` + `client-root`)

The entry ns holds the adapter's client-root handle in a `defonce`, allocated inert at namespace load and filled by the first `render!` through it. Two contractual bits:

- **`defonce` + `rf.adapter.reagent/client-root`** — `core.cljs` reloads on every save, and `mount!` runs again on each one; the `defonce` keeps the same handle, and the handle keeps the same React root: the first `render!` creates it, every later one renders into it. React 19 complains loudly if `create-root` is called a second time on a live DOM node (or silently mounts two fighting roots) — with the adapter owning the root that cannot happen, and there is no raw root or create-or-render branch in your file. Every reload renders into that same retained root, which is why your app-db and your scroll position survive a save.
- **`(js/document.getElementById "app")`** — must match the `id` in `index.html`. Mismatch here is the most common cause of a blank page with no console error.

`render!` takes the handle, the tree, and the DOM node; the node is read on the first call only. `reagent.dom.client` is never required by the entry ns — the adapter drives it for you (the same three functions come with `day8/reagent-slim`, so the file is byte-for-byte the same on either coordinate).

## Where everything else goes

The scaffold already splits by concern, and `core.cljs` `:require`s the three so their registrations load before the mount:

```
src/acme/my_app/
├── core.cljs        ; the entry ns (this leaf)
├── events.cljs      ; rf/reg-event  — :counter/initialise, :counter/increment
├── subs.cljs        ; rf/reg-sub    — :counter/value
└── views.cljs       ; rf/reg-view   — counter-buttons, counter-app
```

`views.cljs` requires just `[re-frame.core :as rf]` and calls `rf/reg-view` fully qualified — the macro defines the view symbol, registers it under `(keyword *ns* sym)`, and binds `dispatch` / `subscribe` to the frame in scope at render time. Per-app effects (`rf/reg-fx`) go beside the events. The folder shape is a convention; re-frame2 has no opinion about it.

## UIx greenfield

This skill scaffolds against **Reagent**. An explicit UIx request is the **same twelve-file project with three files swapped** — the template's `:substrate :uix` emission. `deps.edn` trades the Reagent adapter + `reagent/reagent` for the UIx adapter + `com.pitch/uix.core` / `uix.dom`; `core.cljs` mounts through `uix.dom`'s root API with the adapter's `frame-root` as a `$` element; `views.cljs` is `defui` + `$`, because UIx has **no auto-injection** — components read subscriptions through the adapter's `use-subscribe` hook and get `dispatch` off `use-frame` (capture-frame in hook position, destructured once per render). The other nine files — `package.json`, `shadow-cljs.edn`, `.gitignore`, `index.html`, `app.css`, `events.cljs`, `subs.cljs`, `events_test.cljs`, `README.md` — are identical to the Reagent scaffold, bar the display label: the generator writes `UIx` where `package.json`'s `description` and the README's first sentence say `Reagent`, and that one-word swap is the whole difference. The dataflow is a framework concern, not a substrate one, and no Xray, schema or devtools piece rides either route. Do **not** reach for the Reagent `rf/reg-view` views on a UIx app, and do not re-derive the events or subs per substrate.

> **Heads-up on the UIx version target.** `spec/006-ReactiveSubstrate.md` names **UIx 2.x** (hooks-based) as the design target, but the template pins **`com.pitch/uix` `1.4.4`** — the **known-good, tested** set. Use the template pin; treat UIx 2.x as an unverified manual override to test before relying on it. The pins below are read off the template's `_uix/deps.edn` by derivation, so they follow a template bump automatically.

> **Pre-publish coordinate shape.** The two `day8/re-frame2*` coords below carry the template's forward-correct `:mvn/version`; until the framework is on Clojars, point them at a checkout (`:local/root "<RE_FRAME2>/implementation/core"` and `…/implementation/adapters/uix`) exactly as `SKILL.md` step 2 does for Reagent. The `com.pitch/uix.*` deps are on Clojars and keep `:mvn/version`.

The three files, derived from the template's `_uix/` tree by `tests/first_counter_derivation.clj` (regenerate with `bb tests/first_counter_derivation.clj`; do not hand-edit the bodies):

<!-- BEGIN generated by tests/first_counter_derivation.clj -->

### `deps.edn`

```clojure
;; acme/my-app — re-frame2 application (UIx).
;;
;; The two day8/re-frame2 coordinates ride one version; bump them together.
;; uix.dom is a direct dependency: the adapter ships uix.core only, and
;; mounting the React root is the application's call.
{:paths ["src"]

 :deps  {org.clojure/clojure       {:mvn/version "1.12.0"}
         org.clojure/clojurescript {:mvn/version "1.12.145"}
         day8/re-frame2            {:mvn/version "0.0.1.alpha"}
         day8/re-frame2-uix        {:mvn/version "0.0.1.alpha"}
         com.pitch/uix.core        {:mvn/version "1.4.4"}
         com.pitch/uix.dom         {:mvn/version "1.4.4"}}

 ;; shadow-cljs.edn reads its classpath from this alias. Deps only — the
 ;; `npx shadow-cljs` wrapper supplies its own `-m`, so no :main-opts here.
 :aliases
 {:shadow {:extra-paths ["test"]
           :extra-deps  {thheller/shadow-cljs {:mvn/version "3.4.10"}}}}}
```

### `src/acme/my_app/core.cljs`

```clojure
(ns acme.my-app.core
  "Entry point: installs the UIx adapter and mounts the app."
  (:require [uix.core             :refer [$]]
            [uix.dom              :as uix-dom]
            [re-frame.core        :as rf]
            [re-frame.adapter.uix :as rf.adapter.uix]
            ;; Requiring these installs their registrations.
            [acme.my-app.events]
            [acme.my-app.subs]
            [acme.my-app.views :as views]))

;; One React root for the life of the page: React must not get a second
;; `create-root` for a live DOM node, and a hot reload has to render into
;; the root that already owns #app.
(defonce ^:private react-root (atom nil))

(def app-frame :rf/default)

;; `mount!` is the ^:dev/after-load hook. shadow-cljs calls it after every
;; successful hot reload — it does NOT re-run `init` — so this is what
;; re-renders your edited views. `frame-root` creates the app frame the
;; first time, running `:initial-events` synchronously so the first render
;; sees the seeded app-db, and reuses the live frame without re-seeding on
;; every later render: a reload leaves app-db exactly as you left it.
(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (when-not @react-root
      (reset! react-root (uix-dom/create-root el)))
    (uix-dom/render-root
      ($ rf.adapter.uix/frame-root {:id             app-frame
                                    :initial-events [[:counter/initialise]]}
         ($ views/counter-app))
      @react-root)))

;; Called ONCE by shadow-cljs (:init-fn in shadow-cljs.edn) when the bundle
;; loads. `init!` installs the adapter; it does not create a frame — the
;; `frame-root` element in `mount!` does.
(defn ^:export init []
  (rf/init! rf.adapter.uix/adapter)
  (mount!))
```

### `src/acme/my_app/views.cljs`

```clojure
(ns acme.my-app.views
  "Views (UIx). Components are `defui`; subscriptions arrive through the
   adapter's `use-subscribe` hook and `dispatch` comes off `use-frame`,
   which captures the render-time frame so a callback targets it later."
  (:require [uix.core             :refer [$ defui]]
            [re-frame.adapter.uix :as rf.adapter.uix]))

(defui counter-buttons []
  (let [value              (rf.adapter.uix/use-subscribe [:counter/value])
        {:keys [dispatch]} (rf.adapter.uix/use-frame)]
    ($ :div
       ($ :button {:on-click #(dispatch [:counter/increment])} "+1")
       ($ :span {:style #js {:margin "0 1em"}} value))))

(defui counter-app []
  ($ :div
     ($ :h1 "acme/my-app")
     ($ counter-buttons)))
```

<!-- END generated -->



The adapter's `frame-root` ensures + provides the app frame for its subtree; rendering without it raises `:rf.error/no-frame-context` on the first subscribe. The `^:dev/after-load mount!` split is the same as the Reagent entry, and this route needs it exactly as much. The fastest path to the same project is the generator itself — `clojure -Tnew create … :substrate :uix`, which the skill runs when the author asks for the generator route: the exact pre-publish command, with its **absolute** `:local/root` into the reviewed checkout's `tools/template`, is in [`README.md` §Running the generator pre-publish](../README.md#running-the-generator-pre-publish).

## Differences from re-frame v1

For a v1 dev starting fresh, three things changed at the entry-namespace layer: the render surface is now the adapter's client root (`rf.adapter.reagent/render!` through a `defonce` `client-root` handle, over React 19's `reagent.dom.client` Root API, which you never call yourself); boot is **explicit** (`(rf/init! rf.adapter.reagent/adapter)` is mandatory — no self-install); and there is **no implicit global `app-db`** — the root `frame-root {:id … :initial-events …}` ENSURE element creates your one app frame at mount (the runtime never infers a default; multi-frame apps add more roots/providers). Views are registered with the `reg-view` macro (v1 used plain `defn`).

The full v1→v2 story is the migration skill's territory (`migration/from-re-frame-v1/`, via `SKILL-REDIRECT.md`); this skill is greenfield-only.
