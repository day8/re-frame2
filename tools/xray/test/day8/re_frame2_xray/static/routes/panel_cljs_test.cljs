(ns day8.re-frame2-xray.static.routes.panel-cljs-test
  "Node-lane view tests for Xray's Static Routes panel — the BROWSE verb:
  flat list, search, Simulate-URL, per-row inline expand, the hermetic
  Simulate-navigation preview and the cross-link to Dynamic Routing."
  (:require [cljs.test :refer-macros [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.routes.panel :as panel]
            [day8.re-frame2-xray.test-helpers.static-shell-tree
             :as static-shell-tree]
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
    (let [result (apply (first node) (rest node))]
      ;; A form-2 component (the EDN widget is one) answers its render fn;
      ;; call it with the same args to reach the hiccup.
      (expand-children
        (if (fn? result)
          (apply result (rest node))
          result)))
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

(defn- find-all-by-role [tree role]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (= role (:role (second node)))))
          (hiccup-seq tree)))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

;; ---- the node lane's door onto the panel --------------------------------

(defn- panel-tree
  "The hiccup `panel/Panel` renders. `Panel` is an `rf.fresco/defview`
  boundary whose body runs only inside a React render window, so this
  reproduces its four reads, in its order, and hands their values to
  `panel/panel-tree`. `dispatch` defaults to the boundary's own door,
  `(:dispatch (rf/capture-frame))`; `identity` stands in for the
  `as-child` seam, whose crossing is the browser lane's subject.

  Call it inside `(rf/with-frame :rf/xray …)` — it subscribes ambiently."
  ([] (panel-tree (:dispatch (rf/capture-frame))))
  ([dispatch]
   (let [data       @(rf/subscribe [:rf.xray.static.routes/tab-data])
         expanded   @(rf/subscribe [:rf.xray.static.routes/expanded])
         sim-open   @(rf/subscribe [:rf.xray.static.routes/sim-nav-open])
         routes-map @(rf/subscribe [:rf.xray/registered-routes])]
     (panel/panel-tree data
                       {:expanded   expanded
                        :sim-open   sim-open
                        :routes-map routes-map}
                       dispatch
                       identity))))

(defn- set-routes! [routes]
  (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test routes]
                    {:frame :rf/xray}))

(def cart-routes
  {:route/cart      {:path "/cart"      :doc "shopping cart"}
   :route/checkout  {:path "/checkout"  :doc "checkout"}
   :route/payment   {:path "/checkout/payment"}
   :route/confirm   {:path "/checkout/confirm"
                     :parent :route/checkout
                     :on-match [:confirm/load]}})

(defn- present? [tree testids]
  (map #(some? (find-by-testid tree %)) testids))

;; ---- silent state -------------------------------------------------------

(deftest panel-renders-silent-state-when-no-routes
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (set-routes! {})
    (let [tree (panel-tree)]
      (is (some? (find-by-testid tree "rf-xray-static-routes-empty")))
      (is (nil? (find-by-testid tree "rf-xray-static-routes-sim"))
          "the Simulate-URL header is not rendered when silent"))))

;; ---- search filter ------------------------------------------------------

(deftest panel-search-filters-the-list
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (set-routes! cart-routes)
    (rf/dispatch-sync [:rf.xray.static.routes/set-query "checkout"] {:frame :rf/xray})
    (is (= #{"rf-xray-static-routes-row-route/checkout"
             "rf-xray-static-routes-row-route/payment"
             "rf-xray-static-routes-row-route/confirm"}
           (set (map #(:data-testid (second %))
                     (find-all-by-testid-prefix (panel-tree) "rf-xray-static-routes-row-"))))
        "only routes whose route-id, path or doc contains the query render")
    (rf/dispatch-sync [:rf.xray.static.routes/set-query "zzz-not-found"] {:frame :rf/xray})
    (is (some? (find-by-testid (panel-tree) "rf-xray-static-routes-empty-filtered"))
        "a query matching nothing renders the empty-filtered state")))

;; ---- Simulate-URL -------------------------------------------------------

(deftest simulate-url-header-follows-the-input
  (testing "the result block and clear button track the input, the matching
            route's candidate row is flagged winner, and a URL matching
            nothing still shows the result block"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (set-routes! cart-routes)
      (is (some? (find-by-testid (panel-tree) "rf-xray-static-routes-sim-input")))
      (are [url expected]
           (= expected
              (do (rf/dispatch-sync [:rf.xray.static.routes/set-sim-url url]
                                    {:frame :rf/xray})
                  (let [tree (panel-tree)]
                    {:clear?  (some? (find-by-testid tree "rf-xray-static-routes-sim-clear"))
                     :result? (some? (find-by-testid tree "rf-xray-static-routes-sim-result"))
                     :winner  (some-> (find-by-testid
                                        tree "rf-xray-static-routes-sim-candidate-route/cart")
                                      second
                                      :data-winner)})))
        "/cart"         {:clear? true  :result? true  :winner "true"}
        "/no-such-path" {:clear? true  :result? true  :winner nil}
        ""              {:clear? false :result? false :winner nil}))))

;; ---- per-row inline expand ----------------------------------------------

(deftest panel-row-expand-toggle
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (set-routes! cart-routes)
    (rf/dispatch-sync [:rf.xray.static.routes/toggle-row :route/cart] {:frame :rf/xray})
    (is (= [true true]
           (present? (panel-tree) ["rf-xray-static-routes-meta-route/cart"
                                   "rf-xray-static-routes-sim-nav-toggle-route/cart"]))
        "the expand surface unfolds with the registrar meta and the
         Simulate-navigation toggle")
    (rf/dispatch-sync [:rf.xray.static.routes/toggle-row :route/cart] {:frame :rf/xray})
    (is (nil? (find-by-testid (panel-tree) "rf-xray-static-routes-expand-route/cart"))
        "a second toggle folds it")))

(deftest panel-expand-surfaces-schema-when-present
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (set-routes! {:route/article {:path   "/articles/:slug"
                                  :params [:map [:slug :string]]
                                  :query  [:map [:page :int]]}})
    (rf/dispatch-sync [:rf.xray.static.routes/toggle-row :route/article] {:frame :rf/xray})
    (is (= [true true]
           (present? (panel-tree) ["rf-xray-static-routes-params-schema-route/article"
                                   "rf-xray-static-routes-query-schema-route/article"])))))

;; ---- hermetic Simulate-navigation preview -------------------------------

(deftest panel-sim-nav-preview-matches-the-simulate-url-input
  (testing "the preview's URL and Params rows match the Simulate-URL input
            against the previewed row's pattern"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (set-routes! {:route/article {:path "/articles/:slug"}})
      (doseq [ev [[:rf.xray.static.routes/set-sim-url "/articles/intro"]
                  [:rf.xray.static.routes/toggle-row :route/article]
                  [:rf.xray.static.routes/toggle-sim-nav :route/article]]]
        (rf/dispatch-sync ev {:frame :rf/xray}))
      (let [tree (panel-tree)
            text #(->> (hiccup-seq (find-by-testid tree %))
                       (filter string?)
                       (apply str))]
        (is (= ["/articles/intro  (matched)" (pr-str {:slug "intro"})]
               (map text ["rf-xray-static-routes-sim-nav-url"
                          "rf-xray-static-routes-sim-nav-params"])))))))

;; ---- cross-link to Dynamic Routing --------------------------------------

(deftest panel-jump-to-dynamic-flips-mode-and-tab
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-mode :static] {:frame :rf/xray})
    (rf/dispatch-sync [:rf.xray.static.routes/jump-to-dynamic :route/cart]
                      {:frame :rf/xray})
    (is (= [:dynamic :routing]
           [@(rf/subscribe [:rf.xray/mode]) @(rf/subscribe [:rf.xray/selected-tab])]))))

;; ---- Static tab inventory -----------------------------------------------

(deftest static-shell-routes-tab-is-in-inventory
  (testing "the Static shell's :routes slot mounts the registry's :panel.
            The walk stops at that bridge's `[:>]` head; the panel's own
            first paint is the browser lane's subject"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray.static/select-tab :routes] {:frame :rf/xray})
      (is (= ((:panel (panel-registry/tab-by-id :static :routes)))
             (last (find-by-testid (static-shell-tree/surface-tree)
                                   "rf-xray-static-detail-panel-routes")))))))

;; ---- a11y list semantics + keyboard operability -------------------------

(deftest routes-list-rows-keyboard-operable
  (testing "the list is role=list with one role=listitem per route; each row
            body is a focusable role=button, and Enter / Space consume the key
            and fire that row's toggle while other keys bubble"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (set-routes! cart-routes)
      (let [seen   (atom [])
            tree   (panel-tree #(swap! seen conj %))
            bodies (filter #(= "0" (:tab-index (second %)))
                           (find-all-by-role tree "button"))
            cart   (some #(when (.includes (:aria-label (second %)) ":route/cart") %)
                         bodies)
            press  (fn [k]
                     (let [prevented (atom false)]
                       (reset! seen [])
                       ((:on-key-down (second cart))
                        #js {:key k :preventDefault #(reset! prevented true)})
                       [@prevented @seen]))
            n      (count cart-routes)
            toggle [[:rf.xray.static.routes/toggle-row :route/cart]]]
        (is (= "list" (:role (second (find-by-testid tree "rf-xray-static-routes-list")))))
        (is (= [n n] [(count (find-all-by-role tree "listitem")) (count bodies)])
            "one listitem and one focusable button body per route")
        (is (every? #(contains? (second %) :aria-expanded) bodies))
        (are [k expected] (= expected (press k))
          "Enter" [true toggle]
          " "     [true toggle]
          "a"     [false []])))))
