(ns re-frame.routing-prefetch-test
  "Intent prefetch (Spec 012 §Route-plan prefetch): the pure structural gate
  (`rf.routing.address/prefetch-address-error`), the pure payload synthesis
  (`rf.routing.link/prefetch-payload`), the `:prefetch` value check, and the
  `:rf.route/prefetch` event's destination gate and warmed identity, driven
  against stubbed late-bound resource hooks. The warm plan itself (isolation,
  dedupe, reuse, planning failure) is proven by the `ep-0037-r3-prefetch-*`
  conformance fixtures.

  ## Posture split

  The gates, the payload synthesis and the stubbed hooks' `@calls` (late-bound
  fns, not traces) are production-real, so whether prefetch REACHED planning,
  and with which identity, is asserted unguarded and runs in the ordinary
  `clojure -M:test` suite AND in `scripts/test-routing-prod-gate.sh` (the
  `-Dre-frame.debug=false` lane). The REPORTING — the `:rf.route/prefetched`
  summary and the `:rf.error/prefetch-bad-address` rejection traces — is
  dev-only, so those assertions sit inside `(when rf.interop/debug-enabled? …)`
  arms. That includes the no-leak check on a schema rejection: with no trace it
  would pass vacuously, and outside the arm the posture-independent fact is
  that the offending value never reached the warm plan."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.address :as rf.routing.address]
            [re-frame.routing.link :as rf.routing.link]
            [re-frame.routing-test-support :as rf.routing-test-support]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(deftest prefetch-address-error-accepts-only-a-closed-route-address
  (doseq [[request expected]
          [[{:to :route/article :params {:slug "x"} :query {:tab "c"} :fragment "reply"} nil]
           [{:url "/articles/x"}                        {:reason :unknown-keys :keys [:url]}]
           [{:params {:slug "x"}}                       {:reason :missing-to :keys [:to]}]
           [{:to :route/article :params [:not :a :map]} {:reason :bad-address :keys []}]
           [nil                                          {:reason :request-not-a-map :keys []}]]]
    (is (= expected (rf.routing.address/prefetch-address-error request))
        (pr-str request))))

(deftest prefetch-payload-synthesises-the-event-only-on-intent-opt-in
  (testing "an opted-in link yields the address-only event"
    (is (= [:rf.route/prefetch {:to :route/article}]
           (rf.routing.link/prefetch-payload {:to :route/article :prefetch :intent
                                              :class "title" :on-mouse-enter identity}))))
  (testing "an ABSENT :prefetch is passive, and the only way to be"
    (is (nil? (rf.routing.link/prefetch-payload {:to :route/article})))))

(defn- bad-prefetch-ex-data
  "The `ex-data` of the throw `calc` raises for `props`, or nil if it did not throw."
  [calc props]
  (try (calc props) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest a-present-but-unsupported-prefetch-value-fails-loud
  (testing "any PRESENT :prefetch other than :intent throws, nil and false
            included: a silently passive link looks identical to a working one"
    (doseq [v [nil false :render]]
      (is (= {:rf.error/id :rf.error/route-link-bad-prefetch
              :where       'rf/route-link
              :slot        :prefetch
              :accepted    :intent}
             (select-keys (bad-prefetch-ex-data rf.routing.link/prefetch-payload
                                                {:to :route/article :prefetch v})
                          [:rf.error/id :where :slot :accepted]))
          (pr-str v)))))

(deftest every-link-surface-validates-the-prefetch-value-on-both-hosts
  ;; The JVM SSR shell. `link-model`'s check is pinned on both hosts by
  ;; `re-frame.route-link-ssr-parity-cljs-test`, the client render's by
  ;; `re-frame.route-link-cljs-test`.
  (rf.routing/reg-route :route/article {} "/articles/:slug")
  (testing "the SSR shell rejects a bad value rather than rendering it stripped"
    (is (= :rf.error/route-link-bad-prefetch
           (:rf.error/id (bad-prefetch-ex-data rf.routing.link/route-link-render-ssr
                                               {:to :route/article :prefetch :render})))))
  (testing "and strips a valid :intent before DOM emission"
    (is (= [:a {:href "/articles/x" :class "t"}]
           (rf.routing.link/route-link-render-ssr {:to :route/article :params {:slug "x"}
                                                   :prefetch :intent :class "t"})))))

;; ---- the destination gate --------------------------------------------------
;;
;; The structural gate cannot know whether an address RESOLVES. Prefetch
;; resolves the destination through `route-url` BEFORE planning, so an address
;; a navigation would refuse never warms anything and never reports a success.

(defn- with-warm-hook
  "Publish a stub `:routing/on-route-prefetch` that records every warm-plan call
  and reports one warmed requirement, call `(f calls)`, then unpublish it. The
  Resources artefact is not on this classpath, so the hook is otherwise unbound."
  [f]
  (let [calls (atom [])]
    (rf.late-bind/set-fn! :routing/on-route-prefetch
                       (fn [plan]
                         (swap! calls conj plan)
                         {:warmed 1 :fx [[:dispatch [:warm/ensured]]]}))
    (try (f calls)
         (finally (rf.late-bind/set-fn! :routing/on-route-prefetch nil)))))

(defn- prefetch!
  "Dispatch `[:rf.route/prefetch address]` and return the tags of the summary
  and bad-address traces it emitted."
  [address]
  (with-trace-recorder! [traces {:pred #(contains? #{:rf.route/prefetched
                                                     :rf.error/prefetch-bad-address}
                                                   (:operation %))
                                 :shape :by-op}]
    (rf/dispatch-sync [:rf.route/prefetch address])
    {:prefetched (mapv :tags (:rf.route/prefetched @traces))
     :rejected   (mapv :tags (:rf.error/prefetch-bad-address @traces))}))

(deftest prefetch-resolves-the-named-destination-before-planning
  (rf.routing/reg-route :route/probe {} "/probe/:id")
  (with-warm-hook
    (fn [calls]
      (testing "a resolvable destination reaches the warm plan and reports one summary"
        (let [{:keys [prefetched]} (prefetch! {:to :route/probe :params {:id "7"}})]
          (is (= [{:route-id :route/probe :params {:id "7"}}]
                 (mapv #(select-keys % [:route-id :params]) @calls)))
          (when rf.interop/debug-enabled?
            (is (= [{:route-id :route/probe :warmed 1}]
                   (mapv #(select-keys % [:route-id :warmed]) prefetched))))))
      (doseq [[address rejection]
              [[{:to :route/does-not-exist} {:reason :no-such-route :keys [:to]}]
               ;; reaching the warm hook as {:params {}} would warm the wrong identity
               [{:to :route/probe}          {:reason :missing-route-param :keys [:id]}]
               ;; the structural gate runs first, so its own :reason wins
               [{:url "/probe/7"}           {:reason :unknown-keys :keys [:url]}]]]
        (testing (pr-str address)
          (reset! calls [])
          (let [{:keys [prefetched rejected]} (prefetch! address)]
            (is (empty? @calls) "rejected before the warm plan")
            (when rf.interop/debug-enabled?
              ;; and no success summary beside it, or the trace would lie
              (is (= [[] [rejection]]
                     [prefetched (mapv #(select-keys % [:reason :keys]) rejected)])))))))))

(deftest prefetch-rejects-params-that-fail-the-routes-schema-without-leaking-them
  (let [restore (rf.routing-test-support/with-stub-validator)]
    (try
      (rf.routing/reg-route :route/guarded
                         {:params (fn [{:keys [id]}] (= "ok" id))} "/guarded/:id")
      (with-warm-hook
        (fn [calls]
          (testing "conforming params warm"
            (prefetch! {:to :route/guarded :params {:id "ok"}})
            (is (= 1 (count @calls))))
          (testing "non-conforming params reject before planning, against the same
                    schemas a navigation uses; the rejection names the slot and
                    carries no value, though route-url's own ex-data embeds it"
            (reset! calls [])
            (let [{:keys [prefetched rejected]}
                  (prefetch! {:to :route/guarded :params {:id "SECRET-100"}})]
              (is (empty? @calls))
              (when rf.interop/debug-enabled?
                (is (= [[] [{:reason :route-url-validation :keys [:params]}]]
                       [prefetched (mapv #(select-keys % [:reason :keys]) rejected)]))
                (is (not (re-find #"SECRET-100" (pr-str rejected)))))))))
      (finally (restore)))))

;; ---- the warmed identity is the activated identity ---------------------------
;;
;; A warm-up keyed on different `:params` / `:query` / `:fragment` than the
;; activation would leave two cache entries for one destination, the warm one
;; ownerless and never reused.

(defn- with-identity-hooks
  "Publish BOTH late-bound resource hooks — the prefetch WARM plan and the
  navigation ENTRY plan — each recording only the identity facts a cache entry
  is keyed on, and call `(f warm entry)`."
  [f]
  (let [identity-of #(select-keys % [:route-id :params :query :fragment])
        warm        (atom [])
        entry       (atom [])]
    (rf.late-bind/set-fn! :routing/on-route-prefetch
                       (fn [plan]
                         (swap! warm conj (identity-of plan))
                         {:warmed 1 :fx []}))
    (rf.late-bind/set-fn! :routing/on-route-entry
                       (fn [plan] (swap! entry conj (identity-of plan)) nil))
    (try (f warm entry)
         (finally (rf.late-bind/set-fn! :routing/on-route-prefetch nil)
                  (rf.late-bind/set-fn! :routing/on-route-entry nil)))))

(defn- register-probe-routes! []
  (rf.routing/reg-route :route/elsewhere {} "/elsewhere")
  (rf.routing/reg-route :route/probe {:query-defaults {:tab :overview}} "/probe/:id"))

(def ^:private probe-identity
  {:route-id :route/probe :params {:id "7"} :query {:tab :overview} :fragment nil})

(deftest prefetch-warms-the-identity-a-link-click-activates
  ;; Hover and click resolve through different seams (named address vs URL), so
  ;; the pair cannot pass by both sides agreeing on one wrong value.
  (register-probe-routes!)
  (let [props {:to :route/probe :params {:id "7"} :query {:drop nil}
               :prefetch :intent :class "nav-link"}]
    (with-identity-hooks
      (fn [warm entry]
        (is (= [:rf.route/prefetch {:to :route/probe :params {:id "7"} :query {:drop nil}}]
               (rf.routing.link/prefetch-payload props))
            "the hover payload is the address as written; normalising is the seam's job")
        (rf/dispatch-sync (rf.routing.link/prefetch-payload props))
        ;; Park elsewhere so the click is never an exact no-op.
        (rf/dispatch-sync [:rf.route/navigate {:to :route/elsewhere}])
        (rf/dispatch-sync (:payload (rf.routing.link/link-model props :rf/default)))
        (is (= [[probe-identity] [probe-identity]]
               [@warm (filterv #(= :route/probe (:route-id %)) @entry)])
            "hover warms, and the click activates, ONE identity")))))

(deftest prefetch-warms-the-identity-a-programmatic-navigation-commits
  ;; The named-address pair also reaches the empty fragment, which
  ;; `prefetch-payload` never carries.
  (register-probe-routes!)
  (doseq [address [;; route-url elides a nil-valued query key
                   {:to :route/probe :params {:id "7"} :query {:drop nil}}
                   ;; route-url emits no trailing #, and "" is truthy
                   {:to :route/probe :params {:id "7"} :fragment ""}]]
    (testing (pr-str address)
      (with-identity-hooks
        (fn [warm entry]
          (rf/dispatch-sync [:rf.route/prefetch address])
          (rf/dispatch-sync [:rf.route/navigate {:to :route/elsewhere}])
          (rf/dispatch-sync [:rf.route/navigate address])
          (is (= [[probe-identity] [probe-identity]]
                 [@warm (filterv #(= :route/probe (:route-id %)) @entry)])))))))
