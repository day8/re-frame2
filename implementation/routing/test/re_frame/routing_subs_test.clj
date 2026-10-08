(ns re-frame.routing-subs-test
  "Framework subs for re-frame.routing (`:rf.route/fragment`, the
  nested-layout `:rf.route/chain`, `:rf.route/id`) and the activated /
  deactivated lifecycle trace.

  ## Posture split

  The subs are production-real and run in the ordinary `clojure -M:test` suite
  AND in `scripts/test-routing-prod-gate.sh` (the `-Dre-frame.debug=false`
  lane). The lifecycle TRACE rides `trace/emit!`, gated on
  `rf.interop/debug-enabled?`, so its assertion sits inside a
  `(when rf.interop/debug-enabled? …)` arm beside the posture-independent read
  of where the navigations landed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(deftest sub-rf-route-fragment
  (testing ":rf.route/fragment reads the slice's :fragment"
    (rf/reg-route :route/docs {} "/docs/:page")
    (rf/dispatch-sync [:rf.route/handle-url-change "/docs/routing#scroll-restoration"
                       {:rf.route/cause :link}])
    (is (= "scroll-restoration" @(rf/subscribe [:rf.route/fragment])))))

(deftest sub-rf-route-chain
  (testing ":rf.route/chain walks :parent from the active route up to the root"
    (rf/reg-route :route/account          {} "/account")
    (rf/reg-route :route/account.settings {:parent :route/account} "/account/settings")
    (rf/reg-route :route/account.profile  {:parent :route/account.settings} "/account/settings/profile")
    (rf/dispatch-sync [:rf.route/navigate {:to :route/account.profile}])
    (is (= [:route/account :route/account.settings :route/account.profile]
           @(rf/subscribe [:rf.route/chain])))))

(deftest route-activated-deactivated-trace-on-navigation
  (testing "the first navigation emits only :rf.route/activated, a cross-route
            one emits deactivated then activated, and a same-id one emits neither"
    (rf/reg-route :route/from {} "/from")
    (rf/reg-route :route/to   {} "/to")
    (let [traces (atom [])]
      (rf/register-listener! :trace ::lifecycle (fn [ev] (swap! traces conj ev)))
      (doseq [to [:route/from :route/to :route/to]]
        (rf/dispatch-sync [:rf.route/navigate {:to to}]))
      (rf/unregister-listener! :trace ::lifecycle)
      (is (= :route/to @(rf/subscribe [:rf.route/id])))
      (when rf.interop/debug-enabled?
        (is (= [[:rf.route/activated :route/from]
                [:rf.route/deactivated :route/from]
                [:rf.route/activated :route/to]]
               (->> @traces
                    (filter #(#{:rf.route/activated :rf.route/deactivated} (:operation %)))
                    (mapv (juxt :operation (comp :route-id :tags))))))))))
