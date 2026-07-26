(ns re-frame.resources.trace-egress
  "OFF-BOX trace-row egress projection for the resource + mutation trace family.

  ## Why this namespace exists

  `re-frame.resources.scope-registry/project-scope-resolved-egress` projects
  `:rf.resource/scope-resolved`, redacting the resolver's
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
      `:resource/key`.

  A generic value-path egress walk (the central trace-egress chokepoint) is
  STRUCTURALLY BLIND to these once copied into trace tags — a frame's declared
  `:sensitive`/`:large` app-db PATHS do not match a resolver-owned scoped key's
  embedded scope/params (Spec 015 §10 / Derivations §Resources expose process
  nodes). So the
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
  unregistered-owner key (and stamps `:sensitive? true`). The unregistered arm
  fails closed independently of any frame. Structural attribution survives every
  case: the resource-id (position 1 of the projected key) and recognized
  structural scalar tags ride verbatim, so a tool can still attribute the row.

  A tag the slot vocabulary does not NAME is projected by SHAPE rather than
  passed through verbatim (rf2-wd9im): a scoped key anywhere inside it — a member
  of an unnamed key vector, or the key EMBEDDED at position 1 of a resource
  work-id — projects through the same owner classification a NAMED slot's keys
  do, a map payload tokenizes, and a scalar rides verbatim. So no map-shaped
  value under an unrecognised tag egresses raw at any depth, and a slot nobody
  has enumerated yet cannot leak the way `:blocking` / `:identities` did.

  The redaction is the off-box DEFAULT; the trusted-local `:include-sensitive?`
  opt-in lifts it at the epoch consumer (the `local-raw` boundary — the same
  switch the app-db / HTTP-body / scope-resolved redactions honour).

  ## The family's keys also ride FOREIGN rows (rf2-1kiuj, rf2-425mm)

  Everything above is routed by the epoch tool-pair on the row's OPERATION
  namespace. But an `ensure` lowers into EFFECTS, and those effects address the
  work by its scoped key, so the same keys reach off-box egress under
  `:rf.fx/args` and `:rf.event/fx` on `:rf.fx/*` / `:rf.error/*` rows the family
  does not own — where the namespace routing never looks. `project-fx-args-egress`
  (bottom of this ns) closes that: the SAME `project-trace-scoped-key` owner
  classification, reached by SLOT instead of by op, and touching nothing on the
  row but the keys — and, since rf2-425mm, the resolved `:scope` the runtime
  writes into that same continuation payload, projected by the SAME rule the
  family's own rows give it (`project-unknown-slot-value`), so the two carriers
  of one scope agree the way the two carriers of one key already did."
  (:require [re-frame.resources.registry :as registry]
            [re-frame.resources.ssr :as ssr]))

#?(:clj (set! *warn-on-reflection* true))

(defn- redacted-token? [c]
  (and (map? c) (contains? c :rf/redacted)))

(defn- project-trace-scoped-key
  "Fail-closed projection of one trace-row `scoped-key`
  `[scope resource-id params]` for OFF-BOX trace egress. Returns
  `[projected-key sensitive?]`. For a REGISTERED owner the scope + params
  tokenize per the owner's `whole-entry-disposition` classification (a
  `:sensitive?` / `:large?` key redacts to opaque content-addressed
  `{:rf/redacted <digest>}` tokens; a plain key rides verbatim). A key whose
  resource-id is UNREGISTERED (nil owner spec — the
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
    ;; disposition+project-key pipeline: tooling / SSR egress treat
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
  "Tag slots NAMED as carrying a VECTOR of scoped-key vectors across the
  resource +
  mutation trace family — `events.cljc` (`:matched` / `:keys` / `:exempt`),
  the `:rf.mutation/succeeded` settlement + its nested `:patch-summary`
  (`:removed` / `:committed` / `:patched` / `:populated` / `:invalidated`), the
  `:rf.mutation/optimistic-rolled-back` row (`:restored` / `:conflicted` /
  `:refetched`), the reconcile summaries (`:restored-keys` / `:conflicted-keys`
  / `:refetched-keys` / `:reconciliation-refetches`), and the
  `:rf.resource/cancel-timers` fx evidence (`:resource/keys`).

  This roster is a FIDELITY aid, not the line of defence (rf2-wd9im). It was
  enumerated from the events / mutation / reconcile rows, so it is a list of the
  slots someone happened to look at — and it rotted: `:blocking` / `:identities`
  (EP-0037 `:rf.resource/route-plan`), `:optimistic-keys`, `:forced-keys`,
  `:revisions`, and the scoped-key EMBEDDED in every `:work/id` /
  `:superseded` / `:aborted` work-id all carry scoped keys and none of them is
  named here. Naming a slot buys nothing a shape read cannot: the SHAPE-DRIVEN
  fail-closed default (`project-unknown-slot-value`) recognises a scoped key
  wherever it sits and projects it through the SAME
  `project-trace-scoped-key`, so an unnamed slot's keys project IDENTICALLY to a
  named one's. Do not grow this set for a newly-noticed slot; the default
  already covers it."
  #{:resource/keys :matched :removed :keys :exempt :committed
    :patched :populated :invalidated
    :restored :conflicted :refetched
    :restored-keys :conflicted-keys :refetched-keys :reconciliation-refetches
    ;; `:affected-keys` — the `:rf.mutation/succeeded` / `:rf.mutation/failed`
    ;; settlement rows' union of populated/patched/removed/stale-marked scoped
    ;; keys (Spec 016 §Mutation completion continuations). A vector of scoped
    ;; keys exactly like `:matched` / `:removed`, so it is PER-KEY projected
    ;; here (preserving a tool's per-key joins) rather than falling to the
    ;; fail-closed default below as one coarse digest.
    :affected-keys})

(def ^:private error-envelope-slot
  "Tag slots that carry an HTTP FAILURE ENVELOPE — the `:rf.http/*` reply's
  `{:status :body :body-text :detail …}` map. The
  `:rf.resource/failed` first-load row + the `:rf.mutation/failed` settlement
  row stamp the envelope under `:error`; the `:rf.resource/page-failed`
  load-more row stamps it under `:page-error`. The raw response body
  (`:body` / `:body-text` / `:detail`) routinely echoes the SUBMITTED FORM
  FIELDS and validation text quoting user values — app-owned data the generic
  scoped-key vocabulary is structurally blind to (it is not a scoped key, not
  a cursor), so it slipped through the `:else` verbatim and reached the
  epoch / MCP off-box channel UNREDACTED. It is tokenized to a content-
  addressed `{:rf/redacted <digest>}` via the SAME `redact-value` the cursor
  uses (distinct envelopes stay distinct, so a tool's per-failure joins
  survive; the raw body never rides). The status/category attribution is
  preserved on the row's OTHER scalar tags (`:status-before` / `:status-after`
  / `:rf.frame/id` / …) — only the body-bearing envelope is tokenized."
  #{:error :page-error})

(def ^:private sibling-owned-slot
  "Tag slots OWNED by the SIBLING `:rf.resource/scope-resolved` projector
  (`re-frame.resources.scope-registry/project-scope-resolved-egress`), which the
  epoch tool-pair runs BEFORE this family projector on the SAME row
  (`omit-off-box-resource-scope-values` → `omit-off-box-resource-trace-keys`).
  That projector already classifies the resolver-owned slot — EP-0025
  (rf2-71dr8t) made resolved-scope egress UNCONDITIONALLY fail-closed (the
  `:rf.egress/public` declassification escape hatch was the removed propagation
  enum), so the sibling always substitutes the `:rf/redacted` sentinel. It must
  therefore PASS THROUGH this projector unchanged — it was already classified
  upstream.

  `:scope` USED TO BE IN THIS SET AND IS NOT (rf2-1zc33). The upstream premise
  holds on exactly ONE row: the epoch tool-pair applies the sibling under
  `(= :rf.resource/scope-resolved (:operation ev))`, while THIS projector runs on
  every `:rf.resource/*` / `:rf.mutation/*` / `:rf.warning/resource-*` row
  (`resource-family-op?` is operation-agnostic). Five other row types carry a
  resolved CONCRETE scope under a free `:scope` tag — `:rf.resource/invalidated`
  / `refetch-decision` / `removed`, `:rf.warning/resource-clear-scope-unresolved`
  and `:rf.mutation/started` + `optimistic-applied` — and on those the sibling
  never ran, so a pass-through here meant `:scope` was classified by NOBODY and
  the resolver's IDENTITY MAP (`[:rf.scope/session {:username …}]`) egressed
  off-box raw. `:rf.resource/refetch-decision` carried the same scope TWICE:
  redacted inside `:resource/key`, raw under `:scope`.

  `:scope` now falls to the SHAPE-driven default below, which is right per shape
  without a row predicate: `:rf.scope/global` is a scalar and rides verbatim (no
  over-redaction); a `[tier {identity}]` 2-vector is not scoped-key-shaped, so
  the walk descends — the TIER KEYWORD rides verbatim (a tool still shows
  \"session scope\") and the identity MAP tokenizes, distinct scopes keeping
  distinct digests so per-scope joins survive; and on `:rf.resource/scope-resolved`
  itself the sibling has already substituted its `:rf/redacted` SENTINEL — a bare
  keyword, hence a scalar — which the default rides verbatim, leaving that row
  byte-identical (the sibling stamps `:sensitive?` on the row itself, so the
  stamp is not carried by the slot). Do NOT re-add `:scope` here, and do NOT add
  a row predicate to keep the two projectors in step: a maintained roster of rows
  is exactly what rots."
  #{:input-values})

(def ^:private cursor-slot
  "Tag slots that carry the load-more PAGINATION CURSOR as a FREE scalar tag
  (not a scoped key): the `:rf.resource/load-more` row's `:page-param` (the
  resolved next-page param) and the `:rf.resource/page-appended` row's
  `:next-page-param` (the cursor the just-appended page derived for the page
  AFTER it). The cursor is an OWNER-DEFINED value — it can carry a record id /
  a tenant-scoped offset token / a timestamp (`:next-page-param` is the app's
  own fn over the resource data), so it is owner-local identity-bearing the
  same way a scoped key's scope / params are. The generic value-path egress
  walk is structurally blind to it once copied into a free tag, and it is NOT a
  scoped-key vector, so it escapes the scoped-key slots above (rf2-3tysyj). It
  is tokenized iff the ROW's resource owner classifies non-`:serialize`
  (sensitive / large / unregistered fail-closed) — the SAME
  disposition that governs the row's `:resource/key` — so a plain feed's cursor
  rides verbatim (no over-redaction)."
  #{:page-param :next-page-param})

(defn- row-owner-redacts?
  "Whether the resource OWNER named by this trace row's `:resource/key`
  classifies non-`:serialize` (sensitive / large, or UNREGISTERED →
  fail-closed) — i.e. whether the row's
  owner-local identity-bearing FREE tags (the load-more cursor) must tokenize.
  Reuses the SAME owner classification the scoped-key projection uses
  (`disposition+project-key`), with the trace-egress fail-closed default for an
  unregistered / unreadable owner (`project-trace-scoped-key`'s nil-spec arm).
  Returns false when the row carries no usable `:resource/key` (no owner to read
  — the cursor then rides verbatim; structural attribution is unaffected).
  Pure."
  [tags frame-id]
  (let [sk (:resource/key tags)]
    (when (and (vector? sk) (= 3 (count sk)))
      (let [[_pk sens?] (project-trace-scoped-key sk frame-id)]
        sens?))))

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
  vocabulary so a row's nested scoped keys never leak."
  #{:patch-summary :invalidation})

(defn- scoped-key-shape?
  "Whether `v` has the SHAPE of a scoped resource key
  `[scope resource-id canonical-params]`: a 3-vector whose position 1 is the
  resource-id KEYWORD and whose position 2 is the canonical params MAP. Read
  ONLY by the shape-driven fail-closed default below, to recognise a scoped key
  sitting in a slot the vocabulary does not name (rf2-wd9im).

  The params `map?` test is what discriminates a real key from the family's
  OTHER 3-element vectors, and it is the whole reason a shape read is safe here:
  `:owner [:app :l 1]` (a view path) and `:cause [:mutation :m/save 7]` both
  have a keyword at position 1 and a SCALAR at position 2, and both MUST ride
  verbatim — tokenizing them would destroy attribution for no security gain.
  A `:params-schema` may admit non-map params (a vector / an explicit nil), and
  such a key is deliberately NOT recognised here; it falls to the recursive walk
  instead, which still tokenizes every map the key contains (a `{:from-db …}`
  resolved scope's value map included). The walk is the guarantee, this
  predicate is the fidelity arm."
  [v]
  (and (vector? v) (= 3 (count v)) (keyword? (nth v 1)) (map? (nth v 2))))

(defn- project-unknown-slot-value
  "SHAPE-DRIVEN fail-closed projection of ONE tag value under a slot the
  vocabulary above does not name. Returns `[projected sensitive?]`.

  ## Why shape and not another slot name (rf2-wd9im)

  The projector was keyed ENTIRELY on slot NAME, and the fail-closed default
  rf2-7qbxbm added closed over ONE value shape — a MAP. A sequential value under
  an unnamed slot fell through the verbatim `:else`, so
  `:rf.resource/route-plan`'s `:blocking` / `:identities` (vectors of scoped
  keys) egressed a `:sensitive?` owner's scope + params RAW, where the identical
  keys under `:matched` tokenized. Adding those two names would have left the
  same hole for `:optimistic-keys`, `:forced-keys`, `:revisions`, and — on the
  MAJORITY of rows in the family — the scoped key EMBEDDED at position 1 of
  every resource `:work/id` / `:superseded` / `:aborted` work-id
  (`[:rf.work/resource <scoped-key> <generation>]`), none of which any roster
  named. A name roster cannot cover a slot nobody has looked at yet; a shape
  read covers all of them, including the ones added next year.

  ## The rule

    - an already-projected `{:rf/redacted …}` token rides as-is + stays
      sensitive (idempotent — never re-digested);
    - a SCOPED-KEY-SHAPED vector projects through `project-trace-scoped-key` —
      the SAME owner classification the NAMED slots use, so an unnamed slot's
      keys and a named slot's keys cannot drift, per-key distinctness (and
      therefore a tool's per-key joins) survives, and a PLAIN owner's key still
      rides verbatim (no over-redaction);
    - a MAP is tokenized — the unambiguous app-payload shape (rf2-7qbxbm);
    - any OTHER collection is walked MEMBER-WISE by this same rule, preserving
      the collection's KIND. Depth is what reaches a work-id's embedded key and
      a `{:from-db …}` scope's value map; kind preservation is load-bearing
      because scoped-key identity is kind-SENSITIVE (rf2-wgutc2 — a list-params
      key and a vector-params key are DISTINCT), so collapsing a list to a
      vector at egress would make two distinct keys look like one, and `:tags`
      rides as a SET whose egress shape tools read;
    - a SCALAR rides verbatim — keywords / numbers / booleans / short id strings
      are structural facts, not app payloads. Vectors of them (`:branch`'s route
      ids, `:invalidated-tags`, `:owner`, a mutation `:work/id`) therefore still
      ride verbatim member-by-member, which is the discrimination the bare
      \"redact any vector-of-vectors\" guard could not make.

  The invariant this buys, statable and testable: NO map-shaped value under an
  unrecognised resource-family trace tag egresses off-box raw, at ANY depth.
  Pure."
  [v frame-id]
  (cond
    (redacted-token? v)   [v true]
    (scoped-key-shape? v) (project-trace-scoped-key v frame-id)
    (map? v)              [(ssr/redact-value v) true]

    (coll? v)
    (let [sens?* (volatile! false)
          proj   (fn [x]
                   (let [[pv s] (project-unknown-slot-value x frame-id)]
                     (when s (vreset! sens?* true))
                     pv))]
      [(cond
         (set? v)    (into #{} (map proj) v)
         (vector? v) (mapv proj v)
         ;; a list / lazy seq must stay a seq — `mapv` would print it as a
         ;; vector and erase the kind distinction described above.
         (seq? v)    (apply list (map proj v))
         :else       (mapv proj v))
       @sens?*])

    :else [v false]))

(def ^:private fx-carrier-slot
  "Trace tag slots owned by the FX family that the resource family's scoped keys
  RIDE IN (rf2-1kiuj).

  A resource `ensure` lowers into effects, and those effects address the work by
  its scoped key: `[:rf.http/managed {:request-id [:rf.req <frame>
  [:rf.work/resource <scoped-key> <gen>]] :on-success [… {:work/id … :resource/key
  …}] …}]`. `re-frame.fx/handle-one-fx` stamps that argument payload verbatim
  under `:rf.fx/args` (on `:rf.fx/handled`, on `:rf.fx/skipped-on-platform`, and
  on the always-on `:rf.error/*` fx-failure traces), and `do-fx` stamps the whole
  effect vector under `:rf.event/fx`. So the family's keys reach off-box egress on
  rows the family does not own — `:rf.fx/*` / `:rf.error/*`, not `:rf.resource/*`.

  The slots are named HERE, beside the shape predicate and the key projection they
  need, rather than in the epoch consumer: one roster, in the namespace that knows
  what a scoped key looks like. `project-fx-args-egress` no-ops (reference-
  preserving) on a tags map carrying neither slot, so the consumer hands it every
  row and needs no row predicate of its own."
  #{:rf.fx/args :rf.event/fx})

(defn- project-embedded-keys
  "Project every resource SCOPED KEY — and every resolved `:scope` — embedded
  anywhere in `v`, leaving every other value UNTOUCHED. Returns
  `[projected sensitive?]`.

  The FOREIGN-CARRIER counterpart of `project-unknown-slot-value`, and it differs
  from it in exactly one arm, deliberately: a MAP is DESCENDED INTO rather than
  tokenized. The fail-closed map arm is right for an unnamed slot on a row the
  resource family OWNS (the value there is presumed owner payload — rf2-7qbxbm);
  it is wrong here, because an fx-args payload is the FX family's, and the only
  thing in it the resource family may speak for is its own data. Tokenizing the
  whole payload would redact a PLAIN owner's request map as readily as a sensitive
  one's — over-redaction, which for the resource tools is as much a defect as the
  leak.

  So: an already-projected token rides as-is (idempotent, never re-digested); a
  scoped-key-shaped vector projects through `project-trace-scoped-key` — the SAME
  owner classification the family rows' own `:resource/key` takes, so the two
  carriers of one key cannot drift; any other collection is walked through,
  preserving its KIND (scoped-key identity is kind-sensitive — rf2-wgutc2) and
  walking a map's KEYS as well as its values (a key can be map-keyed by scoped
  key); every other scalar rides verbatim.

  ## …AND the resolved `:scope` beside them (rf2-425mm)

  A scoped key is not the only family datum the family PUTS in a foreign carrier.
  The continuation payload every `ensure` / `refetch` / `load-more` / mutation
  `execute` builds for its transport
  (`transport.http/build-managed-args`) is the runtime's stale-suppression
  verification identity — `{:work/id … :resource/key <scoped-key> :scope <resolved
  scope> :generation … :rf.frame/id …}` — and it rides `:on-success` /
  `:on-failure` INSIDE the fx args. The `:resource/key` there projects (it is
  scoped-key-shaped) and so does the key embedded in the `:work/id`; the free
  `:scope` beside them is a `[tier {identity}]` TUPLE, not a scoped key, so the
  walk descended it, found a plain map of app values, and let the resolver's
  IDENTITY MAP through in the clear — one slot from the `:resource/key` that had
  just redacted the very same bytes. The rf2-irwsq shape again, now inside a
  single map.

  A value under a `:scope` key is therefore projected by the FAMILY rule
  (`project-unknown-slot-value`) rather than by the carrier walk, which is what
  makes the two carriers of one scope agree by construction: `:rf.scope/global`
  is a SCALAR and rides verbatim, a `[tier {identity}]` tuple keeps its TIER
  keyword (a tool still reads \"session scope\") while the identity map
  tokenizes to a content-addressed `{:rf/redacted <digest>}` — distinct scopes
  keeping distinct digests, so per-scope joins survive — and an unresolved
  `{:from-db …}` reference tokenizes whole. Exactly what rf2-1zc33 settled for
  the same slot on the family's OWN rows; this is that ruling reaching the
  carrier the family projector never runs on.

  `:scope` is the ONE key named here and this is not the beginning of a roster:
  the arm exists because the resource runtime writes that key into a foreign
  payload itself, so the family owns the value by construction rather than by a
  guess about shape (a resolved scope is arbitrary EDN — there is no shape to
  read). Everything else in the carrier is still the fx family's and still rides
  through untouched. Pure."
  [v frame-id]
  (cond
    (redacted-token? v)   [v true]
    (scoped-key-shape? v) (project-trace-scoped-key v frame-id)

    (coll? v)
    (let [sens?* (volatile! false)
          note!  (fn [s pv] (when s (vreset! sens?* true)) pv)
          proj   (fn [x]
                   (let [[pv s] (project-embedded-keys x frame-id)]
                     (note! s pv)))
          ;; the family's own resolved scope, planted in a foreign payload by
          ;; the runtime — projected by the FAMILY rule so the carrier cannot
          ;; drift from the row (rf2-425mm). Every other entry takes the walk.
          entry  (fn [k x]
                   (if (= :scope k)
                     (let [[pv s] (project-unknown-slot-value x frame-id)]
                       (note! s pv))
                     (proj x)))]
      [(cond
         (map? v)    (reduce-kv (fn [m k x] (assoc m (proj k) (entry k x))) {} v)
         (set? v)    (into #{} (map proj) v)
         (vector? v) (mapv proj v)
         (seq? v)    (apply list (map proj v))
         :else       (mapv proj v))
       @sens?*])

    :else [v false]))

(declare project-tags*)

(defn- project-disposition-row
  "Project one optimistic-rollback disposition row
  `{:resource/key <scoped-key> :restored … :conflict … :on-conflict …}` for
  off-box egress: the scoped key
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
          ;; the load-more cursor (`:page-param` / `:next-page-param`) is a FREE
          ;; tag, not a scoped key, so its classification rides the ROW's owner
          ;; (named by `:resource/key`): tokenize the cursor iff that owner is
          ;; non-`:serialize` (sensitive / large / derived / unregistered) —
          ;; computed ONCE here so the per-slot walk just consults it
          ;; (rf2-3tysyj). A plain feed's cursor rides verbatim.
          cursor-redacts? (row-owner-redacts? tags frame-id)
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

                ;; cursor: a non-nil free cursor tag tokenizes (content-addressed)
                ;; iff the row's owner redacts; idempotent (an opaque token stays
                ;; sensitive + is not re-hashed — `redact-value` of an already
                ;; redacted token would re-digest, so guard it).
                (and (cursor-slot k) (some? v))
                (if (redacted-token? v)
                  (do (note! true) (assoc m k v))
                  (if cursor-redacts?
                    (do (note! true) (assoc m k (ssr/redact-value v)))
                    (assoc m k v)))

                ;; HTTP failure envelope (`:error` / `:page-error`): the raw
                ;; `:rf.http/*` reply map (`:body` / `:body-text` / `:detail`)
                ;; echoes submitted form fields + validation text — app-owned
                ;; data the scoped-key vocabulary is blind to (rf2-7qbxbm). It
                ;; is UNCONDITIONALLY tokenized off-box (content-addressed via
                ;; `redact-value`, the same tokenizer the cursor uses) — the
                ;; status/category attribution survives on the row's sibling
                ;; scalar tags. Idempotent (an already-redacted token rides
                ;; as-is). A nil / non-payload envelope rides verbatim.
                (and (error-envelope-slot k) (some? v))
                (if (redacted-token? v)
                  (do (note! true) (assoc m k v))
                  (do (note! true) (assoc m k (ssr/redact-value v))))

                ;; An already-projected token rides as-is and still marks the row
                ;; sensitive — guarded BEFORE `sibling-owned-slot` so a
                ;; pre-redacted `:input-values` does not lose the row's
                ;; `:sensitive?` stamp.
                (redacted-token? v)
                (do (note! true) (assoc m k v))

                ;; SIBLING-OWNED slots ride verbatim: the scope-resolved sibling
                ;; projector already classified `:input-values` upstream on the
                ;; row it owns (EP-0025 made resolved-scope egress
                ;; unconditionally fail-closed), so re-tokenizing here is a no-op
                ;; at best and double-work at worst. `:scope` is deliberately NOT
                ;; here (rf2-1zc33) — the sibling runs on ONE operation and this
                ;; projector runs on the whole family, so a pass-through left
                ;; five row types' resolved scopes classified by nobody. See the
                ;; `sibling-owned-slot` docstring.
                (sibling-owned-slot k)
                (assoc m k v)

                ;; FAIL-CLOSED default — SHAPE-DRIVEN (rf2-7qbxbm gave it the
                ;; map arm; rf2-wd9im made it read shape rather than only
                ;; `map?`). Every slot the vocabulary above does not NAME is
                ;; projected by `project-unknown-slot-value`: a scoped key
                ;; anywhere inside it projects through the owner exactly as a
                ;; NAMED slot's keys do, a map payload tokenizes, a scalar rides
                ;; verbatim, and any other collection is walked member-wise.
                ;;
                ;; This is what closes the CLASS rather than an instance. The
                ;; former arm covered ONE shape, so a sequential value fell
                ;; through the verbatim `:else` and
                ;; `:rf.resource/route-plan`'s `:blocking` / `:identities`
                ;; egressed a sensitive owner's scope + params RAW — as did
                ;; `:optimistic-keys` / `:forced-keys` / `:revisions` and the
                ;; scoped key EMBEDDED in every resource `:work/id` /
                ;; `:superseded` / `:aborted` work-id.
                ;;
                ;; NOTE for the reader who reaches here from a row's emit site:
                ;; a slot NAME in `scoped-keys-slot` does not mean the row's
                ;; value is a key vector. `:rf.resource/route-plan` carries
                ;; `:removed` as an INT COUNT while the mutation-settlement rows
                ;; carry it as a key vector — the two were written against
                ;; different mental models of `:removed`. Both are handled: the
                ;; named arm above requires `sequential?`, so the int count
                ;; falls to this default and rides verbatim as the scalar it is.
                :else
                (let [[pv s] (project-unknown-slot-value v frame-id)]
                  (note! s) (assoc m k pv))))
            {}
            tags)]
      [tags' @sens?*])))

(defn project-resource-trace-egress
  "Project a resource / mutation trace row's `tags` for OFF-BOX egress against
  the `frame-id` classification. The family-level
  analogue of `re-frame.resources.scope-registry/project-scope-resolved-egress`,
  keyed on the resource trace family's scoped-key-bearing tag VOCABULARY rather
  than per-operation: every `:resource/key` (single scoped key), every
  scoped-keys vector slot (`:resource/keys` / `:matched` / `:removed` / `:keys`
  / `:exempt` / `:committed` / `:restored` / `:conflicted` / `:refetched` /
  `:restored-keys` / `:conflicted-keys` / `:refetched-keys`), and the
  optimistic-rollback `:dispositions` (per-key maps) is projected through the
  resource OWNER classification (`whole-entry-disposition` +
  `ssr/project-scoped-key`).

  A `:sensitive?` / `:large?` owner's scope + params
  tokenize to opaque content-addressed `{:rf/redacted <digest>}` (distinct
  values stay distinct, so a tool's per-key joins survive); a plain owner's key
  rides verbatim; an UNREGISTERED owner FAILS CLOSED (redacted — the
  trace-egress default). The resource-id (position 1 of every projected key) and
  recognized structural scalar tags (`:rf.frame/id`, `:cause`, `:decision`,
  `:generation`, `:owner`, `:delay-ms`, counts, …) ride verbatim. A tag the
  vocabulary does not NAME is projected by SHAPE (rf2-wd9im,
  `project-unknown-slot-value`): a scoped key anywhere inside it projects through
  the same owner classification, a map payload tokenizes, a scalar rides
  verbatim. That is what covers `:rf.resource/route-plan`'s `:blocking` /
  `:identities`, the optimistic rows' `:optimistic-keys` / `:forced-keys` /
  `:revisions`, and the scoped key EMBEDDED at position 1 of every resource
  `:work/id` / `:superseded` / `:aborted` work-id
  (`[:rf.work/resource <scoped-key> <generation>]`) — none of which the slot
  roster names. When ANY projected slot redacted a
  sensitive / unregistered key, the row is stamped `:sensitive? true`.

  The load-more PAGINATION CURSOR — `:page-param` (on `:rf.resource/load-more`)
  / `:next-page-param` (on `:rf.resource/page-appended`) — is a FREE owner-
  defined tag (an app `:next-page-param` fn over the feed data, so it can carry
  a record id / tenant offset / timestamp), not a scoped key, so it escapes the
  scoped-key slots. It rides the ROW's owner classification (read from the
  sibling `:resource/key`): tokenized to a content-addressed `{:rf/redacted
  <digest>}` when that owner is non-`:serialize` (sensitive / large /
  unregistered fail-closed), riding verbatim for a plain feed (no
  over-redaction).

  The free `:scope` tag — the RESOLVED CONCRETE scope the family stamps on
  `:rf.resource/invalidated` / `refetch-decision` / `removed`,
  `:rf.warning/resource-clear-scope-unresolved` and `:rf.mutation/started` +
  `optimistic-applied` — also takes the shape default (rf2-1zc33). It is NOT
  sibling-owned: the `:rf.resource/scope-resolved` projector the epoch tool-pair
  runs first is applied on that ONE operation, so on these five rows a
  pass-through left the resolver's identity map classified by nobody. Under the
  shape default `:rf.scope/global` rides verbatim, and a
  `[:rf.scope/session {…}]` tuple keeps its TIER keyword while the identity map
  tokenizes — so `:rf.resource/refetch-decision`, which carries one scope under
  BOTH `:resource/key` and `:scope`, can no longer redact and leak the same
  value side by side.

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

(defn project-fx-args-egress
  "Project the FX-ARGS trace tag slots of ANY trace row for OFF-BOX egress, so a
  resource scoped key riding an FX row is classified by its resource OWNER
  exactly as the same key riding a `:rf.resource/*` row is (rf2-1kiuj).

  `project-resource-trace-egress` above is routed by the epoch tool-pair on the
  row's OPERATION NAMESPACE (`resource-family-op?`), so it never reached
  `:rf.fx/args` or `:rf.event/fx` — and a resolver-owned key's embedded scope +
  params are not app-db-rooted, so the generic value-path walk cannot classify
  them either (Spec 015 §10). Two blind spots meeting, and between them a
  naturally-captured `ensure` record egressed a `:sensitive?` owner's resolved
  scope and canonical params RAW at eighteen paths. In its sharpest form the
  SAME payload rode TWO carriers of ONE record with only one rule applied — the
  rf2-irwsq shape: the structured `:effects[*].args` slot read `:rf/redacted`
  while the `:rf.fx/args` TAG three rows above it carried the secret in the clear.

  Given a row's `tags` + the `frame-id`, walks `:rf.fx/args` / `:rf.event/fx` by
  SHAPE (`project-embedded-keys`) and stamps `:sensitive? true` when a key
  redacted. Everything else on the row rides UNTOUCHED, whatever its shape: the
  row belongs to the fx family, and the resource family speaks only for the data
  it planted there — its scoped keys, and (rf2-425mm) the resolved `:scope` the
  transport continuation payload carries beside them, which is a
  `[tier {identity}]` TUPLE rather than a scoped key and so was descended into
  and let through in the clear one slot from the `:resource/key` that had just
  redacted the same bytes. A tags map carrying NEITHER slot rides through reference-preserved —
  which is what lets the epoch consumer apply this to every row and keep no row
  predicate of its own, so a family key riding a carrier on some future op is
  covered without anyone widening a roster.

  Idempotent (an opaque token re-projects to itself); a non-map `tags` rides
  unchanged. Pure. Off-box only — the trusted-local `:include-sensitive?` opt-in
  lifts it at the epoch consumer, and the on-box listener keeps the raw evidence."
  [tags frame-id]
  (if-not (map? tags)
    tags
    (let [sens?* (volatile! false)
          tags'  (reduce (fn [m slot]
                           (if-not (contains? m slot)
                             m
                             (let [[pv s] (project-embedded-keys (get m slot) frame-id)]
                               (when s (vreset! sens?* true))
                               (assoc m slot pv))))
                         tags
                         fx-carrier-slot)]
      (cond-> tags' @sens?* (assoc :sensitive? true)))))
