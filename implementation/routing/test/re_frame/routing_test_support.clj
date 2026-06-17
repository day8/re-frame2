(ns re-frame.routing-test-support
  "Shared JVM test fixtures + helpers for the routing artefact's split
  concern-test namespaces (rf2-u8qe7y finding 3).

  The routing implementation is split into per-concern siblings under
  `re-frame.routing.*` (see `re-frame.routing`'s docstring). The JVM test
  surface mirrors that split: `routing-registry-test`,
  `routing-navigation-test`, `routing-can-leave-test`,
  `routing-nav-token-test`, `routing-scroll-test`, `routing-url-bound-test`,
  `routing-subs-test`, `routing-on-match-error-test`, and the pure
  `routing-plan-test`. Each requires this namespace for the shared
  `reset-runtime` fixture so the registrar/runtime reset + façade `:reload`
  recovery lives in ONE place rather than copied per file.

  Per the long-established consumer-test pattern: `reset-runtime` wipes the
  registrar (`clear-all!`), re-inits the plain-atom substrate, and
  `(require 're-frame.routing :reload)` re-runs the façade's
  `reg-event` / `reg-fx` / `reg-sub` / hook / listener wires on the
  fresh registrar. The `:rf.test/simulate-http-resolution` fixture event
  (`re-frame.routing.test-support`) and `re-frame.ssr`'s ns-load
  registrations are re-seated the same way."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.routing :as routing]
            [re-frame.routing.test-support]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime
  "`:each` fixture: wipe the registrar + runtime state, re-init the
  plain-atom substrate, and re-`require` the routing / ssr / test-support
  namespaces with `:reload` so their ns-load-time registrations resurrect
  after `(registrar/clear-all!)`."
  [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`, and
  ;; routing/nav fxs now require a carried frame stamp. Register `:rf/default`
  ;; explicitly as the conventional single app frame. URL ownership is now an
  ;; EXPLICIT declaration (no `:rf/default`-owns-by-default floor — `url-owner-
  ;; frame-id` returns nil from absence), so this suite's URL-owning frame opts
  ;; in via `{:url-bound? true}`; the body runs with `:rf/default` pinned as
  ;; the ambient scope (the carried-invariant equivalent of wrapping every
  ;; test in `(with-frame :rf/default …)`). Explicit `{:frame …}` opts and
  ;; inner `with-frame`/`reg-frame` in the bodies still win.
  (rf/reg-frame :rf/default {:url-bound? true
                             :doc "Routing-suite default app frame (explicit URL owner)."})
  ;; Framework events / fx (routing.cljc, ssr.cljc) are registered at
  ;; ns-load; clear-all! wiped them. Reload to resurrect.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  ;; rf2-dbiv8: re-seat the test-only `:rf.test/simulate-http-resolution`
  ;; fixture event after clear-all! (it lives in the test-support ns, not
  ;; the production façade).
  (require 're-frame.routing.test-support :reload)
  (routing/reset-counters!)
  ;; rf2-1hncp2 / rf2-oosjmh: the scroll-position cache AND the nav-counter
  ;; high-water marks are module-level host atoms (not runtime-db), so
  ;; `clear-all!` / frame reset does not touch them — drop them explicitly so
  ;; a saved position / counter value never leaks across tests.
  (routing/reset-scroll-cache!)
  (routing/reset-nav-counters!)
  ;; rf2-3l7xxz: the URL-ownership claim-order vector is process-global state
  ;; (re-frame.routing.nav-fx/url-claim-order) that `clear-all!` / the `frames`
  ;; reset above does NOT touch (the registrar's registration-hooks vector is
  ;; `defonce` and survives `clear-all!`, so the `:rf/default` reg at the top
  ;; DID record a claim through the still-live hook — but a PRIOR test's claims
  ;; also accumulated). Reset the vector here so the incumbent is deterministic
  ;; per test, then re-seat `:rf/default`'s claim so it is the first-claimed
  ;; (and thus resolved) owner for this test. Tests that hand ownership to a
  ;; different frame re-register `:rf/default {:url-bound? false}` in their body,
  ;; which drops this claim.
  (routing/reset-url-claims!)
  (rf/reg-frame :rf/default {:url-bound? true
                             :doc "Routing-suite default app frame (explicit URL owner)."})
  (rf/with-frame :rf/default
    (test-fn)))

(defn with-stub-validator
  "Install a tiny stub validator + explainer that interprets schemas as
  Clojure predicates `(fn [v] truthy?)`. Lets the schema-validation tests
  assert the validation path without dragging in Malli / spec.alpha.
  Returns a cleanup fn for the caller to invoke.

  Captures the prior bundle through the encapsulated
  `snapshot-schema-fns` / `restore-schema-fns!` pair (rf2-l4ljvr) rather
  than reaching the raw `validator-fn` / `explainer-fn` atoms — the
  snapshot also preserves the printer, so the cleanup restores the full
  prior bundle.

  rf2-ps05ug: the stub is process-global, so while it is installed EVERY
  schema-validating boundary uses it — including the routing recordable
  allocation cofx, whose `:schema` is now a real Malli VECTOR
  (`[:map [:token :string] [:counter :int]]`). A Malli vector is not a
  callable predicate (and although a vector IS `ifn?` — it implements IFn
  for index lookup — calling it as `(schema value)` throws for a
  non-integer `value`), so a naive call would throw and the fail-closed
  `validate-with-registered-fn` would coerce that to a FALSE, spuriously
  rejecting the well-formed generated nav-allocation during an unrelated
  param-validation test. The stub therefore only adjudicates ACTUAL fn
  schemas (`fn?` — the predicate schemas these tests install) and PASSES
  any other (Malli vector / map) schema. The explainer is symmetric."
  []
  (let [snap     (schemas/snapshot-schema-fns)
        validate (fn [schema value]
                   (if (fn? schema)
                     (boolean (schema value))
                     true))                          ;; non-predicate (Malli) schema → pass
        explain  (fn [schema value]
                   (when (fn? schema)
                     {:reason :stub-explainer :value value}))]
    (schemas/set-schema-fns! {:validate validate :explain explain})
    (fn [] (schemas/restore-schema-fns! snap))))
