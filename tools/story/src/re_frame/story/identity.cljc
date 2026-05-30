(ns re-frame.story.identity
  "Snapshot-identity. Per IMPL-SPEC §5.6 + spec/007 §Variant snapshot
  identity.

  Every variant has a stable **snapshot identity** — a content hash
  over the canonicalised `(variant × resolved-args × decorators ×
  loaders × substrate × modes × view-schema-digest)` tuple. Visual-
  regression services key against `[variant-id content-hash]` — when
  the body changes, the hash changes; when it doesn't, the hash is
  stable across hosts and runs.

  ## Migration path (rf2-5x1wt.3 — single canonical primitive)

  The canonical projection + hashing that used to live here
  (`canonical-form` / `content-hash`) has been **folded into the single
  fingerprinting primitive** `re-frame.story.fingerprint`, per
  tools/story/spec/017-Testing-Story.md §Canonicalization. There is now
  exactly one canonical path; `:plan-hash`, `:run-hash`, determinism, and
  semantic-diff share it with snapshot identity.

  This ns keeps its public surface (`snapshot-tuple`, `snapshot-identity`,
  and the back-compat `canonical-form` / `content-hash` re-exports) so the
  shipping watch-mode + visual-regression call sites keep working
  unchanged. Snapshot identity hashes its tuple through the fingerprint
  `content-hash` low primitive — the **strip-free** ordered hash.

  The rf2-5x1wt.3 *fold* was a pure relocation of the hashing code (no
  value change). The later rf2-lvrqa *soundness* fix is a deliberate
  canonical-form REVISION: it type-tags the canonical form (so `{}` ≠ `[]`,
  `{:k 1}` ≠ `[:k 1]`) and folds functions to a stable sentinel, and BUMPS
  `canonical-version` `:rf/snapshot-canonical-v1` → `:rf/snapshot-canonical-v2`.
  Because the version is the first hashed slot, the snapshot content-hash
  VALUE changes. External visual-regression baselines therefore re-capture
  on their next run (the bump is the re-stamp signal); there are no in-repo
  stored hash fixtures to migrate. The hashing CODE still lives in the
  single fingerprint primitive — this ns only re-exports it.

  The volatile-field strip + `:variant-id` → `:variant/id` reconciliation
  live in the fingerprint `canonicalize` path that backs determinism /
  semantic-diff / `:plan-hash` / `:run-hash`; they do NOT touch the
  strip-free snapshot `content-hash`, so the snapshot tuple keeps its
  `:variant-id` slot exactly as before. The deliberate path for any *new*
  consumer is to call `re-frame.story.fingerprint/canonicalize` (or
  `content-hash` / `canonical-hash` / `plan-hash` / `run-hash`) directly
  rather than these re-exports.

  ## What's in the hash

  Per spec/007 §Variant snapshot identity (lines 424-429) the hash
  includes:

  - Variant id
  - `:events` setup dispatches and the `:play-script` / `:plays` play
    surfaces (in order) — rf2-0wrud removed the legacy `:play` slot
  - `:loaders` / `:loaders-complete-when` / `:loaders-teardown`
    (in declared order; canonicalised)
  - Effective `:args` (post-`:extends`-merge with story + active modes)
  - Variant `:decorators` id sequence + their ref-args
  - Variant `:tags` set
  - Variant `:viewport` / `:background` visual chrome
  - Variant `:args->events`, `:platforms`, `:substrates` targeting
  - Parent story `:component` id
  - Parent story `:decorators`
  - Parent story `:tags`
  - The *registered* schema digest of the view (per spec/011
    §`:rf/schema-digest`) — sourced via the `:schemas/app-schemas-digest`
    late-bind hook so a schema change invalidates the snapshot identity.
    When the schemas artefact is absent from the classpath the digest is
    nil and the slot still participates in the hash (nil is stable).
  - Active substrate (when computing per-substrate identity)
  - Active modes (when computing per-mode identity)

  ## Hash function

  IMPL-SPEC §5.6 specifies `sha-256` of a transit-serialised canonical
  form. Stage 3 implements a **portable** hash function: a stable
  string serialisation (deterministic key order; sets/vectors written
  with stable order) hashed with `hash` (JVM `clojure.lang.Util/hasheq`,
  CLJS `cljs.core/hash`).

  Trade-off vs sha-256: `hash` is 32-bit and faster, but only ~4-billion
  states. For visual-regression keying that's enough provided the
  caller dedupes by `[variant-id content-hash]` (the variant id is
  unique; the hash is per-variant per-cell). The sha-256 path is left
  as a Stage 6+ extension when an external service needs cryptographic
  collision-resistance.

  The canonical-form keyword `canonical-version`
  (now `:rf/snapshot-canonical-v2`, bumped by rf2-lvrqa) is the first slot
  of the hashed structure, so a canonical-form revision bumps the version
  and old baselines are detectably stale rather than silently mis-compared."
  (:require [re-frame.late-bind         :as late-bind]
            [re-frame.story.args        :as args]
            [re-frame.story.fingerprint :as fingerprint]
            [re-frame.story.registrar   :as registrar]))

;; ---- canonicalisation + hash (folded into fingerprint) -------------------
;;
;; rf2-5x1wt.3 — the canonical projection + the 8-char-hex content hash
;; now live in the single primitive `re-frame.story.fingerprint`. These
;; vars are thin re-exports so the shipping watch-mode + visual-regression
;; call sites (and the JVM/CLJS runtime tests that assert on them) keep
;; their import surface. New consumers should call the fingerprint ns
;; directly. The rf2-lvrqa canonical-version v2 bump re-stamps the hash
;; value (type tags + fn sentinel); the re-export wiring is unchanged.

(def canonical-form
  "Back-compat re-export of `re-frame.story.fingerprint/canonical-form`.
  The single canonical projection now lives in the fingerprint ns; this
  alias keeps the shipping import surface. New code should call
  `re-frame.story.fingerprint/canonicalize` (which also strips volatile
  fields) directly."
  fingerprint/canonical-form)

(def content-hash
  "Back-compat re-export of `re-frame.story.fingerprint/content-hash`.
  The single content-hash primitive now lives in the fingerprint ns; this
  alias keeps the shipping import surface. The hash VALUE was re-stamped by
  the rf2-lvrqa canonical-form revision (`canonical-version`
  `:rf/snapshot-canonical-v2`: structural type tags + the `:rf/opaque-fn`
  fn sentinel); the re-export wiring is unchanged."
  fingerprint/content-hash)

;; ---- snapshot tuple -------------------------------------------------------

(defn- variant-body-slice
  "Return the slice of the variant body that contributes to the snapshot
  identity. Excludes runtime-environmental keys (`:source` coords) and
  Stage 4+ slots that don't yet exist (kept for forward compatibility).

  Per spec/007 §Variant snapshot identity the variant-level `:decorators`
  participate in the hash — watch-mode auto-rerun keys off this identity
  so a decorator-only edit MUST perturb it.

  ## Slice membership (rf2-bgwnf)

  A key belongs in the slice iff editing it changes the variant's
  *settled rendered/tested state* — the thing a visual-regression
  baseline or watch-mode rerun must invalidate on. Audited 2026-05-21:

  Included:
  - `:play-script` / `:plays` — the post-render interaction sequences.
    A play edit changes the asserted/driven state, so it MUST perturb
    the hash. (rf2-0wrud removed the legacy `:play` slot; this slice
    tracked `:play`, which silently no longer existed — the bug
    rf2-bgwnf fixes.)
  - `:events` — pre-render setup dispatches.
  - `:loaders` / `:loaders-complete-when` / `:loaders-teardown` — async
    setup + the symmetric teardown; both shape the frame's settled state.
  - `:decorators` / `:tags` — composition + classification.
  - `:viewport` / `:background` — visual chrome that lands IN the
    screenshot, so a baseline must invalidate when they change.
  - `:args->events` / `:platforms` / `:substrates` — derivation +
    targeting that change what is rendered.

  Excluded (documented, not an oversight):
  - `:args` — captured via `:effective-args` in `snapshot-tuple` (post-
    `:extends`-merge + active-mode merge), so reproducing it here would
    double-count.
  - `:argtypes` — controls-panel metadata only; it shapes the controls
    UI, not the rendered snapshot. The args it constrains are already
    captured via `:effective-args`.
  - `:modes` — the variant's *available* mode refs. The *active* mode
    context is captured by `snapshot-tuple` (`:active-modes` slot +
    merged into `:effective-args`); the available-mode SET does not
    change the snapshot for a given active-mode context.
  - `:dispatch-console?` / `:xray` — dev-tooling affordances; no effect
    on the settled rendered state.
  - `:doc` / `:source` — prose + coords; runtime-environmental.
  - `:extends` — resolved away into `:effective-args` before hashing."
  [variant-id]
  (let [body (registrar/handler-meta :variant variant-id)]
    (when body
      (select-keys body
                   [:events :play-script :plays
                    :loaders :loaders-complete-when :loaders-teardown
                    :tags :decorators :args->events :platforms :substrates
                    :viewport :background]))))

(defn- story-body-slice
  "Story-level slice that the variant inherits for identity purposes.
  Per IMPL-SPEC §5.6 the parent story's `:component` id and
  `:decorators` are part of the variant's identity."
  [variant-id]
  (let [story-id (args/parent-story-id variant-id)
        body     (when story-id (registrar/handler-meta :story story-id))]
    (when body
      (select-keys body [:component :decorators :tags]))))

(defn- view-schema-digest
  "Return the *registered* schema digest of the view per spec/007 §Variant
  snapshot identity (lines 424-429) and spec/011 §`:rf/schema-digest`.

  Sourced via the `:schemas/app-schemas-digest` late-bind hook so this
  ns does not statically `:require` the schemas artefact — in builds
  where schemas is absent from the classpath the lookup returns nil
  and the digest slot still participates in the hash (nil is stable
  across runs). When schemas IS present, registering a new app-schema
  or mutating an existing one perturbs the digest and therefore the
  variant snapshot identity — exactly the invalidation visual-regression
  baselines need on schema changes."
  []
  (when-let [f (late-bind/get-fn :schemas/app-schemas-digest)]
    (f)))

(defn snapshot-tuple
  "Build the canonical tuple that feeds `content-hash` for a variant.
  Returns a map.

  Arguments:
  - `variant-id` — keyword variant id.
  - `opts` (optional) — `{:active-modes [...] :cell-overrides {...}
                          :substrate <keyword>}`.

  The tuple captures everything the visual-regression service treats
  as identity-determining. A change to ANY of these fields produces a
  fresh hash; otherwise the hash is stable across runs.

  Per spec/007 §Variant snapshot identity the tuple includes the view's
  registered schema-digest — sourced via the `:schemas/app-schemas-digest`
  late-bind hook — so a schema change on the view invalidates the
  visual-regression baseline."
  ([variant-id] (snapshot-tuple variant-id nil))
  ([variant-id {:keys [active-modes cell-overrides substrate] :as _opts}]
   (let [variant      (variant-body-slice variant-id)
         story        (story-body-slice variant-id)
         effective    (args/resolve-args variant-id
                                         {:active-modes   active-modes
                                          :cell-overrides cell-overrides})
         schema-digest (view-schema-digest)]
     {:rf/snapshot-canonical :rf/snapshot-canonical-v1
      :variant-id            variant-id
      :variant               variant
      :story                 story
      :effective-args        effective
      :view-schema-digest    schema-digest
      ;; rf2-z86vu — `:active-modes` already perturbs identity via
      ;; `:effective-args` (mode args are merged in by `resolve-args`),
      ;; so this top-level slot is intentionally belt-and-braces: it
      ;; keeps the mode-id SET part of the identity so two distinct modes
      ;; that happen to register identical args (and therefore identical
      ;; `:effective-args`) still produce DIFFERENT snapshot hashes. The
      ;; visual-regression baseline is keyed per active-mode context, so
      ;; the mode id — not just its resolved args — is identity-bearing.
      ;; Do not "simplify" this away. (Contrast `:cell-overrides`, which
      ;; perturbs identity only via `:effective-args` — overrides carry
      ;; no id, so there is no analogous collision risk.)
      :active-modes          (vec (or active-modes []))
      :substrate             substrate})))

(defn snapshot-identity
  "Public entry point per IMPL-SPEC §3.2 — return the snapshot-identity
  record for `(variant × active-modes × cell-overrides × substrate)`.

  Returns:

      {:variant-id   <variant-id>
       :active-modes [<mode-id> ...]
       :substrate    <substrate-id>
       :content-hash \"<8-char hex>\"}

  Stable across hosts (JVM and CLJS produce the same hex hash for the
  same canonical inputs). Visual-regression services key against
  `[variant-id content-hash]`."
  ([variant-id] (snapshot-identity variant-id nil))
  ([variant-id {:keys [active-modes substrate] :as opts}]
   (let [tuple (snapshot-tuple variant-id opts)
         hex   (content-hash tuple)]
     {:variant-id    variant-id
      :active-modes  (vec (or active-modes []))
      :substrate     substrate
      :content-hash  hex})))
