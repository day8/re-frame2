(ns day8.re-frame2-machines-viz.chart.layout-cljs-test
  "Pure-data tests for the chart-layout graph projector.

  Post rf2-gpzb4 (2026-05-21 xyflow migration) — the SVG-side
  positioning primitives (`layout`, `layered-fallback`, `:x`/`:y`/
  `:rank` on nodes, `:points` on edges) are gone; xyflow + elkjs own
  positioning. This suite pins the substrate-agnostic graph parse
  surface that survived the migration."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
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

;; ---- parse-definition ---------------------------------------------------

(deftest parse-definition-empty-for-nil
  (let [g (layout/parse-definition nil)]
    (is (= [] (:nodes g)))
    (is (= [] (:edges g)))))

(deftest parse-definition-extracts-flat-machine-nodes
  (let [{:keys [nodes initial-path]} (layout/parse-definition idle-loading-success)
        paths (set (map :path nodes))]
    (is (= [:idle] initial-path))
    (is (= #{[:idle] [:loading] [:success] [:failed]} paths))
    (is (some :initial? nodes) "the initial state is flagged")
    (is (= 2 (count (filter :final? nodes)))
        "final states are flagged")))

(deftest parse-definition-flags-compound-initial
  (testing "rf2-54s5a — a compound parent's :initial child is flagged
            :initial? (xstate per-level initial semantics)"
    (let [{:keys [nodes]} (layout/parse-definition compound-machine)
          browsing (first (filter #(= [:authenticated :browsing] (:path %)) nodes))]
      (is (true? (:initial? browsing))))))

(deftest parse-definition-wires-compound-parent-id
  (testing "rf2-54s5a — compound substates carry :parent-id (the
            parent's node-id) for xyflow parentNode nesting; top-level
            states carry none"
    (let [{:keys [nodes]} (layout/parse-definition compound-machine)
          browsing (first (filter #(= [:authenticated :browsing] (:path %)) nodes))
          unauth   (first (filter #(= [:unauth] (:path %)) nodes))]
      (is (= (layout/node-id [:authenticated]) (:parent-id browsing)))
      (is (nil? (:parent-id unauth))))))

(deftest parse-definition-extracts-edges
  (let [{:keys [edges]} (layout/parse-definition idle-loading-success)
        edge-pairs (set (map (juxt :from :to :event) edges))]
    (is (contains? edge-pairs [[:idle] [:loading] :start]))
    (is (contains? edge-pairs [[:loading] [:success] :ok]))
    (is (contains? edge-pairs [[:loading] [:failed] :err]))))

(deftest parse-definition-emits-xyflow-shaped-edges
  (testing "rf2-gpzb4 xyflow migration — every edge has :id, :source,
            :target string ids (xyflow contract) AND :from-path,
            :to-path vectors (substrate-side contract)"
    (let [{:keys [edges]} (layout/parse-definition idle-loading-success)]
      (is (every? :id edges)         "every edge has a stable string id")
      (is (every? :source edges)     "every edge has :source (string)")
      (is (every? :target edges)     "every edge has :target (string)")
      (is (every? :from-path edges)  "every edge has :from-path (vector)")
      (is (every? :to-path edges)    "every edge has :to-path (vector)")
      (is (every? :event-label edges) "every edge has the xstate label"))))

(deftest parse-definition-emits-xyflow-shaped-nodes
  (testing "rf2-gpzb4 xyflow migration — every node has :id string
            (xyflow contract) alongside :path (vector)"
    (let [{:keys [nodes]} (layout/parse-definition idle-loading-success)]
      (is (every? :id nodes))
      (is (every? string? (map :id nodes)))
      (is (every? :path nodes)))))

(deftest parse-definition-extracts-compound-nodes
  (let [{:keys [nodes]} (layout/parse-definition compound-machine)
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

(deftest parse-definition-projects-every-parallel-region
  (testing "rf2-lkwev xyflow Phase 2 — full parallel-region rendering:
            EVERY region projects (Phase 1 deferred all but the first)"
    (let [parallel {:type :parallel
                    :regions {:r1 {:initial :a :states {:a {:on {:go :b}}
                                                        :b {}}}
                              :r2 {:initial :x :states {:x {:on {:go :y}}
                                                        :y {}}}}}
          {:keys [nodes edges parallel?]} (layout/parse-definition parallel)
          paths (set (map :path (remove :region? nodes)))]
      (is parallel? "the projection flags itself as parallel")
      (is (contains? paths [:a]))
      (is (contains? paths [:b]))
      (is (contains? paths [:x])
          "rf2-lkwev — :r2's nodes NOW surface (full parallel layout)")
      (is (contains? paths [:y]))
      ;; Both regions' edges surface.
      (is (= #{:go} (set (map :event edges)))
          "edges from both regions project")
      (is (= 2 (count edges)) "one :go edge per region"))))

(deftest parse-definition-emits-region-container-nodes
  (testing "rf2-lkwev — each parallel region surfaces a synthetic
            :region? compound container node with a region-prefixed id"
    (let [parallel {:type :parallel
                    :regions {:audio   {:initial :playing
                                        :states {:playing {:on {:pause :paused}}
                                                 :paused  {:on {:play :playing}}}}
                              :display {:initial :on
                                        :states {:on  {:on {:dim :off}}
                                                 :off {:on {:lit :on}}}}}}
          {:keys [nodes]} (layout/parse-definition parallel)
          regions (filter :region? nodes)]
      (is (= 2 (count regions)) "one container node per region")
      (is (every? :compound? regions) "region containers are compound")
      (is (= #{(layout/region-node-id :audio)
               (layout/region-node-id :display)}
             (set (map :id regions)))
          "region node-ids are region-prefixed")
      (is (= #{0 1} (set (map :region-index regions)))
          "region containers carry their ordinal index for boundary colour"))))

(deftest parse-definition-tags-region-states-with-parent
  (testing "rf2-lkwev — every state inside a region carries :region +
            :parent-id so the chart projector emits xyflow parentNode
            sub-flow grouping"
    (let [parallel {:type :parallel
                    :regions {:r1 {:initial :a :states {:a {} :b {}}}
                              :r2 {:initial :x :states {:x {} :y {}}}}}
          {:keys [nodes]} (layout/parse-definition parallel)
          states (remove :region? nodes)]
      (is (every? :parent-id states) "every state has a parent region id")
      (is (every? :region states)    "every state knows its region")
      (let [r1-states (filter #(= :r1 (:region %)) states)]
        (is (every? #(= (layout/region-node-id :r1) (:parent-id %)) r1-states)
            ":r1 states point at the :r1 container")))))

(deftest parse-definition-region-edges-stay-region-local
  (testing "rf2-lkwev — orthogonality: a region's edges never reference
            a sibling region's node (regions are independent zones)"
    (let [parallel {:type :parallel
                    :regions {:r1 {:initial :a :states {:a {:on {:go :b}} :b {}}}
                              :r2 {:initial :x :states {:x {:on {:go :y}} :y {}}}}}
          {:keys [nodes edges]} (layout/parse-definition parallel)
          r1-ids (set (map :id (filter #(= :r1 (:region %)) (remove :region? nodes))))
          r2-ids (set (map :id (filter #(= :r2 (:region %)) (remove :region? nodes))))]
      (doseq [e edges]
        (is (or (and (contains? r1-ids (:source e)) (contains? r1-ids (:target e)))
                (and (contains? r2-ids (:source e)) (contains? r2-ids (:target e))))
            "each edge stays within one region")))))

(deftest region-node-id-is-prefixed-and-distinct
  (testing "rf2-lkwev — region node-ids are region__-prefixed so they
            never collide with a state node-id. rf2-ee38b.21 — segments
            are injectively escaped (the `/` ns separator → `_2f`)."
    (is (= "region__r1" (layout/region-node-id :r1)))
    (is (= "region__auth_2fmain" (layout/region-node-id :auth/main)))
    (is (not= (layout/region-node-id :r1) (layout/node-id [:r1]))
        "a region container id differs from the same-named state id")))

;; ---- highlight-id ------------------------------------------------------

(deftest highlight-id-handles-flat-state
  (is (some? (layout/highlight-id :authing)))
  (is (= (layout/highlight-id :authing)
         (layout/highlight-id [:authing]))
      "flat keyword and 1-element vector resolve to the same id"))

(deftest highlight-id-handles-hierarchical-path
  (let [id (layout/highlight-id [:authenticated :browsing])]
    (is (string? id))
    (is (not= id (layout/highlight-id [:authenticated])))))

(deftest highlight-id-nil-for-nil-state
  (is (nil? (layout/highlight-id nil))))

(deftest highlight-id-nil-for-region-map
  (testing "rf2-g2svr — the single-active resolver returns nil for a
            region-map (a map is not a single-active state); the
            multi-active `highlight-ids` is the resolver for that arm"
    (is (nil? (layout/highlight-id {:data :loading :form :neutral})))))

;; ---- highlight-ids — multi-active (rf2-yoe6e / rf2-g2svr, G1) -----------
;;
;; Spec 005 §Snapshot shape: `:state` has three arms — flat keyword,
;; hierarchical path, OR a region-map (PARALLEL — N simultaneously-active
;; leaves). `highlight-ids` resolves ALL THREE to a SET of active-leaf
;; node-ids so the chart lights up EVERY active region at once (the §1.2
;; parity bar in 001-Topology-Parity.md). These pins are the new
;; capability: flat→1, compound→1 leaf, region-map→N, nested→deepest leaf.

(deftest highlight-ids-flat-keyword-is-singleton
  (testing "rf2-g2svr — a flat-keyword `:state` resolves to a one-element
            set (the single-active case, set-wrapped)"
    (is (= #{(layout/node-id [:authing])}
           (layout/highlight-ids :authing)))))

(deftest highlight-ids-compound-path-is-singleton-leaf
  (testing "rf2-g2svr — a hierarchical path resolves to a one-element set
            holding the DEEPEST leaf's id (node-id of the full path)"
    (is (= #{(layout/node-id [:authenticated :browsing])}
           (layout/highlight-ids [:authenticated :browsing])))))

(deftest highlight-ids-region-map-is-the-set-of-active-leaves
  (testing "rf2-g2svr (THE NEW CAPABILITY) — a PARALLEL snapshot's
            region-map resolves to the SET of N active leaves, one per
            region. Each region value resolves the same way the parse
            mints the region's state ids — via node-id of the in-region
            path (parse-parallel keeps the in-region node-id; it does NOT
            region-prefix it)."
    (let [state {:data :loading :form :neutral :mode :active}
          ids   (layout/highlight-ids state)]
      (is (= 3 (count ids)) "three regions → three active leaf ids")
      (is (= #{(layout/node-id [:loading])
               (layout/node-id [:neutral])
               (layout/node-id [:active])}
             ids)))))

(deftest highlight-ids-region-map-matches-parsed-region-state-ids
  (testing "rf2-g2svr — the resolved set is exactly the set of node-ids
            the parse minted for the active region states, so the
            projection will mark those real nodes :active (no phantom
            ids). Pins the resolver against the parser's actual output."
    (let [parallel {:type :parallel
                    :regions {:audio {:initial :muted
                                      :states {:muted   {:on {:unmute :playing}}
                                               :playing {:on {:mute :muted}}}}
                              :video {:initial :hidden
                                      :states {:hidden {:on {:show :shown}}
                                               :shown  {:on {:hide :hidden}}}}}}
          {:keys [nodes]} (layout/parse-definition parallel)
          node-ids (set (map :id (remove :region? nodes)))
          ;; both regions advanced past their initial states
          state    {:audio :playing :video :shown}
          ids      (layout/highlight-ids state)]
      (is (= 2 (count ids)))
      (is (every? #(contains? node-ids %) ids)
          "every active id is a REAL parsed region-state node")
      (is (= #{(layout/node-id [:playing]) (layout/node-id [:shown])} ids)))))

(deftest highlight-ids-nested-region-value-resolves-to-deepest-leaf
  (testing "rf2-g2svr — a region whose value is itself a vector path (a
            compound region) resolves to the DEEPEST leaf, exactly as the
            single-compound case does. Spec 005: a compound region's
            value is a vector path INSIDE that region."
    (let [state {:auth [:authenticated :dashboard] :lifecycle :idle}
          ids   (layout/highlight-ids state)]
      (is (= #{(layout/node-id [:authenticated :dashboard])
               (layout/node-id [:idle])}
             ids))
      (is (= 2 (count ids))
          "the compound region contributes ONE id (its deepest leaf), not
           one-per-path-segment"))))

(deftest highlight-ids-empty-for-nil
  (testing "rf2-g2svr — nil `:state` (no highlight) → the empty set (a
            SET, never nil — callers can always `contains?` it)"
    (is (= #{} (layout/highlight-ids nil)))))

(deftest highlight-ids-empty-for-empty-region-map
  (testing "rf2-g2svr — an empty region-map resolves to the empty set"
    (is (= #{} (layout/highlight-ids {})))))

(deftest highlight-ids-subsumes-highlight-id-for-single-active
  (testing "rf2-g2svr — for a single-active state, `highlight-ids` is
            exactly `#{(highlight-id state)}` — the multi-active resolver
            is a strict superset of the single-active one"
    (doseq [state [:authing [:authenticated :browsing] [:a]]]
      (is (= #{(layout/highlight-id state)}
             (layout/highlight-ids state))
          (str "single-active " state " agrees with the set resolver")))))

;; ---- node-id ----------------------------------------------------------

(deftest node-id-is-public-fn
  (testing "node-id is exported so xyflow + SCXML + Mermaid emitters
            address nodes the same way"
    (is (string? (layout/node-id [:idle])))
    (is (= (layout/node-id [:idle])
           (layout/node-id [:idle]))
        "deterministic")))

(deftest node-id-distinct-for-distinct-paths
  (is (not= (layout/node-id [:authenticated])
            (layout/node-id [:authenticated :browsing]))))

;; ---- edge-label xstate-stately convention -----------------------------

;; Shape: `event [guard] / action`. Brackets + slash appear ONLY when
;; their segment is present, per xstate-stately.

(deftest edge-label-event-only
  (is (= "submit"
         (layout/edge-label {:event :submit}))))

(deftest edge-label-event-with-guard
  (is (= "submit [authed?]"
         (layout/edge-label {:event :submit :guard :authed?}))))

(deftest edge-label-event-with-action
  (is (= "submit / log-it"
         (layout/edge-label {:event :submit :action :log-it}))))

(deftest edge-label-event-with-guard-and-action
  (is (= "submit [authed?] / log-it"
         (layout/edge-label {:event :submit
                             :guard :authed?
                             :action :log-it}))))

(deftest edge-label-after-with-guard-and-action
  (is (= "after(1500) [timeout?] / cleanup"
         (layout/edge-label {:event  :after-1500
                             :after  1500
                             :guard  :timeout?
                             :action :cleanup}))))

(deftest edge-label-always-with-guard
  (is (= "always [ready?]"
         (layout/edge-label {:event   :always
                             :always? true
                             :guard   :ready?}))))

(deftest edge-label-namespaced-event-with-guard
  (is (= "auth/submit [authed?] / log-it"
         (layout/edge-label {:event  :auth/submit
                             :guard  :authed?
                             :action :log-it}))))

(deftest edge-label-namespaced-guard-renders-ns
  (testing "guards may be namespaced; the label preserves the namespace"
    (is (= "submit [auth/authed?]"
           (layout/edge-label {:event :submit
                               :guard :auth/authed?})))))

(deftest parse-definition-emits-event-label-with-guard-and-action
  (testing "parse-definition emits the full xstate label on every edge"
    (let [m {:initial :idle
             :states  {:idle {:on {:submit [{:target :loading
                                             :guard  :authed?
                                             :action :log-it}
                                            {:target :failed
                                             :guard  :anon?}]}}
                       :loading {}
                       :failed  {}}}
          {:keys [edges]} (layout/parse-definition m)
          labels (set (map :event-label edges))]
      (is (contains? labels "submit [authed?] / log-it"))
      (is (contains? labels "submit [anon?]")))))

;; ---- self-transitions (rf2-ee38b.21) -----------------------------------
;;
;; Spec 005 §Self-transitions: `:target :same-state` is the EXTERNAL
;; self-transition (exit+entry fire); omitting `:target` is the INTERNAL
;; one (only :action runs). Both must chart as a self-loop (source ==
;; target). Pre-fix, `:same-state` resolved to a phantom node-id and
;; the internal form emitted nothing.

(deftest parse-definition-external-self-transition-same-state
  (testing "rf2-ee38b.21 — `:target :same-state` resolves to the source
            path itself (a true self-loop), NOT a phantom :same-state
            node"
    (let [m {:initial :a :states {:a {:on {:ping {:target :same-state}}} }}
          {:keys [nodes edges]} (layout/parse-definition m)
          node-ids (set (map :id nodes))
          self     (first (filter #(= :ping (:event %)) edges))]
      (is (some? self) "the :ping self-transition is charted")
      (is (= [:a] (:from self)))
      (is (= [:a] (:to self)) "target resolves to the source path")
      (is (= (:source self) (:target self)) "source == target → self-loop")
      (is (contains? node-ids (:target self))
          "the self-loop target is a REAL node (no phantom :same-state)")
      (is (not (contains? node-ids "same_state"))
          "no dangling :same-state node is minted"))))

(deftest parse-definition-internal-self-transition-omit-target
  (testing "rf2-ee38b.21 — a transition that omits :target (internal —
            runs only :action) charts as a self-anchored edge flagged
            :internal? rather than silently dropping"
    (let [m {:initial :a :states {:a {:on {:tick {:action :inc}}}}}
          {:keys [edges]} (layout/parse-definition m)
          tick (first (filter #(= :tick (:event %)) edges))]
      (is (some? tick) "the internal :tick transition is charted")
      (is (= [:a] (:from tick)))
      (is (= [:a] (:to tick)) "internal transition self-anchors")
      (is (true? (:internal? tick)) "flagged internal")
      (is (= :inc (:action tick))))))

;; ---- wildcard `:*` (rf2-ee38b.21) --------------------------------------

(deftest parse-definition-wildcard-event-label
  (testing "rf2-ee38b.21 — the `:*` wildcard `:on` arm (Spec 005
            §Wildcard) renders as `* (any)`, not a bare `*` that reads
            like a real event"
    (let [m {:initial :a :states {:a {:on {:* :b}} :b {}}}
          {:keys [edges]} (layout/parse-definition m)
          wild (first (filter #(= :* (:event %)) edges))]
      (is (some? wild) "the wildcard transition is charted")
      (is (= "* (any)" (:event-label wild))))))

;; ---- machine-level (top-level) :on fallback (rf2-ee38b.21) -------------

(deftest parse-definition-machine-level-on-fallback
  (testing "rf2-ee38b.21 — a top-level (machine-level) :on fallback
            (Spec 005 — `:on` valid per-state AND top-level) charts one
            edge from every leaf state, flagged :machine-level?, rather
            than being dropped entirely"
    (let [m {:initial :a :on {:logout :a} :states {:a {} :b {}}}
          {:keys [edges]} (layout/parse-definition m)
          logout (filter #(= :logout (:event %)) edges)]
      (is (= 2 (count logout)) "one inherited edge per leaf state")
      (is (every? :machine-level? logout) "flagged machine-level")
      (is (= #{[:a] [:b]} (set (map :from logout))) "from every leaf")
      (is (every? #(= [:a] (:to %)) logout) "all land on the target"))))

(deftest parse-definition-machine-level-on-targets-top-level-state
  (testing "rf2-ee38b.21 — a machine-level :on target is a TOP-LEVEL
            state, resolved at the root — NOT relative to the inheriting
            leaf's parent (a compound leaf must still reach :unauth)"
    (let [m {:initial :authenticated
             :on      {:logout :unauth}
             :states  {:unauth        {}
                       :authenticated {:initial :browsing
                                       :states  {:browsing {}
                                                 :paying   {}}}}}
          {:keys [edges]} (layout/parse-definition m)
          logout (filter #(= :logout (:event %)) edges)]
      (is (every? #(= [:unauth] (:to %)) logout)
          "every inherited :logout lands on the top-level :unauth")
      (is (contains? (set (map :from logout)) [:authenticated :browsing])
          "compound leaves inherit it too"))))

;; ---- node-id injectivity (rf2-ee38b.21) --------------------------------

(deftest node-id-is-injective-over-hyphen-ns-underscore
  (testing "rf2-ee38b.21 — distinct paths mint DISTINCT ids. The old
            `[^a-zA-Z0-9_]`-collapse merged `:a/b`, `:a-b`, `:a_b` all
            to `\"a_b\"` (React key collision dropped a node)"
    (let [ids (map (comp layout/node-id vector) [:a/b :a-b :a_b :logged-in :logged_in])]
      (is (= (count ids) (count (set ids)))
          "all five distinct keywords yield distinct ids")
      (is (not= (layout/node-id [:a/b]) (layout/node-id [:a-b])))
      (is (not= (layout/node-id [:a-b]) (layout/node-id [:a_b])))
      (is (not= (layout/node-id [:logged-in]) (layout/node-id [:logged_in]))))))

(deftest node-id-distinct-from-region-container-id
  (testing "rf2-ee38b.21 — a state literally named :region__foo cannot
            collide with a region container's id"
    (is (not= (layout/node-id [:region__foo])
              (layout/region-node-id :foo)))))

;; ---- edge-id collision (rf2-ee38b.21) ----------------------------------

(deftest parse-definition-edge-ids-distinct-for-guarded-fork
  (testing "rf2-ee38b.21 — a same-event/same-target fork that differs
            only by guard mints DISTINCT edge ids so xyflow keeps both
            branches (pre-fix the ids collided → one branch dropped)"
    (let [m {:initial :a
             :states  {:a {:on {:go [{:target :b :guard :g1}
                                     {:target :b :guard :g2}]}}
                       :b {}}}
          {:keys [edges]} (layout/parse-definition m)
          go-edges (filter #(= :go (:event %)) edges)
          ids      (map :id go-edges)]
      (is (= 2 (count go-edges)) "both candidate edges survive")
      (is (= 2 (count (set ids))) "their xyflow ids are distinct"))))

(deftest parse-definition-edge-ids-distinct-for-identical-candidates
  (testing "rf2-ee38b.21 — even byte-identical candidates (same target,
            no guard/action) get distinct ids via the per-key ordinal"
    (let [m {:initial :a
             :states  {:a {:on {:go [{:target :b} {:target :b}]}}
                       :b {}}}
          {:keys [edges]} (layout/parse-definition m)
          ids (map :id (filter #(= :go (:event %)) edges))]
      (is (= 2 (count ids)))
      (is (= 2 (count (set ids))) "ordinal disambiguates identical candidates"))))

;; ---- entry / exit state actions (rf2-ee38b.21) -------------------------

(deftest parse-definition-threads-entry-exit-onto-nodes
  (testing "rf2-ee38b.21 — :entry / :exit state actions (Spec 005
            §State nodes) surface as name strings on the parsed node"
    (let [m {:initial :a
             :states  {:a {:entry :on-enter :exit :on-leave}
                       :b {:entry (with-meta (fn [_]) {:name 'do-thing})}}}
          {:keys [nodes]} (layout/parse-definition m)
          a (first (filter #(= [:a] (:path %)) nodes))
          b (first (filter #(= [:b] (:path %)) nodes))]
      (is (= "on-enter" (:entry a)))
      (is (= "on-leave" (:exit a)))
      (is (= "do-thing" (:entry b)) "fn entry surfaces its :name meta")
      (is (not (contains? b :exit)) "absent exit is omitted"))))

(deftest parse-definition-no-entry-exit-when-absent
  (testing "rf2-ee38b.21 — a state with no :entry / :exit carries
            neither key (cond-> skips the assoc)"
    (let [{:keys [nodes]} (layout/parse-definition idle-loading-success)
          idle (first (filter #(= [:idle] (:path %)) nodes))]
      (is (not (contains? idle :entry)))
      (is (not (contains? idle :exit))))))

;; ---- :final? is always boolean (rf2-ee38b.21) --------------------------

(deftest parse-definition-final-flag-is-boolean
  (testing "rf2-ee38b.21 — :final? is boolean-wrapped (false, not nil)
            for non-final states, matching its sibling flags"
    (let [{:keys [nodes]} (layout/parse-definition idle-loading-success)
          idle    (first (filter #(= [:idle] (:path %)) nodes))
          success (first (filter #(= [:success] (:path %)) nodes))]
      (is (false? (:final? idle)) ":final? is false, not nil")
      (is (true?  (:final? success))))))
