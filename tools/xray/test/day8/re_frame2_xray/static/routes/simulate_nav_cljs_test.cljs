(ns day8.re-frame2-xray.static.routes.simulate-nav-cljs-test
  "View tests for the hermetic Simulate-navigation preview
  (rf2-o5f5f.3).

  ## Hermetic posture

  The Simulate-navigation preview MUST NOT mutate app-db, dispatch
  navigation, or fire fx. Pure preview — params + handler + db slot
  shape. This file pins the contract.

  Pure-data tests for the projection live in
  `routing_helpers_cljs_test.cljc`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.routes.panel :as panel]
            [day8.re-frame2-xray.static.routes.simulate-nav :as simulate-nav]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

;; ---- hiccup walkers -----------------------------------------------------

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- text-of [tree]
  (->> (hiccup-seq tree)
       (filter string?)
       (apply str)))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

(def routes
  {:route/cart    {:path "/cart"
                   :on-match [:cart/load]}
   :route/article {:path     "/articles/:slug"
                   :on-match [:article/load]
                   :params   [:map [:slug :string]]}})

;; ---- direct view tests (pure render path) ------------------------------

(deftest preview-unknown-route-renders-the-unknown-block
  (testing "unknown route-id → red unknown block"
    (let [tree (simulate-nav/preview routes :route/nope nil)]
      (is (some? (find-by-testid tree "rf-xray-static-routes-sim-nav-unknown"))
          "unknown surface rendered"))))

(deftest preview-registered-no-url
  (testing "registered route, no URL → path + on-match + db-slot + slot-shape"
    (let [tree (simulate-nav/preview routes :route/cart nil)]
      (is (some? (find-by-testid tree "rf-xray-static-routes-sim-nav-route/cart")))
      (is (some? (find-by-testid tree "rf-xray-static-routes-sim-nav-path")))
      (is (some? (find-by-testid tree "rf-xray-static-routes-sim-nav-on-match")))
      (is (some? (find-by-testid tree "rf-xray-static-routes-sim-nav-db-slot")))
      (is (some? (find-by-testid tree "rf-xray-static-routes-sim-nav-slot-shape"))))))

(deftest preview-renders-hermetic-marker
  (testing "the preview surface labels itself hermetic (no dispatch)"
    (let [tree (simulate-nav/preview routes :route/cart nil)]
      (is (re-find #"Hermetic preview" (text-of tree))
          "the preview surface labels itself 'Hermetic preview'"))))

(deftest preview-with-matching-url-shows-matched-marker
  (testing "URL that matches the route's pattern surfaces (matched) in the URL row"
    (let [tree (simulate-nav/preview routes :route/cart "/cart")]
      (is (some? (find-by-testid tree "rf-xray-static-routes-sim-nav-url"))))))

;; ---- integration through the panel (hermetic posture) ------------------

(deftest panel-sim-nav-does-not-touch-app-db
  (testing "opening + closing the Simulate-navigation preview leaves the
            current route slice untouched"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test routes]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/set-current-route-slice-override-for-test
                         {:id :route/article :params {:slug "old"} :query {}}]
                        {:frame :rf/xray})
      ;; Expand row + toggle preview for :route/cart — the preview targets
      ;; a different route than the current slice, so a real navigation
      ;; would change the slice. The preview MUST NOT.
      (rf/dispatch-sync [:rf.xray.static.routes/toggle-row :route/cart]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray.static.routes/toggle-sim-nav :route/cart]
                        {:frame :rf/xray})
      (let [slice @(rf/subscribe [:rf.xray/current-route-slice])]
        (is (= :route/article (:id slice))
            "current slice unchanged after opening the preview")
        (is (= {:slug "old"} (:params slice))
            "params unchanged"))
      ;; Toggle closed — still unchanged.
      (rf/dispatch-sync [:rf.xray.static.routes/toggle-sim-nav :route/cart]
                        {:frame :rf/xray})
      (let [slice @(rf/subscribe [:rf.xray/current-route-slice])]
        (is (= :route/article (:id slice))
            "current slice still unchanged after closing the preview")))))
