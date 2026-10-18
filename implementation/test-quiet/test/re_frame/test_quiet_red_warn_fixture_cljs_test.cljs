(ns re-frame.test-quiet-red-warn-fixture-cljs-test
  "Armed child targets for small and pipe-sized red output: warning replay
  and a FAIL block."
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

(def ^:private large-fail-fixture-armed?
  (boolean (some-> (.. js/process -env -RF2_TQ_RED_FAIL_VOLUME)
                   (= "1"))))

(defn- large-fail-actual
  "Several pipe buffers of text ending in the tail marker. Built here rather
  than in the assertion, whose quoted form the FAIL block's `expected:` line
  prints ahead of the value, so only the value carries the marker."
  []
  (str "RED-FAIL-VOLUME-HEAD " (apply str (repeat (* 1024 1024) "x"))
       " RED-FAIL-VOLUME-TAIL-MARKER"))

(deftest large-fail-block-when-armed
  (when large-fail-fixture-armed?
    ;; Touching `process.stdout` puts a POSIX stdout pipe in non-blocking
    ;; mode, so the FAIL block below meets the short writes a real run does.
    (js/console.log "RED-FAIL-VOLUME-STDOUT-OPENED"))
  ;; Unarmed both sides are nil; armed, the FAIL block prints the large value.
  (is (= (when large-fail-fixture-armed? :expected)
         (when large-fail-fixture-armed? (large-fail-actual)))
      "armed fixture fails with a FAIL block larger than a pipe buffer"))
