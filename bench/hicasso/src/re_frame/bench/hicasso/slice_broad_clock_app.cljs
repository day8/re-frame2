(ns re-frame.bench.hicasso.slice-broad-clock-app
  "THE SLICE'S BROAD-UPDATE CLOCK, WITH A DONOR ARM BESIDE IT — one broad
  application operation, through to the paint that follows it, taken on
  the Hicasso page and on the UIx page that renders the same feed
  (rf2-9wmqd).

      HICASSO_INIT_FN=re-frame.bench.hicasso.slice-broad-clock-app/-main \\
      HICASSO_OUT_DIR=out/hicasso-slice-broad \\
      HICASSO_PORT=8139 \\
        node implementation/hicasso/test/re_frame/bench/hicasso/run.cjs

  NO NEW BUILD ID. `run.cjs` takes its entry from `HICASSO_INIT_FN` and
  rides `:hicasso-bench`, the id the whole lane already shares, so this
  arm costs `implementation/shadow-cljs.edn` — an HD-017 hot-zone file —
  nothing.

  ## WHY THERE IS A SECOND DRIVER AT ALL

  [[re-frame.bench.hicasso.slice-echo-clock-app]] is the first, and its
  own docstring states what it does not serve: **`U3` and `U4` are not in
  its row set, and it carries no donor arm at all**, so `C3` and `C4`
  have nothing to compare against. This driver takes the first of those
  three — `U3`, *operation latency* for a broad application operation —
  and supplies the donor arm `C3` and `C4` are stated on.

  It does NOT take `U4`. §THE ROW SET below says why, at source, and the
  reason is about the slice rather than about this mechanism.

  ## IT IS THE SAME WINDOW, NOT A COPY OF IT

  `window!` and `after-paint` are [[re-frame.bench.hicasso.slice-echo-clock-app]]'s,
  REQUIRED rather than transcribed, and so are its `measured-mask` and
  `structure-over-measured`. This lane pays for local copies deliberately
  where the copy is of SHIPPING code (the rf2-2rtt6.32 call-convention
  discipline), and that reason does not reach a sibling arm: two copies
  of one bench mechanism is two things that can drift with nothing
  holding them in step, which is the shape `rf.bench.hicasso.lane/visit-plan`'s own
  docstring is written against. So the two drivers cannot disagree about
  what a window is, and a repair to the window reaches both.

  Every reading is therefore `t0` → real DOM event → `requestAnimationFrame`
  → `setTimeout 0` → `t-paint`, decomposed into `:commit`, `:to-raf` and
  `:raf-to-paint`, exactly as over there.

  ## THE ROW SET, AND WHY IT IS THE ONE IT IS

      :idle-frame     no interaction at all — the frame and nothing else,
                      on the Hicasso page. The FLOOR every other row pays.

      :locale         a real `change` on the published `<select>`, which
                      moves ONE key in `app-db` and re-renders every
                      boundary on the page that read a string. `U3`'s
                      estimand — operation latency for a broad
                      application operation.

      :theme          a real click on the published theme button, which
                      moves one key and re-renders every boundary that
                      read a token. `U3`'s estimand on a second event
                      path, and a NARROWER broad update — which is the
                      point of having both.

      :donor-locale   `:locale`, on the UIx page.
      :donor-theme    `:theme`, on the UIx page.

      :ctl-blocked    `:locale` plus a busy-wait of [[blocked-ms]] on the
                      window's post-commit seam. The positive control.

  ### `U3`'S THREE NAMED OPERATIONS, AND WHY ONLY ONE CLASS OF THEM IS HERE

  The first driver names *a route change, a reset, a save reply* as the
  broad operations `U3` wants. **Two of those three cannot be bracketed
  by this window at all, and the reason is mechanical rather than a
  matter of taste.**

  `re-frame.hicasso.examples.slice.flow-dom-cljs-test`'s namespace
  docstring, §*TWO CLICKS, TWO SETTLING RULES*, records the split as a
  finding of the slice's own authoring report:

  - A **Hicasso intent** — the `:on-change` and `:on-click` vectors this
    driver's two measured operations fire — dispatches through the
    runtime's **synchronous** frame-locked door, so the handler has run,
    `app-db` has moved and React has committed before the DOM event
    returns. That is exactly the operation `window!` is shaped for.
  - A **route-link** click ends at `re-frame.routing/activate-link!`,
    which ends at `router/dispatch!` — **the ASYNC door**. The click
    returns with the navigation merely ENQUEUED, and the router drains it
    on `interop/next-tick`, a next-turn TASK. A window that stops at the
    first paint after the click stops BEFORE THE OPERATION HAS BEGUN.
  - **Every async mutation reply in any re-frame2 application arrives
    through a router drain**, in that docstring's words, so a save reply
    is the same case and so is the digest's reload.

  A row over either would not be a slower reading of `U3`, it would be a
  reading of the click that requested the operation — the same class of
  error as a `p95` of a mount published against a line about a paint,
  which is the error the first driver exists to have stopped making. So
  the async-door operations are OUT, and what they need is a different
  window — one bounded by the drain AND the paint that follows it — which
  is not this mechanism and is not built here.

  The third of the three, a **reset**, is synchronous and is reachable.
  It is not here because it lives on the ARTICLE route, behind a draft
  that has to be dirtied outside the window, and this page is the feed —
  see §THE PAGE below. It is named so it is not rediscovered.

  ### `U4` IS NOT SERVED BY THIS DRIVER EITHER, AND NOT FOR THE SAME REASON

  `U4` is registered over *dragging/animation stay inside frame budget*,
  estimand *per-frame latency*. **The slice application has no drag and no
  animation**: every interaction it publishes is discrete — a keystroke, a
  click, a select. A per-frame estimator driven by repeating one of those
  once a frame would be measuring a scripted repetition of a discrete
  interaction and publishing it against a line written about a continuous
  one, and this programme has refused that shape before.

  Adding a drag to the slice is not the repair either: it would move the
  population, and `U1`–`U4` are governed on the application's own
  interactions rather than on interactions added to reach a row.

  **The repair exists and it is another witness application.**
  `re-frame.hicasso.examples.ledger` publishes a virtualized 10,000-row
  list over a real scroll viewport — its `virtualized-dom-cljs-test`
  drives `scrollTop` and a real `scroll` event, and the vendor artefact's
  own note names *host-local animation/drag state* — which is a genuinely
  CONTINUOUS interaction and the population `U4` is stated over. A
  per-frame driver belongs there, on this same `window!` mechanism but
  with an estimator over FRAME INTERVALS rather than over window lengths.
  It is a third driver on a third page, and it is not this one.

  ## THE PAGE, AND WHY THE DONOR RENDERS EXACTLY IT

  Both arms open on the slice's FEED route, at the shipped seed: seven
  articles at a page size of three, so three pages, a pager with both
  ends reachable, and the editor's digest with its five blocks — one of
  them of a kind this build has no renderer for. Eleven boundaries.

  The first driver sits on the ARTICLE route because its estimands are
  the editor's controlled fields. This one takes the other route for the
  reason that driver gives: *the route the editor rows type into is the
  route a navigation row would leave*. Two drivers, two pages, no
  preparation crossing between them.

  [[re-frame.bench.hicasso.slice-donor-views]] renders the same feed page
  in UIx, reading the same subscriptions and dispatching the same events.
  Its own docstring carries the donor's argument — why UIx and not
  Reagent, what makes it a denominator rather than a strawman, and the
  three places the two arms are NOT the same.

  ## THE FAIRNESS GATE RUNS BEFORE ANY CLOCK, AND IT HAS A NEGATIVE CONTROL

  `rf.bench.hicasso.lane/canonical` is this lane's one answer to *are these two arms
  building the same page* — attribute names sorted, so the comparison is
  of the DOM and not of two serialisers. [[assert-parity!]] takes it over
  both containers on the seeded page and REFUSES the run when they
  differ.

  A gate nobody has seen refuse is a gate nobody should trust, so
  [[parity-discrimination!]] moves the DONOR frame's locale to French,
  outside every window, and requires the same comparison to DISAGREE —
  then puts it back and requires it to agree again. Two settles, no
  clock, and the run stops if the gate cannot tell two pages apart.

  **A dev-mode compile is expected to REFUSE that gate, and correctly.**
  `data-rf2-source-coord` carries each boundary's own source position, so
  under `goog.DEBUG` the two arms' attributes differ by construction and
  the pages are not the same page. `run.cjs` builds this lane in
  `release` mode, where the emit is DCE'd on both sides.

  ## AND WHAT THE GATE CANNOT SEE IS WHAT [[pre-state]] IS FOR

  The gate runs ONCE, on the seeded page, before the first window. Every
  arm moves its own page afterwards, and the arms are not symmetric across
  the two frames — so two arms that started on the same page can spend the
  run on different ones, and nothing in the paragraph above would notice.

  PR #8599's audit measured exactly that on this file's first cut: at
  `{:warmup 8 :samples 12}`, `:theme` ran in the non-seed locale on 4 of
  its 12 measured visits per round and `:donor-theme` on 8 of 12, because
  two arms move the Hicasso frame's locale and only one moves the donor's.
  The comparative theme figure was therefore a ratio between two
  populations. [[pre-state]] closes it by making each arm's page state a
  pure function of its own visit index, and
  `slice-broad-window-dom-cljs-test` asserts the closure over a replay of
  the schedule rather than trusting this paragraph.

  ## THE ECHO IS READ OFF SOMETHING THE INTERACTION CANNOT HAVE WRITTEN

  The first driver's rule, applied to two different operations. A
  scripted interaction sets its control up by mutating it, so the
  control's own state is the one thing the echo may not be read over.

  - **`:locale`** writes the `<select>`'s value through the pristine
    `HTMLSelectElement.prototype` setter and then checks the **`<h1>`'s
    text** — the application's title, in the target language. Nothing but
    a React commit puts `Chronique` on that heading, and the target is
    always the locale the page is NOT in, so a stale page cannot pass by
    carrying the previous sample's answer.
  - **`:theme`** clicks a real button and then checks the **root
    element's inline `background-color`** against the target theme's
    `:surface` token. A button's activation behaviour writes no style.

  Both checks are adjudicated per sample, banked into `rf.bench.hicasso.lane/tally`, and
  `rf.bench.hicasso.lane/assert-verified!` refuses the run at `N unverified of M`.

  ## THE NEGATIVE CONTROLS ON THE ECHOES, TAKEN ONCE AT BOOT

  [[echo-discrimination!]] runs four windows before anything is measured
  and requires all four to REFUSE:

  - the two `:locale` arms' **setup mutation alone** — the native value
    write with no `change` event, and therefore no handler, no dispatch,
    no state write and no commit;
  - the two `:theme` arms with **no interaction at all**, asked for the
    other theme's token.

  The theme arms get the weaker of the two shapes and that is stated
  rather than smoothed: a click IS the whole interaction there, so there
  is no setup mutation to keep while the event is suppressed. What their
  control establishes is that the check distinguishes *a commit happened*
  from *nothing happened*, which is the conjunct a broken arm would fail.

  ## THE POSITIVE CONTROL ADJUDICATES THE WINDOW, NOT THE SENSITIVITY

  `:ctl-blocked` is `:locale` plus [[blocked-ms]] of blocked main thread
  spent AFTER React's commit has returned and BEFORE the browser can
  produce a frame. Its prediction is additive and needs no model of the
  application: the window must lengthen by [[blocked-ms]], to within the
  one rendering interval the frame grid rounds it to ([[control-slack]]).
  A commit-bounded window would not see one millisecond of it, so the
  control catches a rig that had quietly stopped measuring a paint.

  ONE control, on the Hicasso side only, and that is enough because there
  is one window: both arms run the same `window!` over the same
  mechanism. What a donor-side fault would look like is not a blind
  window but an interaction that never reached the donor application, and
  the donor's own per-sample echo and its boot negative control are what
  adjudicate that.

  ## WHAT THIS FILE PUBLISHES, AND WHAT IT REFUSES TO

  Per arm: `{:n :min :max :p50 :p95 :p99}`, the `:commit` / `:to-raf` /
  `:raf-to-paint` decomposition over the measured visits, the guard's
  verdict, the control's verdict and the runtime label. Then two
  comparative figures — `:locale / :donor-locale` and
  `:theme / :donor-theme`, per round and as a range, through
  `rf.bench.hicasso.lane/ratio-between` — every arm's range against the floor through
  `rf.bench.hicasso.lane/across-rounds`, and per pair what size of difference the run
  could have SEEN, through `rf.bench.hicasso.lane/resolution`.

  **No line is applied to any of them.** `U3`'s 100 ms `p95`, `C3`'s
  `1.25x` and `C4`'s `1.5x` appear nowhere in this file and none of them
  should: `budgets.md` §7 routes every distributional row to pinned
  evidence runs on P-DEV-1 and forbids converting one into a pull-request
  threshold, §9.1 says such a row may never name the first lane, and
  `check_budget_ledger.py` enforces both. The only pass/fail this file
  makes are the INSTRUMENT-INTEGRITY verdicts above — the parity gate,
  the four echo controls, the echo tally, the arm-order guard and the
  positive control — and §2's rule holds for every one of them: they are
  structural, not distributional.

  ## THE ONE THING A READER MUST CHECK BEFORE QUOTING ANY RATIO HERE

  **A paint-bounded window is dominated by the wait for the browser's
  next rendering opportunity, and that wait is in every arm.** Two arms
  that each wait one interval read `1.00x` whatever their substrates
  cost, so a `C3` ratio taken over `:summary` on a small page can be
  `1.00x` for a reason that has nothing to do with either substrate.

  That is why `:over-floor` is published beside the comparative figures.
  It is `rf.bench.hicasso.lane/across-rounds` over each arm's ratio to `:idle-frame`, and
  its `:straddles-1?` flag is the FIRST of two tests: an arm whose range
  against an EMPTY FRAME includes 1.0 was not separated from the floor by
  this window, and no ratio between two such arms is a reading about
  anything. The `:structure` block's `:commit` leg is the other half —
  the application's own work, outside the frame grid entirely.

  ## AND THE SECOND TEST, BECAUSE THE FIRST IS NOT SUFFICIENT

  **`:straddles-1?` asks whether ONE arm cleared the floor. A bar row
  asks whether TWO arms separate from EACH OTHER at a stated line, and a
  pair can answer yes to the first and no to the second.** That is not
  hypothetical: under `rf2-9wmqd` the `:locale` pair cleared the gate in
  all three evidence runs and still could not resolve `1.25x`, because
  the same `~96%` frame grid that flattens the ratio also flattens any
  difference in it. A reader applying only `:straddles-1?` would have
  quoted that as a pass.

  So `:resolution` is published beside `:comparative`, one entry per
  pair, from `rf.bench.hicasso.lane/resolution` — see that function for the arithmetic.
  Its `:resolves-at` is the smallest difference in the ARMS' OWN WORK
  whose effect on the published ratio would have been as large as the
  scatter this run actually showed. **Read it against the line YOUR row
  is stated at**: a row read at `1.25x` off a run whose `:resolves-at` is
  `1.85` is quoting the frame grid, whatever the ratio says. It carries
  no line and no verdict of its own, and `budgets.md` §7 is why — every
  distributional row is adjudicated in a pinned evidence run, never here.

  **If the shipped seed cannot separate the measured arms from the floor,
  the honest conclusion is that `C3` needs a broader population than the
  slice's feed page** — which is a finding about the row, and never a
  licence to widen a band or to quote an unresolved ratio. A pair that
  clears the floor but whose `:resolves-at` sits above the row's line is
  the same finding reached one step later.

  Owner: rf2-9wmqd."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.slice-donor-views :as rf.bench.hicasso.slice-donor-views]
            [re-frame.bench.hicasso.slice-echo-clock-app :as rf.bench.hicasso.slice-echo-clock-app]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.examples.slice.db :as rf.hicasso.examples.slice.db]
            [re-frame.hicasso.examples.slice.events :as rf.hicasso.examples.slice.events]
            [re-frame.hicasso.examples.slice.i18n :as rf.hicasso.examples.slice.i18n]
            [re-frame.hicasso.examples.slice.routes :as rf.hicasso.examples.slice.routes]
            [re-frame.hicasso.examples.slice.views :as rf.hicasso.examples.slice.views]
            [uix.dom :as uix-dom]))

;; ---------------------------------------------------------------------------
;; The knobs — every one of them a SCHEDULE knob, never a line
;; ---------------------------------------------------------------------------

(def sampling
  "Per-round warm-up and measured counts. `rf2-h904p`'s values, carried
  rather than chosen, and the sibling driver's — a comparative figure
  taken on a different schedule from the row it sits beside is a
  comparison a reader has to reconcile before they can read it.

  **A tail quantile over 12 is mostly interpolation** (`rf.bench.hicasso.lane/quantile`
  prices it), so the run that reads this instrument will want more, and
  raising these two is how it gets them.

  RAISING THEM IS SAFE HERE, and that is a property of [[pre-state]]
  rather than of these numbers. Each arm's page state is a function of its
  own visit index, so two paired arms see the same state mix at every
  `:samples` — where an instrument that let the arms inherit each other's
  state has mixes that depend on how `slot-order`'s rotation lands on the
  count, and equalises at 12 while diverging at 13."
  {:warmup 8 :samples 12})

(def rounds
  "Rounds per run. Five is the lane's standard and what `across-rounds`
  and `control-verdict-strict` are shaped for."
  5)

(def blocked-ms
  "How long `:ctl-blocked` blocks the main thread after the commit and
  before the frame.

  Chosen against the FRAME INTERVAL rather than by taste, and the
  sibling's reasoning is the whole of it: a paint-bounded window is
  quantised by the display's rendering opportunities — roughly 16.7 ms at
  60 Hz — so an injected cost much smaller than one frame can be absorbed
  by the quantisation entirely. 50 ms is three frames."
  50.0)

(def control-slack
  "The half-width of `:ctl-blocked`'s band, as a fraction of
  [[blocked-ms]]. Derived from the frame grid, not tuned to a
  measurement: every window ends at the first rendering opportunity at or
  after its own work, so the injected duration reaches the difference
  ROUNDED to the grid. `0.5` of 50 ms is ±25 ms, which covers one
  interval with room for a grid that is not exactly 60 Hz.

  It is [[re-frame.bench.hicasso.slice-echo-clock-app/control-slack]]'s
  value and its argument. Stated here rather than aliased because a band
  is a claim about THIS instrument's control, and an instrument whose
  band moves when a sibling's moves is an instrument nobody can
  re-adjudicate."
  0.5)

(def hicasso-frame
  "The Hicasso arm's DEFAULT frame id. Its own, because frames are
  isolated contexts: the two arms hold two copies of the same seeded
  `app-db` and neither can see the other's.

  A DEFAULT rather than a constant, because [[boot!]] takes a pair of ids
  and a caller that mounts REPEATEDLY IN ONE PROCESS must hand it fresh
  ones — see [[boot!]]'s §MOUNTING TWICE. The bench page mounts once and
  takes these."
  ::hicasso-frame)

(def donor-frame
  "The UIx arm's default frame id. [[hicasso-frame]] carries why it is a
  default."
  ::donor-frame)

(def initial-events
  "What both arms are seeded with, in order.

  ONE definition for both, because the two arms differing in their seed
  is the failure the canonical-DOM gate would report as a page difference
  and a reader would spend an afternoon on. It is the slice's own pair
  from `app.cljs`, opened on the feed rather than on an article."
  [[::rf.hicasso.examples.slice.events/seed]
   [:rf.route/navigate {:to rf.hicasso.examples.slice.routes/feed}]])

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private !state
  (atom {:hicasso-container nil
         :hicasso-handle    nil
         :donor-container   nil
         :donor-root        nil
         :echo-tally        nil
         :first-refusal     nil
         :frames            nil
         :visits            {}
         :aux               {}}))

(defn verification
  "`{:writes M :unverified N}` over every window taken since the last
  [[boot!]] — warm-up visits included, because a verification is worth
  more the more of them there are."
  []
  (rf.bench.hicasso.lane/tally-value (:echo-tally @!state)))

(defn visits
  "`{arm-id n}` — how many windows each arm has taken since the last
  [[boot!]], warm-up visits included.

  A COUNT, not the last index. [[pre-state]] is a function of the INDEX,
  which is one less, and [[claim-visit!]] is the one place that conversion
  happens. The distinction is worth a line because the first cut of this
  reader published the index under this docstring's words, and the suite
  row that compares it against `rf.bench.hicasso.lane/visit-plan`'s per-arm visit count is
  what found the disagreement.

  Exposed because a suite that wants to check the pre-state the driver
  actually established against the one the schedule predicts needs to see
  the counter as well as the rule."
  []
  (:visits @!state))

(defn frames
  "`{:hicasso id :donor id}` — the frame ids THIS boot mounted on.

  Read this rather than [[hicasso-frame]] / [[donor-frame]]: those are
  defaults, and a caller that mounts repeatedly hands [[boot!]] fresh ones
  for the reason its §MOUNTING TWICE gives."
  []
  (:frames @!state))

(def populations
  "Which visits each published figure is taken over.

  `:summary`, `:structure`, `:comparative`, `:over-floor` and
  `:resolution` are all taken over the MEASURED visits, because each of
  the last three is built out of the first two and a ratio whose
  numerator and denominator are drawn from different populations is not
  a ratio. `:echo` is the verification tally and covers every window,
  warm-up included: it is a count of refusals rather than a
  distribution, so it decomposes nothing."
  {:summary     :measured-visits
   :structure   :measured-visits
   :comparative :measured-visits
   :over-floor  :measured-visits
   :resolution  :measured-visits
   :echo        :all-visits})

;; ---------------------------------------------------------------------------
;; The glass
;; ---------------------------------------------------------------------------

(def ^:private pristine-select-value-setter
  "`HTMLSelectElement.prototype`'s own `value` setter, captured lazily.

  The sibling's `HTMLInputElement` capture, one element type over — but
  **NOT for the sibling's reason, and the difference is measured rather
  than assumed.** Over there the prototype write is load-bearing: React
  tracks a controlled text input's value on an instance-level descriptor,
  so assigning through the instance leaves the following `input` looking
  like a no-op change and the handler never runs. A `<select>` is not on
  that path. `react-dom` 19.2's change plugin sends a `select`'s native
  `change` to `getTargetInstForChangeEvent`, which is `if (\"change\" ===
  domEventName) return targetInst;` and consults no tracker at all — the
  tracker is read on the TEXT-INPUT branch beside it. A plain
  `(set! (.-value node) v)` would produce a real change here.

  So this is DEFENSIVE and it is kept anyway, because the alternative is
  two doors: the two drivers would write a control's value from outside
  React in two different ways, one of them correct for a reason the other
  does not share, and the next arm would copy whichever it read first.

  A `delay` and not a `def` of the setter itself: this namespace's top
  level runs wherever it is loaded, and `js/HTMLSelectElement` does not
  exist on the node lane."
  (delay (.-set (js/Object.getOwnPropertyDescriptor
                  js/HTMLSelectElement.prototype "value"))))

(defn- set-native-select-value! [node v]
  (.call @pristine-select-value-setter node v))

(defn- container-for [side]
  (case side
    :hicasso (:hicasso-container @!state)
    :donor   (:donor-container @!state)))

(defn- node-at [side sel]
  (some-> (container-for side) (.querySelector sel)))

(defn- locale-select [side] (node-at side "#slice-locale"))
(defn- title-heading [side] (node-at side "h1.slice-title"))
(defn- page-root     [side] (node-at side "main.slice"))
(defn- theme-buttons [side]
  (some-> (container-for side) (.querySelectorAll "button.theme-choice") array-seq))

;; ---------------------------------------------------------------------------
;; The two echoes
;; ---------------------------------------------------------------------------

(def locales
  "The two locales the row alternates between, in `i18n/locales`' order.
  A vector rather than a set so *the other one* is arithmetic."
  (vec rf.hicasso.examples.slice.i18n/locales))

(defn- other-locale [now]
  (first (remove #(= % now) locales)))

(defn locale-echo-check
  "The observation the `:locale` arms take: is the application's own
  `<h1>` showing the target locale's title?

  `:rendered` is the discriminating half — only a React commit writes the
  heading's text. `:glass` is the `<select>`'s own value, carried beside
  it because the control keeping the value this file wrote is a real
  fact whose opposite is a real regression, and a reader of a refusal
  needs to see which of the two went."
  [side want]
  {:expect   (rf.hicasso.examples.slice.i18n/t want :app/title)
   :rendered (some-> (title-heading side) .-textContent)
   :glass    (some-> (locale-select side) .-value)
   :want     (name want)})

(defn- locale-echo-verified? [{:keys [expect rendered glass want]}]
  (and (= expect rendered) (= want glass)))

(defn theme-echo-check
  "The observation the `:theme` arms take: has the page root's inline
  background moved to the target theme's `:surface` token?

  The root's `style` attribute is written by React and by nothing else on
  this page — a button's activation behaviour writes no style — so the
  check needs no second place to read from and no sentinel to scribble.
  The token values are already CSSOM's own serialisation
  (`\"rgb(255, 255, 255)\"`), so this is an equality and not a parse."
  [side want]
  {:expect   (rf.hicasso.examples.slice.i18n/token want :surface)
   :rendered (some-> (page-root side) .-style .-backgroundColor)
   :want     (name want)})

(defn- theme-echo-verified? [{:keys [expect rendered]}]
  (and (some? expect) (= expect rendered)))

(defn- note-refusal!
  "Keep the FIRST refusal's detail, so a run that dies on `N unverified of
  M` says which arm, which conjunct and against what. `rf.bench.hicasso.lane/tally` counts
  and does not describe."
  [arm-id echo]
  (swap! !state update :first-refusal
         (fn [prev] (or prev (assoc echo :arm arm-id))))
  nil)

;; ---------------------------------------------------------------------------
;; The arms
;; ---------------------------------------------------------------------------

(defn- idle-plan
  "The floor. No interaction at all — what one settle costs when the
  application does nothing. `:echo` is `:n/a` and banks as verified,
  because there is no echo to be late.

  Taken on the Hicasso container, and one floor rather than two: the wait
  for a rendering opportunity is the browser's and belongs to neither
  arm. A second floor on the donor page would measure the same grid and
  invite a reader to divide one by the other."
  [_]
  {:interact!        (fn [] nil)
   :observe-at-frame (fn [] {:verified? true :echo :n/a})})

(defn locale-plan
  "Switch one arm's display language: write the OTHER locale onto the
  `<select>` through the prototype's setter, then fire a real `change`.

  ALTERNATING and not fixed. The target is always the locale the page is
  not in, so a window that closed too early cannot pass the echo check by
  carrying the previous sample's answer — the sibling's rotor property,
  over a roster of two.

  WHAT ALTERNATES IS THE PRE-STATE, and this body reads the result rather
  than deciding it: [[pre-state]] puts the frame in the seed locale on
  even visits and the other one on odd ones, so `now` alternates and the
  target with it. Each arm therefore takes both directions in equal
  numbers over any even block of visits, which used to be a consequence of
  the plan and is now a statement about the arm."
  [side]
  (fn [_]
    (let [select (locale-select side)
          now    (keyword (.-value select))
          want   (other-locale now)]
      {:interact!        (fn []
                           (set-native-select-value! select (name want))
                           (.dispatchEvent select (js/Event. "change" #js {:bubbles true})))
       :observe-at-frame (fn []
                           (let [seen (locale-echo-check side want)]
                             {:verified? (locale-echo-verified? seen) :echo seen}))})))

(def themes
  "The themes the application's own table names, as a sequence.

  Derived from `i18n/themes` rather than written out, so a theme added
  there cannot leave this file quietly timing a two-theme page. ORDER IS
  NOT RELIED ON and could not be — a map has none. The only question
  asked of this roster is *which member is the page not wearing*, which
  is answered by the token values and not by position."
  (vec (keys rf.hicasso.examples.slice.i18n/themes)))

(defn theme-state
  "`{:target <button> :want <theme>}` for one arm: the button to click,
  and the theme the page will be in once the click has committed.

  ## NEITHER FACT IS READ OFF A LABEL OR OFF AN INDEX

  A theme button carries no id and no data attribute — its markup is a
  class, a type, an intent and a TRANSLATED label — so the label cannot
  name the theme (it is `Dark` in one locale and `Sombre` in another, and
  the `:locale` arm moves the page between them). Position could name it,
  because the view places `[:light :dark]` in that order, but that is a
  literal inside a body this file does not own and a reordering there
  would silently swap this arm's target.

  So both facts come off the page instead: the BUTTON is the one the
  application did not mark `current`, and the WANTED THEME is the one
  whose `:surface` token is not the colour the root is wearing. Two
  independent reads of the same fact, which is what makes the pair worth
  asserting against each other.

  REFUSES rather than guessing when the page is not in a state either
  read can answer — a roster that is not two buttons, a `current` mark on
  none or both, or a root wearing a colour no theme in the table names.
  Each of those is a page this arm has no business timing, and a plan
  that quietly picked `:light` would time it anyway."
  [side]
  (let [buttons (vec (theme-buttons side))
        current? (fn [^js b] (.contains (.-classList b) "current"))
        marked  (filterv current? buttons)
        now-bg  (some-> (page-root side) .-style .-backgroundColor)
        want    (first (remove (fn [th] (= (rf.hicasso.examples.slice.i18n/token th :surface) now-bg)) themes))]
    (when-not (= 2 (count themes))
      (throw (ex-info (str "the application's theme table names " (count themes) " themes, and "
                           "this arm's whole rule for choosing a target is *the one the page "
                           "is not wearing*, which answers nothing at any other roster size")
                      {:rf.error/id ::theme-table-unexpected
                       :themes      themes})))
    (when-not (= 2 (count buttons))
      (throw (ex-info (str "the " (name side) " arm's theme switch is not two buttons — it has "
                           (count buttons) ", so there is no `the other one` to click")
                      {:rf.error/id ::theme-roster-unexpected
                       :side        side
                       :buttons     (count buttons)})))
    (when-not (= 1 (count marked))
      (throw (ex-info (str "the " (name side) " arm marks " (count marked) " theme buttons "
                           "`current`, and exactly one of them is the page saying which "
                           "theme it is in")
                      {:rf.error/id ::theme-current-unexpected
                       :side        side
                       :marked      (count marked)})))
    (when-not (some (fn [th] (= (rf.hicasso.examples.slice.i18n/token th :surface) now-bg)) themes)
      (throw (ex-info (str "the " (name side) " arm's root is wearing " (pr-str now-bg)
                           ", which is neither theme's :surface token, so the theme echo "
                           "has no target it could be checked against")
                      {:rf.error/id ::theme-surface-unknown
                       :side        side
                       :observed    now-bg
                       :tokens      (mapv #(rf.hicasso.examples.slice.i18n/token % :surface) themes)})))
    {:target (first (remove current? buttons))
     :want   want}))

(defn theme-plan
  "Switch one arm's theme: a real click on whichever of the two theme
  buttons is not the current one.

  `HTMLElement.click()` and not a synthesised event: the user agent's
  activation behaviour is what a user's click runs, and there is no
  tracked value to leave stale — a button is not a controlled input.

  ALTERNATING, like the locale arm and for the same reason: the target is
  always the theme the page is not in, so a window that closed too early
  cannot pass by carrying the previous sample's answer. [[pre-state]] is
  what alternates, and [[theme-state]] reads the page it left."
  [side]
  (fn [_]
    (let [{:keys [target want]} (theme-state side)]
      {:interact!        (fn [] (.click target))
       :observe-at-frame (fn []
                           (let [seen (theme-echo-check side want)]
                             {:verified? (theme-echo-verified? seen) :echo seen}))})))

(defn- busy-wait!
  "Block the main thread for `ms`. A spin and not a `setTimeout`, because
  the control has to occupy the interval the window is measuring rather
  than yield it."
  [ms]
  (let [end (+ (rf.bench.hicasso.lane/now-ms) ms)]
    (loop [] (when (< (rf.bench.hicasso.lane/now-ms) end) (recur)))))

(defn- blocked-plan
  "`:locale` on the Hicasso arm, plus [[blocked-ms]] of blocked main
  thread on `window!`'s `after-commit!` seam.

  WHERE THE COST SITS IS THE CONTROL. Injecting it inside `interact!`
  would put it before `t-commit`, where a `flushSync` window would see
  every millisecond of it and the control would prove only that the
  instrument can measure a busy loop. On the seam it lands strictly
  between the commit and the frame — invisible to the window this one
  replaces, and fully inside this one.

  It rides the LOCALE arm rather than the theme arm because
  [[control-per-round]] subtracts the two, and a control paired with the
  broader of the two operations is the pair whose difference is dominated
  by the injected duration rather than by the operations' own spread.

  A SUBTRACTION IS A COMPARATIVE, so this arm is bound by the same
  requirement the two published ones are: it declares `:locale` at
  [[pre-state]] exactly as `:locale` does, and the two therefore run over
  one population. Before that rule existed they did not — the audit's
  replay puts this arm in the non-seed THEME on 8 of 12 measured visits
  per round against `:locale`'s 4 of 12, the same divergence as the
  published theme pair with the dimensions swapped."
  [state]
  (assoc ((locale-plan :hicasso) state)
         :after-commit! (fn [] (busy-wait! blocked-ms))))

;; ---------------------------------------------------------------------------
;; The pre-state — what each arm's page is in BEFORE its window opens
;; ---------------------------------------------------------------------------

(defn- settle!
  "Two frames, outside every window. React's commit and the initial
  events' drain are not the same tick."
  []
  (.then (rf.bench.hicasso.slice-echo-clock-app/after-paint) (fn [_] (rf.bench.hicasso.slice-echo-clock-app/after-paint))))

(def seed-locale
  "The locale both frames open in, taken from the application's own seed
  rather than written out. A literal `:en` here would be a second
  authority for a fact `db/seed` already states, and the first thing to go
  stale when the slice ships a third locale and changes its default."
  (:locale rf.hicasso.examples.slice.db/seed))

(def seed-theme
  "The theme both frames open in, from the same seed and for the same
  reason."
  (:theme rf.hicasso.examples.slice.db/seed))

(defn- other-theme
  "The theme the application's table names that is not `now`. The same
  rule [[theme-state]] applies to the RENDERED page, applied to a value —
  *the one it is not* — so neither depends on the map's order."
  [now]
  (first (remove #(= % now) themes)))

(defn pre-state
  "`{:locale l :theme t}` — the state `arm`'s own frame is put into before
  its `visit`th window, counting from zero.

  ## WHY AN ARM ESTABLISHES ITS PRE-STATE INSTEAD OF INHERITING IT

  Every measured arm moves ONE dimension of its page and reads the other.
  Left to inherit, what it reads is whatever the arms scheduled before it
  happened to leave — and the arms are not symmetric across the two
  frames, so the two halves of a comparative are not drawn from the same
  population.

  PR #8599's audit measured it on this file's own roster. `:locale` and
  `:ctl-blocked` both move the HICASSO frame's locale while only
  `:donor-locale` moves the donor's, so replaying `rf.bench.hicasso.lane/visit-plan` at
  `{:warmup 8 :samples 12}` puts `:theme` in the non-seed locale on 4 of
  its 12 measured visits per round and `:donor-theme` on 8 of 12. The same
  arithmetic in the other dimension splits the CONTROL from the arm it is
  subtracted from: `:ctl-blocked` runs in the non-seed theme on 8 of 12
  where `:locale` runs there on 4 of 12. Neither is visible to the
  canonical-DOM gate, which runs on the seeded page before sampling
  begins and is silent by design about everything after it.

  ## WHAT THIS RULE GIVES, AND WHY IT IS THE RULE RATHER THAN THE OTHER ONE

  The pre-state is a pure function of `(arm, visit)`. No arm's page state
  depends on any other arm's, so two paired arms see IDENTICAL state
  mixes at every schedule, by construction — which is the property
  `slice-broad-window-dom-cljs-test` asserts over a replay of the plan.

  The audit named a second candidate, ISOLATING THE CONTROL — restoring
  the locale the control moved, or giving the control its own root — and
  it is rejected on a replay rather than on taste. Both variants happen to
  equalise `:theme` against `:donor-theme` at `{:warmup 8 :samples 12}`,
  and both stop doing so at `{:warmup 8 :samples 13}` and at `{:warmup 8
  :samples 20}`: the equality is an accident of how `slot-order`'s
  rotation lands on twelve indices, not a property of the schedule. Since
  [[sampling]]'s own docstring says a run reading this instrument will
  RAISE those counts, a repair that holds at 12 and breaks at 13 is a
  repair that breaks exactly when it is first relied on. The isolating
  variant also leaves the control's own theme mix wrong — a control on its
  own root never has its theme moved at all — so it does not close the
  second divergence in any case.

  ## THE ARM'S OWN DIMENSION STILL ALTERNATES, AND NOW IT IS GUARANTEED

  `:alternates` names the dimension the arm MOVES; that one is put into
  the seed value on even visits and the other value on odd ones, so the
  arm's target — always *the one the page is not in* — alternates too.
  The rotor property [[locale-plan]] and [[theme-plan]] are written for is
  therefore no longer inherited from whatever ran before: it is stated
  here, and each arm takes both directions in equal numbers over any even
  block of visits rather than as a happy consequence of the plan.

  The dimension the arm does NOT move is pinned to the seed's value. That
  NARROWS each measured arm's population — `:theme` now always switches
  theme on a page in the seeded locale — and the narrowing is the point: a
  comparative is a statement about two arms, and two arms narrowed to the
  same stated population are comparable where two arms drifting
  independently are not."
  [{:keys [alternates]} visit]
  (let [flip? (odd? visit)]
    {:locale (if (and flip? (= :locale alternates)) (other-locale seed-locale) seed-locale)
     :theme  (if (and flip? (= :theme  alternates)) (other-theme  seed-theme)  seed-theme)}))

(defn- frame-for
  "The frame id THIS boot gave `side` — read from the live pair rather
  than from [[hicasso-frame]] / [[donor-frame]], which are only its
  defaults. A caller that mounts twice on fresh ids and then dispatched
  into the default would be writing to a frame nothing renders from."
  [side]
  (get (frames) side))

(defn- claim-visit!
  "Bank this arm's visit and answer its INDEX, counting from zero. Called
  once per [[measure-one!]] and by nothing else, so the index an arm's
  `n`th window runs under is `n - 1`.

  The stored value is the COUNT and the answer is the index. Storing the
  index instead would make [[visits]] read one short of the plan's own
  per-arm visit count, which is exactly the disagreement the suite's
  schedule row is written to catch."
  [arm-id]
  (dec (get-in (swap! !state update-in [:visits arm-id] (fnil inc 0))
               [:visits arm-id])))

(defn establish-pre-state!
  "Put `arm`'s own frame into [[pre-state]] for `visit`, and answer a
  promise that resolves once the page shows it.

  Through the APPLICATION'S OWN EVENTS, on the arm's own frame, and
  strictly outside every window — the same door and the same settle
  [[parity-discrimination!]] uses to move a frame between two states
  without touching a clock. A locale or theme already at its wanted value
  writes the same value back, `app-db` does not move, and nothing
  re-renders; the cost is a dispatch and two frames either way, and both
  are outside the reading."
  [arm visit]
  (let [{:keys [locale theme]} (pre-state arm visit)]
    (rf/with-frame (frame-for (:side arm))
      (rf/dispatch-sync [::rf.hicasso.examples.slice.events/set-locale (name locale)])
      (rf/dispatch-sync [::rf.hicasso.examples.slice.events/set-theme {:theme theme}]))
    (settle!)))

;; ---------------------------------------------------------------------------
;; Sampling
;; ---------------------------------------------------------------------------

(defn- bank-aux!
  "Record one sample's structural parts, and adjudicate its echo.

  Warm-up visits bank here and are narrowed out again before publication:
  this is called from inside [[measure-one!]], which the lane calls for
  warm-up and measured visits alike and which is handed an ARM rather
  than a visit, so it cannot tell them apart and does not try.
  `rf.bench.hicasso.slice-echo-clock-app/structure-over-measured` narrows the parts to the measured
  population using the mask `rf.bench.hicasso.lane/visit-plan` produces.

  The two consumers want different populations, which is why the
  narrowing is at publication rather than here. The TALLY wants every
  window; the DISTRIBUTIONS want the measured visits only."
  [arm-id {:keys [commit-ms to-raf-ms raf-to-paint-ms echo]}]
  (let [t (:echo-tally @!state)]
    (when-not (:verified? echo) (note-refusal! arm-id (:echo echo)))
    (swap! t (fn [{:keys [of bad]}]
               {:of  (inc of)
                :bad (if (:verified? echo) bad (inc bad))})))
  (swap! !state update-in [:aux arm-id]
         (fn [a] (-> (or a {:commit [] :to-raf [] :raf-to-paint []})
                     (update :commit conj commit-ms)
                     (update :to-raf conj to-raf-ms)
                     (update :raf-to-paint conj raf-to-paint-ms))))
  nil)

(defn measure-one!
  "One sample of `arm`, as a promise of its `:ms`.

  Three things happen before the window and all of them are outside it.

  [[establish-pre-state!]] runs FIRST, putting this arm's frame into the
  state its own visit index names, so what the window measures does not
  depend on which arms the schedule happened to run before it. Its
  docstring carries the divergence that made this necessary and the
  alternative that was rejected.

  `after-paint` runs next, so every window starts in the first task after
  a paint: a paint-bounded window is PREDECESSOR-DEPENDENT by
  construction, and aligning the phase makes it a constant of the
  instrument rather than a property of whatever ran before. The sibling's
  `measure-one!` carries the incident that established this, and what the
  alignment costs the estimand.

  The plan is built last and still outside — reading the node, deciding
  what the echo must be — so nothing but the interaction and the frame is
  inside the window. It reads the page rather than the pre-state it was
  just given, which is deliberate: the target stays *whichever value the
  page is not showing*, so the echo is adjudicated against the rendered
  page and a pre-state that failed to land cannot quietly become the
  answer the arm expects."
  [arm]
  (let [visit (claim-visit! (:id arm))]
    (-> (establish-pre-state! arm visit)
        (.then (fn [_] (rf.bench.hicasso.slice-echo-clock-app/after-paint)))
        (.then (fn [_] (rf.bench.hicasso.slice-echo-clock-app/window! ((:plan arm) !state))))
        (.then (fn [r] (bank-aux! (:id arm) r) (:ms r))))))

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

(defn boot!
  "Mount both arms into their own containers on their own frames, and
  answer a promise that resolves once both pages are on the screen.

  The Hicasso arm goes through `rf.hicasso/mount!`, the application's own root
  door, with the application's own views and `initial-events`. The donor
  arm makes its frame with `rf/make-frame` — core's own door, carrying
  the SAME `:initial-events` — and renders through `uix.dom`, which is
  how a UIx application mounts.

  `:identifier-prefix` on both, distinct: `useId` numbers every root from
  the same start, and a page with two roots either names them apart or
  watches their generated ids collide. Neither page renders a `useId`
  value today; if one ever did, distinct prefixes would put the
  difference in front of the canonical-DOM gate rather than under it.

  INSTALLING THE ADAPTER AND LEAVING REACT'S `act` ENVIRONMENT ARE NOT
  DONE HERE. Both are process-wide and belong to [[-main]], which owns
  the bench page.

  ## MOUNTING TWICE: THE FRAME IDS ARE AN ARGUMENT, AND FRESH ONES ARE
  ## MANDATORY FOR A CALLER THAT MOUNTS REPEATEDLY

  The bench page boots once and the no-arg arity is for it. A SUITE boots
  once per row, and handing this the same pair of ids each time does not
  merely inherit the previous row's `app-db` — **it leaves the Hicasso arm
  RENDERED BUT DEAF**, and that is measured rather than feared.

  `re-frame.hicasso.impl.collector`'s `!cells` is a process-global table
  keyed by `(frame, query)`, and its own comment states that isolation
  between roots is a property of THAT KEYING rather than anything React
  provides. A cell holds its reaction for the life of every boundary that
  reads the key, and `invalidate-cell!` is the repair for a reaction that
  has been retired — including, in its own words, *across a same-id
  reincarnation*. But that repair rides the reaction's DISPOSAL hook, and
  a test fixture that resets `re-frame.frame/frames` to `{}` retires the
  frame without disposing through it. The second mount on the same id then
  finds a live cell holding a dead reaction: `subs/subscribe` is never
  called again, so the new frame's sub-cache stays EMPTY, and no watch is
  installed, so nothing on the page ever re-renders.

  The symptom is silent in the worst way — the first render is perfect.
  PR #8606's first cut booted every row on these two defaults and CI read
  it exactly so: the mount row passed, the Hicasso arm's captured read
  roster came back `#{}` against the donor's 34, every locale and theme
  pre-state failed to reach the page, and all nine Hicasso-side windows
  went unverified while all six donor-side ones verified.

  So a repeat caller passes fresh ids, which is the discipline
  `slice-echo-window-dom-cljs-test` already keeps against a milder form of
  the same hazard. [[frames]] answers the pair this boot actually used;
  read that rather than the defaults."
  ([] (boot! {:hicasso hicasso-frame :donor donor-frame}))
  ([{hic-frame :hicasso don-frame :donor}]
   (let [hic-container (rf.bench.hicasso.lane/fresh-container!)
         don-container (rf.bench.hicasso.lane/fresh-container!)
         handle        (rf.hicasso/mount! hic-container
                                 {:frame             hic-frame
                                  :identifier-prefix "hic"
                                  :initial-events    initial-events}
                                 [rf.hicasso.examples.slice.views/app {}])
         _             (rf/make-frame {:id             don-frame
                                       :initial-events initial-events})
         don-root      (uix-dom/create-root don-container {:identifier-prefix "don"})]
     (uix-dom/render-root (rf.bench.hicasso.slice-donor-views/root don-frame) don-root)
     (swap! !state assoc
            :hicasso-container hic-container
            :hicasso-handle    handle
            :donor-container   don-container
            :donor-root        don-root
            :first-refusal     nil
            :frames            {:hicasso hic-frame :donor don-frame}
            :visits            {}
            :aux               {}
            :echo-tally        (rf.bench.hicasso.lane/tally))
     (.then (settle!)
            (fn [_]
              (doseq [side [:hicasso :donor]]
                (when (nil? (locale-select side))
                  (throw (ex-info (str "the " (name side) " arm mounted but its feed did not: "
                                       "#slice-locale is not on its page, so there is no "
                                       "published control to switch")
                                  {:rf.error/id ::feed-absent
                                   :side        side})))
                (when (nil? (page-root side))
                  (throw (ex-info (str "the " (name side) " arm rendered no main.slice root, "
                                       "so the theme echo has nothing to read")
                                  {:rf.error/id ::root-absent
                                   :side        side}))))
              nil)))))

(defn teardown!
  "Take both roots down and drop both containers. Exposed for a caller
  that mounts and unmounts around each of its rows.

  It does NOT make a re-mount on the same frame ids safe, and could not:
  the table that goes stale is `collector/!cells`, which is
  process-global, keyed by `(frame, query)` and reached through a disposal
  hook this cannot reach. [[boot!]]'s §MOUNTING TWICE carries the
  measurement and the remedy."
  []
  (let [{:keys [hicasso-container hicasso-handle donor-container donor-root]} @!state]
    (when hicasso-handle (rf.hicasso/unmount! hicasso-handle))
    (when donor-root (uix-dom/unmount-root donor-root))
    (doseq [c [hicasso-container donor-container]]
      (when (and c (.-parentNode c)) (.removeChild (.-parentNode c) c)))
    (swap! !state assoc
           :hicasso-container nil :hicasso-handle nil
           :donor-container nil :donor-root nil)
    nil))

;; ---------------------------------------------------------------------------
;; The fairness gate, and the control that proves it discriminates
;; ---------------------------------------------------------------------------

(defn pages
  "Both arms' canonical DOM, as `{:hicasso s :donor s}`."
  []
  {:hicasso (rf.bench.hicasso.lane/canonical (container-for :hicasso))
   :donor   (rf.bench.hicasso.lane/canonical (container-for :donor))})

(defn- first-difference
  "The index of the first character at which `a` and `b` differ, and the
  60 characters from it in each, or nil when they are equal. A refusal
  that prints two 4 KB pages is a refusal nobody reads."
  [a b]
  (let [n (min (count a) (count b))
        i (loop [i 0] (cond (= i n)                    (when (not= (count a) (count b)) i)
                            (not= (nth a i) (nth b i)) i
                            :else                      (recur (inc i))))]
    (when (some? i)
      {:at      i
       :hicasso (subs a i (min (count a) (+ i 60)))
       :donor   (subs b i (min (count b) (+ i 60)))})))

(defn assert-parity!
  "REFUSE unless both arms are building the same page.

  This is the entire fairness guarantee of a cross-arm ratio, in
  `rf.bench.hicasso.lane/canonical`'s own words: without it two arms can be timed against
  each other while building different pages. It runs on the seeded page,
  before any window, because that is the only moment both arms are known
  to be in the same state — every measured arm alternates its own page
  independently afterwards, so a comparison taken later would be
  comparing two different locales and would refuse for the wrong reason."
  [why]
  (let [{:keys [hicasso donor]} (pages)]
    (when (not= hicasso donor)
      (throw (ex-info (str "the two arms are not building the same page (" why "), so no "
                           "ratio between them would be a ratio about a substrate. "
                           "Under a dev compile this is expected and correct: "
                           "data-rf2-source-coord carries each boundary's own source "
                           "position, and the lane is built in release mode where the "
                           "emit is DCE'd on both sides.")
                      {:rf.error/id ::pages-differ
                       :why         why
                       :difference  (first-difference hicasso donor)
                       :sizes       {:hicasso (count hicasso) :donor (count donor)}})))
    nil))

(defn parity-discrimination!
  "THE SABOTAGE, BUILT IN, on the fairness gate rather than on an echo.

  Move the DONOR frame's locale to the other one — outside every window,
  through the application's own event, on the frame that arm renders from
  — and require [[assert-parity!]] to REFUSE. Then put it back and
  require it to pass again.

  Answers a promise. A gate that cannot tell two pages apart would let
  every later refusal read as a clean pass, and the run would publish a
  ratio taken across two applications."
  []
  (let [;; Read off the donor's own `<select>` rather than assuming the
        ;; seed's `:en`. The assumption would be true today and would stop
        ;; being true the moment anything ran before this — which is
        ;; exactly the class of thing a control is supposed to survive.
        now         (keyword (.-value (locale-select :donor)))
        other       (other-locale now)
        to-locale!  (fn [l] (rf/with-frame (frame-for :donor)
                              (rf/dispatch-sync [::rf.hicasso.examples.slice.events/set-locale (name l)])))
        restore     (fn [] (to-locale! now))
        break       (fn [] (to-locale! other))]
    (break)
    (-> (settle!)
        (.then (fn [_]
                 (let [{:keys [hicasso donor]} (pages)]
                   (when (= hicasso donor)
                     (throw (ex-info (str "the canonical-DOM gate does not discriminate: the "
                                          "donor arm was moved to a different locale and its "
                                          "page still serialised identically to the Hicasso "
                                          "arm's. Every parity check this run could take "
                                          "would pass whatever the two arms rendered, so "
                                          "nothing may be measured")
                                      {:rf.error/id ::parity-not-discriminating
                                       :size        (count hicasso)})))
                   (restore))))
        (.then (fn [_] (settle!)))
        (.then (fn [_] (assert-parity! "after the gate's own negative control restored it")
                 nil)))))

;; ---------------------------------------------------------------------------
;; The negative controls on the ECHOES
;; ---------------------------------------------------------------------------

(defn- refuse-window!
  "Take one window over `plan` and require its observation to REFUSE.
  Answers a promise of the refused observation and REJECTS if it passed."
  [label plan]
  (.then (rf.bench.hicasso.slice-echo-clock-app/window! plan)
         (fn [{:keys [echo]}]
           (when (:verified? echo)
             (throw (ex-info (str label " does not discriminate: a window whose interaction "
                                  "could not have moved the application still verified. Every "
                                  "reading this arm could take would be timing something "
                                  "other than a slice-application echo, so nothing may be "
                                  "measured")
                             {:rf.error/id ::echo-not-discriminating
                              :control     label
                              :observed    (:echo echo)})))
           (:echo echo))))

(defn echo-discrimination!
  "Four windows, before the first warm-up visit, all four required to
  refuse. Answers a promise.

  ## The locale halves: the SETUP MUTATION ALONE

  Write the other locale onto the `<select>` through the same pristine
  setter the arm uses — and fire NO event, so no handler, no dispatch, no
  state write and no commit — then ask the arm's own check for the
  target's title. It must refuse, because the heading is written by a
  React commit and by nothing else. The control leaves the page as it
  found it: the restoring write is through the same setter and is not a
  model change, so it needs no dispatch to unwind.

  ## The theme halves: NO INTERACTION AT ALL

  Weaker, and stated rather than smoothed. A click IS the whole
  interaction on a theme button, so there is no setup mutation to keep
  while the event is suppressed — suppressing the click leaves nothing.
  What this half establishes is that the check distinguishes *a commit
  happened* from *nothing happened*, asked against the theme the page is
  NOT in.

  ## Why here rather than only in a suite

  The sibling's argument, unchanged: a suite row is the right place to
  prove a repair and the wrong place to keep a driver honest, because the
  driver is what a quiet-box window runs and the suite is not. So the run
  itself asks, once, at a cost of four frames."
  []
  (let [locale-control
        (fn [side]
          (let [select (locale-select side)
                now    (keyword (.-value select))
                want   (other-locale now)]
            (.then (refuse-window!
                     (str (name side) " locale echo")
                     {:interact!        (fn [] (set-native-select-value! select (name want)))
                      :observe-at-frame (fn []
                                          (let [seen (locale-echo-check side want)]
                                            {:verified? (locale-echo-verified? seen)
                                             :echo      seen}))})
                   (fn [seen]
                     (set-native-select-value! select (name now))
                     seen))))
        theme-control
        (fn [side]
          ;; `theme-state`'s own `:want` — the arm's target, taken through
          ;; the arm's own reader rather than through a second copy of the
          ;; rule, so this control adjudicates the code the readings use.
          (let [{:keys [want]} (theme-state side)]
            (refuse-window!
              (str (name side) " theme echo")
              {:interact!        (fn [] nil)
               :observe-at-frame (fn []
                                   (let [seen (theme-echo-check side want)]
                                     {:verified? (theme-echo-verified? seen)
                                      :echo      seen}))})))]
    (-> (locale-control :hicasso)
        (.then (fn [_] (locale-control :donor)))
        (.then (fn [_] (theme-control :hicasso)))
        (.then (fn [_] (theme-control :donor))))))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(def arms
  "The measured arms, floor first so it leads the schedule.

  `:side` is the frame the arm interacts with and reads, and
  `:alternates` is the dimension of that frame's state it MOVES.
  [[pre-state]] is a function of exactly those two and the arm's own visit
  index, which is what makes two paired arms comparable: `:locale` and
  `:donor-locale` declare the same `:alternates` on different sides, so
  they see the same state mix on two frames, and so do `:theme` and
  `:donor-theme`. `:ctl-blocked` declares `:locale` because it IS the
  locale operation plus a blocked seam, which is what keeps
  [[control-per-round]]'s subtraction over one population rather than two.

  `:idle-frame` alternates NOTHING — it is the floor and it interacts with
  nothing — so it holds the seeded state on every visit."
  [{:id :idle-frame   :plan idle-plan              :side :hicasso :alternates nil}
   {:id :locale       :plan (locale-plan :hicasso) :side :hicasso :alternates :locale}
   {:id :donor-locale :plan (locale-plan :donor)   :side :donor   :alternates :locale}
   {:id :theme        :plan (theme-plan :hicasso)  :side :hicasso :alternates :theme}
   {:id :donor-theme  :plan (theme-plan :donor)    :side :donor   :alternates :theme}
   {:id :ctl-blocked  :plan blocked-plan           :side :hicasso :alternates :locale
    :control? true}])

(defn- readings-by-arm [readings]
  (reduce (fn [m round]
            (reduce-kv (fn [m id xs] (update m id (fnil into []) xs)) m round))
          {}
          readings))

(defn round-ratios
  "Each round's arm-to-floor ratio map, which is what both comparative
  figures are built from.

  Every ratio is against the floor measured in THAT round — `rf.bench.hicasso.lane/normalise`'s
  rule, and the reason this box's drift across rounds is not a term in
  any published figure. `rf.bench.hicasso.lane/ratio-between` then divides two of them,
  and the floor cancels: what is left is the per-round ratio of two
  within-round medians."
  [readings]
  (mapv (fn [round] (:ratio (rf.bench.hicasso.lane/normalise round :idle-frame))) readings))

(defn control-per-round
  "One adjudicated figure per round: `:ctl-blocked`'s median less
  `:locale`'s, in milliseconds.

  A DIFFERENCE and not a ratio, because the prediction is additive — the
  control injects a duration, not a multiple — and pairing each round
  with its own denominator is what `control-verdict-strict` asks for."
  [readings]
  (mapv (fn [round]
          (rf.bench.hicasso.lane/round4 (- (:p50 (rf.bench.hicasso.lane/summarise (get round :ctl-blocked)))
                          (:p50 (rf.bench.hicasso.lane/summarise (get round :locale))))))
        readings))

(defn take-plan!
  "Take the plan, publish the record, and adjudicate the
  instrument-integrity verdicts. Answers a promise.

  Order matters and is the lane's: the record is published BEFORE
  `assert-verified!` can throw, so an operator reading a failed run has
  the evidence on the console rather than only the refusal."
  []
  (.then
    (rf.bench.hicasso.lane/rounds-async! arms sampling rounds measure-one!)
    (fn [{:keys [readings samples]}]
      (let [by-arm  (readings-by-arm readings)
            ratios  (round-ratios readings)
            control (rf.bench.hicasso.lane/control-verdict-strict blocked-ms
                                                 (control-per-round readings)
                                                 control-slack)
            verdict (rf.bench.hicasso.lane/guard! samples "slice broad update")
            summary (into {} (map (fn [[id xs]] [id (rf.bench.hicasso.lane/summarise xs)])) by-arm)
            compare {:locale (rf.bench.hicasso.lane/ratio-between ratios :locale :donor-locale)
                     :theme  (rf.bench.hicasso.lane/ratio-between ratios :theme :donor-theme)}]
        (rf.bench.hicasso.lane/record! :slice-broad
                      {:window      :interaction-to-paint
                       :population  {:hicasso {:app   're-frame.hicasso.examples.slice
                                               :views 're-frame.hicasso.examples.slice.views
                                               :route :feed}
                                     :donor   {:app   're-frame.hicasso.examples.slice
                                               :views 're-frame.bench.hicasso.slice-donor-views
                                               :route :feed
                                               :spine :uix/use-subscribe}
                                     ;; DERIVED from the application's own seed,
                                     ;; never transcribed: a population pin that
                                     ;; restated three integers would be a second
                                     ;; source for them, and the first thing to go
                                     ;; stale when the seed grows an article.
                                     :seed    {:articles  (count (:order rf.hicasso.examples.slice.db/seed))
                                               :page-size rf.hicasso.examples.slice.db/page-size
                                               :pages     (rf.hicasso.examples.slice.db/page-count
                                                            (rf.hicasso.examples.slice.db/listed rf.hicasso.examples.slice.db/seed))}}
                       :schedule    (assoc sampling
                                           :rounds           rounds
                                           :visits-per-arm   (* (+ (:warmup sampling)
                                                                   (:samples sampling))
                                                                rounds)
                                           :measured-per-arm (* (:samples sampling) rounds))
                       :populations populations
                       :summary     summary
                       :structure   (rf.bench.hicasso.slice-echo-clock-app/structure-over-measured arms sampling rounds
                                                                  (:aux @!state))
                       :comparative compare
                       :over-floor  (rf.bench.hicasso.lane/across-rounds ratios)
                       :resolution  (into {}
                                          (map (fn [[pair c]]
                                                 [pair (rf.bench.hicasso.lane/resolution c summary :idle-frame)]))
                                          compare)
                       :control     control
                       :guard       (select-keys verdict [:refuse? :contaminated?
                                                          :unchecked? :tolerance])
                       :echo        (cond-> (rf.bench.hicasso.lane/tally-value (:echo-tally @!state))
                                      (:first-refusal @!state)
                                      (assoc :first-refusal (:first-refusal @!state)))
                       :runtime     (rf.bench.hicasso.lane/runtime-label)
                       :note        (str "No line is applied to any figure above. U3's 100 ms "
                                         "p95, C3's 1.25x and C4's 1.5x are read against this "
                                         "instrument in their own quiet-box window, not here. "
                                         "Read :over-floor's :straddles-1? on a measured arm "
                                         "before quoting any :comparative range: an arm this "
                                         "window did not separate from an empty frame carries "
                                         "no ratio about a substrate. Then read :resolution's "
                                         ":resolves-at for that pair against the line your row "
                                         "is stated at — clearing the floor does not mean the "
                                         "pair could resolve the line, and a difference below "
                                         ":resolves-at is inside this run's own scatter. "
                                         "Neither figure is a verdict.")})
        (set! (.-HICASSO_GUARD_REFUSED js/window) (boolean (:refuse? verdict)))
        (set! (.-HICASSO_CONTROL_FAILED js/window) (not (:ok? control)))
        (rf.bench.hicasso.lane/assert-verified! (:echo-tally @!state) "slice broad update")
        nil))))

(defn ^:export -main []
  (rf/init! rf.adapter.uix/adapter)
  (rf.bench.hicasso.lane/leave-act-environment!)
  (if-not (rf.bench.hicasso.lane/self-test!)
    (rf.bench.hicasso.lane/fail! (str "the arm-order self-test failed — the copy of the schedule "
                     "rule this app is about to rely on no longer behaves like "
                     "the one the .cjs drivers use, so nothing may be measured"))
    (-> (boot!)
        ;; The fairness gate first, then its own negative control, then
        ;; the four echo controls — all of them before the first warm-up
        ;; visit, and all of them travelling the same `.catch`, so a run
        ;; whose instrument cannot discriminate dies here rather than
        ;; publishing a record nobody should read.
        (.then (fn [_] (assert-parity! "on the seeded page, before any window")))
        (.then (fn [_] (parity-discrimination!)))
        (.then (fn [_] (echo-discrimination!)))
        (.then (fn [_] (take-plan!)))
        (.catch (fn [e] (rf.bench.hicasso.lane/fail! (rf.bench.hicasso.lane/describe-throw "slice-broad-clock-app" e))))
        (.then (fn [_] (rf.bench.hicasso.lane/done!)))))
  nil)
