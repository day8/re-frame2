(ns re-frame.story.play.ci-runner-test
  "Pure unit tests for the Story `:script` CI-as-test discovery +
  projection seams (rf2-3qcxk).

  All tests are JVM-runnable. The CLJS-only `install-ci-hooks!` is
  exercised by the browser-side runner in
  `examples/scripts/serve-and-run-story-play-scripts.cjs`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.play.ci-runner :as rf.story.play.ci-runner]
            [re-frame.story.play.runner    :as rf.story.play.runner]
            [re-frame.story.registrar      :as rf.story.registrar]))

;; ---- fixtures -------------------------------------------------------------

(defn- reset-registrar [test-fn]
  (rf.story.registrar/clear-all!)
  (test-fn))

(use-fixtures :each reset-registrar)

;; ---- has-play-script? ----------------------------------------------------

(deftest has-play-script-missing
  (testing "has-play-script? is false when :script is absent"
    (is (false? (rf.story.play.ci-runner/has-play-script? {})))
    (is (false? (rf.story.play.ci-runner/has-play-script? {:setup [[:foo]]})))))

(deftest has-play-script-empty
  (testing "has-play-script? is false for empty vectors / maps"
    (is (false? (rf.story.play.ci-runner/has-play-script? {:script []})))
    (is (false? (rf.story.play.ci-runner/has-play-script? {:script {:script []}})))
    (is (false? (rf.story.play.ci-runner/has-play-script? {:script {}})))))

(deftest has-play-script-bare-vector
  (testing "has-play-script? is true for a non-empty bare vector"
    (is (true? (rf.story.play.ci-runner/has-play-script? {:script [[:dispatch [:foo]]]})))))

(deftest has-play-script-map-form
  (testing "has-play-script? is true for a map with at least one step"
    (is (true? (rf.story.play.ci-runner/has-play-script?
                 {:script {:script [[:dispatch [:foo]]]
                                :auto-run? true}})))))

;; ---- variants-with-play-scripts ------------------------------------------

(deftest discovery-from-injected-registrations
  (testing "discovery from an injected `{id → body}` map filters bodies
            without `:script` and sorts the result"
    (let [regs {:story.a/with-script    {:script [[:dispatch [:foo]]]}
                :story.b/without-script {:setup [[:bar]]}
                :story.c/with-map-form  {:script
                                         {:script [[:wait 0]] :auto-run? true}}
                :story.d/empty-script   {:script []}
                :story.e/empty-map      {:script {:script []}}}]
      (is (= [:story.a/with-script :story.c/with-map-form]
             (rf.story.play.ci-runner/variants-with-play-scripts regs))))))

(deftest discovery-from-live-registrar
  (testing "no-arg discovery reads from the live Story registrar
            and respects re-registrations"
    ;; Inject directly into the side-table — the schema-validated
    ;; reg-variant* path needs the canonical vocabulary which is
    ;; out of scope for a discovery test.
    (swap! rf.story.registrar/kind->id->body assoc-in
           [:variant :story.t/script]
           {:script [[:dispatch [:foo]]]})
    (swap! rf.story.registrar/kind->id->body assoc-in
           [:variant :story.t/no-script]
           {:setup [[:foo]]})
    (is (= [:story.t/script] (rf.story.play.ci-runner/variants-with-play-scripts)))

    ;; A third variant with a `:script` lands in sorted order.
    (swap! rf.story.registrar/kind->id->body assoc-in
           [:variant :story.t/another]
           {:script {:script [[:wait 0]]}})
    (is (= [:story.t/another :story.t/script]
           (rf.story.play.ci-runner/variants-with-play-scripts)))))

(deftest discovery-with-zero-variants
  (testing "discovery returns the empty vector when nothing is registered"
    (is (= [] (rf.story.play.ci-runner/variants-with-play-scripts)))))

;; ---- ci-context ----------------------------------------------------------

(deftest ci-context-shape
  (testing "ci-context bundles the variant list + per-play rows"
    (swap! rf.story.registrar/kind->id->body assoc-in
           [:variant :story.c/a]
           {:script [[:dispatch [:a]]]})
    (swap! rf.story.registrar/kind->id->body assoc-in
           [:variant :story.c/b]
           {:script {:script [[:wait 0]] :auto-run? false :name "b"}})
    (let [ctx (rf.story.play.ci-runner/ci-context)]
      (is (= [:story.c/a :story.c/b] (:variants ctx)))
      (is (not (contains? ctx :summaries))
          "the legacy per-variant :summaries projection is dropped (rf2-k9u0h)")
      (is (= 2 (count (:rows ctx))))
      (is (= :story.c/a (:variant-id (first (:rows ctx)))))
      (is (= "b"        (:name       (second (:rows ctx))))))))

;; ---- terminal? -----------------------------------------------------------

(deftest terminal?-recognises-pass-and-fail-only
  (is (true?  (rf.story.play.ci-runner/terminal? {:status :pass})))
  (is (true?  (rf.story.play.ci-runner/terminal? {:status :fail})))
  (is (false? (rf.story.play.ci-runner/terminal? {:status :running})))
  (is (false? (rf.story.play.ci-runner/terminal? {:status :idle})))
  (is (false? (rf.story.play.ci-runner/terminal? nil))))

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
                 :results     [(rf.story.play.runner/step-pass 0 [:dispatch [:a]])
                               (rf.story.play.runner/step-fail 1 [:assert-db [:k] 2]
                                                 {:expected 2
                                                  :actual   1
                                                  :message  "msg"})
                               (rf.story.play.runner/step-exception 2 [:dispatch [:b]] "boom")]}
          out   (rf.story.play.ci-runner/project-state state)]
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
  (is (nil? (rf.story.play.ci-runner/project-state nil))))

;; ---- multi-play (rf2-tl7zk) ----------------------------------------------

(deftest has-plays?-recognises-non-empty-plays
  (is (false? (rf.story.play.ci-runner/has-plays? {})))
  (is (false? (rf.story.play.ci-runner/has-plays? {:plays []})))
  (is (true?  (rf.story.play.ci-runner/has-plays? {:plays [{:name "p" :script [[:dispatch [:a]]]}]}))))

(deftest has-any-play?-or-of-both
  (is (false? (rf.story.play.ci-runner/has-any-play? {})))
  (is (true?  (rf.story.play.ci-runner/has-any-play? {:script [[:dispatch [:a]]]})))
  (is (true?  (rf.story.play.ci-runner/has-any-play? {:plays [{:name "p" :script [[:dispatch [:a]]]}]}))))

(deftest discovery-includes-plays-variants
  (testing "variants-with-play-scripts picks up :plays-carrying bodies"
    (let [regs {:story.a/single  {:script [[:dispatch [:a]]]}
                :story.b/multi   {:plays [{:name "p1" :script [[:dispatch [:b1]]]}
                                          {:name "p2" :script [[:dispatch [:b2]]]}]}
                :story.c/none    {:setup []}}]
      (is (= [:story.a/single :story.b/multi]
             (rf.story.play.ci-runner/variants-with-play-scripts regs))))))

(deftest ci-rows-enumerates-plays-per-variant
  (testing "ci-rows produces one row per play; single-script variants produce one row"
    (let [regs {:story.a/single
                {:script {:name "single-named"
                               :script [[:dispatch [:a]]]}}

                :story.b/multi
                {:plays [{:name "happy" :script [[:dispatch [:b1]]]}
                         {:name "error" :script [[:dispatch [:b2]]]
                          :auto-run? true}]}

                :story.c/no-play {:setup []}}
          rows (rf.story.play.ci-runner/ci-rows regs)]
      (is (= 3 (count rows))
          "single + multi(2) = 3 rows total")
      (is (= [[:story.a/single "single-named"]
              [:story.b/multi  "happy"]
              [:story.b/multi  "error"]]
             (mapv (fn [r] [(:variant-id r) (:play-key r)]) rows))
          "rows preserve declaration order within a variant")
      (let [error-row (last rows)]
        (is (= "error" (:play-key error-row)))
        (is (true? (:auto-run? error-row))
            "row carries the per-play auto-run? flag")))))

(deftest ci-rows-handles-bare-play-script
  (testing "a bare :script (no :name) yields a row with :play-key nil"
    (let [regs {:story.x/bare {:script [[:dispatch [:a]]]}}
          rows (rf.story.play.ci-runner/ci-rows regs)]
      (is (= 1 (count rows)))
      (is (nil? (:play-key (first rows))))
      (is (= 1   (:script-len (first rows))))
      (is (true? (:auto-run? (first rows)))
          "bare scripts default :auto-run? to true (legacy contract)"))))

(deftest ci-context-includes-rows
  (testing "ci-context exposes the per-play rows alongside :variants"
    (swap! rf.story.registrar/kind->id->body assoc-in
           [:variant :story.ctx/multi]
           {:plays [{:name "a" :script [[:dispatch [:foo]]]}
                    {:name "b" :script [[:dispatch [:bar]]]}]})
    (swap! rf.story.registrar/kind->id->body assoc-in
           [:variant :story.ctx/single]
           {:script {:name "lone" :script [[:dispatch [:baz]]]}})
    (let [ctx (rf.story.play.ci-runner/ci-context)]
      (is (= [:story.ctx/multi :story.ctx/single] (:variants ctx)))
      ;; rows are per-PLAY — multi(2) + single(1) = 3 rows.
      (is (= 3 (count (:rows ctx))))
      (is (= [[:story.ctx/multi  "a"]
              [:story.ctx/multi  "b"]
              [:story.ctx/single "lone"]]
             (mapv (fn [r] [(:variant-id r) (:play-key r)]) (:rows ctx)))))))
