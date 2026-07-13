(ns re-frame.ui.resource-lease-owner-mixed-cljs-test
  "rf2-vxgfnd.106 — wiring-sensitive proof that the frozen stock-Reagent
  helper and the actual re-frame.ui reconciler draw owner identities through
  the same neutral process-global mint."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.adapter.resource-lease :as adapter-lease]
            [re-frame.core :as rf]
            [re-frame.features :as features]
            [re-frame.registrar :as registrar]
            [re-frame.resource-lease-owner :as lease-owner]
            [re-frame.router :as router]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui.reactive :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(defn- silent-dispatch
  ([] (silent-dispatch nil))
  ([_event] nil)
  ([_event _opts] nil))

(deftest stock-reagent-and-ui-reconcile-share-the-neutral-mint
  (registrar/register! :resource ::feed {:rf/resource {}})
  (let [cell  (reactive/make-cell ::ui-owner)
        calls (atom 0)]
    (with-redefs [features/require-feature! (constantly true)
                  router/dispatch! silent-dispatch
                  ;; The controlled neutral source makes this a wiring test,
                  ;; not a coincidence test. A private UI counter would leave
                  ;; calls=1 and cannot manufacture [:shared 2].
                  lease-owner/mint! (fn [] [:shared (swap! calls inc)])]
      (let [stock-owner (adapter-lease/mint-lease-owner!)
            [_ cap] (rf/with-frame :rf/default
                      (reactive/with-capture
                       cell
                       #(do (reactive/lease-site
                             ::site {:resource ::feed})
                            :element)))]
        (reactive/commit-resources! cell cap)
        (reactive/reconcile-resource-leases! cell cap)
        (let [ui-owner (-> (reactive/resource-reservations cell)
                           vals first :owner)]
          (is (= 2 @calls))
          (is (= [:shared 1] stock-owner))
          (is (= [:shared 2] ui-owner))
          (is (not= stock-owner ui-owner)))))))
