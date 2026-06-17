(ns re-frame.resources.ssr
  "LATE-BOUND SSR / hydration integration for resources. Per Spec 016
  §SSR and hydration and §Restore and replay.

  SSR MUST use request-local frames — a process-global resource cache
  would leak data between users. On a server render the runtime resolves
  the route, computes route resources, enqueues blocking ensures, DRAINS
  until the blocking resources for the current nav-token settle, renders
  with the settled state, and serializes ONLY the allowed resource-runtime
  projection (NEVER all of `:rf.db/runtime`). On client hydration the
  allowed projection installs into the target frame-state's
  `:rf.runtime/resources` slice in runtime-db; hydrated entries are
  preserved, fresh ones avoid a duplicate fetch, stale ones
  background-refetch by event, and hydration NEVER crosses scopes.

  ## What lives here (the SSR slice, rf2-ctk2av)

  This namespace is PURE / host-agnostic. SSR runs on the JVM, so the
  whole body is reader-conditional CLJC and the heavy lifting is plain
  data transforms the host adapter / route slice drives:

  - **Server projection** — `project-resources-runtime-db` (the body
    behind the `:ssr/extend-runtime-db-projection` hook) projects the
    durable `:entries`, applying per-entry REDACTION / OMISSION through
    the resource's `:sensitive?` / `:large?` classification and the
    shared `rf/elide-wire-value` walker, and `projection-metadata`
    records the serialized / redacted / omitted / fresh / stale /
    refetch-on-client decision per entry.
  - **Server blocking drain** — `blocking-settled?` is the drain
    PREDICATE the host loops on (\"have the blocking resources for this
    nav-token settled?\"); `settle-blocking-timeout` is the timeout
    POLICY (settle each unsettled blocking entry as a structured
    first-load failure + produce a route-blocking-failure record), so a
    blocked SSR render never hangs indefinitely.
  - **Client hydration** — `hydrate-runtime-db` reconciles a freshly
    INSTALLED resource subtree on `:rf/hydrate`: it recomputes the
    reverse indexes from `:entries` (never trusts the wire), reconciles
    owners (SSR owners orphan — they belong to a settled server render),
    and surfaces server clock skew. `hydrate-refetch-plan` decides which
    hydrated entries need a client refetch (omitted / redacted →
    metadata-only refetch; stale → background refetch; fresh → no
    double-fetch).

  ## Late-binding both ways

  Resource hydration uses an EXPLICIT projection hook — the
  allowlist-by-subsystem-child `project-runtime-db` of [011-SSR]. Rather
  than statically `:require`ing SSR (which would drag the SSR body into
  every resources app), resources publishes the LATE-BOUND
  `:ssr/extend-runtime-db-projection` hook (server projection) and the
  `:resources/hydrate-runtime-db` hook (client reconcile); SSR consults
  them. The wiring is late-bound BOTH ways — an app that loads resources
  but does not SSR carries none of this code path, and an SSR app without
  resources sees no behaviour change.

  Only the durable resource projection (`:rf.runtime/resources` →
  `:entries`) rides the wire; `:tag-index` / `:owner-index` are
  recomputable-from-`:entries` (rebuilt on install, never trusted from the
  snapshot — Spec 016 §Restore and replay part 5) and need not ride. Per
  Spec 016 §Runtime-subsystem graduation clause 4."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.resources.classification :as classification]
            [re-frame.resources.mutation-registry :as mutation-registry]
            [re-frame.resources.mutation-runtime :as mutation-runtime]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.state :as state]
            [re-frame.resources.timers :as timers]
            [re-frame.resources.work-ledger :as work-ledger]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; `hydrate-runtime-db` (the reconcile) computes the client refetch plan via
;; `hydrate-refetch-plan` (defined below) on the `:rf/hydrate` install path
;; (rf2-fopuj9), so the latter is forward-declared. It also settles a hydrated
;; non-terminal entry whose `:current-work` was stripped on the wire to its last
;; stable status via `settle-entry-to-last-stable` (rf2-bg6qah), shared with the
;; restore path — forward-declared for the same file-order reason.
(declare hydrate-refetch-plan)
(declare settle-entry-to-last-stable)

;; ---- shared clock ---------------------------------------------------------
;;
;; The WALL-clock epoch-ms read this slice uses — the server clock stamped on
;; `loaded-at` / `stale-at`, and the live clock the client compares restored
;; absolute timestamps against to surface skew — is the core
;; `re-frame.interop/epoch-now-ms` (rf2-366u0g). It is byte-identical to the
;; private `now-ms` this ns used to define (`System/currentTimeMillis` on the
;; JVM, `js/Date.now` on CLJS) and is the canonical EP-0010 §Time wall-clock
;; surface — NOT the perf clock `interop/now-ms` (`performance.now()` on CLJS,
;; origin-relative, ~1e4), which is incomparable with `js/Date`-based freshness
;; checks. These resources are durable freshness / skew readers, so the wall
;; clock is correct.
;;
;; `interop/epoch-now-ms` is PUBLIC (rf2-wshzsp test seam preserved): the
;; restore-reconcile suite `with-redefs`-stubs it to a sentinel and ADVERSARIALLY
;; pins that restore reads it ONLY for the clock-skew DIAGNOSTIC — never to
;; freshen a DURABLE restored entry / instance timestamp (EP-0010
;; §Restore/Replay: "restored resource entries do not re-read the live clock
;; during install").

;; ---- freshness classification (Spec 016 §Status semantics) ----------------
;;
;; Freshness is computed from the entry's DURABLE absolute timestamps
;; (`:stale-at` / `:invalidated-at`) against a supplied clock — never from
;; trusting a timer fired on time (Spec 016 §Stale and GC scheduling). The
;; SAME derivation the subs layer uses, lifted here so the server projection
;; metadata and the client refetch decision agree.

(defn entry-stale?
  "True iff `entry` is stale against `clock-ms`: it has been explicitly
  invalidated (`:invalidated-at` set) OR its `:stale-after-ms` window has
  elapsed (`:stale-at` set and `clock-ms >= :stale-at`). Freshness is
  ORTHOGONAL to load status (a `:loaded` entry may be stale). Per Spec 016
  §Status semantics."
  [entry clock-ms]
  (boolean
    (and entry
         (or (some? (:invalidated-at entry))
             (when-let [sa (:stale-at entry)] (>= clock-ms sa))))))

;; ---- per-entry sensitivity / size classification (Spec 016 clause 4) ------
;;
;; EP-0015 §6 reconciliation (rf2-5pld34). Params, scopes, and data carry
;; `:sensitive?` / `:large?` classification OWNED by the resource definition
;; (Spec 015 §Resource and mutation durable classification). The owner
;; surface, per EP-0015 issue 11 (ruled):
;;
;;   - the coarse whole-entry `:sensitive?` / `:large?` claims on the
;;     resource spec are the degenerate ROOT-PROP case (the whole resource is
;;     the classification unit) — they gate the metadata-only redact / omit
;;     shape (`whole-entry-disposition`);
;;   - per-slot `:sensitive?` / `:large?` props on the `:data-schema` /
;;     `:params-schema` are the canonical FINE-GRAINED surface (the same
;;     EP-0005 mechanism the machine `:data-schema` uses) — there is NO new
;;     resource path-map vocabulary.
;;
;; A `:sensitive?` (or `:large?`) resource must NOT ship its data verbatim
;; onto the wire — every visitor of every SSR page would otherwise receive
;; it. The whole-entry coarse claim drives redact / omit; a `:serialize`
;; entry's data slice still rides through the merged frame-owned
;; `re-frame.projection/project-egress` (over the SHARED `rf/elide-wire-value`
;; walker) under the SSR boundary profile, so any per-slot `:data-schema`
;; mark the frame classification carries composes as defense-in-depth. The
;; classification + projection live in `re-frame.resources.classification`
;; (the owner-classification seam); this slice consults it — never a
;; family-private elider.

;; The per-entry disposition (`:serialize` / `:redact` / `:omit`) is computed
;; inside `project-entry` (`classification/whole-entry-disposition-for` over the
;; resource OWNER's coarse root-prop `:sensitive?` / `:large?` claim PLUS the
;; named-scope-resolver derived-sensitivity inheritance arm — EP-0015 §6 / issue
;; 11 + EP-0016 wave rf2-fi6tda.1; Spec 015 §Derived sensitivity). Sensitive wins
;; over large (the redaction sentinel is the more conservative shape).

;; ---- scoped-key privacy (Spec 016 clause 4, rf2-otms75) -------------------
;;
;; The scoped resource KEY (`[scope resource-id params]`) is the MAP KEY of the
;; projected `{scoped-key wire-entry}` slice — so even when the entry's DATA is
;; redacted/omitted, the raw scope + params still ride in the key. Spec 016
;; §Runtime-subsystem graduation clause 4 / §Xray and AI tooling: "params,
;; scopes, and data carry the same classification" — a `:sensitive?` resource's
;; scope (user / tenant / impersonation markers) or a `:large?` resource's
;; params MUST NOT ride raw on the hydration wire any more than its data does.
;;
;; The client refetch is ROUTE-DRIVEN: the route plan re-resolves params from
;; the LIVE route on the client and re-ensures under a freshly-computed scoped
;; key (route.cljc/route-resource-plan). So a redacted/omitted hydrated entry
;; does NOT need its original raw scope/params to refetch — it only needs (a)
;; the resource-id (position 1, never sensitive) so the refetch plan can name
;; it, and (b) a DISTINCT key so the index recompute does not collapse two
;; entries. We therefore project the sensitive/large scope + params to an
;; OPAQUE, content-addressed DIGEST: distinct values stay distinct (no
;; collision / lost entry), the raw identity never rides, and the digest is
;; deliberately one-way (refetch is route-driven, not key-driven).

(def ^:private fnv-offset-basis 2166136261)
(def ^:private fnv-prime 16777619)

(defn- fnv-1a-32
  "A deterministic, cross-process-stable 32-bit FNV-1a hash of `s` (a string),
  returned as an 8-char lower-case hex. Used to content-address a redacted
  scope / params value so distinct keys stay distinct on the wire without the
  raw value riding. Self-contained (no dep on the SSR artefact's hash)."
  [^String s]
  (let [bytes #?(:clj (.getBytes s "UTF-8") :cljs s)
        n     #?(:clj (alength ^bytes bytes) :cljs (.-length bytes))]
    (loop [i 0
           h fnv-offset-basis]
      (if (< i n)
        (let [b #?(:clj (bit-and (aget ^bytes bytes i) 0xff)
                   :cljs (bit-and (.charCodeAt bytes i) 0xff))
              h' (-> (bit-xor h b)
                     (* fnv-prime)
                     (bit-and 0xffffffff))]
          (recur (inc i) h'))
        #?(:clj  (format "%08x" (bit-and h 0xffffffff))
           :cljs (let [hx (.toString (bit-and h 0xffffffff) 16)]
                   (str (subs "00000000" 0 (max 0 (- 8 (count hx)))) hx)))))))

(defn redact-value
  "Project an owner-local identity-bearing `value` to its wire shape under a
  metadata-only (`:redact` / `:omit`) classification: a `{:rf/redacted
  <digest>}` token whose digest content-addresses the canonical value (Spec
  016 clause 4 / rf2-otms75). Distinct values get distinct digests (so a
  projected key / cursor stays unique — the index recompute never collapses
  two entries, and a tool's per-page joins survive), the raw value never
  rides, and the token is opaque (the client refetches route-driven, not from
  this digest). A nil / empty value (the empty-scope / no-params case)
  projects to a stable `{:rf/redacted nil}` — there is nothing sensitive to
  hide.

  Used both for a scoped key's scope / params components (`project-scoped-key`)
  and for the off-box trace-egress projection of the load-more cursor tag
  (`:page-param` / `:next-page-param` — rf2-3tysyj), which can carry record
  ids; the same content-addressed tokenizer keeps the two boundaries from
  drifting."
  [value]
  (if (or (nil? value) (and (coll? value) (empty? value)))
    {:rf/redacted nil}
    {:rf/redacted (fnv-1a-32 (pr-str value))}))

(defn project-scoped-key
  "Project a scoped resource KEY (`[scope resource-id params]`) to its wire
  shape per the resource's `disposition` (Spec 016 clause 4 / rf2-otms75):

    - `:serialize` — the key rides with its scope verbatim, but its PARAMS
      are projected through the resource OWNER's per-slot `:params-schema`
      classification (`classification/project-params`) so a params slot the
      owner marked `:sensitive?` / `:large?` does NOT ride raw even though the
      coarse whole-entry claim leaves the key serialized (EP-0015 issue 11 —
      the CO-EQUAL fine-grained counterpart to the data surface; Spec 016
      clause 4 \"params, scopes, and data carry the same classification\").
      A resource with no `:params-schema` marks rides its params verbatim
      (the wire-safe default — same as its data);
    - `:redact` / `:omit` — the scope and params are replaced by opaque
      content-addressed `{:rf/redacted <digest>}` tokens so the sensitive /
      large raw identity does NOT ride, while the resource-id (position 1) and
      key DISTINCTNESS are preserved (the digest differs per distinct value).
      The COARSE whole-entry claim already redacts the WHOLE params component
      here, so the per-slot `:params-schema` surface is subsumed.

  The wire key keeps the `[scope resource-id params]` SHAPE so the client's
  index recompute + refetch plan parse it unchanged (resource-id at position
  1). `spec` is the resource owner spec (`registry/resource-meta`), carrying
  the `:params-schema` marks; nil / no marks → the params ride verbatim on
  `:serialize`. PURE."
  [scoped-key disposition spec]
  (if (= :serialize disposition)
    (let [[scope resource-id params] scoped-key]
      [scope resource-id (classification/project-params params spec)])
    (let [[scope resource-id params] scoped-key]
      [(redact-value scope) resource-id (redact-value params)])))

(defn disposition+project-key
  "Resolve a scoped key's resource OWNER spec, compute its frame-aware
  whole-entry disposition, and project the key accordingly — the shared
  disposition+project-key pipeline (rf2-366u0g). Returns
  `[projected-key disposition spec]`:

    1. `spec`        ← `registry/resource-meta` of `scoped-key`'s resource-id;
    2. `disposition` ← `classification/whole-entry-disposition-for spec frame-id`
       (the coarse owner `:sensitive?` / `:large?` claim PLUS the named-scope-
       resolver derived-sensitivity inheritance against the frame);
    3. projected key ← `project-scoped-key scoped-key disposition spec`
       (`:redact` / `:omit` replace scope + params with opaque content-addressed
       `{:rf/redacted <digest>}` tokens; `:serialize` projects per-slot
       `:params-schema` marks — the resource-id always survives).

  The single home for the pipeline the SSR durable-egress projection
  (`project-entry`), the TOOL-egress algebra view (`tooling/project-key-for-
  egress`), and the off-box TRACE-egress projector
  (`trace_egress/project-trace-scoped-key`, in its REGISTERED-owner branch —
  it keeps its own nil-spec fail-closed-to-`:redact` + idempotent-token guards
  as the OUTER wrapper) all reuse, so the owner classification + key projection
  never drift between egress boundaries. Pure.

  It lives HERE (not in `classification`) because the pipeline needs both
  `project-scoped-key` (this ns) AND `registry/resource-meta` — and
  `re-frame.resources.registry` already requires `classification`, so hosting
  it in `classification` would introduce a require cycle. `ssr` already
  requires both `classification` and `registry`, and the trace / tool egress
  callers already require `ssr`."
  [scoped-key frame-id]
  (let [resource-id (second scoped-key)
        spec        (registry/resource-meta resource-id)
        disposition (classification/whole-entry-disposition-for spec frame-id)]
    [(project-scoped-key scoped-key disposition spec) disposition spec]))

(defn- project-entry
  "Project a single durable cache `entry` (under `scoped-key`) to its wire
  shape per its classification, against the SSR `frame-id` (so the shared
  elision walker resolves the resource's declared sensitive / large schema
  paths). Returns `[wire-entry metadata]` where `metadata` records the
  per-entry projection decision (Spec 016 §SSR and hydration step 7):

    {:resource/key   scoped-key
     :resource-id    <id>
     :disposition    :serialized | :redacted | :omitted
     :freshness      :fresh | :stale
     :status         <entry :status>
     :refetch-on-client? <bool>
     :loaded-at :stale-at :invalidated-at <absolute ms>}

  - `:serialized` — data rides verbatim;
  - `:redacted`   — `:sensitive?` resource: data replaced by the redaction
    sentinel; the client hydrates it as metadata-only and refetches if the
    route still needs it (`:refetch-on-client? true`);
  - `:omitted`    — `:large?` resource: the `:data` key is dropped entirely;
    metadata-only, refetch-on-client.

  `:refresh-error` rides ONLY when the entry's data is serialized (it is
  the same privacy/size class as data — Spec 016 §SSR and hydration: a
  `:refresh-error` serializes only when the error envelope is allowed by
  the data projection). A redacted/omitted entry drops `:refresh-error`.

  rf2-9e0tyq: the entry carries its own scoped-key VECTOR as `:resource/key`
  (the `:entries` map is keyed on the byte `key-id`), so the scoped key is
  read from the entry, not a separate map-key argument. The wire entry's
  `:resource/key` is PROJECTED through `project-scoped-key` (scope+params
  redacted for a `:redact` / `:omit` resource) so the raw identity never rides
  in the in-entry copy any more than in the wire MAP key."
  [frame-id clock-ms entry]
  (let [scoped-key  (:resource/key entry)
        resource-id (second scoped-key)
        ;; The shared disposition+project-key pipeline (rf2-366u0g) resolves the
        ;; owner spec ONCE, computes the frame-aware whole-entry disposition, and
        ;; projects the scoped key in one call:
        ;;   - the spec carries BOTH the coarse root-prop disposition AND the
        ;;     per-slot `:data-schema` marks (`project-data` layer (a) — EP-0015
        ;;     §6), needed below for the `:serialize` data projection;
        ;;   - the disposition is the OWNER's coarse `:sensitive?` / `:large?`
        ;;     claim PLUS the named-scope-resolver derived-sensitivity inheritance
        ;;     arm (a `{:from-db <id>}` resolver reading a frame-sensitive `:db`
        ;;     input upgrades to `:redact` even when the owner did not declare
        ;;     `:sensitive?` — EP-0016 wave rf2-fi6tda.1; Spec 015 §Derived
        ;;     sensitivity);
        ;;   - `projected-key` is the in-entry `:resource/key` copy projected the
        ;;     SAME way as the wire MAP key (rf2-9e0tyq) — a `:redact` / `:omit`
        ;;     resource redacts its scope + params to opaque content-addressed
        ;;     tokens, so the raw identity never rides in EITHER carrier.
        [projected-key disposition spec] (disposition+project-key scoped-key frame-id)
        stale?      (entry-stale? entry clock-ms)
        ;; metadata-only entries refetch on the client if a live route owner
        ;; needs them; serialized stale entries also refetch (background);
        ;; serialized fresh entries do NOT (no double-fetch).
        metadata-only? (not= :serialize disposition)
        base        {:resource/key   scoped-key
                     :resource-id    resource-id
                     :freshness      (if stale? :stale :fresh)
                     :status         (:status entry)
                     :loaded-at      (:loaded-at entry)
                     :stale-at       (:stale-at entry)
                     :invalidated-at (:invalidated-at entry)}
        wire-entry  (case disposition
                      ;; ship the data, but PROJECT it through the merged
                      ;; frame-owned `project-egress` (EP-0015 §10/§11) under
                      ;; the SSR boundary profile — so a per-slot `:data-schema`
                      ;; mark the frame classification carries is still redacted
                      ;; even on a coarse-`:serialize` resource (defense in
                      ;; depth). `classification/project-data` defers to
                      ;; `project-egress`; frameless egress rides the data
                      ;; UNCHANGED so the coarse owner classification (not
                      ;; frame-presence) governs serialize-vs-redact for a pure /
                      ;; test-harness projection outside a frame scope.
                      :serialize
                      (assoc entry :data
                             (classification/project-data
                               (:data entry) spec frame-id
                               :rf.egress/ssr-hydration))
                      ;; sensitive: replace data with the redaction sentinel
                      ;; (the entry still announces it exists; metadata only).
                      ;; The coarse `:sensitive?` claim is the authority — the
                      ;; sentinel is explicit, not dependent on a frame-resolved
                      ;; schema mark. refresh-error is the same privacy class.
                      :redact
                      (-> entry
                          (assoc :data privacy/redacted-sentinel)
                          (dissoc :refresh-error))
                      ;; large: drop the data key entirely (metadata only).
                      :omit
                      (-> entry
                          (dissoc :data :refresh-error)))
        ;; transient host-pointer facts never ride the wire: the work-id the
        ;; entry currently points at references a host handle that does not
        ;; survive the round-trip (Spec 016 §Restore and replay part 2 — a
        ;; non-terminal attempt is dangling on install).
        wire-entry  (dissoc wire-entry :current-work)
        ;; rf2-9e0tyq — the in-entry `:resource/key` copy is the SAME projected
        ;; key computed above (`disposition+project-key`), matching the wire MAP
        ;; key: a `:redact` / `:omit` resource redacts its scope + params to
        ;; opaque content-addressed tokens here too, so the raw identity never
        ;; rides in EITHER carrier. The client's `recompute-indexes` keys index
        ;; members on the byte `key-id` of this projected `:resource/key`.
        wire-entry  (assoc wire-entry :resource/key projected-key)
        meta'       (assoc base
                           :disposition (case disposition
                                          :serialize :serialized
                                          :redact    :redacted
                                          :omit      :omitted)
                           :refetch-on-client?
                           (boolean (or metadata-only? stale?)))]
    [wire-entry meta']))

(defn projection-metadata
  "Compute the per-entry SSR projection metadata for a frame's resource
  `:entries` against `clock-ms` (Spec 016 §SSR and hydration step 7 — the
  serialized / redacted / omitted / fresh / stale / refetch-on-client
  decisions). Returns a vector of per-entry metadata maps. PURE; the host
  adapter records it in route/SSR diagnostics."
  [frame-id clock-ms entries]
  (mapv (fn [[_k-id entry]] (second (project-entry frame-id clock-ms entry)))
        entries))

(defn project-resources-runtime-db
  "Project the durable resource-runtime slice that rides the
  `:rf/hydration-payload`'s `:rf/runtime-db` slice. Returns a
  `{:rf.runtime/resources {:entries …}}` map (the durable cache facts,
  per-entry REDACTED / OMITTED by the resource's `:sensitive?` / `:large?`
  classification) for a runtime-db carrying resource entries, or `{}`
  otherwise. The reverse indexes (`:tag-index` / `:owner-index`) are
  deliberately EXCLUDED — they are recomputable-from-`:entries` and rebuilt
  on install. Per Spec 016 §SSR and hydration / §Runtime-subsystem
  graduation clause 4.

  This is the body behind the `:ssr/extend-runtime-db-projection` hook.

  Resolves the SSR frame from the in-effect carried-frame scope (the shared
  `rf/elide-wire-value` walker reads it) so the resource's declared
  sensitive / large schema paths govern the per-entry projection. The
  projection NEVER ships all of `:rf.db/runtime` — only the
  `:rf.runtime/resources` `:entries` (Spec 016 §SSR and hydration: \"Do not
  serialize all of `:rf.db/runtime` by default\").

  rf2-otms75 — the projected MAP KEY is also privacy-aligned: a `:sensitive?` /
  `:large?` resource's scope + params are redacted to opaque content-addressed
  tokens in the wire key (`project-scoped-key`), so the raw identity never
  rides any more than its data does (Spec 016 clause 4). A `:serialize`
  resource's key rides verbatim."
  [runtime-db]
  (let [resources (get runtime-db state/resources-key)
        entries   (:entries resources)]
    (if (seq entries)
      (let [frame-id (frame/resolve-current-frame)
            clock-ms (interop/epoch-now-ms)
            ;; rf2-9e0tyq — the projected wire entries are RE-KEYED on the byte
            ;; `key-id` of each entry's PROJECTED `:resource/key` (the wire
            ;; entry's own `:resource/key`, set by `project-entry`), so the
            ;; client installs them under the same byte identity it will then
            ;; `recompute-indexes` over. `entries` here is keyed on the byte
            ;; `key-id`; the scoped-key vector is read from each entry.
            wired    (into {}
                           (map (fn [[_k-id entry]]
                                  (let [wire-entry (first (project-entry frame-id clock-ms entry))]
                                    [(state/key-id (:resource/key wire-entry)) wire-entry])))
                           entries)]
        {state/resources-key {:entries wired}})
      {})))

;; ---- server blocking drain (Spec 016 §SSR and hydration steps 3-4) --------
;;
;; SSR resolves the route, enqueues blocking resource ensures, and DRAINS
;; until the blocking resources for the current nav-token settle. The host
;; adapter / route slice owns the actual drain LOOP (it owns the event
;; pump); this namespace owns the PREDICATE it loops on and the TIMEOUT
;; policy, so the wait point is one pure, testable contract independent of
;; the host's pump.

(defn entry-settled?
  "True iff a resource `entry` has settled to a terminal cache status for
  SSR — `:loaded` (data ready) or `:error` (first-load failed, no data) —
  i.e. it is no longer `:idle` / `:loading` / `:fetching` (work in flight).
  A nil entry (never enqueued) is NOT settled. Per Spec 016 §SSR and
  hydration step 4 / §Lifecycle is an FSM."
  [entry]
  (boolean (and entry (contains? #{:loaded :error} (:status entry)))))

(defn blocking-settled?
  "The SSR blocking-drain PREDICATE (Spec 016 §SSR and hydration step 4):
  given the frame's resource `:entries` and the set of `blocking-keys` the
  route enqueued for the current nav-token, return true iff EVERY blocking
  entry has settled (`entry-settled?`). The host adapter loops the event
  pump until this returns true (or the timeout fires — `settle-blocking-
  timeout`). An empty `blocking-keys` set is trivially settled (a route
  with no blocking resources never blocks the render). PURE — the host
  reads the live frame's entries each tick and re-evaluates.

  `blocking-keys` are scoped resource keys (the VECTOR form the route slice
  stores); nav-token isolation is the CALLER's responsibility — the route
  slice computes the blocking-keys for the current nav-token only, so a
  superseded navigation's keys never enter this predicate.

  rf2-9e0tyq: `entries` is keyed on the CEDN-1 byte `key-id`, so each
  scoped-key vector is translated through `state/key-id` before lookup."
  [entries blocking-keys]
  (every? (fn [k] (entry-settled? (get entries (state/key-id k)))) blocking-keys))

(defn unsettled-blocking-keys
  "Return the subset of `blocking-keys` whose entries have NOT settled
  (still `:idle` / `:loading` / `:fetching`, or absent). The set
  `settle-blocking-timeout` fails closed against when the SSR deadline
  fires. Per Spec 016 §SSR and hydration (blocking timeout policy).
  rf2-9e0tyq: looks each scoped-key vector up by its byte `key-id`."
  [entries blocking-keys]
  (into #{} (remove (fn [k] (entry-settled? (get entries (state/key-id k))))) blocking-keys))

(defn ssr-timeout-error
  "The structured first-load failure envelope a blocking SSR timeout
  settles an unsettled entry to (Spec 016 §SSR and hydration: a timeout
  settles the resource as a structured first-load failure for that SSR
  frame). The `:error` envelope shares the closed `:rf.http/*` failure
  taxonomy (Spec 014) — a wall-clock budget elapsed is `:rf.http/timeout`,
  tagged `:reason :ssr-blocking-timeout` so a renderer can distinguish an
  SSR-deadline failure from a genuine upstream timeout."
  [deadline-ms]
  {:kind       :rf.http/timeout
   :reason     :ssr-blocking-timeout
   :limit-ms   deadline-ms
   :message    (str "SSR blocking resource did not settle within the "
                    deadline-ms "ms render deadline; settled as a first-load "
                    "failure so the request does not hang (Spec 016 §SSR and "
                    "hydration).")})

(defn settle-blocking-timeout
  "Apply the SSR blocking-timeout POLICY (Spec 016 §SSR and hydration: a
  timeout settles the resource as a structured first-load failure for that
  SSR frame, records the route blocking failure, and lets the renderer
  choose error / skeleton / fallback — it MUST NOT hang indefinitely).

  Pure `(entries blocking-keys deadline-ms) -> {:entries :route-blocking-
  failure}`:

    - every UNSETTLED blocking entry (`unsettled-blocking-keys`) is settled
      to a first-load failure (`entry-failed` with the `ssr-timeout-error`
      envelope) so the entry leaves `:loading`/`:idle` and the renderer sees
      a structured `:error`, never a hung `:loading`;
    - `:route-blocking-failure` is the record the route/SSR diagnostics
      surface (`:rf.error/resource-ssr-blocking-timeout`) — which blocking
      keys timed out, the deadline, and the failure envelope.

  Returns the updated `:entries` plus the route-blocking-failure record (or
  nil when nothing timed out). The host adapter installs `:entries` into the
  SSR frame's runtime-db and hands `:route-blocking-failure` to the route
  slice / renderer. This namespace does NOT touch route state directly (the
  route slice owns that surface)."
  [entries blocking-keys deadline-ms frame-id]
  (let [unsettled (unsettled-blocking-keys entries blocking-keys)]
    (if (empty? unsettled)
      {:entries entries :route-blocking-failure nil}
      (let [error    (ssr-timeout-error deadline-ms)
            ;; rf2-9e0tyq — `unsettled` are scoped-key VECTORS; the `:entries`
            ;; map is keyed on the byte `key-id`, so key each settle by
            ;; `key-id` and stamp the entry's own `:resource/key` vector (so a
            ;; freshly-minted timeout entry carries its identity for the
            ;; downstream iterators / projection).
            entries' (reduce
                       (fn [es k]
                         (let [k-id  (state/key-id k)
                               entry (or (get es k-id)
                                         (state/empty-entry (second k) k))]
                           (assoc es k-id (state/entry-failed entry {:error error}))))
                       entries unsettled)]
        (trace/emit-error! :rf.error/resource-ssr-blocking-timeout
                           {:rf.error/id :rf.error/resource-ssr-blocking-timeout
                            :where       're-frame.resources.ssr/settle-blocking-timeout
                            :frame       frame-id
                            :recovery    :settled-as-first-load-failure
                            :timed-out   (vec unsettled)
                            :limit-ms    deadline-ms
                            :reason      (str (count unsettled) " blocking SSR "
                                              "resource(s) did not settle within "
                                              deadline-ms "ms; settled as first-load "
                                              "failures so the render does not hang.")})
        {:entries entries'
         :route-blocking-failure
         {:rf.error/id :rf.error/resource-ssr-blocking-timeout
          :timed-out   (vec unsettled)
          :limit-ms    deadline-ms
          :error       error}}))))

;; ---- routing slice literals (duplicated, not imported) --------------------
;;
;; Two SSR-slice consumers read the routing-runtime subtree:
;;   - the BLOCKING DRAIN reads the current nav-token's blocking scoped-key
;;     set the route slice wrote on entry (`route.cljc` §blocking-path), so it
;;     knows which resources must settle before the render;
;;   - RESTORE reconciliation compares a restored route owner's nav-token
;;     against the nav-token the restored routing slice considers live (Spec
;;     016 §Restore and replay part 4 — `[:rf.runtime/routing :current
;;     :nav-token]`, present because restore installs both partitions
;;     wholesale).
;; We mirror the routing literals here rather than `:require` the route slice —
;; the same duplication-not-import decoupling `route.cljc` itself uses
;; (resources never statically depends on routing).

(def ^:private routing-key
  "The routing-runtime subtree key (`:rf.runtime/routing`). Mirrors the
  literal routing + `route.cljc` use; duplicated (not imported) so the SSR
  slice never statically `:require`s routing/route."
  :rf.runtime/routing)

(def ^:private routing-current-nav-token-path
  "Runtime-db-relative path to the live route slice's nav-token
  (`[:rf.runtime/routing :current :nav-token]`). The drain reads which
  nav-token is live to pick its blocking slot; restore reads it to know which
  nav-token the restored routing slice considers live."
  [routing-key :current :nav-token])

(def ^:private routing-resource-blocking-key
  "The routing-runtime child holding per-nav-token blocking scoped-key sets
  (`:resource-blocking`). The route slice writes `[:rf.runtime/routing
  :resource-blocking <nav-token>]` on entry (`route.cljc/blocking-path`);
  the SSR drain loop reads the CURRENT nav-token's slot."
  :resource-blocking)

(defn current-blocking-keys
  "Read the set of blocking scoped resource keys the route slice enqueued for
  the CURRENT nav-token from `runtime-db` (`[:rf.runtime/routing
  :resource-blocking <current-nav-token>]`). Returns `#{}` when there is no
  routing slice, no current nav-token, or no blocking resources for it — a
  route with no blocking resources never blocks the render. Reads ONLY the
  current nav-token's slot; a superseded navigation's stale slot never enters
  the drain (the route slice releases it on leave). PURE. Per Spec 016 §SSR
  and hydration step 3 / §Route integration."
  [runtime-db]
  (let [nav-token (get-in runtime-db routing-current-nav-token-path)]
    (or (get-in runtime-db [routing-key routing-resource-blocking-key nav-token])
        #{})))

;; ---- the SSR blocking-drain LOOP (Spec 016 §SSR and hydration steps 3-4) ---
;;
;; `blocking-settled?` is the PREDICATE and `settle-blocking-timeout` is the
;; TIMEOUT POLICY; `drain-blocking-resources!` is the LOOP that fuses them and
;; INSTALLS the result, so the host render path (Ring / streaming) calls ONE
;; late-bound entry point instead of re-implementing the wait. The host owns
;; the event PUMP (it owns the drain pump + the wall clock); it supplies a
;; `pump!` thunk the loop calls each tick to let the in-flight blocking-resource
;; replies land, and a `clock-fn` + `deadline-ms` budget. This namespace owns
;; reading the live blocking set, the settled check, and installing the timeout
;; settle into the frame's runtime-db — so a never-settling blocking resource
;; renders a SETTLED first-load failure, never a hung `:loading` / skeleton.

(defn drain-blocking-resources!
  "DRAIN the current nav-token's blocking resources for an SSR `frame-id`
  until they settle or a wall-clock deadline fires (Spec 016 §SSR and
  hydration steps 3-4). The body behind the `:resources/drain-blocking-ssr!`
  late-bind hook the host render path (Ring / streaming) consults BEFORE it
  renders, so the render walk always sees a SETTLED resource state — never an
  unchecked `:loading` / skeleton an in-flight (or never-settling) blocking
  resource would otherwise leave.

  Opts (host-supplied — the host owns the pump + the clock):

    :pump!       — 1-arity fn `(fn [tick-ms] …)` the loop calls each tick to
                   advance the event pump so an in-flight blocking-resource
                   reply lands and its `:rf.resource.internal/succeeded` /
                   `failed` reply event drains (settling the entry). The arg
                   is the `:tick-ms` poll-granularity hint (how long the host
                   may yield this tick); a host that pumps synchronously may
                   ignore it. For a synchronous / already-settled transport
                   this is a no-op the loop rarely needs; for a real async
                   transport it yields (e.g. a short sleep) so the reply
                   thread makes progress. NIL → the loop re-checks without
                   pumping (it still respects the deadline, so a never-settling
                   resource still times out — useful for a sync test stub).
    :deadline-ms — the wall-clock render-deadline budget in ms (the SSR
                   blocking timeout). On elapse every still-unsettled blocking
                   entry is settled to a structured first-load failure
                   (`settle-blocking-timeout`). REQUIRED — an unbounded drain
                   would let a never-settling resource hang the request.
    :clock-fn    — 0-arity epoch-ms clock (defaults to the host platform
                   clock); injectable so a test drives the deadline
                   deterministically.
    :tick-ms     — advisory poll-granularity hint passed as the `pump!` arg
                   each tick (defaults to 0). The loop itself just re-checks
                   after each `pump!`; the hint lets a host yield in bounded
                   slices rather than busy-spinning.

  Returns `{:settled? <bool> :timed-out <#{scoped-key}> :route-blocking-failure
  <record-or-nil>}`. On a clean settle within budget: `:settled? true`,
  `:timed-out #{}`, no failure record, and the frame's runtime-db is
  UNCHANGED. On timeout: the still-unsettled blocking entries are settled to
  first-load failures IN the frame's runtime-db (via `swap-runtime-db!`),
  `:settled? false`, and `:route-blocking-failure` carries the record the host
  hands the route slice / renderer. An empty blocking set (a route with no
  blocking resources) returns `:settled? true` immediately without pumping.

  PURE w.r.t. routing state — it touches only the resource `:entries` (the
  route slice owns the route transition; the host hands it the failure
  record). The drain reads the LIVE frame each tick (`frame-runtime-db-value`)
  so a reply that lands mid-pump is observed."
  [frame-id {:keys [pump! deadline-ms clock-fn tick-ms]}]
  (let [clock-fn (or clock-fn interop/epoch-now-ms)
        rdb0     (frame/frame-runtime-db-value frame-id)
        blocking (current-blocking-keys rdb0)]
    (if (empty? blocking)
      {:settled? true :timed-out #{} :route-blocking-failure nil}
      (let [start    (clock-fn)
            deadline (+ start deadline-ms)]
        (loop []
          (let [rdb     (frame/frame-runtime-db-value frame-id)
                entries (get-in rdb [state/resources-key :entries])]
            (cond
              ;; every blocking entry settled within budget — render sees the
              ;; settled state, runtime-db untouched.
              (blocking-settled? entries blocking)
              {:settled? true :timed-out #{} :route-blocking-failure nil}

              ;; deadline elapsed — settle every still-unsettled blocking entry
              ;; to a first-load failure and INSTALL it (the render then sees a
              ;; structured :error, never a hung :loading).
              (>= (clock-fn) deadline)
              (let [{:keys [entries route-blocking-failure]}
                    (settle-blocking-timeout entries blocking deadline-ms frame-id)]
                (frame/swap-runtime-db! frame-id assoc-in
                                        [state/resources-key :entries] entries)
                {:settled?              false
                 :timed-out             (unsettled-blocking-keys
                                          (get-in rdb [state/resources-key :entries])
                                          blocking)
                 :route-blocking-failure route-blocking-failure})

              ;; not yet settled, budget remains — pump the host event loop so
              ;; an in-flight blocking reply lands, then re-check the live frame.
              :else
              (do (when pump! (pump! (or tick-ms 0)))
                  (recur)))))))))

;; ---- client hydration (Spec 016 §SSR and hydration / §Restore and replay) -
;;
;; On `:rf/hydrate` the SSR handler installs the payload's `:rf/runtime-db`
;; slice wholesale into the runtime-db partition (Spec 011 §The :rf/hydrate
;; event) — including the projected resource `:entries`. The reverse indexes
;; were NOT serialized (recomputable-from-entries), and the installed owners
;; reference the SETTLED server render. `hydrate-runtime-db` reconciles the
;; freshly-installed resource subtree so the client sees a coherent cache:
;;
;;   1. recompute `:tag-index` / `:owner-index` from the installed `:entries`
;;      (never trust the wire — Spec 016 §Restore and replay part 5);
;;   2. reconcile owners by kind — SSR owners (`[:ssr request-id nav-token]`)
;;      do NOT survive as live client leases (they belong to a settled
;;      server render); they are dropped as orphans (Spec 016 §Restore and
;;      replay part 4 / §Release authority is per owner kind);
;;   3. clear each entry's transient `:current-work` pointer (the work it
;;      pointed at never crossed the wire — part 2);
;;   4. surface server CLOCK SKEW when a restored `:stale-at` is implausible
;;      against the live clock (Spec 016 §SSR and hydration: absolute
;;      timestamps; skew surfaced when it makes freshness ambiguous).

(defn- ssr-owner?
  "True iff `owner` is an SSR owner token (`[:ssr request-id nav-token]`).
  SSR owners belong to one server render and never survive as a live
  client-side lease. Per Spec 016 §Release authority is per owner kind."
  [owner]
  (and (vector? owner) (= :ssr (first owner))))

(defn- route-owner?
  "True iff `owner` is a route owner token (`[:route route-id nav-token]`).
  A route owner's nav-token sits at index 2. Per Spec 016 §Active owners and
  causes / §Release authority is per owner kind."
  [owner]
  (and (vector? owner) (= :route (first owner))))

;; ---- route-owner reconcile POLICY (the hydration↔restore split, rf2-64bdnk) -
;;
;; A nil live nav-token means TWO different things, so the route-owner orphan
;; rule MUST distinguish the caller:
;;
;;   - HYDRATION (`:ride-through`): there is no client routing slice yet (the
;;     server projection carries no `:current`), so route owners ride through
;;     UNCHANGED — routing's own client subsystem reconciles their liveness
;;     once it boots. A nil token here is "no comparison possible yet", NOT
;;     "no owner is live".
;;   - RESTORE (`{:restore-live-nav-token <token-or-nil>}`): epoch restore
;;     installs BOTH partitions wholesale, so the restored routing slice's
;;     `:current` nav-token IS present (or genuinely absent/nil). Per Spec 016
;;     §Restore part 4 a route owner revives ONLY IF the restored routing
;;     names the SAME live nav-token; ANY other token — including the case
;;     where the restored routing has NO live nav-token at all (absent routing
;;     slice / no `:current` / nil nav-token) — means NO route owner is live,
;;     so EVERY route owner orphans (a nil live token here is "nothing is
;;     live", the opposite of hydration's "can't compare yet").

(def ^:private hydration-route-owner-policy
  "The route-owner reconcile policy for the SSR-hydration path: route owners
  ride through unchanged (routing's client subsystem owns their liveness; the
  server projection carries no `:current` to compare against). Per Spec 016
  §Restore and replay part 4 (the hydration no-comparison-yet case)."
  :ride-through)

(defn- restore-route-owner-policy
  "The route-owner reconcile policy for the epoch-RESTORE path: a route owner
  revives only if its nav-token EQUALS `live-nav-token` (the restored
  `[:rf.runtime/routing :current :nav-token]`). A nil `live-nav-token` (absent
  routing slice / no `:current` / nil nav-token) means NO route owner is live
  → every route owner orphans (rf2-64bdnk). Per Spec 016 §Restore part 4."
  [live-nav-token]
  {:restore-live-nav-token live-nav-token})

(defn- route-owner-orphan?
  "True iff a route `owner` must be RELEASED as an orphan under
  `route-owner-policy`:

    - `:ride-through` (hydration) → false for every route owner (they ride
      through; routing's client subsystem reconciles them);
    - `{:restore-live-nav-token <t>}` (restore) → true iff the owner's
      nav-token ≠ `<t>`. When `<t>` is nil (the restored routing names no live
      nav-token) EVERY route owner orphans, because no owner's token can equal
      nil (a real nav-token is never nil). Per Spec 016 §Restore part 4
      (rf2-64bdnk)."
  [route-owner-policy owner]
  (and (route-owner? owner)
       (not= :ride-through route-owner-policy)
       (not= (:restore-live-nav-token route-owner-policy)
             (nth owner 2 nil))))

(defn- reconcile-entry-owners
  "Reconcile a restored/hydrated entry's `:active-owners`, returning
  `[entry' dropped-orphans]`. Drops:

    - SSR owners (`[:ssr …]`) — they belong to one settled server render and
      never survive as a live client lease (Spec 016 §Restore and replay
      part 4);
    - ORPHANED route owners (`[:route route-id nav-token]`) per
      `route-owner-policy` (`route-owner-orphan?`): on HYDRATION
      (`:ride-through`) NONE — route owners ride through for routing's own
      client reconcile; on RESTORE (`{:restore-live-nav-token <t>}`) every
      route owner whose nav-token ≠ the restored live token, INCLUDING all of
      them when the restored routing names no live token at all (rf2-64bdnk) —
      such an owner would otherwise pin its entry alive forever + refetch on
      focus/reconnect (part 4).

  Also clears the transient `:current-work` pointer (the attempt it pointed
  at did not cross the wire / no longer exists — part 2). Machine / lease /
  live-nav route owners ride through unchanged (their liveness is reconciled
  by their own subsystem)."
  [entry route-owner-policy]
  (let [owners  (:active-owners entry #{})
        orphan? (fn [o] (or (ssr-owner? o)
                            (route-owner-orphan? route-owner-policy o)))
        dropped (into #{} (filter orphan?) owners)
        kept    (into #{} (remove orphan?) owners)]
    [(-> entry
         (assoc :active-owners kept)
         (assoc :current-work nil))
     dropped]))

(defn clock-skew-ms
  "Compute server→client clock skew evidence for a hydrated `entry` against
  `clock-ms`: the number of ms a restored `:stale-at` lies in the FUTURE
  beyond the live clock by an IMPLAUSIBLE margin (a `:stale-at` far ahead of
  `now` means the server clock ran ahead of the client's, making freshness
  ambiguous). Returns the positive skew ms when implausible, else nil. PURE.
  Per Spec 016 §SSR and hydration (server clock skew surfaced when it makes
  freshness ambiguous)."
  [entry clock-ms]
  (when-let [sa (:stale-at entry)]
    (let [la (:loaded-at entry)
          ;; the entry's own freshness window (loaded-at..stale-at). A
          ;; :stale-at more than one whole window ahead of `now` (relative to
          ;; when it loaded) is implausible against the live clock — the
          ;; server's clock was ahead. nil window (no :stale-after-ms) can't
          ;; be checked.
          window (when (and la sa) (- sa la))]
      (when (and window (pos? window) (> sa (+ clock-ms window)))
        (- sa clock-ms)))))

(defn hydrate-runtime-db
  "Reconcile a freshly-INSTALLED resource subtree on `:rf/hydrate` (the body
  behind the `:resources/hydrate-runtime-db` hook the SSR hydrate handler
  consults). `runtime-db` is the runtime-db partition the SSR handler is
  about to install (the payload's `:rf/runtime-db` slice merged with
  hydration metadata). Returns the runtime-db with the `:rf.runtime/resources`
  subtree reconciled — or `runtime-db` unchanged when it carries no resource
  entries (an SSR app without resource data; a no-op).

  Reconciliation (Spec 016 §SSR and hydration / §Restore and replay):
    1. drop SSR owners from every entry (they orphan — part 4) + clear the
       transient `:current-work` pointer (part 2), AND settle a non-terminal
       `:loading` / `:fetching` entry whose `:current-work` was stripped on the
       wire to its last STABLE status (`settle-entry-to-last-stable` — rf2-bg6qah):
       the server projection strips `:current-work` (`project-entry`) but keeps
       the entry's `:status`, so a hydrated entry can arrive `:loading` / `:fetching`
       with NO live work behind it. Left as-is it would dangle — a `:fetching`
       entry with fresh data is skipped by the refetch planner (no double-fetch)
       yet has no work in flight, so it would render `:fetching` forever. Settling
       resolves it: `:fetching`-with-data → `:loaded` (the planner then keeps it
       fresh / background-refetches if stale), `:loading`-with-no-data → `:idle`
       (the planner then refetches it). This mirrors the restore reconcile (the
       unprojected snapshot needs the same settle — `reconcile-on-restore`);
    2. recompute `:tag-index` / `:owner-index` from the reconciled
       `:entries` (never trust the wire — part 5);
    3. emit a `:rf.resource/hydrated` trace summarising installed / orphaned
       counts, and a clock-skew diagnostic when a restored `:stale-at` is
       implausible against the live clock;
    4. COMPUTE the client refetch plan (`hydrate-refetch-plan`) over the
       reconciled entries (rf2-fopuj9), emitting one `:rf.resource/hydrate-
       refetch` decision row per entry that is NOT sufficient on its own —
       stale (background refetch), omitted (`:no-data`), or redacted (the
       `:rf/redacted` sentinel is metadata-only, NOT usable data, so it is
       `:metadata-only`). Fresh-with-USABLE-data entries are absent from the
       plan (no double-fetch). The plan rides the trace channel here; the
       route slice consults `hydrate-refetch-plan` directly to ISSUE the
       refetch under a live owner (it owns \"does the route still NEED it?\").
       Wiring the COMPUTE into the reconcile means the per-entry decision is
       never lost on the `:rf/hydrate` install path.

  NEVER crosses scopes: it only reconciles the entries the server projected
  under their own scoped keys (the scope is the first element of each key);
  it does not move data between scopes. PURE w.r.t. `runtime-db` (the trace
  emit self-gates on debug)."
  ([runtime-db] (hydrate-runtime-db runtime-db nil))
  ([runtime-db frame-id]
   (let [resources (get runtime-db state/resources-key)
         entries   (:entries resources)]
     (if-not (seq entries)
       runtime-db
       (let [clock-ms (interop/epoch-now-ms)
             ;; reconcile each entry: orphan SSR owners + clear current-work +
             ;; settle a non-terminal entry to last-stable (rf2-bg6qah).
             ;; Hydration passes nil live-nav-token — there is no client
             ;; routing yet, so route owners ride through unchanged (their
             ;; liveness is reconciled by routing on the live client). The
             ;; stale-nav route-owner orphan rule is RESTORE-specific (Spec
             ;; 016 §Restore and replay part 4); see `reconcile-on-restore`.
             ;; The settle, by contrast, is SHARED with restore: the server
             ;; projection stripped `:current-work` on the wire but kept the
             ;; entry's `:status`, so a hydrated `:loading` / `:fetching` entry
             ;; has no live work behind it — settling it to last-stable
             ;; (`:fetching`+data → `:loaded`, `:loading`+no-data → `:idle`)
             ;; lets the refetch planner classify it correctly rather than
             ;; leaving it dangling in a non-terminal status forever.
             reconciled (reduce-kv
                          (fn [acc k entry]
                            (let [[entry' dropped] (reconcile-entry-owners
                                                     entry hydration-route-owner-policy)
                                  entry''          (settle-entry-to-last-stable entry')
                                  skew             (clock-skew-ms entry clock-ms)]
                              (-> acc
                                  (assoc-in [:entries k] entry'')
                                  (update :orphaned into (map (fn [o] [k o])) dropped)
                                  (cond-> skew (update :skews assoc k skew)))))
                          {:entries {} :orphaned [] :skews {}}
                          entries)
             entries'  (:entries reconciled)
             ;; recompute the reverse indexes from the reconciled entries
             subtree'  (state/recompute-indexes
                         (assoc resources :entries entries'))
             rdb'      (assoc runtime-db state/resources-key subtree')]
         (trace/emit! :rf.event :rf.resource/hydrated
                      {:rf.frame/id    frame-id
                       :installed      (count entries')
                       :orphaned-owners (vec (:orphaned reconciled))
                       :clock-skews    (:skews reconciled)})
         (doseq [[k skew] (:skews reconciled)]
           (trace/emit! :warning :rf.resource/hydrate-clock-skew
                        {:rf.frame/id  frame-id
                         :resource/key k
                         :skew-ms      skew
                         :reason       (str "hydrated entry's absolute :stale-at is "
                                            skew "ms ahead of the live client clock "
                                            "— server clock skew makes freshness "
                                            "ambiguous; refetch will resolve it.")}))
         ;; rf2-fopuj9 — COMPUTE the client refetch plan over the reconciled
         ;; entries on the `:rf/hydrate` install path, emitting the per-entry
         ;; `:rf.resource/hydrate-refetch` decision rows (stale / no-data /
         ;; metadata-only). The route slice consults `hydrate-refetch-plan`
         ;; directly to ISSUE the refetch under a live owner; computing it here
         ;; ensures the decision (esp. that a REDACTED sentinel entry is
         ;; metadata-only, not fresh-with-data) is never lost on hydrate.
         (hydrate-refetch-plan rdb' clock-ms frame-id)
         rdb')))))

;; ---- epoch-restore reconcile (Spec 016 §Restore and replay parts 2/4/5) ---
;;
;; Restore (the EP-0001 epoch restore / time travel) shares the SSR-hydration
;; install path, but with ONE load-bearing asymmetry: SSR hydration installs
;; the SERVER PROJECTION (the wire shape `project-entry` already produced — it
;; STRIPPED `:current-work` on the wire and never ships non-terminal work-ledger
;; rows), whereas epoch restore installs the UNPROJECTED captured snapshot —
;; the live frame-state value as of the restored epoch. That snapshot still
;; carries:
;;
;;   - entries left mid-flight in `:loading` / `:fetching` whose `:current-work`
;;     points at an attempt the restored timeline no longer owns (the host
;;     handle was never serialized — Spec 016 §Restore and replay part 2), and
;;   - non-terminal work-ledger rows (`:queued` / `:running` / `:abort-requested`)
;;     for those vanished attempts.
;;
;; So restore needs everything `hydrate-runtime-db` does (recompute indexes —
;; part 5; orphan SSR owners — part 4; clear `:current-work`), PLUS a
;; restore-specific orphan rule and TWO restore-specific settles the hydration
;; projection had already done on the wire:
;;
;;   0. orphan STALE-NAV route owners — part 4. A `[:route route-id nav-token]`
;;      owner whose nav-token ≠ the restored `[:rf.runtime/routing :current
;;      :nav-token]` names a navigation the restored timeline has already left;
;;      it must be RELEASED as an orphan or it pins its entry alive forever +
;;      refetches on focus/reconnect. Restore installs both partitions wholesale
;;      so the live nav-token IS present; SSR hydration has no client routing yet
;;      (the route owners ride through for routing's own client reconcile), so
;;      this rule is RESTORE-only.
;;
;;   1. settle every `:loading` / `:fetching` entry to its last STABLE status
;;      (`:loaded` if it has usable `:data`, `:error` if it was a failed first
;;      load with no data, `:idle` if it never loaded) — never left stranded
;;      pointing at a vanished attempt (part 2);
;;   2. record every restored NON-terminal work-ledger row as DANGLING — settle
;;      it to the terminal `:suppressed` status with a `:dangling` outcome so a
;;      pre-restore in-flight reply that lands post-restore is suppressed by the
;;      ordinary work-id + generation check (the entry no longer points at it and
;;      its row is terminal). The generation allocator is host-side + monotonic
;;      (part 1, `state/generation-cache`), so the dangling `:work/id` can never
;;      re-match a live entry — collision is structurally impossible.
;;
;; Whether a settled entry then re-fetches is a freshness decision (part 3) — it
;; refetches only on the next live-owner `ensure`; restore never eagerly continues
;; old work.

(defn settle-entry-to-last-stable
  "Settle one restored cache `entry` to its last STABLE status (Spec 016
  §Restore and replay part 2). A `:loading` / `:fetching` entry references an
  attempt the restored timeline no longer owns, so it MUST NOT stay stranded
  in an in-flight status pointing at a vanished `:current-work`:

    - `:loaded`  when it has usable `:data`  (a refresh was in flight — keep the
      last-known-good data; a refetch is a later freshness decision);
    - `:error`   when it has a first-load `:error` envelope and no data;
    - `:idle`    when it never loaded (no data, no error).

  A `:loaded` / `:error` / `:idle` entry is ALREADY stable — returned unchanged
  (besides the `:current-work` clear the caller applies). PURE."
  [entry]
  (if (contains? #{:loading :fetching} (:status entry))
    (let [next (cond
                 (state/has-data? entry) :loaded
                 (some? (:error entry))  :error
                 :else                   :idle)]
      (assoc entry :status next))
    entry))

(defn dangle-non-terminal-work!
  "Settle every NON-terminal work-ledger row in `runtime-db` to the terminal
  `:suppressed` status with a `:dangling` outcome (Spec 016 §Restore and replay
  part 2). A restored non-terminal row (`:queued` / `:running` /
  `:abort-requested`) names a request the restored timeline no longer owns —
  its host handle was never serialized. Marking it terminal-suppressed means a
  pre-restore in-flight reply carrying its `:work/id` lands against a row that is
  no longer live and an entry that no longer points at it, so the ordinary
  work-id + generation check suppresses it (`live-entry-for-reply` — no stale
  reply may mutate a post-restore entry).

  Returns `[runtime-db' dangled-work-ids]`. Terminal rows ride through unchanged
  (they already carry an outcome). PURE w.r.t. the host side table — host
  handles are dropped by the resources frame-destroy / restore teardown, not
  here (this only moves the durable serializable row forward)."
  [runtime-db]
  (let [ledger (get runtime-db state/work-ledger-key)
        non-terminal (into []
                           (comp (filter (fn [[_ r]]
                                           (contains? work-ledger/non-terminal-statuses
                                                      (:status r))))
                                 (map key))
                           ledger)]
    (if (empty? non-terminal)
      [runtime-db []]
      ;; rf2-9e0tyq — `non-terminal` are ledger map keys (already byte
      ;; `work-id-id`s from the `(map key)` scan), so update them via
      ;; `update-record-by-id` (NOT `update-record`, which re-transforms a
      ;; work-id VECTOR to its byte id and would double-hash).
      [(reduce (fn [rdb work-id-id]
                 (work-ledger/update-record-by-id rdb work-id-id
                                                   work-ledger/mark-terminal
                                                   :suppressed
                                                   {:reason :dangling
                                                    :recovery :restore-reconcile}))
               runtime-db non-terminal)
       non-terminal])))

(defn dangle-pending-mutations!
  "Reconcile restored PENDING mutation INSTANCES on epoch restore (rf2-o3d1uf,
  Spec 016 §Restore and replay part 2). A restored `:pending` (or `:idle`)
  mutation instance at `:rf.runtime/mutations` retains its `:current-work` +
  `:generation` — pointing at an attempt the restored timeline no longer owns
  (the host handle was never serialized). Unlike a resource cache entry (whose
  reply path checks the ENTRY's `:current-work`), a mutation reply checks the
  INSTANCE's `:current-work` + `:generation` (`live-instance-for-reply`), so
  WITHOUT reconciling the instance a late pre-restore mutation success/failure
  reply would still match the restored instance and PATCH / POPULATE /
  INVALIDATE post-restore resource state — the exact correctness leak this
  closes.

  Each restored pending/idle instance is TERMINALLY SETTLED to `:error` with
  the `:dangling-on-restore` envelope and its `:current-work` is CLEARED
  (`mutation-runtime/instance-dangled`). Clearing `:current-work` makes the
  ordinary work-id + generation gate suppress the late reply (the
  `(= work-id (:current-work inst))` check fails); the generation allocator is
  host-side + monotonic (part 1), so the dangling work-id can never re-match a
  live instance anyway — clearing the pointer is belt-and-braces on top of the
  structural impossibility. Already-terminal (`:success` / `:error`) instances
  ride through unchanged (a settled write's durable outcome is real).

  EP-0019 Q3 GUARD — a dangled OPTIMISTIC write also ROLLS BACK its recorded
  apply (the entry shows the optimistic value with no in-flight write to confirm
  it — an accepted-error-shaped terminal). The rollback runs INSIDE this same
  pure pass (`mutation-runtime/dangle-rollback-optimistic`), NOT as a second
  post-restore dispatched event that could RACE a fresh load: an UNMOVED
  `:revision` restores the recorded `:before` verbatim; a CONFLICT (the entry's
  revision moved) marks the entry durably STALE in place (the read path refetches
  on the next live-owner ensure — no dispatch, no race), unless `:on-conflict
  :force` restores the inverse anyway. The mutation `:on-conflict` policy is read
  off the process-global mutation registry (registration state survives the
  restore). `settled-at` (the restore's causal time) stamps both the dangled
  instance `:settled-at` and the durable stale `:invalidated-at` on a conflict.

  Returns `[runtime-db' dangled-instance-ids rolled-back-keys]`. No-op (returns
  `[runtime-db [] []]`) when the snapshot carries no mutation instances (a
  mutation-free restore). PURE w.r.t. the host side table (the work-ledger host
  handles are cleared separately — the mutation's work-ledger row is dangled by
  `dangle-non-terminal-work!`, work-kind `:mutation`)."
  [runtime-db settled-at]
  (let [instances (get-in runtime-db (mutation-runtime/instances-path))
        ;; rf2-8iciw8 — `:rf.runtime/mutations` is keyed on the CEDN-1 byte
        ;; `key-id`; iterate the map's OWN keys (already byte key-ids) and
        ;; operate on the direct `[mutations-key <key-id>]` path. Do NOT re-feed
        ;; a byte key-id through `instance-path` (that would re-encode it).
        pending   (into []
                        (comp (filter (fn [[_ inst]] (mutation-runtime/pending? inst)))
                              (map key))
                        instances)]
    (if (empty? pending)
      [runtime-db [] []]
      (let [[rdb' rolled-keys dangled-ids]
            (reduce
              (fn [[rdb rk dids] key-id]
                (let [inst-path (conj (mutation-runtime/instances-path) key-id)
                      inst (get-in rdb inst-path)
                      ;; EP-0019 Q3 — roll back the recorded optimistic apply
                      ;; (conflict-aware, INSIDE the pass) BEFORE settling the
                      ;; instance terminal, so the restored cache shows truth (the
                      ;; restored `:before`, or a durable-stale entry the read path
                      ;; refetches), never a dangling optimistic value with no
                      ;; in-flight write to confirm it.
                      spec (mutation-registry/mutation-meta (:mutation/id inst))
                      [rdb2 keys2] (mutation-runtime/dangle-rollback-optimistic
                                     rdb inst spec settled-at)]
                  [(update-in rdb2 inst-path
                              mutation-runtime/instance-dangled settled-at)
                   (into rk keys2)
                   ;; surface the kind-preserving `:instance/id` in the returned
                   ;; dangled-id list (the byte key-id is opaque storage detail).
                   (conj dids (:instance/id inst))]))
              [runtime-db [] []]
              pending)]
        [rdb' dangled-ids rolled-keys]))))

(defn clear-host-transients-on-restore!
  "Clear the restored `frame-id`'s HOST-SIDE transient resource caches that the
  pre-restore timeline armed (rf2-nd1r9q, Spec 016 §Restore and replay part 5).
  Epoch restore installs the durable snapshot wholesale, but host side tables
  are NOT frame-state — they belong to the pre-restore timeline and must be
  cleared so a stale timer / abandoned in-flight handle cannot fire against the
  restored state:

    - **stale / GC timer handles** (`timers/release-frame!`) — cancelled +
      dropped for the frame; stale/GC scheduling re-arms LAZILY from the
      restored entries' durable timestamps the next time the runtime touches
      each entry (the timer is advisory, re-checked against durable facts), so
      restore arms NO eager timer;
    - **work-ledger host handles** (`work-ledger/release-frame!`) — the
      AbortController / transport-promise slots for the frame's in-flight
      attempts; best-effort aborted on the way out (the work is dangling per
      part 2 — its durable row was already settled `:suppressed`).

  DELIBERATELY does NOT touch (Spec 016 part 5 / the bead's explicit fence):

    - the host-side GENERATION high-water mark (`state/generation-cache`) — it
      is monotonic + must NOT rewind (part 1), or a pre-restore reply's
      generation could re-match a post-restore entry; resetting it here would
      reintroduce the exact anti-recycling hazard restore exists to prevent;
    - the focus/reconnect REVALIDATION-LISTENER ownership — those host
      listeners are frame-lifecycle-scoped (install on frame create, remove on
      frame destroy), NOT epoch-scoped; a restore is not a frame teardown, so
      their ownership rides through (re-installing them here would double-bind
      or orphan the live listeners).

  This is the restore-specific SUBSET of the frame-destroy teardown
  (`release-resources-host-caches!`), which clears all four. Side-effecting
  (mutates the module-level `timer-table` / `handle-table`); idempotent;
  returns nil. No-op for a frame with no armed transients."
  [frame-id]
  (timers/release-frame! frame-id)
  (work-ledger/release-frame! frame-id)
  nil)

;; ---- deferred restore trace intents (rf2-obi8rr) --------------------------
;;
;; `reconcile-on-restore` runs INSIDE `perform-restore!` BEFORE the atomic
;; `replace-frame-state!` install (it reconciles the runtime-db the install is
;; about to write). The install can still FAIL after the reconcile: a frame
;; destroyed in the post-liveness teardown window makes `replace-frame-state!`
;; return nil (the rf2-s93722 race), so no frame-state is written. If the
;; reconcile had ALREADY emitted `:rf.resource/restored` / `owner-released`
;; success traces, those would leak — announcing a restore that never installed.
;;
;; So the restore-reconcile trace rows are computed as INTENTS (plain data,
;; `{:level :op :tags}`) the reconcile DEFERS: when invoked via the epoch hook
;; (`:defer-traces? true`) they ride back as metadata on the returned runtime-db
;; under `::deferred-trace-intents`, and `perform-restore!` emits them through
;; `commit-restore-reconcile-traces!` ONLY AFTER a successful install. Invoked
;; directly (the 1-/2-arity, the pure unit-test path) the reconcile still emits
;; inline — there is no install to gate against. The host-side-transient clear is
;; NOT deferred: it is idempotent and the only failure path is an already-
;; destroyed frame whose transients `frame/destroy-frame!` already released.

(def ^:private deferred-trace-intents-key
  "Metadata key carrying the deferred restore-reconcile trace intents on the
  runtime-db `reconcile-on-restore` returns under `:defer-traces? true`
  (rf2-obi8rr). `perform-restore!` reads them via `commit-restore-reconcile-
  traces!` and emits them only after the frame-state install succeeds."
  ::deferred-trace-intents)

(defn- emit-trace-intent!
  "Emit one deferred restore trace intent `{:level :op :tags}` through the
  matching `trace` emitter. `:error` routes through `trace/emit-error!` (the
  structured-error channel); every other level (`:rf.epoch` / `:warning` / …)
  routes through `trace/emit!`. Per rf2-obi8rr."
  [{:keys [level op tags]}]
  (if (= :error level)
    (trace/emit-error! op tags)
    (trace/emit! level op tags))
  nil)

(defn commit-restore-reconcile-traces!
  "Emit the restore-reconcile trace intents DEFERRED on `reconciled-runtime-db`
  by a `reconcile-on-restore` call made with `:defer-traces? true` (rf2-obi8rr).
  The body behind the `:resources/commit-restore-reconcile!` hook
  `perform-restore!` consults AFTER a successful `replace-frame-state!` install,
  so a failed restore (a destroyed-frame install returning nil) emits NO
  `:rf.resource/restored` / `:rf.resource/owner-released` rows. Reads the intents
  from the `::deferred-trace-intents` metadata key and fires each through
  `emit-trace-intent!`. No-op when the value carries no deferred intents (a
  resource-free restore, or a reconcile that emitted inline). Returns nil."
  [reconciled-runtime-db]
  (doseq [intent (-> reconciled-runtime-db meta (get deferred-trace-intents-key))]
    (emit-trace-intent! intent))
  nil)

(defn reconcile-on-restore
  "Reconcile a freshly-INSTALLED resource subtree on `restore-epoch!` (the body
  behind the `:resources/reconcile-on-restore` hook epoch `perform-restore!`
  consults). `runtime-db` is the runtime-db partition the epoch restore is about
  to install (the UNPROJECTED captured snapshot — `:rf.runtime/resources`,
  `:rf.runtime/work-ledger`, AND `:rf.runtime/mutations`). Returns the runtime-db
  with the resource + work-ledger + mutation slices reconciled — or `runtime-db`
  unchanged when it carries no resource entries, no work-ledger rows, AND no
  mutation instances (a no-op for a resource-free restore).

  Unlike `hydrate-runtime-db` (which reconciles the SERVER PROJECTION — already
  `:current-work`-stripped on the wire, no non-terminal rows), restore installs
  the UNPROJECTED snapshot, so reconciliation does everything hydration does PLUS
  the two settles the wire projection had already done (Spec 016 §Restore and
  replay parts 2/4/5):

    1. for every entry — orphan SSR owners AND STALE-NAV route owners (a
       `[:route route-id nav-token]` owner whose nav-token ≠ the restored
       `[:rf.runtime/routing :current :nav-token]` — part 4), clear the
       transient `:current-work` pointer (part 2), AND settle a `:loading` /
       `:fetching` entry to its last STABLE status
       (`settle-entry-to-last-stable`, part 2);
    2. recompute `:tag-index` / `:owner-index` from the reconciled `:entries`
       (never trust the snapshot — part 5);
    3. settle every NON-terminal work-ledger row to terminal `:suppressed` /
       `:dangling` (`dangle-non-terminal-work!`, part 2) so a pre-restore in-flight
       reply is suppressed;
    3b. settle every restored PENDING MUTATION INSTANCE to terminal `:error` /
       `:dangling-on-restore` and CLEAR its `:current-work`
       (`dangle-pending-mutations!`, part 2 — rf2-o3d1uf) so a late pre-restore
       mutation reply cannot patch / populate / invalidate post-restore state
       (the mutation reply gate checks the INSTANCE's `:current-work` +
       `:generation`, not the resource entry's, so the resource-side dangle
       alone does NOT suppress it);
    4. a `:rf.resource/restored` trace summarising reconciled / orphaned /
       dangled (work + mutation) counts, a `:rf.resource/owner-released` row per
       stale-nav route owner released as an orphan (part 4), and a clock-skew
       diagnostic when a restored `:stale-at` is implausible against the live
       clock — emitted INLINE by the 1-/2-arity (the pure unit path; no install
       to gate), or DEFERRED under `:defer-traces? true` (rf2-obi8rr): the
       intents ride back as metadata for `commit-restore-reconcile-traces!` to
       emit AFTER `perform-restore!`'s install succeeds, so a failed
       (destroyed-frame) restore leaks NO `:rf.resource/restored` /
       `owner-released` rows.

  The stale-nav route-owner orphan rule is RESTORE-specific: restore installs
  both partitions wholesale so the live nav-token IS present, whereas SSR
  hydration has no client routing yet — `hydrate-runtime-db` passes a nil live
  nav-token, leaving route owners for routing's own client reconcile.

  `opts` (3-arity): `{:defer-traces? <bool> :restore-time-ms <epoch-ms>}`.

  - `:defer-traces?` (rf2-obi8rr) — when true (the epoch hook path) the trace
    rows are NOT emitted inline; they ride back as `::deferred-trace-intents`
    metadata on the returned runtime-db for `commit-restore-reconcile-traces!`
    to emit post-install. The host-side-transient clear runs regardless
    (idempotent; the only failure path is an already-destroyed frame whose
    transients were already released).
  - `:restore-time-ms` (rf2-wshzsp) — the restore's CAUSAL time: the restored
    epoch's `:committed-at` (the committing token's `:rf.cofx`
    `:rf/time-ms`, replay-stable per EP-0010 §Time). It is the source of the
    DURABLE `:settled-at` stamped on a PENDING mutation instance dangled on
    restore — NOT the live install clock (`interop/epoch-now-ms`). Per EP-0010 §Restore/Replay
    a durable frame-state field MUST come from a causal input, never an ambient
    world read at install. nil on the pure-unit 1-/2-arity (no token / no real
    restore epoch), where it falls back to the live clock — those paths install
    no epoch and the stamp is never replayed.

  NEVER crosses scopes (it only reconciles entries under their own scoped keys).
  Part 1 (the monotone host-side generation allocator) is restore-safe by
  construction — it is NOT frame-state, so restore cannot rewind it; nothing here
  touches it."
  ([runtime-db] (reconcile-on-restore runtime-db nil nil))
  ([runtime-db frame-id] (reconcile-on-restore runtime-db frame-id nil))
  ([runtime-db frame-id {:keys [defer-traces? restore-time-ms]}]
   (let [resources (get runtime-db state/resources-key)
         entries   (:entries resources)
         ledger    (get runtime-db state/work-ledger-key)
         mutations (get-in runtime-db (mutation-runtime/instances-path))
         ;; the nav-token the restored routing slice currently considers live
         ;; — restore installs both partitions wholesale, so routing :current
         ;; is present in this same runtime-db (nil when the app carries no
         ;; routing slice). Route owners whose nav-token ≠ this are stale-nav
         ;; orphans (Spec 016 §Restore and replay part 4).
         live-nav-token (get-in runtime-db routing-current-nav-token-path)]
     (if-not (or (seq entries) (seq ledger) (seq mutations))
       runtime-db
       (let [clock-ms (interop/epoch-now-ms)
             ;; reconcile each entry: orphan SSR owners + STALE-NAV route
             ;; owners (part 4) + clear current-work (part 2) + settle a
             ;; mid-flight status to last-stable (restore-specific — the wire
             ;; projection never carried an in-flight entry, but the
             ;; unprojected snapshot can).
             route-owner-policy (restore-route-owner-policy live-nav-token)
             reconciled (reduce-kv
                          (fn [acc k entry]
                            (let [[entry' dropped] (reconcile-entry-owners entry route-owner-policy)
                                  entry''          (settle-entry-to-last-stable entry')
                                  skew             (clock-skew-ms entry clock-ms)]
                              (-> acc
                                  (assoc-in [:entries k] entry'')
                                  (update :orphaned into (map (fn [o] [k o])) dropped)
                                  (cond-> skew (update :skews assoc k skew)))))
                          {:entries {} :orphaned [] :skews {}}
                          entries)
             entries'  (:entries reconciled)
             subtree'  (state/recompute-indexes
                         (assoc resources :entries entries'))
             rdb'      (cond-> runtime-db
                         (seq entries) (assoc state/resources-key subtree'))
             ;; settle the dangling non-terminal work-ledger rows (restore-specific)
             [rdb'' dangled] (dangle-non-terminal-work! rdb')
             ;; settle the dangling PENDING mutation instances + clear their
             ;; :current-work so a late pre-restore mutation reply is suppressed
             ;; by the instance work-id + generation gate (rf2-o3d1uf). Runs on
             ;; the resource-reconciled runtime-db; the mutation work-ledger row
             ;; was already dangled by `dangle-non-terminal-work!` above.
             ;;
             ;; rf2-wshzsp — the dangled instance's DURABLE `:settled-at` is a
             ;; frame-state field, so per EP-0010 §Time + §Restore/Replay it MUST
             ;; come from the restore's CAUSAL time (`restore-time-ms` — the
             ;; restored epoch's `:committed-at`, itself the committing token's
             ;; `:rf.cofx` `:rf/time-ms`), NOT the ambient `clock-ms`
             ;; (`interop/epoch-now-ms`) read above. Sourcing it from the live install clock
             ;; would make the durable stamp non-replayable (the exact shape the
             ;; EP's restore clause warns against: a durable write fed by an
             ;; ambient read at install). `clock-ms` legitimately feeds ONLY the
             ;; clock-skew DIAGNOSTIC (below) — never a durable entry/instance
             ;; field. Falls back to `clock-ms` only on the pure-unit 1-/2-arity
             ;; (no token in flight, no causal time available — and those paths
             ;; carry no real restore epoch), keeping the unit harness's existing
             ;; behaviour intact.
             settled-at (or restore-time-ms clock-ms)
             ;; EP-0019 Q3 — the dangle ALSO rolls back any recorded optimistic
             ;; apply INSIDE this pass (`rolled-mutation-keys` are the keys the
             ;; rollback restored / durably-staled). A restore-before may have
             ;; re-created / dropped entries + tags, so recompute the reverse
             ;; indexes once more AFTER the dangle when the rollback touched the
             ;; cache (otherwise the earlier `subtree'` recompute is current).
             [rdb-dangled dangled-mutations rolled-mutation-keys]
             (dangle-pending-mutations! rdb'' settled-at)
             rdb''' (if (seq rolled-mutation-keys)
                      (update rdb-dangled state/resources-key state/recompute-indexes)
                      rdb-dangled)
             ;; rf2-obi8rr — compute the restore-reconcile trace rows as INTENTS
             ;; (plain `{:level :op :tags}` data). They are emitted inline below
             ;; for the direct (unit-test) path, or deferred onto the returned
             ;; runtime-db's metadata under `:defer-traces? true` so the epoch
             ;; `perform-restore!` only fires them after a successful install.
             intents (into
                       [{:level :rf.epoch
                         :op    :rf.resource/restored
                         :tags  {:rf.frame/id       frame-id
                                 :reconciled        (count entries')
                                 :orphaned-owners   (vec (:orphaned reconciled))
                                 :dangled-work      (vec dangled)
                                 :dangled-mutations (vec dangled-mutations)
                                 ;; EP-0019 Q3 — the optimistic keys a dangled
                                 ;; optimistic write rolled back INSIDE this pass.
                                 :rolled-back-mutation-keys (vec rolled-mutation-keys)
                                 :clock-skews       (:skews reconciled)}}]
                       cat
                       [;; per-owner row for every STALE-NAV route owner released
                        ;; as an orphan (Spec 016 §Restore and replay part 4:
                        ;; "orphaned owners are dropped with a trace row"). SSR
                        ;; orphans ride the summary above (they belong to a
                        ;; settled server render, not a stale navigation).
                        (for [[k owner] (:orphaned reconciled)
                              :when (route-owner? owner)]
                          {:level :rf.epoch
                           :op    :rf.resource/owner-released
                           :tags  {:rf.frame/id    frame-id
                                   :resource/key   k
                                   :owner          owner
                                   :nav-token      (nth owner 2 nil)
                                   :live-nav-token live-nav-token
                                   :reason         :stale-nav-orphan
                                   :recovery       :restore-reconcile}})
                        (for [[k skew] (:skews reconciled)]
                          {:level :warning
                           :op    :rf.resource/restore-clock-skew
                           :tags  {:rf.frame/id  frame-id
                                   :resource/key k
                                   :skew-ms      skew
                                   :reason       (str "restored entry's absolute :stale-at is "
                                                      skew "ms ahead of the live clock — clock "
                                                      "skew makes freshness ambiguous; the next "
                                                      "live-owner ensure will resolve it.")}})
                        ;; rf2-nftz2s §4 — a PENDING mutation instance was
                        ;; terminally dangled and its DURABLE :settled-at stamped
                        ;; from the LIVE install clock (`clock-ms`) because NO
                        ;; causal `:restore-time-ms` was supplied. Per EP-0010
                        ;; §Time + §Restore/Replay a durable frame-state field
                        ;; MUST come from the restore's causal time (the restored
                        ;; epoch's `:committed-at`), never an ambient read at
                        ;; install — so this is a replay-determinism HAZARD, not
                        ;; a silent fallback. The pure-unit 1-/2-arity paths
                        ;; (no token, no real restore epoch) legitimately have no
                        ;; causal time and install no epoch, so the stamp is never
                        ;; replayed there; but a PRODUCTION restore that dangles
                        ;; real pending mutations without threading
                        ;; `:restore-time-ms` is the seam the bead flags. We
                        ;; surface it LOUDLY (no-silent-swallow) rather than
                        ;; refuse — the 3-arity production caller
                        ;; (`reconcile-runtime-db-on-restore`) always threads the
                        ;; epoch's `:committed-at`, so this fires only on a
                        ;; genuinely-missing causal time.
                        (when (and (seq dangled-mutations) (nil? restore-time-ms))
                          [{:level :warning
                            :op    :rf.resource/restore-settled-at-from-live-clock
                            :tags  {:rf.frame/id        frame-id
                                    :dangled-mutations  (vec dangled-mutations)
                                    :settled-at         settled-at
                                    :reason             (str (count dangled-mutations)
                                                             " pending mutation instance(s) were "
                                                             "dangled on restore with NO causal "
                                                             ":restore-time-ms — their durable "
                                                             ":settled-at was stamped from the live "
                                                             "install clock (" settled-at "), which "
                                                             "is NOT replay-stable (EP-0010 §Time + "
                                                             "§Restore/Replay). Thread the restored "
                                                             "epoch's :committed-at as "
                                                             ":restore-time-ms so the durable stamp "
                                                             "folds the causal token. (Expected nil "
                                                             "only on the pure-unit 1-/2-arity, which "
                                                             "installs no epoch.)")
                                    :recovery           :restore-reconcile}}])])]
         ;; rf2-nd1r9q — clear the restored frame's HOST-SIDE transients (stale /
         ;; GC timer handles + work-ledger host handles) that the pre-restore
         ;; timeline armed (Spec 016 §Restore and replay part 5). These are NOT
         ;; frame-state, so the wholesale install does not touch them; a stale
         ;; timer or abandoned in-flight handle must not fire against the
         ;; restored state. Scheduling re-arms LAZILY from the restored durable
         ;; timestamps on the next live-owner touch, so restore triggers no
         ;; eager refetch. Skips the generation high-water mark (must not rewind
         ;; — part 1) and the revalidation listeners (frame-lifecycle-scoped, not
         ;; epoch-scoped). Guarded on `frame-id` (the host always passes one; the
         ;; pure-unit 1-arity passes nil and has no live host tables to clear).
         ;; NOT deferred (rf2-obi8rr): idempotent, and the only failed-install
         ;; path is an already-destroyed frame whose transients were released.
         (when frame-id
           (clear-host-transients-on-restore! frame-id))
         (if defer-traces?
           ;; rf2-obi8rr — ride the intents back as metadata; the epoch
           ;; `perform-restore!` emits them via `commit-restore-reconcile-traces!`
           ;; only after the frame-state install succeeds.
           (vary-meta rdb''' assoc deferred-trace-intents-key intents)
           ;; the direct (unit) path: no install to gate against — emit inline.
           (do (run! emit-trace-intent! intents)
               rdb''')))))))

;; ---- client refetch decision (Spec 016 §SSR and hydration) ----------------
;;
;; After install, a hydrated entry either renders its data immediately
;; (fresh serialized) or needs a client refetch. The decision (no double-
;; fetch is the load-bearing one):
;;
;;   - FRESH + has USABLE data     -> no refetch (the win SSR exists for);
;;   - STALE + has USABLE data     -> background-refetch by event (renders
;;                                    stale data immediately, refreshes);
;;   - metadata-only (redacted /   -> refetch ONLY if the route still needs
;;     omitted; no usable data)       it (a live owner / the route's plan).
;;
;; The load-bearing correctness boundary (rf2-fopuj9): a REDACTED entry rides
;; the wire with its `:data` REPLACED by the redaction sentinel
;; (`privacy/redacted-sentinel` = `:rf/redacted`) — it is METADATA ONLY, NOT
;; usable data. A naive `(some? (:data entry))` would see the sentinel as
;; present and misclassify a redacted entry as fresh-with-data → NEVER
;; refetch, leaving the client rendering the `:rf/redacted` sentinel as if it
;; were the real value. `hydrated-data-usable?` excludes the sentinel so a
;; redacted entry is correctly treated as metadata-only and refetched.

(defn hydrated-data-usable?
  "True iff a HYDRATED `entry` carries USABLE last-known-good `:data` — i.e.
  `:data` is present AND is NOT the redaction sentinel
  (`privacy/redacted-sentinel`). A `:sensitive?` resource projects its data
  as the sentinel (`project-entry` `:redact`), so on the wire the entry's
  `:data` is `:rf/redacted` — metadata only, never usable. An `:large?`
  resource OMITS the `:data` key entirely (nil — also not usable). This is the
  hydration-side counterpart of `state/has-data?`, which does NOT know about
  the wire sentinel because it only ever sees live durable data. Per Spec 016
  §SSR and hydration (redacted entries hydrate as metadata only)."
  [entry]
  (let [d (:data entry)]
    (and (some? d) (not= privacy/redacted-sentinel d))))

(defn entry-needs-refetch?
  "Decide whether a hydrated `entry` needs a client refetch against
  `clock-ms`. Per Spec 016 §SSR and hydration:

    - a FRESH entry WITH USABLE data does NOT refetch (avoid the duplicate
      immediate fetch — the property SSR exists for);
    - a STALE entry with USABLE data background-refetches by policy;
    - a metadata-only entry (redacted → the `:rf/redacted` sentinel; omitted
      → no `:data`; or settled to `:error` on the server) refetches if the
      route still needs it.

  Returns `false` only for the fresh-with-USABLE-data case; the metadata-only
  / stale / no-data cases return `true` so the route slice can issue the
  refetch under a live owner (it is the route plan that decides whether the
  route still NEEDS the entry; this predicate answers \"is the hydrated value
  sufficient on its own?\"). A REDACTED entry's sentinel `:data` is NOT usable
  (`hydrated-data-usable?`), so it is treated as metadata-only — never as
  fresh-with-data (rf2-fopuj9)."
  [entry clock-ms]
  (let [usable? (hydrated-data-usable? entry)]
    (cond
      (not usable?)                true            ;; metadata-only / redacted / error
      (entry-stale? entry clock-ms) true           ;; stale-while-revalidate
      :else                         false)))       ;; fresh + usable data → no dup-fetch

(defn hydrate-refetch-plan
  "Build the client refetch plan for a hydrated resource subtree: the
  scoped keys whose hydrated entry is NOT sufficient on its own (per
  `entry-needs-refetch?`) and therefore need a client refetch under a live
  owner. Returns a vector of `{:resource/key :resource-id :reason}` entries
  (`:reason` one of `:metadata-only` / `:stale` / `:no-data`). The route
  slice consults this to issue `:rf.resource/refetch` (cause `:hydration`)
  for the entries its route plan still needs — fresh-with-data entries are
  ABSENT from the plan (no double-fetch). Per Spec 016 §SSR and hydration.

  Emits one `:rf.resource/hydrate-refetch` trace per plan entry (the
  per-entry hydration refetch DECISION row — distinct from the ordinary
  `:rf.resource/refetch` traces the route slice's later dispatch emits) so
  the Xray lifecycle timeline / AI-Audit explains why a hydrated entry was
  not sufficient on its own (`:reason` `:no-data` / `:stale` /
  `:metadata-only`). Per Spec 016 §Xray and AI tooling. PURE w.r.t.
  `runtime-db` (the trace emit self-gates on debug, as `hydrate-runtime-db`'s
  `:rf.resource/hydrated` does) — the route slice / host drives the issuing.

  `frame-id` (3-arity) is the explicit hydration target the trace rows are
  tagged with; the 1-/2-arity overloads omit it (a frame-agnostic decision)."
  ([runtime-db] (hydrate-refetch-plan runtime-db (interop/epoch-now-ms) nil))
  ([runtime-db clock-ms] (hydrate-refetch-plan runtime-db clock-ms nil))
  ([runtime-db clock-ms frame-id]
   (let [entries (get-in runtime-db [state/resources-key :entries])
         ;; rf2-9e0tyq — `entries` is keyed on the opaque byte `key-id`; the
         ;; plan names each entry by its stored `:resource/key` VECTOR (the
         ;; route slice re-resolves params from the live route under that key)
         ;; and reads the resource-id from position 1 of THAT vector.
         plan    (into []
                       (comp
                         (filter (fn [[_ entry]] (entry-needs-refetch? entry clock-ms)))
                         (map (fn [[_k-id entry]]
                                {:resource/key (:resource/key entry)
                                 :resource-id  (second (:resource/key entry))
                                 :reason       (cond
                                                 ;; a REDACTED entry rides the
                                                 ;; sentinel as `:data` — metadata
                                                 ;; only, classified distinctly from
                                                 ;; an OMITTED entry (`:no-data`) so
                                                 ;; Xray / the route slice can tell a
                                                 ;; sensitive-redaction refetch from a
                                                 ;; large-omission one (rf2-fopuj9).
                                                 (= privacy/redacted-sentinel (:data entry)) :metadata-only
                                                 (nil? (:data entry))         :no-data
                                                 (entry-stale? entry clock-ms) :stale
                                                 :else                         :metadata-only)})))
                       entries)]
     (doseq [{:keys [resource-id reason] resource-key :resource/key} plan]
       (trace/emit! :rf.event :rf.resource/hydrate-refetch
                    {:rf.frame/id  frame-id
                     :resource/key resource-key
                     :resource-id  resource-id
                     :reason       reason
                     :cause        :hydration}))
     plan)))

;; ---- install / publish ----------------------------------------------------

(defn install-ssr-integration!
  "Publish the LATE-BOUND SSR integration hooks (Spec 016 §SSR and
  hydration):

    - `:ssr/extend-runtime-db-projection` — SSR's `project-runtime-db`
      rides the durable resource `:entries` (per-entry redacted / omitted)
      on the hydration payload;
    - `:resources/drain-blocking-ssr!` — the SSR render path (Ring /
      streaming) drains the current nav-token's blocking resources until they
      settle or the render deadline fires, settling a never-settling blocking
      resource to a first-load failure so the render never hangs (Spec 016
      §SSR and hydration steps 3-4);
    - `:resources/hydrate-runtime-db` — the SSR `:rf/hydrate` handler
      reconciles the installed resource subtree (recompute indexes, orphan
      SSR owners, surface clock skew);
    - `:resources/reconcile-on-restore` — epoch `restore-epoch!`'s
      `perform-restore!` reconciles the UNPROJECTED captured snapshot it is
      about to install (everything the hydrate reconcile does PLUS settling
      mid-flight `:loading` / `:fetching` entries to last-stable and recording
      restored non-terminal work-ledger rows as dangling so a pre-restore reply
      is suppressed — Spec 016 §Restore and replay parts 2/4/5). Called by epoch
      with `:defer-traces? true` so its success rows ride back as metadata
      instead of firing inline (rf2-obi8rr);
    - `:resources/commit-restore-reconcile!` — epoch `perform-restore!` emits
      the restore-reconcile success rows (`:rf.resource/restored` /
      `:rf.resource/owner-released`) the reconcile deferred, fired ONLY AFTER the
      frame-state install succeeds so a destroyed-frame restore that writes
      nothing leaks no success traces (rf2-obi8rr).

  No-op effect on an app that never SSRs / time-travels — the hooks simply sit
  unread. All are no-op on an app WITHOUT resources (the projection contributes
  nothing for an empty entries map; the reconciles are no-ops for a resource-free
  runtime-db). Published from the `re-frame.resources` façade so a `:reload`
  re-wires them."
  []
  (late-bind/set-fns!
    {:ssr/extend-runtime-db-projection project-resources-runtime-db
     :resources/drain-blocking-ssr!    drain-blocking-resources!
     :resources/hydrate-runtime-db     hydrate-runtime-db
     :resources/reconcile-on-restore   reconcile-on-restore
     :resources/commit-restore-reconcile! commit-restore-reconcile-traces!})
  nil)

(defn hydrate-resources!
  "Install the allowed resource projection into the target frame-state's
  `:rf.runtime/resources` slice on client hydration — the direct,
  frame-targeted entry point for a host that drives hydration imperatively
  (the `:rf/hydrate` path uses the `:resources/hydrate-runtime-db` hook
  instead). Reconciles the frame's installed resource subtree
  (`hydrate-runtime-db`: preserve entries, recompute indexes, orphan SSR
  owners, surface clock skew) via the privileged runtime-db mutator, and
  returns the resulting refetch plan (`hydrate-refetch-plan`) so the caller
  can issue background refetches under live owners. Per Spec 016 §SSR and
  hydration (client hydration).

  `frame-id` is the explicit carried hydration target (EP-0002 — no ambient
  fallback). NEVER crosses scopes (the reconcile only touches the entries
  the server projected under their own scoped keys)."
  [frame-id]
  (let [rdb (frame/swap-runtime-db! frame-id hydrate-runtime-db frame-id)]
    (hydrate-refetch-plan (or rdb {}) (interop/epoch-now-ms) frame-id)))
