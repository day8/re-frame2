(ns re-frame.std-interceptors
  "Standard interceptors. Per Spec 002 / API.md §Standard interceptors and
  Spec 001 §Hot-reload semantics M-21.

  Ships TWO specific helpers plus the ->interceptor primitive:
    path        — focus a handler on an app-db sub-slice (this ns)
    unwrap      — assert [id payload-map] event shape (this ns)

  (Coeffects are no longer wired by a `inject-cofx` interceptor — they
  are declared via `:rf.cofx/requires` on the handler and delivered flat;
  EP-0017, no interceptor surface.)

  The principle: keep helpers that do specific, non-trivial work; drop
  those that are just (->interceptor :before f) or (->interceptor :after f)
  with no other logic. Custom before/after work uses ->interceptor directly.

  EP-0022 (Slice C, rf2-0adhqs.3): this ns also registers the
  framework-standard `:rf.interceptor/path` interceptor as a `:factory`,
  referenced as `[:rf.interceptor/path <path-vector>]`. Its `:factory`
  builds the FULL standard-path interceptor (`standard-path-interceptor`)
  implementing the normative rules 1-5 of [Spec 002 §Standard
  `:rf.interceptor/path`](../../../spec/002-Frames.md), notably **rule 4**:
  when the handler emits a `:db` effect whose focused value is `identical?`
  to the original focused slice, the interceptor widens back to the
  ORIGINAL full app-db OBJECT (not an `assoc-in` allocation), preserving
  the frame-commit `identical?` no-op (rf2-ekq28v). A non-vector / malformed
  path argument is `:rf.error/path-interceptor-bad-path`.

  This standard interceptor is DISTINCT from the legacy `path` fn / the
  `rf/path` value constructor: the standard interceptor is the canonical
  `:factory` consumer and is the carrier of the rule-4 identity-fast-path.
  Since the EP-0022 reference-only flip (rf2-0adhqs.9) chains carry refs
  only, the `path` fn's value is no longer a legal inline chain entry —
  it is the underlying value builder the `:rf.interceptor/path` factory
  consumes, and may still be registered at the `reg-interceptor` boundary
  and referenced by id. (The legacy `path` value's own `:after` only
  short-circuits the no-`:db` case, not the unchanged-slice case — which is
  why the standard interceptor, not the legacy value, carries rule 4.)"
  (:require [re-frame.interceptor :as interceptor]
            [re-frame.interceptor-registry :as icpt-reg]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- path -----------------------------------------------------------------
;;
;; Focus a handler on an app-db sub-slice. The handler sees and returns
;; only the slice; the interceptor splices the result back into the
;; parent app-db. Per Spec 002 / API.md.

(defn path
  "(path :a :b :c) returns an interceptor that focuses the handler on the
  sub-slice at [:a :b :c]. The handler receives the slice value as :db
  (not the full app-db); its returned :db is spliced back into the
  full app-db at the same path."
  [& path-segs]
  (let [path-vec (vec path-segs)]
    (interceptor/->interceptor*
      :id    :path
      :path  path-vec
      :before
      (fn [ctx]
        (-> ctx
            ;; Stash the original db on a stack (supports nested path
            ;; interceptors). Reserved-namespace slot per Spec
            ;; Conventions §Reserved namespaces — framework keys on the
            ;; interceptor context belong under :rf/...
            (update :rf/path-stack (fnil conj []) (:db (:coeffects ctx)))
            (assoc-in [:coeffects :db]
                      (get-in (:db (:coeffects ctx)) path-vec))))
      :after
      (fn [ctx]
        ;; Guard: only unwind when our `:before` actually pushed. When an
        ;; EARLIER interceptor's `:before` throws, `execute-chain`
        ;; short-circuits all downstream `:before` stages (including
        ;; ours) yet still runs every `:after` in reverse (Spec 002
        ;; §rule 2). With no push, `:rf/path-stack` is absent — `(pop [])`
        ;; would throw a SPURIOUS second error masking the original. The
        ;; sibling `unwrap` interceptor mirrors this guard via its
        ;; `:rf/unwrap-stash` presence check. No stack → no-op teardown.
        (let [stack (:rf/path-stack ctx)]
          (if (empty? stack)
            ctx
            ;; The splice-back only fires when the handler actually
            ;; emitted a `:db` effect. If the handler returned no `:db`,
            ;; the slice didn't change and we MUST NOT synthesise a `:db`
            ;; effect — downstream tools rely on "no `:db` effect = no DB
            ;; write" (the docstring contract). Synthesising would be
            ;; idempotent at the value level (same `original-db`
            ;; re-spliced with the same pre-handler slice) but allocated a
            ;; fresh map per path-walk-step and produced a spurious `:db`
            ;; effect from a no-`:db` handler.
            (let [original-db (peek stack)
                  new-stack   (pop stack)
                  handler-emitted-db? (contains? (:effects ctx) :db)]
              (cond-> (assoc ctx :rf/path-stack new-stack)
                handler-emitted-db?
                (assoc-in [:effects :db]
                          (assoc-in original-db path-vec
                                    (get-in ctx [:effects :db])))))))))))

;; ---- standard :rf.interceptor/path (EP-0022 Slice C — FULL contract) -------
;;
;; The framework-standard `:rf.interceptor/path` interceptor, referenced as
;; `[:rf.interceptor/path <path-vector>]` and built by the registered
;; `:factory` (`path-factory`). It implements the FULL normative contract of
;; Spec 002 §Standard `:rf.interceptor/path` (rules 1-5), notably RULE 4: an
;; unchanged focused slice widens back to the ORIGINAL full app-db OBJECT so
;; the frame-commit `identical?` no-op (rf2-ekq28v) is preserved.
;;
;; This is DISTINCT from the legacy `path` fn above. The legacy fn (the
;; `rf/path` value builder) only short-circuits the no-`:db`
;; case; it still does `(assoc-in original-db path slice)` when a `:db` effect
;; is present, allocating a fresh top-level map even for an unchanged slice —
;; which defeats the commit no-op. The standard interceptor knows BOTH the
;; original full app-db object AND the original focused slice, so it can detect
;; the unchanged-slice case and re-emit the original object.

(defn- throw-bad-path!
  "Throw the canonical `:rf.error/path-interceptor-bad-path` error (Spec 002
  §Standard `:rf.interceptor/path` / §Error model). Raised by the factory when
  the `[:rf.interceptor/path <path-vector>]` ref carries a non-vector or
  otherwise malformed path argument."
  [path-vector reason]
  (throw (ex-info ":rf.error/path-interceptor-bad-path"
                  {:rf.error/id :rf.error/path-interceptor-bad-path
                   :where       :rf.interceptor/path
                   :recovery    :fix-path
                   :reason      reason
                   :got         path-vector
                   :expected    "an EDN vector naming a concrete app-db path, e.g. [:cart :items]"})))

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

(defn register-standard-interceptors!
  "Register the framework-standard interceptors (currently only
  `:rf.interceptor/path`) into the active registrar. Idempotent — called at
  namespace load AND from `re-frame.core/init!` so the standard refs survive a
  test fixture's `registrar/clear-all!` (which wipes the `:interceptor` kind
  along with everything else). Mirrors how the reserved fx survive via
  defmethod — the standard interceptors re-seed here on every boot."
  []
  (icpt-reg/reg-interceptor*
    :rf.interceptor/path
    {:doc "Framework-standard path interceptor (EP-0022). Focuses an event
          handler on an app-db sub-slice at the given path-vector; the handler
          sees/returns only the slice, spliced back into full app-db.
          Referenced as `[:rf.interceptor/path <path-vector>]`."}
    {:factory path-factory})
  nil)

;; Register at namespace load so standalone require'rs (no init!) get the
;; standard refs; `init!` re-registers (idempotent) for the post-clear-all!
;; test path.
(register-standard-interceptors!)

;; ---- unwrap ---------------------------------------------------------------
;;
;; Asserts that the event is exactly [<id> <payload-map>] (per the M-19
;; canonical map-payload form), and replaces :event with the payload map.
;; The handler then destructures the map directly (one level less of
;; destructuring): (fn [_ {:keys [...]}] ...) instead of (fn [_ [_ {:keys [...]}]] ...).

(def unwrap-interceptor
  "Interceptor VALUE (not a fn). Since EP-0022 (chains are reference-only) it
  is the registration-boundary input — register it and reference it by id:
  `(reg-interceptor :app/unwrap unwrap-interceptor)` then
  `(reg-event :foo {:interceptors [:app/unwrap]} (fn [_ {:keys [a b]}] ...))`.
  The :event coeffect inside the handler is the payload map.

  The `-interceptor` suffix telegraphs value-shape (per rf2-k367k +
  Conventions §Value-vs-fn naming): this Var is an interceptor map,
  not a fn; calling it raises ArityException."
  (interceptor/->interceptor*
    :id    :unwrap
    :before
    (fn [ctx]
      (let [event (interceptor/get-coeffect ctx :event)]
        (if-not (and (vector? event)
                     (= 2 (count event))
                     (map? (second event)))
          (do (trace/emit-error! :rf.error/unwrap-bad-event-shape
                                 {:event event
                                  :expected "[event-id payload-map]"
                                  :recovery :no-recovery})
              ctx)
          ;; Stash the unwrapped event under :rf/unwrap-stash so :after
          ;; can restore the original vector for downstream consumers.
          (-> ctx
              (assoc :rf/unwrap-stash event)
              (interceptor/assoc-coeffect :event (second event))))))
    :after
    (fn [ctx]
      (if-let [original (:rf/unwrap-stash ctx)]
        (-> ctx
            (dissoc :rf/unwrap-stash)
            (interceptor/assoc-coeffect :event original))
        ctx))))
