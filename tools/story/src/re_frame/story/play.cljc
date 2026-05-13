(ns re-frame.story.play
  "Phase 4 — `:play` sequence execution. Per IMPL-SPEC §5.4 phase 4 +
  spec/007 §Play functions + §Assertion vocabulary.

  ## What this module does

  The variant body's `:play` slot is a vector of event vectors:

      :play [[:auth/email-changed \"alice@example.com\"]
             [:auth/password-changed \"hunter2\"]
             [:auth/login-pressed]
             [:rf.assert/path-equals [:auth :status] :authenticated]
             [:rf.assert/path-equals [:nav :route] :dashboard]]

  Each event is `dispatch-sync`'d into the variant's frame in declared
  order, draining run-to-completion between each. `:rf.assert/*` events
  ride the same dispatch path — re-frame's interceptor chain runs the
  registered assertion handler (see `re-frame.story.assertions`), which
  appends a record into `[:rf.story/assertions]` on the variant frame's
  app-db. Per IMPL-SPEC §2.3 the assertion never throws; the play
  sequence runs to completion regardless of which assertions fail.

  ## Trace-bus accumulators

  Per the IMPL-SPEC §5.1 assertion list shape, three assertions need
  per-frame trace-bus knowledge:

  - `:rf.assert/no-warnings`   — was any `:warning` trace event emitted?
  - `:rf.assert/effect-emitted` — was a given fx-id emitted?
  - `:rf.assert/dispatched?`   — was a given event dispatched?

  We register a per-frame trace listener at the start of the play
  sequence that:

  - records `:warning` and `:error` `:op-type` events into the
    assertion module's `warnings-accumulator`,
  - records every `:event/dispatched` event vector into
    `dispatched-events-accumulator`,
  - records every fx call (operations under `:event/do-fx` /
    `:fx` op-type) into `emitted-fx-accumulator`.

  The accumulators clear at play-start and live until frame teardown
  (per `assertions/drop-trace-accumulators!`).

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
  - `install-helpers!` — registers the play-runner's internal
                         trace-listener helpers; idempotent.
  - `play-stepper-active?` / `step-once!` — UI hooks (Stage 4's
                                            play-stepper slot)."
  (:require [re-frame.core             :as rf]
            [re-frame.trace            :as trace]
            [re-frame.interop          :as interop]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.async      :as async]
            [re-frame.story.config     :as config]
            [re-frame.story.frames     :as frames]
            [re-frame.story.registrar  :as registrar]))

;; ---------------------------------------------------------------------------
;; Per-frame trace listener
;;
;; One listener per variant frame. Filters trace events by `:frame`
;; (per spec/009 §Dispatch correlation) and routes them into the
;; assertion module's per-frame accumulators. Idempotent.
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
  "Build the trace-event listener for `frame-id`. Routes each event
  into the right accumulator. Skips events that don't target the
  frame so cross-frame traffic (e.g. the default frame's lifecycle
  events) stays out of the variant's accumulators.

  Listener executes INSIDE the running dispatch drain, so it never
  re-enters dispatch-sync — it stores side-effects in atoms and lets
  the play-runner drain them between events.

  Per Spec 009 §Privacy + rf2-bclgj: events whose `:sensitive?` flag
  is true are dropped before any accumulator updates when the global
  `:trace/show-sensitive?` flag is false (the default). The
  suppressed-events counter bumps for the targeted frame so the UI
  can surface a `[● REDACTED]` hint."
  [frame-id]
  (fn [ev]
    (when (= frame-id (frame-of ev))
      (cond
        (config/suppress-sensitive? ev)
        (config/note-suppressed! frame-id)

        :else
        (case (:op-type ev)
          :warning      (assertions/record-warning! frame-id ev)
          :error        (do (assertions/record-warning! frame-id ev)
                            (when (= :rf.error/handler-exception (:operation ev))
                              (record-pending-exception! frame-id ev)))
          :event        (when (= :event/dispatched (:operation ev))
                          (let [event-vec (get-in ev [:tags :event])]
                            (when (and event-vec
                                       (not (assertions/assertion-event? event-vec)))
                              (assertions/record-dispatched! frame-id event-vec))))
          :event/do-fx  (let [fx-map (get-in ev [:tags :fx])]
                          (when (map? fx-map)
                            (doseq [fx-id (keys fx-map)]
                              (assertions/record-emitted-fx! frame-id fx-id))))
          :fx           (let [fx-id (get-in ev [:tags :fx-id])]
                          (when fx-id
                            (assertions/record-emitted-fx! frame-id fx-id)))
          nil)))))

(defn- drain-pending-exceptions!
  "Append any pending exception trace events from `frame-id` as
  assertion records on the variant's assertions slot. Called by the
  play-runner after each dispatch-sync returns (i.e. after the drain
  has settled). Clears the pending slot on exit."
  [frame-id]
  (let [evs (get @pending-exceptions frame-id [])]
    (when (seq evs)
      (doseq [ev evs]
        (let [event-vec (get-in ev [:tags :event])
              msg       (get-in ev [:tags :exception-message])
              exc       (get-in ev [:tags :exception])]
          (assertions/record!
            frame-id
            {:assertion :rf.error/exception
             :phase     :phase-4-play
             :event     event-vec
             :error     {:message (or msg
                                      #?(:clj (when exc (.getMessage ^Throwable exc))
                                         :cljs (when exc (str exc))))}
             :passed?   false})))
      (swap! pending-exceptions assoc frame-id []))))

(defn install-trace-listener!
  "Register a per-frame trace listener that feeds the assertion module's
  accumulators. Idempotent — re-registering replaces. Returns the
  listener id."
  [frame-id]
  (when config/enabled?
    (let [id (listener-id frame-id)]
      (trace/register-trace-cb! id (listener-for-frame frame-id))
      id)))

(defn remove-trace-listener!
  "Tear down the per-frame trace listener for `frame-id`. Idempotent."
  [frame-id]
  (when config/enabled?
    (trace/remove-trace-cb! (listener-id frame-id))
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
      (let [record {:assertion :rf.error/exception
                    :phase     :phase-4-play
                    :event     event
                    :error     {:message #?(:clj  (.getMessage ^Throwable e)
                                            :cljs (str e))}
                    :passed?   false}]
        (assertions/record! frame-id record))))
  ;; After the drain settles, walk any captured handler-exception
  ;; trace events into assertion records. Safe to dispatch-sync now —
  ;; the drain has ended.
  (drain-pending-exceptions! frame-id))

(defn- read-assertions-after
  "Return the per-frame assertions vector, post-play."
  [frame-id]
  (assertions/read-assertions frame-id))

(defn variant-play-events
  "Resolve `:play` for `variant-id` from the registered body. Defaults
  to an empty vector when the body has no `:play`."
  [variant-id]
  (or (:play (registrar/handler-meta :variant variant-id)) []))

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
           (assertions/reset-trace-accumulators! variant-id)
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
             ;; the caller sees the accumulator.
             (assertions/record! variant-id
                                 {:assertion :rf.error/exception
                                  :phase     :phase-4-setup
                                  :error     {:message #?(:clj (.getMessage ^Throwable e)
                                                          :cljs (str e))}
                                  :passed?   false})
             (resolve (read-assertions-after variant-id)))))))))

;; ---------------------------------------------------------------------------
;; UI play-stepper hook (Stage 4 placeholder, finalised here)
;; ---------------------------------------------------------------------------

(defonce
  ^{:doc "Per-frame play-stepper state. `{frame-id → {:remaining vec,
         :ran vec}}`. The UI shell consumes this to render the
         stepper widget. Used only when the play sequence is being
         driven step-by-step rather than via `execute-play!`."}
  stepper-state
  (atom {}))

(defn play-stepper-active?
  [frame-id]
  (contains? @stepper-state frame-id))

(defn begin-stepper!
  "Initialise a step-by-step play run for `frame-id`. The UI's
  play-stepper widget calls `step-once!` to advance one event."
  [frame-id]
  (when config/enabled?
    (assertions/reset-trace-accumulators! frame-id)
    (install-trace-listener! frame-id)
    (swap! stepper-state assoc frame-id
           {:remaining (vec (variant-play-events frame-id))
            :ran       []})
    nil))

(defn step-once!
  "Advance the play stepper for `frame-id` by one event. Returns the
  event that was dispatched, or nil when no events remain."
  [frame-id]
  (when config/enabled?
    (let [{:keys [remaining]} (get @stepper-state frame-id)
          ev (first remaining)]
      (when ev
        (dispatch-one! frame-id ev)
        (swap! stepper-state update frame-id
               (fn [s] (-> s
                           (update :remaining subvec 1)
                           (update :ran conj ev)))))
      ev)))

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
