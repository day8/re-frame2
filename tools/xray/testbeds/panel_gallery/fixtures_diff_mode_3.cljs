(ns panel-gallery.fixtures-diff-mode-3
  "Fixture builders for the **mode-3 diff grammar** Story set.

  Mode-3 is the third lens of the HANDLER `:db` toggle: the full data
  tree WITH inline diff annotations (gutter glyphs, row washes, R3
  `[N∆]` chips on collapsed containers, R4 vertical rails, R5-tinted
  descendant washes, R6 `(was N)` vector-shift suffixes, R7 type-
  change `← was <prior>` suffixes, R8 redaction-curated suffixes).

  Each variant pins one R-rule + one scenario so Mike can visually
  inspect every grammar branch when the work is complete. The Story
  set is the canonical visual demonstration surface for re-frame2
  (the Storybook analogue per
  `feedback_skill_anchor_to_known_mental_models`).

  ## Fixture shape

  Each builder returns a map:

      {:before <cljs-value>     ;; the before-tree (epoch N-1 :db)
       :value  <cljs-value>     ;; the after-tree (epoch N :db)
       :opts   {<opts-key> ...}} ;; widget opts; `:full-with-diff? true`
                                ;; enables mode-3 chrome (R3 chip + R4 rail).

  The gallery wraps each fixture with a sub-mounted edn-inspector
  call: `[edn-inspector value (assoc opts :before before)]`. Mode-3
  variants pass `:full-with-diff? true` to opt the renderer into the
  R3 chip + R4 rail chrome; non-mode-3 variants are also covered (see
  `combo-mode-toggle-three-up` which renders the SAME data in all
  three modes side-by-side).

  ## R-rule inventory

  - R1 — value-side modified-scalar glyph + `← was <prior>`
  - R2 — key-side `+`/`−` glyph on new/removed map keys
  - R3 — collapsed `[N∆]` count chip on containers with descendant change
  - R4 — 2px vertical rail through change-bearing subtree
  - R5 — wholly-new/removed subtree (parent-only marking, descendant wash retained)
  - R6 — vector shift-detection via Editscript A* (LCS) — `(was N)` suffix
  - R7 — type-change containers reclassify to `:modified` with `← was <prior>`
  - R8 — one-sided redaction renders `← was redacted` / `← now redacted`"
  (:require [day8.re-frame2-xray.views.edn-inspector :as edn-inspector]))

;; =========================================================================
;; R1 — modified scalar
;; =========================================================================

(defn r1-modified-scalar
  "R1 — modified scalar leaf. `{:counter 5} → {:counter 6}`. Shows
  the value-side `~` glyph + `← was 5` italic muted suffix."
  []
  {:before {:counter 5}
   :value  {:counter 6}
   :opts   {:full-with-diff? true}})

;; =========================================================================
;; R2 — new / removed map keys (key-side glyph)
;; =========================================================================

(defn r2-new-map-key
  "R2 — wholly new key. `{:a 1} → {:a 1 :b 2}`. The `:b` key row paints
  a `+` glyph at column 1 (key-side reach) AND the value side picks
  up the green wash."
  []
  {:before {:a 1}
   :value  {:a 1 :b 2}
   :opts   {:full-with-diff? true}})

(defn r2-removed-map-key
  "R2 — removed key. `{:a 1 :b 2} → {:a 1}`. The `:b` key row paints
  a `−` glyph at column 1 and the key text strikes through. The
  value side renders the prior value (also struck through)."
  []
  {:before {:a 1 :b 2}
   :value  {:a 1}
   :opts   {:full-with-diff? true}})

;; =========================================================================
;; R3 — collapsed [N∆] chip
;; =========================================================================

(defn r3-collapsed-chip
  "R3 (revised) — collapsed container with descendant changes shows a
  `[N∆]` count chip after the closing ellipsis. The fixture sets a
  shallow `:default-expanded-depth 1` so `:user`'s 3-change subtree
  collapses by default — the operator sees `▸ :user {…} [3∆]`. Click
  the triangle to expand; the chip vanishes and per-row signals
  reveal themselves."
  []
  {:before {:counter 5
            :user    {:id 7 :name "Ada" :role "engineer" :team "platform"}}
   :value  {:counter 5
            :user    {:id 7 :name "Ada Lovelace" :role "lead" :team "platform"}}
   :opts   {:full-with-diff? true
            :default-expanded-depth 1}})

;; =========================================================================
;; R4 — vertical rail through subtree
;; =========================================================================

(defn r4-vertical-rail
  "R4 — single 2px vertical rail in the dominant-op hue runs through
  the change-bearing subtree. Sub-tree has one change at row 3; the
  rail paints through its full height so the operator's peripheral
  vision picks up the region indicator."
  []
  {:before {:items [{:id :a :qty 1}
                    {:id :b :qty 1}
                    {:id :c :qty 1}
                    {:id :d :qty 1}
                    {:id :e :qty 1}]}
   :value  {:items [{:id :a :qty 1}
                    {:id :b :qty 1}
                    {:id :c :qty 99}   ; <-- modified
                    {:id :d :qty 1}
                    {:id :e :qty 1}]}
   :opts   {:full-with-diff? true
            :default-expanded-depth 3}})

;; =========================================================================
;; R5 — wholly-new / wholly-removed subtrees
;; =========================================================================

(defn r5-wholly-new-subtree
  "R5 (revised) — wholly-new subtree. `:flash` didn't exist in the
  before-tree; the parent `:flash` row gets the `+` glyph + green
  triangle + green rail. Per-leaf gutter glyphs + stripes are
  SUPPRESSED on the descendants, but the low-opacity green wash IS
  RETAINED across every descendant row (partial-visibility
  readability)."
  []
  {:before {:a 1}
   :value  {:a 1
            :flash {:level :ok
                    :text "Order placed"
                    :timestamp 1735689600
                    :duration 250}}
   :opts   {:full-with-diff? true}})

(defn r5-wholly-removed-subtree
  "R5 (revised) — wholly-removed subtree. Inverse of the new-subtree
  case. The parent `:legacy` row paints the `−` glyph + red triangle
  + red rail; descendants paint with red wash but no per-leaf glyphs."
  []
  {:before {:a 1
            :legacy {:flag true :version 1 :note "deprecated"}}
   :value  {:a 1}
   :opts   {:full-with-diff? true}})

;; =========================================================================
;; R6 — vector insert / remove / reorder
;; =========================================================================

(defn r6-vector-insert
  "R6 — vector insert with LCS shift detection. `[:a :b :c]` →
  `[:a :NEW :b :c]`. After-index 1 carries a `+` for the new element;
  after-indices 2 and 3 paint as `:same-shifted` with `(was 1)` /
  `(was 2)` muted suffixes — the elements moved positionally but
  carry the same identity."
  []
  {:before [:a :b :c]
   :value  [:a :NEW :b :c]
   :opts   {:full-with-diff? true
            :default-expanded-depth 3}})

(defn r6-vector-remove
  "R6 — vector remove with LCS shift detection. `[:a :b :c :d]` →
  `[:a :c :d]`. Surviving after-indices 1 (`:c`) and 2 (`:d`) carry
  `(was 2)` / `(was 3)` muted suffixes. The removed element `:b`
  lives on the `:vector-removals` channel — the renderer can interleave
  a `−` row in the pure-diff lens."
  []
  {:before [:a :b :c :d]
   :value  [:a :c :d]
   :opts   {:full-with-diff? true
            :default-expanded-depth 3}})

(defn r6-vector-reorder
  "R6 — vector reorder. `[:a :b :c]` → `[:c :a :b]`. Demonstrates LCS
  shift-detection (vs. the naive positional compare that would render
  every element as `:modified`). The smallest Editscript transcript
  is `[[[0] :+ :c] [[3] :-]]` — one insert + one remove."
  []
  {:before [:a :b :c]
   :value  [:c :a :b]
   :opts   {:full-with-diff? true
            :default-expanded-depth 3}})

;; =========================================================================
;; R7 — type-change containers
;; =========================================================================

(defn r7-type-change
  "R7 — type-change container. `{:flash \"hi\"}` → `{:flash {:level
  :ok}}`. The string-to-map flip classifies as `:modified` (not
  `:children`); the value column shows the new map shape; the suffix
  reads `← was \"hi\"` via the `mini` renderer."
  []
  {:before {:flash "hi"}
   :value  {:flash {:level :ok}}
   :opts   {:full-with-diff? true
            :default-expanded-depth 3}})

;; =========================================================================
;; R8 — sensitive-redaction (curated suffix)
;; =========================================================================

(defn r8-was-redacted-now-visible
  "R8 — `was redacted, now visible`. The before-side carries the
  `:rf/redacted` sentinel; the after-side carries a real value. The
  row reads as `:modified` with curated suffix `← was redacted` (no
  sentinel marker text leak)."
  []
  {:before {:user-id :rf/redacted}
   :value  {:user-id "uid-12345"}
   :opts   {:full-with-diff? true}})

(defn r8-was-visible-now-redacted
  "R8 — `was visible, now redacted`. Inverse of the previous case.
  The after-side carries the `:rf/redacted` sentinel; suffix reads
  `← now redacted` without printing the prior value (the redaction
  was probably applied for a reason)."
  []
  {:before {:user-id "uid-12345"}
   :value  {:user-id :rf/redacted}
   :opts   {:full-with-diff? true}})

;; =========================================================================
;; Combination + edge-case demonstrations
;; =========================================================================

(defn combo-all-ops-cascade
  "All-ops cascade — the §2 canonical example from the findings doc.
  `:counter` modified + `:user :name` modified + `:flash` wholly-new +
  `:legacy-flag` removed. The full grammar in one mount."
  []
  {:before {:counter     5
            :user        {:id 7 :name "Ada"}
            :legacy-flag true}
   :value  {:counter 6
            :user    {:id 7 :name "Ada Lovelace"}
            :flash   {:level :ok :text "Order placed"}}
   :opts   {:full-with-diff? true}})

(defn combo-mixed-ops-subtree
  "Mixed-ops subtree — `:user` has BOTH `:added` AND `:removed` AND
  `:modified` descendants. The R5 wholly-added-collapse rule does NOT
  trigger (mixed → per-leaf marking returns). Each leaf paints its
  own glyph + wash; the rail aggregates."
  []
  {:before {:user {:id 7 :name "Ada" :legacy "x"}}
   :value  {:user {:id 7 :name "Ada Lovelace" :email "ada@example.com"}}
   :opts   {:full-with-diff? true
            :default-expanded-depth 3}})

(defn combo-deeply-nested-change
  "Deeply-nested change at depth 5+. Verifies that triangles + rails +
  chips don't pile up uselessly. Single leaf change at the bottom
  produces a single chain of `:children` ancestors with their rails."
  []
  (let [path [:tenant :acme :department :eng :team :status]]
    {:before (assoc-in {} path :idle)
     :value  (assoc-in {} path :active)
     :opts   {:full-with-diff? true
              :default-expanded-depth 6}}))

(defn combo-long-vector-one-insert
  "Long-vector-one-insert (the LCS payoff demo). 12-element vector
  with one element inserted at index 4. Under naive compare this
  would render as 8 rows of `:modified`; under LCS it's 1 `+` + 7
  `(was N)` muted suffixes."
  []
  (let [base (mapv (fn [i] (keyword (str "item-" i))) (range 12))
        ins  (vec (concat (take 4 base) [:NEW-AT-4] (drop 4 base)))]
    {:before base
     :value  ins
     :opts   {:full-with-diff? true
              :default-expanded-depth 3}}))

(defn combo-empty-diff
  "Empty diff — `before == after`. Mode-3 must render correctly when
  there's no change; no glyphs, no chips, no rails, no washes."
  []
  (let [v {:counter 5
           :user {:id 7 :name "Ada Lovelace"}}]
    {:before v
     :value  v
     :opts   {:full-with-diff? true}}))

(defn combo-massive-diff
  "Massive diff — every key in a small app-db got new values. Verifies
  the chrome doesn't melt into noise. Each row paints its own ~ +
  wash; the operator's eye still tracks the cascade because every
  row carries the same glyph at the same column."
  []
  {:before {:counter 1 :user-id 1 :session 1 :flash 1 :route :home}
   :value  {:counter 2 :user-id 2 :session 2 :flash 2 :route :dashboard}
   :opts   {:full-with-diff? true}})

(defn combo-mode-toggle-three-up
  "Mode-toggle three-up — the same diff rendered in all THREE modes
  side-by-side. Operator sees the relationship between `:diff` (flat
  list) / `:full` (no chrome) / `:full+diff` (mode-3 with annotations).

  The Story workspace renders three cards side-by-side; the gallery
  wires each card to a different `:full-with-diff?` + (caller side)
  mode opt. For this fixture we provide the same before/after data;
  the gallery's three-up workspace picks the three modes to display."
  []
  {:before {:counter 5 :user {:id 7 :name "Ada"}}
   :value  {:counter 6 :user {:id 7 :name "Ada Lovelace"}
            :flash {:level :ok}}
   :opts   {:full-with-diff? true}})

;; =========================================================================
;; Theme + density variants
;; =========================================================================

(defn theme-density-narrow-panel
  "Narrow-panel render — the chrome must stay readable at L4-panel-on-
  laptop-screen widths (320px wide). The `(was N)` suffixes + `[N∆]`
  chips must not wrap awkwardly. Wrap the mount in a narrow CSS
  container; the variant uses the same data as `combo-all-ops-cascade`
  with a width-constraining wrapper at the gallery view layer."
  []
  {:before {:counter     5
            :user        {:id 7 :name "Ada"}
            :legacy-flag true}
   :value  {:counter 6
            :user    {:id 7 :name "Ada Lovelace"}
            :flash   {:level :ok :text "Order placed"}}
   :opts   {:full-with-diff? true
            :narrow-panel? true}})
