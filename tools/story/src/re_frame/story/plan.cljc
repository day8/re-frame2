(ns re-frame.story.plan
  "Variant-plan compiler and `explain` base (Lane B foundation).

  Per `tools/story/spec/017-Testing-Story.md` §Four-bucket authoring
  model + §Variant plan + §Total resolution order, every registered
  variant and inline plan MUST be normalized before execution. This
  namespace is the **author-surface foundation**: it turns a raw variant
  body (or inline map) into the normalized `:world` / `:script` /
  `:expect` shape, resolving the `:extends` parent chain, applying
  `:compose` fragment/check composition with strict conflict resolution,
  lowering author ergonomics, resolving `:args` + `[:arg key]`
  placeholders, computing the required-runner capability set, and
  producing `explain` data during compilation.

  ## What this layer DOES

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
  - Compute the `:required-runner` capability set from the script steps
    and the declared assertions, through the capability-token registry in
    `re-frame.story.requirements` (the single home for the
    per-step / per-assertion requirement maps).
  - Attach `:source-chain` and an `:explain` map.

  ## View arg schemas

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

  ## View-state subscription overrides

  A variant whose goal is rendering / design exploration MAY author
  `:sub-overrides` — a map of exact subscription query vectors to data
  values the renderer surfaces for them (§View-state subscription
  overrides). The compiler resolves `[:arg key]` placeholders inside the
  override VALUES (same one-level mechanism as setup/script), validates
  each resolved value against the subscription's OUTPUT schema when one is
  on file (distinct from the view-arg schema above — a missing schema soft-
  passes), lowers the map to `[:world :render :sub-overrides]`, and marks
  the plan `:fidelity` with `:sub-overrides`. A resolved value that
  violates a sub's output schema FAILS plan construction (before render)
  with `:rf.error/story-sub-override-invalid`.

  Overrides feed the RENDER PATH only (`re-frame.story.sub-overrides`) —
  never app-db, never `compute-sub` — so a `:sub-overrides` value does NOT
  satisfy `:rf.assert/sub-equals` (which evaluates through `compute-sub`
  against the frame snapshot). `:sub-overrides` is the third, deliberately
  lower-fidelity rung of the fidelity ladder; the `:fidelity` set labels
  it so a reviewer can tell at a glance which evidence a variant rests on.

  ## Network world slot

  When a variant authors `:network` (a `{[method url] {:reply …}}` route
  map), the compiler keeps the per-route reply data at `[:world :network]`
  (the source of truth that feeds `:plan-hash` through the `:world` slot
  and `explain`) and **lowers** it to the existing managed-request stub
  machinery — the variant frame overrides `:rf.http/managed` with the stub
  fx `re-frame.http.test-support/install-managed-request-stubs!` registers
  (folded into `[:world :frame :fx-overrides]`). It reuses that helper
  rather than inventing a new HTTP mock; the actual `install-…!` call is a
  RUNTIME concern (run when the variant frame is created). `:network` and
  an explicit author `:fx-overrides` on `:rf.http/managed` are a hard
  conflict (`:rf.error/story-network-fx-conflict`) — `:network` is the
  dedicated affordance for that fx. See §Network world slot below.

  ## Strict `:compose` composition + capability inference

  Strict `:compose` fragment/check composition AND its conflict resolution
  land HERE (§`:compose` / §Merge rules / §Conflict resolution): see the
  `## Strict composition` block below — `resolve-strict-conflict`,
  variant-owned-wins, the flat-fragment guard
  (`:rf.error/story-compose-nested-fragment`), and the silent-conflict
  failure (`:rf.error/story-compose-conflict`). The capability-token
  registry lives in `re-frame.story.requirements`: the `:required-runner`
  slot is filled through it (the single home for the per-step /
  per-assertion requirement maps).

  ## What this layer DEFERS

  The runner itself + evidence projection are downstream of this compiler.
  This layer keeps the per-route `:network` reply map at `[:world :network]`
  and lowers it to the managed-stub fx-override the runner installs. The
  run-artifact wiring that threads `[:world :network]` onto the artifact's
  `:network` slot so replay RE-INSTALLS the route stubs lives in
  `re-frame.story.determinism/->artifact` + `re-frame.story.artifact` —
  without reshaping the plan, exactly as this layer anticipates.

  ## Purity / elision

  Every fn here is pure data → data, so the compiler is JVM-runnable and
  the test suite needs no host. The default body `lookup` reads the Story
  side-table (`re-frame.story.registrar`), which under the §6 elision
  contract is empty in a production bundle; callers thread an explicit
  `:lookup` (a `{variant-id → raw-body}` map or a 1-arg fn) for pure
  tests."
  (:require [re-frame.story.args         :as args]
            [re-frame.story.assertions   :as assertions]
            [re-frame.story.config       :as config]
            [re-frame.story.registrar    :as registrar]
            [re-frame.story.requirements :as requirements]
            [re-frame.story.malli-schema :as msu]
            [re-frame.story.tags         :as tags]
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
;; The *parent-chain* slice of §Merge rules — the foundation the strict
;; `:compose` conflict machinery builds on. The principle from §`:extends`:
;; context flows down, verdict is local.
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
  child-wins for scalars; see `merge-context`).

  `:args` / `:argtypes` are deliberately ABSENT: they are resolved by the
  dedicated `merge-key` deep-merge over the inherited → composed → child
  chain (the single source of truth for `arg-map` / `argtypes`), never read
  off `ctx`. Listing them here only buys a redundant deep-merge per compile
  and misleads a reader into thinking `ctx` is the arg source.

  `:sensitive` / `:large` (rf2-cmjly3 finding 12) carry the EP-0025 durable
  app-db classification (`{:app-db [[path]…]}`) — a plain map, so they
  merge exactly like every other context key (a child re-declaring the axis
  replaces the parent's `:app-db` vector; a child that omits it inherits the
  parent's verbatim). This is what lets `[:world :sensitive]` / `[:world
  :large]` reach `allocate-inline!` (below in `frames.cljc`) for an inline
  plan run, which has no registered variant body to read the classification
  off. It does NOT change `allocate!`'s REGISTERED-variant path — that reads
  the classification straight off the raw (un-merged) variant body via
  `apply-variant-classification!`, entirely independent of the plan
  compiler, and is unaffected by this key's presence here."
  [:sub-overrides :db-seed :network :fx-overrides :interceptor-overrides
   :decorators :loaders :loaders-teardown :loaders-complete-when
   :modes :substrates :platforms :viewport :background :xray
   :dispatch-console? :component :doc :args->events
   :sensitive :large])

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

;; Tags are additive through `:extends` (§Merge rules — append/union), then
;; resolved through the SHARED `re-frame.story.tags` resolver (`:!x` removal
;; markers stripped + their base subtracted, story fallback when the chain
;; declares none). `compile-body` unions `:tags` across the resolved `bodies`
;; and hands the union to `tags/resolve-markers` so the plan's `:tags` is the
;; EFFECTIVE set — identical to what `variants-with-tags`, docs chips, and the
;; sidebar filter compute through the same resolver.

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

(defn- reject-malformed-steps!
  "FAIL plan construction when any COERCED (but not-yet-folded) script step
  is structurally malformed — an unknown step tag or a tag with the wrong
  arity/shape (spec/017 §Script step grammar). Reuses the
  runner's `validate-script` (`runner/step-arity-ok?` + `known-step?`) — the
  ONE encoding of the per-tag shape constraints — so the plan compiler and
  the runtime runner agree on what a well-formed step is.

  This gate MUST run BEFORE `assertions/fold-script`: the fold helpers
  (`assertions/assert-db->atom` / `assert-dom->atom`) assume a well-formed
  shipping step (a 3-/4-arity `:assert-db`, a recognised `:assert-dom`
  mode), so a malformed one folded raw throws an opaque HOST exception
  (IndexOutOfBounds / `No matching clause`) at plan-compile rather than the
  project's structured `:rf.error/story-*` shape. Catching it here surfaces
  the typo through the SAME error vocabulary every other plan error uses
  (cf `reject-unknown-assertions!` / `reject-assert-in-setup!`)."
  [id script]
  (when-let [offenders (seq (runner/validate-script script))]
    (fail! :rf.error/story-bad-step
           (str "re-frame2-story: variant " id
                " — malformed script step(s) "
                (pr-str (mapv (fn [{:keys [idx step reason]}]
                                {:idx idx :step step :reason reason})
                              offenders))
                ". Each step must be a recognised tagged step with the right "
                "shape (e.g. [:assert-db path value] / [:assert-db path :pred "
                "fn], [:assert-dom selector :visible|:hidden] / [:assert-dom "
                "selector :text string]). Fix the step tag, arity, or mode.")
           {:variant/id id :offending-steps (vec offenders)})))

(defn- normalize-scripts
  "Return `{:script [step ...] :scripts [{:name :script :auto-run?} ...]}`
  for a merged body. Accepts the target `:script` spelling AND the
  shipping `:play-script` / `:plays` spellings, lowering them through the
  runner's canonical coercion. The primary `:script` is the first play.

  Each play's coerced script is first SHAPE-VALIDATED
  (`reject-malformed-steps!`) so a malformed `:assert-db` / `:assert-dom`
  (or any step) FAILS with a structured `:rf.error/story-bad-step` BEFORE
  the fold — the fold helpers assume well-formed input.

  After validation every play's script is FOLDED (`assertions/fold-script`):
  a shipping `:assert-db` / `:assert-dom` step rewrites to
  the canonical `[:assert assertion-atom]` checkpoint, so EVERY in-script
  assertion — whatever sugar the author typed — resolves to the ONE
  assertion atom shape (spec/017 §Assertions — one atom, two positions).
  Each `:scripts` entry carries the folded script too, so named plays the
  runner drives also see the canonical checkpoints."
  [id body]
  (let [plays (cond
                (contains? body :script)
                ;; Target spelling: a bare step vector, coerced the same
                ;; way the runner coerces a `:play-script` body's `:script`.
                [{:script (runner/coerce-script (:script body)) :auto-run? true}]

                :else
                (runner/variant-body->plays body))
        plays (mapv (fn [p]
                      (reject-malformed-steps! id (:script p))
                      (update p :script assertions/fold-script))
                    (vec plays))]
    {:script  (-> plays first :script (or []))
     :scripts plays}))

(defn- assert-step?
  "True iff `step` is an `[:assert assertion-vector]` in-script checkpoint.
  Per spec/017 §Script step grammar the `[:assert …]`
  step is legal in `:script` but ILLEGAL in `:setup` — setup establishes
  preconditions, it does not judge."
  [step]
  (and (vector? step)
       (pos? (count step))
       (= :assert (first step))))

(defn- reject-assert-in-setup!
  "FAIL plan construction when any resolved `:setup` step is an
  `[:assert …]` checkpoint (spec/017 §Script step grammar + §Setup).
  `:assert` is the mid-script verdict atom; placing one in
  `:setup` confuses precondition with judgement. The variant resolves it
  by moving the assertion to `:script` (as an `[:assert …]` checkpoint)
  or to the terminal `:assertions` slot. The reject runs at plan-compile
  time so the error surfaces before any run, the same way the other
  `:rf.error/story-*` plan errors do."
  [id setup]
  (when-let [offenders (seq (filter assert-step? setup))]
    (fail! :rf.error/story-assert-in-setup
           (str "re-frame2-story: variant " id
                " — :setup carries an [:assert …] checkpoint "
                (pr-str (vec offenders))
                ". An [:assert …] checkpoint is a mid-script verdict and is "
                "ILLEGAL in :setup (which establishes preconditions, it does "
                "not judge). Move the assertion to :script (as an "
                "[:assert …] checkpoint) or to the terminal :assertions slot.")
           {:variant/id id :offending-steps (vec offenders)})))

;; ============================================================================
;; Assertion-id validation
;; ============================================================================

(defn- script-assertion-atoms
  "Collect the assertion atoms an `[:assert assertion-atom]` checkpoint
  carries from a folded `script`. The shipping
  `:assert-db` / `:assert-dom` steps are already folded to `[:assert …]`
  by `normalize-scripts`, so this single walk covers every in-script
  assertion position uniformly. Pure data → data."
  [script]
  (into [] (keep (fn [step] (when (assert-step? step) (second step)))) script))

(defn- reject-unknown-assertions!
  "FAIL plan construction when any authored assertion atom — terminal
  `:assertions` OR an in-script `[:assert …]` checkpoint — names an id
  that is not in the recognised P1 vocabulary
  (`assertions/known-assertion-ids`, spec/017 §Assertions).
  Catching it at compile time surfaces the typo before any run, the same
  way the other `:rf.error/story-*` plan errors do — never letting an
  unknown id record a vacuous `:rf.assert/unknown` pseudo-record at run
  time. `script-assertions` are the atoms pulled from `[:assert …]`
  checkpoints (post-fold); `terminal-assertions` are the child's own
  `:assertions` atoms."
  [id script-assertions terminal-assertions]
  (let [offenders (into []
                        (comp (remove nil?)
                              (remove (fn [a]
                                        (assertions/assertion-id-known?
                                          (assertions/assertion-atom-id a)))))
                        (concat terminal-assertions script-assertions))]
    (when (seq offenders)
      (fail! :rf.error/story-unknown-assertion
             (str "re-frame2-story: variant " id
                  " — unknown assertion id(s) "
                  (pr-str (mapv assertions/assertion-atom-id offenders))
                  " in " (pr-str (vec offenders))
                  ". An assertion atom must name a recognised :rf.assert/* id "
                  "(the shipping seven, the DOM family, or a registered "
                  "requirement id). Check the spelling, or fold a shipping "
                  ":assert-db / :assert-dom step rather than inventing an id.")
             {:variant/id id
              :offending-assertions (vec offenders)
              :known-ids assertions/known-assertion-ids}))))

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
;; Required-runner inference
;; ============================================================================
;;
;; The capability-token registry + cost-ordered concrete runners + the
;; per-step / per-assertion requirement maps live in
;; `re-frame.story.requirements` — the single home so the
;; runner-selection, `:cannot-run` refusal, and post-run evidence-slot
;; validation all read ONE source of truth. The plan compiler computes the
;; `:required-runner` slot through that registry. Per §Runner requirements
;; — capability is a SET of tokens, not a tier scalar.

(defn- compute-required-runner
  "Union the capability tokens demanded by every setup step, script step,
  and terminal assertion, through the `re-frame.story.requirements` registry
  (§Runner requirements). `:headless` work contributes the empty set (which
  resolves to the cheapest `:headless` runner). An in-script `[:assert …]`
  checkpoint's wrapped-atom tokens are folded by `requirements/step-tokens`,
  so the script vector alone carries them."
  [setup script assertions]
  (requirements/plan-required-runner setup script assertions))

;; ============================================================================
;; View arg schemas
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
;; validation schema — so it is never consumed here). The first-match key
;; order — `:rf/props` (canonical, `spec/Spec-Schemas.md`
;; §`:rf/registration-metadata`) then `:schema` (the alternative `reg-*`
;; metadata key) — is the canonical resolution shared with every Story
;; consumer through `re-frame.story.malli-schema/view-args-schema`. `:rf/props`
;; wins over `:schema`; there is NO composition (a view's only schema
;; surface is its props). `:spec` is NOT a slot: the framework reads
;; `:schema` only on `reg-*` metadata (see
;; migration/from-re-frame-v1/README.md §M-54).
;;
;; **Boundary.** This validates EXPLICIT view inputs only. Values
;; returned from subscriptions are validated by subscription-output
;; schemas (§View-state subscription overrides); the two are different
;; contracts and are not conflated here.

(def view-args-schema-keys
  "Re-export of `re-frame.story.malli-schema/view-args-schema-keys` — the
  canonical first-match key order `[:rf/props :schema]`."
  msu/view-args-schema-keys)

(def view-args-schema
  "Re-export of `re-frame.story.malli-schema/view-args-schema` — the
  first-match key picker over a resolved `view-meta` map. The compiler
  uses it to write `[:world :view-args-schema]`; the shared resolver
  `re-frame.story.view-args/compiled-view-args-schema` (which consumers
  call) reads that compiled slot back."
  msu/view-args-schema)

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
;; View-state subscription overrides
;; ============================================================================
;;
;; Per spec §View-state subscription overrides. `:sub-overrides` is a map
;; of EXACT subscription query vectors → data values the renderer surfaces
;; for view-state / design exploration. The compiler:
;;
;;   1. resolves `[:arg key]` placeholders inside the override VALUES
;;      (handled in `compile-body` by the shared `substitute-args` pass);
;;   2. validates each resolved value against the subscription's OUTPUT
;;      schema when one is on file (this section) — DISTINCT from the
;;      view-arg schema, which validates explicit view inputs. A sub with
;;      no output schema soft-passes (the floor matches the view-arg
;;      malformed-value convention: no validator → no malformed check);
;;   3. lowers the resolved map to `[:world :render :sub-overrides]`;
;;   4. marks the plan `:fidelity` with `:sub-overrides`.
;;
;; A resolved value that VIOLATES a sub's output schema FAILS plan
;; construction (before render) with `:rf.error/story-sub-override-
;; invalid`. The override never touches app-db or `compute-sub`, so it
;; does NOT satisfy `:rf.assert/sub-equals` (the §View-state subscription
;; overrides honesty rule) — that boundary lives at the render-path
;; resolver (`re-frame.story.sub-overrides`) + the assertion module.
;;
;; **Subscription-output-schema source.** A sub's output schema rides on
;; its framework `:sub` registrar metadata `:schema` slot (`reg-sub`'s
;; optional metadata map: `(reg-sub :id {:schema …} …)`). The sub-id is
;; the FIRST element of the query vector. The compiler resolves it through
;; a `:sub-lookup` opt — a `(sub-id) → sub-meta` fn or `{sub-id → sub-meta}`
;; map, defaulting to the framework `:sub` registrar — so the compiler
;; stays a pure data → data fn for host-free tests (the test threads an
;; explicit map; production reads the registrar).

(def ^:private sub-output-schema-keys
  "Sub-metadata keys carrying the subscription OUTPUT schema, in
  resolution order (first present wins). `:schema` is the `reg-sub`
  metadata slot (Spec 010 §reg-sub); `:rf/output` is reserved for a
  future explicit output-schema key a port may adopt."
  [:schema :rf/output])

(defn sub-output-schema
  "Return the OUTPUT schema for a subscription from its `sub-meta` map
  (the framework `:sub` registrar slot), or nil when none is present.
  Picks the first of `sub-output-schema-keys` that is set. This is the
  subscription-output contract — DISTINCT from the view-args/props schema
  `view-args-schema` reads (§View-state subscription overrides — sharp
  boundary)."
  [sub-meta]
  (when (map? sub-meta)
    (some (fn [k] (let [s (get sub-meta k)] (when (some? s) s)))
          sub-output-schema-keys)))

(defn validate-sub-overrides
  "Validate every resolved `:sub-overrides` value against its
  subscription's OUTPUT schema. Pure data → data.

  - `sub-overrides` — the resolved `{query-vector value}` map (post `[:arg
    key]` substitution).
  - `sub-lookup` — a 1-arg fn `(sub-id) → sub-meta` resolving a
    subscription's registration metadata (for its output schema). The
    sub-id is `(first query-vector)`.
  - `validator-fns` — `{:validate (fn [schema value] truthy?) :explain (fn
    [schema value] explanation)}`, the SAME malformed-value validator the
    view-arg path threads. With no validator, value-against-schema
    checking SOFT-PASSES (the host-free floor — a `:sub-overrides`-only
    JVM test that wants schema enforcement threads a Malli validator).

  Returns `{:status :ok | :invalid :violations [...]}` where each
  violation is `{:query-v qv :sub-id id :value v :schema s :explain
  explanation}`. A query whose sub carries no output schema is skipped
  (soft-pass); only a present schema that the value fails is a violation."
  [sub-overrides sub-lookup validator-fns]
  (let [validate (:validate validator-fns)
        explain  (:explain  validator-fns)
        entries  (seq sub-overrides)]
    (if (or (nil? entries) (nil? validate))
      ;; No overrides, or no validator → nothing structural to check.
      {:status :ok :violations []}
      (let [violations
            (into []
                  (keep (fn [[query-v value]]
                          (let [sub-id (when (vector? query-v) (first query-v))
                                schema (sub-output-schema (sub-lookup sub-id))]
                            (when (and (some? schema) (not (validate schema value)))
                              {:query-v query-v
                               :sub-id  sub-id
                               :value   value
                               :schema  schema
                               :explain (when explain (explain schema value))}))))
                  entries)]
        {:status     (if (empty? violations) :ok :invalid)
         :violations violations}))))

;; ============================================================================
;; Fidelity ladder
;; ============================================================================
;;
;; Per spec §View-state subscription overrides — the fidelity ladder is:
;; real setup events (highest) → schema-checked app-db seed → subscription
;; overrides (lowest, but legitimate when labelled). `:fidelity` is a SET
;; of the rungs a resolved plan actually rests on, computed from the world
;; inputs so authors never type it. A reviewer reads the set to know which
;; evidence a variant's render leans on.
;;
;;   :real-setup    — the variant has setup events (or a script) that
;;                    drive real state into the frame;
;;   :db-seed       — a schema-checked direct app-db seed (the world
;;                    `:db-seed` slot, wired end-to-end: the compiler lowers
;;                    it to `[:world :db-seed]` and the runtime seeds +
;;                    schema-validates the frame's app-db BEFORE the script);
;;   :sub-overrides — one or more view-state subscription overrides.

(defn compute-fidelity
  "Compute the `:fidelity` set for a resolved plan from its world inputs.
  Pure data → data. Returns a (possibly empty) set drawn from
  `#{:real-setup :db-seed :sub-overrides}`:

  - `:real-setup`    when `setup` (or `script`) is non-empty — real
                     events drive the frame's state;
  - `:db-seed`       when `db-seed` resolves any entry (a schema-checked
                     direct app-db seed merged into the frame BEFORE the
                     script);
  - `:sub-overrides` when `sub-overrides` resolves any entry.

  A plain events-driven variant yields `#{:real-setup}`; a pure design
  variant that only pins sub values yields `#{:sub-overrides}`; a
  db-seed variant yields `#{:db-seed}`; a hybrid carries the union. The
  empty set (no setup, no seed, no overrides) is a legitimate
  render-the-view-as-mounted variant. An EMPTY resolved seed / override
  map is treated as absent (no rung) — the same `seq` floor `setup`
  uses."
  [{:keys [setup script db-seed sub-overrides]}]
  (cond-> #{}
    (or (seq setup) (seq script)) (conj :real-setup)
    (seq db-seed)                 (conj :db-seed)
    (seq sub-overrides)           (conj :sub-overrides)))

;; ============================================================================
;; Network world slot
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
;; `:world` slot, `explain`, and — threaded onto the artifact's `:network`
;; slot by `re-frame.story.determinism/->artifact` — the run
;; artifact) AND **lowers** it to the existing managed-request stub
;; machinery: the variant frame overrides `:rf.http/managed` with the stub
;; fx that `re-frame.http.test-support/install-managed-request-stubs!`
;; registers. We do NOT invent a new HTTP mock — the lowering names the
;; existing seam; the runner (and `replay-run-artifact`, via the `:network`
;; slot) installs the route map and points `:fx-overrides` at the stub fx.
;;
;; `:network` is NOT a replacement for generic `:fx-overrides`; it is the
;; higher-level affordance for `:rf.http/managed` specifically. Generic
;; `:fx-overrides` still serve every non-HTTP effect and the unusual cases
;; (§Network stubs — "generic :fx-overrides still exists").

(def managed-fx-id
  "The production managed-HTTP fx id `:network` lowers an override of —
  `re-frame.http.managed`'s `:rf.http/managed` (Spec 014)."
  :rf.http/managed)

(def managed-stub-fx-id
  "The fx id `re-frame.http.test-support/install-managed-request-stubs!`
  registers and returns — the per-call stub target a `:network` variant's
  frame redirects `:rf.http/managed` to. Mirrors that helper's private
  `stub-fx-id` constant (Spec 014 §Testing); naming it here lets the plan
  declare the lowering without depending on the http artefact at compile
  time (the actual `install-…!` call is a RUNTIME concern, run when the
  variant frame is created)."
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
;; Strict composition — `:compose` fragments + checks
;; ============================================================================
;;
;; Per spec/017 §`:compose` / §Total resolution order / §Merge rules /
;; §Conflict resolution: a variant (or inline plan) MAY pull in registered
;; fragments and checks through `:compose [id ...]`, applied in declared
;; order between the parent-chain merge (step 2) and the variant-owned
;; values (step 4).
;;
;; - A FRAGMENT contributes world/behaviour: setup + script APPEND in
;;   declared order; args/argtypes deep-merge; `:network`,
;;   `:fx-overrides`, `:interceptor-overrides`, loaders, decorators fold
;;   in. (Per §Merge rules script DOES append through `:compose` — unlike
;;   `:extends`, where script never appends.)
;; - A CHECK contributes its id to the inheritable `:expect :checks` list
;;   (check identity preserved); its body is NOT inlined here — the runner
;;   expands a check-id into grouped assertions, keyed by the check id, so
;;   a failed check shows both the id and the underlying records.
;;
;; FLAT FRAGMENTS (§Fragments). A composed fragment that itself carries
;; `:compose` (or `:extends`) is a hard error — `:rf.error/story-compose-
;; nested-fragment` — so cycles are impossible in P1. The schema already
;; rejects this at registration; the compiler re-checks because a
;; programmatic registration may bypass the macro/schema path, and an
;; inline plan may name a fragment whose body was hand-built.
;;
;; STRICT-CONFLICT FIELDS (§Merge rules / §Conflict resolution). The
;; strict-conflict fields are the override MAPS `:fx-overrides` and
;; `:interceptor-overrides`, resolved per-KEY:
;;
;;   - the variant OWNS any key it sets directly → variant-owned-wins;
;;     composed fragments only fill keys the variant left unset;
;;   - two composed fragments setting the SAME key to DIFFERENT values
;;     while the variant is silent → HARD ERROR (`:rf.error/story-compose-
;;     conflict`), resolved by the variant stating the wanted value;
;;   - two composed fragments setting the same key to the SAME value → no
;;     conflict (declared order, identical → fine);
;;   - exactly one fragment setting a key → that value fills it.
;;
;; There is NO `:resolve-conflicts` escape hatch in P1 (the schema rejects
;; it); the only resolution is the priority ladder above.

(def ^:private strict-conflict-keys
  "The context-map keys whose per-key composition is strict (§Merge rules
  — Strict conflict). Each value is a `{id → override}` map; the conflict
  is resolved per id, with the variant owning any id it sets directly."
  [:fx-overrides :interceptor-overrides])

(defn- check-flat-fragment!
  "FAIL with `:rf.error/story-compose-nested-fragment` when a composed
  `fragment` body carries `:compose` or `:extends` — P1 fragments MUST be
  flat (§Fragments). The schema rejects this at registration; this is the
  compile-time guard for programmatic/inline paths."
  [variant-id fragment-id fragment]
  (when (or (contains? fragment :compose)
            (contains? fragment :extends))
    (fail! :rf.error/story-compose-nested-fragment
           (str "re-frame2-story: fragment " fragment-id " composed by "
                variant-id " carries "
                (if (contains? fragment :compose) ":compose" ":extends")
                " — P1 fragments MUST be flat (no fragment composing "
                "another fragment); cycles are impossible in P1.")
           {:variant/id  variant-id
            :fragment/id fragment-id
            :offending-key (if (contains? fragment :compose) :compose :extends)})))

(defn- resolve-strict-conflict
  "Resolve ONE strict-conflict override-map field across the composed
  fragments + the variant-owned value. Pure data → data.

  - `field` — `:fx-overrides` or `:interceptor-overrides`.
  - `frag-contribs` — vector of `{:source fragment-id :map override-map}`
    in declared order.
  - `variant-owned` — the override map set DIRECTLY in the variant body
    (nil/absent when the variant is silent).
  - `variant-id` — for error/explain attribution.

  Returns `{:merged {id → override} :resolved [...] :unresolved [...]}`:

  - `:merged` is the final override map fed into `[:world :frame field]`.
  - `:resolved` is the explain trail of strict conflicts the priority
    ladder settled — each `{:field :key :winner :winning-source
    :losing-sources :rule}`.
  - `:unresolved` records any id where two fragments disagree AND the
    variant is silent — a HARD conflict. The caller FAILS when this is
    non-empty (the variant resolves it by stating the value).

  Variant-owned-wins: a key the variant sets directly is owned by the
  variant; composed fragments for that key become losing sources (the
  variant value is the winner; no failure, even when fragments disagree)."
  [field frag-contribs variant-owned variant-id]
  (let [;; gather, per override id, the fragment sources that set it
        ;; (in declared order) — {id [{:source :value} ...]}
        by-id    (reduce
                   (fn [acc {:keys [source map]}]
                     (reduce-kv (fn [m k v]
                                  (update m k (fnil conj [])
                                          {:source source :value v}))
                                acc map))
                   {}
                   frag-contribs)
        ;; Resolve in a DETERMINISTIC key order. `by-id` is a hash-map, so
        ;; iterating it directly would let hash-map iteration order (which
        ;; differs across CLJS / JVM) leak into `:resolved` / `:unresolved`
        ;; — and thence into `explain`'s `:strict-conflicts` vector and the
        ;; `:rf.error/story-compose-conflict` ex-data. The override keys are
        ;; fx-/interceptor-ids (keywords); sorting by `pr-str` gives a
        ;; total, platform-stable order so explain diffs + error messages
        ;; reproduce. (`:merged` is order-independent and `:explain` is
        ;; excluded from `plan-hash`, so this is a pure debug-surface fix.)
        ordered  (sort-by (comp pr-str key) by-id)
        owned?   (fn [k] (and (map? variant-owned) (contains? variant-owned k)))]
    (reduce
      (fn [{:keys [merged resolved unresolved]} [k contribs]]
        (let [distinct-vals (distinct (map :value contribs))]
          (cond
            ;; variant owns this key → variant-owned-wins (no conflict,
            ;; even if fragments disagree); the fragment(s) lose.
            (owned? k)
            {:merged     merged   ; variant value is merged on top later
             :resolved   (conj resolved
                               {:field          field
                                :key            k
                                :winner         (get variant-owned k)
                                :winning-source :variant
                                :losing-sources (mapv :source contribs)
                                :rule           :variant-owned-wins})
             :unresolved unresolved}

            ;; one distinct value across all contributing fragments →
            ;; no conflict (identical, or a single fragment).
            (= 1 (count distinct-vals))
            {:merged     (assoc merged k (-> contribs first :value))
             :resolved   resolved
             :unresolved unresolved}

            ;; two+ fragments disagree AND the variant is silent → HARD.
            :else
            {:merged     merged
             :resolved   resolved
             :unresolved (conj unresolved
                               {:field   field
                                :key     k
                                :sources (mapv :source contribs)
                                :values  (vec distinct-vals)})})))
      {:merged {} :resolved [] :unresolved []}
      ordered)))

;; ============================================================================
;; Compiler
;; ============================================================================

;; ---- lookup coercion -----------------------------------------------------
;;
;; Every `:lookup` option (variant bodies, view metadata, sub metadata,
;; fragment bodies, check bodies) accepts the SAME two shapes — a 1-arg fn
;; `(id) → body-or-nil` OR a `{id → body}` map — and falls back to a
;; per-kind default when nil. `coerce-kind-lookup` is the single parametric
;; coercer for all five; only the default-fn and the option label (for the
;; error message) differ, both parameters. (One coercer, not five
;; hand-rolled copies of the identical `cond`.)

(defn- coerce-kind-lookup
  "Coerce a `:lookup`-shaped option into a 1-arg `(id) → body` fn. Accepts
  a 1-arg fn or a `{id → body}` map, falling back to `default-fn` when nil.
  `opt-key` names the option for the `:rf.error/story-bad-lookup` message.
  The single coercer behind every lookup option (variant / view / sub /
  fragment / check)."
  [opt-key lookup default-fn]
  (cond
    (nil? lookup) default-fn
    (map? lookup) #(get lookup %)
    (fn? lookup)  lookup
    :else         (fail! :rf.error/story-bad-lookup
                         (str "re-frame2-story: " opt-key " must be a fn or a map")
                         {opt-key lookup})))

;; ---- per-kind default lookups (the `default-fn` fed to coerce-kind-lookup)

(defn- default-lookup
  "Default raw variant-body lookup — reads the Story side-table. Production
  bundles elide the side-table, so the lookup returns nil there; pure
  tests thread an explicit `:lookup`."
  [variant-id]
  (registrar/handler-meta :variant variant-id))

(defn- default-view-lookup
  "Default view-metadata lookup — reads the **framework** `:view`
  registrar slot (where `reg-view` stamps a view's symbol metadata, incl.
  any `:rf/props` / `:schema` props-schema slot). Production bundles that
  elide views return nil; pure tests thread an explicit `:view-lookup`."
  [view-id]
  (framework-registrar/handler-meta :view view-id))

(defn- default-sub-lookup
  "Default subscription-metadata lookup — reads the **framework** `:sub`
  registrar slot (where `reg-sub` stamps a sub's metadata, incl. any
  `:schema` output-schema slot). Resolves the OUTPUT-schema source for
  `:sub-overrides` value validation. Production bundles
  that elide subs return nil; pure tests thread an explicit `:sub-lookup`."
  [sub-id]
  (framework-registrar/handler-meta :sub sub-id))

(defn- default-fragment-lookup
  "Default fragment-body lookup — reads the Story side-table `:fragment`
  kind. Production bundles elide the side-table; pure tests
  thread an explicit `:fragment-lookup`."
  [fragment-id]
  (registrar/handler-meta :fragment fragment-id))

(defn- default-check-lookup
  "Default check-body lookup — reads the Story side-table `:check` kind."
  [check-id]
  (registrar/handler-meta :check check-id))

(defn- default-global-decorators
  "Default global-decorators ref vector — reads the project-wide
  `config/get-global-decorators` (Storybook `preview.ts`
  `decorators: [...]` parity, the outermost wrap layer). Production
  bundles with Story elided register no globals, so the vector is empty
  there; pure tests thread an explicit `:global-decorators`."
  []
  (config/get-global-decorators))

(defn- default-story-decorators-lookup
  "Default story-level `:decorators` lookup — reads the parent story body's
  `:decorators` slot off the Story side-table `:story` kind. The full
  decorator stack folded into `[:world :decorators]` is `(concat globals
  story variant-chain)`, with the parent story's decorators sitting between
  the project-wide globals and the variant chain. Production bundles elide
  the side-table; pure tests thread an explicit `:story-decorators`."
  [story-id]
  (:decorators (registrar/handler-meta :story story-id)))

(defn- default-story-lookup
  "Default parent-story-body lookup — reads the Story side-table `:story`
  kind. Feeds the shared tag resolver's story-fallback layer (a variant that
  declares no tags on its `:extends` chain inherits the parent story's
  `:tags`). Production bundles elide the side-table (nil → no story tags);
  pure tests thread an explicit `:story-lookup`."
  [story-id]
  (registrar/handler-meta :story story-id))

(defn expand-checks
  "Expand a plan's `:expect :checks` ids into the
  `{check-id [assertion-atom …]}` map the unified run-result groups its
  check records by (spec/017 §Total resolution order step 8
  — \"expand checks into grouped assertions\"; §Checks — a failed check
  shows BOTH the check id AND the underlying records). Pure data → data.

  `check-ids` is the plan's resolved `[:expect :checks]` vector (check
  identity preserved); `check-lookup` is a 1-arg fn `(check-id) →
  check-body` OR a `{check-id → check-body}` map resolving each check's
  registration body (its `:assertions` slot is the assertion atoms the
  check packs). Defaults to the Story side-table `:check` kind. An
  unregistered check id maps to an empty atom vector (it groups nothing —
  the run-level aggregation still sees any ungrouped records), so a missing
  check never throws at result-assembly time."
  ([check-ids] (expand-checks check-ids nil))
  ([check-ids check-lookup]
   (let [lookup (coerce-kind-lookup :check-lookup check-lookup default-check-lookup)]
     (into {}
           (map (fn [cid]
                  [cid (vec (:assertions (lookup cid)))]))
           (or check-ids [])))))

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
    (the host-free floor).
  - `:fragment-lookup` / `:check-lookup` — a 1-arg fn `(id) → body` OR a
    `{id → body}` map resolving the bodies named in `:compose`.
    Default to the Story side-table `:fragment` / `:check` kinds.
  - `:sub-lookup` — a 1-arg fn `(sub-id) → sub-meta` OR a `{sub-id →
    sub-meta}` map resolving a subscription's registration metadata (for
    its OUTPUT schema, against which `:sub-overrides` values are
    validated). Defaults to the framework `:sub` registrar.
  - `:global-decorators` — the project-wide global-decorators ref vector
    (or a 0-arg fn returning one) prepended to `[:world :decorators]` as
    the outermost wrap layer). Defaults to
    `config/get-global-decorators`; pure tests pass an explicit vector.
  - `:story-decorators` — a 1-arg fn `(story-id) → decorators-vec` OR a
    `{story-id → decorators-vec}` map resolving the parent story's
    `:decorators` slot (folded between globals and the variant chain).
    Defaults to the Story side-table `:story` kind.
  - `:story-lookup` — a 1-arg fn `(story-id) → story-body` OR a
    `{story-id → story-body}` map resolving the parent story body, whose
    `:tags` feed the shared tag resolver's story-fallback layer (used only
    when the `:extends` chain declares no tags). Defaults to the Story
    side-table `:story` kind."
  ([id body lookup] (compile-body id body lookup nil))
  ([id body lookup {:keys [view-lookup validator-fns sub-lookup
                           fragment-lookup check-lookup
                           global-decorators story-decorators story-lookup
                           run-args] :as _opts}]
  (let [view-lookup  (coerce-kind-lookup :view-lookup view-lookup default-view-lookup)
        sub-lookup   (coerce-kind-lookup :sub-lookup sub-lookup default-sub-lookup)
        frag-lookup  (coerce-kind-lookup :fragment-lookup fragment-lookup
                                         default-fragment-lookup)
        chk-lookup   (coerce-kind-lookup :check-lookup check-lookup
                                         default-check-lookup)
        story-lookup (coerce-kind-lookup :story-lookup story-lookup
                                         default-story-lookup)
        ;; ---- ambient decorator layers ----
        ;; The full decorator stack folded into `[:world :decorators]` is
        ;; `(concat globals story variant-chain)` — the SAME set
        ;; `decorators/collect-decorator-refs` assembles at resolve time.
        ;; Folding it HERE makes the compiled plan the single source of
        ;; truth: the canvas (via `resolve-decorators`) and
        ;; `render-variant` (via `render-inputs`' `:decorators`) both read
        ;; `[:world :decorators]`, so they paint the IDENTICAL decorator
        ;; tree. Globals + story decorators are AMBIENT (not part of the
        ;; variant body or its `:extends` chain), resolved through the
        ;; project config + the parent-story body — both overridable for
        ;; pure JVM tests, exactly like the view / sub / fragment lookups.
        global-decos (cond
                       (nil? global-decorators) (default-global-decorators)
                       (fn?  global-decorators) (global-decorators)
                       :else                    global-decorators)
        story-deco-lk (coerce-kind-lookup :story-decorators story-decorators
                                          default-story-decorators-lookup)
        chain        (resolve-source-chain id body lookup)
        bodies       (map :body chain)         ; root-first
        inherited    (vec (butlast bodies))    ; root → immediate parent
        child        (:body (last chain))
        ;; ---- :compose resolution (§Total resolution order
        ;; step 3 — applied BETWEEN the parent merge and the variant-owned
        ;; values). `:compose` is a child-only directive (like `:extends`);
        ;; it is not inherited. Each entry resolves to a fragment OR a
        ;; check; an unregistered id FAILS. Composed fragments are checked
        ;; FLAT (no nested :compose/:extends).
        compose-ids  (vec (:compose child))
        composed     (mapv
                       (fn [cid]
                         (if-let [frag (frag-lookup cid)]
                           (do (check-flat-fragment! id cid frag)
                               {:kind :fragment :id cid :body frag})
                           (if-let [chk (chk-lookup cid)]
                             {:kind :check :id cid :body chk}
                             (fail! :rf.error/story-compose-unknown
                                    (str "re-frame2-story: :compose on " id
                                         " references unregistered fragment/check "
                                         cid)
                                    {:variant/id id :compose/id cid}))))
                       compose-ids)
        frag-layers  (->> composed (filter #(= :fragment (:kind %))) (mapv :body))
        composed-checks (->> composed (filter #(= :check (:kind %))) (mapv :id))
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
        ;; checks inherit root→child (incl. the child's own :checks); the
        ;; composed check-ids append after (declared order). A check is an
        ;; IDENTITY (§Checks — a failed check shows its id), so the list is
        ;; deduped first-seen: a check inherited AND re-composed (or composed
        ;; twice) rides `[:expect :checks]` exactly ONCE, so the runner
        ;; expands it into ONE grouped check record rather than N duplicates.
        checks       (-> (reduce (fn [acc layer] (into acc (:checks layer)))
                                 [] bodies)
                         (into composed-checks)
                         distinct
                         vec)
        ;; setup APPENDS: inherited (root→parent), THEN composed fragments
        ;; (declared order), THEN the variant's own setup — variant-owned
        ;; values land last (§Merge rules + §Total resolution order). Each
        ;; layer's setup is coerced through the runner's `coerce-script`:
        ;; a bare event-vector shorthand normalizes to
        ;; `[:dispatch event-vector]`, so the stored `[:world :setup]`
        ;; carries tagged steps uniformly (the same coercion `:script`
        ;; gets) — bare vectors are an authoring shorthand, never the P1
        ;; public form.
        setup-raw    (vec (concat
                            (mapcat (fn [l] (runner/coerce-script (pick-setup l))) inherited)
                            (mapcat (fn [l] (runner/coerce-script (pick-setup l))) frag-layers)
                            (runner/coerce-script (pick-setup child))))
        ;; script APPENDS through `:compose` only (never through
        ;; `:extends`): composed-fragment scripts in declared order, THEN
        ;; the child's own script (§Merge rules). Each fragment's script is
        ;; coerced the same way the child's is.
        compose-script (vec (mapcat (fn [l] (:script (normalize-scripts id l)))
                                    frag-layers))
        {child-script :script scripts :scripts} (normalize-scripts id child)
        script       (vec (concat compose-script child-script))
        ;; rf2-k23efg: `compose-script` above only ever fed the REPORTED
        ;; top-level `:script` — the runtime executes `[:world :scripts]`
        ;; (below), never the top-level `:script` slot, so a composed
        ;; fragment's `:script` was silently dropped from every actual run
        ;; (plan/explain looked right; nothing happened). Fold it into the
        ;; PRIMARY (first/auto-run) play too, exactly where it lands in the
        ;; top-level `:script`: prepended onto that play's own script when
        ;; the child declares one, or synthesized as a fresh auto-run play
        ;; when the child composes a fragment's script but authors none of
        ;; its own.
        scripts      (cond
                       (empty? compose-script) scripts
                       (seq scripts) (update-in scripts [0 :script]
                                                 #(vec (concat compose-script %)))
                       :else [{:script compose-script :auto-run? true :name nil}])
        ;; terminal assertions are CHILD-ONLY (verdict is local)
        assertions   (vec (:assertions child))
        ;; ---- args: inherited deep-merge, THEN composed fragments, THEN
        ;; the child's own — variant args win over composed/inherited (the
        ;; layers go root → fragments → child, last-wins per deep-merge).
        merge-key    (fn [k]
                       (reduce (fn [m l] (args/deep-merge m (get l k)))
                               {}
                               (concat inherited frag-layers [child])))
        ;; The `:extends`-merged variant-CHAIN args (the §Total resolution
        ;; order variant layer). This is the ONLY arg layer the plan body
        ;; carries; the ambient (global / story) + per-run (active-modes /
        ;; cell-overrides) layers live OUTSIDE the body and arrive via the
        ;; `:run-args` opt.
        variant-arg-map (merge-key :args)
        ;; Fold the ambient + per-run layers AROUND the variant layer so
        ;; the effective `arg-map` (and therefore every `[:arg key]`
        ;; substitution below, `[:world :args]` / `[:world :effective-args]`,
        ;; and the plan hash) matches `args/resolve-args` for the SAME
        ;; `:active-modes` / `:cell-overrides`. `:run-args` is the
        ;; `{:pre [global story mode] :post [cell-overrides]}` shape
        ;; `re-frame.story.args/run-arg-layers` produces: `:pre` is lower
        ;; precedence than the variant layer, `:post` higher. Absent (a pure
        ;; plan-compile / explain / render-prep with no run opts) ⇒ the
        ;; variant layer alone.
        arg-map      (if run-args
                       (args/deep-merge-all
                         (concat (:pre run-args)
                                 [variant-arg-map]
                                 (:post run-args)))
                       variant-arg-map)
        argtypes     (merge-key :argtypes)
        ;; ---- arg substitution ----
        subs!        (atom [])
        setup        (substitute-args setup-raw arg-map subs!)
        ;; An `[:assert …]` checkpoint is ILLEGAL in :setup
        ;; (spec/017 §Script step grammar). Reject at plan-compile time,
        ;; on the fully-resolved setup (inherited ⧺ composed ⧺ own), so a
        ;; misplaced verdict surfaces before any run.
        _            (reject-assert-in-setup! id setup)
        script*      (substitute-args script arg-map subs!)
        ;; The RUNTIME executes `[:world :scripts]` (the named plays from
        ;; `normalize-scripts`), NOT the top-level `:script` slot.
        ;; `normalize-scripts` ran on the RAW body, so each play's `:script`
        ;; still carries unresolved `[:arg key]` placeholders. Substitute them
        ;; against the SAME `arg-map` the top-level `:script` resolved against
        ;; (which folds the run-opts layers), so the executed plays use the
        ;; effective args the result reports — not the raw placeholder. Without
        ;; this, an `[:arg …]` in a play script would reach the dispatched
        ;; event verbatim.
        scripts*     (mapv (fn [p]
                             (update p :script substitute-args arg-map subs!))
                           scripts)
        ;; Every authored assertion atom (terminal
        ;; `:assertions` AND an in-script `[:assert …]` checkpoint, incl.
        ;; the folded `:assert-db` / `:assert-dom` steps) MUST name a
        ;; recognised :rf.assert/* id. An unknown id FAILS plan
        ;; construction here, before any run (spec/017 §Assertions). The
        ;; script is already folded (`normalize-scripts`), so one walk over
        ;; the `[:assert …]` checkpoints covers every in-script position.
        _            (reject-unknown-assertions!
                       id (script-assertion-atoms script*) assertions)
        ;; ---- view-state subscription overrides ----
        ;; `:sub-overrides` composes like `:network`: composed-fragment
        ;; override maps merge in declared order (a later fragment wins a
        ;; query key), THEN the variant chain (`ctx`, where `:extends`
        ;; context flowed down) wins on top. Override VALUES may carry
        ;; `[:arg key]` placeholders (e.g. an error message driven by a
        ;; control), so substitute AFTER merging. KEYS are exact query
        ;; vectors — `[:arg]` substitution walks them too, so a query arg
        ;; can also be control-driven.
        frag-sub-ovr (reduce (fn [m l] (merge m (:sub-overrides l))) {} frag-layers)
        ;; The RAW merged overrides BEFORE `[:arg key]` substitution — kept
        ;; on the plan (`[:world :render :sub-overrides-raw]`) so the render
        ;; path (`render-variant`) can RE-resolve the
        ;; placeholders against the POST-control effective args (a control
        ;; that drives an override value, e.g. `{[:login/error] [:arg
        ;; :message]}`, must reflect the live control). The plan-time
        ;; resolved overrides below feed validation + the run path.
        sub-overrides-raw (merge frag-sub-ovr (:sub-overrides ctx))
        sub-overrides (substitute-args sub-overrides-raw arg-map subs!)
        ;; ---- :db-seed world slot ----
        ;; The MIDDLE fidelity rung: a direct app-db state seed
        ;; (`{path → value}`). Composes through the parent chain (ctx,
        ;; deep-merged via `merge-context` through `:extends`) + composed
        ;; fragments' seed maps (later wins per declared order, then the
        ;; variant chain — the SAME ordering `:sub-overrides` / `:network`
        ;; follow). Seed values MAY carry `[:arg key]` placeholders (a
        ;; control-driven seed slice), so substitute before lowering. The
        ;; runtime seeds + schema-validates the resolved map BEFORE the
        ;; script (spec/017 §Setup); a violation FAILS the run with
        ;; `:rf.error/story-db-seed-invalid`.
        frag-db-seed (reduce (fn [m l] (merge m (:db-seed l))) {} frag-layers)
        db-seed      (substitute-args (merge frag-db-seed (:db-seed ctx)) arg-map subs!)
        ;; ---- strict-conflict composition ----
        ;; The strict-conflict override MAPS (`:fx-overrides` /
        ;; `:interceptor-overrides`) compose per-KEY: the variant chain
        ;; OWNS any key it set (variant-owned-wins), composed fragments
        ;; fill the rest, and two fragments disagreeing on a key while the
        ;; variant is silent is a HARD conflict (§Conflict resolution). The
        ;; variant-owned value is the parent-chain-merged `ctx` slot (the
        ;; variant + its ancestors, where `:extends` context flowed down).
        strict-res   (into {}
                           (map (fn [field]
                                  (let [contribs (mapv (fn [{:keys [id body]}]
                                                         {:source id
                                                          :map    (get body field)})
                                                       (filter #(= :fragment (:kind %)) composed))
                                        contribs (filterv #(map? (:map %)) contribs)]
                                    [field (resolve-strict-conflict
                                             field contribs (get ctx field) id)])))
                           strict-conflict-keys)
        unresolved   (mapcat :unresolved (vals strict-res))
        _            (when (seq unresolved)
                       (fail! :rf.error/story-compose-conflict
                              (str "re-frame2-story: variant " id
                                   " — composed fragments conflict on strict field(s) "
                                   "while the variant is silent: "
                                   (pr-str (mapv (fn [u] [(:field u) (:key u)]) unresolved))
                                   ". The variant owns its end-state — state the "
                                   "wanted value directly in the variant body "
                                   "(§Conflict resolution; no :resolve-conflicts in P1).")
                              {:variant/id id :conflicts (vec unresolved)}))
        ;; Final strict-override maps: composed contributions (the keys no
        ;; variant value owns) UNDER the variant-owned map (variant wins).
        composed-fx  (get-in strict-res [:fx-overrides :merged])
        composed-ic  (get-in strict-res [:interceptor-overrides :merged])
        ctx-fx       (merge composed-fx (:fx-overrides ctx))
        ctx-ic       (merge composed-ic (:interceptor-overrides ctx))
        ;; ---- network world slot ----
        ;; Per-route replies may carry `[:arg key]` placeholders (e.g. a
        ;; stubbed id driven by a control), so substitute before lowering.
        ;; `:network` composes through the parent chain (ctx) + composed
        ;; fragments' route maps (later wins per declared order, then the
        ;; variant chain).
        frag-network (reduce (fn [m l] (merge m (:network l))) {} frag-layers)
        network      (substitute-args (merge frag-network (:network ctx)) arg-map subs!)
        ;; `:network` and an explicit `:fx-overrides` on :rf.http/managed
        ;; both own the same fx — that is a hard conflict (§Network stubs).
        _            (check-network-fx-conflict! id ctx-fx network)
        ;; Lower the route map to the managed-stub fx override; nil when
        ;; there are no routes. The derived `:fx-overrides` merges UNDER any
        ;; non-managed author overrides (the conflict above already ruled
        ;; out a managed-targeting author override).
        network-low  (lower-network network)
        fx-overrides (merge (when network-low (:fx-overrides network-low))
                            ctx-fx)
        interceptor-overrides ctx-ic
        ;; ---- composed-fragment loaders / decorators (rf2-2g7ebs) ----
        ;; `ctx` (above) is reduced over the `:extends` chain bodies ONLY —
        ;; a composed fragment's `:loaders` / `:loaders-teardown` /
        ;; `:decorators` never reached it, so they were silently dropped
        ;; from every composed variant (the Fragment schema permits them;
        ;; the compiler just never read them). Fold frag-layers in here,
        ;; same declared-order-append discipline as `:setup` / `:script`:
        ;; composed fragments contribute FIRST, the variant-chain's own
        ;; value (`ctx`) lands after/outermost.
        frag-loaders (vec (mapcat #(:loaders %) frag-layers))
        loaders      (vec (concat frag-loaders (:loaders ctx)))
        frag-loaders-teardown (vec (mapcat #(:loaders-teardown %) frag-layers))
        loaders-teardown (vec (concat frag-loaders-teardown (:loaders-teardown ctx)))
        ;; Decorators fold in `globals -> story -> fragment -> variant`
        ;; order — composed fragments sit BETWEEN the ambient story
        ;; decorators and the variant-chain's own decorators (below).
        frag-decorators (vec (mapcat #(:decorators %) frag-layers))
        ;; ---- view arg schema + effective-args validation ----
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
        ;; ---- :sub-overrides output-schema validation ----
        ;; Each resolved override value is validated against its
        ;; subscription's OUTPUT schema (distinct from the view-args
        ;; schema above). A sub with no output schema soft-passes; a value
        ;; that violates a present schema FAILS plan construction BEFORE
        ;; render with `:rf.error/story-sub-override-invalid`. Threads the
        ;; SAME malformed-value validator the view-args path uses, so a
        ;; JVM test with no validator checks shape only at the renderer.
        sub-ovr-val  (when (seq sub-overrides)
                       (validate-sub-overrides sub-overrides sub-lookup validator-fns))
        _            (when (and sub-ovr-val (= :invalid (:status sub-ovr-val)))
                       (fail! :rf.error/story-sub-override-invalid
                              (str "re-frame2-story: variant " id
                                   " — :sub-overrides value(s) do not satisfy the "
                                   "subscription output schema(s): "
                                   (pr-str (mapv :query-v (:violations sub-ovr-val))))
                              {:variant/id id
                               :violations (:violations sub-ovr-val)}))
        ;; ---- fidelity ladder ----
        ;; Computed from the resolved world inputs so authors never type
        ;; it. `:real-setup` (events/script) > `:db-seed` (reserved world
        ;; slot) > `:sub-overrides` — the rung(s) a reviewer reads to know
        ;; which evidence the variant rests on (§View-state subscription
        ;; overrides — fidelity ladder).
        fidelity     (compute-fidelity {:setup         setup
                                        :script        script*
                                        :db-seed       db-seed
                                        :sub-overrides sub-overrides})
        ;; ---- runner requirement ----
        ;; rf2-m0cge5 finding 10: unioned across EVERY auto-run play's
        ;; script, not just `script*` (the primary/first play alone).
        ;; `[:world :scripts]` retains every play, and the runtime
        ;; auto-runs each `:auto-run? true` one in order
        ;; (`runner/auto-runnable-plays` — the single definition both
        ;; `runtime/run-phase-4!` and `runner-events/auto-run!` delegate
        ;; to). Runner-selection trusts `:required-runner` VERBATIM, so an
        ;; auto-run play OTHER than the first whose step lifts capability
        ;; (e.g. an `:assert-dom` checkpoint → `:dom`) that this slot never
        ;; reflected let `:auto` selection pick a runner that cannot
        ;; execute it — a spurious mid-run failure instead of an honest
        ;; `:cannot-run` refusal at selection time. `scripts*` is the
        ;; fully arg-substituted + folded plays vector (mirrors `script*`
        ;; for the first play by construction), so this SUBSUMES the old
        ;; single-play computation whenever the first play auto-runs (the
        ;; common case) and correctly extends it to every other auto-run
        ;; play.
        auto-run-scripts (vec (mapcat :script (runner/auto-runnable-plays scripts*)))
        required     (compute-required-runner setup auto-run-scripts assertions)
        ;; ---- source coords ----
        source       (:source child)
        platforms    (or (:platforms ctx) #{:client})
        ;; ---- full decorator stack ----
        ;; `(concat globals story fragments variant-chain)` — globals
        ;; outermost, then the parent story's `:decorators`, then composed
        ;; fragments' `:decorators` in declared order (rf2-2g7ebs —
        ;; `frag-decorators` above; previously dropped entirely), then the
        ;; variant-chain slot (`(:decorators ctx)` — the `:extends`-merged,
        ;; child-wins refs). The SAME ordered set
        ;; `decorators/collect-decorator-refs` assembles at resolve time;
        ;; folding it onto `[:world :decorators]` here makes the compiled
        ;; plan the single source of truth, so the canvas + render-variant
        ;; resolve the identical stack. Each layer falls through to `[]`
        ;; when absent — the empty-collection concat is render-transparent.
        ;; The parent story id (nil for an inline plan-map target with no
        ;; `:variant/id`, or any id outside the variant-id grammar). Bound
        ;; ONCE here — both the story-decorator lookup below AND the
        ;; `:story/id` stamp on the returned plan (rf2-xk8oz4) read this
        ;; SAME resolution, so they can never disagree about the parent.
        sid          (args/parent-story-id id)
        story-decos  (vec (when sid (story-deco-lk sid)))
        full-decos   (vec (concat global-decos
                                  story-decos
                                  frag-decorators
                                  (or (:decorators ctx) [])))
        ;; ---- effective tags (shared resolver) ----
        ;; Union `:tags` across the resolved `:extends` chain (`bodies` is
        ;; root→child and includes an inline body correctly, unlike a re-walk
        ;; via `lookup`), fall back to the parent story's `:tags` when the
        ;; chain declares none, then resolve `:!x` removal markers through the
        ;; shared `re-frame.story.tags` resolver. So the plan's `:tags` is the
        ;; EFFECTIVE set — a `:!dev` cancels an inherited `:dev` and never
        ;; leaks itself, matching what every other tag consumer computes.
        chain-tags   (reduce (fn [acc b] (into acc (:tags b))) #{} bodies)
        eff-tags     (tags/resolve-markers
                       (if (seq chain-tags)
                         chain-tags
                         (set (:tags (when sid (story-lookup sid))))))
        world        (cond-> {:setup          setup
                              :args           arg-map
                              :argtypes       argtypes
                              :effective-args eff-args
                              :scripts        scripts*
                              :platforms      platforms}
                       (some? schema)        (assoc :view-args-schema schema)
                       (some? sub-overrides) (assoc-in [:render :sub-overrides] sub-overrides)
                       ;; The resolved direct app-db seed
                       ;; (`{path → value}`, `[:arg]` placeholders
                       ;; substituted). In `:world` so it participates in
                       ;; `:plan-hash` (the seed IS part of the variant's
                       ;; identity) and feeds the `:fidelity` `:db-seed`
                       ;; rung. Present only when non-empty; a
                       ;; variant with no seed carries no slot. The runtime
                       ;; reads `[:world :db-seed]`, merges it into the
                       ;; frame's app-db BEFORE the script, and
                       ;; schema-validates the seeded app-db (spec/017
                       ;; §Setup).
                       (seq db-seed)         (assoc :db-seed db-seed)
                       ;; The fidelity ladder, computed from
                       ;; the resolved world inputs. In `:world` so it
                       ;; participates in `:plan-hash` (fingerprint's
                       ;; `plan-hash-input-keys` hashes `:world`). Present
                       ;; when non-empty; a bare render-as-mounted variant
                       ;; (no setup/seed/overrides) carries no slot.
                       (seq fidelity)        (assoc :fidelity fidelity)
                       ;; `:network` keeps the per-route reply data (source
                       ;; of truth, feeds :plan-hash via :world); the
                       ;; lowering folds its managed-stub fx
                       ;; override into the frame's `:fx-overrides` below.
                       (seq network)          (assoc :network network)
                       (seq fx-overrides)     (assoc-in [:frame :fx-overrides] fx-overrides)
                       (seq interceptor-overrides) (assoc-in [:frame :interceptor-overrides] interceptor-overrides)
                       ;; `loaders` / `loaders-teardown` already fold in
                       ;; composed-fragment contributions (rf2-2g7ebs,
                       ;; above) — `(seq …)`, not `(contains? ctx …)`, since
                       ;; the slot may now be non-empty from fragments alone
                       ;; even when the variant chain itself carries none.
                       (seq loaders)                (assoc :loaders loaders)
                       ;; Carry `:loaders-complete-when` onto
                       ;; the plan's `:world` (it inherits through `:extends`
                       ;; into `ctx`) so the inline-plan run path (which has
                       ;; no registered body) drives phase-1 loaders + the
                       ;; completion predicate from the plan alone.
                       (contains? ctx :loaders-complete-when) (assoc :loaders-complete-when (:loaders-complete-when ctx))
                       (seq loaders-teardown)       (assoc :loaders-teardown loaders-teardown)
                       ;; The FULL stack (globals + story + variant chain),
                       ;; not just `(:decorators ctx)`. Folded when non-empty
                       ;; so a bare variant carries no slot
                       ;; (render-transparent).
                       (seq full-decos)             (assoc :decorators full-decos)
                       (contains? ctx :modes)       (assoc :modes (:modes ctx))
                       (contains? ctx :substrates)  (assoc :substrates (:substrates ctx))
                       (contains? ctx :viewport)    (assoc :viewport (:viewport ctx))
                       (contains? ctx :background)  (assoc :background (:background ctx))
                       (contains? ctx :xray)        (assoc :xray (:xray ctx))
                       (contains? ctx :component)   (assoc :component (:component ctx))
                       ;; rf2-cmjly3 finding 12: the EP-0025 durable app-db
                       ;; classification, carried through `:extends` like
                       ;; every other context key. Read by
                       ;; `run-inline-phase-0!` (`runtime.cljc`) and threaded
                       ;; into `allocate-inline!` (`frames.cljc`) so an inline
                       ;; plan's `:sensitive` / `:large` declaration is
                       ;; actually applied to the elision registry instead of
                       ;; being silently discarded.
                       (contains? ctx :sensitive)   (assoc :sensitive (:sensitive ctx))
                       (contains? ctx :large)       (assoc :large (:large ctx)))
        resolved-conflicts (vec (mapcat :resolved (vals strict-res)))
        explain      {:source-chain (mapv :variant/id chain)
                      :parent-chain (mapv :variant/id (butlast chain))
                      ;; The resolved `:compose` entries, in
                      ;; declared order, classified fragment vs check (§Explain
                      ;; API — composed fragments/checks).
                      :compose      (mapv (fn [{:keys [kind id]}] {:kind kind :id id})
                                          composed)
                      ;; resolved strict conflicts: winning + losing sources +
                      ;; the rule that chose the winner (§Conflict resolution —
                      ;; explain MUST list these). Unresolved conflicts throw
                      ;; upstream, so this slot only ever lists settled ones.
                      :strict-conflicts resolved-conflicts
                      :merge        {:setup      :append-inherited-compose-own
                                     :args       :deep-merge-inherited-compose-own
                                     :checks     :inherit-then-compose
                                     :assertions :child-only
                                     ;; script appends through :compose only,
                                     ;; never through :extends (§Merge rules).
                                     :script     :compose-then-child
                                     :fx-overrides :strict-variant-owned-wins
                                     :interceptor-overrides :strict-variant-owned-wins}
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
                      ;; Per-route network stubs + the
                      ;; managed-stub fx the routes lower to (§Network
                      ;; stubs — ":network participates in explain").
                      :network      (when (seq network)
                                      {:routes       network
                                       :lowered-to   (:fx-overrides network-low)})
                      ;; View-state subscription overrides +
                      ;; the resolved fidelity ladder. `explain` surfaces
                      ;; the resolved override map (post `[:arg]`
                      ;; substitution) and the validation outcome so a
                      ;; reviewer / doc page can see exactly which subs were
                      ;; pinned, and `:fidelity` labels the evidence rung(s)
                      ;; (§View-state subscription overrides — overrides are
                      ;; visible in explain).
                      :sub-overrides (when (seq sub-overrides)
                                       {:overrides  sub-overrides
                                        ;; :status :ok here (an :invalid run
                                        ;; throws upstream); documents the
                                        ;; passing output-schema contract.
                                        :validation (when sub-ovr-val
                                                      {:status     (:status sub-ovr-val)
                                                       :violations (:violations sub-ovr-val)})})
                      ;; The resolved direct app-db seed (post
                      ;; `[:arg]` substitution). `explain` surfaces it so a
                      ;; reviewer / doc page sees exactly which app-db
                      ;; slices the variant seeds; `:fidelity` labels it the
                      ;; `:db-seed` rung. The seeded-app-db schema
                      ;; validation is a RUN-TIME check (it needs the
                      ;; frame's registered app-db schemas), so it is NOT a
                      ;; plan-compile slot here — a violation surfaces as
                      ;; `:rf.error/story-db-seed-invalid` at run time.
                      :db-seed      (when (seq db-seed) {:seed db-seed})
                      :fidelity     fidelity
                      :setup-order  setup
                      :script-order script*
                      :checks       checks
                      :assertions   assertions
                      :required-runner required
                      :platforms    platforms
                      :tags         eff-tags
                      :source       source}]
    (cond-> {:variant/id      id
             :source-chain    (mapv :variant/id chain)
             :world           world
             :script          script*
             :expect          (normalize-expect checks assertions)
             :required-runner required
             :tags            eff-tags
             :explain         explain}
      source (assoc :source source)
      ;; The parent story id (rf2-xk8oz4). `plan-hash-input-keys` includes
      ;; `:story/id` SPECIFICALLY so two variants under different stories
      ;; with otherwise-identical bodies do not collide — but this stamp
      ;; is the only site that ever populates the slot the hash reads,
      ;; and it was missing: `select-keys` silently dropped the absent
      ;; key, so `plan-hash` was actually taken over `[:world :script
      ;; :expect :required-runner :tags]` alone. Present only when a
      ;; parent resolves (an inline plan-map target with no `:variant/id`
      ;; carries no slot — render-transparent).
      sid    (assoc :story/id sid)
      ;; The RAW (pre-`[:arg]`-substitution) sub-overrides.
      ;; A SIBLING of `:world` (NOT inside it) so it stays OUT of
      ;; `plan-hash-input-keys` — it is fully derivable from the resolved
      ;; `[:world :render :sub-overrides]` + `:effective-args`, so hashing
      ;; it would be redundant noise. `render-variant` re-resolves it
      ;; against the POST-control effective args so a control that drives an
      ;; override value reflects the live control (see
      ;; `re-frame.story.render/resolve-render-sub-overrides`).
      (seq sub-overrides-raw) (assoc-in [:render-raw :sub-overrides] sub-overrides-raw)))))

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
    `:rf/props` / `:schema` props-schema slot for view-args validation.
    Defaults to the framework `:view` registrar (§View arg schemas).
  - `:validator-fns` — `{:validate (fn …) :explain (fn …)}` for
    malformed-value checking of `:effective-args`; with none supplied
    only required-key presence is checked.
  - `:fragment-lookup` / `:check-lookup` — a 1-arg fn `(id) → body` OR a
    `{id → body}` map resolving the bodies named in `:compose`.
    Default to the side-table `:fragment` / `:check` kinds.
  - `:sub-lookup` — a 1-arg fn `(sub-id) → sub-meta` OR a `{sub-id →
    sub-meta}` map resolving a subscription's registration metadata for
    its OUTPUT schema, against which `:sub-overrides` values are validated.
    Defaults to the framework `:sub` registrar.
  - `:global-decorators` / `:story-decorators` — the ambient decorator
    layers folded into the FULL `[:world :decorators]` stack:
    a global-decorators ref vector (or 0-arg fn) defaulting to
    `config/get-global-decorators`, and a `(story-id) → decorators-vec`
    lookup defaulting to the Story side-table `:story` kind. Pure tests
    thread explicit values; the live runtime uses the defaults.
  - `:run-args` — the ambient + per-run arg layers to fold AROUND the
    `:extends`-merged variant arg layer, in the
    `{:pre [global story mode] :post [cell-overrides]}` shape
    `re-frame.story.args/run-arg-layers` produces. With it the compiled
    `[:world :args]` / `[:world :effective-args]`, every `[:arg key]`
    substitution in setup/script/db-seed/network/sub-overrides, and the
    plan hash all use the SAME effective args as `args/resolve-args` for
    those `:active-modes` / `:cell-overrides`. Absent (a bare
    compile / `explain` / render-prep) ⇒ the variant arg layer alone, so
    the controls/render path layers its overrides on top of the plan-time
    effective args.

  Returns the normalized plan map: `:variant/id`, `:source-chain`,
  `:world` (incl. `:effective-args` and `:view-args-schema` when a view
  schema is on file, `[:render :sub-overrides]` + `:fidelity` when the
  variant pins view-state), `:script`, `:expect`, `:required-runner`,
  `:tags`, `:explain` (and `:source` when coords are present).

  FAILS with a structured `:rf.error/story-*` ex-info on: an unregistered
  keyword target, an unregistered `:extends` parent, an `:extends` cycle,
  a missing `[:arg key]`, an unregistered/nested `:compose` fragment, a
  silent strict-conflict between composed fragments, `:effective-args`
  that violate the view-args schema (`:rf.error/story-view-args-invalid`),
  or a `:sub-overrides` value that violates its subscription's output
  schema (`:rf.error/story-sub-override-invalid`)."
  ([target] (variant-plan target nil))
  ([target {:keys [lookup] :as opts}]
   (let [lookup-fn    (coerce-kind-lookup :lookup lookup default-lookup)
         compile-opts (select-keys opts [:view-lookup :validator-fns :sub-lookup
                                         :fragment-lookup :check-lookup
                                         :global-decorators :story-decorators
                                         :story-lookup :run-args])]
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
