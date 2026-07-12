(ns re-frame.ui.reactive-sub-override-schema-cljs-test
  "rf2-vxgfnd.21 — the compiled-view Story `:sub-overrides` path applies the
  SAME schema validator + recover-to-nil as the Reagent-family `subscribe`
  path, and resolves the override ONCE at render.

  The Reagent leg of this parity is pinned by
  `re-frame.subs-override-seam-cljs-test` (the honesty fold-in) and
  `re-frame.security.*` (the redaction leg); BOTH now route through the ONE
  shared `re-frame.subs.override-schema/validate-sub-override!` primitive, so
  this file only has to prove the compiled-view door (`re-frame.ui.reactive`)
  invokes it identically — a schema-invalid override emits
  `:rf.error/schema-validation-failure {:where :sub-override}` and surfaces
  nil, a conforming or schemaless override surfaces unchanged, a nil-valued
  override stays a HIT (distinct from a miss), and a miss reads the real sub.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` (node) AND
  `clojure -M:test` (JVM), so the override validation is graft-checked on
  BOTH tiers against the REAL observation port + sub-cache (plain-atom)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.late-bind            :as late-bind]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.trace.tooling        :as trace-tooling]
            [re-frame.ui.reactive          :as reactive]))

(def ^:private fid :rf/default)

(defn- with-stub-validator
  "Install a minimal REGISTERED validator (the same late-bind hook the Malli
  adapter and Spec 010 §step-6 use) so the override schema check runs without
  pulling the schemas artefact onto the ui test classpath — the point under
  test is that the compiled path reuses whatever validator is registered.
  Saves + restores the prior hook so the consolidated node-test bundle's real
  Malli validator is untouched for sibling tests."
  [test-fn]
  (let [prior (late-bind/get-fn :schemas/validate-with-registered-fn)]
    (late-bind/set-fn! :schemas/validate-with-registered-fn
      (fn [schema value] (if (= schema :int) (int? value) true)))
    (try (test-fn)
         (finally (late-bind/set-fn! :schemas/validate-with-registered-fn prior)))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  with-stub-validator)

(defn- read-override
  "Read `query` as a compiled-view site with `overrides` bound (the explicit
  override door). Outside a ViewCell, `sub-read` returns the freshly resolved
  value — for a HIT, the schema-validated pinned value."
  [overrides query]
  (rf/with-frame fid
    (binding [reactive/*sub-overrides* overrides]
      (reactive/sub-read query))))

(defn- collect-errors
  "Run `thunk` capturing every `:rf.error/*` trace event. Returns the vector
  of captured events."
  [thunk]
  (let [seen (atom [])
        lid  ::override-schema-errors]
    (trace-tooling/register-listener! lid
      (fn [ev] (when (= :error (:op-type ev)) (swap! seen conj ev))))
    (try (thunk) (finally (trace-tooling/unregister-listener! lid)))
    @seen))

;; ---- the schema-validation fold-in (parity with the Reagent path) --------

(deftest invalid-override-emits-and-surfaces-nil
  (testing "an override violating the sub's :schema emits :where :sub-override
            (frame-stamped) and surfaces nil through the compiled-view door"
    (rf/reg-sub :count/value {:schema :int} (fn [db _] (get db :n 0)))
    (let [result (atom ::unset)
          errors (collect-errors
                   (fn []
                     (reset! result
                             (read-override {[:count/value] "not-an-int"}
                                            [:count/value]))))]
      (is (nil? @result)
          "the violating override is replaced-with-default (nil)")
      (let [failure (first (filter #(= :rf.error/schema-validation-failure
                                       (:operation %))
                                   errors))]
        (is (some? failure)
            "a schema-validation-failure was emitted for the violating override")
        (is (= :sub-override (-> failure :tags :where))
            "the :where discriminator is :sub-override")
        (is (= fid (-> failure :tags :frame))
            "the failure trace is stamped with the subscribing frame")))))

(deftest conforming-override-surfaces-unchanged-no-failure
  (testing "an override that conforms to the sub's :schema surfaces unchanged
            with no failure trace"
    (rf/reg-sub :count/value {:schema :int} (fn [db _] (get db :n 0)))
    (let [result (atom ::unset)
          errors (collect-errors
                   (fn []
                     (reset! result
                             (read-override {[:count/value] 42} [:count/value]))))]
      (is (= 42 @result) "the conforming override surfaces unchanged")
      (is (not-any? #(= :rf.error/schema-validation-failure (:operation %)) errors)
          "no schema-validation-failure for a conforming override"))))

(deftest schemaless-sub-skips-validation
  (testing "a sub with no :schema → any override value surfaces, no validation"
    (rf/reg-sub :free/value (fn [db _] (get db :v)))
    (let [result (atom ::unset)
          errors (collect-errors
                   (fn []
                     (reset! result
                             (read-override {[:free/value] {:any "shape"}}
                                            [:free/value]))))]
      (is (= {:any "shape"} @result)
          "any value surfaces when the sub declares no schema")
      (is (not-any? #(= :rf.error/schema-validation-failure (:operation %)) errors)
          "no validation runs when the sub has no :schema"))))

;; ---- HIT vs MISS distinction ---------------------------------------------

(deftest nil-valued-override-is-a-hit-distinct-from-a-miss
  (testing "a nil-valued override is a HIT (surfaces nil), distinct from a
            miss (surfaces the real subscription)"
    (rf/reg-sub :x/value (fn [db _] (get db :x ::real)))
    (rf/dispatch-sync [:rf/set-db {}] {:frame fid})
    (is (nil? (read-override {[:x/value] nil} [:x/value]))
        "nil override surfaces as nil, NOT the real ::real value")
    (is (= ::real (read-override {[:other/q] :pinned} [:x/value]))
        "a MISS falls through to the real subscription")))

(deftest miss-reads-the-real-subscription
  (testing "a non-overridden query is unaffected even with other overrides bound"
    (rf/reg-sub :a/sub (fn [_db _] :real-a))
    (rf/reg-sub :b/sub (fn [_db _] :real-b))
    (rf/dispatch-sync [:rf/set-db {}] {:frame fid})
    (is (= :pinned-a (read-override {[:a/sub] :pinned-a} [:a/sub]))
        "overridden query → pinned")
    (is (= :real-b (read-override {[:a/sub] :pinned-a} [:b/sub]))
        "non-overridden query → real")))
