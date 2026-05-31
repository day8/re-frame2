(ns re-frame.story.play
  "Phase 4 — play-script trace listener + stepper helpers.

  rf2-0wrud (2026-05-20): the variant body's legacy `:play`
  event-vector slot was REMOVED. `:play-script` is the canonical AND
  ONLY phase-4 surface. This module retains the per-frame trace
  listener and the step-by-step play-stepper helpers. The rich-DSL
  execution itself lives in `re-frame.story.play.runner-events`.

  rf2-q651r: `:rf.assert/dispatched?` / `:rf.assert/effect-emitted` /
  `:rf.assert/no-warnings` PROJECT their fact from the epoch tape (the
  SSOT, `re-frame.story.assertions/dispatched-events` / `warnings` /
  `emitted-fx`), the same source the run-result evidence slots read.

  rf2-luzky: the per-frame listener's `trace-accumulators` dev-mirror feed
  (the `record-warning!` / `record-emitted-fx!` / `record-dispatched!`
  calls) is REMOVED along with the side-table itself — those facts are
  answerable from the tape + stub-call log, so the mirror carried nothing
  the SSOT does not. The listener is retained PURELY as the egress seam:
  the load-bearing PRIVACY-suppression gate (Spec 009 §Privacy —
  default-dropping `:sensitive?` events + the `config/note-suppressed!`
  redaction counter) and the synchronous handler-exception capture
  (`pending-exceptions`). It no longer touches any assertions accumulator.

  ## What this module does

  A `:play-script` body carries tagged steps. Authors wrap event
  vectors as `[:dispatch-sync <event-vec>]` (or `:dispatch` for async).
  The `:rf.assert/*` events ride the same dispatch path — re-frame's
  interceptor chain runs the registered assertion handler (see
  `re-frame.story.assertions`), which appends a record into
  `[:rf.story/assertions]` on the variant frame's app-db. Per IMPL-SPEC
  §2.3 the assertion never throws; the play sequence runs to completion
  regardless of which assertions fail.

  ## Privacy egress seam (the per-frame trace listener)

  We register a per-frame trace listener at the start of the play
  sequence. Its two — and only — LOAD-BEARING jobs:

  - PRIVACY (Spec 009 §Privacy): default-drop `:sensitive? true` events
    BEFORE anything else and bump the UI redaction counter
    (`config/note-suppressed!`);
  - synchronous handler-exception capture into `pending-exceptions`
    (drained into the assertions list between dispatches).

  rf2-luzky removed this listener's former `trace-accumulators` dev-mirror
  feed: `:rf.assert/no-warnings` / `:rf.assert/effect-emitted` /
  `:rf.assert/dispatched?` project from the epoch tape + stub-call log (the
  SSOT), so the parallel mirror was redundant.

  ## Async surface

  Stage 5's play execution is synchronous — `dispatch-sync` drains
  run-to-completion (per spec/002), so a sequence of N events
  completes in N drains. The play-runner returns a resolved promise
  immediately on completion. Future async-play surfaces (e.g.
  Playwright-style waiting on a UI selector) are Stage 6 hooks.

  ## Public API

  - `execute-play!`  — runs a play sequence against a variant frame
                       and returns a resolved promise of the
                       accumulated assertions vector.
  - `install-trace-listener!` / `remove-trace-listener!` — per-frame
                         trace-listener install + teardown; idempotent.
  - `play-stepper-active?` / `step-once!` — UI hooks (Stage 4's
                                            play-stepper slot)."
  (:require [re-frame.core             :as rf]
            ;; rf2-qwm0a — the listener surface
            ;; (`register-listener!` / `unregister-listener!`) lives in
            ;; `re-frame.trace.tooling` (production-DCE split).
            [re-frame.trace.tooling    :as trace-tooling]
            [re-frame.interop          :as interop]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.async      :as async]
            [re-frame.story.config     :as config]
            [re-frame.story.error      :as story-error]
            [re-frame.story.frames     :as frames]
            [re-frame.story.late-bind  :as late-bind]
            [re-frame.story.play.runner :as runner]
            [re-frame.story.registrar  :as registrar]))

;; ---------------------------------------------------------------------------
;; Per-frame trace listener — the Spec 009 §Privacy egress seam
;;
;; One listener per variant frame. Filters trace events by `:frame`
;; (per spec/009 §Dispatch correlation) and (a) drops `:sensitive?`
;; events + bumps the redaction counter, (b) captures synchronous
;; handler-exceptions into `pending-exceptions`. Idempotent. It does NOT
;; feed any assertions accumulator (rf2-luzky — the dev-mirror is gone;
;; assertions project from the epoch tape + stub-call log).
;; ---------------------------------------------------------------------------

(defn- listener-id [frame-id]
  (keyword "re-frame.story.play"
           (str "trace-" (when frame-id (str frame-id)))))

(defn- frame-of [ev]
  ;; Per Spec 009 §Per-frame routing: the canonical tag key is :frame
  ;; (rf2-shaa1 dropped the :frame-id alias from impl emit sites).
  (get-in ev [:tags :frame]))

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

  Two LOAD-BEARING jobs (rf2-luzky — the `trace-accumulators` dev mirror
  this listener once also fed is removed; warnings / fx / dispatched are
  answered from the epoch tape + stub-call log, the SSOT):

  1. PRIVACY (Spec 009 §Privacy + rf2-bclgj): events whose `:sensitive?`
     flag is true are DROPPED here when the global
     `:rf.privacy/show-sensitive?` flag is false (the default). The
     suppressed-events counter bumps (`config/note-suppressed!`) for the
     targeted frame so the UI can surface a `[● REDACTED]` hint. This is
     the egress gate — it runs BEFORE the listener does anything else, so
     a sensitive event never reaches the exception capture below.

  2. Synchronous handler-exception capture: a non-suppressed
     `:rf.error/handler-exception` event is stashed into
     `pending-exceptions`, which the play-runner drains AFTER each
     dispatch-sync settles (so it can record an assertion via
     dispatch-sync without re-entering the in-flight drain)."
  [frame-id]
  (fn [ev]
    (when (= frame-id (frame-of ev))
      (cond
        (config/suppress-sensitive? ev)
        (config/note-suppressed! frame-id)

        (and (= :error (:op-type ev))
             (= :rf.error/handler-exception (:operation ev)))
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

  Public (rf2-z2dq8) so the new rich-DSL runner (`runner-events`) and
  the runtime's loader/events drivers can drain between dispatches. The
  legacy `:rf.story/assertions` contract is load-bearing — the test-mode
  pane, the chrome-level widget, and the Xray assertions panel all
  read off this slot."
  [frame-id phase]
  (let [evs (get @pending-exceptions frame-id [])]
    (when (seq evs)
      (doseq [ev evs]
        (let [event-vec (get-in ev [:tags :event])
              msg       (get-in ev [:tags :exception-message])
              exc       (get-in ev [:tags :exception])]
          ;; rf2-9kpsq — the trace event may carry a pre-extracted
          ;; `:exception-message` (and a possibly-nil `:exception`); thread
          ;; it as the explicit `:message` override on the shared
          ;; projection so the message survives even without the throwable.
          (assertions/record!
            frame-id
            (story-error/exception-record frame-id phase event-vec exc
                                          {:message msg}))))
      (swap! pending-exceptions assoc frame-id []))))

(defn install-trace-listener!
  "Register the per-frame privacy egress seam (the trace listener that
  default-drops `:sensitive?` events + captures handler-exceptions).
  Idempotent — re-registering replaces. Returns the listener id."
  [frame-id]
  (when config/enabled?
    (let [id (listener-id frame-id)]
      (trace-tooling/register-listener! id (listener-for-frame frame-id))
      id)))

(defn remove-trace-listener!
  "Tear down the per-frame trace listener for `frame-id`. Idempotent."
  [frame-id]
  (when config/enabled?
    (trace-tooling/unregister-listener! (listener-id frame-id))
    nil))

;; ---------------------------------------------------------------------------
;; Play sequence execution
;; ---------------------------------------------------------------------------

(defn- dispatch-one!
  "Dispatch a single event in the play sequence. Wraps `dispatch-sync`
  with the exception-record path so phase-4 errors land in the
  assertion list rather than aborting the sequence (IMPL-SPEC §2.3 +
  §5.5).

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
      (assertions/record!
        frame-id
        (story-error/exception-record frame-id :phase-4-play event e))))
  ;; After the drain settles, walk any captured handler-exception
  ;; trace events into assertion records. Safe to dispatch-sync now —
  ;; the drain has ended.
  (drain-pending-exceptions! frame-id :phase-4-play))

(defn- read-assertions-after
  "Return the per-frame assertions vector, post-play."
  [frame-id]
  (assertions/read-assertions frame-id))

(defn variant-play-events
  "Resolve a flat event-vector list for `variant-id`'s phase-4 play.

  rf2-0wrud (2026-05-20): the legacy `:play` event-vector slot has been
  removed. This fn now derives a flat event-vector list from the
  variant's `:play-script` body by extracting events from the
  `:dispatch` / `:dispatch-sync` steps. Other step types (`:wait`,
  `:click`, `:type`, `:assert-db`, `:assert-dom`) have no event-vector
  representation and are skipped here — the rich-DSL runner
  (`re-frame.story.play.runner-events`) is the canonical executor.

  This shape stays around for the play-stepper UI which advances ONE
  event at a time."
  [variant-id]
  (let [body   (registrar/handler-meta :variant variant-id)
        spec   (runner/parse-spec (:play-script body))
        script (:script spec)]
    (->> (or script [])
         (keep (fn [step]
                 (when (and (vector? step)
                            (#{:dispatch :dispatch-sync} (first step))
                            (vector? (second step)))
                   (second step))))
         vec)))

(defn execute-play!
  "Run the play sequence against `variant-id`'s frame. Drives the
  trace-listener, dispatches each event in order, and returns a
  resolved promise of the assertions vector.

  Per IMPL-SPEC §5.4 phase 4 + §2.3 the sequence runs to completion
  regardless of which assertions fail. `:rf.error/exception` records
  cover phase-4 throws.

  `opts` accepts `:install-listener?` (default true) — when false the
  caller has already installed the listener (e.g. the UI shell). The
  listener is idempotent so the default-true path is also safe."
  ([variant-id]
   (execute-play! variant-id (variant-play-events variant-id) nil))
  ([variant-id play-events]
   (execute-play! variant-id play-events nil))
  ([variant-id play-events {:keys [install-listener?]
                            :or   {install-listener? true}}]
   (if-not config/enabled?
     (async/resolved [])
     (async/promise
       (fn [resolve]
         (try
           (swap! pending-exceptions assoc variant-id [])
           (when install-listener?
             (install-trace-listener! variant-id))
           (try
             (doseq [ev play-events]
               (dispatch-one! variant-id ev))
             (finally
               (when install-listener?
                 ;; Leave the listener in place if the caller declared
                 ;; ownership; otherwise tear down so destroyed variants
                 ;; don't accumulate dangling cbs.
                 (remove-trace-listener! variant-id))))
           (resolve (read-assertions-after variant-id))
           (catch #?(:clj Throwable :cljs :default) e
             ;; A failure inside execute-play itself (not the dispatched
             ;; events) becomes a phase-4-setup record. The play has not
             ;; necessarily completed but we still resolve the promise so
             ;; the caller sees the accumulator. rf2-9kpsq — routed through
             ;; the shared projection (was a drifted message-only copy that
             ;; dropped :stack / :data); :event is nil (the failure is in
             ;; the play harness, not a dispatched event).
             (assertions/record!
               variant-id
               (story-error/exception-record variant-id :phase-4-setup nil e))
             (resolve (read-assertions-after variant-id)))))))))

;; ---------------------------------------------------------------------------
;; UI play-stepper hook (Stage 4 placeholder, finalised here)
;;
;; rf2-ee38b.3 re-base: the stepper now walks the FULL coerced
;; `:play-script` (every step type — `:dispatch` / `:dispatch-sync` /
;; `:wait` / `:click` / `:type` / `:assert-db` / `:assert-dom`), driving
;; each step through the SAME rich-DSL executor the canvas auto-run path
;; uses (`runner-events/run-step!`, fetched via the `:run-play-step`
;; late-bind hook to avoid the play ↔ runner-events cycle). Previously it
;; dispatched only the `:dispatch`/`:dispatch-sync` events from
;; `variant-play-events` and silently dropped the rest — so the cursor /
;; total were wrong and assert outcomes never surfaced during a stepped
;; run. The slot now holds STEPS, not bare event vectors.
;; ---------------------------------------------------------------------------

(defonce
  ^{:doc "Per-frame play-stepper state. `{frame-id → {:remaining vec,
         :ran vec, :results vec}}` where `:remaining` / `:ran` carry
         coerced `:play-script` STEPS (rf2-ee38b.3 — was bare event
         vectors) and `:results` carries the per-step result records the
         rich-DSL executor returned. The UI shell consumes this to
         render the stepper widget. Used only when the play sequence is
         being driven step-by-step rather than via `execute-play!`."}
  stepper-state
  (atom {}))

(defn variant-play-steps
  "Resolve the FULL coerced `:play-script` step vector for `variant-id`'s
  default play (rf2-ee38b.3). Unlike `variant-play-events` (which drops
  every non-dispatch step) this returns EVERY step the rich-DSL runner
  recognises, in order, so the step-debugger walks the same sequence the
  auto-run path executes.

  Pure data → data; works on JVM + CLJS.

  rf2-5x1wt.19 — the script is FOLDED (`assertions/fold-script`) so the
  stepper walks the SAME canonical `[:assert …]` checkpoints the auto-run
  path drives: a shipping `:assert-db` / `:assert-dom` step is rewritten to
  the one assertion atom before the stepper executes it."
  [variant-id]
  (let [body (registrar/handler-meta :variant variant-id)
        spec (runner/parse-spec (:play-script body))]
    (assertions/fold-script (vec (:script spec)))))

(defn play-stepper-active?
  [frame-id]
  (contains? @stepper-state frame-id))

(defn begin-stepper!
  "Initialise a step-by-step play run for `frame-id`. The UI's
  play-stepper widget calls `step-once!` to advance one step.

  rf2-ee38b.3: seeds the FULL coerced script (all step types), not just
  the dispatch-bearing events."
  [frame-id]
  (when config/enabled?
    (swap! pending-exceptions assoc frame-id [])
    (install-trace-listener! frame-id)
    (swap! stepper-state assoc frame-id
           {:remaining (variant-play-steps frame-id)
            :ran       []
            :results   []})
    nil))

(defn step-once!
  "Advance the play stepper for `frame-id` by one step. Executes the
  step through the rich-DSL executor (`runner-events/run-step!` via the
  `:run-play-step` late-bind hook) so EVERY step type runs in the
  debugger exactly as it does on the live canvas. Returns the step that
  ran, or nil when no steps remain.

  rf2-ee38b.3: previously this dispatched only `:dispatch`/`:dispatch-
  sync` events and dropped the rest. If the executor hook is absent (a
  Stage-3-only build where `runner-events` never loaded) it falls back
  to the legacy `dispatch-one!` for dispatch steps and a no-op record
  for the rest, so the cursor stays honest."
  [frame-id]
  (when config/enabled?
    (let [{:keys [remaining]} (get @stepper-state frame-id)
          step (first remaining)]
      (when step
        (let [idx     (count (:ran (get @stepper-state frame-id)))
              run-fn  (late-bind/get-fn :run-play-step)
              result  (cond
                        run-fn (run-fn frame-id idx step)

                        ;; Fallback: executor unavailable. Drive dispatch
                        ;; steps the legacy way; record nothing for the
                        ;; other step types but still advance the cursor.
                        (#{:dispatch :dispatch-sync} (runner/step-type step))
                        (do (dispatch-one! frame-id (runner/step-event step))
                            (runner/step-skip idx step))

                        :else (runner/step-skip idx step))]
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
  No-op when no step has run. rf2-ee38b.3."
  [frame-id]
  (when config/enabled?
    (swap! stepper-state update frame-id
           (fn [s]
             (if (seq (:ran s))
               (let [last-step (peek (:ran s))]
                 (-> s
                     (update :ran pop)
                     (update :results (fn [r] (if (seq r) (pop r) r)))
                     (update :remaining (fn [rem] (into [last-step] rem)))))
               s))))
  nil)

(defn stepper-rewind!
  "Reset the substrate's run cursor to the start: every step back into
  `:remaining`, `:ran` + `:results` emptied. The UI's rewind also
  restores the pre-play epoch + clears the assertion accumulator.
  rf2-ee38b.3."
  [frame-id]
  (when config/enabled?
    (swap! stepper-state update frame-id
           (fn [s]
             (when s
               (let [full (into (vec (:ran s)) (:remaining s))]
                 (assoc s :remaining full :ran [] :results []))))))
  nil)

(defn end-stepper!
  "Tear down the play stepper for `frame-id`. The UI calls this when
  the stepper widget closes."
  [frame-id]
  (when config/enabled?
    (remove-trace-listener! frame-id)
    (swap! stepper-state dissoc frame-id))
  nil)

(defn drop-pending-exceptions!
  "Per-frame teardown for the pending-exceptions accumulator. Wired
  from `frames/destroy!` via the late-bound assertion-drop hook."
  [frame-id]
  (swap! pending-exceptions dissoc frame-id)
  nil)
