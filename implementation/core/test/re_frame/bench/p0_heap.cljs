(ns re-frame.bench.p0-heap
  "EP-0038 P0 — RETAINED HEAP per boundary, the second red-zone axis.

  The delegated ruling on rf2-2rtt6.1 sets a UIx threshold on the clock
  AND on retained heap, per witness family, so this axis is not a
  supporting number either.

  ## Retention, not allocation, and why that is not a preference

  V8's CDP SAMPLING heap profiler **drops the samples of collected
  objects**. Pointed at a mount/unmount loop it reports the residue of a
  page that has already been discarded, not the cost of the page: on the
  predecessor's instrument the same 80,000 objects read 4.77 MB when a
  global held them and 0.00 MB when nothing did. Nothing here counts
  allocations.

  Retention is also the right question. What an application pays for a
  boundary is what the boundary keeps STANDING — the hook records, the
  subscription registration, the fiber — not what it touched on the way
  up. The budget in validation.md is stated the same way: exclusive
  retained per boundary, ~0.4-0.5 KB target, > 1 KB fails on paper.

  ## The shape of a reading

  Mount K roots and KEEP them. The driver forces a full collection, reads
  the heap, and only then asks this namespace to mount. Nothing is timed
  and nothing is unmounted inside a window. Then the roots are released,
  the heap is collected and read again — and that second read is a control
  in its own right, because a substrate whose released heap does not
  return to baseline is retaining something after unmount, which is a
  different and worse finding than a large per-boundary figure.

  Every mount is verified at the DOM: `mount!` counts the boundary
  elements the arm should have produced and answers the count beside the
  expectation. An arm that silently rendered nothing would otherwise read
  as the leanest substrate in the table.

  ## Segments here too

  One adapter per process, so the Reagent-on-subs and UIx-on-subs arms
  cannot both be live at once. `prepare!` swaps the adapter and stands the
  frame back up, and the DRIVER calls it BEFORE the baseline read of the
  pair it is about to take — so the swap's own residue lands in the
  baseline rather than in the arm. The floor is measured in both segments
  and is the shared calibrator, exactly as on the clock.

  ## What a JS-heap reading cannot see

  DOM nodes live in Blink's C++ heap, not V8's, so none of these figures
  contain the elements themselves. Every arm builds the identical DOM —
  the clock run's canonical-DOM parity gate is what establishes that — so
  the omission cancels in `arm - floor`, which is the exclusive figure the
  red-zone is set from. The absolute column is a JS-heap figure and is
  labelled as one.

  ## Cache cardinality is part of the witness (rf2-2rtt6.16, rf2-5prok)

  A retained-bytes-per-boundary figure is only defined relative to how
  many boundaries share a subscription, so every reading here stamps **B**
  (boundaries), **E** (boundary-query edges) and **Q** (unique live query
  keys). B is read back off the DOM and E follows from B and the arm's
  read count; Q is the one a page cannot see from the outside, so
  [[mount!]] asks the frame's own sub-cache how many entries it is
  holding and the driver refuses a mount whose answer is not the Q the
  plan asked for. A Q that is asserted rather than counted is the same
  class of decoration as a mount count that is printed and not gated.

  Owner: rf2-2rtt6.1 (standard); this arm rf2-2rtt6.4; the fan-out family
  and the additive model rf2-5prok."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [clojure.string :as str]
            [re-frame.bench.hicasso.arm1.mount :as hic-mount]
            [re-frame.bench.hicasso.arm1.runtime :as hic-rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.order-guard :as guard]
            [re-frame.bench.p0-arms :as arms]
            [re-frame.bench.p0-fixture :as fx]
            [re-frame.bench.p0-floor :as floor]
            [re-frame.bench.p0-harness :as h]
            [re-frame.bench.p0-hicasso :as hic]
            [re-frame.bench.p0-reagent :as rg]
            [re-frame.bench.p0-uix :as ux]
            [re-frame.frame :as frame]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [uix.dom :as uix-dom]))

(def per-root
  "Boundaries in one root of the `grid` family — the same 300 the clock
  rows drive, so a per-boundary figure here and a bulk ratio there
  describe the same page."
  fx/cells-n)

(def rows-per-root fx/w1-rows)

;; ---------------------------------------------------------------------------
;; Arms — keyed `family/substrate`, the same naming the driver schedules on
;; ---------------------------------------------------------------------------

;; `element-of` / `form-of` take the ROOT INDEX. The published arms ignore
;; it — four roots of `grid/uix` are four copies of one page, which is the
;; shape that produced the fan-out finding in the first place — but the
;; fan-out family needs it, because a root's boundaries are numbered from
;; its own base in a GLOBAL numbering and that base is the only thing
;; separating "four roots sharing every key" from "four roots sharing
;; none".

(defn- react-root-arm [element-of selector expected]
  {:selector    selector
   :expected    expected
   :mount-one   (fn [c i]
                  (let [r (react-dom-client/createRoot c)]
                    (react-dom/flushSync (fn [] (.render r (element-of i))))
                    r))
   :unmount-one (fn [r] (.unmount r))})

(defn- reagent-root-arm [form-of selector expected]
  {:selector    selector
   :expected    expected
   :mount-one   (fn [c i]
                  (let [rt (rdc/create-root c)]
                    (react-dom/flushSync (fn [] (rdc/render rt (form-of i))))
                    rt))
   :unmount-one (fn [rt] (rdc/unmount rt))})

(defn- uix-root-arm [element-of selector expected]
  {:selector    selector
   :expected    expected
   :mount-one   (fn [c i]
                  (let [rt (uix-dom/create-root c)]
                    (react-dom/flushSync (fn [] (uix-dom/render-root (element-of i) rt)))
                    rt))
   :unmount-one (fn [rt] (uix-dom/unmount-root rt))})

(defn- hicasso-root-arm
  "One root of the Hicasso candidate arm (rf2-2rtt6.34), through Arm 1's
  OWN root door — `arm1.mount/root!` installs the frame provider, renders
  inside a `flushSync` and returns the handle its `release!` takes.

  The unmount is NOT `arm1.mount/release!`, and the difference is the
  whole survival metric. `release!` ends with `runtime/reset-runtime!`,
  which drops every cell, edge and cached entry by force — an arm torn
  down that way would answer zero residue whatever it had leaked, and
  would also destroy the sibling roots of a multi-root arm. This unmounts
  the root and nothing else, so React's own `useSyncExternalStore`
  cleanup is what releases the edges and the references, and the residue
  the driver reads afterwards is a measurement rather than a reset."
  [hiccup-of selector expected]
  {:selector    selector
   :expected    expected
   :mount-one   (fn [c i] (hic-mount/root! c arms/frame-id (hiccup-of i)))
   :unmount-one (fn [handle]
                  (react-dom/flushSync (fn [] (.unmount (:root handle)))))})

(def arm-table
  "`arm-id -> {:segment :selector :expected :keys-expected :mount-one
  :unmount-one}`.

  `:segment` is which adapter the arm needs; the driver calls `prepare!`
  with it before taking the baseline of the pair. A floor arm names the
  segment it is being measured IN, because the floor is the calibrator and
  has to be read on both sides of the seam.

  `:keys-expected` is **Q**, and it is written down here rather than
  derived, because the published arms are exactly the ones whose Q the
  ruling had to reconstruct by reading the source: every root of
  `grid/uix` renders the same `[:p0/cell 0…299]` vectors against the same
  frame, so four roots hold **300** reactions and not 1,200. That claim is
  now a number this instrument checks on every mount instead of an
  argument about a file."
  {:list/floor
   (assoc (react-root-arm (fn [_] (floor/w1 (mapv fx/row-value (range rows-per-root))))
                          ".row" rows-per-root)
          :segment nil :keys-expected 0)

   :list/reagent
   (assoc (reagent-root-arm (fn [_] (rg/w1-root arms/frame-id rows-per-root))
                            ".row" rows-per-root)
          :segment :reagent-subs :keys-expected rows-per-root)

   :list/uix
   (assoc (uix-root-arm (fn [_] (ux/w1-root arms/frame-id rows-per-root))
                        ".row" rows-per-root)
          :segment :uix-subs :keys-expected rows-per-root)

   :grid/floor
   (assoc (react-root-arm (fn [_] (floor/u-grid (vec (repeat per-root 0))))
                          ".cell" per-root)
          :segment nil :keys-expected 0)

   :grid/reagent
   (assoc (reagent-root-arm (fn [_] (rg/u-root arms/frame-id)) ".cell" per-root)
          :segment :reagent-subs :keys-expected per-root)

   :grid/uix
   (assoc (uix-root-arm (fn [_] (ux/u-root arms/frame-id)) ".cell" per-root)
          :segment :uix-subs :keys-expected per-root)})

;; ---------------------------------------------------------------------------
;; The fan-out family — B, E and Q moved independently (rf2-5prok)
;; ---------------------------------------------------------------------------

(defn- fan-arm
  "One rung of the fan-out sweep: `reads` subscription reads on each of the
  same `.cell` boundaries the `grid` family holds, drawn from `q` unique
  query keys across the whole page.

  Constructed per mount rather than tabled, because the rung IS the
  parameters. B is `ROOTS × per-root` and does not move; E is `B × reads`;
  Q is `q`. Mean fan-out E/Q therefore falls out of the plan and is not a
  property of a hand-written page."
  [substrate reads q]
  (let [root-of (case substrate
                  :reagent (fn [r] (rg/fan-root arms/frame-id (* r per-root) per-root reads))
                  :uix     (fn [r] (ux/fan-root arms/frame-id (* r per-root) per-root reads)))
        base    (case substrate
                  :reagent (reagent-root-arm root-of ".cell" per-root)
                  :uix     (uix-root-arm root-of ".cell" per-root))]
    (assoc base
           :segment       (case substrate :reagent :reagent-subs :uix :uix-subs)
           :reads         reads
           ;; A boundary that reads NOTHING holds no cache entry, so the
           ;; R=0 rung's Q is 0 whatever `q` says. Stating it here keeps
           ;; the driver from having to special-case its own shell rung.
           :keys-expected (if (zero? (long reads)) 0 (long q)))))

;; ---------------------------------------------------------------------------
;; The ladder family — 1/3/7/20 reads at Q = E, three substrates (rf2-2rtt6.34)
;; ---------------------------------------------------------------------------
;;
;; The fan family moves Q with B and E/B held near-fixed; this one moves
;; READS with B fixed and Q pinned to E — the mandatory distinct-query
;; witness, which is the regime both published per-read gates are stated
;; in (validation.md:136-140). It carries three substrates rather than
;; two, because the candidate has to be judged against donor rows taken
;; on ITS OWN instrument (validation.md:180-189) and this instrument has
;; no 3/7/20 rung on any substrate until now.
;;
;; `fx/fan-key` supplies the keys unchanged: at Q = B·R the rule
;; `(n·R + k) mod Q` is the identity over `0 … B·R−1`, so every one of
;; the E edges lands on its own key and Q = E exactly. The DOM is
;; identical at every rung on every substrate — one `span.cell` per
;; boundary carrying `data-i` and the one-character text `0`, because
;; every `:p0/fan` value is 0 and a sum of zeros is zero — so the floor
;; subtraction stays honest as R grows.

(defn- lad-arm
  "One rung of the ladder: `reads` DISTINCT subscription reads on each of
  the same `.cell` boundaries the `grid` family holds, drawn from `q`
  unique query keys — and at Q = E the driver states `q` as `B·reads`."
  [substrate reads q]
  (let [reads   (long reads)
        base    (case substrate
                  :reagent (reagent-root-arm
                             (fn [r] (rg/lad-root arms/frame-id (* r per-root) per-root reads))
                             ".cell" per-root)
                  :uix     (uix-root-arm
                             (fn [r] (ux/lad-root arms/frame-id (* r per-root) per-root reads))
                             ".cell" per-root)
                  :hicasso (hicasso-root-arm
                             (fn [r] (hic/lad-grid (* r per-root) per-root reads))
                             ".cell" per-root))]
    (assoc base
           ;; The candidate reads through `re-frame.subs` and the
           ;; substrate's single internal frame context, so it needs
           ;; NEITHER adapter's hooks and mounts correctly in either
           ;; segment. It is measured in BOTH — and the first run of this
           ;; row showed why that is a measurement rather than a seam
           ;; check. Its R=0 shell read IDENTICALLY in the two segments
           ;; (1,141 B either side) while its R=1 rung did not, because
           ;; `subs/subscribe` builds a reaction whose implementation
           ;; comes from the INSTALLED ADAPTER's reactive substrate. The
           ;; two candidate columns are therefore one view layer over two
           ;; subscription substrates, which is exactly the pair a
           ;; per-read claim has to be stated against: the arm cannot be
           ;; cheaper than the reactions it holds.
           :segment       (case substrate
                            :reagent :reagent-subs
                            :uix     :uix-subs
                            :hicasso nil)
           :reads         reads
           ;; A boundary that reads NOTHING holds no cache entry, so the
           ;; R=0 rung's Q is 0 whatever `q` says — [[fan-arm]]'s rule,
           ;; unchanged.
           :keys-expected (if (zero? reads) 0 (long q)))))

(defn- arm-for
  "Resolve `arm-id` to an arm map. `:fan/*` and `:lad/*` are
  parameterised by `opts` (`{:reads r :keys q}`); everything else is the
  published table."
  [arm-id opts]
  (or (get arm-table arm-id)
      (case arm-id
        :fan/reagent (fan-arm :reagent (:reads opts) (:keys opts))
        :fan/uix     (fan-arm :uix     (:reads opts) (:keys opts))
        :lad/reagent (lad-arm :reagent (:reads opts) (:keys opts))
        :lad/uix     (lad-arm :uix     (:reads opts) (:keys opts))
        :lad/hicasso (lad-arm :hicasso (:reads opts) (:keys opts))
        nil)))

;; ---------------------------------------------------------------------------
;; Segment preparation — always OUTSIDE the baseline read
;; ---------------------------------------------------------------------------

(defn prepare!
  "Install the adapter `segment-id` names and stand the frame back up.

  Called by the driver BEFORE its baseline read, so the adapter swap's own
  residue is in the baseline and not in the arm's retained delta. Passing
  a segment that is already installed still re-seeds, so the two branches
  cost the same."
  [segment-id]
  ;; The Hicasso runtime memoises `capture-frame` per frame id, and
  ;; `enter-segment!` destroys and re-creates `:p0/frame` — a bundle
  ;; captured before the swap is pinned to a dead incarnation. Resetting
  ;; here rather than after an arm is deliberate: `prepare!` runs OUTSIDE
  ;; every measured window and before the baseline read, so the reset's
  ;; own residue lands in the baseline, and no arm's teardown is ever
  ;; forced — which is what leaves the survival metric something to
  ;; measure (rf2-2rtt6.34).
  (hic-rt/reset-runtime!)
  (let [segment (first (filter #(= segment-id (:id %)) arms/segments))]
    (when segment (arms/enter-segment! segment)))
  true)

;; ---------------------------------------------------------------------------
;; Holding, and letting go
;; ---------------------------------------------------------------------------

(defonce ^:private held (atom nil))

(defn- release!*
  []
  (when-some [{:keys [unmount-one handles containers]} @held]
    (doseq [hd (reverse handles)]
      (unmount-one hd))
    (doseq [c containers] (.remove c))
    (reset! held nil))
  nil)

(defn- live-key-count
  "How many UNIQUE query keys the frame's subscription cache is holding —
  **Q**, counted rather than asserted.

  The production cache is `{query-vector {:reaction … :ref-count n}}` per
  frame and an entry is evicted in-tick when its last derefer drops
  (`re-frame.subs.cache`), so between arms this reads 0 and during an arm
  it reads exactly the number of distinct query vectors the mounted page
  holds — independently of how many boundaries hold each one. That is the
  quantity the two published heap families disagreed about, and it is
  cheaper to count it than to argue about it."
  []
  (if-some [f (frame/frame arms/frame-id)]
    (count @(:sub-cache f))
    0))

(defn mount!
  "Mount `k` roots of `arm-id` and KEEP them. Answers the DOM read-back
  over the boundary class the arm should have produced, AND the sub-cache
  read-back over the unique query keys the plan said it would hold.

  `opts` carries the fan-out rung — `{:reads r :keys q}` — and is ignored
  by the published arms. Q is set on the fixture BEFORE the first root
  mounts and once for the whole arm, because the roots of an arm share one
  frame and therefore one key space.

  A literal `#js` object rather than `clj->js`: `clj->js` would render
  `:ok?` as the key `\"ok?\"` and a driver reading `verify.ok` would see
  `undefined` — every mount reported unverified while every mount was
  fine. That happened, on the predecessor's first run."
  [arm-id k opts]
  (release!*)
  (let [opts       (js->clj opts :keywordize-keys true)
        arm        (arm-for (keyword arm-id) opts)
        {:keys [mount-one unmount-one selector expected keys-expected]} arm
        _          (when-some [q (:keys opts)] (fx/set-fan-keys! q))
        containers (mapv (fn [_] (h/container!)) (range k))
        handles    (into [] (map-indexed (fn [i c] (mount-one c i))) containers)
        elements   (.-length (.querySelectorAll js/document selector))
        want       (* k expected)
        live       (live-key-count)]
    (reset! held {:arm (keyword arm-id) :unmount-one unmount-one
                  :handles handles :containers containers})
    (let [hic (hic-rt/residue)]
      #js {:elements     elements
           :expected     want
           :keys         live
           :keysExpected keys-expected
           :ok           (and (= elements want) (= live keys-expected))
           ;; THE STRUCTURAL STAMP, counted off the Hicasso runtime on
           ;; every mount of every arm (rf2-2rtt6.34). On a candidate arm
           ;; it is what makes "one hook plus N edges in a shared index" a
           ;; number the driver gates rather than a sentence in a
           ;; docstring: `boundaries` must be B WHERE ANYTHING IS READ,
           ;; `edges` must be E, `cells` must be Q, and `entries` must be
           ;; B at Q = E — one read-set entry per boundary, because on the
           ;; distinct-query witness no two boundaries read the same SET
           ;; and the entry cache's sharing buys nothing. With no reads at
           ;; all `boundaries` is 0 and `entries` is 1: since rf2-dabt3
           ;; fused the sub-index into the cell table the runtime knows a
           ;; boundary only through the cells it reads, so an edgeless one
           ;; retains no membership and is correctly absent. On a DONOR
           ;; arm every one of them must be zero, which is the check that
           ;; the candidate's runtime is not standing behind the rows it
           ;; is compared to.
           :hicasso      #js {:cells      (:cells hic)
                              :cellRefs   (:cell-refs hic)
                              :boundaries (:boundaries hic)
                              :edges      (:edges hic)
                              :entries    (:entries hic)}})))

;; ---------------------------------------------------------------------------
;; The positive control
;; ---------------------------------------------------------------------------

(defonce ^:private control-cell (atom nil))

(defn control!
  "Hold a dense JS array of exactly `n` doubles and answer its length, so
  the allocation cannot be proved dead and elided.

  V8 stores a packed double array as `n` unboxed 8-byte slots behind a
  small header, so the retained cost is **8n bytes** — PREDICTED, not
  merely observed. That is the entire point of a control: a figure the
  instrument has to hit, not one it gets to report.

  The predecessor's first draft of this control was a flat one-byte string
  of `8n` characters, on the same reasoning, and it was a FICTION: a
  4.7 MB `'x'.repeat(…)` reads as 6 KB on all three readers because V8
  does not materialise the characters. Had it shipped, the control would
  have missed by three orders of magnitude every round and the natural
  conclusion would have been that the instrument was broken."
  [n]
  (let [a (js/Array. n)]
    (dotimes [i n] (aset a i (+ i 0.5)))
    (reset! control-cell a)
    (.-length a)))

(defn control-release! [] (reset! control-cell nil) 0)

;; ---------------------------------------------------------------------------
;; THE ADDITIVE MODEL — and the rule that decides whether it may be priced
;; ---------------------------------------------------------------------------
;;
;; The heap-regime ruling (rf2-2rtt6.16 Part 3) adopts a target shape for
;; the budget: a boundary's retained cost decomposes into a SHELL, a
;; per-EDGE term (one boundary's attachment to one query) and a per-unique
;; -KEY term (one cached reaction, however many boundaries hold it).
;;
;;     y  =  shell  +  (E/B)·edge  +  (Q/B)·key
;;
;; It also REFUSES to freeze those numbers from cross-instrument algebra,
;; because the paired rows came from different instruments and runs — and
;; it makes this sweep the gate. So the sweep has to do two things a fit
;; alone does not: identify each term from a CONTRAST that moves one
;; factor at a time, and then try to BREAK the model on a rung it was not
;; fitted to.
;;
;; ## Two models, because the published ladder already doubts the first
;;
;; **M3** is the ruling's shape exactly, three terms. **M4** adds one:
;;
;;     y = shell + [E>0]·step + (E/B)·edge + (Q/B)·key
;;
;; where `step` is what a boundary pays for SUBSCRIBING AT ALL, over and
;; above what it pays per read. There is already evidence for it: the
;; reads ladder's Reagent curve fits `397 + 943·R` at r² 0.9988 while its
;; measured R=0 shell reads 428 and its R=1 rung reads 1,562 — a first
;; read that costs 224 B more than the line, invisible in an r² taken
;; against a 19 KB range. M3 says that number is zero. Deciding between
;; them is this sweep's job; assuming M3 and reporting its terms would be
;; assuming the answer.
;;
;; ## Identification — each term from one contrast
;;
;;   shell  the R=0 rung, on its own.
;;   key    the SLOPE of the R=1 family in Q/B. E/B is pinned at 1 across
;;          those four rungs, so nothing but the number of unique keys is
;;          moving.
;;   edge   `R2QB2 − R1Q2`: two rungs at the SAME Q, differing by one read
;;          per boundary. One extra edge each, no extra keys, nothing else.
;;   step   `(intercept − shell) − edge`. The intercept of the R=1 family
;;          is everything a reading boundary pays that is not per-key; the
;;          contrast says how much of that is per-EDGE; the remainder is
;;          the term M3 says does not exist.
;;
;; ## Falsification — one rung is held out
;;
;; `R2Q2B` (two reads, every one of them distinct) is measured and never
;; used to identify anything. Both models predict it from the terms above
;; and the page publishes both predictions. A model that survives that is
;; a model that priced a rung it had never seen.
;;
;; The verdict is not an instrument fault and does not stop a run. It
;; decides whether the numbers may be QUOTED as component prices, and
;; under which shape — which is exactly what the ruling gated.

(def additive-criterion
  "The thresholds, fixed before the sweep ran and not moved after it.

  `:min-r2` — the R=1 family must be a line in Q/B. 0.98 over four rungs
  is well inside what this instrument has shown it can resolve (the reads
  ladder returned r² ≥ 0.9987 on both substrates) and far outside what a
  page whose shared reactions were priced differently from its exclusive
  ones would return. This one gates everything: without it there is no
  per-unique-key price to argue about.

  `:max-oos-error` — the held-out rung must be predicted within 10%. It
  sits at 3–13 KB per boundary and this instrument's per-arm ranges are
  well under 1% wide, so 10% is a margin for the MODEL, not for the
  reader.

  `:max-key-disagreement` — the per-key term solved from the R=2 pair
  alone must agree with the R=1 slope within 15%. Two disjoint subsets of
  the sweep, one arithmetic.

  `:max-step-frac` — how big `step` may be, as a fraction of the whole
  fan-out-1 boundary, and still be called zero. 10%: a term that moves a
  distinct-query boundary by a tenth is not a rounding error, and a
  budget that named it `edge` would misprice every page whose fan-out is
  not 1."
  {:min-r2 0.98 :max-oos-error 0.10 :max-key-disagreement 0.15 :max-step-frac 0.10})

(defn- ols
  "Ordinary least squares of `[x y]` pairs. Answers `{:slope :intercept
  :r2 :n}`. `:r2` is 1.0 for a degenerate y — a family that does not vary
  is perfectly explained by a flat line, and calling that 0 would refuse
  the one case where the model is trivially exact."
  [pts]
  (let [n   (count pts)
        xs  (mapv first pts)
        ys  (mapv second pts)
        mx  (/ (reduce + xs) n)
        my  (/ (reduce + ys) n)
        sxx (reduce + (map (fn [x] (let [d (- x mx)] (* d d))) xs))
        sxy (reduce + (map (fn [x y] (* (- x mx) (- y my))) xs ys))
        slope (if (zero? sxx) 0.0 (/ sxy sxx))
        b0  (- my (* slope mx))
        syy (reduce + (map (fn [y] (let [d (- y my)] (* d d))) ys))
        ssr (reduce + (map (fn [x y] (let [e (- y (+ b0 (* slope x)))] (* e e))) xs ys))]
    {:slope slope :intercept b0 :n n
     :r2 (if (zero? syy) 1.0 (- 1.0 (/ ssr syy)))}))

(defn- pct [x] (str (.toFixed (* 100.0 x) 2) "%"))

(defn additive-fit
  "Price the terms from one substrate's rungs and adjudicate which model —
  if either — may carry them into a budget.

  `rungs` is a seq of `{:rung :reads :keys :boundaries :y}`, where `y` is
  exclusive retained bytes per boundary (arm − floor). Answers a map whose
  `:model` is `\"M3\"`, `\"M4\"` or `nil`; `nil` means the page publishes
  the ROWS and not the PRICES, and says which check refused."
  [rungs {:keys [min-r2 max-oos-error max-key-disagreement max-step-frac]}]
  (let [xof      (fn [{:keys [keys boundaries]}] (/ (double keys) (double boundaries)))
        of-reads (fn [r] (vec (sort-by xof (filterv #(= r (long (:reads %))) rungs))))
        zero     (first (of-reads 0))
        ones     (of-reads 1)
        twos     (of-reads 2)
        pair     (fn [xs x] (first (filter #(< (js/Math.abs (- (xof %) x)) 1e-9) xs)))]
    (if (or (nil? zero) (< (count ones) 3) (< (count twos) 2))
      {:ok?    false
       :model  nil
       :why    (str "the sweep is missing a rung the model needs — R=0 ×"
                    (if zero 1 0) ", R=1 ×" (count ones) ", R=2 ×" (count twos)
                    " (need 1, ≥3, 2)")
       :checks []}
      (let [{:keys [slope intercept r2]} (ols (mapv (fn [g] [(xof g) (:y g)]) ones))
            shell    (:y zero)
            key-term slope
            ;; The identification pair: the LOWER-Q of the two R=2 rungs
            ;; and the R=1 rung at the same Q. One extra edge per boundary
            ;; and nothing else moved.
            lo       (first twos)
            hi       (peek twos)
            twin     (pair ones (xof lo))
            edge-c   (when twin (- (:y lo) (:y twin)))
            edge-i   (- intercept shell)
            step     (when edge-c (- edge-i edge-c))
            ;; The HELD-OUT rung: never used above, predicted by both.
            m3       (+ shell (* 2.0 edge-i) (* (xof hi) key-term))
            m4       (when step
                       (+ shell step (* 2.0 edge-c) (* (xof hi) key-term)))
            err      (fn [p] (when p (/ (- p (:y hi)) (:y hi))))
            m3-err   (err m3)
            m4-err   (err m4)
            dx       (- (xof hi) (xof lo))
            key-2    (when-not (zero? dx) (/ (- (:y hi) (:y lo)) dx))
            key-gap  (when (and key-2 (not (zero? key-term)))
                       (/ (- key-2 key-term) key-term))
            fan1     (+ intercept key-term)          ; the whole Q/B = 1 boundary
            step-lim (* max-step-frac (js/Math.abs fan1))
            linear?  (>= r2 min-r2)
            key-ok?  (boolean (and key-gap (<= (js/Math.abs key-gap) max-key-disagreement)))
            step-0?  (boolean (and step (<= (js/Math.abs step) step-lim)))
            m3-ok?   (boolean (and m3-err (<= (js/Math.abs m3-err) max-oos-error)))
            m4-ok?   (boolean (and m4-err (<= (js/Math.abs m4-err) max-oos-error)))
            model    (cond
                       (not (and linear? key-ok?)) nil
                       (and step-0? m3-ok?)        "M3"
                       m4-ok?                      "M4"
                       :else                       nil)
            checks
            [{:name "the R=1 family is a LINE in Q/B — the per-unique-key price exists"
              :ok   linear?
              :detail (str "r² " (.toFixed r2 5) " over " (count ones)
                           " rungs (floor " min-r2 ")")}
             {:name "the per-key term from the R=2 PAIR agrees with the R=1 slope"
              :ok   key-ok?
              :detail (if key-gap
                        (str "R=2 pair " (.toFixed key-2 0) " B vs R=1 slope "
                             (.toFixed key-term 0) " B — " (pct key-gap))
                        "the two R=2 rungs share a Q/B; the pair cannot price a slope")}
             {:name "M3 — the per-edge term is the same priced from the intercept and from the contrast"
              :ok   step-0?
              :detail (if step
                        (str "intercept − shell = " (.toFixed edge-i 0)
                             " B, R2QB2 − R1Q2 = " (.toFixed edge-c 0)
                             " B, so step = " (.toFixed step 0) " B against a "
                             (.toFixed step-lim 0) " B band ("
                             (.toFixed (* 100.0 max-step-frac) 0) "% of the Q/B=1 boundary)")
                        "no R=1 rung shares a Q with an R=2 rung — the contrast is unavailable")}
             {:name "M3 predicts the HELD-OUT R2Q2B rung"
              :ok   m3-ok?
              :detail (str "predicted " (.toFixed m3 0) " B, measured "
                           (.toFixed (:y hi) 0) " B — " (pct m3-err))}
             {:name "M4 (M3 + a per-subscribing-boundary step) predicts the HELD-OUT R2Q2B rung"
              :ok   m4-ok?
              :detail (if m4
                        (str "predicted " (.toFixed m4 0) " B, measured "
                             (.toFixed (:y hi) 0) " B — " (pct m4-err))
                        "step unavailable, so M4 is unidentified")}]]
        {:ok?            (some? model)
         :model          model
         :checks         checks
         :shell          shell
         :key            key-term
         :key-alt        key-2
         :edge-intercept edge-i
         :edge-contrast  edge-c
         :edge           (if (= model "M3") edge-i edge-c)
         :step           step
         :intercept      intercept
         :r2             r2
         :held-out       {:rung (:rung hi) :measured (:y hi)
                          :m3 m3 :m3-error m3-err :m4 m4 :m4-error m4-err}
         :criterion      {:min-r2 min-r2
                          :max-oos-error max-oos-error
                          :max-key-disagreement max-key-disagreement
                          :max-step-frac max-step-frac}
         :why            (case model
                           "M3" "ADDITIVE (M3) — shell / per-edge / per-unique-key may be quoted"
                           "M4" (str "ADDITIVE ONLY WITH A FOURTH TERM (M4) — a subscribing "
                                     "boundary pays a step of " (.toFixed step 0)
                                     " B that is neither shell nor per-edge; the three-term "
                                     "budget would misprice every page whose fan-out is not 1")
                           (str "REFUSED: "
                                (str/join "; " (map :name (remove :ok checks)))))}))))

(defn fan-self-test
  "The adjudicator's own positive control, PREDICTED before it is run.

  Three synthetic pages, all built by arithmetic and none measured, each
  one a shape the sweep must tell apart from the others:

  **A** is M3 exactly — shell 400, edge 250, key 900 over B = 1,200. It
  must come back `M3` with those three numbers and a held-out rung
  predicted to the byte.

  **B** is M4 — the same, plus a 400 B step every subscribing boundary
  pays. Its R=1 family is a PERFECT line (r² = 1) and its held-out rung is
  still missed by M3, because M3 has nowhere to put the step but the
  per-edge term and then charges it twice. It must come back `M4`, with
  the step recovered at 400 B.

  **C** carries a quadratic key term (`+300·(Q/B)²`) — what a page whose
  shared reactions were priced differently from its exclusive ones would
  look like. Both models must REFUSE it. Its R=1 family still fits a line
  at r² ≈ 0.997, comfortably past the 0.98 floor, so the linearity check
  is not what catches it.

  B and C are the reason the R=2 rungs are in the sweep at all: a page
  that is not M3 can fit the R=1 family perfectly, and nothing inside that
  family can tell. A rule that cannot fail has not adjudicated anything."
  []
  (let [b     1200.0
        mk    (fn [f] (mapv (fn [[id r q]] {:rung id :reads r :keys q :boundaries b
                                            :y (f (double r) (/ (double q) b))})
                            [["R0" 0 0] ["R1Q1" 1 1200] ["R1Q2" 1 600]
                             ["R1Q4" 1 300] ["R1Q8" 1 150]
                             ["R2Q2B" 2 2400] ["R2QB2" 2 600]]))
        m3*   (mk (fn [r x] (if (zero? r) 400.0 (+ 400.0 (* r 250.0) (* x 900.0)))))
        m4*   (mk (fn [r x] (if (zero? r)
                              400.0
                              (+ 400.0 400.0 (* r 250.0) (* x 900.0)))))
        curvy (mk (fn [r x] (if (zero? r)
                              400.0
                              (+ 400.0 (* r 250.0) (* x 900.0) (* x x 300.0)))))
        fa    (additive-fit m3* additive-criterion)
        fb    (additive-fit m4* additive-criterion)
        fc    (additive-fit curvy additive-criterion)
        near? (fn [a b tol] (and (number? a) (< (js/Math.abs (- a b)) tol)))
        held  (fn [f k] (get-in f [:held-out k]))
        chk   [{:name "A — the exact M3 page comes back M3, priced to its own three terms"
                :ok   (and (= "M3" (:model fa))
                           (near? (:shell fa) 400.0 0.5)
                           (near? (:edge fa) 250.0 0.5)
                           (near? (:key fa) 900.0 0.5)
                           (near? (:step fa) 0.0 0.5))
                :detail (str "shell " (.toFixed (:shell fa) 1)
                             " · edge " (.toFixed (:edge fa) 1)
                             " · key " (.toFixed (:key fa) 1)
                             " · step " (.toFixed (:step fa) 1))}
               {:name "A — its HELD-OUT rung is predicted to the byte"
                :ok   (near? (held fa :m3-error) 0.0 1e-9)
                :detail (str "R2Q2B predicted " (.toFixed (held fa :m3) 1)
                             " B against " (.toFixed (held fa :measured) 1) " B")}
               {:name "B — a per-subscribing-boundary STEP is caught, and M4 carries it"
                :ok   (and (= "M4" (:model fb))
                           (near? (:step fb) 400.0 0.5)
                           (near? (:edge fb) 250.0 0.5)
                           (near? (:key fb) 900.0 0.5)
                           (near? (held fb :m4-error) 0.0 1e-9))
                :detail (str "step " (.toFixed (:step fb) 1)
                             " B; M3 misses the held-out rung by "
                             (pct (held fb :m3-error))
                             ", M4 lands on it")}
               {:name "B — and its R=1 family is a PERFECT line, so nothing inside it could tell"
                :ok   (near? (:r2 fb) 1.0 1e-9)
                :detail (str "r² " (.toFixed (:r2 fb) 6)
                             " — the R=2 rungs are the only reason B and A are distinguishable")}
               {:name "C — a QUADRATIC key term is refused by BOTH models"
                :ok   (and (nil? (:model fc)) (not (:ok? fc)))
                :detail (:why fc)}
               {:name "C — …and not by the r² floor, which it clears"
                :ok   (>= (:r2 fc) (:min-r2 additive-criterion))
                :detail (str "r² " (.toFixed (:r2 fc) 5) " ≥ "
                             (:min-r2 additive-criterion)
                             "; the held-out rung misses by " (pct (held fc :m3-error))
                             " (M3) and " (pct (held fc :m4-error)) " (M4)")}]]
    {:ok? (every? :ok chk) :checks chk}))

;; ---------------------------------------------------------------------------
;; THE READS LADDER — the marginal per-read slope and the shell (rf2-2rtt6.34)
;; ---------------------------------------------------------------------------
;;
;; One line per substrate, `y = intercept + slope · R`, fitted over the
;; MANDATED rungs 1/3/7/20 and over nothing else.
;;
;; **R = 0 rides along as an anchor and is regressed nowhere.** That is
;; not a style preference: the previous publication of this ladder on the
;; freehand instrument regressed `[0 1 3 7 20]` while saying in three
;; places that it excluded the sub-free rung, and the audit of PR #7260
;; withdrew the whole fit over it. The intercept moved 25% when the
;; mistake was corrected. So the exclusion is in the code, and
;; [[ladder-self-test]]'s third check is the regression guard on it.
;;
;; The slope is a MARGINAL cost — what the next read costs once a boundary
;; already reads. The first read is a separate quantity and is reported
;; separately: on Reagent it ran 21% above the marginal slope on the
;; predecessor instrument, and one number standing for both hid that.

(def ladder-criterion
  "`:min-r2` — the rungs must be a LINE in R. A per-read cost that grew
  or shrank with the number of reads would not be a per-read cost at all,
  and the fit is what forecloses that reading. 0.98 is the fan sweep's
  own floor, kept identical so the two families are judged the same way;
  the predecessor ladder returned r² ≥ 0.9987 on both substrates, and a
  page whose per-read term was quadratic reads 0.965 over these rungs —
  which [[ladder-self-test]] check B measures rather than assumes."
  {:min-r2 0.98})

(defn ladder-fit
  "Price one substrate's ladder. `rungs` is a seq of `{:rung :reads :y}`,
  `y` exclusive retained bytes per boundary (arm − floor).

  Answers `{:slope :intercept :r2 :n :shell :first-read :linear?}` —
  `:shell` the DIRECTLY MEASURED R=0 rung (never the fitted intercept),
  `:first-read` the increment `y(1) − y(0)`."
  [rungs {:keys [min-r2]}]
  (let [rungs    (mapv (fn [g] (update g :reads long)) rungs)
        reactive (filterv #(pos? (:reads %)) rungs)
        by-reads (into {} (map (juxt :reads :y)) rungs)
        {:keys [slope intercept r2 n]} (ols (mapv (fn [g] [(:reads g) (:y g)]) reactive))]
    {:slope      slope
     :intercept  intercept
     :r2         r2
     :n          n
     :fitted     (mapv :reads reactive)
     :shell      (get by-reads 0)
     :first-read (when (and (contains? by-reads 0) (contains? by-reads 1))
                   (- (get by-reads 1) (get by-reads 0)))
     :linear?    (and (>= n 2) (>= r2 min-r2))
     :why        (cond
                   ;; A single reactive rung has no slope. OLS answers 0
                   ;; for it (Sxx is zero) and r² answers 1.0 (a family
                   ;; that does not vary is perfectly explained by a flat
                   ;; line), which is a pair of numbers that look like a
                   ;; result and are not one. Say so rather than print it.
                   (< n 2)
                   (str "UNIDENTIFIED — " n " reactive rung(s); a slope needs at least two")

                   (>= r2 min-r2)
                   (str "a line in R over " n " rungs (r² " (.toFixed r2 5) ")")

                   :else
                   (str "NOT a line in R — r² " (.toFixed r2 5) " under the "
                        min-r2 " floor; the slope is not a per-read cost"))}))

(defn ladder-self-test
  "The ladder fit's own control, PREDICTED before anything is measured.

  **A** is an exact line, `y = 400 + 900·R`, with an R=0 rung ON it. Both
  terms must come back to the byte at r² = 1, and the first-read
  increment must equal the slope.

  **B** is a page whose per-read term is QUADRATIC — `y = 400 + 900·R²`,
  what a substrate whose Nth read cost more than its first would look
  like. It must be refused by the r² floor. This is the check that makes
  the floor a criterion rather than a decoration: the arithmetic is fixed,
  so the prediction is stated here in advance — **r² ≈ 0.9646 over
  1/3/7/20, against a 0.98 floor**.

  **C** is A with its R=0 rung moved to an absurd 99,999 B. The fit must
  be BIT-IDENTICAL to A's, because R=0 is regressed nowhere. It is a
  regression guard on the exact defect the audit of PR #7260 found in the
  predecessor ladder, and it is a check of this file's own arithmetic —
  not independent corroboration of anything measured."
  []
  (let [mk    (fn [f] (mapv (fn [r] {:rung (str "R" r) :reads r :y (f (double r))})
                            [0 1 3 7 20]))
        a     (ladder-fit (mk (fn [r] (+ 400.0 (* 900.0 r)))) ladder-criterion)
        b     (ladder-fit (mk (fn [r] (+ 400.0 (* 900.0 r r)))) ladder-criterion)
        c     (ladder-fit (assoc-in (vec (mk (fn [r] (+ 400.0 (* 900.0 r))))) [0 :y] 99999.0)
                          ladder-criterion)
        near? (fn [x y tol] (and (number? x) (< (js/Math.abs (- x y)) tol)))
        chk   [{:name "A — an exact line is recovered to the byte, and R=0 is not in the fit"
                :ok   (and (near? (:slope a) 900.0 1e-6)
                           (near? (:intercept a) 400.0 1e-6)
                           (near? (:r2 a) 1.0 1e-9)
                           (= 4 (:n a))
                           (near? (:shell a) 400.0 1e-9)
                           (near? (:first-read a) 900.0 1e-9))
                :detail (str "slope " (.toFixed (:slope a) 3)
                             " · intercept " (.toFixed (:intercept a) 3)
                             " · r² " (.toFixed (:r2 a) 6)
                             " · fitted over " (pr-str (:fitted a))
                             " · shell " (.toFixed (:shell a) 1)
                             " · first read " (.toFixed (:first-read a) 1))}
               {:name "B — a QUADRATIC per-read term is refused by the r² floor"
                :ok   (and (not (:linear? b)) (near? (:r2 b) 0.9646 5e-4))
                :detail (str "r² " (.toFixed (:r2 b) 5) " against a predicted 0.9646 and a "
                             (:min-r2 ladder-criterion) " floor — " (:why b))}
               {:name "C — an absurd R=0 rung does not move the fit by one bit"
                :ok   (and (= (:slope c) (:slope a))
                           (= (:intercept c) (:intercept a))
                           (= (:r2 c) (:r2 a))
                           (near? (:shell c) 99999.0 1e-9))
                :detail (str "shell " (.toFixed (:shell c) 0) " B, slope still "
                             (.toFixed (:slope c) 3) " B and intercept still "
                             (.toFixed (:intercept c) 3)
                             " B — the guard on the defect the audit of PR #7260 found "
                             "in the predecessor ladder")}]]
    {:ok? (every? :ok chk) :checks chk}))

;; ---------------------------------------------------------------------------
;; THE ALLOCATION WINDOW — the survival metric's OTHER half (rf2-2rtt6.76)
;; ---------------------------------------------------------------------------
;;
;; Everything above this line measures RETENTION, and says so in its own
;; docstring: "Nothing here counts allocations." That is correct and it is
;; also why the survival metric has been half-witnessed since HD-002 was
;; written. validation.md prices the tier-3 per-read budget as "steady-state
;; allocation slope across warm 1/3/7/20 reads, zero retained per-occurrence
;; objects after commit/teardown". The second clause is witnessed — in bytes
;; by the ladder's residue column and in objects by the structural stamp
;; above (rf2-2rtt6.9). **The first clause is a different quantity measured
;; with a different instrument**, and the reads ladder does not answer it:
;; a boundary's retained bytes per read and its steady-state allocation per
;; read are not the same number and need not even have the same sign.
;;
;; ## What HD-002 actually predicted, so it can fail
;;
;; `hd-002-adjudication.md` states the cost law as "allocation is
;; proportional to the CHANGE, not to the read count. The unchanged case
;; allocates nothing", and H1's pre-registered prediction is that "the
;; allocation slope across warm 1/3/7/20 reads is FLAT AT ZERO",
;; **falsified by** a non-flat slope. The mechanism is [[entry-matches?]]
;; in `arm1/runtime.cljs` — an ordered pairwise compare of the cached
;; entry's key array against the scratch, which allocates nothing, so a
;; warm re-render whose read set did not change re-uses `subscribe`'s
;; identity and the commit does no work.
;;
;; That is a claim about EDGE MAINTENANCE and not about the whole render.
;; A warm re-render at R reads also allocates R query vectors in the arm's
;; own body, R subscription recomputations, and a React element tree — all
;; of which every substrate pays and all of which grow with R. So the
;; quantity that answers HD-002 is the candidate's slope **less the
;; same-run donor's**, which is why this row carries the donors beside the
;; candidate exactly as the ladder does. A candidate slope quoted alone
;; would be mostly other people's allocation.
;;
;; ## The instrument, and why it is not the one above
;;
;; If no collection runs between two readings of V8's used-heap counter,
;; their difference is the bytes allocated in between — INCLUDING the bytes
;; already unreachable, because nothing has reclaimed them yet. Garbage is
;; visible precisely because it has not been collected. So a window is:
;; collect, then read the counter at every leg boundary of every iteration,
;; and accumulate the RISING steps and the FALLING steps separately. The
;; published figure is the sum of the rising steps, which is an allocation
;; total whether or not a collection intervened, because a collection is
;; excluded from it rather than netted against it.
;;
;; This is `b8-alloc`'s method, and the two instruments it rules out are
;; ruled out here for the same reasons: V8's CDP **sampling** heap profiler
;; drops the samples of collected objects, so it reports retention wearing
;; an allocation instrument's name; and a heap SNAPSHOT collects before it
;; walks, destroying the very bytes in question. B8 demonstrates the first
;; with a negative control rather than asserting it, and this row cites
;; that finding rather than re-running it.
;;
;; ## Nothing inside the window may allocate on the instrument's behalf
;;
;; The samples land in a PREALLOCATED `Float64Array` sized before the
;; window opens. A `.push` onto a growable array would allocate inside the
;; measured region, which is the one thing an allocation instrument may
;; not do — the counter cannot tell the arm's bytes from the sampler's.

(defonce ^:private alloc-template (volatile! nil))
(defonce ^:private alloc-sink (volatile! 0.0))
(defonce ^:private alloc-samples (volatile! nil))

(defn- mem
  "V8's used-heap counter, read from inside the page.

  Requires `--enable-precise-memory-info`, which `p0_run.cjs` launches
  Chromium with; without it Chrome quantises this to 100 KB buckets and
  every figure here would be noise. The driver proves the flag took rather
  than trusting it. This is the same counter CDP's `Runtime.getHeapUsage`
  returns — the retention row above establishes that they agree — and it
  is read here rather than over CDP because a CDP round trip costs about a
  millisecond and there are two readings per iteration, which would cost
  more than the window being measured."
  []
  (if-some [m (.-memory js/performance)] (.-usedJSHeapSize m) -1))

(defn alloc-prepare!
  "Build the control template and the sample buffer ONCE, outside every
  window.

  `js/Array` + `fill` transitions the elements kind and allocates twice;
  doing it here means the `.slice` taken inside the window is a single
  clean `FixedDoubleArray` of `d` unboxed 8-byte slots, so the copy costs
  a **predicted 8d bytes**. That prediction is the whole point of a
  control — a figure the instrument has to hit, not one it gets to
  report."
  [d n]
  (vreset! alloc-template (when (pos? d) (.fill (js/Array. d) 0.5)))
  (vreset! alloc-sink 0.0)
  (vreset! alloc-samples (js/Float64Array. (+ 1 (* 2 (long n)))))
  nil)

(defn- alloc-control!
  "One control allocation: a `.slice` of the template, dropped on the next
  statement. One element is summed into a volatile that outlives the call,
  because Closure is entitled to delete an expression whose value nothing
  reads — this lane has already published a `layout-ms` of exactly 0.000
  because a property read was dropped."
  []
  (when-some [t @alloc-template]
    (let [c (.slice t)]
      (vreset! alloc-sink (+ @alloc-sink (aget c 0)))))
  nil)

(defn alloc-window!
  "Run `n` iterations of one work unit with the counter sampled at every
  leg boundary, and answer the raw samples.

  `kind` is `\"write\"` — a warm bulk re-render of the arm that is ALREADY
  MOUNTED, through the same `:p0/write-all` event the bulk clock arms
  drive — or `\"control\"` (a dropped `.slice` of predicted size) or
  `\"idle\"` (nothing at all, which prices the sampler's own footprint as
  a constant sitting inside every other figure).

  `drain` is `\"reagent\"` for Reagent's own documented synchronous render
  drain, and anything else for the empty `flushSync` a
  `useSyncExternalStore` spine needs to commit in this window — the same
  split `p0-arms/subs-bulk-arm` makes, so the write is like-for-like.

  Nothing here collects, logs, or crosses CDP. The driver collects before
  the window and reads the counter on both sides of it, and those two
  readings are what the in-page rising-step sum is checked against."
  [n kind drain]
  (let [n       (long n)
        ^js buf @alloc-samples
        write?  (= kind "write")
        ctl?    (= kind "control")
        reagent? (= drain "reagent")]
    (aset buf 0 (mem))
    (dotimes [i n]
      (aset buf (inc (* 2 i)) (mem))
      (cond
        write? (do (arms/write-all! (inc i))
                   (if reagent?
                     (react-dom/flushSync (fn [] (r/flush)))
                     (react-dom/flushSync (fn [] nil))))
        ctl?   (alloc-control!)
        :else  nil)
      (aset buf (+ 2 (* 2 i)) (mem)))
    ;; The read-back, OUTSIDE the window: the text of one boundary, which
    ;; at R reads of a page written to `v` is `(str (* R v))`. A row whose
    ;; writes never reached the page is the cheapest row in any table, and
    ;; this is the same class of gate as the mount read-back above.
    (let [cell (.querySelector js/document ".cell")]
      #js {:samples (js/Array.from buf)
           :n       n
           :kind    kind
           :text    (when cell (.-textContent cell))})))

;; ---------------------------------------------------------------------------
;; The page-side door
;; ---------------------------------------------------------------------------

(defn install!
  "Publish the instrument on `window.P0H`. The page mounts; it never
  decides when a reading is taken."
  []
  (set! (.-P0H js/window)
        #js {:prepare        (fn [seg] (prepare! (when seg (keyword seg))))
             :mount          (fn [arm k opts] (mount! arm k opts))
             :release        (fn [] (release!*) true)
             :control        (fn [n] (control! n))
             :controlRelease (fn [] (control-release!))
             :perfMem        (fn []
                               (if-some [m (.-memory js/performance)]
                                 (.-usedJSHeapSize m)
                                 -1))
             :slotOrder      (fn [n round] (clj->js (guard/slot-order n round)))
             ;; ONE expression of the arm-order rule, used by both rows.
             ;; The heap row's samples are produced by the DRIVER (only it
             ;; can force a collection), so the driver hands them back in
             ;; here to be adjudicated by the same `re-frame.bench.order-guard`
             ;; the clock row uses in-page. A second copy of the rule in
             ;; JavaScript would be a second place for it to drift.
             :verdict        (fn [samples tolerance]
                               (pr-str (guard/verdict (js->clj samples :keywordize-keys true)
                                                      {:tolerance tolerance})))
             ;; ONE expression of the positive-control rule too, and it is
             ;; the LANE's. The driver owns the collector, so it is the
             ;; driver that reads `predicted` against the measured range —
             ;; and until rf2-95s5b it printed that pair as a bare ratio
             ;; nothing adjudicated. `lane/control-verdict` is what the
             ;; freehand P0 arms already publish their controls under; what
             ;; it DECIDES is not this namespace's to change (rf2-egdaq),
             ;; and its docstring states the rule it applies — range
             ;; OVERLAP, not every-round-inside.
             ;;
             ;; A flat literal `#js` answer, never `clj->js`: that would
             ;; render `:ok?` as the key `"ok?"` and a driver reading
             ;; `v.ok` would see `undefined` — the control green for ever
             ;; because nothing could read it. The same trap `mount!`
             ;; already carries the scar from.
             :controlVerdict (fn [predicted measured slack]
                               (let [m (js->clj measured :keywordize-keys true)
                                     v (lane/control-verdict
                                         predicted
                                         (select-keys m [:min :max :mean])
                                         slack)]
                                 #js {:ok    (boolean (:ok? v))
                                      :why   (:why v)
                                      :slack (:slack v)}))
             :guardSelfTest  (fn [] (pr-str (guard/self-test)))
             ;; The fan-out sweep's two doors, and both of them are RULES
             ;; rather than arithmetic, so both live here rather than in a
             ;; JavaScript restatement the driver would own alone. The
             ;; self-test runs before the sweep measures anything, exactly
             ;; as the arm-order guard's does.
             ;;
             ;; Flat `#js` answers, never `clj->js`, for the reason
             ;; `mount!` carries the scar from: `clj->js` renders `:ok?`
             ;; as the key `"ok?"` and a driver reading `v.ok` sees
             ;; `undefined` — a gate green for ever because nothing could
             ;; read it. The EDN rides along whole, for the record file.
             :fanSelfTest    (fn []
                               (let [st (fan-self-test)]
                                 #js {:ok     (boolean (:ok? st))
                                      :checks (into-array
                                                (map (fn [c]
                                                       #js {:ok     (boolean (:ok c))
                                                            :name   (:name c)
                                                            :detail (:detail c)})
                                                     (:checks st)))}))
             :fanVerdict     (fn [rungs]
                               (let [v (additive-fit
                                         (js->clj rungs :keywordize-keys true)
                                         additive-criterion)]
                                 #js {:ok       (boolean (:ok? v))
                                      :model    (:model v)
                                      :why      (:why v)
                                      :shell    (:shell v)
                                      :edge     (:edge v)
                                      :edgeIntercept (:edge-intercept v)
                                      :edgeContrast  (:edge-contrast v)
                                      :step     (:step v)
                                      :key      (:key v)
                                      :keyAlt   (:key-alt v)
                                      :r2       (:r2 v)
                                      :heldOut  #js {:rung     (get-in v [:held-out :rung])
                                                     :measured (get-in v [:held-out :measured])
                                                     :m3       (get-in v [:held-out :m3])
                                                     :m3Error  (get-in v [:held-out :m3-error])
                                                     :m4       (get-in v [:held-out :m4])
                                                     :m4Error  (get-in v [:held-out :m4-error])}
                                      :checks   (into-array
                                                  (map (fn [c]
                                                         #js {:ok     (boolean (:ok c))
                                                              :name   (:name c)
                                                              :detail (:detail c)})
                                                       (:checks v)))
                                      :edn      (pr-str v)}))
             ;; The ladder's two doors (rf2-2rtt6.34), CLJS for the same
             ;; reason the fan sweep's are: a JavaScript restatement of a
             ;; fit rule would be a second place for it to drift, and this
             ;; one decides what may be quoted as a per-read price. Flat
             ;; `#js` answers, never `clj->js` — the `:ok?`-becomes-`"ok?"`
             ;; trap `mount!` already carries the scar from.
             :ladderSelfTest (fn []
                               (let [st (ladder-self-test)]
                                 #js {:ok     (boolean (:ok? st))
                                      :checks (into-array
                                                (map (fn [c]
                                                       #js {:ok     (boolean (:ok c))
                                                            :name   (:name c)
                                                            :detail (:detail c)})
                                                     (:checks st)))}))
             :ladderFit      (fn [rungs]
                               (let [v (ladder-fit (js->clj rungs :keywordize-keys true)
                                                   ladder-criterion)]
                                 #js {:slope     (:slope v)
                                      :intercept (:intercept v)
                                      :r2        (:r2 v)
                                      :n         (:n v)
                                      :shell     (:shell v)
                                      :firstRead (:first-read v)
                                      :linear    (boolean (:linear? v))
                                      :why       (:why v)
                                      :edn       (pr-str v)}))
             ;; The survival metric's structural half, read AFTER the
             ;; collector has run and the reapers' macrotask has fired.
             ;; Every field must be zero once an arm is released; a
             ;; nonzero one is a retained per-occurrence object, which is
             ;; HD-002 clause (d)'s failure and a different and worse
             ;; finding than a large per-boundary figure.
             :hicassoResidue (fn []
                               (let [r (hic-rt/residue)]
                                 #js {:cells      (:cells r)
                                      :cellRefs   (:cell-refs r)
                                      :boundaries (:boundaries r)
                                      :edges      (:edges r)
                                      :entries    (:entries r)}))
             ;; The allocation row's two doors (rf2-2rtt6.76). The window
             ;; itself is page-side because it must contain no CDP round
             ;; trip — the whole method is that nothing collects and
             ;; nothing else runs between two readings of the counter —
             ;; and the driver owns everything on either side of it.
             :allocPrepare   (fn [d n] (alloc-prepare! d n))
             :allocWindow    (fn [n kind drain] (alloc-window! n kind drain))
             :boundariesPerRoot #js {:list rows-per-root :grid per-root}})
  nil)
