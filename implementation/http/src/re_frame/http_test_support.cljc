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
  (:require [re-frame.fx                   :as fx]
            [re-frame.http-encoding        :as encoding]
            [re-frame.http-machine-wrapper :as machine-wrapper]
            [re-frame.http-middleware      :as middleware]
            [re-frame.late-bind            :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- canned-stub fx registrations ----------------------------------------
;;
;; Per the namespace docstring: the gate is \"explicit test-support
;; import\". These (fx/reg-fx ...) calls fire iff some namespace in the
;; require closure pulled `re-frame.http-test-support` in. Production app
;; code must not. The handler bodies live in `re-frame.http-machine-wrapper`
;; (rf2-3i9b) so the `with-managed-request-stubs*` helper — which composes
;; against `canned-success-handler` / `canned-failure-handler` directly —
;; still reaches them without circular requires.

(fx/reg-fx :rf.http/managed-canned-success
           {:doc "Spec 014 — synthesised success reply (test stub).
                  Registration gated on explicit `re-frame.http-test-support`
                  require per rf2-cdmle."}
           machine-wrapper/canned-success-handler)

(fx/reg-fx :rf.http/managed-canned-failure
           {:doc "Spec 014 — synthesised failure reply (test stub).
                  Registration gated on explicit `re-frame.http-test-support`
                  require per rf2-cdmle."}
           machine-wrapper/canned-failure-handler)

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
