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

  Ships in `day8/re-frame2-flows`; entry points are published through
  `re-frame.late-bind` so the core artefact's `re-frame.core` re-exports
  reach them. Apps that don't register any flows don't pull the per-
  frame flow registry, the topological-sort engine, the dirty-check
  `last-inputs` map, or the post-drain `run-flows!` walker.

  Public façade over `re-frame.flows.topo` (pure Kahn's + cycle-path
  extraction) and `re-frame.flows.registry` (per-frame `flows` +
  `last-inputs` atoms, `reg-flow` / `clear-flow`)."
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
  "Evaluate one flow against the given db. Returns `[new-db dirty?]` on
  successful evaluation (skip or recompute); on the failure path the
  exception re-throws after the `:rf.flow/failed` trace fires.

  Failed-flow contract (rf2-wyt97 / rf2-hrqvg, Spec 013 §Failure
  semantics): the failing flow's own output is NOT written (the throw
  happened during `:output`; there is no usable new-output). Its
  `last-inputs` slot is NOT advanced so the flow re-attempts on the
  next drain. `run-flows!` catches the propagated throw, FLUSHES
  prior successful flows' dirty writes via `replace-container!`
  (rule 1 of the contract), and re-throws so the router's outer catch
  emits the cascade-level `:rf.error/flow-eval-exception` per Spec
  009 §Error contract. The cascade halts at the failing flow —
  downstream flows scheduled later in topo order do NOT run on this
  drain (rule 3); they re-attempt naturally on the next drain.

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
        ;; Per Spec 009 §:op-type vocabulary: `:rf.flow/skip` records a
        ;; value-equal recompute suppression. Tools use this to surface
        ;; "flow ran but inputs were stable" — distinct from "flow
        ;; didn't fire at all". Outer `debug-enabled?` gate elides the
        ;; tag-map construction in prod.
        (when interop/debug-enabled?
          ;; `:input-paths-unchanged` (rf2-931pm) names every input db-path
          ;; whose value was `=` to the previous run — the cascade DAG
          ;; consumer reads this to render the "considered, no recompute"
          ;; branch dimmed. For a value-equal skip every input is by
          ;; definition unchanged; we ship the full input-path vector.
          (trace/emit! :flow :rf.flow/skip
                       {:flow-id                flow-id
                        :reason                 :inputs-value-equal
                        :input-paths-unchanged  (:inputs flow)
                        :frame                  frame-id}))
        [db false])
      (try
        (let [new-output (apply (:output flow) new-inputs)
              ;; Per rf2-qlzh4: capture the pre-write value at the
              ;; flow's `:path` BEFORE we assoc-in the new output.
              ;; This becomes the `:before` slot on the
              ;; `:rf.flow/computed` trace below — consumers (Causa
              ;; Event Detail, re-frame-10x flow panel) no longer
              ;; need to walk the epoch's `:db-before` snapshot to
              ;; render "wrote [:cart :total] 47.50 -> 52.50". The
              ;; read happens against `db` (the loop accumulator
              ;; that includes prior flows' writes in this drain), so
              ;; a downstream flow whose `:path` overlaps an upstream
              ;; flow's `:path` sees the UPSTREAM's just-written
              ;; value as its `:before` — the correct cascade-local
              ;; semantics. Gated to dev-only so the read is DCE'd
              ;; under `:advanced` + `goog.DEBUG=false`.
              old-output (when interop/debug-enabled?
                           (get-in db (:path flow)))
              new-db     (assoc-in db (:path flow) new-output)]
          (swap! last-inputs assoc-in [flow-id frame-id] new-inputs)
          ;; Per Spec 009 §:op-type vocabulary: `:rf.flow/computed`
          ;; records a successful recompute. The dirty-check is
          ;; `=`-equality so this only fires when inputs actually
          ;; changed.
          ;;
          ;; Wire-bearing payloads (`:input-values`, `:result`,
          ;; `:before`) ride through `elision/elide-wire-value` per
          ;; Spec 009 §Size elision in traces — the walker is the
          ;; single normative emission site for `:rf.size/large-
          ;; elided` (and the `:rf/redacted` privacy sentinel).
          ;; Without this the flow trace bypassed the elision
          ;; contract that every other wire-emitting surface honours;
          ;; a flow reading or producing a large or sensitive value
          ;; would surface raw on the trace bus. Off-box defaults
          ;; match `event-emit` / `error-emit`.
          ;;
          ;; The `:path` opt on each walker call names where in the
          ;; slice's root the wrapped value lives — `:result` and
          ;; `:before` BOTH live at the flow's output path; each
          ;; `:input-values` entry is the value at the matching input
          ;; path. The walker reads `[:rf/elision :declarations
          ;; <path>]` and emits the marker for schema-declared large
          ;; slots.
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
                          :before       (elision/elide-wire-value
                                          old-output
                                          {:frame frame-id :path (:path flow)})
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
          ;; throw so `run-flows!` can flush already-accumulated dirty
          ;; writes from PRIOR successful flows in the same drain
          ;; (rf2-wyt97 / rf2-hrqvg, Spec 013 §Failure semantics rule 1)
          ;; and then propagate to the router's outer catch which emits
          ;; the cascade-level :rf.error/flow-eval-exception per Spec
          ;; 009 §Error contract. The per-flow `:rf.flow/failed` trace
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
          ;; Per rf2-je5p8: wrap the throw in an ex-info carrying the
          ;; flow-attribution slots `:rf.flow/failed-id` /
          ;; `:rf.flow/failed-frame`. The router's `run-flows!` catch
          ;; reads these and stamps `:flow-id` / `:flow` into the
          ;; substrate record's `:tags` so ops in CLJS production
          ;; (where `:rf.flow/failed` DCEs) can attribute the cascade-
          ;; level `:rf.error/flow-eval-exception` to a specific flow.
          ;; The original exception remains the `:cause` for stack-
          ;; trace introspection. Symmetric with Spec 013 §Failure
          ;; semantics rule 4: the per-flow trace fires first with
          ;; flow attribution; the cascade-level error preserves the
          ;; same attribution at the substrate boundary.
          (throw (ex-info (or #?(:clj (.getMessage ^Throwable e)
                                 :cljs (.-message e))
                              ":rf.error/flow-eval-exception")
                          {:rf.flow/failed-id    flow-id
                           :rf.flow/failed-frame frame-id}
                          e)))))))

(defn run-flows!
  "Per Spec 013 §Drain integration: walk THIS FRAME'S registered flows
  in topological order, dirty-check each one, recompute and write
  if inputs changed. Called from the per-event drain after :db commits.

  Flows are frame-scoped — only flows registered against frame-id run
  here, leaving sibling frames' flows untouched.

  Failed-flow contract (rf2-wyt97 / rf2-hrqvg, Spec 013 §Failure
  semantics): when a flow's `:output` throws, prior successful flows'
  dirty writes in the same drain are PRESERVED — we flush via
  `replace-container!` before re-throwing. The cascade then halts:
  flows scheduled later in topo order do not run on this drain, and
  the router's outer catch surfaces the cascade-level
  `:rf.error/flow-eval-exception`. The failing flow's own
  `last-inputs` is NOT advanced (so it re-attempts next drain);
  prior flows' `last-inputs` ARE advanced (they computed successfully
  and their outputs are now in app-db). This is the strongest 'no
  work is silently lost' guarantee compatible with cascade-level
  error surfacing."
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
            (let [flow            (flow-map (first remaining))
                  [new-db dirty?] (try
                                    (evaluate-flow! frame-id db flow)
                                    (catch #?(:clj Throwable :cljs :default) e
                                      ;; Flush prior successful flows'
                                      ;; writes before propagating the
                                      ;; throw — they are already in
                                      ;; `db` (the loop accumulator)
                                      ;; and their `last-inputs` slots
                                      ;; are already advanced (per
                                      ;; evaluate-flow!'s success
                                      ;; branch). Without this flush,
                                      ;; the router catches the
                                      ;; cascade exception but
                                      ;; `replace-container!` (below)
                                      ;; never runs and prior flows'
                                      ;; outputs silently vanish from
                                      ;; app-db while their
                                      ;; `:rf.flow/computed` traces
                                      ;; still claim the write
                                      ;; happened. Pre-rf2-wyt97 this
                                      ;; was a real bug; the
                                      ;; misleading evaluate-flow!
                                      ;; docstring described
                                      ;; per-flow-isolated semantics
                                      ;; that the impl never
                                      ;; delivered.
                                      (when any-dirty?
                                        (adapter/replace-container! container db))
                                      (throw e)))]
              (recur (rest remaining) new-db (or any-dirty? dirty?)))))))))

;; ---- late-bind hook registration ----------------------------------------
;;
;; re-frame.core, re-frame.fx, re-frame.router and re-frame.test-support
;; need to call into flows but ship in the core artefact — they cannot
;; `:require` this namespace because the flows artefact is optional
;; (apps that don't register flows don't carry it). Publish entry
;; points through the late-bind hook registry; consumers look the fns
;; up at call time.
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
;; Per rf2-wbtjn — frame-destroy teardown hook (symmetric with the
;; machines `:machines/teardown-on-frame-destroy!` hook landed by
;; rf2-vsigt). `frame/destroy-frame!` invokes this hook so per-frame
;; flow-registry entries, the matching `last-inputs` rows, and any
;; `:flow` registrar slots whose last owning frame was destroyed all
;; clear in one step. Without the hook a long-running SSR JVM (per-
;; request frame churn) leaks flow state indefinitely.
(late-bind/set-fn! :flows/teardown-on-frame-destroy!
                   registry/teardown-on-frame-destroy!)
