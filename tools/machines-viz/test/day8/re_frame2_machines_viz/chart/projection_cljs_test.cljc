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

;; ---- on-chart edge-click wiring (rf2-u422r) ----------------------------
;;
;; The on-chart machine simulator clicks a transition edge to send its
;; event into the hermetic sim engine. The projector threads the host's
;; `:on-edge-click` callback onto every edge `:data {:onClick}` + carries
;; the raw fireable `:eventId` / `:fromPath` / `:toPath` so the edge
;; component can hand the host the originating transition. These JVM pins
;; guard that wiring; the edge component's click behaviour is pinned at
;; the DOM layer (chart_dom).

(deftest xyflow-graph-edge-carries-fireable-event-id
  (testing "rf2-u422r — a plain `:on` transition edge carries its raw
            fireable `:eventId` (the keyword the sim sends) + the
            from/to paths on its `:data`"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          start  (first (filter #(= (:source %) (layout/node-id [:idle]))
                                (:edges graph)))]
      (is (some? start) "fixture has the idle→loading :start edge")
      (is (= :start (:eventId (:data start)))
          "the raw fireable event keyword rides on the edge data")
      (is (= [:idle] (:fromPath (:data start))))
      (is (= [:loading] (:toPath (:data start)))))))

(deftest xyflow-graph-after-and-always-edges-are-not-fireable
  (testing "rf2-u422r — `:after`-timer + `:always` eventless edges fire
            automatically inside the engine, so they carry a nil
            `:eventId` (the host filters them out — they are not
            user-clickable on the chart)"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          after  (first (filter #(:afterMs (:data %)) (:edges graph)))
          ;; the `:always` edge's label segment starts with "always"
          ;; (the guarded fixture renders "always [loaded?]").
          always (first (filter #(re-find #"^always"
                                          (or (:eventLabel (:data %)) ""))
                                (:edges graph)))]
      (is (some? after) "fixture has an :after edge")
      (is (nil? (:eventId (:data after)))
          "the :after edge is not user-fireable")
      (is (some? always) "fixture has an :always edge")
      (is (nil? (:eventId (:data always)))
          "the :always edge is not user-fireable"))))

(deftest xyflow-graph-threads-on-edge-click-onto-every-edge
  (testing "rf2-u422r — the host's `:on-edge-click` callback threads onto
            EVERY edge's `:data {:onClick}` (the edge component decides
            clickability from the presence of BOTH the callback + a
            fireable event-id)"
    (let [parsed   (layout/parse-definition idle-loading)
          captured (atom nil)
          cb       (fn [m] (reset! captured m))
          graph    (projection/xyflow-graph parsed {} {:on-edge-click cb})]
      (is (seq (:edges graph)))
      (is (every? #(= cb (:onClick (:data %))) (:edges graph))
          "every edge carries the same on-edge-click callback"))))

(deftest xyflow-graph-omits-on-click-when-no-callback
  (testing "rf2-u422r — omitting `:on-edge-click` leaves `:onClick` nil
            so the edge label stays inert (no wiring)"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})]
      (is (every? #(nil? (:onClick (:data %))) (:edges graph))))))

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

(deftest xyflow-graph-flags-self-transition
  (testing "rf2-54s5a — a source==target edge carries :data {:selfLoop true}"
    (let [parsed   (layout/parse-definition self-loop-machine)
          graph    (projection/xyflow-graph parsed {} {})
          self     (first (filter #(= (:source %) (:target %)) (:edges graph)))
          non-self (first (filter #(not= (:source %) (:target %)) (:edges graph)))]
      (is (some? self) "fixture has a self-transition")
      (is (true?  (:selfLoop (:data self))))
      (is (false? (:selfLoop (:data non-self)))))))

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

(deftest elk-children-nests-compound-substates
  (testing "rf2-54s5a — a compound parent nests its substates as elk
            :children (so they lay out inside the container)"
    (let [parsed   (layout/parse-definition compound-machine)
          children (projection/->elk-children parsed)
          by-id    (into {} (map (juxt :id identity) children))
          authed   (get by-id (layout/node-id [:authenticated]))]
      (is (some? authed) "compound parent is a top-level elk child")
      (is (= 2 (count (:children authed)))
          "browsing + paying nest inside"))))

;; ---- self-transitions / wildcard / machine-level :on (rf2-ee38b.21) ----

(deftest xyflow-graph-same-state-projects-self-loop
  (testing "rf2-ee38b.21 — `:target :same-state` projects a true
            self-loop (source == target, :selfLoop true) into a REAL
            node — not a dangling edge to a phantom :same-state node"
    (let [parsed   (layout/parse-definition same-state-machine)
          graph    (projection/xyflow-graph parsed {} {})
          node-ids (set (map :id (:nodes graph)))
          ;; the only non-entry edge is the :ping self-transition
          ping     (first (remove #(:entry (:data %)) (:edges graph)))]
      (is (some? ping))
      (is (= (:source ping) (:target ping)) "source == target")
      (is (true? (:selfLoop (:data ping))))
      (is (contains? node-ids (:target ping))
          "target is a real node, not a phantom :same-state"))))

(deftest xyflow-graph-internal-self-transition-flagged
  (testing "rf2-ee38b.21 — an internal self-transition (omit :target)
            projects a self-loop flagged `:internal true`"
    (let [parsed (layout/parse-definition internal-self-machine)
          graph  (projection/xyflow-graph parsed {} {})
          tick   (first (remove #(:entry (:data %)) (:edges graph)))]
      (is (some? tick))
      (is (= (:source tick) (:target tick)))
      (is (true? (:selfLoop (:data tick))))
      (is (true? (:internal (:data tick)))))))

(deftest xyflow-graph-wildcard-not-fireable
  (testing "rf2-ee38b.21 — the `:*` wildcard edge carries a NIL :eventId
            so the on-chart sim can't dispatch a literal `[:* ...]`
            (same inert posture as :after / :always); the real `:start`
            event stays fireable"
    (let [parsed (layout/parse-definition wildcard-machine)
          graph  (projection/xyflow-graph parsed {} {})
          wild   (first (filter #(re-find #"\(any\)"
                                          (or (:eventLabel (:data %)) ""))
                                (:edges graph)))
          start  (first (filter #(= :start (:eventId (:data %)))
                                (:edges graph)))]
      (is (some? wild) "the wildcard edge is present")
      (is (nil? (:eventId (:data wild))) "wildcard is NOT fireable")
      (is (some? start) "the real :start edge stays fireable")
      (is (= :start (:eventId (:data start)))))))

(deftest xyflow-graph-machine-level-on-flagged
  (testing "rf2-ee38b.21 — machine-level (top-level) :on fallback edges
            project with `:machineLevel true` from every leaf"
    (let [parsed (layout/parse-definition machine-level-on-machine)
          graph  (projection/xyflow-graph parsed {} {})
          logout (filter #(= :logout (:eventId (:data %))) (:edges graph))]
      (is (= 2 (count logout)) "one inherited edge per leaf")
      (is (every? #(true? (:machineLevel (:data %))) logout)))))

(deftest xyflow-graph-state-only-transitions-not-machine-level
  (testing "rf2-ee38b.21 — a normal state-local transition carries
            `:machineLevel false`"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          start  (first (filter #(= :start (:eventId (:data %))) (:edges graph)))]
      (is (some? start))
      (is (false? (:machineLevel (:data start))))
      (is (false? (:internal (:data start)))))))

;; ---- entry / exit state actions (rf2-ee38b.21) -------------------------

(deftest xyflow-graph-threads-entry-exit-onto-node-data
  (testing "rf2-ee38b.21 — :entry / :exit action name strings ride the
            node :data so state-node can paint `entry / <name>` rows"
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

(deftest xyflow-graph-attaches-edge-points-to-matching-edge
  (testing "rf2-cz8v6 (THE G2 CAPABILITY) — an edge whose id has an
            entry in :edge-points carries that route on its
            `:data {:points}` so the edge renders THROUGH the bend
            points (not a straight/bezier shortcut)"
    (let [parsed   (layout/parse-definition idle-loading)
          ;; the idle→loading :start edge
          start    (->> (:edges parsed)
                        (filter #(= (:source %) (layout/node-id [:idle])))
                        first)
          route    [{:x 0 :y 0} {:x 0 :y 50} {:x 80 :y 50} {:x 80 :y 100}]
          graph    (projection/xyflow-graph
                     parsed {} {:edge-points {(:id start) route}})
          start-e  (edge-by-id graph (:id start))]
      (is (some? start-e))
      (is (= route (:points (:data start-e)))
          "elk's routed bend-points ride on the edge data"))))

(deftest xyflow-graph-edge-without-route-falls-back-to-nil-points
  (testing "rf2-cz8v6 — a simple edge with no :edge-points entry carries
            `:points nil`, so the edge component falls back to the
            bezier path (the no-bend-point case)"
    (let [parsed (layout/parse-definition idle-loading)
          ;; supply a route for ONE edge only; every other edge is bare
          start  (->> (:edges parsed)
                      (filter #(= (:source %) (layout/node-id [:idle])))
                      first)
          graph  (projection/xyflow-graph
                   parsed {} {:edge-points {(:id start) [{:x 0 :y 0} {:x 9 :y 9}]}})
          others (remove #(= (:id %) (:id start))
                         (remove #(:entry (:data %)) (:edges graph)))]
      (is (seq others) "fixture has more than one transition edge")
      (is (every? #(nil? (:points (:data %))) others)
          "edges with no route entry carry nil points (bezier fallback)"))))

(deftest xyflow-graph-no-edge-points-leaves-all-points-nil
  (testing "rf2-cz8v6 — omitting :edge-points entirely (the pre-layout
            render, before elk resolves) leaves EVERY edge's :points nil
            so the whole chart falls back to beziers"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})]
      (is (seq (:edges graph)))
      (is (every? #(nil? (:points (:data %))) (:edges graph))
          "no edge-points map → no routed edges"))))

(deftest xyflow-graph-self-loop-never-carries-points
  (testing "rf2-cz8v6 — a self-loop keeps its dedicated loop path: even
            when elk emits a route for the self-edge id, the projector
            drops it so :points stays nil (self-loops unchanged)"
    (let [parsed (layout/parse-definition self-loop-machine)
          self   (->> (:edges parsed)
                      (filter #(= (:source %) (:target %)))
                      first)
          graph  (projection/xyflow-graph
                   parsed {} {:edge-points {(:id self) [{:x 0 :y 0} {:x 5 :y 5}]}})
          self-e (edge-by-id graph (:id self))]
      (is (some? self) "fixture has a self-transition")
      (is (true? (:selfLoop (:data self-e))))
      (is (nil? (:points (:data self-e)))
          "a self-loop never carries elk points (it keeps its loop path)"))))

(deftest xyflow-graph-routed-edge-keeps-active-highlight
  (testing "rf2-cz8v6 — G1's active-edge styling survives routing: an
            edge that BOTH has a route AND touches the highlighted node
            is `:active` (highlighted) AND carries its `:points`"
    (let [parsed  (layout/parse-definition idle-loading)
          hi      (layout/node-id [:loading])
          start   (->> (:edges parsed)
                       (filter #(= (:source %) (layout/node-id [:idle])))
                       first)
          route   [{:x 0 :y 0} {:x 0 :y 40} {:x 60 :y 40}]
          graph   (projection/xyflow-graph
                    parsed {} {:highlight-id hi
                               :edge-points  {(:id start) route}})
          start-e (edge-by-id graph (:id start))]
      (is (true? (:active (:data start-e)))
          "the idle→loading edge still highlights (target is active)")
      (is (= route (:points (:data start-e)))
          "and still carries its elk route — highlight + routing coexist"))))

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

(deftest xyflow-graph-marks-fired-edge
  (testing "rf2-qeemm (THE G3 CAPABILITY) — an edge whose id ∈
            :fired-edge-ids gets `:fired true`; every other edge stays
            `:fired false`"
    (let [parsed   (layout/parse-definition idle-loading)
          start    (->> (:edges parsed)
                        (filter #(= (:source %) (layout/node-id [:idle])))
                        first)
          graph    (projection/xyflow-graph
                     parsed {} {:fired-edge-ids #{(:id start)}})
          start-e  (edge-by-id graph (:id start))
          others   (remove #(= (:id %) (:id start)) (:edges graph))]
      (is (some? start) "fixture has the idle→loading edge")
      (is (true? (:fired (:data start-e)))
          "the fired edge is marked :fired")
      (is (every? #(false? (:fired (:data %))) others)
          "no other edge is marked fired (including entry edges)"))))

(deftest xyflow-graph-no-fired-edge-ids-leaves-all-unfired
  (testing "rf2-qeemm — omitting :fired-edge-ids (the viewer / Story path,
            or a non-fired epoch) leaves EVERY edge `:fired false`"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})]
      (is (seq (:edges graph)))
      (is (every? #(false? (:fired (:data %))) (:edges graph))
          "no fired set → no fired edges"))))

(deftest xyflow-graph-fired-collects-multiple-edges
  (testing "rf2-qeemm — a set with N fired ids marks ALL N edges :fired
            (an epoch with several microsteps lights every traversed arm)"
    (let [parsed (layout/parse-definition idle-loading)
          start  (->> (:edges parsed)
                      (filter #(= (:source %) (layout/node-id [:idle])))
                      first)
          ;; the :always loading→ready edge
          always (->> (:edges parsed)
                      (filter #(= (:target %) (layout/node-id [:ready])))
                      first)
          ids    #{(:id start) (:id always)}
          graph  (projection/xyflow-graph parsed {} {:fired-edge-ids ids})
          fired  (set (map :id (filter #(:fired (:data %)) (:edges graph))))]
      (is (some? start) "fixture has the :start edge")
      (is (some? always) "fixture has the :always edge")
      (is (= ids fired)
          "exactly the two fired ids are marked, no more no fewer"))))

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
  (testing "rf2-qeemm — :fired coexists with G1 :active + G2 routing on
            the SAME edge: a fired edge that also touches an active node
            AND carries an elk route keeps all three"
    (let [parsed  (layout/parse-definition idle-loading)
          hi      (layout/node-id [:loading])
          start   (->> (:edges parsed)
                       (filter #(= (:source %) (layout/node-id [:idle])))
                       first)
          route   [{:x 0 :y 0} {:x 0 :y 40} {:x 60 :y 40}]
          graph   (projection/xyflow-graph
                    parsed {} {:highlight-id   hi
                               :edge-points    {(:id start) route}
                               :fired-edge-ids #{(:id start)}})
          start-e (edge-by-id graph (:id start))]
      (is (true? (:fired  (:data start-e))) "edge is fired")
      (is (true? (:active (:data start-e))) "edge is still active (G1)")
      (is (= route (:points (:data start-e))) "edge still carries its route (G2)"))))

(deftest xyflow-graph-fired-is-edge-id-not-endpoint-matched
  (testing "rf2-qeemm — :fired matches the EDGE id directly, NOT endpoint
            node-ids like :focused. Passing only :fired-edge-ids (no
            from/to lens) lights the fired edge while NO edge is :focused"
    (let [parsed  (layout/parse-definition idle-loading)
          start   (->> (:edges parsed)
                       (filter #(= (:source %) (layout/node-id [:idle])))
                       first)
          graph   (projection/xyflow-graph
                    parsed {} {:fired-edge-ids #{(:id start)}})
          start-e (edge-by-id graph (:id start))]
      (is (true? (:fired (:data start-e))) "the fired edge lights")
      (is (every? #(false? (:focused (:data %))) (:edges graph))
          "no from/to lens → no edge is focused (fired is independent)"))))

(deftest xyflow-graph-entry-edges-carry-fired-false
  (testing "rf2-qeemm — initial entry edges keep the every-edge :data
            shape whole: they carry `:fired false` (never fired)"
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
  (testing "rf2-shv82 (Issue 1) — `:idle --connect--> :active` mints an
            edge whose target is the COMPOUND `:active`'s node-id; the
            projector must NOT drop or filter it"
    (let [parsed   (layout/parse-definition parent-level-transition-machine)
          graph    (projection/xyflow-graph parsed {} {})
          active-id (layout/node-id [:active])
          edge     (first (filter #(and (= :connect (:eventId (:data %)))
                                        (= (:target %) active-id))
                                  (:edges graph)))]
      (is (some? edge) "the compound-as-target edge survives projection")
      (is (= (layout/node-id [:idle]) (:source edge))))))

(deftest xyflow-graph-emits-edge-with-compound-as-source
  (testing "rf2-shv82 (Issue 1) — `:active --disconnect--> :idle` mints
            an edge whose source is the COMPOUND `:active`'s node-id"
    (let [parsed (layout/parse-definition parent-level-transition-machine)
          graph  (projection/xyflow-graph parsed {} {})
          active-id (layout/node-id [:active])
          edge   (first (filter #(and (= :disconnect (:eventId (:data %)))
                                      (= (:source %) active-id))
                                (:edges graph)))]
      (is (some? edge) "the compound-as-source edge survives projection"))))

(deftest xyflow-graph-emits-self-loop-on-compound
  (testing "rf2-shv82 (Issue 1) — `:active --send--> :active` (a
            compound self-transition) projects as a self-loop on the
            compound node, flagged :selfLoop"
    (let [parsed (layout/parse-definition parent-level-transition-machine)
          graph  (projection/xyflow-graph parsed {} {})
          active-id (layout/node-id [:active])
          edge   (first (filter #(and (= (:source %) active-id)
                                      (= (:target %) active-id)
                                      (= :send (:eventId (:data %))))
                                (:edges graph)))]
      (is (some? edge) "the compound self-loop survives projection")
      (is (true? (:selfLoop (:data edge)))))))

(deftest xyflow-graph-emits-compound-endpoint-edges-survive-projection
  (testing "rf2-shv82 (Issue 1) — every edge the parser emits also
            appears in the projected graph; no edge involving a compound
            endpoint is silently dropped at the projection layer (the
            rf2-shv82 bug was DOM-layer; pin the contract here so a
            future projection-layer filter would fail the test)"
    (let [parsed (layout/parse-definition parent-level-transition-machine)
          graph  (projection/xyflow-graph parsed {} {})
          parsed-ids (set (map :id (:edges parsed)))
          proj-ids   (set (map :id (remove #(:entry (:data %))
                                           (:edges graph))))]
      (is (= parsed-ids proj-ids)
          "every parsed edge id appears in the projected edge ids"))))

;; ---- self-loop fan (rf2-shv82, Issue 2) --------------------------------
;;
;; Multiple self-loops on the same source render via stacked loops at
;; the same coords pre-rf2-shv82 → garbled label overlap (3 self-loops
;; on `:disconnected` in the testdeck: `:ws/arm-fail`, `:ws/disarm-fail`,
;; `:ws/clear`). The fix assigns each a stable `loop-index` per source
;; (0..N-1 in emission order), which `chart.edges/edge-path` uses to
;; rotate the loop's perimeter slot + label anchor.

(def ^:private multi-self-loop-machine
  "Three self-loops on `:idle` (mirrors the testdeck `:disconnected`
  shape: 3 distinct events on one node, all self-transitions)."
  {:initial :idle
   :states  {:idle {:on {:arm    {:action :arm-it}
                         :disarm {:action :disarm-it}
                         :clear  {:action :clear-it}}}}})

(deftest xyflow-graph-assigns-loop-index-per-source
  (testing "rf2-shv82 (Issue 2) — multiple self-loops on the same source
            get distinct `:loopIndex` ordinals (0, 1, 2, …) in emission
            order so the renderer can fan them around the perimeter"
    (let [parsed (layout/parse-definition multi-self-loop-machine)
          graph  (projection/xyflow-graph parsed {} {})
          self-loops (->> (:edges graph)
                          (remove #(:entry (:data %)))
                          (filter #(true? (:selfLoop (:data %)))))]
      (is (= 3 (count self-loops)) "fixture has 3 self-loops on :idle")
      (is (= [0 1 2] (sort (map #(:loopIndex (:data %)) self-loops)))
          "the 3 self-loops get ordinals 0/1/2 (distinct slots)"))))

(deftest xyflow-graph-single-self-loop-gets-index-zero
  (testing "rf2-shv82 (Issue 2) — a single self-loop on a node gets
            :loopIndex 0 (the historical top-right slot — single-self-
            loop renders pixel-identical to pre-rf2-shv82)"
    (let [parsed (layout/parse-definition self-loop-machine)
          graph  (projection/xyflow-graph parsed {} {})
          ping   (first (filter #(true? (:selfLoop (:data %))) (:edges graph)))]
      (is (some? ping))
      (is (= 0 (:loopIndex (:data ping)))
          "single self-loop gets slot 0 (historical position)"))))

(deftest xyflow-graph-non-self-loops-have-nil-loop-index
  (testing "rf2-shv82 (Issue 2) — a normal transition (source != target)
            carries `:loopIndex nil` (no perimeter slot to assign)"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          non-self (filter #(false? (:selfLoop (:data %)))
                           (remove #(:entry (:data %)) (:edges graph)))]
      (is (seq non-self))
      (is (every? #(nil? (:loopIndex (:data %))) non-self)
          "non-self-loop edges carry no loop-index"))))

(deftest xyflow-graph-self-loop-indices-are-per-source
  (testing "rf2-shv82 (Issue 2) — the loop-index counter resets per
            source node (a self-loop on :a starts at 0 even if :b
            already has 3 self-loops). Each node fans independently."
    (let [m {:initial :a
             :states  {:a {:on {:a1 {} :a2 {}}}
                       :b {:on {:b1 {}}}}}
          parsed (layout/parse-definition m)
          graph  (projection/xyflow-graph parsed {} {})
          self-loops (filter #(true? (:selfLoop (:data %)))
                             (remove #(:entry (:data %)) (:edges graph)))
          by-source  (group-by :source self-loops)
          a-indices  (sort (map #(:loopIndex (:data %)) (get by-source (layout/node-id [:a]))))
          b-indices  (sort (map #(:loopIndex (:data %)) (get by-source (layout/node-id [:b]))))]
      (is (= [0 1] a-indices) ":a has self-loops 0 and 1")
      (is (= [0]   b-indices) ":b's first self-loop is 0, not 2"))))

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
  (testing "rf2-shv82 (Issue 3) — an edge whose source and target sit
            in DIFFERENT parent containers gets :crossHierarchy true"
    (let [parsed (layout/parse-definition cross-hierarchy-machine)
          graph  (projection/xyflow-graph parsed {} {})
          escape (first (filter #(= :escape (:eventId (:data %))) (:edges graph)))]
      (is (some? escape))
      (is (true? (:crossHierarchy (:data escape)))
          "inner→sibling escapes the :outer container"))))

(deftest xyflow-graph-same-parent-edge-not-cross-hierarchy
  (testing "rf2-shv82 (Issue 3) — an edge between two siblings under the
            SAME parent is NOT cross-hierarchy (both leaves share a
            parent-id; the edge stays inside the container)"
    (let [parsed (layout/parse-definition compound-machine)
          graph  (projection/xyflow-graph parsed {} {})
          ;; :browsing --checkout--> :paying (both under :authenticated)
          checkout (first (filter #(= :checkout (:eventId (:data %)))
                                  (:edges graph)))]
      (is (some? checkout))
      (is (false? (:crossHierarchy (:data checkout)))
          "two siblings under the same compound parent are NOT cross-hierarchy"))))

(deftest xyflow-graph-flat-machine-no-cross-hierarchy
  (testing "rf2-shv82 (Issue 3) — a flat machine has no containers, so
            no edge is cross-hierarchy"
    (let [parsed (layout/parse-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          non-entry (remove #(:entry (:data %)) (:edges graph))]
      (is (seq non-entry))
      (is (every? #(false? (:crossHierarchy (:data %))) non-entry)))))

(deftest xyflow-graph-self-loop-not-cross-hierarchy
  (testing "rf2-shv82 (Issue 3) — a self-loop (source == target) is
            never cross-hierarchy regardless of its container nesting"
    (let [parsed (layout/parse-definition self-loop-machine)
          graph  (projection/xyflow-graph parsed {} {})
          ping   (first (filter #(true? (:selfLoop (:data %))) (:edges graph)))]
      (is (some? ping))
      (is (false? (:crossHierarchy (:data ping)))))))

(deftest xyflow-graph-entry-edges-carry-cross-hierarchy-false
  (testing "rf2-shv82 — entry edges keep the every-edge :data shape
            whole: they carry :crossHierarchy false + :loopIndex nil"
    (let [parsed (layout/parse-definition cross-hierarchy-machine)
          graph  (projection/xyflow-graph parsed {} {})
          entry  (first (filter #(:entry (:data %)) (:edges graph)))]
      (is (some? entry))
      (is (false? (:crossHierarchy (:data entry))))
      (is (nil?   (:loopIndex (:data entry)))))))
