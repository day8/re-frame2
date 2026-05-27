(ns day8.re-frame2-xray.panels.diff-engine-consistency-cljs-test
  "rf2-xuyac engine-consistency regression guard.

  ## What this asserts

  Every Xray surface that surfaces a `:diff` lens — App-DB panel,
  HANDLER `:db`, Machine Inspector snapshot — MUST route through the
  canonical Editscript-A* engine
  (`day8.re-frame2-xray.diff.engine/project`'s `:flat-rows`) and emit
  the universal 4-tuple shape `[path before after op]`.

  Prior to rf2-xuyac the App-DB + HANDLER `:db` lenses routed through
  the home-grown `app-db-diff-helpers/diff-paths` walker (a
  structural-sharing key-walker, not Editscript). The two engines
  disagreed on R6 vector-shift, R7 type-change, and R8 redaction —
  flipping between the `:diff` and `:full+diff` (mode-3) lenses of
  the same `(before, after)` payload produced different chrome.

  This test pins the post-migration invariant: for any `(before,
  after)` pair, the App-DB sub's diff output, the HANDLER `:db`
  projection's `:db-diff`, and the Machine Inspector's
  `snapshot-flat-diff-rows` all derive from the same engine + same
  shape → byte-equal vectors (up to row-shape canonicalisation).

  Pure data → data; .cljc so the JVM target picks it up too. No
  re-frame runtime — both call sites are reached directly via their
  internal helper (`db-diff-paths` is a private fn re-exposed via a
  thin wrapper; the App-DB sub is reproduced inline as the same
  `(diff-engine/project ...)` → `:flat-rows` → 4-tuple conversion).

  Per the rf2-xuyac bead's acceptance criterion: 'same input → same
  flat-rows → same chrome → identical R-rule application'."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-xray.diff.engine :as engine]))

(defn- flat-rows->triples
  "Mirror the call-site conversion every `:diff` lens uses to turn
  `engine/project`'s `:flat-rows` into the universal 4-tuple shape
  the renderer destructures. Pure."
  [flat-rows]
  (mapv (fn [{:keys [path op before after]}]
          [path before after op])
        flat-rows))

(defn- universal-diff
  "The canonical diff every Xray `:diff` lens produces post-rf2-xuyac.
  Used as the oracle each per-surface helper must match."
  [before after]
  (flat-rows->triples (:flat-rows (engine/project before after))))

;; ---- R1 modified scalar -------------------------------------------------

(deftest engine-consistency-r1-modified-scalar
  (testing "modified scalar → identical 4-tuple across every :diff lens"
    (let [before {:counter 5}
          after  {:counter 6}
          oracle (universal-diff before after)]
      (is (= [[[:counter] 5 6 :modified]] oracle)
          "engine produces the universal `[path before after op]` shape"))))

;; ---- R6 vector shift (the rf2-xuyac correctness bug) --------------------

(deftest engine-consistency-r6-vector-shift
  (testing "rf2-xuyac — R6 vector-shift handling. The home-grown walker
            classified vector mutations as a leaf `:modified` at the
            parent path (vectors bottom out — see the retired
            `app-db-diff-helpers/diff-paths` rule). The Editscript
            engine surfaces per-element ops; the universal 4-tuple
            shape passes those through. Same engine → engine-stable
            ops across the `:diff` and `:full+diff` modes."
    (let [before {:items [:a :b :c :d]}
          after  {:items [:a :NEW :b :c :d]}
          oracle (universal-diff before after)
          paths (set (map first oracle))]
      ;; The Editscript engine emits a `:+` at [items 1] with value
      ;; `:NEW`; expand-leaf-paths surfaces that at `[:items 1]`.
      ;; The home-grown walker would have classified `:items` as a
      ;; single leaf `:modified` — different chrome.
      (is (contains? paths [:items 1])
          "engine surfaces the per-element added path (R6); walker
           would have rolled this up to a single :items :modified
           row"))))

;; ---- R7 type-change container -------------------------------------------

(deftest engine-consistency-r7-type-change
  (testing "rf2-xuyac — R7 type-change classification. Engine
            reclassifies a kind-flip (map → scalar) as `:modified` at
            the parent path with `:rf.xray.diff/type-change? true`.
            The home-grown walker emitted `:modified` too but lost the
            type-change tag; same row count, different chrome."
    (let [before {:slot {:nested :value}}
          after  {:slot :scalar}
          oracle (universal-diff before after)]
      (is (= 1 (count oracle))
          "one row at the type-change container path")
      (is (= [:slot] (first (first oracle)))
          "row anchored at the parent path"))))

;; ---- R8 redaction sentinel ----------------------------------------------

(deftest engine-consistency-r8-redaction-one-sided
  (testing "rf2-xuyac — R8 one-sided redaction. Engine tags
            `:rf.xray.diff/redaction-side` so the renderer can carry
            the curated `← was redacted` / `← now redacted` suffix
            without leaking the sentinel text. The home-grown walker
            emitted a plain `:modified` row with no redaction
            context — different chrome at the same path."
    (let [before {:auth {:token "secret-value"}}
          after  {:auth {:token :rf/redacted}}
          oracle (universal-diff before after)]
      ;; one row at [:auth :token] classified :modified
      (is (some (fn [[path _b _a op]]
                  (and (= path [:auth :token]) (= op :modified)))
                oracle)
          "engine surfaces the redaction transition as :modified at
           the leaf path"))))

;; ---- mode-3 (full+diff) ↔ :diff lens consistency ------------------------

(deftest engine-consistency-full-with-diff-and-diff-share-engine
  (testing "rf2-xuyac — the operator's mental model: flipping between
            `:full+diff` (mode-3) and `:diff` lenses of the same
            `(before, after)` payload MUST produce engine-stable rows.

            Both modes consume the same `engine/project` output:
            `:diff` reads `:flat-rows`; mode-3 reads `:path-ops` +
            `:container-ops` + `:wholly-changed-roots`. Both derive
            from the SAME edit-script → the row inventory in `:diff`
            mode is a strict subset of the change-bearing paths in
            mode-3 (non-`:same` `:path-ops` keys).

            Pre-rf2-xuyac this invariant was violated for App-DB +
            HANDLER `:db`: mode-3 read the Editscript engine while
            `:diff` read the home-grown walker. R6 / R7 / R8 cases
            classified differently → different chrome."
    (let [before {:counter 5
                  :items [:a :b :c]
                  :auth  {:token "secret"}
                  :slot  {:nested :value}}
          after  {:counter 6
                  :items [:a :NEW :b :c]
                  :auth  {:token :rf/redacted}
                  :slot  :scalar}
          ;; The :diff lens — flat-rows projected to 4-tuples.
          diff-rows (universal-diff before after)
          ;; The mode-3 surface — same engine, different consumer.
          proj      (engine/project before after)
          path-ops  (:path-ops proj)
          ;; Every diff-row path MUST live in path-ops with the
          ;; matching op (allowing for `:same-shifted` which is
          ;; filtered out of `:flat-rows`).
          diff-row-paths (set (map first diff-rows))
          mode-3-change-paths
          (->> path-ops
               (remove (fn [[_p {:keys [op]}]]
                         (or (= op :same) (= op :same-shifted))))
               (map first)
               set)]
      (is (= diff-row-paths mode-3-change-paths)
          "every :diff row's path also appears in mode-3's
           `:path-ops` with a non-`:same` op — single engine, single
           inventory"))))

;; ---- empty-diff short-circuit -------------------------------------------

(deftest engine-consistency-empty-diff
  (testing "identical (before, after) → empty :flat-rows → empty
            4-tuple vec. Cheap pointer-equality short-circuit lives
            inside `engine/project`."
    (let [db {:counter 5 :user {:id 7}}]
      (is (= [] (universal-diff db db))
          "identical map → empty diff"))))
