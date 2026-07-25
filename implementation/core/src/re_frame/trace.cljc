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
  `clear-trace-buffer!` / `configure-trace-buffer!`) and
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
  the entire `(when goog.DEBUG (rf/register-listener! :trace …))` block is
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

(def ^:dynamic ^:private *continuation-predicate* nil)
(def ^:dynamic ^:private *epoch-capture-enabled?* true)
(def ^:dynamic ^:private *frame-policy-enabled?* true)
;; Retentionless structural delivery (rf2-vxgfnd.244). When false, an emitted
;; event still streams live to every registered trace listener but is NOT
;; pushed onto any per-frame trace ring. `call-with-structural-delivery` binds
;; it false so an obsolete incarnation A's terminal facts — which carry A's
;; inherited dispatch-id and A's bare frame id — cannot be retained by the
;; ring that id now names (a same-id successor B's ring once B is installed).
(def ^:dynamic ^:private *ring-retention-enabled?* true)

(defn ^:no-doc call-with-continuation-predicate
  "Run `f` with an internal exact-owner predicate guarding the distinct
  callback stages of trace projection, epoch capture, and tooling delivery.
  nil/ordinary callers retain the historical always-continue behaviour."
  [continue? f]
  (let [parent *continuation-predicate*
        combined #(and (or (nil? parent) (parent))
                       (or (nil? continue?) (continue?)))]
    (binding [*continuation-predicate* combined]
      (f))))

(defn ^:no-doc call-with-terminal-continuation-predicate
  "Run the exact-token private teardown recipe under its own authority.

  Unlike ordinary nesting, this intentionally replaces a now-false authored
  event predicate: the destroy recipe is the sole approved closing-incarnation
  exemption and cannot inherit the abandoned event tail's terminal fence."
  [continue? f]
  (binding [*continuation-predicate* continue?]
    (f)))

(defn- continuing? []
  (or (nil? *continuation-predicate*)
      (*continuation-predicate*)))

(defn ^:no-doc continuation-live?
  "Internal shared reading for composite always-on emitters participating in
  the current exact-owner event pipeline. Ordinary callers read true."
  []
  (continuing?))

(defn- snapshot-continuation
  "Capture the CURRENT continuation predicate as a standalone thunk that
  re-reads the caller's exact authority regardless of any later dynamic
  rebinding of `*continuation-predicate*`.

  `deliver!` hands this snapshot to the PUBLIC trace-listener fan-out as the
  before/after `continue?` check, then rebinds `*continuation-predicate*` to
  the neutral scope for the listener bodies themselves. That split is the whole
  of rf2-eaxnai: a listener's nested authored work (dispatch / destroy /
  create — e.g. a same-id successor's `:initial-events` seed) runs under the
  ordinary always-continue authority, while the delivery loop's own checks
  still consult the caller's exact incarnation predicate so destroying the
  current incarnation still suppresses the REMAINING listener fan-out.

  The captured predicate is the live incarnation closure, so the snapshot
  re-checks the registry on each call — a listener that destroys the outer
  incarnation flips a later `(snapshot)` call to false without the snapshot
  ever reading the (now neutral) dynamic var."
  []
  (let [p *continuation-predicate*]
    (fn [] (or (nil? p) (p)))))

(defn- call-with-ordinary-delivery-scope
  "Run `f` under the ordinary delivery defaults the public trace-listener
  fan-out is entitled to — the same four axes `deliver!` restores around an
  inline fan-out (rf2-vf2qke + rf2-eaxnai): epoch capture, frame-no-emit policy
  and ring retention back to their defaults, and the exact-owner continuation
  neutralised.

  `deliver!` gets this for free because an inline fan-out runs inside its
  `binding`. A DEFERRED fan-out (rf2-wxy1c) runs later, at the post-drain
  boundary, long after `deliver!` returned — so without re-establishing the
  scope here a listener body would inherit whatever authority the DRAIN was
  running under. That is precisely the rf2-eaxnai defect: a listener whose
  nested authored work (dispatch / destroy / create) consults a fence that
  belongs to the drain's own event tail gets silently dropped.

  Cross-platform (rf2-uoy6m): CLJS defers drain-owned delivery too, so its flush
  runs after `deliver!` returned and needs the same scope re-established. The
  four dynamic vars exist on both hosts; in a production CLJS bundle this fn is
  referenced only from the DCE-eliminated `debug-enabled?` branch of
  `call-with-deferred-listener-delivery`, so it does not survive elision."
  [f]
  (binding [*epoch-capture-enabled?*  true
            *frame-policy-enabled?*   true
            *ring-retention-enabled?* true
            *continuation-predicate*  nil]
    (f)))

(defn ^:no-doc call-with-deferred-listener-delivery
  "Run `f` as one post-drain trace-listener delivery region — the rf2-wxy1c seam.
  Trace events emitted inside it while the framework owns a frame's `:drain-lock`
  are appended rather than fanned out, and delivered on the way out, serialized
  process-wide, before the enclosing dispatch / drain call returns.

  The drain engine wraps its `:drain-lock` acquire → run → release regions in this
  (`re-frame.router/drain-try!` / `drain-block!` and
  `re-frame.frame/call-serialized-with-drain!`) so that arbitrary listener code is
  never invoked — nor awaited — while a drain lock is held, and so that two
  independent frames draining concurrently cannot enter one listener at once. See
  `re-frame.trace.tooling/call-with-deferred-fanout` for the mechanism and the
  invariants that placement carries.

  Cross-platform behaviour, host-specific WIRING (rf2-uoy6m). The contract is
  uniform: a drain-owned emit is delivered post-drain — settled state only, no
  intra-drain observation or influence — on JVM and CLJS alike. What differs is
  how this fn reaches the tooling wrapper, and only because CLJS has `:advanced`
  DCE that the JVM does not:

    - JVM calls `re-frame.trace.tooling/call-with-deferred-fanout` directly.

    - CLJS reaches it through the `:trace.tooling/call-with-deferred-fanout`
      late-bind hook, gated by `interop/debug-enabled?`. This fn is on the
      always-reachable production drain path, so a STATIC reference to the tooling
      sibling from here would pull the dev-only listener registry + ring machinery
      (and its `check-bundle-isolation.cjs` sentinel) into the production counter
      bundle. `(and debug-enabled? …)` folds to `false` under
      `:advanced` + `goog.DEBUG=false`, DCE-ing the whole branch — hook lookup and
      `call-with-ordinary-delivery-scope` reference included — so a production
      bundle carries no edge into the tooling ns and, with no listener registry
      there, has nothing to defer regardless. In dev the hook resolves to the
      cross-platform `call-with-deferred-fanout`, which runs the single-threaded
      CLJS deferral."
  [f]
  #?(:clj  (trace-tooling/call-with-deferred-fanout f call-with-ordinary-delivery-scope)
     :cljs (if-let [defer (and interop/debug-enabled?
                               (late-bind/get-fn-cached
                                 :trace.tooling/call-with-deferred-fanout))]
             (defer f call-with-ordinary-delivery-scope)
             (f))))

(defn ^:no-doc call-with-structural-delivery
  "Deliver a structural trace without bare-id epoch/policy attribution and
  without any per-frame ring retention.

  Used when an obsolete incarnation must report a lifecycle fact after its
  registry slot may already belong to a same-id successor. The successor's
  frame-no-emit policy cannot suppress the predecessor's required fact, its
  epoch capture cannot buffer it, and — per rf2-vxgfnd.244 — no per-frame ring
  (the successor's included, since predecessor and successor share the bare
  frame id) may retain it. The fact still streams live to every registered
  trace listener exactly once. This is the whole of `retentionless structural
  delivery`: global live delivery, zero durable per-frame residue."
  [f]
  (binding [*epoch-capture-enabled?*   false
            *frame-policy-enabled?*    false
            *ring-retention-enabled?*  false]
    (f)))

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
  slot is fed by the router's classified sensitive-path overlap
  calculation (`:rf/sensitive?` on the scope-meta map) — the per-frame
  sensitive-declarations registry written by the EP-0025 commit-plane
  classification effects, not a schema-attached slot prop and not a
  frame annotation (both removed); the handler-meta `:sensitive?`
  annotation is likewise gone. Per Spec 009 §Handler-scope."
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
     the rest. For surface macros (`subscribe`) and synchronous error
     emits in `dispatch!` / `dispatch-sync!`. Per Spec 009 §Handler-scope
     and §`:rf.trace/call-site`."
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

;; ---- trace-disabled (tool / inspector) frames ----------------------------
;;
;; Per rf2-2qaqh: an inspector tool (Xray, Story, re-frame2-pair) renders
;; its OWN UI inside a dedicated frame (`:rf/xray`). That UI's reactive
;; substrate emits `:sub/run` + `:view/render` trace events on every panel
;; render — and because the shared ring buffer is process-global, the
;; tool's self-instrumentation evicts every APPLICATION event from the
;; ring (observed: 200/200 events were `:rf/xray`; zero app events). The
;; inspector must not flood the buffer it inspects.
;;
;; The fix is a frame-level emission gate, the frame-scoped sibling of the
;; handler-scoped `:rf.trace/no-emit?` (Spec 009 §Trace-emission opt-out):
;; a frame registered with `:rf.trace/frame-no-emit? true` is recorded
;; here, and `emit!` / `emit-error!` short-circuit (no envelope alloc, no
;; delivery) for any event tagged with that frame. Xray marks `:rf/xray`
;; trace-disabled at frame registration (`mount/ensure-xray-frame!`), so
;; tool frames produce no trace at all while application frames are
;; unaffected.
;;
;; Mechanism (not a hardcoded `:rf/xray` literal): `frame.cljc`'s
;; the frame engine reads the config flag and calls `set-frame-no-emit!` —
;; trace.cljc owns the canonical set + predicate so the gate is single-
;; sourced. The set is held under a defonce atom so a hot `:after-load`
;; cycle never wipes prior registrations; the empty-set common case (no
;; tool frame mounted) short-circuits on `(seq …)` before any membership
;; test, so the hot emit path pays a single nil-check when no tool frame
;; is registered.

(defonce ^:private trace-disabled-frames
  ;; Set of frame-ids whose trace emission is suppressed. Populated by
  ;; `frame.cljc`'s engine from the `:rf.trace/frame-no-emit?`
  ;; config flag (and cleared on re-registration when the flag drops).
  (atom #{}))

(defn set-frame-no-emit!
  "Mark / unmark `frame-id` as trace-disabled. When `no-emit?` is truthy
  the frame is added to the trace-disabled set; otherwise it is removed.
  `emit!` / `emit-error!` short-circuit for any event whose `:frame` tag
  is in the set. Idempotent. Per Spec 009 §Trace-emission opt-out
  (frame-level) and rf2-2qaqh.

  The three-argument engine arity performs the liveness predicate INSIDE the
  atom update. The frame engine serializes auxiliary publication and supplies
  the committed config's owner predicate, so a superseded config is rejected
  before this store changes. The two-argument arity remains the direct
  teardown/test primitive."
  ([frame-id no-emit?]
   (set-frame-no-emit! frame-id no-emit? (constantly true)))
  ([frame-id no-emit? current?]
   (swap! trace-disabled-frames
          (fn [disabled]
            (if (current?)
              ((if no-emit? conj disj) disabled frame-id)
              disabled)))
   nil))

(defn frame-trace-disabled?
  "Canonical predicate: true iff `frame-id` is currently registered
  trace-disabled (a tool / inspector frame). Single-sourced here so no
  call site hardcodes `:rf/xray`."
  [frame-id]
  (contains? @trace-disabled-frames frame-id))

(defn clear-frame-no-emit!
  "Reset the trace-disabled-frames set to empty. Test / teardown helper —
  the per-frame flag is otherwise process-sticky (it tracks frame
  registrations, which `reset! frame/frames {}` does not unwind)."
  []
  (reset! trace-disabled-frames #{})
  nil)

(defn clear-frame-no-emit-for!
  "Remove `frame-id` from the trace-disabled set. Per-frame teardown
  counterpart to `set-frame-no-emit!` — routed into `destroy-frame!`
  alongside the per-frame trace-ring release so a destroyed tool /
  inspector frame (e.g. `:rf/xray`) does not leave a permanent entry in
  this process-global atom (rf2-zcl055). `clear-frame-no-emit!` resets
  the WHOLE set; this removes one id. Idempotent — a no-op when the id is
  absent (the common application-frame case). Equivalent to
  `(set-frame-no-emit! frame-id false)`; named for symmetry with the
  destroy-time call site."
  [frame-id]
  (swap! trace-disabled-frames disj frame-id)
  nil)

(defn- ambient-frame-id
  "The frame the runtime is currently running in, read through the
  late-bound `:frame/current-frame-id` hook (published by
  `re-frame.frame` at ns-load; `re-frame.trace` cannot require `frame`
  — `frame` requires `trace`). nil outside any frame-bound scope
  (registry-time, boot-time, a bare REPL emit).

  ONE resolution point. Both the pre-build frame-policy gate
  (`tagged-frame-trace-disabled?`) and the envelope's own `:frame`
  stamp (`stamp-frame`) read the ambient frame here, so the gate and
  the envelope can never disagree about which frame an emit belongs to."
  []
  (when-let [current-frame (late-bind/get-fn-cached :frame/current-frame-id)]
    (current-frame)))

(defn- tagged-frame-trace-disabled?
  "True when the frame this (not-yet-built) trace envelope targets is
  registered trace-disabled. Resolves the frame exactly as
  `build-event`'s `stamp-frame` will a moment later — the explicit
  `:frame` tag when the emit site supplied one, else `ambient-frame-id`
  — so the suppression gate and the envelope it gates can never
  disagree about which frame an emit belongs to.

  The ambient tier is load-bearing (rf2-yl4c0s): without it an
  un-tagged emit under a disabled tool frame ESCAPED suppression, and
  the inspector's own reactivity leaked into the ring it inspects. The
  empty-set common case (no tool frame mounted) short-circuits before
  ANY frame resolution — including the late-bind hook lookup — so the
  hot emit path pays a single nil-check when no inspector is running."
  [tags]
  (let [disabled @trace-disabled-frames]
    (and (seq disabled)
         (contains? disabled (or (:frame tags) (ambient-frame-id))))))

;; ---- canonical frame reader (rf2-7737vq Stage A) --------------------------
;;
;; Mike's ruling: a RAW trace event carries frame identity ONLY at
;; `[:tags :frame]` — there is no public top-level `:frame` on the raw
;; trace-event shape (the producer stamps it under `:tags`; see `build-
;; event` and the router/cofx emit sites). Derived / projection records
;; (cascade bundles, `:rf/epoch-record`s, dispatch consequences, cursor /
;; summary records) carry frame identity at TOP-LEVEL `:frame` instead.
;;
;; `trace-event-frame` is the ONE canonical reader the instrumentation /
;; trace contract owns for the raw-event shape. Consumers (Xray, Story,
;; story-mcp, machines-viz, re-frame2-pair) read a raw trace event's
;; frame through this accessor rather than reaching into `[:tags :frame]`
;; (or a dual `(or (get-in ev [:tags :frame]) (:frame ev))` read) at each
;; call site. Pre-alpha posture: one shape, one reader, no compatibility
;; ambiguity. Per Spec 009 §Core fields (`:frame` rides under `:tags`)
;; and Tool-Pair §Identity spellings.

(defn trace-event-frame
  "Return the frame-id a RAW trace event targets — its `[:tags :frame]`
  slot — or nil when the event is not frame-qualified (registry-time /
  boot-time emits outside any in-flight cascade).

  This is the single canonical reader for frame identity on the raw
  trace-event shape, owned by the Spec 009 instrumentation / trace
  contract. Raw trace events carry frame identity ONLY under
  `[:tags :frame]` (per Spec 009 §Core fields — `:source` / `:recovery`
  hoist top-level, but `:frame` rides under `:tags`). Derived /
  projection records (cascade bundles, `:rf/epoch-record`s, dispatch
  consequences, cursor / summary records) instead carry frame identity
  at TOP-LEVEL `:frame` — read those records' `:frame` slot directly,
  not via this accessor.

  Tool consumers MUST read a raw trace event's frame through this
  accessor rather than hardcoding the `[:tags :frame]` path. Per
  rf2-7737vq and Tool-Pair §Identity spellings (`:frame` is the single
  bare carve-out tag on the raw layer)."
  [trace-event]
  (get-in trace-event [:tags :frame]))

;; `frame-of` reads cleanly as the one-word form some call sites prefer
;; (`(trace/frame-of ev)`). Same accessor, same raw-event contract — an
;; alias, not a second shape.
(def ^{:doc "Alias of `trace-event-frame` — the canonical raw trace-event
  frame reader. See `trace-event-frame`."}
  frame-of trace-event-frame)

;; ---- why frame gets a reader but sub-id does not (rf2-9x8q1c) -------------
;; Subscription identity ALSO has two spellings — a raw trace event tags it
;; `:rf.sub/id` (under `[:tags …]`, the same raw layer `:frame` rides), while
;; a DERIVED `:sub-runs` projection row keys it `:sub-id` at top level — so by
;; analogy with frame identity it would seem to want a `trace-event-sub-id`
;; sibling reader here. It deliberately does NOT get one. The frame reader
;; earns its keep because a single tool consumer reads frame identity off BOTH
;; raw events and derived records in one walk, so it needs one accessor that
;; spans both shapes. Subscription identity has no such dual-shape consumer:
;; the two spellings sit on opposite sides of ONE projection boundary
;; (`re-frame.epoch.capture/sub-run-row`, which reads the raw `:rf.sub/id` tag
;; and writes the derived `:sub-id` key). Raw-side consumers (data
;; classification, the sub-run/dispose emit sites) read `:rf.sub/id` from
;; `:tags`; derived-side consumers (epoch state, Xray panels) read `:sub-id`
;; from already-projected rows; nothing reads both. A shared reader would have
;; zero call sites — it would add vocabulary, not remove a hardcoded-path
;; hazard. The boundary, not a reader, is the right shape here.

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

(defn- stamp-dispatch-id
  "Merge the run's `:rf.trace/dispatch-id` into `base-tags` so
  consumers can group raw trace events by run without inferring
  from sequence. Caller-supplied `:rf.trace/dispatch-id` wins. Per
  Spec 009 §Dispatch correlation — the cross-cutting correlation spine
  lives under `:rf.trace/*` (Conventions §`:rf.trace/*`)."
  [base-tags dispatch-id]
  (if (and dispatch-id (not (contains? base-tags :rf.trace/dispatch-id)))
    (assoc base-tags :rf.trace/dispatch-id dispatch-id)
    base-tags))

(defn- stamp-frame
  "Merge the emit's frame into `base-tags` under the canonical raw
  trace-event key `:frame`, so EVERY event emitted inside a
  frame-bound run carries frame identity at the one path Spec 009
  §Frame identity on the raw event designates — the path
  `trace-event-frame` reads.

  Spec 009 says a raw trace event carries frame identity ONLY under
  `[:tags :frame]`, and that every emit site that knows the frame
  includes it there. Emit sites that pass an explicit `:frame` tag
  (the router, cofx, error and frame-lifecycle sites) still win —
  a `:rf.frame/created` for a dynamically constructed frame B is
  tagged `{:frame B}` while the ambient frame is still A, and that
  explicit tag is authoritative. The stamp only supplies the tag for
  emit sites that DIDN'T, which the runtime otherwise leaves with no
  frame identity at all.

  rf2-hbmeb is what that costs. The whole `:rf.resource/*` /
  `:rf.mutation/*` family stamps its frame as the family EVIDENCE key
  `:rf.frame/id` (the qualified carried-frame spelling of Spec 016 /
  EP-0002, alongside `:resource/key` and `:generation`) and never
  stamped the routing tag, so `trace-event-frame` returned nil for
  every row in the family. `re-frame.epoch.capture/capture-event!`
  buffers only frame-resolvable events, so a real `ensure` /
  `release-owner` cascade put 7 family rows on the bus and 0 into the
  3 epoch records it settled — the resource lifecycle was absent from
  every epoch record a tool could read, and the epoch-side resource
  egress projector had never run over a real record.

  Stamping HERE rather than at ~100 family emit sites is the point:
  the fact is supplied once, in the producer every reader already
  goes through, so no future emit site can forget it. It also lets the
  duplicated ambient-frame fallback chains downstream
  (`trace.tooling/push-to-ring!`, `epoch.capture/capture-event!`)
  collapse onto the single canonical `[:tags :frame]` read."
  [base-tags]
  (if (contains? base-tags :frame)
    base-tags
    (if-let [frame-id (ambient-frame-id)]
      (assoc base-tags :frame frame-id)
      base-tags)))

(def ^:private uncorrelated-op-types
  "Op-type classes whose emits are STRUCTURAL — not part of any cascade's
  causal event chain — so they must never inherit the in-scope cascade's
  `:rf.trace/dispatch-id`, even when they fire while a cascade is on the
  stack. Per Spec 009 §Dispatch correlation, frame-lifecycle emits (op-type
  `:rf.frame`: `:rf.frame/created` / `:rf.frame/re-registered` /
  `:rf.frame/destroyed` / `:rf.frame/drain-interrupted`) stay UNCORRELATED.

  The load-bearing case (rf2-7eel71): when a frame construction runs inside frame A's
  handler / fx scope — e.g. an fx that dynamically creates a modal / panel
  frame B — its `:rf.frame/created` emit is tagged `{:frame B}` but the
  scope carries A's cascade dispatch-id. Stamping A's id onto B's marker
  makes the epoch capture seam bypass its orphan-drop arm (the marker's id
  is non-nil) and buffer it into B; B never runs an event carrying A's id,
  so the marker strands in B's buffer for the frame's whole lifetime and is
  swept into any later halt record. Leaving frame-lifecycle emits
  uncorrelated (nil dispatch-id) is precisely what lets that orphan-drop arm
  keep the cross-frame marker out of the sibling frame's harvested record.
  `:rf.frame/destroyed` / `:rf.frame/drain-interrupted` already self-heal,
  but the rule is single-sourced here for the whole op-type class."
  #{:rf.frame})

(defn- build-event
  "Assemble the trace envelope (pure construction; reads
  `*handler-scope*`). `op-type` discriminates the success vs error
  paths — see Spec 009 §Core fields and §Error event shape for the
  hoist contract (`:source` / `:recovery` / `:rf.trace/trigger-handler`
  / `:rf.trace/call-site` / `:sensitive?`).

  Per rf2-twt7m Change 1: `:rf.trace/call-site` rides BOTH error and
  success-path emits when the in-scope cascade was kicked off by a
  call-site-capturing macro (`rf/dispatch` / `rf/dispatch-sync` /
  `rf/subscribe`). Previously gated to errors
  only; widened so consumers (Event lens, Xray, Story) can render
  jump-to-source links from every event in a cascade, not just
  errors."
  [op-type operation tags]
  (let [scope       *handler-scope*
        trigger     (some-> scope :trigger-handler)
        dispatch-id (some-> scope :dispatch-id)
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
        ;; Structural (frame-lifecycle) emits stay UNCORRELATED — they never
        ;; inherit the in-scope cascade's dispatch-id, so the epoch capture
        ;; seam's orphan-drop arm keeps a cross-frame `:rf.frame/created` /
        ;; `:rf.frame/re-registered` marker out of a SIBLING frame's harvested
        ;; record (rf2-7eel71). See `uncorrelated-op-types`.
        ;; `stamp-frame` supplies the canonical `[:tags :frame]` routing
        ;; tag for emit sites that didn't (rf2-hbmeb); it runs for EVERY
        ;; op-type, including the uncorrelated structural ones — those are
        ;; exempt from inheriting the cascade's DISPATCH-ID, not from
        ;; carrying their own frame (they already tag it explicitly, so the
        ;; stamp is a no-op there).
        tags+       (stamp-frame
                      (if (contains? uncorrelated-op-types op-type)
                        base-tags
                        (stamp-dispatch-id base-tags dispatch-id)))]
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
  skipped. Per Spec 009 §Listener invocation rules and rf2-qwm0a.

  Structural-delivery scope is a property of the CURRENT outer envelope,
  NOT ambient authority for the external callbacks it fans out to
  (rf2-vf2qke). By the time the public tooling listener fan-out runs, every
  structural decision has already been consumed against THIS envelope:
  epoch capture was gated above, frame-no-emit was gated in `emit!` /
  `emit-error!`, and the outer ring-retention decision is captured here into
  `retain?` and passed explicitly to the tooling hook for the outer ring
  push. So the fan-out restores ordinary delivery defaults: a listener that
  reacts to A's structural terminal fact by dispatching or emitting its OWN
  legitimate nested work into a same-id successor B or an unrelated frame C
  must run that work under NORMAL scope — normal epoch capture, its own
  frame-no-emit policy, normal per-frame ring retention, AND the neutral
  continuation scope (rf2-eaxnai) — rather than inheriting A's
  retentionless/no-capture/no-policy/exact-owner scope. A listener that
  explicitly re-requests structural semantics via
  `call-with-structural-delivery` re-binds the flags false and is honoured.

  The exact-owner continuation is the fourth axis restored to ordinary defaults
  here (rf2-eaxnai): the outer envelope may be fenced to A's exact incarnation
  (`call-with-continuation-predicate`), but that fence exists to suppress
  A's own remaining framework-owned trace stages after A is lost — it must NOT
  leak into a public listener's nested authored work. Before this fix a listener
  that destroyed A and created same-id B with `:initial-events` had B's seed
  `dispatch-sync!` consult `continuation-live?`, inherit A's now-false predicate,
  and get silently dropped at `build-envelope` — leaving B live with `{}` and no
  recovery (make-frame's reuse/no-reseed law). The neutral rebinding frees the
  listener body; the `outer-continue?` snapshot below preserves the loop's exact
  before/after suppression of A's remaining fan-out."
  [event]
  (when (and *epoch-capture-enabled?* (continuing?))
    (deliver-to-epoch-capture! event))
  ;; Epoch capture is a separate callback-bearing publication. If it destroys
  ;; A, do not deliver the same stale event into tooling/rings/listeners.
  (when (continuing?)
    (when-let [deliver-tooling (late-bind/get-fn-cached :trace.tooling/deliver!)]
      ;; Capture the outer envelope's ring-retention decision AND the caller's
      ;; exact continuation predicate BEFORE restoring ordinary defaults. The
      ;; tooling hook (in the sibling ns, which cannot see these vars) reads
      ;; `retain?` from the argument, not the dynamic var — so restoring the
      ;; flags to their ordinary defaults around the whole call leaves the outer
      ;; ring push governed by the captured structural decision while any
      ;; listener-triggered nested emit runs under normal scope (rf2-vf2qke).
      ;; `outer-continue?` (rf2-eaxnai) is the same idea for the exact-owner
      ;; continuation: the loop's before/after `continue?` checks consult this
      ;; snapshot (retaining A's predicate so A destruction suppresses A's
      ;; remaining listeners), while `*continuation-predicate*` is neutralised in
      ;; the binding below so each listener BODY's nested authored work is not
      ;; strangled by A's now-false predicate.
      (let [retain?         *ring-retention-enabled?*
            outer-continue? (snapshot-continuation)]
        (binding [*epoch-capture-enabled?*  true
                  *frame-policy-enabled?*   true
                  *ring-retention-enabled?* true
                  *continuation-predicate*  nil]
          (try
            (deliver-tooling event outer-continue? retain?)
            (catch #?(:clj Throwable :cljs :default) e
              (when (outer-continue?) (throw e)))))))))

(defn- hoist-projected-sensitive
  "Hoist a classification-projection-stamped `[:tags :sensitive?]` up to
  the envelope's TOP LEVEL (and strip it from `:tags`), mirroring the
  `build-event` posture.

  Why this is load-bearing (rf2-md2wn0 — privacy correctness): some
  projection clauses decide sensitivity DURING the classification
  projection rather than at emit time, and stamp `[:tags :sensitive?]`
  there — e.g.
  `re-frame.classification/project-machine-error-tags` (a sensitive machine's
  `:exception-data`) and `project-sub-tags` (a registration-classified
  sub output, or one that fails closed on a nil frame — EP-0025 has no
  sensitivity propagation). The classification projection runs
  AFTER `build-event`, so `compute-sensitive?` never saw that signal
  and the top-level `:sensitive?` flag is absent.

  The MCP egress gate (`re-frame.mcp-base.sensitive/sensitive-event?`)
  reads the TOP-LEVEL `:sensitive?` only (Spec 009 §Privacy — the
  `:rf/trace-event` schema types `:sensitive?` at the root). Without
  this hoist a projection-classified-sensitive event egresses as
  non-sensitive: `strip-sensitive` does NOT drop it when the boot gate
  is OFF (include? false), the fail-closed whole-event drop never
  fires, and per-slot redaction is the only line of defence. This hoist
  restores the defence-in-depth contract uniformly for EVERY projection
  clause that stamps `[:tags :sensitive?]` — present and future.

  Caller-supplied / scope-derived sensitivity already hoisted by
  `build-event` is untouched: that path strips `:sensitive?` from
  `:tags` BEFORE delivery, so a tag-level flag here can only be one a
  projection clause added. We OR with any existing top-level flag so a
  prior hoist is never demoted."
  [event]
  (if (and (map? event)
           (true? (some-> event :tags :sensitive?)))
    (-> event
        (assoc :sensitive? true)
        (update :tags dissoc :sensitive?))
    event))

(defn- maybe-project-marks
  "Apply the data-classification projection if the classification
  artefact is loaded. Per Spec 015 §Implementation notes
  recommendation B: emit-time path-walk + sentinel substitution.

  The projection hook is published by `re-frame.classification` at ns-load;
  when the classification artefact is absent the hook is unbound and this is
  a no-op pass-through. Inside the existing `interop/debug-enabled?`
  gate (in `emit!`) so production builds DCE the hook lookup along
  with the rest of the trace emit.

  After projection, `hoist-projected-sensitive` lifts any
  projection-stamped `[:tags :sensitive?]` to the top level
  (rf2-md2wn0) so the MCP egress gate — which reads the top-level flag
  — fails closed on projection-classified-sensitive events."
  [event]
  ;; Sticky hook (rf2-f72pd) — `:classification/project-trace-event` is
  ;; published once at re-frame.classification load and never withdrawn.
  (if-let [project (late-bind/get-fn-cached :classification/project-trace-event)]
    (hoist-projected-sensitive (project event))
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
  before delivery, the classification-projection hook walks `:tags` to
  substitute `:rf/redacted` and `:rf.size/large-elided` markers at
  paths declared sensitive / large by the in-scope handler's
  registration meta or the EP-0025 commit-plane `:sensitive` / `:large` effects. Gated by the same
  `interop/debug-enabled?` so production CLJS bundles DCE the entire
  classification machinery.

  Per Spec 009 §Emitting trace events and §Handler-scope."
  [op operation tags]
  (when interop/debug-enabled?
    ;; `:no-emit?` short-circuit sits *inside* the outer
    ;; `interop/debug-enabled?` gate per Spec 009 §Production builds
    ;; (the outer gate must stand alone for Closure DCE — see
    ;; §Production-elision verification). The frame-level gate
    ;; (rf2-2qaqh) is its sibling: an event tagged with a trace-
    ;; disabled (tool / inspector) frame is suppressed before any
    ;; envelope is built — the inspector's own reactivity must not
    ;; flood the buffer it inspects.
    (when-not (or (true? (some-> *handler-scope* :no-emit?))
                  (and *frame-policy-enabled?*
                       (tagged-frame-trace-disabled? tags)))
      (let [event     (build-event op operation tags)
            projected (when (continuing?)
                        (try
                          (maybe-project-marks event)
                          (catch #?(:clj Throwable :cljs :default) e
                            (when (continuing?) (throw e)))))]
        ;; Classification projection is the first callback-bearing stage.
        (when (continuing?)
          (deliver! projected))))))

(defn emit-error!
  "Emit a structured error trace event. `:operation` is the error
  category (e.g. `:rf.error/handler-exception`), `:op-type` is
  `:error`, and `:tags` includes `:category`, `:exception`,
  `:where`, etc.

  Reads `*handler-scope*` to hoist `:trigger-handler`, `:call-site`,
  and `:dispatch-id` onto the envelope, and to honour the `:no-emit?`
  short-circuit (symmetric with `emit!`). Per Spec 009 §Error contract
  and §Handler-scope.

  Per Spec 015: the same classification-projection hook runs on error
  traces so exception traces don't accidentally leak sensitive event-args /
  fx-args / cofx-values."
  [error-operation tags]
  (when interop/debug-enabled?
    ;; `:no-emit?` short-circuit sits *inside* the outer
    ;; `interop/debug-enabled?` gate per Spec 009 §Production builds.
    ;; The frame-level gate (rf2-2qaqh) suppresses errors emitted from
    ;; a trace-disabled (tool / inspector) frame too — symmetric with
    ;; `emit!`.
    (when-not (or (true? (some-> *handler-scope* :no-emit?))
                  (and *frame-policy-enabled?*
                       (tagged-frame-trace-disabled? tags)))
      (let [event      (build-event :error error-operation tags)
            projected (when (continuing?)
                        (try
                          (maybe-project-marks event)
                          (catch #?(:clj Throwable :cljs :default) e
                            (when (continuing?) (throw e)))))]
        (when (continuing?)
          (deliver! projected))))))

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
