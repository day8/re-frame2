(ns day8.re-frame2-xray.static.routes.simulate-nav-cljs-test
  "The Static Routes row-expand surface and the hermetic posture of its
  Simulate-navigation preview. Routes register through the real
  `reg-route` and the route slice is one a real `:rf.route/navigate`
  wrote, so no row pins a shape the registrar or the router does not
  emit. The preview's projection is `routing_helpers_cljs_test.cljc`'s
  subject."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.routing]
            [day8.re-frame2-xray.panels.routing-helpers :as h]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.routes.row-expand :as row-expand]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture))

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

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

(defn- host-route-slice
  "The route slice in `:rf/default`'s runtime-db, where a real navigation
  writes it."
  []
  (get-in (rf.frame/frame-runtime-db-value :rf/default)
          [:rf.runtime/routing :current]))

;; ---- the row expand the preview opens from ------------------------------

(deftest row-expand-reads-what-reg-route-stores-rf2-y8doi-22
  (testing "a route registered through the real `reg-route` shows its capture
            names (the compiled form's `:names`) and an open-in-editor chip
            off the standard `:file` / `:line` coords"
    (setup-xray-frame!)
    (rf/reg-route ::chapter {} "/simulate-nav-test/books/:book/chapters/:chapter")
    (let [routes (select-keys (rf/registrations {:source :store :kind :route})
                              [::chapter])
          row    (first (h/project-routes routes))]
      (rf/with-frame :rf/xray
        (let [tree (row-expand/render identity row {:sim-open? false :routes-map routes})]
          (is (= [true true]
                 (map #(some? (find-by-testid tree %))
                      [(str "rf-xray-static-routes-keys-" (subs (pr-str ::chapter) 1))
                       "xray-open-in-editor"]))))))))

(deftest row-expand-jump-chip-promises-only-the-lens-rf2-y8doi-22
  (testing "the `→ Dynamic` chip flips to the Dynamic Routing lens and does
            not scope it to the row, so its title does not say it does"
    (let [routes {:route/cart {:path "/cart"}}
          tree   (row-expand/render identity (first (h/project-routes routes))
                                    {:sim-open? false :routes-map routes})]
      (is (= "Open the Dynamic Routing lens"
             (:title (second (find-by-testid
                               tree "rf-xray-static-routes-jump-runtime-route/cart"))))))))

;; ---- hermetic posture ---------------------------------------------------

(deftest panel-sim-nav-does-not-touch-app-db
  (testing "opening the Simulate-navigation preview leaves the host frame's
            route slice untouched"
    (setup-xray-frame!)
    (rf/reg-route ::old {} "/simulate-nav-test/old/:slug")
    (rf/dispatch-sync [:rf.route/navigate {:to ::old :params {:slug "old"}}]
                      {:frame :rf/default})
    (let [slice (host-route-slice)]
      (is (= ::old (:route-id slice)) "PRECONDITION: the navigation landed")
      ;; The preview targets a different route than the live slice, so a
      ;; real navigation would change it.
      (doseq [ev [[:rf.xray.static.routes/toggle-row :route/cart]
                  [:rf.xray.static.routes/toggle-sim-nav :route/cart]]]
        (rf/dispatch-sync ev {:frame :rf/xray}))
      (is (= slice (host-route-slice))))))
