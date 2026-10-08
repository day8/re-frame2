(ns re-frame.route-link-test
  "JVM tests for the `:route/link` registered view: the SSR shell's anchor
  (policy keys stripped, html attrs and children passed through), the
  server frame's `:url-strategy` reaching the emitted href, and the
  `:rf.route/url-requested` payload a click carries honouring the link's
  `:replace?` / `:scroll` / `:bypass-leave?` policy. The click handler's
  modifier-key branching is CLJS-only (`route_link_cljs_test.cljs`); the
  cross-host href table is `route_link_ssr_parity_cljs_test.cljc`.

  Per Spec 012 §Linking from views and API.md `route-link` row."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.link :as rf.routing.link]
            [re-frame.ssr :as rf.ssr]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

;; ---- server-frame :url-strategy --------------------------------------------
;;
;; The SSR pipeline renders inside the request frame's `rf/with-frame` scope,
;; so a based server frame must emit `/demos/active` exactly as the hydrated
;; client does — `/active` would leave the deployment mount if followed before
;; hydration, and is a Spec 011 hydration mismatch.

(deftest route-link-ssr-honours-the-server-frames-url-strategy
  (rf/reg-route :route/active {} "/active")
  (rf/make-frame {:id           :ssr/history-base
                  :platform     :server
                  :url-bound?   true
                  :url-strategy (rf.routing/with-base-path rf.routing/history-url-strategy "/demos")})
  (rf/with-frame :ssr/history-base
    (testing "the SSR emitter walking `[rf/route-link …]`"
      (is (str/includes? (rf.ssr/render-to-string [rf/route-link {:to :route/active} "Active"])
                         "href=\"/demos/active\"")))
    (testing "the registered :route/link view the emitter resolves"
      (is (= "/demos/active" (:href (second ((rf/view :route/link) {:to :route/active}))))))))

;; ---- navigation policy on a link -----------------------------------------
;;
;; `click-payload` is the dispatch a click carries: `link-model` builds it
;; through the same synthesiser `route-link-render` uses.

(defn- click-payload [props]
  (:payload (rf.routing.link/link-model props :rf/default)))

(defn- record-nav-fx!
  "Re-register the :client-only navigation fx for the JVM, recording each
  call as `[fx-id arg]`. Returns the recording atom."
  []
  (let [seen (atom [])]
    (doseq [fx-id [:rf.nav/push-url :rf.nav/replace-url :rf.nav/scroll]]
      (rf.fx/reg-fx fx-id {:platforms #{:server :client}}
                    (fn [_ arg] (swap! seen conj [fx-id arg]))))
    seen))

(defn- click-fx!
  "Commit `/`, then dispatch the click payload for `props`; return the nav fx
  the click ran."
  [seen props]
  (rf/dispatch-sync [:rf.route/handle-url-change "/" {:rf.route/cause :initial}])
  (reset! seen [])
  (rf/dispatch-sync (click-payload props))
  @seen)

(deftest route-link-policy-keys-ride-the-click-not-the-anchor
  (rf/reg-route :route/cart {} "/cart")
  (let [props {:to :route/cart :fragment "top" :class "nav"
               :replace? true :scroll :preserve :bypass-leave? true}]
    (testing "the policy keys never reach the <a>; html attrs and children pass through"
      (is (= [:a {:href "/cart#top" :class "nav"} "Cart"]
             (rf.routing/route-link-render-ssr props "Cart"))))
    (testing "the click's :rf.route/url-requested carries each policy key written"
      (is (= [:rf.route/url-requested
              {:url "/cart#top" :replace? true :scroll :preserve :bypass-leave? true}]
             (click-payload props))))
    (testing "and none that was not"
      (is (= [:rf.route/url-requested {:url "/cart"}]
             (click-payload {:to :route/cart}))))))

(deftest route-link-replace-replaces-the-history-entry
  (rf/reg-route :route/home {} "/")
  (rf/reg-route :route/cart {} "/cart")
  (let [seen (record-nav-fx!)]
    (doseq [[props fx-id] [[{:to :route/cart}                :rf.nav/push-url]
                           [{:to :route/cart :replace? true} :rf.nav/replace-url]]]
      (is (= [[fx-id "/cart"]]
             (filterv (comp #{:rf.nav/push-url :rf.nav/replace-url} first)
                      (click-fx! seen props)))
          (pr-str props)))))

(deftest route-link-scroll-overrides-the-link-default
  (rf/reg-route :route/home {} "/")
  (rf/reg-route :route/cart {:scroll :restore} "/cart")
  (let [seen (record-nav-fx!)]
    ;; No link :scroll → the route's own; a link :scroll overrides it; false → none.
    (doseq [[props strategies] [[{:to :route/cart}                  [:restore]]
                                [{:to :route/cart :scroll :preserve} [:preserve]]
                                [{:to :route/cart :scroll false}     []]]]
      (is (= strategies
             (into [] (comp (filter (comp #{:rf.nav/scroll} first))
                            (map (comp :strategy second)))
                   (click-fx! seen props)))
          (pr-str props)))))

(deftest route-link-bypass-leave-skips-the-current-leave-guard
  (rf/reg-route :route/editor {:can-leave :editor/can-leave?} "/editor")
  (rf/reg-route :route/cart {} "/cart")
  (rf/reg-sub :editor/can-leave? (fn [_ _] false))
  (record-nav-fx!)
  (rf/dispatch-sync [:rf.route/handle-url-change "/editor" {:rf.route/cause :initial}])
  (let [routing #(get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                         [:rf.runtime/routing])]
    (rf/dispatch-sync (click-payload {:to :route/cart}))
    (is (= :route/editor (:route-id (:current (routing))))
        "without :bypass-leave? the guard blocks the link")
    (rf/dispatch-sync [:rf.route/cancel (:id (:pending-navigation (routing)))])
    (rf/dispatch-sync (click-payload {:to :route/cart :bypass-leave? true}))
    (is (= [:route/cart nil] [(:route-id (:current (routing))) (:pending-navigation (routing))])
        "with :bypass-leave? true the link leaves, creating no pending navigation")))
