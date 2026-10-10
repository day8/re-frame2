;;;; tests/runtime/recorder_test.clj — the signal recorder's source-level
;;;; contract. Its behaviour against a real frame (change dedup, a :changes
;;;; stop, the ring cap) is the fixture's runtime_recording_test.cljs; this
;;;; pins what that cannot reach: rAF teardown on the stop paths, the
;;;; next-tick fallback, the :ms and predicate stops, the default stop window,
;;;; and that the recorder never mutates the app.
;;;;
;;;; Run: bb tests/runtime/recorder_test.clj

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns recorder-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(deftest recorder-schedules-stops-and-tears-down
  (let [drive (rt/defn-named 'drive-recording!)
        tick  (rt/defn-named 'recording-sampler-tick!)
        start (rt/defn-named 'start-recording!)]
    (is (rt/mentions? 'js/cancelAnimationFrame (rt/defn-named 'stop-recording!))
        "stop-recording! must cancel the rAF loop")
    (is (rt/mentions? 'js/cancelAnimationFrame (rt/defn-named 'read-recording))
        "read-recording {:stop true} must cancel the rAF loop")
    (is (rt/mentions? 'js/requestAnimationFrame drive) "the driver samples on requestAnimationFrame")
    (is (rt/form-contains? #(and (symbol? %) (str/includes? (str %) "next-tick")) drive)
        "and falls back to next-tick when rAF is absent")
    (is (rt/mentions? :ms tick) ":ms stop condition handled")
    (is (rt/mentions? :changes tick) ":changes stop condition handled")
    (is (rt/mentions? 'pred-fn tick) "predicate stop condition handled")
    (is (rt/mentions? 'default-recording-stop-ms start)
        "a recording given no stop defaults to a wall-clock window")
    (is (rt/mentions? :no-signals start) "start-recording! refuses an empty signal set")))

(deftest recorder-source-is-read-only
  (let [src (->> '[sample-one-signal sample-signals recording-sampler-tick!
                   drive-recording! start-recording! read-recording
                   stop-recording! recording-info]
                 (map (comp pr-str rt/defn-named))
                 (str/join "\n"))]
    (is (= [] (filter #(str/includes? src %)
                      ["pair-dispatch" "replace-frame-state!" "app-db-reset!" ".dispatchEvent"
                       ".setAttribute" "restore-epoch" ".innerHTML"]))
        "the recorder must never dispatch, reset, restore or write the DOM")
    (is (every? #(str/includes? src %) ["app-db-value" "querySelector" "activeElement"])
        "control: the scan reads the samplers, which read app-db, the DOM and focus")))

(let [{:keys [fail error]} (run-tests 'recorder-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
