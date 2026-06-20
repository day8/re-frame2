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
   :machines  {:static-fn         machines-tooling/machine-algebra-view
               :live-fn           machines-tooling/machine-instance-algebra-view
               :live-shape        :map
               :selector-targets  machines-tooling/machine-selector-targets}})

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
                :derive count
                :output-path   [:cart :total]})
  ;; :resources — a process node (runtime-db / remote authority).
  (rf/reg-resource :article/by-slug
                   {:scope         :rf.scope/global
                    :params-schema [:map [:slug :string]]}
                   (fn [{:keys [slug]} _ctx]
                     {:request {:method :get
                                :url    (str "/api/articles/" slug)}}))
  ;; :routes — a route fact (runtime-db / on-route).
  (rf/reg-route :route/article {} "/articles/:slug")
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
      ;; flow node id is FRAME-SCOPED (rf2-k0meap.2): [:flow <frame-id> <flow-id>]
      ;; so a reused flow-id across frames does not collapse onto one slot.
      (is (contains? nodes [:flow :rf/default :cart/materialized-total])
          "a flow node, [:flow <frame-id> <flow-id>]-tagged (frame-scoped)")
      (is (contains? nodes [:resource :article/by-slug]) "a resource node, [:resource …]-tagged")
      (is (contains? nodes [:machine :upload/main]) "a machine node, [:machine …]-tagged")
      (is (contains? nodes [:rf/route :route/article]) "a route node, [:rf/route …]-tagged")
      ;; The superkinds are classified — a tool that knows only the two
      ;; superkinds can classify every node.
      (is (= :derivation (get-in nodes [[:sub :cart/total] :kind])))
      (is (= :derivation (get-in nodes [[:flow :rf/default :cart/materialized-total] :kind])))
      (is (= :process    (get-in nodes [[:resource :article/by-slug] :kind])))
      (is (= :process    (get-in nodes [[:rf/route :route/article] :kind])))
      (is (= :process    (get-in nodes [[:machine :upload/main] :kind])))
      ;; The informative refinement rides on :refinement, never :kind — the
      ;; three :process families share one closed superkind (rf2-7wwp1z).
      (is (= :resource-process (get-in nodes [[:resource :article/by-slug] :refinement])))
      (is (= :route-fact       (get-in nodes [[:rf/route :route/article] :refinement])))
      (is (= :machine-process  (get-in nodes [[:machine :upload/main] :refinement]))))))

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
      (is (contains? roles :selector))
      ;; rf2-k0meap.2: the selector sub node is ENRICHED with the
      ;; :machine-selector refinement (still a :derivation superkind — not a
      ;; second subscription system; the refinement is colour, not contract).
      (is (= :machine-selector
             (get-in (:nodes g) [[:sub :upload/progress] :refinement]))
          "the selector sub node carries :refinement :machine-selector")
      (is (= :derivation (get-in (:nodes g) [[:sub :upload/progress] :kind]))
          "the selector remains an ordinary :derivation — refinement is not a superkind")
      ;; A NON-selector sub (no machine input) carries no machine-selector
      ;; refinement — only the actual selector targets are enriched.
      (is (nil? (get-in (:nodes g) [[:sub :cart/total] :refinement]))
          "a non-selector sub is not falsely refined :machine-selector"))))

(deftest same-flow-id-on-two-frames-stays-distinct
  ;; rf2-k0meap.2 point-1: a flow is FRAME-SCOPED — the same flow-id may
  ;; register against two frames with different :inputs / :derive / :output-path.
  ;; The composed graph must preserve the frame dimension so one frame's
  ;; flow does NOT overwrite another's when their ids match.
  (testing "the SAME flow-id registered on two frames yields TWO distinct
            composed flow nodes, keyed by [:flow <frame-id> <flow-id>]"
    (rf/reg-frame :app/a {})
    (rf/reg-frame :app/b {})
    ;; Same flow-id, different output paths, on two different frames.
    (rf/with-frame :app/a
      (rf/reg-flow {:id     :shared/total
                    :inputs [[:cart :items]]
                    :derive count
                    :output-path   [:a-total]}))
    (rf/with-frame :app/b
      (rf/reg-flow {:id     :shared/total
                    :inputs [[:basket :lines]]
                    :derive count
                    :output-path   [:b-total]}))
    (let [g     (graph/derivation-graph all-contributors)
          nodes (:nodes g)
          a-id  [:flow :app/a :shared/total]
          b-id  [:flow :app/b :shared/total]]
      (is (contains? nodes a-id) "frame :app/a's flow node is present, frame-scoped")
      (is (contains? nodes b-id) "frame :app/b's flow node is present, frame-scoped")
      (is (not= a-id b-id) "the two nodes have DISTINCT ids (no collapse)")
      ;; Each preserved its own per-frame output — the collapse bug would
      ;; have left only one of these.
      (is (= [:db [:a-total]] (get-in nodes [a-id :output]))
          "frame A's flow kept its own :output path")
      (is (= [:db [:b-total]] (get-in nodes [b-id :output]))
          "frame B's flow kept its own :output path")
      (is (= [:frame :app/a] (get-in nodes [a-id :owner])))
      (is (= [:frame :app/b] (get-in nodes [b-id :owner]))))))

(deftest route-resource-activation-edge-is-param-role
  (testing "a route's :resources route-metadata becomes a :param edge"
    ;; The Resources artefact publishes :resources as an accepted route key;
    ;; both façades are loaded in this core test, so reg-route accepts it.
    (rf/reg-route :route/article
                  {:resources [{:resource :article/by-slug
                                :blocking? true}]} "/articles/:slug")
    (rf/reg-resource :article/by-slug
                     {:scope         :rf.scope/global
                      :params-schema [:map [:slug :string]]}
                     (fn [{:keys [slug]} _ctx]
                       {:request {:method :get
                                  :url    (str "/api/articles/" slug)}}))
    (let [g     (graph/derivation-graph all-contributors)
          edges (:edges g)
          param (filter #(= :param (:role %)) edges)]
      (is (seq param) "a :param edge is present")
      (is (some #(and (= [:rf/route :route/article] (:from %))
                      (= [:resource :article/by-slug] (:to %))
                      (= :param (:role %)))
                param)
          "the route-owned resource activation edge runs route → resource"))))

;; ---- precise machine→selector edge targeting (rf2-4qmiij) -----------------

(deftest machine-selector-edge-targets-only-the-machine-it-reads
  (testing "in a multi-machine app a selector edge runs ONLY from the machine
            the selector reads — never the cross product of every machine"
    ;; Two registered machines; ONE selector reading ONLY :upload/main.
    (rf/reg-machine :upload/main
                    {:initial :idle
                     :data    {:progress 0}
                     :states  {:idle {:on {:upload/start {:target :uploading}}}
                               :uploading {:on {:upload/done {:target :idle}}}}})
    (rf/reg-machine :download/main
                    {:initial :idle
                     :data    {:bytes 0}
                     :states  {:idle {:on {:download/start {:target :fetching}}}
                               :fetching {:on {:download/done {:target :idle}}}}})
    (rf/reg-sub :upload/progress
                :<- [:rf/machine :upload/main]
                (fn [snapshot _] (get-in snapshot [:data :progress] 0)))
    (let [g     (graph/derivation-graph all-contributors)
          edges (:edges g)
          sel   (filter #(= :selector (:role %)) edges)]
      ;; EXACTLY one selector edge, from :upload/main → :upload/progress.
      (is (= [{:from [:machine :upload/main]
               :to   [:sub :upload/progress]
               :role :selector}]
             (vec sel))
          "one selector edge from the machine the selector actually reads")
      ;; The unrelated machine receives NO selector edge (the cross-product bug).
      (is (not-any? #(= [:machine :download/main] (:from %)) sel)
          "no selector edge from the unrelated :download/main machine"))))

(deftest machine-has-tag-selector-targets-the-named-machine
  (testing "a [:rf/machine-has-tag? machine-id tag] selector targets only that machine"
    (rf/reg-machine :upload/main
                    {:initial :idle :data {} :states {:idle {}}})
    (rf/reg-machine :download/main
                    {:initial :idle :data {} :states {:idle {}}})
    (rf/reg-sub :upload/busy?
                :<- [:rf/machine-has-tag? :upload/main :busy]
                (fn [tagged? _] (boolean tagged?)))
    (let [g   (graph/derivation-graph all-contributors)
          sel (filter #(= :selector (:role %)) (:edges g))]
      (is (= [{:from [:machine :upload/main]
               :to   [:sub :upload/busy?]
               :role :selector}]
             (vec sel))
          "the has-tag? selector edge runs from the named machine only"))))

(deftest selector-targeting-on-machine-selector-targets-fn
  (testing "machine-selector-targets returns the set of machine ids a selector reads"
    (rf/reg-sub :upload/progress
                :<- [:rf/machine :upload/main]
                (fn [snapshot _] snapshot))
    (rf/reg-sub :plain/sub (fn [db _] db))
    (is (= #{:upload/main} (machines-tooling/machine-selector-targets :upload/progress))
        "the target machine id is extracted")
    (is (= #{} (machines-tooling/machine-selector-targets :plain/sub))
        "a non-selector sub has no targets")
    (is (= #{} (machines-tooling/machine-selector-targets :nope/unregistered))
        "an unregistered sub has no targets")))

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
    (rf/reg-route :route/article {} "/articles/:slug")
    ;; Drive a navigation so the route slice is materialized in runtime-db.
    (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "welcome"}])
    (let [g     (graph/live-derivation-graph :rf/default all-contributors)
          slice (get (:nodes g) :rf/route)]
      (is (some? slice) "the live route slice node is present, keyed by :rf/route")
      (is (= :route/article (:route-id slice)) "the live matched route id")
      (is (= {:slug "welcome"} (:params slice)) "the live matched params"))))

;; ---- live graph: realized route-owned resource edges (rf2-k0meap.1) -------
;;
;; The composer's live route→resource edge derivation is exercised through
;; the PUBLIC `live-derivation-graph` over CUSTOM contributors that return
;; the realized shapes a navigation-then-fetch produces — the live route
;; slice with its `[:route route-id nav-token]` owner, and a concrete
;; scoped-key resource entry whose `:lifecycle :owners` carries that SAME
;; route owner. Driving these through the real edge-extraction path (not a
;; private fn) pins that the static graph's `:parametric` route-resource
;; marker resolves to a concrete edge in the live graph.

(def ^:private nav-token-fixture 42)
(def ^:private scoped-key-fixture
  [[:rf.scope/global] :article/by-slug {:slug "welcome"}])

(defn- live-route-node-fixture []
  {:id         :rf/route
   :kind       :process
   :refinement :route-fact
   :route-id   :route/article
   :params     {:slug "welcome"}
   :nav-token  nav-token-fixture
   :owner      [:route :route/article nav-token-fixture]
   :output     [:runtime [:rf.runtime/routing :current]]
   :storage    :runtime-db :evaluation :on-route :lifecycle :frame})

(defn- live-resource-node-fixture [owners]
  {:id         scoped-key-fixture
   :kind       :process
   :refinement :resource-process
   :inputs     [[:scope [:rf.scope/global]] [:param {:slug "welcome"}]]
   :output     [:runtime [:rf.runtime/resources :entries scoped-key-fixture]]
   :storage    :runtime-db
   :authority  {:kind :remote :system :server}
   :evaluation #{:on-route}
   :lifecycle  {:kind :scoped-resource-key :owners owners}
   :status     :loaded})

(defn- fixture-live-contributors
  "A contributor map whose route + resource live-fns return the supplied
  fixture nodes (and whose other families contribute nothing), so the
  composer's live edge derivation runs over realized shapes."
  [route-node resource-node]
  {:routes    {:static-fn (constantly {}) :live-shape :node
               :live-fn   (constantly route-node)}
   :resources {:static-fn (constantly {}) :live-shape :map
               :live-fn   (constantly (when resource-node
                                        {scoped-key-fixture resource-node}))}})

(deftest live-graph-draws-realized-route-owned-resource-edge
  (testing "a route-owned resource entry (owner [:route route-id nav-token])
            yields a :param edge from the live route node to the CONCRETE
            [:resource <scoped-key>] node — the live resolution of the
            static graph's :parametric route-resource marker (rf2-k0meap.1)"
    (let [route-node (live-route-node-fixture)
          res-node   (live-resource-node-fixture
                       #{[:route :route/article nav-token-fixture]})
          g          (graph/live-derivation-graph
                       :rf/default
                       (fixture-live-contributors route-node res-node))
          res-id     [:resource scoped-key-fixture]]
      (is (contains? (:nodes g) :rf/route) "the live route node is present")
      (is (contains? (:nodes g) res-id) "the concrete scoped-key resource node is present")
      (is (some #(= % {:from  :rf/route
                       :to    res-id
                       :role  :param
                       :owner [:route :route/article nav-token-fixture]})
                (:edges g))
          "the realized route→concrete-resource :param edge targets the concrete scoped key")))

  (testing "a NON-route owner (e.g. a :rf.scope/global activation owner)
            draws NO route-resource edge — only realized route owners do"
    (let [route-node (live-route-node-fixture)
          ;; owner is NOT a [:route …] shape → no route edge.
          res-node   (live-resource-node-fixture #{[:component :some/widget]})
          g          (graph/live-derivation-graph
                       :rf/default
                       (fixture-live-contributors route-node res-node))]
      (is (not-any? #(and (= :param (:role %))
                          (= [:resource scoped-key-fixture] (:to %)))
                    (:edges g))
          "no route-resource edge for a non-route owner")))

  (testing "an owner whose nav-token does NOT match any live route node
            draws no edge (a stale / superseded owner)"
    (let [route-node (live-route-node-fixture)        ;; nav-token 42
          res-node   (live-resource-node-fixture
                       #{[:route :route/article 999]}) ;; stale token
          g          (graph/live-derivation-graph
                       :rf/default
                       (fixture-live-contributors route-node res-node))]
      (is (not-any? #(and (= :param (:role %))
                          (= [:resource scoped-key-fixture] (:to %)))
                    (:edges g))
          "no edge when the resource owner's route nav-token matches no live route")))

  (testing "the STATIC graph draws NO realized route-resource edge — realized
            owners are live-only"
    (let [g (graph/derivation-graph
              (fixture-live-contributors (live-route-node-fixture)
                                         (live-resource-node-fixture
                                           #{[:route :route/article nav-token-fixture]})))]
      ;; static-fns return {} so there are no nodes; the realized-edge derivation
      ;; is also gated on :live mode regardless.
      (is (not-any? #(= :param (:role %)) (:edges g))
          "the static graph emits no realized route-resource edge"))))

;; ---- optional-contributor loading (the no-flows / no-resources story) -----
;;
;; `resolve-var` tolerates a genuinely-absent optional family namespace (a
;; FileNotFoundException → nil), the EXPECTED-absence a core-only / no-flows
;; app produces. A present namespace resolves to its var; an init/compile
;; failure of a present namespace propagates (not tested here — it requires a
;; deliberately-broken sibling on the classpath).

(deftest resolve-var-tolerates-a-genuinely-absent-namespace
  (testing "a symbol whose NAMESPACE is not on the classpath is EXPECTED
            absence — resolve-var returns nil (the no-flows story)"
    (is (nil? (#'graph/resolve-var 'totally.absent.optional.sibling/some-view))
        "an un-loaded optional family namespace is tolerated as absent (nil)"))
  (testing "a DASHED absent namespace is tolerated too"
    (is (nil? (#'graph/resolve-var 're-frame.totally-absent.optional-sibling/some-view))
        "a dashed un-loaded optional family namespace is tolerated as absent (nil)")))

(deftest resolve-var-resolves-a-present-var
  (testing "a present namespace + present var resolves to the var (the happy path)"
    (is (var? (#'graph/resolve-var 'clojure.string/upper-case))
        "an existing tooling var resolves")))

(deftest resolve-sibling-yields-nil-for-an-absent-family
  (testing "an absent optional sibling resolves to nil (the family contributes
            nothing) — the no-flows / no-resources story"
    (is (nil? (#'graph/resolve-sibling 'totally.absent.sibling/static-view
                                       'totally.absent.sibling/live-view
                                       :map))
        "a genuinely-absent family yields nil (tolerated)")))

(deftest default-contributors-wires-the-machine-selector-targets-surface
  (testing "the JVM default contributors wire the machine selector-target
            extractor (EP-0014 machine-selector refinement reads it) — present
            and callable, not dropped by the narrowed resolver (rf2-k0meap.8)"
    (let [c (graph/default-contributors)]
      (is (fn? (get-in c [:machines :selector-targets]))
          "the :machines contributor carries a callable :selector-targets extractor")
      ;; and the assembled default-contributors graph still draws the precise
      ;; selector edge — the wiring is live end-to-end.
      (rf/reg-machine :upload/main
                      {:initial :idle :data {} :states {:idle {}}})
      (rf/reg-sub :upload/progress
                  :<- [:rf/machine :upload/main]
                  (fn [snapshot _] snapshot))
      (let [g   (graph/derivation-graph)            ;; zero-arg → default-contributors
            sel (filter #(= :selector (:role %)) (:edges g))]
        (is (some #(= % {:from [:machine :upload/main]
                         :to   [:sub :upload/progress]
                         :role :selector})
                  sel)
            "the default-contributor graph draws the precise machine→selector edge")))))

;; ---- fixed family set: only the five in-tree families compose -------------
;;
;; The graph composes EXACTLY the five in-tree families (subs / flows /
;; resources / routes / machines), iterating `graph/families` filtered to the
;; ones a contributor map carries. A contributor key OUTSIDE that fixed set
;; contributes nothing — there is no synthetic-family extension path
;; (pre-alpha posture: a sixth family is a spec/code change here).

(deftest a-family-outside-the-fixed-five-contributes-no-nodes
  (testing "a contributor key the central `families` vector does not list is
            simply ignored — only the fixed five families compose"
    (rf/reg-sub :cart/items (fn [db _] (get-in db [:cart :items])))
    (let [contributors {:subs    (:subs all-contributors)
                        ;; :widgets is not one of the five — no contract entry,
                        ;; not in `families` → never iterated, never composed.
                        :widgets {:static-fn  (constantly {:w/a {:id :w/a :kind :process}})
                                  :live-shape :map
                                  :live-fn    (constantly {})}}
          g     (graph/derivation-graph contributors)
          nodes (:nodes g)]
      (is (contains? nodes [:sub :cart/items])
          "the in-tree :subs family composes")
      (is (= #{:subs} (->> nodes vals (map :rf/family) set))
          "no node from the off-set :widgets family appears")
      (is (not (contains? @#'graph/family-contract :widgets))
          "core has no contract entry for a family outside the fixed five"))))
