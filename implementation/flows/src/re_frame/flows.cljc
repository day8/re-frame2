(ns re-frame.flows
  "Flows — registered, runtime-toggleable computed-state declarations.
  Per Spec 013.

  A flow says: 'when these app-db paths change, run this pure function
  and write the result to that app-db path.' Flows evaluate after every
  event drain in topological order over their static dependency graph.

  Flows are deliberately a NICHE convenience — not a sub replacement,
  not a new dataflow paradigm. Use a sub if the value is consumed by
  views; use a flow only if it must live in app-db for SSR / time-travel
  / inspector reasons.

  ## Artefact (rf2-tfw3, fourth per-feature split per rf2-5vjj Strategy B)

  This namespace ships in `day8/re-frame2-flows`, separate from the
  core artefact (`day8/re-frame2`). The core artefact's `re-frame.core`
  re-exports of `reg-flow` / `clear-flow`, and the `:rf.fx/reg-flow` /
  `:rf.fx/clear-flow` runtime fxs in `re-frame.fx`, look this
  namespace's entry points up via the `re-frame.late-bind` hook table —
  loading this namespace publishes the hooks. Apps that don't register
  any flows don't drag the per-frame flow registry, the topological-
  sort engine, the dirty-check `last-inputs` map, or the post-drain
  `run-flows!` walker onto the classpath.

  ## Internal layout (rf2-mnu8z)

  Per the rf2-zkca8 leaf-size ceiling the original 431-LoC monolith
  was split along its three natural seams; this namespace is now the
  public FAÇADE — it owns the post-drain evaluation walker, the fx-
  call indirections, and the late-bind hook publications, and re-
  exports the registry's public-surface symbols. The split is:

    - `re-frame.flows.topo`     — pure Kahn's topological sort +
      cycle-path extraction. Unit-testable in isolation, no atoms,
      no traces.
    - `re-frame.flows.registry` — per-frame `flows` + `last-inputs`
      atoms, validation, `reg-flow` / `clear-flow`, the registrar
      replacement-hook for hot-reload invalidation, and the
      test-only `reset-flows!` / `reset-last-inputs!` resets.
    - `re-frame.flows` (this)   — `evaluate-flow!` / `run-flows!`,
      fx-call indirections, late-bind hook publication, and the
      public re-export surface.

  External consumers continue to reach every documented symbol at
  `re-frame.flows/<name>` — production code via the late-bind hooks,
  the per-artefact test fixtures via `re-frame.flows/flows` and
  `(resolve 're-frame.flows/last-inputs)`."
  (:require [re-frame.flows.registry :as registry]
            [re-frame.flows.topo :as topo]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

;; ---- public-surface re-exports -------------------------------------------
;;
;; Atoms re-exported as Vars so test fixtures across artefacts
;; (`flows_test.clj`, `flows_trace_test.clj`, `smoke_test.clj`,
;; `epoch_test.clj`, `core_api_additions_test.clj`, `reg_view_test.clj`,
;; `source_coords_test.clj`, `ssr/test_fixture.clj`) keep working
;; against the SAME atom value at `re-frame.flows/flows` and
;; `re-frame.flows/last-inputs`. Several tests reach `last-inputs`
;; via `(resolve 're-frame.flows/last-inputs)`; that resolve must
;; continue to land on a var deref-equal to the registry's atom.

(def flows       registry/flows)
(def last-inputs registry/last-inputs)

(def reg-flow           registry/reg-flow)
(def clear-flow         registry/clear-flow)
(def reset-flows!       registry/reset-flows!)
(def reset-last-inputs! registry/reset-last-inputs!)

;; ---- fx hooks (called from re-frame.fx) ---------------------------------
;;
;; The :rf.fx/reg-flow / :rf.fx/clear-flow runtime fx receive a {:frame ...}
;; cofx via fx.cljc. Thread the frame through.

(defn reg-flow-fx!
  ([flow]      (reg-flow flow))
  ([flow opts] (reg-flow flow opts)))

(defn clear-flow-fx!
  ([id]      (clear-flow id))
  ([id opts] (clear-flow id opts)))

;; ---- evaluation ---------------------------------------------------------
;;
;; Called from the per-event drain after :db commits and before :fx runs.

(defn- read-inputs [db flow]
  (mapv (fn [path] (get-in db path)) (:inputs flow)))

(defn- evaluate-flow!
  "Evaluate one flow against the given db. Returns the [new-db dirty?] tuple.

  Emits one of the per-flow `:rf.flow/*` traces per call (per Spec 009
  §:op-type vocabulary, §Flow tracing): `:rf.flow/skip` when value-equal
  recompute suppression triggers, `:rf.flow/computed` on a successful
  recompute, or `:rf.flow/failed` when the flow's `:output` fn throws.
  On the failure path the in-flight db is returned unchanged and `dirty?`
  is `false` so downstream flows still walk."
  [frame-id db flow]
  (let [flow-id    (:id flow)
        new-inputs (read-inputs db flow)
        ;; `last-inputs` is shaped {flow-id {frame-id inputs}} so the
        ;; hot-reload invalidation hook can drop a flow's whole row
        ;; with one O(1) dissoc (rf2-2xq8w / PERF Q10). Per-frame
        ;; dirty-check windows stay independent.
        old-inputs (get-in @last-inputs [flow-id frame-id])]
    (if (= new-inputs old-inputs)
      (do
        ;; Per Spec 009 §:op-type vocabulary: :rf.flow/skip records the
        ;; suppressed recompute (per rf2-719e value-equal recompute
        ;; suppression). Tools use this to surface "flow ran but inputs
        ;; were stable" — distinct from "flow didn't fire at all because
        ;; nothing wrote".
        (trace/emit! :flow :rf.flow/skip
                     {:flow-id flow-id
                      :reason  :inputs-value-equal
                      :frame   frame-id})
        [db false])
      (try
        (let [new-output (apply (:output flow) new-inputs)
              new-db     (assoc-in db (:path flow) new-output)]
          (swap! last-inputs assoc-in [flow-id frame-id] new-inputs)
          ;; Per Spec 009 §:op-type vocabulary: :rf.flow/computed records
          ;; a successful recompute. :input-values are raw values (not
          ;; hashed) — the trace surface is dev-only and elided in
          ;; production, and downstream tools (10x flow panel) display
          ;; them. Per rf2-719e the dirty-check is =-equality so this
          ;; only fires when inputs actually changed.
          (trace/emit! :flow :rf.flow/computed
                       {:flow-id      flow-id
                        :input-values new-inputs
                        :result       new-output
                        :path         (:path flow)
                        :frame        frame-id})
          [new-db true])
        (catch #?(:clj Throwable :cljs :default) e
          ;; Per Spec 009 §:op-type vocabulary: :rf.flow/failed fires
          ;; when the flow's :output fn throws. last-inputs is NOT
          ;; advanced — so the flow will retry on the next drain rather
          ;; than silently caching a stale-or-missing output. We re-
          ;; throw so the router's outer catch (router.cljc) emits the
          ;; cascade-level :rf.error/flow-eval-exception per Spec 009
          ;; §Error contract; the per-flow `:rf.flow/failed` trace
          ;; emitted here adds the flow-attributed detail tools (10x
          ;; flow panel) consume.
          (trace/emit! :flow :rf.flow/failed
                       {:flow-id flow-id
                        :ex      e
                        :inputs  new-inputs
                        :frame   frame-id})
          (throw e))))))

(defn run-flows!
  "Per Spec 013 §Drain integration: walk THIS FRAME'S registered flows
  in topological order, dirty-check each one, recompute and write
  if inputs changed. Called from the per-event drain after :db commits.

  Flows are frame-scoped — only flows registered against frame-id run
  here, leaving sibling frames' flows untouched."
  [frame-id]
  (let [container (frame/get-frame-db frame-id)
        flow-map  (get @flows frame-id)]
    (when (seq flow-map)
      (let [ordered (topo/topo-sort flow-map)]
        (loop [remaining ordered
               db       (adapter/read-container container)
               any-dirty? false]
          (if (empty? remaining)
            (when any-dirty?
              (adapter/replace-container! container db))
            (let [flow (flow-map (first remaining))
                  [new-db dirty?] (evaluate-flow! frame-id db flow)]
              (recur (rest remaining) new-db (or any-dirty? dirty?)))))))))

;; ---- late-bind hook registration ----------------------------------------
;;
;; re-frame.core, re-frame.fx, re-frame.router and re-frame.test-support
;; need to call into flows but per rf2-tfw3 ship in the core artefact
;; — they cannot `:require` this namespace because the flows artefact
;; is optional (apps that don't register flows don't carry it). Publish
;; entry points through the late-bind hook registry; consumers look the
;; fns up at call time. See re-frame.late-bind.
;;
;; Calls are written as literal `set-fn!` invocations with a literal
;; keyword (one per line) — the late-bind drift gate
;; (`re-frame.late-bind-drift-test`) detects each publication via regex
;; over `implementation/**/src/**`, matching every other artefact's
;; publication block (schemas / machines / routing / http / ssr).

(late-bind/set-fn! :flows/reg-flow           reg-flow)
(late-bind/set-fn! :flows/clear-flow         clear-flow)
(late-bind/set-fn! :flows/reg-flow-fx!       reg-flow-fx!)
(late-bind/set-fn! :flows/clear-flow-fx!     clear-flow-fx!)
(late-bind/set-fn! :flows/run-flows!         run-flows!)
(late-bind/set-fn! :flows/reset-last-inputs! reset-last-inputs!)
(late-bind/set-fn! :flows/reset-flows!       reset-flows!)
