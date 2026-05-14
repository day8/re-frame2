(ns day8.re-frame2-causa.theme.perf-tier-cljs-test
  "Pure-data tests for the shared perf-tier ladder (rf2-6ja23).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `performance_helpers_cljs_test.cljc`:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

  The ladder mirrors `spec/007-UX-IA.md` §Colour system §Perf scale:

      :fast      <16ms       green   #4ADE80   ●
      :medium    16-50ms     yellow  #FBBF24   ●
      :slow      50-100ms    orange  #FB923C   ▲
      :blocking  >=100ms     red     #F87171   ▲    INP threshold

    1. `classify-tier` — boundaries 16ms / 50ms / 100ms (right-open at
       the lower edge); nil / negative / non-numeric → :fast.
    2. `tier-colour` / `tier-glyph` / `tier-label` — stable mappings,
       fallbacks present.
    3. `over-budget?` — at-or-above threshold semantics, nil guards.
    4. `default-budget-ms` / `tier-order` — stable structural constants."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.theme.perf-tier :as perf-tier]))

;; ---- (1) classification boundaries --------------------------------------

(deftest classify-tier-spec-boundaries
  (testing "fast < 16ms"
    (is (= :fast (perf-tier/classify-tier 0)))
    (is (= :fast (perf-tier/classify-tier 1)))
    (is (= :fast (perf-tier/classify-tier 15)))
    (is (= :fast (perf-tier/classify-tier 15.999))))
  (testing "medium = [16, 50)"
    (is (= :medium (perf-tier/classify-tier 16)))
    (is (= :medium (perf-tier/classify-tier 30)))
    (is (= :medium (perf-tier/classify-tier 49.999))))
  (testing "slow = [50, 100)"
    (is (= :slow (perf-tier/classify-tier 50)))
    (is (= :slow (perf-tier/classify-tier 75)))
    (is (= :slow (perf-tier/classify-tier 99.999))))
  (testing "blocking >= 100"
    (is (= :blocking (perf-tier/classify-tier 100)))
    (is (= :blocking (perf-tier/classify-tier 500)))
    (is (= :blocking (perf-tier/classify-tier 100000))))
  (testing "nil / negative / non-numeric collapse to :fast"
    (is (= :fast (perf-tier/classify-tier nil)))
    (is (= :fast (perf-tier/classify-tier -5)))
    (is (= :fast (perf-tier/classify-tier "not a number")))
    (is (= :fast (perf-tier/classify-tier :keyword)))))

;; ---- (2) colour + glyph + label -----------------------------------------

(deftest tier-colour-mirrors-spec
  (testing "perf-scale hex strings match tools/causa/spec/007-UX-IA.md §Colour system"
    (is (= "#4ADE80" (perf-tier/tier-colour :fast)))      ; green
    (is (= "#FBBF24" (perf-tier/tier-colour :medium)))    ; yellow
    (is (= "#FB923C" (perf-tier/tier-colour :slow)))      ; orange
    (is (= "#F87171" (perf-tier/tier-colour :blocking)))) ; red
  (testing "unknown tier falls back to text-tertiary so the dot is
            never invisible"
    (is (= "#6B7080" (perf-tier/tier-colour :unknown)))
    (is (= "#6B7080" (perf-tier/tier-colour nil)))))

(deftest tier-glyph-pairs-shape-with-colour
  (testing "Colour is never alone (spec/007-UX-IA.md §Colour is never alone)"
    ;; Within-budget tiers — calm dot
    (is (= "●" (perf-tier/tier-glyph :fast)))
    (is (= "●" (perf-tier/tier-glyph :medium)))
    ;; Over-budget tiers — attention triangle
    (is (= "▲" (perf-tier/tier-glyph :slow)))
    (is (= "▲" (perf-tier/tier-glyph :blocking))))
  (testing "unknown tier falls back to the hollow-dot affordance"
    (is (= "○" (perf-tier/tier-glyph :unknown)))
    (is (= "○" (perf-tier/tier-glyph nil)))))

(deftest tier-label-stable
  (is (= "fast"     (perf-tier/tier-label :fast)))
  (is (= "medium"   (perf-tier/tier-label :medium)))
  (is (= "slow"     (perf-tier/tier-label :slow)))
  (is (= "blocking" (perf-tier/tier-label :blocking)))
  (testing "unknown tier still produces a printable label (str fallback)"
    (is (= ":unknown" (perf-tier/tier-label :unknown)))
    (is (= ""         (perf-tier/tier-label nil)))))

(deftest tier-order-is-fastest-first
  (is (= [:fast :medium :slow :blocking] perf-tier/tier-order)))

;; ---- (3) over-budget? ----------------------------------------------------

(deftest over-budget-honours-threshold
  (testing "duration at or above budget is over-budget"
    (is (true? (perf-tier/over-budget? 16 16)))
    (is (true? (perf-tier/over-budget? 16 17)))
    (is (true? (perf-tier/over-budget? 16 100))))
  (testing "duration below budget is within-budget"
    (is (false? (perf-tier/over-budget? 16 0)))
    (is (false? (perf-tier/over-budget? 16 15.9))))
  (testing "nil / non-number guards — both sides protected"
    (is (false? (perf-tier/over-budget? nil 100)))
    (is (false? (perf-tier/over-budget? 16 nil)))
    (is (false? (perf-tier/over-budget? "16" 100)))
    (is (false? (perf-tier/over-budget? 16 "100")))))

;; ---- (4) defaults --------------------------------------------------------

(deftest default-budget-is-one-frame-at-60fps
  (testing "16ms — the one-frame-at-60fps target per spec/007 §Performance budget"
    (is (= 16 perf-tier/default-budget-ms))))

;; ---- (5) ladder consistency — colour ↔ glyph alignment -------------------

(deftest within-budget-tiers-share-the-calm-dot
  (testing "fast + medium share the calm-dot glyph — within-budget; the
            colour carries the within-vs-warning signal"
    (is (= (perf-tier/tier-glyph :fast) (perf-tier/tier-glyph :medium))))
  (testing "slow + blocking share the attention triangle — over-budget"
    (is (= (perf-tier/tier-glyph :slow) (perf-tier/tier-glyph :blocking)))))

(deftest every-tier-has-a-colour-and-glyph
  (testing "no nil drop-outs across the ladder — every tier resolves to
            a printable swatch and shape"
    (doseq [tier perf-tier/tier-order]
      (is (string? (perf-tier/tier-colour tier))
          (str "tier-colour returns a string for " tier))
      (is (string? (perf-tier/tier-glyph tier))
          (str "tier-glyph returns a string for " tier))
      (is (string? (perf-tier/tier-label tier))
          (str "tier-label returns a string for " tier)))))
