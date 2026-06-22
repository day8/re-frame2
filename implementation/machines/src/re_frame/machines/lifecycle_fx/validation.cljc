(ns re-frame.machines.lifecycle-fx.validation
  "Registration-time validators for the machine grammar.

  Pure leaf functions called from
  `re-frame.machines.lifecycle-fx.registration/make-machine-handler` at
  the top of its body. Each validator throws an `ex-info` keyed on a
  `:rf.error/machine-*` taxonomy member; consumers (the `reg-machine`
  macro, the registrar, Xray) inspect the `ex-data`. The validators
  in this namespace are:

    - `validate-history!` — `:type :history` pseudo-state shape:
      placement, closed key-set, one-per-compound,
      `:default-target` resolution.
    - `validate-parallel!` — `:type :parallel` shape.
    - `validate-schemas!` — machine-level `:schemas` map (EP-0029 A3):
      closed sub-key set (`:data` / `:events` / `:output` / `:tags` /
      `:meta`); `:input` and unknown keys fail loud.
    - `validate-spawn!` — single `:spawn` `:machine-id` xor
      `:definition`.
    - `validate-spawn-all!` — `:spawn-all` shape.
    - `validate-no-spawn-timeout-ms!` — rejects the unsupported
      `:timeout-ms` / `:on-timeout` slots on `:spawn` / `:spawn-all`.
    - `validate-final-state!` — `:final?` shape.
    - `validate-machine!` — top-level dispatch + guard/action ref
      resolution.

  `walk-state-nodes` yields `[state-key state-node]` pairs for every
  node under `:states`, recursing through `:states` maps; used by the
  top-level dispatch."
  (:require [re-frame.error :as error]
            [re-frame.machines.grammar :as grammar]
            [re-frame.machines.parallel :as parallel]))

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
   (error/thrown-ex-info error-kw 'rf/reg-machine reason
                         {:recovery :fix-registration :extra extras})))

(defn- ref-resolves?
  "Mirror of the runtime resolver `transition/chase-ref`: a
  `:guards` / `:actions` keyword reference resolves iff chasing the
  keyword-indirection chain through `registry` terminates at a fn (a bare
  fn registry value, or the co-located `{:fn <fn> ...}` entry map). A
  registry VALUE may be a fn, an entry map, or another keyword
  (indirection) — so the validator must follow the FULL chain, not just
  test membership of the first key. Otherwise a multi-hop chain whose
  terminal hop is missing (`{:a :b}` with no `:b`) passes registration
  yet throws `:rf.error/machine-unresolved-guard/-action` at runtime when
  `chase-ref` returns nil — defeating the fail-fast contract Spec 005
  §Registration advertises.

  Resolution outcomes, identical to `chase-ref`:
   - reaches a fn or a `{:fn ...}` map → resolves (truthy);
   - hits a keyword with no `registry` entry → unresolved (false);
   - re-visits a keyword (a CYCLE) → unresolved (false) — the runtime
     `chase-ref` returns nil on a cycle, treating it as unresolved, so a
     cyclic indirection is rejected at registration rather than at runtime."
  [registry ref]
  (loop [r ref seen #{}]
    (cond
      (fn? r)                r
      (and (map? r) (:fn r)) (:fn r)
      (contains? seen r)     false   ;; cycle — unresolved (matches chase-ref nil)
      (keyword? r)           (if (contains? registry r)
                               (recur (get registry r) (conj seen r))
                               false) ;; dangling terminal hop — unresolved
      :else                  false)))

(defn- validate-no-spawn-timeout-ms!
  "Per Spec 005 §Wall-clock timeouts on :spawn — `:timeout-ms` /
  `:on-timeout` on `:spawn` / `:spawn-all` are rejected: registration throws
  `:rf.error/spawn-timeout-ms-removed`. Express a wall-clock spawn timeout
  via the parent state's `:after` slot instead."
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

(defn- spawn-id-xor-definition-error
  "The XOR check shared by single `:spawn` and each `:spawn-all` child:
  a spawn-spec must declare EXACTLY ONE of `:machine-id` /
  `:definition`. Returns a `:reason` string when the spec violates the XOR
  (neither key, or both keys), or nil when exactly one is present. Per Spec
  005 §`:spawn` (the spec-table key cell \"exactly one of these\") +
  Spec-Schemas §`:rf/state-node` — \"exactly one of `:machine-id` or
  `:definition`\" is a registration-time constraint. (A both-set spec would
  otherwise initialise a child from the inline `:definition` while stamping
  `:rf/machine-type` from the registered `:machine-id` — a lazy-resolution /
  restore type mismatch.)"
  [spec]
  (let [has-id?  (contains? spec :machine-id)
        has-def? (contains? spec :definition)]
    (cond
      (and has-id? has-def?)
      "declares BOTH :machine-id and :definition — exactly one is allowed (they are XOR)"
      (and (not has-id?) (not has-def?))
      "declares NEITHER :machine-id nor :definition — exactly one is required"
      :else nil)))

(defn- validate-spawn-all!
  "Per Spec 005 §Spawn-and-join via `:spawn-all`: walk the
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
          ;; A child spawn-spec must declare EXACTLY ONE of
          ;; `:machine-id` / `:definition` (XOR). A child carrying BOTH keys
          ;; would otherwise materialise a different machine type on restore
          ;; than the one that spawned it.
          (when-let [reason (spawn-id-xor-definition-error c)]
            (throw (validation-error
                     :rf.error/machine-spawn-all-bad-shape
                     (str "each child spawn-spec " reason)
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
  "Per Spec 005 §Parallel regions and Spec-Schemas
  §`:rf/transition-table` §`:type :parallel` constraint: when a root
  state-node declares `:type :parallel`, validate the shape at
  registration time.

  Error categories:
    - `:rf.error/machine-parallel-bad-shape` — `:type :parallel` declared
      without a `:regions` map, OR `:regions` is empty, OR `:regions`
      coexists with `:initial` / `:states`, OR a region body is missing
      its own `:initial`.
    - `:rf.error/machine-parallel-nested-not-supported` — a region's own
      state-tree declares `:type :parallel`; nested parallel regions
      aren't supported in v1.
    - `:rf.error/machine-parallel-on-done-target` — the parallel root's
      `:on-done` declares an in-machine `:target` in ANY value form:
      a bare-keyword target, a vector-path target,
      a map with `:target`, or a candidate vector containing a target map.
      A root-only `:type :parallel` machine has no sibling flat state to
      land a target on; the parallel `:on-done` runs its `:action` + emits
      `:fx` (the \"then continue\" is a dispatch/raise in that fx), never an
      in-machine transition target. Every target-bearing value form is
      rejected (an accepted target would silently stall the all-final
      configuration).
    - `:rf.error/machine-parallel-root-on-bad-target` — a root parallel `:on`
      transition's `:target` is NOT region-qualified. The root
      `:on` is the ancestor fallback; a `:target` must name one or more
      regions — `[<region> & <in-region-path>]` (single) or
      `[[<region> …] [<region> …]]` (multiple). A bare keyword / a head that
      is not a declared region is rejected (the root has no flat sibling state
      to land a non-region-qualified target on).
    A `:type :parallel` ROOT MAY declare `:after` — it is
    ROOT-OWNED (scheduled at machine birth, alive for the whole machine,
    stale-gated by the root's own per-path epoch). Its `:target` reuses the
    EXACT region-qualified grammar root `:on` targets use, so a non-region-
    qualified root `:after` target is rejected with the SAME
    `:rf.error/machine-parallel-root-on-bad-target` (the timer-driven analog
    of the root `:on` ancestor fallback). Express a root-level timeout this way
    rather than the semantically-weaker region-`:after`-that-`:raise`s
    workaround (whose timer is bound to an arbitrary region's lifecycle)."
  [machine]
  (when (parallel/parallel? machine)
    (when-not (and (map? (:regions machine)) (seq (:regions machine)))
      (throw (validation-error
               :rf.error/machine-parallel-bad-shape
               ":type :parallel requires a non-empty :regions map")))
    ;; The parallel root's `:on-done` must not carry an in-machine `:target`
    ;; in ANY form (root-only parallel has no flat sibling to land on).
    ;; Normalise EVERY value-form `:on-done` admits — mirroring
    ;; `transition/normalise-candidates` (the runtime grammar the
    ;; parallel-root `apply-on-done-action` resolves through) — to its
    ;; candidate map(s), then reject if any candidate declares `:target`.
    ;;
    ;; This covers every target-bearing form: a bare-keyword target
    ;; (`:on-done :next`), a vector-path target (`:on-done [:next]`), a map
    ;; with `:target`, and a candidate vector containing a target map. An
    ;; accepted target would normalise at runtime to a `:target`-only /
    ;; action-less candidate that `apply-on-done-action` selects, runs no
    ;; action for, marks the done signal handled (suppressing auto-destroy),
    ;; and moves nowhere — a SILENT STALL in the all-final configuration. Per
    ;; the loud-failure posture, every target-bearing form is rejected loudly
    ;; at registration. Action / fx-only `:on-done` (no `:target`) stays
    ;; accepted.
    (when (contains? machine :on-done)
      (let [on-done (:on-done machine)
            ;; Mirror transition/normalise-candidates: keyword → {:target kw};
            ;; vector-of-maps → as-is; any other vector → {:target vec};
            ;; map → [map]; nil/other → no target-bearing candidate.
            cands   (cond
                      (keyword? on-done) [{:target on-done}]
                      (and (vector? on-done)
                           (seq on-done)
                           (every? map? on-done)) on-done
                      (vector? on-done)  [{:target on-done}]
                      (map? on-done)     [on-done]
                      :else              [])]
        (when (some #(contains? % :target) cands)
          (throw (validation-error
                   :rf.error/machine-parallel-on-done-target
                   (str "a parallel root's :on-done cannot declare an in-machine "
                        ":target (a bare-keyword target, a vector-path target, a "
                        "map with :target, or a candidate vector containing one) "
                        "— a :type :parallel machine is root-only (no sibling "
                        "flat state to land a target on; an accepted target "
                        "would silently STALL in the all-final configuration). "
                        "Express \"then continue\" as an :action / :fx (e.g. a "
                        "dispatch to a coordinator). Per Spec 005 §Final states "
                        "§The done-state signal.")
                   {:on-done on-done})))))
    ;; Every root parallel `:on` transition's `:target` (if present) MUST be
    ;; region-qualified — the root ancestor fallback has no flat sibling state
    ;; to land a bare-keyword / non-region target on. A target is either a
    ;; single region-qualified path `[<region> & <in-region-path>]` (a vector
    ;; whose head is a declared region) OR multiple such paths
    ;; `[[<region> …] [<region> …]]` (a vector of vectors). A targetless /
    ;; action-only transition is fine (no target to check). The check
    ;; normalises each `:on` entry to its candidate map(s) and validates each
    ;; candidate's `:target`.
    ;;
    ;; The SAME region-qualified target grammar governs a root-owned `:after`
    ;; transition (the timer-driven analog of the root `:on` ancestor
    ;; fallback), so a non-region-qualified root `:after` target is rejected
    ;; with the SAME `:rf.error/machine-parallel-root-on-bad-target` keyword.
    ;; `candidates-of` is the shared `:on` / `:after` value-form normaliser
    ;; (mirroring `transition/normalise-candidates`).
    (let [region-names (set (keys (:regions machine)))
          declared?    (fn [t] (contains? region-names t))
          bad-target!  (fn [slot target]
                         (throw (validation-error
                                  :rf.error/machine-parallel-root-on-bad-target
                                  (str "a root parallel " slot " :target must be "
                                       "region-qualified — [<region> & "
                                       "<in-region-path>] for one region or "
                                       "[[<region> …] [<region> …]] for many. "
                                       "Each target's head must be a declared "
                                       "region. A :type :parallel root has no "
                                       "flat sibling state to land a bare "
                                       "keyword / non-region target on. Per "
                                       "Spec 005 §Transition broadcast §Root "
                                       "parallel :on.")
                                  {:target       target
                                   :regions      region-names})))
          check-one!   (fn [slot target]
                         (cond
                           (nil? target) nil          ; targetless / action-only
                           ;; multiple region-qualified targets
                           (and (vector? target) (seq target) (every? vector? target))
                           (doseq [t target]
                             (when-not (and (seq t) (declared? (first t)))
                               (bad-target! slot target)))
                           ;; single region-qualified target
                           (vector? target)
                           (when-not (declared? (first target))
                             (bad-target! slot target))
                           ;; bare keyword / any other shape — no flat sibling
                           :else (bad-target! slot target)))
          candidates-of (fn [v]
                          (cond
                            (nil? v)                       [{}]
                            (keyword? v)                   [{:target v}]
                            (and (vector? v) (every? map? v) (seq v)) v
                            (vector? v)                    [{:target v}]
                            (map? v)                       [v]
                            :else                          []))]
      (doseq [[_event v] (:on machine)
              cand        (candidates-of v)]
        (check-one! ":on" (:target cand)))
      (doseq [[_delay v] (:after machine)
              cand        (candidates-of v)]
        (check-one! ":after" (:target cand))))
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

  Per Spec 005 §Parallel regions: for parallel-region
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
;; default-target) §Pseudo-state constraints: the v1 CLJS reference claims
;; `:fsm/history`, so a `:type :history` node is FIRST-CLASS grammar.
;; `make-machine-handler` validates the pseudo-state's shape at registration
;; (the same layer that rejects malformed compound states):
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
;; The `:history` state-node KEY form (`{:a {:history {...}}}`) and
;; a root / region `:type :history` are NOT part of the grammar — they are
;; misplaced-history registration errors (`:rf.error/machine-history-
;; misplaced`), the named error every other malformed-placement case uses.
;; Per Spec 009 §Error contract the recovery is `:no-recovery` (registration
;; is rejected). The precise error-id catalogue for history-grammar
;; violations is owned by Spec 009; these ids conform to that family's
;; `:rf.error/machine-history-*` naming.

(def ^:private history-pseudo-keys
  "The closed key-set a `:type :history` pseudo-state may carry. Anything
  else is `:rf.error/machine-history-extra-keys`."
  #{:type :deep? :default-target})

(def ^:private history-node?
  "True iff `node` is a history pseudo-state (`:type :history`). The
  shared `grammar/history-node?` — registration and the runtime read the
  one predicate."
  grammar/history-node?)

(defn- node-at-states
  "Walk a `:states` map down absolute `path`, returning the leaf
  state-node (or nil if `path` doesn't resolve). Scope-local resolver —
  `states` is the flat machine's `:states` or a single region body's
  `:states`, so `path` is scope-relative (region names are never part of
  a within-region path). Delegates to the shared `grammar/node-at` (the
  same root→leaf descent the runtime `transition/node-at` uses) so
  registration resolves targets against EXACTLY the tree the runtime
  drives. `path` is coerced to a vector for the shared fn's count/seq
  semantics."
  [states path]
  (grammar/node-at states (vec path)))

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

  History is first-class grammar (`:fsm/history`). A
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
  "Per Spec 005 §Final states §`:final?` constraints:

   - A `:final?` state MUST NOT be compound (no `:states`, no `:initial`).
   - A `:final?` state MUST NOT declare `:on`, `:always`, `:after`,
     `:spawn`, or `:spawn-all` — final means final, no further
     transitions out. `:entry` and `:exit` ARE permitted.
   - A non-final state declaring `:output-key` is a registration error
     (`:rf.error/machine-output-key-without-final`).
   - Per Spec 005 §`:on-error`: a `:final?` leaf MAY declare
     `:error? true` — a designated ERROR terminal (re-frame2's spelling of
     XState v5's error final). A child finishing via an error leaf routes to
     the spawning parent's `:spawn :on-error` transition instead of
     `:on-done`. `:error?` on a NON-final state is meaningless and rejected
     (`:rf.error/machine-error-flag-without-final`), symmetric with
     `:output-key`.

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
              :output-key (:output-key state-node)}))

    ;; Non-final state declaring :error? — error (symmetric with
    ;; :output-key). `:error?` designates an error TERMINAL; it is
    ;; meaningless on a non-final state.
    (contains? state-node :error?)
    (throw (validation-error
             :rf.error/machine-error-flag-without-final
             (str ":error? is only meaningful on a :final? state (it designates "
                  "an error terminal — see Spec 005 §`:on-error`).")
             {:state  state-key
              :error? (:error? state-node)}))))

(defn- validate-spawn!
  "Per Spec 005 §`:spawn` + Spec-Schemas §`:rf/state-node`: a
  single `:spawn`-bearing state node's spawn-spec must declare EXACTLY ONE of
  `:machine-id` / `:definition`. Rejects both-set and neither-set at
  registration with `:rf.error/machine-spawn-bad-shape` (fail-closed) —
  without this gate a malformed spec would defer to a late actor-id
  allocation failure (neither) or a silent type mismatch on restore (both).
  `:spawn-all` children are checked by `validate-spawn-all!`
  (the `:spawn` / `:spawn-all` mutual exclusion means at most one runs here).
  Absent `:spawn` is fine."
  [state-key state-node]
  (when-let [spawn (:spawn state-node)]
    (when (map? spawn)
      (when-let [reason (spawn-id-xor-definition-error spawn)]
        (throw (validation-error
                 :rf.error/machine-spawn-bad-shape
                 (str ":spawn spec " reason ".")
                 {:state state-key
                  :spawn spawn}))))))

(defn- validate-spawn-on-error!
  "Per Spec 005 §Final states §`:on-error`: a `:spawn`-bearing
  state's `:spawn :on-error` is an `:on`-shaped transition spec — a keyword
  target, a vector-path target, a single transition map `{:target :guard
  :actions}`, or a guarded candidate vector. Reject a malformed `:on-error`
  shape at registration (`:rf.error/machine-bad-on-error-clause`); the guard /
  action ref resolution is checked by the top-level pass (alongside `:on` /
  `:on-done`). Absent `:on-error` is fine (the spawn simply has no failure
  routing — the trace + escape-hatch remain)."
  [state-key state-node]
  (when-let [spawn (:spawn state-node)]
    (when (contains? spawn :on-error)
      (let [oe (:on-error spawn)]
        (when-not (or (keyword? oe)
                      (map? oe)
                      (and (vector? oe) (seq oe)))
          (throw (validation-error
                   :rf.error/machine-bad-on-error-clause
                   (str ":spawn :on-error must be an :on-shaped transition spec — "
                        "a keyword target, a vector-path target, a single "
                        "transition map {:target :guard :actions}, or a "
                        "non-empty guarded candidate vector. Got: " (pr-str oe) ".")
                   {:state    state-key
                    :on-error oe})))))))

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
  §Self-transitions), so it is always a self-loop. A keyword
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

(defn- walk-state-nodes-with-scope
  "Like `walk-state-nodes-with-path` but additionally yields the `:states`
  SCOPE each node lives in (its flat machine's `:states` or its owning
  region's body's `:states`) — `[scope-states absolute-path state-node]`
  triples. Used by `validate-transition-targets!`, which must resolve a
  vector `:target` against the same scope the runtime resolver uses (a
  vector target is absolute FROM THE REGION ROOT, so a region's nodes resolve
  within that region's `:states`, never the parallel root). Paths are
  scope-relative — region-name keywords are never part of a within-region
  path, exactly as in `walk-state-nodes-with-path`."
  [machine]
  (letfn [(walk [scope path nodes]
            (mapcat
              (fn [[k n]]
                (let [p (conj path k)]
                  (cons [scope p n]
                        (when (:states n)
                          (walk scope p (:states n))))))
              nodes))]
    (cond
      (parallel/parallel? machine)
      (mapcat (fn [[_region region-body]]
                (walk (:states region-body) [] (:states region-body)))
              (:regions machine))

      :else
      (let [scope (:states machine)]
        (walk scope [] scope)))))

;; ---- transition target shape + resolution ---------------------------------
;;
;; Per Spec 005 (005:441 "the snapshot's :state slot is already validated at
;; registration time — a transition targeting an unknown state fails
;; registration") and Spec-Schemas §`TransitionTarget` (`[:or :keyword
;; [:vector :keyword]]`): every transition slot's `:target` MUST be a keyword
;; (a sibling of the declaring state — resolves to a direct child of the
;; declaring state's parent compound), a non-empty vector path (absolute from
;; the region/machine root), or the `:same-state` self-target sentinel.
;; Anything else is malformed; a keyword / vector that does not resolve to a
;; real node is an unresolved target. Both are rejected loudly at
;; registration: `{:target 42}` is a malformed shape, `{:target [:missing]}`
;; is unresolved. This is aligned with XState v5 (which rejects unresolvable
;; targets at machine creation). The parallel ROOT's own region-qualified
;; `:on` / `:on-done` targets have DIFFERENT (region-qualified) semantics and
;; are validated by `validate-parallel!`; this block walks only per-region /
;; flat state nodes.

(def ^:private candidate-targets
  "Normalise a transition slot's value (an `:on` entry, an `:after` entry,
  an `:on-done`, a `:spawn :on-error`) to the seq of `:target`s it declares,
  each tagged with `:present?`. The shared `grammar/candidate-targets`,
  built on the SAME `grammar/transition-value-form` recogniser the runtime
  normaliser (`transition/normalise-candidates`) uses — so registration and
  the runtime share one grammar layer by construction. The `:present?` marker
  distinguishes \"`:target` key absent\" (internal
  transition — always fine) from \"`:target` present but malformed\" (e.g.
  `{:target nil}`)."
  grammar/candidate-targets)

(defn- validate-target!
  "Validate one `:target` against the declaring state's `scope` (its region /
  machine `:states`) and `path` (its absolute scope-relative path). `slot` and
  `state-key` name the declaring site for diagnostics. A nil / `:same-state`
  target is fine (internal / self-target sentinel). A keyword resolves as a
  sibling (direct child of the declaring state's parent); a vector resolves
  absolutely from the scope root — either must land on a real node (an
  occupiable state OR a `:type :history` pseudo-state, both live in `:states`).
  A non-keyword / non-vector target is malformed shape; an unresolved keyword /
  vector is an unresolved target. Emits `:rf.error/machine-bad-target` /
  `:rf.error/machine-unresolved-target`."
  [scope path slot state-key target]
  (cond
    (nil? target)          nil
    (= :same-state target) nil
    ;; An EMPTY vector is a malformed target SHAPE, not an unresolved path:
    ;; Spec 005 §error taxonomy (005:4250) + Spec-Schemas §TransitionTarget
    ;; require a NON-EMPTY vector path. `[]` names no node, so it can never
    ;; be a real (resolvable-or-not) absolute path — it is a caller
    ;; typo/schema error in the same class as `{:target 42}`. Reject it via
    ;; the `:rf.error/machine-bad-target` (malformed-shape) branch BEFORE the
    ;; generic keyword/vector resolution branch, so tools/conformance
    ;; consumers that branch on `:rf.error/id` classify it correctly.
    ;; Aligned with XState v5, which rejects malformed targets at machine
    ;; creation rather than degrading them to a missing-state reference.
    (and (vector? target) (empty? target))
    (throw (validation-error
             :rf.error/machine-bad-target
             (str "the " slot " :target " (pr-str target) " on state "
                  state-key " is malformed — an EMPTY vector is not a valid "
                  "transition :target. A :target must be a keyword (sibling "
                  "of the declaring state), a NON-EMPTY vector path (absolute "
                  "from the region/machine root), or the :same-state "
                  "self-target sentinel. Per Spec-Schemas §TransitionTarget "
                  "([:or :keyword [:vector :keyword]]) + Spec 005 §error "
                  "taxonomy (non-empty vector).")
             {:state  state-key
              :slot   slot
              :target target}))
    (or (keyword? target) (vector? target))
    ;; A keyword is a sibling of the declaring state — owning compound is the
    ;; declaring state's PARENT (`drop-last path`). A vector is absolute.
    (when-not (resolves-to-state? scope (vec (drop-last path)) target)
      (throw (validation-error
               :rf.error/machine-unresolved-target
               (str "the " slot " :target " (pr-str target) " on state "
                    state-key " does not resolve to a real state — a keyword "
                    "target names a sibling (a direct child of the declaring "
                    "state's parent compound) and a vector target is an "
                    "absolute path from the (region) root; both MUST name a "
                    "declared state (or a :type :history pseudo-state). Per "
                    "Spec 005 §Transition resolution + Spec-Schemas "
                    "§TransitionTarget.")
               {:state  state-key
                :slot   slot
                :target target})))
    :else
    (throw (validation-error
             :rf.error/machine-bad-target
             (str "the " slot " :target " (pr-str target) " on state "
                  state-key " is malformed — a transition :target must be a "
                  "keyword (sibling of the declaring state), a non-empty vector "
                  "path (absolute from the region/machine root), or the "
                  ":same-state self-target sentinel. Per Spec-Schemas "
                  "§TransitionTarget ([:or :keyword [:vector :keyword]]).")
             {:state  state-key
              :slot   slot
              :target target}))))

(defn- valid-after-delay-key?
  "Per Spec-Schemas §`:rf/state-node` `:after` (1699-1705):
  an `:after` map KEY (the delay) is well-formed iff it is one of the
  three closed forms:

    - a POSITIVE integer — literal milliseconds (the schema's `pos-int?`),
    - a NON-EMPTY vector — a subscription vector `[sub-id & args]`
      re-resolved at runtime (the schema's `[:vector :any]`),
    - a FUNCTION — `(fn [{:keys [snapshot]}] ms)` computed once at entry
      (the schema's `fn?`).

  Mirrors `transition/classify-delay-source`'s `{:literal :sub :fn}`
  triad, but applied as a REGISTRATION gate so an invalid static key
  (`-1`, `0`, `\"soon\"`, `nil`, `[]`) is rejected at `reg-machine` time
  rather than degrading to an `:rf.warning/no-clock-configured` no-op at
  fx time. Dynamic resolutions (a sub vector / fn that RETURNS an invalid
  ms at runtime) keep their fx-time warning — only the STATIC key shape is
  gated here."
  [delay-key]
  (boolean
    (or (and (integer? delay-key) (pos? delay-key))
        (and (vector? delay-key) (seq delay-key))
        (fn? delay-key))))

(defn- validate-after-delays!
  "Reject any `:after` map KEY that is not a positive
  integer, a non-empty subscription vector, or a function, at
  registration. Walks every transition-bearing node: each state node
  (`walk-state-nodes`), every region root + the parallel root, and the
  flat-machine root — the same coverage the guard/action `:after` ref
  check applies, so an invalid delay key cannot hide on a root fallback
  `:after`.

  Throws `:rf.error/machine-bad-after-delay` (a dedicated taxonomy member,
  distinct from the value-side `:rf.error/machine-bad-after-spec` the
  transition reducer raises for a malformed transition VALUE) so the error
  widget / conformance can discriminate \"the delay key is wrong\" from
  \"the transition spec is wrong\".

  Keeps the runtime fx-time `:rf.warning/no-clock-configured` warning for
  DYNAMIC delays (sub-vector / fn) that resolve to an invalid ms at
  runtime — only the STATIC key shape is gated here."
  [machine]
  (let [check-key!
        (fn [state-key delay-key]
          (when-not (valid-after-delay-key? delay-key)
            (throw (validation-error
                     :rf.error/machine-bad-after-delay
                     (str "the :after delay key " (pr-str delay-key)
                          " on state " state-key " is invalid — an :after "
                          "delay must be a POSITIVE integer (literal ms), a "
                          "NON-EMPTY subscription vector ([sub-id & args]), or "
                          "a function ((fn [{:keys [snapshot]}] ms)). Per "
                          "Spec-Schemas §:rf/state-node :after.")
                     {:state     state-key
                      :slot      :after
                      :delay-key delay-key}))))
        roots (if (parallel/parallel? machine)
                (cons machine (vals (:regions machine)))
                [machine])]
    (doseq [[state-key state-node] (walk-state-nodes machine)
            [delay-key _t]         (:after state-node)]
      (check-key! state-key delay-key))
    (doseq [root           roots
            [delay-key _t] (:after root)]
      (check-key! :rf/root delay-key))))

(defn- validate-transition-targets!
  "Per Spec 005 (005:441) + Spec-Schemas §TransitionTarget:
  reject malformed-shape and unresolved transition `:target`s at registration
  for every transition-bearing slot of every per-region / flat state node —
  `:on`, `:after`, `:always`, a compound's `:on-done`, and a `:spawn`-bearing
  state's `:spawn :on-error`. The parallel root's region-qualified `:on` /
  `:on-done` targets are validated by `validate-parallel!` (different
  semantics) and are NOT revisited here.

  Catches `{:target 42}` (malformed → `:rf.error/machine-bad-target`) and
  `{:target [:missing]}` (unresolved → `:rf.error/machine-unresolved-target`)
  at registration rather than at the triggering dispatch (where a malformed
  target would otherwise throw `:rf.error/machine-bad-state-form` and an
  unresolved one would commit an invalid snapshot)."
  [machine]
  (doseq [[scope path node] (walk-state-nodes-with-scope machine)]
    (let [state-key (peek path)
          check!    (fn [slot v]
                      (doseq [{:keys [present? target]} (candidate-targets v)]
                        (when present?
                          (validate-target! scope path slot state-key target))))]
      (doseq [[_event v] (:on node)]
        (check! :on v))
      (doseq [[_delay v] (:after node)]
        (check! :after v))
      (doseq [entry (always-entries node)]
        (check! :always entry))
      (when (contains? node :on-done)
        (check! :on-done (:on-done node)))
      (when-let [oe (get-in node [:spawn :on-error])]
        (check! :spawn/on-error oe)))))

;; ---- machine-level :schemas map (EP-0029 A3) -------------------------------
;;
;; The machine-level `:schemas` map is the single home for a machine's
;; optional schema declarations (EP-0029 A3, the clean-break successor to the
;; retired EP-0005 `:data-schema` key). The accepted sub-key vocabulary is
;; closed: `:data` is the live, wired category this wave ships (it validates
;; the machine's `:data` slot at the `:where :machine-data` boundary — see
;; `re-frame.machines.data-validation`). The remaining A3 categories
;; (`:events` / `:output` / `:tags` / `:meta`) are accepted as DECLARATION-ONLY
;; surfaces — their values stay abstract and carry no wired behaviour yet (the
;; `[:schemas :output]` → completion-payload binding is a separate EP-0029 wave,
;; rf2-kgr3kk). `[:schemas :input]` is NOT accepted: state input (B1, "For
;; Later Consideration") is not adopted, so declaring it must fail loud rather
;; than no-op. Any other sub-key is unknown and fails loud — the closed set
;; keeps the machine contract discoverable and rejects typos / not-yet-adopted
;; categories at registration.
(def ^:private accepted-schemas-keys
  "The closed sub-key set the machine-level `:schemas` map may carry (EP-0029
  A3). `:data` is wired this wave; `:events` / `:output` / `:tags` / `:meta`
  are accepted declaration-only. `:input` is intentionally EXCLUDED (state
  input is not adopted)."
  #{:data :events :output :tags :meta})

(defn validate-schemas!
  "Validate the machine-level `:schemas` map (EP-0029 A3). When present it
  MUST be a map whose keys are all members of `accepted-schemas-keys`. An
  unknown sub-key — including `:input` (state input is not adopted) — fails
  loud with `:rf.error/machine-bad-schemas-key`; a non-map `:schemas` value
  fails loud with `:rf.error/machine-bad-schemas`. A machine with no
  `:schemas` key is unaffected. The sub-key VALUES are opaque schema values —
  this validator never interprets them (machine core requires no schema
  library, EP-0029 Non-goal / rf2-49zxkc)."
  [machine]
  (when (contains? machine :schemas)
    (let [schemas (:schemas machine)]
      (when-not (map? schemas)
        (throw (validation-error
                 :rf.error/machine-bad-schemas
                 (str "machine :schemas must be a map of schema categories "
                      "(e.g. {:data <schema>}), got " (pr-str (type schemas)))
                 {:schemas schemas})))
      (doseq [k (keys schemas)]
        (when-not (contains? accepted-schemas-keys k)
          (throw (validation-error
                   :rf.error/machine-bad-schemas-key
                   (str "machine :schemas carries unknown sub-key " k
                        ". Accepted categories are "
                        (pr-str accepted-schemas-keys)
                        (when (= :input k)
                          " (:input — state input — is not adopted; EP-0029 B1)")
                        ".")
                   {:schemas-key k :accepted accepted-schemas-keys})))))))

(defn validate-machine!
  "Run every registration-time check the machine grammar requires.
  Composed at the top of `make-machine-handler` so the registered handler
  fn's body is exclusively about request processing.

  Per Spec 005 §History states §Pseudo-state constraints:
  every `:type :history` pseudo-state — placement (must have an owning
  compound), the closed `:type` / `:deep?` / `:default-target` key-set,
  at-most-one-per-compound, and `:default-target` resolution. Throws
  `:rf.error/machine-history-misplaced` / `-extra-keys` / `-duplicate` /
  `-bad-default-target`.

  Per Spec 005 §Parallel regions: `:type :parallel`
  shape — `:regions` non-empty, mutually exclusive with `:initial` /
  `:states`, no nested parallel.

  Per Spec 005 §Schema validation / EP-0029 A3: the machine-level `:schemas`
  map (when present) must be a map whose sub-keys are within the closed set
  `#{:data :events :output :tags :meta}`. An unknown sub-key — including
  `:input` (state input is not adopted) — throws
  `:rf.error/machine-bad-schemas-key`; a non-map `:schemas` throws
  `:rf.error/machine-bad-schemas`.

  Per Spec 005 §Spawn-and-join via `:spawn-all`: every
  `:spawn-all`-bearing state node — shape, no duplicate `:id`s, required
  join-event keys per `:join` form, mutually exclusive with `:spawn`.

  Per Spec 005 §`:spawn` + Spec-Schemas §`:rf/state-node`:
  every single `:spawn`-bearing state node — and every `:spawn-all` child —
  must declare EXACTLY ONE of `:machine-id` / `:definition` (XOR). Throws
  `:rf.error/machine-spawn-bad-shape` (single `:spawn`) /
  `:rf.error/machine-spawn-all-bad-shape` (child) on both-set or neither-set.

  Every `:spawn` / `:spawn-all` rejects the unsupported
  `:timeout-ms` / `:on-timeout` slot (use parent `:after`).

  Every `:on` / `:always` / `:entry` / `:exit` slot's guard
  and action keyword refs must resolve against the machine's `:guards` /
  `:actions` maps. Throws `:rf.error/machine-unresolved-guard` /
  `:rf.error/machine-unresolved-action` on dangling refs.

  Per Spec 005 §Initial-state cascading: every compound state-node
  (declares `:states`) MUST declare `:initial`. Throws
  `:rf.error/machine-compound-state-missing-initial`.

  Per Spec 005 §Self-loop forbidden at registration: an `:always` entry
  that targets its own declaring state is rejected. Throws
  `:rf.error/machine-always-self-loop`.

  Per Spec 005 (005:441) + Spec-Schemas §TransitionTarget:
  every transition slot's `:target` (`:on` / `:after` / `:always` /
  compound `:on-done` / `:spawn :on-error`) must be a well-formed,
  resolvable target. Throws `:rf.error/machine-bad-target` (malformed
  shape) / `:rf.error/machine-unresolved-target` (keyword / vector that
  names no declared state).

  Per Spec-Schemas §`:rf/state-node` `:after`: every `:after`
  map KEY (the delay) must be a positive integer, a non-empty subscription
  vector, or a function. Throws `:rf.error/machine-bad-after-delay` for a
  static key that is none of those (`-1`, `0`, `\"soon\"`, `nil`, `[]`) —
  gated at registration rather than degrading to an fx-time
  `:rf.warning/no-clock-configured` no-op."
  [machine]
  (validate-history! machine)
  (validate-parallel! machine)
  ;; The machine-level `:schemas` map (EP-0029 A3) — closed sub-key set; an
  ;; unknown sub-key (incl. `:input`) or a non-map `:schemas` fails loud.
  (validate-schemas! machine)
  (doseq [[s n] (walk-state-nodes machine)]
    (validate-spawn! s n)
    (validate-spawn-all! s n)
    (validate-no-spawn-timeout-ms! s n)
    (validate-final-state! s n)
    (validate-spawn-on-error! s n)
    (validate-compound-initial! s n))
  ;; The self-loop check needs each declaring node's absolute path to
  ;; resolve vector `:target`s, so it drives off the path-aware walker.
  (doseq [[path n] (walk-state-nodes-with-path machine)]
    (validate-always-self-loop! path (peek path) n))
  ;; Every transition slot's `:target` shape + resolution.
  (validate-transition-targets! machine)
  ;; Every `:after` delay KEY must be a positive integer, a
  ;; non-empty subscription vector, or a function — gated at registration
  ;; rather than degrading to an fx-time :rf.warning/no-clock-configured.
  (validate-after-delays! machine)
  ;; Validate guard/action references at construction time. machine-id
  ;; isn't known yet (it's the registration-site id), so error tags use
  ;; a placeholder; real misuse traces at handler-call time fill it in.
  (let [guards-map  (:guards machine)
        actions-map (:actions machine)
        ;; Follow the FULL chase-ref chain, not just the first
        ;; key — a multi-hop keyword indirection (`{:a :b}` → `:b`) whose
        ;; terminal hop is missing, or a cyclic indirection, is rejected
        ;; here at registration rather than throwing the same unresolved
        ;; error at runtime when the timer/guard/action fires.
        check-guard! (fn [g s]
                       (when (and (keyword? g)
                                  (not (ref-resolves? guards-map g)))
                         (throw (validation-error
                                  :rf.error/machine-unresolved-guard
                                  (str "guard ref " g " does not resolve against the machine's :guards map")
                                  {:guard g :state s}))))
        check-action! (fn [a s]
                        (when (and (keyword? a)
                                   (not (ref-resolves? actions-map a)))
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
      ;; `:after` ref is failed fast here at registration rather than at
      ;; runtime when the timer fires.
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
      ;; Per Spec 005 §Final states §The done-state signal: an `:on-done` on
      ;; a compound node is an `:on`-shaped transition (fired when the
      ;; compound reaches a `:final?` child); its guard / action refs must
      ;; resolve at registration like any other slot.
      (check-transition! (:on-done state-node) s)
      ;; Per Spec 005 §Final states §`:on-error`: a `:spawn`-bearing
      ;; state's `:spawn :on-error` is an `:on`-shaped transition fired when a
      ;; spawned child fails; its guard / action refs resolve at registration
      ;; like `:on-done`. (Shape is checked separately by `validate-spawn-on-error!`.)
      (check-transition! (get-in state-node [:spawn :on-error]) s)
      (check-action! (:entry state-node) s)
      (check-action! (:exit  state-node) s))
    ;; Per Spec 005 §Transition resolution: the machine root's own `:on`
    ;; fallback (consulted at runtime) carries `:guard` / `:action` refs that
    ;; must resolve at registration too. `walk-state-nodes` yields the nodes
    ;; INSIDE each region's `:states` but not the region body itself, so a
    ;; parallel machine's per-region root `:on` / `:after` (which IS consulted
    ;; at runtime via the region's own `machine-transition-single` root
    ;; fallback) is validated here too.
    ;;
    ;; The PARALLEL ROOT's OWN `:on` (the ancestor fallback, consulted at
    ;; runtime when no region handles the event) carries `:guard` / `:action`
    ;; refs that must resolve here too, so the parent parallel root is added
    ;; to `roots`. (The root-parallel transition target SHAPE —
    ;; region-qualified — is validated by `validate-parallel!`; this block
    ;; validates only the guard/action refs, like every other transition
    ;; slot.)
    (let [roots (if (parallel/parallel? machine)
                  (cons machine (vals (:regions machine)))
                  [machine])]
      (doseq [root roots
              [_ t] (:on root)]
        (check-transition! t :rf/root))
      (doseq [root roots
              [_ t] (:after root)]
        (check-transition! t :rf/root)))
    ;; The PARALLEL ROOT's own `:on-done` (fired when all
    ;; regions reach final) carries `:guard` / `:action` refs that must
    ;; resolve at registration. (`walk-state-nodes` yields per-region nodes,
    ;; not the parallel root itself, so this is validated explicitly.)
    (when (parallel/parallel? machine)
      (check-transition! (:on-done machine) :rf/root))))
