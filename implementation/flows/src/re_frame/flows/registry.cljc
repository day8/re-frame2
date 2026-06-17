(ns re-frame.flows.registry
  "Per-frame flow registry — owns the `flows` registry atom and the
  PER-FRAME `last-inputs` dirty-check containers, flow-map validation,
  registration (`reg-flow` / `clear-flow`), and the registrar
  replacement-hook that invalidates the dirty-check on hot reload.

  Per Spec 013 flows are FRAME-SCOPED: same flow-id can register against
  two frames with different `:inputs` / `:output` / `:path`, and
  undo / time-travel semantics belong to one frame's history. The
  registry shape is `{frame-id {flow-id flow-map}}`.

  Dirty-check storage is PER-FRAME by construction (rf2-94ol5). Each
  frame owns its own `last-inputs` container (`atom {flow-id inputs}`),
  held in the `frame-last-inputs` registry keyed by frame-id. A frame's
  drain reads and writes ONLY its own atom; the failed-flow rollback
  snapshots and restores ONLY the draining frame's atom. Cross-frame
  interference during a concurrent drain is therefore impossible by
  construction — a frame can no more touch a sibling's dirty-check rows
  than it can touch a sibling's app-db. (The prior single global atom
  keyed `{flow-id {frame-id inputs}}` made the wholesale-snapshot
  rollback over-broad: on a throw it reverted EVERY frame's rows, which
  on the JVM could clobber a concurrently-draining sibling's just-
  advanced rows. Mike ruled the structural per-frame-atom fix B over the
  minimal per-frame-slice scoping A — 2026-06-01.)

  Per rf2-mnu8z this is the second leg of the flows split. The façade
  (`re-frame.flows`) re-exports the public surface — `reg-flow`,
  `clear-flow`, `reset-flows!`, `reset-last-inputs!`, plus the
  rf2-4gvb4 read accessors `flows-snapshot` / `last-inputs-snapshot`.
  The underlying atoms themselves are PRIVATE to this artefact: external
  consumers (production code, the late-bind directory, test fixtures
  across artefacts) reach the registry state through the accessor seam
  rather than dereferencing the atom Vars directly. `last-inputs-snapshot`
  re-aggregates the per-frame atoms back into the canonical
  `{flow-id {frame-id inputs}}` observation shape so its public contract
  is unchanged across the storage restructure."
  (:require [re-frame.elision :as elision]
            [re-frame.error :as error]
            [re-frame.flows.topo :as topo]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.marks :as marks]
            [re-frame.path :as path]
            [re-frame.registrar :as registrar]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

;; ---- state ---------------------------------------------------------------
;;
;; Per rf2-4gvb4 — the atoms below are PRIVATE to this artefact. External
;; consumers reach the registry state through the public read accessors
;; (`flows-snapshot` / `last-inputs-snapshot`) and the reset fns
;; (`reset-flows!` / `reset-last-inputs!`) — the facade re-exports them at
;; `re-frame.flows`. The atoms themselves are NOT a public surface; treating
;; them as one couples consumers to the internal shape (the per-frame
;; container restructure below would otherwise force every test fixture to
;; follow). The accessor seam keeps the flexibility on the framework side
;; while giving tests the read-and-reset affordances they actually need.

(defonce
  ^{:doc     "frame-id → flow-id → flow-map. Per-frame so undo / time-travel
              / clear semantics are unambiguous."
    :private true}
  flows
  (atom {}))

;; Per rf2-94ol5 — PER-FRAME dirty-check storage. `frame-last-inputs` is a
;; registry mapping `frame-id → (atom {flow-id last-seen-input-vec})`: each
;; frame owns its OWN inner atom of dirty-check rows, mirroring how every
;; other per-frame runtime cell (app-db container, router queue, sub-cache,
;; drain-lock — see `re-frame.frame/new-frame-record`) is held independently
;; per frame.
;;
;; This is the structural fix (Mike ruled B 2026-06-01): a frame's drain
;; reads / writes ONLY `(get @frame-last-inputs frame-id)`, and the failed-
;; flow rollback in `re-frame.flows/run-flows-on-db` snapshots / restores
;; ONLY that one atom. A frame can no more clobber a sibling's dirty-check
;; rows than it can clobber a sibling's app-db — cross-frame interference is
;; impossible BY CONSTRUCTION, not merely avoided. The prior single global
;; atom keyed `{flow-id {frame-id inputs}}` made the wholesale-snapshot
;; rollback over-broad: on a throw it `reset!`-reverted EVERY frame's rows,
;; so on the JVM a concurrently-draining sibling's just-advanced rows were
;; clobbered (spurious recompute + sub-invalidation storm; violated the
;; documented per-frame-independence invariant).
;;
;; The outer `frame-last-inputs` atom is mutated only on frame-slot
;; lifecycle (first touch creates the inner atom; teardown / reset removes
;; it). The hot per-drain path mutates the INNER atom in place — so the
;; per-frame container identity is stable across a frame's lifetime and the
;; reader / writer never contend on the outer registry.
(defonce
  ^{:doc     "frame-id → (atom {flow-id last-seen-input-vec}). Per-frame
              dirty-check containers (rf2-94ol5). Each frame's inner atom is
              read / written only by that frame's drain; the failed-flow
              rollback restores only the draining frame's own atom."
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

;; ---- public read accessors (rf2-4gvb4) -----------------------------------
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

(defn ^:no-doc last-inputs-snapshot
  "Return the dirty-check value re-aggregated to the canonical observation
  shape `{flow-id {frame-id inputs}}`. Reads every per-frame container
  (rf2-94ol5 storage) and inverts the `{frame-id {flow-id inputs}}` layout
  back to `flow-id`-outer so the public contract is unchanged across the
  per-frame restructure. Empty per-frame containers contribute nothing, so
  a frame whose dirty-check rows all cleared leaves no key behind.
  Snapshot — observers MUST NOT mutate (the underlying atoms are private).

  RAW — NOT an egress boundary (EP-0015). The cached input vectors are the
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

;; ---- intra-artefact-only mutation helpers (rf2-4gvb4 / rf2-94ol5) --------
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
  different atom and is untouched (rf2-94ol5)."
  [frame-id prior]
  (reset! (ensure-frame-last-inputs-atom! frame-id) prior))

;; ---- last-inputs row maintenance (rf2-94ol5) -----------------------------
;;
;; With per-frame `last-inputs` containers, dropping one flow's dirty-check
;; row for a frame is a plain `dissoc` on that frame's own inner atom — no
;; two-level-map walk, and structurally incapable of touching a sibling
;; frame's container. The clear-flow / frame-destroy / hot-reload-invalidate
;; paths share this one helper so the "drop this flow's row for this frame"
;; invariant lives in exactly one place. (The prior `prune-frame-row`
;; maintained the global `{flow-id {frame-id v}}` map's inner-map emptiness;
;; per-frame storage makes the dissoc unconditional and frame-local.)

(defn- drop-frame-flow-row!
  "Drop `flow-id`'s dirty-check row from `frame-id`'s `last-inputs`
  container. No-op when the frame has no container. Frame-local by
  construction (rf2-94ol5)."
  [frame-id flow-id]
  (when-let [a (get @frame-last-inputs frame-id)]
    (swap! a dissoc flow-id)))

(defn- owning-frames
  "The frame-ids in the per-frame `flows` map (`{frame-id {flow-id
  flow-map}}`) that still register `flow-id`. Empty when the
  destroyed/cleared frame was the last owner and the shared `:flow`
  registrar slot can be released; otherwise names the surviving owners
  `realign-registrar-owner!` re-points the slot to. O(F) over frame
  count (typically 1-3 at v1).

  Cost note: if a profile-driven hot path ever shows many-frame
  topologies stressing this, the optimisation is a reverse index
  `{flow-id #{frame-id ...}}` maintained by `reg-flow` / `clear-flow`,
  making the check O(1). Deferred until measurement warrants the extra
  atom."
  [flows-map flow-id]
  (filterv #(contains? (get flows-map %) flow-id) (keys flows-map)))

;; ---- validation ----------------------------------------------------------
;;
;; Per Spec 013 §Flow shape: `:inputs` is a vector of app-db paths, `:path`
;; is an app-db path. A path is a non-empty vector of scalar map keys. The
;; prior validator only enforced `vector?` on each, so three classes of
;; malformed input slipped through (per audit rf2-o3hok findings Q5 / TE4):
;;
;;   - `:inputs [:foo :bar]` (vector of bare keywords) — passed; then
;;     topo's `prefix?` threw on `(count :foo)`.
;;   - `:inputs [[:foo] :bar]` (mixed) — same path, same delayed boom.
;;   - `:path []` — passed; `(prefix? [] anything)` is true, so the empty-
;;     path flow silently became a depends-on prerequisite of EVERY other
;;     flow in the frame (per Spec 013 §Dependency rule).
;;
;; The tightened validator rejects each malformation up front with a stable
;; error id and ex-data that names the offending entries / elements so
;; callers don't have to chase the failure into the topo / evaluator stack.
;;
;; The OPTIONAL output data-classification keys (`:sensitive` / `:large` /
;; `:rf.egress/output-sensitivity` / `:large?`, Spec 015 §Derived sensitivity)
;; are validated in the same table (rf2-cgk0wb): previously they were
;; FAIL-OPEN — a malformed declaration (`:sensitive [:token]`, `:large "blob"`,
;; a typo'd enum value) registered cleanly but installed no redaction, the
;; worst failure for a safety feature. The boolean `:sensitive?` declassify
;; spelling is now REJECTED (EP-0015 issue 9; Spec 015:425).

(defn- valid-path-element?
  "True iff `x` is admissible as a flow path segment — the SHARED EP-0012
  concrete-segment domain (`re-frame.path/segment?`, Conventions §Segment
  domain), no longer a flows-private enumeration (rf2-t3cfil). The shared
  domain is a keyword / string / symbol / boolean / integer / UUID / instant
  / nil; composites (vectors / maps / sets / seqs) and host handles are
  rejected. Collections are never the right value for a `get-in` path step
  and almost always indicate a caller bug (e.g. passing a bare keyword where
  a vector-of-paths was expected, then wrapping it one level too many).

  Flows DELEGATE the membership question to the shared policy rather than
  re-deriving it: per the EP-0012 disposition-1 graduation note, a subsystem
  narrows from the shared upper bound but never re-enumerates it. This widens
  the prior flows enumeration (keyword / string / integer / symbol / boolean)
  to also admit UUID, instant, and nil segments — all valid associative keys
  the shared `:rf/path` algebra already focuses through. The flows-SPECIFIC
  restriction (a `:path` / `:inputs` path must be NON-EMPTY — an empty path is
  a root-output footgun that makes the flow a depends-on prerequisite of every
  other flow, Spec 013 §Dependency rule) is layered ON TOP in `valid-path?`
  below, NOT folded into the shared segment domain."
  [x]
  (path/segment? x))

(defn- valid-path?
  "A flow `:inputs` path or the flow `:path` is a NON-EMPTY vector of valid
  path segments. The non-emptiness is the flows-specific root-output policy
  (Spec 013 §Dependency rule — an empty path overlaps every path); the
  per-element domain is the shared EP-0012 segment policy (`valid-path-element?`
  → `re-frame.path/segment?`, rf2-t3cfil)."
  [x]
  (and (vector? x) (seq x) (every? valid-path-element? x)))

(defn- valid-output-subpath?
  "A flow output classification subpath (an entry of `:sensitive` / `:large`)
  is a vector of valid path segments, AND — unlike a flow `:inputs` path or
  the flow `:path` — the EMPTY vector `[]` is legal: it marks the whole
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
;; predicate — matching the original `cond` evaluation order so existing
;; tests pinning rejection ids see no shift.
;;
;; Data-driven so the rules are introspectable (a test or the spec can
;; read the table) and adding a clause is a single conj.
(def ^:private validation-rules
  [{:pred     (fn [flow] (some? (:id flow)))
    :error-kw :rf.error/flow-missing-id
    :reason   ":id is required (flow registration must name an id)"}

   ;; rf2-ihfz9o issue 2: a PRESENT `:id` must be a keyword. Spec-Schemas
   ;; §FlowMeta requires `[:id :keyword]` and Spec 013 §The registration
   ;; shape describes flow ids as namespaced feature identifiers; the
   ;; public examples are all keywords, and the `:flow-id` slot is emitted
   ;; unchanged into `:rf.flow/*` trace + error payloads (re-frame.flows
   ;; :274/:315), so a string / number / map id violates the public
   ;; schema contract and leaks an arbitrary id shape downstream. Rejecting
   ;; at the API boundary (pre-alpha — resolve here, not by normalising in
   ;; every consumer) is the masterpiece choice. Distinct from the
   ;; absent-id case above (`flow-missing-id` fires first on nil); this
   ;; rule fires only when an id is present but the wrong type — a fifth
   ;; member of the `:rf.error/flow-bad-*` family alongside bad-inputs /
   ;; bad-output / bad-path.
   {:pred     (fn [flow] (keyword? (:id flow)))
    :error-kw :rf.error/flow-bad-id
    :reason   ":id must be a keyword (flow ids are namespaced feature identifiers; the public FlowMeta schema requires :keyword and the :flow-id trace/error slot carries it unchanged)"}

   {:pred     (fn [flow] (vector? (:inputs flow)))
    :error-kw :rf.error/flow-bad-inputs
    :reason   ":inputs must be a vector of paths"}

   ;; One clause for both "entry isn't a vector" and "entry isn't a valid
   ;; path" — `valid-path?` already requires `vector?`, so the older
   ;; two-arm split (the prior code carried a separate `(every? vector?
   ;; ...)` check) was strictly subsumed by this one. The single rejection
   ;; message names what the entry must be; the `:bad-entries` slot points
   ;; at the offending values so callers can fix them without a stack-trace
   ;; dig.
   {:pred     (fn [flow] (every? valid-path? (:inputs flow)))
    :error-kw :rf.error/flow-bad-inputs
    :reason   ":inputs entries must each be a non-empty vector of path segments (the shared EP-0012 segment domain: keyword / string / symbol / boolean / integer / UUID / instant / nil)"
    :extras   (fn [flow] {:bad-entries (vec (remove valid-path? (:inputs flow)))})}

   {:pred     (fn [flow] (fn? (:output flow)))
    :error-kw :rf.error/flow-bad-output
    :reason   ":output must be a fn"}

   {:pred     (fn [flow] (vector? (:path flow)))
    :error-kw :rf.error/flow-bad-path
    :reason   ":path must be a vector"}

   {:pred     (fn [flow] (seq (:path flow)))
    :error-kw :rf.error/flow-bad-path
    :reason   ":path must be non-empty (an empty :path would make this flow a depends-on prerequisite of every other flow per Spec 013 §Dependency rule)"}

   {:pred     (fn [flow] (every? valid-path-element? (:path flow)))
    :error-kw :rf.error/flow-bad-path
    :reason   ":path elements must each be a path segment (the shared EP-0012 segment domain: keyword / string / symbol / boolean / integer / UUID / instant / nil)"
    :extras   (fn [flow] {:bad-elements (vec (remove valid-path-element? (:path flow)))})}

   ;; rf2-cgk0wb issue 2: the OPTIONAL output data-classification keys
   ;; (`:sensitive` / `:large` per-path vectors; `:sensitive?` / `:large?`
   ;; whole-output booleans) were FAIL-OPEN. `explicit-flow-output-mark-paths`
   ;; silently `(filter vector?)`-dropped malformed `:sensitive` / `:large`
   ;; entries and only honoured a LITERAL `true` for `:sensitive?` / `:large?`,
   ;; so a typo (`:sensitive [:token]`, `:large "blob"`, `:sensitive? :yes`,
   ;; `:large? 1`) registered with a normal return value and NO diagnostic
   ;; while the intended redaction / large-elision silently never happened.
   ;; That is the worst failure mode for a SAFETY feature: the author believes
   ;; a slot is protected and it is not. Reject at the API boundary instead —
   ;; same fail-FAST posture the core flow-path validation already takes, and
   ;; the same `:rf.error/flow-bad-*` family. These rules fire AFTER the core
   ;; `:id` / `:inputs` / `:output` / `:path` rules (a flow with a broken
   ;; required shape reports that first), but BEFORE any registry / app-db /
   ;; elision-declaration state mutates (validate-flow is the first call in
   ;; `reg-flow`, before `frame-id` / the `swap!`), so a rejected registration
   ;; installs NO flow row and NO elision declaration.
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

   ;; Derived-output sensitivity (EP-0015 issue 9): a flow declares its
   ;; output's sensitivity with the closed `:rf.egress/output-sensitivity`
   ;; enum (`:rf.egress/inherit` default | `:rf.egress/sensitive` force |
   ;; `:rf.egress/public` declassify) — NOT the rejected boolean `:sensitive?`
   ;; overload (Spec 015:425). REJECT a `:sensitive?` key with a recovery
   ;; hint naming the enum; an unknown enum value is fail-closed (a typo is a
   ;; loud error, never a silent permissive inherit).
   {:pred     (fn [flow] (not (contains? flow :sensitive?)))
    :error-kw :rf.error/flow-bad-marks
    :reason   (str "the boolean :sensitive? declassify/force spelling is rejected on a "
                   "flow output (Spec 015) — declare derived-output sensitivity with "
                   ":rf.egress/output-sensitivity, whose closed value set is "
                   (pr-str marks/output-sensitivity-values)
                   " (:rf.egress/public to declassify, :rf.egress/sensitive to force-mark, "
                   ":rf.egress/inherit — the default — to inherit from inputs)")
    :extras   (fn [flow] {:bad-key :sensitive?
                          :bad-value (:sensitive? flow)
                          :use :rf.egress/output-sensitivity})}

   {:pred     (fn [flow] (or (not (contains? flow :rf.egress/output-sensitivity))
                             (contains? marks/output-sensitivity-values
                                        (:rf.egress/output-sensitivity flow))))
    :error-kw :rf.error/flow-bad-marks
    :reason   (str ":rf.egress/output-sensitivity, when present, must be one of "
                   (pr-str marks/output-sensitivity-values)
                   " — :rf.egress/inherit (default) inherits from inputs, "
                   ":rf.egress/sensitive force-marks, :rf.egress/public declassifies")
    :extras   (fn [flow] {:bad-key   :rf.egress/output-sensitivity
                          :bad-value (:rf.egress/output-sensitivity flow)
                          :valid     marks/output-sensitivity-values})}

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

;; ---- Spec 015 §7 flow-output data classification (rf2-ouemt / rf2-ihfz9o) --
;;
;; A `reg-flow` registration may carry data-classification keys describing
;; the SENSITIVITY / SIZE of the flow's OWN OUTPUT value:
;;
;;   :rf.egress/output-sensitivity — the closed derived-output declassification
;;        enum (EP-0015 issue 9): :rf.egress/inherit (default) | :rf.egress/sensitive
;;        (force the whole output sensitive) | :rf.egress/public (declassify)
;;   :large?     true   — the whole output is large
;;   :sensitive  [paths]— per-output-path sensitive sub-slots ([[]] = whole)
;;   :large      [paths]— per-output-path large sub-slots ([[]] = whole)
;;
;; PLUS — per Spec 015:313 and the rf2-ihfz9o ruling (Mike (a) PROPAGATE,
;; 2026-06-09) — a flow OUTPUT inherits the data-classification of its
;; INPUT paths. A flow reading a sensitive app-db (or runtime-db-qualified,
;; rf2-4eisfr) input emits a sensitive output unless the author explicitly
;; declassifies with `:rf.egress/output-sensitivity :rf.egress/public` (the
;; rejected `:sensitive? false` spelling is gone — Spec 015:425). This is the
;; same propagation/override grammar subscriptions already implement
;; (`marks/resolve-sub-output-marks`) — flows just need their OWN trigger
;; because a flow does not recompute on a mark-only change (subs re-resolve on
;; every compute pass). Propagation is FAIL-CLOSED: an over-redacted derived
;; value is visibly wrong in dev; an under-redacted one is an invisible privacy
;; leak. Format-preserving derivations (copy / reformat / concat — e.g. 015's
;; :computed/full-name) propagate by default; de-sensitising derivations (hash /
;; mask / aggregate) declassify with `:rf.egress/output-sensitivity
;; :rf.egress/public` (015's :computed/hashed-token).
;;
;; :large is ASYMMETRIC and is NOT auto-propagated (Mike's lean, rf2-ihfz9o
;; OPEN PRECISION, settled here in impl): :sensitive is TRANSITIVE (derived-
;; from-sensitive is sensitive), but a flow usually SHRINKS a large input
;; (count / summary / first-N), so :large-by-default would over-elide the
;; common case and force `:large? false` everywhere. The consequence
;; asymmetry confirms it — under-redact-sensitive is a LEAK; mis-:large is
;; mild either way. So :large marks come ONLY from explicit flow declarations
;; (`:large? true` / `:large [paths]`); :sensitive marks come from explicit
;; declarations UNION propagated input marks.
;;
;; The flow's output lands in app-db at `(:path flow)`, so a sensitive /
;; large output value egresses through TWO observation channels:
;;   1. the `:rf.flow/computed` `:result` / `:before` trace slots (and the
;;      `:rf.flow/failed` failure path), and
;;   2. the app-db destination slot itself — visible to App-DB-Diff style
;;      observation, the `:rf.event/db` pending-db trace (the t2
;;      `:rf.event/db-pending-post-flow` stamp — Spec 015:568), view
;;      render-arg egress, and any downstream sub reading the slot.
;;
;; Rather than projecting the registration marks at each flow emit site (a
;; flow-id→marks table is wrong for flows — Spec 013 lets the SAME flow-id
;; carry DIFFERENT definitions, hence different marks, in different frames),
;; we make the marks FIRST-CLASS through the SAME per-frame elision registry
;; the schema-first wire walker (`elision/elide-wire-value`) already reads.
;; We translate the registration's output-rooted marks (explicit + propagated)
;; into ABSOLUTE app-db declarations rooted at `(:path flow)` and write them
;; into the frame's `[:rf.runtime/elision :sensitive-declarations]` /
;; `:declarations` slots.
;;
;; ONE walker then covers BOTH channels:
;;   - the flow trace `:result` / `:before` already ride
;;     `elide-wire-value` rooted at `(:path flow)` (see `re-frame.flows`),
;;     so they pick the declarations up with no emit-site change; and
;;   - `project-db-tags` / `project-view-rendered-tags` (re-frame.marks)
;;     elide the app-db destination slot through the SAME registry, so the
;;     db-diff / pending-db / view egress paths redact it too.
;;
;; THE PROPAGATION TRIGGER (rf2-ihfz9o IMPLEMENTATION SHAPE): the input-path
;; marks a flow inherits arrive on the frame's elision registry AFTER the
;; flow registers (`add-marks` / `set-marks` / schema population / an upstream
;; flow's propagated mark). A flow does NOT recompute on a mark-only change,
;; so it needs a MARK-MUTATION-aware refresh. We resolve at TWO points,
;; FRAME-SCOPED and TOPO-ORDERED:
;;   1. at `reg-flow` time (initial install — covers add-marks-then-reg-flow),
;;   2. at the START of every `run-flows-on-db` drain, walking the frame's
;;      flows in dependency order and re-deriving each flow's `:source :flow`
;;      output declarations (covers marks added after registration AND the
;;      flow-DAG case — flow B reading flow A's `:path` inherits A's already-
;;      refreshed propagated mark because A is refreshed first in topo order).
;; The drain refresh is the flows analogue of subs' per-compute re-resolution;
;; it stays within the flows artefact (no add-marks/set-marks hook into core).
;;
;; Frame-scoping is structural: the declarations live in the FRAME's own
;; app-db elision registry, so the same flow-id registered against two
;; frames with different marks writes into two different registries — no
;; cross-frame bleed. Entries are stamped `{:source :flow :flow-id <id>}`
;; so lifecycle cleanup (clear-flow / path-change re-register / frame
;; destroy) can drop exactly this flow's contributions while leaving
;; schema-sourced (`:source :schema`) and add-marks-sourced
;; (`:source :marks`) entries untouched. A flow's
;; `:rf.egress/output-sensitivity :rf.egress/public` declassify suppresses only
;; the FLOW's own propagated/whole mark — it can never unmark a schema- or
;; add-marks-sourced declaration on the same path (union semantics,
;; Spec 015:295).

(defn- explicit-flow-output-mark-paths
  "Translate a flow's EXPLICIT output-rooted classification keys into
  ABSOLUTE app-db declaration paths rooted at the flow's `:path`. Returns
  `[sensitive-paths large-paths]` (each a vector of absolute path vectors).
  This is the author's hand-declared set; the PROPAGATED whole-output
  sensitive mark (input-inherited) is layered on by the resolver below.

  - `:rf.egress/output-sensitivity :rf.egress/sensitive` ⇒ the flow's
    `:path` itself (force-mark the whole output sensitive); `:large? true`
    ⇒ the `:path` itself (force-mark large);
  - per-path `:sensitive` / `:large` entries ⇒ `(:path flow)` ++ sub-path
    (`[[]]` whole-value mark ⇒ the `:path` itself);
  - `:rf.egress/output-sensitivity :rf.egress/public` / `:large? false` ⇒ NO
    whole-output mark here (the `:public` declassify suppresses propagation
    too — see the resolver); per-path entries, if any, still apply."
  [flow]
  (let [base       (:path flow)
        abs        (fn [sub] (into (vec base) sub))
        per-sens   (->> (:sensitive flow) (filter vector?) (map abs))
        per-large  (->> (:large flow)     (filter vector?) (map abs))
        whole-sens (when (= :rf.egress/sensitive (:rf.egress/output-sensitivity flow))
                     [(vec base)])
        whole-lg   (when (true? (:large? flow))     [(vec base)])]
    [(vec (concat whole-sens per-sens))
     (vec (concat whole-lg   per-large))]))

(defn ^:no-doc input-resolve-path
  "Resolve one flow `:inputs` path to the absolute declaration-coordinate
  path the elision registry is keyed by (plain path vectors, partition-
  blind — `add-marks` / schema / a flow's own propagated mark all store
  bare path vectors). A bare input is an app-db path verbatim; a runtime-
  db-qualified input `[:rf.db/runtime …rest]` (rf2-4eisfr) drops the
  partition key so its `…rest` matches a declaration on that runtime-db
  slot (e.g. a sensitive route param / machine-data slot declared via
  `add-marks`). Partition-aware by construction — the SAME machinery, one
  pass, per the rf2-ihfz9o COMPOSE-WITH-rf2-4eisfr note.

  NOT private (rf2-p44r3u): `re-frame.flows/elide-inputs` reuses this SAME
  normalization when seeding `elision/elide-wire-value`'s `:path` opt for a
  flow's per-input trace value, so the `:rf.flow/computed` `:input-values`
  and `:rf.flow/failed` `:inputs` slots elide a runtime-qualified input
  against the STRIPPED declaration path — closing the input-value leak
  where the registry keyed the sensitive declaration at the stripped path
  but the trace seed carried the raw `[:rf.db/runtime …]` path and never
  matched it."
  [input-path]
  (if (= :rf.db/runtime (first input-path))
    (subvec (vec input-path) 1)
    (vec input-path)))

(defn- input-overlaps-declaration?
  "True iff ANY of the flow's resolved input paths overlaps ANY path in the
  `decls` declaration map (one a prefix of the other, either direction —
  `topo/output-paths-overlap?` is exactly this prefix test). An input that
  reads a sensitive slot, a parent of one, or a child of one all count: a
  flow reading `[:user]` when `[:user :ssn]` is sensitive may surface the
  ssn in its output, and a flow reading `[:user :ssn]` directly obviously
  does. Conservative (footgun-prevention, not security-grade taint — same
  posture as subs' layer-1 check), and fail-closed."
  [flow decls]
  (boolean
    (when (seq decls)
      (let [decl-paths (keys decls)]
        (some (fn [input-path]
                (let [p (input-resolve-path input-path)]
                  (some #(topo/output-paths-overlap? p %) decl-paths)))
              (:inputs flow))))))

(defn- flow-declares-marks?
  "True when the flow registration carries any output data-classification
  key. Used (alongside a non-nil prior registration) to gate the elision-
  registry write at `reg-flow` time. NOTE: propagation may install a mark
  even for a flow with no explicit classification key, so the drain-time
  refresh (`refresh-flow-output-declarations!`) ALWAYS runs for every
  registered flow regardless of this predicate — this gate only governs the
  reg-flow-time first-touch write."
  [flow]
  (or (contains? flow :sensitive)
      (contains? flow :large)
      (contains? flow :rf.egress/output-sensitivity)
      (contains? flow :large?)))

(defn- drop-flow-sourced
  "Remove every `{:source :flow :flow-id <flow-id>}` entry from a
  declaration map, preserving schema- and marks-sourced entries (and any
  OTHER flow's entries — disjoint output paths guarantee none overlap, but
  the `:flow-id` filter keeps the operation surgical regardless)."
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
  `{:source :flow :flow-id <flow-id>}` so lifecycle cleanup can find
  them. The value-shape is what `elision/elide-wire-value` reads: the
  sensitive table is membership-only, and the large table's entry rides
  as the `->marker` hint declaration (we carry no `:hint`, which the
  walker tolerates — `(:hint nil)` ⇒ no hint)."
  [existing paths flow-id]
  (reduce (fn [acc path]
            (assoc acc (vec path) {:source :flow :flow-id flow-id}))
          (or existing {})
          paths))

(defn- resolve-flow-sensitive-paths
  "Resolve `flow`'s absolute sensitive output declaration paths against the
  CURRENT registry `reg` (after THIS flow's own `:source :flow` entries have
  been dropped, so propagation never feeds back on itself). The set is:

    explicit `:rf.egress/output-sensitivity :rf.egress/sensitive` (force) /
           `:sensitive [paths]` declarations
    UNION  the PROPAGATED whole-output mark — installed iff (a) the flow did
           not declassify with `:rf.egress/output-sensitivity :rf.egress/public`,
           and (b) some input path overlaps an existing sensitive declaration
           on the frame.

  `:rf.egress/output-sensitivity :rf.egress/public` is the DECLASSIFY claim
  (EP-0015 issue 9; replaces the rejected `:sensitive? false` spelling): it
  suppresses BOTH the whole-output force-mark and the propagated mark (per-path
  `:sensitive` entries, being explicit author intent on sub-slots, still
  apply). It cannot unmark a schema-/add-marks-sourced declaration on the same
  path — those entries are never `:source :flow`, so they survive the
  `drop-flow-sourced` carry and re-union at read time (Spec 015:295)."
  [flow reg]
  (let [base        (vec (:path flow))
        [explicit-s _] (explicit-flow-output-mark-paths flow)
        opted-out?  (= :rf.egress/public (:rf.egress/output-sensitivity flow))
        ;; Propagate against the carried (non-flow) declarations PLUS any
        ;; OTHER flow's already-resolved sensitive declarations — so a flow
        ;; reading an upstream flow's sensitive `:path` inherits it (the
        ;; topo-ordered drain refresh guarantees the upstream is resolved
        ;; first). The carried map here already excludes THIS flow.
        propagate?  (and (not opted-out?)
                         (input-overlaps-declaration?
                           flow (get reg :sensitive-declarations)))
        propagated  (when propagate? [base])]
    (vec (distinct (concat explicit-s propagated)))))

(defn- fold-flow-declarations
  "Fold ONE flow's `:source :flow` output declarations into the registry
  `reg` (pure). Drops THIS flow's prior `:source :flow` entries first (so a
  re-registration / mark-mutation refresh replaces cleanly), then overlays
  the freshly-resolved absolute declarations. The sensitive set is EXPLICIT
  ∪ PROPAGATED (input-inherited, fail-closed) per Spec 015:313 + the
  rf2-ihfz9o ruling; the large set is EXPLICIT-ONLY (:large is not auto-
  propagated — the asymmetry settled in this PR). Resolving the propagation
  against the CARRY (this-flow-dropped) registry means a flow never inherits
  from its own prior mark (idempotence + no self-loop), and — because the
  caller folds flows in topo order — a flow reading an upstream flow's `:path`
  resolves against the upstream's already-folded propagated mark."
  [reg flow]
  (let [flow-id        (:id flow)
        [_ explicit-l] (explicit-flow-output-mark-paths flow)
        carry-s        (drop-flow-sourced (get reg :sensitive-declarations) flow-id)
        carry-l        (drop-flow-sourced (get reg :declarations) flow-id)
        sens-abs       (resolve-flow-sensitive-paths flow {:sensitive-declarations carry-s})
        new-s          (assoc-flow-paths carry-s sens-abs flow-id)
        new-l          (assoc-flow-paths carry-l explicit-l flow-id)]
    (cond-> (or reg {})
      (seq new-s)    (assoc :sensitive-declarations new-s)
      (empty? new-s) (dissoc :sensitive-declarations)
      (seq new-l)    (assoc :declarations new-l)
      (empty? new-l) (dissoc :declarations))))

(defn- write-flow-output-marks!
  "Install (or refresh) a SINGLE `flow`'s output marks into `frame-id`'s
  app-db elision registry (the `reg-flow`-time path). Delegates the
  resolution to `fold-flow-declarations`; shares `swap-elision-slot!` with
  `re-frame.marks` so the runtime-prune semantics stay uniform."
  [frame-id flow]
  (elision/swap-elision-slot! frame-id
    (fn [reg] (fold-flow-declarations reg flow))))

(defn refresh-flow-output-declarations!
  "Re-derive EVERY flow's `:source :flow` output declarations for `frame-id`,
  walking the frame's flows in TOPOLOGICAL (dependency) order so a flow that
  reads an upstream flow's `:path` inherits the upstream's just-refreshed
  propagated mark (rf2-ihfz9o). Frame-scoped. Called at the START of
  `re-frame.flows/run-flows-on-db` (before any flow computes, so the refreshed
  declaration is in the registry when the t2 `:rf.event/db-pending-post-flow`
  trace and the `:rf.flow/computed` `:result` slot project — Spec 015:568) —
  the MARK-MUTATION-aware refresh flows need because a flow does not recompute
  on a mark-only `add-marks` / `set-marks` / schema change (subs re-resolve on
  every compute pass; flows do not).

  Read-resolve-write-IF-CHANGED: reads the frame's two declaration sub-maps,
  folds all flows through them in one pure pass, and calls the runtime-db
  write surface ONLY when the resolved declarations actually differ — so a
  frame whose flows declare and inherit nothing (or whose declarations are
  already settled, the steady state after the first event) pays a pure-data
  fold and NO runtime-db write / reactive churn on the per-event hot path."
  [frame-id]
  (when-let [flow-map (get @flows frame-id)]
    (when (seq flow-map)
      (let [ordered (topo/topo-sort flow-map)
            ;; Current declaration sub-maps (the only two slots flows touch).
            cur-s    (elision/sensitive-declarations frame-id)
            cur-l    (elision/declarations frame-id)
            reg0     (cond-> {}
                       (seq cur-s) (assoc :sensitive-declarations cur-s)
                       (seq cur-l) (assoc :declarations cur-l))
            reg'     (reduce (fn [acc flow-id]
                               (fold-flow-declarations acc (get flow-map flow-id)))
                             reg0
                             ordered)]
        ;; Only write when the fold changed something. The fold is pure, so
        ;; this comparison is exact — no spurious runtime-db replace / reactive
        ;; invalidation when the declarations are already settled.
        (when (not= reg0 reg')
          (elision/swap-elision-slot! frame-id
            (fn [reg]
              ;; Re-fold against the live `reg` inside the swap (it carries any
              ;; non-flow `:source :schema` / `:source :marks` entries reg0's
              ;; two-submap reconstruction already preserved, plus guards
              ;; against a concurrent mutation between the read and the swap).
              (reduce (fn [acc flow-id]
                        (fold-flow-declarations acc (get flow-map flow-id)))
                      reg
                      ordered))))))))

;; ---- flow output-declaration snapshot / rollback (rf2-gdzv6o) -------------
;;
;; `refresh-flow-output-declarations!` writes the frame's runtime-db elision
;; registry IMMEDIATELY (via `swap-elision-slot!`), BEFORE the flow walk in
;; `re-frame.flows/run-flows-on-db` runs. A flow throw is a PRE-INSTALL throw
;; that aborts the WHOLE event (Spec 013 §Failure semantics), so the pending
;; `:db` effect and the draining frame's `last-inputs` advances are discarded
;; — but the refresh's runtime-db write would SURVIVE, leaving the elision
;; declarations out of sync with the committed (rolled-back) frame state and
;; violating the all-or-nothing two-partition contract (Spec 013:284,288 —
;; a pre-install flow throw must leave BOTH app-db and runtime-db unchanged).
;;
;; These two helpers let the drain snapshot the frame's flow output-declaration
;; slots BEFORE the refresh and restore them EXACTLY on a throw — the precise
;; mirror of the `frame-last-inputs-snapshot` / `reset-frame-last-inputs-to!`
;; pair this same walk already uses for the dirty-check rollback. Frame-scoped:
;; both name `frame-id` and touch only that frame's runtime-db elision slot, so
;; a concurrently-draining sibling frame is structurally untouched (rf2-94ol5).

(defn ^:no-doc flow-output-declarations-snapshot
  "Snapshot `frame-id`'s flow-touchable elision declaration sub-maps — the
  two slots `refresh-flow-output-declarations!` (and `fold-flow-declarations`)
  ever mutate: `:sensitive-declarations` and `:declarations`. Returned as a
  plain map suitable for `restore-flow-output-declarations!`. The drain-start
  snapshot for the rf2-gdzv6o throw-path rollback."
  [frame-id]
  {:sensitive-declarations (elision/sensitive-declarations frame-id)
   :declarations           (elision/declarations frame-id)})

(defn ^:no-doc restore-flow-output-declarations!
  "Restore `frame-id`'s flow output-declaration slots to `prior` (a snapshot
  from `flow-output-declarations-snapshot`), rolling back any mark-mutation
  refresh that landed before an aborted flow walk. Writes the two declaration
  sub-maps back EXACTLY through `swap-elision-slot!` (pruning an emptied slot
  the same way `fold-flow-declarations` does), leaving any other elision-slot
  keys the refresh never touches unchanged. Frame-scoped (rf2-94ol5)."
  [frame-id prior]
  (let [prior-s (:sensitive-declarations prior)
        prior-l (:declarations prior)]
    (elision/swap-elision-slot! frame-id
      (fn [reg]
        (cond-> (or reg {})
          (seq prior-s)    (assoc :sensitive-declarations prior-s)
          (empty? prior-s) (dissoc :sensitive-declarations)
          (seq prior-l)    (assoc :declarations prior-l)
          (empty? prior-l) (dissoc :declarations))))))

(defn- clear-flow-output-marks!
  "Drop `flow-id`'s `:source :flow` declarations from `frame-id`'s elision
  registry. Called on `clear-flow` (`registry.cljc` §clear-flow) so a
  *single* deregistered flow leaves no orphaned redaction declaration behind
  while its frame and the frame's other flows live on. Schema- and
  marks-sourced entries survive.

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

;; `reg-flow`'s same-frame `:path`-change branch (rf2-73pi1) vacates the
;; OLD output path via `vacate-output-path!`, which is defined alongside
;; `clear-flow` further down (both share the app-db dissoc semantics).
;; Forward-declare it so the registration path can reference it without
;; reordering the large clear/teardown block above the hot registration
;; surface.
(declare vacate-output-path!)

(defn reg-flow
  "Register a flow against a frame. Per Spec 013 — flows are frame-
  scoped: their lifecycle, evaluation, undo / time-travel semantics
  all belong to one frame.

  Required keys on the flow map: :id :inputs :output :path.
  Optional: :doc :schema.

  EP-0002 — flows are CONTEXT-REQUIRED FRAME-LOCAL registration. The
  frame to register against is the explicit `:frame` opt (the *override*),
  else the carried-invariant scope chain via `frame/require-current-frame!`
  (a `with-frame` / frame-provider scope, or a frame `:on-create` hook). A
  `reg-flow` issued under no established scope and no explicit `:frame`
  raises the always-on `:rf.error/no-frame-context` (per Spec 002 §Frame
  target resolution) rather than silently registering against `:rf/default`
  — namespace-load time is not a reason to synthesise a default frame."
  ([flow] (reg-flow flow {}))
  ([flow {:keys [frame] :as _opts}]
   (validate-flow flow)
   (let [frame-id (or frame
                      (frame/require-current-frame!
                        :reg-flow
                        {:where    'rf/reg-flow
                         :event-id (:id flow)}))
         flow-id  (:id flow)]
     ;; rf2-zbxvqj: reject registration against a NON-LIVE frame BEFORE any
     ;; state mutates. `frame/frame` returns nil when the frame record is
     ;; absent (never registered, or `destroy-frame!`'s step-6 dissoc ran) OR
     ;; present-but-`:destroyed?` (post-step-3, pre-step-6). Either way the
     ;; frame is non-live and must not acquire flow state.
     ;;
     ;; The bug: `call-serialized-with-drain!` runs its thunk DIRECTLY for an
     ;; absent/destroyed frame (frame.cljc §call-serialized-with-drain!), so
     ;; without this guard the registration below unconditionally installed a
     ;; `flows[frame-id flow-id]` row, an elision declaration, and a `:flow`
     ;; registrar slot stamped with the dead `frame-id`. A later `reg-frame`
     ;; reusing that id then inherited the resurrected flow — breaking the
     ;; frame-destroy isolation contract (Spec 013 §destroy-frame! releases
     ;; every per-frame flow slot / last-inputs row / dead registrar entry).
     ;;
     ;; Pre-alpha contract (acceptance): REJECT with a stable structured
     ;; error category rather than create dormant state for a typo'd or
     ;; destroyed frame id. `clear-flow` keeps its permissive absent-frame
     ;; no-op (teardown must be idempotent); only this MUTATING path rejects.
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
     ;; ATOMIC check-and-insert (rf2-qxwib). The cycle check and the
     ;; commit are ONE `swap!` update fn over `@flows`: it reads the
     ;; frame's CURRENTLY-COMMITTED flow-map, runs topo-sort on the
     ;; prospective map, and — only if that passes — returns the map
     ;; with the new flow assoc'd in. `swap!` re-invokes the update fn
     ;; (re-reading committed state, re-running the cycle check) whenever
     ;; a concurrent writer wins the compare-and-set, so a cycle can
     ;; NEVER be admitted by interleaving.
     ;;
     ;; Pre-fix (rf2-7csri-era) this was check-THEN-act: it read
     ;; `@flows` once, built a prospective map, ran topo-sort, then
     ;; committed in a SEPARATE `swap!`. Two threads reg-flow'ing on the
     ;; same frame two flows that together form a cycle (T1: A reads B's
     ;; path; T2: B reads A's path) could each pass the check against the
     ;; OTHER's not-yet-committed flow (neither sees it), then both
     ;; commit — leaving a cyclic registry that throws on every drain.
     ;; Folding the validation into the update fn closes that TOCTOU.
     ;;
     ;; Single-threaded path is unchanged in cost: `swap!` invokes the
     ;; update fn exactly once with no contention, so this runs the same
     ;; single topo-sort the prior code did. Only contended same-frame
     ;; registration pays the retry (re-running the cheap Kahn sort over
     ;; a handful of nodes), and only then to preserve correctness.
     ;;
     ;; The cycle-check throw propagates OUT of `swap!`, so on rejection
     ;; the atom is left untouched and the prior registration survives —
     ;; preserving the rf2-7csri "a rejected REPLACEMENT does not vacate
     ;; the slot the prior entry shares" guarantee, now atomically.
     ;;
     ;; `prior-on-frame` captures THIS frame's currently-committed flow
     ;; entry for `flow-id`, recorded from inside the update fn so it
     ;; reflects the state the WINNING CAS observed (a contended retry
     ;; re-records against the re-read map; the last invocation — the one
     ;; whose return value commits — leaves the authoritative value). It
     ;; drives two decisions below: (rf2-mb9vq) per-frame first-vs-
     ;; replacement for the `:rf.flow/registered` trace, and (rf2-73pi1)
     ;; the same-frame `:path`-change vacate. `nil` ⇒ first-time
     ;; registration of `flow-id` on THIS frame (even if a SIBLING frame
     ;; already holds the global registrar slot).
     (let [prior-on-frame (volatile! nil)]
       ;; rf2-2woz9 finding 2: a same-frame `reg-flow` REPLACEMENT publishes
       ;; the new flow into `flows` (visible to a drain) in the `swap!`
       ;; below, but the stale-`last-inputs` invalidation only fires later
       ;; via `registrar/register!` → `invalidate-flow-on-replace!`. Pre-fix,
       ;; a drain that started in that window saw the NEW flow with the OLD
       ;; input cache and skipped recompute on `=`-equal inputs, so the first
       ;; post-replacement drain kept stale output (violating Spec 013's
       ;; re-registration contract that the new flow re-evaluates on the next
       ;; event regardless of input equality). Serializing publish →
       ;; path-vacate → invalidation against the frame drain (`:drain-lock`)
       ;; makes them one indivisible step: no drain can observe the new flow
       ;; before its stale dirty-check row is dropped. The atomic check-and-
       ;; insert `swap!` (rf2-qxwib) is preserved verbatim INSIDE the
       ;; serialized region — its TOCTOU cycle guarantee is unchanged; we
       ;; only add the outer drain-exclusion. Reentrant: a mid-drain
       ;; `:rf.fx/reg-flow` runs directly inside the single-drainer window
       ;; (see `frame/call-serialized-with-drain!`).
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
                        ;; the cycle check's atomicity (rf2-qxwib): a throw
                        ;; propagates out of `swap!`, the CAS never fires, and
                        ;; the prior registration survives untouched.
                        ;;
                        ;; 1. Throws :rf.error/flow-path-overlap (rf2-um6d9) if
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
           ;; rf2-73pi1 finding 2: a same-frame re-registration that MOVES the
           ;; output to a new `:path` must vacate the OLD path from this
           ;; frame's app-db — otherwise the previous definition's last write
           ;; lingers and downstream reads see stale derived state at the
           ;; abandoned slot. Only fires on a real same-frame replacement
           ;; (`prior-on-frame` non-nil) whose `:path` actually changed; the
           ;; commit above already installed the new definition, so the new
           ;; path recomputes on the next drain. First-time registration and
           ;; same-path hot-reload leave app-db untouched.
           (when-let [prior @prior-on-frame]
             (let [old-path (:path prior)]
               (when (not= old-path (:path flow))
                 (vacate-output-path! frame-id old-path))))
           ;; Spec 015 §7 / rf2-ouemt + rf2-ihfz9o: install this flow's output
           ;; data-classification marks (EXPLICIT ∪ PROPAGATED-from-inputs)
           ;; into the frame's app-db elision registry, rooted at `(:path
           ;; flow)`. `write-flow-output-marks!` drops THIS flow's prior
           ;; `:source :flow` entries first, so a re-registration that changed
           ;; `:path` or the mark set replaces cleanly (covering the old-path
           ;; declarations the value-vacate above does not touch). Inside the
           ;; serialized region so a concurrent drain's `elide-wire-value` read
           ;; cannot observe a half-updated registry.
           ;;
           ;; Gate: skip the registry write only for a FIRST registration that
           ;; (a) declares no classification key AND (b) inherits nothing —
           ;; none of its input paths overlap an existing sensitive declaration
           ;; on the frame. Such a flow has no mark to install or clear, so it
           ;; pays zero cost and triggers no spurious invalidation. A
           ;; re-registration (`prior-on-frame` non-nil) ALWAYS writes (even a
           ;; now-unmarked replacement must CLEAR the prior definition's marks);
           ;; and a flow that inherits a sensitive input (the realistic
           ;; add-marks-THEN-reg-flow ordering) writes its propagated mark
           ;; eagerly so an observer reading the registry right after reg-flow
           ;; sees it (the drain refresh would otherwise install it only on the
           ;; first event). rf2-ihfz9o.
           (when (or (flow-declares-marks? flow)
                     (some? @prior-on-frame)
                     (input-overlaps-declaration?
                       flow (elision/sensitive-declarations frame-id)))
             (write-flow-output-marks! frame-id flow))
           ;; Cycle check + commit done atomically above. The :flow registrar
           ;; slot keys on flow-id only; stamp :frame into the metadata so
           ;; introspection
           ;; / hot-reload hooks can read the owning frame. `register!`
           ;; returns `{:was previous :now metadata}` — `:was` is nil on
           ;; first-time registration, non-nil on hot-reload re-registration.
           ;; Per rf2-v5ttb: stamp `:handler-fn` so the registrar's
           ;; `:different-fn?` calculation (registrar.cljc) can tell a real
           ;; body change from an idempotent reload. The registrar reads
           ;; `:handler-fn` uniformly across kinds; events / subs / fx all
           ;; populate it at their registration sites, but flows historically
           ;; stored the body under `:output` only — so `(not= nil nil)` was
           ;; the answer for every flow re-registration and `:different-fn?`
           ;; was always `false` (re-frame-10x's flow panel / Xray / re-frame2-pair
           ;; missed every real body swap). The `:output` slot is preserved
           ;; for the flow-eval site that reads it; the additional
           ;; `:handler-fn` stamp aligns the cross-kind hot-reload trace
           ;; surface Spec 001 standardises.
           ;;
           ;; This `register!` is what fires the `invalidate-flow-on-replace!`
           ;; replacement hook (:759) that drops the stale `last-inputs` row —
           ;; keeping it INSIDE the serialized region is the fix for finding
           ;; 2 (the publish above and this invalidation are now indivisible
           ;; to a concurrent drain).
           (registrar/register!
             :flow flow-id
             (source-coords/merge-coords
               (assoc flow
                      :frame      frame-id
                      :handler-fn (:output flow))))))
       ;; Per Spec 009 §:op-type vocabulary: :rf.flow/registered fires
       ;; on FIRST-TIME registration AGAINST THIS FRAME. Pre-rf2-mb9vq
       ;; this gated on the GLOBAL registrar `:was` (flow-id-scoped, not
       ;; frame-scoped), so registering the same flow-id against a SECOND
       ;; frame — an INDEPENDENT first-time registration per Spec 013
       ;; §Frame-scoping line 102 — was misclassified as a replacement and
       ;; the trace was suppressed: a per-frame flow inventory built from
       ;; `op-type :flow` missed the second frame's flow even though
       ;; `flows-snapshot` showed it. Gate on the PER-FRAME prior slot
       ;; (`prior-on-frame`) instead: a genuine same-frame re-registration
       ;; (`prior-on-frame` non-nil) still suppresses — its hot-reload
       ;; signal rides `:rf.registry/handler-replaced` (emitted by
       ;; `registrar/register!` per Spec 001 §Hot-reload trace surface) —
       ;; while a same-id/different-frame FIRST registration emits
       ;; `:rf.flow/registered` with the correct `:frame` tag.
       ;;
       ;; The outer `debug-enabled?` gate matches the hot-path emits in
       ;; flows.cljc (per Spec 009 §Production builds, "keep the gate
       ;; OUTERMOST"); reg-flow is a cold path so the cost is negligible,
       ;; but the gate keeps the tag-map literal out of CLJS prod and the
       ;; convention uniform across every flow emit site (rf2-ee38b.9).
       (when (and interop/debug-enabled? (nil? @prior-on-frame))
         (trace/emit! :flow :rf.flow/registered
                      {:flow-id flow-id
                       :inputs  (:inputs flow)
                       :path    (:path flow)
                       :frame   frame-id})))
     ;; Spec 015 §7. Flows — the output data-classification marks are
     ;; installed FRAME-AWARE into the app-db elision registry via
     ;; `write-flow-output-marks!` inside the serialized region above
     ;; (rf2-ouemt). We deliberately do NOT also stash them in the global
     ;; `re-frame.marks` per-(kind, id) table: that table is keyed by
     ;; flow-id ALONE, but Spec 013 lets the SAME flow-id carry DIFFERENT
     ;; definitions (hence different marks) per frame, so a frame-blind
     ;; `{flow-id marks}` entry is the wrong shape for flows. The
     ;; frame-scoped elision-registry write is the single source of truth
     ;; the wire walker, the db-diff projection, and the view-render-arg
     ;; projection all read.
     flow-id)))

(defn- dissoc-in-safe
  "Like `dissoc-in` over `(butlast path) → (last path)` but robust against
  the two unmaterialised-output failure modes flagged by audit rf2-q25os:

  - **Unmaterialised parent.** When a flow with `:path [:step-2 :result]`
    is cleared BEFORE its first drain, the parent slot `:step-2` may not
    exist. The naïve `(update-in db [:step-2] dissoc :result)` returns
    `(dissoc nil :result)` ⇒ `nil`, producing `{:step-2 nil}` — a
    spurious nil parent. Detect this case and leave `db` unchanged.
  - **Non-map intermediate.** When an intermediate path step holds a
    non-map value (a scalar already wrote past the flow's planned path),
    the naïve `update-in` calls `(dissoc 1 :result)` and throws
    `ClassCastException`. Treat this as a no-op — the flow's `:path`
    never materialised, so there's nothing to clear.

  Single-element paths and non-vector paths are handled by the caller's
  earlier branches; this helper is only called for `(>= (count path) 2)`."
  [db path]
  (let [parent-path (vec (butlast path))
        leaf        (last path)
        parent      (get-in db parent-path ::missing)]
    (cond
      ;; Parent was never materialised — leave db as-is. Per audit
      ;; rf2-q25os Repro 1: registering a nested-path flow then clearing
      ;; before any drain would write `{<parent> nil}` otherwise.
      (or (= ::missing parent) (nil? parent)) db
      ;; Parent is non-map (scalar / vector / set) — there's no
      ;; meaningful "dissoc this leaf" on a non-map intermediate. Per
      ;; audit rf2-q25os Repro 2: throwing ClassCastException for a
      ;; cleanup operation is poor manners; leave the value untouched
      ;; (it's not OUR flow's output anyway).
      (not (map? parent)) db
      :else (update-in db parent-path dissoc leaf))))

(defn- vacate-output-path!
  "Dissoc a flow's output `:path` from `frame-id`'s app-db (only that
  frame's container). Shared by `clear-flow` (full deregistration) and
  the same-frame `:path`-change branch of `reg-flow` (rf2-73pi1 finding
  2 — moving a flow's output to a new path must vacate the OLD path so
  downstream reads don't see stale derived state at the abandoned slot).

  `validate-flow` guarantees `:path` is a non-empty vector at
  registration (rejects non-vector and empty via :rf.error/flow-bad-path),
  so a `:path` read back out of the registry always carries one — no
  non-vector / empty-path arms are reachable here.

  rf2-aqt7: when :path is a single-element vector [:k], (butlast [:k])
  is () and (update-in db [] dissoc :k) does NOT dissoc — Clojure's
  update-in on the empty path falls into (assoc {} nil (apply f val
  args)), producing {... nil nil}. Special-case length 1 so the leaf
  is dissoc'd directly. The (>= 2) branch routes through
  `dissoc-in-safe` which handles the unmaterialised-parent / non-map-
  intermediate cases without writing nil parents or throwing (per audit
  rf2-q25os).

  Per rf2-2vpac: skips the write when the dissoc branch was a no-op
  (missing key, or `dissoc-in-safe` returning `db` literally on
  unmaterialised-parent / non-map-intermediate). Otherwise we trigger
  reactive sub-cache invalidation for a no-op write — cheap-but-needless
  walk of the sub graph (`identical?` is O(1)). No-op when the frame has
  no app-db container.

  EP-0001 (rf2-adwcv6): writes the app-db PARTITION of the one physical
  frame-state container via `frame/swap-frame-db!` — `app-db-container` is
  now a READ-ONLY projection. Flow OUTPUTS are app-db values (Mike ruling
  #12), so vacating one is an app-db write. The `identical?` no-op skip is
  preserved by computing `new-db` first and only swapping when it changed."
  [frame-id path]
  (when-let [db (frame/frame-app-db-value frame-id)]
    (let [new-db (if (= 1 (count path))
                   (dissoc db (first path))
                   (dissoc-in-safe db path))]
      (when-not (identical? new-db db)
        (frame/swap-frame-db! frame-id (constantly new-db))))))

;; ---- registrar-slot owner maintenance (rf2-73pi1) ------------------------
;;
;; The `:flow` registrar slot is keyed by flow-id alone and carries the
;; MOST-RECENTLY-REGISTERED frame's flow-map with `:frame` stamped in
;; (Spec 013 §Frame-scoping line 105). On clear / destroy of the frame
;; whose metadata currently occupies the slot, that metadata becomes
;; STALE: it points at a frame that no longer owns the id. Runtime
;; evaluation is unaffected (the per-frame `flows` registry is the source
;; of truth), but registrar-backed tooling / hot-reload goes stale — the
;; next surviving-frame re-registration computes `:different-fn?` against
;; the dead frame's `:handler-fn`, and a Xray flow panel reading the slot
;; attributes the flow to a destroyed frame.

(defn- realign-registrar-owner!
  "After `flow-id` was removed from `cleared-frame-id`, keep the shared
  `:flow` registrar slot pointing at an actually-live owner (rf2-73pi1).

  - When NO surviving frame in `flows-map` holds `flow-id`, unregister
    the slot (the cleared/destroyed frame was the last owner).
  - When a surviving frame holds it AND the registrar slot's current
    `:frame` is the cleared/destroyed frame (its metadata is now stale),
    re-point the slot to a deterministic surviving owner — re-stamping
    that owner's flow-map (with `:frame` / `:handler-fn`) exactly as
    `reg-flow` would. The surviving owner is chosen by sorted frame-id
    for determinism across calls.
  - When a surviving frame holds it but the registrar slot already names
    a still-living owner, leave it untouched (no churn).

  Returns nil — side-effects the registrar only."
  [flows-map flow-id cleared-frame-id]
  (let [owners (owning-frames flows-map flow-id)]
    (if (empty? owners)
      ;; Last owner released the id — drop the slot.
      (registrar/unregister! :flow flow-id)
      ;; A sibling still owns the id. Only re-point when the slot's
      ;; metadata named the frame we just cleared (so its body / path /
      ;; frame attribution is stale).
      (let [current-owner (:frame (registrar/lookup :flow flow-id))]
        (when (= current-owner cleared-frame-id)
          (let [survivor (first (sort owners))
                flow     (get-in flows-map [survivor flow-id])]
            (registrar/register!
              :flow flow-id
              (source-coords/merge-coords
                (assoc flow
                       :frame      survivor
                       :handler-fn (:output flow))))))))))

(defn clear-flow
  "Deregister a flow from a frame; dissoc its output path from that
  frame's app-db (only that frame). EP-0002 — context-required
  frame-local: the explicit `:frame` opt (the *override*) wins, else the
  carried-invariant scope chain via `frame/require-current-frame!`. A
  `clear-flow` issued under no established scope and no explicit `:frame`
  raises `:rf.error/no-frame-context` rather than clearing against
  `:rf/default`.

  Per audit rf2-q25os: the nested-path dissoc is robust against the
  output path never having been materialised (no spurious nil parent
  created) and against a non-map intermediate (no ClassCastException
  thrown) — see `dissoc-in-safe` / `vacate-output-path!` above.

  Vacation contract (rf2-ee38b.9): clearing a flow with `:path
  [:wizard :result]` removes the LEAF (`:result`) only. If `:result`
  was the sole key under `:wizard`, an empty parent map `{:wizard {}}`
  remains — this is deliberate, not a leak. The flow's *value* is
  fully gone (the spec's \"vacate the slot\" requirement); pruning empty
  ancestor maps would risk deleting unrelated sibling slots that happen
  to be empty and own ancestors this flow never created, so leaf-only
  vacation is the correct contract. Downstream consumers read the leaf,
  not the parent's emptiness."
  ([id] (clear-flow id {}))
  ([id {:keys [frame] :as _opts}]
   (let [frame-id (or frame
                      (frame/require-current-frame!
                        :clear-flow
                        {:where    'rf/clear-flow
                         :event-id id}))
         ;; rf2-4wqu6 finding 2: the flow lookup + `:path` capture MUST
         ;; happen INSIDE the drain-lock, not before it. Pre-fix this
         ;; read the flow and captured `path` ahead of
         ;; `call-serialized-with-drain!`; on the JVM a competing same-frame
         ;; same-id `reg-flow` replacement could win the lock after that
         ;; stale read, install a NEW `:path`, a drain could materialise the
         ;; replacement's new output, and this clear-flow would then run
         ;; under the lock using the OLD path — vacating a now-empty old
         ;; path (a no-op) while removing the live registry row and leaving
         ;; the replacement's new output stale in app-db (violating Spec
         ;; 013's clear-flow cleanup contract). It would also emit a
         ;; misleading `:rf.flow/cleared` for the old path.
         ;;
         ;; The fix folds the lookup, path capture, app-db vacate, registry
         ;; removal, dirty-row drop, and registrar realignment into ONE
         ;; serialized operation over the SAME live flow definition. The
         ;; thunk returns the path it actually cleared (or nil when no flow
         ;; was registered under the lock) so the `:rf.flow/cleared` emit
         ;; below fires only on a real clear, with the path captured under
         ;; the lock. (rf2-2woz9 finding 1: serializing the mutation against
         ;; the drain already made the vacate→deregister sequence atomic;
         ;; this extends that atomicity to the lookup the sequence reads.)
         ;; Reentrant: a mid-drain `:rf.fx/clear-flow` runs directly inside
         ;; the single-drainer window (see `frame/call-serialized-with-drain!`).
         cleared-path
         (frame/call-serialized-with-drain!
           frame-id
           (fn []
             (when-let [flow (get-in @flows [frame-id id])]
               (let [path (:path flow)]
                 (vacate-output-path! frame-id path)
                 ;; Spec 015 §7 / rf2-ouemt: drop this flow's output data-
                 ;; classification declarations from the frame's app-db elision
                 ;; registry so a deregistered flow leaves no orphaned redaction
                 ;; behind. Schema- / add-marks-sourced entries survive.
                 (clear-flow-output-marks! frame-id id)
                 ;; Drop the flow from `frame-id`'s per-frame slot; when that was
                 ;; the LAST flow on the frame, prune the now-empty `frame-id` key
                 ;; entirely rather than leave a `{frame-id {}}` husk (rf2-4bbaw).
                 ;; The husk was harmless (bounded by frame count, tolerated by the
                 ;; concurrency-stress invariant) but a naive `flows-snapshot`
                 ;; consumer would iterate the empty entry; pruning keeps the
                 ;; registry exactly symmetric with `teardown-on-frame-destroy!`'s
                 ;; `(swap! flows dissoc frame-id)`.
                 (swap! flows (fn [m]
                                (let [m' (update m frame-id dissoc id)]
                                  (cond-> m'
                                    (empty? (get m' frame-id)) (dissoc frame-id)))))
                 ;; Drop the cleared flow's dirty-check row from THIS frame's own
                 ;; `last-inputs` container (rf2-94ol5). Frame-local — a sibling
                 ;; frame registering the same id keeps its own row untouched.
                 (drop-frame-flow-row! frame-id id)
                 ;; Keep the shared `:flow` registrar slot aligned with a live
                 ;; owner (rf2-73pi1): unregister when this was the LAST frame
                 ;; holding the id, or re-point the slot to a surviving owner
                 ;; when the cleared frame was the slot's current (now-stale)
                 ;; metadata writer. Pre-fix this only unregistered on
                 ;; last-owner-release, leaving the slot pointing at the cleared
                 ;; frame whenever a sibling still held the id.
                 (realign-registrar-owner! @flows id frame-id)
                 path))))]
     ;; Per Spec 009 §:op-type vocabulary: :rf.flow/cleared fires after
     ;; clear-flow has removed the flow from the per-frame registry
     ;; and dissoc-in'd its output path. Tools observe this to drop
     ;; their per-flow display state. Only emit when a flow was ACTUALLY
     ;; cleared (rf2-4wqu6 finding 2 — a no-op clear-flow for an unregistered
     ;; id, or one that lost the race to a competing lifecycle op, emits
     ;; nothing), carrying the path captured under the lock. The outer
     ;; `debug-enabled?` gate matches the hot-path emits in flows.cljc (per
     ;; Spec 009 §Production builds, "keep the gate OUTERMOST"); clear-flow is
     ;; a cold path so the cost is negligible, but the gate keeps the
     ;; tag-map literal out of CLJS prod and the convention uniform
     ;; across every flow emit site (rf2-ee38b.9).
     (when (and cleared-path interop/debug-enabled?)
       (trace/emit! :flow :rf.flow/cleared
                    {:flow-id id
                     :path    cleared-path
                     :frame   frame-id}))
     nil)))

;; ---- frame-destroy teardown ---------------------------------------------
;;
;; Per rf2-wbtjn — symmetric with the machines `:teardown-on-frame-destroy!`
;; hook (rf2-vsigt). On `destroy-frame!`, the flows registered against the
;; destroyed frame, the per-frame `last-inputs` rows, AND any `:flow`
;; registrar entries whose last owning frame was the destroyed one MUST
;; clear — otherwise SSR-style per-request frame churn / pair-tool
;; time-travel / `make-frame` ephemeral usage leak flow definitions and
;; cached input vectors indefinitely (audit
;; `ai/findings/flows-security-audit-2026-05-15.md` F1).

(defn teardown-on-frame-destroy!
  "Drop every per-frame entry the flows artefact holds against `frame-id`:

   1. Snapshot the flow-ids the destroyed frame owned (needed for the
      registrar prune in step 4).
   2. Dissoc `frame-id` from the per-frame flow registry.
   3. Dissoc the destroyed frame's `last-inputs` container from the
      per-frame `frame-last-inputs` registry — one step (rf2-94ol5),
      since per-frame storage holds the destroyed frame's rows in its
      own inner atom and removing the frame-keyed slot drops them all.
   4. For each flow-id the destroyed frame owned, keep the `:flow`
      registrar slot aligned with a live owner (rf2-73pi1): unregister
      the slot when no surviving frame still registers the id, or
      re-point it to a surviving owner when the destroyed frame was the
      slot's current (now-stale) metadata writer. The `:frame` stamped
      onto the registrar entry was the destroyed frame whenever it was
      the most-recent registrant — leaving that metadata pointing at the
      dead frame staled registrar-backed tooling / hot-reload.

   NO explicit flow-output elision-mark scrub (rf2-yt5bbl). Unlike
   `clear-flow` — which removes ONE flow's `:source :flow` declarations
   while its frame lives on (hence its `clear-flow-output-marks!` call) —
   frame-destroy drops the WHOLE frame. The elision registry lives in the
   frame's runtime-db partition at `[:rf.runtime/elision]`
   (`re-frame.elision` §registry-of), INSIDE the one physical
   `:frame-state` container held under the frame record; `destroy-frame!`
   step 6 (`frame.cljc` §dissoc-frame!) `(swap! frames dissoc id)` drops
   that whole record — container, runtime-db, and every `:source :flow`
   declaration — so the marks are gone with the frame and a per-flow scrub
   here would be redundant work over about-to-be-GC'd state. The
   teardown hook runs BEFORE step 6 (`frame.cljc:1711` then `:1757`), but
   that ordering is moot: nothing observes the (still-live) elision slot
   between the hook and the dissoc, and a reused frame-id gets a FRESH
   empty container (`frame.cljc` §new-frame-record), so a destroyed/reused
   frame can never observe a prior incarnation's stale flow-sourced
   declaration. Pinned by
   `flows_destroy_frame_teardown_test` §destroy-frame-drops-flow-output-marks.

   Idempotent against a frame the registry never recorded (a frame
   destroy before any `reg-flow`). Published via the
   `:flows/teardown-on-frame-destroy!` late-bind hook so
   `frame/destroy-frame!` reaches it without statically requiring the
   flows artefact."
  [frame-id]
  (when frame-id
    (let [owned-flow-ids (keys (get @flows frame-id))]
      (swap! flows dissoc frame-id)
      ;; Drop the destroyed frame's entire `last-inputs` container in one
      ;; step (rf2-94ol5) — per-frame storage means the destroyed frame's
      ;; rows ARE its inner atom, so removing the frame-keyed slot drops
      ;; every row at once and cannot touch any sibling frame's container.
      (swap! frame-last-inputs dissoc frame-id)
      ;; Registrar realignment (rf2-73pi1): for each flow-id the destroyed
      ;; frame owned, either unregister the `:flow` slot (no surviving
      ;; owner) or re-point it to a surviving owner when the destroyed
      ;; frame was the slot's stale metadata writer — the shared
      ;; `realign-registrar-owner!` helper `clear-flow` also uses.
      (let [remaining @flows]
        (doseq [flow-id owned-flow-ids]
          (realign-registrar-owner! remaining flow-id frame-id)))))
  nil)

;; ---- hot-reload invalidation --------------------------------------------
;;
;; Per Spec 001 §Hot-reload semantics: when a flow re-registers, the
;; per-frame :last-inputs entry MUST clear so the new flow re-evaluates
;; on the next drain regardless of whether inputs changed. Without this,
;; a hot-reloaded flow with a different :output fn but identical recent
;; inputs would silently keep serving the previous result.

(defn- invalidate-flow-on-replace!
  [{:keys [kind id now]}]
  (when (= kind :flow)
    ;; Per rf2-jfpf3: Spec 013 §Re-registration scopes the invalidation
    ;; to `[frame-id flow-id]`, NOT every frame holding the flow id.
    ;; Pre-fix, a re-registration on frame `:left` wiped
    ;; `last-inputs[id]` entirely — `:right`'s row for the same id
    ;; recomputed unnecessarily on its next drain, weakening frame
    ;; isolation and wasting work under multi-frame setups (per-tenant
    ;; SSR, pair-tool replays). The registrar replacement-hook payload
    ;; carries `:now` (the new metadata) with `:frame` stamped at
    ;; `reg-flow`-time; read the frame from there and drop only that
    ;; frame's row from its own `last-inputs` container (rf2-94ol5 —
    ;; per-frame storage makes the sibling-frame untouched guarantee
    ;; structural rather than reliant on careful keying).
    (let [frame-id (:frame now)]
      (drop-frame-flow-row! frame-id id))))

(defonce ^:private _hot-reload-hook
  ;; `defonce` only needs the side-effect to fire once at namespace
  ;; load; the value bound to the var is incidental. `add-replacement-hook!`
  ;; returns nil — let that be the bound value.
  (registrar/add-replacement-hook! invalidate-flow-on-replace!))

;; ---- test-only resets ----------------------------------------------------

(defn reset-last-inputs!
  "Test-only: clear ALL per-frame dirty-check `last-inputs` containers
  (rf2-94ol5 — drops the whole `frame-last-inputs` registry, discarding
  every frame's inner atom). The flows reset-runtime fixture uses this to
  drop stale per-flow state between tests so re-registration does not
  silently no-op when new-inputs =-equal a stale entry from a sibling test.
  Per rf2-tfw3 (the fourth per-feature split): this is published through
  the late-bind hook table so `re-frame.test-support`'s reset-runtime
  fixture can call it without statically requiring `re-frame.flows`."
  []
  (reset! frame-last-inputs {})
  nil)

(defn reset-flows!
  "Test-only: clear the per-frame flow registry AND the paired
  dirty-check `last-inputs` map. Per rf2-tfw3 — exposed via the
  late-bind hook table so `re-frame.test-support` can reset state
  without a static require on this namespace.

  Per rf2-mb65w: resets BOTH atoms in lockstep. Pre-fix, the function
  cleared only `flows` and left `last-inputs` standing. A test fixture
  / re-frame2-pair / Xray harness calling `reset-flows!` standalone (the
  function's name suggests \"reset all flow state\") then re-registered
  the same flow-id would silently no-op the first evaluation when
  new-inputs `=`-equalled a leftover entry. The two-atom reset is the
  single sound invariant — anything calling `reset-flows!` wants flow
  state cleared, and `last-inputs` is downstream cache for the same
  registry. Per rf2-94ol5 the dirty-check reset now drops every
  per-frame `last-inputs` container (the `frame-last-inputs` registry)."
  []
  (reset! flows {})
  (reset! frame-last-inputs {})
  nil)
