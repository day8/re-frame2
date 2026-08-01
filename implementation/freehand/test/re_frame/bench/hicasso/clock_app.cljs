(ns re-frame.bench.hicasso.clock-app
  "THE CANDIDATE'S CLOCK — the page half (rf2-0qj9w).

  The programme has no wall-clock measurement of its own candidate. Hook
  count and per-read retained heap are measured; mount, bulk K=100/300,
  narrow and per-keystroke are not, and every clock figure published so
  far — `M1` mount 1.0150×, bulk-broad 0.6291× — is about the DONORS.
  This entry, its driver `clock_run.cjs` and
  [[re-frame.bench.hicasso.clock-views]] are the three files that close
  that.

  ## This page does not decide when a sample starts, and that is the point

  Every other clock entry in this lane wraps `performance.now()` around a
  `flushSync` and banks the span. That window ends when the JavaScript
  returns — **before** the style recalculation, the layout, the pre-paint
  and the paint that the mutation causes. Under-reporting would be
  tolerable if it were common-mode; it is not. How much work a substrate
  leaves for the browser after its own call stack unwinds is exactly what
  differs between these arms, and Hicasso's whole design concerns WHEN
  work happens. An in-page window flatters whichever arm defers most.

  So this entry exposes OPERATIONS and no clock. `clock_run.cjs` reads
  Chrome's own renderer counters over the Chrome DevTools Protocol
  (`Performance.getMetrics`) on either side of one operation, and the
  operation does not resolve until the browser has produced the frame
  that follows it. The number banked is therefore main-thread task time
  INCLUDING style, layout and paint recording — the work a user waits
  for. `TaskDuration` is a protocol value rather than a web-exposed one,
  so it does not carry the Spectre clamp `performance.now()` does.

  The in-page span is still taken, on the same operation in the same
  sample, and published beside the frame-inclusive one. The gap between
  them is a measurement of the error the other instrument makes, per arm.

  ## THREE segments, one substrate arm each, and why that is forced

  `install-adapter!` is once per process, so Reagent and UIx cannot be
  interleaved inside one round — that much this lane already knew. What
  this row adds is a second, sharper constraint: a bulk write here is
  `frame/replace-app-db!`, and **every** arm mounted against that frame
  re-renders when it lands. Two substrate arms standing in one segment
  would each pay for the other's writes, and the clock would be reading a
  page carrying an arm that is not under test. So the candidate gets a
  segment of its own:

      :reagent-subs   floor, reagent-subs, ctl-2x
      :uix-subs       floor, uix-subs,     ctl-2x
      :hicasso        floor, hicasso,      ctl-2x

  The candidate's segment installs the **UIx adapter** — the one Arm 1's
  own witnesses install, and the substrate its React-hook spine is built
  over. The floor runs in all three: it holds no re-frame state, reads no
  subscription and is untouched by which adapter is installed, so every
  published figure is a ratio to the floor measured in THAT segment of
  THAT round, and a cross-segment figure is a ratio of two
  floor-normalised ratios with the seam cancelled. That is a weaker
  interleaving than one segment's and the record says so rather than
  describing it as though it were sample-level.

  ## What the driver calls, in order

      HCLOCK.enterSegment(segId)      install adapter, register, seed
      HCLOCK.plan(rowId, segId)       the arm ids, in plan order
      HCLOCK.canon(rowId, armId)      the page this arm builds, hashed
      HCLOCK.prepare(rowId, armId)    stand up whatever the row writes to
      HCLOCK.sample(rowId, armId)     ONE operation, settled to the next frame
      HCLOCK.finish(rowId, armId)     release it
      HCLOCK.tally()                  N unverified of M

  A keystroke row is the one exception: the driver sends the key itself
  through the protocol's input domain, because a JavaScript-dispatched
  event is not a user interaction and Event Timing reports user
  interactions. The page supplies [[settle-and-verify!]] for the half
  after the key.

  Owner: rf2-2rtt6.1 (standard); this entry rf2-0qj9w."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as hmount]
            [re-frame.bench.hicasso.arm1.runtime :as hrt]
            [re-frame.bench.hicasso.clock-views :as kv]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.p0-reagent-views :as v]
            [re-frame.bench.hicasso.p0-uix-views :as ux]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [uix.dom :as uix-dom]))

;; ---------------------------------------------------------------------------
;; Rows
;; ---------------------------------------------------------------------------

(def rows
  "The five clock rows the candidate has never had, and what one SAMPLE of
  each is.

  `:bulk100` and `:bulk300` are validation.md's `bulk K=100/300`: K is how
  many of the 300 mounted boundaries a single commit changes. The floor
  re-renders its whole tree either way — it has no reactive graph to
  localise with — so a K-sensitive arm shows up as a ratio that MOVES with
  K and a K-insensitive one as one that does not, which is the whole
  localisation question stated as an experiment."
  {:M1        {:kind :mount :cells 300
               :why "mount, 300 sub-reading boundaries, 901 elements — a bar row"}
   :bulk300   {:kind :bulk :k 300
               :why "one commit, all 300 boundaries change — a bar row"}
   :bulk100   {:kind :bulk :k 100
               :why "one commit, 100 of 300 boundaries change — the K=100 rung"}
   :narrow    {:kind :bulk :k 1
               :why "one commit, exactly one boundary changes — localisation"}
   :keystroke {:kind :keystroke :cells 100
               :why "one real keypress into a controlled field over 100 boundaries"}})

;; ---------------------------------------------------------------------------
;; Segments
;; ---------------------------------------------------------------------------

(def ^:private segments
  "Three, one substrate arm each. `:hicasso` installs the UIx adapter —
  Arm 1's React-hook spine is built over it and its own witnesses install
  it — so the segment names the arm under test rather than the adapter
  underneath it, and the record says which."
  [{:id :reagent-subs :adapter reagent-adapter/adapter :name "Reagent-on-subs"}
   {:id :uix-subs     :adapter uix-adapter/adapter     :name "UIx-on-subs"}
   {:id :hicasso      :adapter uix-adapter/adapter     :name "Hicasso Arm 1 (UIx adapter)"}])

(defn- segment-named [id] (first (filter #(= id (:id %)) segments)))

(defn- zeros [n] (vec (repeat n 0)))

(defonce ^:private state
  (atom {:segment nil :prepared {} :pending nil :tally nil :op 0 :gen 1000 :cells []}))

(defn- next-gen! [] (:gen (swap! state update :gen inc)))
(defn- next-op! [] (:op (swap! state update :op inc)))

(defn enter-segment!
  "Tear down whatever adapter is installed, install this segment's,
  re-register the sub graph and stand the frame back up seeded.

  All of it OUTSIDE every measured window. A destroy that throws is
  RECORDED rather than swallowed: this is the segment seam, and a frame
  that did not tear down keeps its subscription caches and its watches
  while the other adapter is installed over the top of them — after which
  every figure in the segment is a figure for a page carrying the previous
  segment's reactive graph."
  [seg-id]
  (let [{:keys [adapter]} (segment-named seg-id)]
    (try (rf/destroy-frame! v/subs-frame)
         (catch :default e (lane/teardown-failure! "enter-segment! destroy-frame!" e)))
    (when (rf/current-adapter)
      (try (rf/destroy-adapter!)
           (catch :default e (lane/teardown-failure! "enter-segment! destroy-adapter!" e))))
    ;; `reset-runtime!` drops every cell, edge and cached entry the
    ;; candidate holds — and it calls `forget-frame-ops!` on the way, so a
    ;; destroyed-and-recreated frame cannot hand the arm a memoised bundle
    ;; pointing at the previous incarnation.
    (hrt/reset-runtime!)
    (rf/init! adapter)
    (v/register!)
    (kv/register-draft!)
    (rf/make-frame {:id v/subs-frame})
    (frame/replace-app-db! v/subs-frame (v/seed-cells v/cells-n 0))
    (lane/leave-act-environment!)
    (swap! state assoc :segment seg-id :cells (zeros v/cells-n))
    nil))

;; ---------------------------------------------------------------------------
;; Arms — one door each, and every one the door its own application calls
;; ---------------------------------------------------------------------------

(defn- floor-mount-arm
  "`scale` is how many times the witness's own page this arm builds, and
  `:control?` is DERIVED from it: an arm that builds a different page ON
  PURPOSE is exactly the arm the canonical-DOM gate must exempt, and
  keeping the two facts independent is how a control's doubled page goes
  unchecked.

  `:floor?` marks an arm that writes through `root.render` rather than
  through the substrate. It is a PROPERTY OF THE ARM rather than a set of
  arm ids held in [[sample-bulk!]], because the id set was already wrong
  once the moment a fourth floor-shaped arm existed, and a floor-shaped
  arm mistaken for a substrate one would write app-db and be timed as
  though it had re-rendered."
  [id n scale]
  {:id       id
   :scale    scale
   :cells    n
   :floor?   true
   :control? (not= 1 scale)
   :mount    (fn [container]
               (let [root (react-dom-client/createRoot container)]
                 (react-dom/flushSync
                   (fn [] (.render root (v/m1-floor (zeros n)))))
                 root))
   :unmount  (fn [root] (.unmount root))})

;; ---------------------------------------------------------------------------
;; THE THREE-POINT CONTROL — the one that differences the constant away
;; ---------------------------------------------------------------------------

(def ^:private ctl3-dirty
  "The dirty-set sizes of the three-point control's arms, at FIXED page
  size. The floor supplies a fourth point at 300 for free, because a floor
  sample rewrites every one of its cells with a value that is fresh on
  every operation.

  ## Why the control this replaces cannot be repaired by widening it

  `ctl-2x` is the floor at twice the boundaries and its prediction is
  2.00x. Over seven runs (rf2-emvod) it read 1.8173x on the MOUNT row and
  1.7334 / 1.7696 / 1.7796 on bulk300 / bulk100 / narrow — four rows doing
  wildly different work, all undershooting by 9-13%. `rf2-5xrcd`'s
  diagnosis was that doubling the PAGE does not double an UPDATE's work,
  but that argument does not reach the mount row, where the work does
  scale with the page.

  ONE ADDITIVE CONSTANT fits all four at once. A ratio of two arms one of
  which is twice the other reads `(2W + c)/(W + c)`, which is below 2 for
  any positive `c` and does not care what kind of row it is. Inverting the
  four measured ratios against their own floors gives c = 1.040 / 1.043 /
  0.873 / 0.790 ms — one constant across a 901-element cold mount and a
  one-cell write, on floors spanning 3.58 to 5.70 ms.

  `rf2-7iqb5` proposed doubling the CHANGED SET instead. That removes the
  update-row confound and leaves `c` exactly where it was, because
  `(2D + c)/(D + c)` has the same shape as `(2W + c)/(W + c)`: a
  differently-wrong number, not a right one.

  ## What this construction is

  Arms that build ONE page and differ only in how many of its boundaries
  change per commit. The statistic is a DIFFERENCE OF DIFFERENCES:

      (T(2D) - T(eps)) / (T(D) - T(eps))  ->  (2D - eps) / (D - eps)

  and it is invariant under BOTH of the perturbations this lane has
  measured:

  * an ADDITIVE per-sample constant `c` — the protocol round trip, the
    settle, React's whole-tree reconciliation walk, the document's
    pre-paint — cancels in each difference, whatever its size, and the
    control needs no claim about what it is;
  * a MULTIPLICATIVE block-level perturbation `λ` — which is what
    `rf2-cvvb7`'s nineteen-run load ladder found ambient load to be —
    cancels in the quotient, because `λaΔd₂ / λaΔd₁` drops `λ`.

  `ctl-2x` is invariant under the second only. That is the whole repair.

  ## THE FIRST BUILD OF THIS CONTROL WAS REFUTED, AND BY WHAT

  It was built exactly as `rf2-emvod` ruled it — eps = 1, D = 100, 2D =
  200, on the floor's own 300-boundary page, canonical-DOM identical and
  not exempted from the fairness gate. It REFUSED, 11 of 18 blocks inside
  the band, at a per-block median of 1.609x against a predicted 2.0101x.
  The refusal was not noise: the denominator was a healthy 0.96 ms
  [0.84–1.42] and the shortfall was consistent across all three segments.

  What refuted it is that the page's cost is NOT affine in the dirty set
  over `[1, 300]`. Marginal cost per dirty cell, tared p50 over 18 blocks
  at 40 samples each:

      interval      reagent    uix    hicasso
      [1, 100]       11.18    9.70     11.49   µs per dirty cell
      [100, 200]      6.18    5.94      5.25
      [200, 300]      6.19    6.89      7.05

  The first hundred cells cost about TWICE what the next two hundred do,
  on every arm. `LayoutDuration` alone is clean — 0.132 / 0.534 / 0.994 /
  1.337 ms at d = 1 / 100 / 200 / 300 on the Reagent segment, which is the
  three-point statistic reading 2.14x on the layout counter — so the
  workload's LAYOUT half is affine and something else is not.

  The mechanism is PAINT, and it saturates. A commit that dirties cells
  0..d-1 damages a region that grows with `d` only until it covers the
  VIEWPORT; past that, the extra dirty rows are off-screen, they are laid
  out but never painted. The viewport holds a few tens of rows, so paint
  is still growing at d = 1 and has stopped by d = 100, which is exactly
  the shape in the table. It is a property of the page and the window,
  not of the instrument, and no amount of differencing removes it because
  it is not a constant — it is a saturating function of the very axis the
  control varies.

  RECORDED AS A REFUTATION, and the `eps` arm is kept as the WITNESS of
  it: [[ctl3-witness-dirty]] is published on every row so the claim that
  the affine regime starts well below `D` is re-measured every run rather
  than inherited from this docstring.

  ## Why the page is 3,000 boundaries and not the floor's 300

  Two constraints, and on a 300-cell page they have no common solution.

  The control's three points must sit ABOVE the paint knee, which the
  table above puts somewhere below d = 100. That leaves `[100, 300]` to
  work in, so the widest spacing available is 100 cells per interval —
  and at ~6 µs per cell that is a denominator of 0.60 ms. Measured
  against it, the per-sample jitter of this instrument gives a p50
  standard error of roughly 0.2 ms at 40 samples, so the quotient's own
  spread swamps the 25% band: adjudicated at 100/200/300 the same
  dataset put only 7 of 18 blocks inside, with the denominator collapsing
  as low as 0.10 ms in one block. A control that refuses because its
  denominator went to noise has not caught anything.

  SIGNAL SCALES WITH THE PAGE AND THE NOISE DOES NOT — the jitter is the
  harness, the protocol round trip and the ambient box, none of which
  care how many rows the list has. So the fix is a bigger page for the
  control's own arms: 3,000 boundaries, points at 1,000 / 2,000 / 3,000,
  spacing 1,000 cells, denominator ~6 ms against the same ~0.28 ms of
  noise. The required spacing was DERIVED from the measured noise before
  the page size was chosen, rather than chosen and then justified.

  ## What that costs, stated rather than buried

  These arms no longer build the floor's page, so the canonical-DOM gate
  must exempt them exactly as it exempts `ctl-2x`, and the guarantee the
  300-cell build had is gone. What replaces it is narrower and is the one
  that matters for a control whose arms are compared only with EACH
  OTHER: the driver checks that all four arms of the control hash to the
  SAME canonical page as one another, and refuses the row if they do not.

  The wall clock barely moves. A sample on this instrument is dominated
  by the protocol round trip and the frame settle, not by the page: at
  10 samples a bulk row took 72 s with the 300-cell arms, and the extra
  page work here is a few milliseconds on three arms of eight."
  {:ctl-b1000 1000 :ctl-b2000 2000 :ctl-b3000 3000})

(def ^:private ctl3-cells
  "The control's own page: 3,000 boundaries, 9,001 elements. See
  [[ctl3-dirty]] for why it is not the floor's 300."
  3000)

(def ^:private ctl3-witness-dirty
  "THE REGIME WITNESS, and it is not a control point.

  One dirty cell on the control's own page. Its job is to re-measure, on
  every run, the thing that refuted the first build of this control: that
  the marginal cost per dirty cell is much higher at the bottom of the
  range than it is between the control's own points, because paint is
  still growing there and has saturated by the time the control starts.

  Published as a marginal-cost table rather than adjudicated. If a run
  ever showed the low-end marginal cost AGREEING with the control's own,
  the paint-saturation account would be wrong — and the control's choice
  of `eps = 1000` rather than `eps = 1` would be unmotivated, which is a
  finding about this instrument that a reader should be handed rather
  than have to reconstruct."
  1)

(defn- dirty-cells
  "The `d`-dirty arm's next render: the first `d` cells carry `val`, which
  is fresh on every operation, and the remaining `n - d` hold 0 and never
  move. EXACTLY `d` boundaries change per commit, by construction rather
  than by assertion — and the read-back in [[sample-bulk!]] probes a cold
  cell as well as two hot ones, so an arm that dirtied more than it
  declared fails its own verification."
  [n d val]
  (into (vec (repeat d val)) (repeat (- n d) 0)))

(defn- dirty-floor-arm
  "One point of the three-point control: the control's own page, `d` of its
  boundaries dirty per commit.

  `:ctl3?` marks it as belonging to the control, and it is what
  [[sabotage!]] is scoped to — the floor also dirties every one of its
  cells, and a falsification that reached it would corrupt the
  denominator of every published ratio instead of the control it is
  supposed to break."
  [id d]
  (assoc (floor-mount-arm id ctl3-cells (/ ctl3-cells v/cells-n))
         :dirty d
         :ctl3? true))

(def ^:private plumb-arm
  "THE TARE. An arm that mounts nothing, writes nothing and settles the
  same frame every other arm settles.

  It exists because the driver's window is not free. One sample costs a
  `Runtime.callFunctionOn` round trip, the task that runs it, a
  `requestAnimationFrame` callback, a `setTimeout` callback, and the
  browser producing a frame — main-thread work that lands inside the
  counters and is the SAME for every arm. An uncorrected ratio therefore
  reads `(2W + c)/(W + c)`, which is below 2 for any positive `c`. Run 1
  of this instrument measured that directly: the doubling control read
  1.5909x [1.1635 – 2.1509] against a prediction of 2.00x, and its
  decomposition showed layout doubling exactly (1.56 -> 3.08 ms) while
  3.6 ms of every sample did not move at all.

  This arm measures that 3.6 ms, and every published figure subtracts it.

  **It is not the `zero-reading NOOP arm` this lane records as an
  instrument fault.** That fault is an arm that was supposed to do work
  and did none, so its cheapness was mistaken for speed. This one is
  supposed to do none: its reading IS the quantity, it is published, and
  it is subtracted rather than compared."
  {:id       :plumb
   :cells    0
   :control? true
   :plumb?   true
   :mount    (fn [_container] nil)
   :unmount  (fn [_] nil)})

(defn- m1-arms [seg-id]
  [plumb-arm
   (floor-mount-arm :floor v/cells-n 1)
   (case seg-id
     :reagent-subs {:id      :reagent-subs
                    :cells   v/cells-n
                    :mount   (fn [container]
                               (let [root (rdc/create-root container)]
                                 (react-dom/flushSync
                                   (fn [] (rdc/render root (v/subs-root v/m1-subs v/cells-n))))
                                 root))
                    :unmount (fn [root] (rdc/unmount root))}
     :uix-subs     {:id      :uix-subs
                    :cells   v/cells-n
                    :mount   (fn [container]
                               (let [root (uix-dom/create-root container)]
                                 (react-dom/flushSync
                                   (fn [] (uix-dom/render-root (ux/subs-root ux/m1 v/cells-n) root)))
                                 root))
                    :unmount (fn [root] (uix-dom/unmount-root root))}
     ;; `mount/root!` is the candidate's own door — what an Arm 1
     ;; application calls. A shared door would measure the shim.
     ;;
     ;; `unmount!`'s half and NOT `release!`: `release!` also resets the
     ;; runtime, which would drop every cell and cached entry between
     ;; samples and hand the arm a cold start each time. No donor gets
     ;; that treatment — the frame and its subscription cache outlive a
     ;; Reagent or UIx unmount — so the candidate is measured with its own
     ;; caches surviving exactly as theirs do. The cell reaper still
     ;; disposes a cell whose last reader unmounted, one macrotask later,
     ;; which is the arm's own behaviour and not the harness's to
     ;; suppress.
     :hicasso      {:id      :hicasso
                    :cells   v/cells-n
                    :mount   (fn [container]
                               (hmount/root! container v/subs-frame (kv/m1 v/cells-n)))
                    :unmount (fn [handle] (.unmount ^js (:root handle)))})
   (floor-mount-arm :ctl-2x (* 2 v/cells-n) 2)])

(defn- ctl3-arms
  "The three-point control's arms plus its regime witness, and they exist
  on BULK ROWS ONLY.

  A mount row's operation IS the mount, so it has no standing page to
  write a changed set into and no changed-set axis to be linear in. Its
  control stays `ctl-2x` and stays known to undershoot — recorded on the
  bead rather than papered over here, because the row this instrument
  blocks is a bulk row and an arm invented for a mount row would be an
  arm nothing had asked for.

  The witness is FIRST in the plan and lowest in dirty count, so a reader
  of the marginal-cost table reads it in the order the cells rise."
  []
  (let [top (apply max (vals ctl3-dirty))]
    (into [(assoc (dirty-floor-arm :ctl-b-witness ctl3-witness-dirty)
                  :ctl3? false :ctl3-witness? true)]
          (mapv (fn [[id d]]
                  ;; `:ctl3-top?` is the 2D point, and it is the ONE arm
                  ;; `sabotage!` reaches. Moving all three together would
                  ;; collapse the denominator to zero and refuse on a
                  ;; division rather than on a wrong number — a refusal that
                  ;; demonstrates nothing about the control's sensitivity.
                  (cond-> (dirty-floor-arm id d) (= d top) (assoc :ctl3-top? true)))
                (sort-by val ctl3-dirty)))))

(defn- kb-floor-arm [id busy]
  {:id           id
   :cells        kv/kb-cells-n
   :control?     (pos? busy)
   :lower-bound? true
   :mount        (fn [container]
                   (let [root (react-dom-client/createRoot container)]
                     (react-dom/flushSync
                       (fn [] (.render root (kv/kb-floor-element kv/kb-cells-n busy))))
                     root))
   :unmount      (fn [root] (.unmount root))})

(defn- kb-arms [seg-id]
  [plumb-arm
   (kb-floor-arm :floor 0)
   (case seg-id
     :reagent-subs {:id      :reagent-subs
                    :cells   kv/kb-cells-n
                    :mount   (fn [container]
                               (let [root (rdc/create-root container)]
                                 (react-dom/flushSync
                                   (fn [] (rdc/render root (kv/r-kb-root kv/kb-cells-n))))
                                 root))
                    :unmount (fn [root] (rdc/unmount root))}
     :uix-subs     {:id      :uix-subs
                    :cells   kv/kb-cells-n
                    :mount   (fn [container]
                               (let [root (uix-dom/create-root container)]
                                 (react-dom/flushSync
                                   (fn [] (uix-dom/render-root (kv/u-kb-root kv/kb-cells-n) root)))
                                 root))
                    :unmount (fn [root] (uix-dom/unmount-root root))}
     :hicasso      {:id      :hicasso
                    :cells   kv/kb-cells-n
                    :mount   (fn [container]
                               (hmount/root! container v/subs-frame (kv/kb-form kv/kb-cells-n)))
                    :unmount (fn [handle] (.unmount ^js (:root handle)))})
   (kb-floor-arm :ctl-50ms 50)])

(defn- arms-for [row-key seg-id]
  (cond
    (= :keystroke row-key)              (kb-arms seg-id)
    (= :bulk (:kind (get rows row-key))) (into (m1-arms seg-id) (ctl3-arms))
    :else                                (m1-arms seg-id)))

(defn- arm-named [row-key arm-id]
  (first (filter #(= arm-id (:id %)) (arms-for row-key (:segment @state)))))

(defn- expected-elements [row-key arm]
  (if (= :keystroke row-key)
    (kv/kb-elements (:cells arm))
    (v/m1-elements (:cells arm))))

;; ---------------------------------------------------------------------------
;; The write, and its read-back
;; ---------------------------------------------------------------------------

(defn- write-cells!
  "Install a new app-db in which `k` of the 300 cells carry `val`, and
  answer the cells to probe.

  `k = 300` is the broad row, `k = 100` the K=100 rung, `k = 1` narrow.
  The narrow cell ROTATES with the operation — `(mod (* 7 op) 300)`, and 7
  is coprime with 300 so any run of consecutive ops takes different cells
  — and the value is fresh on every write, so a stale page holds the
  PREVIOUS value at the probed cell and fails its read-back.

  **The vector CARRIES FORWARD rather than being rebuilt from zeros.** A
  narrow write starting from a fresh zero vector would reset the previous
  op's cell as well as setting this one, so TWO boundaries would change
  and the row would not measure what its name says. K is the number of
  boundaries whose value moves, and this is what makes that true."
  [k val op]
  (let [n     v/cells-n
        prev  (:cells @state)
        start (if (= k 1) (mod (* 7 op) n) 0)
        cells (reduce (fn [cs d] (assoc cs (mod (+ start d) n) val)) prev (range k))]
    (swap! state assoc :cells cells)
    (frame/replace-app-db! v/subs-frame {:cells cells})
    (if (= k 1) [start] [start (mod (+ start (dec k)) n)])))

(defn- drain!
  "The arm's own synchronous drain, uniform in SHAPE across arms because a
  window shape that differs between arms prices the window.

  Reagent's is `reagent.core/flush` inside one `flushSync`. UIx's and
  Hicasso's is an EMPTY `flushSync`, because a `useSyncExternalStore`
  notification schedules at React's SYNC lane and an empty flush is what
  lets it land. Not `flush-views!` and not `act` — `act` diverts work to a
  queue that is not the browser's, and every window here is taken outside
  it."
  [arm-id]
  (if (= :reagent-subs arm-id)
    (react-dom/flushSync (fn [] (r/flush)))
    (react-dom/flushSync (fn [] nil))))

;; ---------------------------------------------------------------------------
;; The frame settle — what makes this instrument frame-inclusive
;; ---------------------------------------------------------------------------

(defn- settle-frame
  "A promise that resolves AFTER the browser has produced the frame that
  follows the mutation.

  `requestAnimationFrame` runs BEFORE paint, so a callback registered
  there is not enough on its own; the `setTimeout` inside it lands in the
  first task after that frame's rendering lifecycle has run. This is the
  standard after-paint idiom, and it is what makes the driver's
  `Performance.getMetrics` delta include the style recalculation, the
  layout and the paint recording that the in-page span excludes.

  The WAIT costs wall clock and costs the measurement nothing: an idle
  browser accrues no task time, so the counters the driver reads move only
  for work that was actually done."
  []
  (js/Promise. (fn [resolve]
                 (js/requestAnimationFrame (fn [] (js/setTimeout resolve 0))))))

;; ---------------------------------------------------------------------------
;; Prepare / sample / finish
;; ---------------------------------------------------------------------------

(defn prepare!
  "Stand up whatever this row writes to, outside every window, and check
  the page it built against the arm's own arithmetic.

  A mount row prepares nothing: its operation IS the mount. Every other
  row mounts once here and writes to that standing mount thereafter.

  The prepared mounts are a MAP keyed by arm, not one slot, because the
  sample loop interleaves arms and a single slot would force block
  interleaving — which is what the arm-order guard exists to refuse."
  [row-key arm-id]
  (let [arm (arm-named row-key arm-id)]
    (if (or (:plumb? arm) (= :mount (:kind (get rows row-key))))
      (settle-frame)
      (let [container (lane/fresh-container!)
            handle    ((:mount arm) container)
            expected  (expected-elements row-key arm)
            got       (lane/element-count container)]
        (when-not (= expected got)
          (throw (js/Error. (str "the " (name arm-id) " arm built " got
                                 " elements where its own " (:cells arm)
                                 " cells make " expected))))
        (swap! state assoc-in [:prepared arm-id]
               {:arm arm :container container :handle handle})
        (settle-frame)))))

(defn solo!
  "Show ONLY `arm-id`'s standing mount and hide every other arm's, then
  settle a frame so the newly-shown subtree is laid out BEFORE anything is
  measured.

  ## Why this exists, and it is a measured repair rather than a tidy-up

  A row that writes to a standing mount has every arm of the segment
  mounted at once, and run 2 of this instrument measured what that costs.
  A frame in which NOTHING is dirty is nearly free — the tare arm read
  0.33 ms — but a frame in which anything is dirty runs pre-paint and
  paint over the whole document, and this row's document is four arms
  deep: 901 + 901 + 1,801 elements. So every bulk sample carried ~1.2 ms
  that belonged to arms that were not under test, and every ratio was
  compressed toward 1.0 by it. The doubling control said so plainly —
  1.4304–1.5214x against a prediction of 2.00x on the three bulk rows,
  while the SAME control on the mount row, where only one arm is ever
  standing, passed at 1.9103x. One instrument, two answers, and the
  difference is exactly whether the other arms were on the page.

  `display: none` and not an unmount, because the arm must stay WARM: its
  React tree, its subscription cache and its cells are what a steady-state
  write meets, and remounting per sample would price a cold first write
  instead. A hidden subtree is not laid out and not painted, so it leaves
  the frame; it keeps everything else.

  The show is done HERE, outside every window, and followed by a settle,
  so the full layout of the arm that is about to be measured has already
  happened when the clock starts."
  [_row-key arm-id]
  (doseq [[id p] (:prepared @state)]
    (set! (.. ^js (:container p) -style -display) (if (= id arm-id) "" "none")))
  (settle-frame))

(defn finish!
  "Release this arm's standing mount. Never timed."
  [_row-key arm-id]
  (when-some [p (get-in @state [:prepared arm-id])]
    (lane/release! p)
    (swap! state update :prepared dissoc arm-id))
  (settle-frame))

(defn- bank! [ok?]
  (let [t (:tally @state)]
    (swap! t (fn [{:keys [of bad]}] {:of (inc of) :bad (if ok? bad (inc bad))}))
    ok?))

(defn- sample-mount!
  "ONE mount, and NOTHING ELSE inside the driver's window.

  The container is created and attached outside the in-page span — a
  `document.createElement` billed to one arm and not another is a
  systematic error the size of some of the effects here — and the mount is
  left STANDING. Verifying it and tearing it down are [[reap!]]'s, which
  the driver calls after it has read the counters, because unmounting 300
  or 600 boundaries is real work and charging it to the mount would price
  a teardown as part of a mount row."
  [_row-key arm]
  (let [container (lane/fresh-container!)
        handle    (volatile! nil)
        t0        (lane/now-ms)
        _         (vreset! handle ((:mount arm) container))
        ms        (- (lane/now-ms) t0)]
    (swap! state assoc :pending {:arm arm :container container :handle @handle})
    (.then (settle-frame) (fn [_] #js {:inPageMs ms :ok true}))))

(defn reap!
  "Verify and release the mount [[sample-mount!]] left standing. Called by
  the driver AFTER it has read the counters, so nothing here is timed."
  [row-key]
  (if-some [p (:pending @state)]
    (let [ok? (= (expected-elements row-key (:arm p)) (lane/element-count (:container p)))]
      (bank! ok?)
      (lane/release! p)
      (swap! state assoc :pending nil)
      (.then (settle-frame) (fn [_] #js {:ok ok?})))
    (.then (settle-frame) (fn [_] #js {:ok true}))))

(defn- sample-bulk!
  "ONE commit. Write, drain, stop the in-page span, then READ THE WRITTEN
  CELLS BACK OUT OF THE DOM before settling the frame.

  The read-back is not decoration. On a predecessor harness, deleting the
  drain made a substrate arm fail its read-back on EVERY write while
  reading FASTER, with a range that still overlapped the valid window's —
  the clock alone would have accepted it."
  [row-key arm]
  (let [k      (:k (get rows row-key))
        val    (next-gen!)
        op     (next-op!)
        mnt    (get-in @state [:prepared (:id arm)])
        cont   (:container mnt)
        root   (:handle mnt)
        n      (:cells arm)
        ;; The DECLARED dirty count is the arm's; the ACTUAL one is what
        ;; [[sabotage!]] may have replaced it with. The gap between them is
        ;; the falsification, and it is the page that carries it — the
        ;; driver goes on predicting from the declaration, which is what
        ;; makes the control the only gate that can see it.
        dirty  (when-some [d (:dirty arm)]
                 (if (:ctl3-top? arm) (or (:sabotage @state) d) d))
        t0     (lane/now-ms)
        ;; The floor renders INSIDE the `flushSync`, element tree and all,
        ;; and neither half is a convenience. `root.render` called outside
        ;; a React event schedules at React's DEFAULT lane and an empty
        ;; `flushSync` flushes only the SYNC lane, so a floor arm that
        ;; rendered outside one would have its commit land outside the
        ;; measured window entirely — the recorded fault is 80 of 320
        ;; floor samples ending on a cell that still held its old value.
        ;; And hoisting the element tree out would under-charge the
        ;; denominator every published ratio is taken against.
        ;;
        ;; A probe is `[index expected-text]` rather than a bare index,
        ;; because a dirty-set arm has to prove BOTH halves of its own
        ;; claim: that the cells it said it would change did, and that a
        ;; cell it said it would leave alone still holds `0`. An arm that
        ;; quietly dirtied its whole page would pass an index-only
        ;; read-back while destroying the control it belongs to.
        probes (cond
                 (some? dirty)
                 (do (react-dom/flushSync
                       (fn [] (.render ^js root (v/m1-floor (dirty-cells n dirty val)))))
                     (cond-> [[0 (str val)] [(dec dirty) (str val)]]
                       (< dirty n) (conj [(dec n) "0"])))

                 (:floor? arm)
                 (do (react-dom/flushSync
                       (fn [] (.render ^js root (v/m1-floor (vec (repeat n val))))))
                     [[0 (str val)] [(dec n) (str val)]])

                 :else
                 (let [ps (write-cells! k val op)]
                   (drain! (:id arm))
                   (mapv (fn [i] [i (str val)]) ps)))
        ms     (- (lane/now-ms) t0)
        ok?    (every? (fn [[i expect]] (= expect (lane/text-at cont i))) probes)]
    (bank! ok?)
    (.then (settle-frame) (fn [_] #js {:inPageMs ms :ok ok?}))))

(defn sample!
  "ONE operation of one arm, settled to the next frame. The tare arm's
  operation is the settle and nothing else — that is the whole of what it
  is for."
  [row-key arm-id]
  (let [arm (arm-named row-key arm-id)]
    (if (:plumb? arm)
      (.then (settle-frame) (fn [_] (bank! true) #js {:inPageMs 0 :ok true}))
      (case (:kind (get rows row-key))
        :mount (sample-mount! row-key arm)
        :bulk  (sample-bulk! row-key arm)
        (throw (js/Error. (str "row " (name row-key)
                               " has no in-page sample door — the driver owns its input")))))))

;; ---------------------------------------------------------------------------
;; The keystroke half the driver cannot do from JavaScript
;; ---------------------------------------------------------------------------

(defn sabotage!
  "Make the three-point control's 2D arm dirty `d` cells instead of the
  3,000 it declares, and answer what was installed.

  ## This exists so the control can be shown REFUSING

  A control that has never been seen to fail is not evidence that the
  instrument is sound; it is a control whose sensitivity is unmeasured,
  and this lane has found that defect nine times in two days. So the
  falsification is a KNOB rather than a hand edit someone once made and
  described afterwards: a reader reproduces the refusal with an
  environment variable.

  What it breaks is the one thing the control is for. The arms go on
  DECLARING 1,000 / 2,000 / 3,000 to the driver, so the prediction stays
  `(3000-1000)/(2000-1000) = 2.00`, while the top arm renders `d`. Every
  other gate is untouched and passes — the four control arms still hash to
  the same canonical page as one another, the read-back verifies (the
  probes follow the ACTUAL dirty set, so the cells that changed did change
  and the cold cell is still `0`), the arm-order guard is clean, the band
  is unmoved. The control is the only gate in this driver that can see it,
  and a run with the knob on is expected to exit 1 naming exactly that.

  `d = 2400` is the documented example: it should read
  `(2400-1000)/(2000-1000) = 1.40x`, which is outside the band and inside
  the range of values a merely noisy run could not reach."
  [d]
  (swap! state assoc :sabotage (when (and (number? d) (pos? d) (<= d ctl3-cells)) (long d)))
  (:sabotage @state))

(defn focus-draft!
  "Focus this arm's controlled field, so the driver's key events land in
  it. Outside every window."
  [arm-id]
  (let [cont (:container (get-in @state [:prepared arm-id]))
        el   (some-> cont (.querySelector "input.draft"))]
    (if (some? el) (do (.focus ^js el) true) false)))

(defn settle-and-verify!
  "The half of a keystroke sample the page owns: settle the frame the
  keypress caused, then read the field's value back out of the DOM.

  The driver sends the key through the protocol's input domain rather than
  dispatching one from JavaScript. A JavaScript-dispatched event is not a
  user interaction, and Event Timing reports user interactions."
  [arm-id expected]
  (.then (settle-frame)
         (fn [_]
           (let [cont (:container (get-in @state [:prepared arm-id]))
                 el   (some-> cont (.querySelector "input.draft"))
                 got  (some-> ^js el .-value)]
             #js {:ok (bank! (= expected got)) :got (str got)}))))

;; ---------------------------------------------------------------------------
;; The fairness gate
;; ---------------------------------------------------------------------------

(defn- str-hash
  "A 32-bit FNV-style hash of `s`, so the driver can compare the pages
  three segments built without moving three 18 KB strings across the
  protocol per row."
  [s]
  (let [n (count s)]
    (loop [i 0 h (int -2128831035)]
      (if (< i n)
        ;; `Math.imul` and not `*`: a 32-bit FNV multiply overflows the
        ;; double's exact-integer range, and a hash that loses low bits is
        ;; a hash that lets two different pages compare equal.
        (recur (inc i) (js/Math.imul (bit-xor h (.charCodeAt s i)) 16777619))
        h))))

(defn canon!
  "Mount this arm once OUTSIDE every window and answer the page it builds,
  with attribute names SORTED and then hashed.

  `innerHTML` preserves insertion order and two front ends write props in
  different orders, so comparing it compares the serialiser rather than
  the page. This is the entire fairness guarantee of a cross-arm ratio:
  without it two arms can be timed against each other while building
  different pages. The driver compares across all three segments, because
  that is where the candidate meets its donors."
  [row-key arm-id]
  (let [arm (arm-named row-key arm-id)]
    (if (:plumb? arm)
      ;; The tare builds no page, so it has none to compare. Marked a
      ;; control so the gate exempts it by the same rule that exempts the
      ;; doubling arm.
      #js {:arm (name arm-id) :hash 0 :bytes 0 :control true}
      (let [c (lane/fresh-container!)
            h ((:mount arm) c)
            s (lane/canonical c)]
        (lane/release! {:arm arm :container c :handle h})
        ;; The canon mount wrote nothing, but a Hicasso or Reagent arm
        ;; mounted here has warmed the frame's caches. Re-seed so a row's
        ;; first sample meets the same app-db every other one does.
        (frame/replace-app-db! v/subs-frame (v/seed-cells v/cells-n 0))
        (swap! state assoc :cells (zeros v/cells-n))
        #js {:arm (name arm-id) :hash (str-hash s) :bytes (count s)
             :control (boolean (:control? arm))}))))

;; ---------------------------------------------------------------------------
;; The driver's front door
;; ---------------------------------------------------------------------------

(defn -main []
  (lane/leave-act-environment!)
  (swap! state assoc :tally (lane/tally))
  (set! (.-HCLOCK js/window)
        #js {:rows     (clj->js (mapv (fn [[k m]] {:id (name k) :why (:why m)}) rows))
             :segments (clj->js (mapv (fn [s] {:id (name (:id s)) :name (:name s)}) segments))
             :cellsN   v/cells-n
             :kbCellsN kv/kb-cells-n
             :enterSegment  (fn [seg] (enter-segment! (keyword seg)) true)
             ;; `dirty` is the DECLARED count and never the sabotaged one.
             ;; The driver's prediction is derived from what the page says
             ;; each arm will change rather than from a literal it carries,
             ;; so the two can be made to disagree on purpose and the
             ;; control is what notices.
             :plan          (fn [row seg]
                              (clj->js (mapv (fn [a] {:id      (name (:id a))
                                                      :scale   (or (:scale a) 1)
                                                      :cells   (or (:cells a) 0)
                                                      :control (boolean (:control? a))
                                                      :dirty   (or (:dirty a) 0)
                                                      :ctl3    (boolean (:ctl3? a))
                                                      :ctl3Witness (boolean (:ctl3-witness? a))
                                                      :lowerBound (boolean (:lower-bound? a))})
                                             (arms-for (keyword row) (keyword seg)))))
             :canon         (fn [row arm] (canon! (keyword row) (keyword arm)))
             :prepare       (fn [row arm] (prepare! (keyword row) (keyword arm)))
             :sample        (fn [row arm] (sample! (keyword row) (keyword arm)))
             :reap          (fn [row] (reap! (keyword row)))
             :solo          (fn [row arm] (solo! (keyword row) (keyword arm)))
             :finish        (fn [row arm] (finish! (keyword row) (keyword arm)))
             :focusDraft    (fn [arm] (focus-draft! (keyword arm)))
             :settleVerify  (fn [arm expected] (settle-and-verify! (keyword arm) expected))
             :settle        (fn [] (settle-frame))
             :sabotage      (fn [d] (sabotage! d))
             :tally         (fn [] (clj->js (lane/tally-value (:tally @state))))
             :teardownCheck (fn [] (clj->js (mapv :where (lane/drain-teardown-failures!))))
             :runtime       (fn [] (pr-str (lane/runtime-label)))
             ;; Both censuses, because neither sees the other's references:
             ;; `lane/residue` counts attached containers and the frame's
             ;; sub-cache, `runtime/residue` counts the candidate's own
             ;; cells, edges and cached entries.
             :residue       (fn [] (pr-str {:lane (lane/residue v/subs-frame)
                                            :arm1 (hrt/residue)}))})
  (set! (.-HCLOCK_READY js/window) true)
  (js/console.log ";; HCLOCK ready")
  nil)
