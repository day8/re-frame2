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

  The machine fx-ids `:rf.machine/spawn` and `:rf.machine/destroy` are
  registered by `re-frame.machines` (ships in `day8/re-frame2-machines`)
  at its ns-load time via the regular `reg-fx` path. They are NOT
  reserved in core's case-block — apps that don't pull in the machines
  artefact don't carry the trace strings or the handler for them."
  (:require [re-frame.registrar :as registrar]
            [re-frame.error :as error]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.performance :as performance
             #?@(:cljs [:include-macros true])]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- registration ---------------------------------------------------------

(defn register-with-marks!
  "Shared registration tail for `reg-fx` and `reg-cofx` (rf2-a3pl56). Both
  register sites validate any declared `:sensitive` / `:large` marks fail-loud
  BEFORE the registrar write (rf2-ehexnw — the marks themselves are DERIVED
  from the registrar meta at `marks-for` read time, no imperative stash), then
  write the registrar entry as the merged source-coords + the `:handler-fn`
  callable slot. `kind` is `:fx` / `:cofx` (drives both the marks-validation
  kind and the registrar kind); `extra-slots` is an optional map of
  kind-specific registrar slots merged on top — `nil` for `reg-fx`, the
  `:recordable?` / `:provided?` grade flags for `reg-cofx`. `re-frame.cofx`
  reaches this through its existing `fx/` alias (the same single-definition
  pattern as `runs-on-platform?` / `platform-for-frame-record`, rf2-4ymm0)."
  [kind id meta handler-fn extra-slots]
  (when-let [validate! (late-bind/get-fn :marks/validate-marks!)]
    (validate! kind meta))
  (registrar/register! kind id (merge (assoc (source-coords/merge-coords meta)
                                             :handler-fn handler-fn)
                                      extra-slots)))

(defn reg-fx
  "Register an effect handler under `id`. The handler runs when a
  `reg-event` handler returns an effect-map carrying `[id args]` inside its
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
      :schema     Malli schema for `args` (per Spec 010 §:schema on fx
                  registrations; rf2-ieu0i).
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
      (rf/reg-event :user/login-failed
        (fn [_ [_ reason]]
          {:fx [[:my/notify {:level :error :message (str \"Login failed: \" reason)}]]}))

  Framework-shipped fx (`:dispatch`, `:dispatch-later`, `:rf.http/managed`,
  `:rf.nav/push-url`, ...) are documented in `spec/API.md §Effect-map
  shape` and their per-feature Spec; introspect via
  `(rf/handler-meta :fx <id>)`.

  See also: `reg-cofx` (the input-side counterpart), `clear-fx`,
  `reg-event` (the consumer)."
  [id metadata-or-handler & maybe-handler]
  (let [[meta handler-fn]
        (if (map? metadata-or-handler)
          [metadata-or-handler (first maybe-handler)]
          [{} metadata-or-handler])]
    (register-with-marks! :fx id meta handler-fn nil)
    id))

(defn clear-fx
  "Unregister an fx handler. Zero-arity clears every registered fx;
  one-arity clears the named one. Hot-reload tools and test fixtures
  call this between rebuilds.

  Returns nil. See also: `reg-fx`, the user-facing surface `rf/clear-fx`
  (this is the underlying fn — they point at the same value)."
  ([] (registrar/clear-kind! :fx))
  ([id] (registrar/unregister! :fx id)))

;; ---- EP-0023 inline-registration lowering (rf2-ffc6s0) --------------------
;;
;; An image's inline `:registrations` `:reg-fx` entry carries the raw effect
;; handler fn under `:impl`. For the inline fx to RUN when an event handler
;; emits it through a frame-targeted cascade, the assembled generation's
;; resolver descriptor must carry the SAME runnable slot `reg-fx` installs —
;; `:handler-fn` (the fx-walker reads it via `registrar/handler :fx`). Closes
;; the EP-0023 §Image Fragments "same runtime descriptor shape" contract for
;; fx. Published via late-bind (image-assembly cannot static-require this ns).

(defn lower-inline-fx
  "Lower an inline `:reg-fx` descriptor's raw fn body into the runnable fx slot
  `reg-fx` installs (`:handler-fn`). `_meta` is the inline entry's metadata map
  (unused — the descriptor already carries the inline `:metadata`; the runtime
  `:platforms` predicate reads that map directly); `impl` is the raw
  `(fn [ctx args] …)` handler. Returns ONLY the runnable slot so image-assembly
  merges it onto the descriptor, preserving `:impl` + provenance."
  [_meta impl]
  {:handler-fn impl})

(late-bind/set-fn! :image/lower-inline-fx lower-inline-fx)

;; ---- the platform predicate -----------------------------------------------

(defn runs-on-platform?
  "Does the `:platforms` metadata permit `active-platform`?

  Per Spec 011 §634-642 the `:platforms` slot applies symmetrically to
  `reg-fx` AND `reg-cofx`. Default is `#{:client :server}` (both
  permitted). The same predicate body answered both questions —
  re-frame.cofx aliases this fn so the contract has one definition
  (rf2-4ymm0 SP6)."
  [meta active-platform]
  (let [platforms (:platforms meta #{:client :server})]
    (contains? platforms active-platform)))

(defn platform-for-frame-record
  "Resolve the active platform for a frame given its (already-resolved)
  frame record. The frame's `:config :platform` override (set by the
  `:ssr-server` preset, or any user-supplied frame config) takes
  precedence over the host-wide platform marker
  (`interop/active-platform`, toggled via `re-frame.core/init-platform`).

  Single definition of the per-frame platform resolution shared by the
  router's `:fx` walk (`router/run-fx-effects!`) and the cofx injector
  (`cofx/active-platform-for-frame`, which resolves the record from a
  frame-id first). Both sites previously inlined the identical
  `(or (-> rec :config :platform) (interop/active-platform))` kernel."
  [frame-record]
  (or (-> frame-record :config :platform)
      (interop/active-platform)))

;; ---- do-fx ----------------------------------------------------------------

(declare dispatch-fx-handler)

;; ---- reserved fx-id table -------------------------------------------------
;;
;; Per Conventions §Reserved fx-ids — `:dispatch`, `:dispatch-later`,
;; `:rf.fx/reg-flow`, `:rf.fx/clear-flow` resolve to runtime-internal
;; callables held behind `late-bind` hooks (avoiding cyclic loads against
;; the router and flows namespaces). Each entry maps the fx-id to a small
;; body-fn so the case-block in `handle-one-fx` is a dispatch off this
;; table — adding a reserved fx-id is a data edit, not a code edit.
;;
;; Body-fn signature: `(fn [frame-id args])`. It is invoked inside the
;; perf bracket; on success it returns; the caller emits `:rf.fx/handled`
;; uniformly. When a hook is unregistered (the producing artefact is not
;; on the classpath) the body-fn is a no-op — matching the pre-existing
;; `when-let [f (late-bind/get-fn ...)]` shape across all four sites.
;;
;; `:dispatch-later` carries its own body because it wraps the hook call
;; in `set-timeout!` and destructures `{:keys [ms event]}` from args; the
;; other three are uniform `(hook args {:frame frame-id})` calls.

(defn- call-frame-scoped-hook!
  "Resolve `hook-key` and invoke it with `(hook args {:frame frame-id})`.
  When the hook is unregistered (producing artefact absent), this is a
  no-op — matches the pre-refactor `when-let` shape."
  [hook-key frame-id args]
  (when-let [f (late-bind/get-fn hook-key)]
    (f args {:frame frame-id})))

;; ---- reserved-fx override tier (rf2-snsup5) -------------------------------
;;
;; Per Conventions §Reserved fx-ids, the reserved set is tiered by the
;; STATE-INSTALLATION criterion (Mike-ruled 2026-06-10): a reserved fx-id
;; stays OVERRIDABLE via `:fx-overrides` (fn-value OR keyword-redirect) when
;; its body only ROUTES dispatches or touches host/browser state WITHOUT
;; writing the frame runtime-db; it HARD-REJECTS the override (emit
;; `:rf.error/reserved-fx-override`, run the real reserved/registered body)
;; when its body INSTALLS or CLEARS durable frame-internal runtime state that
;; later framework behaviour depends on.
;;
;;   OVERRIDABLE: `:dispatch`, `:dispatch-later` (pure router-queue routing),
;;                `:rf.machine/dispatch-to-system` (pure lookup-then-dispatch,
;;                zero state writes), `:rf.nav/push-url`, `:rf.nav/replace-url`,
;;                `:rf.nav/scroll`, `:rf.nav/capture-scroll` (host-API
;;                wrappers; no frame runtime-db write). These are the
;;                legitimate test/story stubbing affordance (capture a
;;                dispatch without queueing it, no-op a navigation) and are
;;                NOT in the reject set below — they flow through the existing
;;                fn-value / keyword-redirect resolution unchanged.
;;
;;   HARD-REJECT (the set below): the state-installing lifecycle fxs. Their
;;                bodies install/clear durable runtime-db state (machine
;;                snapshots, flow registry entries) or thread a correctness-
;;                critical nav-token; an override that stubs them out would
;;                break framework behaviour FAR FROM the override site (a
;;                spawned actor's later dispatches become `:rf.error/no-such-
;;                handler`; a dropped nav-token silently defeats stale-result
;;                suppression). Per rf2-snsup5: reject-all (option B) is OFF
;;                the table — it would reverse the rf2-nrpj1 ruling that
;;                fn-value overrides of `:dispatch` / `:dispatch-later`
;;                pre-empt the reserved body (pinned in fx_test.clj:1042-1107).
;;
;; `:rf.fx/reg-flow` / `:rf.fx/clear-flow` resolve via `reserved-fx-handlers`
;; (the reserved table); `:rf.machine/spawn` / `:rf.machine/destroy` /
;; `:rf.route/with-nav-token` resolve via the registrar (registered by their
;; feature artefacts through the regular `reg-fx` path). The reject is
;; enforced uniformly in `handle-one-fx` BEFORE resolution, so it covers both
;; resolution paths.
(def ^:private rejected-reserved-fx-ids
  "Reserved fx-ids whose `:fx-overrides` are HARD-REJECTED (rf2-snsup5).
  Overriding any of these installs/clears durable frame runtime-db state
  (machines, flows) or drops a correctness-critical nav-token — the override
  is ignored, `:rf.error/reserved-fx-override` is emitted, and the real
  reserved/registered body runs. In production the effective override map is
  stripped of these keys LOUDLY before `do-fx` (see `strip-rejected-overrides`)."
  #{:rf.machine/spawn
    :rf.machine/destroy
    :rf.fx/reg-flow
    :rf.fx/clear-flow
    :rf.route/with-nav-token})

;; Inheritable envelope fields — copied from parent to child when
;; `:dispatch` / `:dispatch-later` queue a new envelope. Per Spec 002
;; §Cascade propagation (line 1162) and §Drain-loop pseudocode
;; `inheritable-envelope-keys` (lines 947-952). `:event` is NOT
;; inherited — the child gets its own.
;; Per EP-0010 (rf2-s9ss0t) / EP-0017 (rf2-alc1lf) `:rf.cofx` (the recordable
;; coeffect TOKEN) is NOT inherited — a child dispatch is a DISTINCT causal
;; token, so `build-envelope` stamps it a fresh `:rf/time-ms` rather than
;; copying the parent's (Spec 002 §Dispatch Envelope Stamping). Its absence
;; from this list is the mechanism. (`:dispatched-at` was retired in the same
;; change — EP-0010 rider b — so there is nothing left to exclude on that axis.)
;; Per rf2-ejtpd, `:source` is ALSO not inherited — each fx-emitted
;; child dispatch's `:source` reflects its immediate trigger
;; (`:fx-dispatch` / `:fx-dispatch-later`), stamped by the fx handler
;; below. Inheriting `:source` would mis-report a `:dispatch` fx three
;; levels deep as `:source :ui`.
;;
;; Per EP-0017 §6 (rf2-aflgcc): the per-call `:rf.cofx/mint-policy` IS inherited
;; — DISTINCT from the `:rf.cofx` token above. The token is per-causal-run
;; (a child gets its own); the MINT POLICY is the strict/replay DISCIPLINE that
;; must hold across the whole cascade. A replay or `:strict` test that dispatched
;; the parent intends the SAME no-host-read discipline for every child it emits
;; via `:dispatch` / `:dispatch-later`; without inheritance a child silently
;; falls back to the frame-config / `:live` default and re-mints a fresh
;; nondeterministic value, the exact silent-fallback EP-0017 §6 forbids. The
;; envelope carries `:rf.cofx/mint-policy` ONLY when it was supplied per-call
;; (`build-envelope` stamps it conditionally), so inheriting it carries forward
;; exactly the per-call `:strict` / `:explicit-live` override — the frame-config
;; tier is untouched (the child reads its own frame's policy fresh) and the
;; override-free hot path inherits nothing (the key is absent on the parent).
(def ^:private inheritable-envelope-keys
  [:frame :fx-overrides :interceptor-overrides :trace-id :origin
   :rf.cofx/mint-policy])

(defn- child-dispatch-opts
  "Project the parent envelope's inheritable keys onto the opts map for a
  child dispatch. Per Spec 002 §Cascade propagation: the dispatched
  child inherits `:frame`, `:fx-overrides`, `:interceptor-overrides`,
  `:trace-id`, `:origin`, and — per EP-0017 §6 (rf2-aflgcc) — the per-call
  `:rf.cofx/mint-policy` (the strict/replay discipline holds across the whole
  cascade; the `:rf.cofx` token itself is NOT inherited — the child gets its
  own causal run). `:source` is NOT inherited (rf2-ejtpd) — the
  fx handler stamps the specific `:fx-dispatch` / `:fx-dispatch-later`
  value via a separate `:source` opt on the call site. When
  `parent-envelope` is nil (caller did not thread one through — legacy
  routing-artefact callers or test fixtures), falls back to
  `{:frame frame-id}` so single-key propagation still holds.

  Per rf2-1ve9h (Mike-approved Option A, 2026-05-28) the prior
  parallel `:rf/dispatch-origin` axis was collapsed into `:source` —
  the child dispatch's `:source` is now stamped directly by the
  `:dispatch` / `:dispatch-later` fx-handler call site as
  `:fx-dispatch` / `:fx-dispatch-later` (or `:machine-action` when the
  emitting parent handler is a machine, per rf2-c3990). No origin slot
  to inherit / override on the child opts here.

  Per rf2-j20a7 / Spec 005 §Level 4: when the parent envelope is tagged
  `:rf.machine/internal? true` (the router stamps it in
  `run-handler-cascade!` whenever the emitting handler is a machine),
  the child is a machine-internal continuation event and inherits the
  flag. `re-frame.router/dispatch!` reads it to insert the child at the
  FRONT of the queue so the machine settles its macrostep to quiescence
  before the next external event. Unlike the trace-only inheritable
  keys, this is a runtime ordering flag — carried unconditionally.

  Per rf2-snsup5 §Cascade-exclusion: the inherited `:fx-overrides` is
  filtered against `rejected-reserved-fx-ids` so a reject-tier reserved-fx
  override (state-installing lifecycle fx / nav-token) NEVER propagates into
  a machine-internal or fx-emitted child dispatch. A captured-dispatch test
  override of `:dispatch` rides the cascade as before (it's OVERRIDABLE);
  a `:rf.machine/spawn` / `:rf.fx/reg-flow` / `:rf.route/with-nav-token`
  entry that slipped onto an envelope is dropped from the child opts (it is
  also rejected at the dev per-call site and the production prod-strip — this
  is the third defence layer, keeping the reject local to the override site
  rather than leaking it transitively across the whole `[:dispatch …]`
  cascade)."
  [frame-id parent-envelope]
  (if parent-envelope
    (cond-> (select-keys parent-envelope inheritable-envelope-keys)
      (:rf.machine/internal? parent-envelope)  (assoc :rf.machine/internal? true)
      ;; Cascade-exclusion (rf2-snsup5): never inherit a reject-tier reserved-fx
      ;; override into a child dispatch. No-op (identity, no churn) when the
      ;; inherited `:fx-overrides` carries no reject-tier key — the dominant path.
      (some #(contains? (:fx-overrides parent-envelope) %) rejected-reserved-fx-ids)
      (update :fx-overrides #(apply dissoc % rejected-reserved-fx-ids)))
    {:frame frame-id}))

(def ^:private reserved-fx-handlers
  "Reserved fx-id → body-fn `(fn [frame-id parent-envelope args])`.
  Driven by `handle-one-fx`; emit of `:rf.fx/handled` lives in the
  caller so each reserved fx surfaces exactly one success trace,
  uniformly.

  `parent-envelope` is the dispatch envelope of the event that produced
  this fx vector. Per Spec 002 §The binary fx-handler signature (line
  603) and §Drain-loop pseudocode (lines 916, 961-963), the reserved-fx
  defmethods for `:dispatch` / `:dispatch-later` read the parent envelope
  to propagate inheritable keys (`:fx-overrides`, `:interceptor-overrides`,
  `:trace-id`, `:origin`, `:source`) onto the child dispatch — per
  Spec 002 §Cascade propagation."
  {:dispatch
   ;; Append to back of the frame's router queue. Per Spec 002
   ;; §Cascade propagation, the child envelope inherits the parent's
   ;; `:fx-overrides` / `:interceptor-overrides` / `:trace-id` /
   ;; `:origin`. Per rf2-ejtpd, `:source` is OVERRIDDEN to
   ;; `:fx-dispatch` — the child's immediate trigger is "the
   ;; `:dispatch` fx executed", not whatever woke the originating
   ;; user event.
   ;;
   ;; Per rf2-c3990: when the emitting handler IS a machine
   ;; (`:rf.machine/internal? true` on the parent envelope), the
   ;; child dispatch is an *actor message* — one machine emitting a
   ;; dispatch into the actor system. The substrate stamps
   ;; `:source :machine-action` for that path so the Epoch panel and
   ;; trace filters can distinguish machine-emitted continuations from
   ;; plain `:dispatch` fx cascades. The `:rf.machine/internal? true`
   ;; flag still rides on the envelope (via `child-dispatch-opts`) so
   ;; the router can front-of-queue insert per Spec 005 §Level 4.
   (fn [frame-id parent-envelope args]
     ;; Sticky hook (rf2-f72pd) — `:router/dispatch!` is published once
     ;; at re-frame.router load and never withdrawn; this fires per
     ;; `:dispatch` fx invocation.
     (when-let [f (late-bind/get-fn-cached :router/dispatch!)]
       (f args (assoc (child-dispatch-opts frame-id parent-envelope)
                      :source (if (:rf.machine/internal? parent-envelope)
                                :machine-action
                                :fx-dispatch)))))

   :dispatch-later
   ;; Delayed dispatch — wraps the same router hook in `set-timeout!`.
   ;; Inheritable keys are projected at fx-firing time and captured in
   ;; the closure so the deferred dispatch carries the parent envelope's
   ;; overrides into the eventual child cascade. Per rf2-ejtpd, the
   ;; deferred dispatch stamps `:source :fx-dispatch-later` so the Epoch
   ;; panel's DISPATCH step renders the precise trigger rather than the
   ;; originating user event's `:source`.
   ;;
   ;; Per rf2-5qp4g: stamp `:source-detail {:ms <ms>}` alongside the
   ;; `:source` so the Epoch panel's DISPATCH step can render the
   ;; ORIGINAL scheduled delay (e.g. `from fx :dispatch-later · 500ms`)
   ;; rather than just the kind label. The detail rides on the
   ;; envelope, then onto the `:rf.event/dispatched` trace via
   ;; `emit-dispatched-trace`'s opt-in stamp (router.cljc rf2-5qp4g).
   ;;
   ;; Per rf2-c3990: machine-emitted `:dispatch-later` is an *actor
   ;; message* scheduled with a delay — stamp `:source :machine-action`
   ;; (carrying the same `:source-detail {:ms <ms>}`) when the parent
   ;; envelope is machine-internal, matching the `:dispatch` fx
   ;; handler's machine-action discriminator above.
   (fn [frame-id parent-envelope {:keys [ms event]}]
     (let [machine? (:rf.machine/internal? parent-envelope)
           opts (assoc (child-dispatch-opts frame-id parent-envelope)
                       :source        (if machine? :machine-action :fx-dispatch-later)
                       :source-detail {:ms ms})]
       (interop/set-timeout!
         (fn []
           ;; Sticky hook (rf2-f72pd) — same as above; the timer
           ;; callback fires per scheduled :dispatch-later.
           (when-let [dispatch! (late-bind/get-fn-cached :router/dispatch!)]
             (dispatch! event opts)))
         ms)))

   ;; Per Spec 013 — flows are frame-scoped. The flow registers against
   ;; the dispatching frame.
   ;;
   ;; Both fx-ids route through the SAME hooks the public API uses
   ;; (`:flows/reg-flow` / `:flows/clear-flow`) — the API-shape hooks
   ;; accept `(arg opts)` with opts carrying `:frame`, and
   ;; `call-frame-scoped-hook!` passes `{:frame frame-id}` as the second
   ;; arg, so one hook pair serves both surfaces.
   ;;
   ;; ONE-EVENT LAG — THE LEAST-OBVIOUS FLOW BEHAVIOUR (Spec 013
   ;; §Sequencing). The `:fx` walk is the LAST drain stage — it runs
   ;; AFTER the flow-transform `:after` has already evaluated this event's
   ;; flows (Spec 013 §Drain integration: step 4 runs after step 2). So a
   ;; flow registered HERE was not in the registry when the flow transform
   ;; walked, and does NOT compute its initial output on THIS event — it
   ;; first fires on the NEXT drain on the same frame. This lag is
   ;; structural (a synchronous re-walk would need a second `app-db`
   ;; install, breaking the one-install-per-event invariant), NOT a bug.
   ;; Callers needing the initial value immediately dispatch a follow-up
   ;; no-op event from the SAME handler (see Spec 013 §Sequencing). Do not
   ;; "fix" this by re-running the flow transform after `:fx`.
   :rf.fx/reg-flow
   (fn [frame-id _parent-envelope args]
     (call-frame-scoped-hook! :flows/reg-flow frame-id args))

   :rf.fx/clear-flow
   (fn [frame-id _parent-envelope args]
     (call-frame-scoped-hook! :flows/clear-flow frame-id args))})

(defn- emit-fx-error!
  "Fan a runtime `:rf.error/*` fx-category out through BOTH error
  substrates (rf2-goum9x): the always-on `error-emit` listener registry
  (axis 1 — production-survivable; the source of truth for off-box
  shippers and the SSR error projector, which fail-closes
  `:rf.error/fx-handler-exception` to 500 off this stream — Spec 011
  §SSR error projection) AND the dev-only trace surface (DCE'd under
  CLJS `:advanced` + `goog.DEBUG=false`).

  Before this, the user-registered-fx exception
  (`:rf.error/fx-handler-exception`), the unknown-fx-id
  (`:rf.error/no-such-fx`), and the override-fallthrough
  (`:rf.error/override-fallthrough`) categories went ONLY to the dev
  trace, so they vanished in production observability under the same
  gate that strips dev traces — even though Spec 009 §Error event
  catalogue lists them as production-reachable runtime errors. The
  reserved-fx typed-throw path already fanned out through this
  substrate; this brings the three drift sites into line.

  `trace-payload` is the existing dev-trace map for the category
  (unchanged shape, so dev consumers see exactly what they did before).

  Reaches the shared two-channel `emit-error-both!` (rf2-c4oycd) through the
  `:error-emit/emit-error-both` late-bind hook — fx.cljc cannot static-require
  `re-frame.error-emit` (a load cycle), exactly as it reached `dispatch-on-error!`
  through its hook before. `elapsed-ms 0` (not a timed path); `(interop/now-ms)`
  is the emit instant."
  [category event event-id frame-id exception trace-payload]
  ;; Both channels via the shared helper: axis 1 the always-on corpus-wide
  ;; listener (survives prod elision), axis 2 the dev trace (DCE'd in CLJS prod).
  ;; `nil` when the substrate ns hasn't loaded (it is a foundational always-on
  ;; surface, so in practice it is present).
  (when-let [emit-error-both! (late-bind/get-fn-cached :error-emit/emit-error-both)]
    (emit-error-both! category event event-id frame-id exception 0
                      (interop/now-ms) trace-payload)))

(defn- emit-reserved-fx-override!
  "Emit `:rf.error/reserved-fx-override` (rf2-snsup5) when an `:fx-overrides`
  entry targets a reject-tier reserved fx-id (`rejected-reserved-fx-ids`).
  Fans out through BOTH error substrates via `emit-fx-error!` — the override
  is a production-reachable misconfiguration (Spec 009 §Error event
  catalogue). The recovery is `:reserved-body-ran`: the override is ignored
  and the real reserved/registered body runs. `where` discriminates the
  dev per-call site (`:handle-one-fx`) from the production prod-strip
  (`:production-strip`)."
  [fx-id override-target frame-id origin-event origin-event-id where]
  (emit-fx-error! :rf.error/reserved-fx-override
                  origin-event origin-event-id frame-id nil
                  {:failing-id fx-id
                   :rf.fx/id   fx-id
                   :frame      frame-id
                   :override   override-target
                   :where      where
                   :reason     (str "`:fx-overrides` targeted the reserved fx-id `"
                                    fx-id "`, which installs/clears durable frame "
                                    "runtime state (or threads a correctness-critical "
                                    "nav-token) and may NOT be overridden (rf2-snsup5). "
                                    "The override was IGNORED; the reserved body runs. "
                                    "Only the routing/host-API reserved fxs (`:dispatch`, "
                                    "`:dispatch-later`, `:rf.machine/dispatch-to-system`, "
                                    "`:rf.nav/*`) are overridable.")
                   :recovery   :reserved-body-ran}))

(defn strip-rejected-overrides
  "Production prod-strip (rf2-snsup5): remove every reject-tier reserved
  fx-id (`rejected-reserved-fx-ids`) from an effective `:fx-overrides` map,
  emitting one LOUD `:rf.error/reserved-fx-override` per stripped key. Called
  by the router on the merged per-frame ⋈ per-call override map BEFORE
  `do-fx` so a production frame can never stub a state-installing lifecycle
  fx — the dev path (`handle-one-fx`) emits the error and runs the reserved
  body per-call; this is the production analogue that drops the keys up
  front (the dev-only `handle-one-fx` per-call emit DCEs under :advanced).

  Returns the same map untouched (identity, no churn) when it carries no
  reject-tier key — the dominant path. `frame-id` / `origin-event` carry
  cascade context for the emit; both read-only here. Public so the router
  can call it; not part of the user surface."
  [overrides frame-id origin-event]
  (if (and (map? overrides)
           (some #(contains? overrides %) rejected-reserved-fx-ids))
    (let [origin-event-id (when (vector? origin-event) (first origin-event))]
      (reduce-kv
        (fn [acc fx-id override-target]
          (if (contains? rejected-reserved-fx-ids fx-id)
            (do
              (emit-reserved-fx-override! fx-id override-target frame-id
                                          origin-event origin-event-id
                                          :production-strip)
              acc)
            (assoc acc fx-id override-target)))
        {}
        overrides))
    overrides))

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
  invocation goes through the synthesised meta returned by
  `resolved-fx-meta`, and `handle-one-fx` emits the
  `:rf.fx/override-applied` trace at the point the override fn actually
  fires so the trace cannot claim an override applied while the original
  ran; see rf2-nrpj1).

  `frame-id` / `origin-event` / `origin-event-id` (rf2-goum9x) carry the
  cascade context so the `:rf.error/override-fallthrough` emit can be
  frame-attributed and fanned out through the always-on error-emit
  substrate (not just the dev trace) — they are otherwise read-only here."
  [original-fx-id overrides frame-id origin-event origin-event-id]
  (if (contains? overrides original-fx-id)
    (let [override-target (get overrides original-fx-id)]
      (cond
        ;; (3) function value — CLJS-reference convenience. The
        ;; `:rf.fx/override-applied` trace is emitted by `handle-one-fx`
        ;; when the override fn actually fires (not here), because a
        ;; fn-value override of a *reserved* fx-id must pre-empt the
        ;; reserved body — emitting the trace here would fire it even on
        ;; paths that (counterfactually) declined the override. Returns
        ;; the original-fx-id unchanged; `resolved-fx-meta` synthesises
        ;; the meta carrying the user's fn.
        (fn? override-target)
        original-fx-id

        ;; (2) id-redirect to a registered fx.
        (keyword? override-target)
        (if (registrar/lookup :fx override-target)
          (do
            (trace/emit! :rf.fx :rf.fx/override-applied
                         {:rf.fx/from original-fx-id :rf.fx/to override-target})
            override-target)
          (do
            ;; rf2-goum9x: override misconfiguration is a production-
            ;; reachable runtime error (Spec 009 §Error event catalogue) —
            ;; fan it out through the always-on listener so override-map
            ;; mistakes are visible in production, not only under dev
            ;; traces. The recovery is the framework's own
            ;; `:replaced-with-default` (use the registered fx).
            (emit-fx-error! :rf.error/override-fallthrough
                            origin-event
                            origin-event-id
                            frame-id
                            nil
                            {:failing-id     original-fx-id
                             :overrides-map  overrides
                             :looked-up-id   override-target
                             :frame          frame-id
                             :reason         (str "Override redirected `"
                                                  original-fx-id
                                                  "` to `"
                                                  override-target
                                                  "`, which is not registered. Using the registered `"
                                                  original-fx-id
                                                  "` instead.")
                             :recovery       :replaced-with-default})
            original-fx-id))

        ;; `nil` / `false` — the documented noop-style placeholder
        ;; (spec/002 §`:fx-overrides`): "no override", silently fall
        ;; through to the original fx. KEPT silent on purpose.
        (or (nil? override-target) (false? override-target))
        original-fx-id

        :else
        ;; Any OTHER value (a number / string / map / vector / …) is a
        ;; malformed `:fx-overrides` entry — an `:fx-overrides` value must
        ;; be an fx-id keyword (id-redirect), a fn (override body), or
        ;; nil/false (noop). Fail LOUD, matching the sibling
        ;; unregistered-keyword branch (rf2-3az1vn P2 + spec/002 §`:fx-overrides`
        ;; :else throw): emit `:rf.error/override-fallthrough` through BOTH
        ;; error substrates so a silently-swallowed bad override surfaces in
        ;; dev AND production observability, then fall through to the original
        ;; fx (recovery `:replaced-with-default`).
        (do
          (emit-fx-error! :rf.error/override-fallthrough
                          origin-event
                          origin-event-id
                          frame-id
                          nil
                          {:failing-id    original-fx-id
                           :overrides-map overrides
                           :override      override-target
                           :frame         frame-id
                           :reason        (str "Override for `"
                                               original-fx-id
                                               "` is `"
                                               (pr-str override-target)
                                               "`, which is not a valid `:fx-overrides` "
                                               "value — it must be an fx-id keyword "
                                               "(id-redirect), a function (override body), "
                                               "or nil/false (noop). Using the registered `"
                                               original-fx-id
                                               "` instead.")
                           :recovery      :replaced-with-default})
          original-fx-id)))
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

  When called inside the fx-handler's `*handler-scope*` binding (the
  user-registered fx branch), `emit!` hoists the fx handler's
  registration coord onto the emitted event's `:rf.trace/trigger-
  handler` slot — so consumers can jump to the fx's `reg-fx` site
  from the success trace. Reserved fx-id calls (`:dispatch`,
  `:dispatch-later`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`) emit
  outside any fx-handler binding; the outer event handler's scope
  (if any) stamps the event handler's coord instead, which is the
  right attribution for those — they don't have their own
  registration site."
  ([fx-id args frame-id]
   (emit-handled! fx-id args frame-id nil))
  ([fx-id args frame-id elapsed-ms]
   ;; rf2-hhh92: `elapsed-ms` (the wall-clock duration of the fx-handler
   ;; invoke, dev-only) rides onto `:rf.fx/handled` as `:rf.fx/elapsed-ms`
   ;; so the Trace panel's DURATION column reads the per-op duration. The
   ;; slot construction rides `(some? elapsed-ms)` — callers pass nil in
   ;; production (the brackets ride `interop/debug-enabled?`), so the
   ;; assoc collapses and the prod emit shape is unchanged.
   (trace/emit! :rf.fx :rf.fx/handled
                (cond-> {:rf.fx/id   fx-id
                         :rf.fx/args args
                         :frame      frame-id}
                  (some? elapsed-ms) (assoc :rf.fx/elapsed-ms elapsed-ms)))))

(defn handle-one-fx
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
  to the originator without a separate cofx-injection step.

  `parent-envelope` (when supplied) is the dispatch envelope of the
  originating event. Per Spec 002 §The binary fx-handler signature
  (line 603) and §Drain-loop pseudocode (lines 916, 961-963) it is
  exposed on the fx-handler ctx at `(:envelope m)` — reserved-fx
  defmethods (`:dispatch`, `:dispatch-later`) read it to propagate
  inheritable envelope keys onto child dispatches per
  §Cascade propagation. User fxs typically only read `(:frame m)`.

  Public so that fx wrappers (per Spec 012 §Navigation tokens
  `:rf.route/with-nav-token`, and any future single-fx re-entry helper)
  can route a single inner fx entry through the same machinery as the
  outer walk — without re-emitting the `:event/do-fx` boundary marker
  that `do-fx` terminates each walk with. `do-fx` remains the entry
  point for the whole `:fx` vector."
  ([frame-id pair active-platform overrides origin-event]
   (handle-one-fx frame-id pair active-platform overrides origin-event nil))
  ([frame-id [original-fx-id args] active-platform overrides origin-event parent-envelope]
  (let [origin-event-id (when (vector? origin-event) (first origin-event))
        ;; rf2-snsup5: HARD-REJECT an `:fx-overrides` entry that targets a
        ;; reject-tier reserved fx-id (`rejected-reserved-fx-ids` — the
        ;; state-installing lifecycle fxs + the nav-token threader). Whether
        ;; the override is fn-value or keyword-redirect, it is IGNORED: we
        ;; emit `:rf.error/reserved-fx-override` and neutralise the override
        ;; for this id so the real reserved/registered body runs (the
        ;; reserved table for `:rf.fx/reg-flow` / `:rf.fx/clear-flow`, the
        ;; registrar for `:rf.machine/spawn` / `:rf.machine/destroy` /
        ;; `:rf.route/with-nav-token`). This is the DEV-path counterpart of
        ;; the router's production prod-strip (`strip-rejected-overrides`):
        ;; the per-call emit + neutralise rides `emit-fx-error!`'s always-on
        ;; fan-out, but the trace half DCEs under :advanced.
        ;;
        ;; OVERRIDABLE reserved fxs (`:dispatch`, `:dispatch-later`,
        ;; `:rf.machine/dispatch-to-system`, `:rf.nav/*`) are NOT in the
        ;; reject set, so they flow through unchanged — the rf2-nrpj1
        ;; fn-value-pre-empts-reserved-body contract stays intact.
        reject-override? (and (contains? overrides original-fx-id)
                              (contains? rejected-reserved-fx-ids original-fx-id))
        _ (when reject-override?
            (emit-reserved-fx-override! original-fx-id
                                        (get overrides original-fx-id)
                                        frame-id origin-event origin-event-id
                                        :handle-one-fx))
        ;; Neutralise the rejected override before resolution so the reserved
        ;; body runs as if no override were present. The OVERRIDABLE-tier
        ;; entries are untouched.
        overrides (if reject-override? (dissoc overrides original-fx-id) overrides)
        fx-id (resolve-fx-with-overrides original-fx-id overrides
                                         frame-id origin-event origin-event-id)
        resolved-meta (resolved-fx-meta original-fx-id fx-id overrides)
        ;; rf2-nrpj1: a function-value override (`{:dispatch (fn [m args] ...)}`)
        ;; must run IN PLACE OF the registered/reserved fx — per spec/002
        ;; §`:fx-overrides` the resolution model consults `:fx-overrides`
        ;; FIRST and `(fn? override) → override` runs unconditionally. For
        ;; the OVERRIDABLE reserved fx-ids (`:dispatch`, `:dispatch-later`)
        ;; `fx-id` is still the reserved keyword (the fn-value branch of
        ;; `resolve-fx-with-overrides` returns it unchanged), so the
        ;; `reserved-fx-handlers` lookup below would otherwise fire the
        ;; reserved body and silently ignore the override. We detect the
        ;; fn-value override here and route it down the user-fx branch
        ;; (which invokes `resolved-meta`'s synthesised `:handler-fn`),
        ;; pre-empting the reserved body for reserved ids and matching the
        ;; spec resolution order for user ids. (Reject-tier ids were already
        ;; neutralised above, so they never reach this branch.)
        fn-value-override? (and (contains? overrides original-fx-id)
                                (fn? (get overrides original-fx-id)))]
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
    (if-let [reserved-body (and (not fn-value-override?)
                                (get reserved-fx-handlers fx-id))]
      ;; Reserved fx-id — dispatch through the table; one uniform
      ;; `:rf.fx/handled` emit follows. The `:rf.machine/spawn` and
      ;; `:rf.machine/destroy` machine fx-ids are NOT in this table —
      ;; they are registered by re-frame.machines (day8/re-frame2-machines)
      ;; via the regular reg-fx path and arrive here through the
      ;; registrar default below. The reserved-fx body signature is
      ;; `(fn [frame-id parent-envelope args])` — `:dispatch` /
      ;; `:dispatch-later` read parent-envelope to propagate
      ;; inheritable envelope keys per Spec 002 §Cascade propagation.
      ;;
      ;; Generic typed-throw routing (rf2-eb4lp + rf2-on7sj-class
      ;; pattern): if a reserved-fx body throws with the canonical
      ;; `:rf.error/id` discriminator slot (per Spec 009 §The
      ;; thrown-error shape) carrying a keyword category, route it
      ;; through the always-on `error-emit` substrate so prod monitors
      ;; get the typed signal; ex-data is preserved verbatim including
      ;; any reserved-fx-specific slots (e.g. `:cycle`). Reached via
      ;; the late-bind hook `:error-emit/dispatch-on-error` (fx.cljc
      ;; cannot statically require error-emit — would form a load
      ;; cycle). Untyped throws re-throw to preserve the crash-loud
      ;; contract. This generalisation keeps reserved-fx-specific
      ;; error keywords (e.g. flow-cycle) out of core/fx.cljc so they
      ;; DCE from consumer bundles that don't use the offending fx.
      (try
        ;; rf2-hhh92: wall-clock the reserved-fx body (dev-only) so
        ;; `:rf.fx/handled` carries `:rf.fx/elapsed-ms`. The `now-ms`
        ;; brackets ride `interop/debug-enabled?` (nil in prod → DCE).
        (let [t0 (when interop/debug-enabled? (interop/now-ms))]
          (reserved-body frame-id parent-envelope args)
          (emit-handled! fx-id args frame-id
                         (when interop/debug-enabled? (- (interop/now-ms) t0))))
        (catch #?(:clj Throwable :cljs :default) e
          (let [ex-data-map (ex-data e)
                category    (:rf.error/id ex-data-map)]
            (if (keyword? category)
              ;; rf2-vzrxp3: nil-safe (a thrown non-Error value has no message).
              (let [msg (error/ex-message-safe e)]
                ;; Both channels via the shared `emit-fx-error!` helper
                ;; (rf2-xxlzfl): axis 1 the always-on per-error observability
                ;; fan-out (rf2-bacs4 / sticky hook rf2-f72pd; survives
                ;; `:advanced` + `goog.DEBUG=false`), axis 2 the dev trace
                ;; (DCE'd in CLJS prod). `emit-fx-error!` reaches the shared
                ;; two-channel `emit-error-both!` through the
                ;; `:error-emit/emit-error-both` late-bind hook (fx cannot
                ;; static-require error-emit — load cycle), stamping
                ;; `elapsed-ms 0` + `(interop/now-ms)` internally — the same
                ;; two-step the three other fx error sites
                ;; (`:rf.error/no-such-fx`, `:rf.error/fx-handler-exception`,
                ;; `:rf.error/override-fallthrough`) route through. The typed
                ;; category + reserved-fx-specific ex-data slots ride verbatim
                ;; (e.g. flow-cycle's `:cycle`) via the merged trace-payload.
                (emit-fx-error! category origin-event origin-event-id frame-id e
                                (merge {:failing-id        fx-id
                                        :rf.fx/id          fx-id
                                        :rf.fx/args        args
                                        :frame             frame-id
                                        :exception         e
                                        :exception-message msg
                                        :recovery          :no-recovery}
                                       (dissoc ex-data-map :rf.error/id))))
              ;; Untyped reserved-fx throw — preserve crash-loud
              ;; contract by re-throwing.
              (throw e)))))
      ;; Default: user-registered fx — OR a synthesised meta carrying a
      ;; function-value override (per `resolved-fx-meta` above; the
      ;; spec/002 CLJS-reference convenience form). `resolved-meta` was
      ;; computed once at top of `handle-one-fx` so the fallthrough
      ;; honours both registry hits and the fn-value override branch
      ;; without a second lookup.
      (if-let [meta resolved-meta]
      (if (runs-on-platform? meta active-platform)
        ;; Per Spec 010 §Validation order step 5 (rf2-xp2o3): before the
        ;; fx handler runs, validate its args against any `:schema` on
        ;; the fx's registration meta. The schemas artefact is optional
        ;; — when absent or when no `:schema` is registered, the
        ;; late-bind hook resolves nil or the guard short-circuits and
        ;; the call is a no-op (true / pass).
        ;; On failure (returns false) the offending fx is skipped (per
        ;; Spec 010 §Per-step recovery row 5: `:recovery :skipped`) and
        ;; the walk continues with the next entry in the `:fx` vector —
        ;; sibling fx are not impacted, the cascade does not halt.
        ;; `validate-fx!` itself emits the `:rf.error/schema-validation-
        ;; failure :where :fx-args` trace; this caller only honours the
        ;; boolean.
        ;; Sticky hook (rf2-f72pd) — fires per-fx invocation.
        (let [validate-fx! (late-bind/get-fn-cached :schemas/validate-fx!)
              fx-ok?       (if (and validate-fx!
                                    (:schema meta))
                             (try
                               ;; rf2-9cm27 — pass the in-flight cascade's
                               ;; frame so the `:where :fx-args` failure trace
                               ;; carries `:frame` and lands in the per-frame
                               ;; epoch `:trace-events` (epoch capture buffers
                               ;; only frame-tagged traces). Mirrors the
                               ;; `:where :app-db` / `:where :event` traces.
                               (validate-fx! fx-id origin-event-id args meta frame-id)
                               (catch #?(:clj Throwable :cljs :default) _ true))
                             true)]
        (if-not fx-ok?
          ;; Schema validation failed — the offending fx is skipped.
          ;; `validate-fx!` already emitted the structured error trace;
          ;; do NOT emit `:rf.fx/handled` (the fx did not run) and do
          ;; NOT emit a sibling warning (the schema-validation-failure
          ;; trace IS the warning, per Spec 010).
          nil
          ;; Publish the fx handler's HandlerScope — `:trigger-handler`
          ;; for the fx handler's invocation AND the success-path
          ;; `:rf.fx/handled` emit; `:no-emit?` per Spec 009
          ;; "innermost handler wins". (`:sensitive?` is path-marked
          ;; via schema-slot meta; the handler-meta annotation has
          ;; been removed.) Errors emitted from
          ;; inside the fx body carry the fx handler's source-coord;
          ;; the success-path `:rf.fx/handled` emit picks up the same
          ;; coord through `emit!`'s hoist of `*handler-scope*` — the
          ;; outer event handler's scope would otherwise stamp the
          ;; event handler's coord onto the
          ;; `:rf.fx/handled` event (Story/Xray want jump-to-source to
          ;; land on the fx handler's `reg-fx` site, not the event
          ;; handler that produced the fx vector). `:call-site` /
          ;; `:dispatch-id` are inherited from the outer scope.
          (trace/with-handler-scope
            (trace/handler-scope-from-meta :fx fx-id meta)
            ;; rf2-nrpj1: emit `:rf.fx/override-applied` HERE — at the
            ;; point the fn-value override actually fires — not during
            ;; resolution. This is the trace-honesty half of the fix: the
            ;; trace previously fired at resolution time even for reserved
            ;; fx-ids where the override was then silently ignored and the
            ;; reserved body ran instead (claiming an override applied
            ;; while the original ran). It now fires iff the override fn is
            ;; about to be invoked. `:rf.fx/to ::fn-value` marks the
            ;; CLJS-reference fn-value form (the keyword form's trace is
            ;; emitted by `resolve-fx-with-overrides` with its target id).
            (when fn-value-override?
              (trace/emit! :rf.fx :rf.fx/override-applied
                           {:rf.fx/from original-fx-id :rf.fx/to ::fn-value}))
            ;; rf2-hhh92: wall-clock the user fx-handler invoke (dev-only)
            ;; so `:rf.fx/handled` carries `:rf.fx/elapsed-ms`. The
            ;; `now-ms` brackets ride `interop/debug-enabled?` (nil in
            ;; prod → DCE under :advanced).
            (let [t0  (when interop/debug-enabled? (interop/now-ms))
                  ok? (try
                        ;; Per Spec 002 §The binary fx-handler signature
                        ;; (line 603): the fx-handler ctx carries `:frame`
                        ;; (active frame id), `:event` (origin event
                        ;; vector — Spec 014 §Reply addressing), and
                        ;; `:envelope` (parent dispatch envelope — read
                        ;; by reserved fxs only; surfaced here too so
                        ;; user fxs can observe `:trace-id` / `:origin`
                        ;; / `:source` without a separate cofx hop).
                        ((:handler-fn meta) (cond-> {:frame frame-id}
                                              origin-event   (assoc :event origin-event)
                                              parent-envelope (assoc :envelope parent-envelope))
                                            args)
                        true
                        (catch #?(:clj Throwable :cljs :default) e
                          ;; rf2-vzrxp3: nil-safe (a thrown non-Error value has no message).
                          (let [msg (error/ex-message-safe e)]
                            ;; rf2-goum9x: a thrown registered fx is
                            ;; production-survivable (Spec 009/011) — fan it
                            ;; out through the always-on error-emit listener
                            ;; so SSR fails-closed to 500 and off-box shippers
                            ;; see it, NOT just the dev trace. The fx is
                            ;; skipped, siblings still fire, app-db is NOT
                            ;; rolled back (preserves fx-walk semantics).
                            ;; Mirrors the reserved-fx typed-throw path above.
                            (emit-fx-error!
                              :rf.error/fx-handler-exception
                              origin-event
                              origin-event-id
                              frame-id
                              e
                              {:failing-id        fx-id
                               :rf.fx/id          fx-id
                               :rf.fx/args        args
                               :frame             frame-id
                               :exception         e
                               :exception-message msg
                               :reason            (str "Effect handler `" fx-id "` threw: " msg ".")
                               :recovery          :no-recovery}))
                          false))]
              (when ok?
                (emit-handled! fx-id args frame-id
                               (when interop/debug-enabled? (- (interop/now-ms) t0))))))))
        (trace/emit! :warning :rf.fx/skipped-on-platform
                     {:rf.fx/id                   fx-id
                      :frame                      frame-id
                      :rf.fx/args                 args
                      :rf.fx/platform             active-platform
                      :rf.fx/registered-platforms (:platforms meta)
                      :recovery                   :skipped}))
      ;; rf2-goum9x: an unknown fx-id is a production-reachable runtime
      ;; error (Spec 009 §Error event catalogue) — fan it out through the
      ;; always-on listener so load-order / optional-artefact mistakes are
      ;; visible in production, not only under dev traces. The fx is
      ;; dropped; the cascade continues with the remaining `:fx` entries.
      (emit-fx-error! :rf.error/no-such-fx
                      origin-event
                      origin-event-id
                      frame-id
                      nil
                      {:rf.fx/id   fx-id
                       :rf.fx/args args
                       :frame      frame-id
                       :recovery   :no-recovery})))))))

(def framework-coeffect-keys
  "Coeffect keys populated by the runtime itself (not by user-registered
  `reg-cofx` suppliers declared via `:rf.cofx/requires`). Filtered OUT of
  the `:rf.event/coeffects` stamp on `:rf.event/run-end` (rf2-9dk9y) so the
  Xray Event lens's COEFFECTS section shows only handler-declared coeffects
  (mirrors the AFTER INTERCEPTORS section's filter-out-framework-defaults
  posture).

  `:db` + `:event` are populated by `assemble-initial-ctx`.
  `:source` + `:trace-id` are envelope keys also surfaced on the cofx
  map by `assemble-initial-ctx` for handler-body convenience.
  `:rf.db/runtime` (the runtime-db partition) + `:rf.frame/id` (the
  runtime-context frame id, the event-context spelling of the frame
  stamp per Spec 002 §Event context — the retired bare `:frame`
  coeffect is gone, rf2-1m6rf1) are the EP-0001 partition coeffects,
  likewise injected by `assemble-initial-ctx` — framework defaults, not
  user cofx (rf2-bvwoi4).
  `:rf.cofx` (the EP-0017 flat recordable-coeffect map, rf2-s9ss0t /
  rf2-alc1lf) is a framework coeffect stamped at envelope construction, so it
  is filtered out here exactly like the other framework defaults — the
  COEFFECTS section shows only genuinely handler-declared coeffects (Spec
  002 §Event Context And Coeffects).
  `:rf.cofx/mint-policy` (rf2-n0myjq) is the resolved effective mint policy
  stamped by `assemble-initial-ctx` so the machine ensure path can read it; a
  framework default, not user cofx, so it is filtered out here too."
  #{:db :event :source :trace-id :rf.db/runtime :rf.frame/id :rf.cofx
    :rf.cofx/mint-policy})

(defn user-injected-coeffects
  "Project the user-injected subset of a coeffects map. Pure data → data.

  Filters out the framework-default keys per `framework-coeffect-keys`.
  Returns nil when the projection is empty (so the caller can use
  `(when ...)` to skip the trace stamp entirely — consumers treat ABSENT
  `:rf.event/coeffects` as 'no user-injected coeffects', distinct from
  an empty map which would itself be a 'stamped but empty' signal).

  Called from `re-frame.router/emit-cascade-trailers!` to stamp the
  per-event user-cofx subset onto `:rf.event/run-end` (rf2-9dk9y — the
  stamp moved here from `:rf.fx/do-fx` so events that return only `:db`
  — no `:fx` — still get a COEFFECTS row in the Xray Event lens)."
  [coeffects]
  (when (map? coeffects)
    (let [projected (reduce-kv (fn [acc k v]
                                 (if (contains? framework-coeffect-keys k)
                                   acc
                                   (assoc acc k v)))
                               {}
                               coeffects)]
      (when (seq projected)
        projected))))

;; ---- per-entry :fx-shape policing (rf2-n6d3m) -----------------------------
;;
;; `events.cljc/fx-value-ok?` (rf2-24zly) polices the whole `:fx` VALUE at
;; commit time: a non-`nil`, non-sequential value (`{:fx :oops}`) is dropped
;; with :rf.error/effect-map-shape before it ever reaches `do-fx`. This is the
;; level DOWN: an individual ENTRY inside an otherwise-well-shaped `:fx` vector.
;;
;; Per `:rf/effect-map` (Spec-Schemas §:rf/effect-map) each entry is a
;; `[:tuple :keyword :any]` — a `[fx-id args]` vector. The do-fx walk used to
;; guard with `(when (and (vector? pair) (seq pair)) …)`, which (a) silently
;; dropped EVERY non-vector entry with no diagnostic — including the clear
;; typo `{:fx [[:good a] :oops]}` (a bare keyword where a `[fx-id args]` pair
;; was meant) — and (b) waved ANY non-empty vector through, so a non-keyword
;; head (`["not-a-keyword" {}]`) reached `handle-one-fx` and was mis-reported
;; as `:rf.error/no-such-fx` (wrong diagnostic), and a surplus tuple field
;; (`[:some/fx {:used true} {:dropped true}]`) was SILENTLY truncated because
;; `handle-one-fx` destructures only `[original-fx-id args]`. Both are shape
;; violations; the framework polices them loudly elsewhere (interceptors-in-
;; metadata, M-8 effect keys, the `:fx` value shape) so it must here too.
;;
;; We tolerate the legitimately-empty idiom and police every clear typo:
;;
;;   nil / [] (empty)        → silent no-op. The documented conditional-fx
;;                             idiom `[(when cond? [:fx-id args])]` nil-pads
;;                             entries on purpose; a `[]` is likewise a
;;                             deliberate no-op. NO trace.
;;   [:fx-id] / [:fx-id args]→ walked normally. A 1- or 2-element vector whose
;;                             head is a keyword is the documented pair (the
;;                             1-arity no-args shorthand `[:fx-id]` is in wide
;;                             use, e.g. `[[:fx-test/a] [:fx-test/b]]`; the
;;                             2-arity carries args). An unregistered fx-id head
;;                             is still policed downstream by `handle-one-fx`'s
;;                             :rf.error/no-such-fx path — not our concern here.
;;   vector, NON-keyword head→ shape violation. `["not-a-keyword" {}]` is NOT an
;;                             unknown fx-id, it is a bad fx-id type. Emit
;;                             :rf.error/effect-map-shape, drop, siblings run.
;;   vector, arity ≥ 3       → shape violation. The surplus slot would be
;;                             silently discarded by `handle-one-fx`. Emit
;;                             :rf.error/effect-map-shape, drop, siblings run.
;;   non-`nil`, non-empty,   → the clear typo (a keyword / map / string / number
;;   NON-vector entry          / list where a `[fx-id args]` vector was meant).
;;                             Emit :rf.error/effect-map-shape naming the entry +
;;                             handler (recovery :logged-and-skipped, symmetric
;;                             with rf2-24zly) and DROP just that entry — sibling
;;                             entries still run, the cascade is not aborted.

(defn- fx-entry-ok?
  "True iff an individual `:fx` entry `pair` is shaped well enough to walk —
  i.e. `nil`, an empty collection (both the legal conditional-fx no-op), or a
  1- or 2-element vector whose head is a keyword (the documented `[fx-id]` /
  `[fx-id args]` pair — the 1-arity no-args shorthand is supported because
  `handle-one-fx` destructures `args` as `nil` for it). Anything else is a
  shape violation: a non-`nil`, non-empty, NON-vector entry (forgot-the-inner-
  vector typo), a vector with a NON-keyword head (bad fx-id type — would be
  mis-reported as an unknown fx-id), or a vector of arity ≥ 3 (the surplus
  slot would be silently truncated by `handle-one-fx`). On a violation, emit
  :rf.error/effect-map-shape naming the offending entry + handler and return
  false so the walk drops just that entry — siblings still run. `event` (the
  originating event vector, when supplied) names the offending handler; absent
  ⇒ the trace degrades gracefully (nil event-id)."
  [pair frame-id event]
  (cond
    ;; Legal no-op: nil or empty — the conditional-fx idiom. Skip, no trace.
    (nil? pair)               false
    (and (vector? pair)
         (#{1 2} (count pair))
         (keyword? (first pair))) true     ;; [fx-id] / [fx-id args] — walk it
    (and (coll? pair)
         (empty? pair))       false        ;; empty [] / () — legal no-op, no trace
    :else
    ;; A shape violation. Three sub-cases, distinguished only for the human-
    ;; facing :reason; all drop the entry + emit one :rf.error/effect-map-shape.
    (let [event-id (when (vector? event) (first event))
          reason   (cond
                     (not (vector? pair))
                     (str "Effect-map for `" event-id "` returned an `:fx` entry of type `"
                          (pr-str (type pair))
                          "` (value `" (pr-str pair) "`); each `:fx` entry must be a "
                          "`[fx-id args]` vector (e.g. `[:dispatch [:saved]]`)"
                          " — did you forget the inner vector?")

                     (not (keyword? (first pair)))
                     (str "Effect-map for `" event-id "` returned an `:fx` entry `"
                          (pr-str pair) "` whose fx-id (the first element) is `"
                          (pr-str (first pair)) "` of type `" (pr-str (type (first pair)))
                          "`; each `:fx` entry must be a `[fx-id args]` vector whose "
                          "fx-id is a keyword (e.g. `[:dispatch [:saved]]`).")

                     :else ;; arity ≥ 3 (the empty-vector arity-0 case is handled above)
                     (str "Effect-map for `" event-id "` returned an `:fx` entry `"
                          (pr-str pair) "` with " (count pair) " elements; each `:fx` "
                          "entry must be a `[fx-id args]` vector of at most two elements "
                          "(e.g. `[:dispatch [:saved]]` or the no-args `[:fx-id]`)"
                          " — the surplus element(s) would be silently discarded."))]
      (trace/emit-error! :rf.error/effect-map-shape
                         {:failing-id        event-id
                          :rf.trace/event-id event-id
                          :rf.event/v        event
                          :frame             frame-id
                          :offending-key     :fx
                          :value             pair
                          :reason            reason
                          :recovery          :logged-and-skipped})
      false)))

(defn do-fx
  "Walk the :fx vector in source order. Per Spec 002 §`:fx` ordering rule 3:
  each entry's handler returns synchronously before the next begins.
  Errors trace independently and the walk continues (rule 4: one bad
  fx does not halt the rest).

  Per-entry shape policing (rf2-n6d3m): each entry passes through
  `fx-entry-ok?` before dispatch. A `nil` / empty entry is the legal
  conditional-fx no-op (skipped, no trace); a non-`nil`, non-empty
  NON-vector entry (the forgot-the-inner-vector typo) emits
  :rf.error/effect-map-shape and is dropped while sibling entries still
  run. This composes one level down from rf2-24zly's whole-`:fx`-value
  check (events.cljc/`fx-value-ok?`), which already rejected a
  non-sequential `:fx` value before it reached this walk.

  Per Spec 002 §Per-frame and per-call overrides: an fx-id override map
  may be provided via `opts`. Each [fx-id args] is rewritten through that
  map before lookup.

  Optional `opts` map (rf2-ee38b.1 — collapsed the former six-step nil-
  padding arity ladder, which threaded these positionally one accreted
  arg at a time, into a single keyword-keyed map):

    :overrides        an fx-id override map (Spec 002 §Per-frame and
                      per-call overrides). Each [fx-id args] is rewritten
                      through it before lookup. Defaults to `{}`.
    :origin-event     the originating event vector, passed through to
                      user-registered fx handlers as `:event` on their
                      ctx — needed by Spec 014 §Reply addressing (the fx
                      captures the originating event-id from the dispatch
                      envelope's cofx).
    :parent-envelope  the originating dispatch envelope, threaded to
                      reserved-fx defmethods (and exposed at `(:envelope
                      m)` on user-fx ctx) per Spec 002 §The binary
                      fx-handler signature (line 603) + §Drain-loop
                      pseudocode (lines 916, 961-963). Reserved-fx bodies
                      for `:dispatch` / `:dispatch-later` read it to
                      propagate inheritable keys onto the child dispatch
                      per §Cascade propagation.
    :effects          the originating handler's full effects map (the
                      closed `{:db ... :fx ...}` shape). Used ONLY to
                      stamp shape info onto the terminating
                      `:event/do-fx` trace marker's `:tags` (rf2-twt7m
                      Change 2): `:fx` (the vector returned) and
                      `:db-present?` (boolean, true iff the handler
                      returned a `:db` slot). NOT threaded into per-fx
                      invocations — fx handlers already receive the
                      effects shape they need via per-fx args. Absent ⇒
                      the trace marker degrades gracefully (no `:fx` /
                      `:db-present?` slots).

  The 3-arity (no `opts`) is the bare machine-exit / cascade-fx walk
  shape; the router supplies the full opts map once per drained event.

  Per rf2-9dk9y: the `:coeffects` stamp moved OFF this marker and
  ONTO `:rf.event/run-end` (where it rides regardless of whether the
  handler returned `:fx` — the prior placement silently dropped the
  COEFFECTS row when a handler returned only `:db`)."
  ([frame-id fx-vec active-platform]
   (do-fx frame-id fx-vec active-platform nil))
  ([frame-id fx-vec active-platform
    {:keys [overrides origin-event parent-envelope effects]}]
   (doseq [pair fx-vec]
     ;; Per-entry shape policing (rf2-n6d3m): a non-nil/non-empty NON-vector
     ;; entry (the forgot-the-inner-vector typo) emits
     ;; :rf.error/effect-map-shape and is dropped; nil/empty stays the legal
     ;; conditional-fx no-op; a well-shaped entry walks normally. Composes
     ;; with rf2-24zly's whole-:fx-value check one level up (events.cljc).
     (when (fx-entry-ok? pair frame-id origin-event)
       (handle-one-fx frame-id pair active-platform (or overrides {})
                      origin-event parent-envelope)))
   ;; Per rf2-twt7m Change 2: stamp `:fx` + `:db-present?` onto the
   ;; `:event/do-fx` marker's `:tags` when the caller supplied the
   ;; effects map. The full `:db` VALUE is NOT stamped — slice
   ;; changes already ride the App-db diff trace
   ;; (`:event/db-changed`), and the value can be huge. Presence +
   ;; shape is what consumers (Event lens, Xray) need to align
   ;; cascade rows with handler returns. The tag-map is the third
   ;; arg to `trace/emit!`; `trace/emit!` itself is DCE-gated, so
   ;; prod builds elide both the construction and the emit.
   (trace/emit! :rf.fx :rf.fx/do-fx
                (cond-> {:frame frame-id}
                  (some? effects)  (assoc :rf.event/fx          (:fx effects)
                                          :rf.event/db-present? (contains? effects :db))))))
