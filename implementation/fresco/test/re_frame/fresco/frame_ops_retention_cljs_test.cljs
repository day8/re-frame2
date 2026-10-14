(ns re-frame.fresco.frame-ops-retention-cljs-test
  "**A DESTROYED FRAME DROPS ITS `!frame-ops` ROW**.

  `impl.frames/!frame-ops` holds one row per frame — the `capture-frame`
  bundle and the ambient dispatch closure over it, pinned to the
  incarnation that minted them. [[re-frame.fresco.impl.frames/frame-row]]'s
  replace branch evicts a row on the SUCCESSOR's first lookup under the
  same key. That works for a client id, which is reused across
  incarnations, and it is inert for an id that can never recur.

  `server/render` mints exactly such an id — `fresh-frame-id`'s
  `(gensym \"request-\")` — so with that eviction alone every request
  served would leave a row that nothing would ever look up again, each
  retaining that request's captured bundle: linear growth in requests
  served, in a process built to be long-lived.

  ## What the row measures

  The row belongs to an INCARNATION, so the moment that incarnation is
  destroyed the row is unreadable by anything and the eviction is
  `destroy-frame!`'s to make. `server/render` destroys its per-request
  frame once the request is served, so the row reads the eviction on the
  path where a missed one is unbounded rather than merely untidy: one
  request must leave no `fresco.ssr` row behind.

  Its control is `runtime/body-runs`. `!frame-ops` is populated by RENDER
  — `run-once` binds `(frame-dispatch frame-kw)` around every boundary
  body — so a `render` that reached no boundary at all would leave the
  table empty for the wrong reason, and reds instead of passing.

  ## Runtime

  `-cljs-test`, not `-dom-cljs-test`: the row is a `react-dom/server`
  render and a direct table read, and touches no document."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.frames :as rf.fresco.impl.frames]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.fresco.server :as rf.fresco.server]
            [re-frame.test-support :as rf.test-support]))

;; Registered ABOVE `use-fixtures` for the sibling suites' reason: the
;; reset fixture captures its source-store baseline when the
;; `use-fixtures` form is EVALUATED, so a registration written below it
;; is erased before the first row runs.

(rf/reg-sub ::label (fn [db _] (:label db)))

(rf.fresco/defview page
  "One subscription read, so the render acquires a frame-keyed cell and
  the boundary body actually runs."
  [_]
  [:div.page [:p.value (rf.fresco/sub [::label])]])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

(def ^:private snapshot {:label "alpha"})

(defn- request
  "One request's options — the smallest set `server/render` requires."
  []
  {:hiccup   [page {}]
   :snapshot snapshot
   :payload  [:label]})

(defn- ssr-rows
  "The `!frame-ops` keys minted by a per-request frame.

  `fresh-frame-id` spells its gensym into the `fresco.ssr` namespace, so
  this counts exactly the rows a request left behind and is blind to
  whatever else the bundle's other suites have in the table."
  []
  (filter #(= "fresco.ssr" (namespace %)) (keys @rf.fresco.impl.frames/!frame-ops)))

;; ---------------------------------------------------------------------------
;; The case that would be unbounded: one row per REQUEST
;; ---------------------------------------------------------------------------

(deftest a-server-render-leaves-no-row-behind
  (rf.fresco.test.runtime/reset-body-runs!)
  (rf.fresco.server/render (request))
  (is (pos? (rf.fresco.test.runtime/body-runs))
      (str "control: a `render` that reached no boundary would leave the "
           "table empty for the wrong reason; ran "
           (rf.fresco.test.runtime/body-runs) " bodies"))
  (is (empty? (ssr-rows))
      "a request leaves no per-request row, so the table cannot grow with
       requests served"))
