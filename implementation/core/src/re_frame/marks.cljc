(ns re-frame.marks
  "Data-classification path-marks for sensitive + large values per Spec 015.

  This namespace owns:
    - `add-marks` / `set-marks` — the dedicated registration kinds for
      declaring path-marks against an `app-db` (frame-scoped).
      `add-marks` merges into the existing frame mark-set; `set-marks`
      replaces the frame mark-set wholesale. Both take the same
      `{path mark, ...}` shape — symmetric path-keyed form.
    - Per-registration marks — DERIVED at read time (`marks-for`) from the
      registration metadata `re-frame.registrar` already holds, normalised to
      `{:sensitive [paths] :large [paths] :output-sensitivity <enum> :large? bool}`.
      The author keys (`:sensitive` / `:large` / `:rf.egress/output-sensitivity`
      / `:large?`) are stored by every reg-* path on the registrar entry, so
      `marks-for` re-derives them through the SAME `normalise-marks` validation
      the registration ran — no duplicated imperative side-table (rf2-ehexnw).
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
  are DEMOTED off the public façade — EP-0015 §3, rf2-mngp4o — and their
  `:marks/*` late-bind hooks are GONE (rf2-gjp7t6, they had zero consumers);
  they survive ONLY as test / conformance helpers reached by direct require.
  Durable app-db classification is authored on the frame. The former
  schema→app-db-egress route is gone post-EP-0015 §8.)

  EP-0025 (rf2-398kql) — the machine `:data-schema`→marks classification
  bridge is GONE. `marks-for` no longer unions a schema-sourced side-table;
  it returns ONLY the registrar-derived author marks for `(kind, id)`. Frame-
  declared `:sensitive` / `:large {:app-db …}` paths (`reg-frame`, EP-0015)
  are the SOLE app-db data-classification mechanism. The machine `:data-schema`
  still VALIDATES `:data` (EP-0005's rename + validation), but its per-slot
  `:sensitive?` / `:large?` props no longer classify the machine's durable
  `:data` for trace / SSR egress — that schema→MARKS bridge (the EP-0005
  redaction half) is reversed."
  (:require [re-frame.elision :as elision]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.path :as path]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as adapter]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- per-registration marks: DERIVED from the registrar (rf2-ehexnw) ------
;;
;; A registration's declared marks (`:sensitive` / `:large` /
;; `:rf.egress/output-sensitivity` / `:large?`) are NOT stashed into a second
;; imperative side-table at registration time. Every reg-* path already stores
;; the author meta on its `re-frame.registrar` entry (`registrar/register! kind
;; id (assoc meta … :handler-fn …)`), so `marks-for` DERIVES the marks at read
;; time by running `normalise-marks` over `registrar/handler-meta` — the SAME
;; validation path the registration ran. This is the pattern machine-guards use
;; ("read it back rather than duplicate into a second registrar entry"): the
;; registrar is the single source of truth, snapshot/restored by the test-
;; isolation runtime fixture for free.
;;
;; Marks bind to (kind, id), not to (frame, kind, id) — `registrar/handler-meta`
;; resolves under the same realm / image-generation scope as the original
;; registration (`*registrar*` / `*generation*`). `add-marks` / `set-marks` are
;; the exception — they are frame-scoped and write into the per-frame elision
;; registry.

;; ---- machine :data-schema marks: REMOVED (EP-0025, rf2-398kql) -----------
;;
;; The schema-sourced `machine-id->schema-marks` side-table is GONE. It was the
;; read-time-union partner of the machine `:data-schema`→marks redaction bridge
;; (EP-0005, rf2-w46fpt / rf2-qpibk0): `reg-machine` extracted each `:sensitive?`
;; / `:large?` per-`:data`-slot prop, rooted it under `[:data …]`, and recorded
;; it here, where `marks-for :event <machine-id>` unioned it with the registrar-
;; derived author marks at read time. EP-0025 makes frame-declared `:sensitive`
;; / `:large {:app-db …}` paths (`reg-frame`, EP-0015) the SOLE app-db data-
;; classification mechanism and KILLS the schema-field classification axis — so
;; the table, its `declare-machine-schema-marks!` / `clear-machine-schema-marks!`
;; writers, and `merge-schema-marks` (the read-time union) all die with it.
;; `marks-for` now returns ONLY the registrar-derived author marks (below).
;; (`:data-schema` VALIDATION is untouched — only the schema→MARKS bridge dies.)

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
;; We now REJECT LOUDLY at the ingestion boundary (`validate-marks!`, called
;; from each reg-* path AFTER the registrar write — rf2-ehexnw), mirroring the
;; flows-side fix (rf2-cgk0wb, which rejects malformed `reg-flow` classification
;; metadata with `:rf.error/flow-bad-marks` before any state mutates). The
;; validation runs the SAME `normalise-marks` `marks-for` re-runs at read time,
;; so a malformed declaration is caught at registration (fail-loud) AND can
;; never be re-derived into a mark — but nothing is stashed: the marks are
;; derived from the registrar meta, not duplicated into a side-table. Per the
;; k0ew8n
;; warn-vs-reject principle: there are ZERO legitimate non-vector entries and a
;; correct spelling always exists (`[[:token]]`), so REJECT, not warn. The
;; ex-info carries the canonical thrown-error shape (Spec 009 §The thrown-error
;; shape) and names the offending key + value/entry so the author can fix it
;; without a stack-trace dig.

;; A mark-path SEGMENT is admitted by the shared EP-0012 segment domain
;; (`re-frame.path/segment?` — Conventions §The `:rf/path` algebra §Segment
;; domain), NOT a private re-enumeration. Redaction-mark paths are
;; `:rf/path` consumers (Spec 015 §6 points classification path maps at the
;; shared `:rf/path` identity), so a mark path inherits the one segment
;; algebra every other path-shaped fact uses — keyword / string / symbol /
;; boolean / integer / UUID / instant / nil. The prior private
;; `valid-mark-element?` omitted UUID, instant, and nil, narrowing the
;; domain with no recorded policy (rf2-94o54l.2 finding 2, rf2-94o54l.6):
;; a `:sensitive [[:user #uuid "…"]]` mark — a perfectly valid `get-in`
;; path against a uuid-keyed map — was silently REJECTED. Per Conventions
;; §The `:rf/path` algebra a subsystem MUST NOT keep private ad hoc path
;; logic once the shared helper exists; marks compose ON the shared domain.

(defn- valid-mark-subpath?
  "A `:sensitive` / `:large` entry is a vector of EP-0012 path segments
  (`re-frame.path/segment?` — the shared segment domain). Unlike a flow
  `:inputs` path the EMPTY vector `[]` is legal — it marks the whole value
  (the `[[]]` convention). A composite element (a nested vector / map) is
  not a concrete segment and signals a caller bug (e.g. the nested-vector
  path `[[:a [:b]]]`); `path/segment?` rejects it without re-enumerating
  the host-type discrimination here."
  [x]
  (and (vector? x) (every? path/segment? x)))

(defn- marks-error
  "Build the malformed-marks ex-info with the canonical thrown-error shape
  (per Spec 009 §The thrown-error shape). Mirrors `re-frame.flows.registry`'s
  `flow-error`: `:rf.error/bad-marks` is the message AND the `:rf.error/id`
  discriminator; `:bad-key` names the offending classification key; `extras`
  merges the offending-value slot (`:bad-value` for a non-vector whole,
  `:bad-entries` for malformed entries)."
  [mark-key reason extras]
  (error/thrown-ex-info
    :rf.error/bad-marks
    'rf/reg-marks
    reason
    {:recovery :fix-registration
     :extra    (merge {:bad-key mark-key} extras)}))

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
                              "EP-0012 path segments (keyword / string / "
                              "symbol / boolean / integer / UUID / instant / "
                              "nil); [] marks the whole value")
                         {:bad-entries (vec (remove valid-mark-subpath? paths))}))

     :else
     paths)))

;; ---- derived-output sensitivity claim (EP-0015 issue 9) ------------------
;;
;; A subscription / flow can copy, summarise, hash, or reshape a sensitive
;; input into a NEW value. The author declares the resulting output's
;; sensitivity with the closed `:rf.egress/output-sensitivity` enum (Spec 015
;; §Derived sensitivity) — NOT by overloading the boolean `:sensitive?`
;; declassify spelling (that overload is REJECTED, Spec 015:425, because
;; `:sensitive` already names a path COLLECTION at the registration layer).
;;
;;   :rf.egress/inherit    — default; the output inherits sensitivity from
;;                           its inputs (propagation, fail-closed).
;;   :rf.egress/sensitive  — force-mark the output sensitive even from
;;                           public inputs.
;;   :rf.egress/public     — DECLASSIFY: the output is safe to surface
;;                           despite sensitive inputs. The declassification
;;                           analogue of `:rf.scope/global` — Xray enumerates
;;                           every `:public` claim as a standing audit
;;                           surface (see `public-declassification-claims`).
;;
;; Fail-closed: an unknown value THROWS (the enum is closed — a typo never
;; silently falls through to a permissive inherit), and `:inherit` is the
;; safe default when the key is absent. The claim is stored verbatim in the
;; per-(kind, id) marks table under `:output-sensitivity`; `resolve-sub-output-marks`
;; (subs) and the flows registry resolver consult it instead of the removed
;; `:sensitive?` boolean override.

(def ^:const output-sensitivity-values
  "The closed value set of the `:rf.egress/output-sensitivity` derived-output
  declassification claim (EP-0015 issue 9; Spec 015 §Derived sensitivity)."
  #{:rf.egress/inherit
    :rf.egress/sensitive
    :rf.egress/public})

(defn- coerce-output-sensitivity
  "Validate a `:rf.egress/output-sensitivity` claim against the closed enum
  and return it verbatim. Fail-closed: an unknown value THROWS
  `:rf.error/bad-marks` (the enum is closed — a typo is a loud error, never a
  silent permissive fall-through). `nil` (key absent) returns `:rf.egress/inherit`,
  the safe default."
  [v]
  (cond
    (nil? v)                          :rf.egress/inherit
    (contains? output-sensitivity-values v) v
    :else
    (throw (marks-error :rf.egress/output-sensitivity
                        (str ":rf.egress/output-sensitivity must be one of "
                             (pr-str output-sensitivity-values)
                             " — :rf.egress/inherit (default) inherits from inputs, "
                             ":rf.egress/sensitive force-marks, :rf.egress/public declassifies")
                        {:bad-value v
                         :valid     output-sensitivity-values}))))

(defn- reject-sensitive-boolean-overload!
  "REJECT the boolean `:sensitive?` declassify/force spelling on a derived
  output (`:sub` kind — Spec 015:425): `:sensitive` already names a path
  collection at the registration layer, so overloading `:sensitive?` to a
  whole-output boolean is forbidden. Derived-output sensitivity is declared
  with the `:rf.egress/output-sensitivity` enum instead. Throws with a recovery
  hint naming the correct spelling.

  SCOPED to derived outputs (`:sub`): for non-derived kinds (`:event` / `:fx`
  / `:cofx` / machine `:event` entries), a top-level `:sensitive?` was always a
  stored-but-never-consulted no-op vestige (it is meaningful ONLY as a Malli
  schema-slot prop, never as registration meta), so this rejection does not
  fire there — those kinds keep their prior permissive behaviour and the key is
  simply dropped by `normalise-marks`. (`:large?` is NOT rejected on any kind —
  it remains the whole-output size override; size has no declassification
  analogue and is out of EP-0015 issue 9's scope.)

  No-op when `kind` is not `:sub` or the meta carries no `:sensitive?` key."
  [kind meta]
  (when (and (= :sub kind) (contains? meta :sensitive?))
    (throw (marks-error :sensitive?
                        (str "the boolean :sensitive? declassify/force spelling is "
                             "rejected on a derived (sub) output (Spec 015) — declare "
                             "derived-output sensitivity with "
                             ":rf.egress/output-sensitivity, whose closed value set is "
                             (pr-str output-sensitivity-values)
                             " (:rf.egress/public to declassify, :rf.egress/sensitive "
                             "to force-mark, :rf.egress/inherit — the default — to "
                             "inherit from inputs)")
                        {:bad-value (:sensitive? meta)
                         :use       :rf.egress/output-sensitivity}))))

(defn- normalise-marks
  "Extract the mark-relevant subset of a registration meta-map and
  normalise into the canonical shape this namespace consults:

    {:sensitive           [vector-of-paths]
     :large               [vector-of-paths]
     :output-sensitivity  <closed enum>  ;; derived-output claim (subs/flows)
     :large?              <bool-or-nil>}  ;; whole-output size override (subs/flows)

  Derived-output sensitivity is declared with the closed
  `:rf.egress/output-sensitivity` enum (EP-0015 issue 9; Spec 015 §Derived
  sensitivity). On the derived (`:sub`) path the legacy boolean `:sensitive?`
  declassify/force spelling is REJECTED (Spec 015:425) — a `:sensitive?` key
  throws with a recovery hint naming the enum; on non-derived kinds it is the
  prior stored-but-ignored no-op and is silently dropped. An explicit
  `:rf.egress/output-sensitivity` is validated against the closed enum
  (fail-closed: an unknown value throws); the absent default
  `:rf.egress/inherit` is NOT stored (the resolver treats a missing claim as
  inherit, so an empty marks entry stays empty).

  `kind` scopes the `:sensitive?` rejection to derived outputs (see
  `reject-sensitive-boolean-overload!`). Returns `nil` when the meta-map
  carries no mark-relevant keys — callers branch on the nil to avoid stashing
  empty tables."
  [kind meta]
  (reject-sensitive-boolean-overload! kind meta)
  (let [;; The explicit (non-default) output-sensitivity claim, or nil when
        ;; the key is absent OR is the no-op `:rf.egress/inherit` default —
        ;; either way the marks entry omits the slot and the resolver
        ;; inherits. An unknown value throws (fail-closed).
        os (when (contains? meta :rf.egress/output-sensitivity)
             (let [v (coerce-output-sensitivity (:rf.egress/output-sensitivity meta))]
               (when (not= :rf.egress/inherit v) v)))]
    (when (or (contains? meta :sensitive)
              (contains? meta :large)
              (some? os)
              (contains? meta :large?))
      (cond-> {}
        (contains? meta :sensitive) (assoc :sensitive (coerce-paths (:sensitive meta) :sensitive))
        (contains? meta :large)     (assoc :large     (coerce-paths (:large meta) :large))
        (some? os)                  (assoc :output-sensitivity os)
        (contains? meta :large?)    (assoc :large?    (boolean (:large? meta)))))))

(defn validate-marks!
  "Validate a registration's mark declaration at the reg-* boundary, FAIL-LOUD
  (rf2-ehexnw / rf2-y7l5t5). Returns nil. No-op (no throw) when `meta` carries
  no mark-relevant keys or carries only well-formed ones.

  Called from each reg-* path BEFORE the underlying registrar write. It runs the
  SAME `normalise-marks` `marks-for` re-runs at read time and DISCARDS the
  result — its only job is the throw-side-effect: a malformed `:sensitive` /
  `:large` / `:rf.egress/output-sensitivity` declaration (or a `:sensitive?`
  overload on a `:sub`) raises `:rf.error/bad-marks` at registration rather
  than silently mis-deriving (or lazily throwing) at the first emit. NOTHING is
  stashed — the marks are derived from the registrar meta by `marks-for`, not
  duplicated into a process-scoped side-table. Validating BEFORE the registrar
  write means a malformed registration never LANDS, so a stored meta `marks-for`
  re-derives at read time is always well-formed (rf2-ehexnw)."
  [kind meta]
  (normalise-marks kind meta)
  nil)

;; NOTE: `union-marks!` is GONE (rf2-ehexnw). It was the additive-merge analogue
;; of the deleted `kind->id->marks` side-table and had NO production caller. Its
;; remaining read-time-union consumer — `merge-schema-marks` (the machine
;; `:data-schema`→marks bridge) — is itself GONE (EP-0025, rf2-398kql): the
;; schema-field classification axis is killed in favour of frame-declared paths
;; as the sole app-db mechanism. The three path/flag union helpers it shared
;; (`union-path-vecs` / `union-whole-output-flag` / `union-output-sensitivity`)
;; had no other caller and are removed with it — `add-marks` / `set-marks` use
;; `assoc-paths` to layer frame-scoped `:source :marks` declarations, not a
;; declaration-level union.

(defn marks-for
  "Return the mark declaration for `(kind, id)`, or nil — DERIVED at read time
  from `registrar/handler-meta` (rf2-ehexnw), NOT a duplicated side-table.

  The returned shape is `{:sensitive [paths] :large [paths]
  :output-sensitivity <enum> :large? bool}` — slots are present only when the
  registration declared them. `:output-sensitivity` is the derived-output
  declassification claim (`:rf.egress/sensitive` / `:rf.egress/public`; the
  `:rf.egress/inherit` default is omitted).

  `normalise-marks` runs over the registrar's stored meta (the SAME validation
  `validate-marks!` ran at registration, so a clean stored meta never throws;
  an unknown id returns nil meta → nil marks).

  EP-0025 (rf2-398kql): the prior machine `:data-schema`→marks union is GONE.
  An `:event`-kind id that names a machine carrying a `:data-schema` no longer
  has its schema's `:sensitive?` / `:large?` per-slot props folded in here —
  schema-field classification is killed; frame-declared `:sensitive` / `:large
  {:app-db …}` paths are the sole app-db mechanism. `marks-for` returns the
  registrar-derived author marks for EVERY kind uniformly (no `:event` special
  case)."
  [kind id]
  (normalise-marks kind (registrar/handler-meta kind id)))

(defn public-declassification-claims
  "Enumerate every registered derived output that carries an explicit
  `:rf.egress/output-sensitivity :rf.egress/public` declassification claim —
  the standing AUDIT surface (EP-0015 issue 9; Spec 015 §Derived sensitivity).
  A `:public` claim is the declassification analogue of `:rf.scope/global`:
  this list lets a reviewer see every place an author asserted \"this
  derived-from-sensitive value is safe.\"

  Returns a vector of `{:kind <kind> :id <id>}` maps (e.g.
  `{:kind :sub :id :auth/token-prefix}`), sorted by `(kind, id)` string for a
  stable audit ordering. Pure read DERIVED from the registrar (rf2-ehexnw) —
  walks every mark-carrying kind (`:event :sub :fx :cofx`) via
  `registrar/registrations`, re-derives each entry's marks through
  `normalise-marks`, and collects the `:rf.egress/public` ones. The Xray
  `:public`-claim panel consumes it, mirroring how `global-scope-audit`
  consumes the resources registry. Empty when no output is declassified."
  []
  (->> [:event :sub :fx :cofx]
       (mapcat (fn [kind]
                 (keep (fn [[id meta]]
                         (when (= :rf.egress/public
                                  (:output-sensitivity (normalise-marks kind meta)))
                           {:kind kind :id id}))
                       (registrar/registrations kind))))
       (sort-by (juxt (comp str :kind) (comp str :id)))
       vec))

;; `declare-machine-schema-marks!` / `clear-machine-schema-marks!` are GONE
;; (EP-0025, rf2-398kql) — they wrote / cleared the deleted schema-sourced
;; `machine-id->schema-marks` table that backed the machine `:data-schema`→marks
;; redaction bridge (EP-0005). With the schema-field classification axis killed
;; in favour of frame-declared paths, there is no schema-sourced side-table for
;; a machine `:data` slot's `:sensitive?` / `:large?` prop to populate.

(defn clear-marks!
  "No-op retained for test-isolation directory symmetry — production code never
  calls this. Returns nil.

  EP-0025 (rf2-398kql): the only mutable marks state this ns once owned — the
  schema-sourced `machine-id->schema-marks` table — is GONE. The author-sourced
  marks now live in the registrar (rf2-ehexnw), which the test-isolation runtime
  fixture already snapshot/restores via `re-frame.test-support/restore-registrar!`;
  the per-frame app-db marks live in the frame's runtime-db elision registry,
  reset by the same fixture's frame teardown. There is no separate side-table
  left to clear here."
  []
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
;;
;; KEPT AS-IS, NOT folded into `reg-frame` (rf2-jk4vky assessment, the
;; rf2-ehexnw follow-on). The Codex finding named these the "internal twins of
;; the removed public marks surface" and asked whether the only app-db
;; mark-authoring path should be `reg-frame` `:sensitive` / `:large`. It should
;; NOT — they are a genuinely DISTINCT, load-bearing declaration source, not a
;; redundant twin:
;;   1. Different source, deliberately unioned. `reg-frame` writes `:source
;;      :frame` declarations once at registration time (frame-class
;;      `install!`, which writes the elision slot DIRECTLY — it does not call
;;      these fns). `add-marks` / `set-marks` write the SEPARATE `:source
;;      :marks` entries imperatively/post-registration. The two sources are
;;      preserved side-by-side and unioned at `marks-for` read time; the
;;      conformance fixtures specifically pin that union. Folding them away
;;      would collapse a distinction the model relies on.
;;   2. No registrar-derivable equivalent. The rf2-ehexnw derivation that
;;      retired the imperative kind->id->marks side-table worked because those
;;      marks were per-(kind,id) registration metadata, READABLE BACK from the
;;      registrar. These are per-FRAME app-db PATH marks keyed by frame-id —
;;      there is no registration to derive them from, so the "read it back from
;;      the registrar" pattern does not apply.
;;   3. Load-bearing test/conformance authoring path. ~40 call sites across
;;      `marks_test`, `frame_classification_cljs_test`, `flows_output_marks_
;;      test`, and the `conformance_test` / `conformance_corpus_cljs_test`
;;      harnesses (which drive `:add-marks` / `:set-marks` EDN fixture ops).
;;      Folding into `reg-frame` would delete the imperative authoring path
;;      these depend on and balloon test churn — with NO data-over-functions
;;      win (the kind->id->marks fold's payoff was eliminating a redundant
;;      imperative MIRROR of derivable data; here the data is not derivable).
;; They are already off the public façade (item 1, EP-0015 §3) and `^:no-doc`
;; / internal — the assessment's accept criterion ("kept as internal" or
;; "folded") lands on kept-internal.

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

(def ^:private app-db-mark-values
  "The closed value set of an `add-marks` / `set-marks` `{path mark}` map's
  mark slot. A mark is `:sensitive` (redact the value) or `:large` (emit a
  size marker) — nothing else."
  #{:sensitive :large})

(defn- split-by-mark
  "Partition `{path mark}` map into `[sensitive-paths large-paths]`, with
  each path routed through `re-frame.path/normalize-concrete` — the same
  VALIDATED concrete boundary frame classification and resource scope use
  (EP-0012, rf2-w9x5fv). An `add-marks` / `set-marks` path is a CONCRETE
  app-db path, so it normalizes to its canonical vector form AND fails
  closed (`:rf.error/bad-path`) on any host-object / composite segment that
  could never match a real `get-in` step.

  FAIL-CLOSED on the mark VALUE too (rf2-94o54l.6): a mark outside the
  closed set `#{:sensitive :large}` is REJECTED LOUDLY with
  `:rf.error/bad-marks` naming the offending path + mark — NOT silently
  dropped. The prior silent drop was a privacy footgun (Conventions §No
  silent swallow, §738 — recognized-but-unhonored input MUST signal): a
  typoed mark (`{[:user :ssn] :sensitivee}`) made the author believe a
  path was redacted while `split-by-mark` quietly discarded it, the same
  armed-trap shape the `:sensitive` / `:large` declaration validator
  (`coerce-paths`) already rejects. The mark enum is closed, so a typo is
  a loud error, never a permissive no-op."
  [path->mark]
  (reduce-kv (fn [[s l] path mark]
               (let [p (path/normalize-concrete path)]
                 (case mark
                   :sensitive [(conj s p) l]
                   :large     [s (conj l p)]
                   (error/throw-error!
                     :rf.error/bad-marks
                     'rf/add-marks
                     (str "an app-db mark value must be one of "
                          (pr-str app-db-mark-values)
                          " — :sensitive redacts the value, :large "
                          "emits a size marker")
                     {:recovery :fix-mark-value
                      :extra    {:bad-path path
                                 :bad-mark mark
                                 :valid    app-db-mark-values}}))))
             [[] []]
             path->mark))

(defn- assoc-paths
  "Add `paths` to `existing` declaration map with `{:source :marks}`."
  [existing paths]
  (reduce (fn [acc path]
            (assoc acc (vec path) {:source :marks}))
          (or existing {})
          paths))

(defn- write-elision-slots
  "Rewrite the two app-db elision-registry declaration slots on `base` from the
  resolved `new-s` (`:sensitive-declarations`) and `new-l` (`:declarations`)
  maps: assoc the slot when its map is non-empty, dissoc it when empty so an
  emptied slot vanishes rather than lingering as `{}`. The single helper behind
  `add-marks` / `set-marks`, which hand-inlined this same cond-> (rf2-mo9ekx)."
  [base new-s new-l]
  (cond-> base
    (seq new-s)    (assoc :sensitive-declarations new-s)
    (empty? new-s) (dissoc :sensitive-declarations)
    (seq new-l)    (assoc :declarations new-l)
    (empty? new-l) (dissoc :declarations)))

(defn ^:no-doc add-marks
  "TEST / CONFORMANCE-ONLY (rf2-gjp7t6). Additively merge path-marks into the
  `app-db` mark-set of `frame-id`. Per Spec 015 §App-db marks (per frame).

  `path->mark` is a map from `get-in`-shaped path vectors to mark
  keywords (`:sensitive` or `:large`). Paths supplied here MERGE into
  the frame's existing marks — paths NOT mentioned keep their prior
  state. Repeat calls accumulate. Each path is normalized to its
  canonical EP-0012 vector form, and a mark value outside the closed set
  `#{:sensitive :large}` FAILS CLOSED with `:rf.error/bad-marks` (a typoed
  mark is never silently dropped — rf2-94o54l.6).

      (marks/add-marks :rf/default
        {[:user :ssn]   :sensitive
         [:auth :token] :sensitive
         [:docs :csv]   :large})

  Returns `frame-id`. Pure declaration — does NOT mutate `app-db`,
  does NOT install an interceptor, does NOT change any handler's view
  of the data. The declaration only feeds the mark-lookup table the
  observation surfaces (trace bus, Xray, MCP, third-party log sinks)
  consult at emission time.

  NOTE: NOT a public façade fn and NOT reachable through a late-bind hook
  (EP-0015 §3, rf2-mngp4o removed the imperative façade export; rf2-gjp7t6
  removed the `:marks/add-marks` hook — it had zero consumers). Frame-owned
  `:sensitive` / `:large` classification is the public authoring surface;
  this fn survives ONLY as a test / conformance helper, reached by direct
  require from the marks tests + the conformance corpus harness.

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
          (write-elision-slots (or reg {}) new-s new-l)))))
  frame-id)

(defn ^:no-doc set-marks
  "TEST / CONFORMANCE-ONLY (rf2-gjp7t6). Replace the `app-db` mark-set of
  `frame-id` with `path->mark`. Per Spec 015 §App-db marks (per frame).

  `path->mark` is a map from `get-in`-shaped path vectors to mark
  keywords (`:sensitive` or `:large`). Paths supplied here REPLACE the
  frame's prior marks set wholesale — paths NOT mentioned are CLEARED.
  Each path is normalized to its canonical EP-0012 vector form, and a
  mark value outside the closed set `#{:sensitive :large}` FAILS CLOSED
  with `:rf.error/bad-marks` (rf2-94o54l.6).

      (marks/set-marks :rf/default
        {[:user :ssn]   :sensitive
         [:auth :token] :sensitive
         [:docs :csv]   :large})

  Returns `frame-id`. Pure declaration — does NOT mutate `app-db`,
  does NOT install an interceptor, does NOT change any handler's view
  of the data. The declaration only feeds the mark-lookup table the
  observation surfaces (trace bus, Xray, MCP, third-party log sinks)
  consult at emission time.

  NOTE: NOT a public façade fn and NOT reachable through a late-bind hook
  (EP-0015 §3, rf2-mngp4o removed the imperative façade export; rf2-gjp7t6
  removed the `:marks/set-marks` hook — it had zero consumers). Frame-owned
  `:sensitive` / `:large` classification is the public authoring surface;
  this fn survives ONLY as a test / conformance helper, reached by direct
  require from the marks tests + the conformance corpus harness.

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
          (write-elision-slots (or reg {}) new-s new-l)))))
  frame-id)

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

(defn large-marker
  "Build the `:rf.size/large-elided` marker for value `v` at `path`.
  Delegates to `re-frame.elision/->marker` with `{:reason :marks}` so
  the marker shape is built in ONE place — this ns already requires
  `re-frame.elision`, so there is no extra dependency (the stale
  \"carries no dependency on elision's privates\" rationale is gone,
  rf2-ih437c). The `:reason :marks` tag lets consumers discriminate
  per-registration marks from schema-driven marks.

  Public because the off-box epoch egress projector
  (`re-frame.epoch.tool-pair/projected-record`, rf2-at60h) reuses it to
  substitute the marker for a whole-output `:large?`-stamped sub's
  `:value` / `:prev-value` in the structured `:sub-runs` row — the same
  `:reason :marks` provenance the whole-output propagation table sets,
  built in ONE place rather than re-inlined a third time."
  [v path]
  (elision/->marker v path {:reason :marks}))

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

(defn- mark-paths
  "Destructure a canonical marks map into its `[sensitive-paths large-paths]`
  pair, each defaulting to `[]` when the slot is absent — the shared shape
  every per-registration projector hand-inlined as `(or (:sensitive marks) [])`
  / `(or (:large marks) [])` before passing the pair to `redact-with-paths` /
  `redact-event-vec` (rf2-tqxqm7)."
  [marks]
  [(or (:sensitive marks) []) (or (:large marks) [])])

(defn- redact-by-marks
  "Redact `v` against the canonical `marks` map — `(redact-with-paths v sens
  large)` with the `[]`-defaulted `[sens large]` pair from `mark-paths`. The
  shared one-shot redact the single-value projectors reach through (fx args,
  cofx map values, the standalone cofx produced value); callers that redact
  more than one slot from the same marks (sub value + prev-value, the machine
  multi-slot walk) destructure `mark-paths` directly."
  [v marks]
  (let [[sens large] (mark-paths marks)]
    (redact-with-paths v sens large)))

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

(defn- marks-when
  "Resolve the marks declared by the `kind` handler registered under `id`,
  guarding a nil `id` (no lookup, no marks). Returns the canonical
  `{:sensitive [paths] :large [paths] …}` shape, or nil when `id` is nil or
  the handler declared no marks. The single per-(kind, id) lookup wrapper the
  trace-tag projectors reach through — `(marks-when :event event-id)`,
  `(marks-when :fx fx-id)`, `(marks-when :cofx cofx-id)`, `(marks-when :sub
  sub-id)`, and `(marks-when :event machine-id)` (a machine IS an `:event`
  handler) replaced the five byte-identical `<kind>-marks` wrappers that
  differed only in the kind keyword (rf2-8mvd28)."
  [kind id]
  (when id
    (marks-for kind id)))

(defn redact-event-by-registration
  "Apply the REGISTRATION-OWNED `:sensitive` / `:large` marks declared by the
  event handler registered under `(first event)` to the event vector `event`,
  returning the vector with declared arg-map paths redacted. A no-op (returns
  `event` unchanged) when `event` is not a `[event-id arg-map …]` vector or the
  handler declared no marks.

  ALWAYS-ON (NOT gated on `interop/debug-enabled?`) — the registration marks
  table is populated at registration time in production as well as dev (only the
  emit-time TRACE projection is dev-gated), and this fn is the production egress
  consumer (rf2-qe6v1u): EP-0015 makes event args REGISTRATION-OWNED transient
  payloads, so a handler registered with `{:sensitive [[:password]]}` must have
  that path redacted on the frame-owned `:observability :errors` sink even when
  the frame declares no matching `:sensitive {:app-db …}` classification — the
  event-shaped error slot is projected through the EVENT registration's marks,
  not (only) the frame's app-db policy. Reuses `redact-event-vec`, the exact
  arg-map-rooted walk the dev trace path uses, so the production sink and the
  dev trace redact event args identically.

  Published via the `:marks/redact-event-by-registration` late-bind hook;
  `re-frame.projection` consumes it for the `:rf.observe/error` / handled-event
  `:event` slot."
  [event]
  (if-let [marks (marks-when :event (when (vector? event) (first event)))]
    (let [[sens large] (mark-paths marks)]
      (redact-event-vec event sens large))
    event))

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

;; ONE axis-keyed propagation table (rf2-2s595w): `{<axis> {<frame-id>
;; {<sub-id> true}}}` where `<axis>` is `:sensitive` or `:large`. The prior
;; fully-parallel twin atoms (`frame->sub-id->sensitive?` /
;; `frame->sub-id->large?`) carried byte-identical assoc-in / dissoc structure
;; per axis; collapsing into one defonce atom keyed by axis removes the twin
;; while preserving the `[frame-id sub-id]` nesting under each axis (so a
;; per-frame clear still drops both axes' entries for the frame). Empty in
;; production — only `mark-sub-output!` writes it, and the sub-cache invokes
;; that only under `debug-enabled?`.
(defonce ^:private frame->sub-id->marks
  (atom {}))

(defn- swap-axis-flag
  "Fold a single `axis` (`:sensitive` / `:large`) flag for `[frame-id sub-id]`
  into the propagation map `m`: set the leaf `true` when `flag?`, else dissoc
  the sub-id from the axis's frame entry. The shared per-axis update the twin
  atoms each hand-inlined."
  [m axis frame-id sub-id flag?]
  (if flag?
    (assoc-in m [axis frame-id sub-id] true)
    (update-in m [axis frame-id] dissoc sub-id)))

(defn mark-sub-output!
  "Record the resolved sensitive/large state of a sub's most recent
  output. Called by the sub-cache after `compute-and-cache!` resolves
  the value. The flags fold into the propagation table; downstream
  emit sites read via `sub-output-sensitive?` / `sub-output-large?`.

  INVARIANT: the sub-cache only invokes this under `debug-enabled?`, so the
  propagation table (`frame->sub-id->marks`) is EMPTY in production. Any
  consumer reading it MUST be dev-gated too — an always-on egress consumer
  would read empty and fail open."
  [frame-id sub-id sensitive? large?]
  (swap! frame->sub-id->marks
         (fn [m]
           (-> m
               (swap-axis-flag :sensitive frame-id sub-id sensitive?)
               (swap-axis-flag :large     frame-id sub-id large?))))
  nil)

(defn sub-output-sensitive?
  [frame-id sub-id]
  (true? (get-in @frame->sub-id->marks [:sensitive frame-id sub-id])))

(defn sub-output-large?
  [frame-id sub-id]
  (true? (get-in @frame->sub-id->marks [:large frame-id sub-id])))

(defn clear-sub-output-marks!
  ([] (reset! frame->sub-id->marks {})
      nil)
  ([frame-id] (swap! frame->sub-id->marks
                     (fn [m]
                       (-> m
                           (update :sensitive dissoc frame-id)
                           (update :large dissoc frame-id))))
              nil))

(defn- frame-elision-decls?
  "True when `frame-id`'s runtime-db elision registry carries any declaration
  under `decl-key` (`:sensitive-declarations` or `:declarations`). The single
  axis-parameterized read behind `resolve-sub-output-marks`'s layer-1 footgun
  check (rf2-hkly6h) — the two near-identical container-read blocks differed
  only in this sub-key.

  EP-0001 (rf2-vzld77): the elision registry is durable framework state in the
  frame's runtime-db partition at `[:rf.runtime/elision …]` (Conventions
  §Reserved runtime-db keys), so the layer-1 footgun check reads the runtime-db
  projection, not app-db."
  [frame-id decl-key]
  (let [container (frame/runtime-db-container frame-id)
        rt        (when container (adapter/read-container container))
        decls     (get-in rt [:rf.runtime/elision decl-key])]
    (boolean (seq decls))))

(defn resolve-sub-output-marks
  "Compute the sensitive/large flags that should be stamped onto a sub's
  output, given the sub's registered marks + input-signals' propagation
  state + a layer-1 sub's path overlap with the frame's app-db
  sensitive declarations.

  Sensitivity resolution per Spec 015 §Derived sensitivity — driven by the
  closed `:rf.egress/output-sensitivity` declassification claim (EP-0015
  issue 9), NOT the rejected boolean `:sensitive?` overload (removed):
    1. `:rf.egress/sensitive` forces sensitive (even from public inputs)
    2. `:rf.egress/public`    DECLASSIFIES (overrides propagation)
    3. `:rf.egress/inherit` (default, claim absent): propagate — if ANY
       input-signal's resolved sub-output is sensitive, OR if the sub is
       layer-1 and any sensitive app-db path was declared, mark sensitive
  Size still uses the `:large?` whole-output override (size has no
  declassification analogue — EP-0015 issue 9 is sensitivity-only).

  Returns `[sensitive? large?]`. INVARIANT: the input-signal propagation reads
  go through `sub-output-sensitive?` / `sub-output-large?`, whose table is only
  populated under `debug-enabled?` (see `mark-sub-output!`) — so this resolver,
  and any consumer of its result, MUST be dev-gated; in production the table is
  empty and propagation would silently fail open."
  [frame-id sub-id input-signals layer-1?]
  (let [marks       (marks-when :sub sub-id)
        output-sens (:output-sensitivity marks)
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
        ;; not security-grade taint. The two registry reads are the same
        ;; axis-parameterized `frame-elision-decls?` differing only in the
        ;; declaration sub-key (rf2-hkly6h).
        any-sens?   (when layer-1?
                      (frame-elision-decls? frame-id :sensitive-declarations))
        any-large?  (when layer-1?
                      (frame-elision-decls? frame-id :declarations))
        sensitive?  (cond
                      (= :rf.egress/sensitive output-sens) true
                      (= :rf.egress/public output-sens)    false
                      :else                                (or input-s? (boolean any-sens?)))
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
        marks    (marks-when :event event-id)]
    (if-not marks
      tags
      (let [[sens large] (mark-paths marks)
            redacted (redact-event-vec event sens large)]
        (assoc tags slot redacted)))))

(defn- project-fx-tags
  "Walk `:rf.fx/handled` tag shape: `:rf.fx/id` carries the fx keyword and
  `:rf.fx/args` carries the args value. Marks come from the fx handler's
  registration."
  [tags]
  (let [fx-id (:rf.fx/id tags)
        marks (marks-when :fx fx-id)]
    (if-not marks
      tags
      (assoc tags :rf.fx/args (redact-by-marks (:rf.fx/args tags) marks)))))

(defn- project-cofx-map-slot
  "Walk a `{cofx-id value}` map carried under `slot` in `tags`, redacting each
  value against its cofx-id's registered `:sensitive` / `:large` marks.
  Pass-through (returns `tags` unchanged) when the slot is not a map, and a
  cofx-id with no declared marks rides its value verbatim. Re-assocs the
  walked map back under the SAME `slot`.

  The one parameterized walk behind both cofx map-slot trace shapes
  (rf2-ew7uy2) — they were byte-identical modulo the slot keyword:

    - `:rf.event/coeffects` — the injected coeffects map (e.g.
      `:rf.event/dispatched`, `:rf.event/run-end` — rf2-9dk9y).
    - `:rf.event/cofx` — the post-generation flat `:rf.cofx` replay token the
      `:rf.event/run-start` trace carries (rf2-1xdotm): a one-fact-per-owner-
      qualified-key map (`:rf/time-ms`, generated recordable facts, supplied
      facts). The framework `:rf/time-ms` fact carries no marks and rides
      verbatim; a declared-sensitive recordable fact never surfaces raw (the
      epoch record's `:rf.cofx` replay slot reads off this redacted shape)."
  [tags slot]
  (let [cofx-map (get tags slot)]
    (if-not (map? cofx-map)
      tags
      (let [walked (reduce-kv
                     (fn [acc cofx-id v]
                       (let [marks (marks-when :cofx cofx-id)]
                         (if-not marks
                           (assoc acc cofx-id v)
                           (assoc acc cofx-id (redact-by-marks v marks)))))
                     (empty cofx-map)
                     cofx-map)]
        (assoc tags slot walked)))))

(defn- project-cofx-run-tags
  "Walk the produced-value op shape shared by `:rf.cofx/run` (ambient
  supplier, rf2-hhh92) and `:rf.cofx/generated` (slice-B.7 recordable
  generation, rf2-ygpac8): `:rf.cofx/id` carries the cofx keyword and
  `:rf.cofx/value` carries the PRODUCED value — the coeffect that egresses
  into `:coeffects` / the generated fact written into the record (rf2-sepqgg;
  the requirement-arg rides the distinct `:rf.cofx/arg`, which this chokepoint
  does not redact). Redact the produced value against the cofx's registered
  marks — mirrors `project-fx-tags` for the standalone-value op (the cofx
  success / generation emit does not ride under `:rf.event/coeffects`)."
  [tags]
  (let [cofx-id (:rf.cofx/id tags)
        marks   (marks-when :cofx cofx-id)]
    (if (or (not marks) (not (contains? tags :rf.cofx/value)))
      tags
      (assoc tags :rf.cofx/value (redact-by-marks (:rf.cofx/value tags) marks)))))

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

      ;; Whole-output propagation wins: stamp at root. `prop-s?` is the
      ;; RESOLVED sensitivity (`resolve-sub-output-marks` already applied the
      ;; `:rf.egress/output-sensitivity` claim — a `:public` declassify
      ;; resolves to false here, a `:sensitive` force / inherited-sensitive to
      ;; true), so no per-marks declassify guard is needed.
      prop-s?
      (cond-> (assoc tags :rf.sub/value privacy/redacted-sentinel :sensitive? true)
        has-prev? (assoc :rf.sub/prev-value privacy/redacted-sentinel))

      :else
      (let [[sens large] (mark-paths marks)
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
        marks      (marks-when :sub sub-id)
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

;; The reserved inner event-id the runtime dispatches into a PARENT machine
;; when one of its `:spawn`-spawned children FAILS (Spec 005 §`:on-error`;
;; re-frame2's spelling of XState v5 `invoke onError`). The dispatched shape is
;; `[:rf.machine.spawn/error <invoke-id> <error>]`; `<error>` (the 3rd element)
;; is CHILD-owned — the child's raw `:output-key` result or an exception
;; envelope carrying `:exception-data`. Inlined here (not a require on the
;; machines artefact, which ships ABOVE core) so the marks chokepoint can
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
  "Summarize the synthetic spawn-error event vector for trace egress
  (rf2-0gdic7 / rf2-lft14p). Keep the STRUCTURAL routing facts — the reserved
  `:rf.machine.spawn/error` id and the `<invoke-id>` (the parent's
  `:spawn`-bearing state path the runtime routes on) — and replace the
  CHILD-owned `<error>` payload (3rd element, plus any further args) with the
  `:rf/redacted` sentinel. The parent's local control flow still reads the RAW
  error off `:event` at runtime (`(nth ev 2)`); this projection runs ONLY at the
  egress chokepoint, so the parent transition / guard / action semantics are
  unchanged — only the off-box trace surface is summarized. Conservative
  fail-closed posture symmetric with `project-machine-error-tags`: a payload we
  cannot classify against the PARENT's marks does not egress raw."
  [event]
  (into [(nth event 0) (nth event 1) privacy/redacted-sentinel]
        ;; drop any extra args past the canonical 3 (none in the current
        ;; shape, but fail-closed if the shape ever grows)
        (repeat (max 0 (- (count event) 3)) privacy/redacted-sentinel)))

(defn- project-spawn-synthetic-payloads
  "Summarize the two CHILD-owned / unclassifiable spawn payloads that egress
  through machine traces, UNCONDITIONALLY (independent of the trace machine's
  declared marks) — rf2-0gdic7 / rf2-lft14p / rf2-mxboxi:

    - `:start` (on `:rf.machine.spawn/spawned`) — the newborn child's start
      args; can hold credentials / large data (the same reason
      `reject-unregistered-spawn!` deliberately carries NO spawn args). Summarized
      to `:rf/redacted`.
    - the synthetic `[:rf.machine.spawn/error <invoke-id> <error>]` event the
      parent receives, wherever it rides a machine trace: under the bare
      `:event` slot (`:rf.machine/event-received` / `:rf.machine/transition` /
      unhandled-event rows) and under `:input :event` (the parent's
      `:rf.machine/guard-evaluated` / `:rf.machine/action-ran` for the
      `:on-error` transition). The 3rd element (the child error payload) is
      summarized; the reserved id + invoke-id survive so the trace stays
      locatable.

  All other event vectors ride untouched (their own marks still apply via
  `project-event-tags`); the summary is precise to the reserved id / `:start`
  slot."
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
;; runtime-db path (e.g. `[:rf.runtime/machines :snapshots :checkout/payment
;; :data :token]`, EP-0025) in its elision registry. The machine trace
;; `:before` / `:after` / `:snapshot` slots carry the SNAPSHOT value (rooted at
;; the snapshot, not the runtime-db root), so the frame's absolute declaration
;; is re-rooted SNAPSHOT-relative by stripping this prefix before it can match.
(def ^:private machine-snapshot-prefix [:rf.runtime/machines :snapshots])

(defn frame-snapshot-marks
  "Compute the SNAPSHOT-relative sensitive/large path set the FRAME declares for
  the machine snapshot keyed under `actor-id` (EP-0025, rf2-398kql) — the
  frame-owned replacement for the removed `:data-schema`→marks bridge. Reads the
  frame's elision-registry declarations (`:source :frame`, installed by
  `reg-frame` `:sensitive` / `:large {:app-db …}`), keeps only those rooted at
  `[:rf.runtime/machines :snapshots <actor-id> …]`, and strips that prefix so
  the remaining path indexes into the snapshot value the trace slot carries
  (e.g. `[… :data :token]`). Returns `{:sensitive [paths] :large [paths]}` (slot
  omitted when empty), or nil when `frame-id` is nil or the frame declares no
  matching snapshot path.

  Public so the machines SSR-hydration projector (`re-frame.machines.ssr`) can
  reuse the SAME re-rooting for its `:data`-map projection (one level deeper —
  it strips the leading `:data` segment too)."
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
  "Walk machine `:data`-bearing trace tag shapes. Marks are paths rooted at the
  SNAPSHOT — per Spec 015 §State machines — so common marks are written as
  `[:data :jwt]`, `[:data :user :ssn]`, etc.

  EP-0025 (rf2-398kql): the marks come from the FRAME's declared classification
  of the machine snapshot path (`frame-snapshot-marks`, the frame-owned sole
  app-db mechanism), UNIONED with any author marks on the machine's `:event`
  registration meta. The prior machine `:data-schema`→marks bridge (EP-0005) is
  removed — a `:sensitive?` / `:large?` `:data`-slot prop no longer classifies
  durable `:data` for egress. Machine `:data` surfaces in several differently-
  shaped trace slots, and per rf2-20d6k2 EVERY one is redacted so a declared
  slot never egresses raw:

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

  All slots resolve marks via the SAME (frame, actor/machine-id) lookup, so a
  spawned instance's snapshot path (keyed under its instance id in the frame's
  declarations) covers the instance's traces. Per rf2-ws5thu / rf2-yyvtk5 the
  live-actor instance address rides under `:actor-id` on every live-runtime row
  (`:rf.machine/transition` / `:rf.machine/snapshot-updated` plus the guard /
  action / microstep / history rows yyvtk5 completed), reserving `:machine-id`
  for the registered TYPE; the lookup prefers `:actor-id` and falls back to
  `:machine-id` for the rows that still legitimately carry the addressed-id
  under that key (`:rf.machine/started` — the BIRTH signal keyed by the
  type/singleton id)."
  [tags frame-id]
  ;; The CHILD-OWNED synthetic on-error payload and the `:start` payload are
  ;; summarized UNCONDITIONALLY (independent of the parent/spawn machine's
  ;; marks) — rf2-0gdic7 / rf2-lft14p / rf2-mxboxi. Run these first so they
  ;; apply even when the trace's machine declares no `:data` marks (a child's
  ;; secret cannot be classified against its PARENT's marks, and an inline
  ;; child's `:start` args are unclassifiable here at all).
  (let [tags       (project-spawn-synthetic-payloads tags)
        machine-id (or (:actor-id tags) (:machine-id tags))
        ;; EP-0025: union the FRAME's snapshot-path classification (the sole
        ;; app-db mechanism — `frame-snapshot-marks`) with any author marks on
        ;; the machine's `:event` registration meta. Both are snapshot-rooted
        ;; `[:data …]` path sets.
        author     (marks-when :event machine-id)
        frame-mk   (frame-snapshot-marks frame-id machine-id)
        marks      (let [s (into (vec (:sensitive author)) (:sensitive frame-mk))
                         l (into (vec (:large author))     (:large frame-mk))]
                     (when (or (seq s) (seq l))
                       (cond-> {}
                         (seq s) (assoc :sensitive s)
                         (seq l) (assoc :large l))))]
    (if-not marks
      tags
      (let [[sens large] (mark-paths marks)
            ;; `:data`-map-relative paths for the slots that carry the bare
            ;; `:data` map (not a full snapshot).
            data-sens  (strip-data-prefix sens)
            data-large (strip-data-prefix large)
            ;; Whole-snapshot projection (paths rooted at the snapshot).
            project    (fn [v] (when v (redact-with-paths v sens large)))
            ;; Bare-`:data`-map projection (paths rooted at the `:data` map).
            project-data (fn [v] (when v (redact-with-paths v data-sens data-large)))
            ;; A guard/action `:input` map: redact its `:data` sub-slot;
            ;; leave `:event` to project-event-tags / the synthetic-payload
            ;; summary above.
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

(defn- redact-exception-data-slot
  "Fail-closed whole-slot redaction tail shared by the two exception-payload
  projectors (rf2-za4853): replace the WHOLE `:exception-data` slot with the
  `:rf/redacted` sentinel and stamp `:sensitive? true`. The author-keyed
  `ex-data` of a thrown machine action / flow output is not path-walkable, so
  when the projector's (own) guard decides the surrounding machine / frame
  handles secrets, the whole slot is dropped rather than shipped raw. Only the
  redact ACTION is shared — each projector keeps its own redact-or-not guard —
  so this helper unconditionally applies the same two-key assoc and cannot
  weaken either fail-closed decision."
  [tags]
  (assoc tags :exception-data privacy/redacted-sentinel :sensitive? true))

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
  other `:rf.error/*` trace handles the raw exception object.

  Per rf2-yyvtk5 the `:rf.error/machine-action-exception` row now addresses
  the throwing LIVE actor instance under `:actor-id` (reserving `:machine-id`
  for the registered TYPE); the lookup prefers `:actor-id` and falls back to
  `:machine-id` so a spawned instance's snapshot path still gates the exception
  payload.

  EP-0025 (rf2-398kql): the \"machine declares ANY `:sensitive` mark\" decision
  now consults the FRAME's snapshot-path classification (the sole app-db
  mechanism) unioned with any author `:event` registration marks — the machine
  `:data-schema`→marks bridge that once contributed here is removed."
  [tags frame-id]
  (let [machine-id (or (:actor-id tags) (:machine-id tags))
        author     (marks-when :event machine-id)
        frame-mk   (frame-snapshot-marks frame-id machine-id)]
    (if (and (contains? tags :exception-data)
             (or (seq (:sensitive author)) (seq (:sensitive frame-mk))))
      (redact-exception-data-slot tags)
      tags)))

(defn- project-flow-failed-tags
  "Walk the `:rf.flow/failed` tag shape (rf2-iqh5yf). The trace carries the
  throwing flow's structured exception summary — `:exception-message` (plain
  string) + `:exception-data` (the ex-info `ex-data` map) — alongside
  `:flow-id` / `:inputs` / `:frame`. The `:inputs` slot already rode the
  wire-elision walker at emit time (`re-frame.flows/elide-inputs`); the
  `:exception-data` map is the developer's arbitrary, author-keyed payload —
  the SAME exposure class `project-machine-error-tags` covers for a thrown
  machine action's `ex-data`.

  Per [Security.md §Author guidance for exceptions under path-level
  `:sensitive?`] the framework does NOT taint-track values into ex-data
  keys — a flow `:output` that throws `(ex-info msg {:token tok})` over a
  sensitive input has put the secret at an author-chosen key the path-keyed
  walker cannot resolve. So the conservative, footgun-prevention posture
  (mirroring the machine path and `resolve-sub-output-marks`'s layer-1
  treatment): when the flow's FRAME declares ANY sensitive elision
  declaration — the frame handles secrets, and a flow read one — elide the
  WHOLE `:exception-data` slot to `:rf/redacted` and stamp `:sensitive? true`
  before the error trace crosses the bus / epoch-capture / AI-MCP egress
  boundary. The structural slots (`:flow-id` / `:frame` / `:exception-message`
  / `:inputs`) stay intact: `:flow-id` / `:frame` carry no user value and are
  the attribution consumers need; `:inputs` was already elided; the
  category-only `:exception-message` is left as-is (per the same author
  guidance — name the failure category, not the value).

  A flow whose frame declares NO SENSITIVE classification rides
  `:exception-data` verbatim (precise, not a blanket scrub — symmetric with
  every other per-registration projection; gated on SENSITIVE declarations
  only, like `project-machine-error-tags`'s `(seq (:sensitive marks))` —
  `:large` does not apply: the slot is a developer-shaped diagnostic, not an
  app-data path graph). Frame resolution comes off `:frame`; a nil /
  unresolvable frame FAILS CLOSED (redacts), matching `elide-wire-value`."
  [tags]
  (let [frame-id (:frame tags)]
    (if (and (contains? tags :exception-data)
             (some? (:exception-data tags))
             (or (nil? frame-id)
                 (seq (elision/sensitive-declarations frame-id))))
      (redact-exception-data-slot tags)
      tags)))

(defn- project-machine-wrote-db-tags
  "Walk the `:rf.error/machine-action-wrote-db` tag shape (rf2-x9haxl).
  A machine action / `:rf.machine/update-snapshot` patch that wrongly carries
  a `:db` key is rejected by `transition/enforce-db-disallow` /
  `update-snapshot/update-snapshot-fx`, which emit this error carrying the
  STRIPPED `:db` value under `:offending-value`. That value is the WHOLE app-db
  the callback tried to write — not snapshot-shaped, so the machine's
  `[:data …]`-rooted marks do not map onto it, and `machine-op?` (above) does
  NOT reach this `:rf.error/*` op. Left raw, the entire app-db (every secret it
  holds) egresses to trace listeners, epoch capture, MCP / tool readbacks, and
  logs.

  The offending value is a programmer-error diagnostic, not an app-data path
  graph: the operator needs to know an action wrote `:db` and WHERE (the
  `:actor-id` / `:machine-id` / `:action-id` / `:state-path` structural slots,
  left intact), not the app-db contents. So the whole `:offending-value` slot is
  summarized to the `:rf/redacted` sentinel UNCONDITIONALLY (independent of the
  machine's declared marks — the value is the whole app-db, inherently the most
  sensitive payload and unclassifiable per-slot here), the same fail-closed
  posture `project-spawn-synthetic-payloads` takes for the child-owned `:start`
  / spawn-error payloads. The runtime never reads `:offending-value` back — it
  is observability-only — so the strip is egress-only and changes no behaviour."
  [tags]
  (if (contains? tags :offending-value)
    (assoc tags :offending-value privacy/redacted-sentinel)
    tags))

(defn- machine-op?
  [operation]
  (let [n (and (keyword? operation) (namespace operation))]
    (and n (or (= "rf.machine" n)
               (and (>= (count n) 11)
                    (= "rf.machine." (subs n 0 11)))))))

(defn- interceptor-ref-id?
  "True when `x` is a structurally-valid interceptor REFERENCE id — a bare
  keyword, or an `[id arg]` 2-vector whose head is a keyword (Spec 002
  §Interceptor references). Mirrors
  `re-frame.interceptor-registry/interceptor-ref?` but inlined here so the
  marks chokepoint carries no require on the registry ns (avoids a load
  cycle). Used by `project-override-summary-tags` to FAIL CLOSED on any
  non-id payload in the `:rf.interceptor/override-summary` shape."
  [x]
  (or (keyword? x)
      (and (vector? x)
           (= 2 (count x))
           (keyword? (first x)))))

(defn- project-override-summary-tags
  "Fail-closed projection for the `:rf.interceptor/override-summary` trace tag
  (Spec 009 §`:tags` interceptor family, rf2-9vx0jk). The router constructs the
  summary id/count-only:

    {:matched [<ref-id>…] :replaced [<ref-id>…] :removed [<ref-id>…] :count N}

  where every `<ref-id>` is a bare keyword or an `[id arg]` 2-vector reference
  — never an interceptor value, fn, executable map, or raw replacement/factory
  arg. This projection is the documented BOUNDARY: it re-asserts that shape
  fail-closed so that IF the summary ever grows to carry a non-id payload (a
  refactor regression), the egress is the `:rf/redacted` sentinel rather than a
  raw value crossing the bus / epoch-capture / AI-MCP egress boundary.

  An `[id arg]` ref is EDN-serializable but its `arg` is NOT proven
  privacy-safe — so the conservative rule keeps a BARE-KEYWORD id verbatim but
  reduces an `[id arg]` ref to its head keyword `id` (dropping the `arg`). Any
  entry that is neither shape collapses to `:rf/redacted`. The scalar `:count`
  is kept when it is a number; anything else is dropped. Unknown keys in the
  summary map are dropped (only the four known slots egress)."
  [tags]
  (let [summary (:rf.interceptor/override-summary tags)]
    (if-not (map? summary)
      ;; A non-map payload under the slot is malformed — drop it entirely.
      (dissoc tags :rf.interceptor/override-summary)
      (let [sanitize-id (fn [x]
                          (cond
                            (keyword? x)            x
                            (interceptor-ref-id? x) (first x) ;; [id arg] → id
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
                      (project-cofx-map-slot :rf.event/coeffects)

                      ;; rf2-1xdotm — the `:rf.event/run-start` trace carries
                      ;; the post-generation flat `:rf.cofx` replay token under
                      ;; `:rf.event/cofx`; redact each fact's value against the
                      ;; cofx-id's declared marks (the same per-cofx-id rule
                      ;; `:rf.event/coeffects` uses) so a declared-sensitive
                      ;; recordable fact never egresses raw on the run-start
                      ;; trace / epoch record's replay slot.
                      (and (map? tags) (contains? tags :rf.event/cofx))
                      (project-cofx-map-slot :rf.event/cofx)

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

                      ;; `:rf.cofx/run` (ambient supplier) and `:rf.cofx/
                      ;; generated` (slice-B.7 recordable generation) share
                      ;; the produced-value shape — `:rf.cofx/id` + `:rf.cofx/
                      ;; value` — so both redact the produced value against
                      ;; the cofx's declared marks here.
                      (and (map? tags) (or (= :rf.cofx/run operation)
                                           (= :rf.cofx/generated operation)))
                      (project-cofx-run-tags)

                      (and (map? tags) (= :rf.sub/run operation))
                      (project-sub-tags frame-id)

                      (and (map? tags) (machine-op? operation))
                      (project-machine-tags frame-id)

                      ;; rf2-9vx0jk — the dev-only `:rf.interceptor/override-
                      ;; summary` tag on `:rf.event/run-start` carries id/count-
                      ;; only override facts. This projection is the documented
                      ;; chokepoint boundary: it re-asserts the id-only shape
                      ;; FAIL-CLOSED (an `[id arg]` ref → head id; any non-ref
                      ;; payload → `:rf/redacted`) so a future shape-grow that
                      ;; smuggled a value never egresses raw.
                      (and (map? tags)
                           (contains? tags :rf.interceptor/override-summary))
                      (project-override-summary-tags)

                      ;; rf2-iqh5yf — the `:rf.flow/failed` trace carries a
                      ;; throwing flow's structured exception summary, including
                      ;; the developer's arbitrary `:exception-data` ex-info map.
                      ;; A flow `:output` that throws over a sensitive input may
                      ;; smuggle the secret into an author-keyed ex-data slot the
                      ;; path-keyed walker cannot resolve, so redact the whole
                      ;; `:exception-data` fail-closed when the flow's frame
                      ;; declares sensitivity (the flows analogue of the machine
                      ;; clause below). Keyed on the operation so it reaches the
                      ;; flow op-type's `:exception-data` distinctly from the
                      ;; machine error category.
                      (and (map? tags) (= :rf.flow/failed operation))
                      (project-flow-failed-tags)

                      ;; rf2-zsm03 — the `:rf.error/machine-action-exception`
                      ;; trace carries the thrown action's `ex-data` under a
                      ;; bare `:exception-data` slot. Its op namespace is
                      ;; `:rf.error/*` (NOT `rf.machine`), so `machine-op?`
                      ;; above does not reach it; redact the slot against the
                      ;; machine's declared `:sensitive` marks here so an
                      ;; action that throws app secrets inside a sensitive
                      ;; machine does not leak them past the egress boundary.
                      ;; Guarded off the flow op so the two `:exception-data`
                      ;; carriers route to their own projector.
                      (and (map? tags)
                           (not= :rf.flow/failed operation)
                           (contains? tags :exception-data))
                      (project-machine-error-tags frame-id)

                      ;; rf2-x9haxl — the `:rf.error/machine-action-wrote-db`
                      ;; trace carries the STRIPPED `:db` value (the whole app-db
                      ;; the action / `:rf.machine/update-snapshot` patch wrongly
                      ;; wrote) under a bare `:offending-value` slot. Its op
                      ;; namespace is `:rf.error/*` (NOT `rf.machine`), so
                      ;; `machine-op?` does not reach it; summarize the slot to
                      ;; `:rf/redacted` unconditionally so the app-db (and every
                      ;; secret it holds) does not egress raw to listeners /
                      ;; epoch / MCP / logs. Last in the cond — a tags map
                      ;; reaching here carries `:offending-value` but none of the
                      ;; earlier slot shapes.
                      (and (map? tags) (contains? tags :offending-value))
                      (project-machine-wrote-db-tags))]
      (assoc event :tags tags'))))

;; ---- late-bind hook registration ----------------------------------------
;;
;; The trace ns reads through these hooks; this ns reads through the
;; existing elision-registry. The arrangement avoids load cycles
;; (`re-frame.trace` → `re-frame.marks` would cycle since marks
;; requires elision which requires trace).

(late-bind/set-fn! :marks/project-trace-event project-trace-event)
(late-bind/set-fn! :marks/redact-event-by-registration redact-event-by-registration)
(late-bind/set-fn! :marks/validate-marks!     validate-marks!)
(late-bind/set-fn! :marks/marks-for           marks-for)
;; NOTE: the `:marks/declare-machine-schema-marks!` / `:marks/clear-machine-schema-marks!`
;; hooks are GONE (EP-0025, rf2-398kql). They published the writers of the
;; deleted schema-sourced `machine-id->schema-marks` table — the machine
;; `:data-schema`→marks redaction bridge (EP-0005). Frame-declared `:sensitive`
;; / `:large {:app-db …}` paths are now the sole app-db classification mechanism;
;; the schema-field classification axis (and its machine bridge) is killed.
(late-bind/set-fn! :marks/resolve-sub-output-marks resolve-sub-output-marks)
(late-bind/set-fn! :marks/mark-sub-output!    mark-sub-output!)
(late-bind/set-fn! :marks/clear-marks!        clear-marks!)
(late-bind/set-fn! :marks/clear-sub-output-marks! clear-sub-output-marks!)
;; NOTE: the `:marks/add-marks` / `:marks/set-marks` hooks are GONE (rf2-gjp7t6).
;; EP-0015 §3 (rf2-mngp4o) removed the public imperative façade exports, and the
;; hooks themselves had ZERO consumers — they were kept only for directory-
;; contract symmetry. The underlying `add-marks` / `set-marks` fns survive as
;; test / conformance-only helpers (^:no-doc), reached by DIRECT REQUIRE from
;; the marks tests + the conformance corpus harness — never through a late-bind
;; hook. With no consumer the publications were dead weight.
