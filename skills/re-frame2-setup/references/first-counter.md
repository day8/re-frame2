# First counter

End-to-end worked example: a working re-frame2 counter in one file. This is the smallest piece of code that exercises every layer (event → handler → app-db change → sub recompute → view re-render).

Use it as the body of `src/your_app/core.cljs`. When it mounts and clicks work, **greenfield setup is done** — switch the author to the main `re-frame2` skill for everything else.

> **Reagent only.** This leaf uses Reagent's `reg-view` macro (with auto-injected `dispatch`/`subscribe`) and `reagent.dom.client`. **UIx and Helix have no auto-injection** — they read subs through the adapter's `use-subscribe` hook and dispatch through `(:dispatch (rf/frame-handle))`, and mount through their own root API. For a UIx/Helix greenfield, do **not** use this Reagent counter: take the complete generator route (SKILL.md cardinal rule 4) or copy the substrate-specific entry ns + `views.cljs` from [`entry-namespace.md` §UIx / Helix greenfield](entry-namespace.md). The events and subs below (`reg-event` / `reg-sub`) are identical across substrates — only the view + mount layer differs.

## Contents

- The whole file
- What each block does
- Verifying it works
- What to do next

---

## The whole file

```clojure
(ns your-app.core
  (:require [reagent.dom.client       :as rdc]
            [re-frame.core            :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Schema artefact — required as side-effecting loads so the
            ;; late-bind validator hooks publish (Malli's validate/explain)
            ;; BEFORE any reg-app-schema call runs. Pulls in Malli via the
            ;; schemas artefact's deps.
            [re-frame.schemas]
            [re-frame.schemas.malli])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Schema ----------------------------------------------------------------

;; A whole-app-db schema attached at the empty path `[]` (get-in/assoc-in
;; grain: `[]` is "the whole map"). Closed map: a typo like `:countr/value`
;; is caught at the write boundary instead of producing a silent nil. The
;; framework validates every registered path-schema after each handler
;; mutation; a non-conforming write rolls back the `:db` effect.
(def CounterDb
  [:map {:closed true}
   [:counter/value :int]])

;; App-db schemas are FRAME-LOCAL (EP-0002 carried-frame invariant): they
;; target a frame, and the runtime never synthesises one from absence. At
;; ns-load time no frame is established, so this registration MUST NOT run as
;; a load-time side-effect (it would raise :rf.error/no-frame-context).
;; Instead it is a boot step: init calls register-schema! AFTER reg-frame
;; makes the app's :rf/default frame live, inside a with-frame scope.
(defn register-schema! []
  (rf/reg-app-schema [] {:schema CounterDb}))   ;; :schema-in-metadata grammar

;; -- Events ----------------------------------------------------------------

(rf/reg-event :counter/initialise
  (fn [_cofx _event] {:db {:counter/value 0}}))

(rf/reg-event :counter/increment
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

;; -- Subscriptions ---------------------------------------------------------

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- Views -----------------------------------------------------------------

(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/increment])} "+1"]
   [:span {:style {:margin "0 1em"}} @(subscribe [:counter/value])]])

(reg-view counter-app []
  [:div
   [:h1 "re-frame2 counter"]
   [counter-buttons]])

;; -- Mount -----------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export init []
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame :rf/default {})
  ;; Attach the schema and seed the app-db under a live frame scope, with
  ;; dispatch-sync so the initial render sees the seeded state (not a
  ;; transient empty frame). This is the reset-boundary shape the generator
  ;; ships: on a hot reload the second reg-frame is a surgical update that
  ;; PRESERVES app-db, and this explicit dispatch-sync re-seeds the demo
  ;; state each time. Seeding via :initial-events instead would only run on
  ;; the first registration, so a save during the demo would not reseed.
  (rf/with-frame :rf/default
    (register-schema!)                          ;; frame-local schema attach
    (rf/dispatch-sync [:counter/initialise]))
  (rdc/render react-root
    [rf/frame-provider {:frame :rf/default}
     [counter-app]]))
```

That's the entire greenfield app. ~35 lines of substance, every re-frame2 primitive exercised once — including the typed-at-boundaries schema attach the generator ships.

This is the same `:counter/initialise` / `:counter/increment` events, `:counter/value` sub, and `CounterDb` whole-app-db schema that the generator template registers (`tools/template/resources/day8/re_frame2_template/_shared/events.cljs` + `_shared/subs.cljs` + `_shared/schema.cljs`, attached under the frame in `_reagent/core.cljs`) and that the UIx / Helix view snippets in [`entry-namespace.md` §UIx / Helix greenfield](entry-namespace.md) dispatch and subscribe — so the SKILL.md claim "the events and subs are identical across substrates; only the view layer differs" holds in **copied** code. The canonical worked example at `examples/reagent/counter/core.cljs` uses the same namespaced `:counter/value` app-db key (seeded to `5` there) and additionally keeps a `:counter/dec` / `-` button; this minimal greenfield counter stays on the single-increment shape the template ships, so the three sources share one vocabulary. If you want decrement, add a `:counter/decrement` event (`(fn [{:keys [db]} _] {:db (update db :counter/value dec)})`) and a matching `-` button in **all** substrate views you use.

## What each block does

### Schema

`CounterDb` is a Malli schema attached at the **empty path `[]`** — the whole-app-db form (`get-in`/`assoc-in` grain, where `[]` means "the whole map"). The framework validates against every registered path-schema **after every handler** completes a state mutation; a non-conforming write rolls back the `:db` effect and emits a structured `:rf.error/schema-validation-failure` trace. A `[:map {:closed true} …]` catches typos (`:countr/value`) at the boundary; open vs closed is a team call (open admits new keys mid-development, closed catches typos — closed is the recommended starter posture).

Two contracts make the attach work:

- **The artefact must be loaded as a side effect before any `reg-app-schema` runs.** Requiring `re-frame.schemas` + `re-frame.schemas.malli` publishes Malli's `validate`/`explain` into the framework's late-bind hook table — so the registration actually validates rather than throwing `:rf.error/schemas-artefact-missing`. (On the CLJS reference, schema **implies** validation — Spec 010 §Schema implies validation; there is no silent soft-pass with the artefact on the classpath.)
- **The attach is frame-local and runs at boot, not at ns-load.** `reg-app-schema` targets a frame (EP-0002 carried-frame invariant); at namespace-load time no frame is established, so registering there raises `:rf.error/no-frame-context`. The attach therefore lives in a `register-schema!` fn that `init` calls **after** `reg-frame` makes `:rf/default` live, inside `(rf/with-frame :rf/default …)`. (Contrast `reg-event` / `reg-sub`, which are frame-agnostic global registrations and are fine at ns-load.)

A schema describes **shape**, not durable egress policy — whether an app-db path is `:sensitive?` / `:large?` (and so redacted/elided at Xray / Story / trace boundaries) is **frame-owned**, declared on `reg-frame`, not re-attached on the schema (Spec 015 §Schemas describe shape, not durable app-db egress policy).

### Events

Two events: an initialiser and one mutation. There is **one** event-registration form — `reg-event`. A handler takes the coeffects map (destructure `:db` from it to read the current app-db) and the event vector, and returns a map describing the next state and what to do: `{:db <next-app-db>}` writes the new app-db. Pure functions, dispatched through re-frame2's drain so they run in order, one at a time.

So `:counter/initialise` returns `{:db {:counter/value 0}}` — seed the app-db to a fresh counter — and `:counter/increment` returns `{:db (update db :counter/value inc)}` — the next app-db with the count bumped. `_cofx` in the initialiser is just the unused coeffects argument; `:counter/increment` destructures `{:keys [db]}` to read the current value.

Adding a side effect later (an HTTP request, navigation, a child dispatch) doesn't change the form: the same handler returns the same `{:db ...}` **plus** an `:fx` key — `{:db ... :fx [...]}`. No new macro, no signature change. The counter doesn't need `:fx` yet.

### Subscriptions

One subscription, `[:counter/value]`, reads `(:counter/value db)`. Views deref it with `@(subscribe [:counter/value])`. Under re-frame2's value-equal recompute suppression, the sub re-runs only when its inputs change; if the new return value is `=` to the previous one, downstream consumers don't re-render. That suppression is automatic; nothing to configure.

### Views

`reg-view` is a macro that **registers a view** under `(keyword *ns* sym)` — here, `:your-app.core/counter-buttons` and `:your-app.core/counter-app`. It also defs a regular CLJS var with the same name so the hiccup `[counter-buttons]` reference works.

Inside the body, two locals are **auto-injected** by the macro:

- `dispatch` — bound to the current frame's `dispatch` fn. Use it like `(dispatch [:counter/increment])`.
- `subscribe` — bound to the current frame's `subscribe` fn. Use it like `@(subscribe [:counter/value])`.

This is what makes registered views frame-aware without you threading the frame through every component. The macro resolves both at render time against whatever frame is in scope — here `:rf/default`, scoped by the root `frame-provider {:frame …}` in `init`; multi-frame apps wrap subtrees in additional `frame-provider`s to swap it (`{:frame …}` to scope an existing frame, or `{:id …}` to ensure a named frame for the subtree). (There is no implicit default frame: the provider is what supplies the carried frame to the tree.)

The result is regular Reagent hiccup. Reagent renders, the React 19 root commits, the DOM updates.

### Mount

`defonce` guards `react-root` against hot-reload. See `entry-namespace.md` for why.

`init`'s lines (in this order):

1. `(rf/init! reagent-adapter/adapter)` — install the Reagent substrate adapter (no frame is created here).
2. `(rf/reg-frame :rf/default {})` — register the app frame. On a hot reload this re-registration is a **surgical update** that preserves the existing app-db (and sub-cache and queue); only the frame's metadata/config is replaced.
3. `(rf/with-frame :rf/default (register-schema!) (rf/dispatch-sync [:counter/initialise]))` — under a live frame scope: first attach the frame-local schema (`reg-app-schema` needs an established frame — see §Schema), then seed the app-db synchronously, so the initial render sees `{:counter/value 0}` rather than a transient empty frame. This explicit `dispatch-sync` is the **reset boundary**: it re-seeds the demo state on **every** hot reload. (Seeding via `:initial-events` instead would only run on the first registration — the surgical update in step 2 does not rerun them — so a save during the demo would leave the counter wherever you'd clicked it.)
4. `(rdc/render react-root [rf/frame-provider {:frame :rf/default} [counter-app]])` — mount, wrapped in `frame-provider`'s `{:frame …}` SCOPE-only shape (the frame already exists from step 2) so the tree's `dispatch` / `subscribe` resolve to `:rf/default`.

## Verifying it works

```
npx shadow-cljs watch app
```

(`npx` resolves the locally-installed `shadow-cljs` from `node_modules/.bin`, so it works even when no global `shadow-cljs` binary is on your shell PATH — the common case on a fresh project, especially on Windows/PowerShell. `npm run watch` runs the same command via the `package.json` script.)

Wait for the compile to land. The terminal prints something like `[:app] Build completed.`

Visit `http://localhost:8280/` (the template's port — or whatever you set in `:dev-http` in `shadow-cljs.edn`).

You should see:

- The heading `re-frame2 counter`
- A `+1` button
- The number `0` beside it

Click `+1` — the number becomes `1`, then `2`, and so on. Refresh the page — back to `0` (state lives in app-db, which resets on full reload).

First-run failures, in roughly the order you'll hit them:
- **`shadow-cljs: command not found` / `Cannot find module`** — `npm install` hasn't run (or you ran `npx shadow-cljs` before installing). Run `npm install`, then retry `npx shadow-cljs watch app`.
- **Page loads but the browser console shows `main.js` 404 (`GET /js/main.js 404`)** — `:output-dir`, `:asset-path`, and `index.html`'s `<script src>` disagree. The template serves `resources/public` with `:output-dir "resources/public/js"` + `:asset-path "/js"` + `<script src="/js/main.js">`; if you changed any one, change the others to match.
- **Blank page, no console errors** — `index.html` is missing `<main id="app">`, or the entry ns looks up a different id than `index.html` declares.

If you see a blank page, open the browser console. Most failures land there with a clear error — but note the missing-sub case below is *silent* on this bare route:
- `Cannot read property 'getElementById' of undefined` — script ran before DOM was ready; check `index.html` loads `main.js` at the *bottom* of `<body>`.
- A thrown `:rf.error/no-adapter-installed` (reason: *"was called before `(rf/init! ...)`; require an adapter ns and pass its `adapter` Var"*) — a view subscribed/rendered before `(rf/init! ...)` seated an adapter. `rf/init!` didn't run before render: check `init` is the `:init-fn` shadow-cljs is calling.
- A thrown / emitted `:rf.error/no-frame-context` — the tree rendered with **no frame established**. Under EP-0002 the runtime infers no frame (`:rf/default` is not auto-registered — you register it yourself), so a bare `subscribe` / `dispatch` outside any scope fails loudly. Fix: wrap the root in `[rf/frame-provider {:frame :rf/default} [root-view]]` and ensure `(rf/reg-frame :rf/default …)` ran before render — see `init` above.
- A blank/empty subscribed value with **no console error** (e.g. the number never appears) — a subscribe to an unregistered sub builds a nil-yielding reaction and emits `:rf.error/no-such-sub`; it does **not** throw. On this bare route there's no error-sink listener wired, so the miss surfaces only as a silent `nil` render — registrations didn't run. If you split subs into multiple namespaces, make sure `core.cljs` `:require`s them so they load. (The generator template wires an error-sink in `events.cljs` that pushes `:rf.error/no-such-sub` to the console; this minimal counter doesn't, so the only tell is the blank value.)

**No schema errors? That's expected — and it means validation ran clean.** This counter attaches the `CounterDb` whole-app-db schema (see §Schema), so after `:counter/initialise` and each `:counter/increment` the framework *does* validate the new app-db against it — and `{:counter/value 0}` (then `1`, `2`, …) conforms, so there are no `:rf.error/schema-validation-failure` traces. This is real validation, not a soft-pass: with `day8/re-frame2-schemas` on the classpath, requiring `re-frame.schemas` wires Malli automatically (Spec 010 §Schema implies validation on CLJS). To *see* it fire, temporarily seed a non-`:int` value (e.g. `{:counter/value "0"}` in `:counter/initialise`) — the write rolls back and the error sink surfaces the violation; revert it once you've seen the boundary work.

## What to do next

**Setup is done.** From here, **switch skills**:

- **Writing more code (events, subs, machines, schemas, frames, fx, flows, routing, SSR)** — load the **`re-frame2`** skill. It covers the API surface in modular files; you can load just the pieces relevant to what you're building.
- **Inspecting the running app live from the REPL** — install the **`re-frame2-pair`** skill. It attaches over nREPL, lets you walk app-db, dispatch from the REPL, hot-swap handlers, time-travel through epoch history. nREPL stays dev-only and bound to localhost — see SKILL.md cardinal rule 6.

Both skills are independent of this one and can be loaded individually.

If you want worked examples of more substantial re-frame2 apps before you keep building, the repo's `examples/reagent/` directory has worked apps for: TodoMVC, the seven 7GUIs tasks, login with state machines + managed HTTP, routing, SSR, the nine-states pattern, realworld. Browse them via `SKILL-REDIRECT.md` → Examples directory.
