(ns re-frame.story.ui.canvas-skeleton-cljs-test
  "The canvas loading-skeleton predicate and the viewport-px indicator."
  (:require [cljs.test :refer-macros [are deftest is testing]]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]))

;; Each row's `args` are `[phase first-rendered? assertions-recorded?
;; events-only?]`; a three-element row calls the 3-arity. `=` on a boolean
;; holds the predicate to a real boolean, not merely a truthy value.

(deftest loading-phase-truth-table
  (are [args expected] (= expected (apply rf.story.ui.canvas/loading-phase? args))
    [:loading   false false]       true
    [nil        false false]       false
    ;; Each flag overrides a loading phase.
    [:loading   true  false]       false
    ;; loader-never-completes / loader-rejects park at :loading but record
    ;; an assertion, and the user view must render.
    [:loading   false true]        false
    ;; Events-only variants take the :pre-mount → :ready fast path.
    [:pre-mount false false true]  false))

(deftest loading-skeleton-hiccup-shape
  (testing "hiccup root carries the canonical data-test"
    (let [hiccup (rf.story.ui.canvas/loading-skeleton)
          [_tag props] hiccup]
      (is (= "story-canvas-loading-skeleton" (:data-test props)))
      (is (= "status" (:role props)))
      (is (= "polite" (:aria-live props))))))

(deftest first-rendered-sentinel-round-trip
  (testing "marker round-trips through the per-variant set"
    (rf.story.ui.canvas/reset-first-rendered!)
    (is (false? (rf.story.ui.canvas/variant-first-rendered? :story.x/y)))
    (rf.story.ui.canvas/mark-variant-rendered! :story.x/y)
    (is (true? (rf.story.ui.canvas/variant-first-rendered? :story.x/y)))
    (rf.story.ui.canvas/reset-first-rendered! :story.x/y)
    (is (false? (rf.story.ui.canvas/variant-first-rendered? :story.x/y))))
  (testing "reset all"
    (rf.story.ui.canvas/mark-variant-rendered! :story.a/one)
    (rf.story.ui.canvas/mark-variant-rendered! :story.b/two)
    (is (true? (rf.story.ui.canvas/variant-first-rendered? :story.a/one)))
    (rf.story.ui.canvas/reset-first-rendered!)
    (is (false? (rf.story.ui.canvas/variant-first-rendered? :story.a/one)))
    (is (false? (rf.story.ui.canvas/variant-first-rendered? :story.b/two)))))

(deftest viewport-indicator-elides-for-full
  (is (nil? (rf.story.ui.canvas/viewport-indicator {:label "Full" :width nil :height nil}))))

(deftest viewport-indicator-shows-dims
  (let [[_tag props text] (rf.story.ui.canvas/viewport-indicator {:label "iPhone" :width 375 :height 667})]
    (is (= "story-canvas-viewport-indicator" (:data-test props)))
    (is (= "375 × 667" text))))
