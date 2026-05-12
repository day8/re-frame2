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
  "Register a coeffect handler under `id`. A coeffect is a source of
  input data injected into an event handler's `:coeffects` map.

  Handler signatures:

      (fn [context])         → context     ;; no-value form (1-arity)
      (fn [context value])   → context     ;; value form    (2-arity)

  The handler should `assoc` its result into `(:coeffects context)`
  under a key — conventionally `id` — and return the updated context.
  The standard cofx ids are `:db` (the frame's `app-db` value at drain
  start) and `:event` (the dispatched event vector); both are populated
  by the runtime before the interceptor chain runs.

  Consumed by event handlers via `inject-cofx` placed in the
  interceptor-vector slot of `reg-event-{db,fx,ctx}`:

      (rf/reg-cofx :now
        (fn [ctx]
          (assoc-in ctx [:coeffects :now] (js/Date.))))

      (rf/reg-event-fx :user/save
        [(rf/inject-cofx :now)]                       ;; <-- inject here
        (fn [{:keys [db now]} [_ user]]               ;; <-- read here
          {:db (assoc db :saved-at now :user user)}))

  Shapes:

      (reg-cofx :id                                (fn [ctx] ...))
      (reg-cofx :id {:doc \"...\" :spec ...}         (fn [ctx] ...))

  Optional metadata keys: `:doc`, `:spec` (Malli schema for the
  injected value — validated per Spec 010 §Validation order step 2).

  Returns `id`.

  See also: `inject-cofx` (consumer-side), `reg-fx` (output-side
  counterpart), the standard `:db` / `:event` cofx registered below."
  [id metadata-or-handler & maybe-handler]
  (let [[meta handler-fn]
        (if (map? metadata-or-handler)
          [metadata-or-handler (first maybe-handler)]
          [{} metadata-or-handler])]
    (registrar/register! :cofx id (assoc (source-coords/merge-coords meta)
                                         :handler-fn handler-fn))
    id))

(defn inject-cofx
  "Build a `:before`-only interceptor that runs the cofx registered
  under `cofx-id` and merges its result into the running event
  handler's `:coeffects`.

  Used in the positional interceptor-vector of `reg-event-{db,fx,ctx}`:

      (rf/reg-cofx :now
        (fn [ctx] (assoc-in ctx [:coeffects :now] (js/Date.))))

      (rf/reg-event-fx :foo
        [(rf/inject-cofx :now)]                 ;; <-- interceptor position
        (fn [{:keys [db now]} _]                ;; <-- read injected value
          {:db (assoc db :timestamp now)}))

  The handler sees the injected value under the conventional `cofx-id`
  key in its first arg. Some cofx accept a per-call value:
  `(inject-cofx :stub {:status 200})` — the value is passed to the
  cofx handler's 2-arity form.

  Errors:
    `:rf.error/no-such-cofx`               — `cofx-id` is not registered;
                                              the handler still runs but
                                              with no injection (recovery
                                              :no-recovery).
    `:rf.error/schema-validation-failure`  — cofx carries a `:spec` and
                                              the injected value fails it;
                                              the handler is short-circuited
                                              via `:rf/skip-handler?`.

  Notes
  -----
  Three arities exist for plumbing reasons:

      (inject-cofx :id)                   — no value, no call-site
      (inject-cofx :id value)             — per-call value, no call-site
      (inject-cofx :id value call-site)   — value + macro-stamped call-site
                                            (use `cofx/no-value` for the
                                            no-value path with a call-site)

  The 3-arity form is what the `re-frame.core/inject-cofx` macro expands
  to (per rf2-ts1a); it captures `(meta &form)` at the user call site
  so error events emitted from the cofx body carry the invocation
  coord. The 1-/2-arity forms wrap to the 3-arity with a `nil`
  call-site.

  See also: `reg-cofx`, `re-frame.core/inject-cofx` (macro form with
  call-site capture)."
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
  {:doc "Inject the frame's current `app-db` value under `:coeffects :db`. Pre-populated by the drain loop before the interceptor chain runs; explicit `(inject-cofx :db)` is rarely needed and is a no-op. Exists for symmetry with `:event` and v1 idioms."}
  (fn [ctx]
    ;; The drain loop has already populated :coeffects :db with the frame's
    ;; current app-db value before invoking the chain — so this cofx is
    ;; usually a no-op. It exists for symmetry with v1.
    ctx))

(reg-cofx :event
  {:doc "Inject the dispatched event vector under `:coeffects :event`. Pre-populated by the dispatch envelope before the chain runs; explicit `(inject-cofx :event)` is a no-op."}
  (fn [ctx]
    ;; Same as :db — the dispatch envelope wires the event into :coeffects
    ;; before the chain runs.
    ctx))
