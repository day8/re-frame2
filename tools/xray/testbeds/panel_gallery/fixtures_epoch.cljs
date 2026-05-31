(ns panel-gallery.fixtures-epoch
  "Pure fixture builders for the Xray **Epoch** panel gallery (rf2-mzcwt).

  ## What the Epoch panel renders

  `tools/xray/spec/021-Dynamic-Panel-Designs.md` §9.1 — the focused
  epoch's complete computational timeline as a numbered vertical
  cascade: DISPATCH → COEFFECT (one per cofx) → HANDLER → FLOW
  (one per flow, rf2-xnb1x) → FX → SUBSCRIPTIONS → VIEWS. Runtime-
  boundary schema violations attach as inline sub-blocks under
  their owning step (rf2-xgeag + rf2-8resu); hot-reload drift
  surfaces via the Issues panel exclusively (rf2-7gf7v retired the
  standalone SCHEMA HOT-RELOAD tail step). Each step is
  CONDITIONAL — only the steps whose driving trace events surfaced
  get rendered (per
  `tools/xray/src/day8/re_frame2_xray/panels/epoch/projection.cljc`).

  The 10-entry badge inventory + 16ms long-step threshold ride on
  `panels.epoch.projection` (PRs #2191 / #2193). These fixtures
  exercise every section the projection lights up, one variant per
  section, so the gallery reads as a feature-coverage table.

  ## How variants seed state

  The Epoch panel reads through a composite sub
  `:rf.xray/epoch-pipeline` that joins:

    - `:rf.xray/focus`         — carries `:epoch-id`
    - `:rf.xray/epoch-history` — vector of `:rf/epoch-record` maps

  No variant pins focus. With no spine bus seeded the focus stays nil;
  the shared `focus-resolver`'s head-fallback (rf2-h0120) then renders
  `(peek epoch-history)` — i.e. the LAST element of the oldest-first
  history vector. Each fixture therefore returns a one-record history
  whose `:trace-events` slice surfaces the section under test.

  ## Epoch-record shape (per spec/004-AppDbDiff + re-frame.epoch)

      {:epoch-id      <int>             ; stable id, ring-buffer key
       :frame         :rf/default
       :committed-at  <ms>
       :event-id      <kw>              ; first elem of :trigger-event
       :trigger-event <event-vec>
       :dispatch-id   <int>             ; child-dispatch attribution
       :trace-events  [<trace-event> ...]}  ; what the projection reads

  ## Trace-event shape (per Spec 009 + panels.epoch.projection)

  The projection reads per-event `:operation` + `:tags`; the surface
  for each step is captured in the projection's per-row builders. Each
  builder here mirrors the substrate emit-site shape — the same shape
  the projection unit tests at
  `tools/xray/test/day8/re_frame2_xray/panels/epoch/projection_cljs_test.cljc`
  pin. Drift in either keeps both broken in lock-step.")

;; ---- trace-event primitives ---------------------------------------------

(defn- ev
  "Minimal trace-event map. `op-type` is the broad classifier
  (`:rf.event / :rf.fx / :rf.sub / :rf.view / :rf.machine / …`),
  `operation` the precise emit op the projection reads."
  [op-type operation tags]
  {:op-type   op-type
   :operation operation
   :tags      tags})

(defn- dispatched-ev
  "`:rf.event/dispatched` trace — drives the DISPATCH row. Carries
  the substrate-canonical `:rf.event/v` (event vector) +
  `:source` + optional `:rf.trace/call-site`."
  ([event] (dispatched-ev event nil nil))
  ([event source] (dispatched-ev event source nil))
  ([event source coord]
   (cond-> (ev :rf.event :rf.event/dispatched
              {:rf.event/v         event
               :source             source
               :rf.trace/call-site coord})
     true (assoc :event event :source source))))

(defn- cofx-run-ev
  "`:rf.cofx/run` trace — one per USER-injected coeffect. System
  cofx (`:db / :event / :frame / :source / :trace-id`) are filtered
  out by the projection (rf2-cq0ch), so fixtures only need to emit
  the user-injected ids the COEFFECT step should surface.

  rf2-w2r4p — substrate stamps the canonical `:rf.cofx/elapsed-ms`
  duration tag (rf2-hhh92 · `re-frame.cofx`; spec 009 §243); fixture
  mirrors that. The reader retains a legacy `:duration-ms` fallback
  for older runtimes / external test fixtures."
  [id value]
  (ev :rf.cofx :rf.cofx/run {:rf.cofx/id          id
                             :rf.cofx/value       value
                             :rf.cofx/elapsed-ms  0.1}))

(defn- run-end-ev
  "`:rf.event/run-end` trace — the projection reads the handler's
  finalised duration here.

  rf2-slnce — substrate stamps the canonical `:rf.event/elapsed-ms`
  (rf2-hhh92 · `re-frame.router/emit-run-end-trace`); fixture mirrors
  that. The reader retains legacy `:duration-ms` fallbacks for older
  runtimes / external test fixtures."
  [duration-ms]
  (ev :rf.event :rf.event/run-end {:rf.event/elapsed-ms duration-ms}))

(defn- db-changed-ev
  "`:rf.event/db-changed` trace — drives both HANDLER's `:db-diff`
  slot AND the dedicated APP-DB DIFF step (rf2-rrykz, same payload,
  two surfaces). `paths` is a vec of
  `[path before after change-kind]` quads."
  [paths]
  (ev :rf.event :rf.event/db-changed
      {:rf.event/db-changed-paths paths}))

(defn- do-fx-ev
  "`:rf.fx/do-fx` trace — carries the handler's returned `:rf.event/fx`
  payload. Drives the FX step's per-row attribution AND the
  CHILD-DISPATCHES step (rf2-yx1ae harvests dispatch-family fx from
  this same payload)."
  [fx]
  (ev :rf.fx :rf.fx/do-fx {:rf.event/fx fx}))

(defn- fx-handled-ev
  "`:rf.fx/handled` trace — one per fx-handler invocation. The FX
  step's per-row outcome reads `:rf.fx/id` + `:rf.fx/args` +
  `:rf.fx/elapsed-ms` here.

  rf2-ipaza — substrate stamps the canonical `:rf.fx/elapsed-ms`
  (rf2-hhh92 · `re-frame.fx`; spec 009 §241); fixture mirrors that.
  The reader retains a legacy `:duration-ms` fallback for older
  runtimes / external test fixtures."
  ([fx-id args duration-ms]
   (ev :rf.fx :rf.fx/handled {:rf.fx/id          fx-id
                              :rf.fx/args        args
                              :rf.fx/elapsed-ms  duration-ms})))

(defn- fx-override-applied-ev
  "`:rf.fx/override-applied` trace — a registered fx was diverted by a
  dev-time override (`re-frame.fx/handle-one-fx`). The SIDE EFFECTS
  `:fx` row reads this op via `fx-outcome-op->status` → `:overridden`
  and paints the ↺ tick (rf2-kt6js)."
  [fx-id args]
  (ev :rf.fx :rf.fx/override-applied {:rf.fx/id   fx-id
                                      :rf.fx/args args}))

(defn- fx-skipped-on-platform-ev
  "`:rf.fx/skipped-on-platform` trace — a platform-gated fx that did not
  run on the current target (`re-frame.fx/handle-one-fx`). The SIDE
  EFFECTS `:fx` row reads this op via `fx-outcome-op->status` →
  `:skipped` and paints the · tick (rf2-kt6js)."
  [fx-id args]
  (ev :rf.fx :rf.fx/skipped-on-platform {:rf.fx/id   fx-id
                                         :rf.fx/args args}))

(defn- fx-handler-exception-ev
  "`:rf.error/fx-handler-exception` trace (rf2-ahhgn) — a registered
  fx-handler threw during the post-commit fx walk. The fx-id rides
  `:rf.fx/id` so the projection's `attach-to-fx-error-row` matches it
  to the SIDE EFFECTS step's `:fx` row; the message rides
  `:exception-message`; the failing handler's reg-site coord rides the
  hoisted `:rf.trace/trigger-handler :source-coord` slot. Drives the
  inline 'Exception Thrown' card on the matching `:fx` row + the per-
  effect ✗ tick."
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

(defn- handler-exception-ev
  "`:rf.error/handler-exception` trace (rf2-ahhgn · rf2-wnvid) — a
  handler / interceptor / injected-coeffect threw; the router emits
  this from `emit-handler-exception!`. The message rides
  `:exception-message`; the failing handler's reg-site coord rides the
  hoisted `:rf.trace/trigger-handler :source-coord` slot; the raw
  exception object rides `:exception` (the Epoch card's collapsible
  details read its `.stack` + `ex-data`). Drives the HANDLER step's
  inline 'Exception Thrown' card + the per-step ✗ status + the epoch
  `:outcome :error`."
  ([event-id message coord exception]
   (cond-> (ev :error :rf.error/handler-exception
               {:event-id          event-id
                :handler-id        event-id
                :failing-id        event-id
                :exception-message message
                :reason            "Event handler threw."
                :exception         exception})
     true  (assoc :recovery :no-recovery)
     coord (assoc :rf.trace/trigger-handler {:kind         :event
                                             :id           event-id
                                             :source-coord coord}))))

(defn- db-pending-ev
  "`:rf.event/db-pending` trace (t1) — the POST-handler, PRE-flow db
  value the handler chain returned, stamped under `:rf.event/db`
  (rf2-ta0y7). The Epoch HANDLER step's `:db` sub-section reads this so
  it shows ONLY the handler's own contribution, not the post-flow state
  (rf2-4wywy). Its presence is also the `db-write?` signal that lets the
  rf2-wnvid PHANTOM-`:db` fix know the handler actually wrote a `:db`."
  [db]
  (ev :rf.event :rf.event/db-pending {:rf.event/db db}))

(defn- db-pending-post-flow-ev
  "`:rf.event/db-pending-post-flow` trace (t2) — the POST-flow,
  PRE-commit (flow-augmented) db value, stamped under `:rf.event/db`
  (rf2-ta0y7). OMITTED at the emit site when no flow changed `:db`
  (t1 == t2). The Epoch FLOW step reads the t1→t2 pair to render the
  flow's OWN `:db` diff as a separate step from the handler's `:db`
  write (rf2-4wywy / rf2-48oc4)."
  [db]
  (ev :rf.event :rf.event/db-pending-post-flow {:rf.event/db db}))

(defn- flow-recomputed-ev
  "`:rf.flow/computed` trace — drives the FLOW step.

  rf2-yhgk8 — substrate stamps the `:rf.flow/computed` operation with
  bare `:flow-id` / `:path` / `:before` / `:result` / `:elapsed-ms`
  tags (Spec 009 §Flow trace events · `re-frame.flows`). The name
  `flow-recomputed-ev` is retained for call-site stability; the
  payload is canonical."
  [flow-id path before after]
  (ev :rf.flow :rf.flow/computed {:flow-id    flow-id
                                  :path       path
                                  :before     before
                                  :result     after
                                  :elapsed-ms 0.4}))

(defn- sub-run-ev
  "`:rf.sub/run` trace — drives one SUBSCRIPTIONS row. Per rf2-kfh1v
  the canonical tag names are `:rf.sub/id`, `:rf.sub/query-v`,
  `:rf.sub/value-changed?`, `:rf.sub/prev-value`, `:rf.sub/value` —
  the projection reads ONLY these post-rf2-kfh1v.

  The 5-arg `opts` form admits the cascade-attribution slots the
  substrate stamps conditionally so the rf2-1cc03 / rf2-87c8a chrome
  renders:

    `:cause-event-id` — the head keyword of the dispatching cascade's
                        trigger event (`:rf.sub/cause-event-id`,
                        rf2-okz1u / rf2-1cc03). The view renders this
                        as the `caused by <event-id>` cell. Stamped
                        only when present (OMIT-vs-nil parity with the
                        emit site).
    `:inputs`         — the sub's upstream input-signal query-vectors
                        (`:rf.sub/inputs`). The inputs column prefers
                        the live `(rf/handler-meta :sub <id>)` static
                        topology (rf2-87c8a), but in the gallery's
                        bare-panel mount the sub isn't registered, so
                        the view falls back to this `:inputs` slot —
                        which is what makes the inputs column paint the
                        upstream sub-ids rather than the `app-db`
                        Level-1 label."
  ([sub-vec changed? before after]
   (sub-run-ev sub-vec changed? before after nil))
  ([sub-vec changed? before after {:keys [cause-event-id inputs]}]
   (ev :rf.sub :rf.sub/run
       (cond-> {:rf.sub/id             (when (vector? sub-vec) (first sub-vec))
                :rf.sub/query-v        sub-vec
                :rf.sub/value-changed? changed?
                :rf.sub/prev-value     before
                :rf.sub/value          after
                :rf.sub/elapsed-ms     0.2}
         (some? cause-event-id) (assoc :rf.sub/cause-event-id cause-event-id)
         (some? inputs)         (assoc :rf.sub/inputs inputs)))))

(defn- view-rendered-ev
  "`:rf.view/rendered` trace (per rf2-6djth — NOT the simpler
  `:rf.view/render` marker; only `:rf.view/rendered` carries
  `:rf.view/id` + `:rf.view/deref-subs` + `:rf.view/elapsed-ms`)."
  [view-id deref-subs elapsed-ms]
  (ev :rf.view :rf.view/rendered {:rf.view/id         view-id
                                  :rf.view/deref-subs deref-subs
                                  :rf.view/elapsed-ms elapsed-ms}))

(defn- view-unmounted-ev
  "`:rf.view/unmounted` trace (per rf2-9hoos / rf2-te71r) — drives
  the VIEWS step's UNMOUNTED sub-section (rf2-gmw1i). Substrate
  stamps `:rf.view/id` + `:rf.view/render-key` + `:frame` on every
  view-instance teardown."
  [view-id render-key]
  (ev :rf.view :rf.view/unmounted {:rf.view/id         view-id
                                   :rf.view/render-key render-key
                                   :frame              :rf/default}))

(defn- sub-dispose-ev
  "`:rf.sub/dispose` trace (per rf2-mrnur) — drives the SUBSCRIPTIONS
  step's DISPOSED sub-section (rf2-wpfjo). Substrate stamps
  `:rf.sub/id` + `:rf.sub/query-v` + `:rf.sub/reason` + `:frame` on
  every cache-eviction site (closed `:reason` set per rf2-mrnur:
  `:no-more-derefers / :hot-reload / :cache-clear`)."
  [sub-vec reason]
  (ev :rf.sub :rf.sub/dispose {:rf.sub/id      (when (vector? sub-vec) (first sub-vec))
                               :rf.sub/query-v sub-vec
                               :rf.sub/reason  reason
                               :frame          :rf/default}))

;; ---- machine-handler trace primitives -----------------------------------

(defn- machine-transition-ev
  "`:rf.machine/transition` trace — drives the HANDLER step's
  machine TRANSITION sub-section + the DATA-REDUCTION /
  SNAPSHOT-DIFF projections (the panel's machine-handler full
  design, PR #2193). `before` + `after` are full machine snapshot
  maps (`:state` + `:data` + …)."
  [machine-id event before after microsteps]
  (ev :rf.machine :rf.machine/transition
      {:machine-id machine-id
       :event      event
       :before     before
       :after      after
       :microsteps microsteps}))

(defn- machine-guard-ev
  "`:rf.machine/guard-evaluated` trace — drives the HANDLER step's
  GUARDS sub-section (one row per guard, with outcome
  `:pass / :fail / :threw`)."
  [guard-id outcome]
  (ev :rf.machine :rf.machine/guard-evaluated {:guard-id guard-id
                                               :outcome  outcome}))

(defn- machine-action-ev
  "`:rf.machine/action-ran` trace — drives the HANDLER step's
  LIFECYCLE sub-section (one row per action, grouped by `:phase`
  `:exit / :transition / :entry / :always / :after-action / …` per
  rf2-82a0u). Optional `:outcome :fx` rides the rf2-9c27r
  per-action fx attribution."
  ([action-id phase outcome]
   (machine-action-ev action-id phase outcome nil nil))
  ([action-id phase outcome data action-fx]
   (let [outcome-map (cond-> (if (map? outcome) outcome {})
                       action-fx (assoc :fx action-fx)
                       data      (assoc :data data))]
     (ev :rf.machine :rf.machine/action-ran
         {:action-id action-id
          :phase     phase
          :outcome   outcome-map
          :input     {:data (or data {}) :event nil}}))))

(defn- machine-timer-cancel-ev
  "`:rf.machine.timer/cancelled` trace — drives the HANDLER step's
  AFTER-TIMERS sub-section (one row per cancelled timer with
  `:reason` in the unified rf2-82a0u set)."
  [machine-id state delay reason]
  (ev :rf.machine :rf.machine.timer/cancelled
      {:machine-id machine-id
       :state      state
       :delay      delay
       :reason     reason}))

;; ---- schema-violation trace primitives ----------------------------------

(defn- schema-violation-ev
  "`:rf.error/schema-validation-failure` trace (rf2-17vxj) — the
  runtime per-event boundary check (`:where` in
  `:app-db / :cofx / :sub-return / :fx-args`). Drives the SCHEMA
  VIOLATIONS step.

  Per rf2-2ek7t the trace event also carries `:explain-humanized` —
  the Malli-humanized form of the raw `:explain` map. The substrate
  populates this slot via the late-bind `:schemas/humanize-explain!`
  hook (Malli adapter publishes `malli.error/humanize` under it).
  For fixture-replay tests (which bypass the substrate's
  `emit-validation-failure!` helper) we seed a representative
  humanized shape directly so the violation block demonstrates the
  new chrome — `{<path-leaf> [<message>]}` is the canonical
  humanize output for a single-error explain."
  [where failing-id path value rollback?]
  (let [path-leaf (if (sequential? path) (last path) path)]
    (ev :error :rf.error/schema-validation-failure
        {:where             where
         :failing-id        failing-id
         :path              path
         :value             value
         :rollback?         (boolean rollback?)
         :explain           {:errors [{:path path :message "expected int"}]}
         :explain-humanized {path-leaf [(str "should be an integer, got "
                                              (pr-str value))]}})))

;; `schema-hot-reload-ev` retired per rf2-w8evg — the standalone
;; SCHEMA HOT-RELOAD tail step was retired in rf2-7gf7v; hot-reload
;; drift now surfaces via the Issues panel exclusively, so a fixture
;; helper that synthesises a tail-step trace is dead weight. If the
;; Issues-panel gallery needs an equivalent it lives there.

;; ---- epoch-record builder ------------------------------------------------

(defn- epoch-record
  "Minimal `:rf/epoch-record` for the Epoch panel's projection. The
  projection reads `:trace-events` + `:event-id` + `:dispatch-id`;
  the panel's empty-state branch additionally reads `:epoch-id`.

  rf2-4wywy / rf2-48oc4 — the optional `:db-before` / `:db-after`
  snapshots are the RAW app-db values the framework records on every
  epoch record (the framework leaves diffs to be computed JIT by
  consumers). The HANDLER step's `:db` sub-section + the FLOW step's
  `:db` diff are both computed against these (via the Editscript engine
  + the t1/t2 pending snapshots), and `handler-db-diff-block`
  subscribes to the selected record to read them. A fixture that drives
  the `:db` diff surfaces MUST carry both."
  [{:keys [epoch-id event dispatch-id trace-events db-before db-after]}]
  (cond-> {:epoch-id      epoch-id
           :frame         :rf/default
           :committed-at  (* 1000 (or epoch-id 0))
           :event-id      (first event)
           :trigger-event event
           :dispatch-id   (or dispatch-id (* 1000 (or epoch-id 0)))
           :trace-events  (vec trace-events)}
    (some? db-before) (assoc :db-before db-before)
    (some? db-after)  (assoc :db-after db-after)))

(defn- single-epoch-history
  "Wrap one trace-event vector into a one-record history. The single
  record is the head (the focus-resolver's head-fallback resolves to
  `(peek epoch-history)`)."
  [opts]
  [(epoch-record opts)])

;; ---- VARIANT 1: vanilla reg-event-db cascade ----------------------------
;;
;; Section coverage: DISPATCH + COEFFECT + HANDLER (reg-event-db
;; flavour, with the `:db` FULL+DIFF sub-section) + SIDE EFFECTS (`:db`
;; ✓ row — a bare reg-event-db that returns ONLY `:db` now lights the
;; SIDE EFFECTS step's `:db` sub-step because `db-commit?` keys off
;; `:rf.event/db-changed`, rf2-kt6js) + SUBSCRIPTIONS + VIEWS. The
;; simplest possible counter-inc-style cascade — three subs / two views
;; / one cofx.
;;
;; rf2-4wywy — the record carries `:db-before` / `:db-after` snapshots
;; + the t1 (`db-pending-ev`) post-handler snapshot so the HANDLER step
;; renders its `:db` FULL+DIFF block (the `[:counter] 5 → 6` change) and
;; the view's `handler-db-diff-block` resolves a real diff rather than
;; the `— db-after not available` placeholder.

(defn vanilla-db-history
  "Vanilla `reg-event-db` cascade. One user-cofx, a single db path
  mutation, two subs (one changed, one cached-unchanged), one view
  render. Carries db snapshots + t1 so the HANDLER `:db` diff + the
  SIDE EFFECTS `:db` ✓ row render against the current panel."
  []
  (single-epoch-history
    {:epoch-id  1
     :event     [:counter/inc 1]
     :db-before {:counter 5}
     :db-after  {:counter 6}
     :trace-events
     [(dispatched-ev [:counter/inc 1] :ui)
      (cofx-run-ev :now 1700000000000)
      (db-pending-ev {:counter 6})
      (db-changed-ev [[[:counter] 5 6 :modified]])
      (sub-run-ev [:counter/value]   true  5 6)
      (sub-run-ev [:counter/doubled] true  10 12)
      (sub-run-ev [:counter/label]   false "Count: 5" "Count: 5")
      (view-rendered-ev :counter/badge [[:counter/value]] 0.8)
      (run-end-ev 0.5)]}))

;; ---- VARIANT 2: SIDE EFFECTS step (rf2-kt6js) ---------------------------
;;
;; Section coverage: the SIDE EFFECTS step (badge `:SIDE-EFFECTS`) with
;; its THREE sub-steps in fixed order `:db → :fx → other`, each carrying
;; its own per-effect ✓/✗ tick (the shared rf2-ahhgn `:status`
;; primitive) AND a sub-step ✓/✗ rollup in its header:
;;
;; rf2-j630b — the SIDE EFFECTS step renders these as a FLAT ledger (one
;; row per effect in execution order; no group headers):
;;
;;   :db    — the handler's app-db write, FIRST in the ledger. ✓ committed
;;            (no rollback this variant — the schema-fail rollback rides
;;            VARIANT 4b). Its args slot is the clickable `→ app-db`
;;            destination marker (the diff lives in the App-db panel).
;;   :fx    — the handler's `:fx` vector entries, one row each in order,
;;            exercising the full per-effect glyph set the projection's
;;            `fx-outcome-op->status` maps:
;;              ✓ ran        (`:rf.fx/handled`)
;;              ↺ overridden (`:rf.fx/override-applied`)
;;              – skipped    (`:rf.fx/skipped-on-platform`; NEUTRAL)
;;   other  — a TOP-LEVEL effect key beyond `:db` / `:fx` on the returned
;;            map, LAST in the ledger. re-frame2's effect map is the closed
;;            `{:db :fx}` shape; the runtime DROPS any other key, so the
;;            row renders the muted `–` not-run diagnostic (NEUTRAL).
;;
;; The single SIDE EFFECTS badge is the AND-of-rows: this all-actioned
;; ledger (the skipped + dropped rows are neutral) reads ✓.
;;
;; rf2-4wywy — db snapshots + t1 so the HANDLER `:db` FULL+DIFF block
;; renders alongside the SIDE EFFECTS `:db` ✓ row.

(defn reg-event-fx-history
  "`reg-event-fx` cascade that returns `:db` + a three-entry `:fx`
  vector (a ran effect, an overridden effect, a platform-skipped
  effect) + a stray top-level `:analytics` effect the runtime drops.
  Exercises the SIDE EFFECTS flat ledger (rf2-j630b) — the `:db` →
  app-db row, each per-effect glyph variant, and the `other` not-run
  diagnostic — under one ✓/✗ badge."
  []
  (single-epoch-history
    {:epoch-id  2
     :event     [:order/submit {:order-id 42}]
     :db-before {:order {:status :draft}}
     :db-after  {:order {:status :submitting}}
     :trace-events
     [(dispatched-ev [:order/submit {:order-id 42}] :ui)
      (db-pending-ev {:order {:status :submitting}})
      (db-changed-ev [[[:order :status] :draft :submitting :modified]])
      ;; The handler returned {:db .. :fx [[..] [..] [..]] :analytics ..}.
      ;; The `:db` + `:fx` entries drive the per-effect rows; `:analytics`
      ;; is the closed-shape-violating top-level key the runtime drops.
      (do-fx-ev {:db       ::placeholder
                 :fx       [[:http/post {:url "/api/orders" :body {:order-id 42}}]
                            [:analytics/track {:event :order-submitted}]
                            [:clipboard/write {:text "ORDER-42"}]]
                 :analytics {:event :order-submitted}})
      ;; :db commit (✓) — recorded by db-changed above; the per-fx rows:
      (fx-handled-ev :http/post {:url "/api/orders"} 3.4)
      ;; analytics/track was diverted by a dev-time override (↺).
      (fx-override-applied-ev :analytics/track {:event :order-submitted})
      ;; clipboard/write is browser-only — skipped on this platform (·).
      (fx-skipped-on-platform-ev :clipboard/write {:text "ORDER-42"})
      (run-end-ev 1.2)]}))

;; ---- VARIANT 3: machine-driven cascade ----------------------------------
;;
;; Section coverage (rf2-u69j7): the machine-handler section renders as
;; a SINGLE TIME-ORDERED CASCADE — one row per substrate emit, in trace
;; insertion order. Replaces the pre-rf2-u69j7 7-category roll-up
;; (TRANSITION / GUARDS / LIFECYCLE / AFTER-TIMERS / DATA-REDUCTION /
;; SNAPSHOT-DIFF / FX) with a single cascade view per Mike's bead body.
;;
;; The fixture below richly exercises every cascade row kind + every
;; rf2-82a0u phase the substrate stamps:
;;
;;   1. guard pass (handshake-valid?)
;;   2. guard fail  (retry-budget?) — exercises the fail outcome chip
;;   3. exit action — clear-retry-timer (data write)
;;   4. transition action — log-transition (no data write, no fx)
;;   5. timer cancellation — on-exit reason
;;   6. transition emit — :connecting → :open
;;   7. entry action — store-session (data write)
;;   8. entry action — notify-subscribers (per-action fx attribution)
;;   9. always action — heartbeat-pulse
;;  10. after-action — retry-failover-arm

(defn machine-history
  "Machine-handler cascade for a `:ws/connection` machine handling
  `:ws/open` (rf2-u69j7). Exercises every cascade row kind +
  every rf2-82a0u phase + the long-step warning chrome (the
  store-session action lands at 18ms — above the 16ms threshold)
  so the gallery exercises:

    - kind variety (guard / action / transition / timer)
    - phase variety (exit / transition / entry / always / after-action)
    - outcome variety (pass / fail / ok / threw / cancelled)
    - per-action data delta + per-action fx attribution
    - duration warning chrome on a long-running entry action"
  []
  (let [before-snap {:state :connecting
                     :data  {:retries 2 :last-error :timeout}}
        after-snap  {:state :open
                     :data  {:retries 0 :session-id "ws-7821"
                             :opened-at 1700000000000}}]
    (single-epoch-history
      {:epoch-id 3
       :event    [:ws/open {:url "wss://api.example.com" :session-id "ws-7821"}]
       :trace-events
       [(dispatched-ev [:ws/open {:url "wss://api.example.com"
                                  :session-id "ws-7821"}] :ws)
        ;; --- guards (substrate emits BEFORE the transition fires) ---
        (machine-guard-ev :ws/handshake-valid? :pass)
        ;; A second guard fails — the cascade still proceeds because
        ;; the outer transition has multiple `when` branches and the
        ;; substrate emits one row per evaluated guard.
        (machine-guard-ev :ws/retry-budget? :fail)
        ;; --- exit phase — leaving :connecting ---
        (machine-action-ev :ws.connecting/clear-retry-timer
                           :exit
                           {:data {:retries 0}}
                           {:retries 0}
                           nil)
        ;; --- transition phase ---
        (machine-action-ev :ws/log-transition
                           :transition
                           {:logged true}
                           nil
                           nil)
        ;; --- timer cancellation (substrate fires :on-exit) ---
        (machine-timer-cancel-ev :ws/connection :connecting 5000 :on-exit)
        ;; --- transition emit — the macrostep's headline ---
        (machine-transition-ev :ws/connection
                               [:ws/open {:url "wss://api.example.com"
                                          :session-id "ws-7821"}]
                               before-snap
                               after-snap
                               1)
        ;; --- entry phase — arriving in :open ---
        (machine-action-ev :ws.open/store-session
                           :entry
                           {:data {:session-id "ws-7821"
                                   :opened-at 1700000000000}}
                           {:session-id "ws-7821"
                            :opened-at  1700000000000}
                           nil)
        ;; entry phase emits per-action fx (rf2-9c27r / rf2-u69j7 —
        ;; surfaces inline on the cascade row, not in a sibling
        ;; FX sub-section).
        (machine-action-ev :ws.open/notify-subscribers
                           :entry
                           {:fx [[:dispatch [:ws/notify :open]]
                                 [:http/post {:url "/ws/registered"}]]}
                           nil
                           [[:dispatch [:ws/notify :open]]
                            [:http/post {:url "/ws/registered"}]])
        ;; --- always phase ---
        (machine-action-ev :ws/heartbeat-pulse
                           :always
                           {:beat true}
                           nil
                           nil)
        ;; --- after-action ---
        (machine-action-ev :ws/retry-failover-arm
                           :after-action
                           {:armed true}
                           nil
                           nil)
        (db-changed-ev [[[:rf/runtime :machines :snapshots :ws/connection :state]
                         :connecting :open :modified]])
        (do-fx-ev {:db ::placeholder
                   :dispatch [:ws/notify :open]
                   :http/post {:url "/ws/registered"}})
        (fx-handled-ev :db nil 0.2)
        (fx-handled-ev :dispatch [:ws/notify :open] 0.1)
        (fx-handled-ev :http/post {:url "/ws/registered"} 4.2)
        (run-end-ev 2.1)]})))

;; ---- VARIANT 4: SIDE EFFECTS `:db` schema-fail rollback (rf2-kt6js) ------
;;
;; Section coverage: the SIDE EFFECTS step's `:db` sub-step painting the
;; ✗ schema-fail rollback (rf2-kt6js + rf2-8resu inline-violation
;; attachment). The `:where :app-db` runtime boundary failure attaches
;; to the SIDE EFFECTS `:db` row (`attach-to-fx-db-row`) so the operator
;; reads the failing boundary INLINE with the rejected commit:
;;
;;   - `db-commit?` is true (the `:rollback? true` violation implies a
;;     commit was attempted), so the SIDE EFFECTS step appears with its
;;     `:db` row.
;;   - `db-effect-row`'s `:status` is `:error` (`db-rolled-back?`), so
;;     the per-effect tick + the sub-step + the step header all paint ✗.
;;   - the `:app-db` violation's reason box rides the `:db` row.
;;   - `mark-rolled-back-downstream` mutes SUBSCRIPTIONS / VIEWS chrome.
;;   - the epoch `:outcome` reads `:error` (a step settled `:error`), so
;;     the panel-root `data-rf-xray-outcome` flips error.
;;
;; The rollback cascade carries NO user `:fx` rows (per Spec 010 the
;; `:fx` walk doesn't run when the commit rolls back) — so the SIDE
;; EFFECTS step here carries ONLY the visibly-red `:db` sub-step.

(defn schema-violations-history
  "Cascade where the app-db boundary schema rejected the handler's `:db`
  write and rolled the cascade back. Exercises the SIDE EFFECTS step's
  `:db` ✗ schema-fail state (rf2-kt6js): the `:where :app-db` violation
  attaches to the `:db` row, the step + sub-step + per-effect tick all
  paint ✗, and SUBSCRIPTIONS / VIEWS mute downstream."
  []
  (single-epoch-history
    {:epoch-id  4
     :event     [:counter/set "not-a-number"]
     ;; The write was rejected + rolled back — the committed db is
     ;; unchanged (db-before == db-after); the HANDLER `:db` block shows
     ;; the rejected post-handler value (t1) the boundary refused.
     :db-before {:counter 5}
     :db-after  {:counter 5}
     :trace-events
     [(dispatched-ev [:counter/set "not-a-number"] :ui)
      ;; the handler RETURNED a `:db` (t1) — its `:counter` slot carries
      ;; the string the app-db boundary then rejected.
      (db-pending-ev {:counter "not-a-number"})
      ;; Validation failure: the handler tried to set :counter to a
      ;; string; the app-db boundary schema rejected the write and
      ;; rolled the cascade back. Attaches to the SIDE EFFECTS step's
      ;; `:db` row (rf2-8resu / rf2-kt6js) + flips downstream steps muted.
      (schema-violation-ev :app-db :counter/set [:counter]
                           "not-a-number" true)
      (run-end-ev 0.3)]}))

;; ---- VARIANT 5: child-dispatching cascade -------------------------------
;;
;; Section coverage: CHILD DISPATCHES step
;; (`:dispatch / :dispatch-n / :dispatch-later`). The fixture also
;; pre-pends an OLDER epoch (the `:cart/add` child sitting in the
;; buffer) so the panel's `find-child-epoch` joins the parent→child
;; link; the `:audit/log` child has aged out of the buffer (rendered
;; with the "not in buffer" muted marker) — i.e. no matching
;; epoch-record carries the parent dispatch-id.

(defn child-dispatches-history
  "Multi-record history: one resolved child cascade in the buffer +
  one parent cascade whose handler returns `:dispatch` /
  `:dispatch-n` / `:dispatch-later` fx. The parent's
  CHILD-DISPATCHES section shows all four rows; only the
  `:cart/add` row resolves to an epoch-id (the others fall through
  to the muted not-in-buffer marker because no child epoch records
  carry the matching parent dispatch-id)."
  []
  [;; OLDER child epoch — present in the buffer, parent-dispatch-id
   ;; matches the parent's :dispatch-id below. This is the row the
   ;; CHILD-DISPATCHES section resolves to a "jump to" affordance.
   (-> (epoch-record
         {:epoch-id 50
          :event    [:cart/add :apple]
          :trace-events
          [(dispatched-ev [:cart/add :apple] :ui)
           (db-changed-ev [[[:cart :items] [] [{:id :apple}] :modified]])
           (run-end-ev 0.4)]})
       (assoc :parent-dispatch-id 9001))
   ;; PARENT cascade — emits three flavours of child dispatch.
   (-> (epoch-record
         {:epoch-id    51
          :event       [:checkout/begin]
          :dispatch-id 9001
          :trace-events
          [(dispatched-ev [:checkout/begin] :ui)
           (db-changed-ev [[[:checkout :phase] :idle :began :modified]])
           (do-fx-ev {:db          ::placeholder
                      :dispatch    [:cart/add :apple]
                      :dispatch-n  [[:audit/log :checkout/begin]
                                    [:metrics/emit :checkout/begin]]
                      :dispatch-later {:ms 250
                                       :dispatch [:checkout/retry-prompt]}})
           (fx-handled-ev :db nil 0.2)
           (fx-handled-ev :dispatch [:cart/add :apple] 0.1)
           (fx-handled-ev :dispatch-n [[:audit/log :checkout/begin]
                                       [:metrics/emit :checkout/begin]] 0.2)
           (fx-handled-ev :dispatch-later
                          {:ms 250 :dispatch [:checkout/retry-prompt]} 0.1)
           (run-end-ev 1.4)]}))])

;; ---- VARIANT 6: long-step cascade ---------------------------------------
;;
;; Section coverage: 16ms long-step warning chrome — the `▲` glyph +
;; warning tone fires when any single step's `:duration-ms` exceeds
;; `proj/long-step-threshold-ms` (= 16). The fixture stamps a 42ms
;; HANDLER (the cascade-driving duration) + a 28ms fx call, so two
;; per-step duration chips carry the long-step chrome.

(defn long-step-history
  "Cascade with a 42ms handler + 28ms fx — both above the 16ms
  threshold. Drives the long-step warning chrome on the per-step
  duration chips."
  []
  (single-epoch-history
    {:epoch-id 5
     :event    [:report/recompute-all {:tenant :acme}]
     :trace-events
     [(dispatched-ev [:report/recompute-all {:tenant :acme}] :ui)
      (db-changed-ev [[[:report :status] :idle :recomputing :modified]
                      [[:report :results] {} {:tenant :acme :rows 1000} :modified]])
      (do-fx-ev {:db ::placeholder
                 :http/post {:url "/api/report/recompute"}})
      (fx-handled-ev :db nil 0.4)
      (fx-handled-ev :http/post {:url "/api/report/recompute"} 28)
      (sub-run-ev [:report/results] true {} {:tenant :acme :rows 1000})
      (view-rendered-ev :report/table [[:report/results]] 18)
      ;; The HANDLER's duration is the dominant long-step (42ms — well
      ;; above 16ms). The view also crosses the threshold for the
      ;; render row.
      (run-end-ev 42)]}))

;; ---- VARIANT 7: flow-firing cascade -------------------------------------
;;
;; Section coverage: FLOW step. Three flow-recomputed events ride
;; the cascade after the handler returns — the framework runs flows
;; at the outermost :after, transforming the pending :db before the
;; single deferred install (per `re-frame.flow`).
;;
;; rf2-xnb1x: the projection splats each flow into its OWN numbered
;; FLOW step (mirror of the per-cofx COEFFECT split). This fixture
;; renders THREE first-class FLOW steps in the cascade — operator
;; counts flows by counting circles, not rows-in-a-step.

(defn flows-history
  "Cascade triggering three downstream flows — `:cart/total`,
  `:cart/item-count`, `:cart/badge`. Drives THREE numbered FLOW
  steps in the cascade per the rf2-xnb1x per-flow split."
  []
  (single-epoch-history
    {:epoch-id 6
     :event    [:cart/add :pear]
     :trace-events
     [(dispatched-ev [:cart/add :pear] :ui)
      (db-changed-ev [[[:cart :items]
                       [{:id :apple :qty 1}]
                       [{:id :apple :qty 1} {:id :pear :qty 1}]
                       :modified]])
      (flow-recomputed-ev :cart/total       [:cart :total]
                          120 195)
      (flow-recomputed-ev :cart/item-count  [:cart :item-count]
                          1 2)
      (flow-recomputed-ev :cart/badge       [:cart :badge]
                          "1 item" "2 items")
      (do-fx-ev {:db ::placeholder})
      (fx-handled-ev :db nil 0.3)
      (sub-run-ev [:cart/total] true 120 195)
      (view-rendered-ev :cart/badge-view [[:cart/badge]] 0.6)
      (run-end-ev 0.9)]}))

;; ---- empty + no-focus auxiliary fixtures --------------------------------
;;
;; Two non-section-specific variants the gallery includes so the
;; panel's empty-state copy is also visible at a glance.

(defn empty-history
  "No epochs at all — drives the panel's `:no-focus` empty-state line
  ('No epoch focused. Dispatch an event to see the cascade.')."
  []
  [])

(defn no-events-history
  "One epoch whose `:trace-events` slice is empty. The projection
  returns an empty step vector; the panel renders the
  cold-pipeline empty-state without crashing."
  []
  (single-epoch-history
    {:epoch-id 7
     :event    [:counter/init]
     :trace-events []}))

;; ---- VARIANT 11: disposed-subs cascade (rf2-wpfjo) ----------------------
;;
;; Section coverage: SUBSCRIPTIONS step's DISPOSED sub-section
;; (rf2-wpfjo). A route change tears down two view-instances; their
;; subs lose their last derefer and evict (`:no-more-derefers`). A
;; third sub was re-registered (`:hot-reload`) so the cache cleared.
;; One sub recomputes (the new route's root sub).

(defn disposed-subs-history
  "Cascade where three sub-cache entries are evicted (two by
  `:no-more-derefers` after the prior route's views unmounted, one
  by `:hot-reload` after a re-registration) while one sub recomputes.
  Exercises the SUBSCRIPTIONS step's DISPOSED sub-section (rf2-wpfjo)
  with the full `:reason` closed set."
  []
  (single-epoch-history
    {:epoch-id 11
     :event    [:route/change :reports]
     :trace-events
     [(dispatched-ev [:route/change :reports] :ui)
      (db-changed-ev [[[:route] :checkout :reports :modified]])
      (sub-run-ev [:route/current] true :checkout :reports)
      (sub-dispose-ev [:checkout/total]       :no-more-derefers)
      (sub-dispose-ev [:checkout/line-items]  :no-more-derefers)
      (sub-dispose-ev [:report/threshold-cfg] :hot-reload)
      (run-end-ev 0.7)]}))

;; ---- VARIANT 10: unmounted-views cascade (rf2-gmw1i) --------------------
;;
;; Section coverage: VIEWS step's UNMOUNTED sub-section (rf2-gmw1i).
;; A route change tears down two views; one new view mounts. Exercises
;; the `N re-rendered; M unmounted` header counter + the per-row red
;; `✗` glyph chrome on the unmounted entries.

(defn unmounted-views-history
  "Cascade where two view instances unmount (route change tears down
  the modal + a sidebar item) while one new view re-renders.
  Exercises the VIEWS step's UNMOUNTED sub-section (rf2-gmw1i)."
  []
  (single-epoch-history
    {:epoch-id 10
     :event    [:route/change :dashboard]
     :trace-events
     [(dispatched-ev [:route/change :dashboard] :ui)
      (db-changed-ev [[[:route] :checkout :dashboard :modified]])
      (sub-run-ev [:route/current] true :checkout :dashboard)
      (view-rendered-ev :app.dashboard/Page [[:route/current]] 1.1)
      (view-unmounted-ev :app.checkout/Modal      [:Modal 0])
      (view-unmounted-ev :app.sidebar/CheckoutItem [:CheckoutItem 0])
      (run-end-ev 0.6)]}))

;; ---- rf2-5qp4g — DISPATCH source-kind enrichment fixtures ----------------
;;
;; Each closed-set substrate-internal `:source` value (rf2-ejtpd:
;; `:after-timer`, `:machine-spawn`, `:fx-dispatch`,
;; `:fx-dispatch-later`) produces a different rich label on the
;; DISPATCH step. One fixture per kind exercises the projection +
;; renderer path for that kind.

(defn- dispatched-ev-rich
  "Tag-rich dispatched trace primitive — preserves the canonical
  `:rf.event/v` + `:source` of `dispatched-ev` AND admits extra tags
  the substrate stamps for source-kind enrichment (parent-dispatch-id,
  source-detail). Mirrors the shape `re-frame.router/emit-dispatched-
  trace` produces under rf2-ejtpd."
  [event source extra-tags]
  (ev :rf.event :rf.event/dispatched
      (merge {:rf.event/v event :source source} extra-tags)))

(defn after-timer-source-history
  "Cascade triggered by a machine `:after` timer firing (rf2-ejtpd ·
  rf2-5qp4g). The dispatched event is the synthetic
  `[:rf.machine.timer/after-elapsed 250 <epoch> [:active :authenticating]]`
  payload the substrate dispatches when the timer's delay elapses;
  `:source :after-timer` rides the envelope.

  Exercises the DISPATCH step's `from :after timer · 250ms on
  [:active :authenticating]` rich chrome."
  []
  (single-epoch-history
    {:epoch-id 12
     :event    [:ws/connection
                [:rf.machine.timer/after-elapsed 250 41
                 [:active :authenticating]]]
     :trace-events
     [(dispatched-ev-rich
        [:ws/connection
         [:rf.machine.timer/after-elapsed 250 41
          [:active :authenticating]]]
        :after-timer
        {:rf.trace/call-site {:file "src/app/ws.cljs" :line 22}})
      (run-end-ev 0.3)]}))

(defn machine-spawn-source-history
  "Cascade triggered by a spawn fx — substrate dispatches the spawned
  actor's first event with `:source :machine-spawn` (rf2-ejtpd ·
  rf2-5qp4g). The synthetic-default path emits the spawned-id +
  `[:rf.machine/spawned]` payload (rf2-ijm7).

  Exercises the DISPATCH step's `from machine spawn ·
  :checkout/worker` rich chrome."
  []
  (single-epoch-history
    {:epoch-id 13
     :event    [:checkout/worker [:rf.machine/spawned]]
     :trace-events
     [(dispatched-ev-rich
        [:checkout/worker [:rf.machine/spawned]]
        :machine-spawn
        {:rf.trace/call-site {:file "src/app/checkout.cljs" :line 88}})
      (db-changed-ev [[[:checkout :workers :worker-1] nil
                       {:id :worker-1 :state :idle} :added]])
      (run-end-ev 0.4)]}))

(defn fx-dispatch-source-history
  "Multi-record history: a parent cascade whose handler returned a
  `:dispatch` fx, plus the resulting child cascade (rf2-ejtpd ·
  rf2-5qp4g). The child's `:source :fx-dispatch` rides with
  `:rf.trace/parent-dispatch-id` pointing back to the parent
  cascade's `:dispatch-id` so the DISPATCH step's parent-epoch chip
  resolves and renders as a click-to-navigate button."
  []
  [;; PARENT cascade — emits the :dispatch fx
   (-> (epoch-record
         {:epoch-id    20
          :event       [:checkout/begin]
          :dispatch-id 9001
          :trace-events
          [(dispatched-ev [:checkout/begin] :ui)
           (db-changed-ev [[[:checkout :phase] :idle :began :modified]])
           (do-fx-ev {:dispatch [:cart/add :apple]})
           (fx-handled-ev :dispatch [:cart/add :apple] 0.1)
           (run-end-ev 0.5)]}))
   ;; CHILD cascade — emitted by the :dispatch fx; the panel
   ;; head-fallback (`peek epoch-history`) lands on this record so
   ;; the DISPATCH renderer reads `:source :fx-dispatch` with
   ;; parent-dispatch-id 9001 → parent-epoch-id 20.
   (-> (epoch-record
         {:epoch-id    21
          :event       [:cart/add :apple]
          :dispatch-id 9002
          :trace-events
          [(dispatched-ev-rich
             [:cart/add :apple]
             :fx-dispatch
             {:rf.trace/parent-dispatch-id 9001})
           (db-changed-ev [[[:cart :items] [] [{:id :apple}] :modified]])
           (run-end-ev 0.3)]})
       (assoc :parent-dispatch-id 9001))])

(defn fx-dispatch-later-source-history
  "Multi-record history: a parent cascade whose handler returned a
  `:dispatch-later` fx, plus the resulting child cascade fired by
  the timer (rf2-ejtpd · rf2-5qp4g).

  The child's dispatched trace carries `:source :fx-dispatch-later`
  + `:rf.trace/parent-dispatch-id` 9001 + the rich
  `:rf.event/source-detail {:ms 500}` tag so the DISPATCH step
  renders the original scheduled delay alongside the kind label and
  the parent-epoch link."
  []
  [;; PARENT cascade — emits the :dispatch-later fx
   (-> (epoch-record
         {:epoch-id    22
          :event       [:checkout/begin]
          :dispatch-id 9001
          :trace-events
          [(dispatched-ev [:checkout/begin] :ui)
           (do-fx-ev {:dispatch-later
                      {:ms 500 :dispatch [:checkout/retry-prompt]}})
           (fx-handled-ev :dispatch-later
                          {:ms 500 :dispatch [:checkout/retry-prompt]}
                          0.1)
           (run-end-ev 0.4)]}))
   ;; CHILD cascade — fired 500ms later by the timer
   (-> (epoch-record
         {:epoch-id    23
          :event       [:checkout/retry-prompt]
          :dispatch-id 9003
          :trace-events
          [(dispatched-ev-rich
             [:checkout/retry-prompt]
             :fx-dispatch-later
             {:rf.trace/parent-dispatch-id 9001
              :rf.event/source-detail      {:ms 500}})
           (db-changed-ev [[[:checkout :phase] :began :prompting :modified]])
           (run-end-ev 0.5)]})
       (assoc :parent-dispatch-id 9001))])

(defn fx-dispatch-orphaned-source-history
  "Child cascade with `:source :fx-dispatch` whose parent has aged
  out of the buffer (rf2-5qp4g). Exercises the unresolved
  parent-epoch chrome — a muted plain span carrying the
  parent-dispatch-id so the operator sees the lineage even when the
  parent isn't in the ring."
  []
  (single-epoch-history
    {:epoch-id 24
     :event    [:cart/sync]
     :trace-events
     [(dispatched-ev-rich
        [:cart/sync]
        :fx-dispatch
        ;; No matching parent epoch in this single-record history
        {:rf.trace/parent-dispatch-id 99999})
      (run-end-ev 0.2)]}))

;; ---- VARIANT 17: handler-threw EXCEPTION (rf2-ahhgn · rf2-wnvid) ---------
;;
;; Section coverage: the inline "Exception Thrown" block (rf2-ahhgn,
;; polished by rf2-wnvid). A handler threw BEFORE returning, so:
;;
;;   - the projection's `attach-exceptions` attaches the
;;     `:rf.error/handler-exception` to the HANDLER step + stamps it
;;     `:status :error` → the HANDLER header paints ✗ and the
;;     `epoch-outcome` flips `:error` (panel-root `data-rf-xray-outcome`).
;;   - the HANDLER `:db` sub-section reads `db-write?` FALSE (no t1, no
;;     `:rf.event/db-changed`) → renders the `— no :db (handler threw)`
;;     placeholder, NOT the phantom full post-cascade app-db (the
;;     rf2-wnvid PHANTOM-`:db` fix).
;;   - the error card's `Rolled back` recovery chip stays OFF: there is
;;     no `:rf.event/db-changed`, so `db-committed?` is false — NO
;;     SPURIOUS 'Rolled back' (the rf2-wnvid contract).
;;   - the card's collapsible `<details>` discloses the raw exception's
;;     `.stack` + `ex-data` (rf2-wnvid) — seeded via a real `ex-info`.
;;
;; SETTLE-FIRST (rf2-ahhgn): the framework recovers a handler throw and
;; settles the epoch-record `:outcome :ok`; the panel's `:error` outcome
;; is DERIVED from the trace stream (`epoch-outcome`), NOT that slot —
;; so the record carries NO non-`:ok` framework outcome here.

(defn exception-history
  "Cascade where the event handler threw before returning (the
  `:rf.error/handler-exception` path). Exercises the inline 'Exception
  Thrown' card — message + collapsible stack / ex-data — with NO
  phantom `:db` and NO spurious 'Rolled back' chip (the handler threw
  before any commit, so `db-committed?` is false). Drives the
  `:outcome :error` panel-root flip (rf2-ahhgn)."
  []
  (single-epoch-history
    {:epoch-id 25
     :event    [:checkout/charge {:amount 4200 :currency :usd}]
     ;; db-before == db-after — the handler mutated nothing (it threw).
     :db-before {:checkout {:phase :reviewing}}
     :db-after  {:checkout {:phase :reviewing}}
     :trace-events
     [(dispatched-ev [:checkout/charge {:amount 4200 :currency :usd}] :ui)
      ;; NO db-pending (t1) + NO db-changed — the handler threw before
      ;; returning a `:db`, so `db-write?` is false (no phantom :db) and
      ;; `db-committed?` is false (no spurious 'Rolled back').
      (handler-exception-ev
        :checkout/charge
        "No payment gateway configured for currency :usd"
        {:file "src/app/checkout.cljs" :line 142}
        (ex-info "No payment gateway configured for currency :usd"
                 {:amount 4200 :currency :usd :gateways [:eur :gbp]}))
      (run-end-ev 0.4)]}))

;; ---- VARIANT 18: fx-handler-threw EXCEPTION (rf2-ahhgn) ------------------
;;
;; Section coverage: a registered fx-handler threw during the post-commit
;; fx walk (`:rf.error/fx-handler-exception`). Distinct from VARIANT 17
;; (a HANDLER throw): here the `:db` commit + the cascade SUCCEEDED, and
;; the throw is attributed to a SIDE EFFECTS `:fx` ROW:
;;
;;   - `attach-to-fx-error-row` matches `:failing-id` against the `:fx`
;;     row's `:fx-id` → the 'Exception Thrown' card renders INLINE on
;;     that fx row, and the row's per-effect tick paints ✗.
;;   - the SIDE EFFECTS step gains `:status :error` → step header ✗ +
;;     the threw-count chip reads `1 threw`.
;;   - because the `:db` DID commit (a `:rf.event/db-changed` rode), the
;;     card's `Rolled back` chip stays OFF — re-frame2's fx atomicity is
;;     pre-commit-transactional / post-commit-best-effort (a post-commit
;;     fx throw does NOT roll the committed `:db` back; rf2-wnvid).

(defn fx-exception-history
  "`reg-event-fx` cascade whose `:db` committed cleanly but a post-commit
  `:fx` handler (`:email/send`) threw. Exercises the SIDE EFFECTS step's
  per-`:fx`-row 'Exception Thrown' card (`attach-to-fx-error-row`), the
  row's ✗ tick, the `1 threw` header chip, and the NO-'Rolled back'
  contract (the committed `:db` is not rolled back on a post-commit fx
  throw — rf2-wnvid)."
  []
  (single-epoch-history
    {:epoch-id  26
     :event     [:invite/send {:to "ada@example.com"}]
     :db-before {:invites []}
     :db-after  {:invites [{:to "ada@example.com" :status :queued}]}
     :trace-events
     [(dispatched-ev [:invite/send {:to "ada@example.com"}] :ui)
      (db-pending-ev {:invites [{:to "ada@example.com" :status :queued}]})
      (db-changed-ev [[[:invites] [] [{:to "ada@example.com" :status :queued}]
                       :modified]])
      (do-fx-ev {:db ::placeholder
                 :fx [[:email/send {:to "ada@example.com" :template :invite}]]})
      ;; the :email/send fx handler threw — attaches to the matching
      ;; SIDE EFFECTS :fx row (failing-id :email/send) as the ✗ tick +
      ;; the inline 'Exception Thrown' card.
      (fx-handler-exception-ev
        :email/send
        "SMTP connection refused (smtp.example.com:587)"
        {:file "src/app/email.cljs" :line 58})
      (run-end-ev 1.1)]}))

;; ---- VARIANT 19: subscriptions caused-by + input-signals (rf2-1cc03 ·
;;                  rf2-87c8a) --------------------------------------------
;;
;; Section coverage: the SUBSCRIPTIONS table's `caused by <event-id>`
;; cell (rf2-1cc03) + the `inputs` column's static input-signal topology
;; (rf2-87c8a). A layer-2 derived sub recomputes because an upstream
;; layer-1 sub's value changed:
;;
;;   - `:rf.sub/cause-event-id` rides each row → the `caused by
;;     :cart/add` chrome renders below the sub-id (testid
;;     `rf-xray-epoch-sub-row-cause-event-id-N`).
;;   - `:rf.sub/inputs` rides the derived rows → the `inputs` column
;;     paints the upstream sub-ids. In the gallery's bare-panel mount
;;     the subs aren't registered, so `sub-input-signals`' live
;;     `(rf/handler-meta :sub …)` lookup returns nil and the view falls
;;     back to the row's `:inputs` slot (the rf2-87c8a fallback arm) —
;;     which is exactly what surfaces the upstream sub-ids in the gallery.
;;   - the layer-1 root sub (`:cart/items`) carries NO `:inputs` → its
;;     inputs cell reads the `app-db` Level-1 label (the contrast the
;;     column is designed to show).

(defn caused-by-subs-history
  "Cascade where a `:cart/add` handler invalidates `:cart/items`
  (layer-1, reads app-db) which cascades to two derived subs
  (`:cart/total`, `:cart/badge`). Exercises the SUBSCRIPTIONS table's
  `caused by <event-id>` cell (rf2-1cc03) + the static `:input-signals`
  inputs column (rf2-87c8a) — the layer-1 root reads `app-db`, the
  derived subs name their upstream input + the invalidating event."
  []
  (single-epoch-history
    {:epoch-id  27
     :event     [:cart/add :pear]
     :db-before {:cart {:items [{:id :apple :qty 1}]}}
     :db-after  {:cart {:items [{:id :apple :qty 1} {:id :pear :qty 1}]}}
     :trace-events
     [(dispatched-ev [:cart/add :pear] :ui)
      (db-pending-ev {:cart {:items [{:id :apple :qty 1} {:id :pear :qty 1}]}})
      (db-changed-ev [[[:cart :items]
                       [{:id :apple :qty 1}]
                       [{:id :apple :qty 1} {:id :pear :qty 1}]
                       :modified]])
      ;; layer-1 root sub — reads app-db directly (no :inputs → the
      ;; inputs column reads the `app-db` Level-1 label).
      (sub-run-ev [:cart/items] true
                  [{:id :apple :qty 1}]
                  [{:id :apple :qty 1} {:id :pear :qty 1}]
                  {:cause-event-id :cart/add})
      ;; layer-2 derived — input-signal [:cart/items]; recomputed because
      ;; :cart/items changed, attributed to the :cart/add cascade.
      (sub-run-ev [:cart/total] true 120 195
                  {:cause-event-id :cart/add
                   :inputs         [[:cart/items]]})
      ;; layer-3 derived — input-signal [:cart/total]; same cause event.
      (sub-run-ev [:cart/badge] true "1 item" "2 items"
                  {:cause-event-id :cart/add
                   :inputs         [[:cart/total]]})
      (run-end-ev 0.6)]}))

;; ---- VARIANT 20: handler-`:db` vs flow-`:db`-diff (rf2-4wywy · rf2-48oc4)
;;
;; Section coverage: the HANDLER step's `:db` diff (the handler's OWN
;; contribution, post-handler / PRE-flow == t1) rendered as a SEPARATE
;; step from the FLOW step's `:db` diff (the flow's t1→t2 reshape):
;;
;;   - the handler wrote `[:cart :items]` (t1 = db-before + the new item).
;;   - a `:cart/total` flow then recomputed `[:cart :total]` at the
;;     outermost `:after`, producing t2 (the flow-augmented db).
;;   - the record's `:db-after` is the FINAL post-flow state (t2).
;;   - the HANDLER `:db` block reads `:db-post-handler` (t1) so it shows
;;     ONLY `[:cart :items]` changing — NOT the flow's `[:cart :total]`.
;;   - the FLOW step reads the t1→t2 pair (`:db-pre-flow` / `:db-post-flow`)
;;     so it renders the `[:cart :total] 120 → 195` reshape as its OWN
;;     `:db` diff — the two contributions are no longer conflated
;;     (the rf2-4wywy bug).

(defn handler-flow-db-history
  "Cascade where the handler writes `[:cart :items]` and a downstream
  `:cart/total` flow then writes `[:cart :total]`. Exercises the
  separation (rf2-4wywy / rf2-48oc4) of the HANDLER step's `:db` diff
  (handler-only, t1) from the FLOW step's `:db` diff (the flow's t1→t2
  reshape) — two distinct numbered steps, each scoped to its own change."
  []
  (let [db-before {:cart {:items [{:id :apple :qty 1}] :total 120}}
        t1        {:cart {:items [{:id :apple :qty 1} {:id :pear :qty 1}]
                          :total 120}}
        t2        {:cart {:items [{:id :apple :qty 1} {:id :pear :qty 1}]
                          :total 195}}]
    (single-epoch-history
      {:epoch-id  28
       :event     [:cart/add :pear]
       :db-before db-before
       ;; the record's :db-after is the FINAL post-flow state (t2).
       :db-after  t2
       :trace-events
       [(dispatched-ev [:cart/add :pear] :ui)
        ;; t1 — POST-handler, PRE-flow: the handler wrote :items only.
        (db-pending-ev t1)
        (db-changed-ev [[[:cart :items]
                         [{:id :apple :qty 1}]
                         [{:id :apple :qty 1} {:id :pear :qty 1}]
                         :modified]])
        ;; the :cart/total flow recomputed at the outermost :after.
        (flow-recomputed-ev :cart/total [:cart :total] 120 195)
        ;; t2 — POST-flow, PRE-commit: the flow-augmented :total.
        (db-pending-post-flow-ev t2)
        (run-end-ev 0.7)]})))
