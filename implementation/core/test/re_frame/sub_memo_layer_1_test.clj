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
  ;; EP-0002 (rf2-jue6sp): `init!` no longer synthesises `:rf/default`,
  ;; and ambient subscribe / dispatch now require a carried frame stamp.
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

(deftest layer-1-memo-skips-recompute-on-equal-db
  (testing "two consecutive derefs against the same db value run the body once"
    (let [runs (atom 0)]
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
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
    (let [runs (atom 0)]
      (rf/reg-event :seed   (fn [{:keys [db]} _]      {:db {:n 0}}))
      (rf/reg-event :update (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
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
    (let [runs (atom 0)]
      ;; Two events that produce structurally-equal but non-identical maps.
      ;; `(assoc db :touched true)` would change the value; instead replace
      ;; with a fresh map that equals the old one.
      (rf/reg-event :seed   (fn [{:keys [db]} _] {:db {:n 42 :other :a}}))
      (rf/reg-event :reseed (fn [{:keys [db]} _] {:db {:n 42 :other :a}}))  ;; new map, =
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
    (let [captured (atom nil)]
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 99}}))
      (rf/reg-sub :n (fn [db query-v]
                       (reset! captured [db query-v])
                       (:n db)))
      (rf/dispatch-sync [:seed])
      (let [r (rf/subscribe [:n :arg1 :arg2])]
        (is (= 99 @r))
        (let [[db query-v] @captured]
          (is (= {:n 99} db) "body receives the full db value")
          (is (= [:n :arg1 :arg2] query-v) "body receives the full query-v"))))))

(deftest layer-1-memo-handles-false-and-empty
  (testing "false and empty-map db values are not confused with the
            ::unset sentinel — the body runs once for each, memo skips on
            repeat.

            NOTE (rf2-ekq28v): a `{:db nil}` return is now COERCED to
            `{:db {}}` at the commit boundary (app-db is always a map,
            never nil — the v1 nil-footgun is removed structurally), so a
            handler can no longer drive app-db to nil. The nil-vs-::unset
            sentinel concern is still exercised by the `false` value (a
            falsey, non-map db that the memo wrapper must distinguish from
            ::unset) and by the empty-map `{}` (a falsey-adjacent value the
            coercion produces). The `{:db nil}` coercion itself is pinned
            in `re-frame.db-noop-commit-test`."
    (let [runs (atom 0)]
      (rf/reg-event :seed-false (fn [{:keys [db]} _] {:db false}))
      (rf/reg-event :seed-empty (fn [{:keys [db]} _] {:db {}}))
      (rf/reg-event :seed-map   (fn [{:keys [db]} _] {:db {:n 1}}))
      (rf/reg-sub :v (fn [db _] (swap! runs inc) db))

      (rf/dispatch-sync [:seed-false])
      (let [r (rf/subscribe [:v])]
        (is (= false @r))
        (is (= 1 @runs) "first deref against false db runs body once")
        (is (= false @r))
        (is (= 1 @runs) "repeat deref against false db skips memo")

        (rf/dispatch-sync [:seed-empty])
        (is (= {} @r))
        (is (= 2 @runs) "transition false → {} re-runs body")
        (is (= {} @r))
        (is (= 2 @runs) "repeat deref against {} db skips memo")

        (rf/dispatch-sync [:seed-map])
        (is (= {:n 1} @r))
        (is (= 3 @runs) "transition {} → map re-runs body")))))

;; ---- equivalence with layer-2+ -------------------------------------------
;;
;; Belt-and-braces: the specialisation is correctness-preserving relative
;; to a layer-2 sub with one upstream that just reads app-db.

(deftest layer-1-and-layer-2-produce-the-same-stream-of-values
  (testing "a layer-1 sub and a layer-2 sub chained off it produce the
            same stream of values across N db updates"
    (rf/reg-event :seed   (fn [{:keys [db]} _]      {:db {:n 0}}))
    (rf/reg-event :update (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf/reg-sub :n        (fn [db _] (:n db)))
    (rf/reg-sub :n-via-l2 {:inputs [[:n]]} (fn [[n] _] n))
    (rf/dispatch-sync [:seed])
    (let [r1 (rf/subscribe [:n])
          r2 (rf/subscribe [:n-via-l2])]
      (doseq [v (range 1 6)]
        (rf/dispatch-sync [:update v])
        (is (= v @r1 @r2)
            (str "layer-1 and layer-2 agree at v=" v))))))
