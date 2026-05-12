(ns day8.re-frame2-causa.panels.event-detail-cljs-test
  "Tests for the Causa event-detail hero panel (Phase 2, rf2-op3bz).

  ## Three contracts under test

  1. **Cascade list when nothing is selected.** With trace events in
     the buffer but no selected dispatch-id, the panel's
     `:rf.causa/event-detail` composite sub returns every cascade and
     the view renders the empty-state list. Clicking a cascade row
     fires `:rf.causa/select-dispatch-id`.

  2. **Cascade detail when something is selected.** With a selection
     set via `:rf.causa/select-dispatch-id`, the composite sub returns
     the matching cascade record and the view renders the six-domino
     rows.

  3. **Clear selection.** Dispatching `:rf.causa/clear-selected-
     dispatch-id` empties the selection and the view returns to the
     cascade list.

  ## Pure-data scope

  The view is pure hiccup; the tests assert against the hiccup tree
  rather than booting a substrate adapter / mounting to the DOM. This
  keeps the suite fast and host-portable per the same node-test
  rationale as `preload_cljs_test.cljs`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- fixture trace stream ------------------------------------------------

(defn- cascade-evs
  "Produce a representative one-cascade event stream — same shape as
  re-frame.trace.projection's own tests. `dispatch-id` rides on every
  event's `:tags :dispatch-id` per rf2-g6ih4."
  [dispatch-id event-vec id-base]
  [{:id (+ id-base 1) :op-type :event    :operation :event/dispatched
    :tags {:dispatch-id dispatch-id :event event-vec}}
   {:id (+ id-base 2) :op-type :event    :operation :event
    :tags {:dispatch-id dispatch-id :phase :run-start}}
   {:id (+ id-base 3) :op-type :event    :operation :event
    :tags {:dispatch-id dispatch-id :phase :run-end}}
   {:id (+ id-base 4) :op-type :event    :operation :event/do-fx
    :tags {:dispatch-id dispatch-id}}
   {:id (+ id-base 5) :op-type :fx       :operation :rf.fx/handled
    :tags {:dispatch-id dispatch-id :fx-id :db}}
   {:id (+ id-base 6) :op-type :fx       :operation :rf.fx/handled
    :tags {:dispatch-id dispatch-id :fx-id :dispatch}}
   {:id (+ id-base 7) :op-type :sub/run  :operation :sub/run
    :tags {:dispatch-id dispatch-id :sub-id :sub/foo}}
   {:id (+ id-base 8) :op-type :view     :operation :view/render
    :tags {:dispatch-id dispatch-id :render-key [:app/root nil]}}])

(defn- seed-buffer!
  "Wire the trace collector (preload-style) and push the supplied
  events through it so the Causa buffer matches the production
  delivery path."
  [evs]
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (doseq [ev evs]
    (trace-bus/collect-trace! ev)))

(defn- expand-fn-component
  "When `node` is a hiccup-style `[fn-component args...]` vector
  (first element is a function rather than a keyword/string), invoke
  the fn with the rest of the args so the test can recurse into the
  rendered sub-tree. Otherwise return the node unchanged."
  [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- hiccup-seq
  "Walk a hiccup tree and emit every node (vectors only). Vectors
  whose first element is a function are invoked first so the walker
  descends into the sub-tree they would render to."
  [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree))
       (map expand-fn-component)))

(defn- find-by-testid
  "Find the first node in a hiccup tree whose attrs map has the given
  `:data-testid`. Returns nil when no such node exists."
  [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

;; ---- (1) empty state: cascade list --------------------------------------

(deftest empty-state-renders-cascade-list
  (testing "with cascades in the buffer but no selection, the panel
            renders the cascade list"
    (seed-buffer! (concat (cascade-evs 100 [:user/login {:id 42}] 0)
                          (cascade-evs 200 [:user/logout] 100)))
    (rf/with-frame :rf/causa
      (let [tree (event-detail/event-detail-view)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-empty"))
            "empty-state container present")
        (is (some? (find-by-testid tree "rf-causa-cascade-list"))
            "cascade list container present")
        (is (some? (find-by-testid tree "rf-causa-cascade-row-100"))
            "cascade 100 has a clickable row")
        (is (some? (find-by-testid tree "rf-causa-cascade-row-200"))
            "cascade 200 has a clickable row")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail container absent when no selection")))))

(deftest empty-state-renders-with-no-cascades
  (testing "with an empty buffer + no selection the panel still
            renders the empty-state container"
    (seed-buffer! [])
    (rf/with-frame :rf/causa
      (let [tree (event-detail/event-detail-view)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-empty"))
            "empty-state container present even with an empty buffer")
        (is (nil? (find-by-testid tree "rf-causa-cascade-list"))
            "no cascade list when there are zero cascades")))))

;; ---- (2) cascade-detail when a dispatch-id is selected ------------------

(deftest cascade-detail-renders-six-dominoes
  (testing "after selecting a dispatch-id the panel switches to the
            cascade-detail layout"
    (seed-buffer! (cascade-evs 100 [:user/login {:id 42}] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (let [tree (event-detail/event-detail-view)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail container present")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-empty"))
            "empty-state container absent once a selection is set")
        (is (some? (find-by-testid tree "rf-causa-event-detail-clear"))
            "clear button is rendered alongside the cascade")))))

(deftest selecting-non-existent-dispatch-id-shows-orphaned-state
  (testing "selecting a dispatch-id that's not in the buffer surfaces
            the orphaned-selection branch"
    (seed-buffer! (cascade-evs 100 [:user/login {:id 42}] 0))
    (rf/with-frame :rf/causa
      ;; 999 is not in the buffer — the composite sub returns
      ;; :selected-cascade=nil; the view should surface the
      ;; orphaned-state branch rather than the cascade rows.
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 999])
      (let [tree (event-detail/event-detail-view)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-orphaned"))
            "orphaned-selection container present")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail absent when the id has no matching cascade")))))

;; ---- (3) the select / clear events -------------------------------------

(deftest select-dispatch-id-event-writes-to-causa-frame
  (testing ":rf.causa/select-dispatch-id sets :selected-dispatch-id on
            the :rf/causa frame's db (not the host's :rf/default)"
    (seed-buffer! [])
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 42]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= 42 (:selected-dispatch-id causa-db))
          "selection lands on the Causa frame")
      (is (nil? (:selected-dispatch-id default-db))
          "selection did NOT leak into :rf/default"))))

(deftest clear-selected-dispatch-id-returns-to-empty-state
  (testing "after select + clear the panel renders the empty state"
    (seed-buffer! (cascade-evs 100 [:user/login {:id 42}] 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 100])
      (rf/dispatch-sync [:rf.causa/clear-selected-dispatch-id])
      (let [tree (event-detail/event-detail-view)]
        (is (some? (find-by-testid tree "rf-causa-event-detail-empty"))
            "empty-state container present after clear")
        (is (nil? (find-by-testid tree "rf-causa-event-detail-cascade"))
            "cascade-detail container absent after clear")))))

;; ---- (4) defaults / wiring ----------------------------------------------

(deftest default-panel-id-is-event-detail
  (testing "registry/default-panel-id is :event-detail per §10 Lock 7"
    (is (= :event-detail registry/default-panel-id))))

(deftest selected-panel-sub-defaults-to-event-detail
  (testing ":rf.causa/selected-panel returns :event-detail before any
            :rf.causa/select-panel has fired"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (is (= :event-detail
             @(rf/subscribe [:rf.causa/selected-panel]))))))

(deftest selected-panel-sub-tracks-select-panel-event
  (testing "dispatching :rf.causa/select-panel updates the sub"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-panel :app-db])
      (is (= :app-db @(rf/subscribe [:rf.causa/selected-panel]))))))
