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
  node-type, the parentId/extent sub-flow wiring, and the
  parent-before-child sort."
  {:type    :parallel
   :regions {:audio {:initial :muted
                     :states  {:muted   {:on {:unmute :playing}}
                               :playing {:on {:mute :muted}}}}
             :video {:initial :hidden
                     :states  {:hidden  {:on {:show :shown}}
                               :shown   {:on {:hide :hidden}}}}}})

(def nested-compound-machine
  "A two-level compound (`:outer` → `:mid` → `:leaf`) so the G4
  active-container chain can be pinned across MORE than one level —
  an active deep leaf must light EVERY enclosing container."
  {:initial :outer
   :states  {:outer {:initial :mid
                     :states  {:mid {:initial :leaf
                                     :states  {:leaf  {:on {:go :other}}
                                               :other {}}}}}}})

(def self-loop-machine
  "A machine with a self-transition (:idle --ping--> :idle) so the
  `:selfLoop` flag has a positive case."
  {:initial :idle
   :states  {:idle {:on {:ping :idle :go :busy}}
             :busy {:on {:done :idle}}}})

(def same-state-machine
  "rf2-ee38b.21 — external self-transition via the `:target :same-state`
  sentinel (Spec 005). Must project a self-loop, not a phantom node."
  {:initial :a
   :states  {:a {:on {:ping {:target :same-state}}}}})

(def internal-self-machine
  "rf2-ee38b.21 — internal self-transition (omit :target). Self-anchors
  and projects `:internal true`."
  {:initial :a
   :states  {:a {:on {:tick {:action :inc}}}}})

(def wildcard-machine
  "rf2-ee38b.21 — a `:*` wildcard `:on` arm (Spec 005 §Wildcard)."
  {:initial :a
   :states  {:a {:on {:start :b :* :err}}
             :b {}
             :err {}}})

(def machine-level-on-machine
  "rf2-ee38b.21 — a machine-level (top-level) :on fallback."
  {:initial :a :on {:logout :a} :states {:a {} :b {}}})

(def entry-exit-machine
  "rf2-ee38b.21 — :entry / :exit state actions."
  {:initial :a
   :states  {:a {:entry :on-enter :exit :on-leave}
             :b {}}})

(defn- edge-by-id
  "Pluck an edge from a projected graph by xyflow id."
  [graph id]
  (first (filter #(= id (:id %)) (:edges graph))))

(defn- node-by-id
  [graph id]
  (first (filter #(= id (:id %)) (:nodes graph))))

(defn- event-node-for
  "rf2-qo5xy — find the event-node a projected graph emitted for a
  given parsed-edge id. The events-as-nodes paradigm hoists each
  transition into a `\"rf2-event\"` xyflow node; the legacy single-
  edge state→state shape (with the event/guard/action on the edge
  label) is gone."
  [graph parsed-edge-id]
  (first (filter #(and (= "rf2-event" (:type %))
                       (= (str "event__" parsed-edge-id) (:id %)))
                 (:nodes graph))))

(defn- inbound-edge-for
  "rf2-qo5xy — the source-state → event-node edge for a parsed-edge id."
  [graph parsed-edge-id]
  (edge-by-id graph (str parsed-edge-id "__in")))

(defn- outbound-edge-for
  "rf2-qo5xy — the event-node → target-state edge for a parsed-edge
  id. nil for internal transitions (which emit no outbound edge)."
  [graph parsed-edge-id]
  (edge-by-id graph (str parsed-edge-id "__out")))

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

;; ---- xyflow-graph parentId / extent sub-flow wiring (G1) ---------------
;;
;; rf2-xh1lm — xyflow v12 reads `parentId` (NOT `parentNode`, the pre-v12
;; name). The projector must emit `:parentId` so xyflow's
;; `adoptUserNodes` walks the parent lookup and treats `:position` as
;; parent-relative; emitting `:parentNode` instead is silently ignored
;; (the previous shape — substates rendered at root + visually escaped
;; the parent container).

(deftest xyflow-graph-region-children-wire-parent-id
  (testing "rf2-lkwev + rf2-xh1lm — every state inside a region carries
            `:parentId` (the region container id) + `:extent \"parent\"`
            so xyflow v12's sub-flow nests + clamps it; the region
            container itself carries NEITHER"
    (let [parsed (layout/parse-definition parallel-machine)
          graph  (projection/xyflow-graph parsed {} {})
          region (node-by-id graph (layout/region-node-id :audio))
          muted  (node-by-id graph (layout/node-id [:muted]))]
      (is (= (layout/region-node-id :audio) (:parentId muted)))
      (is (= "parent" (:extent muted)))
      (is (nil? (:parentId region)) "region container is not nested")
      (is (nil? (:extent region))))))

(deftest xyflow-graph-region-children-do-not-emit-pre-v12-parent-node
  (testing "rf2-xh1lm — the projector emits the v12 `:parentId` shape
            ONLY; the pre-v12 `:parentNode` key MUST NOT appear (xyflow
            v12 silently ignores it, hiding the bug behind a green test
            suite — the regression mode this guards)"
    (let [parsed (layout/parse-definition parallel-machine)
          graph  (projection/xyflow-graph parsed {} {})]
      (doseq [n (:nodes graph)]
        (is (not (contains? n :parentNode))
            (str "node " (:id n) " must not carry the dead :parentNode key"))))))

(deftest xyflow-graph-flat-state-has-no-parent-id
  (testing "a state in a non-parallel machine carries no parentId /
            extent — those wire ONLY for region children"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          idle   (node-by-id graph (layout/node-id [:idle]))]
      (is (nil? (:parentId idle)))
      (is (nil? (:extent idle))))))

;; ---- xyflow-graph parent-before-child sort (G1) ------------------------

(deftest xyflow-graph-sorts-regions-before-children
  (testing "rf2-lkwev — xyflow requires a parentId target to appear in
            the nodes array BEFORE any node that references it (v12's
            `adoptUserNodes` warns otherwise). Every region container
            must precede its first child in the projected order."
    (let [parsed   (layout/parse-definition parallel-machine)
          graph    (projection/xyflow-graph parsed {} {})
          ids      (mapv :id (:nodes graph))
          index-of (fn [id] (.indexOf ids id))]
      (doseq [n (:nodes graph)
              :let [parent (:parentId n)]
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
              :let [parent (:parentId n)]
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

;; ---- xyflow-graph multi-active highlight (rf2-g2svr, G1) ----------------
;;
;; A PARALLEL machine's snapshot `:state` is a region-map — N
;; simultaneously-active leaves (one per region). `:highlight-ids` (a
;; SET) marks EVERY active leaf `:active` so the chart lights up all
;; regions at once (the §1.2 parity bar). The scalar `:highlight-id`
;; stays as a single-active convenience that folds into the set.

(deftest xyflow-graph-highlight-ids-marks-every-active-leaf
  (testing "rf2-g2svr (THE PARITY CAPABILITY) — passing a SET of two
            region-leaf ids marks BOTH region states `:active`
            simultaneously (parallel multi-active highlight)"
    (let [parsed     (layout/parse-definition parallel-machine)
          playing-id (layout/node-id [:playing])
          shown-id   (layout/node-id [:shown])
          muted-id   (layout/node-id [:muted])
          hidden-id  (layout/node-id [:hidden])
          graph      (projection/xyflow-graph
                       parsed {} {:highlight-ids #{playing-id shown-id}})]
      (is (true? (:active (:data (node-by-id graph playing-id))))
          ":audio region's active leaf lights up")
      (is (true? (:active (:data (node-by-id graph shown-id))))
          ":video region's active leaf lights up — SIMULTANEOUSLY")
      (is (false? (:active (:data (node-by-id graph muted-id))))
          "the inactive :audio leaf stays dark")
      (is (false? (:active (:data (node-by-id graph hidden-id))))
          "the inactive :video leaf stays dark"))))

(deftest xyflow-graph-highlight-ids-from-snapshot-resolver
  (testing "rf2-g2svr — end-to-end: `highlight-ids` resolves a parallel
            region-map to the set, the projection lights every active
            leaf. This is the live-chart path (chart.cljs calls
            highlight-ids on :current-state)."
    (let [parsed   (layout/parse-definition parallel-machine)
          ;; both regions advanced past initial
          state    {:audio :playing :video :shown}
          ids      (layout/highlight-ids state)
          graph    (projection/xyflow-graph parsed {} {:highlight-ids ids})
          ;; Scope to leaf STATE nodes — rf2-80rm2 (G4) additionally marks
          ;; the region CONTAINERS active (active-region chrome), which the
          ;; dedicated G4 tests below pin; here we keep the G1 invariant that
          ;; exactly the two active leaves light up among the state nodes.
          active-states (set (map :id (filter #(and (= "state" (:type %))
                                                    (:active (:data %)))
                                              (:nodes graph))))]
      (is (= #{(layout/node-id [:playing]) (layout/node-id [:shown])} active-states)
          "exactly the two active region leaves are marked active"))))

(deftest xyflow-graph-scalar-highlight-id-still-works
  (testing "rf2-g2svr — the scalar `:highlight-id` is back-compat: it
            folds into the active set as a singleton, so flat/compound
            callers (and existing tests) need no set"
    (let [parsed  (layout/parse-definition idle-loading)
          hi      (layout/node-id [:loading])
          graph   (projection/xyflow-graph parsed {} {:highlight-id hi})
          loading (node-by-id graph hi)
          idle    (node-by-id graph (layout/node-id [:idle]))]
      (is (true?  (:active (:data loading))))
      (is (false? (:active (:data idle)))))))

(deftest xyflow-graph-highlight-id-and-ids-union
  (testing "rf2-g2svr — when BOTH `:highlight-id` and `:highlight-ids`
            are supplied the active set is their union"
    (let [parsed (layout/parse-definition parallel-machine)
          a      (layout/node-id [:playing])
          b      (layout/node-id [:shown])
          graph  (projection/xyflow-graph
                   parsed {} {:highlight-id a :highlight-ids #{b}})]
      (is (true? (:active (:data (node-by-id graph a)))))
      (is (true? (:active (:data (node-by-id graph b))))))))

;; ---- xyflow-graph active-region CONTAINER chrome (rf2-80rm2, G4) ---------
;;
;; G1 lit the active LEAF; G4 lights the active region/compound CONTAINER
;; so the zone itself reads as active (Stately parity §1.4). The projector
;; folds a container into `:active` when ANY descendant leaf is in the
;; active set — walked up the `:parent-id` chain every node already carries
;; (no path-prefix reimplementation, no duplicate highlight logic). The
;; container components (parallel-region-node / compound-node) then paint
;; the active chrome; these JVM pins guard the projection half.

(deftest xyflow-graph-active-leaf-lights-its-region-container
  (testing "rf2-80rm2 (THE G4 CAPABILITY) — an active region LEAF marks its
            parallel-region CONTAINER `:active`, so the zone (not just the
            leaf inside it) reads as active"
    (let [parsed     (layout/parse-definition parallel-machine)
          playing-id (layout/node-id [:playing])
          audio-id   (layout/region-node-id :audio)
          graph      (projection/xyflow-graph
                       parsed {} {:highlight-ids #{playing-id}})
          audio      (node-by-id graph audio-id)]
      (is (= "parallel-region" (:type audio)) "fixture sanity: audio is a region")
      (is (true? (:active (:data audio)))
          "the :audio region container lights because its :playing leaf is active"))))

(deftest xyflow-graph-inactive-region-container-stays-inactive
  (testing "rf2-80rm2 — a region whose leaf is NOT in the active set keeps
            its container `:active false` (only the active region(s) get
            chrome — orthogonality of the active read)"
    (let [parsed     (layout/parse-definition parallel-machine)
          playing-id (layout/node-id [:playing])     ; :audio leaf, active
          audio-id   (layout/region-node-id :audio)
          video-id   (layout/region-node-id :video)
          graph      (projection/xyflow-graph
                       parsed {} {:highlight-ids #{playing-id}})]
      (is (true?  (:active (:data (node-by-id graph audio-id))))
          "the active region container lights")
      (is (false? (:active (:data (node-by-id graph video-id))))
          "the region with no active leaf stays inactive"))))

(deftest xyflow-graph-both-region-containers-active-when-both-have-active-leaf
  (testing "rf2-80rm2 — a parallel snapshot with an active leaf in EVERY
            region lights EVERY region container simultaneously (the
            multi-active read at the container level)"
    (let [parsed   (layout/parse-definition parallel-machine)
          state    {:audio :playing :video :shown}
          ids      (layout/highlight-ids state)
          graph    (projection/xyflow-graph parsed {} {:highlight-ids ids})
          audio    (node-by-id graph (layout/region-node-id :audio))
          video    (node-by-id graph (layout/region-node-id :video))]
      (is (true? (:active (:data audio))) ":audio container active")
      (is (true? (:active (:data video))) ":video container active"))))

(deftest xyflow-graph-compound-container-active-with-active-descendant
  (testing "rf2-80rm2 — self-consistency: a compound (non-parallel)
            container also gets active chrome when an active descendant
            leaf lit it (the same `:parent-id`-chain mechanic)"
    (let [parsed      (layout/parse-definition compound-machine)
          browsing-id (layout/node-id [:authenticated :browsing])
          authed-id   (layout/node-id [:authenticated])
          graph       (projection/xyflow-graph
                        parsed {} {:highlight-ids #{browsing-id}})
          authed      (node-by-id graph authed-id)]
      (is (= "compound" (:type authed)) "fixture sanity: authenticated is compound")
      (is (true? (:active (:data authed)))
          "the compound container lights because its :browsing leaf is active"))))

(deftest xyflow-graph-active-chain-lights-every-enclosing-container
  (testing "rf2-80rm2 — a deep active leaf lights EVERY enclosing
            container up the `:parent-id` chain (more than one level)"
    (let [parsed   (layout/parse-definition nested-compound-machine)
          leaf-id  (layout/node-id [:outer :mid :leaf])
          mid-id   (layout/node-id [:outer :mid])
          outer-id (layout/node-id [:outer])
          other-id (layout/node-id [:outer :mid :other])
          graph    (projection/xyflow-graph
                     parsed {} {:highlight-ids #{leaf-id}})]
      (is (true? (:active (:data (node-by-id graph leaf-id))))
          "the active leaf itself stays active (G1 unchanged)")
      (is (true? (:active (:data (node-by-id graph mid-id))))
          "the immediate compound parent lights")
      (is (true? (:active (:data (node-by-id graph outer-id))))
          "the grandparent compound lights too (chain walks all the way up)")
      (is (false? (:active (:data (node-by-id graph other-id))))
          "an inactive sibling leaf stays dark"))))

(deftest xyflow-graph-flat-machine-unaffected-by-container-chrome
  (testing "rf2-80rm2 — a flat machine has no containers, so the active
            set is exactly the active leaf(s); no spurious node lights"
    (let [parsed   (layout/parse-definition idle-loading)
          hi       (layout/node-id [:loading])
          graph    (projection/xyflow-graph parsed {} {:highlight-id hi})
          flagged  (filter #(contains? (:data %) :active) (:nodes graph))
          actives  (set (map :id (filter #(:active (:data %)) flagged)))]
      (is (= #{hi} actives)
          "exactly the highlighted leaf is active — no container chrome leaks"))))

(deftest xyflow-graph-inactive-compound-container-stays-inactive
  (testing "rf2-80rm2 — a compound with NO active descendant keeps its
            container `:active false` (no highlight → no chrome)"
    (let [parsed (layout/parse-definition compound-machine)
          graph  (projection/xyflow-graph parsed {} {})
          authed (node-by-id graph (layout/node-id [:authenticated]))]
      (is (false? (:active (:data authed)))
          "no highlight → the compound container is inactive"))))

(deftest xyflow-graph-no-highlight-leaves-all-inactive
  (testing "rf2-g2svr — neither `:highlight-id` nor `:highlight-ids` →
            no state/region node is active (empty set, never
            nil-comparison surprises). Initial-marker nodes carry no
            `:active` key at all (they are not states), so the check
            scopes to nodes that carry the flag."
    (let [parsed (layout/parse-definition parallel-machine)
          graph  (projection/xyflow-graph parsed {} {})
          flagged (filter #(contains? (:data %) :active) (:nodes graph))]
      (is (seq flagged) "fixture has state/region nodes carrying :active")
      (is (every? #(false? (:active (:data %))) flagged)))))

(deftest xyflow-graph-multi-active-edges-active-in-each-region
  (testing "rf2-g2svr — with N active leaves, an edge touching ANY active
            leaf is `:active`. Each region's self/incident edge lights up
            independently (orthogonality preserved)."
    (let [parsed   (layout/parse-definition parallel-machine)
          ids      #{(layout/node-id [:playing]) (layout/node-id [:shown])}
          graph    (projection/xyflow-graph parsed {} {:highlight-ids ids})
          active-e (filter #(:active (:data %)) (:edges graph))
          ;; edges incident to :playing (audio) and :shown (video)
          regions  (set (map (fn [e]
                               (cond
                                 (or (= (:source e) (layout/node-id [:playing]))
                                     (= (:target e) (layout/node-id [:playing]))) :audio
                                 (or (= (:source e) (layout/node-id [:shown]))
                                     (= (:target e) (layout/node-id [:shown]))) :video
                                 :else :other))
                             active-e))]
      (is (seq active-e) "at least one edge is active")
      (is (contains? regions :audio) "an :audio-region edge is active")
      (is (contains? regions :video) "a :video-region edge is active"))))

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
  (testing "rf2-qo5xy — events-as-nodes paradigm: an inbound edge
            (source-state → event-node) is `:focused` when the parsed
            transition's source/target match the from/to lens. The
            paired outbound edge (event-node → target-state) gets the
            same focused flag so the WHOLE traversal lights up."
    (let [parsed (layout/parse-definition idle-loading)
          from   (layout/node-id [:idle])
          to     (layout/node-id [:loading])
          start-edge (->> (:edges parsed)
                          (filter #(= (:source %) from))
                          first)
          graph  (projection/xyflow-graph parsed {}
                                          {:from-highlight-id from
                                           :to-highlight-id   to})
          in-edge  (inbound-edge-for  graph (:id start-edge))
          out-edge (outbound-edge-for graph (:id start-edge))]
      (is (some? in-edge)  "the inbound edge for the focused transition exists")
      (is (some? out-edge) "and so does the outbound edge")
      (is (true? (:focused (:data in-edge))))
      (is (true? (:focused (:data out-edge))))
      ;; Every OTHER edge is not focused.
      (let [other-edges (remove #(#{(:id in-edge) (:id out-edge)} (:id %))
                                (:edges graph))]
        (is (every? false? (map (comp :focused :data) other-edges)))))))

(deftest xyflow-graph-edge-not-focused-without-both-lens-ends
  (testing "with only ONE lens end set, no edge is focused (the
            some?/some? guard requires both)"
    (let [parsed (layout/parse-definition idle-loading)
          from   (layout/node-id [:idle])
          graph  (projection/xyflow-graph parsed {} {:from-highlight-id from})]
      (is (every? false? (map (comp :focused :data) (:edges graph)))))))

;; ---- xyflow-graph arrowheads (rf2-5qsxo) -------------------------------

(deftest xyflow-graph-edge-requests-arrowclosed-marker-end
  (testing "rf2-5qsxo — every transition edge requests an `arrowclosed`
            markerEnd so React Flow draws an arrowhead at the target end
            (the custom edge component forwards the resolved url to its
            BaseEdge)."
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})]
      (is (seq (:edges graph)))
      (is (every? #(= "arrowclosed" (:type (:markerEnd %))) (:edges graph))
          "every edge carries an arrowclosed markerEnd")
      (is (every? #(string? (:color (:markerEnd %))) (:edges graph))
          "the marker colour resolves to a token string"))))

(deftest xyflow-graph-marker-end-colour-tracks-active-edge
  (testing "rf2-5qsxo — the arrowhead colour tracks the edge stroke: an
            edge touching the highlighted node uses the active (cyan)
            colour, idle edges use the default border colour, so the
            marker reads as part of the same line."
    ;; Highlight `:ready` (a leaf): only the loading→ready edge touches
    ;; it, so the graph has a clean active/inactive split.
    (let [parsed   (layout/parse-definition idle-loading)
          hi       (layout/node-id [:ready])
          graph    (projection/xyflow-graph parsed {} {:highlight-id hi})
          active   (first (filter #(:active (:data %)) (:edges graph)))
          inactive (first (filter #(not (:active (:data %))) (:edges graph)))]
      (is (some? active) "fixture has at least one active edge")
      (is (some? inactive) "fixture has at least one idle edge")
      (is (not= (:color (:markerEnd active))
                (:color (:markerEnd inactive)))
          "active vs idle arrowheads are distinct colours"))))

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

(deftest xyflow-graph-compound-style-from-measured-position
  (testing "rf2-a64bi — a compound container's `:style {:width :height}`
            comes from its measured position entry, the SAME way a region
            container's does. The compound renderer fills its box with
            `width:100% height:100%`, so without the styled box xyflow
            falls back to `compound-node-min-{width,height}` (220×120)
            and substates whose parent-relative elk coords were computed
            against the FULL measured extent overflow + visually escape
            the container."
    (let [parsed    (layout/parse-definition compound-machine)
          cid       (layout/node-id [:authenticated])
          positions {cid {:x 0 :y 0 :width 328 :height 156}}
          graph     (projection/xyflow-graph parsed positions {})
          compound  (node-by-id graph cid)]
      (is (= "compound" (:type compound)) "fixture sanity: authenticated is compound")
      (is (= {:width 328 :height 156} (:style compound))))))

(deftest xyflow-graph-compound-style-coexists-with-parent-relative-substates
  (testing "rf2-a64bi + rf2-xh1lm — when a compound has substates, the
            compound's `:style {:width :height}` matches elk's bounding
            box AND each substate carries `:parentId` (xyflow v12's
            sub-flow key, NOT the pre-v12 `:parentNode`) + `:extent
            \"parent\"` with a parent-relative `:position`. The two
            together are the containment contract: xyflow adopts the
            child against the parent's measured box, then clamps the
            parent-relative substates inside it."
    (let [parsed    (layout/parse-definition compound-machine)
          cid       (layout/node-id [:authenticated])
          browsing  (layout/node-id [:authenticated :browsing])
          paying    (layout/node-id [:authenticated :paying])
          positions {cid      {:x 0   :y 0  :width 328 :height 156}
                     browsing {:x 14  :y 34 :width 140 :height 44}
                     paying   {:x 174 :y 34 :width 140 :height 44}}
          graph     (projection/xyflow-graph parsed positions {})
          compound  (node-by-id graph cid)
          b         (node-by-id graph browsing)
          p         (node-by-id graph paying)]
      (is (= {:width 328 :height 156} (:style compound))
          "compound gets elk's measured box as :style")
      (is (= cid (:parentId b)) ":browsing nests under :authenticated")
      (is (= cid (:parentId p)) ":paying nests under :authenticated")
      (is (not (contains? b :parentNode))
          "rf2-xh1lm — the pre-v12 :parentNode key MUST NOT appear")
      (is (not (contains? p :parentNode)))
      (is (= "parent" (:extent b)))
      (is (= "parent" (:extent p)))
      (is (= {:x 14 :y 34} (:position b))
          "substate :position is parent-relative (passed through verbatim)")
      (is (= {:x 174 :y 34} (:position p))))))

(deftest xyflow-graph-leaf-state-has-no-style
  (testing "rf2-a64bi — a leaf (non-container) state carries NO `:style`
            even with a measured size in the positions map; xyflow sizes
            leaf nodes from the rendered DOM (`state-node-min-{width,height}`)
            rather than a projector-supplied box."
    (let [parsed    (layout/parse-definition idle-loading)
          idle-id   (layout/node-id [:idle])
          positions {idle-id {:x 0 :y 0 :width 200 :height 60}}
          graph     (projection/xyflow-graph parsed positions {})
          idle      (node-by-id graph idle-id)]
      (is (nil? (:style idle))))))

(deftest xyflow-graph-position-defaults-to-origin
  (testing "a node with no entry in the positions map defaults to
            {:x 0 :y 0} (the pre-layout placeholder)"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          idle   (node-by-id graph (layout/node-id [:idle]))]
      (is (= {:x 0 :y 0} (:position idle))))))

(deftest xyflow-graph-event-node-carries-after-ms-and-event-label
  (testing "rf2-qo5xy — events-as-nodes paradigm: the event-node
            (not the edge) carries the `:afterMs` + the visible event
            label. The `⌚`-prefixed segment (from chart.layout/event-
            segment) rides on the event-node's `:eventLabel`."
    (let [parsed     (layout/parse-definition idle-loading)
          graph      (projection/xyflow-graph parsed {} {})
          after-parsed (first (filter :after (:edges parsed)))
          after-node (event-node-for graph (:id after-parsed))]
      (is (some? after-parsed) "parser emitted an :after edge")
      (is (some? after-node)   "projector emitted the matching event-node")
      (is (= 1000 (:afterMs (:data after-node))))
      (is (string? (:eventLabel (:data after-node))))
      (is (= "after" (:variant (:data after-node)))
          "the variant attribute identifies the `:after` event-node kind"))))

(deftest xyflow-graph-event-node-eventLabel-is-event-segment
  (testing "rf2-qo5xy — the event-node's `:eventLabel` is the raw
            event-segment text from chart.layout/event-segment (e.g.
            \"start\"); guard/action ride on dedicated `:guard` +
            `:action` slots of the event-node payload."
    (let [parsed     (layout/parse-definition idle-loading)
          graph      (projection/xyflow-graph parsed {} {})
          start-parsed (->> (:edges parsed)
                            (filter #(= (:source %) (layout/node-id [:idle])))
                            first)
          ev-node    (event-node-for graph (:id start-parsed))]
      (is (some? start-parsed) "parser emitted the :start edge")
      (is (some? ev-node)      "projector emitted the matching event-node")
      (is (= "start" (:eventLabel (:data ev-node)))
          "no guard/action: the label is just the event segment")
      (is (= "on" (:variant (:data ev-node)))
          "regular :on event-node variant"))))

(deftest xyflow-graph-event-node-surfaces-guard-and-action
  (testing "rf2-qo5xy — when an edge declares a guard / action, the
            event-node's `:data` carries them as separate strings (the
            renderer paints the `[guard]` chip + `+ <action>` pill from
            these). The legacy `event [guard] / action` text composition
            is gone — each piece sits in its own slot."
    (let [m {:initial :idle
             :states  {:idle {:on {:submit {:target :loading
                                            :guard  :authed?
                                            :action :log-it}}}
                       :loading {}}}
          parsed (layout/parse-definition m)
          graph  (projection/xyflow-graph parsed {} {})
          submit-parsed (first (:edges parsed))
          ev-node       (event-node-for graph (:id submit-parsed))]
      (is (some? ev-node) "the event-node was projected")
      (is (= "submit"  (:eventLabel (:data ev-node))))
      (is (= "authed?" (:guard      (:data ev-node))))
      (is (= "log-it"  (:action     (:data ev-node)))))))

(deftest xyflow-graph-entry-edge-carries-empty-event-line-label
  (testing "rf2-a2b55 — entry edges (initial-marker → leaf) have no
            event label; the every-edge-:data invariant means they
            carry `:eventLineLabel \"\"` alongside the existing
            `:eventLabel \"\"`."
    (let [parsed   (layout/parse-definition idle-loading)
          graph    (projection/xyflow-graph parsed {} {})
          idle-id  (layout/node-id [:idle])
          marker-id (str "initial__" idle-id)
          entry    (edge-by-id graph (str marker-id "__entry"))]
      (is (some? entry))
      (is (= "" (:eventLineLabel (:data entry))))
      (is (= "" (:eventLabel (:data entry)))))))

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
            constants map: the regular projection's state-label font
            size differs from the compact projection's, proving
            `:density` actually changes what the renderer paints.

            rf2-so5b0 — historical assertions on `:tag-pill-height`
            replaced with `:state-label-px` since the tag-pill family
            retired with the visible pill row; the state-label keys
            are now the density's load-bearing typography surface."
    (let [parsed   (layout/parse-definition idle-loading)
          idle-id  (layout/node-id [:idle])
          regular  (-> (projection/xyflow-graph parsed {} {:chart vc/chart-regular})
                       (node-by-id idle-id) :data :chart)
          compact  (-> (projection/xyflow-graph parsed {} {:chart vc/chart-compact})
                       (node-by-id idle-id) :data :chart)]
      (is (not= (:state-label-px regular) (:state-label-px compact)))
      (is (= 13 (:state-label-px regular)))
      (is (= 11 (:state-label-px compact)))
      ;; corner-radius is the locked invariant — identical across both
      (is (= (:corner-radius regular) (:corner-radius compact) 6)))))

;; ---- on-chart edge-click wiring (rf2-u422r) ----------------------------
;;
;; The on-chart machine simulator clicks a transition edge to send its
;; event into the hermetic sim engine. The projector threads the host's
;; `:on-edge-click` callback onto every edge `:data {:onClick}` + carries
;; the raw fireable `:eventId` / `:fromPath` / `:toPath` so the edge
;; component can hand the host the originating transition. These JVM pins
;; guard that wiring; the edge component's click behaviour is pinned at
;; the DOM layer (chart_dom).

(deftest xyflow-graph-event-node-carries-fireable-event-id
  (testing "rf2-u422r + rf2-qo5xy — the event-node (not an edge) carries
            its fireable `:eventId` for the on-chart sim path; from/to
            paths ride with it so the host can dispatch the originating
            transition."
    (let [parsed   (layout/parse-definition idle-loading)
          graph    (projection/xyflow-graph parsed {} {})
          start    (->> (:edges parsed)
                        (filter #(= (:source %) (layout/node-id [:idle])))
                        first)
          ev-node  (event-node-for graph (:id start))]
      (is (some? ev-node) "the start event-node was projected")
      (is (= :start (:eventId (:data ev-node)))
          "the raw fireable event keyword rides on the event-node")
      (is (= [:idle]    (:fromPath (:data ev-node))))
      (is (= [:loading] (:toPath   (:data ev-node)))))))

(deftest xyflow-graph-after-and-always-event-nodes-not-fireable
  (testing "rf2-u422r + rf2-qo5xy — `:after` + `:always` event-nodes
            carry nil `:eventId` (the engine fires them automatically;
            the host filters them out for clickability). Their variant
            slot still identifies them as `:after` / `:always`."
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          after-parsed  (first (filter :after   (:edges parsed)))
          always-parsed (first (filter :always? (:edges parsed)))
          after-node    (event-node-for graph (:id after-parsed))
          always-node   (event-node-for graph (:id always-parsed))]
      (is (some? after-node)  "fixture has an :after event-node")
      (is (nil? (:eventId (:data after-node))) "not user-fireable")
      (is (= "after" (:variant (:data after-node))))
      (is (some? always-node) "fixture has an :always event-node")
      (is (nil? (:eventId (:data always-node))) "not user-fireable")
      (is (= "always" (:variant (:data always-node)))))))

(deftest xyflow-graph-threads-on-edge-click-onto-every-event-node
  (testing "rf2-u422r + rf2-qo5xy — the host's `:on-edge-click` (now
            on-event-click) threads onto every event-node's
            `:data {:onClick}` (the event-node component decides
            clickability from the callback + fireable eventId pair)."
    (let [parsed   (layout/parse-definition idle-loading)
          captured (atom nil)
          cb       (fn [m] (reset! captured m))
          graph    (projection/xyflow-graph parsed {} {:on-edge-click cb})
          ev-nodes (filter #(= "rf2-event" (:type %)) (:nodes graph))]
      (is (seq ev-nodes))
      (is (every? #(= cb (:onClick (:data %))) ev-nodes)
          "every event-node carries the same on-event-click callback"))))

(deftest xyflow-graph-omits-on-click-when-no-callback
  (testing "rf2-u422r — omitting `:on-edge-click` leaves `:onClick` nil
            so the edge label stays inert (no wiring)"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})]
      (is (every? #(nil? (:onClick (:data %))) (:edges graph))))))

;; ---- ->elk-children (G3) -----------------------------------------------

(deftest elk-children-flat-is-state-plus-event-nodes
  (testing "rf2-qo5xy — a flat machine projects one elk child per
            parsed state node PLUS one synthetic event-node per parsed
            transition (events-as-nodes paradigm). All children carry
            id + width/height + label."
    (let [parsed   (layout/parse-definition idle-loading)
          children (projection/->elk-children parsed)
          n-states (count (:nodes parsed))
          n-events (count (:edges parsed))]
      (is (= (+ n-states n-events) (count children))
          "states + events = total flat children")
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
  (testing "rf2-lkwev + rf2-qo5xy — a parallel machine projects ONE elk
            top-level child per region (regions are the only top-level
            structural containers); each region nests its states AND
            the events declared inside as `:children`."
    (let [parsed   (layout/parse-definition parallel-machine)
          children (projection/->elk-children parsed)
          regions  (filter #(re-find #"^region__" (:id %)) children)]
      (is (= 2 (count regions))
          "two regions land as top-level elk children")
      (is (every? #(contains? % :layoutOptions) regions))
      (is (every? #(seq (:children %)) regions))
      ;; the audio region nests its two states + its two events
      ;; (`:unmute` + `:mute`).
      (let [audio (first (filter #(= (layout/region-node-id :audio) (:id %))
                                 regions))]
        (is (= 4 (count (:children audio)))
            "2 states + 2 events nest under the audio region")))))

(deftest elk-children-region-padding-leaves-header-room
  (testing "each region's elk.padding leaves top room for the header
            strip the parallel-region-node paints"
    (let [parsed   (layout/parse-definition parallel-machine)
          children (projection/->elk-children parsed)]
      (is (every? #(= "layered" (get-in % [:layoutOptions "elk.algorithm"]))
                  children))
      (is (every? #(re-find #"top=" (get-in % [:layoutOptions "elk.padding"]))
                  children)))))

;; ---- measure-then-relayout: ELK sizes to the real box (rf2-d9ro2) -------
;;
;; The bug: ELK was fed CONSTANT floor dimensions, never the real
;; rendered box, so any node whose content (long label + tag/action
;; pills) exceeded the floor overlapped its neighbours. The fix threads
;; xyflow's measured `{node-id {:width :height}}` into the projection;
;; a leaf / event-node lays out at `(max measured floor)`. These pins
;; live at the cheap JVM layer (the live re-layout lifecycle is wired in
;; chart.cljs + browser-pinned); they fix the producer side of the bug.

(deftest leaf-elk-size-floors-to-min-when-unmeasured
  (testing "rf2-d9ro2 — with no measurement (first pass) a leaf falls
            back to the `state-node-min-{width,height}` floor — the
            pre-fix single-pass behaviour"
    (is (= {:width  projection/state-node-min-width
            :height projection/state-node-min-height}
           (projection/leaf-elk-size nil)))
    (is (= {:width  projection/state-node-min-width
            :height projection/state-node-min-height}
           (projection/leaf-elk-size {})))))

(deftest leaf-elk-size-uses-measured-when-larger
  (testing "rf2-d9ro2 — a measured box LARGER than the floor wins per
            dimension (ELK must budget the real rendered size)"
    (let [big (projection/leaf-elk-size {:width 300 :height 90})]
      (is (= 300 (:width big)))
      (is (= 90  (:height big))))))

(deftest leaf-elk-size-floors-each-dimension-independently
  (testing "rf2-d9ro2 — the floor applies PER dimension: a node wider
            than the floor but shorter than it keeps the wide measured
            width AND the floor height"
    (let [m (projection/leaf-elk-size {:width 320 :height 10})]
      (is (= 320 (:width m)) "wide measured width wins")
      (is (= projection/state-node-min-height (:height m))
          "sub-floor measured height clamps up to the floor"))))

(deftest elk-child-leaf-uses-measured-dims
  (testing "rf2-d9ro2 — `elk-child` sizes a LEAF to its measured box
            (floored), looked up by node-id from the measured-dims map"
    (let [parsed   (layout/parse-definition idle-loading)
          idle-id  (layout/node-id [:idle])
          measured {idle-id {:width 260 :height 72}}
          idle     (first (filter #(= idle-id (:id %)) (:nodes parsed)))
          child    (projection/elk-child idle measured)]
      (is (= 260 (:width child)))
      (is (= 72  (:height child)))))
  (testing "rf2-d9ro2 — an unmeasured leaf (absent from the map) keeps
            the floor"
    (let [parsed   (layout/parse-definition idle-loading)
          idle     (first (filter #(= (layout/node-id [:idle]) (:id %))
                                  (:nodes parsed)))
          child    (projection/elk-child idle {})]
      (is (= projection/state-node-min-width  (:width child)))
      (is (= projection/state-node-min-height (:height child))))))

(deftest elk-child-compound-ignores-measured-dims
  (testing "rf2-d9ro2 — a COMPOUND keeps its floor seed even with a
            measured entry: its true extent comes from ELK laying out its
            measured children, so feeding back its `100%`-of-the-box
            self-measurement would be circular"
    (let [parsed    (layout/parse-definition compound-machine)
          cid       (layout/node-id [:authenticated])
          measured  {cid {:width 999 :height 999}}
          compound  (first (filter #(= cid (:id %)) (:nodes parsed)))
          child     (projection/elk-child compound measured)]
      (is (= projection/compound-node-min-width  (:width child)))
      (is (= projection/compound-node-min-height (:height child))))))

(deftest elk-event-child-uses-measured-dims
  (testing "rf2-d9ro2 — an event-node also renders at content size
            (event header + guard chip + action pill), so it takes
            `(max measured floor)` like a leaf"
    (let [edge     {:id "e1" :event :start}
          ev-id    (projection/event-node-id edge)
          measured {ev-id {:width 200 :height 80}}
          child    (projection/elk-event-child edge measured)]
      (is (= 200 (:width child)))
      (is (= 80  (:height child))))
    (testing "unmeasured event-node keeps the event-node floor"
      (let [child (projection/elk-event-child {:id "e1" :event :start} nil)]
        (is (= projection/event-node-elk-width  (:width child)))
        (is (= projection/event-node-elk-height (:height child)))))))

(deftest elk-children-threads-measured-dims-to-leaves-and-events
  (testing "rf2-d9ro2 — `->elk-children` forwards the measured-dims map
            so every leaf + event-node in the projected tree lays out at
            its real box; an unmeasured node falls back to the floor"
    (let [parsed     (layout/parse-definition idle-loading)
          idle-id    (layout/node-id [:idle])
          start-edge (->> (:edges parsed)
                          (filter #(= idle-id (:source %)))
                          first)
          ev-id      (projection/event-node-id start-edge)
          measured   {idle-id {:width 260 :height 72}
                      ev-id   {:width 180 :height 60}}
          children   (projection/->elk-children parsed measured)
          by-id      (into {} (map (juxt :id identity)) children)]
      (is (= 260 (:width (get by-id idle-id))) "measured leaf width threaded")
      (is (= 72  (:height (get by-id idle-id))))
      (is (= 180 (:width (get by-id ev-id))) "measured event width threaded")
      (is (= 60  (:height (get by-id ev-id))))
      ;; A sibling NOT in the measured map stays at the floor.
      (let [ready-id (layout/node-id [:ready])]
        (is (= projection/state-node-min-width
               (:width (get by-id ready-id)))
            "unmeasured sibling keeps the floor")))))

(deftest elk-children-no-measured-dims-equals-floor-single-pass
  (testing "rf2-d9ro2 — with no measured-dims (the first pass / nil) the
            output is identical to the historical single-pass: every leaf
            at the floor. Guards that the two-pass is purely additive."
    (let [parsed (layout/parse-definition idle-loading)]
      (is (= (projection/->elk-children parsed)
             (projection/->elk-children parsed nil))
          "the 1-arity and nil-2-arity are identical")
      (let [children (projection/->elk-children parsed nil)
            leaves   (remove #(re-find #"^event__" (:id %)) children)]
        (is (every? #(= projection/state-node-min-width (:width %)) leaves)
            "every leaf at the width floor when unmeasured")))))

;; ---- initial-state markers + self-loops (rf2-54s5a) --------------------

(deftest xyflow-graph-emits-initial-marker-node-and-entry-edge
  (testing "rf2-54s5a — the machine's initial state gets a synthetic
            initial-marker node + an unlabelled entry edge into it"
    (let [parsed   (layout/parse-definition idle-loading)
          graph    (projection/xyflow-graph parsed {} {})
          idle-id   (layout/node-id [:idle])
          marker-id (str "initial__" idle-id)
          marker   (node-by-id graph marker-id)
          entry    (edge-by-id graph (str marker-id "__entry"))]
      (is (some? marker) "initial-marker node emitted")
      (is (= "initial-marker" (:type marker)))
      (is (some? entry) "entry edge emitted")
      (is (= marker-id (:source entry)))
      (is (= idle-id (:target entry)))
      (is (= "left" (:targetHandle entry)))
      (is (= "" (:eventLabel (:data entry)))
          "entry edge has no event label"))))

(deftest xyflow-graph-threads-initial-flag-onto-node-data
  (testing "rf2-54s5a — node :data carries :initial (true for the
            machine's initial state, false otherwise)"
    (let [parsed  (layout/parse-definition idle-loading)
          graph   (projection/xyflow-graph parsed {} {})
          idle    (node-by-id graph (layout/node-id [:idle]))
          loading (node-by-id graph (layout/node-id [:loading]))]
      (is (true?  (:initial (:data idle))))
      (is (false? (:initial (:data loading)))))))

(deftest xyflow-graph-emits-compound-substate-initial-marker
  (testing "rf2-54s5a + rf2-xh1lm — a compound parent's :initial substate
            also gets a marker (xstate per-level initial semantics) sharing
            the compound's coordinate frame via xyflow v12's `:parentId`"
    (let [parsed      (layout/parse-definition compound-machine)
          graph       (projection/xyflow-graph parsed {} {})
          browsing-id (layout/node-id [:authenticated :browsing])
          marker      (node-by-id graph (str "initial__" browsing-id))]
      (is (some? marker) "the compound's initial substate gets a marker")
      (is (= (layout/node-id [:authenticated]) (:parentId marker)))
      (is (not (contains? marker :parentNode))
          "rf2-xh1lm — the pre-v12 :parentNode key MUST NOT appear"))))

(deftest xyflow-graph-self-transition-routes-through-event-node
  (testing "rf2-qo5xy — a self-transition (source == target in the
            parsed graph) routes through its event-node like every
            other transition: source-state → event-node → source-state.
            The structural self-loop becomes a TWO-edge fork — the
            event-node sits beside the state, both edges anchor on it.
            Pre-rf2-qo5xy a self-loop was a single edge with selfLoop
            true; the events-as-nodes paradigm dissolves that special
            case (the visible loop arc is now the route around the
            event-node)."
    (let [parsed   (layout/parse-definition self-loop-machine)
          graph    (projection/xyflow-graph parsed {} {})
          self     (first (filter #(= (:source %) (:target %))
                                  (:edges parsed)))
          in-edge  (inbound-edge-for  graph (:id self))
          out-edge (outbound-edge-for graph (:id self))]
      (is (some? self)     "fixture has a self-transition (parsed)")
      (is (some? in-edge)  "the inbound edge survives projection")
      (is (some? out-edge) "the outbound edge survives projection")
      (is (= (:source self) (:source in-edge))  "inbound source == state")
      (is (= (:source self) (:target out-edge)) "outbound target == state")
      ;; Both edges connect through the same event-node.
      (is (= (:target in-edge) (:source out-edge))
          "inbound target == outbound source == event-node"))))

(deftest xyflow-graph-compound-children-wire-parent-id
  (testing "rf2-54s5a + rf2-xh1lm — compound substates nest via xyflow
            v12's `:parentId` (same mechanic as parallel-region children;
            the pre-v12 `:parentNode` key is silently ignored by v12 so
            the projector MUST NOT emit it)"
    (let [parsed   (layout/parse-definition compound-machine)
          graph    (projection/xyflow-graph parsed {} {})
          browsing (node-by-id graph (layout/node-id [:authenticated :browsing]))]
      (is (= (layout/node-id [:authenticated]) (:parentId browsing)))
      (is (= "parent" (:extent browsing)))
      (is (not (contains? browsing :parentNode))
          "rf2-xh1lm — the pre-v12 :parentNode key MUST NOT appear"))))

(deftest elk-children-nests-compound-substates-and-events
  (testing "rf2-54s5a + rf2-qo5xy — a compound parent nests its
            substates AS WELL AS the event-nodes of any transition
            whose source is inside the compound. State nodes count is
            unchanged (2); event-nodes for `:checkout` (browsing → paying)
            and `:done` (paying → browsing) sit inside too."
    (let [parsed   (layout/parse-definition compound-machine)
          children (projection/->elk-children parsed)
          by-id    (into {} (map (juxt :id identity) children))
          authed   (get by-id (layout/node-id [:authenticated]))]
      (is (some? authed) "compound parent is a top-level elk child")
      (let [kid-types (group-by #(if (re-find #"^event__" (:id %))
                                   :event :state)
                                (:children authed))]
        (is (= 2 (count (:state kid-types)))
            "browsing + paying state-nodes nest inside")
        (is (= 2 (count (:event kid-types)))
            "two event-nodes (checkout + done) nest inside too")))))

;; ---- self-transitions / wildcard / machine-level :on (rf2-ee38b.21) ----

(deftest xyflow-graph-same-state-projects-through-event-node
  (testing "rf2-ee38b.21 + rf2-qo5xy — `:target :same-state` resolves
            to source == target in the parsed graph; the projector
            routes the transition through an event-node (no phantom
            target node)."
    (let [parsed   (layout/parse-definition same-state-machine)
          graph    (projection/xyflow-graph parsed {} {})
          node-ids (set (map :id (:nodes graph)))
          ping     (first (:edges parsed))
          in-edge  (inbound-edge-for  graph (:id ping))
          out-edge (outbound-edge-for graph (:id ping))]
      (is (= (:source ping) (:target ping)) "parsed: source == target")
      (is (some? in-edge))
      (is (some? out-edge))
      (is (contains? node-ids (:target out-edge))
          "the outbound target is a real node, not a phantom"))))

(deftest xyflow-graph-internal-self-transition-emits-no-outbound
  (testing "rf2-ee38b.21 + rf2-qo5xy — an internal self-transition
            (omit :target) emits an inbound edge into the event-node
            but NO outbound edge — the Stately convention 'runs an
            action and we hang here'. The event-node carries
            `:internal true`."
    (let [parsed   (layout/parse-definition internal-self-machine)
          graph    (projection/xyflow-graph parsed {} {})
          tick     (first (:edges parsed))
          ev-node  (event-node-for graph (:id tick))
          in-edge  (inbound-edge-for  graph (:id tick))
          out-edge (outbound-edge-for graph (:id tick))]
      (is (true? (:internal? tick)) "parser flagged the internal transition")
      (is (some? ev-node))
      (is (true? (:internal (:data ev-node))))
      (is (some? in-edge)  "inbound edge into the event-node is emitted")
      (is (nil? out-edge)  "no outbound edge (internal hangs at the event-node)"))))

(deftest xyflow-graph-wildcard-event-node-not-fireable
  (testing "rf2-ee38b.21 + rf2-qo5xy — the `:*` wildcard transition's
            event-node carries a NIL :eventId (not user-fireable on
            the chart); the real `:start` event-node stays fireable."
    (let [parsed (layout/parse-definition wildcard-machine)
          graph  (projection/xyflow-graph parsed {} {})
          ev-nodes (filter #(= "rf2-event" (:type %)) (:nodes graph))
          wild   (first (filter #(re-find #"\(any\)"
                                          (or (:eventLabel (:data %)) ""))
                                ev-nodes))
          start  (first (filter #(= :start (:eventId (:data %))) ev-nodes))]
      (is (some? wild) "the wildcard event-node is present")
      (is (nil? (:eventId (:data wild))))
      (is (some? start) "the real :start event-node stays fireable")
      (is (= :start (:eventId (:data start)))))))

(deftest xyflow-graph-machine-level-event-nodes-flagged
  (testing "rf2-ee38b.21 + rf2-qo5xy — machine-level (top-level) :on
            fallback transitions project as event-nodes flagged
            `:machineLevel true` (one per inheriting leaf)."
    (let [parsed (layout/parse-definition machine-level-on-machine)
          graph  (projection/xyflow-graph parsed {} {})
          ev-nodes (filter #(= "rf2-event" (:type %)) (:nodes graph))
          logout (filter #(= :logout (:eventId (:data %))) ev-nodes)]
      (is (= 2 (count logout)) "one inherited event-node per leaf")
      (is (every? #(true? (:machineLevel (:data %))) logout)))))

(deftest xyflow-graph-state-only-transitions-not-machine-level
  (testing "rf2-ee38b.21 + rf2-qo5xy — a normal state-local transition's
            event-node carries `:machineLevel false`."
    (let [parsed  (layout/parse-definition idle-loading)
          graph   (projection/xyflow-graph parsed {} {})
          start-p (->> (:edges parsed)
                       (filter #(= :start (:event %)))
                       first)
          ev-node (event-node-for graph (:id start-p))]
      (is (some? ev-node))
      (is (false? (:machineLevel (:data ev-node))))
      (is (false? (:internal     (:data ev-node)))))))

;; ---- entry / exit state actions (rf2-ee38b.21) -------------------------

(deftest xyflow-graph-threads-entry-exit-onto-node-data
  (testing "rf2-ee38b.21; rf2-a2b55 — :entry / :exit action name
            strings ride the node :data so state-node can paint
            `+ <name>` (entry) / `- <name>` (exit) action pills below
            the state name (Stately graph view convention; rf2-a2b55
            replaced the prior text rows with pills)"
    (let [parsed (layout/parse-definition entry-exit-machine)
          graph  (projection/xyflow-graph parsed {} {})
          a      (node-by-id graph (layout/node-id [:a]))
          b      (node-by-id graph (layout/node-id [:b]))]
      (is (= "on-enter" (:entry (:data a))))
      (is (= "on-leave" (:exit (:data a))))
      (is (nil? (:entry (:data b))) "a state with no entry carries nil")
      (is (nil? (:exit (:data b)))))))

;; ---- elk bend-point edge routing (rf2-cz8v6, G2) -----------------------
;;
;; elk computes multi-point edge routes (start → bend… → end) that go
;; AROUND nested/parallel containers. `chart.cljs/compute-layout!` lifts
;; them into an `{edge-id [{:x :y} …]}` map (absolute coords via
;; `elk.json.edgeCoords ROOT`); the projector attaches each edge's route
;; to its `:data {:points}` so `chart.edges/transition-edge` can draw a
;; poly-path THROUGH the bends instead of a bezier shortcut that may cut
;; across a container (§1.7 of `001-Topology-Parity.md`). These pins
;; guard the projection half at the cheap JVM layer.

(deftest xyflow-graph-attaches-edge-points-by-elk-edge-id
  (testing "rf2-cz8v6 + rf2-qo5xy + rf2-r636q — `:edge-points` is keyed
            by the elk edge ids `chart.cljs/->elk-input` emits
            (`<spec-edge-id>__in` / `<spec-edge-id>__out`), and each
            xyflow edge looks up ITS OWN id: the inbound edge gets the
            `__in` route (source-state → event-node), the outbound edge
            gets the `__out` route (event-node → target-state). Each
            edge draws exactly the segment it represents."
    (let [parsed     (layout/parse-definition idle-loading)
          start      (->> (:edges parsed)
                          (filter #(= (:source %) (layout/node-id [:idle])))
                          first)
          in-route   [{:x 0 :y 0} {:x 0 :y 25} {:x 40 :y 25} {:x 40 :y 50}]
          out-route  [{:x 40 :y 50} {:x 40 :y 75} {:x 80 :y 75} {:x 80 :y 100}]
          graph      (projection/xyflow-graph
                       parsed {} {:edge-points
                                  {(str (:id start) "__in")  in-route
                                   (str (:id start) "__out") out-route}})
          in-edge    (inbound-edge-for  graph (:id start))
          out-edge   (outbound-edge-for graph (:id start))]
      (is (some? in-edge))
      (is (some? out-edge))
      (is (= in-route (:points (:data in-edge)))
          "elk's `__in` route rides on the inbound edge")
      (is (= out-route (:points (:data out-edge)))
          "elk's `__out` route rides on the outbound edge"))))

(deftest xyflow-graph-bare-canonical-key-does-not-route
  (testing "rf2-r636q — a `:edge-points` entry keyed by the BARE
            canonical edge-id (the pre-fix mis-key the producer never
            emits) routes NOTHING: neither the inbound nor the outbound
            edge picks it up, since the contract keys on the elk
            `__in` / `__out` ids. This is the failing-before/passing-
            after guard for the dead-G2 bug."
    (let [parsed   (layout/parse-definition idle-loading)
          start    (->> (:edges parsed)
                        (filter #(= (:source %) (layout/node-id [:idle])))
                        first)
          route    [{:x 0 :y 0} {:x 0 :y 50} {:x 80 :y 50} {:x 80 :y 100}]
          graph    (projection/xyflow-graph
                     parsed {} {:edge-points {(:id start) route}})
          in-edge  (inbound-edge-for  graph (:id start))
          out-edge (outbound-edge-for graph (:id start))]
      (is (nil? (:points (:data in-edge)))
          "a bare-canonical-keyed entry does not reach the inbound edge")
      (is (nil? (:points (:data out-edge)))
          "a bare-canonical-keyed entry does not reach the outbound edge"))))

(deftest xyflow-graph-edge-without-route-falls-back-to-nil-points
  (testing "rf2-cz8v6 + rf2-qo5xy + rf2-r636q — edges with no matching
            `__in` / `__out` `:edge-points` entry carry `:points nil`
            (bezier fallback) on BOTH the inbound and the outbound edge."
    (let [parsed (layout/parse-definition idle-loading)
          start  (->> (:edges parsed)
                      (filter #(= (:source %) (layout/node-id [:idle])))
                      first)
          graph  (projection/xyflow-graph
                   parsed {} {:edge-points
                              {(str (:id start) "__out") [{:x 0 :y 0} {:x 9 :y 9}]}})
          out-edges     (filter #(:outbound (:data %)) (:edges graph))
          inbound-edges (filter #(:inbound  (:data %)) (:edges graph))
          other-outs    (remove #(= (:id %) (str (:id start) "__out")) out-edges)
          other-ins     (remove #(= (:id %) (str (:id start) "__in")) inbound-edges)]
      (is (seq other-outs) "fixture has additional outbound edges")
      (is (every? #(nil? (:points (:data %))) other-outs)
          "outbound edges with no route entry carry nil points")
      (is (every? #(nil? (:points (:data %))) other-ins)
          "inbound edges with no route entry carry nil points")
      (is (nil? (:points (:data (inbound-edge-for graph (:id start)))))
          "the routed transition's inbound edge has no `__in` entry → nil"))))

(deftest xyflow-graph-no-edge-points-leaves-all-points-nil
  (testing "rf2-cz8v6 — omitting :edge-points entirely (the pre-layout
            render, before elk resolves) leaves EVERY edge's :points nil
            so the whole chart falls back to beziers"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})]
      (is (seq (:edges graph)))
      (is (every? #(nil? (:points (:data %))) (:edges graph))
          "no edge-points map → no routed edges"))))

(deftest xyflow-graph-self-loop-outbound-can-carry-points
  (testing "rf2-cz8v6 + rf2-qo5xy — the events-as-nodes paradigm
            dissolves the legacy self-loop special case: a self-
            transition's outbound edge (event-node → source-state)
            is just a regular edge with elk-routed points. The visible
            loop arc is the route around the event-node sibling."
    (let [parsed (layout/parse-definition self-loop-machine)
          self   (->> (:edges parsed)
                      (filter #(= (:source %) (:target %)))
                      first)
          graph  (projection/xyflow-graph
                   parsed {} {:edge-points
                              {(str (:id self) "__out")
                               [{:x 0 :y 0} {:x 5 :y 5} {:x 10 :y 0}]}})
          out-edge (outbound-edge-for graph (:id self))]
      (is (some? self) "fixture has a self-transition")
      (is (some? out-edge) "outbound edge survives projection")
      ;; The route attaches to the outbound edge (the visible loop
      ;; arc) — no longer dropped as in the legacy single-edge model.
      (is (= [{:x 0 :y 0} {:x 5 :y 5} {:x 10 :y 0}]
             (:points (:data out-edge)))))))

(deftest xyflow-graph-routed-edge-keeps-active-highlight
  (testing "rf2-cz8v6 + rf2-qo5xy — G1's active-edge styling survives
            routing through the event-node: an outbound edge whose
            target is active carries both `:active true` and the elk
            route on its `:data`."
    (let [parsed  (layout/parse-definition idle-loading)
          hi      (layout/node-id [:loading])
          start   (->> (:edges parsed)
                       (filter #(= (:source %) (layout/node-id [:idle])))
                       first)
          route   [{:x 0 :y 0} {:x 0 :y 40} {:x 60 :y 40}]
          graph   (projection/xyflow-graph
                    parsed {} {:highlight-id hi
                               :edge-points  {(str (:id start) "__out") route}})
          out-edge (outbound-edge-for graph (:id start))]
      (is (true? (:active (:data out-edge))))
      (is (= route (:points (:data out-edge)))))))

;; ---- fired-this-epoch edge highlight (rf2-qeemm, G3) -------------------
;;
;; The Xray inspector resolves which edges fired THIS epoch
;; (`extract-fired-edge-ids`, B7 — emits CANONICAL machines-viz edge-ids)
;; and threads them as `:fired-edge-ids` (a SET) into the projector, which
;; marks each matching edge `:fired`. The edge component then paints the
;; FIRED treatment (emphasised + animated stroke + `data-fired`). These
;; JVM pins guard the projection half; the DOM suite pins the rendered
;; `data-fired` attr. The match is by EDGE-ID (not endpoint node-ids like
;; `:focused`) so every traversed arm lights up.

(deftest xyflow-graph-marks-fired-event-node-and-its-edges
  (testing "rf2-qeemm + rf2-qo5xy — a parsed-edge id in :fired-edge-ids
            marks BOTH the inbound + outbound edges AND the event-node
            for that transition with `:fired true` (the whole
            event-as-nodes structural fork lights up). Other edges /
            event-nodes stay `:fired false`."
    (let [parsed   (layout/parse-definition idle-loading)
          start    (->> (:edges parsed)
                        (filter #(= (:source %) (layout/node-id [:idle])))
                        first)
          graph    (projection/xyflow-graph
                     parsed {} {:fired-edge-ids #{(:id start)}})
          ev-node  (event-node-for graph (:id start))
          in-edge  (inbound-edge-for  graph (:id start))
          out-edge (outbound-edge-for graph (:id start))
          other-ev (remove #(= (:id %) (:id ev-node))
                           (filter #(= "rf2-event" (:type %)) (:nodes graph)))
          other-ed (remove #(#{(:id in-edge) (:id out-edge)} (:id %))
                           (:edges graph))]
      (is (true? (:fired (:data ev-node))))
      (is (true? (:fired (:data in-edge))))
      (is (true? (:fired (:data out-edge))))
      (is (every? #(false? (:fired (:data %))) other-ev))
      (is (every? #(false? (:fired (:data %))) other-ed)))))

(deftest xyflow-graph-no-fired-edge-ids-leaves-all-unfired
  (testing "rf2-qeemm — omitting :fired-edge-ids (the viewer / Story path,
            or a non-fired epoch) leaves EVERY edge `:fired false`"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})]
      (is (seq (:edges graph)))
      (is (every? #(false? (:fired (:data %))) (:edges graph))
          "no fired set → no fired edges"))))

(deftest xyflow-graph-fired-collects-multiple-event-nodes
  (testing "rf2-qeemm + rf2-qo5xy — a set with N fired parsed-edge ids
            marks N event-nodes + their inbound/outbound edges as
            fired. An epoch with two traversed arms lights two
            event-node forks."
    (let [parsed (layout/parse-definition idle-loading)
          start  (->> (:edges parsed)
                      (filter #(= (:source %) (layout/node-id [:idle])))
                      first)
          always (->> (:edges parsed)
                      (filter #(= (:target %) (layout/node-id [:ready])))
                      first)
          ids    #{(:id start) (:id always)}
          graph  (projection/xyflow-graph parsed {} {:fired-edge-ids ids})
          fired-ev-nodes (set (map :id (filter #(and (= "rf2-event" (:type %))
                                                     (:fired (:data %)))
                                               (:nodes graph))))]
      (is (= #{(str "event__" (:id start)) (str "event__" (:id always))}
             fired-ev-nodes)
          "exactly the two event-nodes for the fired ids light up"))))

(deftest xyflow-graph-fired-marker-colour-distinct
  (testing "rf2-qeemm — a fired edge's arrowhead colour differs from a
            non-fired edge's (the FIRED hue is distinct so a traversed
            arm reads as 'what just happened')"
    (let [parsed   (layout/parse-definition idle-loading)
          start    (->> (:edges parsed)
                        (filter #(= (:source %) (layout/node-id [:idle])))
                        first)
          graph    (projection/xyflow-graph
                     parsed {} {:fired-edge-ids #{(:id start)}})
          fired-e  (edge-by-id graph (:id start))
          plain-e  (first (remove #(or (= (:id %) (:id start))
                                       (:entry (:data %)))
                                  (:edges graph)))]
      (is (some? plain-e) "fixture has a non-fired transition edge")
      (is (not= (:color (:markerEnd fired-e))
                (:color (:markerEnd plain-e)))
          "fired vs non-fired arrowheads are distinct colours"))))

(deftest xyflow-graph-fired-coexists-with-active-and-routing
  (testing "rf2-qeemm + rf2-qo5xy — :fired + :active + :points coexist
            on the outbound edge of a fired transition that also
            touches an active node and carries an elk route."
    (let [parsed  (layout/parse-definition idle-loading)
          hi      (layout/node-id [:loading])
          start   (->> (:edges parsed)
                       (filter #(= (:source %) (layout/node-id [:idle])))
                       first)
          route   [{:x 0 :y 0} {:x 0 :y 40} {:x 60 :y 40}]
          graph   (projection/xyflow-graph
                    parsed {} {:highlight-id   hi
                               :edge-points    {(str (:id start) "__out") route}
                               :fired-edge-ids #{(:id start)}})
          out-edge (outbound-edge-for graph (:id start))]
      (is (true? (:fired  (:data out-edge))))
      (is (true? (:active (:data out-edge))))
      (is (= route (:points (:data out-edge)))))))

(deftest xyflow-graph-fired-is-parsed-edge-id-not-endpoint-matched
  (testing "rf2-qeemm + rf2-qo5xy — :fired matches the parsed-edge id
            directly (via the event-node bridge), NOT endpoint node-ids
            like :focused. Passing only :fired-edge-ids (no from/to
            lens) lights the fired forks while no edge is :focused."
    (let [parsed  (layout/parse-definition idle-loading)
          start   (->> (:edges parsed)
                       (filter #(= (:source %) (layout/node-id [:idle])))
                       first)
          graph   (projection/xyflow-graph
                    parsed {} {:fired-edge-ids #{(:id start)}})
          out-edge (outbound-edge-for graph (:id start))]
      (is (true? (:fired (:data out-edge))))
      (is (every? #(false? (:focused (:data %))) (:edges graph))))))

(deftest xyflow-graph-entry-edges-carry-fired-false
  (testing "rf2-qeemm — initial entry edges (initial-marker → state)
            keep the every-edge :data shape whole: they carry
            `:fired false` (never fired). Note: these are distinct
            from the rf2-qo5xy state→event-node→state edges. The
            entry-edge is the marker→state hop."
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {:fired-edge-ids #{"anything"}})
          entry  (first (filter #(:entry (:data %)) (:edges graph)))]
      (is (some? entry) "fixture has an entry edge")
      (is (false? (:fired (:data entry)))
          "entry edges are never fired"))))

;; ---- compound-endpoint edges (rf2-shv82, Issue 1) -----------------------
;;
;; A parent-level transition like `:active → :disconnected` (declared on
;; the compound `:active`, inherited by every leaf inside) projects with
;; the compound's node-id as one endpoint. Pre-rf2-shv82 the chart's
;; compound-node + parallel-region-node had no `<Handle>` children, so
;; xyflow's `isNodeInitialized` returned false, `getEdgePosition` returned
;; null, and the edge was silently dropped from the DOM (the 5-layer
;; probe in the rf2-shv82 bead proves all 4 such edges survive the
;; projector + ELK but vanish before render). The fix is to add invisible
;; handles to the container nodes (chart.nodes/compound-node +
;; chart.nodes.parallel-region-node) — these JVM pins guard the projector
;; half (every compound-endpoint edge that the parser emits MUST survive
;; the projection; the renderer half is pinned by `chart_dom_cljs_test`'s
;; `data-edge-count == data-edge-count-projected` parity gate).

(def ^:private parent-level-transition-machine
  "A machine that mirrors the rf2-shv82 reproducer (testdeck
  `:ws/connection`) at minimum size: a compound parent owns a parent-
  level `:on` transition every leaf inherits + a self-transition on the
  compound itself, plus an inbound transition from a sibling top-level
  state. All four edge shapes have the compound as an endpoint."
  {:initial :idle
   :states  {:idle    {:on {:connect :active}}
             :active  {:initial :connecting
                       ;; parent-level inherited transitions
                       :on      {:disconnect :idle
                                 :send {}}   ;; compound self-transition
                       :states  {:connecting {:on {:done :connected}}
                                 :connected  {}}}
             :failed  {:on {:retry :active}}}})

(deftest xyflow-graph-emits-edge-with-compound-as-target
  (testing "rf2-shv82 (Issue 1) + rf2-qo5xy — `:idle --connect--> :active`
            mints an event-node + outbound edge whose target is the
            COMPOUND `:active` node-id; the projector must not drop it."
    (let [parsed   (layout/parse-definition parent-level-transition-machine)
          graph    (projection/xyflow-graph parsed {} {})
          active-id (layout/node-id [:active])
          connect  (first (filter #(= :connect (:event %)) (:edges parsed)))
          ev-node  (event-node-for graph (:id connect))
          out-edge (outbound-edge-for graph (:id connect))
          in-edge  (inbound-edge-for  graph (:id connect))]
      (is (some? ev-node))
      (is (= active-id (:target out-edge))
          "outbound edge targets the compound :active")
      (is (= (layout/node-id [:idle]) (:source in-edge))
          "inbound edge sources from :idle"))))

(deftest xyflow-graph-emits-edge-with-compound-as-source
  (testing "rf2-shv82 (Issue 1) + rf2-qo5xy — `:active --disconnect--> :idle`
            mints an inbound edge whose source is the COMPOUND `:active`."
    (let [parsed (layout/parse-definition parent-level-transition-machine)
          graph  (projection/xyflow-graph parsed {} {})
          active-id (layout/node-id [:active])
          disc   (first (filter #(= :disconnect (:event %)) (:edges parsed)))
          in-edge (inbound-edge-for graph (:id disc))]
      (is (some? in-edge))
      (is (= active-id (:source in-edge))))))

(deftest xyflow-graph-emits-self-routing-on-compound
  (testing "rf2-shv82 (Issue 1) + rf2-qo5xy — `:active --send--> {}`
            (the compound's internal self-transition: omit :target,
            just declare :action) projects as an event-node beside
            the compound with an inbound edge from the compound but
            NO outbound (internal transition convention)."
    (let [parsed (layout/parse-definition parent-level-transition-machine)
          graph  (projection/xyflow-graph parsed {} {})
          active-id (layout/node-id [:active])
          send-e (first (filter #(= :send (:event %)) (:edges parsed)))
          ev-node  (event-node-for     graph (:id send-e))
          in-edge  (inbound-edge-for   graph (:id send-e))
          out-edge (outbound-edge-for  graph (:id send-e))]
      (is (true? (:internal? send-e))
          "fixture sanity: :send is an internal self-transition")
      (is (some? ev-node))
      (is (true? (:internal (:data ev-node))))
      (is (some? in-edge)  "inbound edge from compound exists")
      (is (= active-id (:source in-edge))
          "inbound source == :active compound")
      (is (nil? out-edge)
          "internal transition emits no outbound edge"))))

(deftest xyflow-graph-emits-compound-endpoint-event-nodes-survive-projection
  (testing "rf2-shv82 (Issue 1) + rf2-qo5xy — every parsed edge mints
            an event-node in the projected graph; no compound-endpoint
            edge is silently dropped at the projection layer."
    (let [parsed (layout/parse-definition parent-level-transition-machine)
          graph  (projection/xyflow-graph parsed {} {})
          parsed-ids (set (map :id (:edges parsed)))
          ev-node-ids (set (map :id (filter #(= "rf2-event" (:type %))
                                            (:nodes graph))))
          expected (set (map #(str "event__" %) parsed-ids))]
      (is (= expected ev-node-ids)
          "every parsed edge id has a matching event-node"))))

;; ---- self-loop fan superseded by multi-event collapse (rf2-shv82 → rf2-j10sm)
;;
;; rf2-shv82 (Issue 2) introduced a per-source self-loop perimeter fan:
;; N self-loops on `:disconnected` rotated around the node's perimeter
;; so their labels did not stack. rf2-j10sm (Phase 2, B) supersedes the
;; fan with the xstate/Stately multi-event collapse: N self-loops share
;; ONE loop arc with N vertically-stacked labels (one event per row).
;; That collapse is the right convention; the fan is preserved in
;; `chart.edges/edge-path` for direct callers / future explicit-fan
;; affordances, but the projection layer no longer fans — every
;; self-loop carries `:loopIndex 0` (the historical single-self-loop
;; slot) and rides the multi-event sibling stack via `:siblingIndex` +
;; `:siblingCount` instead.

(def ^:private multi-self-loop-machine
  "Three self-loops on `:idle` (mirrors the testdeck `:disconnected`
  shape: 3 distinct events on one node, all self-transitions)."
  {:initial :idle
   :states  {:idle {:on {:arm    {:action :arm-it}
                         :disarm {:action :disarm-it}
                         :clear  {:action :clear-it}}}}})

(deftest xyflow-graph-multi-self-events-each-get-own-event-node
  (testing "rf2-qo5xy — multiple self-events on one source each project
            as their own event-node (events-as-nodes paradigm). The
            legacy sibling-collapse (one arc, N stacked labels) is
            superseded — each event is its own first-class box, and
            the action attribution rides on the event-node itself."
    (let [parsed (layout/parse-definition multi-self-loop-machine)
          graph  (projection/xyflow-graph parsed {} {})
          ev-nodes (filter #(= "rf2-event" (:type %)) (:nodes graph))
          on-idle  (filter (fn [e] (let [in (inbound-edge-for graph
                                                              (subs (:id e)
                                                                    (count "event__")))]
                                     (= (:source in) (layout/node-id [:idle]))))
                           ev-nodes)]
      (is (= 3 (count on-idle))
          "fixture has 3 events on :idle → 3 event-nodes")
      (is (= #{:arm :disarm :clear}
             (set (map #(:eventId (:data %)) on-idle))))
      ;; The fixture's events ARE all internal (action only, no target),
      ;; so each event-node anchors ONE inbound edge and NO outbound:
      ;; 3 inbound, 0 outbound — the Stately convention for internal
      ;; handlers ("runs an action and we hang here").
      (let [inbound  (filter #(:inbound  (:data %)) (:edges graph))
            outbound (filter #(:outbound (:data %)) (:edges graph))]
        (is (= 3 (count inbound))
            "3 inbound edges (one per internal self-event)")
        (is (= 0 (count outbound))
            "no outbound edges — these are internal transitions")))))

(deftest xyflow-graph-single-event-emits-one-event-node
  (testing "rf2-qo5xy — a transition with exactly one event maps to one
            event-node, one inbound edge, one outbound edge — the
            paradigm's minimum unit."
    (let [parsed   (layout/parse-definition self-loop-machine)
          graph    (projection/xyflow-graph parsed {} {})
          ping     (first (filter #(= (:source %) (:target %))
                                  (:edges parsed)))
          ev-node  (event-node-for graph (:id ping))
          in-edge  (inbound-edge-for  graph (:id ping))
          out-edge (outbound-edge-for graph (:id ping))]
      (is (some? ping))
      (is (some? ev-node))
      (is (some? in-edge))
      (is (some? out-edge)))))

(deftest xyflow-graph-multiple-events-on-same-source-target-pair
  (testing "rf2-qo5xy — the events-as-nodes paradigm dissolves the
            multi-event same-`[source target]` collapse: each event
            becomes its own event-node, so two events both
            transitioning A → B emit TWO event-nodes (with two
            inbound + two outbound edges)."
    (let [m {:initial :a
             :states  {:a {:on {:go-fast :b :go-slow :b}}
                       :b {}}}
          parsed (layout/parse-definition m)
          graph  (projection/xyflow-graph parsed {} {})
          ev-nodes (filter #(= "rf2-event" (:type %)) (:nodes graph))]
      (is (= 2 (count ev-nodes))
          "two events on :a → two event-nodes (no collapse)")
      (is (= #{:go-fast :go-slow}
             (set (map #(:eventId (:data %)) ev-nodes)))))))

(deftest xyflow-graph-each-parsed-edge-yields-one-event-node
  (testing "rf2-qo5xy — the projection invariant: parsed-edges count
            equals event-nodes count. No collapse, no duplication."
    (doseq [m [idle-loading compound-machine self-loop-machine
               wildcard-machine machine-level-on-machine]]
      (let [parsed   (layout/parse-definition m)
            graph    (projection/xyflow-graph parsed {} {})
            ev-nodes (filter #(= "rf2-event" (:type %)) (:nodes graph))]
        (is (= (count (:edges parsed)) (count ev-nodes))
            (str "fixture " m " mismatch"))))))

;; ---- cross-hierarchy label placement (rf2-shv82, Issue 3) --------------
;;
;; A cross-hierarchy edge (source and target in different parent
;; containers) has a routed midpoint that can land far from where the
;; user perceives the edge to originate. The projector flags it so the
;; renderer can anchor the label at the source-side first bend point
;; instead.

(def ^:private cross-hierarchy-machine
  "A compound with an inner state that crosses out to a top-level
  sibling (mirrors testdeck `:authenticating → :failed`)."
  {:initial :outer
   :states  {:outer  {:initial :inner
                      :states  {:inner {:on {:escape :sibling}}}}
             :sibling {}}})

(deftest xyflow-graph-flags-cross-hierarchy-edge
  (testing "rf2-shv82 (Issue 3) + rf2-qo5xy — an outbound edge whose
            source event-node and target state sit in DIFFERENT parent
            containers gets :crossHierarchy true."
    (let [parsed (layout/parse-definition cross-hierarchy-machine)
          graph  (projection/xyflow-graph parsed {} {})
          escape (first (filter #(= :escape (:event %)) (:edges parsed)))
          out-edge (outbound-edge-for graph (:id escape))]
      (is (some? out-edge))
      (is (true? (:crossHierarchy (:data out-edge)))
          "inner→sibling escapes the :outer container"))))

(deftest xyflow-graph-same-parent-edge-not-cross-hierarchy
  (testing "rf2-shv82 (Issue 3) + rf2-qo5xy — an outbound edge between
            two siblings under the SAME parent is NOT cross-hierarchy."
    (let [parsed (layout/parse-definition compound-machine)
          graph  (projection/xyflow-graph parsed {} {})
          checkout (first (filter #(= :checkout (:event %)) (:edges parsed)))
          out-edge (outbound-edge-for graph (:id checkout))]
      (is (some? out-edge))
      (is (false? (:crossHierarchy (:data out-edge)))))))

(deftest xyflow-graph-flat-machine-no-cross-hierarchy
  (testing "rf2-shv82 (Issue 3) + rf2-qo5xy — a flat machine has no
            containers, so no outbound edge is cross-hierarchy."
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          out-edges (filter #(:outbound (:data %)) (:edges graph))]
      (is (seq out-edges))
      (is (every? #(false? (:crossHierarchy (:data %))) out-edges)))))

(deftest xyflow-graph-self-routing-not-cross-hierarchy
  (testing "rf2-shv82 (Issue 3) + rf2-qo5xy — a self-routing transition
            (source == target) is never cross-hierarchy regardless of
            container nesting."
    (let [parsed (layout/parse-definition self-loop-machine)
          graph  (projection/xyflow-graph parsed {} {})
          ping   (first (filter #(= (:source %) (:target %)) (:edges parsed)))
          out-edge (outbound-edge-for graph (:id ping))]
      (is (some? out-edge))
      (is (false? (:crossHierarchy (:data out-edge)))))))

(deftest xyflow-graph-entry-edges-carry-cross-hierarchy-false
  (testing "rf2-shv82 — entry edges keep the every-edge :data shape
            whole: they carry :crossHierarchy false + :loopIndex nil"
    (let [parsed (layout/parse-definition cross-hierarchy-machine)
          graph  (projection/xyflow-graph parsed {} {})
          entry  (first (filter #(:entry (:data %)) (:edges graph)))]
      (is (some? entry))
      (is (false? (:crossHierarchy (:data entry))))
      (is (nil?   (:loopIndex (:data entry))))
      ;; rf2-j10sm (Phase 2, B) — entry edges are always singleton
      ;; (marker → state) so they ride the every-edge :data shape with
      ;; siblingIndex 0 + siblingCount 1.
      (is (= 0 (:siblingIndex (:data entry))))
      (is (= 1 (:siblingCount (:data entry)))))))
