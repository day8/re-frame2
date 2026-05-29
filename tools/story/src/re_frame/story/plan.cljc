(ns re-frame.story.plan
  "Variant-plan compiler and `explain` base (Lane B foundation).

  Per `tools/story/spec/017-Testing-Story.md` §Four-bucket authoring
  model + §Variant plan + §Total resolution order, every registered
  variant and inline plan MUST be normalized before execution. This
  namespace is the **author-surface foundation**: it turns a raw variant
  body (or inline map) into the normalized `:world` / `:script` /
  `:expect` shape, resolving the `:extends` parent chain, lowering author
  ergonomics, resolving `:args` + `[:arg key]` placeholders, computing the
  initial required-runner capability set, and producing `explain` data
  during compilation.

  ## What this layer DOES (rf2-5x1wt.10)

  - Read a registered variant body (or accept an inline map).
  - Resolve the `:extends` parent chain root-to-child, bounded with cycle
    detection (§Total resolution order step 1; §`:extends`).
  - Lower author ergonomics into the four-bucket shape: `:events`/`:setup`
    → `[:world :setup]`; `:play-script`/`:plays`/`:script` → `:script`;
    `:checks`/`:assertions` → `:expect`.
  - Resolve `:args` through the same precedence chain as
    `re-frame.story.args` (deep-merge, later wins) and substitute
    `[:arg key]` placeholders in setup/script/sub-overrides; a missing
    arg FAILS plan construction.
  - Preserve platforms / tags / workshop context under `:world`.
  - Compute the **initial** `:required-runner` capability set from the
    script steps and the declared assertions (a coarse first cut; the
    full per-assertion capability registry lands with rf2 runner-
    requirements work).
  - Attach `:source-chain` and an `:explain` map.

  ## What this layer DEFERS

  Strict `:compose` fragment/check composition + conflict resolution
  (§Merge rules / §Conflict resolution), the runner itself, evidence
  projection, view-arg-schema validation, `:network` lowering, and the
  capability-token registry are **later beads**. This compiler emits the
  scaffolding (e.g. an empty `[:world :network]` passthrough, a coarse
  `:required-runner`) so those beads slot in without reshaping the plan.

  ## Purity / elision

  Every fn here is pure data → data, so the compiler is JVM-runnable and
  the test suite needs no host. The default body `lookup` reads the Story
  side-table (`re-frame.story.registrar`), which under the §6 elision
  contract is empty in a production bundle; callers thread an explicit
  `:lookup` (a `{variant-id → raw-body}` map or a 1-arg fn) for pure
  tests."
  (:require [re-frame.story.args      :as args]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.play.runner :as runner]))

;; ============================================================================
;; Errors
;; ============================================================================

(defn- fail!
  "Throw a structured plan-construction error. Mirrors the
  `:rf.error/story-*` family the registrar / extends layers use so tools
  surface plan failures the same way registration failures surface."
  [id reason data]
  (throw (ex-info (str id)
                  (merge {:rf.error/id id
                          :where       'rf.story/variant-plan
                          :recovery    :fix-registration
                          :reason      reason}
                         data))))

;; ============================================================================
;; Parent-chain resolution
;; ============================================================================

(def ^:dynamic *max-extends-depth*
  "Hard cap on the `:extends` chain length, mirroring
  `re-frame.story.extends/*max-extends-depth*`. A body that hits this
  limit is treated as a cycle."
  32)

(defn resolve-source-chain
  "Walk the `:extends` chain from `body` and return the vector of
  `{:variant/id :body}` layers in **root-first** order — the deepest
  ancestor first, the supplied `body` last.

  `lookup` is a fn `(variant-id) → raw-body-or-nil` that returns the
  *raw* (un-merged) body for a parent variant. A body that does not carry
  `:extends` yields a single-entry chain `[{:variant/id id :body body}]`.

  FAILS with `:rf.error/story-extends-unknown` when a named parent is not
  found, and `:rf.error/story-extends-cycle` when a variant id reappears
  on the chain (or the depth cap is exceeded).

  Self-resolving: the supplied `id`/`body` is the child; only `:extends`
  references are looked up. Per §Total resolution order step 1."
  [id body lookup]
  (loop [acc     (list {:variant/id id :body body})
         current body
         visited  (if id #{id} #{})
         depth    0]
    (let [parent-id (:extends current)]
      (cond
        (nil? parent-id)
        (vec acc)                          ; acc is built child-first via conj-to-front

        (contains? visited parent-id)
        (fail! :rf.error/story-extends-cycle
               (str "re-frame2-story: :extends cycle through " parent-id)
               {:chain (conj (vec visited) parent-id) :id parent-id})

        (>= depth *max-extends-depth*)
        (fail! :rf.error/story-extends-chain-too-long
               (str "re-frame2-story: :extends chain exceeds "
                    *max-extends-depth* " levels at " parent-id)
               {:chain (conj (vec visited) parent-id) :id parent-id})

        :else
        (if-let [parent (lookup parent-id)]
          (recur (conj acc {:variant/id parent-id :body parent})
                 parent
                 (conj visited parent-id)
                 (inc depth))
          (fail! :rf.error/story-extends-unknown
                 (str "re-frame2-story: :extends references unregistered "
                      "variant " parent-id)
                 {:parent parent-id :id id}))))))

;; ============================================================================
;; Field merge (foundation: context-flows-down / verdict-is-local)
;; ============================================================================
;;
;; rf2-5x1wt.10 implements the *parent-chain* slice of §Merge rules — the
;; foundation that .11/.12 build the strict `:compose` conflict machinery
;; on. The principle from §`:extends`: context flows down, verdict is
;; local.
;;
;;   - inherited through :extends — world context (setup, args, frame,
;;     platforms, tags, workshop slots) AND checks (the inheritable
;;     expectation form);
;;   - NOT inherited — ordinary terminal `:assertions` and `:script`.
;;
;; Setup APPENDS root→child (preserving order); script and assertions are
;; taken from the child only.

(def ^:private setup-keys
  "Source keys that lower into `[:world :setup]`, in priority order
  (`:setup` is the target spelling; `:events` is the shipping spelling)."
  [:setup :events])

(def ^:private context-keys
  "World/context keys inherited through `:extends` (deep-merge for maps,
  child-wins for scalars; see `merge-context`)."
  [:args :argtypes :sub-overrides :network :fx-overrides :interceptor-overrides
   :decorators :loaders :loaders-teardown :loaders-complete-when
   :modes :substrates :platforms :viewport :background :xray
   :dispatch-console? :component :doc :args->events])

(defn- pick-setup
  "Return the raw setup vector for a body — the first of `setup-keys`
  present. Both spellings lower to the same `[:world :setup]` slot."
  [body]
  (some (fn [k] (when (contains? body k) (get body k))) setup-keys))

(defn- merge-context
  "Deep-merge one context value root→child. Maps recurse (per
  `args/deep-merge`); everything else is child-wins replacement."
  [parent child]
  (if (and (map? parent) (map? child))
    (args/deep-merge parent child)
    (if (some? child) child parent)))

(defn- merge-tags
  "Tags are additive through `:extends` (§Merge rules — append/union)."
  [parent child]
  (into (set parent) (set child)))

;; ============================================================================
;; Script normalization
;; ============================================================================
;;
;; `:play-script` (single) / `:plays` (named) / `:script` (target) all
;; lower to `:script`. We reuse the shipping `runner/variant-body->plays`
;; so bare event-vector shorthand and the `:dispatch`/`:dispatch-sync`/
;; `:wait`/`:assert-*` tag grammar coerce exactly as the runtime sees
;; them. Per spec §Public vocabulary + §Setup and script.
;;
;; The normalized `:script` is the FIRST (auto-run / primary) play's
;; coerced step vector; the full named-play set is preserved under
;; `[:world :scripts]` so `:plays` is not dropped (§Public vocabulary —
;; "named scripts in the normalized plan").

(defn- normalize-scripts
  "Return `{:script [step ...] :scripts [{:name :script :auto-run?} ...]}`
  for a merged body. Accepts the target `:script` spelling AND the
  shipping `:play-script` / `:plays` spellings, lowering them through the
  runner's canonical coercion. The primary `:script` is the first play."
  [body]
  (let [plays (cond
                (contains? body :script)
                ;; Target spelling: a bare step vector, coerced the same
                ;; way the runner coerces a `:play-script` body's `:script`.
                [{:script (runner/coerce-script (:script body)) :auto-run? true}]

                :else
                (runner/variant-body->plays body))
        plays (vec plays)]
    {:script  (-> plays first :script (or []))
     :scripts plays}))

;; ============================================================================
;; Arg placeholder substitution
;; ============================================================================

(defn arg-placeholder?
  "True iff `x` is an `[:arg key]` placeholder (per §Args — data
  placeholders)."
  [x]
  (and (vector? x)
       (= 2 (count x))
       (= :arg (first x))))

(defn substitute-args
  "Recursively replace every `[:arg key]` placeholder in `form` with the
  resolved value from `arg-map`. FAILS with `:rf.error/story-missing-arg`
  when a placeholder references an absent key (§Args — 'plan construction
  MUST fail'). Records each substitution into the `subs!` atom as
  `{:key key :value v}` so `explain` can show them.

  Walks vectors, maps, sets, and lists; scalars pass through. A resolved
  value is itself walked so a placeholder may resolve to a structure
  containing further data (placeholders inside resolved values are NOT
  re-expanded — resolution is one level)."
  [form arg-map subs!]
  (cond
    (arg-placeholder? form)
    (let [k (second form)]
      (if (contains? arg-map k)
        (let [v (get arg-map k)]
          (swap! subs! conj {:key k :value v})
          v)
        (fail! :rf.error/story-missing-arg
               (str "re-frame2-story: [:arg " k "] references an arg that "
                    "is not declared on the variant or its parents")
               {:arg k :available (set (keys arg-map))})))

    (map? form)
    (reduce-kv (fn [m k v]
                 (assoc m
                        (substitute-args k arg-map subs!)
                        (substitute-args v arg-map subs!)))
               (empty form) form)

    (vector? form)
    (mapv #(substitute-args % arg-map subs!) form)

    (set? form)
    (into (empty form) (map #(substitute-args % arg-map subs!)) form)

    (seq? form)
    (apply list (map #(substitute-args % arg-map subs!) form))

    :else form))

;; ============================================================================
;; Expect normalization
;; ============================================================================

(defn- normalize-expect
  "Lower `:checks` + `:assertions` into the `:expect` bucket. Checks are
  the inheritable expectation form (already merged into `merged` by the
  context pass); ordinary assertions are own-only (taken from the child
  body only — handled by the caller passing the child's assertions)."
  [checks assertions]
  {:checks     (vec (or checks []))
   :assertions (vec (or assertions []))})

;; ============================================================================
;; Required-runner inference (coarse foundation)
;; ============================================================================
;;
;; The full capability-token registry is a later bead. This foundation
;; emits the *initial* required-runner set from a small static map of the
;; step/assertion ids the shipping grammar already carries. Per §Runner
;; model — capability is a SET of tokens, not a tier scalar.

(def ^:private step-capabilities
  "Capability tokens a script step requires, keyed by step tag."
  {:dispatch       #{:app-db}
   :dispatch-sync  #{:app-db}
   :wait           #{}
   :wait-until     #{}
   :assert-db      #{:app-db}
   :assert         #{:app-db}
   :assert-dom     #{:dom}
   :click          #{:dom}
   :type           #{:dom}
   :focus          #{:dom}})

(def ^:private assertion-capabilities
  "Capability tokens a terminal assertion id requires. Coarse first cut;
  the per-assertion registry (rf2 runner-requirements) supersedes this."
  {:rf.assert/path-equals    #{:app-db}
   :rf.assert/path-matches   #{:app-db}
   :rf.assert/sub-equals     #{:app-db}
   :rf.assert/dispatched?    #{:app-db}
   :rf.assert/state-is       #{:app-db}
   :rf.assert/no-warnings    #{:app-db}
   :rf.assert/effect-emitted #{:app-db}
   :rf.assert/schema-error   #{:app-db}
   :rf.assert/dom-visible    #{:dom}
   :rf.assert/dom-hidden     #{:dom}
   :rf.assert/dom-text       #{:dom}
   :rf.assert/visual-snapshot #{:pixels}
   :rf.assert/a11y           #{:a11y-engine}})

(defn- step-tokens [step]
  (get step-capabilities (when (vector? step) (first step)) #{}))

(defn- assertion-tokens [assertion]
  (get assertion-capabilities (when (vector? assertion) (first assertion)) #{}))

(defn- compute-required-runner
  "Union the capability tokens demanded by every setup step, script step,
  and terminal assertion. `:headless` work needs no token (the empty set
  resolves to the default `:headless` runner)."
  [setup script assertions]
  (reduce into #{}
          (concat (map step-tokens setup)
                  (map step-tokens script)
                  (map assertion-tokens assertions))))

;; ============================================================================
;; Compiler
;; ============================================================================

(defn- default-lookup
  "Default raw-body lookup — reads the Story side-table. Production
  bundles elide the side-table, so the lookup returns nil there; pure
  tests thread an explicit `:lookup`."
  [variant-id]
  (registrar/handler-meta :variant variant-id))

(defn- coerce-lookup
  "Accept either a 1-arg fn or a `{variant-id → body}` map as `:lookup`."
  [lookup]
  (cond
    (nil? lookup) default-lookup
    (map? lookup) #(get lookup %)
    (fn? lookup)  lookup
    :else         (fail! :rf.error/story-bad-lookup
                         "re-frame2-story: :lookup must be a fn or a map"
                         {:lookup lookup})))

(defn compile-body
  "Compile a raw variant `body` (the child) registered under `id` into a
  normalized plan. `lookup` is a 1-arg fn returning raw parent bodies.

  This is the pure core; `variant-plan` is the public front door that
  resolves a registered/keyword/map target onto this fn."
  [id body lookup]
  (let [chain        (resolve-source-chain id body lookup)
        bodies       (map :body chain)         ; root-first
        child        (:body (last chain))
        ;; ---- context merge (root→child) ----
        ctx          (reduce
                       (fn [acc layer]
                         (reduce (fn [m k]
                                   (if (contains? layer k)
                                     (update m k merge-context (get layer k))
                                     m))
                                 acc context-keys))
                       {}
                       bodies)
        ;; checks inherit; build the inherited+own check list root→child
        checks       (reduce (fn [acc layer]
                               (into acc (:checks layer)))
                             []
                             bodies)
        ;; setup APPENDS root→child (§Merge rules)
        setup-raw    (vec (mapcat (fn [layer] (or (pick-setup layer) [])) bodies))
        ;; script + terminal assertions are CHILD-ONLY (verdict is local)
        {:keys [script scripts]} (normalize-scripts child)
        assertions   (vec (:assertions child))
        ;; ---- args ----
        arg-map      (reduce (fn [m layer] (args/deep-merge m (:args layer)))
                             {} bodies)
        argtypes     (reduce (fn [m layer] (args/deep-merge m (:argtypes layer)))
                             {} bodies)
        ;; ---- arg substitution ----
        subs!        (atom [])
        setup        (substitute-args setup-raw arg-map subs!)
        script*      (substitute-args script arg-map subs!)
        sub-overrides (substitute-args (:sub-overrides ctx) arg-map subs!)
        ;; ---- runner requirement ----
        required     (compute-required-runner setup script* assertions)
        ;; ---- source coords ----
        source       (:source child)
        platforms    (or (:platforms ctx) #{:client})
        world        (cond-> {:setup     setup
                              :args      arg-map
                              :argtypes  argtypes
                              :scripts   scripts
                              :platforms platforms}
                       (some? sub-overrides) (assoc-in [:render :sub-overrides] sub-overrides)
                       (contains? ctx :network)     (assoc :network (:network ctx))
                       (contains? ctx :fx-overrides) (assoc-in [:frame :fx-overrides] (:fx-overrides ctx))
                       (contains? ctx :interceptor-overrides) (assoc-in [:frame :interceptor-overrides] (:interceptor-overrides ctx))
                       (contains? ctx :loaders)     (assoc :loaders (:loaders ctx))
                       (contains? ctx :loaders-teardown) (assoc :loaders-teardown (:loaders-teardown ctx))
                       (contains? ctx :decorators)  (assoc :decorators (:decorators ctx))
                       (contains? ctx :modes)       (assoc :modes (:modes ctx))
                       (contains? ctx :substrates)  (assoc :substrates (:substrates ctx))
                       (contains? ctx :viewport)    (assoc :viewport (:viewport ctx))
                       (contains? ctx :background)  (assoc :background (:background ctx))
                       (contains? ctx :xray)        (assoc :xray (:xray ctx))
                       (contains? ctx :component)   (assoc :component (:component ctx)))
        explain      {:source-chain (mapv :variant/id chain)
                      :parent-chain (mapv :variant/id (butlast chain))
                      :compose      []          ; foundation: no fragments/checks compose yet
                      :merge        {:setup      :append-root-to-child
                                     :args       :deep-merge-root-to-child
                                     :checks     :inherit-root-to-child
                                     :assertions :child-only
                                     :script     :child-only}
                      :args         arg-map
                      :substitutions @subs!
                      :setup-order  setup
                      :script-order script*
                      :checks       checks
                      :assertions   assertions
                      :required-runner required
                      :platforms    platforms
                      :tags         (reduce (fn [acc layer] (merge-tags acc (:tags layer)))
                                            #{} bodies)
                      :source       source}]
    (cond-> {:variant/id      id
             :source-chain    (mapv :variant/id chain)
             :world           world
             :script          script*
             :expect          (normalize-expect checks assertions)
             :required-runner required
             :tags            (reduce (fn [acc layer] (merge-tags acc (:tags layer)))
                                      #{} bodies)
             :explain         explain}
      source (assoc :source source))))

(defn variant-plan
  "Compile `target` into a normalized variant plan (§Variant plan).

  `target` is either:

  - a **keyword** — a registered variant id resolved through the body
    `lookup` (default: the Story side-table);
  - a **map** — an inline plan body (compiled the same way, with an
    optional `:variant/id`).

  `opts`:

  - `:lookup` — a 1-arg fn `(variant-id) → raw-body` OR a
    `{variant-id → raw-body}` map, used to resolve `:extends` parents
    (and the keyword target itself). Defaults to the side-table.

  Returns the normalized plan map: `:variant/id`, `:source-chain`,
  `:world`, `:script`, `:expect`, `:required-runner`, `:tags`, `:explain`
  (and `:source` when coords are present).

  FAILS with a structured `:rf.error/story-*` ex-info on: an unregistered
  keyword target, an unregistered `:extends` parent, an `:extends` cycle,
  or a missing `[:arg key]`."
  ([target] (variant-plan target nil))
  ([target {:keys [lookup] :as _opts}]
   (let [lookup-fn (coerce-lookup lookup)]
     (cond
       (keyword? target)
       (if-let [body (lookup-fn target)]
         (compile-body target body lookup-fn)
         (fail! :rf.error/story-unknown-variant
                (str "re-frame2-story: no registered variant " target)
                {:variant/id target}))

       (map? target)
       (compile-body (:variant/id target) (dissoc target :variant/id) lookup-fn)

       :else
       (fail! :rf.error/story-bad-target
              "re-frame2-story: variant-plan target must be a keyword id or a map"
              {:target target})))))

(defn explain
  "Return the `:explain` map for `target` (§Explain API). Convenience over
  `(:explain (variant-plan target opts))`. Shows the source chain, parent
  chain, field-level merge decisions, args + substitutions, final
  setup/script order, checks/assertions, required runner, platforms, and
  tags."
  ([target] (explain target nil))
  ([target opts] (:explain (variant-plan target opts))))
