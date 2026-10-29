(ns re-frame.classification
  "Data-classification path projection for sensitive + large values per
  EP-0025 and Spec 015.

  This namespace owns the EGRESS-TIME PROJECTION substrate: the path-walk +
  sentinel substitution that redacts `:rf/redacted` at `:sensitive` paths and surfaces
  `:rf.size/large-elided` markers at `:large` paths. There is NO classification
  PROPAGATION (no sub / flow input → output inheritance, no value-match) and NO
  imperative `add-marks` / `set-marks` API.

  It owns:
    - Per-registration classification — DERIVED at read time
      (`registration-classification`) from the registration metadata
      `re-frame.registrar` already holds, normalised to
      `{:sensitive [paths] :large [paths] :large? bool}`. The author keys
      (`:sensitive` / `:large` / `:large?`) are stored by every reg-* path on
      the registrar entry, so the projection re-derives them through the SAME
      `normalise-classification` validation the registration ran — no
      duplicated imperative side-table.
    - Emit-time projection — the path-walk + sentinel substitution consumed by
      `re-frame.trace/build-event` to redact a trace event's `:tags` against
      the in-scope registrations' declared paths and the frame's app-db
      classification.

  Per Spec 015 §Hot-path cost: the entire surface rides
  `re-frame.interop/debug-enabled?` — registrations still populate tables at
  boot (constant memory), but emit-time projection is gated and constant-folds
  out of CLJS production bundles via `goog.DEBUG`.

  Durable app-db classification is the frame's static policy
  (`re-frame.elision/frame-policy`): its `reg-classification` declarations,
  the output declarations of the flows it runs, and the subsystem claims it
  makes on the instances the frame's runtime-db holds.
  There is no durable `:sensitive` / `:large {:app-db …}`
  *frame annotation* (a frame is not app-db's definition site), and
  classification does NOT propagate — you redact exactly the paths you classify;
  nothing is inherited (no derived-output sensitivity, no value-match)."
  (:require [re-frame.elision :as rf.elision]
            [re-frame.error :as rf.error]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.path :as rf.path]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.source-coords :as rf.source-coords]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- per-registration classification: DERIVED from the registrar
;;
;; A registration's declared classification (`:sensitive` / `:large` /
;; `:large?`) is NOT stashed into a second imperative side-table at registration
;; time. Every reg-* path already stores the author meta on its
;; `re-frame.registrar` entry, so `registration-classification` DERIVES it at
;; read time by running `normalise-classification` over
;; `rf.registrar/handler-meta` — the SAME validation path the registration ran. The
;; registrar is the single source of truth, snapshot/restored by the test-
;; isolation runtime fixture for free.

;; ---- malformed-declaration rejection --------------------------------------
;;
;; A `:sensitive` / `:large` declaration is a vector of output-path vectors
;; (`[[:user :ssn] [:auth :token]]`; `[[]]` marks the whole value). We REJECT
;; LOUDLY at the ingestion boundary (`validate-classification!`, called from each
;; reg-* path BEFORE the registrar write): a hand-written typo —
;; `:sensitive :token` (bare keyword), `:sensitive "blob"` (string),
;; `:sensitive {…}` (map), or `:sensitive [:token]` / `[[:a [:b]]]` (a
;; non-vector / non-scalar-element entry) — would otherwise register with NO
;; error and NO classification, the worst failure mode for a
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
  dropped (fail-loud, not fail-open).

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
  not propagate. A `:rf.egress/output-sensitivity` enum or a boolean
  `:sensitive?` key is silently dropped if present (no propagation reads
  them). `:large?` is the whole-output size override (size has no
  propagation analogue).

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
  FAIL-LOUD. Returns nil. No-op (no throw) when `meta`
  carries no classification-relevant keys or carries only well-formed ones.

  Called from each reg-* path BEFORE the underlying registrar write. It runs the
  SAME `normalise-classification` the projection re-runs at read time and
  DISCARDS the result — its only job is the throw-side-effect: a malformed
  `:sensitive` / `:large` declaration raises `:rf.error/bad-classification` at
  registration rather than silently mis-deriving at the first emit. NOTHING is
  stashed. The `kind` arg is accepted for call-site symmetry but unused."
  ([meta] (validate-classification! nil meta))
  ([_kind meta]
   (normalise-classification meta)
   nil))

(defn- classification-of
  "The classification declared on the handler-meta `lookup` returns, or nil.
  A lookup that hits an id more than one namespace registers fails closed: the
  whole value is `:sensitive`."
  [lookup]
  (try
    (normalise-classification (lookup))
    (catch #?(:clj Throwable :cljs :default) e
      (if (rf.registrar/ambiguous-registration? e)
        {:sensitive [[]]}
        (throw e)))))

(defn registration-classification
  "Return the classification declaration for `(kind, id)`, or nil — DERIVED at
  read time from `rf.registrar/handler-meta`, NOT a duplicated
  side-table.

  The returned shape is `{:sensitive [paths] :large [paths] :large? bool}` —
  slots are present only when the registration declared them. EP-0025: there is
  no derived-output sensitivity (no propagation).

  An id more than one namespace registers, read inside a frame made without
  `:images`, has no single declaration to follow, so it fails closed: the
  whole value is `:sensitive`."
  [kind id]
  (classification-of #(rf.registrar/handler-meta kind id)))

;; ---- static app-db classification: reg-classification ---------------------

(def ^:private classification-decl-keys
  "The closed key set of a `reg-classification` declaration."
  #{:sensitive :large :doc})

(defn reg-classification*
  "Programmatic form of `reg-classification` (no macro source-coordinate
  capture). Registers `decl` under the registrar kind `:classification`, keyed
  by the keyword `id`: a closed map of `:sensitive` and `:large`, each a vector
  of app-db-ABSOLUTE paths (`[]` classifies the whole of app-db), plus an
  optional `:doc`. The paths take the same vocabulary and collection matcher as
  a registration's transient declarations. Each frame's egress classification
  unions the registrations its own view resolves, so a declared path is
  redacted from its first egress (`re-frame.elision/frame-policy`). A malformed
  declaration raises `:rf.error/bad-classification`. Returns `id`.

  Spec 015 §Static app-db classification."
  [id decl]
  (when-not (keyword? id)
    (throw (classification-error :id "reg-classification's id must be a keyword"
                                 {:bad-value id})))
  (when-not (map? decl)
    (throw (classification-error :decl
                                 (str "reg-classification's declaration must be a map of "
                                      ":sensitive / :large path vectors, plus an optional :doc")
                                 {:bad-value decl})))
  (when-let [unknown (seq (remove classification-decl-keys (keys decl)))]
    (throw (classification-error (first unknown)
                                 (str "a reg-classification declaration is a closed map of "
                                      ":sensitive, :large and :doc")
                                 {:bad-entries (vec unknown)})))
  (let [sensitive (coerce-paths (:sensitive decl) :sensitive)
        large     (coerce-paths (:large decl) :large)]
    (rf.registrar/register! :classification id
                            (rf.source-coords/merge-coords
                              (cond-> {:sensitive sensitive :large large}
                                (contains? decl :doc) (assoc :doc (:doc decl)))))
    id))

;; ---- emit-time projection ------------------------------------------------

(defn large-marker
  "Build the `:rf.size/large-elided` marker for value `v` at `path`. Delegates
  to `re-frame.elision/->marker` with `{:reason :classification}` so the marker
  shape is built in ONE place.

  Public because the off-box epoch egress projector
  (`re-frame.epoch.tool-pair/project-record`) reuses it to substitute the
  marker for a whole-output `:large?`-stamped sub's `:value` / `:prev-value`.

  A value that is already a marker is returned unchanged, so projecting twice
  equals projecting once: a second marker would measure the first one rather
  than the payload."
  [v path]
  (if (rf.elision/marker? v)
    v
    (rf.elision/->marker v path {:reason :classification})))

;; ---- the declaration-coordinate candidate set -----------------------------
;;
;; The walker threads `[path candidates]`: the CONCRETE runtime path (which
;; drives the `:large` marker's `:path` / `:handle`) beside a SET of candidate
;; DECLARATION coordinates — the positions in declaration space the walker
;; could currently be standing at. The candidate machinery is
;; `re-frame.elision`'s, shared with the schema-first wire walker (its
;; §collection-coordinate declaration matching): the prefix vocabulary that
;; prunes the set, the map-key / index forks, and the large-over-sensitive
;; collision set. In the DEFAULT mode the forks keep only the named-segment
;; interpretation, so the set IS exactly `#{path}` while `path` is still some
;; declaration's prefix and `#{}` once it is not: the exact path membership
;; test. `:index-free?` adds the collection-coordinate interpretations the wire
;; walker always uses.

(defn- walk-with-paths
  "Walk `v` and substitute sentinels at the declared paths. Paths in
  `sensitive-paths` and `large-paths` are rooted at `v`. Sensitive wins over
  large at the same path; a large-marked subtree containing a sensitive
  descendant gets the ordinary size marker, measured over the subtree after
  that descendant redacts.

  `index-free?` selects how a COLLECTION is read. Default false — every index
  and key is a declaration segment, so matching is the exact path membership
  test. True — an index, and a map key below a declaration's first segment, is
  a COLLECTION COORDINATE that consumes no declared segment: the index-free
  declaration `[:value :email]` matches the runtime `[:value <i> :email]` in
  every element, and `[:accounts :token]` matches `[:accounts <k> :token]`
  under every key of a `:map-of`. A key at the root always consumes a segment,
  so `[:auth :password]` never reaches a same-named pair under some other
  slot. A declaration that pins a literal index or key still matches either
  way (that interpretation is retained whenever some declaration reaches it),
  so the mode only ever ADDS the per-element reading.

  No-op early-exit: when both path sets are empty, returns `v` unchanged.
  Shares the recursion skeleton (`re-frame.elision/walk-tree`) and the
  candidate forks with the schema-first elision walker; only the per-node
  decision is its own."
  [v sensitive-paths large-paths index-free?]
  (if (and (empty? sensitive-paths) (empty? large-paths))
    v
    (let [sensitive-set (set (map vec sensitive-paths))
          large-set     (set (map vec large-paths))
          prefixes      (rf.elision/decl-prefix-set (concat sensitive-set large-set))
          shadow-set    (rf.elision/large-paths-shadowing-sensitive large-set sensitive-set)
          matches?      (fn [tbl candidates] (boolean (some #(contains? tbl %) candidates)))
          descend
          {:map-key (fn [[path candidates] k]
                      [(conj path k)
                       (rf.elision/fork-decl-paths candidates k prefixes index-free?)])
           :index   (fn [[path candidates] i]
                      [(conj path i)
                       (rf.elision/fork-index-paths candidates i prefixes index-free?)])
           ;; A seq the app has not forced is never walked. A declared path
           ;; that can still reach inside it fails closed; otherwise it ships
           ;; as found.
           :unforced (fn [[_path candidates] s]
                       (if (seq candidates) rf.privacy/redacted-sentinel s))
           :leaf    (fn [_state v] v)}
          ;; Measures a collision node: sensitive redaction only, large off.
          measure
          (assoc descend :decide
                 (fn [[_path candidates] _v]
                   (if (matches? sensitive-set candidates)
                     rf.privacy/redacted-sentinel
                     rf.elision/walk-recur)))]
      (rf.elision/walk-tree
        v [[] #{[]}]
        (assoc descend :decide
               (fn [[path candidates :as state] v]
                 (cond
                   (matches? sensitive-set candidates) rf.privacy/redacted-sentinel

                   ;; A large node holding a sensitive descendant gets the
                   ;; ordinary marker, measured over the subtree after that
                   ;; descendant redacts (Spec 015 §No propagation).
                   (and (matches? large-set candidates)
                        (seq shadow-set)
                        (matches? shadow-set candidates)
                        (not (rf.elision/marker? v)))
                   (large-marker (rf.elision/walk-tree v state measure) path)

                   (matches? large-set candidates)     (large-marker v path)
                   :else                               rf.elision/walk-recur)))))))

(defn redact-with-paths
  "Public projection helper. Walks `v` and substitutes sentinels at the declared
  paths. Empty `[[]]` path substitutes the whole value (sensitive wins over large
  at the root). Per Spec 015 §What gets a sentinel.

  `opts` may carry `:index-free? true` for a caller whose declared
  paths are INDEX-FREE — written against the shape rather than against a
  concrete runtime position, so a positional container, or a `:map-of` below
  a declaration's first segment, contributes no segment and the declaration
  names EVERY element. That is the kind a
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

(defn- event-classification
  "The classification of the handler that serves events addressed to `id` in
  `frame-id`. That is `id`'s own event registration; an id with none that
  names a live spawned machine actor is served by the handler-meta the
  late-bound `:machines/resolve-actor-handler-meta` builds — the router's own
  route to it — which carries the actor's machine TYPE classification, since
  the type's handler serves every instance. Spec 005 §Privacy."
  [id frame-id]
  (when id
    (or (registration-classification :event id)
        (when-let [resolve! (and (some? frame-id)
                                 (nil? (rf.registrar/handler-meta :event id))
                                 (rf.late-bind/get-fn :machines/resolve-actor-handler-meta))]
          (classification-of #(resolve! [id] frame-id))))))

(defn- project-execute-event-payload
  "Per-event-id DYNAMIC projection for a `[:rf.mutation/execute <args>]` event
  vector. The execute payload's classification lives on the
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

(defn- project-navigation-event-arg
  "Per-event-id DYNAMIC projection for a routing navigation event vector —
  `[:rf.route/handle-url-change url …]` and `[:rf.route/navigate {request}]`.
  The first argument (the URL, or the
  request's `:url` or `:to` route's `:params` / `:query` / `:fragment`) carries
  a route's path params and query values, whose classification lives on the
  ROUTE it names (its `:sensitive` declaration), not on the event registration,
  so the static registration layer cannot express it. Defers to the
  late-bound `:routing/project-url-change-event-args` hook (published by
  `re-frame.routing`; core stays decoupled). Hook unbound (routing artefact
  absent) ⇒ pass-through: without the artefact the event has no handler at
  all. Identity-preserving when nothing applies."
  [event]
  (if-let [project (rf.late-bind/get-fn :routing/project-url-change-event-args)]
    (if (>= (count event) 2)
      (let [arg  (nth event 1)
            arg' (project arg)]
        (if (identical? arg arg') event (assoc event 1 arg')))
      event)
    event))

;; The reserved inner event-id the runtime dispatches into a PARENT machine
;; when one of its `:spawn`-spawned children FAILS (Spec 005 §`:on-error`).
;; The inner shape is `[:rf.machine.spawn/error <invoke-id> <error>]`;
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

(defn- project-spawn-error-event
  "Summarize the child-owned payload of a spawn-error event in either of the
  two shapes it egresses in: the inner `[:rf.machine.spawn/error <invoke-id>
  <error>]` a parent's transition and guard/action traces echo, and the routed
  carrier `[<parent-id> [:rf.machine.spawn/error …]]` the parent is dispatched,
  which every `:rf.event/v`, `:event-received` and error-record slot carries.
  Any other value is returned unchanged."
  [v]
  (cond
    (spawn-error-event? v)
    (summarize-spawn-error-event v)

    (and (vector? v) (spawn-error-event? (nth v 1 nil)))
    (assoc v 1 (summarize-spawn-error-event (nth v 1)))

    :else
    v))

(defn redact-event-by-registration
  "Project an event vector for egress — the SINGLE event-vector chokepoint,
  the event peer of `project-fx-args`. Two layers compose:

  1. The event REGISTRATION's static `:sensitive` / `:large` classification
     declared under `(first event)`, applied to the arg-map paths (EP-0015 —
     event args are registration-owned transient payloads).
  2. Per-event-id DYNAMIC classification the static model cannot express:
     `:rf.mutation/execute` — the payload's classification lives on the
     MUTATION spec named inside the args (the per-owner declaration
     surface), so the resources-published
     `:resources/project-execute-event-args` hook projects it
     (`project-execute-event-payload`); `:rf.route/handle-url-change`
     and `:rf.route/navigate` — the URL's or request's classification lives
     on the ROUTE it names, so the
     routing-published `:routing/project-url-change-event-args` hook projects
     it (`project-navigation-event-arg`). Unbound ⇒ pass-through.

  A spawn-error event — inner or routed to its parent — has its child-owned
  payload summarized whatever the parent declares (`project-spawn-error-event`).

  With `frame-id`, an event addressed to a spawned machine actor in that frame
  takes its machine type's registration classification (`event-classification`);
  without one, only `(first event)`'s own registration applies.

  A no-op when `event` is not a `[event-id arg-map …]` vector or nothing
  applies.

  ALWAYS-ON (NOT gated on `interop/debug-enabled?`) — the registration
  classification is populated at registration time in production as well as dev
  (only the emit-time TRACE projection is dev-gated), and this fn is the
  production egress consumer.

  Published via the `:classification/redact-event-by-registration` late-bind
  hook; `re-frame.projection` consumes it for the `:rf.observe/error` /
  handled-event `:event` slot, and `project-event-tags` / the `:dispatch` /
  `:dispatch-later` fx-arg recursion route every other event-bearing slot
  through it, so all of them redact identically."
  ([event] (redact-event-by-registration event nil))
  ([event frame-id]
   (let [event' (if-let [class (event-classification (when (vector? event) (first event)) frame-id)]
                  (let [[sens large] (classification-paths class)]
                    (redact-event-vec event sens large))
                  event)
         event' (project-spawn-error-event event')]
     (case (when (vector? event') (first event'))
       :rf.mutation/execute (project-execute-event-payload event')
       (:rf.route/handle-url-change
        :rf.route/navigate) (project-navigation-event-arg event')
       event'))))

(defn- project-event-tags
  "Walk `:rf.event/dispatched` / `:rf.event/db-changed` / `:rf.fx/do-fx` tag
  shapes: the dispatched event vector lives at `:rf.event/v` and is a
  `[event-id arg-map]` form; the `:rf.error/*` error traces carry it under the
  bare `:event` slot, so we redact whichever slot the trace carries — through
  `redact-event-by-registration`, the single event-vector chokepoint (static
  registration classification + the per-event-id dynamic layer),
  so this slot, the always-on `:rf.observe/*` records, and the `:dispatch` /
  `:dispatch-later` fx-arg recursion all redact identically.
  Reference-preserving when nothing applies."
  [tags slot]
  (let [event  (get tags slot)
        event' (redact-event-by-registration event (:frame tags))]
    (if (identical? event event')
      tags
      (assoc tags slot event'))))

(defn- project-dispatch-later-args
  "Redact a `:dispatch-later` args map (`{:ms <ms> :event [target-id arg-map …]}`
  — Spec 002 §Reserved fx-ids) by recursing the carried TARGET event through
  the target's OWN registration classification. `:dispatch-later` is a
  reserved fx with no registration to declare paths on, but the event it
  defers has an owner.
  Identity-preserving when the target declares none."
  [args frame-id]
  (if-let [event (and (map? args) (:event args))]
    (let [event' (redact-event-by-registration event frame-id)]
      (if (identical? event event') args (assoc args :event event')))
    args))

(defn- project-fx-args
  "Redact ONE fx's `args` value through everything its `fx-id` declares — the
  chokepoint shared by every fx-arg-bearing trace slot (`project-fx-tags` for
  the `[:rf.fx/id :rf.fx/args]` pair, `project-event-fx-entry` for the
  `:rf.event/fx` aggregate entries, and the machine action-`:outcome` `:fx`
  echo). Two layers compose:

  1. The fx REGISTRATION's static `:sensitive` / `:large` paths.
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
  not a bug this closes).

  `frame-id` is the frame the trace belongs to, which a `:dispatch` /
  `:dispatch-later` target addressed to a spawned actor resolves its type in."
  [fx-id args frame-id]
  (let [args (if-let [class (classification-when :fx fx-id)]
               (redact-by-classification args class)
               args)]
    (case fx-id
      :dispatch        (redact-event-by-registration args frame-id)
      :dispatch-later  (project-dispatch-later-args args frame-id)
      :rf.http/managed (if-let [project (rf.late-bind/get-fn :http/project-managed-fx-args)]
                         (project args)
                         args)
      args)))

(defn- project-fx-tags
  "Redact the fx args carried by ANY trace slot that stamps the `[:rf.fx/id
  :rf.fx/args]` pair, through `project-fx-args` — the fx REGISTRATION's
  declared `:sensitive` / `:large` PLUS the per-fx-id dynamic classification
  (`:dispatch` / `:dispatch-later` target inheritance, `:rf.http/managed`'s
  per-call `:sensitive?`).

  Covers the per-effect `:rf.fx/handled` success trace AND the always-on fx
  ERROR traces (`:rf.error/fx-handler-exception` + its siblings) AND
  `:rf.fx/skipped-on-platform` — every one stamps the SAME `:rf.fx/id` +
  `:rf.fx/args`, so every one gets the SAME redaction. Keying
  off the SLOT SHAPE (both keys present) rather than op `:rf.fx/handled` is
  what closes the error-trace `:rf.fx/args` leak — the production-survivable
  `:rf.error/fx-handler-exception` fans out through the always-on error-emit
  listener, not just the dev trace, so an fx that persists / sends a
  classified value (a session token to localStorage, an auth header) must NOT
  egress it raw when the fx throws.

  A KEYWORD-REDIRECTED fx (`:fx-overrides` id-redirect) stamps
  the ORIGINAL fx-id as `:rf.fx/from` alongside the resolved `:rf.fx/id`,
  and the args are the SAME value the caller shaped for the ORIGINAL fx's
  contract — so the walk composes `project-fx-args` for BOTH ids: the
  redirect TARGET's own declaration first (an app stub may declare its own
  static paths), then the ORIGINAL registration's static paths + per-fx-id
  dynamic classification. Without it a
  `:sensitive? true` `:rf.http/managed` request redirected to a test stub
  would ride RAW on the stub's own `:rf.fx/handled` — the stub
  registration declares nothing and the resolved id matches no dynamic
  case, but the ORIGINAL id carries both."
  [tags]
  (if-not (contains? tags :rf.fx/args)
    tags
    (let [args  (:rf.fx/args tags)
          from  (:rf.fx/from tags)
          args' (project-fx-args (:rf.fx/id tags) args (:frame tags))
          args' (if (and from (not= from (:rf.fx/id tags)))
                  (project-fx-args from args' (:frame tags))
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
  [entry frame-id]
  (if (and (vector? entry) (>= (count entry) 2))
    (let [args  (nth entry 1)
          args' (project-fx-args (first entry) args frame-id)]
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
  per-event trace sequence). The per-effect `:rf.fx/handled` redaction alone
  is not enough: without this walk a classified fx's args would survive
  RAW on this always-stamped `:rf.event/fx` vector even though `:rf.fx/handled`
  redacts them."
  [tags]
  (let [fx-vec (:rf.event/fx tags)]
    (if-not (vector? fx-vec)
      tags
      (assoc tags :rf.event/fx (mapv #(project-event-fx-entry % (:frame tags)) fx-vec)))))

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
  "Apply the routing-owned route-sub egress projector to a route
  read sub's value `v`. Defers to the late-bound `:routing/project-route-sub-egress`
  hook (routing publishes it; core stays decoupled), which re-seeds
  the wire walk at the route slice's runtime-db storage position so the route's
  re-rooted `:sensitive` / `:large` decls match the bare slice the sub returns.
  A no-op pass-through when the hook is unbound (routing artefact absent) or
  `sub-id` is not a framework route read sub (the projector itself fail-opens on
  a non-route id). `frame-id` seeds the walker's `:frame` opt so the frame's
  classification is reachable (the projector inherits `elide-wire-value`'s
  fail-closed posture on a nil / unresolvable frame).

  The five-argument arity projects a PRIOR value, handing the projector the
  inputs it was computed from (`prior-inputs`) and the classification policy
  it was computed under (`prior-policy`), because a navigation replaces the
  current route and the prior value is classified by the route it was computed
  under."
  ([v sub-id frame-id]
   (if-let [project (rf.late-bind/get-fn :routing/project-route-sub-egress)]
     (project sub-id v {:frame frame-id})
     v))
  ([v sub-id frame-id prior-inputs prior-policy]
   (if-let [project (rf.late-bind/get-fn :routing/project-route-sub-egress)]
     (project sub-id v {:frame frame-id} prior-inputs prior-policy)
     v)))

(defn- project-resource-sub-slot
  "Apply the resources-owned read-sub egress projector to a resource or
  mutation read sub's value `v`. Defers to the late-bound
  `:resources/project-resource-sub-egress` hook (resources publishes it; core
  stays decoupled), which redacts the slots the owner's `reg-resource` /
  `reg-mutation` declaration names, re-rooted onto the sub's value. The sub's
  query vector `query-v` names the owner. A no-op pass-through when the hook
  is unbound (resources artefact absent) or `query-v` names no resource or
  mutation read sub.

  The five-argument arity projects a PRIOR value, handing the projector the
  inputs it was computed from (`prior-inputs`) and the classification policy
  it was computed under (`prior-policy`), because a mutation read sub names its
  instance rather than its mutation, and a cleared instance survives only in
  those inputs."
  ([v query-v frame-id]
   (if-let [project (rf.late-bind/get-fn :resources/project-resource-sub-egress)]
     (project query-v v {:frame frame-id})
     v))
  ([v query-v frame-id prior-inputs prior-policy]
   (if-let [project (rf.late-bind/get-fn :resources/project-resource-sub-egress)]
     (project query-v v {:frame frame-id} prior-inputs prior-policy)
     v)))

(declare machine-classification snapshot-value-classification)

(defn- machine-sub-classification
  "The machine classification a `[:rf/machine <id>]` sub's value carries, or nil
  for any other sub. That sub's value IS actor `<id>`'s snapshot, so it is
  redacted by the class the machine's own traces use for that snapshot
  (`machine-classification`), keyed by the id in the carried query vector."
  [tags frame-id]
  (when (= :rf/machine (:rf.sub/id tags))
    (machine-classification frame-id (second (:rf.sub/query-v tags)) (:rf.sub/value tags))))

(defn- project-sub-tags
  "Walk `:sub/run` tag shape: `:rf.sub/id` carries the sub query keyword and
  `:rf.sub/value` carries the output. The reactive recompute also stamps a
  `:rf.sub/prev-value` (the prior computed value); it gets the IDENTICAL
  treatment. Classification comes from the sub's registration's per-output-path
  declarations.

  EP-0025: there is NO sub-output PROPAGATION — a sub does not inherit its
  input's sensitivity, and there is no whole-output `:sensitive? true` stamp from
  a propagation table. Only the registration's own declared paths redact.

  Three families of framework read subs are the NARROW exceptions. The framework
  registers them, so no app can classify them, and each is an alternate
  PROJECTION of a subsystem-owned durable fact, so its value/prev-value also
  takes that fact's classification — only at egress (in-process `@(subscribe …)`
  stays raw).

  The MACHINE SUB `[:rf/machine <id>]` returns actor `<id>`'s whole snapshot, so
  its value/prev-value are walked by the SAME snapshot-rooted classification the
  machine's own traces use (`machine-sub-classification`), unioned with the
  registration's paths into one walk. Its prev-value additionally takes the
  classification the machine hook gives that snapshot itself under the policy
  it was computed with (`snapshot-value-classification`), because the snapshot
  outlives its actor once the actor is destroyed.

  The ROUTE READ SUBS (`:rf/route` / `:rf.route/query` / `:rf.route/params`)
  project the route-owned durable fact, so their value/prev-value are
  ALSO run through the routing-owned egress projector (late-bound, decoupled),
  which re-seeds the walk at `[:rf.runtime/routing :current …]` so the route's
  re-rooted classification — which the SUB REGISTRATION does not carry — matches.
  This is NOT generic propagation: only the route-owned read surfaces, and only
  at egress (in-process `@(subscribe [:rf/route])` stays raw). The route
  projection composes ON TOP of any registration classification (the route subs
  declare none, so in practice the registration step is a no-op for them). Their
  prev-value is projected with the `::prev-inputs` it was computed from, because
  a navigation replaces the current route: the projector classifies the
  leaving route's slice by that route's own declaration.

  The RESOURCE and MUTATION READ SUBS (`:rf/resource` / `:rf.resource/*` and
  `:rf/mutation` / `:rf.mutation/*`) project the owner's durable entry or
  instance, so their value/prev-value are ALSO run through the resources-owned
  egress projector (late-bound, decoupled), which redacts the slots the
  `reg-resource` / `reg-mutation` declaration names, re-rooted onto the sub's
  value. The declaration is read from the owner spec rather than the live
  instances, so a prior value keeps its classification after an evict or a
  clear removes the instance.

  EP-0002 — FAIL CLOSED on a nil `frame-id`: subs are frame-scoped,
  so a sub trace with no carried frame is malformed; `:rf.sub/value` (and
  `:rf.sub/prev-value` when present) are conservatively redacted.

  IMAGE-LOCAL classification: the reactive recompute carries the
  EXACT classification declaration captured for its reaction under an internal
  `:rf.sub/classification` slot (the same `sub-meta` the schema validator read).
  When present it is AUTHORITATIVE — we project from it rather than re-resolving
  `classification-when :sub sub-id` through the ambient registrar, which after the
  frame-generation binding unwinds (one-shot deref) or across HMR/incarnation
  replacement (an ongoing reaction) may see no image metadata or a CONFLICTING
  same-id global registration. Presence (even an empty captured declaration) wins
  over any global; the carrier is stripped here so it never egresses. Absence (a
  trace not stamped by the memo path) falls back to the registrar resolution.
  The memo path's `::prev-inputs` and `::prev-policy` carriers (the inputs the
  prior value was computed from, and the classification policy it was computed
  under — Spec 015 §Retained values keep their recorded classification) are
  read and stripped here too."
  [tags frame-id]
  (let [sub-id      (:rf.sub/id tags)
        captured?   (contains? tags :rf.sub/classification)
        class       (if captured?
                      (normalise-classification (:rf.sub/classification tags))
                      (classification-when :sub sub-id))
        prev-inputs (::prev-inputs tags)
        prev-policy (::prev-policy tags)
        tags        (dissoc tags :rf.sub/classification ::prev-inputs ::prev-policy)
        has-prev?   (contains? tags :rf.sub/prev-value)
        prior       (when (and has-prev? (= :rf/machine sub-id) (some? frame-id))
                      (snapshot-value-classification
                        (second (:rf.sub/query-v tags)) (:rf.sub/prev-value tags)
                        [prev-policy (rf.elision/frame-policy frame-id)]))]
    (cond
      (nil? frame-id)
      (cond-> (assoc tags :rf.sub/value rf.privacy/redacted-sentinel :sensitive? true)
        has-prev? (assoc :rf.sub/prev-value rf.privacy/redacted-sentinel))

      :else
      ;; Registration classification (none for the framework read subs) unioned
      ;; with the machine sub's snapshot classification in one walk, then the
      ;; route-sub and resource-sub egress projections — composed so all can
      ;; apply. Any other sub with no registration classification leaves both
      ;; slots an identity transform (the walk early-exits on no paths; each
      ;; projector fail-opens on a sub it does not own), so `tags` rides
      ;; through reference-preserved (the common case).
      (let [[sens large]     (classification-paths class)
            [m-sens m-large] (classification-paths (machine-sub-classification tags frame-id))
            [p-sens p-large] (classification-paths prior)
            sens             (into sens m-sens)
            large            (into large m-large)
            query-v          (:rf.sub/query-v tags)]
        (cond-> tags
          true            (assoc :rf.sub/value
                                 (project-resource-sub-slot
                                   (project-route-sub-slot
                                     (redact-with-paths (:rf.sub/value tags) sens large)
                                     sub-id frame-id)
                                   query-v frame-id))
          has-prev?       (assoc :rf.sub/prev-value
                                 (project-resource-sub-slot
                                   (project-route-sub-slot
                                     (redact-with-paths (:rf.sub/prev-value tags)
                                                        (into sens p-sens) (into large p-large))
                                     sub-id frame-id prev-inputs prev-policy)
                                   query-v frame-id prev-inputs prev-policy))
          (:large? class) (assoc :large? true))))))

(defn project-sub-value
  "The egress form of `v`, subscription `query-v`'s value in frame `frame-id`:
  the `:rf.sub/value` a `:sub/run` trace of the same query carries once
  projected, because it is that projection (`project-sub-tags`), so a value
  shipped outside a trace cannot drift from the trace's. The derivation-graph
  egress projector applies it to a live sub node's `:value`.

  The sub's registration classification resolves through `frame-id`'s own view
  (`:live-frame/call-with-frame-resolution`), so an image-local declaration
  answers for its frame whatever resolution the caller stands in. A nil
  `frame-id` fails closed to `:rf/redacted`."
  [v query-v frame-id]
  (let [project #(:rf.sub/value
                   (project-sub-tags {:rf.sub/id      (first query-v)
                                      :rf.sub/query-v query-v
                                      :rf.sub/value   v}
                                     frame-id))]
    (if-let [with-owner (when (some? frame-id)
                          (rf.late-bind/get-fn :live-frame/call-with-frame-resolution))]
      (with-owner frame-id project)
      (project))))

(defn- frame-has-declarations?
  "True when `frame-id` carries any elision declaration (sensitive or large).
  Cheap read used to gate the full-db walk so the no-classification common
  case stays reference-preserving.

  On a CLASSIFIED frame this gate re-reads the classification that the
  downstream `elide-wire-value` walk reads again (a few extra `get-in` reads
  per db-bearing trace event). The gate read is INTENTIONALLY kept separate
  rather than threading pre-resolved tables into the walk: `elide-wire-value`
  is the public egress surface whose fail-closed contract keys off `frame-id`
  live-frame resolution (not the declaration tables), so folding them down
  would spread that contract across call sites for no production gain — the
  whole projection path is dev/JVM-only (DCE-elided in production CLJS). The
  explicit gate reads plainly; clarity wins over a dev-only read count."
  [frame-id]
  (boolean (or (seq (rf.elision/sensitive-declarations frame-id))
               (seq (rf.elision/declarations frame-id)))))

(defn- project-db-tags
  "Walk the `:rf.event/db` slot carried by the `:rf.event/db-pending` (t1) and
  `:rf.event/db-pending-post-flow` (t2) trace events. The slot stamps the FULL
  pending `app-db` value, so its declared paths are the FRAME's app-db
  classification, rooted at the db root. We route it through the schema-first wire
  walker `re-frame.elision/elide-wire-value`.

  Gated on `frame-has-declarations?` so a frame with no classification keeps the
  reference-identity the `:rf.event/db` stamp promises. FAIL CLOSED on a nil
  `frame-id`. A declaration exists before any event runs, so a path the
  pending db writes is redacted from its first egress."
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

(defn- project-after-delta-segment
  "Apply `project-v` (a fn of `[side k v]`) to every VALUE in one segment delta
  `{:added {k v} :changed {k {:before b :after a}} :removed {k v}}` — the shape
  `re-frame.interceptor/segment-delta` builds. `side` names the context the
  value was read from: `:before` for `:removed` entries and `:changed :before`,
  `:after` for `:added` entries and `:changed :after`. Keys are left alone."
  [seg project-v]
  (let [entries (fn [m side]
                  (if (map? m)
                    (reduce-kv (fn [acc k v] (assoc acc k (project-v side k v))) m m)
                    m))
        changed (fn [m]
                  (if (map? m)
                    (reduce-kv (fn [acc k ba]
                                 (assoc acc k (if (map? ba)
                                                (cond-> ba
                                                  (contains? ba :before) (update :before #(project-v :before k %))
                                                  (contains? ba :after)  (update :after #(project-v :after k %)))
                                                ba)))
                               m m)
                    m))]
    (if-not (map? seg)
      seg
      (cond-> seg
        (contains? seg :added)   (update :added entries :after)
        (contains? seg :changed) (update :changed changed)
        (contains? seg :removed) (update :removed entries :before)))))

(defn- project-after-deltas-tags
  "Walk the `:rf.event/after-deltas` slot on `:rf.event/run-end` — the dev-only
  per-user-`:after` context diff, read through generic trace inspection: one
  `{:rf.interceptor.delta/id … :rf.interceptor.delta/ctx-delta {:coeffects …
  :effects …}}` record per user `:after` that changed the context, each diff
  carrying its `:before` / `:after` VALUES. The standard `:rf.interceptor/path`
  `:after` rewrites `[:coeffects :db]` and widens `[:effects :db]`, so without
  this arm every path-focused handler would ship the frame's classified paths
  RAW past this chokepoint.

  A `:db` value is walked at its TRUE app-db focus: under a path
  focus it is a SLICE, and a root walk cannot match `[:auth :token]` against
  `{:token …}`. The producer, `re-frame.interceptor/invoke-after`, records each
  side's absolute focus in a PRIVATE metadata carrier on the record,
  `{:re-frame.interceptor/db-focus {:before <path|nil> :after <path|nil>}}`;
  this fn is its ONE consumer, and STRIPS it unconditionally (every frame,
  classified or not) so it never egresses. Per value, at focus `p`:
    - nil `frame-id` — FAIL CLOSED to the sentinel;
    - a frame with no declarations — identity (reference-preserving);
    - `p` a vector — `elide-wire-value {:frame f :path p}` (`[]` is the root);
    - `p` nil (UNKNOWN: a stack entry recorded no path) — FAIL CLOSED.
  A record with NO carrier (built by hand) reads as focus `[]` on both sides.
  `:fx` values (the `:effects` segment) take no focus: each entry's args
  redact through its fx registration, as `project-event-fx-tags` does for
  `:rf.event/fx`."
  [tags frame-id]
  (let [deltas (:rf.event/after-deltas tags)]
    (if-not (sequential? deltas)
      tags
      (let [declared?  (and (some? frame-id) (frame-has-declarations? frame-id))
            project-db (fn [focus v]
                         (cond
                           (nil? frame-id)  rf.privacy/redacted-sentinel
                           (not declared?)  v
                           (vector? focus)  (rf.elision/elide-wire-value v {:frame frame-id :path focus})
                           :else            rf.privacy/redacted-sentinel))
            project    (fn [rec]
                         (let [m     (meta rec)
                               focus (get m :re-frame.interceptor/db-focus {:before [] :after []})
                               rec   (if (contains? m :re-frame.interceptor/db-focus)
                                       (with-meta rec (not-empty (dissoc m :re-frame.interceptor/db-focus)))
                                       rec)
                               d     (when (map? rec) (:rf.interceptor.delta/ctx-delta rec))
                               cofx-v (fn [side k v] (if (= :db k) (project-db (get focus side) v) v))
                               fx-v   (fn [side k v]
                                        (cond
                                          (= :db k)                   (project-db (get focus side) v)
                                          (and (= :fx k) (vector? v)) (mapv #(project-event-fx-entry % frame-id) v)
                                          :else                       v))]
                           (if-not (map? d)
                             rec
                             (assoc rec :rf.interceptor.delta/ctx-delta
                                    (cond-> d
                                      (contains? d :coeffects) (update :coeffects project-after-delta-segment cofx-v)
                                      (contains? d :effects)   (update :effects project-after-delta-segment fx-v))))))]
        (assoc tags :rf.event/after-deltas (mapv project deltas))))))

(defn- project-view-rendered-tags
  "Walk the `:rf.view/render-args` slot carried by the `:rf.view/rendered` trace
  event. The slot holds the vector of POSITIONAL render args/props. Like
  `:rf.event/db`, the declared paths are the FRAME's app-db classification; we
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

(defn- project-spawn-synthetic-payloads
  "Summarize the two CHILD-owned / unclassifiable spawn payloads that egress
  through machine traces, UNCONDITIONALLY (independent of the trace machine's
  declared classification):

    - `:start` (on `:rf.machine.spawn/spawned`) — the newborn child's start
      args; summarized to `:rf/redacted`.
    - the spawn-error event the parent receives, inner or routed, wherever it
      rides a machine trace: under the bare `:event` slot and under `:input
      :event` (`project-spawn-error-event`). The child-owned error is
      summarized; the reserved id + invoke-id survive."
  [tags]
  (cond-> tags
    (contains? tags :start)
    (assoc :start rf.privacy/redacted-sentinel)

    (contains? tags :event)
    (update :event project-spawn-error-event)

    (and (map? (:input tags)) (contains? (:input tags) :event))
    (update-in [:input :event] project-spawn-error-event)))

;; A frame classifies a durable machine `:data` slot at its ABSOLUTE runtime-db
;; path, under `rf.elision/machine-snapshot-prefix` (`[:rf.runtime/machines
;; :snapshots <actor-id>]`): the machine's own declaration, applied at egress to
;; the snapshot by the machines hook, unioned with any effect claim on the path.
;; The machine trace slots carry the SNAPSHOT value, so the absolute declaration
;; is re-rooted SNAPSHOT-relative by stripping that prefix.

(defn- snapshot-classification-in
  "Re-root the elision declarations `sens-decls` / `large-decls` (axis maps
  keyed by absolute path) to the snapshot keyed under `actor-id`:
  keep those rooted at `[:rf.runtime/machines :snapshots <actor-id> …]` and
  strip that prefix. Returns `{:sensitive [paths] :large [paths]}` (slot
  omitted when empty), or nil when `actor-id` is nil or nothing matches."
  [sens-decls large-decls actor-id]
  (when actor-id
    (let [prefix (conj rf.elision/machine-snapshot-prefix actor-id)
          n      (count prefix)
          under  (fn [decls]
                   (into []
                         (comp (filter #(and (>= (count %) n)
                                             (= prefix (subvec (vec %) 0 n))))
                               (map #(subvec (vec %) n)))
                         (keys decls)))
          sens   (under sens-decls)
          large  (under large-decls)]
      (when (or (seq sens) (seq large))
        (cond-> {}
          (seq sens)  (assoc :sensitive sens)
          (seq large) (assoc :large large))))))

(defn frame-snapshot-classification
  "Compute the SNAPSHOT-relative sensitive/large path set the FRAME declares for
  the machine snapshot keyed under `actor-id` (EP-0025) — classification is
  frame-owned; there is no `:data-schema`→classification bridge.
  Reads the frame's live classification (`rf.elision/sensitive-declarations`,
  which applies the machine's declaration to the snapshot the frame's
  runtime-db holds), keeps only paths rooted at
  `[:rf.runtime/machines :snapshots <actor-id> …]`, and strips that prefix so
  the remaining path indexes into the snapshot value (e.g. `[… :data :token]`).
  Returns `{:sensitive [paths] :large [paths]}` (slot omitted when empty), or nil
  when `frame-id` is nil or the frame declares no matching snapshot path.

  Public so the machines SSR-hydration projector (`re-frame.machines.ssr`) can
  reuse the SAME re-rooting for its `:data`-map projection."
  [frame-id actor-id]
  (when frame-id
    (snapshot-classification-in (rf.elision/sensitive-declarations frame-id)
                                (rf.elision/declarations frame-id)
                                actor-id)))

(defn- snapshot-value-classification
  "The snapshot-relative classification `policies` give `snapshot`, actor
  `actor-id`'s snapshot held outside runtime-db: the machines hook names the
  machine from the snapshot itself, so a value keeps the classification it was
  computed under after its actor is destroyed. Spec 015 §Retained values keep
  their recorded classification."
  [actor-id snapshot policies]
  (let [registry (rf.elision/with-runtime-classification
                   nil policies {:rf.runtime/machines {:snapshots {actor-id snapshot}}})]
    (snapshot-classification-in (:sensitive-declarations registry)
                                (:declarations registry)
                                actor-id)))

(defn- actor-classification
  "Actor `actor-id`'s snapshot-relative classification: the frame's claims on
  its snapshot path (`frame-snapshot-classification`), unioned with the claims
  the frame's current policy makes on `snapshot`, the value the trace carries
  (with none carried, a singleton's own id names its machine). A trace emitted
  inside the event that first boots a singleton describes a snapshot
  runtime-db does not hold yet, so the frame's claims alone would miss it.
  Spec 015 §Subsystem projection-relative classification."
  [frame-id actor-id snapshot]
  (when (and frame-id actor-id)
    (let [live (frame-snapshot-classification frame-id actor-id)
          own  (snapshot-value-classification
                 actor-id (if (map? snapshot) snapshot {})
                 [(rf.elision/frame-policy frame-id)])
          s    (into [] (distinct) (concat (:sensitive live) (:sensitive own)))
          l    (into [] (distinct) (concat (:large live) (:large own)))]
      (when (or (seq s) (seq l))
        (cond-> {}
          (seq s) (assoc :sensitive s)
          (seq l) (assoc :large l))))))

(defn- machine-classification
  "The classification actor `machine-id`'s snapshot is redacted by at egress:
  `actor-classification` of `snapshot`, the value being projected (which
  carries the machine's own `:source :machine` claims), UNIONED with any
  author classification on the machine's `:event` registration meta.
  Returns `{:sensitive [paths] :large [paths]}` (slot omitted when empty), or nil
  when neither declares anything. Shared by the machine traces
  (`project-machine-tags`) and the `[:rf/machine <id>]` sub's `:rf.sub/run`
  trace (`project-sub-tags`), so one snapshot redacts identically on both."
  [frame-id machine-id snapshot]
  (let [author   (event-classification machine-id frame-id)
        frame-mk (actor-classification frame-id machine-id snapshot)
        s        (into (vec (:sensitive author)) (:sensitive frame-mk))
        l        (into (vec (:large author))     (:large frame-mk))]
    (when (or (seq s) (seq l))
      (cond-> {}
        (seq s) (assoc :sensitive s)
        (seq l) (assoc :large l)))))

(defn- project-action-outcome-shell
  "UNCONDITIONAL (machine-classification-independent) projection of the
  `:rf.machine/action-ran` `:outcome` tag — the action's RAW returned effect
  map. Per Spec 005 §Action effect map the return is `{:data
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
               (vector? (:fx outcome)) (update :fx (fn [fx] (mapv #(project-event-fx-entry % (:frame tags)) fx)))
               (contains? outcome :db) (assoc :db rf.privacy/redacted-sentinel))))))

(defn- traced-snapshot
  "The actor snapshot a machine trace carries — its `:after`, `:before` or
  `:snapshot` slot — or nil."
  [tags]
  (some #(when (map? %) %) [(:after tags) (:before tags) (:snapshot tags)]))

(defn- project-machine-tags
  "Walk machine `:data`-bearing trace tag shapes. Paths are rooted at the
  SNAPSHOT — per Spec 015 §State machines — so common paths are written as
  `[:data :jwt]`, `[:data :user :ssn]`, etc.

  EP-0025: the classification comes from the FRAME's declared
  classification of the machine snapshot path (`frame-snapshot-classification`,
  the frame-owned sole app-db mechanism), UNIONED with any author classification
  on the machine's `:event` registration meta. There is no machine
  `:data-schema`→classification bridge.

  Machine `:data` surfaces in several differently-shaped trace slots, and EVERY
  one is redacted: `:before` / `:after` / `:snapshot` (full snapshot maps),
  `:data` (bare `:data` map, one level shallower), `:input` (`{:data … :event
  …}`), and `:cascade` (step vector each with a `:data-delta`).

  The trace ALSO ECHOES the ROUTED inner event into two slots — top-level
  `:event` and `[:input :event]` (per Spec 005 §Trace events; the router copies
  `(second outer-event)` there). A `:sensitive` payload carried THROUGH the
  machine as that routed event would otherwise ship RAW in these echo slots —
  the generic `project-event-tags` keys off the INNER event-id, which is
  typically unregistered. So the machine's own
  EVENT-rooted classification paths redact these echo slots too. That is safe to
  union with the `:data` snapshot paths on the SAME declaration because the two
  roots are disjoint: an integer event index never matches a `:data`-prefixed
  snapshot key, and a `:data`-prefixed key never matches a vector index — each
  path bites only the surface it is rooted at."
  [tags frame-id]
  ;; The CHILD-OWNED synthetic on-error payload and the `:start` payload are
  ;; summarized UNCONDITIONALLY (independent of the parent/spawn machine's
  ;; classification), and the action-`:outcome` echo's `:fx` / disallowed-`:db`
  ;; halves are registration-driven. Run these first so they apply
  ;; even when the trace's machine declares no `:data` classification.
  (let [tags       (project-spawn-synthetic-payloads tags)
        tags       (project-action-outcome-shell tags)
        machine-id (or (:actor-id tags) (:machine-id tags))
        class      (machine-classification frame-id machine-id (traced-snapshot tags))]
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
                                ;; mirroring the top-level `:event`.
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
          ;; The action's returned effect map echoed at
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
          ;; machine's EVENT-rooted classification. `project` is
          ;; a whole-value path walk; only the machine's event-rooted paths
          ;; bite here (the disjoint-root reasoning in the docstring).
          (contains? tags :event)    (assoc :event    (project (:event    tags)))
          (contains? tags :input)    (assoc :input    (project-input (:input tags)))
          (contains? tags :cascade)  (assoc :cascade  (project-cascade (:cascade tags))))))))

(def ^:private thrown-value-slots
  "The slots that carry a thrown value: its `ex-data` (`:exception-data`), its
  message (`:exception-message`), and the raw `:exception`, whose printed form
  carries both."
  [:exception-data :exception-message :exception])

(defn- carries-thrown-value?
  [tags]
  (boolean (some #(contains? tags %) thrown-value-slots)))

(defn- redact-exception-slots
  "Fail-closed whole-slot redaction tail shared by the machine and flow exception
  projectors: replace each PRESENT thrown-value slot, the `:reason` sentence
  beside it, and the raw `:exception` inside an `:info` failure map with the
  `:rf/redacted` sentinel, and stamp `:sensitive? true`. A message routinely
  quotes the value that failed, so a row judged sensitive is redacted for its
  text as well as its `ex-data`."
  [tags]
  (cond-> (reduce (fn [tags slot]
                    (cond-> tags
                      (contains? tags slot) (assoc slot rf.privacy/redacted-sentinel)))
                  (assoc tags :sensitive? true)
                  (conj thrown-value-slots :reason))
    (and (map? (:info tags)) (contains? (:info tags) :exception))
    (assoc-in [:info :exception] rf.privacy/redacted-sentinel)))

(defn- project-machine-error-tags
  "Walk a machine-owned row that carries a thrown value: the
  `:rf.error/machine-action-exception` row of a throwing action, guard,
  `:on-done` callback or destroy-time `:exit` action, the
  `:rf.machine/guard-evaluated` and `:rf.machine/action-ran` rows of the throw
  itself, and the timer's `:rf.error/machine-after-*` rows. `:exception-data` is
  the thrown value's `ex-data`, which could embed the same app secrets the
  machine's `:data` classification gates, and `:exception-message` / `:reason` /
  `:exception` can quote them. The conservative, footgun-prevention posture:
  when the machine declares ANY `:sensitive` classification, elide each of those
  slots the row carries WHOLE.

  EP-0025: the \"machine declares ANY `:sensitive`\" decision
  consults the FRAME's snapshot-path classification (the sole app-db mechanism)
  unioned with any author `:event` registration classification. A row that names
  no machine has neither to consult.

  A nil / unresolvable frame FAILS CLOSED (redacts) — matching the sibling
  `project-flow-exception-tags` and Spec 015 §Failure-posture (unknown frame =>
  fail closed). `:exception-data` is author-shaped `ex-data` and the text slots
  are free prose, none of which can be path-walked safely, so a nil frame
  redacts each WHOLE slot."
  [tags frame-id]
  (if-not (carries-thrown-value? tags)
    tags
    (let [machine-id (or (:actor-id tags) (:machine-id tags))
          author     (event-classification machine-id frame-id)
          frame-mk   (actor-classification frame-id machine-id (traced-snapshot tags))]
      (if (or (nil? frame-id)
              (seq (:sensitive author)) (seq (:sensitive frame-mk)))
        (redact-exception-slots tags)
        tags))))

(defn- project-flow-exception-tags
  "Walk the flow rows that carry a thrown value: `:rf.flow/failed`'s structured
  exception summary — `:exception-message` (plain string) + `:exception-data`
  (the ex-info `ex-data` map, or nil) — and the run-level
  `:rf.error/flow-eval-exception`'s raw `:exception`, whose printed form carries
  the flow's thrown value as its cause. The `:inputs` slot already rode the
  wire-elision walker at emit time; the `:exception-data` map is the developer's
  arbitrary author-keyed payload, and the message can quote the same values.
  When the flow's FRAME declares ANY sensitive elision declaration, elide each
  slot the row carries WHOLE. A nil / unresolvable frame FAILS CLOSED (redacts)."
  [tags]
  (let [frame-id (:frame tags)]
    (if (and (carries-thrown-value? tags)
             (or (nil? frame-id)
                 (seq (rf.elision/sensitive-declarations frame-id))))
      (redact-exception-slots tags)
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

(defn- machine-error-op?
  "True for the machine layer's `:rf.error/machine-*` error categories."
  [operation]
  (and (keyword? operation)
       (= "rf.error" (namespace operation))
       (let [n (name operation)]
         (and (> (count n) 8) (= "machine-" (subs n 0 8))))))

(defn- flow-exception-op?
  "True for the flow rows that carry a thrown value: the per-flow
  `:rf.flow/failed` and the run-level `:rf.error/flow-eval-exception`."
  [operation]
  (case operation
    (:rf.flow/failed :rf.error/flow-eval-exception) true
    false))

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
  in-scope registrations (and the frame's app-db classification). Returns the
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
                        ;; (public IDENTITY — Spec 015),
                        ;; NOT a dispatched event. It egresses VERBATIM here,
                        ;; mirroring the always-on record's
                        ;; `error-emit/raw-identity-query-vector-event?` skip
                        ;; (keyed on the SAME `:op :subscribe` realm the core
                        ;; emitters stamp). Projecting it would misread the
                        ;; vector as a dispatched event and borrow a same-id
                        ;; EVENT registration's `:sensitive` paths to mutate the
                        ;; identity — a LEGAL event/sub id collision, since the
                        ;; two live in SEPARATE registries. Every
                        ;; OTHER `:event` slot projects — including the
                        ;; `:dispatch` / `:dispatch-sync` frame-destroyed realms,
                        ;; whose `:event` IS a dispatched-event payload. `=` on
                        ;; keyword operands, never `identical?`.
                        (and (contains? tags :event)
                             (not (and (= :rf.error/frame-destroyed operation)
                                       (= :subscribe (:op tags)))))
                        (project-event-tags :event)

                        ;; `:rf.error/drain-depth-exceeded`'s dev-only
                        ;; `:last-event` — the last-settled event's vector.
                        (contains? tags :last-event)
                        (project-event-tags :last-event)

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
                        ;; error-trace args redact too.
                        (and (contains? tags :rf.fx/id)
                             (contains? tags :rf.fx/args))
                        (project-fx-tags)

                        (contains? tags :rf.event/coeffects)
                        (project-cofx-map-slot :rf.event/coeffects)

                        (contains? tags :rf.event/cofx)
                        (project-cofx-map-slot :rf.event/cofx)

                        (contains? tags :rf.event/db)
                        (project-db-tags frame-id)

                        ;; `:rf.event/run-end`'s per-`:after` ctx diffs — their
                        ;; `:db` / `:fx` values.
                        (contains? tags :rf.event/after-deltas)
                        (project-after-deltas-tags frame-id)

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

                        (flow-exception-op? operation)
                        (project-flow-exception-tags)

                        (or (machine-op? operation) (machine-error-op? operation))
                        (project-machine-error-tags frame-id)

                        (contains? tags :offending-value)
                        (project-machine-wrote-db-tags)

                        (resource-failure-op? operation)
                        (project-resource-error-tags)))]
      (assoc event :tags tags'))))

;; ---- late-bind hook registration ----------------------------------------
;;
;; The trace ns reads through these hooks; this ns reads the frame's
;; classification through `re-frame.elision`. The arrangement avoids load cycles (`re-frame.trace` →
;; `re-frame.classification` would cycle since this ns requires elision which
;; requires trace).

(rf.late-bind/set-fn! :classification/project-trace-event project-trace-event)
(rf.late-bind/set-fn! :classification/redact-event-by-registration redact-event-by-registration)
(rf.late-bind/set-fn! :classification/registration-classification registration-classification)
