(ns re-frame.trace
  "Per-process trace event stream. Per Spec 009.

  Every dispatch, drain step, render, fx invocation, error, and machine
  transition emits a structured trace event. Listeners (10x,
  re-frame-pair, AI tools) subscribe and consume the stream. The shape
  is event-at-a-time (not span-shaped); cascade correlation rides on
  `:dispatch-id`. Production builds elide trace emission entirely via
  `re-frame.interop/debug-enabled?` — see `emit!` below. See Spec 009
  §Core fields, §Dispatch correlation, and §Handler-scope.

  Topology (rf2-qwm0a + rf2-ic1sv): this ns carries the always-loaded
  hot fast path — `emit!` / `emit-error!` / `*handler-scope*` and the
  bracket macros. The public-tooling surface (`register-listener!` /
  `unregister-listener!` / `clear-listeners!` / `trace-buffer` /
  `clear-trace-buffer!` / `configure-trace-buffer!` / `configure`) and
  the buffer + listener state live in the sibling
  `re-frame.trace.tooling`, which is loaded only when a test fixture,
  tool, or dev preload requires it.

  Per rf2-ic1sv pick c: the app-facing registration surface lives on
  `re-frame.trace` (this ns) — the `register-listener!` / `unregister-
  listener!` / `clear-listeners!` defs at the bottom of this file are
  the canonical names. The `-trace-` infix dropped from the function
  names because the namespace already says `trace`. JVM and CLJS both
  see the same shape.

  CLJS production DCE: the listener registration fns are tiny (atom
  swap), but the buffer machinery is heavier. Production builds rely
  on user-side `goog.DEBUG` gating around registration call sites so
  the entire `(when goog.DEBUG (rf/register-listener! …))` block is
  dead-coded. The buffer + configure surface remains accessible only
  via `re-frame.trace.tooling/<name>` directly so production counter
  bundles can still DCE the heavier machinery.

  `deliver!` reaches the tooling fan-out through the single
  `:trace.tooling/deliver!` late-bind hook (mirroring the existing
  `:epoch/capture-event` shape)."
  (:require [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            ;; Per rf2-ic1sv pick c: `re-frame.trace/register-listener!`
            ;; et al. are the canonical app-facing names. Bringing the
            ;; tooling sibling in on both CLJS and JVM allows the
            ;; consolidated surface. Production CLJS DCE depends on
            ;; user-side `goog.DEBUG` gating of registration call sites
            ;; — the trivial registration fns survive otherwise, but
            ;; the heavier buffer machinery only enters the bundle when
            ;; explicitly used.
            [re-frame.trace.tooling :as trace-tooling])
  #?(:cljs (:require-macros [re-frame.trace])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- event id counter (cheap, monotonic per process) ----------------------

(defonce ^:private event-counter (atom 0))

(defn- next-event-id []
  (swap! event-counter inc))

;; ---- handler-scope: the five-slot in-scope reading -----------------------

(defrecord HandlerScope [trigger-handler call-site dispatch-id sensitive? no-emit?])

(def ^:dynamic *handler-scope*
  "HandlerScope record currently in scope for trace emission, or nil at
  top of stack. Bound by `with-handler-scope` (and its variants) at
  every handler-execution boundary. See Spec 009 §Handler-scope."
  nil)

(defn trigger-handler-from-meta
  "Build a `:rf.trace/trigger-handler` value `{:kind :id :source-coord
  {:ns :file :line :column}}` from a registrar slot's `meta` map.
  Returns nil when no source-coord keys are present (programmatic
  registration). Per Spec 009 §Handler-scope."
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

(defn no-emit?-from-meta
  "True iff `meta` carries `:rf.trace/no-emit? true`. Used at queue-time
  `:event/dispatched` emit (before handler-scope is bound). Per Spec 009
  §Trace-emission opt-out."
  [meta]
  (true? (:rf.trace/no-emit? meta)))

(defn handler-scope-from-meta
  "Build a HandlerScope from a registrar slot's `meta` for a handler
  about to execute. `:call-site` and `:dispatch-id` are left nil for
  `with-handler-scope` to inherit from the parent. The `:sensitive?`
  slot is fed by the router's schema-derived overlap calculation
  (`:rf/sensitive?` on the scope-meta map) — the handler-meta
  annotation has been removed; sensitivity is now path-marked at the
  schema slot. Per Spec 009 §Handler-scope."
  [kind id meta]
  (->HandlerScope (trigger-handler-from-meta kind id meta)
                  nil
                  nil
                  (true? (:rf/sensitive? meta))
                  (true? (:rf.trace/no-emit? meta))))

(defn inherit-scope
  "Merge `parent`'s `:call-site` and `:dispatch-id` into `new-scope`
  where `new-scope`'s value is nil. Meta-derived slots are preserved
  as-is (innermost-wins). Per Spec 009 §Handler-scope §Composition."
  [new-scope parent]
  (if (nil? parent)
    new-scope
    (cond-> new-scope
      (nil? (:call-site new-scope))   (assoc :call-site   (:call-site parent))
      (nil? (:dispatch-id new-scope)) (assoc :dispatch-id (:dispatch-id parent)))))

#?(:clj
   (defmacro with-handler-scope
     "Bind `*handler-scope*` to `scope` (a HandlerScope record) for the
     duration of `body`, inheriting `:call-site` and `:dispatch-id`
     from the parent scope where `scope`'s slots are nil. Use at every
     handler-execution boundary (router, fx, cofx, subs, views):

         (trace/with-handler-scope (trace/handler-scope-from-meta :event id meta)
           (run-chain ...))

     Per Spec 009 §Handler-scope."
     [scope & body]
     `(binding [*handler-scope* (inherit-scope ~scope *handler-scope*)]
        ~@body)))

#?(:clj
   (defmacro with-call-site
     "Bind `*handler-scope*` with `:call-site` set to `cs`, inheriting
     the rest. For surface macros (`subscribe`, `inject-cofx`) and
     synchronous error emits in `dispatch!` / `dispatch-sync!`. Per
     Spec 009 §Handler-scope and §`:rf.trace/call-site`."
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
     inheriting the rest. Used by `router/process-event!` to publish
     the cascade's `:dispatch-id` and the envelope's `:call-site` once
     on entry to the drain. Per Spec 009 §Handler-scope and §Dispatch
     correlation."
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

;; ---- emission -------------------------------------------------------------

(defn- deliver-to-epoch-capture!
  "Forward the assembled trace event to the epoch-capture buffer if
  re-frame.epoch has registered its capture hook. The capture hook
  is published through `re-frame.late-bind` (key `:epoch/capture-event`);
  routing through there keeps this namespace free of a require on
  re-frame.epoch and ensures `clear-listeners!` (a user-facing API) does
  NOT wipe the internal capture path. Per Tool-Pair §Time-travel and
  Spec 009 §`register-epoch-listener!`."
  [event]
  ;; Sticky hook (rf2-f72pd) — `:epoch/capture-event` is published once
  ;; at re-frame.epoch load and never withdrawn; this fires on every
  ;; trace emit during a cascade.
  (when-let [capture (late-bind/get-fn-cached :epoch/capture-event)]
    (try
      (capture event)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

;; ---- shared emit substrate ------------------------------------------------
;;
;; The `interop/debug-enabled?` gate stays in the public emit wrappers
;; (NOT in `build-event`) so Closure DCE elides the whole expression at
;; `:advanced` + `goog.DEBUG=false`. Per Spec 009 §Production builds.

(defn- compute-sensitive?
  "Hoist `:sensitive?` from the in-scope handler's registration meta,
  with caller-supplied `:sensitive?` in `tags` winning (queue-time
  `:event/dispatched` computes its own reading). Per Spec 009
  §Privacy / sensitive data in traces."
  [tags scope]
  (let [tag-sensitive? (:sensitive? tags)]
    (cond
      (some? tag-sensitive?) (true? tag-sensitive?)
      :else                  (true? (some-> scope :sensitive?)))))

(defn- stamp-cascade-id
  "Merge the cascade's `:dispatch-id` into `base-tags` so consumers
  can group raw trace events by cascade without inferring from
  sequence. Caller-supplied `:dispatch-id` wins. Per Spec 009
  §Dispatch correlation."
  [base-tags cascade-id]
  (if (and cascade-id (not (contains? base-tags :dispatch-id)))
    (assoc base-tags :dispatch-id cascade-id)
    base-tags))

(defn- build-event
  "Assemble the trace envelope (pure construction; reads
  `*handler-scope*`). `op-type` discriminates the success vs error
  paths — see Spec 009 §Core fields and §Error event shape for the
  hoist contract (`:source` / `:recovery` / `:rf.trace/trigger-handler`
  / `:rf.trace/call-site` / `:sensitive?`).

  Per rf2-twt7m Change 1: `:rf.trace/call-site` rides BOTH error and
  success-path emits when the in-scope cascade was kicked off by a
  call-site-capturing macro (`rf/dispatch` / `rf/dispatch-sync` /
  `rf/subscribe` / `rf/inject-cofx`). Previously gated to errors
  only; widened so consumers (Event lens, Causa, Story) can render
  jump-to-source links from every event in a cascade, not just
  errors."
  [op-type operation tags]
  (let [scope       *handler-scope*
        trigger     (some-> scope :trigger-handler)
        cascade-id  (some-> scope :dispatch-id)
        sensitive?  (compute-sensitive? tags scope)
        error?      (= op-type :error)
        source      (:source tags)
        recovery    (if error?
                      (:recovery tags :no-recovery)
                      (:recovery tags))
        call-site   (some-> scope :call-site)
        ;; Strip hoisted slots from `:tags` so they don't double-up at
        ;; the top level. Exception: the error path KEEPS `:source`
        ;; under `:tags` because boundary-interceptor / error-emit
        ;; sites use it as an emission-site discriminator (e.g.
        ;; `:source :boundary` per Spec 010 §Production builds), and
        ;; consumers already fall back top-level → `:tags :source`.
        ;; Error path additionally merges `{:category operation}`.
        base-tags   (cond-> (dissoc tags :recovery :sensitive?)
                      (not error?) (dissoc :source)
                      error?       (->> (merge {:category operation})))
        tags+       (stamp-cascade-id base-tags cascade-id)]
    (cond-> {:operation operation
             :op-type   op-type
             :id        (next-event-id)
             :time      (interop/now-ms)
             :tags      tags+}
      source               (assoc :source source)
      ;; Success path hoists :recovery only when caller supplied one;
      ;; error path always stamps (defaulting to :no-recovery above).
      (or error? recovery) (assoc :recovery recovery)
      trigger              (assoc :rf.trace/trigger-handler trigger)
      ;; `:rf.trace/call-site` rides BOTH error and success-path
      ;; emits (rf2-twt7m Change 1). Hoisted from the in-scope
      ;; cascade's call-site (envelope's macro-stamped coord or a
      ;; `with-call-site` wrapper around a surface-macro body).
      call-site            (assoc :rf.trace/call-site call-site)
      ;; Top-level `:sensitive? true` stamp. Absent (not `false`)
      ;; when not sensitive — consumers treat absent as false.
      sensitive?           (assoc :sensitive? true))))

(defn- deliver!
  "Side-effect dispatch for an assembled trace envelope: epoch-capture
  fan-out, then ring-buffer push + listener fan-out via the
  `:trace.tooling/deliver!` hook published by `re-frame.trace.tooling`.
  The tooling hook is unregistered in production (the tooling sibling
  ns is not loaded) — the lookup returns nil and the fan-out is
  skipped. Per Spec 009 §Listener invocation rules and rf2-qwm0a."
  [event]
  (deliver-to-epoch-capture! event)
  (when-let [deliver-tooling (late-bind/get-fn-cached :trace.tooling/deliver!)]
    (deliver-tooling event)))

(defn- maybe-project-marks
  "Apply the data-classification marks projection if the marks
  artefact is loaded. Per Spec 015 §Implementation notes
  recommendation B: emit-time path-walk + sentinel substitution.

  The projection hook is published by `re-frame.marks` at ns-load;
  when the marks artefact is absent the hook is unbound and this is
  a no-op pass-through. Inside the existing `interop/debug-enabled?`
  gate (in `emit!`) so production builds DCE the hook lookup along
  with the rest of the trace emit."
  [event]
  ;; Sticky hook (rf2-f72pd) — `:marks/project-trace-event` is
  ;; published once at re-frame.marks load and never withdrawn.
  (if-let [project (late-bind/get-fn-cached :marks/project-trace-event)]
    (project event)
    event))

(defn emit!
  "Emit a trace event. Production builds elide the body entirely
  (Closure DCE on the `interop/debug-enabled?` gate); in dev / JVM
  the envelope is built and delivered to the ring buffer, epoch
  capture, and all registered listeners synchronously.

  Reads `*handler-scope*` to hoist the in-scope slots onto the
  envelope: `:trigger-handler` on every emit, `:dispatch-id` merged
  into `:tags`, `:sensitive?` stamped at top level. Short-circuits
  before allocation when the scope's `:no-emit?` slot is true.

  Per Spec 015 (data classification): after envelope assembly and
  before delivery, the marks-projection hook walks `:tags` to
  substitute `:rf/redacted` and `:rf.size/large-elided` markers at
  paths declared sensitive / large by the in-scope handler's
  registration meta or `add-marks` / `set-marks`. Gated by the same
  `interop/debug-enabled?` so production CLJS bundles DCE the entire
  marks machinery.

  Per Spec 009 §Emitting trace events and §Handler-scope."
  [op operation tags]
  (when interop/debug-enabled?
    ;; `:no-emit?` short-circuit sits *inside* the outer
    ;; `interop/debug-enabled?` gate per Spec 009 §Production builds
    ;; (the outer gate must stand alone for Closure DCE — see
    ;; §Production-elision verification).
    (when-not (true? (some-> *handler-scope* :no-emit?))
      (deliver! (maybe-project-marks (build-event op operation tags))))))

(defn emit-error!
  "Emit a structured error trace event. `:operation` is the error
  category (e.g. `:rf.error/handler-exception`), `:op-type` is
  `:error`, and `:tags` includes `:category`, `:exception`,
  `:where`, etc.

  Reads `*handler-scope*` to hoist `:trigger-handler`, `:call-site`,
  and `:dispatch-id` onto the envelope, and to honour the `:no-emit?`
  short-circuit (symmetric with `emit!`). Per Spec 009 §Error contract
  and §Handler-scope.

  Per Spec 015: the same marks-projection hook runs on error traces so
  exception traces don't accidentally leak sensitive event-args /
  fx-args / cofx-values."
  [error-operation tags]
  (when interop/debug-enabled?
    ;; `:no-emit?` short-circuit sits *inside* the outer
    ;; `interop/debug-enabled?` gate per Spec 009 §Production builds.
    (when-not (true? (some-> *handler-scope* :no-emit?))
      (deliver! (maybe-project-marks (build-event :error error-operation tags))))))

;; ---- late-bind hook registration ------------------------------------------
;; Published through `late-bind` so registrar can emit without requiring
;; this ns (cyclic load order).

(late-bind/set-fn! :trace/emit!       emit!)
(late-bind/set-fn! :trace/emit-error! emit-error!)

;; ---- Public listener-registration surface (rf2-ic1sv pick c) -------------
;;
;; Per pick c: `re-frame.trace/register-listener!` etc. are the
;; canonical app-facing names — the `-trace-` infix dropped because
;; the namespace already says `trace`. Available on both JVM and CLJS;
;; production CLJS bundles rely on user-side `goog.DEBUG` gating of
;; registration call sites for DCE.

(def register-listener!     trace-tooling/register-listener!)
(def unregister-listener!   trace-tooling/unregister-listener!)
(def clear-listeners!       trace-tooling/clear-listeners!)

(def trace-buffer           trace-tooling/trace-buffer)
(def clear-trace-buffer!    trace-tooling/clear-trace-buffer!)
(def configure-trace-buffer! trace-tooling/configure-trace-buffer!)
(def configure              trace-tooling/configure)
