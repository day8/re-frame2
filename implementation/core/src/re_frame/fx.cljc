(ns re-frame.fx
  "Effect interpreter (do-fx) and reserved fx-id table.

  Per Spec 002 §`:fx` ordering and atomicity guarantees:
    1. :db commits first, atomically.
    2. :fx entries process in source order.
    3. Each fx-handler runs synchronously before the next entry begins.
    4. Subscriptions observe the post-:db state.

  Reserved fx-ids (per Conventions §Reserved fx-ids):
    :dispatch         — runtime, intra-frame dispatch (back of router queue)
    :dispatch-later   — runtime, delayed dispatch
    :raise            — machine-internal (machine handler routes locally)
    :rf.fx/reg-flow   — runtime, register a flow (Spec 013)
    :rf.fx/clear-flow — runtime, clear a flow

  Per rf2-xbtj the machine fx-ids `:rf.machine/spawn` and
  `:rf.machine/destroy` are registered by `re-frame.machines` (which now
  ships in `day8/re-frame2-machines`) at its ns-load time, via the
  regular `reg-fx` path. They are NOT reserved in core's case-block —
  apps that don't pull in the machines artefact don't carry the trace
  strings or the handler for them."
  (:require [re-frame.registrar :as registrar]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.performance :as performance
             #?@(:cljs [:include-macros true])]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

;; ---- registration ---------------------------------------------------------

(defn reg-fx
  "Register an effect handler under `id`. The handler runs when a
  `reg-event-fx` returns an effect-map carrying `[id args]` inside its
  `:fx` vector — `{:fx [[:my-fx args] ...]}`.

  Handler signature: `(fn [ctx args] ...)` — **v2 changed from v1**.

    `ctx`  is a small map carrying:
             `:frame` — the active frame id (Spec 002 §`:fx` ordering)
             `:event` — the originating event vector (Spec 014 §Reply
                        addressing). The fx may capture the originating
                        `event-id` to address replies back without a
                        separate cofx-injection step.
    `args` is the second element of the `[id args]` pair as emitted by
           the event handler (any value — map, vector, scalar).

  Shapes:

      (reg-fx :id                                  (fn [ctx args] ...))
      (reg-fx :id {:doc \"...\" :platforms #{:client}} (fn [ctx args] ...))

  Optional metadata keys:

      :doc        one-sentence what-and-why; surfaces via
                  `(rf/handler-meta :fx id)`.
      :spec       Malli schema for `args` (per Spec 010 §:spec on fx
                  registrations).
      :platforms  set of `#{:client :server}`; default
                  `#{:client :server}`. The fx is skipped on platforms
                  not in the set (`:rf.fx/skipped-on-platform` warning
                  trace).

  Returns `id`.

  Example:

      (rf/reg-fx :my/notify
        {:doc       \"Show a toast notification.\"
         :platforms #{:client}}
        (fn [_ctx {:keys [message level]}]
          (js/window.toast level message)))

      ;; Consumed from an event handler:
      (rf/reg-event-fx :user/login-failed
        (fn [_ [_ reason]]
          {:fx [[:my/notify {:level :error :message (str \"Login failed: \" reason)}]]}))

  Framework-shipped fx (`:dispatch`, `:dispatch-later`, `:rf.http/managed`,
  `:rf.nav/push-url`, ...) are documented in `spec/API.md §Effect-map
  shape` and their per-feature Spec; introspect via
  `(rf/handler-meta :fx <id>)`.

  See also: `reg-cofx` (the input-side counterpart), `clear-fx`,
  `reg-event-fx` (the consumer)."
  [id metadata-or-handler & maybe-handler]
  (let [[meta handler-fn]
        (if (map? metadata-or-handler)
          [metadata-or-handler (first maybe-handler)]
          [{} metadata-or-handler])]
    (registrar/register! :fx id (assoc (source-coords/merge-coords meta)
                                       :handler-fn handler-fn))
    id))

(defn clear-fx
  "Unregister an fx handler. Zero-arity clears every registered fx;
  one-arity clears the named one. Hot-reload tools and test fixtures
  call this between rebuilds.

  Returns nil. See also: `reg-fx`, the user-facing surface `rf/clear-fx`
  (this is the underlying fn — they point at the same value)."
  ([] (registrar/clear-kind! :fx))
  ([id] (registrar/unregister! :fx id)))

;; ---- the platform predicate -----------------------------------------------

(defn- fx-runs-on-platform? [meta active-platform]
  (let [platforms (:platforms meta #{:client :server})]
    (contains? platforms active-platform)))

;; ---- do-fx ----------------------------------------------------------------

(declare dispatch-fx-handler)

(defn- resolve-fx-with-overrides
  "Apply fx-id overrides per Spec 002 §Per-frame and per-call overrides.

  Three override-value shapes are honoured (per [002 §`:fx-overrides`](spec/002-Frames.md#fx-overrides--replace-fx-handlers)):

    1. **Missing key** — no override; the original fx-id flows through.
    2. **Keyword value** — id-redirect: the registered fx at the target id
       runs in place of the original. If the target is not registered,
       emit `:rf.error/override-fallthrough` and fall back to the original
       fx-id. This is the **pattern-level**, portable form (SSR-safe).
    3. **Function value** `(fn [m args] ...)` — CLJS reference convenience
       for test fixtures and story decorators. The fn runs in place of the
       registered fx; no registry lookup against the original fx-id is
       performed. Spec/002 marks this form as a CLJS-reference local
       affordance (not portable across the wire); the JVM-side reference
       (this code) supports it too — `.cljc` is single-source.

  Returns the resolved fx-id (keyword); for the fn-value branch, returns
  the original-fx-id (used only for trace shape — the actual handler
  invocation goes through `:rf.fx/override-applied` and the synthesised
  meta returned by `resolved-fx-meta`)."
  [original-fx-id overrides]
  (if (contains? overrides original-fx-id)
    (let [override-target (get overrides original-fx-id)]
      (cond
        ;; (3) function value — CLJS-reference convenience.
        (fn? override-target)
        (do
          (trace/emit! :fx :rf.fx/override-applied
                       {:from original-fx-id :to ::fn-value})
          original-fx-id)

        ;; (2) id-redirect to a registered fx.
        (keyword? override-target)
        (if (registrar/lookup :fx override-target)
          (do
            (trace/emit! :fx :rf.fx/override-applied
                         {:from original-fx-id :to override-target})
            override-target)
          (do
            (trace/emit-error! :rf.error/override-fallthrough
                               {:failing-id     original-fx-id
                                :overrides-map  overrides
                                :looked-up-id   override-target
                                :reason         (str "Override redirected `"
                                                     original-fx-id
                                                     "` to `"
                                                     override-target
                                                     "`, which is not registered. Using the registered `"
                                                     original-fx-id
                                                     "` instead.")
                                :recovery       :replaced-with-default})
            original-fx-id))

        :else
        ;; Neither fn nor keyword — treat as "no override" and fall
        ;; through to the original fx. Includes `nil` (documented in
        ;; spec/002 §`:fx-overrides` as a noop-style placeholder).
        original-fx-id))
    original-fx-id))

(defn- resolved-fx-meta
  "Return the fx-handler meta to invoke for `original-fx-id` under
  `overrides`. The fn-value branch synthesises a transient meta that
  carries the user-supplied lambda as `:handler-fn`; the id-redirect
  and no-override branches look up the registrar entry under the
  resolved fx-id.

  Returns `nil` when no handler is resolvable (the caller then emits
  `:rf.error/no-such-fx`)."
  [original-fx-id resolved-fx-id overrides]
  (let [override (get overrides original-fx-id)]
    (if (and (contains? overrides original-fx-id)
             (fn? override))
      ;; Function-value override — synthesise a meta with the user's fn.
      ;; `:platforms` defaults to both so the fn is callable from JVM and
      ;; browser tests alike (the override is a test/story affordance —
      ;; gating it by platform would surprise the test author).
      {:handler-fn override
       :platforms  #{:client :server}}
      (registrar/lookup :fx resolved-fx-id))))

(defn- emit-handled!
  "Emit a `:rf.fx/handled` success trace for a dispatched fx. Per Spec-Schemas
  §`:rf/epoch-record` `:effects` projection: every dispatched fx surfaces
  one entry, with `:outcome :ok` for the success path. The epoch projection
  consumes this trace; pair tools route off it without re-folding the raw
  trace stream.

  Per rf2-lf84g: when called inside the fx-handler's
  `*current-trigger-handler*` binding (the user-registered fx branch),
  `emit!` hoists the fx handler's registration coord onto the emitted
  event's `:rf.trace/trigger-handler` slot — so consumers can jump to
  the fx's `reg-fx` site from the success trace. Reserved fx-id calls
  (`:dispatch`, `:dispatch-later`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`)
  emit outside any fx-handler binding; the outer event handler's
  binding (if any) stamps the event handler's coord instead, which is
  the right attribution for those — they don't have their own
  registration site."
  [fx-id args frame-id]
  (trace/emit! :fx :rf.fx/handled
               {:fx-id   fx-id
                :fx-args args
                :frame   frame-id}))

(defn- handle-one-fx
  "Process one [fx-id args] pair. Falls into one of three buckets:
   1. Reserved fx-id with runtime handling (:dispatch, :dispatch-later, :rf.fx/...).
   2. User-registered fx looked up via registrar.
   3. Unknown fx-id — emit :rf.error/no-such-fx and continue.

  Successful dispatches emit `:rf.fx/handled` so the epoch `:effects`
  projection records one entry per dispatched fx (per Spec-Schemas
  §`:rf/epoch-record`). Warning and error paths emit their existing
  traces (`:rf.fx/skipped-on-platform`, `:rf.error/fx-handler-exception`,
  `:rf.error/no-such-fx`) and do NOT additionally emit `:rf.fx/handled`,
  so the projection stays one-entry-per-fx.

  `origin-event` (when supplied) is the originating event vector, threaded
  through to the user-registered fx handler's ctx so handlers like
  `:rf.http/managed` (Spec 014 §Reply addressing) can address replies back
  to the originator without a separate cofx-injection step."
  [frame-id [original-fx-id args] active-platform overrides origin-event]
  (let [fx-id (resolve-fx-with-overrides original-fx-id overrides)
        resolved-meta (resolved-fx-meta original-fx-id fx-id overrides)]
   ;; Per Spec 009 §Performance instrumentation (rf2-du3i): every fx
   ;; invocation — reserved or user-registered — runs inside a perf
   ;; bracket so prod builds with the perf flag enabled produce a
   ;; `rf:fx:<fx-id>` measure entry per fx walk-step. Default-off: the
   ;; bracket DCEs under :advanced + `re-frame.performance/enabled?=false`.
   ;; The bracket sits at the top of `handle-one-fx` so it covers reserved
   ;; fx-ids too (`:dispatch`, `:dispatch-later`, `:rf.fx/reg-flow`,
   ;; `:rf.fx/clear-flow`) — without that, an app whose handlers only
   ;; emit `:dispatch` produces zero `rf:fx:*` entries even with the perf
   ;; flag on.
   (performance/mark-and-measure :fx fx-id
    (case fx-id
    :dispatch
    (do
      ;; Append to back of the frame's router queue.
      (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
        (dispatch! args {:frame frame-id}))
      (emit-handled! fx-id args frame-id))

    :dispatch-later
    (let [{:keys [ms event]} args]
      (interop/set-timeout!
        (fn []
          (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
            (dispatch! event {:frame frame-id})))
        ms)
      (emit-handled! fx-id args frame-id))

    :rf.fx/reg-flow
    ;; Per Spec 013 — flows are frame-scoped. The flow registers against
    ;; the dispatching frame.
    (do
      (when-let [reg-flow! (late-bind/get-fn :flows/reg-flow-fx!)]
        (reg-flow! args {:frame frame-id}))
      (emit-handled! fx-id args frame-id))

    :rf.fx/clear-flow
    (do
      (when-let [clear-flow! (late-bind/get-fn :flows/clear-flow-fx!)]
        (clear-flow! args {:frame frame-id}))
      (emit-handled! fx-id args frame-id))

    ;; Per rf2-xbtj the `:rf.machine/spawn` and `:rf.machine/destroy`
    ;; machine fx-ids are no longer reserved here — they are registered
    ;; by re-frame.machines (day8/re-frame2-machines) via the regular
    ;; reg-fx path and arrive here through the registrar default below.

    ;; Default: user-registered fx — OR a synthesised meta carrying a
    ;; function-value override (per `resolved-fx-meta` above; the
    ;; spec/002 CLJS-reference convenience form). `resolved-meta` was
    ;; computed once at top of `handle-one-fx` so the case-block fallthrough
    ;; honours both registry hits and the fn-value override branch without
    ;; a second lookup.
    (if-let [meta resolved-meta]
      (if (fx-runs-on-platform? meta active-platform)
        ;; Per rf2-3nn8 (error path) and rf2-lf84g (success path): bind
        ;; `*current-trigger-handler*` for the duration of the fx
        ;; handler's invocation AND for the success-path `:rf.fx/handled`
        ;; emit that follows. Error traces emitted from inside the fx
        ;; body (`:rf.error/fx-handler-exception` here and anything the
        ;; body itself surfaces) carry the fx handler's source-coord;
        ;; the success-path `:rf.fx/handled` emit picks up the same
        ;; coord through `emit!`'s hoist of `*current-trigger-handler*`
        ;; (the outer event handler's binding would otherwise stamp the
        ;; event handler's coord onto the `:rf.fx/handled` event, which
        ;; is not what consumers want — Story/Causa want jump-to-source
        ;; to land on the fx handler's `reg-fx` site, not the event
        ;; handler that produced the fx vector).
        ;; Per rf2-isdwf: bind `*current-sensitive?*` to the fx
        ;; handler's reading so any trace event emitted inside the
        ;; fx body's scope (including the success `:rf.fx/handled`
        ;; emit) carries the fx-handler-level sensitivity flag.
        ;; Per Spec 009 §Privacy "innermost in-scope handler's flag
        ;; wins" rule: when an event handler is sensitive but the fx
        ;; handler it dispatches to is not, the `:rf.fx/handled`
        ;; trace event reflects the FX handler's reading.
        ;; Per rf2-qsjda: bind `*current-no-emit?*` to the fx
        ;; handler's reading so the "innermost in-scope handler
        ;; wins" rule applies to trace-emission opt-out too.
        (binding [trace/*current-trigger-handler*
                  (trace/trigger-handler-from-meta :fx fx-id meta)
                  trace/*current-sensitive?*
                  (trace/sensitive?-from-meta meta)
                  trace/*current-no-emit?*
                  (trace/no-emit?-from-meta meta)]
          (let [ok? (try
                      ((:handler-fn meta) (cond-> {:frame frame-id}
                                            origin-event (assoc :event origin-event))
                                          args)
                      true
                      (catch #?(:clj Throwable :cljs :default) e
                        (let [msg (#?(:clj .getMessage :cljs .-message) e)]
                          (trace/emit-error! :rf.error/fx-handler-exception
                                             {:failing-id        fx-id
                                              :fx-id             fx-id
                                              :fx-args           args
                                              :frame             frame-id
                                              :exception         e
                                              :exception-message msg
                                              :reason            (str "Effect handler `" fx-id "` threw: " msg ".")
                                              :recovery          :no-recovery}))
                        false))]
            (when ok?
              (emit-handled! fx-id args frame-id))))
        (trace/emit! :warning :rf.fx/skipped-on-platform
                     {:fx-id                fx-id
                      :frame                frame-id
                      :fx-args              args
                      :platform             active-platform
                      :registered-platforms (:platforms meta)
                      :recovery             :skipped}))
      (trace/emit-error! :rf.error/no-such-fx
                         {:fx-id    fx-id
                          :fx-args  args
                          :frame    frame-id
                          :recovery :no-recovery}))))))

(defn do-fx
  "Walk the :fx vector in source order. Per Spec 002 §`:fx` ordering rule 3:
  each entry's handler returns synchronously before the next begins.
  Errors trace independently and the walk continues (rule 4: one bad
  fx does not halt the rest).

  Per Spec 002 §Per-frame and per-call overrides: an fx-id override map
  may be provided. Each [fx-id args] is rewritten through that map
  before lookup.

  The optional 5-arity passes the originating event vector through to
  user-registered fx handlers as `:event` on their ctx — needed by Spec
  014 §Reply addressing (the fx captures the originating event-id from
  the dispatch envelope's cofx)."
  ([frame-id fx-vec active-platform]
   (do-fx frame-id fx-vec active-platform {} nil))
  ([frame-id fx-vec active-platform overrides]
   (do-fx frame-id fx-vec active-platform overrides nil))
  ([frame-id fx-vec active-platform overrides origin-event]
   (doseq [pair fx-vec]
     (when (and (vector? pair) (seq pair))
       (handle-one-fx frame-id pair active-platform overrides origin-event)))
   (trace/emit! :event :event/do-fx {:frame frame-id})))
