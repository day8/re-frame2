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
            ;; (`register-trace-cb!` / `clear-trace-cbs!` / `trace-buffer`
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
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
  (rf/init! plain-atom/adapter)
  ;; Framework events / fx are registered at namespace-load time in
  ;; routing.cljc; clear-all! wiped them. Re-eval those registrations
  ;; so :rf/url-changed, :rf/url-requested, :rf.route/* etc. resurrect.
  (require 're-frame.routing :reload)
  (test-fn))

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
      (rf/register-trace-cb! ::recorder listener)

      ;; ---- Frame lifecycle: :frame/created, :frame/re-registered ----------
      (rf/reg-frame :test/main {:doc "comprehensive flow frame"})
      ;; Re-register to fire :frame/re-registered.
      (rf/reg-frame :test/main {:doc "comprehensive flow frame (rev 2)"})

      ;; ---- Event handlers --------------------------------------------------
      (rf/reg-event-db :seed (fn [_ _] {:n 0 :items [1 2 3]}))
      (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
      ;; Re-register :inc with a different fn body to fire
      ;; :rf.registry/handler-replaced.
      (rf/reg-event-db :inc  (fn [db _] (update db :n (fnil inc 0))))

      ;; Subs (used to demonstrate the absence of :sub/run / :sub/create
      ;; emit — see rf2-hyxg).
      (rf/reg-sub :n     (fn [db _] (:n db)))
      (rf/reg-sub :n*2   :<- [:n] (fn [n _] (* 2 (or n 0))))

      ;; ---- A user-registered fx fires :event/do-fx wrapping the walk ------
      (let [fx-fired (atom 0)]
        (rf/reg-fx :test/incr (fn [_ _] (swap! fx-fired inc)))
        (rf/reg-event-fx :do-fx-event
          (fn [_ _]
            {:db {:n 99}
             :fx [[:test/incr :go]]}))
        (rf/dispatch-sync [:do-fx-event] {:frame :test/main})
        (is (= 1 @fx-fired) "user fx ran"))

      ;; ---- :rf.fx/override-applied ----------------------------------------
      (rf/reg-fx :prod/sender   (fn [_ _] :prod-fired))
      (rf/reg-fx :stub/sender   (fn [_ _] :stub-fired))
      (rf/reg-event-fx :send
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
      (rf/reg-event-fx :send-broken
        (fn [_ _] {:fx [[:nonexistent/fx :payload]]}))
      (rf/dispatch-sync [:send-broken] {:frame :test/main})

      ;; ---- :rf.error/no-such-handler --------------------------------------
      (rf/dispatch-sync [:no/such/event] {:frame :test/main})

      ;; ---- :rf.error/handler-exception ------------------------------------
      (rf/reg-event-db :throws (fn [_ _] (throw (ex-info "oops" {:bad? true}))))
      (rf/dispatch-sync [:throws] {:frame :test/main})

      ;; ---- :rf.error/fx-handler-exception ---------------------------------
      (rf/reg-fx :throwing-fx (fn [_ _] (throw (ex-info "fx blew" {}))))
      (rf/reg-event-fx :run-throwing-fx
        (fn [_ _] {:fx [[:throwing-fx :ignored]]}))
      (rf/dispatch-sync [:run-throwing-fx] {:frame :test/main})

      ;; ---- :rf.fx/skipped-on-platform (warning) ---------------------------
      (rf/reg-fx :client-only-fx
                 {:platforms #{:client}}
                 (fn [_ _] :nope))
      (rf/reg-event-fx :run-client-fx
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
      (rf/subscribe-once :test/main [:unresolved])

      ;; ---- :rf.error/sub-exception ----------------------------------------
      (rf/reg-sub :throwing-sub (fn [_db _] (throw (ex-info "sub-boom" {}))))
      (rf/subscribe-once :test/main [:throwing-sub])

      ;; ---- :rf.error/dispatch-sync-in-handler -----------------------------
      (rf/reg-event-fx :nested-sync
        (fn [_ _]
          (rf/dispatch-sync [:inc] {:frame :test/main})
          {}))
      (rf/dispatch-sync [:nested-sync] {:frame :test/main})

      ;; ---- :rf.error/frame-destroyed --------------------------------------
      ;; Subscribe against a frame that doesn't exist.
      (rf/subscribe-once :no/such/frame [:n])

      ;; ---- :rf.error/drain-depth-exceeded ---------------------------------
      ;; A handler that re-dispatches itself; the drain bound (default 100)
      ;; trips and emits the structured error.
      (rf/reg-event-fx :loop-forever
        (fn [_ _]
          {:fx [[:dispatch [:loop-forever]]]}))
      (rf/dispatch-sync [:loop-forever] {:frame :test/main})

      ;; ---- Routing: :rf.warning/route-shadowed-by-equal-score -------------
      ;; Two routes with the same structural rank (same path shape, both
      ;; concrete). The second registration sees the first and warns.
      (rf/reg-route :route/a {:path "/foo"})
      (rf/reg-route :route/b {:path "/foo"})

      ;; ---- Routing: :rf.route.nav-token/allocated + :rf.route/url-changed ----
      ;; A reg-route + dispatch [:rf/url-changed url] threads through the
      ;; allocate-token + match-url emit path.
      (rf/reg-route :user/show {:path "/users/:id"})
      (rf/dispatch-sync [:rf/url-changed "/users/42"] {:frame :test/main})
      ;; Now repeat with a fragment change only — emits :rf.route/url-changed
      ;; with prev/next-fragment shape.
      (rf/dispatch-sync [:rf/url-changed "/users/42#section"] {:frame :test/main})

      ;; ---- Routing: :rf.route/navigation-blocked --------------------------
      ;; Set up a :can-leave sub that returns false, then request a URL.
      (rf/reg-sub :always-block (fn [_ _] false))
      (rf/reg-route :nav/blocker {:path "/blockable" :can-leave :always-block})
      ;; Move "into" the blockable route so its :can-leave guards the next nav.
      (rf/dispatch-sync [:rf/url-changed "/blockable"] {:frame :test/main})
      (rf/dispatch-sync [:rf/url-requested {:url "/users/42"}] {:frame :test/main})

      ;; ---- Routing: :rf.route.nav-token/stale-suppressed ---------------------
      ;; Allocate a token by navigating, then dispatch the framework's
      ;; nav-token-checking event with a deliberately mismatched token.
      (rf/dispatch-sync [:rf/url-changed "/users/7"] {:frame :test/main})
      (rf/dispatch-sync [:rf.test/simulate-http-resolution
                         {:carried-nav-token :stale/token
                          :on-success-event  [:noop]}]
                        {:frame :test/main})

      ;; ---- Machine: :rf.machine/transition + :rf.machine.timer/scheduled --
      ;; Register a machine where the destination state of the first
      ;; transition declares an :after — entering that state schedules
      ;; the timer, which is what we want to trace.
      (let [m {:id      :tl
               :initial :red
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
        (rf/reg-event-db :machine/init
          (fn [db _]
            (assoc-in db [:rf/machines :machine/tl]
                      {:state :red :data {}})))
        (rf/dispatch-sync [:machine/init] {:frame :test/main})
        (rf/dispatch-sync [:machine/tl [:tick]] {:frame :test/main})
        (rf/dispatch-sync [:machine/tl [:tick]] {:frame :test/main}))

      ;; ---- Frame destruction: :frame/destroyed ----------------------------
      (rf/destroy-frame! :test/main)

      (rf/remove-trace-cb! ::recorder)

      (let [events @recorded
            seen   (ops-of events)]

        (testing "every captured event satisfies the universal envelope shape"
          (is (every? valid-envelope? events)
              (str "non-conformant envelopes seen — first 3: "
                   (vec (take 3 (remove valid-envelope? events))))))

        ;; ---- :event op-type ------------------------------------------------
        (testing ":event :event (run-start / run-end phase)"
          (let [run-starts (filter #(and (= :event   (:op-type %))
                                         (= :event   (:operation %))
                                         (= :run-start (:phase (:tags %))))
                                   events)
                run-ends   (filter #(and (= :event (:op-type %))
                                         (= :event (:operation %))
                                         (= :run-end (:phase (:tags %))))
                                   events)]
            (is (seq run-starts) ":event run-start fires for each handler invocation")
            (is (seq run-ends)   ":event run-end fires for each handler invocation")
            ;; Tag shape: the first run-start carries :event-id, :event, :frame.
            (let [t (:tags (first run-starts))]
              (is (keyword? (:event-id t)))
              (is (vector?  (:event t)))
              (is (keyword? (:frame t))))))

        (testing ":event :event/db-changed fires when a handler returns :db"
          (is (has-op? events :event :event/db-changed)
              "expected :event :event/db-changed at least once")
          (let [t (:tags (find-op events :event :event/db-changed))]
            (is (keyword? (:event-id t)))
            (is (vector?  (:event t)))
            (is (keyword? (:frame t)))))

        (testing ":event :event/do-fx wraps the fx walk on every dispatch"
          (is (has-op? events :event :event/do-fx)
              "expected :event :event/do-fx at least once")
          (is (keyword? (:frame (:tags (find-op events :event :event/do-fx))))))

        ;; ---- :fx op-type ---------------------------------------------------
        (testing ":fx :rf.fx/override-applied fires under :fx-overrides"
          (is (has-op? events :fx :rf.fx/override-applied)
              "expected :fx :rf.fx/override-applied")
          (let [t (:tags (find-op events :fx :rf.fx/override-applied))]
            (is (= :prod/sender (:from t)))
            (is (= :stub/sender (:to t)))))

        ;; ---- :warning op-type ----------------------------------------------
        (testing ":warning :rf.fx/skipped-on-platform fires when an fx's :platforms excludes the active platform"
          (is (has-op? events :warning :rf.fx/skipped-on-platform)
              "expected :warning :rf.fx/skipped-on-platform")
          (let [t (:tags (find-op events :warning :rf.fx/skipped-on-platform))]
            (is (= :client-only-fx (:fx-id t)))
            (is (= #{:client}      (:registered-platforms t)))
            (is (set? (:registered-platforms t)))))

        (testing ":warning :rf.warning/route-shadowed-by-equal-score fires on equal-rank route registration"
          (is (has-op? events :warning :rf.warning/route-shadowed-by-equal-score)
              "expected :warning :rf.warning/route-shadowed-by-equal-score")
          (let [t (:tags (find-op events :warning :rf.warning/route-shadowed-by-equal-score))]
            (is (keyword? (:route-id t)))
            (is (keyword? (:shadowed t)))))

        ;; ---- :frame op-type ------------------------------------------------
        (testing ":frame :frame/created fires on first reg-frame for an id"
          (is (has-op? events :frame :frame/created)
              "expected :frame :frame/created")
          (let [t (:tags (find-op events :frame :frame/created))]
            (is (keyword? (:frame t)))
            (is (map?     (:config t)))))

        (testing ":frame :frame/re-registered fires on subsequent reg-frame for the same id"
          (is (has-op? events :frame :frame/re-registered)
              "expected :frame :frame/re-registered")
          (is (keyword? (:frame (:tags (find-op events :frame :frame/re-registered))))))

        (testing ":frame :frame/destroyed fires on destroy-frame!"
          (is (has-op? events :frame :frame/destroyed)
              "expected :frame :frame/destroyed")
          (is (keyword? (:frame (:tags (find-op events :frame :frame/destroyed))))))

        ;; ---- :registry op-type ---------------------------------------------
        (testing ":registry :rf.registry/handler-replaced fires on EVERY re-registration (rf2-6w7zn)"
          ;; Per Spec 001 §Hot-reload trace surface the emit is
          ;; unconditional on re-registration — the prior `different-fn?`
          ;; gate dropped events for kinds like `:frame` whose slot
          ;; replacement need not rotate `:handler-fn`. Tools branch on
          ;; the `:different-fn?` tag (preserved below) to suppress
          ;; idempotent reload noise on their side.
          (is (has-op? events :registry :rf.registry/handler-replaced)
              "expected :registry :rf.registry/handler-replaced")
          ;; The flow re-registers BOTH `:test/main` (a frame, same
          ;; handler-fn) and `:inc` (an event, different fn body) so
          ;; both events fire. Find the `:inc` event explicitly so the
          ;; `:different-fn?` assertion targets the real fn-change case.
          (let [different-events (filterv (fn [ev]
                                            (and (= :registry (:op-type ev))
                                                 (= :rf.registry/handler-replaced
                                                    (:operation ev))
                                                 (true? (get-in ev [:tags :different-fn?]))))
                                          events)
                idempotent-events (filterv (fn [ev]
                                             (and (= :registry (:op-type ev))
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

        ;; ---- :machine op-type ----------------------------------------------
        (testing ":machine :rf.machine/transition fires on a machine event"
          (is (has-op? events :machine :rf.machine/transition)
              "expected :machine :rf.machine/transition")
          (let [t (:tags (find-op events :machine :rf.machine/transition))]
            (is (keyword? (:machine-id t)))
            (is (vector?  (:event t)))
            (is (map?     (:before t)))
            (is (map?     (:after t)))))

        (testing ":machine :rf.machine.timer/scheduled fires when a state with :after is entered"
          (is (has-op? events :machine :rf.machine.timer/scheduled)
              "expected :machine :rf.machine.timer/scheduled")
          (let [t (:tags (find-op events :machine :rf.machine.timer/scheduled))]
            (is (keyword? (:state t)))
            (is (number?  (:delay t)))))

        ;; ---- routing :event ops --------------------------------------------
        (testing ":event :rf.route.nav-token/allocated fires on :rf/url-changed full nav"
          (is (has-op? events :event :rf.route.nav-token/allocated)
              "expected :event :rf.route.nav-token/allocated")
          (let [t (:tags (find-op events :event :rf.route.nav-token/allocated))]
            (is (keyword? (:route-id t)))
            (is (some?    (:nav-token t)))))

        (testing ":event :rf.route/url-changed fires on fragment-only navigation"
          ;; Per Spec 009 §:op-type vocabulary and Spec 012 §Fragments:
          ;; :rf.route/url-changed is the canonical op-name for fragment-only
          ;; navigation. Consumers discriminate full vs fragment-only by :tags.
          (is (has-op? events :event :rf.route/url-changed)
              "expected :event :rf.route/url-changed")
          (let [t (:tags (find-op events :event :rf.route/url-changed))]
            (is (keyword? (:route-id t)))
            (is (string?  (:next-fragment t)))))

        (testing ":event :rf.route/navigation-blocked fires when :can-leave returns false"
          (is (has-op? events :event :rf.route/navigation-blocked)
              "expected :event :rf.route/navigation-blocked")
          (let [t (:tags (find-op events :event :rf.route/navigation-blocked))]
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
            (is (= :throwing-fx (:fx-id t)))
            (is (string? (:exception-message t)))))

        (testing ":error :rf.error/no-such-fx"
          (is (has-op? events :error :rf.error/no-such-fx)
              "expected :error :rf.error/no-such-fx")
          (is (= :nonexistent/fx
                 (:fx-id (:tags (find-op events :error :rf.error/no-such-fx))))))

        (testing ":error :rf.error/no-such-handler"
          (is (has-op? events :error :rf.error/no-such-handler)
              "expected :error :rf.error/no-such-handler")
          (let [t (:tags (find-op events :error :rf.error/no-such-handler))]
            (is (= :no/such/event (:event-id t)))
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
            (gap-check :sub/run                       :sub/run)
            (gap-check :sub/create                    :sub/create)
            (gap-check :rf.machine.lifecycle/created  :rf.machine.lifecycle/created)
            (gap-check :rf.machine.lifecycle/destroyed :rf.machine.lifecycle/destroyed)
            (gap-check :rf.machine/event-received     :rf.machine/event-received)
            (gap-check :rf.machine/snapshot-updated   :rf.machine/snapshot-updated)
            (gap-check :registry :rf.registry/handler-registered)
            (gap-check :registry :rf.registry/handler-cleared)))

        (testing "diagnostic: every (op-type, operation) pair the flow produced"
          ;; Always passes; printing only when test verbosity helps.
          (is (vector? seen)
              (str "captured pairs: " (pr-str seen))))))))

;; ---- listener API: lifecycle and isolation --------------------------------

(deftest trace-listener-lifecycle
  (testing "register-trace-cb! is keyed; same-id re-registration replaces; remove-trace-cb! removes only that id"
    (let [a-events (atom [])
          b-events (atom [])
          c-events (atom [])]
      (rf/register-trace-cb! ::a (fn [ev] (swap! a-events conj ev)))
      (rf/register-trace-cb! ::b (fn [ev] (swap! b-events conj ev)))
      (rf/register-trace-cb! ::c (fn [ev] (swap! c-events conj ev)))

      (rf/reg-event-db :ping (fn [db _] (assoc db :ping? true)))
      (rf/dispatch-sync [:ping])
      (let [a1 (count @a-events)
            b1 (count @b-events)
            c1 (count @c-events)]
        (is (pos? a1) "listener a received events")
        (is (= a1 b1) "listeners receive the same events")
        (is (= a1 c1)))

      ;; Remove ::b; ::a and ::c continue.
      (rf/remove-trace-cb! ::b)
      (rf/dispatch-sync [:ping])
      (is (> (count @a-events) (count @b-events))
          "after removal, ::b stops accumulating")
      (is (= (count @a-events) (count @c-events))
          "::a and ::c stay in lock-step")

      ;; Replace ::a with a different fn under the same id.
      (let [a-events-2 (atom [])]
        (rf/register-trace-cb! ::a (fn [ev] (swap! a-events-2 conj ev)))
        (let [a-pre (count @a-events)]
          (rf/dispatch-sync [:ping])
          (is (= a-pre (count @a-events))
              "the original a-events atom no longer accumulates after re-register under same id"))
        (is (pos? (count @a-events-2))
            "the replacement listener under ::a accumulates"))

      (rf/remove-trace-cb! ::a)
      (rf/remove-trace-cb! ::c))))

(deftest trace-listener-exception-isolation
  (testing "a listener that throws does not crash the dispatch flow and does not block other listeners"
    (let [survivor-events (atom [])
          throw-count     (atom 0)]
      (rf/register-trace-cb! ::throwing
        (fn [_ev]
          (swap! throw-count inc)
          (throw (ex-info "tool blew up" {:listener ::throwing}))))
      (rf/register-trace-cb! ::survivor
        (fn [ev] (swap! survivor-events conj ev)))

      (rf/reg-event-db :init (fn [_ _] {:n 0}))
      (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

      ;; The dispatch flow MUST proceed despite the throwing listener.
      (rf/dispatch-sync [:init])
      (rf/dispatch-sync [:inc])
      (rf/dispatch-sync [:inc])

      (is (= 2 (:n (rf/get-frame-db :rf/default)))
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

      (rf/remove-trace-cb! ::throwing)
      (rf/remove-trace-cb! ::survivor))))

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
    (rf/reg-event-db :rf2-qsjda/internal-bookkeeping
                     {:rf.trace/no-emit? true}
                     (fn [db _] (assoc db :bookkeeping/ran? true)))

    (let [recorded (atom [])]
      (rf/register-trace-cb! ::rec (fn [ev] (swap! recorded conj ev)))

      (rf/dispatch-sync [:rf2-qsjda/internal-bookkeeping])

      ;; Handler ran (db committed) but no traces were emitted.
      (is (true? (:bookkeeping/ran? (rf/get-frame-db :rf/default)))
          "the handler body still ran — :rf.trace/no-emit? opts out
           of TRACE EMISSION, not handler execution")

      ;; Sanity: no trace events for this dispatch at all. We assert
      ;; on event-id / event-vec tags, since the trace stream might
      ;; carry framework-level emits unrelated to our dispatch
      ;; (e.g. registrar registration traces fired by the
      ;; reg-event-db above).
      (let [our-events
            (filter
              (fn [ev]
                (let [tags (:tags ev)
                      eid  (or (:event-id tags)
                               (let [ev-vec (:event tags)]
                                 (when (vector? ev-vec) (first ev-vec))))]
                  (= :rf2-qsjda/internal-bookkeeping eid)))
              @recorded)]
        (is (empty? our-events)
            (str "expected NO trace events for the :rf.trace/no-emit?
                  handler's dispatch, got: "
                 (vec (map (juxt :op-type :operation) our-events)))))

      (rf/remove-trace-cb! ::rec))))

(deftest no-emit-flag-absent-emits-normally
  (testing "Baseline sanity: the SAME dispatch shape WITHOUT
            `:rf.trace/no-emit? true` produces the normal cascade
            traces (`:event/dispatched`, run-start, run-end,
            `:event/db-changed`). Pins the opt-out as the difference."
    (rf/reg-event-db :rf2-qsjda/normal
                     {:doc "without :rf.trace/no-emit?"}
                     (fn [db _] (assoc db :normal/ran? true)))

    (let [recorded (atom [])]
      (rf/register-trace-cb! ::rec (fn [ev] (swap! recorded conj ev)))

      (rf/dispatch-sync [:rf2-qsjda/normal])

      (let [our-events
            (filter
              (fn [ev]
                (let [tags (:tags ev)
                      eid  (or (:event-id tags)
                               (let [ev-vec (:event tags)]
                                 (when (vector? ev-vec) (first ev-vec))))]
                  (= :rf2-qsjda/normal eid)))
              @recorded)
            ops (set (map :operation our-events))]
        (is (contains? ops :event/dispatched)
            ":event/dispatched fired for the un-flagged handler")
        (is (contains? ops :event/db-changed)
            ":event/db-changed fired for the un-flagged handler")
        (is (contains? ops :event)
            ":event (run-start / run-end) fired for the un-flagged handler"))

      (rf/remove-trace-cb! ::rec))))
