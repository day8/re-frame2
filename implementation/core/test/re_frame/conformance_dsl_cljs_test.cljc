(ns re-frame.conformance-dsl-cljs-test
  "Focused, host-neutral unit tests for the conformance handler-body DSL
  evaluator.

  The `re-frame.conformance` DSL interpreter is `.cljc`, so `resolve-value*`,
  `realise-event-handler`, and `realise-event-fx-handler` MUST behave
  identically on both hosts. This single `.cljc` carries the whole coverage
  and runs on both, so no per-host copy can drift into a subset that drops
  cases.

  Naming note: the `-cljs-test` suffix is a test-DISCOVERY constraint, not a
  host restriction — the CLJS `:node-test` build discovers `cljs-test$`, and
  that suffix also satisfies the JVM cognitect runner's `.*-test$`, so this
  ONE file runs on BOTH hosts. The corpus runner covers the DSL end-to-end
  via real fixtures; these target individual DSL shapes."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.conformance :as rf.conformance]))

;; ---- :event-arg / :get-event-arg split ------------------------------------
;;
;; The DSL splits event-arg access into two forms:
;;   [:event-arg n]                  — n-th event arg
;;   [:event-arg n default-val]      — n-th event arg, default-val if nil
;;                                     (UNCONDITIONAL: no type-dispatch even
;;                                     when default-val is a keyword and the
;;                                     n-th arg is a map.)
;;   [:get-event-arg n :key]         — key-access into n-th event arg
;;   [:get-event-arg n :key default] — key-access with default if missing/nil

(deftest get-event-arg-key-access
  (testing ":get-event-arg extracts a key from a map arg"
    (let [ctx {:event [:some-id {:foo 99}]}]
      (is (= 99  (rf.conformance/resolve-value* [:get-event-arg 1 :foo] ctx)))
      (is (= nil (rf.conformance/resolve-value* [:get-event-arg 1 :missing] ctx)))))

  (testing "[:get-event-arg n :key default] uses default for missing/nil"
    (let [ctx {:event [:some-id {:foo 99 :nilval nil}]}]
      (is (= 99       (rf.conformance/resolve-value* [:get-event-arg 1 :foo :fallback] ctx))
          "present non-nil value is returned (default ignored)")
      (is (= :default (rf.conformance/resolve-value* [:get-event-arg 1 :missing :default] ctx))
          "missing key falls back to default")
      (is (= :default (rf.conformance/resolve-value* [:get-event-arg 1 :nilval :default] ctx))
          "explicit nil value also falls back to default")))

  (testing "[:get-event-arg n :key] on a nil arg returns nil"
    (let [ctx {:event [:some-id]}]
      (is (= nil (rf.conformance/resolve-value* [:get-event-arg 1 :foo] ctx))))))

;; ---- shared harness primitives --------------------------------------------
;;
;; `collect-cofx-keys`, `realise-cofx-supplier`, `submap?`,
;; `check-trace-emissions`, and `resolve-sub` live in the shared
;; `.cljc` owner `re-frame.conformance` rather than as private copies in the
;; core corpus runner and the schemas artefact runner, so they run once,
;; identically, on both hosts. These target the primitives directly.

(deftest collect-cofx-keys-walks-nested-steps
  (testing "pulls every [:cofx-key K] ref across nested body steps, as a set"
    (is (= #{:app-version :user}
           (rf.conformance/collect-cofx-keys
             [[:set [:v]  [:cofx-key :app-version]]
              [:fx  :sink [:cofx-key :user]]]))))

  (testing "no cofx refs → empty set"
    (is (= #{} (rf.conformance/collect-cofx-keys [[:noop]])))))

(deftest realise-cofx-supplier-returns-set-value
  (testing "the supplier returns the :set step's value"
    (is (= 7 ((rf.conformance/realise-cofx-supplier [[:set [:v] 7]])))))

  (testing "the LAST :set wins (single-delivery convention)"
    (is (= 2 ((rf.conformance/realise-cofx-supplier
                [[:set [:v] 1] [:set [:v] 2]])))))

  (testing ":set value passes through eval-value* (reflection forms resolve)"
    (is (= 5 ((rf.conformance/realise-cofx-supplier [[:set [:v] [:fn :+ 2 3]]])))))

  (testing "no :set step → nil"
    (is (nil? ((rf.conformance/realise-cofx-supplier [[:noop]]))))))

(deftest submap?-recursive-partial-match
  (testing "flat subset matches"
    (is (true?  (rf.conformance/submap? {:a 1} {:a 1 :b 2})))
    (is (false? (rf.conformance/submap? {:a 2} {:a 1})))
    (is (false? (rf.conformance/submap? {:a 1} {}))
        "a missing key is a mismatch (present-with-nil vs absent both fail = )"))

  (testing "recurses into nested maps"
    (is (true?  (rf.conformance/submap? {:a {:b 1}} {:a {:b 1 :c 2}})))
    (is (false? (rf.conformance/submap? {:a {:b 2}} {:a {:b 1 :c 2}}))))

  (testing "non-map values compare by ="
    (is (true?  (rf.conformance/submap? 5 5)))
    (is (false? (rf.conformance/submap? 5 6)))))

(deftest check-trace-emissions-order-preserving-subset
  (let [actual [{:op :a} {:op :b} {:op :c}]]
    (testing "in-order subset with tolerated extras → no failures"
      (is (empty? (rf.conformance/check-trace-emissions actual [{:op :a} {:op :c}]))))

    (testing "out-of-order expected → failure"
      (is (seq (rf.conformance/check-trace-emissions actual [{:op :c} {:op :a}]))))

    (testing "missing expected trace → one failure"
      (is (= 1 (count (rf.conformance/check-trace-emissions actual [{:op :z}]))))))

  (testing "partial key match (extra actual keys ignored)"
    (is (empty? (rf.conformance/check-trace-emissions
                  [{:op :a :extra 99}] [{:op :a}]))))

  (testing "nested-map keys are matched submap-wise"
    (is (empty? (rf.conformance/check-trace-emissions
                  [{:op :x :tags {:id 1 :n 2}}] [{:tags {:id 1}}])))
    (is (seq (rf.conformance/check-trace-emissions
               [{:op :x :tags {:id 1}}] [{:tags {:id 2}}])))))

(deftest resolve-sub-frame-normalisation
  (testing "bare query-v → the caller-supplied default frame"
    (is (= [:rf/default [:count]]    (rf.conformance/resolve-sub :rf/default [:count])))
    (is (= [:rf/default [:count 5]]  (rf.conformance/resolve-sub :rf/default [:count 5]))
        "a 2-elt query whose 2nd elt is NOT a vector is a plain query-v, not [frame q]")
    (is (= [:other-frame [:count]]   (rf.conformance/resolve-sub :other-frame [:count]))
        "default-frame is a parameter — no baked-in frame keyword"))

  (testing "[frame-id [query-v]] → explicit frame (default ignored)"
    (is (= [:frame-2 [:count 5]]
           (rf.conformance/resolve-sub :rf/default [:frame-2 [:count 5]])))))
