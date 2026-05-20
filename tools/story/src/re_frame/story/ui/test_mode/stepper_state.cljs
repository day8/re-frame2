(ns re-frame.story.ui.test-mode.stepper-state
  "CLJS-side local state for the play step-debugger (rf2-ulw5m + spec/009
  §Play step-debugger).

  The step-debugger is the Storybook Interactions-panel equivalent for
  Story's `:test` mode pane: step / pause / rewind / step-back /
  breakpoint controls over the variant's `:play-script`. The runtime
  substrate already exists in `re-frame.story.play` (`begin-stepper!` /
  `step-once!` / `end-stepper!`); this namespace is the per-variant local
  state surface the view consumes + the mutators that drive the
  substrate.

  ## Per-variant slot shape

      {:active?        <bool>         ; the stepper is in flight
       :auto-playing?  <bool>         ; the interval is ticking
       :cursor         <int>          ; number of events dispatched so far
       :total          <int>          ; count of play-script events in this run
       :play-events    <vector>       ; immutable snapshot of the events
                                      ;   (derived from :play-script via
                                      ;    re-frame.story.play/variant-play-events)
       :statuses       <vector>       ; `stepper-pure/enrich-statuses` rows
       :breakpoints    #{<int>}       ; step indices that pause auto-play
       :epoch-stack    <vector>       ; per-step :epoch-id pre-images, for
                                      ;   step-back via epoch/restore-epoch
       :interval-id    <int|nil>      ; the auto-play setInterval handle
       :tick-ms        <int>}         ; ms between auto-play steps

  ## Step-back semantics

  The runtime substrate only steps FORWARD (`step-once!`). To support
  step-back we leverage the same `epoch/restore-epoch` substrate the
  scrubber uses: BEFORE each forward step we capture the variant frame's
  current `:epoch-id` (the head of `epoch-history`) and push it onto
  `:epoch-stack`. A step-back POPS the stack and `restore-epoch`s the
  previous frame state, then decrements `:cursor` so the UI tracks the
  position. This gives true step-back without modifying the runtime.

  Rewind = repeated step-back to step 0. We implement it as a single
  restore against the bottom-of-stack epoch-id plus a cursor reset.

  ## Auto-play

  When `:auto-playing?` is true a `setInterval` (default 600ms) drives
  forward steps. The tick checks `breakpoint-hit?` BEFORE dispatching;
  hitting a breakpoint pauses the interval without consuming the step.

  Tear-down: `end!` is called when the user clicks 'Stop', when the pane
  unmounts, or when the stepper hits the end of the play sequence.
  Clears the interval and the per-frame play state."
  (:require [reagent.core                                 :as r]
            [re-frame.epoch                               :as epoch]
            [re-frame.story.play                          :as play]
            [re-frame.story.runtime                       :as runtime]
            [re-frame.story.assertions                    :as assertions]
            [re-frame.story.ui.test-mode.pure             :as test-mode-pure]
            [re-frame.story.ui.test-mode.stepper-pure     :as stepper-pure]))

;; ---- ratom ---------------------------------------------------------------

(defonce results-atom (r/atom {}))

(def ^:const default-tick-ms 600)

;; ---- helpers -------------------------------------------------------------

(defn- current-epoch-id
  "Read the variant frame's current epoch-id (the head of its history).
  Returns nil when the history is empty (production elision / disabled
  ring buffer)."
  [variant-id]
  (-> (epoch/epoch-history variant-id) last :epoch-id))

(defn- recompute-statuses
  "Pure: re-derive the enriched step list from a slot. Called after every
  mutator that moves the cursor / changes breakpoints / records a step."
  [slot]
  (let [{:keys [play-events cursor breakpoints]} slot
        ;; Read the assertion accumulator off the variant frame so the
        ;; outcome glyphs land as the run progresses. The variant frame
        ;; lives in `play/stepper-state` so we read the assertions via
        ;; the assertions module.
        records (assertions/read-assertions (:variant-id slot))]
    (assoc slot :statuses
           (stepper-pure/enrich-statuses
             (test-mode-pure/play-step-statuses play-events records)
             cursor
             breakpoints))))

(defn- clear-interval! [slot]
  (when-let [hid (:interval-id slot)]
    (js/clearInterval hid))
  (assoc slot :interval-id nil :auto-playing? false))

(declare auto-tick!)

(defn- set-interval! [variant-id tick-ms]
  (js/setInterval #(auto-tick! variant-id) tick-ms))

;; ---- public mutators -----------------------------------------------------

(defn begin!
  "Initialise a step-by-step run for `variant-id`. Tears down any prior
  stepper state, then re-allocates the variant frame (so the run starts
  fresh) and primes the substrate via `play/begin-stepper!`.

  Asynchronous: the caller may await the returned promise to drive a
  follow-up auto-resume, but the UI does not need to — the slot's
  `:active?` flag drives the view."
  [variant-id]
  ;; If a prior stepper is active for this variant, tear it down first.
  (when (get @results-atom variant-id)
    (swap! results-atom update variant-id clear-interval!))
  (play/end-stepper! variant-id)
  ;; Re-allocate the variant frame so the stepper starts from the
  ;; documented initial state.
  (-> (runtime/reset-variant variant-id)
      (.then  (fn [_]
                ;; Per rf2-0wrud `:play-script` is the canonical AND ONLY
                ;; phase-4 slot. `play/variant-play-events` derives the
                ;; flat event-vec list from the variant's :play-script
                ;; body — the same shape the legacy `:play` slot carried.
                (let [play-events (play/variant-play-events variant-id)
                      total       (count play-events)]
                  (play/begin-stepper! variant-id)
                  (swap! results-atom assoc variant-id
                         {:variant-id     variant-id
                          :active?        true
                          :auto-playing?  false
                          :cursor         0
                          :total          total
                          :play-events    (vec play-events)
                          :statuses       []
                          :breakpoints    #{}
                          :epoch-stack    [(current-epoch-id variant-id)]
                          :interval-id    nil
                          :tick-ms        default-tick-ms})
                  (swap! results-atom update variant-id recompute-statuses)
                  nil)))
      (.catch (fn [_]
                ;; If reset-variant rejects we still leave the slot
                ;; cleared so the UI returns to the inactive state.
                (swap! results-atom dissoc variant-id)
                nil))))

(defn step!
  "Dispatch the next event in the play sequence. No-ops when the stepper
  is parked at the end or not active."
  [variant-id]
  (let [slot (get @results-atom variant-id)]
    (when (stepper-pure/can-step? slot)
      ;; Capture the pre-step epoch-id so step-back can restore it.
      (let [pre-id (current-epoch-id variant-id)]
        (play/step-once! variant-id)
        (swap! results-atom update variant-id
               (fn [s]
                 (-> s
                     (update :cursor inc)
                     (update :epoch-stack (fnil conj []) pre-id)
                     recompute-statuses)))))))

(defn step-back!
  "Restore the variant frame to its previous epoch and decrement the
  cursor. Uses `epoch/restore-epoch` against the top-of-stack id;
  no-ops when the stepper is parked at step 0 or not active.

  Step-back never re-dispatches the popped event — that would create a
  fresh epoch and lose the deterministic mapping between cursor and
  history. The popped event simply becomes pending again."
  [variant-id]
  (let [slot (get @results-atom variant-id)]
    (when (stepper-pure/can-step-back? slot)
      (let [stack    (or (:epoch-stack slot) [])
            ;; Pop the head; the NEW head is the pre-image of the
            ;; previous step (the cursor we're stepping back to).
            new-stack (vec (butlast stack))
            target-id (peek new-stack)]
        (when target-id
          (epoch/restore-epoch variant-id target-id))
        (swap! results-atom update variant-id
               (fn [s]
                 (-> s
                     (update :cursor (fn [c] (max 0 (dec (or c 0)))))
                     (assoc  :epoch-stack new-stack)
                     recompute-statuses)))))))

(defn rewind!
  "Restore the variant frame to the pre-play epoch and reset the cursor.
  Equivalent to repeated `step-back!` from the current cursor. No-ops
  when the stepper is at step 0 or not active."
  [variant-id]
  (let [slot (get @results-atom variant-id)]
    (when (stepper-pure/can-rewind? slot)
      (let [stack (or (:epoch-stack slot) [])
            ;; The pre-play epoch-id is the bottom of the stack (the
            ;; first pre-image we pushed on `begin!`).
            seed  (first stack)]
        (when seed
          (epoch/restore-epoch variant-id seed))
        ;; Also reset the assertion accumulator so a fresh forward run
        ;; doesn't pile new records on top of the old ones.
        (assertions/reset-trace-accumulators! variant-id)
        (swap! results-atom update variant-id
               (fn [s]
                 (-> s
                     (assoc :cursor      0
                            :epoch-stack [seed])
                     clear-interval!
                     recompute-statuses)))))))

(defn pause!
  "Stop auto-play. Idempotent."
  [variant-id]
  (swap! results-atom update variant-id (fn [s] (when s (clear-interval! s)))))

(defn resume!
  "Start auto-play. No-ops when the stepper is at the end or already
  ticking. Default tick is 600ms — the slot's `:tick-ms` slot can be
  overridden by a future controls widget."
  [variant-id]
  (let [slot (get @results-atom variant-id)]
    (when (stepper-pure/can-resume? slot)
      (let [tick (or (:tick-ms slot) default-tick-ms)
            hid  (set-interval! variant-id tick)]
        (swap! results-atom update variant-id
               (fn [s] (assoc s :auto-playing? true :interval-id hid)))))))

(defn toggle-breakpoint!
  "Toggle a breakpoint on step `index` (0-based). No-ops when the slot
  isn't active. Auto-play hitting a breakpoint pauses BEFORE the event
  is dispatched (see `auto-tick!`)."
  [variant-id index]
  (when (and (integer? index)
             (get @results-atom variant-id))
    (swap! results-atom update variant-id
           (fn [s]
             (let [bps (or (:breakpoints s) #{})
                   bps' (if (contains? bps index)
                          (disj bps index)
                          (conj bps index))]
               (-> s
                   (assoc :breakpoints bps')
                   recompute-statuses))))))

(defn end!
  "Tear down the stepper for `variant-id`. Clears the interval, drops
  the substrate's per-frame state, and removes the local slot."
  [variant-id]
  (swap! results-atom update variant-id (fn [s] (when s (clear-interval! s))))
  (play/end-stepper! variant-id)
  (swap! results-atom dissoc variant-id))

;; ---- internal auto-play tick --------------------------------------------

(defn- auto-tick!
  "One auto-play tick. Pauses on breakpoint hit or end-of-sequence; else
  takes one forward step."
  [variant-id]
  (let [slot (get @results-atom variant-id)]
    (cond
      (not (:active? slot))
      (pause! variant-id)

      (stepper-pure/at-end? (:cursor slot) (:total slot))
      (pause! variant-id)

      (stepper-pure/breakpoint-hit? (:cursor slot)
                                    (:breakpoints slot))
      (pause! variant-id)

      :else
      (step! variant-id))))
