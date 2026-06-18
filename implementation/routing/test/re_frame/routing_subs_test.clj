(ns re-frame.routing-subs-test
  "Framework-sub tests for re-frame.routing (`:rf/route` and the
  `:rf.route/*` derived subs, `:rf.route/chain` nested-layout chain,
  `:rf/pending-navigation`, and the activated/deactivated lifecycle
  trace). Split from routing_test.clj per rf2-u8qe7y finding 3."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

;; ---- Spec 012 §Nested layouts ---------------------------------------------

(deftest routing-nested-layout-parent-link
  (testing ":parent metadata round-trips through reg-route"
    ;; Per Spec 012 §Nested layouts: a child route declares :parent
    ;; <route-id> so views can render the layout chain. The :rf.route/chain
    ;; sub is the runtime's enumeration entry point but is not yet
    ;; framework-registered; the registry-level contract — :parent is
    ;; enumerable via handler-meta — IS implemented and is what tooling
    ;; queries. This test pins that registry-level contract.
    (rf/reg-route :route/account             {:path "/account"})
    (rf/reg-route :route/account.settings    {:path   "/account/settings"
                                              :parent :route/account})
    (rf/reg-route :route/account.billing     {:path   "/account/billing"
                                              :parent :route/account})
    (let [settings-meta (rf/handler-meta :route :route/account.settings)
          billing-meta  (rf/handler-meta :route :route/account.billing)
          account-meta  (rf/handler-meta :route :route/account)]
      (is (= :route/account (:parent settings-meta))
          ":route/account.settings carries :parent :route/account")
      (is (= :route/account (:parent billing-meta))
          ":route/account.billing carries :parent :route/account")
      (is (nil? (:parent account-meta))
          "the parent route itself has no :parent (chain root)"))))

;; ---- rf2-k72qn: framework subs — fragment, chain, pending-navigation ------
;;
;; Per Spec 012 §Subscriptions the framework ships nine canonical subs over
;; the route slice and pending-nav slot. The six core ones
;; (:rf/route, :rf.route/{id,params,query,transition,error}) are pinned by
;; the bootstrap tests above; the three from rf2-k72qn close out the table.

(deftest sub-rf-route-fragment
  (testing ":rf.route/fragment reads the slice's :fragment"
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Land on a URL with a fragment — the slice carries :fragment "x"
    (rf/dispatch-sync [:rf.route/transitioned "/docs/routing#scroll-restoration"])
    (is (= "scroll-restoration"
           @(rf/subscribe [:rf.route/fragment]))
        ":rf.route/fragment returns the URL's #fragment")
    ;; Land on a URL with no fragment — sub returns nil
    (rf/dispatch-sync [:rf.route/transitioned "/docs/api"])
    (is (nil? @(rf/subscribe [:rf.route/fragment]))
        ":rf.route/fragment returns nil when the URL has no #fragment")))

(deftest sub-rf-route-chain
  (testing ":rf.route/chain returns the :parent-chain [parent-most ... current]"
    (rf/reg-route :route/account             {:path "/account"})
    (rf/reg-route :route/account.settings    {:path   "/account/settings"
                                              :parent :route/account})
    (rf/reg-route :route/account.profile     {:path   "/account/settings/profile"
                                              :parent :route/account.settings})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; Land on the deepest leaf — chain walks up to the root.
    (rf/dispatch-sync [:rf.route/navigate :route/account.profile])
    (is (= [:route/account :route/account.settings :route/account.profile]
           @(rf/subscribe [:rf.route/chain]))
        ":rf.route/chain returns [root ... leaf]")
    ;; Mid-chain
    (rf/dispatch-sync [:rf.route/navigate :route/account.settings])
    (is (= [:route/account :route/account.settings]
           @(rf/subscribe [:rf.route/chain]))
        ":rf.route/chain returns the partial chain from the middle")
    ;; Root has a single-element chain
    (rf/dispatch-sync [:rf.route/navigate :route/account])
    (is (= [:route/account]
           @(rf/subscribe [:rf.route/chain]))
        ":rf.route/chain returns a single-element chain for the root")))

(deftest sub-rf-pending-navigation
  (testing ":rf/pending-navigation reads the pending-nav slot"
    (rf/reg-route :editor/article
                  {:path      "/editor/articles/:id"
                   :params    [:map [:id :string]]
                   :can-leave :editor/can-leave?})
    (rf/reg-route :route/cart {:path "/cart"})
    (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :editor/can-leave?
                (fn [db _] (not (get-in db [:editor :dirty?]))))
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; No pending nav yet
    (is (nil? @(rf/subscribe [:rf/pending-navigation]))
        ":rf/pending-navigation returns nil when no nav is pending")
    ;; Set up a pending nav via the can-leave guard
    (rf/dispatch-sync [:rf.route/transitioned "/editor/articles/A"])
    (rf/dispatch-sync [:editor/dirty true])
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (let [pending @(rf/subscribe [:rf/pending-navigation])]
      (is (some? pending)
          ":rf/pending-navigation populated after a guard rejection"))
    ;; Clear it
    (rf/dispatch-sync [:rf.route/cancel "pn-1"])
    (is (nil? @(rf/subscribe [:rf/pending-navigation]))
        ":rf/pending-navigation returns nil after :rf.route/cancel")))

;; ---- rf2-3a5nk7: route slice key is :route-id, sub-id :rf.route/id unchanged
;;
;; Adversarial pin for the slice-key rename (bare :id → :route-id). The
;; durable slice must be SELF-DESCRIBING — the active route id lives under
;; :route-id, NOT bare :id — while the consumer-facing subscription id
;; stays :rf.route/id (the rename is a slice-internal change, invisible to
;; sub callers). Both halves are asserted together so a regression that
;; reverted EITHER (slice back to :id, or sub-id drift) fails loudly.

(deftest route-slice-keyed-route-id-sub-id-unchanged
  (testing "the durable route slice is keyed :route-id (not bare :id);
            the :rf.route/id sub-id is unchanged and reads the new key"
    (rf/reg-route :route/cart {:path "/cart"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    (rf/dispatch-sync [:rf.route/navigate :route/cart])
    ;; 1. The RAW durable slice carries the active route id under :route-id.
    (let [slice (get-in (rf/runtime-db-value :rf/default)
                        [:rf.runtime/routing :current])]
      (is (= :route/cart (:route-id slice))
          "the slice stores the active route id under :route-id")
      (is (not (contains? slice :id))
          "the slice carries NO bare :id key (self-describing rename, rf2-3a5nk7)"))
    ;; 2. The subscription id is unchanged — :rf.route/id still resolves and
    ;;    returns the route id, reading the renamed slice key.
    (is (= :route/cart @(rf/subscribe [:rf.route/id]))
        ":rf.route/id sub-id unchanged; returns the active route id")
    ;; 3. The :rf/route slice sub exposes :route-id and NOT bare :id.
    (let [pub-slice @(rf/subscribe [:rf/route])]
      (is (= :route/cart (:route-id pub-slice))
          ":rf/route sub returns the slice keyed :route-id")
      (is (not (contains? pub-slice :id))
          ":rf/route sub carries NO bare :id key"))))

(deftest route-activated-deactivated-trace-on-navigation
  (testing ":rf.route/deactivated + :rf.route/activated fire on cross-route
            navigation (rf2-dn26r). Same-id navigation emits NEITHER."
    (rf/reg-route :route/from {:path "/from"})
    (rf/reg-route :route/to   {:path "/to"})
    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))
    ;; First nav: no prior route → only :rf.route/activated fires.
    (let [traces (atom [])]
      (rf/register-listener! :trace ::act1 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate :route/from])
      (rf/unregister-listener! :trace ::act1)
      (is (= [:route/from]
             (map #(-> % :tags :route-id)
                  (filter #(= :rf.route/activated (:operation %)) @traces)))
          "first nav: :rf.route/activated for :route/from")
      (is (empty? (filter #(= :rf.route/deactivated (:operation %)) @traces))
          "first nav (no prior): :rf.route/deactivated does NOT fire"))
    ;; Cross-route nav: both fire in deactivated→activated order.
    (let [traces (atom [])]
      (rf/register-listener! :trace ::act2 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate :route/to])
      (rf/unregister-listener! :trace ::act2)
      (let [lifecycle (filter #(#{:rf.route/activated :rf.route/deactivated}
                                (:operation %))
                              @traces)]
        (is (= [:rf.route/deactivated :rf.route/activated]
               (map :operation lifecycle))
            "cross-route nav: deactivated → activated in that order")
        (is (= :route/from
               (-> (first lifecycle) :tags :route-id))
            ":deactivated carries the prior route-id")
        (is (= :route/to
               (-> (second lifecycle) :tags :route-id))
            ":activated carries the next route-id")))
    ;; Same-id navigation: neither fires (route stays active across the transition).
    (let [traces (atom [])]
      (rf/register-listener! :trace ::act3 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf.route/navigate :route/to])
      (rf/unregister-listener! :trace ::act3)
      (let [lifecycle (filter #(#{:rf.route/activated :rf.route/deactivated}
                                (:operation %))
                              @traces)]
        (is (empty? lifecycle)
            "same-id navigation: neither lifecycle trace fires")))))
