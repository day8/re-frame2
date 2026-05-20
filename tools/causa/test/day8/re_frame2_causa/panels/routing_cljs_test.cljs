(ns day8.re-frame2-causa.panels.routing-cljs-test
  "CLJS-side wiring + view tests for Causa's Runtime Routing tab —
  the focused-event lens (rf2-o5f5f.3 narrows from rf2-lq0ef).

  ## Scope (post-narrow)

  Per Mike's two-verbs-two-homes decision (2026-05-19), the Runtime
  Routing tab is a focused-event lens: it surfaces the routing slice
  of the focused event's cascade — FROM/TO chips when navigation
  fired; otherwise an empty state pointing to Static Routes for the
  browse verb.

  The browse + search + Simulate-URL surface was promoted to the
  Static Routes panel (see
  `static/routes/panel_cljs_test.cljs`).

  ## What's under test

    1. **Registry wires the lens subs** — every sub the panel reads
       gets installed by `register-causa-handlers!`. The promoted
       browse / search / Simulate-URL slots (now under
       `:rf.causa.static.routes/*`) are NOT registered by
       `routing/install!` anymore.

    2. **Empty / oriented states** — no-nav focused event renders the
       empty state; nav-allocated focused cascade renders the FROM/TO
       chip row + slice detail.

    3. **Frame isolation** — every read targets `:rf/causa`'s frame."
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

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- fixture builders ---------------------------------------------------

(def cart-routes
  {:route/cart      {:path "/cart"      :doc "cart"}
   :route/checkout  {:path "/checkout"  :doc "checkout"}
   :route/payment   {:path "/checkout/payment"}
   :route/confirm   {:path "/checkout/confirm"
                     :parent :route/checkout
                     :on-match [:confirm/load]}})

(defn- nav-allocated [route-id]
  {:id        99
   :op-type   :event
   :operation :rf.route.nav-token/allocated
   :tags      {:route-id route-id :nav-token "nav-1"}})

;; ---- (1) registry wiring + tab inventory --------------------------------

(deftest registry-installs-routing-lens-subs
  (testing "register-causa-handlers! installs the focused-event lens subs"
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
        "view-facing focused-event-lens composite sub registered")
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
      (is (= 8 (count panels))
          "exactly 8 entries — Event / App DB / Views / Trace / Machines / Machines Canvas / Routing / Issues (rf2-mkpnb)"))))

;; ---- (2) empty state ---------------------------------------------------

(deftest panel-renders-empty-state-without-nav-cascade
  (testing "no focused-event nav → empty orientation pointing to Static Routes"
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
        (is (some? (find-by-testid tree "rf-causa-routing-empty"))
            "empty orientation rendered when focused event did not navigate")
        (is (nil? (find-by-testid tree "rf-causa-routing-nav-row"))
            "FROM/TO row NOT rendered without nav cascade")
        ;; Promoted browse surfaces MUST NOT be present anymore
        (is (nil? (find-by-testid tree "rf-causa-routing-list"))
            "flat list NOT rendered (promoted to Static Routes)")
        (is (nil? (find-by-testid tree "rf-causa-routing-search"))
            "search NOT rendered (promoted to Static Routes)")
        (is (nil? (find-by-testid tree "rf-causa-routing-sim"))
            "Simulate-URL NOT rendered (promoted to Static Routes)")))))

;; ---- (3) FROM / TO chip row when focused cascade navigated -------------

(deftest panel-renders-from-to-when-cascade-navigated
  (testing "focused cascade with nav-token emit → FROM + TO chip row + slice detail"
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
            buffer [{:id 99 :op-type :event :operation :event/dispatched
                     :tags {:dispatch-id 99
                            :event [:rf.route/navigate :route/confirm]}}
                    (assoc nav-event :tags
                           (assoc (:tags nav-event) :dispatch-id 99))]]
        (rf/dispatch-sync [:rf.causa/sync-trace-buffer buffer]
                          {:frame :rf/causa})
        (rf/dispatch-sync [:rf.causa/focus-cascade 99 nil] {:frame :rf/causa}))
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-causa-routing-nav-row"))
            "FROM/TO chip row rendered")
        ;; FROM cell does NOT render here — no prior slice id distinct
        ;; from confirm, so from-to-from-cascade collapses :from-id.
        ;; The TO cell + nav marker chips MUST render.
        (is (some? (find-by-testid tree "rf-causa-routing-to-cell"))
            "TO cell rendered")
        (is (some? (find-by-testid tree "rf-causa-routing-marker-to"))
            "TO marker chip text rendered")
        (is (some? (find-by-testid tree "rf-causa-routing-nav-summary"))
            "header nav summary surfaces the TO id")
        (is (some? (find-by-testid tree "rf-causa-routing-slice-detail"))
            "slice detail grid rendered alongside nav row")
        (is (nil? (find-by-testid tree "rf-causa-routing-empty"))
            "empty state NOT rendered when navigation triggered")))))

(deftest panel-renders-from-and-to-when-prior-slice-differs
  (testing "focused cascade with nav-token emit + distinct prior slice → FROM + TO"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/causa})
      ;; Prior slice = :route/cart (will be the FROM after the nav lands on
      ;; :route/confirm).
      (rf/dispatch-sync [:rf.causa/set-current-route-slice-override-for-test
                         {:id :route/cart :params {} :query {}}]
                        {:frame :rf/causa})
      (let [nav-event (nav-allocated :route/confirm)
            buffer [{:id 99 :op-type :event :operation :event/dispatched
                     :tags {:dispatch-id 99
                            :event [:rf.route/navigate :route/confirm]}}
                    (assoc nav-event :tags
                           (assoc (:tags nav-event) :dispatch-id 99))]]
        (rf/dispatch-sync [:rf.causa/sync-trace-buffer buffer]
                          {:frame :rf/causa})
        (rf/dispatch-sync [:rf.causa/focus-cascade 99 nil] {:frame :rf/causa}))
      (let [tree (routing/Panel)]
        (is (some? (find-by-testid tree "rf-causa-routing-from-cell"))
            "FROM cell rendered (prior slice = :route/cart)")
        (is (some? (find-by-testid tree "rf-causa-routing-marker-from"))
            "FROM marker chip rendered")
        (is (some? (find-by-testid tree "rf-causa-routing-to-cell"))
            "TO cell rendered (nav destination = :route/confirm)")
        (is (some? (find-by-testid tree "rf-causa-routing-marker-to"))
            "TO marker chip rendered")))))
