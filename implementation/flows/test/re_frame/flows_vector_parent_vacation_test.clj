(ns re-frame.flows-vector-parent-vacation-test
  "rf2-vx1ps6 — a flow output written under a VECTOR parent is vacated
  symmetrically with its write.

  Integer path segments are valid (`re-frame.path/segment?` admits safe
  integers; `reg-flow` output-path validation accepts them). `evaluate-flow!`
  writes a vector-index output through `assoc-in`, which DOES target the vector
  index. But vacation (`clear-flow`, and the re-registration output-path move)
  routed through `dissoc-in-safe`, which returned the db UNCHANGED for any
  non-map parent — so a flow whose `:output-path` leaf sits under a vector could
  be written but never vacated, stranding the derived value with no live flow
  (violating Spec 013 §clear-flow cleanup / §Re-registration vacation).

  The fix vacates an in-range vector index by `assoc`-ing `nil` (the only local
  vacation a vector admits — a `dissoc` would shift every later element),
  mirroring `re-frame.path/container-for`'s vector-index semantics."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; clear-flow vacates a vector-index output leaf (the repro).
;; ---------------------------------------------------------------------------

(deftest clear-flow-vacates-vector-index-output
  (testing "clear-flow on a flow whose :output-path leaf sits under a vector actually vacates the index (pre-fix dissoc-in-safe no-op'd on the non-map parent, stranding the derived value)"
    (rf/reg-event :seed (fn [_ _] {:db {:cells [10 20 30 40] :src 99}}))
    (rf/dispatch-sync [:seed])
    ;; A flow whose output leaf is a vector INDEX.
    (rf/reg-flow :cell3 {:inputs [[:src]] :output-path [:cells 3]} (fn [x] x))
    (rf/dispatch-sync [:seed])
    (is (= [10 20 30 99] (:cells (rf/app-db-value :rf/default)))
        "precondition: the flow wrote its derived value into vector index 3")

    (rf.flows/clear-flow :cell3)
    (let [cells (:cells (rf/app-db-value :rf/default))]
      (is (nil? (get cells 3))
          (str "index 3 was vacated (assoc'd nil) — the stranded derived value "
               "is gone. Pre-fix it stayed 99. Got " (pr-str cells)))
      (is (= [10 20 30] (subvec cells 0 3))
          "the sibling vector entries are untouched (no dissoc/shift)")
      (is (= 4 (count cells))
          "vacation is local: the vector keeps its length (nil at the index, not a shift)"))
    (is (not (contains? (get (rf.flows/flows-snapshot) :rf/default) :cell3))
        "the flow's registry row is gone")))

;; ---------------------------------------------------------------------------
;; A re-registration output-path MOVE off a vector index also vacates the old
;; index (the same dissoc-in-safe path, out of drain).
;; ---------------------------------------------------------------------------

(deftest reg-flow-output-path-move-off-vector-index-vacates-old-index
  (testing "re-registering a flow with a new :output-path vacates the OLD vector-index leaf"
    (rf/reg-event :seed (fn [_ _] {:db {:cells [10 20 30 40] :src 7}}))
    (rf/dispatch-sync [:seed])
    (rf/reg-flow :mover {:inputs [[:src]] :output-path [:cells 2]} (fn [x] x))
    (rf/dispatch-sync [:seed])
    (is (= [10 20 7 40] (:cells (rf/app-db-value :rf/default)))
        "precondition: the flow wrote index 2")

    ;; Re-register with a NEW output-path — the old vector-index [:cells 2]
    ;; must be vacated, and the new path materialises on the next drain.
    (rf/reg-flow :mover {:inputs [[:src]] :output-path [:derived]} (fn [x] x))
    (is (nil? (get (:cells (rf/app-db-value :rf/default)) 2))
        "the old vector-index leaf [:cells 2] was vacated on the path move (pre-fix it stranded 7)")
    (rf/dispatch-sync [:seed])
    (is (= 7 (:derived (rf/app-db-value :rf/default)))
        "the new output-path materialised on the next drain")))
