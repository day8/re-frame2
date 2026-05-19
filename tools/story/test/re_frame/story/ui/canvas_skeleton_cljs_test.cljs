(ns re-frame.story.ui.canvas-skeleton-cljs-test
  "CLJS-side regression net for the canvas loading skeleton + viewport-
  px indicator (rf2-0s4p1 / rf2-zgu68).

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
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story.ui.canvas :as canvas]))

(use-fixtures :each {:before (fn [] (canvas/reset-first-rendered!))})

;; ---- loading-phase? -----------------------------------------------------

(deftest loading-phase-pre-mount-mounting-loading
  (testing "pre-mount / mounting / loading + not-rendered? + no assertions → true"
    (is (true? (canvas/loading-phase? :pre-mount false false)))
    (is (true? (canvas/loading-phase? :mounting  false false)))
    (is (true? (canvas/loading-phase? :loading   false false)))))

(deftest loading-phase-ready-or-error
  (testing ":ready / :error → false"
    (is (false? (canvas/loading-phase? :ready false false)))
    (is (false? (canvas/loading-phase? :error false false)))))

(deftest loading-phase-first-rendered-overrides
  (testing "first-rendered? true → false regardless of phase"
    (is (false? (canvas/loading-phase? :loading true false)))
    (is (false? (canvas/loading-phase? :pre-mount true false)))))

(deftest loading-phase-assertions-recorded-overrides
  (testing "assertions-recorded? true → false even when phase is loading
            (rf2-qrk2s: loader-never-completes / loader-rejects park the
            lifecycle at :loading but the user view must render)"
    (is (false? (canvas/loading-phase? :loading   false true)))
    (is (false? (canvas/loading-phase? :pre-mount false true)))
    (is (false? (canvas/loading-phase? :mounting  false true)))))

(deftest loading-phase-nil-or-unknown
  (testing "nil phase → false (no skeleton when phase isn't known)"
    (is (false? (canvas/loading-phase? nil false false))))
  (testing "unknown phase → false"
    (is (false? (canvas/loading-phase? :something-else false false)))))

;; ---- loading-skeleton hiccup shape --------------------------------------

(deftest loading-skeleton-hiccup-shape
  (testing "hiccup root carries the canonical data-test"
    (let [hiccup (canvas/loading-skeleton)
          [_tag props] hiccup]
      (is (= "story-canvas-loading-skeleton" (:data-test props)))
      (is (= "status" (:role props)))
      (is (= "polite" (:aria-live props))))))

;; ---- first-rendered sentinel --------------------------------------------

(deftest first-rendered-sentinel-round-trip
  (testing "marker round-trips through the per-variant set"
    (canvas/reset-first-rendered!)
    (is (false? (canvas/variant-first-rendered? :story.x/y)))
    (canvas/mark-variant-rendered! :story.x/y)
    (is (true? (canvas/variant-first-rendered? :story.x/y)))
    (canvas/reset-first-rendered! :story.x/y)
    (is (false? (canvas/variant-first-rendered? :story.x/y))))
  (testing "reset all"
    (canvas/mark-variant-rendered! :story.a/one)
    (canvas/mark-variant-rendered! :story.b/two)
    (is (true? (canvas/variant-first-rendered? :story.a/one)))
    (canvas/reset-first-rendered!)
    (is (false? (canvas/variant-first-rendered? :story.a/one)))
    (is (false? (canvas/variant-first-rendered? :story.b/two)))))

;; ---- viewport-indicator -------------------------------------------------

(deftest viewport-indicator-elides-for-full
  (testing ":full preset has no width/height → indicator hidden"
    (is (nil? (canvas/viewport-indicator {:label "Full" :width nil :height nil})))
    (is (nil? (canvas/viewport-indicator {})))))

(deftest viewport-indicator-shows-dims
  (testing "sized preset → chip with WxH text"
    (let [hiccup (canvas/viewport-indicator {:label "iPhone" :width 375 :height 667})
          [_tag props text] hiccup]
      (is (= "story-canvas-viewport-indicator" (:data-test props)))
      (is (= "375 × 667" text)))))
