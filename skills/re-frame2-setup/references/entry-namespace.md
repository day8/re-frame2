# Entry namespace

The **boot lifecycle** of `your-app/core.cljs` — the entry namespace shadow-cljs's `:init-fn` points at, where re-frame2 wires up to the substrate (Reagent) and to the DOM. The **sole copy-complete `core.cljs`** you write lives in [`first-counter.md` §The whole file](first-counter.md); this leaf explains the boot ceremony that file performs (and the substrate deltas for a UIx greenfield) so you understand each line rather than pasting it blind.

## Contents

- The entry-namespace lifecycle
- Order of operations
- Why `rf/init!` exists (and why it's explicit)
- The Reagent root pattern (`defonce` + `rdc/create-root`)
- Where everything else goes
- UIx greenfield
- Differences from re-frame v1

---

## The entry-namespace lifecycle

The entry namespace is the top of `core.cljs` — the `ns` form, a `defonce` React-root atom, and the exported `init` fn shadow-cljs calls. It is the mount half of the one copy-complete counter in [`first-counter.md`](first-counter.md) (§The whole file; counter-specific mount notes in §Mount), not a file you write separately. Reading that `init` top to bottom, it does four things, in order — full contract in §Order of operations below:

1. `(rf/init! reagent-adapter/adapter)` — install the Reagent adapter (no frame yet).
2. `(register-schema!)` — attach the frame-local schema, naming the app frame explicitly (`{:frame :rf/default}`), before the frame even exists.
3. `(rdc/render …)` — mount the tree wrapped in `[rf/frame-root {:id :rf/default :initial-events [[:counter/initialise]]} …]`. `frame-root` is the ENSURE element: it **creates** `:rf/default` the first time (running the `:initial-events` seed synchronously, so the initial render sees the seeded app-db), **reuses** the live frame without re-seeding on every later mount, and provides it so every bare `dispatch` / `subscribe` under it resolves against `:rf/default`.

`defonce` guards the React root so a save doesn't create a second one (§The Reagent root pattern). shadow-cljs's `:browser` target re-runs `:init-fn` (`init`) after **each** hot reload, so `init` IS the re-render path — no separate `^:dev/after-load` hook. The re-rendered `frame-root` finds `:rf/default` already live and leaves your state alone — a hot reload is a **no-op for app state**; the `:initial-events` seed runs once, at frame creation (refresh the tab to re-seed). Events / subs / views live above the mount point in `core.cljs`, or in their own namespaces for anything larger (§Where everything else goes).

`counter-app` is the top-level registered view. `:rf/default` is the generator template's frame id — under EP-0002 (the carried-frame invariant) it is **not** auto-registered; the view's `frame-root` ENSURE element creates it at mount (§Order of operations). Any id works — keep it consistent between `frame-root`'s `:id` and the schema attach's `{:frame …}` target.

The entry symbol is `init`, matching the generator template's `:init-fn {{namespace}}.core/init`. (The repo's `examples/core/counter/core.cljs` names its entry fn `run` — the same lazy-root + `frame-root {:id … :initial-events …}` ENSURE boot; see [`boot-and-mount-an-app.md`](../../../docs/core/how-to/boot-and-mount-an-app.md). Pick any entry-symbol name and keep `:init-fn` pointing at it; this skill uses `init`.)

## Order of operations

`init` must do these things, **in this order**, every time:

1. **`(rf/init! reagent-adapter/adapter)`** — install the substrate adapter. (`init!` installs the adapter and runtime capabilities only; it creates **no** frame.)
2. **`(register-schema!)`** — attach the frame-local schema. The registration names the app frame **explicitly** (`(rf/reg-app-schema [] {:frame :rf/default} CounterDb)`), so it runs at boot BEFORE the frame exists — the `:initial-events` seed in step 3 is then validated from its very first write. (A bare two-slot `reg-app-schema` with no frame scope raises `:rf.error/no-frame-context`; see [`first-counter.md` §Schema](first-counter.md).)
3. **`(rdc/render react-root [rf/frame-root {:id :rf/default :initial-events [[:your-app/initialise]]} [counter-app]])`** — mount the React tree in `frame-root`'s `{:id …}` **ENSURE** shape. `frame-root` creates `:rf/default` the first time — running the `:initial-events` seed **synchronously at frame creation**, so the initial render sees the seeded app-db — then **reuses** the live frame without re-seeding on every later mount, and provides the frame so every bare `dispatch` / `subscribe` under it resolves against `:rf/default`. A hot reload therefore never clobbers state; the seed runs at frame **creation** (a browser refresh re-seeds), and editing what `:your-app/initialise` writes changes what a fresh mount seeds. (`frame-provider` is the SCOPE-only sibling — it provides an **already-created** frame, e.g. one your code built programmatically with `rf/make-frame`, and fails loud given `{:id …}`.)

If you render *without* `frame-root` (or a `frame-provider` around an existing frame), every `subscribe` / `dispatch` in the tree raises `:rf.error/no-frame-context` — the runtime refuses to guess a frame. If you render before `rf/init!`, `frame-root`'s frame creation finds no substrate adapter and you get `:rf.error/no-adapter-installed`.

## Why `rf/init!` exists (and why it's explicit)

re-frame2 splits **the registry** (the process-wide handler / sub / fx map your `reg-*` forms write to) from **the substrate** (Reagent / UIx / plain atom), supplied at boot via an *adapter map*. `rf/init!` is when that adapter map + the runtime capabilities are **installed**, before any frame exists. (A single-frame app never spells an *image* — that layering is the `re-frame2` skill's territory; here the ordinary `reg-*` path writes the default registrations and your one frame resolves them.)

Three consequences:

- **Adapters are values, not magic.** `re-frame.adapter.reagent/adapter` is a regular CLJS var holding a map. `rf/init!` takes that value directly — no global registration, no name-based lookup. Swap it for `re-frame.adapter.uix/adapter` and you have a UIx app.
- **`rf/init!` is idempotent — safe to call more than once.** `init` re-runs on **every** hot reload, so `rf/init!` runs again each save; that is safe — a second `init!` re-installs the substrate config only when none is seated, and creates no frame. Leave it unguarded in `init` (don't wrap it in a `defonce`); the idempotence is what makes the per-reload re-run harmless. (`frame-root` re-mounting against the same `:id` likewise reuses the live frame — no re-seed, no state loss.)
- **No implicit boot.** No default adapter — multi-substrate support and the per-frame substrate-config model (Spec 006) mean the runtime must know *which* adapter you want before any subscription resolves, so you supply it at boot via the explicit `init!`.

**You don't construct the adapter map — you require the namespace and pass its exported `adapter` var.** App authors never assemble it by hand; the map implements the reactive-substrate adapter contract of [Spec 006](../../../spec/006-ReactiveSubstrate.md), and constructing or extending it is the **`re-frame2-implementor`** skill's territory, not greenfield's.

## The Reagent root pattern (`defonce` + `rdc/create-root`)

The entry ns holds the React root in a `defonce` atom, created lazily inside `init`. Two contractual bits:

- **`defonce`** — `core.cljs` reloads on every save; without `defonce`, each reload calls `create-root` again on the same DOM node and React 19 complains loudly (or silently mounts two fighting roots). `defonce` creates the root exactly once per page load.
- **`(js/document.getElementById "app")`** — must match the `id` in `index.html`. Mismatch here is the most common cause of a blank page with no console error.

`rdc/render` is called against `react-root`, not the DOM node. Both `create-root` and `render` come from `reagent.dom.client` — the React 19 client-Root surface for Reagent 2.x.

## Where everything else goes

In a tiny app, all of this fits in `your-app/core.cljs` above the mount point:

- **Events** — `(rf/reg-event ...)` (the one event-registration form)
- **Subscriptions** — `(rf/reg-sub ...)`; **Effects** — `(rf/reg-fx ...)` (per-app fx)
- **Views** — `(reg-view <name> [...] <body>)` (the `reg-view` macro from `re-frame.core`)
- **`:initial-events [[:your-app/initialise]]`** on the root `frame-root` — the initial seed event, run once at frame creation

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

## UIx greenfield

This skill scaffolds against **Reagent** (the default reference substrate). For a UIx greenfield the **dataflow is unchanged** — the events, subscription, and schema are the substrate-neutral trio in [`shared-dataflow.md`](shared-dataflow.md), copied **verbatim**. Four layers are substrate-specific: `deps.edn`, the build wiring (`package.json` + `shadow-cljs.edn` + `index.html` + `app.css` — all **Xray-free** on this route), the entry-ns root API, and the views. The **complete** emitted project is the three shared files (`events.cljs` + `subs.cljs` + `schema.cljs`) plus the substrate `core.cljs` + `views.cljs` and the Xray-free build wiring below — nothing from the Reagent-only `first-counter.md`:

- **deps.edn** — swap `day8/re-frame2-reagent` for `day8/re-frame2-uix`, drop the `reagent/reagent` pin, **drop the `day8/re-frame2-xray` coord** (this route ships no Xray — see the build-wiring bullet below), and add the substrate's Maven deps **at the exact versions the generator template pins** (the template is the source of truth — do not chase latest or invent a version, same discipline as the Reagent/React/shadow pins in [`deps-versions.md`](deps-versions.md)). The UIx day-one framework set is **three** coords — core + `-uix` + `-schemas` — matching the template's `_uix/deps.edn`:

  > **Pre-publish coordinate shape.** The `day8/re-frame2-uix` **framework** coord below shows `:mvn/version "<VERSION>"` (the post-publish shape); pre-publish it takes the `:git/sha` / `:local/root` form like the Reagent day-one set — see [`deps-versions.md` §Choosing the coordinate](deps-versions.md#choosing-the-coordinate-publication-state-decides-the-shape). The `com.pitch/uix.*` substrate deps are on Clojars and keep `:mvn/version`.

  ```clojure
  ;; UIx — replace the reagent line with (framework coord post-publish shape; pre-publish use :git/sha):
  day8/re-frame2-uix {:mvn/version "<VERSION>"}
  com.pitch/uix.core {:mvn/version "1.4.4"}
  com.pitch/uix.dom  {:mvn/version "1.4.4"}
  ;; ...and DELETE the day8/re-frame2-xray line — the UIx route ships no Xray.
  ```

  > **Heads-up on the UIx version target.** `spec/006-ReactiveSubstrate.md` names **UIx 2.x** (hooks-based) as the design target, but the generator template pins **`com.pitch/uix` `1.4.4`** — the **known-good, tested** set. Use the template pin; treat UIx 2.x as an unverified manual override to test before relying on it. (Pins read off the template's `_uix/deps.edn`; re-check if either bumps.)
- **build wiring — no Xray pieces on this route.** Xray's panel shell is hiccup, rendered through the ratom-family (Reagent) substrates; on an element-shaped React substrate like UIx it cannot mount — the launch verbs refuse cleanly, and hiccup handed to the React-hook render slot raises the `:rf.error/hiccup-on-element-render-slot` diagnostic. So the UIx scaffold ships **no dependency it cannot honour**: no `day8/re-frame2-xray` coord, no `@xyflow/react` / `elkjs` npm packages, no `:devtools` preload, no `[data-rf-xray-host]` host column, no `.rf2-xray-host` CSS — matching the generator template's `_uix` emission. The devtools story on this route: **Story and the `re-frame2-pair` tooling work on every substrate**; Xray rides the ratom-family substrates today (UIx support follows once Xray mounts on element substrates). Concretely, three files diverge from the Reagent shapes in [`shadow-cljs.md`](shadow-cljs.md):

  - `package.json` — **three** npm deps only: `shadow-cljs` (dev), `react` + `react-dom` (runtime), at the pinned `implementation/package.json` versions. No `@xyflow/react`, no `elkjs` — they exist solely for the machine canvas inside the Xray preload this route never loads ([`deps-versions.md` §`package.json`](deps-versions.md)).
  - `shadow-cljs.edn` — same build, **no `:devtools` key** (the template's `_uix` emission simply has none):

  ```clojure
  ;; UIx — shadow-cljs.edn (the whole file; note there is no devtools-preload entry)
  {:deps   {:aliases [:shadow]}           ;; pull classpath from deps.edn's :shadow alias
   :source-paths ["src"]

   :dev-http {8280 "resources/public"}    ;; dev server: serve resources/public on :8280

   :builds
   {:app
    {:target     :browser
     :output-dir "resources/public/js"
     :asset-path "/js"
     :modules    {:main {:init-fn your-app.core/init}}}}}
  ```

  - `index.html` + `app.css` — the same shell and dev CSP, **without the `<aside data-rf-xray-host>` column or the `.rf2-xray-host` rules** (`#app` simply spans the viewport):

  ```html
  <!doctype html>
  <html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <!-- Same dev-flavoured CSP as the Reagent route (shadow-cljs.md §index.html):
         'unsafe-eval' for shadow's dev-build module loading, 'unsafe-inline'
         for the views' inline :style props. -->
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' ws: wss:; object-src 'none'; base-uri 'none'">
    <title>your-app — re-frame2</title>
    <link rel="stylesheet" href="/css/app.css">
  </head>
  <body>
    <div class="rf2-app-shell">
      <main id="app"></main>
    </div>
    <script src="/js/main.js"></script>
  </body>
  </html>
  ```

  ```css
  body { font: 16px/1.4 system-ui, sans-serif; margin: 0; }
  .rf2-app-shell { display: flex; min-height: 100vh; }
  #app { flex: 1; min-width: 0; padding: 2em; }
  ```

- **shared dataflow** — first lay down the substrate-neutral `events.cljs` + `subs.cljs` + `schema.cljs` from [`shared-dataflow.md`](shared-dataflow.md), copied **verbatim** into `src/your_app/`. They carry the `:counter/initialise` / `:counter/increment` events, the `:counter/value` sub, and the `CounterDb` schema + `register-schema!` — identical to the events / sub / schema the Reagent `first-counter.md` inlines. Do **not** copy them out of the Reagent file, and do **not** re-derive them per substrate.
- **entry ns** — `:require` the shared `your-app.events`, `your-app.subs`, and `your-app.schema` (so their registrations load), require `[re-frame.adapter.uix :as uix-adapter]`, pass `uix-adapter/adapter` to `rf/init!`, call `(schema/register-schema!)` (it names `:rf/default` explicitly, so it runs before the frame exists — the generator's boot order), and mount with the substrate's own root API — **wrapping the tree in the adapter's `frame-root` `{:id … :initial-events …}` ENSURE shape** (a `$`-element native component that creates the frame at mount and reuses it without re-seeding on later mounts). For UIx that's `uix.dom/create-root` + `uix.dom/render-root`, view wrapped in `$` from `uix.core`:
  ```clojure
  (ns your-app.core
    (:require [uix.core             :refer [$]]
              [uix.dom              :as uix-dom]
              [re-frame.core        :as rf]
              [re-frame.adapter.uix :as uix-adapter]
              [your-app.events]              ;; reg-event registrations (shared-dataflow.md)
              [your-app.subs]                ;; reg-sub registration   (shared-dataflow.md)
              [your-app.schema :as schema]   ;; CounterDb + register-schema! (shared-dataflow.md)
              [your-app.views  :as views]))

  (defonce react-root (uix-dom/create-root (js/document.getElementById "app")))

  (defn ^:export init []
    (rf/init! uix-adapter/adapter)
    (schema/register-schema!)   ;; names :rf/default explicitly — attach before the frame exists
    (uix-dom/render-root
      ($ uix-adapter/frame-root {:id             :rf/default
                                 :initial-events [[:counter/initialise]]}
        ($ views/counter-app))
      react-root))
  ```
  The adapter's `frame-root` ensures + provides the app frame for its subtree; rendering without it raises `:rf.error/no-frame-context` on the first subscribe. The entry namespace matches the generator template's `_uix/core.cljs`.
- **views** — the substitution `first-counter.md` does **not** cover. Reagent's `reg-view` auto-injects `dispatch`/`subscribe`; UIx has **no auto-injection** — components read subs through the adapter's `use-subscribe` hook and get `dispatch` off the adapter's `use-frame` hook (capture-frame in hook position), destructured once per render (the closed-over `dispatch` still targets that frame from an async callback). UIx uses `defui` + `$`. Copy the `views.cljs` below verbatim (identical to the template's `_uix/views.cljs`, reproduced here so the recipe is self-contained):

  ```clojure
  ;; UIx — src/your_app/views.cljs
  (ns your-app.views
    (:require [uix.core             :refer [$ defui]]
              [re-frame.adapter.uix :as uix-adapter]))

  (defui counter-buttons []
    (let [value              (uix-adapter/use-subscribe [:counter/value])
          {:keys [dispatch]} (uix-adapter/use-frame)]
      ($ :div
         ($ :button {:on-click #(dispatch [:counter/increment])} "+1")
         ($ :span {:style #js {:margin "0 1em"}} value))))

  (defui counter-app []
    ($ :div
       ($ :h1 "your-app")
       ($ counter-buttons)))
  ```

- everything the two substrates share is **single-sourced**: the events, subscription, and schema live once in [`shared-dataflow.md`](shared-dataflow.md), and the `frame-root` `:initial-events` seed + `:init-fn ...core/init` boot are the same. **What differs is the view layer and the Xray-free build wiring** — do not reach for the Reagent `reg-view` first-counter leaf (or its Xray-wired build shapes) on a UIx app; use the substrate views and the build wiring above (or take the complete generator route below).

The fastest path for a non-Reagent greenfield is the **generator template**, which ships a complete `_uix/` variant. This is a **user-run** route — the **author** invokes `clojure -Tnew create … :substrate :uix` in their own shell; **this skill does not run `clojure -Tnew`** (its `allowed-tools` cover only the manual scaffold). Pre-publish caveat + the working `:local/root` dev route: [`SKILL.md` cardinal rule 5](../SKILL.md) and [the generator-template section](../README.md#relationship-to-the-generator-template).

## Differences from re-frame v1

For a v1 dev starting fresh, three things changed at the entry-namespace layer: the render surface is now `reagent.dom.client` (`rdc/render` against a `create-root` root — React 19); boot is **explicit** (`(rf/init! reagent-adapter/adapter)` is mandatory — no self-install); and there is **no implicit global `app-db`** — the root `frame-root {:id … :initial-events …}` ENSURE element creates your one app frame at mount (the runtime never infers a default; multi-frame apps add more roots/providers). Views are registered with the `reg-view` macro (v1 used plain `defn`).

The full v1→v2 story is the migration skill's territory (`migration/from-re-frame-v1/`, via `SKILL-REDIRECT.md`); this skill is greenfield-only.
