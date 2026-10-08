(ns re-frame.routing-replan-test
  "`[:rf.route/replan-resources {:cause …}]`, the ROUTING half.

  The Resources artefact is not on the routing test classpath, so the
  `:routing/on-route-replan` hook is unbound by default; every planning
  assertion here drives the event against a STUB hook that records what it was
  handed and returns a plan of the documented shape. That is exactly the
  ownership seam: routing owns the request gate, the slice read, the branch
  walk, the unconditional slot replacement and the readiness re-projection;
  Resources owns the plan. The Resources-side semantics (the same-owner subset
  release, the caller cause on every ensure, the fail-closed whole-owner
  release) are proven in `re-frame.resources-route-replan-cljs-test` (both
  hosts) and the RealWorld acceptance test; the end-to-end row is the
  `ep-0037-replan-*` conformance fixtures.

  ## Posture split

  The request GATE (`rf.routing.replan/replan-request-error`), the hook consultation
  (`@calls` — a late-bound fn, not a trace), the slice / slot writes and the
  captured nav fxs are all production-real and carry no posture guard. What is
  dev-only is the REPORTING: the `:rf.error/replan-bad-request` rejection
  reaches the caller through `trace/emit-error!`, gated on
  `rf.interop/debug-enabled?`; those assertions sit inside
  `(when rf.interop/debug-enabled? …)` arms."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing.replan :as rf.routing.replan]
            [re-frame.routing-test-support :as rf.routing-test-support]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db []
  (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- slice []
  (get-in (runtime-db) [:rf.runtime/routing :current]))

(defn- plan-slot [token]
  (get-in (runtime-db) [:rf.runtime/routing :resource-plan token]))

(defn- blocking-slot [token]
  (get-in (runtime-db) [:rf.runtime/routing :resource-blocking token]))

(defn- reg-nav-fxs-capturing!
  "Register the routing history / scroll fxs (`:platforms #{:server :client}`
  so they route on the JVM) with capturing handlers. Returns the atoms."
  []
  (let [pushed (atom []) replaced (atom []) scrolled (atom [])]
    (rf.fx/reg-fx :rf.nav/push-url       {:platforms #{:server :client}} (fn [_ url]  (swap! pushed conj url)))
    (rf.fx/reg-fx :rf.nav/replace-url    {:platforms #{:server :client}} (fn [_ url]  (swap! replaced conj url)))
    (rf.fx/reg-fx :rf.nav/scroll         {:platforms #{:server :client}} (fn [_ args] (swap! scrolled conj args)))
    (rf.fx/reg-fx :rf.nav/capture-scroll {:platforms #{:server :client}} (fn [_ _] nil))
    {:push pushed :replace replaced :scroll scrolled}))

(defn- with-replan-hook
  "Publish a stub `:routing/on-route-replan` that RECORDS every call and returns
  `(plan-fn call)`, run `(f calls)`, then unpublish it."
  [plan-fn f]
  (let [calls (atom [])]
    (rf.late-bind/set-fn! :routing/on-route-replan
                       (fn [entry]
                         (swap! calls conj entry)
                         (plan-fn entry)))
    (try (f calls)
         (finally (rf.late-bind/set-fn! :routing/on-route-replan nil)))))

(defn- replan!
  "Dispatch `event-vec` synchronously and return the
  `:rf.error/replan-bad-request` rejections it emitted (dev arm)."
  [event-vec]
  (with-trace-recorder! [traces {:pred #(= :rf.error/replan-bad-request (:operation %))}]
    (rf/dispatch-sync event-vec)
    @traces))

(def ^:private k1 "k1")
(def ^:private k2 "k2")
(def ^:private id1 [:rf.scope/global :t/viewer {}])
(def ^:private id2 [:rf.scope/global :t/docs {:page "routing"}])

;; ---- the request gate -----------------------------------------------------

(deftest replan-request-error-is-total-over-the-closed-payload
  ;; Structure before content: arity, then map-ness, then the closed roster,
  ;; then a present non-nil :cause (presence, not truthiness).
  (are [event-vec expected] (= expected (rf.routing.replan/replan-request-error event-vec))
    [:rf.route/replan-resources {:cause [:session-restore]}]        nil
    [:rf.route/replan-resources {:cause false}]                     nil
    [:rf.route/replan-resources]                                    {:reason :bad-event-arity :keys []}
    [:rf.route/replan-resources {:cause [:x]} :extra]               {:reason :bad-event-arity :keys []}
    [:rf.route/replan-resources "session-restore"]                  {:reason :not-a-map :keys []}
    [:rf.route/replan-resources nil]                                {:reason :not-a-map :keys []}
    [:rf.route/replan-resources {:b 1 :a 2 :cause [:x]}]            {:reason :unknown-key :keys [:a :b]}
    [:rf.route/replan-resources {}]                                 {:reason :missing-cause :keys [:cause]}
    [:rf.route/replan-resources {:cause nil}]                       {:reason :missing-cause :keys [:cause]}))

;; ---- the handler: rejections before planning ------------------------------

(deftest replan-rejects-before-planning-and-leaves-the-slice-untouched
  (rf/reg-route :route/docs {} "/docs/:page")
  (with-replan-hook
    (fn [_] {:fx [] :blocking {} :identities {}})
    (fn [calls]
      (testing "NO active route slice → :no-active-route; the hook is never consulted"
        (let [rejected (replan! [:rf.route/replan-resources {:cause [:boot]}])]
          (is (empty? @calls) "planning never ran")
          (is (nil? (slice)) "no slice was minted")
          (when rf.interop/debug-enabled?
            (is (= [[{:reason :no-active-route :keys [] :where :event :frame :rf/default} :no-recovery]]
                   (mapv (juxt #(select-keys (:tags %) [:reason :keys :where :frame]) :recovery)
                         rejected))))))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"}}])
      (let [before (slice)]
        (testing "a malformed request consults no hook and leaves the committed slice
                  byte-for-byte unchanged"
          (rf/dispatch-sync [:rf.route/replan-resources {:cause [:x] :reload? true}])
          (is (empty? @calls) "the hook was never consulted")
          (is (= before (slice)) "the slice is untouched"))))))

;; ---- the handler: the hook contract + the unconditional slot writes -------

(deftest replan-consults-the-hook-with-the-current-slice-and-replaces-the-slots
  (rf/reg-event :docs/load (fn [{:keys [db]} _] {:db (update db :loads (fnil inc 0))}))
  (rf/reg-event :replan/ensured (fn [{:keys [db]} [_ cause]] {:db (update db :ensured (fnil conj []) cause)}))
  (rf/reg-route :route/shell {} "/")
  (rf/reg-route :route/docs {:parent :route/shell :on-match [[:docs/load]]} "/docs/:page")
  (let [fxs (reg-nav-fxs-capturing!)]
    (with-replan-hook
      (fn [{:keys [cause]}]
        {:fx         [[:dispatch [:replan/ensured cause]]]
         :blocking   {k1 id1}
         :identities {k1 id1 k2 id2}})
      (fn [calls]
        (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"}
                                               :query {:tab "a"} :fragment "top"}])
        (let [before (slice)
              token  (:nav-token before)
              loads-before (:loads (rf/app-db-value :rf/default))]
          (reset! (:push fxs) []) (reset! (:replace fxs) []) (reset! (:scroll fxs) [])
          (with-trace-recorder! [traces {:pred  #(contains? #{:rf.route.nav-token/allocated
                                                              :rf.route/activated
                                                              :rf.route/deactivated
                                                              :rf.route/planned
                                                              :rf.route/fragment-changed}
                                                            (:operation %))}]
            (rf/dispatch-sync [:rf.route/replan-resources {:cause [:session-restore]}])
            (testing "the hook is consulted ONCE with the CURRENT slice, ctx {}, the
                      registered branch, the token's (absent) prior plan and the cause"
              (is (= 1 (count @calls)))
              (let [entry (first @calls)]
                ;; `:route/docs` declares no query vocabulary, so `tab` stays a
                ;; string key.
                (is (= {:route-id        :route/docs
                        :params          {:page "routing"}
                        :query           {"tab" "a"}
                        :fragment        "top"
                        :nav-token       token
                        :ctx             {}
                        :cause           [:session-restore]
                        :prev-identities nil
                        :branch-error    nil}
                       (select-keys entry [:route-id :params :query :fragment :nav-token
                                           :ctx :cause :prev-identities :branch-error])))
                (is (= [:route/shell :route/docs] (mapv :route-id (:branch entry)))
                    "the REGISTERED parent-to-leaf branch, resolved by routing's own walk")
                (is (map? (:runtime-db entry)) "the pre-commit runtime-db is threaded")
                (is (contains? entry :app-db) "the current app-db is threaded")))
            (testing "the slots are written and readiness is re-projected from the plan"
              (is (= {k1 id1 k2 id2} (plan-slot token)))
              (is (= {k1 id1} (blocking-slot token)))
              (is (= :loading (:transition (slice))) "a pending blocking requirement → :loading")
              (is (nil? (:error (slice)))))
            (testing "the address is byte-for-byte preserved and NOTHING else moved"
              (is (= (dissoc before :transition :error)
                     (dissoc (slice) :transition :error))
                  "route-id / params / query / fragment / nav-token unchanged")
              (is (= [[:session-restore]] (:ensured (rf/app-db-value :rf/default)))
                  "the plan's fx were spliced into the returned fx")
              (is (= loads-before (:loads (rf/app-db-value :rf/default)))
                  "no :on-match re-fired")
              (is (every? empty? (map deref (vals fxs))) "no push, replace or scroll")
              (when rf.interop/debug-enabled?
                (is (empty? @traces)
                    "no nav-token allocation, no activation pair, no planned projection, no fragment trace"))))
          (testing "UNCONDITIONAL replacement: an EMPTY next plan under the SAME token
                    removes both slots — never leaves them holding the prior value"
            (rf.late-bind/set-fn! :routing/on-route-replan
                               (fn [entry] (swap! calls conj entry) {:fx [] :blocking {} :identities {}}))
            (rf/dispatch-sync [:rf.route/replan-resources {:cause [:tenant-switch]}])
            (is (= {k1 id1 k2 id2} (:prev-identities (last @calls)))
                "the second replan sees the FIRST replan's identities as its previous membership")
            (is (nil? (blocking-slot token)) "the blocking slot is REMOVED")
            (is (not (contains? (get-in (runtime-db) [:rf.runtime/routing :resource-plan]) token))
                "the plan slot is REMOVED, not left holding {k1 k2}")
            (is (= :idle (:transition (slice))) "nothing blocking → :idle")
            (is (nil? (:error (slice)))))
          (testing "a FAILED plan installs the error, projects :error, and clears both slots"
            ;; seed a slot first so the clear is observable
            (rf.late-bind/set-fn! :routing/on-route-replan
                               (fn [entry] (swap! calls conj entry) {:fx [] :blocking {k1 id1} :identities {k1 id1}}))
            (rf/dispatch-sync [:rf.route/replan-resources {:cause [:seed]}])
            (is (= {k1 id1} (plan-slot token)))
            (rf.late-bind/set-fn! :routing/on-route-replan
                               (fn [entry]
                                 (swap! calls conj entry)
                                 {:fx         [[:dispatch [:replan/ensured :released-whole-owner]]]
                                  :blocking   {}
                                  :identities {}
                                  :plan-error {:rf.error/id :rf.error/resource-route-plan
                                               :route-id    :route/docs
                                               :nav-token   token
                                               :plan-cause  :replan
                                               :reason      "scope resolved nil"}}))
            (rf/dispatch-sync [:rf.route/replan-resources {:cause [:broken]}])
            (is (= :error (:transition (slice))))
            (is (= {:rf.error/id :rf.error/resource-route-plan :nav-token token}
                   (select-keys (:error (slice)) [:rf.error/id :nav-token]))
                "the failure names the token that is staying")
            (is (nil? (plan-slot token)) "both slots cleared on a committed failed replan")
            (is (nil? (blocking-slot token)))
            (is (= token (:nav-token (slice))) "…and the token itself is still the same")
            (is (= :released-whole-owner (last (:ensured (rf/app-db-value :rf/default))))
                "the plan's release fx still rides")
            (testing "…and a later SUCCESSFUL replan REPAIRS it (the error is cleared)"
              (rf.late-bind/set-fn! :routing/on-route-replan
                                 (fn [entry] (swap! calls conj entry) {:fx [] :blocking {} :identities {k2 id2}}))
              (rf/dispatch-sync [:rf.route/replan-resources {:cause [:repaired]}])
              (is (nil? (:error (slice))))
              (is (= :idle (:transition (slice))))
              (is (= {k2 id2} (plan-slot token))))))))))

(deftest replan-is-a-noop-when-the-hook-is-unbound-or-returns-nil
  (rf/reg-route :route/docs {} "/docs/:page")
  (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"}}])
  (let [rdb (runtime-db)]
    (testing "no Resources artefact (hook unbound) → {} — the event ships with routing,
              the semantics with Resources"
      (rf/dispatch-sync [:rf.route/replan-resources {:cause [:x]}])
      (is (= rdb (runtime-db))
          "runtime-db untouched — the slice, every slot and readiness alike"))
    (testing "a bound hook that finds nothing to replan (nil) is the same no-op"
      (with-replan-hook
        (fn [_] nil)
        (fn [calls]
          (rf/dispatch-sync [:rf.route/replan-resources {:cause [:x]}])
          (is (= 1 (count @calls)) "the hook WAS consulted")
          (is (= rdb (runtime-db))))))))

(deftest replan-is-frame-scoped
  (testing "a replan dispatched to one frame consults the hook for THAT frame's slice
            only and writes THAT frame's runtime-db"
    (rf/reg-route :route/docs {} "/docs/:page")
    (rf/make-frame {:id :other :doc "a sibling, URL-unbound frame"})
    (with-replan-hook
      (fn [_] {:fx [] :blocking {} :identities {k1 id1}})
      (fn [calls]
        (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"}}])
        (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "other"}}] {:frame :other})
        (let [default-token (:nav-token (slice))
              other-token   (get-in (:rf.db/runtime (rf/frame-state-value :other))
                                    [:rf.runtime/routing :current :nav-token])]
          (rf/dispatch-sync [:rf.route/replan-resources {:cause [:x]}] {:frame :other})
          (is (= [[{:page "other"} other-token]] (mapv (juxt :params :nav-token) @calls))
              "one consultation, with the sibling's slice, not the default's")
          (is (= {k1 id1} (get-in (:rf.db/runtime (rf/frame-state-value :other))
                                  [:rf.runtime/routing :resource-plan other-token])))
          (is (nil? (plan-slot default-token)) "the default frame's slot is untouched"))))))
