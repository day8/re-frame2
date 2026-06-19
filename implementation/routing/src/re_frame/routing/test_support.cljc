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
  gating registration on an explicit `re-frame.http.test-support` require
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
            [re-frame.routing.nav-token :as nav-token]
            [re-frame.routing.reply :as route-reply]))

#?(:clj (set! *warn-on-reflection* true))

(defn simulate-http-resolution-handler
  "`:rf.test/simulate-http-resolution` event handler. Registered at
  ns-load (below) so a `(require 're-frame.routing.test-support :reload)`
  re-wires it on a fresh registrar.

  Replays an async http completion that carries the `:carried-nav-token`
  captured at request time. The stale check is the SAME ordinary
  reply-envelope `:suppress` gate the production
  `:rf.route/with-nav-token` handler uses (via
  `re-frame.routing.reply/suppress?`): match against the current
  `[:rf.runtime/routing :current :nav-token]` → dispatch the
  `:on-success-event` continuation; mismatch → suppress and emit
  `:rf.route.nav-token/stale-suppressed` joined to the route work-id
  (same trace shape as production, so a single conformance assertion
  covers both paths).

  rf2-azcmd3 — the payload's optional `:carried-route-id` is the route id
  CAPTURED at request time (mirrors the production `:route-id` arg). It is
  used for the suppressed attempt's work-id rather than the live slice id at
  stale-arrival, so a cross-route stale completion attributes its work-id to
  the route-loader attempt, not whatever route is live when it arrives.

  rf2-ux8sgg — the payload's optional `:carried-completed-at` is the reply
  completion time the loader captured (the recordable `:rf/time-ms` fact on
  the reply token, EP-0017). It mirrors the production
  `:rf.route/with-nav-token` `:completed-at` arg: when supplied, a stale
  (superseded) completion's reply / trace carries it, so the test path
  exercises the same completion-time-preservation contract as production."
  [{frame :rf.frame/id rdb :rf.db/runtime} [_ {:keys [on-success-event carried-nav-token carried-route-id carried-completed-at]}]]
  ;; EP-0001 (rf2-vzld77): the route slice is durable routing runtime-db state.
  (let [slice   (get-in (or rdb {}) [:rf.runtime/routing :current])
        current (:nav-token slice)]
    (if-not (route-reply/suppress? carried-nav-token current)
      ;; Gate matches (token current) — dispatch the continuation.
      {:fx [[:dispatch on-success-event]]}

      ;; Stale — suppress through the shared reply-envelope correctness
      ;; boundary. rf2-7d30s — frame-attribute the suppression (matches
      ;; the production `with-nav-token-handler` path) so it lands in the
      ;; emitting frame's epoch / Xray.
      (let [event-id (when (vector? on-success-event) (first on-success-event))]
        (nav-token/emit-stale-suppressed!
          {:carried-token carried-nav-token
           :current-token current
           :event-id      event-id
           :frame-id      frame
           ;; rf2-azcmd3 — use the CAPTURED route id (carried with the
           ;; nav-token at request time), NOT `(:route-id slice)` (the route live
           ;; at stale-arrival). Mirrors the production `with-nav-token-
           ;; handler` fix so a cross-route stale completion attributes its
           ;; work-id to the route-loader attempt, not the current route id.
           :route-id      carried-route-id
           :loader-id     event-id
           ;; rf2-ux8sgg — mirror the production `:completed-at` lane so the
           ;; stale reply / trace carries the captured reply completion time.
           :completed-at  carried-completed-at})
        {}))))

;; ---- test-only fixture event registration --------------------------------
;;
;; Per the namespace docstring: the gate is "explicit test-support
;; import". This (events/reg-event ...) call fires iff some namespace
;; in the require closure pulled `re-frame.routing.test-support` in.
;; Production / SSR app code must NOT. Spec 012 §Navigation tokens —
;; stale-result suppression documents the production `:rf.route/with-nav-token`
;; counterpart; this fixture event is test-runner-internal (`:rf.test/*`).

(events/reg-event :rf.test/simulate-http-resolution
                     simulate-http-resolution-handler)
