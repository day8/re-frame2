(ns re-frame.routing.test-support
  "Test-support namespace for the routing artefact (Spec 012).

  ## What lives here (rf2-dbiv8 — keep test fixtures out of the production façade)

  This namespace is the **sole home** for the routing test-only fixture
  event:

   - `:rf.test/simulate-http-resolution` — the test-only fixture analogue
     of the production-grade `:rf.route/with-nav-token` fx. Tests /
     conformance fixtures use it to replay an async http completion that
     carries a captured nav-token, exercising the stale-result
     suppression path WITHOUT standing up a real http fx.

  The production-grade counterpart (`:rf.route/with-nav-token`) lives in
  `re-frame.routing.nav-token` and is wired by the `re-frame.routing`
  façade; the test fixture's only consumers are tests and conformance
  fixtures, so it lives behind an explicit test-support require rather
  than in the always-on production registry.

  ## Why this exists at all (rf2-dbiv8, mirrors rf2-cdmle / rf2-zk08x)

  `:rf.test/simulate-http-resolution` was previously registered
  UNCONDITIONALLY in the `re-frame.routing` façade — so a test-runner-
  internal `:rf.test/*` event (reserved by Spec 008 / Conventions.md per
  the `:rf.test/*` namespace) was registered into the registry of EVERY
  app that did `(:require [re-frame.routing])`, and the keyword string
  survived into production bundles. The managed-HTTP artefact already
  solved the identical posture mismatch for its canned-stub fxs by
  gating registration on an explicit `re-frame.http-test-support` require
  (rf2-cdmle, follow-up to rf2-zk08x); this namespace applies the same
  pattern to routing.

  Production posture: this namespace is unreferenced from any production
  module, so CLJS `:advanced` trims it wholesale (fx-id keyword string
  fragments and all) and JVM/SSR sees classpath absence through the
  normal artefact require boundary.

  ## Adoption

  Test files / conformance fixtures that dispatch
  `:rf.test/simulate-http-resolution` add this namespace to their require
  closure alongside the routing façade:

  ```clojure
  (ns my-app.routing-tests
    (:require [re-frame.routing]              ;; production routing surface
              [re-frame.routing.test-support])) ;; test-only fixture event
  ```

  Per the `re-frame.routing` `:reload` recovery pattern (a `clear-all!`
  test fixture re-`require`s the namespace to re-wire every handler), a
  routing test fixture that wipes the registrar should also
  `(require 're-frame.routing.test-support :reload)` so the fixture event
  re-seats."
  (:require [re-frame.events :as events]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(defn simulate-http-resolution-handler
  "`:rf.test/simulate-http-resolution` event-fx handler. Registered at
  ns-load (below) so a `(require 're-frame.routing.test-support :reload)`
  re-wires it on a fresh registrar.

  Replays an async http completion that carries the `:carried-nav-token`
  captured at request time. Match against the current
  `[:rf/runtime :routing :current :nav-token]` → dispatch the
  `:on-success-event` continuation; mismatch → suppress and emit
  `:rf.route.nav-token/stale-suppressed` (same trace shape as the
  production `:rf.route/with-nav-token` handler, so a single conformance
  assertion covers both paths)."
  [{:keys [db frame]} [_ {:keys [on-success-event carried-nav-token]}]]
  (let [current (get-in db [:rf/runtime :routing :current :nav-token])]
    (cond
      (= carried-nav-token current)
      ;; Token matches — dispatch the continuation.
      {:fx [[:dispatch on-success-event]]}

      :else
      ;; Stale — suppress.
      ;; rf2-7d30s — frame-attribute the suppression (matches the
      ;; production `with-nav-token-handler` path) so it lands in the
      ;; emitting frame's epoch / Xray.
      (do (trace/emit-error! :rf.route.nav-token/stale-suppressed
                             (cond-> {:carried-token     carried-nav-token
                                      :current-token     current
                                      :rf.trace/event-id (when (vector? on-success-event)
                                                           (first on-success-event))
                                      :recovery          :replaced-with-default}
                               frame (assoc :frame frame)))
          {}))))

;; ---- test-only fixture event registration --------------------------------
;;
;; Per the namespace docstring: the gate is "explicit test-support
;; import". This (events/reg-event-fx ...) call fires iff some namespace
;; in the require closure pulled `re-frame.routing.test-support` in.
;; Production / SSR app code must NOT. Spec 012 §Navigation tokens —
;; stale-result suppression documents the production `:rf.route/with-nav-token`
;; counterpart; this fixture event is test-runner-internal (`:rf.test/*`).

(events/reg-event-fx :rf.test/simulate-http-resolution
                     simulate-http-resolution-handler)
