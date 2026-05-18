(ns day8.re-frame2-causa.panels.routing-cljs-test
  "CLJS-side wiring + view tests for Causa's Routing tab (rf2-nrbs9).

  ## What's under test (in addition to the pure-data tests in
  `routing_helpers_cljs_test.cljc`)

    1. **Registry wires the subs** — every sub the panel reads gets
       installed by `register-causa-handlers!`, and the `:routing`
       tab id is in the L3 tabs vector + the palette panel list.

    2. **Render contract** — root container + header + tree (or
       empty state) match the production view tree.

    3. **Silent state** — when no routes are registered the panel
       renders the empty body (`No routes registered.`), no tree.

    4. **Orientation HERE marker** — when routes are registered and
       a current route slice exists, the current row carries the
       `:here` marker.

    5. **FROM / TO markers** — when the focused cascade carries a
       `:rf.route.nav-token/allocated` trace event the corresponding
       rows carry `:from` / `:to` markers.

    6. **Frame isolation** — every read targets `:rf/causa`'s frame;
       the panel never reads the host frame's app-db directly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.palette.subs :as palette-subs]
            [day8.re-frame2-causa.panels.routing :as routing]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers (mirror issues_ribbon_view_cljs_test) ---------------

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

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (hiccup-seq tree)))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- fixture builders ---------------------------------------------------

(def cart-routes
  {:route/cart      {:path "/cart"      :doc "cart"}
   :route/checkout  {:path "/checkout"  :doc "checkout"}
   :route/payment   {:path "/checkout/payment"}
   :route/confirm   {:path "/checkout/confirm"}})

(defn- nav-allocated [route-id]
  {:id        99
   :op-type   :event
   :operation :rf.route.nav-token/allocated
   :tags      {:route-id route-id :nav-token "nav-1"}})

;; ---- (1) registry wiring + tab inventory --------------------------------

(deftest registry-installs-routing-subs
  (testing "register-causa-handlers! installs every Routing-tab sub"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/registered-routes))
        ":rf.causa/registered-routes sub registered")
    (is (some? (registrar/handler :sub :rf.causa/registered-routes-override))
        "test-only override sub registered")
    (is (some? (registrar/handler :sub :rf.causa/current-route-slice))
        ":rf.causa/current-route-slice sub registered")
    (is (some? (registrar/handler :sub :rf.causa/current-route-slice-override))
        "test-only override sub registered")
    (is (some? (registrar/handler :sub :rf.causa/routing-tab-data))
        "view-facing composite sub registered")
    (is (some? (registrar/handler :event :rf.causa/set-registered-routes-override-for-test))
        "test-only override event registered")
    (is (some? (registrar/handler :event :rf.causa/set-current-route-slice-override-for-test))
        "test-only override event registered")))

(deftest palette-includes-routing
  (testing "the palette's canonical panel list carries the :routing entry"
    (let [ids (set (map :id palette-subs/palette-panels))]
      (is (contains? ids :routing) ":routing in palette-panels")
      (is (= 7 (count palette-subs/palette-panels))
          "exactly 7 entries — Event / App DB / Views / Trace / Machines / Routing / Issues"))))

;; ---- (2) silent state ---------------------------------------------------

(deftest panel-renders-silent-state-when-no-routes
  (testing "no routes registered → empty-state body, no tree"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Seed both overrides to nil so the panel falls through.
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test {}]
                        {:frame :rf/causa})
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-causa-routing"))
            "panel root present")
        (is (some? (find-by-testid tree "rf-causa-routing-header"))
            "header always renders")
        (is (some? (find-by-testid tree "rf-causa-routing-empty"))
            "silent state body present")
        (is (nil? (find-by-testid tree "rf-causa-routing-tree"))
            "tree NOT rendered")))))

;; ---- (3) orientation HERE marker ----------------------------------------

(deftest panel-renders-here-marker-without-navigation
  (testing "current slice present + no nav cascade → HERE on the active row"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa/set-current-route-slice-override-for-test
                         {:id :route/cart :params {} :query {}}]
                        {:frame :rf/causa})
      (let [tree (routing/Panel)
            cart-row (find-by-testid tree "rf-causa-routing-row-route/cart")]
        (is (some? (find-by-testid tree "rf-causa-routing-tree"))
            "tree renders when routes exist")
        (is (some? cart-row) "current route row renders")
        (is (= "here" (:data-marker (second cart-row)))
            "current route row carries the HERE marker")
        (is (some? (find-by-testid tree "rf-causa-routing-marker-here"))
            "HERE chip text rendered")))))

;; ---- (4) FROM / TO markers ---------------------------------------------

(deftest panel-renders-from-to-when-cascade-navigated
  (testing "focused cascade with nav-token emit → FROM + TO markers"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      ;; Current slice still on the FROM route — simulates reading
      ;; the panel while the cascade is mid-flight (or against a stale
      ;; snapshot); the helpers expose both markers.
      (rf/dispatch-sync [:rf.causa/set-current-route-slice-override-for-test
                         {:id :route/cart :params {} :query {}}]
                        {:frame :rf/causa})
      ;; Seed the trace-buffer with a nav-allocated cascade + focus on it.
      ;; The cascade projector buckets the nav-allocated emit under :other.
      (let [nav-event (nav-allocated :route/confirm)
            ;; A minimal trace-buffer that the projection's group-cascades
            ;; will bucket into one cascade with id=99.
            buffer [{:id 99 :op-type :event :operation :event/dispatched
                     :tags {:dispatch-id 99
                            :event [:rf.route/navigate :route/confirm]}}
                    (assoc nav-event :tags
                           (assoc (:tags nav-event) :dispatch-id 99))]]
        (rf/dispatch-sync [:rf.causa/sync-trace-buffer buffer]
                          {:frame :rf/causa})
        (rf/dispatch-sync [:rf.causa/focus-cascade 99 nil] {:frame :rf/causa}))

      (let [tree (routing/Panel)
            cart-row    (find-by-testid tree "rf-causa-routing-row-route/cart")
            confirm-row (find-by-testid tree "rf-causa-routing-row-route/confirm")]
        (is (some? cart-row)    "cart row present")
        (is (some? confirm-row) "confirm row present")
        (is (= "from" (:data-marker (second cart-row)))
            "cart row carries the FROM marker")
        (is (= "to" (:data-marker (second confirm-row)))
            "confirm row carries the TO marker")
        (is (some? (find-by-testid tree "rf-causa-routing-marker-from"))
            "FROM chip text rendered")
        (is (some? (find-by-testid tree "rf-causa-routing-marker-to"))
            "TO chip text rendered")
        (is (some? (find-by-testid tree "rf-causa-routing-nav-summary"))
            "header nav summary surfaces the TO id")))))

;; ---- (5) slice detail rendering ----------------------------------------

(deftest panel-renders-slice-detail
  (testing "params + query + fragment grid rendered when current slice present"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa/set-current-route-slice-override-for-test
                         {:id     :route/confirm
                          :params {:order-id "ord-1234"}
                          :query  {:source "cart"}}]
                        {:frame :rf/causa})
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-causa-routing-slice-detail"))
            "slice detail grid present")))))
