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
            [re-frame.late-bind :as late-bind])
  #?(:cljs (:require-macros [re-frame.trace])))

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

;; ---- handler-scope: the consolidated bundle (rf2-ryri7) -------------------
;;
;; Every handler-execution boundary (the router's `process-event!`, the
;; sub recompute path, the fx dispatcher, the cofx injector, the view
;; render wrapper) needs to publish the same five-slot "reading" to the
;; trace stream so `emit!` / `emit-error!` can hoist the relevant pieces
;; onto each emitted event. The five slots are:
;;
;;   :trigger-handler  — registration coord of the in-scope handler
;;                       (rf2-3nn8 error path / rf2-lf84g success path).
;;                       Shape `{:kind :id :source-coord {:ns :file :line
;;                       :column}}` or nil when no source-coord is stamped.
;;
;;   :call-site        — compile-time invocation site of the surface that
;;                       reached the runtime through its macro form
;;                       (`dispatch`, `dispatch-sync`, `subscribe`,
;;                       `inject-cofx`). Shape `{:ns :file :line :column}`
;;                       or nil for fn-form callers. Per rf2-ts1a.
;;
;;   :dispatch-id      — cascade-wide correlation id. Set once on entry
;;                       to the drain (router process-event!) and rides
;;                       every event emitted inside the run-to-completion
;;                       drain. Per Spec 009 §Dispatch correlation and
;;                       rf2-g6ih4.
;;
;;   :sensitive?       — boolean. True when the in-scope handler's
;;                       registration meta carries `:sensitive? true`.
;;                       Emitted events get a top-level `:sensitive? true`
;;                       stamp; absent reads as false (Spec 009 line 1176).
;;                       Per rf2-isdwf.
;;
;;   :no-emit?         — boolean. True when the in-scope handler's
;;                       registration meta carries `:rf.trace/no-emit?
;;                       true`. `emit!` / `emit-error!` short-circuit
;;                       (no envelope allocation, no listener fan-out)
;;                       when bound true. Per Spec 009 §Trace-emission
;;                       opt-out and rf2-qsjda.
;;
;; The five slots were originally five sibling dynamic Vars
;; (`*current-trigger-handler*`, `*current-call-site*`,
;; `*current-dispatch-id*`, `*current-sensitive?*`, `*current-no-emit?*`)
;; bound side-by-side at every handler-scope site. Per rf2-ryri7 they are
;; consolidated into one record `HandlerScope` bound to one Var
;; `*handler-scope*`: one binding-frame allocation per scope (instead of
;; five), one Var to mock in tests, one record-field edit when a sixth
;; concern lands.
;;
;; Composition rule: the innermost handler-scope binding wins for the
;; meta-derived slots (`:trigger-handler` / `:sensitive?` / `:no-emit?`)
;; per Spec 009. The `:call-site` and `:dispatch-id` slots are inherited
;; from the parent scope unless the new scope explicitly overrides them
;; — call-site originates at macro expansion time and rides through
;; nested scopes; dispatch-id is allocated once per cascade and survives
;; the handler-chain → sub recompute → fx → cofx descent. The
;; constructor and binding macros handle inheritance automatically.
;;
;; Production elision: the whole trace surface compiles out via the outer
;; `(when interop/debug-enabled? ...)` gate in `emit!` / `emit-error!`, so
;; all `*handler-scope*` reads are dead code under :advanced + goog.DEBUG=
;; false. The `:trigger-handler` slot is NOT elided in error traces (which
;; survive into production via `error-emit/dispatch-on-error!`).

(defrecord HandlerScope [trigger-handler call-site dispatch-id sensitive? no-emit?])

(def ^:dynamic *handler-scope*
  "The HandlerScope record currently in scope for trace emission.
  Bound by `with-handler-scope` (and its variants) at every handler-
  execution boundary (router, subs, fx, cofx, views) and at every
  partial-binding site (dispatch error emits, subscribe/inject-cofx
  macros). nil at top of stack — registration-time emits, outermost-
  dispatch lookup failures, async-callback emits all see nil and emit
  events without the hoisted handler-scope slots.

  Per rf2-ryri7. Replaces five sibling dynamic Vars: *current-trigger-
  handler* (rf2-3nn8 / rf2-lf84g), *current-call-site* (rf2-ts1a),
  *current-dispatch-id* (rf2-g6ih4), *current-sensitive?* (rf2-isdwf),
  *current-no-emit?* (rf2-qsjda)."
  nil)

(defn trigger-handler-from-meta
  "Build a `:rf.trace/trigger-handler` value from a registrar meta map.
  `kind` is the registry kind (`:event`, `:sub`, `:fx`, `:cofx`, `:view`);
  `id` is the registered id; `meta` is the registrar slot's metadata (as
  returned by `registrar/lookup`). Picks `:ns` / `:file` / `:line` /
  `:column` off the meta map (the source-coord stamp lives flat on the
  meta map per `re-frame.source-coords/merge-coords`).

  Returns nil when no source-coord keys are present on `meta` — the
  slot stays unbound and the error event omits the field. That covers
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

(defn sensitive?-from-meta
  "Read `:sensitive?` from a registrar slot's meta map. Returns
  `true` iff the meta carries `:sensitive? true`; `false` for every
  other shape (nil meta, absent key, falsy value).

  Per rf2-isdwf. Used by `handler-scope-from-meta` and by
  `emit-dispatched-trace!` (queue-time `:event/dispatched` emit, before
  the handler-scope binding exists)."
  [meta]
  (true? (:sensitive? meta)))

(defn no-emit?-from-meta
  "Read `:rf.trace/no-emit?` from a registrar slot's meta map. Returns
  `true` iff the meta carries `:rf.trace/no-emit? true`; `false` for
  every other shape (nil meta, absent key, falsy value).

  Per rf2-qsjda. Used by `handler-scope-from-meta` and by
  `emit-dispatched-trace!` to gate the queue-time `:event/dispatched`
  emit (mirrors `sensitive?-from-meta`, same rationale: handler-scope
  binding doesn't exist yet at enqueue time)."
  [meta]
  (true? (:rf.trace/no-emit? meta)))

(defn sensitive?
  "Predicate: is `trace-event`'s top-level `:sensitive?` field truthy?

  The framework-published predicate every consumer (Causa, Story,
  pair2-preload, pair2-mcp, story-mcp, causa-mcp) gates on. Replaces
  the per-consumer `(and (map? ev) (true? (:sensitive? ev)))` private
  helper.

  Per Spec 009 §Privacy / sensitive data in traces and rf2-isdwf
  (audit G5)."
  [trace-event]
  (and (map? trace-event)
       (true? (:sensitive? trace-event))))

(defn handler-scope-from-meta
  "Build a HandlerScope from a registrar slot's meta map for a handler
  about to execute. The three meta-derived slots (`:trigger-handler` /
  `:sensitive?` / `:no-emit?`) are computed; `:call-site` and
  `:dispatch-id` are nil — `with-handler-scope` fills them from the
  parent scope on bind.

  `kind` is the registry kind (`:event`, `:sub`, `:fx`, `:cofx`, `:view`);
  `id` is the registered handler's id; `meta` is the registrar slot's
  metadata.

  Pre-compute this at registration time (views) when meta is fixed, or
  inline at boundary time (router/fx/cofx/subs) when meta resolves per
  dispatch. Per rf2-ryri7."
  [kind id meta]
  (->HandlerScope (trigger-handler-from-meta kind id meta)
                  nil
                  nil
                  (true? (:sensitive? meta))
                  (true? (:rf.trace/no-emit? meta))))

(defn inherit-scope
  "Merge `parent`'s `:call-site` and `:dispatch-id` into `new-scope` for
  any slot where `new-scope`'s value is nil. The meta-derived slots
  (`:trigger-handler` / `:sensitive?` / `:no-emit?`) on `new-scope` are
  preserved as-is — Spec 009's innermost-handler-wins rule. Returns a
  HandlerScope. Per rf2-ryri7."
  [new-scope parent]
  (if (nil? parent)
    new-scope
    (cond-> new-scope
      (nil? (:call-site new-scope))   (assoc :call-site   (:call-site parent))
      (nil? (:dispatch-id new-scope)) (assoc :dispatch-id (:dispatch-id parent)))))

#?(:clj
   (defmacro with-handler-scope
     "Bind `*handler-scope*` to `scope` (a HandlerScope record) for the
     duration of `body`. Inherits `:call-site` and `:dispatch-id` from
     the parent scope where `scope`'s slots are nil. Per rf2-ryri7.

     Usage at every handler-execution boundary (router, fx, cofx, subs,
     views):

         (trace/with-handler-scope (trace/handler-scope-from-meta :event id meta)
           (run-chain ...))"
     [scope & body]
     `(binding [*handler-scope* (inherit-scope ~scope *handler-scope*)]
        ~@body)))

#?(:clj
   (defmacro with-call-site
     "Bind `*handler-scope*` with `:call-site` set to `cs`, inheriting
     the rest from the parent scope. For surface macros (`subscribe`,
     `inject-cofx`) and for synchronous error emits in `dispatch!` /
     `dispatch-sync!` that stamp the envelope's call-site before
     emitting `:rf.error/frame-destroyed` etc.

     Per rf2-ryri7 (replaces `(binding [*current-call-site* cs] ...)`)."
     [cs & body]
     `(let [cs# ~cs
            parent# *handler-scope*]
        (binding [*handler-scope* (if parent#
                                    (assoc parent# :call-site cs#)
                                    (->HandlerScope nil cs# nil false false))]
          ~@body))))

#?(:clj
   (defmacro with-dispatch-id+call-site
     "Bind `*handler-scope*` with `:dispatch-id` and `:call-site` set,
     inheriting the rest from the parent. Used by `router/process-event!`
     to publish the cascade's `:dispatch-id` and the envelope's
     `:call-site` once on entry to the drain.

     Per rf2-ryri7 (replaces the dispatch-id + call-site pair of bindings
     that wrapped `process-event*` pre-consolidation)."
     [dispatch-id call-site & body]
     `(let [did# ~dispatch-id
            cs# ~call-site
            parent# *handler-scope*]
        (binding [*handler-scope* (if parent#
                                    (assoc parent#
                                           :dispatch-id did#
                                           :call-site   cs#)
                                    (->HandlerScope nil cs# did# false false))]
          ~@body))))

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
    :sensitive?    — boolean. Match the top-level `:sensitive?` field
                     (per Spec 009 §Privacy filter-vocab row, rf2-isdwf).
                     Pass `false` to exclude sensitive events; pass
                     `true` to select only sensitive events. Absent ⇒
                     no constraint.
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
           sensitive-filter (:sensitive? opts)
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
                  ;; Per rf2-isdwf: top-level `:sensitive?` is hoisted
                  ;; (NOT nested under :tags). Match against the
                  ;; top-level slot only; absent reads as false.
                  (or (nil? sensitive-filter)
                      (= (true? sensitive-filter)
                         (true? (:sensitive? ev))))
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

;; ---- shared emit substrate (rf2-xwp6o, rf2-ryri7) -------------------------
;;
;; `emit!` (success path) and `emit-error!` (error path) share an identical
;; envelope-construction core and an identical delivery path. The shared
;; pieces live in `build-event` (pure construction; reads the handler-scope
;; bundle internally via `*handler-scope*`) and `deliver!` (side-effect
;; dispatch: ring buffer push + epoch capture + listener fan-out). The
;; two public emit fns reduce to ~8-line wrappers carrying the prod-DCE
;; outer gate.
;;
;; The outer `(when interop/debug-enabled? ...)` and inner `(when-not
;; (:no-emit? *handler-scope*) ...)` guards stay in the wrappers — NOT
;; in `build-event` — because Spec 009 §Production builds mandates the
;; outermost form of an emit call be `(when interop/debug-enabled? ...)`
;; alone for Closure DCE to elide the whole expression in `:advanced`
;; builds with `goog.DEBUG=false`.

(defn- compute-sensitive?
  "Per rf2-isdwf: hoist `:sensitive?` to the top level of the trace
  event when the in-scope handler's registration meta carries
  `:sensitive? true`. Caller-supplied `:sensitive?` in tags wins (e.g.
  `emit-dispatched-trace!` computes its own reading at queue time,
  before the handler-scope binding exists)."
  [tags scope]
  (let [tag-sensitive? (:sensitive? tags)]
    (cond
      (some? tag-sensitive?) (true? tag-sensitive?)
      :else                  (true? (some-> scope :sensitive?)))))

(defn- stamp-cascade-id
  "Per rf2-g6ih4: stamp the cascade's :dispatch-id on every event
  emitted inside the drain so consumers can group raw trace events by
  cascade without inferring from sequence. Caller-supplied
  :dispatch-id wins (`:event/dispatched` and any future emit that
  needs to override)."
  [base-tags cascade-id]
  (if (and cascade-id (not (contains? base-tags :dispatch-id)))
    (assoc base-tags :dispatch-id cascade-id)
    base-tags))

(defn- build-event
  "Assemble the trace envelope. Pure construction — reads
  `*handler-scope*` once and pulls the four hoist-relevant slots
  (`:trigger-handler` / `:call-site` / `:dispatch-id` / `:sensitive?`)
  from the record. No side-effects. Op-type discriminates the divergent
  top-level hoists between success and error paths:

    - success (op-type ≠ :error): hoists `:source` and `:recovery` from
      tags when present; `:rf.trace/call-site` is NOT read (success
      traces don't carry it).
    - error (op-type = :error): always stamps `:recovery` (defaulting
      to `:no-recovery`); hoists `:rf.trace/call-site` from
      `*handler-scope*`'s `:call-site` slot when set; merges
      `{:category operation}` into tags.

  The shared core — id, time, tags+, trigger-handler, sensitive? — is
  identical across both paths. Per rf2-xwp6o (factor) and rf2-ryri7
  (single-Var read)."
  [op-type operation tags]
  (let [scope       *handler-scope*
        trigger     (some-> scope :trigger-handler)
        cascade-id  (some-> scope :dispatch-id)
        sensitive?  (compute-sensitive? tags scope)
        error?      (= op-type :error)
        ;; Source and recovery are caller-supplied through `tags` on
        ;; the success path; on the error path `:recovery` defaults
        ;; to `:no-recovery` per Spec 009 §Error contract.
        source      (when-not error? (:source tags))
        recovery    (if error?
                      (:recovery tags :no-recovery)
                      (:recovery tags))
        call-site   (when error? (some-> scope :call-site))
        ;; Per Spec 009 §Required top-level fields: :source and
        ;; :recovery (when present) live at the top level of the
        ;; trace event, NOT inside :tags. Strip them from base-tags
        ;; so the hoisted copies don't double-up. `:sensitive?` is
        ;; hoisted too (rf2-isdwf). Error path additionally merges
        ;; its `:category` slot from `operation`.
        base-tags   (cond-> (dissoc tags :source :recovery :sensitive?)
                      error? (->> (merge {:category operation})))
        tags+       (stamp-cascade-id base-tags cascade-id)]
    (cond-> {:operation operation
             :op-type   op-type
             :id        (next-event-id)
             :time      (interop/now-ms)
             :tags      tags+}
      source     (assoc :source source)
      ;; Success path: hoist :recovery only when caller supplied
      ;; one. Error path: always stamp (default :no-recovery).
      (or error? recovery) (assoc :recovery recovery)
      ;; Per rf2-lf84g: hoist the in-scope handler's registration
      ;; coord onto every trace event emitted inside a handler's
      ;; scope. Symmetric across success and error paths per
      ;; rf2-3nn8.
      trigger    (assoc :rf.trace/trigger-handler trigger)
      ;; Per rf2-ts1a: error path only — hoist the compile-time
      ;; call-site of the surface that was reached through its
      ;; macro form. Absent on the success path (call-site rides
      ;; only error traces).
      call-site  (assoc :rf.trace/call-site call-site)
      ;; Per rf2-isdwf: top-level `:sensitive? true` stamp. Absent
      ;; (NOT `:sensitive? false`) when the in-scope handler is not
      ;; sensitive — per Spec 009 line 1176 "Consumers treat absent
      ;; as false."
      sensitive? (assoc :sensitive? true))))

(defn- deliver!
  "Side-effect dispatch for an assembled trace envelope: ring-buffer
  push (Spec 009 §Retain-N), epoch-capture fan-out (Tool-Pair
  §Time-travel), and listener fan-out (Spec 009 §Listener invocation
  rules). Delivery is synchronous — listeners SHOULD be fast.
  Listeners that throw don't break the runtime; the stream continues.
  Per rf2-xwp6o."
  [event]
  (push-to-buffer! event)
  (deliver-to-epoch-capture! event)
  (doseq [[_ f] @listeners]
    (try
      (f event)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn emit!
  "Emit a trace event. In production builds (when interop/debug-enabled?
  is false at compile time), Closure DCE removes the body and the call
  becomes a no-op.

  In dev / JVM: builds the envelope (via `build-event`) and delivers
  it (via `deliver!`) to the ring buffer, epoch-capture, and all
  registered listeners. Delivery is synchronous — listeners SHOULD be
  fast; per Spec 009 §Listener invocation rules, batching is the
  listener's choice.

  Per Spec 009 §Core fields: :source is hoisted to the top level of
  the envelope (origin of the trigger — :ui :timer :http :repl
  :machine). Tags retain everything else.

  Per rf2-g6ih4: when `*handler-scope*` is bound with a non-nil
  `:dispatch-id` (the runtime is inside a drain processing a dispatch),
  the in-flight cascade's id is merged into `:tags :dispatch-id`.
  Callers that supply their own `:dispatch-id` in tags win (the only
  such caller in the framework is `:event/dispatched`, which stamps
  its own freshly-allocated id).

  Per rf2-lf84g: when `*handler-scope*` is bound with a non-nil
  `:trigger-handler` (the emit fires inside a handler's execution
  scope — event / sub / fx / cofx / view), the handler's registration
  coord rides on the emitted event under the top-level
  `:rf.trace/trigger-handler` slot. Mirrors the error path
  (`emit-error!`) — same field, same shape, same elision behaviour.
  Success-path traces emitted inside a handler's scope
  (`:rf.fx/handled`, `:rf.machine/transition`, `:event/db-changed`,
  `:event/do-fx`, ...) carry the registration coord so tools can
  jump-to-source from any trace event in a cascade, not just errors.

  Per rf2-qsjda: when the in-scope `*handler-scope*` carries
  `:no-emit? true` (the handler's registration meta carries
  `:rf.trace/no-emit? true`), the body short-circuits before envelope
  allocation — no buffer push, no epoch-capture, no listener fan-out,
  no id-counter bump. This is the framework-level opt-out for trace-
  consuming integrations whose own bookkeeping handlers must not
  re-enter the trace stream and form a cb-dispatch loop. Per Spec 009
  §Trace-emission opt-out."
  [op operation tags]
  (when interop/debug-enabled?
    ;; Per rf2-qsjda: short-circuit when the in-scope handler opted
    ;; out of trace emission. Guard sits *inside* the outer
    ;; `interop/debug-enabled?` gate (Spec 009 §Production builds
    ;; mandates the outermost form be that gate alone — `(when
    ;; (and X interop/debug-enabled?) ...)` defeats Closure DCE).
    (when-not (true? (some-> *handler-scope* :no-emit?))
      (deliver! (build-event op operation tags)))))

(defn emit-error!
  "Emit a structured error trace event. Per Spec 009 §Error contract:
  `:operation` is the error category (e.g. `:rf.error/handler-exception`),
  `:op-type` is :error, and `:tags` includes `:category`, `:exception`,
  `:where`, etc.

  Per rf2-3nn8: when `*handler-scope*` is bound with a non-nil
  `:trigger-handler` (a handler is currently in scope — event handler
  running, sub recomputing, fx handler dispatching, cofx injecting,
  view rendering), the value is hoisted to the top-level
  `:rf.trace/trigger-handler` slot on the emitted event. Absent when
  no handler is in scope (e.g. outermost-dispatch
  `:rf.error/no-such-handler`).

  Per rf2-ts1a: when `*handler-scope*` is bound with a non-nil
  `:call-site` (the error fires inside the body of a surface that was
  reached through its macro form — `dispatch`, `dispatch-sync`,
  `subscribe`, `inject-cofx`), the compile-time-captured call site is
  hoisted to the top-level `:rf.trace/call-site` slot on the emitted
  event. Absent when the surface was reached through its fn form
  (`dispatch*` etc.) or when no surface is in scope.

  Per rf2-g6ih4: when `*handler-scope*` is bound with a non-nil
  `:dispatch-id` (the error fires inside a drain), the in-flight
  cascade's id is merged into `:tags :dispatch-id` so consumers can
  correlate the error with the rest of the cascade. Caller-supplied
  `:dispatch-id` wins.

  Per rf2-qsjda: when the in-scope `*handler-scope*` carries
  `:no-emit? true` (the handler's registration meta carries
  `:rf.trace/no-emit? true`), the body short-circuits before envelope
  allocation — symmetric with the success path in `emit!`. The
  framework-level trace-emission opt-out applies to error traces too
  (a Causa-style bookkeeping handler must not re-enter the consumer
  through error emits any more than through success emits). Per
  Spec 009 §Trace-emission opt-out."
  [error-operation tags]
  (when interop/debug-enabled?
    ;; Per rf2-qsjda: short-circuit when the in-scope handler opted
    ;; out of trace emission. Guard sits *inside* the outer
    ;; `interop/debug-enabled?` gate per Spec 009 §Production builds
    ;; (the outer gate must stand alone for Closure DCE).
    (when-not (true? (some-> *handler-scope* :no-emit?))
      (deliver! (build-event :error error-operation tags)))))

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.registrar emits a trace event when a handler is replaced but
;; cannot `:require` this namespace without a cyclic load order.
;; Publish `emit!` through the late-bind hook registry. See
;; re-frame.late-bind.

(late-bind/set-fn! :trace/emit!       emit!)
(late-bind/set-fn! :trace/emit-error! emit-error!)
