(ns re-frame.http-test-support
  "Test-support namespace for the managed-HTTP artefact (Spec 014).

  ## NOT the stubbing-macro home (rf2-fu71w)

  This namespace is the **registration gate** for the two canned-stub fxs
  (`:rf.http/managed-canned-success`, `:rf.http/managed-canned-failure`).
  Loading the namespace as a side effect registers those two fxs.

  The actual stubbing macros — `with-managed-request-stubs`,
  `with-managed-request-stubs*`, `install-managed-request-stubs!`,
  `uninstall-managed-request-stubs!` — live in `re-frame.http-managed`,
  NOT here. A test author reaching for \"the HTTP stub helper\" wants
  `re-frame.http-managed`; this namespace is required only when the
  canned-stub fx ids need to be reachable through `:fx-overrides`. See
  [Spec 008 §HTTP test surfaces](../../../../../spec/008-Testing.md#http-test-surfaces--two-namespaces-rf2-fu71w)
  and [Spec API.md rows 283–286](../../../../../spec/API.md) for the
  full surface split.

  ## Why this namespace exists (rf2-cdmle, follow-up to rf2-zk08x)

  The two canonical canned-stub fxs

    - `:rf.http/managed-canned-success`
    - `:rf.http/managed-canned-failure`

  are test-only affordances per Spec 014 §Testing. Earlier they registered
  at `re-frame.http-managed` namespace load, gated on
  `re-frame.interop/debug-enabled?`. That gate works on CLJS — under
  `:advanced + goog.DEBUG=false` the entire `(when ...)` body DCEs, fx-id
  keyword string fragments and all (the
  `scripts/check-elision.cjs` sentinels pin that contract). On the JVM,
  however, `debug-enabled?` is unconditionally true; the canned-stub fxs
  were therefore registered in JVM/SSR production builds too — discoverable
  via `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}`
  from any handler.

  rf2-zk08x's audit flagged this as a security-surface posture mismatch:
  test stubs ought not be production-default API. Per the operator decision
  the gate moves from \"`when debug-enabled?`\" to **\"explicit test-support
  import\"**: the canned-stub fxs no longer register at `re-frame.http-managed`
  load time. Test code (and any dev-only demo / testbed that relies on the
  canned shape) opts in by `:require`-ing this namespace; loading it
  registers the two fxs against the same handler bodies the prior gate used.

  ## Public surface (registered at ns-load)

  - `:rf.http/managed-canned-success` — synthesised success reply.
  - `:rf.http/managed-canned-failure` — synthesised failure reply.

  Both fxs delegate to `re-frame.http-machine-wrapper/canned-success-handler`
  / `canned-failure-handler` — the same handler bodies the
  `with-managed-request-stubs` helper composes against. The args contract is
  identical to the contract documented in Spec 014 §Testing; only the
  registration site moved.

  ## Production posture

  Production / SSR application code must NOT `:require` this namespace.
  When absent from the require closure, the canned-stub fx ids are not
  registered, and `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}`
  resolves through the no-such-fx error path the framework raises for any
  unregistered fx id. The new
  `re-frame.http-test-support-jvm-test/canned-stub-fxs-absent-without-test-support`
  test pins this absence on the JVM directly; the
  `scripts/check-elision.cjs` sentinels continue to pin elision in CLJS
  production bundles (the test-support namespace is unreferenced from any
  production module, so :advanced trims it wholesale alongside the gated
  branches).

  ## Adoption

  Test files / dev demos that rely on the canned-stub fx ids — directly via
  `:fx-overrides`, or indirectly via `(registrar/handler :fx :rf.http/managed-canned-*)`
  lookup — must add this namespace to their require closure:

  ```clojure
  (ns my-app.tests
    (:require [re-frame.http-managed]
              [re-frame.http-test-support]))
  ```

  Code that uses `with-managed-request-stubs` / `install-managed-request-stubs!`
  does NOT need this namespace — those helpers register their own
  `:rf.http/managed-test-stub` fx at user invocation time, independent of
  the canned-stub fx ids."
  (:require [re-frame.fx                   :as fx]
            [re-frame.http-machine-wrapper :as machine-wrapper]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- canned-stub fx registrations ----------------------------------------
;;
;; Per the namespace docstring: the gate is now \"explicit test-support
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
