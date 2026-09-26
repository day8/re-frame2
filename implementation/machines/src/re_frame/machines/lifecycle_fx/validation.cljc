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
    - `validate-schemas!` — machine-level `:schemas` map:
      closed sub-key set (`:data` / `:events` / `:output` / `:tags` /
      `:meta`); `:input` and unknown keys fail loud.
    - `validate-spawn!` — single `:spawn` `:machine-id` xor
      `:definition`.
    - `validate-spawn-all!` — `:spawn-all` shape.
    - `validate-no-spawn-timeout-ms!` — rejects the unsupported
      `:timeout-ms` slot on `:spawn` / `:spawn-all`.
    - `validate-final-state!` — `:final?` shape.
    - `validate-node-keys!` — reject unknown BARE state-node keys, and
      root-only keys below the root (`:rf.error/machine-unknown-node-key`);
      namespaced keys pass.
    - `validate-node-transition-keys!` — reject unknown BARE keys on every
      transition map (`:rf.error/machine-unknown-node-key` with `:slot`).
    - `validate-spawn-spec-keys!` — reject unknown BARE `:spawn` /
      `:spawn-all`-child keys (`:rf.error/machine-unknown-spawn-key`).
    - `validate-tags!` — reject a non-set `:tags` slot
      (`:rf.error/machine-bad-tags`) rather than silently coercing it.
    - `validate-machine!` — top-level dispatch + guard/action ref
      resolution.

  `walk-state-nodes` yields `[state-key state-node]` pairs for every
  node under `:states`, recursing through `:states` maps; used by the
  top-level dispatch."
  (:require [clojure.string :as str]
            [re-frame.error :as rf.error]
            [re-frame.machines.choice :as rf.machines.choice]
            [re-frame.machines.grammar :as rf.machines.grammar]
            [re-frame.machines.internal-events :as rf.machines.internal-events]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.timeout :as rf.machines.timeout]))

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
   (rf.error/thrown-ex-info error-kw 'rf/reg-machine reason
                         {:recovery :fix-registration :extra extras})))

;; ---------------------------------------------------------------------------
;; Key totality
;;
;; A machine definition is not always hand-written. It can be merged from
;; config, decoded from JSON or transit, or emitted by a generator, so its KEYS
;; are whatever the producer put there — a string, a number, a vector, an
;; opaque host object. Two operations the key checks below perform are PARTIAL
;; on the host: `namespace` is defined only on `Named`, and `pr-str` reaches an
;; arbitrary object's `toString`. A partial operation on the REJECTION path
;; destroys the very failure it was supposed to describe, so both are made
;; total here, once, rather than at each of the three key checks.

(defn- namespaced-key?
  "Is `k` the NAMESPACED open-extension carve-out that every no-silent-swallow
  key check honours?

  TOTAL over any key a map can carry. A bare `(namespace k)` is not:
  `namespace` THROWS on a key that is not `Named`, so a definition as
  ordinary as

    {:initial :a :states {:a {}} \"x\" 1}

  would raise a host `ClassCastException` (a `js/Error` on CLJS) out of
  `validate-machine!` in place of the `:rf.error/machine-unknown-node-key` that
  `reg-machine`'s registration gate promises.

  Testing `Named`-ness FIRST also makes the answer the RIGHT one rather than
  merely non-throwing: a key that is not a keyword or a symbol is not a legal
  node / spawn-spec key under any reading of the grammar, so it is not carved
  out — it lands in the offending set and earns the same rejection a misspelt
  `:on-entry` earns. `tools/machines-viz`'s hand-mirror of this walk is
  total the same way; the engine-grammar parity ratchet pins the two answers
  together."
  [k]
  (and (or (keyword? k) (symbol? k))
       (some? (namespace k))))

(def ^:private literally-printable-types
  "The `rf.error/diag-value-summary` `:type` tags whose values `pr-str` renders
  TOTALLY — no `toString` on an opaque host object, and no element-wise descent
  into a collection that might be holding one."
  #{:keyword :symbol :string :number :boolean :nil})

(defn- key-label
  "`k` rendered for a diagnostic MESSAGE, total over any key.

  An EDN scalar prints LITERALLY, because naming the key IS the diagnostic —
  \"you wrote :on-entry, the slot is :entry\" — and this validator's caller is
  `reg-machine`, which is holding the definition already. That is the
  deliberate divergence from `machines-viz`'s `definition-summary`, whose
  caller is handed a definition decoded from a share URL and therefore reports
  shape only.

  Anything else — an opaque host object, or a collection that may contain one —
  renders as its `rf.error/diag-value-summary` shape tag (`<scalar>`, `<vector>`,
  …). `pr-str` on such a value reaches `toString`, and a `toString` that throws
  would replace the structured rejection with a host exception: the same defect
  one level down from the one `namespaced-key?` prevents."
  [k]
  (let [{tag :type} (rf.error/diag-value-summary k)]
    (if (literally-printable-types tag)
      (pr-str k)
      (str "<" (name tag) ">"))))

(defn- key-labels
  "`ks` rendered as a vector-shaped diagnostic fragment, each element through
  `key-label`."
  [ks]
  (str "[" (str/join " " (map key-label ks)) "]"))

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
  "Per Spec 005 §`:timeout` / `:on-timeout` — a `:timeout-ms` slot on
  `:spawn` / `:spawn-all` is NOT accepted: registration throws
  `:rf.error/spawn-timeout-ms-removed`. Use the spawn-level `:timeout` /
  `:on-timeout` grammar validated by `rf.machines.timeout/validate-timeouts!`."
  [state-key state-node]
  (doseq [[slot-key spec]
          [[:spawn     (:spawn state-node)]
           [:spawn-all (:spawn-all state-node)]]]
    (when (map? spec)
      (when (some? (:timeout-ms spec))
        (throw (validation-error
                 :rf.error/spawn-timeout-ms-removed
                 (str ":timeout-ms is not a " slot-key " key. Express a "
                      "wall-clock spawn timeout with the spawn-level "
                      ":timeout / :on-timeout pair (a positive-integer ms or "
                      "an ISO-8601 duration), e.g. {:spawn {:machine-id … "
                      ":timeout 30000 :on-timeout {:target :timed-out}}}. "
                      "Per Spec 005 §:timeout / :on-timeout.")
                 {:state      state-key
                  :slot       slot-key
                  :timeout-ms (:timeout-ms spec)}))))))

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

(defn- inline-spawn-address-error
  "An inline `:definition` spawn-spec must carry an ADDRESS:
  `:id-prefix` (the base its `<prefix>#<n>` id is minted from) or
  `:fixed-actor-id` (the address itself). A `:machine-id` spawn needs
  neither, because its prefix defaults to that registered TYPE; an inline
  definition has no type to default to, so an unaddressed one would reach
  the id allocator with a nil prefix and crash there, after registration
  had accepted it. Returns a `:reason` string for an unaddressed inline spec,
  else nil. Shared by single `:spawn` and each `:spawn-all` child, and run
  after the XOR check."
  [spec]
  (when (and (contains? spec :definition)
             (not (or (:id-prefix spec) (:fixed-actor-id spec))))
    (str "carries an inline :definition but no address — give it :id-prefix "
         "(the base its <prefix>#<n> id is minted from) or :fixed-actor-id (the "
         "address itself); only a :machine-id spawn defaults its prefix, to that "
         "registered type")))

(def ^:private known-spawn-all-block-keys
  "The closed BARE key vocabulary a `:spawn-all` block map may declare. Any
  BARE key outside this set is rejected at registration with
  `:rf.error/machine-spawn-all-bad-shape` (no silent swallow). NAMESPACED
  keys pass (the open extension carve-out). Sibling cancellation on a join
  decision is unconditional, so `:cancel-on-decision?` is not accepted. There
  are no child-vocabulary keys: a child completes by reaching a `:final?`
  state, so a `:spawn-all` declares only how RESULTS COMBINE (Spec 005
  §Child completion protocol)."
  #{:children :join
    :on-all-complete :on-some-complete :on-any-failed})

(defn- validate-spawn-all!
  "Per Spec 005 §Spawn-and-join via `:spawn-all`: walk the
  state tree at registration time and reject malformed `:spawn-all`
  declarations.

  Error categories:
    - `:rf.error/machine-spawn-all-bad-shape` — a child spawn-spec is
      missing `:id`; or `:spawn-all` is not a map; or the join-event
      slots are missing per the required-iff rules; or no `:machine-id`
      / `:definition`; or an inline `:definition` with neither
      `:id-prefix` nor `:fixed-actor-id`; or the `:join` value is outside the closed
      `:all` / `:any` enum; or an unknown bare key on the block (e.g.
      `:cancel-on-decision?`).
    - `:rf.error/machine-spawn-all-duplicate-id` — two children share an
      `:id` keyword inside the same `:spawn-all` block. The id covers any
      duplicate spawned-actor id, whichever form spawned it: at runtime
      `lifecycle-fx.spawn` raises it too, for `:spawn-all` children that
      resolve to one actor address and for a single `:spawn` whose
      generated address a live actor already holds.
    - `:rf.error/machine-spawn-all-with-spawn` — a state node declares
      both `:spawn` and `:spawn-all` (mutually exclusive).
    - `:rf.error/machine-bad-on-done-clause` — a child's `:on-done` is not
      a fn (a join child's `:on-done` is a `:data` fold only)."
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
      ;; No-silent-swallow on the block keys: reject any unknown bare key
      ;; (e.g. `:cancel-on-decision?`) so a retired / misspelt
      ;; key fails loud rather than being silently ignored. `:timeout-ms`
      ;; is excluded — it carries its OWN dedicated retired-key rejection
      ;; (`validate-no-spawn-timeout-ms!` → `:rf.error/spawn-timeout-ms-
      ;; removed`, naming the replacement), so that SPECIFIC diagnostic wins.
      (let [offending (->> (keys spawn-all-spec)
                           (remove namespaced-key?)
                           (remove known-spawn-all-block-keys)
                           (remove #{:timeout-ms})
                           vec)]
        (when (seq offending)
          (throw (validation-error
                   :rf.error/machine-spawn-all-bad-shape
                   (str ":spawn-all block declares unknown bare key(s) "
                        (key-labels offending)
                        " — a bare key outside the reserved :spawn-all "
                        "vocabulary reads as a typo or an unsupported key "
                        "(e.g. :cancel-on-decision?) and would be "
                        "silently ignored. Use a NAMESPACED key for a user "
                        "extension. Valid keys: "
                        (pr-str (vec (sort known-spawn-all-block-keys))) ".")
                   {:state          state-key
                    :offending-keys offending}))))
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
          (when-let [reason (or (spawn-id-xor-definition-error c)
                                (inline-spawn-address-error c))]
            (throw (validation-error
                     :rf.error/machine-spawn-all-bad-shape
                     (str "each child spawn-spec " reason)
                     {:state state-key
                      :child c})))
          ;; A join child's `:on-done` is a `:data` fold only: the join folds
          ;; each completion, and the block's join events own control flow.
          (when (and (contains? c :on-done) (not (fn? (:on-done c))))
            (throw (validation-error
                     :rf.error/machine-bad-on-done-clause
                     (str "the :spawn-all child " (pr-str (:id c)) " declares a "
                          "non-fn :on-done " (pr-str (:on-done c)) ". A join "
                          "child's :on-done is a fn folding the parent's :data at "
                          "that child's successful finality — (fn [{:keys [data "
                          "result]}] new-data). Control flow under a join belongs "
                          "to the block's :on-all-complete / :on-some-complete / "
                          ":on-any-failed.")
                     {:state   state-key
                      :child   (:id c)
                      :on-done (:on-done c)}))))
        (let [ids (map :id children)]
          (when (not= (count ids) (count (set ids)))
            (let [dup (->> (frequencies ids) (filter (fn [[_ n]] (> n 1))) (map first))]
              (throw (validation-error
                       :rf.error/machine-spawn-all-duplicate-id
                       "two children share an :id keyword inside the same :spawn-all block"
                       {:state state-key :duplicate-ids dup}))))))
      ;; The join grammar is a closed two-member enum: `:all` (default)
      ;; and `:any`.
      ;; Quorum cases count completions in the parent's `:data` (each
      ;; child spec's `:on-done`) and decide with a guard on the state's
      ;; `:after` entry (Spec 005 §Composition with hierarchy and
      ;; `:after`); adding
      ;; `{:n}` later is a compatible widening. Any other `:join` value —
      ;; `{:n N}` / `{:fn ...}` included — is rejected as an unknown join
      ;; spec.
      (let [join (:join spawn-all-spec :all)]
        (cond
          (= :all join)
          (when-not (vector? (:on-all-complete spawn-all-spec))
            (throw (validation-error
                     :rf.error/machine-spawn-all-bad-shape
                     ":on-all-complete event-vector is required when :join is :all (default)"
                     {:state state-key})))
          (= :any join)
          (when-not (vector? (:on-some-complete spawn-all-spec))
            (throw (validation-error
                     :rf.error/machine-spawn-all-bad-shape
                     ":on-some-complete event-vector is required when :join is :any"
                     {:state state-key})))
          :else
          (throw (validation-error
                   :rf.error/machine-spawn-all-bad-shape
                   ":join must be :all or :any"
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
    workaround (whose timer is bound to an arbitrary region's lifecycle).
    The root's own `:spawn` child belongs to no region either, so its
    `:on-error` and transition-shaped `:on-done` targets take the same
    grammar and the same refusal."
  [machine]
  (when (rf.machines.parallel/parallel? machine)
    (when-not (and (map? (:regions machine)) (seq (:regions machine)))
      (throw (validation-error
               :rf.error/machine-parallel-bad-shape
               ":type :parallel requires a non-empty :regions map")))
    ;; The parallel root's `:on-done` must not carry an in-machine `:target`
    ;; in ANY form (root-only parallel has no flat sibling to land on).
    ;; Normalise every value-form `:on-done` admits via
    ;; `rf.machines.grammar/candidate-maps`, the same grammar
    ;; the runtime parallel-root `apply-on-done-action` resolves through), then
    ;; reject if any candidate declares `:target`.
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
    ;; accepted. A malformed shape (grammar nil) degrades to no candidates.
    (when (contains? machine :on-done)
      (let [on-done (:on-done machine)
            cands   (or (rf.machines.grammar/candidate-maps on-done) [])]
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
    ;; The same region-qualified target grammar governs a root-owned `:after`
    ;; transition (the timer-driven analog of the root `:on` ancestor
    ;; fallback), so a non-region-qualified root `:after` target is rejected
    ;; with the SAME `:rf.error/machine-parallel-root-on-bad-target` keyword.
    ;; `candidates-of` is the SHARED `:on` / `:after` value-form normaliser —
    ;; the shared `rf.machines.grammar/candidate-maps`; malformed values produce `[]`.
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
          candidates-of (fn [v] (or (rf.machines.grammar/candidate-maps v) []))]
      (doseq [[_event v] (:on machine)
              cand        (candidates-of v)]
        (check-one! ":on" (:target cand)))
      (doseq [[_delay v] (:after machine)
              cand        (candidates-of v)]
        (check-one! ":after" (:target cand)))
      ;; The root's own `:spawn` child belongs to no region either, so its
      ;; `:on-error` and transition-shaped `:on-done` take the same grammar.
      (let [{:keys [on-error on-done]} (when (map? (:spawn machine)) (:spawn machine))]
        (doseq [cand (candidates-of on-error)]
          (check-one! ":spawn :on-error" (:target cand)))
        (when-not (fn? on-done)
          (doseq [cand (candidates-of on-done)]
            (check-one! ":spawn :on-done" (:target cand))))))
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

;; ---- non-parallel root `:after` / `:timeout` -------------------------------
;;
;; Per Spec 005 §Root-level `:after` — the timer-driven ancestor fallback:
;; the feature is scoped to a `:type :parallel` machine root. Its runtime
;; support is likewise parallel-only — `transition/schedule-root-after-fx`
;; (the birth-time scheduler) is called ONLY from
;; `rf.machines.parallel/run-initial-cascade`'s parallel branch; a flat/compound
;; machine's birth (`rf.machines.parallel/bootstrap-step`) never calls it, and there is
;; no root resolver that would fire a flat root `:after` at the decl-path
;; `[]` empty-path node (`rf.machines.grammar/node-at` resolves an empty path to nil).
;; `rf.machines.timeout/validate-timeouts!` + `validate-after-delays!` both happily
;; accept a WELL-FORMED root `:timeout` / `:after` on a flat/compound
;; machine (they validate the pairing / duration / delay-key SHAPE, not
;; whether the runtime can ever schedule or resolve it), so — absent this
;; check — such a machine registers cleanly and its "whole-machine
;; deadline" silently never fires. Reject it loudly here instead, on the
;; DESUGARED machine (`validate-machine!` calls this after
;; `rf.machines.timeout/desugar-timeouts`), so a root `:timeout` — which lowers onto
;; `:after` — is caught via its lowered form in the SAME check as a
;; directly-authored root `:after`.
;;
;; A parallel machine's REGION-ROOT `:after` has the SAME
;; unscheduled shape: it sits on the region body itself (decl-path `[]` WITHIN
;; the region), not on an entered leaf. `bootstrap-step` schedules only the
;; region's entered initial LEAVES; `schedule-root-after-fx` schedules only the
;; MACHINE root's own `:after` — neither reaches the region container's own
;; `:after`, so it too registers-but-never-fires. A region body is structurally
;; a flat/compound mini-machine, so its root `:after` is the exact analog of a
;; flat machine-root `:after` and is rejected with the SAME category — keeping
;; the runtime honest (no accept-but-inert path) and consistent with the
;; machine-root rejection. (The machine's OWN parallel-root `:after` is
;; the one supported, scheduled root-`:after` form.) Reject-vs-schedule is a
;; genuine design call; this REJECTS, for consistency with the machine-root
;; rejection + fail-loud (a per-region root scheduler would be a feature
;; expansion), so a region-root deadline belongs on the region's `:initial`
;; state's own `:after` / `:timeout` instead.

(defn- validate-non-parallel-root-after!
  "Reject an UNSCHEDULED root-level `:after` — whether hand-authored or lowered
  from a `:timeout` / `:on-timeout` — with
  `:rf.error/machine-non-parallel-root-after-not-supported`. Two shapes are
  rejected:

    - a non-parallel (flat / compound) MACHINE root's `:after`; and
    - a parallel machine's REGION-ROOT `:after` (on a region body itself).

  A `:type :parallel` machine's OWN root `:after` is unaffected — that IS the
  supported, scheduled, resolved feature per Spec 005. Absent / empty `:after`
  is fine everywhere."
  [machine]
  (if (rf.machines.parallel/parallel? machine)
    ;; The machine's own parallel-root `:after` is supported; only a
    ;; REGION-ROOT `:after` is the unscheduled shape.
    (doseq [[rn region-body] (:regions machine)]
      (when (seq (:after region-body))
        (throw (validation-error
                 :rf.error/machine-non-parallel-root-after-not-supported
                 (str "region " (pr-str rn) " of a :type :parallel machine "
                      "declares a REGION-ROOT :after " (pr-str (:after region-body))
                      " on the region body itself — either hand-authored or "
                      "lowered from a region-root :timeout / :on-timeout. "
                      "Root-level :after scheduling + resolution is supported "
                      "ONLY for the :type :parallel MACHINE root (Per Spec 005 "
                      "§Root-level :after); a region body is structurally a "
                      "flat/compound mini-machine, so its OWN root :after would "
                      "register but NEVER schedule or fire (bootstrap-step "
                      "schedules only the region's entered leaves). Move the "
                      "deadline onto the region's :initial state's own :after / "
                      ":timeout instead.")
                 {:region rn :after (:after region-body)}))))
    (when (seq (:after machine))
      (throw (validation-error
               :rf.error/machine-non-parallel-root-after-not-supported
               (str "a non-parallel (flat/compound) machine root declares "
                    ":after " (pr-str (:after machine)) " — either "
                    "hand-authored or lowered from a root :timeout / "
                    ":on-timeout, or from the root :spawn's own. Root-level "
                    ":after scheduling + resolution "
                    "is supported ONLY for a :type :parallel machine root "
                    "(Per Spec 005 §Root-level :after). On a flat/compound "
                    "root the timer would register but NEVER schedule or "
                    "fire. Move the deadline onto the machine's :initial "
                    "state's own :after / :timeout instead.")
               {:after (:after machine)})))))

;; ---- machine-root slots no runtime path reads ------------------------------
;;
;; Per Spec 005 §State nodes (the machine root): the root is validated as a
;; state node, so it accepts the whole state-node vocabulary, but the runtime
;; reads only part of it there. It honours `:entry` / `:exit` / `:tags` (birth,
;; teardown, the tag union), `:spawn` (a child spawned at birth that ends with
;; the machine), `:on` (the ancestor fallback), `:initial` / `:states` /
;; `:type`, and on a `:type :parallel` root its `:regions`, its `:after` (with
;; the `:timeout` / `:on-timeout` that lowers onto it) and an action-only
;; `:on-done`. The keys below are read on a state node and never on the root —
;; the root is entered once at birth, never re-entered, and never a final
;; state — so each would register and do nothing. Reject them loudly, naming
;; the substitute. A flat root's `:after` / `:timeout` keep their own refusal
;; (`validate-non-parallel-root-after!`).

(def ^:private root-unread-keys
  "State-node keys no runtime path reads on ANY machine root."
  #{:spawn-all :always :choice :final? :output-key :error? :deep? :default-target})

(def ^:private flat-root-unread-keys
  "State-node keys no runtime path reads on a flat / compound machine root, in
  addition to `root-unread-keys`. A parallel root's `:on-done` is its
  all-regions-final signal; a flat root finishes by entering a top-level
  `:final?` state instead. The runtime runs `:regions` only under
  `:type :parallel`, so on any other root they are the likeliest sign of a
  missing `:type`."
  #{:on-done :regions})

(defn- root-slot-substitute
  "The substitute an author reaches for in place of root key `k`."
  [k parallel?]
  (case k
    :spawn-all
    (if parallel?
      (str "For one child that lives as long as the machine, declare :spawn on "
           "the root; for several, declare :spawn-all on the state of a "
           "single-state region (a :type :parallel root cannot be wrapped in a "
           "compound).")
      (str "For one child that lives as long as the machine, declare :spawn on "
           "the root; for several, wrap the tree in one compound state and "
           "declare :spawn-all there."))

    :always
    (str "The root is entered once and never re-entered, so it has no "
         "eventless settle: route :initial into a state whose :always fires "
         "at birth.")

    :choice
    (str "A :choice belongs on a :type :choice state; make the :initial state "
         "one to decide at birth.")

    (:final? :output-key :error?)
    (str "The root is never a final state: the machine finishes when it enters "
         "a :final? state that is a direct child of the root, which carries "
         ":output-key / :error?.")

    (:deep? :default-target)
    (str ":deep? / :default-target belong on a :type :history node inside a "
         "compound state.")

    :on-done
    (str "A flat or compound machine finishes by entering a top-level :final? "
         "state; to continue after a sub-flow completes, wrap it in a compound "
         "state and declare :on-done there.")

    :regions
    (str ":regions run in parallel only on a :type :parallel root: declare "
         ":type :parallel on the root, which then carries no :initial or "
         ":states.")))

(defn- validate-root-slots!
  "Reject every machine-root key no runtime path reads there with
  `:rf.error/machine-root-slot-not-supported`, naming the key(s) and the
  substitute. Flat, compound and `:type :parallel` roots alike."
  [machine]
  (let [parallel? (rf.machines.parallel/parallel? machine)
        unread    (cond-> root-unread-keys
                    (not parallel?) (into flat-root-unread-keys))
        offending (vec (sort (filter #(contains? machine %) unread)))]
    (when (seq offending)
      (throw (validation-error
               :rf.error/machine-root-slot-not-supported
               (str "the machine root declares " (key-labels offending)
                    ", which the runtime never reads on the root, so "
                    (if (next offending) "they" "it")
                    " would be silently ignored. The root runs its :entry "
                    "and spawns its :spawn child at birth, runs its :exit at "
                    "teardown, and joins its :tags to the tag union. "
                    (str/join " " (distinct (map #(root-slot-substitute % parallel?)
                                                 offending))))
               {:offending-keys offending})))))

;; ---- parallel region-body slots no runtime path reads ----------------------
;;
;; Per Spec 005 §State nodes (the machine root) and §Parallel regions: a region
;; body is the root of its region's tree and follows the machine root's rule.
;; Every region is entered at birth and exited at teardown, never on a
;; transition, so the body's `:entry` / `:exit` run there and its `:tags` join
;; the tag union. The runtime also reads its `:initial` / `:states`, its `:on`
;; (the region's ancestor fallback) and its `:type`. A region body's `:after`
;; (and the `:timeout` / `:on-timeout` lowered onto it) keeps its own refusal
;; (`validate-non-parallel-root-after!`), a `:choice` its own
;; (`rf.machines.choice/validate-node-choice!`), and a nested `:type :parallel`
;; its own (`validate-parallel!`) — each runs first. The keys below would
;; register on a region body and do nothing.

(def ^:private region-unread-keys
  "State-node keys no runtime path reads on a parallel region body."
  #{:spawn :spawn-all :always :final? :output-key :error? :deep? :default-target :regions})

(defn- region-slot-substitute
  "The substitute an author reaches for in place of region-body key `k`."
  [k]
  (case k
    (:spawn :spawn-all)
    (str "For a child that lives as long as the region, wrap the region's "
         "states in one compound state and declare " k " there.")

    :always
    (str "A region is entered once, at birth, and never re-entered, so it has "
         "no eventless settle: route the region's :initial into a state whose "
         ":always fires at birth.")

    (:final? :output-key :error?)
    (str "A region body is never a final state: a region is final when its "
         "active state is a :final? leaf.")

    (:deep? :default-target)
    (str ":deep? / :default-target belong on a :type :history node inside a "
         "compound state.")

    :regions
    (str "Parallel regions do not nest: a region body declares :initial and "
         ":states.")))

(defn- validate-region-slots!
  "Reject every key no runtime path reads on a parallel region body with
  `:rf.error/machine-root-slot-not-supported`, naming the key(s), the
  substitute, and the region under `:path` (`[:regions <region>]`)."
  [machine]
  (when (rf.machines.parallel/parallel? machine)
    (doseq [[rn body] (:regions machine)
            :let [offending (vec (sort (filter #(contains? body %) region-unread-keys)))]
            :when (seq offending)]
      (throw (validation-error
               :rf.error/machine-root-slot-not-supported
               (str "region " (key-label rn) " declares " (key-labels offending)
                    ", which the runtime never reads on a region body, so "
                    (if (next offending) "they" "it")
                    " would be silently ignored. A region runs its :entry when "
                    "the machine is born and its :exit at teardown, and joins "
                    "its :tags to the tag union. "
                    (str/join " " (distinct (map region-slot-substitute offending))))
               {:offending-keys offending
                :path           [:regions rn]})))))

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
      (rf.machines.parallel/parallel? machine)
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
  shared `rf.machines.grammar/history-node?` — registration and the runtime read the
  one predicate."
  rf.machines.grammar/history-node?)

(defn- node-at-states
  "Walk a `:states` map down absolute `path`, returning the leaf
  state-node (or nil if `path` doesn't resolve). Scope-local resolver —
  `states` is the flat machine's `:states` or a single region body's
  `:states`, so `path` is scope-relative (region names are never part of
  a within-region path). Delegates to the shared `rf.machines.grammar/node-at` (the
  same root→leaf descent the runtime `transition/node-at` uses) so
  registration resolves targets against EXACTLY the tree the runtime
  drives. `path` is coerced to a vector for the shared fn's count/seq
  semantics."
  [states path]
  (rf.machines.grammar/node-at states (vec path)))

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
  (if (rf.machines.parallel/parallel? machine)
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
  An inline `:definition` must also carry `:id-prefix` or `:fixed-actor-id`
  (`inline-spawn-address-error`), refused with the same id. A `:spawn` that
  is not a map — a vector of specs, XState's multi-`invoke` spelling — is
  refused with the same id too: a state spawns at most one child, and N
  children is `:spawn-all`.
  `:spawn-all` children are checked by `validate-spawn-all!`
  (the `:spawn` / `:spawn-all` mutual exclusion means at most one runs here).
  Absent `:spawn` is fine."
  [state-key state-node]
  (when-let [spawn (:spawn state-node)]
    (when-not (map? spawn)
      (throw (validation-error
               :rf.error/machine-spawn-bad-shape
               (str ":spawn on state " state-key " must be ONE spawn-spec map, got "
                    (pr-str spawn) ". A state spawns at most one child through "
                    ":spawn; to spawn several, declare them as the :children of a "
                    ":spawn-all block ({:spawn-all {:children [{:id :a …} {:id :b …}] "
                    ":on-all-complete [...]}}).")
               {:state state-key
                :spawn spawn})))
    (when-let [reason (or (spawn-id-xor-definition-error spawn)
                          (inline-spawn-address-error spawn))]
      (throw (validation-error
               :rf.error/machine-spawn-bad-shape
               (str ":spawn spec " reason ".")
               {:state state-key
                :spawn spawn})))))

(defn- validate-spawn-on-error!
  "Per Spec 005 §Final states §`:on-error`: a `:spawn`-bearing
  state's `:spawn :on-error` is an `:on`-shaped transition spec — a keyword
  target, a vector-path target, a single transition map `{:target :guard
  :actions}`, or a guarded candidate vector. Reject a malformed `:on-error`
  shape at registration (`:rf.error/machine-bad-on-error-clause`); the guard /
  action ref resolution is checked by the top-level pass (alongside `:on` /
  `:on-done`). Absent `:on-error` is fine: the failure event still reaches
  the parent, where an explicit `:on {:rf.machine.spawn/error …}` may take it,
  else it is a no-op, with the trace and the escape hatch available either
  way."
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

(defn- validate-spawn-on-done!
  "Per Spec 005 §Final states D2: a `:spawn`-bearing state's `:spawn :on-done`
  is either a fn — the `:data` fold `(fn [{:keys [data result]}] new-data)` —
  or an `:on`-shaped transition spec the parent takes when the child
  completes: a keyword target, a vector-path target, a single transition map
  `{:target :guard :action}`, or a non-empty guarded candidate vector, the
  same shapes `:on-error` admits. Refuse any other value at registration
  (`:rf.error/machine-bad-on-done-clause`). The transition form's targets,
  guard / action refs and keys are checked by the passes every transition slot
  shares. Absent `:on-done` is fine."
  [state-key state-node]
  (when-let [spawn (:spawn state-node)]
    (when (and (map? spawn) (contains? spawn :on-done))
      (let [od (:on-done spawn)]
        (when-not (or (fn? od)
                      (keyword? od)
                      (map? od)
                      (and (vector? od) (seq od)))
          (throw (validation-error
                   :rf.error/machine-bad-on-done-clause
                   (str ":spawn :on-done must be a fn or an :on-shaped transition "
                        "spec. A fn folds the parent's :data — (fn [{:keys [data "
                        "result]}] new-data); a keyword target, a vector-path "
                        "target, a single transition map {:target :guard :action}, "
                        "or a non-empty guarded candidate vector moves the parent "
                        "when the child completes. Got: " (pr-str od) ".")
                   {:state   state-key
                    :on-done od})))))))

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

(defn- validate-initial-resolves!
  "Per Spec 005 §Initial-state cascading: an `:initial` names the child the
  cascade enters, so it MUST be the key of a state declared in the SAME
  node's `:states`. An `:initial` naming nothing would boot the machine into
  a phantom state that handles no event. `node` is the machine root, a
  parallel region body, or a state node; `extras` is the per-site ex-data
  (`:state`, and `:region` for a region body). A node without `:initial` is
  not checked here — a compound's missing `:initial` is
  `validate-compound-initial!`'s.

  Emits `:rf.error/machine-unresolved-target` with `:slot :initial`."
  [node extras]
  (when (contains? node :initial)
    (let [initial (:initial node)
          states  (:states node)]
      (when-not (and (keyword? initial)
                     (map? states)
                     (contains? states initial))
        (throw (validation-error
                 :rf.error/machine-unresolved-target
                 (str "the :initial " (pr-str initial) " on state "
                      (key-label (:state extras)) " names no state declared in "
                      "its :states — :initial must be the key of a direct child "
                      "(declared: " (key-labels (when (map? states) (keys states)))
                      "). An undeclared "
                      ":initial would boot a phantom state that handles no event.")
                 (assoc extras :slot :initial :target initial)))))))

(defn- always-entries
  "Normalise a state-node's `:always` slot to a vector of entry maps through
  the shared `rf.machines.grammar/candidate-maps`. Absent
  `:always` — the key missing, OR present with an explicit nil value — yields
  the empty vector (no ancestor-blocking use case for `:always`, unlike `:on`
  / `:after`'s nil-is-forbidden-transition form), so the nil is gated BEFORE
  the shared normaliser (which maps nil to `[{}]`). A malformed value degrades
  to `[]`: `validate-transition-values!` has refused it with
  `:rf.error/machine-bad-always` before any caller here runs."
  [state-node]
  (let [a (:always state-node)]
    (if (nil? a)
      []
      (or (rf.machines.grammar/candidate-maps a) []))))

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

(defn- validate-always-unguarded-targetless!
  "Per Spec 005 §Self-loop forbidden at registration: an `:always` entry with
  neither a `:guard` nor a `:target` is enabled on every settle and changes no
  state, so the microstep loop re-selects it until the depth limit aborts the
  macrostep. It is refused at registration. Run-once-on-entry is `:entry`;
  the fixed-point loop is the GUARDED targetless form
  (`{:guard :more? :action :bump}`), whose action flips the guard false.

  Emits `:rf.error/machine-always-unguarded-targetless`."
  [state-key state-node]
  (doseq [entry (always-entries state-node)]
    (when (and (nil? (:guard entry))
               (nil? (:target entry)))
      (throw (validation-error
               :rf.error/machine-always-unguarded-targetless
               (str "state " state-key " declares an :always entry with no "
                    ":guard and no :target (" (pr-str entry) ") — it is enabled "
                    "on every settle and changes no state, so the microstep loop "
                    "re-runs it until the depth limit aborts the macrostep. To "
                    "run an action once on entering the state, use :entry; to "
                    "loop until a condition holds, add a :guard the action "
                    "flips false; to move on, add a :target.")
               {:state state-key
                :entry entry})))))

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
      (rf.machines.parallel/parallel? machine)
      (mapcat (fn [[_region region-body]] (walk [] (:states region-body)))
              (:regions machine))

      :else
      (walk [] (:states machine)))))

(defn- walk-state-nodes-with-scope
  "Like `walk-state-nodes-with-path` but additionally yields the `:states`
  SCOPE each node lives in (its flat machine's `:states` or its owning
  region's body's `:states`) and the OWNING REGION NAME (nil for a flat /
  compound machine) — `[scope-states absolute-path state-node region-name]`
  tuples. Used by `validate-transition-targets!`, which must resolve a
  vector `:target` against the same scope the runtime resolver uses (a
  vector target is absolute FROM THE REGION ROOT, so a region's nodes resolve
  within that region's `:states`, never the parallel root). Paths are
  scope-relative — region-name keywords are never part of a within-region
  path, exactly as in `walk-state-nodes-with-path`.

  The region name is carried for DIAGNOSTICS only — it never widens
  resolution. It lets an unresolved vector target whose head names a SIBLING
  REGION be diagnosed as the cross-region mistake it is rather than as a
  generic missing state."
  [machine]
  (letfn [(walk [scope region path nodes]
            (mapcat
              (fn [[k n]]
                (let [p (conj path k)]
                  (cons [scope p n region]
                        (when (:states n)
                          (walk scope region p (:states n))))))
              nodes))]
    (cond
      (rf.machines.parallel/parallel? machine)
      (mapcat (fn [[region region-body]]
                (walk (:states region-body) region [] (:states region-body)))
              (:regions machine))

      :else
      (let [scope (:states machine)]
        (walk scope nil [] scope)))))

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
  each tagged with `:present?`. The shared `rf.machines.grammar/candidate-targets`,
  built on the SAME `rf.machines.grammar/transition-value-form` recogniser the runtime
  normaliser (`transition/normalise-candidates`) uses — so registration and
  the runtime share one grammar layer by construction. The `:present?` marker
  distinguishes \"`:target` key absent\" (internal
  transition — always fine) from \"`:target` present but malformed\" (e.g.
  `{:target nil}`)."
  rf.machines.grammar/candidate-targets)

(defn- cross-region-note
  "Per Spec 005 §Cross-region coordination: the region-aware tail of
  an `:rf.error/machine-unresolved-target` message.

  A `:type :parallel` machine drives each region through a SYNTHETIC
  single-machine spec built from that region's body alone
  (`parallel/build-region-machine`), so a region-local `:target` resolves
  strictly WITHIN the declaring region — a region name is not addressable from
  inside a region. An author who writes `{:target [:b :two]}` inside region
  `:a` almost always means \"move sibling region `:b` to `:two`\", and the
  generic \"does not resolve to a real state\" text leaves them hunting for a
  state they can see in the machine map. Name the mistake instead, and name the
  two spellings that DO express cross-region movement.

  A keyword target is read as a one-segment path, so a region's `:on-done :b`
  naming sibling region `:b` gets the same note.

  Returns nil when `region-ctx` is absent (flat / compound machine) or when the
  target's head is not a declared sibling region — a nested in-region path
  whose head merely SHADOWS a sibling region's name resolves normally and never
  reaches here."
  [region-ctx target]
  (let [{:keys [region regions]} region-ctx
        path (cond (keyword? target)                   [target]
                   (and (vector? target) (seq target)) (vec target))
        head (first path)]
    (when (and region (contains? (disj (set regions) region) head))
      (str " NOTE: " head " names a SIBLING REGION of this parallel machine, "
           "not a state inside region " region ". A region-local :target "
           "always resolves WITHIN the declaring region (a region name is not "
           "addressable from inside a region), so " (pr-str target)
           " cannot mean \"move region " head
           (if (next path) (str " to " (pr-str (vec (rest path)))) "")
           "\". Express cross-region movement on "
           "the parallel ROOT's own :on / :after — the ancestor fallback, "
           "whose targets ARE region-qualified: "
           "{:on {<event> {:target "
           (if (next path) (pr-str [path]) (str "[[" head " <state>]]"))
           "}}} — or "
           "coordinate through a sibling-state guard (the :tags / :all-state "
           "ctx keys). Per Spec 005 §Cross-region coordination — tags as "
           "stateIn."))))

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
  `:rf.error/machine-unresolved-target`.

  `region-ctx` (nil for a flat / compound machine) is
  `{:region <declaring-region> :regions <all declared region names>}`. It is
  DIAGNOSTIC ONLY — resolution is unchanged — and drives `cross-region-note`
  plus the `:region` / `:regions` ex-data keys."
  [scope path slot state-key target region-ctx]
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
                    "§TransitionTarget."
                    (cross-region-note region-ctx target))
               (cond-> {:state  state-key
                        :slot   slot
                        :target target}
                 region-ctx (assoc :region  (:region region-ctx)
                                   :regions (set (:regions region-ctx)))))))
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
  four closed forms:

    - a POSITIVE integer — literal milliseconds (the schema's `pos-int?`),
    - an ISO-8601 duration STRING — `\"PT5S\"`, read by the parser the
      `:timeout` duration uses (`rf.machines.timeout/valid-duration?`), so the
      two delay slots accept the same strings and refuse the same `\"5s\"`
      shorthand; the timer lowers it to milliseconds when it arms,
    - a NON-EMPTY vector — a subscription vector `[sub-id & args]`
      re-resolved at runtime (the schema's `[:vector :any]`),
    - a FUNCTION — `(fn [{:keys [snapshot]}] ms)` computed once at entry
      (the schema's `fn?`).

  Mirrors `transition/classify-delay-source`'s `{:literal :sub :fn}`
  triad (an ISO string is a literal), but applied as a REGISTRATION gate so
  an invalid static key (`-1`, `0`, `\"soon\"`, `\"5s\"`, `nil`, `[]`) is
  rejected at `reg-machine` time
  rather than arming nothing at fx time. Dynamic resolutions (a sub vector /
  fn that RETURNS an invalid ms at runtime) report the same
  `:rf.error/machine-bad-after-delay` at fx time — only the STATIC key shape
  is gated here."
  [delay-key]
  (boolean
    (or (and (integer? delay-key) (pos? delay-key))
        (and (string? delay-key) (rf.machines.timeout/valid-duration? delay-key))
        (and (vector? delay-key) (seq delay-key))
        (fn? delay-key))))

(defn- validate-after-delays!
  "Reject any `:after` map KEY that is not a positive
  integer, an ISO-8601 duration string, a non-empty subscription vector, or a
  function, at registration. Walks every transition-bearing node: each state node
  (`walk-state-nodes`), every region root + the parallel root, and the
  flat-machine root — the same coverage the guard/action `:after` ref
  check applies, so an invalid delay key cannot hide on a root fallback
  `:after`.

  Throws `:rf.error/machine-bad-after-delay` (a dedicated taxonomy member,
  distinct from the value-side `:rf.error/machine-bad-after-spec` raised for
  a malformed transition VALUE) so the error
  widget / conformance can discriminate \"the delay key is wrong\" from
  \"the transition spec is wrong\".

  A DYNAMIC delay (sub-vector / fn) that resolves to an invalid ms at
  runtime reports the same id at fx time, where the timer is skipped —
  only the STATIC key shape is gated here."
  [machine]
  (let [check-key!
        (fn [state-key delay-key]
          (when-not (valid-after-delay-key? delay-key)
            (throw (validation-error
                     :rf.error/machine-bad-after-delay
                     (str "the :after delay key " (pr-str delay-key)
                          " on state " state-key " is invalid — an :after "
                          "delay must be a POSITIVE integer (literal ms), an "
                          "ISO-8601 duration string (\"PT5S\", \"PT2M\"; not the "
                          "\"5s\" shorthand), a NON-EMPTY subscription vector "
                          "([sub-id & args]), or a function "
                          "((fn [{:keys [snapshot]}] ms)). Per "
                          "Spec-Schemas §:rf/state-node :after.")
                     {:state     state-key
                      :slot      :after
                      :delay-key delay-key}))))
        roots (if (rf.machines.parallel/parallel? machine)
                (cons machine (vals (:regions machine)))
                [machine])]
    (doseq [[state-key state-node] (walk-state-nodes machine)
            [delay-key _t]         (:after state-node)]
      (check-key! state-key delay-key))
    (doseq [root           roots
            [delay-key _t] (:after root)]
      (check-key! :rf/root delay-key))))

(defn- validate-definition-shape!
  "Refuse a definition whose shape the later checks assume, before any of them
  reads it, on the machine root, each parallel region body and every state
  node. Those checks walk `:states` / `:regions` as maps and iterate `:on` /
  `:after` as maps, the `:timeout` validator and both desugars among them, so
  any other shape there would escape as a host exception instead of a refusal.

    - A `:states` / `:regions` that is neither nil nor a map, and a state node
      that is neither nil nor a map, throw `:rf.error/machine-bad-structure`.
      On a `:type :parallel` root a `:regions` that is neither nil nor a map,
      and a region body that is not a map, throw
      `:rf.error/machine-parallel-bad-shape`, as `validate-parallel!` does for
      a missing or empty one.
    - An `:on` / `:after` clause that is neither nil nor a map throws
      `:rf.error/machine-bad-on-clause` / `:rf.error/machine-bad-after-spec`,
      the categories the runtime raises for a malformed transition value.

  A `:rf.error/machine-bad-structure` or clause refusal carries `:state`, the
  `:slot` and the offending `:value`, and a region body adds `:region`; a
  parallel-root refusal carries what `validate-parallel!`'s does. nil is the
  absent value in every one of these slots."
  [machine]
  (let [refuse!
        (fn [error-id reason extras slot v]
          (throw (validation-error error-id reason (assoc extras :slot slot :value v))))

        check-node!
        (fn [extras where node]
          (doseq [slot  [:states :regions]
                  :let  [v (get node slot)]
                  :when (and (some? v) (not (map? v)))]
            (refuse! :rf.error/machine-bad-structure
                     (str "the " slot " slot on " where " is " (key-label v) " — "
                          slot " is a map from each "
                          (if (= :states slot)
                            "state id to its state node"
                            "region name to its region body")
                          ", or nil for none. Per Spec 005 §Transition table grammar.")
                     extras slot v))
          (doseq [[slot error-id] [[:on    :rf.error/machine-bad-on-clause]
                                   [:after :rf.error/machine-bad-after-spec]]
                  :let  [v (get node slot)]
                  :when (and (some? v) (not (map? v)))]
            (refuse! error-id
                     (str "the " slot " clause on " where " is " (key-label v)
                          " — an " slot " clause is a map from each "
                          (if (= :on slot) "event id" "delay")
                          " to its transition, or nil for none. Per Spec 005"
                          " §Transition table grammar.")
                     extras slot v)))

        walk!
        (fn walk! [states]
          (doseq [[k n] states
                  :when (some? n)]
            (when-not (map? n)
              (refuse! :rf.error/machine-bad-structure
                       (str "state " (key-label k) " is " (key-label n)
                            " — a state node is a map. Per Spec 005 §State nodes.")
                       {:state k} :states n))
            (check-node! {:state k} (str "state " (key-label k)) n)
            (walk! (:states n))))

        parallel? (rf.machines.parallel/parallel? machine)
        regions   (:regions machine)]
    (when parallel?
      (when (and (some? regions) (not (map? regions)))
        (throw (validation-error
                 :rf.error/machine-parallel-bad-shape
                 ":type :parallel requires a non-empty :regions map")))
      (doseq [[rn body] regions
              :when (not (map? body))]
        (throw (validation-error
                 :rf.error/machine-parallel-bad-shape
                 "each region body must be a non-empty state-node map"
                 {:region rn}))))
    (check-node! {:state :rf/root} "the machine root" machine)
    (if parallel?
      (doseq [[rn body] regions]
        (check-node! {:state :rf/region-root :region rn} (str "region " (key-label rn)) body)
        (walk! (:states body)))
      (walk! (:states machine)))))

(defn- transition-value?
  "True iff `v` is a transition value the macrostep accepts: a target state
  keyword, a target path vector, a transition map, a vector of transition
  maps, or nil. Decided by `rf.machines.grammar/candidate-maps`, the grammar
  the runtime normaliser resolves every transition value through, so
  registration refuses exactly the values a macrostep would."
  [v]
  (some? (rf.machines.grammar/candidate-maps v)))

(defn- validate-transition-values!
  "Refuse a transition value the macrostep cannot read, on the machine root,
  each parallel region body and every state node, with the category the
  runtime raises for its slot: an `:on` entry
  `:rf.error/machine-bad-on-clause`; an `:after` entry, an `:on-timeout` and a
  `:spawn :on-timeout` (both lower onto `:after`)
  `:rf.error/machine-bad-after-spec`; a non-nil `:always`
  `:rf.error/machine-bad-always`; and an `:on-done`
  `:rf.error/machine-bad-on-done-clause`. A value `transition-value?` accepts
  registers exactly as before, nil included.

  Runs on the definition as written, after the checks that refuse a slot the
  runtime never reads at a node, so a value in such a slot is refused for its
  place first. Each refusal carries `:state`, the `:slot` and the offending
  `:value`; a region body adds `:region`, an `:on` entry `:event-id` and an
  `:after` entry `:delay-key`."
  [machine]
  (let [check!
        (fn [error-id extras what slot v]
          (when-not (transition-value? v)
            (throw (validation-error
                     error-id
                     (str what " is " (key-label v) " — a transition value is a "
                          "target state keyword, a target path vector, a "
                          "transition map, a vector of transition maps, or nil."
                          " Per Spec 005 §Transition table grammar.")
                     (assoc extras :slot slot :value v)))))

        check-node!
        (fn [extras where node]
          (doseq [[event-id v] (:on node)]
            (check! :rf.error/machine-bad-on-clause (assoc extras :event-id event-id)
                    (str "the :on transition for " (key-label event-id) " on " where)
                    :on v))
          (doseq [[delay-key v] (:after node)]
            (check! :rf.error/machine-bad-after-spec (assoc extras :delay-key delay-key)
                    (str "the :after transition for " (key-label delay-key) " on " where)
                    :after v))
          (when (some? (:always node))
            (check! :rf.error/machine-bad-always extras
                    (str "the :always on " where) :always (:always node)))
          (when (contains? node :on-done)
            (check! :rf.error/machine-bad-on-done-clause extras
                    (str "the :on-done on " where) :on-done (:on-done node)))
          (when (contains? node :on-timeout)
            (check! :rf.error/machine-bad-after-spec extras
                    (str "the :on-timeout on " where) :on-timeout (:on-timeout node)))
          (let [spawn (:spawn node)]
            (when (and (map? spawn) (contains? spawn :on-timeout))
              (check! :rf.error/machine-bad-after-spec extras
                      (str "the :spawn :on-timeout on " where)
                      :spawn/on-timeout (:on-timeout spawn)))))]
    (check-node! {:state :rf/root} "the machine root" machine)
    (when (rf.machines.parallel/parallel? machine)
      (doseq [[rn body] (:regions machine)]
        (check-node! {:state :rf/region-root :region rn} (str "region " (key-label rn)) body)))
    (doseq [[state-key node] (walk-state-nodes machine)
            :when (some? node)]
      (check-node! {:state state-key} (str "state " (key-label state-key)) node))))

(defn- validate-transition-targets!
  "Per Spec 005 (005:441) + Spec-Schemas §TransitionTarget:
  reject malformed-shape and unresolved transition `:target`s at registration
  for every transition-bearing slot of every per-region / flat state node —
  `:on`, `:after`, `:always`, a compound's `:on-done`, and a `:spawn`-bearing
  state's `:spawn :on-error` and transition-shaped (non-fn)
  `:spawn :on-done`. The parallel root's region-qualified `:on` /
  `:on-done` targets are validated by `validate-parallel!` (different
  semantics) and are NOT revisited here.

  Catches `{:target 42}` (malformed → `:rf.error/machine-bad-target`) and
  `{:target [:missing]}` (unresolved → `:rf.error/machine-unresolved-target`)
  at registration rather than at the triggering dispatch (where a malformed
  target would otherwise throw `:rf.error/machine-bad-state-form` and an
  unresolved one would commit an invalid snapshot).

  Per Spec 005 §Transition resolution steps 6-7, a non-parallel
  (flat / compound) machine root's OWN `:on` is the ancestor-fallback
  transition slot `pick-transition` consults, at runtime, when no state-path
  node handles the event — stamped with decl-path `[]`, so a keyword
  `:target` resolves as a TOP-LEVEL sibling (`target-path`'s
  `(drop-last [])` → `[]`) exactly like `resolves-to-state?` with an empty
  `owning-path`. `walk-state-nodes-with-scope` only yields nodes INSIDE
  `:states` (region or flat), so the root's own `:on` needs its own check —
  unchecked, an invalid root `:on` target would register cleanly and commit
  an unresolved `:state` at the first dispatch that fell through to it
  instead of failing fast here. (A non-parallel root's `:after` cannot reach this point — it is
  rejected outright by `validate-non-parallel-root-after!`, called earlier —
  so only `:on`, and the root `:spawn`'s own `:on-error` / transition-shaped
  `:on-done`, need checking here.)

  A parallel machine's REGION BODY carries the exact analog — its own root
  `:on` is that REGION's ancestor fallback, consulted when no state-path node
  in the region handles the event, and it fires (`build-region-machine` hands
  the body to the engine as a synthetic flat machine, root `:on` included).
  The scope walker yields only nodes INSIDE `:states`, so a region-root `:on`
  target needs its own check — unchecked, ANY target would register cleanly
  there (a cross-region `[:b :two]` and a plainly-missing `[:nowhere]` alike)
  and the runtime would commit the unresolved vector verbatim into the
  region's state slot — `{:a [:b :two], :b :one}`, a nonsense configuration
  with no error. It is checked here, region-scoped exactly as a region
  state-node's target is. The region body's own `:on-done` takes the region's
  done and resolves the same way (decl-path `[]`), so it is checked alongside,
  and a sibling region's name as its target is refused rather than read as a
  cross-region move. A region-root `:after` cannot reach this point
  either — `validate-non-parallel-root-after!` rejects it — so, as at the flat
  root, only `:on` needs checking."
  [machine]
  (let [region-names (when (rf.machines.parallel/parallel? machine)
                       (set (keys (:regions machine))))
        region-ctx   (fn [region]
                       (when region {:region region :regions region-names}))]
    (doseq [[scope path node region] (walk-state-nodes-with-scope machine)]
      (let [state-key (peek path)
            check!    (fn [slot v]
                        (doseq [{:keys [present? target]} (candidate-targets v)]
                          (when present?
                            (validate-target! scope path slot state-key target
                                              (region-ctx region)))))]
        (doseq [[_event v] (:on node)]
          (check! :on v))
        (doseq [[_delay v] (:after node)]
          (check! :after v))
        (doseq [entry (always-entries node)]
          (check! :always entry))
        (when (contains? node :on-done)
          (check! :on-done (:on-done node)))
        (when-let [oe (get-in node [:spawn :on-error])]
          (check! :spawn/on-error oe))
        ;; A fn `:spawn :on-done` is a `:data` fold and carries no target.
        (let [od (get-in node [:spawn :on-done])]
          (when (and (some? od) (not (fn? od)))
            (check! :spawn/on-done od)))))
    ;; Each REGION BODY's own root `:on` — the region ancestor fallback — and
    ;; its own `:on-done`, which takes the region's done. Both are resolved
    ;; with decl-path `[]` against that region's `:states`, exactly as the
    ;; flat-root branch below resolves the machine root's own `:on`, so a
    ;; region's `:on-done` never lands outside its region.
    (doseq [[region region-body]        (:regions machine)
            [slot v]                    (concat (map (fn [[_event v]] [:on v])
                                                     (:on region-body))
                                                (when (contains? region-body :on-done)
                                                  [[:on-done (:on-done region-body)]]))
            {:keys [present? target]}   (candidate-targets v)
            :when                       present?]
      (validate-target! (:states region-body) [] slot :rf/region-root target
                        (region-ctx region))))
  (when-not (rf.machines.parallel/parallel? machine)
    (let [scope  (:states machine)
          check! (fn [slot v]
                   (doseq [{:keys [present? target]} (candidate-targets v)]
                     (when present?
                       (validate-target! scope [] slot :rf/root target nil))))]
      (doseq [[_event v] (:on machine)]
        (check! :on v))
      ;; The root's own `:spawn :on-error` and transition-shaped
      ;; `:spawn :on-done` resolve at decl-path `[]` too.
      (when-let [oe (get-in machine [:spawn :on-error])]
        (check! :spawn/on-error oe))
      (let [od (get-in machine [:spawn :on-done])]
        (when (and (some? od) (not (fn? od)))
          (check! :spawn/on-done od))))))

(defn- validate-region-spawn-paths!
  "Per Spec 005 §Reserved snapshot-internal keys (`:rf/spawned`): refuse a
  `:type :parallel` machine in which two regions declare a `:spawn` /
  `:spawn-all` at the SAME in-region path, with
  `:rf.error/machine-parallel-bad-shape`.

  The parent's `[:data :rf/spawned …]` mirror keys a region's spawn by its
  in-region path, because that is the key a region action can name (a region
  name is not addressable from inside a region). Two regions spawning at one
  in-region path would share that key, and each region's action would read,
  and could destroy, the other region's child. The runtime registry slot is
  region-qualified and would still tell them apart; the mirror cannot."
  [machine]
  (when (rf.machines.parallel/parallel? machine)
    (reduce
      (fn [seen [_scope path node region]]
        (if-not (or (:spawn node) (:spawn-all node))
          seen
          (if-let [other (get seen path)]
            (throw (validation-error
                     :rf.error/machine-parallel-bad-shape
                     (str "regions " (pr-str other) " and " (pr-str region)
                          " both declare a :spawn / :spawn-all at the in-region "
                          "path " (pr-str path) ". A parent's :rf/spawned mirror "
                          "keys a region's spawn by its in-region path — the key a "
                          "region action can name — so the two regions would share "
                          "one entry, and each would read the other's child. Rename "
                          "the spawning state in one of the regions. Per Spec 005 "
                          "§Reserved snapshot-internal keys.")
                     {:regions    [other region]
                      :state-path path}))
            (assoc seen path region))))
      {}
      (walk-state-nodes-with-scope machine))
    nil))

;; ---- machine-level :schemas map --------------------------------------------
;;
;; The machine-level `:schemas` map is the single home for a machine's
;; optional schema declarations. The accepted sub-key vocabulary is closed.
;; `:data` validates
;; the machine's `:data` slot at the `:where :machine-data` boundary — see
;; `re-frame.machines.data-validation`). `:output` validates the completion
;; payload selected by a final state's `:output-key`. `:events`, `:tags`, and
;; `:meta` are accepted as declaration-only surfaces. `:input` is not accepted
;; because state input is not supported. Any other sub-key is unknown and fails
;; loud rather than becoming a no-op; the closed set
;; keeps the machine contract discoverable and rejects typos / not-yet-adopted
;; categories at registration.
(def ^:private accepted-schemas-keys
  "The closed sub-key set the machine-level `:schemas` map may carry. `:data`
  and `:output` are wired; `:events`, `:tags`, and `:meta` are declaration-only.
  `:input` is intentionally excluded."
  #{:data :events :output :tags :meta})

(defn validate-schemas!
  "Validate the machine-level `:schemas` map. When present it
  MUST be a map whose keys are all members of `accepted-schemas-keys`. An
  unknown sub-key — including `:input` (state input is not adopted) — fails
  loud with `:rf.error/machine-bad-schemas-key`; a non-map `:schemas` value
  fails loud with `:rf.error/machine-bad-schemas`. A machine with no
  `:schemas` key is unaffected. The sub-key VALUES are opaque schema values —
  this validator never interprets them (machine core requires no schema
  library)."
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

;; ---- no-silent-swallow: unknown state-node / spawn-spec keys + :tags shape --
;;
;; Per Conventions §No silent swallow, classify every state-node and spawn-spec
;; key at registration:
;;   - key ∈ the known bare set → accepted (parsed by the rest of validation);
;;   - key is NAMESPACED → open accretion, ignored (user metadata / extensions);
;;   - key is BARE and unknown → HARD `:rf.error/machine-unknown-node-key` /
;;     `:rf.error/machine-unknown-spawn-key` naming the key + the valid
;;     vocabulary. `:meta` remains the sanctioned bare metadata slot.
;; A non-set `:tags` value fails with `:rf.error/machine-bad-tags`.

(def ^:private known-state-node-keys
  "The closed BARE key vocabulary a machine state-node may declare, projected
  from the Spec-Schemas `:rf/state-node` (`::state-node`) grammar. Any BARE key
  outside this set is a typo / a retired-or-foreign spelling and is rejected at
  registration with `:rf.error/machine-unknown-node-key`; NAMESPACED keys are the
  open user-metadata / extension carve-out and pass untouched. `:meta` is the
  sanctioned bare free slot for tooling metadata. This is the single home for the
  bare-key vocabulary — a grammar addition adds ONE key here alongside its schema
  row. The machine root also accepts `known-machine-root-extra-keys`.

  A `:type :history` pseudo-state carries its OWN closed key-set
  (`validate-history!`), so the node-key walk SKIPS it — that validator
  already rejects foreign keys with the history-specific error id. A
  `:type :choice` state is walked like any other: its own validator
  (`rf.machines.choice/validate-node-choice!`) refuses the waiting-state
  keys it must not carry, and this walk refuses an unknown bare key and a
  leaf's `:on-done`."
  #{;; root-shape / pseudo-state
    :type :deep? :default-target :regions
    ;; compound
    :initial :states
    ;; lifecycle actions
    :entry :exit
    ;; declarative actor lifecycle
    :spawn :spawn-all
    ;; transitions (eventful / eventless / delayed / named-intent)
    :always :after :choice :timeout :on-timeout :on :on-done
    ;; projection / terminal
    :tags :final? :output-key :error?
    ;; tooling (DEBUG-only, macro-stamped on any node — absent in production)
    :meta :source-coords :source-code})

(def ^:private known-machine-root-extra-keys
  "Keys legal ONLY on the machine ROOT, beyond the universal
  `known-state-node-keys`. Two groups:

    - the machine's own blocks, which the runtime reads off the root alone:
      `:data`, `:schemas`, `:internal-events`, the `:guards` / `:actions`
      registries (a parallel region inherits the root's), and the parallel
      root's `:region-order` (`rf.machines.parallel/normalise-region-order`
      stamps it once at registration);
    - the registration metadata that folds onto the machine body per
      `registration/reg-machine*`: `:doc` is the registration doc string;
      `:sensitive` / `:large` are the machine-level data-classification
      declarations (projection-relative `:data` classification); `:schema` is
      the event-vector boundary schema for the dispatched OUTER vector;
      `:raise-depth-limit` / `:always-depth-limit` are the per-machine
      cycle-detection depth overrides (`transition/raise-depth-limit-default`).

  On a nested state or a parallel region body nothing reads them, so the
  child-node walk refuses them as root-only."
  #{:data :schemas :internal-events :guards :actions :region-order
    :doc :sensitive :large :schema :raise-depth-limit :always-depth-limit})

(def ^:private retired-spawn-spec-keys
  "Retired spawn-spec keys that carry their OWN dedicated retired-key rejection
  (`validate-no-spawn-timeout-ms!` → `:rf.error/spawn-timeout-ms-removed`). They
  are excluded from the generic unknown-key detection so the SPECIFIC removal
  diagnostic wins (naming the replacement) instead of the generic
  `:rf.error/machine-unknown-spawn-key`."
  #{:timeout-ms})

(def ^:private known-spawn-spec-keys
  "The closed BARE key vocabulary a single `:spawn` spec may declare, projected
  from the Spec-Schemas `InvokeSpec` grammar. Any BARE
  key outside this set is rejected at registration with
  `:rf.error/machine-unknown-spawn-key`; NAMESPACED keys pass (the runtime stamps
  `:rf/parent-id` / `:rf/invoke-id` on declarative spawns — namespaced, so they
  are covered by the namespaced carve-out and need no explicit listing). A
  misspelt bare spawn key (`:machine` for `:machine-id`, `:on-complete` for
  `:on-done`) would otherwise leave the spawn under-specified and silently
  mis-fire. `:source-coords` / `:source-code` are the DEBUG-only macro-stamped
  reference-site slots the compiler co-locates on EVERY map node (a `:spawn` map
  included, per Spec-Schemas §`MachineElementEntry` / the reference-site coord
  note) — accepted (they are absent in production)."
  #{:machine-id :definition :data :id-prefix :on-done :on-error
    :start :fixed-actor-id :timeout :on-timeout
    :source-coords :source-code})    ;; DEBUG-only macro-stamped coord slots

(def ^:private known-spawn-all-child-spec-keys
  "The closed BARE key vocabulary a `:spawn-all` CHILD spec may declare,
  projected from the Spec-Schemas `InvokeAllChildSpec` grammar.

  It is the single-`:spawn` vocabulary plus the join-address key `:id`, MINUS
  `:on-error`. A join child has no per-child error transition: failure control
  flow under a join is the block's own `:on-any-failed`, which decides for the
  whole fan-out. Admitting `:on-error` here would accept a key nothing
  honours — the silent under-specification this whole check exists to
  prevent — so it falls through to the ordinary
  `:rf.error/machine-unknown-spawn-key`, which names the valid set. `:on-done`
  IS honoured on a child spec: it folds the parent's `:data` at that child's
  successful finality, before the join fold; a failed child skips it."
  (-> known-spawn-spec-keys
      (disj :on-error)
      (conj :id)))

(defn- unknown-bare-keys
  "The BARE (non-namespaced) keys of `m` that are NOT in `known` — the
  no-silent-swallow discriminator. A namespaced key is the open extension
  carve-out and is never flagged; see `namespaced-key?` for why that test is
  spelt the way it is and why a non-`Named` key is flagged rather than carved
  out."
  [m known]
  (->> (keys m)
       (remove namespaced-key?)
       (remove known)
       vec))

(defn- validate-node-keys!
  "Reject any unknown BARE key on a state node at registration with
  `:rf.error/machine-unknown-node-key`, naming the offending key(s) and the valid
  vocabulary. `:type :history` pseudo-states are SKIPPED — they carry their own
  closed key-set validated elsewhere. A `:type :choice` state is checked here
  too: it is a leaf, so an `:on-done` on it is refused like any leaf's.
  Namespaced keys pass (the open extension carve-out). The MACHINE ROOT (`at-root?`) additionally accepts
  the root-only keys (`known-machine-root-extra-keys`: `:data`, `:guards`,
  `:actions`, the registration metadata, …); on a nested state or a region body
  the message names them as root-only.

  `:on-done` is placement-checked with the same id: it fires when a node's
  children complete, so it belongs on a compound (`:states`) or parallel
  (`:regions`) node, and on a leaf it could never fire.
  Per Conventions §No silent swallow + §Reserved state-node keys."
  [state-key state-node at-root?]
  (when (and (map? state-node)
             (not (history-node? state-node)))
    (let [known     (cond-> known-state-node-keys
                       at-root? (into known-machine-root-extra-keys))
          offending (unknown-bare-keys state-node known)
          root-only (filterv known-machine-root-extra-keys offending)]
      (when (seq offending)
        (throw (validation-error
                 :rf.error/machine-unknown-node-key
                 (if (seq root-only)
                   (str "state " (key-label state-key) " declares root-only key(s) "
                        (key-labels root-only)
                        " — they belong on the machine root, which is the only "
                        "place the runtime reads them, so on a nested state or a "
                        "region body they would be silently ignored. Move them "
                        "to the root. Valid keys here: "
                        (pr-str (vec (sort known))) ".")
                   (str "state " (key-label state-key) " declares unknown bare key(s) "
                        (key-labels offending)
                        " — a bare key outside the reserved state-node vocabulary "
                        "reads as a typo (e.g. XState's :invoke for re-frame2's "
                        ":spawn, or :on-entry for :entry) and would be silently "
                        "ignored. Use :meta for tooling metadata, or a NAMESPACED "
                        "key (:my.app/note) for a user extension. Valid keys: "
                        (pr-str (vec (sort known))) "."))
                 {:state          state-key
                  :offending-keys offending
                  :valid-keys     known})))
      (when (and (contains? state-node :on-done)
                 (not (seq (:states state-node)))
                 (not (seq (:regions state-node))))
        (throw (validation-error
                 :rf.error/machine-unknown-node-key
                 (str "state " (key-label state-key) " declares :on-done but is "
                      "a LEAF — :on-done fires when a compound state reaches its "
                      ":final? child (or a parallel root reaches all-regions-final), "
                      "so on a node with no :states it can never fire. Declare it "
                      "on the enclosing compound; for a spawned child's "
                      "completion use the :spawn spec's own :on-done.")
                 {:state          state-key
                  :offending-keys [:on-done]
                  :valid-keys     (disj known :on-done)}))))))

(defn- validate-state-regions!
  "Refuse `:regions` on a state that is not `:type :parallel` with
  `:rf.error/machine-unknown-node-key`, the id a leaf's `:on-done` is refused
  under. The runtime runs `:regions` only on a `:type :parallel` machine root,
  so on such a state they would be silently ignored. A parallel region body's
  `:regions` is refused by `validate-region-slots!`, and a `:type :history`
  node's by its own closed key set."
  [state-key state-node]
  (when (and (map? state-node)
             (contains? state-node :regions)
             (not= :parallel (:type state-node))
             (not (history-node? state-node)))
    (throw (validation-error
             :rf.error/machine-unknown-node-key
             (str "state " (key-label state-key) " declares :regions but is not "
                  ":type :parallel — the runtime runs :regions only on a "
                  ":type :parallel machine root, so here they would be silently "
                  "ignored. Declare :type :parallel on the machine root and move "
                  "the regions there.")
             {:state          state-key
              :offending-keys [:regions]
              :valid-keys     (disj known-state-node-keys :regions)}))))

(defn- validate-spawn-spec-keys!
  "Reject any unknown BARE key on a `:spawn` spec or a `:spawn-all` child spec at
  registration with `:rf.error/machine-unknown-spawn-key`. Namespaced keys pass
  (the runtime-stamped `:rf/parent-id` / `:rf/invoke-id` + user extensions).

  The two vocabularies are NOT the same set: a `:spawn-all` child adds the
  join-address `:id` and drops `:on-error` (a join child's failure control flow
  is the block's `:on-any-failed`). Per
  Conventions §Spawn-spec keys + §No silent swallow."
  [state-key state-node]
  (let [check! (fn [spec where valid-keys]
                 (when (map? spec)
                   ;; The retired `:timeout-ms` slot has its OWN dedicated
                   ;; rejection (`:rf.error/spawn-timeout-ms-removed`, naming the
                   ;; replacement); exclude it from the generic unknown-key scan
                   ;; so that SPECIFIC diagnostic wins.
                   (let [known     (into valid-keys retired-spawn-spec-keys)
                         offending (unknown-bare-keys spec known)]
                     (when (seq offending)
                       (throw (validation-error
                                :rf.error/machine-unknown-spawn-key
                                (str where " on state " (key-label state-key)
                                     " declares unknown bare key(s) "
                                     (key-labels offending)
                                     " — a bare key outside the reserved "
                                     "spawn-spec vocabulary reads as a typo (e.g. "
                                     ":machine for :machine-id) and would leave "
                                     "the spawn silently under-specified. Use a "
                                     "NAMESPACED key for a user extension. Valid "
                                     "keys: "
                                     (pr-str (vec (sort valid-keys)))
                                     ".")
                                {:state          state-key
                                 :where          where
                                 :offending-keys offending
                                 :valid-keys     valid-keys}))))))]
    (check! (:spawn state-node) :spawn known-spawn-spec-keys)
    ;; Only a seqable `:children` has child specs to scan. Anything else — a fn
    ;; is the natural mis-spelling of a runtime-sized fan-out — is refused as
    ;; `:rf.error/machine-spawn-all-bad-shape` by `validate-spawn-all!`, which
    ;; seqing it here would pre-empt with a host exception.
    (let [children (get-in state-node [:spawn-all :children])]
      (when (seqable? children)
        (doseq [child children]
          (check! child :spawn-all-child known-spawn-all-child-spec-keys))))))

(def ^:private known-transition-keys
  "The closed BARE key vocabulary a transition map may declare, projected from
  the Spec-Schemas `Transition` grammar, plus the DEBUG-only `:source-coords` /
  `:source-code` the `reg-machine` macro co-locates on every transition map.
  A bare key outside this set is refused with
  `:rf.error/machine-unknown-node-key`; NAMESPACED keys pass."
  #{:target :reenter? :guard :action :meta :source-coords :source-code})

(def ^:private transition-key-spellings
  "XState's transition keys, each with the re-frame2 spelling the refusal names."
  {:cond     ":guard"
   :actions  ":action (one fn or keyword; call several from one fn)"
   :reenter  ":reenter?"
   :internal "the default (omit it; a transition without :reenter? true is internal)"})

(defn- validate-transition-keys!
  "Refuse an unknown BARE key on any transition map in the slot value `v` — a
  single map, a guarded candidate vector, or the keyword / path sugar (which
  carries no keys). `slot` names the transition slot for the diagnostic and the
  ex-data (`:on`, `:after`, `:always`, `:choice`, `:on-done`, `:on-timeout`,
  `:spawn/on-error`, `:spawn/on-done`, `:spawn/on-timeout`). A malformed
  value — and a fn `:spawn :on-done`, which is a `:data` fold rather than a
  transition — carries no candidate maps here; a malformed shape is refused by
  the slot's own validator.

  Without it a misspelt key is silently inert: `{:targt :b}` is a targetless
  no-op with no trace, `:cond` fires the transition unguarded, `:actions`
  drops the action, and `:reenter` never re-enters. Per Conventions §No silent
  swallow."
  [state-key slot v]
  (doseq [t (or (rf.machines.grammar/candidate-maps v) [])]
    (let [offending (unknown-bare-keys t known-transition-keys)]
      (when (seq offending)
        (throw (validation-error
                 :rf.error/machine-unknown-node-key
                 (str "the " (pr-str slot) " transition on state "
                      (key-label state-key) " declares unknown bare key(s) "
                      (key-labels offending)
                      " — a bare key outside the transition vocabulary would be "
                      "silently ignored."
                      (apply str (for [k offending
                                       :let [s (transition-key-spellings k)]
                                       :when s]
                                   (str " " (pr-str k) " is " s ".")))
                      " Use :meta for tooling metadata, or a NAMESPACED key "
                      "(:my.app/note) for a user extension. Valid keys: "
                      (pr-str (vec (sort known-transition-keys))) ".")
                 {:state          state-key
                  :slot           slot
                  :offending-keys offending
                  :valid-keys     known-transition-keys}))))))

(defn- validate-node-transition-keys!
  "Run `validate-transition-keys!` over every transition slot `node` declares —
  a state node, the machine root, or a parallel region body — on the RAW
  (pre-desugar) definition, so `:choice` candidates and `:on-timeout` specs are
  named in the slot the author wrote. `:type :history` pseudo-states are
  skipped: they carry no transition slot, and `validate-history!` refuses one."
  [state-key node]
  (when (and (map? node) (not (history-node? node)))
    (let [check-map! (fn [slot m]
                       (when (map? m)
                         (doseq [[_ v] m]
                           (validate-transition-keys! state-key slot v))))
          spawn      (when (map? (:spawn node)) (:spawn node))]
      (check-map! :on (:on node))
      (check-map! :after (:after node))
      (doseq [[slot v] [[:always (:always node)]
                        [:choice (:choice node)]
                        [:on-done (:on-done node)]
                        [:on-timeout (:on-timeout node)]
                        [:spawn/on-error (:on-error spawn)]
                        [:spawn/on-done (:on-done spawn)]
                        [:spawn/on-timeout (:on-timeout spawn)]]
              :when (some? v)]
        (validate-transition-keys! state-key slot v)))))

(defn- validate-tags!
  "Reject a NON-SET `:tags` slot on a state node at registration with
  `:rf.error/machine-bad-tags` — mirroring `:rf.error/machine-bad-internal-events`
  (its sibling set-valued slot). Per Spec-Schemas `:rf/state-node` (`:tags` is
  strict `[:set :keyword]`): silently COERCING a vector / single keyword to a
  set would violate naming rule 2 (\"never a silently-normalised alias\") and
  be inconsistent with `:internal-events`, which HARD-REJECTS exactly that
  non-set shape. A set with a
  non-keyword member is likewise rejected, and so is a member in a RESERVED
  framework namespace (`:rf/*`, `:rf.*/*`) — the same namespaces
  `:internal-events` refuses. Absent `:tags` is fine (elided slot)."
  [state-key state-node]
  (when (contains? state-node :tags)
    (let [tags (:tags state-node)]
      (when-not (and (set? tags) (every? keyword? tags))
        (throw (validation-error
                 :rf.error/machine-bad-tags
                 (str ":tags on state " state-key " must be a SET of keywords "
                      "(#{:loading :busy}), got " (pr-str tags)
                      ". A vector / single keyword is not coerced — the "
                      "slot is a strict set, mirroring :internal-events. Per "
                      "Spec 005 §State tags + Spec-Schemas :rf/state-node.")
                 {:state state-key
                  :tags  tags})))
      (let [reserved (filterv rf.machines.internal-events/reserved-rf-keyword? tags)]
        (when (seq reserved)
          (throw (validation-error
                   :rf.error/machine-bad-tags
                   (str ":tags on state " state-key " declares " (pr-str reserved)
                        " in a RESERVED framework namespace — :rf/* and :rf.*/* "
                        "belong to the framework, and a tag lands in the "
                        "user-visible snapshot :tags. Use your own namespace, "
                        "e.g. :ui.state/loading. Per Spec 005 §State tags.")
                   {:state    state-key
                    :tags     tags
                    :reserved reserved})))))))

(defn validate-machine!
  "Run every registration-time check the machine grammar requires.
  Composed at the top of `make-machine-handler` so the registered handler
  fn's body is exclusively about request processing.

  Per Spec 005 §Transition table grammar, before any other check reads the
  definition: on the machine root, each region body and every state node,
  `:states` and `:regions` are maps or nil and every state node is a map or
  nil (`:rf.error/machine-bad-structure`; `:rf.error/machine-parallel-bad-shape`
  for a parallel root's `:regions` and its region bodies), and the `:on` /
  `:after` clause is a map or nil (`:rf.error/machine-bad-on-clause` /
  `:rf.error/machine-bad-after-spec`). Every transition value is one the
  macrostep reads, refused otherwise with the category the runtime raises for
  its slot (`:rf.error/machine-bad-on-clause`,
  `:rf.error/machine-bad-after-spec`, `:rf.error/machine-bad-always`,
  `:rf.error/machine-bad-on-done-clause`).

  Per Spec 005 §History states §Pseudo-state constraints:
  every `:type :history` pseudo-state — placement (must have an owning
  compound), the closed `:type` / `:deep?` / `:default-target` key-set,
  at-most-one-per-compound, and `:default-target` resolution. Throws
  `:rf.error/machine-history-misplaced` / `-extra-keys` / `-duplicate` /
  `-bad-default-target`.

  Per Spec 005 §Parallel regions: `:type :parallel`
  shape — `:regions` non-empty, mutually exclusive with `:initial` /
  `:states`, no nested parallel.

  Per Spec 005 §Root-level `:after` (scoped to a `:type :parallel` root):
  a NON-parallel (flat / compound) machine root's `:after` — hand-authored
  or lowered from a root `:timeout` / `:on-timeout` — has no runtime
  scheduling / resolution path and is rejected with
  `:rf.error/machine-non-parallel-root-after-not-supported`.

  Per Spec 005 §Transition resolution steps 6-7: a non-parallel machine
  root's own `:on` (the ancestor fallback, decl-path `[]`) is validated
  for target shape + resolution exactly like a state's `:on`, throwing
  `:rf.error/machine-bad-target` / `:rf.error/machine-unresolved-target`
  on a malformed or dangling target (a parallel root's region-qualified
  `:on` is validated separately by `validate-parallel!`).

  Per Spec 005 §Schema validation, the machine-level `:schemas`
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
  must declare EXACTLY ONE of `:machine-id` / `:definition` (XOR), and an
  inline `:definition` must carry `:id-prefix` or `:fixed-actor-id`. Throws
  `:rf.error/machine-spawn-bad-shape` (single `:spawn`) /
  `:rf.error/machine-spawn-all-bad-shape` (child) on both-set, neither-set,
  or an unaddressed inline definition.

  Every `:spawn` / `:spawn-all` rejects the unsupported `:timeout-ms` slot;
  spawn-level `:timeout` / `:on-timeout` is supported.

  Per Spec 005 §Final states D2: a single `:spawn`'s `:on-done` is a fn (the
  `:data` fold) or an `:on`-shaped transition, and a `:spawn-all` child's
  `:on-done` is a fn; anything else throws
  `:rf.error/machine-bad-on-done-clause`. Two parallel regions declaring a
  `:spawn` / `:spawn-all` at the same in-region path throw
  `:rf.error/machine-parallel-bad-shape` (they would share one `:rf/spawned`
  mirror entry).

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
  compound `:on-done` / `:spawn :on-error` / transition-shaped
  `:spawn :on-done`) must be a well-formed,
  resolvable target. Throws `:rf.error/machine-bad-target` (malformed
  shape) / `:rf.error/machine-unresolved-target` (keyword / vector that
  names no declared state).

  Per Spec-Schemas §`:rf/state-node` `:after`: every `:after`
  map KEY (the delay) must be a positive integer, an ISO-8601 duration string
  (the `:timeout` duration grammar), a non-empty subscription vector, or a
  function. Throws `:rf.error/machine-bad-after-delay` for a static key that
  is none of those (`-1`, `0`, `\"soon\"`, `\"5s\"`, `nil`, `[]`) —
  gated at registration rather than arming nothing at fx time.

  Per Conventions §No silent swallow + §Reserved state-node keys /
  §Spawn-spec keys: every state node (root + descendants + parallel-region
  roots) rejects an unknown BARE key with `:rf.error/machine-unknown-node-key`
  — a root-only key (`:data`, `:guards`, …) below the root included — and so
  does every transition map in every transition slot (ex-data `:slot`),
  and every `:spawn` / `:spawn-all`-child spawn-spec rejects an unknown BARE key
  with `:rf.error/machine-unknown-spawn-key` (namespaced keys pass — the open
  extension carve-out). A non-set `:tags` slot is rejected with
  `:rf.error/machine-bad-tags` (never silently coerced to a set), mirroring
  `:rf.error/machine-bad-internal-events`.

  Per Spec 005 §State nodes (the machine root): a machine root declaring a
  state-node key no runtime path reads on the root (`:spawn-all`,
  `:always`, `:choice`, `:final?`, `:output-key`, `:error?`, `:deep?`,
  `:default-target`, and a flat root's `:on-done` and `:regions`) throws
  `:rf.error/machine-root-slot-not-supported`, before any other check reads
  the root. The root's own `:spawn` is held
  to the same spawn grammar, target and ref checks a state's is. A parallel region body follows
  the same rule: one declaring `:spawn`, `:spawn-all`, `:always`, `:final?`,
  `:output-key`, `:error?`, `:deep?`, `:default-target` or `:regions` throws
  it too, with `:path` naming the region. A state declaring `:regions` without
  `:type :parallel` throws `:rf.error/machine-unknown-node-key`."
  [machine]
  ;; Every check below walks `:states` / `:regions` as maps and reads each
  ;; node's `:on` / `:after` as a map, the `:timeout` validator first among
  ;; them, so any other shape is refused before anything iterates it.
  (validate-definition-shape! machine)
  ;; The root accepts the whole state-node vocabulary; refuse the part of it no
  ;; runtime path reads on the root before any slot validator reads it. The
  ;; `:timeout` validator walks a root's `:regions` bodies whatever the root's
  ;; `:type`, so a flat root's `:regions` is refused here, ahead of it.
  (validate-root-slots! machine)
  ;; Validate the `:timeout` / `:on-timeout` grammar on the raw spec, before
  ;; either desugar, so diagnostics name the `:timeout` / `:on-timeout` keys the
  ;; author wrote (timeout-requires-on-timeout pairing, the integer-ms /
  ;; ISO-8601-only duration rule rejecting the "5s"/"10ms" shorthand, and
  ;; the :after-collision guard). Then DESUGAR the spec so every subsequent
  ;; structural validator (transition targets, final-state shape, after
  ;; delays) sees the lowered `:after` form — the `:on-timeout` transition
  ;; target flows through the same target-resolution check `:after` uses, a
  ;; `:final?` state carrying a `:timeout` is rejected as it would be for an
  ;; `:after`, and the desugared form is exactly what the runtime drives.
  (rf.machines.timeout/validate-timeouts! machine)
  ;; Validate the `:type :choice` / `:choice` grammar on the
  ;; RAW spec (before BOTH desugars) so diagnostics name the `:type :choice`
  ;; / `:choice` keys the author wrote AND a choice state that also declares
  ;; a reserved key (incl. `:timeout`) is caught with that key still present.
  ;; The path-aware walker yields the declaring node's absolute path so a
  ;; self-targeting candidate is resolved.
  (doseq [[path n] (walk-state-nodes-with-path machine)]
    (rf.machines.choice/validate-node-choice! path (peek path) n))
  ;; A parallel region ROOT (a region body) may itself be a `:type :choice`
  ;; node only in a degenerate sense; the walker above does not yield region
  ;; roots, so validate them here for completeness (rejected via the same
  ;; reserved-key / shape rules — a region root carries `:states`, a
  ;; reserved key, so a `:type :choice` region root fails loud).
  (when (rf.machines.parallel/parallel? machine)
    (doseq [[rn body] (:regions machine)]
      (rf.machines.choice/validate-node-choice! [rn] rn body)))
  ;; Validate the `:internal-events` declaration on the raw
  ;; spec: it must be a set of keywords, and reserved `:rf/*` lifecycle names
  ;; are forbidden. A declared internal event is expected to have an ordinary
  ;; `:on` handler; the visibility boundary rejects only external dispatch.
  ;; Neither named-intent desugar touches `:internal-events`,
  ;; so the raw spec is the right basis.
  (rf.machines.internal-events/validate-internal-events! machine)
  ;; No-silent-swallow on state-node / spawn-spec keys + the `:tags` shape, on
  ;; the RAW spec (before BOTH desugars) so diagnostics name the exact keys the
  ;; author wrote — a `:choice` / `:timeout` / `:on-timeout` key is still present
  ;; here and is a KNOWN member of the bare vocabulary, so it is not flagged; a
  ;; typo (XState's `:invoke` / `:on-entry`) IS. `:type :history` pseudo-states
  ;; are skipped (their own closed key-set validates them).
  ;; Per Conventions §No silent swallow + §Reserved state-node keys /
  ;; §Spawn-spec keys. Every transition map in every slot is held to the
  ;; closed transition vocabulary the same way (`validate-node-transition-keys!`).
  ;; The machine ROOT is itself a state-node (it carries `:initial` / `:states`
  ;; or `:regions`, plus the root-only `:guards` / `:actions` / `:data` /
  ;; `:schemas`), and `walk-state-nodes` yields only the nodes UNDER
  ;; `:states`, so validate the root explicitly (a typo'd top-level key —
  ;; `:innitial`, `:gaurds` — must not slip through).
  (validate-node-keys! :rf/root machine true)
  (validate-spawn-spec-keys! :rf/root machine)
  (validate-node-transition-keys! :rf/root machine)
  (validate-tags! :rf/root machine)
  (doseq [[s n] (walk-state-nodes machine)]
    (validate-node-keys! s n false)
    (validate-state-regions! s n)
    (validate-spawn-spec-keys! s n)
    (validate-node-transition-keys! s n)
    (validate-tags! s n))
  ;; A parallel region ROOT (a region body) is a state-node the plain
  ;; `walk-state-nodes` does NOT yield, so run the key / tags checks on each
  ;; region body too (a typo'd bare key on a region root must not slip through).
  ;; A region body is NOT the machine root — the root-only keys (`:data` /
  ;; `:guards` / `:doc` / …) live on the machine root, not per region.
  (when (rf.machines.parallel/parallel? machine)
    (doseq [[rn body] (:regions machine)]
      (validate-node-keys! rn body false)
      (validate-spawn-spec-keys! rn body)
      (validate-node-transition-keys! rn body)
      (validate-tags! rn body)))
  ;; DESUGAR both named-intent grammars onto their underlying mechanisms
  ;; (`:timeout` → `:after`, `:choice` → `:always`) so every subsequent
  ;; structural validator (transition targets, self-loop, after delays) and
  ;; the runtime see the lowered form.
  (let [as-written machine
        machine    (rf.machines.choice/desugar-choices (rf.machines.timeout/desugar-timeouts machine))]
  (validate-history! machine)
  (validate-parallel! machine)
  (validate-region-spawn-paths! machine)
  ;; A non-parallel root's `:after` (hand-authored or lowered
  ;; from a root `:timeout` / `:on-timeout`) has no runtime scheduling /
  ;; resolution path; reject it loudly rather than silently registering a
  ;; whole-machine deadline that never fires. Runs on the DESUGARED machine
  ;; so a root `:timeout` is caught via its lowered `:after` form too.
  (validate-non-parallel-root-after! machine)
  ;; A region body follows the machine root's rule: refuse the keys no
  ;; runtime path reads on it. Runs after the nested-parallel and region
  ;; `:after` refusals, which name those shapes more precisely.
  (validate-region-slots! machine)
  ;; Every transition value is one the macrostep reads. Checked after the
  ;; refusals above, which name a slot the runtime never reads at its node,
  ;; and on the definition as written, so an `:on-timeout` is named as such
  ;; rather than as the `:after` entry it lowers onto.
  (validate-transition-values! as-written)
  ;; The machine-level `:schemas` map has a closed sub-key set; an
  ;; unknown sub-key (incl. `:input`) or a non-map `:schemas` fails loud.
  (validate-schemas! machine)
  ;; The machine root's own `:spawn` is held to the spawn grammar a state's is
  ;; (`:spawn-all` is refused on the root above).
  (validate-spawn! :rf/root machine)
  (validate-no-spawn-timeout-ms! :rf/root machine)
  (validate-spawn-on-error! :rf/root machine)
  (validate-spawn-on-done! :rf/root machine)
  (doseq [[s n] (walk-state-nodes machine)]
    (validate-spawn! s n)
    (validate-spawn-all! s n)
    (validate-no-spawn-timeout-ms! s n)
    (validate-final-state! s n)
    (validate-spawn-on-error! s n)
    (validate-spawn-on-done! s n)
    (validate-compound-initial! s n)
    (validate-initial-resolves! n {:state s}))
  ;; The machine root's and each region body's own `:initial` — the entry
  ;; points `walk-state-nodes` does not yield. A parallel root carries no
  ;; `:initial` (`validate-parallel!` refuses one).
  (if (rf.machines.parallel/parallel? machine)
    (doseq [[rn body] (:regions machine)]
      (validate-initial-resolves! body {:state :rf/region-root :region rn}))
    (validate-initial-resolves! machine {:state :rf/root}))
  ;; The self-loop check needs each declaring node's absolute path to
  ;; resolve vector `:target`s, so it drives off the path-aware walker.
  (doseq [[path n] (walk-state-nodes-with-path machine)]
    (validate-always-self-loop! path (peek path) n)
    (validate-always-unguarded-targetless! (peek path) n))
  ;; Every transition slot's `:target` shape + resolution.
  (validate-transition-targets! machine)
  ;; Every `:after` delay KEY must be a positive integer, an ISO-8601
  ;; duration string, a non-empty subscription vector, or a function — gated at registration
  ;; rather than arming nothing at fx time.
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
        ;; A `:guard` / action slot holds ONE fn, ONE keyword ref, or nil —
        ;; the same closed form the engine's `resolve-guard` /
        ;; `resolve-action` accept. Any other value (a vector of actions, a
        ;; `{:type …}` / `{:and …}` / `{:id … :params …}` map) is refused here
        ;; with the engine's own form ids, rather than at the first drain that
        ;; reaches it.
        check-guard! (fn [g s]
                       (cond
                         (keyword? g)
                         (when-not (ref-resolves? guards-map g)
                           (throw (validation-error
                                    :rf.error/machine-unresolved-guard
                                    (str "guard ref " g " does not resolve against the machine's :guards map")
                                    {:guard g :state s})))

                         (and (some? g) (not (fn? g)))
                         (throw (validation-error
                                  :rf.error/machine-bad-guard-form
                                  (str "the :guard " (pr-str g) " on state " s
                                       " is not a guard — a :guard is ONE fn or "
                                       "ONE keyword naming an entry in the "
                                       "machine's :guards map. There is no data "
                                       "form: compose predicates inside one fn "
                                       "(for XState's and/or/not), or name the "
                                       "parameterised guard in :guards (for "
                                       "{:id … :params …}).")
                                  {:guard g :state s :slot :guard}))))
        check-action! (fn [a s slot]
                        (cond
                          (keyword? a)
                          (when-not (ref-resolves? actions-map a)
                            (throw (validation-error
                                     :rf.error/machine-unresolved-action
                                     (str "action ref " a " does not resolve against the machine's :actions map")
                                     {:action a :state s})))

                          (and (some? a) (not (fn? a)))
                          (throw (validation-error
                                   :rf.error/machine-bad-action-form
                                   (str "the " slot " " (pr-str a) " on state " s
                                        " is not an action — " slot " is ONE fn "
                                        "or ONE keyword naming an entry in the "
                                        "machine's :actions map, never a vector "
                                        "or an XState {:type …} object. To run "
                                        "several, call them in order from one fn, "
                                        "or name that fn in :actions.")
                                   {:action a :state s :slot slot}))))
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
                              (check-action! (:action tt) s :action)))]
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
        (check-action! (:action t) s :action))
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
      ;; A transition-shaped `:spawn :on-done` resolves its guard / action refs
      ;; the same way; a fn `:on-done` is the `:data` fold and carries none.
      (let [od (get-in state-node [:spawn :on-done])]
        (when-not (fn? od)
          (check-transition! od s)))
      (check-action! (:entry state-node) s :entry)
      (check-action! (:exit  state-node) s :exit))
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
    (let [roots (if (rf.machines.parallel/parallel? machine)
                  (cons machine (vals (:regions machine)))
                  [machine])]
      (doseq [root roots
              [_ t] (:on root)]
        (check-transition! t :rf/root))
      (doseq [root roots
              [_ t] (:after root)]
        (check-transition! t :rf/root)))
    ;; The machine root's own `:entry` / `:exit`, and each parallel region
    ;; body's, run at birth and teardown (`walk-state-nodes` yields neither),
    ;; so their refs resolve here like any state's.
    (check-action! (:entry machine) :rf/root :entry)
    (check-action! (:exit  machine) :rf/root :exit)
    ;; The root's own `:spawn :on-error` / transition-shaped `:on-done` too.
    (check-transition! (get-in machine [:spawn :on-error]) :rf/root)
    (let [od (get-in machine [:spawn :on-done])]
      (when-not (fn? od)
        (check-transition! od :rf/root)))
    (when (rf.machines.parallel/parallel? machine)
      (doseq [[rn body] (:regions machine)]
        (check-action! (:entry body) rn :entry)
        (check-action! (:exit  body) rn :exit)
        ;; A region body's own `:on-done` takes the region's done — the
        ;; compound case scoped to one region — so its guard / action refs
        ;; resolve here like a compound's, attributed to the region.
        (check-transition! (:on-done body) rn)))
    ;; The PARALLEL ROOT's own `:on-done` (fired when all
    ;; regions reach final) carries `:guard` / `:action` refs that must
    ;; resolve at registration. (`walk-state-nodes` yields per-region nodes,
    ;; not the parallel root itself, so this is validated explicitly.)
    (when (rf.machines.parallel/parallel? machine)
      (check-transition! (:on-done machine) :rf/root)))))
