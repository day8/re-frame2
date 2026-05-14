# first-counter

End-to-end worked example: a working re-frame2 counter in one file. This is the smallest piece of code that exercises every layer (event → handler → app-db change → sub recompute → view re-render).

Use it as the body of `src/your_app/core.cljs`. When it mounts and clicks work, **greenfield setup is done** — switch the author to the main `re-frame2` skill for everything else.

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

(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:count 0}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :count inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :count dec)))

;; -- Subscriptions ---------------------------------------------------------

(rf/reg-sub :count
  (fn [db _query] (:count db)))

;; -- Views -----------------------------------------------------------------

(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"}} @(subscribe [:count])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

(reg-view root-view []
  [:div
   [:h1 "re-frame2 counter"]
   [counter-buttons]])

;; -- Mount -----------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:counter/initialise])
  (rdc/render react-root [root-view]))
```

That's the entire greenfield app. ~30 lines of substance, every re-frame2 primitive exercised once.

## What each block does

### Events

Three events: an initialiser and two mutations. Each takes the current `db`, returns the next `db`. Pure functions, dispatched through re-frame2's drain so they run in order, one at a time.

`reg-event-db` is the "DB-only" event handler — the handler's return value replaces `app-db` for the registered frame. For events that need to return both a new DB **and** other effects (HTTP requests, navigation, child dispatches), the macro is `reg-event-fx` and the handler returns a map `{:db ... :fx [...]}`. The counter doesn't need that yet.

### Subscriptions

One subscription, `[:count]`, reads `(:count db)`. Views deref it with `@(subscribe [:count])`. Under re-frame2's value-equal recompute suppression (rf2-719e), the sub re-runs only when its inputs change; if the new return value is `=` to the previous one, downstream consumers don't re-render. That suppression is automatic; nothing to configure.

### Views

`reg-view` is a macro that **registers a view** under `(keyword *ns* sym)` — here, `:your-app.core/counter-buttons` and `:your-app.core/root-view`. It also defs a regular CLJS var with the same name so the hiccup `[counter-buttons]` reference works.

Inside the body, two locals are **auto-injected** by the macro:

- `dispatch` — bound to the current frame's `dispatch` fn. Use it like `(dispatch [:counter/inc])`.
- `subscribe` — bound to the current frame's `subscribe` fn. Use it like `@(subscribe [:count])`.

This is what makes registered views frame-aware without you threading the frame through every component. The macro resolves both at render time against whatever frame is in scope (the default frame, here; `frame-provider` lets multi-frame apps swap it for a subtree).

The result is regular Reagent hiccup. Reagent renders, the React 18 root commits, the DOM updates.

### Mount

`defonce` guards `react-root` against hot-reload. See `entry-namespace.md` for why.

`run`'s three lines (in this order):

1. `(rf/init! reagent-adapter/adapter)` — install the Reagent substrate adapter.
2. `(rf/dispatch-sync [:counter/initialise])` — seed `app-db` to `{:count 0}` before first render. `dispatch-sync` drains the event synchronously; the `app-db` is committed before `run` returns.
3. `(rdc/render react-root [root-view])` — mount.

## Verifying it works

```
shadow-cljs watch app
```

Wait for the compile to land. The terminal prints something like `[:app] Build completed.`

Visit `http://localhost:8020/` (or whatever `:http-port` you set in `shadow-cljs.edn`).

You should see:

- The heading `re-frame2 counter`
- The number `0`
- A `-` button on the left, `+` button on the right

Click `+` — the number becomes `1`. Click `-` — back to `0`. Refresh the page — back to `0` (state lives in app-db, which resets on full reload).

If you see a blank page, open the browser console. Most failures land there with a clear error:
- `Cannot read property 'getElementById' of undefined` — script ran before DOM was ready; check `index.html` loads `main.js` at the *bottom* of `<body>`.
- `Could not find frame :rf/default` — `rf/init!` didn't run before render. Check `run` is the `:init-fn` shadow-cljs is calling.
- `No subscription handler registered for: :count` — registrations didn't run. If you split into multiple namespaces, make sure `core.cljs` `:require`s them so they load.

## What to do next

**Setup is done.** From here, **switch skills**:

- **Writing more code (events, subs, machines, schemas, frames, fx, flows, routing, SSR)** — load the **`re-frame2`** skill. It covers the API surface in modular files; you can load just the pieces relevant to what you're building.
- **Inspecting the running app live from the REPL** — install the **`re-frame-pair2`** skill. It attaches over nREPL, lets you walk app-db, dispatch from the REPL, hot-swap handlers, time-travel through epoch history. nREPL is a remote-evaluation surface: keep it dev-only and bound to localhost (shadow-cljs's default). Never expose the nREPL port on `0.0.0.0` or a shared / public interface — anything that can connect can evaluate arbitrary code in the running JVM.

Both skills are independent of this one and can be loaded individually.

If you want worked examples of more substantial re-frame2 apps before you keep building, the repo's `examples/reagent/` directory has worked apps for: TodoMVC, the seven 7GUIs tasks, login with state machines + managed HTTP, routing, SSR, the nine-states pattern, realworld. Browse them via `SKILL-REDIRECT.md` → Examples directory.
