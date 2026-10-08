(ns re-frame.route-algebra-view-test
  "Tests for the derivation/process algebra view of routes. Per
  [spec/Derivations.md] §Routes expose algebra views
  and the `:rf/derivation-node` shape in
  [spec/Spec-Schemas.md].

  `re-frame.routing.tooling/route-algebra-view` lowers every registered route
  into the normalized algebra node every declared fact/process shares — the
  first PROCESS-LIKE member: a `:route-fact` whose output MATERIALIZES the
  route slice into runtime-db at `[:rf.runtime/routing :current]`, evaluated
  `:on-route`, owned by its `:frame`. `…/route-slice-algebra-view` is the live
  counterpart, reading a frame's runtime-db route slice. Both live in the
  bundle-isolated tooling sibling, consumed by Xray and the conformance
  fixtures.

  These tests pin:
    - the static node, whole: the fixed classifications, the `:rf/route`
      fact id, the route-transition `:inputs`, the runtime-db `:output` and
      the source form;
    - the route-owned resource activation edges (the `:resources`
      route-metadata lowering — parametric target, entry fns never run);
    - the live route slice projection, with and without a live owner;
    - the `:source` / `:doc` passthrough.

  ## Posture split

  The node SHAPE is production-real and carries no posture guard: it runs in
  the ordinary `clojure -M:test` suite AND in
  `scripts/test-routing-prod-gate.sh` (the `-Dre-frame.debug=false` lane).

  Two leaf slots are NOT: `:source` and `:doc`. Source coords are captured
  behind `rf.interop/debug-enabled?` (Spec 001 §Source-coordinate capture), and
  `:doc` is a pure-documentation key the registrar STRIPS before storage in
  production builds (Spec 001 §Production elision contract) so its string
  bytes DCE out of the bundle. Both deftests that read them therefore branch:
  the dev arm makes the presence assertion, and the production arm asserts
  the ELISION — that the strip really happened. Neither arm is vacuous."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]
            [re-frame.routing.tooling :as rf.routing.tooling]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

;; The fixed classifications EVERY route fact algebra node carries, static and
;; live (Derivations §Routes expose algebra views). `:kind` is the closed
;; superkind `:process`; `:refinement :route-fact` is the informative one.
(def fixed-classifications
  {:kind          :process
   :refinement    :route-fact
   :storage       :runtime-db
   :evaluation    :on-route
   :lifecycle     :frame
   :materialized? true})

;; The route slice's runtime-db output address.
(def route-output [:runtime [:rf.runtime/routing :current]])

(defn- with-resources-route-key
  "Run `f` with the late-bound `:routing/extra-route-keys` hook admitting
  `:resources` (the key the Resources artefact owns — Spec 016 §Route
  integration), so `reg-route` accepts it without the resources artefact on
  the test classpath. Restores the prior hook value, because the
  `reset-runtime` fixture does not clear late-bind hooks."
  [f]
  (let [prior (get @rf.late-bind/hooks :routing/extra-route-keys)]
    (try
      (rf.late-bind/set-fn! :routing/extra-route-keys (constantly #{:resources}))
      (f)
      (finally
        (if prior
          (rf.late-bind/set-fn! :routing/extra-route-keys prior)
          (swap! rf.late-bind/hooks dissoc :routing/extra-route-keys))))))

;; ---- a registered route exposes its fact-node view -----------------------

(deftest route-exposes-its-full-fact-node-view
  (testing "a reg-route exposes the full route-fact / runtime-db / on-route / frame
            node: the fact id is :rf/route, NOT the registration id, which is
            recorded under :source-form; no :resource-edges or :doc unless declared"
    (rf/reg-route :route/article {} "/articles/:slug")
    (is (= (merge fixed-classifications
                  {:id          :rf/route
                   :output      route-output
                   :source-form {:kind :reg-route :id :route/article}
                   :inputs      [[:event :rf.route/navigate]
                                 [:event :rf.route/handle-url-change]]})
           (dissoc ((rf.routing.tooling/route-algebra-view) :route/article) :source)))))

;; ---- route-owned resource activation edges -------------------------------

(deftest route-resources-lower-to-activation-edges
  (testing "each :resources entry lowers, in declaration order, to a parametric
            edge from the route params slot — and the static view never runs an
            entry's :params / :when fns (the don't-execute rule)"
    (with-resources-route-key
      (fn []
        (let [ran?   (atom false)
              touch! (fn [& _] (reset! ran? true) {})]
          (rf/reg-route :route/article
                        {:resources
                         [{:resource  :article/by-slug
                           :params    touch!
                           :scope     {:from-db :session/current-tenant}
                           :blocking? true}
                          {:resource :article/comments
                           :params   touch!
                           :when     touch!}]}
                        "/articles/:slug")
          (is (= [{:from      [:runtime [:rf.runtime/routing :current :params]]
                   :to        [:resource :article/by-slug]
                   :role      :param
                   :target    :parametric
                   :blocking? true}
                  {:from   [:runtime [:rf.runtime/routing :current :params]]
                   :to     [:resource :article/comments]
                   :role   :param
                   :target :parametric}]
                 (:resource-edges ((rf.routing.tooling/route-algebra-view) :route/article)))
              ":blocking? is surfaced only when the entry declared it")
          (is (false? @ran?) "no entry fn ran"))))))

;; ---- the live route slice ------------------------------------------------

(deftest live-route-slice-projects-the-materialized-fact
  (testing "route-slice-algebra-view projects a frame's live route slice; the
            owner [:route route-id nav-token] is present only with a nav-token"
    (let [live (fn [current]
                 (rf.frame/replace-runtime-db! :rf/default {:rf.runtime/routing {:current current}})
                 (rf.routing.tooling/route-slice-algebra-view :rf/default))
          base (merge fixed-classifications {:id :rf/route :output route-output})]
      (is (= (merge base {:route-id   :route/article
                          :params     {:slug "welcome"}
                          :query      {:ref "home"}
                          :transition :idle
                          :nav-token  17
                          :owner      [:route :route/article 17]})
             (live {:route-id   :route/article
                    :params     {:slug "welcome"}
                    :query      {:ref "home"}
                    :transition :idle
                    :nav-token  17})))
      (is (= (merge base {:route-id   :route/x
                          :params     {}
                          :query      nil
                          :transition :idle
                          :nav-token  nil})
             (live {:route-id :route/x :params {} :transition :idle}))
          "no nav-token → no live owner edge"))))

(deftest live-route-slice-nil-when-unmaterialized
  (testing "nil before any navigation commits, and for an unknown frame"
    (is (= [nil nil] [(rf.routing.tooling/route-slice-algebra-view :rf/default)
                      (rf.routing.tooling/route-slice-algebra-view :no/such-frame)]))))

;; ---- source-coords / doc passthrough -------------------------------------

(deftest source-coords-surface-in-the-node
  (testing ":ns / :file / :line captured by reg-route surface under :source"
    (rf/reg-route :route/home {} "/")
    (let [source (:source ((rf.routing.tooling/route-algebra-view) :route/home))]
      (if rf.interop/debug-enabled?
        (is (and (:ns source) (:file source) (number? (:line source)))
            (str "coords captured at the call site, got " (pr-str source)))
        (is (nil? source)
            "under -Dre-frame.debug=false the coords are elided (Spec 001)")))))

(deftest doc-passes-through
  (testing ":doc supplied on the route metadata surfaces in the node"
    (rf/reg-route :route/home {:doc "the home route"} "/")
    (if rf.interop/debug-enabled?
      (is (= "the home route" (:doc ((rf.routing.tooling/route-algebra-view) :route/home))))
      (is (not (contains? ((rf.routing.tooling/route-algebra-view) :route/home) :doc))
          "under -Dre-frame.debug=false the registrar strips :doc before storage"))))
