(ns re-frame.derivation-algebra-conformance-cljs-test
  "Cross-family conformance for the derivation/process algebra in
  `spec/Derivations.md`.

  The family suites validate their own projections, and the portable corpus
  fixtures pin each family's lowering and classification. This suite composes
  subscriptions, flows, resources, route facts and machines into one static or
  live graph and checks the laws that cross those ownership boundaries:

  - static and realized `:input`, `:param` and `:selector` edges, and the
    canonical edge order;
  - lifecycle release, observed as nodes leaving the live graph;
  - on-demand reads that do not write durable state;
  - graph egress redaction without loss of identity or connectivity.

  The suite sits outside any one family because it consumes bundle-isolated
  contributors from core, flows, resources, routing, and machines. It does not
  depend on `tools/`. The same `.cljc` tests run in the shared CLJS node gate
  and through this artefact's JVM `:test` alias."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.frame :as rf.frame]
            [re-frame.derivation.graph :as rf.derivation.graph]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            ;; The graph-wide egress projection lives in core so conformance
            ;; can test the same implementation used by tool consumers without
            ;; introducing a dependency on `tools/`.
            [re-frame.derivation.egress :as rf.derivation.egress]
            ;; The `:sub/run` trace projector, which graph egress of a live
            ;; sub's value must match.
            [re-frame.classification :as rf.classification]
            [re-frame.image :as rf.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.identity :as rf.identity]
            ;; Only for the unkeyed SHA-256 the egress handle must NOT equal.
            [re-frame.schemas.digest]
            [re-frame.privacy :as rf.privacy]
            [re-frame.elision :as rf.elision]
            ;; Load-bearing requires: each facade installs its framework
            ;; registrations before the graph contributors are exercised.
            [re-frame.routing]
            [re-frame.routing.tooling :as rf.routing.tooling]
            [re-frame.resources]
            [re-frame.resources.tooling :as rf.resources.tooling]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]
            [re-frame.machines.tooling :as rf.machines.tooling]
            ;; The CLJS lifecycle arm drives the cache through the internal
            ;; subscribe/unsubscribe operations.
            #?(:cljs [re-frame.subs :as rf.subs])
            [re-frame.subs.tooling :as rf.subs.tooling]
            [re-frame.flows.tooling :as rf.flows.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; All CLJS tests share one process-global runtime. This fixture restores the
;; registrar and adapter after each test so this suite cannot leak its
;; plain-atom substrate into later React-backed suites. `:init-fn` reloads the
;; optional family registrations after the baseline has been restored.
(defn- refresh-families! []
  (rf.flows/reset-flows!)
  (rf.flows/reset-last-inputs!)
  (rf.schemas/clear-schemas-by-frame!)
  #?(:clj (do (require 're-frame.routing :reload)
              (require 're-frame.resources :reload)
              (require 're-frame.machines :reload)))
  (rf.frame/ensure-default-frame!))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn refresh-families!}))

;; An explicit contributor map on both runtimes, so this suite does not depend
;; on JVM-only contributor discovery.
(def all-contributors
  {:subs      {:static-fn  rf.subs.tooling/sub-algebra-view
               :live-fn    rf.subs.tooling/sub-cache-algebra-view
               :live-shape :map}
   :flows     {:static-fn  rf.flows.tooling/flow-algebra-view
               :live-fn    rf.flows.tooling/flow-algebra-view
               :live-shape :map}
   :resources {:static-fn  rf.resources.tooling/resource-algebra-view
               :live-fn    rf.resources.tooling/resource-cache-algebra-view
               :live-shape :map}
   :routes    {:static-fn  rf.routing.tooling/route-algebra-view
               :live-fn    rf.routing.tooling/route-slice-algebra-view
               :live-shape :node}
   :machines  {:static-fn         rf.machines.tooling/machine-algebra-view
               :live-fn           rf.machines.tooling/machine-instance-algebra-view
               :live-shape        :map
               :selector-targets  rf.machines.tooling/machine-selector-targets}})

(def cart-items
  [{:sku "a" :qty 2} {:sku "b" :qty 3} {:sku "c" :qty 5}])

(defn- register-one-of-each!
  "Register a declared-input sub chain, a machine and the sub selecting it, and
  a route owning a resource. Per test, because the fixture restores the
  load-time baseline."
  []
  (rf/reg-event ::seed-cart
                (fn [{:keys [db]} [_ items]]
                  {:db (assoc-in db [:cart :items] items)}))
  (rf/reg-sub :cart/items (fn [db _] (get-in db [:cart :items])))
  (rf/reg-sub :cart/total
              {:inputs [[:cart/items]]}
              (fn [[items] _] (reduce + 0 (map :qty items))))
  (rf/reg-sub :upload/progress
              {:inputs [[:rf/machine :upload/main]]}
              (fn [[snapshot] _] (get-in snapshot [:data :progress] 0)))
  (rf/reg-resource :article/by-slug
                   {:scope         :rf.scope/global
                    :params-schema [:map [:slug :string]]}
                   (fn [{:keys [slug]} _ctx]
                     {:request {:method :get
                                :url    (str "/api/articles/" slug)}}))
  (rf/reg-route :route/article
                {:resources [{:resource :article/by-slug :blocking? true}]} "/articles/:slug")
  (rf/reg-machine :upload/main
                  {:initial :idle
                   :data    {:progress 0}
                   :states  {:idle      {:on {:upload/start {:target :uploading}}}
                             :uploading {:after {1000 {:target :idle}}
                                         :on    {:upload/done {:target :idle}}}}}))

;; ===========================================================================
;; (c) GRAPH EDGES — :input / :param / :selector in static + live graphs.
;; ===========================================================================

(deftest c-static-edges-span-input-param-selector
  (register-one-of-each!)
  (let [edges (:edges (rf.derivation.graph/derivation-graph all-contributors))]
    (is (some #{{:from [:sub :cart/items] :to [:sub :cart/total] :role :input}} edges)
        "the static declared-input edge")
    (is (some #(and (= [:rf/route :route/article] (:from %))
                    (= [:resource :article/by-slug] (:to %))
                    (= :param (:role %)))
              edges)
        "the route → resource activation edge")
    (is (some #{{:from [:machine :upload/main] :to [:sub :upload/progress] :role :selector}} edges)
        "the machine process → selector subscription edge")))

(deftest c-named-resolver-scope-input-appears-statically-through-the-composer
  ;; A throwing resolver makes accidental execution fail immediately.
  (rf/reg-resource-scope :conf/tenant
                         {:inputs {:tenant-id [:db [:session :tenant-id]]}}
                         (fn [_inputs _ctx]
                           (throw (ex-info "a named-resolver fn must not run during static graph inspection" {}))))
  (rf/reg-resource :tenant/feed
                   {:scope         {:from-db :conf/tenant}
                    :params-schema [:map [:page :int]]}
                   (fn [{:keys [page]} _ctx]
                     {:request {:method :get :url "/api/feed" :params {:page page}}}))
  (let [res (get-in (rf.derivation.graph/derivation-graph all-contributors) [:nodes [:resource :tenant/feed]])]
    (is (= [[[:param :rf.params] [:scope {:from-db :conf/tenant}]] :conf/tenant [[:db [:session :tenant-id]]]]
           [(:inputs res) (get-in res [:scope-resolver :id]) (get-in res [:scope-resolver :inputs])])
        "the composed node keeps the {:from-db <id>} reference, the resolver id and its static inputs")))

(deftest c-live-graph-has-the-mode-frame-shape-and-realizes-the-route-slice
  ;; The live graph reports realized facts the static graph cannot know: the
  ;; matched route, its params, and the route owner minted by the navigation.
  (register-one-of-each!)
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "welcome"}}])
  (let [g     (rf.derivation.graph/live-derivation-graph :rf/default all-contributors)
        slice (get (:nodes g) :rf/route)]
    (is (= [:live :rf/default true true] ((juxt :mode :frame (comp map? :nodes) (comp vector? :edges)) g)))
    (is (= {:route-id :route/article :params {:slug "welcome"}
            :storage :runtime-db :evaluation :on-route :lifecycle :frame
            :owner [:route :route/article (:nav-token slice)]}
           (select-keys slice [:route-id :params :storage :evaluation :lifecycle :owner])))))

;; ---------------------------------------------------------------------------
;; (c+) Non-route realized composition. Synthetic contributors isolate the
;; composer's live node wrapping and edge derivation from the family runtimes.

(def ^:private live-composition-nav-token 7)
(def ^:private live-composition-scoped-key
  [[:rf.scope/global] :article/by-slug {:slug "a1"}])
(def ^:private live-composition-work-id
  [:rf.work/resource live-composition-scoped-key 3])

(def ^:private live-composition-resource-node
  {:id          live-composition-scoped-key
   :kind        :process :refinement :resource-process
   :inputs      [[:scope [:rf.scope/global]] [:param {:slug "a1"}]]
   :output      [:runtime [:rf.runtime/resources :entries live-composition-scoped-key]]
   :storage     :runtime-db
   :authority   {:kind :remote :system :server}
   :evaluation  #{:on-route}
   :lifecycle   {:kind :scoped-resource-key
                 :owners #{[:route :route/article live-composition-nav-token]}}
   :status      :loading
   :work-ledger {:work/id live-composition-work-id
                 :record  {:work/id live-composition-work-id :status :running
                           :resource/key live-composition-scoped-key}}})

(defn- live-composition-contributors
  "A realized `[:article/page \"a1\"]` reading `[:article/by-slug \"a1\"]`, and a
  route-owned resource fetch."
  []
  {:subs
   {:live-shape :map
    :static-fn  (constantly {})
    :live-fn    (constantly
                  {[:article/page "a1"]
                   {:id      [:article/page "a1"]
                    :kind    :derivation
                    :inputs  [[:sub [:article/by-slug "a1"]]]
                    :output  [:fact [:article/page "a1"]]
                    :storage :ephemeral :evaluation :on-demand
                    :lifecycle :subscription-cache-entry}
                   [:article/by-slug "a1"]
                   {:id      [:article/by-slug "a1"]
                    :kind    :derivation
                    :inputs  [[:db [:articles "a1"]]]
                    :output  [:fact [:article/by-slug "a1"]]
                    :storage :ephemeral :evaluation :on-demand
                    :lifecycle :subscription-cache-entry}})}
   :resources
   {:live-shape :map
    :static-fn  (constantly {})
    :live-fn    (constantly {live-composition-scoped-key live-composition-resource-node})}
   :routes
   {:live-shape :node
    :static-fn  (constantly {})
    :live-fn    (constantly
                  {:id :rf/route :kind :process :refinement :route-fact
                   :route-id :route/article :params {:slug "a1"}
                   :nav-token live-composition-nav-token
                   :owner [:route :route/article live-composition-nav-token]
                   :output [:runtime [:rf.runtime/routing :current]]
                   :storage :runtime-db :evaluation :on-route :lifecycle :frame})}})

(deftest cplus-live-graph-realizes-non-route-nodes-and-edges
  (let [{:keys [nodes edges]} (rf.derivation.graph/live-derivation-graph
                                :rf/default (live-composition-contributors))]
    (is (= #{[:sub [:article/page "a1"]] [:sub [:article/by-slug "a1"]]
             [:resource live-composition-scoped-key] :rf/route}
           (set (keys nodes)))
        "realized subs are keyed by their concrete query vector, the resource by its scoped key")
    (is (= (assoc live-composition-resource-node :rf/family :resources)
           (get nodes [:resource live-composition-scoped-key]))
        "the resource node, live owners and work-ledger included, is composed verbatim")
    (is (= #{{:from [:sub [:article/by-slug "a1"]] :to [:sub [:article/page "a1"]] :role :input}
             {:from  :rf/route
              :to    [:resource live-composition-scoped-key]
              :role  :param
              :owner [:route :route/article live-composition-nav-token]}}
           (set edges))
        "the realized :input edge and the route-owned :param edge")))

;; ---------------------------------------------------------------------------
;; (c++) Deterministic canonical edge order. Callers serialize, diff and hash
;; `:edges`, so logically identical graphs assembled in different insertion
;; orders must produce the same ordered vector. The composer sorts the
;; de-duplicated edges by `re-frame.identity/canonical-bytes` of each complete
;; edge map, which is platform-stable and independent of nested-map spelling.

(defn- permutations-of
  "All orderings of `coll` (small n)."
  [coll]
  (if (<= (count coll) 1)
    (list (vec coll))
    (for [i    (range (count coll))
          tail (permutations-of (concat (take i coll) (drop (inc i) coll)))]
      (into [(nth coll i)] tail))))

(defn- permutation-sub-node [id inputs]
  {:id id :kind :derivation :inputs inputs
   :output [:fact id] :storage :ephemeral :evaluation :on-demand})

(defn- permutation-route
  "A route owning one resource whose key is the same nested map spelled
  `:slug`-first or `:locale`-first."
  [route-id slug slug-first?]
  {:id :rf/route :kind :process :refinement :route-fact
   :route-id route-id :storage :runtime-db :evaluation :on-route :lifecycle :frame
   :resource-edges [{:to     [:resource (if slug-first? {:slug slug :locale :en} {:locale :en :slug slug})]
                     :role   :param
                     :target :parametric}]})

(defn- permutation-graph
  "The static graph of the same nodes and edges, inserted in `sub-order` /
  `route-order`. `:b` declares its `[:sub [:a]]` input twice, so the duplicate
  must collapse before canonicalization."
  [sub-order route-order slug-first?]
  (let [subs   {:a (permutation-sub-node :a [])
                :b (permutation-sub-node :b [[:sub [:a]] [:sub [:a]]])
                :c (permutation-sub-node :c [[:sub [:a]]])}
        routes {:r1 (permutation-route :r1 "s1" slug-first?)
                :r2 (permutation-route :r2 "s2" slug-first?)}
        in-order (fn [m order] (reduce (fn [acc k] (assoc acc k (get m k))) (array-map) order))]
    (rf.derivation.graph/derivation-graph
      {:subs   {:live-shape :map :static-fn (constantly (in-order subs sub-order))}
       :routes {:live-shape :node :static-fn (constantly (in-order routes route-order))}})))

(deftest cplusplus-edge-order-is-canonical-across-registration-permutations
  (let [baseline (permutation-graph [:a :b :c] [:r1 :r2] true)]
    (is (= [{:from [:rf/route :r1] :to [:resource {:slug "s1" :locale :en}]
             :role :param :target :parametric}
            {:from [:rf/route :r2] :to [:resource {:slug "s2" :locale :en}]
             :role :param :target :parametric}
            {:from [:sub :a] :to [:sub :b] :role :input}
            {:from [:sub :a] :to [:sub :c] :role :input}]
           (:edges baseline))
        "the four de-duplicated edges in canonical-bytes order, on CLJ and CLJS alike")
    (is (= #{baseline}
           (set (for [sub-order   (permutations-of [:a :b :c])
                      route-order (permutations-of [:r1 :r2])
                      slug-first? [true false]]
                  (permutation-graph sub-order route-order slug-first?))))
        "every insertion order and both nested-map spellings give the identical graph")))

;; A live edge endpoint carries a subscription's whole query vector, and a
;; query argument may be a finite float or a fn, outside the CEDN-1 domain.
;; Ordering by `canonical-bytes` alone would throw and lose the whole live graph.

(def ^:private out-of-domain-arg-fn
  "One fixed fn object, so every assembly sees the same value."
  (fn [x] x))

(defn- live-query-arg-contributors
  "Live subs `[:b 0.5]`, `[:c]` and `[:d <fn>]` reading `[:a]`, inserted in `order`."
  [order]
  (let [nodes {:a [[:a] []]
               :b [[:b 0.5] [[:sub [:a]]]]
               :c [[:c] [[:sub [:a]]]]
               :d [[:d out-of-domain-arg-fn] [[:sub [:a]]]]}]
    {:subs {:live-shape :map
            :static-fn  (constantly {})
            :live-fn    (constantly
                          (reduce (fn [m k]
                                    (let [[q inputs] (get nodes k)]
                                      (assoc m q (permutation-sub-node q inputs))))
                                  (array-map) order))}}))

(deftest cplusplus-live-edge-order-tolerates-out-of-domain-query-args
  (is (= #{[{:from [:sub [:a]] :to [:sub [:c]] :role :input}
            {:from [:sub [:a]] :to [:sub [:b 0.5]] :role :input}
            {:from [:sub [:a]] :to [:sub [:d out-of-domain-arg-fn]] :role :input}]}
         (set (for [order (permutations-of [:a :b :c :d])]
                (:edges (rf.derivation.graph/live-derivation-graph
                          :rf/default (live-query-arg-contributors order))))))
      "CEDN-1 edges keep their canonical position and out-of-domain edges sort after them, under every insertion order"))

;; ===========================================================================
;; (e) LIFECYCLE — drive each release boundary and observe the node or owner
;;     leave the live graph. Subscription cache entries are CLJS-only because
;;     JVM cache reactions are not dereferenceable.
;; ===========================================================================

(deftest e-destroying-a-frame-releases-its-frame-owned-graph-nodes
  (register-one-of-each!)
  (rf/make-frame {:id :checkout/frame})
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "welcome"}}]
                    {:frame :checkout/frame})
  (rf/dispatch-sync [:upload/main [:upload/start]] {:frame :checkout/frame})
  (let [live #(rf.derivation.graph/live-derivation-graph :checkout/frame all-contributors)]
    (is (every? (:nodes (live)) [:rf/route [:machine :upload/main]])
        "the route slice and machine snapshot are live before teardown")
    (rf.frame/destroy-frame! :checkout/frame)
    (is (= [:live {} []] ((juxt :mode :nodes :edges) (live)))
        "no frame-owned node or edge survives the teardown")))

(deftest e-route-owned-resource-release-propagates-through-the-assembled-live-graph
  ;; Route A owns a resource and route B owns none. The normal navigate hook
  ;; releases owner A on supersession; the resource tooling projection and the
  ;; composer's realized :param edge must both drop it. This artefact carries
  ;; no HTTP transport, so the route-owned cache row is built with the canonical
  ;; resources constructors; the owner and its release are the real ones.
  (rf.fx/reg-fx :rf.http/managed       (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx _args] nil))
  (rf/reg-resource :article/by-slug
                   {:scope         :rf.scope/global
                    :params-schema [:map [:slug :string]]}
                   (fn [{:keys [slug]} _ctx]
                     {:request {:method :get :url (str "/api/articles/" slug)}}))
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource :article/by-slug
                              :params   (fn [route] {:slug (get-in route [:params :slug])})}]}
                "/articles/:slug")
  (rf/reg-route :route/about {} "/about")
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "welcome"}}])
  (let [owner-a    (:owner (rf.routing.tooling/route-slice-algebra-view :rf/default))
        scoped-key (rf.resources.state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "welcome"})
        work-id    (rf.resources.work-ledger/resource-work-id scoped-key 1)
        entry      (-> (rf.resources.state/empty-entry :article/by-slug scoped-key)
                       (rf.resources.state/attach-owner owner-a)
                       (assoc :status :fetching :current-work work-id))
        live       #(rf.derivation.graph/live-derivation-graph :rf/default all-contributors)
        resources  (fn [g] (filterv #(= :resources (:rf/family %)) (vals (:nodes g))))
        params     (fn [g] (filterv #(= :param (:role %)) (:edges g)))]
    (rf.frame/swap-runtime-db!
      :rf/default
      (fn [rdb]
        (-> (or rdb {})
            (assoc-in (rf.resources.state/entry-path scoped-key) entry)
            (assoc-in (conj (rf.resources.state/owner-index-path) owner-a)
                      #{(rf.resources.state/key-id scoped-key)})
            (rf.resources.work-ledger/put-record
              work-id
              (rf.resources.work-ledger/work-record {:work-id      work-id
                                                     :frame-id     :rf/default
                                                     :resource/key scoped-key
                                                     :generation   1
                                                     :transport    :rf.http/managed
                                                     :owner        owner-a
                                                     :cause        :test/materialize})))))
    (let [g-a       (live)
          [res-key] (keep (fn [[k n]] (when (= :resources (:rf/family n)) k)) (:nodes g-a))]
      (is (= [[#{owner-a}] [[:rf/route res-key owner-a]]]
             [(mapv #(get-in % [:lifecycle :owners]) (resources g-a))
              (mapv (juxt :from :to :owner) (params g-a))])
          "before release, owner A owns the resource node and its one realized :param edge"))
    (rf/dispatch-sync [:rf.route/navigate {:to :route/about}])
    (let [g-b   (live)
          slice (get (:nodes g-b) :rf/route)]
      (is (= [:route :route/about (:nav-token slice)] (:owner slice))
          "route B's fresh owner takes the slice")
      (is (= [[#{}] []]
             [(mapv #(get-in % [:lifecycle :owners]) (resources g-b)) (params g-b)])
          "the owner-free resource node stays composed, and no :param edge survives"))))

(deftest e-machine-destroy-releases-the-machine-owned-snapshot-node
  (rf/reg-machine :job/runner
                  {:initial :running
                   :data    {}
                   :states  {:running {:on {:job/finish :done}}
                             :done    {:final? true}}})
  ;; An unhandled event leaves the machine :running and installs its snapshot.
  (rf/dispatch-sync [:job/runner [:job/finish-noop]])
  (let [node #(get-in (rf.derivation.graph/live-derivation-graph :rf/default all-contributors)
                      [:nodes [:machine :job/runner]])]
    (is (some? (node)) "the instance's snapshot node is live before the final event")
    (rf/dispatch-sync [:job/runner [:job/finish]])
    (is (nil? (node)) "final-state auto-destroy releases the node")))

#?(:cljs
   (deftest e-subscription-disposal-releases-its-cache-entry-node
     ;; One reader materializes the cache entry; dropping the sole reference
     ;; disposes it synchronously.
     (register-one-of-each!)
     (rf/reg-sub :cart/item-qty
                 {:inputs (fn [[_ _sku]] [[:cart/items]])}
                 (fn [[items] [_ sku]] (some #(when (= sku (:sku %)) (:qty %)) items)))
     (rf/dispatch-sync [::seed-cart cart-items])
     (let [q    [:cart/item-qty "b"]
           r    (rf.subs/subscribe q {:frame :rf/default})
           node #(get-in (rf.derivation.graph/live-derivation-graph :rf/default all-contributors)
                         [:nodes [:sub q]])]
       (is (= [3 :subscription-cache-entry 1] [@r (:lifecycle (node)) (:ref-count (node))])
           "one live reader keeps the cache-entry node alive")
       (rf.subs/unsubscribe r)
       (is (nil? (node)) "the node leaves the live graph at ref-count zero"))))

;; ===========================================================================
;; (f) EVALUATION POLICY — reading on-demand facts must not write app-db or
;;     runtime-db. The work ledger lives in runtime-db, so equal durable
;;     partitions also prove no resource work started.
;; ===========================================================================

(deftest f-reading-an-on-demand-node-causes-no-durable-write
  (register-one-of-each!)
  (let [durable  #(mapv (fn [read] (read :rf/default))
                        [rf.frame/frame-app-db-value rf.frame/frame-runtime-db-value])
        before   (durable)
        _        @(rf/subscribe [:cart/items])
        selected @(rf/subscribe [:rf/resource {:resource :article/by-slug :params {:slug "welcome"}}])]
    (is (= :idle (:status selected)) "the selector read ran and saw the idle empty state")
    (is (= before (durable)) "neither app-db nor runtime-db changed")))

;; ===========================================================================
;; (g) TOOL REDACTION — off-box graph egress.
;;
;; `rf.derivation.egress/project-graph` walks value summaries under the named
;; frame's elision policy and remaps every identity-bearing resource position
;; consistently so edges still connect. A live resource node carries scope and
;; params in its identity: the scoped key `[cache-scope resource-id
;; canonical-params]` is the node key, the `:id`, the `:output` path tail, the
;; realized `:inputs`, the work-ledger `:resource/key` and the edge endpoints.
;; ===========================================================================

(def ^:private egress-frame :app/egress-secure)

(def ^:private secret-token "tenant-jwt-9f3a-SECRET")
(def ^:private secret-scope  [:rf.scope/tenant secret-token])
(def ^:private secret-params {:slug "welcome" :auth-token secret-token})
(def ^:private egress-scoped-key [secret-scope :article/by-slug secret-params])
(def ^:private egress-nav-token 23)
(def ^:private egress-generation 4)
(def ^:private egress-work-id [:rf.work/resource egress-scoped-key egress-generation])

(defn- egress-live-contributors
  "A route-owned resource fetch under a sensitive (tenant-scoped) activation,
  in `resource-cache-algebra-view`'s live node shape."
  []
  {:resources
   {:live-shape :map
    :static-fn  (constantly {})
    :live-fn    (constantly
                  {egress-scoped-key
                   {:id          egress-scoped-key
                    :kind        :process :refinement :resource-process
                    :rf/family   :resources
                    :inputs      [[:scope secret-scope] [:param secret-params]]
                    :output      [:runtime [:rf.runtime/resources :entries egress-scoped-key]]
                    :storage     :runtime-db
                    :authority   {:kind :remote :system :server}
                    :evaluation  #{:on-route}
                    :lifecycle   {:kind   :scoped-resource-key
                                  :owners #{[:route :route/article egress-nav-token]}}
                    :status      :loading
                    :work-ledger    {:work/id egress-work-id
                                     :record  {:work/id      egress-work-id
                                               :status       :pending
                                               :resource/key egress-scoped-key}}
                    :host-transient [[:rf.http/in-flight egress-work-id]]}})}
   :routes
   {:live-shape :node
    :static-fn  (constantly {})
    :live-fn    (constantly
                  {:id :rf/route :kind :process :refinement :route-fact
                   :rf/family :routes
                   :route-id :route/article :params {:slug "welcome"}
                   :nav-token egress-nav-token
                   :owner [:route :route/article egress-nav-token]
                   :output [:runtime [:rf.runtime/routing :current]]
                   :storage :runtime-db :evaluation :on-route :lifecycle :frame})}})

(defn- contains-secret?
  "True when the secret appears anywhere in a nested value, map keys included:
  as a whole string, embedded in a larger string (a CEDN-1 token carries the
  raw value inside it), or in the printed form of any other leaf."
  [v]
  (boolean
    (cond
      (string? v) (str/includes? v secret-token)
      (map? v)    (some contains-secret? (concat (keys v) (vals v)))
      (coll? v)   (some contains-secret? v)
      :else       (str/includes? (pr-str v) secret-token))))

(defn- resource-entry
  "The `[node-key node]` of the one resource node in `graph`."
  [graph]
  (first (filter (fn [[k _]] (and (vector? k) (= :resource (first k)))) (:nodes graph))))

(deftest g-live-resource-identity-redacted-at-graph-egress
  (rf/make-frame {:id egress-frame :doc "off-box egress conformance frame"})
  (let [raw                (rf.derivation.graph/live-derivation-graph egress-frame (egress-live-contributors))
        redacted           (rf.derivation.egress/project-graph raw egress-frame)
        [node-key node]    (resource-entry redacted)
        projected          (second node-key)
        wid                (get-in node [:work-ledger :work/id])]
    (is (contains-secret? raw) "the raw composed graph carries the secret")
    (is (not (contains-secret? redacted)) "no raw secret survives anywhere in the off-box graph")
    (is (= [3 :article/by-slug] [(count projected) (nth projected 1)])
        "the projected scoped key keeps its shape and the visible resource id")
    (is (= [projected projected projected node-key]
           [(:id node)
            (last (second (:output node)))
            (get-in node [:work-ledger :record :resource/key])
            (:to (first (filter #(= :param (:role %)) (:edges redacted))))])
        "node key, :id, :output, ledger and edge share one projected identity")
    (is (= [:process :resource-process [:scope :param] :runtime [:rf.runtime/resources :entries] :pending]
           [(:kind node) (:refinement node) (mapv first (:inputs node)) (first (:output node))
            (vec (take 2 (second (:output node)))) (get-in node [:work-ledger :record :status])])
        "classification and the identity-bearing structure survive")
    (is (= [[:rf.work/resource projected egress-generation] wid wid]
           [wid (get-in node [:work-ledger :record :work/id]) (second (first (:host-transient node)))])
        "every work-id copy and the host-transient address name one projected work-id")))

(defn- egress-sensitive-value-contributors
  "`egress-live-contributors` plus a live sub whose value sits at the
  frame-sensitive `[:cart :items]` path, so one graph exercises both leak
  channels: the value-path walk and the resource-identity projection."
  []
  (assoc (egress-live-contributors)
         :subs
         {:live-shape :map
          :static-fn  (constantly {})
          :live-fn    (constantly
                        {[:cart/items]
                         {:id      [:cart/items] :kind :derivation :rf/family :subs
                          :inputs  [] :output [:fact [:cart/items]]
                          :storage :ephemeral :evaluation :on-demand
                          :lifecycle :subscription-cache-entry
                          :value   {:cart {:items secret-token}}}})}))

(defn- classify-egress-frame-sensitive! []
  (rf.frame/swap-runtime-db! egress-frame
    (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[:cart :items]]}))))

(defn- sub-value [graph]
  (get-in graph [:nodes [:sub [:cart/items]] :value]))

(deftest g-graph-egress-for-unknown-frame-fails-closed
  ;; Without a reachable policy the value fails closed; resource identity
  ;; projection is independent of that policy.
  (rf/make-frame {:id egress-frame})
  (classify-egress-frame-sensitive!)
  (let [raw     (rf.derivation.graph/live-derivation-graph egress-frame (egress-sensitive-value-contributors))
        unknown (rf.derivation.egress/project-graph raw :app/does-not-exist)]
    (is (= [rf.privacy/redacted-sentinel {:cart {:items rf.privacy/redacted-sentinel}}]
           [(sub-value unknown) (sub-value (rf.derivation.egress/project-graph raw egress-frame))])
        "an unknown frame redacts the whole value; the known frame redacts its classified leaf")
    (is (not (contains-secret? unknown)))))

(deftest g-graph-egress-nil-frame-is-unregistrable-and-fails-closed
  ;; The fail-closed stamp for an absent governing frame must be a value no app
  ;; can register a frame under, or a live frame registered at it would ship
  ;; value fields raw. `nil` is that value structurally. The test registers a
  ;; frame at nil where the runtime allows it, and binds an ambient frame, so
  ;; borrowing either policy would show.
  (rf/make-frame {:id egress-frame})
  (classify-egress-frame-sensitive!)
  (let [registered? (try (rf/make-frame {:id nil}) true
                         (catch #?(:clj Throwable :cljs :default) _ false))]
    (try
      (let [raw      (rf.derivation.graph/live-derivation-graph egress-frame
                                                                (egress-sensitive-value-contributors))
            redacted (rf/with-frame :rf/default
                       (rf.derivation.egress/project-graph raw nil))]
        (is (= rf.privacy/redacted-sentinel (sub-value redacted)))
        (is (not (contains-secret? redacted))))
      (finally
        (when registered?
          (try (rf/destroy-frame! nil)
               (catch #?(:clj Throwable :cljs :default) _ nil)))))))

(deftest g-graph-egress-is-idempotent
  ;; Forwarders may project a graph more than once. Whole-graph equality
  ;; catches a fresh handle in any identity-bearing position.
  (rf/make-frame {:id egress-frame})
  (let [raw  (rf.derivation.graph/live-derivation-graph egress-frame (egress-live-contributors))
        once (rf.derivation.egress/project-graph raw egress-frame)]
    (is (= once (rf.derivation.egress/project-graph once egress-frame)))))

;; A live sub node's `:value` is the sub's own output, so egress redacts it by
;; the sub's REGISTRATION classification, as the `:sub/run` trace does for the
;; same value, before the frame-policy walk. The registration resolves in the
;; inspected frame's generation.

(def ^:private partner-subs
  "Query vector → raw live value for each sub `register-partner-subs!` declares."
  {[:partner/api-token] secret-token
   [:partner/session]   {:token secret-token :user "ada"}
   [:partner/archive]   {:blob "an archived payload"}
   [:partner/name]      "Acme"})

(defn- api-token-sub [db _] (get-in db [:tenant :partner-api-key]))

(defn- register-partner-subs! []
  (rf/reg-sub :partner/api-token {:sensitive [[]]} api-token-sub)
  (rf/reg-sub :partner/session {:sensitive [[:token]]} (fn [db _] (:session db)))
  (rf/reg-sub :partner/archive {:large [[:blob]]} (fn [db _] (:archive db)))
  (rf/reg-sub :partner/name (fn [db _] (:partner-name db))))

(defn- partner-graph
  "The live graph of `subs` (query vector → value) in `sub-cache-algebra-view`'s
  node shape. `:partner/name` reads the token sub, so the graph has an edge."
  [frame-id subs]
  (rf.derivation.graph/live-derivation-graph
    frame-id
    {:subs {:live-shape :map
            :static-fn  (constantly {})
            :live-fn    (constantly
                          (into {}
                                (map (fn [[q v]]
                                       [q {:id          q :kind :derivation :rf/family :subs
                                           :source-form {:kind :reg-sub :id (first q)}
                                           :inputs      (if (= [:partner/name] q) [[:sub [:partner/api-token]]] [])
                                           :output      [:fact q]
                                           :storage     :ephemeral :evaluation :on-demand
                                           :lifecycle   :subscription-cache-entry
                                           :value       v}]))
                                subs))}}))

(defn- live-value [graph q]
  (get-in graph [:nodes [:sub q] :value]))

(defn- without-values [graph]
  (update graph :nodes #(into {} (map (fn [[k n]] [k (dissoc n :value)])) %)))

(defn- trace-projected-value
  "The `:rf.sub/value` a `:sub/run` trace of `q` returning `v` carries after
  egress projection under `frame-id`."
  [frame-id q v]
  (get-in (rf.classification/project-trace-event
            {:operation :rf.sub/run
             :tags      {:frame frame-id :rf.sub/id (first q) :rf.sub/query-v q :rf.sub/value v}})
          [:tags :rf.sub/value]))

(deftest g-graph-egress-redacts-a-live-sub-by-its-own-registration
  (register-partner-subs!)
  (rf/make-frame {:id egress-frame})
  (let [raw      (partner-graph egress-frame partner-subs)
        redacted (rf.derivation.egress/project-graph raw egress-frame)]
    (is (contains-secret? raw) "the raw composed graph carries the token")
    (is (= rf.privacy/redacted-sentinel (live-value redacted [:partner/api-token]))
        "a whole-output :sensitive [[]] sub's value is redacted whole")
    (is (= {:token rf.privacy/redacted-sentinel :user "ada"} (live-value redacted [:partner/session]))
        "a nested :sensitive [[:token]] sub redacts only :token")
    (is (= "Acme" (live-value redacted [:partner/name])) "an unclassified sibling rides raw")
    (is (not (contains-secret? redacted)))
    (is (seq (:edges raw)))
    (is (= (without-values raw) (without-values redacted))
        "only :value changes: query-vector identity, structure and edges are untouched")
    (doseq [[q v] partner-subs]
      (is (= (trace-projected-value egress-frame q v) (live-value redacted q))
          (str "graph egress of " q " equals its :sub/run trace projection")))))

(deftest g-graph-egress-of-classified-subs-is-idempotent
  (register-partner-subs!)
  (rf/make-frame {:id egress-frame})
  (let [once (rf.derivation.egress/project-graph (partner-graph egress-frame partner-subs) egress-frame)]
    (is (= once (rf.derivation.egress/project-graph once egress-frame)))))

(deftest g-graph-egress-resolves-the-sub-in-the-inspected-frames-generation
  ;; The token sub is declared only in the inspected frame's image, so the
  ;; global registrar knows nothing of its `:sensitive`.
  (rf.live-frame/make-frame
    {:id     :app/partner-image
     :images [(rf.image/image {:id            :app/partner
                               :registrations {:reg-sub [[:partner/api-token {:sensitive [[]]}
                                                          api-token-sub]]}})]}
    [])
  (let [graph (partner-graph :app/partner-image (select-keys partner-subs [[:partner/api-token]]))]
    (is (= rf.privacy/redacted-sentinel
           (live-value (rf.derivation.egress/project-graph graph :app/partner-image) [:partner/api-token])))))

;; A live sub's query vector `[:sub-id :kw {…}]` has the scoped-key shape; the
;; identity projection must touch resource nodes only.
(def ^:private scoped-key-shaped-query
  [:items/page :active {:limit 10}])

(deftest g-egress-leaves-a-scoped-key-shaped-sub-node-alone
  (rf/make-frame {:id egress-frame})
  (let [contributors (assoc (egress-live-contributors)
                            :subs
                            {:live-shape :map
                             :static-fn  (constantly {})
                             :live-fn    (constantly
                                           {scoped-key-shaped-query
                                            {:id      scoped-key-shaped-query :kind :derivation
                                             :inputs  [] :output [:fact scoped-key-shaped-query]
                                             :storage :ephemeral :evaluation :on-demand
                                             :lifecycle :subscription-cache-entry}})})
        redacted     (rf.derivation.egress/project-graph
                       (rf.derivation.graph/live-derivation-graph egress-frame contributors) egress-frame)]
    (is (= [scoped-key-shaped-query [:fact scoped-key-shaped-query]]
           ((juxt :id :output) (get-in redacted [:nodes [:sub scoped-key-shaped-query]]))))
    (is (not (contains-secret? redacted)) "the resource node in the same graph is still projected")))

;; The handle is the full 64-char hex HMAC-SHA-256 of the CEDN-1 token under a
;; private per-runtime key. A 32-bit hash would merge `{:q "Aa"}` and
;; `{:q "BB"}` (they share a String.hashCode) into one node, and would let a
;; low-entropy param be recovered by enumeration.

(defn- egress-search-results
  "The raw and egressed live graphs for one `:search/results` entry per params."
  [& params]
  (let [raw (rf.derivation.graph/live-derivation-graph
              egress-frame
              {:resources
               {:live-shape :map
                :static-fn  (constantly {})
                :live-fn    (constantly
                              (into {}
                                    (for [p params
                                          :let [k [:rf.scope/global :search/results p]]]
                                      [k {:id k :kind :process :refinement :resource-process
                                          :rf/family :resources :storage :runtime-db
                                          :output [:runtime [:rf.runtime/resources :entries k]]}])))}})]
    [raw (rf.derivation.egress/project-graph raw egress-frame)]))

(defn- resource-node-keys [graph]
  (filterv #(= :resource (first %)) (keys (:nodes graph))))

(defn- key-bytes
  "Host key bytes (`byte[]` / an array of ints) from 0-255 integers."
  [xs]
  #?(:clj (byte-array (map unchecked-byte xs)) :cljs (into-array xs)))

(deftest g-distinct-resource-identities-stay-distinct-nodes-at-egress
  (rf/make-frame {:id egress-frame})
  (let [[_ redacted] (egress-search-results {:q "Aa"} {:q "BB"})]
    (is (= 2 (count (resource-node-keys redacted))))))

(deftest g-opaque-handle-digest-is-hmac-sha256
  ;; Known answers prove the digest IS HMAC-SHA-256 on this host.
  (let [hmac #'rf.derivation.egress/hmac-sha256-hex
        jefe (key-bytes [0x4a 0x65 0x66 0x65])]
    (testing "RFC 4231 test vectors"
      (is (= "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
             (hmac (key-bytes (repeat 20 0x0b)) "Hi There"))
          "RFC 4231 test case 1")
      (is (= "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
             (hmac jefe "what do ya want for nothing?"))
          "RFC 4231 test case 2"))
    (is (= "a956a5d2b915c9ced86c9664fbd1903b82f72541a8a2d71ff0638382c79f5c1e"
           (hmac jefe "café ✓"))
        "the message is digested as UTF-8")))

(deftest g-live-handle-is-a-full-width-keyed-digest
  (rf/make-frame {:id egress-frame})
  (let [params              {:user/id 424242}
        [raw redacted]      (egress-search-results params)
        [_ [_ _ [tag hex]]] (first (resource-node-keys redacted))
        [_ redacted']       (egress-search-results {:f (fn [] nil)})
        [_ [_ _ handle']]   (first (resource-node-keys redacted'))]
    (is (= [:rf.resource/opaque true] [tag (some? (re-matches #"[0-9a-f]{64}" (str hex)))])
        "64 lowercase hex chars, untruncated")
    (is (not= (#'re-frame.schemas.digest/sha256-hex (rf.identity/canonical-bytes params)) hex)
        "keyed, not an unkeyed SHA-256")
    (is (= redacted (rf.derivation.egress/project-graph raw egress-frame))
        "the same value gets the same handle within one runtime")
    (is (= rf.privacy/redacted-sentinel handle') "a value outside the CEDN-1 domain fails closed")))

;; ===========================================================================
;; (g+) The real resource contributor applies classification and scoped-key
;; projection before the composer receives its nodes. The cache row is a
;; direct fixture (this artefact has no HTTP dependency); the route still runs
;; through normal navigation so its owner and activation edge are genuine.
;; ===========================================================================

(def ^:private real-egress-frame :app/real-resource-egress)

(deftest gplus-resource-cache-graph-egress-via-the-tooling-path
  (rf/make-frame {:id real-egress-frame})
  (rf/reg-resource :secret/tenant-article
                   {:scope         :rf.scope/global
                    :params-schema [:map [:auth-token :string]]
                    :sensitive?    true}
                   (fn [{:keys [auth-token]} _ctx]
                     {:request {:method  :get
                                :url     "/api/secure-article"
                                :headers {"Authorization" auth-token}}}))
  (rf/reg-route :route/secure-article {} "/secure")
  (rf/dispatch-sync [:rf.route/navigate {:to :route/secure-article}] {:frame real-egress-frame})
  (let [owner      (:owner (rf.routing.tooling/route-slice-algebra-view real-egress-frame))
        scoped-key (rf.resources.state/scoped-resource-key :rf.scope/global :secret/tenant-article
                                                           {:auth-token secret-token})
        work-id    (rf.resources.work-ledger/resource-work-id scoped-key 1)]
    (rf.frame/swap-runtime-db!
      real-egress-frame
      (fn [rdb]
        (-> (assoc-in (or rdb {}) (rf.resources.state/entry-path scoped-key)
                      (assoc (rf.resources.state/empty-entry :secret/tenant-article scoped-key)
                             :status :fetching :active-owners #{owner} :current-work work-id))
            (rf.resources.work-ledger/put-record
              work-id
              (rf.resources.work-ledger/work-record {:work-id      work-id
                                                     :frame-id     real-egress-frame
                                                     :resource/key scoped-key
                                                     :generation   1
                                                     :transport    :rf.http/managed
                                                     :owner        owner
                                                     :cause        :test/materialize}))))))
  (let [g                  (rf.derivation.graph/live-derivation-graph real-egress-frame all-contributors)
        [res-key res-node] (first (filter (fn [[_ n]] (= :resources (:rf/family n))) (:nodes g)))]
    (is (not (contains-secret? g)) "the auth-token appears nowhere in the composed graph")
    (is (= [:secret/tenant-article res-key :fetching true true]
           [(nth (:id res-node) 1)
            (:to (first (filter #(= :param (:role %)) (:edges g))))
            (:status res-node)
            (some? (get-in res-node [:work-ledger :work/id]))
            (some? (:host-transient res-node))])
        "the resource id stays visible, the route edge joins the projected key, and the ledger and host-handle positions are present")))
