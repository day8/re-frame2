(ns day8.re-frame2-xray.panels.routing-cljs-test
  "CLJS-side wiring + view tests for Xray's Dynamic Routing tab —
  the three-section stack (rf2-ad7zx.7, reconciled to RoutesPanel
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
       installed by `register-xray-handlers!`.

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

    7. **Frame isolation** — every read targets `:rf/xray`'s frame."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.palette.subs :as palette-subs]
            [day8.re-frame2-xray.panels.routing :as routing]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

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

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (frame/reg-frame :rf/xray {}))

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

(defn- deactivated [route-id]
  ;; The runtime's :rf.route/deactivated lifecycle emit for the PRIOR
  ;; route on a cross-route nav (carries :tags :route-id = FROM). FROM
  ;; is read off this emit, not the live slice (rf2-m9rx6).
  {:id        98
   :op-type   :rf.event
   :operation :rf.route/deactivated
   :tags      {:route-id route-id}})

;; ---- (1) registry wiring + tab inventory --------------------------------

(deftest registry-installs-routing-subs
  (testing "register-xray-handlers! installs the topology-plus-overlay subs"
    (registry/register-xray-handlers!)
    (is (some? (registrar/handler :sub :rf.xray/registered-routes))
        ":rf.xray/registered-routes sub registered (shared with Static)")
    (is (some? (registrar/handler :sub :rf.xray/current-route-slice))
        ":rf.xray/current-route-slice sub registered")
    (is (some? (registrar/handler :sub :rf.xray/routing-tab-data))
        "view-facing topology-plus-overlay composite sub registered"))
  (testing "rf2-e8330v — production registration installs NO -for-test ids
            nor *-override subs; install-test-overrides! installs them"
    (registry/register-xray-handlers!)
    (is (nil? (registrar/handler :sub :rf.xray/registered-routes-override)))
    (is (nil? (registrar/handler :sub :rf.xray/current-route-slice-override)))
    (is (nil? (registrar/handler :event :rf.xray/set-registered-routes-override-for-test)))
    (is (nil? (registrar/handler :event :rf.xray/set-current-route-slice-override-for-test)))
    (xray-test-support/install-test-overrides!)
    (is (some? (registrar/handler :sub :rf.xray/registered-routes-override))
        "test-only override sub registered by seam")
    (is (some? (registrar/handler :sub :rf.xray/current-route-slice-override))
        "test-only override sub registered by seam")
    (is (some? (registrar/handler :event :rf.xray/set-registered-routes-override-for-test))
        "test-only override event registered by seam")
    (is (some? (registrar/handler :event :rf.xray/set-current-route-slice-override-for-test))
        "test-only override event registered by seam"))
  (testing "rf2-o5f5f.3 — browse + search + Simulate-URL slots NO LONGER live
            under :rf.xray.routing/* (promoted to :rf.xray.static.routes/*)"
    (registry/register-xray-handlers!)
    (is (nil? (registrar/handler :sub :rf.xray.routing/query))
        ":rf.xray.routing/query removed (moved to static.routes/query)")
    (is (nil? (registrar/handler :sub :rf.xray.routing/sim-url))
        ":rf.xray.routing/sim-url removed (moved to static.routes/sim-url)")
    (is (nil? (registrar/handler :sub :rf.xray.routing/expanded))
        ":rf.xray.routing/expanded removed (moved to static.routes/expanded)")
    (is (nil? (registrar/handler :event :rf.xray.routing/set-query))
        ":rf.xray.routing/set-query removed (moved to static.routes/set-query)")
    (is (nil? (registrar/handler :event :rf.xray.routing/set-sim-url))
        ":rf.xray.routing/set-sim-url removed (moved to static.routes/set-sim-url)")
    (is (nil? (registrar/handler :event :rf.xray.routing/toggle-row))
        ":rf.xray.routing/toggle-row removed (moved to static.routes/toggle-row)")))

(deftest palette-includes-routing
  (testing "the palette's canonical panel list carries the :routing entry"
    (let [panels (palette-subs/palette-panels)
          ids    (set (map :id panels))]
      (is (contains? ids :routing) ":routing in palette-panels")
      (is (contains? ids :module-view) ":module-view in palette-panels")
      (is (= 9 (count panels))
          "exactly 9 entries — Epoch / App DB / Views / Trace / Machines / Routing / Resources / Graph / Modules (the Resources tab — Spec 016 §Xray and AI tooling — earns its own L4 tab per Mike's cohesive-sub-domain ruling; rf2-9ett2d added the EP-0014 derivation-graph 'Graph' tab — the unified derivation/process graph across all algebra-view families; rf2-wtg9z4 added the EP-0013 'Modules' tab — the (realm, frame) address space + the disposition-6 demand-trigger surface; rf2-gbz39 removed the Issues tab per Mike's Option (c) ruling — issues surface inline + event-row pink-wash + the always-on issues ribbon signal. rf2-5gl5r retired the Event/Handler tab; the Epoch panel supersedes it. rf2-sc3r1 originally added Epoch at order 5; rf2-4v67l removed the Chrome A11y dogfood in favour of Story's shipped panel; rf2-ga16q removed the Machines Canvas tab — its browse-all canvas relocated to the Static Machines sub-tab.)"))))

;; ---- (2) three sections render (always-visible base layer) --------------

(deftest panel-renders-three-sections-when-routes-registered
  (testing "CURRENT ROUTE + NAVIGATION + ROUTE TABLE all render top → bottom"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/set-current-route-slice-override-for-test
                         {:route-id :route/cart :params {} :query {}}]
                        {:frame :rf/xray})
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-xray-routing"))
            "panel root present")
        ;; spec/021 §14.1 (rf2-6xezz) — every L4 panel scrubs its
        ;; self-naming heading + per-panel header icon; content opens
        ;; directly on CURRENT ROUTE (matching Figma RoutesPanel).
        (is (nil? (find-by-testid tree "rf-xray-routing-header"))
            "no panel header (heading + icon scrubbed per §14.1)")
        (is (nil? (find-by-testid tree "rf-xray-routing-panel-icon"))
            "no per-panel header icon (🌐 removed per §14.1)")
        ;; §1 CURRENT ROUTE
        (is (some? (find-by-testid tree "rf-xray-routing-current"))
            "§1 CURRENT ROUTE section renders")
        (is (some? (find-by-testid tree "rf-xray-routing-current-id"))
            "current route id renders")
        ;; §2 NAVIGATION THIS EPOCH
        (is (some? (find-by-testid tree "rf-xray-routing-nav"))
            "§2 NAVIGATION THIS EPOCH section renders")
        ;; §3 ROUTE TABLE
        (is (some? (find-by-testid tree "rf-xray-routing-table"))
            "§3 ROUTE TABLE section renders")
        ;; Each route gets a table row.
        (doseq [rid (keys cart-routes)]
          (is (some? (find-by-testid tree
                       (str "rf-xray-routing-table-row-" (name rid))))
              (str "route-table row rendered for " rid)))
        ;; Tree disclosure: :route/checkout is a parent (cart-routes nests
        ;; :route/payment + :route/confirm under it) so its row carries
        ;; the `▾` disclosure chevron (Figma ChevronRight on parent rows).
        ;; Row test-ids use (name route-id), so :route/checkout → checkout.
        (let [chevron (find-by-testid
                        tree "rf-xray-routing-table-row-checkout-chevron")]
          (is (some? chevron)
              "parent route :route/checkout renders a disclosure chevron")
          (is (re-find #"▾" (node-text chevron))
              "chevron glyph is ▾ (always-expanded tree)"))
        ;; Leaf routes carry NO chevron — the leading cell is an aligned
        ;; spacer instead.
        (is (nil? (find-by-testid
                    tree "rf-xray-routing-table-row-cart-chevron"))
            "leaf route :route/cart renders no disclosure chevron")))))

(deftest current-route-section-shows-id-params-path
  (testing "§1 surfaces the active id, params, and matched path"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/set-current-route-slice-override-for-test
                         {:route-id :route/cart :params {:id 42} :path "/cart"}]
                        {:frame :rf/xray})
      (let [tree (routing/Panel)
            id   (find-by-testid tree "rf-xray-routing-current-id")
            params (find-by-testid tree "rf-xray-routing-current-params")
            path (find-by-testid tree "rf-xray-routing-current-path")]
        (is (some? id) "current id present")
        (is (re-find #":route/cart" (node-text id)) "id text shows :route/cart")
        (is (some? params) "current params present")
        (is (re-find #":id 42" (node-text params)) "params text shows {:id 42}")
        (is (some? path) "matched path present")
        (is (re-find #"/cart" (node-text path)) "path text shows /cart")))))

(deftest panel-renders-silent-when-no-routes
  (testing "no routes registered → silent caption + no sections"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test {}]
                        {:frame :rf/xray})
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-xray-routing"))
            "panel root present")
        (is (nil? (find-by-testid tree "rf-xray-routing-header"))
            "no panel header even in the silent state (scrubbed per §14.1)")
        (is (some? (find-by-testid tree "rf-xray-routing-silent"))
            "silent caption rendered for empty registrar")
        (is (nil? (find-by-testid tree "rf-xray-routing-table"))
            "ROUTE TABLE NOT rendered when no routes registered")
        (is (nil? (find-by-testid tree "rf-xray-routing-current"))
            "CURRENT ROUTE NOT rendered when silent")
        (is (nil? (find-by-testid tree "rf-xray-routing-nav"))
            "NAVIGATION NOT rendered when silent")))))

;; ---- (3) no-activity branch (focused epoch with no routing trace) -------

(deftest panel-renders-no-activity-when-cascade-has-no-routing
  (testing "no routing trace events → CURRENT ROUTE + ROUTE TABLE render; NAVIGATION quiet; current row highlighted"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/set-current-route-slice-override-for-test
                         {:route-id :route/cart :params {} :query {}}]
                        {:frame :rf/xray})
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-xray-routing-current"))
            "CURRENT ROUTE renders")
        (is (some? (find-by-testid tree "rf-xray-routing-table"))
            "ROUTE TABLE renders unconditionally")
        (is (some? (find-by-testid tree "rf-xray-routing-no-activity"))
            "NAVIGATION reads 'No route activity in this epoch.'")
        ;; current-route row highlight (:here marker → mode-accent row).
        (is (some? (find-by-testid tree "rf-xray-routing-table-current-marker"))
            "current route gets the '◀ current' marker when no nav this epoch")
        (is (nil? (find-by-testid tree "rf-xray-routing-nav-outcome"))
            "no outcome chip when no activity")))))

;; ---- (4) per-epoch overlay (focused cascade with nav-token emit) --------

(deftest panel-paints-to-marker-and-outcome-when-cascade-navigated
  (testing "nav-token emit → :to marker on the table row + NAVIGATION FROM/TO + transitioned outcome"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/set-current-route-slice-override-for-test
                         {:id     :route/confirm
                          :params {:order-id "ord-1234"}
                          :query  {:source "cart"}}]
                        {:frame :rf/xray})
      (let [nav-event (nav-allocated :route/confirm)
            buffer [{:id 99 :op-type :rf.event :operation :rf.event/dispatched
                     :tags {:rf.trace/dispatch-id 99
                            :rf.event/v [:rf.route/navigate :route/confirm]}}
                    (assoc nav-event :tags
                           (assoc (:tags nav-event) :rf.trace/dispatch-id 99))]]
        (rf/dispatch-sync [:rf.xray/sync-trace-buffer buffer]
                          {:frame :rf/xray})
        (rf/dispatch-sync [:rf.xray/focus-event 99 nil] {:frame :rf/xray}))
      (let [tree (routing/Panel)]
        ;; ROUTE TABLE still renders.
        (is (some? (find-by-testid tree "rf-xray-routing-table")))
        ;; :to overlay glyph present on the destination table row.
        (is (some? (find-by-testid tree "rf-xray-routing-table-marker-to"))
            ":to overlay glyph rendered on destination route in the table")
        ;; The header (+ its → TO summary chip) is gone per §14.1; the
        ;; NAVIGATION THIS EPOCH section is now the sole TO surface.
        (is (nil? (find-by-testid tree "rf-xray-routing-nav-summary"))
            "no header summary chip (header scrubbed per §14.1)")
        ;; NAVIGATION section surfaces FROM ──► TO + outcome.
        (is (some? (find-by-testid tree "rf-xray-routing-nav-to"))
            "NAVIGATION TO id rendered")
        (is (some? (find-by-testid tree "rf-xray-routing-nav-outcome"))
            "NAVIGATION outcome chip rendered")
        (let [outcome (find-by-testid tree "rf-xray-routing-nav-outcome")]
          (is (re-find #"transitioned" (node-text outcome))
              "outcome reads 'transitioned' for an :on-match nav"))
        (is (nil? (find-by-testid tree "rf-xray-routing-no-activity"))
            "empty-state caption NOT rendered when activity present")))))

(deftest panel-paints-from-and-to-when-prior-slice-differs
  (testing "distinct prior slice → both :from and :to markers + FROM in NAVIGATION"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      ;; Cross-route nav cart → confirm: the cascade carries both the
      ;; nav-token/allocated emit (TO = confirm) and the deactivated emit
      ;; (FROM = cart). FROM is read off the deactivated emit, NOT the
      ;; live slice (rf2-m9rx6). The live slice is left at :route/confirm
      ;; (the post-nav value) to prove the FROM is cascade-derived.
      (rf/dispatch-sync [:rf.xray/set-current-route-slice-override-for-test
                         {:route-id :route/confirm :params {} :query {}}]
                        {:frame :rf/xray})
      (let [nav-event (nav-allocated :route/confirm)
            buffer [{:id 99 :op-type :rf.event :operation :rf.event/dispatched
                     :tags {:rf.trace/dispatch-id 99
                            :rf.event/v [:rf.route/navigate :route/confirm]}}
                    (assoc nav-event :tags
                           (assoc (:tags nav-event) :rf.trace/dispatch-id 99))
                    (assoc (deactivated :route/cart) :tags
                           (assoc (:tags (deactivated :route/cart))
                                  :rf.trace/dispatch-id 99))]]
        (rf/dispatch-sync [:rf.xray/sync-trace-buffer buffer]
                          {:frame :rf/xray})
        (rf/dispatch-sync [:rf.xray/focus-event 99 nil] {:frame :rf/xray}))
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-xray-routing-table-marker-from"))
            ":from overlay glyph rendered on origin route in the table")
        (is (some? (find-by-testid tree "rf-xray-routing-table-marker-to"))
            ":to overlay glyph rendered on destination route in the table")
        (is (some? (find-by-testid tree "rf-xray-routing-nav-from"))
            "NAVIGATION FROM id rendered")
        (let [from (find-by-testid tree "rf-xray-routing-nav-from")]
          (is (re-find #":route/cart" (node-text from))
              "FROM reads the prior route :route/cart"))))))
