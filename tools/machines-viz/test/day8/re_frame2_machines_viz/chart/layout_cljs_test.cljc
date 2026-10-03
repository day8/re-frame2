(ns day8.re-frame2-machines-viz.chart.layout-cljs-test
  "Pure-data tests for the chart-layout graph projector.

  xyflow + elkjs own positioning, so the projector carries no
  positioning primitives (no `:x`/`:y`/`:rank` on nodes, no `:points`
  on edges). This suite pins the substrate-agnostic graph parse
  surface."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart.layout :as layout]))

;; ---- fixtures ----------------------------------------------------------

(def idle-loading-success
  "Canonical small machine: idle → loading → success/failed."
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}})

(def compound-machine
  "One compound state with a nested region."
  {:initial :unauth
   :states  {:unauth        {:on {:login :authenticated}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {:on {:done :browsing}}}
                             :on      {:logout :unauth}}}})

;; ---- project-definition ---------------------------------------------------

(deftest project-definition-empty-for-nil
  (let [g (layout/project-definition nil)]
    (is (= [] (:nodes g)))
    (is (= [] (:edges g)))))

(deftest project-definition-extracts-flat-machine-nodes
  (let [{:keys [nodes initial-path]} (layout/project-definition idle-loading-success)
        ;; Exclude the synthetic ROOT-CONTAINER frame node
        ;; (`:path []`) that wraps the whole machine.
        paths (set (map :path (remove :root-container? nodes)))]
    (is (= [:idle] initial-path))
    (is (= #{[:idle] [:loading] [:success] [:failed]} paths))
    (is (some :initial? nodes) "the initial state is flagged")
    (is (= 2 (count (filter :final? nodes)))
        "final states are flagged")))

(deftest project-definition-flags-compound-initial
  (testing "a compound parent's :initial child is flagged
            :initial? (xstate per-level initial semantics)"
    (let [{:keys [nodes]} (layout/project-definition compound-machine)
          browsing (first (filter #(= [:authenticated :browsing] (:path %)) nodes))]
      (is (true? (:initial? browsing))))))

(deftest project-definition-wires-compound-parent-id
  (testing "compound substates carry :parent-id (the
            parent's node-id) for xyflow `:parentId` nesting (v12 reads
            `parentId`, not the pre-v12 `parentNode`).

            A top-level state nests under the synthetic ROOT-CONTAINER
            frame, so its `:parent-id` is `root-container-id` (a nested
            substate points at its own compound parent)."
    (let [{:keys [nodes]} (layout/project-definition compound-machine)
          browsing (first (filter #(= [:authenticated :browsing] (:path %)) nodes))
          unauth   (first (filter #(= [:unauth] (:path %)) nodes))]
      (is (= (layout/node-id [:authenticated]) (:parent-id browsing)))
      (is (= layout/root-container-id (:parent-id unauth))))))

(deftest project-definition-extracts-edges
  (let [{:keys [edges]} (layout/project-definition idle-loading-success)
        edge-pairs (set (map (juxt :from :to :event) edges))]
    (is (contains? edge-pairs [[:idle] [:loading] :start]))
    (is (contains? edge-pairs [[:loading] [:success] :ok]))
    (is (contains? edge-pairs [[:loading] [:failed] :err]))))

(deftest project-definition-emits-xyflow-shaped-edges
  (testing "every edge has :id, :source,
            :target string ids (xyflow contract) AND :from-path,
            :to-path vectors (substrate-side contract)"
    (let [{:keys [edges]} (layout/project-definition idle-loading-success)]
      (is (every? :id edges)         "every edge has a stable string id")
      (is (every? :source edges)     "every edge has :source (string)")
      (is (every? :target edges)     "every edge has :target (string)")
      (is (every? :from-path edges)  "every edge has :from-path (vector)")
      (is (every? :to-path edges)    "every edge has :to-path (vector)")
      (is (every? :event-label edges) "every edge has the xstate label"))))

(deftest project-definition-emits-xyflow-shaped-nodes
  (testing "every node has :id string
            (xyflow contract) alongside :path (vector)"
    (let [{:keys [nodes]} (layout/project-definition idle-loading-success)]
      (is (every? :id nodes))
      (is (every? string? (map :id nodes)))
      (is (every? :path nodes)))))

(deftest project-definition-extracts-compound-nodes
  (let [{:keys [nodes]} (layout/project-definition compound-machine)
        paths (set (map :path nodes))
        top   (filter #(= 1 (count (:path %))) nodes)]
    (is (contains? paths [:unauth]))
    (is (contains? paths [:authenticated]))
    (is (contains? paths [:authenticated :browsing]))
    (is (contains? paths [:authenticated :paying]))
    (is (= 2 (count top))
        "two top-level states (:unauth + :authenticated)")
    (is (some :compound? top)
        "the compound parent carries the :compound? flag")))

(deftest project-definition-projects-every-parallel-region
  (testing "full parallel-region rendering: EVERY region
            projects"
    (let [parallel {:type :parallel
                    :regions {:r1 {:initial :a :states {:a {:on {:go :b}}
                                                        :b {}}}
                              :r2 {:initial :x :states {:x {:on {:go :y}}
                                                        :y {}}}}}
          {:keys [nodes edges parallel?]} (layout/project-definition parallel)
          paths (set (map :path (remove :region? nodes)))]
      (is parallel? "the projection flags itself as parallel")
      (is (contains? paths [:a]))
      (is (contains? paths [:b]))
      (is (contains? paths [:x])
          ":r2's nodes surface too (full parallel layout)")
      (is (contains? paths [:y]))
      ;; Both regions' edges surface.
      (is (= #{:go} (set (map :event edges)))
          "edges from both regions project")
      (is (= 2 (count edges)) "one :go edge per region"))))

(deftest project-definition-emits-region-container-nodes
  (testing "each parallel region surfaces a synthetic
            :region? compound container node with a region-prefixed id"
    (let [parallel {:type :parallel
                    :regions {:audio   {:initial :playing
                                        :states {:playing {:on {:pause :paused}}
                                                 :paused  {:on {:play :playing}}}}
                              :display {:initial :on
                                        :states {:on  {:on {:dim :off}}
                                                 :off {:on {:lit :on}}}}}}
          {:keys [nodes]} (layout/project-definition parallel)
          regions (filter :region? nodes)]
      (is (= 2 (count regions)) "one container node per region")
      (is (every? :compound? regions) "region containers are compound")
      (is (= #{(layout/region-node-id :audio)
               (layout/region-node-id :display)}
             (set (map :id regions)))
          "region node-ids are region-prefixed")
      (is (= #{0 1} (set (map :region-index regions)))
          "region containers carry their ordinal index for boundary colour"))))

(deftest project-definition-tags-region-states-with-parent
  (testing "every state inside a region carries :region +
            :parent-id so the chart projector emits xyflow `:parentId`
            sub-flow grouping (v12 reads `parentId`)"
    (let [parallel {:type :parallel
                    :regions {:r1 {:initial :a :states {:a {} :b {}}}
                              :r2 {:initial :x :states {:x {} :y {}}}}}
          {:keys [nodes]} (layout/project-definition parallel)
          ;; Exclude the synthetic ROOT-CONTAINER frame (it has
          ;; no `:region` and no `:parent-id`; it is the wrapping frame, not a
          ;; region state).
          states (remove #(or (:region? %) (:root-container? %)) nodes)]
      (is (every? :parent-id states) "every state has a parent region id")
      (is (every? :region states)    "every state knows its region")
      (let [r1-states (filter #(= :r1 (:region %)) states)]
        (is (every? #(= (layout/region-node-id :r1) (:parent-id %)) r1-states)
            ":r1 states point at the :r1 container")))))

(deftest project-definition-region-edges-stay-region-local
  (testing "orthogonality: a region's edges never reference
            a sibling region's node (regions are independent zones)"
    (let [parallel {:type :parallel
                    :regions {:r1 {:initial :a :states {:a {:on {:go :b}} :b {}}}
                              :r2 {:initial :x :states {:x {:on {:go :y}} :y {}}}}}
          {:keys [nodes edges]} (layout/project-definition parallel)
          r1-ids (set (map :id (filter #(= :r1 (:region %)) (remove :region? nodes))))
          r2-ids (set (map :id (filter #(= :r2 (:region %)) (remove :region? nodes))))]
      (doseq [e edges]
        (is (or (and (contains? r1-ids (:source e)) (contains? r1-ids (:target e)))
                (and (contains? r2-ids (:source e)) (contains? r2-ids (:target e))))
            "each edge stays within one region")))))

(deftest region-node-id-is-prefixed-and-distinct
  (testing "region node-ids are region__-prefixed so they
            never collide with a state node-id. Segments
            are injectively escaped (the `/` ns separator → `_2f`)."
    (is (= "region__r1" (layout/region-node-id :r1)))
    (is (= "region__auth_2fmain" (layout/region-node-id :auth/main)))
    (is (not= (layout/region-node-id :r1) (layout/node-id [:r1]))
        "a region container id differs from the same-named state id")))

;; ---- region-scoped node-ids --------------------------------------------
;;
;; `project-parallel` REGION-SCOPES every region-state id. Kept verbatim,
;; two regions sharing a state NAME would mint IDENTICAL ids — the canonical
;; Spec 005 `:ingest` shape (three regions each with a `:done {:final?
;; true}` leaf) would collide all three `:done` nodes into one: xyflow would
;; drop two, and `highlight-ids` would mis-attribute the multi-active
;; highlight across regions.

(deftest region-scoped-id-is-injective-across-regions
  (testing "the SAME state path in two DIFFERENT regions mints
            DISTINCT ids (region is part of the id namespace), but composes
            stably from the region container id + the in-region node-id"
    (is (= (str (layout/region-node-id :fetch) "__" (layout/node-id [:done]))
           (layout/region-scoped-id :fetch [:done]))
        "composed from region-node-id + in-region node-id")
    (is (not= (layout/region-scoped-id :fetch [:done])
              (layout/region-scoped-id :validate [:done]))
        "same state name, two regions → DISTINCT ids")
    (is (not= (layout/region-scoped-id :fetch [:done])
              (layout/node-id [:done]))
        "a region-scoped id differs from the bare in-region id")))

(deftest project-definition-parallel-same-name-region-states-distinct-nodes
  (testing "the Spec 005 :ingest shape: three regions each
            carrying a same-named `:done {:final? true}` leaf. All three
            `:done` nodes must be PRESENT and DISTINCT (collided into one
            node-id, xyflow would drop two)"
    (let [ingest {:type :parallel
                  :regions {:fetch    {:initial :loading
                                       :states {:loading {:on {:loaded :done}}
                                                :done    {:final? true}}}
                            :validate {:initial :checking
                                       :states {:checking {:on {:ok :done}}
                                                :done     {:final? true}}}
                            :index    {:initial :building
                                       :states {:building {:on {:built :done}}
                                                :done     {:final? true}}}}}
          {:keys [nodes]} (layout/project-definition ingest)
          state-nodes (remove :region? nodes)
          done-nodes  (filter #(= [:done] (:path %)) state-nodes)
          done-ids    (map :id done-nodes)]
      (is (= 3 (count done-nodes)) "all three regions' :done leaves survive")
      (is (= 3 (count (set done-ids)))
          "the three :done node-ids are DISTINCT (no collision/drop)")
      (is (= #{(layout/region-scoped-id :fetch [:done])
               (layout/region-scoped-id :validate [:done])
               (layout/region-scoped-id :index [:done])}
             (set done-ids))
          "each :done id is region-scoped to its own region")
      ;; The whole projection has globally-unique node ids.
      (is (= (count nodes) (count (set (map :id nodes))))
          "every projected node id is globally unique"))))

(deftest project-definition-parallel-same-name-region-states-edges-region-scoped
  (testing "an intra-region edge to/from a shared-name state
            resolves to that region's OWN scoped id (no endpoint is shared
            across regions)"
    (let [ingest {:type :parallel
                  :regions {:fetch    {:initial :loading
                                       :states {:loading {:on {:loaded :done}}
                                                :done    {:final? true}}}
                            :validate {:initial :checking
                                       :states {:checking {:on {:ok :done}}
                                                :done     {:final? true}}}}}
          {:keys [nodes edges]} (layout/project-definition ingest)
          node-ids (set (map :id nodes))]
      ;; every edge endpoint is a REAL projected node (no phantom/collided id)
      (doseq [e edges]
        (is (contains? node-ids (:source e))
            (str "edge source " (:source e) " is a real node"))
        (is (contains? node-ids (:target e))
            (str "edge target " (:target e) " is a real node")))
      ;; the :fetch :loaded→:done edge lands on :fetch's OWN :done, not
      ;; :validate's
      (let [fetch-done (layout/region-scoped-id :fetch [:done])]
        (is (some #(= fetch-done (:target %)) edges)
            ":fetch's :loaded→:done edge targets :fetch's scoped :done")
        (is (= (count (set (map :id edges))) (count edges))
            "every edge id is distinct (no cross-region edge-id collision)")))))

(deftest project-definition-region-top-level-on-no-machine-root-node
  (testing "a parallel region whose def carries a TOP-LEVEL :on
            fallback must NOT project a malformed region-scoped MACHINE-ROOT
            node. A `{:path [] :machine-root? true}` node minted for the
            region's top-level :on would be region-scoped to the degenerate
            id `region__fetch__` (trailing `__`, empty path segment) nested
            INSIDE the region — a stray `root` chip. A region is a compound
            state, not a machine; its :on is an ordinary region-scoped
            fallback (XState v5)."
    (let [ingest {:type :parallel
                  :regions {:fetch    {:initial :loading
                                       :on      {:abort :loading}
                                       :states {:loading {:on {:loaded :done}}
                                                :done    {:final? true}}}
                            :validate {:initial :checking
                                       :states {:checking {:on {:ok :done}}
                                                :done     {:final? true}}}}}
          {:keys [nodes edges]} (layout/project-definition ingest)
          node-ids (set (map :id nodes))
          ;; The synthetic ROOT-CONTAINER frame id legitimately
          ;; ends in `__` (a sentinel boundary no real node-id can mint, like
          ;; `machine-root-id`); exclude it from the trailing-`__` degeneracy
          ;; check, which targets the degenerate region-scoped empty-path id.
          region-node-ids (disj node-ids layout/root-container-id)]
      ;; NO synthetic machine-root node inside any region
      (is (empty? (filter :machine-root? nodes))
          "no synthetic machine-root node is minted for a region :on")
      ;; NO degenerate region-scoped empty-path id
      (is (not (contains? node-ids (str (layout/region-node-id :fetch) "__")))
          "no degenerate `region__fetch__` (empty path segment) node")
      (is (every? #(not (str/ends-with? % "__")) region-node-ids)
          "no real node id ends in a trailing `__` (the degenerate root marker)")
      ;; The region-level fallback edge IS projected, sourced from the
      ;; REGION CONTAINER (rid) into its in-region target — every edge
      ;; endpoint is a real projected node (no phantom/empty id).
      (doseq [e edges]
        (is (contains? node-ids (:source e))
            (str "edge source " (:source e) " is a real node"))
        (is (contains? node-ids (:target e))
            (str "edge target " (:target e) " is a real node")))
      (let [fallback (first (filter :machine-level? edges))]
        (is (some? fallback)
            "the region's top-level :on fallback edge is projected")
        (is (= (layout/region-node-id :fetch) (:source fallback))
            "the region fallback sources from the region container, not a phantom root")
        (is (= (layout/region-scoped-id :fetch [:loading]) (:target fallback))
            "the fallback resolves to the in-region target (:loading)")))))

(deftest project-definition-region-top-level-targetless-on-anchors-to-container
  (testing "a parallel region whose def carries a TARGETLESS /
            action-only TOP-LEVEL :on fallback (`:on {:abort {:action :log}}`)
            must anchor that internal fallback edge to the REGION CONTAINER on
            BOTH ends. Region-scoping the TARGET via
            `(region-scoped-id region-id (:to-path e))` would turn a
            targetless edge's `:to-path []` into the degenerate
            `region__fetch__` — a phantom edge endpoint. The flat machine's
            internal machine-level fallback self-anchors target = source =
            machine-root; the region analogue is the region container."
    (let [ingest {:type :parallel
                  :regions {:fetch    {:initial :loading
                                       :on      {:abort {:action :log}} ;; targetless
                                       :states {:loading {:on {:loaded :done}}
                                                :done    {:final? true}}}
                            :validate {:initial :checking
                                       :states {:checking {:on {:ok :done}}
                                                :done     {:final? true}}}}}
          {:keys [nodes edges]} (layout/project-definition ingest)
          node-ids (set (map :id nodes))]
      ;; NO synthetic machine-root node + NO degenerate region-scoped empty-path id
      (is (empty? (filter :machine-root? nodes))
          "no synthetic machine-root node is minted for a targetless region :on")
      (is (not (contains? node-ids (str (layout/region-node-id :fetch) "__")))
          "no degenerate `region__fetch__` (empty path segment) node is minted")
      ;; EVERY edge endpoint resolves to a real, canonical projected node
      (doseq [e edges]
        (is (contains? node-ids (:source e))
            (str "edge source " (:source e) " is a real node"))
        (is (contains? node-ids (:target e))
            (str "edge target " (:target e) " is a real node")))
      ;; the internal fallback edge is region-container-anchored on both ends
      (let [fallback (first (filter :machine-level? edges))]
        (is (some? fallback)
            "the region's targetless top-level :on fallback edge is projected")
        (is (true? (:internal? fallback))
            "the targetless fallback stays flagged :internal? (a hanging action chip)")
        (is (= (layout/region-node-id :fetch) (:source fallback))
            "the internal fallback sources from the region container")
        (is (= (layout/region-node-id :fetch) (:target fallback))
            "the internal fallback TARGETS the region container too — never the
             phantom `region__fetch__` node")))))

(deftest highlight-ids-same-name-region-states-attribute-per-region
  (testing "when two regions are both in a same-named `:done`
            state, the multi-active highlight lights EACH region's OWN
            `:done` node — not one node twice (with colliding ids, lighting
            region A's :done would also light B's)"
    (let [ingest {:type :parallel
                  :regions {:fetch    {:initial :loading
                                       :states {:loading {:on {:loaded :done}}
                                                :done    {:final? true}}}
                            :validate {:initial :checking
                                       :states {:checking {:on {:ok :done}}
                                                :done     {:final? true}}}}}
          {:keys [nodes]} (layout/project-definition ingest)
          node-ids (set (map :id (remove :region? nodes)))
          ;; BOTH regions reached their (same-named) :done leaf
          state    {:fetch :done :validate :done}
          ids      (layout/highlight-ids state)]
      (is (= 2 (count ids))
          "TWO distinct active leaves light up (one per region), not one")
      (is (= #{(layout/region-scoped-id :fetch [:done])
               (layout/region-scoped-id :validate [:done])}
             ids)
          "each region's :done is attributed to its OWN node-id")
      (is (every? #(contains? node-ids %) ids)
          "every active id is a REAL parsed region-state node (no phantom)"))))

;; ---- highlight-id ------------------------------------------------------

(deftest highlight-id-resolves-each-state-arm
  (testing "the single-active resolver maps a flat keyword and its
            1-element vector to the same leaf id, a hierarchical path to its
            deepest leaf, and returns nil for nil and for a region-map (a map
            is not a single-active state; `highlight-ids` resolves that arm)"
    (doseq [[label state expected]
            [["flat keyword"      :authing                     (layout/node-id [:authing])]
             ["1-element vector"  [:authing]                   (layout/node-id [:authing])]
             ["hierarchical path" [:authenticated :browsing]   (layout/node-id [:authenticated :browsing])]
             ["nil"               nil                          nil]
             ["region-map"        {:data :loading :form :neutral} nil]]]
      (is (= expected (layout/highlight-id state)) label))))

;; ---- highlight-ids — multi-active (G1) ----------------------------------
;;
;; Spec 005 §Snapshot shape: `:state` has three arms — flat keyword,
;; hierarchical path, OR a region-map (PARALLEL — N simultaneously-active
;; leaves). `highlight-ids` resolves ALL THREE to a SET of active-leaf
;; node-ids so the chart lights up EVERY active region at once (the §1.2
;; parity bar in 001-Topology-Parity.md).

(deftest highlight-ids-resolves-each-state-arm
  (testing "every `:state` arm resolves to the SET of active-leaf ids: a flat
            keyword and a hierarchical path to one leaf (the path's deepest),
            a region-map to one region-scoped leaf per region (the scoped id
            project-parallel mints, so same-named states in two regions stay
            distinct; a compound region contributes its deepest leaf, not one
            id per segment), and nil or an empty region-map to the empty set
            (a SET, never nil, so callers can always `contains?` it)"
    (doseq [[label state expected]
            [["flat keyword"          :authing
              #{(layout/node-id [:authing])}]
             ["hierarchical path"     [:authenticated :browsing]
              #{(layout/node-id [:authenticated :browsing])}]
             ["region-map"            {:data :loading :form :neutral :mode :active}
              #{(layout/region-scoped-id :data [:loading])
                (layout/region-scoped-id :form [:neutral])
                (layout/region-scoped-id :mode [:active])}]
             ["compound region value" {:auth [:authenticated :dashboard] :lifecycle :idle}
              #{(layout/region-scoped-id :auth [:authenticated :dashboard])
                (layout/region-scoped-id :lifecycle [:idle])}]
             ["nil"                   nil #{}]
             ["empty region-map"      {}  #{}]]]
      (is (= expected (layout/highlight-ids state)) label))))

(deftest highlight-ids-subsumes-highlight-id-for-single-active
  (testing "for a single-active state, `highlight-ids` is
            exactly `#{(highlight-id state)}` — the multi-active resolver
            is a strict superset of the single-active one"
    (doseq [state [:authing [:authenticated :browsing] [:a]]]
      (is (= #{(layout/highlight-id state)}
             (layout/highlight-ids state))
          (str "single-active " state " agrees with the set resolver")))))

;; ---- node-id ----------------------------------------------------------

(deftest node-id-distinct-for-distinct-paths
  (is (not= (layout/node-id [:authenticated])
            (layout/node-id [:authenticated :browsing]))))

;; ---- edge-label xstate-stately convention -----------------------------

;; Shape: `event [guard] / action`. Brackets + slash appear ONLY when
;; their segment is present, per xstate-stately.

(deftest edge-label-renders-each-segment-form
  (testing "each legal segment combination, the `:after` clock glyph with
            its `<ms>ms` suffix, the `:always` infinity glyph, and namespaced
            events and guards, which keep their namespace"
    (doseq [[label edge expected]
            [["event only"             {:event :submit}                              "submit"]
             ["event + guard"          {:event :submit :guard :authed?}              "submit [authed?]"]
             ["event + action"         {:event :submit :action :log-it}              "submit / log-it"]
             ["event + guard + action" {:event :submit :guard :authed? :action :log-it}
              "submit [authed?] / log-it"]
             [":after + guard + action" {:event :after-1500 :after 1500 :guard :timeout? :action :cleanup}
              "⌚ 1500ms [timeout?] / cleanup"]
             [":always + guard"        {:event :always :always? true :guard :ready?} "∞ [ready?]"]
             ["namespaced event"       {:event :auth/submit :guard :authed? :action :log-it}
              "auth/submit [authed?] / log-it"]
             ["namespaced guard"       {:event :submit :guard :auth/authed?}         "submit [auth/authed?]"]]]
      (is (= expected (layout/edge-label edge)) label))))

;; ---- event-line --------------------------------------------------------

(deftest event-line-omits-the-action-segment
  (testing "`event-line` does NOT emit `/ action`; that text form is
            `edge-label`'s job. The action surfaces as a pill in the chart and
            as the full text in `:data-event` via `edge-label`."
    (doseq [[label edge expected]
            [["event + action"         {:event :submit :action :log-it}                 "submit"]
             ["event + guard + action" {:event :submit :guard :authed? :action :log-it} "submit [authed?]"]]]
      (is (= expected (layout/event-line edge)) label))))

(deftest event-segment-after-iso-duration-renders-milliseconds
  (testing "an ISO-8601 `:after` delay key renders its milliseconds, not the
            ISO spelling with an `ms` suffix glued on"
    (is (= "⌚ 1000ms" (layout/event-segment {:after "PT1S"})))
    (is (= "⌚ 500ms" (layout/event-segment {:after "PT0.5S"}))))
  (testing "an integer-millisecond delay keeps its label"
    (is (= "⌚ 1500ms" (layout/event-segment {:after 1500})))))

;; ---- name-of (the public guard/action/entry/exit stringifier) ----------
;;
;; `name-of` is the single public helper `event-line` / `edge-label` /
;; `chart.projection` all build on to render a guard / action / entry /
;; exit value as a short label string. The keyword arms are exercised
;; transitively by the edge-label tests above; the two LOAD-BEARING
;; branches — an inlined `(fn ...)` guard/action, which the docstring
;; calls out as the `#object[Function]` failure mode it guards — need a
;; direct pin of their own. These deterministic cases nail each arm.

(deftest name-of-renders-each-arm
  (testing "nil passes through (upstream cond-> arms skip the segment); a
            keyword renders via `name`, a namespaced one as `ns/name`; a fn
            renders its `:name` meta, or the literal `\"fn\"` when it has none,
            never `#object[Function]`; any other value falls through to `str`"
    (doseq [[label value expected]
            [["nil"                nil                                          nil]
             ["plain keyword"      :authed?                                     "authed?"]
             ["namespaced keyword" :auth/admin?                                 "auth/admin?"]
             ["named fn"           (with-meta (fn [_] true) {:name 'do-thing})  "do-thing"]
             ["anonymous fn"       (fn [_] true)                                "fn"]
             ["string"             "raw"                                        "raw"]
             ["symbol"             'go!                                         "go!"]
             ["number"             42                                           "42"]]]
      (is (= expected (layout/name-of value)) label))))

(deftest project-definition-emits-event-label-with-guard-and-action
  (testing "project-definition emits the full xstate label on every edge"
    (let [m {:initial :idle
             :states  {:idle {:on {:submit [{:target :loading
                                             :guard  :authed?
                                             :action :log-it}
                                            {:target :failed
                                             :guard  :anon?}]}}
                       :loading {}
                       :failed  {}}}
          {:keys [edges]} (layout/project-definition m)
          labels (set (map :event-label edges))]
      (is (contains? labels "submit [authed?] / log-it"))
      (is (contains? labels "submit [anon?]")))))

;; ---- self-transitions --------------------------------------------------
;;
;; Spec 005 §Self-transitions (XState v5 semantics): a TARGETED self-
;; transition (`:target :same-state` / a self keyword) is INTERNAL BY
;; DEFAULT — its own :exit/:entry do NOT re-run; only `:reenter? true`
;; makes it EXTERNAL. Omitting `:target` is the targetless internal no-op
;; (only :action runs). All chart as a self-loop (source == target). A
;; projector that resolved `:same-state` literally would mint a phantom
;; node-id, and one that skipped the targetless form would emit nothing.

(deftest project-definition-self-transition-same-state
  (testing "`:target :same-state` resolves to the source
            path itself (a true self-loop), NOT a phantom :same-state
            node"
    (let [m {:initial :a :states {:a {:on {:ping {:target :same-state}}} }}
          {:keys [nodes edges]} (layout/project-definition m)
          node-ids (set (map :id nodes))
          self     (first (filter #(= :ping (:event %)) edges))]
      (is (some? self) "the :ping self-transition is charted")
      (is (= [:a] (:from self)))
      (is (= [:a] (:to self)) "target resolves to the source path")
      (is (= (:source self) (:target self)) "source == target → self-loop")
      (is (contains? node-ids (:target self))
          "the self-loop target is a REAL node (no phantom :same-state)"))))

(deftest project-definition-internal-self-transition-omit-target
  (testing "a transition that omits :target (internal —
            runs only :action) charts as a self-anchored edge flagged
            :internal? rather than silently dropping"
    (let [m {:initial :a :states {:a {:on {:tick {:action :inc}}}}}
          {:keys [edges]} (layout/project-definition m)
          tick (first (filter #(= :tick (:event %)) edges))]
      (is (some? tick) "the internal :tick transition is charted")
      (is (= [:a] (:from tick)))
      (is (= [:a] (:to tick)) "internal transition self-anchors")
      (is (true? (:internal? tick)) "flagged internal")
      (is (= :inc (:action tick))))))

;; ---- wildcard `:*` -----------------------------------------------------

(deftest project-definition-wildcard-event-label
  (testing "the `:*` wildcard `:on` arm (Spec 005
            §Wildcard) renders as `* (any)`, not a bare `*` that reads
            like a real event"
    (let [m {:initial :a :states {:a {:on {:* :b}} :b {}}}
          {:keys [edges]} (layout/project-definition m)
          wild (first (filter #(= :* (:event %)) edges))]
      (is (some? wild) "the wildcard transition is charted")
      (is (= "* (any)" (:event-label wild))))))

;; ---- machine-level (top-level) :on fallback ----------------------------

(deftest project-definition-machine-level-on-fallback
  (testing "a top-level (machine-level) :on fallback
            (Spec 005 — `:on` valid per-state AND top-level) charts
            EXACTLY ONE edge, sourced from the synthetic MACHINE-ROOT
            node, flagged :machine-level? — NOT one back-edge per leaf
            (a per-state repetition would scramble ordering)"
    (let [m {:initial :a :on {:logout :a} :states {:a {} :b {}}}
          {:keys [nodes edges]} (layout/project-definition m)
          logout (filter #(= :logout (:event %)) edges)]
      (is (= 1 (count logout)) "ONE machine-level edge, not one per leaf")
      (is (every? :machine-level? logout) "flagged machine-level")
      (is (= #{[]} (set (map :from logout)))
          "sourced from the root context, not a concrete leaf")
      (is (= layout/machine-root-id (:source (first logout)))
          "its canonical :source is the synthetic MACHINE-ROOT node id")
      (is (every? #(= [:a] (:to %)) logout) "lands on the target")
      ;; The synthetic root node is surfaced so the chip has a source.
      (let [root (first (filter :machine-root? nodes))]
        (is (some? root) "the synthetic MACHINE-ROOT node is present")
        (is (= layout/machine-root-id (:id root)))
        (is (= [] (:path root)))))))

(deftest project-definition-no-machine-level-on-emits-no-root-node
  (testing "a machine with NO top-level :on projects only
            its own states: no synthetic MACHINE-ROOT node leaks in"
    (let [m {:initial :a :states {:a {:on {:go :b}} :b {}}}
          {:keys [nodes edges]} (layout/project-definition m)]
      (is (empty? (filter :machine-root? nodes))
          "no root node when there is no machine-level fallback")
      (is (not-any? #(= layout/machine-root-id (:id %)) nodes))
      (is (not-any? :machine-level? edges)))))

(deftest project-definition-machine-level-on-targets-top-level-state
  (testing "a machine-level :on target is a TOP-LEVEL state,
            resolved at the root; the SINGLE projected edge lands on the
            top-level target regardless of which leaf inherits it at
            runtime"
    (let [m {:initial :authenticated
             :on      {:logout :unauth}
             :states  {:unauth        {}
                       :authenticated {:initial :browsing
                                       :states  {:browsing {}
                                                 :paying   {}}}}}
          {:keys [edges]} (layout/project-definition m)
          logout (filter #(= :logout (:event %)) edges)]
      (is (= 1 (count logout)) "ONE machine-level edge for the fallback")
      (is (every? #(= [:unauth] (:to %)) logout)
          "the inherited :logout lands on the top-level :unauth")
      (is (= layout/machine-root-id (:source (first logout)))
          "sourced from the MACHINE-ROOT node, not a specific leaf"))))

(deftest project-definition-machine-level-on-targetless-action-only
  (testing "a TARGETLESS (action-only) machine-level :on
            fallback self-anchors on the synthetic MACHINE-ROOT chip as an
            :internal? affordance (runtime fires the action + leaves the
            state as it is — XState v5 targetless semantics), mirroring
            the parallel-root :on, rather than being silently DROPPED."
    (let [m {:initial :a
             :on      {:ping {:action :log-ping}}   ;; no :target
             :states  {:a {} :b {}}}
          {:keys [nodes edges]} (layout/project-definition m)
          pings (filter #(= :ping (:event %)) edges)]
      (is (= 1 (count pings)) "ONE machine-level affordance, not dropped")
      (let [e (first pings)]
        (is (true? (:machine-level? e)) "flagged as an inherited fallback")
        (is (true? (:internal? e)) "targetless → internal self-anchored chip")
        (is (= [] (:from e)) "sourced from the root context")
        (is (= [] (:to-path e)) "no target — self-anchored")
        (is (= layout/machine-root-id (:source e)))
        (is (= layout/machine-root-id (:target e))
            "self-anchored on the MACHINE-ROOT chip (state unchanged)")
        (is (= :log-ping (:action e)) "the inherited action is preserved"))
      (let [root (first (filter :machine-root? nodes))]
        (is (some? root) "the MACHINE-ROOT chip anchors the affordance")
        (is (= layout/machine-root-id (:id root)))))))

(deftest project-definition-machine-level-on-wildcard-targetless
  (testing "a `:*` wildcard machine-level :on with no target is
            an action-only inherited fallback too; it self-anchors on the
            MACHINE-ROOT chip rather than being dropped"
    (let [m {:initial :a
             :on      {:* {:action :audit}}   ;; wildcard, no :target
             :states  {:a {} :b {}}}
          {:keys [edges]} (layout/project-definition m)
          wild (filter #(= :* (:event %)) edges)]
      (is (= 1 (count wild)) "ONE wildcard affordance, not dropped")
      (let [e (first wild)]
        (is (true? (:machine-level? e)))
        (is (true? (:internal? e)) "targetless wildcard → internal chip")
        (is (= layout/machine-root-id (:source e)))
        (is (= layout/machine-root-id (:target e)))
        (is (= :audit (:action e)))))))

;; ---- node-id injectivity -----------------------------------------------

(deftest node-id-is-injective-over-hyphen-ns-underscore
  (testing "distinct paths mint DISTINCT ids. A
            `[^a-zA-Z0-9_]`-collapse would merge `:a/b`, `:a-b`, `:a_b` all
            to `\"a_b\"` (a React key collision drops a node)"
    (let [ids (map (comp layout/node-id vector) [:a/b :a-b :a_b :logged-in :logged_in])]
      (is (= (count ids) (count (set ids)))
          "all five distinct keywords yield distinct ids")
      (is (not= (layout/node-id [:a/b]) (layout/node-id [:a-b])))
      (is (not= (layout/node-id [:a-b]) (layout/node-id [:a_b])))
      (is (not= (layout/node-id [:logged-in]) (layout/node-id [:logged_in]))))))

(deftest node-id-distinct-from-region-container-id
  (testing "a state literally named :region__foo cannot
            collide with a region container's id"
    (is (not= (layout/node-id [:region__foo])
              (layout/region-node-id :foo)))))

;; ---- edge-id collision -------------------------------------------------

(deftest project-definition-edge-ids-distinct-for-identical-candidates
  (testing "even byte-identical candidates (same target,
            no guard/action) get distinct ids via the per-key ordinal"
    (let [m {:initial :a
             :states  {:a {:on {:go [{:target :b} {:target :b}]}}
                       :b {}}}
          {:keys [edges]} (layout/project-definition m)
          ids (map :id (filter #(= :go (:event %)) edges))]
      (is (= 2 (count ids)))
      (is (= 2 (count (set ids))) "ordinal disambiguates identical candidates"))))

;; ---- entry / exit state actions ----------------------------------------

(deftest project-definition-threads-entry-exit-onto-nodes
  (testing ":entry / :exit state actions (Spec 005
            §State nodes) surface as name strings on the parsed node"
    (let [m {:initial :a
             :states  {:a {:entry :on-enter :exit :on-leave}
                       :b {:entry (with-meta (fn [_]) {:name 'do-thing})}}}
          {:keys [nodes]} (layout/project-definition m)
          a (first (filter #(= [:a] (:path %)) nodes))
          b (first (filter #(= [:b] (:path %)) nodes))]
      (is (= "on-enter" (:entry a)))
      (is (= "on-leave" (:exit a)))
      (is (= "do-thing" (:entry b)) "fn entry surfaces its :name meta")
      (is (not (contains? b :exit)) "absent exit is omitted"))))

(deftest project-definition-no-entry-exit-when-absent
  (testing "a state with no :entry / :exit carries
            neither key (cond-> skips the assoc)"
    (let [{:keys [nodes]} (layout/project-definition idle-loading-success)
          idle (first (filter #(= [:idle] (:path %)) nodes))]
      (is (not (contains? idle :entry)))
      (is (not (contains? idle :exit))))))

;; ---- :final? is always boolean -----------------------------------------

(deftest project-definition-final-flag-is-boolean
  (testing ":final? is boolean-wrapped (false, not nil)
            for non-final states, matching its sibling flags"
    (let [{:keys [nodes]} (layout/project-definition idle-loading-success)
          idle    (first (filter #(= [:idle] (:path %)) nodes))
          success (first (filter #(= [:success] (:path %)) nodes))]
      (is (false? (:final? idle)) ":final? is false, not nil")
      (is (true?  (:final? success))))))

;; ---- :error? error-terminal KIND threads through parse ------------------
;;
;; An `:error?` final (Spec 005 §:final?) is a re-frame2 EXTENSION: a child
;; finishing via it routes the spawning parent's `:spawn` `:on-error` rather
;; than `:on-done`. The chart surfaces the terminal KIND so the renderer can
;; paint the error-hue outer ring (NOT XState/Stately parity — XState has no
;; first-class error-final flag).

(deftest project-definition-error-flag-needs-final
  (testing "a stray :error? on a NON-final node is
            REJECTED by the recursive grammar gate (:error? is only
            meaningful on a :final? state). project-definition rejects the
            machine BEFORE graph construction and returns a value-free
            :definition-error carrying the canonical
            :rf.error/machine-error-flag-without-final defect, rather than
            projecting the node with :error? silently gated to false."
    (let [result (layout/project-definition
                  {:initial :a
                   :states  {:a {:error? true :on {:go :b}}
                             :b {}}})]
      (is (empty? (:nodes result))
          "no nodes/edges are projected for the rejected machine (no ELK, no orphans)")
      (is (empty? (:edges result)))
      (is (= :rf.error/machine-error-flag-without-final
             (get-in result [:definition-error :defect :category]))
          ":error? on a non-final node surfaces the canonical error-flag-without-final defect"))))

(deftest project-definition-definition-error-is-content-free-and-rejection-only
  (testing "a structurally-invalid definition's `:definition-error` is
            content-free BY CONSTRUCTION: beside the category it carries
            CARDINALITY, never material read off the definition — no `:keys`
            (every top-level key, uncapped) and no defect `:path`, but the
            top-level key COUNT and the defect's DEPTH. That projection
            rejects these BEFORE ELK with the canonical category is
            `recursively-invalid-rejected-by-every-surface` in the
            cross-emitter ns; the full summary grammar is
            `definition-summary-is-content-free-by-construction` in the
            grammar-validation ns."
    (doseq [[label definition]
            [["nested compound missing :initial"
              {:initial :outer :states {:outer {:states {:inner {}}}}}]
             ["dangling transition target"
              {:initial :idle :states {:idle {:on {:go :missing}}}}]
             ["unknown bare node key"
              {:initial :idle :states {:idle {:on-entry :oops}}}]]]
      (let [{:keys [definition-error]} (layout/project-definition definition)]
        (is (nil? (:keys definition-error))
            (str label ": no raw top-level key set rides the projection result"))
        (is (nil? (get-in definition-error [:defect :path]))
            (str label ": no raw state-id path either"))
        (is (integer? (:count definition-error))
            (str label ": carries the structural top-level key COUNT"))
        (is (integer? (get-in definition-error [:defect :depth]))
            (str label ": carries the defect's DEPTH, not its path")))))

  (testing "a nil definition is NOT an error (it is the empty-state
            placeholder), and a VALID definition carries none"
    (is (nil? (:definition-error (layout/project-definition nil))))
    (is (nil? (:definition-error
               (layout/project-definition {:initial :a :states {:a {:on {:go :b}} :b {}}})))
        "a valid definition carries no :definition-error")))

;; ---- :on-done (XState onDone) completion edge ---------------------------
;;
;; Spec 005 §The done-state signal: a COMPOUND node's `:on-done` advances
;; the OUTER flow to a SIBLING target when its `:final?` child is reached
;; (the machine KEEPS RUNNING). A PARALLEL-ROOT's `:on-done` runs
;; action/fx ONLY (no :target — registration rejects one), rendered as a
;; TERMINAL completion affordance. A chart that did not parse `:on-done`
;; would understate the real control flow.

(def checkout-on-done
  "Spec 005 §The done-state signal example: a sub-flow `:flow` inside a
  compound. When `:flow` reaches its `:final?` `:paid` child, `:flow`'s
  `:on-done` advances the machine to the SIBLING `:next`."
  {:initial :flow
   :states  {:flow {:initial :collecting
                    :on-done :next                       ;; ← sibling of :flow
                    :states  {:collecting {:on {:submit :submitting}}
                              :submitting {:on {:ok :paid}}
                              :paid       {:final? true}}}
             :next {:on {:reset [:flow]}}}})

(deftest project-definition-compound-on-done-is-sibling-edge
  (testing "a compound `:on-done` projects ONE completion edge
            from the compound to its SIBLING target (resolved relative to
            the compound's OWN level), flagged :on-done?, carrying the
            done-path (the engine's done.state node)"
    (let [{:keys [edges]} (layout/project-definition checkout-on-done)
          od (filter :on-done? edges)]
      (is (= 1 (count od)) "exactly one :on-done completion edge")
      (let [e (first od)]
        (is (= [:flow] (:from e)) "sourced from the compound itself")
        (is (= [:next] (:to e)) "lands on the SIBLING :next (compound-level resolution)")
        (is (= :rf.machine/done (:event e)) "carries the reserved done event")
        (is (= [:flow] (:done-path e)) "the done.state node is the compound's path")
        (is (= "✓ done" (:event-label e))
            "renders the completion ✓ done chip, not an ordinary event arrow")
        (is (not (:internal? e)) "a targeted compound :on-done is a real sibling edge")))))

(deftest project-definition-no-on-done-emits-no-completion-edge
  (testing "a machine with no :on-done emits no :on-done? edge
            (no false-positive completion arrows)"
    (let [{:keys [edges]} (layout/project-definition compound-machine)]
      (is (empty? (filter :on-done? edges))))))

(deftest project-definition-compound-on-done-guarded-candidate-vector
  (testing "an :on-done candidate-vector (guarded forks)
            projects each target-bearing arm as its own completion edge,
            mirroring the :on candidate-vector grammar"
    (let [m {:initial :flow
             :states  {:flow {:initial :work
                              :on-done [{:target :ok-next :guard :ok?}
                                        {:target :err-next :guard :err?}]
                              :states  {:work {:on {:finish :done}}
                                        :done {:final? true}}}
                       :ok-next  {}
                       :err-next {}}}
          {:keys [edges]} (layout/project-definition m)
          od (filter :on-done? edges)]
      (is (= 2 (count od)) "both guarded completion arms project")
      (is (= #{[:ok-next] [:err-next]} (set (map :to od))))
      (is (every? #(= [:flow] (:from %)) od) "both sourced from the compound")
      (is (= #{:ok? :err?} (set (map :guard od))) "guards preserved"))))

(def ingest-parallel-on-done
  "Spec 005 §The done-state signal parallel example: three orthogonal
  axes; when ALL settle final, the parallel-root `:on-done` runs its
  action (no :target — a parallel root is root-only)."
  {:type    :parallel
   :on-done {:action :announce}                 ;; ← parallel-root onDone (action only)
   :regions {:fetch    {:initial :loading :states {:loading {:on {:loaded :done}} :done {:final? true}}}
             :validate {:initial :checking :states {:checking {:on {:ok :done}} :done {:final? true}}}
             :index    {:initial :building :states {:building {:on {:built :done}} :done {:final? true}}}}})

(deftest project-definition-parallel-root-on-done-is-terminal-affordance
  (testing "a PARALLEL-ROOT `:on-done` (action/fx-only, no
            :target — registration rejects one) projects a TERMINAL
            completion affordance (self-anchored, :internal?), NOT a
            sibling edge; carries the action + :parallel-root? flag"
    (let [{:keys [edges nodes]} (layout/project-definition ingest-parallel-on-done)
          od (filter :on-done? edges)]
      (is (= 1 (count od)) "one parallel-root completion affordance")
      (let [e (first od)]
        (is (true? (:parallel-root? e)) "flagged parallel-root")
        (is (true? (:internal? e))
            "a target-less parallel-root :on-done self-anchors (terminal, no sibling segment)")
        (is (= (:source e) (:target e)) "terminal affordance: source == target")
        (is (= :announce (:action e)) "the parallel-root action is preserved")
        (is (= "✓ done / announce" (:event-label e))
            "renders the completion chip with its action"))
      ;; the synthetic parallel-root node is surfaced as the affordance anchor
      (let [root (first (filter :parallel-root? nodes))]
        (is (some? root) "a synthetic parallel-root node anchors the affordance")
        (is (= (:source (first od)) (:id root))
            "the completion edge anchors on the parallel-root node")))))

(deftest project-definition-bare-parallel-mints-no-synthetic-root
  (testing "a parallel machine with NO :on-done and NO root :on projects
            only its regions' nodes and edges: no synthetic parallel-root
            node, no completion edge, no MACHINE-ROOT chip, no root :on edge"
    (let [m {:type :parallel
             :regions {:a {:initial :x :states {:x {:on {:go :y}} :y {}}}
                       :b {:initial :p :states {:p {:on {:go :q}} :q {}}}}}
          {:keys [nodes edges]} (layout/project-definition m)]
      (is (empty? (filter :parallel-root? nodes)))
      (is (empty? (filter :on-done? edges)))
      (is (empty? (filter :machine-root? nodes))
          "no synthetic MACHINE-ROOT chip without a root :on")
      (is (not-any? #(= layout/machine-root-id (:id %)) nodes))
      (is (empty? (filter :parallel-root-on? edges))
          "no root :on edges"))))

;; ---- root parallel `:on` projection -------------------------------------
;;
;; A `:type :parallel` machine's OWN top-level `:on` is the ANCESTOR FALLBACK
;; for its regions (Spec 005 §Root parallel `:on`, verified against
;; xstate@5.32.0). Runtime semantics: when no region-local transition
;; handles the event the root `:on` fires, moving one or more REGION-QUALIFIED
;; targets atomically (untargeted regions stay put). The projection sources
;; each root `:on` edge from the synthetic MACHINE-ROOT chip into the region-
;; scoped target node (a targetless action-only root `:on` self-anchors on the
;; chip as an internal affordance); dropping the root `:on` would leave Xray
;; unable to render the transition in topology or highlight it on a focused
;; event.

(deftest project-definition-parallel-root-on-single-region-target
  (testing "a root :on targeting ONE region `[:a :two]` projects
            ONE edge from the MACHINE-ROOT chip into region :a's :two node"
    ;; Mirrors spec/conformance/fixtures/parallel-root-on-single-region-target.
    (let [m {:type    :parallel
             :on      {:one {:target [:a :two]}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
          {:keys [nodes edges]} (layout/project-definition m)
          root-ons (filter :parallel-root-on? edges)]
      (is (= 1 (count root-ons)) "exactly one root-:on edge")
      (let [e (first root-ons)]
        (is (true? (:machine-level? e)) "flagged as an inherited fallback")
        (is (= [] (:from-path e)) "sourced from the root context")
        (is (= [:a :two] (:to-path e)) "region-qualified target path")
        (is (= :one (:event e)))
        (is (= layout/machine-root-id (:source e))
            "sourced from the synthetic MACHINE-ROOT chip")
        (is (= (layout/region-scoped-id :a [:two]) (:target e))
            "lands on region :a's :two node (region-scoped)")
        (is (not (:internal? e)) "a region-targeting root :on is not internal"))
      ;; the synthetic MACHINE-ROOT chip is surfaced as the anchor
      (let [root (first (filter :machine-root? nodes))]
        (is (some? root) "the synthetic MACHINE-ROOT chip is present")
        (is (= layout/machine-root-id (:id root)))))))

(deftest project-definition-parallel-root-on-multi-region-target
  (testing "a root :on with MULTIPLE region-qualified targets
            `[[:a :x] [:b :y]]` projects ONE edge per region, both sourced
            from the MACHINE-ROOT chip; the untargeted region :c gets none"
    ;; Mirrors spec/conformance/fixtures/parallel-root-on-multi-region-target.
    (let [m {:type    :parallel
             :on      {:advance {:target [[:a :x] [:b :y]] :action :bump}}
             :regions {:a {:initial :one :states {:one {} :x {}}}
                       :b {:initial :one :states {:one {} :y {}}}
                       :c {:initial :one :states {:one {}}}}}
          {:keys [edges]} (layout/project-definition m)
          root-ons (filter :parallel-root-on? edges)]
      (is (= 2 (count root-ons)) "one root-:on edge per region-qualified target")
      (is (= #{[:a :x] [:b :y]} (set (map :to-path root-ons))))
      (is (every? #(= layout/machine-root-id (:source %)) root-ons)
          "both sourced from the MACHINE-ROOT chip")
      (is (= #{(layout/region-scoped-id :a [:x])
               (layout/region-scoped-id :b [:y])}
             (set (map :target root-ons)))
          "each lands on its region-scoped target node")
      (is (every? #(= :bump (:action %)) root-ons) "the root action is preserved")
      ;; no root :on edge addresses the untargeted :c region
      (is (not-any? #(= :c (first (:to-path %))) root-ons)
          "the untargeted region :c gets no root :on edge"))))

(deftest project-definition-parallel-root-on-targetless-action-only
  (testing "a TARGETLESS action-only root :on self-anchors on
            the MACHINE-ROOT chip as an internal affordance (moves no region)"
    (let [m {:type    :parallel
             :on      {:ping {:action :log-ping}}   ;; no :target
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
          {:keys [nodes edges]} (layout/project-definition m)
          root-ons (filter :parallel-root-on? edges)]
      (is (= 1 (count root-ons)) "one root-:on affordance")
      (let [e (first root-ons)]
        (is (true? (:internal? e)) "targetless → internal self-anchored chip")
        (is (= [] (:to-path e)) "no region-qualified target")
        (is (= layout/machine-root-id (:source e)))
        (is (= layout/machine-root-id (:target e))
            "self-anchored on the MACHINE-ROOT chip (moves no region)")
        (is (= :log-ping (:action e)) "the action is preserved"))
      (is (some? (first (filter :machine-root? nodes)))
          "the MACHINE-ROOT chip anchors the affordance"))))

(deftest project-definition-parallel-root-on-edge-ids-distinct-and-machine-root-prefixed
  (testing "multi-region root :on edges mint DISTINCT ids
            (no xyflow duplicate-id drop) carrying the MACHINE-ROOT source"
    (let [m {:type    :parallel
             :on      {:advance {:target [[:a :x] [:b :y]]}}
             :regions {:a {:initial :one :states {:one {} :x {}}}
                       :b {:initial :one :states {:one {} :y {}}}}}
          {:keys [edges]} (layout/project-definition m)
          root-ons (filter :parallel-root-on? edges)
          ids      (map :id root-ons)]
      (is (= 2 (count ids)))
      (is (= 2 (count (set ids))) "the two root :on edge ids are distinct")
      (is (every? string? ids))
      (is (every? #(str/starts-with? % layout/machine-root-id) ids)
          "each id reads from the MACHINE-ROOT source segment"))))

;; ---- root parallel `:after` projection ----------------------------------
;;
;; A `:type :parallel` root MAY declare its own `:after` — the
;; TIMER-DRIVEN analog of the root `:on` ancestor fallback. It is root-owned
;; (scheduled at machine birth), and when it fires it runs its `:action` once
;; and atomically moves one or more REGION-QUALIFIED targets (untargeted
;; regions stay put) — identical apply grammar to the root `:on`. The
;; projection sources each root `:after` edge from the synthetic MACHINE-ROOT
;; chip into the region-scoped target node, carrying `:after <delay>` +
;; `:parallel-root-after? true`; dropping it would leave a machine-lifetime
;; timeout invisible in topology.

(deftest project-definition-parallel-root-after-single-region-target
  (testing "a root :after targeting ONE region `[:a :two]`
            projects ONE edge from the MACHINE-ROOT chip into region :a's
            :two node, carrying :after <delay>"
    (let [m {:type    :parallel
             :after   {500 {:target [:a :two]}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
          {:keys [nodes edges]} (layout/project-definition m)
          root-afters (filter :parallel-root-after? edges)]
      (is (= 1 (count root-afters)) "exactly one root-:after edge")
      (let [e (first root-afters)]
        (is (true? (:machine-level? e)) "flagged as an inherited fallback")
        (is (true? (:parallel-root-on? e))
            "shares the root :on re-pointing path (machine-root → region-scoped)")
        (is (= 500 (:after e)) "carries the delay so event-segment paints ⌚")
        (is (= [] (:from-path e)) "sourced from the root context")
        (is (= [:a :two] (:to-path e)) "region-qualified target path")
        (is (= layout/machine-root-id (:source e))
            "sourced from the synthetic MACHINE-ROOT chip")
        (is (= (layout/region-scoped-id :a [:two]) (:target e))
            "lands on region :a's :two node (region-scoped)")
        (is (not (:internal? e)) "a region-targeting root :after is not internal"))
      (is (some? (first (filter :machine-root? nodes)))
          "the synthetic MACHINE-ROOT chip is surfaced (anchors the :after)"))))

(deftest project-definition-parallel-root-after-multi-region-target
  (testing "a root :after with MULTIPLE region-qualified targets
            `[[:a :two] [:b :two]]` projects ONE edge per region; the
            untargeted region gets none"
    (let [m {:type    :parallel
             :after   {1000 {:target [[:a :two] [:b :two]] :action :bump}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}
                       :c {:initial :one :states {:one {}}}}}
          {:keys [edges]} (layout/project-definition m)
          root-afters (filter :parallel-root-after? edges)]
      (is (= 2 (count root-afters)) "one root-:after edge per region-qualified target")
      (is (= #{[:a :two] [:b :two]} (set (map :to-path root-afters))))
      (is (every? #(= 1000 (:after %)) root-afters) "each carries the delay")
      (is (every? #(= :bump (:action %)) root-afters) "the root action is preserved")
      (is (every? #(= layout/machine-root-id (:source %)) root-afters))
      (is (not-any? #(= :c (first (:to-path %))) root-afters)
          "the untargeted region :c gets no root :after edge"))))

(deftest project-definition-parallel-root-after-action-only
  (testing "a TARGETLESS action-only root :after self-anchors on
            the MACHINE-ROOT chip as an internal affordance (moves no region)"
    (let [m {:type    :parallel
             :after   {2000 {:action :timeout-log}}   ;; no :target
             :regions {:a {:initial :one :states {:one {}}}
                       :b {:initial :one :states {:one {}}}}}
          {:keys [nodes edges]} (layout/project-definition m)
          root-afters (filter :parallel-root-after? edges)]
      (is (= 1 (count root-afters)) "one root-:after affordance")
      (let [e (first root-afters)]
        (is (true? (:internal? e)) "targetless → internal self-anchored chip")
        (is (= 2000 (:after e)))
        (is (= [] (:to-path e)) "no region-qualified target")
        (is (= layout/machine-root-id (:source e)))
        (is (= layout/machine-root-id (:target e))
            "self-anchored on the MACHINE-ROOT chip (moves no region)")
        (is (= :timeout-log (:action e)) "the action is preserved"))
      (is (some? (first (filter :machine-root? nodes)))
          "the MACHINE-ROOT chip anchors the affordance"))))

(deftest project-definition-parallel-root-on-and-after-coexist
  (testing "a root :on AND a root :after to the SAME
            region target coexist as DISTINCT edges (no id collision)"
    (let [m {:type    :parallel
             :on      {:go {:target [:a :two]}}
             :after   {1000 {:target [:a :two]}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {}}}}}
          {:keys [edges]} (layout/project-definition m)
          root-edges (filter :parallel-root-on? edges)
          ids        (map :id root-edges)]
      (is (= 2 (count root-edges)) "both the root :on and root :after edges project")
      (is (= 1 (count (filter :parallel-root-after? root-edges)))
          "exactly one is the :after edge")
      (is (= 1 (count (remove :parallel-root-after? root-edges)))
          "exactly one is the plain :on edge")
      (is (= 2 (count (set ids)))
          "the :on and :after edges to the same target mint DISTINCT ids"))))

;; ---- consumer-attachment requirements ----------------------------------
;;
;; EP-0017 / Spec 005 §Consumer attachment: a fact-consuming named guard /
;; action declares `:rf.cofx/requires` on its `:guards` / `:actions` entry
;; MAP. `attach-cofx-requires` resolves the transition / lifecycle ref
;; against that registry and surfaces the declared IDS (never the `:fn`).

(def cofx-machine
  "A machine whose named guard, transition action, entry action, and exit
  action each declare `:rf.cofx/requires`, plus a bare-fn guard / undeclared
  action that declare nothing (so the no-facts case carries no requirement).
  Mirrors Spec 005 §Consumer attachment's worked example."
  {:initial :idle
   :guards  {:within-window? {:rf.cofx/requires [:rf/time-ms]
                              :fn (fn [_] true)}
             :always-ok?     (fn [_] true)}            ;; bare fn → no diet
   :actions {:schedule-retry {:rf.cofx/requires [:payment/retry-jitter-ms]
                             :fn (fn [_] nil)}
             :stamp-started  {:rf.cofx/requires [:rf/time-ms]
                             :fn (fn [_] nil)}
             :stamp-ended    {:rf.cofx/requires [[:ui/local-theme "theme"]]
                             :fn (fn [_] nil)}
             :log-it         (fn [_] nil)}             ;; bare fn → no diet
   :states  {:idle    {:entry :stamp-started
                       :exit  :stamp-ended
                       :on    {:go {:target :busy
                                    :guard  :within-window?
                                    :action :schedule-retry}
                               :noop {:target :busy
                                      :guard  :always-ok?
                                      :action :log-it}}}
             :busy    {}}})

(defn- edge-with-event [edges ev]
  (first (filter #(= ev (:event %)) edges)))

(deftest cofx-requires-on-guard-and-action-edges
  (testing "a named guard / action declaring :rf.cofx/requires
            surfaces its declared IDS (compact strings) on the edge"
    (let [{:keys [edges]} (layout/project-definition cofx-machine)
          go-edge   (edge-with-event edges :go)
          noop-edge (edge-with-event edges :noop)]
      (is (= ["rf/time-ms"] (:guard-requires go-edge))
          "the guard's :rf.cofx/requires id surfaces")
      (is (= ["payment/retry-jitter-ms"] (:action-requires go-edge))
          "the action's :rf.cofx/requires id surfaces")
      ;; The undeclared (bare-fn) guard/action edge carries NOTHING — the
      ;; no-facts case renders no requirement.
      (is (nil? (:guard-requires noop-edge))
          "a bare-fn guard declares no requires → no key")
      (is (nil? (:action-requires noop-edge))
          "a bare-fn action declares no requires → no key"))))

(deftest cofx-requires-on-entry-and-exit-nodes
  (testing "a named entry / exit action declaring
            :rf.cofx/requires surfaces its IDS on the state node"
    (let [{:keys [nodes]} (layout/project-definition cofx-machine)
          idle (first (filter #(= [:idle] (:path %)) nodes))]
      (is (= ["rf/time-ms"] (:entry-requires idle))
          "the entry action's :rf.cofx/requires id surfaces on the node")
      (is (= ["ui/local-theme(\"theme\")"] (:exit-requires idle))
          "the exit action's parameterized [id arg] requirement reads as id(arg)"))))

(deftest cofx-requires-noop-for-fact-free-machine
  (testing "a machine declaring no :rf.cofx/requires anywhere
            projects NO requires keys on any edge or node"
    (let [{:keys [edges nodes]} (layout/project-definition idle-loading-success)]
      (is (not-any? :guard-requires edges))
      (is (not-any? :action-requires edges))
      (is (not-any? :entry-requires nodes))
      (is (not-any? :exit-requires nodes)))))

(deftest cofx-requires-never-serialises-fn-or-source
  (testing "the surfaced requirements carry IDS only — never
            the executable :fn nor any :source-* snippet"
    (let [{:keys [edges nodes]} (layout/project-definition cofx-machine)
          all (concat (mapcat (juxt :guard-requires :action-requires) edges)
                      (mapcat (juxt :entry-requires :exit-requires) nodes))]
      (is (every? (fn [reqs] (every? string? (or reqs []))) all)
          "every surfaced requirement is a plain display string")
      (is (not-any? (fn [reqs] (some #(re-find #"fn|source" (str %)) (or reqs [])))
                    all)
          "no :fn / :source vocabulary leaks into the surfaced requirements"))))

(deftest cofx-requires-machine-scoped-across-parallel-regions
  (testing "a region guard/action resolves its requires
            against the MACHINE-LEVEL :guards/:actions (XState v5 scoping)"
    (let [m {:type    :parallel
             :guards  {:ready? {:rf.cofx/requires [:rf/time-ms]
                               :fn (fn [_] true)}}
             :regions {:a {:initial :one
                          :states {:one {:on {:go {:target :two :guard :ready?}}}
                                   :two {}}}
                       :b {:initial :x :states {:x {}}}}}
          {:keys [edges]} (layout/project-definition m)
          go-edge (edge-with-event edges :go)]
      (is (= ["rf/time-ms"] (:guard-requires go-edge))
          "a region guard resolves its requires against the machine registry"))))

(deftest raw-node-at-no-region-node-never-borrows-a-sibling-region
  (testing "a node with a region-relative `:path` but NO
            `:region` resolves against the TOP-LEVEL `:states` only, and a
            parallel definition has none — so the answer is nil, never the
            first region that happens to carry a state of that name. A
            fallback that scanned `(:regions definition)` in map order would
            return whichever region matched first — a wrong-region lifecycle
            attribution. Every node
            `project-parallel` mints carries `:region`, so nothing in the
            pipeline can reach this shape — a hand-built node that has lost
            its region identity gets nil rather than a plausible-looking
            neighbour."
    (let [m {:type    :parallel
             :regions {:audio {:initial :active
                               :states  {:active {:entry :enter-a} :off {}}}
                       :video {:initial :active
                               :states  {:active {:entry :enter-b} :off {}}}}}]
      (is (nil? (layout/raw-node-at m {:path [:active]}))
          "a no-:region region-relative node resolves to nil, not region
           :audio's raw node")
      (is (= {:entry :enter-a} (layout/raw-node-at m {:path [:active] :region :audio}))
          "the :audio node resolves strictly within its own region")
      (is (= {:entry :enter-b} (layout/raw-node-at m {:path [:active] :region :video}))
          "the :video node resolves strictly within its own region")))
  (testing "flat / compound (no-`:region`) lookup: a
            top-level `:states` node and a nested compound descendant both
            resolve, and an absent path is nil"
    (let [m {:initial :idle
             :states  {:idle {:entry :log}
                       :busy {:initial :step1
                              :states  {:step1 {:exit :cleanup}}}}}]
      (is (= {:entry :log} (layout/raw-node-at m {:path [:idle]}))
          "a flat top-level node resolves")
      (is (= {:exit :cleanup} (layout/raw-node-at m {:path [:busy :step1]}))
          "a nested compound descendant resolves")
      (is (nil? (layout/raw-node-at m {:path [:nope]}))
          "an absent path is nil")
      (is (nil? (layout/raw-node-at m {:path []}))
          "the synthetic empty-path node is nil"))))

;; ---- a REGION's OWN top-level :on-done ----------------------------------
;;
;; Spec 005 §Parallel `:on-done`: "A compound region reaching its own
;; :final? child raises a region-local done.state.<region-compound> that
;; the region's :on-done takes … exactly the compound case, scoped to one
;; region." Mermaid and SCXML project this, and so does the chart:
;; `project-parallel` reads a region's own top-level `:on-done` as well as a
;; NESTED compound's (via `collect-state-edges`), so a region `:on-done`
;; projects an `:on-done?` edge. Its target resolves within the region, as
;; the region's own `:on` does. Reading only the nested form would leave a G9
;; cross-emitter-parity gap (001-Topology-Parity.md).

(deftest project-definition-region-on-done-target-bearing-edge
  (testing "a region's own top-level :on-done with a KEYWORD
            target projects a ✓ done edge from the region's OWN container
            to the region's own state, matching SCXML's
            done.state.a -> a___a1 shape"
    (let [m {:type    :parallel
             :regions {:a {:initial :a1
                           :on-done :a1
                           :states  {:a1 {:on {:go :a2}}
                                     :a2 {:final? true}}}
                       :b {:initial :b1
                           :states  {:b1 {:on {:go :b2}}
                                     :b2 {:final? true}}}}}
          {:keys [nodes edges]} (layout/project-definition m)
          od (filter :on-done? edges)]
      (is (= 1 (count od)) "exactly one region on-done completion edge")
      (let [e (first od)
            a-rid (layout/region-node-id :a)
            a1-id (layout/region-scoped-id :a [:a1])]
        (is (= a-rid (:source e)) "sourced from region :a's OWN container")
        (is (= a1-id (:target e)) "lands on region :a's own :a1 state")
        (is (= :rf.machine/done (:event e)) "carries the reserved done event")
        (is (= [:a] (:done-path e)) "the done.state node is the region's own path")
        (is (= "✓ done" (:event-label e)))
        (is (not (:internal? e)) "a targeted region on-done is a real edge")
        (is (contains? (set (map :id nodes)) a-rid))
        (is (contains? (set (map :id nodes)) a1-id))))))

(deftest project-definition-region-on-done-action-only-self-anchors
  (testing "a region's own top-level :on-done that is
            ACTION-ONLY (no target) self-anchors on the region's OWN
            container as a terminal completion affordance — mirrors how
            a nested compound's action-only :on-done self-anchors, and
            how the parallel-root's action-only :on-done self-anchors on
            the parallel-root chip"
    (let [m {:type    :parallel
             :regions {:a {:initial :a1
                           :on-done {:action :log}
                           :states  {:a1 {:on {:go :a2}}
                                     :a2 {:final? true}}}
                       :b {:initial :b1 :states {:b1 {}}}}}
          {:keys [edges]} (layout/project-definition m)
          od (filter :on-done? edges)]
      (is (= 1 (count od)) "one region on-done completion affordance")
      (let [e (first od)
            a-rid (layout/region-node-id :a)]
        (is (true? (:internal? e)) "target-less region on-done self-anchors")
        (is (= a-rid (:source e)) "sourced from region :a's container")
        (is (= a-rid (:target e)) "self-anchored: no phantom sibling edge")
        (is (= :log (:action e)))))))

;; ---- :same-state at machine-root / region-root drops --------------------
;;
;; `collect-machine-edges`' own docstring declares a `:same-state`
;; machine-level fallback DROPPED ("there is no concrete root state to
;; self-transition against at the top level"). A `resolve-target-path` that
;; returned `(vec [])` = `[]` for this shape — TRUTHY in Clojure — would let
;; the candidate survive as a real (non-internal) edge and mint a phantom
;; edge to `(node-id [])` / `(region-scoped-id region-id [])`, both
;; degenerate empty-ish ids no real node carries.

(deftest project-definition-machine-root-same-state-drops-no-phantom-edge
  (testing "a machine-level :on candidate with :target
            :same-state is DROPPED (honouring collect-machine-edges'
            own docstring), not minted as a phantom edge to node-id \"\""
    (let [m {:initial :a
             :states  {:a {} :b {}}
             :on      {:noop {:target :same-state}}}
          {:keys [nodes edges]} (layout/project-definition m)]
      (is (empty? (filter #(= :noop (:event %)) edges))
          "the root :same-state fallback is dropped entirely")
      (is (empty? (filter :machine-root? nodes))
          "no synthetic MACHINE-ROOT chip is minted for a fully-dropped fallback"))))

(deftest project-definition-region-root-same-state-drops-no-phantom-edge
  (testing "a PARALLEL REGION's own top-level :on candidate
            with :target :same-state is likewise dropped, not region-
            scoped into a degenerate `region__<id>__` phantom edge"
    (let [m {:type    :parallel
             :regions {:a {:initial :one
                           :on      {:noop {:target :same-state}}
                           :states  {:one {} :two {}}}
                       :b {:initial :one :states {:one {}}}}}
          {:keys [edges]} (layout/project-definition m)
          degenerate-id (str (layout/region-node-id :a) "__")]
      (is (empty? (filter #(= :noop (:event %)) edges))
          "the region-root :same-state fallback is dropped entirely")
      (is (not-any? #(= degenerate-id (:target %)) edges)
          "no degenerate region-scoped empty-path target (region__a__) leaks into the graph"))))

;; ---- forbidden transitions: nil ≡ {} -------------------------------------
;;
;; Spec 005 §Forbidden transitions declares `{:on {:logout {}}}` and
;; `{:on {:logout nil}}` RUNTIME-EQUIVALENT — both block parent-fallthrough
;; for that event. `grammar/transition-candidates` gives `nil` its own
;; branch: falling through to `cond`'s `:else []` would drop a nil-spelled
;; forbidden transition to ZERO candidates while the `{}` spelling renders
;; as a blocking chip, and a reader would see `:logout` as inherited and
;; reachable at a state where the engine blocks it.

(deftest project-definition-nil-forbidden-transition-matches-empty-map
  (testing "a nil-spelled forbidden transition (`:on {:logout
            nil}}`) projects the SAME internal blocking chip as the
            empty-map spelling (`{:on {:logout {}}}`), not silently
            dropped"
    (let [nil-spelled {:initial :a :states {:a {:on {:logout nil}}}}
          map-spelled {:initial :a :states {:a {:on {:logout {}}}}}
          {edges-nil :edges} (layout/project-definition nil-spelled)
          {edges-map :edges} (layout/project-definition map-spelled)
          nil-e (first (filter #(= :logout (:event %)) edges-nil))
          map-e (first (filter #(= :logout (:event %)) edges-map))]
      (is (some? nil-e) "the nil-spelled forbidden transition is NOT dropped")
      (is (some? map-e) "the empty-map spelling projects too (baseline)")
      (is (true? (:internal? nil-e)) "nil spelling flags :internal? true, like {}")
      (is (= (dissoc nil-e :id) (dissoc map-e :id))
          "nil and {} project structurally-identical edges (bar the edge id)"))))
