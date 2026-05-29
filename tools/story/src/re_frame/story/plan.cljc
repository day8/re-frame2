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

  ## View arg schemas (rf2-5x1wt.12)

  When a variant's `:component` resolves to a registered view that
  carries a props schema on its view metadata, the compiler copies that
  schema into `[:world :view-args-schema]`, records the resolved
  `[:world :effective-args]` (the post-substitution args that feed the
  view — at plan time, before control-panel overrides), and validates
  the effective args against the schema. A required view input that is
  missing, or a malformed value, FAILS plan construction with
  `:rf.error/story-view-args-invalid` carrying the failing arg key, the
  Malli schema path, and the source variant. The schema, effective args,
  and any validation outcome surface in `:explain` (and downstream docs).

  This is the **explicit view-input** contract only. It is distinct from
  subscription-output schemas, which validate values supplied by
  subscriptions / `:sub-overrides` (§View-state subscription overrides);
  the two MUST NOT be conflated.

  ## Network world slot (rf2-5x1wt.14)

  When a variant authors `:network` (a `{[method url] {:reply …}}` route
  map), the compiler keeps the per-route reply data at `[:world :network]`
  (the source of truth that feeds `:plan-hash` through the `:world` slot
  and `explain`) and **lowers** it to the existing managed-request stub
  machinery — the variant frame overrides `:rf.http/managed` with the stub
  fx `re-frame.http-test-support/install-managed-request-stubs!` registers
  (folded into `[:world :frame :fx-overrides]`). It reuses that helper
  rather than inventing a new HTTP mock; the actual `install-…!` call is a
  RUNTIME concern (run when the variant frame is created). `:network` and
  an explicit author `:fx-overrides` on `:rf.http/managed` are a hard
  conflict (`:rf.error/story-network-fx-conflict`) — `:network` is the
  dedicated affordance for that fx. See §Network world slot below.

  ## What this layer DEFERS

  Strict `:compose` fragment/check composition + conflict resolution
  (§Merge rules / §Conflict resolution), the runner itself, evidence
  projection, and the capability-token registry are **later beads**. This
  compiler emits the scaffolding (a coarse `:required-runner`, the lowered
  `:network` fx-override the deferred runner installs) so those beads slot
  in without reshaping the plan. Wiring the per-route reply data into the
  run artifact awaits the run-artifact ns (rf2-5x1wt.7).

  ## Purity / elision

  Every fn here is pure data → data, so the compiler is JVM-runnable and
  the test suite needs no host. The default body `lookup` reads the Story
  side-table (`re-frame.story.registrar`), which under the §6 elision
  contract is empty in a production bundle; callers thread an explicit
  `:lookup` (a `{variant-id → raw-body}` map or a 1-arg fn) for pure
  tests."
  (:require [re-frame.story.args         :as args]
            [re-frame.story.registrar    :as registrar]
            [re-frame.story.malli-schema :as msu]
            [re-frame.story.play.runner  :as runner]
            [re-frame.registrar          :as framework-registrar]))

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
;; View arg schemas (rf2-5x1wt.12)
;; ============================================================================
;;
;; Per spec §View arg schemas: a registered view MAY expose a schema for
;; its explicit args/props on its view metadata. Story copies that schema
;; into `[:world :view-args-schema]`, validates `[:world :effective-args]`
;; against it before render, and derives controls from it where explicit
;; argtypes are absent (the controls-panel derivation in
;; `re-frame.story.ui.controls` reads the same schema).
;;
;; **Metadata key.** The spec names `:rf/props` for the props schema and
;; `:rf/args` for macro-captured argument *symbols* (introspection, NOT a
;; validation schema — so it is never consumed here). The live framework
;; view-metadata contract (Spec 010) carries the boundary schema on
;; `:spec`, with `:schema` as the Story-only alias the controls /
;; schema-validation panels already read. We therefore resolve, first
;; match wins: `:rf/props` (the spec-named props key, when a port adopts
;; it) → `:spec` (the live Spec 010 slot) → `:schema` (the alias). This
;; consumes the key the framework already exposes rather than inventing a
;; parallel one (§View arg schemas — "M0 MUST confirm the exact key").
;;
;; **Boundary.** This validates EXPLICIT view inputs only. Values
;; returned from subscriptions are validated by subscription-output
;; schemas (§View-state subscription overrides); the two are different
;; contracts and are not conflated here.

(def ^:private view-args-schema-keys
  "View-metadata keys carrying the explicit-input (props) schema, in
  resolution order (first present wins). See the section comment."
  [:rf/props :spec :schema])

(defn view-args-schema
  "Return the explicit view-args/props schema from a `view-meta` map (the
  `:view` registrar slot), or nil when none is present. Picks the first
  of `view-args-schema-keys` that is set."
  [view-meta]
  (when (map? view-meta)
    (some (fn [k] (let [s (get view-meta k)] (when (some? s) s)))
          view-args-schema-keys)))

(defn- map-entry-optional?
  "True iff a Malli `[:map …]` entry `[k props? child]` is marked
  `{:optional true}` in its per-entry properties map. A non-optional
  entry is a REQUIRED view input."
  [entry]
  (let [props (second entry)]
    (boolean (and (msu/properties? props) (:optional props)))))

(defn validate-effective-args
  "Validate `effective-args` against a view-args `schema` (the value
  returned by `view-args-schema`). Returns a result map:

      {:status :ok | :invalid
       :schema schema
       :missing  [{:key k :schema entry-schema :path [...]} ...]
       :malformed [{:key k :value v :schema entry-schema
                    :path [...] :explain explanation} ...]}

  Two tiers of checking, both pure:

  - **Required-key presence (host-free floor).** For a top-level
    `[:map …]` schema, every entry NOT marked `{:optional true}` whose
    key is absent from `effective-args` is a `:missing` violation. This
    needs no Malli runtime, so it runs under `clojure -M:test`.

  - **Malformed-value (validator-driven).** When `validator-fns`
    (`{:validate (fn [schema value] truthy?) :explain (fn [schema value]
    explanation)}`) is supplied, each present entry's value is validated
    against its entry schema; a failure is a `:malformed` violation
    carrying the validator's explanation. With no validator (the
    JVM-test default) malformed-value checking soft-passes, matching the
    `re-frame.story.ui.schema-validation/args-violations` convention.

  Each violation carries the Malli `:path` (`[k]` for a top-level map
  entry) so callers can report 'where' in the schema the failure sits.
  A non-`:map` top-level schema validates the whole args map as one
  value (validator-driven only)."
  ([schema effective-args] (validate-effective-args schema effective-args nil))
  ([schema effective-args validator-fns]
   (let [validate (:validate validator-fns)
         explain  (:explain  validator-fns)
         args     (or effective-args {})]
     (cond
       (nil? schema)
       {:status :ok :schema nil :missing [] :malformed []}

       (and (vector? schema) (= :map (msu/schema-op schema)))
       (let [entries   (msu/schema-children schema)
             missing   (into []
                              (keep (fn [entry]
                                      (let [k (msu/map-entry-key entry)]
                                        (when (and (not (map-entry-optional? entry))
                                                   (not (contains? args k)))
                                          {:key    k
                                           :schema (msu/map-entry-schema entry)
                                           :path   [k]}))))
                              entries)
             malformed (when validate
                         (into []
                               (keep (fn [entry]
                                       (let [k  (msu/map-entry-key entry)
                                             cs (msu/map-entry-schema entry)
                                             v  (get args k)]
                                         (when (and (contains? args k)
                                                    (not (validate cs v)))
                                           {:key     k
                                            :value   v
                                            :schema  cs
                                            :path    [k]
                                            :explain (when explain (explain cs v))}))))
                               entries))
             malformed (vec malformed)]
         {:status    (if (and (empty? missing) (empty? malformed)) :ok :invalid)
          :schema    schema
          :missing   missing
          :malformed malformed})

       ;; Top-level non-:map schema — validate the whole args map.
       (and validate (not (validate schema args)))
       {:status    :invalid
        :schema    schema
        :missing   []
        :malformed [{:key     ::root
                     :value   args
                     :schema  schema
                     :path    []
                     :explain (when explain (explain schema args))}]}

       :else
       {:status :ok :schema schema :missing [] :malformed []}))))

;; ============================================================================
;; Network world slot (rf2-5x1wt.14)
;; ============================================================================
;;
;; Per spec §The network surface + §Network stubs: managed HTTP stubbing is
;; first-class world input. A variant authors
;;
;;   {:network {[:get  "/api/cart"]      {:reply {:ok {:items []}}}
;;              [:post "/api/checkout"]  {:reply {:failure {:kind :rf.http/http-4xx
;;                                                          :status 409}}}}}
;;
;; The compiler keeps the per-route reply data at `[:world :network]` (the
;; source of truth that feeds `:plan-hash` via `plan-hash-input-keys`'s
;; `:world` slot, and `explain`, and — once rf2-5x1wt.7's run-artifact ns
;; lands — the run artifact) AND **lowers** it to the existing managed-
;; request stub machinery: the variant frame overrides `:rf.http/managed`
;; with the stub fx that `re-frame.http-test-support/install-managed-
;; request-stubs!` registers. We do NOT invent a new HTTP mock — the
;; lowering names the existing seam so the (deferred) runner installs the
;; route map and points the frame's `:fx-overrides` at the stub fx.
;;
;; `:network` is NOT a replacement for generic `:fx-overrides`; it is the
;; higher-level affordance for `:rf.http/managed` specifically. Generic
;; `:fx-overrides` still serve every non-HTTP effect and the unusual cases
;; (§Network stubs — "generic :fx-overrides still exists").

(def managed-fx-id
  "The production managed-HTTP fx id `:network` lowers an override of —
  `re-frame.http-managed`'s `:rf.http/managed` (Spec 014)."
  :rf.http/managed)

(def managed-stub-fx-id
  "The fx id `re-frame.http-test-support/install-managed-request-stubs!`
  registers and returns — the per-call stub target a `:network` variant's
  frame redirects `:rf.http/managed` to. Mirrors that helper's private
  `stub-fx-id` constant (Spec 014 §Testing); naming it here lets the plan
  declare the lowering without depending on the http artefact at compile
  time (the actual `install-…!` call is a RUNTIME concern, run when the
  variant frame is created — see the runtime-migration bead)."
  :rf.http/managed-test-stub)

(defn lower-network
  "Lower a resolved `:network` route map into the managed-stub fx override.
  Pure data → data. Returns
  `{:network <route-map> :fx-overrides {:rf.http/managed
  :rf.http/managed-test-stub}}` when `network` is non-empty, or `nil`
  when there are no routes.

  `network` is the per-route reply map already merged + arg-substituted
  (`{[method url] {:reply …}}`). It is preserved verbatim as the source of
  truth; the derived `:fx-overrides` entry is the lowering the runner
  consumes to point the frame's `:rf.http/managed` at the stub fx."
  [network]
  (when (seq network)
    {:network      network
     :fx-overrides {managed-fx-id managed-stub-fx-id}}))

(defn- check-network-fx-conflict!
  "FAIL plan construction when `:network` and an explicit author
  `:fx-overrides` BOTH target `:rf.http/managed` (§Network stubs — the
  conflict must resolve predictably). `:network` is the dedicated managed-
  HTTP affordance; an explicit `:fx-overrides {:rf.http/managed …}` is the
  coarse escape hatch. Letting one silently win would flatten exactly the
  route-level intent `:network` exists to preserve, so the two-owner case
  is a hard error the author resolves by dropping one surface.

  `author-fx-overrides` is the variant-authored `:fx-overrides` map (the
  context value, before lowering); `network` is the resolved route map.
  No-op when either is absent or `:network` is empty."
  [id author-fx-overrides network]
  (when (and (seq network)
             (map? author-fx-overrides)
             (contains? author-fx-overrides managed-fx-id))
    (fail! :rf.error/story-network-fx-conflict
           (str "re-frame2-story: variant " id " sets BOTH :network and an "
                "explicit :fx-overrides on " managed-fx-id " — they conflict. "
                ":network is the dedicated managed-HTTP affordance; drop the "
                "explicit :fx-overrides entry (or drop :network and stub "
                "manually).")
           {:variant/id   id
            :fx-id        managed-fx-id
            :network      network
            :fx-overrides author-fx-overrides})))

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

(defn- default-view-lookup
  "Default view-metadata lookup — reads the **framework** `:view`
  registrar slot (where `reg-view` stamps a view's symbol metadata, incl.
  any props/`:spec`/`:schema` slot). Production bundles that elide views
  return nil; pure tests thread an explicit `:view-lookup`."
  [view-id]
  (framework-registrar/handler-meta :view view-id))

(defn- coerce-view-lookup
  "Accept a 1-arg fn or a `{view-id → view-meta}` map as `:view-lookup`."
  [view-lookup]
  (cond
    (nil? view-lookup) default-view-lookup
    (map? view-lookup) #(get view-lookup %)
    (fn? view-lookup)  view-lookup
    :else              (fail! :rf.error/story-bad-lookup
                              "re-frame2-story: :view-lookup must be a fn or a map"
                              {:view-lookup view-lookup})))

(defn compile-body
  "Compile a raw variant `body` (the child) registered under `id` into a
  normalized plan. `lookup` is a 1-arg fn returning raw parent bodies.

  This is the pure core; `variant-plan` is the public front door that
  resolves a registered/keyword/map target onto this fn.

  `opts` (4-arity) may carry:

  - `:view-lookup` — a 1-arg fn `(view-id) → view-meta` resolving the
    `:component` view's registration metadata (for the view-args schema).
    Defaults to the framework `:view` registrar.
  - `:validator-fns` — `{:validate (fn [schema value]) :explain (fn …)}`
    used for malformed-value checking of `:effective-args` (§View arg
    schemas). With no validator only required-key presence is checked
    (the host-free floor)."
  ([id body lookup] (compile-body id body lookup nil))
  ([id body lookup {:keys [view-lookup validator-fns] :as _opts}]
  (let [view-lookup  (coerce-view-lookup view-lookup)
        chain        (resolve-source-chain id body lookup)
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
        ;; ---- network world slot (rf2-5x1wt.14) ----
        ;; Per-route replies may carry `[:arg key]` placeholders (e.g. a
        ;; stubbed id driven by a control), so substitute before lowering.
        network      (substitute-args (:network ctx) arg-map subs!)
        ;; `:network` and an explicit `:fx-overrides` on :rf.http/managed
        ;; both own the same fx — that is a hard conflict (§Network stubs).
        _            (check-network-fx-conflict! id (:fx-overrides ctx) network)
        ;; Lower the route map to the managed-stub fx override; nil when
        ;; there are no routes. The derived `:fx-overrides` merges UNDER any
        ;; non-managed author overrides (the conflict above already ruled
        ;; out a managed-targeting author override).
        network-low  (lower-network network)
        fx-overrides (merge (when network-low (:fx-overrides network-low))
                            (:fx-overrides ctx))
        ;; ---- view arg schema + effective-args validation (rf2-5x1wt.12) ----
        ;; `:effective-args` at plan time IS the resolved arg-map; the
        ;; render path layers control-panel overrides on top later. We
        ;; copy the view's explicit-input schema into the plan, validate
        ;; the effective args against it, and FAIL plan construction on a
        ;; missing-required or malformed view input (§View arg schemas).
        component-id (:component ctx)
        view-meta    (when component-id (view-lookup component-id))
        schema       (view-args-schema view-meta)
        eff-args     arg-map
        validation   (when schema
                       (validate-effective-args schema eff-args validator-fns))
        _            (when (and validation (= :invalid (:status validation)))
                       (fail! :rf.error/story-view-args-invalid
                              (str "re-frame2-story: variant " id
                                   " — :effective-args do not satisfy the view-args "
                                   "schema of :component " component-id
                                   (when-let [m (seq (:missing validation))]
                                     (str "; missing required " (mapv :key m)))
                                   (when-let [b (seq (:malformed validation))]
                                     (str "; malformed " (mapv :key b))))
                              {:variant/id      id
                               :component       component-id
                               :view-args-schema schema
                               :missing         (:missing validation)
                               :malformed       (:malformed validation)
                               :effective-args  eff-args}))
        ;; ---- runner requirement ----
        required     (compute-required-runner setup script* assertions)
        ;; ---- source coords ----
        source       (:source child)
        platforms    (or (:platforms ctx) #{:client})
        world        (cond-> {:setup          setup
                              :args           arg-map
                              :argtypes       argtypes
                              :effective-args eff-args
                              :scripts        scripts
                              :platforms      platforms}
                       (some? schema)        (assoc :view-args-schema schema)
                       (some? sub-overrides) (assoc-in [:render :sub-overrides] sub-overrides)
                       ;; `:network` keeps the per-route reply data (source
                       ;; of truth, feeds :plan-hash via :world); the
                       ;; lowering (rf2-5x1wt.14) folds its managed-stub fx
                       ;; override into the frame's `:fx-overrides` below.
                       (seq network)          (assoc :network network)
                       (seq fx-overrides)     (assoc-in [:frame :fx-overrides] fx-overrides)
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
                      :effective-args eff-args
                      :view-args-schema schema
                      :view-args-validation (when validation
                                              ;; :status :ok here (an :invalid
                                              ;; validation throws upstream); the
                                              ;; slot documents the passing contract.
                                              {:status    (:status validation)
                                               :missing   (:missing validation)
                                               :malformed (:malformed validation)})
                      ;; rf2-5x1wt.14 — per-route network stubs + the
                      ;; managed-stub fx the routes lower to (§Network
                      ;; stubs — ":network participates in explain").
                      :network      (when (seq network)
                                      {:routes       network
                                       :lowered-to   (:fx-overrides network-low)})
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
      source (assoc :source source)))))

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
  - `:view-lookup` — a 1-arg fn `(view-id) → view-meta` OR a
    `{view-id → view-meta}` map, used to resolve the `:component` view's
    props/`:spec`/`:schema` slot for view-args validation. Defaults to
    the framework `:view` registrar (§View arg schemas).
  - `:validator-fns` — `{:validate (fn …) :explain (fn …)}` for
    malformed-value checking of `:effective-args`; with none supplied
    only required-key presence is checked.

  Returns the normalized plan map: `:variant/id`, `:source-chain`,
  `:world` (incl. `:effective-args` and `:view-args-schema` when a view
  schema is on file), `:script`, `:expect`, `:required-runner`, `:tags`,
  `:explain` (and `:source` when coords are present).

  FAILS with a structured `:rf.error/story-*` ex-info on: an unregistered
  keyword target, an unregistered `:extends` parent, an `:extends` cycle,
  a missing `[:arg key]`, or `:effective-args` that violate the view-args
  schema (`:rf.error/story-view-args-invalid`)."
  ([target] (variant-plan target nil))
  ([target {:keys [lookup] :as opts}]
   (let [lookup-fn    (coerce-lookup lookup)
         compile-opts (select-keys opts [:view-lookup :validator-fns])]
     (cond
       (keyword? target)
       (if-let [body (lookup-fn target)]
         (compile-body target body lookup-fn compile-opts)
         (fail! :rf.error/story-unknown-variant
                (str "re-frame2-story: no registered variant " target)
                {:variant/id target}))

       (map? target)
       (compile-body (:variant/id target) (dissoc target :variant/id)
                     lookup-fn compile-opts)

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
