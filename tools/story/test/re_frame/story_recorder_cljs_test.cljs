(ns re-frame.story-recorder-cljs-test
  "CLJS tests for the Test Codegen recorder's platform-dependent paths — the
  `now-ms*` clock and `start-recording!` behind `append` and the start/stop
  cycle — and the snippet read back through the CLJS reader. The pure
  predicates and transitions are covered on the JVM by
  `re-frame.story-recorder-test`; the Reagent mirror and modal by
  `re-frame.story.ui.recorder-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [cljs.reader :as edn]
            [clojure.string :as str]
            [re-frame.story.recorder :as rf.story.recorder]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-recorder! [f]
  (rf.story.recorder/clear!)
  (f))

(use-fixtures :each reset-recorder!)

;; ---- pure state machine --------------------------------------------------

(deftest append-captures-recordable-events
  (let [s0 (rf.story.recorder/start rf.story.recorder/initial-state :story.x/y 0)
        s1 (-> s0
               (rf.story.recorder/append [:counter/inc])
               (rf.story.recorder/append [:rf.assert/path-equals [:a] 1])
               (rf.story.recorder/append [:counter/dec]))]
    (is (= [[:counter/inc] [:counter/dec]] (:events s1)))))

;; ---- impure entrypoints --------------------------------------------------

(deftest start-and-stop-cycle
  (rf.story.recorder/start-recording! :story.x/y 0)
  (is (rf.story.recorder/recording?))
  (rf.story.recorder/record-event! [:counter/inc])
  (rf.story.recorder/record-event! [:counter/dec])
  (rf.story.recorder/stop-recording!)
  (is (not (rf.story.recorder/recording?)))
  (is (= [[:counter/inc] [:counter/dec]]
         (rf.story.recorder/recorded-events))))

;; ---- mid-recording assertion insertion ----------------------------------

(deftest insert-assertion!-interleaves-with-recorded-events
  (rf.story.recorder/start-recording! :story.x/y 0)
  (rf.story.recorder/record-event! [:counter/inc])
  (rf.story.recorder/insert-assertion! :rf.assert/sub-equals
                              {:sub [:counter] :expected 1})
  (rf.story.recorder/record-event! [:counter/inc])
  (rf.story.recorder/insert-assertion! [:rf.assert/no-warnings])
  (rf.story.recorder/stop-recording!)
  (is (= [[:counter/inc]
          [:rf.assert/sub-equals [:counter] 1]
          [:counter/inc]
          [:rf.assert/no-warnings]]
         (rf.story.recorder/recorded-events))))

(deftest gen-play-snippet-roundtrips
  (testing "the snippet reads back through the CLJS reader with the public
            :script slot, each event wrapped as a [:dispatch-sync <event>] step"
    (let [events [[:counter/inc] [:auth/login {:id 1}]]
          snip   (rf.story.recorder/gen-play-snippet events {:variant-id :story.x/y})
          steps  (-> (edn/read-string snip) (nth 2) :script :script)]
      (is (not (str/includes? snip ":play-script")))
      (is (= (mapv #(vector :dispatch-sync %) events) steps)))))
