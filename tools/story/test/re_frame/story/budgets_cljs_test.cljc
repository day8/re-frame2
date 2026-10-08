(ns re-frame.story.budgets-cljs-test
  "Deterministic enforcement gate for the Story UI parity budgets
  (normative table spec/018 §10.1; single source of truth
  `re-frame.story.budgets`). It asserts bounded output and a single
  bounded derivation pass at the project floor — never wall-clock time,
  which is flaky in CI. The sidebar / controls / docs aliases live in
  `.cljs` files the JVM cannot require, so those tests are CLJS-only."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.budgets :as rf.story.budgets]
            [re-frame.story.ui.state.filters :as rf.story.ui.state.filters]
            [re-frame.story.ui.sidebar-search :as rf.story.ui.sidebar-search]
            [re-frame.story.ui.workspace :as rf.story.ui.workspace]
            #?@(:cljs [[re-frame.story.ui.sidebar :as rf.story.ui.sidebar]
                       [re-frame.story.ui.controls :as rf.story.ui.controls]
                       [re-frame.story.ui.docs :as rf.story.ui.docs]])))

(defn- floor-registry
  "A deterministic synthetic `id->body` variant map: `stories` parent
  stories evenly carrying `variants` variants, ids `:story.s<i>/v<j>`."
  [{:keys [variants stories]}]
  (let [per-story (long (Math/ceil (/ (double variants) stories)))]
    (into {}
          (for [s (range stories)
                v (range per-story)
                :let [idx (+ (* s per-story) v)]
                :when (< idx variants)]
            [(keyword (str "story.s" s) (str "v" v))
             {:tags (if (even? idx) #{:dev} #{:test})}]))))

(def ^:private floor (:variants rf.story.budgets/project-floor))
(def ^:private stories (:stories rf.story.budgets/project-floor))

;; ---------------------------------------------------------------------------
;; N1 / N3 / N4 — the sidebar derivation at floor scale
;; ---------------------------------------------------------------------------

(deftest sidebar-derivation-groups-every-story-at-floor
  (testing "at the floor, grouping emits one row per story; the per-story
            cap itself is `bound-variants`' contract, pinned against the
            shipped sidebar in `implementation-reads-the-budgets`"
    (let [reg     (floor-registry rf.story.budgets/project-floor)
          grouped (rf.story.ui.state.filters/group-variants-by-story reg)]
      (is (= stories (count grouped))
          "every story appears exactly once in the grouped tree"))))

(deftest filter-pipeline-is-single-bounded-pass
  (testing "the full keystroke pipeline produces output bounded by the input"
    (let [id->body (floor-registry rf.story.budgets/project-floor)
          filtered (rf.story.ui.state.filters/filter-variants id->body #{:dev})
          grouped  (rf.story.ui.state.filters/group-variants-by-story filtered)
          tree     (rf.story.ui.sidebar-search/filter-grouped-tree grouped "v1")
          shown    (mapcat :variants tree)]
      (is (<= (count filtered) (count id->body))
          "tag filter never grows the set")
      (is (<= (reduce + 0 (map (comp count :variants) grouped))
              (count filtered))
          "grouping never duplicates a variant (single pass, no fan-out)")
      (is (<= (count shown) (count filtered))
          "search narrowing never grows the set"))))

;; ---------------------------------------------------------------------------
;; G1 / G2 / G3 — variants-grid cell cap and matrix dimension guard
;; ---------------------------------------------------------------------------

(deftest bound-cells-expanded-reveals-every-cell
  (testing "expanded? reveals the full set past the cap (one explicit page-all gesture)"
    (let [cells (vec (range 250))]
      (is (= {:shown cells :hidden 0}
             (rf.story.budgets/bound-cells cells rf.story.budgets/grid-visible-cell-cap true))))))

(deftest matrix-dimension-guard
  (testing "warn at ≥ 12×12 = 144"
    (is (not (rf.story.budgets/matrix-warn? [11 11])) "121 cells: below warn")
    (is (rf.story.budgets/matrix-warn? [12 12]) "144 cells: at warn"))
  (testing "hard cap 400: over it the grid MUST paginate"
    (is (not (rf.story.budgets/matrix-over-hard-cap? [20 20])) "400 cells: at cap, not over")
    (is (rf.story.budgets/matrix-over-hard-cap? [21 20]) "420 cells: over the hard cap")))

(deftest variants-grid-render-path-is-bounded
  (let [cells (vec (range floor))]
    (doseq [[label n expanded? shown hidden warn? over?]
            [["a floor-scale grid shows the G1 cell cap and pages the rest"
              2000 false 100 1900 true true]
             ["G3: even EXPANDED, a grid past the hard cap renders at most the hard cap"
              2000 true 400 1600 true true]
             ["a grid at the visible cap is never bounded and never warns"
              100 false 100 0 false false]
             ["a grid under the hard cap EXPANDS fully"
              300 true 300 0 true false]]]
      (testing label
        (is (= {:shown          (subvec cells 0 shown)
                :hidden         hidden
                :total          n
                :warn?          warn?
                :over-hard-cap? over?}
               (rf.story.ui.workspace/bound-grid-cells (subvec cells 0 n) expanded?)))))))

;; ---------------------------------------------------------------------------
;; C2 — controls flat-panel row cap render path (CLJS-only — controls.cljs)
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest controls-flat-panel-render-path-is-bounded
     (testing "the controls editor's `bound-arg-rows` caps visible rows at the
               C2 flat-row cap, however many args a variant declares"
       (let [entries (mapv (fn [i] [(keyword (str "arg" i)) i]) (range floor))
             {:keys [shown hidden]} (rf.story.ui.controls/bound-arg-rows entries false)]
         (is (= [rf.story.budgets/controls-flat-row-cap (- floor rf.story.budgets/controls-flat-row-cap)]
                [(count shown) hidden])
             "visible rows are capped; the remainder is paged behind +N more")))
     (testing "a panel AT/UNDER the cap is never bounded"
       (let [entries (mapv (fn [i] [(keyword (str "arg" i)) i])
                           (range rf.story.budgets/controls-flat-row-cap))
             {:keys [hidden]} (rf.story.ui.controls/bound-arg-rows entries false)]
         (is (zero? hidden))))
     (testing "expanded? reveals every row (one explicit page-all gesture)"
       (let [entries (mapv (fn [i] [(keyword (str "arg" i)) i]) (range 150))
             {:keys [shown hidden]} (rf.story.ui.controls/bound-arg-rows entries true)]
         (is (= [150 0] [(count shown) hidden]))))
     (testing "the flat-expanded sentinel is a keyword (never a vector path),
               so it can never collide with a nested-control path in the
               shared `:expanded` ratom set"
       (is (keyword? rf.story.ui.controls/flat-expanded-sentinel)))))

;; ---------------------------------------------------------------------------
;; The budget table matches spec/018 §10.1
;; ---------------------------------------------------------------------------

(deftest ratified-budget-numbers
  (is (= [40 20 60 100 144 400]
         [rf.story.budgets/sidebar-variant-cap
          rf.story.budgets/captured-artifact-cap
          rf.story.budgets/controls-flat-row-cap
          rf.story.budgets/grid-visible-cell-cap
          rf.story.budgets/matrix-warn-threshold
          rf.story.budgets/matrix-hard-cap])
      "N1, N2, C2, G1, G2, G3")
  (is (= {:variants 2000 :stories 200 :workspaces 50} rf.story.budgets/project-floor) "N3")
  (is (= {:filtered-rebuild 8 :inline-validate 4 :spine-first-paint 100}
         rf.story.budgets/latency-targets-ms)
      "documented latency targets — data only, never asserted as wall-clock")
  (is (= {:max-gestures 1 :excerpt-beats 2} rf.story.budgets/evidence-gesture-budget) "X1"))

;; ---------------------------------------------------------------------------
;; Single source: the implementation surfaces READ the budget constants
;; (CLJS-only — sidebar/docs aliases live in files the JVM can't require)
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest implementation-reads-the-budgets
     (testing "the shipped sidebar caps alias the budget single-source"
       (is (= rf.story.budgets/sidebar-variant-cap rf.story.ui.sidebar/default-variant-cap))
       (is (= rf.story.budgets/captured-artifact-cap rf.story.ui.sidebar/default-artifact-cap)))
     (testing "the docs evidence-excerpt cap reads the budget single-source"
       (is (= (:excerpt-beats rf.story.budgets/evidence-gesture-budget)
              rf.story.ui.docs/evidence-excerpt-beat-cap)))
     (testing "the shipped `bound-variants` honours the budget cap at scale"
       (let [vs (mapv (fn [i] [(keyword "story.x" (str "v" i)) {}]) (range floor))
             {:keys [shown hidden]} (rf.story.ui.sidebar/bound-variants
                                      vs rf.story.budgets/sidebar-variant-cap false)]
         (is (= rf.story.budgets/sidebar-variant-cap (count shown)))
         (is (= (- floor rf.story.budgets/sidebar-variant-cap) hidden))))))
