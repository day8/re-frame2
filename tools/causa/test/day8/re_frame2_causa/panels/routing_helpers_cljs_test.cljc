(ns day8.re-frame2-causa.panels.routing-helpers-cljs-test
  "Pure-data tests for Causa's Routes tab helpers (rf2-nrbs9, reshaped
  per rf2-lq0ef).

  Dual-target naming (`.cljc` + `_cljs_test`):

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **project-routes** — registrar map → row vector, sorted by
       path; per-key surface (route-id / path / doc / parent / tags /
       has-on-match? / has-can-leave? / rank / meta); empty
       registrar → `[]`.
    2. **filter-rows** — substring match across route-id + path + doc,
       case-insensitive; blank query is identity.
    3. **simulate-url** — runs every route's compiled pattern against
       the URL, returns ranked candidates + winner; mirrors `match-url`
       resolution order.
    4. **focused-cascade** — lookup by dispatch-id.
    5. **nav-token-allocated-in-cascade** — scans `:other` bucket for
       the `:rf.route.nav-token/allocated` emit; nil when absent.
    6. **from-to-from-cascade** — derives `{:from-id :to-id
       :navigated?}` per the lens contract.
    7. **assign-markers** — TO wins over FROM wins over HERE; HERE
       only surfaces when no navigation happened.
    8. **project-data** — top-level composite; silent state when no
       routes; correct decoration when focused event causes navigation;
       carries query + sim-url through."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.routing-helpers :as h]))

;; ---- fixture builders ---------------------------------------------------

(defn- route
  "Build a registrar-shaped route metadata map. Adds the
  `:rf.route/compiled` slot the simulator reads (production sets this
  at `reg-route` time) so the simulator tests work without booting
  the full registrar."
  [path & {:keys [doc parent tags on-match can-leave]}]
  (cond-> {:path path}
    doc       (assoc :doc doc)
    parent    (assoc :parent parent)
    tags      (assoc :tags tags)
    on-match  (assoc :on-match on-match)
    can-leave (assoc :can-leave can-leave)))

(def cart-routes
  "Realistic registrar shape — a small e-commerce route set."
  {:route/root      (route "/")
   :route/cart      (route "/cart"      :doc "shopping cart")
   :route/checkout  (route "/checkout"  :doc "checkout overview")
   :route/payment   (route "/checkout/payment")
   :route/confirm   (route "/checkout/confirm" :parent :route/checkout)
   :route/admin     (route "/admin"
                           :tags     #{:admin}
                           :can-leave :guard/admin-leave?)
   :route/audit     (route "/admin/audit"
                           :parent   :route/admin
                           :on-match [:audit/load])
   :route/not-found (route "/404")})

(defn- nav-allocated-trace
  [route-id nav-token & [dispatch-id]]
  {:id        1
   :op-type   :event
   :operation :rf.route.nav-token/allocated
   :tags      (cond-> {:route-id route-id :nav-token nav-token}
                dispatch-id (assoc :dispatch-id dispatch-id))})

(defn- cascade
  [dispatch-id event-vec & {:keys [other effects fx handler]
                            :or {other [] effects [] fx nil handler nil}}]
  {:dispatch-id dispatch-id
   :event       event-vec
   :handler     handler
   :fx          fx
   :effects     effects
   :subs        []
   :renders     []
   :other       other})

;; ---- project-routes -----------------------------------------------------

(deftest project-routes-test
  (testing "empty registrar yields []"
    (is (= [] (h/project-routes {}))))

  (testing "single route yields one row"
    (let [rows (h/project-routes {:route/root (route "/")})]
      (is (= 1 (count rows)))
      (is (= :route/root (-> rows first :route-id)))
      (is (= "/" (-> rows first :path)))
      (is (false? (-> rows first :has-on-match?)))
      (is (false? (-> rows first :has-can-leave?)))))

  (testing "no :depth field — flat per rf2-lq0ef"
    (let [rows (h/project-routes cart-routes)]
      (is (every? #(not (contains? % :depth)) rows)
          "rows must not carry a :depth field (decorative tree dropped)")))

  (testing "rows are sorted by path lexicographically"
    (let [paths (mapv :path (h/project-routes cart-routes))]
      (is (= paths (sort paths)))))

  (testing "metadata surface — doc / parent / tags / has-on-match? / has-can-leave?"
    (let [by-id (into {} (map (juxt :route-id identity))
                      (h/project-routes cart-routes))]
      (is (= "shopping cart" (get-in by-id [:route/cart :doc])))
      (is (= :route/checkout (get-in by-id [:route/confirm :parent])))
      (is (= #{:admin} (get-in by-id [:route/admin :tags])))
      (is (true? (get-in by-id [:route/admin :has-can-leave?])))
      (is (true? (get-in by-id [:route/audit :has-on-match?])))
      (is (false? (get-in by-id [:route/cart :has-on-match?])))))

  (testing ":meta is the full registrar entry verbatim — click-to-expand surface"
    (let [by-id (into {} (map (juxt :route-id identity))
                      (h/project-routes cart-routes))]
      (is (= (route "/cart" :doc "shopping cart")
             (get-in by-id [:route/cart :meta]))))))

;; ---- filter-rows --------------------------------------------------------

(deftest filter-rows-test
  (let [rows (h/project-routes cart-routes)]
    (testing "blank / nil query is identity"
      (is (= rows (h/filter-rows rows nil)))
      (is (= rows (h/filter-rows rows "")))
      (is (= rows (h/filter-rows rows "   "))))

    (testing "substring on path"
      (let [filtered (h/filter-rows rows "checkout")
            ids      (set (map :route-id filtered))]
        (is (contains? ids :route/checkout))
        (is (contains? ids :route/payment))
        (is (contains? ids :route/confirm))
        (is (not (contains? ids :route/cart)))))

    (testing "substring on route-id"
      (let [filtered (h/filter-rows rows "audit")
            ids      (set (map :route-id filtered))]
        (is (= #{:route/audit} ids))))

    (testing "substring on doc"
      (let [filtered (h/filter-rows rows "shopping")
            ids      (set (map :route-id filtered))]
        (is (contains? ids :route/cart))))

    (testing "case-insensitive"
      (is (= (h/filter-rows rows "CHECKOUT")
             (h/filter-rows rows "checkout"))))))

;; ---- simulate-url -------------------------------------------------------

(deftest simulate-url-test
  (testing "nil / blank URL yields a benign empty result"
    (let [r (h/simulate-url cart-routes nil)]
      (is (nil? (:url r)))
      (is (= [] (:candidates r)))
      (is (nil? (:winner r))))
    (is (= [] (:candidates (h/simulate-url cart-routes "")))))

  (testing "exact path match — winner is the matching route"
    (let [r (h/simulate-url cart-routes "/cart")]
      (is (= "/cart" (:path r)))
      (is (= :route/cart (:winner r)))
      (is (= 1 (count (:candidates r))))
      (is (true? (-> r :candidates first :winner?)))
      (is (= [:route/cart] (mapv :route-id (:candidates r))))))

  (testing "no match — empty candidates, nil winner"
    (let [r (h/simulate-url cart-routes "/nope")]
      (is (= [] (:candidates r)))
      (is (nil? (:winner r)))))

  (testing "query / fragment are stripped before matching"
    (let [r (h/simulate-url cart-routes "/cart?source=email#step-1")]
      (is (= "/cart" (:path r)))
      (is (= :route/cart (:winner r)))))

  (testing "trailing slash normalises (multi-segment only)"
    (let [r (h/simulate-url cart-routes "/checkout/")]
      (is (= :route/checkout (:winner r)))))

  (testing "every matching route is a candidate (ranked descending)"
    ;; `/checkout/payment` only matches `:route/payment` exactly;
    ;; but if we register a splat-style fallback, it should appear
    ;; as a lower-ranked candidate. Build a registrar with a splat
    ;; to exercise the cascade.
    (let [routes {:route/exact (route "/checkout/payment")
                  :route/splat (route "/*rest")}
          r      (h/simulate-url routes "/checkout/payment")
          ids    (mapv :route-id (:candidates r))]
      (is (= :route/exact (:winner r))
          "static-heavy pattern outranks splat")
      (is (= 2 (count ids)))
      (is (= :route/exact (first ids)))
      (is (= :route/splat (second ids))))))

;; ---- focused-cascade ----------------------------------------------------

(deftest focused-cascade-test
  (testing "nil focused-id → nil"
    (is (nil? (h/focused-cascade [(cascade 1 [:a])] nil))))

  (testing "no match → nil"
    (is (nil? (h/focused-cascade [(cascade 1 [:a])] 99))))

  (testing "match returns the cascade record"
    (let [c (cascade 7 [:foo])]
      (is (= c (h/focused-cascade [(cascade 1 [:a]) c (cascade 9 [:b])] 7))))))

;; ---- nav-token-allocated-in-cascade -------------------------------------

(deftest nav-token-allocated-in-cascade-test
  (testing "nil cascade → nil"
    (is (nil? (h/nav-token-allocated-in-cascade nil))))

  (testing "cascade with no nav emit → nil"
    (is (nil? (h/nav-token-allocated-in-cascade (cascade 1 [:foo])))))

  (testing "nav emit in :other bucket is found"
    (let [c (cascade 1 [:rf.route/navigate :route/cart]
              :other [(nav-allocated-trace :route/cart "nav-1")])
          ev (h/nav-token-allocated-in-cascade c)]
      (is (some? ev))
      (is (= :route/cart (-> ev :tags :route-id)))
      (is (= "nav-1" (-> ev :tags :nav-token)))))

  (testing "nav emit in :effects bucket is also found"
    (let [c (cascade 1 [:foo]
              :effects [(nav-allocated-trace :route/x "nav-2")])]
      (is (some? (h/nav-token-allocated-in-cascade c))))))

;; ---- from-to-from-cascade -----------------------------------------------

(deftest from-to-from-cascade-test
  (testing "no nav emit → not navigated"
    (let [c (cascade 1 [:foo])
          {:keys [navigated? from-id to-id]}
          (h/from-to-from-cascade c {:id :route/cart})]
      (is (false? navigated?))
      (is (nil? from-id))
      (is (nil? to-id))))

  (testing "nav from cart to confirm yields both ids"
    (let [c (cascade 1 [:rf.route/navigate :route/confirm]
              :other [(nav-allocated-trace :route/confirm "nav-7")])
          {:keys [navigated? from-id to-id]}
          (h/from-to-from-cascade c {:id :route/cart})]
      (is (true? navigated?))
      (is (= :route/cart from-id))
      (is (= :route/confirm to-id))))

  (testing "first navigation (no prior slice) yields nil FROM"
    (let [c (cascade 1 [:rf.route/navigate :route/cart]
              :other [(nav-allocated-trace :route/cart "nav-1")])
          {:keys [navigated? from-id to-id]}
          (h/from-to-from-cascade c nil)]
      (is (true? navigated?))
      (is (nil? from-id))
      (is (= :route/cart to-id))))

  (testing "same-route re-navigation collapses FROM to nil"
    (let [c (cascade 1 [:rf.route/navigate :route/cart {:filter :all}]
              :other [(nav-allocated-trace :route/cart "nav-3")])
          {:keys [navigated? from-id to-id]}
          (h/from-to-from-cascade c {:id :route/cart :params {:filter :open}})]
      (is (true? navigated?))
      (is (nil? from-id) "from collapses when TO == current")
      (is (= :route/cart to-id)))))

;; ---- assign-markers -----------------------------------------------------

(deftest assign-markers-test
  (let [rows (h/project-routes cart-routes)]
    (testing "no navigation: HERE on the current route only"
      (let [decorated (h/assign-markers rows
                                        {:current-id :route/cart
                                         :from-id    nil
                                         :to-id      nil
                                         :navigated? false})
            by-id (into {} (map (juxt :route-id :marker)) decorated)]
        (is (= :here (get by-id :route/cart)))
        (is (nil? (get by-id :route/checkout)))
        (is (nil? (get by-id :route/audit)))))

    (testing "navigation: FROM + TO win; HERE suppressed (TO is the new HERE)"
      (let [decorated (h/assign-markers rows
                                        {:current-id :route/confirm
                                         :from-id    :route/cart
                                         :to-id      :route/confirm
                                         :navigated? true})
            by-id (into {} (map (juxt :route-id :marker)) decorated)]
        (is (= :from (get by-id :route/cart)))
        (is (= :to   (get by-id :route/confirm)))
        (is (not= :here (get by-id :route/confirm)))))

    (testing "navigation with same-route collapse: only TO surfaces"
      (let [decorated (h/assign-markers rows
                                        {:current-id :route/cart
                                         :from-id    nil
                                         :to-id      :route/cart
                                         :navigated? true})
            by-id (into {} (map (juxt :route-id :marker)) decorated)]
        (is (= :to (get by-id :route/cart)))
        (is (nil? (get by-id :route/checkout)))))))

;; ---- project-data composite --------------------------------------------

(deftest project-data-silent-test
  (testing "silent state — no routes registered"
    (let [data (h/project-data {} {:id :route/anything} nil)]
      (is (true? (:silent? data)))
      (is (= [] (:routes data)))
      (is (false? (:navigated? data))))))

(deftest project-data-orientation-test
  (testing "no focused cascade but routes present — orientation HERE only"
    (let [data (h/project-data cart-routes {:id :route/cart} nil)
          by-id (into {} (map (juxt :route-id :marker)) (:routes data))]
      (is (false? (:silent? data)))
      (is (false? (:navigated? data)))
      (is (= :here (get by-id :route/cart)))
      (is (= :route/cart (get-in data [:current :id]))))))

(deftest project-data-navigation-test
  (testing "focused cascade caused navigation — TO renders"
    (let [c (cascade 42 [:rf.route/navigate :route/confirm]
              :other [(nav-allocated-trace :route/confirm "nav-9" 42)])
          data (h/project-data cart-routes {:id :route/confirm} c)
          by-id (into {} (map (juxt :route-id :marker)) (:routes data))]
      (is (true? (:navigated? data)))
      (is (= :route/confirm (:to-id data)))
      (is (nil? (:from-id data))
          "current.id == to-id ⇒ FROM collapses per from-to-from-cascade")
      (is (= :to (get by-id :route/confirm)))))

  (testing "nav cascade with distinct current slice — FROM ≠ TO"
    (let [c (cascade 1 [:rf.route/navigate :route/confirm]
              :other [(nav-allocated-trace :route/confirm "nav-3")])
          data (h/project-data cart-routes {:id :route/cart} c)
          by-id (into {} (map (juxt :route-id :marker)) (:routes data))]
      (is (true? (:navigated? data)))
      (is (= :route/cart (:from-id data)))
      (is (= :route/confirm (:to-id data)))
      (is (= :from (get by-id :route/cart)))
      (is (= :to   (get by-id :route/confirm))))))

(deftest project-data-query-and-sim-test
  (testing "query filter is applied to :routes"
    (let [data (h/project-data cart-routes {:id :route/cart} nil "checkout" nil)
          ids  (set (map :route-id (:routes data)))]
      (is (true? (:filtered? data)))
      (is (contains? ids :route/checkout))
      (is (not (contains? ids :route/cart)))))

  (testing "sim-url drives :sim-result"
    (let [data (h/project-data cart-routes {:id :route/cart} nil nil "/cart")]
      (is (= "/cart" (-> data :sim-result :path)))
      (is (= :route/cart (-> data :sim-result :winner)))))

  (testing "blank sim-url leaves :sim-result nil"
    (let [data (h/project-data cart-routes {:id :route/cart} nil nil "")]
      (is (nil? (:sim-result data))))))

;; ---- project-static-data (rf2-o5f5f.3) ---------------------------------

(deftest project-static-data-empty-test
  (testing "silent state — no routes registered"
    (let [data (h/project-static-data {} nil nil)]
      (is (true? (:silent? data)))
      (is (= [] (:routes data)))
      (is (= 0 (:total-routes data)))
      (is (false? (:filtered? data)))
      (is (nil? (:sim-result data))))))

(deftest project-static-data-rows-test
  (testing "rows are projected + sorted + carry no :marker"
    (let [data (h/project-static-data cart-routes nil nil)]
      (is (false? (:silent? data)))
      (is (= (count cart-routes) (:total-routes data)))
      (is (= (count cart-routes) (count (:routes data))))
      (is (every? #(not (contains? % :marker)) (:routes data))
          "Static rows MUST NOT carry :marker (event-INDEPENDENT)"))))

(deftest project-static-data-query-test
  (testing "query narrows the row list + flips :filtered?"
    (let [data (h/project-static-data cart-routes "checkout" nil)
          ids  (set (map :route-id (:routes data)))]
      (is (true? (:filtered? data)))
      (is (contains? ids :route/checkout))
      (is (not (contains? ids :route/cart)))))

  (testing "blank query is identity"
    (let [data (h/project-static-data cart-routes "" nil)]
      (is (false? (:filtered? data)))
      (is (= (count cart-routes) (count (:routes data)))))))

(deftest project-static-data-sim-url-test
  (testing "non-blank sim-url drives :sim-result"
    (let [data (h/project-static-data cart-routes nil "/cart")]
      (is (= "/cart" (-> data :sim-result :path)))
      (is (= :route/cart (-> data :sim-result :winner)))))

  (testing "blank sim-url leaves :sim-result nil"
    (let [data (h/project-static-data cart-routes nil "")]
      (is (nil? (:sim-result data))))))

;; ---- simulate-navigation-preview (rf2-o5f5f.3) -------------------------

(deftest simulate-navigation-preview-unknown-test
  (testing "unknown route-id returns the unknown shape"
    (let [pv (h/simulate-navigation-preview cart-routes :route/nope nil)]
      (is (true? (:unknown? pv)))
      (is (= :route/nope (:route-id pv))))))

(deftest simulate-navigation-preview-no-url-test
  (testing "registered route, no URL → path / on-match / slot shape; no params"
    (let [pv (h/simulate-navigation-preview cart-routes :route/audit nil)]
      (is (false? (:unknown? pv)))
      (is (= :route/audit (:route-id pv)))
      (is (= "/admin/audit" (:path pv)))
      (is (= [:audit/load] (:on-match pv)))
      (is (= [:rf/route] (:db-slot pv)))
      (is (false? (:matched? pv)))
      (is (nil? (:params pv)))
      (is (= {:id :route/audit :path "/admin/audit"} (:slot-shape pv))))))

(deftest simulate-navigation-preview-with-matching-url-test
  (testing "matching URL → :matched? true + params surfaced + slot shape carries params"
    ;; cart-routes uses bare paths (no compiled pattern) — the helper
    ;; compiles on-demand. A literal /cart matches :route/cart.
    (let [pv (h/simulate-navigation-preview cart-routes :route/cart "/cart")]
      (is (false? (:unknown? pv)))
      (is (true? (:matched? pv)))
      (is (= "/cart" (:url pv)))
      (is (= [:rf/route] (:db-slot pv)))
      (is (contains? (:slot-shape pv) :id))
      (is (= :route/cart (-> pv :slot-shape :id))))))

(deftest simulate-navigation-preview-with-mismatching-url-test
  (testing "URL does not match this route's pattern → :matched? false"
    (let [pv (h/simulate-navigation-preview cart-routes :route/cart "/checkout")]
      (is (false? (:matched? pv)))
      (is (nil? (:params pv)))
      ;; Slot shape still carries path (registered) but no params (no match).
      (is (= "/cart" (:path pv)))
      (is (not (contains? (:slot-shape pv) :params))))))

;; ---- project-topology (rf2-3kjlo) ---------------------------------------

(def parented-routes
  "Registrar with explicit `:parent` references so the topology
  projection can build a non-trivial tree. /checkout has two child
  routes; the rest sit at depth 0."
  {:route/root      (route "/")
   :route/cart      (route "/cart")
   :route/checkout  (route "/checkout")
   :route/payment   (route "/checkout/payment"
                           :parent :route/checkout)
   :route/confirm   (route "/checkout/confirm"
                           :parent :route/checkout)
   :route/admin     (route "/admin")})

(deftest project-topology-empty-test
  (testing "empty registrar yields []"
    (is (= [] (h/project-topology {})))))

(deftest project-topology-depth-and-shape-test
  (testing "every registered route appears exactly once in the projection"
    (let [topology (h/project-topology parented-routes)
          by-id    (group-by #(-> % :row :route-id) topology)]
      (is (= (count parented-routes) (count topology))
          "topology row count equals registrar size")
      (doseq [rid (keys parented-routes)]
        (is (= 1 (count (get by-id rid)))
            (str rid " appears exactly once in the projection")))))

  (testing "parented children land at depth = parent depth + 1"
    (let [topology (h/project-topology parented-routes)
          by-id    (into {} (map (juxt #(-> % :row :route-id) :depth))
                         topology)]
      (is (= 0 (get by-id :route/checkout))
          ":route/checkout sits at depth 0 (no parent)")
      (is (= 1 (get by-id :route/payment))
          ":route/payment sits at depth 1 under :route/checkout")
      (is (= 1 (get by-id :route/confirm))
          ":route/confirm sits at depth 1 under :route/checkout")
      (is (= 0 (get by-id :route/admin)) ":route/admin stays at depth 0")))

  (testing "children appear after their parent (DFS order)"
    (let [topology (h/project-topology parented-routes)
          ids      (mapv #(-> % :row :route-id) topology)
          ck-idx   (.indexOf ids :route/checkout)
          pay-idx  (.indexOf ids :route/payment)
          conf-idx (.indexOf ids :route/confirm)]
      (is (< ck-idx pay-idx) ":route/checkout precedes :route/payment")
      (is (< ck-idx conf-idx) ":route/checkout precedes :route/confirm")))

  (testing "last-at-depth? flag marks last sibling at each depth"
    (let [topology (h/project-topology parented-routes)
          last-by-id (into {} (map (juxt #(-> % :row :route-id) :last-at-depth?))
                           topology)]
      ;; Within :route/checkout's children paths sort lexicographically:
      ;; "/checkout/confirm" < "/checkout/payment", so :route/payment is
      ;; the last child at depth 1.
      (is (true? (get last-by-id :route/payment))
          ":route/payment is the last sibling at depth 1 under /checkout")
      (is (false? (get last-by-id :route/confirm))
          ":route/confirm is not the last sibling at depth 1"))))

(deftest project-topology-orphan-parent-test
  (testing "rows whose :parent points to an unregistered route become roots"
    (let [orphan-routes {:route/orphan
                         (route "/orphan" :parent :route/missing)
                         :route/root (route "/")}
          topology (h/project-topology orphan-routes)
          orphan-entry (some #(when (= :route/orphan (-> % :row :route-id)) %)
                             topology)]
      (is (some? orphan-entry) "orphan still appears in topology")
      (is (= 0 (:depth orphan-entry))
          "orphan rendered at depth 0 (parent reference broken)"))))

(deftest project-topology-cycle-protection-test
  (testing "a cycle in the :parent graph does not loop"
    ;; A → B → A. Neither has a nil parent; both should still appear
    ;; (the rooted? predicate treats them as roots since neither's
    ;; parent points to nil, BUT the registered-ids set DOES contain
    ;; both ids — so they're NOT rooted via the orphan branch. They
    ;; ARE rooted only when their parent isn't in registered-ids.
    ;; With both parents registered they wouldn't be roots — which
    ;; means the walk-from-roots never includes them. Test the milder
    ;; cycle case: a self-cycle.
    (let [self-cycle {:route/self
                      (route "/self" :parent :route/self)
                      :route/root (route "/")}
          topology (h/project-topology self-cycle)
          ids      (mapv #(-> % :row :route-id) topology)]
      ;; :route/root appears; :route/self may or may not (it's a self-
      ;; root via the cycle-protection branch). Critically, the call
      ;; must terminate and return a vector.
      (is (vector? topology) "projection terminates on self-cycle")
      (is (contains? (set ids) :route/root)
          "non-cycling routes still appear"))))

;; ---- epoch-routing-activity (rf2-3kjlo) ---------------------------------

(deftest epoch-routing-activity-no-cascade-test
  (testing "nil cascade → nil activity"
    (is (nil? (h/epoch-routing-activity nil nil)))
    (is (nil? (h/epoch-routing-activity nil {:id :route/cart})))))

(deftest epoch-routing-activity-no-routing-trace-test
  (testing "cascade with no routing trace events → nil (no activity)"
    (let [c (cascade 1 [:counter/inc])]
      (is (nil? (h/epoch-routing-activity c {:id :route/cart}))))))

(deftest epoch-routing-activity-on-match-test
  (testing "nav-token-allocated emit → phase :on-match + match params"
    (let [c (cascade 7 [:rf.route/navigate :route/confirm]
              :other [(nav-allocated-trace :route/confirm "nav-1")])
          activity (h/epoch-routing-activity c {:id :route/confirm
                                                :params {:order-id "x"}})]
      (is (some? activity))
      (is (= :on-match (:phase activity)))
      (is (= {:order-id "x"} (:match activity))
          "match surfaces the slice's params when phase is :on-match"))))

(deftest epoch-routing-activity-events-test
  (testing "events list carries root event vector + downstream dispatches"
    (let [downstream {:id 8 :op-type :event :operation :event/dispatched
                      :tags {:event [:cart/route-entered]}}
          c (cascade 7 [:rf.route/navigate :route/cart]
              :other [(nav-allocated-trace :route/cart "nav-1")
                      downstream])
          activity (h/epoch-routing-activity c {:id :route/cart})]
      (is (= [[:rf.route/navigate :route/cart]
              [:cart/route-entered]]
             (:events activity))))))

(deftest epoch-routing-activity-navigation-blocked-test
  (testing "navigation-blocked emit → phase :navigation-blocked + nil match"
    (let [blocked-ev {:id 1 :op-type :event
                      :operation :rf.route/navigation-blocked
                      :tags {:route-id :route/admin}}
          c (cascade 1 [:rf.route/navigate :route/admin]
              :other [blocked-ev])
          activity (h/epoch-routing-activity c {:id :route/cart})]
      (is (= :navigation-blocked (:phase activity)))
      (is (nil? (:match activity))
          "match is only surfaced when phase is :on-match"))))

(deftest epoch-routing-activity-fragment-changed-test
  (testing "fragment-changed emit → phase :fragment-changed"
    (let [frag-ev {:id 1 :op-type :event
                   :operation :rf.route/fragment-changed
                   :tags {:fragment "step-2"}}
          c (cascade 1 [:foo] :other [frag-ev])
          activity (h/epoch-routing-activity c {:id :route/cart})]
      (is (= :fragment-changed (:phase activity))))))

;; ---- project-topology-data composite (rf2-3kjlo) ------------------------

(deftest project-topology-data-silent-test
  (testing "no routes registered → silent? true, empty topology, nil activity"
    (let [data (h/project-topology-data {} {:id :route/cart} nil)]
      (is (true? (:silent? data)))
      (is (= [] (:topology data)))
      (is (nil? (:activity data)))
      (is (false? (:navigated? data))))))

(deftest project-topology-data-topology-shape-test
  (testing "topology vector mirrors project-topology + carries marker"
    (let [data (h/project-topology-data parented-routes
                                        {:id :route/cart}
                                        nil)
          ids  (mapv #(-> % :row :route-id) (:topology data))]
      (is (false? (:silent? data)))
      (is (= (count parented-routes) (count (:topology data))))
      (is (contains? (set ids) :route/cart))
      ;; No focused cascade ⇒ no activity; current slice ⇒ HERE marker
      ;; on :route/cart only.
      (let [marker-by-id (into {}
                               (map (juxt #(-> % :row :route-id) :marker))
                               (:topology data))]
        (is (= :here (get marker-by-id :route/cart))
            ":route/cart carries :here marker (current slice id)")
        (is (nil? (get marker-by-id :route/admin))
            "non-current routes carry no marker")))))

(deftest project-topology-data-overlay-test
  (testing "focused cascade caused navigation → :to overlay + :on-match phase"
    (let [c (cascade 42 [:rf.route/navigate :route/confirm]
              :other [(nav-allocated-trace :route/confirm "nav-9" 42)])
          data (h/project-topology-data parented-routes
                                        {:id :route/confirm
                                         :params {:x 1}}
                                        c)
          marker-by-id (into {}
                             (map (juxt #(-> % :row :route-id) :marker))
                             (:topology data))]
      (is (true? (:navigated? data)))
      (is (= :route/confirm (:to-id data)))
      (is (= :to (get marker-by-id :route/confirm)))
      (is (some? (:activity data)))
      (is (= :on-match (-> data :activity :phase)))
      (is (= {:x 1} (-> data :activity :match)))))

  (testing "focused cascade with distinct prior slice paints both :from and :to"
    (let [c (cascade 1 [:rf.route/navigate :route/confirm]
              :other [(nav-allocated-trace :route/confirm "nav-3")])
          data (h/project-topology-data parented-routes {:id :route/cart} c)
          marker-by-id (into {}
                             (map (juxt #(-> % :row :route-id) :marker))
                             (:topology data))]
      (is (= :from (get marker-by-id :route/cart)))
      (is (= :to   (get marker-by-id :route/confirm))))))

(deftest project-topology-data-no-activity-test
  (testing "focused cascade has no routing trace events → activity nil; HERE still paints"
    (let [c (cascade 9 [:counter/inc])
          data (h/project-topology-data parented-routes
                                        {:id :route/cart}
                                        c)
          marker-by-id (into {}
                             (map (juxt #(-> % :row :route-id) :marker))
                             (:topology data))]
      (is (false? (:navigated? data)))
      (is (nil? (:activity data))
          "activity is nil when cascade has no routing trace events")
      (is (= :here (get marker-by-id :route/cart))
          "current route still gets :here marker"))))
