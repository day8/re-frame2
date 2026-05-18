(ns day8.re-frame2-causa.panels.routing-helpers-cljs-test
  "Pure-data tests for Causa's Routing tab helpers (rf2-nrbs9).

  Dual-target naming (`.cljc` + `_cljs_test`):

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **project-route-tree** — registrar map → row vector, sorted
       by path; correct depth derivation; empty registrar → `[]`.
    2. **focused-cascade** — lookup by dispatch-id.
    3. **nav-token-allocated-in-cascade** — scans `:other` bucket
       for the `:rf.route.nav-token/allocated` emit; nil when
       absent.
    4. **from-to-from-cascade** — derives `{:from-id :to-id
       :navigated?}` per the lens contract.
    5. **assign-markers** — TO wins over FROM wins over HERE; HERE
       only surfaces when no navigation happened.
    6. **project-data** — top-level composite; silent state when no
       routes; correct decoration when focused event causes
       navigation."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.routing-helpers :as h]))

;; ---- fixture builders ---------------------------------------------------

(defn- route
  "Build a registrar-shaped route metadata map."
  ([path] (route path nil))
  ([path doc] (cond-> {:path path}
                doc (assoc :doc doc))))

(def cart-routes
  "Realistic registrar shape — a small e-commerce route set."
  {:route/root      (route "/")
   :route/cart      (route "/cart"      "shopping cart")
   :route/checkout  (route "/checkout"  "checkout overview")
   :route/payment   (route "/checkout/payment")
   :route/confirm   (route "/checkout/confirm")
   :route/admin     (route "/admin")
   :route/audit     (route "/admin/audit")
   :route/not-found (route "/404")})

(defn- nav-allocated-trace
  "Build a `:rf.route.nav-token/allocated` trace event map mirroring
  the shape `(trace/emit! :event :rf.route.nav-token/allocated ...)`
  produces in `re-frame.routing`."
  [route-id nav-token & [dispatch-id]]
  {:id        1
   :op-type   :event
   :operation :rf.route.nav-token/allocated
   :tags      (cond-> {:route-id route-id :nav-token nav-token}
                dispatch-id (assoc :dispatch-id dispatch-id))})

(defn- cascade
  "Build a minimal cascade record per `re-frame.trace.projection`
  shape — only the slots the helpers actually read."
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

;; ---- project-route-tree -------------------------------------------------

(deftest project-route-tree-test
  (testing "empty registrar yields []"
    (is (= [] (h/project-route-tree {}))))

  (testing "single route yields one row at depth 0 for root"
    (let [rows (h/project-route-tree {:route/root (route "/")})]
      (is (= 1 (count rows)))
      (is (= :route/root (-> rows first :route-id)))
      (is (= "/" (-> rows first :path)))
      (is (= 0 (-> rows first :depth)))))

  (testing "multi-segment paths derive depth from segment count"
    (let [rows (h/project-route-tree cart-routes)
          by-id (into {} (map (juxt :route-id identity)) rows)]
      (is (= 0 (get-in by-id [:route/root :depth])))
      (is (= 1 (get-in by-id [:route/cart :depth])))
      (is (= 1 (get-in by-id [:route/checkout :depth])))
      (is (= 2 (get-in by-id [:route/payment :depth])))
      (is (= 2 (get-in by-id [:route/audit :depth])))))

  (testing "rows are sorted by path lexicographically"
    (let [paths (mapv :path (h/project-route-tree cart-routes))]
      (is (= paths (sort paths)))))

  (testing "doc is carried through when present, nil otherwise"
    (let [by-id (into {} (map (juxt :route-id identity))
                      (h/project-route-tree cart-routes))]
      (is (= "shopping cart" (get-in by-id [:route/cart :doc])))
      (is (nil? (get-in by-id [:route/payment :doc]))))))

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
  (let [rows (h/project-route-tree cart-routes)]
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
        ;; Current-id == TO; HERE is suppressed in favour of TO.
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
  (testing "focused cascade caused navigation — FROM + TO render"
    (let [c (cascade 42 [:rf.route/navigate :route/confirm]
              :other [(nav-allocated-trace :route/confirm "nav-9" 42)])
          data (h/project-data cart-routes {:id :route/confirm} c)
          by-id (into {} (map (juxt :route-id :marker)) (:routes data))]
      (is (true? (:navigated? data)))
      (is (= :route/confirm (:to-id data)))
      ;; Note: current-slice's :id is the POST-nav value (the slice
      ;; reflects TO), so the FROM is derived from the nav-token emit
      ;; ∧ the slice difference. Since current.id == to-id, FROM
      ;; collapses to nil per from-to-from-cascade contract.
      (is (nil? (:from-id data)))
      (is (= :to (get by-id :route/confirm)))))

  (testing "nav cascade with distinct current slice — FROM ≠ TO"
    ;; This simulates the case where the panel is reading mid-cascade:
    ;; the nav-token emit identifies TO; the current slice (perhaps
    ;; from a stale snapshot, or a different frame) carries the prior
    ;; route-id; helper produces both markers.
    (let [c (cascade 1 [:rf.route/navigate :route/confirm]
              :other [(nav-allocated-trace :route/confirm "nav-3")])
          data (h/project-data cart-routes {:id :route/cart} c)
          by-id (into {} (map (juxt :route-id :marker)) (:routes data))]
      (is (true? (:navigated? data)))
      (is (= :route/cart (:from-id data)))
      (is (= :route/confirm (:to-id data)))
      (is (= :from (get by-id :route/cart)))
      (is (= :to   (get by-id :route/confirm))))))
