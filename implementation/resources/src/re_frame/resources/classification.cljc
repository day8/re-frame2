(ns re-frame.resources.classification
  "Resource and mutation owner classification. See Spec 015 §Resource and
  mutation durable classification and Spec 016 §SSR and hydration.

  ## Ownership

  A `reg-resource` / `reg-mutation` declaration creates DURABLE
  runtime-subsystem state (cache entries under `:rf.runtime/resources`,
  scoped keys, params, data, errors, refresh errors, work-ledger summaries;
  mutation instances under `:rf.runtime/mutations` carrying params / result
  / error). Those shapes are owned by the resource or mutation definition.
  They are durable runtime-subsystem state, not transient registration
  payloads.

  Fine-grained durable classification is declared by projection-relative
  `:sensitive` / `:large` paths on the resource or mutation spec. The runtime
  lowers those paths per instance into the frame elision registry, and every
  durable egress boundary reads that registry. Schema `:sensitive?` / `:large?`
  props govern validation-failure redaction only; they do not classify durable
  state. Coarse whole-entry `:sensitive?` / `:large?` claims remain the root
  case, where the whole resource is the classification unit.

  ## Projection is registry-driven — the routing / machines standard model

  Classification NAMES the facts; the framework PROJECTS them at every
  egress boundary (SSR, tool, trace, epoch, observability) through the
  merged frame-owned `re-frame.projection/project-egress` over the shared
  `re-frame.elision/elide-wire-value` walker — NEVER a
  family-private resource elider. The fine-grained per-slot declarations
  are LOWERED per instance into the per-frame elision registry
  (`reconcile-registry`), and the egress boundaries READ THAT REGISTRY: the
  SSR projector walks the entry's `:data` and BOTH classification-bearing
  components of its scoped key through `project-egress` SEEDED AT THE ABSOLUTE
  OFFSET where the lowered decls live (`project-entry-data`,
  `project-entry-scope` at `:resource/key` index 0, `project-entry-params` at
  index 2), so a declared slot
  redacts / elides with no second derivation from the spec. This is the
  resources peer of routing's `project-routing-egress` (the
  `[:rf.runtime/routing]`-offset slice walk) and machines'
  `project-snapshot-data` (the `frame-snapshot-classification` registry
  read) — one source of truth, all sources unioned at lookup.

  The coarse whole-entry disposition (`whole-entry-disposition`) remains the
  separate, frame-independent authority that gates redact / omit / serialize
  on the whole entry.

  ## Sensitive wins over large

  A resource declared BOTH `:sensitive` and `:large` at the same slot
  redacts as SENSITIVE — the redaction sentinel is the more conservative
  shape (it still announces an entry exists, but emits no
  `:rf.size/large-elided` marker that could leak path / byte size / digest /
  fetch-handle). This is enforced once, at egress, by the shared elision
  walker's sensitive-before-large ordering (`re-frame.elision/walk`) — the
  registry read inherits it.

  This is an internal seam used by SSR, tooling, trace egress, and event-time
  registry reconciliation. The public surface is the projection-relative
  declaration on `reg-resource` / `reg-mutation`."
  (:require [re-frame.late-bind :as rf.late-bind]
            [re-frame.classification :as rf.classification]
            [re-frame.elision :as rf.elision]
            [re-frame.path :as rf.path]
            [re-frame.projection :as rf.projection]
            [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
            [re-frame.resources.state :as rf.resources.state]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Whole-entry disposition — the coarse root-prop owner classification.
;;
;; Coarse whole-entry `:sensitive?` / `:large?` claims are the degenerate
;; root-prop case (the WHOLE
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
  Spec 015 §Resource and mutation durable classification."
  [spec]
  (cond
    (:sensitive? spec) :redact
    (:large? spec)     :omit
    :else              :serialize))

;; ---------------------------------------------------------------------------
;; No derived-sensitivity propagation.
;;
;; Classification does not flow from a scope resolver's inputs to its output
;; (Spec 015 §No propagation, no taint). A resource's whole-entry disposition
;; comes only from its owner declaration. A sensitive derived scope must be
;; declared directly with a projection-relative `[:scope ...]` or
;; `[:params ...]` path.

;; ---------------------------------------------------------------------------
;; A resource / mutation DEFINITION declares its sensitive / large slots
;; PROJECTION-RELATIVE to the instance projection (the matrix root: entry's
;; `:params` / `:data`), via top-level `:sensitive` / `:large` vectors on the
;; `reg-resource` / `reg-mutation` spec:
;;
;;   (rf/reg-resource :user-profile
;;     {:sensitive [[:data :ssn]] :large [[:data :avatar-bytes]]})
;;
;; This is "classify at the fact's definition site" — the resource IS defined
;; at `reg-resource`, so it declares its own (statically-known) sensitive
;; fields there. The declaration is value-INDEPENDENT and standing: it redacts
;; whatever later occupies the slot on EVERY instance (the per-instance
;; application means every minted scoped key and landed data value is governed
;; by the same owner declaration, with no per-instance author code or absolute
;; runtime storage paths in app code.
;;
;; Lowering = re-rooting + axis-split. The declaration paths are rooted at the
;; instance projection (`[:data …]` → the data value; `[:params …]` → the
;; scoped-key params; `[:scope …]` / a `[:scope]`-rooted path → the scope
;; component). `instance-declaration-paths` re-roots the `:data`-rooted paths
;; under the entry's absolute `:data` path and the `:params` / `:scope`-rooted
;; paths under the scoped-key carrier, writing them into the per-frame elision
;; registry (`reconcile-registry`) — the egress boundaries read them back from
;; there (`project-entry-data` for `:data`; `project-entry-scope` /
;; `project-entry-params` for the key's index-0 / index-2 components). A
;; bare-rooted path (no
;; `:data` / `:params` head) defaults to the DATA projection — the common
;; `{:sensitive [[:ssn]]}` shorthand for a data field.
;; ---------------------------------------------------------------------------

(def ^:private declaration-axis-keys
  "The two projection-relative declaration axes a `reg-resource` /
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
              (try (rf.path/normalize-concrete p) nil
                   (catch #?(:clj Throwable :cljs :default) e
                     (str "an invalid path in the `" k "` classification "
                          "declaration: " (or (ex-message e) (str e)))))))
          payload)))

(defn classification-declaration-defect
  "PURE fail-loud-INPUT validator for a resource / mutation `spec`'s
  projection-relative `:sensitive` / `:large` declarations. Returns
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
      (let [p (vec (rf.path/normalize-concrete p))]
        (case (first p)
          :data   (update acc :data conj (subvec p 1))
          :params (update acc :params conj (subvec p 1))
          :scope  (update acc :params conj p)
          ;; bare-rooted ⇒ a data field shorthand
          (update acc :data conj p))))
    {:data [] :params []}
    (get spec k)))

(defn spec-declaration-marks
  "The projection-relative `:sensitive` / `:large` declarations a
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
;; Mutation continuation-reply projection (rf2-825mzj / EP-0025 §subsystems).
;;
;; A `:reply-to` mutation continuation dispatches a canonical reply map carrying
;; the accepted attempt's `:params` + resolved `:scope` (Spec 016 §Mutation
;; completion continuations). Those are the SAME owner-classified facts the
;; durable instance stores, so an owner that declared `:sensitive [[:params
;; :password]]` must not repeat the raw value on the continuation reply (which
;; rides the trace bus AND is dispatched into app workflow). The mutation OWNER
;; declaration is the single source — this redacts the reply IN THE SAME
;; construction step resources builds it, deriving the paths from the spec (never
;; a second classification language). The mutation `:request` handler already ran
;; on the RAW params before the instance was minted, so the causal write is
;; unaffected; only the completion echo is projected.
;; ---------------------------------------------------------------------------

(defn- carrier-decl-paths
  "Re-root ONE axis (`:sensitive` / `:large`) of `spec`'s projection-relative
  declarations onto a `{:params … :scope …}` sibling CARRIER map — the shape
  the mutation continuation REPLY and the `[:rf.mutation/execute …]` event
  payload share. The `:params` bucket holds params-rooted paths (head
  stripped) AND `:scope`-rooted paths (kept WITH their `[:scope …]` head), so
  a scope entry indexes the carrier's sibling `:scope` verbatim while a params
  entry re-roots under `[:params …]`. A `:data`-rooted / bare decl re-roots
  under `data-slot` when one is given (the reply's `:value` result projection)
  and is DROPPED when `data-slot` is nil (the execute payload carries no
  result slot at execute time). Pure."
  [spec k data-slot]
  (let [{:keys [data params]} (split-projection-paths spec k)]
    (into (if data-slot (mapv #(into [data-slot] %) data) [])
          (mapv (fn [p] (if (= :scope (first p)) p (into [:params] p)))
                params))))

(defn redact-continuation-reply
  "Redact the owner-declared `:sensitive` / `:large` param + scope subpaths of a
  continuation REPLY map, deriving the paths from the owner `spec`'s
  projection-relative declaration (`carrier-decl-paths`). The reply carries
  `:params` + `:scope` as siblings and the decoded result under `:value`, so a
  `:params`-rooted decl `[:password]` redacts reply `[:params :password]`, a
  `:scope`-rooted decl redacts reply `[:scope …]`, and a `:data`-rooted decl
  redacts the reply's `:value` (the result projection). Sensitive wins
  over large at a shared path (the shared elision walker's ordering). A `spec`
  that declares neither axis — or a nil spec (unregistered owner) — rides the
  reply UNCHANGED. Pure / value-independent.

  BOTH continuation families use this, at DIFFERENT boundaries, and the
  difference is deliberate:

    - MUTATIONS call it at the SOURCE (`mutation_events`, rf2-825mzj). A
      mutation's completion echo is redacted before it reaches any carrier;
      a coarse `:sensitive?` root prop is not part of `reg-mutation`'s
      declaration surface at all, so this is the whole of the mutation reply's
      classification.
    - READS call it at OFF-BOX EGRESS
      (`trace_egress/redact-reply-declarations`, rf2-ko5lm), and must not call
      it at the source: the app's own `:reply-to` handler is entitled to the
      decoded body and the trusted-local `:include-sensitive?` opt-in must still
      show it. There it composes with the coarse `whole-entry-disposition` arm,
      which reads the root prop a read owner CAN declare.

  INDEX-FREE, and that is what makes an INFINITE FEED work (rf2-zaopo). A
  projection-relative declaration is written against the projection's SHAPE,
  not against a concrete runtime position, so a positional container consumes
  no declared segment and `[:data :email]` names EVERY element. The DURABLE
  side has always read it that way — `project-entry-data` walks a feed's page
  vector through `elide-wire-value`, whose index-free fork matches the
  declaration on every page. The reply carries the MERGED ITEM LIST under
  `:value` (`events/infinite-reply-value`), so without the same reading the
  re-rooted `[:value :email]` reached nothing at `[:value <i> :email]` and a
  feed's declared field redacted in the durable entry while riding raw in the
  continuation echo of it. Hence `{:index-free? true}`: no new declaration
  vocabulary — a feed needs no \"each item\" wildcard because the index-free
  declaration already IS that spelling — just the carrier honouring the reading
  the durable side established."
  [reply spec]
  (let [sens  (carrier-decl-paths spec :sensitive :value)
        large (carrier-decl-paths spec :large :value)]
    (if (or (seq sens) (seq large))
      (rf.classification/redact-with-paths reply sens large {:index-free? true})
      reply)))

(defn project-execute-event-args
  "Redact a `[:rf.mutation/execute <args>]` event's ARGS MAP for the
  event-bearing trace slots the CORE event projection walks — `:rf.event/v` on
  the dispatched / lifecycle traces, the bare `:event` error slot, the
  always-on `:rf.observe/*` records, and the `:dispatch` / `:dispatch-later`
  fx-arg recursion (rf2-3ej3xu). The mutation OWNER's projection-relative
  `:sensitive` / `:large` declarations govern the payload — the SAME single
  declaration surface the durable-instance lowering and the continuation-reply
  projection read (rf2-825mzj), never a second classification language: a
  `:params`-rooted decl redacts args `[:params …]`, a `:scope`-rooted decl the
  sibling `:scope`; `:data`-rooted decls name the RESULT projection, which
  does not exist at execute time, and are skipped. The `:reply-to`
  continuation address — when a payload-carrying event vector — additionally
  rides the TARGET event registration's own classification through the
  core-bound `:classification/redact-event-by-registration` hook, the same
  composition the managed-HTTP `:on-success` / `:on-failure` addresses get.

  Consumed by core through the `:resources/project-execute-event-args`
  late-bind hook (published by the `re-frame.resources` facade with
  `mutation-registry/mutation-meta` as `spec-of` — the registry requires this
  ns, so a static back-require would cycle). An unregistered `:mutation` id, a
  spec that declares neither axis, or a non-map `args` rides UNCHANGED (the
  EP-0025 fail-open). The causal write is untouched — the execute handler
  reads the RAW payload; only egress projects. Pure / value-independent."
  [args spec-of]
  (if-not (map? args)
    args
    (let [spec  (when-let [mid (:mutation args)] (spec-of mid))
          sens  (when spec (carrier-decl-paths spec :sensitive nil))
          large (when spec (carrier-decl-paths spec :large nil))
          args  (if (or (seq sens) (seq large))
                  (rf.classification/redact-with-paths args sens large)
                  args)
          redact-event (rf.late-bind/get-fn :classification/redact-event-by-registration)
          reply-to     (:reply-to args)]
      (if (and redact-event (vector? reply-to))
        (let [rt' (redact-event reply-to)]
          (if (identical? reply-to rt') args (assoc args :reply-to rt')))
        args))))

;; ---------------------------------------------------------------------------
;; Schemas validate; they do NOT classify durably.
;;
;; A resource's `:data-schema` / `:params-schema` (and, for an infinite feed,
;; the per-page validation supplied on the request's `:decode`)
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
;; Egress projection — registry-driven, the routing / machines standard model.
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
    (boolean (or (under (rf.elision/sensitive-declarations frame-id))
                 (under (rf.elision/declarations frame-id))))))

(defn project-entry-data
  "Project a resource entry's serialized DATA value for egress across
  `boundary-profile` (a `:rf.egress/*` profile, e.g. `:rf.egress/ssr-hydration`)
  against `frame-id`'s per-frame elision registry — the REGISTRY-DRIVEN egress
  read. The resource's projection-relative `:data`
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
  (let [data-prefix (conj (rf.resources.state/entry-path-by-id key-id) :data)]
    (if (and frame-id (registry-classifies-under? frame-id data-prefix))
      (rf.projection/project-egress
        data
        {:frame             frame-id
         :path              data-prefix
         :rf.egress/profile boundary-profile})
      data)))

(defn- project-entry-key-component
  "Project ONE component of an entry's scoped key for egress against `frame-id`'s
  per-frame elision registry — the shared body behind `project-entry-scope`
  (index 0) and `project-entry-params` (index 2).

  The scoped key is the vector `[scope resource-id params]` and it lives at the
  entry's absolute `:resource/key` carrier, so `instance-declaration-paths`
  lowers a `:scope`-rooted declaration to `[… :resource/key 0 …]` and a
  `:params`-rooted one to `[… :resource/key 2 …]` (`reconcile-registry`). Here
  we walk the bare component value through `re-frame.projection/project-egress`
  SEEDED at that same offset, so the lowered `:source :resource` decls match and
  a marked slot redacts (`:sensitive` → `:rf/redacted`) / elides (`:large` →
  `:rf.size/large-elided`) without the raw value riding in the wire key.

  ONE rule, applied at both indices by parameterising the index alone — the
  scope and params components of a key carry the same classification (Spec 016
  clause 4 \"params, scopes, and data carry the same classification\"), so they
  must not be able to drift into two rules.

  GATED on a registry declaration under the component's offset
  (`registry-classifies-under?`): when nothing is declared there the component
  rides VERBATIM (reference / byte-identity preserving — the scoped key is a
  CEDN-1 byte-keyed identity, so a list↔vector collapse from an unnecessary walk
  would break the cache-key-identity round-trip). Frame-SCOPED: a nil /
  unresolvable `frame-id` rides the component VERBATIM. Pure w.r.t. the value."
  [component index key-id frame-id boundary-profile]
  (let [prefix (conj (rf.resources.state/entry-path-by-id key-id) :resource/key index)]
    (if (and frame-id (registry-classifies-under? frame-id prefix))
      (rf.projection/project-egress
        component
        {:frame             frame-id
         :path              prefix
         :rf.egress/profile boundary-profile})
      component)))

(defn project-entry-params
  "Project a resource entry's scoped-key PARAMS value (index 2 of
  `[scope resource-id params]`) for egress against `frame-id`'s per-frame
  elision registry — the CO-EQUAL params counterpart to `project-entry-data`
  (Spec 016 clause 4 \"params, scopes, and data carry the same
  classification\"), and the sibling of `project-entry-scope` below.

  Used on a `:serialize` entry (no coarse whole-entry claim) — the COARSE
  `:redact` / `:omit` dispositions still replace the WHOLE params component with
  an opaque content-addressed digest (`re-frame.resources.ssr/project-scoped-key`),
  irrespective of frame, which subsumes the per-slot surface. Declaration-GATED
  and frame-SCOPED per `project-entry-key-component`. `key-id` is the entry's
  CEDN-1 byte key-id. Pure w.r.t. the value."
  [params key-id frame-id boundary-profile]
  (project-entry-key-component params 2 key-id frame-id boundary-profile))

(defn project-entry-scope
  "Project a resource entry's scoped-key SCOPE value (index 0 of
  `[scope resource-id params]`) for egress — the CO-EQUAL scope counterpart to
  `project-entry-params` (rf2-5e2ye).

  ## The asymmetry this closes

  `instance-declaration-paths` has always lowered a `:scope`-rooted declaration
  to the entry's absolute `[… :resource/key 0 …]` path exactly as it lowers a
  `:params`-rooted one to `[… :resource/key 2 …]`, so the epoch / registry walk
  over the runtime-db honoured BOTH, and (since rf2-dl7bz)
  `trace-egress/redact-key-declarations` honours both on every trace and tool
  key. But the SSR wire key projected only index 2, so on a `:serialize` owner
  declaring `{:sensitive [[:scope :tenant-id]]}` the resolved tenant id rode RAW
  in the hydration payload — the LAST carrier of that value that disagreed, and
  the only one that is not dev-gated.

  ## Reaching through the `[tier {identity}]` tuple

  A scope component is `:rf.scope/global` or the tuple `[tier {identity-map}]`,
  so the declared `:tenant-id` sits at the INDEXED runtime path
  `[… :resource/key 0 1 :tenant-id]` while the lowered decl is the index-FREE
  `[… :resource/key 0 :tenant-id]`. `elide-wire-value`'s index-free fork
  (`fork-index-paths`) matches the two — the same mechanism that lets ONE
  `[… :data :token]` decl cover every page of an infinite feed's `:data` vector,
  and the same reading `redact-key-declarations` walks index-free on the trace
  side. The scope TIER keyword at index 0 of the tuple is not a declared path,
  so it survives and attribution is preserved.

  Declaration-GATED and frame-SCOPED per `project-entry-key-component`: an
  UNDECLARED scope rides byte-identical (`:rf.scope/global` is untouched, and so
  is an undeclared sibling of a declared identity slot), which is what preserves
  the CEDN-1 key identity. `key-id` is the entry's CEDN-1 byte key-id. Pure
  w.r.t. the value."
  [scope key-id frame-id boundary-profile]
  (project-entry-key-component scope 0 key-id frame-id boundary-profile))

;; ---------------------------------------------------------------------------
;; Invalid-params error-payload projection.
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
;; Schema props do not drive DURABLE classification. They do govern the
;; validation-failure record that the schema produces, so this seam reads those
;; props directly to redact failing params. Durable wire, SSR, and tool egress
;; remain governed by projection-relative declarations lowered into the frame
;; registry.
;; ---------------------------------------------------------------------------

(defn- validation-failure-params-marks
  "The per-slot `:sensitive?` / `:large?` props a `:params-schema` declares, as
  `{:sensitive {path decl} :large {path decl}}` rooted at the params root — the
  SCHEMA-PROP marks, read via the shared late-bound walker hooks. Used ONLY by
  `redact-invalid-params-error` to redact the failing params in the VALIDATION-
  FAILURE-TRACE (the schema's own egress product, per Spec 015 §Schemas
  describe shape) — NOT a durable
  classification route. Empty maps when the schema is nil / unschematic / the
  walker hooks are unbound. Pure (modulo the memoised walker)."
  [schema]
  (let [extract (fn [hook]
                  (if-let [f (and schema (rf.late-bind/get-fn-cached hook))]
                    (or (f schema []) {})
                    {}))]
    {:sensitive (extract :schemas/extract-sensitive-paths-from-schema)
     :large     (extract :schemas/extract-large-paths-from-schema)}))

(defn redact-invalid-params-error
  "Project the `:params` + `:error` (explainer output) slots of an invalid-params
  failure error payload against the resource / mutation `spec`'s `:params-schema`
  per-slot `:sensitive?` / `:large?` VALIDATION props. Returns
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

  This is the schema's OWN VALIDATION-FAILURE-TRACE egress product (Spec 015
  §Schemas describe shape), distinct from DURABLE wire/SSR/tool egress, which
  `project-entry-params` governs through the frame registry. `error` may be nil (no
  explainer registered); the `:error` key is then nil. Pure (modulo the memoised walker + the late-bound redaction
  hook)."
  [params error spec]
  (let [schema     (:params-schema spec)
        redact-fn  (rf.late-bind/get-fn-cached :schemas/redact-validation-tags)
        {:keys [sensitive large]} (validation-failure-params-marks schema)
        ;; The failing params slot is the validator's own failure product — redact
        ;; the schema's `:sensitive?` / `:large?` slots per-slot (the surviving
        ;; schema-prop route), keeping the non-sensitive failing field diagnostic.
        params'    (if (or (seq sensitive) (seq large))
                     (rf.classification/redact-with-paths params (keys sensitive) (keys large))
                     params)
        ;; The explainer output carries the failing params verbatim; treat it as
        ;; the `:explain` value-bearing slot the shared seam scrubs whole-payload.
        redacted-e (if (and redact-fn (some? schema) (some? error))
                     (:explain (redact-fn schema {:explain error}))
                     error)]
    {:params params'
     :error  redacted-e}))

;; ---------------------------------------------------------------------------
;; Per-instance lowering into the per-frame elision registry.
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
;; `[:rf.runtime/routing :current …]`). The registry is the single source read
;; by `project-entry-data` / `project-entry-params` and by generic classification
;; tooling.
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
  (conj (rf.resources.state/entry-path-by-id key-id) :data))

(defn- entry-key-prefix
  "The ABSOLUTE runtime-db prefix for an entry's scoped-key `:resource/key`
  carrier — `[:rf.runtime/resources :entries <key-id> :resource/key]`. The
  scoped key is `[scope resource-id params]`, so a `:params`-rooted declared
  path re-roots under index 2, a `:scope`-rooted one under index 0."
  [key-id]
  (conj (rf.resources.state/entry-path-by-id key-id) :resource/key))

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

(def ^:private resource-owner
  "The multi-owner elision-registry owner identity a resource / mutation
  instance lowering claims under. All current entries share this owner; the
  reconcile rebuilds the full resource-owned claim set from the live entries
  each commit (self-dropping), so `rf.elision/replace-owner-claims` drops evicted
  entries' claims and installs current ones in one step."
  {:source resource-source})

(defn reconcile-registry
  "PURE per-instance lowering: given a base `runtime-db` value and a `spec-of`
  resolver `(fn [resource-id] -> spec|nil)`, return the runtime-db with the
  `[:rf.runtime/elision …]` registry's resource-owner claims REPLACED by the
  declarations of EVERY current cache entry (re-rooted absolute per the entry's
  byte `key-id`), delegating to the core multi-owner op
  `re-frame.elision/replace-owner-claims`. The standard EP-0025 lowering — the
  resources peer of `re-frame.routing.classification/apply-route-classification`
  and `re-frame.machines.classification/lower-at-spawn!` (rf2-v8x9n8).

  Operates on a VALUE so a resource handler's durable `:rf.db/runtime`
  transition folds the registry update into the SAME atomic commit that
  writes / evicts the entry. IDEMPOTENT + SELF-DROPPING: the full resource-
  owned claim set is rebuilt from the current `:entries`, so an evicted entry's
  declarations vanish (the per-instance teardown) and a newly-minted entry's
  appear, with no separate drop hook. Value-INDEPENDENT (the declared path
  redacts whatever later occupies the slot). A path ALSO claimed by another
  owner (`{:source :effect}` / `{:source :flow …}` / `{:source :route}` /
  `{:source :machine …}`) rides untouched and unions at egress-lookup time
  (rf2-wdm1vg).

  `spec-of` is injected (resources' `registry` requires `classification`, so a
  static back-require would cycle) — the caller (a resource event handler in
  `re-frame.resources.events`) passes `registry/resource-meta`. A resource id
  with no registered spec, or a spec that declares no classification, contributes
  nothing.

  Reconcile-aware (router `re-frame.elision/reconcile-runtime-db-effect`): when
  the lowering touched the registry (the base carried resource-owned entries
  or the current entries declare some), it emits an EXPLICIT
  `:rf.runtime/elision` key (possibly cleared of all resource entries) so a
  whole-value runtime-db commit honours an eviction's drop verbatim rather than
  resurrecting the prior registry. The common no-classification-ever case emits
  no key."
  [runtime-db spec-of]
  (let [base    (or runtime-db {})
        entries (get-in base (rf.resources.state/entries-path))
        reg     (get base elision-registry-key)
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
        new-reg (-> (or reg {})
                    (rf.elision/replace-owner-claims :sensitive-declarations resource-owner sensitive)
                    (rf.elision/replace-owner-claims :declarations resource-owner large))]
    (cond
      (seq new-reg)
      (assoc base elision-registry-key new-reg)

      ;; emptied, but the base carried a registry → emit the explicit clear so
      ;; the router's reconcile honours an eviction's drop verbatim.
      (contains? base elision-registry-key)
      (assoc base elision-registry-key {})

      ;; nothing ever classified → leave the key absent (no stray sub-tree).
      :else base)))

;; ---------------------------------------------------------------------------
;; Per-instance MUTATION lowering into the per-frame elision registry
;; (rf2-825mzj — the mutation peer of the resource-entry lowering above).
;;
;; A mutation INSTANCE lives at `[:rf.runtime/mutations <key-id>]` and stores the
;; canonical `:params`, resolved `:scope`, and decoded `:result` (Spec 016
;; §Cache home). The owner `reg-mutation` spec declares its sensitive slots
;; PROJECTION-RELATIVE to that instance projection (`:sensitive [[:params
;; :password]]`), and the runtime LOWERS the declaration into the SAME per-frame
;; elision registry PER INSTANCE, re-rooting each projection-relative path to the
;; instance's ABSOLUTE runtime-db path under `:source :mutation` — exactly as
;; resource entries lower under `:source :resource`. The durable instance keeps
;; the RAW value (the success-path `:invalidates` / `:patches` / `:populates`
;; read it, and the request handler already consumed it); only the egress
;; boundaries that read this registry (epoch export, off-box tool, MCP) redact.
;;
;; Re-rooting (per instance, keyed on the instance id's byte `key-id`):
;;   - a `:params`-rooted path → `[:rf.runtime/mutations <key-id> :params …]`;
;;   - a `:scope`-rooted path  → `[:rf.runtime/mutations <key-id> :scope …]`;
;;   - a `:data`-rooted / bare path → `[:rf.runtime/mutations <key-id> :result …]`
;;     (the mutation's decoded result is its data projection).
;; ---------------------------------------------------------------------------

(defn- mutation-instance-declaration-paths
  "The ABSOLUTE `{:sensitive [paths] :large [paths]}` runtime-db paths a single
  mutation instance contributes, re-rooting `spec`'s projection-relative
  declarations under the instance's byte `key-id`. `:params`-rooted paths
  re-root under the instance `:params`; `:scope`-rooted paths under `:scope`;
  `:data`-rooted / bare paths under `:result`. Pure; returns `{:sensitive []
  :large []}` when the spec declares neither axis."
  [key-id spec]
  (let [s            (split-projection-paths spec :sensitive)
        l            (split-projection-paths spec :large)
        inst-prefix  (conj (rf.resources.mutation-runtime/instances-path) key-id)
        result-pre   (conj inst-prefix :result)
        params-pre   (conj inst-prefix :params)
        scope-pre    (conj inst-prefix :scope)
        ;; a `:params`-bucket path is either a params-rooted path (head stripped)
        ;; or a `:scope`-rooted path (kept WITH its `[:scope …]` head); re-root
        ;; each to the matching instance slot.
        ->key-abs    (fn [p]
                       (if (= :scope (first p))
                         (into scope-pre (subvec (vec p) 1))
                         (into params-pre p)))
        ->abs        (fn [axis]
                       (into (mapv #(into result-pre %) (:data axis))
                             (mapv ->key-abs (:params axis))))]
    {:sensitive (->abs s)
     :large     (->abs l)}))

(def ^:private mutation-source
  "The elision-registry `:source` tag a mutation-lowered declaration carries —
  the mutation peer of `:source :resource`. Source-scoped so reconciliation /
  clear touch only mutation-sourced entries (an evicted instance self-drops)."
  :mutation)

(def ^:private mutation-owner
  "The multi-owner elision-registry owner identity a mutation instance lowering
  claims under. The reconcile rebuilds the full mutation-owned claim set from the
  live instances each commit (self-dropping), so `replace-owner-claims` drops a
  cleared instance's claims and installs current ones in one step."
  {:source mutation-source})

(defn reconcile-mutation-registry
  "PURE per-instance MUTATION lowering: given a base `runtime-db` value and a
  `spec-of` resolver `(fn [mutation-id] -> spec|nil)`, return the runtime-db with
  the `[:rf.runtime/elision …]` registry's `:source :mutation` claims REPLACED by
  the declarations of EVERY current mutation instance (re-rooted absolute per the
  instance's byte `key-id`), delegating to `re-frame.elision/replace-owner-claims`.
  The mutation peer of `reconcile-registry` — the standard EP-0025 lowering.

  Operates on a VALUE so a mutation handler's durable `:rf.db/runtime` transition
  folds the registry update into the SAME atomic commit that mints / settles /
  clears the instance. IDEMPOTENT + SELF-DROPPING: the full mutation-owned claim
  set is rebuilt from the current `:rf.runtime/mutations` instances, so a cleared
  instance's declarations vanish and a newly-minted instance's appear, with no
  separate drop hook. Value-INDEPENDENT. A path ALSO claimed by another owner
  (`:source :resource` / `:effect` / …) rides untouched and unions at
  egress-lookup time.

  `spec-of` is injected (the mutation registry requires this ns, so a static
  back-require would cycle) — the caller passes `mutation-registry/mutation-meta`.
  A mutation id with no registered spec, or a spec that declares no
  classification, contributes nothing.

  Reconcile-aware (mirrors `reconcile-registry`): when the lowering emptied a
  base that carried a registry, it emits an EXPLICIT (possibly cleared)
  `:rf.runtime/elision` key so a whole-value runtime-db commit honours a clear's
  drop verbatim; the never-classified case emits no key."
  [runtime-db spec-of]
  (let [base      (or runtime-db {})
        instances (get-in base (rf.resources.mutation-runtime/instances-path))
        reg       (get base elision-registry-key)
        {:keys [sensitive large]}
        (reduce-kv
          (fn [acc key-id inst]
            (let [mid  (:mutation/id inst)
                  spec (when mid (spec-of mid))]
              (if-not spec
                acc
                (let [{:keys [sensitive large]} (mutation-instance-declaration-paths key-id spec)]
                  (-> acc
                      (update :sensitive into sensitive)
                      (update :large into large))))))
          {:sensitive [] :large []}
          (or instances {}))
        new-reg   (-> (or reg {})
                      (rf.elision/replace-owner-claims :sensitive-declarations mutation-owner sensitive)
                      (rf.elision/replace-owner-claims :declarations mutation-owner large))]
    (cond
      (seq new-reg)
      (assoc base elision-registry-key new-reg)

      (contains? base elision-registry-key)
      (assoc base elision-registry-key {})

      :else base)))
