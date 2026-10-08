(ns re-frame.story.ui.shell-poll-tick-cljs-test
  "The shell's 500 ms poll runs two detectors, and a throw in the
  fingerprint half must not starve the watch-mode half: called bare, a
  throw from `resolution-fingerprints` (`:rf.error/story-missing-arg`)
  would abort the tick before `watch-mode-tick!` ran, so watch mode would
  silently stop re-running while its eye icon still read 'watching'."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.trace :as rf.trace]
            [re-frame.story.ui.shell :as rf.story.ui.shell]))

(def ^:private poll-tick! @#'rf.story.ui.shell/poll-tick!)

(deftest fingerprint-throw-does-not-starve-watch-mode
  (testing "a throwing fingerprint pass is caught and reported, and the
            watch-mode pass still runs on the same tick"
    (let [errors      (atom [])
          watch-calls (atom 0)]
      (with-redefs [rf.story.ui.shell/detect-and-tick!
                    (fn [] (throw (ex-info "fingerprint compile failed"
                                           {:rf.error/id :rf.error/story-missing-arg})))
                    rf.story.ui.shell/detect-watch-drift!
                    (fn [] (swap! watch-calls inc) nil)
                    rf.trace/emit-error!
                    (fn [op tags] (swap! errors conj [op (:where tags) (:recovery tags)]) nil)]
        (poll-tick!)
        (is (= 1 @watch-calls)
            "watch-mode-tick! still ran after the fingerprint pass threw")
        (is (= [[:rf.story.shell/hot-reload-tick-failed
                 :rf.story.shell/detect-and-tick!
                 :next-tick-retry]]
               @errors)
            "the throw is reported through emit-error!, not swallowed")))))
