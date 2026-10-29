(ns re-frame.fresco.reincarnation-remount-dom-cljs-test
  "A VIEW THAT READS NOTHING, ACROSS A SAME-ID FRAME RECREATION — dead
  until the tree is remounted.

  The frame context carries the frame's KEYWORD, its address and not its
  incarnation, so destroying a frame and making another under the same id
  changes nothing React can see. A boundary that reads a subscription is
  repaired by its cell (`reincarnation_paint_dom_cljs_test`). A boundary
  that reads none has no cell, and its props compare `=`, so React's memo
  skips it on every parent re-render and its intents stay bound to the
  destroyed incarnation: each click refuses with `:rf.error/frame-destroyed`
  and the successor's app-db never moves. That is the documented behaviour
  (fresco/spec/authoring-contract.md, The incarnation rule), and so is its
  remedy — remount the tree — which the last half of the row exercises.

  The row re-renders the root with the SAME tree after the recreation, so
  the dead click is the memo bailing out rather than nothing having
  rendered at all.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` it degrades to a stated skip."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.test-support :as rf.test-support]))

(def ^:private frame-id ::account)

;; Registered ABOVE `use-fixtures`: the reset fixture captures its baseline
;; when the `use-fixtures` form is evaluated.
(rf/reg-event ::seed (fn [_ _] {:db {:clicks 0}}))
(rf/reg-event ::clicked (fn [{:keys [db]} _] {:db (update db :clicks (fnil inc 0))}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (rf.fresco.impl.collector/reset-runtime!)
                      (rf.error-emit/clear-error-listeners!))}))

(rf.fresco/defview toolbar
  "A static control: an intent and no read, the shape of a toolbar or nav."
  [_]
  [:button.save {:on-click [::clicked]} "Save"])

(defn- tree [] [rf.fresco/frame-provider {:frame frame-id} [toolbar {}]])

(defn- seat! []
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [::seed]))
  (rf.frame/frame-incarnation-token frame-id))

(defn- clicks [] (:clicks (rf/app-db-value frame-id)))

(defn- click!
  "Click the button and answer the `:rf.error/frame-destroyed` records the
  click fanned — the only observable separating a refusal from a write
  that went somewhere else."
  [container]
  (let [seen (atom [])
        k    (keyword "reincarnation-remount" (name (gensym "refusal")))]
    (rf.error-emit/register-error-listener!
      k (fn [r] (when (= :rf.error/frame-destroyed (:rf.error/id r)) (swap! seen conj r))))
    (try
      (.click (.querySelector container "button.save"))
      (rf.fresco.impl.mount/settle!)
      @seen
      (finally (rf.error-emit/unregister-error-listener! k)))))

(deftest a-read-free-view-keeps-the-dead-frames-intents-until-the-tree-remounts
  (if-not (rf.fresco.impl.mount/browser?)
    (is true "a mounted-boundary claim needs a real React DOM — :node-test has no document")
    (let [_         (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
          container (rf.fresco.impl.mount/fresh-container!)
          root      (rf.fresco/client-root)]
      (try
        (let [first-token (seat!)]
          (rf.fresco/render! root (tree) container)
          (is (= [] (click! container)) "premise: the predecessor takes a click")
          (is (= 1 (clicks)) "premise: and its app-db moves")

          (rf/destroy-frame! frame-id)
          (is (not (identical? first-token (seat!)))
              "premise: the successor is a new incarnation under the same id")
          (rf.fresco/render! root (tree) container)

          (let [refusals (click! container)]
            (is (= 1 (count refusals))
                "re-rendered with the same tree, the memo skipped the view, so
                 the click still fires the predecessor's intent and refuses")
            (is (= frame-id (:frame (first refusals))) "attributed to the frame"))
          (is (= 0 (clicks)) "and the successor's app-db did not move")

          (rf.fresco/unmount! root)
          (rf.fresco/render! root (tree) container)
          (is (= [] (click! container)) "remounted, the view lowers against the successor")
          (is (= 1 (clicks)) "and the click lands in the successor's app-db"))
        (finally
          (rf.fresco/unmount! root)
          (when-some [p (.-parentNode container)] (.removeChild p container)))))))
