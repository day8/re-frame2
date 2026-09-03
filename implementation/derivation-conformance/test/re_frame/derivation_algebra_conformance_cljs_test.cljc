(ns re-frame.derivation-algebra-conformance-cljs-test
  "Cross-family conformance for the derivation/process algebra in
  `spec/Derivations.md`.

  The family suites validate their own projections. This suite registers or
  supplies subscriptions, flows, resources, route facts, and machines, then
  checks their assembled static and live graphs. Its sections cover:

  - lowering to the closed `:derivation` / `:process` superkinds;
  - storage, evaluation, lifecycle, and authority classification;
  - static and realized `:input`, `:param`, and `:selector` edges;
  - whole-value semantics and the optional-delta boundary;
  - lifecycle release and on-demand reads that do not write durable state;
  - graph egress redaction without loss of identity or connectivity.

  The suite sits outside any one family because it consumes bundle-isolated
  contributors from core, flows, resources, routing, and machines. It does not
  depend on `tools/`. The same `.cljc` tests run in the shared CLJS node gate
  and through this artefact's JVM `:test` alias."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.set :as set]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.frame :as frame]
            [re-frame.derivation.graph :as graph]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            ;; The graph-wide egress projection lives in core so conformance
            ;; can test the same implementation used by tool consumers without
            ;; introducing a dependency on `tools/`.
            [re-frame.derivation.egress :as egress]
            [re-frame.privacy :as privacy]
            [re-frame.elision :as elision]
            ;; Load-bearing requires: each facade installs its framework
            ;; registrations before the graph contributors are exercised.
            [re-frame.routing]
            [re-frame.routing.tooling :as routing-tooling]
            [re-frame.resources]
            [re-frame.resources.tooling :as resources-tooling]
            [re-frame.resources.state :as resources-state]
            [re-frame.resources.work-ledger :as work-ledger]
            [re-frame.machines.tooling :as machines-tooling]
            [re-frame.machines.paths :as machine-paths]
            ;; The CLJS lifecycle arm drives the cache through the internal
            ;; subscribe/unsubscribe operations.
            [re-frame.subs :as subs]
            [re-frame.subs.tooling :as subs-tooling]
            [re-frame.flows.tooling :as flows-tooling]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; Closed vocabularies from `spec/Derivations.md` and `spec/Spec-Schemas.md`.
;; ---------------------------------------------------------------------------

(def superkinds
  "The closed two-superkind enum (Derivations §The node shape). A tool that
  understands only these can classify every node."
  #{:derivation :process})

(def refinements
  "The informative refinements and the canonical superkind each refines."
  {:resource-process :process
   :route-fact       :process
   :machine-process  :process
   :machine-selector :derivation})

;; Check `:kind` directly rather than reducing refinements to superkinds; doing
;; so would accept a refined value in the closed `:kind` slot.

(def storage-classes
  "The closed local storage classes. External authority is a separate axis."
  #{:ephemeral :app-db :runtime-db :host-transient})

(def evaluation-policies
  "The closed evaluation-policy set (Derivations §Evaluation policy)."
  #{:on-demand :after-event :on-reply :on-route :on-transition :scheduled :manual})

(def lifecycles
  "The closed lifecycle set (Derivations §Lifecycle and owner)."
  #{:subscription-cache-entry :frame :route :scoped-resource-key :machine-instance :host-root})

;; ---------------------------------------------------------------------------
;; Runtime fixture.
;; ---------------------------------------------------------------------------

;; All CLJS tests share one process-global runtime. This fixture restores the
;; registrar and adapter after each test so this suite cannot leak its
;; plain-atom substrate into later React-backed suites. `:init-fn` reloads the
;; optional family registrations after the baseline has been restored.
(defn- refresh-families! []
  (flows/reset-flows!)
  (flows/reset-last-inputs!)
  (schemas/clear-schemas-by-frame!)
  #?(:clj (do (require 're-frame.routing :reload)
              (require 're-frame.resources :reload)
              (require 're-frame.machines :reload)))
  (frame/ensure-default-frame!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn refresh-families!}))

;; ---------------------------------------------------------------------------
;; Use an explicit contributor map on both runtimes so this suite does not
;; depend on JVM-only contributor discovery.
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; The subscription and flow share one whole-value function and differ only in
;; storage, evaluation, lifecycle, and output policy.
;; ---------------------------------------------------------------------------

(defn sum-cart
  "The shared whole-value derivation function — total quantity across cart
  items. A pure fn of the declared inputs; the subscription and the flow
  carry it under `:derive` (an opaque token — Derivations §The node shape)."
  [items]
  (reduce + 0 (map :qty items)))

(def cart-items
  [{:sku "a" :qty 2} {:sku "b" :qty 3} {:sku "c" :qty 5}])

;; ---------------------------------------------------------------------------
;; Register one source form of each family into the default frame. The graph
;; the composer assembles over these is the conformance subject.
;; ---------------------------------------------------------------------------

(defn- register-one-of-each! []
  ;; Registered per test because the fixture restores the load-time baseline.
  (rf/reg-event ::seed-cart
                   (fn [{:keys [db]} [_ items]]
                     {:db (assoc-in db [:cart :items] items)}))
  ;; :subs — a layer-1 `:db` reader, a static `:<-` derivation over it (the
  ;; :input edge source carrying the shared sum-cart fn), a parametric
  ;; input-fn sub (the don't-execute / parametric-marker subject), and a
  ;; machine selector (the :selector edge target).
  (rf/reg-sub :cart/items (fn [db _] (get-in db [:cart :items])))
  (rf/reg-sub :cart/total
              :<- [:cart/items]
              (fn [items _] (sum-cart items)))
  (rf/reg-sub :article/page
              (fn [[_ slug]] [[:article/by-slug slug] [:comments/for-article slug]])
              (fn [[a c] _] {:article a :comments c}))
  (rf/reg-sub :upload/progress
              :<- [:rf/machine :upload/main]
              (fn [snapshot _] (get-in snapshot [:data :progress] 0)))
  ;; :flows — the subscription's policy TWIN: same sum-cart, materialized
  ;; into app-db `:after-event`.
  (rf/reg-flow :cart/materialized-total {:inputs [[:cart :items]] :output-path [:cart :total]} sum-cart)
  ;; :resources — a process node (runtime-db / external authority); declares
  ;; a route-owned resource so the route gets a :param activation edge.
  (rf/reg-resource :article/by-slug
                   {:scope         :rf.scope/global
                    :params-schema [:map [:slug :string]]}
                   (fn [{:keys [slug]} _ctx]
                     {:request {:method :get
                                :url    (str "/api/articles/" slug)}}))
  ;; :routes — a route fact (runtime-db / on-route), owning the resource.
  (rf/reg-route :route/article
                {:resources [{:resource :article/by-slug :blocking? true}]} "/articles/:slug")
  ;; :machines — a process node (runtime-db / on-transition) with an `:after`
  ;; timer (→ `:scheduled` in the policy set).
  (rf/reg-machine :upload/main
                  {:initial :idle
                   :data    {:progress 0}
                   :states  {:idle      {:on {:upload/start {:target :uploading}}}
                             :uploading {:after {1000 {:target :idle}}
                                         :on    {:upload/done {:target :idle}}}}}))

(defn- node-by-family
  "Pick the assembled-graph node whose `:rf/family` is `family` and whose
  `:source-form :id` is `source-id` — the canonical way to find one family's
  node without hardcoding its tagged node-id form."
  [nodes family source-id]
  (->> nodes vals
       (filter (fn [n] (and (= family (:rf/family n))
                            (= source-id (get-in n [:source-form :id])))))
       first))

;; ===========================================================================
;; (a) LOWERING — each source form lowers to the correct node kind/superkind.
;; ===========================================================================

(deftest a-every-source-form-lowers-to-a-node-of-the-right-superkind
  (register-one-of-each!)
  (let [nodes (:nodes (graph/derivation-graph all-contributors))]
    (testing "all five families lowered into the assembled graph"
      (is (= #{:subs :flows :resources :routes :machines}
             (->> nodes vals (map :rf/family) set))
          "every source form lowered to at least one node"))
    (testing "every node's :kind is directly in the closed superkind enum"
      (doseq [[node-id node] nodes]
        (is (contains? superkinds (:kind node))
            (str node-id " :kind " (:kind node)
                  " is not in the closed DerivationKind enum "
                 (pr-str superkinds)
                 " — refined kinds must ride :refinement, never :kind"))))
    (testing "every refinement agrees with the canonical superkind in :kind"
      (doseq [[node-id node] nodes
              :let [refinement (:refinement node)]
              :when (some? refinement)]
        (is (contains? refinements refinement)
            (str node-id " :refinement " refinement " is not a known refinement"))
        (is (= (:kind node) (get refinements refinement))
            (str node-id " :refinement " refinement " refines "
                 (get refinements refinement) " but :kind asserts " (:kind node)))))
    (testing "subscriptions and flows are derivations"
      (is (= :derivation (:kind (node-by-family nodes :subs :cart/total))))
      (is (= :derivation (:kind (node-by-family nodes :subs :cart/items))))
      (is (= :derivation (:kind (node-by-family nodes :flows :cart/materialized-total)))))
    (testing "resources, routes, and machines are processes with family refinements"
      (let [res   (node-by-family nodes :resources :article/by-slug)
            route (node-by-family nodes :routes    :route/article)
            mach  (node-by-family nodes :machines  :upload/main)]
        (is (= :process (:kind res)))
        (is (= :process (:kind route)))
        (is (= :process (:kind mach)))
        (is (= :resource-process (:refinement res)))
        (is (= :route-fact       (:refinement route)))
        (is (= :machine-process  (:refinement mach)))))
    (testing "a machine-selector sub is labelled independently of its selector edge"
      (let [sel (node-by-family nodes :subs :upload/progress)]
        (is (some? sel) "the machine-selector sub node is present")
        (is (= :machine-selector (:refinement sel))
            "the :upload/progress selector sub is enriched with :refinement :machine-selector")
        (is (= :derivation (:kind sel))
            "the machine-selector refinement rides the :derivation superkind, never replaces it")))
    (testing "the route fact uses :rf/route while :source-form keeps the registration id"
      (let [route (node-by-family nodes :routes :route/article)]
        (is (= :rf/route (:id route)))
        (is (= :route-fact (:refinement route)))
        (is (= {:kind :reg-route :id :route/article} (:source-form route)))))
    (testing "each node records the source form it lowered from"
      (is (= :reg-sub      (get-in (node-by-family nodes :subs :cart/total)        [:source-form :kind])))
      (is (= :reg-flow     (get-in (node-by-family nodes :flows :cart/materialized-total) [:source-form :kind])))
      (is (= :reg-resource (get-in (node-by-family nodes :resources :article/by-slug)  [:source-form :kind])))
      (is (= :reg-route    (get-in (node-by-family nodes :routes :route/article)     [:source-form :kind])))
      (is (= :reg-machine  (get-in (node-by-family nodes :machines :upload/main)     [:source-form :kind]))))))

;; ===========================================================================
;; (b) CLASSIFICATION — storage / evaluation / lifecycle per family, against
;;     the spec/Derivations.md fixed-classification tables.
;; ===========================================================================

(deftest b-storage-evaluation-lifecycle-classified-per-family
  (register-one-of-each!)
  (let [nodes (:nodes (graph/derivation-graph all-contributors))
        sub   (node-by-family nodes :subs      :cart/total)
        flow  (node-by-family nodes :flows     :cart/materialized-total)
        res   (node-by-family nodes :resources :article/by-slug)
        route (node-by-family nodes :routes    :route/article)
        mach  (node-by-family nodes :machines  :upload/main)]
    (testing "subscription — ephemeral, on-demand, cache-entry"
      (is (= :ephemeral                (:storage sub)))
      (is (= :on-demand                (:evaluation sub)))
      (is (= :subscription-cache-entry (:lifecycle sub)))
      (is (false? (:materialized? sub))))
    (testing "flow — materialized app-db, after-event, frame"
      (is (= :app-db      (:storage flow)))
      (is (= :after-event (:evaluation flow)))
      (is (= :frame       (:lifecycle flow)))
      (is (true? (:materialized? flow)))
      (is (= [:db [:cart :total]] (:output flow)) "materialized output address"))
    (testing "resource — runtime-db local storage and external authority"
      (is (= :runtime-db    (:storage res)) "storage names the local home")
      (is (= :scoped-resource-key  (:lifecycle res)))
      (is (= #{:on-route :on-reply :scheduled :manual} (:evaluation res))
          "a multi-trigger process carries a policy set")
      (is (= :remote (get-in res [:authority :kind]))
          "remote authority is separate from local storage")
      (is (true? (:materialized? res))))
    (testing "route — runtime-db / on-route / frame"
      (is (= :runtime-db (:storage route)))
      (is (= :on-route   (:evaluation route)))
      (is (= :frame      (:lifecycle route)))
      (is (= [:runtime [:rf.runtime/routing :current]] (:output route))))
    (testing "machine — runtime-db / on-transition (+scheduled for :after) / machine-instance"
      (is (= :runtime-db        (:storage mach)))
      (is (= :machine-instance  (:lifecycle mach)))
      ;; Exact equality catches valid but spurious policies that closed-set
      ;; membership checks cannot detect.
      (is (= #{:on-transition :scheduled} (:evaluation mach))
          "a non-spawning machine with an :after timer has no extra policy")
      (is (true? (:materialized? mach))))))

(deftest b-every-classification-is-in-its-closed-vocabulary
  (register-one-of-each!)
  (let [nodes (:nodes (graph/derivation-graph all-contributors))]
    (testing "every classification is in its closed set"
      (doseq [[node-id node] nodes]
        ;; Storage is one local class; authority is checked separately.
        (is (contains? storage-classes (:storage node))
            (str node-id " :storage " (:storage node) " is not a closed storage class"))
        (is (not= :remote (:storage node))
            (str node-id " uses :remote as a storage class — external authority is the :authority axis"))
        ;; Evaluation is one policy or a set of policies.
        (let [ev (:evaluation node)]
          (doseq [p (if (set? ev) ev #{ev})]
            (is (contains? evaluation-policies p)
                (str node-id " :evaluation member " p " is not a closed policy"))))
        ;; Live nodes may enrich the lifecycle keyword with ownership data.
        (let [lc (:lifecycle node)
              lk (if (map? lc) (:kind lc) lc)]
          (is (contains? lifecycles lk)
              (str node-id " :lifecycle " lk " is not a closed lifecycle")))))))

(deftest b-external-authority-names-its-local-storage-separately
  ;; Authority names the source of truth; storage still names the local home.
  (register-one-of-each!)
  (let [nodes (:nodes (graph/derivation-graph all-contributors))
        res   (node-by-family nodes :resources :article/by-slug)]
    (is (= :remote     (get-in res [:authority :kind])))
    (is (= :runtime-db (:storage res))
        "a remote-authority node still names a local storage class")
    (is (contains? storage-classes (:storage res)))
    (is (= :rf.http/managed (get-in res [:authority :transport]))
        "the transport mirrors the registered Spec 016 transport (a projection)")))

;; ===========================================================================
;; (c) GRAPH EDGES — :input / :param / :selector in static + live graphs.
;; ===========================================================================

(deftest c-static-edges-span-input-param-selector
  (register-one-of-each!)
  (let [edges (:edges (graph/derivation-graph all-contributors))
        roles (->> edges (map :role) set)]
    (testing "an :input edge follows the static `:<-` chain (cart/items → cart/total)"
      (is (some #(= % {:from [:sub :cart/items] :to [:sub :cart/total] :role :input})
                edges)
          "the static :<- input edge"))
    (testing "a :param edge follows route-owned resource activation (route → resource)"
      (is (some #(and (= [:rf/route :route/article] (:from %))
                      (= [:resource :article/by-slug] (:to %))
                      (= :param (:role %)))
                edges)
          "the route → resource activation edge, :param role"))
    (testing "a :selector edge follows the machine → its selector sub"
      (is (some #(= % {:from [:machine :upload/main]
                       :to   [:sub :upload/progress]
                       :role :selector})
                edges)
          "the machine process → selector subscription edge"))
    (testing "all three edge roles present"
      (is (= #{:input :param :selector} (set/intersection roles #{:input :param :selector}))))))

(deftest c-parametric-sub-contributes-no-static-edge-dont-execute
  ;; Derivations §The don't-execute rule — static inspection never runs an
  ;; input-fn, so a parametric sub reports the :parametric marker and
  ;; contributes no static :input edge. Its realized edges are live-only.
  (register-one-of-each!)
  (let [g    (graph/derivation-graph all-contributors)
        node (get (:nodes g) [:sub :article/page])]
    (is (some? node) "the parametric sub node is present")
    (is (= :parametric (:inputs node)) "its declared inputs are the :parametric marker")
    (is (not-any? #(= [:sub :article/page] (:to %)) (:edges g))
        "no static :input edge points at the parametric sub (don't-execute rule)")))

(deftest c-named-resolver-scope-input-appears-statically-through-the-composer
  ;; The family suite checks this enrichment before composition. This arm
  ;; checks that the composer preserves the resolver reference and its declared
  ;; db inputs. A throwing resolver makes accidental execution fail immediately.
  (register-one-of-each!)
  (rf/reg-resource-scope :conf/tenant
                         {:inputs {:tenant-id [:db [:session :tenant-id]]}}
                         (fn [_inputs _ctx]
                           (throw (ex-info "a named-resolver fn must not run during static graph inspection" {}))))
  (rf/reg-resource :tenant/feed
                   {:scope         {:from-db :conf/tenant}
                    :params-schema [:map [:page :int]]}
                   (fn [{:keys [page]} _ctx]
                     {:request {:method :get :url "/api/feed" :params {:page page}}}))
  (let [nodes (:nodes (graph/derivation-graph all-contributors))
        res   (node-by-family nodes :resources :tenant/feed)]
    (is (some? res) "the {:from-db} resource composed into the umbrella cross-family graph")
    (testing "the named-resolver reference remains a static scope input"
      (is (= [[:param :rf.params] [:scope {:from-db :conf/tenant}]]
             (:inputs res))
          "the composed node preserves the {:from-db <id>} reference"))
    (testing "the composer preserves the resolver id and declared db inputs"
      (is (= :conf/tenant (get-in res [:scope-resolver :id]))
          "the composed node names the referenced resolver id")
      (is (= [[:db [:session :tenant-id]]] (get-in res [:scope-resolver :inputs]))
          "the resolver inputs remain static metadata"))))

(deftest c-live-graph-has-the-mode-frame-shape-and-realizes-the-route-slice
  ;; The static / live split (Derivations §Static and live graphs): the live
  ;; graph carries `:mode :live` + `:frame`, and reports realized nodes (the
  ;; materialized route slice) the static graph cannot know — the concrete
  ;; matched route id, its params, and the route owner (nav-token), facts
  ;; that exist only after a navigation commits to runtime-db.
  (register-one-of-each!)
  (let [g0 (graph/live-derivation-graph :rf/default all-contributors)]
    (testing "the live graph always carries the :mode :live + :frame shape"
      (is (= :live (:mode g0)))
      (is (= :rf/default (:frame g0)))
      (is (map? (:nodes g0)))
      (is (vector? (:edges g0)))))
  ;; Drive a navigation so the route slice is materialized in runtime-db.
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "welcome"}}])
  (let [g     (graph/live-derivation-graph :rf/default all-contributors)
        slice (get (:nodes g) :rf/route)]
    ;; Assert setup explicitly so the remaining checks cannot pass vacuously.
    (testing "the navigation materialized the live route slice"
      (is (some? slice)
          "the navigation must materialize the :rf/route live node — absence is a failure"))
    (testing "the live route slice node is realized, keyed by :rf/route"
      (is (= :route/article (:route-id slice)) "the live matched route id")
      (is (= {:slug "welcome"} (:params slice)) "the live realized params")
      ;; The live view preserves the static classification.
      (is (= :runtime-db (:storage slice)))
      (is (= :on-route   (:evaluation slice)))
      (is (= :frame      (:lifecycle slice)))
      ;; Owner identity is the route id paired with the navigation token.
      (is (some? (:owner slice))
          "the navigation must allocate a route owner — absence is a failure")
      (is (= [:route :route/article (:nav-token slice)] (:owner slice))
          "the live route owner is [:route route-id nav-token]"))))

;; ---------------------------------------------------------------------------
;; (c+) Non-route realized composition.
;;
;; Synthetic contributors isolate the composer's deterministic wrapping and
;; edge derivation from the family runtimes. The fixtures represent concrete
;; sub-cache, resource-cache, and route facts that cannot exist in a static
;; graph.

(def ^:private live-composition-nav-token 7)
(def ^:private live-composition-scoped-key
  ;; [cache-scope resource-id canonical-params] — a concrete live fact id.
  [[:rf.scope/global] :article/by-slug {:slug "a1"}])

;; A resource work-id embeds both the scoped key and attempt generation. Using
;; the canonical tuple lets the fixture exercise every identity-bearing slot.
(def ^:private live-composition-generation 3)
(def ^:private live-composition-work-id
  ;; `[:rf.work/resource <scoped-key> <generation>]`.
  [:rf.work/resource live-composition-scoped-key live-composition-generation])

(defn- live-composition-contributors
  "Contributors whose subs / resources / routes live-fns return the realized
  shapes a `[:article/page \"a1\"]` materialization + a route-owned fetch
  produce, so the composer's live node-wrapping + edge derivation runs over
  concrete facts."
  []
   {;; A live sub-cache projection: a realized parametric sub keyed by its
   ;; concrete query vector, declaring a concrete `[:sub q]` upstream input —
   ;; the realized edge a parametric sub cannot enumerate statically.
   :subs
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
   ;; A live resource cache entry, route-owned, with lifecycle + work-ledger.
   :resources
   {:live-shape :map
    :static-fn  (constantly {})
    :live-fn    (constantly
                  {live-composition-scoped-key
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
                    ;; Match the canonical resource attempt identity and a
                    ;; non-terminal status emitted by the work ledger.
                    :work-ledger {:work/id live-composition-work-id
                                  :record  {:work/id live-composition-work-id :status :running
                                            :resource/key live-composition-scoped-key}}}})}
   ;; The live route slice with its realized owner.
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
  (let [g     (graph/live-derivation-graph :rf/default (live-composition-contributors))
        nodes (:nodes g)
        edges (:edges g)]
    (testing "realized sub nodes are wrapped by their concrete query vector
              (the canonical live `[:sub q]` id — not the static bare-id form)"
      (is (contains? nodes [:sub [:article/page "a1"]])
          "the realized parametric sub is keyed by its concrete query vector")
      (is (contains? nodes [:sub [:article/by-slug "a1"]])
          "its concrete upstream sub is a node too"))
    (testing "the realized :input edge a parametric sub cannot enumerate
              statically is present in the live graph (live `[:sub q]` input
              resolves to the concrete upstream node id)"
      (is (some #(= % {:from [:sub [:article/by-slug "a1"]]
                       :to   [:sub [:article/page "a1"]]
                       :role :input})
                edges)
          "the realized :input edge from the concrete upstream sub"))
    (testing "the composed resource node is keyed by its concrete scoped key,
              carrying its live lifecycle owners + work-ledger"
      (let [res (get nodes [:resource live-composition-scoped-key])]
        (is (some? res) "the live resource node is present, scoped-key keyed")
        (is (= :process (:kind res)))
        (is (= #{[:route :route/article live-composition-nav-token]}
               (get-in res [:lifecycle :owners]))
            "the live owner set is composed through verbatim")
        ;; Separate witnesses make malformed work-id components diagnosable.
        (is (= live-composition-work-id (get-in res [:work-ledger :work/id]))
            "the composer preserves the canonical resource work-id")
        (is (= :rf.work/resource (first (get-in res [:work-ledger :work/id])))
            "the work-id tuple head is the :rf.work/resource family head (one-attempt-one-:work/id)")
        (is (= live-composition-scoped-key (second (get-in res [:work-ledger :work/id])))
            "the work-id tuple carries the concrete scoped-key")
        (is (= live-composition-generation (nth (get-in res [:work-ledger :work/id]) 2))
            "the work-id tuple embeds the attempt generation")
        (is (= live-composition-work-id (get-in res [:work-ledger :record :work/id]))
            "the work-ledger record carries the same canonical work-id tuple")
        (is (= :running (get-in res [:work-ledger :record :status]))
            "the record preserves the non-terminal issuance status")
        ;; Managed effects use the namespaced key at both levels.
        (is (not (contains? (:work-ledger res) :work-id))
            "the composed work-ledger uses :work/id")
        (is (not (contains? (get-in res [:work-ledger :record]) :work-id))
            "the work-ledger record uses :work/id")))
    (testing "the realized route-owned resource edge resolves to a concrete key"
      (is (some #(= % {:from  :rf/route
                       :to    [:resource live-composition-scoped-key]
                       :role  :param
                       :owner [:route :route/article live-composition-nav-token]})
                edges)
          "the live route → concrete resource :param edge"))))

;; ===========================================================================
;; (c++) DETERMINISTIC CANONICAL EDGE ORDER under registration/projection
;; permutation (rf2-3fc89f.1).
;;
;; [Derivations.md] §Graph inspection promises MECHANICAL, DETERMINISTIC
;; assembly. The composer's `:edges` is an explicitly vector-valued collection
;; that callers serialize / diff / hash / snapshot / display, so two logically
;; identical graphs — the SAME nodes and the SAME edges, assembled under
;; different projection / registration INSERTION orders — must produce the
;; SAME ordered `:edges` vector (and the same whole graph value). Before the
;; fix the composer inherited `nodes`-map iteration + each `:edge-fn`'s scan
;; order, so a mere insertion-order permutation emitted an edge PERMUTATION.
;;
;; The fix sorts the de-duplicated edge collection by
;; `re-frame.identity/canonical-bytes` of each COMPLETE edge map — a
;; platform-stable TOTAL key (identical on CLJ and CLJS, and order-insensitive
;; over each edge map's own keys, so it does not depend on nested-map SPELLING
;; the way `pr-str` would). These synthetic contributors isolate that ordering
;; from the family runtimes: two `:input` edges (subs `:b`,`:c` → `:a`, with
;; `:b` declaring its `[:sub [:a]]` input TWICE so `distinct` must collapse it
;; BEFORE canonicalization) and two family-owned `:param` edges (routes →
;; resource nodes keyed by a NESTED EDN map spelled two ways).
;; ===========================================================================

(defn- permutations-of
  "All orderings of `coll` (small n; hand-rolled so this tier needs no
  combinatorics dependency)."
  [coll]
  (if (<= (count coll) 1)
    (list (vec coll))
    (for [i    (range (count coll))
          tail (permutations-of (concat (take i coll) (drop (inc i) coll)))]
      (into [(nth coll i)] tail))))

(defn- permutation-sub-node [id inputs]
  {:id id :kind :derivation :inputs inputs
   :output [:fact id] :storage :ephemeral :evaluation :on-demand})

(defn- permutation-resource-key
  "A route's resource-key — a NESTED EDN map spelled `:slug`-first or
  `:locale`-first. Both spellings are the SAME identity; canonical ordering
  must not depend on which the projection happened to build."
  [slug slug-first?]
  (if slug-first? {:slug slug :locale :en} {:locale :en :slug slug}))

(defn- permutation-route [route-id slug slug-first?]
  {:id :rf/route :kind :process :refinement :route-fact
   :route-id route-id :storage :runtime-db :evaluation :on-route :lifecycle :frame
   :resource-edges [{:to [:resource (permutation-resource-key slug slug-first?)]
                     :role :param :target :parametric}]})

(defn- permutation-contributors
  "Synthetic STATIC contributors carrying the SAME nodes/edges but assembled
  in `sub-order` / `route-order` insertion order, with each route's nested
  resource-key map spelled per `slug-first?`."
  [sub-order route-order slug-first?]
  (let [subs   {:a (permutation-sub-node :a [])
                ;; `:b` declares its `[:sub [:a]]` input TWICE — `distinct`
                ;; must suppress the duplicate before canonicalization.
                :b (permutation-sub-node :b [[:sub [:a]] [:sub [:a]]])
                :c (permutation-sub-node :c [[:sub [:a]]])}
        routes {:r1 (permutation-route :r1 "s1" slug-first?)
                :r2 (permutation-route :r2 "s2" slug-first?)}]
    {:subs   {:live-shape :map
              :static-fn  (constantly
                            (reduce (fn [m k] (assoc m k (get subs k)))
                                    (array-map) sub-order))}
     :routes {:live-shape :node
              :static-fn  (constantly
                            (reduce (fn [m k] (assoc m k (get routes k)))
                                    (array-map) route-order))}}))

(def ^:private permutation-expected-edges
  "The canonical `:edges` vector — the byte-stable total order every
  permutation and both nested-map spellings must produce, on CLJ and CLJS
  alike. Pinned so a cross-platform divergence (or a regression to
  iteration-order output) fails the gate; the two `:param` edges sort before
  the two `:input` edges under the CEDN-1 key of each complete edge map."
  [{:from [:rf/route :r1] :to [:resource {:slug "s1" :locale :en}]
    :role :param :target :parametric}
   {:from [:rf/route :r2] :to [:resource {:slug "s2" :locale :en}]
    :role :param :target :parametric}
   {:from [:sub :a] :to [:sub :b] :role :input}
   {:from [:sub :a] :to [:sub :c] :role :input}])

(deftest cplusplus-edge-order-is-canonical-across-registration-permutations
  (let [baseline (graph/derivation-graph (permutation-contributors [:a :b :c] [:r1 :r2] true))]
    (testing "the de-duplicated edge SET is the four expected edges (the
              duplicated `[:sub [:a]]` input on `:b` collapsed to ONE edge —
              distinct runs BEFORE canonicalization)"
      (is (= (set permutation-expected-edges) (set (:edges baseline)))
          "same edge set as expected")
      (is (= 4 (count (:edges baseline)))
          "four distinct edges — the duplicate input was suppressed"))
    (testing "the `:edges` vector is the pinned canonical total order"
      (is (= permutation-expected-edges (:edges baseline))
          "edges emit in canonical-bytes order, not iteration order"))
    (testing "edge CONTENTS / identity are preserved — the sort reorders the
              collection, it does not rewrite edges (the `:param` edge keeps
              its nested resource key, `:role`, and `:target`)"
      (let [param (first (filter #(= :param (:role %)) (:edges baseline)))]
        (is (= [:resource {:locale :en :slug "s1"}] (:to param))
            "the nested resource-key EDN rides through verbatim")
        (is (= :parametric (:target param))
            "the static route-resource `:target` marker is preserved")
        (is (= [:rf/route :r1] (:from param))
            "the route node id is the re-targeted `:from`")))
    (testing "`:nodes` stays a MAP (order-insensitive under value equality)"
      (is (map? (:nodes baseline))))
    (testing "EVERY insertion-order permutation and BOTH nested-map spellings
              produce the identical `:edges` VALUE and the identical whole
              graph (registration/projection history is invisible)"
      (doseq [sub-order   (permutations-of [:a :b :c])
              route-order (permutations-of [:r1 :r2])
              slug-first? [true false]]
        (let [g (graph/derivation-graph
                  (permutation-contributors sub-order route-order slug-first?))]
          (is (= permutation-expected-edges (:edges g))
              (str "edges must equal the canonical order for sub-order "
                   sub-order " route-order " route-order
                   " slug-first? " slug-first?))
          (is (= baseline g)
              (str "the whole graph value must be permutation-invariant for "
                   sub-order " / " route-order " / " slug-first?)))))
    (testing "SERIALIZATION pin: for a fixed spelling, every insertion-order
              permutation serializes `:edges` byte-identically — the property
              tools depend on when they diff / hash / snapshot the graph"
      (doseq [slug-first? [true false]]
        (let [serials (for [sub-order   (permutations-of [:a :b :c])
                            route-order (permutations-of [:r1 :r2])]
                        (pr-str (:edges (graph/derivation-graph
                                          (permutation-contributors sub-order route-order slug-first?)))))]
          (is (= 1 (count (distinct serials)))
              (str "all permutations must serialize `:edges` identically (slug-first? "
                   slug-first? ")")))))))

;; ===========================================================================
;; (d) WHOLE-VALUE — the semantic whole-value law (slice-1).
;; ===========================================================================

(deftest d-materialized-flow-output-equals-the-whole-value-recompute
  ;; Derivations §The whole-value law: a materialized derivation's output
  ;; path holds the same whole value its derivation fn computes from the same
  ;; inputs. The flow materializes `(sum-cart items)` into [:cart :total];
  ;; after seeding :cart/items, the app-db path must equal the whole-value
  ;; recompute of the same function over the same inputs.
  (register-one-of-each!)
  ;; Seed the flow input. The flow runs :after-event (same-commit
  ;; materialization), so after this dispatch the output path is settled.
  (rf/dispatch-sync [::seed-cart cart-items])
  (let [app-db        (frame/frame-app-db-value :rf/default)
        materialized  (get-in app-db [:cart :total])
        whole-value   (sum-cart (get-in app-db [:cart :items]))]
    (is (= whole-value materialized)
        "the materialized flow output equals derive(inputs) — the whole-value law")
    (is (= (sum-cart cart-items) materialized)
        "and equals the whole-value recompute over the original inputs")))

(deftest d-flow-and-sub-differ-only-in-policy-not-in-the-whole-value
  ;; The subscription and flow express the same whole-value computation with
  ;; different storage, evaluation, lifecycle, and output policies.
  ;;
  ;; Verify the law by value, not function identity: a
  ;; subscription body that names `sum-cart` is wrapped by `reg-sub` into a
  ;; distinct `(fn [[items] _] (sum-cart items))` computation fn, so the two
  ;; `:derive` tokens are distinct objects through the registrar. The graph
  ;; treats those executable tokens as opaque.
  (register-one-of-each!)
  (let [nodes (:nodes (graph/derivation-graph all-contributors))
        sub   (node-by-family nodes :subs  :cart/total)
        flow  (node-by-family nodes :flows :cart/materialized-total)]
    (testing "both carry an opaque :derive whole-value token (never serialized)"
      (is (some? (:derive sub)))
      (is (some? (:derive flow))))
    (testing "they differ on every policy axis"
      (is (not= (:storage sub)    (:storage flow)) "ephemeral vs app-db")
      (is (not= (:evaluation sub) (:evaluation flow)) "on-demand vs after-event")
      (is (not= (:lifecycle sub)  (:lifecycle flow)) "cache-entry vs frame")
      (is (not= (:output sub)     (:output flow)) "[:fact …] vs [:db …]")
      (is (not= (:materialized? sub) (:materialized? flow)) "false vs true"))
    (testing "both remain derivations over the same whole value"
      (is (= :derivation (:kind sub) (:kind flow)))
      ;; the flow's :derive IS sum-cart; the subscription's wraps it — both
      ;; yield the identical whole value over the identical inputs.
      (is (= ((:derive flow) cart-items) (sum-cart cart-items))
          "the flow's whole-value fn equals the shared sum-cart"))
    ;; Read the subscription through the reactive path rather than invoking
    ;; only its opaque graph token.
    (testing "the ephemeral subscription read equals the whole-value recompute"
      (rf/dispatch-sync [::seed-cart cart-items])
      (is (= (sum-cart cart-items) @(rf/subscribe [:cart/total]))
          "reading @(rf/subscribe [:cart/total]) after seeding equals (sum-cart cart-items) — the ephemeral whole-value law"))))

(deftest d-delta-law-is-semantic-only-no-delta-support-still-conforms
  ;; The optional delta law applies only when an executable delta protocol is
  ;; present. The current implementation remains whole-value only.
  (register-one-of-each!)
  (let [nodes (:nodes (graph/derivation-graph all-contributors))]
    (doseq [[node-id node] nodes]
      (is (not (contains? node :step-delta))
          (str node-id " carries a :step-delta — slice-1 ships no delta protocol")))))

;; ===========================================================================
;; (e) LIFECYCLE — drive each release boundary and observe the corresponding
;;     node or owner leave the live graph. Subscription cache entries are
;;     CLJS-only because JVM cache reactions are not dereferenceable.
;; ===========================================================================

(deftest e-destroying-a-frame-releases-its-frame-owned-graph-nodes
  ;; Materialize a route slice and machine snapshot in a dedicated frame, then
  ;; verify that destroying the frame removes both from the observable graph.
  ;; Host handle teardown is owned and tested by the respective subsystems;
  ;; this arm covers the graph-node boundary.
  (register-one-of-each!)
  (rf/make-frame {:id :checkout/frame :doc "a frame to destroy"})
  ;; Materialize frame-owned facts: a committed route slice + a live singleton
  ;; machine snapshot, both in :checkout/frame.
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "welcome"}}]
                    {:frame :checkout/frame})
  (rf/dispatch-sync [:upload/main [:upload/start]] {:frame :checkout/frame})
  (let [g-before (graph/live-derivation-graph :checkout/frame all-contributors)
        nodes    (:nodes g-before)]
    ;; Assert setup before teardown so absence afterwards cannot pass vacuously.
    (testing "the setup dispatches materialized both frame-owned nodes"
      (is (contains? nodes :rf/route)
          "the navigation must materialize the :rf/route live node — absence is a failure")
      (is (contains? nodes [:machine :upload/main])
          "the machine start must materialize the [:machine :upload/main] live snapshot node"))
    (testing "the route slice (a :frame-lifecycle node) is owned by the frame"
      (let [slice (get nodes :rf/route)]
        (is (= :frame (:lifecycle slice)))
        (is (= [:route :route/article (:nav-token slice)] (:owner slice)))))
    (frame/destroy-frame! :checkout/frame)
    (let [g-after (graph/live-derivation-graph :checkout/frame all-contributors)]
      (testing "destroy-frame! releases every frame-owned graph node"
        (is (= :live (:mode g-after)) "the live graph shape survives a destroyed frame")
        (is (= {} (:nodes g-after))
            "no node survives the frame teardown — the route slice + machine snapshot are gone")
        (is (= [] (:edges g-after)) "and no edge survives")
        (is (nil? (get (:nodes g-after) :rf/route))
            "the route owner is released (its frame is gone)")
        (is (nil? (get (:nodes g-after) [:machine :upload/main]))
            "the machine snapshot node is released (its frame is gone)")))))

(deftest e-route-exit-supersession-releases-the-prior-route-owner
  ;; A second navigation replaces both the route id and its nav-token-based
  ;; owner identity.
  (register-one-of-each!)
  (rf/reg-route :route/about {} "/about")
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "welcome"}}])
  (let [g-a   (graph/live-derivation-graph :rf/default all-contributors)
        slice (get (:nodes g-a) :rf/route)]
    (testing "the first navigation materialized the route-A slice"
      (is (some? slice)
          "navigating to route A must materialize the :rf/route live node — absence is a failure"))
    (let [owner-a     (:owner slice)
          nav-token-a (:nav-token slice)]
      (testing "before supersession the live owner is route A under nav-token-A"
        (is (= :route/article (:route-id slice)))
        (is (= [:route :route/article nav-token-a] owner-a)))
      ;; Supersede: a second navigation commits a new slice.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/about}])
      (let [g-b      (graph/live-derivation-graph :rf/default all-contributors)
            slice-b  (get (:nodes g-b) :rf/route)]
        (testing "the superseding navigation materialized the route-B slice"
          (is (some? slice-b)
              "navigating to route B must materialize the superseding :rf/route live node — absence is a failure"))
        (testing "the superseding navigation releases the prior route owner"
          (is (= :route/about (:route-id slice-b))
              "the live slice now reports route B (the prior route fact is exited)")
          (is (not= owner-a (:owner slice-b))
              "the route-A owner identity is no longer the live owner — released by supersession")
          (is (not= nav-token-a (:nav-token slice-b))
              "a fresh nav-token owns the new route — the prior token's owner is gone")
          (is (= [:route :route/about (:nav-token slice-b)] (:owner slice-b))
              "the live owner is now [:route route-B fresh-nav-token]"))))))

(deftest e-route-owned-resource-release-propagates-through-the-assembled-live-graph
  ;; The CROSS-FAMILY release contract (Derivations §Route-owned resource
  ;; activation edges — the realized owner edge is LIVE state; §Lifecycle and
  ;; owner — route exit releases route owners). `e-route-exit-supersession…`
  ;; above grades release on the route `:rf/route` slice ALONE, and the family
  ;; runtime suite (`resources_route_cljs_test/route-leave-releases-prior-route-
  ;; owner`) grades it on the entry's runtime `:active-owners` field ALONE.
  ;; NEITHER exercises the resources tooling PROJECTION
  ;; (`resource-cache-algebra-view` → `[:lifecycle :owners]`) or the graph
  ;; COMPOSER's route→resource owner-edge derivation
  ;; (`re-frame.derivation.graph/route-edges`, live arm) through a REAL
  ;; route-owned resource release. A regression that left a stale owner in the
  ;; composed resource node's `[:lifecycle :owners]` (a broken
  ;; `resource-cache-algebra-view`) or a stale realized `:param` edge (a broken
  ;; composer) would keep BOTH those suites green while the live graph — the
  ;; surface inspection/tooling consumers read to answer "what keeps this
  ;; resource alive" — falsely reports the released route as a live owner.
  ;;
  ;; This arm drives the WHOLE assembled live graph through the real routing +
  ;; resources tooling contributors (`all-contributors`, not a synthetic graph
  ;; map): route A OWNS a route-owned resource; route B does not. Route A→B
  ;; supersession through the normal `:rf.route/navigate` hook (which dispatches
  ;; the real `:rf.resource/release-owner`) must release owner A from every
  ;; projected resource lifecycle owner set AND every composed graph edge, while
  ;; route B's fresh owner takes the slice.
  ;;
  ;; Cache-row construction (NOT the release): this artefact intentionally
  ;; carries NO HTTP transport artefact (deps.edn — the bundle-isolation
  ;; boundary), so the route-entry ensure has no live fetch to write the
  ;; `:loading` entry. As the g+ egress arm does, the row is built directly from
  ;; the canonical `resources.state` / `work-ledger` constructors — including
  ;; the reverse `:owner-index` the release handler consults. The route owner is
  ;; the GENUINE live nav-token owner minted by the real navigation, and the
  ;; RELEASE is driven entirely by the real supersession hook — never by editing
  ;; the post-state.
  (fx/reg-fx :rf.http/managed       (fn [_ctx _args] nil))
  (fx/reg-fx :rf.http/managed-abort (fn [_ctx _args] nil))
  (rf/reg-resource :article/by-slug
                   {:scope         :rf.scope/global
                    :params-schema [:map [:slug :string]]}
                   (fn [{:keys [slug]} _ctx]
                     {:request {:method :get :url (str "/api/articles/" slug)}}))
  ;; Route A OWNS the resource under its nav-token; route B owns nothing.
  (rf/reg-route :route/article
                {:params    [:map [:slug :string]]
                 :resources [{:resource :article/by-slug
                              :params   (fn [route] {:slug (get-in route [:params :slug])})}]}
                "/articles/:slug")
  (rf/reg-route :route/about {} "/about")
  ;; --- Navigate to route A: the real navigation mints the route owner. ---
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:slug "welcome"}}])
  (let [owner-a    (:owner (routing-tooling/route-slice-algebra-view :rf/default))
        scope      :rf.scope/global
        params     {:slug "welcome"}
        scoped-key (resources-state/scoped-resource-key scope :article/by-slug params)
        k-id       (resources-state/key-id scoped-key)
        work-id    (work-ledger/resource-work-id scoped-key 1)
        entry      (-> (resources-state/empty-entry :article/by-slug scoped-key)
                       (resources-state/attach-owner owner-a)
                       (assoc :status :fetching :current-work work-id))]
    (is (= [:route :route/article (:nav-token (routing-tooling/route-slice-algebra-view :rf/default))]
           owner-a)
        "the real navigation minted the route-A owner [:route route-A nav-token]")
    ;; Materialize the route-owned cache row with the canonical constructors
    ;; (resource entry + its reverse owner-index member + the in-flight work
    ;; record) — the shape a real route-owned fetch produces. This is test setup,
    ;; NOT the release under grade.
    (frame/swap-runtime-db!
      :rf/default
      (fn [rdb]
        (-> (or rdb {})
            (assoc-in (resources-state/entry-path scoped-key) entry)
            (assoc-in (conj (resources-state/owner-index-path) owner-a) #{k-id})
            (work-ledger/put-record
              work-id
              (work-ledger/work-record {:work-id      work-id
                                        :frame-id     :rf/default
                                        :resource/key scoped-key
                                        :generation   1
                                        :transport    :rf.http/managed
                                        :owner        owner-a
                                        :cause        :test/materialize})))))
    (let [entry-a       (get-in (frame/frame-runtime-db-value :rf/default)
                                (resources-state/entry-path scoped-key))
          g-a           (graph/live-derivation-graph :rf/default all-contributors)
          res-a         (->> (:nodes g-a)
                             (filter (fn [[_ n]] (= :resources (:rf/family n))))
                             first)
          param-edges-a (filterv #(= :param (:role %)) (:edges g-a))]
      ;; SETUP proof #1 — runtime state (the seam the family suite grades).
      (testing "the route-owned resource row is live under the route-A owner (runtime state)"
        (is (some? entry-a) "the cache entry is materialized")
        (is (contains? (:active-owners entry-a) owner-a)
            "the entry's runtime :active-owners carries the route-A owner"))
      ;; SETUP proof #2 — the ASSEMBLED LIVE GRAPH (the seam THIS arm grades).
      (testing "the assembled live graph exposes the route-owned resource node carrying owner A"
        (is (some? res-a)
            "the resources tooling contributor composed a live :resources node — absence is a failure")
        (let [[res-key res-node] res-a]
          (is (= :article/by-slug (nth (:id res-node) 1))
              "the registration resource-id is visible inside the composed scoped-key id")
          (is (contains? (get-in res-node [:lifecycle :owners]) owner-a)
              "owner A is present in the composed resource node's :lifecycle :owners")
          (testing "a realized :param edge carries owner A and joins the resource node key"
            (is (= 1 (count param-edges-a))
                "exactly one realized route-owned :param edge exists (owner A → the one resource)")
            (let [edge (first param-edges-a)]
              (is (= owner-a (:owner edge))
                  "the realized :param edge carries the route-A owner")
              (is (= :rf/route (:from edge))
                  "the edge originates at the live route slice node")
              (is (= res-key (:to edge))
                  "the edge :to matches the composed resource node key")))))
      ;; --- Release: supersede route A with route B through the NORMAL hook. ---
      ;; `commit-navigation`'s `:routing/on-route-entry` hook dispatches the real
      ;; `:rf.resource/release-owner {:owner owner-A}`, which drops owner A from
      ;; the entry + owner-index + work record — the genuine release path.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/about}])
      (let [entry-b        (get-in (frame/frame-runtime-db-value :rf/default)
                                   (resources-state/entry-path scoped-key))
            g-b            (graph/live-derivation-graph :rf/default all-contributors)
            slice-b        (get (:nodes g-b) :rf/route)
            owner-b        (:owner slice-b)
            res-owner-sets (->> (:nodes g-b) vals
                                (filter #(= :resources (:rf/family %)))
                                (keep #(get-in % [:lifecycle :owners])))
            edge-owners    (into #{} (keep :owner) (:edges g-b))
            param-edges-b  (filterv #(= :param (:role %)) (:edges g-b))]
        (testing "the superseding navigation materialized route B's fresh owner on the slice"
          (is (some? slice-b)
              "navigating to route B must materialize the superseding :rf/route slice — absence is a failure")
          (is (= :route/about (:route-id slice-b))
              "the live slice now reports route B (route A is exited)")
          (is (= [:route :route/about (:nav-token slice-b)] owner-b)
              "the live owner is now [:route route-B fresh-nav-token]")
          (is (not= owner-a owner-b)
              "route B's fresh owner is not route A's released owner"))
        (testing "release propagates through the assembled live graph — no stale owner A survives"
          (is (seq res-owner-sets)
              "the owner-free resource node REMAINS composed (release is not GC deletion),
               so the owner-absence check below is non-vacuous")
          (is (not-any? #(contains? % owner-a) res-owner-sets)
              "owner A is absent from EVERY composed resource node's :lifecycle :owners")
          (is (not (contains? edge-owners owner-a))
              "owner A is absent from EVERY composed graph edge's :owner")
          (is (empty? param-edges-b)
              "no realized route-owned :param edge survives — route B owns no resource, and route A's activation edge is released"))
        (testing "release is an owner release, NOT GC deletion — the entry may
                  legitimately remain cached but owner-free (the runtime seam agrees)"
          (is (not (contains? (:active-owners entry-b) owner-a))
              "the runtime entry no longer lists owner A among its :active-owners"))))))

(deftest e-machine-destroy-releases-the-machine-owned-snapshot-node
  ;; A final-state transition auto-destroys the machine instance. Observe the
  ;; snapshot before and after so the release assertion cannot pass vacuously.
  (register-one-of-each!)
  ;; A machine with a :final? terminal state (the singleton lifecycle's
  ;; release boundary). Distinct id from :upload/main so the existing arms are
  ;; untouched.
  (rf/reg-machine :job/runner
                  {:initial :running
                   :data    {}
                   :states  {:running {:on {:job/finish :done}}
                             :done    {:final? true}}})
  ;; Materialize the live singleton snapshot.
  (rf/dispatch-sync [:job/runner [:job/finish-noop]]) ;; no-op event: stays :running, installs snapshot
  ;; Prove the instance exists before driving its release boundary.
  (let [g-before    (graph/live-derivation-graph :rf/default all-contributors)
        node-before (get (:nodes g-before) [:machine :job/runner])
        rt-before   (frame/frame-runtime-db-value :rf/default)]
    (testing "the machine instance materialized a live snapshot before destroy"
      (is (some? node-before)
          "the no-op dispatch must materialize the [:machine :job/runner] live snapshot node — absence is a failure")
      (is (some? (get-in rt-before (machine-paths/snapshot-path :job/runner)))
          "the machine-owned snapshot must be live before the final event")))
  (rf/dispatch-sync [:job/runner [:job/finish]])      ;; → :done (:final?) → auto-destroy
  (let [g-after (graph/live-derivation-graph :rf/default all-contributors)
        node    (get (:nodes g-after) [:machine :job/runner])
        rt      (frame/frame-runtime-db-value :rf/default)]
    (testing "the machine instance node is released from the live graph on destroy"
      (is (nil? node)
          "the :machine-instance-owned snapshot node is gone after final-state auto-destroy")
      (is (nil? (get-in rt (machine-paths/snapshot-path :job/runner)))
          "the machine-owned snapshot is released from runtime-db (machine destroy releases its owners)"))))

#?(:cljs
   (deftest e-subscription-disposal-releases-its-cache-entry-node
     ;; Drive the real CLJS cache path: one reader materializes the node and
     ;; dropping the sole reference disposes it synchronously.
     (register-one-of-each!)
     ;; A parametric sub whose one realized input edge is a plain layer-1
     ;; reader, so a concrete subscribe materializes exactly one clean cache
     ;; entry (+ its :cart/items input) carrying a realized `[:sub q]` edge —
     ;; no resource / unregistered-input fan-out.
     (rf/reg-sub :cart/item-qty
                 (fn [[_ _sku]] [[:cart/items]])
                 (fn [[items] [_ sku]] (some #(when (= sku (:sku %)) (:qty %)) items)))
     (rf/dispatch-sync [::seed-cart cart-items])
     (let [q [:cart/item-qty "b"]
           r (subs/subscribe q {:frame :rf/default})]
       (is (= 3 @r)
           "sanity: the parametric sub computes its whole value from the realized input")
       ;; Live cache entries use the concrete query vector in their node id.
       ;; Assert presence before disposal to avoid a vacuous release check.
       (let [g-before (graph/live-derivation-graph :rf/default all-contributors)
             node     (get (:nodes g-before) [:sub q])]
         (testing "the live subscribe materialized the cache-entry node"
           (is (some? node)
               "subscribing must surface the concrete cache-entry node in the live graph")
           (is (= :derivation (:kind node)))
           (is (= :subscription-cache-entry (:lifecycle node))
               "the live node carries the :subscription-cache-entry lifecycle it is released under")
           (is (= 1 (:ref-count node))
               "one live reader keeps the cache entry alive — the :ref-count lifecycle evidence")))
       ;; The sole reader drops the ref-count to zero and triggers eviction.
       (subs/unsubscribe :rf/default q)
       (let [g-after (graph/live-derivation-graph :rf/default all-contributors)]
         (testing "subscription disposal releases the cache-entry node from the live graph"
           (is (nil? (get (:nodes g-after) [:sub q]))
                "the :subscription-cache-entry node left the live graph at ref-count zero")
           (is (not (contains? (set (keys @(:sub-cache (frame/frame :rf/default)))) q))
               "and the underlying :sub-cache entry is evicted — the release the node absence reflects"))))))

;; ===========================================================================
;; (f) EVALUATION POLICY — reading on-demand facts must not write app-db or
;;     runtime-db. In particular, reading a resource selector starts no work.
;; ===========================================================================

(deftest f-reading-an-on-demand-node-causes-no-durable-write
  ;; Exercise both an ordinary subscription and the read-fact selector of a
  ;; resource process. The work ledger lives in runtime-db, so equality of the
  ;; two durable partitions also proves that no resource work was started.
  (register-one-of-each!)
  ;; First, pin the algebra classification the read is exercising: the sub is
  ;; the canonical :on-demand node; the resource selectors are the
  ;; read-fact surface of an :on-demand-readable process.
  (let [nodes (:nodes (graph/derivation-graph all-contributors))
        sub   (node-by-family nodes :subs      :cart/items)
        res   (node-by-family nodes :resources :article/by-slug)]
    (is (= :on-demand (:evaluation sub))
        "the subscription is the canonical :on-demand node whose read must be write-free")
    (is (contains? (set (:selectors res)) :rf/resource)
        "the resource exposes the :rf/resource read selector"))
  ;; Snapshot both durable partitions, read the on-demand nodes, re-snapshot.
  (let [app-before (frame/frame-app-db-value :rf/default)
        rt-before  (frame/frame-runtime-db-value :rf/default)
        ;; Read the ordinary subscription. The value is deliberately
        ;; discarded — it may be nil (no cart seeded); the point is that the
        ;; read RAN, between the two durable-state snapshots.
        _          @(rf/subscribe [:cart/items])
        ;; Read the resource selector for an unmaterialized key — the idle
        ;; empty-state projection. Reading it must not start work or write a
        ;; cache entry / work-ledger record.
        sel-val    @(rf/subscribe [:rf/resource
                                   {:resource :article/by-slug :params {:slug "welcome"}}])
        app-after  (frame/frame-app-db-value :rf/default)
        rt-after   (frame/frame-runtime-db-value :rf/default)]
    (testing "the reads return values (the reads actually happened)"
      ;; The subscription read above is unasserted by design (see its comment);
      ;; the selector read carries the observable claim.
      (is (= :idle (:status sel-val))
          "reading the selector for an unmaterialized key yields the idle empty-state"))
    (testing "neither durable partition changed merely because a reader read"
      (is (= app-before app-after)
          "app-db is value-equal after on-demand reads")
      (is (= rt-before rt-after)
          "runtime-db is value-equal — no cache entry or work-ledger record"))
    (testing "specifically, no work-ledger record was created by the selector read"
      (is (= (:rf.runtime/work-ledger rt-before) (:rf.runtime/work-ledger rt-after))
          "reading a resource selector starts no resource work (§Evaluation policy rule 1)"))))

;; ===========================================================================
;; (g) TOOL REDACTION — off-box graph egress.
;;
;; The composer assembles an internal graph; it does not apply a graph-wide
;; wire policy. `egress/project-graph` owns that boundary: it walks value
;; summaries under the named frame's elision policy and remaps every
;; identity-bearing resource position consistently so edges still connect.
;;
;; The projection lives in bundle-isolated core so this test and tool consumers
;; can share it without making this implementation tier depend on `tools/`.
;; The synthetic arms below isolate that graph-wide projection. The final g+
;; arm separately exercises `resource-cache-algebra-view`, whose resource
;; classification and scoped-key projection run before composition.
;; ===========================================================================

;; ---- (g) resource identity egress redaction ------------------------------
;;
;; A live resource node carries scope and params in its identity: the scoped key
;; `[cache-scope resource-id canonical-params]` that is simultaneously the
;; node KEY, the `:id`, the `:output` runtime-path tail, the realized
;; `:inputs` `[:scope …]` / `[:param …]`, the `:work-ledger :record
;; :resource/key`, and the edge endpoints naming it. A value-path walker cannot
;; reach these positions, so the graph projection must opaque them consistently.

(def ^:private egress-frame :app/egress-secure)

(def ^:private secret-token "tenant-jwt-9f3a-SECRET")
(def ^:private secret-scope  [:rf.scope/tenant secret-token])
(def ^:private secret-params {:slug "welcome" :auth-token secret-token})
(def ^:private egress-scoped-key
  ;; [cache-scope resource-id canonical-params] — the live fact identity.
  [secret-scope :article/by-slug secret-params])
(def ^:private egress-nav-token 23)

;; Embed the sensitive scoped key in the canonical work-id tuple so the test
;; covers the top-level ledger link, record copy, and host-transient handle
;; address as distinct identity positions.
(def ^:private egress-generation 4)
(def ^:private egress-work-id
  [:rf.work/resource egress-scoped-key egress-generation])

(defn- egress-live-contributors
  "Contributors whose `:resources` / `:routes` live-fns return the realized
  shapes a route-owned fetch under a sensitive (tenant-scoped) activation
  produces, so the composer's live node-wrapping + edge derivation runs over
  the concrete sensitive scoped key. Mirrors `resource-cache-algebra-view`'s
  live-node-for shape (`re-frame.resources.tooling`)."
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
  "Return true when the secret token appears anywhere in a nested value,
  including map keys."
  [v]
  (boolean
    (cond
      (= v secret-token) true
      (map? v)           (some contains-secret? (concat (keys v) (vals v)))
      (coll? v)          (some contains-secret? v)
      :else              false)))

(defn- projected-scoped-key?
  "Return true for a projected scoped-key tuple that preserves resource id."
  [v]
  (and (vector? v)
       (= 3 (count v))
       (keyword? (nth v 1))))

(deftest g-live-resource-identity-redacted-at-graph-egress
  ;; A sensitive scope/params fixture, projected through the shared graph
  ;; egress boundary, asserting no raw secret survives anywhere, the
  ;; non-sensitive resource id remains visible, all identity positions use the
  ;; same stable opaque scoped key, and edges still connect.
  (rf/make-frame {:id egress-frame :doc "off-box egress conformance frame"})
  (let [raw      (graph/live-derivation-graph egress-frame (egress-live-contributors))
        redacted (egress/project-graph raw egress-frame)]

    (testing "the raw fixture exposes the sensitive identity to the projection"
      (is (contains? (:nodes raw) [:resource egress-scoped-key])
          "the live resource node is keyed by the raw sensitive scoped key")
      (is (contains-secret? raw)
          "sanity: the raw composed graph carries the secret token"))

    (testing "no raw secret survives in the egressed graph"
      (is (not (contains-secret? redacted))
          "the secret must not appear anywhere in the off-box graph"))

    (testing "the resource id remains visible and identity remapping is stable"
      (let [node          (-> redacted :nodes vals first)
            node-key      (-> redacted :nodes keys first)
            key-scoped    (second node-key)
            id-scoped     (:id node)
            output-scoped (last (second (:output node)))
            ledger-scoped (get-in node [:work-ledger :record :resource/key])]
        (is (= :resource (first node-key)) "still a :resource node key")
        (is (projected-scoped-key? key-scoped) "still a 3-tuple scoped-key shape")
        (is (= :article/by-slug (nth key-scoped 1))
            "the registration resource-id remains visible")
        (is (not= secret-scope (nth key-scoped 0)) "the scope component is opaqued")
        (is (not= secret-params (nth key-scoped 2)) "the params component is opaqued")
        (is (= key-scoped id-scoped output-scoped ledger-scoped)
             "node key, :id, :output, and work-ledger use one projected identity")))

    (testing "graph connectivity survives the identity remap"
      (is (not (contains? (:nodes redacted) [:resource egress-scoped-key]))
          "the raw-scoped-key node key is gone")
      (let [node     (-> redacted :nodes vals first)
            node-key (-> redacted :nodes keys first)
            edge     (->> (:edges redacted)
                          (filter #(= :param (:role %)))
                          first)]
        (is (= :process (:kind node)) "still classified by superkind")
        (is (= :resource-process (:refinement node)))
        (is (some? edge) "the route → resource :param edge is still present")
        (is (= node-key (:to edge))
             "the edge :to uses the same projected node key")))

    (testing "identity-bearing fields keep their structure without the secret"
      (let [node (-> redacted :nodes vals first)]
        (is (= [:scope :param] (mapv first (:inputs node)))
            "the [:scope …] / [:param …] input roles survive")
        (is (not= secret-scope  (second (first (:inputs node)))))
        (is (not= secret-params (second (second (:inputs node)))))
        (is (= :runtime (first (:output node))) ":output is still a runtime address")
        (is (= [:rf.runtime/resources :entries] (take 2 (second (:output node))))
            "the :output runtime path prefix survives")
        (let [rec (get-in node [:work-ledger :record])]
          (is (vector? (:work/id rec))
              "the work-ledger record's :work/id keeps the canonical tuple shape")
          (is (= :rf.work/resource (first (:work/id rec)))
              "the work-id family head survives projection (:rf.work/resource)")
          (is (= egress-generation (nth (:work/id rec) 2))
              "the non-secret attempt generation rides through unchanged")
          (is (projected-scoped-key? (nth (:work/id rec) 1))
              "the work-id's embedded scoped key is projected to the opaque-handle shape")
          (is (= :article/by-slug (nth (nth (:work/id rec) 1) 1))
              "the resource-id inside the work-id's embedded scoped key stays visible")
          (is (= :pending (:status rec)))
          (is (projected-scoped-key? (:resource/key rec))
              "the work-ledger :resource/key keeps the scoped-key shape"))))

    (testing "all work-id copies and the host-transient address use one projected id"
      (let [node    (-> redacted :nodes vals first)
            top-wid (get-in node [:work-ledger :work/id])
            rec-wid (get-in node [:work-ledger :record :work/id])
            ht-wid  (second (first (:host-transient node)))]
        (is (some? top-wid) "the top-level work-ledger link carries a work-id")
        (is (= top-wid rec-wid)
            "the top-level slot and record copy use the same projected work-id")
        (is (= top-wid ht-wid)
            "the host-transient handle address names the same projected work-id")
        (is (not (contains-secret? top-wid))
            "no raw secret survives in the projected work-id")))))

(defn- egress-sensitive-value-contributors
  "The `egress-live-contributors` fixture plus a value-bearing live sub node
  sitting at the frame-sensitive `[:cart :items]` path, so one graph exercises
  BOTH egress leak channels: the value-path walk and the frame-independent
  resource-identity projection."
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

(defn- classify-egress-frame-sensitive!
  "Install the `[:cart :items]` sensitive classification on `egress-frame`
  through the same runtime-db effect the commit plane uses."
  []
  (frame/swap-runtime-db! egress-frame
    (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:cart :items]]}))))

(deftest g-graph-egress-for-unknown-frame-fails-closed
  ;; An unreachable frame has no usable value policy, so value-bearing fields
  ;; fail closed. Resource identity projection is independent of that policy.
  (rf/make-frame {:id egress-frame})
  (classify-egress-frame-sensitive!)
  (let [contributors (egress-sensitive-value-contributors)
        raw (graph/live-derivation-graph egress-frame contributors)]
    (testing "the raw graph carries the secret in value and identity positions"
      (is (contains-secret? raw)))
    (testing "egress under an unknown frame fails closed"
      (let [redacted (egress/project-graph raw :app/does-not-exist)
            sub      (get-in redacted [:nodes [:sub [:cart/items]]])]
        (is (= privacy/redacted-sentinel (:value sub))
            "the whole value-bearing field is redacted under no reachable policy")
        (is (not (contains-secret? redacted))
            "no raw secret survives — value-path fail-closed + frame-independent
             identity projection cover both leak channels")))
    (testing "a nil frame does not borrow an ambient frame's policy"
      (rf/with-frame :rf/default
        (is (some? (frame/resolve-current-frame))
            "an ambient frame is bound, making policy borrowing observable")
        (let [redacted (egress/project-graph raw nil)
              sub      (get-in redacted [:nodes [:sub [:cart/items]]])]
          (is (= privacy/redacted-sentinel (:value sub))
              "nil frame redacts rather than using the ambient frame")
          (is (not (contains-secret? redacted))
              "no raw secret survives a nil-frame egress under an ambient binding"))))
    (testing "egress under the known frame applies its classified value path"
      (let [redacted (egress/project-graph raw egress-frame)
            sub      (get-in redacted [:nodes [:sub [:cart/items]]])]
        (is (= privacy/redacted-sentinel (get-in sub [:value :cart :items]))
            "the classified [:cart :items] leaf is redacted")
        (is (= :derivation (:kind sub)) "the sub node is still present + classified")
        (is (not (contains-secret? redacted)))))))

(deftest g-graph-egress-nil-frame-fails-closed-under-sentinel-id-collision
  ;; rf2-g1vu — the fail-closed stamp `project-graph` applies when the
  ;; governing frame is nil / unreachable must be a value NO app can register
  ;; a frame under. It was `::no-egress-frame`, i.e. the ordinary public
  ;; keyword `:re-frame.derivation.egress/no-egress-frame`: `make-frame`
  ;; validates no `:id` type and the registry is keyed by whatever id it is
  ;; handed, so an app registering a live frame under that literal turned the
  ;; fail-CLOSED stamp into a live-frame walk under that frame's (empty)
  ;; declaration registry, and the graph's value-bearing fields shipped RAW.
  ;;
  ;; Same defect class as Xray's `::no-frame`, fixed by the rf2-7htk7 third
  ;; pass (commit 449bfd21c7) with the collision regression this arm mirrors:
  ;; register a live frame under the literal id, leave the governing frame
  ;; unselected, assert `:rf/redacted`.
  (rf/make-frame {:id egress-frame})
  (classify-egress-frame-sensitive!)
  ;; The colliding frame: an ordinary public keyword any app can spell, and
  ;; deliberately with no classification of its own — an empty declaration
  ;; registry is what makes the leak observable.
  (rf/make-frame {:id :re-frame.derivation.egress/no-egress-frame})
  (try
    (let [raw (graph/live-derivation-graph egress-frame
                                           (egress-sensitive-value-contributors))]
      (is (contains-secret? raw)
          "the raw graph carries the secret in value and identity positions")
      ;; An ambient frame is bound as well, so a pass here cannot be an
      ;; accident of there being nothing to borrow.
      (rf/with-frame :rf/default
        (is (some? (frame/resolve-current-frame))
            "an ambient frame is bound, making policy borrowing observable")
        (let [redacted (egress/project-graph raw nil)
              sub      (get-in redacted [:nodes [:sub [:cart/items]]])]
          (is (= privacy/redacted-sentinel (:value sub))
              "a nil governing frame must redact the whole value-bearing field
               even with a live frame registered under the dead-frame
               sentinel's former keyword id")
          (is (not (contains-secret? redacted))
              "no raw secret survives a nil-frame egress while a frame is live
               under the dead-frame sentinel's former keyword id (rf2-g1vu)"))))
    (finally
      ;; Never leave the colliding id live for a sibling test.
      (rf/destroy-frame! :re-frame.derivation.egress/no-egress-frame))))

(deftest g-graph-egress-is-idempotent
  ;; Forwarders may project the same graph more than once. Full graph equality
  ;; catches fresh handles in any identity-bearing position, including realized
  ;; inputs that would not be detected by node-key checks alone.
  (rf/make-frame {:id egress-frame})
  (let [raw   (graph/live-derivation-graph egress-frame (egress-live-contributors))
        once  (egress/project-graph raw egress-frame)
        twice (egress/project-graph once egress-frame)
        node1 (-> once :nodes vals first)
        node2 (-> twice :nodes vals first)]
    (is (not (contains-secret? twice)) "still no secret after double projection")
    ;; A second egress pass is the identity.
    (is (= once twice)
        "egress is idempotent — re-projecting an already-projected graph is the
         identity (project(project(x)) == project(x))")
    ;; Spelled-out witnesses identify the position that drifted.
    (is (= (set (keys (:nodes once))) (set (keys (:nodes twice))))
        "node keys are stable under re-projection")
    (is (= (:id node1) (:id node2))
        "the projected scoped-key :id is stable under re-projection")
    (is (= (:inputs node1) (:inputs node2))
        "the projected realized :inputs ([:scope …]/[:param …]) are stable — a
         second pass must not re-hash the opaque handles into fresh handles")
    (is (= (:output node1) (:output node2))
        "the projected :output runtime path is stable under re-projection")
    (is (= (get-in node1 [:work-ledger :record :resource/key])
           (get-in node2 [:work-ledger :record :resource/key]))
        "the projected work-ledger :resource/key is stable under re-projection")
    ;; The work-id is copied into three positions; diagnose each independently.
    (is (= (get-in node1 [:work-ledger :work/id])
           (get-in node2 [:work-ledger :work/id]))
        "the projected top-level :work-ledger :work/id is stable under re-projection")
    (is (= (get-in node1 [:work-ledger :record :work/id])
           (get-in node2 [:work-ledger :record :work/id]))
        "the projected :work-ledger :record :work/id is stable under re-projection")
    (is (= (:host-transient node1) (:host-transient node2))
        "the projected :host-transient in-flight handle is stable under re-projection")))

;; ===========================================================================
;; (g+) RESOURCE-CONTRIBUTOR EGRESS THROUGH THE COMPOSER.
;;
;; The synthetic g arms isolate `egress/project-graph`. This arm instead uses
;; the actual resource contributor, which applies resource classification and
;; scoped-key projection before the composer receives its nodes. The cache row
;; is a direct fixture because this artefact intentionally has no HTTP
;; dependency; resource state and work-ledger constructors keep its shape
;; canonical. The route still runs through normal navigation so its owner and
;; activation edge are genuine live facts.
;; ===========================================================================

(def ^:private real-egress-frame :app/real-resource-egress)

(deftest gplus-resource-cache-graph-egress-via-the-tooling-path
  (rf/make-frame {:id real-egress-frame :doc "real resource-cache graph egress conformance frame"})
  (rf/reg-resource :secret/tenant-article
                   {:scope         :rf.scope/global
                    :params-schema [:map [:auth-token :string]]
                    :sensitive?    true}
                   (fn [{:keys [auth-token]} _ctx]
                     {:request {:method  :get
                                :url     "/api/secure-article"
                                :headers {"Authorization" auth-token}}}))
  ;; Keep the route params non-sensitive so this arm isolates the resource
  ;; identity projection. Normal navigation supplies a real nav-token owner.
  (rf/reg-route :route/secure-article {} "/secure")
  (rf/dispatch-sync [:rf.route/navigate {:to :route/secure-article}]
                    {:frame real-egress-frame})
  (let [nav-token   (:nav-token (get (:nodes (graph/live-derivation-graph real-egress-frame all-contributors))
                                     :rf/route))
        owner       [:route :route/secure-article nav-token]
        scope       :rf.scope/global
        params      {:auth-token secret-token}
        scoped-key  (resources-state/scoped-resource-key scope :secret/tenant-article params)
        work-id     (work-ledger/resource-work-id scoped-key 1)
        entry       (assoc (resources-state/empty-entry :secret/tenant-article scoped-key)
                           :status :fetching :active-owners #{owner} :current-work work-id)]
    (is (some? nav-token) "the navigation minted a nav-token")
    ;; Materialize with the resource and work-ledger constructors. This is test
    ;; setup, not the resource request/write path under test.
    (frame/swap-runtime-db!
      real-egress-frame
      (fn [rdb]
        (-> (assoc-in (or rdb {}) (resources-state/entry-path scoped-key) entry)
            (work-ledger/put-record
              work-id
              (work-ledger/work-record {:work-id      work-id
                                        :frame-id     real-egress-frame
                                        :resource/key scoped-key
                                        :generation   1
                                        :transport    :rf.http/managed
                                        :owner        owner
                                        :cause        :test/materialize}))))))
  (let [g              (graph/live-derivation-graph real-egress-frame all-contributors)
        resource-entry (->> (:nodes g)
                             (filter (fn [[_ n]] (= :resources (:rf/family n))))
                             first)]
    (testing "the resource contributor assembled the materialized cache entry"
      (is (some? resource-entry)
          "resources.tooling assembled a live :resources node"))
    (let [[res-key res-node] resource-entry]
      (testing "the resource id remains visible after contributor projection"
        (is (= :secret/tenant-article (nth (:id res-node) 1))
            "the registration resource-id survives inside the (already
             tooling-projected) scoped key"))
      (testing "no raw secret survives contributor projection and composition"
        (is (not (contains-secret? g))
            "the secret auth-token must not appear anywhere in the composed graph"))
      (testing "the route edge joins the contributor's projected resource key"
        (let [edge (->> (:edges g) (filter #(= :param (:role %))) first)]
          (is (some? edge) "the route-owned resource activation edge is present")
          (is (= res-key (:to edge))
              "the edge :to matches the (already-projected) resource node key")))
      (testing "the canonical entry exposes its ledger and host-handle address"
        (is (= :fetching (:status res-node)))
        (is (some? (get-in res-node [:work-ledger :work/id]))
            "a real work-ledger link is present")
        (is (some? (:host-transient res-node))
            "a host-transient in-flight handle address is present")))))
