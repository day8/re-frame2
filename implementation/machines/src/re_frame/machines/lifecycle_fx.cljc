(ns re-frame.machines.lifecycle-fx
  "Registration boundary and live-lifecycle fx handlers for state machines.

  Three concerns share the file because they all sit on the
  registration / app-db / live-handler edge:

   1. **Registration**: `create-machine-handler` (the event-fx handler
      factory) and `reg-machine*` (the plain-fn surface beneath the
      `reg-machine` macro). Registration-time validation
      (`validate-machine!`) and lazy snapshot synthesis
      (`synthesise-initial-snapshot`) extracted per rf2-f9tu.

   2. **Live-lifecycle fxs**: `spawn-fx`, `destroy-machine-fx`, and
      `invoke-all-init-fx` — the runtime side of declarative `:invoke`
      and `:invoke-all`. Per rf2-xbtj these handlers ship inside the
      machines artefact (rather than `re-frame.fx`'s reserved
      case-block) so an app that doesn't pull in
      `day8/re-frame2-machines` carries neither the trace strings nor
      the handler symbols on its production-elision bundle.

   3. **Query API**: `machines`, `machine-meta`, and
      `machine-by-system-id` — pure reads over the existing event
      registry and the per-frame `[:rf/system-ids]` reverse index.

  Pure transition mechanics live in `re-frame.machines.transition`;
  `:after` timer scheduling lives in `re-frame.machines.timer`."
  (:require [re-frame.events :as events]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.transition :as transition]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

(defn- snapshot-path [machine-id]
  [:rf/machines machine-id])

;; ---- :invoke-all join-event interception (rf2-6vmw) ----------------------
;;
;; Per Spec 005 §Spawn-and-join via :invoke-all, the parent's handler
;; boundary intercepts events whose inner-event-id matches the active
;; state's :on-child-done / :on-child-error. The interception:
;;
;;   1. Resolves the active :invoke-all-bearing state by walking the
;;      snapshot's :state path leaf→root looking for a state node whose
;;      :invoke-all declares the matching event keyword.
;;   2. Reads the join state at [:rf/spawned <parent> <invoke-id>].
;;   3. Adds <child-id> (event[1]) to :done or :failed.
;;   4. If :resolved? is already true, the event is silently dropped
;;      (post-resolution late-completion).
;;   5. Else evaluates the join condition. On resolution:
;;        - latches :resolved? true
;;        - if :cancel-on-decision? (default true), emits per-sibling
;;          :rf.machine/destroy fx and :rf.machine.invoke/cancelled-on-
;;          join-resolution traces
;;        - dispatches the parent join event via :fx [[:dispatch ...]]
;;   6. Writes the new join state back into app-db.

(defn- find-active-invoke-all-in-tree
  "Helper for find-active-invoke-all. Given a machine-like map with
  `:states` (for a non-parallel machine, the machine itself; for a
  region of a parallel machine, the region body) and a path inside
  that tree, walk leaf→root looking for an :invoke-all-bearing state
  whose :on-child-done or :on-child-error matches inner-event-id."
  [tree path inner-event-id]
  (loop [i (dec (count path))]
    (when (>= i 0)
      (let [prefix (vec (take (inc i) path))
            n      (transition/node-at tree prefix)
            ia     (:invoke-all n)]
        (cond
          (and ia (= inner-event-id (:on-child-done ia)))
          {:invoke-id prefix :spec ia :kind :done}
          (and ia (= inner-event-id (:on-child-error ia)))
          {:invoke-id prefix :spec ia :kind :failed}
          :else
          (recur (dec i)))))))

(defn- find-active-invoke-all
  "Walk the snapshot's :state path leaf→root looking for an
  :invoke-all-bearing state whose :on-child-done or :on-child-error
  matches the given inner-event-id. Returns
  {:invoke-id <prefix-path> :spec <invoke-all-spec> :kind :done|:failed}
  or nil.

  Per Spec 005 §Parallel regions (rf2-l67o): for parallel-region
  machines, iterates each region's active state-tree (prefixing the
  region name onto the resolved :invoke-id, matching the per-region
  scoping prefix-region-invoke-id applies on the entry-side)."
  [machine snapshot inner-event-id]
  (cond
    (parallel/parallel? machine)
    (some (fn [[region-name region-state]]
            (let [region-body (parallel/region-machine machine region-name)
                  region-path (transition/state-path region-state)
                  match       (find-active-invoke-all-in-tree
                                region-body region-path inner-event-id)]
              (when match
                (update match :invoke-id #(vec (cons region-name %))))))
          (:state snapshot))

    :else
    (find-active-invoke-all-in-tree machine (transition/state-path (:state snapshot)) inner-event-id)))

(defn- join-condition-met?
  "Evaluate the join condition against the current join state.
  Returns truthy iff the join has resolved on the success-side
  (:on-all-complete / :on-some-complete should fire)."
  [spec join-state]
  (let [join     (:join spec :all)
        children (:children spec)
        n-total  (count children)
        n-done   (count (:done   join-state))
        n-failed (count (:failed join-state))]
    (cond
      (= :all join)
      (= n-done n-total)

      (= :any join)
      (>= n-done 1)

      (and (map? join) (pos-int? (:n join)))
      (>= n-done (:n join))

      (and (map? join) (fn? (:fn join)))
      ((:fn join) {:done   (:done   join-state)
                   :failed (:failed join-state)
                   :total  n-total})

      :else false)))

(defn- intercept-invoke-all-event
  "Per Spec 005 §Child completion protocol (rf2-6vmw). When the parent's
  handler receives an event whose inner event-id matches the active
  :invoke-all-bearing state's :on-child-done / :on-child-error, the
  runtime updates the join state and (on resolution) cancels surviving
  siblings + dispatches the join event. The event is NOT fed into the
  machine's normal :on lookup.

  Returns nil (NOT a child-event for any active :invoke-all) or a
  re-frame effect map with :db (updated app-db) and :fx (per-sibling
  destroys + the join-event dispatch)."
  [machine db _path snapshot parent-id inner-event]
  (let [inner-id (first inner-event)
        match    (find-active-invoke-all machine snapshot inner-id)]
    (when match
      (let [{:keys [invoke-id spec kind]} match
            child-id   (second inner-event)
            ;; Read the live join state from app-db (the seed was written
            ;; by :rf.machine/invoke-all-init on entry).
            join-state (get-in db [:rf/spawned parent-id invoke-id])]
        (cond
          ;; Pure-call snapshot: no app-db join state seeded yet — fall
          ;; through to no-op (the runtime tracks join state via the fx
          ;; handlers, not via the pure machine-transition).
          (or (not (map? join-state))
              (not (contains? join-state :children)))
          {:db db :fx []}

          ;; Already resolved: ignore late-completion. Trace once for
          ;; observability so tools can correlate.
          (:resolved? join-state)
          (do (trace/emit! :machine :rf.machine.invoke-all/late-completion
                           {:machine-id parent-id
                            :invoke-id  invoke-id
                            :child-id   child-id
                            :kind       kind})
              {:db db :fx []})

          :else
          (let [join-state' (case kind
                              :done   (update join-state :done   (fnil conj #{}) child-id)
                              :failed (update join-state :failed (fnil conj #{}) child-id))
                cancel?  (let [c (:cancel-on-decision? spec)]
                           (if (nil? c) true (boolean c)))
                fail-fired?
                (and (= kind :failed)
                     (vector? (:on-any-failed spec)))
                success-fired?
                (and (not fail-fired?)
                     (join-condition-met? spec join-state'))
                resolution-event
                (cond
                  fail-fired?    (:on-any-failed spec)
                  success-fired? (cond
                                   (= :all (:join spec :all)) (:on-all-complete spec)
                                   :else                       (:on-some-complete spec)))
                resolved? (boolean (or fail-fired? success-fired?))
                join-state'' (assoc join-state' :resolved? resolved?)
                _ (when fail-fired?
                    (trace/emit! :machine :rf.machine.invoke-all/any-failed
                                 {:machine-id parent-id
                                  :invoke-id  invoke-id
                                  :failed-id  child-id
                                  :failed     (:failed join-state'')
                                  :done       (:done   join-state'')}))
                _ (when (and success-fired? (= :all (:join spec :all)))
                    (trace/emit! :machine :rf.machine.invoke-all/all-completed
                                 {:machine-id parent-id
                                  :invoke-id  invoke-id
                                  :done       (:done join-state'')}))
                _ (when (and success-fired? (not= :all (:join spec :all)))
                    (trace/emit! :machine :rf.machine.invoke-all/some-completed
                                 {:machine-id parent-id
                                  :invoke-id  invoke-id
                                  :done       (:done join-state'')
                                  :join       (:join spec)}))
                cancel-fx
                (when (and resolved? cancel?)
                  (let [completed-ids (into #{} (concat (:done join-state'')
                                                        (:failed join-state'')))
                        survivors     (->> (:children join-state'')
                                           (remove (fn [[cid _]] (contains? completed-ids cid))))
                        join-event-kw (cond
                                        fail-fired?    :on-any-failed
                                        (= :all (:join spec :all)) :on-all-complete
                                        :else :on-some-complete)]
                    (doseq [[cid spawned-id] survivors]
                      (trace/emit! :machine :rf.machine.invoke/cancelled-on-join-resolution
                                   {:machine-id parent-id
                                    :invoke-id  invoke-id
                                    :child-id   cid
                                    :spawned-id spawned-id
                                    :join-event join-event-kw}))
                    (mapv (fn [[_ spawned-id]]
                            [:rf.machine/destroy spawned-id])
                          survivors)))
                dispatch-fx
                (when resolution-event
                  [[:dispatch (into [parent-id resolution-event] [])]])
                fx (vec (concat (or cancel-fx []) (or dispatch-fx [])))
                new-db (assoc-in db [:rf/spawned parent-id invoke-id] join-state'')]
            {:db new-db :fx fx}))))))

;; ---- registration-time validation -----------------------------------------
;;
;; Per rf2-3y3y / Spec 005 §Wall-clock timeouts on :invoke: the
;; pre-release :timeout-ms / :on-timeout slot on :invoke / :invoke-all
;; was DROPPED. State-level :after on the parent state subsumes the
;; wall-clock guard, with the standard exit-cascade destroying spawned
;; children.

(defn- validate-no-invoke-timeout-ms!
  "Per rf2-3y3y / Spec 005 §Wall-clock timeouts on :invoke — use parent
  state's :after, the pre-release :timeout-ms / :on-timeout slots on
  :invoke and :invoke-all are DROPPED."
  [state-key state-node]
  (doseq [[slot-key spec]
          [[:invoke     (:invoke state-node)]
           [:invoke-all (:invoke-all state-node)]]]
    (when (map? spec)
      (let [t  (:timeout-ms spec)
            ot (:on-timeout spec)]
        (when (or (some? t) (some? ot))
          (throw (ex-info ":rf.error/invoke-timeout-ms-removed"
                          {:state      state-key
                           :slot       slot-key
                           :timeout-ms t
                           :on-timeout ot
                           :reason
                           (str ":timeout-ms / :on-timeout on " slot-key
                                " were dropped per rf2-3y3y. Use the parent "
                                "state's :after slot for wall-clock guards. "
                                "See MIGRATION.md §M-44 for the rewrite "
                                "recipe.")
                           :migration "MIGRATION.md §M-44"})))))))

(defn- validate-invoke-all!
  "Per Spec 005 §Spawn-and-join via :invoke-all (rf2-6vmw): walk the
  state tree at registration time and reject malformed :invoke-all
  declarations.

  Three error categories:
    - :rf.error/machine-invoke-all-bad-shape — a child invoke-spec is
      missing :id; or :invoke-all is not a vector; or the join-event
      slots are missing per the required-iff rules; or no :machine-id
      / :definition.
    - :rf.error/machine-invoke-all-duplicate-id — two children share an
      :id keyword inside the same :invoke-all block.
    - :rf.error/machine-invoke-all-with-invoke — a state node declares
      both :invoke and :invoke-all (mutually exclusive)."
  [state-key state-node]
  (let [inv-all (:invoke-all state-node)]
    (when inv-all
      (when (:invoke state-node)
        (throw (ex-info ":rf.error/machine-invoke-all-with-invoke"
                        {:state state-key})))
      (when-not (map? inv-all)
        (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                        {:state state-key
                         :reason "invoke-all slot must be a map"})))
      (let [children (:children inv-all)]
        (when-not (and (vector? children) (seq children))
          (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                          {:state state-key
                           :reason ":children must be a non-empty vector of child specs"})))
        (doseq [c children]
          (when-not (and (map? c) (keyword? (:id c)))
            (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                            {:state state-key
                             :reason "each child invoke-spec must declare an :id keyword"
                             :child c})))
          (when-not (or (:machine-id c) (:definition c))
            (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                            {:state state-key
                             :reason "each child invoke-spec must declare :machine-id or :definition"
                             :child c}))))
        (let [ids (map :id children)]
          (when (not= (count ids) (count (set ids)))
            (let [dup (->> (frequencies ids) (filter (fn [[_ n]] (> n 1))) (map first))]
              (throw (ex-info ":rf.error/machine-invoke-all-duplicate-id"
                              {:state state-key :duplicate-ids dup}))))))
      (when-not (keyword? (:on-child-done inv-all))
        (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                        {:state state-key
                         :reason ":on-child-done is required (event keyword)"})))
      (when-not (keyword? (:on-child-error inv-all))
        (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                        {:state state-key
                         :reason ":on-child-error is required (event keyword)"})))
      (let [join (:join inv-all :all)]
        (cond
          (= :all join)
          (when-not (vector? (:on-all-complete inv-all))
            (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                            {:state state-key
                             :reason ":on-all-complete event-vector is required when :join is :all (default)"})))
          (or (= :any join)
              (and (map? join) (or (pos-int? (:n join))
                                   (fn? (:fn join)))))
          (when-not (vector? (:on-some-complete inv-all))
            (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                            {:state state-key
                             :reason ":on-some-complete event-vector is required when :join is :any / {:n N} / {:fn ...}"})))
          :else
          (throw (ex-info ":rf.error/machine-invoke-all-bad-shape"
                          {:state state-key
                           :reason ":join must be :all, :any, {:n pos-int}, or {:fn fn?}"
                           :join join})))))))

(defn- validate-parallel!
  "Per Spec 005 §Parallel regions (rf2-l67o / Stage 2) and Spec-Schemas
  §`:rf/transition-table` §`:type :parallel` constraint: when a root
  state-node declares `:type :parallel`, validate the shape at
  registration time.

  Three error categories:
    - :rf.error/machine-parallel-bad-shape — `:type :parallel` declared
      without a `:regions` map, OR `:regions` is empty, OR `:regions`
      coexists with `:initial` / `:states`, OR a region body is missing
      its own `:initial`.
    - :rf.error/machine-parallel-nested-not-supported — a region's own
      state-tree declares `:type :parallel`; nested parallel regions
      aren't supported in v1."
  [machine]
  (when (parallel/parallel? machine)
    (when-not (and (map? (:regions machine)) (seq (:regions machine)))
      (throw (ex-info ":rf.error/machine-parallel-bad-shape"
                      {:reason ":type :parallel requires a non-empty :regions map"})))
    (when (or (contains? machine :initial) (contains? machine :states))
      (throw (ex-info ":rf.error/machine-parallel-bad-shape"
                      {:reason ":type :parallel is mutually exclusive with :initial / :states at the root"})))
    (doseq [[region-name region-body] (:regions machine)]
      (when-not (keyword? region-name)
        (throw (ex-info ":rf.error/machine-parallel-bad-shape"
                        {:reason "region names must be keywords"
                         :region region-name})))
      (when-not (and (map? region-body) (seq region-body))
        (throw (ex-info ":rf.error/machine-parallel-bad-shape"
                        {:reason "each region body must be a non-empty state-node map"
                         :region region-name})))
      (when (= :parallel (:type region-body))
        (throw (ex-info ":rf.error/machine-parallel-nested-not-supported"
                        {:reason "nested parallel regions are not supported in v1"
                         :region region-name})))
      (when-not (keyword? (:initial region-body))
        (throw (ex-info ":rf.error/machine-parallel-bad-shape"
                        {:reason "each region body must declare :initial (the cascade entry-point)"
                         :region region-name})))
      (letfn [(walk [path nodes]
                (doseq [[k n] nodes]
                  (when (= :parallel (:type n))
                    (throw (ex-info ":rf.error/machine-parallel-nested-not-supported"
                                    {:reason "nested parallel regions are not supported in v1"
                                     :region region-name
                                     :state-path (conj path k)})))
                  (when (:states n)
                    (walk (conj path k) (:states n)))))]
        (walk [] (:states region-body))))))

(defn- walk-state-nodes
  "Yield [state-key state-node] pairs for every node under :states,
  recursing through :states maps. Used by registration-time validators.

  Per Spec 005 §Parallel regions (rf2-l67o / Stage 2): for parallel-region
  machines, walks the state nodes under every region's :states. Region-
  name keywords are NOT yielded as state keys (they're region
  identifiers, not states)."
  [machine]
  (letfn [(walk [path nodes]
            (mapcat
              (fn [[k n]]
                (cons [k n]
                      (when (:states n)
                        (walk (conj path k) (:states n)))))
              nodes))]
    (cond
      (parallel/parallel? machine)
      (mapcat (fn [[_region region-body]] (walk [] (:states region-body)))
              (:regions machine))

      :else
      (walk [] (:states machine)))))

(defn- validate-final-state!
  "Per Spec 005 §Final states (rf2-gn80) §`:final?` constraints:

   - A `:final?` state MUST NOT be compound (no `:states`, no `:initial`).
   - A `:final?` state MUST NOT declare `:on`, `:always`, `:after`,
     `:invoke`, or `:invoke-all` — final means final, no further
     transitions out. `:entry` and `:exit` ARE permitted.
   - A non-final state declaring `:output-key` is a registration error
     (`:rf.error/machine-output-key-without-final`).

  Reject malformed declarations at registration time."
  [state-key state-node]
  (cond
    (true? (:final? state-node))
    (do
      (when (or (contains? state-node :states)
                (contains? state-node :initial))
        (throw (ex-info ":rf.error/machine-final-state-compound"
                        {:state state-key
                         :reason "a :final? state cannot be compound (no :states / :initial)."})))
      (doseq [bad-key [:on :always :after :invoke :invoke-all]]
        (when (contains? state-node bad-key)
          (throw (ex-info ":rf.error/machine-final-state-has-transitions"
                          {:state    state-key
                           :slot     bad-key
                           :reason   (str "a :final? state cannot declare " bad-key
                                          " — final means final; no further transitions.")})))))

    ;; Non-final state declaring :output-key — error per D3.
    (contains? state-node :output-key)
    (throw (ex-info ":rf.error/machine-output-key-without-final"
                    {:state      state-key
                     :output-key (:output-key state-node)
                     :reason     ":output-key is only meaningful on a :final? state."}))))

(defn- validate-machine!
  "Run every registration-time check the machine grammar requires (rf2-f9tu).
  Composed at the top of `create-machine-handler` so the registered handler
  fn's body is exclusively about request processing.

  Per Spec 005 §Parallel regions (rf2-l67o / Stage 2): `:type :parallel`
  shape — `:regions` non-empty, mutually exclusive with `:initial` /
  `:states`, no nested parallel.

  Per Spec 005 §Spawn-and-join via `:invoke-all` (rf2-6vmw): every
  `:invoke-all`-bearing state node — shape, no duplicate `:id`s, required
  join-event keys per `:join` form, mutually exclusive with `:invoke`.

  Per rf2-3y3y: every `:invoke` / `:invoke-all` rejects the dropped
  `:timeout-ms` / `:on-timeout` slot (use parent `:after`).

  Per rf2-oz9t: every `:on` / `:always` / `:entry` / `:exit` slot's guard
  and action keyword refs must resolve against the machine's `:guards` /
  `:actions` maps. Throws `:rf.error/machine-unresolved-guard` /
  `:rf.error/machine-unresolved-action` on dangling refs."
  [machine]
  (validate-parallel! machine)
  (doseq [[s n] (walk-state-nodes machine)]
    (validate-invoke-all! s n)
    (validate-no-invoke-timeout-ms! s n)
    (validate-final-state! s n))
  ;; Validate guard/action references at construction time. machine-id
  ;; isn't known yet (it's the registration-site id), so error tags use
  ;; a placeholder; real misuse traces at handler-call time fill it in.
  (let [guards-map  (:guards machine)
        actions-map (:actions machine)
        check-guard! (fn [g s]
                       (when (and (keyword? g)
                                  (not (contains? guards-map g)))
                         (throw (ex-info ":rf.error/machine-unresolved-guard"
                                         {:guard g :state s}))))
        check-action! (fn [a s]
                        (when (and (keyword? a)
                                   (not (contains? actions-map a)))
                          (throw (ex-info ":rf.error/machine-unresolved-action"
                                          {:action a :state s}))))]
    (doseq [[s state-node] (walk-state-nodes machine)]
      (doseq [[_ t] (:on state-node)
              t     (if (vector? t) t [t])]
        (check-guard!  (:guard t)  s)
        (check-action! (:action t) s))
      (doseq [t (:always state-node)]
        (check-guard!  (:guard t)  s)
        (check-action! (:action t) s))
      (check-action! (:entry state-node) s)
      (check-action! (:exit  state-node) s))))

(defn- synthesise-initial-snapshot
  "Build the lazily-synthesised initial snapshot for `machine` (rf2-f9tu).

  Per Spec 005 §Initial-state cascading and §Parallel regions: for flat /
  compound machines, descends the root `:initial` cascade to a leaf path;
  for parallel-region machines, `:state` becomes a map of region-name →
  that region's cascaded initial.

  Per Spec 005 §Snapshot shape (`{:state :data :meta?}`): the spec's
  optional `:meta` propagates onto the snapshot so the 3-arity ctx and
  any downstream version-check see the same `:meta` the spec declares.

  Per Spec 005 §State tags (rf2-ee0d) / §Tags compose across regions
  (rf2-l67o): the initial tag union is stamped via
  `commit-tags-parallel`; the slot is elided when the union is empty."
  [machine]
  (let [initial-state (cond
                        (parallel/parallel? machine)
                        (into {}
                              (for [[rn region-body] (:regions machine)]
                                [rn (parallel/region-initial-state region-body)]))

                        :else
                        (let [decl (:initial machine)]
                          (transition/denormalise-state
                            (transition/initial-cascade machine (transition/state-path decl))
                            decl)))
        initial    (cond-> {:state initial-state
                            :data  (or (:data machine) {})}
                     (some? (:meta machine)) (assoc :meta (:meta machine)))]
    (parallel/commit-tags-parallel machine initial)))

(declare reg-machine*)
(declare destroy-single-actor!)
(declare abort-actor-in-flight-http!)

;; ---- final-state orchestration (rf2-gn80) --------------------------------
;;
;; Per Spec 005 §Final states (rf2-gn80): when a machine enters a `:final?`
;; state, the runtime fires the parent's `:on-done` (if any) and auto-
;; destroys the actor SYNCHRONOUSLY (D4). The orchestration:
;;
;;   1. Resolve the final state-node from the post-transition snapshot
;;      (single / compound / parallel-all-regions-final).
;;   2. Read the child's `:data` slot designated by the final state's
;;      `:output-key` — call it `result`. Absent `:output-key` ⇒ nil.
;;   3. Look up the parent's spec at `[:rf/machines <parent-id>]` and find
;;      the `:invoke` map at `:rf/invoke-id` (the prefix-path the runtime
;;      stamped on the child's `:data` at spawn time). Extract `:on-done`.
;;   4. Run `:on-done` against the parent's `:data` with `result`.
;;   5. Emit `:rf.machine/done` trace (D6).
;;   6. Tear down the child: dissoc snapshot, clear `[:rf/spawned ...]`
;;      slot, clear `[:rf/system-ids <sid>]` (D8 — AFTER `:on-done` ran),
;;      emit `:rf.machine/destroyed` with `:reason :rf.machine/finished`
;;      (D6 enrichment), abort in-flight HTTP, unregister handler (D4).
;;
;; For singleton machines (no `:rf/parent-id` on `:data`): skip steps 3-4
;; and emit a `:rf.machine/done` with `:parent-id nil` (D7 — singleton
;; symmetry). The teardown still runs — the snapshot is dissoc'd, the
;; `:system-id` reverse-index entry is cleared, and the handler is
;; unregistered.

(defn- all-regions-final?
  "Per Spec 005 §Final states §Parallel regions and `:final?` (rf2-gn80):
  a parallel-region machine is `:final?` only when EVERY region's active
  leaf is `:final?`. Walk each region's body + active path and check the
  leaf-node's `:final?` flag. Returns false when ANY region's leaf isn't
  final, or when `:state` is not a parallel state-map."
  [machine state]
  (and (parallel/parallel? machine)
       (map? state)
       (every?
         (fn [[region-name region-state]]
           (let [region-body (get-in machine [:regions region-name])
                 leaf-node   (transition/node-at region-body
                                                  (transition/state-path region-state))]
             (transition/final-state-node? leaf-node)))
         state)))

(defn- find-invoke-spec-at
  "Walk `parent-spec`'s state tree to the node at `invoke-id` (the
  absolute prefix-path stamped at spawn time) and return that node's
  `:invoke` map. For a parallel-region parent, the first element of
  `invoke-id` is the region name (per rf2-l67o); strip and descend
  into that region's body. Returns nil if the path doesn't resolve
  or the node doesn't declare `:invoke`."
  [parent-spec invoke-id]
  (when (and parent-spec (vector? invoke-id) (seq invoke-id))
    (let [[head & tail] invoke-id
          [tree path]   (if (and (parallel/parallel? parent-spec)
                                 (contains? (:regions parent-spec) head))
                          [(get-in parent-spec [:regions head]) (vec tail)]
                          [parent-spec invoke-id])
          node          (transition/node-at tree path)]
      (:invoke node))))

(defn- finalize-machine
  "Per Spec 005 §Final states (rf2-gn80): orchestrate the `:on-done` +
  auto-destroy cascade. Returns `{:db new-db :fx fx}` — the handler's
  return value when the post-transition snapshot is tagged
  `:rf/finished? true`.

  Arguments:
    machine      — the runtime-stamped machine spec (the finishing actor's)
    machine-id   — the finishing actor's id (its event-handler key)
    frame-id     — the frame the actor runs in
    db           — the app-db AT the time the handler was invoked
    next-snapshot — the post-transition snapshot (carries `:rf/finished?`)
    inner-event  — the event that caused the finish (for diagnostics)
    extra-fx     — the fx vector from the transition (passed through)"
  [machine machine-id frame-id db next-snapshot _inner-event extra-fx]
  (let [child-data (:data next-snapshot)
        ;; Resolve the final state-node so we can extract `:output-key`.
        final-node (cond
                     (parallel/parallel? machine)
                     ;; All regions are final per `all-regions-final?` — pick
                     ;; any region's leaf for the output-key resolution. The
                     ;; conventional choice is the first region's leaf; apps
                     ;; with cross-region output needs declare `:output-key`
                     ;; on every region's terminal leaf consistently.
                     (let [state (:state next-snapshot)
                           [region-name region-state] (first state)
                           region-body (get-in machine [:regions region-name])]
                       (transition/node-at region-body
                                            (transition/state-path region-state)))

                     :else
                     (transition/node-at machine
                                          (transition/state-path (:state next-snapshot))))
        output-key  (:output-key final-node)
        result      (when output-key (get child-data output-key))
        parent-id   (:rf/parent-id child-data)
        invoke-id   (:rf/invoke-id child-data)
        ;; (1) Find parent's `:on-done`, if this is an `:invoke`-spawned
        ;; actor. The parent's spec carries the `:invoke` map at
        ;; `invoke-id`.
        parent-meta (when parent-id
                      (let [m (registrar/lookup :event parent-id)]
                        (when (:rf/machine? m) (:rf/machine m))))
        invoke-spec (when (and parent-meta invoke-id)
                      (find-invoke-spec-at parent-meta invoke-id))
        on-done-fn  (:on-done invoke-spec)
        parent-path (snapshot-path parent-id)
        ;; (2) Emit `:rf.machine/done` trace BEFORE the destroy cascade
        ;; (D6 ordering).
        _ (trace/emit! :machine :rf.machine/done
                       {:machine-id machine-id
                        :output     result
                        :parent-id  parent-id
                        :frame      frame-id})
        ;; (3) Apply :on-done to the parent's `:data`. The parent's
        ;; snapshot lives at [:rf/machines <parent-id>]; we read it,
        ;; pass `:data` + `result` to `:on-done`, and write the new
        ;; `:data` back. If the parent has no snapshot (spawned a
        ;; child but was itself destroyed) or no `:on-done`, this
        ;; reduces to identity.
        db-after-on-done
        (if (and on-done-fn parent-id)
          (let [parent-snap     (get-in db parent-path)
                parent-data     (:data parent-snap)
                new-parent-data (try
                                  (on-done-fn parent-data result)
                                  (catch #?(:clj Throwable :cljs :default) e
                                    (trace/emit-error! :rf.error/machine-action-exception
                                                       {:machine-id machine-id
                                                        :action-id  :rf.invoke/on-done
                                                        :parent-id  parent-id
                                                        :invoke-id  invoke-id
                                                        :exception  e
                                                        :reason     ":on-done callback threw."
                                                        :recovery   :no-recovery})
                                    parent-data))]
            (if (and parent-snap (some? new-parent-data))
              (assoc-in db (conj parent-path :data) new-parent-data)
              db))
          db)
        ;; (4) Find the actor's `:system-id` binding (if any) BEFORE
        ;; we mutate the reverse index — so we can release it after
        ;; `:on-done` ran (D8) and emit the `:released` trace.
        released-sid
        (some (fn [[sid mid]]
                (when (= mid machine-id) sid))
              (get db-after-on-done :rf/system-ids))
        ;; (5) Emit :rf.machine/destroyed with :reason :rf.machine/finished
        ;; (D6 enrichment) BEFORE the registrar unregister so any in-flight
        ;; trace consumers see the destroy signal while the handler still
        ;; resolves.
        _ (trace/emit! :machine :rf.machine/destroyed
                       {:frame      frame-id
                        :actor-id   machine-id
                        :system-id  released-sid
                        :parent-id  parent-id
                        :invoke-id  invoke-id
                        :reason     :rf.machine/finished})
        ;; (6) Build the destroy-side db: dissoc the child's snapshot;
        ;; clear the parent's [:rf/spawned <parent-id> <invoke-id>] slot
        ;; (with lazy-allocation prune); clear [:rf/system-ids <sid>]
        ;; (D8 — after on-done).
        db-after-destroy
        (cond-> db-after-on-done
          true            (update :rf/machines dissoc machine-id)
          released-sid    (update :rf/system-ids dissoc released-sid)
          (and parent-id invoke-id)
          (update-in [:rf/spawned parent-id] dissoc invoke-id)
          (and parent-id invoke-id
               (empty? (get-in db-after-on-done [:rf/spawned parent-id])))
          (update :rf/spawned dissoc parent-id))
        db-after-destroy
        (cond-> db-after-destroy
          (and parent-id invoke-id
               (empty? (get db-after-destroy :rf/spawned)))
          (dissoc :rf/spawned))
        ;; Also clean up the :rf/system-ids slot if it just emptied.
        db-after-destroy
        (cond-> db-after-destroy
          (and released-sid
               (empty? (get db-after-destroy :rf/system-ids)))
          (dissoc :rf/system-ids))]
    ;; (7) Synchronous side effects: abort in-flight HTTP, emit
    ;; system-id-released trace (when applicable), unregister handler.
    (abort-actor-in-flight-http! machine-id)
    (when released-sid
      (trace/emit! :machine :rf.machine/system-id-released
                   {:frame      frame-id
                    :system-id  released-sid
                    :machine-id machine-id}))
    (registrar/unregister! :event machine-id)
    {:db db-after-destroy
     :fx extra-fx}))

(defn create-machine-handler
  "Returns a function suitable for registration with reg-event-fx.

  Per Spec 005 §Registration — the machine IS the event handler. The
  machine spec MUST NOT carry `:id`; the machine's id is the surrounding
  registration's event-id, derived at handler-call time from the
  dispatched event vector's first element.

  Per rf2-f9tu the body is decomposed into:
    - `validate-machine!` — every registration-time check (parallel
      shape, invoke-all shape, dropped :timeout-ms slots, guard/action
      ref resolution).
    - `synthesise-initial-snapshot` — initial-state cascade, :data /
      :meta seeding, tag union stamping.
    - the returned handler fn — frame stamping, intercept-invoke-all-
      event branch, bootstrap-pending detection + initial-entry
      cascade, machine-transition dispatch, action-failure projection."
  [machine]
  (validate-machine! machine)
  ;; Per rf2-f9tu — `synthesise-initial-snapshot` runs lazily INSIDE the
  ;; returned handler, not at registration time. The initial-state
  ;; computation reaches through `:initial` / `:states` / `:regions`;
  ;; running it at registration time would force every registered spec
  ;; (including the stub child machines the conformance corpus declares
  ;; without `:initial` for `:invoke` targets, and any spec whose
  ;; `:initial` derives from a fn-form computed at dispatch time) to
  ;; satisfy the snapshot shape at reg-machine call time. The original
  ;; (pre-split) implementation deferred this work; preserve that.
  (let [base-initial (delay (synthesise-initial-snapshot machine))]
    (fn [{:keys [db frame] :as _cofx} event]
      (let [machine-id (first event)
            ;; Per Spec 009 §:op-type vocabulary: :rf.machine/event-received
            ;; fires at the top of the handler so consumers see the inbound
            ;; event before any state derivation.
            _ (trace/emit! :rf.machine/event-received :rf.machine/event-received
                           {:machine-id machine-id
                            :event      event
                            :frame      (or frame :rf/default)})
            ;; Stamp the live frame + platform + parent-id onto the
            ;; machine def so spawn-id allocation (frame-scoped per Spec
            ;; 002) gets the right key, `:after` scheduling can gate on
            ;; `:server`, and `apply-transition-once` can emit spawn /
            ;; destroy fx whose args carry `:rf/parent-id` (used by the
            ;; fx handlers to address the runtime-owned [:rf/spawned
            ;; <parent-id> <invoke-id>] registry slot).
            frame-id   (or frame :rf/default)
            platform   (or (get-in (frame/frame-meta frame-id) [:config :platform])
                           :client)
            machine    (assoc machine
                              :rf/frame     frame-id
                              :rf/platform  platform
                              :rf/parent-id machine-id)
            path       (snapshot-path machine-id)
            ;; Per rf2-0z73: detect "first event for this machine" so the
            ;; initial-state's `:entry` actions fire as part of bringing
            ;; the machine to life. Two flavours:
            ;;
            ;;   - Singleton path: `(get-in db path)` is `nil` — the
            ;;     snapshot is being lazily synthesised right now.
            ;;
            ;;   - Spawn path: `spawn-fx` pre-seeded the snapshot at
            ;;     `[:rf/machines <spawned-id>]` and stamped
            ;;     `:rf/bootstrap-pending? true` so the actor's first
            ;;     dispatch sees the marker and runs the cascade before
            ;;     processing the event.
            existing-snap (get-in db path)
            needs-bootstrap? (or (nil? existing-snap)
                                 (true? (:rf/bootstrap-pending? existing-snap)))
            snapshot   (cond
                         (nil? existing-snap)
                         (assoc @base-initial :rf/bootstrap-pending? true)

                         :else
                         existing-snap)
            ;; Sub-event routing per Spec 005 §Registration. The outer
            ;; event is [:machine-id <inner-event> & extra-args]. Extra
            ;; args are conj'd onto the inner event — the convention
            ;; http-style fx callbacks rely on:
            ;;   :on-success [:machine-id [:inner-id]]   →
            ;;   (conj on-success response) yields
            ;;   [:machine-id [:inner-id] response]      →
            ;;   inner-event = [:inner-id response]
            inner-event (cond
                          (and (vector? event)
                               (>= (count event) 2)
                               (vector? (second event)))
                          (let [inner (second event)
                                extra (drop 2 event)]
                            (if (seq extra)
                              (vec (concat inner extra))
                              inner))

                          :else event)
            intercepted (intercept-invoke-all-event machine db path snapshot machine-id inner-event)
            ;; Per rf2-0z73: when the snapshot is bootstrap-pending, run
            ;; the initial-state entry cascade once before processing the
            ;; user event. Bootstrap fx flow OUT of the handler ahead of
            ;; any fx the user event produces — entry happens-before user
            ;; event handling.
            [boot-snap boot-fx :as boot-result]
            (cond
              (and needs-bootstrap? (not intercepted))
              (parallel/apply-initial-entry-cascade machine snapshot)

              :else
              [snapshot []])
            boot-failed? (and (vector? boot-result)
                              (= ::transition/action-failed (first boot-result)))
            post-boot-snap (when-not boot-failed?
                             (dissoc boot-snap :rf/bootstrap-pending?))]
        (cond
          intercepted
          intercepted

          boot-failed?
          (let [info       (second boot-result)
                ex         (:exception info)
                ex-msg     #?(:clj  (when ex (.getMessage ^Throwable ex))
                              :cljs (when ex (.-message ex)))
                ex-data    (when ex (ex-data ex))
                action-ref (:action-ref info)]
            (trace/emit-error! :rf.error/machine-action-exception
                               {:machine-id        machine-id
                                :action-id         action-ref
                                :state-path        (:state-path info)
                                :transition        (:transition info)
                                :event             [:rf.machine/bootstrap]
                                :failing-id        machine-id
                                :handler-id        machine-id
                                :frame             (or frame :rf/default)
                                :exception         ex
                                :exception-message ex-msg
                                :exception-data    ex-data
                                :reason            "Machine initial-entry action threw."
                                :recovery          :no-recovery})
            {})

          :else
          (let [step-result (parallel/machine-transition machine post-boot-snap inner-event)]
            (if (and (vector? step-result)
                     (= ::transition/action-failed (first step-result)))
              (let [info       (second step-result)
                    ex         (:exception info)
                    ex-msg     #?(:clj  (when ex (.getMessage ^Throwable ex))
                                  :cljs (when ex (.-message ex)))
                    ex-data    (when ex (ex-data ex))
                    action-ref (:action-ref info)]
                (trace/emit-error! :rf.error/machine-action-exception
                                   {:machine-id        machine-id
                                    :action-id         action-ref
                                    :state-path        (:state-path info)
                                    :transition        (:transition info)
                                    :event             inner-event
                                    :failing-id        machine-id
                                    :handler-id        machine-id
                                    :frame             (or frame :rf/default)
                                    :exception         ex
                                    :exception-message ex-msg
                                    :exception-data    ex-data
                                    :reason            "Machine action threw."
                                    :recovery          :no-recovery})
                {})
              (let [[next-snapshot fx] step-result
                    merged-fx (vec (concat boot-fx fx))
                    ;; Per Spec 005 §Final states (rf2-gn80): recompute
                    ;; the finality flag at the lifecycle-handler
                    ;; boundary against the post-transition snapshot.
                    ;; For single / compound machines, look up the leaf
                    ;; node and check `:final?`. For parallel-region
                    ;; machines, the parent is `:final?` only when every
                    ;; region's leaf is `:final?`. The pure-transition
                    ;; surface stays free of runtime-only metadata.
                    finished? (or (and (not (parallel/parallel? machine))
                                       (transition/final-on-leaf? machine (:state next-snapshot)))
                                  (all-regions-final? machine (:state next-snapshot)))
                    new-db (assoc-in db path next-snapshot)]
                (trace/emit! :machine :rf.machine/transition
                             {:machine-id  machine-id
                              :event       inner-event
                              :before      snapshot
                              :after       next-snapshot})
                (when (not= snapshot next-snapshot)
                  (trace/emit! :rf.machine/snapshot-updated :rf.machine/snapshot-updated
                               {:machine-id machine-id
                                :path       path
                                :before     snapshot
                                :after      next-snapshot
                                :frame      (or frame :rf/default)}))
                (if finished?
                  (finalize-machine machine machine-id (or frame :rf/default)
                                    new-db next-snapshot inner-event merged-fx)
                  {:db new-db
                   :fx merged-fx})))))))))

;; ---- reg-machine* — plain-fn surface (rf2-8bp3) ---------------------------

(defn reg-machine*
  "Plain-fn surface beneath the `reg-machine` macro. Registers a machine
  as an event handler under `machine-id`. Equivalent to
  `(reg-event-fx machine-id (create-machine-handler machine))`.

  Per Spec 005 §reg-machine vs reg-machine*: the macro `reg-machine`
  walks the literal spec form at expansion time and stamps per-element
  source coordinates onto the spec's `:rf.machine/source-coords` key.
  This fn assumes no such walking — it accepts whatever spec map the
  caller has already constructed. Use this fn for runtime registration
  with computed ids, fixture-synthesised specs, or REPL workflows.

  Per Spec 005 §Querying machines, the registration metadata is stamped
  with :rf/machine? true and :rf/machine (the spec map). (rf/machines)
  filters the :event registry by :rf/machine?; (rf/machine-meta id)
  reads the spec back out via the standard registrar query API.

  Per Spec 001 §Source-coordinate capture, the call-site `:ns` /
  `:line` / `:file` carried by `re-frame.source-coords/*pending-coords*`
  (set by the `reg-machine` macro) is merged into the registration
  metadata via the `reg-event-fx` defn's `merge-coords` call."
  [machine-id machine]
  (let [handler-fn (create-machine-handler machine)]
    (events/reg-event-fx machine-id
                         {:rf/machine? true
                          :rf/machine  machine}
                         handler-fn)
    (trace/emit! :rf.machine.lifecycle/created :rf.machine.lifecycle/created
                 {:machine-id machine-id
                  :initial    (:initial machine)})
    machine-id))

;; ---- query API (Spec 005 §Querying machines) -----------------------------
;;
;; Two thin lookup fns over the existing event registry — derived views,
;; not a new registry kind. (rf/machines) filters event handlers whose
;; registration metadata carries :rf/machine? true; (rf/machine-meta id)
;; returns the registered machine's spec map.

(defn machines
  "Return a sequence of machine-ids — every event handler whose
  registration metadata carries :rf/machine? true. Per Spec 005
  §Querying machines."
  []
  (->> (registrar/handlers :event)
       (keep (fn [[id m]] (when (:rf/machine? m) id)))
       (vec)))

(defn machine-meta
  "Return the registered machine's spec map (:initial, :data, :guards,
  :actions, :states, :doc, source coords) for machine-id, or nil if
  no machine is registered under that id. Per Spec 005 §Querying
  machines."
  [machine-id]
  (let [m (registrar/lookup :event machine-id)]
    (when (:rf/machine? m)
      (:rf/machine m))))

;; ---- spawn / destroy live-handler wiring ---------------------------------
;;
;; `apply-transition-once` emits `[:rf.machine/spawn args]` and
;; `[:rf.machine/destroy actor-id]` into the fx vector whenever
;; entry/exit cascades cross an :invoke-bearing state. Per Spec 005
;; §Spawning, the spawned actor is itself an event handler whose id is
;; the actor address; spawn-fx registers the live handler under the
;; spawned id and seeds its initial snapshot at [:rf/machines <id>].
;;
;; The two-tier registry described in Spec 005 (frame-local handlers
;; that revert with the frame's snapshot) is not yet built — for v1 the
;; registration goes through the global registrar via `events/reg-event-
;; fx`. Frame isolation is preserved by the snapshot living at
;; `[:rf/machines <id>]` inside the spawning frame's app-db.

(defn- compute-actor-id
  "Resolve the actor id for a spawn. Per Spec 005 §Declarative :invoke
  Spec-spec keys: `:invoke-id` is an explicit id (per-state singleton);
  otherwise the runtime allocated id is sourced from the spawn args
  the transition runner produced via next-spawn-id."
  [args frame-id]
  (or (:invoke-id args)
      (:rf/spawned-id args)
      ;; Fallback: re-allocate (shouldn't happen for declarative :invoke,
      ;; but supports hand-emitted [:rf.machine/spawn args] forms from
      ;; action :fx).
      (transition/next-spawn-id frame-id (or (:id-prefix args) (:machine-id args)))))

(defn- resolve-spawn-machine
  "Resolve the machine spec to register for a spawn. `:machine-id`
  references a registered machine — read its spec back from the
  registrar via the `:rf/machine` metadata. `:definition` carries an
  inline spec map. Returns the spec map or nil if neither resolves.
  Per [005 §Spawn-spec keys]."
  [args]
  (let [machine-id (:machine-id args)
        defn       (:definition args)]
    (cond
      defn        defn
      machine-id  (let [m (registrar/lookup :event machine-id)]
                    (when (:rf/machine? m)
                      (:rf/machine m))))))

(defn spawn-fx
  "fx handler for `:rf.machine/spawn`. Per Spec 005 §Spawning, the spawned
  actor is itself an event handler at `<spawned-id>`; its snapshot lives
  at `[:rf/machines <spawned-id>]` in the spawning frame's app-db.

  Lifecycle wired here:
   1. Resolve the spawn's machine spec (`:machine-id` from the registrar
      OR an inline `:definition`).
   2. Register the live event handler under the spawned id via
      `create-machine-handler` / `reg-event-fx`. Re-spawn under the
      same id replaces — last-write-wins, matching standard
      re-registration.
   3. Initialise the actor's snapshot at [:rf/machines <spawned-id>]
      using the spec's :initial / :data (overridden by the spawn args'
      :data). Per rf2-ijm7 the runtime stamps `:rf/self-id` (the
      spawned actor's own address) and, when applicable,
      `:rf/parent-id` + `:rf/invoke-id` into the actor's initial
      `:data` under the framework-reserved `:rf/*` namespace.
   4. If `:system-id` present, bind it in the per-frame
      [:rf/system-ids] reverse index. Collisions emit
      `:rf.error/system-id-collision` and rebind (last-write-wins).
   5. If `:rf/parent-id` + `:rf/invoke-id` present (declarative `:invoke`
      desugar — rf2-t07u Option A revised), bind the spawned id at
      `[:rf/spawned <parent-id> <invoke-id>]`.
   6. If `:start` event-vector present, dispatch
      `[<spawned-id> <start>]`. When `:start` is absent (per rf2-ijm7),
      the runtime dispatches a synthetic `[<spawned-id>
      [:rf.machine/spawned]]` so generic child machines may declare a
      leaf-level `:on :rf.machine/spawned :target ...` transition."
  [{:keys [frame]} args]
  (let [frame-id   (or frame :rf/default)
        spawned-id (compute-actor-id args frame-id)
        spec       (resolve-spawn-machine args)
        spec'      (if (and spec (contains? args :data))
                     (assoc spec :data (:data args))
                     spec)
        system-id  (:system-id args)
        ;; Per rf2-t07u (Option A revised): the runtime tracks each
        ;; declarative-:invoke spawn at [:rf/spawned <parent-id>
        ;; <invoke-id>] — populated only when the spawn carries both.
        parent-id  (:rf/parent-id args)
        invoke-id  (:rf/invoke-id args)
        track?     (and parent-id invoke-id)
        ;; Per rf2-ijm7: stamp framework-reserved keys into the spawned
        ;; actor's initial :data so the actor knows its own address
        ;; (:rf/self-id) and, for declarative-:invoke spawns, its
        ;; parent's address + invoke-id.
        spec''     (if spec'
                     (let [base-data (or (:data spec') {})
                           data'     (cond-> (assoc base-data :rf/self-id spawned-id)
                                       parent-id (assoc :rf/parent-id parent-id)
                                       invoke-id (assoc :rf/invoke-id invoke-id))]
                       (assoc spec' :data data'))
                     spec')]
    (trace/emit! :machine :rf.machine/spawned
                 {:frame      frame-id
                  :machine-id (:machine-id args)
                  :spawned-id spawned-id
                  :id-prefix  (:id-prefix args)
                  :start      (:start args)
                  :on-spawn   (:on-spawn args)
                  :system-id  system-id
                  :parent-id  parent-id
                  :invoke-id  invoke-id})
    (when spec''
      (reg-machine* spawned-id spec''))
    ;; (3) Initialise the snapshot + (4) bind :system-id + (5) bind the
    ;; runtime-owned spawn registry (atomically under one app-db swap so
    ;; observers see consistent state).
    (when-let [container (frame/get-frame-db frame-id)]
      (let [parallel-child? (and spec'' (parallel/parallel? spec''))
            initial-decl  (:initial spec'')
            initial-state (when spec''
                            (cond
                              parallel-child?
                              (into {} (for [[rn region-body] (:regions spec'')]
                                         [rn (parallel/region-initial-state region-body)]))

                              :else
                              (transition/denormalise-state
                                (transition/initial-cascade spec'' (transition/state-path initial-decl))
                                initial-decl)))
            initial-snap  (when spec''
                            ;; Per rf2-0z73: stamp `:rf/bootstrap-pending?
                            ;; true` so the spawned actor's first event
                            ;; (the synthetic `[:rf.machine/spawned]` OR
                            ;; the user-supplied `:start`) triggers the
                            ;; initial-state entry cascade before
                            ;; processing the event.
                            (-> (parallel/commit-tags-parallel
                                  spec''
                                  {:state initial-state
                                   :data  (or (:data spec'') {})})
                                (assoc :rf/bootstrap-pending? true)))
            old-db        (adapter/read-container container)
            existing      (when system-id
                            (get-in old-db [:rf/system-ids system-id]))
            new-db
            (cond-> old-db
              spec''     (assoc-in [:rf/machines spawned-id] initial-snap)
              system-id  (assoc-in [:rf/system-ids system-id] spawned-id)
              track?     (assoc-in [:rf/spawned parent-id invoke-id] spawned-id))]
        (when (and system-id existing (not= existing spawned-id))
          (trace/emit-error! :rf.error/system-id-collision
                             {:frame             frame-id
                              :system-id         system-id
                              :existing-machine  existing
                              :rebound-to        spawned-id
                              :reason            (str ":system-id " system-id
                                                      " was already bound to "
                                                      existing
                                                      "; rebinding to " spawned-id
                                                      " (last-write-wins).")
                              :recovery          :warned-and-replaced}))
        (adapter/replace-container! container new-db)
        (when system-id
          (trace/emit! :machine :rf.machine/system-id-bound
                       {:frame      frame-id
                        :system-id  system-id
                        :machine-id spawned-id}))))
    ;; (6) Fire the :start event into the new actor. Per rf2-ijm7,
    ;; spawns that don't supply :start receive a synthetic
    ;; [:rf.machine/spawned] so generic child machines can declare their
    ;; first transition out of an :initial state at spec-write time.
    (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
      (let [start (:start args)]
        (if (some? start)
          (dispatch! [spawned-id start] {:frame frame-id})
          (dispatch! [spawned-id [:rf.machine/spawned]] {:frame frame-id}))))
    spawned-id))

(defn invoke-all-init-fx
  "fx handler for `:rf.machine/invoke-all-init` (rf2-6vmw). Per Spec 005
  §Spawn-and-join via :invoke-all, on entry to an :invoke-all-bearing
  state the runtime emits this fx (alongside per-child :rf.machine/spawn
  fxs) to seed the join state at [:rf/spawned <parent> <invoke-id>] in
  the frame's app-db. The seed map shape is:

    {:children {<child-id> <spawned-id>, ...}
     :done      #{}
     :failed    #{}
     :resolved? false
     :spec      <invoke-all-spec>}

  Subsequent :on-child-done / :on-child-error events arrive at the
  parent's create-machine-handler boundary and are intercepted by
  intercept-invoke-all-event."
  [{:keys [frame]} args]
  (let [frame-id   (or frame :rf/default)
        parent-id  (:rf/parent-id args)
        invoke-id  (:rf/invoke-id args)
        join-state (:join-state args)
        children   (:children join-state)]
    (when-let [container (frame/get-frame-db frame-id)]
      (let [old-db (adapter/read-container container)
            new-db (assoc-in old-db [:rf/spawned parent-id invoke-id] join-state)]
        (adapter/replace-container! container new-db)))
    (trace/emit! :machine :rf.machine.invoke-all/started
                 {:machine-id parent-id
                  :invoke-id  invoke-id
                  :child-ids  (set (keys children))
                  :children   children
                  :frame      frame-id})
    nil))

;; ---- in-flight HTTP abort cascade (rf2-wvkn) ------------------------------
;;
;; Per Spec 005 §Cancellation cascade — in-flight `:rf.http/managed`
;; aborts: when a spawned state-machine actor is destroyed, every
;; in-flight `:rf.http/managed` request the actor had issued is
;; aborted. The abort is fired through the late-bind hook
;; `:http/abort-on-actor-destroy` — re-frame.machines does NOT
;; statically `:require` re-frame.http-managed; the destroy path
;; looks up this fn at call time.

(defn- abort-actor-in-flight-http!
  "Fire the late-bind hook that aborts every in-flight `:rf.http/managed`
  request for the destroyed actor. Idempotent and safe to call when
  the http artefact is absent (returns nil)."
  [actor-id]
  (when actor-id
    (when-let [abort! (late-bind/get-fn :http/abort-on-actor-destroy)]
      (try (abort! actor-id)
           (catch #?(:clj Throwable :cljs :default) _ nil))))
  nil)

(defn- destroy-single-actor!
  "Destroy a single spawned actor: dissoc the snapshot at
  [:rf/machines <id>], clear any :system-id binding pointing at it,
  unregister the live event handler. Used by destroy-machine-fx for
  the keyword-form legacy/imperative destroy AND iterated for each
  child in an :invoke-all teardown.

  Per rf2-wvkn (Spec 005 §Cancellation cascade), also fires the
  `:http/abort-on-actor-destroy` late-bind hook so any in-flight
  `:rf.http/managed` requests the actor had issued are aborted."
  [frame-id actor-id]
  (when actor-id
    (abort-actor-in-flight-http! actor-id)
    (let [released-sid
          (when (frame/get-frame-db frame-id)
            (let [db (adapter/read-container (frame/get-frame-db frame-id))]
              (some (fn [[sid mid]]
                      (when (= mid actor-id) sid))
                    (get db :rf/system-ids))))]
      (when-let [container (frame/get-frame-db frame-id)]
        (let [old-db (adapter/read-container container)
              new-db (cond-> old-db
                       true         (update :rf/machines dissoc actor-id)
                       released-sid (update :rf/system-ids dissoc released-sid))]
          (adapter/replace-container! container new-db)))
      (when released-sid
        (trace/emit! :machine :rf.machine/system-id-released
                     {:frame      frame-id
                      :system-id  released-sid
                      :machine-id actor-id}))
      (registrar/unregister! :event actor-id)
      released-sid)))

(declare destroy-machine-fx-single)

(defn destroy-machine-fx
  "fx handler for `:rf.machine/destroy`. Per Spec 005 §Spawning, destroy
  unregisters the spawned actor's event handler, clears its snapshot at
  `[:rf/machines <id>]` in the spawning frame's app-db, and (if the
  actor was system-id-bound) clears the `[:rf/system-ids]` reverse
  index entry. Emits `:rf.machine/destroyed` and (when applicable)
  `:rf.machine/system-id-released`.

  Per rf2-t07u (Option A revised), `args` can be either:
    - a keyword `actor-id` — the legacy / imperative form (action emits
      `[:rf.machine/destroy actor-id]` directly with the recorded id), OR
    - a map `{:rf/parent-id ... :rf/invoke-id ...}` — the declarative-
      `:invoke` exit-cascade form, where the runtime resolves the actor
      id from `[:rf/spawned <parent-id> <invoke-id>]` in the frame's
      app-db.

  Per rf2-6vmw, the map form may also carry `:rf/invoke-all true` —
  the declarative-:invoke-all exit-cascade form. The slot at
  [:rf/spawned <parent-id> <invoke-id>] holds a join-state map whose
  :children sub-map has every spawned child id. The handler iterates
  :children and tears each one down, then clears the slot."
  [{:keys [frame]} args]
  (let [frame-id  (or frame :rf/default)
        tracked?  (map? args)
        invoke-all? (and tracked? (true? (:rf/invoke-all args)))
        parent-id (when tracked? (:rf/parent-id args))
        invoke-id (when tracked? (:rf/invoke-id args))]
    (cond
      invoke-all?
      (let [join-state (when-let [container (frame/get-frame-db frame-id)]
                         (get-in (adapter/read-container container)
                                 [:rf/spawned parent-id invoke-id]))
            children   (when (map? join-state) (:children join-state))]
        (doseq [[child-id spawned-id] children]
          (trace/emit! :machine :rf.machine/destroyed
                       {:frame      frame-id
                        :actor-id   spawned-id
                        :parent-id  parent-id
                        :invoke-id  invoke-id
                        :child-id   child-id
                        :reason     :explicit})        ;; rf2-gn80 D6 — discriminator
          (destroy-single-actor! frame-id spawned-id))
        ;; Clear the slot + lazy-allocation prune.
        (when-let [container (frame/get-frame-db frame-id)]
          (let [old-db (adapter/read-container container)
                new-db (cond-> old-db
                         true (update-in [:rf/spawned parent-id]
                                         dissoc invoke-id)
                         (empty? (get-in old-db [:rf/spawned parent-id]))
                         (update :rf/spawned dissoc parent-id))
                new-db (cond-> new-db
                         (empty? (get new-db :rf/spawned))
                         (dissoc :rf/spawned))]
            (adapter/replace-container! container new-db)))
        nil)

      :else
      (destroy-machine-fx-single {:frame frame} args))))

(defn- destroy-machine-fx-single
  "Original :rf.machine/destroy implementation — handles the keyword
  (legacy/imperative) form and the single-:invoke (tracked map) form."
  [{:keys [frame]} args]
  (let [frame-id  (or frame :rf/default)
        tracked?  (map? args)
        parent-id (when tracked? (:rf/parent-id args))
        invoke-id (when tracked? (:rf/invoke-id args))
        actor-id
        (cond
          (not tracked?) args
          :else (when-let [container (frame/get-frame-db frame-id)]
                  (get-in (adapter/read-container container)
                          [:rf/spawned parent-id invoke-id])))
        released-sid
        (when (and actor-id (frame/get-frame-db frame-id))
          (let [db (adapter/read-container (frame/get-frame-db frame-id))]
            (some (fn [[sid mid]]
                    (when (= mid actor-id) sid))
                  (get db :rf/system-ids))))]
    (trace/emit! :machine :rf.machine/destroyed
                 {:frame      frame-id
                  :actor-id   actor-id
                  :system-id  released-sid
                  :parent-id  parent-id
                  :invoke-id  invoke-id
                  :reason     :explicit})              ;; rf2-gn80 D6 — discriminator
    ;; Tracked-form destroy with no resolved actor-id is a benign no-op:
    ;; the spawn slot was already cleared (e.g. by an earlier explicit
    ;; destroy) or the spawn was suppressed (SSR / platform gating).
    (when actor-id
      (abort-actor-in-flight-http! actor-id)
      (when-let [container (frame/get-frame-db frame-id)]
        (let [old-db (adapter/read-container container)
              new-db (cond-> old-db
                       true          (update :rf/machines dissoc actor-id)
                       released-sid  (update :rf/system-ids dissoc released-sid)
                       tracked?      (update-in [:rf/spawned parent-id]
                                                dissoc invoke-id))
              ;; Tidy up the per-parent map if it just emptied — same
              ;; lazy-allocation invariant as :rf/system-ids.
              new-db (cond-> new-db
                       (and tracked?
                            (empty? (get-in new-db [:rf/spawned parent-id])))
                       (update :rf/spawned dissoc parent-id))
              new-db (cond-> new-db
                       (and tracked? (empty? (get new-db :rf/spawned)))
                       (dissoc :rf/spawned))]
          (adapter/replace-container! container new-db)))
      (when released-sid
        (trace/emit! :machine :rf.machine/system-id-released
                     {:frame      frame-id
                      :system-id  released-sid
                      :machine-id actor-id}))
      ;; Unregister the live handler. Last so any in-flight trace emit
      ;; against the actor still resolves before the slot disappears.
      (registrar/unregister! :event actor-id))
    nil))

;; ---- query API for system-id (Spec 005 §Named addressing via :system-id) -

(defn machine-by-system-id
  "Look up the spawned-machine id currently bound to `system-id` in the
  active frame's `[:rf/system-ids]` reverse index, or nil. The `frame`
  arg defaults to the current frame (per `frame/current-frame`); pass
  an explicit frame-id for cross-frame lookups.

  Per Spec 005 §Named addressing via :system-id."
  ([system-id]
   (machine-by-system-id system-id (frame/current-frame)))
  ([system-id frame-id]
   (when-let [container (frame/get-frame-db frame-id)]
     (get-in (adapter/read-container container) [:rf/system-ids system-id]))))

;; Framework-shipped subscriptions (`:rf/machine`, `:rf/machine-has-tag?`)
;; are registered in the public-façade `re-frame.machines` namespace so
;; `(require 're-frame.machines :reload)` in a test fixture's reset path
;; re-installs them after `registrar/clear-all!`. Keeping the
;; `subs/reg-sub` calls at the façade level avoids the implicit
;; reload-locality trap — `:reload` is shallow, so a sub registered in
;; a sub-namespace wouldn't be re-fired by a façade-only reload.
