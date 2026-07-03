(ns re-frame.http.test-support
  "Test-support namespace for the managed-HTTP artefact (Spec 014).

  ## What lives here (rf2-lwmgw — single discoverable home)

  Per [rf2-lwmgw](#) (audit-of-audits #15) the namespace is now the **sole
  home** for every HTTP test-machinery surface:

   - the stubbing macros / fns:
      - `with-managed-request-stubs`        — body-bracketing macro
      - `with-managed-request-stubs*`       — plain-fn surface
      - `install-managed-request-stubs!`    — multi-`deftest` installer
      - `uninstall-managed-request-stubs!`  — idempotent teardown
   - load-time registration of the two canned-stub fxs:
      - `:rf.http/managed-canned-success`
      - `:rf.http/managed-canned-failure`
   - the late-bind hook publication the `re-frame.core` re-export
     `rf/with-managed-request-stubs` (and its `with-managed-request-stubs*`
     plumbing) resolves through.

  The ergonomic `with-managed-request-stubs` macro is the `re-frame.core`
  façade surface. The raw `install-managed-request-stubs!` /
  `uninstall-managed-request-stubs!` pair is NOT a façade export (rf2-ntwwyt
  — test-support infrastructure, not app-facing core surface): tests call
  these two defns directly from this namespace, so they carry no late-bind
  hook.

  The previous arrangement split these across two namespaces — the macros
  lived in `re-frame.http.managed`, and `re-frame.http.test-support` was a
  bare \"registration gate\" for the canned-stub fxs. A test author reaching
  for the HTTP stub helper had to know which surface lived where. The
  consolidation (rf2-lwmgw, Mike-confirmed option (a) on audit-of-audits
  #15) drops that split: one namespace, one require, every HTTP test
  surface.

  The production fx surface (`:rf.http/managed`, `:rf.http/managed-abort`,
  the middleware family) continues to live in `re-frame.http.managed`.
  Production / SSR app code must NOT `:require` this namespace.

  ## Adoption

  Test files / dev demos that exercise any HTTP stub surface — the
  `with-managed-request-stubs` macro, the raw install/uninstall pair, or the
  canned-stub fx ids via `:fx-overrides` — add this namespace to their
  require closure:

  ```clojure
  (ns my-app.tests
    (:require [re-frame.http.managed]        ;; production fx surface
              [re-frame.http.test-support])) ;; stub macros + canned fxs
  ```

  ## Why this exists at all (rf2-cdmle, follow-up to rf2-zk08x)

  The canned-stub fxs

    - `:rf.http/managed-canned-success`
    - `:rf.http/managed-canned-failure`

  are test-only affordances per Spec 014 §Testing. Earlier they registered
  at `re-frame.http.managed` namespace load, gated on
  `re-frame.interop/debug-enabled?`. That gate works on CLJS — under
  `:advanced + goog.DEBUG=false` the entire `(when ...)` body DCEs, fx-id
  keyword string fragments and all. On the JVM, however, `debug-enabled?`
  is unconditionally true; the canned-stub fxs were therefore registered
  in JVM/SSR production builds too — discoverable via
  `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}` from
  any handler.

  rf2-zk08x's audit flagged this as a security-surface posture mismatch:
  test stubs ought not be production-default API. Per the operator decision
  the gate moved from \"`when debug-enabled?`\" to **\"explicit test-support
  import\"**: loading this namespace registers the two fxs against the same
  handler bodies the prior gate used. Production posture: this namespace
  is unreferenced from any production module, so CLJS `:advanced` trims it
  wholesale and JVM/SSR sees classpath absence through the normal artefact
  require boundary.

  ## Public surface (registered at ns-load)

  - `:rf.http/managed-canned-success` — synthesised success reply.
  - `:rf.http/managed-canned-failure` — synthesised failure reply.

  Plus the four stub macros / fns listed above (and the matching late-bind
  hook publication under `:http/with-managed-request-stubs*` for the façade
  `with-managed-request-stubs` macro)."
  (:require [re-frame.events               :as events]
            [re-frame.frame                :as frame]
            [re-frame.fx                   :as fx]
            [re-frame.http.encoding        :as encoding]
            [re-frame.http.handlers        :as handlers]
            [re-frame.http.middleware      :as middleware]
            [re-frame.http.privacy         :as privacy]
            [re-frame.late-bind            :as late-bind]
            [re-frame.registrar            :as registrar]
            [re-frame.router               :as router]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- canned-stub handler bodies (rf2-w59es5) ------------------------------
;;
;; These synthesise a managed-HTTP reply WITHOUT a real transport, for the
;; canned-stub fxs (`:rf.http/managed-canned-*`) and the route-map stub
;; (`stub-handler`) below. They are TEST scaffolding — they used to live in
;; the production-loaded `re-frame.http.machine-wrapper` so the (then-split)
;; stub macros could reach them without a circular require. Now that the
;; macros and the canned-stub fx registrations both live HERE (rf2-lwmgw),
;; the constraint is gone and the bodies moved here too, leaving
;; `http-machine-wrapper` to carry only the production machine spec.
;;
;; rf2-2utlm / rf2-k67u3 — the canned path delegates to
;; `middleware/run-after-then-dispatch!`, the shared run-`:after`-then-late-
;; bind reply tail `http-transport/dispatch-reply!` also uses, so the
;; `:after`-chain + build-reply-event + late-bind lookup pattern lives in one
;; place.
;;
;; rf2-622e3 — origin-event resolution lives in
;; `encoding/resolve-origin-event` (single source of truth shared with
;; `http-managed/managed-handler`).

(defn run-request-chain
  "Per rf2-yhfgf — the request-side middleware chain (Spec 014 §Middleware)
  fires for every issued request, not just real-transport ones. The canned
  stub fxs replace the TRANSPORT, not the entire managed pipeline, so they
  walk the chain before synthesising the reply. The chain's `:before` fns
  may carry load-bearing side effects (auth-token attachment, idempotency-
  key stamping, observability) that the canned path would otherwise mask.

  A throw inside any `:before` raises `:rf.error/http-interceptor-failed`
  with the same classification the real handler emits (per `run-interceptor-
  chain!`), and the canned reply is NOT dispatched.

  Returns the post-`:before` middleware-ctx so the caller can thread it
  through `run-after-chain!` — the same ctx the `:after` chain sees on
  the real-transport path (carried forward by `managed-handler` as the
  normalised ctx's `:middleware-ctx`). The route-map stub (`stub-handler`,
  rf2-azrcs) ALSO reads the post-`:before` `:request` back off this ctx to
  key its match against the url the managed pipeline would actually issue,
  and runs `handlers/validate-url!` on it — the final-url validation belongs
  to the `:rf.http/managed` OVERRIDE-TARGET role (the stub), not to this
  shared chain walk, which the lower-level canned handlers also call with a
  bare `:value` and no `:request`.

  rf2-xmp74u — seeds the SAME effective top-level `:sensitive?` into `ctx0`
  that `http-handlers/managed-handler` seeds on the real-transport path
  (via `privacy/request-sensitive?`, OR-reducing per-call `:sensitive?` and
  `[:request :sensitive?]`). Without it, a request that opted in via the
  TOP-LEVEL `:sensitive? true` form ran the canned/stub `:before` chain with
  no top-level flag, so a throwing `:before` emitted
  `:rf.error/http-interceptor-failed` WITHOUT redacting non-denylisted query
  values — a stub-path leak + test false-green relative to production. The
  chain's own `:sensitive-of` reducer still recomputes the EFFECTIVE
  sensitivity from the threaded ctx (so a `:before` that MARKS the request
  sensitive is honoured); seeding the floor here matches production's
  pre-chain reading."
  [frame-ctx args-map]
  (let [;; EP-0002 carried invariant — the canned stub runs inside a
        ;; cascade, so the fx context carries the envelope frame as
        ;; `:frame`; a nil stamp is an invariant failure
        ;; (`:rf.error/no-frame-context`), never a synthesised `:rf/default`.
        frame-id     (frame/require-frame-stamp!
                       (:frame frame-ctx) :rf.http/managed-canned
                       {:where 'rf.http/run-request-chain})
        origin-event (encoding/resolve-origin-event frame-ctx args-map)
        sensitive?   (privacy/request-sensitive? args-map origin-event)
        ctx0         {:request    (:request args-map)
                      :args       args-map
                      :frame      frame-id
                      :event      origin-event
                      :sensitive? sensitive?}]
    (middleware/run-interceptor-chain! frame-id ctx0)))

(defn- dispatch-canned-reply!
  "rf2-r5m22 — the canned-stub reply tail, mirroring
  `http-transport/dispatch-reply!`: thread the synthesised reply-payload
  through the per-frame `:after` interceptor chain (REVERSE registration
  order) BEFORE handing off to the late-bind reply router.

  Before this, the canned path ran ONLY the `:before` half of the
  interceptor chain (`run-request-chain`) and called the reply router
  directly — so an `:after` carrying response-time telemetry, header-
  driven auth refresh, or any of the response-side use-cases the
  middleware contract sells (Spec 014 §Middleware) silently never fired
  on the stub path. That broke `run-request-chain`'s own promise that
  load-bearing interceptor side effects 'fire on the canned path the
  same way they fire on the real-transport path' — stub-path response
  handling diverged from production.

  `middleware-ctx` is the post-`:before` ctx `run-request-chain`
  produced for THIS request — the exact same shape the real-transport
  `:after` chain sees, so a `:before` that recorded a wall-clock mark /
  stamped a correlation-id can read its own work back in the `:after`.
  An `:after` throw propagates as `:rf.error/http-interceptor-failed`
  (per `run-after-chain!`), matching the real path; the reply is then
  not dispatched.

  rf2-k67u3 — the run-`:after`-then-dispatch tail is shared with the
  real-transport reply path (`http-transport/dispatch-reply!`) via
  `middleware/run-after-then-dispatch!`. The canned path always supplies
  a `:middleware-ctx` (produced by `run-request-chain`), so the `:after`
  chain always runs here."
  [{:keys [origin-event explicit-on reply-payload kind frame middleware-ctx]}]
  (middleware/run-after-then-dispatch!
    {:frame          frame
     :middleware-ctx middleware-ctx
     :origin-event   origin-event
     :explicit-on    explicit-on
     :reply-payload  reply-payload
     :kind           kind}))

;; rf2-azrcs — the canned-success / canned-failure bodies split into a
;; pre-computed-ctx emit (`emit-canned-success!` / `emit-canned-failure!`)
;; plus a thin run-the-chain-then-emit wrapper. The route-map stub
;; (`stub-handler`) runs the `:before` chain ONCE itself (so it can key its
;; route match against the post-`:before` url), then calls the emit fns with
;; that same ctx — WITHOUT re-running `:before` (which would double-fire
;; load-bearing interceptor side effects).
(defn emit-canned-success!
  "Synthesise a success reply from a PRE-COMPUTED post-`:before`
  `middleware-ctx` (rf2-azrcs). Threads the reply through the `:after`
  chain via `dispatch-canned-reply!`. Does NOT run the `:before` chain —
  the caller already did."
  [frame-ctx args-map middleware-ctx]
  (let [;; EP-0002 carried invariant — fx-context `:frame` is the cascade
        ;; envelope stamp; a nil stamp is an invariant failure, never a
        ;; synthesised `:rf/default`.
        frame-id     (frame/require-frame-stamp!
                       (:frame frame-ctx) :rf.http/managed-canned-success
                       {:where 'rf.http/emit-canned-success!})
        value        (get args-map :value {:stubbed true})
        origin-event (encoding/resolve-origin-event frame-ctx args-map)
        ;; rf2-ibksxg — the canned stub delivers a MINIMAL canonical reply
        ;; (`:status :ok` + `:value`), the shape a handler actually branches
        ;; on. A stub has no real request lifecycle, so the identity facts the
        ;; real transport carries (`:work/id` / `:attempt` / `:completed-at`)
        ;; are deliberately OMITTED — synthesising fake ones would add noise no
        ;; handler reads. The real transport's full envelope is pinned by the
        ;; lowering conformance (`http-reply-lowering-test`).
        reply        {:status :ok :value value}]
    (dispatch-canned-reply!
      {:origin-event   origin-event
       :explicit-on    {:supplied? (contains? args-map :on-success)
                        :value     (:on-success args-map)}
       :reply-payload  reply
       :kind           :success
       :frame          frame-id
       :middleware-ctx middleware-ctx})
    nil))

(defn emit-canned-failure!
  "Synthesise a failure reply from a PRE-COMPUTED post-`:before`
  `middleware-ctx` (rf2-azrcs). Symmetric with `emit-canned-success!`."
  [frame-ctx args-map middleware-ctx]
  (let [;; EP-0002 carried invariant — fx-context `:frame` is the cascade
        ;; envelope stamp; a nil stamp is an invariant failure, never a
        ;; synthesised `:rf/default`.
        frame-id     (frame/require-frame-stamp!
                       (:frame frame-ctx) :rf.http/managed-canned-failure
                       {:where 'rf.http/emit-canned-failure!})
        kind         (or (:kind args-map) :rf.http/transport)
        tags         (or (:tags args-map) {})
        failure      (assoc tags :kind kind)
        origin-event (encoding/resolve-origin-event frame-ctx args-map)
        ;; rf2-ibksxg — deliver a MINIMAL canonical reply: the classified
        ;; `:rf.http/*` failure map rides verbatim under `:error`, and an
        ;; `:rf.http/aborted` kind maps to `:status :cancelled` (mirroring the
        ;; real transport's status taxonomy) while every other kind is
        ;; `:status :error`. The stub omits the real transport's identity facts
        ;; (`:work/id` / `:attempt` / `:work/status` / `:completed-at`) — a
        ;; handler branches on `:status` and reads `:error`, nothing more; the
        ;; full envelope is pinned by the lowering conformance.
        reply        (if (= :rf.http/aborted kind)
                       {:status :cancelled :error failure}
                       {:status :error :error failure})]
    (dispatch-canned-reply!
      {:origin-event   origin-event
       :explicit-on    {:supplied? (contains? args-map :on-failure)
                        :value     (:on-failure args-map)}
       :reply-payload  reply
       :kind           :failure
       :frame          frame-id
       :middleware-ctx middleware-ctx})
    nil))

(defn canned-success-handler
  "Stub fx — synthesises a success reply per Spec 014 §Testing.

  Per rf2-yhfgf — walks the per-frame request-side (`:before`) interceptor
  chain before synthesising the reply, so `:before` interceptors with
  load-bearing side effects (auth headers, observability) fire on the
  canned path the same way they fire on the real-transport path.
  Per rf2-azrcs — the chain walk (`run-request-chain`) also validates the
  final post-`:before` url with the canonical `:rf.error/http-bad-request`.

  Per rf2-r5m22 — also threads the reply through the response-side
  (`:after`) chain (via `dispatch-canned-reply!`), mirroring
  `http-transport/dispatch-reply!` so the stub path is a faithful test
  seam for BOTH halves of the middleware chain."
  [frame-ctx args-map]
  (emit-canned-success! frame-ctx args-map (run-request-chain frame-ctx args-map)))

(defn canned-failure-handler
  "Stub fx — synthesises a failure reply per Spec 014 §Testing.

  Per rf2-yhfgf — walks the per-frame request-side (`:before`) interceptor
  chain before synthesising the reply (symmetric with `canned-success-
  handler`). Real-transport failures still go through the chain too; the
  canned path honours the same pre-transport contract.

  Per rf2-r5m22 — also threads the reply through the response-side
  (`:after`) chain (via `dispatch-canned-reply!`), mirroring
  `http-transport/dispatch-reply!`."
  [frame-ctx args-map]
  (emit-canned-failure! frame-ctx args-map (run-request-chain frame-ctx args-map)))

;; ---- optional `:after-ms` delay (rf2-j1mo4) ------------------------------
;;
;; Per Mike's ruling (B, 2026-05-30): a delay is a PARAMETER of the same
;; effect, not a new effect — so rather than minting `-later` fx ids the
;; existing canned-stub fxs take an optional `:after-ms` arg.
;;
;;   - absent / 0 / non-positive → reply lands immediately (current
;;     behaviour, unchanged);
;;   - positive N               → reply lands after an N-ms
;;     `:dispatch-later` tick.
;;
;; The timer is the framework-native `:dispatch-later` (NOT raw
;; `interop/set-timeout!`), so the deferred reply is observable in the
;; tape and time-travel-safe — Tool-Pair time-travel and the documented
;; `:dispatch-later` nil-override seam apply automatically. This is the
;; single framework-owned home for what the example demo stubs previously
;; open-coded as a per-app three-hop chain (stub-fx → schedule-reply →
;; `:dispatch-later` → deliver-reply → canned-success).
;;
;; The deliverer is one self-recursive framework event. First entry (with
;; a positive `:after-ms`) schedules the `:dispatch-later` re-dispatching
;; itself with `:after-ms` stripped; the deferred entry (no `:after-ms`)
;; re-fires the named canned fx, which synthesises the reply through the
;; ordinary immediate path. Routing the re-fire back through the fx id
;; (rather than calling the handler body directly) keeps the canned fx
;; visible in the tape on the deferred tick exactly as it is on the
;; immediate path.

(def ^:private deliver-canned-reply-id :rf.http/deliver-canned-reply)

(events/reg-event deliver-canned-reply-id
  {:doc "Framework-private (rf2-j1mo4). Delivers a canned HTTP reply, optionally
         after an `:after-ms` delay. Dispatched by the `:rf.http/managed-canned-*`
         fxs when their args-map carries a positive `:after-ms`; self-recurses
         once through `:dispatch-later` to honour the delay, then re-fires the
         named canned fx for immediate synthesis. No user dispatches this
         directly."}
  (fn deliver-canned-reply [_ [_ fx-id args-map]]
    (let [after-ms (:after-ms args-map)]
      (if (and after-ms (pos? after-ms))
        ;; Schedule the (now-immediate) re-fire after the delay. Stripping
        ;; `:after-ms` is what makes the deferred entry take the immediate
        ;; branch below — exactly one timer tick, never a loop.
        {:fx [[:dispatch-later
               {:ms    after-ms
                :event [deliver-canned-reply-id fx-id (dissoc args-map :after-ms)]}]]}
        ;; No (remaining) delay — re-fire the canned fx for immediate synthesis.
        {:fx [[fx-id args-map]]}))))

(defn- with-after-ms
  "Decorate a canned-stub fx handler body so a positive `:after-ms` on the
  args-map defers the reply via the framework `:dispatch-later` timer
  (rf2-j1mo4). Absent / 0 / non-positive `:after-ms` runs `body-fn`
  immediately — byte-for-byte the pre-rf2-j1mo4 behaviour. `fx-id` is the
  canned fx's own id, threaded so the deferred re-fire targets the same fx.

  On the deferred path the re-fire runs inside a DIFFERENT event context
  (the framework deliverer), so the originating event is no longer
  reachable through `(:event frame-ctx)`. We resolve it eagerly on the
  immediate dispatch and pin it onto the args-map as `:rf.http/origin-
  event` BEFORE deferring; on the deferred re-entry the wrapper threads
  that pinned origin back onto `frame-ctx` as `:event` so the body's
  `encoding/resolve-origin-event` addresses the caller's originating
  handler — not the framework deliverer event."
  [fx-id body-fn]
  (fn after-ms-aware-handler [frame-ctx args-map]
    (let [after-ms (:after-ms args-map)]
      (if (and after-ms (pos? after-ms))
        ;; Deferred path — pin the origin, then hand off to the deliverer
        ;; event which schedules the `:dispatch-later`.
        (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
          (let [pinned (assoc args-map :rf.http/origin-event
                              (encoding/resolve-origin-event frame-ctx args-map))]
            (dispatch! [deliver-canned-reply-id fx-id pinned]
                       (cond-> {} (:frame frame-ctx) (assoc :frame (:frame frame-ctx))))))
        ;; Immediate path. On the deferred re-entry the ambient `:event`
        ;; is the framework deliverer; restore the caller's pinned origin
        ;; so reply addressing is identical to the synchronous path.
        (let [ctx (if-let [origin (:rf.http/origin-event args-map)]
                    (assoc frame-ctx :event origin)
                    frame-ctx)]
          (body-fn ctx args-map))))
    nil))

;; ---- canned-stub fx registrations ----------------------------------------
;;
;; Per the namespace docstring: the gate is \"explicit test-support
;; import\". These (fx/reg-fx ...) calls fire iff some namespace in the
;; require closure pulled `re-frame.http.test-support` in. Production app
;; code must not. The handler bodies (`canned-success-handler` /
;; `canned-failure-handler`) live HERE alongside their registrations
;; (rf2-w59es5 — the stub scaffolding consolidated into this test-support
;; namespace). The `with-after-ms` decorator (rf2-j1mo4) adds the optional
;; `:after-ms` delay around those same bodies without changing their
;; contract.

(fx/reg-fx :rf.http/managed-canned-success
           {:doc "Spec 014 — synthesised success reply (test stub).
                  Registration gated on explicit `re-frame.http.test-support`
                  require per rf2-cdmle. Optional `:after-ms` (rf2-j1mo4)
                  defers the reply via `:dispatch-later`."}
           (with-after-ms :rf.http/managed-canned-success
                          canned-success-handler))

(fx/reg-fx :rf.http/managed-canned-failure
           {:doc "Spec 014 — synthesised failure reply (test stub).
                  Registration gated on explicit `re-frame.http.test-support`
                  require per rf2-cdmle. Optional `:after-ms` (rf2-j1mo4)
                  defers the reply via `:dispatch-later`."}
           (with-after-ms :rf.http/managed-canned-failure
                          canned-failure-handler))

;; ---- with-managed-request-stubs ------------------------------------------
;;
;; Per rf2-lwmgw the stub macros / fns live HERE alongside the canned-stub
;; fx registrations. The previous split (macros in `re-frame.http.managed`,
;; gate-only namespace here) misleadingly named this ns for a role it did
;; not own.

(defn- stub-handler
  "Route-map-consulting `:rf.http/managed` override target.

  rf2-azrcs — the stub now keys its route match against the request the
  managed pipeline would ACTUALLY issue, not the pre-middleware draft. It
  runs the per-frame `:before` chain ONCE (`run-request-chain` above, which
  also validates the final url with the canonical
  `:rf.error/http-bad-request`), reads the post-`:before` `:method`/`:url`
  back off that ctx to key the route map, then emits the canned reply
  through the `:after` chain with that SAME ctx — without re-running
  `:before` (no double-firing of load-bearing interceptor side effects).

  Consequences of the prior pre-`:before` matching this fixes:
   - a base-URL / url-rewriting `:before` made the stub match the ORIGINAL
     url (false-green when keyed to the draft, false-fail when keyed to the
     final url the production transport sends); and
   - a `:before` that blanked the url received a synthetic stubbed reply
     instead of the production `:rf.error/http-bad-request`, masking the
     invalid request. `run-request-chain` now throws before any match."
  [stubs frame-ctx args-map]
  ;; Run the `:before` chain ONCE up front (a `:before` throw propagates
  ;; exactly as the real `:rf.http/managed` handler's does — no reply is
  ;; synthesised). Then validate the FINAL post-`:before` url with the
  ;; canonical `:rf.error/http-bad-request` — the stub is the
  ;; `:rf.http/managed` override target, so it owes the same final-url
  ;; guard the real handler runs after its `:before` chain. A `:before`
  ;; that blanks the url now throws here instead of receiving a synthetic
  ;; stubbed reply.
  (let [middleware-ctx (run-request-chain frame-ctx args-map)
        _              (handlers/validate-url! (:request middleware-ctx))
        ;; Match against the POST-`:before` request — the url the managed
        ;; pipeline would actually send.
        req            (:request middleware-ctx)
        method         (or (:method req) :get)
        url            (:url req)
        entry          (get stubs [method url])
        reply          (:reply entry)]
    (cond
      (and entry (contains? reply :ok))
      (emit-canned-success! frame-ctx (assoc args-map :value (:ok reply))
                            middleware-ctx)

      (and entry (contains? reply :failure))
      (emit-canned-failure! frame-ctx
                            (-> args-map
                                (assoc :kind (or (:kind (:failure reply))
                                                 :rf.http/transport))
                                (assoc :tags (dissoc (:failure reply) :kind)))
                            middleware-ctx)

      :else
      (emit-canned-failure! frame-ctx
                            (assoc args-map
                                   :kind :rf.http/transport
                                   :tags {:message "no stub matched"
                                          :method  method
                                          :url     url})
                            middleware-ctx))))

(def ^:private stub-fx-id :rf.http/managed-test-stub)

;; rf2-vn8qjv (issue 2) — stack/token discipline for the lower-level
;; install/uninstall surface. The single stub fx-id `:rf.http/managed-test-stub`
;; is the DOCUMENTED routing target — users hardcode it in
;; `:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}`, so the id
;; must stay stable. But a naive "register then unconditionally clear" makes
;; nested installs clobber: an inner `install!` replaces the outer handler and
;; the inner `uninstall!` clears it, leaving the outer scope routing to a
;; now-absent fx. We snapshot the prior handler onto a stack at install and
;; restore the top-of-stack at uninstall, so an outer install survives a fully
;; nested inner install/uninstall pair. Empty stack → clear the fx (no prior
;; handler to restore), so a balanced top-level install/uninstall leaks nothing.
(defonce ^:private stub-fx-handler-stack (atom []))

(defn- peek-and-pop!
  "Pop the top element off the `stack-atom`'s vector, returning a
  `[popped]` 1-tuple (nil-safe — an empty stack yields `[nil]`). Test-time
  single-threaded use; the read-then-swap is not atomic and need not be."
  [stack-atom]
  (let [top (peek @stack-atom)]
    (swap! stack-atom (fn [s] (if (seq s) (pop s) s)))
    [top]))

(defn install-managed-request-stubs!
  "Test-time helper. `stubs` is `{[method url] {:reply <:ok|:failure>}}`.
  Registers the `:rf.http/managed-test-stub` fx — a route-map-consulting
  override target that synthesises the configured reply.

  NOTE: this lower-level fn only REGISTERS the stub fx; it does not route
  `:rf.http/managed` through it. To route, either dispatch with
  `:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}`, or — far
  more usually — wrap the test body via `with-managed-request-stubs` /
  `with-managed-request-stubs*`, which install a per-scope stub fx AND the
  matching `:rf.http/managed → <scope-stub>` override for the body's
  dynamic extent, so plain `dispatch-sync` calls auto-route with no manual
  `:fx-overrides`.

  rf2-vn8qjv — stack/token discipline: install snapshots any prior
  `:rf.http/managed-test-stub` handler before overwriting it, so a nested
  install/uninstall pair restores the outer install on exit. Always paired
  with `uninstall-managed-request-stubs!` (LIFO).

  Per Spec 014 §Testing — the framework ships canonical stub fxs."
  [stubs]
  (swap! stub-fx-handler-stack conj (registrar/handler :fx stub-fx-id))
  (fx/reg-fx stub-fx-id
             {:doc "with-managed-request-stubs synthesised stub"}
             (fn [frame-ctx args-map]
               (stub-handler stubs frame-ctx args-map)))
  stub-fx-id)

(defn uninstall-managed-request-stubs!
  "Tear down the stub fx `install-managed-request-stubs!` registered. Pops
  the install stack: if an outer install's handler was snapshotted it is
  restored (so a nested install/uninstall pair leaves the outer install
  intact); otherwise the fx id is cleared.

  Idempotent — calling without a matching install (empty stack) clears the
  fx id, a no-op when it is already absent, so a teardown that runs without
  a matching install (or twice) is safe and leaks no test fx. Test-time
  helper."
  []
  (let [[prior] (peek-and-pop! stub-fx-handler-stack)]
    (if prior
      (fx/reg-fx stub-fx-id
                 {:doc "with-managed-request-stubs synthesised stub"}
                 prior)
      (fx/clear-fx stub-fx-id)))
  nil)

;; rf2-vn8qjv (issue 2) — `with-managed-request-stubs*` allocates a UNIQUE
;; override fx-id per scope so nested scopes compose. The lower-level
;; install/uninstall surface above keys off the single stable id (the
;; documented `:fx-overrides` target); the scoped wrapper instead mints a
;; fresh fx-id per invocation and binds the override to THAT id. An outer
;; scope's fx and override therefore stay live and correctly-routed while a
;; fully-nested inner scope installs, runs, and tears down its own distinct
;; fx — no clobber, no order-dependence.
(defonce ^:private scope-stub-counter (atom 0))

(defn- next-scope-stub-id
  "Mint a process-unique `:rf.http/managed-test-stub-<n>` fx-id for one
  `with-managed-request-stubs*` scope."
  []
  (keyword "rf.http" (str "managed-test-stub-" (swap! scope-stub-counter inc))))

(defn with-managed-request-stubs*
  "Function form: install stubs, route `:rf.http/managed` through them for
  the dynamic extent of `thunk`, run `thunk`, then uninstall. Test-time
  helper.

  `stubs` is `{[method url] {:reply <:ok|:failure>}}`. The wrapper mints a
  process-unique stub fx-id for THIS scope (rf2-vn8qjv), registers the
  route-map-consulting stub under it, and binds the lexical-scope
  fx-override `{:rf.http/managed <scope-stub-id>}`
  (`re-frame.router/*fx-overrides*`, the public `rf/with-fx-overrides`
  seam) for the thunk's dynamic extent. So every `dispatch-sync` inside
  the body auto-routes by `:request :method` + `:request :url` with NO
  per-call `:fx-overrides` — matching the documented contract (Spec 014
  §Testing). A dispatch that deliberately supplies its OWN `:fx-overrides`
  still wins: the router merges the lexical default UNDER the per-call opt
  (per-call > lexical > per-frame).

  Nesting composes: because each scope owns a distinct fx-id, an inner
  `with-managed-request-stubs*` registers and tears down ITS id without
  touching the outer scope's still-live fx + override. After the inner
  scope exits, an outer-scope dispatch still routes to the outer stub."
  [stubs thunk]
  (let [scope-stub-id (next-scope-stub-id)]
    (try
      (fx/reg-fx scope-stub-id
                 {:doc "with-managed-request-stubs (scoped) synthesised stub"}
                 (fn [frame-ctx args-map]
                   (stub-handler stubs frame-ctx args-map)))
      ;; Bind the lexical-scope override so plain dispatches in the body
      ;; route `:rf.http/managed` → this scope's route-map stub
      ;; automatically. The thunk's dispatches run synchronously within
      ;; this dynamic extent; per-call `:fx-overrides` still take
      ;; precedence (router merge).
      (binding [router/*fx-overrides* (merge router/*fx-overrides*
                                             {:rf.http/managed scope-stub-id})]
        (thunk))
      (finally
        (fx/clear-fx scope-stub-id)))))

#?(:clj
   (defmacro with-managed-request-stubs
     "Test-time helper. `stubs` is `{[method url] {:reply <:ok|:failure>}}`.
     Installs a per-call fx-override on `:rf.http/managed` that consults
     the stub map, synthesises the configured reply, and runs `body`.
     Every `dispatch-sync` in the body auto-routes by method + URL — no
     manual `:fx-overrides` needed (the helper installs the override for
     the body's dynamic extent).

     Per Spec 014 §Testing."
     [stubs & body]
     `(with-managed-request-stubs* ~stubs (fn [] ~@body))))

;; ---- late-bind hook publication ------------------------------------------
;;
;; The `re-frame.core` `with-managed-request-stubs` macro (and its
;; `with-managed-request-stubs*` plumbing) resolves through the late-bind
;; hook table — see `re-frame.core-http`. Publishing the hook from THIS
;; namespace (per rf2-lwmgw) means `with-managed-request-stubs*` raises
;; `:rf.error/http-artefact-missing` until a test opts in by `:require`-ing
;; `re-frame.http.test-support` — symmetric with the canned-stub fx ids'
;; registration gate above.
;;
;; The raw `install-managed-request-stubs!` / `uninstall-managed-request-stubs!`
;; pair is NOT a `re-frame.core` façade export (rf2-ntwwyt), so it publishes no
;; late-bind hook — tests call these two defns directly from this namespace.

(late-bind/set-fn! :http/with-managed-request-stubs*      with-managed-request-stubs*)
