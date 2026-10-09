(ns day8.re-frame2-machines-viz.chart.post-elk-cljs-test
  "The opt-in post-ELK pass (001-Topology-Parity.md §4.3.1 + §4.3.2). Stub
  layouts are keyed off the real ids the parse mints (`layout/node-id`,
  `projection/event-node-id`)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]
            [day8.re-frame2-machines-viz.chart.post-elk :as post-elk]))

;; ---- fixtures ----------------------------------------------------------

(def linear-machine
  {:initial :a
   :states  {:a {:on {:go :b}}
             :b {:on {:go :c}}
             :c {:final? true}}})

(def door-cyclic-machine
  "A forward spine with a back-edge `alarming → reset → locked`; `:open` fans
  to two targets, one under the landscape threshold."
  {:initial :locked
   :states  {:locked   {:on {:insert-coin :closed}}
             :closed   {:on {:push :open}}
             :open     {:on {:close :closed :trip :alarming}}
             :alarming {:on {:reset :locked}}}})

(def branchy-machine
  "A hub fanning to three distinct targets: at the landscape threshold."
  {:initial :menu
   :states  {:menu {:on {:a :sa :b :sb :c :sc}}
             :sa   {}
             :sb   {}
             :sc   {}}})

(def same-target-fan-machine
  "Three events from one state that all land on ONE target: one distinct branch."
  {:initial :a
   :states  {:a {:on {:x :b :y :b :z :b}}
             :b {:on {:go :c}}
             :c {}}})

(def parallel-machine
  {:type    :parallel
   :regions {:audio {:initial :muted
                     :states  {:muted   {:on {:unmute :playing}}
                               :playing {:on {:mute :muted}}}}
             :video {:initial :hidden
                     :states  {:hidden {:on {:show :shown}}
                               :shown  {:on {:hide :hidden}}}}}})

(def nested-compound-in-region-machine
  "`:low`/`:high` sit inside the compound `:playing`, inside the `:audio`
  region, so their direct parent is `:playing`, not the region."
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
  "`:step2` (inside `:working`) returns to the top-level `:idle`: a back-edge
  whose endpoints sit in different parents' coordinate frames. The target is
  a vector path because a bare keyword would resolve sibling-relative."
  {:initial :idle
   :states  {:idle    {:on {:start :working}}
             :working {:initial :step1
                       :states  {:step1 {:on {:next :step2}}
                                 :step2 {:on {:reset [:idle]}}}}}})

(def compound-same-parent-back-edge-machine
  "`:b → :a` is a same-parent back-edge nested in `:grouped`, whose origin
  is not (0,0)."
  {:initial :outer
   :states  {:outer   {:on {:go :grouped}}
             :grouped {:initial :a
                       :states  {:a {:on {:next :b}}
                                 :b {:on {:back :a}}}}}})

;; ---- helpers -----------------------------------------------------------

(defn- edge-between [parsed source target]
  (some #(when (and (= source (:source %)) (= target (:target %))) %) (:edges parsed)))

(defn- door-back-edge [parsed]
  (edge-between parsed (layout/node-id [:alarming]) (layout/node-id [:locked])))

(defn- event-id? [id]
  (str/starts-with? id "__rf2_event_"))

(defn- col-positions
  "A stub ELK result laying every real state out in one vertical column in
  parse order, each event-node one half-layer below its source, and a
  back-edge's event-node sunk below the deepest state (the §4.3.1 signature)."
  [parsed]
  (let [step     100
        states   (remove #(or (:region? %) (:root-container? %)
                              (:machine-root? %) (:parallel-root? %))
                         (:nodes parsed))
        layer-of (into {} (map-indexed (fn [i n] [(:id n) i]) states))
        max-layer (reduce max 0 (vals layer-of))]
    {:positions
     (merge
       (into {} (map (fn [n] [(:id n) {:x 200 :y (* step (get layer-of (:id n) 0))
                                       :width 120 :height 50}]))
             states)
       (into {} (keep (fn [e]
                        (when (:target e)
                          (let [sl (get layer-of (:source e))
                                tl (get layer-of (:target e))]
                            [(projection/event-node-id e)
                             {:x 200
                              :y (if (< tl sl) (* step (inc max-layer)) (* step (+ sl 0.5)))
                              :width 96 :height 34}]))))
             (:edges parsed)))
     :edge-points {}
     :edge-labels {}}))

(defn- side-by-side-regions
  "ELK's shape for `parallel-machine` before the transpose: the root frame,
  the two region containers side by side at a positive origin, and each
  region's children stacked in a column."
  [parsed]
  (let [desc   (post-elk/region-descendant-ids parsed)
        column (fn [rid]
                 (into {} (map-indexed (fn [i id] [id {:x 20 :y (+ 40 (* 80 i))
                                                       :width 120 :height 50}]))
                       (sort (get desc rid))))
        audio  (layout/region-node-id :audio)
        video  (layout/region-node-id :video)]
    {:positions   (merge {layout/root-container-id {:x 12  :y 12  :width 680 :height 540}
                          audio                    {:x 60  :y 120 :width 200 :height 400}
                          video                    {:x 460 :y 120 :width 200 :height 400}}
                         (column audio)
                         (column video))
     :edge-points {}
     :edge-labels {}}))

;; ---- the opt-in gate + aspect heuristic ---------------------------------

(deftest adaptive?-is-opt-in-only
  (testing "only :auto opts in; the default :tb and a forced :lr never run the pass"
    (is (= [true false false] (map post-elk/adaptive? [:auto :tb :lr])))
    (is (= :lr (post-elk/resolve-direction :auto (layout/project-definition branchy-machine)))
        ":auto defers to the heuristic")))

(deftest aspect-direction-biases-per-machine
  (doseq [[label machine expected]
          [["a 2-way fan stays under the threshold: column"     door-cyclic-machine     :tb]
           ["a 3-way hub flows landscape"                       branchy-machine         :lr]
           ["three events to ONE target are one branch: column" same-target-fan-machine :tb]
           ["a parallel machine leaves its aspect to the region transpose" parallel-machine :tb]]]
    (is (= expected (post-elk/aspect-direction (layout/project-definition machine))) label)))

;; ---- parallel-region stacking-axis transpose ----------------------------

(deftest region-descendant-ids-groups-states-and-event-nodes
  (let [parsed (layout/project-definition parallel-machine)
        desc   (post-elk/region-descendant-ids parsed)
        audio  (get desc (layout/region-node-id :audio))]
    (is (= #{(layout/region-node-id :audio) (layout/region-node-id :video)} (set (keys desc))))
    (is (= #{(layout/region-scoped-id :audio [:muted]) (layout/region-scoped-id :audio [:playing])}
           (set (remove event-id? audio))))
    (is (= 2 (count (filter event-id? audio))) "the region's two event-nodes fold in")))

(deftest transpose-parallel-regions-stacks-and-flips
  (testing "the regions re-stack into one column at the leftmost / topmost
            region's own (positive) origin, and each region's vertical column
            of children becomes a row"
    (let [parsed (layout/project-definition parallel-machine)
          audio  (layout/region-node-id :audio)
          video  (layout/region-node-id :video)
          np     (:positions (post-elk/transpose-parallel-regions
                               (side-by-side-regions parsed) parsed))
          kids   (map np (remove event-id? (get (post-elk/region-descendant-ids parsed) audio)))]
      (is (= [60 120] ((juxt :x :y) (np audio))))
      (is (= 60 (:x (np video))))
      (is (>= (:y (np video)) (+ (:y (np audio)) (:height (np audio))))
          "video stacks below audio's band")
      (is (apply = (map :y kids)) "the children share a y")
      (is (not (apply = (map :x kids))) "and spread along x"))))

(deftest transpose-grows-the-frame-to-enclose-the-stacked-column
  (testing "the regions are the root frame's parentId children, so the frame
            grows to hold the column (keeping its origin and ELK's 20px right
            inset, never shrinking) or xyflow clamps the bands back inside"
    (let [parsed (layout/project-definition parallel-machine)
          np     (:positions (post-elk/transpose-parallel-regions
                               (side-by-side-regions parsed) parsed))
          right  (reduce max (map #(+ (:x %) (:width %))
                                  (map np [(layout/region-node-id :audio)
                                           (layout/region-node-id :video)])))]
      (is (> right 680) "the stacked column outgrows the side-by-side frame")
      (is (= {:x 12 :y 12 :width (+ right 20) :height 540}
             (np layout/root-container-id))))))

(defn- x-overlap? [a b]
  (and (< (:x a) (+ (:x b) (:width b)))
       (< (:x b) (+ (:x a) (:width a)))))

(deftest transpose-event-chips-clear-state-boxes
  (testing "a bare x/y swap inherits ELK's height-sized flow pitch, so chips
            would bury into the state boxes; the re-pack spaces ranks by width"
    (let [parsed    (layout/project-definition parallel-machine)
          desc      (post-elk/region-descendant-ids parsed)
          audio     (layout/region-node-id :audio)
          video     (layout/region-node-id :video)
          state-ids (fn [rid] (sort (remove event-id? (get desc rid))))
          event-ids (fn [rid] (sort (filter event-id? (get desc rid))))
          ;; an ELK column at realistic sizes (state 152×58, chip 96×34),
          ;; chips on the half-ranks between states
          region-col (fn [rid x0]
                       (merge
                         (into {} (map-indexed (fn [i id] [id {:x (+ x0 20) :y (* 108 i)
                                                               :width 152 :height 58}]))
                               (state-ids rid))
                         (into {} (map-indexed (fn [i id] [id {:x (+ x0 48) :y (+ 54 (* 108 i))
                                                               :width 96 :height 34}]))
                               (event-ids rid))))
          np (:positions (post-elk/transpose-parallel-regions
                           {:positions   (merge {audio {:x 0   :y 0 :width 200 :height 500}
                                                 video {:x 400 :y 0 :width 200 :height 500}}
                                                (region-col audio 0)
                                                (region-col video 400))
                            :edge-points {}
                            :edge-labels {}}
                           parsed))]
      (doseq [rid [audio video]
              eb  (map np (event-ids rid))
              sb  (map np (state-ids rid))]
        (is (not (x-overlap? eb sb)) (str "chip " eb " overlaps state " sb " in " rid))))))

(deftest transpose-clears-nested-compound-region-edge-routes
  (testing "the region-touch test walks the whole ancestor chain: an edge between
            two leaves of a compound nested in a region loses its stale route"
    (let [parsed    (layout/project-definition nested-compound-in-region-machine)
          low->high (edge-between parsed
                                  (layout/region-scoped-id :audio [:playing :low])
                                  (layout/region-scoped-id :audio [:playing :high]))
          seeded    {:positions   (:positions (col-positions parsed))
                     :edge-points {(str (:id low->high) "__in")  [{:x 1 :y 1} {:x 2 :y 2}]
                                   (str (:id low->high) "__out") [{:x 3 :y 3} {:x 4 :y 4}]}
                     :edge-labels {}}]
      (is (= {} (:edge-points (post-elk/transpose-parallel-regions seeded parsed)))))))

;; ---- back-edge return-route detour --------------------------------------

(deftest back-edge-detour-lifts-the-chip-beside-the-spine
  (testing ":tb — the chip is lifted to mid-height and the route leaves the
            source sideways to a lane off the spine"
    (let [parsed    (layout/project-definition door-cyclic-machine)
          back-e    (door-back-edge parsed)
          ;; locked centre (260, 25); alarming centre (260, 325); the chip sank below
          positions {(layout/node-id [:locked])          {:x 200 :y 0   :width 120 :height 50}
                     (layout/node-id [:alarming])        {:x 200 :y 300 :width 120 :height 50}
                     (projection/event-node-id back-e)   {:x 200 :y 400 :width 96  :height 34}}
          {:keys [event-pos in-points out-points]} (post-elk/back-edge-detour back-e positions :tb)
          in-elbow  (second in-points)]
      (is (<= 25 (+ (:y event-pos) 17) 325) "the chip centre sits between the endpoints")
      (is (= 325 (:y in-elbow)) "the __in elbow keeps the source's y")
      (is (not= 260 (:x in-elbow)) "and moves off the spine")
      (is (= 25 (:y (second out-points))) "the __out elbow keeps the target's y"))))

(deftest back-edge-detour-lr-mirrors-the-elbow
  (testing ":lr — the chip bows above the flow row and the elbows leave and
            re-enter VERTICALLY, never running along the row first"
    (let [parsed    (layout/project-definition door-cyclic-machine)
          back-e    (door-back-edge parsed)
          ;; locked centre (60, 225); alarming centre (360, 225); the chip sank right
          positions {(layout/node-id [:locked])          {:x 0   :y 200 :width 120 :height 50}
                     (layout/node-id [:alarming])        {:x 300 :y 200 :width 120 :height 50}
                     (projection/event-node-id back-e)   {:x 450 :y 200 :width 96  :height 34}}
          {:keys [in-points out-points]} (post-elk/back-edge-detour back-e positions :lr)
          chip      (last in-points)]
      (is (true? (post-elk/back-edge? back-e positions :lr)))
      (is (< (:y chip) 225) "the chip sits above the row")
      (is (<= 60 (:x chip) 360) "mid-width between the endpoints")
      (is (= {:x 360 :y (:y chip)} (second in-points)) "leaves the source vertically")
      (is (= {:x 60 :y (:y chip)} (second out-points)) "re-enters the target vertically"))))

(deftest reroute-back-edges-rewrites-positions-and-routes
  (let [parsed (layout/project-definition door-cyclic-machine)
        stub   (col-positions parsed)
        back-e (door-back-edge parsed)
        ev-id  (projection/event-node-id back-e)
        fwd-ev (projection/event-node-id
                 (edge-between parsed (layout/node-id [:locked]) (layout/node-id [:closed])))
        out    (post-elk/reroute-back-edges stub parsed :tb)]
    (is (< (get-in out [:positions ev-id :y]) (get-in stub [:positions ev-id :y]))
        "the sunk chip is lifted")
    (is (every? (:edge-points out) [(str (:id back-e) "__in") (str (:id back-e) "__out")])
        "both of the back-edge's segments get detour routes")
    (is (= (get-in stub [:positions fwd-ev]) (get-in out [:positions fwd-ev]))
        "a forward chip is untouched")))

(deftest reroute-back-edges-excludes-cross-hierarchy-candidates
  (testing "endpoints in different parents' frames are never compared, even when
            the raw numbers look sunk"
    (let [parsed (layout/project-definition nested-back-edge-machine)
          back-e (edge-between parsed (layout/node-id [:working :step2]) (layout/node-id [:idle]))
          stub   {:positions   {(layout/node-id [:idle])          {:x 200 :y 0   :width 120 :height 50}
                                (layout/node-id [:working :step2]) {:x 200 :y 200 :width 120 :height 50}
                                (projection/event-node-id back-e)  {:x 200 :y 300 :width 96  :height 34}}
                  :edge-points {}
                  :edge-labels {}}]
      (is (true? (post-elk/back-edge? back-e (:positions stub) :tb))
          "the naive geometric check would flag it")
      (is (= stub (post-elk/reroute-back-edges stub parsed :tb))))))

(deftest reroute-back-edges-nested-same-parent-writes-absolute-edge-points
  (testing ":edge-points are root-absolute, so a detour computed in the shared
            parent's frame is rebased by that parent's full ancestor origin
            (grouped 600,500 + root container 10,15)"
    (let [parsed (layout/project-definition compound-same-parent-back-edge-machine)
          a-id   (layout/node-id [:grouped :a])
          b-id   (layout/node-id [:grouped :b])
          back-e (edge-between parsed b-id a-id)
          out    (post-elk/reroute-back-edges
                   {:positions   {layout/root-container-id         {:x 10  :y 15  :width 800 :height 700}
                                  (layout/node-id [:grouped])       {:x 600 :y 500 :width 220 :height 320}
                                  a-id                              {:x 40  :y 20  :width 120 :height 50}
                                  b-id                              {:x 40  :y 120 :width 120 :height 50}
                                  (projection/event-node-id back-e) {:x 40  :y 260 :width 96  :height 34}}
                    :edge-points {}
                    :edge-labels {}}
                   parsed :tb)]
      (is (= {:x 710 :y 660} (first (get-in out [:edge-points (str (:id back-e) "__in")])))
          "the detour starts on b's absolute centre")
      (is (= {:x 710 :y 560} (peek (get-in out [:edge-points (str (:id back-e) "__out")])))
          "and ends on a's absolute centre"))))

;; ---- composing pass -----------------------------------------------------

(deftest apply-post-elk-runs-the-transpose-then-the-reroute
  (let [linear (layout/project-definition linear-machine)
        door   (layout/project-definition door-cyclic-machine)
        par    (layout/project-definition parallel-machine)
        par-stub (side-by-side-regions par)]
    (is (= (col-positions linear) (post-elk/apply-post-elk (col-positions linear) linear :tb))
        "identity when neither pattern is present")
    (is (= (post-elk/reroute-back-edges (col-positions door) door :tb)
           (post-elk/apply-post-elk (col-positions door) door :tb)))
    (is (= (post-elk/transpose-parallel-regions par-stub par)
           (post-elk/apply-post-elk par-stub par :tb)))))
