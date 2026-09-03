(ns re-frame.routing-uncaptured-param-test
  "rf2-0iuh3 — a `:params` key the route PATTERN does not capture.

  Route `/probe/:id`, address `{:to :route/probe :params {:id \"7\" :extra \"x\"}}`.
  `:extra` names no `:name` / `*name` segment, so `route-url` cannot put it in
  the URL and `match-url` cannot read it back. Before this namespace the two
  ACTIVATION doors disagreed about what that address MEANS:

      [:rf.route/navigate {:to :route/probe :params {:id \"7\" :extra \"x\"}}]
        committed  :params {:id \"7\" :extra \"x\"}
      a route-link CLICK -> [:rf.route/url-requested {:url \"/probe/7\" …}]
        committed  :params {:id \"7\"}        (it resolves through the URL)
      [:rf.route/prefetch …] warmed {:id \"7\" :extra \"x\"}

  So hovering a link warmed one resource identity and clicking that SAME link
  activated another. The root cause is the door disagreement, not prefetch:
  Spec 012 §The one planning pipeline says doors differ in cause and history /
  scroll policy, NOT in target.

  THE ANSWER: neither committed value. An uncaptured path param is REJECTED
  LOUD at `route-url`, the one registry-aware emission boundary all three
  named-address doors already share. Spec 012 §Validity rules rule 2 already
  states the principle in as many words — letting address keys \"ride beside it
  and be silently ignored is the exact failure class this grammar exists to
  kill\" — and `route-url` already fails CLOSED on emission for every other
  input that breaks the `route-url` / `match-url` prism: the empty-string
  segment (`\"\"` cannot round-trip through trailing-slash normalisation) and
  the sequential-optional-group prefix rule (a later group emitted after an
  earlier one elided lands in the wrong capture slot). An uncaptured param is
  the same class and was the one remaining hole.

  Truncating instead (committing `{:id \"7\"}` from every door) would have been
  silent but total, and it loses the typo an optional group can hide:
  `/docs{/:section}?` with `{:sction \"x\"}` elides the group, builds `/docs`,
  and throws nothing — so `route-url`'s own `:rf.error/missing-route-param` can
  never catch that misspelling. Rejecting does.

  Covers the emission boundary, both activation doors, prefetch, and the
  adversarial trio the fix has to keep apart: a param that IS captured, a param
  that is NOT, and a route with no params at all.

  ## Posture split (rf2-o5dbf)

  The REJECTION is production-real and carries no posture guard. `route-url`
  THROWS (so the emission-boundary and link-door cases were already
  posture-independent), the programmatic door leaves the slice untouched and
  pushes no history entry, and prefetch never consults the warm hook. Those
  run in the ordinary `clojure -M:test` suite AND in
  `scripts/test-routing-prod-gate.sh` (the `-Dre-frame.debug=false` lane).

  What is dev-only is how the rejection is ANNOUNCED on the two EVENT doors:
  `:rf.error/schema-validation-failure` and `:rf.error/prefetch-bad-address`
  reach the caller through `trace/emit-error!`, gated on
  `rf.interop/debug-enabled?` and read once at load time. (The comment `the
  always-on channel` beside one of them means always-on with respect to the
  SCHEMAS ARTEFACT — the diagnostic does not require it to be loaded — not
  with respect to the production gate.) Those assertions are kept VERBATIM
  inside `(when rf.interop/debug-enabled? …)` arms marked `rf2-o5dbf`.

  Two of them are NEGATIVE over the recorder — `(is (empty? rejected))` on the
  positive control and `(is (empty? prefetched))` on the rejection case — and
  would pass vacuously under the gate. They are inside the arm; the
  production-visible half of each (the warm hook WAS consulted, exactly once,
  with the captured-param plan / was never consulted at all) sits outside it."
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
  (rf.routing/reg-route :route/plain     {} "/plain")
  (rf.routing/reg-route :route/docs      {} "/docs{/:section}?")
  (rf.routing/reg-route :route/files     {} "/files/*rest"))

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

  (testing "POSITIVE CONTROL — a param the pattern DOES capture builds its URL"
    (is (= "/probe/7" (rf.routing.registry/route-url {:to :route/probe :params {:id "7"}})))
    (is (= "/plain"   (rf.routing.registry/route-url {:to :route/plain :params {}})))
    (is (= "/plain"   (rf.routing.registry/route-url {:to :route/plain})))
    (testing "including an optional-group inner name, present or elided"
      (is (= "/docs/api" (rf.routing.registry/route-url {:to :route/docs :params {:section "api"}})))
      (is (= "/docs"     (rf.routing.registry/route-url {:to :route/docs :params {}}))))
    (testing "and a splat name"
      (is (= "/files/a/b" (rf.routing.registry/route-url {:to :route/files :params {:rest "a/b"}})))))

  (testing "a param the pattern does not capture is rejected LOUD"
    (let [data (thrown-data #(rf.routing.registry/route-url {:to     :route/probe
                                                  :params {:id "7" :extra "x"}}))]
      (is (some? data) "route-url threw rather than silently building /probe/7")
      (is (= :rf.error/route-url-validation (:rf.error/id data)))
      (is (= :uncaptured-params (:reason data)))
      (is (= [:extra] (:keys data)) "the offending param KEY is named")
      (is (= :params (:slot data)) "the offending SLOT is named, for prefetch's projection")
      (is (= :route/probe (:route-id data)))
      (is (= 'rf.routing/route-url (:where data)))))

  (testing "a route with NO path params at all rejects any param"
    (let [data (thrown-data #(rf.routing.registry/route-url {:to :route/plain :params {:anything 1}}))]
      (is (= :uncaptured-params (:reason data)))
      (is (= [:anything] (:keys data)))
      (is (= :route/plain (:route-id data)))))

  (testing "an optional group's inner name is CAPTURED even when the group
            elides — the typo beside it is what rejects"
    (is (= "/docs" (rf.routing.registry/route-url {:to :route/docs :params {}})))
    (let [data (thrown-data #(rf.routing.registry/route-url {:to :route/docs :params {:sction "x"}}))]
      (is (= :uncaptured-params (:reason data))
          "the misspelling `:sction` is caught — route-url's own
           :rf.error/missing-route-param never could, because the group simply
           elides and /docs builds cleanly")
      (is (= [:sction] (:keys data)))))

  (testing "every uncaptured key is named, in the total canonical order the
            rest of routing reports key sets in"
    (let [data (thrown-data #(rf.routing.registry/route-url {:to     :route/probe
                                                 :params {:id "7" :zeta 1 :alpha 2}}))]
      (is (= [:alpha :zeta] (:keys data)))))

  (testing "the rejection names STRUCTURE only — it carries no param VALUE, so
            a secret in an uncaptured key never reaches an error surface"
    (let [data (thrown-data #(rf.routing.registry/route-url {:to     :route/probe
                                                 :params {:id "7" :token "SECRET-100"}}))]
      (is (not (contains? data :value)))
      (is (not (re-find #"SECRET-100" (pr-str data))))
      (is (not (re-find #"SECRET-100" (pr-str (:keys data))))))))

(deftest the-reserved-not-found-route-is-the-one-exemption
  ;; `:rf.route/not-found`'s slice `:params` are the framework's FACT record of
  ;; the miss (`plan/not-found-params` → `{:url … :reason …}`), not path
  ;; captures, so the fallback route's pattern has no say over their
  ;; vocabulary. Without the exemption the address-bar restore after a
  ;; URL-driven rejection (`decisions/current-slice->url`, which rebuilds the
  ;; CURRENT slice through route-url) would silently stop rebuilding a
  ;; registered not-found route's URL.
  (register-routes!)
  (rf.routing/reg-route :rf.route/not-found {} "/404")
  (is (= "/404" (rf.routing.registry/route-url {:to     :rf.route/not-found
                                     :params {:url "/nope" :reason :malformed-url}}))
      "the miss record rides through rather than rejecting")
  (testing "and the exemption is scoped to that ONE reserved id — an ordinary
            route with the same-shaped params still rejects"
    (let [data (thrown-data #(rf.routing.registry/route-url {:to     :route/plain
                                                  :params {:url "/nope"}}))]
      (is (= :uncaptured-params (:reason data)))
      (is (= [:url] (:keys data)))))

  (testing "the exemption is load-bearing on a LIVE door, not just on the
            best-effort address-bar restore: an in-place query edit while
            parked on the fallback carries the miss record back through
            route-url, and an unexempted fallback would reject it"
    (rf/dispatch-sync [:rf.route/transitioned "/nope"])
    (is (= :rf.route/not-found (:route-id (current-slice))))
    (is (= {:url "/nope"} (:params (current-slice)))
        "the miss record is the fallback's :params — never path captures")
    (rf/dispatch-sync [:rf.route/navigate {:query {:x "1"}}])
    (is (= {:x "1"} (:query (current-slice)))
        "the in-place edit committed rather than rejecting")
    (is (= {:url "/nope"} (:params (current-slice)))
        "and the miss record survived the edit unchanged")))

;; ===========================================================================
;; The two ACTIVATION doors agree
;; ===========================================================================

(deftest both-activation-doors-agree-on-an-uncaptured-param
  (register-routes!)
  (let [pushed (atom [])]
    (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
               (fn [_ url] (swap! pushed conj url)))

    (testing "POSITIVE CONTROL — the well-formed sibling address commits the
              SAME params through both doors"
      (rf/dispatch-sync [:rf.route/navigate {:to :route/probe :params {:id "7"}}])
      (is (= {:id "7"} (:params (current-slice))) "programmatic door")
      (rf/dispatch-sync [:rf.route/navigate {:to :route/elsewhere}])
      (rf/dispatch-sync (:payload (rf.routing.link/link-model {:to :route/probe :params {:id "7"}}
                                                   :rf/default)))
      (is (= :route/probe (:route-id (current-slice))))
      (is (= {:id "7"} (:params (current-slice))) "link door"))

    (testing "the PROGRAMMATIC door rejects an uncaptured param — it used to
              commit :params {:id \"7\" :extra \"x\"}, a slice the address bar
              could not spell and a reload could not reproduce"
      (rf/dispatch-sync [:rf.route/navigate {:to :route/elsewhere}])
      (reset! pushed [])
      (let [before (current-slice)]
        (with-trace-recorder!
          [traces {:pred #(= :rf.error/schema-validation-failure (:operation %))}]
          (rf/dispatch-sync [:rf.route/navigate {:to     :route/probe
                                                 :params {:id "7" :extra "x"}}])
          ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring). The
          ;; REJECTION itself is asserted immediately below, on the slice and
          ;; on the push log, posture-independently.
          (when rf.interop/debug-enabled?
            (is (= 1 (count @traces)) "the caller bug surfaces on the always-on channel")
            (is (= :route/probe (:route-id (:tags (first @traces)))))))
        (is (= before (current-slice)) "slice unchanged — nothing was committed")
        (is (= :route/elsewhere (:route-id (current-slice))))
        (is (empty? @pushed) "and no history entry was pushed")))

    (testing "the LINK door rejects the same address at href synthesis, so the
              click payload that used to commit :params {:id \"7\"} can never be
              built — the two doors no longer disagree, they agree to refuse"
      (let [data (thrown-data #(rf.routing.link/link-model {:to     :route/probe
                                                 :params {:id "7" :extra "x"}}
                                                :rf/default))]
        (is (= :rf.error/route-url-validation (:rf.error/id data)))
        (is (= :uncaptured-params (:reason data)))
        (is (= [:extra] (:keys data)))))))

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
      (let [collect (fn [address]
                      (with-trace-recorder!
                        [traces {:pred  #(contains? #{:rf.route/prefetched
                                                      :rf.error/prefetch-bad-address}
                                                    (:operation %))
                                 :shape :by-op}]
                        (rf/dispatch-sync [:rf.route/prefetch address])
                        {:prefetched (:rf.route/prefetched @traces)
                         :rejected   (:rf.error/prefetch-bad-address @traces)}))]
        (testing "POSITIVE CONTROL — the captured-param address still warms"
          (let [{:keys [prefetched rejected]} (collect {:to :route/probe :params {:id "7"}})]
            ;; SEMANTIC, posture-independent (rf2-o5dbf): the warm hook really
            ;; ran, exactly once, on the captured-param plan. Without this the
            ;; `(empty? rejected)` leg is vacuous under the gate.
            (is (= [{:route-id :route/probe :params {:id "7"}}] @calls))
            ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
            (when rf.interop/debug-enabled?
              (is (empty? rejected))
              (is (= 1 (count prefetched))))))

        (testing "an uncaptured param rejects BEFORE planning, on the SAME
                  boundary the activation doors refuse it at — prefetch warmed
                  {:id \"7\" :extra \"x\"} while a click activated {:id \"7\"}"
          (reset! calls [])
          (let [{:keys [prefetched rejected]}
                (collect {:to :route/probe :params {:id "7" :extra "x"}})]
            ;; SEMANTIC, posture-independent (rf2-o5dbf): the address was
            ;; refused BEFORE planning, so nothing was warmed.
            (is (empty? @calls) "the warm hook was never consulted — no ensures")
            ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
            (when rf.interop/debug-enabled?
              (is (empty? prefetched) "no success summary trace")
              (is (= 1 (count rejected)))
              (let [tags (:tags (first rejected))]
                (is (= :route-url-validation (:reason tags))
                    "the bare name of the boundary's error id, as Spec 009 specifies")
                (is (= [:params] (:keys tags)) "the offending SLOT is named")
                (is (= :route/probe (:route-id tags))))))))
      (finally (rf.late-bind/set-fn! :routing/on-route-prefetch nil)))))
