(ns re-frame.story.args
  "Args-precedence resolution. Per IMPL-SPEC §5.2 + spec/007 §Args at
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
  scalars) replace. Per IMPL-SPEC §5.2 and Storybook's documented
  convention.

  ## Elision

  This namespace is pure — every fn is data → data. Production builds
  retain the fns (per IMPL-SPEC §6.3 — `run-variant`'s body survives
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

  Per IMPL-SPEC §5.2: 'Deep-merge (per Storybook's convention) for
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
;; Re-export from `re-frame.story.predicates` so existing call sites keep
;; their `args/parent-story-id` shape. The canonical definition lives in
;; the leaf namespace; this is a transitional alias for the rest of the
;; tree that hasn't yet migrated.

(def parent-story-id
  "Derive the parent story id from a variant id. Per spec/007
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

  Per IMPL-SPEC §5.2 the precedence chain is:

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
  "Alias for `resolve-args` matching the IMPL-SPEC §5.2 public-name
  call-out (`get-effective-args`). Same arguments, same return."
  ([variant-id] (resolve-args variant-id nil))
  ([variant-id opts] (resolve-args variant-id opts)))
