(ns re-frame.machines.lifecycle-fx.validation
  "Registration-time validators for the machine grammar (rf2-f9tu).

  Pure leaf functions called from
  `re-frame.machines.lifecycle-fx.registration/make-machine-handler` at
  the top of its body. Each validator throws an `ex-info` keyed on a
  `:rf.error/machine-*` taxonomy member; consumers (the `reg-machine`
  macro, the registrar, Causa) inspect the `ex-data`. The validators
  in this namespace are:

    - `validate-parallel!` — `:type :parallel` shape (rf2-l67o).
    - `validate-invoke-all!` — `:spawn-all` shape (rf2-6vmw).
    - `validate-no-invoke-timeout-ms!` — rejects the dropped
      `:timeout-ms` / `:on-timeout` slots on `:spawn` / `:spawn-all`
      (rf2-3y3y).
    - `validate-final-state!` — `:final?` shape (rf2-gn80).
    - `validate-machine!` — top-level dispatch + guard/action ref
      resolution (rf2-oz9t).

  `walk-state-nodes` yields `[state-key state-node]` pairs for every
  node under `:states`, recursing through `:states` maps; used by the
  top-level dispatch."
  (:require [re-frame.machines.parallel :as parallel]))

#?(:clj (set! *warn-on-reflection* true))

;; Every validation throw shares the canonical thrown-error skeleton
;; (per Spec 009 §The thrown-error shape — the :rf.error/id ex-data
;; contract):
;;
;;   {:rf.error/id <category-kw>     ;; CANONICAL DISCRIMINATOR
;;    :where       'rf/reg-machine    ;; user-facing fn for greping the call site
;;    :recovery    :fix-registration  ;; "the caller fixes their machine map and retries"
;;    :reason      "<diagnostic>"     ;; one human-readable sentence
;;    + per-site slots (:state / :slot / :guard / :action / :region / …)}
;;
;; `:rf.error/id` is read uniformly by every consumer (Causa's error
;; widget, the pair-tool overlay, `:on-error` policies); the message
;; string is the stringified kw so `.getMessage` / `ex-message` pivots
;; to the same category without ex-data. Modelled on
;; `re-frame.flows.registry/flow-error`.

(defn- validation-error
  "Build a machine-validation ex-info with the canonical thrown-error
  shape (per Spec 009). `error-kw` becomes the message AND the
  `:rf.error/id` discriminator slot; `reason` is the human-readable
  diagnostic; `extras` merges per-site slots (e.g. `:state`, `:slot`,
  `:guard`)."
  ([error-kw reason] (validation-error error-kw reason nil))
  ([error-kw reason extras]
   (ex-info (str error-kw)
            (merge {:rf.error/id error-kw
                    :where       'rf/reg-machine
                    :recovery    :fix-registration
                    :reason      reason}
                   extras))))

(defn- validate-no-invoke-timeout-ms!
  "Per rf2-3y3y / Spec 005 §Wall-clock timeouts on :spawn — use parent
  state's `:after`, the pre-release `:timeout-ms` / `:on-timeout` slots
  on `:spawn` and `:spawn-all` are DROPPED."
  [state-key state-node]
  (doseq [[slot-key spec]
          [[:spawn     (:spawn state-node)]
           [:spawn-all (:spawn-all state-node)]]]
    (when (map? spec)
      (let [t  (:timeout-ms spec)
            ot (:on-timeout spec)]
        (when (or (some? t) (some? ot))
          (throw (validation-error
                   :rf.error/spawn-timeout-ms-removed
                   (str ":timeout-ms / :on-timeout on " slot-key
                        " were dropped. Use the parent state's :after slot "
                        "for wall-clock guards. See "
                        "migration/from-re-frame-v1/README.md §M-44 for the "
                        "rewrite recipe.")
                   {:state      state-key
                    :slot       slot-key
                    :timeout-ms t
                    :on-timeout ot
                    :migration  "migration/from-re-frame-v1/README.md §M-44"})))))))

(defn- validate-invoke-all!
  "Per Spec 005 §Spawn-and-join via `:spawn-all` (rf2-6vmw): walk the
  state tree at registration time and reject malformed `:spawn-all`
  declarations.

  Three error categories:
    - `:rf.error/machine-spawn-all-bad-shape` — a child invoke-spec is
      missing `:id`; or `:spawn-all` is not a vector; or the join-event
      slots are missing per the required-iff rules; or no `:machine-id`
      / `:definition`.
    - `:rf.error/machine-spawn-all-duplicate-id` — two children share an
      `:id` keyword inside the same `:spawn-all` block.
    - `:rf.error/machine-spawn-all-with-spawn` — a state node declares
      both `:spawn` and `:spawn-all` (mutually exclusive)."
  [state-key state-node]
  (let [inv-all (:spawn-all state-node)]
    (when inv-all
      (when (:spawn state-node)
        (throw (validation-error
                 :rf.error/machine-spawn-all-with-spawn
                 "a state node cannot declare both :spawn and :spawn-all (they are mutually exclusive)"
                 {:state state-key})))
      (when-not (map? inv-all)
        (throw (validation-error
                 :rf.error/machine-spawn-all-bad-shape
                 "invoke-all slot must be a map"
                 {:state state-key})))
      (let [children (:children inv-all)]
        (when-not (and (vector? children) (seq children))
          (throw (validation-error
                   :rf.error/machine-spawn-all-bad-shape
                   ":children must be a non-empty vector of child specs"
                   {:state state-key})))
        (doseq [c children]
          (when-not (and (map? c) (keyword? (:id c)))
            (throw (validation-error
                     :rf.error/machine-spawn-all-bad-shape
                     "each child invoke-spec must declare an :id keyword"
                     {:state state-key
                      :child c})))
          (when-not (or (:machine-id c) (:definition c))
            (throw (validation-error
                     :rf.error/machine-spawn-all-bad-shape
                     "each child invoke-spec must declare :machine-id or :definition"
                     {:state state-key
                      :child c}))))
        (let [ids (map :id children)]
          (when (not= (count ids) (count (set ids)))
            (let [dup (->> (frequencies ids) (filter (fn [[_ n]] (> n 1))) (map first))]
              (throw (validation-error
                       :rf.error/machine-spawn-all-duplicate-id
                       "two children share an :id keyword inside the same :spawn-all block"
                       {:state state-key :duplicate-ids dup}))))))
      (when-not (keyword? (:on-child-done inv-all))
        (throw (validation-error
                 :rf.error/machine-spawn-all-bad-shape
                 ":on-child-done is required (event keyword)"
                 {:state state-key})))
      (when-not (keyword? (:on-child-error inv-all))
        (throw (validation-error
                 :rf.error/machine-spawn-all-bad-shape
                 ":on-child-error is required (event keyword)"
                 {:state state-key})))
      (let [join (:join inv-all :all)]
        (cond
          (= :all join)
          (when-not (vector? (:on-all-complete inv-all))
            (throw (validation-error
                     :rf.error/machine-spawn-all-bad-shape
                     ":on-all-complete event-vector is required when :join is :all (default)"
                     {:state state-key})))
          (or (= :any join)
              (and (map? join) (or (pos-int? (:n join))
                                   (fn? (:fn join)))))
          (when-not (vector? (:on-some-complete inv-all))
            (throw (validation-error
                     :rf.error/machine-spawn-all-bad-shape
                     ":on-some-complete event-vector is required when :join is :any / {:n N} / {:fn ...}"
                     {:state state-key})))
          :else
          (throw (validation-error
                   :rf.error/machine-spawn-all-bad-shape
                   ":join must be :all, :any, {:n pos-int}, or {:fn fn?}"
                   {:state state-key
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
      (throw (validation-error
               :rf.error/machine-parallel-bad-shape
               ":type :parallel requires a non-empty :regions map")))
    (when (or (contains? machine :initial) (contains? machine :states))
      (throw (validation-error
               :rf.error/machine-parallel-bad-shape
               ":type :parallel is mutually exclusive with :initial / :states at the root")))
    (doseq [[region-name region-body] (:regions machine)]
      (when-not (keyword? region-name)
        (throw (validation-error
                 :rf.error/machine-parallel-bad-shape
                 "region names must be keywords"
                 {:region region-name})))
      (when-not (and (map? region-body) (seq region-body))
        (throw (validation-error
                 :rf.error/machine-parallel-bad-shape
                 "each region body must be a non-empty state-node map"
                 {:region region-name})))
      (when (= :parallel (:type region-body))
        (throw (validation-error
                 :rf.error/machine-parallel-nested-not-supported
                 "nested parallel regions are not supported in v1"
                 {:region region-name})))
      (when-not (keyword? (:initial region-body))
        (throw (validation-error
                 :rf.error/machine-parallel-bad-shape
                 "each region body must declare :initial (the cascade entry-point)"
                 {:region region-name})))
      (letfn [(walk [path nodes]
                (doseq [[k n] nodes]
                  (when (= :parallel (:type n))
                    (throw (validation-error
                             :rf.error/machine-parallel-nested-not-supported
                             "nested parallel regions are not supported in v1"
                             {:region region-name
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
     `:spawn`, or `:spawn-all` — final means final, no further
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
        (throw (validation-error
                 :rf.error/machine-final-state-compound
                 "a :final? state cannot be compound (no :states / :initial)."
                 {:state state-key})))
      (doseq [bad-key [:on :always :after :spawn :spawn-all]]
        (when (contains? state-node bad-key)
          (throw (validation-error
                   :rf.error/machine-final-state-has-transitions
                   (str "a :final? state cannot declare " bad-key
                        " — final means final; no further transitions.")
                   {:state state-key
                    :slot  bad-key})))))

    ;; Non-final state declaring :output-key — error per D3.
    (contains? state-node :output-key)
    (throw (validation-error
             :rf.error/machine-output-key-without-final
             ":output-key is only meaningful on a :final? state."
             {:state      state-key
              :output-key (:output-key state-node)}))))

(defn- compound? [state-node]
  "A state node is compound iff it declares a non-empty `:states` map."
  (and (map? (:states state-node))
       (seq (:states state-node))))

(defn- validate-compound-initial!
  "Per Spec 005 §Initial-state cascading: every compound state-node MUST
  declare `:initial` — the substate to enter when control reaches the
  compound state without a deeper target. A compound node without
  `:initial` would otherwise yield a non-leaf `:state` snapshot (the
  cascade has no entry-point to descend into) instead of failing
  registration, so reject it here.

  Emits `:rf.error/machine-compound-state-missing-initial`."
  [state-key state-node]
  (when (and (compound? state-node)
             (not (contains? state-node :initial)))
    (throw (validation-error
             :rf.error/machine-compound-state-missing-initial
             (str "compound state " state-key
                  " declares :states but no :initial — every compound state "
                  "must name the substate to enter when control reaches it "
                  "without a deeper target.")
             {:state state-key}))))

(defn- always-entries
  "Normalise a state-node's `:always` slot to a vector of entry maps.
  `:always` admits a single entry map or a vector of entry maps; absent
  yields the empty vector."
  [state-node]
  (let [a (:always state-node)]
    (cond
      (nil? a)    []
      (vector? a) a
      :else       [a])))

(defn- always-self-loop?
  "True iff an `:always` entry's `:target` resolves to its own declaring
  state at `path` (a self-loop). A keyword target names a sibling at the
  declaring level — it self-targets when it equals the declaring state's
  own key (the last element of `path`). A vector target is an absolute
  path — it self-targets when it equals `path`.

  An `:always` entry with NO `:target` is an *internal* eventless
  transition (it runs its `:action` without changing state) — the
  canonical action-microstep pattern (per Spec 005 §What `:always` is),
  e.g. `{:guard :has-queued? :action :flush-queue}` where the action
  flips the guard false and the loop settles. Internal `:always` is NOT
  a self-loop; only an explicit self-`:target` is rejected at
  registration (per Spec 005 §Self-loop forbidden at registration — the
  rule keys off the `:target` resolving to the declaring state)."
  [path entry]
  (let [target (:target entry)]
    (cond
      (nil? target)     false
      (keyword? target) (= target (peek path))
      (vector? target)  (= target path))))

(defn- validate-always-self-loop!
  "Per Spec 005 §Self-loop forbidden at registration: a state whose
  `:always` targets itself is rejected at construction time. The loop
  either fires repeatedly to depth-exceeded (guard stays true) or is a
  no-op (guard flips on first hit) — in both cases the author intended
  something else. Catch the topology bug at registration rather than
  late at runtime via the depth-exceeded backstop.

  `path` is the declaring state's absolute path; `state-key` is its leaf
  key (the trace-tag the spec catalogue pins). Emits
  `:rf.error/machine-always-self-loop`."
  [path state-key state-node]
  (doseq [entry (always-entries state-node)]
    (when (always-self-loop? path entry)
      (throw (validation-error
               :rf.error/machine-always-self-loop
               (str "state " state-key
                    " declares an :always transition that targets itself — "
                    "an eventless self-loop either runs to depth-exceeded or "
                    "is a no-op. Use :after for a re-arming timer, or target a "
                    "distinct state.")
               {:state state-key})))))

(defn- walk-state-nodes-with-path
  "Like `walk-state-nodes` but yields `[absolute-path state-node]` pairs —
  the absolute path is the vector of state keys from the (region) root
  down to the node. Used by the self-loop validator, which needs the
  declaring node's path to resolve vector `:target`s.

  Per Spec 005 §Parallel regions: for parallel-region machines, walks
  each region's `:states`. Region-name keywords are NOT part of the
  yielded path (region identifiers are not states — `:always` targets
  resolve within a region, exactly as in `walk-state-nodes`)."
  [machine]
  (letfn [(walk [path nodes]
            (mapcat
              (fn [[k n]]
                (let [p (conj path k)]
                  (cons [p n]
                        (when (:states n)
                          (walk p (:states n))))))
              nodes))]
    (cond
      (parallel/parallel? machine)
      (mapcat (fn [[_region region-body]] (walk [] (:states region-body)))
              (:regions machine))

      :else
      (walk [] (:states machine)))))

(defn validate-machine!
  "Run every registration-time check the machine grammar requires (rf2-f9tu).
  Composed at the top of `make-machine-handler` so the registered handler
  fn's body is exclusively about request processing.

  Per Spec 005 §Parallel regions (rf2-l67o / Stage 2): `:type :parallel`
  shape — `:regions` non-empty, mutually exclusive with `:initial` /
  `:states`, no nested parallel.

  Per Spec 005 §Spawn-and-join via `:spawn-all` (rf2-6vmw): every
  `:spawn-all`-bearing state node — shape, no duplicate `:id`s, required
  join-event keys per `:join` form, mutually exclusive with `:spawn`.

  Per rf2-3y3y: every `:spawn` / `:spawn-all` rejects the dropped
  `:timeout-ms` / `:on-timeout` slot (use parent `:after`).

  Per rf2-oz9t: every `:on` / `:always` / `:entry` / `:exit` slot's guard
  and action keyword refs must resolve against the machine's `:guards` /
  `:actions` maps. Throws `:rf.error/machine-unresolved-guard` /
  `:rf.error/machine-unresolved-action` on dangling refs.

  Per Spec 005 §Initial-state cascading: every compound state-node
  (declares `:states`) MUST declare `:initial`. Throws
  `:rf.error/machine-compound-state-missing-initial`.

  Per Spec 005 §Self-loop forbidden at registration: an `:always` entry
  that targets its own declaring state is rejected. Throws
  `:rf.error/machine-always-self-loop`."
  [machine]
  (validate-parallel! machine)
  (doseq [[s n] (walk-state-nodes machine)]
    (validate-invoke-all! s n)
    (validate-no-invoke-timeout-ms! s n)
    (validate-final-state! s n)
    (validate-compound-initial! s n))
  ;; The self-loop check needs each declaring node's absolute path to
  ;; resolve vector `:target`s, so it drives off the path-aware walker.
  (doseq [[path n] (walk-state-nodes-with-path machine)]
    (validate-always-self-loop! path (peek path) n))
  ;; Validate guard/action references at construction time. machine-id
  ;; isn't known yet (it's the registration-site id), so error tags use
  ;; a placeholder; real misuse traces at handler-call time fill it in.
  (let [guards-map  (:guards machine)
        actions-map (:actions machine)
        check-guard! (fn [g s]
                       (when (and (keyword? g)
                                  (not (contains? guards-map g)))
                         (throw (validation-error
                                  :rf.error/machine-unresolved-guard
                                  (str "guard ref " g " does not resolve against the machine's :guards map")
                                  {:guard g :state s}))))
        check-action! (fn [a s]
                        (when (and (keyword? a)
                                   (not (contains? actions-map a)))
                          (throw (validation-error
                                   :rf.error/machine-unresolved-action
                                   (str "action ref " a " does not resolve against the machine's :actions map")
                                   {:action a :state s}))))]
    (doseq [[s state-node] (walk-state-nodes machine)]
      (doseq [[_ t] (:on state-node)
              t     (if (vector? t) t [t])]
        (check-guard!  (:guard t)  s)
        (check-action! (:action t) s))
      ;; `:always` admits a single entry map OR a vector of entry maps;
      ;; normalise via `always-entries` so a single-map `:always`'s
      ;; guard/action refs are validated (iterating the raw map yields
      ;; MapEntries, so `(:guard t)`/`(:action t)` would no-op and a
      ;; dangling ref would slip past fail-fast registration).
      (doseq [t (always-entries state-node)]
        (check-guard!  (:guard t)  s)
        (check-action! (:action t) s))
      (check-action! (:entry state-node) s)
      (check-action! (:exit  state-node) s))))
