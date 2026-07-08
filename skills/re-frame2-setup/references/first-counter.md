# First counter

End-to-end worked example: a working re-frame2 counter in one file. This is the smallest piece of code that exercises every layer (event → handler → app-db change → sub recompute → view re-render).

Use it as the body of `src/your_app/core.cljs`. When it mounts and clicks work, **greenfield setup is done** — switch the author to the main `re-frame2` skill for everything else.

> **Reagent only.** This leaf uses Reagent's `reg-view` macro (auto-injected `dispatch`/`subscribe`) and `reagent.dom.client`. **UIx and Helix have no auto-injection** — they read subs through the adapter's `use-subscribe` hook, dispatch through `(:dispatch (rf/capture-frame))`, and mount through their own root API. For a UIx/Helix greenfield do **not** use this counter: copy the substrate entry ns + `views.cljs` from [`entry-namespace.md` §UIx / Helix greenfield](entry-namespace.md), or take the generator route (SKILL.md cardinal rule 5). The events and subs below (`reg-event` / `reg-sub`) are identical across substrates — only the view + mount layer differs.

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
;; target a frame, and the runtime never synthesises one from absence. A
;; bare ns-load reg-app-schema with no frame in scope raises
;; :rf.error/no-frame-context, so the attach lives in `register-schema!` and
;; runs at BOOT — `init` calls it under `with-frame :rf/default`, AFTER
;; `reg-frame` makes the frame live (see the Mount section below). This is the
;; same register-schema! the generator template ships in `_shared/schema.cljs`.
;; (Contrast reg-event / reg-sub, which are frame-agnostic global
;; registrations and are fine at ns-load.)
(defn register-schema! []
  (rf/reg-app-schema [] CounterDb))   ;; schema is the positional value slot

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

;; Namespace load does no DOM work — the root is created lazily inside init.
(defonce react-root (atom nil))

;; shadow-cljs's :browser target re-runs :init-fn (init) after EACH hot
;; reload, so init IS the re-render path — no separate ^:dev/after-load hook.
(defn ^:export init []
  (rf/init! reagent-adapter/adapter)             ;; install the Reagent adapter (no frame yet)
  (rf/reg-frame :rf/default {})                   ;; register the app frame (surgical hot-reload update)
  (rf/with-frame :rf/default                      ;; under a live frame scope:
    (register-schema!)                            ;;   attach the frame-local schema (needs a frame)
    (rf/dispatch-sync [:counter/initialise]))     ;;   seed app-db synchronously — the reset boundary
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

That's the entire greenfield app. ~35 lines of substance, every re-frame2 primitive exercised once — including the typed-at-boundaries schema attach the generator ships.

These are the same `:counter/initialise` / `:counter/increment` events, `:counter/value` sub, and `CounterDb` schema the generator template registers (`tools/template/resources/day8/re_frame2_template/_shared/events.cljs` + `_shared/subs.cljs` + `_shared/schema.cljs`, attached under the frame in `_reagent/core.cljs`) — and the same forms the UIx / Helix snippets in [`entry-namespace.md` §UIx / Helix greenfield](entry-namespace.md) dispatch and subscribe, so the "events and subs identical across substrates; only the view differs" claim holds in **copied** code.

The canonical worked example `examples/core/counter/core.cljs` uses the same `:counter/value` key (seeded to `5`) and adds a `:counter/dec` / `-` button; this minimal counter stays single-increment. To add decrement: a `:counter/decrement` event (`(fn [{:keys [db]} _] {:db (update db :counter/value dec)})`) plus a `-` button in **all** substrate views you use.

## What each block does

### Schema

`CounterDb` is a Malli schema attached at the **empty path `[]`** — the whole-app-db form (`get-in`/`assoc-in` grain, where `[]` means "the whole map"). The framework validates against every registered path-schema **after every handler** completes a state mutation; a non-conforming write rolls back the `:db` effect and emits a structured `:rf.error/schema-validation-failure` trace. A `[:map {:closed true} …]` catches typos (`:countr/value`) at the boundary; open vs closed is a team call (open admits new keys mid-development, closed catches typos — closed is the recommended starter posture).

Two contracts make the attach work:

- **The artefact must be loaded as a side effect before any `reg-app-schema` runs.** Requiring `re-frame.schemas` + `re-frame.schemas.malli` publishes Malli's `validate`/`explain` into the framework's late-bind hook table — so the registration actually validates rather than throwing `:rf.error/schemas-artefact-missing`. (On the CLJS reference, schema **implies** validation — Spec 010 §Schema implies validation; there is no silent soft-pass with the artefact on the classpath.)
- **The attach is frame-local and runs at boot, not at ns-load.** `reg-app-schema` targets a frame (EP-0002 carried-frame invariant); at namespace-load time no frame is established, so registering there raises `:rf.error/no-frame-context`. The attach therefore lives in a `register-schema!` fn that `init` calls **after** `reg-frame` makes `:rf/default` live, inside `(rf/with-frame :rf/default …)`. (Contrast `reg-event` / `reg-sub`, which are frame-agnostic global registrations and are fine at ns-load.)

A schema describes **shape**, not egress policy — whether a path is `:sensitive?` / `:large?` (redacted/elided at Xray/trace boundaries) is **frame-owned** on `reg-frame`, not on the schema (Spec 015).

### Events

An initialiser and one mutation, both via the **one** event-registration form `reg-event`. A handler takes the coeffects map (destructure `:db` to read the current app-db) + the event vector, and returns `{:db <next-app-db>}`. Pure functions, dispatched through re-frame2's drain so they run in order, one at a time. So `:counter/initialise` returns `{:db {:counter/value 0}}` (seed a fresh counter); `:counter/increment` destructures `{:keys [db]}` and returns `{:db (update db :counter/value inc)}` (count bumped). `_cofx` is the unused coeffects argument.

Adding a side effect later (HTTP, navigation, a child dispatch) doesn't change the form — the same handler returns `{:db ... :fx [...]}`, no new macro, no signature change. The counter doesn't need `:fx` yet.

### Subscriptions

One subscription, `[:counter/value]`, reads `(:counter/value db)`. Views deref it with `@(subscribe [:counter/value])`. re-frame2's value-equal recompute suppression (a sub re-runs only when inputs change, and downstream consumers skip re-render when the value is unchanged) is automatic — nothing to configure here.

### Views

`reg-view` **registers a view** under `(keyword *ns* sym)` (here `:your-app.core/counter-buttons` / `:counter-app`) and defs a same-named var so `[counter-buttons]` works. Inside the body it auto-injects two frame-bound locals — `dispatch` (e.g. `(dispatch [:counter/increment])`) and `subscribe` (e.g. `@(subscribe [:counter/value])`) — resolved at render time against the frame in scope (here `:rf/default`, from the root `frame-provider` in `init`), so you never thread the frame through components. The result is regular Reagent hiccup.

### Mount

`defonce` guards `react-root` against hot-reload. `init` runs four steps in order — `rf/init!` → `reg-frame` → `with-frame (register-schema!) (dispatch-sync …)` → `rdc/render` wrapped in `frame-provider` — fully detailed in [`entry-namespace.md` §Order of operations](entry-namespace.md). Two counter-specific facts:

- **Step 3 attaches the schema before the seed**, both inside `(rf/with-frame :rf/default …)`: `reg-app-schema` needs an established frame (§Schema), and seeding synchronously means the initial render sees `{:counter/value 0}`.
- **The `dispatch-sync` seed re-seeds this counter on every hot reload** (the reset boundary — see entry-namespace.md), so a save mid-demo resets the count to `0` rather than leaving it where you'd clicked.

## Verifying it works

```
npx shadow-cljs watch app
```

(`npx` resolves the locally-installed `shadow-cljs` from `node_modules/.bin`, so it works with no global binary on PATH — the common case on a fresh project, especially on Windows/PowerShell. `npm run watch` runs the same command.)

Wait for the compile to land (the terminal prints `[:app] Build completed.`), then visit `http://localhost:8280/` (the template's port — or whatever you set in `:dev-http`).

You should see:

- The heading `re-frame2 counter`
- A `+1` button
- The number `0` beside it

Click `+1` — the number becomes `1`, then `2`, and so on. Refresh the page — back to `0` (state lives in app-db, which resets on full reload).

First-run failures, roughly in order:
- **`shadow-cljs: command not found` / `Cannot find module`** — `npm install` hasn't run (or you ran `npx shadow-cljs` before installing). Run `npm install`, then retry `npx shadow-cljs watch app`.
- **Page loads but the browser console shows `main.js` 404 (`GET /js/main.js 404`)** — `:output-dir`, `:asset-path`, and `index.html`'s `<script src>` disagree. The template serves `resources/public` with `:output-dir "resources/public/js"` + `:asset-path "/js"` + `<script src="/js/main.js">`; if you changed any one, change the others to match.
- **Blank page, no console errors** — `index.html` is missing `<main id="app">`, or the entry ns looks up a different id than `index.html` declares.

If you see a blank page, open the browser console. Most failures land there with a clear error — but note the missing-sub case below is *silent* on this bare route:
- `Cannot read property 'getElementById' of undefined` — script ran before DOM was ready; check `index.html` loads `main.js` at the *bottom* of `<body>`.
- A thrown `:rf.error/no-adapter-installed` (reason: *"was called before `(rf/init! ...)`; require an adapter ns and pass its `adapter` Var"*) — a view subscribed/rendered before `(rf/init! ...)` seated an adapter. `rf/init!` didn't run before render: check `init` is the `:init-fn` shadow-cljs is calling.
- A thrown / emitted `:rf.error/no-frame-context` — the tree rendered with **no frame established**. Under EP-0002 the runtime infers no frame (`:rf/default` is not auto-registered — you register it yourself), so a bare `subscribe` / `dispatch` outside any scope fails loudly. Fix: wrap the root in `[rf/frame-provider {:frame :rf/default} [root-view]]` and ensure `(rf/reg-frame :rf/default …)` ran before render — see `init` above.
- A blank/empty subscribed value with **no console error** (e.g. the number never appears) — a subscribe to an unregistered sub builds a nil-yielding reaction and emits `:rf.error/no-such-sub`; it does **not** throw. On this bare route there's no error-sink listener wired, so the miss surfaces only as a silent `nil` render — registrations didn't run. If you split subs into multiple namespaces, make sure `core.cljs` `:require`s them so they load. (The generator template wires an error-sink in `events.cljs` that pushes `:rf.error/no-such-sub` to the console; this minimal counter doesn't, so the only tell is the blank value.)

**No schema errors? That's validation running clean, not a soft-pass.** This counter attaches the `CounterDb` schema (§Schema), so after `:counter/initialise` and each `:counter/increment` the framework *does* validate the new app-db — and `{:counter/value 0}` (then `1`, `2`, …) conforms, so no `:rf.error/schema-validation-failure` traces. It is real validation: with `day8/re-frame2-schemas` on the classpath, requiring `re-frame.schemas` wires Malli automatically (Spec 010 §Schema implies validation on CLJS). To *see* it fire, temporarily seed a non-`:int` (e.g. `{:counter/value "0"}` in `:counter/initialise`) — the write rolls back and the error sink surfaces the violation; revert once seen.

## What to do next

**Setup is done.** From here, **switch skills**:

- **Writing more code (events, subs, machines, schemas, frames, fx, flows, routing, SSR)** — load the **`re-frame2`** skill. It covers the API surface in modular files; you can load just the pieces relevant to what you're building.
- **Touring the Xray panel that just auto-opened** — load the **`re-frame2-xray`** skill. The day-one preload mounts Xray into the right-side host the moment the counter renders; the skill is a read-only tour of how to launch it and which tab surfaces what (epoch cascade, app-db, trace, the registry-browse Static tabs).
- **Inspecting the running app live from the REPL** — install the **`re-frame2-pair`** skill. It attaches over nREPL to walk app-db, dispatch from the REPL, hot-swap handlers, and time-travel through epoch history (that last needs `day8/re-frame2-epoch` on your own classpath — it is **not** in the day-one deps; see [`deps-versions.md`](deps-versions.md)). nREPL stays dev-only and bound to localhost — see SKILL.md cardinal rule 7.

All three are independent and can be loaded individually.

For worked examples of more substantial apps, the repo's `examples/` directory has TodoMVC, the seven 7GUIs tasks, login with state machines + managed HTTP, routing, SSR, the nine-states pattern, and realworld. Browse them via `SKILL-REDIRECT.md` → Examples directory.
