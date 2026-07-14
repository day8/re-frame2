# 01 — Getting started

## Install

```clojure
;; guide:no-fixture — install stub, elided coordinates
;; deps.edn
{:deps {day8/re-frame2    {…}
        day8/re-frame2-ui {…}}}
```

`package.json` carries `react` and `react-dom` — nothing else. No Reagent, no UIx, no
Helix. (The published artifact's peer floor is planned as React 19.2.4+; the reference
implementation currently builds against a 19.2.0 pin, and the floor takes effect when
the artifact's peer contract ships with it.)

The compiler owns one whole-build view digest. For Shadow 3.4.10, configure its two
load-bearing settings once (not once per build):

```clojure
;; shadow-cljs.edn
{:cache-blockers #{re-frame.ui}
 :build-defaults
 {:build-hooks [(re-frame.ui.compiler.build-hook/hook)]}
 ;; :builds ...
 }
```

Both settings serve one contract: the compiler keeps a single, truthful, whole-build
picture of your views (the *build digest*), across daemon restarts, cached builds, and
hot reloads. Forget one and the failure is loud, not subtle — a misconfigured dev
bundle refuses to hydrate or debug against a false identity rather than doing so
silently. One habit worth knowing early: evaluating a view directly in the REPL
repaints it, but the digest only advances when you save and the next watch pass
completes. What the digest is and why the cache needs blocking is
[11 — How it works](11-how-it-works.md); none of this machinery reaches advanced
production output.

A minimal project is four files: `deps.edn` and `shadow-cljs.edn` at the root
(configured above), your app namespace under `src/`, and an `index.html` with a
`<div id="root">`. Nothing else is load-bearing.

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

> **Stage note.** Everything above is on main today except the button itself —
> committed dispatch from compiled handler sites *(lands S3 — committed handlers)*.
> Until then, **you are the click**: the live loop shipped with S2, so drive it
> yourself — dispatch from your REPL or your AI pair against the running frame, or in
> a test with `(ui.test/dispatch! frame [:count/inc])` — and watch the view repaint.
> That's the deepest fact about this library, met early: the UI is an event stream,
> and a button is just one of the emitters.

One more default worth knowing: this single-root page needed no root identity — the
root id derives from the mounted view. When a page mounts the same view twice, or you
need stable identity for hydration, you author it ([08](08-ssr.md)).

## Prove it without a browser

The rendered tree is data and the button's handler is a vector, so your first test
needs no DOM, no browser, no flake — it runs on the JVM in your watch loop:

```clojure
(ns my.app-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.ui.test :as ui.test]
            [my.app :as app]))

(deftest counter-carries-intent
  (let [frame (rf/make-frame {:initial-events [[:rf/set-db {:count 3}]]})
        tree  (ui.test/render [app/counter] {:frame frame})]
    (is (= [:count/inc] (-> tree (ui.test/find :button) ui.test/attrs :on-click)))
    (is (= "+"          (-> tree (ui.test/find :button) ui.test/text)))))
```

Two ground rules, both cheap: the events/subs a view touches must be `.cljc` (they run
on the JVM here — standard re-frame discipline anyway), and attribute reads go through
`ui.test/attrs`, never keyword lookup on the node. And notice there is no test-only
frame constructor: `rf/make-frame` with `:initial-events` is how every frame begins,
and `[:rf/set-db {…}]` is the framework's standard seeding event for when a test wants
a specific starting state. The full testing story is
[09](09-testing.md). This test runs on main today, `sub` site included — the Tier-1
harness shipped at Stage 1, and the snapshot path that lets a headless render resolve
subscriptions arrived with the S2 reactive core.

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
