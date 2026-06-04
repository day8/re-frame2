(ns day8.re-frame2-xray.test-helpers.trace-event-builders
  "Canonical trace-event builders for Xray fixtures + projection tests
  (rf2-tyivx).

  ## Why one ns

  Pre-rf2-tyivx the per-trace-event constructors (`cofx-run-ev`,
  `fx-handled-ev`, `flow-recomputed-ev`, `view-rendered-ev`, etc.)
  were duplicated between:

    - `tools/xray/testbeds/panel_gallery/fixtures_epoch.cljs` (gallery
      synth fixtures)
    - `tools/xray/test/day8/re_frame2_xray/panels/epoch/projection_cljs_test.cljc`
      (projection unit tests)
    - the various substrate-side test fixtures that drive other
      projection-adjacent tests.

  Drift between copies caused the rf2-e0xjx cluster (rf2-yhgk8 /
  rf2-slnce / rf2-ipaza / rf2-w2r4p): the substrate canonical emit
  names rotated and the fixtures + projection reader changed in
  lock-step — both wrong, both consistent, both silently broken in
  any test that didn't also rotate. A single shared builder ns is the
  forcing function that keeps fixtures + projection reader pinned to
  ONE substrate-canonical name set.

  ## Scope

  Every builder mirrors the substrate emit-site shape one-for-one
  (the same shape `re-frame.cofx` / `re-frame.fx` / `re-frame.flows`
  / `re-frame.views` etc. stamp through `trace/emit!`). Adding a new
  builder here is the right move whenever a new substrate emit shape
  needs fixture coverage. Builders are pure-data and CLJC — call from
  CLJS test files AND CLJ test files alike.

  The companion ns
  `day8.re-frame2-xray.test-helpers.real-substrate-projection-cljs-test`
  (rf2-tyivx end-to-end coverage) drives a REAL `trace/emit!`
  invocation against the substrate so a substrate-side rename can
  never silently desync from the synth fixtures again.

  ## Glossary

  | Builder                        | Operation                              | Drives                              |
  | ------------------------------ | -------------------------------------- | ----------------------------------- |
  | `ev`                           | (low-level)                            | every other builder                 |
  | `dispatched-ev`                | `:rf.event/dispatched`                 | DISPATCH row                        |
  | `cofx-run-ev`                  | `:rf.cofx/run`                         | COEFFECT step                       |
  | `run-end-ev`                   | `:rf.event/run-end`                    | HANDLER duration                    |
  | `db-changed-ev`                | `:rf.event/db-changed`                 | APP-DB DIFF step                    |
  | `do-fx-ev`                     | `:rf.fx/do-fx`                         | FX per-row attribution              |
  | `fx-handled-ev`                | `:rf.fx/handled`                       | FX per-row outcome                  |
  | `flow-recomputed-ev`           | `:rf.flow/computed`                    | FLOW step (one row per flow)        |
  | `sub-run-ev`                   | `:rf.sub/run`                          | SUBSCRIPTIONS step                  |
  | `sub-dispose-ev`               | `:rf.sub/dispose`                      | SUBSCRIPTIONS DISPOSED sub-section  |
  | `view-rendered-ev`             | `:rf.view/rendered`                    | VIEWS step                          |
  | `view-unmounted-ev`            | `:rf.view/unmounted`                   | VIEWS UNMOUNTED sub-section         |
  | `machine-transition-ev`        | `:rf.machine/transition`               | HANDLER machine cascade             |
  | `machine-guard-ev`             | `:rf.machine/guard-evaluated`          | HANDLER GUARDS                      |
  | `machine-action-ev`            | `:rf.machine/action-ran`               | HANDLER LIFECYCLE                   |
  | `machine-timer-cancel-ev`      | `:rf.machine.timer/cancelled`          | HANDLER AFTER-TIMERS                |
  | `schema-violation-ev`          | `:rf.error/schema-validation-failure`  | SCHEMA VIOLATIONS                   |
  | `schema-hot-reload-ev`         | `:rf.schema/violation`                 | SCHEMA HOT-RELOAD                   |
  | `handler-exception-ev`         | `:rf.error/handler-exception`          | HANDLER inline error card           |
  | `coeffect-exception-ev`        | `:rf.error/coeffect-exception`         | COEFFECT inline error card (rf2-yz57h) |
  | `interceptor-exception-ev`     | `:rf.error/interceptor-exception`      | INTERCEPTOR step (rf2-yz57h)        |
  | `fx-handler-exception-ev`      | `:rf.error/fx-handler-exception`       | SIDE EFFECTS inline error card      |"
  (:refer-clojure :exclude [ev]))

;; ---- low-level primitive -----------------------------------------------

(defn ev
  "Minimal trace-event map. `op-type` is the broad classifier
  (`:rf.event / :rf.fx / :rf.sub / :rf.view / :rf.machine / …`),
  `operation` the precise emit op the projection reader keys on."
  [op-type operation tags]
  {:op-type   op-type
   :operation operation
   :tags      tags})

;; ---- dispatch + handler-frame ------------------------------------------

(defn dispatched-ev
  "`:rf.event/dispatched` trace — drives the DISPATCH row. Carries
  the substrate-canonical `:rf.event/v` (event vector) + `:source` +
  optional `:rf.trace/call-site`. The top-level `:event` + `:source`
  slots are also stamped for legacy reader paths that match on the
  outer shape (projection_cljs_test.cljc reads these)."
  ([event] (dispatched-ev event nil nil))
  ([event source] (dispatched-ev event source nil))
  ([event source coord]
   (cond-> (ev :rf.event :rf.event/dispatched
               {:rf.event/v         event
                :source             source
                :rf.trace/call-site coord})
     true (assoc :event event :source source))))

(defn run-end-ev
  "`:rf.event/run-end` trace — the projection reads the handler's
  finalised duration here.

  rf2-slnce — substrate stamps the canonical `:rf.event/elapsed-ms`
  (rf2-hhh92 · `re-frame.router/emit-run-end-trace`); fixture mirrors
  that shape. Reader retains a legacy `:duration-ms` fallback for
  older runtimes / external test fixtures."
  ([] (run-end-ev nil nil))
  ([duration-ms] (run-end-ev duration-ms nil))
  ([duration-ms coeffects]
   (ev :rf.event :rf.event/run-end
       (cond-> {:rf.event/elapsed-ms duration-ms}
         coeffects (assoc :rf.event/coeffects coeffects)))))

;; ---- coeffects ---------------------------------------------------------

(defn cofx-run-ev
  "`:rf.cofx/run` trace — one per USER-injected coeffect (system
  cofx `:db / :event / :frame / :source / :trace-id` are filtered out
  by the projection per rf2-cq0ch). The 3-arg form supplies a
  duration; the 2-arg legacy form omits it.

  rf2-w2r4p — substrate stamps the canonical `:rf.cofx/elapsed-ms`
  duration tag (rf2-hhh92 · `re-frame.cofx`; spec 009 §243)."
  ([id value]
   (ev :rf.cofx :rf.cofx/run {:rf.cofx/id    id
                              :rf.cofx/value value}))
  ([id value duration-ms]
   (ev :rf.cofx :rf.cofx/run {:rf.cofx/id         id
                              :rf.cofx/value      value
                              :rf.cofx/elapsed-ms duration-ms})))

;; ---- db-changed --------------------------------------------------------

(defn db-changed-ev
  "`:rf.event/db-changed` trace — drives both HANDLER's `:db-diff`
  slot AND the APP-DB DIFF step. `paths` is a vec of
  `[path before after change-kind]` quads."
  [paths]
  (ev :rf.event :rf.event/db-changed
      {:rf.event/db-changed-paths paths}))

;; ---- fx ----------------------------------------------------------------

(defn do-fx-ev
  "`:rf.fx/do-fx` trace — carries the handler's returned `:rf.event/fx`
  payload. Drives the FX step's per-row attribution AND the CHILD-
  DISPATCHES step (rf2-yx1ae harvests dispatch-family fx from this
  same payload)."
  [fx]
  (ev :rf.fx :rf.fx/do-fx {:rf.event/fx fx}))

(defn fx-handled-ev
  "`:rf.fx/handled` trace — one per fx-handler invocation. FX per-row
  outcome chrome reads `:rf.fx/id` + `:rf.fx/args` +
  `:rf.fx/elapsed-ms` here.

  rf2-ipaza — substrate stamps the canonical `:rf.fx/elapsed-ms`
  (rf2-hhh92 · `re-frame.fx`; spec 009 §241)."
  [fx-id args duration-ms]
  (ev :rf.fx :rf.fx/handled {:rf.fx/id         fx-id
                             :rf.fx/args       args
                             :rf.fx/elapsed-ms duration-ms}))

;; ---- pending-`:db` snapshots (t1 / t2) — rf2-ta0y7 / rf2-4wywy ----------

(defn db-pending-ev
  "`:rf.event/db-pending` trace (t1) — the POST-handler, PRE-flow db
  value the handler chain returned, stamped under `:tags :rf.event/db`
  (rf2-ta0y7). The Epoch HANDLER step's `:db` sub-section reads this
  so it shows ONLY the handler's contribution, not the post-flow
  state (rf2-4wywy)."
  [db]
  (ev :rf.event :rf.event/db-pending {:rf.event/db db}))

(defn db-pending-post-flow-ev
  "`:rf.event/db-pending-post-flow` trace (t2) — the POST-flow,
  PRE-commit (flow-augmented) db value, stamped under
  `:tags :rf.event/db` (rf2-ta0y7). OMITTED at the emit site when no
  flow changed `:db` (t1 == t2). The Epoch FLOW step reads the t1→t2
  pair to render the flow's OWN `:db` diff (rf2-4wywy)."
  [db]
  (ev :rf.event :rf.event/db-pending-post-flow {:rf.event/db db}))

;; ---- flows -------------------------------------------------------------

(defn flow-recomputed-ev
  "`:rf.flow/computed` trace — drives the FLOW step.

  rf2-yhgk8 — substrate stamps the `:rf.flow/computed` operation with
  bare `:flow-id` / `:path` / `:before` / `:result` / `:elapsed-ms`
  tags (Spec 009 §Flow trace events · `re-frame.flows`). The name
  `flow-recomputed-ev` is retained for call-site stability; the
  payload is canonical.

  4-arg legacy form omits the elapsed-ms tag; 5-arg form stamps it."
  ([flow-id path before after]
   (ev :rf.flow :rf.flow/computed {:flow-id flow-id
                                   :path    path
                                   :before  before
                                   :result  after}))
  ([flow-id path before after elapsed-ms]
   (ev :rf.flow :rf.flow/computed {:flow-id    flow-id
                                   :path       path
                                   :before     before
                                   :result     after
                                   :elapsed-ms elapsed-ms})))

;; ---- subscriptions -----------------------------------------------------

(defn sub-run-ev
  "`:rf.sub/run` trace — drives one SUBSCRIPTIONS row. Per rf2-kfh1v
  the canonical tag names are `:rf.sub/id`, `:rf.sub/query-v`,
  `:rf.sub/value-changed?`, `:rf.sub/prev-value`, `:rf.sub/value` —
  the projection reads ONLY these post-rf2-kfh1v.

  4-arg form omits an elapsed-ms tag; 5-arg form stamps it."
  ([sub-vec changed? before after]
   (ev :rf.sub :rf.sub/run
       {:rf.sub/id             (when (vector? sub-vec) (first sub-vec))
        :rf.sub/query-v        sub-vec
        :rf.sub/value-changed? changed?
        :rf.sub/prev-value     before
        :rf.sub/value          after}))
  ([sub-vec changed? before after elapsed-ms]
   (ev :rf.sub :rf.sub/run
       {:rf.sub/id             (when (vector? sub-vec) (first sub-vec))
        :rf.sub/query-v        sub-vec
        :rf.sub/value-changed? changed?
        :rf.sub/prev-value     before
        :rf.sub/value          after
        :rf.sub/elapsed-ms     elapsed-ms})))

(defn sub-dispose-ev
  "`:rf.sub/dispose` trace — drives the SUBSCRIPTIONS DISPOSED sub-
  section (rf2-wpfjo). Per rf2-mrnur substrate stamps `:rf.sub/id` +
  `:rf.sub/query-v` + `:rf.sub/reason` + `:frame` on every cache-
  eviction site (closed `:reason` set per rf2-mrnur:
  `:no-more-derefers / :hot-reload / :cache-clear`)."
  ([sub-vec reason]
   (sub-dispose-ev sub-vec reason :rf/default))
  ([sub-vec reason frame]
   (ev :rf.sub :rf.sub/dispose
       {:rf.sub/id      (when (vector? sub-vec) (first sub-vec))
        :rf.sub/query-v sub-vec
        :rf.sub/reason  reason
        :frame          frame})))

;; ---- views -------------------------------------------------------------

(defn view-rendered-ev
  "`:rf.view/rendered` trace (per rf2-6djth — NOT the simpler
  `:rf.view/render` marker; only `:rf.view/rendered` carries
  `:rf.view/id` + `:rf.view/deref-subs` + `:rf.view/elapsed-ms`).

  Two-arg form omits elapsed-ms; three-arg stamps it. The four-arg
  form takes an `opts` map carrying the cascade-attribution slots the
  substrate stamps conditionally (rf2-25zo2 / rf2-8wrzz.1) so render-
  cause fixtures (rf2-bhi3t) match the emit shape one-for-one:

    `:elapsed-ms`   — wall-clock render duration.
    `:mount?`       — `true` on the instance's first render (mount),
                      `false`/absent on a re-render. Stamped only when
                      `true` (the substrate's `:rf.view/mount?` cond->
                      shape — absent means re-render).
    `:triggered-by` — the single sub-id whose value changed in the
                      view's read-set (the per-view re-render cause).
                      Absent on a structural / props-driven re-render.
    `:render-key`   — the per-instance tuple (rf2-u3lii — drives the
                      col-2 render-args DIFF's same-instance keying;
                      `:rf.view/render-key`). Stamped only when present.
    `:render-args`  — the positional args/props vector passed to THIS
                      render (rf2-rpgq8 · `:rf.view/render-args`). The
                      substrate stamps it only `(seq render-args)`;
                      mirrored here — stamped only when non-empty."
  ([view-id deref-subs]
   (view-rendered-ev view-id deref-subs nil))
  ([view-id deref-subs elapsed-ms]
   (ev :rf.view :rf.view/rendered
       (cond-> {:rf.view/id         view-id
                :rf.view/deref-subs deref-subs}
         (some? elapsed-ms)
         (assoc :rf.view/elapsed-ms elapsed-ms))))
  ([view-id deref-subs elapsed-ms {:keys [mount? triggered-by render-key render-args]}]
   (ev :rf.view :rf.view/rendered
       (cond-> {:rf.view/id         view-id
                :rf.view/deref-subs deref-subs}
         (some? elapsed-ms)   (assoc :rf.view/elapsed-ms elapsed-ms)
         (true? mount?)       (assoc :rf.view/mount? true)
         (some? triggered-by) (assoc :rf.view/triggered-by triggered-by)
         (some? render-key)   (assoc :rf.view/render-key render-key)
         (seq render-args)    (assoc :rf.view/render-args (vec render-args))))))

(defn view-unmounted-ev
  "`:rf.view/unmounted` trace — drives the VIEWS UNMOUNTED sub-section
  (rf2-gmw1i). Per `re-frame.views/emit-view-unmounted!` the substrate
  stamps `:rf.view/id` + `:rf.view/render-key` + `:frame`."
  ([view-id]
   (view-unmounted-ev view-id nil :rf/default))
  ([view-id render-key frame]
   (ev :rf.view :rf.view/unmounted
       {:rf.view/id         view-id
        :rf.view/render-key render-key
        :frame              frame})))

;; ---- machine handler ---------------------------------------------------

(defn machine-transition-ev
  "`:rf.machine/transition` trace — drives the HANDLER step's
  machine TRANSITION sub-section + the DATA-REDUCTION /
  SNAPSHOT-DIFF projections (rf2-u69j7). `before` + `after` are full
  machine snapshot maps (`:state` + `:data` + …).

  rf2-52u5n / rf2-n9f4z — the 6-arg form stamps the STRUCTURED
  `:cascade` step vector (the ordered exit/action/entry/microstep
  steps the substrate emits per rf2-n9f4z) so the EVENT HANDLER
  machine-cascade render can be driven from a synth fixture. The
  5-arg form OMITS `:cascade` (the negative-guard / older-trace
  shape the view falls back to the summary on)."
  ([machine-id before after]
   (machine-transition-ev machine-id before after nil 0))
  ([machine-id before after event microsteps]
   (ev :rf.machine :rf.machine/transition
       (cond-> {:machine-id machine-id
                :before     before
                :after      after}
         event      (assoc :event event)
         microsteps (assoc :microsteps microsteps))))
  ([machine-id before after event microsteps cascade]
   (ev :rf.machine :rf.machine/transition
       (cond-> {:machine-id machine-id
                :before     before
                :after      after}
         event       (assoc :event event)
         microsteps  (assoc :microsteps microsteps)
         (some? cascade) (assoc :cascade cascade)))))

(defn machine-guard-ev
  "`:rf.machine/guard-evaluated` trace — drives the HANDLER step's
  GUARDS sub-section (one row per guard, with outcome
  `:pass / :fail / :threw`)."
  [guard-id outcome]
  (ev :rf.machine :rf.machine/guard-evaluated
      {:guard-id guard-id
       :outcome  outcome}))

(defn machine-action-ev
  "`:rf.machine/action-ran` trace — drives the HANDLER step's
  LIFECYCLE sub-section (one row per action, grouped by `:phase` per
  rf2-82a0u). Optional `:outcome :fx` rides the rf2-9c27r per-action
  fx attribution."
  ([action-id phase outcome]
   (machine-action-ev action-id phase outcome nil))
  ([action-id phase outcome data]
   (ev :rf.machine :rf.machine/action-ran
       {:action-id action-id
        :phase     phase
        :outcome   outcome
        :input     {:data  (or data {})
                    :event nil}})))

(defn machine-timer-cancel-ev
  "`:rf.machine.timer/cancelled` trace — drives the HANDLER step's
  AFTER-TIMERS sub-section (one row per cancelled timer with
  `:reason` in the unified rf2-82a0u set)."
  [machine-id state delay reason]
  (ev :rf.machine :rf.machine.timer/cancelled
      {:machine-id machine-id
       :state      state
       :delay      delay
       :reason     reason}))

(defn machine-unhandled-no-op-ev
  "`:rf.machine.event/unhandled-no-op` trace (rf2-ugdas) — the benign
  unhandled-event no-op (xstate-v5 parity). Op-type `:rf.machine` (NOT a
  severity), so `issue-event?` does not flag it. Drives the EVENT HANDLER
  machine cascade's muted `:no-op` row."
  [machine-id event state]
  (ev :rf.machine :rf.machine.event/unhandled-no-op
      {:machine-id machine-id
       :event      event
       :state      state}))

(defn machine-started-ev
  "`:rf.machine/started` trace (rf2-it4vt, the machine BIRTH signal
  `maybe-boot` emits per rf2-gl588 / F-triple-prime). Op-type `:rf.machine`
  (benign birth, NOT a severity). Carries the machine's INITIAL logical
  `:state` + INITIAL `:data` + the `:cause` enum (`:explicit` / `:lazy` /
  `:spawned`). Drives the EVENT HANDLER machine cascade's `[START]` row.

    - EAGER  -> `:explicit` (an explicit `[:machine-id [:rf.machine/start]]`)
    - LAZY   -> `:lazy`     (init folded into the first real event's epoch)
    - SPAWN  -> `:spawned`  (the spawn fx pre-seeded the snapshot)"
  [machine-id state data cause]
  (ev :rf.machine :rf.machine/started
      {:machine-id machine-id
       :state      state
       :data       data
       :cause      cause}))

(defn machine-history-restored-ev
  "`:rf.machine.history/restored` trace (rf2-mle6e.5, spec/009 §History trace
  events) — a transition resolved a `:type :history` pseudo-state. Op-type
  `:rf.machine` (benign activity, NOT a severity). On the `:recorded` path
  pass `:restored-config`; on the `:default` path pass `:fallback` and omit
  `:restored-config`."
  [{:keys [machine-id compound-path kind source fallback restored-config resolved-leaf]}]
  (ev :rf.machine :rf.machine.history/restored
      (cond-> {:machine-id    machine-id
               :compound-path compound-path
               :kind          kind
               :source        source
               :resolved-leaf resolved-leaf}
        (= :default source)    (assoc :fallback fallback)
        (not= :default source) (assoc :restored-config restored-config))))

(defn machine-history-recorded-ev
  "`:rf.machine.history/recorded` trace (rf2-mle6e.5, spec/009 §History trace
  events) — a history-bearing compound's exit wrote its config into
  `:rf/history`. Op-type `:rf.machine`. `:prev-config` is OMITTED on the
  first-ever recording (pass nil)."
  [{:keys [machine-id compound-path kind recorded-config prev-config]}]
  (ev :rf.machine :rf.machine.history/recorded
      (cond-> {:machine-id      machine-id
               :compound-path   compound-path
               :kind            kind
               :recorded-config recorded-config}
        (some? prev-config) (assoc :prev-config prev-config))))

(defn machine-action-exception-ev
  "`:rf.error/machine-action-exception` trace (rf2-e7yhv) — a machine
  action threw during a transition. Mirrors the emit shape in
  `machines/lifecycle_fx/registration.cljc/trace-action-failure!`: the
  message rides `[:tags :exception-message]`, the raw exception rides
  `[:tags :exception]`, and the `:transition` slot carries the matched
  transition map (whose `:rf/via-wildcard?` flag attributes a `:*`
  wildcard-action throw). Op-type `:error` — DOES go pink (the inverse of
  the benign no-op). Drives the HANDLER step's inline EXCEPTION card.

  `via-wildcard?` stamps `:rf/via-wildcard? true` on the `:transition`
  slot so the card can attribute the throw to a wildcard action."
  [{:keys [machine-id action-id event message exception via-wildcard? state-path]}]
  (ev :error :rf.error/machine-action-exception
      (cond-> {:machine-id        machine-id
               :action-id         action-id
               :event             event
               :state-path        (or state-path [:armed])
               :failing-id        machine-id
               :handler-id        machine-id
               :exception-message message
               :recovery          :no-recovery
               :transition        (cond-> {:action action-id}
                                    via-wildcard? (assoc :rf/via-wildcard? true))}
        exception (assoc :exception exception))))

;; ---- schema ------------------------------------------------------------

(defn schema-violation-ev
  "`:rf.error/schema-validation-failure` trace (rf2-17vxj) — the
  runtime per-event boundary check (`:where` in
  `:app-db / :cofx / :sub-return / :fx-args`). Drives the SCHEMA
  VIOLATIONS step.

  4-arg form omits the rollback flag; 5-arg form stamps it; 6-arg form
  additionally carries a Malli `:explain` map (rf2-plev0 — the
  projection's `schema-violation-row` decodes it into the `:decoded`
  expected/got summary, mirroring the substrate emit shape that stamps
  `:explain`)."
  ([where failing-id path value]
   (schema-violation-ev where failing-id path value nil))
  ([where failing-id path value rollback?]
   (schema-violation-ev where failing-id path value rollback? nil))
  ([where failing-id path value rollback? explain]
   (ev :error :rf.error/schema-validation-failure
       (cond-> {:where      where
                :failing-id failing-id
                :path       path
                :value      value}
         (some? rollback?) (assoc :rollback? rollback?)
         (some? explain)   (assoc :explain explain)))))

(defn schema-hot-reload-ev
  "`:rf.schema/violation` trace (rf2-17vxj) — the hot-reload drift
  check fires when a re-registration changed the schema at a
  `(frame-id, path)` AND the live `app-db` value at `path` fails the
  new schema. Distinct from the runtime per-event failure; rides on
  the standalone SCHEMA HOT-RELOAD tail step (rf2-xgeag · Option A)."
  [frame-id path mismatching-value]
  (ev :warning :rf.schema/violation
      {:frame             frame-id
       :path              path
       :mismatching-value mismatching-value
       :recovery          :logged-and-skipped}))

;; ---- cascade exceptions (rf2-ahhgn) ------------------------------------

(defn handler-exception-ev
  "`:rf.error/handler-exception` trace (rf2-ahhgn) — a handler /
  interceptor / injected-coeffect threw; the router emits this from
  `emit-handler-exception!`. Mirrors that emit shape: the message rides
  `[:tags :exception-message]`, the handler id rides `[:tags :handler-id]`
  / `[:tags :failing-id]`, the optional interceptor `:phase` rides
  `[:tags :phase]`, and the failing handler's SOURCE-COORD rides the
  hoisted top-level `:rf.trace/trigger-handler :source-coord` slot.
  Drives the HANDLER step's inline error card + the per-step ✗ status.

  2-arg form: event-id + message. The 3-arg form adds the source-coord;
  the 4-arg form adds the interceptor `:phase` (`:before` / `:after`);
  the 5-arg form adds the raw `:exception` object (rf2-wnvid — the Epoch
  card's collapsible details read the stack / ex-data off it)."
  ([event-id message] (handler-exception-ev event-id message nil nil nil))
  ([event-id message coord] (handler-exception-ev event-id message coord nil nil))
  ([event-id message coord phase] (handler-exception-ev event-id message coord phase nil))
  ([event-id message coord phase exception]
   (cond-> (ev :error :rf.error/handler-exception
               (cond-> {:event-id          event-id
                        :handler-id        event-id
                        :failing-id        event-id
                        :exception-message message
                        :reason            "Event handler threw."}
                 phase     (assoc :phase phase)
                 exception (assoc :exception exception)))
     true     (assoc :recovery :no-recovery)
     coord    (assoc :rf.trace/trigger-handler {:kind         :event
                                                :id           event-id
                                                :source-coord coord}))))

(defn fx-handler-exception-ev
  "`:rf.error/fx-handler-exception` trace (rf2-ahhgn) — a registered
  fx-handler threw during the post-commit fx walk. The fx-id rides
  `[:tags :rf.fx/id]` so the projection's `attach-to-fx-error-row` can
  match it to the FX step's `:db`/user-fx rows; the message rides
  `[:tags :exception-message]`. Drives the FX step's inline error card
  (per-row when the fx-id matches, step-level otherwise)."
  ([fx-id message] (fx-handler-exception-ev fx-id message nil))
  ([fx-id message coord]
   (cond-> (ev :error :rf.error/fx-handler-exception
               {:rf.fx/id          fx-id
                :failing-id        fx-id
                :exception-message message
                :reason            "Effect handler threw."})
     true  (assoc :recovery :no-recovery)
     coord (assoc :rf.trace/trigger-handler {:kind         :fx
                                             :id           fx-id
                                             :source-coord coord}))))

(defn coeffect-exception-ev
  "`:rf.error/coeffect-exception` trace (rf2-mszrz / rf2-yz57h) — a
  coeffect INJECTOR threw during `:before`-chain coeffect injection. The
  cofx id rides `[:tags :failing-id]`; the message rides
  `[:tags :exception-message]`. The router emits this from
  `emit-pipeline-exception!` after `classify-pipeline-exception` reads the
  throwing interceptor's captured `:rf/cofx-id`. Drives the COEFFECT
  step's inline 'Exception Thrown' card (rf2-yz57h)."
  ([cofx-id message] (coeffect-exception-ev cofx-id message nil))
  ([cofx-id message exception]
   (cond-> (ev :error :rf.error/coeffect-exception
               (cond-> {:failing-id        cofx-id
                        :exception-message message
                        :reason            (str "Coeffect injection for `"
                                                cofx-id "` threw.")}
                 exception (assoc :exception exception)))
     true (assoc :recovery :no-recovery))))

(defn interceptor-exception-ev
  "`:rf.error/interceptor-exception` trace (rf2-mszrz / rf2-yz57h) — a USER
  interceptor threw in its `:before` or `:after` phase. The interceptor id
  rides `[:tags :failing-id]`, the phase rides `[:tags :phase]`
  (`:before` / `:after`), and the message rides `[:tags :exception-message]`.
  The router emits this from `emit-pipeline-exception!` after
  `classify-pipeline-exception` finds a captured `:id` that is neither a
  handler-wrapper nor a cofx injector. Drives the INTERCEPTOR step's inline
  'Exception Thrown' card (rf2-yz57h).

  rf2-siheh — the optional `coord` map (`{:ns :file :line}`) rides
  `[:tags :source-coord]`, mirroring what the router threads from a
  `->interceptor`-macro-built interceptor's captured `:source-coord`. The
  projection lifts it onto the INTERCEPTOR row's `:coord` so the view can
  render a jump-to-source chip (parity with EVENT HANDLER / SUBSCRIPTIONS /
  VIEWS). Absent → no chip (the `->interceptor*` fn / framework-interceptor
  path)."
  ([intc-id phase message] (interceptor-exception-ev intc-id phase message nil nil))
  ([intc-id phase message exception] (interceptor-exception-ev intc-id phase message exception nil))
  ([intc-id phase message exception coord]
   (cond-> (ev :error :rf.error/interceptor-exception
               (cond-> {:failing-id        intc-id
                        :phase             phase
                        :exception-message message
                        :reason            (str "Interceptor `" intc-id
                                                "` threw in its `" (name phase)
                                                "` phase.")}
                 exception (assoc :exception exception)
                 coord     (assoc :source-coord coord)))
     true (assoc :recovery :no-recovery))))
