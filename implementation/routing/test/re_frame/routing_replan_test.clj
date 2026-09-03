(ns re-frame.routing-replan-test
  "rf2-y8jjk — `[:rf.route/replan-resources {:cause …}]`, the ROUTING half.

  The Resources artefact is not on the routing test classpath, so the
  `:routing/on-route-replan` hook is unbound by default; every planning
  assertion here drives the event against a STUB hook that records what it was
  handed and returns a plan of the documented shape. That is exactly the seam
  the ruling fixes: routing owns the request gate, the slice read, the branch
  walk, the unconditional slot replacement and the readiness re-projection;
  Resources owns the plan. The Resources-side semantics (the same-owner subset
  release, the caller cause on every ensure, the fail-closed whole-owner
  release) are proven in `re-frame.resources-route-replan-cljs-test` (both
  hosts) and the RealWorld acceptance test; the end-to-end row is the
  `ep-0037-replan-*` conformance fixtures.

  ## Posture split (rf2-o5dbf)

  The request GATE (`rf.routing.replan/replan-request-error`), the hook consultation
  (`@calls` — a late-bound fn, not a trace), the slice / slot writes and the
  captured nav fxs are all production-real and carry no posture guard. What is
  dev-only is the REPORTING: the `:rf.error/replan-bad-request` rejection
  reaches the caller through `trace/emit-error!`, gated on
  `rf.interop/debug-enabled?`; those assertions sit inside
  `(when rf.interop/debug-enabled? …)` arms."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
  (testing "a two-element vector carrying a map with a non-nil :cause passes"
    (is (nil? (rf.routing.replan/replan-request-error [:rf.route/replan-resources {:cause [:session-restore]}])))
    (is (nil? (rf.routing.replan/replan-request-error [:rf.route/replan-resources {:cause :tenant-switch}])))
    (is (nil? (rf.routing.replan/replan-request-error [:rf.route/replan-resources {:cause false}]))
        "any non-nil edn is a cause — the gate checks presence, not truthiness"))
  (testing "the event-VECTOR shape gate runs first"
    (is (= {:reason :bad-event-arity :keys []}
           (rf.routing.replan/replan-request-error [:rf.route/replan-resources])))
    (is (= {:reason :bad-event-arity :keys []}
           (rf.routing.replan/replan-request-error [:rf.route/replan-resources {:cause [:x]} :extra])))
    (is (= {:reason :not-a-map :keys []}
           (rf.routing.replan/replan-request-error [:rf.route/replan-resources "session-restore"])))
    (is (= {:reason :not-a-map :keys []}
           (rf.routing.replan/replan-request-error [:rf.route/replan-resources nil]))))
  (testing "the payload is a CLOSED map over #{:cause} — structure before content"
    (is (= {:reason :unknown-key :keys [:force?]}
           (rf.routing.replan/replan-request-error [:rf.route/replan-resources {:cause [:x] :force? true}])))
    (is (= {:reason :unknown-key :keys [:a :b]}
           (rf.routing.replan/replan-request-error [:rf.route/replan-resources {:b 1 :a 2 :cause [:x]}]))
        "offending keys ride in total canonical order")
    (is (= {:reason :unknown-key :keys [:to]}
           (rf.routing.replan/replan-request-error [:rf.route/replan-resources {:to :route/docs}]))
        "a route address is NOT a replan payload — the command replans the ACTIVE route"))
  (testing ":cause is REQUIRED and non-nil — silently defaulting the one field the
            command exists to carry would defeat it"
    (is (= {:reason :missing-cause :keys [:cause]}
           (rf.routing.replan/replan-request-error [:rf.route/replan-resources {}])))
    (is (= {:reason :missing-cause :keys [:cause]}
           (rf.routing.replan/replan-request-error [:rf.route/replan-resources {:cause nil}])))))

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
            (is (= 1 (count rejected)))
            (let [tags (:tags (first rejected))]
              (is (= :no-active-route (:reason tags)))
              (is (= [] (:keys tags)))
              (is (= :event (:where tags)))
              (is (= :rf/default (:frame tags)) "frame-attributed")
              (is (= :no-recovery (:recovery (first rejected))))))))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"}}])
      (let [before (slice)]
        (is (= :route/docs (:route-id before)))
        (reset! calls [])
        (testing "every malformed shape rejects with its own :reason, consults no hook,
                  and leaves the committed slice byte-for-byte unchanged"
          (doseq [[event-vec reason keys*]
                  [[[:rf.route/replan-resources]                              :bad-event-arity []]
                   [[:rf.route/replan-resources {:cause [:x]} :extra]         :bad-event-arity []]
                   [[:rf.route/replan-resources "restore"]                    :not-a-map       []]
                   [[:rf.route/replan-resources {:cause [:x] :reload? true}]  :unknown-key     [:reload?]]
                   [[:rf.route/replan-resources {}]                           :missing-cause   [:cause]]
                   [[:rf.route/replan-resources {:cause nil}]                 :missing-cause   [:cause]]]]
            (let [rejected (replan! event-vec)]
              (is (empty? @calls) (str (pr-str event-vec) " — the hook was never consulted"))
              (is (= before (slice)) (str (pr-str event-vec) " — the slice is untouched"))
              (when rf.interop/debug-enabled?
                (is (= 1 (count rejected)) (str (pr-str event-vec) " — one rejection"))
                (is (= reason (:reason (:tags (first rejected)))))
                (is (= keys* (:keys (:tags (first rejected)))))))))
        (testing "the request gate wins over the slice gate: a malformed event on a
                  frame with a route is reported as malformed, not as no-active-route"
          (let [rejected (replan! [:rf.route/replan-resources {}])]
            (when rf.interop/debug-enabled?
              (is (= :missing-cause (:reason (:tags (first rejected))))))))))))

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
          (is (some? token))
          (is (nil? (plan-slot token)) "no Resources on this classpath — the activation wrote no plan")
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
                (is (= :route/docs (:route-id entry)))
                (is (= {:page "routing"} (:params entry)))
                (is (= {:tab "a"} (:query entry)))
                (is (= "top" (:fragment entry)))
                (is (= token (:nav-token entry)) "the UNCHANGED token — no allocation")
                (is (= {} (:ctx entry)) "the reserved entry ctx, never nil")
                (is (= [:session-restore] (:cause entry)) "the caller cause, verbatim")
                (is (nil? (:prev-identities entry)))
                (is (= [:route/shell :route/docs] (mapv :route-id (:branch entry)))
                    "the REGISTERED parent-to-leaf branch, resolved by routing's own walk")
                (is (nil? (:branch-error entry)))
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
              (is (empty? @(:push fxs)) "no push")
              (is (empty? @(:replace fxs)) "no replace")
              (is (empty? @(:scroll fxs)) "no scroll")
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
            (is (nil? (plan-slot token)) "the plan slot is REMOVED, not left holding {k1 k2}")
            (is (nil? (blocking-slot token)) "the blocking slot is REMOVED")
            (is (not (contains? (get-in (runtime-db) [:rf.runtime/routing :resource-plan]) token)))
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
            (is (= :rf.error/resource-route-plan (:rf.error/id (:error (slice)))))
            (is (= token (:nav-token (:error (slice)))) "the failure names the token that is staying")
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
  (let [before (slice)
        rdb    (runtime-db)]
    (testing "no Resources artefact (hook unbound) → {} — the event ships with routing,
              the semantics with Resources"
      (is (nil? (rf.late-bind/get-fn :routing/on-route-replan)) "the routing suite carries no Resources")
      (let [rejected (replan! [:rf.route/replan-resources {:cause [:x]}])]
        (is (= before (slice)) "slice untouched")
        (is (= rdb (runtime-db)) "runtime-db untouched — no slot, no readiness write")
        (when rf.interop/debug-enabled?
          (is (empty? rejected) "a well-formed request on a live route is NOT a bad request"))))
    (testing "a bound hook that finds nothing to replan (nil) is the same no-op"
      (with-replan-hook
        (fn [_] nil)
        (fn [calls]
          (rf/dispatch-sync [:rf.route/replan-resources {:cause [:x]}])
          (is (= 1 (count @calls)) "the hook WAS consulted")
          (is (= before (slice)))
          (is (= rdb (runtime-db))))))))

(deftest replan-keeps-the-fragment-only-law-and-mirrors-it
  (testing "rf2-k4exp1's mirror: a fragment-only navigation never consults the replan
            hook, and a replan after it keeps the fragment and the token it left"
    (rf/reg-route :route/docs {} "/docs/:page")
    (reg-nav-fxs-capturing!)
    (with-replan-hook
      (fn [_] {:fx [] :blocking {} :identities {k1 id1}})
      (fn [calls]
        (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "a"}])
        (let [token (:nav-token (slice))]
          (rf/dispatch-sync [:rf.route/navigate {:to :route/docs :params {:page "routing"} :fragment "b"}])
          (is (empty? @calls) "the fragment-only door does not replan resources")
          (is (= token (:nav-token (slice))) "…and mints no token")
          (is (= "b" (:fragment (slice))))
          (rf/dispatch-sync [:rf.route/replan-resources {:cause [:after-fragment]}])
          (is (= 1 (count @calls)) "an explicit replan is the separate, named contract")
          (is (= "b" (:fragment (first @calls))) "…over the CURRENT fragment")
          (is (= token (:nav-token (slice))) "…under the same token")
          (is (= "b" (:fragment (slice))) "…leaving the fragment where it was"))))))

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
          (is (= 1 (count @calls)))
          (is (= {:page "other"} (:params (first @calls))) "the sibling's slice, not the default's")
          (is (= other-token (:nav-token (first @calls))))
          (is (= {k1 id1} (get-in (:rf.db/runtime (rf/frame-state-value :other))
                                  [:rf.runtime/routing :resource-plan other-token])))
          (is (nil? (plan-slot default-token)) "the default frame's slot is untouched"))))))
