(ns day8.re-frame2-causa.panels.machines.xyflow-style-cljs-test
  "Style-map tests for the xyflow per-node + per-edge style provider
  (rf2-uwvyj · spec/021 §17.4.2 + §17.4.3 + §17.4.5)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa.panels.machines.xyflow-style :as xstyle]
            [day8.re-frame2-causa.theme.tokens :as t :refer [tokens]]))

;; ---- node-style ---------------------------------------------------------

(deftest node-style-standard-shape
  (let [s (xstyle/node-style :standard)]
    (is (= (:bg-2 tokens) (:background s)))
    (is (= "6px" (:border-radius s)))
    (is (= (str "1px solid " (:border-default tokens)) (:border s)))
    (is (= (:text-primary tokens) (:color s)))))

(deftest node-style-final-double-ring
  (let [s (xstyle/node-style :final)]
    (is (= (str "2px solid " (:green tokens)) (:border s)))
    (is (some? (:box-shadow s))
        "final state has the inner gap box-shadow (double-ring)")))

(deftest node-style-current-applies-pulse-animation
  (let [s (xstyle/node-style :current)]
    (is (= (str "2px solid " (:green tokens)) (:border s)))
    (is (some? (:animation s)))
    (is (re-find #"rf-causa-machine-pulse" (str (:animation s)))
        "uses the spec-named pulse keyframe")))

(deftest node-style-region-is-dashed-transparent
  (let [s (xstyle/node-style :region)]
    (is (= "transparent" (:background s)))
    (is (re-find #"dashed" (str (:border s))))))

(deftest node-style-unknown-falls-back-to-standard
  (is (= (xstyle/node-style :standard)
         (xstyle/node-style :something-bogus))))

;; ---- edge-style ---------------------------------------------------------

(deftest edge-style-registered-is-dashed
  (let [s (xstyle/edge-style :registered)]
    (is (= (:text-tertiary tokens) (:stroke s)))
    (is (= 1 (:stroke-width s)))
    (is (= "4 4" (:stroke-dasharray s)))))

(deftest edge-style-registered-traversed-is-solid
  (let [s (xstyle/edge-style :registered-traversed)]
    (is (= (:text-tertiary tokens) (:stroke s)))
    (is (= 1 (:stroke-width s)))
    (is (nil? (:stroke-dasharray s))
        "traversed edges are solid, not dashed")))

(deftest edge-style-fired-this-epoch-is-thick-violet
  (let [s (xstyle/edge-style :fired-this-epoch)]
    (is (= (:accent-violet tokens) (:stroke s)))
    (is (= 2 (:stroke-width s)))))

(deftest edge-animated-only-for-fired-this-epoch
  (is (true?  (xstyle/animated? :fired-this-epoch)))
  (is (false? (xstyle/animated? :registered)))
  (is (false? (xstyle/animated? :registered-traversed))))

;; ---- edge-label-style ---------------------------------------------------

(deftest edge-label-style-uses-secondary-text-color
  (is (= (:text-secondary tokens) (:fill xstyle/edge-label-style))))
