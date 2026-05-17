(ns re-frame.machines.lifecycle-fx.validation
  "Registration-time validators for the machine grammar (rf2-f9tu).

  Pure leaf functions called from
  `re-frame.machines.lifecycle-fx.registration/create-machine-handler` at
  the top of its body. Each validator throws an `ex-info` keyed on a
  `:rf.error/machine-*` taxonomy member; consumers (the `reg-machine`
  macro, the registrar, Causa) inspect the `ex-data`. The validators
  in this namespace are:

    - `validate-parallel!` — `:type :parallel` shape (rf2-l67o).
    - `validate-invoke-all!` — `:invoke-all` shape (rf2-6vmw).
    - `validate-no-invoke-timeout-ms!` — rejects the dropped
      `:timeout-ms` / `:on-timeout` slots on `:invoke` / `:invoke-all`
      (rf2-3y3y).
    - `validate-final-state!` — `:final?` shape (rf2-gn80).
    - `validate-machine!` — top-level dispatch + guard/action ref
      resolution (rf2-oz9t).

  `walk-state-nodes` yields `[state-key state-node]` pairs for every
  node under `:states`, recursing through `:states` maps; used by the
  top-level dispatch."
  (:require [re-frame.machines.parallel :as parallel]))

#?(:clj (set! *warn-on-reflection* true))

(defn- validate-no-invoke-timeout-ms!
  "Per rf2-3y3y / Spec 005 §Wall-clock timeouts on :invoke — use parent
  state's `:after`, the pre-release `:timeout-ms` / `:on-timeout` slots
  on `:invoke` and `:invoke-all` are DROPPED."
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
                                "See migration/from-re-frame-v1/README.md §M-44 "
                                "for the rewrite recipe.")
                           :migration "migration/from-re-frame-v1/README.md §M-44"})))))))

(defn- validate-invoke-all!
  "Per Spec 005 §Spawn-and-join via `:invoke-all` (rf2-6vmw): walk the
  state tree at registration time and reject malformed `:invoke-all`
  declarations.

  Three error categories:
    - `:rf.error/machine-invoke-all-bad-shape` — a child invoke-spec is
      missing `:id`; or `:invoke-all` is not a vector; or the join-event
      slots are missing per the required-iff rules; or no `:machine-id`
      / `:definition`.
    - `:rf.error/machine-invoke-all-duplicate-id` — two children share an
      `:id` keyword inside the same `:invoke-all` block.
    - `:rf.error/machine-invoke-all-with-invoke` — a state node declares
      both `:invoke` and `:invoke-all` (mutually exclusive)."
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
    - `:rf.error/machine-parallel-bad-shape` — `:type :parallel` declared
      without a `:regions` map, OR `:regions` is empty, OR `:regions`
      coexists with `:initial` / `:states`, OR a region body is missing
      its own `:initial`.
    - `:rf.error/machine-parallel-nested-not-supported` — a region's own
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
  "Yield `[state-key state-node]` pairs for every node under `:states`,
  recursing through `:states` maps. Used by the registration-time
  validators.

  Per Spec 005 §Parallel regions (rf2-l67o / Stage 2): for parallel-region
  machines, walks the state nodes under every region's `:states`. Region-
  name keywords are NOT yielded as state keys (they're region identifiers,
  not states)."
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

(defn validate-machine!
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
