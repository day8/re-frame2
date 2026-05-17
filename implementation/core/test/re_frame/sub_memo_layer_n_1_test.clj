(ns re-frame.sub-memo-layer-n-1-test
  "Regression tests for the layer-2 single-input sub memoisation contract
  — Spec 006 §No-op via value equality (rf2-719e), with the layer-2-1
  specialisation per rf2-0y2bp.

  The layer-2-1 path is specialised to a fixed-arity-1 wrapper that
  compares the upstream value directly (no varargs-seq alloc, no
  seq-vs-seq `=` walk). Parity with the layer-1 specialisation
  (rf2-sxacg). These tests pin the result-equivalence contract: the
  specialised wrapper must short-circuit on `=` inputs exactly like the
  generic varargs wrapper, must recompute on `not=` inputs, and must
  hand the body fn the same `(upstream-value, query-v)` shape it always
  received.

  Microbench note: the alloc-per-recompute saving is one ArraySeq/Cons
  per layer-2-single-input recompute (the varargs collection seq the
  `(fn [& in-vals])` form would force). The adapter's
  `make-derived-value` (rf2-v1nu0) already specialises its recompute
  closure to `(compute-fn @s0)` for the 1-source case, so the only
  remaining seq alloc on the hot path was at the memo wrapper — this
  removes it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs.cache :as subs-cache]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (try (test-fn)
       (finally
         (subs-cache/configure! {:grace-period-ms 50}))))

(use-fixtures :each reset-runtime)

;; ---- result-equivalence — the contract pin --------------------------------

(deftest layer-n-1-memo-skips-recompute-on-equal-upstream
  (testing "two consecutive derefs against an unchanged upstream value
            run the layer-2 body once"
    (subs-cache/configure! {:grace-period-ms 0})
    (let [runs (atom 0)]
      (rf/reg-event-db :seed (fn [_ _] {:n 7}))
      (rf/reg-sub :n  (fn [db _] (:n db)))
      (rf/reg-sub :n*2 :<- [:n] (fn [n _] (swap! runs inc) (* 2 n)))
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
    (subs-cache/configure! {:grace-period-ms 0})
    (let [runs (atom 0)]
      (rf/reg-event-db :seed   (fn [_ _]      {:n 0}))
      (rf/reg-event-db :update (fn [db [_ v]] (assoc db :n v)))
      (rf/reg-sub :n   (fn [db _] (:n db)))
      (rf/reg-sub :n*2 :<- [:n] (fn [n _] (swap! runs inc) (* 2 n)))
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

(deftest layer-n-1-memo-suppresses-when-db-changes-but-upstream-equal
  (testing "the layer-2 body short-circuits when the upstream sub's
            value is unchanged, even though the underlying db changed.
            This is the headline value of the no-op-by-equality contract
            — diamond-shape graphs do not over-compute downstream."
    (subs-cache/configure! {:grace-period-ms 0})
    (let [runs (atom 0)]
      (rf/reg-event-db :seed     (fn [_ _]      {:n 1 :other :a}))
      (rf/reg-event-db :touch    (fn [db _]     (assoc db :other :b)))
      (rf/reg-sub :n   (fn [db _] (:n db)))
      (rf/reg-sub :n*2 :<- [:n] (fn [n _] (swap! runs inc) (* 2 n)))
      (rf/dispatch-sync [:seed])
      (let [r (rf/subscribe [:n*2])]
        (is (= 2 @r))
        (is (= 1 @runs))
        ;; Change db on a key the :n sub does NOT read.
        (rf/dispatch-sync [:touch])
        (is (= 2 @r))
        (is (= 1 @runs)
            "upstream sub yields = value → layer-2 body must not re-run")))))

(deftest layer-n-1-body-receives-upstream-value-and-query-v
  (testing "the body fn still receives the canonical (upstream, query-v)
            shape under the specialised wrapper — no shape regression"
    (subs-cache/configure! {:grace-period-ms 0})
    (let [captured (atom nil)]
      (rf/reg-event-db :seed (fn [_ _] {:n 99}))
      (rf/reg-sub :n   (fn [db _] (:n db)))
      (rf/reg-sub :n*2 :<- [:n]
                  (fn [n query-v]
                    (reset! captured [n query-v])
                    (* 2 n)))
      (rf/dispatch-sync [:seed])
      (let [r (rf/subscribe [:n*2 :arg1 :arg2])]
        (is (= 198 @r))
        (let [[n query-v] @captured]
          (is (= 99 n)
              "body receives the upstream's value as a scalar (not wrapped)")
          (is (= [:n*2 :arg1 :arg2] query-v) "body receives the full query-v"))))))

(deftest layer-n-1-memo-handles-nil-and-false
  (testing "nil and false upstream values are not confused with the ::unset
            sentinel — the body runs once for each, memo skips on
            repeat"
    (subs-cache/configure! {:grace-period-ms 0})
    (let [runs (atom 0)]
      (rf/reg-event-db :seed-nil   (fn [_ _] {:n nil}))
      (rf/reg-event-db :seed-false (fn [_ _] {:n false}))
      (rf/reg-event-db :seed-val   (fn [_ _] {:n 1}))
      (rf/reg-sub :n   (fn [db _] (:n db)))
      (rf/reg-sub :n-shadow :<- [:n]
                  (fn [n _] (swap! runs inc) n))

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

;; ---- equivalence with layer-2+ (≥2 inputs) --------------------------------
;;
;; Belt-and-braces: the 1-input specialisation is correctness-preserving
;; relative to a 2-input layer-2 sub that ignores its second input.

(deftest layer-n-1-and-layer-n-produce-the-same-stream-of-values
  (testing "a 1-input layer-2 sub and a 2-input layer-2 sub (one of the
            inputs constant) produce the same stream of values across
            N db updates"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :seed   (fn [_ _]      {:n 0 :k :stable}))
    (rf/reg-event-db :update (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-sub :n  (fn [db _] (:n db)))
    (rf/reg-sub :k  (fn [db _] (:k db)))
    (rf/reg-sub :n*2-1 :<- [:n]
                (fn [n _] (* 2 n)))
    (rf/reg-sub :n*2-2 :<- [:n] :<- [:k]
                (fn [[n _k] _] (* 2 n)))
    (rf/dispatch-sync [:seed])
    (let [r1 (rf/subscribe [:n*2-1])
          r2 (rf/subscribe [:n*2-2])]
      (doseq [v (range 1 6)]
        (rf/dispatch-sync [:update v])
        (is (= (* 2 v) @r1 @r2)
            (str "1-input and 2-input layer-2 agree at v=" v))))))

;; ---- chain of layer-2 single-input subs -----------------------------------
;;
;; Stress the memo: B :<- A, C :<- B. A change to db that A absorbs but
;; B/C don't should never re-run B or C; a change that A propagates
;; should run A, B, C once each.

(deftest layer-n-1-chain-propagates-and-suppresses-correctly
  (subs-cache/configure! {:grace-period-ms 0})
  (let [a-runs (atom 0)
        b-runs (atom 0)
        c-runs (atom 0)]
    (rf/reg-event-db :seed   (fn [_ _]      {:n 1 :other :x}))
    (rf/reg-event-db :update (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-event-db :touch  (fn [db _]     (assoc db :other :y)))
    (rf/reg-sub :a (fn [db _] (swap! a-runs inc) (:n db)))
    (rf/reg-sub :b :<- [:a] (fn [a _] (swap! b-runs inc) (inc a)))
    (rf/reg-sub :c :<- [:b] (fn [b _] (swap! c-runs inc) (inc b)))
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
