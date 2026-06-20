(ns day8.re-frame2-xray.panels.derivation-graph-consumer-cljs-test
  "Behavioral consumer test for the Derivation-Graph panel (EP-0014 prop-3,
  rf2-9ett2d; the test-coverage gap rf2-4wtllq).

  ## The seam this closes

  The existing Derivation-Graph tests are valuable but partial:
    - `derivation_graph_helpers_cljs_test.cljc` — PURE helper behavior over a
      fixture graph (classification / grouping / role counts / summary).
    - `derivation_graph_redaction_cljs_test.cljc` — `redact-graph-for-egress`
      over a fixture live graph.
    - `registry_cljs_test.cljs` — REGISTRATION presence (the panel's subs /
      events resolve), but no assertion on the returned graph behavior.

  None proves that Xray's ACTUAL `:rf.xray/derivation-graph` subscription
  CALLS the shared `re-frame.derivation.graph` composer over the panel's
  `xray-contributors` map, honors `:static` vs `:live`, threads
  `:rf.xray/target-frame` into the live composer, and feeds the resulting
  graph into `:rf.xray/derivation-graph-tab-data`. A regression could wire
  live mode to the static graph, drop the target-frame argument, pass the
  wrong contributor map, or strip the EP-0013 relocation coordinates in
  tab-data while every helper/registry test still passed.

  ## What's under test (the actual subscriptions, end to end)

    1. STATIC mode — `:rf.xray/derivation-graph` returns a graph the shared
       COMPOSER produced over `xray-contributors` (real registered subs +
       machine become nodes; the composer's edge roles appear), and
       `:rf.xray/derivation-graph-tab-data` reports `:mode :static`, the
       node/edge tally, by-family grouping, and edge roles.
    2. ALL FIVE families wired (rf2-1fc459) — the contributor map carries
       `:subs :flows :resources :routes :machines`; a registered machine +
       its selector flow through to the graph as a `:machine` process node
       and a precise `:selector` edge.
    3. PRECISE selector targeting (rf2-4qmiij) — two machines, one selector
       reading only one: the unrelated machine gets NO selector edge through
       the Xray consumer path.
    4. LIVE mode — switching `:rf.xray/set-derivation-graph-mode :live` +
       setting `:rf.xray/set-target-frame` makes `:rf.xray/derivation-graph`
       call `live-derivation-graph` with `:frame <target>` (the live shape,
       carrying the observed frame id).
    5. OVERRIDE path — the test override still bypasses composer output.
    6. EP-0013 coordinates — a node carrying optional `:realm/id` / `:app/id`
       / `:module/id` survives tab-data summarization unchanged (the future
       relocation coordinates ride through; Spec-Schemas reserves them)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.machines]                     ;; load the machines facade so reg-machine works
            [re-frame.machines.tooling :as machines-tooling]
            [re-frame.routing]                      ;; load routing so reg-route + navigate materialize a live route slice
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.derivation-graph :as derivation-graph]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- setup-xray! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (frame/reg-frame :rf/xray {}))

;; A one-shot read of an `:rf.xray/*` sub, in the :rf/xray frame (where the
;; panel's mode / override / target-frame slots live). `subscribe-once`'s
;; 2-arity form is `(frame-id query-v)` — the named-frame read from outside
;; any ambient scope.
(defn- read-xray [q-v]
  (rf/subscribe-once :rf/xray q-v))

;; ---- host-app registrations (the composer's subject) --------------------

(defn- register-host-subs! []
  ;; a static `:<-` derivation chain — the :input edge source.
  (rf/reg-sub :cart/items (fn [db _] (get-in db [:cart :items])))
  (rf/reg-sub :cart/total
              :<- [:cart/items]
              (fn [items _] (count items))))

(defn- register-machine+selector! []
  ;; a machine process node + an ordinary sub that READS it (the selector).
  (rf/reg-machine :upload/main
                  {:initial :idle
                   :data    {:progress 0}
                   :states  {:idle      {:on {:upload/start {:target :uploading}}}
                             :uploading {:on {:upload/done {:target :idle}}}}})
  (rf/reg-sub :upload/progress
              :<- [:rf/machine :upload/main]
              (fn [snapshot _] (get-in snapshot [:data :progress] 0))))

;; ---- (1) static mode consumes the shared composer -----------------------

(deftest static-mode-subscription-consumes-the-composer
  (testing ":rf.xray/derivation-graph returns a graph the shared composer
            produced over xray-contributors (the real registered subs are nodes)"
    (setup-xray!)
    (register-host-subs!)
    (let [graph (read-xray [:rf.xray/derivation-graph])]
      (is (= :static (:mode graph)) "default mode is :static")
      ;; The registered subs became composer nodes — not a hand-rolled shape.
      (is (contains? (:nodes graph) [:sub :cart/total])
          "the registered :cart/total sub is a [:sub …] node in the composed graph")
      (is (contains? (:nodes graph) [:sub :cart/items])
          "the registered :cart/items sub is a node")
      ;; The composer's static :input edge (cart/items → cart/total).
      (is (some #(= % {:from [:sub :cart/items]
                       :to   [:sub :cart/total]
                       :role :input})
                (:edges graph))
          "the static :<- input edge the composer derives is present")))
  (testing "tab-data summarizes the composer graph for the view (mode / counts / grouping / roles)"
    (setup-xray!)
    (register-host-subs!)
    (let [{:keys [mode summary by-family edges]} (read-xray [:rf.xray/derivation-graph-tab-data])]
      (is (= :static mode) "tab-data carries the static mode")
      (is (= :static (:mode summary)))
      (is (pos? (:node-count summary)) "the header reports a real node count")
      (is (contains? (set (keys by-family)) :subs) "subs family is grouped")
      (is (some #(= :input (:role %)) edges) "the :input edge role is summarized into tab-data"))))

;; ---- (2)+(3) all five families wired + precise selector targeting -------

(deftest machine-family-and-precise-selector-edge-flow-through-the-consumer
  (testing "a registered machine + its selector flow through Xray's contributor
            map as a :machine node + a :selector edge (rf2-1fc459 wires :machines)"
    (setup-xray!)
    (register-machine+selector!)
    (let [graph (read-xray [:rf.xray/derivation-graph])]
      (is (contains? (:nodes graph) [:machine :upload/main])
          "the machine family contributes a [:machine …] process node")
      (is (some #(= % {:from [:machine :upload/main]
                       :to   [:sub :upload/progress]
                       :role :selector})
                (:edges graph))
          "the machine → selector :selector edge is drawn through the Xray consumer")))
  (testing "in a multi-machine app the selector edge targets ONLY the machine
            it reads — no cross product (rf2-4qmiij)"
    (setup-xray!)
    (rf/reg-machine :upload/main
                    {:initial :idle :data {:progress 0}
                     :states {:idle {:on {:upload/start {:target :uploading}}}
                              :uploading {:on {:upload/done {:target :idle}}}}})
    (rf/reg-machine :download/main
                    {:initial :idle :data {:bytes 0}
                     :states {:idle {:on {:download/start {:target :fetching}}}
                              :fetching {:on {:download/done {:target :idle}}}}})
    (rf/reg-sub :upload/progress
                :<- [:rf/machine :upload/main]
                (fn [snapshot _] (get-in snapshot [:data :progress] 0)))
    ;; Scope the assertion to OUR selector (the consolidated node-test build's
    ;; shared registrar carries machines/selectors from sibling namespaces;
    ;; precise targeting is a property of each edge, not the global count).
    (let [sel        (filter #(= :selector (:role %))
                             (:edges (read-xray [:rf.xray/derivation-graph])))
          our-target (filter #(= [:sub :upload/progress] (:to %)) sel)]
      (is (= [{:from [:machine :upload/main]
               :to   [:sub :upload/progress]
               :role :selector}]
             (vec our-target))
          "exactly one selector edge to :upload/progress, from the machine it reads")
      ;; The unrelated machine receives NO edge to OUR selector (the cross-product bug).
      (is (not-any? #(and (= [:machine :download/main] (:from %))
                          (= [:sub :upload/progress] (:to %)))
                    sel)
          "the unrelated :download/main machine gets no edge to our selector")
      ;; And every selector edge in the whole graph is precisely targeted — its
      ;; :from machine id matches a machine the :to selector actually reads
      ;; (no edge points from a machine the selector does not name).
      (is (every? (fn [{:keys [from to]}]
                    (let [sub-id (second to)]
                      (contains? (machines-tooling/machine-selector-targets sub-id)
                                 (second from))))
                  sel)
          "every selector edge runs from a machine its target selector reads"))))

;; ---- (4) live mode calls the live composer with the target frame --------

(deftest live-mode-subscription-calls-the-live-composer-with-the-target-frame
  (testing "switching to :live + setting :rf.xray/target-frame makes
            :rf.xray/derivation-graph call live-derivation-graph with :frame"
    (setup-xray!)
    ;; A real host frame to observe.
    (frame/reg-frame :app/main {})
    (rf/dispatch-sync [:rf.xray/set-derivation-graph-mode :live] {:frame :rf/xray})
    (rf/dispatch-sync [:rf.xray/set-target-frame :app/main] {:frame :rf/xray})
    (let [graph (read-xray [:rf.xray/derivation-graph])]
      (is (= :live (:mode graph)) "the live graph carries :mode :live")
      (is (= :app/main (:frame graph))
          "the live composer was called with the observed target frame id"))
    (let [{:keys [mode]} (read-xray [:rf.xray/derivation-graph-tab-data])]
      (is (= :live mode) "tab-data reflects the live mode"))))

;; ---- (4b) LIVE mode carries REAL live CONTENT + frame isolation (rf2-k0meap.3)
;;
;; The §4 test above proves the live composer is CALLED with the target frame,
;; but a regression that returned an EMPTY live graph for the selected frame,
;; dropped the live nodes/edges, or failed to feed live content into tab-data
;; would still satisfy it (it asserts only :mode + :frame). This test plants a
;; REAL live fact in the target frame — a navigation that materializes the
;; route slice — and asserts the live node + family grouping flow through the
;; consumer path; a SECOND frame (no navigation) proves target-frame isolation.

(deftest live-mode-carries-the-target-frames-live-content-and-isolates-by-frame
  (testing "a live fact in the TARGET frame surfaces as a live node through
            the Xray consumer path (graph + tab-data carry it)"
    (setup-xray!)
    (frame/reg-frame :app/main {})
    (frame/reg-frame :app/other {})
    ;; Register a route and materialize the route slice in :app/main by
    ;; navigating (the live route slice view reads runtime-db, ungated).
    (rf/with-frame :app/main
      (rf/reg-route :route/article {} "/articles/:slug"))
    (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "welcome"}]
                      {:frame :app/main})
    (rf/dispatch-sync [:rf.xray/set-derivation-graph-mode :live] {:frame :rf/xray})
    (rf/dispatch-sync [:rf.xray/set-target-frame :app/main] {:frame :rf/xray})
    (let [graph (read-xray [:rf.xray/derivation-graph])
          slice (get (:nodes graph) :rf/route)]
      (is (= :live (:mode graph)))
      ;; The realized route slice is a LIVE node in the composed graph — not
      ;; an empty graph (the regression this closes).
      (is (some? slice) "the live route slice node is present in the live graph")
      (is (= :route/article (:route-id slice)) "the realized matched route id")
      (is (= {:slug "welcome"} (:params slice)) "the realized live params")
      (is (= :routes (:rf/family slice)) "the live node is family-tagged :routes"))
    (testing "tab-data feeds the live node into its by-family grouping (live
              content reaches the view-facing composite, not just :mode)"
      (let [{:keys [mode by-family silent?]} (read-xray [:rf.xray/derivation-graph-tab-data])]
        (is (= :live mode))
        (is (not silent?) "the live graph is NOT empty — tab-data carries live content")
        (is (contains? (set (keys by-family)) :routes)
            "the routes family is grouped from the live node")
        (let [route-entries (get by-family :routes)]
          (is (some (fn [[node-id _]] (= :rf/route node-id)) route-entries)
              "the live route node is in the routes group")))))

  (testing "target-frame ISOLATION — pointing Xray at a DIFFERENT frame (no
            navigation) yields an empty live graph; the first frame's live
            slice does NOT leak across"
    ;; (state from the previous testing block persists within the deftest's
    ;; single fixture run — :app/other was registered but never navigated.)
    (rf/dispatch-sync [:rf.xray/set-target-frame :app/other] {:frame :rf/xray})
    (let [graph (read-xray [:rf.xray/derivation-graph])]
      (is (= :app/other (:frame graph)) "the live graph targets the OTHER frame")
      (is (nil? (get (:nodes graph) :rf/route))
          "no route slice leaks from :app/main into the :app/other live graph")
      (is (= {} (into {} (filter (fn [[_ n]] (= :routes (:rf/family n))) (:nodes graph))))
          "the :app/other live graph carries no routes-family live node"))))

;; ---- (5) the override path bypasses the composer ------------------------

(deftest override-bypasses-the-composer-output
  (testing "the test override slot short-circuits the composer (tests drive a
            fixture graph without a full runtime)"
    (setup-xray!)
    (register-host-subs!)                  ;; composer WOULD produce a non-trivial graph
    (let [fixture {:mode  :static
                   :nodes {[:sub :only/me]
                           {:id :only/me :kind :derivation :rf/family :subs}}
                   :edges []}]
      (rf/dispatch-sync [:rf.xray/set-derivation-graph-override-for-test fixture]
                        {:frame :rf/xray})
      (let [graph (read-xray [:rf.xray/derivation-graph])]
        (is (= fixture graph) "the override is returned verbatim, NOT the composer graph")
        (is (not (contains? (:nodes graph) [:sub :cart/total]))
            "the composer's nodes are absent — the override won")))))

;; ---- (6) EP-0013 relocation coordinates survive tab-data ----------------

(deftest ep0013-relocation-coordinates-survive-tab-data
  (testing "a node carrying optional :realm/id / :app/id / :module/id rides
            through summarization unchanged (Spec-Schemas reserves them for the
            future EP-0013 relocation; the consumer must preserve them)"
    (setup-xray!)
    (let [coord-node {:id        :scoped/fact
                      :kind      :derivation
                      :rf/family :subs
                      :value     {:count 3}        ;; a value-bearing field → summarized
                      :realm/id  :realm/tenant-a
                      :app/id    :app/checkout
                      :module/id :module/cart}
          fixture    {:mode  :static
                      :nodes {[:sub :scoped/fact] coord-node}
                      :edges []}]
      (rf/dispatch-sync [:rf.xray/set-derivation-graph-override-for-test fixture]
                        {:frame :rf/xray})
      (let [{:keys [by-family]} (read-xray [:rf.xray/derivation-graph-tab-data])
            summarized (->> (get by-family :subs)
                            (some (fn [[k node]] (when (= k [:sub :scoped/fact]) node))))]
        (is (some? summarized) "the coordinate-carrying node is grouped under :subs")
        (is (= :realm/tenant-a (:realm/id summarized)) ":realm/id preserved through tab-data")
        (is (= :app/checkout (:app/id summarized)) ":app/id preserved through tab-data")
        (is (= :module/cart (:module/id summarized)) ":module/id preserved through tab-data")
        ;; the value-bearing field still got its on-box summary attached
        (is (contains? summarized :summaries) "the on-box summary is attached")
        ;; structure preserved: kind + family ride through
        (is (= :derivation (:kind summarized)))
        (is (= :subs (:rf/family summarized)))))))
