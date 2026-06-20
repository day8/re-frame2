(ns re-frame.story.args
  "Args-precedence resolution. Per `002-Runtime.md` §Args resolution precedence + /spec/007-Stories.md §Args at
  three levels.

  When the rendering layer asks 'what args is this variant rendered
  with?', the runtime composes five sources in this strict order (later
  wins):

  1. **Global args** — `re-frame.story.config/global-args` (host's
     boot-time defaults: theme, locale).
  2. **Story args** — `:args` on the parent story.
  3. **Mode args** — the *active* modes' `:args` (deep-merge, not
     replace). When the caller passes a set of modes, each mode's
     `:args` deep-merges in declared order.
  4. **Variant args** — `:args` on the variant.
  5. **Cell-local overrides** — runtime overrides from controls
     (`:story/set-arg`), passed in via the `cell-overrides` arg.

  Deep-merge semantics: maps recurse; non-map values (vectors, sets,
  scalars) replace. Per `002-Runtime.md` §Args resolution precedence and Storybook's documented
  convention.

  ## Elision

  This namespace is pure — every fn is data → data. Production builds
  retain the fns (per `001-Authoring.md` §Registration macros — `run-variant`'s body survives
  with no registrations to act on, returning empty). Story callers
  invoke `resolve-args` only inside the `(when enabled? ...)`-gated
  runtime entry points; the call site disappears in production along
  with the rest of the runtime."
  (:require [re-frame.story.config     :as config]
            [re-frame.story.predicates :as pred]
            [re-frame.story.registrar  :as registrar]))

;; ---- deep-merge -----------------------------------------------------------

(defn deep-merge
  "Recursive merge: when both `a` and `b` are maps, merge them entry-by-
  entry with deep-merge on overlapping keys. Otherwise `b` replaces `a`.

  Per `002-Runtime.md` §Args resolution precedence: 'Deep-merge (per Storybook's convention) for
  nested maps; override-by-replacement for vectors.'

  - `(deep-merge nil x)` → `x`.
  - `(deep-merge {} {})` → `{}`.
  - Vectors and sets are NOT merged element-wise — they replace."
  [a b]
  (cond
    (and (map? a) (map? b))
    (reduce-kv
      (fn [m k v]
        (assoc m k (deep-merge (get m k) v)))
      a
      b)

    (nil? b) a

    :else b))

(defn deep-merge-all
  "Deep-merge a sequence of maps in order. Later wins."
  [maps]
  (reduce deep-merge {} (remove nil? maps)))

;; ---- parent-story lookup --------------------------------------------------
;;
;; Re-export from `re-frame.story.predicates` (the canonical leaf home).
;; This `args/parent-story-id` alias exists because ~9 sibling namespaces
;; (decorators, identity, runtime, canvas, controls, multi-substrate,
;; panels, schema-validation, workspace) `:require` args already and call
;; it through this alias. The alias is a thin pass-through to
;; `pred/parent-story-id`.

(def parent-story-id
  "Derive the parent story id from a variant id. Per /spec/007-Stories.md
  §Canonical id grammar, a variant id `:story.foo.bar/empty` has
  namespace `\"story.foo.bar\"` and name `\"empty\"`; the parent story
  id is the keyword named `:story.foo.bar`.

  Returns nil if `variant-id` does not match the variant-id grammar."
  pred/parent-story-id)

;; ---- mode-set materialisation --------------------------------------------

(defn- mode-args
  "Return the `:args` map registered against `mode-id`, or `{}` if the
  mode is unregistered. Stage 3 does not throw on an unregistered mode;
  callers (Stage 4 UI shell, Stage 5 play-runner) ignore missing modes.
  Tools surface the mismatch as a validation warning."
  [mode-id]
  (or (:args (registrar/handler-meta :mode mode-id)) {}))

(defn- active-modes-args
  "Compose every active mode's `:args` map by deep-merging in declared
  order. `active-modes` is a sequential collection — order is
  preserved so two modes touching the same arg-key resolve last-wins."
  [active-modes]
  (deep-merge-all (map mode-args active-modes)))

;; ---- public surface -------------------------------------------------------

(defn resolve-args
  "Materialise the effective args map for a variant render.

  Per `002-Runtime.md` §Args resolution precedence the precedence chain is:

      global-args
        < story-args
        < mode-args (in declared order, deep-merge)
        < variant-args
        < cell-overrides

  Arguments:
  - `variant-id` — the variant's keyword id.
  - `opts` (optional) — `{:active-modes [...] :cell-overrides {...}}`.
    `:active-modes` is a sequential coll of mode ids (preserve order
    so deep-merge is deterministic). `:cell-overrides` is a map of
    runtime overrides from the controls panel.

  Returns the deep-merged args map. If the variant or its parent
  story is unregistered, the corresponding layer contributes `{}`. The
  fn never throws on a missing artefact — the caller may decide whether
  that's an error (Stage 4 surfaces it inline; Stage 5 records it as
  a `:rf.error/unknown-variant` assertion)."
  ([variant-id]
   (resolve-args variant-id nil))
  ([variant-id {:keys [active-modes cell-overrides] :as _opts}]
   (let [variant-body (registrar/handler-meta :variant variant-id)
         story-id     (parent-story-id variant-id)
         story-body   (when story-id (registrar/handler-meta :story story-id))
         global       (config/get-global-args)
         story-args   (:args story-body)
         mode-args    (active-modes-args (or active-modes []))
         variant-args (:args variant-body)
         overrides    (or cell-overrides {})]
     (deep-merge-all [global story-args mode-args variant-args overrides]))))

(defn get-effective-args
  "Alias for `resolve-args` matching the `002-Runtime.md` §Args resolution precedence public-name
  call-out (`get-effective-args`). Same arguments, same return."
  ([variant-id] (resolve-args variant-id nil))
  ([variant-id opts] (resolve-args variant-id opts)))

;; ---- ambient + run arg layers ---------------------------------------------
;;
;; The plan compiler resolves the variant-CHAIN `:args` (the `:extends`-merged
;; variant body args, the §Total resolution order variant layer) itself, but
;; the AMBIENT layers (global-args, parent-story `:args`) and the per-RUN
;; layers (active-modes' `:args`, the controls-panel `:cell-overrides`) live
;; outside the variant body. `resolve-args` folds all five (`global <
;; story-args < mode-args < variant-args < cell-overrides`); the plan compiler
;; needs the four NON-variant layers, split around its own variant layer, so
;; its `arg-map` — and therefore every `[:arg key]` substitution in
;; setup/script/db-seed/network/sub-overrides, plus `[:world :effective-args]`
;; and the plan hash — uses the SAME effective args the run/render surfaces
;; report. This keeps a cell override / active mode reporting the same
;; effective-args the executed plan substitutes.

(defn run-arg-layers
  "Return the AMBIENT + per-RUN arg layers (everything `resolve-args` folds
  AROUND the variant layer), split into the `:pre`-variant and `:post`-variant
  groups the plan compiler deep-merges around its own `:extends`-merged
  variant `arg-map`:

      :pre  [global-args story-args mode-args]   ; lower precedence than variant
      :post [cell-overrides]                      ; higher precedence than variant

  So the compiler's effective args are
  `(deep-merge-all (concat pre [variant-chain-args] post))`, which equals
  `resolve-args` for the SAME `:active-modes` / `:cell-overrides` whenever the
  variant carries no `:extends` (and is the strictly-more-correct,
  extends-aware variant layer when it does — the plan is the single source of
  truth for the variant layer; this fn supplies only the layers it cannot see).

  `opts` is `{:active-modes [...] :cell-overrides {...}}` — the SAME shape
  `resolve-args` takes. Pure aside from the registrar/config reads `resolve-args`
  already performs."
  ([variant-id] (run-arg-layers variant-id nil))
  ([variant-id {:keys [active-modes cell-overrides] :as _opts}]
   (let [story-id   (parent-story-id variant-id)
         story-body (when story-id (registrar/handler-meta :story story-id))]
     {:pre  [(config/get-global-args)
             (:args story-body)
             (active-modes-args (or active-modes []))]
      :post [(or cell-overrides {})]})))
