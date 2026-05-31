(ns re-frame.http-test-support
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
   - the late-bind hook publications the `re-frame.core` re-exports
     (`rf/with-managed-request-stubs`, `rf/install-managed-request-stubs!`,
     `rf/uninstall-managed-request-stubs!`, `rf/with-managed-request-stubs*`)
     resolve through.

  The previous arrangement split these across two namespaces — the macros
  lived in `re-frame.http-managed`, and `re-frame.http-test-support` was a
  bare \"registration gate\" for the canned-stub fxs. A test author reaching
  for the HTTP stub helper had to know which surface lived where. The
  consolidation (rf2-lwmgw, Mike-confirmed option (a) on audit-of-audits
  #15) drops that split: one namespace, one require, every HTTP test
  surface.

  The production fx surface (`:rf.http/managed`, `:rf.http/managed-abort`,
  the middleware family) continues to live in `re-frame.http-managed`.
  Production / SSR app code must NOT `:require` this namespace.

  ## Adoption

  Test files / dev demos that exercise any HTTP stub surface — the macros,
  the canned-stub fx ids via `:fx-overrides`, or the `re-frame.core`
  re-exports — add this namespace to their require closure:

  ```clojure
  (ns my-app.tests
    (:require [re-frame.http-managed]        ;; production fx surface
              [re-frame.http-test-support])) ;; stub macros + canned fxs
  ```

  ## Why this exists at all (rf2-cdmle, follow-up to rf2-zk08x)

  The canned-stub fxs

    - `:rf.http/managed-canned-success`
    - `:rf.http/managed-canned-failure`

  are test-only affordances per Spec 014 §Testing. Earlier they registered
  at `re-frame.http-managed` namespace load, gated on
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
  hook publications under `:http/install-managed-request-stubs!`,
  `:http/uninstall-managed-request-stubs!`, `:http/with-managed-request-stubs*`)."
  (:require [re-frame.events               :as events]
            [re-frame.fx                   :as fx]
            [re-frame.http-encoding        :as encoding]
            [re-frame.http-machine-wrapper :as machine-wrapper]
            [re-frame.http-middleware      :as middleware]
            [re-frame.late-bind            :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

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

(events/reg-event-fx deliver-canned-reply-id
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
;; require closure pulled `re-frame.http-test-support` in. Production app
;; code must not. The handler bodies live in `re-frame.http-machine-wrapper`
;; (rf2-3i9b) so the `with-managed-request-stubs*` helper — which composes
;; against `canned-success-handler` / `canned-failure-handler` directly —
;; still reaches them without circular requires. The `with-after-ms`
;; decorator (rf2-j1mo4) adds the optional `:after-ms` delay around those
;; same bodies without changing their contract.

(fx/reg-fx :rf.http/managed-canned-success
           {:doc "Spec 014 — synthesised success reply (test stub).
                  Registration gated on explicit `re-frame.http-test-support`
                  require per rf2-cdmle. Optional `:after-ms` (rf2-j1mo4)
                  defers the reply via `:dispatch-later`."}
           (with-after-ms :rf.http/managed-canned-success
                          machine-wrapper/canned-success-handler))

(fx/reg-fx :rf.http/managed-canned-failure
           {:doc "Spec 014 — synthesised failure reply (test stub).
                  Registration gated on explicit `re-frame.http-test-support`
                  require per rf2-cdmle. Optional `:after-ms` (rf2-j1mo4)
                  defers the reply via `:dispatch-later`."}
           (with-after-ms :rf.http/managed-canned-failure
                          machine-wrapper/canned-failure-handler))

;; ---- with-managed-request-stubs ------------------------------------------
;;
;; Per rf2-lwmgw the stub macros / fns live HERE alongside the canned-stub
;; fx registrations. The previous split (macros in `re-frame.http-managed`,
;; gate-only namespace here) misleadingly named this ns for a role it did
;; not own.

(defn- stub-handler
  [stubs frame-ctx args-map]
  (let [req    (:request args-map)
        method (or (:method req) :get)
        url    (:url req)
        entry  (get stubs [method url])
        reply  (:reply entry)]
    (cond
      (and entry (contains? reply :ok))
      (machine-wrapper/canned-success-handler frame-ctx (assoc args-map :value (:ok reply)))

      (and entry (contains? reply :failure))
      (machine-wrapper/canned-failure-handler frame-ctx
                                              (-> args-map
                                                  (assoc :kind (or (:kind (:failure reply))
                                                                   :rf.http/transport))
                                                  (assoc :tags (dissoc (:failure reply) :kind))))

      :else
      (machine-wrapper/canned-failure-handler frame-ctx
                                              (assoc args-map
                                                     :kind :rf.http/transport
                                                     :tags {:message "no stub matched"
                                                            :method  method
                                                            :url     url})))))

(def ^:private stub-fx-id :rf.http/managed-test-stub)

(defn install-managed-request-stubs!
  "Test-time helper. `stubs` is `{[method url] {:reply <:ok|:failure>}}`.
  Registers a per-call fx-override target that consults `stubs` and
  synthesises the configured reply.

  Use with `:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}`
  on `dispatch-sync`, or wrap the test body via `with-managed-request-stubs`.

  Per Spec 014 §Testing — the framework ships canonical stub fxs."
  [stubs]
  (fx/reg-fx stub-fx-id
             {:doc "with-managed-request-stubs synthesised stub"}
             (fn [frame-ctx args-map]
               (stub-handler stubs frame-ctx args-map)))
  stub-fx-id)

(defn uninstall-managed-request-stubs!
  []
  (fx/clear-fx stub-fx-id)
  nil)

(defn with-managed-request-stubs*
  "Function form: install stubs, run thunk, uninstall. Test-time helper."
  [stubs thunk]
  (try
    (install-managed-request-stubs! stubs)
    (thunk)
    (finally
      (uninstall-managed-request-stubs!))))

#?(:clj
   (defmacro with-managed-request-stubs
     "Test-time helper. `stubs` is `{[method url] {:reply <:ok|:failure>}}`.
     Installs a per-call fx-override on `:rf.http/managed` that consults
     the stub map, synthesises the configured reply, and runs `body`.

     Per Spec 014 §Testing."
     [stubs & body]
     `(with-managed-request-stubs* ~stubs (fn [] ~@body))))

;; ---- late-bind hook publication ------------------------------------------
;;
;; The `re-frame.core` re-exports of the stub surface
;; (`install-managed-request-stubs!`, `uninstall-managed-request-stubs!`,
;; `with-managed-request-stubs*`) resolve through the late-bind hook
;; table — see `re-frame.core-http`. Publishing the hooks from THIS
;; namespace (per rf2-lwmgw) means `rf/install-managed-request-stubs!`
;; and friends raise `:rf.error/http-artefact-missing` until a test
;; opts in by `:require`-ing `re-frame.http-test-support` — symmetric
;; with the canned-stub fx ids' registration gate above.

(late-bind/set-fn! :http/install-managed-request-stubs!   install-managed-request-stubs!)
(late-bind/set-fn! :http/uninstall-managed-request-stubs! uninstall-managed-request-stubs!)
(late-bind/set-fn! :http/with-managed-request-stubs*      with-managed-request-stubs*)
