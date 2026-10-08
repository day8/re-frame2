(ns day8.re-frame2-xray.palette.dispatch-routing-cljs-test
  "Click-time frame routing of the Xray command palette.

  Handlers fire after render, when React has popped the frame context, so
  an unscoped `rf/dispatch` would not reach `:rf/xray` (in the browser it
  raises `:rf.error/no-frame-context`). The palette threads the
  frame-bound dispatcher its boundary captured with
  `(:dispatch (rf/capture-frame))`; `test-helpers.palette-tree` defaults to
  that same door.

  Each row plucks a handler off the rendered tree and fires it OUTSIDE any
  `with-frame`. The fixture leaves `:rf/default` as an ambient scope, so a
  mis-routed dispatch here lands in `:rf/default`'s db, which each row
  checks stays clean."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.palette-tree :as palette-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :runtime
     :async?     true
     :post-reset (fn []
                   (registry/register-xray-handlers!)
                   (rf/make-frame {:id :rf/xray}))}))

(defn- handler-of
  "The `attr` handler on the node `testid` of the open palette, after
  `events` are dispatched into `:rf/xray`."
  [testid attr & events]
  (let [tree (rf/with-frame :rf/xray
               (rf/dispatch-sync [:rf.xray/palette-open])
               (run! rf/dispatch-sync events)
               (palette-tree/palette-tree))]
    ;; Walk in scope: expanding a tree re-invokes nested component fns.
    (get-in (rf/with-frame :rf/xray (rf.test-helpers/find-by-testid tree testid))
            [1 attr])))

(defn- fake-event [extra]
  (clj->js (merge {:preventDefault (fn []) :stopPropagation (fn [])} extra)))

(defn- await-routed
  "Await `pred` on `:rf/xray`'s db, then check `slot` never landed in
  `:rf/default`'s db."
  [done pred slot]
  (-> (rf.test-support/poll-until #(pred (rf/app-db-value :rf/xray))
                                  {:label (str slot) :timeout-ms 1000})
      (.then (fn [_] (is (nil? (get (rf/app-db-value :rf/default) slot)))))
      (.catch (fn [e] (is false (.-message e)) nil))
      (.then (fn [_] (done)))))

(defn- key-event [k]
  (fake-event {:key k :ctrlKey false :metaKey false}))

(deftest backdrop-click-closes-palette-from-default-frame-context
  ((handler-of "rf-xray-palette-backdrop" :on-click) (fake-event {}))
  (async done (await-routed done #(not (:palette-open? %)) :palette-open?)))

(deftest esc-keydown-closes-palette-from-default-frame-context
  ((handler-of "rf-xray-palette-input" :on-key-down) (key-event "Escape"))
  (async done (await-routed done #(not (:palette-open? %)) :palette-open?)))

(deftest arrow-down-keydown-moves-cursor-from-default-frame-context
  ((handler-of "rf-xray-palette-input" :on-key-down) (key-event "ArrowDown"))
  (async done (await-routed done #(pos? (:palette-cursor %)) :palette-cursor)))

(deftest arrow-up-keydown-routes-to-xray-frame
  (let [handler (handler-of "rf-xray-palette-input" :on-key-down
                            [:rf.xray/palette-cursor-set 2])]
    (is (= 2 (:palette-cursor (rf/app-db-value :rf/xray))))
    (handler (key-event "ArrowUp"))
    (async done (await-routed done #(< (:palette-cursor %) 2) :palette-cursor))))

(deftest input-on-change-updates-query-from-default-frame-context
  ((handler-of "rf-xray-palette-input" :on-change)
   (fake-event {:target #js {:value "search"}}))
  (async done (await-routed done #(= "search" (:palette-query %)) :palette-query)))
