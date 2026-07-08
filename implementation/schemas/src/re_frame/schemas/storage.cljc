(ns re-frame.schemas.storage
  "Per-frame storage + registration surface for app-db schemas.

  Per Spec 010 §Per-frame schemas. The registry shape is
    {frame-id {path schema-meta}}
  mirroring `re-frame.flows`'s frame-scoping (rf2-lvwr). The per-frame
  atom is the **single source of truth** for `app-db` schemas — app-db
  schemas are NOT a registrar kind (resolved rf2-0frdi, finalised
  rf2-cq1ak). Source-coords / hot-reload / pair-tool introspection
  reads through `app-schema-meta-at`, which returns the per-frame
  metadata map (including the registration's `:ns` / `:line` / `:file`
  source-coords).

  Owns:
    - `schemas-by-frame` atom (the authoritative store).
    - `reg-app-schema` / `reg-app-schemas` registration entry points.
    - `app-schema-at` / `app-schemas` query entry points.
    - `app-schema-meta-at` — meta-introspection (source-coords, etc.)
      consumed by pair-tools and source-coord tests.
    - `frame-schema-entries` cross-artefact seam consumed by
      `re-frame.elision` / `re-frame.epoch` via the late-bind table.
    - `snapshot-schemas-by-frame` / `restore-schemas-by-frame!` /
      `clear-schemas-by-frame!` — test-support hooks consumed by
      `re-frame.test-support`'s reset-runtime fixture."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.path :as path]
            [re-frame.privacy :as privacy]
            [re-frame.schemas.validator :as validator]
            [re-frame.schemas.walker :as walker]
            [re-frame.source-coords :as source-coords]))

#?(:clj (set! *warn-on-reflection* true))

(defonce
  ^{:doc "frame-id → path → schema-meta. Per-frame so a story or test
          fixture's reg-app-schema does not bleed into the default
          frame's contract."}
  schemas-by-frame
  (atom {}))

(defn resolve-frame
  "Resolve the frame a schema registration / read targets. EP-0002 —
  app-db schemas are CONTEXT-REQUIRED FRAME-LOCAL: the explicit `:frame`
  opt (the *override*) wins, else the carried-invariant scope chain via
  `frame/require-current-frame!` (a `with-frame` / frame-provider scope, or
  a frame `:initial-events` step). Called under no established scope and no
  explicit `:frame`, it raises the always-on `:rf.error/no-frame-context`
  (per Spec 002 §Frame target resolution) rather than resolving to a
  synthesised `:rf/default` floor — namespace-load time is not a reason to
  pick a default frame. `operation` (optional) names the surface for the
  error payload's `:operation` slot.

  rf2-7pllal — the explicit `(:frame opts)` override is a frame TARGET, not
  necessarily a frame-id keyword: EP-0024 made frame VALUES first-class, so
  `{:frame frame-value}` is a legal shape. The override is normalized through
  `frame/frame-target->id` (a keyword id passes byte-identically; a frame
  value yields its runnable id) — the SAME pattern `re-frame.core/dispatch`
  uses for its `:frame` opt — so a schema registered under a frame VALUE is
  keyed by the SAME id a read-by-id later resolves. Before the fix a
  `{:frame frame-value}` registration stored under the frame-value MAP itself
  and a read-by-id silently missed it.

  The resolved target is then asserted to be a keyword frame-id: the
  `schemas-by-frame` registry is keyed by it, so an arbitrary non-keyword
  `:frame` (a string, a non-frame map, a vector) must fail loud at the
  resolution boundary rather than silently becoming a registry key that no
  keyword-id read can ever reach (the no-silent-swallow principle)."
  ([opts] (resolve-frame opts :reg-app-schema))
  ([opts operation]
   (let [override (:frame opts)
         frame-id (if (some? override)
                    (frame/frame-target->id override)
                    (frame/require-current-frame!
                      operation {:where 'rf/reg-app-schema}))]
     (when-not (keyword? frame-id)
       (error/throw-error!
         :rf.error/app-schemas-bad-arg
         'rf/app-schemas
         (str "the :frame opt must be a frame-id keyword or a frame value "
              "(from rf/make-frame); got " (pr-str override)
              ", which resolved to the non-keyword frame target "
              (pr-str frame-id) ". Pass a frame-id keyword or a frame value.")
         {:recovery :supply-a-frame-id-or-frame-value
          :extra    {:received override
                     :resolved frame-id
                     :expected "keyword frame-id or frame value"}}))
     frame-id)))

(defn coerce-opts
  "Permit the keyword-only sugar `(app-schemas frame-id)` and the
  opts-map form `(app-schemas {:frame frame-id})`.

  rf2-iszpyg — a `nil` argument coerces to `{}` (the empty-opts shape),
  identical to the no-arg arity's behaviour: it means \"no override —
  resolve the frame from the carried-invariant scope\". Every read /
  registration entry point already passes `{}` in its zero-arity
  (`app-schema-at`, `app-schema-meta-at`, `app-schemas`,
  `app-schemas-digest`, `reg-app-schemas`), so `nil` only arrives from an
  explicit `(app-schemas nil)` by a trusted in-process caller. Unlike the
  nil-PATH hazard (a non-sequential path poisons the `get-in` validation
  hot path), a nil OPTS has no downstream hazard — it just delegates frame
  resolution to scope, exactly as the no-arg arity does — so accepting it
  removes a footgun rather than masking a defect.

  rf2-7pllal — a bare frame VALUE (`rf/make-frame`'s return token) is itself
  a map (it carries `:rf.frame/object` / `:rf.frame/runnable-id`), so the
  `frame/frame-value?` discriminator MUST be checked BEFORE the generic
  `map?` opts-map branch. Otherwise a bare frame value is misclassified as an
  opts map with no `:frame` key and silently falls back to the ambient frame
  (or throws `:rf.error/no-frame-context` outside a scope). A bare frame
  value coerces to `{:frame <frame-value>}` so the frame the value names is
  the registration / read target — the value's id is then resolved by
  `resolve-frame` (via `frame/frame-target->id`), mirroring how the keyword-
  sugar arity names a frame."
  [opts-or-frame-id]
  (cond
    (nil? opts-or-frame-id) {}
    ;; A keyword frame-id is the `{:frame kw}` sugar; a bare frame VALUE is the
    ;; `{:frame value}` sugar — both are frame TARGETS naming a frame. The
    ;; frame-value? check MUST come before the generic `map?` branch below: a
    ;; frame value IS a map, so without this it would be misclassified as an
    ;; opts map with no `:frame` key and silently fall back to the ambient
    ;; frame (rf2-7pllal). resolve-frame later normalizes the target (keyword
    ;; or value) to its frame id via frame/frame-target->id.
    (or (keyword? opts-or-frame-id)
        (frame/frame-value? opts-or-frame-id)) {:frame opts-or-frame-id}
    (map? opts-or-frame-id) opts-or-frame-id
    :else
    (error/throw-error!
      :rf.error/app-schemas-bad-arg
      'rf/app-schemas
      (str "app-schemas expects a keyword frame-id, a frame value, or an "
           "opts map; got " (pr-str opts-or-frame-id) ". Pass a frame-id "
           "keyword, a frame value (from rf/make-frame), or a "
           "{:frame <frame-id-or-value>} opts map.")
      {:recovery :supply-a-frame-id-or-opts-map
       :extra    {:received opts-or-frame-id
                  :expected "keyword frame-id, frame value, or opts map"}})))

(defn frame-target->opts
  "Lift a `reg-app-schema` metadata `:frame` value — a frame TARGET — into the
  `{:frame <target>}` opts shape `resolve-frame` consumes, so the singular
  registration path resolves a target IDENTICALLY to the read surface.

  rf2-5429ec — the bug this closes: the singular `reg-app-schema` previously
  passed the bare `(:frame metadata)` value straight through `coerce-opts` as
  if it were the WHOLE opts arg. For a frame-id keyword / frame value that
  happens to work (coerce-opts lifts both to `{:frame target}`), but for a
  NON-frame MAP target (e.g. `{:not :a-frame}`, or a raw frame-record-shaped
  map) `coerce-opts` classifies it as an OPTS map and returns it verbatim —
  with no `:frame` key — so `resolve-frame` saw no override and silently
  borrowed the AMBIENT frame, registering the schema against the wrong frame
  with no error. That contradicts spec/010-Schemas.md §Per-frame schemas and
  spec/API.md §Schemas, which require an explicit `:frame` resolving to a
  non-keyword target (a string, a vector, a non-frame map) to FAIL LOUD with
  `:rf.error/app-schemas-bad-arg` (the no-silent-swallow principle).

  The metadata `:frame` value is a frame TARGET (not an opts map), so we wrap
  it back into `{:frame target}` and let `resolve-frame` apply the SAME
  `frame/frame-target->id` + keyword-frame-id assertion the read surface uses.
  A non-frame map target then resolves to itself (frame-target->id leaves a
  non-frame map unchanged) and trips the keyword-frame-id guard → loud throw.
  A `nil` target (no `:frame` in the metadata) yields `{}` — resolve from the
  carried-invariant scope. Pure."
  [frame-target]
  (if (some? frame-target)
    {:frame frame-target}
    {}))

(defn- best-effort-frame
  "Resolve the registration frame for an error payload WITHOUT throwing —
  the explicit `:frame` opt if present, else the carried-invariant scope
  frame, else `nil` when no scope is established. Used only to enrich the
  `:rf.error/app-schema-runtime-path` payload (rf2-k0ew8n): the path-gate
  runs before the registration's own (throwing) frame resolution, so we
  must not let a missing scope (or a malformed opts shape) mask the
  runtime-path rejection.

  The argument is an OPTS map / opts-sugar (the bulk path's `opts-or-frame-id`,
  or the singular path's `(frame-target->opts (:frame metadata))` lift) — i.e.
  the same shape `resolve-frame` consumes — so this is the non-throwing twin of
  the registration's own resolution and they never disagree on which frame the
  error payload names (rf2-5429ec)."
  [opts]
  (try
    (resolve-frame (coerce-opts opts))
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn- extract-app-schema-from-metadata
  "Pull the `:schema` out of a `reg-app-schema` metadata map (rf2-wvh95f F2 —
  :schema-in-metadata). The second arg of `reg-app-schema` is now the standard
  Spec 001 registration-metadata map `{:schema … :frame … :doc …}`; the schema
  is its `:schema` value. Fails LOUDLY at the authoring boundary (dev AND prod
  — a caller bug, not user input):

   - a non-map second arg throws `:rf.error/app-schema-bad-metadata` naming the
     path and the value (the common slip after the F2 grammar change is passing
     the bare schema where the metadata map now goes);
   - a map with no `:schema` key throws the same error (the schema is the point
     of the registration — an absent one is never an implicit pass).

  Returns the schema value."
  [path metadata]
  (when-not (map? metadata)
    (error/throw-error!
      :rf.error/app-schema-bad-metadata
      'rf/reg-app-schema
      (str "reg-app-schema's second arg must be a registration-metadata map "
           "carrying the schema under :schema — e.g. (reg-app-schema " (pr-str path)
           " {:schema MySchema}). Got " (pr-str metadata) ". Per rf2-wvh95f F2 "
           "the schema is :schema-in-metadata, no longer a positional arg.")
      {:recovery :wrap-schema-in-metadata-map
       :extra    {:path path :received metadata}}))
  (when-not (contains? metadata :schema)
    (error/throw-error!
      :rf.error/app-schema-bad-metadata
      'rf/reg-app-schema
      (str "reg-app-schema for path " (pr-str path) " declares no :schema in "
           "its metadata map. :schema is REQUIRED — it IS the registration. "
           "Per rf2-wvh95f F2 / Spec 010.")
      {:recovery :supply-schema-in-metadata-map
       :extra    {:path path :metadata metadata}}))
  (:schema metadata))

;; ---- registration-time path-shape validation (rf2-sk0ql) ------------------
;;
;; A `reg-app-schema` `path` is a `get-in`/`assoc-in`-shaped path: a
;; SEQUENTIAL collection of keys, or the empty vector `[]` for the whole
;; `app-db` root (Spec 010 §`app-db` schemas — path-based / §A schema for
;; the whole `app-db` / §Digest algorithm "path is a vector of keywords
;; (or the empty vector for the root schema)").
;;
;; Before rf2-sk0ql `reg-app-schema` / `reg-app-schemas` stored the `path`
;; verbatim with no shape check. A non-sequential scalar — a bare keyword
;; (`:n`), a string, a number, nil — registered successfully and surfaced
;; through introspection, but `validate-app-schema!` later evaluates
;; `(get-in db reg-path)` over every stored path, and `get-in` with a
;; non-sequential `ks` throws `IllegalArgumentException` ("Don't know how
;; to create ISeq from: …"). The live router's `run-post-commit-validation!`
;; wraps that call in a defensive try/catch (a buggy validator must not
;; mask app-db state) and treats the throw as `true` — so an invalid `:db`
;; commit is installed permanently with NO `:rf.error/schema-validation-
;; failure` trace and NO rollback. Worse, the poisoned entry persists in
;; the frame's registry and makes EVERY subsequent commit's validation
;; throw, silently disabling post-commit validation for the whole frame —
;; a correctness- and privacy-relevant bypass (the redaction-bearing
;; failure traces never fire).
;;
;; Fix: fail loud at registration time, BEFORE writing `schemas-by-frame`,
;; with a framework error id (`:rf.error/app-schema-bad-path`). An invalid
;; shape never lands, so the validation hot path can never be poisoned.
;; Always-on (NOT gated by `interop/debug-enabled?`): a malformed
;; registration is a programming error that must surface in every build,
;; and the cost is one cheap predicate per `reg-app-schema` call.

(defn valid-app-schema-path?
  "True when `path` is a valid `reg-app-schema` path: a sequential
  collection of CONCRETE path segments (a vector / seq), INCLUDING the
  empty vector `[]` for the whole-`app-db` root schema. Non-sequential
  scalars — a bare keyword, string, number, nil, map, set — are rejected:
  they break the `(get-in db path)` the validation hot path runs.

  `sequential?` (not `vector?`) is deliberate — APIs MAY accept any
  sequential collection for migration ergonomics (Conventions §The
  `:rf/path` algebra §Path shape). The accepted seq is then NORMALIZED to
  its canonical vector form before it becomes a stored declaration / digest
  path key, so a list path and the equivalent vector path are ONE identity
  (EP-0012 §Path shape — \"all stored declarations MUST normalize to a
  vector\").

  rf2-ujmc3u: each SEGMENT must additionally be a concrete `:rf/path`
  segment (`re-frame.path/segment?` — a portable EDN identity value:
  keyword, string, symbol, safe-range integer, boolean, UUID, instant, or
  nil). Schema paths are full `:rf/path` citizens (Conventions §The
  `:rf/path` algebra cites schema paths), so a composite / function / host
  / float / unsafe-integer segment is rejected at registration rather than
  riding into a stored declaration and a digest path key — the same shared
  concrete boundary every other path consumer inherits, no private
  shape-only grammar. `segment?` shares the CEDN-1 safe-integer predicate,
  so an out-of-safe-range integer segment fails here AND would fail the
  canonical digest-key encoding."
  [path]
  (and (sequential? path)
       (every? path/segment? path)))

;; ---- runtime-db first-segment rejection (rf2-k0ew8n) ----------------------
;;
;; Per Spec 010 §`app-db` schemas, app schemas validate ONLY app-db: the
;; `validate-app-schema!` hot path reads `(get-in app-db path)`. After
;; EP-0001 the durable machine / routing / SSR / elision state moved OUT
;; of app-db into the SEPARATE runtime-db partition, reached through the
;; reserved `:rf.runtime/*` namespace (and the now-retired legacy app-db
;; `:rf/runtime` root). A path whose FIRST segment reaches into runtime-db
;; is a CATEGORY error for `reg-app-schema`:
;;
;;   - a normal `[:map …]` schema registered there validates `(get-in
;;     app-db [:rf.runtime/… …])`, which is `nil` (the state lives in
;;     runtime-db, not app-db), so EVERY dev commit fails validation /
;;     detonates, or
;;   - the author falsely believes they have installed a guard over
;;     runtime-db state when nothing of the sort happened.
;;
;; Either way there is NOTHING to soft-land: no legitimate caller registers
;; an app schema against a runtime path. So — unlike the `:rf.db/runtime`
;; EFFECT seam, where a warning teaches because the misuse still executes
;; as intended — this is a HARD REJECT at the existing pre-mutation gate
;; (RULING (b), rf2-k0ew8n). The runtime-db partition is framework-owned —
;; the framework validates it (machine `:snapshots` refined per-machine from
;; each machine's `:data-schema`); it is NOT a user schema-registration
;; surface (per Conventions §Reserved runtime-db keys — "user code MUST NOT
;; register against it"). So the error tells the user the honest remedy
;; (drop the runtime path), NOT to call a non-public, framework-owned API
;; (rf2-sklyam — the prior `:reason` pointed users at `reg-runtime-schema`,
;; which has no public export and is framework-owned). EP-0001 line ~484
;; said "warn"; that predates the fail-closed hardening campaign
;; (rf2-sk0ql, rf2-naihn1, the legacy-root hard error) and is the artefact
;; under repair here, not a constraint.

(defn runtime-app-schema-path?
  "True when `path`'s FIRST segment reaches into the runtime-db partition
  — a keyword in the reserved `:rf.runtime/*` namespace (`:rf.runtime/
  machines`, `:rf.runtime/routing`, `:rf.runtime/elision`, …), the
  runtime-db CONTAINER root `:rf.db/runtime` (the epoch / restore path
  prefix), OR the retired legacy app-db `:rf/runtime` root. Such a path is
  a category error for `reg-app-schema`, which validates ONLY app-db
  (rf2-k0ew8n).

  Mirrors the canonical runtime-path detection used across the core
  conformance reader (`(= \"rf.runtime\" (namespace (first path)))`) and
  also catches the `:rf.db/runtime` container root and the legacy
  `:rf/runtime` segment. A non-sequential or empty `path` is NOT runtime
  — `valid-app-schema-path?` owns the shape check and the empty `[]` root
  is a legitimate whole-app-db schema."
  [path]
  (boolean
    (when (and (sequential? path) (seq path))
      (let [head (first path)]
        (and (keyword? head)
             (or (= "rf.runtime" (namespace head))
                 (= :rf.db/runtime head)
                 (= :rf/runtime head)))))))

(defn assert-app-schema-path!
  "Throw a framework error (per Spec 009 error catalogue) when `path` is
  not a valid `reg-app-schema` path. Called by `reg-app-schema` /
  `reg-app-schemas` BEFORE the store mutation so a bad path never lands
  in `schemas-by-frame`. Two distinct rejections:

    - `:rf.error/app-schema-bad-path` — the SHAPE is wrong (a
      non-sequential scalar), which would poison the `validate-app-schema!`
      hot path's `(get-in db path)` (rf2-sk0ql).
    - `:rf.error/app-schema-runtime-path` — the shape is fine but the
      FIRST segment reaches into the runtime-db partition (`:rf.runtime/*`
      or the legacy `:rf/runtime` root). App schemas validate only app-db;
      the runtime-db partition is framework-owned (the framework validates
      it — machine `:snapshots` refined per-machine from each machine's
      `:data-schema`) and is NOT a user schema-registration surface, so the
      honest remedy is to drop the runtime path (rf2-k0ew8n, RULING (b);
      rf2-sklyam — the reason no longer points users at a non-public,
      framework-owned API).

  `frame` (optional) names the resolved registration frame for the
  runtime-path error payload; callers pass the frame they resolved. It is
  best-effort context only — the gate runs before the registration's own
  frame resolution, so a `nil` frame still rejects loudly."
  ([path] (assert-app-schema-path! path nil))
  ([path frame]
   (when-not (valid-app-schema-path? path)
     (error/throw-error!
       :rf.error/app-schema-bad-path
       'rf/reg-app-schema
       (str "reg-app-schema path " (pr-str path) " is invalid; pass "
            "a sequential get-in path (vector/seq) "
            "of CONCRETE :rf/path segments (keyword, "
            "string, symbol, safe-range integer, "
            "boolean, UUID, instant, or nil), or [] "
            "for the app-db root — composite / "
            "function / host / float / unsafe-integer "
            "segments are rejected (EP-0012 §The "
            ":rf/path algebra).")
       {:recovery :supply-a-concrete-get-in-path
        :extra    {:received path}}))
   (when (runtime-app-schema-path? path)
     (error/throw-error!
       :rf.error/app-schema-runtime-path
       'rf/reg-app-schema
       (str "app schemas validate only app-db; a "
            "path whose first segment is a "
            ":rf.runtime/* keyword (or the legacy "
            ":rf/runtime root) reaches into the "
            "runtime-db partition. The runtime-db "
            "partition is framework-owned and "
            "validated by the framework (machine "
            ":snapshots refined per-machine from "
            "each machine's :data-schema); it is "
            "NOT a user schema-registration "
            "surface — drop the runtime path.")
       {:recovery :drop-the-runtime-path
        :extra    {:received path
                   :frame    frame}}))))

;; ---- bulk first-argument shape validation (rf2-naihn1) --------------------
;;
;; `reg-app-schemas` documents its first argument as a `{path -> schema}`
;; MAP (Spec 010 §`reg-app-schemas` / API.md). Before this fix the plural
;; API never checked that shape: in Clojure `(keys nil)` is `nil` and
;; iterating `nil` yields no entries, so `(reg-app-schemas nil)` ran the
;; up-front `(run! assert-app-schema-path! (keys nil))` (no-op), registered
;; nothing, and returned `[]` — INDISTINGUISHABLE from the documented `{}`
;; no-op. A boot/config/schema-loader bug that passes `nil` (or any non-map
;; — a vector, a string, a seq of pairs) instead of a schema map then gets
;; a FALSE GREEN: no schemas registered, no validation contract installed,
;; no error surfaced. That silently disables schema enforcement for the
;; whole batch. Fix: reject nil / non-map FIRST, before any mutation, with
;; an explicit error id; keep the empty map `{}` as the documented no-op.

(defn valid-bulk-schemas-arg?
  "True when `path->schema` is an acceptable `reg-app-schemas` first
  argument: a map (INCLUDING the empty map `{}`, the documented no-op).
  nil, vectors, strings, seqs of pairs, and every other non-map shape are
  rejected — they would silently register nothing and hand back `[]`,
  masking a malformed-input bug (rf2-naihn1)."
  [path->schema]
  (map? path->schema))

(defn assert-bulk-schemas-arg!
  "Throw `:rf.error/app-schemas-bad-batch` when `reg-app-schemas`'
  first argument is not a `{path -> schema}` map. Called BEFORE any
  store mutation so a nil / non-map batch rejects atomically rather than
  silently no-op'ing to `[]` (rf2-naihn1). `{}` is accepted (the
  documented empty no-op)."
  [path->schema]
  (when-not (valid-bulk-schemas-arg? path->schema)
    (error/throw-error!
      :rf.error/app-schemas-bad-batch
      'rf/reg-app-schemas
      (str "reg-app-schemas expects a {path -> schema} map (possibly empty); "
           "got " (pr-str path->schema) ". Pass a map of path -> schema.")
      {:recovery :supply-a-path-to-schema-map
       :extra    {:received path->schema
                  :expected "a {path -> schema} map (possibly empty)"}})))

(defn coerce->frame-id
  "Resolve a frame-id from the `opts-or-frame-id` argument the read
  surface accepts: coerce through `coerce-opts` (keyword sugar / opts
  map / throw on bad shape), then `resolve-frame` (`:frame` override or
  the carried-invariant scope frame; raises `:rf.error/no-frame-context`
  outside any scope per EP-0002). The query entry points (`app-schema-at` /
  `app-schema-meta-at` / `app-schemas` / `app-schemas-digest`) use the
  opts ONLY to name a frame, so they collapse the coerce+resolve pair
  through this helper. The `reg-*` entry points keep the two-step form so
  they can read further keys off the coerced opts map."
  [opts-or-frame-id]
  (resolve-frame (coerce-opts opts-or-frame-id)))

;; ---- validator-unavailable warning (rf2-fq7d2) ----------------------------
;;
;; Per Spec 010 §Recommended soft-pass, the schemas artefact ships with a
;; Malli-delegating default validator that returns true ("pass") when the
;; `:schemas/malli-validate` late-bind hook is unbound — i.e. when nothing
;; has published Malli's `validate` into the late-bind table. This is
;; intentional (apps that swap in a non-Malli validator must work), but a
;; `reg-app-schema` call WITH the default validator AND that hook unbound
;; validates nothing — boundary-validated handlers silently accept
;; untrusted input. This warning is the dev-time nudge for that state.
;;
;; Post-rf2-v96fh (schema implies validation) the *common* path can no
;; longer reach it: the `re-frame.schemas` facade now `:require`s
;; `re-frame.schemas.malli` itself, so loading the schemas artefact binds
;; `:schemas/malli-validate` at ns-load and the default validator is LIVE
;; the moment a schema is registered. The warning therefore fires only in
;; the two residual cases — symmetric with `validator.cljc`'s soft-pass
;; fallback doc:
;;   (1) a non-Malli port / app that installed its own validator via
;;       `set-schema-validator!` but left the Malli hook unbound (it
;;       opted out of Malli, so condition 2 below is false — NO warning;
;;       see the closing note), or
;;   (2) a test harness that deliberately unbinds `:schemas/malli-validate`
;;       to exercise the absent-validator path.
;;
;; The warning fires once per process from `reg-app-schema` /
;; `reg-app-schemas` when BOTH hold:
;;   1. `:schemas/malli-validate` late-bind hook is unbound, AND
;;   2. `validator-fn` is still the framework default.
;;
;; Apps that registered a non-default validator (a Zod port, clojure.spec
;; bridge, etc.) opted out of Malli explicitly — condition 2 is false, so
;; no warning.

(defonce ^:private validator-unavailable-warned
  ;; Process-lifecycle one-shot. Reset by `clear-validator-unavailable-warned!`
  ;; (used by the schemas test-fixture's `reset-runtime`).
  (atom false))

(defn clear-validator-unavailable-warned!
  "Reset the one-shot `:rf.warning/schema-validator-unavailable` cache.
  Used by test fixtures so each case starts from a clean diagnostic slate."
  []
  (reset! validator-unavailable-warned false))

;; ---- walker-opaque warning (rf2-jsokn / rf2-ycqtv finding #12) ------------
;;
;; Per Spec 010 §The `:schema` value is opaque to re-frame, the framework's
;; schema walker (`re-frame.schemas.walker`) is pure data — it handles
;; vector-form Malli EDN and treats compiled `m/schema` values as opaque
;; leaves. A user that registers a compiled `m/schema` value and puts
;; `:sensitive?` / `:large?` per-slot flags inside the compiled value will
;; see the walker **silently skip** them — the validation-failure trace
;; won't redact the sensitive slot and the size-elision walker won't see
;; the `:large?` declarations.
;;
;; This warning fires once per process from `reg-app-schema` /
;; `reg-app-schemas` when the registered schema is a genuinely opaque
;; NON-keyword value (compiled `m/schema` object, etc.) the walker
;; cannot introspect. Symmetric with the
;; `:rf.warning/schema-validator-unavailable` warn-once-per-process
;; pattern above. Cost is one boot-time predicate per `reg-app-schema`
;; call; the warning is the discoverability nudge for the one workable
;; shape — registering the vector form so the walker can introspect the
;; per-slot flags (the registration-meta `:sensitive?` fallback has been
;; removed; sensitivity is path-targeted — rf2-k0ew8n).
;;
;; Keyword schemas DO NOT warn (rf2-ee38b.6 — correctness P2). A bare
;; keyword is non-vector but is a valid, idiomatic Malli schema in two
;; flavours: a primitive type (`:int` / `:string` / `:boolean` / `:any`)
;; and a registry reference (`:my/user-schema`). The walker's keyword
;; clause returns `acc` with no declarations because a bare keyword
;; CANNOT carry per-slot props — for a primitive there is provably
;; nothing to skip, so the warning was a pure false positive on the
;; common case. The predicate cannot cheaply distinguish a primitive
;; keyword from a registry-ref keyword without a Malli registry consult
;; (which would violate Spec 010 §The `:schema` value is opaque to
;; re-frame). Rather than warn on every keyword — a frequent
;; false-positive to catch a rare registry-ref true-positive — we
;; suppress the keyword case entirely. Registry refs that hide per-slot
;; flags are an advanced shape covered by the walker docstring's
;; discoverability caveat (rf2-yaioz).

(defonce ^:private walker-opaque-warned
  ;; Process-lifecycle one-shot. Reset by `clear-walker-opaque-warned!`
  ;; (used by the schemas test-fixture's `reset-runtime`).
  (atom false))

(defn clear-walker-opaque-warned!
  "Reset the one-shot `:rf.warning/schema-walker-opaque` cache. Used by
  test fixtures so each case starts from a clean diagnostic slate."
  []
  (reset! walker-opaque-warned false))

(defn- walker-introspectable?
  "True when the schema value is a form the pure-data walker can fully
  introspect for per-slot `:sensitive?` / `:large?` flags — the root is
  vector-form Malli EDN or a bare keyword (primitive type or registry
  ref — a keyword cannot carry per-slot props, so the walker provably
  skips nothing and there is nothing to warn about, rf2-ee38b.6) AND no
  NESTED child anywhere beneath it is itself opaque.

  Per rf2-hi0tf8: a schema whose ROOT is walkable vector-form EDN can
  still embed an opaque child at a deeper slot (e.g. `[:map [:token
  (m/schema [:string {:sensitive? true}])]]`) — a root-only check would
  call that schema introspectable even though the nested compiled
  value's per-slot flags are exactly as invisible to the walk as a
  top-level opaque schema's. `schema-has-opaque-child?` recurses the
  whole tree so this predicate is accurate at any depth."
  [schema]
  (not (walker/schema-has-opaque-child? schema)))

(defn- maybe-warn-walker-opaque!
  "Emit `:rf.warning/schema-walker-opaque` once per process when
  `reg-app-schema` / `reg-app-schemas` is invoked with a schema that is
  opaque — at its ROOT or at any NESTED child — to the pure-data walker
  (a compiled `m/schema` object / other non-vector, non-keyword value,
  anywhere in the tree). Vector forms with no opaque descendant and bare
  keywords do not warn — see `walker-introspectable?`.

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`
  so the production bundle DCEs the consult+emit branch (Spec 009
  §Production builds)."
  [schema path]
  (when (and (not (walker-introspectable? schema))
             (not @walker-opaque-warned))
    (when (compare-and-set! walker-opaque-warned false true)
      (when-let [emit! (late-bind/get-fn :trace/emit!)]
        (emit! :warning :rf.warning/schema-walker-opaque
               {:path path
                :schema-kind (if (map? schema)
                               :compiled-schema-object
                               :unknown)
                :reason
                (str "reg-app-schema was called with a schema that is"
                     " opaque — either the registered value ITSELF is a"
                     " compiled / opaque form (a non-vector, non-keyword"
                     " value such as a compiled m/schema object), or a"
                     " vector-form schema embeds one as a NESTED child."
                     " The schema-walker (used for per-slot"
                     " `:sensitive?` / `:large?` extraction) can only"
                     " introspect vector-form Malli EDN — per-slot flags"
                     " inside an opaque value, at any depth, are"
                     " silently skipped. The workable shape: register"
                     " the vector form directly, all the way down, so"
                     " the walker can introspect every per-slot flag."
                     " (The handler/cofx/sub registration-meta"
                     " `:sensitive?` fallback has been removed —"
                     " sensitivity is path-targeted and the redactor"
                     " consults only per-slot schema declarations.) Per"
                     " Spec 010 §The `:schema` value is opaque to"
                     " re-frame.")})))))

(defn- maybe-warn-validator-unavailable!
  "Emit `:rf.warning/schema-validator-unavailable` once per process when
  `reg-app-schema` / `reg-app-schemas` is invoked AND the Malli adapter
  is unloaded AND the framework-default validator is still installed.

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`
  so the production bundle DCEs the consult+emit branch (Spec 009
  §Production builds). The keyword `:rf.warning/schema-validator-
  unavailable` is a literal arg at the call site — moving the gate
  inside this helper would leave the literal reachable from the
  unconditional helper call and defeat the elision sentinel."
  []
  (when (and (not @validator-unavailable-warned)
             (nil? (late-bind/get-fn :schemas/malli-validate))
             (validator/using-default-validator?))
    (when (compare-and-set! validator-unavailable-warned false true)
      (when-let [emit! (late-bind/get-fn :trace/emit!)]
        (emit! :warning :rf.warning/schema-validator-unavailable
               {:reason
                (str "reg-app-schema was called but :schemas/malli-validate"
                     " is unbound and the framework-default validator is"
                     " still installed — every validation site soft-passes."
                     " Require `re-frame.schemas.malli` at app boot to"
                     " activate Malli validation, or call"
                     " `set-schema-validator!` with a non-default fn"
                     " to suppress this warning.")})))))

;; ---- hot-reload :rf.schema/violation trace (rf2-ee38b.6) ------------------
;;
;; Per Spec 010 §Schema migration on hot-reload + Spec 009 error catalogue
;; row `:rf.schema/violation`: when a `(frame-id, path)` schema is
;; re-registered during dev (a file save re-evaluates `reg-app-schema`
;; with a DIFFERENT schema for the same path), the live `app-db` value at
;; that path may now violate the new schema. The runtime emits a
;; `:rf.schema/violation` trace (`:op-type :warning`, recovery
;; `:logged-and-skipped`) so dev panels highlight the stale slice — the
;; live app continues running; `app-db` is NOT auto-cleared or rewound.
;;
;; Distinct from `:rf.error/schema-validation-failure` (dispatch-time
;; boundary validation): this fires at the hot-reload edge against
;; PRE-EXISTING state, only when the schema actually changed AND the live
;; value fails the new schema. A no-op re-eval with the same schema does
;; nothing; a re-eval that the live value still satisfies does nothing.

;; The `:rf/redacted` privacy sentinel — Spec 009 §Privacy. Stamped in
;; place of `:mismatching-value` when the new schema declares the slot
;; sensitive, mirroring the `:rf.error/schema-validation-failure`
;; redaction posture so the hot-reload trace never re-leaks a credential.
;; rf2-qe237 — refer to the canonical core def rather than a local copy so
;; the keyword can never drift across artefacts.
;;
;; rf2-u9bjgr / rf2-kzghnz — the sensitivity decision below ALSO fails CLOSED
;; on an OPAQUE schema (a compiled `m/schema` object the pure-data walker
;; cannot introspect): `schema-has-sensitive?` returns false on an opaque
;; value even though Malli may honour a `{:sensitive? true}` slot inside it for
;; the violation. Without the fail-closed arm a hot-reload violation against an
;; opaque schema carrying a sensitive slot leaked `:mismatching-value` verbatim
;; — the SAME asymmetry the `:rf.error/schema-validation-failure` redactor
;; closed for the validate-*! egress (`re-frame.schemas.validate`, the
;; `(or schema-has-sensitive? schema-opaque?)` posture). This is the redaction
;; half of the same fail-closed posture for the hot-reload egress edge.
(def ^:private redacted-sentinel privacy/redacted-sentinel)

(defn- maybe-emit-schema-violation!
  "Emit `:rf.schema/violation` when a re-registration changes the schema
  at `(frame-id, path)` AND the live `app-db` value at `path` fails the
  NEW schema. `prior-schema` is the schema the path carried before this
  registration (nil when first registration). No-op when there is no
  prior schema, the schema is unchanged, no validator is registered, or
  the live value still validates.

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`
  so the production bundle DCEs the consult+emit branch (Spec 009
  §Production builds)."
  [frame-id path prior-schema new-schema]
  (when (and (some? prior-schema)
             (not= prior-schema new-schema))
    (when-let [vf @validator/validator-fn]
      (let [db        (frame/frame-app-db-value frame-id)
            live-val  (get-in db path)
            ;; A malformed new schema can make the validator throw
            ;; (e.g. Malli's `:malli.core/child-error` on a childless
            ;; `[:vector]`). A bad schema is not a hot-reload violation
            ;; and MUST NOT crash registration — treat a throwing
            ;; validator as "cannot determine a violation" (pass).
            passes?   (try (boolean (vf new-schema live-val))
                           (catch #?(:clj Throwable :cljs :default) _ true))]
        (when-not passes?
          (when-let [emit! (late-bind/get-fn :trace/emit!)]
            ;; rf2-u9bjgr / rf2-kzghnz — fail CLOSED on an OPAQUE schema. The
            ;; pure-data walker cannot see a `{:sensitive? true}` slot inside a
            ;; compiled `m/schema` value, so `schema-has-sensitive?` alone would
            ;; return false and `:mismatching-value` would egress the live value
            ;; verbatim — the asymmetry the validate-*! redactor already closes
            ;; (`re-frame.schemas.validate` uses the same
            ;; `(or schema-has-sensitive? schema-has-opaque-child?)` posture). A
            ;; bare keyword is provably flag-free and is NOT opaque
            ;; (`schema-opaque?`). Per rf2-hi0tf8 the opaque check is the
            ;; RECURSIVE `schema-has-opaque-child?`, not the root-only
            ;; `schema-opaque?` — a vector-form `new-schema` can embed a nested
            ;; opaque child the root-only check would miss.
            (let [sensitive? (or (walker/schema-has-sensitive? new-schema)
                                 (walker/schema-has-opaque-child? new-schema))]
              (emit! :warning :rf.schema/violation
                     {:path               path
                      :pre-reload-schema  prior-schema
                      :post-reload-schema new-schema
                      :mismatching-value  (if sensitive?
                                            redacted-sentinel
                                            live-val)
                      :frame              frame-id
                      :recovery           :logged-and-skipped
                      :sensitive?         sensitive?}))))))))

;; ---- schema-attached app-db egress markers REMOVED (EP-0015 §8, rf2-d2r3um)
;;
;; A `reg-app-schema` `{:large? true}` / `{:sensitive? true}` slot prop used
;; to be walked into the frame's `[:rf.runtime/elision …]` registry at
;; registration time (and re-walked per-dispatch by the router) via the
;; `:elision/populate-from-schemas!` hook — a SECOND route to classify a
;; durable app-db path. EP-0015 §8 abolishes that route: schemas describe
;; shape, validation, and explainability, NOT durable app-db egress policy.
;; Durable app-db classification rides the EP-0025 commit-plane classification
;; effects — a `reg-event` returns `:sensitive` / `:large` alongside `:db`
;; (`re-frame.elision/apply-classification-effects`, `:source :effect`).
;;
;; With population gone, so is the registration-time population call, its
;; relevance pre-check (`populate-relevant?` / `schema-contributes-elision?`),
;; and the per-frame registration linearization lock (rf2-naihn1) — that lock
;; existed ONLY to make the side-table write + the elision refresh atomic per
;; frame, closing an off-box-redaction-loss race in the two-step population.
;; A bare `(swap! schemas-by-frame assoc-in …)` is atomic on its own, so the
;; side-table write needs no lock now.
;;
;; The schema's `{:sensitive? true}` slot prop still drives
;; schema-validation-failure-trace redaction (`re-frame.schemas.validate`),
;; and the per-slot extractors still serve their surviving owner-local
;; consumers (the resource `:data-schema` classification, the HTTP body-privacy
;; projector, story-mcp's tool-egress projector) — each consults the schema
;; directly, never this registry. The machine `:data-schema`→MARKS redaction
;; bridge (EP-0005) is NOT among them: EP-0025 (rf2-398kql) reversed it —
;; durable machine `:data` classification now rides the SAME commit-plane
;; classification effects as every other app-db path, not a schema→marks walk.
;; (The machine `:data-schema` still VALIDATES and its props still drive the
;; machine-data validation-FAILURE-trace redactor — only the schema→marks
;; CLASSIFICATION bridge is gone; see `re-frame.schemas.walker` ns-doc.)

;; ---- app-db schema registration -------------------------------------------

(defn reg-app-schema
  "Register a Malli schema at a path inside app-db. Validation runs in
  dev whenever an event handler returns a new app-db; failures emit
  :rf.error/schema-validation-failure.

  ## Grammar — `:schema` lives in the metadata map (rf2-wvh95f F2)

      (rf/reg-app-schema [:user] {:schema UserSchema})
      (rf/reg-app-schema [:user] {:schema UserSchema :frame :session})

  The second arg is the standard Spec 001 registration-metadata map: the
  schema rides under the `:schema` key (the canonical home for `:schema`
  across the whole `reg-*` family — Spec 001 §The metadata map), alongside
  the optional `:frame` target and `:doc`. The schema is no longer a
  positional second arg (the rf2-wvh95f F2 normalisation — :schema-in-
  metadata, uniform with every other reg-* surface). The path is the
  registration id (Conventions §reg-* return-value convention). A missing
  `:schema` key, or a non-map second arg, throws
  `:rf.error/app-schema-bad-metadata` at the authoring boundary.

  Per Spec 010 §Per-frame schemas this registration is frame-scoped.
  EP-0002 — context-required frame-local: the frame comes from the
  explicit :frame metadata key (the *override*), else the carried-invariant
  scope chain (a (with-frame ...) wrapper, a frame-provider, or a frame
  :initial-events step). Registering under no established scope and no
  explicit :frame raises :rf.error/no-frame-context — namespace-load time
  is not a reason to register against a synthesised :rf/default.

  Per rf2-0frdi / rf2-cq1ak the schemas artefact owns its own per-frame
  side-table (`schemas-by-frame`) — app-db schemas are NOT a registrar
  kind. The authoritative store is keyed by `(frame-id, path)` so that
  registrations against frame A and frame B against the same path are
  independent entries. Pair-tools and source-coord tests read via
  `app-schema-meta-at`.

  Per rf2-52dfy the `:frame` value is coerced through `coerce-opts`,
  the SAME contract the read surface (`app-schema-at` /
  `app-schema-meta-at` / `app-schemas`) uses for a frame TARGET: a frame-id
  keyword or a frame value names the registration frame, and a malformed
  target throws `:rf.error/app-schemas-bad-arg`.

  Per rf2-sk0ql the `path` must be a `get-in`/`assoc-in`-shaped path: a
  SEQUENTIAL collection of keys, or the empty vector `[]` for the whole-
  `app-db` root (Spec 010 §`app-db` schemas — path-based / §A schema for
  the whole `app-db`). A non-sequential scalar (a bare keyword, string,
  number, nil) throws `:rf.error/app-schema-bad-path` at registration —
  BEFORE the store mutation — so it can never land. Previously such a
  shape registered fine but made `validate-app-schema!`'s `(get-in db
  path)` throw, which the router silently swallowed as a validation pass,
  installing an invalid commit with no failure trace and no rollback and
  poisoning every subsequent commit's validation for the frame."
  [path metadata]
  (let [schema (extract-app-schema-from-metadata path metadata)
        ;; The frame TARGET (if any) rides under `:frame` in the metadata map.
        ;; rf2-5429ec — it is a frame TARGET, NOT the whole opts arg, so lift it
        ;; back into the `{:frame target}` opts shape `resolve-frame` consumes
        ;; (via `frame-target->opts`). This makes the singular registration path
        ;; resolve a target IDENTICALLY to the read surface: a keyword / frame
        ;; value resolves to its frame id; a non-keyword target (a string, a
        ;; vector, or a NON-frame MAP like `{:not :a-frame}`) FAILS LOUD with
        ;; `:rf.error/app-schemas-bad-arg` rather than (the old bug) being read
        ;; as a key-less opts map and silently borrowing the ambient frame.
        frame-opts (frame-target->opts (:frame metadata))]
   ;; Per rf2-sk0ql — reject a malformed `path` shape BEFORE the store
   ;; mutation. A non-sequential scalar (bare keyword / string / number /
   ;; nil) would register fine but make `validate-app-schema!`'s
   ;; `(get-in db path)` throw, which the router silently swallows as a
   ;; validation pass (a correctness- + privacy-relevant bypass). Always-on
   ;; — a malformed registration is a programming error in every build.
   ;; Per rf2-k0ew8n — the SAME gate also hard-rejects a runtime-db path
   ;; (`:rf.runtime/*` / legacy `:rf/runtime` first segment) with the
   ;; distinct `:rf.error/app-schema-runtime-path`. The frame is resolved
   ;; best-effort for the payload so a missing scope cannot mask it.
   (assert-app-schema-path! path (best-effort-frame frame-opts))
   (let [frame-id     (resolve-frame frame-opts)
         ;; EP-0012 §Path shape (rf2-94o54l.2 + rf2-ujmc3u): a
         ;; `reg-app-schema` path is accepted as any sequential collection
         ;; but NORMALIZED to its canonical vector form before it becomes
         ;; the stored key / digest path key, so a list path `(list :a :b)`
         ;; and the equivalent vector `[:a :b]` are ONE identity — the
         ;; side-table key, the `schema-meta` `:path`, the prior-schema
         ;; lookup, and the digest line all derive from the same canonical
         ;; vector. `normalize-concrete` (not bare `normalize`) routes the
         ;; path through the SHARED concrete-segment boundary so a schema
         ;; path is a full `:rf/path` citizen — composite / function / host
         ;; / float / unsafe-integer segments fail closed rather than riding
         ;; into a stored declaration and digest key. The up-front
         ;; `assert-app-schema-path!` already validated the same segment
         ;; domain (and the runtime-db first-segment category); this is the
         ;; canonical-vector producer + a defense-in-depth re-check.
         path         (path/normalize-concrete path)
         ;; The per-frame schema-metadata map (the {path → schema-meta}
         ;; entry value) — `:schema` + `:path` + `:frame` + source-coords.
         ;; Named `schema-meta` to match the slice vocabulary
         ;; (`frame-schema-entries`, `app-schema-meta-at`, the
         ;; `validate-app-schema!` destructure) and to avoid shadowing
         ;; `clojure.core/meta`.
         ;; rf2-wvh95f F2 — the registration is metadata-shaped, so the stored
         ;; schema-meta IS the author's RegistrationMetadata map (`:doc`,
         ;; `:tags`, `:platforms`, … and any open `:my/*` extension keys) with
         ;; the runtime-stamped `:schema` / `:path` / `:frame` overlaid.
         ;; rf2-5429ec (Evidence 2) — previously only `:doc` + `:tags` were
         ;; copied through, silently DROPPING every other RegistrationMetadata
         ;; key (`:platforms`, …) AND any additive / open metadata key, which
         ;; contradicts Spec-Schemas §`AppSchemaMeta` (`[:merge
         ;; RegistrationMetadata [:map :path :schema :frame]]`) and spec/API.md
         ;; §`app-schema-meta-at` ("returns :path, :schema, :frame, source
         ;; coords, and the rest of :rf/registration-metadata"). Start from the
         ;; author map minus the two slots whose stored value is the RESOLVED
         ;; form (`:schema` = the extracted schema; `:frame` = the resolved
         ;; frame-id, not the original frame TARGET), then overlay the resolved
         ;; `:schema` / `:path` / `:frame`. Open-map / future-additive keys ride
         ;; through. Source-coords merge per the production-elision contract.
         schema-meta  (source-coords/merge-coords
                        (assoc (dissoc metadata :schema :frame)
                               :schema schema :path path :frame frame-id))
         ;; Capture the path's prior schema BEFORE the swap so the
         ;; hot-reload `:rf.schema/violation` check (rf2-ee38b.6) can
         ;; compare pre- vs post-reload shapes. nil on first registration.
         ;;
         ;; EP-0015 §8 (rf2-d2r3um): the side-table write is a bare atomic
         ;; `swap!` — no per-frame linearization lock and no schema→elision
         ;; population. Schemas no longer feed the app-db egress registry
         ;; (frame policy owns it), so the two-step off-box-redaction-loss
         ;; race the lock guarded (rf2-naihn1) no longer exists.
         prior-schema (get-in @schemas-by-frame [frame-id path :schema])]
     (swap! schemas-by-frame assoc-in [frame-id path] schema-meta)
     ;; Per rf2-fq7d2: dev-time nudge when `:schemas/malli-validate` is
     ;; unbound AND the framework-default validator is still installed —
     ;; the default soft-passes per Spec 010 §Recommended soft-pass, so a
     ;; reg-app-schema with no validator wired up validates nothing. Note
     ;; that post-rf2-v96fh the facade auto-requires the Malli adapter, so
     ;; this only fires for a substitute-validator port or a test that
     ;; unbinds the hook (see the warning block above). Production elides
     ;; via the outer `interop/debug-enabled?` gate (Spec 009 §Production
     ;; builds). Read-only dev side effects (warnings + a violation trace).
     (when interop/debug-enabled?
       (maybe-warn-validator-unavailable!)
       ;; Per rf2-jsokn: dev-time nudge when the registered schema is
       ;; an opaque non-vector, non-keyword form (compiled m/schema
       ;; object, etc.) — the schema walker can only introspect vector
       ;; Malli EDN, so a per-slot `:sensitive?` flag inside an opaque
       ;; value is silently skipped by the schema-validation-failure
       ;; redactor (EP-0015 §8: `:large?` / `:sensitive?` no longer feed
       ;; the app-db egress registry, but `:sensitive?` still drives
       ;; validation-failure-trace redaction). Production elides via the
       ;; outer `interop/debug-enabled?` gate.
       (maybe-warn-walker-opaque! schema path)
       ;; Per rf2-ee38b.6 / Spec 010 §Schema migration on hot-reload:
       ;; emit `:rf.schema/violation` when a re-registration changes the
       ;; schema and the live app-db value at `path` fails the new shape.
       ;; O(1) — reads the prior schema (captured above) + one validation
       ;; of the live value at this path.
       (maybe-emit-schema-violation! frame-id path prior-schema schema))
     path)))

(defn reg-app-schemas
  "Bulk-register a map of `{path -> schema}` against the active frame
  (or the frame named in `opts`). Per rf2-jzs9 — the plural form of
  `reg-app-schema`, designed for feature-modular apps (per Conventions
  §Feature-modularity prefix convention) where a single feature module
  registers 5–20 schemas under a common path prefix like `[:cart …]` or
  `[:auth …]`.

  Shape:

    (rf/reg-app-schemas {[:auth]                AuthSlice
                         [:auth :login-form]    FormSlice
                         [:cart]                CartSlice
                         [:cart :items]         [:vector CartItem]})

    (rf/reg-app-schemas {[:foo] FooSchema} {:frame :tenant/a})

  Per Spec 010 §Per-frame schemas registration is frame-scoped; EP-0002 —
  context-required frame-local: the `:frame` opt (the *override*) names
  the frame for every entry in the map (you cannot mix frames in a single
  call), else the carried-invariant scope chain resolves it. Registering
  under no scope and no explicit `:frame` raises
  `:rf.error/no-frame-context` (the up-front path-shape sweep still runs
  first).
  The singular form `reg-app-schema` remains available and is used
  internally for each entry — every entry stamps its own per-frame side-
  table entry with source-coords captured from this call site.

  Returns the vector of paths registered, in iteration order. Last-
  write-wins on duplicate paths inside the same map (map iteration
  order in Clojure is small-map literal-order, large-map hash order;
  callers that rely on deterministic order should use a singular
  `reg-app-schema` chain instead)."
  ([path->schema] (reg-app-schemas path->schema {}))
  ([path->schema opts-or-frame-id]
   ;; EP-0015 §8 (rf2-d2r3um): schemas no longer feed the app-db egress
   ;; registry, so there is no per-entry elision repopulation to defer and
   ;; no O(n²) bulk-registration hazard (the rf2-ee38b.6 / rf2-utdxg perf
   ;; machinery is gone). Each delegated `reg-app-schema` does only its own
   ;; atomic side-table `swap!` and its O(1) dev-side checks.
   ;;
   ;; Per rf2-52dfy — `opts` is coerced through the same `coerce-opts`
   ;; contract the read surface uses (bare keyword → `{:frame kw}`
   ;; sugar; bad shape → `:rf.error/app-schemas-bad-arg`). Write/read
   ;; agree; the singular `reg-app-schema` call re-coerces the
   ;; already-coerced map (idempotent — a map passes through verbatim).
   ;; Per rf2-naihn1 — reject a nil / non-map first argument BEFORE any
   ;; mutation (and before the path sweep, which `(keys nil)` would
   ;; silently no-op through). `(reg-app-schemas nil)` previously
   ;; registered nothing and returned `[]` — indistinguishable from the
   ;; `{}` no-op — so a schema-loader bug passing nil got a false green
   ;; with schema enforcement silently disabled. `{}` stays the
   ;; documented empty no-op.
   (assert-bulk-schemas-arg! path->schema)
   ;; Per rf2-sk0ql — validate EVERY path shape up front, before any
   ;; store mutation, so a single malformed key cannot half-register the
   ;; batch (the earlier-iterated entries would otherwise land before the
   ;; bad key's delegated `reg-app-schema` throws). Reject the whole call
   ;; atomically. (`reg-app-schema` re-asserts per entry — cheap and
   ;; idempotent — but this up-front sweep is what guarantees the
   ;; all-or-nothing contract.) Per rf2-k0ew8n the same sweep also rejects
   ;; a runtime-db path key (`:rf.error/app-schema-runtime-path`) before
   ;; any mutation, so a batch with one runtime path lands NOTHING.
   (let [batch-frame (best-effort-frame opts-or-frame-id)]
     (run! #(assert-app-schema-path! % batch-frame) (keys path->schema)))
   ;; EP-0015 §8 (rf2-d2r3um): no per-frame registration lock and no
   ;; deferred elision repopulation. Schemas no longer feed the app-db
   ;; egress registry (frame policy owns it), so each delegated
   ;; `reg-app-schema` is just its own atomic side-table `swap!` plus its
   ;; O(1) dev-side checks (validator nudge, walker-opaque nudge, hot-reload
   ;; `:rf.schema/violation`). The opts pass through unchanged.
   ;; rf2-wvh95f F2 — the singular `reg-app-schema` now takes the schema in a
   ;; metadata map (:schema-in-metadata). The bulk plural keeps its natural
   ;; `{path -> schema}` shape (the map value IS the schema — there is no
   ;; positional-vs-metadata ambiguity in a bulk map); each entry is delegated
   ;; by wrapping its schema into the metadata map alongside the shared :frame.
   ;; `opts-or-frame-id` is coerced through the SAME `coerce-opts` contract the
   ;; read surface uses (bare keyword / frame value / `{:frame …}` opts map all
   ;; normalise to `{:frame <target>}`), so the per-entry `:frame` is the
   ;; resolved target regardless of which opts shape the caller passed.
   (let [frame-target (:frame (coerce-opts opts-or-frame-id))]
     (mapv (fn [[path schema]]
             (reg-app-schema path (cond-> {:schema schema}
                                    (some? frame-target) (assoc :frame frame-target))))
           path->schema))))

(defn app-schema-at
  "Look up the registered schema for a path in a frame, or nil.

  Arities:
    (app-schema-at path)         ;; carried-invariant scope frame (EP-0002)
    (app-schema-at path opts)    ;; opts map; :frame names the frame
                                 ;; (keyword sugar also accepted)

  Per Spec 010 §Schemas as a tooling and agent surface."
  ([path] (app-schema-at path {}))
  ([path opts-or-frame-id]
   ;; EP-0012 §Path shape (rf2-94o54l.2 + rf2-ujmc3u): normalize the lookup
   ;; path to its canonical vector THROUGH the shared concrete-segment
   ;; boundary so a `(list :a :b)` read finds the entry stored under the
   ;; equivalent `[:a :b]` key (registration normalizes the same way) —
   ;; storage and lookup AGREE on one canonical-vector identity — and a
   ;; bad-segment lookup (composite / host / float / unsafe-integer) fails
   ;; closed loudly rather than silently missing.
   (let [frame-id (coerce->frame-id opts-or-frame-id)
         path     (path/normalize-concrete path)]
     (when-let [m (get-in @schemas-by-frame [frame-id path])]
       (:schema m)))))

(defn app-schema-meta-at
  "Return the registration metadata map for a path in a frame, or nil.

  Unlike `app-schema-at` (which returns just the `:schema` value), this
  returns the full meta map stamped at `reg-app-schema` — including
  source-coords (`:ns` / `:line` / `:file`), `:path`, `:schema`, and
  `:frame`. Used by pair-tools, 10x panels, and source-coord tests that
  need to introspect where a schema was registered.

  Per rf2-0frdi / rf2-cq1ak this is the canonical read surface for
  app-db schema metadata — app-db schemas are NOT a registrar kind;
  the per-frame side-table is the single source of truth.

  Arities:
    (app-schema-meta-at path)         ;; carried-invariant scope frame (EP-0002)
    (app-schema-meta-at path opts)    ;; opts map; :frame names the frame
                                      ;; (keyword sugar also accepted)"
  ([path] (app-schema-meta-at path {}))
  ([path opts-or-frame-id]
   ;; EP-0012 §Path shape (rf2-94o54l.2 + rf2-ujmc3u): normalize the lookup
   ;; path THROUGH the shared concrete-segment boundary so a non-vector seq
   ;; read resolves to the canonically-stored vector entry (registration
   ;; normalizes the key the same way) and a bad-segment lookup fails closed.
   (let [frame-id (coerce->frame-id opts-or-frame-id)
         path     (path/normalize-concrete path)]
     (get-in @schemas-by-frame [frame-id path]))))

(defn app-schemas
  "Return every registered `app-schema-at` declaration for a frame as a
  `{path → schema}` map. Pair tools and 10x panels read this to
  introspect what schemas apply in a given frame.

  Arities:

    (app-schemas)              ;; sugar for (app-schemas {})
    (app-schemas frame-id)     ;; sugar for (app-schemas {:frame frame-id})
    (app-schemas opts)         ;; opts is a map; supports {:frame ...}
                               ;; and is the place future opts will land

  Per Spec 010 §Per-frame schemas the result is the schema set
  registered against the named frame (active frame when none is
  given). Schemas registered against a different frame do not appear."
  ([] (app-schemas {}))
  ([opts-or-frame-id]
   (let [frame-id (coerce->frame-id opts-or-frame-id)]
     (reduce-kv (fn [acc path m] (assoc acc path (:schema m)))
                {}
                (get @schemas-by-frame frame-id {})))))

(defn frame-schema-entries
  "Cross-artefact seam — consumed by `re-frame.elision` and
  `re-frame.epoch` via the `:schemas/frame-schema-entries` late-bind
  hook. Returns the `{path → schema-meta}` map for a frame, or `{}`."
  [frame-id]
  (get @schemas-by-frame frame-id {}))

;; ---- hot-reload semantics ------------------------------------------------
;;
;; Per rf2-0frdi the schemas artefact no longer participates in the
;; registrar's replacement-hook stream — `reg-app-schema` writes only to
;; `schemas-by-frame`. Re-registering a `(frame-id, path)` entry is an
;; ordinary `swap!`: the new meta replaces the prior entry atomically, and
;; the validation hot path (`frame-schema-entries`) picks up the new
;; schema on its next read. The per-frame map IS the validation cache.
;;
;; One dev-only side effect accompanies a re-registration (rf2-ee38b.6):
;;   `:rf.schema/violation` — emitted when the schema CHANGED and the live
;;   `app-db` value at the path fails the new shape (Spec 010 §Schema
;;   migration on hot-reload). See `maybe-emit-schema-violation!`. Gated on
;;   `interop/debug-enabled?` and DCE'd in production.
;;
;; EP-0015 §8 (rf2-d2r3um): a re-registration no longer refreshes any
;; schema-derived app-db elision declarations — schemas do not feed the
;; app-db egress registry; durable app-db classification is frame-owned.

;; ---- test-support snapshot / restore -------------------------------------
;;
;; Consumed by re-frame.test-support's make-reset-runtime-fixture via the
;; `:schemas/{snapshot-by-frame,restore-by-frame!,clear-by-frame!}`
;; late-bind hooks. The fixture captures and restores the per-frame
;; registry around each test; when the schemas artefact is not on the
;; classpath the hooks are nil and the fixture no-ops the schema steps.

(defn snapshot-schemas-by-frame
  "Return a snapshot value of the per-frame schema registry."
  []
  @schemas-by-frame)

(defn restore-schemas-by-frame!
  "Reset the per-frame schema registry to the supplied snapshot."
  [snap]
  (reset! schemas-by-frame snap))

(defn clear-schemas-by-frame!
  "Reset the per-frame schema registry to `{}`. Used by test fixtures
  and by `make-reset-runtime-fixture`'s `:clear-app-schemas? true`
  path (rf2-cq1ak). The per-frame registry is the schemas artefact's
  only mutable registration state — there is no companion side-table to
  clear (the registration linearization lock was removed with the
  schema→elision population, EP-0015 §8, rf2-d2r3um)."
  []
  (reset! schemas-by-frame {}))

(defn on-frame-destroyed!
  "Per Spec 002 §Destroy: drop every schema registered against the
  destroyed frame so a subsequent `reg-frame` of the same id starts
  with a clean schema slate. Called from `frame/destroy-frame!`
  through the `:schemas/on-frame-destroyed!` late-bind hook
  (mirrors the `:machines/on-frame-destroyed!` /
  `:ssr/on-frame-destroyed` cleanup contract).

  Without this cleanup, schemas registered against a destroyed
  frame would persist and re-fire when the frame is re-registered
  — under the rf2-wkxng / rf2-6m0se rollback contract that
  manifests as spurious rollbacks against orphan paths the new
  frame's :initial-events never wrote. Idempotent — a missing frame
  entry is a no-op `dissoc`."
  [frame-id]
  (swap! schemas-by-frame dissoc frame-id))
