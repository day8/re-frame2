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
  (:require [re-frame.elision :as elision]
            [re-frame.flows.registry :as registry]
            [re-frame.flows.topo :as topo]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
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

;; ---- evaluation ---------------------------------------------------------
;;
;; Called from the per-event drain after :db commits and before :fx runs.

(defn- read-inputs [db flow]
  (mapv (fn [path] (get-in db path)) (:inputs flow)))

(defn- evaluate-flow!
  "Evaluate one flow against the given db. Returns the `[new-db dirty?]`
  tuple. On the failure path the exception re-throws after the
  `:rf.flow/failed` trace fires — `run-flows!`'s loop exits and the
  router's outer catch emits the cascade-level
  `:rf.error/flow-eval-exception`. Trace semantics live in Spec 009
  §Flow trace events; this docstring just names the failure-path
  unwind (rf2-rlmla Q11 — the prior docstring claimed `[db false]`
  was returned and downstream flows still walked, which contradicts
  the actual `(throw e)` impl at the catch site below).

  Hot path — runs after every event drain for every registered flow.
  Trace payload construction sits inside an `interop/debug-enabled?`
  outer gate so the elision walker (`elide-wire-value`) is not invoked
  in CLJS production builds (per Spec 009 §Production builds, rf2-drr4z
  perf slice). Future editors: keep the gate OUTERMOST on each emit
  site and keep wire-value walks INSIDE it — Closure DCE folds the
  whole branch under `:advanced` + `goog.DEBUG=false`."
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
        ;; nothing wrote". Outer debug-enabled? gate elides the tag-map
        ;; construction in prod (rf2-drr4z).
        (when interop/debug-enabled?
          (trace/emit! :flow :rf.flow/skip
                       {:flow-id flow-id
                        :reason  :inputs-value-equal
                        :frame   frame-id}))
        [db false])
      (try
        (let [new-output (apply (:output flow) new-inputs)
              new-db     (assoc-in db (:path flow) new-output)]
          (swap! last-inputs assoc-in [flow-id frame-id] new-inputs)
          ;; Per Spec 009 §:op-type vocabulary: :rf.flow/computed records
          ;; a successful recompute. Per rf2-719e the dirty-check is
          ;; =-equality so this only fires when inputs actually changed.
          ;;
          ;; Wire-bearing payloads (`:input-values`, `:result`) ride
          ;; through `elision/elide-wire-value` per Spec 009 §Size
          ;; elision in traces — the walker is the single normative
          ;; emission site for `:rf.size/large-elided` (and the
          ;; `:rf/redacted` privacy sentinel). Without this the flow
          ;; trace bypassed the elision contract that every other
          ;; wire-emitting surface honours; a flow reading or
          ;; producing a large or sensitive value would surface raw
          ;; on the trace bus. Off-box defaults match `event-emit` /
          ;; `error-emit`.
          ;;
          ;; The `:path` opt on each walker call names where in the
          ;; slice's root the wrapped value lives — `:result` IS the
          ;; value at the flow's output path; each `:input-values`
          ;; entry is the value at the matching input path. The
          ;; walker reads `[:rf/elision :declarations <path>]` and
          ;; emits the marker for declared-large or auto-detected-
          ;; large slots.
          ;;
          ;; Outer `interop/debug-enabled?` gate keeps the elision
          ;; walker out of CLJS prod builds (rf2-drr4z) — Closure
          ;; constant-folds the whole branch under `:advanced` +
          ;; `goog.DEBUG=false`.
          (when interop/debug-enabled?
            (trace/emit! :flow :rf.flow/computed
                         {:flow-id      flow-id
                          :input-values (mapv (fn [input-path v]
                                                (elision/elide-wire-value
                                                  v
                                                  {:frame frame-id :path input-path}))
                                              (:inputs flow)
                                              new-inputs)
                          :result       (elision/elide-wire-value
                                          new-output
                                          {:frame frame-id :path (:path flow)})
                          :path         (:path flow)
                          :frame        frame-id}))
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
          ;;
          ;; The failure-path `:inputs` payload rides through the
          ;; elision walker for the same reason as the success path —
          ;; the value that triggered the throw may itself be a
          ;; large or sensitive blob, and the trace bus is the wire
          ;; boundary. Each entry is walked under its own declared
          ;; input path so per-path declarations apply. Outer debug-
          ;; enabled? gate elides the walk in CLJS prod (rf2-drr4z).
          (when interop/debug-enabled?
            (trace/emit! :flow :rf.flow/failed
                         {:flow-id flow-id
                          :ex      e
                          :inputs  (mapv (fn [input-path v]
                                           (elision/elide-wire-value
                                             v
                                             {:frame frame-id :path input-path}))
                                         (:inputs flow)
                                         new-inputs)
                          :frame   frame-id}))
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
(late-bind/set-fn! :flows/run-flows!         run-flows!)
(late-bind/set-fn! :flows/reset-last-inputs! reset-last-inputs!)
(late-bind/set-fn! :flows/reset-flows!       reset-flows!)
