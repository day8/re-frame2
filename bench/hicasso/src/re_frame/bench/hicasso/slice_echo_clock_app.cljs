(ns re-frame.bench.hicasso.slice-echo-clock-app
  "THE SLICE'S INTERACTION-TO-PAINT CLOCK — one discrete interaction,
  through to the paint that follows it, on the witness application
  `U1`–`U4` are stated over (rf2-xa8wo, deliverable 2).

      HICASSO_INIT_FN=re-frame.bench.hicasso.slice-echo-clock-app/-main \\
      HICASSO_OUT_DIR=out/hicasso-slice-echo \\
      HICASSO_PORT=8137 \\
        node implementation/hicasso/test/re_frame/bench/hicasso/run.cjs

  NO NEW BUILD ID, and that is checked rather than assumed. `run.cjs`
  takes its entry from `HICASSO_INIT_FN` and rides `:hicasso-bench`, the
  id the whole lane already shares, so this arm costs
  `implementation/shadow-cljs.edn` — an HD-017 hot-zone file — nothing.

  ## THE WINDOW, WHICH IS THE WHOLE POINT OF THIS FILE

  Every other clock on this lane brackets its operation with
  `react-dom/flushSync` and stops when the commit returns. That is a
  MOUNT OR A COMMIT AND NOT A PAINT, and `docs/design/hicasso/product/
  budgets.md` §4 says so in terms: the estimands `U1`–`U4` are registered
  over *latency to visible echo*, *latency to next paint*,
  *operation latency* and *per-frame latency*, and §9.3 calls them
  *slice-app user-visible gates*. A `p95` taken over a commit and
  published against a line written about a paint is worse than no `p95`,
  because it is quotable.

  [[window!]] is the repair. It starts the clock immediately before a real
  DOM event, and stops it in the first task AFTER the browser has produced
  and painted the frame that carries the echo:

      t0 ──▶ dispatchEvent ──▶ [React's discrete lane commits] ──▶ t-commit
             │
             └─▶ requestAnimationFrame ──▶ [the frame's rendering steps:
                     the callback runs BEFORE style, layout and paint, and
                     is where this file READS THE ECHO OUT OF THE DOM]
                     │
                     └─▶ setTimeout 0 ──▶ the first task after that frame's
                             rendering lifecycle ──▶ t-paint

  `requestAnimationFrame` runs before paint, so a callback registered
  there is not enough on its own; the `setTimeout` inside it lands in the
  first task after that frame's rendering lifecycle has run. This is the
  standard after-paint idiom and the lane already relies on it in three
  places (`clock-app`, `hd8-clock-app`, `shapes.census-clock-app`) for
  exactly this reason.

  **Three things carry the claim that this window reaches a paint, and
  none of them is a sentence in this docstring.**

  1. THE MECHANISM. The reading cannot be taken at all unless a frame's
     rendering steps run: the promise resolves from a `setTimeout` queued
     inside a `requestAnimationFrame` callback, so a window in which the
     browser produced no frame produces no reading — it hangs, and
     `run.cjs`'s sentinel kills the run. A `flushSync` window crosses no
     frame boundary and would answer instantly.
  2. THE ECHO, CHECKED PER SAMPLE — and checked against something the
     BROWSER'S OWN MUTATION COULD NOT HAVE PRODUCED. See §THE ECHO IS
     READ OFF THE MIRROR THE APPLICATION WRITES below: the check is
     read out of the DOM INSIDE those rendering steps, before style,
     layout and paint, so a verified sample is one whose painted frame
     carried the echo. It banks into `rf.bench.hicasso.lane/tally` and
     `rf.bench.hicasso.lane/assert-verified!` refuses the run at `N unverified of M`.
  3. THE NEGATIVE CONTROL ON THE ECHO ITSELF, taken once at boot:
     [[echo-discrimination!]] performs the keystroke's SETUP MUTATION
     ALONE — the native value write, with no DOM event and therefore no
     handler, no dispatch, no state write and no commit — and requires
     the very check the arms use to REFUSE it. Nothing is measured if it
     does not.
  4. THE POSITIVE CONTROL, which adjudicates the window rather than the
     echo, and is described next.

  Every reading is also published DECOMPOSED — `:commit`, `:to-raf`,
  `:raf-to-paint` — so a reader can see how much of a window closed
  inside the DOM event and how much of it is the browser's rendering
  lifecycle. A rig that had reverted to a commit-bounded window would
  show the first of those three and nothing else.

  ## THE POSITIVE CONTROL IS INVISIBLE TO THE WINDOW IT REPLACES

  `:ctl-blocked` is `:keystroke` plus a busy-wait of [[blocked-ms]]
  milliseconds on the main thread, spent AFTER React's commit has returned
  and BEFORE the browser can produce a frame. Its prediction is additive
  and needs no model of the application: **the window must lengthen by
  [[blocked-ms]]**, to within the one rendering interval the browser's
  frame grid rounds it to — see [[control-slack]], where the band is
  derived from that interval rather than tuned to a measurement.

  What makes it the right control here rather than a generic one is where
  the injected cost sits. A commit-bounded window would not see one
  millisecond of it — the block begins after the commit it stops at — so
  a rig that had quietly reverted to measuring a commit reports a control
  that predicted [[blocked-ms]] and measured nothing. The control
  therefore adjudicates the INSTRUMENT'S WINDOW and not merely its
  sensitivity.

  It is adjudicated under `rf.bench.hicasso.lane/control-verdict-strict` — every round
  inside the band — and not under the overlap rule, because these legs are
  tens of milliseconds and nowhere near the 100 µs clamp Chrome puts on
  `performance.now()`. `rf.bench.hicasso.lane/control-verdict`'s own docstring names that exactly: a
  batched window lifting the legs clear of the quantum is the condition
  under which the strict rule is the one to use.

  ## WHAT THIS FILE PUBLISHES, AND WHAT IT DELIBERATELY DOES NOT

  It publishes `{:n :min :max :p50 :p95 :p99}` per arm, the guard's
  verdict, the control's verdict, and the runtime label — and it compares
  none of them to anything.

  **`:summary` and `:structure` are published over ONE population and
  `:populations` says which.** `:structure` is documented as the
  decomposition of `:summary`, so a decomposition taken over a different
  set of visits than the whole it decomposes is not a decomposition. The
  banked parts cover every visit, warm-up included — [[bank-aux!]] is
  called from inside [[measure-one!]], which the lane calls for both —
  and [[structure-over-measured]] narrows them to the visits
  `rf.bench.hicasso.lane/rounds-async!` actually returned, using the MASK the lane's own
  [[re-frame.bench.hicasso.lane/visit-plan]] produces rather than a second
  reading of the schedule. At `{:warmup 8 :samples 12}` over five rounds
  that is `60` values per arm on both, against the `100` an unnarrowed
  tally would have carried.

  The one figure deliberately left over ALL visits is `:echo`, the
  verification tally, and `:populations` labels it as such: a
  verification is worth more the more windows it covers, and it is a
  count of refusals rather than a distribution, so it decomposes nothing.

  **There is no threshold in this file, no band around a latency, and no
  pinned figure.** `U1`'s one-60-Hz-frame line, `U2`'s 50 ms `p95` and
  100 ms `p99`, `U3`'s 100 ms — none of them appears here, and none of
  them should. Reading this instrument against those lines is a separate
  quiet-box window (`rf2-85og2` gate 1), and an instrument that carried
  the line it is meant to be read against would be an instrument nobody
  could re-adjudicate. The only pass/fail this file makes are the two
  INSTRUMENT-INTEGRITY verdicts above, and `budgets.md` §2's rule holds
  for both: they are structural, not distributional.

  ## THE ROWS, AND WHICH ESTIMANDS THEY CAN AND CANNOT SERVE

      :idle-frame   no interaction at all — the frame and nothing else.
                    The FLOOR of any paint-bounded window on this box: the
                    wait for the next rendering opportunity, which every
                    other row pays too and which no application work can
                    remove. Without it a tail quantile on the rows above
                    cannot be read, because a `p95` at the floor and a
                    `p95` twice it are different findings and the figure
                    alone does not say which.

      :keystroke    one character typed over the last one in the editor's
                    title field. `U1`'s estimand — *latency to visible
                    echo* on a controlled update — and an instance of
                    `U2`'s.

      :toggle       a real click on the published checkbox. `U2`'s
                    estimand — *latency to next paint* for an ordinary
                    discrete interaction — on a second event path
                    (`click`/`change` rather than `input`).

      :ctl-blocked  the positive control described above.

  **`U3` AND `U4` ARE NOT SERVED BY THIS ROW SET, and that is stated
  rather than left to be discovered.** `U3` is *operation latency* for a
  BROAD operation — a route change, a reset, a save reply — which needs
  its own preparation outside the window, because the route the editor
  rows type into is the route a navigation row would leave. `U4` is
  *per-frame latency* under dragging or animation, which is not one
  interaction through to one paint at all: it is a run of consecutive
  frames, and its estimator is a distribution over frame intervals rather
  than over window lengths. Both want another driver on [[window!]]'s
  mechanism; neither is this one.

  ## THE POPULATION IS THE SLICE APPLICATION, MOUNTED THROUGH ITS OWN DOOR

  [[boot!]] calls `rf.hicasso/mount!` with the slice's own `[views/app {}]` and the
  same two `:initial-events` its `-main` passes, differing only in which
  route it opens on — which is data the application already takes. Nothing
  here reaches under `re-frame.hicasso.impl.*`, nothing rebuilds a view,
  and no interaction is simulated by dispatching an event: every reading
  starts at a DOM event on a node the application rendered.

  ## THE SCRIPTED KEYSTROKE GOES THROUGH THE PROTOTYPE'S OWN SETTER

  `(set! (.-value node) v)` does NOT work, and the failure is silent.
  React installs an instance-level property descriptor on every controlled
  input to track its value; assigning through it updates React's tracked
  value, so the `input` event that follows is seen as a no-op change and
  the handler never runs. Writing through the descriptor captured off
  `HTMLInputElement.prototype` leaves the tracker stale, which is what
  makes the event a real one.
  `examples.per-keystroke-dom-cljs-test` carries the same capture for the
  same reason.

  ## THE ECHO IS READ OFF THE MIRROR THE APPLICATION WRITES

  **A scripted interaction sets the control up by mutating it, so the
  control's own state is the one thing an echo check may not be taken
  over.** This instrument's first version did exactly that on both
  measured arms: `keystroke` wrote `want` onto `input.value` and then
  verified `input.value`; `toggle` called `HTMLElement.click()`, whose
  activation behaviour flips `checkedness` in the user agent, and then
  verified `checked`. Remove the Hicasso handler, the re-frame dispatch,
  the state write or the React commit and BOTH checks could still read
  true — so what the window timed was a native control mutation surviving
  to the next frame, which is not what `U1`–`U4` are stated over.
  `examples.per-keystroke-dom-cljs-test` names this shape in its own
  §*The echo is read BEFORE the flush*: *a scripted keystroke reaches the
  real code path only by writing the accepted text onto the control and
  then firing the event*, and it reaches for a field whose model answers
  something ELSE. This lane cannot — the slice's `::events/edit` takes
  what it is given, verbatim, and no field on the page normalises.

  So the discriminator here is not a different VALUE, it is a different
  PLACE. React keeps a controlled `input`'s committed value in TWO
  places: the `value` PROPERTY, which the user agent and this file both
  write, and the `value` CONTENT ATTRIBUTE — `defaultValue` — which
  **only React's own commit writes**. `react-dom` 19's `updateInput` ends
  every controlled-input update with `setDefaultValue(element, type,
  value)`, unconditionally whenever a `value` prop is present, and
  `per-keystroke-dom-cljs-test`'s measured mutation trace carries the
  matching record (`INPUT@value`, once per keystroke, `\"…dataa\" ->
  \"…dataab\"`). Neither `HTMLInputElement.prototype`'s `value` setter
  nor a checkbox's activation behaviour touches it.

  [[blank-committed-title!]] therefore scribbles
  [[committed-value-sentinel]] over that mirror OUTSIDE the window,
  before every measured interaction, and the check inside the frame asks
  whether the APPLICATION put the model's own title back. It cannot have
  come from the setup mutation, because the setup mutation writes a
  different property; and it cannot be left over from the previous
  sample, because the sentinel is written between them.

  **The checkbox arm reads the same mirror**, and that is the point of it
  rather than a convenience: `::events/toggle-published` writes the
  draft, the editor's body re-runs, and React reconciles the title input
  along with the checkbox — so a restored title mirror is evidence that
  the toggle reached the model and came back through a commit. Reading
  the checkbox's own `defaultChecked` would NOT have served: `updateInput`
  writes it only when the element has no `checked` prop, and this one has.

  Owner: rf2-xa8wo."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.examples.slice.events :as rf.hicasso.examples.slice.events]
            [re-frame.hicasso.examples.slice.routes :as rf.hicasso.examples.slice.routes]
            [re-frame.hicasso.examples.slice.views :as rf.hicasso.examples.slice.views]))

;; ---------------------------------------------------------------------------
;; The knobs — every one of them a SCHEDULE knob, never a line
;; ---------------------------------------------------------------------------

(def article-slug
  "The seeded article the editor opens on. Any of `db/seed`'s seven would
  do; this one is the slug `flow-dom-cljs-test` already navigates to, so
  a reader comparing the two is comparing one page."
  "intents")

(def sampling
  "Per-round warm-up and measured counts.

  `:warmup 8` is `rf2-h904p`'s value and is carried rather than chosen:
  `rf.bench.hicasso.lane/rounds!`'s docstring records that it puts the +27% step this lane
  sees after a site's sixth execution inside the warm-up. `:samples 12`
  is the same file's figure. **A tail quantile over 12 is mostly
  interpolation** — `rf.bench.hicasso.lane/quantile`'s docstring prices exactly that — so
  the run that reads this instrument will want more, and raising these
  two is how it gets them."
  {:warmup 8 :samples 12})

(def rounds
  "Rounds per run. Five is the lane's standard and what `across-rounds`
  and `control-verdict-strict` are shaped for."
  5)

(def blocked-ms
  "How long `:ctl-blocked` blocks the main thread after the commit and
  before the frame.

  Chosen against the FRAME INTERVAL rather than by taste. A paint-bounded
  window is quantised by the display's rendering opportunities — roughly
  16.7 ms at 60 Hz — so an injected cost much smaller than one frame can
  be absorbed by the quantisation entirely and would make a control that
  fails for the clock rather than for the instrument. 50 ms is three
  frames, which is far enough clear of the grid that the control's band
  can be set from the quantisation and still be narrow."
  50.0)

(def control-slack
  "The half-width of `:ctl-blocked`'s band, as a fraction of
  [[blocked-ms]].

  Derived from the frame grid, not tuned to a measurement.

  Both arms start at the same phase ([[measure-one!]]'s alignment), so
  each round's adjudicated figure is a difference of two windows that each
  end at the first rendering opportunity at or after their own work. The
  injected duration therefore reaches the difference ROUNDED to the grid,
  never exactly: the block does not add to the wait the unblocked arm
  pays, it SUBSUMES it and leaves whatever is left of the interval it
  landed inside. So the difference sits within one rendering interval —
  about 16.7 ms at 60 Hz — of [[blocked-ms]], in either direction, with
  nothing wrong with the instrument.

  `0.5` of 50 ms is ±25 ms, which covers that interval with room for a
  grid that is not exactly 60 Hz. It is deliberately generous, because the
  claim the control makes is THE INSTRUMENT SEES THE INJECTED COST, not
  THE MODEL IS EXACT — and a control tightened until it only just passes
  is a control tuned to the box it was written on."
  0.5)

(def rotor
  "The characters `:keystroke` types, in turn. Rotating rather than fixed
  so a page left stale by a window that closed too early cannot pass the
  echo check by carrying the PREVIOUS sample's character."
  "abcdefghijklmnopqrstuvwxyz")

;; ---------------------------------------------------------------------------
;; State — one mounted slice, and the counters that adjudicate it
;; ---------------------------------------------------------------------------

(defonce ^:private !state
  (atom {:container   nil
         :handle      nil
         :base-title  nil
         :tick        0
         :echo-tally  nil
         :aux         {}}))

(defn- next-tick! []
  (:tick (swap! !state update :tick inc)))

(defn verification
  "`{:writes M :unverified N}` over every window taken since the last
  [[boot!]] — warm-up visits included, because a verification is worth
  more the more of them there are. Exposed so the DOM self-test can read
  the same counter [[take-plan!]] adjudicates."
  []
  (rf.bench.hicasso.lane/tally-value (:echo-tally @!state)))

(defn banked-structure
  "[[bank-aux!]]'s raw per-arm `{:commit :to-raf :raf-to-paint}` vectors,
  over EVERY visit this run has taken.

  Exposed so the DOM self-test can compare the population that is BANKED
  against the population that is PUBLISHED, which is the pair that drifted
  apart the first time."
  []
  (:aux @!state))

(def populations
  "Which visits each published figure is taken over.

  A def rather than a literal inside [[take-plan!]] so the claim is
  citable from the suite: the audit that reopened `rf2-xa8wo` found
  `:structure` published over `100` values per arm against a `:summary`
  of `60` while the record described the first as a decomposition of the
  second, and a label nobody can assert is how that recurs."
  {:summary   :measured-visits
   :structure :measured-visits
   :echo      :all-visits})

;; ---------------------------------------------------------------------------
;; The glass
;; ---------------------------------------------------------------------------

(def ^:private pristine-input-value-setter
  "`HTMLInputElement.prototype`'s own `value` setter, captured lazily.

  A `delay` and not a `def` of the setter itself: this namespace's top
  level runs wherever it is loaded, and `js/HTMLInputElement` does not
  exist on the node lane."
  (delay (.-set (js/Object.getOwnPropertyDescriptor
                  js/HTMLInputElement.prototype "value"))))

(defn- set-native-value! [node v]
  (.call @pristine-input-value-setter node v))

(defn- node-at [sel]
  (some-> (:container @!state) (.querySelector sel)))

(defn- title-field [] (node-at "#slice-title"))
(defn- published-box [] (node-at "#slice-published"))

;; ---------------------------------------------------------------------------
;; The mirror — the one place on the glass ONLY a React commit writes
;; ---------------------------------------------------------------------------

(def committed-value-sentinel
  "What [[blank-committed-title!]] scribbles over the title field's
  committed mirror before an interaction.

  It has to be a string the model cannot be holding when the check runs,
  and it is: the checks are EQUALITIES against either the seeded
  article's title plus one letter of [[rotor]] ([[keystroke-plan]]) or
  the title the application is already showing ([[toggle-plan]]), and the
  only door into `[:drafts slug :title]` is `::events/edit` carrying
  `::rf.hicasso/value` off a real `input` event — which is to say, off a value
  this file typed.

  PRINTABLE ASCII, and that is not cosmetic. The first spelling of this
  def wrapped the text in NUL codepoints, on the reasoning that a
  codepoint nothing can type is the strongest sentinel there is. It is
  also the strongest way to make a source file BINARY: `git` classifies a
  file containing a NUL as binary, which cost the file its line-ending
  normalisation, its diffs and its greps in one move. The sentinel does
  not need to be untypable — it needs to be unequal."
  "<< no commit reached this field >>")

(defn committed-title
  "The title the application's LAST REACT COMMIT wrote onto the editor's
  title field — read off `defaultValue`, which is the `value` CONTENT
  ATTRIBUTE and not the property.

  See the namespace docstring §THE ECHO IS READ OFF THE MIRROR THE
  APPLICATION WRITES for why this and not `.-value`."
  []
  (when-some [f (title-field)] (.-defaultValue f)))

(defn blank-committed-title!
  "Scribble [[committed-value-sentinel]] over that mirror.

  ALWAYS OUTSIDE A WINDOW — every caller is a plan builder, and
  [[measure-one!]] builds the plan after its frame alignment and before
  `t0`. It is one attribute write on one element, it runs identically for
  every sample of every measured arm, and it touches nothing React tracks:
  React's change tracker watches the `value` PROPERTY, so a scribble on
  the attribute cannot turn the interaction that follows into a no-op."
  []
  (when-some [f (title-field)]
    (set! (.-defaultValue f) committed-value-sentinel))
  nil)

(defn- note-refusal!
  "Keep the FIRST refusal's detail, so a run that dies on `N unverified of
  M` says which conjunct went and against what.

  `rf.bench.hicasso.lane/tally` counts and does not describe, and a bare count over a
  two-part check is a diagnosis the operator has to reproduce. One
  `swap!` on a slot that is written at most once a run is cheaper than
  that."
  [arm-id echo]
  (swap! !state update :first-refusal
         (fn [prev] (or prev (assoc echo :arm arm-id))))
  nil)

;; ---------------------------------------------------------------------------
;; The window
;; ---------------------------------------------------------------------------

(defn after-paint
  "A promise that resolves in the first task AFTER the browser has
  produced and painted the next frame.

  Used OUTSIDE every window, to let the page settle between operations."
  []
  (js/Promise. (fn [resolve]
                 (js/requestAnimationFrame (fn [] (js/setTimeout resolve 0))))))

(defn window!
  "ONE reading: `interact!` — a real DOM event on the mounted slice —
  through to the paint that follows it.

  Answers a promise of

      {:ms              t-paint - t0    the reading, and the estimand
       :commit-ms       t-commit - t0   how much of it closed inside the
                                        DOM event — the whole of what a
                                        `flushSync` window would have seen
       :to-raf-ms       t-raf - t0      through to the frame's rendering
                                        steps beginning
       :raf-to-paint-ms t-paint - t-raf style, layout, paint, and the task
                                        hop out of the rendering lifecycle
       :echo            whatever `observe-at-frame` answered, read INSIDE
                        those rendering steps and therefore before paint}

  The three-way decomposition is published rather than derived later
  because it is what a reader needs to interpret a tail quantile on this
  window: `:commit-ms` is the application's own work, and everything
  above it is the browser's rendering lifecycle plus the wait for a
  rendering opportunity — which is QUANTISED by the display's refresh and
  is in every row, including [[idle-plan]]'s.

  `t0` is taken immediately before `interact!`. A real user's latency
  starts at the input device rather than at `dispatchEvent`, and that
  segment belongs to the user agent rather than to the application;
  `PerformanceEventTiming` would carry it, at a `duration` rounded up to
  the nearest 8 ms, which is coarser than the whole of what `U1` is stated
  over. The omission is therefore deliberate and is a floor on the
  reading, never a ceiling.

  `after-commit!`, when a plan supplies one, runs after `t-commit` has
  been read and before the frame is asked for. It is the seam the positive
  control occupies, and it is a real one: effects, other listeners and
  layout reads all run there in an ordinary application, inside the user's
  wait and outside anything a `flushSync` window can see.

  A throw anywhere inside REJECTS rather than escaping. An exception
  raised in a `requestAnimationFrame` or `setTimeout` callback would
  otherwise leave this promise unsettled for ever and the run would die on
  `run.cjs`'s twenty-minute sentinel wearing a timeout's face."
  [{:keys [interact! after-commit! observe-at-frame]}]
  (js/Promise.
    (fn [resolve reject]
      (try
        (let [t0 (rf.bench.hicasso.lane/now-ms)]
          (interact!)
          (let [t-commit (rf.bench.hicasso.lane/now-ms)]
            (when after-commit! (after-commit!))
            (js/requestAnimationFrame
              (fn []
                (try
                  (let [t-raf (rf.bench.hicasso.lane/now-ms)
                        echo  (observe-at-frame)]
                    (js/setTimeout
                      (fn []
                        (try
                          (let [t-paint (rf.bench.hicasso.lane/now-ms)]
                            (resolve {:ms              (- t-paint t0)
                                      :commit-ms       (- t-commit t0)
                                      :to-raf-ms       (- t-raf t0)
                                      :raf-to-paint-ms (- t-paint t-raf)
                                      :echo            echo}))
                          (catch :default e (reject e))))
                      0))
                  (catch :default e (reject e)))))))
        (catch :default e (reject e))))))

;; ---------------------------------------------------------------------------
;; The arms
;; ---------------------------------------------------------------------------

(defn- idle-plan
  "The floor. No interaction at all — what one settle costs when the
  application does nothing. `:echo` is `:n/a` and banks as verified,
  because there is no echo to be late.

  It does NOT blank the mirror, and that is the arm rather than an
  omission: nothing here asks the application for anything, so nothing
  would put the mirror back, and a blank taken here would refuse every
  floor sample for the correct reason. What this row measures is the wait
  for a rendering opportunity with no application work in it at all."
  [_]
  {:interact!        (fn [] nil)
   :observe-at-frame (fn [] {:verified? true :echo :n/a})})

(defn title-echo-check
  "The observation both measured arms take: did the application commit
  `expect` onto the title field's mirror by the time the frame's rendering
  steps ran?

  ONE function, shared by [[keystroke-plan]], [[toggle-plan]] and
  [[echo-discrimination!]], so the negative control adjudicates the code
  the readings use rather than a copy of it. `:glass` is carried beside
  `:rendered` because the property staying put is a real fact whose
  opposite is a real regression — but it is NOT the discriminating half,
  and a reader of a refusal needs to see which of the two went."
  [expect]
  (let [f (title-field)]
    {:expect   expect
     :glass    (some-> f .-value)
     :rendered (committed-title)}))

(defn- title-echo-verified? [{:keys [expect glass rendered]}]
  (and (= expect rendered) (= expect glass)))

(defn- keystroke-plan
  "One character typed OVER the last one, so the field's length — and
  therefore the work — is the same at every sample.

  A user typing over a selection produces exactly this: the control's
  value moves first, the `input` event fires second. Appending instead
  would grow the title by one character per sample and make a run's last
  readings a different amount of work from its first, which is precisely
  the ramp the guard's `:position` factor exists to catch.

  The mirror is blanked before the interaction and checked inside the
  frame, so `want` reaching the glass proves the application put it there
  and not this file's own setup write. Namespace docstring, §THE ECHO IS
  READ OFF THE MIRROR THE APPLICATION WRITES."
  [_]
  (let [field (title-field)
        want  (str (:base-title @!state)
                   (nth rotor (mod (next-tick!) (count rotor))))]
    (blank-committed-title!)
    {:interact!        (fn []
                         (set-native-value! field want)
                         (.dispatchEvent field (js/Event. "input" #js {:bubbles true})))
     :observe-at-frame (fn []
                         (let [seen (title-echo-check want)]
                           {:verified? (title-echo-verified? seen) :echo seen}))}))

(defn- toggle-plan
  "A real click on the published checkbox.

  `HTMLElement.click()` and not a synthesised `change`: the user agent's
  activation behaviour flips `checkedness` WITHOUT going through the
  JavaScript property setter React tracks, which is what leaves React's
  tracked value stale and makes the change a real one.

  **And that same activation is why `checked` cannot be this arm's
  witness.** The flip happens in the user agent, before any handler runs,
  so a check over `checked` reads true whether or not the application ever
  saw the click. The witness is therefore the TITLE field's mirror, which
  the click cannot touch and which React rewrites when
  `::events/toggle-published` moves the draft and the editor's body
  re-runs. `title` is read off the property BEFORE the mirror is blanked —
  the application's current committed title, which a toggle does not
  change — so the arm asks *did a commit happen* rather than *did the
  title change*."
  [_]
  (let [box   (published-box)
        want  (not (.-checked box))
        title (some-> (title-field) .-value)]
    (blank-committed-title!)
    {:interact!        (fn [] (.click box))
     :observe-at-frame (fn []
                         (let [seen (assoc (title-echo-check title)
                                           :want-checked want
                                           :checked      (.-checked box))]
                           {:verified? (and (title-echo-verified? seen)
                                            (= want (:checked seen)))
                            :echo      seen}))}))

(defn- busy-wait!
  "Block the main thread for `ms`. A spin and not a `setTimeout`, because
  the control has to occupy the interval the window is measuring rather
  than yield it."
  [ms]
  (let [end (+ (rf.bench.hicasso.lane/now-ms) ms)]
    (loop [] (when (< (rf.bench.hicasso.lane/now-ms) end) (recur)))))

(defn- blocked-plan
  "[[keystroke-plan]] plus [[blocked-ms]] of blocked main thread on
  [[window!]]'s `after-commit!` seam.

  WHERE THE COST SITS IS THE CONTROL. Injecting it inside `interact!`
  would put it before `t-commit`, where a `flushSync` window would see
  every millisecond of it and the control would prove only that the
  instrument can measure a busy loop. On the seam it lands strictly
  between the commit and the frame — invisible to the window this one
  replaces, and fully inside this one."
  [state]
  (assoc (keystroke-plan state) :after-commit! (fn [] (busy-wait! blocked-ms))))

(def arms
  "The measured arms, floor first so it leads the schedule."
  [{:id :idle-frame  :plan idle-plan}
   {:id :keystroke   :plan keystroke-plan}
   {:id :toggle      :plan toggle-plan}
   {:id :ctl-blocked :plan blocked-plan :control? true}])

;; ---------------------------------------------------------------------------
;; Sampling
;; ---------------------------------------------------------------------------

(defn- bank-aux!
  "Record one sample's structural parts.

  The decomposition is kept per arm because the control's claim is about
  WHERE in the window the injected cost lands, and that cannot be
  re-derived from `:ms` afterwards.

  ## WARM-UP VISITS BANK HERE, AND ARE NARROWED OUT AGAIN BEFORE PUBLICATION

  This function is called from inside [[measure-one!]], which the lane
  calls for warm-up and measured visits alike and which is handed an ARM
  rather than a visit — so it cannot tell them apart, and it does not try.
  It banks everything, and [[structure-over-measured]] narrows the parts
  down to the measured population before they are published, using the
  mask `rf.bench.hicasso.lane/visit-plan` produces.

  The two consumers want different populations, which is why the
  narrowing is at publication rather than here. The TALLY wants every
  window: a verification is worth more the more of them it covers, and a
  warm-up window that failed to echo is exactly as damning as a measured
  one. The DISTRIBUTIONS want the measured visits only, because they are
  published as the decomposition of a summary taken over those."
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

  The arm's plan is built OUTSIDE the window — reading the node, deciding
  what the echo must be — so nothing but the interaction and the frame is
  inside it.

  ## EVERY WINDOW STARTS AT THE SAME POINT IN THE FRAME GRID

  [[after-paint]] runs first, OUTSIDE the reading, so the clock always
  starts in the first task after a paint. Without it this instrument
  cannot produce a reportable figure at all, and the arm-order guard says
  so rather than the author noticing.

  **A paint-bounded window is PREDECESSOR-DEPENDENT BY CONSTRUCTION.** Its
  length is dominated by the wait for the browser's next rendering
  opportunity, and those sit on a grid — so how long a sample waits
  depends on where in that grid the PREVIOUS sample left the clock. An arm
  that follows `:ctl-blocked`, which spans three intervals and ends with a
  frame already overdue, waits almost nothing; the same arm following
  `:keystroke`, which ends just after a paint, waits a whole interval. The
  first run of this instrument measured exactly that: `:idle-frame` read a
  full interval after `:keystroke` and zero after `:ctl-blocked`, ranges
  disjoint, and the guard REFUSED.

  That is a fault in the ARM and not in the guard's tolerance, which is
  not the arm's to move. Aligning every window to the grid is the repair:
  the phase is then a constant of the instrument rather than a property of
  whatever ran before.

  ## WHAT THE ALIGNMENT COSTS THE ESTIMAND, STATED RATHER THAN HIDDEN

  A real user's interaction arrives at a uniformly random phase in the
  grid; this one always arrives at the same phase, immediately after a
  paint, which is the phase with the LONGEST wait to the next rendering
  opportunity. So a reading taken here is the conservative end of the
  phase distribution — for a `p95` or `p99` latency line, the end that
  cannot flatter the application.

  The alternative is to randomise the phase deliberately, with a delay
  drawn uniformly from one interval before `t0`. That reproduces the
  user's phase distribution and is guard-clean for the same reason
  alignment is (an independent draw is not a predecessor effect), at the
  cost of needing far more samples to resolve a tail. **It is not built.**
  What would warrant building it: a reading whose `p95` sits close enough
  to a line that the difference between the worst phase and the mean phase
  decides it — at which point the honest answer is the user's
  distribution, not this one's."
  [arm]
  (.then (after-paint)
         (fn [_]
           (.then (window! ((:plan arm) !state))
                  (fn [r] (bank-aux! (:id arm) r) (:ms r))))))

;; ---------------------------------------------------------------------------
;; The negative control on the ECHO — taken once, before anything is measured
;; ---------------------------------------------------------------------------

(defn echo-discrimination!
  "THE SABOTAGE, BUILT IN. Perform the keystroke arm's SETUP MUTATION AND
  NOTHING ELSE — the native `value` write, with no DOM event, and
  therefore no Hicasso handler, no re-frame dispatch, no state write and
  no React commit — take one window over it, and require
  [[title-echo-check]] to REFUSE.

  Answers a promise of the refused observation, and REJECTS if the check
  passed.

  ## Why it is here rather than only in the suite

  The claim this instrument makes is that its window times a SLICE-APP
  ECHO. A check that would read true with the application removed cannot
  carry that claim however carefully the arm around it is written, and the
  version of this file that shipped had two such checks. A suite row is
  the right place to prove a repair; it is the wrong place to keep a
  driver honest, because the driver is what a quiet-box window runs and
  the suite is not. So the run itself asks the question, once, at a cost
  of one frame, and refuses to measure anything if the answer is wrong.

  ## What it suppresses, and why that is the whole chain

  Not firing the event removes every link at once: handler, dispatch,
  state write, commit. That is deliberate — a control that suppressed only
  one of them would prove the check sees THAT link and say nothing about
  the others. The window it takes is a real one, on the real page, through
  the same [[window!]] and the same observation the arms use; the only
  difference is the missing `dispatchEvent`.

  ## It leaves the page as it found it

  Both restoring writes undo scribbles THIS FUNCTION made — the property
  through the same pristine setter, the mirror by assignment — and neither
  is a model change, so neither needs a dispatch to unwind. React's change
  tracker still holds the application's own title afterwards, which is what
  keeps the first real keystroke a real change."
  []
  (let [field (title-field)
        model (.-value field)
        want  (str model (nth rotor 0))]
    (blank-committed-title!)
    (-> (window!
          {:interact!        (fn [] (set-native-value! field want))
           :observe-at-frame (fn []
                               (let [seen (title-echo-check want)]
                                 {:verified? (title-echo-verified? seen)
                                  :echo      seen}))})
        (.then (fn [{:keys [echo]}]
                 (set-native-value! field model)
                 (set! (.-defaultValue field) model)
                 (when (:verified? echo)
                   (throw (ex-info
                            (str "the echo check does not discriminate: a window whose "
                                 "interaction was the SETUP MUTATION ALONE — no DOM event, "
                                 "so no handler, no dispatch, no state write and no commit "
                                 "— still verified. Every reading this instrument could "
                                 "take would be timing a native control mutation through "
                                 "to the next frame rather than a slice-application echo, "
                                 "so nothing may be measured")
                            {:rf.error/id ::echo-not-discriminating
                             :observed    (:echo echo)})))
                 (:echo echo))))))

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

(defn boot!
  "Mount the slice into `container` on `frame-id`, opened on the article
  route, and answer a promise of the mounted handle.

  Two settles, both outside every window: React's commit and the initial
  events' drain are not the same tick, and the base title is read off the
  glass rather than copied out of `db/seed` — a copy here would be a
  second authority that a change to the seed would silently falsify.

  INSTALLING THE ADAPTER AND LEAVING REACT'S `act` ENVIRONMENT ARE NOT
  DONE HERE. Both are process-wide and belong to whoever owns the process:
  [[-main]] owns the bench page and does both, while the DOM self-test
  runs inside a shared browser bundle where a sibling suite's adapter and
  `act` flag have to survive it, so it installs its own through
  `re-frame.test-support`'s reset fixture and restores the flag itself."
  [container frame-id]
  (let [handle (rf.hicasso/mount! container
                         {:frame          frame-id
                          :initial-events [[::rf.hicasso.examples.slice.events/seed]
                                           [:rf.route/navigate
                                            {:to     rf.hicasso.examples.slice.routes/article
                                             :params {:slug article-slug}}]]}
                         [rf.hicasso.examples.slice.views/app {}])]
    (swap! !state assoc
           :container container
           :handle handle
           :tick 0
           :aux {}
           :first-refusal nil
           :echo-tally (rf.bench.hicasso.lane/tally))
    (-> (after-paint)
        (.then (fn [_] (after-paint)))
        (.then (fn [_]
                 (let [field (title-field)]
                   (when (nil? field)
                     (throw (ex-info (str "the slice mounted but its editor did not: "
                                          "#slice-title is not on the page, so there is "
                                          "no controlled field to type into")
                                     {:rf.error/id ::editor-absent
                                      :slug        article-slug})))
                   (swap! !state assoc :base-title (.-value field))
                   handle))))))

(defn teardown!
  "Take this root down and drop the container. Exposed because the DOM
  self-test mounts and unmounts around each of its rows."
  []
  (let [{:keys [container handle]} @!state]
    (when handle (rf.hicasso/unmount! handle))
    (when (and container (.-parentNode container))
      (.removeChild (.-parentNode container) container))
    (swap! !state assoc :container nil :handle nil :base-title nil)
    nil))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn- readings-by-arm [readings]
  (reduce (fn [m round]
            (reduce-kv (fn [m id xs] (update m id (fnil into []) xs)) m round))
          {}
          readings))

(defn measured-mask
  "Per arm id, the `:measured?` flag of each of that arm's visits IN THE
  ORDER [[bank-aux!]] appended them.

  Taken from `rf.bench.hicasso.lane/visit-plan` — the schedule both of the lane's loops
  walk — rather than re-derived from `warmup`, `samples` and
  `rf.bench.hicasso.lane/slot-order` here. A second reading of the schedule is the exact
  shape this lane has paid for twice, and it would be worse here than
  usual: a mask that drifted would not fail, it would publish a
  distribution over the wrong visits and say nothing.

  The order is safe to rely on because `rf.bench.hicasso.lane/rounds-async!` runs its
  visits SERIALLY — visit *n+1* starts only once *n*'s promise has
  resolved — so [[bank-aux!]]'s appends happen in plan order."
  [arms sampling rounds]
  (reduce (fn [m {:keys [arm measured?]}]
            (update m (:id arm) (fnil conj []) measured?))
          {}
          (rf.bench.hicasso.lane/visit-plan arms sampling rounds)))

(defn- keep-measured
  "`xs` narrowed to the visits the lane measured. REFUSES rather than
  truncating when the mask and the banked vector disagree, because the two
  disagreeing is exactly the drift the mask exists to prevent and a silent
  `map` over the shorter of them would hide it."
  [arm-id mask xs]
  (when-not (= (count mask) (count xs))
    (throw (ex-info (str "the measured mask and the banked decomposition disagree on "
                         arm-id ": the schedule plans " (count mask) " visits and "
                         (count xs) " were banked, so no narrowing of one by the other "
                         "is meaningful")
                    {:rf.error/id ::mask-mismatch
                     :arm         arm-id
                     :planned     (count mask)
                     :banked      (count xs)})))
  (into [] (keep-indexed (fn [i x] (when (nth mask i) x))) xs))

(defn structure-over-measured
  "`aux` — [[bank-aux!]]'s per-arm `{:commit :to-raf :raf-to-paint}`
  vectors — summarised over the MEASURED visits only, so `:structure`
  decomposes the `:summary` it is published beside rather than a larger
  population that happens to include it.

  Namespace docstring, §WHAT THIS FILE PUBLISHES, for the numbers."
  [arms sampling rounds aux]
  (let [mask (measured-mask arms sampling rounds)]
    (into {}
          (map (fn [[id {:keys [commit to-raf raf-to-paint]}]]
                 (let [m (get mask id)]
                   [id {:commit       (rf.bench.hicasso.lane/summarise (keep-measured id m commit))
                        :to-raf       (rf.bench.hicasso.lane/summarise (keep-measured id m to-raf))
                        :raf-to-paint (rf.bench.hicasso.lane/summarise (keep-measured id m raf-to-paint))}])))
          aux)))

(defn control-per-round
  "One adjudicated figure per round: the difference between
  `:ctl-blocked`'s median and `:keystroke`'s, in milliseconds.

  A DIFFERENCE and not a ratio, because the prediction is additive — the
  control injects a duration, not a multiple — and pairing each round with
  its own denominator is what `control-verdict-strict` asks for."
  [readings]
  (mapv (fn [round]
          (rf.bench.hicasso.lane/round4 (- (:p50 (rf.bench.hicasso.lane/summarise (get round :ctl-blocked)))
                          (:p50 (rf.bench.hicasso.lane/summarise (get round :keystroke))))))
        readings))

(defn take-plan!
  "Take the plan, publish the record, and adjudicate the two
  instrument-integrity verdicts. Answers a promise.

  Order matters and is the lane's: the record is published BEFORE
  `assert-verified!` can throw, so an operator reading a failed run has
  the evidence on the console rather than only the refusal."
  []
  (.then
    (rf.bench.hicasso.lane/rounds-async! arms sampling rounds measure-one!)
    (fn [{:keys [readings samples]}]
      (let [by-arm  (readings-by-arm readings)
            control (rf.bench.hicasso.lane/control-verdict-strict blocked-ms
                                                 (control-per-round readings)
                                                 control-slack)
            verdict (rf.bench.hicasso.lane/guard! samples "slice interaction-to-paint")]
        (rf.bench.hicasso.lane/record! :slice-echo
                      {:window     :interaction-to-paint
                       :population {:app   're-frame.hicasso.examples.slice
                                    :route :article
                                    :slug  article-slug}
                       :schedule   (assoc sampling
                                          :rounds           rounds
                                          :visits-per-arm   (* (+ (:warmup sampling)
                                                                  (:samples sampling))
                                                               rounds)
                                          :measured-per-arm (* (:samples sampling) rounds))
                       :populations populations
                       :summary    (into {} (map (fn [[id xs]] [id (rf.bench.hicasso.lane/summarise xs)])) by-arm)
                       :structure  (structure-over-measured arms sampling rounds (:aux @!state))
                       :control    control
                       :guard      (select-keys verdict [:refuse? :contaminated?
                                                         :unchecked? :tolerance])
                       :echo       (cond-> (rf.bench.hicasso.lane/tally-value (:echo-tally @!state))
                                     (:first-refusal @!state)
                                     (assoc :first-refusal (:first-refusal @!state)))
                       :runtime    (rf.bench.hicasso.lane/runtime-label)
                       :note       (str "No line is applied to any figure above. U1-U4 are "
                                        "read against this instrument in their own quiet-box "
                                        "window, not here.")})
        (set! (.-HICASSO_GUARD_REFUSED js/window) (boolean (:refuse? verdict)))
        (set! (.-HICASSO_CONTROL_FAILED js/window) (not (:ok? control)))
        (rf.bench.hicasso.lane/assert-verified! (:echo-tally @!state) "slice interaction-to-paint")
        nil))))

(defn ^:export -main []
  (rf/init! rf.adapter.uix/adapter)
  (rf.bench.hicasso.lane/leave-act-environment!)
  (if-not (rf.bench.hicasso.lane/self-test!)
    (rf.bench.hicasso.lane/fail! (str "the arm-order self-test failed — the copy of the schedule "
                     "rule this app is about to rely on no longer behaves like "
                     "the one the .cjs drivers use, so nothing may be measured"))
    (-> (boot! (or (js/document.getElementById "app") (rf.bench.hicasso.lane/fresh-container!))
               ::frame)
        ;; The echo's negative control runs BEFORE the first warm-up
        ;; visit and its throw travels the same `.catch` as any other
        ;; failure, so a run whose check does not discriminate dies here
        ;; rather than publishing a record nobody should read.
        (.then (fn [_] (echo-discrimination!)))
        (.then (fn [_] (take-plan!)))
        (.catch (fn [e] (rf.bench.hicasso.lane/fail! (rf.bench.hicasso.lane/describe-throw "slice-echo-clock-app" e))))
        (.then (fn [_] (rf.bench.hicasso.lane/done!)))))
  nil)
