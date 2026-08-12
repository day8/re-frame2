(ns re-frame.hicasso.readset-group-census
  "THE SHARED READ-SET CENSUS — how many memberships would a notification
  group actually coalesce (rf2-hic-083).

  [specification §11](../../../../../docs/design/hicasso/product/specification.md)
  carries *Shared read-set notification groups* as **Census, then spike**,
  and the order is the design. The criteria this instrument is measured
  against were frozen before it existed, in
  `docs/design/hicasso/product/readset-group-census.md`; read them first,
  because they fix the unit, the arithmetic and the default verdict, and
  nothing here may move any of the three.

  ## What it counts, and off what

  A **membership** is one slot in a cell's `.-readers` array — since
  rf2-dabt3 simultaneously the sub-key's reverse edge and the boundary's
  reference to that key's cell, which is why
  [[re-frame.hicasso.impl.inventory/stats]] reports it once under two
  names. It is the quantity the proposal proposes to reduce.

  The census reads it off the **read-set entry cache**, one row per
  entry:

  - `B` — [[re-frame.hicasso.impl.collector]]'s `entry.refs`, the
    committed boundaries sharing that entry. `make-subscribe` increments
    it and its cleanup decrements it, so it is React's count and not a
    reconstruction of one.
  - `R` — `(count entry.set)`, and deliberately **not**
    `(alength entry.keys)`. `make-subscribe` walks the SET, so a body
    that reads one key twice acquires one cell and holds one membership.
    Pricing the key ARRAY would over-report exactly the shape whose read
    sequence is duplicated, and over-reporting is the direction a
    candidate's own instrument must never err in.

  ## It cannot be believed on its own, so it does not ask to be

  Four properties, each executable, in
  `re-frame.hicasso.readset-group-census-cljs-test`:

  | proof | what it forecloses |
  |---|---|
  | NON-EMPTY | a reporter that reports \"clean\" and \"nothing ran\" identically |
  | POSITIVE control | a reporter that cannot detect coalescence when it is present, whose zero elsewhere therefore means nothing |
  | OVER-REPORT control | a legal population that must come back clean coming back positive |
  | CALIBRATION | a new number believed before the landmark beside it is reproduced |

  The landmark is the sharpest of the four and costs nothing, because the
  runtime already walks the same quantity from the other side:
  `Σ B·R` taken entry-side must equal `stats`'s `:cell-refs`, which sums
  `readers.length` over the cell table. The two walks share no traversal
  and no code.

  ## It REPORTS what it cannot resolve

  Every entry the walk reaches is in the answer. An entry no boundary has
  claimed (`refs` 0 — an abandoned render's, or one inside the 4 ms reap
  horizon) is counted in `:unclaimed` and contributes no memberships
  rather than vanishing; an entry whose key array is longer than its key
  set is counted in `:duplicate-read-entries`; an entry with no reads at
  all — a read-free shell, and `examples.forms/details-form` is a real
  one — is counted in `:read-free-entries`, because at `R = 0` the
  grouped cost is `B` against today's nothing and a reader who did not
  know that would read the pooled figure wrongly. A disagreement with the
  landmark is `:divergence`, reported with both numbers rather than
  resolved by taking a side: it has two known real causes, an entry
  evicted by the reap horizon before React claimed it, and a cell
  disposed under live readers when its frame was destroyed.

  ## Residence and lane

  `implementation/hicasso/test`, which `implementation/shadow-cljs.edn`
  already carries as a `:source-paths` entry — so this file is compiled
  as a dependency of its two suites and no build config changes. It
  matches neither test regexp (`:node-test` selects `cljs-test$`,
  `:browser-test` selects `-dom-cljs-test$`), so it runs nowhere itself.
  Nothing under `src/` requires it and no production code reaches it."
  (:require [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]))

(defn saving
  "The memberships a notification group would save for one entry, from the
  identity the criteria fixed before anything was measured:

      B·R − (R + B) = (B−1)(R−1) − 1

  Public because the arithmetic is the verdict's spine and a suite that
  re-spelled it could agree with a wrong [[rows]] by construction."
  [b r]
  (- (* b r) (+ r b)))

(defn rows
  "One row per read-set entry currently in
  [[re-frame.hicasso.impl.collector/!entries]], claimed or not.

  `:keys` carries the entry's own sub-key set so a row can be read back
  to the boundary that produced it — the census's answer has to be
  legible, not merely correct."
  []
  (into []
        (mapcat (fn [[bucket-key entries]]
                  (map (fn [^js e]
                         (let [b     (.-refs e)
                               st    (.-set e)
                               r     (count st)
                               slots (alength (.-keys e))]
                           {:bucket          bucket-key
                            :boundaries      b
                            :reads           r
                            :read-slots      slots
                            :duplicate-read? (not= r slots)
                            :memberships     (* b r)
                            :grouped         (if (pos? b) (+ r b) 0)
                            :saving          (if (pos? b) (saving b r) 0)
                            :keys            st}))
                       entries)))
        @collector/!entries))

(defn report
  "The census. Pooled figures over [[rows]], with the landmark beside them.

  `:coalesced` is the criteria's C1 quantity — saved memberships over
  current memberships. `:shareable-fraction` is the same question asked
  the most generous way any sharing scheme could ask it: the memberships
  living in entries more than one boundary holds, which is an upper bound
  on what grouping could ever touch whatever its arithmetic. Both are
  reported because a denominator must not be able to decide a marginal
  verdict on its own."
  []
  (let [rs        (rows)
        claimed   (filterv (comp pos? :boundaries) rs)
        m         (reduce + 0 (map :memberships claimed))
        m'        (reduce + 0 (map :grouped claimed))
        shared    (filterv #(<= 2 (:boundaries %)) claimed)
        shareable (reduce + 0 (map :memberships shared))
        paying    (filterv #(pos? (:saving %)) claimed)
        non-losing (filterv #(<= 0 (:saving %)) claimed)
        cell-side (:cell-refs (inventory/stats))]
    {:entries                (count rs)
     :claimed                (count claimed)
     :unclaimed              (- (count rs) (count claimed))
     :duplicate-read-entries (count (filterv :duplicate-read? rs))
     :read-free-entries      (count (filterv (comp zero? :reads) rs))
     :memberships            m
     :grouped                m'
     :saved                  (- m m')
     :coalesced              (if (pos? m) (/ (double (- m m')) m) 0.0)
     :shared-entries         (count shared)
     :shareable              shareable
     :shareable-fraction     (if (pos? m) (/ (double shareable) m) 0.0)
     :paying-entries         (count paying)
     ;; Entries that would not LOSE — `saving ≥ 0`, so break-even
     ;; included. `:paying-entries` alone would tolerate a `B = R = 2`
     ;; entry, which saves nothing and is still the shape a scheme would
     ;; be built for.
     :non-losing-entries     (count non-losing)
     :paying-saving          (reduce + 0 (map :saving paying))
     :max-boundaries         (reduce max 0 (map :boundaries rs))
     :max-reads              (reduce max 0 (map :reads rs))
     ;; The whole census in one legible value: how many claimed entries
     ;; sit at each `[B R]`. It is what makes a pooled figure readable as
     ;; a shape rather than believed as a total, and it is the row a
     ;; reader checks the identity against by hand.
     :shape                  (frequencies (map (juxt :boundaries :reads) claimed))
     :landmark               {:entry-side m
                              :cell-side  cell-side
                              :divergence (- m cell-side)}}))

(defn calibrated?
  "Did the entry-side walk reproduce the cell-side landmark exactly?

  Never a filter and never a guard: a census that dropped its rows when
  this went false would answer a smaller number and look healthy. It is
  asserted by the suites and reported by [[report]], and that is all."
  [rpt]
  (zero? (:divergence (:landmark rpt))))

(defn pool
  "Add one application's [[report]] to a running total, so a pooled
  verdict is arithmetic over the parts rather than a second walk.

  Only the extensive quantities add; the fractions and the maxima are
  re-derived by [[pooled]] so a pooled fraction can never be an average
  of fractions."
  [acc rpt]
  (merge-with +
              (or acc {:entries 0 :claimed 0 :unclaimed 0 :duplicate-read-entries 0
                       :read-free-entries 0 :memberships 0 :grouped 0
                       :shared-entries 0 :shareable 0 :paying-entries 0
                       :non-losing-entries 0 :paying-saving 0 :apps 0
                       :divergence 0})
              {:entries                (:entries rpt)
               :claimed                (:claimed rpt)
               :unclaimed              (:unclaimed rpt)
               :duplicate-read-entries (:duplicate-read-entries rpt)
               :read-free-entries      (:read-free-entries rpt)
               :memberships            (:memberships rpt)
               :grouped                (:grouped rpt)
               :shared-entries         (:shared-entries rpt)
               :shareable              (:shareable rpt)
               :paying-entries         (:paying-entries rpt)
               :non-losing-entries     (:non-losing-entries rpt)
               :paying-saving          (:paying-saving rpt)
               :divergence             (:divergence (:landmark rpt))
               :apps                   1}))

(defn pooled
  "Close a [[pool]] accumulator into a verdict-shaped map."
  [acc]
  (let [m  (:memberships acc)
        m' (:grouped acc)]
    (assoc acc
           :saved              (- m m')
           :coalesced          (if (pos? m) (/ (double (- m m')) m) 0.0)
           :shareable-fraction (if (pos? m) (/ (double (:shareable acc)) m) 0.0))))
