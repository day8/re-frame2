(ns re-frame.story.play
  "Phase 4 — play-script trace listener + stepper helpers.

  `:script` is the canonical and ONLY phase-4 surface. This module
  holds the per-frame trace listener and the step-by-step play-stepper
  helpers. The rich-DSL execution itself lives in
  `re-frame.story.play.runner-events`.

  `:rf.assert/dispatched?` / `:rf.assert/effect-emitted` /
  `:rf.assert/no-warnings` PROJECT their fact from the epoch tape (the
  SSOT, `re-frame.story.assertions/dispatched-events` / `warnings` /
  `emitted-fx`), the same source the run-result evidence slots read.

  The per-frame listener is PURELY the egress seam: the load-bearing
  PRIVACY-suppression gate (Spec 009 §Privacy — default-dropping
  `:sensitive?` events + the `rf.story.config/note-suppressed!` redaction counter)
  and the synchronous handler-exception capture (`pending-exceptions`). It
  touches no assertions accumulator — warnings / fx / dispatched are all
  answerable from the epoch tape + stub-call log, the SSOT.

  ## What this module does

  A `:script` body carries tagged steps. Authors wrap event
  vectors as `[:dispatch-sync <event-vec>]` (or `:dispatch` for async).
  The `:rf.assert/*` events ride the same dispatch path — re-frame's
  interceptor chain runs the registered assertion handler (see
  `re-frame.story.assertions`), which appends a record into
  `[:rf.story/assertions]` on the variant frame's app-db. Per `004-Assertions.md`
  §Record-don't-throw semantics the assertion never throws; the play sequence runs to completion
  regardless of which assertions fail.

  ## Privacy egress seam (the per-frame trace listener)

  We register a per-frame trace listener at the start of the play
  sequence. Its two — and only — LOAD-BEARING jobs:

  - PRIVACY (Spec 009 §Privacy): default-drop `:sensitive? true` events
    BEFORE anything else and bump the UI redaction counter
    (`rf.story.config/note-suppressed!`);
  - synchronous handler-exception capture into `pending-exceptions`
    (drained into the assertions list between dispatches).

  The listener feeds no assertions accumulator: `:rf.assert/no-warnings` /
  `:rf.assert/effect-emitted` / `:rf.assert/dispatched?` project from the
  epoch tape + stub-call log (the SSOT).

  ## Execution lives elsewhere

  This module executes no play. Phase 4 — the fold, the settled
  boundary, `:wait` / `:click` / `:assert-*` and the DOM and browser
  settlement hooks — runs in `re-frame.story.play.runner-events`: the
  canvas auto-run drives `run!`, and the step-debugger drives
  `step-once!` → `run-step!`.

  ## Public API

  - `install-trace-listener!` / `remove-trace-listener!` — per-frame
                         trace-listener install + teardown; idempotent.
  - `play-stepper-active?` / `step-once!` — UI play-stepper hooks."
  (:require [re-frame.core             :as rf]
            ;; The canonical RAW trace-event frame reader
            ;; (`re-frame.trace/frame-of`) reads the frame off a trace event.
            [re-frame.trace            :as rf.trace]
            [re-frame.story.args       :as rf.story.args]
            [re-frame.story.assertions :as rf.story.assertions]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.error      :as rf.story.error]
            [re-frame.story.late-bind  :as rf.story.late-bind]
            [re-frame.story.plan       :as rf.story.plan]
            [re-frame.story.play.runner :as rf.story.play.runner]
            [re-frame.story.registrar  :as rf.story.registrar]))

;; ---------------------------------------------------------------------------
;; Per-frame trace listener — the Spec 009 §Privacy egress seam
;;
;; One listener per variant frame. Filters trace events by `:frame`
;; (per spec/009 §Dispatch correlation) and (a) drops `:sensitive?`
;; events + bumps the redaction counter, (b) captures synchronous
;; handler-exceptions into `pending-exceptions`. Idempotent. It does NOT
;; feed any assertions accumulator — assertions project from the epoch
;; tape + stub-call log.
;; ---------------------------------------------------------------------------

(defn- listener-id [frame-id]
  (keyword "re-frame.story.play"
           (str "trace-" (when frame-id (str frame-id)))))

;; Per-frame pending-exception accumulator. The listener captures
;; `:rf.error/handler-exception` synchronously (from inside the running
;; drain) and stores them here; the play-runner drains the slot AFTER
;; each dispatch-sync settles so it can record an assertion via
;; dispatch-sync without re-entering an in-flight drain.
(defonce
  ^{:doc "frame-id → vector of pending exception trace events captured
         during the most recent play dispatch. Drained by
         `drain-pending-exceptions!` after each event."}
  pending-exceptions
  (atom {}))

(defn- record-pending-exception!
  [frame-id ev]
  (swap! pending-exceptions update frame-id (fnil conj []) ev))

(defn- listener-for-frame
  "Build the trace-event listener for `frame-id` — the Spec 009 §Privacy
  egress seam for the play-runner. Skips events that don't target the
  frame so cross-frame traffic (e.g. the default frame's lifecycle
  events) stays out of this frame's egress path.

  Listener executes INSIDE the running dispatch drain, so it never
  re-enters dispatch-sync — it stores side-effects in atoms and lets
  the play-runner drain them between events.

  Two LOAD-BEARING jobs (warnings / fx / dispatched are answered from the
  epoch tape + stub-call log, the SSOT — not from this listener):

  1. PRIVACY (Spec 009 §Privacy + EP-0015): events whose
     `:sensitive?` flag is true are DROPPED here when Story's local-render
     egress profile redacts (`:rf.egress/local-redacted` — the default).
     The suppressed-events counter bumps (`rf.story.config/note-suppressed!`) for the
     targeted frame so the UI can surface a `[● REDACTED]` hint. This is
     the egress gate — it runs BEFORE the listener does anything else, so
     a sensitive event never reaches the exception capture below.

  2. Synchronous pipeline-exception capture: a non-suppressed pipeline
     exception (`:rf.error/handler-exception` /
     `:rf.error/coeffect-exception` / `:rf.error/interceptor-exception`)
     is stashed into `pending-exceptions`, which the play-runner drains
     AFTER each dispatch-sync settles (so it can record an assertion via
     dispatch-sync without re-entering the in-flight drain). The capture
     spans the WHOLE pipeline — a play-script event whose cofx injector or
     user interceptor throws surfaces just as a handler throw does. The
     shared `rf.story.error/pipeline-exception-event?` predicate is the single
     projection."
  [frame-id]
  (fn [ev]
    (when (= frame-id (rf.trace/frame-of ev))
      (cond
        ;; Resolve the suppress decision against THIS frame's egress
        ;; profile (the listener is already frame-scoped). Revealing a
        ;; sibling frame never opens this frame's gate.
        (rf.story.config/suppress-sensitive? ev frame-id)
        (rf.story.config/note-suppressed! frame-id)

        (rf.story.error/pipeline-exception-event? frame-id ev)
        (record-pending-exception! frame-id ev)

        :else nil))))

(defn drain-pending-exceptions!
  "Append any pending exception trace events from `frame-id` as
  assertion records on the variant's assertions slot. Called by the
  play-runner after each dispatch-sync returns (i.e. after the drain
  has settled) AND by the runtime's phase-1 loaders + phase-2 events
  drivers so handler exceptions from any phase land in the assertions
  list rather than evaporating into trace-event noise.

  `phase` is stamped onto each record — callers pass `:phase-1-loaders`,
  `:phase-2-events`, or `:phase-4-play` to match the originating phase.
  Clears the pending slot on exit.

  Public so the rich-DSL runner (`runner-events`) and the runtime's
  loader/events drivers can drain between dispatches. The
  `:rf.story/assertions` contract is load-bearing — the test-mode pane,
  the chrome-level widget, and the Xray assertions panel all read off this
  slot."
  [frame-id phase]
  (let [evs (get @pending-exceptions frame-id [])]
    (when (seq evs)
      (doseq [ev evs]
        (let [event-vec (get-in ev [:tags :event])
              msg       (get-in ev [:tags :exception-message])
              exc       (get-in ev [:tags :exception])]
          ;; The trace event may carry a pre-extracted
          ;; `:exception-message` (and a possibly-nil `:exception`); thread
          ;; it as the explicit `:message` override on the shared
          ;; projection so the message survives even without the throwable.
          ;; Preserve the originating `:operation` / `:failing-id` so a
          ;; captured cofx / interceptor failure is distinguishable from a
          ;; handler throw on the record.
          (rf.story.assertions/record!
            frame-id
            (rf.story.error/exception-record frame-id phase event-vec exc
                                          {:message    msg
                                           :operation  (:operation ev)
                                           :failing-id (get-in ev [:tags :failing-id])}))))
      (swap! pending-exceptions assoc frame-id []))))

(defn install-trace-listener!
  "Register the per-frame privacy egress seam (the trace listener that
  default-drops `:sensitive?` events + captures handler-exceptions).
  Idempotent — re-registering replaces. Returns the listener id."
  [frame-id]
  (when rf.story.config/enabled?
    (let [id (listener-id frame-id)]
      (rf/register-listener! :trace id (listener-for-frame frame-id))
      id)))

(defn remove-trace-listener!
  "Tear down the per-frame trace listener for `frame-id`. Idempotent."
  [frame-id]
  (when rf.story.config/enabled?
    (rf/unregister-listener! :trace (listener-id frame-id))
    nil))

;; ---------------------------------------------------------------------------
;; Single-event dispatch — the step-debugger's fallback executor
;; ---------------------------------------------------------------------------

(defn- dispatch-one!
  "Dispatch a single play event — `step-once!`'s fallback for a dispatch
  step when the rich-DSL executor (the `:run-play-step` late-bind hook) is
  absent. Wraps `dispatch-sync`
  with the exception-record path so phase-4 errors land in the
  assertion list rather than aborting the sequence (`004-Assertions.md` §Record-don't-throw semantics +
  `002-Runtime.md` §Error projection).

  The re-frame router catches handler exceptions and emits a
  `:rf.error/handler-exception` trace event rather than re-throwing;
  the per-frame trace listener captures those into a pending slot,
  which `drain-pending-exceptions!` flushes into the assertions list
  *after* this dispatch-sync settles.

  An exception that escapes the interceptor chain (e.g. a setup error)
  lands in our local try/catch and gets recorded directly."
  [frame-id event]
  (try
    (rf/dispatch-sync event {:frame frame-id})
    (catch #?(:clj Throwable :cljs :default) e
      (rf.story.assertions/record!
        frame-id
        (rf.story.error/exception-record frame-id :phase-4-play event e))))
  ;; After the drain settles, walk any captured handler-exception
  ;; trace events into assertion records. Safe to dispatch-sync now —
  ;; the drain has ended.
  (drain-pending-exceptions! frame-id :phase-4-play))

;; ---------------------------------------------------------------------------
;; The stepped program — read from the COMPILED plan (rf2-499z)
;;
;; The step-debugger (`variant-play-steps`) and the scrubber
;; (`variant-play-events`) both show the program the auto-run path
;; EXECUTED, so they read it where the runtime reads it: the compiled
;; plan's `[:world :scripts]`, never the raw `:script` slot. The compiler
;; is what substitutes `[:arg key]` placeholders, prepends `:compose`d
;; fragments' scripts and lowers `:plays` into named scripts. A raw read
;; saw none of that: an `[:arg]` script stepped its placeholder verbatim,
;; a composed variant stepped without its fragments, and a `:plays`
;; variant stepped nothing at all.
;; ---------------------------------------------------------------------------

(defn- stepped-program
  "The folded, arg-substituted step vector for `variant-id`: the scripts of
  the compiled plan's AUTO-RUNNABLE plays, concatenated in order — the
  exact program `runtime/run-phase-4!` executes
  (`rf.story.play.runner/auto-runnable-plays` over `[:world :scripts]`).
  A variant that auto-runs nothing yields its PRIMARY compiled play
  instead, so a script the author opted out of auto-running can still be
  stepped by hand.

  `opts` is the `run-variant` opts map (`:active-modes` /
  `:cell-overrides`), folded into the compile through
  `rf.story.args/run-arg-layers` exactly as `runtime/prepare-context`
  folds it — pass the SAME opts the run or the preparation received. An
  unregistered variant yields `[]`; a plan-construction failure throws,
  as the runtime would for the same variant."
  [variant-id opts]
  (if-not (rf.story.registrar/handler-meta :variant variant-id)
    []
    (let [plan  (rf.story.plan/variant-plan
                  variant-id
                  {:run-args (rf.story.args/run-arg-layers variant-id opts)})
          plays (get-in plan [:world :scripts] [])
          auto  (rf.story.play.runner/auto-runnable-plays plays)]
      (vec (if (seq auto)
             (mapcat :script auto)
             (:script (first plays)))))))

(defn variant-play-events
  "The flat event-vector list of `variant-id`'s stepped program (see
  `stepped-program`): one per `:dispatch` / `:dispatch-sync` step. Other
  step types have no event-vector representation and are skipped. The
  scrubber matches these against the run's `:epoch-tape`, so `opts`
  should be the opts that run received.

  A plan-construction failure yields `[]` rather than throwing. An
  `[:arg]` that only an active mode or cell override supplies cannot
  compile without those opts, and the scrubber's contract is to degrade to
  'no epoch buffer', never to lose the run result it is attached to."
  ([variant-id] (variant-play-events variant-id nil))
  ([variant-id opts]
   (let [steps (try (stepped-program variant-id opts)
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                      (if (= 'rf.story/variant-plan (:where (ex-data e)))
                        []
                        (throw e))))]
     (into []
           (keep (fn [step]
                   (when (and (vector? step)
                              (#{:dispatch :dispatch-sync} (first step))
                              (vector? (second step)))
                     (second step))))
           steps))))

;; ---------------------------------------------------------------------------
;; UI play-stepper hook
;;
;; The stepper walks the FULL compiled program (`stepped-program`, every
;; step type — `:dispatch` / `:dispatch-sync` / `:wait` / `:click` /
;; `:type` / `:assert-db` / `:assert-dom`), driving each step through the SAME
;; rich-DSL executor the canvas auto-run path uses
;; (`runner-events/run-step!`, fetched via the `:run-play-step` late-bind
;; hook to avoid the play ↔ runner-events cycle). The slot holds STEPS,
;; not bare event vectors, so the cursor / total stay honest and assert
;; outcomes surface during a stepped run.
;; ---------------------------------------------------------------------------

(defonce
  ^{:doc "Per-frame play-stepper state. `{frame-id → {:remaining vec,
         :ran vec, :results vec}}` where `:remaining` / `:ran` carry
         coerced `:script` STEPS and `:results` carries the per-step
         result records the
         rich-DSL executor returned. The UI shell consumes this to
         render the stepper widget. Used only when the play sequence is
         being driven step-by-step rather than auto-run by
         `runner-events/run!`."}
  stepper-state
  (atom {}))

(defn variant-play-steps
  "The FULL step vector the step-debugger walks for `variant-id`. Unlike
  `variant-play-events` (which drops every non-dispatch step) this returns
  EVERY step the rich-DSL runner recognises, in order.

  Read from the COMPILED plan (`stepped-program`), so it is the program
  the auto-run path executes: folded (a shipping `:assert-db` /
  `:assert-dom` step is already the canonical `[:assert …]` checkpoint),
  `[:arg]`-substituted, `:compose`d fragments prepended, `:plays` lowered.
  `opts` is the `run-variant` opts map the frame was prepared with.

  Pure aside from the registrar reads; works on JVM + CLJS."
  ([variant-id] (variant-play-steps variant-id nil))
  ([variant-id opts] (stepped-program variant-id opts)))

(defn play-stepper-active?
  [frame-id]
  (contains? @stepper-state frame-id))

(defn begin-stepper!
  "Initialise a step-by-step play run for `frame-id`. The UI's
  play-stepper widget calls `step-once!` to advance one step.

  Seeds the FULL compiled program (every step type, `variant-play-steps`).
  `opts` is the `run-variant` opts map the frame was PREPARED with, so the
  seeded steps substitute the same args the prepared frame saw."
  ([frame-id] (begin-stepper! frame-id nil))
  ([frame-id opts]
   (when rf.story.config/enabled?
     (swap! pending-exceptions assoc frame-id [])
     (install-trace-listener! frame-id)
     ;; Reset the per-dispatch-step settle boundaries at the START
     ;; of a fresh stepping session (the stepper analogue of `run!`'s reset),
     ;; so `step-once!`'s per-step appends window onto THIS session's epoch tape
     ;; rather than accumulating onto a previous run's boundaries. Fetched via
     ;; the late-bind seam because `play` cannot `:require` `runner-events`.
     (when-let [clear! (rf.story.late-bind/get-fn :clear-step-boundaries)]
       (clear! frame-id))
     (swap! stepper-state assoc frame-id
            {:remaining (variant-play-steps frame-id opts)
             :ran       []
             :results   []})
     nil)))

(defn step-once!
  "Advance the play stepper for `frame-id` by one step. Executes the
  step through the rich-DSL executor (`runner-events/run-step!` via the
  `:run-play-step` late-bind hook) so EVERY step type runs in the
  debugger exactly as it does on the live canvas. Returns the step that
  ran, or nil when no steps remain.

  Every step type runs through the executor. If the executor hook is
  absent (a host that has not loaded `runner-events`) it
  falls back to `dispatch-one!` for dispatch steps and a no-op record for
  the rest, so the cursor stays honest."
  [frame-id]
  (when rf.story.config/enabled?
    (let [{:keys [remaining]} (get @stepper-state frame-id)
          step (first remaining)]
      (when step
        (let [idx     (count (:ran (get @stepper-state frame-id)))
              run-fn  (rf.story.late-bind/get-fn :run-play-step)
              ;; rf2-iz0t8 — a `[:flush-presence]` against a Promise-backed
              ;; host is the ONE step whose outcome is not known by the time
              ;; the executor returns. The executor calls this back with the
              ;; SETTLED result a microtask later, so the stepper records the
              ;; same verdict the auto-run loop would. Every other step calls
              ;; back synchronously, BEFORE the slot below exists — which
              ;; `recorded` (still `::none`) is what declines: the synchronous
              ;; return value already recorded the final result in that case,
              ;; and there is nothing to amend.
              ;;
              ;; STALE-SETTLEMENT FENCE (rf2-6pfpt). The gap between parking
              ;; and settling is one a user can reset the session across
              ;; (`begin-stepper!` / `stepper-rewind!` / `stepper-step-back!` /
              ;; `end-stepper!` / `clear-all-play-state!`). A BOUNDS guard —
              ;; all this used to carry — is a size test, not an identity
              ;; test: it correctly declines while a reset has left `:results`
              ;; shorter than `idx`, then silently permits the clobber once a
              ;; new cursor has grown back past `idx`, landing a stale
              ;; amendment on a different session's step.
              ;;
              ;; The claim a settling step actually needs is narrower than a
              ;; session: amend the record I MYSELF recorded, not whatever now
              ;; occupies my index. That is the provisional record's own object
              ;; identity, which is why this is `identical?` and not `=` — two
              ;; runs of the same step are legitimately EQUAL and must still be
              ;; told apart. It also needs no session counter and no bump at
              ;; those five mutation sites: it is automatically correct at
              ;; every cursor change, including ones not yet written. A
              ;; generation would additionally be WRONG at
              ;; `stepper-step-back!`, which pops only the last step — an
              ;; amendment still owed for an EARLIER index remains valid, and a
              ;; session-level bump would refuse it, quietly reinstating the
              ;; rf2-iz0t8 hazard of a clean flush shown over a failed one.
              recorded (atom ::none)
              settle! (fn [settled]
                        (swap! stepper-state update-in [frame-id :results]
                               (fn [rs]
                                 (if (and rs
                                          (< idx (count rs))
                                          (identical? (nth rs idx) @recorded))
                                   (assoc rs idx settled)
                                   rs))))
              result  (cond
                        run-fn (run-fn frame-id idx step settle!)

                        ;; Fallback: executor unavailable. Drive dispatch
                        ;; steps directly via `dispatch-one!`; record nothing
                        ;; for the other step types but still advance the cursor.
                        (#{:dispatch :dispatch-sync} (rf.story.play.runner/step-type step))
                        (do (dispatch-one! frame-id (rf.story.play.runner/step-event step))
                            (rf.story.play.runner/step-skip idx step))

                        :else (rf.story.play.runner/step-skip idx step))]
          ;; Publish the claim BEFORE the record is visible in `:results`, so
          ;; there is no window in which a settlement could see its own record
          ;; at `idx` without recognising it. Both are synchronous and
          ;; adjacent; a settlement that already fired (every step but a
          ;; pending presence flush) saw `::none` and correctly declined.
          (reset! recorded result)
          (swap! stepper-state update frame-id
                 (fn [s] (-> s
                             (update :remaining subvec 1)
                             (update :ran conj step)
                             (update :results (fnil conj []) result))))))
      step)))

(defn stepper-step-back!
  "Pop the most-recently-run step back into `:remaining` and drop its
  recorded result, so a subsequent `step-once!` re-runs it cleanly. The
  UI's step-back also restores the prior epoch (db state); this keeps the
  substrate's remaining/ran/results cursor consistent with that restore.
  No-op when no step has run."
  [frame-id]
  (when rf.story.config/enabled?
    ;; Guard against a missing entry: `update` on a missing key
    ;; would associate the fn's nil/`s` return (a `{frame-id nil}` entry),
    ;; which flips `play-stepper-active?` (a `contains?` check) to true for a
    ;; frame whose stepper was never begun. Only touch an existing,
    ;; non-nil slot.
    (when (some? (get @stepper-state frame-id))
      (swap! stepper-state update frame-id
             (fn [s]
               (if (seq (:ran s))
                 (let [last-step (peek (:ran s))]
                   (-> s
                       (update :ran pop)
                       (update :results (fn [r] (if (seq r) (pop r) r)))
                       (update :remaining (fn [rem] (into [last-step] rem)))))
                 s)))))
  nil)

(defn stepper-rewind!
  "Reset the substrate's run cursor to the start: every step back into
  `:remaining`, `:ran` + `:results` emptied. The UI's rewind also
  restores the pre-play epoch + clears the assertion accumulator."
  [frame-id]
  (when rf.story.config/enabled?
    ;; Guard against a missing entry: an `update` whose fn returns nil for
    ;; a missing key would associate that nil (a `{frame-id nil}` entry),
    ;; making `play-stepper-active?` report true for a frame whose stepper
    ;; was never begun. Only rewind an existing, non-nil slot.
    (when (some? (get @stepper-state frame-id))
      (swap! stepper-state update frame-id
             (fn [s]
               (when s
                 (let [full (into (vec (:ran s)) (:remaining s))]
                   (assoc s :remaining full :ran [] :results [])))))))
  nil)

(defn end-stepper!
  "Tear down the play stepper for `frame-id`. The UI calls this when
  the stepper widget closes."
  [frame-id]
  (when rf.story.config/enabled?
    (remove-trace-listener! frame-id)
    (swap! stepper-state dissoc frame-id))
  nil)

(defn drop-pending-exceptions!
  "Per-frame teardown for the pending-exceptions accumulator. Wired
  from `frames/destroy!` via the late-bound assertion-drop hook."
  [frame-id]
  (swap! pending-exceptions dissoc frame-id)
  nil)

(defn clear-all-play-state!
  "Hard, GLOBAL reset of every per-process play atom this module owns —
  `pending-exceptions` and `stepper-state` — plus the per-frame trace
  listeners those sessions install. Used by the canonical test-reset path
  (`re-frame.story/clear-all!` + `re-frame.story.test-support/story-reset!`).

  Per-frame teardown (`frames/destroy!` → `drop-pending-exceptions!` +
  `end-stepper!`) evicts these atoms one frame at a time, but a registry
  reset that does NOT tear down frames (e.g. `with-clean-registry`) would
  otherwise leave both atoms populated — a stale `stepper-state` entry makes
  `play-stepper-active?` report a session a later test never began, and a
  stale `pending-exceptions` entry could drain into a fresh frame's
  assertions. This is the remaining un-reset per-process play state
  alongside the config atoms and the runner-events run atoms.

  Also unregisters every per-frame trace listener `begin-stepper!` /
  `install-trace-listener!` registered (keyed by frame-id across both
  atoms), so a stale listener cannot capture into the just-cleared atom on
  the next dispatch. Idempotent."
  []
  (when rf.story.config/enabled?
    (let [frame-ids (into (set (keys @pending-exceptions))
                          (keys @stepper-state))]
      (doseq [frame-id frame-ids]
        (remove-trace-listener! frame-id)))
    (reset! pending-exceptions {})
    (reset! stepper-state {}))
  nil)
