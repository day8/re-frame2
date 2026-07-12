# 01 — Getting started

## Install

```clojure
;; deps.edn
{:deps {day8/re-frame2    {…}
        day8/re-frame2-ui {…}}}
```

`package.json` carries `react` and `react-dom` (19.2.4+) — nothing else. No Reagent, no
UIx, no Helix.

## The whole app

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui :refer [defview sub]]))

;; ---- dataflow: plain re-frame2 ------------------------------------------

(rf/reg-event :app/init  (fn [_ _] {:db {:count 0}}))
(rf/reg-event :count/inc (fn [{:keys [db]} _] {:db (update db :count inc)}))
(rf/reg-sub   :count     (fn [db _] (:count db)))

;; ---- view -----------------------------------------------------------------

(defview counter []
  [:div
   [:span "Count: " (sub [:count])]
   [:button {:on-click [:count/inc]} "+"]])

;; ---- mount ----------------------------------------------------------------

(defn ^:export run []
  (rf/init! ui/adapter)
  (ui/mount [ui/frame-root {:id :app :initial-events [[:app/init]]}
              [counter]]
            (js/document.getElementById "root")))
```

Three things carry the whole model:

1. **`(sub [:count])` is the value.** Not a ref — nothing to deref. When `[:count]`
   changes, this view re-renders. That is the entire reactive contract.
2. **`{:on-click [:count/inc]}` is data.** No closure, no `dispatch` in scope. The vector
   dispatches to the frame this view is mounted under, when the click happens. You can
   read what the button does without running anything — and so can Xray.
3. **`frame-root` stands up the frame — before rendering.** `mount` runs a preflight
   that creates `:app` if absent and drains `:initial-events` exactly once; only then
   does React render, with `frame-root` scoping the live frame. That ordering is why an
   abandoned render, a StrictMode replay, or a hot reload can never double-seed app-db.
   On reload the preflight finds the frame live and *reuses* it — your state survives
   edits. No provider → `sub` raises `:rf.error/no-frame-context`; the runtime never
   guesses.

> **Stage note.** The compiled template, the props model, and the mount grammar above
> are shipped (Stage 1, on main today); the live reactive loop — `sub` re-rendering the
> view, `frame-root`'s runtime ENSURE, the `ui/adapter` you hand `rf/init!` — *(lands
> S2 — reactive subs)*. Same source either way: what you write today is the contract.

One more default worth knowing: this single-root page needed no root identity — the
root id derives from the mounted view. When a page mounts the same view twice, or you
need stable identity for hydration, you author it ([08](08-ssr.md)).

## Prove it without a browser

The rendered tree is data and the button's handler is a vector, so your first test
needs no DOM, no browser, no flake — it runs on the JVM in your watch loop:

```clojure
(ns my.app-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ui.test :as ui.test]
            [my.app :as app]))

(deftest counter-carries-intent
  (let [frame (ui.test/frame {:app-db {:count 3}})
        tree  (ui.test/render [app/counter] {:frame frame})]
    (is (= [:count/inc] (-> tree (ui.test/find :button) ui.test/attrs :on-click)))
    (is (= "+"          (-> tree (ui.test/find :button) ui.test/text)))))
```

Two ground rules, both cheap: the events/subs a view touches must be `.cljc` (they run
on the JVM here — standard re-frame discipline anyway), and attribute reads go through
`ui.test/attrs`, never keyword lookup on the node. The full testing story is
[09](09-testing.md). *(The test harness core is Stage 1; a Tier-1 render that crosses a
`sub` site — as this one does — lands S2. A subless view tests today.)*

## Hot reload

```clojure
(defn ^:dev/after-load reload! [] (run))
```

`mount` is idempotent per root; views re-register by name; the frame is reused; edited
views repaint against live state. This is the default development loop.

## Where next

Real views with props and lists → [02](02-views.md). Porting an app? Read
[04 — Events](04-events.md) first: data handlers are the biggest habit change and the
biggest payoff. Want the whole model on one page — state shape, tiles, tests? The
worked app: [10](10-worked-app.md).
