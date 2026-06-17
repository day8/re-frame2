(ns re-frame.resources.trace-egress
  "OFF-BOX trace-row egress projection for the resource + mutation trace family
  (rf2-8x0gfa / EP-0015).

  ## Why this namespace exists

  `re-frame.resources.scope-registry/project-scope-resolved-egress` (rf2-84l82t)
  projects ONE row — `:rf.resource/scope-resolved` — redacting the resolver's
  resolved `:input-values` / `:scope`. But the broader resource/mutation trace
  family (`:rf.resource/*` + `:rf.mutation/*` rows emitted from
  `re-frame.resources.events` / `…timers` / `…mutation-events`) copies the SAME
  owner-local SCOPED KEYS into trace TAGS:

    - `:resource/key`  — a single scoped-key vector `[scope resource-id params]`
      (`:rf.resource/cache-hit` / `deduped` / `owner-attached` / `work-started`
      / `fetch-started` / `poll-fired` / `refetch-decision` / the `gc-scheduled`
      / `stale-scheduled` / `poll-scheduled` timer rows / …);
    - `:resource/keys` / `:matched` / `:removed` / `:keys` / `:exempt` /
      `:committed` / `:restored` / `:conflicted` / `:refetched` /
      `:restored-keys` / `:conflicted-keys` / `:refetched-keys` — a VECTOR of
      scoped-key vectors (`:rf.resource/invalidated` / `refetch-decision` /
      `revalidate-scan` / the `:rf.mutation/succeeded` settlement / the
      `:rf.mutation/optimistic-rolled-back` rollback);
    - `:dispositions` — the optimistic-rollback per-key maps each embedding a
      `:resource/key` (EP-0019 §Surfacing to tooling).

  A generic value-path egress walk (the central trace-egress chokepoint) is
  STRUCTURALLY BLIND to these once copied into trace tags — a frame's declared
  `:sensitive`/`:large` app-db PATHS do not match a resolver-owned scoped key's
  embedded scope/params (EP-0015; the resource trace family is an egress record
  set, Spec 015 §10 / Derivations §Resources expose process nodes). So the
  resource family owns ONE family-level trace-row egress projector — the
  slot-keyed analogue of `project-scope-resolved-egress` — consumed off-box
  through the late-bound `:resources/project-resource-trace-egress` hook the
  epoch tool-pair consults; on-box listeners keep the raw evidence (the leak is
  at off-box / epoch / MCP egress, not the local listener).

  ## Why a PRODUCTION-reachable namespace (not the bundle-isolated tooling sibling)

  The trace-egress hook must be PUBLISHED whenever the resources artefact is
  loaded — the epoch tool-pair consults it on every off-box record projection,
  on BOTH runtimes. `re-frame.resources.tooling` (the algebra-view sibling) is
  bundle-isolated: the `re-frame.resources` facade only `:require`s it under
  `#?(:clj …)`, so a CLJS app that loads resources never pulls it in. Publishing
  the hook from there would leave the projector unpublished on CLJS (where the
  browser / MCP off-box egress matters most). This projector therefore lives in
  its own small production-reachable ns, mirroring where
  `project-scope-resolved-egress` lives (`scope_registry`, production-reachable),
  and reuses the SAME owner classification + key projection the SSR / durable /
  tooling-view paths use — never a tooling-private elider.

  ## FAIL-CLOSED posture (Spec 015 §10)

  A trace row may name a resource id that is no longer (or never) registered —
  the owner spec a value-path projector would trust is absent.
  `re-frame.resources.classification/whole-entry-disposition` of a nil spec is
  `:serialize` (a no-coarse-claim ALGEBRA read), but a TRACE row whose owner we
  cannot read is not provably safe, so this projector REDACTS an
  unregistered-owner key (and stamps `:sensitive? true`). A missing / unknown
  frame likewise reduces conservatively (`whole-entry-disposition-for`'s
  nil-frame reduction keeps the owner boundary; the unregistered arm fails
  closed). Structural attribution survives every case: the resource-id (position
  1 of the projected key) and every NON-key tag ride verbatim, so a tool still
  attributes the row to its resource and reads the structural facts; only the
  identity-bearing scope + params components are tokenized.

  The redaction is the off-box DEFAULT; the trusted-local `:include-sensitive?`
  opt-in lifts it at the epoch consumer (the `local-raw` boundary — the same
  switch the app-db / HTTP-body / scope-resolved redactions honour)."
  (:require [re-frame.resources.registry :as registry]
            [re-frame.resources.ssr :as ssr]))

#?(:clj (set! *warn-on-reflection* true))

(defn- redacted-token? [c]
  (and (map? c) (contains? c :rf/redacted)))

(defn- project-trace-scoped-key
  "Fail-closed projection of one trace-row `scoped-key`
  `[scope resource-id params]` for OFF-BOX trace egress (rf2-8x0gfa). Returns
  `[projected-key sensitive?]`. For a REGISTERED owner the scope + params
  tokenize per the owner's `whole-entry-disposition-for` classification (a
  `:sensitive?` / `:large?` / derived-sensitive key redacts to opaque
  content-addressed `{:rf/redacted <digest>}` tokens; a plain key rides
  verbatim). A key whose resource-id is UNREGISTERED (nil owner spec — the
  trace names an owner we cannot read) is REDACTED rather than serialized raw
  (the trace-egress fail-closed default). An already-projected key (both scope
  and params are opaque tokens) rides as-is + stays marked sensitive
  (idempotent — never re-hashed). A non-scoped-key value rides unchanged +
  non-sensitive. Pure."
  [scoped-key frame-id]
  (cond
    (not (and (vector? scoped-key) (= 3 (count scoped-key))))
    [scoped-key false]

    (and (redacted-token? (nth scoped-key 0)) (redacted-token? (nth scoped-key 2)))
    [scoped-key true]

    ;; fail closed — owner unreadable, redact scope + params, keep the id. This
    ;; nil-spec fail-closed-to-`:redact` (and the idempotent-token guard above)
    ;; is the OUTER wrapper the trace-egress family keeps around the shared
    ;; disposition+project-key pipeline (rf2-366u0g): tooling / SSR egress treat
    ;; an unregistered owner as `:serialize` (the algebra read), but a TRACE row
    ;; whose owner we cannot read is not provably safe.
    (nil? (registry/resource-meta (second scoped-key)))
    [(ssr/project-scoped-key scoped-key :redact nil) true]

    ;; REGISTERED owner — the shared pipeline computes the disposition + projects
    ;; the key exactly as the SSR durable-egress + tool-egress paths do. `:redact`
    ;; / `:omit` redacts the scope+params; `:serialize` rides verbatim — so the
    ;; sensitivity flag is `(not= :serialize disposition)`.
    :else
    (let [[projected-key disposition _spec] (ssr/disposition+project-key scoped-key frame-id)]
      [projected-key (not= :serialize disposition)])))

(def ^:private scoped-key-slot
  "Tag slots on a resource / mutation trace row that carry a SINGLE scoped-key
  vector `[scope resource-id params]`."
  #{:resource/key})

(def ^:private scoped-keys-slot
  "Tag slots that carry a VECTOR of scoped-key vectors across the resource +
  mutation trace family — `events.cljc` (`:matched` / `:keys` / `:exempt`),
  the `:rf.mutation/succeeded` settlement + its nested `:patch-summary`
  (`:removed` / `:committed` / `:patched` / `:populated` / `:invalidated`), the
  `:rf.mutation/optimistic-rolled-back` row (`:restored` / `:conflicted` /
  `:refetched`), the reconcile summaries (`:restored-keys` / `:conflicted-keys`
  / `:refetched-keys` / `:reconciliation-refetches`), and the
  `:rf.resource/cancel-timers` fx evidence (`:resource/keys`)."
  #{:resource/keys :matched :removed :keys :exempt :committed
    :patched :populated :invalidated
    :restored :conflicted :refetched
    :restored-keys :conflicted-keys :refetched-keys :reconciliation-refetches})

(def ^:private disposition-rows-slot
  "Tag slots that carry a VECTOR of per-key DISPOSITION maps (each embedding a
  `:resource/key`): the `:rf.mutation/optimistic-rolled-back` row's
  `:dispositions` and the `:rf.mutation/succeeded` `:patch-summary` `:rollback`
  slot (the recorded inverse, same `{:resource/key …}` shape)."
  #{:dispositions :rollback})

(def ^:private nested-map-slot
  "Tag slots whose value is a NESTED MAP carrying further scoped-key slots — the
  `:rf.mutation/succeeded` `:patch-summary` (embeds `:removed` / `:committed` /
  `:rollback` / …) + its descriptor-level `:invalidation` evidence (embeds the
  `:populate-exempt` key union). Projected recursively through the SAME slot
  vocabulary so a row's nested scoped keys never leak (rf2-8x0gfa)."
  #{:patch-summary :invalidation})

(declare project-tags*)

(defn- project-disposition-row
  "Project one optimistic-rollback disposition row
  `{:resource/key <scoped-key> :restored … :conflict … :on-conflict …}` for
  off-box egress (rf2-8x0gfa / EP-0019 §Surfacing to tooling): the scoped key
  is fail-closed-projected; the boolean disposition facts ride verbatim.
  Returns `[projected-row sensitive?]`. A non-map row rides unchanged. Pure."
  [row frame-id]
  (if-not (map? row)
    [row false]
    (let [[k sens?] (project-trace-scoped-key (:resource/key row) frame-id)]
      [(cond-> row (contains? row :resource/key) (assoc :resource/key k)) sens?])))

(defn- project-tags*
  "Core slot-keyed projection of a `tags` map — returns `[tags' sensitive?]`
  WITHOUT stamping `:sensitive?` (the caller stamps once at the top). Recurses
  into nested-map slots (`:patch-summary` / `:invalidation`) through the same
  vocabulary. Pure."
  [tags frame-id]
  (if-not (map? tags)
    [tags false]
    (let [sens?* (volatile! false)
          note!  (fn [s] (when s (vreset! sens?* true)))
          tags'
          (reduce-kv
            (fn [m k v]
              (cond
                (and (scoped-key-slot k) (some? v))
                (let [[pk s] (project-trace-scoped-key v frame-id)]
                  (note! s) (assoc m k pk))

                (and (scoped-keys-slot k) (sequential? v))
                (assoc m k (mapv (fn [sk]
                                   (let [[pk s] (project-trace-scoped-key sk frame-id)]
                                     (note! s) pk))
                                 v))

                (and (disposition-rows-slot k) (sequential? v))
                (assoc m k (mapv (fn [row]
                                   (let [[pr s] (project-disposition-row row frame-id)]
                                     (note! s) pr))
                                 v))

                (and (nested-map-slot k) (map? v))
                (let [[mv s] (project-tags* v frame-id)]
                  (note! s) (assoc m k mv))

                :else (assoc m k v)))
            {}
            tags)]
      [tags' @sens?*])))

(defn project-resource-trace-egress
  "Project a resource / mutation trace row's `tags` for OFF-BOX egress against
  the `frame-id` classification (rf2-8x0gfa / EP-0015). The family-level
  analogue of `re-frame.resources.scope-registry/project-scope-resolved-egress`,
  keyed on the resource trace family's scoped-key-bearing tag VOCABULARY rather
  than per-operation: every `:resource/key` (single scoped key), every
  scoped-keys vector slot (`:resource/keys` / `:matched` / `:removed` / `:keys`
  / `:exempt` / `:committed` / `:restored` / `:conflicted` / `:refetched` /
  `:restored-keys` / `:conflicted-keys` / `:refetched-keys`), and the
  optimistic-rollback `:dispositions` (per-key maps) is projected through the
  resource OWNER classification (`whole-entry-disposition-for` +
  `ssr/project-scoped-key`).

  A `:sensitive?` / `:large?` / derived-sensitive owner's scope + params
  tokenize to opaque content-addressed `{:rf/redacted <digest>}` (distinct
  values stay distinct, so a tool's per-key joins survive); a plain owner's key
  rides verbatim; an UNREGISTERED owner FAILS CLOSED (redacted — the
  trace-egress default). The resource-id (position 1 of every projected key) and
  every NON-key tag (`:rf.frame/id`, `:cause`, `:tags`, `:decision`,
  `:generation`, `:owner`, `:work/id`, `:delay-ms`, counts, …) ride verbatim —
  structural attribution is preserved. When ANY projected slot redacted a
  sensitive / unregistered key, the row is stamped `:sensitive? true`.

  Nested-map slots (`:patch-summary` / `:invalidation`) are projected
  RECURSIVELY through the same vocabulary so a row's nested scoped keys never
  leak. Idempotent (an opaque token re-projects to itself); a non-map `tags`
  rides unchanged. Pure. The on-box listener path never calls this — the raw
  evidence stays for dev tooling; this is the OFF-BOX egress projector consulted
  by the epoch tool-pair (the trusted-local `:include-sensitive?` opt-in lifts
  it)."
  [tags frame-id]
  (if-not (map? tags)
    tags
    (let [[tags' sens?] (project-tags* tags frame-id)]
      (cond-> tags' sens? (assoc :sensitive? true)))))
