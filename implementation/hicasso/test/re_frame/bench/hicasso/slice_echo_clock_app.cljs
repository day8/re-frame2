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
  2. THE ECHO, CHECKED PER SAMPLE. The typed character, or the flipped
     checkbox, is read out of the DOM INSIDE those rendering steps —
     before style, layout and paint — so a verified sample is one whose
     painted frame carried the echo. It banks into `lane/tally` and
     `lane/assert-verified!` refuses the run at `N unverified of M`.
  3. THE POSITIVE CONTROL, which is the discriminating one and is
     described next.

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
  exactly [[blocked-ms]]**.

  What makes it the right control here rather than a generic one is where
  the injected cost sits. A commit-bounded window would not see one
  millisecond of it — the block begins after the commit it stops at — so
  a rig that had quietly reverted to measuring a commit reports a control
  that predicted [[blocked-ms]] and measured nothing. The control
  therefore adjudicates the INSTRUMENT'S WINDOW and not merely its
  sensitivity.

  It is adjudicated under `lane/control-verdict-strict` — every round
  inside the band — and not under the overlap rule, because these legs are
  tens of milliseconds and nowhere near the 100 µs clamp Chrome puts on
  `performance.now()`. `lane/control-verdict`'s own docstring names that exactly: a
  batched window lifting the legs clear of the quantum is the condition
  under which the strict rule is the one to use.

  ## WHAT THIS FILE PUBLISHES, AND WHAT IT DELIBERATELY DOES NOT

  It publishes `{:n :min :max :p50 :p95 :p99}` per arm, the guard's
  verdict, the control's verdict, and the runtime label — and it compares
  none of them to anything.

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

      :idle-frame   no interaction at all — the settle and nothing else.
                    The FLOOR of any paint-bounded window on this box: what
                    a reading costs when the application does no work.
                    Without it a `p95` cannot be read, because the frame
                    boundary is in every other row too.

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

  [[boot!]] calls `h/mount!` with the slice's own `[views/app {}]` and the
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

  Owner: rf2-xa8wo."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.slice.events :as slice-events]
            [re-frame.hicasso.examples.slice.routes :as slice-routes]
            [re-frame.hicasso.examples.slice.views :as slice-views]))

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
  `lane/rounds!`'s docstring records that it puts the +27% step this lane
  sees after a site's sixth execution inside the warm-up. `:samples 12`
  is the same file's figure. **A tail quantile over 12 is mostly
  interpolation** — `lane/quantile`'s docstring prices exactly that — so
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

  Derived, not tuned. Each round's adjudicated figure is a DIFFERENCE of
  two per-round medians, and each of those two carries at most one
  rendering interval of quantisation, so the difference can sit up to two
  intervals — about 33 ms — from [[blocked-ms]] in the worst case before
  anything is wrong with the instrument. `0.5` of 50 ms is ±25 ms on the
  MEDIAN of twelve samples, where the per-sample quantisation has largely
  averaged out; it is deliberately generous, because the claim the control
  makes is THE INSTRUMENT SEES THE INJECTED COST, not THE MODEL IS EXACT."
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
  (lane/tally-value (:echo-tally @!state)))

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
        (let [t0 (lane/now-ms)]
          (interact!)
          (let [t-commit (lane/now-ms)]
            (when after-commit! (after-commit!))
            (js/requestAnimationFrame
              (fn []
                (try
                  (let [t-raf (lane/now-ms)
                        echo  (observe-at-frame)]
                    (js/setTimeout
                      (fn []
                        (try
                          (let [t-paint (lane/now-ms)]
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
  because there is no echo to be late."
  [_]
  {:interact!        (fn [] nil)
   :observe-at-frame (fn [] {:verified? true :echo :n/a})})

(defn- keystroke-plan
  "One character typed OVER the last one, so the field's length — and
  therefore the work — is the same at every sample.

  A user typing over a selection produces exactly this: the control's
  value moves first, the `input` event fires second. Appending instead
  would grow the title by one character per sample and make a run's last
  readings a different amount of work from its first, which is precisely
  the ramp the guard's `:position` factor exists to catch."
  [_]
  (let [field (title-field)
        want  (str (:base-title @!state)
                   (nth rotor (mod (next-tick!) (count rotor))))]
    {:interact!        (fn []
                         (set-native-value! field want)
                         (.dispatchEvent field (js/Event. "input" #js {:bubbles true})))
     :observe-at-frame (fn []
                         (let [got (.-value field)]
                           {:verified? (= want got) :echo got}))}))

(defn- toggle-plan
  "A real click on the published checkbox.

  `HTMLElement.click()` and not a synthesised `change`: the user agent's
  activation behaviour flips `checkedness` WITHOUT going through the
  JavaScript property setter React tracks, which is what leaves React's
  tracked value stale and makes the change a real one."
  [_]
  (let [box  (published-box)
        want (not (.-checked box))]
    {:interact!        (fn [] (.click box))
     :observe-at-frame (fn []
                         (let [got (.-checked box)]
                           {:verified? (= want got) :echo got}))}))

(defn- busy-wait!
  "Block the main thread for `ms`. A spin and not a `setTimeout`, because
  the control has to occupy the interval the window is measuring rather
  than yield it."
  [ms]
  (let [end (+ (lane/now-ms) ms)]
    (loop [] (when (< (lane/now-ms) end) (recur)))))

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
  re-derived from `:ms` afterwards. Warm-up samples bank here too — a
  verification is worth more the more of them there are, and the count
  published beside the tally says how many."
  [arm-id {:keys [commit-ms to-raf-ms raf-to-paint-ms echo]}]
  (let [t (:echo-tally @!state)]
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
  inside it."
  [arm]
  (.then (window! ((:plan arm) !state))
         (fn [r] (bank-aux! (:id arm) r) (:ms r))))

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
  (let [handle (h/mount! container
                         {:frame          frame-id
                          :initial-events [[::slice-events/seed]
                                           [:rf.route/navigate
                                            {:to     slice-routes/article
                                             :params {:slug article-slug}}]]}
                         [slice-views/app {}])]
    (swap! !state assoc
           :container container
           :handle handle
           :tick 0
           :aux {}
           :echo-tally (lane/tally))
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
    (when handle (h/unmount! handle))
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

(defn control-per-round
  "One adjudicated figure per round: the difference between
  `:ctl-blocked`'s median and `:keystroke`'s, in milliseconds.

  A DIFFERENCE and not a ratio, because the prediction is additive — the
  control injects a duration, not a multiple — and pairing each round with
  its own denominator is what `control-verdict-strict` asks for."
  [readings]
  (mapv (fn [round]
          (lane/round4 (- (:p50 (lane/summarise (get round :ctl-blocked)))
                          (:p50 (lane/summarise (get round :keystroke))))))
        readings))

(defn take-plan!
  "Take the plan, publish the record, and adjudicate the two
  instrument-integrity verdicts. Answers a promise.

  Order matters and is the lane's: the record is published BEFORE
  `assert-verified!` can throw, so an operator reading a failed run has
  the evidence on the console rather than only the refusal."
  []
  (.then
    (lane/rounds-async! arms sampling rounds measure-one!)
    (fn [{:keys [readings samples]}]
      (let [by-arm  (readings-by-arm readings)
            control (lane/control-verdict-strict blocked-ms
                                                 (control-per-round readings)
                                                 control-slack)
            verdict (lane/guard! samples "slice interaction-to-paint")]
        (lane/record! :slice-echo
                      {:window     :interaction-to-paint
                       :population {:app   're-frame.hicasso.examples.slice
                                    :route :article
                                    :slug  article-slug}
                       :schedule   (assoc sampling :rounds rounds)
                       :summary    (into {} (map (fn [[id xs]] [id (lane/summarise xs)])) by-arm)
                       :structure  (into {}
                                         (map (fn [[id {:keys [commit to-raf raf-to-paint]}]]
                                                [id {:commit       (lane/summarise commit)
                                                     :to-raf       (lane/summarise to-raf)
                                                     :raf-to-paint (lane/summarise raf-to-paint)}]))
                                         (:aux @!state))
                       :control    control
                       :guard      (select-keys verdict [:refuse? :contaminated?
                                                         :unchecked? :tolerance])
                       :echo       (lane/tally-value (:echo-tally @!state))
                       :runtime    (lane/runtime-label)
                       :note       (str "No line is applied to any figure above. U1-U4 are "
                                        "read against this instrument in their own quiet-box "
                                        "window, not here.")})
        (set! (.-HICASSO_GUARD_REFUSED js/window) (boolean (:refuse? verdict)))
        (set! (.-HICASSO_CONTROL_FAILED js/window) (not (:ok? control)))
        (lane/assert-verified! (:echo-tally @!state) "slice interaction-to-paint")
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
        (.then (fn [_] (take-plan!)))
        (.catch (fn [e] (lane/fail! (lane/describe-throw "slice-echo-clock-app" e))))
        (.then (fn [_] (lane/done!)))))
  nil)
