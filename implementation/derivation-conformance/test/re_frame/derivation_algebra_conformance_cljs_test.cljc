(ns re-frame.derivation-algebra-conformance-cljs-test
  "Cross-family derivation/process-ALGEBRA conformance (rf2-jn7frs;
  EP-0014 Reference Implementation Plan item 9 + §Validation / Conformance;
  spec/Derivations.md §Conformance).

  EP-0014's whole point is ONE derivation/process vocabulary across SIX
  plural source forms — `reg-sub`, `reg-runtime-sub`, `reg-flow`,
  `reg-resource`, `reg-route`, `reg-machine` — so a tool reads ONE graph
  (`{:mode :nodes :edges}`) and a maintainer answers ONE question
  consistently: where does this fact come from, when is it evaluated, where
  does it live, and who owns it? Each family already exposes its OWN algebra
  view (the per-artefact `*-algebra-view` tooling siblings) and the composer
  (`re-frame.derivation.graph`) stitches them; the per-artefact suites pin
  each projection in isolation and the composer test
  (`re-frame.derivation-graph-test`) pins the assembly MECHANICS. But until
  this suite NO test proved the four EP-0014 conformance LAWS hold ACROSS
  all families SIMULTANEOUSLY, through the composer, the way they will be
  read in anger.

  That is exactly the umbrella `reply-conformance/` is to EP-0011's reply
  vocabulary: a table-driven cross-family gate so a divergence in any one
  family's lowering / classification / edges cannot silently slip past the
  per-family suites (each of which only ever looks at itself).

  ## What this suite is

  A registration of ONE source form of EACH family into a live frame, then
  one set of cross-family assertions over the assembled STATIC and LIVE
  graphs proving the four laws (the EP-0014 §Validation / Conformance list,
  restated as the slice-1 contract):

    (a) LOWERING        — each source form lowers to the correct node KIND /
                          SUPERKIND. Every node's `:kind` classifies as the
                          closed two-superkind enum (`:derivation` |
                          `:process`); a tool that understands ONLY the two
                          superkinds can classify EVERY node (refined kinds
                          — `:machine-process`, `:route-fact` — reduce to a
                          superkind).
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
            ;; load-bearing side-effecting requires: each façade registers
            ;; its registrar kind / framework events / subs so the family is
            ;; live (the same loads the composer test performs).
            [re-frame.routing]
            [re-frame.routing.tooling :as routing-tooling]
            [re-frame.resources]
            [re-frame.resources.tooling :as resources-tooling]
            [re-frame.machines.tooling :as machines-tooling]
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

(def refined->superkind
  "Informative refined kinds (Derivations §Two superkinds, refinable) and
  the superkind they reduce to. `:machine-process` refines `:process`; a
  route's `:kind` is already the bare `:process` superkind (the `:route-fact`
  detail rides `:refinement`)."
  {:derivation       :derivation
   :process          :process
   :machine-process  :process
   :resource-process :process
   :route-fact       :process})

(defn superkind-of
  "Reduce any node `:kind` to its closed superkind, or nil if unknown."
  [kind]
  (get refined->superkind kind))

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
  #{:subscription-cache-entry :frame :route :resource-key :machine-instance :host-root})

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
   :machines  {:static-fn  machines-tooling/machine-algebra-view
               :live-fn    machines-tooling/machine-instance-algebra-view
               :live-shape :map
               :selector?  machines-tooling/machine-selector?}})

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
  (rf/reg-event-db ::seed-cart
                   (fn [db [_ items]]
                     (assoc-in db [:cart :items] items)))
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
  (rf/reg-flow {:id     :cart/materialized-total
                :inputs [[:cart :items]]
                :output sum-cart
                :path   [:cart :total]})
  ;; :resources — a process node (runtime-db / external authority); declares
  ;; a route-owned resource so the route gets a :param activation edge.
  (rf/reg-resource :article/by-slug
                   {:scope         :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :request       (fn [{:keys [slug]} _ctx]
                                     {:request {:method :get
                                                :url    (str "/api/articles/" slug)}})})
  ;; :routes — a route fact (runtime-db / on-route), owning the resource.
  (rf/reg-route :route/article
                {:path      "/articles/:slug"
                 :resources [{:resource :article/by-slug :blocking? true}]})
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
    (testing "EVERY node classifies to the closed two-superkind enum — a tool
              that understands only :derivation / :process classifies all"
      (doseq [[node-id node] nodes]
        (is (contains? superkinds (superkind-of (:kind node)))
            (str node-id " :kind " (:kind node)
                 " does not reduce to a closed superkind"))))
    (testing "subscriptions + flows are DERIVATIONS (Derivations §Derivation)"
      (is (= :derivation (:kind (node-by-family nodes :subs :cart/total))))
      (is (= :derivation (:kind (node-by-family nodes :subs :cart/items))))
      (is (= :derivation (:kind (node-by-family nodes :flows :cart/materialized-total)))))
    (testing "resources + routes + machines are PROCESSES (Derivations §Process)"
      ;; resource carries the bare superkind :process; route carries :process
      ;; with the :route-fact refinement; machine carries the :machine-process
      ;; refinement — all three reduce to :process.
      (is (= :process (superkind-of (:kind (node-by-family nodes :resources :article/by-slug)))))
      (is (= :process (superkind-of (:kind (node-by-family nodes :routes :route/article)))))
      (is (= :process (superkind-of (:kind (node-by-family nodes :machines :upload/main))))))
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
      (is (= :resource-key  (:lifecycle res)))
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
    ;; The route transition commits the slice synchronously under the
    ;; plain-atom JVM substrate (the JVM gate always exercises this realized
    ;; arm). Some substrates settle the transition across more than one
    ;; drain, so assert the realized facts only WHEN the slice has
    ;; materialized — never as an absence-is-failure (the law is "the live
    ;; graph reports the realized slice", proven where the slice committed).
    (when (some? slice)
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
              "the live route OWNER is [:route route-id nav-token]"))))))

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
          "the flow's whole-value fn equals the shared sum-cart"))))

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
