(ns re-frame.privacy
  "Single policy locus for re-frame privacy / sensitive data / redaction
  per Spec 009 §Privacy / sensitive data in traces (lines 1149-1268,
  resolved by rf2-a32kd; runtime implementation rf2-isdwf; ns
  consolidation rf2-iwqu9).

  This ns owns:

  - **`redacted-sentinel`** — the single `:rf/redacted` sentinel; both
    `with-redacted` (in-handler payload scrub) and
    `re-frame.elision/elide-wire-value` (wire-walker substitution for
    `:sensitive?` paths) emit this same value, sourced from here.

  - **Registration-meta readers**: `sensitive?-from-meta` reads the
    boolean `:sensitive?` flag off a registrar slot's stored meta.
    Every site that needs to know whether a not-yet-bound handler is
    sensitive (the queue-time `:event/dispatched` emit in router, the
    always-on event-emit listener fan-out, the handler-scope binding
    in trace) reads through this single helper.

  - **Public predicate**: `sensitive?` — does this trace event carry
    a top-level `:sensitive? true` field? The filter-out reader every
    off-box listener gates on.

  - **`with-redacted` interceptor**: positional interceptor that
    overwrites named keys in the event payload with `:rf/redacted`
    before the handler chain runs. The handler body itself sees the
    UNREDACTED payload via the regular `:event` coeffect slot; the
    redaction is for the trace surface — every downstream emit that
    copies the event vector (the handler's `:rf.trace/trigger-handler`
    cofx view of the event, the `:event/db-changed` `:tags :app-db-before`
    / `:app-db-after` slots when the corresponding paths exist in
    app-db, and any `:rf.error/handler-exception` `:tags :event` slot
    the runtime emits for a throw from this handler) picks up the
    redacted form.

  - **Registration-time warning**: `:rf.warning/sensitive-without-redaction`
    fires when a registration declares `:sensitive? true` but the
    positional interceptor chain has no `with-redacted` and the
    registration metadata carries no `:no-redaction-needed?` opt-out.
    One emit per `(kind, id)` pair (cached); the cache is reset on
    every fresh registration cycle so re-declarations after fix re-fire.

  Composition: a handler carrying both `:sensitive? true` in its
  metadata-map AND `[(with-redacted [...])]` in its positional
  interceptor chain emits trace events that are BOTH stamped
  `:sensitive? true` AND carry redacted payloads. `:sensitive?` is
  the filter-out signal for off-box listeners; `with-redacted` is
  the in-place scrub. The conservative recommended pattern for new
  sensitive handlers is to declare both.

  Architectural separation: **trace is the emission site, privacy is
  the policy site.** `re-frame.trace` consults privacy when assembling
  the `*handler-scope*` record (for the per-event `:sensitive?` hoist),
  and `re-frame.elision` re-exports the sentinel from here so the
  wire-walker and `with-redacted` emit the same value."
  (:require [re-frame.interceptor :as interceptor]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- redaction sentinel ---------------------------------------------------

(def redacted-sentinel
  "The framework-reserved redaction sentinel per Spec 009 §Privacy. Sits
  in the `:rf/` reserved-keyword namespace so apps cannot legitimately
  produce it as a payload value. Consumers wanting \"was this redacted?\"
  check `(= :rf/redacted v)`.

  Single source of truth: `with-redacted` (this ns) and the elision
  wire-walker (`re-frame.elision/elide-wire-value`) both substitute
  this same value; the walker re-exports through `(def redacted-sentinel
  privacy/redacted-sentinel)` rather than declaring a duplicate."
  :rf/redacted)

;; ---- registration-meta readers -------------------------------------------
;;
;; Single policy reading for the boolean `:sensitive?` flag on a registrar
;; slot's stored meta. Three call sites consult this helper:
;;   - `re-frame.router/emit-dispatched-trace!` — queue-time, before the
;;     handler-scope binding exists.
;;   - `re-frame.event-emit/dispatch-on-event!` — always-on event-emit
;;     fan-out drops sensitive records before fan-out.
;;   - `re-frame.trace/handler-scope-from-meta` — binds the
;;     `*handler-scope*` `:sensitive?` slot for per-event hoist.
;;
;; Per Spec 009 §Privacy / sensitive data in traces — the meta-key shape
;; is normative; this is its single reader.

(defn sensitive?-from-meta
  "True iff `meta` (a registrar slot's stored meta map) carries
  `:sensitive? true`. Returns false on nil, missing key, or
  non-boolean. Per Spec 009 §Privacy / sensitive data in traces."
  [meta]
  (true? (:sensitive? meta)))

;; ---- public predicate ----------------------------------------------------

(defn sensitive?
  "Predicate: is `trace-event`'s top-level `:sensitive?` field truthy?
  The framework-published predicate every trace consumer gates on.
  Re-exported as `re-frame.core/sensitive?`. Per Spec 009 §Privacy /
  sensitive data in traces."
  [trace-event]
  (and (map? trace-event)
       (true? (:sensitive? trace-event))))

;; ---- helpers --------------------------------------------------------------

(defn- redact-paths
  "Apply `:rf/redacted` substitution to every `get-in`-style path inside
  `payload`. Paths whose intermediate slots are missing are skipped (no
  empty maps created). Returns the redacted payload.

  Per Spec 009 line 1224: paths are vectors of keys; v1 vocabulary is
  literal-keys only (no wildcards / predicate paths)."
  [payload paths]
  (reduce
    (fn [acc path]
      (if (and (vector? path) (seq path)
               (some? (get-in acc (butlast path))))
        (assoc-in acc path redacted-sentinel)
        acc))
    payload
    paths))

(defn- redact-event-vector
  "Redact the named paths in the event vector's payload map (the second
  element). Returns the redacted event vector unchanged if the shape
  doesn't match the conventional `[event-id payload-map]` form (per
  Conventions §Unwrap interceptor)."
  [event paths]
  (if (and (vector? event)
           (>= (count event) 2)
           (map? (second event)))
    (let [[id payload & rest-args] event
          redacted-payload (redact-paths payload paths)]
      (into [id redacted-payload] rest-args))
    event))

;; ---- with-redacted interceptor -------------------------------------------

(defn with-redacted
  "Build an interceptor that overwrites the named keys in the event
  vector's payload map with `:rf/redacted` before the handler chain
  runs and before any downstream trace event copies the event vector.

  Signature:

      (with-redacted paths)
      ;; -> an interceptor that redacts the named paths in:
      ;;    - The `:rf.trace/trigger-handler` cofx-slot view of the event
      ;;      (so any emit inside the chain copying the event sees the
      ;;      redacted form).
      ;;    - The `:event/db-changed` `:tags :app-db-before` /
      ;;      `:app-db-after` slots (when the corresponding paths exist
      ;;      in app-db) — handled by the runtime's db-changed emit
      ;;      consulting the interceptor's stashed paths.
      ;;    - Any `:rf.error/handler-exception` `:tags :event` slot.
      ;;
      ;; The handler body itself sees the UNREDACTED payload via the
      ;; regular `:event` coeffect slot.

  Args:
    `paths` — a vector of `get-in`-style key paths into the event
              vector's payload map. Each path is itself a vector;
              the value at every named path is replaced with the
              sentinel keyword `:rf/redacted` before the runtime
              emits any in-chain trace event.

  Returns an interceptor map suitable for inclusion in a `reg-event-*`
  positional chain.

  Per Spec 009 §The `with-redacted` interceptor (lines 1182-1230)."
  [paths]
  (let [paths (vec paths)]
    (interceptor/->interceptor
      :id     :rf/with-redacted
      :before
      (fn [ctx]
        (let [event (interceptor/get-coeffect ctx :event)]
          ;; Per Spec 009 line 1221: the handler body sees the
          ;; UNREDACTED payload via the regular :event cofx slot
          ;; — handlers need the real value to do their work. The
          ;; redaction is for the trace surface only. We stash the
          ;; redacted form under :rf/redacted-event for the
          ;; runtime's emit sites to consult (per the in-chain
          ;; emits listed in the with-redacted docstring above).
          ;;
          ;; Also force `:sensitive? true` for the rest of this
          ;; handler's scope. The interceptor itself
          ;; runs INSIDE the event-handler's `*handler-scope*`
          ;; binding (router.cljc binds before the chain runs), so
          ;; we can't `binding`-shadow it here. Instead we stash a
          ;; `:rf/redacted-paths` slot on the context for the
          ;; runtime to consult when assembling derived emits;
          ;; future emits that copy the event vector consult the
          ;; stashed paths to apply the redaction. The bare event
          ;; coeffect is unchanged so the handler body's view is
          ;; untouched.
          (-> ctx
              (assoc :rf/redacted-paths paths)
              (assoc :rf/redacted-event (redact-event-vector event paths))))))))

;; ---- redaction-aware helpers (for runtime consumers) ---------------------

(defn redact-event
  "Redact `event` using `paths`. Public form of the internal helper —
  exposed so runtime emit sites (e.g. router's :rf.error/handler-exception,
  the :event/db-changed emit) can apply the same redaction logic to
  derived emits."
  [event paths]
  (redact-event-vector event paths))

(defn redacted-event-from-ctx
  "Return the redacted event vector from an interceptor context, or
  the original `:event` coeffect when `with-redacted` did not run.
  Runtime emit sites use this to surface the appropriate shape on
  in-chain trace events."
  [ctx]
  (or (:rf/redacted-event ctx)
      (interceptor/get-coeffect ctx :event)))

;; ---- :rf.warning/sensitive-without-redaction -----------------------------
;;
;; Per Spec 009 §Privacy + Error event catalogue (catalogued at line 870):
;; a registration carrying `:sensitive? true` whose positional
;; interceptor chain lacks `with-redacted` triggers a one-shot warning
;; per `(kind, id)` pair. The suppression cache is keyed on
;; `[kind id]` and is reset on `clear-suppression-cache!` (called from
;; test fixtures and frame-destruction code paths so re-declarations
;; after a fix re-fire the warning, per the bead's "suppression cache
;; reset on frame destroy" requirement).

(defonce ^:private warned-pairs
  ;; Set of [kind id] pairs that have emitted the warning since the
  ;; last cache reset. Per (kind, id) once-per-process suppression.
  (atom #{}))

(defn- has-with-redacted?
  "Walk an interceptor chain looking for `with-redacted` (matched by its
  `:id :rf/with-redacted` interceptor identifier). Returns true iff
  the chain contains at least one such interceptor."
  [interceptors]
  (boolean
    (some (fn [icpt]
            (and (map? icpt)
                 (= :rf/with-redacted (:id icpt))))
          interceptors)))

(defn warn-sensitive-without-redaction!
  "Emit `:rf.warning/sensitive-without-redaction` once per (kind, id) pair
  when the registration declares `:sensitive? true` but the positional
  interceptor chain contains no `with-redacted` and the meta carries
  no `:no-redaction-needed?` opt-out flag.

  Called from `reg-event-*` registration paths after the chain is
  assembled.  Returns nil. Idempotent on repeat (kind, id) — second
  call sees the cached pair and no-ops.

  Per Spec 009 §Privacy + §Error event catalogue (catalogued at line
  870), rf2-isdwf."
  [kind id meta interceptors]
  (when (and (true? (:sensitive? meta))
             (not (true? (:no-redaction-needed? meta)))
             (not (has-with-redacted? interceptors)))
    (let [pair [kind id]]
      (when-not (contains? @warned-pairs pair)
        (swap! warned-pairs conj pair)
        (trace/emit! :warning :rf.warning/sensitive-without-redaction
                     {:kind     kind
                      :id       id
                      :reason
                      (str (name kind) " `" id "` is registered with "
                           ":sensitive? true but its positional interceptor "
                           "chain has no with-redacted. The trace surface "
                           "will receive the unscrubbed event payload "
                           "stamped :sensitive? true; off-box listeners "
                           "that ship sensitive events would leak the "
                           "raw payload. Add `[(rf/with-redacted [<paths>])]` "
                           "to the positional chain, or declare "
                           ":no-redaction-needed? true on the metadata-map "
                           "to opt out of the warning.")
                      :recovery :warned-and-replaced}))))
  nil)

(defn clear-suppression-cache!
  "Reset the warned-pairs cache so the next sensitive-without-redaction
  registration re-emits the warning. Called from test fixtures and
  the frame-destruction code path (per Spec 009 §Privacy / sensitive
  data in traces — suppression cache resets on frame destroy)."
  []
  (reset! warned-pairs #{})
  nil)

;; Publish via late-bind so registrar / frame teardown can call us
;; without a cycle. `re-frame.events` requires this namespace directly
;; to call `warn-sensitive-without-redaction!` from its reg-event-*
;; paths.
(late-bind/set-fn! :privacy/clear-suppression-cache! clear-suppression-cache!)
