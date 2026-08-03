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

  ## The family's keys also ride FOREIGN rows (rf2-1kiuj, rf2-425mm, rf2-xx4ty,
  ## rf2-ko5lm)

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
  of one scope agree the way the two carriers of one key already did; and, since
  rf2-xx4ty, the `:value` + `:params` a READ COMPLETION CONTINUATION reply
  carries beside its `:resource/key`, tokenized through that key's OWNER exactly
  as the load-more cursor is through its row's; and, since rf2-ko5lm, that same
  reply's owner-DECLARED projection-relative slots, which the coarse owner read
  is blind to, substituted through the same `redact-continuation-reply` the
  mutation reply uses at its source; and, since rf2-dl7bz, that SAME
  declaration's copy inside the scoped KEY beside it — the family's universal
  carrier — substituted by `redact-key-declarations`, so a `:params` / `:scope`
  declaration reaches every carrier of the value it names rather than only the
  ones somebody has looked at."
  (:require [re-frame.classification :as core-classification]
            [re-frame.resources.classification :as classification]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.reply :as resources-reply]
            [re-frame.resources.ssr :as ssr]))

#?(:clj (set! *warn-on-reflection* true))

(defn- redacted-token? [c]
  (and (map? c) (contains? c :rf/redacted)))

(defn- key-slot-declarations
  "Split a resource `spec`'s scoped-KEY projection-relative declarations into
  the two components of `[scope resource-id params]`, each path re-rooted at
  the component it names. Returns

    {0 {:sensitive [paths] :large [paths]}    ; the SCOPE component
     2 {:sensitive [paths] :large [paths]}}   ; the PARAMS component

  keyed by the component's INDEX in the key vector, with a component that
  neither axis names dropped — so `nil` means the spec declares nothing the KEY
  carries and the key must ride VERBATIM.

  Built from the already-public `classification/spec-declaration-marks`, whose
  `:params` bucket IS the scoped-key bucket: a `:params`-rooted declaration
  arrives with its head stripped and names the params component, a
  `:scope`-rooted one arrives WITH its `[:scope …]` head and names the scope
  component. That is the same reading
  `classification/instance-declaration-paths` lowers into the per-frame elision
  registry (`[… :resource/key 2 …]` for params, `[… :resource/key 0 …]` for
  scope), which is what makes this agree with the durable side by construction
  rather than by coincidence. Pure / value-independent."
  [spec]
  (let [marks (:params (classification/spec-declaration-marks spec))
        axis  (fn [k]
                (reduce (fn [acc p]
                          (let [p (vec p)]
                            (if (= :scope (first p))
                              (update acc 0 conj (subvec p 1))
                              (update acc 2 conj p))))
                        {0 [] 2 []}
                        (keys (get marks k))))
        s     (axis :sensitive)
        l     (axis :large)]
    (not-empty
      (into {}
            (keep (fn [i]
                    (when (or (seq (get s i)) (seq (get l i)))
                      [i {:sensitive (get s i) :large (get l i)}])))
            [0 2]))))

(defn redact-key-declarations
  "The SCOPED-KEY analogue of `redact-reply-declarations` below (rf2-dl7bz) —
  the owner's per-slot `:params` / `:scope` projection-relative declarations,
  substituted inside the key `[scope resource-id params]` at the trace / tool
  egress boundary. Takes the key ALREADY projected by
  `ssr/disposition+project-key`, that call's `disposition`, and the owner
  `spec` it resolved; returns the key.

  ## Why the coarse projection is not the whole answer

  `ssr/project-scoped-key` projects the key by the COARSE
  `whole-entry-disposition` — the owner's root `:sensitive?` / `:large?` prop.
  A spec that declares `{:sensitive [[:params :account-id]]}` and no coarse prop
  classifies `:serialize`, so that read says nothing and the declared params
  rode VERBATIM inside `:resource/key` on every family row, inside every
  scoped-keys vector slot, inside every `[:rf.work/resource <key> <gen>]`
  work-id, and inside every fx carrier the key reaches — while the SAME bytes
  in the durable entry redact, because `reconcile-registry` lowered the
  declaration to `[… :resource/key 2 …]` and the SSR wire key walks it
  (`classification/project-entry-params`). One value, two carriers, one rule
  applied: the rf2-irwsq shape the sibling `redact-reply-declarations`
  (rf2-ko5lm) closed for the reply's copy of those same params, one slot over
  on the same carrier.

  ## Why HERE and not in `ssr/project-scoped-key`

  `project-scoped-key`'s `:serialize` deferral is DELIBERATE and stays intact:
  the SSR durable path has a REGISTRY-driven counterpart with the entry's
  `key-id` and the live frame, so it walks the params component through
  `project-egress` seeded at the lowered registry offset. The trace / tool
  boundary has neither, so it derives the same declaration from the SPEC — the
  frameless derivation the family already uses beside this fn for the reply
  (`classification/redact-continuation-reply` over `carrier-decl-paths`). The
  two answers are byte-identical by construction, because both re-root the same
  `spec-declaration-marks` bucket onto the same two key components and both walk
  INDEX-FREE. SSR is not a caller that never asked for this — it is not a caller
  of this at all.

  ## Grain: DECLARATION-conditional, and `:serialize`-only

  A `:redact` / `:omit` key has already had its WHOLE scope + params replaced by
  classification-chosen tokens, which SUBSUMES the per-slot surface, so this
  fires only under `:serialize`. Within `:serialize` it fires on the paths the
  owner DECLARED and on nothing else: a spec declaring nothing the key carries
  rides the key back IDENTICAL (`key-slot-declarations` returns nil), which is
  what preserves the CEDN-1 byte identity a cache-key round-trip depends on —
  the walker RECONSTRUCTS collections, so an unnecessary walk would collapse a
  list-valued param to a vector (rf2-wgutc2). Under a declaration that walk DOES
  happen and that collapse IS accepted — the SSR durable path already accepts
  exactly it under the same declaration-existence gate
  (`registry-classifies-under?`), so this extends an accepted cost rather than
  introducing one.

  IDEMPOTENT: re-projecting substitutes the same sentinel at the same path. A
  non-`:serialize` disposition, a nil spec (the unregistered owner the caller
  has already failed closed on), or a non-3-vector rides UNCHANGED. Pure /
  value-independent."
  [scoped-key disposition spec]
  (if-not (and (= :serialize disposition)
               (some? spec)
               (vector? scoped-key)
               (= 3 (count scoped-key)))
    scoped-key
    (if-let [decls (key-slot-declarations spec)]
      (reduce-kv (fn [k i {:keys [sensitive large]}]
                   (update k i core-classification/redact-with-paths
                           sensitive large {:index-free? true}))
                 scoped-key
                 decls)
      scoped-key)))

(defn- coarse-key-projection
  "The COARSE arm of a trace-row scoped-key projection — the whole of
  `project-trace-scoped-key` EXCEPT the owner's per-slot declarations. Takes a
  `scoped-key` already known to be a 3-vector; returns
  `[projected-key coarse-redacts? disposition spec]`.

  `coarse-redacts?` answers ONE question and it is not the row's stamp: does
  this owner's WHOLE-ENTRY claim cover its whole payload? True for a
  `:sensitive?` / `:large?` root prop, for an UNREGISTERED owner (the
  trace-egress fail-closed default — tooling / SSR egress treat an unregistered
  owner as `:serialize`, the algebra read, but a TRACE row whose owner we cannot
  read is not provably safe), and for an ALREADY-PROJECTED key (both components
  opaque tokens — the coarse arm fired upstream; idempotent, never re-hashed).

  It is a SEPARATE reading from the row's `:sensitive?` stamp, and rf2-dl7bz is
  why. `row-owner-redacts?` gates the whole-slot tokenization of the free
  load-more cursor and of a read reply's `:value` / `:params` — payload the
  owner made no per-slot claim about — so it must ask the COARSE question. The
  row STAMP asks the different question \"did anything on this row redact\", and
  a per-slot declaration substituting inside the key answers yes to that while
  answering no to this. Borrowing one flag for both is what made a
  declaration-only owner's whole reply body tokenize, destroying the undeclared
  siblings rf2-ko5lm's grain argument exists to keep readable."
  [scoped-key frame-id]
  (cond
    (and (redacted-token? (nth scoped-key 0)) (redacted-token? (nth scoped-key 2)))
    [scoped-key true nil nil]

    (nil? (registry/resource-meta (second scoped-key)))
    [(ssr/project-scoped-key scoped-key :redact nil) true :redact nil]

    :else
    (let [[projected-key disposition spec] (ssr/disposition+project-key scoped-key frame-id)]
      [projected-key (not= :serialize disposition) disposition spec])))

(defn- project-trace-scoped-key
  "Fail-closed projection of one trace-row `scoped-key`
  `[scope resource-id params]` for OFF-BOX trace egress. Returns
  `[projected-key sensitive?]`. For a REGISTERED owner the scope + params
  tokenize per the owner's `whole-entry-disposition` classification (a
  `:sensitive?` / `:large?` key redacts to classification-chosen
  `{:rf/redacted <digest>}` tokens; a plain key rides verbatim) and, when that
  COARSE read says nothing, the owner's per-slot `:params` / `:scope`
  DECLARATIONS substitute in place (`redact-key-declarations`, rf2-dl7bz) — the
  two arms composing by grain exactly as the reply's do: coarse claim ⇒ the
  whole scope + params tokenize; declaration only ⇒ the declared slots
  substitute and their undeclared siblings ride; neither ⇒ the key is
  byte-identical. A key whose
  resource-id is UNREGISTERED (nil owner spec — the
  trace names an owner we cannot read) is REDACTED rather than serialized raw
  (the trace-egress fail-closed default). An already-projected key (both scope
  and params are opaque tokens) rides as-is + stays marked sensitive
  (idempotent — never re-hashed). A non-scoped-key value rides unchanged +
  non-sensitive. Pure.

  STAMP-PRECISE, on the rf2-ko5lm reading: a declaration that matched nothing in
  THIS key leaves the row unstamped, so `sensitive?` keeps meaning \"something
  on this row redacted\". `not=` is the right comparison and not `identical?` —
  the walk reconstructs collections, and a bare `(1 2 3)` → `[1 2 3]` kind
  collapse is not a redaction and must not stamp."
  [scoped-key frame-id]
  (if-not (and (vector? scoped-key) (= 3 (count scoped-key)))
    [scoped-key false]
    (let [[projected-key coarse? disposition spec] (coarse-key-projection scoped-key frame-id)
          declared-key (redact-key-declarations projected-key disposition spec)]
      [declared-key (or coarse? (not= declared-key projected-key))])))

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
  (`resource-family-op?` is operation-agnostic). This docstring is the ROSTER of
  the other row types that stamp a resolved CONCRETE scope under a free `:scope`
  tag — `:rf.resource/invalidated`, `:rf.resource/refetch-decision`,
  `:rf.resource/removed`, `:rf.warning/resource-clear-scope-unresolved`,
  `:rf.mutation/started` and `:rf.mutation/optimistic-applied`. The sibling never
  ran on any of them, so a pass-through here meant `:scope` was classified by
  NOBODY and the resolver's IDENTITY MAP (`[:rf.scope/session {:username …}]`)
  egressed off-box raw. `:rf.resource/refetch-decision` carried the same scope
  TWICE: redacted inside `:resource/key`, raw under `:scope`.

  `:scope` now falls to the SHAPE-driven default below, which is right per shape
  without a row predicate: `:rf.scope/global` is a scalar and rides verbatim (no
  over-redaction); a `[tier {identity}]` 2-vector is not scoped-key-shaped, so
  the walk descends — the TIER KEYWORD rides verbatim (a tool still shows
  \"session scope\") and the identity MAP tokenizes to a content-FREE token (rf2-hzcv8: a
  free scope tag has no owner claim to permit a digest); and on `:rf.resource/scope-resolved`
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

(def ^:private reply-payload-slot
  "Slots of a READ COMPLETION CONTINUATION reply map that carry the resource
  owner's own data as FREE entries beside its `:resource/key` (rf2-xx4ty):
  `:value` — the DECODED RESPONSE BODY — and `:params` — the canonical params.

  `re-frame.resources.events/read-continuation-reply` builds that map for an
  `ensure` / `refetch` carrying a call-site `:reply-to` (EP-0016 D1, Spec 016
  §Read completion continuations) and `re-frame.reply/complete` APPENDS it as
  the final argument of the target event vector, which the runtime dispatches
  through `[:dispatch <ev>]` — so it rides `:rf.fx/args` and `:rf.event/fx`,
  the same two foreign carriers rf2-1kiuj and rf2-425mm addressed. Neither
  reached these two: `:value` and `:params` are ordinary maps, not scoped keys,
  so the carrier walk descended them and let the owner's data through in the
  clear one slot from the `:resource/key` that had just redacted the very same
  params.

  OWNER-CONDITIONAL, and that is the whole design (see `project-embedded-keys`
  §rf2-xx4ty): these two are read through the ROW's owner exactly as
  `cursor-slot` is, never unconditionally. `:params` and `:value` are ordinary
  English words the FX family uses for its own data — an app's managed-HTTP
  args carry `{:request {… :params {…}}}`, and
  `[:rf.resource/commit-generation {:value 1}]` rides the same effect vector —
  so a name-only arm would redact a plain owner's request map and the runtime's
  own generation counter. The sibling `:resource/key` is what makes the name
  safe to read: it says the map is a RESOURCE reply and names whose."
  #{:params :value})

(def ^:private reply-error-slot
  "The FAILURE ENVELOPE slot of a canonical resource-FAMILY continuation reply
  (rf2-rnsv2): `:error`, the closed `:rf.http/*` envelope
  `reply/failure-reply` stamps on both the `:status :error` and the
  `:status :cancelled` (abort) reply. `error-envelope-slot` above is the same
  envelope's ROW spelling; this is its CARRIER spelling, and the two are
  deliberately separate sets — the row also spells it `:page-error` on the
  load-more failure, while a reply never does.

  ## Grain: MARKER-GATED, UNCONDITIONAL INSIDE THE MARKER

  Tokenized whenever the map carrying it is provably a family reply
  (`family-reply?`), with no owner read and no status read. That is what keeps
  the envelope's two carriers in agreement: `error-envelope-slot` is consumed
  UNCONDITIONALLY on the family's own `:rf.resource/failed` /
  `:rf.resource/page-failed` / `:rf.mutation/failed` rows, so an owner-
  conditional carrier arm would tokenize the row copy of an envelope and let the
  carrier copy of the SAME bytes ride — the rf2-irwsq disagreement, in mirror
  image.

  ## …which is why `:error` IS NOT IN `reply-payload-slot`

  That set is consumed under `(and owner-redacts? …)` in `project-embedded-keys`
  below, so dropping `:error` into it is a one-token edit that LOOKS like
  reusing the proven rf2-xx4ty pattern and in fact implements the
  owner-conditional remedy the paragraph above rejects. The two sets differ in
  GRAIN, not merely in membership, and that is the whole reason there are two:
  `:value` / `:params` are the OWNER'S data, so the owner's coarse claim decides
  (and an unconditional arm would over-redact a plain resource's reply);
  `:error` is the TRANSPORT'S envelope, which no owner classification speaks
  for and which the family's own rows tokenize regardless of owner.

  The envelope really does carry app data: `re-frame.http.privacy` enumerates
  `:body` / `:body-text` / `:decoded` / `:detail` / `:headers` / `:cause` as its
  app-bearing slots, `:detail` being the app's own domain failure map — so a
  422 validation failure echoes the SUBMITTED FORM FIELDS straight back out.
  And it arrives here RAW: the transport's `dispatch-failure!` hands the
  unredacted envelope to `:on-failure`, `privacy/prepare-emit-failure` touching
  only HTTP's own trace row.

  Two non-traps, stated so nobody 'fixes' them. `:status` is NOT the gate — an
  abort settles `:status :cancelled` and still carries `:error`, so the slot
  name inside a recognized reply is the whole test. And tokenizing the envelope
  whole loses the HTTP status code off-box, which is already true of the
  family's own rows (they carry `:status-before` / `:status-after`, not the HTTP
  code); preserving envelope scalars on the carrier ONLY would re-create the
  disagreement this exists to prevent."
  #{:error})

(def ^:private reply-correlation-slot
  "The CORRELATION-FACTS slot of a canonical resource-FAMILY continuation reply
  (rf2-l6wjl): `:correlation`, the map `reply/base-reply` stamps beside the
  reply's own top-level facts — `{:scope … :generation … :rf.reply/resource-key
  …}` for a read completion, `{:scope … :generation … :mutation/id …
  :instance/id …}` for a mutation one.

  ## Grain: MARKER-GATED, the FAMILY rule inside the marker

  Read under `family-reply?` — both work kinds — and never under an owner read.
  The only entry it treats specially is `:scope`, which takes the same
  `project-unknown-slot-value` the free `:scope` beside it takes, so the
  reply's THREE copies of one resolved identity (top-level `:scope`, this one,
  and the scope embedded in `:rf.reply/work-id`) project under one rule. That
  is rf2-425mm's principle — the two carriers of one scope must agree — and
  rf2-1zc33's: a free `:scope` belongs to no owner whose declaration could
  exempt it, so no owner read can speak for it.

  ## Why the payload proof could not reach it

  `carrier-family-payload?` proves a map is the runtime's from its IMMEDIATE
  entries, and the two halves of the family diverge exactly there. A READ
  completion's correlation carries `:rf.reply/resource-key` — a
  `family-named-key?` — so it proved itself and its `:scope` cleaned. A MUTATION
  completion's carries no `resource`-namespaced key and no work-id value (the
  work-id sits at the reply ROOT, not in here), so it proved nothing and its
  `:scope` rode off-box RAW, one slot from the top-level `:scope` that had just
  tokenized the very same bytes. Same family, same substrate, same datum, two
  rules — the rf2-irwsq shape across the family's two halves.

  The proof this arm uses instead is CONSTRUCTION, not vocabulary: inside a map
  that carries the canonical reply marker, `:correlation` is the slot
  `base-reply` built, exactly as position 1 of a `[:rf.work/resource …]` vector
  is family-planted by construction. That is strictly stronger than a registry
  lookup would be — it still holds for a mutation whose registration was
  hot-reloaded away, which is precisely when a fail-closed arm has to work.

  ## …which is why `:correlation` IS NOT IN `reply-payload-slot`

  Same trap `reply-error-slot` records. That set is consumed under
  `(and owner-redacts? …)`, so dropping `:correlation` into it is a one-token
  edit that LOOKS like reusing the proven rf2-xx4ty pattern and in fact makes
  the scope's carriers disagree again for any `:serialize` owner — and it would
  tokenize the whole correlation map, destroying the `:generation` /
  `:mutation/id` / `:instance/id` identities every tool joins on."
  :correlation)

(defn- row-owner-redacts?
  "Whether the resource OWNER named by this trace row's `:resource/key`
  classifies non-`:serialize` (sensitive / large, or UNREGISTERED →
  fail-closed) — i.e. whether the row's
  owner-local identity-bearing FREE tags (the load-more cursor; and, inside an
  fx carrier, a read continuation reply's `reply-payload-slot` entries) must
  tokenize.
  Reuses the SAME owner classification the scoped-key projection uses
  (`coarse-key-projection` — `disposition+project-key` plus the trace-egress
  fail-closed default for an unregistered / unreadable owner).
  Returns false when the row carries no usable `:resource/key` (no owner to read
  — the cursor then rides verbatim; structural attribution is unaffected).
  Pure.

  Reads the COARSE arm DIRECTLY, not `project-trace-scoped-key`'s `sensitive?`
  (rf2-dl7bz). The two diverged the moment a `:serialize` key started honouring
  the owner's per-slot declarations: that substitution makes the KEY redact —
  which the row stamp must report — while saying nothing about the free cursor
  or the reply body, which no declaration names. Reading the stamp here made a
  declaration-only owner's whole `:value` / `:params` tokenize, which is exactly
  the over-redaction the reply arm's grain argument forbids."
  [tags frame-id]
  (let [sk (:resource/key tags)]
    (when (and (vector? sk) (= 3 (count sk)))
      (second (coarse-key-projection sk frame-id)))))

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

(defn- scoped-key-frame?
  "The positional SKELETON of a scoped resource key
  `[scope resource-id canonical-params]`: a 3-vector with the resource-id
  KEYWORD at position 1. Necessary for both readers below, sufficient for
  neither — each adds its own proof that the 3-vector is the resource family's
  (`scoped-key-shape?` on a family ROW, `carrier-family-value?` inside a
  FOREIGN carrier)."
  [v]
  (and (vector? v) (= 3 (count v)) (keyword? (nth v 1))))

(defn- scoped-key-shape?
  "Whether `v` is recognisably a scoped resource key
  `[scope resource-id canonical-params]` — the `scoped-key-frame?` skeleton
  PLUS a proof that the 3-vector is a key rather than one of the family's other
  3-element vectors. Read by the shape-driven fail-closed default below, to
  recognise a scoped key sitting in a slot the vocabulary does not name
  (rf2-wd9im).

  ## What has to be discriminated, and the two proofs that do it

  `:owner [:app :l 1]` (a view path) and `:cause [:mutation :m/save 7]` both
  wear the skeleton — keyword at position 1 — and both MUST ride verbatim, since
  tokenizing them destroys attribution for no security gain. So the skeleton
  alone is never enough. Either proof suffices:

    - a MAP at position 2. The canonical params of the overwhelming majority of
      resources, and nothing structural in the family puts a map there;
    - the resource REGISTRY knows position 1. The family's own authority
      answering \"is this one of mine?\" — the same proof `carrier-family-value?`
      reads one carrier out, for the same question.

  ## Why the registry proof had to be added (merged-PR audit #7013)

  `map?` at position 2 was the ONLY test, and `:params-schema` is REQUIRED but
  free: `[:vector :string]`, `:string`, `[:maybe [:map …]]` are all legal, so a
  REGISTERED owner's canonical params are legally a vector, a scalar, or nil.
  Such a key wore the skeleton and failed the only proof, so on a family row it
  fell through the recursive walk as a bag of structural scalars — owner-aware
  projection never ran, the row was not stamped `:sensitive?`, and a
  `:sensitive?` owner's resolved scope + canonical params egressed RAW under
  `:blocking` / `:identities` and inside every `:work/id`. The identical leak
  rf2-wd9im closed for map params, one params shape over. The registry is not a
  roster and cannot rot: it costs one lookup and it says nothing about `:owner`'s
  `:l` or `:cause`'s `:m/save` (a MUTATION id is not in the RESOURCE registrar).

  ## The one case deliberately left to the walk

  A non-map-params key naming an UNREGISTERED owner. Both proofs are gone — no
  map at position 2, no registry entry — and nothing distinguishes it from
  `[:app :l 1]`, so redacting it would mean redacting every structural 3-vector
  in the family. It stays with the walk, which still tokenizes every MAP inside
  it. The fail-closed arm is unreachable only for an unnamed slot on a family
  row: a NAMED slot projects by position rather than by shape
  (`scoped-key-slot` / `scoped-keys-slot`), and inside a carrier `named?` does
  the same."
  [v]
  (and (scoped-key-frame? v)
       (or (map? (nth v 2))
           (some? (registry/resource-meta (nth v 1))))))

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

(def ^:private work-id-marker
  "The reserved head keyword of a resource WORK-ID vector —
  `[:rf.work/resource <scoped-key-or-mutation-id> <generation>]`
  (`work_ledger/resource-work-id`; a mutation attempt reuses the head and
  carries `[:rf.mutation <instance-id>]` at position 1). The runtime writes it
  into every carrier payload it plants, so it is the family's own signature on
  a foreign row — read as such by `carrier-family-value?` below, and by
  `tooling`/`derivation.egress`'s work-id readers for the same reason."
  :rf.work/resource)

(defn- family-work-id?
  "Whether `v` is a resource work-id vector — a vector headed by
  `work-id-marker`. Position 1 of such a vector is family-planted BY
  CONSTRUCTION, whatever its shape."
  [v]
  (and (vector? v) (= work-id-marker (first v))))

(defn- family-named-key?
  "Whether map key `k` is a name the resource runtime RESERVED for one of its
  scoped keys: anything in the family's own `resource` keyword namespace
  (`:resource/key`, `:resource/keys`, …) — a NAMESPACE read, per Conventions
  §Reserved namespaces, so a name added next year is covered — plus
  `:rf.reply/resource-key`, the spelling the shared REPLY ENVELOPE uses inside
  a completion's `:correlation` facts (`resources.reply`), which lives in the
  reply namespace rather than the family's.

  This is a FIDELITY arm, not the line of defence, and the distinction is worth
  keeping straight (the same one `scoped-keys-slot` makes above). A key here is
  projected whatever the registry says, which is what keeps the fail-closed
  unregistered-owner arm reachable inside a carrier. Every key of a REGISTERED
  owner is already covered by `carrier-family-value?`'s registry proof without
  appearing here, so a spelling nobody has added costs nothing except
  fail-closed-ness for an owner that no longer exists."
  [k]
  (and (keyword? k)
       (or (= "resource" (namespace k))
           (= :rf.reply/resource-key k))))

(defn- carrier-family-payload?
  "Whether MAP `m` inside a FOREIGN carrier is a payload the resource RUNTIME
  planted — proved by the family's own reserved vocabulary appearing among its
  immediate entries: a `resource`-namespaced key, or a value that is a
  `[:rf.work/resource …]` work-id.

  This is what the free `:scope` arm reads (rf2-1kiuj audit #7054). Three of
  the four payloads the runtime plants a `:scope` in satisfy it, by three
  different markers, and no two of them share one:

    - the READ continuation (`transport.http/build-managed-args`) —
      `{:work/id <work-id> :resource/key <key> :scope <scope> …}`;
    - the MUTATION execute reply-payload — `{:instance-id … :work/id <work-id>
      :scope <scope> …}`, with NO resource key at all, which is exactly why the
      work-id is a marker and the key alone is not (rf2-425mm settled that
      mutations must not be left leaking);
    - a READ completion reply's `:correlation` facts (`resources.reply`) —
      `{:scope <scope> :generation … :rf.reply/resource-key <key>}`, whose only
      marker is that reserved reply spelling.

  THE FOURTH DOES NOT, and this docstring once claimed it did. A MUTATION
  completion reply's `:correlation` is `{:scope … :generation … :mutation/id …
  :instance/id …}` — no `resource`-namespaced key, no work-id value, since the
  work-id sits at the reply ROOT rather than in here. So the read half of one
  slot proved itself and the mutation half did not, and the mutation's
  correlation scope egressed raw (rf2-l6wjl). It is reached by a marker-gated
  arm of its own — see `reply-correlation-slot` — because the proof available
  there is the reply's canonical `:rf.reply/work-kind`, not its correlation
  map's vocabulary.

  An app's own `{:request {… :scope {:tenant \"…\"} …}}` carries none of these,
  so its `:scope` — an ordinary English word the FX family uses for its own
  data — is nobody's business here and rides verbatim."
  [m]
  (boolean (some (fn [[k v]] (or (family-named-key? k) (family-work-id? v))) m)))

(defn- resource-reply?
  "Whether MAP `m` is a canonical resource READ-COMPLETION reply, by its own
  marker (`resources.reply/work-kind-resource`, stamped on every reply the
  read continuation substrate builds — `:rf.reply/work-kind :resource`). The
  gate on the `reply-payload-slot` arm (rf2-1kiuj audit #7059): a map that
  merely happens to carry a `:resource/key` beside a `:value` / `:params` is
  NOT a reply, and the family does not speak for those two words anywhere
  else. A mutation reply stamps `:mutation` and is deliberately not matched —
  its payload is redacted at source (`classification/redact-continuation-reply`)."
  [m]
  (= resources-reply/work-kind-resource (:rf.reply/work-kind m)))

(def ^:private family-reply-kinds
  "The resource FAMILY's two canonical reply `:rf.reply/work-kind`s — a read
  completion (`:resource`) and a mutation completion (`:mutation`)
  (`resources.reply/work-kind-resource` / `work-kind-mutation`). Read by
  `family-reply?` below.

  ENUMERATED, never `(some? (:rf.reply/work-kind m))`. `:rf.reply/work-kind` is
  the SHARED `re-frame.reply` substrate's marker, not this family's: managed
  HTTP stamps `:http` on its own canonical reply (`re-frame.http.reply`), and an
  HTTP reply riding these same carriers is the HTTP family's data to classify.
  The resources projector speaks only for what the resources runtime planted."
  #{resources-reply/work-kind-resource
    resources-reply/work-kind-mutation})

(defn- family-reply?
  "Whether MAP `m` is a canonical reply of the resource FAMILY — a read
  completion OR a mutation completion — by the same marker `resource-reply?`
  reads, widened to both work kinds (rf2-rnsv2).

  The widening exists because the two halves diverge on the OWNER'S data and
  agree on the TRANSPORT ENVELOPE. `:value` / `:params` are the owner's, and the
  mutation redacts its own at the source (`classification/redact-continuation-
  reply`), so `resource-reply?` deliberately excludes `:mutation` there.
  `:error` is the transport's `:rf.http/*` failure envelope — no projection of
  owner data, so no source-side redaction reaches it on EITHER half — and both
  halves stamp it identically through the one `reply/failure-reply`. One datum,
  one rule, hence one predicate over both kinds."
  [m]
  (boolean (family-reply-kinds (:rf.reply/work-kind m))))

(defn- redact-reply-declarations
  "The READ-CONTINUATION analogue of the mutation's source-side
  `classification/redact-continuation-reply` (rf2-ko5lm) — literally that
  function, reached at the egress projector instead of at the source.

  `row-owner-redacts?` above reads the COARSE root-prop claim
  (`whole-entry-disposition`). That is the whole of a `:sensitive?` owner's
  declaration and the right grain for tokenizing a WHOLE reply slot, but it is
  not the only claim a resource can make. A spec that declares
  `{:sensitive [[:data :email]]}` and no coarse prop classifies `:serialize`,
  so the coarse arm never fires — and the decoded body carrying that declared
  slot rode the fx carriers VERBATIM, while the very same bytes, landed in the
  durable entry, redact off-box because `reconcile-registry` lowered the
  declaration to the entry's absolute runtime path and the epoch walk reads
  that registry. One value, two carriers, one rule applied: the rf2-irwsq shape
  between the durable entry and the continuation echo of it.

  The mutation half has never had this hole — both settle sites wrap their
  reply in `redact-continuation-reply`, which derives the paths from the spec's
  own projection-relative declaration (rf2-825mzj). The READ reply's carrier
  shape IS the mutation reply's carrier shape — `:value` beside `:params`
  beside `:scope` — so the counterpart is that same function over the read
  owner's spec: a `:data`-rooted / bare decl redacts the reply's `:value`, a
  `:params`-rooted decl its `:params`, a `:scope`-rooted decl its `:scope`.
  Never a second classification language, and nothing new to keep in step.

  ## Grain: DECLARATION-conditional

  This arm fires on the paths the owner DECLARED and on nothing else — not
  unconditionally (an owner that declares nothing rides verbatim) and not
  coarse-owner-conditionally (that read is exactly what misses a `:serialize`
  owner's declaration). The declaration is the thing whose two carriers must
  agree, so its own grain is what makes them agree: the durable entry redacts
  `[:data :email]` and so now does the reply's `:value`. It COMPOSES with the
  coarse arm rather than replacing it — the caller applies this only when
  `row-owner-redacts?` is false, so a coarse owner still tokenizes the whole
  slot and the digests that arm produces are untouched.

  ## Why at egress and not at the source, where the mutation does it

  The coarse `:sensitive?` claim governs OFF-BOX egress, not in-process
  delivery: the app's own `:reply-to` handler is entitled to the decoded body,
  and the trusted-local `:include-sensitive?` opt-in must still show it. A
  source-side redaction would take both away.

  A reply naming an UNREGISTERED owner rides unchanged here and needs no
  fail-closed arm of its own: a nil spec makes `project-trace-scoped-key`'s
  nil-spec branch redact, so `row-owner-redacts?` is already true and the
  caller has tokenized the whole payload before reaching this. Reference-
  preserving when the spec declares neither axis. Pure.

  ## Feeds read index-free (rf2-zaopo, closed)

  `redact-with-paths` matched paths EXACTLY, so on an INFINITE feed a
  `[:data :email]` decl reached nothing in the merged-items vector
  `infinite-reply-value` delivers under `:value` (`[:value <i> :email]`) while
  the durable side matched it on every page (`project-entry-data`).
  `redact-continuation-reply` now opts into the walker's `:index-free?` mode,
  which is the reading a projection-relative declaration always had on the
  durable side. Opt-IN, so the walker's other callers — which declare concrete
  paths — keep the exact match.

  ## The sibling copy, since closed (rf2-dl7bz)

  A `:params`-rooted declaration also names bytes that ride the SIBLING
  `:resource/key`, one slot over on the same carrier, and a `:serialize`
  owner's key rode there verbatim. That copy is closed by
  `redact-key-declarations` above, which is this fn's shape re-rooted onto the
  key's two components instead of the reply's sibling slots —
  `ssr/project-scoped-key`'s documented `:serialize` deferral left intact,
  because the per-slot arm belongs at the boundary that has no registry to read
  rather than inside the projection the SSR durable path shares."
  [reply]
  (let [rid  (second (:resource/key reply))
        spec (when (keyword? rid) (registry/resource-meta rid))]
    (if spec
      (classification/redact-continuation-reply reply spec)
      reply)))

(defn- carrier-family-value?
  "Whether a value sitting in a FOREIGN carrier is a resource scoped key the
  family may speak for. Scoped-key SHAPE is necessary and not sufficient here,
  and that asymmetry is the whole of rf2-1kiuj's audit #7031.

  On a row the family OWNS, the operation namespace already proves the family
  emitted every tag on it, so shape alone is safe and
  `project-unknown-slot-value` reads nothing more (rf2-wd9im). A carrier row is
  the FX family's, and `[<anything> <keyword> <map>]` is a shape ordinary
  application data hits constantly — an event vector `[:app/save :user {…}]`
  has it. Projecting one destroys app data off-box and stamps the row
  `:sensitive?`, which for the resource tools is as much a defect as the leak.

  So the family must PROVE the key is its own, and there are exactly two
  proofs, neither of them a roster:

    - `named?` — the value was reached through the family's own reserved
      vocabulary (a `resource`-namespaced map key, or position 1 of a
      `[:rf.work/resource …]` work-id). The runtime named the position itself,
      so whatever sits there is the family's WHATEVER the registry says — which
      is what keeps the fail-closed arm reachable for a genuine key whose owner
      was cleared or hot-reloaded away (`project-trace-scoped-key`'s nil-spec
      arm);
    - the RESOURCE REGISTRY knows the id. The family's own authority answering
      \"is this one of mine?\" — positive proof independent of position, so a
      registered owner's key still projects from a carrier slot nobody has
      looked at yet.

  A shape match with neither proof is somebody else's data and rides verbatim.

  Note this reads the bare `scoped-key-frame?` SKELETON and not
  `scoped-key-shape?`: the params `map?` test that predicate uses as ONE of its
  two proofs would be a THIRD requirement here, and a redundant one — either
  proof above is already stronger. Requiring it is what made the `named?` arm
  above contradict its own docstring: a genuine `:resource/key` whose owner was
  cleared or hot-reloaded away, carrying legal NON-MAP canonical params, failed
  the map test and rode a carrier verbatim, so the fail-closed arm `named?`
  exists to keep reachable was not reached (merged-PR audit #7013)."
  [v named?]
  (and (scoped-key-frame? v)
       (or named? (some? (registry/resource-meta (nth v 1))))))

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
  scoped key the family can PROVE is its own (`carrier-family-value?`) projects
  through `project-trace-scoped-key` — the SAME owner classification the family
  rows' own `:resource/key` takes, so the two carriers of one key cannot drift;
  any other collection is walked through, preserving its KIND (scoped-key
  identity is kind-sensitive — rf2-wgutc2) and walking a map's KEYS as well as
  its values (a key can be map-keyed by scoped key); every other scalar rides
  verbatim.

  ## `named?`, and why the carrier needs a proof the family's own rows do not

  The `named?` argument is that proof, threaded down the walk: it is true when
  the value sits somewhere the family's RESERVED vocabulary put it — under a
  `resource`-namespaced map key, or at position 1 of a `[:rf.work/resource …]`
  work-id. It resets at every map entry (each key answers for its own value)
  and is inherited through sequential collections, which is what carries it
  from `:resource/keys` to the keys inside.

  Every arm below reads a proof of that kind rather than a local cue, because a
  local cue is something ordinary FX data hits by coincidence, and three merged
  repairs each shipped one:

    - a scoped-key SHAPE is not proof a 3-vector is a scoped key
      (`carrier-family-value?`);
    - a `:scope` KEY is not proof the map is a runtime continuation payload
      (`carrier-family-payload?`);
    - a sibling `:resource/key` is not proof the map is a read reply
      (`resource-reply?`).

  Each proof is the family's own reserved vocabulary — a reserved keyword
  namespace, a reserved work-id head, a canonical reply marker, or the resource
  registry itself. None of them is a slot roster, and none of them has to be
  kept in step with an emit site.

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

  A `:scope` entry OF A RUNTIME CONTINUATION PAYLOAD (`carrier-family-payload?`)
  is therefore projected by the FAMILY rule (`project-unknown-slot-value`)
  rather than by the carrier walk, which is what makes the two carriers of one
  scope agree by construction: `:rf.scope/global` is a SCALAR and rides
  verbatim, a `[tier {identity}]` tuple keeps its TIER keyword (a tool still
  reads \"session scope\") while the identity map tokenizes to
  a content-FREE `{:rf/redacted <shape>}` — a free scope tag carries no
  owner claim that could permit a digest (rf2-hzcv8) — and an unresolved `{:from-db …}`
  reference tokenizes whole. Exactly what rf2-1zc33 settled for the same slot on
  the family's OWN rows; this is that ruling reaching the carrier the family
  projector never runs on.

  The PAYLOAD gate is load-bearing and the arm shipped without it. A resolved
  scope is arbitrary EDN, so there is no shape to read and the arm has to key on
  the NAME — but `:scope` is an ordinary English word, and an app's own
  `{:request {:method :post :scope {:tenant \"…\"} …}}` rides these same
  carriers. Keying on the name ALONE destroyed that map off-box and stamped the
  row `:sensitive?`. What the family actually owns is the payload the runtime
  BUILT, and that payload wears the family's reserved vocabulary — a
  `resource`-namespaced key, or a `[:rf.work/resource …]` work-id. Everything
  else in the carrier is still the fx family's and still rides through untouched.

  ## …AND a read continuation reply's `:value` + `:params` (rf2-xx4ty)

  The transport payload is not the only map the family plants in a foreign
  carrier. An `ensure` / `refetch` MAY carry a call-site `:reply-to`; when the
  read settles, `events/read-continuation-reply` augments the canonical reply
  with the top-level read facts and `re-frame.reply/complete` appends the whole
  map as the target event's final argument, dispatched via `[:dispatch <ev>]`.
  So `{:status :ok :value <DECODED RESPONSE BODY> :params <canonical params>
  :scope … :resource/key <scoped-key> :cache-hit? …}` rides the same two
  carriers. The `:resource/key` projected (scoped-key-shaped) and the `:scope`
  took the arm above, but `:value` and `:params` are ordinary maps: the walk
  descended them and the owner's decoded body egressed in the clear one slot
  from the key that had just redacted its params.

  ### Why this one is OWNER-CONDITIONAL where `:scope` is unconditional

  Not a style choice — the two would DISAGREE for a plain owner if it were, and
  each way round is wrong for the other datum.

  `:scope` is unconditional because the family's own rows classify a free
  `:scope` unconditionally (rf2-1zc33) and the two carriers of one scope must
  agree. Note the grain distinction is about the CLASSIFICATION and not about
  the recognition: both arms now require the same kind of proof that the family
  planted the datum, and only then do they differ in how they treat it.

  `:value` and `:params` are owner-conditional. They belong to a NAMED owner
  whose `:resource/key` sits one slot over, and the family's own rows tokenize
  that owner's params IFF `whole-entry-disposition` is non-`:serialize`, so
  unconditional tokenization would redact a PLAIN resource's reply — the
  over-redaction rf2-1kiuj rejected, and the reason this walk descends maps at
  all. Worse, `:params` and `:value` are words the FX family uses for its own
  data: an app's managed-HTTP args carry `{:request {… :params {…}}}`, and
  `[:rf.resource/commit-generation {:value 1}]` rides the same effect vector, so
  a name-only arm would tokenize a plain app's request params and the runtime's
  generation counter. The grain is therefore `cursor-slot`'s, one carrier out —
  `row-owner-redacts?` on the map's OWN `:resource/key`, the same read that
  governs the load-more cursor (rf2-3tysyj) and the same
  `whole-entry-disposition` that governs the key beside it.

  And the owner read is a CLASSIFICATION, not a recognition: a sibling
  `:resource/key` says whose data it would be, not that these two words are the
  family's at all. A map carrying a genuine sensitive key beside an app's own
  `:value` / `:params` satisfied the owner read and had them destroyed
  (audit #7059). The recognition is the reply's own canonical marker
  (`resource-reply?` — `:rf.reply/work-kind :resource`, which every reply the
  read-continuation substrate builds carries and nothing else does).

  ### Reads and mutations differ here, and the difference is already handled

  The mutation `:reply-to` (`mutation_events/continuation-reply`) is the sibling
  shape: it stamps `:rf.reply/work-kind :mutation`, so `resource-reply?` is
  false and this arm does not fire on it — deliberately. The mutation half is
  closed one layer EARLIER, at the
  source: both settle sites wrap the reply in
  `classification/redact-continuation-reply`, which redacts the mutation's own
  projection-relative `:sensitive` / `:large` declarations before the reply ever
  reaches a carrier (rf2-825mzj). A coarse `:sensitive?` ROOT prop is not part
  of `reg-mutation`'s declaration surface and is inert at every mutation egress
  boundary, not merely this one.

  The read reply has no such source-side redaction, and MUST NOT: the coarse
  `:sensitive?` claim governs OFF-BOX egress, not in-process delivery — the
  app's own continuation handler is entitled to the decoded body, and the
  trusted-local `:include-sensitive?` opt-in must still show it. So the read
  half belongs exactly here, at the egress projector.

  ## …AND the owner's DECLARED paths, when it makes no coarse claim (rf2-ko5lm)

  The owner read above is `whole-entry-disposition` — the COARSE root prop. A
  spec that declares `{:sensitive [[:data :email]]}` and no coarse prop
  classifies `:serialize`, so that read says nothing and the reply rode
  verbatim — while the same bytes in the durable entry redact, because the
  declaration was lowered into the frame's elision registry and the epoch walk
  reads it. `redact-reply-declarations` closes that half by applying the
  mutation's own `classification/redact-continuation-reply` over the read
  owner's spec, and the two arms compose by grain rather than overlap: coarse
  claim ⇒ the whole `:value` / `:params` tokenize; declaration only ⇒ the
  declared paths substitute in place and their undeclared siblings ride;
  neither ⇒ the reply is byte-identical. Pure.

  ## …AND the FAILURE ENVELOPE, on BOTH halves of the family (rf2-rnsv2)

  Everything above is about the reply the read SUCCEEDED with. A read that
  FAILS settles the same carriers with the same canonical reply — `failure-reply`
  composes the same `base-reply` — carrying the transport's classified
  `:rf.http/*` envelope under `:error`. That envelope is the app's own data
  coming back out: HTTP's own redactor enumerates `:body` / `:body-text` /
  `:decoded` / `:detail` / `:headers` as its app-bearing slots, and a 422's
  `:detail` is the app's domain failure map — the SUBMITTED FORM FIELDS. It
  arrives raw, because the transport redacts only its OWN trace row and hands
  `:on-failure` the envelope verbatim. Every other slot of that reply was
  already classified by the arms above; `:error` alone rode the `:else` walk out
  as an ordinary map.

  ### The grain here is neither of the two above, and that is the point

  `:error` is UNCONDITIONAL INSIDE THE FAMILY MARKER — no owner read at all.
  The full argument is on `reply-error-slot`; the one-line form is that the
  family's own rows tokenize this envelope regardless of owner
  (`error-envelope-slot`, consumed unconditionally), so an owner-conditional
  carrier arm would leave a `:serialize` owner's envelope raw on the carrier
  while the identical bytes on `:rf.resource/failed` tokenize. The three grains
  now in this walk each answer to WHOSE datum it is: `:scope` is the runtime's
  (unconditional inside the payload proof), `:value` / `:params` are the OWNER'S
  (conditional on the owner's claim), `:error` is the TRANSPORT'S (unconditional
  inside the reply proof — no owner speaks for it).

  ### …and it is the one arm that spans reads AND mutations

  `resource-reply?` excludes `:rf.reply/work-kind :mutation` deliberately,
  because the mutation redacts its OWN `:value` / `:params` / `:scope` at the
  source. `redact-continuation-reply` re-roots the spec's projection-relative
  declarations and never touches `:error` — correctly, since `:error` is not a
  projection of owner data and no declaration can name it. So the mutation
  failure continuation leaked the identical envelope by the identical route,
  seen by nobody. The `:error` arm therefore gates on `family-reply?` — both
  work kinds — while the arm above it keeps `resource-reply?`. One keyword
  wider, and it is what stops this repair shipping its own sibling leak.

  On `failed-handler`'s branch the carrier is not merely a disagreeing second
  copy but the ONLY off-box copy: that settle row (`:rf.resource/failed` /
  `:rf.resource/refresh-failed`) stamps `:status-before` / `:status-after` and
  no `:error` at all."
  [v frame-id named?]
  (cond
    (redacted-token? v) [v true]

    ;; a scoped key the family can PROVE is its own — see `carrier-family-value?`
    (carrier-family-value? v named?)
    (project-trace-scoped-key v frame-id)

    (coll? v)
    (let [sens?*   (volatile! false)
          note!    (fn [s pv] (when s (vreset! sens?* true)) pv)
          proj     (fn [x named?]
                     (let [[pv s] (project-embedded-keys x frame-id named?)]
                       (note! s pv)))
          ;; rf2-xx4ty — a read continuation reply's `:value` / `:params`, read
          ;; through the owner its own `:resource/key` names (ONCE per map,
          ;; exactly as `project-tags*` reads it once per row for the cursor).
          ;; Gated on the canonical reply MARKER (audit #7059), not on the mere
          ;; presence of a sibling key.
          ;; the two RECOGNITION reads, hoisted out of the classification so
          ;; each arm below takes only the proof it needs: the `:value` /
          ;; `:params` arm is a READ reply PLUS the owner's coarse claim, the
          ;; `:error` arm (rf2-rnsv2) is the FAMILY marker alone.
          reply?         (and (map? v) (resource-reply? v))
          fam-reply?     (and (map? v) (family-reply? v))
          owner-redacts? (and reply? (row-owner-redacts? v frame-id))
          ;; rf2-ko5lm — …and, when that COARSE read says nothing (a
          ;; `:serialize` owner, which is what a spec declaring only
          ;; projection-relative paths classifies as), the owner's DECLARED
          ;; paths, substituted through the mutation reply's own
          ;; `redact-continuation-reply`. Applied to the whole reply BEFORE the
          ;; walk, so the walk descends the already-substituted map and the
          ;; `:rf/redacted` / size-marker sentinels ride out as the scalars they
          ;; are. Reference-preserving — and stamp-precise: a declaration that
          ;; matched nothing in THIS reply leaves the row unstamped.
          v        (if (and reply? (not owner-redacts?))
                     (let [v' (redact-reply-declarations v)]
                       (if (= v' v) v (note! true v')))
                     v)
          ;; position 1 of a `[:rf.work/resource …]` vector is family-planted by
          ;; construction, so the key there is NAMED however unregistered its
          ;; resource-id has become.
          work-id? (family-work-id? v)
          ;; rf2-425mm — the family's own resolved scope, planted in a foreign
          ;; payload by the runtime, projected by the FAMILY rule so the carrier
          ;; cannot drift from the row. Gated on the payload being provably the
          ;; runtime's (audit #7054): `:scope` is a word apps use too.
          payload? (and (map? v) (carrier-family-payload? v))
          entry    (fn [k x]
                     (cond
                       (and payload? (= :scope k))
                       (let [[pv s] (project-unknown-slot-value x frame-id)]
                         (note! s pv))

                       ;; rf2-l6wjl — that same resolved scope's SECOND
                       ;; spelling, inside the reply's `:correlation` facts.
                       ;; `carrier-family-payload?` reaches a READ reply's
                       ;; correlation (it wears `:rf.reply/resource-key`) and
                       ;; not a MUTATION reply's (which wears no reserved
                       ;; vocabulary at all), so one half of the family cleaned
                       ;; the slot and the other rode it raw. Gated on the
                       ;; canonical reply MARKER instead: inside a proven family
                       ;; reply, `:correlation` is the map `base-reply` built.
                       ;; Deliberately NOT a member of `reply-payload-slot` —
                       ;; that set is owner-conditional and this must not be
                       ;; (see the `reply-correlation-slot` docstring). Only
                       ;; `:scope` takes the family rule; every sibling fact
                       ;; takes the ordinary walk, NAMED iff the family reserved
                       ;; its key, which is what keeps a read correlation's
                       ;; `:rf.reply/resource-key` projecting exactly as before.
                       ;; The keys themselves are `base-reply`'s literal
                       ;; keywords, so they ride as-is.
                       (and fam-reply? (= reply-correlation-slot k) (map? x))
                       (reduce-kv
                         (fn [m ck cx]
                           (assoc m ck
                                  (if (= :scope ck)
                                    (let [[pv s] (project-unknown-slot-value cx frame-id)]
                                      (note! s pv))
                                    (proj cx (family-named-key? ck)))))
                         {} x)

                       ;; rf2-rnsv2 — the TRANSPORT FAILURE ENVELOPE of a family
                       ;; reply (read OR mutation). Tokenized UNCONDITIONALLY
                       ;; inside the marker, matching `error-envelope-slot`'s
                       ;; unconditional treatment on the family's own
                       ;; `:rf.resource/failed` / `:rf.mutation/failed` rows, so
                       ;; the two carriers of one envelope agree. Deliberately
                       ;; NOT a member of `reply-payload-slot` — that set is
                       ;; owner-conditional and this must not be (see the
                       ;; `reply-error-slot` docstring). Placed FIRST so the
                       ;; precedence is explicit.
                       (and fam-reply? (reply-error-slot k) (some? x))
                       (note! true (if (redacted-token? x) x (ssr/redact-value x)))

                       ;; the owner's own reply data — tokenized
                       ;; content-FREE (rf2-hzcv8) iff THIS reply's owner redacts, so a
                       ;; plain resource's reply stays fully readable. Idempotent
                       ;; (an opaque token stays sensitive and is not re-digested).
                       (and owner-redacts? (reply-payload-slot k) (some? x))
                       (note! true (if (redacted-token? x) x (ssr/redact-value x)))

                       ;; every other entry takes the walk, NAMED iff the family
                       ;; reserved the key it sits under.
                       :else (proj x (family-named-key? k))))]
      [(cond
         (map? v)    (reduce-kv (fn [m k x] (assoc m (proj k named?) (entry k x))) {} v)
         (set? v)    (into #{} (map #(proj % named?)) v)
         (vector? v) (if work-id?
                       (into [] (map-indexed (fn [i x] (proj x (or named? (= 1 i))))) v)
                       (mapv #(proj % named?) v))
         (seq? v)    (apply list (map #(proj % named?) v))
         :else       (mapv #(proj % named?) v))
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

                ;; cursor: a non-nil free cursor tag tokenizes (content-FREE, rf2-hzcv8)
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
                ;; is UNCONDITIONALLY tokenized off-box (content-FREE via
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
                ;; projector runs on the whole family, so a pass-through left the
                ;; resolved scope classified by nobody on every OTHER row type
                ;; that stamps one. The `sibling-owned-slot` docstring rosters
                ;; them.
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
  tokenize to classification-chosen `{:rf/redacted <digest>}` (distinct
  values stay distinct, so a tool's per-key joins survive); an owner making no
  coarse claim has its per-slot `:params` / `:scope` DECLARATIONS substituted
  inside the key (`redact-key-declarations`, rf2-dl7bz) so the declaration the
  durable entry honours is honoured on the key too; a plain owner's key
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
  sibling `:resource/key`): tokenized to a content-FREE `{:rf/redacted
  <shape>}` when that owner is non-`:serialize` (sensitive / large /
  unregistered fail-closed), riding verbatim for a plain feed (no
  over-redaction).

  The free `:scope` tag — the RESOLVED CONCRETE scope the family stamps on the
  row types `sibling-owned-slot`'s docstring rosters — also takes the shape
  default (rf2-1zc33). It is NOT sibling-owned: the `:rf.resource/scope-resolved`
  projector the epoch tool-pair runs first is applied on that ONE operation, so
  on every rostered row a pass-through left the resolver's identity map
  classified by nobody. Under the shape default `:rf.scope/global` rides
  verbatim, and a `[:rf.scope/session {…}]` tuple keeps its TIER keyword while
  the identity map tokenizes — so `:rf.resource/refetch-decision`, which carries
  one scope under BOTH `:resource/key` and `:scope`, can no longer redact and
  leak the same value side by side.

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

  Given a row's `tags` + the `frame-id`, walks `:rf.fx/args` / `:rf.event/fx`
  (`project-embedded-keys`) and stamps `:sensitive? true` when a key redacted.
  Everything else on the row rides UNTOUCHED, whatever its shape: the row
  belongs to the fx family, and the resource family speaks only for the data it
  planted there — its scoped keys, and (rf2-425mm) the resolved `:scope` the
  transport continuation payload carries beside them, which is a
  `[tier {identity}]` TUPLE rather than a scoped key and so was descended into
  and let through in the clear one slot from the `:resource/key` that had just
  redacted the same bytes, and (rf2-xx4ty) the `:value` + `:params` a READ
  CONTINUATION reply carries beside its own `:resource/key`, tokenized iff that
  key's OWNER redacts so a plain resource's reply stays readable — and
  (rf2-ko5lm) that same reply's owner-DECLARED projection-relative paths, which
  a coarse-only owner read cannot see, substituted through the mutation half's
  own `redact-continuation-reply` so the continuation echo classifies exactly
  as the durable entry it echoes, and (rf2-rnsv2) the transport FAILURE ENVELOPE
  under `:error` on the failing / cancelled counterpart of that same reply —
  the decoded error body, which routinely echoes the SUBMITTED FORM FIELDS —
  tokenized UNCONDITIONALLY inside the family reply marker, on the MUTATION
  continuation as well as the read one, because the envelope belongs to the
  transport rather than to any resource owner and the family's own rows tokenize
  it regardless of owner — and (rf2-l6wjl) that reply's `:correlation` facts,
  whose `:scope` is the SAME resolved identity the free `:scope` beside it
  carries, gated on the same family marker so the reply's three copies of one
  scope project under one rule instead of two.

  \"Speaks only for the data it planted\" is enforced, not merely intended:
  each of those arms fires on PROOF that the runtime planted the value —
  the family's reserved keyword namespace, its work-id head, the canonical
  reply marker, or the resource registry — never on a local cue that ordinary
  application data hits by coincidence. See `project-embedded-keys` §`named?`.

  A tags map carrying NEITHER slot rides through reference-preserved —
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
                             ;; `named? false` — the slot root is the FX
                             ;; family's own value; nothing above it named it.
                             (let [[pv s] (project-embedded-keys (get m slot) frame-id false)]
                               (when s (vreset! sens?* true))
                               (assoc m slot pv))))
                         tags
                         fx-carrier-slot)]
      (cond-> tags' @sens?* (assoc :sensitive? true)))))
