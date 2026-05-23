(ns day8.re-frame2-causa.panels.routing-cljs-test
  "CLJS-side wiring + view tests for Causa's Dynamic Routing tab —
  the three-section stack (rf2-ad7zx.7, reconciled to RoutesPanel.tsx
  + spec/021 §7.2; refining rf2-3kjlo / rf2-o5f5f.3).

  ## Scope (post-rf2-ad7zx.7)

  The Dynamic Routing tab renders three stacked sections per spec/021
  §7.2, top → bottom:

    1. CURRENT ROUTE          — active id (mode-accent, bold) + params
                                + matched path. Always shown.
    2. NAVIGATION THIS EPOCH  — FROM ──► TO + params + outcome chip
                                (coloured by result). Quiet caption
                                ('No route activity in this epoch.')
                                when the focused event isn't a nav.
    3. ROUTE TABLE            — the full registered route graph as a
                                tree (always visible per the topology-
                                plus-overlay contract); current row
                                highlighted + `◀ current` marker;
                                FROM/TO overlay glyphs on matching rows.

  The browse + search + Simulate-URL surface lives on the Static
  Routes panel (see `static/routes/panel_cljs_test.cljs`).

  ## What's under test

    1. **Registry wires the subs** — every sub the panel reads gets
       installed by `register-causa-handlers!`.

    2. **Three sections always render** — when routes are registered,
       CURRENT ROUTE + NAVIGATION THIS EPOCH + ROUTE TABLE all render.

    3. **Route table always renders** — every registered route gets a
       table row regardless of focused-epoch activity.

    4. **Per-epoch overlay** — when the focused cascade carries a
       `:rf.route.nav-token/allocated` emit, the destination row gets a
       `:to` marker, the NAVIGATION section surfaces FROM ──► TO + an
       outcome chip, and the prior route (when distinct) gets `:from`.

    5. **No-activity branch** — when focused cascade has no routing
       trace events, NAVIGATION reads the empty caption, CURRENT ROUTE
       + ROUTE TABLE still render, and the current row is highlighted.

    6. **Silent state** — when no routes registered, panel renders the
       silent-by-default caption (no sections).

    7. **Frame isolation** — every read targets `:rf/causa`'s frame."
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
  (test-support/make-reset-runtime-fixture
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

(defn- node-text
  "Concatenate every string descendant of a hiccup node — used to
  assert on a row's rendered text without coupling to its exact
  child structure."
  [node]
  (->> (hiccup-seq node)
       (filter string?)
       (apply str)))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- fixture builders ---------------------------------------------------

(def cart-routes
  {:route/cart      {:path "/cart"      :doc "cart"}
   :route/checkout  {:path "/checkout"  :doc "checkout"}
   :route/payment   {:path "/checkout/payment"
                     :parent :route/checkout}
   :route/confirm   {:path "/checkout/confirm"
                     :parent :route/checkout
                     :on-match [:confirm/load]}})

(defn- nav-allocated [route-id]
  {:id        99
   :op-type   :rf.event
   :operation :rf.route.nav-token/allocated
   :tags      {:route-id route-id :nav-token "nav-1"}})

;; ---- (1) registry wiring + tab inventory --------------------------------

(deftest registry-installs-routing-subs
  (testing "register-causa-handlers! installs the topology-plus-overlay subs"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/registered-routes))
        ":rf.causa/registered-routes sub registered (shared with Static)")
    (is (some? (registrar/handler :sub :rf.causa/registered-routes-override))
        "test-only override sub registered")
    (is (some? (registrar/handler :sub :rf.causa/current-route-slice))
        ":rf.causa/current-route-slice sub registered")
    (is (some? (registrar/handler :sub :rf.causa/current-route-slice-override))
        "test-only override sub registered")
    (is (some? (registrar/handler :sub :rf.causa/routing-tab-data))
        "view-facing topology-plus-overlay composite sub registered")
    (is (some? (registrar/handler :event :rf.causa/set-registered-routes-override-for-test))
        "test-only override event registered")
    (is (some? (registrar/handler :event :rf.causa/set-current-route-slice-override-for-test))
        "test-only override event registered"))
  (testing "rf2-o5f5f.3 — browse + search + Simulate-URL slots NO LONGER live
            under :rf.causa.routing/* (promoted to :rf.causa.static.routes/*)"
    (registry/register-causa-handlers!)
    (is (nil? (registrar/handler :sub :rf.causa.routing/query))
        ":rf.causa.routing/query removed (moved to static.routes/query)")
    (is (nil? (registrar/handler :sub :rf.causa.routing/sim-url))
        ":rf.causa.routing/sim-url removed (moved to static.routes/sim-url)")
    (is (nil? (registrar/handler :sub :rf.causa.routing/expanded))
        ":rf.causa.routing/expanded removed (moved to static.routes/expanded)")
    (is (nil? (registrar/handler :event :rf.causa.routing/set-query))
        ":rf.causa.routing/set-query removed (moved to static.routes/set-query)")
    (is (nil? (registrar/handler :event :rf.causa.routing/set-sim-url))
        ":rf.causa.routing/set-sim-url removed (moved to static.routes/set-sim-url)")
    (is (nil? (registrar/handler :event :rf.causa.routing/toggle-row))
        ":rf.causa.routing/toggle-row removed (moved to static.routes/toggle-row)")))

(deftest palette-includes-routing
  (testing "the palette's canonical panel list carries the :routing entry"
    (let [panels (palette-subs/palette-panels)
          ids    (set (map :id panels))]
      (is (contains? ids :routing) ":routing in palette-panels")
      (is (= 7 (count panels))
          "exactly 7 entries — Event / App DB / Views / Trace / Machines / Routing / Issues (rf2-4v67l removed the Chrome A11y dogfood in favour of Story's shipped panel; rf2-ga16q removed the Machines Canvas tab — its browse-all canvas relocated to the Static Machines sub-tab)"))))

;; ---- (2) three sections render (always-visible base layer) --------------

(deftest panel-renders-three-sections-when-routes-registered
  (testing "CURRENT ROUTE + NAVIGATION + ROUTE TABLE all render top → bottom"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa/set-current-route-slice-override-for-test
                         {:id :route/cart :params {} :query {}}]
                        {:frame :rf/causa})
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-causa-routing"))
            "panel root present")
        (is (some? (find-by-testid tree "rf-causa-routing-header"))
            "header always renders")
        ;; rf2-ezx8w · spec/021 §17.1.5 — per-panel header icon.
        (is (some? (find-by-testid tree "rf-causa-routing-panel-icon"))
            "panel header icon (🌐) present")
        ;; §1 CURRENT ROUTE
        (is (some? (find-by-testid tree "rf-causa-routing-current"))
            "§1 CURRENT ROUTE section renders")
        (is (some? (find-by-testid tree "rf-causa-routing-current-id"))
            "current route id renders")
        ;; §2 NAVIGATION THIS EPOCH
        (is (some? (find-by-testid tree "rf-causa-routing-nav"))
            "§2 NAVIGATION THIS EPOCH section renders")
        ;; §3 ROUTE TABLE
        (is (some? (find-by-testid tree "rf-causa-routing-table"))
            "§3 ROUTE TABLE section renders")
        ;; Each route gets a table row.
        (doseq [rid (keys cart-routes)]
          (is (some? (find-by-testid tree
                       (str "rf-causa-routing-table-row-" (name rid))))
              (str "route-table row rendered for " rid)))))))

(deftest current-route-section-shows-id-params-path
  (testing "§1 surfaces the active id, params, and matched path"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa/set-current-route-slice-override-for-test
                         {:id :route/cart :params {:id 42} :path "/cart"}]
                        {:frame :rf/causa})
      (let [tree (routing/Panel)
            id   (find-by-testid tree "rf-causa-routing-current-id")
            params (find-by-testid tree "rf-causa-routing-current-params")
            path (find-by-testid tree "rf-causa-routing-current-path")]
        (is (some? id) "current id present")
        (is (re-find #":route/cart" (node-text id)) "id text shows :route/cart")
        (is (some? params) "current params present")
        (is (re-find #":id 42" (node-text params)) "params text shows {:id 42}")
        (is (some? path) "matched path present")
        (is (re-find #"/cart" (node-text path)) "path text shows /cart")))))

(deftest panel-renders-silent-when-no-routes
  (testing "no routes registered → silent caption + no sections"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test {}]
                        {:frame :rf/causa})
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-causa-routing"))
            "panel root present")
        (is (some? (find-by-testid tree "rf-causa-routing-header"))
            "header still renders")
        (is (some? (find-by-testid tree "rf-causa-routing-silent"))
            "silent caption rendered for empty registrar")
        (is (nil? (find-by-testid tree "rf-causa-routing-table"))
            "ROUTE TABLE NOT rendered when no routes registered")
        (is (nil? (find-by-testid tree "rf-causa-routing-current"))
            "CURRENT ROUTE NOT rendered when silent")
        (is (nil? (find-by-testid tree "rf-causa-routing-nav"))
            "NAVIGATION NOT rendered when silent")))))

;; ---- (3) no-activity branch (focused epoch with no routing trace) -------

(deftest panel-renders-no-activity-when-cascade-has-no-routing
  (testing "no routing trace events → CURRENT ROUTE + ROUTE TABLE render; NAVIGATION quiet; current row highlighted"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa/set-current-route-slice-override-for-test
                         {:id :route/cart :params {} :query {}}]
                        {:frame :rf/causa})
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-causa-routing-current"))
            "CURRENT ROUTE renders")
        (is (some? (find-by-testid tree "rf-causa-routing-table"))
            "ROUTE TABLE renders unconditionally")
        (is (some? (find-by-testid tree "rf-causa-routing-no-activity"))
            "NAVIGATION reads 'No route activity in this epoch.'")
        ;; current-route row highlight (:here marker → mode-accent row).
        (is (some? (find-by-testid tree "rf-causa-routing-table-current-marker"))
            "current route gets the '◀ current' marker when no nav this epoch")
        (is (nil? (find-by-testid tree "rf-causa-routing-nav-outcome"))
            "no outcome chip when no activity")))))

;; ---- (4) per-epoch overlay (focused cascade with nav-token emit) --------

(deftest panel-paints-to-marker-and-outcome-when-cascade-navigated
  (testing "nav-token emit → :to marker on the table row + NAVIGATION FROM/TO + transitioned outcome"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      (rf/dispatch-sync [:rf.causa/set-current-route-slice-override-for-test
                         {:id     :route/confirm
                          :params {:order-id "ord-1234"}
                          :query  {:source "cart"}}]
                        {:frame :rf/causa})
      (let [nav-event (nav-allocated :route/confirm)
            buffer [{:id 99 :op-type :rf.event :operation :rf.event/dispatched
                     :tags {:rf.trace/dispatch-id 99
                            :rf.event/v [:rf.route/navigate :route/confirm]}}
                    (assoc nav-event :tags
                           (assoc (:tags nav-event) :rf.trace/dispatch-id 99))]]
        (rf/dispatch-sync [:rf.causa/sync-trace-buffer buffer]
                          {:frame :rf/causa})
        (rf/dispatch-sync [:rf.causa/focus-cascade 99 nil] {:frame :rf/causa}))
      (let [tree (routing/Panel)]
        ;; ROUTE TABLE still renders.
        (is (some? (find-by-testid tree "rf-causa-routing-table")))
        ;; :to overlay glyph present on the destination table row.
        (is (some? (find-by-testid tree "rf-causa-routing-table-marker-to"))
            ":to overlay glyph rendered on destination route in the table")
        ;; Header summary surfaces the TO id.
        (is (some? (find-by-testid tree "rf-causa-routing-nav-summary"))
            "header carries → TO summary chip")
        ;; NAVIGATION section surfaces FROM ──► TO + outcome.
        (is (some? (find-by-testid tree "rf-causa-routing-nav-to"))
            "NAVIGATION TO id rendered")
        (is (some? (find-by-testid tree "rf-causa-routing-nav-outcome"))
            "NAVIGATION outcome chip rendered")
        (let [outcome (find-by-testid tree "rf-causa-routing-nav-outcome")]
          (is (re-find #"transitioned" (node-text outcome))
              "outcome reads 'transitioned' for an :on-match nav"))
        (is (nil? (find-by-testid tree "rf-causa-routing-no-activity"))
            "empty-state caption NOT rendered when activity present")))))

(deftest panel-paints-from-and-to-when-prior-slice-differs
  (testing "distinct prior slice → both :from and :to markers + FROM in NAVIGATION"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      ;; Prior slice = :route/cart; navigate to :route/confirm.
      (rf/dispatch-sync [:rf.causa/set-current-route-slice-override-for-test
                         {:id :route/cart :params {} :query {}}]
                        {:frame :rf/causa})
      (let [nav-event (nav-allocated :route/confirm)
            buffer [{:id 99 :op-type :rf.event :operation :rf.event/dispatched
                     :tags {:rf.trace/dispatch-id 99
                            :rf.event/v [:rf.route/navigate :route/confirm]}}
                    (assoc nav-event :tags
                           (assoc (:tags nav-event) :rf.trace/dispatch-id 99))]]
        (rf/dispatch-sync [:rf.causa/sync-trace-buffer buffer]
                          {:frame :rf/causa})
        (rf/dispatch-sync [:rf.causa/focus-cascade 99 nil] {:frame :rf/causa}))
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-causa-routing-table-marker-from"))
            ":from overlay glyph rendered on origin route in the table")
        (is (some? (find-by-testid tree "rf-causa-routing-table-marker-to"))
            ":to overlay glyph rendered on destination route in the table")
        (is (some? (find-by-testid tree "rf-causa-routing-nav-from"))
            "NAVIGATION FROM id rendered")
        (let [from (find-by-testid tree "rf-causa-routing-nav-from")]
          (is (re-find #":route/cart" (node-text from))
              "FROM reads the prior route :route/cart"))))))
