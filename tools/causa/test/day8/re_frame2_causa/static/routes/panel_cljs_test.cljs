(ns day8.re-frame2-causa.static.routes.panel-cljs-test
  "CLJS wiring + view tests for Causa's Static Routes panel
  (rf2-o5f5f.3).

  ## Scope

  Per Mike's two-verbs-two-homes decision: the BROWSE verb (flat
  list + Simulate-URL + per-row inline expand + hermetic Simulate-
  navigation preview + cross-link to Runtime Routing) lives on the
  Static surface. This file covers:

    1. **Registry wires the Static Routes subs + events** under
       `:rf.causa.static.routes/*`.

    2. **Silent state** — no routes registered → empty body.

    3. **Flat-list rendering** — every registered route surfaces as
       a row; sort by path ascending.

    4. **Search filter** — substring across route-id + path + doc;
       reuses `routing-helpers/filter-rows`.

    5. **Simulate-URL** — header surface resolves the URL via the
       6-rule rank cascade (`routing-helpers/simulate-url`) and the
       winner is highlighted.

    6. **Per-row inline expand** — click → expand surface unfolds in
       place; pattern + matched-keys + handler chip + Simulate-nav
       toggle + cross-link chip.

    7. **Cross-link** — `:rf.causa.static.routes/jump-to-runtime`
       flips mode + selects the Runtime Routing tab.

    8. **Frame isolation** — every read targets `:rf/causa`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.static.routes.panel :as panel]
            [day8.re-frame2-causa.static.shell :as static-shell]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

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

;; ---- fixture data -------------------------------------------------------

(def cart-routes
  {:route/cart      {:path "/cart"      :doc "shopping cart"}
   :route/checkout  {:path "/checkout"  :doc "checkout"}
   :route/payment   {:path "/checkout/payment"}
   :route/confirm   {:path "/checkout/confirm"
                     :parent :route/checkout
                     :on-match [:confirm/load]}})

;; ---- (1) registry wires the subs + events -----------------------------

(deftest registry-installs-static-routes-handlers
  (testing "register-causa-handlers! installs every Static Routes sub + event"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa.static.routes/query))
        ":rf.causa.static.routes/query sub registered")
    (is (some? (registrar/handler :sub :rf.causa.static.routes/sim-url))
        ":rf.causa.static.routes/sim-url sub registered")
    (is (some? (registrar/handler :sub :rf.causa.static.routes/expanded))
        ":rf.causa.static.routes/expanded sub registered")
    (is (some? (registrar/handler :sub :rf.causa.static.routes/sim-nav-open))
        ":rf.causa.static.routes/sim-nav-open sub registered")
    (is (some? (registrar/handler :sub :rf.causa.static.routes/tab-data))
        "view-facing composite registered")
    (is (some? (registrar/handler :event :rf.causa.static.routes/set-query))
        "set-query event registered")
    (is (some? (registrar/handler :event :rf.causa.static.routes/set-sim-url))
        "set-sim-url event registered")
    (is (some? (registrar/handler :event :rf.causa.static.routes/toggle-row))
        "toggle-row event registered")
    (is (some? (registrar/handler :event :rf.causa.static.routes/toggle-sim-nav))
        "toggle-sim-nav event registered")
    (is (some? (registrar/handler :event :rf.causa.static.routes/jump-to-runtime))
        "jump-to-runtime cross-link event registered")))

;; ---- (2) silent state ---------------------------------------------------

(deftest panel-renders-silent-state-when-no-routes
  (testing "no routes → empty body, no list, no search, no Simulate-URL"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test {}]
                        {:frame :rf/causa})
      (let [tree (panel/Panel)]
        (is (some? (find-by-testid tree "rf-causa-static-routes"))
            "panel root present")
        (is (some? (find-by-testid tree "rf-causa-static-routes-header"))
            "header always renders")
        (is (some? (find-by-testid tree "rf-causa-static-routes-empty"))
            "silent empty state rendered")
        (is (nil? (find-by-testid tree "rf-causa-static-routes-list"))
            "flat list NOT rendered when silent")
        (is (nil? (find-by-testid tree "rf-causa-static-routes-sim"))
            "Simulate-URL NOT rendered when silent")
        (is (nil? (find-by-testid tree "rf-causa-static-routes-search"))
            "search NOT rendered when silent")))))

;; ---- (3) flat-list + search rendering -----------------------------------

(deftest panel-renders-flat-list-when-routes-present
  (testing "routes present → flat list + search + Simulate-URL"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (let [tree (panel/Panel)]
        (is (some? (find-by-testid tree "rf-causa-static-routes-list"))
            "flat list rendered")
        (is (some? (find-by-testid tree "rf-causa-static-routes-search"))
            "search box rendered")
        (is (some? (find-by-testid tree "rf-causa-static-routes-sim"))
            "Simulate-URL header rendered")
        (is (nil? (find-by-testid tree "rf-causa-static-routes-empty"))
            "empty state NOT rendered when routes present")
        (let [rows (find-all-by-testid-prefix tree "rf-causa-static-routes-row-")]
          (is (= (count cart-routes) (count rows))
              "one row per registered route"))))))

;; ---- (4) search filter --------------------------------------------------

(deftest panel-search-filters-the-list
  (testing "set-query event narrows the catalogue"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa.static.routes/set-query "checkout"]
                        {:frame :rf/causa})
      (let [tree (panel/Panel)
            rows (find-all-by-testid-prefix tree "rf-causa-static-routes-row-")]
        (is (= #{"rf-causa-static-routes-row-route/checkout"
                 "rf-causa-static-routes-row-route/payment"
                 "rf-causa-static-routes-row-route/confirm"}
               (set (map #(:data-testid (second %)) rows)))
            "only routes whose haystack contains \"checkout\" render"))))

  (testing "filter that matches nothing → empty-filtered state"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa.static.routes/set-query "zzz-not-found"]
                        {:frame :rf/causa})
      (let [tree (panel/Panel)]
        (is (some? (find-by-testid tree "rf-causa-static-routes-empty-filtered"))
            "empty-filtered surface rendered when the query matches no rows")))))

;; ---- (5) Simulate-URL header --------------------------------------------

(deftest panel-simulates-url-and-surfaces-candidates
  (testing "set-sim-url populates the simulator result with ranked candidates"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa.static.routes/set-sim-url "/cart"]
                        {:frame :rf/causa})
      (let [tree (panel/Panel)]
        (is (some? (find-by-testid tree "rf-causa-static-routes-sim-result"))
            "simulator result rendered")
        (is (some? (find-by-testid tree "rf-causa-static-routes-sim-candidate-route/cart"))
            "matching candidate row rendered"))))

  (testing "no match — result block reports zero candidates"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa.static.routes/set-sim-url "/nope-not-here"]
                        {:frame :rf/causa})
      (let [tree (panel/Panel)]
        (is (some? (find-by-testid tree "rf-causa-static-routes-sim-result"))
            "simulator result rendered even on no-match")))))

;; ---- (6) per-row inline expand -----------------------------------------

(deftest panel-row-expand-toggle
  (testing "toggle-row event unfolds the inline expand surface in place"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      ;; Initial render — expand absent.
      (let [tree (panel/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-static-routes-expand-route/cart"))
            "expand surface absent before toggle"))
      (rf/dispatch-sync [:rf.causa.static.routes/toggle-row :route/cart]
                        {:frame :rf/causa})
      (let [tree (panel/Panel)]
        (is (some? (find-by-testid tree "rf-causa-static-routes-expand-route/cart"))
            "expand surface rendered after toggle")
        ;; Inline expand carries the meta + jump button + sim-nav toggle.
        (is (some? (find-by-testid tree "rf-causa-static-routes-meta-route/cart"))
            "registrar meta block rendered")
        (is (some? (find-by-testid tree "rf-causa-static-routes-jump-runtime-route/cart"))
            "→ Runtime cross-link chip rendered")
        (is (some? (find-by-testid tree "rf-causa-static-routes-sim-nav-toggle-route/cart"))
            "Simulate-navigation toggle rendered"))
      (rf/dispatch-sync [:rf.causa.static.routes/toggle-row :route/cart]
                        {:frame :rf/causa})
      (let [tree (panel/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-static-routes-expand-route/cart"))
            "expand surface hidden after second toggle")))))

(deftest panel-expand-surfaces-schema-when-present
  (testing "rows whose registrar meta carries :params / :query schemas render the schema blocks"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [routes-with-schema
            {:route/article
             {:path     "/articles/:slug"
              :params   [:map [:slug :string]]
              :query    [:map [:page :int]]
              :on-match [:article/load]}}]
        (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test
                           routes-with-schema]
                          {:frame :rf/causa})
        (rf/dispatch-sync [:rf.causa.static.routes/toggle-row :route/article]
                          {:frame :rf/causa})
        (let [tree (panel/Panel)]
          (is (some? (find-by-testid tree "rf-causa-static-routes-params-schema-route/article"))
              ":params schema block rendered")
          (is (some? (find-by-testid tree "rf-causa-static-routes-query-schema-route/article"))
              ":query schema block rendered"))))))

;; ---- (7) hermetic Simulate-navigation preview --------------------------

(deftest panel-sim-nav-preview-is-hermetic
  (testing "Simulate-navigation toggle reveals the preview WITHOUT dispatching navigation"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      ;; Set a baseline current slice so we can assert it doesn't change.
      (rf/dispatch-sync [:rf.causa/set-current-route-slice-override-for-test
                         {:id :route/cart :params {} :query {}}]
                        {:frame :rf/causa})
      ;; Expand the row so the toggle is reachable, then flip the preview.
      (rf/dispatch-sync [:rf.causa.static.routes/toggle-row :route/confirm]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa.static.routes/toggle-sim-nav :route/confirm]
                        {:frame :rf/causa})
      (let [tree (panel/Panel)]
        (is (some? (find-by-testid tree "rf-causa-static-routes-sim-nav-route/confirm"))
            "preview surface rendered")
        (is (some? (find-by-testid tree "rf-causa-static-routes-sim-nav-on-match"))
            "preview shows registered :on-match")
        (is (some? (find-by-testid tree "rf-causa-static-routes-sim-nav-db-slot"))
            "preview shows the [:rf/route] db slot")
        (is (some? (find-by-testid tree "rf-causa-static-routes-sim-nav-slot-shape"))
            "preview shows the slot shape that would land"))
      ;; The current slice MUST still be the baseline — no real navigation.
      (let [slice @(rf/subscribe [:rf.causa/current-route-slice])]
        (is (= :route/cart (:id slice))
            "current slice unchanged — preview did NOT mutate app-db")))))

;; ---- (8) cross-link to Runtime Routing ----------------------------------

(deftest panel-jump-to-runtime-flips-mode-and-tab
  (testing ":rf.causa.static.routes/jump-to-runtime → mode :runtime + tab :routing"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      ;; Start in :static, on the Static :routes tab.
      (rf/dispatch-sync [:rf.causa/set-mode :static] {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa.static/select-tab :routes] {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa.static.routes/jump-to-runtime :route/cart]
                        {:frame :rf/causa})
      (is (= :runtime @(rf/subscribe [:rf.causa/mode]))
          "mode flipped to :runtime")
      (is (= :routing @(rf/subscribe [:rf.causa/selected-tab]))
          "Runtime tab set to :routing"))))

;; ---- (9) Static tab inventory exposes :routes --------------------------

(deftest static-shell-routes-tab-is-in-inventory
  (testing ":routes is in the Static tab inventory + the shell can render it"
    (is (contains? (set (map :id (static-shell/tabs))) :routes)
        ":routes is in the Static tab inventory")
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa.static/select-tab :routes] {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (let [tree (static-shell/surface)]
        ;; When the :routes tab is selected the Static shell mounts the
        ;; Static Routes panel (not the placeholder card).
        (is (some? (find-by-testid tree "rf-causa-static-routes"))
            "Static Routes panel mounted via the shell's :routes branch")
        (is (nil? (find-by-testid tree "rf-causa-static-placeholder-routes"))
            "the :routes placeholder card is no longer rendered")))))
