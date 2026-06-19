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
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

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
  (rf/reg-frame :app/main {:on-create [:counter/initialise]})
  (rdc/render react-root
    [rf/frame-provider-existing {:frame :app/main}
     [counter-app]]))
```

That's the entire greenfield app. ~25 lines of substance, every re-frame2 primitive exercised once.

This is the same `:counter/initialise` / `:counter/increment` events and `:counter/value` sub that the generator template registers (`tools/template/resources/day8/re_frame2_template/_shared/events.cljs` + `_shared/subs.cljs`) and that the UIx / Helix view snippets in [`entry-namespace.md` §UIx / Helix greenfield](entry-namespace.md) dispatch and subscribe — so the SKILL.md claim "the events and subs are identical across substrates; only the view layer differs" holds in **copied** code. The canonical worked example at `examples/reagent/counter/core.cljs` uses the same namespaced `:counter/value` app-db key (seeded to `5` there) and additionally keeps a `:counter/dec` / `-` button; this minimal greenfield counter stays on the single-increment shape the template ships, so the three sources share one vocabulary. If you want decrement, add a `:counter/decrement` event (`(fn [{:keys [db]} _] {:db (update db :counter/value dec)})`) and a matching `-` button in **all** substrate views you use.

## What each block does

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

This is what makes registered views frame-aware without you threading the frame through every component. The macro resolves both at render time against whatever frame is in scope — here `:app/main`, scoped by the root `frame-provider-existing` in `init`; multi-frame apps wrap subtrees in additional providers to swap it (`frame-provider-existing` to scope an existing frame, or the owned `frame-provider` to create-and-own a frame for the subtree's lifetime). (There is no implicit default frame: the provider is what supplies the carried frame to the tree.)

The result is regular Reagent hiccup. Reagent renders, the React 19 root commits, the DOM updates.

### Mount

`defonce` guards `react-root` against hot-reload. See `entry-namespace.md` for why.

`init`'s lines (in this order):

1. `(rf/init! reagent-adapter/adapter)` — install the Reagent substrate adapter (no frame is created here).
2. `(rf/reg-frame :app/main {:on-create [:counter/initialise]})` — register the app frame; `:on-create` runs `[:counter/initialise]` synchronously (inside the frame's own scope) so `app-db` is `{:counter/value 0}` by the time `reg-frame` returns.
3. `(rdc/render react-root [rf/frame-provider-existing {:frame :app/main} [counter-app]])` — mount, wrapped in `frame-provider-existing` (scope-only — the frame already exists from step 2) so the tree's `dispatch` / `subscribe` resolve to `:app/main`.

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
- A thrown / emitted `:rf.error/no-frame-context` — the tree rendered with **no frame established**. Under EP-0002 the runtime infers no frame (there is no auto-registered `:rf/default`), so a bare `subscribe` / `dispatch` outside any scope fails loudly. Fix: wrap the root in `[rf/frame-provider-existing {:frame :app/main} [root-view]]` and ensure `(rf/reg-frame :app/main …)` ran before render — see `init` above.
- A blank/empty subscribed value with **no console error** (e.g. the number never appears) — a subscribe to an unregistered sub builds a nil-yielding reaction and emits `:rf.error/no-such-sub`; it does **not** throw. On this bare route there's no error-sink listener wired, so the miss surfaces only as a silent `nil` render — registrations didn't run. If you split subs into multiple namespaces, make sure `core.cljs` `:require`s them so they load. (The generator template wires an error-sink in `events.cljs` that pushes `:rf.error/no-such-sub` to the console; this minimal counter doesn't, so the only tell is the blank value.)

**No schema errors? That's expected.** This counter attaches no app-db schema, so there is no schema-validation work to do — nothing has been registered, so nothing validates. (This is *not* a "soft-pass": with `day8/re-frame2-schemas` on the classpath, requiring `re-frame.schemas` wires Malli automatically, so the moment you `reg-app-schema` a schema it *does* validate — Spec 010 §Schema implies validation on CLJS. The absence of errors here means "no schema attached," not "validation ran and passed" and not "validation silently no-ops.")

## What to do next

**Setup is done.** From here, **switch skills**:

- **Writing more code (events, subs, machines, schemas, frames, fx, flows, routing, SSR)** — load the **`re-frame2`** skill. It covers the API surface in modular files; you can load just the pieces relevant to what you're building.
- **Inspecting the running app live from the REPL** — install the **`re-frame2-pair`** skill. It attaches over nREPL, lets you walk app-db, dispatch from the REPL, hot-swap handlers, time-travel through epoch history. nREPL stays dev-only and bound to localhost — see SKILL.md cardinal rule 6.

Both skills are independent of this one and can be loaded individually.

If you want worked examples of more substantial re-frame2 apps before you keep building, the repo's `examples/reagent/` directory has worked apps for: TodoMVC, the seven 7GUIs tasks, login with state machines + managed HTTP, routing, SSR, the nine-states pattern, realworld. Browse them via `SKILL-REDIRECT.md` → Examples directory.
