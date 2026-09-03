(ns re-frame.conformance-dsl-cljs-test
  "Focused, host-neutral unit tests for the conformance handler-body DSL
  evaluator (rf2-xurchk consolidation).

  The `re-frame.conformance` DSL interpreter is `.cljc`, so `resolve-value*`,
  `realise-event-handler`, and `realise-event-fx-handler` MUST behave
  identically on both hosts. Before rf2-xurchk this coverage was DUPLICATED
  and DRIFTED across `conformance_dsl_test.clj` (JVM, fuller) and
  `conformance_dsl_cljs_test.cljs` (CLJS, a strict subset that dropped
  several cases). This single `.cljc` carries the SUPERSET and runs on both.

  Naming note: the `-cljs-test` suffix is a test-DISCOVERY constraint, not a
  host restriction — the CLJS `:node-test` build discovers `cljs-test$`, and
  that suffix also satisfies the JVM cognitect runner's `.*-test$`, so this
  ONE file runs on BOTH hosts. The corpus runner covers the DSL end-to-end
  via real fixtures; these target individual DSL shapes."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.conformance :as rf.conformance]))

;; ---- :event-arg / :get-event-arg split (rf2-xb5o / rf2-pz9f) -------------
;;
;; Per Mike's resolution of rf2-pz9f:
;;   [:event-arg n]                  — n-th event arg
;;   [:event-arg n default-val]      — n-th event arg, default-val if nil
;;                                     (UNCONDITIONAL: no type-dispatch even
;;                                     when default-val is a keyword and the
;;                                     n-th arg is a map.)
;;   [:get-event-arg n :key]         — key-access into n-th event arg
;;   [:get-event-arg n :key default] — key-access with default if missing/nil
;;
;; The regression-guard below ensures we never re-introduce the prior
;; type-dispatch overload where a keyword 3rd arg + map value silently meant
;; "(get value keyword)" instead of "default-for-nil".

(deftest event-arg-no-key-access-overload
  (testing ":event-arg's 3rd element is unconditionally default-for-nil"
    ;; Event: [:some-id {:foo 99}]. The map is the 1st event arg (index 1;
    ;; index 0 is the event-id).
    (let [ctx {:event [:some-id {:foo 99}]}]
      (testing "with a non-nil map arg, returns the arg verbatim"
        ;; Pre-rf2-xb5o: this returned 99 (key-access overload).
        ;; Post-rf2-xb5o: the keyword 3rd arg is a default-for-nil and never
        ;; fires because the value is non-nil — so the map is returned as-is.
        (is (= {:foo 99}
               (rf.conformance/resolve-value* [:event-arg 1 :foo] ctx))
            "[:event-arg n :foo] with a map arg must NOT do key-access")))

    (testing "with nil arg, default-val is returned (keyword default works)"
      ;; The arg at index 1 doesn't exist (event has only :some-id). The
      ;; keyword default-val IS returned because v is nil.
      (let [ctx {:event [:some-id]}]
        (is (= :foo
               (rf.conformance/resolve-value* [:event-arg 1 :foo] ctx))
            "[:event-arg n :foo] with nil arg returns :foo as default-for-nil")))

    (testing "with nil arg, non-keyword default-val is returned"
      (let [ctx {:event [:some-id]}]
        (is (= {} (rf.conformance/resolve-value* [:event-arg 1 {}] ctx)))
        (is (= 0  (rf.conformance/resolve-value* [:event-arg 1 0] ctx)))
        (is (= [] (rf.conformance/resolve-value* [:event-arg 1 []] ctx)))))

    (testing "two-element form [:event-arg n] returns the n-th arg unchanged"
      (let [ctx {:event [:some-id {:foo 99}]}]
        (is (= {:foo 99}
               (rf.conformance/resolve-value* [:event-arg 1] ctx))))
      (let [ctx {:event [:some-id]}]
        (is (= nil (rf.conformance/resolve-value* [:event-arg 1] ctx)))))))

(deftest get-event-arg-key-access
  (testing ":get-event-arg extracts a key from a map arg"
    (let [ctx {:event [:some-id {:foo 99 :bar "x"}]}]
      (is (= 99  (rf.conformance/resolve-value* [:get-event-arg 1 :foo] ctx)))
      (is (= "x" (rf.conformance/resolve-value* [:get-event-arg 1 :bar] ctx)))
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

;; ---- :return-raw — verbatim (possibly-malformed) effect-map return --------
;;
;; Per rf2-xqt6v: the proactive fx shape-policing categories
;; (:rf.error/effect-map-shape cases a/b/c, :rf.error/effect-handler-bad-return)
;; need a handler that RETURNS a malformed effect-map. The :set / :update / :fx
;; ops always build a well-shaped map, so :return-raw is the only DSL path
;; that yields a literal value verbatim. These tests pin the realiser's
;; contract directly (the corpus fixtures exercise it end-to-end).

(deftest return-raw-routes-through-event-fx
  (testing "a body carrying :return-raw is realised as event-fx (so the raw
            return reaches the fx shape-policing site, not event-db)"
    (let [[kind _handler] (rf.conformance/realise-event-handler
                            [[:return-raw {:db {:x 1}}]])]
      (is (= :fx kind)
          ":return-raw must force the event-fx handler shape"))))

(deftest return-raw-returns-value-verbatim
  (testing "the realised event-fx handler returns the literal value verbatim"
    (let [handler (rf.conformance/realise-event-fx-handler [[:return-raw {:db {:x 1}}]])]
      (is (= {:db {:x 1}}
             (handler {:db {}} [:evt])))))

  (testing "malformed shapes are preserved verbatim — NOT well-shaped away"
    (testing "(a) bad top-level key"
      (let [handler (rf.conformance/realise-event-fx-handler
                      [[:return-raw {:db {:x 1} :http {:url "/api"}}]])]
        (is (= {:db {:x 1} :http {:url "/api"}}
               (handler {:db {}} [:evt]))
            "the non-:db/:fx key survives — the policing site (not the DSL) drops it")))

    (testing "(b) non-sequential :fx value"
      (let [handler (rf.conformance/realise-event-fx-handler [[:return-raw {:fx :oops}]])]
        (is (= {:fx :oops} (handler {:db {}} [:evt])))))

    (testing "(c) malformed :fx entry — bare keyword among well-shaped tuples"
      (let [handler (rf.conformance/realise-event-fx-handler
                      [[:return-raw {:fx [[:good {}] :oops [:good2 {}]]}]])]
        (is (= {:fx [[:good {}] :oops [:good2 {}]]}
               (handler {:db {}} [:evt])))))

    (testing "(c) surplus 3-field :fx entry"
      (let [handler (rf.conformance/realise-event-fx-handler
                      [[:return-raw {:fx [[:sink {:used true} {:dropped true}]]}]])]
        (is (= {:fx [[:sink {:used true} {:dropped true}]]}
               (handler {:db {}} [:evt])))))

    (testing "non-map / non-nil return (the bad-return path)"
      (let [vec-handler (rf.conformance/realise-event-fx-handler
                          [[:return-raw [[:dispatch [:other]]]]])
            nil-handler (rf.conformance/realise-event-fx-handler [[:return-raw nil]])]
        (is (= [[:dispatch [:other]]] (vec-handler {:db {}} [:evt]))
            "a vector return survives verbatim for the bad-return policing site")
        (is (nil? (nil-handler {:db {}} [:evt]))
            "nil survives verbatim — the documented legal no-op"))))

  (testing "reflection forms inside the raw value still resolve"
    (let [handler (rf.conformance/realise-event-fx-handler
                    [[:return-raw {:db {:from-event [:event-arg 1]}}]])]
      (is (= {:db {:from-event 42}}
             (handler {:db {}} [:evt 42]))
          "[:event-arg n] resolves even inside a :return-raw literal"))))

;; ---- shared harness primitives (rf2-wy414k) -------------------------------
;;
;; `normalize-event-handler`, `collect-cofx-keys`, `realise-cofx-supplier`,
;; `submap?`, `check-trace-emissions`, and `resolve-sub` moved OUT of the
;; per-runner private copies (the core corpus runner + the schemas artefact
;; runner both duplicated them) INTO this `.cljc` shared owner so they run once,
;; identically, on both hosts. These target the primitives directly.

(deftest normalize-event-handler-collapses-body-shape
  (testing ":db body-shape is lifted to the single (cofx → effects-map) form"
    (let [pair    [:db (fn [db event] (assoc db :seen event))]
          handler (rf.conformance/normalize-event-handler pair)]
      (is (= {:db {:seen [:evt 7]}}
             (handler {:db {}} [:evt 7]))
          ":db handler reads db from coeffects and lowers the new db into {:db …}")))

  (testing ":fx body-shape passes through unchanged (already the single form)"
    (let [fx-handler (fn [_cofx _event] {:fx [[:noop {}]]})
          pair       [:fx fx-handler]]
      (is (identical? fx-handler (rf.conformance/normalize-event-handler pair))
          ":fx handler is returned verbatim — no wrapping"))))

(deftest collect-cofx-keys-walks-nested-steps
  (testing "pulls every [:cofx-key K] ref across nested body steps, as a set"
    (is (= #{:app-version :user}
           (rf.conformance/collect-cofx-keys
             [[:set [:v]  [:cofx-key :app-version]]
              [:fx  :sink [:cofx-key :user]]]))))

  (testing "no cofx refs → empty set"
    (is (= #{} (rf.conformance/collect-cofx-keys [[:noop]]))))

  (testing "duplicate refs collapse (set semantics)"
    (is (= #{:a}
           (rf.conformance/collect-cofx-keys
             [[:set [:x] [:cofx-key :a]] [:set [:y] [:cofx-key :a]]])))))

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
    (is (= [:frame-2 [:count]]
           (rf.conformance/resolve-sub :rf/default [:frame-2 [:count]])))
    (is (= [:frame-2 [:count 5]]
           (rf.conformance/resolve-sub :rf/default [:frame-2 [:count 5]])))))
