(ns re-frame.trace-test
  "Spec 009 — trace-stream completeness.

  Per the bead rf2-91tl brief: register a listener, dispatch through a
  representative flow that should emit every documented op, and assert
  the canonical event shape per op. Plus exercise the listener API:
  multiple listeners, removal, exception isolation.

  JVM-only by intent — the trace stream is substrate-independent and
  CLJS coverage adds no signal here.

  Canonical envelope per Spec 009 §Core fields (and `re-frame.trace/emit!`):
    {:operation <kw>            ;; specific op (e.g. :event :frame/created)
     :op-type   <kw>             ;; discriminator (:event :frame :machine ...)
     :id        <int>             ;; unique per process
     :time      <ms>              ;; host clock
     :tags      {...}             ;; op-specific bag
     :source    <kw> (optional)   ;; trigger origin — hoisted from tags
     :recovery  <kw> (optional)}  ;; recovery policy — hoisted from tags

  For ops Spec 009 documents but the implementation never emits, this
  test files (or already filed) `bd` bug bead rf2-hyxg and the assertion
  is left in place as `(is (some ...) \"see rf2-hyxg\")` so the gap
  surfaces on the regression dashboard rather than being silently
  skipped."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; rf2-qwm0a: the public-tooling surface
            ;; (`register-listener!` / `clear-listeners!` / `trace-buffer`
            ;; / …) lives in `re-frame.trace.tooling`. `re-frame.trace`
            ;; ships thin wrappers delegating via late-bind so production
            ;; bundles DCE the buffer/listener machinery — but the hooks
            ;; only publish once `trace.tooling` loads. This test does
            ;; not use `re-frame.test-support` (which transitively loads
            ;; the tooling ns), so we require it directly here.
            [re-frame.trace.tooling]))

;; ---- fixtures --------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  ;; Framework events / fx are registered at namespace-load time in
  ;; routing.cljc; clear-all! wiped them. Re-eval those registrations
  ;; so :rf.route/transitioned, :rf/url-requested, :rf.route/* etc. resurrect.
  (require 're-frame.routing :reload)
  ;; rf2-dbiv8 — the test-only `:rf.test/simulate-http-resolution` fixture
  ;; event lives in the routing test-support ns (not the production
  ;; façade); the :rf.route.nav-token/stale-suppressed trace assertion
  ;; below dispatches it. Reload so it re-seats after clear-all!.
  (require 're-frame.routing.test-support :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- ops-of
  "Return every (op-type, operation) pair seen by the recorder, for
  human-readable test failure messages."
  [events]
  (vec (distinct (map (juxt :op-type :operation) events))))

(defn- has-op?
  "Test if any captured event has the given (op-type, operation)."
  [events op-type operation]
  (some (fn [ev]
          (and (= op-type  (:op-type ev))
               (= operation (:operation ev))))
        events))

(defn- find-op
  "Return the first event matching (op-type, operation), or nil."
  [events op-type operation]
  (some (fn [ev]
          (when (and (= op-type  (:op-type ev))
                     (= operation (:operation ev)))
            ev))
        events))

(defn- valid-envelope?
  "Every trace event must have these top-level keys per Spec 009 §Core
  fields. The envelope produced by `trace/emit!` includes :id, :time,
  :operation, :op-type, :tags."
  [ev]
  (and (map? ev)
       (integer? (:id ev))
       (number?  (:time ev))
       (keyword? (:operation ev))
       (keyword? (:op-type ev))
       (map?     (:tags ev))))

;; ---- comprehensive flow -----------------------------------------------------

(deftest trace-stream-completeness
  (testing "a representative dispatch flow emits every documented op-type with the canonical envelope shape"
    (let [recorded (atom [])
          listener (fn [ev] (swap! recorded conj ev))]
      (rf/register-listener! :trace ::recorder listener)

      ;; ---- Frame lifecycle: :frame/created, :frame/re-registered ----------
      (rf/reg-frame :test/main {:doc "comprehensive flow frame"})
      ;; Re-register to fire :frame/re-registered.
      (rf/reg-frame :test/main {:doc "comprehensive flow frame (rev 2)"})

      ;; ---- Event handlers --------------------------------------------------
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0 :items [1 2 3]}}))
      (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
      ;; Re-register :inc with a different fn body to fire
      ;; :rf.registry/handler-replaced.
      (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))

      ;; Subs (used to demonstrate the absence of :sub/run / :sub/create
      ;; emit — see rf2-hyxg).
      (rf/reg-sub :n     (fn [db _] (:n db)))
      (rf/reg-sub :n*2   :<- [:n] (fn [n _] (* 2 (or n 0))))

      ;; ---- A user-registered fx fires :event/do-fx wrapping the walk ------
      (let [fx-fired (atom 0)]
        (rf/reg-fx :test/incr (fn [_ _] (swap! fx-fired inc)))
        (rf/reg-event :do-fx-event
          (fn [_ _]
            {:db {:n 99}
             :fx [[:test/incr :go]]}))
        (rf/dispatch-sync [:do-fx-event] {:frame :test/main})
        (is (= 1 @fx-fired) "user fx ran"))

      ;; ---- :rf.fx/override-applied ----------------------------------------
      (rf/reg-fx :prod/sender   (fn [_ _] :prod-fired))
      (rf/reg-fx :stub/sender   (fn [_ _] :stub-fired))
      (rf/reg-event :send
        (fn [_ _] {:fx [[:prod/sender :payload]]}))
      (rf/dispatch-sync [:send]
                        {:frame        :test/main
                         :fx-overrides {:prod/sender :stub/sender}})

      ;; ---- :rf.fx/override-fallthrough error -------------------------------
      ;; Override redirects to an UNregistered fx; runtime falls back to
      ;; the original.
      (rf/dispatch-sync [:send]
                        {:frame        :test/main
                         :fx-overrides {:prod/sender :no-such/fx}})

      ;; ---- :rf.error/no-such-fx -------------------------------------------
      (rf/reg-event :send-broken
        (fn [_ _] {:fx [[:nonexistent/fx :payload]]}))
      (rf/dispatch-sync [:send-broken] {:frame :test/main})

      ;; ---- :rf.error/no-such-handler --------------------------------------
      (rf/dispatch-sync [:no.such/event] {:frame :test/main})

      ;; ---- :rf.error/handler-exception ------------------------------------
      (rf/reg-event :throws (fn [{:keys [db]} _] {:db (throw (ex-info "oops" {:bad? true}))}))
      (rf/dispatch-sync [:throws] {:frame :test/main})

      ;; ---- :rf.error/fx-handler-exception ---------------------------------
      (rf/reg-fx :throwing-fx (fn [_ _] (throw (ex-info "fx blew" {}))))
      (rf/reg-event :run-throwing-fx
        (fn [_ _] {:fx [[:throwing-fx :ignored]]}))
      (rf/dispatch-sync [:run-throwing-fx] {:frame :test/main})

      ;; ---- :rf.fx/skipped-on-platform (warning) ---------------------------
      (rf/reg-fx :client-only-fx
                 {:platforms #{:client}}
                 (fn [_ _] :nope))
      (rf/reg-event :run-client-fx
        (fn [_ _] {:fx [[:client-only-fx :payload]]}))
      ;; The plain-atom adapter on JVM uses :server platform by default, so
      ;; this should skip-and-warn rather than execute.
      (rf/dispatch-sync [:run-client-fx] {:frame :test/main})

      ;; ---- :rf.error/no-such-sub ------------------------------------------
      ;; A sub whose :<- input isn't registered.
      (rf/reg-sub :unresolved
        :<- [:no-such/input]
        (fn [v _] v))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/subscribe-once [:unresolved] {:frame :test/main})

      ;; ---- :rf.error/sub-exception ----------------------------------------
      (rf/reg-sub :throwing-sub (fn [_db _] (throw (ex-info "sub-boom" {}))))
      (rf/subscribe-once [:throwing-sub] {:frame :test/main})

      ;; ---- :rf.error/dispatch-sync-in-handler -----------------------------
      (rf/reg-event :nested-sync
        (fn [_ _]
          (rf/dispatch-sync [:inc] {:frame :test/main})
          {}))
      (rf/dispatch-sync [:nested-sync] {:frame :test/main})

      ;; ---- :rf.error/frame-destroyed --------------------------------------
      ;; Subscribe against a frame that doesn't exist.
      (rf/subscribe-once [:n] {:frame :no.such/frame})

      ;; ---- :rf.error/drain-depth-exceeded ---------------------------------
      ;; A handler that re-dispatches itself; the drain bound (default 100)
      ;; trips and emits the structured error.
      (rf/reg-event :loop-forever
        (fn [_ _]
          {:fx [[:dispatch [:loop-forever]]]}))
      (rf/dispatch-sync [:loop-forever] {:frame :test/main})

      ;; ---- Routing: :rf.warning/route-shadowed-by-equal-score -------------
      ;; Two routes with the same structural rank (same path shape, both
      ;; concrete). The second registration sees the first and warns.
      (rf/reg-route :route/a {} "/foo")
      (rf/reg-route :route/b {} "/foo")

      ;; ---- Routing: :rf.route.nav-token/allocated + :rf.route/fragment-changed ----
      ;; A reg-route + dispatch [:rf.route/transitioned url] threads through the
      ;; allocate-token + match-url emit path.
      (rf/reg-route :user/show {} "/users/:id")
      (rf/dispatch-sync [:rf.route/transitioned "/users/42"] {:frame :test/main})
      ;; Now repeat with a fragment change only — emits :rf.route/fragment-changed
      ;; with prev/next-fragment shape.
      (rf/dispatch-sync [:rf.route/transitioned "/users/42#section"] {:frame :test/main})

      ;; ---- Routing: :rf.route/navigation-blocked --------------------------
      ;; Set up a :can-leave sub that returns false, then request a URL.
      (rf/reg-sub :always-block (fn [_ _] false))
      (rf/reg-route :nav/blocker {:can-leave :always-block} "/blockable")
      ;; Move "into" the blockable route so its :can-leave guards the next nav.
      (rf/dispatch-sync [:rf.route/transitioned "/blockable"] {:frame :test/main})
      (rf/dispatch-sync [:rf/url-requested {:url "/users/42"}] {:frame :test/main})

      ;; ---- Routing: :rf.route.nav-token/stale-suppressed ---------------------
      ;; Allocate a token by navigating, then dispatch the framework's
      ;; nav-token-checking event with a deliberately mismatched token.
      (rf/dispatch-sync [:rf.route/transitioned "/users/7"] {:frame :test/main})
      (rf/dispatch-sync [:rf.test/simulate-http-resolution
                         {:carried-nav-token :stale/token
                          :on-success-event  [:noop]}]
                        {:frame :test/main})

      ;; ---- Machine: :rf.machine/transition + :rf.machine.timer/scheduled --
      ;; Register a machine where the destination state of the first
      ;; transition declares an :after — entering that state schedules
      ;; the timer, which is what we want to trace.
      (let [m {:initial :red
               :data    {}
               :states
               {:red    {:on    {:tick {:target :green}}}
                :green  {:after {500 {:target :yellow}}
                         :on    {:tick {:target :yellow}}}
                :yellow {:on    {:tick {:target :red}}}}}]
        (rf/reg-machine :machine/tl m)
        ;; Seed and trigger first tick.
        (rf/dispatch-sync [:seed] {:frame :test/main})
        ;; Initialise machine snapshot in app-db.
        (rf/reg-event :machine/init
          (fn [{:keys [db]} _]
            {:db (assoc-in db [:rf.runtime/machines :snapshots :machine/tl]
                      {:state :red :data {}})}))
        (rf/dispatch-sync [:machine/init] {:frame :test/main})
        (rf/dispatch-sync [:machine/tl [:tick]] {:frame :test/main})
        (rf/dispatch-sync [:machine/tl [:tick]] {:frame :test/main}))

      ;; ---- Frame destruction: :frame/destroyed ----------------------------
      (rf/destroy-frame! :test/main)

      (rf/unregister-listener! :trace ::recorder)

      (let [events @recorded
            seen   (ops-of events)]

        (testing "every captured event satisfies the universal envelope shape"
          (is (every? valid-envelope? events)
              (str "non-conformant envelopes seen — first 3: "
                   (vec (take 3 (remove valid-envelope? events))))))

        ;; ---- :rf.event op-type ---------------------------------------------
        (testing ":rf.event/run-start / :rf.event/run-end operations"
          (let [run-starts (filter #(= :rf.event/run-start (:operation %)) events)
                run-ends   (filter #(= :rf.event/run-end (:operation %)) events)]
            (is (seq run-starts) ":rf.event/run-start fires for each handler invocation")
            (is (seq run-ends)   ":rf.event/run-end fires for each handler invocation")
            ;; Tag shape: the first run-start carries :rf.trace/event-id,
            ;; :rf.event/v, :frame.
            (let [t (:tags (first run-starts))]
              (is (keyword? (:rf.trace/event-id t)))
              (is (vector?  (:rf.event/v t)))
              (is (keyword? (:frame t))))))

        (testing ":rf.event :rf.event/db-changed fires when a handler returns :db"
          (is (has-op? events :rf.event :rf.event/db-changed)
              "expected :rf.event :rf.event/db-changed at least once")
          (let [t (:tags (find-op events :rf.event :rf.event/db-changed))]
            (is (keyword? (:rf.trace/event-id t)))
            (is (vector?  (:rf.event/v t)))
            (is (keyword? (:frame t)))))

        (testing ":rf.fx :rf.fx/do-fx wraps the fx walk on every dispatch"
          (is (has-op? events :rf.fx :rf.fx/do-fx)
              "expected :rf.fx :rf.fx/do-fx at least once")
          (is (keyword? (:frame (:tags (find-op events :rf.fx :rf.fx/do-fx))))))

        ;; ---- :rf.fx op-type ------------------------------------------------
        (testing ":rf.fx :rf.fx/override-applied fires under :fx-overrides"
          (is (has-op? events :rf.fx :rf.fx/override-applied)
              "expected :rf.fx :rf.fx/override-applied")
          (let [t (:tags (find-op events :rf.fx :rf.fx/override-applied))]
            (is (= :prod/sender (:rf.fx/from t)))
            (is (= :stub/sender (:rf.fx/to t)))))

        ;; ---- :warning op-type ----------------------------------------------
        (testing ":warning :rf.fx/skipped-on-platform fires when an fx's :platforms excludes the active platform"
          (is (has-op? events :warning :rf.fx/skipped-on-platform)
              "expected :warning :rf.fx/skipped-on-platform")
          (let [t (:tags (find-op events :warning :rf.fx/skipped-on-platform))]
            (is (= :client-only-fx (:rf.fx/id t)))
            (is (= #{:client}      (:rf.fx/registered-platforms t)))
            (is (set? (:rf.fx/registered-platforms t)))))

        (testing ":warning :rf.warning/route-shadowed-by-equal-score fires on equal-rank route registration"
          (is (has-op? events :warning :rf.warning/route-shadowed-by-equal-score)
              "expected :warning :rf.warning/route-shadowed-by-equal-score")
          (let [t (:tags (find-op events :warning :rf.warning/route-shadowed-by-equal-score))]
            (is (keyword? (:route-id t)))
            (is (keyword? (:shadowed t)))))

        ;; ---- :rf.frame op-type ---------------------------------------------
        (testing ":rf.frame :rf.frame/created fires on first reg-frame for an id"
          (is (has-op? events :rf.frame :rf.frame/created)
              "expected :rf.frame :rf.frame/created")
          (let [t (:tags (find-op events :rf.frame :rf.frame/created))]
            (is (keyword? (:frame t)))
            (is (map?     (:config t)))))

        (testing ":rf.frame :rf.frame/re-registered fires on subsequent reg-frame for the same id"
          (is (has-op? events :rf.frame :rf.frame/re-registered)
              "expected :rf.frame :rf.frame/re-registered")
          (is (keyword? (:frame (:tags (find-op events :rf.frame :rf.frame/re-registered))))))

        (testing ":rf.frame :rf.frame/destroyed fires on destroy-frame!"
          (is (has-op? events :rf.frame :rf.frame/destroyed)
              "expected :rf.frame :rf.frame/destroyed")
          (is (keyword? (:frame (:tags (find-op events :rf.frame :rf.frame/destroyed))))))

        ;; ---- :rf.registry op-type ------------------------------------------
        (testing ":rf.registry :rf.registry/handler-replaced fires on EVERY re-registration (rf2-6w7zn)"
          ;; Per Spec 001 §Hot-reload trace surface the emit is
          ;; unconditional on re-registration — the prior `different-fn?`
          ;; gate dropped events for kinds like `:frame` whose slot
          ;; replacement need not rotate `:handler-fn`. Tools branch on
          ;; the `:different-fn?` tag (preserved below) to suppress
          ;; idempotent reload noise on their side.
          (is (has-op? events :rf.registry :rf.registry/handler-replaced)
              "expected :rf.registry :rf.registry/handler-replaced")
          ;; The flow re-registers BOTH `:test/main` (a frame, same
          ;; handler-fn) and `:inc` (an event, different fn body) so
          ;; both events fire. Find the `:inc` event explicitly so the
          ;; `:different-fn?` assertion targets the real fn-change case.
          (let [different-events (filterv (fn [ev]
                                            (and (= :rf.registry (:op-type ev))
                                                 (= :rf.registry/handler-replaced
                                                    (:operation ev))
                                                 (true? (get-in ev [:tags :different-fn?]))))
                                          events)
                idempotent-events (filterv (fn [ev]
                                             (and (= :rf.registry (:op-type ev))
                                                  (= :rf.registry/handler-replaced
                                                     (:operation ev))
                                                  (false? (get-in ev [:tags :different-fn?]))))
                                           events)]
            (is (seq different-events)
                "expected at least one handler-replaced with :different-fn? true")
            (is (seq idempotent-events)
                "expected at least one handler-replaced with :different-fn? false (frame re-reg)")
            (let [t (:tags (first different-events))]
              (is (keyword? (:kind t)))
              (is (some?    (:id t)))
              (is (true?    (:different-fn? t))))))

        ;; ---- :rf.machine op-type -------------------------------------------
        ;; Per Spec 009 §:op-type vocabulary the machine trace family rides
        ;; op-type :rf.machine (the :rf.* single-root scheme; #1973 +
        ;; rf2-aa5qi). The operation carries the slashed identity.
        (testing ":rf.machine :rf.machine/transition fires on a machine event"
          (is (has-op? events :rf.machine :rf.machine/transition)
              "expected :rf.machine :rf.machine/transition")
          (let [t (:tags (find-op events :rf.machine :rf.machine/transition))]
            ;; rf2-ws5thu — the transition row addresses the LIVE actor instance
            ;; under :actor-id (:machine-id reserved for the registered TYPE).
            (is (keyword? (:actor-id t)))
            (is (vector?  (:event t)))
            (is (map?     (:before t)))
            (is (map?     (:after t)))))

        (testing ":rf.machine :rf.machine.timer/scheduled fires when a state with :after is entered"
          (is (has-op? events :rf.machine :rf.machine.timer/scheduled)
              "expected :rf.machine :rf.machine.timer/scheduled")
          (let [t (:tags (find-op events :rf.machine :rf.machine.timer/scheduled))]
            (is (keyword? (:state t)))
            (is (number?  (:delay t)))))

        ;; ---- routing :rf.event ops -----------------------------------------
        ;; Route lifecycle traces ride the :rf.event family (op-type
        ;; :rf.event; rf2-a20e9 completed the #1973 migration in routing).
        (testing ":rf.event :rf.route.nav-token/allocated fires on :rf.route/transitioned full nav"
          (is (has-op? events :rf.event :rf.route.nav-token/allocated)
              "expected :rf.event :rf.route.nav-token/allocated")
          (let [t (:tags (find-op events :rf.event :rf.route.nav-token/allocated))]
            (is (keyword? (:route-id t)))
            (is (some?    (:nav-token t)))))

        (testing ":rf.event :rf.route/fragment-changed fires on fragment-only navigation"
          ;; Per Spec 009 §:op-type vocabulary and Spec 012 §Fragments:
          ;; :rf.route/fragment-changed is the canonical op-name for fragment-only
          ;; navigation. Consumers discriminate full vs fragment-only by :tags.
          (is (has-op? events :rf.event :rf.route/fragment-changed)
              "expected :rf.event :rf.route/fragment-changed")
          (let [t (:tags (find-op events :rf.event :rf.route/fragment-changed))]
            (is (keyword? (:route-id t)))
            (is (string?  (:next-fragment t)))))

        (testing ":rf.event :rf.route/navigation-blocked fires when :can-leave returns false"
          (is (has-op? events :rf.event :rf.route/navigation-blocked)
              "expected :rf.event :rf.route/navigation-blocked")
          (let [t (:tags (find-op events :rf.event :rf.route/navigation-blocked))]
            (is (string?  (:requested-url t)))
            (is (keyword? (:rejecting-route t)))))

        ;; ---- :error op-type errors -----------------------------------------
        (testing ":error :rf.error/handler-exception"
          (is (has-op? events :error :rf.error/handler-exception)
              "expected :error :rf.error/handler-exception")
          (let [ev (find-op events :error :rf.error/handler-exception)
                t  (:tags ev)]
            (is (= :no-recovery (:recovery ev)))
            (is (string?  (:exception-message t)))
            (is (some?    (:event t)))))

        (testing ":error :rf.error/fx-handler-exception"
          (is (has-op? events :error :rf.error/fx-handler-exception)
              "expected :error :rf.error/fx-handler-exception")
          (let [t (:tags (find-op events :error :rf.error/fx-handler-exception))]
            (is (= :throwing-fx (:rf.fx/id t)))
            (is (string? (:exception-message t)))))

        (testing ":error :rf.error/no-such-fx"
          (is (has-op? events :error :rf.error/no-such-fx)
              "expected :error :rf.error/no-such-fx")
          (is (= :nonexistent/fx
                 (:rf.fx/id (:tags (find-op events :error :rf.error/no-such-fx))))))

        (testing ":error :rf.error/no-such-handler"
          (is (has-op? events :error :rf.error/no-such-handler)
              "expected :error :rf.error/no-such-handler")
          (let [t (:tags (find-op events :error :rf.error/no-such-handler))]
            (is (= :no.such/event (:rf.trace/event-id t)))
            (is (= :event         (:kind t)))))

        (testing ":error :rf.error/no-such-sub"
          (is (has-op? events :error :rf.error/no-such-sub)
              "expected :error :rf.error/no-such-sub"))

        (testing ":error :rf.error/sub-exception"
          (is (has-op? events :error :rf.error/sub-exception)
              "expected :error :rf.error/sub-exception"))

        (testing ":error :rf.error/dispatch-sync-in-handler"
          (is (has-op? events :error :rf.error/dispatch-sync-in-handler)
              "expected :error :rf.error/dispatch-sync-in-handler"))

        (testing ":error :rf.error/frame-destroyed (subscribe under unknown frame)"
          (is (has-op? events :error :rf.error/frame-destroyed)
              "expected :error :rf.error/frame-destroyed"))

        (testing ":error :rf.error/override-fallthrough"
          (is (has-op? events :error :rf.error/override-fallthrough)
              "expected :error :rf.error/override-fallthrough"))

        (testing ":error :rf.error/drain-depth-exceeded"
          (is (has-op? events :error :rf.error/drain-depth-exceeded)
              "expected :error :rf.error/drain-depth-exceeded — likely indicates the dispatch loop landed elsewhere")
          (let [t (:tags (find-op events :error :rf.error/drain-depth-exceeded))]
            (is (number? (:depth t)))
            (is (some?   (:last-event t)))))

        (testing ":error :rf.route.nav-token/stale-suppressed"
          (is (has-op? events :error :rf.route.nav-token/stale-suppressed)
              "expected :error :rf.route.nav-token/stale-suppressed"))

        ;; ---- Spec 009 ops never emitted by the implementation --------------
        ;; These op-types appear in Spec 009 §:op-type vocabulary but the
        ;; implementation never emits them. Filed as rf2-hyxg. The
        ;; assertions below intentionally fail loudly so closing rf2-hyxg
        ;; (either by tightening the spec or by adding the emit) re-greens
        ;; the regression dashboard.
        ;;
        ;; Each is wrapped with `is-strict?` set to false so this test
        ;; documents the gap without blocking other assertions; flip
        ;; `is-strict?` to true once rf2-hyxg lands to enforce.
        (let [is-strict? true
              gap-check  (fn [op-type operation]
                           (if is-strict?
                             (is (has-op? events op-type operation)
                                 (str "expected " op-type " " operation
                                      " — see rf2-hyxg"))
                             ;; non-strict: report status but pass.
                             (when-not (has-op? events op-type operation)
                               (println "  [trace-test] note:" op-type operation
                                        "not emitted (rf2-hyxg)"))))]
          (testing "Spec 009 documented ops not yet emitted (rf2-hyxg)"
            (gap-check :rf.sub                        :rf.sub/run)
            (gap-check :rf.sub                        :rf.sub/create)
            (gap-check :rf.machine.lifecycle/created  :rf.machine.lifecycle/created)
            (gap-check :rf.machine.lifecycle/destroyed :rf.machine.lifecycle/destroyed)
            ;; op-type is the machine FAMILY :rf.machine; the slashed
            ;; identity lives in :operation (rf2-aa5qi fixed these two
            ;; emit-sites which previously rode the malformed slashed
            ;; op-type :rf.machine/event-received / :rf.machine/snapshot-updated).
            (gap-check :rf.machine :rf.machine/event-received)
            (gap-check :rf.machine :rf.machine/snapshot-updated)
            (gap-check :rf.registry :rf.registry/handler-registered)
            (gap-check :rf.registry :rf.registry/handler-cleared)))

        (testing "diagnostic: every (op-type, operation) pair the flow produced"
          ;; Always passes; printing only when test verbosity helps.
          (is (vector? seen)
              (str "captured pairs: " (pr-str seen))))))))

;; ---- per-op DURATION timing (rf2-hhh92) -----------------------------------
;;
;; The dev trace stream carries per-op wall-clock so the Trace panel's
;; DURATION column reads it off the trace (previously only views carried
;; `:rf.view/elapsed-ms`; subs/fx/flows/handler rendered `—`). Per Spec
;; 009 §:tags the new tags are `:rf.sub/elapsed-ms`, `:rf.fx/elapsed-ms`,
;; `:rf.flow/computed`'s bare `:elapsed-ms`, `:rf.cofx/elapsed-ms`, and the
;; HANDLER-BODY-only `:rf.event/elapsed-ms` on `:rf.event/run-end`. All
;; ride `interop/debug-enabled?` so production DCEs them (the elision probe
;; pins the prod absence).

;; NOTE on sub timing: `:rf.sub/run` is driven by the reactive memo
;; wrapper, which only fires under a reactive adapter (Reagent / UIx /
;; Helix) — the plain-atom JVM substrate does not recompute through the
;; memo wrapper. So `:rf.sub/elapsed-ms` is asserted in the adapter CLJS
;; test (`view_rendered_op_cljs_test.cljs`, alongside the existing
;; `:rf.sub/run` + `:rf.view/elapsed-ms` coverage). fx / flows / handler-
;; body timing DO fire in plain-atom JVM and are pinned here.

(deftest per-op-timing-tags-on-the-trace-stream
  (testing "fx / flows / handler-body carry elapsed-ms on the dev trace"
    (let [recorded (atom [])]
      (rf/register-listener! :trace ::timing (fn [ev] (swap! recorded conj ev)))

      ;; A flow whose :derive recomputes when [:n] changes (outermost
      ;; :after of the cascade).
      (rf/reg-flow :timing/doubled {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 2 (or n 0))))
      (rf/reg-fx :timing/side (fn [_ _] :ok))
      (rf/reg-event :timing/seed
        (fn [_ _]
          {:db {:n 1}
           :fx [[:timing/side :go]]}))

      (rf/dispatch-sync [:timing/seed])
      (rf/unregister-listener! :trace ::timing)

      (let [events @recorded]
        (testing ":rf.fx/handled carries :rf.fx/elapsed-ms"
          (let [handled (filter #(= :rf.fx/handled (:operation %)) events)]
            (is (seq handled) "at least one :rf.fx/handled emitted")
            (is (every? #(number? (get-in % [:tags :rf.fx/elapsed-ms])) handled)
                "every :rf.fx/handled carries a numeric :rf.fx/elapsed-ms")))

        (testing ":rf.flow/computed carries :elapsed-ms"
          (let [computed (filter #(= :rf.flow/computed (:operation %)) events)]
            (is (seq computed) "the flow recomputed at least once")
            (is (every? #(number? (get-in % [:tags :elapsed-ms])) computed)
                "every :rf.flow/computed carries a numeric :elapsed-ms")))

        (testing ":rf.event/run-end carries the HANDLER-BODY :rf.event/elapsed-ms"
          (let [run-end (filter #(= :rf.event/run-end (:operation %)) events)]
            (is (seq run-end) "at least one :rf.event/run-end emitted")
            (is (every? #(number? (get-in % [:tags :rf.event/elapsed-ms])) run-end)
                "every :rf.event/run-end carries a numeric :rf.event/elapsed-ms")))))))

;; ---- listener API: lifecycle and isolation --------------------------------

(deftest trace-listener-lifecycle
  (testing "register-listener! is keyed; same-id re-registration replaces; unregister-listener! removes only that id"
    (let [a-events (atom [])
          b-events (atom [])
          c-events (atom [])]
      (rf/register-listener! :trace ::a (fn [ev] (swap! a-events conj ev)))
      (rf/register-listener! :trace ::b (fn [ev] (swap! b-events conj ev)))
      (rf/register-listener! :trace ::c (fn [ev] (swap! c-events conj ev)))

      (rf/reg-event :ping (fn [{:keys [db]} _] {:db (assoc db :ping? true)}))
      (rf/dispatch-sync [:ping])
      (let [a1 (count @a-events)
            b1 (count @b-events)
            c1 (count @c-events)]
        (is (pos? a1) "listener a received events")
        (is (= a1 b1) "listeners receive the same events")
        (is (= a1 c1)))

      ;; Remove ::b; ::a and ::c continue.
      (rf/unregister-listener! :trace ::b)
      (rf/dispatch-sync [:ping])
      (is (> (count @a-events) (count @b-events))
          "after removal, ::b stops accumulating")
      (is (= (count @a-events) (count @c-events))
          "::a and ::c stay in lock-step")

      ;; Replace ::a with a different fn under the same id.
      (let [a-events-2 (atom [])]
        (rf/register-listener! :trace ::a (fn [ev] (swap! a-events-2 conj ev)))
        (let [a-pre (count @a-events)]
          (rf/dispatch-sync [:ping])
          (is (= a-pre (count @a-events))
              "the original a-events atom no longer accumulates after re-register under same id"))
        (is (pos? (count @a-events-2))
            "the replacement listener under ::a accumulates"))

      (rf/unregister-listener! :trace ::a)
      (rf/unregister-listener! :trace ::c))))

(deftest trace-listener-exception-isolation
  (testing "a listener that throws does not crash the dispatch flow and does not block other listeners"
    (let [survivor-events (atom [])
          throw-count     (atom 0)]
      (rf/register-listener! :trace ::throwing
        (fn [_ev]
          (swap! throw-count inc)
          (throw (ex-info "tool blew up" {:listener ::throwing}))))
      (rf/register-listener! :trace ::survivor
        (fn [ev] (swap! survivor-events conj ev)))

      (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 0}}))
      (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

      ;; The dispatch flow MUST proceed despite the throwing listener.
      (rf/dispatch-sync [:init])
      (rf/dispatch-sync [:inc])
      (rf/dispatch-sync [:inc])

      (is (= 2 (:n (rf/app-db-value :rf/default)))
          "dispatch flow ran to completion despite a throwing listener")
      (is (pos? @throw-count)
          "the throwing listener WAS invoked (and threw)")
      (is (seq @survivor-events)
          "the surviving listener received events even though the other one threw")
      ;; Sanity: error events ARE delivered too — surviving listener sees both
      ;; ordinary :event traces and any error traces.
      (is (every? #(and (keyword? (:operation %))
                        (keyword? (:op-type %)))
                  @survivor-events)
          "every event the survivor saw conforms to the envelope shape")

      (rf/unregister-listener! :trace ::throwing)
      (rf/unregister-listener! :trace ::survivor))))

;; ---- :rf.trace/no-emit? event-meta opt-out (rf2-qsjda) --------------------
;;
;; Per Spec 009 §Trace-emission opt-out: handlers whose registration meta
;; carries `:rf.trace/no-emit? true` produce NO trace events. The flag is
;; the framework-level escape hatch for trace-consuming integrations
;; whose own bookkeeping dispatches — emitted from inside a trace-cb —
;; would otherwise re-enter the consumer through the trace-cb fan-out
;; and form a cb-dispatch loop. (See `re-frame.trace/*handler-scope*`'s
;; `:no-emit?` slot for the runtime mechanism, per rf2-ryri7.)
;;
;; Covers:
;;   - A handler WITH `:rf.trace/no-emit? true` produces no `:event/
;;     dispatched`, no `:event :run-start` / `:run-end`, no
;;     `:event/db-changed`, no in-cascade emits at all.
;;   - A handler WITHOUT the flag emits normally — sanity baseline so
;;     the no-emit test doesn't trivially pass on a broken framework.

(deftest no-emit-handler-suppresses-every-cascade-trace
  (testing "Handler registration meta `:rf.trace/no-emit? true` causes
            the runtime to emit NO trace events for the dispatch — not
            at queue time (`:event/dispatched`), not at run-start /
            run-end, not on db-commit (`:event/db-changed`), not for
            any in-cascade emit. Per Spec 009 §Trace-emission opt-out
            and rf2-qsjda."
    (rf/reg-event :rf2-qsjda/internal-bookkeeping
                     {:rf.trace/no-emit? true}
                     (fn [{:keys [db]} _] {:db (assoc db :bookkeeping/ran? true)}))

    (let [recorded (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! recorded conj ev)))

      (rf/dispatch-sync [:rf2-qsjda/internal-bookkeeping])

      ;; Handler ran (db committed) but no traces were emitted.
      (is (true? (:bookkeeping/ran? (rf/app-db-value :rf/default)))
          "the handler body still ran — :rf.trace/no-emit? opts out
           of TRACE EMISSION, not handler execution")

      ;; Sanity: no trace events for this dispatch at all. We assert
      ;; on event-id / event-vec tags, since the trace stream might
      ;; carry framework-level emits unrelated to our dispatch
      ;; (e.g. registrar registration traces fired by the
      ;; reg-event above).
      (let [our-events
            (filter
              (fn [ev]
                (let [tags (:tags ev)
                      eid  (or (:rf.trace/event-id tags)
                               (let [ev-vec (:rf.event/v tags)]
                                 (when (vector? ev-vec) (first ev-vec))))]
                  (= :rf2-qsjda/internal-bookkeeping eid)))
              @recorded)]
        (is (empty? our-events)
            (str "expected NO trace events for the :rf.trace/no-emit?
                  handler's dispatch, got: "
                 (vec (map (juxt :op-type :operation) our-events)))))

      (rf/unregister-listener! :trace ::rec))))

(deftest no-emit-flag-absent-emits-normally
  (testing "Baseline sanity: the SAME dispatch shape WITHOUT
            `:rf.trace/no-emit? true` produces the normal cascade
            traces (`:event/dispatched`, run-start, run-end,
            `:event/db-changed`). Pins the opt-out as the difference."
    (rf/reg-event :rf2-qsjda/normal
                     {:doc "without :rf.trace/no-emit?"}
                     (fn [{:keys [db]} _] {:db (assoc db :normal/ran? true)}))

    (let [recorded (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! recorded conj ev)))

      (rf/dispatch-sync [:rf2-qsjda/normal])

      (let [our-events
            (filter
              (fn [ev]
                (let [tags (:tags ev)
                      eid  (or (:rf.trace/event-id tags)
                               (let [ev-vec (:rf.event/v tags)]
                                 (when (vector? ev-vec) (first ev-vec))))]
                  (= :rf2-qsjda/normal eid)))
              @recorded)
            ops (set (map :operation our-events))]
        (is (contains? ops :rf.event/dispatched)
            ":rf.event/dispatched fired for the un-flagged handler")
        (is (contains? ops :rf.event/db-changed)
            ":rf.event/db-changed fired for the un-flagged handler")
        (is (contains? ops :rf.event/run-start)
            ":rf.event/run-start fired for the un-flagged handler")
        (is (contains? ops :rf.event/run-end)
            ":rf.event/run-end fired for the un-flagged handler"))

      (rf/unregister-listener! :trace ::rec))))
