(ns re-frame.cofx
  "Coeffect handlers and registration. Per Spec 002 §Effects (`reg-fx`)
  and coeffects (`reg-cofx`).

  A cofx handler injects data into the handler's input map (under
  :coeffects). The standard cofx are :db (the frame's app-db value at
  drain start) and :event (the dispatched event vector); user-registered
  cofx add custom inputs (current time, browser language, etc.).

  Use inject-cofx in an event handler's interceptor list to ingest a
  registered cofx into the context."
  (:require [re-frame.registrar :as registrar]
            [re-frame.interceptor :as interceptor]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

(def ^{:doc "Sentinel for `inject-cofx`'s 3-arity 'no-value' branch.
  Used by `re-frame.core/inject-cofx`'s macro form (rf2-ts1a) to thread
  a call-site through the no-value path without inventing a private
  sentinel at the macro layer. Equality-tested via `identical?` so
  user values never collide."} no-value
  ::no-value)

(defn- maybe-validate-cofx!
  "Per Spec 010 §Validation order step 2 (rf2-7leq) — after the cofx
  injects, validate its value against the cofx's :spec metadata.

  We look up schemas/validate-cofx! through the late-bind registry so
  this namespace stays decoupled from re-frame.schemas (avoids a
  require cycle). Returns the (possibly mutated) context — sets
  :rf/skip-handler? when validation fails so the handler-as-interceptor
  short-circuits."
  [ctx cofx-id cofx-meta]
  (if-let [validate (late-bind/get-fn :schemas/validate-cofx!)]
    (let [event    (interceptor/get-coeffect ctx :event)
          event-id (first event)
          ;; The cofx's injected value is whatever it just stashed under
          ;; :coeffects keyed by the cofx's id (the conventional shape).
          ;; If the cofx fn injected under a different key, we fall back
          ;; to the cofx-id key — validation only runs when :spec is
          ;; declared, so users opt in by registering against the same
          ;; key they inject under.
          value    (get (:coeffects ctx) cofx-id)
          ok?      (try (validate cofx-id event-id value cofx-meta)
                        (catch #?(:clj Throwable :cljs :default) _ true))]
      (if ok?
        ctx
        (assoc ctx :rf/skip-handler? true)))
    ctx))

(defn reg-cofx
  "Register a coeffect handler.
  cofx-handler signature: (fn [context]) → context  OR  (fn [context value]) → context.
  Returns context with the cofx merged into :coeffects."
  [id metadata-or-handler & maybe-handler]
  (let [[meta handler-fn]
        (if (map? metadata-or-handler)
          [metadata-or-handler (first maybe-handler)]
          [{} metadata-or-handler])]
    (registrar/register! :cofx id (assoc (source-coords/merge-coords meta)
                                         :handler-fn handler-fn))
    id))

(defn inject-cofx
  "Build a :before-only interceptor that runs the registered cofx and
  injects its result into the context's :coeffects.

  Three arities:
    (inject-cofx :id)                  — no value, no call-site
    (inject-cofx :id value)            — value, no call-site
    (inject-cofx :id value call-site)  — value + macro-stamped call-site
                                         (use `cofx/no-value` for the
                                         no-value path with a call-site)

  Per rf2-ts1a: the 3-arity form is what `re-frame.core/inject-cofx`'s
  macro expands to — it captures `(meta &form)` at the user call site
  and stamps it into the interceptor's closure. The `:before` body
  binds `trace/*current-call-site*` to the captured value so error
  events emitted from inside the body (`:rf.error/no-such-cofx`,
  schema-validation failures, exceptions thrown by the cofx fn) carry
  the invocation coord. Pass `nil` for the call-site to opt out of
  stamping (the 1-/2-arity forms wrap to this with `nil`).

  Per Spec 010 §Validation order step 2 (rf2-7leq) — after the cofx
  fn returns, if the cofx's metadata carries a :spec, validate the
  injected value. On failure, mark the context with
  :rf/skip-handler? so subsequent handler interceptors short-circuit
  (recovery: :no-recovery; downstream queue continues).

  Per rf2-3nn8: while the cofx fn body runs, the cofx's own
  source-coord is the in-scope trigger-handler — errors emitted from
  inside the cofx point at the cofx definition, not the enclosing
  event handler. The miss path (`:rf.error/no-such-cofx`) inherits
  the enclosing event handler's binding (set by the router) — the
  cofx that doesn't exist has no coord to point at."
  ([cofx-id]
   (inject-cofx cofx-id no-value nil))
  ([cofx-id value]
   (inject-cofx cofx-id value nil))
  ([cofx-id value call-site]
   (let [valued?      (not (identical? value no-value))
         captured-cs  (when interop/debug-enabled? call-site)]
     (interceptor/->interceptor
       :id (keyword (str "cofx-" (name cofx-id)))
       :before
       (fn [ctx]
         (if-let [meta (registrar/lookup :cofx cofx-id)]
           (binding [trace/*current-trigger-handler*
                     (trace/trigger-handler-from-meta :cofx cofx-id meta)
                     trace/*current-call-site*
                     (or captured-cs trace/*current-call-site*)]
             (-> (if valued?
                   ((:handler-fn meta) ctx value)
                   ((:handler-fn meta) ctx))
                 (maybe-validate-cofx! cofx-id meta)))
           (binding [trace/*current-call-site*
                     (or captured-cs trace/*current-call-site*)]
             (let [event (interceptor/get-coeffect ctx :event)]
               (trace/emit-error! :rf.error/no-such-cofx
                                  (cond-> {:cofx-id  cofx-id
                                           :event-id (when (vector? event) (first event))
                                           :recovery :no-recovery}
                                    valued? (assoc :cofx-value value)))
               ctx))))))))

;; ---- standard cofx --------------------------------------------------------

(reg-cofx :db
  (fn [ctx]
    ;; The drain loop has already populated :coeffects :db with the frame's
    ;; current app-db value before invoking the chain — so this cofx is
    ;; usually a no-op. It exists for symmetry with v1.
    ctx))

(reg-cofx :event
  (fn [ctx]
    ;; Same as :db — the dispatch envelope wires the event into :coeffects
    ;; before the chain runs.
    ctx))
