(ns re-frame.sub-memo-layer-1-test
  "Regression tests for the layer-1 sub memoisation contract — Spec 006
  §No-op via value equality (rf2-719e), with the layer-1 specialisation
  per rf2-sxacg.

  The layer-1 path is specialised to a fixed-arity-1 wrapper that
  compares the db value directly (no varargs-seq alloc, no seq-vs-seq
  `=` walk). These tests pin the result-equivalence contract: the
  specialised wrapper must short-circuit on `=` inputs exactly like the
  generic wrapper, must recompute on `not=` inputs, and must hand the
  body fn the same `(db, query-v)` shape it always received.

  Microbench note: the alloc-per-recompute saving is one ArraySeq/Cons
  per layer-1 recompute (the varargs collection vec/seq the
  `(fn [& in-vals])` form would force). For an app with N layer-1
  subs × M dispatches that touch each, the saving is N×M allocations
  per drain cycle. Specialisation is correctness-preserving — the
  contract is `=` on inputs; we just compare scalars rather than
  one-element seqs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs :as subs]
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
         (subs/configure! {:grace-period-ms 50}))))

(use-fixtures :each reset-runtime)

;; ---- result-equivalence — the contract pin --------------------------------

(deftest layer-1-memo-skips-recompute-on-equal-db
  (testing "two consecutive derefs against the same db value run the body once"
    (subs/configure! {:grace-period-ms 0})
    (let [runs (atom 0)]
      (rf/reg-event-db :seed (fn [_ _] {:n 7}))
      (rf/reg-sub :n (fn [db _] (swap! runs inc) (:n db)))
      (rf/dispatch-sync [:seed])
      (let [r (rf/subscribe [:n])]
        (is (= 7 @r))
        (is (= 1 @runs) "first deref runs the body")
        ;; Second deref against the unchanged db — memo MUST short-circuit.
        (is (= 7 @r))
        (is (= 1 @runs)
            "second deref against same db does NOT re-invoke the body")
        ;; Third deref — still no change.
        (is (= 7 @r))
        (is (= 1 @runs))))))

(deftest layer-1-memo-recomputes-on-changed-db
  (testing "deref after an app-db change runs the body again"
    (subs/configure! {:grace-period-ms 0})
    (let [runs (atom 0)]
      (rf/reg-event-db :seed   (fn [_ _]      {:n 0}))
      (rf/reg-event-db :update (fn [db [_ v]] (assoc db :n v)))
      (rf/reg-sub :n (fn [db _] (swap! runs inc) (:n db)))
      (rf/dispatch-sync [:seed])
      (let [r (rf/subscribe [:n])]
        (is (= 0 @r))
        (is (= 1 @runs))
        (rf/dispatch-sync [:update 1])
        (is (= 1 @r))
        (is (= 2 @runs) "body re-runs when the db value changed")
        (rf/dispatch-sync [:update 2])
        (is (= 2 @r))
        (is (= 3 @runs))))))

(deftest layer-1-memo-value-equal-but-not-identical-skips
  (testing "two `=`-but-not-`identical?` db values still short-circuit
            — the contract is value equality, not identity"
    (subs/configure! {:grace-period-ms 0})
    (let [runs (atom 0)]
      ;; Two events that produce structurally-equal but non-identical maps.
      ;; `(assoc db :touched true)` would change the value; instead replace
      ;; with a fresh map that equals the old one.
      (rf/reg-event-db :seed   (fn [_ _] {:n 42 :other :a}))
      (rf/reg-event-db :reseed (fn [_ _] {:n 42 :other :a}))  ;; new map, =
      (rf/reg-sub :n (fn [db _] (swap! runs inc) (:n db)))
      (rf/dispatch-sync [:seed])
      (let [r (rf/subscribe [:n])]
        (is (= 42 @r))
        (is (= 1 @runs))
        (rf/dispatch-sync [:reseed])
        ;; New db map but `=` to the prior one — memo skips the body.
        (is (= 42 @r))
        (is (= 1 @runs)
            "value-equal db short-circuits the memo (no body re-run)")))))

(deftest layer-1-body-receives-db-and-query-v
  (testing "the body fn still receives the canonical (db, query-v) shape
            under the specialised wrapper — no shape regression"
    (subs/configure! {:grace-period-ms 0})
    (let [captured (atom nil)]
      (rf/reg-event-db :seed (fn [_ _] {:n 99}))
      (rf/reg-sub :n (fn [db query-v]
                       (reset! captured [db query-v])
                       (:n db)))
      (rf/dispatch-sync [:seed])
      (let [r (rf/subscribe [:n :arg1 :arg2])]
        (is (= 99 @r))
        (let [[db query-v] @captured]
          (is (= {:n 99} db) "body receives the full db value")
          (is (= [:n :arg1 :arg2] query-v) "body receives the full query-v"))))))

(deftest layer-1-memo-handles-nil-and-false
  (testing "nil and false db values are not confused with the ::unset
            sentinel — the body runs once for each, memo skips on
            repeat"
    (subs/configure! {:grace-period-ms 0})
    (let [runs (atom 0)]
      (rf/reg-event-db :seed-nil   (fn [_ _] nil))
      (rf/reg-event-db :seed-false (fn [_ _] false))
      (rf/reg-event-db :seed-map   (fn [_ _] {:n 1}))
      (rf/reg-sub :v (fn [db _] (swap! runs inc) db))

      (rf/dispatch-sync [:seed-nil])
      (let [r (rf/subscribe [:v])]
        (is (nil? @r))
        (is (= 1 @runs) "first deref against nil db runs body once")
        (is (nil? @r))
        (is (= 1 @runs) "repeat deref against nil db skips memo")

        (rf/dispatch-sync [:seed-false])
        (is (= false @r))
        (is (= 2 @runs) "transition nil → false re-runs body")
        (is (= false @r))
        (is (= 2 @runs) "repeat deref against false db skips memo")

        (rf/dispatch-sync [:seed-map])
        (is (= {:n 1} @r))
        (is (= 3 @runs) "transition false → map re-runs body")))))

;; ---- equivalence with layer-2+ -------------------------------------------
;;
;; Belt-and-braces: the specialisation is correctness-preserving relative
;; to a layer-2 sub with one upstream that just reads app-db.

(deftest layer-1-and-layer-2-produce-the-same-stream-of-values
  (testing "a layer-1 sub and a layer-2 sub chained off it produce the
            same stream of values across N db updates"
    (subs/configure! {:grace-period-ms 0})
    (rf/reg-event-db :seed   (fn [_ _]      {:n 0}))
    (rf/reg-event-db :update (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-sub :n        (fn [db _] (:n db)))
    (rf/reg-sub :n-via-l2 :<- [:n] (fn [n _] n))
    (rf/dispatch-sync [:seed])
    (let [r1 (rf/subscribe [:n])
          r2 (rf/subscribe [:n-via-l2])]
      (doseq [v (range 1 6)]
        (rf/dispatch-sync [:update v])
        (is (= v @r1 @r2)
            (str "layer-1 and layer-2 agree at v=" v))))))
