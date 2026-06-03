(ns day8.re-frame2-xray.panels.machines.topology-view-cljs-test
  "Pure-data tests for the topology-view composition helpers (rf2-vcnvj).
  Focuses on `static-context-shape` — the root-Context-chrome projection
  the Static-Machines topology path feeds the chart so the root context
  renders on the blank-state topology without a live snapshot."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-xray.panels.machines.topology-view :as tv]))

(deftest static-context-shape-maps-keys-to-type-captions
  (testing "rf2-vcnvj — derives `(key → type-caption)` from the
            definition's declared `:data`, NOT the live values (the door
            machine's `{:opened-count 0 :held-open? false :trail []}`
            shape)."
    (let [def   {:initial :locked
                 :data    {:opened-count 0 :held-open? false :trail []
                           :note "x" :tag :a :nested {} :many #{}}
                 :states  {:locked {}}}
          shape (tv/static-context-shape def)]
      (is (= {:opened-count "number"
              :held-open?   "boolean"
              :trail        "vector"
              :note         "string"
              :tag          "keyword"
              :nested       "map"
              :many         "set"}
             shape)
          "each key maps to its value's type caption (shape, not value)"))))

(deftest static-context-shape-nil-when-no-data
  (testing "rf2-vcnvj — a machine with no `:data` yields nil so the root
            Context panel stays hidden."
    (is (nil? (tv/static-context-shape {:initial :a :states {:a {}}})))
    (is (nil? (tv/static-context-shape {:initial :a :data nil :states {:a {}}})))
    (is (nil? (tv/static-context-shape nil)))))
