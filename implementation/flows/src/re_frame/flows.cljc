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
  (:require [re-frame.elision :as rf.elision]
            [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.flows.topo :as rf.flows.topo]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.trace :as rf.trace]
            ;; Keep tooling unreachable from the CLJS facade so Closure can
            ;; remove it when no tool requires the sibling namespace.
            #?@(:clj [[re-frame.flows.tooling :as rf.flows.tooling]])))

(def ^:no-doc stale-incarnation
  "Internal return marker: the exact frame owner vanished during a flow pass."
  :rf.flow/stale-incarnation)

(defn- owner-live?
  [frame-id owner-token exact-owner?]
  (or (not exact-owner?)
      (rf.frame/event-continuation-live? frame-id owner-token)))

;; ---- public-surface re-exports -------------------------------------------
;;
;; `last-inputs-snapshot` exposes raw cached values for tests and rollback. It
;; is not a tooling or egress surface; trace payloads use the elided path.

(def flows-snapshot       rf.flows.registry/flows-snapshot)
(def ^:no-doc last-inputs-snapshot rf.flows.registry/last-inputs-snapshot)

;; Flow metadata is frame-scoped and therefore cannot use the frame-blind
;; registrar metadata slot.
(def flow-meta-at       rf.flows.registry/flow-meta-at)

(def reg-flow           rf.flows.registry/reg-flow)
;; rf2-kuky.80: no public `clear-flow` re-export here — the registrar inverse
;; is the one kind-keyed `(rf/clear :flow id)`. The registry fn below stays as
;; the late-bind hook target.
(def reset-flows!       rf.flows.registry/reset-flows!)
(def reset-last-inputs! rf.flows.registry/reset-last-inputs!)

;; JVM tools get a convenience alias. CLJS tools require the bundle-isolated
;; sibling directly.
#?(:clj
   (def flow-algebra-view rf.flows.tooling/flow-algebra-view))

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

(defn- validate-output!
  "Validate a computed output through the optional schemas artefact.

  Validation is diagnostic: it emits a schema failure but does not unwind the
  pending flow cascade. The caller invokes this only in debug builds."
  [frame-id owner-token exact-owner? flow new-output]
  (let [live?  #(owner-live? frame-id owner-token exact-owner?)
        schema (:schema flow)]
    (cond
      (not (live?)) false
      ;; KEY-presence, not value truthiness (rf2-6eh5h): a present nil /
      ;; false `:schema` on the flow is a declaration whose exact token is
      ;; delegated to the registered validator below; only an ABSENT key
      ;; means no declaration.
      (not (contains? flow :schema)) true
      :else
      (if-let [validate (rf.late-bind/get-fn-cached
                          :schemas/validate-with-registered-fn)]
        (let [valid? (validate schema new-output)]
          ;; Registered schema functions are authored callback boundaries.
          ;; Once A is gone, their result and every later diagnostic are inert.
          (cond
            (not (live?)) false
            valid? true
            :else
            (let [explain     (rf.late-bind/get-fn-cached
                                :schemas/explain-with-registered-fn)
                  explanation (when explain (explain schema new-output))]
              (if-not (live?)
                false
                (let [redact (rf.late-bind/get-fn-cached
                               :schemas/redact-validation-tags)
                      tags   {:category   :rf.error/schema-validation-failure
                              :where      :flow-output
                              :rf.flow/id (:id flow)
                              :failing-id (:id flow)
                              :schema-id  (:id flow)
                              :path       (:output-path flow)
                              :value      (rf.elision/elide-wire-value
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
                      (rf.trace/emit-error!
                        :rf.error/schema-validation-failure tags)
                      (live?))))))))
        true))))

;; ---- who is running this pass --------------------------------------------
;;
;; The evaluator is shared: the router runs it over an event's PENDING db, and
;; the out-of-drain settle below runs the SAME pass over the COMMITTED one.
;; Everything about evaluation is identical across the two — same topological
;; sort, same dirty check, same phases, same error id — so nothing here
;; branches on the caller except the one thing that genuinely differs: what a
;; failure LEAVES BEHIND, and therefore what the diagnostic may truthfully say
;; about it.
;;
;; In a drain the pass output is a candidate `:db` the router discards on a
;; throw, so the event aborts before the install and app-db is unchanged. In a
;; direct `clear-flow`'s settle the deregistration and output-path vacation are
;; ALREADY COMMITTED and intentionally stand (Spec 013 §The same boundary for a
;; direct `clear-flow`, point 2); only the settle's candidate db is dropped.
;; Reporting the drain's sentence to that caller states three things that are
;; each false — that a drain ran, that an event aborted, and that app-db is
;; unchanged — and sends the reader looking for an event that never existed.
;;
;; A dynamic var rather than an extra parameter: the pass's hot loop already
;; carries seven arguments, this one is read at exactly ONE site on the failure
;; path, and the settle is synchronous, so the binding cannot outlive its pass.

(def ^:private ^:dynamic *pass-caller*
  "Which caller's commit boundary the running flow pass belongs to — `:drain`
  (the router, over a pending db) or `:direct-clear` (`settle-frame-flows!`,
  over the committed one). Read only by [[flow-eval-failure!]]."
  :drain)

(defn- failure-site
  "The clause naming WHERE the failing evaluation ran, for `*pass-caller*`."
  []
  (if (= :direct-clear *pass-caller*)
    "during the settle a direct clear-flow runs before it returns"
    "during the drain"))

(defn- failure-aftermath
  "The clause naming what the failure LEFT BEHIND, for `*pass-caller*`.

  Both branches say the same operational thing — no partial output committed,
  re-attempt next drain — about two different commit boundaries."
  []
  (if (= :direct-clear *pass-caller*)
    (str "the clear itself stands (its flow is already deregistered and its "
         "output leaf vacated — that is what the caller asked for), the "
         "settle's candidate app-db is discarded unwritten (no partial commit, "
         "no flow output changed) and the flow re-attempts on the next drain")
    (str "the event aborts before the :db install (no partial commit, app-db "
         "unchanged) and the flow re-attempts on the next drain")))

(defn- failure-db-noun
  "How to name the app-db the failing install could not write, for
  `*pass-caller*`. The drain writes a PENDING db; the direct settle recomputes
  against the committed one."
  []
  (if (= :direct-clear *pass-caller*) "app-db" "the pending app-db"))

(defn- flow-eval-failure!
  "Report one flow-evaluation failure, attributed to the phase that actually
  threw, and re-raise it for the router.

  `phase` is `:derive` when the authored `:derive` callback threw, and
  `:output-write` when `:derive` RETURNED and the framework's own installation
  of that value at the flow's declared `:output-path` threw — most often
  because the pending app-db holds a container at that path which cannot accept
  the path's final segment (a keyword segment over a vector, say).

  The two are discriminated STRUCTURALLY, by which `try` in `evaluate-flow!`
  caught the throw — never by reading the exception's message. The callback and
  the install sit in separate `try` forms, so an install failure cannot reach
  the derive handler and be reported as authored code failing.

  Returns `stale-incarnation` when the owning incarnation died during the
  callback: a callback may destroy A and then throw, and terminal loss wins —
  the old callback's exception produces no B-attributed failure. Otherwise
  emits the per-flow `:rf.flow/failed` trace and throws the aggregate
  `:rf.error/flow-eval-exception`, carrying the phase on BOTH the dev trace and
  the thrown ex-data so the router can lift it onto the always-on production
  error record.

  The error id, the phase taxonomy, the trace tags and the thrown ex-data are
  the same for every caller. Only the human `:reason` sentence varies, and only
  in the two clauses that describe the CALLER's commit boundary — see
  `*pass-caller*`."
  [frame-id owner-token exact-owner? flow new-inputs phase e]
  (if-not (owner-live? frame-id owner-token exact-owner?)
    stale-incarnation
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
      (if-not (owner-live? frame-id owner-token exact-owner?)
        stale-incarnation
        (rf.error/throw-error!
          :rf.error/flow-eval-exception
          'rf/run-flows-on-db
          (if (= :derive phase)
            (str "a flow's :derive fn threw while recomputing flow "
                 (pr-str flow-id)
                 " " (failure-site) "; " (failure-aftermath)
                 ". Fix the :derive fn so it "
                 "does not throw on the inputs it is given.")
            (str "flow " (pr-str flow-id)
                 "'s :derive fn RETURNED normally, but re-frame could not "
                 "install that value at the flow's declared :output-path "
                 (pr-str output-path)
                 " — " (failure-db-noun) " holds a container at that path which "
                 "cannot accept it. The :derive fn did not throw; the failure "
                 "is the framework's output write, " (failure-site) "; "
                 (failure-aftermath)
                 ". Fix the :output-path, or "
                 "the shape " (failure-db-noun) " holds at its parent path — do "
                 "not go looking in the :derive fn."))
          {:recovery :no-recovery
           :extra    {:rf.flow/failed-id    flow-id
                      :rf.flow/failed-phase phase
                      :rf.flow/output-path  output-path
                      :cause                e}})))))

(defn- evaluate-flow!
  "Evaluate one flow against the pending frame-state.

  Returns the transformed db, or `stale-incarnation` when the owning
  incarnation died mid-visit — the same two-outcome discrimination
  `run-flows-on-db*` itself returns to its own caller.

  A failure is rethrown with the failing flow id AND the
  failing PHASE for router attribution — `:derive` for the authored callback,
  `:output-write` for the framework's install of its returned value (see
  [[flow-eval-failure!]]); `run-flows-on-db` restores dirty-check state and the
  router discards the pending db, so no partial output is committed either way.

  Trace payloads and their elision walks stay inside `debug-enabled?` branches
  so Closure removes them from production CLJS builds."
  [frame-id owner-token exact-owner? pass db runtime-db flow]
  (if-not (owner-live? frame-id owner-token exact-owner?)
    stale-incarnation
    (let [flow-id    (:id flow)
          new-inputs (read-inputs db runtime-db flow)
          ;; Read and write the captured A-owned cell, never a bare-id lookup
          ;; that could resolve to replacement B after a callback.
          old-inputs (rf.flows.registry/pass-flow-last-inputs pass flow-id)]
      (if (= new-inputs old-inputs)
        (do
          (when rf.interop/debug-enabled?
            (rf.trace/emit! :flow :rf.flow/skip
                         {:flow-id               flow-id
                          :reason                :inputs-value-equal
                          :input-paths-unchanged (:inputs flow)
                          :frame                 frame-id}))
          (if (owner-live? frame-id owner-token exact-owner?)
            db
            stale-incarnation))
        ;; The authored `:derive` callback and the framework's own output
        ;; installation are caught by SEPARATE `try` forms.  The split is the
        ;; whole attribution mechanism: an `assoc-in` that cannot write the
        ;; declared `:output-path` into the pending app-db is structurally
        ;; unreachable from the derive handler, so it can never be reported as
        ;; the programmer's `:derive` fn throwing (rf2-gpj9r).
        (let [started-at-ms  (when rf.interop/debug-enabled? (rf.interop/now-ms))
              derive-outcome (try
                               {::output (apply (:derive flow) new-inputs)}
                               (catch #?(:clj Throwable :cljs :default) e
                                 {::thrown e}))]
          (if (contains? derive-outcome ::thrown)
            (flow-eval-failure! frame-id owner-token exact-owner?
                                flow new-inputs :derive (::thrown derive-outcome))
            ;; `:derive` is the principal authored callback boundary.  Its
            ;; value is inert once A loses ownership; no cache write, trace,
            ;; validation or later flow may be attributed to B.
            (if-not (owner-live? frame-id owner-token exact-owner?)
              stale-incarnation
              (try
                (let [new-output      (::output derive-outcome)
                      flow-elapsed-ms (when rf.interop/debug-enabled?
                                        (- (rf.interop/now-ms) started-at-ms))
                      old-output      (when rf.interop/debug-enabled?
                                        (get-in db (:output-path flow)))
                      new-db          (assoc-in db (:output-path flow)
                                                new-output)]
                  (rf.flows.registry/pass-set-flow-last-inputs!
                    pass flow-id new-inputs)
                  (when rf.interop/debug-enabled?
                    (rf.trace/emit! :flow :rf.flow/computed
                                 {:flow-id      flow-id
                                  :input-values (elide-inputs frame-id flow new-inputs)
                                  :before       (rf.elision/elide-wire-value
                                                  old-output
                                                  {:frame frame-id
                                                   :path (:output-path flow)})
                                  :result       (rf.elision/elide-wire-value
                                                  new-output
                                                  {:frame frame-id
                                                   :path (:output-path flow)})
                                  :path         (:output-path flow)
                                  :elapsed-ms   flow-elapsed-ms
                                  :frame        frame-id}))
                  (if-not (owner-live? frame-id owner-token exact-owner?)
                    stale-incarnation
                    (if (or (not rf.interop/debug-enabled?)
                            (validate-output! frame-id owner-token exact-owner?
                                              flow new-output))
                      new-db
                      stale-incarnation)))
                (catch #?(:clj Throwable :cljs :default) e
                  (flow-eval-failure! frame-id owner-token exact-owner?
                                      flow new-inputs :output-write e))))))))))

(defn- run-flows-on-db*
  [frame-id db runtime-db owner-token exact-owner?]
  (if-not (owner-live? frame-id owner-token exact-owner?)
    stale-incarnation
    (let [pass (if exact-owner?
                 (rf.flows.registry/capture-flow-pass-state frame-id owner-token)
                 (rf.flows.registry/legacy-flow-pass-state frame-id))]
      (if-not pass
        stale-incarnation
        (let [flow-map          (:flow-map pass)
              abandoned-before (rf.flows.registry/pass-abandoned-paths-snapshot pass)
              abandoned-paths  (rf.flows.registry/pass-drain-abandoned-paths! pass)
              db               (reduce rf.flows.registry/vacate-path-in-db
                                       db abandoned-paths)]
          (if-not (owner-live? frame-id owner-token exact-owner?)
            stale-incarnation
            (if-not (seq flow-map)
              db
              (let [ordered            (rf.flows.topo/topo-sort flow-map)
                    last-inputs-before (rf.flows.registry/pass-last-inputs-snapshot pass)]
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
                          (recur (rest remaining) result)))))
                  (catch #?(:clj Throwable :cljs :default) e
                    ;; Exact loss detached A's cells during destroy; restoring
                    ;; through the bare id would corrupt B.  When A is still
                    ;; live, restore the captured cells and preserve the
                    ;; ordinary flow-throw contract.
                    (if-not (owner-live? frame-id owner-token exact-owner?)
                      stale-incarnation
                      (do
                        (rf.flows.registry/pass-reset-last-inputs!
                          pass last-inputs-before)
                        (rf.flows.registry/pass-restore-abandoned-paths!
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
   ;; Preserve the established three-argument late-bind contract. Inside an
   ;; event, core's private owner binding supplies the exact A token; direct
   ;; callers have no token and retain the legacy frame-id-scoped behaviour.
   (if-let [owner-token (rf.frame/current-event-owner-token)]
     (run-flows-on-db* frame-id db runtime-db owner-token true)
     (run-flows-on-db* frame-id db runtime-db nil false)))
  ([frame-id db runtime-db {:keys [exact-owner-token]}]
   (run-flows-on-db* frame-id db runtime-db exact-owner-token true)))

;; ---- out-of-drain lifecycle settle ---------------------------------------

(defn- settle-frame-flows!
  "Recompute `frame-id`'s remaining flows against its LIVE app-db and install
  the result, fenced to `owner-token`'s incarnation.

  The out-of-drain half of the Spec 013 §Sequencing boundary. In a drain the
  router runs `run-flows-on-db` over the pending `:db`; a direct
  `clear-flow` has no pending db, so it runs the SAME pass over the committed
  one. This adds no evaluation semantics of its own — same topological sort,
  same dirty check, same failure taxonomy — it only supplies the db and writes
  the result back.

  The candidate is computed BEFORE anything is written, so the three
  non-results all leave app-db exactly as the vacation left it: a lost
  incarnation (`stale-incarnation`), a pass that changed nothing (dirty check
  clean throughout — the common case, and it must not manufacture a write),
  and a `:derive` throw, which propagates the ordinary
  `:rf.error/flow-eval-exception` to the direct caller with no partial output
  installed. `run-flows-on-db` restores the dirty-check cache and pending
  vacations on that throw, so the next drain re-attempts normally.

  The pass runs under `*pass-caller*` `:direct-clear` so that throw's human
  `:reason` describes THIS boundary — the clear stands, the candidate is
  dropped — rather than the drain's abort, which never happened here. The
  binding wraps the whole pass because the throw is raised deep inside it, and
  unwinds with it because the settle is synchronous.

  `registry/clear-flow` calls this through the seam it publishes; the
  `:require` runs the other way."
  [frame-id owner-token]
  (when-let [db (rf.frame/frame-app-db-value frame-id)]
    (let [runtime-db (rf.frame/frame-runtime-db-value frame-id)
          settled    (binding [*pass-caller* :direct-clear]
                       (run-flows-on-db frame-id db runtime-db
                                        {:exact-owner-token owner-token}))]
      (when-not (or (= stale-incarnation settled)
                    (identical? settled db))
        (rf.frame/swap-frame-db-exact! frame-id owner-token (constantly settled)))))
  nil)

(rf.flows.registry/set-settle-fn! settle-frame-flows!)

;; ---- late-bind hook registration ----------------------------------------
;;
;; Core cannot statically require this optional artefact. Keep each publication
;; as a literal call so the late-bind drift gate can discover it.

(rf.late-bind/set-fn! :flows/reg-flow           reg-flow)
(rf.late-bind/set-fn! :flows/clear-flow         rf.flows.registry/clear-flow)
(rf.late-bind/set-fn! :flows/run-flows-on-db    run-flows-on-db)
(rf.late-bind/set-fn! :flows/reset-flows!       reset-flows!)
;; The router owns post-transform commit rollback. These pairs let it restore
;; the eager dirty-check advances and consumed path vacations that this
;; artefact cannot otherwise observe after returning.
(rf.late-bind/set-fn! :flows/snapshot-last-inputs rf.flows.registry/frame-last-inputs-snapshot)
(rf.late-bind/set-fn! :flows/restore-last-inputs!  rf.flows.registry/reset-frame-last-inputs-to!)
(rf.late-bind/set-fn! :flows/snapshot-abandoned-paths rf.flows.registry/abandoned-output-paths-snapshot)
(rf.late-bind/set-fn! :flows/restore-abandoned-paths!  rf.flows.registry/restore-abandoned-output-paths!)
;; Frame destruction must release registry rows and caches owned by that frame.
(rf.late-bind/set-fn! :flows/teardown-on-frame-destroy!
                   rf.flows.registry/teardown-on-frame-destroy!)
