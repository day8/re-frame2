(ns re-frame.derivation-graph-test
  "Tests for the internal derivation/process GRAPH-INSPECTION helper
  (EP-0014 slice-7, rf2-6xm07h). Per [spec/Derivations.md] §Graph
  inspection — internal but structured (graduated from EP-0014) and the
  `:rf/derivation-graph` shape in [spec/Spec-Schemas.md].

  `re-frame.derivation.graph/derivation-graph` composes the five
  algebra-view tooling siblings (subs / flows / resources / routes /
  machines) into ONE `{:mode :nodes :edges}` graph — the shape an Xray
  panel renders as one graph even though the underlying runtime mechanisms
  are subscription cache, flow registry, route slice, resource cache, and
  machine snapshots. `…/live-derivation-graph` is the live counterpart.

  These tests pin:
    - the assembled graph contains nodes from ALL FIVE families + their
      edges (the slice-7 acceptance contract);
    - canonical node-id tagging per family (`[:sub …] / [:flow …] /
      [:resource …] / [:machine …] / [:rf/route …]`);
    - `:input` edges from `[:sub …]` declared inputs, `:param` edges from a
      route's `:resource-edges`, and `:selector` edges from a machine to
      its selector subscription;
    - the static vs live split (a parametric sub contributes NO static
      edge; the live sub-cache realizes them — CLJS-only, so the JVM live
      test pins the route slice + resource cache realized nodes);
    - bundle isolation: a family whose contributor is absent contributes
      no nodes (the no-flows / no-resources story).

  Slice-7 ships NO public accessor (EP-0014 issue-1 disposition): the
  composer lives in the bundle-isolated `re-frame.derivation.graph` ns,
  consumed by Xray + the conformance fixtures; there is no
  `re-frame.core` facade export and no api-manifest row (it mirrors the
  five siblings' CLJS-side fns — reached directly by the consuming tool)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.derivation.graph :as graph]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            ;; load-bearing side-effecting requires: each façade registers
            ;; its registrar kind / framework events so the family is live.
            [re-frame.routing]
            [re-frame.routing.tooling :as routing-tooling]
            [re-frame.resources]
            [re-frame.resources.tooling :as resources-tooling]
            [re-frame.machines.tooling :as machines-tooling]
            [re-frame.subs.tooling :as subs-tooling]
            [re-frame.flows.tooling :as flows-tooling]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (flows/reset-last-inputs!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; re-`require` every optional façade so its framework registrations
  ;; (registrar kinds, framework events, subs) are present after the
  ;; clear-all! above.
  (require 're-frame.routing :reload)
  (require 're-frame.resources :reload)
  (require 're-frame.machines :reload)
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- the JVM contributor map: every family present ------------------------
;;
;; On the JVM `default-contributors` auto-resolves every sibling on the
;; classpath. We build the contributor map explicitly so the test pins the
;; composition regardless of resolution, and exercises the explicit-arg
;; path the CLJS consumer uses.
(def all-contributors
  {:subs      {:static-fn  subs-tooling/sub-algebra-view
               :live-fn    subs-tooling/sub-cache-algebra-view
               :live-shape :map}
   :flows     {:static-fn  flows-tooling/flow-algebra-view
               :live-fn    flows-tooling/flow-algebra-view
               :live-shape :map}
   :resources {:static-fn  resources-tooling/resource-algebra-view
               :live-fn    resources-tooling/resource-cache-algebra-view
               :live-shape :map}
   :routes    {:static-fn  routing-tooling/route-algebra-view
               :live-fn    routing-tooling/route-slice-algebra-view
               :live-shape :node}
   :machines  {:static-fn  machines-tooling/machine-algebra-view
               :live-fn    machines-tooling/machine-instance-algebra-view
               :live-shape :map
               :selector?  machines-tooling/machine-selector?}})

(defn- register-one-of-each! []
  ;; :subs — a static `:<-` sub (an :input edge source) + a layer-1 sub.
  (rf/reg-sub :cart/items (fn [db _] (get-in db [:cart :items])))
  (rf/reg-sub :cart/total
              :<- [:cart/items]
              (fn [items _] (count items)))
  ;; :subs — a machine selector (a sub over [:rf/machine …]) — gets the
  ;; :selector edge from the machine process.
  (rf/reg-sub :upload/progress
              :<- [:rf/machine :upload/main]
              (fn [snapshot _] (get-in snapshot [:data :progress] 0)))
  ;; :flows — a materialized after-event derivation.
  (rf/reg-flow {:id     :cart/materialized-total
                :inputs [[:cart :items]]
                :output count
                :path   [:cart :total]})
  ;; :resources — a process node (runtime-db / remote authority).
  (rf/reg-resource :article/by-slug
                   {:scope         :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :request       (fn [{:keys [slug]} _ctx]
                                     {:request {:method :get
                                                :url    (str "/api/articles/" slug)}})})
  ;; :routes — a route fact (runtime-db / on-route).
  (rf/reg-route :route/article {:path "/articles/:slug"})
  ;; :machines — a process node (runtime-db / on-transition).
  (rf/reg-machine :upload/main
                  {:initial :idle
                   :data    {:progress 0}
                   :states  {:idle      {:on {:upload/start {:target :uploading}}}
                             :uploading {:on {:upload/done {:target :idle}}}}}))

;; ---- empty / shape contract ----------------------------------------------

(deftest empty-graph-has-the-mode-nodes-edges-shape
  (testing "(derivation-graph) returns the {:mode :static :nodes :edges} shape"
    (let [g (graph/derivation-graph all-contributors)]
      (is (= :static (:mode g)))
      (is (map? (:nodes g)))
      (is (vector? (:edges g)))))
  (testing "(live-derivation-graph) carries :mode :live + :frame"
    (let [g (graph/live-derivation-graph :rf/default all-contributors)]
      (is (= :live (:mode g)))
      (is (= :rf/default (:frame g)))
      (is (map? (:nodes g)))
      (is (vector? (:edges g))))))

;; ---- the headline acceptance contract: ALL FIVE families + edges ----------

(deftest static-graph-contains-nodes-from-all-five-families
  (testing "the assembled static graph carries nodes from subs, flows,
            resources, routes, AND machines"
    (register-one-of-each!)
    (let [g        (graph/derivation-graph all-contributors)
          nodes    (:nodes g)
          families (->> nodes vals (map :rf/family) set)]
      ;; Every family contributed at least one node.
      (is (= #{:subs :flows :resources :routes :machines} families)
          "all five algebra-view families are present in the assembled graph")
      ;; Canonical node-id tagging per family.
      (is (contains? nodes [:sub :cart/total]) "a subscription node, [:sub …]-tagged")
      (is (contains? nodes [:flow :cart/materialized-total]) "a flow node, [:flow …]-tagged")
      (is (contains? nodes [:resource :article/by-slug]) "a resource node, [:resource …]-tagged")
      (is (contains? nodes [:machine :upload/main]) "a machine node, [:machine …]-tagged")
      (is (contains? nodes [:rf/route :route/article]) "a route node, [:rf/route …]-tagged")
      ;; The superkinds are classified — a tool that knows only the two
      ;; superkinds can classify every node.
      (is (= :derivation (get-in nodes [[:sub :cart/total] :kind])))
      (is (= :derivation (get-in nodes [[:flow :cart/materialized-total] :kind])))
      (is (= :process    (get-in nodes [[:resource :article/by-slug] :kind])))
      (is (= :process    (get-in nodes [[:rf/route :route/article] :kind])))
      (is (= :machine-process (get-in nodes [[:machine :upload/main] :kind]))))))

(deftest static-graph-edges-span-input-param-and-selector-roles
  (testing "the assembled edges carry :input, :param, and :selector roles"
    (register-one-of-each!)
    (let [g     (graph/derivation-graph all-contributors)
          edges (:edges g)
          roles (->> edges (map :role) set)]
      ;; :input — :cart/total depends on :cart/items. In the STATIC graph
      ;; subscription nodes are keyed by bare sub-id, and a [:sub [:q]]
      ;; declared input resolves to that node id (the qv's head).
      (is (some #(= % {:from [:sub :cart/items]
                       :to   [:sub :cart/total]
                       :role :input})
                edges)
          "the static :<- input edge from :cart/items to :cart/total")
      ;; :selector — :upload/main → :upload/progress (the machine selector).
      (is (some #(= % {:from [:machine :upload/main]
                       :to   [:sub :upload/progress]
                       :role :selector})
                edges)
          "the :selector edge from the machine process to its selector sub")
      (is (contains? roles :input))
      (is (contains? roles :selector)))))

(deftest route-resource-activation-edge-is-param-role
  (testing "a route's :resources route-metadata becomes a :param edge"
    ;; The Resources artefact publishes :resources as an accepted route key;
    ;; both façades are loaded in this core test, so reg-route accepts it.
    (rf/reg-route :route/article
                  {:path      "/articles/:slug"
                   :resources [{:resource :article/by-slug
                                :blocking? true}]})
    (rf/reg-resource :article/by-slug
                     {:scope         :rf.scope/global
                      :params-schema [:map [:slug :string]]
                      :request       (fn [{:keys [slug]} _ctx]
                                       {:request {:method :get
                                                  :url    (str "/api/articles/" slug)}})})
    (let [g     (graph/derivation-graph all-contributors)
          edges (:edges g)
          param (filter #(= :param (:role %)) edges)]
      (is (seq param) "a :param edge is present")
      (is (some #(and (= [:rf/route :route/article] (:from %))
                      (= [:resource :article/by-slug] (:to %))
                      (= :param (:role %)))
                param)
          "the route-owned resource activation edge runs route → resource"))))

;; ---- static vs live: the don't-execute rule -------------------------------

(deftest parametric-sub-contributes-no-static-edge
  (testing "a parametric input-fn sub contributes no static :input edge (don't-execute rule)"
    (rf/reg-sub :article/page
                (fn [[_ slug]] [[:article/by-slug slug] [:comments/for-article slug]])
                (fn [[a c] _] {:article a :comments c}))
    (let [g     (graph/derivation-graph all-contributors)
          node  (get (:nodes g) [:sub :article/page])]
      (is (some? node) "the parametric sub node is present")
      (is (= :parametric (:inputs node)) "its declared inputs are the :parametric marker")
      ;; No static edge names a realized [:article/by-slug …] input — those
      ;; only appear in the LIVE graph (per concrete query vector).
      (is (not-any? #(= [:sub :article/page] (:to %)) (:edges g))
          "no static input edge points at the parametric sub"))))

;; ---- the bundle-isolation / artefact-absence story ------------------------

(deftest absent-family-contributes-no-nodes
  (testing "a family whose contributor is absent contributes no nodes"
    (register-one-of-each!)
    ;; Drop the resources + machines contributors — simulate a no-resources
    ;; / no-machines app. Those families must vanish from the graph.
    (let [subset (dissoc all-contributors :resources :machines)
          g      (graph/derivation-graph subset)
          families (->> (:nodes g) vals (map :rf/family) set)]
      (is (= #{:subs :flows :routes} families)
          "only the present families' nodes appear")
      (is (not (contains? (:nodes g) [:resource :article/by-slug])))
      (is (not (contains? (:nodes g) [:machine :upload/main])))))
  (testing "the subs-only graph still assembles (the core-only app)"
    (rf/reg-sub :cart/items (fn [db _] (get-in db [:cart :items])))
    (let [g (graph/derivation-graph {:subs (:subs all-contributors)})]
      (is (= #{:subs} (->> (:nodes g) vals (map :rf/family) set)))
      (is (contains? (:nodes g) [:sub :cart/items])))))

;; ---- default-contributors auto-resolution (JVM) ---------------------------

(deftest default-contributors-resolves-every-jvm-sibling
  (testing "on the JVM default-contributors auto-resolves all five families"
    ;; Every artefact is on the core :test classpath, so resolution yields
    ;; the full set (the no-arg derivation-graph composes them all).
    (register-one-of-each!)
    (let [g        (graph/derivation-graph)
          families (->> (:nodes g) vals (map :rf/family) set)]
      (is (= #{:subs :flows :resources :routes :machines} families)
          "the zero-arg form auto-resolves and composes every present family")
      ;; And it equals the explicit-contributor composition.
      (is (= (:nodes (graph/derivation-graph))
             (:nodes (graph/derivation-graph all-contributors)))
          "the auto-resolved graph equals the explicit-contributor graph"))))

;; ---- live graph: realized route slice + resource entries ------------------

(deftest live-graph-realizes-the-route-slice
  (testing "the live graph carries the materialized route slice node"
    (rf/reg-route :route/article {:path "/articles/:slug"})
    ;; Drive a navigation so the route slice is materialized in runtime-db.
    (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "welcome"}])
    (let [g     (graph/live-derivation-graph :rf/default all-contributors)
          slice (get (:nodes g) :rf/route)]
      (is (some? slice) "the live route slice node is present, keyed by :rf/route")
      (is (= :route/article (:route-id slice)) "the live matched route id")
      (is (= {:slug "welcome"} (:params slice)) "the live matched params"))))
