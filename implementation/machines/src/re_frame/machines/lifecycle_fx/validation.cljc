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
    - `validate-node-keys!` — reject unknown BARE state-node keys
      (`:rf.error/machine-unknown-node-key`); namespaced keys pass.
    - `validate-spawn-spec-keys!` — reject unknown BARE `:spawn` /
      `:spawn-all`-child keys (`:rf.error/machine-unknown-spawn-key`).
    - `validate-tags!` — reject a non-set `:tags` slot
      (`:rf.error/machine-bad-tags`); the silent coercion is removed.
    - `validate-machine!` — top-level dispatch + guard/action ref
      resolution.

  `walk-state-nodes` yields `[state-key state-node]` pairs for every
  node under `:states`, recursing through `:states` maps; used by the
  top-level dispatch."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.machines.choice :as choice]
            [re-frame.machines.grammar :as grammar]
            [re-frame.machines.internal-events :as internal-events]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.timeout :as timeout]))

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

;; ---------------------------------------------------------------------------
;; Key totality (rf2-dhl4d)
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

  TOTAL over any key a map can carry. This used to be a bare `(namespace k)`,
  and `namespace` THROWS on a key that is not `Named`, so a definition as
  ordinary as

    {:initial :a :states {:a {}} \"x\" 1}

  raised a host `ClassCastException` (a `js/Error` on CLJS) out of
  `validate-machine!` in place of the `:rf.error/machine-unknown-node-key` that
  `reg-machine`'s registration gate promises (rf2-dhl4d).

  Testing `Named`-ness FIRST also makes the answer the RIGHT one rather than
  merely non-throwing: a key that is not a keyword or a symbol is not a legal
  node / spawn-spec key under any reading of the grammar, so it is not carved
  out — it lands in the offending set and earns the same rejection a misspelt
  `:on-entry` earns. `tools/machines-viz`'s hand-mirror of this walk was made
  total the same way under rf2-oztox; the engine-grammar parity ratchet pins
  the two answers together."
  [k]
  (and (or (keyword? k) (symbol? k))
       (some? (namespace k))))

(def ^:private literally-printable-types
  "The `error/diag-value-summary` `:type` tags whose values `pr-str` renders
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
  renders as its `error/diag-value-summary` shape tag (`<scalar>`, `<vector>`,
  …). `pr-str` on such a value reaches `toString`, and a `toString` that throws
  would replace the structured rejection with a host exception: the same defect
  one level down from the one `namespaced-key?` fixes."
  [k]
  (let [{tag :type} (error/diag-value-summary k)]
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
  "Per Spec 005 §`:timeout` / `:on-timeout` — the legacy `:timeout-ms` slot
  on `:spawn` / `:spawn-all` is REMOVED: registration throws
  `:rf.error/spawn-timeout-ms-removed`. Use the spawn-level `:timeout` /
  `:on-timeout` grammar validated by `timeout/validate-timeouts!`."
  [state-key state-node]
  (doseq [[slot-key spec]
          [[:spawn     (:spawn state-node)]
           [:spawn-all (:spawn-all state-node)]]]
    (when (map? spec)
      (when (some? (:timeout-ms spec))
        (throw (validation-error
                 :rf.error/spawn-timeout-ms-removed
                 (str "the legacy :timeout-ms slot on " slot-key
                      " was removed. Express a wall-clock spawn timeout via "
                      "the EP-0029 A4 spawn-level :timeout / :on-timeout "
                      "grammar (a positive-integer ms or an ISO-8601 "
                      "duration), e.g. {:spawn {:machine-id … :timeout 30000 "
                      ":on-timeout {:target :timed-out}}}.")
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

(def ^:private known-spawn-all-block-keys
  "The closed BARE key vocabulary a `:spawn-all` block map may declare. Any
  BARE key outside this set is rejected at registration with
  `:rf.error/machine-spawn-all-bad-shape` (no silent swallow). NAMESPACED
  keys pass (the open extension carve-out). Sibling cancellation on a join
  decision is unconditional, so `:cancel-on-decision?` is not accepted."
  #{:children :join
    :on-child-done :on-child-error
    :on-all-complete :on-some-complete :on-any-failed})

(defn- validate-spawn-all!
  "Per Spec 005 §Spawn-and-join via `:spawn-all`: walk the
  state tree at registration time and reject malformed `:spawn-all`
  declarations.

  Error categories:
    - `:rf.error/machine-spawn-all-bad-shape` — a child spawn-spec is
      missing `:id`; or `:spawn-all` is not a vector; or the join-event
      slots are missing per the required-iff rules; or no `:machine-id`
      / `:definition`; or the `:join` value is outside the closed
      `:all` / `:any` enum; or an unknown bare key on the block (e.g. the
      removed `:cancel-on-decision?`).
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
      ;; No-silent-swallow on the block keys: reject any unknown bare key
      ;; (e.g. the removed `:cancel-on-decision?`) so a retired / misspelt
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
                        "vocabulary reads as a typo or a retired key (e.g. "
                        "the removed :cancel-on-decision?) and would be "
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
      ;; The join grammar is a closed two-member enum: `:all` (default)
      ;; and `:any`.
      ;; Quorum cases use the data-only `:after` + `:done-guard` idiom
      ;; (Spec 005 §Composition with hierarchy and `:after`); re-adding
      ;; `{:n}` later is a compatible widening. Any other `:join` value —
      ;; including a now-removed `{:n N}` / `{:fn ...}` — is rejected as an
      ;; unknown join spec.
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
    workaround (whose timer is bound to an arbitrary region's lifecycle)."
  [machine]
  (when (parallel/parallel? machine)
    (when-not (and (map? (:regions machine)) (seq (:regions machine)))
      (throw (validation-error
               :rf.error/machine-parallel-bad-shape
               ":type :parallel requires a non-empty :regions map")))
    ;; The parallel root's `:on-done` must not carry an in-machine `:target`
    ;; in ANY form (root-only parallel has no flat sibling to land on).
    ;; Normalise every value-form `:on-done` admits via
    ;; `grammar/candidate-maps`, the same grammar
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
            cands   (or (grammar/candidate-maps on-done) [])]
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
    ;; the shared `grammar/candidate-maps`; malformed values produce `[]`.
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
          candidates-of (fn [v] (or (grammar/candidate-maps v) []))]
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

;; ---- non-parallel root `:after` / `:timeout` -------------------------------
;;
;; Per Spec 005 §Root-level `:after` — the timer-driven ancestor fallback:
;; the feature is scoped to a `:type :parallel` machine root. Its runtime
;; support is likewise parallel-only — `transition/schedule-root-after-fx`
;; (the birth-time scheduler) is called ONLY from
;; `parallel/run-initial-cascade`'s parallel branch; a flat/compound
;; machine's birth (`parallel/bootstrap-step`) never calls it, and there is
;; no root resolver that would fire a flat root `:after` at the decl-path
;; `[]` empty-path node (`grammar/node-at` resolves an empty path to nil).
;; `timeout/validate-timeouts!` + `validate-after-delays!` both happily
;; accept a WELL-FORMED root `:timeout` / `:after` on a flat/compound
;; machine (they validate the pairing / duration / delay-key SHAPE, not
;; whether the runtime can ever schedule or resolve it), so — absent this
;; check — such a machine registers cleanly and its "whole-machine
;; deadline" silently never fires. Reject it loudly here instead, on the
;; DESUGARED machine (`validate-machine!` calls this after
;; `timeout/desugar-timeouts`), so a root `:timeout` — which lowers onto
;; `:after` — is caught via its lowered form in the SAME check as a
;; directly-authored root `:after`.
;;
;; A parallel machine's REGION-ROOT `:after` (rf2-x76af2.10) has the SAME
;; unscheduled shape: it sits on the region body itself (decl-path `[]` WITHIN
;; the region), not on an entered leaf. `bootstrap-step` schedules only the
;; region's entered initial LEAVES; `schedule-root-after-fx` schedules only the
;; MACHINE root's own `:after` — neither reaches the region container's own
;; `:after`, so it too registers-but-never-fires. A region body is structurally
;; a flat/compound mini-machine, so its root `:after` is the exact analog of a
;; flat machine-root `:after` and is rejected with the SAME category — keeping
;; the runtime honest (no accept-but-inert path) and consistent with the
;; machine-root rejection. (The machine's OWN parallel-root `:after` remains
;; the one supported, scheduled root-`:after` form.) Reject-vs-schedule is a
;; genuine design call; REJECT was chosen for consistency with the existing
;; machine-root rejection + fail-loud (a per-region root scheduler would be a
;; feature expansion), so a region-root deadline moves onto the region's
;; `:initial` state's own `:after` / `:timeout` instead.

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
  (if (parallel/parallel? machine)
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
                    ":on-timeout. Root-level :after scheduling + resolution "
                    "is supported ONLY for a :type :parallel machine root "
                    "(Per Spec 005 §Root-level :after). On a flat/compound "
                    "root the timer would register but NEVER schedule or "
                    "fire. Move the deadline onto the machine's :initial "
                    "state's own :after / :timeout instead.")
               {:after (:after machine)})))))

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

;; ---- `:on-spawn` keyword-ref resolution -----------------------------------
;;
;; Per Spec 005 §Declarative `:spawn` §`:on-spawn`: a KEYWORD `:on-spawn`
;; resolves through the machine's `:on-spawn-actions` map, falling back to
;; `:actions` — mirroring the runtime `transition/apply-on-spawn`'s
;; `(or (chase-ref (:on-spawn-actions machine) aref)
;;      (chase-ref (:actions machine) aref))`. Registration never validated
;; this: `apply-on-spawn` silently treats a nil resolution as "no callback"
;; (the same branch a genuinely-absent `:on-spawn` takes), so a dangling ref
;; — a typo, a retired action name, a broken multi-hop indirection, a cycle
;; — registers cleanly and the intended side effect just never runs, with
;; no signal anywhere. `validate-on-spawn-ref!` closes the gap, following the
;; FULL `ref-resolves?` chase (through EITHER registry, in order) so a
;; multi-hop or cyclic indirection is caught here too, exactly as the
;; `:guard` / `:action` ref checks already are.

(defn- validate-on-spawn-ref!
  "Validate one spawn-spec's `:on-spawn` keyword ref (if present) against
  `machine`'s `:on-spawn-actions` map, falling back to `:actions` — the
  SAME two-registry fallback `transition/apply-on-spawn` resolves through
  at runtime. `where` (`:spawn` / `:spawn-all-child`) names the declaring
  site for diagnostics. An inline fn `:on-spawn` needs no resolution;
  absent `:on-spawn` is fine (the spawn simply has no callback). Emits
  `:rf.error/machine-unresolved-on-spawn`."
  [machine state-key spawn-spec where]
  (when (map? spawn-spec)
    (let [aref (:on-spawn spawn-spec)]
      (when (and (keyword? aref)
                 (not (ref-resolves? (:on-spawn-actions machine) aref))
                 (not (ref-resolves? (:actions machine) aref)))
        (throw (validation-error
                 :rf.error/machine-unresolved-on-spawn
                 (str where " on state " state-key " references :on-spawn "
                      aref " which does not resolve — chased against the "
                      "machine's :on-spawn-actions map, then its :actions "
                      "map as a fallback (mirroring the runtime resolution "
                      "order), and neither terminates at a fn. Register the "
                      "callback under one of those maps, or fix the "
                      ":on-spawn ref. Known :on-spawn-actions: "
                      (pr-str (vec (keys (:on-spawn-actions machine))))
                      "; known :actions: "
                      (pr-str (vec (keys (:actions machine)))) ".")
                 {:state                  state-key
                  :where                  where
                  :on-spawn               aref
                  :known-on-spawn-actions (vec (keys (:on-spawn-actions machine)))
                  :known-actions          (vec (keys (:actions machine)))}))))))

(defn- validate-on-spawn-refs!
  "Validate EVERY `:on-spawn` keyword ref on `state-node` — the single
  `:spawn`'s `:on-spawn`, plus every `:spawn-all` child's `:on-spawn` — per
  `validate-on-spawn-ref!`. `:spawn` / `:spawn-all` are mutually exclusive
  (enforced by `validate-spawn-all!`), so at most one of the two doseqs
  below does real work per node."
  [machine state-key state-node]
  (validate-on-spawn-ref! machine state-key (:spawn state-node) :spawn)
  (doseq [child (get-in state-node [:spawn-all :children])]
    (validate-on-spawn-ref! machine state-key child :spawn-all-child)))

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
  "Normalise a state-node's `:always` slot to a vector of entry maps through
  the shared `grammar/candidate-maps`. Absent
  `:always` — the key missing, OR present with an explicit nil value — yields
  the empty vector (no ancestor-blocking use case for `:always`, unlike `:on`
  / `:after`'s nil-is-forbidden-transition form), so the nil is gated BEFORE
  the shared normaliser (which maps nil to `[{}]`). A malformed value degrades
  to `[]` (the runtime normaliser throws `:rf.error/machine-bad-always` at the
  first macrostep; this registration-side walker just needs SOME vector to
  iterate — it is not the throw's designated surface)."
  [state-node]
  (let [a (:always state-node)]
    (if (nil? a)
      []
      (or (grammar/candidate-maps a) []))))

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
  unresolved one would commit an invalid snapshot).

  Per Spec 005 §Transition resolution steps 6-7, a non-parallel
  (flat / compound) machine root's OWN `:on` is the ancestor-fallback
  transition slot `pick-transition` consults, at runtime, when no state-path
  node handles the event — stamped with decl-path `[]`, so a keyword
  `:target` resolves as a TOP-LEVEL sibling (`target-path`'s
  `(drop-last [])` → `[]`) exactly like `resolves-to-state?` with an empty
  `owning-path`. `walk-state-nodes-with-scope` only yields nodes INSIDE
  `:states` (region or flat), so the root's own `:on` was UNCHECKED — an
  invalid root `:on` target registered cleanly and committed an unresolved
  `:state` at the first dispatch that fell through to it instead of failing
  fast here. (A non-parallel root's `:after` cannot reach this point — it is
  rejected outright by `validate-non-parallel-root-after!`, called earlier —
  so only `:on` needs checking here.)"
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
        (check! :spawn/on-error oe))))
  (when-not (parallel/parallel? machine)
    (let [scope  (:states machine)
          check! (fn [v]
                   (doseq [{:keys [present? target]} (candidate-targets v)]
                     (when present?
                       (validate-target! scope [] :on :rf/root target))))]
      (doseq [[_event v] (:on machine)]
        (check! v)))))

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
  row.

  `:type :history` and `:type :choice` pseudo-states carry their OWN closed
  key-sets (`validate-history!` / `choice/validate-node-choice!`), so the node-key
  walk SKIPS them — their validators already reject foreign keys with the
  node-kind-specific error id."
  #{;; root-shape / pseudo-state
    :type :deep? :default-target :regions
    ;; parallel-root region declaration order — the explicit registration
    ;; contract (`parallel/normalise-region-order`); author-supplied OR derived
    ;; and stamped once at registration. Root-only, but harmless on the set,
    ;; exactly like `:regions`.
    :region-order
    ;; compound / data / declaration blocks (root-only, but harmless on the set)
    :initial :states :data :schemas :internal-events
    :guards :actions :on-spawn-actions
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
  "Keys legal ONLY on the machine ROOT (the registration-metadata that folds
  onto the machine body per `registration/reg-machine*`), beyond the universal
  `known-state-node-keys`. `:doc` is the registration doc string; `:sensitive` /
  `:large` are the machine-level data-classification declarations
  (projection-relative `:data` classification); `:schema` is the event-vector
  boundary schema for the dispatched OUTER vector; `:raise-depth-limit` /
  `:always-depth-limit` are the per-machine cycle-detection depth overrides
  (`transition/raise-depth-limit-default`). These are meaningless on a child node
  (the child-node walk uses the plain vocabulary)."
  #{:doc :sensitive :large :schema :raise-depth-limit :always-depth-limit})

(def ^:private retired-spawn-spec-keys
  "Retired spawn-spec keys that carry their OWN dedicated retired-key rejection
  (`validate-no-spawn-timeout-ms!` → `:rf.error/spawn-timeout-ms-removed`). They
  are excluded from the generic unknown-key detection so the SPECIFIC removal
  diagnostic wins (naming the replacement) instead of the generic
  `:rf.error/machine-unknown-spawn-key`."
  #{:timeout-ms})

(def ^:private known-spawn-spec-keys
  "The closed BARE key vocabulary a `:spawn` / `:spawn-all`-child spawn-spec may
  declare, projected from the Spec-Schemas `InvokeSpec` + `InvokeAllChildSpec`
  grammars (plus the `:spawn-all`-child-only `:id` join-address key). Any BARE
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
  #{:machine-id :definition :data :id-prefix :on-spawn :on-done :on-error
    :start :fixed-actor-id :system-id :timeout :on-timeout
    :id                              ;; :spawn-all child-only — the join-address key
    :source-coords :source-code})    ;; DEBUG-only macro-stamped coord slots

(defn- unknown-bare-keys
  "The BARE (non-namespaced) keys of `m` that are NOT in `known` — the
  no-silent-swallow discriminator. A namespaced key is the open extension
  carve-out and is never flagged; see `namespaced-key?` for why that test is
  spelt the way it is and why a non-`Named` key is flagged rather than carved
  out (rf2-dhl4d)."
  [m known]
  (->> (keys m)
       (remove namespaced-key?)
       (remove known)
       vec))

(defn- validate-node-keys!
  "Reject any unknown BARE key on a state node at registration with
  `:rf.error/machine-unknown-node-key`, naming the offending key(s) and the valid
  vocabulary. `:type :history` / `:type :choice` pseudo-states are SKIPPED — they
  carry their own closed key-sets validated elsewhere. Namespaced keys pass (the
  open extension carve-out). The MACHINE ROOT (`at-root?`) additionally accepts
  the root-only registration-metadata keys (`:doc` / `:sensitive` / `:large` /
  `:schema`) that fold onto the machine body — those are typos on a child node.
  Per Conventions §No silent swallow + §Reserved state-node keys."
  [state-key state-node at-root?]
  (when (and (map? state-node)
             (not (history-node? state-node))
             (not (choice/choice-node? state-node)))
    (let [known     (cond-> known-state-node-keys
                       at-root? (into known-machine-root-extra-keys))
          offending (unknown-bare-keys state-node known)]
      (when (seq offending)
        (throw (validation-error
                 :rf.error/machine-unknown-node-key
                 (str "state " (key-label state-key) " declares unknown bare key(s) "
                      (key-labels offending)
                      " — a bare key outside the reserved state-node vocabulary "
                      "reads as a typo (e.g. XState's :invoke for re-frame2's "
                      ":spawn, or :on-entry for :entry) and would be silently "
                      "ignored. Use :meta for tooling metadata, or a NAMESPACED "
                      "key (:my.app/note) for a user extension. Valid keys: "
                      (pr-str (vec (sort known))) ".")
                 {:state          state-key
                  :offending-keys offending
                  :valid-keys     known}))))))

(defn- validate-spawn-spec-keys!
  "Reject any unknown BARE key on a `:spawn` spec or a `:spawn-all` child spec at
  registration with `:rf.error/machine-unknown-spawn-key`. Namespaced keys pass
  (the runtime-stamped `:rf/parent-id` / `:rf/invoke-id` + user extensions). Per
  Conventions §Spawn-spec keys + §No silent swallow."
  [state-key state-node]
  (let [check! (fn [spec where]
                 (when (map? spec)
                   ;; The retired `:timeout-ms` slot has its OWN dedicated
                   ;; rejection (`:rf.error/spawn-timeout-ms-removed`, naming the
                   ;; replacement); exclude it from the generic unknown-key scan
                   ;; so that SPECIFIC diagnostic wins.
                   (let [known     (into known-spawn-spec-keys retired-spawn-spec-keys)
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
                                     (pr-str (vec (sort known-spawn-spec-keys)))
                                     ".")
                                {:state          state-key
                                 :where          where
                                 :offending-keys offending
                                 :valid-keys     known-spawn-spec-keys}))))))]
    (check! (:spawn state-node) :spawn)
    (doseq [child (get-in state-node [:spawn-all :children])]
      (check! child :spawn-all-child))))

(defn- validate-tags!
  "Reject a NON-SET `:tags` slot on a state node at registration with
  `:rf.error/machine-bad-tags` — mirroring `:rf.error/machine-bad-internal-events`
  (its sibling set-valued slot). Per Spec-Schemas `:rf/state-node` (`:tags` is
  strict `[:set :keyword]`) + the 2026-07-03 self-consistency review: the runtime
  used to silently COERCE a vector / single keyword to a set, in violation of
  naming rule 2 (\"never a silently-normalised alias\") and inconsistent with
  `:internal-events`, which HARD-REJECTS exactly that non-set shape. A set with a
  non-keyword member is likewise rejected. Absent `:tags` is fine (elided slot)."
  [state-key state-node]
  (when (contains? state-node :tags)
    (let [tags (:tags state-node)]
      (when-not (and (set? tags) (every? keyword? tags))
        (throw (validation-error
                 :rf.error/machine-bad-tags
                 (str ":tags on state " state-key " must be a SET of keywords "
                      "(#{:loading :busy}), got " (pr-str tags)
                      ". A vector / single keyword is NO LONGER coerced — the "
                      "slot is a strict set, mirroring :internal-events. Per "
                      "Spec 005 §State tags + Spec-Schemas :rf/state-node.")
                 {:state state-key
                  :tags  tags}))))))

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
  must declare EXACTLY ONE of `:machine-id` / `:definition` (XOR). Throws
  `:rf.error/machine-spawn-bad-shape` (single `:spawn`) /
  `:rf.error/machine-spawn-all-bad-shape` (child) on both-set or neither-set.

  Every `:spawn` / `:spawn-all` rejects the unsupported `:timeout-ms` slot;
  spawn-level `:timeout` / `:on-timeout` is supported.

  Every `:on` / `:always` / `:entry` / `:exit` slot's guard
  and action keyword refs must resolve against the machine's `:guards` /
  `:actions` maps. Throws `:rf.error/machine-unresolved-guard` /
  `:rf.error/machine-unresolved-action` on dangling refs.

  Per Spec 005 §Declarative `:spawn` §`:on-spawn`: every `:on-spawn`
  keyword ref (a single `:spawn`, or a `:spawn-all` child) must resolve
  against the machine's `:on-spawn-actions` map, falling back to
  `:actions` — the SAME two-registry order the runtime resolves through.
  Throws `:rf.error/machine-unresolved-on-spawn` on a dangling ref,
  mirroring `:rf.error/machine-unresolved-action`'s fail-fast contract.

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
  `:rf.warning/no-clock-configured` no-op.

  Per Conventions §No silent swallow + §Reserved state-node keys /
  §Spawn-spec keys: every state node (root + descendants + parallel-region
  roots) rejects an unknown BARE key with `:rf.error/machine-unknown-node-key`,
  and every `:spawn` / `:spawn-all`-child spawn-spec rejects an unknown BARE key
  with `:rf.error/machine-unknown-spawn-key` (namespaced keys pass — the open
  extension carve-out). A non-set `:tags` slot is rejected with
  `:rf.error/machine-bad-tags` (the silent vector/keyword→set coercion is
  removed), mirroring `:rf.error/machine-bad-internal-events`."
  [machine]
  ;; Validate the `:timeout` / `:on-timeout` grammar on the raw
  ;; spec FIRST, so diagnostics name the `:timeout` / `:on-timeout` keys the
  ;; author wrote (timeout-requires-on-timeout pairing, the integer-ms /
  ;; ISO-8601-only duration rule rejecting the "5s"/"10ms" shorthand, and
  ;; the :after-collision guard). Then DESUGAR the spec so every subsequent
  ;; structural validator (transition targets, final-state shape, after
  ;; delays) sees the lowered `:after` form — the `:on-timeout` transition
  ;; target flows through the same target-resolution check `:after` uses, a
  ;; `:final?` state carrying a `:timeout` is rejected as it would be for an
  ;; `:after`, and the desugared form is exactly what the runtime drives.
  (timeout/validate-timeouts! machine)
  ;; Validate the `:type :choice` / `:choice` grammar on the
  ;; RAW spec (before BOTH desugars) so diagnostics name the `:type :choice`
  ;; / `:choice` keys the author wrote AND a choice state that also declares
  ;; a reserved key (incl. `:timeout`) is caught with that key still present.
  ;; The path-aware walker yields the declaring node's absolute path so a
  ;; self-targeting candidate is resolved.
  (doseq [[path n] (walk-state-nodes-with-path machine)]
    (choice/validate-node-choice! path (peek path) n))
  ;; A parallel region ROOT (a region body) may itself be a `:type :choice`
  ;; node only in a degenerate sense; the walker above does not yield region
  ;; roots, so validate them here for completeness (rejected via the same
  ;; reserved-key / shape rules — a region root carries `:states`, a
  ;; reserved key, so a `:type :choice` region root fails loud).
  (when (parallel/parallel? machine)
    (doseq [[rn body] (:regions machine)]
      (choice/validate-node-choice! [rn] rn body)))
  ;; Validate the `:internal-events` declaration on the raw
  ;; spec: it must be a set of keywords, and reserved `:rf/*` lifecycle names
  ;; are forbidden. A declared internal event is expected to have an ordinary
  ;; `:on` handler; the visibility boundary rejects only external dispatch.
  ;; Neither named-intent desugar touches `:internal-events`,
  ;; so the raw spec is the right basis.
  (internal-events/validate-internal-events! machine)
  ;; No-silent-swallow on state-node / spawn-spec keys + the `:tags` shape, on
  ;; the RAW spec (before BOTH desugars) so diagnostics name the exact keys the
  ;; author wrote — a `:choice` / `:timeout` / `:on-timeout` key is still present
  ;; here and is a KNOWN member of the bare vocabulary, so it is not flagged; a
  ;; typo (XState's `:invoke` / `:on-entry`) IS. `:type :history` / `:type
  ;; :choice` pseudo-states are skipped (their own closed key-sets validate them).
  ;; Per Conventions §No silent swallow + §Reserved state-node keys /
  ;; §Spawn-spec keys.
  ;; The machine ROOT is itself a state-node (it carries `:initial` / `:states`
  ;; or `:regions`, plus root-only `:guards` / `:actions` / `:data` / `:schemas`
  ;; — all KNOWN bare keys), and `walk-state-nodes` yields only the nodes UNDER
  ;; `:states`, so validate the root explicitly (a typo'd top-level key —
  ;; `:innitial`, `:gaurds` — must not slip through).
  (validate-node-keys! :rf/root machine true)
  (validate-tags! :rf/root machine)
  (doseq [[s n] (walk-state-nodes machine)]
    (validate-node-keys! s n false)
    (validate-spawn-spec-keys! s n)
    (validate-tags! s n))
  ;; A parallel region ROOT (a region body) is a state-node the plain
  ;; `walk-state-nodes` does NOT yield, so run the key / tags checks on each
  ;; region body too (a typo'd bare key on a region root must not slip through).
  ;; A region body is NOT the machine root — the root-only registration-metadata
  ;; keys (`:doc` / `:sensitive` / …) live on the machine root, not per region.
  (when (parallel/parallel? machine)
    (doseq [[rn body] (:regions machine)]
      (validate-node-keys! rn body false)
      (validate-spawn-spec-keys! rn body)
      (validate-tags! rn body)))
  ;; DESUGAR both named-intent grammars onto their underlying mechanisms
  ;; (`:timeout` → `:after`, `:choice` → `:always`) so every subsequent
  ;; structural validator (transition targets, self-loop, after delays) and
  ;; the runtime see the lowered form.
  (let [machine (choice/desugar-choices (timeout/desugar-timeouts machine))]
  (validate-history! machine)
  (validate-parallel! machine)
  ;; A non-parallel root's `:after` (hand-authored or lowered
  ;; from a root `:timeout` / `:on-timeout`) has no runtime scheduling /
  ;; resolution path; reject it loudly rather than silently registering a
  ;; whole-machine deadline that never fires. Runs on the DESUGARED machine
  ;; so a root `:timeout` is caught via its lowered `:after` form too.
  (validate-non-parallel-root-after! machine)
  ;; The machine-level `:schemas` map has a closed sub-key set; an
  ;; unknown sub-key (incl. `:input`) or a non-map `:schemas` fails loud.
  (validate-schemas! machine)
  (doseq [[s n] (walk-state-nodes machine)]
    (validate-spawn! s n)
    (validate-spawn-all! s n)
    (validate-no-spawn-timeout-ms! s n)
    (validate-final-state! s n)
    (validate-spawn-on-error! s n)
    (validate-compound-initial! s n)
    ;; Every `:on-spawn` keyword ref (single `:spawn` or a
    ;; `:spawn-all` child) must resolve against `:on-spawn-actions`,
    ;; falling back to `:actions`, at registration.
    (validate-on-spawn-refs! machine s n))
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
      (check-transition! (:on-done machine) :rf/root)))))
