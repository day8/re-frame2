(ns day8.re-frame2-xray.panels.managed-fx-subs-cljs-test
  "Tests for `:rf.xray/managed-fx-for-focused-event` composed with its
  renderer, and for the record panel's section-disclosure slot. Trace events
  are pushed through the production `trace-collector/seed-trace-for-test!`
  path and read back via `subscribe`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.panels :as panels]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.managed-fx-helpers :as h]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (config/set-egress-profile! config/default-egress-profile)
                   (config/reset-suppressed-count!))}))

(defn- seed-buffer! [evs]
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (doseq [ev evs]
    (trace-collector/seed-trace-for-test! ev)))

;; ---- section disclosure state --------------------------------------------

(def ^:private rec-key
  "The record key `managed-fx-helpers/record-key` composes for an HTTP record,
  written out so a drift in the composer shows up here."
  ":http-99-:rf.http/managed")

(defn- expanded-map []
  @(rf/subscribe [:rf.xray/managed-fx-expanded-sections]))

(deftest toggle-inverts-the-state-the-operator-can-see
  (testing "the first click inverts what is RENDERED: a reducer flipping a nil
            override from `false` would be a silent no-op on the two sections
            that default OPEN"
    (seed-buffer! [])
    (rf/with-frame :rf/xray
      ;; default-CLOSED section: first click opens, second shuts
      (rf/dispatch-sync [:rf.xray/managed-fx-toggle-section rec-key :request])
      (is (true? (h/resolve-expanded? (expanded-map) rec-key :request)))
      (rf/dispatch-sync [:rf.xray/managed-fx-toggle-section rec-key :request])
      (is (false? (h/resolve-expanded? (expanded-map) rec-key :request)))
      ;; default-OPEN section: first click SHUTS it
      (rf/dispatch-sync [:rf.xray/managed-fx-toggle-section rec-key :wire])
      (is (false? (h/resolve-expanded? (expanded-map) rec-key :wire)))
      (rf/dispatch-sync [:rf.xray/managed-fx-toggle-section rec-key :wire])
      (is (true? (h/resolve-expanded? (expanded-map) rec-key :wire))))))

;; ---- the mount's own composition -----------------------------------------
;;
;; `panels/managed-fx-list-tree` is the one line where the composite sub's
;; value meets the renderer; `ManagedFxList` is a Fresco boundary the node lane
;; cannot call for hiccup, and the template tests hand `records-list` a vector.

(defn- cascade-evs-two-managed-fx
  "One cascade carrying TWO managed-fx invocations: the composite map has THREE
  entries, so a renderer handed the map instead of the vector cannot answer 2."
  [dispatch-id id-base]
  [{:id (+ id-base 1) :op-type :rf.event :operation :rf.event/dispatched
    :tags {:rf.trace/dispatch-id dispatch-id :rf.event/v [:user/load]}}
   {:id (+ id-base 2) :op-type :rf.fx :operation :rf.fx/do-fx
    :tags {:rf.trace/dispatch-id dispatch-id}}
   {:id (+ id-base 3) :op-type :rf.fx :operation :rf.fx/handled
    :tags {:rf.trace/dispatch-id dispatch-id
           :rf.fx/id :rf.http/managed
           :rf.fx/args {:request    {:method :get :url "/api/users/1"}
                        :request-id :req-a
                        :on-success [:user/loaded]}}}
   {:id (+ id-base 4) :op-type :rf.fx :operation :rf.fx/handled
    :tags {:rf.trace/dispatch-id dispatch-id
           :rf.fx/id :rf.http/managed
           :rf.fx/args {:request    {:method :get :url "/api/users/2"}
                        :request-id :req-b
                        :on-success [:user/loaded]}}}])

(defn- tree-nodes
  "Every node in a hiccup tree, descending into map values too, walked
  structurally rather than through `rf.test-helpers/expand-tree`."
  [node]
  (cond
    (vector? node) (cons node (mapcat tree-nodes node))
    (seq? node)    (cons node (mapcat tree-nodes node))
    (map? node)    (cons node (mapcat tree-nodes (vals node)))
    :else          [node]))

(defn- record-panel-testids
  "Every record-panel `data-testid` in a rendered tree. One per record."
  [tree]
  (->> (tree-nodes tree)
       (keep (fn [n]
               (when (and (vector? n) (map? (second n)))
                 (:data-testid (second n)))))
       (filter #(string/starts-with? % "rf-xray-managed-fx-record-"))))

(deftest managed-fx-list-renders-one-panel-per-record
  (testing "the composite sub answers `{:dispatch-id … :frame … :records […]}`
            and `records-list` takes the RECORDS VECTOR; handed the whole map it
            would walk map entries and throw before painting, with no error
            boundary above this render path"
    (seed-buffer! (cascade-evs-two-managed-fx 600 0))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 600 :rf/default])
      (let [tree (panels/managed-fx-list-tree
                   @(rf/subscribe [:rf.xray/managed-fx-for-focused-event])
                   @(rf/subscribe [:rf.xray/managed-fx-expanded-sections])
                   (:dispatch (rf/capture-frame)))]
        (is (= 2 (count (record-panel-testids tree)))
            "one record panel per RECORD, not one per entry of the composite map")))))
