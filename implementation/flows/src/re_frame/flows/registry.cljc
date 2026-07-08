(ns re-frame.flows.registry
  "Per-frame flow registry — owns the `flows` registry atom and the
  PER-FRAME `last-inputs` dirty-check containers, flow-map validation,
  registration (`reg-flow` / `clear-flow`), and the per-frame dirty-check
  invalidation a same-frame re-registration triggers.

  Per Spec 013 flows are FRAME-SCOPED: same flow-id can register against
  two frames with different `:inputs` / `:derive` / `:output-path`, and
  undo / time-travel semantics belong to one frame's history. The
  registry shape is `{frame-id {flow-id flow-map}}`.

  SINGLE-STORE (rf2-en00bk, applying the rf2-0frdi schemas precedent):
  the per-frame `flows` atom is the SOLE authoritative store. Flows are
  FRAME-DIVERGENT-PER-ID (the same flow-id can carry different
  `:inputs` / `:derive` / `:output-path` per frame), so a frame-BLIND
  `{flow-id metadata}` registrar slot is the wrong shape — it cannot hold
  the authoritative state and had to be hand-REALIGNED to a surviving
  owner on clear / frame-destroy. That double-write + realignment
  workaround is GONE. The `:flow` registrar kind stays RESERVED in
  `registrar/kinds` (Spec 001 §Registry model continuity) but the
  registrar slot is intentionally **empty** — matching the `:app-schema`
  (rf2-0frdi) and `:http-interceptor` precedents. Tools and source-coord
  introspection read the frame-scoped `flow-meta-at` / `flows-snapshot`
  surface, NOT `handler-meta :flow` / `handler-ids :flow`.

  Dirty-check storage is PER-FRAME by construction. Each frame owns its
  own `last-inputs` container (`atom {flow-id inputs}`), held in the
  `frame-last-inputs` registry keyed by frame-id. A frame's drain reads
  and writes ONLY its own atom; the failed-flow rollback snapshots and
  restores ONLY the draining frame's atom. Cross-frame interference during
  a concurrent drain is therefore impossible by construction — a frame can
  no more touch a sibling's dirty-check rows than it can touch a sibling's
  app-db.

  The façade (`re-frame.flows`) re-exports the public surface — `reg-flow`,
  `clear-flow`, `reset-flows!`, `reset-last-inputs!`, plus the read
  accessors `flows-snapshot` / `last-inputs-snapshot`. The underlying atoms
  themselves are PRIVATE to this artefact: external consumers (production
  code, the late-bind directory, test fixtures across artefacts) reach the
  registry state through the accessor seam rather than dereferencing the
  atom Vars directly. `last-inputs-snapshot` re-aggregates the per-frame
  atoms back into the canonical `{flow-id {frame-id inputs}}` observation
  shape."
  (:require [re-frame.elision :as elision]
            [re-frame.error :as error]
            [re-frame.flows.topo :as topo]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.path :as path]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

;; ---- state ---------------------------------------------------------------
;;
;; The atoms below are PRIVATE to this artefact. External consumers reach the
;; registry state through the public read accessors (`flows-snapshot` /
;; `last-inputs-snapshot`) and the reset fns (`reset-flows!` /
;; `reset-last-inputs!`) — the facade re-exports them at `re-frame.flows`. The
;; atoms themselves are NOT a public surface; treating them as one couples
;; consumers to the internal shape. The accessor seam keeps the flexibility on
;; the framework side while giving tests the read-and-reset affordances they
;; actually need.

(defonce
  ^{:doc     "frame-id → flow-id → flow-map. Per-frame so undo / time-travel
              / clear semantics are unambiguous."
    :private true}
  flows
  (atom {}))

;; PER-FRAME dirty-check storage. `frame-last-inputs` is a registry mapping
;; `frame-id → (atom {flow-id last-seen-input-vec})`: each frame owns its OWN
;; inner atom of dirty-check rows, mirroring how every other per-frame runtime
;; cell (app-db container, router queue, sub-cache, drain-lock — see
;; `re-frame.frame/new-frame-record`) is held independently per frame.
;;
;; A frame's drain reads / writes ONLY `(get @frame-last-inputs frame-id)`, and
;; the failed-flow rollback in `re-frame.flows/run-flows-on-db` snapshots /
;; restores ONLY that one atom. A frame can no more clobber a sibling's
;; dirty-check rows than it can clobber a sibling's app-db — cross-frame
;; interference is impossible BY CONSTRUCTION, not merely avoided.
;;
;; The outer `frame-last-inputs` atom is mutated only on frame-slot
;; lifecycle (first touch creates the inner atom; teardown / reset removes
;; it). The hot per-drain path mutates the INNER atom in place — so the
;; per-frame container identity is stable across a frame's lifetime and the
;; reader / writer never contend on the outer registry.
(defonce
  ^{:doc     "frame-id → (atom {flow-id last-seen-input-vec}). Per-frame
              dirty-check containers. Each frame's inner atom is read /
              written only by that frame's drain; the failed-flow rollback
              restores only the draining frame's own atom."
    :private true}
  frame-last-inputs
  (atom {}))

(defn- ^:no-doc ensure-frame-last-inputs-atom!
  "Return the inner `last-inputs` atom for `frame-id`, creating (and
  registering) it on first touch. The create-if-absent is done under a
  single `swap!` over the outer `frame-last-inputs` registry so concurrent
  first-touches on the JVM converge on ONE inner atom (the loser's freshly
  allocated atom is discarded; both threads then read the winner via the
  returned value). Idempotent for an already-present frame — returns the
  existing atom without allocating."
  [frame-id]
  (or (get @frame-last-inputs frame-id)
      (get (swap! frame-last-inputs
                  (fn [m]
                    (if (contains? m frame-id)
                      m
                      (assoc m frame-id (atom {})))))
           frame-id)))

;; ---- public read accessors -----------------------------------------------
;;
;; `flows-snapshot` and `last-inputs-snapshot` are the public seam external
;; consumers (test fixtures, conformance harnesses, the cross-artefact
;; integration tests in http / epoch / routing / ssr / core) use to observe
;; the registry shape without dereferencing the private atoms above. Reads
;; only — mutation goes through `reg-flow` / `clear-flow` /
;; `teardown-on-frame-destroy!` / `reset-flows!` / `reset-last-inputs!`.

(defn flows-snapshot
  "Return the per-frame flow registry value: `{frame-id {flow-id flow-map}}`.
  Snapshot — observers MUST NOT mutate (the underlying atom is private)."
  []
  @flows)

;; ---- per-frame flow introspection (the `handler-meta :flow` replacement) ---
;;
;; SINGLE-STORE (rf2-en00bk): flows are FRAME-DIVERGENT-PER-ID, so the
;; frame-blind registrar `:flow` slot is the wrong shape and is no longer
;; written (the `:flow` registrar kind is RESERVED-but-empty, mirroring
;; `:app-schema` per rf2-0frdi). The canonical introspection surface is
;; `flow-meta-at` — the flows analogue of `schemas/app-schema-meta-at` — which
;; resolves the frame the read targets and returns that frame's flow-map for
;; the id (carrying the registration's `:ns` / `:line` / `:file` source-coords,
;; `:inputs`, `:derive`, `:output-path`, …). Pair-tools, 10x panels and
;; source-coord tests read THIS rather than `(handler-meta :flow flow-id)`.

(defn- coerce-flow-opts
  "Permit the keyword-only sugar `(flow-meta-at id frame-id)` and the opts-map
  form `(flow-meta-at id {:frame frame-id})`. A `nil` arg coerces to `{}` (no
  override — resolve the frame from the carried-invariant scope). A keyword or
  a frame VALUE coerces to `{:frame target}`. Mirrors
  `schemas.storage/coerce-opts`; the frame-value branch precedes the generic
  `map?` branch because a frame value IS a map (rf2-7pllal)."
  [opts-or-frame-id]
  (cond
    (nil? opts-or-frame-id) {}
    (or (keyword? opts-or-frame-id)
        (frame/frame-value? opts-or-frame-id)) {:frame opts-or-frame-id}
    (map? opts-or-frame-id) opts-or-frame-id
    :else {:frame opts-or-frame-id}))

(defn- resolve-read-frame
  "Resolve the frame a flow READ targets. EP-0002 — the explicit `:frame` opt
  (the *override*) wins, else the carried-invariant scope chain via
  `frame/require-current-frame!`. Called under no scope and no explicit
  `:frame`, it raises `:rf.error/no-frame-context` rather than defaulting to
  `:rf/default`. The override is a frame TARGET (keyword id or frame value),
  normalized to its frame-id via `frame/frame-target->id`. Per rf2-8mxnnk the
  `reg-flow` / `clear-flow` WRITE paths normalize their explicit `:frame`
  override the same way, so a frame VALUE keys / reads the same flow uniformly
  across register, clear, and `flow-meta-at`."
  [opts]
  (let [override (:frame opts)]
    (if (some? override)
      (frame/frame-target->id override)
      (frame/require-current-frame!
        :flow-meta-at {:where 'rf/flow-meta-at}))))

(defn flow-meta-at
  "Return the registration metadata map for `flow-id` in a frame, or nil.

  The canonical per-frame introspection surface for flows — the
  `(handler-meta :flow flow-id)` replacement (rf2-en00bk, applying the
  rf2-0frdi `schemas/app-schema-meta-at` precedent). Returns the full flow-map
  stamped at `reg-flow` — including source-coords (`:ns` / `:line` / `:file`),
  `:inputs`, `:derive`, `:output-path`, and any output-classification keys —
  for the `(frame-id, flow-id)` entry in the authoritative per-frame `flows`
  atom. Frame-divergent by construction: the SAME flow-id registered against
  two frames returns each frame's OWN definition, where the frame-blind
  registrar slot could only ever show the most-recent registrant (the very
  reason the realignment workaround existed).

  Arities:
    (flow-meta-at flow-id)         ;; frame from the carried-invariant scope
    (flow-meta-at flow-id opts)    ;; opts map; :frame names the frame target
                                   ;; (keyword id or frame value; keyword sugar
                                   ;; `(flow-meta-at flow-id frame-id)` accepted)"
  ([flow-id] (flow-meta-at flow-id {}))
  ([flow-id opts-or-frame-id]
   (let [opts     (coerce-flow-opts opts-or-frame-id)
         frame-id (resolve-read-frame opts)]
     (get-in @flows [frame-id flow-id]))))

(defn ^:no-doc last-inputs-snapshot
  "Return the dirty-check value re-aggregated to the canonical observation
  shape `{flow-id {frame-id inputs}}`. Reads every per-frame container and
  inverts the `{frame-id {flow-id inputs}}` layout back to `flow-id`-outer.
  Empty per-frame containers contribute nothing, so a frame whose dirty-check
  rows all cleared leaves no key behind. Snapshot — observers MUST NOT mutate
  (the underlying atoms are private).

  RAW — NOT an egress boundary (Spec 015). The cached input vectors are the
  OWNER-LOCAL app-db / runtime-db values just read for the dirty check, so
  they can be sensitive or large. This accessor returns them verbatim and is
  for INTERNAL / TEST / ROLLBACK use only (the failed-flow rollback snapshot,
  conformance harnesses, dirty-check assertions). Any tool or direct read
  that crosses a trust boundary MUST go through the elided trace path
  (`:rf.flow/computed` `:input-values` / `:rf.flow/failed` `:inputs`, which
  ride `re-frame.flows/elide-inputs`), NOT this raw accessor — the cached
  dirty-check value is never shipped off-box."
  []
  (reduce-kv
    (fn [acc frame-id inner-atom]
      (reduce-kv
        (fn [acc flow-id inputs]
          (assoc-in acc [flow-id frame-id] inputs))
        acc
        @inner-atom))
    {}
    @frame-last-inputs))

;; ---- intra-artefact-only mutation helpers --------------------------------
;;
;; `re-frame.flows` (the facade evaluation path) reads / advances the
;; draining frame's `last-inputs` on every successful flow recompute and
;; snapshots / restores it across the drain. These helpers keep the mutation
;; contained in this namespace — the atom never escapes — and are frame-
;; scoped: every read / write / rollback names the frame it operates on, so
;; no path can touch a sibling frame's container. NOT a public surface;
;; ns-doc names the consumer.

(defn ^:no-doc frame-last-inputs-snapshot
  "Return the draining frame's dirty-check rows as a plain `{flow-id inputs}`
  map (the drain-start snapshot for the failed-flow rollback). Empty map
  when the frame has no container yet."
  [frame-id]
  (if-let [a (get @frame-last-inputs frame-id)]
    @a
    {}))

(defn ^:no-doc get-frame-flow-last-inputs
  "Read the last-seen input vec for `[frame-id flow-id]` — the dirty-check
  skip-path read. `nil` when the frame has no container or the flow has no
  row yet."
  [frame-id flow-id]
  (when-let [a (get @frame-last-inputs frame-id)]
    (get @a flow-id)))

(defn ^:no-doc set-frame-flow-last-inputs!
  "Advance the dirty-check row for `[frame-id flow-id]` to `inputs` after a
  successful recompute. Mutates only the frame's own inner atom (creating it
  on first touch)."
  [frame-id flow-id inputs]
  (swap! (ensure-frame-last-inputs-atom! frame-id) assoc flow-id inputs))

(defn ^:no-doc reset-frame-last-inputs-to!
  "Restore the draining frame's `last-inputs` container to `prior` (its
  drain-start snapshot, a plain `{flow-id inputs}` map). Called by
  `re-frame.flows/run-flows-on-db`'s catch arm to roll back the draining
  frame's — and ONLY the draining frame's — dirty-check bookkeeping when a
  flow throws mid-cascade. A concurrently-draining sibling's container is a
  different atom and is untouched."
  [frame-id prior]
  (reset! (ensure-frame-last-inputs-atom! frame-id) prior))

;; ---- pending abandoned output paths --------------------------------------
;;
;; A same-frame `reg-flow` REPLACEMENT that MOVES the output to a new
;; `:output-path` must vacate the OLD path from the frame's app-db (Spec 013
;; §Re-registration — otherwise the previous definition's last write lingers
;; at the abandoned slot as stale derived state). `vacate-output-path!`
;; (below) does that with a DIRECT app-db write, which is correct for an
;; OUT-of-drain call.
;;
;; When `reg-flow` runs IN-drain — reentrantly from inside an event handler or
;; a `:rf.fx/reg-flow` effect — a direct write would be CLOBBERED: the drain
;; runs flows over the PENDING `:db` (the handler's returned value, which still
;; carries the old path) and the router's single deferred commit PUBLISHES that
;; pending value AFTER the reentrant `reg-flow` returns, overwriting the direct
;; vacate and RESURRECTING the old output. So in-drain, record the abandoned
;; path here instead of writing app-db directly.
;; `re-frame.flows/run-flows-on-db` drains this set at the START of the
;; pending-`:db` transform (before the flow walk) and dissocs each path from
;; the PENDING db — so the vacate participates in the SAME value the deferred
;; commit publishes and the handler's returned `:db` cannot resurrect the old
;; path. Per-frame: each frame owns its own inner atom; a sibling frame
;; draining concurrently on another thread holds a different atom and is
;; structurally untouched.

(defonce
  ^{:doc     "frame-id → (atom #{abandoned-output-path ...}). Per-frame set
              of OLD output paths recorded by an IN-DRAIN same-frame
              `reg-flow` `:output-path` move, pending vacate by the current
              drain's `run-flows-on-db` pending-`:db` transform."
    :private true}
  frame-abandoned-output-paths
  (atom {}))

(defn- ensure-frame-abandoned-paths-atom!
  "Return `frame-id`'s inner abandoned-output-paths atom, creating (and
  registering) it on first touch under a single `swap!` so concurrent
  first-touches converge on ONE atom (mirrors
  `ensure-frame-last-inputs-atom!`)."
  [frame-id]
  (or (get @frame-abandoned-output-paths frame-id)
      (get (swap! frame-abandoned-output-paths
                  (fn [m]
                    (if (contains? m frame-id)
                      m
                      (assoc m frame-id (atom #{})))))
           frame-id)))

(defn ^:no-doc record-abandoned-output-path!
  "Record `path` as a pending abandoned output path for `frame-id` — to be
  vacated from the pending `:db` by the current drain's `run-flows-on-db`.
  Called by `reg-flow`'s in-drain `:output-path`-move branch instead of the
  direct app-db write the OUT-of-drain branch uses. Frame-local."
  [frame-id path]
  (swap! (ensure-frame-abandoned-paths-atom! frame-id) conj path))

(defn ^:no-doc abandoned-output-paths-snapshot
  "Return `frame-id`'s pending abandoned output paths as a plain set (the
  drain-start snapshot for the failed-flow / post-commit rollback). Empty set
  when the frame has no container yet. Frame-local."
  [frame-id]
  (if-let [a (get @frame-abandoned-output-paths frame-id)]
    @a
    #{}))

(defn ^:no-doc drain-abandoned-output-paths!
  "Atomically read-and-clear `frame-id`'s pending abandoned output paths,
  returning the set that was pending. The current drain's `run-flows-on-db`
  consumes these once — dissocing each from the pending
  `:db` BEFORE the flow walk — and a throw / post-commit rollback re-records
  them via `restore-abandoned-output-paths!` so the move re-attempts cleanly
  next drain. Frame-local: returns an empty set when the frame has no
  container."
  [frame-id]
  (when-let [a (get @frame-abandoned-output-paths frame-id)]
    (let [drained (volatile! #{})]
      (swap! a (fn [s] (vreset! drained s) #{}))
      @drained)))

(defn ^:no-doc restore-abandoned-output-paths!
  "Restore `frame-id`'s pending abandoned output paths to `prior` (its
  drain-start snapshot). Called when a flow throws mid-cascade
  (`run-flows-on-db`'s catch arm) or a POST-commit schema / machine-data
  validation rolls the event back — the pending `:db` (carrying the vacated
  state) was discarded, so the abandoned paths must re-attempt next drain.
  Frame-local: a concurrently-draining sibling's container is a
  different atom and is untouched."
  [frame-id prior]
  (reset! (ensure-frame-abandoned-paths-atom! frame-id) (set prior)))

;; ---- last-inputs row maintenance -----------------------------------------
;;
;; With per-frame `last-inputs` containers, dropping one flow's dirty-check
;; row for a frame is a plain `dissoc` on that frame's own inner atom — no
;; two-level-map walk, and structurally incapable of touching a sibling
;; frame's container. The clear-flow / frame-destroy / hot-reload-invalidate
;; paths share this one helper so the "drop this flow's row for this frame"
;; invariant lives in exactly one place. Per-frame storage makes the dissoc
;; unconditional and frame-local.

(defn- drop-frame-flow-row!
  "Drop `flow-id`'s dirty-check row from `frame-id`'s `last-inputs`
  container. No-op when the frame has no container. Frame-local by
  construction."
  [frame-id flow-id]
  (when-let [a (get @frame-last-inputs frame-id)]
    (swap! a dissoc flow-id)))

;; ---- validation ----------------------------------------------------------
;;
;; Per Spec 013 §Flow shape: `:inputs` is a vector of app-db paths,
;; `:output-path` is an app-db path. A path is a non-empty vector of scalar
;; map keys. The validator enforces this fully up front rather than letting a
;; malformed shape boom later in the topo / evaluator stack. Three
;; representative malformations it catches:
;;
;;   - `:inputs [:foo :bar]` (vector of bare keywords) — topo's `prefix?`
;;     would otherwise throw on `(count :foo)`.
;;   - `:inputs [[:foo] :bar]` (mixed) — same problem one entry along.
;;   - `:output-path []` — `(prefix? [] anything)` is true, so an empty-path
;;     flow would silently become a depends-on prerequisite of EVERY other
;;     flow in the frame (per Spec 013 §Dependency rule).
;;
;; Each malformation is rejected with a stable error id and ex-data that names
;; the offending entries / elements so callers don't have to chase the failure
;; into the topo / evaluator stack.
;;
;; The OPTIONAL output data-classification keys (`:sensitive` / `:large` /
;; `:large?`, EP-0025) are validated in the same table. They are validated
;; FAIL-CLOSED: a malformed declaration (`:sensitive [:token]`, `:large "blob"`)
;; is rejected rather than silently installing no redaction — the worst failure
;; for a safety feature. EP-0025 removed propagation: the boolean `:sensitive?`
;; spelling and the `:rf.egress/output-sensitivity` enum are both REJECTED (a
;; flow classifies its OWN output directly — no input inheritance).

(defn- valid-path-element?
  "True iff `x` is admissible as a flow path segment — the SHARED
  concrete-segment domain (`re-frame.path/segment?`, Conventions §Segment
  domain). The shared domain is a keyword / string / symbol / boolean /
  integer / UUID / instant / nil; composites (vectors / maps / sets / seqs)
  and host handles are rejected. Collections are never the right value for a
  `get-in` path step and almost always indicate a caller bug (e.g. passing a
  bare keyword where a vector-of-paths was expected, then wrapping it one
  level too many).

  Flows DELEGATE the membership question to the shared `:rf/path` policy
  rather than re-deriving it: a subsystem narrows from the shared upper bound
  but never re-enumerates it. The shared domain admits UUID, instant, and nil
  segments alongside keyword / string / integer / symbol / boolean — all valid
  associative keys the algebra already focuses through. The flows-SPECIFIC
  restriction (a `:output-path` / `:inputs` path must be NON-EMPTY — an empty
  path is a root-output footgun that makes the flow a depends-on prerequisite
  of every other flow, Spec 013 §Dependency rule) is layered ON TOP in
  `valid-path?` below, NOT folded into the shared segment domain."
  [x]
  (path/segment? x))

(defn- valid-path?
  "A flow `:inputs` path or the flow `:output-path` is a NON-EMPTY vector of
  valid path segments. The non-emptiness is the flows-specific root-output
  policy (Spec 013 §Dependency rule — an empty path overlaps every path); the
  per-element domain is the shared segment policy (`valid-path-element?`
  → `re-frame.path/segment?`)."
  [x]
  (and (vector? x) (seq x) (every? valid-path-element? x)))

(defn- valid-output-subpath?
  "A flow output classification subpath (an entry of `:sensitive` / `:large`)
  is a vector of valid path segments, AND — unlike a flow `:inputs` path or
  the flow `:output-path` — the EMPTY vector `[]` is legal: it marks the whole
  output value (the `[[]]` whole-value convention, Spec 015:81). So this is
  `valid-path?` minus the non-empty requirement: a vector whose every element
  (if any) is a shared-domain segment (`valid-path-element?`)."
  [x]
  (and (vector? x) (every? valid-path-element? x)))

;; Every validation throw shares the canonical thrown-error skeleton
;; (per Spec 009 §The thrown-error shape):
;;
;;   {:rf.error/id <category-kw>    ;; CANONICAL DISCRIMINATOR — :rf.error/<category>
;;    :where       'rf/reg-flow     ;; user-facing fn for greping the call site
;;    :recovery    :fix-registration ;; "the caller fixes their flow map and retries"
;;    :reason      "<diagnostic>"
;;    :flow        <the supplied flow>}
;;
;; The `:rf.error/id` discriminator slot is read uniformly by every
;; consumer (Xray's error widget, the pair-tool overlay, `:on-error`
;; policies); the message string is the stringified kw so `.getMessage`
;; pivots to the same category without ex-data. The `:where` / `:recovery`
;; slots mirror the missing-artefact throw shape standardised by
;; `re-frame.late-bind/require-fn!` and used by every
;; `re-frame.core-<artefact>` wrapper. Per-clause extras (`:bad-entries`
;; / `:bad-elements`) merge on top.

(defn- flow-error
  "Build the validate-flow ex-info via the central thrown-error builder
  `re-frame.error/thrown-ex-info` (per Spec 009 §The thrown-error shape).
  `error-kw` is the `:rf.error/id` discriminator slot; `reason` is the
  human-readable diagnostic that LEADS the message (the message also
  trails the `[:rf.error/<id>]` greppability token); `extras` merges
  per-clause slots (e.g. `:bad-entries`)."
  ([error-kw reason flow] (flow-error error-kw reason flow nil))
  ([error-kw reason flow extras]
   (error/thrown-ex-info
     error-kw 'rf/reg-flow reason
     {:recovery :fix-registration
      :extra    (merge {:flow flow} extras)})))

;; The validation rules, in evaluation order. Each rule has a predicate
;; over the flow map (returns truthy to accept), a stable `:error-kw`
;; discriminator, a `:reason` diagnostic, and optional `:extras` that
;; build per-clause ex-data slots (`:bad-entries` / `:bad-elements`).
;; `validate-flow` walks this vector and throws on the first failing
;; predicate, so the rules are ordered most-fundamental-first (a flow with a
;; broken required shape reports that before any classification-key clause).
;;
;; Data-driven so the rules are introspectable (a test or the spec can
;; read the table) and adding a clause is a single conj.
(def ^:private validation-rules
  [{:pred     (fn [flow] (some? (:id flow)))
    :error-kw :rf.error/flow-missing-id
    :reason   ":id is required (flow registration must name an id)"}

   ;; A PRESENT `:id` must be a keyword. Spec-Schemas §FlowMeta requires
   ;; `[:id :keyword]` and Spec 013 §The registration shape describes flow ids
   ;; as namespaced feature identifiers; the public examples are all keywords,
   ;; and the `:flow-id` slot is emitted unchanged into `:rf.flow/*` trace +
   ;; error payloads, so a string / number / map id violates the public schema
   ;; contract and would leak an arbitrary id shape downstream. Rejecting at
   ;; the API boundary — resolving here, not by normalising in every consumer —
   ;; is the masterpiece choice. Distinct from the absent-id case above
   ;; (`flow-missing-id` fires first on nil); this rule fires only when an id
   ;; is present but the wrong type — a member of the `:rf.error/flow-bad-*`
   ;; family alongside bad-inputs / bad-output / bad-path.
   {:pred     (fn [flow] (keyword? (:id flow)))
    :error-kw :rf.error/flow-bad-id
    :reason   ":id must be a keyword (flow ids are namespaced feature identifiers; the public FlowMeta schema requires :keyword and the :flow-id trace/error slot carries it unchanged)"}

   {:pred     (fn [flow] (vector? (:inputs flow)))
    :error-kw :rf.error/flow-bad-inputs
    :reason   ":inputs must be a vector of paths"}

   ;; One clause for both "entry isn't a vector" and "entry isn't a valid
   ;; path" — `valid-path?` already requires `vector?`, so this single check
   ;; subsumes both. The rejection message names what the entry must be; the
   ;; `:bad-entries` slot points at the offending values so callers can fix
   ;; them without a stack-trace dig.
   {:pred     (fn [flow] (every? valid-path? (:inputs flow)))
    :error-kw :rf.error/flow-bad-inputs
    :reason   ":inputs entries must each be a non-empty vector of path segments (the shared EP-0012 segment domain: keyword / string / symbol / boolean / integer / UUID / instant / nil)"
    :extras   (fn [flow] {:bad-entries (vec (remove valid-path? (:inputs flow)))})}

   {:pred     (fn [flow] (fn? (:derive flow)))
    :error-kw :rf.error/flow-bad-output
    :reason   ":derive must be a fn"}

   {:pred     (fn [flow] (vector? (:output-path flow)))
    :error-kw :rf.error/flow-bad-path
    :reason   ":output-path must be a vector"}

   {:pred     (fn [flow] (seq (:output-path flow)))
    :error-kw :rf.error/flow-bad-path
    :reason   ":output-path must be non-empty (an empty :output-path would make this flow a depends-on prerequisite of every other flow per Spec 013 §Dependency rule)"}

   {:pred     (fn [flow] (every? valid-path-element? (:output-path flow)))
    :error-kw :rf.error/flow-bad-path
    :reason   ":output-path elements must each be a path segment (the shared EP-0012 segment domain: keyword / string / symbol / boolean / integer / UUID / instant / nil)"
    :extras   (fn [flow] {:bad-elements (vec (remove valid-path-element? (:output-path flow)))})}

   ;; The OPTIONAL output data-classification keys (`:sensitive` / `:large`
   ;; per-path vectors; `:large?` whole-output boolean) are validated FAIL-FAST.
   ;; A typo (`:sensitive [:token]`, `:large "blob"`, `:large? 1`) is rejected
   ;; at the API boundary rather than silently installing no redaction /
   ;; large-elision — the worst failure mode for a SAFETY feature is the author
   ;; believing a slot is protected when it is not. Same fail-FAST posture as
   ;; the core flow-path validation, and the same `:rf.error/flow-bad-*`
   ;; family. These rules fire AFTER the core `:id` / `:inputs` / `:derive` /
   ;; `:output-path` rules (a flow with a broken required shape reports that
   ;; first), but BEFORE any registry / app-db / elision-declaration state
   ;; mutates (validate-flow is the first call in `reg-flow`, before `frame-id`
   ;; / the `swap!`), so a rejected registration installs NO flow row and NO
   ;; elision declaration.
   ;;
   ;; Vector-of-subpaths rules: `:sensitive` / `:large`, when PRESENT, must be
   ;; a vector whose every entry is a valid output subpath — a vector of
   ;; scalar keys, with `[]` legal (the `[[]]` whole-output convention,
   ;; Spec 015:81). `valid-output-subpath?` is `valid-path?` minus the
   ;; non-empty requirement. The `vector?`-of-the-whole and
   ;; every-entry-well-formed checks are split so the diagnostic distinguishes
   ;; "you passed a non-vector" from "one of your entries is malformed".
   {:pred     (fn [flow] (or (not (contains? flow :sensitive))
                             (vector? (:sensitive flow))))
    :error-kw :rf.error/flow-bad-marks
    :reason   ":sensitive, when present, must be a vector of output subpaths (each a vector of scalar keys; [] marks the whole output)"
    :extras   (fn [flow] {:bad-key :sensitive :bad-value (:sensitive flow)})}

   {:pred     (fn [flow] (or (not (contains? flow :sensitive))
                             (every? valid-output-subpath? (:sensitive flow))))
    :error-kw :rf.error/flow-bad-marks
    :reason   ":sensitive entries must each be a vector of path segments (the shared EP-0012 segment domain: keyword / string / symbol / boolean / integer / UUID / instant / nil); [] marks the whole output"
    :extras   (fn [flow] {:bad-key     :sensitive
                          :bad-entries (vec (remove valid-output-subpath? (:sensitive flow)))})}

   {:pred     (fn [flow] (or (not (contains? flow :large))
                             (vector? (:large flow))))
    :error-kw :rf.error/flow-bad-marks
    :reason   ":large, when present, must be a vector of output subpaths (each a vector of scalar keys; [] marks the whole output)"
    :extras   (fn [flow] {:bad-key :large :bad-value (:large flow)})}

   {:pred     (fn [flow] (or (not (contains? flow :large))
                             (every? valid-output-subpath? (:large flow))))
    :error-kw :rf.error/flow-bad-marks
    :reason   ":large entries must each be a vector of path segments (the shared EP-0012 segment domain: keyword / string / symbol / boolean / integer / UUID / instant / nil); [] marks the whole output"
    :extras   (fn [flow] {:bad-key     :large
                          :bad-entries (vec (remove valid-output-subpath? (:large flow)))})}

   ;; EP-0025 — flow output sensitivity PROPAGATION is removed: a flow no
   ;; longer inherits its inputs' classification, and there is no
   ;; `:rf.egress/output-sensitivity` enum (`:inherit` / `:sensitive` /
   ;; `:public`). A flow classifies its OWN output directly with per-path
   ;; `:sensitive` / `:large` (`[[]]` = whole output) or the `:large?`
   ;; whole-output size override. The boolean `:sensitive?` spelling stays
   ;; rejected (use `:sensitive [[]]` for a whole-output mark).
   {:pred     (fn [flow] (not (contains? flow :sensitive?)))
    :error-kw :rf.error/flow-bad-marks
    :reason   (str "the boolean :sensitive? spelling is rejected on a flow output "
                   "(EP-0025) — classify the whole output with :sensitive [[]] (the "
                   "[[]] whole-value convention) or a per-path :sensitive [paths]")
    :extras   (fn [flow] {:bad-key :sensitive?
                          :bad-value (:sensitive? flow)
                          :use :sensitive})}

   {:pred     (fn [flow] (not (contains? flow :rf.egress/output-sensitivity)))
    :error-kw :rf.error/flow-bad-marks
    :reason   (str ":rf.egress/output-sensitivity is removed (EP-0025 — no flow "
                   "input→output propagation). Classify the flow's own output "
                   "directly with :sensitive [paths] / :large [paths] / :large?")
    :extras   (fn [flow] {:bad-key   :rf.egress/output-sensitivity
                          :bad-value (:rf.egress/output-sensitivity flow)})}

   {:pred     (fn [flow] (or (not (contains? flow :large?))
                             (boolean? (:large? flow))))
    :error-kw :rf.error/flow-bad-marks
    :reason   ":large?, when present, must be a boolean (true forces the whole output large)"
    :extras   (fn [flow] {:bad-key :large? :bad-value (:large? flow)})}])

(defn- validate-flow [flow]
  (some (fn [{:keys [pred error-kw reason extras]}]
          (when-not (pred flow)
            (throw (flow-error error-kw reason flow (when extras (extras flow))))))
        validation-rules))

;; ---- flow-output data classification (EP-0025) --------------------------
;;
;; A `reg-flow` registration may carry data-classification keys describing the
;; SENSITIVITY / SIZE of the flow's OWN OUTPUT value:
;;
;;   :large?     true   — the whole output is large
;;   :sensitive  [paths]— per-output-path sensitive sub-slots ([[]] = whole)
;;   :large      [paths]— per-output-path large sub-slots ([[]] = whole)
;;
;; EP-0025 — there is NO flow input->output PROPAGATION. A flow does NOT inherit
;; the data-classification of its input paths; the `:rf.egress/output-sensitivity`
;; enum (`:inherit` / `:sensitive` / `:public`) is REMOVED. If you derive a
;; secret into a flow's output, classify that output path EXPLICITLY (the same
;; rule as subs: classify exactly what you mark, nothing is inherited). This
;; keeps the helper lightweight (EP-0025 §"What is removed").
;;
;; The flow's output lands in app-db at `(:output-path flow)`, so a sensitive /
;; large output value egresses through TWO observation channels:
;;   1. the `:rf.flow/computed` `:result` / `:before` trace slots (and the
;;      `:rf.flow/failed` failure path), and
;;   2. the app-db destination slot itself — visible to App-DB-Diff style
;;      observation, the `:rf.event/db` pending-db trace (the t2
;;      `:rf.event/db-pending-post-flow` stamp), view render-arg egress, and any
;;      downstream sub reading the slot.
;;
;; Rather than projecting the registration declarations at each flow emit site (a
;; flow-id->declarations table is wrong for flows — Spec 013 lets the SAME
;; flow-id carry DIFFERENT definitions, hence different declarations, in
;; different frames), we make the declarations FIRST-CLASS through the SAME
;; per-frame elision registry the schema-first wire walker
;; (`elision/elide-wire-value`) already reads. We translate the registration's
;; output-rooted EXPLICIT declarations into ABSOLUTE app-db declarations rooted
;; at `(:output-path flow)` and write them into the frame's
;; `[:rf.runtime/elision :sensitive-declarations]` / `:declarations` slots,
;; stamped `{:source :flow :flow-id <id>}`.
;;
;; ONE walker then covers BOTH channels: the flow trace `:result` / `:before`
;; ride `elide-wire-value` rooted at `(:output-path flow)`; `project-db-tags` /
;; `project-view-rendered-tags` (re-frame.classification) elide the app-db
;; destination slot through the SAME registry.
;;
;; The EXPLICIT declarations are known at `reg-flow` time and do not change with
;; app-db mutation (no propagation), so they are installed ONCE at registration
;; (`write-flow-output-marks!`) and dropped on `clear-flow` — there is no
;; drain-time mark-mutation refresh (that machinery existed only to recompute
;; propagated marks, now removed).
;;
;; Frame-scoping is structural: the declarations live in the FRAME's own app-db
;; elision registry, so the same flow-id registered against two frames writes
;; into two different registries — no cross-frame bleed. Entries are stamped
;; `{:source :flow :flow-id <id>}` so lifecycle cleanup (clear-flow / path-change
;; re-register / frame destroy) drops exactly this flow's contributions while
;; leaving frame-, effect-, and other-flow-sourced entries untouched.

(defn- explicit-flow-output-mark-paths
  "Translate a flow's EXPLICIT output-rooted classification keys into ABSOLUTE
  app-db declaration paths rooted at the flow's `:output-path`. Returns
  `[sensitive-paths large-paths]` (each a vector of absolute path vectors).

  - `:large? true` => the `:output-path` itself (force-mark the whole output
    large);
  - per-path `:sensitive` / `:large` entries => `(:output-path flow)` ++ sub-path
    (`[[]]` whole-value mark => the `:output-path` itself).

  EP-0025: there is no `:rf.egress/output-sensitivity` whole-output force-mark —
  classify the whole output with `:sensitive [[]]` (which lands here as the
  `:output-path` itself via the per-path arm)."
  [flow]
  (let [base       (vec (:output-path flow))
        abs        (fn [sub] (into base sub))
        per-sens   (->> (:sensitive flow) (filter vector?) (map abs))
        per-large  (->> (:large flow)     (filter vector?) (map abs))
        whole-lg   (when (true? (:large? flow))     [base])]
    [(vec per-sens)
     (vec (concat whole-lg per-large))]))

;; ---- partition-qualified input primitives (EP-0001 §535-551) -------------
;;
;; The ONE home for the binary `:inputs`-path partition syntax shared across
;; the flows artefact: a bare leading path element resolves against app-db; a
;; leading `:rf.db/runtime` opts the input into the runtime-db partition and is
;; stripped before the path is used. Two consumers ride these primitives:
;;   - `re-frame.flows/resolve-input` — reads the input VALUE against the
;;     pending app-db / runtime-db partitions (strips for the runtime read);
;;   - `re-frame.flows.tooling/declared-input` — lowers to the algebra
;;     `[:db …]` / `[:runtime …rest]` declared-input form.
;; `registry` is required by both `re-frame.flows` and `re-frame.flows.tooling`
;; and requires neither back (no cycle), so it is the natural shared home.

(def ^:no-doc runtime-partition-key
  "The reserved partition key that, as a leading `:inputs`-path element, opts a
  flow input into the runtime-db partition (`:rf.db/runtime`). Bare paths (any
  other leading element) resolve against app-db. The single const all flow-
  artefact consumers key on so the rule, the resolver, and the doc stay in
  lockstep."
  :rf.db/runtime)

(defn ^:no-doc runtime-input?
  "True iff `path` is a partition-qualified runtime-db input — a vector whose
  FIRST element is `runtime-partition-key` (`:rf.db/runtime`). Everything else
  is a bare app-db path (binary syntax — there is no third `[:rf.db/app …]`
  explicit-app form)."
  [path]
  (= runtime-partition-key (first path)))

(defn ^:no-doc input-resolve-path
  "Resolve one flow `:inputs` path to the absolute declaration-coordinate path
  the elision registry is keyed by (plain path vectors, partition-blind). A bare
  input is an app-db path verbatim; a runtime-db-qualified input
  `[:rf.db/runtime …rest]` drops the partition key so its `…rest` matches a
  declaration on that runtime-db slot. Partition-aware by construction.

  Public so `re-frame.flows/resolve-input` (the runtime-db value read) and
  `re-frame.flows.tooling/declared-input` (the algebra `[:runtime …rest]` /
  `[:db …]` lowering) and `re-frame.flows/elide-inputs` (seeding
  `elision/elide-wire-value`'s `:path` opt for a flow's per-input trace value)
  all route partition stripping through this one fn."
  [input-path]
  (if (runtime-input? input-path)
    (subvec (vec input-path) 1)
    (vec input-path)))

(defn- flow-declares-marks?
  "True when the flow registration carries any output data-classification key
  (`:sensitive` / `:large` / `:large?`). Used to gate the elision-registry write
  at `reg-flow` time — a flow that declares no classification installs nothing
  (EP-0025: no propagation, so a no-classification flow can never acquire an
  inherited mark either)."
  [flow]
  (or (contains? flow :sensitive)
      (contains? flow :large)
      (contains? flow :large?)))

(defn- drop-flow-sourced
  "Remove every `{:source :flow :flow-id <flow-id>}` entry from a declaration
  map, preserving every other-sourced entry (and any OTHER flow's entries)."
  [decls flow-id]
  (when decls
    (reduce-kv (fn [acc path decl]
                 (if (and (= :flow (:source decl))
                          (= flow-id (:flow-id decl)))
                   acc
                   (assoc acc path decl)))
               {}
               decls)))

(defn- assoc-flow-paths
  "Add `paths` to `existing` declaration map, each stamped
  `{:source :flow :flow-id <flow-id>}` so lifecycle cleanup can find them."
  [existing paths flow-id]
  (reduce (fn [acc path]
            (assoc acc (vec path) {:source :flow :flow-id flow-id}))
          (or existing {})
          paths))

(defn- fold-flow-declarations
  "Fold ONE flow's `:source :flow` EXPLICIT output declarations into the registry
  `reg` (pure). Drops THIS flow's prior `:source :flow` entries first (so a
  re-registration replaces cleanly), then overlays the flow's explicit absolute
  declarations. EP-0025: explicit-only — no input->output propagation, no
  resolution against the carried registry."
  [reg flow]
  (let [flow-id            (:id flow)
        [explicit-s explicit-l] (explicit-flow-output-mark-paths flow)
        carry-s            (drop-flow-sourced (get reg :sensitive-declarations) flow-id)
        carry-l            (drop-flow-sourced (get reg :declarations) flow-id)
        new-s              (assoc-flow-paths carry-s explicit-s flow-id)
        new-l              (assoc-flow-paths carry-l explicit-l flow-id)]
    (cond-> (or reg {})
      (seq new-s)    (assoc :sensitive-declarations new-s)
      (empty? new-s) (dissoc :sensitive-declarations)
      (seq new-l)    (assoc :declarations new-l)
      (empty? new-l) (dissoc :declarations))))

(defn- write-flow-output-marks!
  "Install (or refresh) a SINGLE `flow`'s EXPLICIT output declarations into
  `frame-id`'s app-db elision registry (the `reg-flow`-time path). Delegates to
  `fold-flow-declarations`; shares `swap-elision-slot!` so the runtime-prune
  semantics stay uniform."
  [frame-id flow]
  (elision/swap-elision-slot! frame-id
    (fn [reg] (fold-flow-declarations reg flow))))

(defn- clear-flow-output-marks!
  "Drop `flow-id`'s `:source :flow` declarations from `frame-id`'s elision
  registry. Called on `clear-flow` so a *single* deregistered flow leaves no
  orphaned redaction declaration behind while its frame and the frame's other
  flows live on. Other-sourced entries survive.

  NOT called on frame-destroy teardown (`teardown-on-frame-destroy!`),
  and deliberately so: the elision registry lives in the frame's runtime-db
  partition at `[:rf.runtime/elision]` (re-frame.elision §registry-of),
  INSIDE the one physical `:frame-state` container held under the frame
  record. `destroy-frame!` step 6 (`frame.cljc` §dissoc-frame!) drops that
  whole frame record — container, runtime-db partition, and every
  `:source :flow` declaration with it — so a per-flow registry scrub at
  teardown would be redundant work over state that is about to be GC'd.
  A reused frame-id starts from a FRESH empty container
  (`frame.cljc` §new-frame-record), so a destroyed/reused frame can never
  observe a prior incarnation's stale flow-sourced declaration. See
  `flows_destroy_frame_teardown_test` §destroy-frame-drops-flow-output-marks."
  [frame-id flow-id]
  (elision/swap-elision-slot! frame-id
    (fn [reg]
      (let [new-s (drop-flow-sourced (get reg :sensitive-declarations) flow-id)
            new-l (drop-flow-sourced (get reg :declarations) flow-id)]
        (cond-> (or reg {})
          (seq new-s)    (assoc :sensitive-declarations new-s)
          (empty? new-s) (dissoc :sensitive-declarations)
          (seq new-l)    (assoc :declarations new-l)
          (empty? new-l) (dissoc :declarations))))))

;; ---- registration --------------------------------------------------------

;; `reg-flow`'s same-frame `:output-path`-change branch vacates the
;; OLD output path via `vacate-output-path!`, which is defined alongside
;; `clear-flow` further down (both share the app-db dissoc semantics).
;; Forward-declare it so the registration path can reference it without
;; reordering the large clear/teardown block above the hot registration
;; surface.
(declare vacate-output-path!)

(defn- reconstruct-flow
  "Reconstruct the internal flow-map from the 3-slot grammar `(reg-flow
  flow-id metadata derive-fn)` (rf2-bqstzr): the id moves back onto the map
  under `:id`, and the pure `:derive` value slot is placed under `:derive`.
  The `:frame` mounting key (if present in metadata) is pulled OUT — it is the
  frame-mounting concern, not a flow-map key (per Conventions §The `:frame`
  registration-metadata key). Returns `[flow frame]` where `flow` is the
  internal flow-map (id + `:inputs` / `:output-path` / `:doc` / `:schema` /
  classification keys + `:derive`) and `frame` is the extracted `:frame`
  override (nil when absent). Mirrors reg-resource / reg-mutation / reg-route's
  `(assoc metadata <value-key> value)` reconstruction (rf2-wvh95f F1).

  Guards the MIDDLE slot is a map BEFORE any `assoc` / `contains?` runs (a
  non-map metadata would otherwise leak a raw host exception), and rejects a
  `:derive` left INSIDE the metadata map as a mislocated key with the loud
  `:rf.error/invalid-flow-metadata` — the third slot is `:derive`'s one home."
  [flow-id metadata derive-fn]
  ;; rf2-bqstzr — the metadata MIDDLE slot must be a map BEFORE any `assoc` /
  ;; `contains?` / `dissoc` runs against it. A non-map metadata would otherwise
  ;; leak a raw host exception instead of the public
  ;; `:rf.error/invalid-flow-metadata`. Mirrors reg-route's `route-bad-metadata`
  ;; / reg-resource's `resource-bad-spec` non-map guard.
  (when-not (map? metadata)
    (throw (error/thrown-ex-info
             :rf.error/invalid-flow-metadata
             'rf/reg-flow
             (str "flow " flow-id "'s metadata (the MIDDLE slot) must be a map, "
                  "got " (pr-str (type metadata)) ". Per rf2-bqstzr the grammar "
                  "is (reg-flow " flow-id " {…} derive-fn): the :inputs / "
                  ":output-path / :doc / :schema reflection-config metadata map "
                  "is the SECOND slot, the pure :derive fn is the THIRD.")
             {:recovery :fix-registration
              :extra    {:id flow-id :value metadata}})))
  ;; rf2-bqstzr — `:derive` is the 3-slot VALUE (the pure derivation). A
  ;; `:derive` left INSIDE the metadata map is a mislocated key; reject it
  ;; loudly so the grammar change cannot be half-applied (a stray metadata
  ;; `:derive` would otherwise silently win or lose against the value-slot one).
  (when (contains? metadata :derive)
    (throw (error/thrown-ex-info
             :rf.error/invalid-flow-metadata
             'rf/reg-flow
             (str "flow " flow-id " declares :derive inside its metadata map — "
                  "per rf2-bqstzr the pure derivation is the THIRD slot: "
                  "(reg-flow " flow-id " {…} derive-fn). Move the derive fn out "
                  "of the metadata map into the value slot.")
             {:recovery :fix-registration
              :extra    {:id flow-id :value (:derive metadata)}})))
  [(-> metadata (dissoc :frame) (assoc :id flow-id :derive derive-fn))
   (:frame metadata)])

(defn reg-flow
  "Register a flow against a frame. Per the canonical Spec 001 3-slot grammar
  (rf2-bqstzr, completing the rf2-wvh95f F1 alignment of the fused registrars):

      (rf/reg-flow :rectangle/area
        {:inputs      [[:width] [:height]]
         :output-path [:area]
         :doc         \"Rectangle area from :width × :height.\"}
        (fn [w h] (* w h)))

  The pure `:derive` fn — the flow's HANDLER (the derivation the input change
  runs) — is the THIRD slot; the middle slot is the reflection-config metadata
  map (`:inputs`, `:output-path`, `:doc`, `:schema`, the EP-0025 output
  classification keys `:sensitive` / `:large` / `:large?`, and the `:frame`
  mounting key). Splitting the derivation out of the fused flow-map restores
  clean doc-DCE (the middle slot is now a pure metadata map) and brings flows
  into line with reg-resource / reg-mutation / reg-route. A `:derive` left
  INSIDE the metadata map is rejected loudly as a mislocated key
  (`:rf.error/invalid-flow-metadata`).

  Per Spec 013 — flows are frame-scoped: their lifecycle, evaluation, undo /
  time-travel semantics all belong to one frame.

  EP-0002 — flows are CONTEXT-REQUIRED FRAME-LOCAL registration. The frame to
  register against is the explicit `:frame` metadata key (the *override*), else
  the carried-invariant scope chain via `frame/require-current-frame!` (a
  `with-frame` / frame-provider scope, or a frame `:initial-events` step). A
  `reg-flow` issued under no established scope and no explicit `:frame` raises
  the always-on `:rf.error/no-frame-context` (per Spec 002 §Frame target
  resolution) rather than silently registering against `:rf/default` —
  namespace-load time is not a reason to synthesise a default frame.

  Returns the flow's id per the `reg-*` return-value convention
  ([Conventions §reg-* return-value convention]).

  A single 3-slot arity only — like its required-metadata siblings
  `reg-route` / `reg-resource`, there is no 2-arity: `:inputs` /
  `:output-path` are mandatory, so a metadata slot is always required."
  ([flow-id metadata derive-fn]
   (let [[flow frame] (reconstruct-flow flow-id metadata derive-fn)]
   (validate-flow flow)
   (let [;; rf2-8mxnnk: normalize an EXPLICIT `:frame` target — a frame VALUE
         ;; (make-frame's return token) or a frame-id keyword — to its frame-id
         ;; BEFORE the liveness check / registry keying, mirroring the READ path
         ;; (`resolve-read-frame` / `flow-meta-at`). Without this a frame VALUE
         ;; keys `@flows` (and the `frame/frame` liveness probe) by the raw map,
         ;; so a LIVE frame reads as not-live (`:rf.error/flow-frame-not-live`)
         ;; and a later `flow-meta-at` — which normalizes — misses the flow. The
         ;; scope-derived id is already a keyword, so only the explicit override
         ;; is normalized (idempotent on a keyword).
         frame-id (or (some-> frame frame/frame-target->id)
                      (frame/require-current-frame!
                        :reg-flow
                        {:where    'rf/reg-flow
                         :event-id (:id flow)}))
         flow-id  (:id flow)
         ;; SINGLE-STORE (rf2-en00bk): stamp source-coords (`:ns` / `:line` /
         ;; `:file`, slim in CLJS prod) into the flow-map STORED in the
         ;; authoritative per-frame `flows` atom — so `flow-meta-at` (the
         ;; `handler-meta :flow` replacement) surfaces them, exactly as the old
         ;; double-store stamped them onto the now-removed registrar `:flow`
         ;; slot. `merge-coords` is idempotent for a same-source re-eval and
         ;; adds only documentation keys (validation already ran on the bare
         ;; `flow`; downstream `:derive` / `:inputs` / `:output-path` reads are
         ;; unaffected by the extra coord keys). Mirrors how
         ;; `schemas.storage/reg-app-schema` stamps coords into
         ;; `schemas-by-frame`.
         flow     (source-coords/merge-coords flow)]
     ;; Reject registration against a NON-LIVE frame BEFORE any state mutates.
     ;; `frame/frame` returns nil when the frame record is absent (never
     ;; registered, or `destroy-frame!`'s step-6 dissoc ran) OR
     ;; present-but-`:destroyed?` (post-step-3, pre-step-6). Either way the
     ;; frame is non-live and must not acquire flow state.
     ;;
     ;; `call-serialized-with-drain!` runs its thunk DIRECTLY for an
     ;; absent/destroyed frame (frame.cljc §call-serialized-with-drain!), so
     ;; without this guard the registration below would install a
     ;; `flows[frame-id flow-id]` row and an elision declaration stamped with
     ;; the dead `frame-id` — and a later `reg-frame` reusing that id would
     ;; inherit the resurrected flow, breaking the frame-destroy isolation
     ;; contract (Spec 013 §destroy-frame! releases every per-frame flow slot /
     ;; last-inputs row).
     ;;
     ;; So REJECT with a stable structured error category rather than create
     ;; dormant state for a typo'd or destroyed frame id. `clear-flow` keeps
     ;; its permissive absent-frame no-op (teardown must be idempotent); only
     ;; this MUTATING path rejects.
     (when (nil? (frame/frame frame-id))
       (error/throw-error!
         :rf.error/flow-frame-not-live
         'rf/reg-flow
         (str "cannot register a flow against frame "
              frame-id
              " — the frame is not live (absent / never registered, "
              "or torn down by destroy-frame!). Register the flow "
              "against a live frame; a destroyed frame must not "
              "acquire flow state (Spec 013 §destroy-frame!).")
         {:recovery :fix-registration
          :extra    {:frame frame-id
                     :flow  flow}}))
     ;; ATOMIC check-and-insert. The cycle check and the commit are ONE
     ;; `swap!` update fn over `@flows`: it reads the frame's
     ;; CURRENTLY-COMMITTED flow-map, runs topo-sort on the prospective map,
     ;; and — only if that passes — returns the map with the new flow assoc'd
     ;; in. `swap!` re-invokes the update fn (re-reading committed state,
     ;; re-running the cycle check) whenever a concurrent writer wins the
     ;; compare-and-set, so a cycle can NEVER be admitted by interleaving:
     ;; two threads reg-flow'ing on the same frame two flows that together
     ;; form a cycle (T1: A reads B's path; T2: B reads A's path) cannot each
     ;; pass against the OTHER's not-yet-committed flow and then both commit.
     ;; The validation lives inside the update fn, so it re-runs against the
     ;; winning CAS's view.
     ;;
     ;; The single-threaded path runs exactly one topo-sort: `swap!` invokes
     ;; the update fn once with no contention. Only contended same-frame
     ;; registration pays the retry (re-running the cheap Kahn sort over a
     ;; handful of nodes), and only then to preserve correctness.
     ;;
     ;; The cycle-check throw propagates OUT of `swap!`, so on rejection the
     ;; atom is left untouched and the prior registration survives — a
     ;; rejected REPLACEMENT does not vacate the slot the prior entry shares.
     ;;
     ;; `prior-on-frame` captures THIS frame's currently-committed flow
     ;; entry for `flow-id`, recorded from inside the update fn so it
     ;; reflects the state the WINNING CAS observed (a contended retry
     ;; re-records against the re-read map; the last invocation — the one
     ;; whose return value commits — leaves the authoritative value). It
     ;; drives two decisions below: per-frame first-vs-replacement for the
     ;; `:rf.flow/registered` trace, the same-frame `:output-path`-change
     ;; vacate, and the same-frame stale-`last-inputs` drop. `nil` ⇒ first-time
     ;; registration of `flow-id` on THIS frame — an INDEPENDENT first-time
     ;; registration per frame (Spec 013 §Frame-scoping), even if a SIBLING
     ;; frame already registers the same id in the per-frame `flows` atom.
     (let [prior-on-frame (volatile! nil)]
       ;; A same-frame `reg-flow` REPLACEMENT publishes the new flow into
       ;; `flows` (visible to a drain) in the `swap!` below, then drops the
       ;; stale-`last-inputs` row DIRECTLY (the `drop-frame-flow-row!` call at
       ;; the foot of the thunk) on a real same-frame replacement. Serializing
       ;; publish → path-vacate → invalidation against the frame drain
       ;; (`:drain-lock`) makes them one indivisible step: no drain can observe
       ;; the new flow before its stale dirty-check row is dropped, so the new
       ;; flow re-evaluates on the next event regardless of input equality
       ;; (Spec 013's re-registration contract). The atomic check-and-insert
       ;; `swap!` runs INSIDE the serialized region — its TOCTOU cycle guarantee
       ;; composes with the outer drain-exclusion. Reentrant: a mid-drain
       ;; `:rf.fx/reg-flow` runs directly inside the single-drainer window (see
       ;; `frame/call-serialized-with-drain!`).
       ;;
       ;; SINGLE-STORE (rf2-en00bk): the invalidation is a DIRECT,
       ;; FRAME-SCOPED `drop-frame-flow-row!` keyed on THIS `frame-id` —
       ;; not the frame-blind registrar replacement-hook the old double-store
       ;; relied on. The hook read `:frame` back off the registrar metadata to
       ;; recover the frame; with the per-frame `flows` atom as the sole store
       ;; the frame is already in hand (`frame-id`), so the drop is more direct
       ;; and structurally sibling-safe (per-frame container).
       (frame/call-serialized-with-drain!
         frame-id
         (fn []
           (swap! flows
                  (fn [m]
                    (let [prior-frame (get m frame-id)]
                      (vreset! prior-on-frame (get prior-frame flow-id))
                      (let [prospective (assoc prior-frame flow-id flow)]
                        ;; Two registration-time rejections, both run on the
                        ;; PROSPECTIVE map inside this update fn so they share
                        ;; the cycle check's atomicity: a throw propagates out
                        ;; of `swap!`, the CAS never fires, and the prior
                        ;; registration survives untouched.
                        ;;
                        ;; 1. Throws :rf.error/flow-path-overlap if
                        ;;    the new flow's output :path overlaps an already-
                        ;;    registered sibling's :path (one a prefix of the
                        ;;    other). The topo dependency rule compares :path vs
                        ;;    :inputs only, so overlapping outputs get no edge
                        ;;    and their eval order is undefined — reject before
                        ;;    any state mutates (Spec 013 §Disjoint output
                        ;;    paths). Checked BEFORE the cycle sort so a frame
                        ;;    with overlapping outputs reports the more specific
                        ;;    footgun rather than whatever (or no) cycle the topo
                        ;;    walk happens to surface.
                        ;; 2. Throws :rf.error/flow-cycle if the prospective map
                        ;;    is cyclic; the update fn never returns and the CAS
                        ;;    never fires, so the atom is unchanged.
                        (topo/detect-output-path-overlap! prospective)
                        (topo/topo-sort prospective)
                        (assoc m frame-id prospective)))))
           ;; A same-frame re-registration that MOVES the output to a new
           ;; `:output-path` must vacate the OLD path from this frame's app-db —
           ;; otherwise the previous definition's last write lingers and
           ;; downstream reads see stale derived state at the abandoned slot.
           ;; Only fires on a real same-frame replacement (`prior-on-frame`
           ;; non-nil) whose `:output-path` actually changed; the commit above
           ;; already installed the new definition, so the new path recomputes
           ;; on the next drain. First-time registration and same-path
           ;; hot-reload leave app-db untouched.
           ;;
           ;; The vacate is routed by call shape:
           ;;  - OUT of a drain (a top-level / `:rf.fx`-post-commit lifecycle
           ;;    call): write app-db DIRECTLY — there is no pending commit to
           ;;    clobber it and the change is durable immediately.
           ;;  - IN a drain (reentrant from an event handler / `:rf.fx/reg-flow`
           ;;    mid-cascade): a direct write would be OVERWRITTEN by the single
           ;;    deferred commit that later publishes the handler's returned
           ;;    `:db` (which still carries the old path) — resurrecting the old
           ;;    output. So RECORD the abandoned path instead; the current
           ;;    drain's `run-flows-on-db` dissocs it from the PENDING `:db`
           ;;    before the flow walk, so the vacate rides the SAME value the
           ;;    deferred commit publishes and the handler's `:db` cannot
           ;;    resurrect it. (`call-serialized-with-drain!` already ran this
           ;;    thunk reentrantly when in-drain, so `frame/in-drain?` here is
           ;;    the matching discriminator.)
           (when-let [prior @prior-on-frame]
             (let [old-path (:output-path prior)]
               (when (not= old-path (:output-path flow))
                 (if (frame/in-drain? frame-id)
                   (record-abandoned-output-path! frame-id old-path)
                   (vacate-output-path! frame-id old-path)))))
           ;; EP-0025: install this flow's EXPLICIT output data-classification
           ;; declarations into the frame's app-db elision registry, rooted at
           ;; `(:output-path flow)`. `write-flow-output-marks!` drops THIS flow's
           ;; prior `:source :flow` entries first, so a re-registration that
           ;; changed `:output-path` or the declaration set replaces cleanly
           ;; (covering the old-path declarations the value-vacate above does not
           ;; touch). Inside the serialized region so a concurrent drain's
           ;; `elide-wire-value` read cannot observe a half-updated registry.
           ;;
           ;; Gate: skip the registry write only for a FIRST registration that
           ;; declares no classification key. Such a flow has nothing to install
           ;; or clear (EP-0025: no propagation, so it can never acquire an
           ;; inherited mark either). A re-registration (`prior-on-frame`
           ;; non-nil) ALWAYS writes (even a now-unmarked replacement must CLEAR
           ;; the prior definition's declarations).
           (when (or (flow-declares-marks? flow)
                     (some? @prior-on-frame))
             (write-flow-output-marks! frame-id flow))
           ;; Per Spec 001 §Hot-reload semantics + Spec 013 §Re-registration:
           ;; a same-frame REPLACEMENT must (1) drop THIS frame's stale
           ;; dirty-check row so the new flow re-evaluates on the next drain
           ;; regardless of input equality (a hot-reloaded `:derive` with
           ;; identical recent inputs would otherwise keep serving the previous
           ;; result), and (2) emit the `:rf.registry/handler-replaced`
           ;; hot-reload trace (Spec 001 §Hot-reload trace surface) the flow
           ;; panel / 10x consume, carrying `:different-fn?` so tools can branch
           ;; a real `:derive` body swap from an idempotent reload.
           ;;
           ;; SINGLE-STORE (rf2-en00bk): with the per-frame `flows` atom the
           ;; SOLE store there is no registrar `:flow` write and no
           ;; replacement-hook indirection — both effects run DIRECTLY here on a
           ;; real same-frame replacement (`prior-on-frame` non-nil; a first-time
           ;; registration has no stale row and emits `:rf.flow/registered`
           ;; instead). The drop is frame-scoped (`drop-frame-flow-row!` keyed on
           ;; `frame-id`) so a re-registration on frame `:left` never touches
           ;; `:right`'s row for the same id (frame isolation; per-frame
           ;; container makes this structural). The trace emit reuses the SAME
           ;; B4 hot-reload dedup-by-shape seam the registrar consults
           ;; (`:trace.tooling/dedup-allow?`, Spec 009 §Hot-reload dedup) so an
           ;; idempotent reload (identical `:derive` identity) emits ZERO events
           ;; and a real body swap emits exactly one — the contract the
           ;; registrar slot used to drive now driven directly by the flows
           ;; artefact. Kept INSIDE the serialized region so the publish above
           ;; and these effects are indivisible to a concurrent drain.
           (when (some? @prior-on-frame)
             (drop-frame-flow-row! frame-id flow-id)
             (when interop/debug-enabled?
               (let [prior      @prior-on-frame
                     different? (not= (:derive prior) (:derive flow))
                     ;; The dedup shape keys on `:handler-fn` identity (Spec 009
                     ;; B4 / `trace.tooling/handler-shape`). Stamp it = `:derive`
                     ;; so the shape discriminates a real body swap from an
                     ;; idempotent reload exactly as the registrar metadata used
                     ;; to (the now-removed `:handler-fn` stamp on the registrar
                     ;; `:flow` slot). Source-coords are stripped by the shape
                     ;; computation, so the coords-stamped `flow` is fine here.
                     shape-meta (assoc flow :handler-fn (:derive flow))
                     dedup-ok?  (if-let [f (late-bind/get-fn-cached
                                             :trace.tooling/dedup-allow?)]
                                  (f :rf.registry/handler-replaced :flow flow-id shape-meta)
                                  true)]
                 (when dedup-ok?
                   (trace/emit! :rf.registry :rf.registry/handler-replaced
                                {:kind          :flow
                                 :id            flow-id
                                 :different-fn? different?})))))))
       ;; Per Spec 009 §:op-type vocabulary: :rf.flow/registered fires on
       ;; FIRST-TIME registration AGAINST THIS FRAME. The gate keys on the
       ;; PER-FRAME prior slot (`prior-on-frame`), so registering the same
       ;; flow-id against a SECOND frame — an INDEPENDENT first-time
       ;; registration per Spec 013 §Frame-scoping line 102 — emits
       ;; `:rf.flow/registered` with the correct `:frame` tag. A genuine
       ;; same-frame re-registration (`prior-on-frame` non-nil) suppresses the
       ;; `:rf.flow/registered` emit — its hot-reload signal rides the
       ;; `:rf.registry/handler-replaced` trace the same-frame replacement
       ;; branch above emits DIRECTLY (rf2-en00bk single-store; per Spec 001
       ;; §Hot-reload trace surface) instead.
       ;;
       ;; The outer `debug-enabled?` gate matches the hot-path emits in
       ;; flows.cljc (per Spec 009 §Production builds, "keep the gate
       ;; OUTERMOST"); reg-flow is a cold path so the cost is negligible,
       ;; but the gate keeps the tag-map literal out of CLJS prod and the
       ;; convention uniform across every flow emit site.
       (when (and interop/debug-enabled? (nil? @prior-on-frame))
         (trace/emit! :flow :rf.flow/registered
                      {:flow-id flow-id
                       :inputs  (:inputs flow)
                       :path    (:output-path flow)
                       :frame   frame-id})))
     ;; Spec 015 §7. Flows — the output data-classification marks are
     ;; installed FRAME-AWARE into the app-db elision registry via
     ;; `write-flow-output-marks!` inside the serialized region above. They
     ;; are deliberately NOT also stashed in a global side-table
     ;; per-(kind, id) table: that table is keyed by
     ;; flow-id ALONE, but Spec 013 lets the SAME flow-id carry DIFFERENT
     ;; definitions (hence different marks) per frame, so a frame-blind
     ;; `{flow-id marks}` entry is the wrong shape for flows. The
     ;; frame-scoped elision-registry write is the single source of truth
     ;; the wire walker, the db-diff projection, and the view-render-arg
     ;; projection all read.
     flow-id))))

(defn- dissoc-in-safe
  "Like `dissoc-in` over `(butlast path) → (last path)` but robust against
  the two unmaterialised-output failure modes:

  - **Unmaterialised parent.** When a flow with `:path [:step-2 :result]`
    is cleared BEFORE its first drain, the parent slot `:step-2` may not
    exist. The naïve `(update-in db [:step-2] dissoc :result)` returns
    `(dissoc nil :result)` ⇒ `nil`, producing `{:step-2 nil}` — a
    spurious nil parent. Detect this case and leave `db` unchanged.
  - **Non-map intermediate.** When an intermediate path step holds a
    non-map value (a scalar already wrote past the flow's planned path),
    the naïve `update-in` calls `(dissoc 1 :result)` and throws
    `ClassCastException`. Treat this as a no-op — the flow's `:output-path`
    never materialised, so there's nothing to clear.

  Single-element paths and non-vector paths are handled by the caller's
  earlier branches; this helper is only called for `(>= (count path) 2)`."
  [db path]
  (let [parent-path (vec (butlast path))
        leaf        (last path)
        parent      (get-in db parent-path ::missing)]
    (cond
      ;; Parent was never materialised — leave db as-is. Registering a
      ;; nested-path flow then clearing before any drain would write
      ;; `{<parent> nil}` otherwise.
      (or (= ::missing parent) (nil? parent)) db
      ;; Parent is non-map (scalar / vector / set) — there's no
      ;; meaningful "dissoc this leaf" on a non-map intermediate. Throwing
      ;; ClassCastException for a cleanup operation is poor manners; leave
      ;; the value untouched (it's not OUR flow's output anyway).
      (not (map? parent)) db
      :else (update-in db parent-path dissoc leaf))))

(defn ^:no-doc vacate-path-in-db
  "Pure `db → db` removal of a flow output `:output-path`'s LEAF — the value
  transform shared by `vacate-output-path!` (which writes the result to the
  live app-db) and `run-flows-on-db`'s in-drain pending-`:db` vacate
  (which dissocs abandoned paths from the PENDING value the deferred commit
  publishes). Returns `db` unchanged (often `identical?`) when the dissoc is a
  no-op (missing key, unmaterialised parent, non-map intermediate).

  `validate-flow` guarantees `:output-path` is a non-empty vector at
  registration, so a `:output-path` read back out of the registry always
  carries one. A single-element vector `[:k]` is dissoc'd directly
  (`(update-in db [] dissoc :k)` would write `{... nil nil}`); a `(>= 2)` path
  routes through `dissoc-in-safe` (unmaterialised-parent / non-map-intermediate
  safe)."
  [db path]
  (if (= 1 (count path))
    (dissoc db (first path))
    (dissoc-in-safe db path)))

(defn- vacate-output-path!
  "Dissoc a flow's output `:output-path` from `frame-id`'s app-db (only that
  frame's container). Shared by `clear-flow` (full deregistration) and the
  same-frame `:output-path`-change branch of `reg-flow` (moving a flow's
  output to a new path must vacate the OLD path so downstream reads don't see
  stale derived state at the abandoned slot).

  `validate-flow` guarantees `:output-path` is a non-empty vector at
  registration (rejects non-vector and empty via :rf.error/flow-bad-path),
  so a `:output-path` read back out of the registry always carries one — no
  non-vector / empty-path arms are reachable here.

  When :path is a single-element vector [:k], (butlast [:k]) is () and
  (update-in db [] dissoc :k) does NOT dissoc — Clojure's update-in on the
  empty path falls into (assoc {} nil (apply f val args)), producing
  {... nil nil}. So length 1 is special-cased to dissoc the leaf directly. The
  (>= 2) branch routes through `dissoc-in-safe`, which handles the
  unmaterialised-parent / non-map-intermediate cases without writing nil
  parents or throwing.

  Skips the write when the dissoc branch was a no-op (missing key, or
  `dissoc-in-safe` returning `db` literally on unmaterialised-parent /
  non-map-intermediate) — otherwise a no-op write triggers reactive sub-cache
  invalidation, a cheap-but-needless walk of the sub graph (`identical?` is
  O(1)). No-op when the frame has no app-db container.

  Writes the app-db PARTITION of the one physical frame-state container via
  `frame/swap-frame-db!` (`app-db-container` is a READ-ONLY projection). Flow
  OUTPUTS are app-db values, so vacating one is an app-db write. The
  `identical?` no-op skip is preserved by computing `new-db` first and only
  swapping when it changed."
  [frame-id path]
  (when-let [db (frame/frame-app-db-value frame-id)]
    (let [new-db (vacate-path-in-db db path)]
      (when-not (identical? new-db db)
        (frame/swap-frame-db! frame-id (constantly new-db))))))

;; ---- registrar-slot owner maintenance: REMOVED (rf2-en00bk) ---------------
;;
;; The double-store's `realign-registrar-owner!` workaround is GONE. It kept a
;; frame-BLIND registrar `:flow` slot (keyed by flow-id alone) pointing at a
;; live owner whenever the slot's current writer was cleared / destroyed —
;; necessary ONLY because flows are FRAME-DIVERGENT-PER-ID and the single-slot
;; shape could not hold the authoritative per-frame state. With the per-frame
;; `flows` atom as the SOLE store (applying the rf2-0frdi schemas precedent)
;; there is no slot to realign: `clear-flow` / `teardown-on-frame-destroy!`
;; simply drop the cleared frame's entry from the per-frame map, and any
;; surviving frame's entry is already authoritative in place. The `:flow`
;; registrar kind stays RESERVED-but-empty (Spec 001 §Registry model); tools
;; introspect via `flow-meta-at` / `flows-snapshot`.

(defn clear-flow
  "Deregister a flow from a frame; dissoc its output path from that
  frame's app-db (only that frame). EP-0002 — context-required
  frame-local: the explicit `:frame` opt (the *override*) wins, else the
  carried-invariant scope chain via `frame/require-current-frame!`. A
  `clear-flow` issued under no established scope and no explicit `:frame`
  raises `:rf.error/no-frame-context` rather than clearing against
  `:rf/default`.

  The nested-path dissoc is robust against the output path never having
  been materialised (no spurious nil parent created) and against a non-map
  intermediate (no ClassCastException thrown) — see `dissoc-in-safe` /
  `vacate-output-path!` above.

  Vacation contract: clearing a flow with `:path [:wizard :result]` removes
  the LEAF (`:result`) only. If `:result`
  was the sole key under `:wizard`, an empty parent map `{:wizard {}}`
  remains — this is deliberate, not a leak. The flow's *value* is
  fully gone (the spec's \"vacate the slot\" requirement); pruning empty
  ancestor maps would risk deleting unrelated sibling slots that happen
  to be empty and own ancestors this flow never created, so leaf-only
  vacation is the correct contract. Downstream consumers read the leaf,
  not the parent's emptiness. When the output path never materialised
  (parent absent / nil / non-map), `dissoc-in-safe` returns `db`
  unchanged and `vacate-output-path!` skips the swap entirely — a
  deliberate no-op with no app-db write and no sub-cache invalidation
  (cross-ref `dissoc-in-safe`)."
  ([id] (clear-flow id {}))
  ([id {:keys [frame] :as _opts}]
   (let [;; rf2-8mxnnk: normalize an EXPLICIT `:frame` target (frame VALUE or
         ;; frame-id keyword) to its frame-id BEFORE the registry lookup /
         ;; vacate — mirroring the READ path. Without this a frame VALUE keys
         ;; the `@flows` lookup by the raw map, so the clear silently MISSES the
         ;; flow (registered / read under the frame-id) and leaves it live. The
         ;; scope-derived id is already a keyword; only the explicit override is
         ;; normalized.
         frame-id (or (some-> frame frame/frame-target->id)
                      (frame/require-current-frame!
                        :clear-flow
                        {:where    'rf/clear-flow
                         :event-id id}))
         ;; The flow lookup + `:output-path` capture happen INSIDE the
         ;; drain-lock, not before it. On the JVM a competing same-frame
         ;; same-id `reg-flow` replacement could otherwise win the lock after
         ;; a stale read, install a NEW `:output-path`, a drain could
         ;; materialise the replacement's new output, and this clear-flow
         ;; would run under the lock using the OLD path — vacating a now-empty
         ;; old path (a no-op) while removing the live registry row and
         ;; leaving the replacement's new output stale in app-db (violating
         ;; Spec 013's clear-flow cleanup contract), and emitting a misleading
         ;; `:rf.flow/cleared` for the old path.
         ;;
         ;; So the lookup, path capture, app-db vacate, registry removal, and
         ;; dirty-row drop fold into ONE serialized operation over the SAME live
         ;; flow definition. The thunk returns the path it actually cleared (or
         ;; nil when no flow was registered under the lock) so the
         ;; `:rf.flow/cleared` emit below fires only on a real clear, with the
         ;; path captured under the lock.
         ;; Reentrant: a mid-drain `:rf.fx/clear-flow` runs directly inside
         ;; the single-drainer window (see `frame/call-serialized-with-drain!`).
         cleared-path
         (frame/call-serialized-with-drain!
           frame-id
           (fn []
             (when-let [flow (get-in @flows [frame-id id])]
               (let [path (:output-path flow)]
                 ;; Same in-drain hazard `reg-flow`'s `:output-path`-move
                 ;; branch guards against (see the comment above its
                 ;; `record-abandoned-output-path!` call): a `clear-flow`
                 ;; issued OUT of a drain can vacate `path` directly — there
                 ;; is no pending commit to clobber it. But a `clear-flow`
                 ;; issued IN a drain (reentrant from an event handler body /
                 ;; `:rf.fx/clear-flow`) races the SAME deferred `:db`
                 ;; commit: a direct write here would be overwritten by the
                 ;; handler's returned `:db` (which still carries the
                 ;; cleared flow's last value at `path`), resurrecting it.
                 ;; So RECORD the abandoned path instead; the current
                 ;; drain's `run-flows-on-db` dissocs it from the PENDING
                 ;; `:db` before the flow walk, riding the same value the
                 ;; deferred commit publishes.
                 (if (frame/in-drain? frame-id)
                   (record-abandoned-output-path! frame-id path)
                   (vacate-output-path! frame-id path))
                 ;; Spec 015 §7: drop this flow's output data-classification
                 ;; declarations from the frame's app-db elision registry so a
                 ;; deregistered flow leaves no orphaned redaction behind.
                 ;; Frame- / effect- / flow-sourced entries survive.
                 (clear-flow-output-marks! frame-id id)
                 ;; Drop the flow from `frame-id`'s per-frame slot; when that was
                 ;; the LAST flow on the frame, prune the now-empty `frame-id` key
                 ;; entirely rather than leave a `{frame-id {}}` husk a naive
                 ;; `flows-snapshot` consumer would iterate. Pruning keeps the
                 ;; registry exactly symmetric with `teardown-on-frame-destroy!`'s
                 ;; `(swap! flows dissoc frame-id)`.
                 (swap! flows (fn [m]
                                (let [m' (update m frame-id dissoc id)]
                                  (cond-> m'
                                    (empty? (get m' frame-id)) (dissoc frame-id)))))
                 ;; Drop the cleared flow's dirty-check row from THIS frame's own
                 ;; `last-inputs` container. Frame-local — a sibling frame
                 ;; registering the same id keeps its own row untouched.
                 (drop-frame-flow-row! frame-id id)
                 ;; SINGLE-STORE (rf2-en00bk): no registrar-slot realignment.
                 ;; The per-frame `flows` atom is the sole store — dropping this
                 ;; frame's entry above is the whole job. Any SURVIVING frame
                 ;; that registers the same id keeps its own authoritative entry
                 ;; in place; there is no frame-blind slot to re-point.
                 path))))]
     ;; Per Spec 009 §:op-type vocabulary: :rf.flow/cleared fires after
     ;; clear-flow has removed the flow from the per-frame registry
     ;; and dissoc-in'd its output path. Tools observe this to drop
     ;; their per-flow display state. Only emit when a flow was ACTUALLY
     ;; cleared (a no-op clear-flow for an unregistered id, or one that lost
     ;; the race to a competing lifecycle op, emits nothing), carrying the
     ;; path captured under the lock. The outer `debug-enabled?` gate matches
     ;; the hot-path emits in flows.cljc (per Spec 009 §Production builds,
     ;; "keep the gate OUTERMOST"); clear-flow is a cold path so the cost is
     ;; negligible, but the gate keeps the tag-map literal out of CLJS prod and
     ;; the convention uniform across every flow emit site.
     (when (and cleared-path interop/debug-enabled?)
       (trace/emit! :flow :rf.flow/cleared
                    {:flow-id id
                     :path    cleared-path
                     :frame   frame-id}))
     nil)))

;; ---- frame-destroy teardown ---------------------------------------------
;;
;; Symmetric with the machines `:teardown-on-frame-destroy!` hook. On
;; `destroy-frame!`, the flows registered against the destroyed frame and the
;; per-frame `last-inputs` rows MUST clear — otherwise SSR-style per-request
;; frame churn / pair-tool time-travel / `make-frame` ephemeral usage would
;; leak flow definitions and cached input vectors indefinitely. SINGLE-STORE
;; (rf2-en00bk): with the per-frame `flows` atom the sole store, this is
;; PURELY dropping the destroyed frame's per-frame entries — there is no
;; frame-blind registrar `:flow` slot to realign to a surviving owner.

(defn teardown-on-frame-destroy!
  "Drop every per-frame entry the flows artefact holds against `frame-id`:

   1. Dissoc `frame-id` from the per-frame flow registry (`flows`) — the
      SOLE authoritative store, so this is the whole job for the flow
      definitions.
   2. Dissoc the destroyed frame's `last-inputs` container from the
      per-frame `frame-last-inputs` registry — one step, since per-frame
      storage holds the destroyed frame's rows in its own inner atom and
      removing the frame-keyed slot drops them all.
   3. Dissoc the destroyed frame's pending abandoned-output-paths container
      (same per-frame storage idiom).

   SINGLE-STORE (rf2-en00bk): NO registrar-slot prune. The old double-store
   kept a frame-blind registrar `:flow` slot that had to be unregistered (last
   owner) or re-pointed to a surviving owner (the `realign-registrar-owner!`
   workaround) on frame-destroy; that slot and workaround are GONE. A surviving
   frame registering the same id keeps its own authoritative entry in the
   per-frame `flows` atom in place — nothing to realign.

   NO explicit flow-output elision-mark scrub. Unlike `clear-flow` — which
   removes ONE flow's `:source :flow` declarations while its frame lives on
   (hence its `clear-flow-output-marks!` call) — frame-destroy drops the
   WHOLE frame. The elision registry lives in the frame's runtime-db
   partition at `[:rf.runtime/elision]` (`re-frame.elision` §registry-of),
   INSIDE the one physical `:frame-state` container held under the frame
   record; `destroy-frame!` step 6 (`frame.cljc` §dissoc-frame!)
   `(swap! frames dissoc id)` drops that whole record — container,
   runtime-db, and every `:source :flow` declaration — so the marks are gone
   with the frame and a per-flow scrub here would be redundant work over
   about-to-be-GC'd state. The teardown hook runs BEFORE step 6, but that
   ordering is moot: nothing observes the (still-live) elision slot between
   the hook and the dissoc, and a reused frame-id gets a FRESH empty
   container (`frame.cljc` §new-frame-record), so a destroyed/reused frame
   can never observe a prior incarnation's stale flow-sourced declaration.
   Pinned by
   `flows_destroy_frame_teardown_test` §destroy-frame-drops-flow-output-marks.

   Idempotent against a frame the registry never recorded (a frame
   destroy before any `reg-flow`). Published via the
   `:flows/teardown-on-frame-destroy!` late-bind hook so
   `frame/destroy-frame!` reaches it without statically requiring the
   flows artefact."
  [frame-id]
  (when frame-id
    (swap! flows dissoc frame-id)
    ;; Drop the destroyed frame's entire `last-inputs` container in one
    ;; step — per-frame storage means the destroyed frame's rows ARE its
    ;; inner atom, so removing the frame-keyed slot drops every row at once
    ;; and cannot touch any sibling frame's container.
    (swap! frame-last-inputs dissoc frame-id)
    ;; Drop the destroyed frame's pending abandoned-output-paths container
    ;; too (same per-frame storage idiom). The frame is gone, so any
    ;; recorded-but-undrained path move is moot.
    (swap! frame-abandoned-output-paths dissoc frame-id))
  nil)

;; ---- hot-reload invalidation --------------------------------------------
;;
;; Per Spec 001 §Hot-reload semantics + Spec 013 §Re-registration: when a flow
;; re-registers against a frame, that frame's `:last-inputs` row MUST clear so
;; the new flow re-evaluates on the next drain regardless of whether inputs
;; changed (a hot-reloaded `:derive` with identical recent inputs would
;; otherwise keep serving the previous result), and the
;; `:rf.registry/handler-replaced` hot-reload trace MUST fire (dedup-by-shape).
;;
;; SINGLE-STORE (rf2-en00bk): both effects are now driven DIRECTLY by `reg-flow`
;; on a same-frame replacement (`prior-on-frame` non-nil), keyed on the frame
;; already in hand — see the `drop-frame-flow-row!` + `:rf.registry/handler-
;; replaced` emit in `reg-flow`. The former registrar replacement-hook
;; (`invalidate-flow-on-replace!`, which recovered the frame from the registrar
;; metadata's `:frame` slot) is GONE along with the registrar `:flow` write that
;; fired it.

;; ---- test-only resets ----------------------------------------------------

(defn reset-last-inputs!
  "Test-only: clear ALL per-frame dirty-check `last-inputs` containers
  (drops the whole `frame-last-inputs` registry, discarding every frame's
  inner atom). The flows reset-runtime fixture uses this to drop stale
  per-flow state between tests so re-registration does not silently no-op
  when new-inputs =-equal a stale entry from a sibling test. Published
  through the late-bind hook table so `re-frame.test-support`'s
  reset-runtime fixture can call it without statically requiring
  `re-frame.flows`."
  []
  (reset! frame-last-inputs {})
  nil)

(defn reset-flows!
  "Test-only: clear the per-frame flow registry AND the paired
  dirty-check `last-inputs` map. Exposed via the late-bind hook table so
  `re-frame.test-support` can reset state without a static require on this
  namespace.

  Resets BOTH atoms in lockstep: a test fixture / re-frame2-pair / Xray
  harness calling `reset-flows!` standalone wants ALL flow state cleared, and
  `last-inputs` is downstream cache for the same registry — leaving it
  standing would silently no-op the first evaluation after a re-registration
  when new-inputs `=`-equal a leftover entry. The dirty-check reset drops
  every per-frame `last-inputs` container (the `frame-last-inputs` registry).

  ALSO drops every per-frame pending abandoned-output-paths container — a
  `reset-flows!` caller wants ALL flow-derived per-frame state cleared, and a
  leftover undrained path move from a sibling test must not leak into the
  next."
  []
  (reset! flows {})
  (reset! frame-last-inputs {})
  (reset! frame-abandoned-output-paths {})
  nil)
