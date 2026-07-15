# 01 — Getting started

In this chapter you install re-frame2 UI, write a complete counter, and drive the
reactive loop — from a button, a REPL, or a test. Later chapters unpack each face:
views, state, events, frames, then a real dashboard.

## Install

```clojure
;; guide:no-fixture — install stub, elided coordinates
;; deps.edn
{:deps {day8/re-frame2    {…}
        day8/re-frame2-ui {…}}}
```

`package.json` carries `react` and `react-dom` — nothing else. No Reagent, no UIx, no
Helix.

The browser/runtime must provide the standard JavaScript `WeakRef` constructor (all
current evergreen browsers and supported modern Node runtimes do). re-frame.ui probes
it once, before admitting the first Root or attaching ViewCell ownership. An older host
fails loud with `:rf.error/ui-platform-incompatible`, data
`{:platform :javascript :capability :js/WeakRef}`, and recovery
`:use-a-weakref-capable-javascript-runtime`; it never falls back to a leaking strong
registry. `FinalizationRegistry` is useful but optional — synchronous weak-registry
compaction covers its absence.

The compiler needs one whole-build view of your app. For Shadow CLJS, set these once
(not once per build):

```clojure
;; shadow-cljs.edn
{:cache-blockers #{re-frame.ui}
 :build-defaults
 {:build-hooks [(re-frame.ui.compiler.build-hook/hook)]}
 ;; :builds ...
 }
```

Forget either and the failure is loud: a misconfigured dev bundle refuses to hydrate or
debug against a false identity. Evaluating a view in the REPL repaints it immediately;
the *build digest* advances when you save and the next watch pass completes. Why that
matters is [12 — How it works](12-how-it-works.md). None of this machinery reaches
advanced production output.

A minimal project is four files: `deps.edn` and `shadow-cljs.edn` at the root, your app
namespace under `src/`, and an `index.html` with a `<div id="root">`.

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

Three facts carry the whole model:

1. **`(sub [:count])` is the value.** Not a ref — nothing to deref. When `[:count]`
   changes, this view re-renders. That is the entire reactive contract.
2. **`{:on-click [:count/inc]}` is data.** No closure, no `dispatch` in scope. The
   vector dispatches to the frame this view is mounted under when the click happens.
   You can read what the button does without running anything — and so can Xray.
3. **`frame-root` stands up the frame before React renders.** `mount` creates `:app`
   if needed, drains `:initial-events` once, then scopes the live frame. An abandoned
   render, StrictMode replay, or hot reload cannot double-seed app-db. On reload the
   preflight finds the frame live and reuses it — your state survives edits.

!!! tip "You are the click"
    Until committed DOM handler wiring lands (S3), drive the loop yourself from a REPL
    or AI pair against the running frame:

    ```clojure
    (rf/dispatch <frame> [:count/inc])
    ```

    Watch the view repaint. That is the deepest fact about this library, met early:
    the UI is an event stream, and a button is only one of the emitters. The same
    shape works in tests: `(ui.test/dispatch! frame [:count/inc])`.

One more default: this single-root page needs no root identity — the id derives from
the mounted view. Author one when a page mounts the same view twice, or for hydration
([11](11-ssr.md)).

## A first test

The rendered tree is data and the button's handler is a vector, so your first test
needs no DOM and no browser — it runs on the JVM in your watch loop.

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

Two ground rules:

- Events and subs a view touches should be `.cljc` (they run on the JVM here).
- Attribute reads go through `ui.test/attrs`, never keyword lookup on the node.

There is no test-only frame constructor: `rf/make-frame` with `:initial-events` is how
every frame begins. `[:rf/set-db {…}]` seeds a specific starting state when a test
wants one. Full testing story: [08](08-testing.md).

## Hot reload

```clojure
(defn ^:dev/after-load reload! [] (run))
```

`mount` is idempotent per root; views re-register by name; the frame is reused; edited
views repaint against live state. This is the default development loop.

For deliberate shutdown, retain the Root returned by `mount` and call
`(ui/unmount! root)`. The Root's id, container, and identifier prefix stay claimed
until React teardown actually settles. That is normally synchronous; a deferred
teardown releases at its settlement microtask. If host cleanup throws, re-frame.ui
force-releases that exact incarnation's subscriptions but quarantines the unproven
container claim, so mount into a fresh container rather than racing React's aborted
cleanup. A first mount whose render throws follows the same transaction: its claim is
fenced through cleanup and, on a normal cleanup return, frees on the next FIFO
microtask before a retry is admitted.
