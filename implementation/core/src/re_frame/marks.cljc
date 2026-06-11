(ns re-frame.marks
  "Data-classification path-marks for sensitive + large values per Spec 015.

  This namespace owns:
    - `add-marks` / `set-marks` — the dedicated registration kinds for
      declaring path-marks against an `app-db` (frame-scoped).
      `add-marks` merges into the existing frame mark-set; `set-marks`
      replaces the frame mark-set wholesale. Both take the same
      `{path mark, ...}` shape — symmetric path-keyed form.
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

  This namespace writes into the SAME durable elision registry slot the
  frame-owned classification installs into
  (`[:rf.runtime/elision :sensitive-declarations]` and
  `[:rf.runtime/elision :declarations]` in the frame's runtime-db
  partition — EP-0001 rf2-vzld77), keyed by absolute path, tagged
  `:source :marks`. The frame-owned (`:source :frame`, EP-0015 §3) and
  marks-sourced declarations union at lookup time — a path declared
  sensitive by EITHER source is sensitive. (`add-marks` / `set-marks`
  are DEMOTED off the public façade — EP-0015 §3, rf2-mngp4o — and remain
  as internal / test / generated-code helpers; durable app-db
  classification is authored on the frame. The former
  schema→app-db-egress route is gone post-EP-0015 §8.)"
  (:require [re-frame.elision :as elision]
            [re-frame.frame :as frame]
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
;; declarations bind to (kind, id), not to (frame, kind, id). `add-marks`
;; / `set-marks` are the exception — they are frame-scoped and write
;; into the per-frame elision registry.

(defonce ^:private kind->id->marks
  (atom {}))

;; ---- machine :data-schema marks (order-independent source — rf2-qpibk0) --
;;
;; Schema-derived machine marks live in a SEPARATE per-machine-id table from
;; the author-sourced `kind->id->marks` `:event` entry, and `marks-for :event
;; <id>` UNIONS the two at READ time. This is the SAME multi-source-union
;; architecture the app-db elision registry uses: differently-sourced
;; declarations (`:source :frame` from `reg-frame`, `:source :marks` from the
;; demoted imperative route) live ALONGSIDE each other in the registry,
;; unioned at lookup. Bringing it to machine marks makes the schema-vs-author union
;; truly ORDER-INDEPENDENT (rf2-qpibk0): a `register-marks!` (or a bare-meta
;; re-registration) on the `:event` entry REPLACES that entry in full
;; (matching the registrar slot semantics every other reg-* site relies on),
;; but it CANNOT drop the schema-derived `[:data …]` marks because they are
;; not stored there — they re-union at the next `marks-for` read regardless
;; of whether the manual `register-marks!` ran before OR after `reg-machine`.
;;
;; The prior bridge (rf2-w46fpt) worked around the replace by capturing the
;; manual marks BEFORE `reg-event-fx` and re-unioning them, which only held
;; for manual-BEFORE-reg-machine; a manual `register-marks!` AFTER reg-machine
;; still clobbered the schema marks. The separate-table read-time union fixes
;; that asymmetry.
;;
;; Keyed by machine-id (the `:event`-registry key — a machine IS an event
;; handler). Per-instance (spawned-actor) entries also live here so the
;; spawn-time bridge keys schema marks under the instance id (rf2-fm1cpl) and
;; the destroy/finalize/frame-teardown lifecycle clears them (rf2-egvm4t).

(defonce ^:private machine-id->schema-marks
  (atom {}))

;; ---- malformed-declaration rejection (rf2-y7l5t5) ------------------------
;;
;; A `:sensitive` / `:large` declaration is a vector of output-path vectors
;; (`[[:user :ssn] [:auth :token]]`; `[[]]` marks the whole value). The prior
;; `coerce-paths` SILENTLY `(filter vector?)`-DROPPED any non-vector entry and
;; coerced a non-collection whole to `[]`, so a hand-written typo —
;; `:sensitive :token` (bare keyword), `:sensitive "blob"` (string),
;; `:sensitive {…}` (map), or `:sensitive [:token]` / `[[:a [:b]]]` (a
;; non-vector / non-scalar-element entry) — registered with NO error and NO
;; mark. The author believes a path is redacted and it is NOT: the EP-0005
;; armed-trap shape on the marks-ingestion side, the worst failure mode for a
;; safety surface.
;;
;; We now REJECT LOUDLY at the ingestion boundary (`register-marks!` /
;; `union-marks!`, called from the reg-* paths), mirroring the flows-side fix
;; (rf2-cgk0wb, which rejects malformed `reg-flow` classification metadata with
;; `:rf.error/flow-bad-marks` before any state mutates). Per the k0ew8n
;; warn-vs-reject principle: there are ZERO legitimate non-vector entries and a
;; correct spelling always exists (`[[:token]]`), so REJECT, not warn. The
;; ex-info carries the canonical thrown-error shape (Spec 009 §The thrown-error
;; shape) and names the offending key + value/entry so the author can fix it
;; without a stack-trace dig.

(defn- valid-mark-element?
  "A mark path element is a scalar map key: keyword / string / integer /
  symbol / boolean. A collection element is never a valid `get-in` step and
  signals a caller bug (e.g. a nested-vector path `[[:a [:b]]]`)."
  [x]
  (or (keyword? x) (string? x) (integer? x) (symbol? x) (boolean? x)))

(defn- valid-mark-subpath?
  "A `:sensitive` / `:large` entry is a vector of scalar path elements. Unlike
  a flow `:inputs` path the EMPTY vector `[]` is legal — it marks the whole
  value (the `[[]]` convention)."
  [x]
  (and (vector? x) (every? valid-mark-element? x)))

(defn- marks-error
  "Build the malformed-marks ex-info with the canonical thrown-error shape
  (per Spec 009 §The thrown-error shape). Mirrors `re-frame.flows.registry`'s
  `flow-error`: `:rf.error/bad-marks` is the message AND the `:rf.error/id`
  discriminator; `:bad-key` names the offending classification key; `extras`
  merges the offending-value slot (`:bad-value` for a non-vector whole,
  `:bad-entries` for malformed entries)."
  [mark-key reason extras]
  (ex-info (str :rf.error/bad-marks)
           (merge {:rf.error/id :rf.error/bad-marks
                   :where       'rf/reg-marks
                   :recovery    :fix-registration
                   :reason      reason
                   :bad-key     mark-key}
                  extras)))

(defn- coerce-paths
  "Normalise a `:sensitive` / `:large` declaration value to a vector of
  path vectors. `nil` becomes `[]`. The whole value must be a vector and
  every entry a vector of scalar path elements (`[[]]` for whole-value
  marks); a malformed value or entry is REJECTED with `:rf.error/bad-marks`
  rather than silently dropped (rf2-y7l5t5 — fail-loud, not fail-open).

  `mark-key` (`:sensitive` / `:large`) names the offending key in the thrown
  ex-data. The 1-arity form is for internal re-normalisation of already-stored
  (well-formed) entries during a union; it uses the same validation so a
  corrupted stored entry can never slip through silently."
  ([paths] (coerce-paths paths :sensitive))
  ([paths mark-key]
   (cond
     (nil? paths)
     []

     (not (vector? paths))
     (throw (marks-error mark-key
                         (str mark-key ", when present, must be a vector of "
                              "output paths (each a vector of scalar keys; "
                              "[] marks the whole value)")
                         {:bad-value paths}))

     (not (every? valid-mark-subpath? paths))
     (throw (marks-error mark-key
                         (str mark-key " entries must each be a vector of "
                              "scalar keys (keyword / string / integer / "
                              "symbol / boolean); [] marks the whole value")
                         {:bad-entries (vec (remove valid-mark-subpath? paths))}))

     :else
     paths)))

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
      (contains? meta :sensitive)  (assoc :sensitive  (coerce-paths (:sensitive meta) :sensitive))
      (contains? meta :large)      (assoc :large      (coerce-paths (:large meta) :large))
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
      ;; supersede the old one in full. The same rule generalises
      ;; from `set-marks` to every reg-* site.
      (swap! kind->id->marks update kind dissoc id)
      (swap! kind->id->marks assoc-in [kind id] marks)))
  nil)

(defn- union-path-vecs
  "Union `existing` and `added` path-vector collections, preserving order
  (existing first, then any added paths not already present) and dropping
  non-vector / duplicate entries. Returns a vector, or nil when the union
  is empty — so the caller can omit an empty slot rather than stash `[]`."
  [existing added]
  (let [seen   (volatile! #{})
        result (reduce (fn [acc p]
                         (let [p (vec p)]
                           (if (or (not (vector? p)) (contains? @seen p))
                             acc
                             (do (vswap! seen conj p)
                                 (conj acc p)))))
                       []
                       (concat (coerce-paths existing) (coerce-paths added)))]
    (when (seq result) result)))

(defn- union-whole-output-flag
  "OR a whole-output `:sensitive?` / `:large?` flag across the existing and
  added declarations, preserving an explicit `false`. Returns the resolved
  boolean, or nil when NEITHER side declared the flag (so the caller omits
  the slot rather than stash a meaningless value).

  Per Spec 015 §union-by-source the flag is monotone-OR (rf2-1zqh1z): `true`
  on EITHER side wins; an explicit `false` is PRESERVED when the other side
  is absent (it is a real opt-out declaration, not a missing one). The prior
  `(or existing added)` collapsed `(or false nil)` to nil and DROPPED the
  opt-out — a footgun the false-override fix closes. Only when both sides are
  absent (nil) does the slot vanish."
  [existing-flag added-flag]
  (cond
    (or (true? existing-flag) (true? added-flag)) true
    (or (false? existing-flag) (false? added-flag)) false
    :else nil))

(defn union-marks!
  "Union a mark declaration into the existing `(kind, id)` marks entry —
  the per-(kind, id) marks-table analogue of `add-marks` merging into a
  frame's app-db elision registry (Spec 015 §App-db marks). Returns nil.
  No-op when `meta` carries no `:sensitive` / `:large` / `:sensitive?` /
  `:large?` keys.

  Unlike `register-marks!` (which REPLACES the entry in full, matching the
  registrar's slot semantics), this MERGES: the supplied `:sensitive` /
  `:large` path vectors UNION with whatever the entry already holds, so a
  registration with a schema-derived mark set AND a manually-registered
  one ends up with BOTH (Spec 015 §Conflict between the two sources is
  resolved by union — there is no way for one source to unmark a path the
  other marked). The whole-output `:sensitive?` / `:large?` flags OR into
  the entry (true wins) and an explicit `false` opt-out is PRESERVED across
  a union that touches only paths (rf2-1zqh1z) — the OR semantics this fn
  documents are honoured both ways.

  ORDER-INDEPENDENT (rf2-qpibk0): unioning declaration A then B yields the
  identical entry as B then A — set-union of paths plus monotone-OR of the
  whole-output flags is commutative + associative, so the registration order
  of a machine's `:data-schema` bridge and a manual `register-marks!` never
  changes the result.

  Used by `reg-machine`'s `:data-schema` redaction bridge (EP-0005): the
  schema's per-slot `:sensitive?` / `:large?` paths (snapshot-rooted under
  `[:data …]`) union into the machine's `:event`-keyed marks entry, so a
  token marked `:sensitive?` in a `:data-schema` is redacted in snapshot
  egress (`project-machine-tags`) exactly like an app-db slot — and a
  machine that ALSO carries a manual `register-marks!` keeps both sets."
  [kind id meta]
  (let [added (normalise-marks meta)]
    (when added
      (swap! kind->id->marks update-in [kind id]
             (fn [existing]
               (let [union-s (union-path-vecs (:sensitive existing) (:sensitive added))
                     union-l (union-path-vecs (:large existing)     (:large added))
                     sens?   (union-whole-output-flag (:sensitive? existing) (:sensitive? added))
                     large?  (union-whole-output-flag (:large? existing)     (:large? added))]
                 (cond-> {}
                   union-s          (assoc :sensitive  union-s)
                   union-l          (assoc :large      union-l)
                   (some? sens?)    (assoc :sensitive? sens?)
                   (some? large?)   (assoc :large?     large?)))))))
  nil)

(defn- merge-schema-marks
  "Union the author-sourced `manual` marks entry with the schema-sourced
  `schema` marks entry into one resolved declaration. Both arguments are the
  canonical `{:sensitive [...] :large [...] :sensitive? bool :large? bool}`
  shape (or nil). Returns the union, or nil when both are nil/empty — so a
  machine with neither manual nor schema marks reads as `nil` (the same
  no-marks signal `marks-for` returned before this table existed).

  Set-union of paths plus monotone-OR of whole-output flags — commutative,
  so the union does not depend on which source is treated as `existing`
  (rf2-qpibk0 order-independence)."
  [manual schema]
  (when (or manual schema)
    (let [union-s (union-path-vecs (:sensitive manual) (:sensitive schema))
          union-l (union-path-vecs (:large manual)     (:large schema))
          sens?   (union-whole-output-flag (:sensitive? manual) (:sensitive? schema))
          large?  (union-whole-output-flag (:large? manual)     (:large? schema))
          merged  (cond-> {}
                    union-s        (assoc :sensitive  union-s)
                    union-l        (assoc :large      union-l)
                    (some? sens?)  (assoc :sensitive? sens?)
                    (some? large?) (assoc :large?     large?))]
      (when (seq merged) merged))))

(defn marks-for
  "Return the registered mark declaration for `(kind, id)`, or nil.

  The returned shape is `{:sensitive [paths] :large [paths]
  :sensitive? bool :large? bool}` — slots are present only when the
  registration declared them.

  For `:event`-kind ids that name a machine carrying a `:data-schema`, the
  author-sourced `kind->id->marks` entry is UNIONED at read time with the
  schema-sourced `machine-id->schema-marks` entry (rf2-qpibk0). The two
  tables are kept separate so a `register-marks!` (or bare-meta
  re-registration) on the `:event` entry — which REPLACES it in full — can
  never drop schema-derived `[:data …]` marks, making the union truly
  order-independent regardless of whether the manual marks were registered
  before OR after `reg-machine`."
  [kind id]
  (let [manual (get-in @kind->id->marks [kind id])]
    (if (= :event kind)
      (merge-schema-marks manual (get @machine-id->schema-marks id))
      manual)))

(defn declare-machine-schema-marks!
  "Record a machine's `:data-schema`-derived marks under `machine-id` in the
  schema-sourced table (rf2-qpibk0). `marks` is the canonical
  `{:sensitive [paths] :large [paths] :sensitive? bool :large? bool}` shape
  (snapshot-rooted under `[:data …]`), or nil to clear the entry. Returns nil.

  Kept separate from the author-sourced `kind->id->marks` `:event` entry so
  `marks-for :event machine-id` unions the two at read time — order-
  independent against any `register-marks!` / re-registration on the `:event`
  entry. Called by `reg-machine`'s `:data-schema` bridge for both the type id
  (at `reg-machine` time) and per-instance spawned-actor ids (at spawn time,
  rf2-fm1cpl)."
  [machine-id marks]
  (if (nil? marks)
    (swap! machine-id->schema-marks dissoc machine-id)
    (swap! machine-id->schema-marks assoc machine-id marks))
  nil)

(defn clear-machine-schema-marks!
  "Drop the schema-sourced marks entry for `machine-id`. Returns nil.

  The destroy / finalize / frame-teardown lifecycle clears a SPAWNED
  INSTANCE's per-instance schema marks here (rf2-egvm4t) so a destroyed
  actor leaves no marks-table residue, and `restore-epoch` / replay re-runs
  the spawn bridge to rehydrate them — the marks table tracks live
  spawned-actor liveness in lock-step with the (revertible) snapshot. Safe
  to call for an id with no entry (no-op)."
  [machine-id]
  (swap! machine-id->schema-marks dissoc machine-id)
  nil)

(defn clear-marks!
  "Drop every registered marks declaration (author-sourced AND
  schema-sourced). Test-isolation only — production code never calls this.
  Returns nil."
  []
  (reset! kind->id->marks {})
  (reset! machine-id->schema-marks {})
  nil)

;; ---- add-marks / set-marks API ------------------------------------------
;;
;; Two dedicated registration kinds for declaring path-marks against an
;; `app-db`. Frame-scoped per Spec 015. Both write through the
;; `[:rf.runtime/elision :sensitive-declarations]` / `[:rf.runtime/elision :declarations]`
;; runtime-db registry slots (EP-0001 rf2-vzld77 — the elision registry is
;; durable framework state) so the schema-first elision walker
;; (`re-frame.elision/elide-wire-value`) sees the declarations without a
;; second lookup path.
;;
;; Internal-helper shape (path-keyed, symmetric between the two fns). Per
;; EP-0015 (rf2-mngp4o) these are NO LONGER published from `re-frame.core`
;; — frame-owned `:sensitive` / `:large` classification + `project-egress`
;; are the public authoring boundary; `add-marks` / `set-marks` survive as
;; internal / test / generated-code helpers called via this home ns:
;;
;;   (marks/add-marks :rf/default
;;     {[:user :ssn]   :sensitive
;;      [:auth :token] :sensitive
;;      [:docs :csv]   :large})
;;
;; - `add-marks` MERGES the supplied paths into the frame's existing
;;   marks set. Paths the caller does NOT mention keep their prior
;;   state. Author-facing repeat-call semantics: additive.
;;
;; - `set-marks` REPLACES the frame's marks set wholesale. Paths the
;;   caller does NOT mention are CLEARED (only the supplied paths
;;   survive). Author-facing repeat-call semantics: declarative.
;;
;; Both honour last-write-wins semantics when called against the same
;; frame and overlap with each other. Differently-sourced entries are
;; always preserved — frame-owned classification (`:source :frame` from
;; `reg-frame`, EP-0015 §3) is not owned by this namespace and lives
;; alongside the `:source :marks` entries, unioned at lookup. (The former
;; schema→app-db-egress route — `:source :schema` — is gone post-EP-0015 §8;
;; schemas describe shape, not durable app-db egress policy.)

(defn- without-marks-sourced
  "Drop entries whose `:source` is `:marks` from a declaration map.
  Used by `set-marks` to clear the prior `add-marks` / `set-marks`
  contributions before overlaying the new ones — frame-sourced
  (`:source :frame`) entries survive."
  [decls]
  (when decls
    (reduce-kv (fn [acc path decl]
                 (if (= :marks (:source decl))
                   acc
                   (assoc acc path decl)))
               {}
               decls)))

(defn- split-by-mark
  "Partition `{path mark}` map into `[sensitive-paths large-paths]`.
  Any unknown mark value is silently dropped (best-effort — the
  declaration is non-validating)."
  [path->mark]
  (reduce-kv (fn [[s l] path mark]
               (case mark
                 :sensitive [(conj s (vec path)) l]
                 :large     [s (conj l (vec path))]
                 [s l]))
             [[] []]
             path->mark))

(defn- assoc-paths
  "Add `paths` to `existing` declaration map with `{:source :marks}`."
  [existing paths]
  (reduce (fn [acc path]
            (assoc acc (vec path) {:source :marks}))
          (or existing {})
          paths))

(defn add-marks
  "Additively merge path-marks into the `app-db` mark-set of `frame-id`.
  Per Spec 015 §App-db marks (per frame).

  `path->mark` is a map from `get-in`-shaped path vectors to mark
  keywords (`:sensitive` or `:large`). Paths supplied here MERGE into
  the frame's existing marks — paths NOT mentioned keep their prior
  state. Repeat calls accumulate.

      (marks/add-marks :rf/default
        {[:user :ssn]   :sensitive
         [:auth :token] :sensitive
         [:docs :csv]   :large})

  Returns `frame-id`. Pure declaration — does NOT mutate `app-db`,
  does NOT install an interceptor, does NOT change any handler's view
  of the data. The declaration only feeds the mark-lookup table the
  observation surfaces (trace bus, Xray, MCP, third-party log sinks)
  consult at emission time.

  NOTE: not a public façade fn (EP-0015 §3, rf2-mngp4o) — frame-owned
  `:sensitive` / `:large` classification is the public authoring surface;
  this is an internal / test / generated-code helper.

  Frame-owned declarations (`reg-frame` `:sensitive` / `:large {:app-db …}`,
  `:source :frame`) are preserved — the two declaration sources union at
  lookup time. (The former schema→app-db-egress route is gone post-EP-0015
  §8; schemas describe shape, not durable app-db egress policy.) Use
  `set-marks` for replace-semantics, or call `add-marks` with a path mapped
  to a different mark to last-write-wins overwrite that path's mark."
  [frame-id path->mark]
  (let [[sens-paths large-paths] (split-by-mark path->mark)]
    (elision/swap-elision-slot! frame-id
      (fn [reg]
        (let [new-s (assoc-paths (get reg :sensitive-declarations) sens-paths)
              new-l (assoc-paths (get reg :declarations) large-paths)]
          (cond-> (or reg {})
            (seq new-s)    (assoc :sensitive-declarations new-s)
            (empty? new-s) (dissoc :sensitive-declarations)
            (seq new-l)    (assoc :declarations new-l)
            (empty? new-l) (dissoc :declarations))))))
  frame-id)

(defn set-marks
  "Replace the `app-db` mark-set of `frame-id` with `path->mark`.
  Per Spec 015 §App-db marks (per frame).

  `path->mark` is a map from `get-in`-shaped path vectors to mark
  keywords (`:sensitive` or `:large`). Paths supplied here REPLACE the
  frame's prior marks set wholesale — paths NOT mentioned are CLEARED.

      (marks/set-marks :rf/default
        {[:user :ssn]   :sensitive
         [:auth :token] :sensitive
         [:docs :csv]   :large})

  Returns `frame-id`. Pure declaration — does NOT mutate `app-db`,
  does NOT install an interceptor, does NOT change any handler's view
  of the data. The declaration only feeds the mark-lookup table the
  observation surfaces (trace bus, Xray, MCP, third-party log sinks)
  consult at emission time.

  NOTE: not a public façade fn (EP-0015 §3, rf2-mngp4o) — frame-owned
  `:sensitive` / `:large` classification is the public authoring surface;
  this is an internal / test / generated-code helper.

  Frame-owned declarations (`reg-frame` `:sensitive` / `:large {:app-db …}`,
  `:source :frame`) are preserved — only the `:source :marks` entries are
  dropped. The two declaration sources union at lookup time. (The former
  schema→app-db-egress route is gone post-EP-0015 §8.)

  Use `add-marks` for additive-merge semantics."
  [frame-id path->mark]
  (let [[sens-paths large-paths] (split-by-mark path->mark)]
    (elision/swap-elision-slot! frame-id
      (fn [reg]
        ;; Drop prior :marks-sourced entries first (frame-sourced survive),
        ;; then assoc the new paths.
        (let [carry-s (without-marks-sourced (get reg :sensitive-declarations))
              carry-l (without-marks-sourced (get reg :declarations))
              new-s   (assoc-paths carry-s sens-paths)
              new-l   (assoc-paths carry-l large-paths)]
          (cond-> (or reg {})
            (seq new-s)    (assoc :sensitive-declarations new-s)
            (empty? new-s) (dissoc :sensitive-declarations)
            (seq new-l)    (assoc :declarations new-l)
            (empty? new-l) (dissoc :declarations))))))
  frame-id)

(defn clear-app-db-marks!
  "Drop every `add-marks` / `set-marks`-sourced declaration for
  `frame-id`. Frame-sourced (`:source :frame`) declarations are preserved.
  Returns nil. Test-isolation only; production code rarely needs this."
  [frame-id]
  (elision/swap-elision-slot! frame-id
    (fn [reg]
      (let [new-s (without-marks-sourced (:sensitive-declarations reg))
            new-l (without-marks-sourced (:declarations reg))]
        (cond-> {}
          (seq new-s) (assoc :sensitive-declarations new-s)
          (seq new-l) (assoc :declarations new-l)))))
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

(defn large-marker
  "Build the `:rf.size/large-elided` marker for value `v` at `path`.
  Mirror of `re-frame.elision/->marker`'s shape — inlined so this ns
  carries no dependency on elision's privates. Carries `:reason
  :marks` so consumers can discriminate per-registration marks
  from schema-driven marks.

  Public because the off-box epoch egress projector
  (`re-frame.epoch.tool-pair/projected-record`, rf2-at60h) reuses it to
  substitute the marker for a whole-output `:large?`-stamped sub's
  `:value` / `:prev-value` in the structured `:sub-runs` row — the same
  `:reason :marks` provenance the whole-output propagation table sets,
  built in ONE place rather than re-inlined a third time."
  [v path]
  (let [p (vec path)]
    {:rf.size/large-elided
     {:path   p
      :bytes  (->bytes v)
      :type   (value-type v)
      :reason :marks
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

;; Flow output marks are NOT resolved through this per-(kind, id) table
;; (rf2-ouemt). Spec 013 lets the SAME flow-id carry different definitions —
;; hence different `:sensitive` / `:large` declarations — in different
;; frames, so a frame-blind `{flow-id marks}` shape is wrong for flows.
;; `re-frame.flows.registry` instead installs each flow's output marks
;; FRAME-AWARE into that frame's app-db elision registry, rooted at the
;; flow's `:path`; the schema-first wire walker (`elision/elide-wire-value`)
;; the flow trace emits already ride, plus `project-db-tags` /
;; `project-view-rendered-tags` below, redact the flow output through that
;; single per-frame source of truth.

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
        ;; EP-0001 (rf2-vzld77): the elision registry is durable framework
        ;; state in the frame's runtime-db partition at `[:rf.runtime/elision …]`
        ;; (Conventions §Reserved runtime-db keys), so the layer-1 footgun
        ;; check reads the runtime-db projection, not app-db.
        any-sens?   (when layer-1?
                      (let [container (frame/runtime-db-container frame-id)
                            rt        (when container (adapter/read-container container))
                            decls     (get-in rt [:rf.runtime/elision :sensitive-declarations])]
                        (boolean (seq decls))))
        any-large?  (when layer-1?
                      (let [container (frame/runtime-db-container frame-id)
                            rt        (when container (adapter/read-container container))
                            decls     (get-in rt [:rf.runtime/elision :declarations])]
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
  "Walk `:rf.event/dispatched` / `:rf.event/db-changed` / `:rf.fx/do-fx`
  tag shapes: the dispatched event vector lives at `:rf.event/v` and is a
  `[event-id arg-map]` form. Marks come from the event handler's
  registration.

  The `:rf.error/*` error traces (e.g. `:rf.error/handler-exception`)
  carry the event vector under the bare `:event` slot per the
  `:rf/error-event` `HandlerExceptionTags` shape (Spec-Schemas), not
  `:rf.event/v`. We redact whichever slot the trace carries so the
  schema-driven / interceptor-driven scrub reaches both the success and
  the error channel."
  [tags slot]
  (let [event    (get tags slot)
        event-id (when (vector? event) (first event))
        marks    (event-marks event-id)]
    (if-not marks
      tags
      (let [sens   (or (:sensitive marks) [])
            large  (or (:large marks) [])
            redacted (redact-event-vec event sens large)]
        (assoc tags slot redacted)))))

(defn- project-fx-tags
  "Walk `:rf.fx/handled` tag shape: `:rf.fx/id` carries the fx keyword and
  `:rf.fx/args` carries the args value. Marks come from the fx handler's
  registration."
  [tags]
  (let [fx-id (:rf.fx/id tags)
        marks (fx-marks fx-id)]
    (if-not marks
      tags
      (let [sens     (or (:sensitive marks) [])
            large    (or (:large marks) [])
            redacted (redact-with-paths (:rf.fx/args tags) sens large)]
        (assoc tags :rf.fx/args redacted)))))

(defn- project-cofx-tags
  "Walk cofx-relevant tag shapes: the cofx-injected value rides under a
  cofx-id key (per `re-frame.cofx`'s injection convention). When a
  trace event carries a `:rf.event/coeffects` slot (e.g.
  `:rf.event/dispatched`, `:rf.event/run-end` — rf2-9dk9y), walk each
  cofx-id key against the cofx's marks."
  [tags]
  (let [cofx-map (:rf.event/coeffects tags)]
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
        (assoc tags :rf.event/coeffects walked)))))

(defn- project-cofx-run-tags
  "Walk the `:rf.cofx/run` op shape (rf2-hhh92): `:rf.cofx/id` carries the
  cofx keyword and `:rf.cofx/value` carries the per-call injected value.
  Redact the value against the cofx's registered marks — mirrors
  `project-fx-tags` for the standalone-value op (the cofx success emit
  does not ride under `:rf.event/coeffects`)."
  [tags]
  (let [cofx-id (:rf.cofx/id tags)
        marks   (cofx-marks cofx-id)]
    (if (or (not marks) (not (contains? tags :rf.cofx/value)))
      tags
      (let [sens     (or (:sensitive marks) [])
            large    (or (:large marks) [])
            redacted (redact-with-paths (:rf.cofx/value tags) sens large)]
        (assoc tags :rf.cofx/value redacted)))))

(defn- project-sub-tags*
  "Inner projection for `project-sub-tags` against a KNOWN carried frame.
  Reads the per-frame whole-output sensitive/large propagation table plus
  the sub's process-scoped per-registration marks."
  [tags frame-id sub-id marks]
  (let [prop-s? (sub-output-sensitive? frame-id sub-id)
        prop-l? (sub-output-large? frame-id sub-id)
        ;; Redact `:rf.sub/prev-value` with the same rule as
        ;; `:rf.sub/value`, but ONLY when the slot is present (the pure
        ;; compute-sub emit and the production base-shape emit omit it).
        ;; `nil` prev-value (first recompute) passes through redaction
        ;; harmlessly.
        has-prev? (contains? tags :rf.sub/prev-value)]
    (cond
      ;; No marks AND no propagation — pass through unchanged.
      (and (nil? marks) (not prop-s?) (not prop-l?))
      tags

      ;; Whole-output propagation wins: stamp at root.
      (and prop-s? (not (false? (:sensitive? marks))))
      (cond-> (assoc tags :rf.sub/value privacy/redacted-sentinel :sensitive? true)
        has-prev? (assoc :rf.sub/prev-value privacy/redacted-sentinel))

      :else
      (let [sens     (or (:sensitive marks) [])
            large    (or (:large marks) [])
            redacted (redact-with-paths (:rf.sub/value tags) sens large)]
        (cond-> (assoc tags :rf.sub/value redacted)
          has-prev? (assoc :rf.sub/prev-value (redact-with-paths (:rf.sub/prev-value tags) sens large))
          prop-l?   (assoc :large? true))))))

(defn- project-sub-tags
  "Walk `:sub/run` tag shape: `:sub-id` carries the sub query keyword
  and `:value` carries the output. Per rf2-l1jz8 the reactive recompute
  also stamps a `:prev-value` (the prior computed value) for value-change
  attribution — it is the SAME sub's output as `:value`, one recompute
  earlier, so it gets the IDENTICAL redaction treatment. Marks come from
  the sub's registration's per-output-path declarations, and the
  propagation table sets a whole-output `:sensitive? true` stamp.

  Both value slots are redacted from process-scoped marks only — this
  fn reads NO reactive container (it runs inside the sub's reaction
  compute via `trace/build-event`; a container deref here would register
  a spurious app-db dependency and break glitch-free layering).

  EP-0002 (rf2-gjq3ow) — FAIL CLOSED on a nil `frame-id`: subs are
  frame-scoped, so a sub trace with no carried frame is malformed. The
  whole-output sensitive/large propagation table is keyed by frame and
  cannot be consulted without one, so we cannot prove the output is safe to
  ship — `:rf.sub/value` (and `:rf.sub/prev-value` when present) are
  conservatively redacted to `:rf/redacted` and the event stamped
  `:sensitive? true`, rather than borrow a default frame's propagation
  state."
  [tags frame-id]
  (let [sub-id     (:rf.sub/id tags)
        marks      (sub-marks sub-id)
        has-prev?  (contains? tags :rf.sub/prev-value)]
    (if (nil? frame-id)
      (cond-> (assoc tags :rf.sub/value privacy/redacted-sentinel :sensitive? true)
        has-prev? (assoc :rf.sub/prev-value privacy/redacted-sentinel))
      (project-sub-tags* tags frame-id sub-id marks))))

(defn- frame-has-declarations?
  "True when `frame-id` carries any schema- or marks-sourced elision
  declaration (sensitive or large). Cheap registry read used to gate
  the full-db walk so the no-marks common case stays
  reference-preserving (no `elide-wire-value` rebuild). Mirrors the
  bead's `no extra work for values with no marks` constraint."
  [frame-id]
  (boolean (or (seq (elision/sensitive-declarations frame-id))
               (seq (elision/declarations frame-id)))))

(defn- project-db-tags
  "Walk the `:rf.event/db` slot carried by the `:rf.event/db-pending`
  (t1) and `:rf.event/db-pending-post-flow` (t2) trace events (router
  `flows-after-interceptor`, rf2-ta0y7). The slot stamps the FULL
  pending `app-db` value, so — unlike the per-registration event / fx /
  cofx / sub slots — its declared paths are the FRAME's app-db elision
  registry (schema `:sensitive?` / `:large?` plus `add-marks` /
  `set-marks`), rooted at the db root. We route it through the schema-
  first wire walker `re-frame.elision/elide-wire-value` — the SAME
  normative emission site the epoch off-box `projected-record` uses for
  `:db-before` / `:db-after` — so sensitive slots elide to `:rf/redacted`
  and large slots to `:rf.size/large-elided` before the snapshot reaches
  any trace listener or epoch-capture sink. Per rf2-6773q.

  Gated on `frame-has-declarations?` so a frame with no marks keeps the
  reference-identity the `:rf.event/db` stamp promises (rf2-ta0y7's
  pointer-sized, copy-free posture) — the walker rebuilds maps, so we
  must not invoke it when there is nothing to elide.

  EP-0002 (rf2-gjq3ow) — FAIL CLOSED on a nil `frame-id`: a `:rf.event/db`
  full-db stamp with no carried frame cannot be elided against any frame's
  policy, so the whole slot is conservatively redacted to the `:rf/redacted`
  sentinel rather than shipped verbatim. A frameless db-stamp is either
  malformed or a boundary the per-frame registry cannot reach — either way
  the value must not leak. The redaction is direct (not via
  `elide-wire-value`) so it cannot re-resolve to an ambient scope and
  borrow that frame's marks — frameless means frameless."
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
  "Walk the `:rf.view/render-args` slot carried by the `:rf.view/rendered`
  trace event (rf2-rpgq8). The slot holds the vector of POSITIONAL render
  args/props passed to a view's render — arbitrary user data, captured by
  the substrate-agnostic `re-frame.views/build-frame-aware-view` wrapper.

  A view is not a handler with per-registration marks, so — like
  `:rf.event/db` — the declared paths are the FRAME's app-db elision
  registry (schema `:sensitive?` / `:large?` plus `add-marks` /
  `set-marks`). We route EACH positional arg through the SAME schema-first
  wire walker `re-frame.elision/elide-wire-value` `project-db-tags` uses
  for `:rf.event/db`, so a sensitive leaf inside a prop map (e.g. a prop
  whose key mirrors a `{:sensitive? true}` app-db path) elides to
  `:rf/redacted` and an over-threshold / `{:large? true}` leaf elides to
  `:rf.size/large-elided` BEFORE the event reaches any trace listener,
  epoch-capture sink, or the AI/MCP wire — the identical emit-time
  treatment every other user-data trace payload gets (Spec 009 §Privacy /
  Spec 015 §Data classification §Views).

  Gated on `frame-has-declarations?` so a frame with no marks keeps the
  args reference-identity untouched (the walker rebuilds collections, so
  we must not invoke it when there is nothing to elide).

  EP-0002 (rf2-gjq3ow) — FAIL CLOSED on a nil `frame-id`: render args with
  no carried frame cannot be elided against any frame's policy, so EACH
  positional arg is conservatively redacted to the `:rf/redacted` sentinel
  rather than shipped verbatim — frameless means frameless, never a
  borrowed default frame's marks."
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
  "Re-root a vector of snapshot-rooted machine mark paths (each
  `[:data …]`) to be `:data`-MAP-relative — drop the leading `:data`
  segment. A path that is NOT under `:data` (e.g. a hand-registered
  `[:state …]` mark) does not address the `:data` map and is dropped, since
  the alternate machine trace slots this re-rooting feeds (`:rf.machine/started`
  `:data`, the `:input :data` of guard/action traces, the cascade
  `:data-delta`s) carry ONLY the `:data` map's contents. The bare `[:data]`
  whole-`:data` mark re-roots to `[[]]` (whole-value)."
  [paths]
  (into []
        (comp (filter #(= :data (first %)))
              (map #(vec (rest %))))
        paths))

(defn- project-machine-tags
  "Walk machine `:data`-bearing trace tag shapes. Marks declared on
  `reg-machine` (and bridged from the `:data-schema`) are paths rooted at the
  SNAPSHOT — per Spec 015 §6. State machines — so common marks are written as
  `[:data :jwt]`, `[:data :user :ssn]`, etc. Machine `:data` surfaces in
  several differently-shaped trace slots, and per rf2-20d6k2 EVERY one is
  redacted so a `:sensitive?` / `:large?` slot never egresses raw:

    - `:before` / `:after` / `:snapshot` (`:rf.machine/transition`,
      `:rf.machine/snapshot-updated`) — FULL snapshot maps; the snapshot-rooted
      `[:data …]` paths apply directly.
    - `:data` (`:rf.machine/started`) — the booted snapshot's `:data` MAP
      directly (one level shallower than a snapshot), so the paths re-root
      `:data`-relative (`[:data :token]` → `[:token]`).
    - `:input` (`:rf.machine/guard-evaluated` / `:rf.machine/action-ran`) —
      `{:data <snapshot :data> :event <event-vec>}`; the `:data` sub-map gets
      the `:data`-relative paths, the `:event` sub-slot is left to
      `project-event-tags` (it is the dispatched event vector, not machine
      `:data`).
    - `:cascade` (`:rf.machine/transition`) — a vector of step maps, each with
      a `:data-delta {<k> <new-v>}` keyed by `:data` keys directly; each delta
      gets the `:data`-relative paths so a sensitive key an action wrote is
      redacted in the per-step explanation (the `:data-delta` rides under the
      same handler-scope `:sensitive?` stamp as `:before` / `:after` per Spec
      005 §Privacy).

  All slots resolve marks via the SAME machine-id lookup, so a spawned
  instance's id-keyed schema marks (rf2-fm1cpl) cover the instance's traces."
  [tags]
  (let [machine-id (:machine-id tags)
        marks      (machine-marks machine-id)]
    (if-not marks
      tags
      (let [sens       (or (:sensitive marks) [])
            large      (or (:large marks) [])
            ;; `:data`-map-relative paths for the slots that carry the bare
            ;; `:data` map (not a full snapshot).
            data-sens  (strip-data-prefix sens)
            data-large (strip-data-prefix large)
            ;; Whole-snapshot projection (paths rooted at the snapshot).
            project    (fn [v] (when v (redact-with-paths v sens large)))
            ;; Bare-`:data`-map projection (paths rooted at the `:data` map).
            project-data (fn [v] (when v (redact-with-paths v data-sens data-large)))
            ;; A guard/action `:input` map: redact its `:data` sub-slot;
            ;; leave `:event` to project-event-tags.
            project-input (fn [input]
                            (if (and (map? input) (contains? input :data))
                              (assoc input :data (project-data (:data input)))
                              input))
            ;; The cascade step vector: redact each step's `:data-delta` map.
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
          ;; rf2-20d6k2 — the additional machine `:data` slots.
          (contains? tags :data)     (assoc :data     (project-data (:data tags)))
          (contains? tags :input)    (assoc :input    (project-input (:input tags)))
          (contains? tags :cascade)  (assoc :cascade  (project-cascade (:cascade tags))))))))

(defn- project-machine-error-tags
  "Walk the `:rf.error/machine-action-exception` tag shape (rf2-zsm03).
  The trace carries `:exception-data` — the `ex-data` of a thrown machine
  action — under a bare slot the machine-snapshot projection
  (`project-machine-tags`) does NOT cover: the `:event` slot is redacted by
  `project-event-tags` and `:before`/`:after`/`:snapshot` by
  `project-machine-tags`, but `:exception-data` is the developer's
  arbitrary exception payload and could embed the same app secrets the
  machine's `:data` marks gate (a thrown action that puts a token /
  document-id in its `ex-data`).

  Unlike the snapshot slots, `:exception-data` is NOT snapshot-shaped, so
  the machine's `[:data …]`-rooted `:sensitive` paths do not map onto it.
  The conservative, footgun-prevention posture (mirroring how
  `resolve-sub-output-marks` treats a layer-1 sub against any sensitive
  app-db declaration — Spec 015 §Propagation rules): when the machine
  declares ANY `:sensitive` mark, the machine handles secrets, so an
  action's `ex-data` MAY carry them and we cannot prove otherwise. Elide
  the WHOLE `:exception-data` slot to the `:rf/redacted` sentinel before
  the error trace crosses the bus / epoch-capture / AI-MCP egress boundary
  or reaches a log sink. The structural slots (`:machine-id` / `:action-id`
  / `:state-path` / `:reason` / `:exception-message`) stay intact — they
  carry no user value and consumers need them to locate the failure.

  A machine with NO `:sensitive` mark rides `:exception-data` verbatim
  (the seam is precise, not a blanket scrub — symmetric with every other
  per-registration projection). `:large` marks do not apply: the slot is
  a developer-shaped diagnostic, not an app-data path graph.

  The whole-Throwable `:exception` slot is left as-is — a Throwable is not
  a Clojure-walkable collection and consumers extract `:exception-message`
  (the plain string, untouched here) separately; this matches how every
  other `:rf.error/*` trace handles the raw exception object."
  [tags]
  (let [machine-id (:machine-id tags)
        marks      (machine-marks machine-id)]
    (if (and (contains? tags :exception-data)
             marks
             (seq (:sensitive marks)))
      (assoc tags :exception-data privacy/redacted-sentinel :sensitive? true)
      tags)))

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
  carries handler-scope-derived data also carries `:frame` because the
  in-scope handler binds it through the router.

  EP-0002 (rf2-gjq3ow) — the carried frame is read straight off `:tags
  :frame`; there is NO `:rf/default` floor. A trace event attributes to the
  frame it CARRIES, never to a synthesised default. The process-scoped
  per-registration projections (event / fx / cofx / machine marks, keyed by
  `(kind id)`) apply regardless — they need no frame. The frame-QUALIFIED
  projections (`project-db-tags` / `project-view-rendered-tags` /
  `project-sub-tags`, which consult the per-frame elision registry and
  sub-output propagation table) receive that carried `frame-id`; when it is
  nil — a genuinely frameless boot/registration emit, or a malformed event
  that should have carried a stamp — those projections FAIL CLOSED rather
  than borrow another frame's marks (see each fn)."
  [event]
  (if-not (map? event)
    event
    (let [operation (:operation event)
          tags      (:tags event)
          frame-id  (:frame tags)
          tags'     (cond-> tags
                      (and (map? tags) (contains? tags :rf.event/v))
                      (project-event-tags :rf.event/v)

                      ;; Error traces carry the event vector under the
                      ;; bare `:event` slot (HandlerExceptionTags etc.).
                      (and (map? tags) (contains? tags :event))
                      (project-event-tags :event)

                      (and (map? tags) (= :rf.fx/handled operation))
                      (project-fx-tags)

                      (and (map? tags) (contains? tags :rf.event/coeffects))
                      (project-cofx-tags)

                      ;; The t1 / t2 pending-`:db` emits stamp the full
                      ;; app-db under `:rf.event/db`; redact against the
                      ;; frame's elision registry (rf2-6773q).
                      (and (map? tags) (contains? tags :rf.event/db))
                      (project-db-tags frame-id)

                      ;; rf2-rpgq8 — the `:rf.view/rendered` op stamps the
                      ;; view's positional render args/props under
                      ;; `:rf.view/render-args`; elide each arg against the
                      ;; frame's app-db elision registry (same walker as
                      ;; `:rf.event/db`) so sensitive / large user data never
                      ;; reaches a listener or the wire raw.
                      (and (map? tags) (contains? tags :rf.view/render-args))
                      (project-view-rendered-tags frame-id)

                      (and (map? tags) (= :rf.cofx/run operation))
                      (project-cofx-run-tags)

                      (and (map? tags) (= :rf.sub/run operation))
                      (project-sub-tags frame-id)

                      (and (map? tags) (machine-op? operation))
                      (project-machine-tags)

                      ;; rf2-zsm03 — the `:rf.error/machine-action-exception`
                      ;; trace carries the thrown action's `ex-data` under a
                      ;; bare `:exception-data` slot. Its op namespace is
                      ;; `:rf.error/*` (NOT `rf.machine`), so `machine-op?`
                      ;; above does not reach it; redact the slot against the
                      ;; machine's declared `:sensitive` marks here so an
                      ;; action that throws app secrets inside a sensitive
                      ;; machine does not leak them past the egress boundary.
                      (and (map? tags) (contains? tags :exception-data))
                      (project-machine-error-tags))]
      (assoc event :tags tags'))))

;; ---- late-bind hook registration ----------------------------------------
;;
;; The trace ns reads through these hooks; this ns reads through the
;; existing elision-registry. The arrangement avoids load cycles
;; (`re-frame.trace` → `re-frame.marks` would cycle since marks
;; requires elision which requires trace).

(late-bind/set-fn! :marks/project-trace-event project-trace-event)
(late-bind/set-fn! :marks/register-marks!     register-marks!)
(late-bind/set-fn! :marks/union-marks!        union-marks!)
(late-bind/set-fn! :marks/marks-for           marks-for)
(late-bind/set-fn! :marks/declare-machine-schema-marks! declare-machine-schema-marks!)
(late-bind/set-fn! :marks/clear-machine-schema-marks!   clear-machine-schema-marks!)
(late-bind/set-fn! :marks/resolve-sub-output-marks resolve-sub-output-marks)
(late-bind/set-fn! :marks/mark-sub-output!    mark-sub-output!)
(late-bind/set-fn! :marks/clear-marks!        clear-marks!)
(late-bind/set-fn! :marks/clear-sub-output-marks! clear-sub-output-marks!)
(late-bind/set-fn! :marks/add-marks           add-marks)
(late-bind/set-fn! :marks/set-marks           set-marks)
