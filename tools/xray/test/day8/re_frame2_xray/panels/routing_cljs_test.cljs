(ns day8.re-frame2-xray.panels.routing-cljs-test
  "CLJS-side wiring + view tests for Xray's Dynamic Routing tab —
  the three-section stack (reconciled to RoutesPanel
  + spec/021 §7.2).

  ## Scope

  The Dynamic Routing tab renders three stacked sections per spec/021
  §7.2, top → bottom:

    1. CURRENT ROUTE          — active id (mode-accent, bold) + params
                                + query + fragment + readiness, read
                                off a slice a real navigation wrote.
                                Always shown.
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
  Routes panel (see `static/routes/panel_cljs_test.cljs`)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.routing]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.panels.routing :as routing]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` owns the reset: plain-atom adapter + the
  ;; default `:all` reset tier, which includes the trace-collector ring
  ;; reset.
  (xray-test-support/make-xray-runtime-fixture))

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

(defn- panel-tree
  "The panel's markup for the CURRENT state of `:rf.xray/routing-tab-data`.

  `routing/Panel` is the `as-component` bridge and answers an
  interop vector, not a tree to walk; the markup is
  `routing/panel-tree`, a pure fn of the composite's VALUE. So the read
  the panel's boundary makes is made here, one line above it, and every
  row below asserts on the hiccup the panel renders.

  This is deliberately the AMBIENT `rf/subscribe`, because these
  rows run under `rf/with-frame :rf/xray` in the node lane with no
  React commit at all. What the panel's own read resolves to — the
  frame React context names, not the ambient one — is the subject of
  `routing_fresco_boundary_dom_cljs_test`, which mounts for real."
  []
  (routing/panel-tree @(rf/subscribe [:rf.xray/routing-tab-data])))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

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
  ;; is read off this emit, not the live slice.
  {:id        98
   :op-type   :rf.event
   :operation :rf.route/deactivated
   :tags      {:route-id route-id}})

(defn- navigated-slice!
  "The route slice ONE real `:rf.route/navigate` writes into `:rf/default`'s
  runtime-db at `[:rf.runtime/routing :current]`, read back off the frame.

  A hand-typed slice carrying `:path` (or `:id`) — keys the router never
  writes — would let a panel branch reading them pass here while dead in
  production. Taking the slice from the producer is what stops the
  fixture and the panel agreeing on a shape nothing emits. Registers
  `route-id` for real; the reset fixture rolls it back."
  [route-id pattern request]
  (rf/reg-route route-id {} pattern)
  (rf/dispatch-sync [:rf.route/navigate (assoc request :to route-id)]
                    {:frame :rf/default})
  (get-in (rf.frame/frame-runtime-db-value :rf/default)
          [:rf.runtime/routing :current]))

(defn- real-route-entry
  "`route-id`'s registrar entry exactly as the registrar holds it."
  [route-id]
  (select-keys (rf/registrations {:source :store :kind :route}) [route-id]))

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
      (let [tree (panel-tree)]
        ;; §1 CURRENT ROUTE
        (is (some? (find-by-testid tree "rf-xray-routing-current-id"))
            "current route id renders")
        ;; §2 NAVIGATION THIS EPOCH
        (is (some? (find-by-testid tree "rf-xray-routing-no-activity"))
            "NAVIGATION reads 'No route activity in this epoch.'")
        ;; §3 ROUTE TABLE
        (is (some? (find-by-testid tree "rf-xray-routing-table-current-marker"))
            "current route gets the '◀ current' marker when no nav this epoch")
        ;; Each route gets a table row, nested ones included.
        (doseq [rid (keys cart-routes)]
          (is (some? (find-by-testid tree
                       (str "rf-xray-routing-table-row-" (name rid))))
              (str "route-table row rendered for " rid)))
        ;; :route/checkout parents :route/payment + :route/confirm, so its row
        ;; carries the `▾` disclosure chevron.
        (is (re-find #"▾" (node-text (find-by-testid
                                       tree "rf-xray-routing-table-row-checkout-chevron")))
            "parent route :route/checkout renders the ▾ disclosure chevron")
        ;; Leaf routes carry NO chevron — the leading cell is an aligned
        ;; spacer instead.
        (is (nil? (find-by-testid
                    tree "rf-xray-routing-table-row-cart-chevron"))
            "leaf route :route/cart renders no disclosure chevron")))))

(deftest current-route-section-shows-the-slice-the-router-writes
  (testing "§1 surfaces the active id, params, query, fragment and readiness of
            a slice ONE real navigation wrote"
    (setup-xray-frame!)
    (let [slice (navigated-slice! ::order "/routing-cljs-test/orders/:order-id"
                                  {:params   {:order-id "ord-1234"}
                                   :query    {:source "cart"}
                                   :fragment "step-3"})]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test
                           (real-route-entry ::order)]
                          {:frame :rf/xray})
        (rf/dispatch-sync [:rf.xray/set-current-route-slice-override-for-test slice]
                          {:frame :rf/xray})
        (let [tree      (panel-tree)
              text-of   #(some-> (find-by-testid tree %) node-text)]
          (is (= (str ::order) (text-of "rf-xray-routing-current-id")))
          (is (= (pr-str (:params slice)) (text-of "rf-xray-routing-current-params")))
          (is (= (pr-str (:query slice)) (text-of "rf-xray-routing-current-query"))
              "the query renders as the router wrote it — pr-str, unsorted")
          (is (= (str "#" (:fragment slice)) (text-of "rf-xray-routing-current-fragment")))
          (is (= (name (:transition slice)) (text-of "rf-xray-routing-current-readiness"))
              "the readiness chip names the slice's :transition")))
      (testing "an :error readiness is visible, with the error on the chip"
        (rf/with-frame :rf/xray
          (rf/dispatch-sync [:rf.xray/set-current-route-slice-override-for-test
                             (assoc slice :transition :error
                                          :error {:reason :plan-failed})]
                            {:frame :rf/xray})
          (let [chip (find-by-testid (panel-tree) "rf-xray-routing-current-readiness")]
            (is (= "error" (node-text chip)))
            (is (= (pr-str {:reason :plan-failed}) (:title (second chip)))
                "the chip's title carries the slice's :error")))))))

(deftest current-route-section-omits-query-and-fragment-the-slice-lacks
  (testing "a navigation with no query and no fragment renders neither row"
    (setup-xray-frame!)
    (let [slice (navigated-slice! ::plain "/routing-cljs-test/plain" {})]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test
                           (real-route-entry ::plain)]
                          {:frame :rf/xray})
        (rf/dispatch-sync [:rf.xray/set-current-route-slice-override-for-test slice]
                          {:frame :rf/xray})
        (let [tree (panel-tree)]
          (is (some? (find-by-testid tree "rf-xray-routing-current-id")))
          (is (nil? (find-by-testid tree "rf-xray-routing-current-query")))
          (is (nil? (find-by-testid tree "rf-xray-routing-current-fragment"))))))))

(deftest panel-renders-silent-when-no-routes
  (testing "no routes registered → silent caption + no sections"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test {}]
                        {:frame :rf/xray})
      (let [tree (panel-tree)]
        (is (some? (find-by-testid tree "rf-xray-routing-silent"))
            "silent caption rendered for empty registrar")
        (is (nil? (find-by-testid tree "rf-xray-routing-table"))
            "ROUTE TABLE NOT rendered when no routes registered")))))

;; ---- (4) per-epoch overlay (focused cascade with nav-token emit) --------

(deftest panel-paints-to-marker-and-outcome-when-cascade-navigated
  (testing "nav-token emit → :to marker on the table row + NAVIGATION FROM/TO + transitioned outcome"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      (let [nav-event (nav-allocated :route/confirm)
            buffer [{:id 99 :op-type :rf.event :operation :rf.event/dispatched
                     :tags {:rf.trace/dispatch-id 99
                            :rf.event/v [:rf.route/navigate {:to :route/confirm}]}}
                    (assoc nav-event :tags
                           (assoc (:tags nav-event) :rf.trace/dispatch-id 99))]]
        (rf/dispatch-sync [:rf.xray/sync-trace-buffer buffer]
                          {:frame :rf/xray})
        (rf/dispatch-sync [:rf.xray/focus-event 99 nil] {:frame :rf/xray}))
      (let [tree (panel-tree)]
        (is (some? (find-by-testid tree "rf-xray-routing-table-marker-to"))
            ":to overlay glyph rendered on destination route in the table")
        (is (some? (find-by-testid tree "rf-xray-routing-nav-to"))
            "NAVIGATION TO id rendered")
        (is (re-find #"transitioned"
                     (node-text (find-by-testid tree "rf-xray-routing-nav-outcome")))
            "outcome reads 'transitioned' for an :on-match nav")))))

(deftest panel-paints-from-and-to-when-prior-slice-differs
  (testing "distinct prior slice → both :from and :to markers + FROM in NAVIGATION"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      ;; Cross-route nav cart → confirm: the cascade carries both the
      ;; nav-token/allocated emit (TO = confirm) and the deactivated emit
      ;; (FROM = cart). FROM is read off the deactivated emit, NOT the
      ;; live slice. The live slice is left at :route/confirm
      ;; (the post-nav value) to prove the FROM is cascade-derived.
      (rf/dispatch-sync [:rf.xray/set-current-route-slice-override-for-test
                         {:route-id :route/confirm :params {} :query {}}]
                        {:frame :rf/xray})
      (let [nav-event (nav-allocated :route/confirm)
            buffer [{:id 99 :op-type :rf.event :operation :rf.event/dispatched
                     :tags {:rf.trace/dispatch-id 99
                            :rf.event/v [:rf.route/navigate {:to :route/confirm}]}}
                    (assoc nav-event :tags
                           (assoc (:tags nav-event) :rf.trace/dispatch-id 99))
                    (assoc (deactivated :route/cart) :tags
                           (assoc (:tags (deactivated :route/cart))
                                  :rf.trace/dispatch-id 99))]]
        (rf/dispatch-sync [:rf.xray/sync-trace-buffer buffer]
                          {:frame :rf/xray})
        (rf/dispatch-sync [:rf.xray/focus-event 99 nil] {:frame :rf/xray}))
      (let [tree (panel-tree)]
        (is (some? (find-by-testid tree "rf-xray-routing-table-marker-from"))
            ":from overlay glyph rendered on origin route in the table")
        (is (re-find #":route/cart" (node-text (find-by-testid tree "rf-xray-routing-nav-from")))
            "FROM reads the prior route :route/cart")))))

;; ---- (5) NAVIGATION THIS EPOCH reads the focused navigation's params -----
;; Never the live route's.

(defn- focused-nav-buffer
  "A focused bundle (dispatch 99, frame :rf/default) carrying `op`'s trace
  with `tags`."
  [op tags]
  [{:id 99 :op-type :rf.event :operation :rf.event/dispatched
    :tags {:rf.trace/dispatch-id 99 :frame :rf/default
           :rf.event/v [:rf.route/navigate {:to (:route-id tags)}]}}
   {:id 100 :op-type :rf.event :operation op
    :tags (assoc tags :rf.trace/dispatch-id 99 :frame :rf/default)}])

(deftest navigation-shows-a-historical-navigations-own-params
  (testing "focused on an EARLIER navigation, the row reads the params THAT
            navigation committed — off the focused epoch's post-state slice
            — not the live route's"
    (setup-xray-frame!)
    (rf/reg-route ::hist {} "/routing-cljs-test/hist/:id")
    (let [nav!    (fn [id]
                    (rf/dispatch-sync [:rf.route/navigate {:to ::hist :params {:id id}}]
                                      {:frame :rf/default})
                    (rf.frame/frame-runtime-db-value :rf/default))
          rdb-1   (nav! "1")
          after-1 (get-in rdb-1 [:rf.runtime/routing :current])
          live    (get-in (nav! "3") [:rf.runtime/routing :current])]
      (is (= {:id "3"} (:params live)) "PRECONDITION: the app has moved on")
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test
                           (real-route-entry ::hist)] {:frame :rf/xray})
        (rf/dispatch-sync [:rf.xray/set-current-route-slice-override-for-test live]
                          {:frame :rf/xray})
        (rf/dispatch-sync [:rf.xray/sync-trace-buffer
                           (focused-nav-buffer :rf.route.nav-token/allocated
                                               {:route-id  ::hist
                                                :nav-token (:nav-token after-1)})]
                          {:frame :rf/xray})
        (rf/dispatch-sync [:rf.xray/focus-event 99 :rf/default] {:frame :rf/xray})
        (rf/dispatch-sync [:rf.xray/sync-epoch-history
                           [{:epoch-id          ::e-1
                             :dispatch-id       99
                             :frame             :rf/default
                             :frame-state-after {:rf.db/runtime rdb-1}}]]
                          {:frame :rf/xray})
        (let [params (find-by-testid (panel-tree) "rf-xray-routing-nav-params")]
          (is (= (pr-str {:id "1"}) (node-text params))
              "E1's own params, not the live {:id \"3\"}"))))))

(deftest navigation-shows-no-params-for-a-blocked-navigation
  (testing "a :can-leave refusal committed nothing, so the row shows no
            params — not the live route's beside a 'blocked' chip"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/set-current-route-slice-override-for-test
                         {:route-id :route/cart :params {:id "3"} :nav-token "nav-2"}]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer
                         (focused-nav-buffer :rf.route/navigation-blocked
                                             {:route-id :route/checkout})]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/focus-event 99 :rf/default] {:frame :rf/xray})
      (let [tree (panel-tree)]
        (is (re-find #"blocked" (node-text (find-by-testid tree "rf-xray-routing-nav-outcome")))
            "PRECONDITION: the focused epoch is the blocked navigation")
        (is (nil? (find-by-testid tree "rf-xray-routing-nav-params"))
            "no params span")))))

(deftest navigation-colours-an-unmatched-url-not-found
  (testing "an unmatched URL commits under :rf.route/not-found WITH a
            nav-token, so it reads :on-match; §7.2's outcome is still
            the red not-found, not a green 'transitioned'"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test cart-routes]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer
                         (focused-nav-buffer :rf.route.nav-token/allocated
                                             {:route-id  :rf.route/not-found
                                              :nav-token "nav-1"})]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/focus-event 99 :rf/default] {:frame :rf/xray})
      (let [outcome (find-by-testid (panel-tree) "rf-xray-routing-nav-outcome")]
        (is (= "not-found" (node-text outcome)))))))
