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
  (:require [re-frame.classification :as rf.classification]
            [re-frame.elision :as rf.elision]
            [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.flows.topo :as rf.flows.topo]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
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

(defn- claims-at
  "The runtime-db-absolute `claims` re-rooted at `path`: a claim at or above
  `path` covers the whole value (`[]`), one below it keeps the segments past
  `path`, and one on another branch, of any length, contributes nothing."
  [claims path]
  (let [n (count path)]
    (keep (fn [claim]
            (let [claim (vec claim)
                  m     (count claim)]
              (cond
                (and (<= m n) (= claim (subvec path 0 m))) []
                (and (> m n) (= path (subvec claim 0 n)))  (subvec claim n))))
          claims)))

(defn- elide-input
  "Elide one input value at its declaration coordinates. A runtime input is
  first redacted at `pending-claims`, the claims the frame's policy makes on
  the runtime-db the pass read: the walk below classifies by the committed
  runtime-db, whose instances an event can be about to change — a navigation
  commits its route through the event's own `:rf.db/runtime`. Both apply, so
  the value is redacted under either."
  [frame-id pending-claims input-path v]
  (let [path (rf.flows.registry/partition-relative-input-path input-path)
        v    (if (and pending-claims (rf.flows.registry/runtime-input? input-path))
               (rf.classification/redact-with-paths
                 v
                 (claims-at (keys (:sensitive-declarations pending-claims)) path)
                 (claims-at (keys (:declarations pending-claims)) path))
               v)]
    (rf.elision/elide-wire-value v {:frame frame-id :path path})))

(defn- elide-inputs
  "Elide input values for trace emission at their declaration coordinates.

  Runtime input paths drop the `:rf.db/runtime` qualifier because the elision
  registry is keyed by paths within a partition. Callers keep this function
  inside a `debug-enabled?` branch so Closure can remove the walk."
  [frame-id runtime-db flow input-values]
  (let [pending-claims (when (some rf.flows.registry/runtime-input? (:inputs flow))
                         (rf.elision/with-runtime-classification
                           nil [(rf.elision/frame-policy frame-id)] runtime-db))]
    (mapv (fn [input-path v] (elide-input frame-id pending-claims input-path v))
          (:inputs flow)
          input-values)))

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
  [frame-id runtime-db flow new-inputs phase e]
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
                       :inputs            (elide-inputs frame-id runtime-db flow new-inputs)
                       :frame             frame-id}))
    (rf.error/throw-error!
      :rf.error/flow-eval-exception
      're-frame.flows/run-flows-on-db
      (if (= :derive phase)
        (str "a flow's :derive fn threw while recomputing flow "
             (pr-str flow-id)
             " during the drain; the flow pass installs nothing (no partial "
             "commit, app-db keeps the value it held before the pass) and the "
             "flow re-attempts on the next drain. Fix the :derive fn so it "
             "does not throw on the inputs it is given.")
        (str "flow " (pr-str flow-id)
             "'s :derive fn RETURNED normally, but re-frame could not "
             "install that value at the flow's declared :output-path "
             (pr-str output-path)
             " — the pending app-db holds a container at that path which "
             "cannot accept it. The :derive fn did not throw; the failure "
             "is the framework's output write, during the drain; the flow "
             "pass installs nothing (no partial commit, app-db keeps the "
             "value it held before the pass) and the flow re-attempts on the "
             "next drain. Fix the :output-path, or the shape the pending "
             "app-db holds at its parent path — do not go looking in the "
             ":derive fn."))
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
  [frame-id db runtime-db flow new-inputs row]
  (let [path    (:output-path flow)
        output  (:output row)
        repair? (not= output (get-in db path ::absent))
        new-db  (if repair?
                  (try
                    (assoc-in db path output)
                    (catch #?(:clj Throwable :cljs :default) e
                      (flow-eval-failure! frame-id runtime-db flow new-inputs :output-write e)))
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
  [frame-id db runtime-db flow new-inputs]
  (let [started-at-ms  (when rf.interop/debug-enabled? (rf.interop/now-ms))
        derive-outcome (try
                         {::output (apply (:derive flow) new-inputs)}
                         (catch #?(:clj Throwable :cljs :default) e
                           {::thrown e}))]
    (when (contains? derive-outcome ::thrown)
      (flow-eval-failure! frame-id runtime-db flow new-inputs :derive (::thrown derive-outcome)))
    (let [new-output (::output derive-outcome)
          old-output (when rf.interop/debug-enabled?
                       (get-in db (:output-path flow)))
          new-db     (try
                       (assoc-in db (:output-path flow) new-output)
                       (catch #?(:clj Throwable :cljs :default) e
                         (flow-eval-failure! frame-id runtime-db flow new-inputs :output-write e)))]
      (when rf.interop/debug-enabled?
        (rf.trace/emit! :flow :rf.flow/computed
                        {:flow-id      (:id flow)
                         :input-values (elide-inputs frame-id runtime-db flow new-inputs)
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
                         :frame        frame-id}))
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

(defn- named-definitions
  "Every definition of the ids in `ids` the bound view holds. An id more than
  one namespace registers is one a live frame refuses to run; classification
  is a union, so every namespace's definition of it is kept, and the flow
  pass, not the policy build, reports the ambiguity."
  [ids]
  (let [ambiguous (rf.registrar/ambiguous-registrations rf.flows.registry/kind)]
    (mapcat (fn [id]
              (if-let [ns->flow (get ambiguous id)]
                (vals ns->flow)
                (some-> (rf.registrar/lookup rf.flows.registry/kind id) list)))
            ids)))

(defn ^:no-doc output-classification
  "The `:flows/output-classification` hook: `{flow-id {:sensitive [abs-path …]
  :large [abs-path …]}}` for the flows frame `frame-id` runs that classify
  their output — each definition of an id its `:flows` names, and each
  definition its memo holds a row for, so a departed or moved output stays
  classified until the event that vacates it. Called while
  `re-frame.elision/frame-policy` builds the frame's policy, inside its
  resolution, so it never throws: every egress of the frame builds that
  policy. Spec 013 §Flow tracing."
  [frame-id]
  (let [defs (concat (named-definitions (:flows (rf.frame/frame-config frame-id)))
                     (keep :flow (vals (rf.flows.registry/frame-last-inputs-snapshot frame-id))))]
    (reduce (fn [acc flow]
              (if-let [decl (rf.flows.registry/output-declaration flow)]
                (update acc (:id flow) #(merge-with into % decl))
                acc))
            nil
            defs)))

(defn- reconcile-departures
  "Vacate what the memo `rows` record for flows that no longer run as they did:
  a row whose flow left the active set has its output path removed from `db`
  and the row dropped, and a row whose flow now writes a different
  `:output-path` has the old path removed. Returns `[db rows]`.

  A row whose id the frame still names but which no longer resolves — its
  registration cleared or swept — warns `:rf.warning/unregistered-flow`."
  [frame-id ids active db rows]
  (let [named (set ids)]
    (reduce-kv
      (fn [[db rows] id row]
        (let [old-path (:output-path (:flow row))]
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
              [(rf.flows.registry/vacate-path-in-db db old-path)
               (dissoc rows id)]))))
      [db rows]
      rows)))

(defn run-flows-on-db
  "Evaluate the flows a frame's `:flows` config names over its pending app-db,
  in dependency order, and return the candidate as `{:db app-db}` plus what the
  router publishes with it. Runtime-db is a read-only input; flow outputs
  always target app-db.

  The router calls this from its outermost `:after` interceptor, after other
  interceptors have shaped the pending db and before commit, and again after an
  `:fx` walk that wrote the frame's state, with the frame's own registration
  view bound — so the ids resolve live for a frame made without `:images`, and
  through its sealed generation for one made with them.

  The memo decides what runs. A flow with no row, or a row computed with a
  different definition, recomputes; otherwise `=`-equal inputs skip `:derive`
  and re-assert the remembered output, which is what `:derive` returns for
  them, so a frame-state a tool write installed outside any event needs no
  memo reset. A row whose flow left the frame, or
  moved its `:output-path`, has the old path vacated in this same pending db.
  The activation checks run over the active set on every pass, so a
  re-registration that completes a cycle or an output overlap aborts the event.

  The pass proposes; the install publishes. When the memo changes the result
  carries `:publish!`, a thunk the router calls once the candidate has
  installed. A throw, a rejected candidate or an aborted event never
  publishes, so there is nothing to undo. Publishing first retains the memo
  the event started from for its epoch record
  (`rf.flows.registry/memo-definitions`)."
  [frame-id db runtime-db]
  (let [ids   (:flows (rf.frame/frame-config frame-id))
        prior (rf.flows.registry/frame-last-inputs-snapshot frame-id)]
    (if (and (empty? ids) (empty? prior))
      {:db db}
      (let [active (active-flows ids)
            [db rows] (reconcile-departures frame-id ids active db prior)
            _     (rf.flows.topo/detect-output-path-overlap!
                    active 're-frame.flows/run-flows-on-db)
            order (rf.flows.topo/topo-sort
                    active 're-frame.flows/run-flows-on-db)
            [db rows]
            (reduce
              (fn [[db rows] id]
                (let [flow       (get active id)
                      row        (get rows id)
                      new-inputs (read-inputs db runtime-db flow)]
                  (if (and (identical? flow (:flow row)) (= new-inputs (:inputs row)))
                    [(reassert-output frame-id db runtime-db flow new-inputs row) rows]
                    (let [[db output] (evaluate-flow frame-id db runtime-db flow new-inputs)]
                      [db (assoc rows id {:flow flow :inputs new-inputs :output output})]))))
              [db rows]
              order)]
        (cond-> {:db db}
          (not (identical? rows prior))
          (assoc :publish! (fn []
                             (when rf.interop/debug-enabled?
                               (rf.flows.registry/retain-snapshot-memo!
                                 frame-id rf.frame/*run-frame-state-before* prior))
                             (rf.flows.registry/publish-memo! frame-id rows))))))))

;; ---- late-bind hook registration ----------------------------------------
;;
;; Core cannot statically require this optional artefact. Keep each publication
;; as a literal call so the late-bind drift gate can discover it.

(rf.late-bind/set-fn! :flows/reg-flow           reg-flow)
(rf.late-bind/set-fn! :flows/clear-flow         rf.flows.registry/clear-flow)
(rf.late-bind/set-fn! :flows/check-activation!  rf.flows.registry/check-activation!)
(rf.late-bind/set-fn! :flows/run-flows-on-db    run-flows-on-db)
(rf.late-bind/set-fn! :flows/reset-flows!       reset-flows!)
(rf.late-bind/set-fn! :flows/output-classification output-classification)
;; An epoch record keeps the memo its snapshots were computed under, and a
;; restore re-seeds it. Spec 013 §Re-registration.
(rf.late-bind/set-fn! :flows/memo-definitions   rf.flows.registry/memo-definitions)
(rf.late-bind/set-fn! :flows/reseed-memo!       rf.flows.registry/reseed-memo!)
;; Frame destruction drops the frame's memo: the flow definitions are registrar
;; rows a destroy leaves alone, so the memo is the whole teardown. Spec 002 §Lifecycle contributions.
(rf.frame/contribute-lifecycle!
  {:id                 :flows
   :phase              #{:post-dead}
   :config-keys        #{:flows}
   :on-frame-destroyed (fn [frame-id _phase]
                         (rf.flows.registry/teardown-on-frame-destroy! frame-id))})
