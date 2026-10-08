(ns re-frame.derivation-graph-test
  "The internal derivation/process graph composer, `re-frame.derivation.graph`
  (spec/Derivations.md §Graph inspection — internal but structured;
  `:rf/derivation-graph` in spec/Spec-Schemas.md). `derivation-graph` composes
  the five algebra-view tooling siblings (subs / flows / resources / routes /
  machines) into ONE `{:mode :nodes :edges}` graph, and `live-derivation-graph`
  is its live counterpart. A family whose contributor is absent contributes no
  nodes. There is no public accessor: Xray and the conformance fixtures name
  the bundle-isolated namespace directly.

  The derivation-conformance suite proves the EP-0014 laws over an explicit
  contributor map; JVM `default-contributors` auto-resolution is pinned here."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.derivation.graph :as rf.derivation.graph]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            ;; load-bearing side-effecting requires: each façade registers
            ;; its registrar kind / framework events so the family is live.
            [re-frame.routing]
            [re-frame.routing.tooling :as rf.routing.tooling]
            [re-frame.resources]
            [re-frame.resources.tooling :as rf.resources.tooling]
            [re-frame.machines.tooling :as rf.machines.tooling]
            [re-frame.subs.tooling :as rf.subs.tooling]
            [re-frame.flows.tooling :as rf.flows.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.flows/reset-last-inputs!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; re-`require` every optional façade so its framework registrations are
  ;; present after the clear-all! above.
  (require 're-frame.routing :reload)
  (require 're-frame.resources :reload)
  (require 're-frame.machines :reload)
  (rf.frame/ensure-default-frame!)
  (binding [rf.frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

;; The explicit contributor map, so the composition does not depend on JVM
;; auto-resolution and takes the explicit-arg path the CLJS consumer uses.
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

(defn- machine! [id]
  (rf/reg-machine id {:initial :idle :data {} :states {:idle {}}}))

(defn- register-one-of-each! []
  ;; :subs — a declared-input sub over a layer-1 sub, and a machine selector.
  (rf/reg-sub :cart/items (fn [db _] (get-in db [:cart :items])))
  (rf/reg-sub :cart/total {:inputs [[:cart/items]]} (fn [[items] _] (count items)))
  (rf/reg-sub :upload/progress
              {:inputs [[:rf/machine :upload/main]]}
              (fn [[snapshot] _] (get-in snapshot [:data :progress] 0)))
  (rf/reg-flow :cart/materialized-total {:inputs [[:cart :items]] :output-path [:cart :total]} count)
  (rf/reg-resource :article/by-slug
                   {:scope         :rf.scope/global
                    :params-schema [:map [:slug :string]]}
                   (fn [{:keys [slug]} _ctx]
                     {:request {:method :get :url (str "/api/articles/" slug)}}))
  ;; The route owns the resource's activation, which draws a :param edge.
  (rf/reg-route :route/article
                {:resources [{:resource :article/by-slug :blocking? true}]}
                "/articles/:slug")
  (machine! :upload/main))

(defn- families [g]
  (into #{} (map :rf/family) (vals (:nodes g))))

(deftest static-graph-contains-nodes-from-all-five-families
  ;; Each family's node sits under its canonical id, a flow's frame-scoped,
  ;; with its superkind and refinement. A machine selector stays a
  ;; :derivation and is refined :machine-selector; an ordinary sub is not.
  (register-one-of-each!)
  (let [expected {[:sub :cart/total]                           [:subs :derivation nil]
                  [:sub :upload/progress]                      [:subs :derivation :machine-selector]
                  [:flow :rf/default :cart/materialized-total] [:flows :derivation nil]
                  [:resource :article/by-slug]                 [:resources :process :resource-process]
                  [:rf/route :route/article]                   [:routes :process :route-fact]
                  [:machine :upload/main]                      [:machines :process :machine-process]}
        g        (rf.derivation.graph/derivation-graph all-contributors)]
    (is (= [:static expected]
           [(:mode g)
            (into {} (map (fn [id] [id ((juxt :rf/family :kind :refinement) (get-in g [:nodes id]))]))
                  (keys expected))]))))

(deftest static-graph-edges-carry-input-selector-and-param-roles
  ;; A static declared input resolves to the bare sub-id node; a route's
  ;; :resources metadata runs route → resource.
  (register-one-of-each!)
  (let [expected #{{:from [:sub :cart/items]         :to [:sub :cart/total]           :role :input}
                   {:from [:machine :upload/main]    :to [:sub :upload/progress]      :role :selector}
                   {:from [:rf/route :route/article] :to [:resource :article/by-slug] :role :param}}
        edges    (into #{} (map #(select-keys % [:from :to :role]))
                       (:edges (rf.derivation.graph/derivation-graph all-contributors)))]
    (is (= expected (set/intersection expected edges)))))

(deftest same-flow-id-on-two-frames-stays-distinct
  ;; A flow is frame-scoped, so one frame's flow never overwrites another's.
  (rf/make-frame {:id :app/a})
  (rf/make-frame {:id :app/b})
  (rf/with-frame :app/a
    (rf/reg-flow :shared/total {:inputs [[:cart :items]] :output-path [:a-total]} count))
  (rf/with-frame :app/b
    (rf/reg-flow :shared/total {:inputs [[:basket :lines]] :output-path [:b-total]} count))
  (is (= {[:flow :app/a :shared/total] [[:db [:a-total]] [:frame :app/a]]
          [:flow :app/b :shared/total] [[:db [:b-total]] [:frame :app/b]]}
         (into {} (keep (fn [[id node]]
                          (when (= :shared/total (:id node))
                            [id ((juxt :output :owner) node)])))
               (:nodes (rf.derivation.graph/derivation-graph all-contributors))))))

(deftest machine-selector-edge-targets-only-the-machine-it-reads
  ;; Never the cross product of every registered machine.
  (machine! :upload/main)
  (machine! :download/main)
  (rf/reg-sub :upload/progress
              {:inputs [[:rf/machine :upload/main]]}
              (fn [[snapshot] _] (get-in snapshot [:data :progress] 0)))
  (is (= [{:from [:machine :upload/main] :to [:sub :upload/progress] :role :selector}]
         (filterv #(= :selector (:role %))
                  (:edges (rf.derivation.graph/derivation-graph all-contributors))))))

(deftest absent-family-contributes-no-nodes
  ;; The no-resources / no-machines app, and the core-only app.
  (register-one-of-each!)
  (is (= [#{:subs :flows :routes} #{:subs}]
         (mapv #(families (rf.derivation.graph/derivation-graph (select-keys all-contributors %)))
               [[:subs :flows :routes] [:subs]]))))

(deftest default-contributors-resolves-every-jvm-sibling
  ;; Every artefact is on the core :test classpath, so the zero-arg form
  ;; composes the same graph as the explicit five-family map.
  (register-one-of-each!)
  (let [g (rf.derivation.graph/derivation-graph)]
    (is (= [#{:subs :flows :resources :routes :machines}
            (rf.derivation.graph/derivation-graph all-contributors)]
           [(families g) g]))))

(deftest default-contributors-wires-the-machine-selector-targets-surface
  ;; The zero-arg graph draws the precise machine → selector edge, so the
  ;; resolver carries the machines family's selector-target extractor.
  (machine! :upload/main)
  (rf/reg-sub :upload/progress {:inputs [[:rf/machine :upload/main]]} (fn [[snapshot] _] snapshot))
  (is (some #{{:from [:machine :upload/main] :to [:sub :upload/progress] :role :selector}}
            (:edges (rf.derivation.graph/derivation-graph)))))

(deftest resolve-sibling-yields-nil-for-an-absent-family
  ;; A genuinely-absent optional family is tolerated, not an error.
  (is (nil? (#'rf.derivation.graph/resolve-sibling 'totally.absent.sibling/static-view
                                                  'totally.absent.sibling/live-view
                                                  :map))))

;; ---- live graph: realized route-owned resource edges ----------------------
;;
;; Custom contributors return the realized shapes a navigation-then-fetch
;; produces: the live route slice with its `[:route route-id nav-token]`
;; owner, and a scoped-key resource entry whose `:lifecycle :owners` carries a
;; route owner. The static graph's `:parametric` route-resource marker
;; resolves to a concrete edge only in the live graph.

(def ^:private scoped-key-fixture
  [[:rf.scope/global] :article/by-slug {:slug "welcome"}])

(defn- live-graph-with-resource-owner [owner]
  (rf.derivation.graph/live-derivation-graph
   :rf/default
   {:routes    {:static-fn  (constantly {})
                :live-shape :node
                :live-fn    (constantly
                             {:id         :rf/route
                              :kind       :process
                              :refinement :route-fact
                              :route-id   :route/article
                              :params     {:slug "welcome"}
                              :nav-token  42
                              :owner      [:route :route/article 42]
                              :output     [:runtime [:rf.runtime/routing :current]]
                              :storage    :runtime-db :evaluation :on-route :lifecycle :frame})}
    :resources {:static-fn  (constantly {})
                :live-shape :map
                :live-fn    (constantly
                             {scoped-key-fixture
                              {:id         scoped-key-fixture
                               :kind       :process
                               :refinement :resource-process
                               :inputs     [[:scope [:rf.scope/global]] [:param {:slug "welcome"}]]
                               :output     [:runtime [:rf.runtime/resources :entries scoped-key-fixture]]
                               :storage    :runtime-db
                               :authority  {:kind :remote :system :server}
                               :evaluation #{:on-route}
                               :lifecycle  {:kind :scoped-resource-key :owners #{owner}}
                               :status     :loaded}})}}))

(deftest live-graph-draws-realized-route-owned-resource-edge
  ;; Only an owner matching a live route node's nav-token draws the edge; a
  ;; stale or superseded owner draws none.
  (is (= [{:from  :rf/route
           :to    [:resource scoped-key-fixture]
           :role  :param
           :owner [:route :route/article 42]}]
         (:edges (live-graph-with-resource-owner [:route :route/article 42]))))
  (is (= [] (:edges (live-graph-with-resource-owner [:route :route/article 999])))))
