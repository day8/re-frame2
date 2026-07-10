(ns re-frame.flows
  "Flows — registered, runtime-toggleable computed-state declarations.

  Each event drain evaluates the frame's flows in dependency order against
  the pending app-db and runtime-db. Changed inputs run the flow's pure
  derivation and materialize its result in app-db before that db is committed.

  Flows are for derived values that must be durable application state. Values
  consumed only by views belong in subscriptions.

  This namespace is the public facade for the optional
  `day8/re-frame2-flows` artefact. It publishes its core integration points
  through `re-frame.late-bind`."
  (:require [re-frame.elision :as elision]
            [re-frame.error :as error]
            [re-frame.flows.registry :as registry]
            [re-frame.flows.topo :as topo]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace]
            ;; Keep tooling unreachable from the CLJS facade so Closure can
            ;; remove it when no tool requires the sibling namespace.
            #?@(:clj [[re-frame.flows.tooling :as flows-tooling]])))

;; ---- public-surface re-exports -------------------------------------------
;;
;; `last-inputs-snapshot` exposes raw cached values for tests and rollback. It
;; is not a tooling or egress surface; trace payloads use the elided path.

(def flows-snapshot       registry/flows-snapshot)
(def ^:no-doc last-inputs-snapshot registry/last-inputs-snapshot)

;; Flow metadata is frame-scoped and therefore cannot use the frame-blind
;; registrar metadata slot.
(def flow-meta-at       registry/flow-meta-at)

(def reg-flow           registry/reg-flow)
(def clear-flow         registry/clear-flow)
(def reset-flows!       registry/reset-flows!)
(def reset-last-inputs! registry/reset-last-inputs!)

;; JVM tools get a convenience alias. CLJS tools require the bundle-isolated
;; sibling directly.
#?(:clj
   (def flow-algebra-view flows-tooling/flow-algebra-view))

;; ---- partition-qualified input resolution -------------------------------
;;
;;   bare path            → app-db    e.g. [:user :first]
;;   [:rf.db/runtime …]   → runtime-db e.g. [:rf.db/runtime :rf.runtime/routing :current :route-id]
;;
;; Both reads use the pending frame-state. Outputs always target app-db, so a
;; runtime-qualified input cannot form a topology edge with another flow's
;; output. Its resolved value still participates in the dirty check.

(defn- resolve-input
  "Read a bare input from app-db or a qualified input from runtime-db."
  [db runtime-db path]
  (if (registry/runtime-input? path)
    (get-in runtime-db (registry/input-resolve-path path))
    (get-in db path)))

(defn- read-inputs
  "Read a flow's inputs from the pending app-db and runtime-db."
  [db runtime-db flow]
  (mapv (fn [path] (resolve-input db runtime-db path)) (:inputs flow)))

(defn- elide-inputs
  "Elide input values for trace emission at their declaration coordinates.

  Runtime input paths drop the `:rf.db/runtime` qualifier because the elision
  registry is keyed by paths within a partition. Callers keep this function
  inside a `debug-enabled?` branch so Closure can remove the walk."
  [frame-id flow input-values]
  (mapv (fn [input-path v]
          (elision/elide-wire-value
            v {:frame frame-id :path (registry/input-resolve-path input-path)}))
        (:inputs flow)
        input-values))

(defn- validate-output!
  "Validate a computed output through the optional schemas artefact.

  Validation is diagnostic: it emits a schema failure but does not unwind the
  pending flow cascade. The caller invokes this only in debug builds."
  [frame-id flow new-output]
  (when-let [schema (:schema flow)]
    (when-let [validate (late-bind/get-fn-cached :schemas/validate-with-registered-fn)]
      (when-not (validate schema new-output)
        (let [explain     (late-bind/get-fn-cached :schemas/explain-with-registered-fn)
              explanation (when explain (explain schema new-output))
              ;; Schema-aware redaction also covers values embedded in Malli's
              ;; explanation, which path elision cannot inspect safely.
              redact      (late-bind/get-fn-cached :schemas/redact-validation-tags)
              tags        {:category   :rf.error/schema-validation-failure
                           :where      :flow-output
                           :rf.flow/id (:id flow)
                           :failing-id (:id flow)
                           :schema-id  (:id flow)
                           :path       (:output-path flow)
                           ;; Keep the value on the same elided path as the
                           ;; successful `:rf.flow/computed` result.
                           :value      (elision/elide-wire-value
                                         new-output
                                         {:frame frame-id :path (:output-path flow)})
                           :explain    explanation
                           :reason     (str "Flow " (:id flow)
                                            " output failed schema "
                                            (pr-str schema) ".")
                           :recovery   :no-recovery
                           :frame      frame-id}]
          (trace/emit-error! :rf.error/schema-validation-failure
                             (cond-> tags
                               redact (->> (redact schema)))))))))

(defn- evaluate-flow!
  "Evaluate one flow against the pending frame-state.

  Returns `[db dirty?]`. A thrown derivation is rethrown with the failing flow
  id for router attribution; `run-flows-on-db` restores dirty-check state and
  the router discards the pending db, so no partial output is committed.

  Trace payloads and their elision walks stay inside `debug-enabled?` branches
  so Closure removes them from production CLJS builds."
  [frame-id db runtime-db flow]
  (let [flow-id    (:id flow)
        new-inputs (read-inputs db runtime-db flow)
        ;; Dirty-check rows live in separate per-frame atoms.
        old-inputs (registry/get-frame-flow-last-inputs frame-id flow-id)]
    (if (= new-inputs old-inputs)
      (do
        ;; A skip means the flow was considered but its resolved inputs were
        ;; value-equal to the previous run.
        (when interop/debug-enabled?
          (trace/emit! :flow :rf.flow/skip
                       {:flow-id                flow-id
                        :reason                 :inputs-value-equal
                        :input-paths-unchanged  (:inputs flow)
                        :frame                  frame-id}))
        [db false])
      (try
        ;; Timing is trace-only and disappears with the surrounding debug
        ;; branches in production builds.
        (let [t0         (when interop/debug-enabled? (interop/now-ms))
              new-output (apply (:derive flow) new-inputs)
              flow-elapsed-ms (when interop/debug-enabled?
                                (- (interop/now-ms) t0))
              ;; Read from the cascade accumulator so `:before` describes the
              ;; value this write replaces. Output-path overlap validation means
              ;; another flow cannot have written the same slot.
              old-output (when interop/debug-enabled?
                           (get-in db (:output-path flow)))
              new-db     (assoc-in db (:output-path flow) new-output)]
          (registry/set-frame-flow-last-inputs! frame-id flow-id new-inputs)
          ;; Inputs and both output values are wire-bearing trace data, so each
          ;; is elided at the path whose declaration governs it.
          (when interop/debug-enabled?
            (trace/emit! :flow :rf.flow/computed
                         {:flow-id      flow-id
                          :input-values (elide-inputs frame-id flow new-inputs)
                          :before       (elision/elide-wire-value
                                          old-output
                                          {:frame frame-id :path (:output-path flow)})
                          :result       (elision/elide-wire-value
                                          new-output
                                          {:frame frame-id :path (:output-path flow)})
                          :path         (:output-path flow)
                          :elapsed-ms   flow-elapsed-ms
                          :frame        frame-id})
            ;; Validation is observational and follows the computed trace.
            (validate-output! frame-id flow new-output))
          [new-db true])
        (catch #?(:clj Throwable :cljs :default) e
          ;; Emit an EDN-safe summary rather than a host Throwable. Projection
          ;; redacts author-controlled `:exception-data` when the frame carries
          ;; sensitive declarations; the router's local error retains the cause.
          (when interop/debug-enabled?
            (trace/emit! :flow :rf.flow/failed
                         {:flow-id           flow-id
                          :exception-message #?(:clj (.getMessage ^Throwable e)
                                                :cljs (.-message e))
                          :exception-data    (ex-data e)
                          :inputs            (elide-inputs frame-id flow new-inputs)
                          :frame             frame-id}))
          ;; Preserve flow attribution for the always-on router error, which is
          ;; still emitted when the debug-only per-flow trace is absent.
          (error/throw-error!
            :rf.error/flow-eval-exception
            'rf/run-flows-on-db
            (str "a flow's :derive fn threw while recomputing flow "
                 (pr-str flow-id)
                 " during the drain; the event aborts before the :db install "
                 "(no partial commit, app-db unchanged) and the flow "
                 "re-attempts on the next drain. Fix the :derive fn so it "
                 "does not throw on the inputs it is given.")
            {:recovery :no-recovery
             :extra    {:rf.flow/failed-id flow-id
                        :cause             e}}))))))

(defn run-flows-on-db
  "Transform a frame's pending app-db by evaluating its flows in dependency
  order. Runtime-db is a read-only input; flow outputs always target app-db.

  The router calls this from its outermost `:after` interceptor, after other
  interceptors have shaped the pending db and before commit. If a derivation
  throws, this function restores the frame's dirty-check cache and pending path
  vacations, then rethrows. The router discards the pending db, preserving
  all-or-nothing event semantics."
  [frame-id db runtime-db]
  (let [flow-map (get (registry/flows-snapshot) frame-id)
        ;; In-drain lifecycle changes cannot write app-db directly because the
        ;; deferred commit would overwrite them. Apply their queued vacations
        ;; to the pending db even when the last flow was just cleared.
        abandoned-before (registry/abandoned-output-paths-snapshot frame-id)
        abandoned-paths  (registry/drain-abandoned-output-paths! frame-id)
        db (reduce registry/vacate-path-in-db db abandoned-paths)]
    (if-not (seq flow-map)
      db
      (let [ordered (topo/topo-sort flow-map)
            ;; Each frame owns a separate cache atom, so rollback cannot
            ;; overwrite a sibling frame's concurrent advances.
            last-inputs-before (registry/frame-last-inputs-snapshot frame-id)]
        (try
          ;; Left-fold app-db writes through the topological order. Every flow
          ;; reads the same pending runtime-db snapshot.
          (loop [remaining ordered
                 db        db]
            (if (empty? remaining)
              db
              (let [flow         (flow-map (first remaining))
                    [new-db _]   (evaluate-flow! frame-id db runtime-db flow)]
                (recur (rest remaining) new-db))))
          (catch #?(:clj Throwable :cljs :default) e
            ;; Match the router's discarded pending db by restoring every
            ;; eager side effect owned by this flow pass.
            (registry/reset-frame-last-inputs-to! frame-id last-inputs-before)
            (registry/restore-abandoned-output-paths! frame-id abandoned-before)
            (throw e)))))))

;; ---- late-bind hook registration ----------------------------------------
;;
;; Core cannot statically require this optional artefact. Keep each publication
;; as a literal call so the late-bind drift gate can discover it.

(late-bind/set-fn! :flows/reg-flow           reg-flow)
(late-bind/set-fn! :flows/clear-flow         clear-flow)
(late-bind/set-fn! :flows/run-flows-on-db    run-flows-on-db)
(late-bind/set-fn! :flows/reset-flows!       reset-flows!)
;; The router owns post-transform commit rollback. These pairs let it restore
;; the eager dirty-check advances and consumed path vacations that this
;; artefact cannot otherwise observe after returning.
(late-bind/set-fn! :flows/snapshot-last-inputs registry/frame-last-inputs-snapshot)
(late-bind/set-fn! :flows/restore-last-inputs!  registry/reset-frame-last-inputs-to!)
(late-bind/set-fn! :flows/snapshot-abandoned-paths registry/abandoned-output-paths-snapshot)
(late-bind/set-fn! :flows/restore-abandoned-paths!  registry/restore-abandoned-output-paths!)
;; Frame destruction must release registry rows and caches owned by that frame.
(late-bind/set-fn! :flows/teardown-on-frame-destroy!
                   registry/teardown-on-frame-destroy!)
