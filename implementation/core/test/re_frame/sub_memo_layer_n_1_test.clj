(ns re-frame.sub-memo-layer-n-1-test
  "Tests for the layer-2 single-input sub memoisation contract
  — Spec 006 §No-op via value equality, with the layer-2-1
  specialisation.

  The layer-2-1 path is specialised to a fixed-arity-1 wrapper that
  compares the upstream value directly (no varargs-seq alloc, no
  seq-vs-seq `=` walk). Parity with the layer-1 specialisation.
  These tests pin the result-equivalence contract: the
  specialised wrapper must short-circuit on `=` inputs exactly like the
  generic varargs wrapper, must recompute on `not=` inputs, and must
  hand the body fn the same `(upstream-value, query-v)` shape as the
  generic wrapper.

  Microbench note: the alloc-per-recompute saving is one ArraySeq/Cons
  per layer-2-single-input recompute (the varargs collection seq the
  `(fn [& in-vals])` form would force). The substrate's
  `make-derived-value` specialises its recompute closure to
  `(compute-fn @s0)` for the 1-source case, so the memo wrapper is the
  only place on the hot path a seq alloc could arise, and the
  fixed-arity wrapper avoids it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; EP-0002: `init!` does not synthesise `:rf/default`,
  ;; and ambient subscribe / dispatch require a carried frame stamp.
  ;; These memoization tests run against a single conventional app frame,
  ;; so register `:rf/default` explicitly and pin it as the established
  ;; scope for the whole body via `with-frame`.
  (rf.frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- result-equivalence — the contract pin --------------------------------

(deftest layer-n-1-memo-skips-recompute-on-equal-upstream
  (testing "two consecutive derefs against an unchanged upstream value
            run the layer-2 body once"
    (let [runs (atom 0)]
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
      (rf/reg-sub :n  (fn [db _] (:n db)))
      (rf/reg-sub :n*2 {:inputs [[:n]]} (fn [[n] _] (swap! runs inc) (* 2 n)))
      (rf/dispatch-sync [:seed])
      (let [r (rf/subscribe [:n*2])]
        (is (= 14 @r))
        (is (= 1 @runs) "first deref runs the body")
        (is (= 14 @r))
        (is (= 1 @runs)
            "second deref against same upstream does NOT re-invoke the body")
        (is (= 14 @r))
        (is (= 1 @runs))))))

(deftest layer-n-1-memo-recomputes-on-changed-upstream
  (testing "deref after an upstream change runs the body again"
    (let [runs (atom 0)]
      (rf/reg-event :seed   (fn [{:keys [db]} _]      {:db {:n 0}}))
      (rf/reg-event :update (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
      (rf/reg-sub :n   (fn [db _] (:n db)))
      (rf/reg-sub :n*2 {:inputs [[:n]]} (fn [[n] _] (swap! runs inc) (* 2 n)))
      (rf/dispatch-sync [:seed])
      (let [r (rf/subscribe [:n*2])]
        (is (= 0 @r))
        (is (= 1 @runs))
        (rf/dispatch-sync [:update 3])
        (is (= 6 @r))
        (is (= 2 @runs) "body re-runs when the upstream value changed")
        (rf/dispatch-sync [:update 5])
        (is (= 10 @r))
        (is (= 3 @runs))))))

(deftest layer-n-1-body-receives-upstream-value-and-query-v
  (testing "the body fn receives the canonical (upstream, query-v)
            shape under the specialised wrapper"
    (let [captured (atom nil)]
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 99}}))
      (rf/reg-sub :n   (fn [db _] (:n db)))
      (rf/reg-sub :n*2 {:inputs [[:n]]}
                  (fn [[n] query-v]
                    (reset! captured [n query-v])
                    (* 2 n)))
      (rf/dispatch-sync [:seed])
      (let [r (rf/subscribe [:n*2 :arg1 :arg2])]
        (is (= 198 @r))
        (let [[n query-v] @captured]
          (is (= 99 n)
              "body receives the upstream's value as the first element of its input vector")
          (is (= [:n*2 :arg1 :arg2] query-v) "body receives the full query-v"))))))

(deftest layer-n-1-memo-handles-nil-and-false
  (testing "nil and false upstream values are not confused with the ::unset
            sentinel — the body runs once for each, memo skips on
            repeat"
    (let [runs (atom 0)]
      (rf/reg-event :seed-nil   (fn [{:keys [db]} _] {:db {:n nil}}))
      (rf/reg-event :seed-false (fn [{:keys [db]} _] {:db {:n false}}))
      (rf/reg-event :seed-val   (fn [{:keys [db]} _] {:db {:n 1}}))
      (rf/reg-sub :n   (fn [db _] (:n db)))
      (rf/reg-sub :n-shadow {:inputs [[:n]]}
                  (fn [[n] _] (swap! runs inc) n))

      (rf/dispatch-sync [:seed-nil])
      (let [r (rf/subscribe [:n-shadow])]
        (is (nil? @r))
        (is (= 1 @runs) "first deref against nil upstream runs body once")
        (is (nil? @r))
        (is (= 1 @runs) "repeat deref against nil upstream skips memo")

        (rf/dispatch-sync [:seed-false])
        (is (= false @r))
        (is (= 2 @runs) "transition nil → false re-runs body")
        (is (= false @r))
        (is (= 2 @runs) "repeat deref against false upstream skips memo")

        (rf/dispatch-sync [:seed-val])
        (is (= 1 @r))
        (is (= 3 @runs) "transition false → val re-runs body")))))

;; ---- chain of layer-2 single-input subs -----------------------------------
;;
;; Stress the memo: B over A, C over B. A change to db that A absorbs but
;; B/C don't should never re-run B or C — the headline value of the
;; no-op-by-equality contract, so diamond-shape graphs do not over-compute
;; downstream; a change that A propagates should run A, B, C once each.

(deftest layer-n-1-chain-propagates-and-suppresses-correctly
  (let [a-runs (atom 0)
        b-runs (atom 0)
        c-runs (atom 0)]
    (rf/reg-event :seed   (fn [{:keys [db]} _]      {:db {:n 1 :other :x}}))
    (rf/reg-event :update (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf/reg-event :touch  (fn [{:keys [db]} _]     {:db (assoc db :other :y)}))
    (rf/reg-sub :a (fn [db _] (swap! a-runs inc) (:n db)))
    (rf/reg-sub :b {:inputs [[:a]]} (fn [[a] _] (swap! b-runs inc) (inc a)))
    (rf/reg-sub :c {:inputs [[:b]]} (fn [[b] _] (swap! c-runs inc) (inc b)))
    (rf/dispatch-sync [:seed])
    (let [rc (rf/subscribe [:c])]
      (is (= 3 @rc))
      (is (= [1 1 1] [@a-runs @b-runs @c-runs]) "each runs once on first deref")
      ;; Touch an unrelated key — :a's value is unchanged.
      (rf/dispatch-sync [:touch])
      (is (= 3 @rc))
      (is (= [2 1 1] [@a-runs @b-runs @c-runs])
          ":a re-runs (db changed) but its result is = → :b and :c suppressed")
      ;; Real change to :n.
      (rf/dispatch-sync [:update 10])
      (is (= 12 @rc))
      (is (= [3 2 2] [@a-runs @b-runs @c-runs])
          "real change propagates through the chain once"))))
