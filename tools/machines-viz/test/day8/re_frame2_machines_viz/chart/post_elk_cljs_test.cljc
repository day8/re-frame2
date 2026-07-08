(ns day8.re-frame2-machines-viz.chart.post-elk-cljs-test
  "Pure-data tests for the post-ELK layout subsystem (rf2-lamdfl +
  rf2-gnrkke) — the OPT-IN adaptive-aspect rebalance + back-edge return-route
  detour that close `001-Topology-Parity.md` §4.3.1 + §4.3.2.

  The #1 acceptance criterion of the redo (after the reverted #3453 made the
  pass auto-default) is that the DEFAULT path does NOT invoke the post-ELK
  subsystem. `adaptive?-is-opt-in-only` + `resolve-direction-only-resolves-
  on-opt-in` pin that gate.

  Four groups, each pinned at the JVM layer (mirroring the `chart.layout` /
  `chart.projection` test corpus):

    0. `adaptive?` / `resolve-direction` — the OPT-IN gate (`:auto` only).
    1. `aspect-direction` / `max-out-degree` — the orientation heuristic
       (column vs landscape per machine).
    2. `transpose-parallel-regions` — the parallel-region stacking-axis
       transpose (regions stack vertically, intra-region flow horizontal).
    3. `back-edge?` / `back-edge-detour` / `reroute-back-edges` — the sunk
       back-edge return-route detour.

  Positions are built PROGRAMMATICALLY from the parsed graph (via the public
  `layout/node-id` + `projection/event-node-id`) so the tests pin BEHAVIOUR,
  not a particular id-string scheme — a stub layout result keyed off the
  real ids the live ELK pass would produce.

  Also covers rf2-olie1s: `region-touch?`'s transitive-ancestor-walk (a
  nested-compound-in-region edge is region-touched, not just a one-hop
  region child) and `reroute-back-edges`' same-parent candidate filter (a
  cross-hierarchy/nested-vs-root back-edge is excluded, never mis-detected)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]
            [day8.re-frame2-machines-viz.chart.post-elk :as post-elk]))

;; ---- fixtures ----------------------------------------------------------

(def linear-machine
  "A pure chain: each state has exactly one outward target. The aspect
  heuristic must keep this a COLUMN (`:tb`) — a chain has nowhere to
  branch (the door/brew/session shape)."
  {:initial :a
   :states  {:a {:on {:go :b}}
             :b {:on {:go :c}}
             :c {:final? true}}})

(def door-cyclic-machine
  "The door shape: a forward spine `locked → closed → open → alarming`
  with a back-edge `alarming → reset → locked`. `:open` fans to two
  targets (close / trip) — under the threshold, so the heuristic keeps it
  a column; it exercises the back-edge reroute."
  {:initial :locked
   :states  {:locked   {:on {:insert-coin :closed}}
             :closed   {:on {:push :open}}
             :open     {:on {:close :closed :trip :alarming}}
             :alarming {:on {:reset :locked}}}})

(def branchy-machine
  "A hub state fanning to THREE distinct targets — at the landscape
  threshold, so the aspect heuristic flows it landscape (`:lr`), the
  quiz/modal/gate shape."
  {:initial :menu
   :states  {:menu {:on {:a :sa :b :sb :c :sc}}
             :sa   {}
             :sb   {}
             :sc   {}}})

(def guarded-fork-machine
  "A genuine 3-way guarded fork (the gate `:gate/check` shape): three
  candidates of ONE trigger branching to three distinct targets. Counts as
  out-degree 3 → landscape."
  {:initial :idle
   :states  {:idle {:on {:check [{:target :high :guard :hi?}
                                 {:target :low  :guard :lo?}
                                 {:target :rejected}]}}
             :high     {}
             :low      {}
             :rejected {}}})

(def same-target-fan-machine
  "Two distinct events from one state that BOTH land on the same target
  plus the spine — only 1 distinct outward target beyond the spine, so it
  is NOT branchy (no visual spread). Stays a column."
  {:initial :a
   :states  {:a {:on {:x :b :y :b}}
             :b {:on {:go :c}}
             :c {}}})

(def parallel-machine
  "Two-region parallel machine — exercises the region transpose + re-stack."
  {:type    :parallel
   :regions {:audio {:initial :muted
                     :states  {:muted   {:on {:unmute :playing}}
                               :playing {:on {:mute :muted}}}}
             :video {:initial :hidden
                     :states  {:hidden {:on {:show :shown}}
                               :shown  {:on {:hide :hidden}}}}}})

(def nested-compound-in-region-machine
  "rf2-olie1s — a parallel machine whose `:audio` region nests a COMPOUND
  state (`:playing`, with its own `:low`/`:high` substates) so `:low`/`:high`
  sit TWO hops from the region container: their DIRECT `:parent-id` is
  `:playing`'s own (region-scoped) id, NOT the region container id. Exercises
  `region-touch?`'s transitive-ancestor-walk fix — an edge between the two
  nested leaves must still have its stale ELK route cleared when the region
  transposes/re-stacks, even though neither leaf's direct parent is the
  region."
  {:type    :parallel
   :regions {:audio {:initial :muted
                     :states  {:muted   {:on {:unmute :playing}}
                               :playing {:initial :low
                                         :states  {:low  {:on {:raise :high}}
                                                   :high {:on {:lower :low}}}
                                         :on {:mute :muted}}}}
             :video {:initial :hidden
                     :states  {:hidden {:on {:show :shown}}
                               :shown  {:on {:hide :hidden}}}}}})

(def nested-back-edge-machine
  "rf2-olie1s — a compound (non-parallel) machine whose nested leaf `:step2`
  (inside the `:working` compound) transitions back to `:idle`, a TOP-LEVEL
  sibling of `:working` — a genuine back-edge shape, but one whose source
  (`:working__step2`, parented to `:working`) and target (`:idle`, parented
  to the machine's synthetic root-container) carry DIFFERENT `:parent-id`.
  The target is an explicit vector-path (`[:idle]`) because a bare keyword
  target from inside a nested compound resolves SIBLING-relative (Spec 005 /
  `resolve-target-path`) — `:reset :idle` would land on `:working/:idle`,
  not the top-level state.

  Exercises `reroute-back-edges`' same-parent filter: this cross-hierarchy
  candidate must be EXCLUDED from back-edge detection/reroute, because its
  endpoints' positions are recorded in different coordinate frames
  (`:working`-relative for `:step2`, root-container-relative for `:idle`) —
  comparing them geometrically is meaningless even when it happens to look
  'sunk'."
  {:initial :idle
   :states  {:idle    {:on {:start :working}}
             :working {:initial :step1
                       :states  {:step1 {:on {:next :step2}}
                                 :step2 {:on {:reset [:idle]}}}}}})

;; ---- helpers -----------------------------------------------------------

(defn- col-positions
  "Build a stub ELK-result `:positions` map laying every REAL state node out
  in a single vertical COLUMN (y = layer index × step) in parse order, with
  each transition's synthetic event-node placed at the layer BELOW its
  source — the events-as-nodes default that sinks a back-edge's event-node
  to the bottom (the §4.3.1 signature). Top-level frame/region/synthetic
  nodes are placed at origin (not on the spine).

  Returns `{:positions … :edge-points {} :edge-labels {}}`."
  [parsed]
  (let [step 100
        states (->> (:nodes parsed)
                    (remove #(or (:region? %) (:root-container? %)
                                 (:machine-root? %) (:parallel-root? %))))
        layer-of (into {} (map-indexed (fn [i n] [(:id n) i]) states))
        state-pos (into {}
                        (map (fn [n]
                               [(:id n) {:x 200
                                         :y (* step (get layer-of (:id n) 0))
                                         :width 120 :height 50}]))
                        states)
        ;; each event-node sits one layer below its source's layer; a
        ;; back-edge's source is the deepest state, so its event-node lands
        ;; at the very bottom (deeper than its target).
        max-layer (reduce max 0 (vals layer-of))
        event-pos (into {}
                        (keep (fn [e]
                                (when (:target e)
                                  (let [sl (get layer-of (:source e))
                                        tl (get layer-of (:target e))]
                                    ;; forward edge: event between source +
                                    ;; target; back-edge (target above
                                    ;; source): event sinks to the bottom.
                                    [(projection/event-node-id e)
                                     {:x 200
                                      :y (if (< tl sl)
                                           (* step (inc max-layer)) ;; sunk
                                           (* step (+ sl 0.5)))
                                      :width 96 :height 34}]))))
                        (:edges parsed))]
    {:positions   (merge state-pos event-pos)
     :edge-points {}
     :edge-labels {}}))

(defn- row-positions
  "rf2-hpe9ws — the `:lr` mirror of `col-positions`: lay every REAL state node
  out in a single horizontal ROW (x = layer index × step) in parse order,
  with each transition's synthetic event-node placed at the layer to the
  RIGHT of its source. A back-edge's source is the rightmost state, so its
  event-node sinks to the far RIGHT (greater x than both endpoints) — the
  `:lr` analogue of the §4.3.1 sunk signature `col-positions` builds on y.

  Returns `{:positions … :edge-points {} :edge-labels {}}`."
  [parsed]
  (let [step 100
        states (->> (:nodes parsed)
                    (remove #(or (:region? %) (:root-container? %)
                                 (:machine-root? %) (:parallel-root? %))))
        layer-of (into {} (map-indexed (fn [i n] [(:id n) i]) states))
        state-pos (into {}
                        (map (fn [n]
                               [(:id n) {:x (* step (get layer-of (:id n) 0))
                                         :y 200
                                         :width 120 :height 50}]))
                        states)
        max-layer (reduce max 0 (vals layer-of))
        event-pos (into {}
                        (keep (fn [e]
                                (when (:target e)
                                  (let [sl (get layer-of (:source e))
                                        tl (get layer-of (:target e))]
                                    ;; forward edge: event between source +
                                    ;; target; back-edge (target left of
                                    ;; source): event sinks to the far right.
                                    [(projection/event-node-id e)
                                     {:x (if (< tl sl)
                                           (* step (inc max-layer)) ;; sunk right
                                           (* step (+ sl 0.5)))
                                      :y 200
                                      :width 96 :height 34}]))))
                        (:edges parsed))]
    {:positions   (merge state-pos event-pos)
     :edge-points {}
     :edge-labels {}}))

;; ====================================================================
;; Step 0 — the OPT-IN gate (rf2-lamdfl + rf2-gnrkke redo)
;; ====================================================================
;; The #1 acceptance criterion: the default path does NOT invoke the
;; post-ELK pass. These guards lock the opt-in surface against a
;; re-regression to the reverted #3453 auto-default.

(deftest adaptive?-is-opt-in-only
  (testing "ONLY :auto opts a machine in to the adaptive pass"
    (is (true? (post-elk/adaptive? :auto))))

  (testing "the DEFAULT :tb is NON-adaptive (byte-identical-to-main path)"
    (is (false? (post-elk/adaptive? :tb))))

  (testing "an explicit :lr is NON-adaptive (forced, not adaptive)"
    (is (false? (post-elk/adaptive? :lr))))

  (testing "nil (prop omitted before the :tb default applies) is NON-adaptive"
    (is (false? (post-elk/adaptive? nil))))

  (testing "an unknown direction value is NON-adaptive (defensive default)"
    (is (false? (post-elk/adaptive? :sideways)))))

(deftest resolve-direction-only-resolves-on-opt-in
  (let [branchy (layout/project-definition branchy-machine)
        linear  (layout/project-definition linear-machine)]
    (testing ":auto defers to the heuristic (branchy → :lr)"
      (is (= :lr (post-elk/resolve-direction :auto branchy))))

    (testing ":auto on a linear machine → :tb"
      (is (= :tb (post-elk/resolve-direction :auto linear))))

    (testing "an explicit :tb passes STRAIGHT THROUGH (no heuristic) even on a branchy machine"
      (is (= :tb (post-elk/resolve-direction :tb branchy))))

    (testing "an explicit :lr passes straight through on a linear machine (no heuristic flip)"
      (is (= :lr (post-elk/resolve-direction :lr linear))))

    (testing "nil passes straight through (the default :tb is applied upstream, NOT here)"
      ;; the chart caller only routes :auto here; resolve-direction stays
      ;; total/defensive — a non-:auto value is the forced direction, not
      ;; the heuristic.
      (is (nil? (post-elk/resolve-direction nil branchy))))))

;; ====================================================================
;; Step 1 — aspect heuristic (rf2-lamdfl)
;; ====================================================================

(deftest max-out-degree-counts-distinct-outward-targets
  (testing "a pure chain has max out-degree 1"
    (is (= 1 (post-elk/max-out-degree (layout/project-definition linear-machine)))))

  (testing "a 3-way hub has max out-degree 3"
    (is (= 3 (post-elk/max-out-degree (layout/project-definition branchy-machine)))))

  (testing "a 3-way guarded fork counts as out-degree 3"
    (is (= 3 (post-elk/max-out-degree (layout/project-definition guarded-fork-machine)))))

  (testing "two events to the SAME target count as ONE distinct outward branch"
    ;; `:a` fans x→:b and y→:b (1 distinct) ; `:b` go→:c (1). Max = 1.
    (is (= 1 (post-elk/max-out-degree
               (layout/project-definition same-target-fan-machine)))))

  (testing "the door `:open` 2-way fan stays under the landscape threshold"
    (is (< (post-elk/max-out-degree (layout/project-definition door-cyclic-machine))
           post-elk/landscape-branch-threshold))))

(deftest aspect-direction-biases-per-machine
  (testing "a linear chain stays a COLUMN (:tb)"
    (is (= :tb (post-elk/aspect-direction (layout/project-definition linear-machine)))))

  (testing "the door (chain + 2-way fan + back-edge) stays a COLUMN"
    (is (= :tb (post-elk/aspect-direction
                 (layout/project-definition door-cyclic-machine)))))

  (testing "a branchy hub flows LANDSCAPE (:lr)"
    (is (= :lr (post-elk/aspect-direction
                 (layout/project-definition branchy-machine)))))

  (testing "a guarded 3-way fork flows LANDSCAPE"
    (is (= :lr (post-elk/aspect-direction
                 (layout/project-definition guarded-fork-machine)))))

  (testing "a PARALLEL machine resolves :tb (its aspect is the region transpose)"
    (is (= :tb (post-elk/aspect-direction
                 (layout/project-definition parallel-machine))))))

;; ====================================================================
;; Step 2 — parallel-region stacking-axis transpose (rf2-lamdfl)
;; ====================================================================

(deftest transpose-is-noop-for-flat-machines
  (let [parsed (layout/project-definition linear-machine)
        stub   (col-positions parsed)]
    (testing "a non-parallel machine is returned UNCHANGED"
      (is (= stub (post-elk/transpose-parallel-regions stub parsed))))))

(deftest region-descendant-ids-groups-states-and-event-nodes
  (let [parsed (layout/project-definition parallel-machine)
        desc   (post-elk/region-descendant-ids parsed)
        region-ids (->> (:nodes parsed) (filter :region?) (map :id) set)]
    (testing "every region container is a key"
      (is (= region-ids (set (keys desc)))))

    (testing "each region groups its OWN states (audio: muted/playing)"
      (let [audio-rid (layout/region-node-id :audio)
            audio-states #{(layout/region-scoped-id :audio [:muted])
                           (layout/region-scoped-id :audio [:playing])}]
        (is (every? (get desc audio-rid) audio-states))))

    (testing "a region's event-nodes are folded in under that region"
      ;; audio has muted--unmute-->playing + playing--mute-->muted = 2 events
      (let [audio-rid (layout/region-node-id :audio)
            ev-ids (filter #(str/starts-with? % "__rf2_event_")
                           (get desc audio-rid))]
        (is (= 2 (count ev-ids)))))))

(deftest transpose-parallel-regions-stacks-and-flips
  (let [parsed (layout/project-definition parallel-machine)
        ;; lay each region SIDE-BY-SIDE with vertical intra-region flow
        ;; (the ELK shape the transpose must flip): region containers at
        ;; x=0 and x=400; each region's states in a vertical column.
        audio-rid (layout/region-node-id :audio)
        video-rid (layout/region-node-id :video)
        ;; region-relative child positions: a vertical stack inside each region
        child-col (fn [ids]
                    (into {} (map-indexed
                               (fn [i id] [id {:x 20 :y (+ 40 (* 80 i))
                                               :width 120 :height 50}])
                               ids)))
        desc (post-elk/region-descendant-ids parsed)
        positions (merge
                    {audio-rid {:x 0   :y 0 :width 200 :height 400}
                     video-rid {:x 400 :y 0 :width 200 :height 400}}
                    (child-col (sort (get desc audio-rid)))
                    (child-col (sort (get desc video-rid))))
        stub {:positions positions :edge-points {} :edge-labels {}}
        out  (post-elk/transpose-parallel-regions stub parsed)
        np   (:positions out)]
    (testing "region containers re-stack into a VERTICAL column (video below audio)"
      (is (= (:x (get np audio-rid)) (:x (get np video-rid)))
          "both regions left-aligned (same x)")
      (is (< (:y (get np audio-rid)) (:y (get np video-rid)))
          "audio (region-index 0) sits ABOVE video (region-index 1)"))

    (testing "the second region's y starts below the first's transposed band"
      (let [audio-band (get np audio-rid)]
        (is (>= (:y (get np video-rid))
                (+ (:y audio-band) (:height audio-band)))
            "no vertical overlap between stacked regions")))

    (testing "intra-region children TRANSPOSE (a vertical stack becomes a row)"
      ;; original audio children: same x (20), increasing y → a column.
      ;; after transpose: same y, increasing x → a row.
      (let [audio-child-ids (filter #(not (str/starts-with? % "__rf2_event_"))
                                    (get desc audio-rid))
            child-positions (map #(get np %) audio-child-ids)
            xs (map :x child-positions)
            ys (map :y child-positions)]
        (is (apply = ys) "transposed children share a y (horizontal row)")
        (is (not (apply = xs)) "transposed children spread on x")))))

(deftest transpose-preserves-positive-region-origin
  ;; rf2-qp613a — for a root-container-wrapped chart the region containers are
  ;; positioned relative to the root frame's padding/header, so every region
  ;; origin is POSITIVE. The column origin must be computed from the ACTUAL
  ;; region positions, NOT `(reduce min 0 …)` which clamped a positive origin
  ;; to zero and slid the stacked column up/left into the reserved root chrome.
  (let [parsed (layout/project-definition parallel-machine)
        desc   (post-elk/region-descendant-ids parsed)
        audio-rid (layout/region-node-id :audio)
        video-rid (layout/region-node-id :video)
        ;; the root frame reserves chrome: a left padding (60) + a top
        ;; header band (120). BOTH region containers start at that positive
        ;; origin (side-by-side: audio at root-x, video offset right).
        root-x 60
        root-y 120
        child-col (fn [ids]
                    (into {} (map-indexed
                               (fn [i id] [id {:x 20 :y (+ 40 (* 80 i))
                                               :width 120 :height 50}])
                               ids)))
        positions (merge
                    {audio-rid {:x root-x        :y root-y :width 200 :height 400}
                     video-rid {:x (+ root-x 400) :y root-y :width 200 :height 400}}
                    (child-col (sort (get desc audio-rid)))
                    (child-col (sort (get desc video-rid))))
        stub {:positions positions :edge-points {} :edge-labels {}}
        out  (post-elk/transpose-parallel-regions stub parsed)
        np   (:positions out)]
    (testing "the stacked column preserves the leftmost region's POSITIVE x origin"
      (is (= root-x (:x (get np audio-rid)))
          "audio container keeps the reserved root x (not clamped to 0)")
      (is (= root-x (:x (get np video-rid)))
          "video container left-aligns to the same positive column x"))

    (testing "the stacked column preserves the topmost region's POSITIVE y origin"
      (is (= root-y (:y (get np audio-rid)))
          "the topmost (audio) band starts at the reserved root y (not 0)")
      (is (>= (:y (get np video-rid)) root-y)
          "the second band stacks below, still inside the reserved frame"))

    (testing "the column does NOT slide into the reserved root chrome"
      (is (>= (:x (get np audio-rid)) root-x))
      (is (>= (:y (get np audio-rid)) root-y)))))

(defn- x-overlap?
  "Do two boxes `{:x :width}` overlap along the x-axis (open intervals)? Pure."
  [a b]
  (and (< (:x a) (+ (:x b) (:width b)))
       (< (:x b) (+ (:x a) (:width a)))))

(deftest transpose-event-chips-clear-state-boxes
  ;; rf2-vb359s — after the transpose, intra-region event-node chips must NOT
  ;; overlap the state boxes. A bare coordinate swap inherits the flow spacing
  ;; ELK sized for node HEIGHTS (state 58 / chip 34), far too tight once boxes
  ;; occupy their WIDTHS along the new x-axis (state 152 / chip 96), so the
  ;; chips buried into the state boxes. The re-pack must space the ranks by
  ;; their actual widths.
  (let [parsed (layout/project-definition parallel-machine)
        desc   (post-elk/region-descendant-ids parsed)
        audio-rid (layout/region-node-id :audio)
        video-rid (layout/region-node-id :video)
        ;; an ELK-shaped column inside each region: states at REALISTIC widths
        ;; (152×58) stacked vertically with an event chip (96×34) between
        ;; consecutive states (the +0.5 inter-rank events-as-nodes shape). The
        ;; vertical pitch (108px) is sized for the heights the bug inherits.
        state-ids  (fn [rid] (->> (get desc rid)
                                  (remove #(str/starts-with? % "__rf2_event_"))
                                  sort))
        event-ids  (fn [rid] (->> (get desc rid)
                                  (filter #(str/starts-with? % "__rf2_event_"))
                                  sort))
        region-cols
        (fn [rid x0]
          (let [ss (state-ids rid)
                es (event-ids rid)
                ;; states on integer ranks 0,1,…; events on the +0.5 ranks
                ;; between them — exactly the column ELK produces for a region.
                state-pos (into {} (map-indexed
                                     (fn [i id] [id {:x (+ x0 20) :y (* 108 i)
                                                     :width 152 :height 58}])
                                     ss))
                event-pos (into {} (map-indexed
                                     (fn [i id] [id {:x (+ x0 48) :y (+ 54 (* 108 i))
                                                     :width 96 :height 34}])
                                     es))]
            (merge state-pos event-pos)))
        positions (merge
                    {audio-rid {:x 0   :y 0 :width 200 :height 500}
                     video-rid {:x 400 :y 0 :width 200 :height 500}}
                    (region-cols audio-rid 0)
                    (region-cols video-rid 400))
        stub {:positions positions :edge-points {} :edge-labels {}}
        out  (post-elk/transpose-parallel-regions stub parsed)
        np   (:positions out)
        check-region
        (fn [rid]
          (let [s-boxes (map #(get np %) (state-ids rid))
                e-boxes (map #(get np %) (event-ids rid))]
            (doseq [eb e-boxes
                    sb s-boxes]
              (is (not (x-overlap? eb sb))
                  (str "event chip " eb " overlaps state box " sb
                       " in region " rid " after transpose")))))]
    (testing "audio region: no event chip overlaps a state box on the flow axis"
      (check-region audio-rid))
    (testing "video region: no event chip overlaps a state box on the flow axis"
      (check-region video-rid))

    (testing "the re-packed ranks read left-to-right with positive flow pitch"
      ;; the transposed audio children must occupy DISTINCT x ranks (not all
      ;; piled on one x), with each rank cleared of the previous.
      (let [audio-children (map #(get np %) (get desc audio-rid))
            xs (sort (distinct (map :x audio-children)))]
        (is (> (count xs) 1) "children spread across multiple flow ranks")
        (is (apply < xs) "ranks are strictly increasing on x")))))

(deftest transpose-clears-region-edge-routes
  (let [parsed (layout/project-definition parallel-machine)
        ;; seed an ELK route on an intra-region edge, then assert the
        ;; transpose CLEARS it (so the renderer re-routes via bezier).
        an-edge (first (:edges parsed))
        seeded  {:positions (:positions (col-positions parsed))
                 :edge-points {(str (:id an-edge) "__in")  [{:x 1 :y 1} {:x 2 :y 2}]
                               (str (:id an-edge) "__out") [{:x 3 :y 3} {:x 4 :y 4}]}
                 :edge-labels {}}
        out (post-elk/transpose-parallel-regions seeded parsed)]
    (testing "a region-touching edge's stale ELK route is dropped"
      (is (not (contains? (:edge-points out) (str (:id an-edge) "__in"))))
      (is (not (contains? (:edge-points out) (str (:id an-edge) "__out")))))))

(deftest transpose-clears-nested-compound-region-edge-routes
  ;; rf2-olie1s — region-touch? must walk the FULL ancestor chain, not just
  ;; one :parent-id hop. :low/:high sit inside :playing, a compound state
  ;; nested INSIDE the :audio region — their DIRECT :parent-id is
  ;; :playing's own id, not the region id, so a one-hop region-touch? test
  ;; misses this edge entirely and its stale absolute ELK route would
  ;; survive the transpose untouched even though :playing (and the region)
  ;; were just repositioned.
  (let [parsed (layout/project-definition nested-compound-in-region-machine)
        low->high (first (filter
                            (fn [e]
                              (and (= (:source e)
                                      (layout/region-scoped-id :audio [:playing :low]))
                                   (= (:target e)
                                      (layout/region-scoped-id :audio [:playing :high]))))
                            (:edges parsed)))
        seeded {:positions (:positions (col-positions parsed))
                :edge-points {(str (:id low->high) "__in")  [{:x 1 :y 1} {:x 2 :y 2}]
                              (str (:id low->high) "__out") [{:x 3 :y 3} {:x 4 :y 4}]}
                :edge-labels {}}
        out (post-elk/transpose-parallel-regions seeded parsed)]
    (testing "sanity: the fixture parses the nested-compound low->high edge"
      (is (some? low->high)))

    (testing "the low->high edge (two hops from the region) IS region-touched"
      (is (not (contains? (:edge-points out) (str (:id low->high) "__in"))))
      (is (not (contains? (:edge-points out) (str (:id low->high) "__out")))))))

;; ====================================================================
;; Step 3 — back-edge return-route detour (rf2-gnrkke)
;; ====================================================================

(deftest back-edge-detection-finds-the-sunk-event-node
  (let [parsed (layout/project-definition door-cyclic-machine)
        stub   (col-positions parsed)
        positions (:positions stub)
        ;; the back-edge is alarming --reset--> locked (target above source)
        back-e (first (filter (fn [e]
                                (and (= (:source e) (layout/node-id [:alarming]))
                                     (= (:target e) (layout/node-id [:locked]))))
                              (:edges parsed)))
        fwd-e  (first (filter (fn [e]
                                (and (= (:source e) (layout/node-id [:locked]))
                                     (= (:target e) (layout/node-id [:closed]))))
                              (:edges parsed)))]
    (testing "the alarming→locked back-edge IS detected (its event-node sank)"
      (is (some? back-e))
      (is (true? (post-elk/back-edge? back-e positions :tb))))

    (testing "a FORWARD edge is NOT a back-edge"
      (is (some? fwd-e))
      (is (not (post-elk/back-edge? fwd-e positions :tb))))))

(deftest back-edge-detour-lifts-the-chip-to-mid-height
  (let [parsed (layout/project-definition door-cyclic-machine)
        stub   (col-positions parsed)
        positions (:positions stub)
        back-e (first (filter (fn [e]
                                (and (= (:source e) (layout/node-id [:alarming]))
                                     (= (:target e) (layout/node-id [:locked]))))
                              (:edges parsed)))
        ev-id  (projection/event-node-id back-e)
        sunk-y (:y (get positions ev-id))
        {:keys [event-pos in-points out-points]}
        (post-elk/back-edge-detour back-e positions :tb)
        src-c  (:y (get positions (:source back-e)))
        tgt-c  (:y (get positions (:target back-e)))]
    (testing "the rerouted chip is LIFTED above its sunk y (mid-height return)"
      (is (< (:y event-pos) sunk-y)
          "rerouted event-node y is above the deep-layer sink"))

    (testing "the chip lands between the endpoints' flow extent (mid-height)"
      (let [chip-cy (+ (:y event-pos) (/ 34 2))]
        (is (<= (min src-c tgt-c) chip-cy (max src-c tgt-c)))))

    (testing "the detour bows to the SIDE of the spine (different x than the column)"
      (let [spine-x (:x (get positions (:source back-e)))]
        (is (not= spine-x (:x event-pos))
            "the chip detours off the spine column")))

    (testing "both segments are multi-point detour routes"
      (is (>= (count in-points) 3))
      (is (>= (count out-points) 3)))))

(deftest back-edge-detour-lr-mirrors-the-elbow
  ;; rf2-hpe9ws — on an :lr layout the back-edge detour must MIRROR the elbow:
  ;; leave the source VERTICALLY to the side lane (above the row), then run
  ;; horizontally to the lifted chip — NOT run along the flow row first (the
  ;; :tb elbow shape, which would cross the forward edges).
  (let [parsed (layout/project-definition door-cyclic-machine)
        ;; an :lr row layout where the back-edge event-node sank to the far
        ;; RIGHT of both endpoints (the :lr sunk signature).
        stub   (row-positions parsed)
        positions (:positions stub)
        back-e (first (filter (fn [e]
                                (and (= (:source e) (layout/node-id [:alarming]))
                                     (= (:target e) (layout/node-id [:locked]))))
                              (:edges parsed)))]
    (testing "the :lr back-edge IS detected (its event-node sank to the right)"
      (is (true? (post-elk/back-edge? back-e positions :lr))))

    (let [sc (#'post-elk/node-center positions (:source back-e))
          tc (#'post-elk/node-center positions (:target back-e))
          {:keys [event-pos in-points out-points]}
          (post-elk/back-edge-detour back-e positions :lr)
          ev-w   projection/event-node-elk-width
          ev-h   projection/event-node-elk-height
          chip-cx (+ (:x event-pos) (/ ev-w 2))
          chip-cy (+ (:y event-pos) (/ ev-h 2))
          in-elbow  (second in-points)
          out-elbow (second out-points)]
      (testing "the chip is lifted ABOVE the flow row (bowed off the :lr spine on y)"
        (is (< chip-cy (:y sc))
            "rerouted chip y is above the source/target row")
        (is (= (:y sc) (:y tc))
            "sanity: the :lr endpoints share the flow row"))

      (testing "the chip lands mid-WIDTH between the endpoints (the :lr flow axis)"
        (is (<= (min (:x sc) (:x tc)) chip-cx (max (:x sc) (:x tc)))))

      (testing "the __in elbow leaves the source VERTICALLY (keeps source x, drops to the lane y)"
        ;; the bug used {:x detour-x :y (:y sc)} — running along the flow row
        ;; first. The :lr mirror keeps the source's x and moves to the lane y.
        (is (= (:x sc) (:x in-elbow))
            "the in-elbow shares the source's x (leaves vertically, not along the row)")
        (is (= chip-cy (:y in-elbow))
            "the in-elbow drops to the side-lane y before running horizontally"))

      (testing "the __out elbow re-enters the target VERTICALLY (keeps target x at the lane y)"
        (is (= (:x tc) (:x out-elbow))
            "the out-elbow shares the target's x (drops onto the target vertically)")
        (is (= chip-cy (:y out-elbow))
            "the out-elbow stays at the lane y until it is above the target")))))

(deftest back-edge-detour-tb-keeps-its-elbow
  ;; rf2-hpe9ws — guard the :tb branch is UNCHANGED by the mirror: the :tb
  ;; elbow still leaves the source SIDEWAYS (to the side lane x) keeping the
  ;; source's y, the historical shape.
  (let [parsed (layout/project-definition door-cyclic-machine)
        stub   (col-positions parsed)
        positions (:positions stub)
        back-e (first (filter (fn [e]
                                (and (= (:source e) (layout/node-id [:alarming]))
                                     (= (:target e) (layout/node-id [:locked]))))
                              (:edges parsed)))
        sc (#'post-elk/node-center positions (:source back-e))
        tc (#'post-elk/node-center positions (:target back-e))
        {:keys [in-points out-points]}
        (post-elk/back-edge-detour back-e positions :tb)
        in-elbow  (second in-points)
        out-elbow (second out-points)]
    (testing "the :tb __in elbow keeps the source's y and moves to the side lane x"
      (is (= (:y sc) (:y in-elbow))
          "the in-elbow shares the source's y (leaves sideways, the :tb shape)")
      (is (not= (:x sc) (:x in-elbow))
          "the in-elbow moves off the spine x to the side lane"))
    (testing "the :tb __out elbow keeps the target's y and stays on the side lane x"
      (is (= (:y tc) (:y out-elbow))
          "the out-elbow shares the target's y (the :tb shape)"))))

(deftest reroute-back-edges-rewrites-positions-and-routes
  (let [parsed (layout/project-definition door-cyclic-machine)
        stub   (col-positions parsed)
        back-e (first (filter (fn [e]
                                (and (= (:source e) (layout/node-id [:alarming]))
                                     (= (:target e) (layout/node-id [:locked]))))
                              (:edges parsed)))
        ev-id  (projection/event-node-id back-e)
        sunk-y (:y (get-in stub [:positions ev-id]))
        out    (post-elk/reroute-back-edges stub parsed :tb)]
    (testing "the back-edge event-node is repositioned (lifted)"
      (is (< (:y (get-in out [:positions ev-id])) sunk-y)))

    (testing "the back-edge's __in / __out segments get detour routes"
      (is (contains? (:edge-points out) (str (:id back-e) "__in")))
      (is (contains? (:edge-points out) (str (:id back-e) "__out"))))

    (testing "a forward edge's event-node is UNTOUCHED"
      (let [fwd-e (first (filter (fn [e]
                                   (and (= (:source e) (layout/node-id [:locked]))
                                        (= (:target e) (layout/node-id [:closed]))))
                                 (:edges parsed)))
            fwd-ev (projection/event-node-id fwd-e)]
        (is (= (get-in stub [:positions fwd-ev])
               (get-in out [:positions fwd-ev]))
            "forward event-node position unchanged")))))

(deftest reroute-is-noop-when-no-back-edge
  (let [parsed (layout/project-definition linear-machine)
        stub   (col-positions parsed)]
    (testing "a machine with no sunk back-edge is returned unchanged"
      (is (= stub (post-elk/reroute-back-edges stub parsed :tb))))))

(deftest reroute-back-edges-excludes-cross-hierarchy-candidates
  ;; rf2-olie1s — node-center's centre read is only valid within one
  ;; coordinate frame (same :parent-id). :step2 (parented to :working) and
  ;; :idle (parented to the synthetic root-container, a DIFFERENT parent)
  ;; sit in DIFFERENT frames; a bare geometric comparison of their positions
  ;; can look "sunk" even though the comparison is meaningless.
  ;; reroute-back-edges must exclude this candidate rather than mis-detect +
  ;; reroute it.
  (let [parsed (layout/project-definition nested-back-edge-machine)
        back-e (first (filter (fn [e]
                                (and (= (:source e) (layout/node-id [:working :step2]))
                                     (= (:target e) (layout/node-id [:idle]))))
                              (:edges parsed)))
        ev-id  (projection/event-node-id back-e)
        ;; hand-built positions: :idle at the top (y 0), :step2 lower
        ;; (y 200), and the reset event-node sunk BELOW both (y 300) — the
        ;; naive geometric signature back-edge? flags as sunk, even though
        ;; :idle and :step2/the event are recorded relative to DIFFERENT
        ;; parents (different frames; the raw comparison is bogus).
        positions {(layout/node-id [:idle])          {:x 200 :y 0   :width 120 :height 50}
                   (layout/node-id [:working :step1]) {:x 200 :y 100 :width 120 :height 50}
                   (layout/node-id [:working :step2]) {:x 200 :y 200 :width 120 :height 50}
                   ev-id                              {:x 200 :y 300 :width 96  :height 34}}
        stub {:positions positions :edge-points {} :edge-labels {}}
        out  (post-elk/reroute-back-edges stub parsed :tb)]
    (testing "sanity: the fixture parses the cross-hierarchy back-edge"
      (is (some? back-e)))

    (testing "sanity: the naive geometric check WOULD flag this cross-hierarchy edge as sunk"
      (is (true? (post-elk/back-edge? back-e positions :tb))))

    (testing "reroute-back-edges excludes it — the event-node position is UNTOUCHED"
      (is (= (get positions ev-id) (get-in out [:positions ev-id]))))

    (testing "reroute-back-edges excludes it — no detour routes are written"
      (is (not (contains? (:edge-points out) (str (:id back-e) "__in"))))
      (is (not (contains? (:edge-points out) (str (:id back-e) "__out")))))))

;; ====================================================================
;; Composing pass
;; ====================================================================

(deftest apply-post-elk-is-identity-for-simple-linear-machines
  (let [parsed (layout/project-definition linear-machine)
        stub   (col-positions parsed)]
    (testing "linear, non-parallel, no back-edge → no-op even after opt-in"
      (is (= stub (post-elk/apply-post-elk stub parsed :tb))))))

(deftest apply-post-elk-reroutes-the-door-back-edge
  (let [parsed (layout/project-definition door-cyclic-machine)
        stub   (col-positions parsed)
        back-e (first (filter (fn [e]
                                (and (= (:source e) (layout/node-id [:alarming]))
                                     (= (:target e) (layout/node-id [:locked]))))
                              (:edges parsed)))
        out    (post-elk/apply-post-elk stub parsed :tb)]
    (testing "the cohesive pass reroutes the door back-edge end-to-end"
      (is (contains? (:edge-points out) (str (:id back-e) "__in")))
      (is (< (:y (get-in out [:positions (projection/event-node-id back-e)]))
             (:y (get-in stub [:positions (projection/event-node-id back-e)])))))))
