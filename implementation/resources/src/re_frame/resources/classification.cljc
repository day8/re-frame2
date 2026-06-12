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
            [re-frame.marks :as marks]
            [re-frame.path :as path]
            [re-frame.privacy :as privacy]
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
  any `:source :marks` / `:source :flow` entries; `re-frame.elision/`
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
;; Per-slot owner classification — the canonical fine-grained surface.
;;
;; EP-0015 issue 11 (ruled): per-slot `:sensitive?` / `:large?` props on the
;; owner's `:data-schema` / `:params-schema` are the canonical fine-grained
;; surface (the EP-0005 mechanism). They are extracted through the SHARED
;; schema walker hooks (`:schemas/extract-sensitive-paths-from-schema` /
;; `:schemas/extract-large-paths-from-schema`) — the same walker
;; `re-frame.elision` consumes for app-schema slots — never a resource-local
;; re-implementation. An inline schema form contributes its marks; a keyword
;; registry ref is an opaque leaf (the walker's documented limitation, shared
;; with every other schema-mark consumer).
;; ---------------------------------------------------------------------------

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
  "The per-slot `:sensitive?` / `:large?` classification a resource /
  mutation `spec`'s `:data-schema` declares for its DATA value, as
  `{:sensitive {path decl} :large {path decl}}` rooted at the data root
  (`[]`). The fine-grained owner surface (EP-0015 issue 11). Empty maps when
  no `:data-schema` or no marks. Pure (modulo the memoised shared walker)."
  [spec]
  (schema-marks (:data-schema spec)))

(defn params-schema-marks
  "The per-slot `:sensitive?` / `:large?` classification a resource /
  mutation `spec`'s `:params-schema` declares for its PARAMS value, as
  `{:sensitive {path decl} :large {path decl}}` rooted at the params root
  (`[]`). The CO-EQUAL fine-grained owner surface to `data-schema-marks`
  (EP-0015 issue 11, ruled: `:params-schema` is a canonical fine-grained
  surface alongside `:data-schema`) — extracted through the SAME shared
  walker, so a `[:account-id {:sensitive? true} :string]` params slot is
  classified identically to a data slot. Spec 016 §Runtime-subsystem
  graduation clause 4: \"params, scopes, and data carry the same
  classification.\" Empty maps when no `:params-schema` or no marks. Pure."
  [spec]
  (schema-marks (:params-schema spec)))

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
;;   (a) the RESOURCE-OWNED per-slot `:data-schema` `:sensitive?` marks (the
;;       canonical fine-grained owner surface, issue 11) redact their slots
;;       to the `:rf/redacted` sentinel via the shared
;;       `re-frame.privacy/redact-paths`. This is the OWNER's declaration
;;       firing irrespective of frame app-db classification — a resource that
;;       marks `[:token]` sensitive in its `:data-schema` redacts that slot on
;;       SSR even when the frame's app-db classification says nothing about it
;;       (resource cache data does not live at a frame app-db path).
;;   (b) the data is THEN projected through the merged frame-owned
;;       `re-frame.projection/project-egress` (over `elide-wire-value`) under
;;       the boundary profile, so any path the FRAME ALSO classifies redacts /
;;       elides as defense-in-depth.
;;
;; "Sensitive wins over large" holds across both: a slot the owner marks
;; sensitive is already the redaction sentinel before (b) runs, and the
;; sentinel is a non-matchable scalar the walker descends into nothing
;; (idempotent under re-projection). Per-slot `:large?` owner marks compose
;; through (b) when the frame also declares the path large; the common
;; whole-resource-large case is the coarse `:large?` root-prop omit
;; (`whole-entry-disposition` → `:omit`), so the durable large entry never
;; reaches this serialize path at all.
;; ---------------------------------------------------------------------------

(defn project-data
  "Project a resource entry's / mutation instance's serialized DATA value for
  egress across `boundary-profile` (a `:rf.egress/*` profile, e.g.
  `:rf.egress/ssr-hydration` at the SSR boundary, `:rf.egress/off-box-tool`
  at a tool boundary) against `frame-id`'s classification and the resource
  `spec`'s OWNER per-slot `:data-schema` marks (EP-0015 §6).

  Two composed layers, both shared primitives:

    (a) the resource-owned `:data-schema` `:sensitive?` per-slot marks
        (`data-schema-marks`) redact their slots to the `:rf/redacted`
        sentinel via `re-frame.privacy/redact-paths` — the OWNER's fine-grained
        declaration, firing regardless of frame app-db classification;
    (b) the result is projected through the merged frame-owned
        `re-frame.projection/project-egress` (over `elide-wire-value`) under
        `boundary-profile`, so any path the FRAME classifies composes as
        defense-in-depth.

  When `frame-id` is nil (a frameless / pure projection — a test harness or
  a tool with no resolvable frame), layer (b) is SKIPPED and the owner-redacted
  data rides as-is: the coarse whole-entry disposition
  (`whole-entry-disposition`) is the resource-owned authority that governs
  redact / omit, so a frameless serialized entry must not be over-redacted to
  the frame walker's fail-closed sentinel merely because no frame scope is
  carried (that fail-closed posture is for app-db egress, not the
  resource-owned decision) — but the OWNER's own `:data-schema` sensitive marks
  STILL fire (layer (a) is frame-independent). `spec` nil / no data-schema →
  layer (a) is a no-op. Pure."
  [data spec frame-id boundary-profile]
  (let [sensitive-paths (keys (:sensitive (data-schema-marks spec)))
        owner-redacted  (if (seq sensitive-paths)
                          (privacy/redact-paths data sensitive-paths)
                          data)]
    (if frame-id
      (projection/project-egress owner-redacted
                                 {:frame             frame-id
                                  :rf.egress/profile boundary-profile})
      owner-redacted)))

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
  `re-frame.marks/redact-with-paths` walker (sensitive → `:rf/redacted`,
  large → `:rf.size/large-elided`, sensitive wins over large at the same
  slot) — the SAME walker the HTTP response-body surface
  (`re-frame.http-privacy-body/classify-decoded`) uses, never a
  resource-private walker. Frame-INDEPENDENT — the OWNER's declaration fires
  irrespective of any frame app-db classification (resource params do not
  live at a frame app-db path). `spec` nil / no `:params-schema` / no marks →
  `params` rides UNCHANGED (the coarse whole-entry disposition is the
  separate authority that redacts/omits the whole key — see
  `re-frame.resources.ssr/project-scoped-key`). Pure."
  [params spec]
  (let [{:keys [sensitive large]} (params-schema-marks spec)]
    (if (or (seq sensitive) (seq large))
      (marks/redact-with-paths params (keys sensitive) (keys large))
      params)))
