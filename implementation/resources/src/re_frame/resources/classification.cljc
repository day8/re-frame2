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
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.projection :as projection]))

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

(defn data-schema-marks
  "The per-slot `:sensitive?` / `:large?` classification a resource /
  mutation `spec`'s `:data-schema` declares for its DATA value, as
  `{:sensitive {path decl} :large {path decl}}` rooted at the data root
  (`[]`). The fine-grained owner surface (EP-0015 issue 11). Empty maps when
  no `:data-schema` or no marks. Pure (modulo the memoised shared walker)."
  [spec]
  (let [schema (:data-schema spec)]
    {:sensitive (extract-paths :schemas/extract-sensitive-paths-from-schema schema [])
     :large     (extract-paths :schemas/extract-large-paths-from-schema     schema [])}))

(defn data-schema-classifies?
  "True iff `spec`'s `:data-schema` marks ANY slot `:sensitive?` or
  `:large?` (the fine-grained surface declares something). When true, a
  `:serialize` (non-whole-redacted) entry's data MUST still be projected
  through frame classification so the marked slots redact / elide — see
  `project-data`."
  [spec]
  (let [{:keys [sensitive large]} (data-schema-marks spec)]
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
