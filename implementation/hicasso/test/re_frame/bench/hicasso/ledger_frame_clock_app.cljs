(ns re-frame.bench.hicasso.ledger-frame-clock-app
  "THE LEDGER'S PER-FRAME CLOCK — a run of consecutive frames under a
  sustained scroll, on the witness application `U4` is stated over
  (rf2-xc0bw).

      HICASSO_INIT_FN=re-frame.bench.hicasso.ledger-frame-clock-app/-main \\
      HICASSO_OUT_DIR=out/hicasso-ledger-frame \\
      HICASSO_PORT=8141 \\
        node implementation/hicasso/test/re_frame/bench/hicasso/run.cjs

  NO NEW BUILD ID. `run.cjs` takes its entry from `HICASSO_INIT_FN` and
  rides `:hicasso-bench`, the id the whole lane already shares, so this
  arm costs `implementation/shadow-cljs.edn` — an HD-017 hot-zone file —
  nothing.

  ## WHY THIS IS A THIRD DRIVER ON A THIRD PAGE

  `docs/design/hicasso/product/budgets.md` §4 registers `U4` as
  *Dragging/animation stay inside frame budget*, estimand **per-frame
  latency**, and §9.4 governs it — with `U1`–`U3` — on a witness
  application under `implementation/hicasso/test/re_frame/hicasso/
  examples/`. Both landed clock drivers sit on the SLICE, and the slice
  publishes no drag and no animation: every interaction it has is
  discrete — a keystroke, a click, a select.
  Both siblings say so at source rather than leaving it to be
  discovered — [[re-frame.bench.hicasso.slice-echo-clock-app]] in its
  §THE ROWS, AND WHICH ESTIMANDS THEY CAN AND CANNOT SERVE, and
  [[re-frame.bench.hicasso.slice-broad-clock-app]] in its §`U4` IS NOT
  SERVED BY THIS DRIVER EITHER — and two repairs that look obvious are
  both wrong:

  - **A per-frame estimator driven by repeating a discrete interaction
    once a frame** would publish a scripted repetition of a discrete
    interaction against a line written about a continuous one — the same
    class of error the first driver exists to have stopped making, which
    was a `p95` of a mount published against a line about a paint.
  - **Adding a drag to the slice** would move the population. `U1`–`U4`
    are governed on the application's OWN interactions, not on
    interactions added to reach a row.

  `re-frame.hicasso.examples.ledger` needs neither. It publishes a
  virtualized ten-thousand-row list over a real scroll viewport — the
  vendor owns its scroll offset in `useState` and listens for the
  platform's own `scroll` events on the element it owns — and a scroll
  over a windowed list is a genuinely CONTINUOUS interaction that the
  application already has. So the population is reached by choosing the
  page, not by adding a gesture to it.

  ## THE ESTIMATOR IS FRAME INTERVALS, AND THAT IS WHY `window!` IS NOT HERE

  [[re-frame.bench.hicasso.slice-echo-clock-app/window!]] measures ONE
  interaction through to the paint that follows it and ends with a
  `setTimeout 0` task hop. **Chaining it once per frame would not produce
  a per-frame estimator**: the hop puts a task boundary between every
  pair of frames, and what a chain of windows publishes is a distribution
  over WINDOW LENGTHS. §4 draws exactly that line — `U4` is *a run of
  consecutive frames whose estimator is a distribution over frame
  intervals rather than over window lengths* — so a driver that reused
  the window here would be measuring the estimand the other two drivers
  already measure and filing it under the row that is not it.

  What IS shared, and is REQUIRED rather than transcribed, is
  `slice-echo-clock-app`'s `after-paint`: the frame-grid alignment every
  reading on this lane starts from. Both siblings settle on it outside
  every measurement, and so does [[prepare!]] here. A second copy of it
  is a second thing that can drift, which is the shape
  `lane/visit-plan`'s own docstring is written against.

  [[frames!]] is the estimator:

      after-paint ──▶ rAF ──▶ [frame 1: read t, observe, do the frame's
                     │         application work]
                     └─▶ rAF ──▶ [frame 2: read t, …]  ──▶ … ──▶ frame K

  `K` timestamps, read at the TOP of each frame's rendering steps and
  before any of that frame's work, give `K-1` INTERVALS. That is the
  reading, and it is the whole of what this file publishes a distribution
  over.

  ## THE SCHEDULE IS THE LANE'S, BUT THE LOOP CANNOT BE

  `lane/rounds-async!` schedules a sample as a promise of ONE NUMBER, and
  a frame run yields `K-1` of them, so that loop does not fit. What does
  fit — and is used — is `lane/visit-plan`, the PLAN both of the lane's
  loops walk: same `lane/slot-order` reflection, same warm-up boundary,
  same round boundary. Walking the plan with `lane/chain` and banking a VECTOR
  per visit rather than a number reuses the schedule at the one level
  where it is stated, instead of re-deriving `(count arms)`,
  `lane/slot-order` and the warm-up boundary here. `lane/visit-plan`
  prices what a second copy of the schedule has cost this lane: the
  `k = 2` degeneracy that survived a fix to its own sibling, and the
  predecessor tagging that was repaired twice privately while ten apps
  riding the shared loop kept the fault.

  One guard sample is banked per VISIT — that visit's median interval —
  so `lane/collect!`'s `:position` still counts visits across the whole
  run and `:predecessor` still names the arm that ran immediately before.

  ## WHAT CARRIES THE CLAIM THAT THESE ARE FRAMES UNDER A REAL SCROLL

  1. THE MECHANISM. A reading cannot be taken at all unless the browser
     produces frames: every timestamp comes from a `requestAnimationFrame`
     callback, so a run in which no frame was produced produces no
     reading — it hangs, and `run.cjs`'s sentinel kills it.

  2. THE PER-RUN VERIFICATION, TAKEN OFF THE MIRROR ONLY A COMMIT WRITES.
     Every frame of every arm reads the model index of the FIRST RENDERED
     ROW out of the DOM, inside the rendering steps and before style,
     layout and paint — see §WHERE THE OBSERVATION IS READ. A measured
     run is verified when that index ADVANCED across the run, which it
     can only do if the vendor saw the scroll, recomputed its window and
     React committed the result. It banks into `lane/tally` and
     `lane/assert-verified!` refuses the run at `N unverified of M`.

  3. THE NEGATIVE CONTROL, WHICH IS AN ARM RATHER THAN A ONE-SHOT.
     `:idle-frames` runs the same rAF chain and the same observation with
     NO scroll, and its verification is INVERTED: it is refused if the
     window advanced. A check that reported movement on a page nothing
     scrolled would make every measured arm's verification vacuous, and
     this one asks the question `rounds × (warmup + samples)` times
     rather than once. It also equalises the observation's own cost
     across the arms, which a floor that skipped the read would not.

  4. THE BOOT DISCRIMINATION, IN BOTH DIRECTIONS.
     [[advance-discrimination!]] takes one idle run and one scroll run
     before anything is measured and requires the first to REFUSE and the
     second to VERIFY. The second half is the one that earns its cost:
     `ledger.virtualized-dom-cljs-test` records two runs of its own that
     set `scrollTop`, watched the `scrollTop` assertion pass, and found
     the window unmoved because the notification never reached the
     vendor. A driver that met that would otherwise publish the box's
     frame grid on all three arms for four minutes before saying so.

  5. THE POSITIVE CONTROL, whose prediction is a FLOOR — described next.

  ## THE POSITIVE CONTROL PREDICTS A FLOOR, NOT A BAND

  `:ctl-blocked` is `:scroll` plus a busy-wait of [[blocked-ms]]
  milliseconds inside EVERY frame, after that frame's scroll has been
  delivered. Its prediction needs no model of the application and no
  band at all: **a frame in which the main thread was blocked for
  [[blocked-ms]] cannot be followed by the next frame in less than
  [[blocked-ms]]**, so no interval this arm produces may sit below it.

  The floor is EXACT in the reading domain rather than exact to within a
  tolerance, and that is worth stating because it is why nothing here is
  tuned. Both endpoints come from the same monotone clock: the interval's
  start `t-i` is read at the top of the callback, [[busy-wait!]] then
  takes its own reading `s ≥ t-i` and spins until a reading `e ≥ s + ms`,
  and the next frame's `t-i+1 ≥ e`. So `t-i+1 - t-i ≥ ms` from
  monotonicity alone — Chrome's 100 µs `performance.now()` clamp cannot
  reach it, because every term is on the same clamped grid.

  It is therefore adjudicated by [[control-verdict-floor]] and NOT by
  `lane/control-verdict` or `lane/control-verdict-strict`: both of those
  ask whether a measurement sits INSIDE a ±slack band around a
  prediction, and a floor has no upper edge. A blocked frame that ran
  long is not a control failure, it is a slow frame.

  [[control-verdict-floor]] carries `:versus-floor` — [[blocked-ms]]
  against the `:idle-frames` arm's own median interval — so a reader can
  see how many of the box's frames the injection spans. **It is context
  and not a line**: nothing is adjudicated against it, and the verdict
  would read the same without it.

  ## WHERE THE OBSERVATION IS READ, AND WHY IT IS THAT PLACE

  `views/ledger-row` writes `aria-rowindex` as `(inc index)` — the row's
  MODEL index, Rule 4 of the recipe — onto the element the vendor
  renders. `vendor/virtual-rows` renders `(range from (inc to))` in
  order into the spacer, and appends the pinned row LAST when it appends
  one at all. So the spacer's `firstElementChild` is the window's first
  row, and its `aria-rowindex` is a number **only React's own commit
  writes**.

  That is the property the check needs. The scroll this file performs
  mutates `scrollTop` on the viewport and dispatches an event; it writes
  no DOM, mints no element and moves no attribute. So an observed advance
  cannot have come from this file's own setup mutation — it can only have
  come from the vendor recomputing its window and React committing rows
  that were not there before. The slice driver reaches for `defaultValue`
  for the same reason and states the general rule at length: **a scripted
  interaction sets the control up by mutating it, so the control's own
  state is the one thing an echo check may not be taken over.** Here the
  mutated state is `scrollTop`, and `scrollTop` is exactly what this
  check does not read.

  The read is two property accesses and a `parseInt`, taken once per
  frame on every arm, and it is deliberately not a `querySelector`: the
  spacer is resolved once at [[boot!]] and the observation closes over
  it.

  ## THE GESTURE, AND WHY ITS NUMBERS ARE DERIVED

  [[scroll-step-px]] is `views/row-height` — ONE ROW PER FRAME, which at
  60 Hz is about 1,440 px/s: a brisk flick rather than a contrivance, and
  the unit `virtualized-dom-cljs-test` already measures the screen in
  (*a scroll costs the rows that ENTERED*). It is read off the screen's
  own geometry rather than typed, so a change to the row height moves the
  gesture with it instead of silently changing what a frame's work is.

  [[start-row]] puts the run clear of both ends of the model:
  `vendor/window-from` clamps `from` at 0, so a run beginning at the top
  would spend its first frames with the window standing still for a
  correct reason, and a run ending past the last row would stop advancing
  for another. [[boot!]] derives both bounds from `window-from` itself
  and REFUSES rather than letting either clamp bite.

  **No `flushSync` anywhere, and that is the one thing this driver does
  differently from the suite that drives the same screen.**
  `virtualized-dom-cljs-test`'s `scroll-to!` wraps its dispatch in
  `react-dom/flushSync` because a scroll is a CONTINUOUS-priority event
  and its `setState` is not flushed by an empty settle — correct for a
  test that must read the DOM on the next line. Here it would be fatal:
  forcing the vendor's update onto the sync lane replaces the scheduler
  whose per-frame behaviour is the subject. The platform's own scroll
  event for the same `scrollTop` write arrives later and carries the same
  offset, which the vendor's `useState` bails on.

  ## THE ROWS

      :idle-frames  the same rAF chain with no application work at all —
                    the box's own frame grid, and the floor every other
                    row pays. It is also the standing negative control on
                    the observation (§4 above).

      :scroll       the sustained scroll. `U4`'s estimand — per-frame
                    latency under a continuous interaction — on the
                    ledger's own.

      :ctl-blocked  `:scroll` plus [[blocked-ms]] of blocked main thread
                    inside every frame. The positive control.

  ## WHAT THIS FILE PUBLISHES, AND WHAT IT DELIBERATELY DOES NOT

  `{:n :min :max :p50 :p95 :p99}` per arm over the measured visits'
  intervals, an `:entry` summary over each measured visit's FIRST
  interval, the verification tally, the two instrument-integrity
  verdicts and the runtime label — and it compares none of them to
  anything.

  `:entry` is published because a run has a beginning: the first interval
  of a gesture follows a settle rather than another frame of the same
  gesture, and a reader of a tail quantile needs to be able to see
  whether that interval is like the rest. `:populations` says which
  visits each figure is taken over — `:summary` and `:entry` over the
  measured visits, `:advance` over every visit, warm-up included, because
  a verification is worth more the more runs it covers.

  **There is no threshold in this file, no band around a latency, and no
  pinned figure.** `U4`'s frame budget does not appear here and should
  not: reading this instrument against that line is a separate quiet-box
  window, and an instrument carrying the line it is meant to be read
  against is an instrument nobody could re-adjudicate. `budgets.md` §7
  routes every distributional row to pinned evidence runs on P-DEV-1 and
  never to a pull-request threshold; §9.1 says such a row may never name
  the first lane at all. **This is an INSTRUMENT and not a GATE.** The
  only pass/fail it makes are the verification tally and the control, and
  §2's rule holds for both: they are structural, not distributional.

  **No ledger cell moves.** `U4` stays `UNPINNED` until a window runs on
  this driver, and what that window still owes is a reading — not a
  driver.

  ## WHAT THIS DRIVER DOES NOT SERVE, STATED RATHER THAN LEFT TO BE FOUND

  It is not a comparative instrument: there is no donor arm, so `C3` and
  `C4` are untouched by it. It takes no `U1`, `U2` or `U3` reading — its
  window is not an interaction through to a paint, and the two slice
  drivers hold those rows.

  It measures ONE gesture at ONE speed. A scroll with the platform's
  focus inside a row — the ledger's own pin, and its most expensive
  scroll — is a second measured arm this file does not carry. What would
  warrant building it: a reading whose `:scroll` distribution sits close
  enough to the frame budget that the pin's extra row could decide it, at
  which point the honest answer is to measure the pinned gesture rather
  than to argue about it.

  There is no DOM self-test beside this file. `slice-echo-clock-app` has
  one; this one is owed, and its absence is why every structural claim
  above is made by a check the RUN performs rather than by a suite row.

  Owner: rf2-xc0bw."
  (:require [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.slice-echo-clock-app :as echo]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.ledger.app :as ledger-app]
            [re-frame.hicasso.examples.ledger.events :as ledger-events]
            [re-frame.hicasso.examples.ledger.vendor :as vendor]
            [re-frame.hicasso.examples.ledger.views :as views]))

;; ---------------------------------------------------------------------------
;; The knobs — every one of them a SCHEDULE knob, never a line
;; ---------------------------------------------------------------------------

(def total
  "How many records the ledger holds for this run. `ledger.events`'
  own `default-total` — §7's *10K-row behavior* — read from there rather
  than typed, so a run's population cannot disagree with the
  application's."
  ledger-events/default-total)

(def sampling
  "Per-round warm-up and measured counts. `rf2-h904p`'s values and both
  siblings', carried rather than chosen: a figure taken on a different
  schedule from the rows it sits beside is a comparison a reader has to
  reconcile before they can read it.

  **What is different here, and is carried anyway.** `rf2-h904p`'s
  argument for `:warmup 8` is that it puts the +27% step this lane sees
  after a site's SIXTH EXECUTION inside the warm-up — an argument about
  visits, on a driver whose visit is one window. A visit here is
  [[frames-per-run]] executions of the per-frame path, so ONE warm-up
  visit already clears that step and eight is far more generous than the
  argument requires. It is carried because the schedule is the lane's and
  because the run's cost is dominated by the control arm rather than by
  warm-up; the knob to reach for when a window needs more tail is
  [[rounds]] or `:samples`, and lowering `:warmup` is the cheapest place
  to find the time.

  **A tail quantile over a short sample is mostly interpolation**
  (`lane/quantile` prices exactly that). It is less pressing here than on
  the sibling clocks, because a visit banks [[frames-per-run]] − 1
  readings rather than one: at these values each arm's `:summary` is
  taken over 1,740 intervals."
  {:warmup 8 :samples 12})

(def rounds
  "Rounds per run. Five is the lane's standard and what `across-rounds`
  and the control verdicts are shaped for."
  5)

(def frames-per-run
  "How many consecutive frames one visit holds the gesture for.

  Thirty is half a second at 60 Hz, which is about the duration of a real
  flick — long enough that the run is a SUSTAINED interaction rather than
  a pair of frames, and short enough that the box is not asked to hold
  one gesture for so long that the run is really a soak test.

  It is also the run's cost, and that is priced here rather than
  discovered: three arms over `(warmup + samples) × rounds` visits is 300
  visits, of which 100 are the control arm at [[blocked-ms]] a frame —
  about 150 s for the control and about 100 s for the other two, plus two
  settle frames per visit. Roughly four and a half minutes, against
  `run.cjs`'s twenty-minute sentinel. Raising [[rounds]] raises all of
  it proportionally."
  30)

(def scroll-step-px
  "How far the viewport is scrolled each frame — ONE ROW.

  Derived from `views/row-height` rather than typed, so the gesture is
  stated in the screen's own units and a change to the row height cannot
  quietly change what a frame's work is. At 60 Hz one row a frame is
  about 1,440 px/s: a brisk flick, and the unit
  `virtualized-dom-cljs-test` measures the screen in."
  views/row-height)

(def start-row
  "The model row every visit's gesture starts at.

  Clear of the model's start by more than `views/overscan`, so
  `vendor/window-from`'s `(max 0 …)` clamp never bites and the window
  advances from the run's first frame; and far enough from its end that
  [[frames-per-run]] rows of gesture cannot reach it. [[boot!]] derives
  both bounds from `window-from` itself and refuses rather than trusting
  this sentence."
  100)

(def blocked-ms
  "How long `:ctl-blocked` blocks the main thread inside each frame.

  `slice-echo-clock-app/blocked-ms`'s value and its derivation: a
  paint-bounded reading is quantised by the display's rendering
  opportunities — roughly 16.7 ms at 60 Hz — so an injected cost much
  smaller than one frame can be absorbed by the quantisation entirely.
  50 ms is three frames.

  Stated here rather than aliased, because a control's prediction is a
  claim about THIS instrument and an instrument whose control moves when
  a sibling's moves is one nobody can re-adjudicate. The two are not the
  same injection in any case: over there the cost is paid once per
  window, on the seam between a commit and a frame; here it is paid once
  per frame, for every frame of the run."
  50.0)

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private !state
  (atom {:container         nil
         :handle            nil
         :viewport          nil
         :spacer            nil
         :window-rows       nil
         :tally             nil
         :first-refusal     nil
         :last-verification nil
         :advance           {}
         :entry             {}}))

(defn verification
  "`{:writes M :unverified N}` over every frame run taken since the last
  [[boot!]] — warm-up visits included, because a verification is worth
  more the more runs it covers."
  []
  (lane/tally-value (:tally @!state)))

(def populations
  "Which visits each published figure is taken over.

  A def rather than a literal inside [[take-plan!]] so the claim is
  citable: the audit that reopened `rf2-xa8wo` found a decomposition
  published over a larger population than the summary it was described as
  decomposing, and a label nobody can assert is how that recurs."
  {:summary  :measured-visits
   :entry    :measured-visits
   :advance  :all-visits})

;; ---------------------------------------------------------------------------
;; The glass
;; ---------------------------------------------------------------------------

(defn- node-at [sel]
  (some-> (:container @!state) (.querySelector sel)))

(defn observer
  "The per-frame observation, as a closure built ONCE per visit and
  OUTSIDE the run.

  Answers the MODEL index of the first rendered row, or `nil` when the
  spacer holds no row or the attribute is not a number. See the namespace
  docstring §WHERE THE OBSERVATION IS READ for why it is this attribute
  on this element and not `scrollTop`.

  The spacer is captured rather than queried per frame: a
  `querySelector` inside the rendering steps is work this instrument is
  measuring, and the node does not move for the life of a mount."
  []
  (let [spacer (:spacer @!state)]
    (fn []
      (when-some [row (.-firstElementChild spacer)]
        (when-some [a (.getAttribute row "aria-rowindex")]
          (let [n (js/parseInt a 10)]
            (when-not (js/isNaN n) (dec n))))))))

;; ---------------------------------------------------------------------------
;; The frame run — the estimator
;; ---------------------------------------------------------------------------

(defn frames!
  "ONE reading: `frames` consecutive frames, each of which records the
  clock, takes the observation and then does `per-frame!`'s work.

  Answers a promise of

      {:at   [t-1 … t-K]   the clock at the top of each frame's
                           rendering steps, before that frame's work
       :seen [i-1 … i-K]   [[observer]]'s answer in the same frames,
                           read INSIDE the rendering steps and therefore
                           before style, layout and paint}

  from which [[intervals]] takes the `K-1` readings this file publishes.

  ORDER INSIDE THE CALLBACK IS THE INSTRUMENT. The clock is read FIRST,
  before the observation and before any application work, so an interval
  spans one whole frame's cost — the observation, the work, the browser's
  rendering lifecycle and the wait for the next rendering opportunity —
  and nothing of the previous one. The observation is second so that what
  it reads is what the frame is about to paint rather than what this
  frame's own work is still producing.

  THE NEXT FRAME IS REQUESTED AFTER `per-frame!` RETURNS, not before.
  A `requestAnimationFrame` registered anywhere inside a callback targets
  the next frame either way, so the order buys nothing for the schedule —
  but it means a throw from `per-frame!` stops the chain instead of
  leaving a callback scheduled onto a run that has already rejected.

  A throw anywhere inside REJECTS rather than escaping. An exception
  raised in a `requestAnimationFrame` callback would otherwise leave this
  promise unsettled for ever and the run would die on `run.cjs`'s
  sentinel wearing a timeout's face."
  [{:keys [frames observe! per-frame!]}]
  (js/Promise.
    (fn [resolve reject]
      (let [at   (array)
            seen (array)]
        (letfn [(tick []
                  (js/requestAnimationFrame
                    (fn []
                      (try
                        (.push at (lane/now-ms))
                        (.push seen (observe!))
                        (per-frame!)
                        (if (< (.-length at) frames)
                          (tick)
                          (resolve {:at (vec at) :seen (vec seen)}))
                        (catch :default e (reject e))))))]
          (try (tick) (catch :default e (reject e))))))))

(defn intervals
  "The `K-1` gaps between `K` frame timestamps — the reading, and the
  estimand.

  A frame run is not a window: there is no `t0` before it and no paint
  after it, and its length is not a figure. What `U4` is stated over is
  the DISTANCE BETWEEN CONSECUTIVE FRAMES while the application is doing
  a frame's worth of work, which is exactly this vector."
  [at]
  (mapv - (rest at) (butlast at)))

;; ---------------------------------------------------------------------------
;; The verification
;; ---------------------------------------------------------------------------

(defn advanced?
  "Did the window the application rendered move forward across the run?

  The whole of the per-run check, and it is a comparison of two
  observations rather than a threshold on anything: the first frame's
  observed model index against the last's. It can only be true if the
  vendor saw a scroll, recomputed its window and React committed rows
  that were not in the document when the run began."
  [seen]
  (let [a (first seen)
        b (peek seen)]
    (boolean (and (number? a) (number? b) (> b a)))))

(defn rows-gained
  "How many model rows the rendered window moved across the run, or
  `nil` when either end was unobservable."
  [seen]
  (let [a (first seen)
        b (peek seen)]
    (when (and (number? a) (number? b)) (- b a))))

(defn frames-changed
  "How many of the run's `K-1` frame transitions carried a NEW window.

  Published rather than adjudicated. It says how much of a run's frames
  did application work — at one row a frame under a scroll the vendor
  keeps up with, every transition should; a run that advanced on a
  quarter of them advanced for real and was dropping frames, which is a
  finding about the subject and not about the instrument."
  [seen]
  (count (filter (fn [[x y]] (not= x y)) (map vector seen (rest seen)))))

(defn verify
  "Adjudicate one frame run against what its arm PREDICTS the window will
  do.

  `expect-advance?` is the arm's own claim: the scrolling arms say the
  window must move, `:idle-frames` says it must not. Both directions bank
  into the same tally, and a floor run whose window advanced is exactly
  as damning as a scroll run whose window did not — the first means the
  observation reports movement on a page nothing scrolled, and every
  reading this instrument could take would then be verified by a check
  that cannot fail."
  [arm-id expect-advance? seen]
  (let [missing (count (remove number? seen))
        moved?  (advanced? seen)]
    {:verified?   (and (zero? missing) (= expect-advance? moved?))
     :arm         arm-id
     :expected    (if expect-advance? :advance :no-advance)
     :observed    (if moved? :advance :no-advance)
     :first-row   (first seen)
     :last-row    (peek seen)
     :rows-gained (rows-gained seen)
     :unobserved  missing}))

(defn- note-refusal!
  "Keep the FIRST refusal's detail, so a run that dies on `N unverified
  of M` says which arm went and what it saw. `lane/tally` counts and does
  not describe, and a bare count over a two-part check is a diagnosis the
  operator has to reproduce."
  [v]
  (swap! !state update :first-refusal (fn [prev] (or prev v)))
  nil)

(defn- bank!
  "Bank one frame run's verification and its descriptive counts.

  ## WARM-UP RUNS BANK HERE TOO, AND ARE NOT NARROWED OUT AGAIN

  This is called for every visit the schedule plans, warm-up included,
  and `:advance` is published over all of them — `populations` says so.
  That is the opposite choice from `:summary`, and deliberately: a
  verification is worth more the more runs it covers, and a warm-up run
  whose window failed to move is exactly as damning as a measured one.
  It is a count of refusals rather than a distribution, so it decomposes
  nothing and no population question arises."
  [arm-id v seen]
  (let [t (:tally @!state)]
    (when-not (:verified? v) (note-refusal! v))
    (swap! t (fn [{:keys [of bad]}]
               {:of  (inc of)
                :bad (if (:verified? v) bad (inc bad))})))
  ;; The LAST run's verification, kept so [[advance-discrimination!]] can
  ;; adjudicate the run it just took rather than fishing the first refusal
  ;; of the session out of a slot that is written at most once.
  (swap! !state assoc :last-verification v)
  (swap! !state update-in [:advance arm-id]
         (fn [a] (-> (or a {:runs 0 :advanced 0 :rows-gained [] :frames-changed []})
                     (update :runs inc)
                     (update :advanced (if (advanced? seen) inc identity))
                     (update :rows-gained (fn [xs]
                                            (if-some [g (rows-gained seen)] (conj xs g) xs)))
                     (update :frames-changed conj (frames-changed seen)))))
  nil)

;; ---------------------------------------------------------------------------
;; The arms
;; ---------------------------------------------------------------------------

(defn- start-top []
  (* start-row views/row-height))

(defn idle-frames
  "The FLOOR, and the standing negative control.

  No application work at all — the rAF chain, the clock and the
  observation, and nothing else. What it measures is the box's own frame
  grid, which every other row pays and which no application work can
  remove: without it a tail quantile on `:scroll` cannot be read, because
  an interval at the grid and an interval at twice it are different
  findings and the figure alone does not say which.

  It does NOT scroll, and [[verify]] therefore requires its window to
  stand still. See the namespace docstring §4."
  []
  (fn [] nil))

(defn scroller
  "The GESTURE: one row further down the model, every frame.

  `scrollTop` is written and a real `scroll` event is dispatched on the
  viewport the vendor owns. That is the pair `virtualized-dom-cljs-test`
  drives the same screen with, and it is the pair the vendor listens for:
  its listener is a plain DOM one added in a `useEffect`, deliberately
  not React's `onScroll`, so what reaches it is the platform's event
  rather than a synthetic React one.

  NO `flushSync`. The suite wraps its dispatch in one because a scroll is
  a CONTINUOUS-priority event whose `setState` an empty settle does not
  flush, and it must read the DOM on the next line. This driver must not:
  forcing the vendor's update onto the sync lane would replace the
  scheduler whose per-frame behaviour is the subject.

  The platform fires its OWN `scroll` event for a programmatic
  `scrollTop` write, during a later frame's scroll steps. It carries the
  same offset this frame already delivered, so the vendor's `useState`
  bails on it and it costs a comparison. The synthetic dispatch is what
  makes the frame's work happen IN that frame rather than whenever the
  platform gets to it, which is what makes an interval attributable to
  the frame it was measured in.

  The offset is carried in the closure rather than read back off the
  node: a read of `scrollTop` inside the rendering steps is a layout
  question, and the run must not ask one."
  []
  (let [vp  (:viewport @!state)
        top (volatile! (start-top))]
    (fn []
      (set! (.-scrollTop vp) (vswap! top + scroll-step-px))
      (.dispatchEvent vp (js/Event. "scroll" #js {:bubbles true})))))

(defn- busy-wait!
  "Block the main thread for `ms`. A spin and not a `setTimeout`, because
  the control has to occupy the interval this instrument is measuring
  rather than yield it.

  The lane's THIRD copy of these three lines — `slice-echo-clock-app` and
  `slice-broad-clock-app` hold the other two, both private. It is
  transcribed rather than required because it is private there and
  lifting it would edit two files this one has no business in. If a
  FOURTH appears it belongs in `lane`, beside `now-ms`, which is the
  clock it is already spelled against."
  [ms]
  (let [end (+ (lane/now-ms) ms)]
    (loop [] (when (< (lane/now-ms) end) (recur)))))

(defn blocked-scroller
  "[[scroller]] plus [[blocked-ms]] of blocked main thread, every frame.

  WHERE THE COST SITS IS THE CONTROL. The block is taken AFTER the
  frame's scroll has been delivered, so the vendor has seen the event and
  React has scheduled its work before the thread goes away — which is the
  shape of an application whose own per-frame work overruns the budget,
  rather than the shape of an input that was never delivered.

  Its prediction is a FLOOR and needs no model of the application: the
  next frame cannot begin until this block ends. See the namespace
  docstring §THE POSITIVE CONTROL PREDICTS A FLOOR for why that bound is
  exact rather than approximate, and [[control-verdict-floor]] for the
  rule it is adjudicated under."
  []
  (let [scroll! (scroller)]
    (fn []
      (scroll!)
      (busy-wait! blocked-ms))))

(def arms
  "The measured arms, floor first so it leads the schedule."
  [{:id :idle-frames :per-frame idle-frames     :advance? false}
   {:id :scroll      :per-frame scroller        :advance? true}
   {:id :ctl-blocked :per-frame blocked-scroller :advance? true :control? true}])

;; ---------------------------------------------------------------------------
;; Sampling
;; ---------------------------------------------------------------------------

(defn prepare!
  "Put the page back where every visit starts, and settle it. Answers a
  promise, and every line of it runs OUTSIDE the run.

  Four steps, and each answers something that has bitten this screen
  before.

  1. Scroll back to [[start-row]], so a visit's gesture always traverses
     the same rows and the arms are compared on one page rather than on
     wherever the previous visit's scroll ended.

  2. Assert `scrollTop` STUCK. A `scrollTop` assignment to an element the
     engine does not consider scrollable is silently ignored, and every
     interval below would then be the box's frame grid with a no-op in
     it. `virtualized-dom-cljs-test`'s `scroll-to!` makes the same
     assertion first and for the same reason.

  3. Settle THREE times, on `slice-echo-clock-app`'s `after-paint`. The
     vendor's `setState` is continuous-priority and the window report is
     a PASSIVE effect, so the commit that moves the window and the commit
     that follows its dispatch are not the same frame — that pair is what
     `virtualized-dom-cljs-test` spends its two settles on. This driver
     needs one more than the suite does, and the reason is the one thing
     it deliberately does NOT do: the suite forces the vendor's update
     onto the sync lane with `flushSync` before it settles at all, so its
     first commit has already happened, while here that commit is the
     scheduler's to place. Three frames outside the window is about 50 ms
     a visit against a run of minutes. The settles also align every run
     to the frame grid: a frame-bounded reading is
     PREDECESSOR-DEPENDENT by construction, and starting each run in the
     first task after a paint makes the phase a constant of the
     instrument rather than a property of whatever ran before —
     [[re-frame.bench.hicasso.slice-echo-clock-app/measure-one!]] carries
     the incident that established that.

  4. Assert the reset LANDED, against `vendor/window-from`'s own
     arithmetic rather than against a number typed here. A visit that
     began on a stale window would still verify — the scrolling arms
     advance either way — while its early frames were catching up rather
     than holding a steady gesture, and nothing downstream would say so."
  []
  (let [vp   (:viewport @!state)
        want (start-top)]
    (set! (.-scrollTop vp) want)
    (when-not (== want (.-scrollTop vp))
      (throw (ex-info (str "the ledger's viewport did not scroll to " want
                           "px — the instrument, not the screen: an element the "
                           "engine does not consider scrollable ignores the "
                           "assignment silently, and every interval this run "
                           "took would be the frame grid with a no-op in it")
                      {:rf.error/id ::viewport-not-scrollable
                       :want        want
                       :got         (.-scrollTop vp)})))
    (.dispatchEvent vp (js/Event. "scroll" #js {:bubbles true}))
    (-> (echo/after-paint)
        (.then (fn [_] (echo/after-paint)))
        (.then (fn [_] (echo/after-paint)))
        (.then (fn [_]
                 (let [[from _] (vendor/window-from want
                                                    {:row-height      views/row-height
                                                     :viewport-height views/viewport-height
                                                     :overscan        views/overscan
                                                     :total           total})
                       seen     ((observer))]
                   (when-not (= from seen)
                     (throw (ex-info
                              (str "the reset scrolled the viewport to " want "px but the "
                                   "rendered window did not follow it: the geometry puts "
                                   "row " from " at the top and the page shows " (pr-str seen)
                                   ". A visit starting on a stale window measures a gesture "
                                   "catching up rather than one being held")
                              {:rf.error/id ::reset-not-settled
                               :want-first  from
                               :saw-first   seen
                               :scroll-top  want})))
                   nil))))))

(defn measure-one!
  "One visit of `arm`, as a promise of its `K-1` intervals.

  The observation and the arm's per-frame work are both built OUTSIDE the
  run — the spacer resolved, the closure minted, the gesture's starting
  offset fixed — so nothing but the clock, one property read and the
  frame's own work is inside it.

  It answers a VECTOR and not a number, which is why this driver cannot
  ride `lane/rounds-async!` and walks `lane/visit-plan` itself. See the
  namespace docstring §THE SCHEDULE IS THE LANE'S, BUT THE LOOP CANNOT
  BE."
  [{:keys [id per-frame advance?]}]
  (.then (prepare!)
         (fn [_]
           (let [observe! (observer)
                 work!    (per-frame)]
             (.then (frames! {:frames     frames-per-run
                              :observe!   observe!
                              :per-frame! work!})
                    (fn [{:keys [at seen]}]
                      (bank! id (verify id advance? seen) seen)
                      (intervals at)))))))

;; ---------------------------------------------------------------------------
;; The negative and positive halves of the boot discrimination
;; ---------------------------------------------------------------------------

(defn- adjudicate-discrimination!
  "Read the verification of the run that just finished, and throw unless
  it went the way its half of [[advance-discrimination!]] requires.

  It BRANCHES on what actually failed rather than reporting the caller's
  message whatever happened. A run whose window could not be OBSERVED —
  no row under the spacer, or an `aria-rowindex` that is not a number —
  has failed for a reason that has nothing to do with the direction the
  caller was testing, and answering it with `the check does not
  discriminate` would send an operator to the wrong place."
  [error-id message]
  (let [v (:last-verification @!state)]
    (cond
      (nil? v)
      (throw (ex-info (str "the discrimination run banked no verification at all, so "
                           "there is nothing to adjudicate and nothing may be measured")
                      {:rf.error/id ::no-verification}))

      (pos? (:unobserved v))
      (throw (ex-info (str "the rendered window could not be read on " (:unobserved v)
                           " of the run's " frames-per-run " frames: the spacer held no "
                           "row, or its aria-rowindex was not a number. The observation "
                           "is the whole of this instrument's verification, so a frame it "
                           "cannot read is a frame whose reading means nothing")
                      {:rf.error/id ::window-unobservable
                       :observed    v}))

      (not (:verified? v))
      (throw (ex-info message {:rf.error/id error-id :observed v}))

      :else v)))

(defn advance-discrimination!
  "THE SABOTAGE, BUILT IN, AND IT RUNS IN BOTH DIRECTIONS. Take one idle
  frame run and one scrolling frame run before anything is measured, and
  require the first to REFUSE and the second to VERIFY.

  Answers a promise of both observations, and REJECTS if either went the
  wrong way.

  ## Why both directions, and why here rather than only in a suite

  The claim this instrument makes is that its intervals are frames under
  a REAL SCROLL of the ledger's own list. Two different failures would
  leave that claim standing while making it false, and they are not
  caught by the same check.

  - **A check that cannot refuse.** If an advance were reported on a page
    nothing scrolled, every measured run would verify and the tally would
    be decorative. The idle half asks that, and `:idle-frames` then goes
    on asking it once per visit for the whole run.
  - **A scroll that never lands.** If the notification did not reach the
    vendor, every arm would publish the box's own frame grid and the
    scrolling arms would fail verification 200 times over four minutes
    before saying so. `ledger.virtualized-dom-cljs-test` records two runs
    of its own that met exactly this: `scrollTop` set, the `scrollTop`
    assertion passing, and the window unmoved. The scrolling half turns
    that into a refusal at boot, at a cost of one gesture.

  Both halves take a REAL run through [[frames!]], on the real page, with
  the same observation the arms use — the only difference is which answer
  is required.

  ## It leaves the page as it found it

  [[prepare!]] runs at the head of every visit and resets the offset, so
  the gesture this function performs is undone before the first warm-up
  run reads anything."
  []
  (-> (measure-one! {:id :ctl-discrimination-idle :per-frame idle-frames :advance? false})
      (.then (fn [_]
               (adjudicate-discrimination!
                 ::advance-not-discriminating
                 (str "the advance check does not discriminate: a run in which NOTHING "
                      "SCROLLED still reported that the rendered window moved forward. "
                      "Every reading this instrument could take would be verified by a "
                      "check that cannot fail, so nothing may be measured"))))
      (.then (fn [_] (measure-one! {:id :ctl-discrimination-scroll
                                    :per-frame scroller
                                    :advance? true})))
      (.then (fn [_]
               (adjudicate-discrimination!
                 ::scroll-not-delivered
                 (str "a full gesture reached the page and the rendered window did not "
                      "move: " frames-per-run " frames each writing scrollTop and "
                      "dispatching a real scroll event on the viewport the vendor owns, "
                      "and the first rendered row is where it started. The notification "
                      "is not reaching the virtualizer, so every arm below would publish "
                      "this box's frame grid and nothing may be measured"))))
      (.then (fn [_]
               ;; The two discrimination runs banked into the same tally
               ;; the measured visits use, and they are NOT visits. Reset
               ;; it here rather than teaching `bank!` about them: a
               ;; counter the arms share with a control is a counter whose
               ;; denominator a reader has to reconstruct.
               (swap! !state assoc
                      :tally             (lane/tally)
                      :first-refusal     nil
                      :last-verification nil
                      :advance           {})
               nil))))

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

(defn boot!
  "Mount the ledger into `container` on `frame-id`, and answer a promise
  of the mounted handle.

  It goes through `h/mount!` — the application's own root door — with the
  application's own view and the `initial-events` `ledger.app` publishes,
  parameterised by the size the application itself defaults to. Nothing
  here reaches under `re-frame.hicasso.impl.*`, nothing rebuilds a view,
  and no scroll is simulated by dispatching an application event: every
  reading starts at a real `scroll` on a node the vendor rendered.

  ## What it refuses rather than discovers

  1. THE SCREEN IS THERE. No viewport and no spacer means no gesture and
     no observation; a run that discovered that inside a rendering step
     would report it as a null reading.
  2. THE GESTURE FITS THE MODEL, at both ends, derived from
     `vendor/window-from` rather than from arithmetic repeated here.
     `window-from` clamps `from` at zero and `to` at the last row, and a
     clamped window stands still — which would make an honest verification
     failure out of a knob that is simply set too near an edge.

  INSTALLING THE ADAPTER AND LEAVING REACT'S `act` ENVIRONMENT ARE NOT
  DONE HERE. Both are process-wide and belong to whoever owns the
  process; [[-main]] owns the bench page and does both."
  [container frame-id]
  (let [handle (h/mount! container
                         {:frame          frame-id
                          :initial-events (ledger-app/initial-events total)}
                         [views/ledger {}])
        geom   {:row-height      views/row-height
                :viewport-height views/viewport-height
                :overscan        views/overscan
                :total           total}]
    (swap! !state assoc
           :container         container
           :handle            handle
           :viewport          nil
           :spacer            nil
           :tally             (lane/tally)
           :first-refusal     nil
           :last-verification nil
           :advance           {}
           :entry             {})
    (-> (echo/after-paint)
        (.then (fn [_] (echo/after-paint)))
        (.then (fn [_]
                 (let [vp     (node-at ".ledger-viewport")
                       spacer (node-at ".ledger-spacer")]
                   (when (or (nil? vp) (nil? spacer))
                     (throw (ex-info (str "the ledger mounted but its virtualizer did not: "
                                          ".ledger-viewport and .ledger-spacer are the scroll "
                                          "container and the row host, and there is no "
                                          "gesture without them")
                                     {:rf.error/id ::viewport-absent
                                      :viewport?   (some? vp)
                                      :spacer?     (some? spacer)})))
                   (let [[from at-start] (vendor/window-from (start-top) geom)
                         last-top       (+ (start-top) (* frames-per-run scroll-step-px))
                         [_ to]         (vendor/window-from last-top geom)]
                     (when-not (pos? from)
                       (throw (ex-info (str "start-row " start-row " puts the window's first "
                                            "row at " from ", on window-from's (max 0 …) "
                                            "clamp. A clamped window stands still while the "
                                            "gesture moves, which would refuse the scrolling "
                                            "arms for a knob's reason")
                                       {:rf.error/id ::gesture-clamped-at-start
                                        :start-row   start-row
                                        :first-row   from})))
                     (when-not (< to (dec total))
                       (throw (ex-info (str "the gesture runs off the end of the model: "
                                            frames-per-run " frames from row " start-row
                                            " at " scroll-step-px "px reaches window end "
                                            to " against a model of " total)
                                       {:rf.error/id ::gesture-clamped-at-end
                                        :start-row   start-row
                                        :frames      frames-per-run
                                        :last-row    to
                                        :total       total})))
                     (swap! !state assoc
                            :viewport vp
                            :spacer spacer
                            :window-rows (inc (- at-start from)))))
                 handle)))))

(defn teardown!
  "Take this root down and drop the container. Exposed for a caller that
  mounts and unmounts around each of its rows."
  []
  (let [{:keys [container handle]} @!state]
    (when handle (h/unmount! handle))
    (when (and container (.-parentNode container))
      (.removeChild (.-parentNode container) container))
    (swap! !state assoc :container nil :handle nil :viewport nil :spacer nil)
    nil))

;; ---------------------------------------------------------------------------
;; The positive control's rule
;; ---------------------------------------------------------------------------

(defn control-verdict-floor
  "Adjudicate a positive control whose prediction is a FLOOR: every
  round's measured minimum at or above `predicted`.

  `per-round` is ONE MEASURED VALUE PER ROUND — here the smallest frame
  interval `:ctl-blocked` produced in that round. Round by round and not
  in aggregate, for the reason `lane/control-verdict-strict` gives about
  its own band: a cross-round minimum cannot tell a control that held
  every round from one that held on average, and a good round must not be
  allowed to vouch for a bad one.

  ## Why neither of the lane's two rules serves

  `lane/control-verdict` asks whether a measured RANGE overlaps a
  ±`slack` band, and `lane/control-verdict-strict` asks whether every
  round sits INSIDE one. Both need an upper edge, and this prediction has
  none: a blocked frame that ran long is a slow frame and not a control
  failure. Widening a band until it covered that would be inventing a
  tolerance where the arithmetic supplies a bound, and this programme has
  refused that shape repeatedly.

  **So there is no slack here at all, and none is needed.** The
  prediction is exact in the reading domain — see the namespace docstring
  §THE POSITIVE CONTROL PREDICTS A FLOOR — because every term is a
  reading from one monotone clock.

  ## `:stated?`, carried over rather than reinvented

  A floor of zero or less is cleared by any reading whatever, and a
  control with no rounds is the same thing said with no data.
  `lane/control-verdict-strict` refuses both and prices the incident that
  put the rule there: a walk profile shipped a control whose own
  prediction had gone vacuous and reported that it saw what it never
  predicted.

  ## `:versus-floor` is CONTEXT and not a line

  `predicted` against `floor-p50` — the `:idle-frames` arm's own median
  interval — says how many of this box's frames the injection spans. A
  reader needs it to know whether the floor could have been cleared
  without the injection. **Nothing is adjudicated against it**, `:ok?`
  would read the same without it, and it is not a threshold on anything.

  Answers `{:rule :every-round-floor :predicted :per-round :measured
  :stated? :versus-floor :below :ok? :why}`. `:below` NAMES each round
  that missed and by how much, because an operator told only `FAILED`
  goes looking at the arms; `:per-round` is carried into the record so a
  later reader can re-adjudicate the run WITHOUT re-running the window."
  [predicted per-round floor-p50]
  (let [vs      (vec per-round)
        stated? (boolean (and (pos? predicted) (seq vs)))
        below   (if-not stated?
                  []
                  (vec (keep-indexed
                         (fn [i v]
                           (when (< v predicted)
                             {:round    (inc i)
                              :measured (lane/round4 v)
                              :short-by (lane/round4 (- predicted v))}))
                         vs)))
        ok?     (and stated? (empty? below))
        floor   (str "predicted floor " (.toFixed predicted 3) " ms")]
    {:rule         :every-round-floor
     :predicted    predicted
     :per-round    vs
     :measured     (lane/summarise vs)
     :stated?      stated?
     :versus-floor (when (and (number? floor-p50) (pos? floor-p50))
                     (lane/round4 (/ predicted floor-p50)))
     :below        below
     :ok?          ok?
     :why          (cond
                     (not (pos? predicted))
                     (str "REFUSED — the control states no prediction ("
                          (.toFixed (double predicted) 3) " ms). A floor built on it is "
                          "cleared by any reading whatever, so nothing here is a control "
                          "and no figure in this run is reportable")

                     (empty? vs)
                     (str floor " — REFUSED: no rounds were adjudicated. A control with "
                          "no data is not a control that passed")

                     ok?
                     (str floor ", and the smallest interval in all " (count vs)
                          " rounds sits at or above it — EVERY round, not merely the "
                          "pooled minimum")

                     :else
                     (str floor ", and " (count below) " of " (count vs)
                          " rounds produced an interval BELOW it ("
                          (str/join ", " (map (fn [{:keys [round measured]}]
                                                (str "round " round " " (.toFixed measured 3)))
                                              below))
                          ") — a frame whose main thread was blocked for the whole of the "
                          "prediction cannot be followed sooner than that, so the "
                          "instrument is not reading the frames it thinks it is and no "
                          "figure in this run is reportable"))}))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn- fresh-readings
  "One empty reading vector per arm, per round. `lane/rounds!`'s own
  shape, which is private there; the readings map this driver builds has
  to be the same one `lane/normalise` and the summarisers expect."
  []
  (atom (vec (repeat rounds (zipmap (map :id arms) (repeat []))))))

(defn- readings-by-arm
  "Every measured interval of every round, pooled per arm."
  [readings]
  (reduce (fn [m round]
            (reduce-kv (fn [m id xs] (update m id (fnil into []) xs)) m round))
          {}
          readings))

(defn run-schedule!
  "Walk `lane/visit-plan` and answer a promise of
  `{:readings :samples}` — the same shape `lane/rounds!` and
  `lane/rounds-async!` answer, so everything downstream of it is the
  lane's.

  THE SCHEDULE IS NOT RESTATED HERE. `lane/visit-plan` produces the
  visits, in execution order, with the reflecting `lane/slot-order`
  rotation, the warm-up boundary and the round boundary all decided over
  there. What this loop adds is the one thing the lane's own loops cannot
  do: bank a VECTOR per visit.

  `lane/observe!` is called for warm-up visits and `lane/collect!` for
  measured ones, exactly as `lane/rounds!`'s own `bank-visit!` does. A
  warm-up visit is still a PREDECESSOR — skipping it would leave the
  first measured visit of a block tagged with whatever ran before the
  warm-up, which `lane/observe!`'s docstring prices at length.

  ONE GUARD SAMPLE PER VISIT, and it is that visit's MEDIAN interval. The
  guard stratifies by `:predecessor` and `:position` to catch arm-order
  contamination and run-level ramps, and both of those are properties of
  the VISIT — a run of thirty frames is what followed the previous arm,
  and its thirtieth frame did not follow anything else. Banking every
  interval as its own guard sample would tag twenty-nine of them with a
  predecessor that is really the same run's own previous frame."
  []
  (let [coll     (lane/sample-collector)
        readings (fresh-readings)]
    (.then
      (lane/chain nil (lane/visit-plan arms sampling rounds)
                  (fn [_ {:keys [round arm measured?]}]
                    (.then (measure-one! arm)
                           (fn [xs]
                             (if measured?
                               (do (lane/collect! coll (name (:id arm))
                                                  (:p50 (lane/summarise xs)))
                                   (swap! readings update-in [round (:id arm)] into xs)
                                   (swap! !state update-in [:entry (:id arm)]
                                          (fnil conj []) (first xs)))
                               (lane/observe! coll (name (:id arm))))
                             nil))))
      (fn [_] {:readings @readings :samples (:samples @coll)}))))

(defn control-per-round
  "One adjudicated figure per round: the SMALLEST frame interval
  `:ctl-blocked` produced in that round.

  The minimum and not a median, because the prediction is a floor and a
  floor is a claim about the smallest reading. A median above the floor
  with a minimum below it is a control that failed, and an aggregate that
  reported the median would call it a pass."
  [readings]
  (mapv (fn [round] (lane/round4 (:min (lane/summarise (get round :ctl-blocked)))))
        readings))

(defn advance-report
  "`:advance`, per arm: how many runs each arm took, how many of them
  moved the rendered window, and the distributions of how far it moved
  and of how many frame transitions carried a new window.

  Descriptive, over every visit including warm-up. `populations` says so.
  Nothing here is adjudicated — the tally is what refuses a run, and it
  refuses on the two-valued question [[verify]] asks rather than on any
  of these counts."
  [advance]
  (into {}
        ;; `a` and not a `:keys` destructuring: two of its keys are
        ;; spelled exactly like vars above, and a local that shadows a var
        ;; is a diff nobody reads twice.
        (map (fn [[id a]]
               [id {:runs           (:runs a)
                    :advanced       (:advanced a)
                    :rows-gained    (lane/summarise (filterv number? (:rows-gained a)))
                    :frames-changed (lane/summarise (:frames-changed a))}]))
        advance))

(defn take-plan!
  "Take the plan, publish the record, and adjudicate the two
  instrument-integrity verdicts. Answers a promise.

  Order matters and is the lane's: the record is published BEFORE
  `assert-verified!` can throw, so an operator reading a failed run has
  the evidence on the console rather than only the refusal."
  []
  (.then
    (run-schedule!)
    (fn [{:keys [readings samples]}]
      (let [by-arm    (readings-by-arm readings)
            floor-p50 (:p50 (lane/summarise (get by-arm :idle-frames)))
            control   (control-verdict-floor blocked-ms
                                             (control-per-round readings)
                                             floor-p50)
            verdict   (lane/guard! samples "ledger per-frame interval")
            visits    (* (+ (:warmup sampling) (:samples sampling)) rounds)]
        (lane/record! :ledger-frame
                      {:window      :frame-interval
                       :population  {:app      're-frame.hicasso.examples.ledger
                                     :views    're-frame.hicasso.examples.ledger.views
                                     :vendor   're-frame.hicasso.examples.ledger.vendor
                                     ;; DERIVED from the application's own geometry and
                                     ;; seed, never transcribed: a population pin that
                                     ;; restated these integers would be a second source
                                     ;; for them and the first thing to go stale.
                                     :total    total
                                     :geometry {:row-height      views/row-height
                                                :viewport-height views/viewport-height
                                                :overscan        views/overscan
                                                :window-rows     (:window-rows @!state)}
                                     :gesture  {:start-row      start-row
                                                :step-px        scroll-step-px
                                                :rows-per-frame (/ scroll-step-px
                                                                   views/row-height)
                                                :frames         frames-per-run}}
                       :schedule    (assoc sampling
                                           :rounds              rounds
                                           :frames-per-run      frames-per-run
                                           :intervals-per-visit (dec frames-per-run)
                                           :visits-per-arm      visits
                                           :measured-per-arm    (* (:samples sampling) rounds)
                                           :intervals-per-arm   (* (:samples sampling) rounds
                                                                   (dec frames-per-run)))
                       :populations populations
                       :summary     (into {} (map (fn [[id xs]] [id (lane/summarise xs)])) by-arm)
                       :entry       (into {} (map (fn [[id xs]] [id (lane/summarise xs)]))
                                          (:entry @!state))
                       :advance     (advance-report (:advance @!state))
                       :control     control
                       :guard       (select-keys verdict [:refuse? :contaminated?
                                                          :unchecked? :tolerance])
                       :verification (cond-> (lane/tally-value (:tally @!state))
                                       (:first-refusal @!state)
                                       (assoc :first-refusal (:first-refusal @!state)))
                       :runtime     (lane/runtime-label)
                       :note        (str "No line is applied to any figure above. U4's frame "
                                         "budget is read against this instrument in its own "
                                         "quiet-box window, not here, and this driver moves "
                                         "no ledger cell. Read :summary on :idle-frames "
                                         "before reading it on :scroll: an interval at this "
                                         "box's own frame grid and an interval at twice it "
                                         "are different findings and the figure alone does "
                                         "not say which. The gesture's realised velocity is "
                                         ":gesture/:step-px divided by :scroll's :p50.")})
        (set! (.-HICASSO_GUARD_REFUSED js/window) (boolean (:refuse? verdict)))
        (set! (.-HICASSO_CONTROL_FAILED js/window) (not (:ok? control)))
        (lane/assert-verified! (:tally @!state) "ledger per-frame interval")
        nil))))

(defn ^:export -main []
  (rf/init! uix-adapter/adapter)
  (lane/leave-act-environment!)
  (if-not (lane/self-test!)
    (lane/fail! (str "the arm-order self-test failed — the copy of the schedule "
                     "rule this app is about to rely on no longer behaves like "
                     "the one the .cjs drivers use, so nothing may be measured"))
    (-> (boot! (or (js/document.getElementById "app") (lane/fresh-container!))
               ::frame)
        ;; Both halves of the discrimination run BEFORE the first warm-up
        ;; visit and their throws travel the same `.catch` as any other
        ;; failure, so a run whose check cannot refuse — or whose scroll
        ;; never reaches the virtualizer — dies here rather than
        ;; publishing a record nobody should read.
        (.then (fn [_] (advance-discrimination!)))
        (.then (fn [_] (take-plan!)))
        (.catch (fn [e] (lane/fail! (lane/describe-throw "ledger-frame-clock-app" e))))
        (.then (fn [_] (lane/done!)))))
  nil)
