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
            [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^{:doc "Sentinel for `inject-cofx`'s 3-arity 'no-value' branch.
  Used by `re-frame.core/inject-cofx`'s macro form (rf2-ts1a) to thread
  a call-site through the no-value path without inventing a private
  sentinel at the macro layer. Equality-tested via `identical?` so
  user values never collide."} no-value
  ::no-value)

;; ---- the platform predicate -----------------------------------------------
;;
;; Per Spec 011 §634-642 the `:platforms` metadata applies to BOTH
;; `reg-fx` AND `reg-cofx`; a cofx tagged `:platforms #{:client}` must
;; no-op when injected on a server-side frame (the SSR contract —
;; request-cofx like browser locale, localStorage, navigator-info etc.
;; would otherwise blow up under JVM render or produce nonsense
;; values).
;;
;; Single definition lives in `re-frame.fx/runs-on-platform?` (rf2-4ymm0
;; SP6); we alias it here so internal call sites read in the cofx
;; vocabulary.

(def ^:private cofx-runs-on-platform? fx/runs-on-platform?)

(defn- active-platform-for-frame
  "Resolve the active platform for a cofx injection. Mirrors the resolution
  in `router/run-fx-effects!`: the frame's `:config :platform` override (set
  by the `:ssr-server` preset, or any user-supplied frame config) takes
  precedence over the host-wide `interop/platform` marker."
  [frame-id]
  (or (when frame-id
        (get-in (frame/frame frame-id) [:config :platform]))
      interop/platform))

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

  Optional metadata keys:

      :doc        one-sentence what-and-why; surfaces via
                  `(rf/handler-meta :cofx id)`.
      :spec       Malli schema for the injected value (validated per
                  Spec 010 §Validation order step 2).
      :platforms  set of `#{:client :server}`; default
                  `#{:client :server}`. The cofx is skipped on platforms
                  not in the set (`:rf.cofx/skipped-on-platform`
                  warning trace, mirroring `reg-fx`'s contract per
                  Spec 011 §634-642).

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

  The 3-arity form is what the `re-frame.core/inject-cofx` macro
  expands to; it captures `(meta &form)` at the user call site so
  error events emitted from the cofx body carry the invocation coord.
  The 1-/2-arity forms wrap to the 3-arity with a `nil` call-site.

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
           ;; Per Spec 011 §634-642 the `:platforms` metadata gates BOTH
           ;; reg-fx AND reg-cofx. A client-only cofx (e.g. browser
           ;; locale, localStorage, navigator-info) must no-op when
           ;; injected under a server-side frame; the runtime emits
           ;; `:rf.cofx/skipped-on-platform` (warning, :recovery
           ;; :skipped) mirroring fx.cljc's gate. The handler chain
           ;; continues — the injection is skipped, not the event.
           (let [frame-id        (interceptor/get-coeffect ctx :frame)
                 active-platform (active-platform-for-frame frame-id)]
             (if (cofx-runs-on-platform? meta active-platform)
               ;; Publish the cofx handler's HandlerScope.
               ;; `:trigger-handler` / `:sensitive?` / `:no-emit?` come
               ;; from the cofx's registration meta per Spec 009's
               ;; "innermost in-scope handler" rule. `:call-site` is
               ;; either the macro-captured site (when reached via the
               ;; `inject-cofx` macro) or inherited from the parent
               ;; scope's call-site — `handler-scope-from-meta` returns
               ;; nil for `:call-site` and `inherit-scope` (run by
               ;; `with-handler-scope`) carries the parent's value
               ;; through; when `captured-cs` is non-nil, override
               ;; explicitly via `assoc`.
               (trace/with-handler-scope
                 (cond-> (trace/handler-scope-from-meta :cofx cofx-id meta)
                   captured-cs (assoc :call-site captured-cs))
                 (-> (if valued?
                       ((:handler-fn meta) ctx value)
                       ((:handler-fn meta) ctx))
                     (maybe-validate-cofx! cofx-id meta)))
               (do
                 (trace/emit! :warning :rf.cofx/skipped-on-platform
                              (cond-> {:cofx-id              cofx-id
                                       :frame                frame-id
                                       :platform             active-platform
                                       :registered-platforms (:platforms meta)
                                       :recovery             :skipped}
                                valued? (assoc :cofx-value value)))
                 ctx)))
           ;; No registered cofx — emit the `:rf.error/no-such-cofx`
           ;; trace. When the cofx was reached via its macro form
           ;; (`captured-cs` non-nil), override the parent scope's
           ;; call-site; when reached via the fn form, `captured-cs`
           ;; is nil and the parent's call-site rides through. Per
           ;; rf2-ryri7.
           (trace/with-call-site (or captured-cs
                                     (some-> trace/*handler-scope* :call-site))
             (let [event (interceptor/get-coeffect ctx :event)]
               (trace/emit-error! :rf.error/no-such-cofx
                                  (cond-> {:cofx-id  cofx-id
                                           :event-id (when (vector? event) (first event))
                                           :recovery :no-recovery}
                                    valued? (assoc :cofx-value value)))
               ctx))))))))

;; ---- standard cofx --------------------------------------------------------

(reg-cofx :db
  {:doc "Inject the frame's current `app-db` value under `:coeffects :db`. Pre-populated by the runtime before the interceptor chain runs; explicit `(inject-cofx :db)` is a no-op. Registered for symmetry with `:event` and so `(handlers :cofx)` enumerates the standard cofx."}
  (fn [ctx]
    ;; The runtime pre-populates :coeffects :db with the frame's current
    ;; app-db value before invoking the chain — so this cofx is a no-op.
    ;; Registered for symmetry with `:event`.
    ctx))

(reg-cofx :event
  {:doc "Inject the dispatched event vector under `:coeffects :event`. Pre-populated by the runtime before the interceptor chain runs; explicit `(inject-cofx :event)` is a no-op. Registered for symmetry with `:db` and so `(handlers :cofx)` enumerates the standard cofx."}
  (fn [ctx]
    ;; The runtime pre-populates :coeffects :event with the dispatched
    ;; event vector before invoking the chain — so this cofx is a no-op.
    ;; Registered for symmetry with `:db`.
    ctx))
