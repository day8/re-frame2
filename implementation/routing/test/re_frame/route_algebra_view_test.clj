(ns re-frame.route-algebra-view-test
  "Tests for the derivation/process algebra view of routes (EP-0014 slice-5,
  rf2-eiiifu). Per [spec/Derivations.md] §Routes expose algebra views
  (graduated from EP-0014) and the `:rf/derivation-node` shape in
  [spec/Spec-Schemas.md].

  `re-frame.routing.tooling/route-algebra-view` lowers every registered route
  into the normalized algebra node every declared fact/process shares — the
  first PROCESS-LIKE member: a `:route-fact` whose output MATERIALIZES the
  route slice into runtime-db at `[:rf.runtime/routing :current]`, evaluated
  `:on-route`, owned by its `:frame`. `…/route-slice-algebra-view` is the live
  counterpart, reading a frame's runtime-db route slice.

  These tests pin:
    - the static registrar-derived projection: the fixed classifications
      (`:kind` / `:storage` / `:evaluation` / `:lifecycle` / `:materialized?`),
      the `:rf/route` fact id, the route-transition `:inputs`, the runtime-db
      `:output`, the source-form metadata, and source coords;
    - the route-owned resource activation edge (the `:resources`
      route-metadata lowering — parametric target per the don't-execute rule);
    - the live route slice projection (matched id / params / query /
      transition / nav-token / owner).

  Slice-5 ships NO public accessor (EP-0014 issue-1 disposition): the views
  live in the bundle-isolated `re-frame.routing.tooling` sibling, reached on
  JVM through the `re-frame.routing/route-algebra-view` convenience alias, and
  consumed by Xray + the conformance fixtures. There is no
  `re-frame.core/route-algebra-view` public facade export."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.routing :as routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]
            [re-frame.routing.tooling :as routing-tooling]))

(use-fixtures :each rts/reset-runtime)

;; The fixed classifications EVERY route fact algebra node carries
;; (Derivations §Routes expose algebra views — the canonical runtime-db /
;; on-route / frame PROCESS-LIKE member of the algebra). `:kind` is the closed
;; superkind `:process` (Spec-Schemas DerivationKind); `:refinement`
;; `:route-fact` is the informative refinement.
(def fixed-classifications
  {:kind          :process
   :refinement    :route-fact
   :storage       :runtime-db
   :evaluation    :on-route
   :lifecycle     :frame
   :materialized? true})

(defn- has-fixed-classifications? [node]
  (= fixed-classifications (select-keys node (keys fixed-classifications))))

;; The route slice's runtime-db output address (the route fact materializes
;; here; the live read path mirrors it).
(def route-output [:runtime [:rf.runtime/routing :current]])

(defn- with-resources-route-key
  "Run `f` with the late-bound `:routing/extra-route-keys` hook published as
  `#{:resources}` (the cross-feature extension the Resources artefact owns —
  Spec 016 §Route integration), so `reg-route` accepts the bare `:resources`
  key without dragging the resources artefact into the routing test deps. The
  tooling reads the raw `:resources` vector off the registrar — it never
  executes the entry fns — so the hook only needs to admit the key.

  Snapshots + restores the prior hook value so the publication never leaks
  into other test namespaces sharing this JVM (the `reset-runtime` fixture
  does not clear late-bind hooks)."
  [f]
  (let [prior (get @late-bind/hooks :routing/extra-route-keys)]
    (try
      (late-bind/set-fn! :routing/extra-route-keys (constantly #{:resources}))
      (f)
      (finally
        (if prior
          (late-bind/set-fn! :routing/extra-route-keys prior)
          (swap! late-bind/hooks dissoc :routing/extra-route-keys))))))

;; ---- empty / shape contract ----------------------------------------------

(deftest empty-registry-returns-empty-map
  (testing "(route-algebra-view) returns {} (not nil) when no routes are registered"
    ;; The fixture re-`require`s the routing façade, which registers framework
    ;; events / fx / subs but NO routes — so the :route registrar kind is empty.
    (is (= {} (routing-tooling/route-algebra-view)))
    (is (map? (routing-tooling/route-algebra-view)))))

(deftest single-arity-returns-one-node
  (testing "(route-algebra-view route-id) returns the single node, nil when unregistered"
    (rf/reg-route :route/home {} "/")
    (is (= ((routing-tooling/route-algebra-view) :route/home)
           (routing-tooling/route-algebra-view :route/home))
        "the one-arity form equals the entry in the all-routes map")
    (is (nil? (routing-tooling/route-algebra-view :route/ghost))
        "an unregistered route id projects to nil")))

(deftest jvm-alias-mirrors-the-tooling-fn
  (testing "the JVM `re-frame.routing/route-algebra-view` alias is the tooling fn"
    (rf/reg-route :route/home {} "/")
    (is (= (routing-tooling/route-algebra-view)
           (routing/route-algebra-view))
        "the JVM convenience alias projects identically to the tooling sibling")))

;; ---- a registered route exposes its fact-node view -----------------------

(deftest route-exposes-its-full-fact-node-view
  (testing "a reg-route exposes the full route-fact / runtime-db / on-route / frame node"
    (rf/reg-route :route/article {} "/articles/:slug")
    (let [node ((routing-tooling/route-algebra-view) :route/article)]
      (is (some? node) "the route is present under its registration id")
      (is (has-fixed-classifications? node)
          "route carries the fixed route-fact / runtime-db / on-route / frame / materialized classifications")
      (is (= :rf/route (:id node))
          "the fact id is :rf/route — the route slice's one consumer-facing name (EP-0007), NOT the registration id")
      (is (= {:kind :reg-route :id :route/article} (:source-form node))
          "the per-route registration id is recorded under :source-form")
      (is (= route-output (:output node))
          "the output materializes the route slice into runtime-db at [:rf.runtime/routing :current]")
      (is (= [[:event :rf.route/navigate]
              [:event :rf.route/transitioned]
              [:event :rf.route/handle-url-change]]
             (:inputs node))
          "the inputs are the route-transition causal events — the :on-route triggers")
      (is (not (contains? node :resource-edges))
          "a route with no :resources carries no :resource-edges key"))))

(deftest every-registered-route-shares-the-rf-route-fact-id
  (testing "every route projects to the same :rf/route fact id (every route materializes the one slice)"
    (rf/reg-route :route/home    {} "/")
    (rf/reg-route :route/about   {} "/about")
    (rf/reg-route :route/article {} "/articles/:slug")
    (let [view (routing-tooling/route-algebra-view)]
      (is (= #{:route/home :route/about :route/article} (set (keys view)))
          "the view is keyed by per-route registration id")
      (is (every? has-fixed-classifications? (vals view)))
      (is (= #{:rf/route} (set (map :id (vals view))))
          "every node's :id is :rf/route — one name per fact (EP-0007)")
      (is (= #{:route/home :route/about :route/article}
             (set (map (comp :id :source-form) (vals view))))
          "each node's :source-form carries its own registration id"))))

;; ---- route-owned resource activation edge --------------------------------

(deftest route-resources-lower-to-activation-edges
  (testing "a route's :resources metadata lowers to route-owned resource activation edges"
    ;; `:resources` is a CROSS-FEATURE route-metadata key owned by the
    ;; Resources artefact (Spec 016 §Route integration), accepted by routing
    ;; via the late-bound :routing/extra-route-keys extension. We publish the
    ;; extension hook directly (no resources artefact dep needed — the tooling
    ;; reads the raw :resources vector off the registrar, never executing the
    ;; entry fns) so reg-route accepts the bare :resources key.
    (with-resources-route-key
      (fn []
        (rf/reg-route :route/article
                      {:resources
                       [{:resource  :article/by-slug
                         :params    (fn [route] {:slug (get-in route [:params :slug])})
                         :scope     (fn [_route ctx] (:current-session-scope ctx))
                         :blocking? true}
                        {:resource :article/comments
                         :params   (fn [route] {:slug (get-in route [:params :slug])})}]} "/articles/:slug")
        (let [node  ((routing-tooling/route-algebra-view) :route/article)
              edges (:resource-edges node)]
          (is (has-fixed-classifications? node))
          (is (vector? edges) "the route owns resource activation — :resource-edges present")
          (is (= 2 (count edges)) "one edge per :resources entry, in declaration order")
          (let [[blocking-edge bg-edge] edges]
            (is (= {:from   [:runtime [:rf.runtime/routing :current :params]]
                    :to     [:resource :article/by-slug]
                    :role   :param
                    :target :parametric
                    :blocking? true}
                   blocking-edge)
                "the blocking edge runs from the route params slot to the resource, parametric target, :blocking? surfaced")
            (is (= [:resource :article/comments] (:to bg-edge)))
            (is (= :parametric (:target bg-edge))
                "the concrete scoped key is parametric — the don't-execute rule keeps the entry fns unexecuted")
            (is (not (contains? bg-edge :blocking?))
                ":blocking? is surfaced only when the entry declared it")))))))

(deftest resource-edge-never-executes-entry-fns
  (testing "static projection NEVER invokes a resource entry's :params / :scope / :when fns (don't-execute rule)"
    (with-resources-route-key
      (fn []
        (let [boom (atom false)]
          (rf/reg-route :route/danger
                        {:resources
                         [{:resource :thing/by-id
                           :params   (fn [_route] (reset! boom true) {:id 1})
                           :scope    (fn [_ _] (reset! boom true) :rf.scope/global)
                           :when     (fn [_ _ _] (reset! boom true) true)}]} "/danger/:id")
          (let [node ((routing-tooling/route-algebra-view) :route/danger)]
            (is (= [:resource :thing/by-id] (:to (first (:resource-edges node)))))
            (is (false? @boom)
                "the static view read the declaration without running ANY entry fn")))))))

;; ---- the live route slice ------------------------------------------------

(deftest live-route-slice-projects-the-materialized-fact
  (testing "route-slice-algebra-view projects a frame's live route slice"
    (rf/reg-route :route/article {} "/articles/:slug")
    ;; Install a live route slice directly into the frame's runtime-db (the
    ;; shape a committed navigation materializes — Spec 012 §The route slice):
    ;; {:route-id :params :query :transition :nav-token …} at
    ;; [:rf.runtime/routing :current].
    (frame/replace-runtime-db!
      :rf/default
      {:rf.runtime/routing
       {:current {:route-id         :route/article
                  :params     {:slug "welcome"}
                  :query      {:ref "home"}
                  :transition :idle
                  :nav-token  17}}})
    (let [node (routing-tooling/route-slice-algebra-view :rf/default)]
      (is (some? node) "the live slice projects to a node")
      (is (has-fixed-classifications? node)
          "the live node carries the same fixed classifications as the static node")
      (is (= :rf/route (:id node)))
      (is (= route-output (:output node)))
      (is (= :route/article (:route-id node)) "the live matched route id")
      (is (= {:slug "welcome"} (:params node)) "the live matched path params")
      (is (= {:ref "home"} (:query node)) "the live matched query params")
      (is (= :idle (:transition node)) "the live transition state")
      (is (= 17 (:nav-token node)) "the live nav-token (route owner identity)")
      (is (= [:route :route/article 17] (:owner node))
          "the owner is [:route route-id nav-token] — the live route owner (Derivations §Lifecycle and owner)"))))

(deftest live-route-slice-nil-when-unmaterialized
  (testing "route-slice-algebra-view returns nil when no route slice has committed"
    ;; A fresh frame's runtime-db starts {} — no :current route slice yet.
    (is (nil? (routing-tooling/route-slice-algebra-view :rf/default))
        "no navigation committed → nil")))

(deftest live-route-slice-nil-for-missing-frame
  (testing "route-slice-algebra-view returns nil for an unknown / destroyed frame"
    (is (nil? (routing-tooling/route-slice-algebra-view :no/such-frame)))))

(deftest live-slice-without-nav-token-omits-owner
  (testing "the :owner edge is present only when both matched id and nav-token are known"
    (frame/replace-runtime-db!
      :rf/default
      {:rf.runtime/routing
       {:current {:route-id :route/x :params {} :transition :idle}}}) ;; no :nav-token
    (let [node (routing-tooling/route-slice-algebra-view :rf/default)]
      (is (some? node))
      (is (= :route/x (:route-id node)))
      (is (nil? (:nav-token node)))
      (is (not (contains? node :owner))
          "no nav-token → no live owner edge (a route fact with no owner is not yet a live owner)"))))

;; ---- source-coords / doc passthrough -------------------------------------

(deftest source-coords-surface-in-the-node
  (testing ":ns / :line / :file captured by reg-route surface under :source"
    (rf/reg-route :route/home {} "/")
    (let [node   ((routing-tooling/route-algebra-view) :route/home)
          source (:source node)]
      (is (some? source) ":source map is present when the registration carried coords")
      (is (some? (:ns source))     ":ns captured at the call site")
      (is (number? (:line source)) ":line captured at the call site")
      (is (some? (:file source))   ":file captured at the call site"))))

(deftest doc-passes-through
  (testing ":doc supplied on the route metadata surfaces in the node"
    (rf/reg-route :route/home {:doc "the home route"} "/")
    (is (= "the home route" (:doc ((routing-tooling/route-algebra-view) :route/home))))))

(deftest no-doc-when-absent
  (testing ":doc is absent when the registration didn't supply it"
    (rf/reg-route :route/home {} "/")
    (is (not (contains? ((routing-tooling/route-algebra-view) :route/home) :doc)))))

;; ---- registry semantics --------------------------------------------------

(deftest unregistered-route-is-removed
  (testing "(clear-route id) removes the route from the algebra view"
    (rf/reg-route :route/a {} "/a")
    (rf/reg-route :route/b {} "/b")
    (is (contains? (routing-tooling/route-algebra-view) :route/a))
    (routing/clear-route :route/a)
    (let [view (routing-tooling/route-algebra-view)]
      (is (not (contains? view :route/a)))
      (is (contains? view :route/b)))))
