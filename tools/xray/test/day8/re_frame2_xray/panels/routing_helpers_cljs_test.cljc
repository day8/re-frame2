(ns day8.re-frame2-xray.panels.routing-helpers-cljs-test
  "Pure-data tests for Xray's Routes tab helpers. Dual-target `.cljc`: the JVM
  test-runner and the `:node-test` build both run it.

  The suite runs under the core reset fixture (plain-atom) because the
  producer-derived rows register real routes and navigate to them; every
  other row is pure data and ignores the runtime."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test    :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.routing]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            #?(:clj  [re-frame.test-support :as rf.test-support
                      :refer [with-trace-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support
                      :refer-macros [with-trace-recorder!]])
            [re-frame.trace.projection :as rf.trace.projection]
            [day8.re-frame2-xray.panels.routing-helpers :as h]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- fixture builders ---------------------------------------------------

(defn- route
  "A registrar-shaped route metadata map. Carries no `:rf.route/compiled`, so
  the simulator compiles the pattern on demand."
  [path & {:keys [doc parent tags on-match can-leave]}]
  (cond-> {:path path}
    doc       (assoc :doc doc)
    parent    (assoc :parent parent)
    tags      (assoc :tags tags)
    on-match  (assoc :on-match on-match)
    can-leave (assoc :can-leave can-leave)))

(def cart-routes
  "A small e-commerce route set."
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
  "The `:rf.route/deactivated` emit for the PRIOR route of a cross-route
  navigation — the FROM."
  [route-id]
  {:id        2
   :op-type   :rf.event
   :operation :rf.route/deactivated
   :tags      {:route-id route-id}})

(defn- nav-cascade
  "A focused navigation cascade: nav-token/allocated (TO) plus, on a
  cross-route nav, deactivated (FROM)."
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

(def ^:private nav-route-id
  "A route id this suite alone registers, so no ns-load registration in the
  shared node bundle carries a second row for it."
  :routing-helpers-test/article)

(defn- navigated-slice
  "The route slice ONE real `:rf.route/navigate` writes at
  `[:rf.runtime/routing :current]`. Taken from the producer so a slot-shape pin
  cannot agree with a fixture typed by the same hand."
  []
  (rf/reg-route nav-route-id {} "/articles/:slug")
  (rf/dispatch-sync [:rf.route/navigate {:to     nav-route-id
                                         :params {:slug "welcome"}}])
  (get-in (rf.frame/frame-runtime-db-value :rf/default)
          [:rf.runtime/routing :current]))

;; ---- project-routes -----------------------------------------------------

(deftest project-routes-test
  (testing "a row carries the route's catalogue fields and its registrar entry verbatim"
    (is (= [{:route-id :route/root :path "/" :doc nil :parent nil :tags nil
             :has-on-match? false :has-can-leave? false :rank nil :meta {:path "/"}}]
           (h/project-routes {:route/root (route "/")}))))
  (let [rows  (h/project-routes cart-routes)
        by-id (into {} (map (juxt :route-id identity)) rows)]
    (testing "rows are sorted by path"
      (is (= (sort (map :path rows)) (map :path rows))))
    (testing "a declared :on-match / :can-leave is flagged"
      (is (= [true true] [(get-in by-id [:route/audit :has-on-match?])
                          (get-in by-id [:route/admin :has-can-leave?])])))))

;; ---- filter-rows --------------------------------------------------------

(deftest filter-rows-test
  (let [rows (h/project-routes cart-routes)
        ids  #(set (map :route-id (h/filter-rows rows %)))]
    (testing "a nil or blank query is identity"
      (is (= rows (h/filter-rows rows nil)))
      (is (= rows (h/filter-rows rows "   "))))
    (testing "substring match on path, route-id and doc"
      (is (= #{:route/checkout :route/payment :route/confirm} (ids "checkout")))
      (is (= #{:route/audit} (ids "audit")))
      (is (= #{:route/cart} (ids "shopping"))))
    (testing "case-insensitive"
      (is (= (ids "CHECKOUT") (ids "checkout"))))))

;; ---- simulate-url -------------------------------------------------------

(deftest simulate-url-test
  (testing "a nil URL yields the empty result"
    (is (= {:url nil :path nil :candidates [] :winner nil}
           (h/simulate-url cart-routes nil))))
  (testing "an exact path match is the sole, winning candidate"
    (let [r (h/simulate-url cart-routes "/cart")]
      (is (= :route/cart (:winner r)))
      (is (= [[:route/cart true]] (mapv (juxt :route-id :winner?) (:candidates r))))))
  (testing "query and fragment are stripped before matching"
    (is (= "/cart" (:path (h/simulate-url cart-routes "/cart?source=email#step-1")))))
  (testing "EVERY trailing slash strips, as `match-url` strips them"
    (let [r (h/simulate-url cart-routes "/cart//")]
      (is (= "/cart" (:path r)))
      (is (= :route/cart (:winner r)))))
  (testing "every matching route is a candidate, ranked descending"
    (let [r (h/simulate-url {:route/exact (route "/checkout/payment")
                             :route/splat (route "/*rest")}
                            "/checkout/payment")]
      (is (= [:route/exact :route/splat] (mapv :route-id (:candidates r)))
          "the static-heavy pattern outranks the splat"))))

;; ---- simulate-url: absolute-URL normalisation ---------------------------
;;
;; A URL pasted from the address bar is absolute; the simulator reduces it to
;; its pathname, and only a LEADING `scheme://` or `//` marks an origin.

(deftest simulate-url-absolute-url-test
  (testing "an absolute URL resolves to its pathname"
    (is (= "/cart" (:path (h/simulate-url cart-routes "https://app.example/cart?source=email#step-1")))))
  (testing "an origin with no path is the root"
    (is (= "/" (:path (h/simulate-url cart-routes "https://app.example")))))
  (testing "a protocol-relative URL strips its authority too"
    (is (= "/cart" (:path (h/simulate-url cart-routes "//app.example/cart?x=1")))))
  (testing "a `://` in the QUERY is not an origin (redirect / OAuth `?next=` URLs)"
    (is (= "/login" (:path (h/simulate-url cart-routes "/login?next=https://app.example/cart")))))
  (testing "a `://` inside a relative PATH is not an origin either"
    (let [r (h/simulate-url cart-routes "/go/https://app.example/cart")]
      (is (= "/go/https://app.example/cart" (:path r)))
      (is (nil? (:winner r)) "no route matches the literal path — it is not rewritten to `/cart`"))))

;; ---- focused-event-bundle ----------------------------------------------------

(deftest focused-event-bundle-test
  (testing "a frameless focus matches by dispatch-id"
    (let [c (cascade 7 [:foo])]
      (is (= c (h/focused-event-bundle [(cascade 1 [:a]) c (cascade 9 [:b])]
                                       {:dispatch-id 7}))))))

(deftest focused-event-bundle-frame-strict-rf2-bz7flo
  (testing "dispatch ids collide across frames; the lookup selects the FOCUSED
            frame's cascade, not the first same-id match"
    (let [c-a (assoc (cascade 7 [:a-event]) :frame :frame/a)
          c-b (assoc (cascade 7 [:b-event]) :frame :frame/b)]
      (is (= c-b (h/focused-event-bundle [c-a c-b] {:dispatch-id 7 :frame :frame/b}))
          "focus on :frame/b selects :frame/b's cascade, not :frame/a's earlier one")
      (is (nil? (h/focused-event-bundle [c-a c-b] {:dispatch-id 7 :frame :frame/c}))
          "no cascade in the focused frame → nil, never a foreign-frame fallback"))))

;; ---- project-static-data ----------------------------------------------

(deftest project-static-data-empty-test
  (testing "silent state — no routes registered"
    (is (= {:silent? true :routes [] :total-routes 0 :filtered? false
            :query nil :sim-url nil :sim-result nil}
           (h/project-static-data {} nil nil)))))

(deftest project-static-data-query-test
  (testing "a query narrows the rows and flips :filtered?"
    (let [data (h/project-static-data cart-routes "checkout" nil)]
      (is (true? (:filtered? data)))
      (is (= #{:route/checkout :route/payment :route/confirm}
             (set (map :route-id (:routes data)))))))
  (testing "a blank query keeps every row"
    (is (= [false 8 false 8]
           ((juxt :silent? :total-routes :filtered? (comp count :routes))
            (h/project-static-data cart-routes "" nil))))))

(deftest project-static-data-sim-url-test
  (testing "a non-blank sim-url drives :sim-result"
    (is (= :route/cart (-> (h/project-static-data cart-routes nil "/cart") :sim-result :winner))))
  (testing "a blank sim-url leaves :sim-result nil"
    (is (nil? (:sim-result (h/project-static-data cart-routes nil ""))))))

;; ---- simulate-navigation-preview ---------------------------------------

(deftest simulate-navigation-preview-unknown-test
  (testing "an unknown route-id returns the unknown shape"
    (is (= {:route-id :route/nope :unknown? true}
           (h/simulate-navigation-preview cart-routes :route/nope nil)))))

(deftest simulate-navigation-preview-slot-is-the-slice-navigate-writes
  (testing "the preview's slot shape is the key set ONE real
            `:rf.route/navigate` writes at [:rf.runtime/routing :current]"
    (let [slice (navigated-slice)
          slot  (:slot-shape (h/simulate-navigation-preview
                               (rf/registrations {:source :store :kind :route})
                               nav-route-id "/articles/welcome"))]
      (is (= nav-route-id (:route-id slice))
          "PRECONDITION: the real navigation landed — otherwise the key set below is not the router's")
      (is (= (set (keys slice)) (set (keys slot)))
          (str "slot keys " (pr-str (sort (keys slot)))
               " vs the slice's " (pr-str (sort (keys slice)))))
      (is (= (:params slice) (:params slot))
          "the params the row matches are the params the navigation wrote"))))

(deftest simulate-navigation-preview-no-url-test
  (testing "a registered route with no URL: its pattern, on-match and slot, no params"
    (let [pv (h/simulate-navigation-preview cart-routes :route/audit nil)]
      (is (= {:route-id        :route/audit
              :path            "/admin/audit"
              :url             nil
              :matched?        false
              :params          nil
              :on-match        [:audit/load]
              :runtime-db-slot [:rf.runtime/routing :current]
              :unknown?        false}
             (dissoc pv :slot-shape)))
      (is (= [:route/audit nil] ((juxt :route-id :params) (:slot-shape pv)))))))

(deftest simulate-navigation-preview-with-matching-url-test
  (testing "a matching URL → :matched? and its params carried into the slot"
    (is (= [true "/cart" {}]
           ((juxt :matched? :url (comp :params :slot-shape))
            (h/simulate-navigation-preview cart-routes :route/cart "/cart"))))))

(deftest simulate-navigation-preview-with-mismatching-url-test
  (testing "a URL that does not match this route's pattern carries no params"
    (is (= [false nil nil]
           ((juxt :matched? :params (comp :params :slot-shape))
            (h/simulate-navigation-preview cart-routes :route/cart "/checkout"))))))

(deftest simulate-navigation-preview-normalises-absolute-url-test
  (testing "the preview strips an absolute URL's origin as the simulator does"
    (is (true? (:matched? (h/simulate-navigation-preview
                            cart-routes :route/cart
                            "https://app.example/cart?source=email#step-1"))))))

(deftest simulate-navigation-preview-row-local-overlapping-test
  (testing "the preview matches the SELECTED row's pattern, not the global winner:
            the splat fallback loses the rank race for /checkout/payment, yet its
            own preview still matches and captures its params"
    (let [pv (h/simulate-navigation-preview {:route/exact (route "/checkout/payment")
                                             :route/splat (route "/*rest")}
                                            :route/splat "/checkout/payment")]
      (is (true? (:matched? pv)))
      (is (some? (:params pv))))))

;; ---- project-topology -------------------------------------------------

(def parented-routes
  "/checkout has two child routes; the rest sit at depth 0."
  {:route/root      (route "/")
   :route/cart      (route "/cart")
   :route/checkout  (route "/checkout")
   :route/payment   (route "/checkout/payment"
                           :parent :route/checkout)
   :route/confirm   (route "/checkout/confirm"
                           :parent :route/checkout)
   :route/admin     (route "/admin")})

(defn- topology-rows [topology & ks]
  (mapv (apply juxt #(-> % :row :route-id) ks) topology))

(deftest project-topology-depth-and-shape-test
  (testing "every route once, children after their parent at depth + 1, roots and
            siblings sorted by path, with the last-sibling, has-children and cycle flags"
    (is (= [[:route/root     0 false false false]
            [:route/admin    0 false false false]
            [:route/cart     0 false false false]
            [:route/checkout 0 true  true  false]
            [:route/confirm  1 false false false]
            [:route/payment  1 true  false false]]
           (topology-rows (h/project-topology parented-routes)
                          :depth :last-at-depth? :has-children? :cycle-root?)))))

(deftest project-topology-orphan-parent-test
  (testing "a row whose :parent is unregistered becomes a depth-0 root"
    (is (= [[:route/root 0] [:route/orphan 0]]
           (topology-rows (h/project-topology {:route/orphan (route "/orphan" :parent :route/missing)
                                               :route/root   (route "/")})
                          :depth)))))

(deftest project-topology-self-cycle-test
  (testing "a self-cycle (A → A) has no root, yet appears exactly once as a cycle root"
    (is (= [[:route/root 0 false] [:route/self 0 true]]
           (topology-rows (h/project-topology {:route/self (route "/self" :parent :route/self)
                                               :route/root (route "/")})
                          :depth :cycle-root?)))))

(deftest project-topology-two-node-cycle-test
  (testing "a two-node cycle (A ↔ B): the path-first member is the cycle root,
            the other rides under it, each exactly once"
    (is (= [[:route/a 0 true] [:route/b 1 false]]
           (topology-rows (h/project-topology {:route/a (route "/a" :parent :route/b)
                                               :route/b (route "/b" :parent :route/a)})
                          :depth :cycle-root?)))))

;; ---- epoch-routing-activity -------------------------------------------

(deftest epoch-routing-activity-events-test
  (testing "events list carries root event vector + downstream dispatches"
    (let [downstream {:id 8 :op-type :rf.event :operation :rf.event/dispatched
                      :tags {:rf.event/v [:cart/route-entered]}}
          c (cascade 7 [:rf.route/navigate {:to :route/cart}]
              :other [(nav-allocated-trace :route/cart "nav-1")
                      downstream])
          activity (h/epoch-routing-activity c {:route-id :route/cart})]
      (is (= [[:rf.route/navigate {:to :route/cart}]
              [:cart/route-entered]]
             (:events activity))))))

;; ---- a HISTORICAL navigation's params -----------------------------------

(def ^:private hist-route-id
  "Route ids this row alone registers (see `nav-route-id`)."
  :routing-helpers-test/hist-article)
(def ^:private hist-other-route-id :routing-helpers-test/hist-other)

(defn- navigate-capturing!
  "Navigate for real and return `[bundle slice]`: the navigation's event-bundle,
  projected from its captured trace by `group-by-event`, and the slice it
  committed."
  [route-id params]
  (with-trace-recorder! [traces]
    (rf/dispatch-sync [:rf.route/navigate {:to route-id :params params}])
    [(->> (rf.trace.projection/group-by-event @traces)
          (filter h/nav-token-allocated-in-event-bundle)
          first)
     (get-in (rf.frame/frame-runtime-db-value :rf/default)
             [:rf.runtime/routing :current])]))

(deftest epoch-routing-activity-reads-a-historical-navigations-own-params
  (testing "focused on an EARLIER navigation, :match is that navigation's params,
            never the live route's"
    (rf/reg-route hist-route-id {} "/hist-articles/:id")
    (rf/reg-route hist-other-route-id {} "/hist-other")
    (let [[bundle-1 after-1] (navigate-capturing! hist-route-id {:id "1"})
          [bundle-3 live]    (navigate-capturing! hist-route-id {:id "3"})]
      (testing "the live slice is the focused navigation → its params"
        (is (= {:id "3"} (:match (h/epoch-routing-activity bundle-3 live)))))
      (testing "the live slice is a LATER navigation → never its params"
        (is (nil? (:match (h/epoch-routing-activity bundle-1 live)))
            "with no post-state slice to read, no params rather than {:id \"3\"}"))
      (testing "the focused epoch's post-state slice supplies its own params"
        (is (= {:id "1"} (:match (h/epoch-routing-activity bundle-1 live after-1)))))
      (testing "but only while the live route is the same route — the on-box
                projection classifies under the LIVE route's declarations"
        (let [[_ live-other] (navigate-capturing! hist-other-route-id {})]
          (is (nil? (:match (h/epoch-routing-activity bundle-1 live-other after-1)))))))))

;; ---- a REFUSED navigation's ends ----------------------------------------

(def ^:private guarded-route-id
  "Route ids this row alone registers (see `nav-route-id`)."
  :routing-helpers-test/guarded)
(def ^:private open-route-id :routing-helpers-test/open)
(def ^:private members-route-id :routing-helpers-test/members)

(defn- refused-bundle!
  "Navigate to `route-id` for real and return the `:rf.route/navigate`
  event-bundle, projected from its captured trace by `group-by-event`."
  [route-id]
  (with-trace-recorder! [traces]
    (rf/dispatch-sync [:rf.route/navigate {:to route-id}])
    (->> (rf.trace.projection/group-by-event @traces)
         (filter #(= :rf.route/navigate (first (:event %))))
         first)))

(deftest project-topology-data-names-the-ends-of-a-refused-navigation
  (testing "a guard-refused navigation allocates no nav-token and deactivates
            nothing; its ends come off the refusal trace the router emits"
    (rf/reg-sub ::leave-ok? (fn [db _] (not (::dirty? db))))
    (rf/reg-sub ::enter-ok? (fn [_ _] false))
    (rf/reg-event ::dirty (fn [{:keys [db]} _] {:db (assoc db ::dirty? true)}))
    (rf/reg-route guarded-route-id {:can-leave ::leave-ok?} "/refused-test/guarded")
    (rf/reg-route open-route-id {} "/refused-test/open")
    (rf/reg-route members-route-id {:can-enter ::enter-ok?} "/refused-test/members")
    (rf/dispatch-sync [:rf.route/navigate {:to guarded-route-id}])
    (let [routes  (rf/registrations {:source :store :kind :route})
          current #(get-in (rf.frame/frame-runtime-db-value :rf/default)
                           [:rf.runtime/routing :current])
          marker  (fn [data id]
                    (some #(when (= id (-> % :row :route-id)) (:marker %))
                          (:topology data)))]
      (testing "entry denied: the TO is the route whose :can-enter refused"
        (let [data (h/project-topology-data routes (current)
                                            (refused-bundle! members-route-id))]
          (is (= :entry-denied (get-in data [:activity :phase]))
              "PRECONDITION: the navigation was denied")
          (is (= members-route-id (:to-id data)))
          (is (nil? (:from-id data)) "the denial trace does not name the start")))
      (rf/dispatch-sync [::dirty])
      (testing "blocked: FROM is the route whose :can-leave refused, TO the
                route the requested URL resolves to"
        (let [data (h/project-topology-data routes (current)
                                            (refused-bundle! open-route-id))]
          (is (= :navigation-blocked (get-in data [:activity :phase]))
              "PRECONDITION: the navigation was blocked")
          (is (= guarded-route-id (:from-id data)))
          (is (= open-route-id (:to-id data)))
          (testing "and marks no table row: the app never moved"
            (is (nil? (marker data open-route-id)))
            (is (= :here (marker data guarded-route-id)))))))))

;; ---- project-topology-data composite ----------------------------------

(defn- markers
  "The `[route-id marker]` pairs of the rows that carry a marker."
  [data]
  (filterv second (topology-rows (:topology data) :marker)))

(deftest project-topology-data-silent-test
  (testing "no routes registered → silent, empty topology"
    (is (= [true []]
           ((juxt :silent? :topology) (h/project-topology-data {} {:route-id :route/cart} nil))))))

(deftest project-topology-data-topology-shape-test
  (testing "no focused cascade: only the current route is marked, :here"
    (is (= [[:route/cart :here]]
           (markers (h/project-topology-data parented-routes {:route-id :route/cart} nil))))))

(deftest project-topology-data-overlay-test
  (testing "a focused cascade that navigated → :to overlay, :on-match phase, the slice's params"
    (let [data (h/project-topology-data parented-routes
                                        {:route-id  :route/confirm
                                         :params    {:x 1}
                                         :nav-token "nav-9"}
                                        (nav-cascade 42 [:rf.route/navigate {:to :route/confirm}]
                                                     :route/confirm nil "nav-9"))]
      (is (= [true :route/confirm :on-match {:x 1}]
             ((juxt :navigated? :to-id (comp :phase :activity) (comp :match :activity)) data)))
      (is (= [[:route/confirm :to]] (markers data)))))
  (testing "FROM is read off the cascade, independent of where the live slice has moved"
    (let [data (h/project-topology-data parented-routes {:route-id :route/admin}
                                        (nav-cascade 7 [:rf.route/navigate {:to :route/confirm}]
                                                     :route/confirm :route/cart "nav-7"))]
      (is (= :route/cart (:from-id data)))
      (is (= [[:route/cart :from] [:route/confirm :to]] (markers data))
          "the live route carries no marker for this historical epoch"))))

(deftest project-topology-data-no-activity-test
  (testing "a focused cascade with no routing trace → no activity; HERE still paints"
    (let [data (h/project-topology-data parented-routes
                                        {:route-id :route/cart}
                                        (cascade 9 [:counter/inc]))]
      (is (nil? (:activity data)))
      (is (= [[:route/cart :here]] (markers data))))))
