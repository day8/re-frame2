(ns re-frame.std-interceptors
  "Standard interceptors. Per Spec 002 / API.md §Standard interceptors and
  Spec 001 §Hot-reload semantics M-21.

  Ships ONE framework-standard interceptor plus the ->interceptor primitive:
    :rf.interceptor/path — focus a handler on an app-db sub-slice, referenced
                           as `[:rf.interceptor/path <path-vector>]` (this ns)

  (The legacy `unwrap-interceptor` VALUE is REMOVED — EP-0022 / rf2-3qeu38 —
  the framework ships no standard unwrap; the canonical spelling is
  handler-payload destructuring, or a project-registered `:app/unwrap`. A
  stale `rf/unwrap-interceptor` reference hits the `^:no-doc` throwing stub
  `unwrap-removed!` below, which names those replacements.)

  (Coeffects are no longer wired by a `inject-cofx` interceptor — they
  are declared via `:rf.cofx/requires` on the handler and delivered flat;
  EP-0017, no interceptor surface.)

  The principle: keep helpers that do specific, non-trivial work; drop
  those that are just (->interceptor :before f) or (->interceptor :after f)
  with no other logic. Custom before/after work uses ->interceptor directly.

  EP-0022 (Slice C, rf2-0adhqs.3): this ns registers the framework-standard
  `:rf.interceptor/path` interceptor as a `:factory`, referenced as
  `[:rf.interceptor/path <path-vector>]`. Its `:factory` builds the FULL
  standard-path interceptor (`standard-path-interceptor`) implementing the
  normative rules 1-5 of [Spec 002 §Standard
  `:rf.interceptor/path`](../../../spec/002-Frames.md), notably **rule 4**:
  when the handler emits a `:db` effect whose focused value is `identical?`
  to the original focused slice, the interceptor widens back to the
  ORIGINAL full app-db OBJECT (not an `assoc-in` allocation), preserving
  the frame-commit `identical?` no-op (rf2-ekq28v). A non-vector / malformed
  path argument is `:rf.error/path-interceptor-bad-path`.

  EP-0022 removed the public `rf/path` VALUE constructor (EP-0022:552/932):
  there is no public path value-builder, only the `[:rf.interceptor/path
  <path-vector>]` ref. The legacy `path` fn that the removed facade var aliased
  is gone (rf2-dgtdna); a stale `(rf/path …)` call hits the `^:no-doc` throwing
  stub `path-removed!` below, which names the ref form as the replacement. The
  legacy value also had WEAKER semantics — its `:after` always re-spliced via
  `assoc-in` when a `:db` effect was present, allocating a fresh top-level map
  even for an UNCHANGED slice and defeating the rule-4 commit no-op
  (rf2-ekq28v). The surviving `standard-path-interceptor` (the factory's
  consumer) is the one path surface and is the carrier of that no-op."
  (:require [re-frame.interceptor :as interceptor]
            [re-frame.interceptor-registry :as icpt-reg]
            [re-frame.image-assembly :as image-assembly]
            [re-frame.error :as error]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- path: the removed value constructor (EP-0022, rf2-dgtdna) -------------
;;
;; EP-0022 (accepted) removed the public `rf/path` VALUE constructor: there is
;; NO public path value-builder, only the framework-registered factory ref
;; `[:rf.interceptor/path <path-vector>]` (EP-0022:552 "There is no public
;; rf/path value constructor."; :932 lists the removal). The implementation had
;; DRIFTED — it kept exporting `rf/path` aliased to a legacy `path` fn here —
;; and that fn was a footgun: its `:after` always re-spliced via `assoc-in`
;; when a `:db` effect was present, allocating a fresh top-level map even for an
;; UNCHANGED slice, defeating the rf2-ekq28v frame-commit `identical?` no-op
;; the canonical `standard-path-interceptor` (below) preserves. rf2-dgtdna
;; removes the legacy fn and replaces the facade var with a `^:no-doc` throwing
;; stub (the project's actionable-removed-API pattern, like
;; `:rf.error/inject-cofx-removed` / `reg-event-db-removed`).
;;
;; The stub throws the canonical `re-frame.error/throw-error!` hard error
;; naming the replacement ref, so a stale `(rf/path …)` call fails LOUDLY and
;; actionably rather than as an opaque "no such var" or a working footgun. It
;; does NOT fan out onto the always-on error-emit channel: unlike the
;; reg-event / inject-cofx removed stubs (whose Spec 009 §Error event catalogue
;; rows + always-on conformance pins were authored alongside them), this is an
;; implementation-only drift fix (API.md + EP-0022 already correct) and rides
;; no catalogued category — just the actionable throw.

;; ---- data-driven removed std-interceptor values (rf2-ne2uk8) ---------------
;;
;; The two removed standard interceptor values (`path` / `unwrap-interceptor`)
;; are described ONCE in this table, not as two bespoke throwers. Unlike the
;; EP-0018 reg-event removed names (which fan out on the always-on error
;; channel + dev trace bus, per their Spec 009 catalogue rows), these are
;; implementation-only drift fixes (API.md + EP-0022 were already correct) that
;; ride no catalogued 009 category — they JUST throw the actionable
;; `re-frame.error/throw-error!` hard error. The table holds each row's
;; behavioural facts; the per-name stubs below are thin lookups, so adding /
;; retiring a removed standard value is a one-row edit and the audit surface is
;; one literal vector. `:msg` / `:extra` are `(fn [args] …)` so a row can weave
;; the call args into its message + ex-data (only `path` needs the path vec).

(def ^:private removed-std-interceptor-values
  "The EP-0022 removed standard interceptor VALUES, one row each — the audit
  surface for the removed std-interceptor names (rf2-ne2uk8). A row is
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
                     "`[<id> <payload-map>]` payload in the handler arglist — "
                     "`(fn [_ {:keys [a b]}] …)` — or register a project "
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
      (error/throw-error! error-kw where (msg args) {:extra ex})
      (error/throw-error! error-kw where (msg args)))))

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
  EP-0022 §Registered interceptors, and docs/api/15-removed.md."
  [& path-segs]
  (raise-removed-std-interceptor! (get removed-std-interceptor-by-sym 'path) path-segs))

;; ---- unwrap: the removed standard value (EP-0022, rf2-3qeu38) --------------
;;
;; EP-0022 (accepted) ships NO framework-standard unwrap value
;; (docs/EP/EP-0022-registered-interceptors.md:53-55 "no standard unwrap";
;; :555-578 §"No standard unwrap"; :881/:932 list the removal). The canonical
;; spelling for the M-19 `[<id> <payload-map>]` shape is handler-payload
;; destructuring in the handler arglist; a project that genuinely needs
;; chain-wide event reshaping registers its OWN `:app/unwrap` interceptor with
;; project `:before` / `:after`. The implementation had DRIFTED — it kept an
;; `unwrap-interceptor` VALUE here and exported it from the facade — so
;; rf2-3qeu38 removes the value (the stash/restore-`:event`-in-`:after`
;; machinery and its `:rf.error/unwrap-bad-event-shape` emit site go with it)
;; and replaces the facade var with the `^:no-doc` throwing stub below (the
;; `rf/path` twin under rf2-dgtdna; the project's actionable-removed-API
;; pattern, like the EP-0018 `reg-event-db` / EP-0017 `inject-cofx` stubs).
;;
;; Like `path-removed!`, the stub throws the canonical
;; `re-frame.error/throw-error!` hard error naming the replacement, so a stale
;; `(rf/unwrap-interceptor …)` reference fails LOUDLY and actionably. It does
;; NOT fan out onto the always-on error-emit channel and rides no catalogued
;; 009 category — just the actionable throw (API.md + EP-0022 already correct;
;; this is an implementation-only drift fix).

(defn ^:no-doc unwrap-removed!
  "REMOVED in EP-0022 (no alias). The framework ships no standard `unwrap`
  value; the canonical spelling for the M-19 `[<id> <payload-map>]` shape is
  handler-payload destructuring in the handler arglist, or a project-registered
  `:app/unwrap` interceptor for genuine chain-wide reshaping. Referencing
  `rf/unwrap-interceptor` is the hard error `:rf.error/unwrap-removed`, naming
  those replacements. See spec/API.md §Standard interceptors,
  EP-0022 §Registered interceptors, and docs/api/15-removed.md."
  [& _args]
  (raise-removed-std-interceptor!
    (get removed-std-interceptor-by-sym 'unwrap-interceptor) _args))

;; ---- standard :rf.interceptor/path (EP-0022 Slice C — FULL contract) -------
;;
;; The framework-standard `:rf.interceptor/path` interceptor, referenced as
;; `[:rf.interceptor/path <path-vector>]` and built by the registered
;; `:factory` (`path-factory`). It implements the FULL normative contract of
;; Spec 002 §Standard `:rf.interceptor/path` (rules 1-5), notably RULE 4: an
;; unchanged focused slice widens back to the ORIGINAL full app-db OBJECT so
;; the frame-commit `identical?` no-op (rf2-ekq28v) is preserved.
;;
;; This is the ONE path surface (the removed legacy `path` fn / `rf/path` value
;; builder only short-circuited the no-`:db` case; it still did
;; `(assoc-in original-db path slice)` when a `:db` effect was present,
;; allocating a fresh top-level map even for an unchanged slice — which defeated
;; the commit no-op, and is why EP-0022 removed it; rf2-dgtdna). The standard
;; interceptor knows BOTH the original full app-db object AND the original
;; focused slice, so it can detect the unchanged-slice case and re-emit the
;; original object.

(defn- throw-bad-path!
  "Throw the canonical `:rf.error/path-interceptor-bad-path` error (Spec 002
  §Standard `:rf.interceptor/path` / §Error model). Raised by the factory when
  the `[:rf.interceptor/path <path-vector>]` ref carries a non-vector or
  otherwise malformed path argument."
  [path-vector reason]
  (error/throw-error!
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
  (interceptor/->interceptor*
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
;; preserves it (`image-assembly/standard-replaceable?`); see EP-0023 §Image
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

    * the EP-0023 FRAMEWORK-STANDARD registry (`image-assembly/register-standard!`)
      — so the standard descriptor is unioned into EVERY resolved image
      generation. Without this, an image-loaded frame whose event references
      `[:rf.interceptor/path …]` under a bound `*generation*` could not resolve
      it (generation-routed `lookup` reads ONLY the generation's resolver — no
      fallback to the registrar atom), and the `:replace-standard` /
      invariant-coupled-standard machinery would be dead code (no standard would
      ever sit in a generation to protect). The standard descriptor carries the
      SAME `:rf/interceptor-descriptor` slot the regular registrar stores, so a
      generation-routed resolution returns a byte-shape-identical value.

  The standard is marked NON-replaceable and INVARIANT-COUPLED via
  `:rf.standard/requires-conformance #{:rf.interceptor.path/commit-identical-no-op}`
  (the rule-4 frame-commit `identical?` no-op, rf2-ekq28v): an image MUST NOT
  silently shadow it, and `:replace-standard` against it FAILS LOUD
  (`:rf.error/image-standard-replacement-forbidden`) until a conformance profile
  proves a replacement preserves the invariant (EP-0023 §Image Patching And
  Overrides; `image-assembly/standard-replaceable?`)."
  []
  (icpt-reg/reg-interceptor*
    :rf.interceptor/path
    path-interceptor-metadata
    path-interceptor-descriptor)
  ;; EP-0023: contribute the SAME standard descriptor into the framework-standard
  ;; registry so it is unioned into every resolved image generation. The
  ;; descriptor carries `:rf/interceptor-descriptor` (the factory) so a
  ;; generation-routed `interceptor-registry/resolve-ref` reads it identically to
  ;; the registrar path. Marked invariant-coupled (non-replaceable until a
  ;; conformance profile exists — rf2-32siq3.41).
  (image-assembly/register-standard!
    :interceptor :rf.interceptor/path
    (assoc path-interceptor-metadata
           :rf/interceptor-descriptor path-interceptor-descriptor
           :rf.standard/requires-conformance #{path-conformance-invariant}))
  nil)

;; Register at namespace load so standalone require'rs (no init!) get the
;; standard refs; `init!` re-registers (idempotent) for the post-clear-all!
;; test path.
(register-standard-interceptors!)
