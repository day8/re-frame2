(ns re-frame.derivation-algebra-conformance-cljs-test
  "Cross-family derivation graph, lifecycle, read-only and egress conformance."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.frame :as rf.frame]
            [re-frame.derivation.graph :as rf.derivation.graph]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.derivation.egress :as rf.derivation.egress]
            [re-frame.classification :as rf.classification]
            [re-frame.image :as rf.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.identity :as rf.identity]
            [re-frame.schemas.digest]
            [re-frame.privacy :as rf.privacy]
            [re-frame.elision :as rf.elision]
            ;; Facades install the registrations used by family contributors.
            [re-frame.routing]
            [re-frame.routing.tooling :as rf.routing.tooling]
            [re-frame.resources]
            [re-frame.resources.tooling :as rf.resources.tooling]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]
            [re-frame.machines.tooling :as rf.machines.tooling]
            #?(:cljs [re-frame.subs :as rf.subs])
            [re-frame.subs.tooling :as rf.subs.tooling]
            [re-frame.flows.tooling :as rf.flows.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; Restore registrations and reload family hooks between tests.
(defn- refresh-families! []
  (rf.flows/reset-flows!)
  (rf.flows/reset-last-inputs!)
  (rf.registrar/clear-kind! :app-schema)
  #?(:clj (do (require 're-frame.routing :reload)
              (require 're-frame.resources :reload)
              (require 're-frame.machines :reload)))
  (rf.frame/ensure-default-frame!))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn refresh-families!}))

;; Pass contributors explicitly so CLJ and CLJS exercise the same assembly.
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

(defn- register-one-of-each!
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

(deftest c-static-edges-span-input-param-selector
  (register-one-of-each!)
  (let [edges (:edges (rf.derivation.graph/derivation-graph all-contributors))]
    (is (some #{{:from [:sub :cart/items] :to [:sub :cart/total] :role :input}} edges))
    (is (some #(and (= [:rf/route :route/article] (:from %))
                    (= [:resource :article/by-slug] (:to %))
                    (= :param (:role %)))
              edges))
    (is (some #{{:from [:machine :upload/main] :to [:sub :upload/progress] :role :selector}} edges))))

(deftest c-named-resolver-scope-input-appears-statically-through-the-composer
  ;; Static inspection must not invoke the resolver.
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
           [(:inputs res) (get-in res [:scope-resolver :id]) (get-in res [:scope-resolver :inputs])]))))

(deftest c-live-graph-has-the-mode-frame-shape-and-realizes-the-route-slice
  (register-one-of-each!)
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "welcome"}}])
  (let [g     (rf.derivation.graph/live-derivation-graph :rf/default all-contributors)
        slice (get (:nodes g) :rf/route)]
    (is (= [:live :rf/default true true] ((juxt :mode :frame (comp map? :nodes) (comp vector? :edges)) g)))
    (is (= {:route-id :route/article :params {:slug "welcome"}
            :storage :runtime-db :evaluation :on-route :lifecycle :frame
            :owner [:route :route/article (:nav-token slice)]}
           (select-keys slice [:route-id :params :storage :evaluation :lifecycle :owner])))))

;; Synthetic contributors isolate assembly from each family's projection.
(def ^:private live-composition-nav-token 7)
(def ^:private live-composition-scoped-key
  [[:rf.scope/global] :article/by-slug {:slug "a1"}])
(def ^:private live-composition-work-id
  [:rf.work/resource :article/by-slug 3])

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
           (set (keys nodes))))
    (is (= (assoc live-composition-resource-node :rf/family :resources)
           (get nodes [:resource live-composition-scoped-key])))
    (is (= #{{:from [:sub [:article/by-slug "a1"]] :to [:sub [:article/page "a1"]] :role :input}
             {:from  :rf/route
              :to    [:resource live-composition-scoped-key]
              :role  :param
              :owner [:route :route/article live-composition-nav-token]}}
           (set edges)))))

;; Opposite insertion orders vary nested-map spelling and duplicate inputs.
(defn- permutation-sub-node [id inputs]
  {:id id :kind :derivation :inputs inputs
   :output [:fact id] :storage :ephemeral :evaluation :on-demand})

(defn- permutation-route
  [route-id slug slug-first?]
  {:id :rf/route :kind :process :refinement :route-fact
   :route-id route-id :storage :runtime-db :evaluation :on-route :lifecycle :frame
   :resource-edges [{:to     [:resource (if slug-first? {:slug slug :locale :en} {:locale :en :slug slug})]
                     :role   :param
                     :target :parametric}]})

(defn- permutation-graph
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
           (:edges baseline)))
    (is (= baseline (permutation-graph [:c :b :a] [:r2 :r1] false)))))

;; Live query arguments may lie outside the canonical EDN domain.
(def ^:private out-of-domain-arg-fn
  (fn [x] x))

(defn- live-query-arg-contributors
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
         (set (for [order [[:a :b :c :d] [:d :c :b :a]]]
                (:edges (rf.derivation.graph/live-derivation-graph
                          :rf/default (live-query-arg-contributors order))))))))

(deftest e-destroying-a-frame-releases-its-frame-owned-graph-nodes
  (register-one-of-each!)
  (rf/make-frame {:id :checkout/frame})
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "welcome"}}]
                    {:frame :checkout/frame})
  (rf/dispatch-sync [:upload/main [:upload/start]] {:frame :checkout/frame})
  (let [live #(rf.derivation.graph/live-derivation-graph :checkout/frame all-contributors)]
    (is (every? (:nodes (live)) [:rf/route [:machine :upload/main]]))
    (rf.frame/destroy-frame! :checkout/frame)
    (is (= [:live {} []] ((juxt :mode :nodes :edges) (live))))))

(deftest e-route-owned-resource-release-propagates-through-the-assembled-live-graph
  ;; Seed the cache directly; navigation still acquires and releases the real route owner.
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
        work-id    (rf.resources.work-ledger/resource-work-id :article/by-slug 1)
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
              (mapv (juxt :from :to :owner) (params g-a))])))
    (rf/dispatch-sync [:rf.route/navigate {:to :route/about}])
    (let [g-b   (live)
          slice (get (:nodes g-b) :rf/route)]
      (is (= [:route :route/about (:nav-token slice)] (:owner slice)))
      (is (= [[#{}] []]
             [(mapv #(get-in % [:lifecycle :owners]) (resources g-b)) (params g-b)])))))

(deftest e-machine-destroy-releases-the-machine-owned-snapshot-node
  (rf/reg-machine :job/runner
                  {:initial :running
                   :data    {}
                   :states  {:running {:on {:job/finish :done}}
                             :done    {:final? true}}})
  ;; An unhandled event installs a running snapshot.
  (rf/dispatch-sync [:job/runner [:job/finish-noop]])
  (let [node #(get-in (rf.derivation.graph/live-derivation-graph :rf/default all-contributors)
                      [:nodes [:machine :job/runner]])]
    (is (some? (node)))
    (rf/reg-event ::destroy-runner (fn [_ _] {:fx [[:rf.machine/destroy :job/runner]]}))
    (rf/dispatch-sync [::destroy-runner])
    (is (nil? (node)))))

#?(:cljs
   (deftest e-subscription-disposal-releases-its-cache-entry-node
     (register-one-of-each!)
     (rf/reg-sub :cart/item-qty
                 {:inputs (fn [[_ _sku]] [[:cart/items]])}
                 (fn [[items] [_ sku]] (some #(when (= sku (:sku %)) (:qty %)) items)))
     (rf/dispatch-sync [::seed-cart [{:sku "b" :qty 3}]])
     (let [q    [:cart/item-qty "b"]
           r    (rf.subs/subscribe q {:frame :rf/default})
           node #(get-in (rf.derivation.graph/live-derivation-graph :rf/default all-contributors)
                         [:nodes [:sub q]])]
       (is (= [3 :subscription-cache-entry 1] [@r (:lifecycle (node)) (:ref-count (node))]))
       (rf.subs/unsubscribe r)
       (is (nil? (node))))))

(deftest f-reading-an-on-demand-node-causes-no-durable-write
  (register-one-of-each!)
  (let [durable  #(mapv (fn [read] (read :rf/default))
                        [rf.frame/frame-app-db-value rf.frame/frame-runtime-db-value])
        before   (durable)
        _        @(rf/subscribe [:cart/items])
        selected @(rf/subscribe [:rf/resource {:resource :article/by-slug :params {:slug "welcome"}}])]
    (is (= :idle (:status selected)))
    (is (= before (durable)))))

;; Resource identities repeat scope/params in keys, fields, ledger and edges.
(def ^:private egress-frame :app/egress-secure)

(def ^:private secret-token "tenant-jwt-9f3a-SECRET")
(def ^:private secret-scope  [:rf.scope/tenant secret-token])
(def ^:private secret-params {:slug "welcome" :auth-token secret-token})
(def ^:private egress-scoped-key [secret-scope :article/by-slug secret-params])
(def ^:private egress-nav-token 23)
(def ^:private egress-generation 4)
(def ^:private egress-work-id [:rf.work/resource :article/by-slug egress-generation])

(defn- egress-live-contributors
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
  [graph]
  (first (filter (fn [[k _]] (and (vector? k) (= :resource (first k)))) (:nodes graph))))

(deftest g-live-resource-identity-redacted-at-graph-egress
  (rf/make-frame {:id egress-frame :doc "off-box egress conformance frame"})
  (let [raw                (rf.derivation.graph/live-derivation-graph egress-frame (egress-live-contributors))
        redacted           (rf.derivation.egress/project-graph raw egress-frame)
        [node-key node]    (resource-entry redacted)
        projected          (second node-key)
        wid                (get-in node [:work-ledger :work/id])]
    (is (contains-secret? raw))
    (is (not (contains-secret? redacted)))
    (is (= [3 :article/by-slug] [(count projected) (nth projected 1)]))
    (is (= [projected projected projected node-key]
           [(:id node)
            (last (second (:output node)))
            (get-in node [:work-ledger :record :resource/key])
            (:to (first (filter #(= :param (:role %)) (:edges redacted))))]))
    (is (= [:process :resource-process [:scope :param] :runtime [:rf.runtime/resources :entries] :pending]
           [(:kind node) (:refinement node) (mapv first (:inputs node)) (first (:output node))
            (vec (take 2 (second (:output node)))) (get-in node [:work-ledger :record :status])]))
    (is (= [egress-work-id wid wid]
           [wid (get-in node [:work-ledger :record :work/id]) (second (first (:host-transient node)))])
        "a work id names its attempt by resource id and generation, so it carries nothing to project")

    (is (= redacted (rf.derivation.egress/project-graph redacted egress-frame)))))

(defn- egress-sensitive-value-contributors
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
  (rf/make-frame {:id egress-frame})
  (classify-egress-frame-sensitive!)
  (let [raw     (rf.derivation.graph/live-derivation-graph egress-frame (egress-sensitive-value-contributors))
        unknown (rf.derivation.egress/project-graph raw :app/does-not-exist)]
    (is (= [rf.privacy/redacted-sentinel {:cart {:items rf.privacy/redacted-sentinel}}]
           [(sub-value unknown) (sub-value (rf.derivation.egress/project-graph raw egress-frame))]))
    (is (not (contains-secret? unknown)))))

(deftest g-graph-egress-nil-frame-is-unregistrable-and-fails-closed
  ;; Try a nil-id frame and bind an ambient frame to expose accidental policy borrowing.
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

(def ^:private partner-subs
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
    (is (contains-secret? raw))
    (is (= [rf.privacy/redacted-sentinel {:token rf.privacy/redacted-sentinel :user "ada"} "Acme"]
           (mapv #(live-value redacted %)
                 [[:partner/api-token] [:partner/session] [:partner/name]])))
    (is (= (without-values raw) (without-values redacted)))
    (is (= (mapv (fn [[q v]] (trace-projected-value egress-frame q v)) partner-subs)
           (mapv #(live-value redacted %) (keys partner-subs))))
    (is (= redacted (rf.derivation.egress/project-graph redacted egress-frame)))))

(deftest g-graph-egress-resolves-the-sub-in-the-inspected-frames-generation
  ;; Only the inspected image declares this sub's sensitive output.
  (rf.live-frame/make-frame
    {:id     :app/partner-image
     :images [(rf.image/image {:id            :app/partner
                               :registrations {:reg-sub [[:partner/api-token {:sensitive [[]]}
                                                          api-token-sub]]}})]}
    [])
  (let [graph (partner-graph :app/partner-image (select-keys partner-subs [[:partner/api-token]]))]
    (is (= rf.privacy/redacted-sentinel
           (live-value (rf.derivation.egress/project-graph graph :app/partner-image) [:partner/api-token])))))

;; A subscription query can resemble a scoped resource key.
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
    (is (not (contains-secret? redacted)))))

;; Full keyed handles keep colliding 32-bit hashes distinct and resist enumeration.
(defn- egress-search-results
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
  [xs]
  #?(:clj (byte-array (map unchecked-byte xs)) :cljs (into-array xs)))

(deftest g-opaque-handle-digest-is-hmac-sha256
  (let [hmac #'rf.derivation.egress/hmac-sha256-hex
        jefe (key-bytes [0x4a 0x65 0x66 0x65])]
    (is (= "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
           (hmac (key-bytes (repeat 20 0x0b)) "Hi There")))
    (is (= "a956a5d2b915c9ced86c9664fbd1903b82f72541a8a2d71ff0638382c79f5c1e"
           (hmac jefe "café ✓")))))

(deftest g-live-handle-is-a-full-width-keyed-digest
  (rf/make-frame {:id egress-frame})
  (let [params              {:user/id 424242}
        [raw redacted]      (egress-search-results params)
        [_ [_ _ [tag hex]]] (first (resource-node-keys redacted))
        [_ redacted']       (egress-search-results {:f (fn [] nil)})
        [_ [_ _ handle']]   (first (resource-node-keys redacted'))
        [_ colliding]       (egress-search-results {:q "Aa"} {:q "BB"})]
    (is (= [:rf.resource/opaque true] [tag (some? (re-matches #"[0-9a-f]{64}" (str hex)))]))
    (is (not= (#'re-frame.schemas.digest/sha256-hex (rf.identity/canonical-bytes params)) hex))
    (is (= redacted (rf.derivation.egress/project-graph raw egress-frame)))
    (is (= rf.privacy/redacted-sentinel handle'))
    (is (= 2 (count (resource-node-keys colliding))))))

;; Seed the cache without HTTP; real navigation supplies the owner and route edge.
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
        work-id    (rf.resources.work-ledger/resource-work-id :secret/tenant-article 1)]
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
    (is (not (contains-secret? g)))
    (is (= [:secret/tenant-article res-key :fetching true true]
           [(nth (:id res-node) 1)
            (:to (first (filter #(= :param (:role %)) (:edges g))))
            (:status res-node)
            (some? (get-in res-node [:work-ledger :work/id]))
            (some? (:host-transient res-node))]))))
