(ns re-frame.story.runtime
  "Story runtime orchestration. Per `002-Runtime.md` §Programmatic API + §Four-phase lifecycle with `:loaders-complete-when`.

  The runtime consumes the registered artefacts and resolves them into a
  runnable variant:

  - `run-variant`     — allocate frame; run four-phase lifecycle;
                        return a promise/future of the result map.
  - `reset-variant`   — tear down and re-run.
  - `prepare-variant` — re-run phases 0-2 ONLY (loaders + setup, no
                        script), for a caller that owns the script itself
                        — the `:test` pane's step-debugger.
  - `watch-variant`   — subscribe to lifecycle transitions.
  - `snapshot-identity` — re-export of `re-frame.story.identity/snapshot-identity`.

  ## The result map

  `re-frame.story.result` owns the schema-backed unified result consumed by
  Test mode, CI, `clojure.test`, and MCP. This namespace supplies the live
  frame, assertion accumulator, epoch tape, execution metadata, and snapshot
  inputs to that pure assembler; it does not maintain a second result shape.

  ## Elision

  Every entry point checks `re-frame.story.config/enabled?`. When
  false (production CLJS builds), the fns return an empty result map
  immediately — the inner body, the registrar lookups, and the frame
  allocation all elide. Per `001-Authoring.md` §Registration macros this is a *feature*:
  production code that accidentally calls `run-variant` does not throw
  — it returns empty."
  (:require [re-frame.core            :as rf]
            [re-frame.error           :as rf.error]
            [re-frame.late-bind       :as rf.late-bind]
            [re-frame.story.args      :as rf.story.args]
            [re-frame.story.assertions :as rf.story.assertions]
            [re-frame.story.async     :as rf.story.async]
            [re-frame.story.config    :as rf.story.config]
            [re-frame.story.decorators :as rf.story.decorators]
            [re-frame.story.error     :as rf.story.error]
            [re-frame.story.frames    :as rf.story.frames]
            [re-frame.story.identity  :as rf.story.identity]
            [re-frame.story.loaders   :as rf.story.loaders]
            [re-frame.story.plan      :as rf.story.plan]
            [re-frame.story.play      :as rf.story.play]
            [re-frame.story.play.evidence :as rf.story.play.evidence]
            [re-frame.story.play.runner :as rf.story.play.runner]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.requirements :as rf.story.requirements]
            [re-frame.story.result    :as rf.story.result]
            [re-frame.interop         :as rf.interop]
            [re-frame.trace           :as rf.trace]
            ;; The listener API lives in `re-frame.trace.tooling`
            ;; (production-DCE split). The hot-path emit fast-path
            ;; (`rf.trace/emit!`) stays in `re-frame.trace`.
            [re-frame.trace.tooling   :as rf.trace.tooling]))

;; ---- empty / disabled result ---------------------------------------------

(defn- empty-result
  "Per `001-Authoring.md` §Registration macros: production callers see an empty result map
  rather than an exception. The shape matches a successful run with
  no registrations to act on."
  [variant-id]
  {:status          :pass            ; the unified verdict; a
                                     ; no-registration run is vacuously green
   :frame           variant-id
   :app-db          {}
   :assertions      []
   :checks          []
   :elapsed-ms      0
   :snapshot        nil
   :decorators      {:hiccup [] :frame-setup [] :fx-override [] :errors []}
   :effective-args  {}
   :lifecycle       :ready})

;; Forward declarations so phase fns (defined before record helpers) can
;; project failures per `002-Runtime.md` §Error projection without reordering the file.
(declare record-error! record-loader-incomplete!)

;; ---- phase exception capture --------------------------------------------
;;
;; re-frame's interceptor chain catches handler exceptions internally and
;; emits a `:rf.error/handler-exception` trace event rather than re-
;; throwing. Story's phase runners convert those trace events
;; into assertion records (per `002-Runtime.md` §Error projection).
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
    (rf.trace.tooling/register-listener! cb-id listener)
    (try (body-fn)
      (finally (rf.trace.tooling/unregister-listener! cb-id)))))

(defn- capture-phase-errors
  "Run `body-fn` (a 0-arg thunk) with a registered trace listener that
  collects PIPELINE-EXCEPTION events targeting `variant-id`'s frame.
  After the body returns, walks the captured errors and records each as
  a phase-tagged assertion via `record-error!`. Returns `body-fn`'s
  return value.

  The capture set is every operation in
  `rf.story.error/pipeline-exception-operations` (handler-exception,
  coeffect-exception, interceptor-exception), not just
  `:rf.error/handler-exception`. A loader/event phase whose cofx
  injector or user interceptor throws is caught by the shared
  `pipeline-exception-event?` predicate, and the originating
  `:operation` / `:failing-id` are preserved onto the record so a cofx
  failure is distinguishable from a handler failure.

  Per Spec 009 §Privacy + EP-0015: pipeline-exception trace
  events whose `:sensitive?` flag is true are dropped from the capture
  set when Story's local-render egress profile redacts
  (`:rf.egress/local-redacted` — the default). A counter bump is recorded
  so the UI's redaction hint can surface 'N sensitive events suppressed'."
  [variant-id phase body-fn]
  (let [collected (atom [])
        listener  (fn [ev]
                    (cond
                      ;; Resolve the suppress decision against the event's
                      ;; own frame (per-(tool,frame) visibility).
                      (rf.story.config/suppress-sensitive? ev)
                      (rf.story.config/note-suppressed! (rf.trace/trace-event-frame ev))

                      (rf.story.error/pipeline-exception-event? variant-id ev)
                      (swap! collected conj ev)))]
    (with-trace-listener
      listener
      (fn []
        (let [result (body-fn)]
          (doseq [ev @collected]
            (record-error! variant-id phase
                           (get-in ev [:tags :event])
                           (get-in ev [:tags :exception])
                           {:operation  (:operation ev)
                            :failing-id (get-in ev [:tags :failing-id])}))
          result)))))

;; ---- phase-2 events execution --------------------------------------------

(defn- setup-step->event
  "Lower a normalized `[:world :setup]` step to the event vector the
  HEADLESS phase-2 executor dispatches, or `nil` when the step is not a
  headless dispatch.

  The plan compiler coerces every authored setup entry through the same
  `coerce-script` the `:script` gets (spec/017 §Script step grammar): a
  bare event-vector shorthand lifts to `[:dispatch …]`, so a stored setup
  step is `[:dispatch event-vector]` / `[:dispatch-sync event-vector]`.
  This returns the wrapped event vector for those, plus a bare event
  vector that bypassed coercion (an out-of-band path).

  A NON-dispatch step (`[:wait …]`, `[:wait-until …]`,
  `[:flush-presence …]`, `[:click …]`, `[:type …]`, `[:focus …]`) is legal
  in `:setup` per the grammar table, but the headless phase-2 path can only
  `dispatch-sync` — it cannot sleep, settle on a predicate, advance the
  presence clock or drive the DOM. Such a step returns `nil` here; `plan-setup-events`
  turns that into a loud `:cannot-run`-shaped refusal rather than a
  silent drop (spec/017 §Script and settled-boundary — a step a runner
  cannot honour FAILS CLOSED, never under-runs and passes falsely)."
  [step]
  (cond
    (and (vector? step)
         (#{:dispatch :dispatch-sync} (first step))
         (vector? (second step)))
    (second step)

    ;; A bare event vector (an out-of-band plan that bypassed coercion):
    ;; a re-frame event vector whose head is NOT a known step tag. Treat
    ;; it as the event itself. A known step tag (`:wait`/`:click`/…) is
    ;; NOT a bare event — it falls through to the refusal below.
    (and (vector? step)
         (keyword? (first step))
         (not (rf.story.play.runner/known-step? step)))
    step

    :else nil))

(defn- non-dispatch-setup-step? [step]
  (nil? (setup-step->event step)))

(defn- plan-setup-events
  "The phase-2 event vectors for a run: the NORMALIZED PLAN's
  `[:world :setup]` steps (§B8 — phase 2 consumes the plan's `:setup`),
  where `:extends` setup APPENDS root→child and composed fragments land
  in declared order (spec/017 §Merge rules). The parent story carries no
  setup slot (the `Story` schema is closed without one), so the plan is
  the complete setup program for registered and inline variants alike.

  REFUSES (throws `:rf.error/story-setup-step-unrunnable`) when a setup
  step is a non-dispatch step (`[:wait …]` / `[:click …]` / `[:type …]`
  / `[:focus …]` / `[:wait-until …]` / `[:flush-presence …]`). Such a step
  is legal in `:setup`, but the headless phase-2 path can only
  `dispatch-sync` — it cannot honour a DOM/reactive boundary or advance
  the presence clock. Per spec/017 §Script and settled-boundary a
  step a runner cannot honour FAILS CLOSED (`:cannot-run`); silently
  dropping it is the forbidden under-run that vanishes a precondition the
  author wrote. The throw is caught by the orchestrator and projected as
  an `:error` run result."
  [variant-id plan]
  (let [plan-setup (get-in plan [:world :setup] [])]
    (when-let [offenders (seq (filter non-dispatch-setup-step? plan-setup))]
      (rf.error/throw-error!
        :rf.error/story-setup-step-unrunnable
        'rf.story/run-variant
        (str "re-frame2-story: variant " variant-id
             " — :setup carries a non-dispatch step "
             (pr-str (vec offenders))
             " that the headless runner cannot execute. A "
             ":wait / :wait-until / :flush-presence / :click / :type / "
             ":focus step is legal in :setup but the headless phase-2 "
             "path can only dispatch-sync; run this variant under a "
             "richer runner, or move the step to :script.")
        {:recovery :use-a-richer-runner-or-move-to-script
         :extra    {:variant/id      variant-id
                    :offending-steps (vec offenders)}}))
    (mapv setup-step->event plan-setup)))

(defn- run-events!
  "Phase 2: dispatch every phase-2 event into the variant's frame,
  draining between each. Per `002-Runtime.md` §Four-phase lifecycle with `:loaders-complete-when` phase 2.

  §B8 — the variant-chain + composed-fragment setup comes from the
  NORMALIZED PLAN's `[:world :setup]` (the tagged dispatch steps the
  compiler lowered `:setup` into), routed through `plan-setup-events`.
  The `dispatch-sync` + per-event exception-drain semantics apply per
  event."
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
              ;; Drain handler-exception trace events that the router
              ;; caught into the assertions list so phase-2 throws land
              ;; where the test-mode UI looks for them.
              (rf.story.play/drain-pending-exceptions! variant-id :phase-2-events))))))))

;; ---- phase-1 loaders execution -------------------------------------------

(defn- run-loaders!
  "Phase 1: dispatch every event in `:loaders` into the variant's
  frame, evaluating `:loaders-complete-when` after each. Per `002-Runtime.md`
  §Four-phase lifecycle with `:loaders-complete-when` phase 1.

  The headless path is synchronous: `dispatch-sync` drains
  run-to-completion before returning, so the
  default predicate's 'no further events in flight' check passes
  trivially. Variants with long-lived fx (websocket / interval) supply
  `:loaders-complete-when`, which is resolved through the predicate
  evaluator.

  Events-only fast-path: when `rf.story.frames/allocate!` drives the lifecycle
  straight to `:ready` (no loaders / no frame-setup /
  no `:loaders-complete-when`), this fn short-circuits with `true`
  rather than firing `start-loaders!`/`finish-loaders!` against a
  machine that's already terminal-for-mount. Both helpers would
  silently no-op (the `:ready` node only accepts `:errored`), but
  routing past them keeps the phase reads honest: `current-state`
  stays `:ready` end-to-end.

  The loader BODY (`:loaders` + `:loaders-complete-when`)
  defaults to the registered variant body but MAY be supplied explicitly,
  so the inline-plan path (which has no registration) feeds the loader
  slots the compiler carried onto the plan's `:world`. The default keeps
  the registered path reading the side-table verbatim."
  ([variant-id] (run-loaders! variant-id (rf.story.frames/variant-body variant-id)))
  ([variant-id loader-body]
   (if (= :ready (rf.story.loaders/current-state variant-id))
    ;; Events-only fast-path. Lifecycle already terminal-
    ;; for-mount; the loader cascade has nothing to do.
    true
    (let [variant-body loader-body
          loader-events (or (:loaders variant-body) [])]
      (rf.story.loaders/start-loaders! variant-id)
      (capture-phase-errors
        variant-id :phase-1-loaders
        (fn []
          (doseq [ev loader-events]
            (try
              (rf/dispatch-sync ev {:frame variant-id})
              (catch #?(:clj Throwable :cljs :default) e
                (record-error! variant-id :phase-1-loaders ev e))
              (finally
                ;; Drain handler-exception trace events the router caught
                ;; into the assertions list so phase-1 loader throws
                ;; surface in the test-mode UI / Xray.
                (rf.story.play/drain-pending-exceptions! variant-id :phase-1-loaders))))))
      ;; The current predicate contract resolves synchronously after the
      ;; loader events have drained.
      (let [complete? (rf.story.loaders/evaluate-complete-when variant-id variant-body)]
        (if complete?
          (do
            (rf.story.loaders/finish-loaders! variant-id)
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
  accumulator. Per `002-Runtime.md` §Error projection errors continue the play sequence
  rather than aborting — the full picture is captured.

  `opts` (optional) is threaded to `rf.story.error/exception-record` —
  callers draining a pipeline-exception trace event pass `:operation`
  / `:failing-id` so the originating component attribution survives
  onto the record."
  ([variant-id phase event err] (record-error! variant-id phase event err nil))
  ([variant-id phase event err opts]
   (let [record (rf.story.error/exception-record variant-id phase event err opts)]
     (try
       (rf/dispatch-sync [::append-assertion record] {:frame variant-id})
       (catch #?(:clj Throwable :cljs :default) dispatch-err
         ;; The frame may already be torn down (run-variant tearing down
         ;; under error, or a hot-reload race destroying the frame mid-
         ;; capture). Emit a debug trace breadcrumb so the lossy path is
         ;; visible in tooling; never re-throw — the caller is already
         ;; in error-recording flow.
         (rf.trace/emit!
           :debug ::append-assertion-failed
           {:frame      variant-id
            :phase      phase
            :event      event
            :error-msg  #?(:clj  (.getMessage ^Throwable dispatch-err)
                           :cljs (str dispatch-err))})))
     record)))

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
  variant frame's `:rf.story/assertions` accumulator, and `::apply-db-seed`
  for the `:db-seed` fidelity rung. Idempotent.

  Mirrors the `install-canonical-<X>!` shape used by every sibling
  installer in `canonical/canonical-installers`."
  []
  (when rf.story.config/enabled?
    (rf/reg-event
      ::append-assertion
      (fn [{:keys [db]} [_ record]]
        {:db (update db :rf.story/assertions (fnil conj []) record)}))
    ;; The `:db-seed` direct app-db seed. Merges the resolved
    ;; `{path → value}` seed map into the frame's app-db via `assoc-in`
    ;; (a top-level keyword path is normalised to a 1-element vector), the
    ;; SAME merge `::apply-app-db-patch` (the `:frame-setup` decorator
    ;; seam) uses. A MERGE (not a wholesale replace) so framework-reserved
    ;; slots the frame already carries (e.g. `:rf.story/assertions`)
    ;; survive — the seed establishes the author's state on top, then
    ;; phase-2 `:setup` events run over it (the fidelity ladder composes:
    ;; `:db-seed` then `:real-setup`).
    (rf/reg-event
      ::apply-db-seed
      (fn [{:keys [db]} [_ seed]]
        {:db (reduce-kv
               (fn [d path v]
                 (assoc-in d (if (vector? path) path [path]) v))
               db
               seed)}))))

;; ---- assertions read -----------------------------------------------------

(defn read-assertions
  "Return the assertions vector for `variant-id`'s frame, or `[]`."
  [variant-id]
  (or (:rf.story/assertions (rf/app-db-value variant-id)) []))

;; ---- run-variant ---------------------------------------------------------
;;
;; `run-variant` is the engine's hot path. Per `002-Runtime.md` §Four-phase lifecycle with `:loaders-complete-when` it drives the
;; four-phase lifecycle (phase-0 setup → phase-1 loaders → phase-2 events →
;; phase-4 play). Rendering is owned by the UI shell and `render-variant`,
;; outside this headless orchestration. To keep the
;; orchestrator readable each phase lives in its own named fn. The
;; named-phase decomposition also gives tests a finer entry surface: a
;; primed ctx can be fed into a single phase fn in isolation.

(defn- plan-checks
  "Resolve a normalized PLAN's `[:expect :checks]` ids into the
  `{check-id [assertion-atom …]}` map the unified result groups by.
  Expands each check id through the Story side-table
  `:check` kind (`rf.story.plan/expand-checks`). Sourcing the check ids from the
  plan (rather than re-reading the variant body) means a REGISTERED
  variant and an INLINE plan (unregistered) resolve their
  checks identically: the compiler already merged inherited + composed
  check ids into `[:expect :checks]`. Tolerant — any resolution failure
  yields an empty map (checks then group nothing; the run-level
  aggregation still sees ungrouped records)."
  [plan]
  (try
    (rf.story.plan/expand-checks (get-in plan [:expect :checks]))
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
  `filterv` over its output. Feeds the tape-evaluated
  expectation matchers (`:rf.assert/schema-error`, `:rf.assert/caused` /
  `:rf.assert/no-cascade-rerender`) that — unlike a
  `reg-event`-backed assertion — carry NO handler and so must be
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
  normalized PLAN (spec/017 §Schema rule). These declare the
  EXPECTED schema violations the result boundary exactly-consumes against
  the projected tape evidence — so a missing/different violation fails the
  run, an exactly-expected violation passes.

  `:rf.assert/schema-error` is NOT dispatched into the frame (it has no
  reg-event handler — it is tape-evaluated), so collecting the DECLARED
  atoms here is the single path that feeds the consumption matcher.

  Defined as a FILTER over the shared `plan-assertion-atoms` collector —
  the same pattern `plan-causal-expectations` uses. The
  collector already wraps the three positions in a tolerant try/catch, so
  `filterv` over its `(vec (concat …))` output is identical to filtering
  the bare `concat` (filterv ignores the extra vec)."
  [plan executed-script]
  (filterv rf.story.assertions/schema-error? (plan-assertion-atoms plan executed-script)))

(defn- plan-causal-expectations
  "Collect every declared `:rf.assert/caused` / `:rf.assert/no-cascade-
  rerender` atom for a normalized PLAN (spec/017 §Causal and
  cascade assertions). These are tape-evaluated against the projected
  `:reactive-counts` `:by-cause` projection (NOT dispatched into the frame,
  NOT a parallel accumulator), so — like `:rf.assert/schema-error` —
  collecting the DECLARED atoms here is the single path that feeds the
  result boundary's causal matcher (`rf.story.result/match-causal-expectations`)."
  [plan executed-script]
  (filterv rf.story.assertions/causal? (plan-assertion-atoms plan executed-script)))

(defn- selection-refusal
  "The `rf.story.requirements/select-runner` refusal as a one-element `:unmet` vector
  when no runner could be chosen (`:auto` and NO concrete runner satisfies
  the plan's required tokens — `:reason :no-runner-satisfies`), or `[]` when
  a runner WAS chosen (`{:status :ok …}`). Pure data → data."
  [runner-selection]
  (if (= :cannot-run (:status runner-selection))
    [runner-selection]
    []))

(defn- requirements-unmet
  "The per-requirement `:cannot-run` refusals derived from the requirements
  registry for a run. Pure data → data. Three sources, unioned:

  1. the `select-runner` refusal, when `:auto` selection found NO capable
     runner (`selection-refusal`);
  2. the FIXED-RUNNER per-unit refusals — every terminal/in-script ASSERTION
     (`rf.story.requirements/unmet-assertions`) and every setup/script STEP
     (`rf.story.requirements/unmet-steps`) whose required capability tokens the chosen
     runner lacks (a `:pixels` `visual-snapshot` / a `:dom` `[:click …]`
     under `:headless`);
  3. the POST-RUN, fail-closed evidence-slot validation
     (`rf.story.requirements/validate-run-evidence`) — an assertion that REQUIRED a
     proof but whose evidence SLOT the tape never produced fails closed to
     `:cannot-run` (the proof was promised, not delivered).

  Under `:auto` selection the chosen runner satisfies every requirement, so
  (2) is empty (the cheapest CAPABLE runner was chosen); under fixed
  `:headless` it surfaces the per-requirement gaps. Both the fixed and auto
  policies feed the SAME `:unmet` slot `rf.story.result/run-result` folds into the
  verdict (a run whose only unmet expectations are `:cannot-run` is itself
  `:cannot-run`, never a vacuous pass — spec/017 §`:cannot-run`)."
  [plan runner-selection evidence-slots executed-script]
  (let [runner    (:runner runner-selection)
        ;; All declared assertion atoms (terminal + check + in-script) — the
        ;; SAME collector the schema/causal expectation filters read.
        atoms     (plan-assertion-atoms plan executed-script)
        ;; setup + script steps — the executed script the auto-plays ran,
        ;; plus the plan's setup program.
        steps     (into (vec (get-in plan [:world :setup] []))
                        (or executed-script []))
        ;; Post-run evidence validation reads the SAME projected evidence the
        ;; result slots derive from (no second derivation).
        evidence  (or evidence-slots {})
        post-run  (when runner
                    (:missing-evidence
                      (rf.story.requirements/validate-run-evidence atoms evidence runner)))]
    (vec (concat (selection-refusal runner-selection)
                 (when runner (rf.story.requirements/unmet-assertions runner atoms))
                 (when runner (rf.story.requirements/unmet-steps runner steps))
                 (or post-run [])))))

(defn- record-result-map
  "Build the unified run-result returned by `run-variant` (spec/017
  §Run result + §Unified run result). Gathers whatever the
  runtime accumulated against the variant's frame and assembles the ONE
  shared shape via `rf.story.result/run-result`:

  - the evidential slots (`:status` floor, `:epoch-tape`,
    `:schema-violations`, `:warnings`, `:effects`, `:sub-runs`,
    `:renders`, `:narrative`) are PROJECTED from the retained epoch tape
    (`.4`'s `rf.story.play.evidence/project-evidence`, via `rf.story.result/run-result`) — NOT a
    parallel accumulator;
  - the judgement slots (`:assertions` / `:checks`) fold the
    `:rf.story/assertions` accumulator (the ONE non-tape input) into
    unified records + groups them under their check ids;
  - the top-level `:status` is the unified verdict.

  The `:lifecycle` / `:frame` / `:snapshot` / `:decorators` /
  `:effective-args` slots are PRESERVED (API stable, spec/017 §1a —
  `:status` sits alongside `:lifecycle`), so consumers can read those slots
  and the unified shape from the one result.

  Checks + schema-expectations are sourced from the compiled `:plan`,
  so a registered variant and an INLINE plan assemble the
  same result through one path."
  [{:keys [variant-id decorator-stack effective-args snapshot executed-script
           executed-play-keys plan runner-selection epoch-baseline]} start-ms]
  (let [app-db   (rf/app-db-value variant-id)
        ;; Read the epoch tape through the
        ;; late-bound `re-frame.core/epoch-history` facade (mirroring
        ;; `re-frame.story.artifact`'s replay-path read), NOT a hard
        ;; `[re-frame.epoch …]` require. Story's published surface (and
        ;; its downstream consumer `tools/story-mcp`) carry no epoch dep on
        ;; the production classpath; the facade degrades to `[]` there
        ;; (per `re-frame.core/epoch-history`'s contract) while the
        ;; `:test`-alias epoch dep makes it the live tape under the gate.
        ;;
        ;; Same-id reruns reset app-db/runtime-db in place so mounted view
        ;; reactions survive, but the epoch ring remains frame-owned across
        ;; runs. Phase 0 therefore captures the last committed epoch id and
        ;; this projection keeps only newer records. Comparing epoch identity,
        ;; rather than dropping a count, remains correct when the bounded ring
        ;; evicts records from its front. Inline runs use a fresh frame and a
        ;; zero baseline, so the same filter covers both paths.
        ;; The FULL retained ring (oldest-first, before the baseline filter).
        ;; rf2-4u5zl4: reading it whole is what lets the truncation signal see
        ;; whether the ring evicted the baseline record itself — the
        ;; baseline-filtered `tape` alone cannot witness its own truncation.
        full-ring (rf/epoch-history variant-id)
        ;; The per-run epoch-tape truncation signal (rf2-4u5zl4): true when
        ;; the bounded ring evicted the run's earliest epochs (the baseline is
        ;; no longer covered). Threaded to `rf.story.result/run-result` so an in-bounds
        ;; causal `:pass` against a finite upper bound resolves `:cannot-run`
        ;; rather than a truncation false-green.
        truncated? (rf.story.play.evidence/run-tape-truncated? full-ring epoch-baseline)
        tape     (vec (filter #(> (or (:epoch-id %) 0) (or epoch-baseline 0))
                              full-ring))
        ;; The runner-recorded per-dispatch-step settle boundaries light
        ;; up the EXACT narrative attribution
        ;; (`rf.story.play.evidence/spans-from-stamps`). The
        ;; stamp is a `:rf.story/*` key the determinism projection strips, so
        ;; the run-hash is unaffected (the `:epoch-tape` slot stays raw).
        ;;
        ;; Boundary stamps are genuine epoch ids, not ring positions, so
        ;; attribution is independent of ring eviction. Step boundaries are
        ;; keyed by `[frame-id play-key]`; read each auto-play's slot in the
        ;; same order its script was concatenated so concurrent play keys on
        ;; one frame cannot collide. A run that failed before play contributes
        ;; no keys and therefore no attribution boundaries.
        attribution (into []
                          (mapcat #(rf.story.play.runner-events/settle-boundaries variant-id %))
                          (or executed-play-keys []))
        ;; Project the tape ONCE here so the post-run evidence
        ;; validation (`requirements-unmet` → `validate-run-evidence`) reads
        ;; the SAME projected slots `rf.story.result/run-result` derives the result
        ;; slots from. One tape, one projection — a duplicate accumulator
        ;; cannot report a proof present while the tape's slot is empty.
        evidence (rf.story.play.evidence/project-evidence tape {:script      executed-script
                                                  :attribution attribution})
        ;; The run-state's `:cannot-run` refusals (a no-DOM
        ;; `[:assert-dom …]` skip, a boundary `:cannot-run?`): steps that
        ;; recorded NO `:rf.story/assertions` entry. Without this fold the
        ;; unified result would aggregate to `:pass` (vacuous green) while the
        ;; run-state read `:cannot-run`. The facade degrades to nil run-state
        ;; (empty refusals) on a host with no runner-events run-state.
        run-state-unmet (rf.story.play.runner/run-state-refusals
                          (rf.story.play.runner-events/current-state variant-id))
        ;; The requirements-registry refusals: the `:auto`
        ;; no-capable-runner refusal, the fixed-runner per-unit capability
        ;; gaps (`unmet-assertions` / `unmet-steps`), and the post-run
        ;; fail-closed evidence-slot validation (`validate-run-evidence`).
        ;; Wired through `runner-selection` (chosen in `prepare-context`).
        req-unmet (requirements-unmet plan runner-selection evidence executed-script)
        ;; The unified `:unmet` slot folds BOTH refusal sources (spec/017
        ;; §Unified run result — "a run whose only unmet expectations are
        ;; :cannot-run is itself :cannot-run").
        unmet    (into (vec run-state-unmet) req-unmet)
        unified  (rf.story.result/run-result
                   {:variant/id          variant-id
                    :epoch-tape          tape
                    ;; The runner-recorded per-dispatch-step settle
                    ;; boundaries (above) so `run-result`'s ONE evidence
                    ;; projection attributes the `:narrative` EXACTLY.
                    :attribution         attribution
                    :assertions          (or (:rf.story/assertions app-db) [])
                    :script              executed-script
                    :check->atoms        (plan-checks plan)
                    ;; The CHOSEN runner + the plan's required
                    ;; capability set, surfaced on the result so Test mode /
                    ;; CI / MCP read which runner ran + what it needed.
                    :runner              (:runner runner-selection)
                    :required-runner     (get plan :required-runner #{})
                    ;; The declared `:rf.assert/schema-error`
                    ;; expectations, EXACT-consumed against the projected
                    ;; tape violations (§Schema rule). Tape-evaluated, NOT
                    ;; dispatched — collected from the plan, not the
                    ;; `:rf.story/assertions` accumulator.
                    :schema-expectations (plan-schema-expectations
                                           plan executed-script)
                    ;; The declared causal / cascade
                    ;; expectations (`:rf.assert/caused` /
                    ;; `:rf.assert/no-cascade-rerender`), tape-evaluated
                    ;; against the projected `:reactive-counts` `:by-cause`
                    ;; projection (§Causal and cascade assertions). Like the
                    ;; schema-error expectations they are tape-evaluated, NOT
                    ;; dispatched — collected from the plan.
                    :causal-expectations (plan-causal-expectations
                                           plan executed-script)
                    ;; rf2-4u5zl4: whether the bounded ring truncated the run
                    ;; tape (evicted the earliest run epochs). An in-bounds
                    ;; causal `:pass` against a finite upper bound resolves
                    ;; `:cannot-run` under truncation — the dropped effects
                    ;; could exceed the bound (a truncation false-green).
                    :epoch-truncated?    truncated?
                    ;; The per-requirement `:cannot-run` refusals
                    ;; the runner could not even attempt (above). Folded into
                    ;; the verdict + surfaced on `:cannot-run` by
                    ;; `rf.story.result/run-result`.
                    :unmet               unmet
                    :app-db              (or app-db {})
                    :elapsed-ms          (- (rf.interop/now-ms) start-ms)})]
    (cond-> (merge unified
                   {:frame           variant-id
                    :snapshot        snapshot
                    :decorators      decorator-stack
                    :effective-args  effective-args
                    :lifecycle       (rf.story.loaders/current-state variant-id)})
      ;; EP-0023 §Stories — surface the behaviour-variant
      ;; IMAGE ids the run resolved behaviour against, so Test mode / MCP /
      ;; Xray can show WHICH behaviour set ran. Present only for a behaviour
      ;; variant (one that declared `:images`); omitted for an ordinary state
      ;; variant resolving against the shared default registrar.
      (seq (rf.story.frames/variant-image-ids variant-id))
      (assoc :images (rf.story.frames/variant-image-ids variant-id)))))

(defn- resolve-runner-selection
  "Normalize the run `opts` and SELECT the runner for `plan`. Returns the
  `rf.story.requirements/select-runner` outcome — either `{:status :ok :runner …
  :unmet …}` (a runner was chosen; under `:auto` it satisfies all, under
  fixed `:headless` its `:unmet` may be non-empty) or a
  `rf.story.requirements/requirement-refusal` (`:auto` and NO runner satisfies, e.g.
  a `:reactive-counts` requirement → `:no-runner-satisfies`).

  `normalize-run-opts` collapses `:runner` / `:escalate` into the canonical
  `{:mode :fixed|:auto :runner …}` shape (the ONE normalization the spec
  pins, §Run / `is` opts); `select-runner` then chooses the cheapest capable
  runner under `:auto`, or runs the whole plan single-pass under the fixed
  runner. The plan's `:required-runner` slot is the union capability set the
  compiler already filled through this SAME registry (`rf.story.plan/compute-required-
  runner` → `rf.story.requirements/plan-required-runner`), so selection reads the ONE
  source of truth, never a re-derivation. Pure aside from nothing — `opts`
  and `plan` in, the selection map out."
  [plan opts]
  (let [norm-opts (rf.story.requirements/normalize-run-opts opts)
        required  (get plan :required-runner #{})]
    (rf.story.requirements/select-runner required norm-opts)))

(defn- prepare-context
  "Resolve the per-run inputs that every phase needs: the NORMALIZED
  PLAN, the decorator stack, the effective args, and the identity
  snapshot. Returns a map; pure aside from the registrar reads.

  §B8 — the run-variant path routes through the variant-plan compiler
  (`re-frame.story.plan`). `prepare-context` compiles the normalized plan
  ONCE and threads it down the phase pipeline (spec/017 §Variant plan —
  every registered variant MUST be normalized before execution). Phase 2
  consumes the plan's `[:world :setup]` (the tagged setup steps); phase 4
  consumes its `[:world :scripts]` (the named scripts — `:plays`
  preserved). A plan-construction failure (an unknown variant, a missing
  `[:arg …]`, a misplaced `[:assert …]` in setup, …) throws here; the
  orchestrator's try/catch (`handle-run-error!`) projects it onto the run
  result.

  Phase-4 drives the rich-DSL step executor through `rf.story.play.runner-events/run!`."
  [variant-id variant-body opts]
  (let [;; Compile the plan WITH the per-run arg layers
        ;; (`:active-modes` / `:cell-overrides`) so the substituted
        ;; setup/script/db-seed/network/sub-overrides, `[:world :effective-args]`,
        ;; and the plan hash all use the SAME effective args the result
        ;; reports. The plan and the result thus run the SAME scenario the
        ;; cell override or active mode describes.
        plan (rf.story.plan/variant-plan variant-id
                                {:run-args (rf.story.args/run-arg-layers variant-id opts)})]
    {:variant-id       variant-id
     :variant-body     variant-body
     :plan             plan
     ;; Select the runner for this plan up front (the cheapest
     ;; capable runner under :auto, or the fixed runner the caller asked for).
     ;; Threaded down so `record-result-map` can surface the chosen runner +
     ;; fold UNMET requirements into the unified result's :cannot-run.
     :runner-selection (resolve-runner-selection plan opts)
     ;; Resolve the decorator stack from the ALREADY-compiled
     ;; plan's `[:world :decorators]` refs (the twin of the inline path's
     ;; `prepare-inline-context`), NOT via `rf.story.decorators/resolve-decorators`
     ;; which recompiles the plan WITHOUT `:run-args`. Reusing this plan
     ;; (a) avoids a redundant second compile, and (b) is correct now that a
     ;; `[:arg key]` may resolve ONLY through a run-opts layer (an active
     ;; mode / cell override) — a recompile without `:run-args` would throw
     ;; `:rf.error/story-missing-arg` substituting that script placeholder.
     ;; v1 modes carry no decorators (`001-Authoring.md` §Registration macros: modes are :args only),
     ;; so `:active-modes` does not perturb the decorator refs.
     :decorator-stack  (rf.story.decorators/resolve-decorator-refs
                         (get-in plan [:world :decorators] []))
     ;; Report the SAME effective args the plan was compiled
     ;; with (the plan is the single source of truth). The plan folds the
     ;; ambient + run layers around its `:extends`-aware variant layer, so
     ;; `[:world :effective-args]` equals `rf.story.args/resolve-args` for a variant
     ;; with no `:extends`, and is the strictly-more-correct extends-aware
     ;; value when the variant inherits args.
     :effective-args   (get-in plan [:world :effective-args] {})
     :snapshot         (rf.story.identity/snapshot-identity variant-id opts)}))

(defn- ensure-fresh-frame!
  "Enforce a FRESH-RUN boundary for `variant-id`. Per
  spec/002-Runtime §`run-variant` step 1 — `run-variant` allocates OR
  resets the variant frame; it never reuses an existing one.

  `rf.story.frames/allocate!` against an existing frame goes through
  `make-frame`'s surgical-update path, which PRESERVES the prior app-db
  and sub-cache (rf.story.frames/allocate! docstring). For a Story run that is
  the wrong shape: a second `run-variant` on the same id would inherit
  the first run's app-db, and — worse — `run-loaders!` short-circuits on
  an already-`:ready` frame, so a loader variant would SKIP its loaders
  on the second run (a stateful, order-dependent false result).

  When a frame already exists under `variant-id` we reset it to fresh
  state IN PLACE via `rf.story.frames/reset-state!` — overwriting both frame-state
  partitions with `{}` through the one physical container so the frame's
  IDENTITY, sub-cache, and projection reactions all survive. The in-place
  reset matters because the canvas mounts the variant view (establishing
  its `@(subscribe …)` reactions on this frame's sub-cache) BEFORE
  `:component-did-mount` → `run-variant` runs — so a `destroy!` would
  orphan every live reaction and a subsequent `:counter/set 42` would
  never re-render the DOM. The in-place reset gives a fresh app-db AND
  drops the lifecycle machine snapshot (runtime-db → `{}`) so loaders
  re-run — without breaking the live reactions. When no frame exists this
  is a no-op and the subsequent `allocate!` builds the clean frame.
  Determinism by default; an intentionally-persistent mode would be an
  explicit opt, not the default."
  [variant-id]
  (when (contains? (set (rf/frame-ids)) variant-id)
    (rf.story.frames/reset-state! variant-id)))

(defn- run-phase-0!
  "Phase 0: enforce a fresh-run boundary, allocate the variant frame
  with its decorator stack, then install the play-runner's privacy
  egress listener.

  `ensure-fresh-frame!` resets any pre-existing frame under `variant-id`
  to fresh state IN PLACE BEFORE allocation so two consecutive
  `run-variant` calls on the same id produce the same fresh app-db and
  loader variants rerun their loaders on the second call
  (spec/002 §`run-variant` step 1). The in-place reset
  preserves frame identity + sub-cache so a live mounted view's
  reactions survive the re-run — a `destroy!` here would orphan them.

  The listener install order matters: it must be in place
  BEFORE phase-1 loaders fire so the privacy gate suppresses sensitive
  loader-phase events and loader-phase handler-exceptions are captured.
  We clear the per-frame `pending-exceptions` slot so the listener has a
  clean slot; `execute-play!` clears it again at play start.

  The `:loaders-complete-when` vector form reads the epoch
  tape (`rf.story.assertions/dispatched-events`, the SSOT) rather than a side-table
  accumulator, so there is no accumulator to seed here.

  Because an in-place reset preserves the frame-owned epoch ring, phase 0
  records the current last-committed `:epoch-id` as `:epoch-baseline`.
  `record-result-map` uses that identity to project evidence from this run
  only.

  Classification comes from the already-compiled plan's
  `[:world :sensitive]` / `[:world :large]` slots, after `:extends`
  resolution. Registered and inline plans therefore apply inherited
  classification through the same allocation boundary."
  [{:keys [variant-id decorator-stack plan] :as ctx}]
  (ensure-fresh-frame! variant-id)
  (rf.story.frames/allocate! variant-id decorator-stack
                     (select-keys (:world plan) [:sensitive :large]))
  (swap! rf.story.play/pending-exceptions assoc variant-id [])
  (rf.story.play/install-trace-listener! variant-id)
  (assoc ctx :epoch-baseline (rf.story.play.runner-events/last-epoch-id variant-id)))

(defn- db-seed-violations
  "Validate the seeded `db` against the frame's REGISTERED app-db schemas
  (spec/017 §Setup — direct seeding bypasses event / cofx
  validation but MUST validate the affected app-db schema). Returns a
  (possibly empty) vector of `{:path :value :explain}` violations.

  REUSES the existing schemas late-bind seam — the SAME
  `:schemas/validate-with-registered-fn` / `:schemas/explain-with-registered-fn`
  the `:sub-return` path + the `:sub-override` fold-in reach, and
  `:schemas/frame-schema-entries` to enumerate the frame's registered
  `{path → schema-meta}`. No new validation mechanism, and no hard dep on
  the schemas artefact: when it is absent every hook resolves nil and the
  walk SOFT-PASSES (the host-free floor — a Story-only build pays nothing).
  When the schemas artefact IS present its registered validator (Malli on
  the CLJS reference) is the same one the framework's dev-mode hot path
  uses, so the seed is held to exactly the contract a real handler commit
  would be (spec/010 §Production builds)."
  [frame-id db]
  (let [entries-fn  (rf.late-bind/get-fn :schemas/frame-schema-entries)
        validate-fn (rf.late-bind/get-fn :schemas/validate-with-registered-fn)
        explain-fn  (rf.late-bind/get-fn :schemas/explain-with-registered-fn)]
    (if (or (nil? entries-fn) (nil? validate-fn))
      ;; No schemas artefact / no validator → soft-pass (nothing to check).
      []
      (into []
            (keep (fn [[reg-path schema-meta]]
                    (let [schema    (:schema schema-meta)
                          reg-slice (get-in db reg-path)]
                      (when (and (some? schema)
                                 (not (validate-fn schema reg-slice)))
                        {:path    reg-path
                         :value   reg-slice
                         :schema  schema
                         :explain (when explain-fn (explain-fn schema reg-slice))}))))
            (entries-fn frame-id)))))

(defn- run-db-seed!
  "Phase 0.5: seed the variant frame's app-db from the plan's
  `[:world :db-seed]` slot, then SCHEMA-VALIDATE the seeded app-db — BEFORE
  any loaders (phase 1) or setup events (phase 2) run. The MIDDLE fidelity
  rung: a direct app-db state seed merged into the frame ahead of the
  script (spec/017 §Setup + §View-state subscription overrides — the
  fidelity ladder).

  No-op when the variant carries no seed (the common case carries no
  `[:world :db-seed]` slot, so the merge dispatch + the schema walk are
  skipped entirely).

  Per spec/017 §Setup direct seeding bypasses event / cofx validation but
  MUST validate the affected app-db schema. A seed that violates a
  registered schema THROWS a structured `:rf.error/story-db-seed-invalid`
  ex-info carrying every `{:path :value :explain}` violation; the
  orchestrator's `handle-run-error!` projects it onto the run result as a
  failed `:rf.error/story-db-seed-invalid` assertion (the run never reaches
  the script — a malformed precondition is not a thing to assert against)."
  [{:keys [variant-id plan] :as ctx}]
  (when-let [seed (not-empty (get-in plan [:world :db-seed]))]
    (rf/dispatch-sync [::apply-db-seed seed] {:frame variant-id})
    (let [violations (db-seed-violations variant-id (rf/app-db-value variant-id))]
      (when (seq violations)
        (rf.error/throw-error!
          :rf.error/story-db-seed-invalid
          'rf.story/run-variant
          (str "re-frame2-story: variant " variant-id
               " — :db-seed does not satisfy the registered app-db "
               "schema(s) at path(s) "
               (pr-str (mapv :path violations))
               ". A direct app-db seed bypasses event/cofx validation "
               "but MUST validate the affected app-db schema "
               "(spec/017 §Setup). Fix the :db-seed value(s) to satisfy "
               "the registered schema(s).")
          {:recovery :fix-the-db-seed-to-satisfy-the-schema
           :extra    {:variant/id variant-id
                      :violations violations}}))))
  ctx)

(defn- run-phase-1!
  "Phase 1: drive loaders to completion. Thin wrapper that returns
  `ctx` so the orchestrator stays a clean threaded pipeline.

  The loader body defaults to the registered variant body
  (`run-loaders!` 1-arity); an inline run threads `:loader-body` on the
  ctx (the plan's `:world` loader slots), so an inline plan's loaders run
  through the SAME phase fn without reading the side-table."
  [{:keys [variant-id loader-body] :as ctx}]
  (assoc ctx :loaders-complete?
         (if (contains? ctx :loader-body)
           (run-loaders! variant-id loader-body)
           (run-loaders! variant-id))))

(defn- run-phase-2!
  "Phase 2: dispatch the plan's `[:world :setup]`, then mark events
  complete on the lifecycle machine. §B8 — setup is sourced from the
  normalized plan."
  [{:keys [variant-id loaders-complete? plan] :as ctx}]
  (when loaders-complete?
    (run-events! variant-id plan)
    (rf.story.loaders/finish-events! variant-id))
  ctx)

(defn- settle-terminal-assertions!
  "Evaluate the plan's terminal handler-backed `:assertions` against the
  FINAL settled state (terminal `:assertions` AUTO-RUN). Called AFTER the
  script phase settles (after the auto-plays
  complete, or — for a variant with no script — after phase-2 setup), so the
  terminal atoms read 'check the FINAL settled state' while an in-script
  `[:assert …]` checkpoint stays 'check here, now'.

  Routes through the SAME executor the in-script checkpoints use
  (`rf.story.play.runner-events/run-terminal-assertions!` → `exec-assert!`), so the
  verdict lands on `:rf.story/assertions` via the ONE recording path —
  `record-result-map` / `rf.story.result/run-result` already folds that accumulator
  into the unified `:pass` / `:fail`. The terminal atoms are the plan's
  `[:expect :assertions]` (the SAME slot `plan-assertion-atoms` reads), so a
  REGISTERED variant and an INLINE plan both route here.

  The tape-evaluated kinds (`:rf.assert/schema-error`, the causal / cascade
  family, the browser-tier oracle family) are NOT double-processed: they
  carry no reg-event handler, so `exec-assert!` records a no-op step-skip
  for them and never dispatches — `rf.story.result/run-result` already evaluates them
  against the epoch tape from the plan's `:schema-expectations` /
  `:causal-expectations` (collected by `plan-schema-expectations` /
  `plan-causal-expectations`). Only the handler-backed terminal atoms are
  evaluated here. Tolerant — any failure is
  swallowed (record-don't-throw)."
  [variant-id plan]
  (try
    (rf.story.play.runner-events/run-terminal-assertions!
      variant-id (get-in plan [:expect :assertions]))
    (catch #?(:clj Throwable :cljs :default) _ nil))
  nil)

(defn- run-phase-4!
  "Phase 4: run the play-script. Returns `[ctx' play-promise]` — `ctx'`
  carries `:executed-script` (the folded steps the auto-plays ran, for the
  unified result's two-level narrative) and `:executed-play-keys` (the
  auto-plays' `:name`s, in execution order), and the orchestrator chains
  `then` on the promise to know when
  to build the result.

  Drives the rich-DSL `:script` runner via
  `rf.story.play.runner-events/run!`. Variants without `:script` / `:plays`
  resolve to an empty script and the promise resolves immediately. Author
  event sequences by wrapping each entry in `[:dispatch-sync <event-vec>]`
  inside a `:script` body.

  The resolved play scripts are FOLDED (shipping
  `:assert-db` / `:assert-dom` rewritten to the canonical `[:assert …]`
  checkpoint), so the executed-script narrative carries the one
  assertion atom.

  The play set comes from the normalized plan's
  `[:world :scripts]` (the named scripts the compiler's `normalize-scripts`
  produces — `:plays` preserved as named scripts, spec/017 §Public
  vocabulary), NOT a second `rf.story.play.runner-events/variant-plays` read of the
  registered body. The compiler already coerces + folds every script, so
  the plan's `:scripts` carry the `{:script :auto-run? :name}` shape the
  runner drives directly.

  Rendering is owned by the UI shell and `render-variant`, not this
  orchestrator.

  `:force-play?` on the ctx (rf2-j538f7.34) runs the auto-plays EVEN WHEN
  `loaders-complete?` is false — the loader-incomplete-but-drained case where
  the loader events ran (e.g. `:story.counter-matrix/loader-never-completes`
  seeds `:count` to 13) but the `:loaders-complete-when` predicate reported
  not-ready, so the lifecycle parks at `:loading` yet the canvas still renders
  the user view (the recorded loader-incomplete assertion turns the skeleton
  off). Only the interactive shell's `resume-run!` sets it: in the browser the
  view is mounted and the auto-play must run against it and publish a play-
  runner run-state — the exact behaviour the pre-split browser `auto-run!` had,
  which the Story/Xray play-scripts browser gate reads via
  `rf.story.play.runner-events/current-state`. The headless `run-variant` / inline paths
  never set it, so their contract (loader-incomplete ⇒ events + play skipped,
  lifecycle parks at `:loading`) is unchanged."
  [{:keys [variant-id loaders-complete? plan force-play?] :as ctx}]
  (if-not (or loaders-complete? force-play?)
    [ctx (rf.story.async/resolved (read-assertions variant-id))]
    (let [plays      (get-in plan [:world :scripts] [])
          auto-plays (rf.story.play.runner/auto-runnable-plays plays)
          ;; The folded steps the auto-plays ran, concatenated in order —
          ;; the script the unified result's two-level narrative spans.
          executed   (vec (mapcat :script auto-plays))
          ;; The auto-plays' own play-keys, in the SAME order as `executed`
          ;; concatenates their scripts. `record-result-map` reads each
          ;; play-key's OWN settle-boundaries slot and concatenates them in
          ;; this order to reconstruct the full per-dispatch-step attribution
          ;; vector. `step-boundaries` is keyed by `[frame-id play-key]`,
          ;; not `frame-id` alone.
          play-keys  (mapv :name auto-plays)
          ctx'       (assoc ctx
                            :executed-script executed
                            :executed-play-keys play-keys)
          ;; Reset the per-dispatch-step settle boundaries for EVERY
          ;; play-key this run is about to drive, before the auto-plays run,
          ;; so the narrative attribution windows onto THIS run's epoch
          ;; tape. The boundaries snapshot the last-committed `:epoch-id` at
          ;; each dispatch step (setup-phase epochs carry an id at or before
          ;; the first boundary → leading nil span), the SAME identity
          ;; `record-result-map` compares against when it filters the tape
          ;; when filtering the tape. Clearing per-play-key (rather than the
          ;; whole frame) means a concurrent run for a different play-key on this
          ;; frame can never be wiped by — or wipe — this reset.
          _          (doseq [pk play-keys]
                       (rf.story.play.runner-events/clear-step-boundaries! variant-id pk))]
      (if (empty? auto-plays)
        ;; No script ran, but the world settled (phase-2 setup committed).
        ;; The terminal `:assertions` check the FINAL settled state, so they
        ;; still auto-run here.
        [ctx' (rf.story.async/resolved (do (settle-terminal-assertions! variant-id plan)
                                  (read-assertions variant-id)))]
        [ctx'
         (rf.story.async/promise
           (fn [resolve]
             ;; Run each auto-play sequentially. The `:rf.assert/*` events
             ;; the folded `[:assert …]` checkpoints dispatch record into
             ;; `:rf.story/assertions` on the frame via the standard
             ;; assertion handlers. Once every auto-play has finished, the
             ;; terminal `:assertions` are evaluated against the FINAL
             ;; settled state and the orchestrator builds the
             ;; result map from the frame's accumulated assertions.
             (letfn [(step! [remaining]
                       (if (empty? remaining)
                         (do (settle-terminal-assertions! variant-id plan)
                             (resolve (read-assertions variant-id)))
                         (let [spec (first remaining)]
                           ;; The orchestrator cleared the settle
                           ;; boundaries ONCE above (before the loop). Drive
                           ;; every auto-play with `:clear-boundaries? false`
                           ;; so the per-play absolute boundaries ACCUMULATE
                           ;; across the concatenated `:executed-script` rather
                           ;; than each `run!` wiping the prior play's — the
                           ;; multi-play attribution-boundary fix.
                           (rf.story.play.runner-events/run! variant-id (:name spec) spec
                                               (fn [_state]
                                                 (step! (rest remaining)))
                                               {:clear-boundaries? false}))))]
               (step! auto-plays))))]))))

(defn- finalise-run!
  "Build and deliver the result map once phase 4's promise settles.
  This chain is load-bearing: `execute-play!` resolves the promise to the
  assertions vector, and we want the result map to read the post-play
  app-db."
  [resolve play-promise ctx start-ms]
  (-> play-promise
      (rf.story.async/then
        (fn [_]
          (resolve (record-result-map ctx start-ms))
          nil))))

(defn- plan-construction-error?
  "True iff `e` is a plan-construction failure — an `ex-info` `re-frame.
  story.plan/fail!` threw. `fail!` stamps `:where 'rf.story/variant-plan`
  on every `:rf.error/story-*` failure, so that marker (NOT the bare
  presence of `:rf.error/id`) is the discriminator. §B8 —
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
  `make-frame` → `make-state-container` when the host installed no
  adapter (the JVM-standalone story-mcp server). Those land after the
  frame exists at `:pre-mount`, so they must take the frame-bound
  record/transition branch, NOT `plan-error-result`."
  [e]
  (= 'rf.story/variant-plan (:where (ex-data e))))

(defn- exception-message [e]
  #?(:clj  (.getMessage ^Throwable e)
     :cljs (str e)))

(defn- plan-error-result
  "The error result returned when `re-frame.story.plan/variant-plan`
  FAILS to compile the variant (§B8). The frame is not
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
                       :error      (rf.story.error/throwable->error-map e)}]))

(defn- db-seed-error?
  "True iff `e` is the structured `:db-seed` schema-validation failure
  `run-db-seed!` throws. Discriminated on `:rf.error/id` —
  the seed throw stamps `:rf.error/story-db-seed-invalid` with a
  `:violations` vector. The frame IS allocated when it throws (the seed
  ran against it), so it takes the frame-bound branch, but we record it as
  its OWN structured assertion (id `:rf.error/story-db-seed-invalid` +
  the path/value/explain violations) rather than the opaque
  `:rf.error/exception` shape, so tooling routes on the seed-failure id."
  [e]
  (= :rf.error/story-db-seed-invalid (:rf.error/id (ex-data e))))

(defn- record-seed-error!
  "Append the structured `:rf.error/story-db-seed-invalid` assertion to the
  variant frame's `:rf.story/assertions` accumulator. The
  record carries the `:violations` vector (`{:path :value :explain}` per
  violating slice) so the result surfaces the exact schema misses the seed
  produced (spec/017 §Setup). `:passed? false` makes the run aggregate to
  `:fail`."
  [variant-id e]
  (let [data   (ex-data e)
        record {:assertion  :rf.error/story-db-seed-invalid
                :variant-id variant-id
                :status     :error
                :passed?    false
                :reason     (exception-message e)
                :violations (:violations data)}]
    (try
      (rf/dispatch-sync [::append-assertion record] {:frame variant-id})
      (catch #?(:clj Throwable :cljs :default) _ nil))
    record))

(defn- handle-run-error!
  "Catch-branch for the orchestrator: record the exception as a
  phase-0-setup assertion (covers any sync throw from the phase chain),
  transition the lifecycle machine to `:error`, then resolve with the
  best result map we can build from whatever the run accumulated. The
  recorded `:rf.error/exception` assertion (`:passed? false`) flows into
  the unified result's `:assertions`, so the aggregation reports `:fail`
  (the error record is a failed expectation) even when the tape is sparse.

  §B8 — a plan-construction failure (thrown in
  `prepare-context`, before frame allocation) cannot be recorded onto a
  frame, so it is projected directly via `plan-error-result`. Every other
  throw lands after the frame exists and routes through the frame-bound
  record/transition path.

  A `:db-seed` schema-validation failure (`run-db-seed!`)
  throws AFTER the frame is allocated, so it takes the frame-bound branch,
  but is recorded as its own structured `:rf.error/story-db-seed-invalid`
  assertion (carrying the path/value/explain violations) rather than the
  opaque `:rf.error/exception` shape.

  The plan-construction branch is gated SOLELY on
  `plan-construction-error?` (the `:where 'rf.story/variant-plan` marker
  that `re-frame.story.plan/fail!` stamps). The `:where` discriminator
  separates plan-compiler failures from later framework/runtime
  errors (which carry `:rf.error/id` but not the plan marker), so the
  lifecycle state is not needed to route correctly — gating on lifecycle
  state would be stale-frame-sensitive (a PRIOR `:ready` run on the same
  id leaves `rf.story.loaders/current-state` at `:ready`, not `:pre-mount`)."
  [resolve variant-id e start-ms]
  (cond
    (plan-construction-error? e)
    (resolve (plan-error-result variant-id e))

    (db-seed-error? e)
    (do
      (record-seed-error! variant-id e)
      (rf.story.loaders/error! variant-id (ex-data e))
      (resolve (record-result-map {:variant-id variant-id} start-ms)))

    :else
    (do
      (record-error! variant-id :phase-0-setup nil e)
      (rf.story.loaders/error! variant-id (ex-data e))
      (resolve (record-result-map {:variant-id variant-id} start-ms)))))

(defn- unknown-variant-result
  "The error result returned when `rf.story.frames/variant-body` finds no
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

(defn- prepare-ctx!
  "PREPARE half of the four-phase lifecycle: compile the plan, allocate +
  reset the frame, run db-seed, loaders, and setup (phases 0-2). Returns the
  threaded ctx with the frame at `:ready` (loader-incomplete / error paths
  aside) and the first React render therefore safe — but WITHOUT executing the
  play script. `resume-ctx!` runs the script (phase 4) against this ctx.

  Throws on a plan-construction / db-seed / phase error; the caller's
  try/catch projects it via `handle-run-error!`. Splitting prepare from
  resume is what lets the one run owner (rf2-j538f7.34) span the real React
  render boundary — loaders/setup before the first render, the script after
  commit — while `run-variant` composes both halves for the headless path."
  [variant-id variant-body opts]
  (-> (prepare-context variant-id variant-body opts)
      run-phase-0!
      run-db-seed!
      run-phase-1!
      run-phase-2!))

(defn- resume-ctx!
  "RESUME half of the four-phase lifecycle: run phase 4 (the auto-plays' play
  script + terminal assertions) against a prepared `ctx`, then resolve
  `resolve` with the unified result once phase 4 settles."
  [resolve ctx start-ms]
  (let [[ctx' play-promise] (run-phase-4! ctx)]
    (finalise-run! resolve play-promise ctx' start-ms)))

(defn run-variant
  "Allocate a frame for `variant-id`, run its setup, loaders, events, and
  play scripts, and return the unified result asynchronously.

  `opts`:
    :active-modes    coll of registered mode ids; deep-merged into args
    :cell-overrides  runtime arg overrides (controls panel)
    :substrate       active substrate (`:reagent`, `:uix`, ...)

  Returns a Promise (CLJS) / CompletableFuture (JVM). Rendering is a
  separate `render-variant` or UI-shell concern — this runner produces no
  rendered output, and the result map carries no rendering slot.

  Production callers (CLJS `:advanced` with `enabled?` false) get an
  immediately-resolved promise of the empty result map — per `001-Authoring.md`
  §Registration macros the runtime doesn't throw when nothing is registered.

  Pre-requisite: `re-frame.story/install-canonical-vocabulary!` must
  have been called at boot — it registers the `::append-assertion`
  helper event this runtime dispatches into."
  ([variant-id] (run-variant variant-id nil))
  ([variant-id opts]
   (if-not rf.story.config/enabled?
     (rf.story.async/resolved (empty-result variant-id))
     (let [variant-body (rf.story.frames/variant-body variant-id)]
       (if (nil? variant-body)
         (rf.story.async/resolved (unknown-variant-result variant-id))
         (let [start-ms (rf.interop/now-ms)]
           (rf.story.async/promise
             (fn [resolve]
               (try
                 (let [ctx (prepare-ctx! variant-id variant-body opts)]
                   (resume-ctx! resolve ctx start-ms))
                 (catch #?(:clj Throwable :cljs :default) e
                   (handle-run-error! resolve variant-id e start-ms)))))))))))

;; ---- the one run owner (rf2-j538f7.34) -----------------------------------
;;
;; A shell auto-play must execute EXACTLY ONCE. Before this, a single focused
;; shell selection had THREE execution owners: the selection-edge frame
;; preallocation (a full `run-variant`), the canvas `component-did-mount` run
;; (a second full `run-variant`), and the post-commit `auto-run!` — so a
;; variant's play-script (and its external effects) ran up to three times and a
;; cumulative script visibly double-counted (a passing variant looked failed).
;;
;; The fix gives Story ONE run owner that spans the real React render
;; boundary, split into a PREPARE half and a RESUME half over a per-variant
;; generation:
;;
;;   • `prepare-run!` runs loaders + setup (phases 0-2) so the first render is
;;     safe, WITHOUT the script. It is idempotent per `:run-key`: the
;;     selection-edge preallocation and the canvas post-commit run collapse to
;;     ONE frame reset + ONE generation. A changed run-key (cell-override /
;;     mode / substrate / hot-reload) — or a re-prepare after the current
;;     generation was already resumed (a React remount) — claims a FRESH
;;     generation.
;;
;;   • `resume-run!` runs the script (phase 4) EXACTLY ONCE per generation.
;;     Every shell trigger (selection-watcher, mount-time block, canvas
;;     post-commit) calls it for the same generation; the generation guard
;;     collapses them to one execution. A resume superseded by a newer prepare
;;     settles explicitly (never `:pass`, never reads the successor frame).
;;
;; Ownership is per variant/frame (one registry slot per variant-id), so two
;; different variants run concurrently and independently — this is NOT a global
;; lock. The inner per-play run-token (`rf.story.play.runner-events/run!`) still guards
;; play-key isolation; it is not the outer lifecycle owner.

(defonce ^:private run-owner
  ;; variant-id -> {:run-key K              ; the shell run-key this generation prepared for
  ;;                :generation G           ; monotonic per-variant run generation
  ;;                :resumed-gen R          ; the generation last claimed for resume (R<G ⇒ pending)
  ;;                :ctx <ctx> | :error e   ; the prepared attempt (or a prepare failure)
  ;;                :start-ms ms}
  (atom {}))

(defn current-generation
  "The current prepared run generation for `variant-id` (0 if never prepared).
  Public so the shell / canvas / tests can observe supersession."
  [variant-id]
  (get-in @run-owner [variant-id :generation] 0))

(defn reset-run-owner!
  "Drop the one-run-owner state for `variant-id` (frame teardown / re-run) or,
  with no arg, for every variant (shell unmount / test isolation). A dropped
  entry means the next `prepare-run!` starts a fresh generation."
  ([] (reset! run-owner {}))
  ([variant-id] (swap! run-owner dissoc variant-id) nil))

(defn- superseded-result
  "The explicit settle-value for a resume that a newer prepare superseded
  (rf2-j538f7.34 criteria 4/5). Never `:pass`; carries no successor state — it
  is built frame-free from `empty-result` so a stale attempt cannot green nor
  combine its args with the successor frame's app-db / evidence."
  [variant-id generation]
  (assoc (empty-result variant-id)
         :status      :cannot-run
         :lifecycle   :superseded
         :superseded? true
         :generation  generation))

(defn prepare-run!
  "PREPARE the one run owner for `variant-id` (rf2-j538f7.34): allocate + reset
  the frame and run loaders + setup (phases 0-2) so the first React render is
  safe, WITHOUT executing the play script. Idempotent per `:run-key` — repeated
  prepares for the same logical run (selection-edge preallocation + canvas
  post-commit) collapse to ONE reset + ONE generation.

  `opts` is the standard `run-variant` opts (`:active-modes` / `:cell-overrides`
  / `:substrate`) plus a `:run-key` — the shell slice that
  identifies this logical run (see `re-frame.story.ui.canvas/run-key`). A
  changed `:run-key`, or a re-prepare after the current generation was already
  resumed, claims a fresh generation and re-runs phases 0-2.

  Fire-and-forget: the display reads the frame's app-db reactively. Returns the
  claimed (or deduped) generation."
  [variant-id {:keys [run-key] :as opts}]
  (when (and rf.story.config/enabled? variant-id)
    (let [cur    (get @run-owner variant-id)
          ;; A fresh logical run when: never prepared; the run-key changed; or
          ;; the current generation was already resumed (a remount / re-entry).
          fresh? (or (nil? cur)
                     (not= (:run-key cur) run-key)
                     (= (:resumed-gen cur) (:generation cur)))]
      (if-not fresh?
        ;; Same run-key, not yet resumed — the frame is already prepared for
        ;; this generation. Do NOT reset it or bump the generation; the pending
        ;; resume owns the one execution.
        (:generation cur)
        (let [variant-body (rf.story.frames/variant-body variant-id)
              gen          (inc (get cur :generation 0))
              base         {:run-key run-key :generation gen :resumed-gen (dec gen)
                            :start-ms (rf.interop/now-ms)}]
          (if (nil? variant-body)
            (do (swap! run-owner assoc variant-id (assoc base :error :unknown-variant))
                gen)
            (do
              (try
                (let [ctx (prepare-ctx! variant-id variant-body opts)]
                  (swap! run-owner assoc variant-id (assoc base :ctx ctx)))
                (catch #?(:clj Throwable :cljs :default) e
                  ;; Capture the prepare failure so the matching resume publishes
                  ;; a structured error result (loaders/setup can throw).
                  (swap! run-owner assoc variant-id (assoc base :error e))))
              gen)))))))

(defn- claim-resume!
  "Atomically claim the current generation for resume. Returns the claimed
  attempt map iff this caller won the claim (the current generation was still
  pending), else nil. Exactly one caller per generation wins — the single run
  owner's exactly-once guarantee."
  [variant-id]
  (loop []
    (let [m   @run-owner
          cur (get m variant-id)]
      (if (or (nil? cur) (= (:resumed-gen cur) (:generation cur)))
        nil
        (if (compare-and-set! run-owner m
              (assoc-in m [variant-id :resumed-gen] (:generation cur)))
          cur
          (recur))))))

(defn resume-run!
  "RESUME the one run owner for `variant-id` (rf2-j538f7.34): run the prepared
  attempt's play script + terminal assertions (phase 4) EXACTLY ONCE per
  prepared generation, then publish the unified result. The shell
  selection-watcher, the shell mount-time block, and the canvas post-commit
  lifecycle all call this for the same generation; the generation guard
  collapses them to ONE script execution.

  A superseded attempt (a newer `prepare-run!` claimed a fresh generation
  before or during this resume) settles as an explicit `superseded-result` —
  never `:pass`, and without reading the successor frame.

  Must be called AFTER `prepare-run!` and AFTER React has committed the canvas
  (so DOM steps see the mounted view). Returns a promise of the result, or nil
  when there is nothing to resume (already resumed for this generation, or
  never prepared)."
  ([variant-id] (resume-run! variant-id nil))
  ([variant-id done-cb]
   (when (and rf.story.config/enabled? variant-id)
     (when-let [attempt (claim-resume! variant-id)]
       (let [my-gen   (:generation attempt)
             start-ms (or (:start-ms attempt) (rf.interop/now-ms))]
         (rf.story.async/promise
           (fn [resolve]
             (try
               (cond
                 ;; A newer prepare already superseded this attempt.
                 (not= my-gen (current-generation variant-id))
                 (resolve (superseded-result variant-id my-gen))
                 ;; The prepare half failed — project its error.
                 (= :unknown-variant (:error attempt))
                 (resolve (unknown-variant-result variant-id))
                 (:error attempt)
                 (handle-run-error! resolve variant-id (:error attempt) start-ms)
                 ;; Run phase 4 against the prepared ctx, re-checking the
                 ;; generation before publishing so a supersession DURING the
                 ;; async script settles as superseded rather than reading the
                 ;; successor frame.
                 ;;
                 ;; `:force-play?` so a loader-incomplete-but-drained variant
                 ;; (`:story.counter-matrix/loader-never-completes` — loaders
                 ;; ran and seeded state, but `:loaders-complete-when` reported
                 ;; not-ready) STILL runs its auto-play against the rendered
                 ;; view and publishes a play-runner run-state. In the browser
                 ;; the canvas renders the user view for such a variant (the
                 ;; recorded loader-incomplete assertion turns the skeleton
                 ;; off), so the auto-play must run — matching the pre-split
                 ;; browser `auto-run!` the Story/Xray play-scripts gate reads.
                 ;; The headless `run-variant` (via `resume-ctx!`) sets no such
                 ;; flag, so its loader-incomplete contract (play skipped,
                 ;; lifecycle parks at :loading) is unchanged.
                 :else
                 (let [[ctx' play-promise] (run-phase-4! (assoc (:ctx attempt) :force-play? true))]
                   (-> play-promise
                       (rf.story.async/then
                         (fn [_]
                           (if (= my-gen (current-generation variant-id))
                             (resolve (record-result-map ctx' start-ms))
                             (resolve (superseded-result variant-id my-gen)))
                           (when done-cb (try (done-cb) (catch #?(:clj Throwable :cljs :default) _ nil)))
                           nil)))))
               (catch #?(:clj Throwable :cljs :default) e
                 (handle-run-error! resolve variant-id e start-ms))))))))))

;; ---- inline plan execution -----------------------------------------------
;;
;; An inline plan (spec/017 §Inline plan) is an executable plan MAP that is
;; NOT registered as a Story variant: it MUST NOT appear in Story
;; navigation, it MAY compose registered fragments + checks, and it MUST
;; return the SAME run-result shape as a registered variant. `story/run`,
;; `story/is`, and `story/explain` already accept a map target (the verbs
;; dispatch on target type — a keyword is a registry lookup, a map is an
;; inline plan); `variant-plan` / `explain` compile a map directly.
;; This is the RUN path for a map target.
;;
;; The design reuses the registered run pipeline rather than forking it:
;;
;;   - the plan is compiled once (`rf.story.plan/variant-plan` accepts the map);
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
  `:rf.story.inline/*` namespace (Story's spec/Conventions.md
  §`:rf.story.*` framework carve-out, which owns the closed member set;
  the framework's spec/Conventions.md reserves the `:rf.story.*`
  sub-namespace). A monotonic counter keeps concurrent inline runs on
  distinct frames; the id is never a registered variant id, so an inline
  run can never collide with — or appear alongside — a navigable variant."
  []
  (keyword "rf.story.inline" (str "plan-" (swap! inline-frame-counter inc))))

(defn- prepare-inline-context
  "Resolve the per-run inputs an inline-plan run needs from its COMPILED
  `plan`. Registry-free twin of `prepare-context`:

  - `:variant-id`      — the minted anonymous frame id (the run's frame);
  - `:plan`            — the supplied compiled plan, threaded down the
                          phases unchanged (it is already normalized);
  - `:decorator-stack` — `rf.story.decorators/resolve-decorator-refs` over the
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
  registered artefact even when the plan is not).

  `:runner-selection` is selected from the SAME compiled plan +
  run `opts` a registered run uses (`resolve-runner-selection`), so an inline
  plan threads requirements selection identically — an UNMET requirement
  surfaces `:cannot-run` on the inline path too."
  ([frame-id plan] (prepare-inline-context frame-id plan nil))
  ([frame-id plan opts]
   {:variant-id       frame-id
    :plan             plan
    :runner-selection (resolve-runner-selection plan opts)
    :decorator-stack  (rf.story.decorators/resolve-decorator-refs
                        (get-in plan [:world :decorators] []))
    :effective-args   (get-in plan [:world :effective-args] {})
    :loader-body      {:loaders               (get-in plan [:world :loaders])
                       :loaders-complete-when (get-in plan [:world :loaders-complete-when])}
    :snapshot         nil}))

(defn- inline-events-only?
  "True iff the inline `plan` drives no loaders / loaders-complete-when and
  carries no `:frame-setup` decorators — so the lifecycle takes the
  `:pre-mount → :ready` fast-path. Mirrors
  `rf.story.loaders/events-only-variant?`, reading the loader slots off the plan's
  `:world` rather than a registered body."
  [plan decorator-stack]
  (and (empty? (get-in plan [:world :loaders]))
       (nil?   (get-in plan [:world :loaders-complete-when]))
       (empty? (:frame-setup decorator-stack))))

(defn- run-inline-phase-0!
  "Phase 0 for an inline run: allocate the ANONYMOUS frame from the plan
  (registry-free), then clear the per-frame `pending-exceptions` slot +
  install the play-runner privacy egress listener — the same ordering
  `run-phase-0!` uses so loader-phase events are captured (no
  side-table to seed).

  Also stamps `:epoch-baseline` (rf2-xj0bj0; identity-based per rf2-96qsjr),
  mirroring `run-phase-0!`, so `record-result-map` filters the SAME way on
  both paths. An inline plan mints a brand-new anonymous frame id every
  run (never reused), so this is always 0 in practice — but computing it
  here (rather than leaving it absent) keeps the inline path symmetric
  with the registered path for whatever the frame's own allocation
  records before the loaders/setup/script phases run, so an inline plan
  and an equivalent registered variant project the same-shaped tape (and
  therefore the same run-hash)."
  [{:keys [variant-id plan decorator-stack] :as ctx}]
  (rf.story.frames/allocate-inline! variant-id
                           decorator-stack
                           (get-in plan [:world :frame :fx-overrides])
                           (inline-events-only? plan decorator-stack)
                           ;; rf2-cmjly3 finding 12: thread the plan's
                           ;; :sensitive/:large classification (carried
                           ;; through `:world` by `plan.cljc`'s
                           ;; `context-keys`) into `allocate-inline!` so it
                           ;; actually applies to the frame's elision
                           ;; registry instead of being silently dropped.
                           (select-keys (:world plan) [:sensitive :large]))
  (swap! rf.story.play/pending-exceptions assoc variant-id [])
  (rf.story.play/install-trace-listener! variant-id)
  (assoc ctx :epoch-baseline (rf.story.play.runner-events/last-epoch-id variant-id)))

(defn run-inline-plan
  "Execute an inline plan MAP (spec/017 §Inline plan) and
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
   (if-not rf.story.config/enabled?
     (rf.story.async/resolved (empty-result (:variant/id inline-plan)))
     (let [start-ms     (rf.interop/now-ms)
           compile-opts (select-keys opts [:lookup :fragment-lookup :check-lookup
                                           :view-lookup :sub-lookup :validator-fns])
           ;; Compile the plan up front so a construction failure (an
           ;; unknown composed fragment, a missing `[:arg …]`, an
           ;; `[:assert …]` in setup, …) is projected directly — no frame
           ;; is allocated, mirroring `plan-error-result`'s frame-free shape.
           plan-or-err  (try {:plan (rf.story.plan/variant-plan inline-plan compile-opts)}
                          (catch #?(:clj Throwable :cljs :default) e {:error e}))]
       (if-let [e (:error plan-or-err)]
         (rf.story.async/resolved (plan-error-result (:variant/id inline-plan) e))
         (let [plan     (:plan plan-or-err)
               frame-id (mint-inline-frame-id)]
           (rf.story.async/promise
             (fn [resolve]
               ;; The SUCCESS path tears the inline frame down
               ;; with the decorator stack used for allocation + the plan's
               ;; `:loaders-teardown`; the FAILURE path converges on the
               ;; SAME teardown, so any resource opened by an inline-plan
               ;; loader or `:frame-setup` decorator is released on every
               ;; failure path (db-seed / phase 1 / phase 2 / phase 4 setup)
               ;; where cleanup matters.
               ;;
               ;; The decorator stack is fixed at `prepare-inline-context`
               ;; time (resolved once, constant across phases), so `teardown!`
               ;; reads it off the captured context. `allocated?` flips true
               ;; only AFTER `run-inline-phase-0!` (which calls
               ;; `allocate-inline!`) succeeds: a throw BEFORE allocation
               ;; (e.g. inside `prepare-inline-context`) tears down nothing —
               ;; no frame exists and no frame-setup `:init` ran — while a
               ;; throw AFTER allocation tears down exactly what was set up
               ;; (partial-allocation safety). `destroy-inline!` already
               ;; no-ops the loader-teardown walk when its third arg is empty.
               (let [ctx*       (volatile! nil)
                     allocated? (volatile! false)
                     teardown!  (fn []
                                  (when @allocated?
                                    (try
                                      (rf.story.frames/destroy-inline!
                                        frame-id (:decorator-stack @ctx*)
                                        (get-in plan [:world :loaders-teardown]))
                                      (catch #?(:clj Throwable :cljs :default) _ nil))))
                     fail!      (fn [e]
                                  ;; A `:db-seed` schema-validation
                                  ;; failure records as its own structured
                                  ;; assertion; every other throw stays the
                                  ;; opaque `:rf.error/exception` shape.
                                  (if (db-seed-error? e)
                                    (record-seed-error! frame-id e)
                                    (record-error! frame-id :phase-0-setup nil e))
                                  (rf.story.loaders/error! frame-id (ex-data e))
                                  (let [result (record-result-map
                                                 {:variant-id frame-id :plan plan} start-ms)]
                                    (teardown!)
                                    (resolve result)))]
                 (try
                   (let [prepared     (prepare-inline-context frame-id plan opts)
                         _            (vreset! ctx* prepared)
                         ;; Phase 0 allocates the anonymous frame (+ runs
                         ;; frame-setup `:init`); only past it does the
                         ;; failure path owe a teardown.
                         ctx0         (run-inline-phase-0! prepared)
                         _            (vreset! allocated? true)
                         ctx          (-> ctx0 run-db-seed! run-phase-1! run-phase-2!)
                         _            (vreset! ctx* ctx)
                         [ctx' play-promise] (run-phase-4! ctx)
                         _            (vreset! ctx* ctx')]
                     (-> play-promise
                         (rf.story.async/then
                           (fn [_]
                             (let [result (record-result-map ctx' start-ms)]
                               (teardown!)
                               (resolve result))
                             nil))
                         ;; A rejection INSIDE the play promise (phase 4) must
                         ;; also converge on teardown, not leak the frame.
                         (rf.story.async/catch* (fn [e] (fail! e) nil))))
                   (catch #?(:clj Throwable :cljs :default) e
                     (fail! e))))))))))))

;; ---- reset-variant -------------------------------------------------------

(defn reset-variant
  "Tear down the variant frame and re-run `run-variant` with `opts`.
  Used by the UI shell for hot reload and the user-triggered reset action.

  Returns a promise/future of the new result map."
  ([variant-id] (reset-variant variant-id nil))
  ([variant-id opts]
   (when rf.story.config/enabled?
     ;; Drop the one-run-owner attempt (rf2-j538f7.34): its prepared ctx points
     ;; at the frame we are about to destroy, so the next prepare must start a
     ;; fresh generation rather than dedupe onto a stale attempt.
     (reset-run-owner! variant-id)
     (rf.story.frames/destroy! variant-id))
   (run-variant variant-id opts)))

;; ---- prepare-variant -----------------------------------------------------

(defn prepare-variant
  "Re-run the PREPARE half of the four-phase lifecycle for `variant-id` —
  phases 0-2 (fresh-frame boundary, allocation, `:db-seed`, `:loaders`,
  `:setup`) — and STOP. The play script (phase 4) is left ENTIRELY pending.

  `reset-variant`'s pre-play counterpart. Where `reset-variant` composes
  both halves (the `:test` pane's Re-run button wants exactly that), this
  one hands back a frame parked at the variant's documented initial state
  with every script step still to run — the position a step-debugger's
  Start must present (`009-Test-Mode.md` §Start semantics). Reaching that
  position through `reset-variant` ran the whole script first, so the
  debugger showed cursor 0 over a POST-script app-db and re-ran every step
  and every effect it had already issued (rf2-k6y2).

  Reuses `prepare-ctx!`, the SAME prepare half `run-variant` and
  `prepare-run!` already drive, so there is no second lifecycle here. The
  frame is reset IN PLACE by phase 0's `ensure-fresh-frame!` rather than
  destroyed, so a canvas already mounted over this variant keeps its live
  reactions while its app-db returns to the initial state.

  Drops the one-run-owner attempt for `variant-id` (as `reset-variant`
  does, and for the same reason): a generation that was prepared but not
  yet resumed would otherwise let a later `resume-run!` run the whole
  script over the frame the caller is about to step through.

  Returns a promise/future that resolves to `nil` once phases 0-2 have
  settled, and REJECTS when preparation fails — an unknown variant, a
  `:db-seed` that violates a registered schema, a throwing loader or
  `:setup` handler. Rejecting (rather than resolving an error result, as
  `run-variant` does) is what lets the caller return its UI to an honest
  inactive state instead of presenting controls over a frame that never
  reached `:ready`.

  `opts` is the standard `run-variant` opts (`:active-modes` /
  `:cell-overrides` / `:substrate`)."
  ([variant-id] (prepare-variant variant-id nil))
  ([variant-id opts]
   (if-not rf.story.config/enabled?
     (rf.story.async/resolved nil)
     (rf.story.async/promise
       (fn [resolve]
         ;; `rf.story.async/promise` rejects when this body throws, which is
         ;; the honest settle for every prepare-half failure — including an
         ;; unknown variant, which `prepare-context`'s plan compile already
         ;; refuses with `:rf.error/story-unknown-variant`. Phases 0-2 are
         ;; synchronous, so the promise has settled by the time this returns.
         (reset-run-owner! variant-id)
         (prepare-ctx! variant-id (rf.story.frames/variant-body variant-id) opts)
         (resolve nil))))))

;; ---- watch-variant -------------------------------------------------------

(defn watch-variant
  "Per `002-Runtime.md` §Programmatic API — subscribe to lifecycle transitions for
  `variant-id`'s frame. `callback` is invoked on every state change
  with `{:frame-id ... :from <state> :to <state> :event <event>}`.

  Returns a 0-arity unsubscribe fn.

  The UI shell and assertion runtime consume this. The watcher table is
  per-frame so destroyed frames clean up automatically via
  `rf.story.frames/destroy!`."
  [variant-id callback]
  (when rf.story.config/enabled?
    (rf.story.loaders/add-watcher! variant-id callback)))

;; ---- snapshot-identity re-export ----------------------------------------

(defn snapshot-identity
  "Per `002-Runtime.md` §Snapshot-identity computation. Compute the content-hash for
  `(variant × active-modes × cell-overrides × substrate)`. See
  `re-frame.story.identity/snapshot-identity` for the canonical form."
  ([variant-id] (rf.story.identity/snapshot-identity variant-id))
  ([variant-id opts] (rf.story.identity/snapshot-identity variant-id opts)))
