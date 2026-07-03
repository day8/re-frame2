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

  ## Projection is registry-driven — the routing / machines standard model

  Classification NAMES the facts; the framework PROJECTS them at every
  egress boundary (SSR, tool, trace, epoch, observability) through the
  merged frame-owned `re-frame.projection/project-egress` over the shared
  `re-frame.elision/elide-wire-value` walker (EP-0015 §10/§11) — NEVER a
  family-private resource elider. The fine-grained per-slot declarations
  are LOWERED per instance into the per-frame elision registry
  (`reconcile-registry`), and the egress boundaries READ THAT REGISTRY: the
  SSR projector walks the entry's `:data` / scoped-key params through
  `project-egress` SEEDED AT THE ABSOLUTE OFFSET where the lowered decls
  live (`project-entry-data` / `project-entry-params`), so a declared slot
  redacts / elides with no second derivation from the spec. This is the
  resources peer of routing's `project-routing-egress` (the
  `[:rf.runtime/routing]`-offset slice walk) and machines'
  `project-snapshot-data` (the `frame-snapshot-classification` registry
  read) — one source of truth, all sources unioned at lookup.

  rf2-d3pku1: this REMOVED the redundant family-private `project-data` /
  `project-params` projectors, which re-derived `split-projection-paths
  spec` at egress — the SAME fact `reconcile-registry` already lowered into
  the registry (EP-0025: point the egress projection at the registry as
  the single source). The COARSE whole-entry disposition
  (`whole-entry-disposition`) remains the separate, frame-independent
  authority that gates redact / omit / serialize on the WHOLE entry.

  ## Sensitive wins over large

  A resource declared BOTH `:sensitive` and `:large` at the same slot
  redacts as SENSITIVE — the redaction sentinel is the more conservative
  shape (it still announces an entry exists, but emits no
  `:rf.size/large-elided` marker that could leak path / byte size / digest /
  fetch-handle). This is enforced once, at egress, by the shared elision
  walker's sensitive-before-large ordering (`re-frame.elision/walk`) — the
  registry read inherits it.

  ## What this slice does NOT touch

  - It introduces NO new public API surface (no facade export) — it is the
    internal owner-classification seam the SSR projection + the reply trace
    egress consume. The public classification surface is the projection-
    relative `:sensitive` / `:large` declarations on `reg-resource` /
    `reg-mutation`.
  - It does NOT change the Spec-Schemas schema-marker grammar (the
    `:sensitive?` / `:large?` Malli props are owned by the schemas artefact
    + Spec-Schemas — EP-0015 bead-plan item 9)."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.classification :as classification]
            [re-frame.elision :as elision]
            [re-frame.path :as path]
            [re-frame.projection :as projection]
            [re-frame.resources.state :as state]))

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

    :serialize  — ship the data slice (still registry-PROJECTED for any
                  per-slot declaration — see `project-entry-data`);
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
;; No derived-sensitivity propagation (EP-0025, rf2-71dr8t).
;;
;; A resource whose `:scope` is a `{:from-db <id>}` reference derives its cache
;; scope from the resolver's declared `:db` inputs. EARLIER (EP-0016 wave,
;; rf2-fi6tda.1) that derivation was a sensitivity PROPAGATION arm: a `:db`
;; input reading a frame-sensitive path made the derived scope (and the
;; resource's WHOLE durable entry) INHERIT `:sensitive` even when the owning
;; resource was not declared sensitive, honouring a closed
;; `:rf.egress/output-sensitivity` declassification enum.
;;
;; EP-0025 REMOVED all sensitivity propagation (subs / flows / this resource-
;; scope arm). Classification does not flow input -> output (Spec 015 §No
;; propagation, no taint). The inheritance pass (`resolver-derived-sensitive?` /
;; `scope-derived-sensitive?`) and the frame-aware `whole-entry-disposition-for`
;; are GONE: a resource's durable-entry disposition is the OWNER's coarse
;; root-prop claim ALONE (`whole-entry-disposition`), and its fine-grained
;; classification rides its OWN projection-relative `:sensitive` / `:large`
;; declarations (lowered per instance into the per-frame elision registry — see
;; the lowering section below). A genuinely-sensitive derived scope is declared
;; directly (`[:scope ...]` / `[:params ...]` sensitive); nothing is inherited
;; from the paths the resolver read.

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
;; component). `instance-declaration-paths` re-roots the `:data`-rooted paths
;; under the entry's absolute `:data` path and the `:params` / `:scope`-rooted
;; paths under the scoped-key carrier, writing them into the per-frame elision
;; registry (`reconcile-registry`) — the egress boundaries read them back from
;; there (`project-entry-data` / `project-entry-params`). A bare-rooted path (no
;; `:data` / `:params` head) defaults to the DATA projection — the common
;; `{:sensitive [[:ssn]]}` shorthand for a data field.
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
  (`reg-resource` / `reg-mutation`) throws `:rf.error/resource-bad-spec`
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
  `:large` map under each). The axis-split shape `instance-declaration-paths`
  re-roots when LOWERING the declarations into the per-frame elision registry
  (`reconcile-registry`). Empty when the spec declares neither axis. Pure /
  value-independent. Assumes the spec passed `classification-declaration-defect`."
  [spec]
  (let [s (split-projection-paths spec :sensitive)
        l (split-projection-paths spec :large)
        ->decl (fn [paths] (into {} (map (fn [p] [p {:source :owner-declaration}])) paths))]
    {:data   {:sensitive (->decl (:data s))   :large (->decl (:data l))}
     :params {:sensitive (->decl (:params s)) :large (->decl (:params l))}}))

;; ---------------------------------------------------------------------------
;; Schemas validate; they do NOT classify durably (EP-0025 rf2-fuqcob).
;;
;; A resource's `:data-schema` / `:params-schema` / per-page `:page-data-schema`
;; VALIDATES the value; it does NOT drive DURABLE egress classification. The
;; per-slot `:sensitive?` / `:large?` schema props survive ONLY for
;; VALIDATION-FAILURE-TRACE redaction (the validator's own transient egress
;; product — `redact-invalid-params-error`, via the shared
;; `:schemas/redact-validation-tags` seam) — NOT as a route into durable resource
;; data / params / scope classification (Spec 015 §Schemas describe shape, not
;; durable egress policy). The SINGLE durable classification surface is the
;; projection-relative `:sensitive` / `:large` path declarations on the spec
;; (`spec-declaration-marks`), LOWERED per instance into the per-frame elision
;; registry (`reconcile-registry`, the lowering section below) — one name per
;; fact, read back at egress through the SAME registry the routing / machines
;; subsystems read.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Egress projection — registry-driven, the routing / machines standard model
;; (EP-0025, rf2-d3pku1 — collapsing the redundant family-private re-derivation).
;;
;; A resource's projection-relative `:sensitive` / `:large` declarations are the
;; SINGLE source of truth, and `reconcile-registry` (below) LOWERS them per
;; instance into the per-frame elision registry at the entry's ABSOLUTE
;; runtime-db path (`[:rf.runtime/resources :entries <key-id> :data …]` for data,
;; the scoped-key `:resource/key` carrier for params/scope) under
;; `:source :resource`. The egress boundaries READ THAT REGISTRY — they do NOT
;; re-derive the declaration from the spec a second time at projection.
;;
;; This makes resources a peer of:
;;   - routing's `re-frame.ssr.payload-policy/project-routing-egress`, which
;;     walks the `:rf.runtime/routing` slice through `project-egress` seeded at
;;     `:path [:rf.runtime/routing]` so the registry's absolute re-rooted route
;;     paths match; and
;;   - machines' `re-frame.machines.ssr/project-snapshot-data`, which reads the
;;     lowered snapshot declarations back from the registry
;;     (`re-frame.classification/frame-snapshot-classification`).
;;
;; EP-0025 §"Point the egress projection at the registry as the single source":
;; the prior resources family-private `project-data` / `project-params`
;; projectors re-derived `split-projection-paths spec` at egress — the SAME fact
;; `reconcile-registry` already lowered into the registry. That dual derivation
;; (rf2-d3pku1) is REMOVED here in favour of the registry read.
;;
;; The registry-driven walk is frame-SCOPED (the registry lives in the live
;; frame's runtime-db). A nil / unresolvable / never-classified frame rides the
;; value VERBATIM — the precise, fail-open-frameless posture machines
;; (`project-snapshot-data` nil-frame ride) and routing (`project-routing-egress`
;; no-live-frame ride) both take. The COARSE whole-entry `:redact` / `:omit`
;; dispositions (`whole-entry-disposition`) remain the separate authority that
;; replaces the WHOLE data / key irrespective of frame — they are NOT a
;; per-slot path concern and do not flow through this registry walk.
;; ---------------------------------------------------------------------------

(defn- registry-classifies-under?
  "True iff `frame-id`'s per-frame elision registry carries ANY `:sensitive` or
  `:large` declaration whose absolute path begins with `prefix` (the entry's
  `:data` / scoped-key offset). A cheap registry membership read used to GATE the
  egress walk: when nothing is declared under the offset, the value rides
  VERBATIM (the walker would otherwise reconstruct collections and collapse a
  list↔vector byte-identity — Spec 016 §Resource identity; the cache-key-identity
  round-trip). Precise, not a blanket scrub — the same posture routing's
  `unclassified-route-slice-rides-verbatim` takes. Pure read."
  [frame-id prefix]
  (let [n     (count prefix)
        under (fn [decls]
                (some (fn [p]
                        (and (>= (count p) n) (= prefix (subvec (vec p) 0 n))))
                      (keys decls)))]
    (boolean (or (under (elision/sensitive-declarations frame-id))
                 (under (elision/declarations frame-id))))))

(defn project-entry-data
  "Project a resource entry's serialized DATA value for egress across
  `boundary-profile` (a `:rf.egress/*` profile, e.g. `:rf.egress/ssr-hydration`)
  against `frame-id`'s per-frame elision registry — the REGISTRY-DRIVEN egress
  read (EP-0025, rf2-d3pku1). The resource's projection-relative `:data`
  declarations were lowered into the registry at the entry's absolute runtime-db
  `:data` path (`[:rf.runtime/resources :entries <key-id> :data …]`,
  `reconcile-registry`); here we walk the bare `:data` value through
  `re-frame.projection/project-egress` SEEDED AT THAT ABSOLUTE OFFSET (`:path
  [… <key-id> :data]`), so the candidate declaration-coordinate set starts where
  the lowered `:source :resource` decls live and the declared slots redact
  (`:sensitive` → `:rf/redacted`) / elide (`:large` → `:rf.size/large-elided`).
  Any path the FRAME ALSO classifies composes as defense-in-depth (the same
  registry, all sources unioned at lookup).

  INFINITE feeds ride for free: the lowered decl is `[… :data :token]`, the
  runtime `:data` is the page VECTOR, and `elide-wire-value`'s index-free fork
  (`fork-index-paths`) matches the index-free decl against the indexed runtime
  path `[… :data <page-idx> :token]` on EVERY page — no special per-page branch.

  GATED on a registry declaration under the entry's `:data` offset
  (`registry-classifies-under?`): when NOTHING is declared there the value rides
  VERBATIM (reference-preserving — the walker would otherwise reconstruct
  collections; the precise posture routing / the cache-key-identity contract
  require). Frame-SCOPED: a nil / unresolvable `frame-id` rides `data` VERBATIM
  (the registry is unreachable without a live frame, and the coarse whole-entry
  disposition is the separate frame-independent authority). `key-id` is the
  entry's CEDN-1 byte key-id. Pure w.r.t. the value (reads the live frame's
  registry)."
  [data key-id frame-id boundary-profile]
  (let [data-prefix (conj (state/entry-path-by-id key-id) :data)]
    (if (and frame-id (registry-classifies-under? frame-id data-prefix))
      (projection/project-egress
        data
        {:frame             frame-id
         :path              data-prefix
         :rf.egress/profile boundary-profile})
      data)))

(defn project-entry-params
  "Project a resource entry's scoped-key PARAMS value for egress against
  `frame-id`'s per-frame elision registry — the CO-EQUAL params counterpart to
  `project-entry-data` (EP-0025, rf2-d3pku1; Spec 016 clause 4 \"params, scopes,
  and data carry the same classification\"). The resource's projection-relative
  `:params` (and `:scope`) declarations were lowered into the registry at the
  scoped key's absolute `:resource/key` carrier (`[… :resource/key 2 …]` for
  params, `[… :resource/key 0 …]` for scope, `reconcile-registry`). The scoped
  key is `[scope resource-id params]`, so the params component sits at index 2 of
  that carrier; here we walk the bare params value through
  `re-frame.projection/project-egress` SEEDED at the params offset
  (`[… :resource/key 2]`) so the lowered `:source :resource` decls match — a
  marked slot redacts / elides without the raw value riding in the wire key.

  Used on a `:serialize` entry (no coarse whole-entry claim) — the COARSE
  `:redact` / `:omit` dispositions still replace the WHOLE params component with
  an opaque content-addressed digest (`re-frame.resources.ssr/project-scoped-key`),
  irrespective of frame. GATED on a registry declaration under the params offset
  (`registry-classifies-under?`): when nothing is declared there the params ride
  VERBATIM (reference / byte-identity preserving — the scoped key is a CEDN-1
  byte-keyed identity, so a list↔vector collapse from an unnecessary walk would
  break the cache-key-identity round-trip). Frame-SCOPED: a nil / unresolvable
  `frame-id` rides `params` VERBATIM. `key-id` is the entry's CEDN-1 byte key-id.
  Pure w.r.t. the value."
  [params key-id frame-id boundary-profile]
  (let [params-prefix (conj (state/entry-path-by-id key-id) :resource/key 2)]
    (if (and frame-id (registry-classifies-under? frame-id params-prefix))
      (projection/project-egress
        params
        {:frame             frame-id
         :path              params-prefix
         :rf.egress/profile boundary-profile})
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
;; (`project-entry-params`) and the schema-validation hot-path traces
;; (`re-frame.schemas.validate/redact-tags`) already close.
;;
;; This routes the error payload through the SAME two shared primitives the
;; rest of the resources family egress uses — never a registry-private elider:
;;
;;   :params  — the SCHEMA-PROP per-slot redaction (the validator's own failure
;;              product): each `:params-schema` `:sensitive?` slot redacts to
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
;; never produced an `:error` in the first place.
;;
;; EP-0025 (rf2-fuqcob): the `:params-schema` per-slot `:sensitive?` / `:large?`
;; props no longer drive DURABLE classification (the durable wire/SSR/tool egress
;; reads only the projection-relative declarations, lowered into the registry —
;; `project-entry-params`). But the VALIDATION-FAILURE-TRACE redaction is the
;; schema's OWN surviving egress product (Spec 015 §Schemas describe shape: "for
;; a slot's own validation-failure-trace redaction the schema produces that
;; record, so it owns its egress shape"). So this seam reads the schema PROPS
;; directly (via the shared walker hooks) to redact the failing `:params`
;; per-slot — distinct from the durable registry route. That keeps the schema-
;; prop route alive exactly where it is legitimate (the validator's own product),
;; while the durable wire/SSR/tool egress is governed solely by the projection-
;; relative declarations.
;; ---------------------------------------------------------------------------

(defn- validation-failure-params-marks
  "The per-slot `:sensitive?` / `:large?` props a `:params-schema` declares, as
  `{:sensitive {path decl} :large {path decl}}` rooted at the params root — the
  SCHEMA-PROP marks, read via the shared late-bound walker hooks. Used ONLY by
  `redact-invalid-params-error` to redact the failing params in the VALIDATION-
  FAILURE-TRACE (the schema's own egress product, the surviving schema-prop
  route per EP-0025 / Spec 015 §Schemas describe shape) — NOT a durable
  classification route. Empty maps when the schema is nil / unschematic / the
  walker hooks are unbound. Pure (modulo the memoised walker)."
  [schema]
  (let [extract (fn [hook]
                  (if-let [f (and schema (late-bind/get-fn-cached hook))]
                    (or (f schema []) {})
                    {}))]
    {:sensitive (extract :schemas/extract-sensitive-paths-from-schema)
     :large     (extract :schemas/extract-large-paths-from-schema)}))

(defn redact-invalid-params-error
  "Project the `:params` + `:error` (explainer output) slots of an invalid-params
  failure error payload against the resource / mutation `spec`'s `:params-schema`
  per-slot `:sensitive?` / `:large?` VALIDATION props (rf2-99j4e4). Returns
  `{:params <projected> :error <projected-or-nil>}`:

    - `:params` per-slot redaction reads the `:params-schema` `:sensitive?` /
      `:large?` props directly (`validation-failure-params-marks`): each
      `:sensitive?` slot redacts to `:rf/redacted`, each `:large?` slot elides
      to the `:rf.size/large-elided` marker, plain slots ride verbatim (so the
      non-sensitive failing field stays diagnostic);
    - `:error` (the explainer output, which carries the failing params VERBATIM
      under Malli's `:value` slots) is routed through THE shared schema-aware
      redaction seam `:schemas/redact-validation-tags`: the whole `:error` blob
      scrubs to `:rf/redacted` when `spec`'s `:params-schema` declares ANY
      `:sensitive?` slot, else rides verbatim.

  EP-0025 (rf2-fuqcob): this is the schema's OWN VALIDATION-FAILURE-TRACE egress
  product, the surviving schema-prop redaction route (Spec 015 §Schemas describe
  shape) — distinct from the DURABLE wire/SSR/tool egress, which the registry-
  driven `project-entry-params` governs via the projection-relative declarations
  alone (schema props no longer classify durably). `error` may be nil (no
  explainer registered); the `:error` key is then nil. Pure (modulo the memoised walker + the late-bound redaction
  hook)."
  [params error spec]
  (let [schema     (:params-schema spec)
        redact-fn  (late-bind/get-fn-cached :schemas/redact-validation-tags)
        {:keys [sensitive large]} (validation-failure-params-marks schema)
        ;; The failing params slot is the validator's own failure product — redact
        ;; the schema's `:sensitive?` / `:large?` slots per-slot (the surviving
        ;; schema-prop route), keeping the non-sensitive failing field diagnostic.
        params'    (if (or (seq sensitive) (seq large))
                     (classification/redact-with-paths params (keys sensitive) (keys large))
                     params)
        ;; The explainer output carries the failing params verbatim; treat it as
        ;; the `:explain` value-bearing slot the shared seam scrubs whole-payload.
        redacted-e (if (and redact-fn (some? schema) (some? error))
                     (:explain (redact-fn schema {:explain error}))
                     error)]
    {:params params'
     :error  redacted-e}))

;; ---------------------------------------------------------------------------
;; Per-instance lowering into the per-frame elision registry (EP-0025
;; §subsystems, rf2-v8x9n8 — aligning resources to the machines / routing
;; standard model).
;;
;; Per the EP-0025 subsystem matrix a resource declares its `:sensitive` /
;; `:large` PROJECTION-RELATIVE to the entry projection (its `:params` / `:data`)
;; and the runtime LOWERS the declaration into the SAME per-frame elision
;; registry (`[:rf.runtime/elision …]`) PER INSTANCE — re-rooting each declared
;; projection-relative path to the instance's ABSOLUTE runtime path and writing
;; it `{:source :resource}` — exactly as machines lower at spawn
;; (`re-frame.machines.classification/lower-at-spawn!` →
;; `[:rf.runtime/machines :snapshots <actor-id> :data …]`) and routes lower at
;; activation (`re-frame.routing.classification/lower-for-route` →
;; `[:rf.runtime/routing :current …]`). BEFORE rf2-v8x9n8 resources DID NOT
;; lower: the marks were applied DIRECTLY at the family-private
;; `project-data` / `project-params` projectors, so a generic registry reader
;; (Xray "what is classified", an MCP registry view, the SSR registry-projection
;; defence-in-depth) never SAW resource classification. The lowering put it in
;; the registry; rf2-d3pku1 then COLLAPSED the redundant family-private
;; projectors into the registry read (`project-entry-data` /
;; `project-entry-params` walk the entry value through `project-egress` seeded at
;; the lowered absolute path), so the registry is now the SINGLE source the
;; egress reads — the "union every source" model the spec sells, uniform across
;; all four subsystems.
;;
;; The lowering is a PURE runtime-db transform (`reconcile-registry`, mirroring
;; routing's `apply-route-classification`) so the durable runtime-db transition
;; that writes / evicts entries folds the registry update into the SAME atomic
;; `:rf.db/runtime` commit. It is VALUE-INDEPENDENT (the declared path redacts
;; whatever later occupies the slot) and IDEMPOTENT + SELF-DROPPING: it derives
;; the full `:source :resource` declaration set from the CURRENT `:entries`, so
;; an evicted entry's declarations vanish (the per-instance teardown the matrix
;; names) with no separate drop hook, and other-sourced entries (`:effect` /
;; `:flow` / `:route` / `:machine`) are left untouched.
;;
;; Absolute re-rooting (per instance, keyed on the entry's byte `key-id`):
;;   - a `:data`-rooted declared path `[:k …]`   → `[:rf.runtime/resources
;;                                                  :entries <key-id> :data :k …]`
;;     (the durable entry's `:data` value lives at `… <key-id> :data`);
;;   - a `:params` / `:scope`-rooted declared path → the entry's scoped-key
;;     `:resource/key` carrier at `… <key-id> :resource/key`. The scoped key is
;;     the vector `[scope resource-id params]`, so a `:params`-rooted path
;;     indexes position 2 and a `:scope`-rooted path position 0.
;; ---------------------------------------------------------------------------

(def ^:private resource-source
  "The elision-registry `:source` tag a resource-lowered declaration carries —
  the resources peer of `:source :machine` / `:source :route` / `:source
  :effect`. Source-scoped so reconciliation / eviction touch only resource-
  sourced entries."
  :resource)

(def ^:private elision-registry-key :rf.runtime/elision)

(defn- entry-data-prefix
  "The ABSOLUTE runtime-db prefix for an entry's `:data` value, keyed on the
  byte `key-id` — `[:rf.runtime/resources :entries <key-id> :data]`."
  [key-id]
  (conj (state/entry-path-by-id key-id) :data))

(defn- entry-key-prefix
  "The ABSOLUTE runtime-db prefix for an entry's scoped-key `:resource/key`
  carrier — `[:rf.runtime/resources :entries <key-id> :resource/key]`. The
  scoped key is `[scope resource-id params]`, so a `:params`-rooted declared
  path re-roots under index 2, a `:scope`-rooted one under index 0."
  [key-id]
  (conj (state/entry-path-by-id key-id) :resource/key))

(defn- instance-declaration-paths
  "The ABSOLUTE `{:sensitive [paths] :large [paths]}` runtime-db paths a single
  entry contributes, re-rooting `spec`'s projection-relative declarations under
  the entry's byte `key-id`. `:data`-rooted paths re-root under the entry's
  `:data`; `:params`-rooted paths re-root under the scoped key's params (index
  2 of `:resource/key`); `:scope`-rooted paths under the scope (index 0). Pure;
  returns `{:sensitive [] :large []}` when the spec declares neither axis."
  [key-id spec]
  (let [s     (split-projection-paths spec :sensitive)
        l     (split-projection-paths spec :large)
        data-prefix (entry-data-prefix key-id)
        key-prefix  (entry-key-prefix key-id)
        ;; a `:params`-rooted path was stripped of its `:params` head in
        ;; `split-projection-paths`; the scoped key holds params at index 2 and
        ;; scope at index 0, so re-root accordingly. A `:scope`-rooted path was
        ;; kept WITH its `[:scope …]` head, so strip it and target index 0.
        ->key-abs (fn [p]
                    (if (= :scope (first p))
                      (into (conj key-prefix 0) (subvec (vec p) 1))
                      (into (conj key-prefix 2) p)))
        ->abs     (fn [axis]
                    (into (mapv #(into data-prefix %) (:data axis))
                          (mapv ->key-abs (:params axis))))]
    {:sensitive (->abs s)
     :large     (->abs l)}))

(defn- without-resource-sourced
  "Drop `:source :resource` entries from a `{path decl}` declaration map,
  preserving every other-sourced entry (`:effect` / `:flow` / `:route` /
  `:machine` / `:owner-declaration`). Returns `{}` for nil."
  [decls]
  (reduce-kv (fn [acc p decl]
               (if (= resource-source (:source decl))
                 acc
                 (assoc acc p decl)))
             {}
             (or decls {})))

(defn- with-resource-paths
  "Overlay `:source :resource` declarations for the absolute `paths` onto the
  carried (non-resource-sourced) declaration map WITHOUT clobbering a foreign-
  source standing entry on the same absolute path (the registry is one entry-
  per-path-per-axis; an app-effect / flow / route claim on the same path is left
  standing — the same posture machines' `apply-decls` SET uses)."
  [carried paths]
  (reduce (fn [acc p]
            (if (contains? acc p)
              acc
              (assoc acc p {:source resource-source})))
          carried
          paths))

(defn reconcile-registry
  "PURE per-instance lowering: given a base `runtime-db` value and a `spec-of`
  resolver `(fn [resource-id] -> spec|nil)`, return the runtime-db with the
  `[:rf.runtime/elision …]` registry's `:source :resource` entries REPLACED by
  the declarations of EVERY current cache entry (re-rooted absolute per the
  entry's byte `key-id`). The standard EP-0025 lowering — the resources peer of
  `re-frame.routing.classification/apply-route-classification` and
  `re-frame.machines.classification/lower-at-spawn!` (rf2-v8x9n8).

  Operates on a VALUE so a resource handler's durable `:rf.db/runtime`
  transition folds the registry update into the SAME atomic commit that
  writes / evicts the entry. IDEMPOTENT + SELF-DROPPING: the full resource-
  sourced set is rebuilt from the current `:entries`, so an evicted entry's
  declarations vanish (the per-instance teardown) and a newly-minted entry's
  appear, with no separate drop hook. Value-INDEPENDENT (the declared path
  redacts whatever later occupies the slot). Other-sourced registry entries
  (`:effect` / `:flow` / `:route` / `:machine`) ride untouched and union at
  egress-lookup time.

  `spec-of` is injected (resources' `registry` requires `classification`, so a
  static back-require would cycle) — the caller (a resource event handler in
  `re-frame.resources.events`) passes `registry/resource-meta`. A resource id
  with no registered spec, or a spec that declares no classification, contributes
  nothing.

  Reconcile-aware (router `re-frame.elision/reconcile-runtime-db-effect`): when
  the lowering touched the registry (the base carried resource-sourced entries
  or the current entries declare some), it emits an EXPLICIT
  `:rf.runtime/elision` key (possibly cleared of all resource entries) so a
  whole-value runtime-db commit honours an eviction's drop verbatim rather than
  resurrecting the prior registry. The common no-classification-ever case emits
  no key."
  [runtime-db spec-of]
  (let [base    (or runtime-db {})
        entries (get-in base (state/entries-path))
        reg     (get base elision-registry-key)
        carry-s (without-resource-sourced (:sensitive-declarations reg))
        carry-l (without-resource-sourced (:declarations reg))
        ;; gather every current entry's absolute declaration paths
        {:keys [sensitive large]}
        (reduce-kv
          (fn [acc key-id entry]
            (let [resource-id (second (:resource/key entry))
                  spec        (when resource-id (spec-of resource-id))]
              (if-not spec
                acc
                (let [{:keys [sensitive large]} (instance-declaration-paths key-id spec)]
                  (-> acc
                      (update :sensitive into sensitive)
                      (update :large into large))))))
          {:sensitive [] :large []}
          (or entries {}))
        new-s   (with-resource-paths carry-s sensitive)
        new-l   (with-resource-paths carry-l large)
        new-reg (cond-> {}
                  (seq new-s) (assoc :sensitive-declarations new-s)
                  (seq new-l) (assoc :declarations new-l))]
    (cond
      (seq new-reg)
      (assoc base elision-registry-key new-reg)

      ;; emptied, but the base carried a registry → emit the explicit clear so
      ;; the router's reconcile honours an eviction's drop verbatim.
      (contains? base elision-registry-key)
      (assoc base elision-registry-key {})

      ;; nothing ever classified → leave the key absent (no stray sub-tree).
      :else base)))
