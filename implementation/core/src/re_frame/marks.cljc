(ns re-frame.marks
  "Data-classification path-marks for sensitive + large values per Spec 015.

  This namespace owns:
    - `reg-marks` — the dedicated registration kind for declaring
      path-marks against an `app-db` (frame-scoped).
    - Per-registration mark tables — a per-(kind, id) index of
      `{:sensitive [paths] :large [paths] :sensitive? bool :large? bool}`
      stashed at registration time so emit-time consumers can resolve
      marks without re-walking the registrar meta on every event.
    - Emit-time projection — the path-walk + sentinel substitution
      consumed by `re-frame.trace/build-event` to redact `:rf/redacted`
      at `:sensitive` paths and surface `:rf.size/large-elided` markers
      at `:large` paths inside trace-event `:tags`.
    - Sub-output mark propagation — a per-(frame, query-v) table that
      records whether a sub's most recent output should be treated as
      sensitive/large for downstream consumers. Footgun prevention,
      not security taint.

  Per Spec 015 §Hot-path cost: the entire surface rides
  `re-frame.interop/debug-enabled?` — registrations still populate
  tables at boot (constant memory), but emit-time projection is gated
  and constant-folds out of CLJS production bundles via `goog.DEBUG`.

  Per Spec 015 §Relationship with schema-attached marks: this
  namespace writes into the SAME registry slot the schema-first
  elision walker reads from (`[:rf/elision :sensitive-declarations]`
  and `[:rf/elision :declarations]`), keyed by absolute path. The two
  declaration sources union at lookup time — a path declared sensitive
  by EITHER source is sensitive."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.substrate.adapter :as adapter]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- per-registration mark tables ----------------------------------------
;;
;; A per-(kind, id) index of the registration's declared marks. Populated
;; at registration time by `register-marks!` (called from the existing
;; `reg-event-*` / `reg-sub` / `reg-fx` / `reg-cofx` / `reg-machine` /
;; `reg-flow` reg-paths). Read at emit time by the projection helpers.
;;
;; The table is process-scoped (mirrors `re-frame.registrar`'s shape) —
;; declarations bind to (kind, id), not to (frame, kind, id). `reg-marks`
;; is the exception — it is frame-scoped and writes into the per-frame
;; elision registry.

(defonce ^:private kind->id->marks
  (atom {}))

(defn- coerce-paths
  "Normalise a `:sensitive` / `:large` declaration value to a vector of
  path vectors. `nil` becomes `[]`; any non-vector entry is dropped
  (the declaration is best-effort — we tolerate hand-written maps that
  passed an empty literal but want to log via `[[]]` for whole-value
  marks)."
  [paths]
  (if (nil? paths)
    []
    (vec (filter vector? paths))))

(defn- normalise-marks
  "Extract the mark-relevant subset of a registration meta-map and
  normalise into the canonical shape this namespace consults:

    {:sensitive  [vector-of-paths]
     :large      [vector-of-paths]
     :sensitive? <bool-or-nil>   ;; whole-output override (subs/flows)
     :large?     <bool-or-nil>}  ;; whole-output override (subs/flows)

  Returns `nil` when the meta-map carries no mark-relevant keys —
  callers branch on the nil to avoid stashing empty tables."
  [meta]
  (when (or (contains? meta :sensitive)
            (contains? meta :large)
            (contains? meta :sensitive?)
            (contains? meta :large?))
    (cond-> {}
      (contains? meta :sensitive)  (assoc :sensitive  (coerce-paths (:sensitive meta)))
      (contains? meta :large)      (assoc :large      (coerce-paths (:large meta)))
      (contains? meta :sensitive?) (assoc :sensitive? (boolean (:sensitive? meta)))
      (contains? meta :large?)     (assoc :large?     (boolean (:large? meta))))))

(defn register-marks!
  "Record a registration's mark declaration for later emit-time consultation.
  Returns nil. No-op when `meta` carries no mark-relevant keys.

  Called from each reg-* path AFTER the underlying registrar write so the
  marks table mirrors the registry. Re-registration replaces the prior
  marks entry in full (no merge — matches the registrar's slot semantics)."
  [kind id meta]
  (let [marks (normalise-marks meta)]
    (if (nil? marks)
      ;; Clear any prior marks for this (kind, id) on re-registration
      ;; without marks — the new registration's declaration set should
      ;; supersede the old one in full (Spec 015 §reg-marks: "second
      ;; call against the same id wins"). The same rule generalises
      ;; from `reg-marks` to every reg-* site.
      (swap! kind->id->marks update kind dissoc id)
      (swap! kind->id->marks assoc-in [kind id] marks)))
  nil)

(defn marks-for
  "Return the registered mark declaration for `(kind, id)`, or nil.

  The returned shape is `{:sensitive [paths] :large [paths]
  :sensitive? bool :large? bool}` — slots are present only when the
  registration declared them."
  [kind id]
  (get-in @kind->id->marks [kind id]))

(defn clear-marks!
  "Drop every registered marks declaration. Test-isolation only —
  production code never calls this. Returns nil."
  []
  (reset! kind->id->marks {})
  nil)

;; ---- reg-marks API -------------------------------------------------------
;;
;; The dedicated registration kind for declaring path-marks against an
;; `app-db`. Frame-scoped per Spec 015 §reg-marks. Writes through the
;; existing `[:rf/elision :sensitive-declarations]` /
;; `[:rf/elision :declarations]` registry slots so the schema-first
;; elision walker (`re-frame.elision/elide-wire-value`) sees the
;; declarations without a second lookup path.
;;
;; Per Spec 015 §reg-marks: a second `reg-marks` call against the same
;; frame REPLACES the previous declaration set (NOT merge). This drops
;; the prior `:reg-marks`-sourced entries and overlays the new ones;
;; schema-sourced entries are preserved (they are not owned by
;; `reg-marks` and live alongside).

(defn- swap-app-db!
  "Mutate the frame's app-db through the substrate adapter. The mark-
  registry lives at `[:rf/elision ...]` per Spec 015 §Mark-lookup table
  shape and matches the schema-first elision walker's storage location."
  [frame-id f]
  (when-let [container (frame/get-frame-db frame-id)]
    (let [old-db (adapter/read-container container)
          new-db (f old-db)]
      (adapter/replace-container! container new-db))))

(defn- without-reg-marks-sourced
  "Drop entries whose `:source` is `:reg-marks` from a declaration map.
  Used by `reg-marks` to clear the prior call's contributions before
  overlaying the new ones — schema-sourced entries survive."
  [decls]
  (when decls
    (reduce-kv (fn [acc path decl]
                 (if (= :reg-marks (:source decl))
                   acc
                   (assoc acc path decl)))
               {}
               decls)))

(defn- overlay-reg-marks
  "Build the new declaration map for one registry slot: drop the prior
  `:reg-marks`-sourced entries, then assoc the new paths with
  `{:source :reg-marks}`."
  [existing paths]
  (let [carry (without-reg-marks-sourced existing)]
    (reduce (fn [acc path]
              (assoc acc (vec path) {:source :reg-marks}))
            carry
            paths)))

(defn reg-marks
  "Declare path-marks against the `app-db` of `frame-id`. Per Spec 015
  §reg-marks (app-db, per frame).

  `metadata` is a map with optional `:sensitive` and `:large` keys; each
  is a vector of `get-in`-shaped paths into `app-db`. A second call
  against the same frame REPLACES the previous declaration set in full
  (per Spec 015 §reg-marks: 'a second `reg-marks` call against the same
  frame wins'). Schema-attached marks (via `reg-app-schema` with
  `:sensitive?` / `:large?` slot metadata) are preserved — the two
  declaration sources union at lookup time per Spec 015 §Relationship
  with schema-attached marks.

      (rf/reg-marks :rf/default
        {:sensitive [[:user :ssn]
                     [:auth :token]
                     [:auth :refresh-token]]
         :large     [[:docs :csv-upload]
                     [:logs :history-buffer]]})

  Returns `frame-id`.

  `reg-marks` is a pure declaration — it does NOT mutate `app-db`,
  does NOT install an interceptor, and does NOT change any handler's
  view of the data. The declaration only feeds the mark-lookup table
  the observation surfaces (trace bus, Causa, MCP, third-party log
  sinks) consult at emission time."
  [frame-id {:keys [sensitive large] :as _metadata}]
  (let [sens (coerce-paths sensitive)
        lrg  (coerce-paths large)]
    (swap-app-db! frame-id
      (fn [db]
        (let [reg     (get db :rf/elision)
              new-s   (overlay-reg-marks (get reg :sensitive-declarations) sens)
              new-l   (overlay-reg-marks (get reg :declarations) lrg)
              new-reg (cond-> (or reg {})
                        (seq new-s) (assoc :sensitive-declarations new-s)
                        (empty? new-s) (dissoc :sensitive-declarations)
                        (seq new-l) (assoc :declarations new-l)
                        (empty? new-l) (dissoc :declarations))]
          (if (seq new-reg)
            (assoc db :rf/elision new-reg)
            (dissoc db :rf/elision)))))
    frame-id))

(defn clear-reg-marks!
  "Drop every `reg-marks`-sourced declaration for `frame-id`. Schema-
  sourced declarations are preserved. Returns nil. Test-isolation
  only; production code rarely needs this."
  [frame-id]
  (swap-app-db! frame-id
    (fn [db]
      (let [reg     (get db :rf/elision)
            new-s   (without-reg-marks-sourced (:sensitive-declarations reg))
            new-l   (without-reg-marks-sourced (:declarations reg))
            new-reg (cond-> {}
                      (seq new-s) (assoc :sensitive-declarations new-s)
                      (seq new-l) (assoc :declarations new-l))]
        (if (seq new-reg)
          (assoc db :rf/elision new-reg)
          (dissoc db :rf/elision)))))
  nil)

;; ---- emit-time projection ------------------------------------------------
;;
;; Two pathways resolve marks at emit time, per Spec 015 §Implementation
;; notes recommendation B (path-graph union at emit time):
;;
;;   1. `redact-with-paths` — given a payload value and a registration's
;;      declared paths (event-arg marks, fx-input marks, cofx-injection
;;      marks, sub-output marks, machine-data marks, flow-output marks),
;;      walk the payload and substitute sentinels at the declared paths.
;;      The walker is the elision-walker re-used with an inline ctx so
;;      the marker shapes are uniform across schema-sourced and per-
;;      registration-sourced marks.
;;
;;   2. `redact-tags` — the chokepoint `re-frame.trace/build-event`
;;      consults. Looks up the in-scope handler's marks (via the
;;      `:rf.trace/trigger-handler`'s kind+id), the cascade's event-id
;;      (per Spec 015 §Event-args -> app-db propagation), and the
;;      frame's app-db elision registry; computes the union and walks
;;      the per-tag projection. Trace tags carry kind-specific shapes
;;      (events under `:event`, fxs under `:fx-id`/`:fx-args`, subs
;;      under `:value`/`:input-signals`, ...) — the projection is per-
;;      tag-shape.

(defn- ->bytes
  "Return a byte-count for a value's printed representation. Used by
  the `:rf.size/large-elided` marker payload."
  [v]
  #?(:clj  (count (.getBytes ^String (pr-str v) "UTF-8"))
     :cljs (count (pr-str v))))

(defn- value-type
  [v]
  (cond
    (map? v)    :map
    (vector? v) :vector
    (set? v)    :set
    (string? v) :string
    :else       :scalar))

(defn- large-marker
  "Mirror of `re-frame.elision/->marker`'s shape — inlined so this ns
  carries no dependency on elision's privates. Carries `:reason
  :reg-marks` so consumers can discriminate per-registration marks
  from schema-driven marks."
  [v path]
  (let [p (vec path)]
    {:rf.size/large-elided
     {:path   p
      :bytes  (->bytes v)
      :type   (value-type v)
      :reason :reg-marks
      :handle [:rf.elision/at p]}}))

(defn- walk-with-marks
  "Walk `v` and substitute sentinels at the declared paths. Paths in
  `sensitive-paths` and `large-paths` are rooted at `v`. Sensitive
  wins over large at the same path.

  No-op early-exit: when both path sets are empty, returns `v`
  unchanged with no allocation. Matches the schema-first elision
  walker's recursion semantics (`re-frame.elision/walk`) but uses
  only the ad-hoc paths the caller supplied."
  [v sensitive-paths large-paths]
  (if (and (empty? sensitive-paths) (empty? large-paths))
    v
    (let [sensitive-set (set (map vec sensitive-paths))
          large-set     (set (map vec large-paths))]
      (letfn [(walk* [v path]
                (let [path (vec path)]
                  (cond
                    (contains? sensitive-set path) privacy/redacted-sentinel
                    (contains? large-set path)    (large-marker v path)
                    (map? v) (reduce-kv (fn [acc k vv]
                                          (assoc acc k (walk* vv (conj path k))))
                                        (empty v) v)
                    (vector? v)
                    (let [n (count v)]
                      (loop [i 0 acc (transient [])]
                        (if (< i n)
                          (recur (inc i) (conj! acc (walk* (nth v i) (conj path i))))
                          (persistent! acc))))
                    (set? v) (into #{} (map #(walk* % path)) v)
                    (seq? v)
                    (let [idx (volatile! -1)]
                      (persistent!
                        (reduce (fn [acc vv]
                                  (vswap! idx inc)
                                  (conj! acc (walk* vv (conj path @idx))))
                                (transient []) v)))
                    :else v)))]
        (walk* v [])))))

(defn redact-with-paths
  "Public projection helper. Walks `v` and substitutes sentinels at the
  declared paths. Empty `[[]]` path substitutes the whole value
  (sensitive wins over large at the root). Per Spec 015 §What gets a
  sentinel."
  [v sensitive-paths large-paths]
  (walk-with-marks v sensitive-paths large-paths))

;; ---- per-trace-event projection ------------------------------------------
;;
;; The chokepoint `re-frame.trace/build-event` consults on every emit
;; (gated by `interop/debug-enabled?` so production CLJS bundles DCE).
;; Tag-shape table: each operation has a known shape, and this fn knows
;; how to walk the tags into the right slot for the right registration's
;; marks.

(defn- redact-event-vec
  "Redact a `[event-id arg-map]` vector. Marks index into the arg-map
  (the second element). Per Spec 015 §Event handlers — paths are rooted
  at the arg-map; whole-arg substitution uses `[[]]`."
  [event sensitive-paths large-paths]
  (cond
    (or (nil? event) (not (vector? event))) event
    (< (count event) 2) event
    :else
    (let [[id payload & rest-args] event
          redacted-payload (redact-with-paths payload sensitive-paths large-paths)]
      (into [id redacted-payload] rest-args))))

(defn- event-marks
  "Resolve the marks declared by the event handler registered under
  `event-id`. Returns `{:sensitive [paths] :large [paths]}` or nil
  when no marks are declared."
  [event-id]
  (when event-id
    (marks-for :event event-id)))

(defn- fx-marks
  [fx-id]
  (when fx-id
    (marks-for :fx fx-id)))

(defn- cofx-marks
  [cofx-id]
  (when cofx-id
    (marks-for :cofx cofx-id)))

(defn- machine-marks
  [machine-id]
  (when machine-id
    (marks-for :event machine-id)))

(defn- sub-marks
  [sub-id]
  (when sub-id
    (marks-for :sub sub-id)))

(defn- flow-marks
  [flow-id]
  (when flow-id
    (marks-for :flow flow-id)))

;; ---- sub-output propagation registry -------------------------------------
;;
;; Per Spec 015 §App-db → subs: a sub reading any sensitive `app-db` path
;; yields a sensitive output by default. The reference implementation
;; uses the per-sub-id registered marks plus an opt-in `:sensitive?`
;; override on registration. The downstream propagation table records
;; the resolved "is this sub's most recent value sensitive?" answer for
;; emit-time consultation. Frame-scoped because subs are frame-scoped.

(defonce ^:private frame->sub-id->sensitive?
  (atom {}))

(defonce ^:private frame->sub-id->large?
  (atom {}))

(defn mark-sub-output!
  "Record the resolved sensitive/large state of a sub's most recent
  output. Called by the sub-cache after `compute-and-cache!` resolves
  the value. The flags fold into the propagation table; downstream
  emit sites read via `sub-output-sensitive?` / `sub-output-large?`."
  [frame-id sub-id sensitive? large?]
  (swap! frame->sub-id->sensitive?
         (fn [m]
           (if sensitive?
             (assoc-in m [frame-id sub-id] true)
             (update m frame-id dissoc sub-id))))
  (swap! frame->sub-id->large?
         (fn [m]
           (if large?
             (assoc-in m [frame-id sub-id] true)
             (update m frame-id dissoc sub-id))))
  nil)

(defn sub-output-sensitive?
  [frame-id sub-id]
  (true? (get-in @frame->sub-id->sensitive? [frame-id sub-id])))

(defn sub-output-large?
  [frame-id sub-id]
  (true? (get-in @frame->sub-id->large? [frame-id sub-id])))

(defn clear-sub-output-marks!
  ([] (reset! frame->sub-id->sensitive? {})
      (reset! frame->sub-id->large? {})
      nil)
  ([frame-id] (swap! frame->sub-id->sensitive? dissoc frame-id)
              (swap! frame->sub-id->large? dissoc frame-id)
              nil))

(defn resolve-sub-output-marks
  "Compute the sensitive/large flags that should be stamped onto a sub's
  output, given the sub's registered marks + input-signals' propagation
  state + a layer-1 sub's path overlap with the frame's app-db
  sensitive declarations.

  Resolution per Spec 015 §3. Subscriptions:
    1. `:sensitive? true`  forces sensitive
    2. `:sensitive? false` opts out (overrides propagation)
    3. Otherwise: propagate — if ANY input-signal's resolved sub-output
       is sensitive, OR if the sub is layer-1 and any sensitive app-db
       path was declared, mark sensitive
  Mirror for `:large?` / `:large`.

  Returns `[sensitive? large?]`."
  [frame-id sub-id input-signals layer-1?]
  (let [marks       (sub-marks sub-id)
        forced-s    (:sensitive? marks)
        forced-l    (:large? marks)
        ;; Propagation from inputs
        input-s?    (and (seq input-signals)
                         (some (fn [q] (sub-output-sensitive? frame-id (first q)))
                               input-signals))
        input-l?    (and (seq input-signals)
                         (some (fn [q] (sub-output-large? frame-id (first q)))
                               input-signals))
        ;; Layer-1: any sensitive app-db declaration triggers propagation
        ;; (footgun prevention — we don't track which path the sub
        ;; *actually* read; the conservative reading is "if there's
        ;; any sensitive path, the sub MAY have read it"). Spec 015
        ;; §Propagation rules acknowledges this is footgun prevention
        ;; not security-grade taint.
        any-sens?   (when layer-1?
                      (let [container (frame/get-frame-db frame-id)
                            db        (when container (adapter/read-container container))
                            decls     (get-in db [:rf/elision :sensitive-declarations])]
                        (boolean (seq decls))))
        any-large?  (when layer-1?
                      (let [container (frame/get-frame-db frame-id)
                            db        (when container (adapter/read-container container))
                            decls     (get-in db [:rf/elision :declarations])]
                        (boolean (seq decls))))
        sensitive?  (cond
                      (true? forced-s)  true
                      (false? forced-s) false
                      :else             (or input-s? (boolean any-sens?)))
        large?      (cond
                      (true? forced-l)  true
                      (false? forced-l) false
                      :else             (or input-l? (boolean any-large?)))]
    [(boolean sensitive?) (boolean large?)]))

;; ---- the trace-event projection chokepoint -------------------------------

(defn- project-event-tags
  "Walk `:event/dispatched` / `:event/db-changed` / `:event/do-fx` tag
  shapes: the dispatched event vector lives at `:event` and is a
  `[event-id arg-map]` form. Marks come from the event handler's
  registration."
  [tags]
  (let [event    (:event tags)
        event-id (when (vector? event) (first event))
        marks    (event-marks event-id)]
    (if-not marks
      tags
      (let [sens   (or (:sensitive marks) [])
            large  (or (:large marks) [])
            redacted (redact-event-vec event sens large)]
        (assoc tags :event redacted)))))

(defn- project-fx-tags
  "Walk `:rf.fx/handled` tag shape: `:fx-id` carries the fx keyword and
  `:fx-args` carries the args value. Marks come from the fx handler's
  registration."
  [tags]
  (let [fx-id (:fx-id tags)
        marks (fx-marks fx-id)]
    (if-not marks
      tags
      (let [sens     (or (:sensitive marks) [])
            large    (or (:large marks) [])
            redacted (redact-with-paths (:fx-args tags) sens large)]
        (assoc tags :fx-args redacted)))))

(defn- project-cofx-tags
  "Walk cofx-relevant tag shapes: the cofx-injected value rides under a
  cofx-id key (per `re-frame.cofx`'s injection convention). When a
  trace event carries a `:coeffects` slot (e.g. `:event/dispatched`,
  `:event/do-fx`), walk each cofx-id key against the cofx's marks."
  [tags]
  (let [cofx-map (:coeffects tags)]
    (if-not (map? cofx-map)
      tags
      (let [walked (reduce-kv
                     (fn [acc cofx-id v]
                       (let [marks (cofx-marks cofx-id)]
                         (if-not marks
                           (assoc acc cofx-id v)
                           (let [sens     (or (:sensitive marks) [])
                                 large    (or (:large marks) [])
                                 redacted (redact-with-paths v sens large)]
                             (assoc acc cofx-id redacted)))))
                     (empty cofx-map)
                     cofx-map)]
        (assoc tags :coeffects walked)))))

(defn- project-sub-tags
  "Walk `:sub/run` tag shape: `:sub-id` carries the sub query keyword
  and `:value` carries the output. Marks come from the sub's
  registration's per-output-path declarations, and the propagation
  table sets a whole-output `:sensitive? true` stamp."
  [tags frame-id]
  (let [sub-id (:sub-id tags)
        marks  (sub-marks sub-id)
        prop-s? (sub-output-sensitive? frame-id sub-id)
        prop-l? (sub-output-large? frame-id sub-id)]
    (cond
      ;; No marks AND no propagation — pass through unchanged.
      (and (nil? marks) (not prop-s?) (not prop-l?))
      tags

      ;; Whole-output propagation wins: stamp at root.
      (and prop-s? (not (false? (:sensitive? marks))))
      (assoc tags :value privacy/redacted-sentinel :sensitive? true)

      :else
      (let [sens     (or (:sensitive marks) [])
            large    (or (:large marks) [])
            redacted (redact-with-paths (:value tags) sens large)]
        (cond-> (assoc tags :value redacted)
          prop-l? (assoc :large? true))))))

(defn- project-machine-tags
  "Walk machine-snapshot tag shapes (`:rf.machine/transition`,
  `:rf.machine/snapshot-updated`): `:before` and `:after` are full
  snapshot maps. Marks declared on `reg-machine` are paths rooted at
  the snapshot — per Spec 015 §6. State machines — so common app-
  marks are written as `[:data :jwt]`, `[:data :user :ssn]`, etc."
  [tags]
  (let [machine-id (:machine-id tags)
        marks      (machine-marks machine-id)]
    (if-not marks
      tags
      (let [sens     (or (:sensitive marks) [])
            large    (or (:large marks) [])
            project  (fn [v] (when v (redact-with-paths v sens large)))]
        (cond-> tags
          (contains? tags :before)   (assoc :before   (project (:before   tags)))
          (contains? tags :after)    (assoc :after    (project (:after    tags)))
          (contains? tags :snapshot) (assoc :snapshot (project (:snapshot tags))))))))

(defn- machine-op?
  [operation]
  (let [n (and (keyword? operation) (namespace operation))]
    (and n (or (= "rf.machine" n)
               (and (>= (count n) 11)
                    (= "rf.machine." (subs n 0 11)))))))

(defn project-trace-event
  "The single chokepoint `re-frame.trace/build-event` consults after
  envelope assembly and before delivery. Walks `:tags` for marks
  declared on the in-scope registrations. Returns the (possibly
  mutated) event.

  Per Spec 015 §Implementation notes recommendation B: emit-time
  union of per-registration marks + propagation graph. The cost is
  gated by `interop/debug-enabled?` upstream (in `emit!`) so
  production builds elide before this fn is reached.

  Frame resolution comes off `:tags :frame` — every trace shape that
  carries handler-scope-derived data also carries `:frame` because
  the in-scope handler binds it through the router."
  [event]
  (if-not (map? event)
    event
    (let [operation (:operation event)
          tags      (:tags event)
          frame-id  (or (:frame tags) :rf/default)
          tags'     (cond-> tags
                      (and (map? tags) (contains? tags :event))
                      (project-event-tags)

                      (and (map? tags) (= :rf.fx/handled operation))
                      (project-fx-tags)

                      (and (map? tags) (contains? tags :coeffects))
                      (project-cofx-tags)

                      (and (map? tags) (= :sub/run operation))
                      (project-sub-tags frame-id)

                      (and (map? tags) (machine-op? operation))
                      (project-machine-tags))]
      (assoc event :tags tags'))))

;; ---- late-bind hook registration ----------------------------------------
;;
;; The trace ns reads through these hooks; this ns reads through the
;; existing elision-registry. The arrangement avoids load cycles
;; (`re-frame.trace` → `re-frame.marks` would cycle since marks
;; requires elision which requires trace).

(late-bind/set-fn! :marks/project-trace-event project-trace-event)
(late-bind/set-fn! :marks/register-marks!     register-marks!)
(late-bind/set-fn! :marks/resolve-sub-output-marks resolve-sub-output-marks)
(late-bind/set-fn! :marks/mark-sub-output!    mark-sub-output!)
(late-bind/set-fn! :marks/clear-marks!        clear-marks!)
(late-bind/set-fn! :marks/clear-sub-output-marks! clear-sub-output-marks!)
(late-bind/set-fn! :marks/reg-marks           reg-marks)
