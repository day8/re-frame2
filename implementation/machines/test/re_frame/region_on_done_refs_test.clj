(ns re-frame.region-on-done-refs-test
  "A parallel REGION BODY's own `:on-done` is an `:on`-shaped transition that
  takes the region's done (Spec 005 §The done-state signal), so its `:guard`
  / `:action` refs are checked at registration exactly as a compound's
  `:on-done` refs are — never left to fail at the first region done that
  reaches them.

  - A keyword ref naming no entry in `:guards` / `:actions` is refused with
    `:rf.error/machine-unresolved-guard` / `:rf.error/machine-unresolved-action`.
  - A value that is neither ONE fn nor ONE keyword is refused with
    `:rf.error/machine-bad-guard-form` / `:rf.error/machine-bad-action-form`.
  - Every candidate of a vector `:on-done` is checked, not only the first.
  - The refusal names the declaring region as its `:state`, as a region
    body's `:entry` / `:exit` refusal does.
  - Inline fns, and named refs that resolve, register."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as rf.machines]))

(defn- machine
  "A two-region parallel machine whose region `region` declares `on-done`,
  with `extra` merged into the root (its `:guards` / `:actions`)."
  ([region on-done] (machine region on-done {}))
  ([region on-done extra]
   (-> {:type    :parallel
        :regions {:a {:initial :a1
                      :states  {:a1 {:on {:go :a2}}
                                :a2 {:final? true}}}
                  :b {:initial :b1
                      :states  {:b1 {:on {:go :b2}}
                                :b2 {:final? true}}}}}
       (assoc-in [:regions region :on-done] on-done)
       (merge extra))))

(defn- refusal
  "The ex-data of the refusal `make-machine-handler` raises for `m`, or nil
  when `m` registers."
  [m]
  (try (rf.machines/make-machine-handler m) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(def ^:private named
  {:guards  {:ready? (fn [_] true)}
   :actions {:log (fn [_] nil)}})

(deftest region-on-done-missing-refs-are-refused
  (testing "an :action naming no entry in :actions"
    (let [d (refusal (machine :a {:action :absent}))]
      (is (= :rf.error/machine-unresolved-action (:rf.error/id d)))
      (is (= :absent (:action d)))
      (is (= :a (:state d)) "the refusal names the declaring region")))
  (testing "a :guard naming no entry in :guards, on the second region"
    (let [d (refusal (machine :b {:guard :absent :target :b1}))]
      (is (= :rf.error/machine-unresolved-guard (:rf.error/id d)))
      (is (= :absent (:guard d)))
      (is (= :b (:state d)) "the refusal names the declaring region"))))

(deftest region-on-done-invalid-forms-are-refused
  (testing "an :action that is a vector of actions"
    (let [d (refusal (machine :a {:action [:absent]}))]
      (is (= :rf.error/machine-bad-action-form (:rf.error/id d)))
      (is (= :action (:slot d)))
      (is (= :a (:state d)))))
  (testing "a :guard in a data-combinator form"
    (let [d (refusal (machine :a {:guard {:and [:g1 :g2]} :target :a1}))]
      (is (= :rf.error/machine-bad-guard-form (:rf.error/id d)))
      (is (= :guard (:slot d)))
      (is (= :a (:state d))))))

(deftest region-on-done-every-candidate-is-checked
  (testing "a missing :action on a LATER candidate is refused"
    (let [d (refusal (machine :a [{:guard (fn [_] false) :target :a1}
                                  {:action :absent}]))]
      (is (= :rf.error/machine-unresolved-action (:rf.error/id d)))
      (is (= :absent (:action d)))))
  (testing "a missing :guard on the FIRST candidate is refused"
    (is (= :rf.error/machine-unresolved-guard
           (:rf.error/id (refusal (machine :a [{:guard :absent :target :a1}
                                               {:action (fn [_] nil)}])))))))

(deftest region-on-done-valid-refs-register
  (testing "an inline fn :guard and :action"
    (is (nil? (refusal (machine :a {:guard (fn [_] true) :action (fn [_] nil)})))))
  (testing "a named :guard and :action that resolve"
    (is (nil? (refusal (machine :a {:guard :ready? :action :log} named)))))
  (testing "a vector of candidates whose refs all resolve"
    (is (nil? (refusal (machine :a [{:guard :ready? :target :a1} {:action :log}] named))))))
