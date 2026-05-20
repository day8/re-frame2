(ns re-frame.story.runtime
  "Story runtime orchestration. Per IMPL-SPEC §3.2 + §5.

  Stage 3 (rf2-von3) lands the runtime that consumes Stage 2's
  registered artefacts and resolves them into a runnable variant:

  - `run-variant`     — allocate frame; run four-phase lifecycle;
                        return a promise/future of the result map.
  - `reset-variant`   — tear down and re-run.
  - `watch-variant`   — subscribe to lifecycle transitions.
  - `snapshot-identity` — re-export of `re-frame.story.identity/snapshot-identity`.

  ## The result map

  Per IMPL-SPEC §3.2 the resolved value of `run-variant` is:

      {:frame           <variant-id>
       :app-db          {...}
       :assertions      [{:assertion ... :passed? true ...} ...]
       :rendered-hiccup [...]               ; when :render? true
       :elapsed-ms      <number>
       :snapshot        {:variant-id ... :content-hash ...}
       :decorators      {:hiccup [...] :frame-setup [...]
                          :fx-override [...] :errors [...]}
       :effective-args  {...}
       :lifecycle       :ready | :error}

  Stage 5 (rf2-h8et) lands the play-sequence runtime that populates
  `:assertions` with full assertion semantics. Stage 3 leaves the slot
  present and empty.

  ## Elision

  Every entry point checks `re-frame.story.config/enabled?`. When
  false (production CLJS builds), the fns return an empty result map
  immediately — the inner body, the registrar lookups, and the frame
  allocation all elide. Per IMPL-SPEC §6.3 this is a *feature*:
  production code that accidentally calls `run-variant` does not throw
  — it returns empty."
  (:require [re-frame.core            :as rf]
            [re-frame.story.args      :as args]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.async     :as async]
            [re-frame.story.config    :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.frames    :as frames]
            [re-frame.story.identity  :as ident]
            [re-frame.story.loaders   :as loaders]
            [re-frame.story.play      :as play]
            [re-frame.story.play.runner-events :as runner-events]
            [re-frame.story.registrar :as registrar]
            [re-frame.interop         :as interop]
            [re-frame.trace           :as trace]
            ;; rf2-qwm0a — listener API lives in
            ;; `re-frame.trace.tooling` (production-DCE split). The
            ;; hot-path emit fast-path (`trace/emit!`) stays in
            ;; `re-frame.trace`.
            [re-frame.trace.tooling   :as trace-tooling]))

;; ---- empty / disabled result ---------------------------------------------

(defn- empty-result
  "Per IMPL-SPEC §6.3: production callers see an empty result map
  rather than an exception. The shape matches a successful run with
  no registrations to act on."
  [variant-id]
  {:frame           variant-id
   :app-db          {}
   :assertions      []
   :rendered-hiccup nil
   :elapsed-ms      0
   :snapshot        nil
   :decorators      {:hiccup [] :frame-setup [] :fx-override [] :errors []}
   :effective-args  {}
   :lifecycle       :ready})

;; Forward declarations so phase fns (defined before record helpers) can
;; project failures per IMPL-SPEC §5.5 without reordering the file.
(declare record-error! record-loader-incomplete!)

;; ---- phase exception capture --------------------------------------------
;;
;; re-frame's interceptor chain catches handler exceptions internally and
;; emits a `:rf.error/handler-exception` trace event rather than re-
;; throwing. Stage 3's phase runners need to convert those trace events
;; into assertion records (per IMPL-SPEC §5.5).
;;
;; The capture pattern: register a trace listener around each phase
;; that collects matching errors into an atom. After the phase, walk
;; the atom and record each into the variant frame's `:rf.story/
;; assertions` accumulator.

(defonce ^:private capture-counter (atom 0))

(defn- with-trace-listener
  "Register `listener` against a fresh capture id, run `body-fn` (a
  0-arg thunk), then remove the listener in a `finally`. Returns
  `body-fn`'s return value. Factors out the register/try/finally/remove
  shape shared by every `capture-phase-errors`-style helper."
  [listener body-fn]
  (let [cb-id (keyword "re-frame.story.runtime"
                       (str "capture-" (swap! capture-counter inc)))]
    (trace-tooling/register-listener! cb-id listener)
    (try (body-fn)
      (finally (trace-tooling/unregister-listener! cb-id)))))

(defn- capture-phase-errors
  "Run `body-fn` (a 0-arg thunk) with a registered trace listener that
  collects `:rf.error/handler-exception` events targeting `variant-id`'s
  frame. After the body returns, walks the captured errors and records
  each as a phase-tagged assertion via `record-error!`. Returns `body-fn`'s
  return value.

  Per Spec 009 §Privacy + rf2-bclgj: handler-exception trace events
  whose `:sensitive?` flag is true are dropped from the capture set
  when the global `:rf.privacy/show-sensitive?` flag is false (the
  default). A counter bump is recorded so the UI's redaction hint
  can surface 'N sensitive events suppressed'."
  [variant-id phase body-fn]
  (let [collected (atom [])
        listener  (fn [ev]
                    (cond
                      (config/suppress-sensitive? ev)
                      (config/note-suppressed! (get-in ev [:tags :frame]))

                      (and (= :rf.error/handler-exception (:operation ev))
                           (= variant-id (get-in ev [:tags :frame])))
                      (swap! collected conj ev)))]
    (with-trace-listener
      listener
      (fn []
        (let [result (body-fn)]
          (doseq [ev @collected]
            (record-error! variant-id phase
                           (get-in ev [:tags :event])
                           (get-in ev [:tags :exception])))
          result)))))

;; ---- phase-2 events execution --------------------------------------------

(defn- run-events!
  "Phase 2: dispatch every event in `:events` (story-level concat
  variant-level) into the variant's frame, draining between each. Per
  IMPL-SPEC §5.4 phase 2."
  [variant-id]
  (let [variant-body (frames/variant-body variant-id)
        story-id     (args/parent-story-id variant-id)
        story-body   (when story-id (registrar/handler-meta :story story-id))
        story-events (or (:events story-body) [])
        var-events   (or (:events variant-body) [])
        all-events   (concat story-events var-events)]
    (capture-phase-errors
      variant-id :phase-2-events
      (fn []
        (doseq [ev all-events]
          (try
            (rf/dispatch-sync ev {:frame variant-id})
            (catch #?(:clj Throwable :cljs :default) e
              ;; Synchronous throws (rare — re-frame's interceptor chain
              ;; usually catches and re-emits via trace) record here.
              (record-error! variant-id :phase-2-events ev e))
            (finally
              ;; rf2-z2dq8 — drain handler-exception trace events that
              ;; the router caught into the assertions list so phase-2
              ;; throws land where the test-mode UI looks for them.
              (play/drain-pending-exceptions! variant-id :phase-2-events))))))))

;; ---- phase-1 loaders execution -------------------------------------------

(defn- run-loaders!
  "Phase 1: dispatch every event in `:loaders` into the variant's
  frame, evaluating `:loaders-complete-when` after each. Per IMPL-SPEC
  §5.4 phase 1.

  In Stage 3 the simple synchronous path is the load-bearing one:
  `dispatch-sync` drains run-to-completion before returning, so the
  default predicate's 'no further events in flight' check passes
  trivially. Variants with long-lived fx (websocket / interval) supply
  `:loaders-complete-when` to override; Stage 3 routes those through
  the predicate evaluator (Stage 5 adds the full assertion runtime).

  rf2-043cm — events-only fast-path: when `frames/allocate!` drives
  the lifecycle straight to `:ready` (no loaders / no frame-setup /
  no `:loaders-complete-when`), this fn short-circuits with `true`
  rather than firing `start-loaders!`/`finish-loaders!` against a
  machine that's already terminal-for-mount. Both helpers would
  silently no-op (the `:ready` node only accepts `:errored`), but
  routing past them keeps the phase reads honest: `current-state`
  stays `:ready` end-to-end."
  [variant-id]
  (if (= :ready (loaders/current-state variant-id))
    ;; Events-only fast-path (rf2-043cm). Lifecycle already terminal-
    ;; for-mount; the loader cascade has nothing to do.
    true
    (let [variant-body (frames/variant-body variant-id)
          loader-events (or (:loaders variant-body) [])]
      (loaders/start-loaders! variant-id)
      (capture-phase-errors
        variant-id :phase-1-loaders
        (fn []
          (doseq [ev loader-events]
            (try
              (rf/dispatch-sync ev {:frame variant-id})
              (catch #?(:clj Throwable :cljs :default) e
                (record-error! variant-id :phase-1-loaders ev e))
              (finally
                ;; rf2-z2dq8 — drain handler-exception trace events the
                ;; router caught into the assertions list so phase-1
                ;; loader throws surface in the test-mode UI / Causa.
                (play/drain-pending-exceptions! variant-id :phase-1-loaders))))))
      ;; Evaluate :loaders-complete-when. In Stage 3 the predicate
      ;; resolves synchronously; Stage 6+ might add an async-retry shape.
      (let [complete? (loaders/evaluate-complete-when variant-id variant-body)]
        (if complete?
          (do
            (loaders/finish-loaders! variant-id)
            true)
          (do
            (record-loader-incomplete! variant-id variant-body)
            false))))))

;; ---- error recording -----------------------------------------------------

(defn- error-record
  "Build an error projection map per IMPL-SPEC §5.5."
  [variant-id phase event err]
  {:assertion :rf.error/exception
   :variant-id variant-id
   :phase     phase
   :event     event
   :error     {:message #?(:clj  (.getMessage ^Throwable err)
                           :cljs (str err))
               :stack   #?(:clj  (with-out-str (.printStackTrace ^Throwable err))
                            :cljs (.-stack err))
               :data    (when (instance? #?(:clj clojure.lang.ExceptionInfo
                                            :cljs ExceptionInfo) err)
                          (ex-data err))}
   :passed?   false})

(defn- loader-incomplete-record
  "Build the non-throwing projection used when a loader predicate is
  false. The runtime cannot advance into events/play while the loader
  contract says the variant is not ready; returning a failed assertion
  keeps the result actionable without requiring a browser timeout."
  [variant-id variant-body]
  {:assertion :rf.error/loader-incomplete
   :variant-id variant-id
   :phase     :phase-1-loaders
   :predicate (:loaders-complete-when variant-body)
   :reason    "loaders-complete-when did not report completion; events and play were skipped"
   :passed?   false})

(defn record-error!
  "Append an error record to the variant frame's `[:rf.story/assertions]`
  accumulator. Per IMPL-SPEC §5.5 errors continue the play sequence
  rather than aborting — the full picture is captured."
  [variant-id phase event err]
  (let [record (error-record variant-id phase event err)]
    (try
      (rf/dispatch-sync [::append-assertion record] {:frame variant-id})
      (catch #?(:clj Throwable :cljs :default) dispatch-err
        ;; The frame may already be torn down (run-variant tearing down
        ;; under error, or a hot-reload race destroying the frame mid-
        ;; capture). Emit a debug trace breadcrumb so the lossy path is
        ;; visible in tooling; never re-throw — the caller is already
        ;; in error-recording flow.
        (trace/emit!
          :debug ::append-assertion-failed
          {:frame      variant-id
           :phase      phase
           :event      event
           :error-msg  #?(:clj  (.getMessage ^Throwable dispatch-err)
                          :cljs (str dispatch-err))})))
    record))

(defn- record-loader-incomplete!
  [variant-id variant-body]
  (let [record (loader-incomplete-record variant-id variant-body)]
    (try
      (rf/dispatch-sync [::append-assertion record] {:frame variant-id})
      (catch #?(:clj Throwable :cljs :default) _ nil))
    record))

;; ---- helper event registrations ------------------------------------------

(defn install-helpers!
  "Register the runtime's internal helper events. Idempotent."
  []
  (when config/enabled?
    (rf/reg-event-db
      ::append-assertion
      (fn [db [_ record]]
        (update db :rf.story/assertions (fnil conj []) record)))))

;; ---- assertions read -----------------------------------------------------

(defn read-assertions
  "Return the assertions vector for `variant-id`'s frame, or `[]`."
  [variant-id]
  (or (:rf.story/assertions (rf/get-frame-db variant-id)) []))

;; ---- run-variant ---------------------------------------------------------
;;
;; `run-variant` is the engine's hot path. Per IMPL-SPEC §5.4 it drives the
;; four-phase lifecycle (phase-0 setup → phase-1 loaders → phase-2 events →
;; phase-4 play); phase-3 render is Stage 4's UI-shell concern. To keep the
;; orchestrator readable each phase lives in its own named fn — the audit
;; (rf2-dd5ze, RT1) flagged the inline 70-line let/try wall the orchestrator
;; used to be. The named-phase decomposition also gives tests a finer entry
;; surface: a primed ctx can be fed into a single phase fn in isolation.

(defn- record-result-map
  "Build the result map returned by `run-variant`. Pure data; gathers
  whatever the runtime accumulated against the variant's frame."
  [variant-id decorator-stack effective-args snapshot start-ms]
  (let [app-db (rf/get-frame-db variant-id)]
    {:frame           variant-id
     :app-db          (or app-db {})
     :assertions      (or (:rf.story/assertions app-db) [])
     :rendered-hiccup nil       ;; Stage 4 fills this in
     :elapsed-ms      (- (interop/now-ms) start-ms)
     :snapshot        snapshot
     :decorators      decorator-stack
     :effective-args  effective-args
     :lifecycle       (loaders/current-state variant-id)}))

(defn- prepare-context
  "Resolve the per-run inputs that every phase needs: the decorator
  stack, the effective args, and the identity snapshot. Returns a map;
  pure aside from the registrar reads.

  rf2-0wrud (2026-05-20): the legacy `:play` event-vector slot was
  removed; phase-4 reads `:play-script` (parsed via the runner) and
  drives the rich-DSL step executor through `runner-events/run!`."
  [variant-id variant-body opts]
  (let [{:keys [active-modes]} opts]
    {:variant-id      variant-id
     :variant-body    variant-body
     :decorator-stack (decorators/resolve-decorators variant-id
                                                     {:active-modes active-modes})
     :effective-args  (args/resolve-args variant-id opts)
     :snapshot        (ident/snapshot-identity variant-id opts)}))

(defn- run-phase-0!
  "Phase 0: allocate the variant frame with its decorator stack, then
  install the play-runner's trace listener.

  The listener install order matters (rf2-v2g9): it must be in place
  BEFORE phase-1 loaders fire so the assertions module's dispatched-
  events accumulator captures loader-phase events. `:loaders-complete-
  when`'s vector form consults that accumulator to gate the loaders-
  complete transition; an empty accumulator during loaders would never
  match. We seed the accumulators here so the listener has a clean
  slot; `execute-play!` resets them again at play start so play-time
  assertion semantics (\"during play\") are preserved."
  [{:keys [variant-id decorator-stack] :as ctx}]
  (frames/allocate! variant-id decorator-stack)
  (assertions/reset-trace-accumulators! variant-id)
  (play/install-trace-listener! variant-id)
  ctx)

(defn- run-phase-1!
  "Phase 1: drive loaders to completion. Thin wrapper that returns
  `ctx` so the orchestrator stays a clean threaded pipeline."
  [{:keys [variant-id] :as ctx}]
  (assoc ctx :loaders-complete? (run-loaders! variant-id)))

(defn- run-phase-2!
  "Phase 2: dispatch every `:events` entry, then mark events complete
  on the lifecycle machine."
  [{:keys [variant-id loaders-complete?] :as ctx}]
  (when loaders-complete?
    (run-events! variant-id)
    (loaders/finish-events! variant-id))
  ctx)

(defn- run-phase-4!
  "Phase 4: run the play-script. Returns the play-promise — the
  orchestrator chains `then` on it to know when to build the result.

  rf2-0wrud (2026-05-20): drives the rich-DSL `:play-script` runner via
  `runner-events/run!`. Variants without `:play-script` / `:plays`
  resolve to an empty script and the promise resolves immediately. The
  legacy `:play` event-vector slot was removed — author event sequences
  by wrapping each entry in `[:dispatch-sync <event-vec>]` inside a
  `:play-script` body.

  Phase 3 (render) is Stage 4's UI-shell concern and is not driven
  from this orchestrator."
  [{:keys [variant-id loaders-complete?]}]
  (if-not loaders-complete?
    (async/resolved (read-assertions variant-id))
    (let [plays (runner-events/variant-plays variant-id)
          auto-plays (filterv (fn [p] (and (:auto-run? p)
                                            (seq (:script p))))
                              plays)]
      (if (empty? auto-plays)
        (async/resolved (read-assertions variant-id))
        (async/promise
          (fn [resolve]
            ;; Run each auto-play sequentially. The `:rf.assert/*` events
            ;; dispatched-sync from `[:dispatch-sync ...]` steps record
            ;; into `:rf.story/assertions` on the frame via the standard
            ;; assertion handlers. Once every auto-play has finished, the
            ;; orchestrator builds the result map from the frame's
            ;; accumulated assertions.
            (letfn [(step! [remaining]
                      (if (empty? remaining)
                        (resolve (read-assertions variant-id))
                        (let [spec (first remaining)]
                          (runner-events/run! variant-id (:name spec) spec
                                              (fn [_state]
                                                (step! (rest remaining)))))))]
              (step! auto-plays))))))))

(defn- finalise-run!
  "Build and deliver the result map once phase 4's promise settles.
  Stage 5 (rf2-h8et) makes this chain load-bearing: `execute-play!`
  resolves the promise to the assertions vector, and we want the
  result map to read the post-play app-db."
  [resolve play-promise {:keys [variant-id decorator-stack effective-args snapshot]} start-ms]
  (-> play-promise
      (async/then
        (fn [_]
          (resolve (record-result-map variant-id decorator-stack
                                      effective-args snapshot start-ms))
          nil))))

(defn- handle-run-error!
  "Catch-branch for the orchestrator: record the exception as a
  phase-0-setup assertion (covers any sync throw from the phase chain),
  transition the lifecycle machine to `:error`, then resolve with the
  best result map we can build from whatever the run accumulated."
  [resolve variant-id e start-ms]
  (record-error! variant-id :phase-0-setup nil e)
  (loaders/error! variant-id (ex-data e))
  (resolve (record-result-map variant-id nil nil nil start-ms)))

(defn- unknown-variant-result
  "The error result returned when `frames/variant-body` finds no
  registration for `variant-id`. Kept separate so the missing-variant
  branch of `run-variant` reads as a single expression."
  [variant-id]
  (assoc (empty-result variant-id)
         :lifecycle :error
         :assertions [{:assertion  :rf.error/unknown-variant
                       :variant-id variant-id
                       :passed?    false}]))

(defn run-variant
  "Per IMPL-SPEC §3.2. Allocate a frame for `variant-id`, run the four-
  phase lifecycle, and return a promise/future of the result map.

  `opts`:
    :active-modes    coll of registered mode ids; deep-merged into args
    :cell-overrides  runtime arg overrides (controls panel)
    :substrate       active substrate (`:reagent`, `:uix`, ...)
    :render?         when truthy, Stage 4's UI shell renders into
                     `:rendered-hiccup`. Stage 3 leaves the slot nil.

  Returns a Promise (CLJS) / CompletableFuture (JVM). Per IMPL-SPEC
  §13.2 this is Stage 3's locked async-return shape.

  Production callers (CLJS `:advanced` with `enabled?` false) get an
  immediately-resolved promise of the empty result map — per IMPL-SPEC
  §6.3 the runtime doesn't throw when nothing is registered.

  Pre-requisite: `re-frame.story/install-canonical-vocabulary!` must
  have been called at boot — it registers the `::append-assertion`
  helper event this runtime dispatches into."
  ([variant-id] (run-variant variant-id nil))
  ([variant-id opts]
   (if-not config/enabled?
     (async/resolved (empty-result variant-id))
     (let [variant-body (frames/variant-body variant-id)]
       (if (nil? variant-body)
         (async/resolved (unknown-variant-result variant-id))
         (let [start-ms (interop/now-ms)]
           (async/promise
             (fn [resolve]
               (try
                 (let [ctx          (-> (prepare-context variant-id variant-body opts)
                                        run-phase-0!
                                        run-phase-1!
                                        run-phase-2!)
                       play-promise (run-phase-4! ctx)]
                   (finalise-run! resolve play-promise ctx start-ms))
                 (catch #?(:clj Throwable :cljs :default) e
                   (handle-run-error! resolve variant-id e start-ms)))))))))))

;; ---- reset-variant -------------------------------------------------------

(defn reset-variant
  "Tear down the variant frame and re-run `run-variant` with `opts`.
  Per IMPL-SPEC §3.2. Used by Stage 4's UI shell on hot-reload + user-
  triggered 'reset' button.

  Returns a promise/future of the new result map."
  ([variant-id] (reset-variant variant-id nil))
  ([variant-id opts]
   (when config/enabled?
     (frames/destroy! variant-id))
   (run-variant variant-id opts)))

;; ---- watch-variant -------------------------------------------------------

(defn watch-variant
  "Per IMPL-SPEC §3.2 — subscribe to lifecycle transitions for
  `variant-id`'s frame. `callback` is invoked on every state change
  with `{:frame-id ... :from <state> :to <state> :event <event>}`.

  Returns a 0-arity unsubscribe fn.

  Stage 4's UI shell + Stage 5's assertions runtime consume this. The
  watcher table is per-frame so destroyed frames clean up automatically
  via `frames/destroy!`."
  [variant-id callback]
  (when config/enabled?
    (loaders/add-watcher! variant-id callback)))

;; ---- snapshot-identity re-export ----------------------------------------

(defn snapshot-identity
  "Per IMPL-SPEC §3.2. Compute the content-hash for
  `(variant × active-modes × cell-overrides × substrate)`. See
  `re-frame.story.identity/snapshot-identity` for the canonical form."
  ([variant-id] (ident/snapshot-identity variant-id))
  ([variant-id opts] (ident/snapshot-identity variant-id opts)))
