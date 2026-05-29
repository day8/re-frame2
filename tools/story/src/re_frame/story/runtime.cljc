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
            [re-frame.story.error     :as story-error]
            [re-frame.story.frames    :as frames]
            [re-frame.story.identity  :as ident]
            [re-frame.story.loaders   :as loaders]
            [re-frame.story.plan      :as plan]
            [re-frame.story.play      :as play]
            [re-frame.story.play.runner :as runner]
            [re-frame.story.play.runner-events :as runner-events]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.result    :as result]
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
  {:status          :pass            ; rf2-5x1wt.19 — the unified verdict; a
                                     ; no-registration run is vacuously green
   :frame           variant-id
   :app-db          {}
   :assertions      []
   :checks          []
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

(defn- setup-step->event
  "Extract the event vector a normalized `[:world :setup]` step carries.
  Per spec/017 §Setup, setup steps are settled dispatches — the plan
  compiler coerces every authored setup entry (bare event vector OR a
  tagged `[:dispatch …]` / `[:dispatch-sync …]`) into a tagged dispatch
  step, and an `[:assert …]` in setup is rejected at plan-compile time
  (`re-frame.story.plan/reject-assert-in-setup!`). So a setup step is
  always `[:dispatch event-vector]` / `[:dispatch-sync event-vector]`;
  this returns the wrapped event vector (or the step itself when a
  bare event vector slipped through an out-of-band path)."
  [step]
  (cond
    (and (vector? step)
         (#{:dispatch :dispatch-sync} (first step))
         (vector? (second step)))
    (second step)

    ;; Defensive: a bare event vector (an out-of-band plan that bypassed
    ;; coercion). Treat it as the event itself.
    (and (vector? step) (keyword? (first step)))
    step

    :else nil))

(defn- plan-setup-events
  "The phase-2 event vectors for a run: the parent STORY's `:events`
  (resolved by id-grammar, NOT part of the variant plan — the plan
  compiler only resolves the `:extends` chain + composed fragments)
  prepended to the NORMALIZED PLAN's `[:world :setup]` steps
  (rf2-5x1wt.22, §B8 — phase 2 consumes the plan's `:setup`).

  The story-level events keep their existing id-grammar resolution so
  `story.foo/bar` still inherits `story.foo`'s preconditions; the
  variant-chain + composed-fragment setup comes from the plan, where
  `:extends` setup APPENDS root→child and `:setup` / `:events` are both
  lowered to the one slot (spec/017 §Merge rules).

  An INLINE plan (rf2-5x1wt.20) has no parent story (it is unregistered),
  so `story-id` resolves nil and only the plan's own `[:world :setup]`
  drives phase 2 — the plan is already the complete setup program."
  [variant-id plan]
  (let [story-id     (args/parent-story-id variant-id)
        story-body   (when story-id (registrar/handler-meta :story story-id))
        story-events (or (:events story-body) [])
        plan-setup   (get-in plan [:world :setup] [])]
    (vec (concat story-events
                 (keep setup-step->event plan-setup)))))

(defn- run-events!
  "Phase 2: dispatch every phase-2 event into the variant's frame,
  draining between each. Per IMPL-SPEC §5.4 phase 2.

  rf2-5x1wt.22 (§B8) — the variant-chain + composed-fragment setup now
  comes from the NORMALIZED PLAN's `[:world :setup]` (the tagged dispatch
  steps the compiler lowered `:setup` / `:events` into), routed through
  `plan-setup-events`. The parent story's `:events` (resolved by id
  grammar, not part of the plan) are prepended. The `dispatch-sync` +
  per-event exception-drain semantics are preserved verbatim."
  [variant-id plan]
  (let [all-events (plan-setup-events variant-id plan)]
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
  stays `:ready` end-to-end.

  rf2-5x1wt.20 — the loader BODY (`:loaders` + `:loaders-complete-when`)
  defaults to the registered variant body but MAY be supplied explicitly,
  so the inline-plan path (which has no registration) feeds the loader
  slots the compiler carried onto the plan's `:world`. The default keeps
  the registered path reading the side-table verbatim."
  ([variant-id] (run-loaders! variant-id (frames/variant-body variant-id)))
  ([variant-id loader-body]
   (if (= :ready (loaders/current-state variant-id))
    ;; Events-only fast-path (rf2-043cm). Lifecycle already terminal-
    ;; for-mount; the loader cascade has nothing to do.
    true
    (let [variant-body loader-body
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
                ;; loader throws surface in the test-mode UI / Xray.
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
            false)))))))

;; ---- error recording -----------------------------------------------------

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
  (let [record (story-error/exception-record variant-id phase event err)]
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

(defn install-canonical-runtime-events!
  "Register the runtime's internal helper events: `::append-assertion`
  for projecting exception captures + assertion records onto the
  variant frame's `:rf.story/assertions` accumulator. Idempotent.

  Mirrors the `install-canonical-<X>!` shape used by every sibling
  installer in `canonical/canonical-installers`. Was the vague
  `install-helpers!` (rf2-152jq inline rename)."
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

(defn- plan-checks
  "Resolve a normalized PLAN's `[:expect :checks]` ids into the
  `{check-id [assertion-atom …]}` map the unified result groups by
  (rf2-5x1wt.19). Expands each check id through the Story side-table
  `:check` kind (`plan/expand-checks`). Sourcing the check ids from the
  plan (rather than re-reading the variant body) means a REGISTERED
  variant and an INLINE plan (rf2-5x1wt.20 — unregistered) resolve their
  checks identically: the compiler already merged inherited + composed
  check ids into `[:expect :checks]`. Tolerant — any resolution failure
  yields an empty map (checks then group nothing; the run-level
  aggregation still sees ungrouped records)."
  [plan]
  (try
    (plan/expand-checks (get-in plan [:expect :checks]))
    (catch #?(:clj Throwable :cljs :default) _ {})))

(defn- plan-assertion-atoms
  "Collect EVERY declared assertion atom for a normalized PLAN, across the
  three positions in the ONE assertion-atom vocabulary: the terminal
  `[:expect :assertions]`, the expanded `[:expect :checks]` atoms, and the
  in-script `[:assert …]` checkpoints of the executed script. Pure aside
  from the check expansion's registrar reads; tolerant (any failure yields
  no atoms).

  This is the SINGLE collector; the predicate-specific helpers
  (`plan-schema-expectations`, `plan-causal-expectations`) are each a
  `filterv` over its output (rf2-2zncm). Feeds the tape-evaluated
  expectation matchers (`:rf.assert/schema-error`, `:rf.assert/caused` /
  `:rf.assert/no-cascade-rerender`, rf2-5x1wt.31) that — unlike a
  `reg-event-fx`-backed assertion — carry NO handler and so must be
  collected from the plan, not the `:rf.story/assertions` accumulator."
  [plan executed-script]
  (try
    (let [terminal     (vec (get-in plan [:expect :assertions]))
          check-atoms  (mapcat val (plan-checks plan))
          script-atoms (into []
                             (keep (fn [step]
                                     (when (and (vector? step)
                                                (= :assert (first step)))
                                       (second step))))
                             (or executed-script []))]
      (vec (concat terminal check-atoms script-atoms)))
    (catch #?(:clj Throwable :cljs :default) _ [])))

(defn- plan-schema-expectations
  "Collect every declared `[:rf.assert/schema-error spec]` atom for a
  normalized PLAN (rf2-5x1wt.21, spec/017 §Schema rule). These declare the
  EXPECTED schema violations the result boundary exactly-consumes against
  the projected tape evidence — so a missing/different violation fails the
  run, an exactly-expected violation passes.

  `:rf.assert/schema-error` is NOT dispatched into the frame (it has no
  reg-event-fx handler — it is tape-evaluated), so collecting the DECLARED
  atoms here is the single path that feeds the consumption matcher.

  Defined as a FILTER over the shared `plan-assertion-atoms` collector —
  the same pattern `plan-causal-expectations` uses (rf2-2zncm). The
  collector already wraps the three positions in a tolerant try/catch, so
  `filterv` over its `(vec (concat …))` output is identical to filtering
  the bare `concat` (filterv ignores the extra vec)."
  [plan executed-script]
  (filterv assertions/schema-error? (plan-assertion-atoms plan executed-script)))

(defn- plan-causal-expectations
  "Collect every declared `:rf.assert/caused` / `:rf.assert/no-cascade-
  rerender` atom for a normalized PLAN (rf2-5x1wt.31, spec/017 §Causal and
  cascade assertions). These are tape-evaluated against the projected
  `:reactive-counts` `:by-cause` projection (NOT dispatched into the frame,
  NOT a parallel accumulator), so — like `:rf.assert/schema-error` —
  collecting the DECLARED atoms here is the single path that feeds the
  result boundary's causal matcher (`result/match-causal-expectations`)."
  [plan executed-script]
  (filterv assertions/causal? (plan-assertion-atoms plan executed-script)))

(defn- record-result-map
  "Build the unified run-result returned by `run-variant` (rf2-5x1wt.19,
  spec/017 §Run result + §Unified run result). Gathers whatever the
  runtime accumulated against the variant's frame and assembles the ONE
  shared shape via `result/run-result`:

  - the evidential slots (`:status` floor, `:epoch-tape`,
    `:schema-violations`, `:warnings`, `:effects`, `:sub-runs`,
    `:renders`, `:narrative`) are PROJECTED from the retained epoch tape
    (`.4`'s `evidence/project-evidence`, via `result/run-result`) — NOT a
    parallel accumulator;
  - the judgement slots (`:assertions` / `:checks`) fold the
    `:rf.story/assertions` accumulator (the ONE non-tape input) into
    unified records + groups them under their check ids;
  - the top-level `:status` is the unified verdict.

  The legacy `:lifecycle` / `:frame` / `:snapshot` / `:decorators` /
  `:effective-args` / `:rendered-hiccup` slots are PRESERVED (API stable,
  spec/017 §1a — `:status` is NET-NEW alongside `:lifecycle`), so existing
  consumers keep reading their slots while the unified shape lands.

  Checks + schema-expectations are sourced from the compiled `:plan`
  (rf2-5x1wt.20), so a registered variant and an INLINE plan assemble the
  same result through one path."
  [{:keys [variant-id decorator-stack effective-args snapshot executed-script plan]} start-ms]
  (let [app-db   (rf/get-frame-db variant-id)
        ;; rf2-5x1wt.19 follow-through — read the epoch tape through the
        ;; late-bound `re-frame.core/epoch-history` facade (mirroring
        ;; `re-frame.story.artifact`'s replay-path read), NOT a hard
        ;; `[re-frame.epoch …]` require. Story's published surface (and
        ;; its downstream consumer `tools/story-mcp`) carry no epoch dep on
        ;; the production classpath; the facade degrades to `[]` there
        ;; (per `re-frame.core/epoch-history`'s contract) while the
        ;; `:test`-alias epoch dep makes it the live tape under the gate.
        tape     (vec (rf/epoch-history variant-id))
        ;; rf2-q5jw4 — thread the run-state's `:cannot-run` refusals into the
        ;; unified result's `:unmet` slot. The runner's per-step `:results`
        ;; carry the SAME refusal facts `runner/finish` reads to compute the
        ;; run-state status (a no-DOM `[:assert-dom …]` skip, a boundary
        ;; `:cannot-run?`), but those steps record NO `:rf.story/assertions`
        ;; entry — so without this the unified result aggregated to `:pass`
        ;; (vacuous green) while the run-state read `:cannot-run`. Folding the
        ;; refusals here makes both consumers agree (spec/017 §Unified run
        ;; result — "a run whose only unmet expectations are :cannot-run is
        ;; itself :cannot-run"). The facade degrades to nil run-state (and so
        ;; an empty `:unmet`) on a host with no runner-events run-state.
        unmet    (runner/run-state-refusals (runner-events/current-state variant-id))
        unified  (result/run-result
                   {:variant/id          variant-id
                    :epoch-tape          tape
                    :assertions          (or (:rf.story/assertions app-db) [])
                    :script              executed-script
                    :check->atoms        (plan-checks plan)
                    ;; rf2-5x1wt.21 — the declared `:rf.assert/schema-error`
                    ;; expectations, EXACT-consumed against the projected
                    ;; tape violations (§Schema rule). Tape-evaluated, NOT
                    ;; dispatched — collected from the plan, not the
                    ;; `:rf.story/assertions` accumulator.
                    :schema-expectations (plan-schema-expectations
                                           plan executed-script)
                    ;; rf2-5x1wt.31 — the declared causal / cascade
                    ;; expectations (`:rf.assert/caused` /
                    ;; `:rf.assert/no-cascade-rerender`), tape-evaluated
                    ;; against the projected `:reactive-counts` `:by-cause`
                    ;; projection (§Causal and cascade assertions). Like the
                    ;; schema-error expectations they are tape-evaluated, NOT
                    ;; dispatched — collected from the plan.
                    :causal-expectations (plan-causal-expectations
                                           plan executed-script)
                    ;; rf2-q5jw4 — the per-requirement `:cannot-run` refusals
                    ;; the runner could not even attempt (above). Folded into
                    ;; the verdict + surfaced on `:cannot-run` by
                    ;; `result/run-result`.
                    :unmet               unmet
                    :app-db              (or app-db {})
                    :elapsed-ms          (- (interop/now-ms) start-ms)})]
    (merge unified
           {:frame           variant-id
            :rendered-hiccup nil       ;; Stage 4 fills this in
            :snapshot        snapshot
            :decorators      decorator-stack
            :effective-args  effective-args
            :lifecycle       (loaders/current-state variant-id)})))

(defn- prepare-context
  "Resolve the per-run inputs that every phase needs: the NORMALIZED
  PLAN, the decorator stack, the effective args, and the identity
  snapshot. Returns a map; pure aside from the registrar reads.

  rf2-5x1wt.22 (§B8 — Runtime Migration) — the shipping run-variant path
  now routes through the variant-plan compiler (`re-frame.story.plan`).
  `prepare-context` compiles the normalized plan ONCE and threads it
  down the phase pipeline (spec/017 §Variant plan — every registered
  variant MUST be normalized before execution). Phase 2 consumes the
  plan's `[:world :setup]` (the tagged setup steps); phase 4 consumes
  its `[:world :scripts]` (the named scripts — `:plays` preserved). A
  plan-construction failure (an unknown variant, a missing `[:arg …]`,
  a misplaced `[:assert …]` in setup, …) throws here; the orchestrator's
  try/catch (`handle-run-error!`) projects it onto the run result so the
  error reports the same way it did before the routing.

  rf2-0wrud (2026-05-20): the legacy `:play` event-vector slot was
  removed; phase-4 drives the rich-DSL step executor through
  `runner-events/run!`."
  [variant-id variant-body opts]
  (let [{:keys [active-modes]} opts]
    {:variant-id      variant-id
     :variant-body    variant-body
     :plan            (plan/variant-plan variant-id)
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
  `ctx` so the orchestrator stays a clean threaded pipeline.

  rf2-5x1wt.20 — the loader body defaults to the registered variant body
  (`run-loaders!` 1-arity); an inline run threads `:loader-body` on the
  ctx (the plan's `:world` loader slots), so an inline plan's loaders run
  through the SAME phase fn without reading the side-table."
  [{:keys [variant-id loader-body] :as ctx}]
  (assoc ctx :loaders-complete?
         (if (contains? ctx :loader-body)
           (run-loaders! variant-id loader-body)
           (run-loaders! variant-id))))

(defn- run-phase-2!
  "Phase 2: dispatch the plan's `[:world :setup]` (+ the parent story's
  `:events`), then mark events complete on the lifecycle machine.
  rf2-5x1wt.22 (§B8) — setup is sourced from the normalized plan."
  [{:keys [variant-id loaders-complete? plan] :as ctx}]
  (when loaders-complete?
    (run-events! variant-id plan)
    (loaders/finish-events! variant-id))
  ctx)

(defn- run-phase-4!
  "Phase 4: run the play-script. Returns `[ctx' play-promise]` — `ctx'`
  carries `:executed-script` (the folded steps the auto-plays ran, for the
  unified result's two-level narrative — rf2-5x1wt.19), and the
  orchestrator chains `then` on the promise to know when to build the
  result.

  rf2-0wrud (2026-05-20): drives the rich-DSL `:play-script` runner via
  `runner-events/run!`. Variants without `:play-script` / `:plays`
  resolve to an empty script and the promise resolves immediately. The
  legacy `:play` event-vector slot was removed — author event sequences
  by wrapping each entry in `[:dispatch-sync <event-vec>]` inside a
  `:play-script` body.

  rf2-5x1wt.19 — the resolved play scripts are FOLDED (shipping
  `:assert-db` / `:assert-dom` rewritten to the canonical `[:assert …]`
  checkpoint), so the executed-script narrative carries the one
  assertion atom.

  rf2-5x1wt.22 (§B8) — the play set now comes from the NORMALIZED PLAN's
  `[:world :scripts]` (the named scripts the compiler's `normalize-scripts`
  produces — `:plays` preserved as named scripts, spec/017 §Public
  vocabulary), NOT a second `runner-events/variant-plays` read of the
  registered body. The compiler already coerces + folds every script, so
  the plan's `:scripts` carry the `{:script :auto-run? :name}` shape the
  runner drives directly.

  Phase 3 (render) is Stage 4's UI-shell concern and is not driven
  from this orchestrator."
  [{:keys [variant-id loaders-complete? plan] :as ctx}]
  (if-not loaders-complete?
    [ctx (async/resolved (read-assertions variant-id))]
    (let [plays      (get-in plan [:world :scripts] [])
          auto-plays (runner/auto-runnable-plays plays)
          ;; The folded steps the auto-plays ran, concatenated in order —
          ;; the script the unified result's two-level narrative spans.
          executed   (vec (mapcat :script auto-plays))
          ctx'       (assoc ctx :executed-script executed)]
      (if (empty? auto-plays)
        [ctx' (async/resolved (read-assertions variant-id))]
        [ctx'
         (async/promise
           (fn [resolve]
             ;; Run each auto-play sequentially. The `:rf.assert/*` events
             ;; the folded `[:assert …]` checkpoints dispatch record into
             ;; `:rf.story/assertions` on the frame via the standard
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
               (step! auto-plays))))]))))

(defn- finalise-run!
  "Build and deliver the result map once phase 4's promise settles.
  Stage 5 (rf2-h8et) makes this chain load-bearing: `execute-play!`
  resolves the promise to the assertions vector, and we want the
  result map to read the post-play app-db."
  [resolve play-promise ctx start-ms]
  (-> play-promise
      (async/then
        (fn [_]
          (resolve (record-result-map ctx start-ms))
          nil))))

(defn- plan-construction-error?
  "True iff `e` is a plan-construction failure — an `ex-info` `re-frame.
  story.plan/fail!` threw. `fail!` stamps `:where 'rf.story/variant-plan`
  on every `:rf.error/story-*` failure, so that marker (NOT the bare
  presence of `:rf.error/id`) is the discriminator. rf2-5x1wt.22 (§B8):
  the runtime compiles the plan in `prepare-context`, BEFORE the frame is
  allocated, so a malformed variant (a missing `[:arg …]`, an `[:assert …]`
  in `:setup`, a `:compose` of an unknown fragment, …) throws here. Such
  an error cannot be recorded onto a frame (none exists yet), so it is
  projected directly into a structured error result (`plan-error-result`)
  rather than routed through `handle-run-error!` (which assumes an
  allocated frame).

  Matching on `:where` (not on any `:rf.error/id`) is load-bearing:
  framework runtime errors thrown LATER in the phase chain also carry an
  `:rf.error/id` — e.g. `:rf.error/no-adapter-installed` from
  `reg-frame` → `make-state-container` when the host installed no
  adapter (the JVM-standalone story-mcp server). Those land after the
  frame exists at `:pre-mount`, so they must take the frame-bound
  record/transition branch, NOT `plan-error-result` (rf2-5x1wt.22
  follow-through)."
  [e]
  (= 'rf.story/variant-plan (:where (ex-data e))))

(defn- exception-message [e]
  #?(:clj  (.getMessage ^Throwable e)
     :cljs (str e)))

(defn- plan-error-result
  "The error result returned when `re-frame.story.plan/variant-plan`
  FAILS to compile the variant (rf2-5x1wt.22, §B8). The frame is not
  allocated when plan construction throws, so the result is built
  directly from the exception — mirroring `unknown-variant-result`'s
  frame-free shape. The `:rf.error/story-*` id rides the assertion record
  so tools surface the plan failure the same way a registration failure
  surfaces."
  [variant-id e]
  (assoc (empty-result variant-id)
         :status     :error
         :lifecycle  :error
         :assertions [{:assertion  (or (:rf.error/id (ex-data e))
                                       :rf.error/story-plan-invalid)
                       :variant-id variant-id
                       :status     :error
                       :passed?    false
                       :reason     (exception-message e)
                       :error      (story-error/throwable->error-map e)}]))

(defn- handle-run-error!
  "Catch-branch for the orchestrator: record the exception as a
  phase-0-setup assertion (covers any sync throw from the phase chain),
  transition the lifecycle machine to `:error`, then resolve with the
  best result map we can build from whatever the run accumulated. The
  recorded `:rf.error/exception` assertion (`:passed? false`) flows into
  the unified result's `:assertions`, so the aggregation reports `:fail`
  (the error record is a failed expectation) even when the tape is sparse.

  rf2-5x1wt.22 (§B8) — a plan-construction failure (thrown in
  `prepare-context`, before frame allocation) cannot be recorded onto a
  frame, so it is projected directly via `plan-error-result`. Every other
  throw lands after the frame exists and routes through the frame-bound
  record/transition path."
  [resolve variant-id e start-ms]
  (if (and (plan-construction-error? e)
           (= :pre-mount (loaders/current-state variant-id)))
    (resolve (plan-error-result variant-id e))
    (do
      (record-error! variant-id :phase-0-setup nil e)
      (loaders/error! variant-id (ex-data e))
      (resolve (record-result-map {:variant-id variant-id} start-ms)))))

(defn- unknown-variant-result
  "The error result returned when `frames/variant-body` finds no
  registration for `variant-id`. Kept separate so the missing-variant
  branch of `run-variant` reads as a single expression."
  [variant-id]
  (assoc (empty-result variant-id)
         :status     :error
         :lifecycle  :error
         :assertions [{:assertion  :rf.error/unknown-variant
                       :variant-id variant-id
                       :status     :error
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
                       [ctx' play-promise] (run-phase-4! ctx)]
                   (finalise-run! resolve play-promise ctx' start-ms))
                 (catch #?(:clj Throwable :cljs :default) e
                   (handle-run-error! resolve variant-id e start-ms)))))))))))

;; ---- inline plan execution (rf2-5x1wt.20) --------------------------------
;;
;; An inline plan (spec/017 §Inline plan) is an executable plan MAP that is
;; NOT registered as a Story variant: it MUST NOT appear in Story
;; navigation, it MAY compose registered fragments + checks, and it MUST
;; return the SAME run-result shape as a registered variant. `story/run`,
;; `story/is`, and `story/explain` already accept a map target (the verbs
;; dispatch on target type — a keyword is a registry lookup, a map is an
;; inline plan); `variant-plan` / `explain` compile a map directly
;; (rf2-5x1wt.24). This is the RUN path for a map target.
;;
;; The design reuses the registered run pipeline rather than forking it:
;;
;;   - the plan is compiled once (`plan/variant-plan` accepts the map);
;;   - an ANONYMOUS frame id is minted in the reserved `:rf.story.inline/*`
;;     namespace (NEVER a registered variant id, so navigation can't surface
;;     it and a concurrent registered run can't collide);
;;   - the decorator stack is resolved from the plan's `[:world :decorators]`
;;     refs (registry-free — the variant/story side-table is not read);
;;   - the SAME phase fns (`run-phase-1!` / `run-phase-2!` / `run-phase-4!`)
;;     drive loaders → setup → script, all sourced from the plan;
;;   - `record-result-map` assembles the one unified result from the plan +
;;     the frame's accumulated assertions + the epoch tape;
;;   - the anonymous frame is torn down once the run resolves.

(defonce ^:private inline-frame-counter (atom 0))

(defn- mint-inline-frame-id
  "Mint a fresh anonymous frame id for an inline-plan run, in the reserved
  `:rf.story.inline/*` namespace (spec/Conventions.md §Reserved
  namespaces). A monotonic counter keeps concurrent inline runs on
  distinct frames; the id is never a registered variant id, so an inline
  run can never collide with — or appear alongside — a navigable variant."
  []
  (keyword "rf.story.inline" (str "plan-" (swap! inline-frame-counter inc))))

(defn- prepare-inline-context
  "Resolve the per-run inputs an inline-plan run needs from its COMPILED
  `plan` (rf2-5x1wt.20). Registry-free twin of `prepare-context`:

  - `:variant-id`      — the minted anonymous frame id (the run's frame);
  - `:plan`            — the supplied compiled plan, threaded down the
                          phases unchanged (it is already normalized);
  - `:decorator-stack` — `decorators/resolve-decorator-refs` over the
                          plan's `[:world :decorators]` refs (the compiler
                          merged the variant chain + composed fragments
                          into that vector);
  - `:effective-args`  — the plan's `[:world :effective-args]` (resolved at
                          compile time);
  - `:loader-body`     — the loader slots (`:loaders` /
                          `:loaders-complete-when`) the compiler carried
                          onto `:world`, so phase 1 runs the inline plan's
                          loaders without a registry read;
  - `:snapshot`        — nil; an unregistered inline plan has no navigable
                          snapshot identity.

  Pure aside from the decorator-body registrar reads (a decorator is a
  registered artefact even when the plan is not)."
  [frame-id plan]
  {:variant-id      frame-id
   :plan            plan
   :decorator-stack (decorators/resolve-decorator-refs
                      (get-in plan [:world :decorators] []))
   :effective-args  (get-in plan [:world :effective-args] {})
   :loader-body     {:loaders               (get-in plan [:world :loaders])
                     :loaders-complete-when (get-in plan [:world :loaders-complete-when])}
   :snapshot        nil})

(defn- inline-events-only?
  "True iff the inline `plan` drives no loaders / loaders-complete-when and
  carries no `:frame-setup` decorators — so the lifecycle takes the
  `:pre-mount → :ready` fast-path (rf2-043cm). Mirrors
  `loaders/events-only-variant?`, reading the loader slots off the plan's
  `:world` rather than a registered body."
  [plan decorator-stack]
  (and (empty? (get-in plan [:world :loaders]))
       (nil?   (get-in plan [:world :loaders-complete-when]))
       (empty? (:frame-setup decorator-stack))))

(defn- run-inline-phase-0!
  "Phase 0 for an inline run: allocate the ANONYMOUS frame from the plan
  (registry-free), then seed the assertion accumulators + install the
  play-runner trace listener — the same ordering `run-phase-0!` uses so
  loader-phase events are captured."
  [{:keys [variant-id plan decorator-stack] :as ctx}]
  (frames/allocate-inline! variant-id
                           decorator-stack
                           (get-in plan [:world :frame :fx-overrides])
                           (inline-events-only? plan decorator-stack))
  (assertions/reset-trace-accumulators! variant-id)
  (play/install-trace-listener! variant-id)
  ctx)

(defn run-inline-plan
  "Execute an inline plan MAP (rf2-5x1wt.20, spec/017 §Inline plan) and
  return a promise/future of the unified run-result — the SAME shape a
  registered variant run returns.

  `inline-plan` is the inline plan body (a map). `opts` is the run opts
  threaded to the plan compiler (`:lookup` / `:fragment-lookup` /
  `:check-lookup` / `:view-lookup` / `:sub-lookup` / `:validator-fns` — so
  the plan MAY compose REGISTERED fragments + checks, or thread explicit
  lookups for a host-free run). The plan is compiled ONCE, run against a
  fresh anonymous frame, and the frame is torn down when the run resolves.

  A plan-construction failure (an unknown composed fragment, a missing
  `[:arg …]`, a misplaced `[:assert …]` in setup, …) surfaces as the same
  structured `:rf.error/story-*` error result a registered variant's
  malformed plan produces — the frame is never allocated in that case.

  The inline plan is NOT registered in the Story side-table; it is absent
  from Story navigation by construction (no registration + an anonymous
  frame stamped `:rf/inline?`)."
  ([inline-plan] (run-inline-plan inline-plan nil))
  ([inline-plan opts]
   (if-not config/enabled?
     (async/resolved (empty-result (:variant/id inline-plan)))
     (let [start-ms     (interop/now-ms)
           compile-opts (select-keys opts [:lookup :fragment-lookup :check-lookup
                                           :view-lookup :sub-lookup :validator-fns])
           ;; Compile the plan up front so a construction failure (an
           ;; unknown composed fragment, a missing `[:arg …]`, an
           ;; `[:assert …]` in setup, …) is projected directly — no frame
           ;; is allocated, mirroring `plan-error-result`'s frame-free shape.
           plan-or-err  (try {:plan (plan/variant-plan inline-plan compile-opts)}
                          (catch #?(:clj Throwable :cljs :default) e {:error e}))]
       (if-let [e (:error plan-or-err)]
         (async/resolved (plan-error-result (:variant/id inline-plan) e))
         (let [plan     (:plan plan-or-err)
               frame-id (mint-inline-frame-id)]
           (async/promise
             (fn [resolve]
               (try
                 (let [ctx          (-> (prepare-inline-context frame-id plan)
                                        run-inline-phase-0!
                                        run-phase-1!
                                        run-phase-2!)
                       [ctx' play-promise] (run-phase-4! ctx)]
                   (-> play-promise
                       (async/then
                         (fn [_]
                           (let [result (record-result-map ctx' start-ms)]
                             (frames/destroy-inline!
                               frame-id (:decorator-stack ctx')
                               (get-in plan [:world :loaders-teardown]))
                             (resolve result))
                           nil))))
                 (catch #?(:clj Throwable :cljs :default) e
                   (record-error! frame-id :phase-0-setup nil e)
                   (loaders/error! frame-id (ex-data e))
                   (let [result (record-result-map
                                  {:variant-id frame-id :plan plan} start-ms)]
                     (try (frames/destroy-inline! frame-id nil nil)
                       (catch #?(:clj Throwable :cljs :default) _ nil))
                     (resolve result))))))))))))

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
