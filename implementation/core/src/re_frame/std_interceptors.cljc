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

  EP-0022 (rf2-0adhqs.2, Slice B additive): this ns also registers the
  framework-standard `:rf.interceptor/path` interceptor as a `:factory`
  (a MINIMAL stub — it reuses the existing `path` fn, which already preserves
  the frame-commit `identical?` no-op). The full standard path contract
  (Spec 002 §Standard `:rf.interceptor/path`) and `:rf.error/path-interceptor-bad-path`
  validation are a LATER slice; this registration only makes the
  `[:rf.interceptor/path <path-vector>]` by-reference resolve."
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

;; ---- standard :rf.interceptor/path registration (EP-0022, MINIMAL) --------
;;
;; Register the framework-standard `:rf.interceptor/path` as a `:factory`
;; interceptor so `[:rf.interceptor/path <path-vector>]` references resolve
;; (Spec 002 §Standard `:rf.interceptor/path`). MINIMAL stub for the additive
;; slice: the factory builds an interceptor by delegating to the existing
;; `path` fn (whose `:after` already preserves the frame-commit `identical?`
;; no-op via the no-`:db` short-circuit). The full standard-path contract
;; (rules 1-5, the `identical?`-rewrite-to-original-object) and the
;; `:rf.error/path-interceptor-bad-path` validation are a LATER slice. A
;; non-vector arg here is rejected as an `:rf.error/interceptor-factory-arity`
;; build failure (the factory throws) until the dedicated path error lands.

(defn- path-factory
  "The `:rf.interceptor/path` factory: receives the one `path-vector` arg and
  returns the focusing interceptor. MINIMAL — delegates to `path`. The
  returned interceptor's `:id` is stamped `:rf.interceptor/path` by the
  registry resolver."
  [path-vector]
  (when-not (vector? path-vector)
    (throw (ex-info "path-vector must be a vector"
                    {:got path-vector :expected "a vector app-db path"})))
  (apply path path-vector))

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
  "Pre-registered interceptor (a value, not a fn). Use as
  `(reg-event :foo {:interceptors [unwrap-interceptor]} (fn [_ {:keys [a b]}] ...))`.
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
