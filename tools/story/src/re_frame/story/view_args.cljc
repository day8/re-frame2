(ns re-frame.story.view-args
  "The ONE shared view-args-schema resolver.

  ## The invariant this enforces

  The compiled variant-plan is the single source of truth. Every Story
  consumer that wants a variant's view-args (props) schema — the
  schema-validation panel, the controls-panel argtype derivation, the
  save-variant snapshot validator — reads it THROUGH this resolver, which
  compiles the plan (`re-frame.story.plan/variant-plan`) and returns the
  schema the compiler wrote to `[:world :view-args-schema]`. No consumer
  reads the bare registrar body and re-derives `:component` /
  `:rf/props` / `:schema` by hand.

  This matters because `:component` (the view whose schema we resolve) can
  be `:extends`-inherited or `:compose`-d in. The plan compiler resolves
  `:component` through the parent chain (`ctx`); a consumer that reads
  `(:component variant-body)` off the bare side-table body would miss an
  inherited / composed component and silently resolve no schema. Routing
  every consumer through this single resolver keeps them in agreement and
  reading the resolved component, not the bare body.

  ## Key resolution (the canonical first-match order)

  The first-match key picker (`malli-schema/view-args-schema` over
  `[:rf/props :schema]`) lives in the pure leaf `re-frame.story.malli-
  schema`, shared with the plan compiler (which uses it to write
  `[:world :view-args-schema]`). `:rf/props` (canonical) wins over
  `:schema` (the alternative location); there is no composition and no
  `:spec` slot. See that ns + the migration §M-54 record.

  ## Memoization

  The default (production) path memoizes the compiled schema per
  `[variant-id mutation-tick]`, where `mutation-tick` is the registrar's
  monotonic write counter (`re-frame.story.registrar/current-mutation-
  tick`). Reading the compiled plan per render is therefore cheap: between
  two registrar writes, repeated calls for the same variant return the
  cached schema in O(1); any registration / hot-reload bumps the tick and
  invalidates the slot. The schema does not depend on the runtime
  effective-args (control overrides / active modes) — only the variant's
  registered body + its `:extends`/`:compose` chain — so the variant-id is
  the only run-varying part of the key.

  ## Purity / elision

  The resolver is pure data → data over the plan. The whole ns lives
  behind the §6 elision contract along with the rest of the Story runtime;
  the default lookup reads the Story side-table, which is empty in a
  production bundle."
  (:require [re-frame.story.malli-schema :as msu]
            [re-frame.story.plan         :as plan]
            [re-frame.story.registrar    :as registrar]))

;; ---- key resolution (re-exported from the leaf for the in-ns surface) ----
;;
;; The picker itself lives in `malli-schema` (no plan dep) so the plan
;; compiler and this resolver share it without a require cycle. Re-export
;; here so callers that already speak `view-args/view-args-schema` keep one
;; import.

(def view-args-schema-keys
  "Re-export of `malli-schema/view-args-schema-keys` — the canonical
  first-match key order `[:rf/props :schema]`."
  msu/view-args-schema-keys)

(def view-args-schema
  "Re-export of `malli-schema/view-args-schema` — the low-level key picker
  over an already-resolved `view-meta` map. Consumers that have a
  variant-id (not a pre-resolved view-meta) call `compiled-view-args-
  schema` instead, which routes through the compiled plan so an
  `:extends`-inherited / `:compose`-d `:component` resolves."
  msu/view-args-schema)

;; ---- compiled-plan resolver (the ONE shared entry) -----------------------

(defonce ^:private cache
  ;; {variant-id schema} valid for one `mutation-tick`. A single tick slot,
  ;; not a {tick → {id → schema}} nest: every registrar write invalidates
  ;; ALL cached schemas (a hot-reloaded view changes its props schema), so
  ;; carrying stale-tick entries buys nothing. Mirrors the registrar's
  ;; `variants-with-tags-cache` shape.
  (atom {:tick -1 :by-id {}}))

(defn- compile-schema
  "Compile `variant-id` into a plan (default side-table lookup) and return
  the `[:world :view-args-schema]` it wrote, or nil. A plan-construction
  failure (an unregistered variant, a missing `[:arg]`, a malformed view
  input under a registered validator, …) yields nil rather than throwing —
  this resolver is a best-effort tooling read (the controls + schema panels
  render gracefully with no schema), not the authoritative compile path
  that fails registration."
  [variant-id]
  (try
    (-> (plan/variant-plan variant-id) :world :view-args-schema)
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn compiled-view-args-schema
  "Return the view-args (props) schema for a REGISTERED `variant-id`,
  resolved off the COMPILED variant-plan (`[:world :view-args-schema]`) —
  the single source of truth. Routes through `plan/variant-plan` with the
  DEFAULT side-table lookup so an `:extends`-inherited or `:compose`-d
  `:component` resolves the same way the runner / render / docs paths see
  it. Returns nil when the variant is unregistered, declares no
  `:component`, or its component view carries no `:rf/props` / `:schema`
  slot.

  Memoized per `[variant-id mutation-tick]` (see the ns docstring): cheap
  to call per render. The result is identical to
  `(get-in (plan/variant-plan variant-id) [:world :view-args-schema])` but
  does not recompile the plan on every call between two registrar writes."
  [variant-id]
  (let [tick (registrar/current-mutation-tick)
        c    @cache]
    (if (and (= tick (:tick c)) (contains? (:by-id c) variant-id))
      (get (:by-id c) variant-id)
      (let [schema (compile-schema variant-id)]
        (swap! cache
               (fn [{prev-tick :tick prev-by :by-id}]
                 (if (= prev-tick tick)
                   {:tick tick :by-id (assoc prev-by variant-id schema)}
                   {:tick tick :by-id {variant-id schema}})))
        schema))))
