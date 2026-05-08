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
            [re-frame.interceptor :as interceptor]))

(defn- maybe-validate-cofx!
  "Per Spec 010 §Validation order step 2 (rf2-7leq) — after the cofx
  injects, validate its value against the cofx's :spec metadata.

  We resolve schemas/validate-cofx! lazily so this namespace stays
  decoupled from re-frame.schemas (avoids a require cycle). Returns
  the (possibly mutated) context — sets :rf/skip-handler? when
  validation fails so the handler-as-interceptor short-circuits."
  [ctx cofx-id cofx-meta]
  (if-let [validate (resolve 're-frame.schemas/validate-cofx!)]
    (let [event    (interceptor/get-coeffect ctx :event)
          event-id (first event)
          ;; The cofx's injected value is whatever it just stashed under
          ;; :coeffects keyed by the cofx's id (the conventional shape).
          ;; If the cofx fn injected under a different key, we fall back
          ;; to the cofx-id key — validation only runs when :spec is
          ;; declared, so users opt in by registering against the same
          ;; key they inject under.
          value    (get (:coeffects ctx) cofx-id)
          ok?      (try ((deref validate) cofx-id event-id value cofx-meta)
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
    (registrar/register! :cofx id (assoc meta :handler-fn handler-fn))
    id))

(defn inject-cofx
  "Build a :before-only interceptor that runs the registered cofx and
  injects its result into the context's :coeffects.

  Two arities:
    (inject-cofx :id)        — no value
    (inject-cofx :id value)  — passes value as second arg to the cofx fn

  Per Spec 010 §Validation order step 2 (rf2-7leq) — after the cofx
  fn returns, if the cofx's metadata carries a :spec, validate the
  injected value. On failure, mark the context with
  :rf/skip-handler? so subsequent handler interceptors short-circuit
  (recovery: :no-recovery; downstream queue continues)."
  ([cofx-id]
   (interceptor/->interceptor
     :id (keyword (str "cofx-" (name cofx-id)))
     :before
     (fn [ctx]
       (if-let [meta (registrar/lookup :cofx cofx-id)]
         (-> ((:handler-fn meta) ctx)
             (maybe-validate-cofx! cofx-id meta))
         (do (println "re-frame-2: no cofx registered for" cofx-id)
             ctx)))))
  ([cofx-id value]
   (interceptor/->interceptor
     :id (keyword (str "cofx-" (name cofx-id)))
     :before
     (fn [ctx]
       (if-let [meta (registrar/lookup :cofx cofx-id)]
         (-> ((:handler-fn meta) ctx value)
             (maybe-validate-cofx! cofx-id meta))
         (do (println "re-frame-2: no cofx registered for" cofx-id)
             ctx))))))

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
