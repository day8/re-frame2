(ns re-frame.classification
  "Data-classification path projection for sensitive + large values per
  EP-0025 (Spec 015 graduation).

  This namespace owns the EGRESS-TIME PROJECTION substrate (migrated off the
  retired `re-frame.marks` ns, EP-0025): the path-walk + sentinel substitution
  that redacts `:rf/redacted` at `:sensitive` paths and surfaces
  `:rf.size/large-elided` markers at `:large` paths. There is NO classification
  PROPAGATION (no sub / flow input → output inheritance, no value-match) and NO
  imperative `add-marks` / `set-marks` API — both removed by EP-0025.

  It owns:
    - Per-registration classification — DERIVED at read time
      (`registration-classification`) from the registration metadata
      `re-frame.registrar` already holds, normalised to
      `{:sensitive [paths] :large [paths] :large? bool}`. The author keys
      (`:sensitive` / `:large` / `:large?`) are stored by every reg-* path on
      the registrar entry, so the projection re-derives them through the SAME
      `normalise-classification` validation the registration ran — no
      duplicated imperative side-table (rf2-ehexnw).
    - Emit-time projection — the path-walk + sentinel substitution consumed by
      `re-frame.trace/build-event` to redact a trace event's `:tags` against
      the in-scope registrations' declared paths and the frame's app-db
      classification registry.

  Per Spec 015 §Hot-path cost: the entire surface rides
  `re-frame.interop/debug-enabled?` — registrations still populate tables at
  boot (constant memory), but emit-time projection is gated and constant-folds
  out of CLJS production bundles via `goog.DEBUG`.

  Durable app-db classification lives in the per-frame elision registry
  (`[:rf.runtime/elision :sensitive-declarations]` / `:declarations` in the
  frame's runtime-db partition — EP-0001 rf2-vzld77), populated by the EP-0025
  commit-plane classification effects (`re-frame.elision`, `:source :effect` —
  a `reg-event` returns `:sensitive` / `:large` alongside `:db`), by `reg-flow`
  output declarations (`re-frame.flows.registry`, `:source :flow`), and by
  subsystem projection-relative declarations (resources / routing). The sources
  union at lookup time. EP-0025: the durable `:sensitive` / `:large {:app-db …}`
  *frame annotation* is REMOVED (a frame is not app-db's definition site), and
  classification does NOT propagate — you redact exactly the paths you classify;
  nothing is inherited (no derived-output sensitivity, no value-match)."
  (:require [re-frame.elision :as rf.elision]
            [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.path :as rf.path]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- per-registration classification: DERIVED from the registrar (rf2-ehexnw)
;;
;; A registration's declared classification (`:sensitive` / `:large` /
;; `:large?`) is NOT stashed into a second imperative side-table at registration
;; time. Every reg-* path already stores the author meta on its
;; `re-frame.registrar` entry, so `registration-classification` DERIVES it at
;; read time by running `normalise-classification` over
;; `rf.registrar/handler-meta` — the SAME validation path the registration ran. The
;; registrar is the single source of truth, snapshot/restored by the test-
;; isolation runtime fixture for free.

;; ---- malformed-declaration rejection (rf2-y7l5t5) ------------------------
;;
;; A `:sensitive` / `:large` declaration is a vector of output-path vectors
;; (`[[:user :ssn] [:auth :token]]`; `[[]]` marks the whole value). We REJECT
;; LOUDLY at the ingestion boundary (`validate-classification!`, called from each
;; reg-* path AFTER the registrar write — rf2-ehexnw): a hand-written typo —
;; `:sensitive :token` (bare keyword), `:sensitive "blob"` (string),
;; `:sensitive {…}` (map), or `:sensitive [:token]` / `[[:a [:b]]]` (a
;; non-vector / non-scalar-element entry) — registers with NO error and NO
;; classification under the prior silent-drop, the worst failure mode for a
;; safety surface. The validation runs the SAME `normalise-classification` the
;; projection re-runs at read time, so a malformed declaration is caught at
;; registration (fail-loud) AND can never be re-derived into a classification.
;;
;; A classification-path SEGMENT is admitted by the shared EP-0012 segment
;; domain (`re-frame.path/segment?`), NOT a private re-enumeration — keyword /
;; string / symbol / boolean / integer / UUID / instant / nil.

(defn- valid-classification-subpath?
  "A `:sensitive` / `:large` entry is a vector of EP-0012 path segments
  (`re-frame.path/segment?` — the shared segment domain). The EMPTY vector `[]`
  is legal — it marks the whole value (the `[[]]` convention). A composite
  element (a nested vector / map) is not a concrete segment and signals a caller
  bug; `rf.path/segment?` rejects it."
  [x]
  (and (vector? x) (every? rf.path/segment? x)))

(defn- classification-error
  "Build the malformed-classification ex-info with the canonical thrown-error
  shape (per Spec 009 §The thrown-error shape). `:rf.error/bad-classification`
  is the message AND the `:rf.error/id` discriminator; `:bad-key` names the
  offending classification key; `extras` merges the offending-value slot
  (`:bad-value` for a non-vector whole, `:bad-entries` for malformed entries)."
  [class-key reason extras]
  (rf.error/thrown-ex-info
    :rf.error/bad-classification
    'rf/reg-classification
    reason
    {:recovery :fix-registration
     :extra    (merge {:bad-key class-key} extras)}))

(defn- coerce-paths
  "Normalise a `:sensitive` / `:large` declaration value to a vector of path
  vectors. `nil` becomes `[]`. The whole value must be a vector and every entry
  a vector of scalar path elements (`[[]]` for whole-value); a malformed value
  or entry is REJECTED with `:rf.error/bad-classification` rather than silently
  dropped (rf2-y7l5t5 — fail-loud, not fail-open).

  `class-key` (`:sensitive` / `:large`) names the offending key in the thrown
  ex-data."
  ([paths] (coerce-paths paths :sensitive))
  ([paths class-key]
   (cond
     (nil? paths)
     []

     (not (vector? paths))
     (throw (classification-error class-key
                                  (str class-key ", when present, must be a vector of "
                                       "output paths (each a vector of scalar keys; "
                                       "[] marks the whole value)")
                                  {:bad-value paths}))

     (not (every? valid-classification-subpath? paths))
     (throw (classification-error class-key
                                  (str class-key " entries must each be a vector of "
                                       "EP-0012 path segments (keyword / string / "
                                       "symbol / boolean / integer / UUID / instant / "
                                       "nil); [] marks the whole value")
                                  {:bad-entries (vec (remove valid-classification-subpath? paths))}))

     :else
     paths)))

(defn- normalise-classification
  "Extract the classification-relevant subset of a registration meta-map and
  normalise into the canonical shape this namespace consults:

    {:sensitive  [vector-of-paths]
     :large      [vector-of-paths]
     :large?     <bool-or-nil>}  ;; whole-output size override (subs/flows)

  EP-0025: there is NO derived-output sensitivity claim — classification does
  not propagate. A `:rf.egress/output-sensitivity` enum and the boolean
  `:sensitive?` overload are GONE; they are silently dropped if present (no
  propagation reads them). `:large?` survives as the whole-output size override
  (size has no propagation analogue).

  Returns `nil` when the meta-map carries no classification-relevant keys —
  callers branch on the nil to avoid stashing empty tables. An EMPTY `:sensitive`
  / `:large` declaration (`[]` — classifies nothing) is dropped, and a map that
  ends up empty returns `nil`."
  [meta]
  (when (or (contains? meta :sensitive)
            (contains? meta :large)
            (contains? meta :large?))
    ;; A present-but-EMPTY `:sensitive` / `:large` vector classifies nothing,
    ;; so omit the slot (a non-empty `[[]]` whole-value mark is KEPT — it has
    ;; one entry). A map that ends up empty reads as nil.
    (let [sens  (when (contains? meta :sensitive)
                  (seq (coerce-paths (:sensitive meta) :sensitive)))
          large (when (contains? meta :large)
                  (seq (coerce-paths (:large meta) :large)))
          m     (cond-> {}
                  sens                     (assoc :sensitive (vec sens))
                  large                    (assoc :large     (vec large))
                  (contains? meta :large?) (assoc :large?    (boolean (:large? meta))))]
      (when (seq m) m))))

(defn validate-classification!
  "Validate a registration's classification declaration at the reg-* boundary,
  FAIL-LOUD (rf2-ehexnw / rf2-y7l5t5). Returns nil. No-op (no throw) when `meta`
  carries no classification-relevant keys or carries only well-formed ones.

  Called from each reg-* path BEFORE the underlying registrar write. It runs the
  SAME `normalise-classification` the projection re-runs at read time and
  DISCARDS the result — its only job is the throw-side-effect: a malformed
  `:sensitive` / `:large` declaration raises `:rf.error/bad-classification` at
  registration rather than silently mis-deriving at the first emit. NOTHING is
  stashed. The `kind` arg is accepted for call-site symmetry but unused (EP-0025
  removed the `:sub`-scoped `:sensitive?` rejection)."
  ([meta] (validate-classification! nil meta))
  ([_kind meta]
   (normalise-classification meta)
   nil))

(defn registration-classification
  "Return the classification declaration for `(kind, id)`, or nil — DERIVED at
  read time from `rf.registrar/handler-meta` (rf2-ehexnw), NOT a duplicated
  side-table.

  The returned shape is `{:sensitive [paths] :large [paths] :large? bool}` —
  slots are present only when the registration declared them. EP-0025: there is
  no derived-output sensitivity (no propagation)."
  [kind id]
  (normalise-classification (rf.registrar/handler-meta kind id)))

;; ---- emit-time projection ------------------------------------------------

(defn large-marker
  "Build the `:rf.size/large-elided` marker for value `v` at `path`. Delegates
  to `re-frame.elision/->marker` with `{:reason :classification}` so the marker
  shape is built in ONE place.

  Public because the off-box epoch egress projector
  (`re-frame.epoch.tool-pair/projected-record`) reuses it to substitute the
  marker for a whole-output `:large?`-stamped sub's `:value` / `:prev-value`."
  [v path]
  (rf.elision/->marker v path {:reason :classification}))

(defn- strict-prefix?
  "True when path `a` is a STRICT prefix of `b` — `b` is `a` extended by at
  least one further segment (so `b` is a descendant slot under `a`)."
  [a b]
  (let [na (count a)]
    (and (> (count b) na)
         (= a (subvec b 0 na)))))

(defn- large-shadow-set
  "The subset of `large-set` whose paths have a `:sensitive` path strictly BELOW
  them — a `:large`-marked subtree containing a `:sensitive` descendant. Per
  Spec 015 §No-propagation (L338, a normative MUST) such a subtree REDACTS
  (descend + redact the descendant) rather than emitting a size-preview marker
  that would leak `:bytes` / `:type` (and, off-box with digests on, a SHA-256
  digest computed over a subtree that contains the secret — a brute-force
  oracle). Computed once per walk; empty in the common no-nesting case. The
  peer of `re-frame.elision/large-keys-shadowing-sensitive`, over path SETS
  rather than declaration tables."
  [sensitive-set large-set]
  (into #{} (filter (fn [lp] (some #(strict-prefix? lp %) sensitive-set))) large-set))

;; ---- the declaration-coordinate candidate set -----------------------------
;;
;; The walker threads `[path candidates]`: the CONCRETE runtime path (which
;; drives the `:large` marker's `:path` / `:handle`) beside a SET of candidate
;; DECLARATION coordinates — the positions in declaration space the walker
;; could currently be standing at.
;;
;; Candidates are pruned against the prefix set of every declared path, so one
;; that can never reach a declaration is dropped and the set stays bounded by
;; that PREFIX VOCABULARY — every prefix of every declared path, plus the
;; retained root — and NOT by the declaration count. The two bounds are not the
;; same, and it is `:index-free?` that separates them: there an unadvanced
;; candidate rides the descent beside the advanced one, so a single declaration
;; can hold several of its own prefixes alive at once. Against `#{[0 0 0]}` the
;; set grows `1 → 2 → 3 → 4` and ends `#{[] [0] [0 0] [0 0 0]}` — four
;; candidates for one declaration (merged-PR audit #7107). Finite and
;; declaration-derived either way, which is what keeps the walk linear in the
;; tree; but a reader who took "declaration cardinality" literally would expect
;; a singleton and misread the fork.
;;
;; In the DEFAULT mode the set IS exactly `#{path}` while `path` is still some
;; declaration's prefix and `#{}` the moment it is not — which is the exact path
;; membership test spelled through the same machinery, since a declared path is
;; always its own prefix.
;; `:index-free?` is the only thing that lets the two diverge. The same device
;; `re-frame.elision`'s schema-first walker uses, at the grain this walker
;; needs — index fork only, no `:map-of` key skip, so this walker stays
;; strictly MORE precise than the durable side's.

(defn- path-prefixes
  "Every non-empty prefix of `path`, the full path included."
  [path]
  (map #(subvec path 0 %) (range 1 (inc (count path)))))

(defn- decl-prefix-set
  "The set of every prefix of every declared path. A candidate declaration
  coordinate stays alive only while it is a member: anything outside can never
  reach a declaration, so dropping it is matching-safe."
  [sensitive-set large-set]
  (into #{} (mapcat path-prefixes) (concat sensitive-set large-set)))

(defn- fork-segment
  "Advance every candidate by one declaration segment `seg`, keeping only those
  still a prefix of some declared path. A map key is always a real segment; a
  positional index is one too UNLESS the caller opted into `:index-free?`."
  [candidates seg prefixes]
  (into #{} (comp (map #(conj % seg)) (filter #(contains? prefixes %))) candidates))

(defn- walk-with-paths
  "Walk `v` and substitute sentinels at the declared paths. Paths in
  `sensitive-paths` and `large-paths` are rooted at `v`. Sensitive wins over
  large at the same path; a large-marked subtree containing a sensitive
  descendant descends-and-redacts rather than emitting a size marker
  (nested-axis suppression — rf2-izlr7f).

  `index-free?` selects how a POSITIONAL container is read (rf2-zaopo). Default
  false — every index is a declaration segment, so matching is the exact path
  membership test this walker has always applied. True — an index is a
  COLLECTION COORDINATE that consumes no declared segment, so the index-free
  declaration `[:value :email]` matches the runtime `[:value <i> :email]` in
  every element. A declaration that pins a literal index still matches either
  way (the indexed interpretation is retained whenever some declaration
  reaches it), so the mode only ever ADDS the per-element reading.

  No-op early-exit: when both path sets are empty, returns `v` unchanged.
  Shares the map/vec/set/seq recursion skeleton with the schema-first elision
  walker via `re-frame.elision/walk-tree` (rf2-cywzkh)."
  [v sensitive-paths large-paths index-free?]
  (if (and (empty? sensitive-paths) (empty? large-paths))
    v
    (let [sensitive-set (set (map vec sensitive-paths))
          large-set     (set (map vec large-paths))
          prefixes      (decl-prefix-set sensitive-set large-set)
          shadow-set    (large-shadow-set sensitive-set large-set)
          matches?      (fn [tbl candidates] (boolean (some #(contains? tbl %) candidates)))]
      (rf.elision/walk-tree
        v [[] #{[]}]
        {:decide  (fn [[path candidates] v]
                    (cond
                      (matches? sensitive-set candidates) rf.privacy/redacted-sentinel
                      ;; NESTED-AXIS SUPPRESSION (rf2-izlr7f): a large-marked
                      ;; node that shadows a sensitive descendant descends so
                      ;; the descendant redacts in place — no size marker.
                      (and (matches? large-set candidates)
                           (seq shadow-set)
                           (matches? shadow-set candidates))
                      rf.elision/walk-recur

                      (matches? large-set candidates)     (large-marker v path)
                      :else                               rf.elision/walk-recur))
         :map-key (fn [[path candidates] k]
                    [(conj path k) (fork-segment candidates k prefixes)])
         :index   (fn [[path candidates] i]
                    [(conj path i)
                     ;; An index-free candidate rides the descent UNCHANGED —
                     ;; the element position consumed no declared segment.
                     ;; Riding an index can never float a declaration past a
                     ;; NAMED slot, so no position guard is needed here (the
                     ;; argument `re-frame.elision/fork-index-paths` makes).
                     (cond-> (fork-segment candidates i prefixes)
                       index-free? (into candidates))])
         :leaf    (fn [_state v] v)}))))

(defn redact-with-paths
  "Public projection helper. Walks `v` and substitutes sentinels at the declared
  paths. Empty `[[]]` path substitutes the whole value (sensitive wins over large
  at the root). Per Spec 015 §What gets a sentinel.

  `opts` may carry `:index-free? true` (rf2-zaopo) for a caller whose declared
  paths are INDEX-FREE — written against the shape rather than against a
  concrete runtime position, so a positional container contributes no segment
  and the declaration names EVERY element. That is the kind a
  projection-relative resource / mutation declaration is, which is why
  `re-frame.resources.classification/redact-continuation-reply` opts in: its
  `[:data :email]` must reach an infinite feed's merged item list the same way
  the durable side's `elide-wire-value` already reaches the page vector. Every
  other caller declares CONCRETE paths and is left on the exact match — the
  mode is opt-in precisely so it cannot widen a boundary that did not ask."
  ([v sensitive-paths large-paths]
   (redact-with-paths v sensitive-paths large-paths nil))
  ([v sensitive-paths large-paths opts]
   (walk-with-paths v sensitive-paths large-paths (true? (:index-free? opts)))))

(defn- classification-paths
  "Destructure a canonical classification map into its `[sensitive-paths
  large-paths]` pair, each defaulting to `[]` when the slot is absent."
  [class]
  [(or (:sensitive class) []) (or (:large class) [])])

(defn- redact-by-classification
  "Redact `v` against the canonical `class` map — `(redact-with-paths v sens
  large)` with the `[]`-defaulted pair from `classification-paths`."
  [v class]
  (let [[sens large] (classification-paths class)]
    (redact-with-paths v sens large)))

;; ---- per-trace-event projection ------------------------------------------

(defn- redact-event-vec
  "Redact a `[event-id arg-map]` vector. Classification paths index into the
  arg-map (the second element). Per Spec 015 §Event handlers — paths are rooted
  at the arg-map; whole-arg substitution uses `[[]]`.

  SECURITY-RELEVANT — POSITIONAL ARGS EGRESS RAW. Only `(second event)` (the
  arg-map) is path-redactable; the remaining positional args are spread through
  unchanged. A secret carried in a POSITIONAL event arg — e.g.
  `[:auth/login \"user\" \"secret-token\"]` — has no declarable `:sensitive`
  path (a positional index is not path-addressable), so it passes through RAW
  into every trace and error sink. This is a KNOWN STRUCTURAL LIMITATION of the
  fail-open EP-0025 model (unclassified ⇒ ships raw), not a bug. PREFER THE MAP
  PAYLOAD FORM for sensitive args — `[:auth/login {:token \"…\"}]` — then
  classify the path so it redacts at egress."
  [event sensitive-paths large-paths]
  (cond
    (or (nil? event) (not (vector? event))) event
    (< (count event) 2) event
    :else
    (let [[id payload & rest-args] event
          redacted-payload (redact-with-paths payload sensitive-paths large-paths)]
      (into [id redacted-payload] rest-args))))

(defn- classification-when
  "Resolve the classification declared by the `kind` handler registered under
  `id`, guarding a nil `id`. Returns the canonical `{:sensitive [paths]
  :large [paths] …}` shape, or nil when `id` is nil or the handler declared
  none."
  [kind id]
  (when id
    (registration-classification kind id)))

(defn- project-execute-event-payload
  "Per-event-id DYNAMIC projection for a `[:rf.mutation/execute <args>]` event
  vector (rf2-3ej3xu). The execute payload's classification lives on the
  MUTATION spec named INSIDE the args (`:mutation` — per-owner,
  projection-relative), not on the `:rf.mutation/execute` event registration,
  so the static registration layer cannot express it. Defers to the late-bound
  `:resources/project-execute-event-args` hook (published by the
  `re-frame.resources` facade; core stays decoupled — the event peer of the
  `:http/project-managed-fx-args` seam). Hook unbound (resources artefact
  absent) ⇒ pass-through: without the artefact the event has no handler at
  all, the documented fail-open. Identity-preserving when nothing applies."
  [event]
  (if-let [project (rf.late-bind/get-fn :resources/project-execute-event-args)]
    (if (>= (count event) 2)
      (let [payload  (nth event 1)
            payload' (project payload)]
        (if (identical? payload payload') event (assoc event 1 payload')))
      event)
    event))

(defn redact-event-by-registration
  "Project an event vector for egress — the SINGLE event-vector chokepoint,
  the event peer of `project-fx-args`. Two layers compose (rf2-3ej3xu):

  1. The event REGISTRATION's static `:sensitive` / `:large` classification
     declared under `(first event)`, applied to the arg-map paths (EP-0015 —
     event args are registration-owned transient payloads).
  2. Per-event-id DYNAMIC classification the static model cannot express:
     `:rf.mutation/execute` — the payload's classification lives on the
     MUTATION spec named inside the args (per-owner, rf2-825mzj's declaration
     surface), so the resources-published
     `:resources/project-execute-event-args` hook projects it
     (`project-execute-event-payload`). Unbound ⇒ pass-through.

  A no-op when `event` is not a `[event-id arg-map …]` vector or nothing
  applies.

  ALWAYS-ON (NOT gated on `interop/debug-enabled?`) — the registration
  classification is populated at registration time in production as well as dev
  (only the emit-time TRACE projection is dev-gated), and this fn is the
  production egress consumer (rf2-qe6v1u).

  Published via the `:classification/redact-event-by-registration` late-bind
  hook; `re-frame.projection` consumes it for the `:rf.observe/error` /
  handled-event `:event` slot, and `project-event-tags` / the `:dispatch` /
  `:dispatch-later` fx-arg recursion route every other event-bearing slot
  through it, so all of them redact identically."
  [event]
  (let [event' (if-let [class (classification-when :event (when (vector? event) (first event)))]
                 (let [[sens large] (classification-paths class)]
                   (redact-event-vec event sens large))
                 event)]
    (case (when (vector? event') (first event'))
      :rf.mutation/execute (project-execute-event-payload event')
      event')))

(defn- project-event-tags
  "Walk `:rf.event/dispatched` / `:rf.event/db-changed` / `:rf.fx/do-fx` tag
  shapes: the dispatched event vector lives at `:rf.event/v` and is a
  `[event-id arg-map]` form; the `:rf.error/*` error traces carry it under the
  bare `:event` slot, so we redact whichever slot the trace carries — through
  `redact-event-by-registration`, the single event-vector chokepoint (static
  registration classification + the per-event-id dynamic layer, rf2-3ej3xu),
  so this slot, the always-on `:rf.observe/*` records, and the `:dispatch` /
  `:dispatch-later` fx-arg recursion all redact identically.
  Reference-preserving when nothing applies."
  [tags slot]
  (let [event  (get tags slot)
        event' (redact-event-by-registration event)]
    (if (identical? event event')
      tags
      (assoc tags slot event'))))

(defn- project-dispatch-later-args
  "Redact a `:dispatch-later` args map (`{:ms <ms> :event [target-id arg-map …]}`
  — Spec 002 §Reserved fx-ids) by recursing the carried TARGET event through
  the target's OWN registration classification. `:dispatch-later` is a
  reserved fx with no registration to declare paths on, but the event it
  defers has an owner (rf2-32ffq1, extending the rf2-6h3c02 per-entry walk).
  Identity-preserving when the target declares none."
  [args]
  (if-let [event (and (map? args) (:event args))]
    (let [event' (redact-event-by-registration event)]
      (if (identical? event event') args (assoc args :event event')))
    args))

(defn- project-fx-args
  "Redact ONE fx's `args` value through everything its `fx-id` declares — the
  chokepoint shared by every fx-arg-bearing trace slot (`project-fx-tags` for
  the `[:rf.fx/id :rf.fx/args]` pair, `project-event-fx-entry` for the
  `:rf.event/fx` aggregate entries, and the machine action-`:outcome` `:fx`
  echo). Two layers compose (rf2-32ffq1):

  1. The fx REGISTRATION's static `:sensitive` / `:large` paths (rf2-6h3c02).
  2. Per-fx-id DYNAMIC classification the static model cannot express:

     - `:dispatch` — the args ARE a target event vector, so the TARGET
       event's own registration classification applies to the target's
       arg-map (`redact-event-by-registration`; `:dispatch` itself, a
       reserved fx, declares none). Without this a classified event
       dispatched from another handler's `:fx` ships its raw payload at the
       DISPATCHING handler's trace slots even though the target's own
       `:rf.event/v` redacts.
     - `:dispatch-later` — the same recursion over the `{:ms … :event […]}`
       carried event.
     - `:rf.http/managed` — its privacy model is the DYNAMIC per-call
       `:sensitive?` flag inside the args map plus the carrier denylists
       (Spec 014 §Privacy), honoured by the dedicated `:rf.http/*` trace
       composers; the SAME redaction applies here through the late-bound
       `:http/project-managed-fx-args` hook (published by
       `re-frame.http.managed`; core stays decoupled — the same seam shape
       as `:routing/project-route-sub-egress`). Hook unbound (http artefact
       absent) ⇒ pass-through: without the artefact the fx cannot run at all
       (`:rf.error/no-such-fx`), the documented unregistered-fx fail-open.

  A no-op for an fx-id with neither layer — which INCLUDES an UNREGISTERED
  fx-id on `:rf.error/no-such-fx` (there is no registration to read a
  `:sensitive` off, and an unaddressable arg is the known EP-0025 fail-open,
  not a bug this closes)."
  [fx-id args]
  (let [args (if-let [class (classification-when :fx fx-id)]
               (redact-by-classification args class)
               args)]
    (case fx-id
      :dispatch        (redact-event-by-registration args)
      :dispatch-later  (project-dispatch-later-args args)
      :rf.http/managed (if-let [project (rf.late-bind/get-fn :http/project-managed-fx-args)]
                         (project args)
                         args)
      args)))

(defn- project-fx-tags
  "Redact the fx args carried by ANY trace slot that stamps the `[:rf.fx/id
  :rf.fx/args]` pair, through `project-fx-args` — the fx REGISTRATION's
  declared `:sensitive` / `:large` PLUS the per-fx-id dynamic classification
  (`:dispatch` / `:dispatch-later` target inheritance, `:rf.http/managed`'s
  per-call `:sensitive?` — rf2-32ffq1).

  Covers the per-effect `:rf.fx/handled` success trace AND the always-on fx
  ERROR traces (`:rf.error/fx-handler-exception` + its siblings) AND
  `:rf.fx/skipped-on-platform` — every one stamps the SAME `:rf.fx/id` +
  `:rf.fx/args`, so every one gets the SAME redaction (rf2-6h3c02). Keying
  off the SLOT SHAPE (both keys present) rather than op `:rf.fx/handled` is
  what closes the error-trace `:rf.fx/args` leak — the production-survivable
  `:rf.error/fx-handler-exception` fans out through the always-on error-emit
  listener, not just the dev trace, so an fx that persists / sends a
  classified value (a session token to localStorage, an auth header) must NOT
  egress it raw when the fx throws.

  A KEYWORD-REDIRECTED fx (`:fx-overrides` id-redirect — rf2-2siusz) stamps
  the ORIGINAL fx-id as `:rf.fx/from` alongside the resolved `:rf.fx/id`,
  and the args are the SAME value the caller shaped for the ORIGINAL fx's
  contract — so the walk composes `project-fx-args` for BOTH ids: the
  redirect TARGET's own declaration first (an app stub may declare its own
  static paths), then the ORIGINAL registration's static paths + per-fx-id
  dynamic classification. This closes the run-mode leak where a
  `:sensitive? true` `:rf.http/managed` request redirected to a canned /
  test stub rode RAW on the stub's own `:rf.fx/handled` — the stub
  registration declares nothing and the resolved id matches no dynamic
  case, but the ORIGINAL id carries both."
  [tags]
  (if-not (contains? tags :rf.fx/args)
    tags
    (let [args  (:rf.fx/args tags)
          from  (:rf.fx/from tags)
          args' (project-fx-args (:rf.fx/id tags) args)
          args' (if (and from (not= from (:rf.fx/id tags)))
                  (project-fx-args from args')
                  args')]
      (if (identical? args args')
        tags
        (assoc tags :rf.fx/args args')))))

(defn- project-event-fx-entry
  "Redact ONE `[fx-id args …]` effect-vector entry through `project-fx-args`
  (static registration paths + per-fx-id dynamic classification). The args
  value is the SECOND element (Spec 002 §The effect vector); replace it IN
  PLACE so the fx-id + any trailing elements survive. A no-op when the entry
  is not a `[fx-id args …]` vector (a nil / empty conditional-fx no-op) or
  nothing applies to it."
  [entry]
  (if (and (vector? entry) (>= (count entry) 2))
    (let [args  (nth entry 1)
          args' (project-fx-args (first entry) args)]
      (if (identical? args args')
        entry
        (assoc entry 1 args')))
    entry))

(defn- project-event-fx-tags
  "Walk the `:rf.event/fx` slot carried by the `:rf.fx/do-fx` trace — the
  handler's WHOLE returned effect vector (`[[fx-id args] …]`, stamped raw by
  `re-frame.fx/do-fx`). Redact each entry's args through THAT fx-id's
  registration classification, mirroring how `project-db-tags` covers the
  sibling `:rf.event/db` slot (the two share posture — Spec 009 §Canonical
  per-event trace sequence). This closes the aggregate-slot leak the per-effect
  `:rf.fx/handled` redaction alone left open: a classified fx's args survived
  RAW on this always-stamped `:rf.event/fx` vector even though `:rf.fx/handled`
  redacted them (rf2-6h3c02)."
  [tags]
  (let [fx-vec (:rf.event/fx tags)]
    (if-not (vector? fx-vec)
      tags
      (assoc tags :rf.event/fx (mapv project-event-fx-entry fx-vec)))))

(defn- project-cofx-map-slot
  "Walk a `{cofx-id value}` map carried under `slot` in `tags`, redacting each
  value against its cofx-id's registered `:sensitive` / `:large`."
  [tags slot]
  (let [cofx-map (get tags slot)]
    (if-not (map? cofx-map)
      tags
      (let [walked (reduce-kv
                     (fn [acc cofx-id v]
                       (let [class (classification-when :cofx cofx-id)]
                         (if-not class
                           (assoc acc cofx-id v)
                           (assoc acc cofx-id (redact-by-classification v class)))))
                     (empty cofx-map)
                     cofx-map)]
        (assoc tags slot walked)))))

(defn- project-cofx-run-tags
  "Walk the produced-value op shape shared by `:rf.cofx/run` and
  `:rf.cofx/generated`: `:rf.cofx/id` carries the cofx keyword and
  `:rf.cofx/value` carries the PRODUCED value. Redact the produced value against
  the cofx's registered classification."
  [tags]
  (let [cofx-id (:rf.cofx/id tags)
        class   (classification-when :cofx cofx-id)]
    (if (or (not class) (not (contains? tags :rf.cofx/value)))
      tags
      (assoc tags :rf.cofx/value (redact-by-classification (:rf.cofx/value tags) class)))))

(defn- project-route-sub-slot
  "rf2-mtzv5m — apply the routing-owned route-sub egress projector to a route
  read sub's value `v`. Defers to the late-bound `:routing/project-route-sub-egress`
  hook (routing publishes it; core stays decoupled — rf2-k682), which re-seeds
  the wire walk at the route slice's runtime-db storage position so the route's
  re-rooted `:sensitive` / `:large` decls match the bare slice the sub returns.
  A no-op pass-through when the hook is unbound (routing artefact absent) or
  `sub-id` is not a framework route read sub (the projector itself fail-opens on
  a non-route id). `frame-id` seeds the walker's `:frame` opt so the per-frame
  elision registry is reachable (the projector inherits `elide-wire-value`'s
  fail-closed posture on a nil / unresolvable frame)."
  [v sub-id frame-id]
  (if-let [project (rf.late-bind/get-fn :routing/project-route-sub-egress)]
    (project sub-id v {:frame frame-id})
    v))

(defn- project-sub-tags
  "Walk `:sub/run` tag shape: `:rf.sub/id` carries the sub query keyword and
  `:rf.sub/value` carries the output. The reactive recompute also stamps a
  `:rf.sub/prev-value` (the prior computed value); it gets the IDENTICAL
  treatment. Classification comes from the sub's registration's per-output-path
  declarations.

  EP-0025: there is NO sub-output PROPAGATION — a sub does not inherit its
  input's sensitivity, and there is no whole-output `:sensitive? true` stamp from
  a propagation table. Only the registration's own declared paths redact.

  rf2-mtzv5m — ROUTE READ SUBS are the one NARROW exception: the framework route
  subs (`:rf/route` / `:rf.route/query` / `:rf.route/params`) are alternate
  PROJECTIONS of the route-owned durable fact, so their value/prev-value are
  ALSO run through the routing-owned egress projector (late-bound, decoupled),
  which re-seeds the walk at `[:rf.runtime/routing :current …]` so the route's
  re-rooted classification — which the SUB REGISTRATION does not carry — matches.
  This is NOT generic propagation: only the route-owned read surfaces, and only
  at egress (in-process `@(subscribe [:rf/route])` stays raw). The route
  projection composes ON TOP of any registration classification (the route subs
  declare none, so in practice the registration step is a no-op for them).

  EP-0002 (rf2-gjq3ow) — FAIL CLOSED on a nil `frame-id`: subs are frame-scoped,
  so a sub trace with no carried frame is malformed; `:rf.sub/value` (and
  `:rf.sub/prev-value` when present) are conservatively redacted.

  rf2-vxgfnd.220 — IMAGE-LOCAL classification: the reactive recompute carries the
  EXACT classification declaration captured for its reaction under an internal
  `:rf.sub/classification` slot (the same `sub-meta` the schema validator read).
  When present it is AUTHORITATIVE — we project from it rather than re-resolving
  `classification-when :sub sub-id` through the ambient registrar, which after the
  frame-generation binding unwinds (one-shot deref) or across HMR/incarnation
  replacement (an ongoing reaction) may see no image metadata or a CONFLICTING
  same-id global registration. Presence (even an empty captured declaration) wins
  over any global; the carrier is stripped here so it never egresses. Absence (a
  trace not stamped by the memo path) falls back to the registrar resolution."
  [tags frame-id]
  (let [sub-id    (:rf.sub/id tags)
        captured? (contains? tags :rf.sub/classification)
        class     (if captured?
                    (normalise-classification (:rf.sub/classification tags))
                    (classification-when :sub sub-id))
        tags      (dissoc tags :rf.sub/classification)
        has-prev? (contains? tags :rf.sub/prev-value)]
    (cond
      (nil? frame-id)
      (cond-> (assoc tags :rf.sub/value rf.privacy/redacted-sentinel :sensitive? true)
        has-prev? (assoc :rf.sub/prev-value rf.privacy/redacted-sentinel))

      :else
      ;; Registration classification (none for the route subs) first, then the
      ;; route-sub egress projection — composed so both can apply. A non-route
      ;; sub with no registration classification leaves `project-slot` an
      ;; identity transform (the projector fail-opens on a non-route id), so
      ;; `tags` rides through reference-preserved (the common case).
      (let [[sens large]  (classification-paths class)
            reg-redact    (fn [v] (if class (redact-with-paths v sens large) v))
            project-slot  (fn [v] (project-route-sub-slot (reg-redact v) sub-id frame-id))]
        (cond-> tags
          true            (assoc :rf.sub/value (project-slot (:rf.sub/value tags)))
          has-prev?       (assoc :rf.sub/prev-value (project-slot (:rf.sub/prev-value tags)))
          (:large? class) (assoc :large? true))))))

(defn- frame-has-declarations?
  "True when `frame-id` carries any elision declaration (sensitive or large).
  Cheap registry read used to gate the full-db walk so the no-classification
  common case stays reference-preserving.

  rf2-wcvv6h: on a CLASSIFIED frame this gate re-reads the registry that the
  downstream `elide-wire-value` walk reads again (a few extra `get-in` reads
  per db-bearing trace event). The gate read is INTENTIONALLY kept separate
  rather than threading pre-resolved tables into the walk: `elide-wire-value`
  is the public egress surface whose fail-closed contract keys off `frame-id`
  live-frame resolution (not the registry tables), so folding the tables down
  would spread that contract across call sites for no production gain — the
  whole projection path is dev/JVM-only (DCE-elided in production CLJS). The
  explicit gate stays readable as-is; clarity wins over a dev-only read count."
  [frame-id]
  (boolean (or (seq (rf.elision/sensitive-declarations frame-id))
               (seq (rf.elision/declarations frame-id)))))

(defn- project-db-tags
  "Walk the `:rf.event/db` slot carried by the `:rf.event/db-pending` (t1) and
  `:rf.event/db-pending-post-flow` (t2) trace events. The slot stamps the FULL
  pending `app-db` value, so its declared paths are the FRAME's app-db elision
  registry, rooted at the db root. We route it through the schema-first wire
  walker `re-frame.elision/elide-wire-value`.

  Gated on `frame-has-declarations?` so a frame with no classification keeps the
  reference-identity the `:rf.event/db` stamp promises. FAIL CLOSED on a nil
  `frame-id`."
  [tags frame-id]
  (cond
    (not (contains? tags :rf.event/db))
    tags

    (nil? frame-id)
    (assoc tags :rf.event/db rf.privacy/redacted-sentinel)

    (frame-has-declarations? frame-id)
    (assoc tags :rf.event/db
           (rf.elision/elide-wire-value (:rf.event/db tags) {:frame frame-id}))

    :else
    tags))

(defn- project-view-rendered-tags
  "Walk the `:rf.view/render-args` slot carried by the `:rf.view/rendered` trace
  event. The slot holds the vector of POSITIONAL render args/props. Like
  `:rf.event/db`, the declared paths are the FRAME's app-db elision registry; we
  route EACH positional arg through `re-frame.elision/elide-wire-value`.

  Gated on `frame-has-declarations?`. FAIL CLOSED on a nil `frame-id`."
  [tags frame-id]
  (cond
    (not (contains? tags :rf.view/render-args))
    tags

    (nil? frame-id)
    (assoc tags :rf.view/render-args
           (mapv (constantly rf.privacy/redacted-sentinel)
                 (:rf.view/render-args tags)))

    (frame-has-declarations? frame-id)
    (assoc tags :rf.view/render-args
           (mapv #(rf.elision/elide-wire-value % {:frame frame-id})
                 (:rf.view/render-args tags)))

    :else
    tags))

(defn- strip-data-prefix
  "Re-root a vector of snapshot-rooted machine classification paths (each
  `[:data …]`) to be `:data`-MAP-relative — drop the leading `:data` segment. A
  path that is NOT under `:data` is dropped. The bare `[:data]` whole-`:data`
  path re-roots to `[[]]` (whole-value)."
  [paths]
  (into []
        (comp (filter #(= :data (first %)))
              (map #(vec (rest %))))
        paths))

;; The reserved inner event-id the runtime dispatches into a PARENT machine
;; when one of its `:spawn`-spawned children FAILS (Spec 005 §`:on-error`).
;; The dispatched shape is `[:rf.machine.spawn/error <invoke-id> <error>]`;
;; `<error>` (the 3rd element) is CHILD-owned. Inlined here (not a require on the
;; machines artefact, which ships ABOVE core) so the projection chokepoint can
;; recognise it without a load cycle.
(def ^:private spawn-error-event-id :rf.machine.spawn/error)

(defn- spawn-error-event?
  "True when `v` is a `[:rf.machine.spawn/error <invoke-id> <error>]` synthetic
  event vector (the parent-failure control-flow event)."
  [v]
  (and (vector? v)
       (>= (count v) 3)
       (= spawn-error-event-id (first v))))

(defn- summarize-spawn-error-event
  "Summarize the synthetic spawn-error event vector for trace egress. Keep the
  STRUCTURAL routing facts — the reserved id and the `<invoke-id>` — and replace
  the CHILD-owned `<error>` payload (3rd element, plus any further args) with the
  `:rf/redacted` sentinel."
  [event]
  (into [(nth event 0) (nth event 1) rf.privacy/redacted-sentinel]
        (repeat (max 0 (- (count event) 3)) rf.privacy/redacted-sentinel)))

(defn- project-spawn-synthetic-payloads
  "Summarize the two CHILD-owned / unclassifiable spawn payloads that egress
  through machine traces, UNCONDITIONALLY (independent of the trace machine's
  declared classification):

    - `:start` (on `:rf.machine.spawn/spawned`) — the newborn child's start
      args; summarized to `:rf/redacted`.
    - the synthetic `[:rf.machine.spawn/error <invoke-id> <error>]` event the
      parent receives, wherever it rides a machine trace: under the bare
      `:event` slot and under `:input :event`. The 3rd element is summarized;
      the reserved id + invoke-id survive."
  [tags]
  (cond-> tags
    (contains? tags :start)
    (assoc :start rf.privacy/redacted-sentinel)

    (spawn-error-event? (:event tags))
    (assoc :event (summarize-spawn-error-event (:event tags)))

    (and (map? (:input tags))
         (spawn-error-event? (get-in tags [:input :event])))
    (assoc-in [:input :event]
              (summarize-spawn-error-event (get-in tags [:input :event])))))

;; The runtime-db prefix under which a machine snapshot lives, per EP-0001 /
;; Spec 005 §Where snapshots live: `[:rf.runtime/machines :snapshots <actor-id>]`.
;; A frame classifies a durable machine `:data` slot by declaring the ABSOLUTE
;; runtime-db path (EP-0025) in its elision registry. The machine trace slots
;; carry the SNAPSHOT value, so the frame's absolute declaration is re-rooted
;; SNAPSHOT-relative by stripping this prefix.
(def ^:private machine-snapshot-prefix [:rf.runtime/machines :snapshots])

(defn frame-snapshot-classification
  "Compute the SNAPSHOT-relative sensitive/large path set the FRAME declares for
  the machine snapshot keyed under `actor-id` (EP-0025, rf2-398kql) — the
  frame-owned replacement for the removed `:data-schema`→classification bridge.
  Reads the frame's elision-registry declarations, keeps only those rooted at
  `[:rf.runtime/machines :snapshots <actor-id> …]`, and strips that prefix so
  the remaining path indexes into the snapshot value (e.g. `[… :data :token]`).
  Returns `{:sensitive [paths] :large [paths]}` (slot omitted when empty), or nil
  when `frame-id` is nil or the frame declares no matching snapshot path.

  Public so the machines SSR-hydration projector (`re-frame.machines.ssr`) can
  reuse the SAME re-rooting for its `:data`-map projection."
  [frame-id actor-id]
  (when (and frame-id actor-id)
    (let [prefix (conj machine-snapshot-prefix actor-id)
          n      (count prefix)
          under  (fn [decls]
                   (into []
                         (comp (filter #(and (>= (count %) n)
                                             (= prefix (subvec (vec %) 0 n))))
                               (map #(subvec (vec %) n)))
                         (keys decls)))
          sens   (under (rf.elision/sensitive-declarations frame-id))
          large  (under (rf.elision/declarations frame-id))]
      (when (or (seq sens) (seq large))
        (cond-> {}
          (seq sens)  (assoc :sensitive sens)
          (seq large) (assoc :large large))))))

(defn- project-action-outcome-shell
  "UNCONDITIONAL (machine-classification-independent) projection of the
  `:rf.machine/action-ran` `:outcome` tag — the action's RAW returned effect
  map (rf2-orcd31). Per Spec 005 §Action effect map the return is `{:data
  :fx}` or nil (`:outcome` is then `:ok`; a throw stamps a keyword error id —
  non-maps pass through untouched here):

    - `:fx` — each `[fx-id args]` entry walks the SAME per-entry
      registration + dynamic classification as the handler-level
      `:rf.event/fx` aggregate (`project-fx-args` via
      `project-event-fx-entry`): a machine action returning
      `[:dispatch [classified-target …]]` or a `:sensitive?`-flagged
      `:rf.http/managed` call redacts here exactly as the same entry will on
      the downstream do-fx aggregate. Registration-driven, so it applies
      even when THIS machine declares no classification.
    - `:db` — hard-disallowed on an action effect map (Spec 005
      §Hard-disallow `:db`); the runtime strips it downstream and emits
      `:rf.error/machine-action-wrote-db`, whose `:offending-value` is
      summarized UNCONDITIONALLY (`project-machine-wrote-db-tags`). The raw
      `:outcome` echo of that same disallowed value gets the SAME
      unconditional summarization — otherwise this one slot would out-leak
      the error trace it always accompanies.

  The machine-classified `:data` half of `:outcome` is projected under the
  class gate in `project-machine-tags` (the same `:data`-rooted path set as
  the bare `:data` slot)."
  [tags]
  (let [outcome (:outcome tags)]
    (if-not (map? outcome)
      tags
      (assoc tags :outcome
             (cond-> outcome
               (vector? (:fx outcome)) (update :fx #(mapv project-event-fx-entry %))
               (contains? outcome :db) (assoc :db rf.privacy/redacted-sentinel))))))

(defn- project-machine-tags
  "Walk machine `:data`-bearing trace tag shapes. Paths are rooted at the
  SNAPSHOT — per Spec 015 §State machines — so common paths are written as
  `[:data :jwt]`, `[:data :user :ssn]`, etc.

  EP-0025 (rf2-398kql): the classification comes from the FRAME's declared
  classification of the machine snapshot path (`frame-snapshot-classification`,
  the frame-owned sole app-db mechanism), UNIONED with any author classification
  on the machine's `:event` registration meta. The prior machine
  `:data-schema`→classification bridge (EP-0005) is removed.

  Machine `:data` surfaces in several differently-shaped trace slots, and EVERY
  one is redacted: `:before` / `:after` / `:snapshot` (full snapshot maps),
  `:data` (bare `:data` map, one level shallower), `:input` (`{:data … :event
  …}`), and `:cascade` (step vector each with a `:data-delta`).

  The trace ALSO ECHOES the ROUTED inner event into two slots — top-level
  `:event` and `[:input :event]` (per Spec 005 §Trace events; the router copies
  `(second outer-event)` there). A `:sensitive` payload carried THROUGH the
  machine as that routed event would otherwise ship RAW in these echo slots —
  the generic `project-event-tags` keys off the INNER event-id, which is
  typically unregistered (rf2-ghgbqi, rf2-agb5jk item 2). So the machine's own
  EVENT-rooted classification paths redact these echo slots too. That is safe to
  union with the `:data` snapshot paths on the SAME declaration because the two
  roots are disjoint: an integer event index never matches a `:data`-prefixed
  snapshot key, and a `:data`-prefixed key never matches a vector index — each
  path bites only the surface it is rooted at."
  [tags frame-id]
  ;; The CHILD-OWNED synthetic on-error payload and the `:start` payload are
  ;; summarized UNCONDITIONALLY (independent of the parent/spawn machine's
  ;; classification), and the action-`:outcome` echo's `:fx` / disallowed-`:db`
  ;; halves are registration-driven (rf2-orcd31). Run these first so they apply
  ;; even when the trace's machine declares no `:data` classification.
  (let [tags       (project-spawn-synthetic-payloads tags)
        tags       (project-action-outcome-shell tags)
        machine-id (or (:actor-id tags) (:machine-id tags))
        author     (classification-when :event machine-id)
        frame-mk   (frame-snapshot-classification frame-id machine-id)
        class      (let [s (into (vec (:sensitive author)) (:sensitive frame-mk))
                         l (into (vec (:large author))     (:large frame-mk))]
                     (when (or (seq s) (seq l))
                       (cond-> {}
                         (seq s) (assoc :sensitive s)
                         (seq l) (assoc :large l))))]
    (if-not class
      tags
      (let [[sens large] (classification-paths class)
            data-sens  (strip-data-prefix sens)
            data-large (strip-data-prefix large)
            project    (fn [v] (when v (redact-with-paths v sens large)))
            project-data (fn [v] (when v (redact-with-paths v data-sens data-large)))
            project-input (fn [input]
                            (if (map? input)
                              (cond-> input
                                ;; `:data` is snapshot-relative (one level
                                ;; shallower than a full snapshot map).
                                (contains? input :data)  (update :data project-data)
                                ;; `:event` echoes the routed inner event —
                                ;; redacted by the machine's EVENT-rooted paths
                                ;; (rf2-ghgbqi), mirroring the top-level `:event`.
                                (contains? input :event) (update :event project))
                              input))
            project-cascade (fn [cascade]
                              (when (vector? cascade)
                                (mapv (fn [step]
                                        (if (and (map? step) (contains? step :data-delta))
                                          (assoc step :data-delta
                                                 (project-data (:data-delta step)))
                                          step))
                                      cascade)))]
        (cond-> tags
          (contains? tags :before)   (assoc :before   (project (:before   tags)))
          (contains? tags :after)    (assoc :after    (project (:after    tags)))
          (contains? tags :snapshot) (assoc :snapshot (project (:snapshot tags)))
          (contains? tags :data)     (assoc :data     (project-data (:data tags)))
          ;; rf2-orcd31 — the action's returned effect map echoed at
          ;; `:rf.machine/action-ran`'s `:outcome`: its `:data` half is
          ;; snapshot-`:data`-shaped (one level shallower, exactly like the
          ;; bare `:data` slot), so the SAME `:data`-rooted path set applies.
          ;; (The `:fx` half + the disallowed-`:db` echo are projected
          ;; unconditionally in `project-action-outcome-shell` — they key off
          ;; the fx registrations / the hard-disallow, not this machine's
          ;; classification.)
          (map? (:outcome tags))     (update :outcome
                                             (fn [outcome]
                                               (if (contains? outcome :data)
                                                 (update outcome :data project-data)
                                                 outcome)))
          ;; The routed inner event echoed at the top level — redacted by the
          ;; machine's EVENT-rooted classification (rf2-ghgbqi). `project` is
          ;; a whole-value path walk; only the machine's event-rooted paths
          ;; bite here (the disjoint-root reasoning in the docstring).
          (contains? tags :event)    (assoc :event    (project (:event    tags)))
          (contains? tags :input)    (assoc :input    (project-input (:input tags)))
          (contains? tags :cascade)  (assoc :cascade  (project-cascade (:cascade tags))))))))

(defn- redact-exception-data-slot
  "Fail-closed whole-slot redaction tail shared by the two exception-payload
  projectors: replace the WHOLE `:exception-data` slot with the `:rf/redacted`
  sentinel and stamp `:sensitive? true`."
  [tags]
  (assoc tags :exception-data rf.privacy/redacted-sentinel :sensitive? true))

(defn- project-machine-error-tags
  "Walk the `:rf.error/machine-action-exception` tag shape. The trace carries
  `:exception-data` — the `ex-data` of a thrown machine action — which could
  embed the same app secrets the machine's `:data` classification gates. The
  conservative, footgun-prevention posture: when the machine declares ANY
  `:sensitive` classification, elide the WHOLE `:exception-data` slot.

  EP-0025 (rf2-398kql): the \"machine declares ANY `:sensitive`\" decision now
  consults the FRAME's snapshot-path classification (the sole app-db mechanism)
  unioned with any author `:event` registration classification.

  A nil / unresolvable frame FAILS CLOSED (redacts) — matching the sibling
  `project-flow-failed-tags` and Spec 015 §Failure-posture (unknown frame =>
  fail closed). `:exception-data` is author-shaped `ex-data` that cannot be
  path-walked safely, so a nil frame redacts the WHOLE slot."
  [tags frame-id]
  (let [machine-id (or (:actor-id tags) (:machine-id tags))
        author     (classification-when :event machine-id)
        frame-mk   (frame-snapshot-classification frame-id machine-id)]
    (if (and (contains? tags :exception-data)
             (or (nil? frame-id)
                 (seq (:sensitive author)) (seq (:sensitive frame-mk))))
      (redact-exception-data-slot tags)
      tags)))

(defn- project-flow-failed-tags
  "Walk the `:rf.flow/failed` tag shape. The trace carries the throwing flow's
  structured exception summary — `:exception-message` (plain string) +
  `:exception-data` (the ex-info `ex-data` map). The `:inputs` slot already rode
  the wire-elision walker at emit time; the `:exception-data` map is the
  developer's arbitrary author-keyed payload. When the flow's FRAME declares ANY
  sensitive elision declaration, elide the WHOLE `:exception-data` slot. A nil /
  unresolvable frame FAILS CLOSED (redacts)."
  [tags]
  (let [frame-id (:frame tags)]
    (if (and (contains? tags :exception-data)
             (some? (:exception-data tags))
             (or (nil? frame-id)
                 (seq (rf.elision/sensitive-declarations frame-id))))
      (redact-exception-data-slot tags)
      tags)))

(defn- project-machine-wrote-db-tags
  "Walk the `:rf.error/machine-action-wrote-db` tag shape. A machine action that
  wrongly carries a `:db` key is rejected, emitting this error carrying the
  STRIPPED `:db` value under `:offending-value` — the WHOLE app-db. The whole
  `:offending-value` slot is summarized to `:rf/redacted` UNCONDITIONALLY."
  [tags]
  (if (contains? tags :offending-value)
    (assoc tags :offending-value rf.privacy/redacted-sentinel)
    tags))

(defn- resource-failure-op?
  "True for the resource / mutation FAILURE trace ops whose tags carry the
  `:rf.http/*` failure ENVELOPE under `:error` / `:page-error`."
  [operation]
  (case operation
    (:rf.resource/failed :rf.resource/page-failed :rf.mutation/failed) true
    false))

(defn- project-resource-error-tags
  "Walk the resource/mutation FAILURE trace tag shape. The failure rows carry
  the `:rf.http/*` reply's failure ENVELOPE (raw `:body` / `:body-text` /
  `:detail`) under `:error` / `:page-error` — app-owned data echoing submitted
  form fields. Each present envelope slot is summarized to `:rf/redacted`."
  [tags]
  (cond-> tags
    (some? (:error tags))      (assoc :error      rf.privacy/redacted-sentinel)
    (some? (:page-error tags)) (assoc :page-error rf.privacy/redacted-sentinel)))

(defn- machine-op?
  [operation]
  (let [n (and (keyword? operation) (namespace operation))]
    (and n (or (= "rf.machine" n)
               (and (>= (count n) 11)
                    (= "rf.machine." (subs n 0 11)))))))

(defn- interceptor-ref-id?
  "True when `x` is a structurally-valid interceptor REFERENCE id — a bare
  keyword, or an `[id arg]` 2-vector whose head is a keyword."
  [x]
  (or (keyword? x)
      (and (vector? x)
           (= 2 (count x))
           (keyword? (first x)))))

(defn- project-override-summary-tags
  "Fail-closed projection for the `:rf.interceptor/override-summary` trace tag.
  Every `<ref-id>` is a bare keyword or an `[id arg]` 2-vector reference — never
  an interceptor value. This projection re-asserts that shape fail-closed: a bare
  keyword id rides verbatim; an `[id arg]` ref reduces to its head keyword;
  anything else collapses to `:rf/redacted`. The scalar `:count` is kept when a
  number."
  [tags]
  (let [summary (:rf.interceptor/override-summary tags)]
    (if-not (map? summary)
      (dissoc tags :rf.interceptor/override-summary)
      (let [sanitize-id (fn [x]
                          (cond
                            (keyword? x)            x
                            (interceptor-ref-id? x) (first x)
                            :else                   rf.privacy/redacted-sentinel))
            sanitize-vec (fn [v]
                           (when (sequential? v)
                             (mapv sanitize-id v)))
            cnt          (:count summary)
            projected    (cond-> {}
                           (contains? summary :matched)
                           (assoc :matched (sanitize-vec (:matched summary)))
                           (contains? summary :replaced)
                           (assoc :replaced (sanitize-vec (:replaced summary)))
                           (contains? summary :removed)
                           (assoc :removed (sanitize-vec (:removed summary)))
                           (number? cnt)
                           (assoc :count cnt))]
        (assoc tags :rf.interceptor/override-summary projected)))))

(defn project-trace-event
  "The single chokepoint `re-frame.trace/build-event` consults after envelope
  assembly and before delivery. Walks `:tags` for classification declared on the
  in-scope registrations (and the frame's app-db registry). Returns the
  (possibly mutated) event. The cost is gated by `interop/debug-enabled?`
  upstream so production builds elide before this fn is reached.

  Frame resolution comes off `:tags :frame`; there is NO `:rf/default` floor. The
  process-scoped per-registration projections (event / fx / cofx / machine) apply
  regardless. The frame-QUALIFIED projections (`project-db-tags` /
  `project-view-rendered-tags` / `project-sub-tags`) receive the carried
  `frame-id`; when it is nil they FAIL CLOSED."
  [event]
  (if-not (map? event)
    event
    (let [operation (:operation event)
          tags      (:tags event)
          frame-id  (:frame tags)
          ;; `tags` is shape-invariant through the threading — every per-kind
          ;; projector returns a map, so the type never becomes non-map. Hoist
          ;; the one `(map? tags)` guard here so each branch below reads as just
          ;; its real per-kind predicate (the slot `contains?` / `operation =`
          ;; test). A non-map `tags` no-ops the chain.
          tags'     (if-not (map? tags)
                      tags
                      (cond-> tags
                        (contains? tags :rf.event/v)
                        (project-event-tags :rf.event/v)

                        ;; The bare `:event` slot on the realm-AMBIGUOUS
                        ;; `:rf.error/frame-destroyed` trace carries a raw
                        ;; subscription QUERY VECTOR in the `:subscribe` realm
                        ;; (public IDENTITY — rf2-zwgqe / rf2-alk8a / Spec 015),
                        ;; NOT a dispatched event. It egresses VERBATIM here,
                        ;; mirroring the always-on record's
                        ;; `error-emit/raw-identity-query-vector-event?` skip
                        ;; (keyed on the SAME `:op :subscribe` realm the UI /
                        ;; core emitters stamp). Projecting it would misread the
                        ;; vector as a dispatched event and borrow a same-id
                        ;; EVENT registration's `:sensitive` paths to mutate the
                        ;; identity — a LEGAL event/sub id collision, since the
                        ;; two live in SEPARATE registries (rf2-wd4ac). Every
                        ;; OTHER `:event` slot still projects — including the
                        ;; `:dispatch` / `:dispatch-sync` frame-destroyed realms,
                        ;; whose `:event` IS a dispatched-event payload. `=` on
                        ;; keyword operands, never `identical?` (#6365).
                        (and (contains? tags :event)
                             (not (and (= :rf.error/frame-destroyed operation)
                                       (= :subscribe (:op tags)))))
                        (project-event-tags :event)

                        ;; `:rf.event/fx` — the WHOLE returned effect vector on
                        ;; `:rf.fx/do-fx`; redact each entry's args through its
                        ;; fx registration (sibling of the `:rf.event/db` walk).
                        (contains? tags :rf.event/fx)
                        (project-event-fx-tags)

                        ;; ANY slot carrying the `[:rf.fx/id :rf.fx/args]` pair —
                        ;; `:rf.fx/handled` AND the always-on fx error traces
                        ;; (`:rf.error/fx-handler-exception` + siblings) +
                        ;; `:rf.fx/skipped-on-platform`. Keyed off slot SHAPE,
                        ;; not op `:rf.fx/handled`, so the production-survivable
                        ;; error-trace args redact too (rf2-6h3c02).
                        (and (contains? tags :rf.fx/id)
                             (contains? tags :rf.fx/args))
                        (project-fx-tags)

                        (contains? tags :rf.event/coeffects)
                        (project-cofx-map-slot :rf.event/coeffects)

                        (contains? tags :rf.event/cofx)
                        (project-cofx-map-slot :rf.event/cofx)

                        (contains? tags :rf.event/db)
                        (project-db-tags frame-id)

                        (contains? tags :rf.view/render-args)
                        (project-view-rendered-tags frame-id)

                        (or (= :rf.cofx/run operation)
                            (= :rf.cofx/generated operation))
                        (project-cofx-run-tags)

                        (= :rf.sub/run operation)
                        (project-sub-tags frame-id)

                        (machine-op? operation)
                        (project-machine-tags frame-id)

                        (contains? tags :rf.interceptor/override-summary)
                        (project-override-summary-tags)

                        (= :rf.flow/failed operation)
                        (project-flow-failed-tags)

                        (and (not= :rf.flow/failed operation)
                             (contains? tags :exception-data))
                        (project-machine-error-tags frame-id)

                        (contains? tags :offending-value)
                        (project-machine-wrote-db-tags)

                        (resource-failure-op? operation)
                        (project-resource-error-tags)))]
      (assoc event :tags tags'))))

;; ---- late-bind hook registration ----------------------------------------
;;
;; The trace ns reads through these hooks; this ns reads through the elision
;; registry. The arrangement avoids load cycles (`re-frame.trace` →
;; `re-frame.classification` would cycle since this ns requires elision which
;; requires trace).

(rf.late-bind/set-fn! :classification/project-trace-event project-trace-event)
(rf.late-bind/set-fn! :classification/redact-event-by-registration redact-event-by-registration)
(rf.late-bind/set-fn! :classification/registration-classification registration-classification)
