(ns re-frame.story-recorder-cljs-test
  "CLJS test for the Test Codegen recorder's platform-dependent path: the
  `now-ms*` clock and `start-recording!` behind the start/stop cycle. The
  pure predicates, transitions and codegen are covered on the JVM by
  `re-frame.story-recorder-test`; the Reagent mirror and modal by
  `re-frame.story.ui.recorder-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.story.recorder :as rf.story.recorder]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-recorder! [f]
  (rf.story.recorder/clear!)
  (f))

(use-fixtures :each reset-recorder!)

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
