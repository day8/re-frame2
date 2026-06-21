(ns re-frame.resources.classification
  "Resource / mutation OWNER-classification — the EP-0015 §6 reconciliation
  (bead-plan item 5). Graduated normatively into
  [`spec/015-Data-Classification.md` §Resource and mutation durable
  classification](../../../../../../spec/015-Data-Classification.md) +
  [`spec/016-Resources.md` §SSR and hydration](../../../../../../spec/016-Resources.md).

  ## The ownership rule (EP-0015 §6, ruled issue 11)

  A `reg-resource` / `reg-mutation` declaration creates DURABLE
  runtime-subsystem state (cache entries under `:rf.runtime/resources`,
  scoped keys, params, data, errors, refresh errors, work-ledger summaries;
  mutation instances under `:rf.runtime/mutations` carrying params / result
  / error). Those shapes are **owned by the resource or mutation
  definition** — they are durable runtime-subsystem state, NOT transient
  registration payloads classified merely because `reg-resource` /
  `reg-mutation` declared them (EP-0015 §4 / Spec 015 §Registration-owned
  transient classification).

  The canonical fine-grained classification surface is **per-slot
  `:sensitive?` / `:large?` props on the existing `:data-schema` /
  `:params-schema`** — the SAME EP-0005 mechanism the machine `:data-schema`
  surface uses (EP-0015 issue 11, ruled). There is **no new resource
  path-map vocabulary** (that would be the fourth spelling EP-0007 exists to
  prevent). The coarse whole-entry `:sensitive?` / `:large?` claims on the
  resource spec remain as the degenerate **root-prop** case — the whole
  resource is the classification unit.

  ## Projection is the merged frame-owned primitive

  Classification NAMES the facts; the framework PROJECTS them at every
  egress boundary (SSR, tool, trace, epoch, observability). Projection is
  the merged frame-owned `re-frame.projection/project-egress` over the
  shared `re-frame.elision/elide-wire-value` walker (EP-0015 §10/§11) —
  NEVER a family-private resource elider. The resource-local redaction this
  slice replaces (the ad-hoc `:sensitive?` → sentinel decision) now DEFERS
  to that source of truth: the whole-entry coarse disposition (the owner's
  root-prop classification) gates redact / omit / serialize, and the
  serialized data slice rides through `project-egress` under the SSR
  boundary profile, so any per-slot `:data-schema` mark the frame
  classification carries composes as defense-in-depth.

  ## Sensitive wins over large

  A resource declared BOTH `:sensitive?` and `:large?` (or whose data-schema
  marks a slot both) redacts as SENSITIVE — the redaction sentinel is the
  more conservative shape (it still announces an entry exists, but emits no
  `:rf.size/large-elided` marker that could leak path / byte size / digest /
  fetch-handle). This mirrors the walker's sensitive-before-large ordering
  (`re-frame.elision/walk`) and the frame-classification install-time rule
  (`re-frame.frame-classification`).

  ## What this slice does NOT touch

  - It introduces NO new public API surface (no facade export) — it is the
    internal owner-classification seam the SSR projection + the reply trace
    egress consume. The public classification surface is the `:data-schema`
    / `:params-schema` per-slot props already on `reg-resource` /
    `reg-mutation` (EP-0005 mechanism).
  - It does NOT change the Spec-Schemas schema-marker grammar (the
    `:sensitive?` / `:large?` Malli props are owned by the schemas artefact
    + Spec-Schemas — EP-0015 bead-plan item 9)."
  (:require [re-frame.elision :as elision]
            [re-frame.late-bind :as late-bind]
            [re-frame.classification :as classification]
            [re-frame.path :as path]
            [re-frame.projection :as projection]
            [re-frame.resources.scope-registry :as scope-registry]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Whole-entry disposition — the coarse root-prop owner classification.
;;
;; EP-0015 issue 11 (ruled): the coarse whole-entry `:sensitive?` /
;; `:large?` claims remain as the degenerate root-prop case (the WHOLE
;; resource is the classification unit). They gate the metadata-only
;; redact / omit shape the durable entry / instance rides on SSR and in
;; tool / epoch projections; sensitive wins over large.
;; ---------------------------------------------------------------------------

(defn whole-entry-disposition
  "Classify how a resource / mutation's WHOLE durable data may ride a wire,
  reading the owner spec's coarse root-prop `:sensitive?` / `:large?` claims.
  Returns one of:

    :serialize  — ship the data slice (still PROJECTED through frame
                  classification — see `project-data`);
    :redact     — metadata-only: replace the data with the redaction
                  sentinel (a `:sensitive?` owner);
    :omit       — metadata-only: drop the data key entirely (a `:large?`
                  owner whose payload is too big to ride).

  Sensitive wins over large (the redaction sentinel is the more
  conservative shape — it still announces the entry exists). A nil spec
  (an unregistered id) is `:serialize` (no coarse claim). Pure. Per
  EP-0015 §6 / Spec 015 §Resource and mutation durable classification."
  [spec]
  (cond
    (:sensitive? spec) :redact
    (:large? spec)     :omit
    :else              :serialize))

;; ---------------------------------------------------------------------------
;; Named-scope-resolver DERIVED-sensitivity propagation (EP-0016 wave,
;; rf2-fi6tda.1) — the fourth framework-known derivation graph EP-0015
;; disposition 8 names (subs / flows / machine-selectors shipped in
;; rf2-t55hxg.8; this is the deferred scope-resolver arm rf2-t55hxg.11 walked
;; back to EP-0016).
;;
;; A resource whose `:scope` policy is a `{:from-db <resolver-id>}` reference
;; derives its cache scope from the resolver's declared `:db` inputs. If any of
;; those inputs reads a FRAME-SENSITIVE app-db path, the derived scope (user /
;; tenant / impersonation identity) MAY carry that sensitivity — so the
;; resource's durable entry (whose scoped KEY embeds the resolved scope, and
;; whose data shares the same privacy class — Spec 016 clause 4) inherits
;; `:sensitive` EVEN WHEN the owning resource was not itself declared
;; `:sensitive?`. This is the automatic-inheritance defence-in-depth arm,
;; consistent with the subs/flows `:rf.egress/output-sensitivity` model
;; (EP-0015 issue 9):
;;
;;   :rf.egress/sensitive — force-mark the derived scope sensitive;
;;   :rf.egress/public    — DECLASSIFY (the resolver asserts the derived scope
;;                          is safe to surface despite sensitive inputs — the
;;                          declassification analogue of :rf.scope/global,
;;                          enumerable as a standing audit surface);
;;   :rf.egress/inherit   — (default) propagate: sensitive iff any declared
;;                          `:db` input path overlaps a frame-sensitive
;;                          declaration.
;;
;; Conservative + FAIL-CLOSED (footgun prevention, not security-grade taint —
;; the SAME posture as subs' layer-1 check and flows' input-overlap check): an
;; input that reads a sensitive slot, a parent of one, or a child of one all
;; count (path overlap, either direction). The PRIMARY scope boundary holds
;; independently of this arm (the resource-owned `:sensitive?` claim +
;; scoped-key redaction); this arm only ADDS the inheritance precision.
;; ---------------------------------------------------------------------------

(defn- frame-sensitive-paths
  "The frame's declared sensitive app-db paths for `frame-id` — the keys of the
  frame-owned `:sensitive` elision declarations (union of `:source :frame` and
  any `:source :effect` / `:source :flow` entries; `re-frame.elision/`
  `sensitive-declarations`). A nil `frame-id` (a frameless / pure projection —
  no resolvable frame scope) yields `[]`: there is no frame classification to
  inherit from, and the primary owner `:sensitive?` boundary still governs."
  [frame-id]
  (if frame-id
    (vec (keys (elision/sensitive-declarations frame-id)))
    []))

(defn- input-overlaps-sensitive?
  "True iff ANY of the resolver's declared `:db` input paths overlaps ANY
  frame-sensitive path (one a prefix of the other, either direction — the
  shared `re-frame.path/overlap?` relation, the SAME prefix test subs / flows
  inherit). Conservative + fail-closed: an input reading a sensitive slot, a
  parent of one, or a child of one all count. Empty input or sensitive-path
  set → false."
  [input-paths sensitive-paths]
  (boolean
    (when (and (seq input-paths) (seq sensitive-paths))
      (some (fn [in-path]
              (some #(path/overlap? in-path %) sensitive-paths))
            input-paths))))

(defn resolver-derived-sensitive?
  "True iff the named-scope resolver `resolver-id` derives a SENSITIVE scope
  against `frame-id`'s app-db classification (EP-0016 wave, rf2-fi6tda.1 —
  EP-0015 disposition 8's fourth framework-known graph). Pure over the resolver
  registry + the frame's elision registry. Resolution, honouring the closed
  `:rf.egress/output-sensitivity` claim (the SAME claim subs/flows honour):

    :rf.egress/sensitive — true (force-mark, even from public inputs);
    :rf.egress/public    — false (DECLASSIFY — the resolver asserts safe);
    :rf.egress/inherit   — (default) true iff any declared `:db` input path
                           overlaps a frame-sensitive declaration.

  Fail-closed: a `resolver-id` with no registered resolver returns false (no
  derivation graph to read — the consumption side still honours the owner's
  `:sensitive?` independently). Per Spec 015 §Derived sensitivity."
  [resolver-id frame-id]
  (let [spec (scope-registry/scope-resolver-meta resolver-id)]
    (if (nil? spec)
      false
      (case (:output-sensitivity spec :rf.egress/inherit)
        :rf.egress/sensitive true
        :rf.egress/public    false
        ;; :rf.egress/inherit (default) — propagate from sensitive :db inputs
        (input-overlaps-sensitive?
          (scope-registry/input-db-paths (:inputs spec))
          (frame-sensitive-paths frame-id))))))

(defn scope-derived-sensitive?
  "True iff a resource `spec`'s `:scope` policy is a `{:from-db <resolver-id>}`
  reference whose resolver derives a SENSITIVE scope against `frame-id`
  (`resolver-derived-sensitive?`). The provenance hook the consumption side
  reads at scoped-key / entry classification: the resolved concrete scope in a
  cache key has lost its resolver provenance, but the resource SPEC retains the
  `{:from-db …}` policy, so the resolver-id is recoverable from the resource-id
  the entry's scoped key carries (Spec 016 §Scope resolution). A non-`{:from-db}`
  scope policy (`:rf.scope/global`, a literal scope, a fn resolver,
  `:rf.scope/from-caller`) has no named-resolver derivation graph and returns
  false. A nil spec returns false. Pure. Per Spec 015 §Derived sensitivity /
  Spec 016 §Resolver references — `{:from-db <id>}`."
  [spec frame-id]
  (boolean
    (when-let [scope (:scope spec)]
      (when (scope-registry/from-db-reference? scope)
        (resolver-derived-sensitive? (:from-db scope) frame-id)))))

(defn whole-entry-disposition-for
  "Frame-aware whole-entry disposition (EP-0016 wave, rf2-fi6tda.1). Returns
  `:serialize` / `:redact` / `:omit` exactly as `whole-entry-disposition`, but
  ALSO folds in the named-scope-resolver derived-sensitivity inheritance arm
  against `frame-id`:

    - the resource OWNER's coarse `:sensitive?` / `:large?` claims govern as
      before (`whole-entry-disposition`) — the PRIMARY boundary, frame-blind;
    - ADDITIONALLY, when the owner did not declare `:sensitive?` but the
      resource's `:scope` is a `{:from-db <id>}` reference whose resolver
      derives a sensitive scope against the frame (`scope-derived-sensitive?`),
      the entry is `:redact` — automatic inheritance, defence-in-depth.

  Sensitive (owner-declared OR derived) wins over large: a derived-sensitive
  resource that is also coarse-`:large?` redacts (the more conservative shape).
  A nil `frame-id` (frameless projection) reduces to `whole-entry-disposition`
  (no frame classification to inherit from; the owner boundary still governs).
  Pure. Per Spec 015 §Derived sensitivity / Spec 016 clause 4."
  [spec frame-id]
  (let [owner (whole-entry-disposition spec)]
    (if (= :redact owner)
      ;; owner already redacts (declared :sensitive?) — unchanged
      :redact
      ;; owner is :serialize or :omit — derived-sensitivity, when it fires,
      ;; UPGRADES to :redact (sensitive wins over large / serialize).
      (if (scope-derived-sensitive? spec frame-id)
        :redact
        owner))))

;; ---------------------------------------------------------------------------
;; EP-0025 §subsystems projection-relative declarations (rf2-h3d8tf).
;;
;; A resource / mutation DEFINITION declares its sensitive / large slots
;; PROJECTION-RELATIVE to the instance projection (the matrix root: entry's
;; `:params` / `:data`), via top-level `:sensitive` / `:large` vectors on the
;; `reg-resource` / `reg-mutation` spec — the EP-0025 example surface:
;;
;;   (rf/reg-resource :user-profile
;;     {:sensitive [[:data :ssn]] :large [[:data :avatar-bytes]]})
;;
;; This is "classify at the fact's definition site" — the resource IS defined
;; at `reg-resource`, so it declares its own (statically-known) sensitive
;; fields there. The declaration is value-INDEPENDENT and standing: it redacts
;; whatever later occupies the slot on EVERY instance (the per-instance
;; application the EP names — every minted scoped key / landed data value
;; redacts under the one owner declaration, with no per-instance author code
;; and no storage paths in app code). It supersedes EP-0015 issue 11's
;; "schema-props are the only fine-grained surface, no resource path-map
;; vocabulary" stance: the projection-relative path vocabulary IS the canonical
;; surface (the same axis vocabulary the machine / app-db / transient cases
;; use — one name per fact), and the schema-prop route is unioned with it
;; (kept as the schema-natural co-declaration until the EP-0025 purge).
;;
;; Lowering = re-rooting + axis-split. The declaration paths are rooted at the
;; instance projection (`[:data …]` → the data value; `[:params …]` → the
;; scoped-key params; `[:scope …]` / a `[:scope]`-rooted path → the scope
;; component). `project-data` consumes the `:data`-rooted paths (stripped of
;; the `:data` head); `project-params` consumes the `:params`-rooted paths
;; (stripped of the `:params` head). A bare-rooted path (no `:data` / `:params`
;; head) defaults to the DATA projection — the common `{:sensitive [[:ssn]]}`
;; shorthand for a data field.
;; ---------------------------------------------------------------------------

(def ^:private declaration-axis-keys
  "The two EP-0025 projection-relative declaration axes a `reg-resource` /
  `reg-mutation` spec may carry — `:sensitive` (redact at egress) / `:large`
  (size marker at egress)."
  #{:sensitive :large})

(defn- decl-defect
  "Human reason for the FIRST malformed entry in a resource / mutation
  classification axis payload `payload` (axis `k`), or nil when well-shaped. A
  payload is a vector of valid concrete `:rf/path` vectors (the same shape the
  machine declaration + the four commit-plane effects validate). Pure /
  value-independent."
  [k payload]
  (cond
    (not (vector? payload))
    (str "the `" k "` classification declaration must be a vector of "
         "projection-relative paths (e.g. [[:data :ssn]] / [[:params :account-id]]); "
         "got a " (pr-str (type payload)))
    :else
    (some (fn [p]
            (cond
              (not (sequential? p))
              (str "each path in the `" k "` classification declaration must be a "
                   "path vector; got " (pr-str p))
              :else
              (try (path/normalize-concrete p) nil
                   (catch #?(:clj Throwable :cljs :default) e
                     (str "an invalid path in the `" k "` classification "
                          "declaration: " (or (ex-message e) (str e)))))))
          payload)))

(defn classification-declaration-defect
  "PURE fail-loud-INPUT validator for a resource / mutation `spec`'s
  EP-0025 projection-relative `:sensitive` / `:large` declarations. Returns
  `{:axis k :reason <string>}` for the FIRST defect, or nil when every present
  declaration is well-shaped (or none is declared). The caller
  (`reg-resource` / `reg-mutation`) throws `:rf.error/invalid-resource-spec`
  at the registration boundary on a defect — the same fail-loud posture as the
  machine declaration."
  [spec]
  (some (fn [k]
          (when (contains? spec k)
            (when-let [reason (decl-defect k (get spec k))]
              {:axis k :reason reason})))
        declaration-axis-keys))

(defn- split-projection-paths
  "Split a `spec`'s projection-relative declarations for one axis (`k`) into
  `{:data [paths] :params [paths]}`, each path re-rooted under its projection
  (the `:data` / `:params` head stripped). A bare-rooted path (no recognised
  head) defaults to the DATA projection (the `[[:ssn]]` shorthand). A
  `:scope`-rooted path rides the PARAMS projection (the scoped key carries
  scope + params together — Spec 016 clause 4: \"params, scopes, and data carry
  the same classification\"). Pure."
  [spec k]
  (reduce
    (fn [acc p]
      (let [p (vec (path/normalize-concrete p))]
        (case (first p)
          :data   (update acc :data conj (subvec p 1))
          :params (update acc :params conj (subvec p 1))
          :scope  (update acc :params conj p)
          ;; bare-rooted ⇒ a data field shorthand
          (update acc :data conj p))))
    {:data [] :params []}
    (get spec k)))

(defn spec-declaration-marks
  "The EP-0025 projection-relative `:sensitive` / `:large` declarations a
  resource / mutation `spec` carries, lowered + axis-split into the SAME
  `{:sensitive {path decl} :large {path decl}}` shape the schema-prop marks
  use — keyed `:data` (the data projection) and `:params` (the scoped-key
  params projection). Returns `{:data {…} :params {…}}` (a `:sensitive` /
  `:large` map under each), so the existing `project-data` / `project-params`
  readers union it with the schema-prop marks. Empty when the spec declares
  neither axis. Pure / value-independent. Assumes the spec passed
  `classification-declaration-defect`."
  [spec]
  (let [s (split-projection-paths spec :sensitive)
        l (split-projection-paths spec :large)
        ->decl (fn [paths] (into {} (map (fn [p] [p {:source :owner-declaration}])) paths))]
    {:data   {:sensitive (->decl (:data s))   :large (->decl (:data l))}
     :params {:sensitive (->decl (:params s)) :large (->decl (:params l))}}))

;; ---------------------------------------------------------------------------
;; Per-slot owner classification — the schema-natural co-declaration surface.
;;
;; EP-0015 issue 11: per-slot `:sensitive?` / `:large?` props on the owner's
;; `:data-schema` / `:params-schema` (the EP-0005 mechanism). They are
;; extracted through the SHARED schema walker hooks
;; (`:schemas/extract-sensitive-paths-from-schema` /
;; `:schemas/extract-large-paths-from-schema`) — the same walker
;; `re-frame.elision` consumes for app-schema slots — never a resource-local
;; re-implementation. An inline schema form contributes its marks; a keyword
;; registry ref is an opaque leaf (the walker's documented limitation, shared
;; with every other schema-mark consumer). EP-0025 §subsystems UNIONS the
;; explicit projection-relative declarations (`spec-declaration-marks`) on top
;; of these — the projection-relative path vocabulary is the canonical surface,
;; the schema-prop the schema-natural co-declaration.
;; ---------------------------------------------------------------------------

(defn- union-marks
  "Union two `{:sensitive {path decl} :large {path decl}}` mark maps (the
  schema-prop marks and the EP-0025 projection-relative declaration marks).
  Per axis the path-keyed maps merge (a path classified by both routes keeps
  one entry; sensitive-wins-over-large is resolved later by the walker). Pure."
  [a b]
  {:sensitive (merge (:sensitive a) (:sensitive b))
   :large     (merge (:large a)     (:large b))})

(defn- extract-paths
  "Extract the `{path decl}` map of `:sensitive?` (or `:large?`) per-slot
  marks from `schema` rooted at `base-path`, via the late-bound shared schema
  walker hook. Returns `{}` when the hook is unbound (no schemas artefact) or
  `schema` is nil / carries no marks."
  [hook schema base-path]
  (if-let [extract (and schema (late-bind/get-fn-cached hook))]
    (or (extract schema base-path) {})
    {}))

(defn- schema-marks
  "The per-slot `:sensitive?` / `:large?` classification a `schema` declares,
  as `{:sensitive {path decl} :large {path decl}}` rooted at `[]`, via the
  shared schema walker. The common engine behind `data-schema-marks`
  (resource DATA value) and `params-schema-marks` (resource PARAMS value) —
  one extraction, two owner surfaces (EP-0015 issue 11: `:data-schema` /
  `:params-schema` are CO-EQUAL fine-grained surfaces). Empty maps when
  `schema` is nil or carries no marks. Pure (modulo the memoised walker)."
  [schema]
  {:sensitive (extract-paths :schemas/extract-sensitive-paths-from-schema schema [])
   :large     (extract-paths :schemas/extract-large-paths-from-schema     schema [])})

(defn data-schema-marks
  "The fine-grained owner classification for a resource / mutation `spec`'s
  DATA value, as `{:sensitive {path decl} :large {path decl}}` rooted at the
  data root (`[]`) — the UNION of (a) the EP-0025 projection-relative
  `:sensitive` / `:large` declarations' `:data`-rooted paths (the canonical
  surface — `spec-declaration-marks`) and (b) the per-slot `:data-schema`
  `:sensitive?` / `:large?` props (the schema-natural co-declaration, EP-0015
  issue 11). Empty maps when the spec declares neither route. Pure (modulo the
  memoised shared walker)."
  [spec]
  (union-marks (schema-marks (:data-schema spec))
               (:data (spec-declaration-marks spec))))

(defn infinite-spec?
  "True iff `spec` declares an infinite feed (`:infinite true` — EP-0021 R1).
  The classification-local predicate behind `project-data`'s per-page
  (`:page-data-schema`) vs whole-value (`:data-schema`) branch. Mirrors
  `re-frame.resources.registry/infinite-resource?` but is defined HERE (a bare
  `(true? (:infinite spec))` read) because `registry` already requires
  `classification` — requiring it back would cycle (Spec 016 §Registration —
  :infinite)."
  [spec]
  (true? (:infinite spec)))

(defn page-data-schema-marks
  "The per-slot `:sensitive?` / `:large?` classification an infinite resource
  `spec`'s `:page-data-schema` declares for ONE PAGE value, as
  `{:sensitive {path decl} :large {path decl}}` rooted at the page root (`[]`).
  The per-page egress/classification contract (EP-0021 R5) — applied per page
  over the accumulated `:data` vector by `project-data`, NOT over the whole
  framework-owned page vector. Empty maps when no `:page-data-schema` or no
  marks. Pure (modulo the memoised shared walker)."
  [spec]
  (schema-marks (:page-data-schema spec)))

(defn params-schema-marks
  "The fine-grained owner classification for a resource / mutation `spec`'s
  PARAMS value, as `{:sensitive {path decl} :large {path decl}}` rooted at the
  params root (`[]`) — the UNION of (a) the EP-0025 projection-relative
  `:sensitive` / `:large` declarations' `:params`-rooted (and `:scope`-rooted)
  paths (the canonical surface — `spec-declaration-marks`) and (b) the per-slot
  `:params-schema` `:sensitive?` / `:large?` props (the schema-natural
  co-declaration, EP-0015 issue 11). The CO-EQUAL params counterpart to
  `data-schema-marks`. Spec 016 §Runtime-subsystem graduation clause 4:
  \"params, scopes, and data carry the same classification.\" Empty maps when
  neither route declares. Pure."
  [spec]
  (union-marks (schema-marks (:params-schema spec))
               (:params (spec-declaration-marks spec))))

(defn data-schema-classifies?
  "True iff `spec`'s `:data-schema` marks ANY slot `:sensitive?` or
  `:large?` (the fine-grained surface declares something). When true, a
  `:serialize` (non-whole-redacted) entry's data MUST still be projected
  through frame classification so the marked slots redact / elide — see
  `project-data`."
  [spec]
  (let [{:keys [sensitive large]} (data-schema-marks spec)]
    (boolean (or (seq sensitive) (seq large)))))

(defn params-schema-classifies?
  "True iff `spec`'s `:params-schema` marks ANY slot `:sensitive?` or
  `:large?`. When true, a `:serialize` entry's scoped-key PARAMS carry a
  fine-grained classification that must be honoured on the wire — see
  `project-params`."
  [spec]
  (let [{:keys [sensitive large]} (params-schema-marks spec)]
    (boolean (or (seq sensitive) (seq large)))))

;; ---------------------------------------------------------------------------
;; Data projection — owner per-slot marks + the merged frame `project-egress`.
;;
;; EP-0015 §6/§10/§11. The serialized data slice is projected in two
;; composed layers, both deferring to SHARED primitives (never a
;; family-private resource elider):
;;
;;   (a) the RESOURCE-OWNED per-slot `:data-schema` `:sensitive?` AND `:large?`
;;       marks (the canonical fine-grained owner surface, issue 11) project
;;       through the shared frame-independent `re-frame.classification/redact-with-paths`
;;       walker — `:sensitive?` slots redact to the `:rf/redacted` sentinel,
;;       `:large?` slots elide to the `:rf.size/large-elided` marker. This is
;;       the OWNER's declaration firing irrespective of frame app-db
;;       classification — a resource that marks `[:token]` sensitive in its
;;       `:data-schema` redacts that slot on SSR even when the frame's app-db
;;       classification says nothing about it, and a `[:body]` `:large?` slot
;;       elides on the wire whether or not the frame independently marks it
;;       (resource cache data does not live at a frame app-db path; rf2-260yhk).
;;       This is the SAME walker the CO-EQUAL `project-params` already uses for
;;       the params component of the key — one walker, two owner surfaces.
;;   (b) the data is THEN projected through the merged frame-owned
;;       `re-frame.projection/project-egress` (over `elide-wire-value`) under
;;       the boundary profile, so any path the FRAME ALSO classifies redacts /
;;       elides as defense-in-depth.
;;
;; "Sensitive wins over large" holds across both: at the same slot the owner
;; walker already resolves to the redaction sentinel (the walker's intrinsic
;; ordering), and the sentinel is a non-matchable scalar the frame walker
;; descends into nothing (idempotent under re-projection). The common
;; whole-resource-large case is still the coarse `:large?` root-prop omit
;; (`whole-entry-disposition` → `:omit`), so the durable whole-large entry
;; never reaches this serialize path at all; the per-slot owner `:large?` here
;; is the FINE-GRAINED complement for a `:serialize` entry with a large leaf.
;; ---------------------------------------------------------------------------

(defn- project-value
  "Project ONE owner-decoded `value` (a whole resource data value, or a SINGLE
  page of an infinite feed) for egress against `frame-id` / `boundary-profile`,
  applying the owner per-slot `marks` (`{:sensitive {path …} :large {path …}}`,
  rooted at the value root) in the two composed layers:

    (a) the owner `:sensitive?` / `:large?` per-slot marks project through the
        shared `re-frame.classification/redact-with-paths` walker (sensitive →
        `:rf/redacted`, large → `:rf.size/large-elided`, sensitive wins at a
        co-marked slot) — frame-independent;
    (b) the result is projected through the merged frame-owned
        `re-frame.projection/project-egress` under `boundary-profile` when a
        `frame-id` is present, so any path the FRAME classifies composes as
        defense-in-depth. A nil `frame-id` skips layer (b) (the owner
        classification is the authority — see `project-data`).

  No marks AND no frame → `value` rides unchanged. Pure."
  [value marks frame-id boundary-profile]
  (let [{:keys [sensitive large]} marks
        owner-projected (if (or (seq sensitive) (seq large))
                          (classification/redact-with-paths value (keys sensitive) (keys large))
                          value)]
    (if frame-id
      (projection/project-egress owner-projected
                                 {:frame             frame-id
                                  :rf.egress/profile boundary-profile})
      owner-projected)))

(defn project-data
  "Project a resource entry's / mutation instance's serialized DATA value for
  egress across `boundary-profile` (a `:rf.egress/*` profile, e.g.
  `:rf.egress/ssr-hydration` at the SSR boundary, `:rf.egress/off-box-tool`
  at a tool boundary) against `frame-id`'s classification and the resource
  `spec`'s OWNER per-slot classification (EP-0015 §6 / EP-0021 R5).

  THE single classification entry point for resource data egress — it branches
  on `spec`'s `:infinite` marker so the per-page (`:page-data-schema`) vs
  whole-value (`:data-schema`) contract is decided in one place (rather than a
  forked parallel projector):

  - **Ordinary resource** (`:infinite` absent / not true): `data` is the whole
    decoded value. The resource-owned `:data-schema` `:sensitive?` / `:large?`
    per-slot marks (`data-schema-marks`) project over it (layer (a)), then the
    merged frame-owned `project-egress` composes any frame-classified path
    (layer (b)) — the existing contract.

  - **Infinite feed** (`:infinite true` — EP-0021 R1/R5): `data` is the
    FRAMEWORK-OWNED VECTOR OF PAGES, and the per-page egress/classification
    contract is `:page-data-schema` (`page-data-schema-marks`), applied PER PAGE
    — each page is projected through the SAME two layers (owner marks + frame
    `project-egress`), and the page-vector SHAPE is preserved. `:data-schema` is
    NOT consulted for the accumulated vector (R5: the framework page vector must
    not be classified as if it were one app-decoded value, and a sensitive /
    large page field must not bypass per-page classification). A non-vector
    `data` on an infinite entry (an empty / not-yet-loaded feed whose `:data`
    is `[]`, or a nil) rides through the per-page mapping harmlessly.

  Both branches compose two SHARED primitives (never a family-private elider):

    (a) the owner per-slot `:sensitive?` AND `:large?` marks through the shared
        `re-frame.classification/redact-with-paths` walker — `:sensitive?` slots redact
        to the `:rf/redacted` sentinel, `:large?` slots elide to the
        `:rf.size/large-elided` marker (sensitive wins at a co-marked slot) —
        the OWNER's fine-grained declaration, firing regardless of frame app-db
        classification (the CO-EQUAL counterpart to `project-params`);
    (b) the merged frame-owned `re-frame.projection/project-egress` (over
        `elide-wire-value`) under `boundary-profile`, so any path the FRAME
        classifies composes as defense-in-depth.

  When `frame-id` is nil (a frameless / pure projection — a test harness or
  a tool with no resolvable frame), layer (b) is SKIPPED and the owner-projected
  data rides as-is: the coarse whole-entry disposition
  (`whole-entry-disposition`) is the resource-owned authority that governs
  redact / omit, so a frameless serialized entry must not be over-redacted to
  the frame walker's fail-closed sentinel merely because no frame scope is
  carried (that fail-closed posture is for app-db egress, not the
  resource-owned decision) — but the OWNER's own per-slot `:sensitive?` /
  `:large?` marks STILL fire (layer (a) is frame-independent). `spec` nil / no
  governing schema → layer (a) is a no-op. Pure."
  [data spec frame-id boundary-profile]
  (if (infinite-spec? spec)
    ;; INFINITE feed (EP-0021 R5): `data` is the framework-owned page VECTOR;
    ;; apply the per-page `:page-data-schema` contract PER PAGE, preserving the
    ;; vector shape. `:data-schema` is deliberately NOT consulted here.
    (let [page-marks (page-data-schema-marks spec)]
      (if (sequential? data)
        (mapv (fn [page] (project-value page page-marks frame-id boundary-profile))
              data)
        ;; a non-vector / nil `:data` (empty / not-yet-loaded feed) — project it
        ;; as a single value so a frame layer (b) still composes; per-page marks
        ;; rooted at the page root no-op against a non-page shape.
        (project-value data page-marks frame-id boundary-profile)))
    ;; ORDINARY resource: the existing whole-value `:data-schema` contract.
    (project-value data (data-schema-marks spec) frame-id boundary-profile)))

;; ---------------------------------------------------------------------------
;; Params projection — the CO-EQUAL fine-grained owner surface for the scoped
;; key (EP-0015 issue 11; Spec 016 clause 4 "params, scopes, and data carry
;; the same classification").
;;
;; The scoped resource key is `[scope resource-id canonical-params]`. On a
;; `:serialize` entry (no coarse whole-entry claim) the key rides VERBATIM on
;; the hydration wire — so without this, a params slot the owner marked
;; `:sensitive?` via `:params-schema` (e.g. `[:account-id {:sensitive? true}
;; :string]`) would ride RAW in the wire key even though it is the same
;; privacy class as the data. `project-params` applies the OWNER's per-slot
;; `:params-schema` marks to the params component of the key so a marked slot
;; redacts (`:sensitive?`) / elides (`:large?`) — the SAME walker, sentinel,
;; and ordering (`sensitive` wins) as `project-data` layer (a). It is
;; frame-INDEPENDENT (the owner declaration, not frame app-db classification —
;; resource params do not live at a frame app-db path), so it fires even
;; frameless. The COARSE whole-entry `:redact` / `:omit` dispositions still
;; replace the entire params component with an opaque content-addressed digest
;; (`re-frame.resources.ssr/project-scoped-key`) — this per-slot surface is
;; the fine-grained complement that fires on a `:serialize` key the coarse
;; claim leaves verbatim.
;; ---------------------------------------------------------------------------

(defn project-params
  "Project a resource entry's scoped-key PARAMS value for egress against the
  resource `spec`'s OWNER per-slot `:params-schema` marks (EP-0015 issue 11;
  Spec 016 clause 4). The CO-EQUAL counterpart to `project-data` for the
  params component of the scoped key:

    - each slot the `:params-schema` marks `:sensitive?` is redacted to the
      `:rf/redacted` sentinel via `re-frame.privacy/redact-paths`;
    - each slot it marks `:large?` is elided through the shared
      `re-frame.elision/elide-wire-value` walker to the
      `:rf.size/large-elided` marker.

  Both axes go through the SHARED frame-independent
  `re-frame.classification/redact-with-paths` walker (sensitive → `:rf/redacted`,
  large → `:rf.size/large-elided`, sensitive wins over large at the same
  slot) — the SAME walker the HTTP response-body surface
  (`re-frame.http.privacy-body/classify-decoded`) uses, never a
  resource-private walker. Frame-INDEPENDENT — the OWNER's declaration fires
  irrespective of any frame app-db classification (resource params do not
  live at a frame app-db path). `spec` nil / no `:params-schema` / no marks →
  `params` rides UNCHANGED (the coarse whole-entry disposition is the
  separate authority that redacts/omits the whole key — see
  `re-frame.resources.ssr/project-scoped-key`). Pure."
  [params spec]
  (let [{:keys [sensitive large]} (params-schema-marks spec)]
    (if (or (seq sensitive) (seq large))
      (classification/redact-with-paths params (keys sensitive) (keys large))
      params)))

;; ---------------------------------------------------------------------------
;; Invalid-params error-payload projection (rf2-99j4e4).
;;
;; `validate+canonicalize-params` (resource + mutation registries) throws
;; `:rf.error/resource-invalid-params` / `:rf.error/mutation-invalid-params`
;; on a `:params-schema` conformance failure, carrying the failing params under
;; `:params` and the registered explainer's output under `:error`. Both ride
;; PUBLIC thrown error data AND any downstream error-capture / logging / trace
;; egress (the AI-boundary + logs threat model). Without projection a params
;; slot the owner marked `:sensitive?` (e.g. `[:token {:sensitive? true} …]`)
;; would leak RAW whenever validation failed on a DIFFERENT, non-sensitive
;; sibling field — the same sibling-leak class the SSR `:serialize` key
;; (`project-params`) and the schema-validation hot-path traces
;; (`re-frame.schemas.validate/redact-tags`) already close.
;;
;; This routes the error payload through the SAME two shared primitives the
;; rest of the resources family egress uses — never a registry-private elider:
;;
;;   :params  — the CO-EQUAL fine-grained owner surface `project-params`:
;;              each `:params-schema` `:sensitive?` slot redacts to
;;              `:rf/redacted`, each `:large?` slot elides to the
;;              `:rf.size/large-elided` marker, plain slots ride verbatim
;;              (so the non-sensitive failing field stays diagnostic).
;;   :error   — the registered explainer's output (Malli's explanation carries
;;              the failing VALUE verbatim at its root `:value` and per-error
;;              `:value` slots), routed through THE shared schema-aware
;;              redaction seam `:schemas/redact-validation-tags` — the same one
;;              the boundary interceptor / cofx-value / machine-`:data` /
;;              sub-override / flow-output emit sites use. When the schema
;;              declares ANY `:sensitive?` slot the whole `:error` blob scrubs
;;              to `:rf/redacted` (a conforming sensitive sibling rides inside
;;              the whole explanation, so the whole-payload scrub is the
;;              correct scope — mirrors the schemas hot-path whole-payload
;;              decision); otherwise it rides verbatim.
;;
;; Schemas-optional: when the `:schemas/redact-validation-tags` hook is unbound
;; (schemas artefact absent) there is no schema to redact `:error` against and
;; the seam falls through verbatim — consistent with the no-validator path that
;; never produced an `:error` in the first place. `project-params` is
;; schema-hook-independent for the redact axis (it reads the owner's
;; `:params-schema` marks through the shared walker, the same one already
;; gating SSR egress), so the `:params` slot is projected regardless.
;; ---------------------------------------------------------------------------

(defn redact-invalid-params-error
  "Project the `:params` + `:error` (explainer output) slots of an invalid-params
  failure error payload against the resource / mutation `spec`'s OWNER per-slot
  `:params-schema` classification (rf2-99j4e4). Returns
  `{:params <projected> :error <projected-or-nil>}`:

    - `:params` is projected through `project-params` (the CO-EQUAL fine-grained
      owner surface — `:sensitive?` slots redact, `:large?` slots elide); and
    - `:error` (the explainer output, which carries the failing params VERBATIM
      under Malli's `:value` slots) is routed through THE shared schema-aware
      redaction seam `:schemas/redact-validation-tags`: the whole `:error` blob
      scrubs to `:rf/redacted` when `spec`'s `:params-schema` declares ANY
      `:sensitive?` slot, else rides verbatim.

  Same two shared primitives the resources family egress already uses (the SSR
  key projection + the schemas validation-failure redactor) — never a
  registry-private elider. `error` may be nil (no explainer registered); the
  `:error` key is then nil. Pure (modulo the memoised walker + the late-bound
  redaction hook)."
  [params error spec]
  (let [schema     (:params-schema spec)
        redact-fn  (late-bind/get-fn-cached :schemas/redact-validation-tags)
        ;; The explainer output carries the failing params verbatim; treat it as
        ;; the `:explain` value-bearing slot the shared seam scrubs whole-payload.
        redacted-e (if (and redact-fn (some? schema) (some? error))
                     (:explain (redact-fn schema {:explain error}))
                     error)]
    {:params (project-params params spec)
     :error  redacted-e}))
