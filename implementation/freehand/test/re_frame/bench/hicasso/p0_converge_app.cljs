(ns re-frame.bench.hicasso.p0-converge-app
  "THE CONVERGED P0 CLOCK TABLE — Reagent-on-subs AND UIx-on-subs on ONE
  witness set, so the bar and the red-zones can be read down one column
  (rf2-a4x1o; EP-0038 P0; the standard is rf2-2rtt6.1).

  ## What was wrong, precisely

  rf2-2rtt6.2 published the bar's DENOMINATOR — Reagent reading re-frame2
  subscriptions — on a 901-element `M1` list and a 51-element `M2` form,
  both reading `[:p0/cell i]`. rf2-2rtt6.4 published the RED-ZONE
  THRESHOLDS — the measured UIx ratio per witness family, under RULING 1
  on rf2-2rtt6.1 — on a 1,203-element `W1` list reading `[:p0/row i]`, a
  51-element `W3` form reading `[:p0/field i]`, and a 301-element grid.
  Its branch was designed before rf2-2rtt6.2 merged and it said so on its
  own page.

  Each of rf2-2rtt6.4's thresholds is sound AS A THRESHOLD: a ratio
  between two arms measured in one run, on one page, through one sub
  graph, in both orders. What is not sound is the TABLE, because a
  red-zone whose page differs from the bar row's page cannot be applied to
  a candidate measured on either one. This entry re-measures the frontier
  arm on rf2-2rtt6.2's witnesses so that every row of the clock table
  describes the same page.

  ## Which witness set, and why that one

  rf2-2rtt6.2's. Four reasons, in order of weight:

  1. **The bar's denominator defines the witness set by construction.**
     HD-012 states the ship number as `<= 1.0x Reagent, like-for-like`.
     The red-zone is a second threshold layered onto the same rows — a
     rule about where a candidate sits relative to UIx — so it has to be
     derived on the pages the denominator lives on, not the other way
     round.
  2. **rf2-2rtt6.2 is on main and owns the measurement lane.** HD-017
     gives `:hicasso-bench` and `run.cjs` to that arm; rf2-2rtt6.4's tree
     rides `:freehand-release`'s compiler settings through a
     `--config-merge` precisely because the lane had not landed. Running
     the frontier arm on the lane retires that workaround.
  3. **rf2-2rtt6.4 nominated it.** Its own `Open items` names
     rf2-2rtt6.2's set as the convergence target and names its `U-broad`
     row as the closest existing correspondence.
  4. **rf2-2rtt6.2's witnesses already carry the control and the lower
     bound.** `:ctl-2x` is an in-plan positive control at exactly twice
     the boundaries, and the `:reagent-ratom` arm is a published labelled
     lower bound on the same page. Moving to the other set would strand
     both.

  ## What runs

  Four rows, two segments each. The rows are rf2-2rtt6.2's three plus one:

  | row | witness | why |
  |---|---|---|
  | `mount-M1` | 901 el, 300 boundaries, `[:p0/cell i]` | the bar row |
  | `mount-M2` | 51 el, 12 fields, `[:p0/cell i]` | DIAGNOSTIC, per rf2-2rtt6.2 |
  | `bulk-broad` | the M1 page, one commit ALL 300 boundaries read | the bar row |
  | `bulk-narrow` | the M1 page, `narrow-batch-k` batched commits, each of which exactly ONE boundary reads, all in one timed window | the converged counterpart of rf2-2rtt6.4's `U-narrow` — the localisation row, and the one row where that arm found UIx materially behind. It has NO counterpart in rf2-2rtt6.2, so it is a NEW row on rf2-2rtt6.2's witness, labelled as such rather than presented as a re-measurement of something |

  ## `?ratom=on` — the reactive leg's SECOND AUTHOR (rf2-2rtt6.21)

  rf2-2rtt6.2's headline 1 is that reading re-frame2 subscriptions rather
  than a bare cursor costs Reagent ~1.22x on mount and ~2.01x on a broad
  commit. rf2-2rtt6.17 measured it THREE TIMES — and all three are re-runs
  of the SAME `:reagent-ratom` arm, which bounds the instrument's
  run-to-run noise and cannot bound a systematic error in how that arm is
  written. That is repetition, not replication.

  This page was already a genuine second implementation of the
  DENOMINATOR — a different app namespace, a different schedule, and
  `reagent-subs / floor` ranges overlapping rf2-2rtt6.2's on all three
  shared rows — and it carried no `:reagent-ratom` arm at all, so it could
  not touch the leg. `?ratom=on` puts that arm in the REAGENT SEGMENT of
  the two rows the headline names, beside this page's own `:reagent-subs`
  arm, in the same round of the same run. The leg is then formed a second
  way, by a second author, on a second page.

  | | |
  |---|---|
  | rows | `M1` and `broad`. `M2`'s leg is quantum-limited on the first author's own page (all five rounds read exactly 1.0000 on one run — the quantum wearing a tie's clothes) and `M2` is the one row whose mount budget already refuses; `narrow` has no first-author counterpart to corroborate |
  | segment | the REAGENT segment only. The leg's two terms are both Reagent arms, so it is formed INSIDE one segment and the seam never enters it; putting a Reagent arm in the UIx segment would buy the leg nothing and would put Reagent's reaction machinery on the page whose whole point is UIx's |
  | default | OFF. With the arm in the plan the Reagent segment runs FOUR arms against the UIx segment's three, so the page's mount budget and the interleave both differ from the schedule rf2-6i0i2's ten-run ensemble was measured under. Default-off keeps this instrument's published four rows reproducible at their own schedule; `?ratom=on` is a DIFFERENT plan and says so on every record it produces |

  A run with the arm on still publishes its cross-segment red-zone,
  because it is computed from the same slices and hiding it would be worse
  than qualifying it — but the record carries `:ratom-arm? true` and a
  comparability note, and that figure may NOT be quoted as a threshold.

  THE FLAG IS GLOBAL AND THE ARM IS NOT. `?ratom=on` with no row
  restriction reaches `M2` and `narrow` as well, and those two rows keep
  their published three-arm plan whatever the flag says. So every fact
  downstream of the arm — the leg, the ratom floor, `:ratom-arm?`, the
  comparability note — is derived from the SLICE by [[ratom-leg]] and
  never from the flag. A row that did not run the arm publishes no leg,
  rather than dividing by a denominator nobody measured.

  ## Three arms a segment, and why not two

  rf2-ouwh8: `lane/slot-order` rotated and then REFLECTED, and at k=2 those
  two operations cancelled — `[0 1]` rotates to `[1 0]` and reflects back
  to `[0 1]`, at every sample index, for ever. A two-arm plan therefore ran
  in ONE order and `both orders` was a claim it could not support. This
  entry was designed around that and never forms a two-arm plan: `:ctl-2x`
  is measured INSIDE the interleave as rf2-2rtt6.2 measures it, so every
  segment carries three arms.

  THE DEFECT HAS SINCE BEEN REPAIRED (PR #7267): at k=2 the reflection is
  dropped, and the plain rotation alternates, which is every order two arms
  have. The three-arm plan is KEPT anyway — it is what every published row
  on this page was measured under, and the schedule an arm runs is part of
  its window. The boot assertion therefore checks the REPAIRED property
  rather than the defect ([[slot-order-varies-at-2?]], which records what
  went wrong when that distinction was missed), and the run still refuses
  to measure if it does not hold.

  ## Two segments, and what makes the seam legitimate

  `install-adapter!` is once per process (Spec 006 SS Single adapter per
  process), so the Reagent and UIx arms cannot be interleaved inside one
  round. Each round runs two SEGMENTS — destroy the adapter, install the
  other, re-register, re-seed — with the FLOOR IN BOTH. The floor holds no
  re-frame state, reads no subscription and is untouched by which adapter
  is installed, so a UIx-over-Reagent figure is a ratio of two
  floor-normalised ratios, and the seam cancels PROVIDED the segment
  drift is common-mode. That cancellation is PUBLISHED rather than
  assumed: the floor's own p50 in the UIx segment
  over its p50 in the Reagent segment is reported per round per row, and
  a reader can see how far it moves and how much less the derived ratio
  moves.

  Segment order alternates with the round, so the cross-segment figure is
  a both-orders result rather than a single-order one however many rounds
  it averages. WHICH segment starts round 0 is a per-run parameter
  (`?start=reagent` or `?start=uix`, default reagent — the schedule every
  five-round run used), because rf2-6i0i2's audit is right that a fixed
  start confounds segment order with temporal position: in every earlier
  run the Reagent-first rounds were also rounds 0, 2 and 4. Independently
  launched runs counterbalance the start, and the inference about the
  order effect is made ACROSS those runs, never by pooling one run's four
  correlated rows as if they were independent trials.

  ## And the order is ADJUDICATED, not merely run (rf2-a4x1o)

  Running both orders is not the same as testing whether the answer
  depends on them. `lane/guard!` adjudicates arms INSIDE a segment; the
  red-zone is a ratio ACROSS the seam and no guard on this page ever
  looked at it by segment order, while `:segment-seam-control` recorded
  the floor's drift and stopped there. [[segment-order-verdict]] asks the
  question the labels were already carrying, and splits the answer in two
  because the two halves fail separately: DIRECTION is fail-closed (two
  strata pointing opposite ways across 1.0 refuse the row), and MAGNITUDE
  is publishable only when the strata OVERLAP.

  It looked like it had found something, AND THAT READING IS WITHDRAWN.
  Across three independent five-round runs the cross-segment figure read
  HIGHER when the Reagent segment ran first in 11 of 12 row-runs, which
  this comment once called a one-sided binomial p = 0.0032. IT IS NOT
  TWELVE TRIALS: the four rows inside a run share a machine, a heap and a
  collector, so the honest unit is the RUN and the sample was n = 3. And
  every one of those runs started with Reagent, so segment order was
  welded to round parity and a page that simply gets slower as it runs
  produces the identical partition. On the counterbalanced ten-run
  ensemble the effect is not established on any row, and counted the old
  way it reads 23 of 40 (rf2-6i0i2). The QUALITATIVE suspicion was worth
  chasing; the p-value was not warranted.

  What survives is the DESIGN repair, which never needed the effect: five
  rounds cannot balance two orders, so the raw mean over-weights whichever
  order got the extra round — which is why `:order-balanced-mean`, the
  mean of the two stratum means, is published on every row beside it, and
  why [[rounds]] is even and the start is a counterbalanced per-run
  parameter. The order question is asked across independently launched
  runs rather than inside one. The studio page carries the ten-run table,
  the withdrawal, and the consequence for `1.2301`.

  ## One row per page

  The first cut ran all four rows in one page and THE ARM-ORDER GUARD
  REFUSED IT (exit 2) on two independent faults, both the arm's:
  `M1/uix-subs/floor` — an arm that hand-builds React elements and cannot
  change — read LAST-THIRD 2.1739x FIRST-THIRD with disjoint ranges while
  every other arm on the page climbed with it, which is the accumulation
  rf2-2rtt6.4 recorded and repaired with one round per page; and
  `narrow/reagent-subs/ctl-2x` was refused on a two-sample stratum
  labelled with an arm from a DIFFERENT ROW, an artefact of the shared
  collector advancing its predecessor pointer only for recorded samples.
  The repairs are one ROW per page (a quarter of the work in a page, and
  no cross-row adjacency to mislabel) and [[mark-predecessor!]] (the
  warm-up advances the pointer without banking a sample, so every recorded
  sample carries its real predecessor). The tolerance was not touched.

  ## What this entry does NOT measure

  Retained heap. The heap red-zones on rf2-2rtt6.1 come from two
  independent witness families already — rf2-2rtt6.5's 1,200-boundary
  reads ladder and rf2-2rtt6.4's list/grid pair — and they agree: 2.262x
  (list) and 2.254x (grid) on markup densities that differ by 4x, against
  a per-read ratio of 3,550/942 on a third shape. Retained bytes per
  subscribing boundary is a property of the BOUNDARY; the clock is not,
  because a page's element count decides what fraction of the window is
  React's own work. The heap axis was therefore already converged in
  substance and the clock axis was not, which is why exactly one arm is
  re-run here.

  Driven by `p0_converge_run.cjs`, which sets this namespace as the
  `:hicasso-bench` build's `:init-fn` through rf2-2rtt6.2's own driver and
  touches no build id and no `implementation/shadow-cljs.edn`."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.p0-reagent-views :as v]
            [re-frame.bench.hicasso.p0-uix-views :as ux]
            [re-frame.bench.order-guard :as guard]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [uix.dom :as uix-dom]))

(def ^:private rounds
  "SIX, and EVEN on purpose (rf2-6i0i2). Five rounds cannot balance two
  segment orders: the alternation split 3:2, the raw mean over-weighted
  whichever order got the extra round, and the per-row disjointness test
  ran at a 20% null rate (2 of C(5,2) = 10 exchangeable assignments). At
  six the split is 3:3, the raw mean and `:order-balanced-mean` coincide
  BY CONSTRUCTION, and the null rate halves to 10% (2 of C(6,3) = 20).
  The four five-round rows this moves are re-published under the balanced
  design and marked superseded IN PLACE on the studio page."
  6)
(def ^:private mount-sampling {:warmup 8 :samples 12})
(def ^:private bulk-sampling {:warmup 8 :samples 12})

;; rf2-2rtt6.2's slack, unchanged and for its reasons: the claim a clock
;; control certifies is THE INSTRUMENT HAS SIGNAL, not THE MODEL IS EXACT.
;; A top-down React re-render is not perfectly linear in element count —
;; the root, the commit and the diff walk do not double — so 2.00 +/- 5%
;; would fail an instrument that is working. (The +/-0.001% standard this
;; wave set belongs to the HEAP control, where the predicted quantity is a
;; known retained byte count; no clock control can be held to it and
;; pretending otherwise would be theatre.)
(def ^:private control-slack 0.25)

(defonce ^:private gen (atom 1000))
(defn- next-gen! [] (swap! gen inc))

(defn- mark-predecessor!
  "Advance the sample collector's `:previous` pointer WITHOUT banking a
  sample. Warm-up samples call this.

  `lane/collect!` both banks a sample and advances the pointer, so a
  harness that skips it during warm-up leaves the first RECORDED sample of
  a block tagged with whatever ran before the warm-up — an arm that has
  not touched the site for twenty operations. The first cut of this entry
  published exactly that and the guard refused it: `narrow/reagent-subs/
  ctl-2x` was contaminated by a two-sample stratum labelled
  `M1/reagent-subs/reagent-subs`, an arm from a different ROW. rf2-2rtt6.4
  met the same class from the other side, where the untagged samples
  became a `<none>` stratum reading 1.35x its siblings.

  A predecessor label that names something that did not run immediately
  before is not a weaker fact than a real one; it is a different fact
  wearing its clothes."
  [coll id]
  (swap! coll assoc :prev id)
  nil)

;; ---------------------------------------------------------------------------
;; Segments
;; ---------------------------------------------------------------------------

(def ^:private segments
  [{:id :reagent-subs :adapter reagent-adapter/adapter :name "Reagent-on-subs"}
   {:id :uix-subs     :adapter uix-adapter/adapter     :name "UIx-on-subs"}])

(defn- query-start
  "Which segment runs FIRST in round 0 — `?start=uix` or `?start=reagent`.

  Defaults to `:reagent-subs`, which is the only schedule any run before
  rf2-6i0i2 ever had. That fixed start is exactly what the audit contested:
  with alternation from a constant start, `Reagent first` and `rounds 0, 2,
  4` are the same set in every run, so an order effect and a temporal one
  are indistinguishable BY DESIGN however many runs agree. The parameter
  exists so that independently launched runs can counterbalance the start
  and pull those two apart; the driver passes it and reports it."
  []
  (let [s (or (some-> js/window .-location .-search) "")]
    (if (re-find #"start=uix" s) :uix-subs :reagent-subs)))

(defn- query-ratom
  "Is the second author's `:reagent-ratom` arm in the plan — `?ratom=on`?

  Default OFF, and the default is the point: with the arm in it the
  Reagent segment runs four arms against the UIx segment's three, which is
  a different page, a different interleave and a different mount budget
  from the one rf2-6i0i2's ensemble measured. An unflagged run of this
  entry is therefore still the instrument those four rows were taken on;
  a flagged run is a different plan and every record it writes says so."
  []
  (let [s (or (some-> js/window .-location .-search) "")]
    (boolean (re-find #"ratom=on" s))))

(defn- segment-order
  "Two segments admit exactly two orders and every round runs both
  segments; `start` names which one goes FIRST in round 0, and the order
  alternates with the round from there. A cross-segment figure taken in
  one order only is a single-order result however many rounds it
  averages."
  [start r]
  (let [base (if (= start (:id (first segments)))
               (vec segments)
               (vec (rseq (vec segments))))]
    (if (even? r) base (vec (rseq base)))))

(defn- enter-segment!
  "Tear down whatever adapter is installed, install this segment's,
  re-register the sub graph and stand the frame back up seeded.

  All of it OUTSIDE every measured window.

  A destroy that throws is RECORDED rather than swallowed. This is the
  segment SEAM, so it is the worst place in the run to lose one: a frame
  that did not tear down keeps its subscription caches and its watches,
  and the very next thing that happens is the OTHER adapter being
  installed over the top of them — after which every figure in the
  segment is a figure for a page carrying the previous segment's
  reactive graph (rf2-f5roa, from the PR #7268 audit)."
  [{:keys [adapter]}]
  (try (rf/destroy-frame! v/subs-frame)
       (catch :default e (lane/teardown-failure! "enter-segment! destroy-frame!" e)))
  (when (rf/current-adapter)
    (try (rf/destroy-adapter!)
         (catch :default e (lane/teardown-failure! "enter-segment! destroy-adapter!" e))))
  (rf/init! adapter)
  (v/register!)
  (rf/make-frame {:id v/subs-frame})
  (frame/replace-app-db! v/subs-frame (v/seed-cells v/cells-n 0))
  (lane/leave-act-environment!)
  nil)

;; ---------------------------------------------------------------------------
;; Mount arms
;; ---------------------------------------------------------------------------

(defn- zeros [n] (vec (repeat n 0)))

(defn- floor-mount-arm
  "`scale` is how many times the witness's own page this arm builds, and
  `:parity-exempt?` is DERIVED from it: an arm that builds a different page
  is exactly the arm the canonical-DOM gate must exempt. They used to be
  independent facts and the control carried only the exemption, which is
  how its doubled page went unchecked."
  [id element-of cells-of & [scale]]
  (let [scale (or scale 1)]
    {:id             id
     :scale          scale
     :parity-exempt? (not= 1 scale)
     :mount          (fn [container props _n]
                       (let [root (react-dom-client/createRoot container)]
                         (.render root (element-of (cells-of props)))
                         root))
     :unmount        (fn [root] (.unmount root))}))

(defn- reagent-mount-arm
  "Reagent's own mount door — what a Reagent application calls."
  [id form-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [root (rdc/create-root container)]
                (rdc/render root (form-of props))
                root))
   :unmount (fn [root] (rdc/unmount root))})

(defn- uix-mount-arm
  "UIx's own mount door — `uix.dom/create-root` + `render-root`, which is
  what a UIx application calls. A shared door would measure the shim."
  [id element-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [root (uix-dom/create-root container)]
                (uix-dom/render-root (element-of props) root)
                root))
   :unmount (fn [root] (uix-dom/unmount-root root))})

(defn- input-value
  "The DOM PROPERTY, not the attribute. React installs a controlled
  `value` as a property and mirrors it to the attribute only on first
  render, so a property read is the one that stays true."
  [container sel]
  (some-> (.querySelector container sel) (.-value)))

(defn- verify-m1
  "Both ends of the list AT THE ARM'S OWN SIZE — `n` rows, so the far probe
  is index `n - 1`.

  A page that committed only its head would pass a single-probe check at
  index 0; a CONTROL that rendered only its base prefix would pass a check
  at index 299, which is what this was for every arm. The one arm whose
  whole purpose is to be twice the page was the one arm nothing checked
  past its first half."
  [n]
  (fn [container]
    (and (= "0" (lane/text-at container 0))
         (= "0" (lane/text-at container (dec n))))))

(defn- verify-m2
  "The first and last field AT THE ARM'S OWN SIZE. Fixed at the witness's
  `fields-n` it read the SHARED PREFIX and could not tell a doubled form
  from a base one."
  [n]
  (fn [container]
    (and (= (v/field-value 0 0) (input-value container "#f0"))
         (= (v/field-value (dec n) 0)
            (input-value container (str "#f" (dec n)))))))

(defn- expectation
  "What THIS arm's page must contain: the witness's own arithmetic applied
  to the arm's `:scale`, as `{:elements n :verify f}`.

  There is no exemption. `mount-round!` used to read
  `(if (:parity-exempt? arm) nil elements)` and skip the element count for
  the control, while the probes read indices that exist in the base page —
  so the `:ctl-2x` arm was the ONE arm in the plan whose page was never
  checked, and a control that rendered only its base prefix passed. Every
  red-zone on this page rests on that control having seen the change its
  own arithmetic predicts."
  [{:keys [elements-of verify-of props]} arm]
  (let [n (* (or (:scale arm) 1) (:n props))]
    {:elements (elements-of n)
     :verify   (verify-of n)}))

(defn- base-elements [{:keys [elements-of props]}] (elements-of (:n props)))

(def ^:private mount-witnesses
  [{:id          :M1
    :grade       :bar
    :verify-of   verify-m1
    :elements-of v/m1-elements
    :doc      (str "the 300-boundary sub-reading list — the bulk shape's mount "
                   "counterpart, one subscription read per boundary. "
                   "rf2-2rtt6.2's M1, unchanged")
    :props    {:n v/cells-n}
    :control  {:predicted (/ (double (v/m1-elements (* 2 v/cells-n)))
                             (double (v/m1-elements v/cells-n)))
               :basis     (str "element count: " (v/m1-elements (* 2 v/cells-n)) " / "
                               (v/m1-elements v/cells-n))}
    ;; The `:reagent-ratom` arm sits between the denominator and the
    ;; control, which is rf2-2rtt6.2's own order — the plan is that arm's
    ;; plan with the UIx segment's arm swapped in on the other side of the
    ;; seam, so a reader comparing the two legs is comparing two authors
    ;; and not two orderings.
    :arms-for (fn [segment-id ratom?]
                (-> [(floor-mount-arm :floor v/m1-floor (fn [{:keys [n]}] (zeros n)))]
                    (into (case segment-id
                            :reagent-subs
                            (cond-> [(reagent-mount-arm
                                       :reagent-subs
                                       (fn [{:keys [n]}] [v/subs-root v/m1-subs n]))]
                              ratom? (conj (reagent-mount-arm
                                             :reagent-ratom
                                             (fn [{:keys [n]}] [v/m1-ratom n]))))
                            :uix-subs
                            [(uix-mount-arm
                               :uix-subs
                               (fn [{:keys [n]}] (ux/subs-root ux/m1 n)))]))
                    (conj (floor-mount-arm :ctl-2x v/m1-floor
                                           (fn [{:keys [n]}] (zeros (* 2 n))) 2))))}

   {:id          :M2
    :grade       :diagnostic
    :verify-of   verify-m2
    :elements-of v/m2-elements
    ;; A SMALLER MOUNT BUDGET THAN THE PAGE DEFAULT, and only here
    ;; (rf2-6i0i2). The sixth round pushed this page over the arm-order
    ;; guard's phase gate: at the default 8+12 sampling a six-round M2
    ;; page runs 720 mounts and FOUR OF SIX invocations were refused,
    ;; every refusal a LAST-third-slower disjoint split (2.0x-2.5x) on
    ;; readings of one to three 100 us quanta. The direction matters —
    ;; the page DEGRADES as it runs, which is the per-page accumulation
    ;; rf2-2rtt6.4 recorded and rf2-flqpd tied to collector debt, and the
    ;; dose-response was measured rather than guessed: 600 mounts a page
    ;; (the five-round budget) ran clean, 720 refused four of six, and a
    ;; misdiagnosed `deeper warm-up` repair — 1,296 mounts — refused
    ;; three of three with the split widened to 2.6x-4.0x. Warm-up depth
    ;; FEEDS the accumulation; the lever is the page's TOTAL mount
    ;; budget. So: 4 warm-up + 10 samples = 504 mounts a page at six
    ;; rounds, back under the budget every clean run ran at, with the
    ;; measured window (one mount) untouched and the tolerance untouched.
    ;; Less warm-up is safe HERE because the failure is the tail, not the
    ;; knee — a cold early sample pulls the FIRST third up, against the
    ;; climb. M1's readings are 30-50 quanta on the same budget, where
    ;; the same absolute drift is inside tolerance; it keeps the page
    ;; default. Batching this row's mounts instead was REFUSED by the
    ;; guard when rf2-2rtt6.2 tried it and is not re-litigated (see the
    ;; clock-note below).
    :sampling    {:warmup 4 :samples 10}
    ;; ONE mount per sample, exactly as rf2-2rtt6.2 publishes it. That arm
    ;; TRIED the batch that would lift this witness clear of Chrome's
    ;; 100 us clamp — eight 51-element roots in one flushSync — and THE
    ;; ARM-ORDER GUARD REFUSED THE WHOLE RUN, exit 2, with all four M2 arms
    ;; reading 3.2x-5.4x slower in the last third than in the first, ranges
    ;; disjoint, while the unbatched M1 row in the same page drifted
    ;; 1.13x-1.16x with ranges overlapping. Re-taking the batch here would
    ;; be re-litigating a refusal the denominator arm already resolved
    ;; against itself. The resulting coarseness is stated on the row and
    ;; the row is graded DIAGNOSTIC.
    :per-sample 1
    :clock-note (str "DIAGNOSTIC-GRADE, not a bar row and not a red-zone a "
                     "candidate is judged against. A 51-element mount takes a few "
                     "tenths of a millisecond — three to six of Chrome's 100 us "
                     "performance.now() quanta — so this witness's ratios are "
                     "quantised more coarsely than a 10% effect. rf2-2rtt6.4 "
                     "recorded the failure mode this row is exposed to: at one "
                     "mount a sample its 51-element form returned exactly 0.75 ms "
                     "from BOTH segments and the ratio came out at precisely "
                     "1.0000 — the quantum wearing a tie's clothes.")
    :doc      (str "the ordinary 12-field form on subs — the shape most "
                   "applications are made of. rf2-2rtt6.2's M2, unchanged")
    :props    {:n v/fields-n}
    :control  {:predicted (/ (double (v/m2-elements (* 2 v/fields-n)))
                             (double (v/m2-elements v/fields-n)))
               :basis     (str "element count: " (v/m2-elements (* 2 v/fields-n)) " / "
                               (v/m2-elements v/fields-n))}
    ;; `ratom?` is IGNORED here, and it is ignored for two reasons rather
    ;; than for want of a `conj` (rf2-2rtt6.21). This is the one row whose
    ;; mount budget already refuses — 720 mounts refused four of six, and
    ;; a fourth arm would put a six-round M2 page at 588 even off the
    ;; reduced sampling below. And the leg it would measure is one the
    ;; first author's own instrument cannot resolve: on the corrected-run
    ;; sweep `M2`'s reactive leg read exactly 1.0000 in ALL FIVE rounds,
    ;; because its two Reagent arms returned the same p50 every time on a
    ;; witness whose window is three to six of Chrome's 100 us quanta.
    ;; A second author cannot corroborate a number that is the clamp.
    :arms-for (fn [segment-id _ratom?]
                [(floor-mount-arm :floor v/m2-floor (fn [{:keys [n]}] (zeros n)))
                 (case segment-id
                   :reagent-subs (reagent-mount-arm
                                   :reagent-subs
                                   (fn [{:keys [n]}] [v/subs-root v/m2-subs n]))
                   :uix-subs     (uix-mount-arm
                                   :uix-subs
                                   (fn [{:keys [n]}] (ux/subs-root ux/m2 n))))
                 (floor-mount-arm :ctl-2x v/m2-floor
                                  (fn [{:keys [n]}] (zeros (* 2 n))) 2)])}])

(defn- witness-named [id] (first (filter #(= id (:id %)) mount-witnesses)))

;; ---------------------------------------------------------------------------
;; Bulk arms
;; ---------------------------------------------------------------------------

(defn- floor-bulk-arm
  "The floor's `force!` renders INSIDE the `flushSync`, and that is not a
  convenience. `root.render` called outside a React event schedules at
  React's DEFAULT lane and an empty `flushSync` flushes only the SYNC
  lane, so a floor arm that rendered in `write!` would have its commit
  land outside the measured window entirely — the recorded fault is 80 of
  320 floor samples ending on a cell that still held its old value."
  [id n & [scale]]
  (let [state (atom (zeros n))
        rt    (volatile! nil)]
    {:id             id
     :scale          (or scale 1)
     :parity-exempt? (not= 1 (or scale 1))
     :cells          n
     :mount          (fn [container]
                       (let [root (react-dom-client/createRoot container)]
                         (vreset! rt root)
                         (react-dom/flushSync (fn [] (.render root (v/m1-floor @state))))
                         root))
     :write!         (fn [i val]
                       (if (= i :all)
                         (reset! state (vec (repeat n val)))
                         (swap! state assoc i val)))
     :force!         (fn [] (react-dom/flushSync
                              (fn [] (.render ^js @rt (v/m1-floor @state)))))
     :unmount        (fn [root] (react-dom/flushSync (fn [] (.unmount root))))}))

(defn- subs-bulk-arm
  "Both substrate bulk arms, one constructor, so they differ in exactly
  two places: the mount door and the drain.

  THE WRITE IS IDENTICAL and it is rf2-2rtt6.2's write —
  `frame/replace-app-db!` — not a `dispatch-sync`. HD-012 states the bar
  over VIEW WORK, and rf2-2rtt6.3 measured the event drain at 11%-16% of
  a write on this substrate; routing the converged row through the event
  pipeline would add that leg to both arms, shrink the view-work
  difference the row exists to show, and make the Reagent figures here
  uncomparable with the ones rf2-2rtt6.2 already published. The pipeline
  is priced on rf2-2rtt6.3's own row.

  The NARROW write installs a whole new app-db with one cell changed, so
  all 300 layer-1 subscriptions recompute and exactly one boundary's
  value changes. That is the localisation question as a re-frame2
  application actually meets it, and it is the same operation on both
  substrate arms."
  [id mount unmount force!]
  (let [cells (atom (zeros v/cells-n))]
    {:id      id
     :cells   v/cells-n
     :mount   mount
     :unmount unmount
     :write!  (fn [i val]
                (if (= i :all)
                  (reset! cells (vec (repeat v/cells-n val)))
                  (swap! cells assoc i val))
                (frame/replace-app-db! v/subs-frame {:cells @cells}))
     :force!  force!}))

(defn- reagent-bulk-arm []
  (subs-bulk-arm
    :reagent-subs
    (fn [container]
      (let [root (rdc/create-root container)]
        (react-dom/flushSync
          (fn [] (rdc/render root [v/subs-root v/m1-subs v/cells-n])))
        root))
    (fn [root] (react-dom/flushSync (fn [] (rdc/unmount root))))
    ;; `reagent.core/flush` is Reagent's own documented synchronous render
    ;; drain — the same drain rf2-2rtt6.2's published row used.
    (fn [] (react-dom/flushSync (fn [] (r/flush))))))

(defn- uix-bulk-arm []
  (subs-bulk-arm
    :uix-subs
    (fn [container]
      (let [root (uix-dom/create-root container)]
        (react-dom/flushSync
          (fn [] (uix-dom/render-root (ux/subs-root ux/m1 v/cells-n) root)))
        root))
    (fn [root] (react-dom/flushSync (fn [] (uix-dom/unmount-root root))))
    ;; `useSyncExternalStore` notifications schedule at React's SYNC lane,
    ;; so an EMPTY flushSync commits them inside the window. Not
    ;; `uix-adapter/flush-views!`, which wraps React's `act()` — `act`
    ;; diverts work to a queue that is not the browser's, and every
    ;; window in this lane is taken outside it.
    (fn [] (react-dom/flushSync (fn [] nil)))))

(defn- ratom-bulk-arm
  "The second author's lower bound on the bulk rows (rf2-2rtt6.21): the
  same 300-cell page, read through an `r/cursor` over a bare
  `reagent.core/atom` instead of through a subscription.

  It is NOT built by [[subs-bulk-arm]], and the difference is the whole
  point of the arm: the write installs into `v/ratom-cells` rather than
  into the frame's app-db, so this arm pays no subscription graph, no
  query cache and no frame. Everything else is held identical to the
  `:reagent-subs` arm beside it — the same mount door
  (`reagent.dom.client`), the same drain (`reagent.core/flush` inside one
  `flushSync`), the same page, the same window. `subs / ratom` is then
  the price of the reactive system and nothing else, which is the term
  rf2-2rtt6.2's headline 1 turns on.

  The write handles a single cell as well as `:all` because the arm is
  written to the same contract as its sibling; only the `:broad` row puts
  it in a plan."
  []
  {:id      :reagent-ratom
   :cells   v/cells-n
   :mount   (fn [container]
              (let [root (rdc/create-root container)]
                (react-dom/flushSync
                  (fn [] (rdc/render root [v/m1-ratom v/cells-n])))
                root))
   :write!  (fn [i val]
              (if (= i :all)
                (reset! v/ratom-cells (vec (repeat v/cells-n val)))
                (swap! v/ratom-cells assoc i val)))
   :force!  (fn [] (react-dom/flushSync (fn [] (r/flush))))
   :unmount (fn [root] (react-dom/flushSync (fn [] (rdc/unmount root))))})

(defn- bulk-arms
  "`:broad` is the bulk row rf2-2rtt6.2 published a reactive leg on, so it
  is the bulk row the second author's arm joins. `:narrow` has no
  first-author counterpart at all — rf2-2rtt6.2 has no narrow row — so a
  ratom arm there would be a new figure rather than a corroboration, and
  this entry does not mint one while it is answering a different question."
  [segment-id row-key ratom?]
  (-> [(floor-bulk-arm :floor v/cells-n)]
      (into (case segment-id
              :reagent-subs (cond-> [(reagent-bulk-arm)]
                              (and ratom? (= row-key :broad))
                              (conj (ratom-bulk-arm)))
              :uix-subs     [(uix-bulk-arm)]))
      (conj (floor-bulk-arm :ctl-2x (* 2 v/cells-n) 2))))

(defn- mount-bulk-arms!
  "Mount every bulk arm once and CHECK THE PAGE IT BUILT, before a clock is
  read.

  Every bulk arm declares `:cells` and `v/m1-elements` turns that into an
  element count by written arithmetic, so this is what makes the bulk
  `:ctl-2x` arm's doubled page load-bearing. Its far-end read-back already
  derives from `:cells` (`lane/bulk-probes` probes index `cells - 1`), so a
  control shrunk to the base 300 cells would still have probed a cell it
  owns; the COUNT is the check a smaller page cannot satisfy. Outside every
  timed window — the arms are mounted once and written to thereafter."
  [arms]
  (mapv (fn [a]
          (let [c        (lane/fresh-container!)
                handle   ((:mount a) c)
                expected (v/m1-elements (:cells a))
                got      (lane/element-count c)]
            (when-not (= expected got)
              (throw (ex-info (str "the bulk arm " (:id a) " built " got
                                   " elements where its own " (:cells a)
                                   " cells make " expected)
                              {:arm (:id a) :expected expected :got got})))
            {:arm a :container c :handle handle}))
        arms))

(defn- release-bulk-arms!
  "Release every bulk arm. A throw is RECORDED, never swallowed — and a
  normal return is not taken at its word: `lane/container-released!` reads
  each container before it is removed, exactly as `lane/release!` and
  `p0_reagent_app`'s sibling do, because a root that survives its own
  unmount does so on a DETACHED tree no later census can see (rf2-jk3vj)."
  [mounts]
  (doseq [{:keys [arm handle container]} mounts]
    (when (try ((:unmount arm) handle)
               true
               (catch :default e
                 (lane/teardown-failure! (str "release-bulk-arms! " (:id arm)) e)))
      (lane/container-released! (str "release-bulk-arms! " (:id arm)) container))
    (.remove container)))

(def ^:private assert-teardown-clean!
  "Lifted into `lane.cljs` (rf2-2rtt6.2), because the Reagent baseline arm
  needs the same adjudication and a second copy of a fatal rule is a second
  authority with nothing holding it in step. Aliased rather than inlined at
  the call sites so this entry's four `assert-teardown-clean!` calls still
  read as the sentences they are."
  lane/assert-teardown-clean!)

;; ---------------------------------------------------------------------------
;; One round of one mount witness in one segment
;; ---------------------------------------------------------------------------

(defn- mount-round!
  [coll t {:keys [id props per-sample arms-for sampling] :as witness} segment-id ratom?]
  (let [{:keys [warmup samples]} (or sampling mount-sampling)
        arms   (arms-for segment-id ratom?)
        n      (count arms)
        k      (or per-sample 1)
        expect (into {} (map (juxt :id #(expectation witness %))) arms)
        acc    (atom (zipmap (map :id arms) (repeat [])))]
    (dotimes [s (+ warmup samples)]
      (doseq [j (lane/slot-order n s)]
        (let [arm (nth arms j)
              {:keys [ms mounts]} (lane/mount-batch! arm props k)
              {exp-elements :elements verify :verify} (get expect (:id arm))]
          ;; EVERY mount is read back out of the document against THIS
          ;; ARM'S OWN arithmetic — its exact element count, and both ends
          ;; of the page it claims to build. An arm that rendered an empty
          ;; page is the cheapest arm in any table, and a CONTROL that
          ;; rendered only its base prefix is the cheapest 2x arm; this is
          ;; the line that catches both.
          (doseq [m mounts]
            (let [ok? (and (= exp-elements (lane/element-count (:container m)))
                           (verify (:container m)))]
              (swap! t (fn [{:keys [of bad]}]
                         {:of (inc of) :bad (if ok? bad (inc bad))}))))
          (doseq [m mounts] (lane/release! m))
          (let [label (str (name id) "/" (name segment-id) "/" (name (:id arm)))]
            (if (>= s warmup)
              (do (lane/collect! coll label ms)
                  (swap! acc update (:id arm) conj ms))
              ;; A warm-up sample is DISCARDED but it still ran, so it is
              ;; still the next sample's predecessor.
              (mark-predecessor! coll label))))))
    @acc))

;; ---------------------------------------------------------------------------
;; One round of one bulk kind in one segment
;; ---------------------------------------------------------------------------

(def ^:private narrow-batch-k
  "How many narrow writes share ONE clock (rf2-zb3qg).

  Chrome clamps `performance.now()` to 100 µs and the unbatched narrow row
  sat one to one-and-a-half quanta above its own floor: every leg was 3–5
  quanta (floor 2–3, reagent-subs 4–4.5, uix-subs 4.5–5), so its four
  published estimates read 1.0405 / 1.1556 / 1.1738 / 1.1972 — the same
  DIRECTION every time, but whether the range cleared 1.0 depended on the
  run, and one estimate had a minimum of exactly 1.0000. That is the
  quantum, not a tie. The row was published as CLAMP-LIMITED rather than as
  a resolved threshold precisely because that was the honest reading.

  Ten writes per window puts the sample 30–50 quanta up, where the quantum
  is ~2–3% of the reading instead of 20–33% of it.

  Ten and not more, for `hd8-rows/narrow-batch-k`'s reasons, which this
  deliberately copies rather than re-derives: the batch's cells must be
  DISTINCT (they are `(mod (* 7 o) 300)` over consecutive `o`, and 7 is
  coprime with 300, so any run of ten is ten different cells), and the
  microtask-scale tolerance an early op receives grows with `k`
  ([[lane/verified-writes!]]). Ten buys the clamp headroom and keeps that
  tolerance at nine turns.

  THE BROAD ROW DOES NOT USE THIS. It passes a batch of ONE — the pre-batch
  window exactly — because its readings are already far above the clamp,
  and because it is a published bar row that nothing here may move."
  10)

(defonce ^:private op-seq (atom 0))
(defn- next-op! [] (swap! op-seq inc))

(defn- bulk-write!
  "One SAMPLE on one arm, timed as one window and verified at the DOM after
  the clock stops.

  BROAD is one write of every cell, and it is a batch of one: the probe
  rotates with the value AND the far end of the grid is checked, because a
  stale page can still carry one fresh cell from the previous write and a
  single fixed probe would accept it.

  NARROW is [[narrow-batch-k]] writes, each changing exactly ONE cell, all
  under one clock. Each op gets its OWN value and its OWN cell, so no
  read-back can be satisfied by a neighbour's commit; and a narrow write
  changes one cell, so one probe is that op's whole page's worth of
  verification — the strength comes from the value being fresh on every
  write, which means a stale page holds the PREVIOUS value at the probed
  cell and fails."
  [t mnt kind _s]
  (let [n (:cells (:arm mnt))]
    (if (= kind :broad)
      (let [val (next-gen!)]
        (lane/verified-writes! t mnt [[:all val (lane/bulk-probes val n)]]))
      (lane/verified-writes!
        t mnt
        (mapv (fn [_]
                (let [o    (next-op!)
                      cell (mod (* 7 o) n)
                      val  (next-gen!)]
                  [cell val [cell]]))
              (range narrow-batch-k))))))

(defn- seed-bulk!
  [mounts t]
  (lane/chain nil mounts
              (fn [_ mnt] (-> (lane/verified-write! t mnt :all 0
                                                    [0 (dec (:cells (:arm mnt)))])
                              (.then (fn [_] nil))))))

(defn- bulk-round!
  "One round. The guard's samples are banked AT THE SAMPLE, inside the
  interleave — banking them per arm after the round would record every
  arm's readings as consecutive and hand the guard a `:position` factor
  that says only what the loop's shape was."
  [mounts t legs coll kind segment-id]
  (let [n     (count mounts)
        total (+ (:warmup bulk-sampling) (:samples bulk-sampling))
        acc0  (zipmap (map #(:id (:arm %)) mounts) (repeat []))]
    (lane/chain {:readings acc0}
                (for [s (range total) j (lane/slot-order n s)] [s j])
                (fn [acc [s j]]
                  (let [mnt   (nth mounts j)
                        id    (:id (:arm mnt))
                        label (str (name kind) "/" (name segment-id) "/" (name id))]
                    (-> (bulk-write! t mnt kind s)
                        (.then (fn [{:keys [ms write-ms gap-ms force-ms]}]
                                 (if (>= s (:warmup bulk-sampling))
                                   (do (lane/collect! coll label ms)
                                       (swap! legs update [kind segment-id id] (fnil conj [])
                                              {:write write-ms :gap gap-ms :force force-ms})
                                       (update-in acc [:readings id] conj ms))
                                   (do (mark-predecessor! coll label)
                                       acc))))))))))

;; ---------------------------------------------------------------------------
;; Aggregation
;; ---------------------------------------------------------------------------

(defn- ratios-of
  "One segment-round's raw readings as `{:p50 {id ms} :ratio {id r}}`,
  every ratio against the floor measured in THAT segment of THAT round."
  [readings]
  (lane/normalise readings :floor))

(defn- range-of [vs]
  {:mean (lane/round4 (/ (reduce + 0.0 vs) (count vs)))
   :min  (lane/round4 (apply min vs))
   :max  (lane/round4 (apply max vs))
   :per-round (mapv lane/round4 vs)
   :straddles-1? (and (<= (apply min vs) 1.0) (>= (apply max vs) 1.0))})

;; ---------------------------------------------------------------------------
;; The segment-order verdict — the gate the cross-segment figure never had
;; ---------------------------------------------------------------------------

(defn- direction-of
  "Where a range sits relative to 1.0, as a verdict rather than a number."
  [{:keys [min max]}]
  (cond (> min 1.0) :numerator-slower
        (< max 1.0) :numerator-faster
        :else       :indistinguishable))

(defn segment-order-verdict
  "Adjudicate a cross-segment figure BY WHICH SEGMENT RAN FIRST.

  Public because `p0_converge_order_cljs_test` replays the PUBLISHED
  per-round vectors through it: a verdict that has only ever seen the run
  it shipped with is a verdict nobody can check. That is the only reason
  anything in this entry is public, and it is the reason for the other
  three — [[ratom-leg]], [[row-record]] and [[reagent-segment-arm-ids]].

  ## What the arm-order guard cannot see, and why this exists

  `lane/guard!` adjudicates arms INSIDE a segment: it asks whether an arm
  reads differently for where in the plan it was measured. The red-zone is
  not an arm — it is a ratio of one segment's floor-normalised arm to the
  OTHER segment's, and no guard on this page ever looked at it by segment
  order. [[segment-order]] alternates, so every round is already labelled
  with the answer; nothing was asking the question. The seam control
  records the floor's own drift and stops there (rf2-a4x1o, from the PR
  #7265 and #7268 audits).

  ## Two claims, adjudicated separately, because they fail separately

  DIRECTION — `UIx slower` / `UIx faster` / `indistinguishable`. This is
  what the bar and the red-zone rule actually consume. It is FAIL-CLOSED:
  a row whose two order strata point OPPOSITE WAYS has no direction to
  publish, `:refuse?` is true, and the driver exits non-zero. A figure
  that says `slower` when Reagent went first and `faster` when UIx did is
  a measurement of the schedule.

  MAGNITUDE — the single number a candidate would be judged against. It is
  reportable only when the two strata OVERLAP. Disjoint strata mean the
  figure has a component that is the segment order, and a mean over a
  bimodal split is not a threshold; the row then publishes its direction
  and its stratum bounds, and NOT a precise magnitude. Silence is not a
  pass: `:magnitude-resolved?` starts false and the overlap has to earn it.

  ## `:order-balanced-mean`, and why the design is now EVEN

  With `rounds` odd the alternation is UNBALANCED — three rounds in one
  order, two in the other — so the arithmetic mean over rounds
  over-weights whichever order got the extra round. The unbiased estimator
  under an alternating design is the MEAN OF THE TWO STRATUM MEANS, and
  that is `:order-balanced-mean`, published beside the raw one whatever
  the parity of `rounds`. `:balanced-design?` says whether they can
  differ. The five-round design could not take the even count without
  moving four published rows; rf2-6i0i2 took it AS a re-publication, so
  [[rounds]] is now 6 and the two estimators coincide by construction on
  every row this entry measures. The odd-round arithmetic stays because
  this fn is PUBLIC and replays the published five-round vectors.

  ## `start`, and what one run's partition can and cannot say

  `start` is the segment that ran first in round 0, and the strata are
  keyed by the segment that actually led each round — `:reagent-first` is
  the rounds Reagent's segment led wherever they fell in the schedule.
  Before rf2-6i0i2 the start was constant, so `Reagent first` and `rounds
  0, 2, 4` were the same set in every run: a temporal drift and an order
  effect produce identical partitions under that design, and no number of
  same-start runs can tell them apart. Counterbalancing the start across
  INDEPENDENTLY LAUNCHED runs is what breaks the tie — an order effect
  keeps its sign when the start flips, a temporal one reverses it — and
  the inference belongs at the run level, one statistic per launched run,
  never four correlated rows of one run counted as four trials.

  ## What a disjoint split is and is not EVIDENCE of

  At `rounds` 5 the strata are 3 and 2. Under the null — no order effect,
  the five rounds exchangeable — the 2-element stratum is one of
  `C(5,2) = 10` equally likely subsets, and exactly TWO of them (the two
  smallest, the two largest) separate the strata. So a disjoint split
  arises **20% of the time with no order effect at all**, per row. At 6
  rounds the same arithmetic gives 2 of `C(6,3) = 20` — 10%. That is
  why disjointness withdraws the magnitude rather than condemning the
  measurement: it is a statement about what this design can resolve, not a
  finding that the schedule moved the number.

  ## `what`, and the figure that does not cross the seam

  The four-argument arity names the figure under adjudication in `:why`,
  and it exists because the red-zone is no longer the only thing this
  verdict judges. rf2-2rtt6.21's REACTIVE LEG — `reagent-subs` over
  `reagent-ratom` — is formed INSIDE the Reagent segment, so the seam
  never enters it. The partition is still the right question: the Reagent
  segment leads half the rounds and follows the other half, and a leg that
  reads differently for which is a leg that has measured the schedule.
  What changes is only the name in the sentence, and a verdict that
  described a within-segment ratio as `the red-zone's` would be a small
  lie in the one place a reader goes to check the arithmetic."
  ([vs rounds start] (segment-order-verdict vs rounds start "the red-zone's"))
  ([vs rounds start what]
    (let [lead-even  (if (= start :uix-subs) :uix-first :reagent-first)
          lead-odd   (if (= lead-even :uix-first) :reagent-first :uix-first)
          strata     {lead-even (vec (keep-indexed #(when (even? %1) %2) vs))
                      lead-odd  (vec (keep-indexed #(when (odd? %1) %2) vs))}
          rf         (range-of (:reagent-first strata))
          uf         (range-of (:uix-first strata))
          overlap?   (and (<= (:min rf) (:max uf)) (<= (:min uf) (:max rf)))
          d-rf       (direction-of rf)
          d-uf       (direction-of uf)
          opposed?   (or (and (= d-rf :numerator-slower) (= d-uf :numerator-faster))
                         (and (= d-rf :numerator-faster) (= d-uf :numerator-slower)))
          balanced?  (even? rounds)]
      {:start               start
       :reagent-first       (assoc rf :direction d-rf :n (count (:reagent-first strata)))
       :uix-first           (assoc uf :direction d-uf :n (count (:uix-first strata)))
       :order-balanced-mean (lane/round4 (/ (+ (:mean rf) (:mean uf)) 2.0))
       :balanced-design?    balanced?
       :strata-overlap?     overlap?
       :magnitude-resolved? overlap?
       :direction-agrees?   (not opposed?)
       :refuse?             opposed?
       :why
       (str what " rounds partitioned by which segment ran FIRST "
            "(round 0 led by " (name start) ", alternating). "
            "Reagent-first " (.toFixed (:mean rf) 4) "x [" (.toFixed (:min rf) 4) "–"
            (.toFixed (:max rf) 4) "] over " (count (:reagent-first strata)) " rounds; "
            "UIx-first " (.toFixed (:mean uf) 4) "x [" (.toFixed (:min uf) 4) "–"
            (.toFixed (:max uf) 4) "] over " (count (:uix-first strata)) " rounds. "
            (if opposed?
              (str "THE TWO STRATA POINT OPPOSITE WAYS, so this row has no direction to "
                   "publish and no figure in it is reportable.")
              (if overlap?
                (str "The strata OVERLAP, so the magnitude survives the partition and the "
                     "row may publish one.")
                (str "The strata are DISJOINT, so part of this figure is WHICH SEGMENT "
                     "RAN FIRST and the mean over them is not a threshold. The row "
                     "publishes its DIRECTION and the stratum bounds; it may NOT publish a "
                     "precise magnitude. At " rounds " rounds the split is "
                     (count (:reagent-first strata)) ":" (count (:uix-first strata))
                     " and a disjoint partition arises by chance alone in 2 of "
                     "C(" rounds "," (count (:uix-first strata)) ") assignments, so this "
                     "withdraws a claim rather than establishing an effect."))))})))

(defn ratom-leg
  "This row's REACTIVE LEG — `reagent-subs` over `reagent-ratom`, per
  round, both floor-normalised in the SAME segment of the SAME round — or
  `nil` when this row's Reagent segment did not run the `:reagent-ratom`
  arm.

  BOTH TERMS ARE THE REAGENT SEGMENT'S OWN, so the floor divides out
  exactly and the seam is not in the arithmetic at all. The leg is formed
  from the floor-normalised ratios rather than from the raw p50s only
  because that is the arithmetic `lane/ratio-between` performs on the
  first author's page, and two authors disagreeing about a division is
  not a finding anybody wants to chase.

  DECIDED BY THE SLICE, and that is the whole of the function
  (rf2-2rtt6.21). `?ratom=on` is a page-global flag while arm presence is
  ROW-SPECIFIC: the `M2` witness ignores the flag outright and
  [[bulk-arms]] admits the arm only on `:broad`. A record that read the
  FLAG instead of the MEASUREMENT therefore divided by a ratio those two
  rows never produced — `subs / nil`, which JavaScript answers `Infinity`
  rather than refusing — formed a ratom floor of nothing, ran the leg
  verdict over it and labelled the row as carrying an arm it was never
  given. The natural flagged invocation, `HICASSO_RATOM=on` with no
  `HICASSO_ONLY`, is exactly the one that selects those rows; the
  published runs escaped it only by always pairing the flag with
  `HICASSO_ONLY=M1,broad`.

  A PARTIAL arm is treated as no arm. Every round must carry a finite,
  positive ratom ratio, because a leg formed over some rounds and not
  others is a different figure from the one this page publishes, and a
  figure that quietly changes what it averages over is worse than one
  that is absent."
  [norm]
  (let [ratom (mapv #(get-in % [:reagent-subs :ratio :reagent-ratom]) norm)]
    (when (and (seq ratom)
               (every? #(and (number? %) (js/isFinite %) (pos? %)) ratom))
      (mapv (fn [m r] (/ (get-in m [:reagent-subs :ratio :reagent-subs]) r))
            norm ratom))))

(defn row-record
  "Turn one row's per-round, per-segment slices into the published record.

  `slices` is `[{segment-id {arm-id [ms ...]}} ...]`, one entry per round.
  `start` is the segment that led round 0, and it is published on the
  record because a reader comparing counterbalanced runs needs it.

  Whether the second author's `:reagent-ratom` arm was in this row's plan
  is not passed in: it is read off the slices by [[ratom-leg]], because
  the flag that requests the arm is global and the plan that runs it is
  per-row. When the arm ran, the record carries a REACTIVE LEG — and it
  also carries the fact that the red-zone beside it came off a four-arm
  Reagent segment, because a threshold and the plan it was measured under
  are one fact and not two (rf2-2rtt6.21).

  Public for `p0_converge_order_cljs_test`, which feeds it one round of
  each row's actual arm set and holds the flagged default selection to
  that contract without a browser."
  [{:keys [row doc grade clock-note control note writes-per-sample start]} slices]
  (let [norm       (mapv (fn [by-seg]
                           (into {} (map (fn [[sid rd]] [sid (ratios-of rd)])) by-seg))
                         slices)
        rz         (mapv (fn [m] (/ (get-in m [:uix-subs :ratio :uix-subs])
                                    (get-in m [:reagent-subs :ratio :reagent-subs])))
                         norm)
        rg-floor   (mapv #(get-in % [:reagent-subs :ratio :reagent-subs]) norm)
        ux-floor   (mapv #(get-in % [:uix-subs :ratio :uix-subs]) norm)
        leg        (ratom-leg norm)
        arm?       (some? leg)
        ratom-floor (when arm?
                      (mapv #(get-in % [:reagent-subs :ratio :reagent-ratom]) norm))
        leg-order  (when arm?
                     (segment-order-verdict leg rounds start "the reactive leg's"))
        seam       (mapv (fn [m] (/ (get-in m [:uix-subs :p50 :floor])
                                    (get-in m [:reagent-subs :p50 :floor])))
                         norm)
        ctl-rg     (mapv #(get-in % [:reagent-subs :ratio :ctl-2x]) norm)
        ctl-ux     (mapv #(get-in % [:uix-subs :ratio :ctl-2x]) norm)
        verdict-of (fn [vs] (lane/control-verdict (:predicted control)
                                                  (select-keys (range-of vs) [:min :max :mean])
                                                  control-slack))
        c-rg       (verdict-of ctl-rg)
        c-ux       (verdict-of ctl-ux)
        order      (segment-order-verdict rz rounds start)]
    {:record
     {:benchmark   (keyword "hicasso.P0.converged" (name row))
      :doc         doc
      :bead        "rf2-6i0i2"
      :grade       grade
      :clock-note  clock-note
      :note        note
      :witness-set "rf2-2rtt6.2"
      :rounds      rounds
      :start-segment start
      ;; STATED ON THE ROW, because a reader comparing this against the
      ;; pre-rf2-zb3qg numbers is comparing two different windows and has to
      ;; be told so. 1 means the sample IS the operation.
      :writes-per-sample (or writes-per-sample 1)
      :red-zone    (assoc (range-of rz)
                          :numerator :uix-subs
                          :denominator :reagent-subs
                          :axis :clock
                          ;; What this row is ENTITLED to publish, decided by
                          ;; the segment-order verdict rather than by whoever
                          ;; writes the studio page. `:direction-only` means
                          ;; the mean above is an average over a split the
                          ;; schedule explains part of, and must not be quoted
                          ;; as a threshold.
                          :claim (if (:magnitude-resolved? order)
                                   :magnitude
                                   :direction-only)
                          :direction (direction-of (range-of rz))
                          :ratom-arm? arm?
                          :comparability
                          (when arm?
                            (str "NOT the published threshold and not comparable with "
                                 "it. With :reagent-ratom in the plan the Reagent "
                                 "segment ran FOUR arms against the UIx segment's "
                                 "three, so the interleave, the page's total mount "
                                 "budget and the adjacency structure all differ from "
                                 "the schedule rf2-6i0i2's ten-run ensemble was "
                                 "measured under. It is reported because it comes off "
                                 "the same slices and hiding it would be worse than "
                                 "qualifying it; it may not be quoted as a red-zone.")))
      :segment-order-control order
      :ratom-arm?  arm?
      ;; THE ROW THIS RUN EXISTS FOR when the arm is in the plan. Both
      ;; terms are Reagent arms measured in the same segment of the same
      ;; round, which is what makes it a second AUTHOR'S reading of
      ;; rf2-2rtt6.2's headline 1 rather than a fourth run of the first's.
      :reactive-leg
      (when arm?
        (assoc (range-of leg)
               :numerator :reagent-subs
               :denominator :reagent-ratom
               :axis :clock
               :claim (if (:magnitude-resolved? leg-order) :magnitude :direction-only)
               :direction (direction-of (range-of leg))
               :why (str "Reagent reading re-frame2 subscriptions over Reagent "
                         "reading an r/cursor on a bare r/atom, both floor-"
                         "normalised in the SAME segment of the SAME round, so the "
                         "floor divides out and the segment seam is not in the "
                         "arithmetic. This is a SECOND IMPLEMENTATION of the term "
                         "rf2-2rtt6.2 published as ~1.22x on mount and ~2.01x on a "
                         "broad commit — a different app namespace, a different "
                         "schedule, a different round count and a different arm "
                         "plan (rf2-2rtt6.21).")))
      :reactive-leg-segment-order leg-order
      :ratom-over-floor   (when arm? (range-of ratom-floor))
      :uix-over-floor     (range-of ux-floor)
      :reagent-over-floor (range-of rg-floor)
      :segment-seam-control
      {:floor-uix-over-floor-reagent (range-of seam)
       :why (str "the FLOOR's own p50 in the UIx segment over its p50 in the "
                 "Reagent segment, same round. The floor is identical work in both "
                 "and holds no re-frame state, so this is drift between the segments "
                 "and nothing else. Every published figure is a ratio to the floor "
                 "measured in the SAME segment of the SAME round, so the seam "
                 "cancels — and the evidence for that is this range against the "
                 "red-zone's, not an argument that it should.")}
      :positive-control {:predicted (:predicted control)
                         :basis     (:basis control)
                         :reagent-segment c-rg
                         :uix-segment     c-ux}
      :per-round   {:reagent (mapv #(get-in % [:reagent-subs :p50]) norm)
                    :uix     (mapv #(get-in % [:uix-subs :p50]) norm)}
      :status      :evidence}
     :controls  [c-rg c-ux]
     :order     order
     :leg-order leg-order}))

;; ---------------------------------------------------------------------------
;; Parity — before any clock is read, in EVERY segment and ACROSS the seam
;; ---------------------------------------------------------------------------

(defn- parity-of-segment!
  "Canonical-DOM equality across the judged arms, and the element count of
  EVERY arm — the control included — against its own arithmetic.

  The count used to be checked over `lane/parity`'s `counts` map, which
  excludes the parity-exempt arms by design, so the one arm building a
  deliberately different page had that page checked nowhere. Exempt from
  the EQUALITY is not exempt from arithmetic: the control is exempt
  because its page differs, and the size it differs BY is the claim it
  exists to make."
  [{:keys [id props arms-for] :as witness} segment-id ratom?]
  (let [{:keys [mounts agree? disagree reference]}
        (lane/parity (arms-for segment-id ratom?) props)
        wrong (into {}
                    (comp (map (fn [{:keys [arm container]}]
                                 [(:id arm)
                                  {:expected (:elements (expectation witness arm))
                                   :got      (lane/element-count container)}]))
                          (remove (fn [[_ {:keys [expected got]}]] (= expected got))))
                    mounts)]
    (try
      {:reference reference
       :problems  (cond-> []
                    (not agree?)
                    (conj {:segment segment-id :witness id
                           :problem :canonical-dom-disagreement :arms disagree})

                    (seq wrong)
                    (conj {:segment segment-id :witness id :problem :element-count
                           :arms wrong}))}
      (finally (doseq [m mounts] (lane/release! m))))))

(defn- parity!
  "Both segments' parity for this page's witness, plus the CROSS-segment
  check.

  Within a segment the floor and the substrate arm must build the same
  page. Across the seam the two SUBSTRATE arms must build the same page as
  each other — which is the comparison the red-zone actually makes, and it
  is checked directly rather than inferred from each segment agreeing with
  its own floor."
  [witness ratom?]
  (let [by-seg (reduce (fn [acc {:keys [id] :as segment}]
                         (enter-segment! segment)
                         (assoc acc id (parity-of-segment! witness id ratom?)))
                       {}
                       segments)
        cross  (when (not= (get-in by-seg [:reagent-subs :reference])
                           (get-in by-seg [:uix-subs :reference]))
                 [{:problem :cross-segment-disagreement :witness (:id witness)}])]
    {:problems (into (vec (mapcat (comp :problems val) by-seg)) cross)}))

;; ---------------------------------------------------------------------------
;; The schedule's own precondition (rf2-ouwh8)
;; ---------------------------------------------------------------------------

(defn- slot-order-varies-at-2?
  "Does `slot-order` emit MORE THAN ONE order at `k = 2`?

  It does now, and that is a REVERSAL of what this function used to assert.
  rf2-ouwh8 found that composing rotate-then-reflect at `k = 2` returns
  `[0 1]` at every index — reversing a pair IS rotating it by one, so the
  two operations cancel and a two-arm plan ran in ONE ORDER FOR EVER. This
  entry was designed around that defect: it puts THREE arms in every
  segment so it never forms a two-arm plan, and it asserted the degeneracy
  at boot so the justification could not rot.

  The defect was then REPAIRED (`e176fed976`, PR #7267): at `k = 2` the
  reflection is dropped and the plain rotation alternates `[0 1]` and
  `[1 0]`, which is every order two arms have. The assertion did rot — in
  the other direction. It went on asserting the pre-repair behaviour, went
  false the moment the repair landed, and this entry's `-main` refused to
  measure anything from then until rf2-zb3qg found it. Every row of
  `docs/design/hicasso/studio/p0-converged-witness-set.md` was
  unreproducible at HEAD for that whole window: the repro command exited 1
  before taking a sample.

  So it asserts the REPAIRED property instead, as a canary — if `k = 2`
  ever silently degenerates again, this entry says so at boot rather than
  in a table.

  THE THREE-ARM PLAN IS KEPT, and not because a two-arm plan would still
  be single-order. It would not. It is kept because three arms is what
  every published row on this page was measured under, and the schedule an
  arm runs is part of its window: changing it would move numbers that
  nothing here is re-running."
  []
  (> (count (distinct (map #(guard/slot-order 2 %) (range 8)))) 1))

(defn- slot-order-varies-at-3?
  "And the three-arm plan this entry actually runs must NOT degenerate."
  []
  (> (count (distinct (map #(guard/slot-order 3 %) (range 8)))) 1))

(defn- slot-order-varies-at-4?
  "Nor the FOUR-arm plan the Reagent segment runs under `?ratom=on`.

  Asserted whether or not the flag is set, for the reason the `k = 2`
  canary is: the property belongs to the shared rule, and an assertion
  that only fires on the days it is needed is an assertion nobody
  maintains. That is precisely how the `k = 2` one rotted."
  []
  (> (count (distinct (map #(guard/slot-order 4 %) (range 8)))) 1))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(def ^:private bulk-control
  {:predicted (/ (double (v/m1-elements (* 2 v/cells-n)))
                 (double (v/m1-elements v/cells-n)))
   :basis (str "element count: " (v/m1-elements (* 2 v/cells-n)) " / "
               (v/m1-elements v/cells-n))})

(def ^:private row-specs
  "ONE ROW PER PAGE. The first cut ran all four in one page and the guard
  refused it — the page degraded as it ran (`M1/uix-subs/floor`, an arm
  that cannot change, read LAST-THIRD 2.1739x FIRST-THIRD with disjoint
  ranges) and the shared collector manufactured cross-row predecessor
  strata. One row per page cuts a page's measured work by about four and
  removes cross-row adjacency by construction."
  {:M1     {:kind :mount :witness :M1 :row :mount-M1 :grade :bar}
   :M2     {:kind :mount :witness :M2 :row :mount-M2 :grade :diagnostic}
   :broad  {:kind :bulk  :witness :M1 :row :bulk-broad :grade :bar
            :doc (str "one commit that all " v/cells-n " sub-reading boundaries read, on "
                      "rf2-2rtt6.2's M1 page — the make-or-break row")
            :control bulk-control}
   :narrow {:kind :bulk  :witness :M1 :row :bulk-narrow :grade :bar
            :doc (str narrow-batch-k " commits, each of which exactly ONE of " v/cells-n
                      " sub-reading boundaries reads, all inside ONE timed window — the "
                      "localisation row. The per-commit figure is the sample divided by "
                      narrow-batch-k)
            :note (str "NEW ROW. rf2-2rtt6.2 has no narrow counterpart, so this is not a "
                       "re-measurement of a published figure: it is rf2-2rtt6.4's U-narrow "
                       "question asked on rf2-2rtt6.2's witness. The two numbers are NOT "
                       "comparable with each other and neither supersedes the other; this "
                       "one is the comparable one, because its page is the page every other "
                       "row here uses.")
            :control bulk-control}})

(defn reagent-segment-arm-ids
  "The arm ids THIS row's Reagent segment runs under `ratom?` — asked of
  the plan itself rather than restated beside it.

  Public because `?ratom=on` is a page-global flag while arm presence is
  row-specific, and until rf2-2rtt6.21 that distinction lived in two
  `cond->`s no test without a browser could reach. `M1` takes the arm from
  its witness's `:arms-for` and `:broad` from [[bulk-arms]]; `M2` ignores
  the flag and `:narrow` is never offered it."
  [row-key ratom?]
  (let [{:keys [kind witness]} (get row-specs row-key)]
    (mapv :id (if (= kind :mount)
                ((:arms-for (witness-named witness)) :reagent-subs ratom?)
                (bulk-arms :reagent-subs row-key ratom?)))))

(defn- query-row
  "Which row this page runs. One row per page (see [[row-specs]]); the
  driver loads the page once per row."
  []
  (let [s (or (some-> js/window .-location .-search) "")]
    (or (some (fn [k] (when (re-find (re-pattern (str "row=" (name k))) s) k))
              [:M1 :M2 :broad :narrow])
        :M1)))

(defn- run-round!
  "One round of THIS PAGE'S row: both segments, in this round's order —
  `start` leads round 0 and the order alternates from there. Answers a
  promise of `{segment-id readings}`."
  [{:keys [kind witness] :as _spec} row-key start ratom? r coll t legs]
  (lane/chain
    {}
    (segment-order start r)
    (fn [acc {:keys [id] :as segment}]
      (enter-segment! segment)
      ;; Checked immediately after the seam and before a single sample: a
      ;; segment entered over a frame that would not destroy is measuring
      ;; the previous segment's reactive graph.
      (assert-teardown-clean! (str "entering segment " (name id)))
      (if (= kind :mount)
        (js/Promise.resolve
          (let [rd (mount-round! coll t (witness-named witness) id ratom?)]
            (assert-teardown-clean! (str "mount round, segment " (name id)))
            (assoc acc id rd)))
        (let [mounts (mount-bulk-arms! (bulk-arms id row-key ratom?))]
          (-> (seed-bulk! mounts t)
              (.then (fn [_] (bulk-round! mounts t legs coll row-key id)))
              (.then (fn [rd]
                       (release-bulk-arms! mounts)
                       (assert-teardown-clean! (str "bulk round, segment " (name id)))
                       (assoc acc id (:readings rd))))
              (.catch (fn [e] (release-bulk-arms! mounts) (throw e)))))))))

(defn- leg-summary [legs]
  (into {}
        (map (fn [[k xs]]
               [k {:write-ms (:p50 (lane/summarise (map :write xs)))
                   :gap-ms   (:p50 (lane/summarise (map :gap xs)))
                   :force-ms (:p50 (lane/summarise (map :force xs)))}]))
        legs))

(defn- publish!
  [{:keys [kind witness row grade doc note control] :as _spec} row-key start
   slices t legs coll]
  (let [w   (witness-named witness)
        {:keys [record controls order leg-order]}
        (row-record {:row row
                     :grade grade
                     :doc (or doc (:doc w))
                     :clock-note (when (= kind :mount) (:clock-note w))
                     :note note
                     :control (or control (:control w))
                     :writes-per-sample (if (= row :bulk-narrow) narrow-batch-k 1)
                     :start start}
                    slices)]
    (lane/record! (name row) record)
    (lane/record! "verification" {row-key (lane/tally-value t)})
    (when (= kind :bulk) (lane/record! "write-legs" (leg-summary @legs)))
    (lane/record! "red-zone"
                  {:row row
                   :axis :clock
                   :witness-set "rf2-2rtt6.2"
                   :rounds rounds
                   :start-segment start
                   :threshold (select-keys (:red-zone record)
                                           [:mean :min :max :per-round :straddles-1?
                                            :claim :direction])
                   :segment-order-control order
                   :publishable (if (:magnitude-resolved? order)
                                  (str "a MAGNITUDE. The two segment-order strata overlap, "
                                       "so the mean survives the partition.")
                                  (str "a DIRECTION ONLY. The two segment-order strata are "
                                       "DISJOINT, so part of this figure is which segment "
                                       "ran first and the mean over them is not a "
                                       "threshold. Quote the direction and the stratum "
                                       "bounds; the order-balanced mean is "
                                       (:order-balanced-mean order) "x."))
                   ;; THE AGGREGATE RULE, stated on every row because the
                   ;; alternative is that it is assumed (rf2-6i0i2, the PR
                   ;; #7303 audit). `:publishable` above governs THIS run's
                   ;; mean quoted ALONE. It says nothing about ensembles, and
                   ;; the ten-run ensemble on the studio page averaged three
                   ;; `:direction-only` row-runs of forty into precise
                   ;; thresholds — correctly, but silently, which is the part
                   ;; that was wrong. So the row now answers both questions.
                   :in-an-ensemble
                   (str "ONE OBSERVATION, whatever `:publishable` says. An "
                        "ensemble mean takes EVERY launched run — the "
                        "`:direction-only` ones included — because dropping a "
                        "run for the way its strata happened to split selects "
                        "on the outcome: a disjoint split IS the extreme "
                        "partition, so excluding it shrinks the spread and "
                        "narrows the very interval it would be used to "
                        "compute. The ensemble's warrant is the LAUNCH COUNT, "
                        "stated as `N launched, N published`, and a run "
                        "dropped for reading badly makes the whole ensemble "
                        "worthless. What an ensemble may NOT do is inherit "
                        "this row's per-run gate as if it had passed it.")
                   :rule (str "RULING 1 on rf2-2rtt6.1: the red-zone threshold IS the "
                              "measured UIx ratio for that witness family. A candidate row "
                              "worse than it is RED and needs an explicit operator waiver "
                              "naming the dogfood benefit. Silence is not a pass. This "
                              "SUPERSEDES the clock threshold rf2-2rtt6.4 published on its "
                              "own witnesses — that figure remains sound as a ratio and is "
                              "simply not comparable with the bar rows.")})
    (when-let [leg (:reactive-leg record)]
      (lane/record! "reactive-leg"
                    {:row row
                     :axis :clock
                     :witness-set "rf2-2rtt6.2"
                     :rounds rounds
                     :start-segment start
                     :second-author-of "rf2-2rtt6.2 headline 1 — the price of the reactive system"
                     :leg (select-keys leg [:mean :min :max :per-round :straddles-1?
                                            :claim :direction :numerator :denominator])
                     :reagent-over-floor (:reagent-over-floor record)
                     :ratom-over-floor   (:ratom-over-floor record)
                     :segment-order-control leg-order
                     :publishable
                     (if (:magnitude-resolved? leg-order)
                       (str "a MAGNITUDE. The two segment-order strata overlap, so the "
                            "mean survives the partition.")
                       (str "a DIRECTION ONLY. The two segment-order strata are DISJOINT, "
                            "so part of this figure is whether the Reagent segment led "
                            "its round, and the mean over them is not a magnitude. Quote "
                            "the direction and the stratum bounds; the order-balanced "
                            "mean is " (:order-balanced-mean leg-order) "x."))
                     :first-author
                     (str "rf2-2rtt6.2 / rf2-2rtt6.17 published this leg three times off ONE "
                          "implementation of the arm: M1 mount 1.218 [1.122-1.310] / 1.216 "
                          "[1.185-1.241] / 1.213 [1.093-1.273], bulk broad 2.008 "
                          "[1.938-2.100] / 1.965 [1.875-2.000] / 2.073 [2.000-2.167]. Three "
                          "runs of one arm bound the instrument's noise and cannot bound a "
                          "systematic error in how the arm is written; this row is the "
                          "second implementation, and agreement or disagreement with those "
                          "figures is the finding either way.")}))
    (let [vd (lane/guard! (:samples @coll)
                          (str "Hicasso P0 converged — " (name row)))]
      (lane/record! "arm-order-guard"
                    {:row row
                     :tolerance (:tolerance vd)
                     :contaminated? (:contaminated? vd)
                     :unchecked? (:unchecked? vd)
                     :refuse? (:refuse? vd)
                     :arms (:arms vd)})
      (when (:refuse? vd) (set! (.-HICASSO_GUARD_REFUSED js/window) true)))
    (when (some (complement :ok?) controls)
      (set! (.-HICASSO_CONTROL_FAILED js/window) true))
    ;; The segment-order refusal is DIRECTIONAL disagreement, and it is
    ;; fatal for the same reason a failed positive control is: the row's
    ;; only surviving claim would be a measurement of the schedule.
    (when (:refuse? order)
      (set! (.-HICASSO_ORDER_REFUSED js/window) true))
    ;; And the reactive leg is held to exactly the same rule. It does not
    ;; cross the seam, but the Reagent segment still LEADS half the rounds
    ;; and follows the other half — a leg whose two strata point opposite
    ;; ways across 1.0 has measured the schedule, and a lower bound that
    ;; depends on when it was measured is not a lower bound.
    (when (:refuse? leg-order)
      (set! (.-HICASSO_ORDER_REFUSED js/window) true))
    ;; LAST, so every record above is on the console first. The count was
    ;; published and never adjudicated: this row could read `N unverified of
    ;; M` with N > 0 and the driver would still exit 0, which is what left
    ;; every read-back in this entry — the written cell, the mount's element
    ;; count, the far end of the page — decorative.
    (lane/assert-verified! t (str "row " (name row)))
    nil))

(defn ^:export -main
  []
  (try
    (lane/leave-act-environment!)
    (cond
      (not (lane/self-test!))
      (do (lane/fail! (str "the arm-order guard's SELF-TEST failed — this copy of the rule "
                           "no longer behaves like the one the .cjs drivers use, so nothing "
                           "was measured"))
          (lane/done!))

      (not (slot-order-varies-at-2?))
      (do (lane/fail! (str "slot-order has DEGENERATED at k=2 — it emits one order at every "
                           "sample index, which is the rf2-ouwh8 defect returning. This entry "
                           "runs three arms and is not itself affected, but the shared rule it "
                           "takes its schedule from is, so nothing here is measured until the "
                           "rule is repaired. The repair belongs to the schedule, never to the "
                           "tolerance"))
          (lane/done!))

      (not (slot-order-varies-at-3?))
      (do (lane/fail! (str "slot-order emits ONE order at k=3, so the three-arm plan this "
                           "entry runs is a single-order plan and `both orders` would be a "
                           "claim it cannot support"))
          (lane/done!))

      (not (slot-order-varies-at-4?))
      (do (lane/fail! (str "slot-order emits ONE order at k=4, so the four-arm Reagent "
                           "segment `?ratom=on` runs would be a single-order plan and the "
                           "reactive leg could not claim `both orders` either"))
          (lane/done!))

      :else
      (let [row-key (query-row)
            start   (query-start)
            ratom?  (query-ratom)
            spec    (get row-specs row-key)
            w       (witness-named (:witness spec))
            ;; ONCE, at boot, and NOT at every segment entry. The mount
            ;; arms never write `ratom-cells` and the bulk arms re-seed
            ;; through their own `write!`, so a per-segment reset would buy
            ;; nothing and would cost something real: Reagent's disposals
            ;; sit on a macrotask queue, so a write at the seam can land on
            ;; reactions whose roots have unmounted but not yet been
            ;; disposed, and every one of them would re-render. Outside the
            ;; window, but it is work the page does not need to do.
            _       (reset! v/ratom-cells (:cells (v/seed-cells v/cells-n 0)))
            {:keys [problems]} (parity! w ratom?)]
        (js/console.log (str ";; HICASSO row " (name row-key)
                             " — round 0 led by " (name start) ", alternating"
                             (when ratom?
                               (str "; :reagent-ratom REQUESTED — this row's Reagent "
                                    "segment runs "
                                    (pr-str (reagent-segment-arm-ids row-key true))
                                    " (rf2-2rtt6.21)"))))
        (lane/record! "parity"
                      {:row row-key :problems problems :ok? (empty? problems)
                       :note (str "canonical DOM with attribute names sorted, inside each "
                                  "segment AND across the seam. The element count is checked "
                                  "against written arithmetic — " (base-elements w) " for "
                                  (name (:id w)) ", and the arm's OWN scale for the "
                                  "control — so the gate can answer false for an arm that "
                                  "rendered nothing, and for a 2x control that rendered "
                                  "only its base prefix.")})
        (if (seq problems)
          (do (lane/fail! (str "the arms do not build the same page under :advanced — "
                               (pr-str problems)))
              (lane/done!))
          (let [coll (lane/sample-collector)
                t    (lane/tally)
                legs (atom {})]
            (-> (lane/chain [] (range rounds)
                            (fn [acc r]
                              (-> (run-round! spec row-key start ratom? r coll t legs)
                                  (.then (fn [slice] (conj acc slice))))))
                (.then (fn [slices]
                         (publish! spec row-key start slices t legs coll)
                         (lane/done!)
                         nil))
                (.catch (fn [e]
                          (lane/fail! (str "the run rejected: " e))
                          (lane/done!))))))))
    (catch :default e
      (lane/fail! (str "the run threw: " e))
      (lane/done!))))
