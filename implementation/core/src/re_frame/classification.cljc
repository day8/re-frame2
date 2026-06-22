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
  (:require [re-frame.elision :as elision]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.path :as path]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- per-registration classification: DERIVED from the registrar (rf2-ehexnw)
;;
;; A registration's declared classification (`:sensitive` / `:large` /
;; `:large?`) is NOT stashed into a second imperative side-table at registration
;; time. Every reg-* path already stores the author meta on its
;; `re-frame.registrar` entry, so `registration-classification` DERIVES it at
;; read time by running `normalise-classification` over
;; `registrar/handler-meta` — the SAME validation path the registration ran. The
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
  bug; `path/segment?` rejects it."
  [x]
  (and (vector? x) (every? path/segment? x)))

(defn- classification-error
  "Build the malformed-classification ex-info with the canonical thrown-error
  shape (per Spec 009 §The thrown-error shape). `:rf.error/bad-classification`
  is the message AND the `:rf.error/id` discriminator; `:bad-key` names the
  offending classification key; `extras` merges the offending-value slot
  (`:bad-value` for a non-vector whole, `:bad-entries` for malformed entries)."
  [class-key reason extras]
  (error/thrown-ex-info
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
  read time from `registrar/handler-meta` (rf2-ehexnw), NOT a duplicated
  side-table.

  The returned shape is `{:sensitive [paths] :large [paths] :large? bool}` —
  slots are present only when the registration declared them. EP-0025: there is
  no derived-output sensitivity (no propagation)."
  [kind id]
  (normalise-classification (registrar/handler-meta kind id)))

(defn clear-classification!
  "No-op retained for test-isolation directory symmetry — production code never
  calls this. Returns nil.

  EP-0025: the author-sourced classification lives in the registrar (rf2-ehexnw),
  which the test-isolation runtime fixture already snapshot/restores; the
  per-frame app-db classification lives in the frame's runtime-db elision
  registry, reset by the same fixture's frame teardown. There is no separate
  side-table left to clear here."
  []
  nil)

;; ---- emit-time projection ------------------------------------------------

(defn large-marker
  "Build the `:rf.size/large-elided` marker for value `v` at `path`. Delegates
  to `re-frame.elision/->marker` with `{:reason :classification}` so the marker
  shape is built in ONE place.

  Public because the off-box epoch egress projector
  (`re-frame.epoch.tool-pair/projected-record`) reuses it to substitute the
  marker for a whole-output `:large?`-stamped sub's `:value` / `:prev-value`."
  [v path]
  (elision/->marker v path {:reason :classification}))

(defn- walk-with-paths
  "Walk `v` and substitute sentinels at the declared paths. Paths in
  `sensitive-paths` and `large-paths` are rooted at `v`. Sensitive wins over
  large at the same path.

  No-op early-exit: when both path sets are empty, returns `v` unchanged.
  Shares the map/vec/set/seq recursion skeleton with the schema-first elision
  walker via `re-frame.elision/walk-tree` (rf2-cywzkh)."
  [v sensitive-paths large-paths]
  (if (and (empty? sensitive-paths) (empty? large-paths))
    v
    (let [sensitive-set (set (map vec sensitive-paths))
          large-set     (set (map vec large-paths))]
      (elision/walk-tree
        v []
        {:decide  (fn [path v]
                    (cond
                      (contains? sensitive-set path) privacy/redacted-sentinel
                      (contains? large-set path)     (large-marker v path)
                      :else                          elision/walk-recur))
         :map-key (fn [path k] (conj path k))
         :index   (fn [path i] (conj path i))
         :leaf    (fn [_path v] v)}))))

(defn redact-with-paths
  "Public projection helper. Walks `v` and substitutes sentinels at the declared
  paths. Empty `[[]]` path substitutes the whole value (sensitive wins over large
  at the root). Per Spec 015 §What gets a sentinel."
  [v sensitive-paths large-paths]
  (walk-with-paths v sensitive-paths large-paths))

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

(defn redact-event-by-registration
  "Apply the REGISTRATION-OWNED `:sensitive` / `:large` classification declared
  by the event handler registered under `(first event)` to the event vector
  `event`, returning the vector with declared arg-map paths redacted. A no-op
  when `event` is not a `[event-id arg-map …]` vector or the handler declared
  none.

  ALWAYS-ON (NOT gated on `interop/debug-enabled?`) — the registration
  classification is populated at registration time in production as well as dev
  (only the emit-time TRACE projection is dev-gated), and this fn is the
  production egress consumer (rf2-qe6v1u): EP-0015 makes event args
  REGISTRATION-OWNED transient payloads.

  Published via the `:classification/redact-event-by-registration` late-bind
  hook; `re-frame.projection` consumes it for the `:rf.observe/error` /
  handled-event `:event` slot."
  [event]
  (if-let [class (classification-when :event (when (vector? event) (first event)))]
    (let [[sens large] (classification-paths class)]
      (redact-event-vec event sens large))
    event))

(defn- project-event-tags
  "Walk `:rf.event/dispatched` / `:rf.event/db-changed` / `:rf.fx/do-fx` tag
  shapes: the dispatched event vector lives at `:rf.event/v` and is a
  `[event-id arg-map]` form. Classification comes from the event handler's
  registration. The `:rf.error/*` error traces carry the event vector under the
  bare `:event` slot, so we redact whichever slot the trace carries."
  [tags slot]
  (let [event    (get tags slot)
        event-id (when (vector? event) (first event))
        class    (classification-when :event event-id)]
    (if-not class
      tags
      (let [[sens large] (classification-paths class)
            redacted (redact-event-vec event sens large)]
        (assoc tags slot redacted)))))

(defn- project-fx-tags
  "Walk `:rf.fx/handled` tag shape: `:rf.fx/id` carries the fx keyword and
  `:rf.fx/args` carries the args value."
  [tags]
  (let [fx-id (:rf.fx/id tags)
        class (classification-when :fx fx-id)]
    (if-not class
      tags
      (assoc tags :rf.fx/args (redact-by-classification (:rf.fx/args tags) class)))))

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

(defn- project-sub-tags
  "Walk `:sub/run` tag shape: `:rf.sub/id` carries the sub query keyword and
  `:rf.sub/value` carries the output. The reactive recompute also stamps a
  `:rf.sub/prev-value` (the prior computed value); it gets the IDENTICAL
  treatment. Classification comes from the sub's registration's per-output-path
  declarations.

  EP-0025: there is NO sub-output PROPAGATION — a sub does not inherit its
  input's sensitivity, and there is no whole-output `:sensitive? true` stamp from
  a propagation table. Only the registration's own declared paths redact.

  EP-0002 (rf2-gjq3ow) — FAIL CLOSED on a nil `frame-id`: subs are frame-scoped,
  so a sub trace with no carried frame is malformed; `:rf.sub/value` (and
  `:rf.sub/prev-value` when present) are conservatively redacted."
  [tags frame-id]
  (let [sub-id    (:rf.sub/id tags)
        class     (classification-when :sub sub-id)
        has-prev? (contains? tags :rf.sub/prev-value)]
    (cond
      (nil? frame-id)
      (cond-> (assoc tags :rf.sub/value privacy/redacted-sentinel :sensitive? true)
        has-prev? (assoc :rf.sub/prev-value privacy/redacted-sentinel))

      (nil? class)
      tags

      :else
      (let [[sens large] (classification-paths class)
            redacted (redact-with-paths (:rf.sub/value tags) sens large)]
        (cond-> (assoc tags :rf.sub/value redacted)
          has-prev? (assoc :rf.sub/prev-value (redact-with-paths (:rf.sub/prev-value tags) sens large))
          (:large? class) (assoc :large? true))))))

(defn- frame-has-declarations?
  "True when `frame-id` carries any elision declaration (sensitive or large).
  Cheap registry read used to gate the full-db walk so the no-classification
  common case stays reference-preserving."
  [frame-id]
  (boolean (or (seq (elision/sensitive-declarations frame-id))
               (seq (elision/declarations frame-id)))))

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
    (assoc tags :rf.event/db privacy/redacted-sentinel)

    (frame-has-declarations? frame-id)
    (assoc tags :rf.event/db
           (elision/elide-wire-value (:rf.event/db tags) {:frame frame-id}))

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
           (mapv (constantly privacy/redacted-sentinel)
                 (:rf.view/render-args tags)))

    (frame-has-declarations? frame-id)
    (assoc tags :rf.view/render-args
           (mapv #(elision/elide-wire-value % {:frame frame-id})
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
  (into [(nth event 0) (nth event 1) privacy/redacted-sentinel]
        (repeat (max 0 (- (count event) 3)) privacy/redacted-sentinel)))

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
    (assoc :start privacy/redacted-sentinel)

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
          sens   (under (elision/sensitive-declarations frame-id))
          large  (under (elision/declarations frame-id))]
      (when (or (seq sens) (seq large))
        (cond-> {}
          (seq sens)  (assoc :sensitive sens)
          (seq large) (assoc :large large))))))

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
  …}`), and `:cascade` (step vector each with a `:data-delta`)."
  [tags frame-id]
  ;; The CHILD-OWNED synthetic on-error payload and the `:start` payload are
  ;; summarized UNCONDITIONALLY (independent of the parent/spawn machine's
  ;; classification). Run these first so they apply even when the trace's machine
  ;; declares no `:data` classification.
  (let [tags       (project-spawn-synthetic-payloads tags)
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
                            (if (and (map? input) (contains? input :data))
                              (assoc input :data (project-data (:data input)))
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
          (contains? tags :input)    (assoc :input    (project-input (:input tags)))
          (contains? tags :cascade)  (assoc :cascade  (project-cascade (:cascade tags))))))))

(defn- redact-exception-data-slot
  "Fail-closed whole-slot redaction tail shared by the two exception-payload
  projectors: replace the WHOLE `:exception-data` slot with the `:rf/redacted`
  sentinel and stamp `:sensitive? true`."
  [tags]
  (assoc tags :exception-data privacy/redacted-sentinel :sensitive? true))

(defn- project-machine-error-tags
  "Walk the `:rf.error/machine-action-exception` tag shape. The trace carries
  `:exception-data` — the `ex-data` of a thrown machine action — which could
  embed the same app secrets the machine's `:data` classification gates. The
  conservative, footgun-prevention posture: when the machine declares ANY
  `:sensitive` classification, elide the WHOLE `:exception-data` slot.

  EP-0025 (rf2-398kql): the \"machine declares ANY `:sensitive`\" decision now
  consults the FRAME's snapshot-path classification (the sole app-db mechanism)
  unioned with any author `:event` registration classification."
  [tags frame-id]
  (let [machine-id (or (:actor-id tags) (:machine-id tags))
        author     (classification-when :event machine-id)
        frame-mk   (frame-snapshot-classification frame-id machine-id)]
    (if (and (contains? tags :exception-data)
             (or (seq (:sensitive author)) (seq (:sensitive frame-mk))))
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
                 (seq (elision/sensitive-declarations frame-id))))
      (redact-exception-data-slot tags)
      tags)))

(defn- project-machine-wrote-db-tags
  "Walk the `:rf.error/machine-action-wrote-db` tag shape. A machine action that
  wrongly carries a `:db` key is rejected, emitting this error carrying the
  STRIPPED `:db` value under `:offending-value` — the WHOLE app-db. The whole
  `:offending-value` slot is summarized to `:rf/redacted` UNCONDITIONALLY."
  [tags]
  (if (contains? tags :offending-value)
    (assoc tags :offending-value privacy/redacted-sentinel)
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
    (some? (:error tags))      (assoc :error      privacy/redacted-sentinel)
    (some? (:page-error tags)) (assoc :page-error privacy/redacted-sentinel)))

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
                            :else                   privacy/redacted-sentinel))
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
          tags'     (cond-> tags
                      (and (map? tags) (contains? tags :rf.event/v))
                      (project-event-tags :rf.event/v)

                      (and (map? tags) (contains? tags :event))
                      (project-event-tags :event)

                      (and (map? tags) (= :rf.fx/handled operation))
                      (project-fx-tags)

                      (and (map? tags) (contains? tags :rf.event/coeffects))
                      (project-cofx-map-slot :rf.event/coeffects)

                      (and (map? tags) (contains? tags :rf.event/cofx))
                      (project-cofx-map-slot :rf.event/cofx)

                      (and (map? tags) (contains? tags :rf.event/db))
                      (project-db-tags frame-id)

                      (and (map? tags) (contains? tags :rf.view/render-args))
                      (project-view-rendered-tags frame-id)

                      (and (map? tags) (or (= :rf.cofx/run operation)
                                           (= :rf.cofx/generated operation)))
                      (project-cofx-run-tags)

                      (and (map? tags) (= :rf.sub/run operation))
                      (project-sub-tags frame-id)

                      (and (map? tags) (machine-op? operation))
                      (project-machine-tags frame-id)

                      (and (map? tags)
                           (contains? tags :rf.interceptor/override-summary))
                      (project-override-summary-tags)

                      (and (map? tags) (= :rf.flow/failed operation))
                      (project-flow-failed-tags)

                      (and (map? tags)
                           (not= :rf.flow/failed operation)
                           (contains? tags :exception-data))
                      (project-machine-error-tags frame-id)

                      (and (map? tags) (contains? tags :offending-value))
                      (project-machine-wrote-db-tags)

                      (and (map? tags) (resource-failure-op? operation))
                      (project-resource-error-tags))]
      (assoc event :tags tags'))))

;; ---- late-bind hook registration ----------------------------------------
;;
;; The trace ns reads through these hooks; this ns reads through the elision
;; registry. The arrangement avoids load cycles (`re-frame.trace` →
;; `re-frame.classification` would cycle since this ns requires elision which
;; requires trace).

(late-bind/set-fn! :classification/project-trace-event project-trace-event)
(late-bind/set-fn! :classification/redact-event-by-registration redact-event-by-registration)
(late-bind/set-fn! :classification/registration-classification registration-classification)
(late-bind/set-fn! :classification/clear-classification! clear-classification!)
