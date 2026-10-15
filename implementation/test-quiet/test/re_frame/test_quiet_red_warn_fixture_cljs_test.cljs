(ns re-frame.test-quiet-red-warn-fixture-cljs-test
  "Armed child targets for small and pipe-sized warning replay."
  (:require [cljs.test :refer-macros [deftest is]]))

(def ^:private red-warning-fixture-armed?
  (boolean (some-> (.. js/process -env -RF2_TQ_RED_WARN_FIXTURE)
                   (= "1"))))

(deftest warns-then-fails-when-armed
  (when red-warning-fixture-armed?
    (js/console.warn "RED-WARN-FIXTURE-MARKER diagnostic context"))
  (is (or (not red-warning-fixture-armed?) (= :expected :actual))
      "armed fixture fails so the run is RED and the buffer replays"))

(def ^:private large-replay-fixture-armed?
  (boolean (some-> (.. js/process -env -RF2_TQ_RED_REPLAY_VOLUME)
                   (= "1"))))

(deftest large-replay-warns-then-fails-when-armed
  (when large-replay-fixture-armed?
    (let [chunk (apply str (repeat 1024 "x"))]
      ;; Fill exactly 256 entries so neither marker is evicted.
      (dotimes [i 255]
        (js/console.warn (str "RED-REPLAY-VOLUME-" i " " chunk)))
      (js/console.warn (str "RED-REPLAY-TAIL-MARKER " chunk))))
  (is (or (not large-replay-fixture-armed?) (= :expected :actual))
      "armed volume fixture fails so the run is RED and the full ring replays"))
