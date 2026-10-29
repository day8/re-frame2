(ns day8.re-frame2-xray.panels.app-db-diff-format-cljs-test
  "Tests for `app-db-diff-format/display-value`: large string leaves collapse
  to a marker, and a tree that needs no elision passes through IDENTICAL, so
  the downstream `identical?` short-circuits (`engine/project`, the
  inspector's projection memo) still hold. CLJS-only because the leaf is."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-xray.panels.app-db-diff-format :as f]))

(deftest display-value-preserves-identity-when-no-elision-needed
  (let [m {:a 1 :b "short string" :c [1 2 3] :d #{:x :y}}]
    (is (identical? m (f/display-value m))
        "a tree with no large strings returns the same reference")))

(deftest display-value-caches-output-on-slow-path
  ;; A fresh rewrite on every call would hand the engine memo a new tree
  ;; every render.
  (let [big  (apply str (repeat (inc f/display-large-string-threshold) "x"))
        m    {:k big :other 42}
        out1 (f/display-value m)]
    (is (identical? out1 (f/display-value m))
        "repeated calls with the same input return the same rewritten output")
    (is (map? (:rf.size/large-elided (:k out1)))
        "large string collapses to the elision marker")))

(deftest display-value-still-elides-large-strings
  (let [big (apply str (repeat (inc f/display-large-string-threshold) "x"))]
    (is (map? (:rf.size/large-elided (f/display-value big)))
        "top-level large string elides")
    (is (map? (:rf.size/large-elided (first (f/display-value [big]))))
        "large string inside a vector elides")
    (is (map? (:rf.size/large-elided (first (f/display-value #{big}))))
        "large string inside a set elides")))

(deftest display-value-never-realises-a-lazy-seq
  ;; The edn-inspector bounds an uncounted seq itself; this pre-pass runs
  ;; before it on every section, so walking one would run app code inside
  ;; the devtool, or hang the page on `(range)`.
  (let [realised (atom 0)
        lazy     (map (fn [x] (swap! realised inc) x) (range 5000))
        m        {:lazy lazy :endless (range) :big (vec (range 3))}]
    (is (identical? m (f/display-value m))
        "a tree whose only seqs are uncounted passes through identical")
    (is (zero? @realised)
        (str "the pre-scan realised " @realised " elements of a lazy seq"))))
