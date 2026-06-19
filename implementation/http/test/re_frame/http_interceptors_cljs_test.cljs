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
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.http.managed :as http-managed]))

;; EP-0002 (rf2-nn0jqa): the no-`:frame` `reg-http-interceptor` /
;; `clear-http-interceptor` calls below resolve the frame through the
;; carried-invariant scope chain (rf2-5q7um6), so the fixture registers
;; `:rf/default` and pins it as the established scope for each test body —
;; the wrapping fn form replaces the prior `:before`/`:after` map so the
;; body can run inside `*current-frame*` :rf/default. Tests that name a
;; frame explicitly (the per-frame-scope case) still override it.
(use-fixtures :each
  (fn [t]
    (http-managed/clear-all-http-interceptors!)
    (frame/ensure-default-frame!)
    (binding [frame/*current-frame* :rf/default]
      (t))
    (http-managed/clear-all-http-interceptors!)))

;; ---- 1. round-trip register / clear ---------------------------------------

(deftest register-and-clear-round-trip
  (testing "reg-http-interceptor adds a slot; clear removes it"
    (rf/reg-http-interceptor :a {:before (fn [ctx] ctx)})
    (let [chain (http-managed/interceptors-snapshot :rf/default)]
      (is (= 1 (count chain)))
      (is (= :a (:id (first chain)))))
    (rf/clear-http-interceptor :a)
    (let [chain (http-managed/interceptors-snapshot :rf/default)]
      (is (zero? (count chain))))))

;; ---- 1a. rf2-vl5xsp — single-arity clear FAILS CLOSED under no scope ------
;;
;; The fixture pins an ambient `*current-frame* :rf/default`, which MASKS the
;; old facade floor (the single-arity used to recurse `[:rf/default id]`,
;; synthesising the default before delegating). Clear the ambient scope
;; (`*current-frame* nil`) and assert the single-arity facade raises the
;; always-on `:rf.error/no-frame-context` — proving the public surface fails
;; closed and the :rf/default floor is gone.

(deftest clear-http-interceptor-single-arity-fails-closed-under-no-scope
  (testing "rf2-vl5xsp — single-arity `rf/clear-http-interceptor` under NO
            ambient frame raises :rf.error/no-frame-context; it does NOT
            synthesise a :rf/default target."
    (binding [frame/*current-frame* nil]
      (let [thrown (try (rf/clear-http-interceptor :some-id)
                        nil
                        (catch :default e e))]
        (is (some? thrown)
            "single-arity clear with no carried frame must throw")
        (is (= :rf.error/no-frame-context
               (:rf.error/id (ex-data thrown)))
            "the throw is the always-on :rf.error/no-frame-context — no :rf/default floor")))))

;; ---- 2. registration order is preserved -----------------------------------

(deftest registration-order-preserved
  (testing "first / second / third register in order"
    (rf/reg-http-interceptor :first  {:before (fn [c] c)})
    (rf/reg-http-interceptor :second {:before (fn [c] c)})
    (rf/reg-http-interceptor :third  {:before (fn [c] c)})
    (let [chain (http-managed/interceptors-snapshot :rf/default)]
      (is (= [:first :second :third] (mapv :id chain))))))

;; ---- 3. re-register replaces in place -------------------------------------

(deftest re-register-replaces-in-place
  (testing "re-registering :a keeps its position; second :a does not duplicate"
    (rf/reg-http-interceptor :a {:before (fn [c] (assoc c ::v 1))})
    (rf/reg-http-interceptor :b {:before (fn [c] c)})
    (rf/reg-http-interceptor :a {:before (fn [c] (assoc c ::v 2))})
    (let [chain (http-managed/interceptors-snapshot :rf/default)]
      (is (= [:a :b] (mapv :id chain)))
      (is (= {::v 2} ((:before (first chain)) {}))
          ":a's :before fn is the v2 fn (replacement)"))))

;; ---- 4. per-frame scope ---------------------------------------------------

(deftest per-frame-scope
  (testing "interceptors registered on different frames do not collide"
    (rf/reg-http-interceptor :on-default {:frame :rf/default :before (fn [c] c)})
    (rf/reg-http-interceptor :on-other   {:frame :other      :before (fn [c] c)})
    (is (= [:on-default] (mapv :id (http-managed/interceptors-snapshot :rf/default))))
    (is (= [:on-other]   (mapv :id (http-managed/interceptors-snapshot :other))))
    ;; clear-http-interceptor on :rf/default doesn't touch :other
    (rf/clear-http-interceptor :on-default)
    (is (zero? (count (http-managed/interceptors-snapshot :rf/default))))
    (is (= [:on-other] (mapv :id (http-managed/interceptors-snapshot :other))))))

;; ---- 5. invalid shape raises ----------------------------------------------

(deftest invalid-shape-raises
  (testing "rf2-uheqq — non-keyword id, non-map interceptor-map, non-fn
            :before / :after, or missing both fns raises
            :rf.error/http-bad-interceptor"
    (let [thrown (try (rf/reg-http-interceptor "string-id" {:before (fn [c] c)})
                      nil
                      (catch :default e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))
    (let [thrown (try (rf/reg-http-interceptor :x "not-a-map")
                      nil
                      (catch :default e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))
    (let [thrown (try (rf/reg-http-interceptor :x {:before "not-a-fn"})
                      nil
                      (catch :default e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))
    (let [thrown (try (rf/reg-http-interceptor :x {:after "not-a-fn"})
                      nil
                      (catch :default e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))
    ;; missing both :before and :after — a no-op interceptor is rejected
    (let [thrown (try (rf/reg-http-interceptor :x {:doc "no fns"})
                      nil
                      (catch :default e e))]
      (is (some? thrown))
      (is (= :rf.error/http-bad-interceptor (:rf.error/id (ex-data thrown)))))))

;; ---- 7. rf2-uheqq — `:after` slot stored alongside `:before` --------------

(deftest after-slot-is-stored
  (testing "rf2-uheqq — an interceptor registered with :after stamps the
            slot under :after on the stored interceptor map"
    (let [after-fn (fn [_ctx resp] resp)]
      (rf/reg-http-interceptor :uheqq/with-after {:after after-fn})
      (let [slot (first (filter #(= :uheqq/with-after (:id %))
                                (http-managed/interceptors-snapshot :rf/default)))]
        (is (some? slot) "slot is in the chain")
        (is (= after-fn (:after slot))
            ":after fn stored verbatim on the slot")
        (is (nil? (:before slot))
            ":before is absent when only :after was supplied")))))

;; ---- 6. late-bind hooks publish under documented keys ---------------------

(deftest late-bind-hooks-published
  (testing ":http/reg-http-interceptor and :http/clear-http-interceptor land in the late-bind registry"
    (is (some? (late-bind/get-fn :http/reg-http-interceptor)))
    (is (some? (late-bind/get-fn :http/clear-http-interceptor)))
    (is (some? (late-bind/get-fn :http/clear-all-http-interceptors!)))))
