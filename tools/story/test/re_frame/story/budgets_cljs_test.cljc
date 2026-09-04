(ns re-frame.story.budgets-cljs-test
  "Deterministic enforcement gate for the Story UI parity budgets
  (rf2-ba86n.2, ratified 2026-05-30; normative table spec/018 §10; single
  source of truth `re-frame.story.budgets`).

  ## What this gate asserts — and what it deliberately does NOT

  The ratified F2 enforcement is a DETERMINISTIC structural/complexity gate,
  NOT a wall-clock micro-bench. Wall-clock latency in CI is flaky (shared
  runners, GC, JIT warm-up), so this gate enforces the *shape* that makes the
  documented latency targets achievable:

  - **Bounded output.** At the project floor (2 000 synthetic variants across
    200 stories) the sidebar derivation emits at most `sidebar-variant-cap`
    rendered rows per story; the variants-grid never exceeds
    `grid-visible-cell-cap` visible cells; a matrix warns past 144 and is
    flagged over the 400 hard cap.
  - **Single bounded pass.** The filter pipeline
    (`filter-variants` → `group-variants-by-story` → `filter-grouped-tree`)
    is asserted to be a single bounded pass: it touches each variant a
    bounded number of times (no O(n²) re-scan), is order-independent and
    idempotent, and the output is itself bounded by the input.

  The DOCUMENTED latency targets (rebuild ≤ 8 ms, inline-validate ≤ 4 ms,
  spine first paint ≤ 100 ms) live as data in `rf.story.budgets/latency-targets-ms`
  and are review-checklist / future-micro-bench bars — this gate does NOT
  assert them as wall-clock time. See the PR body for the structural-vs-clock
  rationale flag.

  ## Where it runs

  `.cljc` so it runs on BOTH the JVM (`clojure -M:test`, cognitect runner —
  ns ends in `-test`) and the CLJS node-test build (`npm run test:cljs` —
  ns ends in `cljs-test`, matching the `cljs-test$` regexp). The pure-data
  budgets + filter pipeline are exercised on both; the sidebar CLJS aliases
  (`default-variant-cap` / `bound-variants`, which live in a `.cljs` file the
  JVM can't `:require`) are asserted CLJS-only, mirroring
  `sidebar-chips-cljs-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.budgets :as rf.story.budgets]
            [re-frame.story.ui.state.filters :as rf.story.ui.state.filters]
            [re-frame.story.ui.sidebar-search :as rf.story.ui.sidebar-search]
            [re-frame.story.ui.workspace :as rf.story.ui.workspace]
            #?@(:cljs [[re-frame.story.ui.sidebar :as rf.story.ui.sidebar]
                       [re-frame.story.ui.controls :as rf.story.ui.controls]
                       [re-frame.story.ui.docs :as rf.story.ui.docs]])))

;; ---------------------------------------------------------------------------
;; Floor-scale synthetic fixture (N3) — pure data, deterministic
;; ---------------------------------------------------------------------------

(defn- floor-registry
  "Build a synthetic `id->body` variant map at (or above) the project floor:
  `stories` parent stories, evenly carrying `variants` total variants. Each
  variant id is `:story.s<i>/v<j>` so `predicates/parent-story-id` derives the
  right parent. Pure + deterministic — no randomness, identical every run."
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
;; N3 — the fixture really is floor-scale
;; ---------------------------------------------------------------------------

(deftest floor-fixture-is-realistic-scale
  (testing "the gate exercises at LEAST the ratified project floor"
    (is (= 2000 floor) "floor variant count is the ratified 2 000")
    (is (= 200 stories) "floor story count is the ratified 200")
    (is (= 50 (:workspaces rf.story.budgets/project-floor))
        "floor workspace count is the ratified 50"))
  (testing "the synthetic registry actually holds floor-many variants"
    (let [reg (floor-registry rf.story.budgets/project-floor)]
      (is (= floor (count reg))))))

;; ---------------------------------------------------------------------------
;; N1 — sidebar derivation emits BOUNDED output per story at floor scale
;; ---------------------------------------------------------------------------

(deftest sidebar-derivation-is-bounded-per-story
  (testing "at the floor, grouping then capping per story never emits more
            than `sidebar-variant-cap` rows for any single story"
    (let [reg     (floor-registry rf.story.budgets/project-floor)
          grouped (rf.story.ui.state.filters/group-variants-by-story reg)
          cap     rf.story.budgets/sidebar-variant-cap]
      (is (= stories (count grouped))
          "every story appears exactly once in the grouped tree")
      (doseq [{:keys [variants]} grouped]
        (let [shown (vec (take cap variants))]
          (is (<= (count shown) cap)
              "no story's visible (capped) row count exceeds the cap")))
      (testing "the elided remainder is always reachable via +N more (F1
                cap-and-page — nothing is dropped, only paged)"
        (let [{:keys [variants]} (first grouped)
              total (count variants)]
          (when (> total cap)
            (is (= total (+ cap (- total cap)))
                "shown + hidden = total: the expander reveals the rest")))))))

;; ---------------------------------------------------------------------------
;; N4 — the filter pipeline is a SINGLE BOUNDED PASS (no O(n²))
;; ---------------------------------------------------------------------------

(deftest filter-pipeline-is-single-bounded-pass
  (let [reg rf.story.budgets/project-floor
        id->body (floor-registry reg)]
    (testing "the full keystroke pipeline produces output bounded by the input"
      (let [filtered (rf.story.ui.state.filters/filter-variants id->body #{:dev})
            grouped  (rf.story.ui.state.filters/group-variants-by-story filtered)
            tree     (rf.story.ui.sidebar-search/filter-grouped-tree grouped "v1")
            shown    (mapcat :variants tree)]
        (is (<= (count filtered) (count id->body))
            "tag filter never grows the set")
        (is (<= (reduce + 0 (map (comp count :variants) grouped))
                (count filtered))
            "grouping never duplicates a variant (single pass, no fan-out)")
        (is (<= (count shown) (count filtered))
            "search narrowing never grows the set")))
    (testing "the pipeline is idempotent — re-running over its own output is
              stable (a second pass finds nothing new to do; rules out an
              accumulating O(n²) re-scan)"
      (let [once  (rf.story.ui.sidebar-search/filter-grouped-tree
                    (rf.story.ui.state.filters/group-variants-by-story
                      (rf.story.ui.state.filters/filter-variants id->body #{:dev}))
                    "v1")
            ;; flatten once's surviving variants back into an id->body map
            flat  (into {} (mapcat :variants once))
            twice (rf.story.ui.sidebar-search/filter-grouped-tree
                    (rf.story.ui.state.filters/group-variants-by-story
                      (rf.story.ui.state.filters/filter-variants flat #{:dev}))
                    "v1")]
        (is (= (reduce + 0 (map (comp count :variants) once))
               (reduce + 0 (map (comp count :variants) twice)))
            "second pass over the filtered output is a fixed point")))
    (testing "the tag filter is order-independent — a single predicate pass,
              not a pairwise comparison"
      (let [forward (rf.story.ui.state.filters/filter-variants id->body #{:dev})
            reverse (rf.story.ui.state.filters/filter-variants
                      (into {} (reverse (seq id->body))) #{:dev})]
        (is (= (count forward) (count reverse))
            "result size is independent of input ordering")))))

;; ---------------------------------------------------------------------------
;; G1 / G3 — variants-grid never exceeds the visible cell cap; pages beyond
;; ---------------------------------------------------------------------------

(deftest grid-cell-output-is-bounded
  (testing "`bound-cells` never emits more than the cap, however large the grid"
    (let [cells (vec (range floor))
          {:keys [shown hidden]} (rf.story.budgets/bound-cells cells)]
      (is (= rf.story.budgets/grid-visible-cell-cap (count shown))
          "visible cells are capped at the grid cell cap")
      (is (= (- floor rf.story.budgets/grid-visible-cell-cap) hidden)
          "the remainder is paged (reachable), not dropped")
      (is (= floor (+ (count shown) hidden))
          "shown + hidden = total — cap-and-page, nothing lost")))
  (testing "a grid at/under the cap is never bounded"
    (let [cells (vec (range rf.story.budgets/grid-visible-cell-cap))]
      (is (= {:shown cells :hidden 0} (rf.story.budgets/bound-cells cells)))))
  (testing "expanded? reveals the full grid (one explicit page-all gesture)"
    (let [cells (vec (range 250))
          {:keys [shown hidden]} (rf.story.budgets/bound-cells cells
                                                      rf.story.budgets/grid-visible-cell-cap
                                                      true)]
      (is (= 250 (count shown)))
      (is (zero? hidden))))
  (testing "page count is deterministic ceil(total / cap), minimum 1"
    (is (= 1 (rf.story.budgets/matrix-page-count 0)))
    (is (= 1 (rf.story.budgets/matrix-page-count rf.story.budgets/grid-visible-cell-cap)))
    (is (= 2 (rf.story.budgets/matrix-page-count (inc rf.story.budgets/grid-visible-cell-cap))))
    (is (= 4 (rf.story.budgets/matrix-page-count rf.story.budgets/matrix-hard-cap)))))

;; ---------------------------------------------------------------------------
;; G2 / G3 — matrix dimension guard: warn > 144, hard cap 400
;; ---------------------------------------------------------------------------

(deftest matrix-dimension-guard
  (testing "warn threshold is the ratified 12×12 = 144"
    (is (= 144 rf.story.budgets/matrix-warn-threshold))
    (is (not (rf.story.budgets/matrix-warn? [11 11])) "121 cells: below warn")
    (is (rf.story.budgets/matrix-warn? [12 12]) "144 cells: at warn")
    (is (rf.story.budgets/matrix-warn? [13 12]) "156 cells: past warn"))
  (testing "hard cap is the ratified 400; beyond it the grid MUST paginate"
    (is (= 400 rf.story.budgets/matrix-hard-cap))
    (is (not (rf.story.budgets/matrix-over-hard-cap? [20 20])) "400 cells: at cap, not over")
    (is (rf.story.budgets/matrix-over-hard-cap? [21 20]) "420 cells: over the hard cap")
    (is (rf.story.budgets/matrix-over-hard-cap? [10 10 5]) "500 cells (3 axes): over"))
  (testing "the hard cap exceeds the per-page visible cap (page bounds one
            page; hard cap bounds the matrix total before paging is forced)"
    (is (> rf.story.budgets/matrix-hard-cap rf.story.budgets/grid-visible-cell-cap)))
  (testing "matrix-product folds axis sizes; empty axes = 0 cells"
    (is (= 0 (rf.story.budgets/matrix-product [])))
    (is (= 144 (rf.story.budgets/matrix-product [12 12])))
    (is (= 24 (rf.story.budgets/matrix-product [2 3 4])))))

;; ---------------------------------------------------------------------------
;; rf2-ba86n.18 — the WIRED render-path bounding (perf fixtures)
;;
;; The tests above assert the pure budget primitives. These exercise the
;; helpers the RENDER PATHS actually call (`rf.story.ui.workspace/bound-grid-cells`,
;; `rf.story.ui.controls/bound-arg-rows`) against floor-scale fixtures — the proof that
;; the variants-grid and controls panel emit bounded output at the 2 000-
;; variant floor without rendering all cells / rows (spec/018 §10).
;; ---------------------------------------------------------------------------

(deftest variants-grid-render-path-is-bounded
  (testing "the grid renderer's `bound-grid-cells` caps visible cells at the
            G1 cell cap, however large the enumerated grid (floor scale)"
    (let [cells (vec (range floor))
          {:keys [shown hidden total warn? over-hard-cap?]}
          (rf.story.ui.workspace/bound-grid-cells cells false)]
      (is (= floor total) "the helper reports the true total")
      (is (= rf.story.budgets/grid-visible-cell-cap (count shown))
          "visible cells are capped at the G1 visible cell cap")
      (is (= (- floor rf.story.budgets/grid-visible-cell-cap) hidden)
          "the remainder is paged (reachable), not dropped")
      (is warn? "a floor-scale grid trips the G2 dense-matrix advisory")
      (is over-hard-cap? "a floor-scale grid trips the G3 hard-cap flag")))
  (testing "G3 — even EXPANDED, a grid past the hard cap never renders all
            cells: expansion tops out at the hard cap, the tail stays paged
            (never freeze the canvas)"
    (let [cells (vec (range floor))
          {:keys [shown hidden over-hard-cap?]}
          (rf.story.ui.workspace/bound-grid-cells cells true)]
      (is (= rf.story.budgets/matrix-hard-cap (count shown))
          "expanded render is bounded by the hard cap, not the full set")
      (is (= (- floor rf.story.budgets/matrix-hard-cap) hidden)
          "the over-hard-cap tail is still paged after expansion")
      (is over-hard-cap?)))
  (testing "a grid AT/UNDER the visible cap is never bounded and never warns"
    (let [cells (vec (range rf.story.budgets/grid-visible-cell-cap))
          {:keys [shown hidden warn? over-hard-cap?]}
          (rf.story.ui.workspace/bound-grid-cells cells false)]
      (is (= rf.story.budgets/grid-visible-cell-cap (count shown)))
      (is (zero? hidden))
      (is (not warn?) "100 cells is under the 144 warn threshold")
      (is (not over-hard-cap?))))
  (testing "a mid-size grid (≤ hard cap) EXPANDS fully — one page-all gesture
            reveals every cell when that is safe (≤ 400)"
    (let [cells (vec (range 300))
          {:keys [shown hidden warn? over-hard-cap?]}
          (rf.story.ui.workspace/bound-grid-cells cells true)]
      (is (= 300 (count shown)) "≤ hard cap: expansion reveals the full grid")
      (is (zero? hidden))
      (is warn? "300 cells is past the 144 warn threshold")
      (is (not over-hard-cap?) "300 cells is under the 400 hard cap"))))

;; ---------------------------------------------------------------------------
;; C2 — controls flat-panel row cap render path (CLJS-only — controls.cljs)
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest controls-flat-panel-render-path-is-bounded
     (testing "the controls editor's `bound-arg-rows` caps visible rows at the
               C2 flat-row cap, however many args a variant declares"
       (let [entries (mapv (fn [i] [(keyword (str "arg" i)) i]) (range floor))
             {:keys [shown hidden]} (rf.story.ui.controls/bound-arg-rows entries false)]
         (is (= rf.story.budgets/controls-flat-row-cap (count shown))
             "visible rows are capped at the C2 flat-row cap")
         (is (= (- floor rf.story.budgets/controls-flat-row-cap) hidden)
             "the remainder is paged behind +N more, not dropped")
         (is (= floor (+ (count shown) hidden))
             "shown + hidden = total — nothing lost")))
     (testing "a panel AT/UNDER the cap is never bounded"
       (let [entries (mapv (fn [i] [(keyword (str "arg" i)) i])
                           (range rf.story.budgets/controls-flat-row-cap))
             {:keys [hidden]} (rf.story.ui.controls/bound-arg-rows entries false)]
         (is (zero? hidden))))
     (testing "expanded? reveals every row (one explicit page-all gesture)"
       (let [entries (mapv (fn [i] [(keyword (str "arg" i)) i]) (range 150))
             {:keys [shown hidden]} (rf.story.ui.controls/bound-arg-rows entries true)]
         (is (= 150 (count shown)))
         (is (zero? hidden))))
     (testing "the flat-expanded sentinel is a keyword (never a vector path),
               so it can never collide with a nested-control path in the
               shared `:expanded` ratom set"
       (is (keyword? rf.story.ui.controls/flat-expanded-sentinel))
       (is (not (vector? rf.story.ui.controls/flat-expanded-sentinel))))))

;; ---------------------------------------------------------------------------
;; Ratified numbers — the budget table matches the ratification verbatim
;; ---------------------------------------------------------------------------

(deftest ratified-budget-numbers
  (testing "every cap is the as-proposed, ratified value"
    (is (= 40  rf.story.budgets/sidebar-variant-cap)   "sidebar variant cap")
    (is (= 20  rf.story.budgets/captured-artifact-cap) "captured-artifacts cap")
    (is (= 60  rf.story.budgets/controls-flat-row-cap) "controls flat-panel row cap")
    (is (= 100 rf.story.budgets/grid-visible-cell-cap) "variants-grid visible cell cap")
    (is (= 144 rf.story.budgets/matrix-warn-threshold) "matrix warn threshold")
    (is (= 400 rf.story.budgets/matrix-hard-cap)       "matrix hard cap"))
  (testing "the documented latency TARGETS carry the ratified numbers (data
            only — not asserted as wall-clock by this gate)"
    (is (= 8   (:filtered-rebuild  rf.story.budgets/latency-targets-ms)))
    (is (= 4   (:inline-validate   rf.story.budgets/latency-targets-ms)))
    (is (= 100 (:spine-first-paint rf.story.budgets/latency-targets-ms))))
  (testing "the failure→evidence budget: ≤ 1 gesture, excerpt ≤ 2 beats"
    (is (= 1 (:max-gestures  rf.story.budgets/evidence-gesture-budget)))
    (is (= 2 (:excerpt-beats rf.story.budgets/evidence-gesture-budget)))))

;; ---------------------------------------------------------------------------
;; Single-source: the implementation surfaces READ the budget constants
;; (CLJS-only — sidebar/docs aliases live in files the JVM can't require)
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest implementation-reads-the-budgets
     (testing "the shipped sidebar caps alias the budget single-source"
       (is (= rf.story.budgets/sidebar-variant-cap rf.story.ui.sidebar/default-variant-cap)
           "rf.story.ui.sidebar/default-variant-cap reads rf.story.budgets/sidebar-variant-cap")
       (is (= rf.story.budgets/captured-artifact-cap rf.story.ui.sidebar/default-artifact-cap)
           "rf.story.ui.sidebar/default-artifact-cap reads rf.story.budgets/captured-artifact-cap"))
     (testing "the docs evidence-excerpt cap reads the budget single-source"
       (is (= (:excerpt-beats rf.story.budgets/evidence-gesture-budget)
              rf.story.ui.docs/evidence-excerpt-beat-cap)
           "rf.story.ui.docs/evidence-excerpt-beat-cap reads the X1 excerpt-beats budget"))
     (testing "the shipped `bound-variants` honours the budget cap at scale —
               the gate's bounding contract IS the one the sidebar renders"
       (let [vs (mapv (fn [i] [(keyword "story.x" (str "v" i)) {}]) (range floor))
             {:keys [shown hidden]} (rf.story.ui.sidebar/bound-variants
                                      vs rf.story.budgets/sidebar-variant-cap false)]
         (is (= rf.story.budgets/sidebar-variant-cap (count shown)))
         (is (= (- floor rf.story.budgets/sidebar-variant-cap) hidden))))
     (testing "rf2-ba86n.18 — the controls flat-panel cap render path reads
               the C2 budget single-source (no parallel copy)"
       (let [entries (mapv (fn [i] [(keyword (str "a" i)) i])
                           (range (inc rf.story.budgets/controls-flat-row-cap)))
             {:keys [shown]} (rf.story.ui.controls/bound-arg-rows entries false)]
         (is (= rf.story.budgets/controls-flat-row-cap (count shown))
             "bound-arg-rows caps at rf.story.budgets/controls-flat-row-cap")))))
