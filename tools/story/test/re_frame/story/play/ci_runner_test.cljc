(ns re-frame.story.play.ci-runner-test
  "The Story `:script` CI-as-test discovery and projection seams. The
  CLJS-only `install-ci-hooks!` is exercised by the browser-side runner in
  `examples/scripts/serve-and-run-story-play-scripts.cjs`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.story.play.ci-runner :as rf.story.play.ci-runner]
            [re-frame.story.play.runner    :as rf.story.play.runner]
            [re-frame.story.registrar      :as rf.story.registrar]))

(defn- reset-registrar [test-fn]
  (rf.story.registrar/clear-all!)
  (test-fn))

(use-fixtures :each reset-registrar)

;; ---- variants-with-play-scripts ------------------------------------------

(defn- inject!
  "Put `id->body` straight into the side-table under `kind`: the
  schema-validated reg-variant* path needs the canonical vocabulary, which
  discovery does not."
  [kind id->body]
  (swap! rf.story.registrar/kind->id->body update kind merge id->body))

(deftest discovery-reads-the-compiled-plays
  (inject! :fragment {:fragment.ci/opener {:script [[:dispatch-sync [:fragment/step]]]}})
  (inject! :variant
           {:story.a/with-script    {:script [[:dispatch [:foo]]]}
            :story.b/without-script {:setup [[:bar]]}
            :story.c/with-map-form  {:script {:script [[:wait 0]] :auto-run? true}}
            :story.d/empty-script   {:script []}
            :story.e/empty-map      {:script {:script []}}
            :story.f/empty-plays    {:plays []}
            :story.g/compose-only   {:compose [:fragment.ci/opener]}})
  (is (= [:story.a/with-script :story.c/with-map-form :story.g/compose-only]
         (rf.story.play.ci-runner/variants-with-play-scripts))
      "sorted; a variant whose only script a composed fragment supplies runs a play")
  (is (= [{:variant-id :story.g/compose-only :play-key nil :name nil
           :script-len 1 :auto-run? true}]
         (filterv #(= :story.g/compose-only (:variant-id %))
                  (rf.story.play.ci-runner/ci-rows)))))

;; ---- terminal? -----------------------------------------------------------

(deftest terminal?-separates-verdicts-from-in-flight-states
  (is (true?  (rf.story.play.ci-runner/terminal? {:status :cannot-run}))
      ":cannot-run is a terminal verdict, so a refused play never reads as a hang")
  (is (false? (rf.story.play.ci-runner/terminal? {:status :running}))))

;; ---- project-state -------------------------------------------------------

(deftest project-state-strips-script-and-pr-strs-vals
  ;; no :script slot; :expected / :actual are pr-str'd to stay JSON-safe
  (is (= {:status      :fail
          :step-idx    2
          :total       3
          :failures    1
          :name        "n"
          :started-ms  100
          :finished-ms 200
          :results     [{:idx 0 :type :dispatch :passed? true}
                        {:idx 1 :type :assert-db :passed? false
                         :message "msg" :expected "2" :actual "1"}
                        {:idx 2 :type :dispatch :passed? false
                         :message "boom" :exception true}]}
         (rf.story.play.ci-runner/project-state
           {:status      :fail
            :step-idx    2
            :total       3
            :failures    1
            :name        "n"
            :started-ms  100
            :finished-ms 200
            :script      [[:assert-db [:k] 1] [:assert-db [:k] 2]]
            :results     [(rf.story.play.runner/step-pass 0 [:dispatch [:a]])
                          (rf.story.play.runner/step-fail 1 [:assert-db [:k] 2]
                                                          {:expected 2 :actual 1 :message "msg"})
                          (rf.story.play.runner/step-exception 2 [:dispatch [:b]] "boom")]}))))

(deftest project-state-nil-yields-nil
  (is (nil? (rf.story.play.ci-runner/project-state nil))))

;; ---- multi-play ----------------------------------------------------------

(deftest ci-rows-enumerates-plays-per-variant
  ;; one row per play, in declaration order; a bare script is one row keyed nil
  (inject! :variant
           {:story.a/single  {:script {:name "single-named" :script [[:dispatch [:a]]]}}
            :story.b/multi   {:plays [{:name "happy" :script [[:dispatch [:b1]]]}
                                      {:name "error" :script [[:dispatch [:b2]] [:wait 0]]}]}
            :story.c/no-play {:setup []}
            :story.x/bare    {:script [[:dispatch [:a]]]}})
  (is (= [{:variant-id :story.a/single :play-key "single-named" :name "single-named"
           :script-len 1 :auto-run? true}
          {:variant-id :story.b/multi :play-key "happy" :name "happy"
           :script-len 1 :auto-run? true}
          {:variant-id :story.b/multi :play-key "error" :name "error"
           :script-len 2 :auto-run? false}
          {:variant-id :story.x/bare :play-key nil :name nil
           :script-len 1 :auto-run? true}]
         (rf.story.play.ci-runner/ci-rows))))
