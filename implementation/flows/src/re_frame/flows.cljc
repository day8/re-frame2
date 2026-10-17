(ns re-frame.flows
  "Flows — registered computed-state declarations a frame activates through
  its `:flows` config.

  Each event drain evaluates the flows its frame runs, in dependency order,
  against the pending app-db and runtime-db. Changed inputs run the flow's pure
  derivation and materialize its result in app-db before that db is committed.

  Flows are for derived values that must be durable application state. Values
  consumed only by views belong in subscriptions.

  This namespace is the public facade for the optional
  `day8/re-frame2-flows` artefact. It publishes its core integration points
  through `re-frame.late-bind`."
  (:require [re-frame.elision :as rf.elision]
            [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.flows.topo :as rf.flows.topo]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            ;; No require of `re-frame.flows.tooling` on EITHER runtime: the
            ;; facade carries no `flow-algebra-view` alias, so tooling is
            ;; unreachable from this facade on both runtimes and Closure
            ;; removes it whenever no tool requires the sibling directly.
            [re-frame.trace :as rf.trace]))

;; ---- public-surface re-exports -------------------------------------------
;;
;; `last-inputs-snapshot` exposes raw cached values for tests. It is not a
;; tooling or egress surface; trace payloads use the elided path. Flow
;; definitions are read through the registrar (`rf/registrations`,
;; `rf/handler-meta`) and a frame's active set through `(:flows (rf/frame-meta
;; f))` — there is no flows-specific read API.

(def ^:no-doc last-inputs-snapshot rf.flows.registry/last-inputs-snapshot)

(def reg-flow           rf.flows.registry/reg-flow)
;; No public `clear-flow` re-export here — the registrar inverse
;; is the one kind-keyed `(rf/clear :flow id)`. The registry fn is the
;; late-bind hook target.
(def reset-flows!       rf.flows.registry/reset-flows!)
(def reset-last-inputs! rf.flows.registry/reset-last-inputs!)

;; No `flow-algebra-view` facade alias. The flow algebra view
;; ships NO public accessor (Derivations §Flows expose algebra views) — every
;; tool requires the bundle-isolated `re-frame.flows.tooling` directly, and
;; `re-frame.derivation.graph` reaches it through `requiring-resolve` on the
;; JVM.

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
  (if (rf.flows.registry/runtime-input? path)
    (get-in runtime-db (rf.flows.registry/partition-relative-input-path path))
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
          (rf.elision/elide-wire-value
            v {:frame frame-id
               :path  (rf.flows.registry/partition-relative-input-path input-path)}))
        (:inputs flow)
        input-values))

(defn- sensitive-output?
  "True when the frame's elision registry classifies anything inside `output`,
  written at the flow's `:output-path`, as sensitive.

  The wire walker that redacts the failure trace's `:value` slot decides. It
  runs here with `:large` left alone, and the output is sensitive exactly when
  that walk changes it. So every declaration the walker honours counts here as
  it does for `:value`: the flow's own `:sensitive` marks; another source's
  declaration at an ancestor or a descendant of the output path; one that
  reaches the output only through an index-free or `:map-of` coordinate, which
  no path-prefix test can see; and the fail-closed whole-value redaction of a
  frame the registry cannot resolve."
  [frame-id flow output]
  (not= output
        (rf.elision/elide-wire-value
          output {:frame                    frame-id
                  :path                     (:output-path flow)
                  :rf.egress/include-large? true})))

(defn- explain-size-marker
  "The `:rf.size/large-elided` marker that stands in for a failing output's
  `:explain` when the frame's elision registry classifies anything in the
  output large, else nil. `elided` is the output as the wire
  walker elided it for the trace's `:value` slot, so the walker decides: the
  output is size-classified exactly when that walk placed a marker in it.

  A declaration covering the whole output (at `:output-path` or above it)
  yields the walker's own marker, reused as is. A narrower one yields a marker
  for the whole output, since the explanation re-ships all of it: its `:path`
  and `:handle` name `:output-path`, which the observational write has just
  filled, and its `:reason` is the declaring source the walker's markers
  carry."
  [flow output elided]
  (when-let [markers (seq (filter rf.elision/marker? (tree-seq coll? seq elided)))]
    (if (rf.elision/marker? elided)
      elided
      (rf.elision/->marker
        output (:output-path flow)
        {:reason (rf.elision/owners->provenance
                   (map #(hash-map :source (get-in % [:rf.size/large-elided :reason]))
                        markers))
         :hint   nil}))))

(defn- validate-output!
  "Validate a computed output through the optional schemas artefact.

  Validation is diagnostic: it emits a schema failure but does not unwind the
  pending flow cascade. The caller invokes this only in debug builds."
  [frame-id flow new-output]
  ;; KEY-presence, not value truthiness: a present nil / false `:schema` on
  ;; the flow is a declaration whose exact token is delegated to the
  ;; registered validator below; only an ABSENT key means no declaration.
  (when (contains? flow :schema)
    (when-let [validate (rf.late-bind/get-fn-cached
                          :schemas/validate-with-registered-fn)]
      (let [schema (:schema flow)]
        (when-not (validate schema new-output)
          (let [explain     (rf.late-bind/get-fn-cached
                              :schemas/explain-with-registered-fn)
                explanation (when explain (explain schema new-output))
                redact      (rf.late-bind/get-fn-cached
                              :schemas/redact-validation-tags)
                elided      (rf.elision/elide-wire-value
                              new-output
                              {:frame frame-id
                               :path  (:output-path flow)})
                tags        {:category   :rf.error/schema-validation-failure
                             :where      :flow-output
                             :rf.flow/id (:id flow)
                             :failing-id (:id flow)
                             :schema-id  (:id flow)
                             :path       (:output-path flow)
                             :value      elided
                             :explain    explanation
                             :reason     (str "Flow " (:id flow)
                                              " output failed schema "
                                              (pr-str schema) ".")
                             :recovery   :no-recovery
                             :frame      frame-id}
                tags        (if redact (redact schema tags) tags)
                ;; `:explain` re-ships the checked value whole (Malli's
                ;; `:value` and every `:errors[*].:value`) and is not
                ;; path-anchored, so it cannot be walked like `:value`. The
                ;; seam above reads only `:sensitive?` props in the SCHEMA, so
                ;; an output the frame's registry classifies is redacted whole
                ;; here, and the trace is stamped so the egress gate drops it.
                ;; This runs after the seam, so sensitive wins over any size
                ;; marker it substituted. Otherwise an output the registry
                ;; classifies large gets a size marker in place of `:explain`,
                ;; as Spec 010's size arm does for a `:large?` schema — unless
                ;; the seam already redacted it, since sensitive wins there too.
                tags        (cond
                              (sensitive-output? frame-id flow new-output)
                              (assoc tags
                                     :explain    rf.privacy/redacted-sentinel
                                     :sensitive? true)

                              (:sensitive? tags) tags

                              :else
                              (if-let [marker (explain-size-marker
                                                flow new-output elided)]
                                (assoc tags :explain marker :large? true)
                                tags))]
            (rf.trace/emit-error! :rf.error/schema-validation-failure tags))))))
  nil)

(defn- flow-eval-failure!
  "Report one flow-evaluation failure, attributed to the phase that actually
  threw, and re-raise it for the router.

  `phase` is `:derive` when the authored `:derive` callback threw, and
  `:output-write` when `:derive` RETURNED and the framework's own installation
  of that value at the flow's declared `:output-path` threw — most often
  because the pending app-db holds a container at that path which cannot accept
  the path's final segment (a keyword segment over a vector, say).

  The two are discriminated STRUCTURALLY, by which `try` in `evaluate-flow`
  caught the throw — never by reading the exception's message. The callback and
  the install sit in separate `try` forms, so an install failure cannot reach
  the derive handler and be reported as authored code failing.

  Emits the per-flow `:rf.flow/failed` trace and throws the aggregate
  `:rf.error/flow-eval-exception`, carrying the phase on BOTH the dev trace and
  the thrown ex-data so the router can lift it onto the always-on production
  error record."
  [frame-id flow new-inputs phase e]
  (let [flow-id     (:id flow)
        output-path (:output-path flow)]
    (when rf.interop/debug-enabled?
      (rf.trace/emit! :flow :rf.flow/failed
                      {:flow-id           flow-id
                       :phase             phase
                       :path              output-path
                       :exception-message #?(:clj (.getMessage ^Throwable e)
                                             :cljs (.-message e))
                       :exception-data    (ex-data e)
                       :inputs            (elide-inputs frame-id flow new-inputs)
                       :frame             frame-id}))
    (rf.error/throw-error!
      :rf.error/flow-eval-exception
      're-frame.flows/run-flows-on-db
      (if (= :derive phase)
        (str "a flow's :derive fn threw while recomputing flow "
             (pr-str flow-id)
             " during the drain; the event aborts before the :db install "
             "(no partial commit, app-db unchanged) and the flow re-attempts "
             "on the next drain. Fix the :derive fn so it does not throw on "
             "the inputs it is given.")
        (str "flow " (pr-str flow-id)
             "'s :derive fn RETURNED normally, but re-frame could not "
             "install that value at the flow's declared :output-path "
             (pr-str output-path)
             " — the pending app-db holds a container at that path which "
             "cannot accept it. The :derive fn did not throw; the failure "
             "is the framework's output write, during the drain; the event "
             "aborts before the :db install (no partial commit, app-db "
             "unchanged) and the flow re-attempts on the next drain. Fix "
             "the :output-path, or the shape the pending app-db holds at "
             "its parent path — do not go looking in the :derive fn."))
      {:recovery :no-recovery
       :extra    {:rf.flow/failed-id    flow-id
                  :rf.flow/failed-phase phase
                  :rf.flow/output-path  output-path
                  :cause                e}})))

(defn- reassert-output
  "The equal-input visit: `:derive` does not run.

  A flow owns its `:output-path` (Spec 013 §Dirty-check semantics), so when the
  candidate db no longer holds the output `row` remembers — a handler replaced
  the db, rebuilt the output's parent map, or wrote the slot — the remembered
  output is written back. An absent slot differs from every value, so a
  remembered nil comes back as a present key. A slot that already holds the
  value leaves `db` untouched.

  The visit's `:rf.flow/skip` carries `:repaired? true` when it wrote. A write
  that cannot be installed fails as `:output-write`, as a recompute's would."
  [frame-id db flow new-inputs row]
  (let [path    (:output-path flow)
        output  (:output row)
        repair? (not= output (get-in db path ::absent))
        new-db  (if repair?
                  (try
                    (assoc-in db path output)
                    (catch #?(:clj Throwable :cljs :default) e
                      (flow-eval-failure! frame-id flow new-inputs :output-write e)))
                  db)]
    (when rf.interop/debug-enabled?
      (rf.trace/emit! :flow :rf.flow/skip
                      (cond-> {:flow-id               (:id flow)
                               :reason                :inputs-value-equal
                               :input-paths-unchanged (:inputs flow)
                               :frame                 frame-id}
                        repair? (assoc :repaired? true))))
    new-db))

(defn- evaluate-flow
  "Run one flow's `:derive` over `new-inputs` and write the result into `db`.
  Returns `[db' output]`.

  The authored `:derive` callback and the framework's own output installation
  are caught by SEPARATE `try` forms. The split is the whole attribution
  mechanism: an `assoc-in` that cannot write the declared `:output-path` into
  the pending app-db is structurally unreachable from the derive handler, so
  it can never be reported as the programmer's `:derive` fn throwing.

  Trace payloads and their elision walks stay inside `debug-enabled?` branches
  so Closure removes them from production CLJS builds."
  [frame-id db flow new-inputs]
  (let [started-at-ms  (when rf.interop/debug-enabled? (rf.interop/now-ms))
        derive-outcome (try
                         {::output (apply (:derive flow) new-inputs)}
                         (catch #?(:clj Throwable :cljs :default) e
                           {::thrown e}))]
    (when (contains? derive-outcome ::thrown)
      (flow-eval-failure! frame-id flow new-inputs :derive (::thrown derive-outcome)))
    (let [new-output (::output derive-outcome)
          old-output (when rf.interop/debug-enabled?
                       (get-in db (:output-path flow)))
          new-db     (try
                       (assoc-in db (:output-path flow) new-output)
                       (catch #?(:clj Throwable :cljs :default) e
                         (flow-eval-failure! frame-id flow new-inputs :output-write e)))]
      (when rf.interop/debug-enabled?
        (rf.trace/emit! :flow :rf.flow/computed
                        {:flow-id      (:id flow)
                         :input-values (elide-inputs frame-id flow new-inputs)
                         :before       (rf.elision/elide-wire-value
                                         old-output
                                         {:frame frame-id
                                          :path  (:output-path flow)})
                         :result       (rf.elision/elide-wire-value
                                         new-output
                                         {:frame frame-id
                                          :path  (:output-path flow)})
                         :path         (:output-path flow)
                         :elapsed-ms   (- (rf.interop/now-ms) started-at-ms)
                         :frame        frame-id})
        (validate-output! frame-id flow new-output))
      [new-db new-output])))

;; ---- the active set ------------------------------------------------------

(defn- active-flows
  "The definitions of the ids in `ids` that resolve, as `{id flow}`. Resolution
  goes through whatever registration view the caller has bound — inside an
  event, the frame's own."
  [ids]
  (reduce (fn [m id]
            (if-let [flow (rf.registrar/lookup rf.flows.registry/kind id)]
              (assoc m id flow)
              m))
          {}
          ids))

(defn- reconcile-departures
  "Vacate what the memo `rows` record for flows that no longer run as they did:
  a row whose flow left the active set has its output path removed from `db`
  and the row dropped, and a row whose flow now writes a different
  `:output-path` has the old path removed. Returns `[db rows]`.

  A row whose id the frame still names but which no longer resolves — its
  registration cleared or swept — warns `:rf.warning/unregistered-flow`. Its
  claims in the frame's elision registry go with it."
  [frame-id ids active db rows]
  (let [named (set ids)]
    (reduce-kv
      (fn [[db rows] id row]
        (let [old-flow (:flow row)
              old-path (:output-path old-flow)]
          (if-let [flow (get active id)]
            (if (= old-path (:output-path flow))
              [db rows]
              [(rf.flows.registry/vacate-path-in-db db old-path) rows])
            (do
              (when (and rf.interop/debug-enabled? (contains? named id))
                (rf.trace/emit! :warning :rf.warning/unregistered-flow
                                {:frame   frame-id
                                 :flow-id id
                                 :path    old-path
                                 :reason  (str "frame " (pr-str frame-id)
                                               " names flow " (pr-str id)
                                               " in its :flows, but no reg-flow"
                                               " defines it any more; its output"
                                               " at " (pr-str old-path)
                                               " is vacated, and re-registering"
                                               " the id resumes it.")}))
              (when (rf.flows.registry/flow-declares-marks? old-flow)
                (rf.flows.registry/drop-output-marks! frame-id id))
              [(rf.flows.registry/vacate-path-in-db db old-path)
               (dissoc rows id)]))))
      [db rows]
      rows)))

(defn run-flows-on-db
  "Transform a frame's pending app-db by evaluating the flows its `:flows`
  config names, in dependency order. Runtime-db is a read-only input; flow
  outputs always target app-db.

  The router calls this from its outermost `:after` interceptor, after other
  interceptors have shaped the pending db and before commit, with the frame's
  own registration view bound — so the ids resolve live for a frame made
  without `:images`, and through its sealed generation for one made with them.

  The memo decides what runs. A flow with no row, or a row computed with a
  different definition, recomputes; otherwise `=`-equal inputs skip `:derive`
  and re-assert the remembered output. A row whose flow left the frame, or
  moved its `:output-path`, has the old path vacated in this same pending db.
  The activation checks run over the active set on every pass, so a
  re-registration that completes a cycle or an output overlap aborts the event.

  The memo advances only when the whole pass succeeds. A throw leaves it as it
  was and propagates; the router discards the pending db, so the event commits
  in full or not at all."
  [frame-id db runtime-db]
  (let [ids  (:flows (rf.frame/frame-config frame-id))
        rows (rf.flows.registry/frame-last-inputs-snapshot frame-id)]
    (if (and (empty? ids) (empty? rows))
      db
      (let [active       (active-flows ids)
            [db rows]    (reconcile-departures frame-id ids active db rows)
            _            (rf.flows.topo/detect-output-path-overlap!
                           active 're-frame.flows/run-flows-on-db)
            order        (rf.flows.topo/topo-sort
                           active 're-frame.flows/run-flows-on-db)
            [db rows]    (reduce
                           (fn [[db rows] id]
                             (let [flow       (get active id)
                                   row        (get rows id)
                                   new-inputs (read-inputs db runtime-db flow)]
                               (if (and (identical? flow (:flow row))
                                        (= new-inputs (:inputs row)))
                                 [(reassert-output frame-id db flow new-inputs row) rows]
                                 (do
                                   ;; A definition the frame has not evaluated
                                   ;; claims its output paths before it emits.
                                   (when (and (not (identical? flow (:flow row)))
                                              (or (rf.flows.registry/flow-declares-marks? flow)
                                                  (some-> (:flow row) rf.flows.registry/flow-declares-marks?)))
                                     (rf.flows.registry/claim-output-marks! frame-id flow))
                                   (let [[db output] (evaluate-flow frame-id db flow new-inputs)]
                                     [db (assoc rows id {:flow   flow
                                                         :inputs new-inputs
                                                         :output output})])))))
                           [db rows]
                           order)]
        (reset! (rf.flows.registry/ensure-frame-memo-atom! frame-id) rows)
        db))))

;; ---- late-bind hook registration ----------------------------------------
;;
;; Core cannot statically require this optional artefact. Keep each publication
;; as a literal call so the late-bind drift gate can discover it.

(rf.late-bind/set-fn! :flows/reg-flow           reg-flow)
(rf.late-bind/set-fn! :flows/clear-flow         rf.flows.registry/clear-flow)
(rf.late-bind/set-fn! :flows/check-activation!  rf.flows.registry/check-activation!)
(rf.late-bind/set-fn! :flows/run-flows-on-db    run-flows-on-db)
(rf.late-bind/set-fn! :flows/reset-flows!       reset-flows!)
;; The router owns post-transform commit rollback: it snapshots a frame's memo
;; before the flow pass and puts it back when the event does not commit.
(rf.late-bind/set-fn! :flows/snapshot-last-inputs rf.flows.registry/frame-last-inputs-snapshot)
(rf.late-bind/set-fn! :flows/restore-last-inputs!  rf.flows.registry/reset-frame-last-inputs-to!)
;; Frame destruction drops the frame's memo.
(rf.late-bind/set-fn! :flows/teardown-on-frame-destroy!
                      rf.flows.registry/teardown-on-frame-destroy!)
;; Core's `:fx` walk settles a frame whose state it changed only when the frame
;; runs a flow.
(rf.late-bind/set-fn! :flows/frame-has-flows? rf.flows.registry/frame-runs-flows?)
