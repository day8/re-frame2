(ns re-frame.bench.hicasso.topo.clock-app
  "**THE TOPOLOGY TOURNAMENT'S CLOCK DRIVER** — the arms × operations ×
  row-counts table the tournament pre-registered and never instrumented
  (rf2-w01c, splitting the clock half out of rf2-hic-036).

  Driven by the lane's generic driver, so it adds no driver of its own and
  takes no new build id:

      HICASSO_INIT_FN=re-frame.bench.hicasso.topo.clock-app/-main \\
      HICASSO_OUT_DIR=out/topo-clock \\
      HICASSO_PORT=8148 \\
      node implementation/hicasso/test/re_frame/bench/hicasso/run.cjs

  ## What was missing, and it was not a control

  [The tournament](../../../../../../../docs/design/hicasso/product/topology-tournament.md)
  froze its arms, operations, row counts, estimand, controls and stopping
  rule before a measurement was taken, and its deterministic half then
  landed complete at all 48 cells. The clock half published nothing, and
  after `rf2-m6i0` the reason stopped being the control:

  > What withholds those three cells now is narrower and more ordinary:
  > **the tournament's clock cells were never instrumented.** Only the
  > control was built, it refused first, and no driver for the 4 arms × 4
  > operations table exists to run.
  > — `topology-tournament.md` §2

  This file is that driver. It builds nothing the lane already has: the
  schedule, the sampling, the guard, the tally and the one meaning of
  *verified* are [[re-frame.bench.hicasso.lane]]'s, and **the control is
  [[re-frame.bench.hicasso.topo.control-app]]'s, called rather than
  reimplemented** — see below.

  ## THE CONSTRAINT THIS DRIVER IS BUILT AROUND

  `rf2-m6i0`'s window established it and `rf2-hic-036` carries it forward
  as the design input for exactly this file:

  > A MANIPULATION CERTIFIES AN INSTRUMENT ONLY WHEN EVERY COST IN THE
  > WINDOW MOVES WITH IT.

  Two controls have now been refused on this lane and both failed the same
  way from opposite ends — one moved only the markup while the handler and
  the subscription layer stood still, the other moved nothing at all on
  two of the four arms. What each left behind was a **shared constant**,
  and a shared constant compresses a measured ratio toward 1, which reads
  exactly like an instrument that cannot see.

  So this driver does not carry a control of its own. **It calls
  [[re-frame.bench.hicasso.topo.control-app/run-arm!]] — the
  rendered-scale doubling with an indexed write, the one manipulation on
  this lane in which every cost in the window moves — and takes no clock
  cell for an arm whose control refused.** That is
  [§1.5](../../../../../../../docs/design/hicasso/product/topology-tournament.md#15-the-controls-and-what-each-refuses)
  as registered (*\"A published clock figure requires both to pass for
  that arm at that row count; either failing withholds the figure\"*), and
  it is the whole reason a second control here would be a defect rather
  than a redundancy: a driver that minted its own would be the third
  attempt at the manipulation two windows have already priced.

  ## THE WINDOWED ARM IS NOT MEASURED, AND THAT IS A RULING

  `rf2-4t36` ruled on `virtual` without taking a new measurement: at the
  window this tournament commits to, its whole commit is 0.125–0.195 ms
  against a fitted per-commit floor of about 0.059 ms — **47% of the
  reading** — so a healthy instrument on a quiet box certifies that arm
  about one run in four. Its clock cells are **UNADDRESSED**, and the
  ruling names the two repairs that are refused: do not widen a band to
  admit them, and do not enlarge the window to rescue them, *\"which would
  certify a regime the cells are not published in\"*.

  This file therefore holds `:virtual` in [[unaddressed]] with its reason
  and **starts no clock on it**. Measuring it and labelling the number
  unaddressed would publish a figure a reader can quote; not measuring it
  cannot.

  ## The floor row, which is REPORTED and decides nothing

  The same ruling is the reason every row count also carries a `:noop`
  row. `[:topo/noop-write]` moves a key no arm reads, so its window holds
  the per-commit cost of a commit that builds no markup — dispatch, the
  `flushSync` boundary, the commit React schedules regardless. That is the
  quantity that killed the windowed arm, and a reader handed an
  arm-to-arm ratio without it cannot tell a topology result from a floor.

  **That cost is ARM-SPECIFIC, and the premise this file used to state —
  that the row holds *\"exactly the cost that does not move with the
  arm\"* — is FALSIFIED.** The window `rf2-w01c` took on this driver
  reads, at `B = 1000`, **31.75 / 31.60 / 31.85 ms on `fine`** against
  **8.00 / 8.20 / 8.40 on `coarse`** and **9.40 / 9.40 / 9.55 on
  `chunked`** — `fine`'s floor is 3.97, 3.85 and 3.79 times `coarse`'s
  across the three runs. The floor MOVES with the arm, by about 4x.
  **What it varies with is not settled**, and this file fits no cost for
  it: the arms differ in boundary count, in subscription count and in
  nothing else the window separates, so the statement the measurement
  supports is *the floor is larger on the arm with more boundaries and
  reads* and nothing sharper. See
  [§2.9.7](../../../../../../../docs/design/hicasso/product/topology-tournament.md#297-the-floor-row-and-the-thing-it-turned-out-not-to-be).

  **[2026-08-22.] A second series, taken under the exclusivity condition
  the merged-PR audit of #8466 asked for, CONFIRMS the falsification and
  RETIRES the `about 4x`.** Read the two apart, because only one of them
  replicated. The claim did, in all six of its readings — the floor is
  arm-specific and always larger on the arm with more boundaries and
  reads — and at the two smaller row counts its factors land on top of
  the series above, 2.15x / 2.12x at `B = 100` and 3.19x / 3.00x at
  `B = 300`. **The factor at `B = 1000` did not**: 3.54x and 3.35x, on
  cells that both read higher (`fine` 35.75 / 39.85 against `coarse`
  10.10 / 11.90). So `about 4x` describes the series above rather than
  this instrument, and the sentence this file is entitled to is the one
  it already narrows itself to. What the second series adds is that **the
  factor grows with `B`** — near 2.1x, 3.0x and 3.4x at the three row
  counts — which no single row count could have shown.

  **The figures above are NOT replaced**, and this note is why rather
  than an omission: that series is two admissible runs against a
  pre-registered three, so it is a record and not a window. See
  [Part 3](../../../../../../../docs/design/hicasso/product/topology-tournament.md#part-3--the-re-take-rf2-w01c-the-exclusive-window).

  The sentence above — that a reader handed an arm-to-arm ratio without
  the floor cannot tell a topology result from a floor — is
  **STRENGTHENED** by that and not weakened: a floor that is itself
  arm-specific is one a reader cannot even bound by taking the smallest
  arm's.

  **It is published beside every cell and it adjudicates nothing.** The
  same ruling rejected subtracting a separately-measured floor on three
  counts, any one sufficient, and the sharpest is that there is no stable
  floor to subtract: the identical fit returns +47% on one arm and
  NEGATIVE constants on the other three. A quantity of that shape is the
  intercept of an assumed cost model, not a property of the rig. So this
  row is a **measurement placed beside the cells**, never a correction
  applied to them, and no arithmetic here subtracts it.

  ## One window

  `batch-k` commits of one operation on one already-mounted page, under
  ONE clock, then — with the clock stopped — two cell-addressed probes
  read back and banked into `rf.bench.hicasso.lane/tally`. The batch is `rf2-9zysg`'s
  repair for Chrome's 100 µs clamp and the same number the control uses;
  the read-back is `control-app`'s, cell-addressed by `data-testid`
  rather than by the row's own text, because a row's text node
  concatenates its label, its `n`, its draft and its button's caption and
  a probe reading it could be satisfied by any of them moving.

  **Every operation's probe pair carries one cell that MUST have moved and
  one that MUST NOT.** The unchanged half is what makes the pair
  load-bearing: a write that reached every row, or a page rebuilt from a
  stale seed, satisfies the changed probe on its own. `:noop`'s pair is
  two unchanged cells, which is the negative control stated as a
  read-back.

  ## What this file does NOT do

  It does not take the window. A clock estimand needs a quiet box — one
  measurement at a time, no other worker compiling — and that is a
  property of the instrument rather than a scheduling preference. Building
  the driver is edit-shaped and unconstrained; running it is not. This
  file is the build half.

  It does not pool the two estimands. The census's rows of markup travel
  beside each cell because they are what gate it before the clock starts,
  and they are labelled as the deterministic quantity they are —
  [§1.4](../../../../../../../docs/design/hicasso/product/topology-tournament.md#14-the-estimand-and-the-one-substitution-that-is-refused)
  forbids a work census standing in for a clock, and a driver that printed
  them in one table would be doing exactly that."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.arm1.mount :as rf.bench.hicasso.arm1.mount]
            [re-frame.bench.hicasso.arm1.runtime :as rf.bench.hicasso.arm1.runtime]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.topo.arms :as rf.bench.hicasso.topo.arms]
            [re-frame.bench.hicasso.topo.control-app :as rf.bench.hicasso.topo.control-app]
            [re-frame.bench.hicasso.topo.model :as rf.bench.hicasso.topo.model]
            [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; Pre-registered — the whole plan, before any run
;; ---------------------------------------------------------------------------

(def measured-arms
  "The arms whose clock cells this driver takes.

  Three of the tournament's four, and the missing one is a RULING rather
  than an omission — see [[unaddressed]]. The order is the roster's own
  (`rf.bench.hicasso.topo.arms/arm-ids` less the unaddressed arm), so a reader comparing this
  table against the census reads the columns in the same order."
  (into [] (remove #{:virtual}) rf.bench.hicasso.topo.arms/arm-ids))

(def unaddressed
  "The arms this driver deliberately starts no clock on, with the reason
  carried in the file rather than in a commit message.

  `rf2-4t36` ruled the windowed arm's cells unresolvable at the committed
  window size and handed them over as UNADDRESSED. A driver that measured
  the arm anyway and labelled the number would publish a figure a reader
  can quote; this one cannot."
  {:virtual (str "rf2-4t36: at the tournament's committed window (w=20) the whole commit is "
                 "0.125-0.195 ms against a fitted per-commit floor of ~0.059 ms — 47% of the "
                 "reading — so this control certifies a healthy instrument about one run in "
                 "four. The cells are UNADDRESSED. Widening the band or enlarging the window "
                 "are both refused: either would certify a regime the cells are not published in")})

(def operations
  "The tournament's four operations, in its own registered order."
  [:sparse :bulk :reorder :edit])

(def floor-op
  "The fifth row, and the only one that is not a tournament operation.

  `[:topo/noop-write]` moves a key no arm reads, so its window holds the
  per-commit cost of a commit that builds no markup. That cost is
  ARM-SPECIFIC — about 4x larger on `fine` than on `coarse` at
  `B = 1000` — rather than the shared constant this row was named for.
  REPORTED beside the cells; it corrects nothing. See the namespace
  docstring."
  :noop)

(def rows
  "Every row of the table, floor last so a reader meets the operations
  first."
  (conj operations floor-op))

(def row-counts
  "`B ∈ {100, 300, 1000}` — the tournament's own, read from the model
  rather than restated, so the clock table and the census table are
  about the same three pages."
  rf.bench.hicasso.topo.model/row-counts)

(def target
  "The row `sparse` and `edit` move, and the row `reorder`'s unchanged
  probe reads.

  ZERO, which is `census_dom_cljs_test`'s own choice and is copied here
  deliberately: the clock table and the work census must describe the
  same operation, and a different target would make them two experiments
  wearing one name. That file's reason stands unchanged — row 0 is the
  only index guaranteed to be in the windowed arm's DOM at every row
  count."
  0)

(def unchanged-probe
  "A row no narrow operation touches, adjacent to [[target]] so a page
  that rebuilt the wrong region fails one of the two probes."
  1)

(def batch-k
  "Operations under ONE clock — the control's own number, not a second
  one.

  Chrome clamps `performance.now()` to 100 µs and `rf2-d2tzk` records
  what that does to a narrow row. `rf2-9zysg`'s repair is to batch, and
  `control-app` already carries the value this lane batches at; two
  numbers for one decision is how a table comes to be taken at a depth
  its control was never certified at."
  rf.bench.hicasso.topo.control-app/batch-k)

(def sampling
  "The control's sampling, for the same reason [[batch-k]] is the
  control's: a cell licensed by a control taken at one depth and measured
  at another is licensed by an experiment that was not run."
  rf.bench.hicasso.topo.control-app/sampling)

(def rounds rf.bench.hicasso.topo.control-app/rounds)

;; ---------------------------------------------------------------------------
;; The deterministic gate every cell passes BEFORE its clock starts
;; ---------------------------------------------------------------------------

(defn markup-expected
  "Rows of markup ONE commit of `op` builds on `arm`'s page at `b` —
  exact integers, and the tournament's own published census
  ([§2.2](../../../../../../../docs/design/hicasso/product/topology-tournament.md#22-the-rung-2-teaching-table--rows-of-markup-built)).

  This is arithmetic and not a lookup table: `fine` builds the rows that
  changed, `coarse` rebuilds its whole view-model, `chunked` rebuilds
  every chunk a change reaches, and a permutation moves no row's read at
  all so `fine`'s memo bails on every one of them.

  [[run-row!]] MEASURES it before starting a clock and refuses the row
  when it disagrees. A page that is not doing the work its arithmetic
  predicts is not the page the tournament is about, and a clock reading
  over it would be a precise number for the wrong experiment."
  [arm op b]
  (case op
    (:sparse :edit) (case arm
                      :fine    1
                      :coarse  b
                      :chunked (min rf.bench.hicasso.topo.model/chunk-size b))
    :bulk           b
    :reorder        (case arm
                      :fine    0
                      (:coarse :chunked) b)
    :noop           0))

;; ---------------------------------------------------------------------------
;; The read-back — pure arithmetic over the model's own seed
;; ---------------------------------------------------------------------------

(defn draft-value
  "The string `edit`'s `committed`-th commit writes. A pure function of
  the commit index, so the probe advances by exactly one per commit in
  the same way `sparse`'s `n` does."
  [committed]
  (str "k" committed))

(defn expectations
  "What a page of `b` rows must read after `committed` commits of `op`,
  as `{:probe :cell :want}`.

  Pure, and `:cell` is DATA — `[:n i]`, `[:draft i]` or `[:head]` —
  rather than a DOM query, so this whole arithmetic is checkable without
  a browser and its witness can assert it under `:node-test`.

  Every pair holds one cell that must have MOVED and one that must NOT.
  A changed probe alone is satisfied by a write that reached every row
  and by a page rebuilt from a stale seed; the unchanged half is what
  refuses both. `:noop`'s pair is two unchanged cells, which is this
  lane's negative control written as a read-back rather than as a
  counter."
  [op b committed]
  (case op
    :sparse  [{:probe :changed   :cell [:n target]              :want (str (+ (:n (rf.bench.hicasso.topo.model/row target)) committed))}
              {:probe :unchanged :cell [:n unchanged-probe]     :want (str (:n (rf.bench.hicasso.topo.model/row unchanged-probe)))}]
    ;; A broad write leaves no unchanged cell to read, so the second probe is
    ;; the FAR END of the table — `rf.bench.hicasso.lane/bulk-probes`' rule, in this file's
    ;; cell-addressed spelling: a commit that got as far as the front of the
    ;; page and no further satisfies the first probe on its own.
    :bulk    [{:probe :changed   :cell [:n target]              :want (str (+ (:n (rf.bench.hicasso.topo.model/row target)) committed))}
              {:probe :far-end   :cell [:n (dec b)]             :want (str (+ (:n (rf.bench.hicasso.topo.model/row (dec b))) committed))}]
    ;; A permutation moves the ORDER and no row's data, so the pair reads
    ;; one of each: the head of the list must have rotated exactly
    ;; `committed` places, and the target row's own `n` must not have moved
    ;; at all.
    :reorder [{:probe :changed   :cell [:head]                  :want (str (mod committed b))}
              {:probe :unchanged :cell [:n target]              :want (str (:n (rf.bench.hicasso.topo.model/row target)))}]
    :edit    [{:probe :changed   :cell [:draft target]          :want (draft-value committed)}
              {:probe :unchanged :cell [:draft unchanged-probe] :want ""}]
    :noop    [{:probe :unchanged :cell [:n target]              :want (str (:n (rf.bench.hicasso.topo.model/row target)))}
              {:probe :unchanged :cell [:head]                  :want "0"}]))

(defn- read-cell
  "Read one [[expectations]] cell out of `container`, as text, or nil.

  `[:head]` reads an ATTRIBUTE and the other two read text, which is the
  only asymmetry here and it is forced: the quantity `reorder` moves is
  which row is first, and that is an identity rather than a value."
  [container cell]
  (case (first cell)
    :n     (some-> (.querySelector container (str "[data-testid=\"n-" (second cell) "\"]"))
                   (.-textContent))
    :draft (some-> (.querySelector container (str "[data-testid=\"draft-" (second cell) "\"]"))
                   (.-textContent))
    :head  (some-> (.querySelector container "li.topo-row") (.getAttribute "data-i"))))

(defn probe-misses
  "Which of `op`'s probes `container` fails after `committed` commits. A
  vector of maps, empty when the page committed what it claims."
  [container op b committed]
  (into []
        (keep (fn [{:keys [cell want] :as e}]
                (let [got (read-cell container cell)]
                  (when-not (= want got) (assoc e :got got)))))
        (expectations op b committed)))

;; ---------------------------------------------------------------------------
;; One measured window
;; ---------------------------------------------------------------------------

(defn write!
  "Dispatch ONE commit of `op` into `frame-id`. `committed` is the page's
  running commit count BEFORE this one, and only `:edit` reads it — its
  cell has to carry a distinct value per commit or a read-back could be
  satisfied by a commit that never happened."
  [frame-id op committed]
  (case op
    :sparse  (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:topo/bump target])
    :bulk    (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:topo/bump-all])
    :reorder (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:topo/rotate])
    :edit    (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:topo/edit target (draft-value (inc committed))])
    :noop    (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:topo/noop-write])))

(defn window!
  "[[batch-k]] commits of `op` on `page` under ONE clock, then — with the
  clock stopped — the two probes read back and BANKED in `t`.

  Answers the elapsed milliseconds for the whole batch.

  The read-back is outside the window and after the last drain, which is
  the batched shape `rf.bench.hicasso.lane/verified-writes!` documents and defends: no
  macrotask runs between the first write and the last drain, so a commit
  React has parked cannot land inside the window however many microtasks
  pass. What the batch relaxes is microtask-scale lateness, on a
  tolerance the unbatched window already grants.

  The commit index is a LOCAL counter for the duration of the batch and
  the page's atom is advanced once at the end, so nothing inside the
  window touches shared state that the clock would then be measuring."
  [t {:keys [frame-id container b committed]} op]
  (let [start @committed
        t0    (rf.bench.hicasso.lane/now-ms)]
    (dotimes [i batch-k]
      (write! frame-id op (+ start i))
      (rf.bench.hicasso.arm1.mount/settle!))
    (let [ms     (- (rf.bench.hicasso.lane/now-ms) t0)
          total  (swap! committed + batch-k)
          misses (probe-misses container op b total)]
      ;; `rf.bench.hicasso.lane/verified-writes!`'s own `bank!`, over this file's probes: one
      ;; tally, one meaning of "verified", one `assert-verified!`.
      (swap! t (fn [{:keys [of bad]}]
                 {:of (+ of (count (expectations op b total)))
                  :bad (+ bad (count misses))}))
      (when (seq misses)
        (js/console.error (str ";; PROBE MISS — " (name op) " @B=" b " — " (pr-str misses))))
      ms)))

;; ---------------------------------------------------------------------------
;; Pages
;; ---------------------------------------------------------------------------

(def ^:private frame-ids
  (into {} (for [arm measured-arms b row-counts]
             [[arm b] (keyword "topo.clock" (str (name arm) "-" b))])))

(defn- structure-of
  "`container`'s own structure. `:boundaries` and `:edges` come from a
  DELTA against the runtime's live table, because all three arms' pages
  are mounted at once and `rf.bench.hicasso.arm1.runtime/stats` counts every live cell in the
  process rather than one container's."
  [container before]
  (let [{:keys [boundaries edges]} (rf.bench.hicasso.arm1.runtime/stats)]
    {:boundaries    (- boundaries (:boundaries before))
     :edges         (- edges (:edges before))
     :elements      (rf.bench.hicasso.lane/element-count container)
     :rendered-rows (.-length (.querySelectorAll container "li.topo-row"))}))

(defn- mount-page!
  "Seed a frame at `b`, mount `arm` over it, and answer the page map the
  rest of this file passes around — with the structure it actually
  mounted, taken as a delta against `before`."
  [arm b before]
  (let [frame-id (get frame-ids [arm b])]
    (rf.bench.hicasso.topo.model/make-frame! frame-id b)
    (rf.bench.hicasso.topo.model/reseed! frame-id b)
    (let [handle (rf.bench.hicasso.arm1.mount/root! (rf.bench.hicasso.arm1.mount/fresh-container!) frame-id [(rf.bench.hicasso.topo.arms/view-of arm) {}])]
      {:arm       arm
       :b         b
       :frame-id  frame-id
       :handle    handle
       :container (:container handle)
       :committed (atom 0)
       :structure (structure-of (:container handle) before)})))

(defn- reseed-page!
  "Return `page` to its seed and let the commit land, outside every
  clock. The commit counter is zeroed AFTER the settle, so the probe
  arithmetic of the row about to run starts where the seed does."
  [{:keys [frame-id committed b]}]
  (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:topo/seed b rf.bench.hicasso.topo.model/window-size])
  (rf.bench.hicasso.arm1.mount/settle!)
  (reset! committed 0)
  nil)

(defn- markup-of
  "Rows of markup ONE commit of `op` builds on `page` — measured, on the
  arms' own counters, outside every clock.

  The counters are a single shared atom, so this runs one page at a time
  and resets immediately before its commit."
  [page op]
  (rf.bench.hicasso.topo.arms/reset-counters!)
  (write! (:frame-id page) op @(:committed page))
  (rf.bench.hicasso.arm1.mount/settle!)
  (swap! (:committed page) inc)
  (:markup (rf.bench.hicasso.topo.arms/runs)))

;; ---------------------------------------------------------------------------
;; One row of the table — one operation, one row count, every measured arm
;; ---------------------------------------------------------------------------

(defn run-row!
  "One `(op, b)` row: check every page still builds the markup its
  arithmetic predicts, then interleave the arms under the clock.

  Answers `{:cells :ratios :guard :markup :tally}`, or `{:fatal …}` when
  the deterministic gate refused before a clock was started.

  **The arms are the manipulation and nothing else is.** Same page size,
  same operation, same batch, same probes, same rounds, mounts and
  releases outside every window — so what separates two cells in a row is
  the topology or it is the floor, and the floor row is measured so a
  reader can tell which."
  [pages op b]
  (let [t     (rf.bench.hicasso.lane/tally)
        legs  (mapv (fn [arm] {:id arm}) measured-arms)]
    (doseq [arm measured-arms] (reseed-page! (get pages arm)))
    (let [built (into {} (map (fn [arm] [arm (markup-of (get pages arm) op)])) measured-arms)
          want  (into {} (map (fn [arm] [arm (markup-expected arm op b)])) measured-arms)]
      (if (not= built want)
        {:fatal (str (name op) " at B=" b ": a page does not build the markup the census "
                     "publishes for it, so the clock is about to read a page the tournament "
                     "is not about — expected " (pr-str want) ", built " (pr-str built))}
        (let [{:keys [readings samples]}
              (rf.bench.hicasso.lane/rounds! legs sampling rounds
                            (fn [a] (window! t (get pages (:id a)) op))
                            (fn [a] (str (name (:id a)) "/" (name op) "@" b)))
              ;; One p50 per arm per round — the within-round median every
              ;; ratio below is a ratio OF, named here rather than in the
              ;; caller so a published row cannot be read as a mean of
              ;; samples (rf2-pqyxz's correction, on the other instrument).
              per-round (mapv (fn [r]
                                (into {} (map (fn [[id xs]] [id (:p50 (rf.bench.hicasso.lane/summarise xs))])) r))
                              readings)
              centre    (into {} (map (fn [arm]
                                        [arm (rf.bench.hicasso.lane/summarise (mapv #(get % arm) per-round))]))
                              measured-arms)]
          {:markup    built
           :cells     centre
           :per-round per-round
           ;; Against `fine`, which is the tournament's reference topology and
           ;; the one arm both refused controls could address. `:straddles-1?`
           ;; is the honesty flag: a range containing 1.0 means the two arms
           ;; are INDISTINGUISHABLE here and the row says so rather than
           ;; quoting a mean as a winner.
           :ratios    (into {} (map (fn [arm] [arm (rf.bench.hicasso.lane/ratio-between per-round arm :fine)]))
                            (remove #{:fine} measured-arms))
           :guard     (rf.bench.hicasso.lane/guard! samples (str "topo clock " (name op) " @B=" b))
           :tally     t})))))

;; ---------------------------------------------------------------------------
;; The floor, placed beside the cells and never subtracted from them
;; ---------------------------------------------------------------------------

(defn floor-shares
  "How much of each cell's window is that cell's OWN ARM's floor, as
  `{[arm op b] share}`.

  `share` is the floor row's centre over the operation row's centre on
  the SAME arm at the SAME row count — a ratio of two measurements, never
  a correction applied to either. `rf2-4t36` rejected subtracting a
  floor and the sharpest of its three reasons is that there is nothing
  stable to subtract: the identical fit returns +47% on one arm and
  negative constants on three. The window STRENGTHENED that reason, by
  measuring a floor that is arm-specific — see the namespace docstring.

  **The received rule for reading a mostly-floor cell does not apply to
  this table, and the reason is that same falsification.** *\"A cell
  whose window is mostly floor has its arm-to-arm ratio compressed
  toward 1\"* is true when both arms carry the SAME floor. These do not,
  so the limit here is a different number: were an operation to add
  nothing at all to either arm, the ratio would read this table's own
  floor ratio — `coarse`/`fine` of 0.254-0.268 at `B = 1000` — and not
  1.00. **No corrected ratio is computed here**, because computing one
  needs the additive cost model `rf2-4t36` refused. Nothing here
  adjudicates on any of it."
  [table]
  (into {}
        (for [b   row-counts
              arm measured-arms
              op  operations
              :let [f (get-in table [[floor-op b] :cells arm :p50])
                    o (get-in table [[op b] :cells arm :p50])]
              :when (and f o (pos? o))]
          [[arm op b] (rf.bench.hicasso.lane/round4 (/ f o))])))

;; ---------------------------------------------------------------------------
;; The control, called rather than reimplemented
;; ---------------------------------------------------------------------------

(defn run-controls!
  "The registered positive control, once per measured arm, BEFORE any
  cell is taken.

  This is [[re-frame.bench.hicasso.topo.control-app/run-arm!]] and not a
  copy of it: the rendered-scale doubling with an indexed write is the
  one manipulation on this lane in which the handler, the subscription
  layer and the render all move together, and a second spelling of it
  here would be a second authority with nothing holding it in step.

  Answers `{arm verdict}`. A refusal is not repaired and not softened —
  the caller takes no cell for that arm, which is §1.5 as registered."
  []
  (reduce (fn [acc arm]
            (let [r (rf.bench.hicasso.topo.control-app/run-arm! arm)]
              (if-let [f (:fatal r)]
                (reduced (assoc acc arm {:ok? false :why f}))
                (do
                  (rf.bench.hicasso.lane/record! (keyword (str "control-" (name arm)))
                                (select-keys r [:verdict :structure :markup]))
                  (when (:refuse? (:guard r))
                    (set! (.-HICASSO_GUARD_REFUSED js/window) true))
                  ;; The lane's ONE adjudication of a read-back. It throws, and
                  ;; it is called AFTER the record above is published.
                  (rf.bench.hicasso.lane/assert-verified! (:tally r)
                                         (str "topo clock control (" (name arm) ")"))
                  (assoc acc arm (:verdict r))))))
          {}
          measured-arms))

;; ---------------------------------------------------------------------------
;; The table
;; ---------------------------------------------------------------------------

(defn run-table!
  "Every row of the table, one row count at a time. Answers
  `{[op b] row}`, or throws through [[run-row!]]'s fatal.

  All three arms' pages stand together for the whole of a row count, so
  every cell in it is measured with the same document under it — a page
  mounted per cell would put a different amount of DOM under each arm and
  bill the difference to the topology. They are released before the next
  row count mounts, so the document holds three pages and never nine."
  []
  (reduce
    (fn [table b]
      (let [pages (reduce (fn [acc arm] (assoc acc arm (mount-page! arm b (rf.bench.hicasso.arm1.runtime/stats))))
                          {} measured-arms)]
        (try
          (let [wrong (into [] (remove (fn [arm]
                                         (= (select-keys (rf.bench.hicasso.topo.arms/expected arm b)
                                                         [:boundaries :edges :elements :rendered-rows])
                                            (:structure (get pages arm)))))
                            measured-arms)]
            (when (seq wrong)
              (throw (ex-info (str "at B=" b " these pages are not the arms they claim: "
                                   (pr-str (into {} (map (fn [arm]
                                                           [arm {:expected (rf.bench.hicasso.topo.arms/expected arm b)
                                                                 :mounted  (:structure (get pages arm))}]))
                                                 wrong)))
                              {:b b :arms wrong})))
            (reduce (fn [acc op]
                      (let [r (run-row! pages op b)]
                        (when-let [f (:fatal r)] (throw (ex-info f {:op op :b b})))
                        (rf.bench.hicasso.lane/record! (keyword (str (name op) "-" b))
                                      (select-keys r [:markup :cells :ratios]))
                        (when (:refuse? (:guard r))
                          (set! (.-HICASSO_GUARD_REFUSED js/window) true))
                        (rf.bench.hicasso.lane/assert-verified! (:tally r)
                                               (str "topo clock " (name op) " @B=" b))
                        (assoc acc [op b] r)))
                    table
                    rows))
          (finally
            (doseq [arm measured-arms] (rf.bench.hicasso.arm1.mount/release! (:handle (get pages arm))))))))
    {}
    row-counts))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn ^:export -main []
  (rf/init! rf.adapter.uix/adapter)
  (rf.bench.hicasso.lane/leave-act-environment!)
  (rf.bench.hicasso.lane/self-test!)
  (try
    (rf.bench.hicasso.lane/record! :design
                  {:arms        measured-arms
                   :unaddressed unaddressed
                   :operations  operations
                   :floor-row   floor-op
                   :row-counts  row-counts
                   :batch-k     batch-k
                   :sampling    sampling
                   :rounds      rounds
                   :target      target
                   :markup      (into {} (for [b row-counts op rows arm measured-arms]
                                           [[arm op b] (markup-expected arm op b)]))})
    (rf.bench.hicasso.lane/record! :runtime (rf.bench.hicasso.lane/runtime-label))
    ;; THE CONTROL RUNS FIRST AND ALONE. If it refuses, the tournament's
    ;; clock half refuses with it and the cells are never taken — which is a
    ;; result, not a failure.
    (let [controls (run-controls!)]
      (rf.bench.hicasso.lane/record! :controls (into {} (map (fn [[arm v]] [arm (select-keys v [:predicted :band :rounds :ok? :why])]))
                                    controls))
      (if-not (every? :ok? (vals controls))
        (do
          (set! (.-HICASSO_CONTROL_FAILED js/window) true)
          (rf.bench.hicasso.lane/fail! (str "the registered positive control refused on "
                           (pr-str (into [] (comp (remove (comp :ok? val)) (map key)) controls))
                           " — no clock cell is taken for an arm whose instrument is not certified, "
                           "which is topology-tournament.md §1.5 as registered")))
        (let [table (run-table!)]
          (rf.bench.hicasso.lane/record! :floor-share (floor-shares table))
          (rf.bench.hicasso.lane/record! :summary
                        (into {} (for [[[op b] r] table]
                                   [[op b] {:cells  (into {} (map (fn [[arm s]] [arm (rf.bench.hicasso.lane/round4 (:p50 s))])) (:cells r))
                                            :ratios (into {} (map (fn [[arm v]]
                                                                    [arm (select-keys v [:mean :min :max :straddles-1?])]))
                                                          (:ratios r))}]))))))
    (catch :default e
      (set! (.-HICASSO_CONTROL_FAILED js/window) true)
      (rf.bench.hicasso.lane/fail! (str "topo clock threw: " (or (ex-message e) (.-message e))))))
  (rf.bench.hicasso.lane/done!))
