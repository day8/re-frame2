(ns re-frame.story.ui.canvas-skeleton-cljs-test
  "CLJS-side regression net for the canvas loading skeleton + viewport-
  px indicator.

  Surface covered:

  - `loading-phase?`        — pre-mount / mounting / loading → true;
                              ready / error / nil → false; first-
                              rendered? overrides
  - `loading-skeleton`      — hiccup shape carries the canonical
                              `data-test` attribute
  - `mark-variant-rendered!` / `variant-first-rendered?` — sentinel
                              round-trip
  - `viewport-indicator`    — hidden for `:full` (no width/height);
                              shows `\"WxH\"` text for sized presets"
  (:require [cljs.test :refer-macros [are deftest is testing use-fixtures]]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]))

(use-fixtures :each {:before (fn [] (rf.story.ui.canvas/reset-first-rendered!))})

;; ---- loading-phase? -----------------------------------------------------
;;
;; One truth table. Each row's `args` are `[phase first-rendered?
;; assertions-recorded? events-only?]`; a three-element row calls the
;; 3-arity, which callers use when they pass no events-only? flag. `=` on a
;; boolean `expected` holds the predicate to a real boolean, not merely a
;; truthy or falsey value.

(deftest loading-phase-truth-table
  (testing "the skeleton shows only in :pre-mount / :mounting / :loading;
            :ready, :error, a nil phase and an unknown phase show none"
    (are [args expected] (= expected (apply rf.story.ui.canvas/loading-phase? args))
      [:pre-mount      false false] true
      [:mounting       false false] true
      [:loading        false false] true
      [:ready          false false] false
      [:error          false false] false
      [nil             false false] false
      [:something-else false false] false))
  (testing "first-rendered? overrides the phase"
    (are [args expected] (= expected (apply rf.story.ui.canvas/loading-phase? args))
      [:loading   true false] false
      [:pre-mount true false] false))
  (testing "assertions-recorded? overrides the phase: loader-never-completes
            and loader-rejects park the lifecycle at :loading, but the user
            view must render"
    (are [args expected] (= expected (apply rf.story.ui.canvas/loading-phase? args))
      [:loading   false true] false
      [:pre-mount false true] false
      [:mounting  false true] false))
  (testing "events-only? overrides the phase. Events-only variants take the
            lifecycle fast-path (`:pre-mount → :ready` via `mount-ready!`);
            the skeleton must NEVER engage for them, including the brief
            pre-allocate window where `current-state` could still report
            `:pre-mount`. events-only? false is the plain predicate"
    (are [args expected] (= expected (apply rf.story.ui.canvas/loading-phase? args))
      [:pre-mount false false true]  false
      [:mounting  false false true]  false
      [:loading   false false true]  false
      [:ready     false false true]  false
      [:pre-mount false false false] true
      [:ready     false false false] false)))

;; ---- loading-skeleton hiccup shape --------------------------------------

(deftest loading-skeleton-hiccup-shape
  (testing "hiccup root carries the canonical data-test"
    (let [hiccup (rf.story.ui.canvas/loading-skeleton)
          [_tag props] hiccup]
      (is (= "story-canvas-loading-skeleton" (:data-test props)))
      (is (= "status" (:role props)))
      (is (= "polite" (:aria-live props))))))

;; ---- first-rendered sentinel --------------------------------------------

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

;; ---- viewport-indicator -------------------------------------------------

(deftest viewport-indicator-elides-for-full
  (testing ":full preset has no width/height → indicator hidden"
    (is (nil? (rf.story.ui.canvas/viewport-indicator {:label "Full" :width nil :height nil})))
    (is (nil? (rf.story.ui.canvas/viewport-indicator {})))))

(deftest viewport-indicator-shows-dims
  (testing "sized preset → chip with WxH text"
    (let [hiccup (rf.story.ui.canvas/viewport-indicator {:label "iPhone" :width 375 :height 667})
          [_tag props text] hiccup]
      (is (= "story-canvas-viewport-indicator" (:data-test props)))
      (is (= "375 × 667" text)))))
