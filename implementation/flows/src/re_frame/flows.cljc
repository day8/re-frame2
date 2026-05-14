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

  ## Artefact

  Ships in `day8/re-frame2-flows`, separate from the core artefact.
  `re-frame.core` looks this namespace's entry points up through
  `re-frame.late-bind`; loading this ns publishes the hooks.

  ## Internal layout

  This namespace is the public FAÇADE — it owns the post-drain
  evaluation walker, the fx-call indirections, and the late-bind hook
  publications, and re-exports the registry's public-surface symbols.
  The split is:

    - `re-frame.flows.topo`     — pure Kahn's topological sort +
                                  cycle-path extraction.
    - `re-frame.flows.registry` — per-frame `flows` + `last-inputs`
                                  atoms, validation, `reg-flow` /
                                  `clear-flow`, the registrar
                                  replacement-hook, and the test-only
                                  resets.
    - `re-frame.flows` (this)   — `evaluate-flow!` / `run-flows!`,
                                  fx-call indirections, late-bind
                                  publication, public re-exports.

  Test fixtures across artefacts reach `re-frame.flows/flows` and
  `re-frame.flows/last-inputs` directly (sometimes via `resolve`); the
  re-exports below MUST land on a Var deref-equal to the registry's
  own atom."
  (:require [re-frame.elision :as elision]
            [re-frame.flows.registry :as registry]
            [re-frame.flows.topo :as topo]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

;; ---- public-surface re-exports -------------------------------------------

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
  unwind."
  [frame-id db flow]
  (let [flow-id    (:id flow)
        new-inputs (read-inputs db flow)
        ;; `last-inputs` is shaped {flow-id {frame-id inputs}} so the
        ;; hot-reload invalidation hook can drop a flow's whole row
        ;; with one O(1) dissoc; per-frame dirty-check windows stay
        ;; independent.
        old-inputs (get-in @last-inputs [flow-id frame-id])]
    (if (= new-inputs old-inputs)
      (do
        ;; :rf.flow/skip records value-equal recompute suppression so
        ;; tools can distinguish "flow ran but inputs were stable" from
        ;; "flow didn't fire at all". Per Spec 009 §:op-type vocabulary.
        (trace/emit! :flow :rf.flow/skip
                     {:flow-id flow-id
                      :reason  :inputs-value-equal
                      :frame   frame-id})
        [db false])
      (try
        (let [new-output (apply (:output flow) new-inputs)
              new-db     (assoc-in db (:path flow) new-output)]
          (swap! last-inputs assoc-in [flow-id frame-id] new-inputs)
          ;; :rf.flow/computed records a successful recompute. The
          ;; dirty-check is =-equality so this only fires when inputs
          ;; actually changed. Per Spec 009 §:op-type vocabulary.
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
          ;;
          ;; The failure-path `:inputs` payload rides through the
          ;; elision walker for the same reason as the success path —
          ;; the value that triggered the throw may itself be a
          ;; large or sensitive blob, and the trace bus is the wire
          ;; boundary. Each entry is walked under its own declared
          ;; input path so per-path declarations apply.
          (trace/emit! :flow :rf.flow/failed
                       {:flow-id flow-id
                        :ex      e
                        :inputs  (mapv (fn [input-path v]
                                         (elision/elide-wire-value
                                           v
                                           {:frame frame-id :path input-path}))
                                       (:inputs flow)
                                       new-inputs)
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
;; Literal `set-fn!` invocations with literal keywords (one per line) —
;; the late-bind drift gate `re-frame.late-bind-drift-test` detects each
;; publication via regex.

(late-bind/set-fn! :flows/reg-flow           reg-flow)
(late-bind/set-fn! :flows/clear-flow         clear-flow)
(late-bind/set-fn! :flows/run-flows!         run-flows!)
(late-bind/set-fn! :flows/reset-last-inputs! reset-last-inputs!)
(late-bind/set-fn! :flows/reset-flows!       reset-flows!)
