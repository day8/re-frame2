(ns day8.re-frame2-machines-viz.chart.projection-cljs-test
  "Pure-data tests for the MachineChart projection layer (rf2-0gmwp).

  `chart.projection` is the central parsed-graph → xyflow nodes/edges
  projector plus the elk.js `children` shape + the edge-type chooser.
  It was extracted from `chart.cljs` (which `:require`s xyflow/elkjs
  and so is JVM-unloadable) precisely so this corpus can pin it at the
  cheap JVM layer instead of the slow browser-DOM layer.

  Fixtures lean on `chart.layout/parse-definition` (itself pure +
  JVM-runnable) so the projection is exercised against the SAME parsed
  shape the live chart feeds it — no hand-mocked node maps drifting
  from the parser's contract.

  Dual-target via the `_cljs_test.cljc` extension — same pattern every
  machines-viz helper test uses."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- fixtures ----------------------------------------------------------

(def idle-loading
  "Flat machine with a plain `:on` transition + an `:after` timer + an
  `:always` eventless transition, so every `choose-edge-type` arm is
  represented in one parse."
  {:initial :idle
   :states  {:idle    {:on    {:start :loading}}
             :loading {:after {1000 {:target :timeout}}
                       :always {:target :ready :guard :loaded?}}
             :ready   {:final? true}
             :timeout {:final? true}}})

(def compound-machine
  "One compound parent so the `\"compound\"` node-type arm fires."
  {:initial :unauth
   :states  {:unauth        {:on {:login :authenticated}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {:on {:done :browsing}}}
                             :on      {:logout :unauth}}}})

(def parallel-machine
  "Two-region parallel machine — exercises the region container
  node-type, the parentNode/extent sub-flow wiring, and the
  parent-before-child sort."
  {:type    :parallel
   :regions {:audio {:initial :muted
                     :states  {:muted   {:on {:unmute :playing}}
                               :playing {:on {:mute :muted}}}}
             :video {:initial :hidden
                     :states  {:hidden  {:on {:show :shown}}
                               :shown   {:on {:hide :hidden}}}}}})

(defn- edge-by-id
  "Pluck an edge from a projected graph by xyflow id."
  [graph id]
  (first (filter #(= id (:id %)) (:edges graph))))

(defn- node-by-id
  [graph id]
  (first (filter #(= id (:id %)) (:nodes graph))))

;; ---- choose-edge-type (G2) ---------------------------------------------

(deftest choose-edge-type-plain-transition
  (testing "a plain `:on` edge → the canonical `transition` type"
    (is (= "transition"
           (projection/choose-edge-type {:event :start})))))

(deftest choose-edge-type-after-timer
  (testing "an `:after`-timer edge → the dedicated `after` type"
    (is (= "after"
           (projection/choose-edge-type {:after 1000 :event :after-1000})))))

(deftest choose-edge-type-always-falls-to-transition
  (testing "an `:always` eventless edge has no distinct edge type — it
            renders via `transition` (its `always` label segment is
            composed upstream in chart.layout/edge-label, not here)"
    (is (= "transition"
           (projection/choose-edge-type {:always? true :event :always})))))

(deftest choose-edge-type-after-wins-over-always
  (testing "an edge carrying BOTH `:after` and `:always?` is an
            `:after`-timer first — the `after` arm precedes the
            transition fall-through"
    (is (= "after"
           (projection/choose-edge-type {:after 500 :always? true})))))

(deftest choose-edge-type-has-no-spawn-arm
  (testing "rf2-0gmwp — `choose-edge-type` NEVER returns `spawn`.
            Per Spec 005 `:spawn` / `:spawn-all` are state-entry
            actions (they spawn child actor machines), not same-machine
            transitions, so the parser emits no spawn edge and there is
            no spawn arm to classify into. The dead `spawn-edge`
            registration was removed. Even an edge map with a stray
            `:spawn` key falls through to `transition`."
    (is (not= "spawn" (projection/choose-edge-type {:event :foo})))
    (is (= "transition" (projection/choose-edge-type {:spawn true :event :foo})))))

(deftest choose-edge-type-matches-live-parsed-edges
  (testing "every edge a real parse emits classifies to a type that is
            actually registered in chart.edges/edge-types (transition |
            after) — pins choose-edge-type against the parser's output"
    (let [{:keys [edges]} (layout/parse-definition idle-loading)]
      (is (seq edges))
      (is (every? #{"transition" "after"}
                  (map projection/choose-edge-type edges))))))

;; ---- xyflow-graph node :type dispatch (G1) -----------------------------

(deftest xyflow-graph-state-node-type
  (testing "a leaf state projects as a `state`-type node"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          idle   (node-by-id graph (layout/node-id [:idle]))]
      (is (= "state" (:type idle))))))

(deftest xyflow-graph-compound-node-type
  (testing "a compound parent projects as a `compound`-type node; its
            leaf children stay `state`"
    (let [parsed (layout/parse-definition compound-machine)
          graph  (projection/xyflow-graph parsed {} {})
          parent (node-by-id graph (layout/node-id [:authenticated]))
          child  (node-by-id graph (layout/node-id [:authenticated :browsing]))]
      (is (= "compound" (:type parent)))
      (is (= "state" (:type child))))))

(deftest xyflow-graph-region-node-type
  (testing "a parallel-region container projects as a
            `parallel-region`-type node"
    (let [parsed (layout/parse-definition parallel-machine)
          graph  (projection/xyflow-graph parsed {} {})
          region (node-by-id graph (layout/region-node-id :audio))]
      (is (= "parallel-region" (:type region))))))

;; ---- xyflow-graph parentNode / extent sub-flow wiring (G1) --------------

(deftest xyflow-graph-region-children-wire-parent-node
  (testing "rf2-lkwev — every state inside a region carries
            `:parentNode` (the region container id) + `:extent
            \"parent\"` so xyflow's sub-flow nests + clamps it; the
            region container itself carries NEITHER"
    (let [parsed (layout/parse-definition parallel-machine)
          graph  (projection/xyflow-graph parsed {} {})
          region (node-by-id graph (layout/region-node-id :audio))
          muted  (node-by-id graph (layout/node-id [:muted]))]
      (is (= (layout/region-node-id :audio) (:parentNode muted)))
      (is (= "parent" (:extent muted)))
      (is (nil? (:parentNode region)) "region container is not nested")
      (is (nil? (:extent region))))))

(deftest xyflow-graph-flat-state-has-no-parent-node
  (testing "a state in a non-parallel machine carries no parentNode /
            extent — those wire ONLY for region children"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          idle   (node-by-id graph (layout/node-id [:idle]))]
      (is (nil? (:parentNode idle)))
      (is (nil? (:extent idle))))))

;; ---- xyflow-graph parent-before-child sort (G1) ------------------------

(deftest xyflow-graph-sorts-regions-before-children
  (testing "rf2-lkwev — xyflow requires a parentNode to appear in the
            nodes array BEFORE any node that references it. Every
            region container must precede its first child in the
            projected order."
    (let [parsed   (layout/parse-definition parallel-machine)
          graph    (projection/xyflow-graph parsed {} {})
          ids      (mapv :id (:nodes graph))
          index-of (fn [id] (.indexOf ids id))]
      (doseq [n (:nodes graph)
              :let [parent (:parentNode n)]
              :when parent]
        (is (< (index-of parent) (index-of (:id n)))
            (str "parent " parent " must precede child " (:id n)))))))

(deftest xyflow-graph-region-sort-is-stable-against-shuffle
  (testing "the sort is defensive — even if upstream emits a child
            before its region, the projector re-orders regions first"
    (let [parsed   (layout/parse-definition parallel-machine)
          ;; Reverse the node order to simulate hostile upstream output.
          shuffled (update parsed :nodes (comp vec reverse))
          graph    (projection/xyflow-graph shuffled {} {})
          ids      (mapv :id (:nodes graph))
          index-of (fn [id] (.indexOf ids id))]
      (doseq [n (:nodes graph)
              :let [parent (:parentNode n)]
              :when parent]
        (is (< (index-of parent) (index-of (:id n))))))))

;; ---- xyflow-graph :data flag derivation (G1) ---------------------------

(deftest xyflow-graph-active-flag
  (testing "the node whose id == highlight-id gets `:active true`; all
            others `:active false`"
    (let [parsed   (layout/parse-definition idle-loading)
          hi       (layout/node-id [:loading])
          graph    (projection/xyflow-graph parsed {} {:highlight-id hi})
          loading  (node-by-id graph hi)
          idle     (node-by-id graph (layout/node-id [:idle]))]
      (is (true?  (:active (:data loading))))
      (is (false? (:active (:data idle)))))))

(deftest xyflow-graph-from-and-to-highlight-flags
  (testing "from-highlight-id / to-highlight-id flip the matching
            node's `:fromHighlight` / `:toHighlight` flags"
    (let [parsed (layout/parse-definition idle-loading)
          from   (layout/node-id [:idle])
          to     (layout/node-id [:loading])
          graph  (projection/xyflow-graph parsed {}
                                          {:from-highlight-id from
                                           :to-highlight-id   to})]
      (is (true? (:fromHighlight (:data (node-by-id graph from)))))
      (is (true? (:toHighlight   (:data (node-by-id graph to)))))
      (is (false? (:toHighlight  (:data (node-by-id graph from))))))))

(deftest xyflow-graph-sim-flag-is-active-and-sim
  (testing ":sim is the conjunction of active? AND the sim? option —
            an active node with sim? true gets `:sim true`; the same
            node with sim? false gets `:sim false`; an inactive node
            never gets `:sim` regardless of sim?"
    (let [parsed   (layout/parse-definition idle-loading)
          hi       (layout/node-id [:loading])
          sim      (projection/xyflow-graph parsed {} {:highlight-id hi :sim? true})
          no-sim   (projection/xyflow-graph parsed {} {:highlight-id hi :sim? false})
          inactive (projection/xyflow-graph parsed {} {:highlight-id hi :sim? true})]
      (is (true?  (:sim (:data (node-by-id sim hi)))))
      (is (false? (:sim (:data (node-by-id no-sim hi)))))
      (is (false? (:sim (:data (node-by-id inactive (layout/node-id [:idle])))))))))

(deftest xyflow-graph-edge-active-when-endpoint-highlighted
  (testing "an edge is `:active` when EITHER endpoint is the
            highlighted node"
    (let [parsed (layout/parse-definition idle-loading)
          hi     (layout/node-id [:loading])
          graph  (projection/xyflow-graph parsed {} {:highlight-id hi})
          ;; idle --start--> loading : target is highlighted
          e      (first (filter #(= (:source %) (layout/node-id [:idle]))
                                (:edges graph)))]
      (is (true? (:active (:data e)))))))

(deftest xyflow-graph-edge-focused-when-source-and-target-match-lens
  (testing "an edge is `:focused` ONLY when source==from-highlight AND
            target==to-highlight (the focused-event lens). A partial
            match is not focused."
    (let [parsed (layout/parse-definition idle-loading)
          from   (layout/node-id [:idle])
          to     (layout/node-id [:loading])
          graph  (projection/xyflow-graph parsed {}
                                          {:from-highlight-id from
                                           :to-highlight-id   to})
          focused-edge (first (filter #(and (= (:source %) from)
                                            (= (:target %) to))
                                      (:edges graph)))
          other-edges  (remove #(and (= (:source %) from)
                                      (= (:target %) to))
                               (:edges graph))]
      (is (true? (:focused (:data focused-edge))))
      (is (every? false? (map (comp :focused :data) other-edges))))))

(deftest xyflow-graph-edge-not-focused-without-both-lens-ends
  (testing "with only ONE lens end set, no edge is focused (the
            some?/some? guard requires both)"
    (let [parsed (layout/parse-definition idle-loading)
          from   (layout/node-id [:idle])
          graph  (projection/xyflow-graph parsed {} {:from-highlight-id from})]
      (is (every? false? (map (comp :focused :data) (:edges graph)))))))

;; ---- xyflow-graph misc payload + style ---------------------------------

(deftest xyflow-graph-region-style-from-measured-position
  (testing "a region container's `:style {:width :height}` comes from
            its measured position entry"
    (let [parsed    (layout/parse-definition parallel-machine)
          rid       (layout/region-node-id :audio)
          positions {rid {:x 0 :y 0 :width 320 :height 180}}
          graph     (projection/xyflow-graph parsed positions {})
          region    (node-by-id graph rid)]
      (is (= {:width 320 :height 180} (:style region))))))

(deftest xyflow-graph-position-defaults-to-origin
  (testing "a node with no entry in the positions map defaults to
            {:x 0 :y 0} (the pre-layout placeholder)"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          idle   (node-by-id graph (layout/node-id [:idle]))]
      (is (= {:x 0 :y 0} (:position idle))))))

(deftest xyflow-graph-edge-carries-after-ms-and-event-label
  (testing "edge `:data` surfaces the after-ms duration + the composed
            event label from the parsed edge"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          after  (first (filter #(:afterMs (:data %)) (:edges graph)))]
      (is (some? after) "the :after edge surfaces an :afterMs")
      (is (= 1000 (:afterMs (:data after))))
      (is (string? (:eventLabel (:data after)))))))

(deftest xyflow-graph-region-data-carries-region-id-and-index
  (testing "a region container's `:data` carries `:regionId` +
            `:regionIndex`; a plain state's does not"
    (let [parsed (layout/parse-definition parallel-machine)
          graph  (projection/xyflow-graph parsed {} {})
          audio  (node-by-id graph (layout/region-node-id :audio))
          video  (node-by-id graph (layout/region-node-id :video))
          muted  (node-by-id graph (layout/node-id [:muted]))]
      (is (= :audio (:regionId (:data audio))))
      (is (= 0 (:regionIndex (:data audio))))
      (is (= 1 (:regionIndex (:data video))))
      (is (not (contains? (:data muted) :regionId))))))

;; ---- :density → threaded visual-constants (rf2-k647w) ------------------
;;
;; The xyflow node/edge components render OUTSIDE the chart's render
;; binding scope (React invokes them lazily), so the projector threads
;; the resolved density's visual-constants map onto every node/edge
;; `:data {:chart {...}}`. These pins guard that threading at the cheap
;; JVM layer — the DOM suite (chart_dom) then pins the rendered effect.

(deftest xyflow-graph-threads-chart-constants-onto-nodes
  (testing "rf2-k647w — the resolved `:chart` map rides on EVERY node's
            `:data` so the xyflow node component reads geometry off the
            payload (it is invoked outside the render binding scope)"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {:chart vc/chart-compact})]
      (is (seq (:nodes graph)))
      (is (every? #(= vc/chart-compact (:chart (:data %))) (:nodes graph))
          "compact density threads chart-compact onto every node"))))

(deftest xyflow-graph-threads-chart-constants-onto-edges
  (testing "rf2-k647w — the resolved `:chart` map rides on EVERY edge's
            `:data` so the edge label typography tracks the density"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {:chart vc/chart-cosy})]
      (is (seq (:edges graph)))
      (is (every? #(= vc/chart-cosy (:chart (:data %))) (:edges graph))
          "cosy density threads chart-cosy onto every edge"))))

(deftest xyflow-graph-chart-defaults-to-regular
  (testing "rf2-k647w — omitting `:chart` (the JVM tests, a density-less
            caller) defaults to `chart-regular` so the regular density
            stays pixel-identical to pre-rf2-k647w"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          idle   (node-by-id graph (layout/node-id [:idle]))]
      (is (= vc/chart-regular (:chart (:data idle)))))))

(deftest xyflow-graph-density-changes-threaded-constants
  (testing "rf2-k647w — switching the density threads a DIFFERENT
            constants map: the regular projection's tag-pill height
            differs from the compact projection's, proving `:density`
            actually changes what the renderer paints"
    (let [parsed   (layout/parse-definition idle-loading)
          idle-id  (layout/node-id [:idle])
          regular  (-> (projection/xyflow-graph parsed {} {:chart vc/chart-regular})
                       (node-by-id idle-id) :data :chart)
          compact  (-> (projection/xyflow-graph parsed {} {:chart vc/chart-compact})
                       (node-by-id idle-id) :data :chart)]
      (is (not= (:tag-pill-height regular) (:tag-pill-height compact)))
      (is (= 16 (:tag-pill-height regular)))
      (is (= 13 (:tag-pill-height compact)))
      ;; corner-radius is the locked invariant — identical across both
      (is (= (:corner-radius regular) (:corner-radius compact) 6)))))

;; ---- ->elk-children (G3) -----------------------------------------------

(deftest elk-children-flat-is-one-per-node
  (testing "a flat machine projects one elk child per parsed node, each
            with id + width/height floors + a label"
    (let [parsed   (layout/parse-definition idle-loading)
          children (projection/->elk-children parsed)]
      (is (= (count (:nodes parsed)) (count children)))
      (is (every? :id children))
      (is (every? #(pos? (:width %)) children))
      (is (every? #(pos? (:height %)) children)))))

(deftest elk-children-compound-uses-compound-floor
  (testing "a compound node gets the compound size floor; a leaf gets
            the state floor"
    (let [parsed   (layout/parse-definition compound-machine)
          children (projection/->elk-children parsed)
          by-id    (into {} (map (juxt :id identity)) children)
          parent   (get by-id (layout/node-id [:authenticated]))
          leaf     (get by-id (layout/node-id [:unauth]))]
      (is (= projection/compound-node-min-width  (:width parent)))
      (is (= projection/compound-node-min-height (:height parent)))
      (is (= projection/state-node-min-width  (:width leaf)))
      (is (= projection/state-node-min-height (:height leaf))))))

(deftest elk-children-parallel-nests-states-under-regions
  (testing "rf2-lkwev — a parallel machine projects ONE elk child per
            region (not per state); each region carries its own
            layoutOptions + nests its states as :children so elkjs lays
            them out inside the zone"
    (let [parsed   (layout/parse-definition parallel-machine)
          children (projection/->elk-children parsed)]
      ;; two regions → two top-level elk children
      (is (= 2 (count children)))
      (is (every? #(contains? % :layoutOptions) children))
      (is (every? #(seq (:children %)) children))
      ;; the audio region nests its two states
      (let [audio (first (filter #(= (layout/region-node-id :audio) (:id %))
                                 children))]
        (is (= 2 (count (:children audio))))))))

(deftest elk-children-region-padding-leaves-header-room
  (testing "each region's elk.padding leaves top room for the header
            strip the parallel-region-node paints"
    (let [parsed   (layout/parse-definition parallel-machine)
          children (projection/->elk-children parsed)]
      (is (every? #(= "layered" (get-in % [:layoutOptions "elk.algorithm"]))
                  children))
      (is (every? #(re-find #"top=" (get-in % [:layoutOptions "elk.padding"]))
                  children)))))
