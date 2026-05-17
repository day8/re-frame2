(ns re-frame.story.play.ci-runner-test
  "Pure unit tests for the Story `:play-script` CI-as-test discovery +
  projection seams (rf2-3qcxk).

  All tests are JVM-runnable. The CLJS-only `install-ci-hooks!` is
  exercised by the browser-side runner in
  `examples/scripts/serve-and-run-story-play-scripts.cjs`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.play.ci-runner :as ci]
            [re-frame.story.play.runner    :as runner]
            [re-frame.story.registrar      :as registrar]))

;; ---- fixtures -------------------------------------------------------------

(defn- reset-registrar [test-fn]
  (registrar/clear-all!)
  (test-fn))

(use-fixtures :each reset-registrar)

;; ---- has-play-script? ----------------------------------------------------

(deftest has-play-script-missing
  (testing "has-play-script? is false when :play-script is absent"
    (is (false? (ci/has-play-script? {})))
    (is (false? (ci/has-play-script? {:events [[:foo]]})))))

(deftest has-play-script-empty
  (testing "has-play-script? is false for empty vectors / maps"
    (is (false? (ci/has-play-script? {:play-script []})))
    (is (false? (ci/has-play-script? {:play-script {:script []}})))
    (is (false? (ci/has-play-script? {:play-script {}})))))

(deftest has-play-script-bare-vector
  (testing "has-play-script? is true for a non-empty bare vector"
    (is (true? (ci/has-play-script? {:play-script [[:dispatch [:foo]]]})))))

(deftest has-play-script-map-form
  (testing "has-play-script? is true for a map with at least one step"
    (is (true? (ci/has-play-script?
                 {:play-script {:script [[:dispatch [:foo]]]
                                :auto-run? true}})))))

;; ---- variants-with-play-scripts ------------------------------------------

(deftest discovery-from-injected-registrations
  (testing "discovery from an injected `{id → body}` map filters bodies
            without `:play-script` and sorts the result"
    (let [regs {:story.a/with-script    {:play-script [[:dispatch [:foo]]]}
                :story.b/without-script {:events [[:bar]]}
                :story.c/with-map-form  {:play-script
                                         {:script [[:wait 0]] :auto-run? true}}
                :story.d/empty-script   {:play-script []}
                :story.e/empty-map      {:play-script {:script []}}}]
      (is (= [:story.a/with-script :story.c/with-map-form]
             (ci/variants-with-play-scripts regs))))))

(deftest discovery-from-live-registrar
  (testing "no-arg discovery reads from the live Story registrar
            and respects re-registrations"
    ;; Inject directly into the side-table — the schema-validated
    ;; reg-variant* path needs the canonical vocabulary which is
    ;; out of scope for a discovery test.
    (swap! registrar/kind->id->body assoc-in
           [:variant :story.t/script]
           {:play-script [[:dispatch [:foo]]]})
    (swap! registrar/kind->id->body assoc-in
           [:variant :story.t/no-script]
           {:events [[:foo]]})
    (is (= [:story.t/script] (ci/variants-with-play-scripts)))

    ;; A third variant with a `:play-script` lands in sorted order.
    (swap! registrar/kind->id->body assoc-in
           [:variant :story.t/another]
           {:play-script {:script [[:wait 0]]}})
    (is (= [:story.t/another :story.t/script]
           (ci/variants-with-play-scripts)))))

(deftest discovery-with-zero-variants
  (testing "discovery returns the empty vector when nothing is registered"
    (is (= [] (ci/variants-with-play-scripts)))))

;; ---- play-script-summary -------------------------------------------------

(deftest summary-from-map-body
  (testing "summary derives name + script-len + auto-run? from the body"
    (swap! registrar/kind->id->body assoc-in
           [:variant :story.s/named]
           {:play-script {:name      "happy-path"
                          :auto-run? false
                          :script    [[:dispatch [:a]]
                                      [:wait 0]
                                      [:assert-db [:k] 1]]}})
    (is (= {:variant-id :story.s/named
            :name       "happy-path"
            :script-len 3
            :auto-run?  false}
           (ci/play-script-summary :story.s/named)))))

(deftest summary-from-bare-vector-body
  (testing "summary defaults :auto-run? to true when the body is bare"
    (swap! registrar/kind->id->body assoc-in
           [:variant :story.s/bare]
           {:play-script [[:dispatch [:foo]]]})
    (let [s (ci/play-script-summary :story.s/bare)]
      (is (= :story.s/bare (:variant-id s)))
      (is (= 1 (:script-len s)))
      (is (true? (:auto-run? s)))
      (is (nil? (:name s))))))

(deftest summary-missing-variant-yields-empty-spec
  (testing "summary for an unknown variant returns the empty-script shape"
    (is (= {:variant-id :story.s/missing
            :name       nil
            :script-len 0
            :auto-run?  true}
           (ci/play-script-summary :story.s/missing)))))

;; ---- ci-context ----------------------------------------------------------

(deftest ci-context-shape
  (testing "ci-context bundles the variant list + per-variant summaries"
    (swap! registrar/kind->id->body assoc-in
           [:variant :story.c/a]
           {:play-script [[:dispatch [:a]]]})
    (swap! registrar/kind->id->body assoc-in
           [:variant :story.c/b]
           {:play-script {:script [[:wait 0]] :auto-run? false :name "b"}})
    (let [ctx (ci/ci-context)]
      (is (= [:story.c/a :story.c/b] (:variants ctx)))
      (is (= 2 (count (:summaries ctx))))
      (is (= :story.c/a (:variant-id (first (:summaries ctx)))))
      (is (= "b"        (:name       (second (:summaries ctx))))))))

;; ---- terminal? -----------------------------------------------------------

(deftest terminal?-recognises-pass-and-fail-only
  (is (true?  (ci/terminal? {:status :pass})))
  (is (true?  (ci/terminal? {:status :fail})))
  (is (false? (ci/terminal? {:status :running})))
  (is (false? (ci/terminal? {:status :idle})))
  (is (false? (ci/terminal? nil))))

;; ---- project-state -------------------------------------------------------

(deftest project-state-strips-script-and-pr-strs-vals
  (testing "project-state returns a stable shape, pr-strs :expected /
            :actual to keep them JSON-safe across runtimes"
    (let [state {:status      :fail
                 :step-idx    2
                 :total       3
                 :failures    1
                 :name        "n"
                 :started-ms  100
                 :finished-ms 200
                 :script      [[:assert-db [:k] 1]
                               [:assert-db [:k] 2]]
                 :results     [(runner/step-pass 0 [:dispatch [:a]])
                               (runner/step-fail 1 [:assert-db [:k] 2]
                                                 {:expected 2
                                                  :actual   1
                                                  :message  "msg"})
                               (runner/step-exception 2 [:dispatch [:b]] "boom")]}
          out   (ci/project-state state)]
      (is (= :fail (:status out)))
      (is (= 2     (:step-idx out)))
      (is (= 3     (:total out)))
      (is (= 1     (:failures out)))
      (is (= "n"   (:name out)))
      (is (= 100   (:started-ms out)))
      (is (= 200   (:finished-ms out)))
      (is (nil? (:script out)) "script slot is stripped")
      (is (= 3 (count (:results out))))
      (let [r1 (nth (:results out) 1)]
        (is (false? (:passed? r1)))
        (is (= "2" (:expected r1)) "expected is pr-str'd for JSON safety")
        (is (= "1" (:actual   r1)))
        (is (= "msg" (:message r1)))))))

(deftest project-state-nil-yields-nil
  (is (nil? (ci/project-state nil))))
