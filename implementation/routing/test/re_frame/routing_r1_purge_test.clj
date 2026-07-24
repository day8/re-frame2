(ns re-frame.routing-r1-purge-test
  "EP-0037 R1 follow-through regression (merged-PR-#6875 audit).

  PR #6875 delivered the R1 contract by DELETING the registration calls for
  the pre-R1 on-match machinery, but deletion alone does not evict a
  registration a `defonce` registry already holds. A dev session that loaded
  routing BEFORE the R1 cut and then `(require 're-frame.routing :reload)`s
  under HMR keeps three retired framework registrations:

    - `:rf.route/on-match-error-trap`        — the corpus-wide on-match error
                                               listener (always-on error-emit
                                               registry, `defonce`).
    - `:rf.route.internal/on-match-error`    — the route-match failure event.
    - `:rf.route.internal/settle-transition` — the per-route `:on-match`
                                               settle event.

  A persisting trap could still observe a new blocking-resource `:loading`
  transition, route an `:on-match` throw through the retired handler, and
  resurrect the removed route `:error` / `:on-error` behaviour — a contract
  violation under normal reload.

  This suite reproduces the audit in a real JVM registry: it SEEDS the three
  retired registrations, exercises the ACTUAL reload path
  (`(require 're-frame.routing :reload)`), and proves the façade idempotently
  unregisters exactly those three — no registry reset, no user-registration
  clearing — and that after the reload no stale machinery can convert an
  `:on-match` throw into route `:error` while the ordinary event error channel
  stays intact."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.emit :as emit]
            ;; The always-on error-emit listener registry has no public
            ;; introspection surface (register / unregister / clear only), so
            ;; the presence check reads its `defonce` `listeners` atom directly.
            [re-frame.error-emit]
            [re-frame.events :as events]
            [re-frame.fx :as fx]
            [re-frame.registrar :as registrar]
            [re-frame.routing :as routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

;; ---- the three retired framework ids --------------------------------------

(def ^:private retired-events
  [:rf.route.internal/settle-transition
   :rf.route.internal/on-match-error])

(def ^:private retired-trap :rf.route/on-match-error-trap)

;; ---- registry probes ------------------------------------------------------

(defn- event-registered?
  "True iff an `:event`-kind handler is registered under `id`."
  [id]
  (some? (registrar/lookup :event id)))

(defn- error-listener-registered?
  "True iff an always-on error-emit listener is registered under `id`."
  [id]
  (contains? @#'re-frame.error-emit/listeners id))

(defn- seed-retired-registrations!
  "Install exactly what a pre-R1 generation left in the `defonce` registries:
  the two internal events (via the framework-internal `events/reg-event`, the
  same call the pre-R1 façade used — reserved ids are legitimate on that path)
  and the corpus-wide error trap. Each records into `seen` if it ever runs, so
  a surviving registration is behaviourally observable."
  [seen]
  (events/reg-event :rf.route.internal/settle-transition
                    {:rf/framework-authority? true}
                    (fn [{:keys [db]} _] (swap! seen conj :settle) {:db db}))
  (events/reg-event :rf.route.internal/on-match-error
                    {:rf/framework-authority? true}
                    (fn [{:keys [db]} _] (swap! seen conj :on-match-error) {:db db}))
  (emit/register-error-listener! retired-trap
                                 (fn [_record] (swap! seen conj :trap))))

;; ============================================================================
;; Acceptance criteria 1 + 3 — the reload purges exactly the three retired ids
;; ============================================================================

(deftest reload-purges-the-three-retired-registrations
  (testing "seed the pre-R1 error trap + both internal events, reload the
            current routing façade, and prove all three retired registrations
            are absent — idempotently, targeting ONLY those three (no registry
            reset, no user-registration clearing)"
    (let [seen (atom #{})]
      (seed-retired-registrations! seen)

      ;; A user registration + a framework registration that MUST survive the
      ;; reload — proves the purge is surgical (not a registry reset).
      (rf/reg-event :app/keep-me (fn [{:keys [db]} _] {:db db}))
      (emit/register-error-listener! ::user-probe (constantly nil))

      ;; Sanity: the seed actually landed in the live registries.
      (is (every? event-registered? retired-events)
          "both retired internal events are registered before the reload")
      (is (error-listener-registered? retired-trap)
          "the retired on-match error trap is registered before the reload")

      ;; Exercise the REAL reload path — the façade re-runs its registrations
      ;; and the idempotent purge.
      (require 're-frame.routing :reload)

      (is (not-any? event-registered? retired-events)
          "the reload unregistered both retired internal events")
      (is (not (error-listener-registered? retired-trap))
          "the reload unregistered the retired on-match error trap")

      ;; Surgical: user + non-retired framework registrations are untouched.
      (is (event-registered? :app/keep-me)
          "a user event is NOT cleared by the reload purge")
      (is (error-listener-registered? ::user-probe)
          "a user error listener under a different id is NOT cleared")
      (is (event-registered? :rf.route/navigate)
          "the live framework routing events remain registered after the reload")

      ;; Idempotent: a SECOND reload with nothing to purge is a clean no-op.
      (require 're-frame.routing :reload)
      (is (not-any? event-registered? retired-events)
          "a second reload leaves the retired events absent (idempotent)")
      (is (not (error-listener-registered? retired-trap))
          "a second reload leaves the trap absent (idempotent)")
      (is (empty? @seen)
          "no seeded registration ever ran — the purge was clean")

      (emit/unregister-error-listener! ::user-probe))))

;; ============================================================================
;; Acceptance criterion 2 — no stale machinery can convert an :on-match throw
;; ============================================================================

(deftest reloaded-routing-cannot-convert-on-match-throw-into-route-error
  (testing "after seeding the pre-R1 trap + settle event and reloading, a
            throwing :on-match event (the shape a blocking-resource :loading
            route drives) cannot be converted by stale R1 machinery into route
            :error / a legacy route :on-error; the ordinary event error channel
            stays intact"
    (let [seen (atom #{})]
      (seed-retired-registrations! seen)
      ;; Reload — purges the trap + both internal events.
      (require 're-frame.routing :reload)

      ;; The retired settle event (the readiness-flip path a blocking-resource
      ;; :loading transition would drive) is gone, so nothing can settle a
      ;; :loading route to :error behind the resource projection.
      (is (not (event-registered? :rf.route.internal/settle-transition))
          "the settle-transition readiness-flip event is unregistered")

      ;; A fresh ordinary error listener under a DISTINCT id — proves the
      ;; always-on error channel still delivers after the purge.
      (let [channel (atom [])]
        (emit/register-error-listener! ::channel-probe
                                       (fn [record] (swap! channel conj record)))
        (rf/reg-event :load/boom
                      (fn [_ _] (throw (ex-info "on-match-boom" {:why :test}))))
        (rf/reg-route :route/boom {:on-match [[:load/boom]]} "/boom")
        (fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))

        (rf/dispatch-sync [:rf.route/transitioned "/boom"])

        (is (not (contains? @seen :trap))
            "the retired trap did NOT fire on the :on-match throw (purged)")
        (is (empty? @seen)
            "no retired registration ran — none survived the reload")

        (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                            [:rf.runtime/routing :current])]
          (is (not= :error (:transition slice))
              "no stale machinery flipped the route transition to :error")
          (is (nil? (:error slice))
              ":rf.route/error stays nil — an :on-match throw is not a route error"))

        (is (some (fn [r] (and (= :rf.error/handler-exception (:error r))
                               (= :load/boom (:event-id r))))
                  @channel)
            "the throw reached the ordinary always-on event error channel,
             attributed to the throwing event")

        (emit/unregister-error-listener! ::channel-probe)))))
