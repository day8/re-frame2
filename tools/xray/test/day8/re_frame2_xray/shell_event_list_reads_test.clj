(ns day8.re-frame2-xray.shell-event-list-reads-test
  "The L2 event list does not read the relative-time anchor.

  Every L2 row's `timestamp` column renders ABSOLUTE wall-clock time,
  so no row consumes a `now` anchor, and a subscription to
  `:rf.xray/relative-time-now-ms` on the list would recompute a max over
  the whole event buffer for a value nothing renders.

  A SOURCE-TEXT guard (JVM, the fast `clojure -M:test` gate) over
  `shell.cljs`, the namespace that renders the list. It matches a
  subscription CALL — `rf/subscribe` or `rf.fresco/sub` followed by the
  query vector — so prose that names the sub does not trip it. The
  control runs the same pattern builder for a query the list really
  reads, so a zero means absence rather than a pattern or a path that
  matches nothing."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn- shell-source []
  ;; `src` is a classpath `:paths` root, so the shell is a classpath
  ;; resource on the JVM whatever the working directory.
  (some-> (io/resource "day8/re_frame2_xray/shell.cljs") slurp))

(defn- read-pattern
  "A subscription call reading exactly `query-id` with no parameters."
  [query-id]
  (re-pattern (str "(?:rf/subscribe|rf\\.fresco/sub)\\s*\\[\\s*"
                   (java.util.regex.Pattern/quote (str query-id))
                   "\\s*\\]")))

(deftest the-event-list-does-not-read-the-relative-time-anchor
  (let [src (shell-source)]
    (is (string? src) "shell.cljs resolves as a classpath resource")
    (testing "CONTROL — the same pattern finds a query the list does read"
      (is (seq (re-seq (read-pattern :rf.xray/filtered-event-bundles) (str src)))))
    (is (empty? (re-seq (read-pattern :rf.xray/relative-time-now-ms) (str src)))
        (str "shell.cljs subscribes to :rf.xray/relative-time-now-ms, but the "
             "L2 rows render absolute time and read no anchor"))))
