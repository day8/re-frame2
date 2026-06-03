(ns re-frame.machines.lifecycle-fx.validation
  "Registration-time validators for the machine grammar.

  Pure leaf functions called from
  `re-frame.machines.lifecycle-fx.registration/make-machine-handler` at
  the top of its body. Each validator throws an `ex-info` keyed on a
  `:rf.error/machine-*` taxonomy member; consumers (the `reg-machine`
  macro, the registrar, Xray) inspect the `ex-data`. The validators
  in this namespace are:

    - `validate-history!` — `:type :history` pseudo-state shape
      (rf2-mle6e.3): placement, closed key-set, one-per-compound,
      `:default-target` resolution.
    - `validate-parallel!` — `:type :parallel` shape (rf2-l67o).
    - `validate-spawn-all!` — `:spawn-all` shape (rf2-6vmw).
    - `validate-no-spawn-timeout-ms!` — rejects the dropped
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
;; `:rf.error/id` is read uniformly by every consumer (Xray's error
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

(defn- validate-no-spawn-timeout-ms!
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

(defn- validate-spawn-all!
  "Per Spec 005 §Spawn-and-join via `:spawn-all` (rf2-6vmw): walk the
  state tree at registration time and reject malformed `:spawn-all`
  declarations.

  Three error categories:
    - `:rf.error/machine-spawn-all-bad-shape` — a child spawn-spec is
      missing `:id`; or `:spawn-all` is not a vector; or the join-event
      slots are missing per the required-iff rules; or no `:machine-id`
      / `:definition`.
    - `:rf.error/machine-spawn-all-duplicate-id` — two children share an
      `:id` keyword inside the same `:spawn-all` block.
    - `:rf.error/machine-spawn-all-with-spawn` — a state node declares
      both `:spawn` and `:spawn-all` (mutually exclusive)."
  [state-key state-node]
  (let [spawn-all-spec (:spawn-all state-node)]
    (when spawn-all-spec
      (when (:spawn state-node)
        (throw (validation-error
                 :rf.error/machine-spawn-all-with-spawn
                 "a state node cannot declare both :spawn and :spawn-all (they are mutually exclusive)"
                 {:state state-key})))
      (when-not (map? spawn-all-spec)
        (throw (validation-error
                 :rf.error/machine-spawn-all-bad-shape
                 ":spawn-all slot must be a map"
                 {:state state-key})))
      (let [children (:children spawn-all-spec)]
        (when-not (and (vector? children) (seq children))
          (throw (validation-error
                   :rf.error/machine-spawn-all-bad-shape
                   ":children must be a non-empty vector of child specs"
                   {:state state-key})))
        (doseq [c children]
          (when-not (and (map? c) (keyword? (:id c)))
            (throw (validation-error
                     :rf.error/machine-spawn-all-bad-shape
                     "each child spawn-spec must declare an :id keyword"
                     {:state state-key
                      :child c})))
          (when-not (or (:machine-id c) (:definition c))
            (throw (validation-error
                     :rf.error/machine-spawn-all-bad-shape
                     "each child spawn-spec must declare :machine-id or :definition"
                     {:state state-key
                      :child c}))))
        (let [ids (map :id children)]
          (when (not= (count ids) (count (set ids)))
            (let [dup (->> (frequencies ids) (filter (fn [[_ n]] (> n 1))) (map first))]
              (throw (validation-error
                       :rf.error/machine-spawn-all-duplicate-id
                       "two children share an :id keyword inside the same :spawn-all block"
                       {:state state-key :duplicate-ids dup}))))))
      (when-not (keyword? (:on-child-done spawn-all-spec))
        (throw (validation-error
                 :rf.error/machine-spawn-all-bad-shape
                 ":on-child-done is required (event keyword)"
                 {:state state-key})))
      (when-not (keyword? (:on-child-error spawn-all-spec))
        (throw (validation-error
                 :rf.error/machine-spawn-all-bad-shape
                 ":on-child-error is required (event keyword)"
                 {:state state-key})))
      (let [join (:join spawn-all-spec :all)]
        (cond
          (= :all join)
          (when-not (vector? (:on-all-complete spawn-all-spec))
            (throw (validation-error
                     :rf.error/machine-spawn-all-bad-shape
                     ":on-all-complete event-vector is required when :join is :all (default)"
                     {:state state-key})))
          (or (= :any join)
              (and (map? join) (or (pos-int? (:n join))
                                   (fn? (:fn join)))))
          (when-not (vector? (:on-some-complete spawn-all-spec))
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

;; Per Spec 005 §History states (`:type :history` — shallow / deep /
;; default-target) §Pseudo-state constraints (rf2-mle6e.1, PR #2863): the
;; v1 CLJS reference claims `:fsm/history`, so a `:type :history` node is
;; FIRST-CLASS grammar — no longer rejected. `make-machine-handler`
;; validates the pseudo-state's shape at registration (the same layer that
;; rejects malformed compound states):
;;
;;   - a `:type :history` node MUST be declared inside a compound state's
;;     `:states` (it has an owning compound whose configuration it
;;     records) — a history node at the machine root, or directly under a
;;     `:type :parallel` root's `:regions` map (no enclosing compound
;;     region), is a registration error;
;;   - it declares ONLY `:type` / `:deep?` / `:default-target` — any other
;;     key (`:states`, `:initial`, `:on`, `:always`, `:after`, `:spawn`,
;;     `:spawn-all`, `:entry`, `:exit`, `:tags`, `:final?`, …) is a
;;     registration error;
;;   - a compound may declare AT MOST ONE history pseudo-state;
;;   - `:default-target`, when present, MUST resolve to a real state — a
;;     direct child of the owning compound (keyword form) or an absolute
;;     path the definition declares (vector form).
;;
;; The legacy `:history` state-node KEY form (`{:a {:history {...}}}`) and
;; a root / region `:type :history` are NOT part of the grammar — they are
;; misplaced-history registration errors (`:rf.error/machine-history-
;; misplaced`), the named error every other malformed-placement case uses.
;; Per Spec 009 §Error contract the recovery is `:no-recovery` (registration
;; is rejected). The precise error-id catalogue for history-grammar
;; violations is owned by Spec 009 (mle6e.2); these ids conform to that
;; family's `:rf.error/machine-history-*` naming.

(def ^:private history-pseudo-keys
  "The closed key-set a `:type :history` pseudo-state may carry. Anything
  else is `:rf.error/machine-history-extra-keys`."
  #{:type :deep? :default-target})

(defn- history-node?
  "True iff `node` is a history pseudo-state (`:type :history`)."
  [node]
  (= :history (:type node)))

(defn- node-at-states
  "Walk a `:states` map down absolute `path`, returning the leaf
  state-node (or nil if `path` doesn't resolve). Scope-local resolver —
  `states` is the flat machine's `:states` or a single region body's
  `:states`, so `path` is scope-relative (region names are never part of
  a within-region path)."
  [states path]
  (loop [m states, p (vec path)]
    (cond
      (empty? p) nil
      :else      (let [n (get m (first p))]
                   (cond
                     (nil? n)        nil
                     (= 1 (count p)) n
                     :else           (recur (:states n) (rest p)))))))

(defn- resolves-to-state?
  "True iff `target` resolves to a real state under `owning-path` within
  `states`. A keyword names a DIRECT CHILD of the owning compound; a
  vector is an absolute path from the (region) root."
  [states owning-path target]
  (cond
    (keyword? target) (some? (node-at-states states (conj (vec owning-path) target)))
    (vector? target)  (and (seq target)
                           (some? (node-at-states states target)))
    :else             false))

(defn- history-nodes-with-path
  "Yield `[absolute-path node]` pairs for every `:type :history`
  pseudo-state inside `states`, recursing through `:states`. Paths are
  scope-relative (region names excluded). Only HISTORY nodes are yielded
  (ordinary states are walked through but not emitted). Used by the history
  validator."
  [states]
  (letfn [(walk [path nodes]
            (mapcat
              (fn [[k n]]
                (let [p (conj path k)]
                  (concat (when (history-node? n) [[p n]])
                          (when (:states n)
                            (walk p (:states n))))))
              nodes))]
    (walk [] states)))

(defn- compound-states-pairs
  "Yield `[compound-key states-map]` for every compound inside `states`
  (the scope root included as `root-key`). Used to enforce
  at-most-one-history-per-compound."
  [root-key states]
  (letfn [(walk [pairs nodes]
            (reduce (fn [acc [k n]]
                      (cond-> acc
                        (and (map? (:states n)) (seq (:states n)))
                        (-> (conj [k (:states n)])
                            (walk (:states n)))))
                    pairs
                    nodes))]
    (walk [[root-key states]] states)))

(defn- validate-history-pseudo-state!
  "Validate one `:type :history` pseudo-state at `path` (its scope-relative
  declaration path including its own key) within `states`. The owning
  compound is at `owning-path` (= `path` minus the last segment). Per Spec
  005 §History states §Pseudo-state constraints."
  [states path node]
  (let [hist-key    (peek path)
        owning-path (vec (drop-last path))]
    ;; A history node must have an OWNING COMPOUND — `owning-path` empty
    ;; means it sits at the machine root (or directly on a region body's
    ;; `:states` with no enclosing compound, which the path walker yields
    ;; with a length-1 path).
    (when (empty? owning-path)
      (throw (validation-error
               :rf.error/machine-history-misplaced
               (str "history pseudo-state " hist-key
                    " must be declared inside a compound state's :states — "
                    "it records that compound's last-active configuration. "
                    "A :type :history node at the machine root (or directly "
                    "under a parallel :regions body) has no owning compound.")
               {:state hist-key :feature :history})))
    ;; It declares only :type / :deep? / :default-target.
    (let [extra (remove history-pseudo-keys (keys node))]
      (when (seq extra)
        (throw (validation-error
                 :rf.error/machine-history-extra-keys
                 (str "history pseudo-state " hist-key
                      " may declare only :type / :deep? / :default-target — "
                      "it is never occupied, so transition / lifecycle / "
                      "projection keys are meaningless on it. Offending: "
                      (pr-str (vec extra)) ".")
                 {:state hist-key :offending-keys (vec extra) :feature :history}))))
    ;; :default-target, when present, must resolve to a real state.
    (when (contains? node :default-target)
      (let [dt (:default-target node)]
        (when-not (resolves-to-state? states owning-path dt)
          (throw (validation-error
                   :rf.error/machine-history-bad-default-target
                   (str "history pseudo-state " hist-key
                        "'s :default-target " (pr-str dt)
                        " does not resolve to a real state — it must be a "
                        "direct child (keyword) of the owning compound or an "
                        "absolute path (vector) the definition declares.")
                   {:state hist-key :default-target dt :feature :history})))))))

(defn- at-most-one-history-per-compound!
  "Per Spec 005 §History states §Pseudo-state constraints: a compound may
  declare AT MOST ONE history pseudo-state. Two `:type :history` children
  under one compound is `:rf.error/machine-history-duplicate` (deep-vs-
  shallow is a property of the single node's `:deep?`, not a reason for
  two nodes). `compound-pairs` yields `[compound-key states-map]` for
  every compound (scope root + nested)."
  [compound-pairs]
  (doseq [[ckey states] compound-pairs]
    (let [hist-keys (->> states
                         (keep (fn [[k n]] (when (history-node? n) k)))
                         vec)]
      (when (> (count hist-keys) 1)
        (throw (validation-error
                 :rf.error/machine-history-duplicate
                 (str "compound state " ckey
                      " declares more than one history pseudo-state ("
                      (pr-str hist-keys) ") — at most one is permitted; "
                      "deep-vs-shallow is the single node's :deep?.")
                 {:state ckey :history-keys hist-keys :feature :history}))))))

(defn- validate-history-scope!
  "Validate every history pseudo-state within one scope — a flat / compound
  machine's `:states` (root-key `:rf/root`) or a single parallel-region
  body's `:states` (root-key the region name). Paths and `:default-target`
  resolution are scope-relative, so region names never enter a within-region
  path (matching how `:always` / `:spawn` scoping resolves per-region). A
  history node DIRECTLY under the scope root (length-1 path → empty
  owning-path) is misplaced — it has no owning compound."
  [root-key states]
  (doseq [[path node] (history-nodes-with-path states)]
    (validate-history-pseudo-state! states path node))
  (at-most-one-history-per-compound! (compound-states-pairs root-key states)))

(defn- validate-history!
  "Validate the first-class history grammar at registration. Per Spec 005
  §History states §Pseudo-state constraints + Spec-Schemas
  §`:rf/transition-table`. Validates every `:type :history` pseudo-state
  (flat / compound / parallel-region) — placement (must have an owning
  compound), the closed key-set, at-most-one-per-compound, and
  `:default-target` resolution.

  Replaces the withdrawn `:rf.error/machine-grammar-not-in-v1` deferral
  (rf2-mle6e.1, PR #2863): history is now claimed (`:fsm/history`). A
  `:type :history` node at the machine root, or directly on a region body
  with no enclosing compound, is `:rf.error/machine-history-misplaced`."
  [machine]
  ;; A root-level `:type :history` (a `:history` machine) has no `:states`
  ;; to walk and no owning compound — flag it directly.
  (when (history-node? machine)
    (throw (validation-error
             :rf.error/machine-history-misplaced
             (str "a machine root cannot be a :type :history pseudo-state — "
                  "history is a child node under a compound's :states.")
             {:state :rf/root :feature :history})))
  (if (parallel/parallel? machine)
    ;; A region body declared as `:type :history`, or with history nodes
    ;; under its `:states`, is validated per-region (region names are the
    ;; scope root — a history node directly under the region's `:states`
    ;; has owning-path empty → misplaced).
    (doseq [[region-name region-body] (:regions machine)]
      (when (history-node? region-body)
        (throw (validation-error
                 :rf.error/machine-history-misplaced
                 (str "parallel region " region-name
                      " cannot be a :type :history pseudo-state.")
                 {:region region-name :feature :history})))
      (validate-history-scope! region-name (:states region-body)))
    (validate-history-scope! :rf/root (:states machine))))

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

(defn- compound?
  "A state node is compound iff it declares a non-empty `:states` map."
  [state-node]
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
  state at `path` (a self-loop). The `:same-state` sentinel is an explicit
  external self-target (it re-enters the declaring state — see Spec 005
  §Self-transitions, rf2-46ban), so it is always a self-loop. A keyword
  target names a sibling at the declaring level — it self-targets when it
  equals the declaring state's own key (the last element of `path`). A
  vector target is an absolute path — it self-targets when it equals `path`.

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
      (nil? target)          false
      (= :same-state target) true
      (keyword? target)      (= target (peek path))
      (vector? target)       (= target path))))

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

  Per Spec 005 §History states §Pseudo-state constraints (rf2-mle6e.3):
  every `:type :history` pseudo-state — placement (must have an owning
  compound), the closed `:type` / `:deep?` / `:default-target` key-set,
  at-most-one-per-compound, and `:default-target` resolution. Throws
  `:rf.error/machine-history-misplaced` / `-extra-keys` / `-duplicate` /
  `-bad-default-target`.

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
  (validate-history! machine)
  (validate-parallel! machine)
  (doseq [[s n] (walk-state-nodes machine)]
    (validate-spawn-all! s n)
    (validate-no-spawn-timeout-ms! s n)
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
                                   {:action a :state s}))))
        ;; A transition slot's value (an `:on` entry, an `:after` entry)
        ;; may be a keyword target, a vector of state-ids (absolute
        ;; target), a vector of guarded transition maps, or a single
        ;; transition map. Normalise to a seq of maps and check each
        ;; one's `:guard` / `:action` ref. Non-map normalised elements
        ;; (a keyword in a `[:cart :paying]` absolute target) yield nil
        ;; from `(:guard t)` / `(:action t)`, so they're harmless.
        check-transition! (fn [t s]
                            (doseq [tt (if (vector? t) t [t])]
                              (check-guard!  (:guard tt)  s)
                              (check-action! (:action tt) s)))]
    (doseq [[s state-node] (walk-state-nodes machine)]
      (doseq [[_ t] (:on state-node)]
        (check-transition! t s))
      ;; Per Spec 005 §Delayed `:after` (005:1334 "exactly as for `:on`"):
      ;; `:after` entries may carry `:guard` / `:action` refs (e.g.
      ;; `{1000 {:target :timeout :guard :no-progress?}}`). A dangling
      ;; `:after` ref previously slipped past registration and only threw
      ;; at runtime when the timer fired — fail fast here instead.
      (doseq [[_ t] (:after state-node)]
        (check-transition! t s))
      ;; `:always` admits a single entry map OR a vector of entry maps;
      ;; normalise via `always-entries` so a single-map `:always`'s
      ;; guard/action refs are validated (iterating the raw map yields
      ;; MapEntries, so `(:guard t)`/`(:action t)` would no-op and a
      ;; dangling ref would slip past fail-fast registration).
      (doseq [t (always-entries state-node)]
        (check-guard!  (:guard t)  s)
        (check-action! (:action t) s))
      (check-action! (:entry state-node) s)
      (check-action! (:exit  state-node) s))
    ;; Per Spec 005 §Transition resolution: the machine root's own `:on`
    ;; fallback (now consulted at runtime per the root-`:on` fix) carries
    ;; `:guard` / `:action` refs that must resolve at registration too —
    ;; previously `walk-state-nodes` only descended `:states`, so a
    ;; dangling root-`:on` / root-`:after` ref escaped validation
    ;; entirely. `walk-state-nodes` yields the nodes INSIDE each region's
    ;; `:states` but not the region body itself, so a parallel machine's
    ;; per-region root `:on` / `:after` (which IS consulted at runtime via
    ;; the region's own `machine-transition-single` root fallback) is
    ;; validated here too. The parent parallel root carries no `:on`
    ;; (it routes via region broadcast).
    (let [roots (if (parallel/parallel? machine)
                  (vals (:regions machine))
                  [machine])]
      (doseq [root roots
              [_ t] (:on root)]
        (check-transition! t :rf/root))
      (doseq [root roots
              [_ t] (:after root)]
        (check-transition! t :rf/root)))))
