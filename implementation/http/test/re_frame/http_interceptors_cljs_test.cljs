(ns re-frame.http-interceptors-cljs-test
  "CLJS-side smoke for Spec 014 §Middleware — per-frame request
  interceptor chain (rf2-6y3q).

  The JVM test (re-frame.http-interceptors-test) covers the full
  end-to-end shape: real transport, real headers landing on the wire,
  trace event assertion. This file confirms that on CLJS:

  - `reg-http-interceptor` / `clear-http-interceptor` round-trip
    against the per-frame registry.
  - Re-registering an id replaces in place.
  - Invalid shape raises `:rf.error/http-bad-interceptor`.
  - The per-frame scope holds (registry-level — frame A and frame B
    have independent slots).
  - The late-bind hooks publish under their documented keys.

  The CLJS Fetch transport itself is covered by the existing CLJS
  test suite for `:rf.http/managed`; this smoke is scoped to the
  interceptor surface."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.http-managed :as http-managed]))

(use-fixtures :each
  {:before (fn [] (http-managed/clear-all-http-interceptors!))
   :after  (fn [] (http-managed/clear-all-http-interceptors!))})

;; ---- 1. round-trip register / clear ---------------------------------------

(deftest register-and-clear-round-trip
  (testing "reg-http-interceptor adds a slot; clear removes it"
    (rf/reg-http-interceptor :a (fn [ctx] ctx))
    (let [chain (get @http-managed/interceptors :rf/default)]
      (is (= 1 (count chain)))
      (is (= :a (:id (first chain)))))
    (rf/clear-http-interceptor :a)
    (let [chain (get @http-managed/interceptors :rf/default)]
      (is (zero? (count chain))))))

;; ---- 2. registration order is preserved -----------------------------------

(deftest registration-order-preserved
  (testing "first / second / third register in order"
    (rf/reg-http-interceptor :first  (fn [c] c))
    (rf/reg-http-interceptor :second (fn [c] c))
    (rf/reg-http-interceptor :third  (fn [c] c))
    (let [chain (get @http-managed/interceptors :rf/default)]
      (is (= [:first :second :third] (mapv :id chain))))))

;; ---- 3. re-register replaces in place -------------------------------------

(deftest re-register-replaces-in-place
  (testing "re-registering :a keeps its position; second :a does not duplicate"
    (rf/reg-http-interceptor :a (fn [c] (assoc c ::v 1)))
    (rf/reg-http-interceptor :b (fn [c] c))
    (rf/reg-http-interceptor :a (fn [c] (assoc c ::v 2)))
    (let [chain (get @http-managed/interceptors :rf/default)]
      (is (= [:a :b] (mapv :id chain)))
      (is (= {::v 2} ((:before (first chain)) {}))
          ":a's :before fn is the v2 fn (replacement)"))))

;; ---- 4. per-frame scope ---------------------------------------------------

(deftest per-frame-scope
  (testing "interceptors registered on different frames do not collide"
    (rf/reg-http-interceptor :on-default {:frame :rf/default} (fn [c] c))
    (rf/reg-http-interceptor :on-other   {:frame :other}      (fn [c] c))
    (is (= [:on-default] (mapv :id (get @http-managed/interceptors :rf/default))))
    (is (= [:on-other]   (mapv :id (get @http-managed/interceptors :other))))
    ;; clear-http-interceptor on :rf/default doesn't touch :other
    (rf/clear-http-interceptor :on-default)
    (is (zero? (count (get @http-managed/interceptors :rf/default))))
    (is (= [:on-other] (mapv :id (get @http-managed/interceptors :other))))))

;; ---- 5. invalid shape raises ----------------------------------------------

(deftest invalid-shape-raises
  (testing "non-keyword id, non-fn before, or non-map opts raises :rf.error/http-bad-interceptor"
    (let [thrown (try (rf/reg-http-interceptor "string-id" (fn [c] c))
                      nil
                      (catch :default e e))]
      (is (some? thrown))
      (is (= ":rf.error/http-bad-interceptor" (.-message thrown))))
    (let [thrown (try (rf/reg-http-interceptor :x "not-a-fn")
                      nil
                      (catch :default e e))]
      (is (some? thrown))
      (is (= ":rf.error/http-bad-interceptor" (.-message thrown))))
    (let [thrown (try (rf/reg-http-interceptor :x "not-a-map" (fn [c] c))
                      nil
                      (catch :default e e))]
      (is (some? thrown))
      (is (= ":rf.error/http-bad-interceptor" (.-message thrown))))))

;; ---- 6. late-bind hooks publish under documented keys ---------------------

(deftest late-bind-hooks-published
  (testing ":http/reg-http-interceptor and :http/clear-http-interceptor land in the late-bind registry"
    (is (some? (late-bind/get-fn :http/reg-http-interceptor)))
    (is (some? (late-bind/get-fn :http/clear-http-interceptor)))
    (is (some? (late-bind/get-fn :http/clear-all-http-interceptors!)))))
