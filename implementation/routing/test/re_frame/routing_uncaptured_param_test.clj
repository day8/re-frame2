(ns re-frame.routing-uncaptured-param-test
  "A `:params` key the route PATTERN does not capture.

  Route `/probe/:id`, address `{:to :route/probe :params {:id \"7\" :extra \"x\"}}`.
  `:extra` names no `:name` / `*name` segment, so `route-url` cannot put it in
  the URL and `match-url` cannot read it back. Accepted, that address would
  MEAN different things at the two ACTIVATION doors:

      [:rf.route/navigate {:to :route/probe :params {:id \"7\" :extra \"x\"}}]
        would commit  :params {:id \"7\" :extra \"x\"}
      a route-link CLICK -> [:rf.route/url-requested {:url \"/probe/7\" …}]
        would commit  :params {:id \"7\"}        (it resolves through the URL)
      [:rf.route/prefetch …] would warm {:id \"7\" :extra \"x\"}

  So hovering a link would warm one resource identity and clicking that SAME
  link would activate another. The hazard is the door disagreement, not prefetch:
  Spec 012 §The one planning pipeline says doors differ in cause and history /
  scroll policy, NOT in target.

  THE RULE: neither value. An uncaptured path param is REJECTED
  LOUD at `route-url`, the one registry-aware emission boundary all three
  named-address doors share. Spec 012 §Validity rules rule 2
  states the principle in as many words — letting address keys \"ride beside it
  and be silently ignored is the exact failure class this grammar exists to
  kill\" — and `route-url` fails CLOSED on emission for every other
  input that breaks the `route-url` / `match-url` prism: the empty-string
  segment (`\"\"` cannot round-trip through trailing-slash normalisation) and
  the sequential-optional-group prefix rule (a later group emitted after an
  earlier one elided lands in the wrong capture slot). An uncaptured param is
  the same class.

  Truncating instead (committing `{:id \"7\"}` from every door) would be
  silent but total, and would lose the typo an optional group can hide:
  `/docs{/:section}?` with `{:sction \"x\"}` elides the group, builds `/docs`,
  and throws nothing — so `route-url`'s own `:rf.error/missing-route-param` can
  never catch that misspelling. Rejecting does.

  Covers the emission boundary, both activation doors and prefetch.

  ## Posture split

  The REJECTION is production-real and carries no posture guard. `route-url`
  THROWS (so the emission-boundary and link-door cases are
  posture-independent), the programmatic door leaves the slice untouched and
  pushes no history entry, and prefetch never consults the warm hook. Those
  run in the ordinary `clojure -M:test` suite AND in
  `scripts/test-routing-prod-gate.sh` (the `-Dre-frame.debug=false` lane).

  What is dev-only is how the rejection is ANNOUNCED on the two EVENT doors:
  `:rf.error/schema-validation-failure` and `:rf.error/prefetch-bad-address`
  reach the caller through `trace/emit-error!`, gated on
  `rf.interop/debug-enabled?` and read once at load time. Those assertions sit
  inside `(when rf.interop/debug-enabled? …)` arms."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.link :as rf.routing.link]
            [re-frame.routing.registry :as rf.routing.registry]
            [re-frame.routing-test-support :as rf.routing-test-support]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

;; ---- fixtures --------------------------------------------------------------

(defn- register-routes! []
  (rf.routing/reg-route :route/elsewhere {} "/elsewhere")
  (rf.routing/reg-route :route/probe     {} "/probe/:id")
  (rf.routing/reg-route :route/plain     {} "/plain"))

(defn- current-slice []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          [:rf.runtime/routing :current]))

(defn- thrown-data
  "`(ex-data ...)` of the throw `f` raises, or nil when it returns normally."
  [f]
  (try (f) nil
       (catch Throwable ex (ex-data ex))))

;; ===========================================================================
;; The emission boundary — route-url
;; ===========================================================================

(deftest route-url-rejects-a-path-param-the-pattern-does-not-capture
  (register-routes!)

  (testing "a param the pattern does not capture is rejected LOUD, naming the
            key and the slot (prefetch projects the slot)"
    (is (= {:rf.error/id :rf.error/route-url-validation
            :reason      :uncaptured-params
            :keys        [:extra]
            :slot        :params
            :route-id    :route/probe}
           (select-keys (thrown-data #(rf.routing.registry/route-url {:to     :route/probe
                                                                      :params {:id "7" :extra "x"}}))
                        [:rf.error/id :reason :keys :slot :route-id]))))

  (testing "a route with NO path params rejects every param, naming them in
            total canonical order"
    (is (= {:reason :uncaptured-params :keys [:alpha :zeta]}
           (select-keys (thrown-data #(rf.routing.registry/route-url {:to     :route/plain
                                                                      :params {:zeta 1 :alpha 2}}))
                        [:reason :keys]))))

  (testing "the rejection names STRUCTURE only — no param VALUE, so a secret in
            an uncaptured key never reaches an error surface"
    (let [data (thrown-data #(rf.routing.registry/route-url
                               {:to     :route/probe
                                :params {:id "7" :token "SECRET-100"}}))]
      (is (and (= [:token] (:keys data))
               (not (re-find #"SECRET-100" (pr-str data))))))))

;; `:rf.route/not-found`'s slice `:params` are the framework's FACT record of the
;; miss (`plan/not-found-params` → `{:url … :reason …}`), not path captures, so
;; the fallback route's pattern has no say over their vocabulary. Without the
;; exemption, every door that rebuilds the CURRENT slice through route-url — the
;; address-bar restore after a rejection, an in-place edit — would reject it.
(deftest the-reserved-not-found-route-is-the-one-exemption
  (register-routes!)
  (rf.routing/reg-route :rf.route/not-found {} "/404")
  (is (= "/404" (rf.routing.registry/route-url {:to     :rf.route/not-found
                                                :params {:url "/nope" :reason :malformed-url}}))
      "the miss record rides through rather than rejecting")
  (testing "an in-place query edit while parked on the fallback commits, and
            the miss record survives it"
    (rf/dispatch-sync [:rf.route/handle-url-change "/nope" {:rf.route/cause :link}])
    (rf/dispatch-sync [:rf.route/navigate {:query {:x "1"}}])
    ;; The fallback declares no query vocabulary, so `:x` commits the way the URL spells it.
    (is (= {:route-id :rf.route/not-found :params {:url "/nope"} :query {"x" "1"}}
           (select-keys (current-slice) [:route-id :params :query])))))

;; ===========================================================================
;; The two ACTIVATION doors agree
;; ===========================================================================

(deftest both-activation-doors-agree-on-an-uncaptured-param
  (register-routes!)
  (let [pushed (atom [])]
    (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
               (fn [_ url] (swap! pushed conj url)))

    (testing "the PROGRAMMATIC door rejects — committing :params {:id \"7\"
              :extra \"x\"} would leave a slice the address bar cannot spell"
      (rf/dispatch-sync [:rf.route/navigate {:to :route/elsewhere}])
      (reset! pushed [])
      (let [before (current-slice)]
        (with-trace-recorder!
          [traces {:pred #(= :rf.error/schema-validation-failure (:operation %))}]
          (rf/dispatch-sync [:rf.route/navigate {:to     :route/probe
                                                 :params {:id "7" :extra "x"}}])
          (when rf.interop/debug-enabled?
            (is (= [:route/probe] (mapv (comp :route-id :tags) @traces)))))
        (is (= before (current-slice)) "slice unchanged — nothing was committed")
        (is (= [:route/elsewhere []] [(:route-id (current-slice)) @pushed])
            "still on the earlier route, and no history entry was pushed")))

    (testing "the LINK door rejects the same address at href synthesis, so no
              click payload committing :params {:id \"7\"} can be built"
      (is (= {:rf.error/id :rf.error/route-url-validation :reason :uncaptured-params :keys [:extra]}
             (select-keys (thrown-data #(rf.routing.link/link-model {:to     :route/probe
                                                                     :params {:id "7" :extra "x"}}
                                                                    :rf/default))
                          [:rf.error/id :reason :keys]))))))

;; ===========================================================================
;; Prefetch inherits the agreed answer
;; ===========================================================================

(deftest prefetch-rejects-the-address-both-activation-doors-refuse
  (register-routes!)
  (let [calls (atom [])]
    (rf.late-bind/set-fn! :routing/on-route-prefetch
                       (fn [plan] (swap! calls conj (select-keys plan [:route-id :params]))
                         {:warmed 1 :fx []}))
    (try
      (testing "POSITIVE CONTROL — the captured-param address warms, exactly
                once, on the captured-param plan"
        (rf/dispatch-sync [:rf.route/prefetch {:to :route/probe :params {:id "7"}}])
        (is (= [{:route-id :route/probe :params {:id "7"}}] @calls)))

      (testing "an uncaptured param rejects BEFORE planning, on the boundary the
                activation doors refuse it at — otherwise prefetch would warm
                {:id \"7\" :extra \"x\"} while a click activates {:id \"7\"}"
        (reset! calls [])
        (with-trace-recorder!
          [traces {:pred #(= :rf.error/prefetch-bad-address (:operation %))}]
          (rf/dispatch-sync [:rf.route/prefetch {:to :route/probe :params {:id "7" :extra "x"}}])
          (is (empty? @calls) "the warm hook was never consulted")
          (when rf.interop/debug-enabled?
            (is (= [{:reason :route-url-validation :keys [:params] :route-id :route/probe}]
                   (mapv #(select-keys (:tags %) [:reason :keys :route-id]) @traces))
                "one rejection, naming the boundary's error id and the offending SLOT"))))
      (finally (rf.late-bind/set-fn! :routing/on-route-prefetch nil)))))
