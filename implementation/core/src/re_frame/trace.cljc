(ns re-frame.trace
  "Per-process trace event stream. Per Spec 009.

  Every dispatch, drain step, render, fx invocation, error, and machine
  transition emits a structured trace event. Listeners (10x, re-frame-pair,
  AI tools) subscribe and consume the stream.

  Production builds elide trace emission entirely via the
  `re-frame.interop/debug-enabled?` flag — see emit! below.

  Trace event shape (per Spec 009 §Core fields):
    {:operation   :event/run               ;; required — what's being traced
     :op-type     :event                   ;; required — discriminator
                                           ;;   (:event :sub/run :sub/create
                                           ;;    :fx :event/do-fx :machine
                                           ;;    :registry :view/render
                                           ;;    :warning :error :info ...)
     :id          <int>                    ;; required — auto-incrementing
                                           ;;   per-process counter, unique per emit
     :time        <millis>                 ;; required — emit timestamp (host clock)
     :tags        {...}                    ;; required — op-type-specific bag
     :source      <kw>                     ;; (when present) hoisted from tags;
                                           ;;   :ui :timer :http :machine :repl ...
     :recovery    <kw>}                    ;; (when present) hoisted from tags;
                                           ;;   error-event recovery disposition.

  The shape is event-at-a-time, not span-shaped: there is no :start/:end/
  :duration pair and no :child-of parent-id. Cascade correlation rides on
  :dispatch-id under :tags of EVERY event emitted inside a cascade;
  :parent-dispatch-id rides under :tags of :event/dispatched events only
  (it documents inter-cascade lineage). Per Spec 009 §Dispatch correlation
  and rf2-g6ih4."
  (:require [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]))

;; ---- listener registry ----------------------------------------------------

(defonce ^:private listeners (atom {}))    ;; id → fn (or nil for cleared)

(defn register-trace-cb!
  "Register a listener that receives every trace event. The id can be any
  comparable value; passing the same id twice replaces. Returns the id."
  [id f]
  (swap! listeners assoc id f)
  id)

(defn remove-trace-cb!
  [id]
  (swap! listeners dissoc id)
  nil)

(defn clear-trace-cbs!
  []
  (reset! listeners {})
  nil)

;; ---- event id counter (cheap, monotonic per process) ----------------------

(defonce ^:private event-counter (atom 0))

(defn- next-event-id []
  (swap! event-counter inc))

;; ---- trigger-handler (rf2-3nn8 / rf2-lf84g) -------------------------------
;;
;; Per Spec 009 §Trace correlation: a trace event MAY carry a
;; `:rf.trace/trigger-handler` field naming the handler whose execution
;; produced the event. Originally introduced (rf2-3nn8) for the error
;; path; widened (rf2-lf84g) to ride success-path traces too — every
;; event emitted inside a handler's execution scope carries the slot.
;; The field is OPTIONAL — it is present when an in-scope handler can
;; be identified (event handler running, sub recomputing, fx handler
;; dispatching, cofx injecting, view rendering) and absent when no
;; handler is in scope (e.g. outermost-dispatch
;; `:rf.error/no-such-handler`, registration-time emits).
;;
;; The runtime carries the in-scope handler through the dynamic Var
;; `*current-trigger-handler*`. Runtime boundaries (the router's
;; `process-event!`, the sub recompute path, the fx dispatcher, the
;; cofx injector, the view render wrapper) bind the Var around the
;; user code they run; `emit!` and `emit-error!` read the Var and
;; hoist its value to the top-level `:rf.trace/trigger-handler` slot
;; on the emitted event when bound.
;;
;; Shape (locked, per rf2-3nn8):
;;
;;   {:kind         <kw>                  ;; :event / :sub / :fx / :cofx / :view
;;    :id           <kw>                  ;; the registered handler's id
;;    :source-coord {:ns     <sym>
;;                   :file   <string>
;;                   :line   <int>
;;                   :column <int>}}      ;; or nil when no source-coord captured
;;
;; The field is NOT elided in production — unlike `:rf.assert/*` which
;; is dev-only, `:rf.error/*` traces ride into prod builds and the
;; trigger-handler coord rides with them. Per Spec 009 §Production
;; builds the broader trace surface is dev-only via `interop/debug-
;; enabled?`; this Var sits inside that gate and naturally elides with
;; the surrounding error emit when prod-elided.

(def ^:dynamic *current-trigger-handler*
  "The handler currently in scope for trace emission. Bound by each
  runtime boundary (router, subs, fx, cofx, views). When bound, both
  `emit!` (success path) and `emit-error!` (error path) attach the
  value to the emitted event under the top-level
  `:rf.trace/trigger-handler` slot. nil outside any handler's scope.

  Shape: `{:kind <kw> :id <kw> :source-coord {:ns :file :line :column}?}`.

  Per rf2-3nn8 (error path) and rf2-lf84g (success path)."
  nil)

;; ---- call-site (rf2-ts1a) -------------------------------------------------
;;
;; Complement to `*current-trigger-handler*` (rf2-3nn8). Where the trigger-
;; handler names the registration site of the in-scope handler, the call-site
;; names the **invocation line** of the specific callable that's about to
;; emit (or has just emitted) an error — e.g. the `(rf/dispatch [:bad-event])`
;; line, the `(rf/subscribe [:bad-sub])` line, the `(rf/inject-cofx :missing)`
;; line. With both pieces, tooling renders two clickable links per error:
;; "handler defined at X:142" (trigger-handler) and "failed call at Y:147"
;; (call-site).
;;
;; The call-site is captured at compile time by the macro form of the
;; callable (per Q1=C: existing-name macro + `*` fn variant; `dispatch` is
;; the macro, `dispatch*` is the runtime-callable fn). The macro binds this
;; Var around the underlying `*`-fn call; `emit-error!` reads the Var and
;; hoists the value to the top-level `:rf.trace/call-site` slot on the
;; emitted event (Q2=A: flat sibling of `:rf.trace/trigger-handler`).
;;
;; For dispatched events the binding happens not at the macro call site but
;; later, when the router's drain pulls the envelope out of the queue:
;; `dispatch*` stamps the call-site onto the envelope, and `process-event!`
;; binds this Var from the envelope before invoking the handler chain so
;; any error emitted inside the chain carries it.
;;
;; For interceptors (`inject-cofx`) the call-site is captured by the
;; macro into the interceptor's closure; the `:before` body binds the Var
;; before calling the cofx fn.
;;
;; Elision (Q3=B): dev-only. The macros omit the call-site map entirely
;; when `goog.DEBUG=false` so the compiled-out call becomes `(dispatch*
;; event-vec)` — identical to the fn-form path. The closure compiler
;; constant-folds the dead branch and the map literal DCE's.

(def ^:dynamic *current-call-site*
  "The compile-time-captured call site of the surface (`dispatch`,
  `dispatch-sync`, `subscribe`, `inject-cofx`) about to emit (or just
  emitted) an error. Bound by each surface's macro form around the
  underlying `*`-fn invocation; `emit-error!` reads the Var and attaches
  the value to the emitted event under `:rf.trace/call-site`. nil when
  the surface was reached through its fn form (`dispatch*` etc.) or when
  no surface is in scope.

  Shape: `{:ns <sym> :file <string> :line <int> :column <int>}` — the
  same shape as `:source-coord` under `:rf.trace/trigger-handler`, but
  carries the **invocation** line rather than the registration line.

  Per rf2-ts1a (Q1=C macro+fn pair, Q2=A flat key, Q3=B dev-only elision)."
  nil)

;; ---- *current-dispatch-id* (rf2-g6ih4) ------------------------------------
;;
;; Per Spec 009 §Dispatch correlation: `:dispatch-id` is a **cascade-wide**
;; correlation key. It is allocated when a dispatch is enqueued (per
;; `router.cljc`) and rides on every trace event emitted **inside** that
;; dispatch's run-to-completion drain — `:event/dispatched` itself,
;; `:event/db-changed`, `:rf.fx/handled`, `:sub/run`, `:rf.machine/transition`,
;; `:rf.error/*`, every emit produced while processing the event.
;;
;; The runtime carries the in-flight cascade's id through the dynamic Var
;; `*current-dispatch-id*` (mirror of `*current-trigger-handler*` per
;; rf2-3nn8). `router.cljc` binds the Var around `process-event*` so every
;; downstream emit — including emits produced by user handler code, by
;; substrate code reacting to a `:db` commit, by fx handlers walking the
;; `:fx` vector — sees the cascade's id. `emit!` reads the Var and merges
;; it into the emitted event's `:tags` map when bound and not already
;; present. Callers that explicitly supply `:dispatch-id` in tags win
;; (e.g. `emit-dispatched-trace!` stamps its own id; child dispatches
;; emitted by fx handlers are themselves `:event/dispatched` events with
;; their own freshly-allocated `:dispatch-id`).
;;
;; `:parent-dispatch-id` remains scoped to `:event/dispatched` only — it
;; documents cascade-from-cascade lineage (which cascade caused this
;; one), which is per-event-dispatch, not per-trace-event.
;;
;; Production elision: when `interop/debug-enabled?` is false the whole
;; trace surface compiles out via the outer `when` gate in `emit!`, so
;; the Var read is dead code.

(def ^:dynamic *current-dispatch-id*
  "The id of the cascade currently being processed by `router.cljc`'s
  drain. Bound by `router.cljc` around `process-event*` to the in-flight
  dispatch's `:dispatch-id`. `emit!` reads the Var and merges it into
  the emitted event's `:tags` when bound and not already present.

  nil outside any drain (e.g. emits produced at registration time, at
  frame creation, by user code outside a handler).

  Per Spec 009 §Dispatch correlation and rf2-g6ih4."
  nil)

(defn trigger-handler-from-meta
  "Build a `:rf.trace/trigger-handler` value from a registrar meta map.
  `kind` is the registry kind (`:event`, `:sub`, `:fx`, `:cofx`, `:view`);
  `id` is the registered id; `meta` is the registrar slot's metadata (as
  returned by `registrar/lookup`). Picks `:ns` / `:file` / `:line` /
  `:column` off the meta map (the source-coord stamp lives flat on the
  meta map per `re-frame.source-coords/merge-coords`).

  Returns nil when no source-coord keys are present on `meta` — the
  Var stays unbound and the error event omits the field. That covers
  the programmatic-registration path (the underlying registration fns
  called directly without the macro wrapping) where coords would be
  meaningless framework-internal positions.

  Per rf2-3nn8."
  [kind id meta]
  (let [coord (cond-> nil
                (:ns     meta) (assoc :ns     (:ns     meta))
                (:file   meta) (assoc :file   (:file   meta))
                (:line   meta) (assoc :line   (:line   meta))
                (:column meta) (assoc :column (:column meta)))]
    (when coord
      {:kind         kind
       :id           id
       :source-coord coord})))

;; ---- retain-N trace ring buffer (dev-only) -------------------------------
;;
;; Per Spec 009 §Retain-N trace ring buffer. Holds the most recent N completed
;; trace events; queryable via (trace-buffer). All ring-buffer machinery is
;; gated on interop/debug-enabled? (the same compile-time flag the rest of the
;; trace surface rides) so production builds drop the buffer entirely — no
;; allocation, no append, no storage.

(def ^:private default-buffer-depth
  "Per Spec 009 §Retain-N trace ring buffer: default 200 events."
  200)

(defonce ^:private buffer-depth (atom default-buffer-depth))

(defonce ^:private trace-buffer-state
  ;; The buffer is a plain vector held under an atom. Append is conj+slice;
  ;; the slot count caps memory. depth=0 disables the buffer entirely (the
  ;; delivery path still works — see configure-trace-buffer!).
  (atom []))

(defn- push-to-buffer!
  "Append ev to the ring buffer, evicting the oldest entry when the slot
  count is exceeded. No-op when the configured depth is 0."
  [ev]
  (when interop/debug-enabled?
    (let [depth @buffer-depth]
      (when (pos? depth)
        (swap! trace-buffer-state
               (fn [v]
                 (let [v' (conj v ev)
                       n  (count v')]
                   (if (> n depth)
                     (subvec v' (- n depth))
                     v'))))))))

(defn trace-buffer
  "Return the trace ring buffer's current contents, oldest first.

  With opts, filters the result. Recognised keys (all compose AND-wise;
  an absent key means \"no constraint on that axis\"):

    :operation     — keep only events with this :operation value.
    :op-type       — keep only events with this :op-type value.
    :since         — keep only events whose :id is strictly greater than
                     this. Useful for cursor-based polling.
    :frame         — keep only events whose `:tags :frame` (or top-level
                     :frame fallback) matches.
    :severity      — keep only events whose :op-type matches one of
                     `:error` / `:warning` / `:info`. Synonym for
                     `:op-type` restricted to the three severity tiers.
    :event-id      — keep only events whose `:tags :event-id` matches.
                     The event-id is the first element of the dispatched
                     event vector (e.g. `:user/login`).
    :handler-id    — keep only events whose `:tags :handler-id` matches.
                     Carried on `:rf.error/handler-exception` and other
                     handler-scoped emits.
    :source        — keep only events whose :source (top-level, hoisted
                     from `:tags :source` by `emit!`) matches. Source
                     identifies the trigger origin — one of `:ui` /
                     `:timer` / `:http` / `:repl` / `:machine` /
                     `:ssr-hydration`. Matched against the top-level slot.
    :origin        — keep only events whose `:tags :origin` matches.
                     Origin tags the actor that issued the dispatch
                     (`:app` / `:pair` / `:story` / `:test` / ...) per
                     Spec 002 §Dispatch origin tagging.
    :dispatch-id   — keep only events whose `:tags :dispatch-id` matches.
                     Cascade-wide post rf2-g6ih4 — every emit inside a
                     drain carries the in-flight cascade's dispatch-id.
    :since-ms      — keep only events whose :time is strictly greater
                     than this numeric host-clock timestamp.
    :between       — `[t0 t1]` two-element vector — keep only events
                     whose :time falls in [t0, t1] inclusive.
    :pred          — `(fn [ev] -> truthy)` arbitrary predicate. Receives
                     the full event map. Returning truthy keeps the event.

  Filters compose: every supplied key must match. Returns an empty vector
  in production (the buffer never receives events when interop/debug-enabled?
  is false at compile time).

  Per Spec 009 §Retain-N trace ring buffer."
  ([] (trace-buffer {}))
  ([opts]
   (if-not interop/debug-enabled?
     []
     (let [{:keys [operation op-type since frame
                   severity event-id handler-id source origin
                   dispatch-id since-ms between pred]} opts
           [between-t0 between-t1] (when (and (sequential? between)
                                              (= 2 (count between)))
                                     between)
           predicate
           (fn [ev]
             (and (or (nil? operation) (= operation (:operation ev)))
                  (or (nil? op-type)   (= op-type   (:op-type ev)))
                  (or (nil? since)     (and (number? (:id ev))
                                            (> (:id ev) since)))
                  (or (nil? frame)
                      (= frame (or (:frame ev)
                                   (get-in ev [:tags :frame]))))
                  (or (nil? severity) (= severity (:op-type ev)))
                  (or (nil? event-id)
                      (= event-id (get-in ev [:tags :event-id])))
                  (or (nil? handler-id)
                      (= handler-id (get-in ev [:tags :handler-id])))
                  (or (nil? source)
                      (= source (or (:source ev)
                                    (get-in ev [:tags :source]))))
                  (or (nil? origin)
                      (= origin (get-in ev [:tags :origin])))
                  (or (nil? dispatch-id)
                      (= dispatch-id (get-in ev [:tags :dispatch-id])))
                  (or (nil? since-ms)
                      (and (number? (:time ev))
                           (> (:time ev) since-ms)))
                  (or (nil? between-t0)
                      (and (number? (:time ev))
                           (<= between-t0 (:time ev) between-t1)))
                  (or (nil? pred) (pred ev))))]
       (filterv predicate @trace-buffer-state)))))

(defn clear-trace-buffer!
  "Empty the ring buffer. Tooling uses this between sessions. No-op in
  production. Per Spec 009 §Retain-N trace ring buffer."
  []
  (when interop/debug-enabled?
    (reset! trace-buffer-state []))
  nil)

(defn configure-trace-buffer!
  "Set the ring buffer's depth. depth=0 disables the buffer (the delivery
  path still works). The new depth applies on the next append; existing
  entries are trimmed to fit immediately. No-op in production.

  Per Spec 009 §Retain-N trace ring buffer."
  [{:keys [depth]}]
  (when (and interop/debug-enabled? (number? depth) (not (neg? depth)))
    (reset! buffer-depth depth)
    (swap! trace-buffer-state
           (fn [v]
             (let [n (count v)]
               (cond
                 (zero? depth) []
                 (> n depth)   (subvec v (- n depth))
                 :else         v)))))
  nil)

(defn configure
  "Generic config dispatch. Recognises :trace-buffer; future config knobs
  add cases here. Per Spec 009 §Retain-N trace ring buffer
  (`(rf/configure :trace-buffer {:depth N})`)."
  [k opts]
  (case k
    :trace-buffer (configure-trace-buffer! opts)
    nil))

;; ---- emission -------------------------------------------------------------

(defn- deliver-to-epoch-capture!
  "Forward the assembled trace event to the epoch-capture buffer if
  re-frame.epoch has registered its capture hook. The capture hook
  is published through `re-frame.late-bind` (key `:epoch/capture-event`);
  routing through there keeps this namespace free of a require on
  re-frame.epoch and ensures `clear-trace-cbs!` (a user-facing API) does
  NOT wipe the internal capture path. Per Tool-Pair §Time-travel and
  Spec 009 §`register-epoch-cb!`."
  [event]
  (when-let [capture (late-bind/get-fn :epoch/capture-event)]
    (try
      (capture event)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn emit!
  "Emit a trace event. In production builds (when interop/debug-enabled?
  is false at compile time), Closure DCE removes the body and the call
  becomes a no-op.

  In dev / JVM: builds the envelope and delivers to all registered
  listeners. Delivery is synchronous — listeners SHOULD be fast; per
  Spec 009 §Listener invocation rules, batching is the listener's choice.

  Per Spec 009 §Core fields: :source is hoisted to the top level of the
  envelope (origin of the trigger — :ui :timer :http :repl :machine).
  Tags retain everything else.

  Per rf2-g6ih4: when `*current-dispatch-id*` is bound (the runtime is
  inside a drain processing a dispatch), the in-flight cascade's id is
  merged into `:tags :dispatch-id`. Callers that supply their own
  `:dispatch-id` in tags win (the only such caller in the framework is
  `:event/dispatched`, which stamps its own freshly-allocated id).

  Per rf2-lf84g: when `*current-trigger-handler*` is bound (the emit
  fires inside a handler's execution scope — event / sub / fx / cofx /
  view), the handler's registration coord rides on the emitted event
  under the top-level `:rf.trace/trigger-handler` slot. Mirrors the
  error path (`emit-error!`) — same field, same shape, same elision
  behaviour. Success-path traces emitted inside a handler's scope
  (`:rf.fx/handled`, `:rf.machine/transition`, `:event/db-changed`,
  `:event/do-fx`, ...) carry the registration coord so tools can
  jump-to-source from any trace event in a cascade, not just errors."
  [op operation tags]
  (when interop/debug-enabled?
    (let [source       (:source tags)
          recovery     (:recovery tags)
          ;; Per Spec 009 §Required top-level fields: :source and
          ;; :recovery (when present) live at the top level of the
          ;; trace event, NOT inside :tags. Hoist them here.
          cascade-id   *current-dispatch-id*
          trigger      *current-trigger-handler*
          base-tags    (dissoc tags :source :recovery)
          ;; Per rf2-g6ih4: stamp the cascade's :dispatch-id on every
          ;; event emitted inside the drain so consumers can group raw
          ;; trace events by cascade without inferring from sequence.
          ;; Caller-supplied :dispatch-id wins (`:event/dispatched` and
          ;; any future emit that needs to override).
          tags+        (if (and cascade-id (not (contains? base-tags :dispatch-id)))
                         (assoc base-tags :dispatch-id cascade-id)
                         base-tags)
          event    (cond-> {:operation operation
                            :op-type   op
                            :id        (next-event-id)
                            :time      (interop/now-ms)
                            :tags      tags+}
                     source   (assoc :source source)
                     recovery (assoc :recovery recovery)
                     ;; Per rf2-lf84g: hoist the in-scope handler's
                     ;; registration coord onto every trace event
                     ;; emitted inside a handler's scope. Symmetric
                     ;; with `emit-error!` per rf2-3nn8.
                     trigger  (assoc :rf.trace/trigger-handler trigger))]
      (push-to-buffer! event)
      (deliver-to-epoch-capture! event)
      (doseq [[_ f] @listeners]
        (try
          (f event)
          (catch #?(:clj Throwable :cljs :default) _
            ;; Listeners that throw don't break the runtime; the stream
            ;; continues. Per Spec 009: listener failures are isolated.
            nil))))))

(defn emit-error!
  "Emit a structured error trace event. Per Spec 009 §Error contract:
  `:operation` is the error category (e.g. `:rf.error/handler-exception`),
  `:op-type` is :error, and `:tags` includes `:category`, `:exception`,
  `:where`, etc.

  Per rf2-3nn8: when `*current-trigger-handler*` is bound (a handler is
  currently in scope — event handler running, sub recomputing, fx
  handler dispatching, cofx injecting, view rendering), the value is
  hoisted to the top-level `:rf.trace/trigger-handler` slot on the
  emitted event. Absent when no handler is in scope (e.g. outermost-
  dispatch `:rf.error/no-such-handler`).

  Per rf2-ts1a: when `*current-call-site*` is bound (the error fires
  inside the body of a surface that was reached through its macro form
  — `dispatch`, `dispatch-sync`, `subscribe`, `inject-cofx`), the
  compile-time-captured call site is hoisted to the top-level
  `:rf.trace/call-site` slot on the emitted event. Absent when the
  surface was reached through its fn form (`dispatch*` etc.) or when
  no surface is in scope.

  Per rf2-g6ih4: when `*current-dispatch-id*` is bound (the error fires
  inside a drain), the in-flight cascade's id is merged into
  `:tags :dispatch-id` so consumers can correlate the error with the
  rest of the cascade. Caller-supplied `:dispatch-id` wins."
  [error-operation tags]
  (when interop/debug-enabled?
    (let [trigger    *current-trigger-handler*
          call-site  *current-call-site*
          cascade-id *current-dispatch-id*
          base-tags  (merge {:category error-operation} tags)
          tags+      (if (and cascade-id (not (contains? base-tags :dispatch-id)))
                       (assoc base-tags :dispatch-id cascade-id)
                       base-tags)
          event      (cond-> {:operation error-operation
                              :op-type   :error
                              :id        (next-event-id)
                              :time      (interop/now-ms)
                              :tags      tags+
                              :recovery  (:recovery tags :no-recovery)}
                       trigger   (assoc :rf.trace/trigger-handler trigger)
                       call-site (assoc :rf.trace/call-site       call-site))]
      (push-to-buffer! event)
      (deliver-to-epoch-capture! event)
      (doseq [[_ f] @listeners]
        (try
          (f event)
          (catch #?(:clj Throwable :cljs :default) _ nil))))))

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.registrar emits a trace event when a handler is replaced but
;; cannot `:require` this namespace without a cyclic load order.
;; Publish `emit!` through the late-bind hook registry. See
;; re-frame.late-bind.

(late-bind/set-fn! :trace/emit!       emit!)
(late-bind/set-fn! :trace/emit-error! emit-error!)
