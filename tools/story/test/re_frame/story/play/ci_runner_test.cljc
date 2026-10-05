(ns re-frame.story.play.ci-runner-test
  "Pure unit tests for the Story `:script` CI-as-test discovery +
  projection seams.

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

;; ---- terminal? -----------------------------------------------------------

(deftest terminal?-separates-verdicts-from-in-flight-states
  (is (true?  (rf.story.play.ci-runner/terminal? {:status :pass})))
  (is (true?  (rf.story.play.ci-runner/terminal? {:status :fail})))
  (is (true?  (rf.story.play.ci-runner/terminal? {:status :cannot-run}))
      ":cannot-run is a terminal verdict, so a refused play never reads as a hang")
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
      (is (= {:status      :fail
              :step-idx    2
              :total       3
              :failures    1
              :name        "n"
              :started-ms  100
              :finished-ms 200
              ;; no :script slot; :expected / :actual are pr-str'd
              :results     [{:idx 0 :type :dispatch :passed? true}
                            {:idx 1 :type :assert-db :passed? false
                             :message "msg" :expected "2" :actual "1"}
                            {:idx 2 :type :dispatch :passed? false
                             :message "boom" :exception true}]}
             out)))))

(deftest project-state-nil-yields-nil
  (is (nil? (rf.story.play.ci-runner/project-state nil))))

;; ---- multi-play ----------------------------------------------------------

(deftest has-plays?-recognises-non-empty-plays
  (is (false? (rf.story.play.ci-runner/has-plays? {})))
  (is (false? (rf.story.play.ci-runner/has-plays? {:plays []})))
  (is (true?  (rf.story.play.ci-runner/has-plays? {:plays [{:name "p" :script [[:dispatch [:a]]]}]}))))

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
      (is (= [[:story.a/single "single-named"]
              [:story.b/multi  "happy"]
              [:story.b/multi  "error"]]
             (mapv (fn [r] [(:variant-id r) (:play-key r)]) rows))
          "single + multi(2) = 3 rows, in declaration order within a variant")
      (is (true? (:auto-run? (last rows)))
          "the error row carries its per-play auto-run? flag"))))

(deftest ci-rows-handles-bare-play-script
  (testing "a bare :script (no :name) yields a row with :play-key nil"
    (let [regs {:story.x/bare {:script [[:dispatch [:a]]]}}
          rows (rf.story.play.ci-runner/ci-rows regs)]
      (is (= [{:variant-id :story.x/bare :play-key nil :name nil
               :script-len 1 :auto-run? true}]
             rows)
          "bare scripts default :auto-run? to true"))))

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
      (is (= [[:story.ctx/multi  "a"]
              [:story.ctx/multi  "b"]
              [:story.ctx/single "lone"]]
             (mapv (fn [r] [(:variant-id r) (:play-key r)]) (:rows ctx)))))))
