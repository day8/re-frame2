(ns day8.re-frame2-causa.panels.machines.xyflow-style-cljs-test
  "Style-map tests for the xyflow per-node + per-edge style provider
  (rf2-uwvyj · spec/021 §17.4.2 + §17.4.3 + §17.4.5 + §6.2 Case C ·
  Figma reconcile rf2-ad7zx.10)."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
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

(deftest node-style-current-is-accent-double-circle-with-pulse
  ;; rf2-ad7zx.10 — Figma reconcile (§6.2 Case C). The TO / current
  ;; state is a DOUBLE-CIRCLE in the mode accent (orange Dynamic / cyan
  ;; Static), not the former green single ring.
  (let [s (xstyle/node-style :current)]
    (testing "circular shape (border-radius 50%) per Figma Case C"
      (is (= "50%" (:border-radius s))))
    (testing "mode-accent border + text"
      (is (= (str "3px solid " (:accent tokens)) (:border s)))
      (is (= (:accent tokens) (:color s))))
    (testing "concentric double-ring via stacked inset box-shadows"
      (is (str/includes? (str (:box-shadow s)) "inset")
          "inner gap + inner ring rendered as inset box-shadows")
      (is (str/includes? (str (:box-shadow s)) (:bg-1 tokens))
          "inner gap painted in :bg-1")
      (is (str/includes? (str (:box-shadow s)) (:accent tokens))
          "inner ring painted in the mode accent"))
    (testing "breathing pulse via the active keyframe"
      (is (re-find #"rf-causa-machine-pulse-active" (str (:animation s)))
          "uses the accent double-circle pulse keyframe"))))

(deftest node-style-from-is-dashed-dim-circle
  ;; rf2-ad7zx.10 — Figma reconcile (§6.2 Case C). The FROM (source)
  ;; state of the focused transition is a dashed/dim circle.
  (let [s (xstyle/node-style :from)]
    (is (= "50%" (:border-radius s)) "circular per Figma Case C")
    (is (= "transparent" (:background s)) "dim — no fill")
    (is (re-find #"dashed" (str (:border s))) "dashed border")
    (is (str/includes? (str (:border s)) (:text-tertiary tokens))
        "dimmed in :text-tertiary")
    (is (= (:text-tertiary tokens) (:color s)) "dimmed label")))

(deftest node-style-compound-is-dashed-container
  ;; rf2-ad7zx.10 — the `active (compound)` grouping container that
  ;; bounds the focused FROM/TO pair (§6.2 Case C).
  (let [s (xstyle/node-style :compound)]
    (is (= "transparent" (:background s)))
    (is (re-find #"dashed" (str (:border s))))
    (is (str/includes? (str (:border s)) (:border-default tokens)))))

(deftest node-style-region-is-dashed-transparent
  (let [s (xstyle/node-style :region)]
    (is (= "transparent" (:background s)))
    (is (re-find #"dashed" (str (:border s))))))

(deftest node-style-region-distinct-from-compound
  ;; The parallel-region container (:region) and the focused-transition
  ;; grouping container (:compound) are SEPARATE kinds — the audit flagged
  ;; that :region was being borrowed for the compound role.
  (is (not= (xstyle/node-style :region)
            (xstyle/node-style :compound))))

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

(deftest edge-style-fired-this-epoch-is-thick-accent
  (let [s (xstyle/edge-style :fired-this-epoch)]
    (is (= (:accent tokens) (:stroke s)))
    (is (= 2 (:stroke-width s)))))

(deftest edge-animated-only-for-fired-this-epoch
  (is (true?  (xstyle/animated? :fired-this-epoch)))
  (is (false? (xstyle/animated? :registered)))
  (is (false? (xstyle/animated? :registered-traversed))))

;; ---- edge-label-style ---------------------------------------------------

(deftest edge-label-style-uses-secondary-text-color
  (is (= (:text-secondary tokens) (:fill xstyle/edge-label-style))))
