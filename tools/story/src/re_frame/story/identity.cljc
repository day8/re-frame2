(ns re-frame.story.identity
  "Snapshot-identity. Per `002-Runtime.md` §Snapshot-identity computation + /spec/007-Stories.md §Variant snapshot
  identity.

  Every variant has a stable **snapshot identity** — a content hash
  over the canonicalised `(variant × resolved-args × decorators ×
  loaders × substrate × modes × view-schema-digest)` tuple. Visual-
  regression services key against `[variant-id content-hash]` — when
  the body changes, the hash changes; when it doesn't, the hash is
  stable across hosts and runs.

  ## Single canonical primitive

  The canonical projection + hashing lives in the single fingerprinting
  primitive `re-frame.story.fingerprint`, per
  tools/story/spec/017-Testing-Story.md §Canonicalization. There is
  exactly one canonical path; `:plan-hash`, `:run-hash`, determinism, and
  semantic-diff share it with snapshot identity.

  This ns exposes its public surface (`snapshot-tuple`, `snapshot-identity`)
  for the watch-mode + visual-regression call sites. Snapshot identity
  hashes its tuple through the fingerprint `content-hash` low primitive —
  the **strip-free** ordered hash.

  The canonical form is sound: it type-tags the canonical form (so `{}` ≠
  `[]`, `{:k 1}` ≠ `[:k 1]`) and folds functions to a stable sentinel. The
  canonical-version tag `re-frame.story.fingerprint/canonical-version`
  (`:rf/snapshot-canonical-v2`) is the single source of truth for the
  version: `fingerprint/hash-canonical` prepends it as the first hashed
  slot of EVERY hash (`content-hash`, `canonical-hash`, `plan-hash`,
  `run-hash`), so a canonical-form revision changes the snapshot
  content-hash VALUE. External visual-regression baselines re-capture on
  their next run (a version bump is the re-stamp signal); there are no
  in-repo stored hash fixtures. The hashing CODE lives in the single
  fingerprint primitive; this ns hashes its snapshot tuple through it. The
  snapshot tuple also carries a `:rf/snapshot-canonical` data slot, which
  reads its value straight from `fingerprint/canonical-version` (it is NOT
  a second, independently-versioned marker — it tracks the one tag).

  The volatile-field strip + `:variant-id` → `:variant/id` reconciliation
  live in the fingerprint `canonicalize` path that backs determinism /
  semantic-diff / `:plan-hash` / `:run-hash`; they do NOT touch the
  strip-free snapshot `content-hash`, so the snapshot tuple keeps its
  `:variant-id` slot. The path for any *new* consumer is to call
  `re-frame.story.fingerprint/canonicalize` (or `content-hash` /
  `canonical-hash` / `plan-hash` / `run-hash`) directly.

  ## What's in the hash

  Per /spec/007-Stories.md §Variant snapshot identity the hash
  includes:

  - Variant id
  - `:events` setup dispatches and the `:play-script` / `:plays` play
    surfaces (in order)
  - `:loaders` / `:loaders-complete-when` / `:loaders-teardown`
    (in declared order; canonicalised)
  - Effective `:args` (post-`:extends`-merge with story + active modes)
  - Variant `:decorators` id sequence + their ref-args
  - The variant's EFFECTIVE tag set — inheritance (`:extends`-chain union
    / parent-story fallback) then `:!x` removal-marker resolution, via the
    shared `re-frame.story.tags` resolver. This single slot subsumes BOTH
    the variant's own tags AND the parent story's tags (as fallback), so
    the identity keys off the variant's resolved classification exactly as
    every other tag consumer reads it — an `:extends`-parent tag edit
    perturbs the hash, a `:!x` marker that cancels an inherited tag does
    NOT, and raw `:!x` authoring syntax never reaches the hash.
  - Variant `:viewport` / `:background` visual chrome
  - Variant `:args->events`, `:platforms`, `:substrates` targeting
  - Parent story `:component` id
  - Parent story `:decorators`
  - The *registered* schema digest of the view (per spec/011
    §`:rf/schema-digest`) — sourced via the `:schemas/app-schemas-digest`
    late-bind hook so a schema change invalidates the snapshot identity.
    When the schemas artefact is absent from the classpath the digest is
    nil and the slot still participates in the hash (nil is stable).
  - Active substrate (when computing per-substrate identity)
  - Active modes (when computing per-mode identity)

  ## Hash function

  `002-Runtime.md` §Snapshot-identity computation specifies `sha-256` of a transit-serialised canonical
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

  The canonical-form version tag `fingerprint/canonical-version`
  (`:rf/snapshot-canonical-v2`) is prepended by
  `fingerprint/hash-canonical` as the first slot of the hashed structure, so
  a canonical-form revision bumps the version and old baselines are
  detectably stale rather than silently mis-compared."
  (:require [re-frame.late-bind         :as late-bind]
            [re-frame.story.args        :as args]
            [re-frame.story.fingerprint :as fingerprint]
            [re-frame.story.registrar   :as registrar]
            [re-frame.story.tags        :as tags]))

;; ---- snapshot tuple -------------------------------------------------------

(defn- variant-body-slice
  "Return the slice of the variant body that contributes to the snapshot
  identity. Excludes runtime-environmental keys (`:source` coords) and
  Stage 4+ slots that don't yet exist (kept for forward compatibility).

  Per /spec/007-Stories.md §Variant snapshot identity the variant-level `:decorators`
  participate in the hash — watch-mode auto-rerun keys off this identity
  so a decorator-only edit MUST perturb it.

  ## Slice membership

  A key belongs in the slice iff editing it changes the variant's
  *settled rendered/tested state* — the thing a visual-regression
  baseline or watch-mode rerun must invalidate on.

  Included:
  - `:play-script` / `:plays` — the post-render interaction sequences.
    A play edit changes the asserted/driven state, so it MUST perturb
    the hash.
  - `:events` — pre-render setup dispatches.
  - `:loaders` / `:loaders-complete-when` / `:loaders-teardown` — async
    setup + the symmetric teardown; both shape the frame's settled state.
  - `:decorators` — composition.
  - `:viewport` / `:background` — visual chrome that lands IN the
    screenshot, so a baseline must invalidate when they change.
  - `:args->events` / `:platforms` / `:substrates` — derivation +
    targeting that change what is rendered.

  Excluded (documented, not an oversight):
  - `:args` — captured via `:effective-args` in `snapshot-tuple` (post-
    `:extends`-merge + active-mode merge), so reproducing it here would
    double-count.
  - `:tags` — captured via `:effective-tags` in `snapshot-tuple`, the
    variant's RESOLVED tag set (`:extends`/story inheritance + `:!x`
    marker resolution, through the shared `re-frame.story.tags` resolver).
    Selecting the RAW child `:tags` here would (a) leak `:!x` authoring
    syntax into the hash, (b) miss `:extends`-chain tag inheritance, and
    (c) diverge from every other tag consumer. The effective slot is the
    single tag input; the raw child + story tag slots are NOT hashed.
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
                    :decorators :args->events :platforms :substrates
                    :viewport :background]))))

(defn- story-body-slice
  "Story-level slice that the variant inherits for identity purposes.
  Per `002-Runtime.md` §Snapshot-identity computation the parent story's
  `:component` id and `:decorators` are part of the variant's identity.

  The parent story's `:tags` are NOT selected here — they reach the hash
  (as a fallback default) through the variant's `:effective-tags` slot in
  `snapshot-tuple`. Hashing the story's RAW `:tags` here as well would
  double-count and re-introduce spurious invalidation: a story-tag edit
  that does NOT change a given variant's effective set (because the variant
  declares its own tags, so story tags are not inherited) would still
  perturb that variant's hash."
  [variant-id]
  (let [story-id (args/parent-story-id variant-id)
        body     (when story-id (registrar/handler-meta :story story-id))]
    (when body
      (select-keys body [:component :decorators]))))

(defn- view-schema-digest
  "Return the *registered* schema digest of the view per /spec/007-Stories.md §Variant
  snapshot identity and spec/011 §`:rf/schema-digest`.

  Sourced via the `:schemas/app-schemas-digest` late-bind hook so this
  ns does not statically `:require` the schemas artefact — in builds
  where schemas is absent from the classpath the lookup returns nil
  and the digest slot still participates in the hash (nil is stable
  across runs). When schemas IS present, registering a new app-schema
  or mutating an existing one perturbs the digest and therefore the
  variant snapshot identity — exactly the invalidation visual-regression
  baselines need on schema changes.

  EP-0002 — app-db schemas are CONTEXT-REQUIRED FRAME-LOCAL,
  so the digest hook resolves a TARGET frame; absence is
  `:rf.error/no-frame-context`, NEVER a synthesised `:rf/default`. Story
  computes snapshot identity for a specific variant, so the variant frame
  IS the explicit target: pass `target-frame-id` (the variant id) through
  the hook's keyword-frame-id arity. The digest is computed against THAT
  frame's registered app-db schema set (the stable empty-set digest when
  the variant frame holds none / is not yet allocated — the digest still
  participates in the hash and stays stable across runs)."
  [target-frame-id]
  (when-let [f (late-bind/get-fn :schemas/app-schemas-digest)]
    (f target-frame-id)))

(defn- effective-tags-slice
  "Return `variant-id`'s EFFECTIVE tag set via the shared
  `re-frame.story.tags` resolver — the SAME resolution every other tag
  consumer (membership `variants-with-tags`, docs / sidebar chips, the tag
  filter, plan compilation, snapshot UI state) reads through. Folds the
  `:extends`-chain union (or parent-story fallback) then `:!x` removal-
  marker resolution, so:

  - an `:extends`-parent tag edit perturbs the identity (RAW child `:tags`
    could not see it),
  - a `:!x` marker that cancels an inherited tag does NOT perturb it, and
  - raw `:!x` authoring syntax never reaches the hash.

  Reads live side-tables via the registrar (`:variant` + `:story`
  registrations). Returns a set; `fingerprint/content-hash` canonicalises
  it with a stable order."
  [variant-id]
  (tags/effective-tags variant-id
                       {:variant (registrar/registrations :variant)
                        :story   (registrar/registrations :story)}))

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

  Per /spec/007-Stories.md §Variant snapshot identity the tuple includes the view's
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
         ;; EP-0002 — the variant frame is the explicit
         ;; target for the frame-local app-db schema digest (no ambient
         ;; resolution / no `:rf/default` synthesis).
         schema-digest (view-schema-digest variant-id)]
     {:rf/snapshot-canonical fingerprint/canonical-version
      :variant-id            variant-id
      :variant               variant
      :story                 story
      :effective-args        effective
      ;; The variant's RESOLVED tag set (shared `re-frame.story.tags`
      ;; resolver — `:extends`/story inheritance + `:!x` marker removal),
      ;; the single tag input to the hash. Raw child + story `:tags` slots
      ;; are intentionally NOT selected in the body slices; this subsumes
      ;; them. Keeps watch-mode drift + visual-regression identity keyed
      ;; off the SAME effective classification every other consumer reads.
      :effective-tags        (effective-tags-slice variant-id)
      :view-schema-digest    schema-digest
      ;; `:active-modes` already perturbs identity via
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
  "Public entry point per `002-Runtime.md` §Programmatic API — return the snapshot-identity
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
         hex   (fingerprint/content-hash tuple)]
     {:variant-id    variant-id
      :active-modes  (vec (or active-modes []))
      :substrate     substrate
      :content-hash  hex})))
