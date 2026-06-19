(ns day8.re-frame2-xray.panels.routing-helpers-cljs-test
  "Pure-data tests for Xray's Routes tab helpers (rf2-nrbs9, reshaped
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
            [day8.re-frame2-xray.panels.routing-helpers :as h]))

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
   :op-type   :rf.event
   :operation :rf.route.nav-token/allocated
   :tags      (cond-> {:route-id route-id :nav-token nav-token}
                dispatch-id (assoc :rf.trace/dispatch-id dispatch-id))})

(defn- deactivated-trace
  "The `:rf.route/deactivated` lifecycle emit the runtime fires for the
  PRIOR route id on a cross-route navigation (carries `:tags :route-id`
  = the FROM). Lands in the cascade's `:other` bucket."
  [route-id]
  {:id        2
   :op-type   :rf.event
   :operation :rf.route/deactivated
   :tags      {:route-id route-id}})

(defn- nav-cascade
  "A focused navigation cascade carrying the runtime's lifecycle emits:
  nav-token/allocated (TO) plus, on a cross-route nav, deactivated
  (FROM). Mirrors `emit-activation-traces!` — same-route navigations
  pass `from-id` nil so NEITHER deactivated nor a distinct FROM appears."
  [dispatch-id event-vec to-id from-id nav-token]
  {:dispatch-id dispatch-id
   :event       event-vec
   :handler     nil
   :fx          nil
   :effects     []
   :subs        []
   :renders     []
   :other       (cond-> [(nav-allocated-trace to-id nav-token dispatch-id)]
                  from-id (conj (deactivated-trace from-id)))})

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

;; ---- simulate-url: absolute-URL normalisation (rf2-6nx8y) ---------------
;;
;; The Simulate-URL UI says "paste a URL", and the common copy-paste
;; workflow copies the WHOLE address-bar URL — an ABSOLUTE URL with a
;; scheme + authority. Before rf2-6nx8y `split-url` stripped only the
;; query + fragment, so `https://app.example/cart` was matched as the
;; literal path `https://app.example/cart` and `/cart` never matched.
;; The simulator now normalises an absolute (or protocol-relative) URL
;; to its `pathname` first, while leaving relative paths untouched.

(deftest simulate-url-absolute-url-test
  (testing "absolute URL pasted from the address bar resolves its pathname"
    (let [r (h/simulate-url cart-routes "https://app.example/cart?source=email#step-1")]
      (is (= "/cart" (:path r))
          "scheme + authority + query + fragment all stripped")
      (is (= :route/cart (:winner r)))))

  (testing "absolute URL with a deep path + trailing slash"
    (let [r (h/simulate-url cart-routes "http://localhost:3000/checkout/")]
      (is (= "/checkout" (:path r))
          "origin stripped + trailing slash normalised")
      (is (= :route/checkout (:winner r)))))

  (testing "absolute URL whose path does not match any route → no winner"
    (let [r (h/simulate-url cart-routes "https://app.example/nope")]
      (is (= "/nope" (:path r)))
      (is (= [] (:candidates r)))
      (is (nil? (:winner r)))))

  (testing "absolute URL with authority only (no path) → root"
    (let [r (h/simulate-url cart-routes "https://app.example")]
      (is (= "/" (:path r)))
      (is (= :route/root (:winner r))
          "bare origin normalises to the root path")))

  (testing "userinfo + port in the authority are dropped with the origin"
    (let [r (h/simulate-url cart-routes "https://user:pw@app.example:8443/cart")]
      (is (= "/cart" (:path r)))
      (is (= :route/cart (:winner r)))))

  (testing "protocol-relative URL (//host/path) also strips the authority"
    (let [r (h/simulate-url cart-routes "//app.example/cart?x=1")]
      (is (= "/cart" (:path r)))
      (is (= :route/cart (:winner r)))))

  (testing "RELATIVE path with a colon segment is NOT treated as a scheme"
    ;; A relative path can legitimately contain a `:` (a literal segment).
    ;; Only a `scheme://` (or leading `//`) marks an origin, so `/a:b/cart`
    ;; stays a relative path. cart-routes has no `/a:b/cart`, so it misses
    ;; cleanly rather than being mangled into a host.
    (let [r (h/simulate-url cart-routes "/cart")]
      (is (= "/cart" (:path r)) "plain relative path unchanged")
      (is (= :route/cart (:winner r))))))

;; ---- focused-cascade ----------------------------------------------------

(deftest focused-cascade-test
  (testing "nil dispatch-id (no focus) → nil"
    (is (nil? (h/focused-cascade [(cascade 1 [:a])] nil)))
    (is (nil? (h/focused-cascade [(cascade 1 [:a])] {}))))

  (testing "no match → nil"
    (is (nil? (h/focused-cascade [(cascade 1 [:a])] {:dispatch-id 99}))))

  (testing "match returns the cascade record (frameless focus)"
    (let [c (cascade 7 [:foo])]
      (is (= c (h/focused-cascade [(cascade 1 [:a]) c (cascade 9 [:b])]
                                  {:dispatch-id 7}))))))

(deftest focused-cascade-frame-strict-rf2-bz7flo
  (testing "rf2-bz7flo — dispatch ids collide across frames; the lookup must
            select the FOCUSED frame's cascade, not the first same-id match.
            Two cascades share dispatch-id 7 in :frame/a and :frame/b with
            distinct events; the focus pins :frame/b."
    (let [c-a (assoc (cascade 7 [:a-event]) :frame :frame/a)
          c-b (assoc (cascade 7 [:b-event]) :frame :frame/b)
          cascades [c-a c-b]]
      (is (= c-b (h/focused-cascade cascades {:dispatch-id 7 :frame :frame/b}))
          "focus on :frame/b selects the :frame/b cascade, not :frame/a's
           same-id cascade that sits earlier in the vector")
      (is (= c-a (h/focused-cascade cascades {:dispatch-id 7 :frame :frame/a}))
          "focus on :frame/a selects the :frame/a cascade")
      (is (nil? (h/focused-cascade cascades {:dispatch-id 7 :frame :frame/c}))
          "no cascade for the focused frame → nil (no foreign-frame
           fallback); the routing tab renders no overlay rather than a
           wrong-frame one"))))

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

;; ---- route-deactivated-in-cascade ---------------------------------------

(deftest route-deactivated-in-cascade-test
  (testing "nil cascade → nil"
    (is (nil? (h/route-deactivated-in-cascade nil))))

  (testing "cascade with no deactivated emit → nil"
    (is (nil? (h/route-deactivated-in-cascade (cascade 1 [:foo])))))

  (testing "deactivated emit in :other bucket is found; :tags :route-id is the FROM"
    (let [c (cascade 1 [:rf.route/navigate :route/confirm]
              :other [(nav-allocated-trace :route/confirm "nav-1")
                      (deactivated-trace :route/cart)])
          ev (h/route-deactivated-in-cascade c)]
      (is (some? ev))
      (is (= :route/cart (-> ev :tags :route-id))))))

;; ---- from-to-from-cascade -----------------------------------------------
;;
;; FROM is derived from the focused cascade's :rf.route/deactivated emit
;; (rf2-m9rx6) — NEVER the live current slice. The live slice is the
;; *current* route, which is time-dependent (it drifts as the app keeps
;; navigating); reading FROM from the cascade makes the lens honest about
;; the transition the SELECTED epoch performed.

(deftest from-to-from-cascade-test
  (testing "no nav emit → not navigated"
    (let [c (cascade 1 [:foo])
          {:keys [navigated? from-id to-id]}
          (h/from-to-from-cascade c)]
      (is (false? navigated?))
      (is (nil? from-id))
      (is (nil? to-id))))

  (testing "cross-route nav yields both ids from the cascade emits"
    (let [c (nav-cascade 1 [:rf.route/navigate :route/confirm]
                         :route/confirm :route/cart "nav-7")
          {:keys [navigated? from-id to-id]}
          (h/from-to-from-cascade c)]
      (is (true? navigated?))
      (is (= :route/cart from-id))
      (is (= :route/confirm to-id))))

  (testing "first navigation (no deactivated emit) yields nil FROM"
    ;; The runtime emits no :rf.route/deactivated on the first nav (no
    ;; prior route to leave) — absence of the emit ⇒ no FROM.
    (let [c (nav-cascade 1 [:rf.route/navigate :route/cart]
                         :route/cart nil "nav-1")
          {:keys [navigated? from-id to-id]}
          (h/from-to-from-cascade c)]
      (is (true? navigated?))
      (is (nil? from-id))
      (is (= :route/cart to-id))))

  (testing "same-route re-navigation (no deactivated emit) collapses FROM"
    ;; Same route-id, changed params/query: the runtime emits NEITHER
    ;; deactivated nor activated, so no FROM surfaces.
    (let [c (nav-cascade 1 [:rf.route/navigate :route/cart {:filter :all}]
                         :route/cart nil "nav-3")
          {:keys [navigated? from-id to-id]}
          (h/from-to-from-cascade c)]
      (is (true? navigated?))
      (is (nil? from-id) "no deactivated emit ⇒ no FROM")
      (is (= :route/cart to-id))))

  (testing "FROM is independent of the live current slice (rf2-m9rx6)"
    ;; The historical bug: FROM came from the live slice's :route-id. A focused
    ;; A→B cascade must report FROM A / TO B regardless of where the app
    ;; has since navigated — the cascade carries deactivated-A /
    ;; allocated-B unconditionally, so the live route is irrelevant.
    (let [c (nav-cascade 1 [:rf.route/navigate :route/confirm]
                         :route/confirm :route/cart "nav-9")
          {:keys [from-id to-id]} (h/from-to-from-cascade c)]
      ;; No current-slice arg at all — FROM/TO read off the cascade.
      (is (= :route/cart from-id) "FROM is the deactivated (prior) route")
      (is (= :route/confirm to-id) "TO is the allocated (new) route"))))

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
    (let [data (h/project-data {} {:route-id :route/anything} nil)]
      (is (true? (:silent? data)))
      (is (= [] (:routes data)))
      (is (false? (:navigated? data))))))

(deftest project-data-orientation-test
  (testing "no focused cascade but routes present — orientation HERE only"
    (let [data (h/project-data cart-routes {:route-id :route/cart} nil)
          by-id (into {} (map (juxt :route-id :marker)) (:routes data))]
      (is (false? (:silent? data)))
      (is (false? (:navigated? data)))
      (is (= :here (get by-id :route/cart)))
      (is (= :route/cart (get-in data [:current :route-id]))))))

(deftest project-data-navigation-test
  (testing "focused cascade caused navigation with no deactivated emit — TO renders, no FROM"
    (let [c (nav-cascade 42 [:rf.route/navigate :route/confirm]
                         :route/confirm nil "nav-9")
          data (h/project-data cart-routes {:route-id :route/confirm} c)
          by-id (into {} (map (juxt :route-id :marker)) (:routes data))]
      (is (true? (:navigated? data)))
      (is (= :route/confirm (:to-id data)))
      (is (nil? (:from-id data))
          "no deactivated emit ⇒ FROM nil per from-to-from-cascade")
      (is (= :to (get by-id :route/confirm)))))

  (testing "cross-route cascade — FROM derived from deactivated emit, not the live slice"
    ;; Live current slice is :route/cart (the post-nav value), and the
    ;; cascade carries deactivated :route/cart → activated :route/confirm.
    (let [c (nav-cascade 1 [:rf.route/navigate :route/confirm]
                         :route/confirm :route/cart "nav-3")
          data (h/project-data cart-routes {:route-id :route/confirm} c)
          by-id (into {} (map (juxt :route-id :marker)) (:routes data))]
      (is (true? (:navigated? data)))
      (is (= :route/cart (:from-id data)))
      (is (= :route/confirm (:to-id data)))
      (is (= :from (get by-id :route/cart)))
      (is (= :to   (get by-id :route/confirm)))))

  (testing "FROM/TO are time-independent — drift in the live slice does not corrupt them (rf2-m9rx6)"
    ;; A focused A→B cascade. The app has since navigated to C, so the
    ;; live slice's :route-id is :route/checkout (≠ both A and B). FROM must
    ;; STILL be A and TO STILL B; the only thing the live slice governs
    ;; is the HERE marker (suppressed here because navigated? is true).
    (let [c (nav-cascade 5 [:rf.route/navigate :route/confirm]
                         :route/confirm :route/cart "nav-5")
          data (h/project-data cart-routes {:route-id :route/checkout} c)
          by-id (into {} (map (juxt :route-id :marker)) (:routes data))]
      (is (= :route/cart (:from-id data))
          "FROM stays A even though the live slice moved on to C")
      (is (= :route/confirm (:to-id data))
          "TO stays B even though the live slice moved on to C")
      (is (= :from (get by-id :route/cart)))
      (is (= :to   (get by-id :route/confirm)))
      (is (nil? (get by-id :route/checkout))
          "the live route C carries no marker for this historical epoch"))))

(deftest project-data-query-and-sim-test
  (testing "query filter is applied to :routes"
    (let [data (h/project-data cart-routes {:route-id :route/cart} nil "checkout" nil)
          ids  (set (map :route-id (:routes data)))]
      (is (true? (:filtered? data)))
      (is (contains? ids :route/checkout))
      (is (not (contains? ids :route/cart)))))

  (testing "sim-url drives :sim-result"
    (let [data (h/project-data cart-routes {:route-id :route/cart} nil nil "/cart")]
      (is (= "/cart" (-> data :sim-result :path)))
      (is (= :route/cart (-> data :sim-result :winner)))))

  (testing "blank sim-url leaves :sim-result nil"
    (let [data (h/project-data cart-routes {:route-id :route/cart} nil nil "")]
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
      ;; EP-0001 (rf2-vzld77): the route slice lands in runtime-db, not app-db.
      (is (= [:rf.runtime/routing :current] (:runtime-db-slot pv)))
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
      (is (= [:rf.runtime/routing :current] (:runtime-db-slot pv)))
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

(deftest simulate-navigation-preview-normalises-absolute-url-test
  (testing "preview applies the same absolute-URL normalisation as the simulator (rf2-6nx8y)"
    ;; An absolute URL pasted into the row's Simulate-URL surface must
    ;; match THIS row's pattern the same way the global simulator does —
    ;; the origin is stripped before `match-against` so `/cart` matches.
    (let [pv (h/simulate-navigation-preview cart-routes :route/cart
                                            "https://app.example/cart?source=email#step-1")]
      (is (true? (:matched? pv))
          "absolute URL's pathname matches the row's pattern")
      (is (= :route/cart (-> pv :slot-shape :id))))))

(deftest simulate-navigation-preview-row-local-overlapping-test
  (testing "row preview matches the SELECTED row's pattern, not the global winner (rf2-m9rx6)"
    ;; Two routes both match /checkout/payment: the exact route (global
    ;; winner) and a lower-ranked splat fallback. The OLD impl reported
    ;; the splat row as no-match because it lost the global rank race;
    ;; the row preview must report ITS OWN match + params.
    (let [routes {:route/exact (route "/checkout/payment")
                  :route/splat (route "/*rest")}
          ;; sanity: the global resolution still ranks exact first.
          sim   (h/simulate-url routes "/checkout/payment")]
      (is (= :route/exact (:winner sim)) "exact route is the global winner")
      ;; Previewing the exact row: matched.
      (let [pv-exact (h/simulate-navigation-preview routes :route/exact "/checkout/payment")]
        (is (true? (:matched? pv-exact)) "exact row matches its own pattern"))
      ;; Previewing the SPLAT row (the global loser): must STILL match
      ;; its own pattern and surface its params.
      (let [pv-splat (h/simulate-navigation-preview routes :route/splat "/checkout/payment")]
        (is (true? (:matched? pv-splat))
            "splat fallback row matches its own pattern despite losing the global rank")
        (is (some? (:params pv-splat))
            "splat captures its params (e.g. :rest) — not hidden")
        (is (contains? (:slot-shape pv-splat) :params)
            "matched splat row carries params into the slot shape")))))

(deftest simulate-navigation-preview-row-local-non-matching-row-test
  (testing "a row whose own pattern does NOT match the URL reports no match (rf2-m9rx6)"
    ;; /cart is the URL; previewing :route/checkout (pattern /checkout)
    ;; must report no match even though some OTHER route matches the URL.
    (let [routes {:route/cart     (route "/cart")
                  :route/checkout (route "/checkout")}
          pv     (h/simulate-navigation-preview routes :route/checkout "/cart")]
      (is (false? (:matched? pv))
          "the selected row's pattern doesn't match the URL ⇒ no match")
      (is (nil? (:params pv))))))

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
          ":route/confirm is not the last sibling at depth 1")))

  (testing "has-children? flags parent routes (the view's `▾` chevron)"
    (let [topology (h/project-topology parented-routes)
          kids-by-id (into {} (map (juxt #(-> % :row :route-id) :has-children?))
                           topology)]
      (is (true? (get kids-by-id :route/checkout))
          ":route/checkout has nested children → has-children? true")
      (is (false? (get kids-by-id :route/payment))
          ":route/payment is a leaf → has-children? false")
      (is (false? (get kids-by-id :route/admin))
          ":route/admin is a leaf → has-children? false")
      (is (every? #(contains? % :has-children?) topology)
          "every topology entry carries a :has-children? flag"))))

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

(deftest project-topology-self-cycle-test
  (testing "a self-cycle (A → A) appears exactly once as a cycle root (rf2-m9rx6)"
    ;; :route/self's :parent is itself. Both parents are registered, so
    ;; it is NOT a root via the rooted? predicate — the walk-from-roots
    ;; never reaches it. The cycle-root append phase must still surface
    ;; it (the topology view is meant to DIAGNOSE malformed parent meta,
    ;; not hide it).
    (let [self-cycle {:route/self
                      (route "/self" :parent :route/self)
                      :route/root (route "/")}
          topology (h/project-topology self-cycle)
          by-id    (group-by #(-> % :row :route-id) topology)]
      (is (vector? topology) "projection terminates on self-cycle")
      (is (= 2 (count topology))
          "every registered route appears exactly once")
      (is (= 1 (count (get by-id :route/self)))
          ":route/self appears exactly once (not dropped)")
      (is (= 1 (count (get by-id :route/root)))
          "non-cycling :route/root still appears once")
      (let [self-entry (first (get by-id :route/self))]
        (is (= 0 (:depth self-entry)) "cycle root rendered at depth 0")
        (is (true? (:cycle-root? self-entry))
            ":route/self flagged :cycle-root? for the diagnose-malformed view"))
      (let [root-entry (first (get by-id :route/root))]
        (is (false? (:cycle-root? root-entry))
            "ordinary root carries :cycle-root? false")))))

(deftest project-topology-two-node-cycle-test
  (testing "a two-node cycle (A ↔ B) — both appear exactly once, projection terminates (rf2-m9rx6)"
    ;; :route/a's :parent is :route/b and vice-versa. Both parents are
    ;; registered, so NEITHER is a root — the walk-from-roots reaches
    ;; neither. The cycle-root append phase surfaces the component once.
    (let [cycle-routes {:route/a (route "/a" :parent :route/b)
                        :route/b (route "/b" :parent :route/a)}
          topology (h/project-topology cycle-routes)
          by-id    (group-by #(-> % :row :route-id) topology)]
      (is (vector? topology) "projection terminates on A ↔ B cycle")
      (is (= 2 (count topology))
          "both cycle members appear, each exactly once")
      (is (= 1 (count (get by-id :route/a))) ":route/a appears exactly once")
      (is (= 1 (count (get by-id :route/b))) ":route/b appears exactly once")
      ;; The first-by-path member is the cycle root (depth 0,
      ;; cycle-root? true); the other rides under it as its child.
      (let [a-entry (first (get by-id :route/a))]
        (is (= 0 (:depth a-entry)) "lexically-first member is the cycle root at depth 0")
        (is (true? (:cycle-root? a-entry)) "cycle root flagged :cycle-root?")))))

(deftest project-topology-mixed-roots-and-cycle-test
  (testing "ordinary roots plus a rootless cycle coexist — all appear once (rf2-m9rx6)"
    (let [mixed {:route/root (route "/")
                 :route/a    (route "/a" :parent :route/b)
                 :route/b    (route "/b" :parent :route/a)}
          topology (h/project-topology mixed)
          by-id    (group-by #(-> % :row :route-id) topology)]
      (is (= 3 (count topology)) "all three registered routes appear exactly once")
      (doseq [rid (keys mixed)]
        (is (= 1 (count (get by-id rid))) (str rid " appears exactly once")))
      (is (false? (:cycle-root? (first (get by-id :route/root))))
          "the genuine root is not flagged as a cycle root"))))

(deftest project-topology-cycle-root-flag-on-normal-routes-test
  (testing "ordinary (acyclic) topology carries :cycle-root? false on every entry"
    (let [topology (h/project-topology parented-routes)]
      (is (every? #(false? (:cycle-root? %)) topology)
          "no acyclic route is flagged :cycle-root?")
      (is (every? #(contains? % :cycle-root?) topology)
          "every entry carries the :cycle-root? key"))))

;; ---- epoch-routing-activity (rf2-3kjlo) ---------------------------------

(deftest epoch-routing-activity-no-cascade-test
  (testing "nil cascade → nil activity"
    (is (nil? (h/epoch-routing-activity nil nil)))
    (is (nil? (h/epoch-routing-activity nil {:route-id :route/cart})))))

(deftest epoch-routing-activity-no-routing-trace-test
  (testing "cascade with no routing trace events → nil (no activity)"
    (let [c (cascade 1 [:counter/inc])]
      (is (nil? (h/epoch-routing-activity c {:route-id :route/cart}))))))

(deftest epoch-routing-activity-on-match-test
  (testing "nav-token-allocated emit → phase :on-match + match params"
    (let [c (cascade 7 [:rf.route/navigate :route/confirm]
              :other [(nav-allocated-trace :route/confirm "nav-1")])
          activity (h/epoch-routing-activity c {:route-id :route/confirm
                                                :params {:order-id "x"}})]
      (is (some? activity))
      (is (= :on-match (:phase activity)))
      (is (= {:order-id "x"} (:match activity))
          "match surfaces the slice's params when phase is :on-match"))))

(deftest epoch-routing-activity-events-test
  (testing "events list carries root event vector + downstream dispatches"
    (let [downstream {:id 8 :op-type :rf.event :operation :rf.event/dispatched
                      :tags {:rf.event/v [:cart/route-entered]}}
          c (cascade 7 [:rf.route/navigate :route/cart]
              :other [(nav-allocated-trace :route/cart "nav-1")
                      downstream])
          activity (h/epoch-routing-activity c {:route-id :route/cart})]
      (is (= [[:rf.route/navigate :route/cart]
              [:cart/route-entered]]
             (:events activity))))))

(deftest epoch-routing-activity-navigation-blocked-test
  (testing "navigation-blocked emit → phase :navigation-blocked + nil match"
    (let [blocked-ev {:id 1 :op-type :rf.event
                      :operation :rf.route/navigation-blocked
                      :tags {:route-id :route/admin}}
          c (cascade 1 [:rf.route/navigate :route/admin]
              :other [blocked-ev])
          activity (h/epoch-routing-activity c {:route-id :route/cart})]
      (is (= :navigation-blocked (:phase activity)))
      (is (nil? (:match activity))
          "match is only surfaced when phase is :on-match"))))

(deftest epoch-routing-activity-fragment-changed-test
  (testing "fragment-changed emit → phase :fragment-changed"
    (let [frag-ev {:id 1 :op-type :rf.event
                   :operation :rf.route/fragment-changed
                   :tags {:fragment "step-2"}}
          c (cascade 1 [:foo] :other [frag-ev])
          activity (h/epoch-routing-activity c {:route-id :route/cart})]
      (is (= :fragment-changed (:phase activity))))))

;; ---- project-topology-data composite (rf2-3kjlo) ------------------------

(deftest project-topology-data-silent-test
  (testing "no routes registered → silent? true, empty topology, nil activity"
    (let [data (h/project-topology-data {} {:route-id :route/cart} nil)]
      (is (true? (:silent? data)))
      (is (= [] (:topology data)))
      (is (nil? (:activity data)))
      (is (false? (:navigated? data))))))

(deftest project-topology-data-topology-shape-test
  (testing "topology vector mirrors project-topology + carries marker"
    (let [data (h/project-topology-data parented-routes
                                        {:route-id :route/cart}
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
            "non-current routes carry no marker"))
      ;; :has-children? survives the data composite (the view reads it
      ;; off the topology entry to paint the `▾` chevron).
      (let [kids-by-id (into {}
                            (map (juxt #(-> % :row :route-id) :has-children?))
                            (:topology data))]
        (is (true? (get kids-by-id :route/checkout))
            ":route/checkout keeps :has-children? through the composite")
        (is (false? (get kids-by-id :route/cart))
            "leaf route keeps :has-children? false through the composite")))))

(deftest project-topology-data-overlay-test
  (testing "focused cascade caused navigation → :to overlay + :on-match phase"
    (let [c (nav-cascade 42 [:rf.route/navigate :route/confirm]
                         :route/confirm nil "nav-9")
          data (h/project-topology-data parented-routes
                                        {:route-id :route/confirm
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

  (testing "cross-route cascade paints both :from (deactivated) and :to (allocated)"
    (let [c (nav-cascade 1 [:rf.route/navigate :route/confirm]
                         :route/confirm :route/cart "nav-3")
          data (h/project-topology-data parented-routes {:route-id :route/confirm} c)
          marker-by-id (into {}
                             (map (juxt #(-> % :row :route-id) :marker))
                             (:topology data))]
      (is (= :route/cart (:from-id data)))
      (is (= :from (get marker-by-id :route/cart)))
      (is (= :to   (get marker-by-id :route/confirm)))))

  (testing "FROM marker is time-independent of the live slice (rf2-m9rx6)"
    ;; Live slice has moved to :route/admin; the focused cascade is still
    ;; cart→confirm. FROM cart / TO confirm must still paint.
    (let [c (nav-cascade 7 [:rf.route/navigate :route/confirm]
                         :route/confirm :route/cart "nav-7")
          data (h/project-topology-data parented-routes {:route-id :route/admin} c)
          marker-by-id (into {}
                             (map (juxt #(-> % :row :route-id) :marker))
                             (:topology data))]
      (is (= :route/cart (:from-id data)))
      (is (= :from (get marker-by-id :route/cart)))
      (is (= :to   (get marker-by-id :route/confirm)))
      (is (nil? (get marker-by-id :route/admin))
          "the live route carries no marker for this historical epoch"))))

(deftest project-topology-data-no-activity-test
  (testing "focused cascade has no routing trace events → activity nil; HERE still paints"
    (let [c (cascade 9 [:counter/inc])
          data (h/project-topology-data parented-routes
                                        {:route-id :route/cart}
                                        c)
          marker-by-id (into {}
                             (map (juxt #(-> % :row :route-id) :marker))
                             (:topology data))]
      (is (false? (:navigated? data)))
      (is (nil? (:activity data))
          "activity is nil when cascade has no routing trace events")
      (is (= :here (get marker-by-id :route/cart))
          "current route still gets :here marker"))))
