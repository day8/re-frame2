(ns re-frame.std-interceptors
  "Standard interceptors. Per Spec 002 / API.md §Standard interceptors and
  Spec 001 §Hot-reload semantics M-21.

  Ships one framework-standard interceptor:
    :rf.interceptor/path — focus a handler on an app-db sub-slice, referenced
                           as `[:rf.interceptor/path <path-vector>]` (this ns)

  The registered `:factory` builds `standard-path-interceptor`, implementing
  the rules in [Spec 002 §Standard
  `:rf.interceptor/path`](../../../spec/002-Frames.md). In particular, when
  the handler emits a `:db` effect whose focused value is `identical?`
  to the original focused slice, the interceptor widens back to the
  original full app-db object rather than allocating with `assoc-in`. This
  preserves the frame commit's identity-based no-op. A non-vector or malformed
  path argument is `:rf.error/path-interceptor-bad-path`.

  `rf/path` and `rf/unwrap-interceptor` survive only as `^:no-doc` throwing
  stubs that provide migration guidance. Coeffects are declared with
  `:rf.cofx/requires`, not represented as interceptors."
  (:require [re-frame.interceptor :as rf.interceptor]
            [re-frame.interceptor-registry :as rf.interceptor-registry]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.error :as rf.error]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- removed standard values ---------------------------------------------
;;
;; The two removed standard interceptor values (`path` / `unwrap-interceptor`)
;; are described once in this table. They raise actionable hard errors but do
;; not fan out through the always-on error channel. `:msg` and `:extra` are fns
;; so the path row can include the supplied path in its diagnostic.

(def ^:private removed-std-interceptor-values
  "The removed standard interceptor values, one row each. A row is
  `{:sym :error-kw :where :msg :extra}`:
    - `:sym`      the bare var symbol exported `^:no-doc` from this ns and
                  aliased onto the `re-frame.core` facade;
    - `:error-kw` the exact `:rf.error/*` id the stub raises;
    - `:where`    the `'rf/<name>` symbol the hard error attributes to;
    - `:msg`      `(fn [args] reason-string)` — the actionable replacement
                  guidance (woven with the call args where it matters);
    - `:extra`    `(fn [args] map)` — the `:extra` ex-data payload (or `nil`)."
  [{:sym      'path
    :error-kw :rf.error/path-removed
    :where    'rf/path
    :msg      (fn [args]
                (str "`rf/path` is REMOVED in EP-0022 (no public path value "
                     "constructor). Reference the framework-standard path "
                     "interceptor by id in the handler's `:interceptors` chain: "
                     "`{:interceptors [[:rf.interceptor/path "
                     (pr-str (vec args)) "]]}`."))
    :extra    (fn [args] {:got (vec args)})}
   {:sym      'unwrap-interceptor
    :error-kw :rf.error/unwrap-removed
    :where    'rf/unwrap-interceptor
    :msg      (fn [_args]
                (str "`rf/unwrap-interceptor` is REMOVED in EP-0022 (no "
                     "framework-standard unwrap value). Destructure the "
                     "`[<id> <payload-map>]` event in the handler arglist — "
                     "`(fn [_ [_ {:keys [a b]}]] …)` — or register a project "
                     "`:app/unwrap` interceptor with your own `:before` / "
                     "`:after` for genuine chain-wide reshaping."))
    :extra    (fn [_args] nil)}])

(def ^:private removed-std-interceptor-by-sym
  "`removed-std-interceptor-values` indexed by bare `:sym` for stub lookup."
  (into {} (map (juxt :sym identity)) removed-std-interceptor-values))

(defn- raise-removed-std-interceptor!
  "Throw the EP-0022 removed-value hard error for `row` (one entry of
  `removed-std-interceptor-values`) on a stale reference carrying `args`.
  Resolves the row's `:msg` / `:extra` fns against the args, then throws via
  the canonical `re-frame.error/throw-error!`. Does NOT fan out on the
  error-emit channel (these ride no catalogued 009 category — the loud throw
  IS the migration alarm). Every stub delegates here so the throw path + the
  per-name facts live in ONE place (the data table)."
  [row args]
  (let [{:keys [error-kw where msg extra]} row
        ex (extra args)]
    (if (some? ex)
      (rf.error/throw-error! error-kw where (msg args) {:extra ex})
      (rf.error/throw-error! error-kw where (msg args)))))

;; The removed standard values survive as thin `^:no-doc` facade stubs — each
;; body is a one-line lookup into `removed-std-interceptor-values`, so the
;; behavioural facts (error id, attributed symbol, message, ex-data) live ONCE
;; in the table rather than in two hand-maintained bodies. Plain `defn`s (the
;; data table, not codegen, is what makes this data-driven), each `^:no-doc`
;; (dropped from the manifest generator + CLJS publics probe — no manifest row).

(defn ^:no-doc path-removed!
  "REMOVED in EP-0022 (no alias). The public `rf/path` VALUE constructor is
  gone; the one path surface is the framework-registered factory ref
  `[:rf.interceptor/path <path-vector>]` in a handler's `:interceptors` chain.
  Calling `rf/path` is the hard error `:rf.error/path-removed`, naming that ref
  as the replacement. See spec/API.md §Standard interceptors,
  EP-0022 §Registered interceptors, and migration/from-re-frame-v1/README.md."
  [& path-segs]
  (raise-removed-std-interceptor! (get removed-std-interceptor-by-sym 'path) path-segs))

(defn ^:no-doc unwrap-removed!
  "REMOVED in EP-0022 (no alias). The framework ships no standard `unwrap`
  value; the canonical spelling for the M-19 `[<id> <payload-map>]` shape is
  handler-payload destructuring in the handler arglist, or a project-registered
  `:app/unwrap` interceptor for genuine chain-wide reshaping. Referencing
  `rf/unwrap-interceptor` is the hard error `:rf.error/unwrap-removed`, naming
  those replacements. See spec/API.md §Standard interceptors,
  EP-0022 §Registered interceptors, and migration/from-re-frame-v1/README.md."
  [& _args]
  (raise-removed-std-interceptor!
    (get removed-std-interceptor-by-sym 'unwrap-interceptor) _args))

;; ---- standard :rf.interceptor/path ----------------------------------------
;;
;; The registered factory builds this interceptor from
;; `[:rf.interceptor/path <path-vector>]`. It retains both the original full
;; app-db object and the focused slice so an unchanged slice can widen back to
;; the original object, preserving the commit boundary's identity no-op.

(defn- throw-bad-path!
  "Throw the canonical `:rf.error/path-interceptor-bad-path` error (Spec 002
  §Standard `:rf.interceptor/path` / §Error model). Raised by the factory when
  the `[:rf.interceptor/path <path-vector>]` ref carries a non-vector or
  otherwise malformed path argument."
  [path-vector reason]
  (rf.error/throw-error!
    :rf.error/path-interceptor-bad-path
    :rf.interceptor/path
    reason
    {:recovery :fix-path
     :extra    {:got      path-vector
                :expected "an EDN vector naming a concrete app-db path, e.g. [:cart :items]"}}))

(defn standard-path-interceptor
  "Build the framework-standard `:rf.interceptor/path` interceptor focusing the
  handler on the app-db sub-slice at `path-vector`. Implements the normative
  rules of [Spec 002 §Standard `:rf.interceptor/path`]:

    1. records the original full app-db object AND the original focused slice
       (stacked, so nested path interceptors compose);
    2. stages the focused slice as the handler's `:db` coeffect;
    3. if the handler emits NO `:db` effect, emits no synthetic `:db` effect;
    4. if the handler emits a `:db` effect whose focused value is `identical?`
       to the original focused slice, rewrites the effect back to the ORIGINAL
       full app-db OBJECT (NOT an `assoc-in` allocation) — preserving the
       frame-commit `identical?` no-op (rf2-ekq28v);
    5. otherwise widens (`assoc-in`) the focused value into the original app-db
       at `path-vector`.

  `path-vector` MUST be a vector (validated by the factory below, which throws
  `:rf.error/path-interceptor-bad-path` otherwise). The root path `[]` focuses
  the whole app-db (`get-in db [] = db`, `assoc-in db [] x = x` via the empty-
  path special-case below)."
  [path-vector]
  (rf.interceptor/->interceptor*
    :id    :rf.interceptor/path
    :path  path-vector
    :before
    (fn [ctx]
      (let [original-db (:db (:coeffects ctx))
            ;; The root path `[]` focuses the whole db (get-in returns db for []).
            focused     (if (seq path-vector)
                          (get-in original-db path-vector)
                          original-db)]
        (-> ctx
            ;; Rule 1: stack the original full app-db AND the original focused
            ;; slice (a pair) so nested path interceptors unwind correctly and
            ;; rule 4 can compare against the slice this `:before` focused.
            ;; Reserved-namespace slot (Conventions §Reserved namespaces).
            (update :rf.interceptor.path/stack (fnil conj [])
                    [original-db focused])
            ;; Rule 2: stage the focused slice as the handler's :db coeffect.
            (assoc-in [:coeffects :db] focused))))
    :after
    (fn [ctx]
      ;; Guard: only unwind when our `:before` pushed (an EARLIER interceptor's
      ;; `:before` throw short-circuits downstream `:before` yet still runs every
      ;; `:after` in reverse — Spec 002 rule 2). No stack → no-op teardown.
      (let [stack (:rf.interceptor.path/stack ctx)]
        (if (empty? stack)
          ctx
          (let [[original-db original-slice] (peek stack)
                new-stack (pop stack)
                emitted?  (contains? (:effects ctx) :db)]
            (cond-> (assoc ctx :rf.interceptor.path/stack new-stack)
              ;; Rule 3 is the absence of this branch — no `:db` effect means
              ;; no synthetic `:db` effect is written.
              emitted?
              (assoc-in [:effects :db]
                        (let [emitted-slice (get-in ctx [:effects :db])]
                          (if (identical? emitted-slice original-slice)
                            ;; Rule 4: unchanged focused slice → re-emit the
                            ;; ORIGINAL full app-db object (identity preserved,
                            ;; commit no-op intact). No allocation.
                            original-db
                            ;; Rule 5: widen the changed slice back into the
                            ;; original app-db at path-vector. The root path
                            ;; `[]` replaces the whole db (assoc-in with [] is
                            ;; ill-defined, so special-case it).
                            (if (seq path-vector)
                              (assoc-in original-db path-vector emitted-slice)
                              emitted-slice)))))))))))

;; ---- standard interceptor registration ------------------------------------

(defn- path-factory
  "The `:rf.interceptor/path` factory: receives the one `path-vector` arg and
  returns the FULL standard-path interceptor. A non-vector / malformed path
  argument fails closed with `:rf.error/path-interceptor-bad-path` (Spec 002
  §Standard `:rf.interceptor/path`). The returned interceptor's `:id` is
  `:rf.interceptor/path` (the registry resolver also re-stamps it)."
  [path-vector]
  (when-not (vector? path-vector)
    (throw-bad-path! path-vector
                     "the path argument of [:rf.interceptor/path <path-vector>] must be a vector"))
  (standard-path-interceptor path-vector))

(def path-interceptor-descriptor
  "The `{:factory path-factory}` descriptor the `:rf.interceptor/path` standard
  registers under (the `reg-interceptor*` authoring input). A def so BOTH the
  regular-registrar registration AND the EP-0023 framework-standard registry
  registration carry the SAME factory object — so a generation-routed
  `registrar/lookup` and a registrar-atom `lookup` resolve the standard path
  interceptor identically (rf2-32siq3.41)."
  {:factory path-factory})

(def path-interceptor-metadata
  "The Spec 001 registration metadata the `:rf.interceptor/path` standard ships.
  Shared by the regular-registrar `reg-interceptor*` and the EP-0023
  framework-standard registry descriptor so both surfaces carry identical
  `:rf/interceptor-descriptor` slots (rf2-32siq3.41)."
  {:doc "Framework-standard path interceptor (EP-0022). Focuses an event
        handler on an app-db sub-slice at the given path-vector; the handler
        sees/returns only the slice, spliced back into full app-db.
        Referenced as `[:rf.interceptor/path <path-vector>]`."})

;; EP-0023 framework-standard registry — the named invariant the
;; `:rf.interceptor/path` standard is coupled to (Spec 002 §Standard
;; `:rf.interceptor/path` rule 4 / rf2-ekq28v): an unchanged focused slice
;; widens back to the ORIGINAL full app-db OBJECT so the frame-commit
;; `identical?` no-op is preserved. The invariant-coupled lock keeps the
;; standard non-replaceable until a conformance profile proves a replacement
;; preserves it (`rf.image-assembly/standard-replaceable?`); see EP-0023 §Image
;; Patching And Overrides and `image_assembly.cljc`'s `:rf.standard/*` keys.
(def ^:private path-conformance-invariant
  :rf.interceptor.path/commit-identical-no-op)

(defn register-standard-interceptors!
  "Register the framework-standard interceptors (currently only
  `:rf.interceptor/path`) into the active registrar AND the EP-0023
  framework-standard registry. Idempotent — called at namespace load AND from
  `re-frame.core/init!` so the standard refs survive a test fixture's
  `registrar/clear-all!` (which wipes the `:interceptor` kind along with
  everything else). Mirrors how the reserved fx survive via defmethod — the
  standard interceptors re-seed here on every boot.

  TWO surfaces, ONE descriptor (rf2-32siq3.41):

    * the REGULAR registrar (`reg-interceptor*`) — the default-image /
      no-generation resolution path, where `registrar/lookup` reads the
      registrar atom directly;

    * the EP-0023 FRAMEWORK-STANDARD registry (`rf.image-assembly/register-standard!`)
      — so the standard descriptor is unioned into EVERY resolved image
      generation. Without this, an image-loaded frame whose event references
      `[:rf.interceptor/path …]` under a bound `*generation*` could not resolve
      it (generation-routed `lookup` reads ONLY the generation's resolver — no
      fallback to the registrar atom), and the framework-standard protection
      machinery would be dead code (no standard would ever sit in a generation to
      protect). The standard descriptor carries the SAME
      `:rf/interceptor-descriptor` slot the regular registrar stores, so a
      generation-routed resolution returns a byte-shape-identical value.

  The standard is marked NON-replaceable and INVARIANT-COUPLED via
  `:rf.standard/requires-conformance #{:rf.interceptor.path/commit-identical-no-op}`
  (the rule-4 frame-commit `identical?` no-op, rf2-ekq28v): a public app image
  MUST NOT shadow it — a collision FAILS LOUD
  (`:rf.error/image-standard-replacement-forbidden`). EP-0026 §Framework Standard
  Registrations: standards are protected, and there is NO public
  `:replace-standard` opt-in; the standard owner's internal define/revise path
  reads `rf.image-assembly/standard-replaceable?` and a conformance profile would
  lift the invariant-coupled lock."
  []
  (rf.interceptor-registry/reg-interceptor*
    :rf.interceptor/path
    path-interceptor-metadata
    path-interceptor-descriptor)
  ;; EP-0023: contribute the SAME standard descriptor into the framework-standard
  ;; registry so it is unioned into every resolved image generation. The
  ;; descriptor carries `:rf/interceptor-descriptor` (the factory) so a
  ;; generation-routed `interceptor-registry/resolve-ref` reads it identically to
  ;; the registrar path. Marked invariant-coupled (non-replaceable until a
  ;; conformance profile exists — rf2-32siq3.41).
  (rf.image-assembly/register-standard!
    :interceptor :rf.interceptor/path
    (assoc path-interceptor-metadata
           :rf/interceptor-descriptor path-interceptor-descriptor
           :rf.standard/requires-conformance #{path-conformance-invariant}))
  nil)

;; Register at namespace load so standalone require'rs (no init!) get the
;; standard refs; `init!` re-registers (idempotent) for the post-clear-all!
;; test path.
(register-standard-interceptors!)
