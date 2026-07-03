(ns re-frame.derivation-algebra-conformance-cljs-test
  "Cross-family derivation/process-ALGEBRA conformance (rf2-jn7frs;
  EP-0014 Reference Implementation Plan item 9 + §Validation / Conformance;
  spec/Derivations.md §Conformance).

  EP-0014's whole point is ONE derivation/process vocabulary across the FIVE
  graph families — subscriptions (`reg-sub`, which folds `reg-runtime-sub`
  under the same subs contributor), flows (`reg-flow`), resources
  (`reg-resource`), route facts (`reg-route`), and machines (`reg-machine`)
  — so a tool reads ONE graph
  (`{:mode :nodes :edges}`) and a maintainer answers ONE question
  consistently: where does this fact come from, when is it evaluated, where
  does it live, and who owns it? Each family already exposes its OWN algebra
  view (the per-artefact `*-algebra-view` tooling siblings) and the composer
  (`re-frame.derivation.graph`) stitches them; the per-artefact suites pin
  each projection in isolation and the composer test
  (`re-frame.derivation-graph-test`) pins the assembly MECHANICS. But until
  this suite NO test proved the EP-0014 conformance AXES hold ACROSS
  all families SIMULTANEOUSLY, through the composer, the way they will be
  read in anger.

  That is exactly the umbrella `reply-conformance/` is to EP-0011's reply
  vocabulary: a table-driven cross-family gate so a divergence in any one
  family's lowering / classification / edges cannot silently slip past the
  per-family suites (each of which only ever looks at itself).

  ## What this suite is

  A registration of ONE source form of EACH family into a live frame, then
  one set of cross-family assertions over the assembled STATIC and LIVE
  graphs proving the six conformance axes (the EP-0014 §Validation /
  Conformance list, restated as the slice-1 contract):

    (a) LOWERING        — each source form lowers to the correct node KIND /
                          SUPERKIND. Every node's `:kind` is DIRECTLY a member
                          of the closed two-superkind enum (`:derivation` |
                          `:process`); a tool that understands ONLY the two
                          superkinds can classify EVERY node by reading `:kind`
                          alone. Informative refined kinds
                          (`:resource-process`, `:route-fact`,
                          `:machine-process`) ride the separate `:refinement`
                          axis, NEVER `:kind` (rf2-7wwp1z).
    (b) CLASSIFICATION  — storage-class / evaluation-policy / lifecycle
                          correctness PER FAMILY, against the
                          spec/Derivations.md fixed-classification tables
                          (subscriptions ephemeral/on-demand/cache-entry;
                          flows app-db/after-event/frame; resources
                          runtime-db/multi-trigger/resource-key + external
                          `:authority`; routes runtime-db/on-route/frame;
                          machines runtime-db/on-transition/machine-instance).
                          Plus the storage-class invariants: NO node uses
                          `:remote` as a storage class (the issue-2 split);
                          every storage / evaluation / lifecycle value is in
                          its closed vocabulary.
    (c) GRAPH EDGES     — `:input` / `:param` / `:selector` edges correct in
                          the STATIC graph (registration-known edges,
                          `:parametric` markers, the don't-execute rule) and
                          the LIVE graph (realized `[:sub q]` edges a
                          parametric sub cannot enumerate statically).
    (d) WHOLE-VALUE     — the semantic whole-value law (slice-1): a
                          MATERIALIZED derivation's output path holds the
                          SAME whole value its derivation fn computes from
                          the same inputs (proven on a flow); an EPHEMERAL
                          derivation's read value equals the whole-value
                          recompute. The optional delta law is deferred per
                          EP-0014 bead-plan item 10 (semantic-only in
                          slice-1); a conforming implementation with NO
                          delta support still conforms.
    (e) LIFECYCLE       — the §Conformance 'Lifecycle' bullet's RELEASE
                          axis (rf2-pomhpf): destroying a frame releases
                          frame-owned graph nodes; route exit / supersession
                          releases the prior route owner; machine destroy
                          (here, final-state auto-destroy) releases the
                          machine-owned snapshot node. The per-family suites
                          and the (c) live arm only ever read a PRESENT
                          owner; this arm drives the teardown TRANSITION and
                          asserts the node / owner has LEFT the live graph —
                          the axis the lifecycle classification exists for.
    (f) EVALUATION      — the §Conformance 'Evaluation policy' bullet's
                          on-demand-no-write law (rf2-qdxvkb; §Evaluation
                          policy rule 1): reading an `:on-demand` node (a
                          subscription, or a resource `:rf.resource/*`
                          selector) causes NO durable write — neither app-db
                          nor runtime-db (so no work-ledger record, no cache
                          mutation) changes merely because a reader read it.
                          The (d) laws prove the MATERIALIZED path's value;
                          this proves the ephemeral/on-demand path's negative
                          no-write property.

  Drives the merged tooling siblings + the graph composer
  (`re-frame.derivation.graph`) — it consumes them, never edits them. The
  families sit ABOVE several artefacts (core + flows/resources/routing/
  machines), so this lives in its own cross-artefact
  `derivation-conformance/` surface (the precedent is `reply-conformance/`
  and `security/`), not any single family's test tree. Runs on the
  `npm run test:cljs` node gate (ns matches `cljs-test$`) AND the JVM gate
  (`implementation/derivation-conformance/deps.edn` `:test`).

  Canonical contract: `spec/Derivations.md` §Conformance + the EP-0014
  §Validation / Conformance list."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.set :as set]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.derivation.graph :as graph]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            ;; EP-0015 / EP-0017 redaction conformance arms (rf2-zt5j96 /
            ;; rf2-iormgq) — the in-tree egress primitives the umbrella egress
            ;; arms compose: `re-frame.identity` mints the stable opaque
            ;; scoped-key handle (the CEDN-1 canonical-bytes hash), and
            ;; `re-frame.privacy` carries the `:rf/redacted` sentinel
            ;; `rf/elide-wire-value` writes. These are `implementation/`-resident
            ;; (NOT a `tools/` reach), so the cross-family conformance surface
            ;; proves the egress LAW over the REAL composer output without
            ;; importing the named first-consumer Xray (bundle isolation).
            [re-frame.identity :as identity]
            [re-frame.privacy :as privacy]
            ;; EP-0025: the derived-output sensitivity INHERITANCE conformance
            ;; (§H) is removed — classification no longer propagates input →
            ;; output (subs or flows). `elision/sensitive-declarations` is still
            ;; used by §G's egress-redaction conformance.
            [re-frame.elision :as elision]
            ;; load-bearing side-effecting requires: each façade registers
            ;; its registrar kind / framework events / subs so the family is
            ;; live (the same loads the composer test performs).
            [re-frame.routing]
            [re-frame.routing.tooling :as routing-tooling]
            [re-frame.resources]
            [re-frame.resources.tooling :as resources-tooling]
            [re-frame.machines.tooling :as machines-tooling]
            [re-frame.machines.paths :as machine-paths]
            [re-frame.subs.tooling :as subs-tooling]
            [re-frame.flows.tooling :as flows-tooling]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace]))

;; ---------------------------------------------------------------------------
;; Closed vocabularies — the slice-1 enums spec/Derivations.md fixes
;; (Spec-Schemas §`:rf/storage-class` / `:rf/evaluation-policy` /
;; `:rf/lifecycle` / `DerivationKind`). A storage class / evaluation policy /
;; lifecycle a node carries MUST be in its closed set; the two superkinds are
;; the only closed `:kind` reduction.
;; ---------------------------------------------------------------------------

(def superkinds
  "The closed two-superkind enum (Derivations §The node shape). A tool that
  understands only these MUST be able to classify every node."
  #{:derivation :process})

(def refinements
  "The informative refined kinds (Derivations §The node shape — refinements
  are informative, the superkind is canonical) and the superkind each refines.
  Per the graduated Spec-Schemas `DerivationKind` enum these are CLOSED out of
  `:kind` (a node's `:kind` is always a bare superkind); a refinement rides the
  separate `:refinement` axis. This map exists only to validate that a node's
  declared `:refinement` (when present) refines the superkind its `:kind`
  asserts (rf2-7wwp1z)."
  {:resource-process :process
   :route-fact       :process
   :machine-process  :process
   :machine-selector :derivation})

;; NOTE (rf2-7wwp1z): there is deliberately NO `superkind-of` reduction helper.
;; The closed `DerivationKind` enum makes a node's `:kind` ALWAYS a bare
;; superkind, so conformance asserts `(contains? superkinds (:kind node))`
;; DIRECTLY. The historic refined->superkind lookup masked a `:kind
;; :machine-process` violation by accepting a refined kind in `:kind` and
;; reducing it — the closed-enum contract is pinned by direct membership now.

(def storage-classes
  "The closed storage-class set (Derivations §Storage class — the issue-2
  swept FOUR-class table). `:remote` is NOT among them — external authority
  is the separate `:authority` axis."
  #{:ephemeral :app-db :runtime-db :host-transient})

(def evaluation-policies
  "The closed evaluation-policy set (Derivations §Evaluation policy)."
  #{:on-demand :after-event :on-reply :on-route :on-transition :scheduled :manual})

(def lifecycles
  "The closed lifecycle set (Derivations §Lifecycle and owner)."
  #{:subscription-cache-entry :frame :route :scoped-resource-key :machine-instance :host-root})

;; ---------------------------------------------------------------------------
;; The runtime fixture — reset every artefact's registry / frame state, init
;; the plain-atom substrate, and re-load every optional façade so its
;; framework registrations are live (mirrors the composer test's fixture).
;; ---------------------------------------------------------------------------

;; This suite installs the NON-React `plain-atom` substrate. Under the
;; always-on `npm run test:cljs` gate EVERY `*-cljs-test` namespace compiles
;; and runs in ONE shared CLJS bundle (one Node process, one process-global
;; runtime — adapter slot, registrar, `frame/frames`). A bare per-test fixture
;; that `clear-all!`s the registrar / installs an adapter and does NOT
;; snapshot-and-restore on teardown strands state for the alphabetically-later
;; suites in the bundle — exactly the run-order hazard
;; `re-frame.test-support/make-reset-runtime-fixture` was built to absorb
;; (rf2-7hwnu): it captures this ns's load-time registrar baseline, folds it
;; back before each test, snapshots, and on the way out RESTORES the registrar
;; AND disposes the installed adapter. The JVM-only composer test this suite
;; mirrors (`re-frame.derivation-graph-test`, a `.clj`) can keep a bare
;; `reset-runtime` because the JVM gate never shares a bundle with a React
;; adapter; the CLJS bundle does (`re-frame.reg-view-react-key-cljs-test`'s
;; `reagent.core/as-element` died with a null `reagent_component` React-
;; internals read when this suite left the runtime dirty), so the conformance
;; CLJS arm MUST use the disciplined fixture. `:init-fn` carries the
;; per-test family-registration refresh (the `:reload`s the bare JVM fixture
;; did inline) — it runs AFTER the adapter is installed and the registrar
;; baseline is reinstated, BEFORE the test body, with the ambient
;; `:rf/default` frame bound (the fixture's default `:ambient-frame`).
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
;; The contributor map — every family present (built explicitly so the suite
;; pins the composition regardless of JVM auto-resolution, and exercises the
;; explicit-arg path the CLJS consumer uses on both runtimes).
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
;; ONE whole-value function shared by the subscription AND the flow — the
;; Derivations §Worked equivalence: the SAME mathematical function, expressed
;; two ways, differs only in policy. The whole-value law (d) is proven by
;; checking the materialized flow output equals `(sum-cart …)` of the same
;; inputs — the identical function the subscription would compute.
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
;; Register ONE source form of EACH family into the default frame. The graph
;; the composer assembles over these is the conformance subject.
;; ---------------------------------------------------------------------------

(defn- register-one-of-each! []
  ;; the seed event for the whole-value law (registered per-test, inside the
  ;; fixture's restored registrar baseline).
  (rf/reg-event ::seed-cart
                   (fn [{:keys [db]} [_ items]]
                     {:db (assoc-in db [:cart :items] items)}))
  ;; :subs — a layer-1 `:db` reader, a static `:<-` derivation over it (the
  ;; :input edge source carrying the shared sum-cart fn), a PARAMETRIC
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
    (testing "EVERY node's :kind is DIRECTLY a member of the closed two-superkind
              enum — a tool that understands only :derivation / :process classifies
              all by reading :kind alone (no refined-kind reduction; rf2-7wwp1z)"
      (doseq [[node-id node] nodes]
        (is (contains? superkinds (:kind node))
            (str node-id " :kind " (:kind node)
                 " is not DIRECTLY in the closed DerivationKind enum "
                 (pr-str superkinds)
                 " — refined kinds must ride :refinement, never :kind"))))
    (testing "every refinement (when present) is informative and refines the
              superkind its :kind asserts — refinements never replace the
              superkind (rf2-7wwp1z)"
      (doseq [[node-id node] nodes
              :let [refinement (:refinement node)]
              :when (some? refinement)]
        (is (contains? refinements refinement)
            (str node-id " :refinement " refinement " is not a known refinement"))
        (is (= (:kind node) (get refinements refinement))
            (str node-id " :refinement " refinement " refines "
                 (get refinements refinement) " but :kind asserts " (:kind node)))))
    (testing "subscriptions + flows are DERIVATIONS (Derivations §Derivation)"
      (is (= :derivation (:kind (node-by-family nodes :subs :cart/total))))
      (is (= :derivation (:kind (node-by-family nodes :subs :cart/items))))
      (is (= :derivation (:kind (node-by-family nodes :flows :cart/materialized-total)))))
    (testing "resources + routes + machines are PROCESSES (Derivations §Process)
              — :kind is the bare :process superkind across ALL THREE families;
              the informative refinement (:resource-process / :route-fact /
              :machine-process) rides :refinement, one convention (rf2-7wwp1z)"
      (let [res   (node-by-family nodes :resources :article/by-slug)
            route (node-by-family nodes :routes    :route/article)
            mach  (node-by-family nodes :machines  :upload/main)]
        (is (= :process (:kind res)))
        (is (= :process (:kind route)))
        (is (= :process (:kind mach)))
        (is (= :resource-process (:refinement res)))
        (is (= :route-fact       (:refinement route)))
        (is (= :machine-process  (:refinement mach)))))
    (testing "the route fact's id is the ONE consumer-facing slice name :rf/route
              (one-name-per-fact), with the per-route id under :source-form"
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
    (testing "subscription — the canonical EPHEMERAL / on-demand / cache-entry member"
      (is (= :ephemeral                (:storage sub)))
      (is (= :on-demand                (:evaluation sub)))
      (is (= :subscription-cache-entry (:lifecycle sub)))
      (is (false? (:materialized? sub))))
    (testing "flow — the canonical MATERIALIZED app-db / after-event / frame member"
      (is (= :app-db      (:storage flow)))
      (is (= :after-event (:evaluation flow)))
      (is (= :frame       (:lifecycle flow)))
      (is (true? (:materialized? flow)))
      (is (= [:db [:cart :total]] (:output flow)) "materialized output address"))
    (testing "resource — runtime-db local storage + EXTERNAL authority (issue-2 split)"
      (is (= :runtime-db    (:storage res)) "storage names the LOCAL home")
      (is (= :scoped-resource-key  (:lifecycle res)))
      (is (= #{:on-route :on-reply :scheduled :manual} (:evaluation res))
          "a multi-trigger process carries the policy SET")
      (is (= :remote (get-in res [:authority :kind]))
          "the remote axis is the separate :authority, NOT a storage class")
      (is (true? (:materialized? res))))
    (testing "route — runtime-db / on-route / frame"
      (is (= :runtime-db (:storage route)))
      (is (= :on-route   (:evaluation route)))
      (is (= :frame      (:lifecycle route)))
      (is (= [:runtime [:rf.runtime/routing :current]] (:output route))))
    (testing "machine — runtime-db / on-transition (+scheduled for :after) / machine-instance"
      (is (= :runtime-db        (:storage mach)))
      (is (= :machine-instance  (:lifecycle mach)))
      (is (contains? (:evaluation mach) :on-transition) "always evaluates :on-transition")
      (is (contains? (:evaluation mach) :scheduled)
          "an :after timer adds :scheduled to the policy set")
      (is (true? (:materialized? mach))))))

(deftest b-every-classification-is-in-its-closed-vocabulary
  (register-one-of-each!)
  (let [nodes (:nodes (graph/derivation-graph all-contributors))]
    (testing "NO node uses :remote as a storage class (the issue-2 split) and
              every storage / evaluation / lifecycle value is in its closed set"
      (doseq [[node-id node] nodes]
        ;; storage — always a single keyword in the closed FOUR-class set.
        (is (contains? storage-classes (:storage node))
            (str node-id " :storage " (:storage node) " is not a closed storage class"))
        (is (not= :remote (:storage node))
            (str node-id " uses :remote as a STORAGE class — external authority is the :authority axis"))
        ;; evaluation — a single policy OR a set, every member closed.
        (let [ev (:evaluation node)]
          (doseq [p (if (set? ev) ev #{ev})]
            (is (contains? evaluation-policies p)
                (str node-id " :evaluation member " p " is not a closed policy"))))
        ;; lifecycle — a keyword, or a {:kind …} map (live nodes); the kind
        ;; is closed.
        (let [lc (:lifecycle node)
              lk (if (map? lc) (:kind lc) lc)]
          (is (contains? lifecycles lk)
              (str node-id " :lifecycle " lk " is not a closed lifecycle")))))))

(deftest b-external-authority-names-its-local-storage-separately
  ;; Derivations §Authority — `:authority :remote` is NOT a license to hide
  ;; state: a node with external authority STILL declares its local :storage.
  (register-one-of-each!)
  (let [nodes (:nodes (graph/derivation-graph all-contributors))
        res   (node-by-family nodes :resources :article/by-slug)]
    (is (= :remote     (get-in res [:authority :kind])))
    (is (= :runtime-db (:storage res))
        "a remote-authority node still names a LOCAL storage class")
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
  ;; Derivations §The don't-execute rule — static inspection NEVER runs an
  ;; input-fn, so a parametric sub reports the :parametric marker and
  ;; contributes NO static :input edge. Its realized edges are live-only.
  (register-one-of-each!)
  (let [g    (graph/derivation-graph all-contributors)
        node (get (:nodes g) [:sub :article/page])]
    (is (some? node) "the parametric sub node is present")
    (is (= :parametric (:inputs node)) "its declared inputs are the :parametric marker")
    (is (not-any? #(= [:sub :article/page] (:to %)) (:edges g))
        "no static :input edge points at the parametric sub (don't-execute rule)")))

(deftest c-live-graph-has-the-mode-frame-shape-and-realizes-the-route-slice
  ;; The static / live split (Derivations §Static and live graphs): the live
  ;; graph carries `:mode :live` + `:frame`, and reports REALIZED nodes (the
  ;; materialized route slice) the static graph cannot know — the concrete
  ;; matched route id, its params, and the route OWNER (nav-token), facts
  ;; that exist only AFTER a navigation commits to runtime-db.
  (register-one-of-each!)
  (let [g0 (graph/live-derivation-graph :rf/default all-contributors)]
    (testing "the live graph always carries the :mode :live + :frame shape"
      (is (= :live (:mode g0)))
      (is (= :rf/default (:frame g0)))
      (is (map? (:nodes g0)))
      (is (vector? (:edges g0)))))
  ;; Drive a navigation so the route slice is materialized in runtime-db.
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "welcome"}])
  (let [g     (graph/live-derivation-graph :rf/default all-contributors)
        slice (get (:nodes g) :rf/route)]
    ;; The route transition commits the slice SYNCHRONOUSLY under the
    ;; plain-atom substrate this suite installs on BOTH the JVM gate AND the
    ;; node CLJS gate (the suite's own fixture pins `plain-atom/adapter` and
    ;; drives `dispatch-sync`). So the realized slice is a FAILING
    ;; PRECONDITION (rf2-djofbh): if the live graph stops materializing the
    ;; route node after the navigation commits, this test MUST fail — never
    ;; silently pass by guarding the assertions behind the slice's presence.
    ;; The earlier `(when (some? slice) …)` masked exactly that regression.
    (testing "the navigation materialized the live route slice (failing precondition)"
      (is (some? slice)
          "the navigation MUST materialize the :rf/route live node — its absence is a failure, not a skip"))
    (testing "the live route slice node is realized, keyed by :rf/route"
      (is (= :route/article (:route-id slice)) "the live matched route id")
      (is (= {:slug "welcome"} (:params slice)) "the live realized params")
      ;; the live route fact carries the SAME fixed classifications as its
      ;; static node — the live view does NOT re-classify.
      (is (= :runtime-db (:storage slice)))
      (is (= :on-route   (:evaluation slice)))
      (is (= :frame      (:lifecycle slice)))
      ;; the live OWNER is [:route route-id nav-token] when a nav-token has
      ;; been allocated (Derivations §Lifecycle and owner).
      (when-let [owner (:owner slice)]
        (is (= [:route :route/article (:nav-token slice)] owner)
            "the live route OWNER is [:route route-id nav-token]")))))

;; ---------------------------------------------------------------------------
;; (c+) The live graph's NON-route realized composition (rf2-k0meap.3 point-1).
;;
;; `c-live-graph-…-realizes-the-route-slice` above only ever asserts the route
;; slice — leaving the composer's live SUB / RESOURCE projection + realized
;; `:input` / `:param` edge assembly untested (a regression in
;; `family-live-nodes`, the canonical `[:sub …]` / `[:resource …]` wrapping,
;; the live `:input` edge derivation, or composed resource/machine inclusion
;; could pass while Xray's composed live graph is wrong). The realized
;; sub-cache materialization needs a reactive substrate (so it is exercised on
;; the node CLJS gate, not the pure JVM path); but the composer's live
;; ASSEMBLY contract — that it wraps realized facts into canonical node ids,
;; derives realized `:input` edges a parametric sub cannot enumerate
;; statically, includes composed resource nodes with their lifecycle /
;; work-ledger, and resolves route-owned resource edges — is deterministic
;; data over the live projections and is pinned HERE through
;; `live-derivation-graph` over CONTRIBUTORS that return the realized shapes a
;; navigation-then-fetch produces. No `when`-skip: the composer's live edge
;; derivation runs unconditionally over realized inputs.

(def ^:private k3-nav-token 7)
(def ^:private k3-scoped-key
  ;; [cache-scope resource-id canonical-params] — a concrete live fact id.
  [[:rf.scope/global] :article/by-slug {:slug "a1"}])

;; EP-0011 (rf2-cxpa87) — the resource attempt's generation. The canonical
;; resource work-id is the FAMILY tuple `[:rf.work/resource <scoped-key>
;; <generation>]` (`re-frame.resources.work-ledger/resource-work-id`), NOT a
;; scalar. Managed-Effects §Work-id correlation: ledger-backed async work
;; carries ONE `:work/id` per attempt, and the resource family's head is
;; `:rf.work/resource`. The previous fixture used a SCALAR `3` and a
;; non-issuance `:status :pending`, which let this cross-family graph tier pass
;; while accepting a non-canonical work-id shape + a status the real issuance
;; row never writes — so it failed to protect the one-attempt-one-:work/id rule
;; or the work-ledger status vocabulary in the graph projection.
(def ^:private k3-generation 3)
(def ^:private k3-work-id
  ;; The canonical resource work-id tuple — `[:rf.work/resource <scoped-key>
  ;; <generation>]`, exactly what `resources.work-ledger/resource-work-id`
  ;; mints at issuance.
  [:rf.work/resource k3-scoped-key k3-generation])

(defn- k3-live-contributors
  "Contributors whose subs / resources / routes live-fns return the realized
  shapes a `[:article/page \"a1\"]` materialization + a route-owned fetch
  produce, so the composer's live node-wrapping + edge derivation runs over
  concrete facts."
  []
  {;; a LIVE sub-cache projection: a realized parametric sub keyed by its
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
   ;; a LIVE resource cache entry, route-owned, with lifecycle + work-ledger.
   :resources
   {:live-shape :map
    :static-fn  (constantly {})
    :live-fn    (constantly
                  {k3-scoped-key
                   {:id          k3-scoped-key
                    :kind        :process :refinement :resource-process
                    :inputs      [[:scope [:rf.scope/global]] [:param {:slug "a1"}]]
                    :output      [:runtime [:rf.runtime/resources :entries k3-scoped-key]]
                    :storage     :runtime-db
                    :authority   {:kind :remote :system :server}
                    :evaluation  #{:on-route}
                    :lifecycle   {:kind :scoped-resource-key
                                  :owners #{[:route :route/article k3-nav-token]}}
                    :status      :loading
                    ;; EP-0011 (rf2-cxpa87) — the in-flight work-ledger link
                    ;; carries the CANONICAL resource work-id TUPLE and the
                    ;; REAL non-terminal issuance status (`:running`, in
                    ;; `work-ledger/non-terminal-statuses`), matching the row
                    ;; `re-frame.resources.work-ledger/work-record` writes — not
                    ;; a scalar id / non-issuance `:pending`.
                    :work-ledger {:work/id k3-work-id
                                  :record  {:work/id k3-work-id :status :running
                                            :resource/key k3-scoped-key}}}})}
   ;; the LIVE route slice with its realized owner.
   :routes
   {:live-shape :node
    :static-fn  (constantly {})
    :live-fn    (constantly
                  {:id :rf/route :kind :process :refinement :route-fact
                   :route-id :route/article :params {:slug "a1"}
                   :nav-token k3-nav-token
                   :owner [:route :route/article k3-nav-token]
                   :output [:runtime [:rf.runtime/routing :current]]
                   :storage :runtime-db :evaluation :on-route :lifecycle :frame})}})

(deftest cplus-live-graph-realizes-non-route-nodes-and-edges
  (let [g     (graph/live-derivation-graph :rf/default (k3-live-contributors))
        nodes (:nodes g)
        edges (:edges g)]
    (testing "realized SUB nodes are wrapped by their CONCRETE query vector
              (the canonical live `[:sub q]` id — not the static bare-id form)"
      (is (contains? nodes [:sub [:article/page "a1"]])
          "the realized parametric sub is keyed by its concrete query vector")
      (is (contains? nodes [:sub [:article/by-slug "a1"]])
          "its concrete upstream sub is a node too"))
    (testing "the realized :input edge a parametric sub CANNOT enumerate
              statically is present in the live graph (live `[:sub q]` input
              resolves to the concrete upstream node id)"
      (is (some #(= % {:from [:sub [:article/by-slug "a1"]]
                       :to   [:sub [:article/page "a1"]]
                       :role :input})
                edges)
          "the realized :input edge from the concrete upstream sub"))
    (testing "the composed RESOURCE node is keyed by its concrete scoped key,
              carrying its live lifecycle owners + work-ledger"
      (let [res (get nodes [:resource k3-scoped-key])]
        (is (some? res) "the live resource node is present, scoped-key keyed")
        (is (= :process (:kind res)))
        (is (= #{[:route :route/article k3-nav-token]}
               (get-in res [:lifecycle :owners]))
            "the live owner set is composed through verbatim")
        ;; EP-0011 (rf2-cxpa87) — the composed work-ledger link carries the
        ;; CANONICAL resource work-id TUPLE (not a scalar), with the
        ;; `:rf.work/resource` family head, and the REAL non-terminal issuance
        ;; status. Assert the tuple head + status explicitly so a regression to
        ;; a scalar id / a non-issuance status (`:pending`) goes RED here.
        (is (= k3-work-id (get-in res [:work-ledger :work/id]))
            "the in-flight work-ledger link is the canonical work-id tuple, composed through")
        (is (= :rf.work/resource (first (get-in res [:work-ledger :work/id])))
            "the work-id tuple head is the :rf.work/resource family head (one-attempt-one-:work/id)")
        (is (= k3-scoped-key (second (get-in res [:work-ledger :work/id])))
            "the work-id tuple carries the concrete scoped-key")
        (is (= k3-generation (nth (get-in res [:work-ledger :work/id]) 2))
            "the work-id tuple embeds the attempt generation")
        (is (= k3-work-id (get-in res [:work-ledger :record :work/id]))
            "the work-ledger RECORD carries the same canonical work-id tuple")
        (is (= :running (get-in res [:work-ledger :record :status]))
            "the work-ledger record status is the real non-terminal issuance status :running (not :pending)")
        ;; The old SCALAR-`:work-id` spelling MUST be absent from the composed
        ;; node — Managed-Effects uses the `:work/id` (namespaced) key, never a
        ;; bare `:work-id`.
        (is (not (contains? (:work-ledger res) :work-id))
            "the composed work-ledger uses :work/id, never the old bare :work-id spelling")
        (is (not (contains? (get-in res [:work-ledger :record]) :work-id))
            "the work-ledger record uses :work/id, never the old bare :work-id spelling")))
    (testing "the REALIZED route-owned resource edge (rf2-k0meap.1) resolves
              the static :parametric marker into a concrete edge: live route
              → concrete [:resource scoped-key], :param role"
      (is (some #(= % {:from  :rf/route
                       :to    [:resource k3-scoped-key]
                       :role  :param
                       :owner [:route :route/article k3-nav-token]})
                edges)
          "the live route → concrete resource :param edge"))))

;; ===========================================================================
;; (d) WHOLE-VALUE — the semantic whole-value law (slice-1).
;; ===========================================================================

(deftest d-materialized-flow-output-equals-the-whole-value-recompute
  ;; Derivations §The whole-value law: a materialized derivation's output
  ;; path holds the SAME whole value its derivation fn computes from the same
  ;; inputs. The flow materializes `(sum-cart items)` into [:cart :total];
  ;; after seeding :cart/items, the app-db path MUST equal the whole-value
  ;; recompute of the SAME function over the SAME inputs.
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
  ;; The §Worked equivalence law: the subscription and the flow are the SAME
  ;; mathematical whole-value derivation, differing ONLY in policy (storage /
  ;; evaluation / lifecycle / output) — NOT in the function. The central
  ;; algebra claim: derive/materialize are policies over ONE dependency
  ;; graph.
  ;;
  ;; The semantic claim is verified by VALUE, not by function identity: a
  ;; subscription body that names `sum-cart` is wrapped by `reg-sub` into a
  ;; distinct `(fn [[items] _] (sum-cart items))` computation fn, so the two
  ;; `:derive` tokens are NOT `=`-identical objects through the registrar
  ;; (the token is opaque — Derivations §The node shape). What MUST hold is
  ;; that both compute the SAME whole value from the SAME inputs, and that
  ;; their algebra views diverge on EVERY policy axis.
  (register-one-of-each!)
  (let [nodes (:nodes (graph/derivation-graph all-contributors))
        sub   (node-by-family nodes :subs  :cart/total)
        flow  (node-by-family nodes :flows :cart/materialized-total)]
    (testing "both carry an opaque :derive whole-value token (never serialized)"
      (is (some? (:derive sub)))
      (is (some? (:derive flow))))
    (testing "they diverge on EVERY policy axis — the difference is policy, not function"
      (is (not= (:storage sub)    (:storage flow)) "ephemeral vs app-db")
      (is (not= (:evaluation sub) (:evaluation flow)) "on-demand vs after-event")
      (is (not= (:lifecycle sub)  (:lifecycle flow)) "cache-entry vs frame")
      (is (not= (:output sub)     (:output flow)) "[:fact …] vs [:db …]")
      (is (not= (:materialized? sub) (:materialized? flow)) "false vs true"))
    (testing "yet BOTH are derivations (the same superkind) and compute the
              SAME whole value from the same inputs"
      (is (= :derivation (:kind sub) (:kind flow)))
      ;; the flow's :derive IS sum-cart; the subscription's wraps it — both
      ;; yield the identical whole value over the identical inputs.
      (is (= ((:derive flow) cart-items) (sum-cart cart-items))
          "the flow's whole-value fn equals the shared sum-cart"))
    ;; rf2-djofbh — the whole-value law on the EPHEMERAL subscription PATH,
    ;; not just the flow's `:derive` token. The namespace doc claims the suite
    ;; proves "an EPHEMERAL derivation's read value equals the whole-value
    ;; recompute"; the prior arms only evaluated the flow's `(:derive flow)`
    ;; token and never READ the subscription. Seed :cart/items, then read the
    ;; ephemeral `:cart/total` subscription through the real reactive path and
    ;; assert its value equals `(sum-cart cart-items)` — the whole-value
    ;; recompute over the same inputs (Derivations §The whole-value law,
    ;; applied to the ephemeral output exactly as `d-materialized-flow-…`
    ;; applies it to the materialized output).
    (testing "the EPHEMERAL subscription's read value equals the whole-value recompute"
      (rf/dispatch-sync [::seed-cart cart-items])
      (is (= (sum-cart cart-items) @(rf/subscribe [:cart/total]))
          "reading @(rf/subscribe [:cart/total]) after seeding equals (sum-cart cart-items) — the ephemeral whole-value law"))))

(deftest d-delta-law-is-semantic-only-no-delta-support-still-conforms
  ;; Derivations §The optional delta law: slice-1 ships NO executable delta
  ;; protocol (EP-0014 bead-plan item 10 defers it). The conformance rule
  ;; that survives now: an implementation with NO delta support still
  ;; conforms — NO node carries an executable `:step-delta`. (When deltas
  ;; ever ship, the commuting law against whole-value recompute applies.)
  (register-one-of-each!)
  (let [nodes (:nodes (graph/derivation-graph all-contributors))]
    (doseq [[node-id node] nodes]
      (is (not (contains? node :step-delta))
          (str node-id " carries a :step-delta — slice-1 ships no delta protocol")))))

;; ===========================================================================
;; (e) LIFECYCLE — the §Conformance 'Lifecycle' bullet's RELEASE axis
;;     (rf2-pomhpf). Every prior arm (the per-family suites + the (c) live
;;     arm) only ever reads a PRESENT owner — positive presence. The lifecycle
;;     classification exists for the RELEASE boundary: "destroying a frame
;;     releases frame-owned graph nodes …; route exit releases route owners;
;;     machine destroy releases machine-owned leases and timers" (§Lifecycle
;;     and owner). These tests drive the teardown TRANSITION and assert the
;;     node / owner has LEFT the live graph — the strongest adversarial arm,
;;     a frame/route/machine teardown vacating the graph.
;; ===========================================================================

(deftest e-destroying-a-frame-releases-its-frame-owned-graph-nodes
  ;; §Conformance Lifecycle, clause 1 ("destroying a frame releases
  ;; frame-owned graph nodes and host-transient state") + §Lifecycle and
  ;; owner (`:frame` lifecycle — `destroy-frame!` releases it). Materialize a
  ;; route slice (a `:frame`-lifecycle process owned by the frame) AND a live
  ;; machine snapshot in a dedicated frame, confirm BOTH are present in that
  ;; frame's live graph, then `destroy-frame!` and assert the frame's live
  ;; graph is empty — every frame-owned node released.
  (register-one-of-each!)
  (rf/reg-frame :checkout/frame {:doc "a frame to destroy"})
  ;; Materialize frame-owned facts: a committed route slice + a live singleton
  ;; machine snapshot, both in :checkout/frame.
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "welcome"}]
                    {:frame :checkout/frame})
  (rf/dispatch-sync [:upload/main [:upload/start]] {:frame :checkout/frame})
  (let [g-before (graph/live-derivation-graph :checkout/frame all-contributors)
        nodes    (:nodes g-before)]
    ;; The frame-owned facts commit SYNCHRONOUSLY under the plain-atom
    ;; substrate this suite installs (the route navigation + the machine
    ;; start both settle in their `dispatch-sync`), so their PRESENCE before
    ;; teardown is a FAILING PRECONDITION (rf2-djofbh): if the live graph
    ;; stops materializing the route / machine nodes after the setup
    ;; dispatches, this test MUST fail rather than vacuously pass by guarding
    ;; the release assertions behind the nodes' presence. The earlier
    ;; `(when (and (contains? nodes :rf/route) (contains? nodes [:machine …])) …)`
    ;; masked exactly that — a setup regression would skip the whole arm.
    (testing "the setup dispatches materialized BOTH frame-owned nodes (failing precondition)"
      (is (contains? nodes :rf/route)
          "the navigation MUST materialize the :rf/route live node — absence is a failure, not a skip")
      (is (contains? nodes [:machine :upload/main])
          "the machine start MUST materialize the [:machine :upload/main] live snapshot node"))
    (testing "the route slice (a :frame-lifecycle node) is owned by the frame"
      (let [slice (get nodes :rf/route)]
        (is (= :frame (:lifecycle slice)))
        (is (= [:route :route/article (:nav-token slice)] (:owner slice)))))
    (frame/destroy-frame! :checkout/frame)
    (let [g-after (graph/live-derivation-graph :checkout/frame all-contributors)]
      (testing "destroy-frame! releases EVERY frame-owned graph node"
        (is (= :live (:mode g-after)) "the live graph shape survives a destroyed frame")
        (is (= {} (:nodes g-after))
            "no node survives the frame teardown — the route slice + machine snapshot are gone")
        (is (= [] (:edges g-after)) "and no edge survives")
        (is (nil? (get (:nodes g-after) :rf/route))
            "the route owner is released (its frame is gone)")
        (is (nil? (get (:nodes g-after) [:machine :upload/main]))
            "the machine snapshot node is released (its frame is gone)")))))

(deftest e-route-exit-supersession-releases-the-prior-route-owner
  ;; §Conformance Lifecycle, clause 3 ("route exit releases route owners") +
  ;; §Lifecycle and owner (`:route` lifecycle — "route exit or supersession
  ;; releases it"). Navigate to route A (the live slice owner is
  ;; `[:route :route/article nav-token-A]`), then navigate to route B. The
  ;; ONE materialized route slice is superseded: its owner is now route B
  ;; under a FRESH nav-token, and the route-A owner identity is no longer the
  ;; live owner (released by supersession).
  (register-one-of-each!)
  (rf/reg-route :route/about {} "/about")
  (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "welcome"}])
  (let [g-a   (graph/live-derivation-graph :rf/default all-contributors)
        slice (get (:nodes g-a) :rf/route)]
    ;; The route-A slice commits SYNCHRONOUSLY under the plain-atom substrate
    ;; this suite installs, so its presence is a FAILING PRECONDITION
    ;; (rf2-djofbh): the supersession arm must not vacuously pass by guarding
    ;; the pre/post checks behind the slices' presence. The earlier
    ;; `(when (some? slice) …)` / `(when (some? slice-b) …)` masked a setup
    ;; regression — a route node that stopped materializing would skip the
    ;; whole supersession-release law.
    (testing "the first navigation materialized the route-A slice (failing precondition)"
      (is (some? slice)
          "navigating to route A MUST materialize the :rf/route live node — absence is a failure, not a skip"))
    (let [owner-a     (:owner slice)
          nav-token-a (:nav-token slice)]
      (testing "before supersession the live owner is route A under nav-token-A"
        (is (= :route/article (:route-id slice)))
        (is (= [:route :route/article nav-token-a] owner-a)))
      ;; Supersede: a second navigation commits a new slice.
      (rf/dispatch-sync [:rf.route/navigate :route/about {}])
      (let [g-b      (graph/live-derivation-graph :rf/default all-contributors)
            slice-b  (get (:nodes g-b) :rf/route)]
        (testing "the superseding navigation materialized the route-B slice (failing precondition)"
          (is (some? slice-b)
              "navigating to route B MUST materialize the superseding :rf/route live node — absence is a failure"))
        (testing "the superseding navigation releases the prior route owner"
          (is (= :route/about (:route-id slice-b))
              "the live slice now reports route B (the prior route fact is exited)")
          (is (not= owner-a (:owner slice-b))
              "the route-A owner identity is no longer the live owner — released by supersession")
          (is (not= nav-token-a (:nav-token slice-b))
              "a fresh nav-token owns the new route — the prior token's lease is gone")
          (is (= [:route :route/about (:nav-token slice-b)] (:owner slice-b))
              "the live owner is now [:route route-B fresh-nav-token]"))))))

(deftest e-machine-destroy-releases-the-machine-owned-snapshot-node
  ;; §Conformance Lifecycle, clause 4 ("machine destroy releases
  ;; machine-owned leases and timers") + §Lifecycle and owner
  ;; (`:machine-instance` lifecycle — "machine destroy releases it"). A
  ;; machine that transitions into a `:final?` state auto-destroys (Spec 005
  ;; §Final state — the XState-aligned actor-done boundary). Materialize the
  ;; instance (its `:machine-instance`-owned snapshot node is live in the
  ;; graph), drive it to its final state, and assert the snapshot node has
  ;; left the live graph — the machine-owned node released.
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
  ;; PRE-TEARDOWN materialization is a FAILING PRECONDITION (rf2-mp1p28):
  ;; the original test only asserted ABSENCE after the final-state
  ;; auto-destroy, so "absent after destroy" was indistinguishable from
  ;; "never present" — a regression where `:job/runner` never materializes a
  ;; live snapshot, or where the live graph stops surfacing machine
  ;; instances, would still pass vacuously. Prove a LIVE instance existed
  ;; (spec/Derivations.md §`:machine-instance` lifecycle: released by machine
  ;; destroy) BEFORE driving the machine to its final state.
  (let [g-before    (graph/live-derivation-graph :rf/default all-contributors)
        node-before (get (:nodes g-before) [:machine :job/runner])
        rt-before   (frame/frame-runtime-db-value :rf/default)]
    (testing "the machine instance materialized a live snapshot BEFORE destroy (failing precondition)"
      (is (some? node-before)
          "the no-op dispatch MUST materialize the [:machine :job/runner] live snapshot node — absence is a failure, not a skip")
      (is (some? (get-in rt-before (machine-paths/snapshot-path :job/runner)))
          "the machine-owned snapshot MUST be live in runtime-db before the final event — proving a live instance existed to release")))
  (rf/dispatch-sync [:job/runner [:job/finish]])      ;; → :done (:final?) → auto-destroy
  (let [g-after (graph/live-derivation-graph :rf/default all-contributors)
        node    (get (:nodes g-after) [:machine :job/runner])
        rt      (frame/frame-runtime-db-value :rf/default)]
    (testing "the machine instance node is released from the live graph on destroy"
      (is (nil? node)
          "the :machine-instance-owned snapshot node is gone after final-state auto-destroy")
      (is (nil? (get-in rt (machine-paths/snapshot-path :job/runner)))
          "the machine-owned snapshot is released from runtime-db (machine destroy releases its leases)"))))

;; ===========================================================================
;; (f) EVALUATION POLICY — the §Conformance 'Evaluation policy' bullet's
;;     on-demand-no-write law (rf2-qdxvkb; §Evaluation policy rule 1:
;;     "`:on-demand` derivations MUST NOT cause durable state changes merely
;;     because a view read them. (Reading a resource selector does not start
;;     resource work.)"). The (d) laws prove the MATERIALIZED path's whole
;;     value; this proves the EPHEMERAL/on-demand path's NEGATIVE no-write
;;     property — neither app-db nor runtime-db (so no work-ledger record, no
;;     cache mutation) changes because a reader read an `:on-demand` node.
;; ===========================================================================

(deftest f-reading-an-on-demand-node-causes-no-durable-write
  ;; The on-demand-no-write invariant proven over BOTH `:on-demand` algebra
  ;; node forms: an ordinary subscription (a `:derivation`/`:ephemeral`/
  ;; `:on-demand`/`:subscription-cache-entry` node) AND a resource
  ;; `:rf.resource/state` selector (the read-fact projection of a `:process`
  ;; node — "reading a selector does not start resource work"). Snapshot the
  ;; durable frame-state (app-db + runtime-db) before and after reading them;
  ;; the two partitions MUST be unchanged. The work-ledger lives at
  ;; `[:rf.runtime/work-ledger …]` in runtime-db, so an unchanged runtime-db
  ;; subsumes "no work record was created."
  (register-one-of-each!)
  ;; First, pin the algebra classification the read is exercising: the sub is
  ;; the canonical :on-demand node; the resource selectors are the
  ;; read-fact surface of an :on-demand-readable process.
  (let [nodes (:nodes (graph/derivation-graph all-contributors))
        sub   (node-by-family nodes :subs      :cart/items)
        res   (node-by-family nodes :resources :article/by-slug)]
    (is (= :on-demand (:evaluation sub))
        "the subscription is the canonical :on-demand node whose read must be write-free")
    (is (contains? (set (:selectors res)) :rf.resource/state)
        "the resource exposes the :rf.resource/state read selector"))
  ;; Snapshot BOTH durable partitions, read the on-demand nodes, re-snapshot.
  (let [app-before (frame/frame-app-db-value :rf/default)
        rt-before  (frame/frame-runtime-db-value :rf/default)
        ;; Read the ordinary subscription.
        sub-val    @(rf/subscribe [:cart/items])
        ;; Read the resource selector for an UNMATERIALIZED key — the idle
        ;; empty-state projection. Reading it MUST NOT start work or write a
        ;; cache entry / work-ledger record.
        sel-val    @(rf/subscribe [:rf.resource/state
                                   {:resource :article/by-slug :params {:slug "welcome"}}])
        app-after  (frame/frame-app-db-value :rf/default)
        rt-after   (frame/frame-runtime-db-value :rf/default)]
    (testing "the reads return values (the reads actually happened)"
      ;; sub-val may be nil (no cart seeded) — the point is the read ran.
      (is (= :idle (:status sel-val))
          "reading the selector for an unmaterialized key yields the idle empty-state"))
    (testing "neither durable partition changed merely because a reader read"
      (is (= app-before app-after)
          "app-db is byte-for-byte unchanged after on-demand reads (rule 1)")
      (is (= rt-before rt-after)
          "runtime-db is byte-for-byte unchanged — no cache entry, no work-ledger record"))
    (testing "specifically, no work-ledger record was created by the selector read"
      (is (= (:rf.runtime/work-ledger rt-before) (:rf.runtime/work-ledger rt-after))
          "reading a resource selector starts no resource work (§Evaluation policy rule 1)"))))

;; ===========================================================================
;; (g) TOOL REDACTION — the §Conformance 'Tool redaction' law over OFF-BOX
;;     GRAPH EGRESS (rf2-zt5j96 / rf2-iormgq; EP-0015 + EP-0017; spec/
;;     Derivations.md §Conformance 'Tool redaction' + §Redaction metadata).
;;
;; Every prior arm reads the RAW on-box graph the composer
;; (`re-frame.derivation.graph`) assembles — correct-as-designed
;; (the EP-0014 tail-2 ruling, rf2-6y7wnb: the composer is raw-on-box;
;; redaction is an EGRESS concern owned by the frame elision policy via the
;; shared `rf/elide-wire-value` walker, applied at the WIRE BOUNDARY where a
;; consuming tool ships the graph OFF-BOX, NOT at the registrar-derived
;; composer). So a passing conformance run that NEVER projects the graph
;; through an egress boundary cannot catch a raw sensitive scope / params /
;; derived value leaving through an identity-embedded graph position, nor a
;; projection that drops graph connectivity. These arms close that gap.
;;
;; ## Where the egress boundary lives, and why this surface re-composes it
;;
;; The graph-level egress CALL SITE is BORN in the named first consumer Xray
;; (`day8.re-frame2-xray.panels.derivation-graph-helpers/redact-graph-for-egress`)
;; — a `tools/` artefact this cross-family conformance surface MUST NOT
;; `:require` (tools/README.md bundle isolation: the dependency arrow flows
;; conformance → implementation, never conformance → tools). But that call
;; site is built ENTIRELY from `implementation/`-resident primitives:
;; `rf/elide-wire-value` (the single shared per-frame fail-closed value
;; walker, EP-0015 §11) for value-bearing node fields, and
;; `identity/canonical-bytes` (the CEDN-1 canonical token, EP-0012) hashed
;; into a stable opaque handle for the identity-embedded scoped key the
;; value-path walk is structurally blind to. So these arms re-compose the
;; SAME egress projection from those in-tree primitives and run it over the
;; REAL composer output (`graph/live-derivation-graph` over contributors that
;; return the realized sensitive shapes) — proving the LAW holds against the
;; actual assembled graph, with no tool reach. `egress-project-graph` below
;; is the conformance surface's faithful mirror of the Xray call site's
;; semantics; if the law it asserts ever diverges from the tool, the Xray
;; redaction suite (`derivation-graph-redaction-cljs-test`) is the per-tool
;; pin and this is the cross-family pin — two independent witnesses to one
;; law.
;; ===========================================================================

;; ---- the in-tree egress projection (mirrors the Xray call site) ----------

(defn- already-projected-handle?
  "True when `v` is ALREADY an egress-projected handle — an opaque
  `[:rf.resource/opaque <digest>]` token or the `:rf/redacted` fail-closed
  sentinel. Re-projecting such a value MUST be the identity (idempotence):
  the conformance mirror models the SAME forwarder-pipeline contract the Xray
  call site does — a value may egress more than once, and hashing an
  already-projected handle would mint a NEW handle and silently change the
  live node identity across the boundary."
  [v]
  (or (= v privacy/redacted-sentinel)
      (and (vector? v)
           (= 2 (count v))
           (= :rf.resource/opaque (nth v 0)))))

(defn- opaque-scoped-key-handle
  "A STABLE, ONE-WAY opaque handle for one secret-bearing scoped-key
  component — the `implementation/`-resident analogue of the Xray
  `opaque-handle`. Deterministic from the value (same value ⇒ same handle,
  so connectivity survives) but IRREVERSIBLE: a HASH of the value's CEDN-1
  canonical token (`identity/canonical-bytes`), never the token itself.
  FAILS CLOSED to the `:rf/redacted` sentinel for any value outside the
  CEDN-1 identity domain or on any error — never host-stringify a secret
  onto the wire. IDEMPOTENT: an already-projected `[:rf.resource/opaque …]`
  handle / `:rf/redacted` sentinel is returned UNCHANGED (hashing it again
  would mint a fresh, DIFFERENT handle on a second egress pass)."
  [v]
  (if (already-projected-handle? v)
    v
    (try
      (let [token  (identity/canonical-bytes v)
            digest #?(:clj  (Integer/toHexString (hash token))
                      :cljs (.toString (bit-and (hash token) 0xffffffff) 16))]
        [:rf.resource/opaque digest])
      (catch #?(:clj Throwable :cljs :default) _
        privacy/redacted-sentinel))))

(defn- scoped-resource-key?
  "True when `v` is a live resource SCOPED KEY — a 3-tuple
  `[cache-scope resource-id canonical-params]` (the resource's concrete live
  fact identity, Derivations §Fact identity): the MIDDLE element is the
  registration `resource-id` keyword and the LAST is the canonical-params
  MAP. A static resource node's `:id` is a bare keyword (not this shape), so
  only LIVE resource identities match; an already-projected scoped key keeps
  the shape, so re-projection is well-defined."
  [v]
  (and (vector? v)
       (= 3 (count v))
       (keyword? (nth v 1))
       (map? (nth v 2))))

(defn- project-scoped-key
  "Project a live resource scoped key `[scope resource-id params]` into its
  egress form `[<scope-handle> resource-id <params-handle>]` — the scope and
  params replaced by stable opaque handles, the registration `resource-id`
  PRESERVED (a tool still sees WHICH resource). Idempotent."
  [[scope resource-id params]]
  [(opaque-scoped-key-handle scope) resource-id (opaque-scoped-key-handle params)])

(defn- project-identity-in-path
  "Replace any live resource scoped key embedded in `path` (e.g. the
  `:output` runtime path `[:runtime [:rf.runtime/resources :entries
  <scoped-key>]]`) with its projected form, so the secret-bearing scoped key
  never egresses through a structure position."
  [path]
  (if (sequential? path)
    (mapv (fn [el]
            (cond
              (scoped-resource-key? el) (project-scoped-key el)
              (sequential? el)          (project-identity-in-path el)
              :else                     el))
          path)
    path))

(defn- project-resource-inputs
  "Project a live resource node's realized `:inputs`
  `[[:scope <scope>] [:param <params>]]` — opaque the `[:scope …]` /
  `[:param …]` payloads (the realized scope + params edges carry the same
  sensitive identity). Other input shapes ride through untouched. Idempotent:
  the payload runs through `opaque-scoped-key-handle`, which returns an
  already-projected handle / `:rf/redacted` UNCHANGED, so re-projecting an
  already-projected inputs vector is the identity (rf2-g197ep — this is the
  one input position projected unconditionally rather than gated by the
  scoped-key shape, so its idempotence MUST come from the handle minter)."
  [inputs]
  (if (sequential? inputs)
    (mapv (fn [in]
            (if (and (vector? in) (= 2 (count in)) (#{:scope :param} (first in)))
              [(first in) (opaque-scoped-key-handle (second in))]
              in))
          inputs)
    inputs))

(defn- project-resource-node-identity
  "Project ONE live resource node's secret-bearing IDENTITY fields — the
  positions the value-path `rf/elide-wire-value` walk is structurally blind
  to: `:id` (the scoped key), `:output` (the scoped key embedded in the
  runtime path), the realized `:inputs` `[:scope …]` / `[:param …]` payloads,
  and `:work-ledger :record :resource/key`. Structure / classification fields
  are untouched."
  [node]
  (cond-> node
    (scoped-resource-key? (:id node))
    (update :id project-scoped-key)

    (contains? node :output)
    (update :output project-identity-in-path)

    (contains? node :inputs)
    (update :inputs project-resource-inputs)

    (scoped-resource-key? (get-in node [:work-ledger :record :resource/key]))
    (update-in [:work-ledger :record :resource/key] project-scoped-key)))

(defn- resource-node-key?
  "True when `node-key` is a LIVE resource node id `[:resource <scoped-key>]`
  whose scoped key embeds a secret-bearing scope/params. A static resource
  node key `[:resource <bare-keyword>]` does NOT match."
  [node-key]
  (and (vector? node-key)
       (= :resource (first node-key))
       (scoped-resource-key? (second node-key))))

(defn- project-resource-node-key
  "Project a live resource node KEY `[:resource <scoped-key>]` to
  `[:resource <projected-scoped-key>]`; other node keys ride through."
  [node-key]
  (if (resource-node-key? node-key)
    [:resource (project-scoped-key (second node-key))]
    node-key))

(def ^:private value-bearing-node-keys
  "The value-bearing summary fields the egress walk runs through
  `rf/elide-wire-value` under the frame's policy (Xray's
  `value-bearing-node-keys`). Identity-embedded positions are handled by the
  scoped-key projection, not this value walk."
  [:value :params :query :state])

(def ^:private dead-egress-frame-sentinel
  "A frame id that can NEVER resolve to a live frame — the conformance
  mirror's fail-closed stamp for a nil / unreachable egress frame, matching
  the Xray helper's `dead-frame-sentinel`. Stamped as the `:frame` opt so
  `rf/elide-wire-value` takes its unresolvable-frame branch (whole value ⇒
  `:rf/redacted`) instead of resolving the ambient bound frame."
  ::no-egress-frame)

(defn- egress-project-graph
  "Project a `DerivationGraph` through `frame-id`'s egress policy for the
  off-box wire boundary — the conformance surface's faithful mirror of the
  Xray `redact-graph-for-egress` call site, built from `implementation/`
  primitives only (no tools reach).

    - value-bearing node fields → `rf/elide-wire-value` under the named
      frame's own policy (passed as the `:frame` opt so it applies THAT
      frame's classification regardless of any ambient scope);
    - FAIL-CLOSED on a nil / UNREACHABLE frame — `rf/elide-wire-value`
      resolves its governing frame as `(or (:frame opts)
      (frame/resolve-current-frame))`, so a nil `:frame` opt falls through to
      the AMBIENT dynamically-bound frame and would ship the value RAW under
      its (here `:rf/default`, empty) policy. A NON-nil but unreachable
      `:frame` opt instead takes the unresolvable-frame fail-closed branch
      (rf2-t55hxg.18: a frame-id alone is not policy-bearing — it must
      resolve to a live frame via `frame/frame`, else the walker fails closed
      to `:rf/redacted`). So when `frame-id` is nil or names no LIVE frame we
      stamp a DEAD-FRAME SENTINEL as the `:frame` opt — a non-nil id that can
      never resolve — so the walker takes its dead-frame fail-closed branch
      rather than resolving an AMBIENT frame (which, unlike Xray's
      `:ambient-frame nil` harness, this cross-family fixture binds to
      `:rf/default`). A nil / absent `:frame` would let the frameless walk
      resolve to that ambient frame and ship the value RAW (rf2-udkj69);
    - identity-embedded scoped keys (node KEY, `:id`, `:output`, `:inputs`,
      `:work-ledger :record :resource/key`, AND every edge endpoint) →
      stable opaque handles, the SAME projection applied consistently so the
      remap keeps connectivity.

  `:mode` / `:frame` unchanged; STRUCTURE preserved (a redacted value/param
  is still an edge; the node is still present + classified)."
  [graph frame-id]
  ;; Carry a NON-nil `:frame` opt so `rf/elide-wire-value` never falls through
  ;; to the ambient frame. A LIVE frame applies its own policy; a nil /
  ;; unreachable frame stamps the dead-frame sentinel so the walker takes its
  ;; unresolvable-frame fail-closed branch (redact whole) — NEVER resolving
  ;; the ambient `:rf/default` this fixture binds (rf2-udkj69).
  (let [reachable? (and (some? frame-id) (contains? (rf/frame-ids) frame-id))
        walk-opts  {:frame (if reachable? frame-id dead-egress-frame-sentinel)}
        redact-node
        (fn [node]
          (-> (reduce
                (fn [n k]
                  (if (contains? n k)
                    (assoc n k (rf/elide-wire-value (get n k) walk-opts))
                    n))
                node
                value-bearing-node-keys)
              project-resource-node-identity))]
    (-> graph
        (update :nodes (fn [nodes]
                         (into {}
                               (map (fn [[k node]]
                                      [(project-resource-node-key k)
                                       (redact-node node)]))
                               nodes)))
        (update :edges (fn [edges]
                         (mapv (fn [edge]
                                 (cond-> edge
                                   (resource-node-key? (:from edge))
                                   (update :from project-resource-node-key)
                                   (resource-node-key? (:to edge))
                                   (update :to project-resource-node-key)))
                               (or edges [])))))))

;; ---- (g) resource identity egress redaction (rf2-zt5j96) -----------------
;;
;; The umbrella OFF-BOX graph-egress arm the conformance gap names: a live
;; resource node carries its sensitive scope/params NOT in a value-bearing
;; field but in its IDENTITY — the concrete scoped key
;; `[cache-scope resource-id canonical-params]` that is simultaneously the
;; node KEY, the `:id`, the `:output` runtime-path tail, the realized
;; `:inputs` `[:scope …]` / `[:param …]`, the `:work-ledger :record
;; :resource/key`, AND the edge endpoints naming it. The §1-6 arms above pin
;; the RAW composed graph (`cplus-live-graph-realizes-non-route-nodes-and-edges`
;; pins raw scoped keys at exactly these positions); this arm projects that
;; raw graph through the shared egress boundary and proves no raw secret
;; survives anywhere while the registration resource-id stays visible and the
;; graph stays connected.

(def ^:private egress-frame :app/egress-secure)

(def ^:private secret-token "tenant-jwt-9f3a-SECRET")
(def ^:private secret-scope  [:rf.scope/tenant secret-token])
(def ^:private secret-params {:slug "welcome" :auth-token secret-token})
(def ^:private egress-scoped-key
  ;; [cache-scope resource-id canonical-params] — the live fact identity.
  [secret-scope :article/by-slug secret-params])
(def ^:private egress-nav-token 23)

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
                    :work-ledger {:work/id 99
                                  :record  {:work/id      99
                                            :status       :pending
                                            :resource/key egress-scoped-key}}}})}
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
  "Deep-walk `v` and return true iff the raw secret token string appears
  ANYWHERE (a leaf, a key, inside a scope/params, a work-ledger record, an
  :output path — anywhere). Strict boolean."
  [v]
  (boolean
    (cond
      (= v secret-token) true
      (map? v)           (some contains-secret? (concat (keys v) (vals v)))
      (coll? v)          (some contains-secret? v)
      :else              false)))

(defn- projected-scoped-key?
  "True when `v` keeps the 3-tuple scoped-key SHAPE after egress projection —
  `[<scope-handle> resource-id <params-handle>]`: the registration
  resource-id keyword is PRESERVED in the middle, scope + params replaced by
  opaque handles (structure preserved, secrets withheld)."
  [v]
  (and (vector? v)
       (= 3 (count v))
       (keyword? (nth v 1))))

(deftest g-live-resource-identity-redacted-at-graph-egress
  ;; A sensitive scope/params fixture, projected through the shared graph
  ;; egress boundary, asserting NO raw secret survives anywhere, the
  ;; non-sensitive resource id remains visible, all identity positions use the
  ;; SAME stable opaque scoped key, and edges still connect.
  (rf/reg-frame egress-frame {:doc "off-box egress conformance frame"})
  (let [raw      (graph/live-derivation-graph egress-frame (egress-live-contributors))
        redacted (egress-project-graph raw egress-frame)]

    (testing "PRECONDITION — the composer assembled the live sensitive resource
              node + its route-owned activation edge over the concrete scoped
              key (the raw on-box graph DOES carry the secret)"
      (is (contains? (:nodes raw) [:resource egress-scoped-key])
          "the live resource node is keyed by the raw sensitive scoped key")
      (is (contains-secret? raw)
          "sanity: the raw composed graph carries the secret token"))

    (testing "(a) NO raw secret token survives ANYWHERE in the egressed graph —
              not in the node key, :id, :inputs, :output, work-ledger, or edges"
      (is (not (contains-secret? redacted))
          "the secret must NOT appear anywhere in the off-box graph"))

    (testing "(b) the non-sensitive resource id remains VISIBLE and every
              identity position uses the SAME stable opaque scoped key"
      (let [node          (-> redacted :nodes vals first)
            node-key      (-> redacted :nodes keys first)
            key-scoped    (second node-key)
            id-scoped     (:id node)
            output-scoped (last (second (:output node)))
            ledger-scoped (get-in node [:work-ledger :record :resource/key])]
        (is (= :resource (first node-key)) "still a :resource node key")
        (is (projected-scoped-key? key-scoped) "still a 3-tuple scoped-key shape")
        (is (= :article/by-slug (nth key-scoped 1))
            "the registration resource-id is PRESERVED (a tool sees WHICH resource)")
        (is (not= secret-scope (nth key-scoped 0)) "the scope component is opaqued")
        (is (not= secret-params (nth key-scoped 2)) "the params component is opaqued")
        (is (= key-scoped id-scoped output-scoped ledger-scoped)
            "ALL identity positions (node key, :id, :output, work-ledger) project
             to the SAME stable opaque scoped key — one fact, one identity")))

    (testing "(c) graph CONNECTIVITY survives — the node is still present +
              classified, and the route-owned edge naming it still connects to
              the (consistently remapped) projected node key"
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
            "the edge :to is remapped to the SAME projected node key (consistent
             remap — connectivity preserved)")))

    (testing "(d) the realized :inputs scope/params, the :output entry path,
              and the work-ledger :resource/key keep their structural shape but
              carry no raw secret"
      (let [node (-> redacted :nodes vals first)]
        (is (= [:scope :param] (mapv first (:inputs node)))
            "the [:scope …] / [:param …] input roles survive")
        (is (not= secret-scope  (second (first (:inputs node)))))
        (is (not= secret-params (second (second (:inputs node)))))
        (is (= :runtime (first (:output node))) ":output is still a runtime address")
        (is (= [:rf.runtime/resources :entries] (take 2 (second (:output node))))
            "the :output runtime path prefix survives")
        (let [rec (get-in node [:work-ledger :record])]
          (is (= 99 (:work/id rec)) "the non-secret work id rides through")
          (is (= :pending (:status rec)))
          (is (projected-scoped-key? (:resource/key rec))
              "the work-ledger :resource/key keeps the scoped-key shape"))))))

(deftest g-graph-egress-for-unknown-frame-fails-closed
  ;; The §Conformance fail-closed clause for the GRAPH egress boundary:
  ;; projecting under an UNKNOWN / never-registered frame must not ship value
  ;; fields raw under no policy. The value-path walk fails closed to
  ;; :rf/redacted; the identity projection is frame-INDEPENDENT (it is a
  ;; one-way hash, never policy-bearing) so it still opaques the scoped key.
  (rf/reg-frame egress-frame {})
  ;; EP-0025: classify [:cart :items] sensitive via the commit-plane effect
  ;; path (:source :effect) — durable app-db classification is no longer a
  ;; frame annotation.
  (frame/swap-runtime-db! egress-frame
    (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:cart :items]]})))
  ;; a value-bearing live sub node at a frame-sensitive path, alongside the
  ;; sensitive resource identity.
  (let [contributors
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
                                :value   {:cart {:items secret-token}}}})})
        raw (graph/live-derivation-graph egress-frame contributors)]
    (testing "PRECONDITION — the raw graph carries the secret in a value field
              AND in the resource identity"
      (is (contains-secret? raw)))
    (testing "egress under an UNKNOWN frame fails closed: the value-bearing
              field is redacted to :rf/redacted (no reachable policy ⇒ no raw
              ship), and the identity scoped key is opaqued — NO secret survives"
      (let [redacted (egress-project-graph raw :app/does-not-exist)
            sub      (get-in redacted [:nodes [:sub [:cart/items]]])]
        (is (= privacy/redacted-sentinel (:value sub))
            "the whole value-bearing field is redacted under no reachable policy")
        (is (not (contains-secret? redacted))
            "no raw secret survives — value-path fail-closed + frame-independent
             identity projection cover both leak channels")))
    (testing "egress under a NIL frame fails closed EVEN UNDER an AMBIENT bound
              frame — it must NOT borrow the ambient frame's policy and ship the
              value raw (rf2-udkj69). The fixture binds ambient :rf/default
              (empty policy); we bind it explicitly here so the regression is
              self-contained. A nil frame-id MUST redact the value-bearing field,
              not resolve :rf/default and pass [:cart :items] through raw."
      (rf/with-frame :rf/default
        (is (some? (frame/resolve-current-frame))
            "PRECONDITION — an ambient frame IS dynamically bound, so a frameless
             walk WOULD resolve it (the borrow this arm forbids)")
        (let [redacted (egress-project-graph raw nil)
              sub      (get-in redacted [:nodes [:sub [:cart/items]]])]
          (is (= privacy/redacted-sentinel (:value sub))
              "nil frame ⇒ the whole value-bearing field is redacted, NOT shipped
               raw under the borrowed ambient :rf/default empty policy")
          (is (not (contains-secret? redacted))
              "no raw secret survives a nil-frame egress under an ambient binding"))))
    (testing "egress under the KNOWN frame redacts the frame-declared sensitive
              value path while keeping the structure"
      (let [redacted (egress-project-graph raw egress-frame)
            sub      (get-in redacted [:nodes [:sub [:cart/items]]])]
        (is (= privacy/redacted-sentinel (get-in sub [:value :cart :items]))
            "the frame-declared sensitive [:cart :items] leaf is redacted")
        (is (= :derivation (:kind sub)) "the sub node is still present + classified")
        (is (not (contains-secret? redacted)))))))

(deftest g-graph-egress-is-idempotent
  ;; A forwarder pipeline that double-projects must not corrupt the graph: a
  ;; value may egress MORE THAN ONCE (re-egress on re-render / re-subscribe /
  ;; cascade), so the projection MUST be the identity on an already-projected
  ;; graph — `project(project(x)) == project(x)`. The opaque handle of an
  ;; already-opaque handle is the SAME handle (not a fresh handle-of-handle),
  ;; and the `:rf/redacted` sentinel re-projects to itself.
  ;;
  ;; rf2-g197ep: the prior assertion checked ONLY node-key set + `:id` + no-raw-
  ;; secret. That MISSED the real non-idempotence: `:id`/node-keys are stable
  ;; only because a projected scoped key (`[<handle> id <handle>]`) no longer
  ;; matches `scoped-resource-key?` (its tail is an opaque VECTOR, not a map),
  ;; so they are not re-projected — but the realized `:inputs`
  ;; `[:scope …]`/`[:param …]` payloads were re-hashed UNCONDITIONALLY, minting
  ;; fresh handles on the second pass. Full graph equality is the assertion
  ;; that catches it; it FAILS against the pre-fix unconditional re-hash.
  (rf/reg-frame egress-frame {})
  (let [raw   (graph/live-derivation-graph egress-frame (egress-live-contributors))
        once  (egress-project-graph raw egress-frame)
        twice (egress-project-graph once egress-frame)
        node1 (-> once :nodes vals first)
        node2 (-> twice :nodes vals first)]
    (is (not (contains-secret? twice)) "still no secret after double projection")
    ;; The headline idempotence law: a second egress pass changes NOTHING.
    (is (= once twice)
        "egress is idempotent — re-projecting an already-projected graph is the
         identity (project(project(x)) == project(x))")
    ;; Spelled-out witnesses so a regression reports WHICH position drifted.
    (is (= (set (keys (:nodes once))) (set (keys (:nodes twice))))
        "node keys are stable under re-projection")
    (is (= (:id node1) (:id node2))
        "the projected scoped-key :id is stable under re-projection")
    (is (= (:inputs node1) (:inputs node2))
        "the projected realized :inputs ([:scope …]/[:param …]) are stable — a
         second pass must NOT re-hash the opaque handles into fresh handles")
    (is (= (:output node1) (:output node2))
        "the projected :output runtime path is stable under re-projection")
    (is (= (get-in node1 [:work-ledger :record :resource/key])
           (get-in node2 [:work-ledger :record :resource/key]))
        "the projected work-ledger :resource/key is stable under re-projection")))
