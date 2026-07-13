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
            [re-frame.frame :as frame]
            [re-frame.flows.registry :as registry]
            [re-frame.flows.topo :as topo]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace]
            ;; Keep tooling unreachable from the CLJS facade so Closure can
            ;; remove it when no tool requires the sibling namespace.
            #?@(:clj [[re-frame.flows.tooling :as flows-tooling]])))

(def ^:no-doc stale-incarnation
  "Internal return marker: the exact frame owner vanished during a flow pass."
  :rf.flow/stale-incarnation)

(defn- owner-live?
  [frame-id owner-token exact-owner?]
  (or (not exact-owner?)
      (frame/frame-incarnation-live? frame-id owner-token)))

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
  [frame-id owner-token exact-owner? flow new-output]
  (let [live?  #(owner-live? frame-id owner-token exact-owner?)
        schema (:schema flow)]
    (cond
      (not (live?)) false
      (nil? schema) true
      :else
      (if-let [validate (late-bind/get-fn-cached
                          :schemas/validate-with-registered-fn)]
        (let [valid? (validate schema new-output)]
          ;; Registered schema functions are authored callback boundaries.
          ;; Once A is gone, their result and every later diagnostic are inert.
          (cond
            (not (live?)) false
            valid? true
            :else
            (let [explain     (late-bind/get-fn-cached
                                :schemas/explain-with-registered-fn)
                  explanation (when explain (explain schema new-output))]
              (if-not (live?)
                false
                (let [redact (late-bind/get-fn-cached
                               :schemas/redact-validation-tags)
                      tags   {:category   :rf.error/schema-validation-failure
                              :where      :flow-output
                              :rf.flow/id (:id flow)
                              :failing-id (:id flow)
                              :schema-id  (:id flow)
                              :path       (:output-path flow)
                              :value      (elision/elide-wire-value
                                            new-output
                                            {:frame frame-id
                                             :path (:output-path flow)})
                              :explain    explanation
                              :reason     (str "Flow " (:id flow)
                                               " output failed schema "
                                               (pr-str schema) ".")
                              :recovery   :no-recovery
                              :frame      frame-id}
                      tags   (if redact (redact schema tags) tags)]
                  (if-not (live?)
                    false
                    (do
                      (trace/emit-error!
                        :rf.error/schema-validation-failure tags)
                      (live?))))))))
        true))))

(defn- evaluate-flow!
  "Evaluate one flow against the pending frame-state.

  Returns `[db dirty?]`. A thrown derivation is rethrown with the failing flow
  id for router attribution; `run-flows-on-db` restores dirty-check state and
  the router discards the pending db, so no partial output is committed.

  Trace payloads and their elision walks stay inside `debug-enabled?` branches
  so Closure removes them from production CLJS builds."
  [frame-id owner-token exact-owner? pass db runtime-db flow]
  (if-not (owner-live? frame-id owner-token exact-owner?)
    stale-incarnation
    (let [flow-id    (:id flow)
          new-inputs (read-inputs db runtime-db flow)
          ;; Read and write the captured A-owned cell, never a bare-id lookup
          ;; that could resolve to replacement B after a callback.
          old-inputs (registry/pass-flow-last-inputs pass flow-id)]
      (if (= new-inputs old-inputs)
        (do
          (when interop/debug-enabled?
            (trace/emit! :flow :rf.flow/skip
                         {:flow-id               flow-id
                          :reason                :inputs-value-equal
                          :input-paths-unchanged (:inputs flow)
                          :frame                 frame-id}))
          (if (owner-live? frame-id owner-token exact-owner?)
            [db false]
            stale-incarnation))
        (try
          (let [t0         (when interop/debug-enabled? (interop/now-ms))
                new-output (apply (:derive flow) new-inputs)]
            ;; `:derive` is the principal authored callback boundary.  Its
            ;; value is inert once A loses ownership; no cache write, trace,
            ;; validation or later flow may be attributed to B.
            (if-not (owner-live? frame-id owner-token exact-owner?)
              stale-incarnation
              (let [flow-elapsed-ms (when interop/debug-enabled?
                                      (- (interop/now-ms) t0))
                    old-output (when interop/debug-enabled?
                                 (get-in db (:output-path flow)))
                    new-db     (assoc-in db (:output-path flow) new-output)]
                (registry/pass-set-flow-last-inputs!
                  pass flow-id new-inputs)
                (when interop/debug-enabled?
                  (trace/emit! :flow :rf.flow/computed
                               {:flow-id      flow-id
                                :input-values (elide-inputs frame-id flow new-inputs)
                                :before       (elision/elide-wire-value
                                                old-output
                                                {:frame frame-id
                                                 :path (:output-path flow)})
                                :result       (elision/elide-wire-value
                                                new-output
                                                {:frame frame-id
                                                 :path (:output-path flow)})
                                :path         (:output-path flow)
                                :elapsed-ms   flow-elapsed-ms
                                :frame        frame-id}))
                (if-not (owner-live? frame-id owner-token exact-owner?)
                  stale-incarnation
                  (if (or (not interop/debug-enabled?)
                          (validate-output! frame-id owner-token exact-owner?
                                            flow new-output))
                    [new-db true]
                    stale-incarnation)))))
          (catch #?(:clj Throwable :cljs :default) e
            ;; A callback may destroy A and then throw.  Terminal loss wins:
            ;; the old callback's exception produces no B-attributed failure.
            (if-not (owner-live? frame-id owner-token exact-owner?)
              stale-incarnation
              (do
                (when interop/debug-enabled?
                  (trace/emit! :flow :rf.flow/failed
                               {:flow-id           flow-id
                                :exception-message #?(:clj (.getMessage ^Throwable e)
                                                      :cljs (.-message e))
                                :exception-data    (ex-data e)
                                :inputs            (elide-inputs frame-id flow new-inputs)
                                :frame             frame-id}))
                (if-not (owner-live? frame-id owner-token exact-owner?)
                  stale-incarnation
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
                                :cause             e}}))))))))))

(defn- run-flows-on-db*
  [frame-id db runtime-db owner-token exact-owner?]
  (if-not (owner-live? frame-id owner-token exact-owner?)
    stale-incarnation
    (let [pass (if exact-owner?
                 (registry/capture-flow-pass-state frame-id owner-token)
                 (registry/legacy-flow-pass-state frame-id))]
      (if-not pass
        stale-incarnation
        (let [flow-map          (:flow-map pass)
              abandoned-before (registry/pass-abandoned-paths-snapshot pass)
              abandoned-paths  (registry/pass-drain-abandoned-paths! pass)
              db               (reduce registry/vacate-path-in-db
                                       db abandoned-paths)]
          (if-not (owner-live? frame-id owner-token exact-owner?)
            stale-incarnation
            (if-not (seq flow-map)
              db
              (let [ordered            (topo/topo-sort flow-map)
                    last-inputs-before (registry/pass-last-inputs-snapshot pass)]
                (try
                  (loop [remaining ordered
                         db        db]
                    (cond
                      (not (owner-live? frame-id owner-token exact-owner?))
                      stale-incarnation

                      (empty? remaining)
                      db

                      :else
                      (let [flow   (flow-map (first remaining))
                            result (evaluate-flow! frame-id owner-token
                                                   exact-owner? pass db
                                                   runtime-db flow)]
                        (if (= stale-incarnation result)
                          stale-incarnation
                          (recur (rest remaining) (first result))))))
                  (catch #?(:clj Throwable :cljs :default) e
                    ;; Exact loss detached A's cells during destroy; restoring
                    ;; through the bare id would corrupt B.  When A is still
                    ;; live, restore the captured cells and preserve the
                    ;; ordinary flow-throw contract.
                    (if-not (owner-live? frame-id owner-token exact-owner?)
                      stale-incarnation
                      (do
                        (registry/pass-reset-last-inputs!
                          pass last-inputs-before)
                        (registry/pass-restore-abandoned-paths!
                          pass abandoned-before)
                        (throw e)))))))))))))

(defn run-flows-on-db
  "Transform a frame's pending app-db by evaluating its flows in dependency
  order. Runtime-db is a read-only input; flow outputs always target app-db.

  The router calls this from its outermost `:after` interceptor, after other
  interceptors have shaped the pending db and before commit. If a derivation
  throws, this function restores the frame's dirty-check cache and pending path
  vacations, then rethrows. The router discards the pending db, preserving
  all-or-nothing event semantics."
  ([frame-id db runtime-db]
   (run-flows-on-db* frame-id db runtime-db nil false))
  ([frame-id db runtime-db {:keys [exact-owner-token]}]
   (run-flows-on-db* frame-id db runtime-db exact-owner-token true)))

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
