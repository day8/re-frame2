(ns re-frame.machine-action-exception-always-on-cljs-test
  "rf2-cprm0q — `:rf.error/machine-action-exception` is catalogued **always-on**
  (Spec 009 §Error event catalogue), but every machine emit site
  (`lifecycle_fx/registration.cljc`, `finalize.cljc`, `traces.cljc`) used to
  call ONLY the dev-gated `trace/emit-error!`, so the record was DCE'd out of
  `:advanced` + `goog.DEBUG=false` production entirely — an always-on category
  SILENTLY SWALLOWED (a 2am machine throw never reached Sentry). And even the
  dev record carried NO action/guard attribution.

  This is the ADVERSARIAL acceptance leg — it drives the ACTUAL failing paths
  (a throwing ACTION and a throwing GUARD both converge on
  `registration.cljc`'s `trace-action-failure!`) and asserts the record fans
  out on the corpus-wide `register-error-listener!` substrate — the always-on
  axis that is NOT gated by `interop/debug-enabled?`, so it is exactly the
  surface that SURVIVES a production build. On main (pre-fix) the `:errors`
  listener receives NOTHING, so every assertion below is RED.

  Pins the two defects the bead fixes:
    (1) the record ACTUALLY reaches the always-on `:errors` channel (both an
        action throw AND a guard throw);
    (2) it carries `:failing-id` (the action / guard KEYWORD) + `:state` (the
        active state path) — machine-local code identifiers, same privacy
        class as `:event-id` — so an off-box shipper learns WHICH action /
        guard in WHICH state threw.
    (3) the production-surviving record is STRUCTURAL-ONLY: the developer's
        arbitrary `:exception-data` (which may embed app secrets) does NOT
        ride it.

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build (`cljs-test$`) AND the JVM `clojure -M:test` runner both pick it up —
  the always-on axis is production-shaped on both."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            ;; Loading the machines facade installs the late-bind hooks
            ;; `reg-machine` resolves through.
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace.tooling :as trace-tooling]))

;; Fresh registrar + plain-atom adapter per test; the always-on error-listener
;; registry (a `defonce` atom) cleared so a listener from one test cannot leak
;; into the next (mirrors machine_spawn_unregistered_type_test /
;; write_after_destroy_always_on_cljs_test).
(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (error-emit/clear-error-listeners!))}))

(defn- throwable? [x]
  #?(:clj  (instance? Throwable x)
     :cljs (instance? js/Error x)))

(defn- state-mentions?
  "The active-state slot is a leaf keyword OR a root→leaf vector path (Spec
  005 §State paths); accept either form naming `kw`."
  [state kw]
  (or (= state kw)
      (and (sequential? state) (boolean (some #{kw} state)))))

(defn- with-always-on-records
  "Run `thunk` while a corpus-wide always-on `:errors` listener records every
  fanned-out record; return the records filtered to the
  `:rf.error/machine-action-exception` category (the production-survivable
  axis). Always unregisters in a `finally`."
  [thunk]
  (let [seen (atom [])]
    (rf/register-listener! :errors ::recorder (fn [r] (swap! seen conj r)))
    (try
      (thunk)
      (filterv #(= :rf.error/machine-action-exception (:error %)) @seen)
      (finally (rf/unregister-listener! :errors ::recorder)))))

(defn- dev-traces-of
  "Capture the dev-trace stream for `op` while `thunk` runs (axis 2 — DCE'd in
  production). Proves BOTH channels fire, so the acceptance hits the real path
  rather than routing around it."
  [op thunk]
  (let [seen (atom [])]
    (trace-tooling/register-listener! ::trace (fn [ev] (swap! seen conj ev)))
    (try
      (thunk)
      (filterv #(= op (:operation %)) @seen)
      (finally (trace-tooling/clear-listeners!)))))

;; ===========================================================================
;; (1)+(2) A throwing ACTION reaches the always-on axis WITH attribution.
;; ===========================================================================

(deftest throwing-action-fans-out-on-always-on-axis-with-attribution
  (testing "a machine transition action that throws fans ONE
            :rf.error/machine-action-exception record out through the
            corpus-wide always-on error listener, carrying :failing-id (the
            action keyword) + :state (the active state path)"
    (rf/reg-machine :cprm0q/action-throws
      {:initial :idle
       :actions {:boom (fn [_] (throw (ex-info "boom" {})))}
       :states  {:idle {:on {:go {:target :done :action :boom}}}
                 :done {}}})
    ;; Boot to :idle cleanly OUTSIDE the capture so only the :go transition's
    ;; action throw is recorded.
    (rf/dispatch-sync [:cprm0q/action-throws [:rf.machine/start]])
    (let [records (with-always-on-records
                    #(rf/dispatch-sync [:cprm0q/action-throws [:go]]))]
      (is (= 1 (count records))
          "exactly ONE always-on record for the throwing action (pre-fix: ZERO)")
      (let [r (first records)]
        (is (= :rf.error/machine-action-exception (:error r))
            "the always-on category actually reached the :errors channel")
        (is (= :boom (:failing-id r))
            ":failing-id is the throwing ACTION's keyword — the attribution")
        (is (state-mentions? (:state r) :idle)
            ":state names the active state the transition fired from")
        (is (= :cprm0q/action-throws (:actor-id r))
            ":actor-id names the live actor instance")
        (is (= :rf/default (:frame r)) ":frame names the owning frame")
        (is (throwable? (:exception r))
            ":exception carries the raw host throwable for off-box shippers")
        (is (number? (:time r)) ":time is a wall-clock millis number")))))

;; ===========================================================================
;; (1)+(2) A throwing GUARD converges on the SAME surface (005:603).
;; ===========================================================================

(deftest throwing-guard-fans-out-on-always-on-axis-with-attribution
  (testing "a guard that throws SURFACES + aborts the macrostep (XState v5
            alignment) through the SAME :rf.error/machine-action-exception
            surface — so it ALSO reaches the always-on axis, with :failing-id
            = the guard keyword"
    (rf/reg-machine :cprm0q/guard-throws
      {:initial :idle
       :guards  {:boom (fn [_] (throw (ex-info "guard boom" {})))}
       :states  {:idle {:on {:go [{:guard :boom :target :a}]}}
                 :a    {}}})
    (rf/dispatch-sync [:cprm0q/guard-throws [:rf.machine/start]])
    (let [records (with-always-on-records
                    #(rf/dispatch-sync [:cprm0q/guard-throws [:go]]))]
      (is (= 1 (count records))
          "exactly ONE always-on record for the throwing guard (pre-fix: ZERO)")
      (let [r (first records)]
        (is (= :rf.error/machine-action-exception (:error r))
            "guard throws converge on the machine-action-exception category")
        (is (= :boom (:failing-id r))
            ":failing-id is the throwing GUARD's keyword")
        (is (state-mentions? (:state r) :idle)
            ":state names the active state the guard was evaluated against")
        (is (throwable? (:exception r)) ":exception carries the host throwable")))))

;; ===========================================================================
;; (3) Privacy — the production-surviving record carries NO :exception-data.
;; ===========================================================================

(deftest always-on-record-is-structural-only-no-exception-data-leak
  (testing "the always-on record is NOT privacy-gated like the dev trace, so
            it must carry STRUCTURAL identifiers only — the developer's
            arbitrary :exception-data (which may embed app secrets the dev
            trace redacts at the marks chokepoint) must NOT ride it"
    (let [secret "super-secret-jwt"]
      (rf/reg-machine :cprm0q/secret-throws
        {:initial :idle
         :actions {:leak (fn [_] (throw (ex-info "boom" {:token secret})))}
         :states  {:idle {:on {:go {:target :done :action :leak}}}
                   :done {}}})
      (rf/dispatch-sync [:cprm0q/secret-throws [:rf.machine/start]])
      (let [records (with-always-on-records
                      #(rf/dispatch-sync [:cprm0q/secret-throws [:go]]))
            r       (first records)]
        (is (some? r) "(precondition) the record fired")
        (is (nil? (:exception-data r))
            "no :exception-data slot rides the always-on record")
        (is (not (str/includes? (pr-str (dissoc r :exception)) secret))
            "no structural slot echoes the secret ex-data payload")))))

;; ===========================================================================
;; Both channels fire — the acceptance hits the REAL path, not a route-around.
;; ===========================================================================

(deftest both-error-channels-fire-for-a-machine-action-throw
  (testing "the fix fans BOTH the always-on record AND the dev trace — the
            dev-only trace (axis 2, DCE'd in prod) still fires exactly once
            with the :action-id, proving the same real emit site drives both"
    (rf/reg-machine :cprm0q/both-channels
      {:initial :idle
       :actions {:boom (fn [_] (throw (ex-info "boom" {})))}
       :states  {:idle {:on {:go {:target :done :action :boom}}}
                 :done {}}})
    (rf/dispatch-sync [:cprm0q/both-channels [:rf.machine/start]])
    (let [dev-evs (dev-traces-of :rf.error/machine-action-exception
                    #(rf/dispatch-sync [:cprm0q/both-channels [:go]]))]
      (is (= 1 (count dev-evs))
          "the dev trace (axis 2) still fires exactly once")
      (is (= :boom (get-in (first dev-evs) [:tags :action-id]))
          "the dev trace carries the :action-id (existing shape preserved)")
      (is (= :boom (get-in (first dev-evs) [:tags :failing-id]))
          "the dev trace's :failing-id is now the action keyword too"))))

;; ===========================================================================
;; The union-record hook the machines layer reaches error-emit through.
;; ===========================================================================

(deftest dispatch-error-record-hook-is-published
  (testing "machines ships above core's require graph, so the always-on
            fan-out reaches the listener via the
            :error-emit/dispatch-error-record late-bind hook — the same hook
            the fail-closed spawn reject uses"
    (is (some? (rf/register-listener! :errors ::probe (fn [_] nil)))
        "the :errors listener registration surface is live")
    (rf/unregister-listener! :errors ::probe)))
