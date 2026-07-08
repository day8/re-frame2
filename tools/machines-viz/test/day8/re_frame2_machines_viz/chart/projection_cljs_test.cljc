(ns day8.re-frame2-machines-viz.chart.projection-cljs-test
  "Pure-data tests for the MachineChart projection layer (rf2-0gmwp).

  `chart.projection` is the central parsed-graph → xyflow nodes/edges
  projector plus the elk.js `children` shape + the edge-type chooser.
  It was extracted from `chart.cljs` (which `:require`s xyflow/elkjs
  and so is JVM-unloadable) precisely so this corpus can pin it at the
  cheap JVM layer instead of the slow browser-DOM layer.

  Fixtures lean on `chart.layout/project-definition` (itself pure +
  JVM-runnable) so the projection is exercised against the SAME parsed
  shape the live chart feeds it — no hand-mocked node maps drifting
  from the parser's contract.

  Dual-target via the `_cljs_test.cljc` extension — same pattern every
  machines-viz helper test uses."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- fixtures ----------------------------------------------------------

(def idle-loading
  "Flat machine with a plain `:on` transition + an `:after` timer + an
  `:always` eventless transition, so every transition kind (plain,
  timer, eventless) is represented in one parse."
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
  "A machine with a self-transition (:idle --ping--> :idle), which
  dissolves through an event-node (`state → event-node → state`) under
  the events-as-nodes paradigm."
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

(def door-cyclic-machine
  "rf2-ly51l — the door machine's topology shape (cyclic + a root-level
  :on fallback). The forward spine `locked → closed → open → alarming`
  loops back via `alarming → locked` (reset) AND a root `:door/audit →
  locked` fallback. Drives the initial-state model-order preference
  (`order-state-children`): `locked` (initial) must lead the children,
  the synthetic machine-root annotation sinks last."
  {:initial :locked
   :on      {:door/audit {:target :locked :action :record-audit}}
   :states  {:locked   {:on {:door/insert-coin :closed}}
             :closed   {:on {:door/push :open}}
             :open     {:on {:door/close   {:target :closed :guard :may-close?}
                             :door/hold    {:action :hold-open}
                             :door/trip    {:target :alarming :action :enter-alarm}}}
             :alarming {:on {:door/reset :locked}}}})

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

(defn- root-children
  "rf2-q129z8 — the EFFECTIVE top-level elk children: the `:children` of
  the synthetic ROOT-CONTAINER frame `->elk-children` now emits as the sole
  top-level child (the Stately-style named box wrapping the whole machine).
  Every pre-q129z8 top-level child (flat states, regions, the machine-root
  chip, top-level event-nodes) now nests one level deeper, under this frame;
  this helper unwraps it so the assertions read against the same effective
  top level they did before the frame landed.

  `elk-children` is the `(projection/->elk-children parsed …)` result vector
  (exactly one element: the root container)."
  [elk-children]
  (:children (first elk-children)))

(defn- root-child-by-id
  "rf2-q129z8 — pluck one effective-top-level elk child by id (an element
  of `root-children`)."
  [elk-children id]
  (first (filter #(= id (:id %)) (root-children elk-children))))

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

;; ---- edge :type (single canonical "transition") ------------------------
;;
;; rf2-dt5b1 — the `choose-edge-type` classifier was removed: under
;; events-as-nodes EVERY projected edge is the canonical `transition`
;; type (the `:after`-timer specifics ride the event-NODE, not a distinct
;; edge type). These pins assert the projector emits ONLY `transition`
;; edges across the parse arms `choose-edge-type` once classified (plain
;; `:on`, `:after`-timer, `:always` eventless).

(deftest every-projected-edge-is-transition-type
  (testing "rf2-dt5b1 — every edge a real parse emits projects as the
            canonical `transition` type (no `after` / `spawn` arms);
            `idle-loading` exercises plain `:on` + `:after` + `:always`."
    (let [graph (projection/xyflow-graph
                  (layout/project-definition idle-loading) {} {})]
      (is (seq (:edges graph)))
      (is (every? #{"transition"} (map :type (:edges graph)))
          "all projected edges are the single canonical transition type"))))

(deftest after-timer-rides-the-event-node-not-an-edge-type
  (testing "rf2-dt5b1 — an `:after`-timer's specifics ride the event-NODE
            (`:variant \"after\"` + `:afterMs`), NOT a distinct edge type;
            the structural edges around it are plain `transition` edges."
    (let [graph      (projection/xyflow-graph
                       (layout/project-definition idle-loading) {} {})
          after-node (first (filter #(= "after" (:variant (:data %)))
                                    (:nodes graph)))]
      (is (some? after-node) "the :after timer projects as an event-node")
      (is (= 1000 (:afterMs (:data after-node))))
      (is (every? #{"transition"} (map :type (:edges graph)))))))

;; ---- xyflow-graph node :type dispatch (G1) -----------------------------

(deftest xyflow-graph-state-node-type
  (testing "a leaf state projects as a `state`-type node"
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          idle   (node-by-id graph (layout/node-id [:idle]))]
      (is (= "state" (:type idle))))))

(deftest xyflow-graph-compound-node-type
  (testing "a compound parent projects as a `compound`-type node; its
            leaf children stay `state`"
    (let [parsed (layout/project-definition compound-machine)
          graph  (projection/xyflow-graph parsed {} {})
          parent (node-by-id graph (layout/node-id [:authenticated]))
          child  (node-by-id graph (layout/node-id [:authenticated :browsing]))]
      (is (= "compound" (:type parent)))
      (is (= "state" (:type child))))))

(deftest xyflow-graph-region-node-type
  (testing "a parallel-region container projects as a
            `parallel-region`-type node"
    (let [parsed (layout/project-definition parallel-machine)
          graph  (projection/xyflow-graph parsed {} {})
          region (node-by-id graph (layout/region-node-id :audio))]
      (is (= "parallel-region" (:type region))))))

;; ---- history pseudo-state projection (rf2-m285a) -----------------------

(def shallow-history-machine
  "rf2-m285a — a compound with a SHALLOW `:type :history` pseudo-state
  targeted by an outer transition."
  {:initial :off
   :states  {:off    {:on {:resume [:player :hist]}}
             :player {:initial :stopped
                      :states  {:stopped {:on {:play :playing}}
                                :playing {:on {:stop :stopped}}
                                :hist    {:type :history :deep? false}}
                      :on      {:power-off :off}}}})

(def deep-history-machine
  (assoc-in shallow-history-machine [:states :player :states :hist]
            {:type :history :deep? true :default-target :playing}))

(deftest xyflow-graph-history-node-type
  (testing "rf2-m285a — a `:type :history` pseudo-state projects as a
            `history-marker`-type node, NOT a `state`"
    (let [parsed (layout/project-definition shallow-history-machine)
          graph  (projection/xyflow-graph parsed {} {})
          hist   (node-by-id graph (layout/node-id [:player :hist]))]
      (is (some? hist) "the history node is present in the projection")
      (is (= "history-marker" (:type hist))
          "it projects to the registered history-marker node-type")
      (is (false? (:deep (:data hist)))
          "shallow history threads :deep false to the renderer"))))

(deftest xyflow-graph-deep-history-node
  (testing "rf2-m285a — a DEEP history pseudo-state threads :deep true"
    (let [parsed (layout/project-definition deep-history-machine)
          graph  (projection/xyflow-graph parsed {} {})
          hist   (node-by-id graph (layout/node-id [:player :hist]))]
      (is (= "history-marker" (:type hist)))
      (is (true? (:deep (:data hist)))))))

(deftest history-node-is-not-occupiable
  (testing "rf2-m285a — a history pseudo-state is NEVER occupiable: not
            initial / final / compound, and not an on-state-click target"
    (let [parsed (layout/project-definition shallow-history-machine)
          ;; the parsed node (pre-projection) carries the pseudo-state flags
          hist-n (first (filter #(= [:player :hist] (:path %)) (:nodes parsed)))]
      (is (true? (:history? hist-n)))
      (is (false? (:initial? hist-n)) "never initial")
      (is (false? (:final? hist-n))   "never final")
      (is (false? (:compound? hist-n)) "never compound"))
    (let [graph (projection/xyflow-graph (layout/project-definition shallow-history-machine) {} {})
          hist  (node-by-id graph (layout/node-id [:player :hist]))]
      (is (nil? (:onClick (:data hist)))
          "a history marker carries no on-state-click handler"))))

(deftest history-node-keeps-incoming-edge
  (testing "rf2-m285a — a transition targeting the history pseudo-state keeps
            its incoming edge (the marker is a legitimate transition target),
            sourced from the off state's event node"
    (let [parsed (layout/project-definition shallow-history-machine)
          ;; one parsed edge :resume from :off → [:player :hist]
          resume (first (filter #(= [:player :hist] (:to-path %)) (:edges parsed)))]
      (is (some? resume) "the :resume → :hist edge is projected")
      (is (= (layout/node-id [:player :hist]) (:target resume))
          "the edge target is the history marker's node id"))))

;; ---- error-final KIND threading + composition (rf2-b4loj) --------------
;;
;; An `:error?` final (Spec 005 §:final?) is a re-frame2 EXTENSION routing
;; the spawning parent's `:on-error` (vs `:on-done`). The projector threads
;; the terminal KIND onto `:data {:errorFinal ...}` so the renderer paints
;; the error-hue OUTER RING for error terminals while a success final keeps
;; the quiet runtime-coupled ring. NOT XState/Stately parity — re-frame2
;; semantic clarity. `:output-key` is deliberately OUT of scope here.

(def success-and-error-finals
  "Two terminals of distinct KIND: a plain success final + an `:error?`
  error final."
  {:initial :running
   :states  {:running {:on {:ok :ok :boom :boom}}
             :ok      {:final? true}
             :boom    {:final? true :error? true}}})

(deftest xyflow-graph-threads-error-final-kind
  (testing "rf2-b4loj — :data :errorFinal is true ONLY for an :error?
            final; a success final and a non-final node carry false"
    (let [parsed  (layout/project-definition success-and-error-finals)
          graph   (projection/xyflow-graph parsed {} {})
          running (node-by-id graph (layout/node-id [:running]))
          ok      (node-by-id graph (layout/node-id [:ok]))
          boom    (node-by-id graph (layout/node-id [:boom]))]
      ;; both terminals are :final; only the error final is :errorFinal.
      (is (true?  (:final (:data ok))))
      (is (true?  (:final (:data boom))))
      (is (false? (:errorFinal (:data running))) "non-final → false")
      (is (false? (:errorFinal (:data ok)))      "success final → false")
      (is (true?  (:errorFinal (:data boom)))    "error final → true"))))

(deftest xyflow-graph-active-error-final-composes
  (testing "rf2-b4loj — an :error? final that is ALSO the current state
            carries BOTH :active true (drives the runtime main-border) AND
            :errorFinal true (drives the static error-hue ring) on its
            :data — the two signals compose, neither clobbers the other"
    (let [parsed (layout/project-definition success-and-error-finals)
          hi     (layout/node-id [:boom])
          graph  (projection/xyflow-graph parsed {} {:highlight-ids #{hi}})
          boom   (node-by-id graph hi)]
      (is (true? (:active     (:data boom))) "the error final is active")
      (is (true? (:errorFinal (:data boom))) "AND it is an error terminal")
      (is (true? (:final      (:data boom)))))))

;; ---- xyflow-graph :on-state-click threading (rf2-34ff3) ----------------
;;
;; rf2-34ff3 (A-PRIME) — `:on-state-click` is threaded onto the `:data`
;; `:onClick` of REAL statechart-state nodes only: LEAF states + COMPOUND
;; states. The synthetic machine-root chip and parallel-region containers
;; are NOT click targets, so the projector must NOT thread `:onClick` onto
;; their `:data` — otherwise a node would carry an `:onClick` its renderer
;; never consumes (the inverse of the original half-wired bug, where the
;; compound-node ignored a threaded `:onClick`).

(deftest xyflow-graph-threads-on-click-to-leaf-and-compound-only
  (testing "rf2-34ff3 — `:onClick` rides leaf + compound `:data`; the
            machine-root chip + region containers carry NO `:onClick`."
    (let [cb (fn [_path] :clicked)]
      ;; Compound + leaf machine: both the compound parent and its leaf
      ;; children carry the callback.
      (let [parsed (layout/project-definition compound-machine)
            graph  (projection/xyflow-graph parsed {} {:on-state-click cb})
            parent (node-by-id graph (layout/node-id [:authenticated]))
            child  (node-by-id graph (layout/node-id [:authenticated :browsing]))
            leaf   (node-by-id graph (layout/node-id [:unauth]))]
        (is (= "compound" (:type parent)))
        (is (= cb (:onClick (:data parent)))
            "compound state carries :onClick (its title strip is clickable)")
        (is (= cb (:onClick (:data child)))
            "a nested leaf carries :onClick")
        (is (= cb (:onClick (:data leaf)))
            "a top-level leaf carries :onClick"))
      ;; Parallel machine: region containers must NOT carry :onClick;
      ;; their leaf children still do.
      (let [parsed (layout/project-definition parallel-machine)
            graph  (projection/xyflow-graph parsed {} {:on-state-click cb})
            region (node-by-id graph (layout/region-node-id :audio))
            muted  (node-by-id graph (layout/region-scoped-id :audio [:muted]))]
        (is (= "parallel-region" (:type region)))
        (is (not (contains? (:data region) :onClick))
            "a parallel-region container carries NO :onClick (not a click target)")
        (is (= cb (:onClick (:data muted)))
            "a leaf inside a region still carries :onClick"))
      ;; Machine-level :on machine: the synthetic machine-root chip must
      ;; NOT carry :onClick; the real states still do.
      (let [parsed (layout/project-definition machine-level-on-machine)
            graph  (projection/xyflow-graph parsed {} {:on-state-click cb})
            root   (first (filter #(= "machine-root" (:type %)) (:nodes graph)))
            a      (node-by-id graph (layout/node-id [:a]))]
        (is (some? root) "the machine-level :on fallback projects a machine-root chip")
        (is (not (contains? (:data root) :onClick))
            "the synthetic machine-root chip carries NO :onClick (not a click target)")
        (is (= cb (:onClick (:data a)))
            "a real leaf state still carries :onClick")))))

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
    (let [parsed (layout/project-definition parallel-machine)
          graph  (projection/xyflow-graph parsed {} {})
          region (node-by-id graph (layout/region-node-id :audio))
          muted  (node-by-id graph (layout/region-scoped-id :audio [:muted]))]
      (is (= (layout/region-node-id :audio) (:parentId muted)))
      (is (= "parent" (:extent muted)))
      (is (nil? (:parentId region)) "region container is not nested")
      (is (nil? (:extent region))))))

(deftest xyflow-graph-region-children-do-not-emit-pre-v12-parent-node
  (testing "rf2-xh1lm — the projector emits the v12 `:parentId` shape
            ONLY; the pre-v12 `:parentNode` key MUST NOT appear (xyflow
            v12 silently ignores it, hiding the bug behind a green test
            suite — the regression mode this guards)"
    (let [parsed (layout/project-definition parallel-machine)
          graph  (projection/xyflow-graph parsed {} {})]
      (doseq [n (:nodes graph)]
        (is (not (contains? n :parentNode))
            (str "node " (:id n) " must not carry the dead :parentNode key"))))))

(deftest xyflow-graph-flat-state-nests-under-root-container
  (testing "rf2-q129z8 — a flat machine's top-level state now nests UNDER
            the synthetic ROOT-CONTAINER frame (the Stately-style named box
            wrapping the whole machine): it carries `:parentId` ==
            `root-container-id` + `:extent \"parent\"`, exactly as a compound
            substate does. The frame itself is the sole node with no parent."
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          idle   (node-by-id graph (layout/node-id [:idle]))
          frame  (node-by-id graph layout/root-container-id)]
      (is (some? frame) "the root-container frame node is projected")
      (is (= "root-container" (:type frame)))
      (is (nil? (:parentId frame)) "the frame itself has no parent")
      (is (= layout/root-container-id (:parentId idle)))
      (is (= "parent" (:extent idle))))))

;; ---- xyflow-graph parent-before-child sort (G1) ------------------------

(deftest xyflow-graph-sorts-regions-before-children
  (testing "rf2-lkwev — xyflow requires a parentId target to appear in
            the nodes array BEFORE any node that references it (v12's
            `adoptUserNodes` warns otherwise). Every region container
            must precede its first child in the projected order."
    (let [parsed   (layout/project-definition parallel-machine)
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
    (let [parsed   (layout/project-definition parallel-machine)
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
  (testing "the node whose id ∈ highlight-ids gets `:active true`; all
            others `:active false`"
    (let [parsed   (layout/project-definition idle-loading)
          hi       (layout/node-id [:loading])
          graph    (projection/xyflow-graph parsed {} {:highlight-ids #{hi}})
          loading  (node-by-id graph hi)
          idle     (node-by-id graph (layout/node-id [:idle]))]
      (is (true?  (:active (:data loading))))
      (is (false? (:active (:data idle)))))))

(deftest xyflow-graph-from-and-to-highlight-flags
  (testing "from-highlight-id / to-highlight-id flip the matching
            node's `:fromHighlight` / `:toHighlight` flags"
    (let [parsed (layout/project-definition idle-loading)
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
    (let [parsed   (layout/project-definition idle-loading)
          hi       (layout/node-id [:loading])
          sim      (projection/xyflow-graph parsed {} {:highlight-ids #{hi} :sim? true})
          no-sim   (projection/xyflow-graph parsed {} {:highlight-ids #{hi} :sim? false})
          inactive (projection/xyflow-graph parsed {} {:highlight-ids #{hi} :sim? true})]
      (is (true?  (:sim (:data (node-by-id sim hi)))))
      (is (false? (:sim (:data (node-by-id no-sim hi)))))
      (is (false? (:sim (:data (node-by-id inactive (layout/node-id [:idle])))))))))

;; ---- xyflow-graph multi-active highlight (rf2-g2svr, G1) ----------------
;;
;; A PARALLEL machine's snapshot `:state` is a region-map — N
;; simultaneously-active leaves (one per region). `:highlight-ids` (a
;; SET) marks EVERY active leaf `:active` so the chart lights up all
;; regions at once (the §1.2 parity bar). A flat / compound snapshot
;; resolves to a singleton set, so `:highlight-ids` is the single
;; active-state option.

(deftest xyflow-graph-highlight-ids-marks-every-active-leaf
  (testing "rf2-g2svr (THE PARITY CAPABILITY) — passing a SET of two
            region-leaf ids marks BOTH region states `:active`
            simultaneously (parallel multi-active highlight)"
    (let [parsed     (layout/project-definition parallel-machine)
          ;; rf2-wnzha — region states are region-scoped ids now.
          playing-id (layout/region-scoped-id :audio [:playing])
          shown-id   (layout/region-scoped-id :video [:shown])
          muted-id   (layout/region-scoped-id :audio [:muted])
          hidden-id  (layout/region-scoped-id :video [:hidden])
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
    (let [parsed   (layout/project-definition parallel-machine)
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
      (is (= #{(layout/region-scoped-id :audio [:playing])
               (layout/region-scoped-id :video [:shown])} active-states)
          "exactly the two active region leaves are marked active"))))

(deftest xyflow-graph-flat-snapshot-singleton-highlight-ids
  (testing "rf2-g2svr — a flat/compound snapshot resolves to a singleton
            `:highlight-ids` set, marking exactly the one active leaf"
    (let [parsed  (layout/project-definition idle-loading)
          hi      (layout/node-id [:loading])
          graph   (projection/xyflow-graph parsed {} {:highlight-ids #{hi}})
          loading (node-by-id graph hi)
          idle    (node-by-id graph (layout/node-id [:idle]))]
      (is (true?  (:active (:data loading))))
      (is (false? (:active (:data idle)))))))

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
    (let [parsed     (layout/project-definition parallel-machine)
          playing-id (layout/region-scoped-id :audio [:playing])
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
    (let [parsed     (layout/project-definition parallel-machine)
          playing-id (layout/region-scoped-id :audio [:playing]) ; :audio leaf, active
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
    (let [parsed   (layout/project-definition parallel-machine)
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
    (let [parsed      (layout/project-definition compound-machine)
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
    (let [parsed   (layout/project-definition nested-compound-machine)
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
    (let [parsed   (layout/project-definition idle-loading)
          hi       (layout/node-id [:loading])
          graph    (projection/xyflow-graph parsed {} {:highlight-ids #{hi}})
          flagged  (filter #(contains? (:data %) :active) (:nodes graph))
          actives  (set (map :id (filter #(:active (:data %)) flagged)))]
      (is (= #{hi} actives)
          "exactly the highlighted leaf is active — no container chrome leaks"))))

(deftest xyflow-graph-inactive-compound-container-stays-inactive
  (testing "rf2-80rm2 — a compound with NO active descendant keeps its
            container `:active false` (no highlight → no chrome)"
    (let [parsed (layout/project-definition compound-machine)
          graph  (projection/xyflow-graph parsed {} {})
          authed (node-by-id graph (layout/node-id [:authenticated]))]
      (is (false? (:active (:data authed)))
          "no highlight → the compound container is inactive"))))

(deftest xyflow-graph-no-highlight-leaves-all-inactive
  (testing "rf2-g2svr — no `:highlight-ids` → no state/region node is
            active (empty set, never nil-comparison surprises).
            Initial-marker nodes carry no
            `:active` key at all (they are not states), so the check
            scopes to nodes that carry the flag."
    (let [parsed (layout/project-definition parallel-machine)
          graph  (projection/xyflow-graph parsed {} {})
          flagged (filter #(contains? (:data %) :active) (:nodes graph))]
      (is (seq flagged) "fixture has state/region nodes carrying :active")
      (is (every? #(false? (:active (:data %))) flagged)))))

(deftest xyflow-graph-multi-active-edges-active-in-each-region
  (testing "rf2-g2svr + rf2-vd3q1i — with N active leaves, an edge SOURCED
            FROM any active leaf is `:active`. Each region's outgoing edge
            lights up independently (orthogonality preserved). Here
            :playing (audio) sources `mute` (playing→muted) and :shown
            (video) sources `hide` (shown→hidden)."
    (let [parsed     (layout/project-definition parallel-machine)
          ;; rf2-wnzha — region states are region-scoped ids now.
          playing-id (layout/region-scoped-id :audio [:playing])
          shown-id   (layout/region-scoped-id :video [:shown])
          ids        #{playing-id shown-id}
          graph      (projection/xyflow-graph parsed {} {:highlight-ids ids})
          active-e   (filter #(:active (:data %)) (:edges graph))
          ;; edges sourced from :playing (audio) and :shown (video)
          regions    (set (map (fn [e]
                                 (cond
                                   (or (= (:source e) playing-id)
                                       (= (:target e) playing-id)) :audio
                                   (or (= (:source e) shown-id)
                                       (= (:target e) shown-id)) :video
                                   :else :other))
                               active-e))]
      (is (seq active-e) "at least one edge is active")
      (is (contains? regions :audio) "an :audio-region edge is active")
      (is (contains? regions :video) "a :video-region edge is active"))))

(deftest xyflow-graph-edge-active-only-when-source-highlighted
  (testing "rf2-vd3q1i — an edge is `:active` ONLY when its SOURCE is the
            highlighted (active) state. An INCOMING edge whose only active
            endpoint is its TARGET is NOT lit — `from-active?` is
            source-active, not incident-to-active."
    ;; idle --start--> loading. Highlight the TARGET (:loading): the
    ;; incoming idle→loading edge must stay quiet (its source :idle is
    ;; not active). Highlight the SOURCE (:idle): the same edge lights.
    (let [parsed       (layout/project-definition idle-loading)
          idle-id      (layout/node-id [:idle])
          loading-id   (layout/node-id [:loading])
          edge-from-idle (fn [graph]
                           ;; the __out half (event-node → loading) carries
                           ;; the canonical edge id with a "__out" suffix;
                           ;; either half reflects the same from-active? flag.
                           (first (filter #(= (:source %) idle-id)
                                          (:edges graph))))
          ;; TARGET active → incoming edge stays quiet.
          tgt-active   (projection/xyflow-graph parsed {} {:highlight-ids #{loading-id}})
          ;; SOURCE active → outgoing edge lights.
          src-active   (projection/xyflow-graph parsed {} {:highlight-ids #{idle-id}})]
      (is (false? (:active (:data (edge-from-idle tgt-active))))
          "incoming edge (only its target is active) is NOT lit")
      (is (true? (:active (:data (edge-from-idle src-active))))
          "outgoing edge (its source is active) IS lit"))))

(deftest xyflow-graph-edge-focused-when-source-and-target-match-lens
  (testing "rf2-qo5xy — events-as-nodes paradigm: an inbound edge
            (source-state → event-node) is `:focused` when the parsed
            transition's source/target match the from/to lens. The
            paired outbound edge (event-node → target-state) gets the
            same focused flag so the WHOLE traversal lights up."
    (let [parsed (layout/project-definition idle-loading)
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
    (let [parsed (layout/project-definition idle-loading)
          from   (layout/node-id [:idle])
          graph  (projection/xyflow-graph parsed {} {:from-highlight-id from})]
      (is (every? false? (map (comp :focused :data) (:edges graph)))))))

;; ---- xyflow-graph arrowheads (rf2-5qsxo) -------------------------------

(deftest xyflow-graph-edge-requests-arrowclosed-marker-end
  (testing "rf2-5qsxo — every transition edge requests an `arrowclosed`
            markerEnd so React Flow draws an arrowhead at the target end
            (the custom edge component forwards the resolved url to its
            BaseEdge)."
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})]
      (is (seq (:edges graph)))
      (is (every? #(= "arrowclosed" (:type (:markerEnd %))) (:edges graph))
          "every edge carries an arrowclosed markerEnd")
      (is (every? #(string? (:color (:markerEnd %))) (:edges graph))
          "the marker colour resolves to a token string"))))

(deftest xyflow-graph-marker-end-colour-tracks-active-edge
  (testing "rf2-5qsxo — the arrowhead colour tracks the edge stroke: an
            edge SOURCED FROM the highlighted node uses the active (cyan)
            colour, idle edges use the default border colour, so the
            marker reads as part of the same line."
    ;; rf2-vd3q1i — highlight `:idle` (a SOURCE state): its outgoing
    ;; idle→start→loading edge lights (source-active), while the rest of
    ;; the graph stays idle, giving a clean active/inactive split.
    (let [parsed   (layout/project-definition idle-loading)
          hi       (layout/node-id [:idle])
          graph    (projection/xyflow-graph parsed {} {:highlight-ids #{hi}})
          active   (first (filter #(:active (:data %)) (:edges graph)))
          inactive (first (filter #(not (:active (:data %))) (:edges graph)))]
      (is (some? active) "fixture has at least one active edge")
      (is (some? inactive) "fixture has at least one idle edge")
      (is (not= (:color (:markerEnd active))
                (:color (:markerEnd inactive)))
          "active vs idle arrowheads are distinct colours"))))

;; ---- rf2-az6e2 — structured visual grammar data -----------------------

(deftest xyflow-graph-threads-palette-onto-every-node-and-edge
  (testing "rf2-az6e2 — the resolved chart-semantic token map (`:palette`
            option) is threaded onto EVERY node + edge `:data {:palette}`
            so the renderers paint the active theme. Default (no
            `:palette`) resolves the dark chart-tokens."
    (let [parsed (layout/project-definition idle-loading)
          ct     (tokens/chart-tokens tokens/light-palette)
          graph  (projection/xyflow-graph parsed {} {:palette ct})]
      (is (seq (:nodes graph)))
      (is (every? #(= ct (:palette (:data %))) (:nodes graph))
          "every node :data carries the threaded palette")
      (is (every? #(= ct (:palette (:data %))) (:edges graph))
          "every edge :data carries the threaded palette"))))

(deftest xyflow-graph-palette-defaults-to-dark-chart-tokens
  (testing "rf2-az6e2 — a caller that omits `:palette` gets the dark
            chart-tokens map on every node/edge (theme-less default)."
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          dark   (tokens/chart-tokens tokens/dark-palette)]
      (is (every? #(= dark (:palette (:data %))) (:nodes graph)))
      (is (every? #(= dark (:palette (:data %))) (:edges graph))))))

(deftest xyflow-graph-event-route-arrowhead-split
  (testing "rf2-az6e2 — the two-edge event route reads as ONE transition:
            the source→event (`__in`) segment carries the SMALLER quiet
            arrowhead (`:arrow-width-quiet`) + the quiet-segment flag,
            while the event→target (`__out`) segment carries the PRIMARY
            arrowhead (`:arrow-width`). Both sizes ride the resolved
            density map (trimmed toward Stately's small/thin heads); the
            head sizes are asserted off the constants, not baked literals.
            `idle --start--> loading` is a plain external transition so
            both halves exist."
    (let [parsed  (layout/project-definition idle-loading)
          graph   (projection/xyflow-graph parsed {} {})
          ;; the plain :on transition edge id
          on-edge (first (filter #(= :start (:event %)) (:edges parsed)))
          in-e    (inbound-edge-for graph (:id on-edge))
          out-e   (outbound-edge-for graph (:id on-edge))]
      (is (some? in-e) "inbound (__in) segment exists")
      (is (some? out-e) "outbound (__out) segment exists")
      (is (= (:arrow-width-quiet vc/chart) (:width (:markerEnd in-e)))
          "quiet source→event arrowhead is the small quiet head")
      (is (= (:arrow-width vc/chart) (:width (:markerEnd out-e)))
          "primary event→target arrowhead is the primary head")
      (is (< (:width (:markerEnd in-e)) (:width (:markerEnd out-e)))
          "quiet __in head is smaller than the primary __out head")
      (is (true? (:quietSegment (:data in-e)))
          "the inbound half is flagged the quiet segment")
      (is (false? (:quietSegment (:data out-e)))
          "the outbound half is the primary segment"))))

(deftest xyflow-graph-internal-transition-no-outbound-segment
  (testing "rf2-az6e2 — an internal / action-only transition (no
            `:target`) emits the inbound terminal segment but NO outgoing
            target segment, so the event chip reads as a terminal route."
    (let [parsed   (layout/project-definition internal-self-machine)
          graph    (projection/xyflow-graph parsed {} {})
          internal (first (filter :internal? (:edges parsed)))]
      (when internal
        (is (some? (inbound-edge-for graph (:id internal)))
            "internal transition keeps the source→event segment")
        (is (nil? (outbound-edge-for graph (:id internal)))
            "internal transition has NO event→target segment")))))

(def internal-after-always-machine
  "rf2-mnp93.4 — a state carrying an internal (action-only, no `:target`)
  `:after` AND `:always`. Both project as terminal event-nodes (no outbound
  segment), exactly like the internal `:on` form — pre-fix BOTH were silently
  dropped by the chart's `:after` / `:always` branches."
  {:initial :a
   :states  {:a {:after  {1000 {:action :timeout-log}}
                 :always [{:action :poll}]}}})

(deftest xyflow-graph-internal-after-always-no-outbound-segment
  (testing "rf2-mnp93.4 — an internal (action-only) :after / :always
            projects the inbound terminal segment but NO outbound segment,
            consistent with the internal :on form (pre-fix both were dropped
            entirely by the chart parse)"
    (let [parsed (layout/project-definition internal-after-always-machine)
          graph  (projection/xyflow-graph parsed {} {})
          aft    (first (filter #(and (:internal? %) (:after %)) (:edges parsed)))
          alw    (first (filter #(and (:internal? %) (:always? %)) (:edges parsed)))]
      (is (some? aft) "the internal :after edge is parsed (not dropped)")
      (is (some? alw) "the internal :always edge is parsed (not dropped)")
      ;; both keep the inbound terminal segment + an event-node, and have NO
      ;; outbound target segment (terminal route — the action-only chip).
      (is (some? (event-node-for graph (:id aft))) "internal :after gets an event-node")
      (is (some? (inbound-edge-for graph (:id aft))) "internal :after keeps source→event")
      (is (nil? (outbound-edge-for graph (:id aft))) "internal :after has NO event→target")
      (is (some? (event-node-for graph (:id alw))) "internal :always gets an event-node")
      (is (some? (inbound-edge-for graph (:id alw))) "internal :always keeps source→event")
      (is (nil? (outbound-edge-for graph (:id alw))) "internal :always has NO event→target")
      ;; the event-node carries the variant (after / always) AND the internal flag.
      (is (= "after" (:variant (:data (event-node-for graph (:id aft))))))
      (is (true? (:internal (:data (event-node-for graph (:id aft))))))
      (is (= "always" (:variant (:data (event-node-for graph (:id alw))))))
      (is (true? (:internal (:data (event-node-for graph (:id alw)))))))))

;; ---- xyflow-graph misc payload + style ---------------------------------

(deftest xyflow-graph-region-style-from-measured-position
  (testing "a region container's `:style {:width :height}` comes from
            its measured position entry"
    (let [parsed    (layout/project-definition parallel-machine)
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
    (let [parsed    (layout/project-definition compound-machine)
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
    (let [parsed    (layout/project-definition compound-machine)
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
    (let [parsed    (layout/project-definition idle-loading)
          idle-id   (layout/node-id [:idle])
          positions {idle-id {:x 0 :y 0 :width 200 :height 60}}
          graph     (projection/xyflow-graph parsed positions {})
          idle      (node-by-id graph idle-id)]
      (is (nil? (:style idle))))))

(deftest xyflow-graph-position-defaults-to-origin
  (testing "a node with no entry in the positions map defaults to
            {:x 0 :y 0} (the pre-layout placeholder)"
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          idle   (node-by-id graph (layout/node-id [:idle]))]
      (is (= {:x 0 :y 0} (:position idle))))))

(deftest xyflow-graph-event-node-carries-after-ms-and-event-label
  (testing "rf2-qo5xy — events-as-nodes paradigm: the event-node
            (not the edge) carries the `:afterMs` + the visible event
            label. The `⌚`-prefixed segment (from chart.layout/event-
            segment) rides on the event-node's `:eventLabel`."
    (let [parsed     (layout/project-definition idle-loading)
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
    (let [parsed     (layout/project-definition idle-loading)
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
          parsed (layout/project-definition m)
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
    (let [parsed   (layout/project-definition idle-loading)
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
    (let [parsed (layout/project-definition parallel-machine)
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
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {:chart vc/chart-compact})]
      (is (seq (:nodes graph)))
      (is (every? #(= vc/chart-compact (:chart (:data %))) (:nodes graph))
          "compact density threads chart-compact onto every node"))))

(deftest xyflow-graph-threads-chart-constants-onto-edges
  (testing "rf2-k647w — the resolved `:chart` map rides on EVERY edge's
            `:data` so the edge label typography tracks the density"
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {:chart vc/chart-cosy})]
      (is (seq (:edges graph)))
      (is (every? #(= vc/chart-cosy (:chart (:data %))) (:edges graph))
          "cosy density threads chart-cosy onto every edge"))))

(deftest xyflow-graph-chart-defaults-to-regular
  (testing "rf2-k647w — omitting `:chart` (the JVM tests, a density-less
            caller) defaults to `chart-regular` so the regular density
            stays pixel-identical to pre-rf2-k647w"
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          idle   (node-by-id graph (layout/node-id [:idle]))]
      (is (= vc/chart-regular (:chart (:data idle)))))))

(deftest xyflow-graph-density-changes-threaded-constants
  (testing "rf2-k647w — switching the density threads a DIFFERENT
            constants map: the regular projection's state-title font
            size differs from the compact projection's, proving
            `:density` actually changes what the renderer paints.

            rf2-so5b0 → rf2-dt5b1 — historical assertions on
            `:tag-pill-height` (then `:state-label-px`) walk to
            `:state-title-px`: the tag-pill family retired with the
            visible pill row, and `:state-label-px` was removed
            (rf2-dt5b1, unread — the state-node label rides
            `:state-title-px` under the rf2-az6e2 structured grammar)."
    (let [parsed   (layout/project-definition idle-loading)
          idle-id  (layout/node-id [:idle])
          regular  (-> (projection/xyflow-graph parsed {} {:chart vc/chart-regular})
                       (node-by-id idle-id) :data :chart)
          compact  (-> (projection/xyflow-graph parsed {} {:chart vc/chart-compact})
                       (node-by-id idle-id) :data :chart)]
      (is (not= (:state-title-px regular) (:state-title-px compact)))
      (is (= 13 (:state-title-px regular)))
      (is (= 11 (:state-title-px compact)))
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
    (let [parsed   (layout/project-definition idle-loading)
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
    (let [parsed (layout/project-definition idle-loading)
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
    (let [parsed   (layout/project-definition idle-loading)
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
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})]
      (is (every? #(nil? (:onClick (:data %))) (:edges graph))))))

;; ---- ->elk-children (G3) -----------------------------------------------

(deftest elk-children-flat-is-state-plus-event-nodes
  (testing "rf2-qo5xy — a flat machine projects one elk child per
            parsed state node PLUS one synthetic event-node per parsed
            transition (events-as-nodes paradigm). All children carry
            id + width/height + label.

            rf2-q129z8 — these now nest UNDER the synthetic ROOT-CONTAINER
            frame (the sole top-level child), so the count is taken against
            `root-children` (the frame's children) — and the parsed state
            count excludes the synthetic root container itself."
    (let [parsed   (layout/project-definition idle-loading)
          children (root-children (projection/->elk-children parsed))
          n-states (count (remove :root-container? (:nodes parsed)))
          n-events (count (:edges parsed))]
      (is (= (+ n-states n-events) (count children))
          "states + events = total children inside the root frame")
      (is (every? :id children))
      (is (every? #(pos? (:width %)) children))
      (is (every? #(pos? (:height %)) children)))))

(deftest elk-children-compound-uses-compound-floor
  (testing "a compound node gets the compound size floor; a leaf gets
            the state floor (rf2-q129z8 — read inside the root frame)"
    (let [parsed   (layout/project-definition compound-machine)
          children (root-children (projection/->elk-children parsed))
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
    (let [parsed   (layout/project-definition parallel-machine)
          children (root-children (projection/->elk-children parsed))
          regions  (filter #(re-find #"^region__" (:id %)) children)]
      (is (= 2 (count regions))
          "two regions land as the root frame's structural children")
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
            strip the parallel-region-node paints (rf2-q129z8 — regions
            now sit inside the root frame)"
    (let [parsed   (layout/project-definition parallel-machine)
          children (root-children (projection/->elk-children parsed))]
      (is (every? #(= "layered" (get-in % [:layoutOptions "elk.algorithm"]))
                  children))
      (is (every? #(re-find #"top=" (get-in % [:layoutOptions "elk.padding"]))
                  children)))))

;; ---- rf2-8q5pt: container elk.padding is density-derived ----------------
;;
;; The bug: the container `elk.padding` was a regular-only literal
;; `[top=44,left=16,bottom=16,right=16]`. But `:container-title-height`
;; and `:container-body-pad` are density-dependent, so compact/cosy
;; reserved the wrong header gap (children crowded or over-spaced the
;; title strip). The fix derives every side from the active density's
;; constants via `container-elk-padding`; these pins make the drift
;; visible to CI (the old "top= is merely present" assertion above could
;; not see it).

(deftest container-elk-padding-derives-from-density-constants
  (testing "rf2-8q5pt — top clears the title strip PLUS a body-pad band;
            sides are the body-pad inset, all density-derived"
    (doseq [[density vc-map] [[:compact vc/chart-compact]
                              [:regular vc/chart-regular]
                              [:cosy    vc/chart-cosy]]]
      (let [{:keys [container-title-height container-body-pad]} vc-map
            expected (str "[top="    (+ container-title-height container-body-pad)
                          ",left="   container-body-pad
                          ",bottom=" container-body-pad
                          ",right="  container-body-pad "]")]
        (is (= expected (projection/container-elk-padding vc-map))
            (str "padding tracks the " density " density constants"))))))

(deftest container-elk-padding-defaults-to-regular
  (testing "rf2-8q5pt — the nil-arity (and a nil chart-vc) fall back to
            the regular density"
    (is (= (projection/container-elk-padding vc/chart-regular)
           (projection/container-elk-padding)))
    ;; Regular: title strip 26 + body-pad 14 = 40 top; 14 on each side.
    (is (= "[top=40,left=14,bottom=14,right=14]"
           (projection/container-elk-padding vc/chart-regular)))))

(deftest elk-children-padding-tracks-threaded-density
  (testing "rf2-8q5pt — `->elk-children` threads `chart-vc` into every
            container's elk.padding so a compact chart reserves a SMALLER
            header gap than a cosy chart (was a fixed literal for both).
            rf2-lxk3h3 — the two parallel REGIONS each hold an initial
            substate, so they take the LEFT-widened initial-marker variant;
            the assertion compares against that variant per density."
    (let [parsed       (layout/project-definition parallel-machine)
          ;; rf2-lxk3h3 — the regions (which hold initial substates) carry
          ;; the LEFT-widened padding; read against `root-children` so the
          ;; assertion addresses the region containers, not the outer frame.
          compact-kids (root-children
                         (projection/->elk-children parsed nil vc/chart-compact))
          cosy-kids    (root-children
                         (projection/->elk-children parsed nil vc/chart-cosy))
          regions-of   (fn [kids] (filter #(re-find #"^region__" (:id %)) kids))
          pad-of       (fn [child] (get-in child [:layoutOptions "elk.padding"]))]
      (is (every? #(= (projection/container-elk-padding vc/chart-compact true)
                      (pad-of %))
                  (regions-of compact-kids))
          "compact region containers use the compact-density padding")
      (is (every? #(= (projection/container-elk-padding vc/chart-cosy true)
                      (pad-of %))
                  (regions-of cosy-kids))
          "cosy region containers use the cosy-density padding")
      (is (not= (projection/container-elk-padding vc/chart-compact true)
                (projection/container-elk-padding vc/chart-cosy true))
          "the two densities reserve genuinely different gaps"))))

;; ---- rf2-lxk3h3: nested initial-marker stays inside the container -------
;;
;; The bug: a NESTED compound / region container's LEFT padding reserved
;; only `:container-body-pad` (≈14 regular), but a nested initial substate's
;; initial-marker glyph (the dot + short hook) is positioned
;; `initial-marker-x-offset` (26) px LEFT of the state — so the dot's left
;; edge landed `initial-marker-left-extent` (26) px left of the state, well
;; PAST the 14px-inset container border, spilling the glyph outside the box
;; (hvac `running`/`conditioning`). The marker is an xyflow-only decorative
;; node — NOT in the ELK graph — so ELK's INCLUDE_CHILDREN pass never grew
;; the box to enclose it. The fix reserves the marker's leftward extent in
;; the container's LEFT padding for any container holding an initial child.

(defn- elk-padding-left
  "rf2-lxk3h3 — parse the `left=` integer out of an `elk.padding` string
  (`[top=40,left=26,bottom=14,right=14]`). Returns an int."
  [pad]
  #?(:clj  (Integer/parseInt (second (re-find #"left=(\d+)" pad)))
     :cljs (js/parseInt (second (re-find #"left=(\d+)" pad)) 10)))

(deftest container-elk-padding-reserves-initial-marker-left-extent
  (testing "rf2-lxk3h3 — `reserve-initial-marker? true` widens ONLY the
            LEFT side to `(max body-pad initial-marker-left-extent)`; top /
            bottom / right are unchanged from the plain inset"
    (doseq [[density vc-map] [[:compact vc/chart-compact]
                              [:regular vc/chart-regular]
                              [:cosy    vc/chart-cosy]]]
      (let [{:keys [container-body-pad]} vc-map
            plain   (projection/container-elk-padding vc-map)
            widened (projection/container-elk-padding vc-map true)]
        ;; The widened LEFT reserves enough to clear the marker's leftward
        ;; extent (≈26px) — so a state placed at the container's content
        ;; edge has its marker dot INSIDE the border.
        (is (>= (elk-padding-left widened)
                projection/initial-marker-left-extent)
            (str density " widened LEFT clears the initial-marker extent"))
        (is (= (elk-padding-left widened)
               (max container-body-pad projection/initial-marker-left-extent))
            (str density " widened LEFT = max(body-pad, marker-extent)"))
        ;; The reservation only ever GROWS the inset, never shrinks it.
        (is (>= (elk-padding-left widened) (elk-padding-left plain))
            (str density " reservation never shrinks the plain inset"))
        ;; top / bottom / right are identical between the two variants —
        ;; only the LEFT side moves.
        (is (= (str/replace plain   #"left=\d+" "left=X")
               (str/replace widened #"left=\d+" "left=X"))
            (str density " only the LEFT side changes"))))))

(deftest container-elk-padding-marker-left-extent-clears-glyph
  (testing "rf2-lxk3h3 / rf2-djqjh6 — the reserved LEFT extent is no smaller
            than the initial-marker glyph's GEOMETRY-radius left edge (the
            conservative wider anchor, `initial-marker-x-offset - 1`), so the
            painted dot (whose left ink only reaches x=1.5) always sits inside
            the container border at every density"
    (doseq [vc-map [vc/chart-compact vc/chart-regular vc/chart-cosy]]
      (let [{:keys [pseudo-radius]} vc-map
            ;; rf2-djqjh6 — DISTINGUISH the GEOMETRY radius from the PAINTED
            ;; radius. The marker node sits `initial-marker-x-offset` (26) px
            ;; LEFT of the state; the dot is centred at node-local
            ;; `dot-x = pseudo-radius + 1`. The reservation anchors to the dot's
            ;; GEOMETRY-radius left edge, node-local `dot-x - pseudo-radius`
            ;; = `(pseudo-radius + 1) - pseudo-radius` = 1 — NOT the slightly-
            ;; narrower PAINTED left edge at `dot-x - dot-paint-r = 1.5` (the
            ;; dot paints at `pseudo-radius − 0.5`). So the conservative
            ;; geometry-radius extent is `initial-marker-x-offset - 1` px left
            ;; of the state's edge.
            glyph-left-extent (dec projection/initial-marker-x-offset)]
        (is (>= projection/initial-marker-left-extent glyph-left-extent)
            "the reserved extent encloses the dot's GEOMETRY-radius left edge")
        ;; the GEOMETRY-radius left edge derived purely from the glyph agrees
        ;; with the node-local `dot-x - pseudo-radius = 1` invariant …
        (let [{:keys [dot-x]} (projection/initial-marker-glyph pseudo-radius)
              ;; … and the PAINTED left edge (`dot-x - (pseudo-radius - 0.5)`)
              ;; is 0.5px further RIGHT — node-local x=1.5 — so it sits inside
              ;; the geometry-radius reservation in every density.
              painted-r   (- pseudo-radius 0.5)]
          (is (= 1 (- dot-x pseudo-radius))
              "the dot's GEOMETRY-radius left edge is at node-local x=1 in every density")
          (is (= 1.5 (- dot-x painted-r))
              "the dot's PAINTED left edge is at node-local x=1.5 (radius shrunk 0.5px)")
          (is (> (- dot-x painted-r) (- dot-x pseudo-radius))
              "the painted left edge is RIGHT of the geometry-radius edge — inside the reservation"))))))

(deftest elk-children-nested-compound-reserves-initial-marker-left
  (testing "rf2-lxk3h3 — every compound container holding an initial
            substate carries the LEFT-widened padding, so the nested
            initial-marker stays inside the box (hvac running/conditioning)"
    (let [parsed   (layout/project-definition nested-compound-machine)
          all-kids (projection/->elk-children parsed)
          ;; Walk the whole nested tree (root frame → :outer → :mid) so
          ;; every level's container padding is checked, not just the top.
          walk     (fn walk [child]
                     (cons child (mapcat walk (:children child))))
          containers (->> all-kids
                          (mapcat walk)
                          (filter #(seq (:children %))))
          ;; Containers that hold an initial substate (every compound here:
          ;; the root frame holds :outer-as-initial, :outer holds :mid,
          ;; :mid holds :leaf) — keyed by id for the parse cross-check.
          initial-parents (into #{}
                                (comp (filter :initial?) (keep :parent-id))
                                (:nodes parsed))
          pad-of   (fn [c] (get-in c [:layoutOptions "elk.padding"]))]
      (is (seq containers) "the nested machine projects compound containers")
      (is (seq initial-parents) "the nested machine has initial substates")
      ;; Every container that holds an initial child reserves the widened
      ;; LEFT extent; every other container keeps the plain inset.
      (doseq [c containers]
        (if (contains? initial-parents (:id c))
          (is (>= (elk-padding-left (pad-of c))
                  projection/initial-marker-left-extent)
              (str (:id c) " (holds initial) reserves the marker LEFT extent"))
          (is (= (elk-padding-left (pad-of c))
                 (:container-body-pad vc/chart-regular))
              (str (:id c) " (no initial child) keeps the plain inset")))))))

(deftest elk-children-leaf-container-keeps-plain-left-inset
  (testing "rf2-lxk3h3 — a container with NO initial child keeps the plain
            body-pad LEFT inset (the reservation is targeted, not global)"
    ;; idle-loading is flat (no nested compounds), so the ONLY container is
    ;; the root frame — which DOES hold the machine's top-level initial, so
    ;; it is widened. Verify the targeting logic by directly checking the
    ;; plain-vs-widened branch through a synthetic no-initial container set.
    (let [plain   (projection/container-elk-padding vc/chart-regular)
          widened (projection/container-elk-padding vc/chart-regular true)]
      (is (not= (elk-padding-left plain) (elk-padding-left widened))
          "plain and widened LEFT differ at the regular density")
      (is (= (:container-body-pad vc/chart-regular) (elk-padding-left plain))
          "the plain LEFT is exactly the body-pad inset"))))

;; ---- rf2-8z1rca: root-container reserves the Context-band TOP padding ---
;;
;; The bug: the synthetic ROOT-CONTAINER frame paints a title strip PLUS a
;; VARIABLE-height Context band, but `container-elk-padding`'s plain TOP
;; reserved only the title strip + a body-pad band. The Context band is NOT
;; an ELK child (it is header chrome drawn by `root-container-node`), so
;; ELK's INCLUDE_CHILDREN pass never grew the frame to enclose it — with
;; non-trivial context the first child laid out at the reserved content edge
;; sat UNDER the painted band. The fix adds `context-band-height` (derived
;; from the row count + the density divider) to the FRAME's TOP padding,
;; threaded from `:context-band`'s row count.

(defn- elk-padding-top
  "rf2-8z1rca — parse the `top=` integer out of an `elk.padding` string
  (`[top=40,left=26,bottom=14,right=14]`). Returns an int."
  [pad]
  #?(:clj  (Integer/parseInt (second (re-find #"top=(\d+)" pad)))
     :cljs (js/parseInt (second (re-find #"top=(\d+)" pad)) 10)))

(defn- root-container-pad
  "rf2-8z1rca — the `elk.padding` string `->elk-children` puts on the
  synthetic ROOT-CONTAINER frame (the SOLE top-level child) for the given
  context-row count + density. nil density ⇒ regular."
  [parsed context-rows chart-vc]
  (-> (projection/->elk-children parsed nil chart-vc context-rows)
      first
      (get-in [:layoutOptions "elk.padding"])))

(deftest context-band-height-grows-with-row-count
  (testing "rf2-8z1rca — `context-band-height` is 0 for no rows and grows
            monotonically with the row count (each row adds row-height +
            gap); fixed pad + header + divider are added once"
    (let [dw (:container-divider-width vc/chart-regular)]
      (is (= 0 (projection/context-band-height 0 dw))
          "no rows → no band → no reservation")
      (is (= 0 (projection/context-band-height -1 dw))
          "a negative/absent count is treated as no band")
      (is (pos? (projection/context-band-height 1 dw))
          "one row paints a non-zero band")
      ;; each additional row adds exactly row-height + row-gap.
      (let [h1 (projection/context-band-height 1 dw)
            h2 (projection/context-band-height 2 dw)
            h3 (projection/context-band-height 3 dw)]
        (is (< h1 h2 h3) "the band grows monotonically with row count")
        (is (= (- h2 h1) (- h3 h2)
               (+ projection/context-band-row-height
                  projection/context-band-row-gap))
            "each extra row adds row-height + row-gap")))))

(deftest root-container-elk-padding-reserves-context-band
  (testing "rf2-8z1rca — the ROOT-CONTAINER frame's ELK top padding is
            LARGER WITH context rows than WITHOUT (it reserves the painted
            Context band); the no-context case is byte-identical to the
            plain marker-widened padding"
    (doseq [[density chart-vc] [[:compact vc/chart-compact]
                                [:regular vc/chart-regular]
                                [:cosy    vc/chart-cosy]]]
      (let [parsed     (layout/project-definition idle-loading)
            no-ctx     (root-container-pad parsed 0 chart-vc)
            with-2     (root-container-pad parsed 2 chart-vc)
            with-5     (root-container-pad parsed 5 chart-vc)]
        ;; No context → the frame padding equals the marker-widened padding
        ;; (the frame holds the top-level initial), unchanged from pre-8z1rca.
        (is (= (projection/container-elk-padding chart-vc true) no-ctx)
            (str density " context-less root padding = marker-widened (no extra top)"))
        ;; WITH context the TOP grows by exactly the band height; more rows
        ;; → more top.
        (is (> (elk-padding-top with-2) (elk-padding-top no-ctx))
            (str density " a 2-row context reserves MORE top than no context"))
        (is (> (elk-padding-top with-5) (elk-padding-top with-2))
            (str density " a 5-row context reserves MORE top than 2 rows"))
        (is (= (elk-padding-top with-2)
               (+ (elk-padding-top no-ctx)
                  (projection/context-band-height
                    2 (:container-divider-width chart-vc))))
            (str density " the extra top is exactly the 2-row band height"))
        ;; the band reservation NEVER touches the side/bottom insets — only
        ;; TOP moves between the no-context and with-context variants.
        (is (= (str/replace no-ctx #"top=\d+" "top=X")
               (str/replace with-2 #"top=\d+" "top=X"))
            (str density " only the TOP side changes for the Context band"))))))

(deftest elk-children-non-root-containers-ignore-context-rows
  (testing "rf2-8z1rca — threading a context-row count widens ONLY the
            root-container frame; nested compound containers keep their
            plain (marker-widened) padding regardless of the count"
    (let [parsed   (layout/project-definition nested-compound-machine)
          ;; A generous context count; only the frame should react to it.
          all-kids (projection/->elk-children parsed nil vc/chart-regular 4)
          walk     (fn walk [child] (cons child (mapcat walk (:children child))))
          ;; every container EXCEPT the synthetic root-container frame.
          nested   (->> all-kids
                        (mapcat walk)
                        (filter #(and (seq (:children %))
                                      (not= layout/root-container-id (:id %)))))
          pad-of   (fn [c] (get-in c [:layoutOptions "elk.padding"]))]
      (is (seq nested) "the nested machine has non-root compound containers")
      ;; Each nested compound holds an initial substate, so it carries the
      ;; marker-widened padding — NOT the context-band-widened root padding.
      (doseq [c nested]
        (is (= (projection/container-elk-padding vc/chart-regular true)
               (pad-of c))
            (str (:id c) " nested container ignores the context-row count")))
      ;; sanity: the root frame DID react to the count.
      (is (= (root-container-pad parsed 4 vc/chart-regular)
             (pad-of (first all-kids)))
          "the root frame carries the context-widened padding"))))

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
    (let [parsed   (layout/project-definition idle-loading)
          idle-id  (layout/node-id [:idle])
          measured {idle-id {:width 260 :height 72}}
          idle     (first (filter #(= idle-id (:id %)) (:nodes parsed)))
          child    (projection/elk-child idle measured)]
      (is (= 260 (:width child)))
      (is (= 72  (:height child)))))
  (testing "rf2-d9ro2 — an unmeasured leaf (absent from the map) keeps
            the floor"
    (let [parsed   (layout/project-definition idle-loading)
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
    (let [parsed    (layout/project-definition compound-machine)
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
    (let [parsed     (layout/project-definition idle-loading)
          idle-id    (layout/node-id [:idle])
          start-edge (->> (:edges parsed)
                          (filter #(= idle-id (:source %)))
                          first)
          ev-id      (projection/event-node-id start-edge)
          measured   {idle-id {:width 260 :height 72}
                      ev-id   {:width 180 :height 60}}
          children   (root-children (projection/->elk-children parsed measured))
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
    (let [parsed (layout/project-definition idle-loading)]
      (is (= (projection/->elk-children parsed)
             (projection/->elk-children parsed nil))
          "the 1-arity and nil-2-arity are identical")
      (let [children (root-children (projection/->elk-children parsed nil))
            leaves   (remove #(re-find #"^event__" (:id %)) children)]
        (is (every? #(= projection/state-node-min-width (:width %)) leaves)
            "every leaf at the width floor when unmeasured")))))

;; ---- order-state-children (rf2-ly51l — initial-state model order) ------

(deftest order-state-children-leads-with-initial
  (testing "rf2-ly51l — the initial state leads the ordered children;
            the machine-root annotation sinks LAST; ordinary states keep
            parse order. This is the model-order half of the initial-
            state placement soft preference."
    (let [parsed  (layout/project-definition door-cyclic-machine)
          ;; rf2-q129z8 — the door states now nest under the ROOT-CONTAINER
          ;; frame, so the effective top level is keyed on its id (was nil).
          top     (get (group-by :parent-id (:nodes parsed))
                       layout/root-container-id)
          ordered (projection/order-state-children top)
          ids     (mapv :id ordered)]
      (is (= (layout/node-id [:locked]) (first ids))
          "the initial state (:locked) leads the model order")
      (is (= layout/machine-root-id (last ids))
          "the synthetic machine-root annotation sinks to the END")
      ;; ordinary states keep their relative parse order between the two.
      (let [mids (->> ids
                      (remove #{(layout/node-id [:locked]) layout/machine-root-id})
                      vec)]
        (is (= [(layout/node-id [:closed])
                (layout/node-id [:open])
                (layout/node-id [:alarming])]
               mids)
            "ordinary states keep parse order (stable sort)")))))

(deftest order-state-children-is-stable-against-shuffle
  (testing "rf2-ly51l — the sort is STABLE: shuffling the non-initial /
            non-root states does not reorder them relative to each other
            (only the initial floats up + the root sinks down)."
    (let [parsed  (layout/project-definition door-cyclic-machine)
          ;; rf2-q129z8 — door states nest under the ROOT-CONTAINER frame.
          top     (get (group-by :parent-id (:nodes parsed))
                       layout/root-container-id)
          init    (filter :initial? top)
          root    (filter :machine-root? top)
          plain   (remove #(or (:initial? %) (:machine-root? %)) top)
          ;; move the initial + root into the MIDDLE of the input so the
          ;; sort has to actively float/sink them, not just preserve.
          shuffled (concat (take 1 plain) init root (drop 1 plain))
          ordered (projection/order-state-children shuffled)]
      (is (true? (:initial? (first ordered))))
      (is (true? (:machine-root? (last ordered))))
      ;; the two plain states keep their input relative order.
      (let [plain-ids (->> ordered
                           (remove #(or (:initial? %) (:machine-root? %)))
                           (mapv :id))]
        (is (= (->> shuffled
                    (remove #(or (:initial? %) (:machine-root? %)))
                    (mapv :id))
               plain-ids)
            "plain states keep relative input order under the stable sort")))))

(deftest elk-children-leads-with-initial-state
  (testing "rf2-ly51l — `->elk-children` emits the initial state FIRST
            among the top-level state children (before any event-node)
            and the machine-root annotation LAST among states, so ELK's
            DEPTH_FIRST source selection + within-layer tiebreak prefer
            the initial state. End-to-end through the production path."
    (let [parsed   (layout/project-definition door-cyclic-machine)
          children (root-children (projection/->elk-children parsed))
          state-children (remove #(re-find #"^event__" (:id %)) children)
          ids      (mapv :id state-children)]
      (is (= (layout/node-id [:locked]) (first ids))
          "the initial state leads the elk state children")
      (is (= layout/machine-root-id (last ids))
          "the machine-root annotation is the last state child"))))

(deftest elk-children-nested-initial-leads-its-container
  (testing "rf2-ly51l — a compound's OWN initial substate leads ITS local
            children (the preference applies per container, not just at
            the top level). `compound-machine`'s `:authenticated` is
            initial :browsing."
    (let [parsed   (layout/project-definition compound-machine)
          children (root-children (projection/->elk-children parsed))
          by-id    (into {} (map (juxt :id identity)) children)
          compound (get by-id (layout/node-id [:authenticated]))
          ;; the compound's STATE children (skip its nested event-nodes).
          sub-states (->> (:children compound)
                          (remove #(re-find #"^event__" (:id %)))
                          (mapv :id))]
      (is (= (layout/node-id [:authenticated :browsing]) (first sub-states))
          "the compound's initial substate leads its local model order"))))

;; ---- ->elk-edge / ->elk-edges (rf2-rlq97) ------------------------------
;;
;; The edges ARE fed into the ELK graph (this is what lets the Layered
;; algorithm route them AROUND node boxes instead of the renderer drawing
;; geometric paths that cut across states). `->elk-edge` is the pure
;; projector for one transition's `__in` / `__out` ELK edge pair, lifted
;; out of the inline `mapcat` that used to live JS-side in
;; `chart.cljs/->elk-input` so the edge-feed is pinnable at the JVM layer.

(deftest elk-edge-emits-in-and-out-for-external-transition
  (testing "rf2-rlq97 — an external transition (has :target) feeds TWO
            ELK edges: source-state → event-node (__in) and event-node →
            target-state (__out), so ELK routes both segments around any
            intervening node"
    (let [parsed   (layout/project-definition idle-loading)
          idle-id  (layout/node-id [:idle])
          start    (->> (:edges parsed)
                        (filter #(= idle-id (:source %)))
                        first)
          ev-id    (projection/event-node-id start)
          elk-eds  (projection/->elk-edge start)]
      (is (= 2 (count elk-eds)) "external transition → __in + __out")
      (let [in-e  (first (filter #(= (str (:id start) "__in")  (:id %)) elk-eds))
            out-e (first (filter #(= (str (:id start) "__out") (:id %)) elk-eds))]
        (is (some? in-e))  (is (some? out-e))
        (is (= [idle-id] (:sources in-e)) "__in source is the source state")
        (is (= [ev-id]   (:targets in-e)) "__in target is the event-node")
        (is (= [ev-id]   (:sources out-e)) "__out source is the event-node")
        (is (= [(:target start)] (:targets out-e))
            "__out target is the transition target state")))))

(deftest elk-edge-omits-out-for-internal-transition
  (testing "rf2-rlq97 — an internal transition (no :target) feeds ONLY
            the __in ELK edge; the event-node hangs with no outgoing
            arrow (Stately convention)"
    (let [parsed  (layout/project-definition internal-self-machine)
          tick    (first (:edges parsed))
          elk-eds (projection/->elk-edge tick)]
      (is (true? (:internal? tick)) "fixture is an internal transition")
      (is (= 1 (count elk-eds)) "internal transition → __in only")
      (is (= (str (:id tick) "__in") (:id (first elk-eds)))))))

(deftest elk-edge-carries-labels-array
  (testing "rf2-rlq97 — every ELK edge carries a :labels array (ELK
            requires one). Under events-as-nodes the transition text is
            on the event-NODE so the edge label text is empty + carries
            NO measured dims (feeding dims on both the node AND its edges
            would double-budget the same text)"
    (let [parsed   (layout/project-definition idle-loading)
          idle-id  (layout/node-id [:idle])
          start    (->> (:edges parsed)
                        (filter #(= idle-id (:source %)))
                        first)
          elk-eds  (projection/->elk-edge start)]
      (doseq [e elk-eds]
        (is (vector? (:labels e)) "labels is a vector")
        (is (= 1 (count (:labels e))) "exactly one label entry")
        (is (= "" (:text (first (:labels e)))) "empty text (label on node)")
        (is (not (contains? (first (:labels e)) :width))
            "no measured width fed (no double-budget)")))))

(deftest elk-edge-label-feeds-measured-dims-when-present
  (testing "rf2-rlq97 — a labelled edge (one whose label-dims map carries
            its elk-edge-id) gets its MEASURED width/height fed into the
            ELK label so ELK reserves a placement channel — the edge-label
            analogue of d9ro2's node measure"
    (let [parsed   (layout/project-definition idle-loading)
          idle-id  (layout/node-id [:idle])
          start    (->> (:edges parsed)
                        (filter #(= idle-id (:source %)))
                        first)
          in-id    (str (:id start) "__in")
          dims     {in-id {:width 88 :height 18}}
          elk-eds  (projection/->elk-edge start dims)
          in-e     (first (filter #(= in-id (:id %)) elk-eds))
          lbl      (first (:labels in-e))]
      (is (= 88 (:width lbl)) "measured label width fed to ELK")
      (is (= 18 (:height lbl)) "measured label height fed to ELK"))))

(deftest elk-edge-label-ignores-zero-dims
  (testing "rf2-rlq97 — a zero-size measured label (a node still awaiting
            measurement) is treated as no label so ELK reserves nothing"
    (is (= [{:text ""}] (projection/elk-edge-label "" {:width 0 :height 0})))
    (is (= [{:text ""}] (projection/elk-edge-label "" nil)))))

(deftest elk-edges-flattens-all-transitions
  (testing "rf2-rlq97 — `->elk-edges` flattens every parsed transition's
            __in/__out pair into the flat ELK `edges` vector
            `->elk-input` clj->js-es onto the root graph. The id set
            matches the `:edge-points` producer/consumer key scheme
            (`<spec-id>__in` / `__out`)"
    (let [parsed   (layout/project-definition idle-loading)
          elk-eds  (projection/->elk-edges parsed)
          ids      (set (map :id elk-eds))
          ;; idle-loading: :start (external), :after (external), :always
          ;; (external) — all have targets, so 3 transitions × 2 = 6 edges.
          n-ext    (count (remove :internal? (:edges parsed)))
          n-int    (count (filter :internal? (:edges parsed)))]
      (is (= (+ (* 2 n-ext) n-int) (count elk-eds))
          "each external transition → 2 ELK edges, each internal → 1")
      (is (every? #(or (re-find #"__in$" %) (re-find #"__out$" %)) ids)
          "every ELK edge id ends in __in or __out (the route key scheme)")
      (is (= (count elk-eds) (count ids)) "no duplicate edge ids"))))

;; ---- rf2-k504af — initial-edge flow-start priority ---------------------
;;
;; The INITIAL state's outgoing `__in` edge carries
;; `elk.layered.priority.direction 1` (paired with the root
;; `considerModelOrder NODES_AND_EDGES`) so ELK pulls the initial to the
;; START of its region's flow — fixing the parallel / pure-cyclic regions
;; (traffic `red`/`walk`) where the soft DEPTH_FIRST + model-order
;; preference slips and the initial sinks to the bottom layer. Every other
;; edge leaves the option unset (the ELK default 0).

(defn- in-edge-priority
  "The `elk.layered.priority.direction` layoutOption on an ELK `__in`
  edge map (nil when unset)."
  [elk-edge]
  (get-in elk-edge [:layoutOptions "elk.layered.priority.direction"]))

(deftest elk-edge-sets-direction-priority-on-initial-source
  (testing "rf2-k504af — the `__in` edge LEAVING an `:initial?` state
            carries `elk.layered.priority.direction 1`; a non-initial
            source's `__in` edge leaves it unset"
    (let [parsed     (layout/project-definition idle-loading)
          ;; idle-loading: :idle is initial, :loading is not.
          idle-id    (layout/node-id [:idle])
          loading-id (layout/node-id [:loading])
          init-set   #{idle-id}
          ;; :idle --:start--> :loading  (the initial state's outgoing arm)
          start-e    (first (filter #(= (:source %) idle-id) (:edges parsed)))
          ;; :loading --:always--> :ready (a non-initial source)
          loading-e  (first (filter #(= (:source %) loading-id) (:edges parsed)))
          start-in   (first (projection/->elk-edge start-e nil init-set))
          loading-in (first (projection/->elk-edge loading-e nil init-set))]
      (is (re-find #"__in$" (:id start-in)) "first elk edge is the __in half")
      (is (= projection/initial-edge-priority-direction
             (in-edge-priority start-in))
          "the initial state's outgoing __in edge gets direction priority 1")
      (is (nil? (in-edge-priority loading-in))
          "a non-initial source's __in edge leaves the priority unset"))))

(deftest elk-edge-omits-priority-when-no-initial-set
  (testing "rf2-k504af — with no `initial-ids` (the pre-bead 2-arity
            path), NO edge carries the direction-priority option"
    (let [parsed  (layout/project-definition idle-loading)
          elk-eds (projection/->elk-edges parsed)] ;; 2-arity → no initial set...
      ;; ->elk-edges DOES derive the set, so assert via the bare ->elk-edge.
      (is (every? #(nil? (in-edge-priority %))
                  (mapcat #(projection/->elk-edge % nil nil) (:edges parsed)))
          "the nil initial-ids arity leaves every edge unset"))))

(deftest elk-edges-derives-initial-set-and-prioritises-each-region-initial
  (testing "rf2-k504af — `->elk-edges` derives the `:initial?` node-id set
            from `:nodes` and tags EACH region's initial `__in` edge — the
            parallel pure-cyclic case (traffic-shaped). Both region
            initials (:muted, :hidden) get the priority; their cycle
            partners (:playing, :shown) do not."
    (let [parsed    (layout/project-definition parallel-machine)
          ;; Derive the actual node-ids from the parse (region states carry
          ;; a `region__<region>__<state>` id scheme, not the bare
          ;; `node-id`), so the test is robust to the id form.
          nodes     (:nodes parsed)
          init-ids  (into #{} (comp (filter :initial?) (map :id)) nodes)
          non-init  (into #{} (comp (remove #(or (:initial? %)
                                                 (:region? %)))
                                    (map :id)) nodes)
          elk-eds   (projection/->elk-edges parsed)
          ;; the `__in` edge whose SOURCE is the given state.
          in-for    (fn [sid]
                      (first (filter #(and (re-find #"__in$" (:id %))
                                           (= [sid] (:sources %)))
                                     elk-eds)))]
      (is (= 2 (count init-ids)) "both region initials present")
      (doseq [iid init-ids]
        (is (= projection/initial-edge-priority-direction
               (in-edge-priority (in-for iid)))
            (str "region initial " iid " gets flow-start priority")))
      (doseq [nid non-init]
        (is (nil? (in-edge-priority (in-for nid)))
            (str "non-initial " nid " leaves the priority unset"))))))

;; ---- :edge-labels → :data {:labelPos} (rf2-rlq97) ----------------------
;;
;; ELK owns edge-label PLACEMENT now (the edge-label analogue of ELK
;; owning node placement). The projector attaches ELK's computed label
;; position to the labelled edge's `:data {:labelPos}` so the renderer
;; paints where ELK reserved a collision-free channel instead of a
;; renderer-side midpoint heuristic.

(deftest xyflow-graph-attaches-elk-label-position-to-edge-data
  (testing "rf2-rlq97 — when :edge-labels carries an ELK-computed
            position for an edge, the projector threads it onto that
            edge's :data {:labelPos}; an edge with no entry gets nil"
    (let [parsed   (layout/project-definition idle-loading)
          idle-id  (layout/node-id [:idle])
          start    (->> (:edges parsed)
                        (filter #(= idle-id (:source %)))
                        first)
          in-id    (str (:id start) "__in")
          out-id   (str (:id start) "__out")
          graph    (projection/xyflow-graph
                     parsed {}
                     {:edge-labels {in-id {:x 42 :y 99}}})
          xy-in    (edge-by-id graph in-id)
          xy-out   (edge-by-id graph out-id)]
      (is (= {:x 42 :y 99} (:labelPos (:data xy-in)))
          "ELK label position threaded onto the inbound edge")
      (is (nil? (:labelPos (:data xy-out)))
          "an edge with no ELK label position gets nil (geometric fallback)"))))

(deftest xyflow-graph-edge-labels-defaults-empty
  (testing "rf2-rlq97 — omitting :edge-labels (events-as-nodes default)
            leaves every edge's :labelPos nil so the renderer keeps its
            geometric anchor"
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})]
      (is (every? #(nil? (:labelPos (:data %))) (:edges graph))
          "no ELK labels fed → every edge :labelPos nil"))))

;; ---- initial-state markers + self-loops (rf2-54s5a) --------------------

(deftest xyflow-graph-emits-initial-marker-node-and-entry-edge
  (testing "rf2-54s5a — the machine's initial state gets a synthetic
            initial-marker node + an unlabelled entry edge into it"
    (let [parsed   (layout/project-definition idle-loading)
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

(deftest xyflow-graph-positions-initial-marker-at-fixed-offset
  (testing "rf2-i9d2ob / rf2-d5s7yg — the initial-marker node sits at a
            FIXED, SMALL offset LEFT of (and slightly below) its state, so
            the fixed glyph (`chart.nodes/initial-marker`) drawn in node-
            local coords reads as a SHORT hook ending just OUTSIDE the
            state's near edge regardless of where ELK placed the state"
    (let [parsed    (layout/project-definition idle-loading)
          idle-id   (layout/node-id [:idle])
          ;; place the initial state at a known position so the offset is
          ;; observable on the marker.
          positions {idle-id {:x 300 :y 120}}
          graph     (projection/xyflow-graph parsed positions {})
          marker    (node-by-id graph (str "initial__" idle-id))]
      (is (pos? projection/initial-marker-x-offset))
      (is (= (- 300 projection/initial-marker-x-offset)
             (get-in marker [:position :x]))
          "marker x = state.x - initial-marker-x-offset (dot sits left of the edge)")
      (is (= (+ 120 projection/initial-marker-y-offset)
             (get-in marker [:position :y]))
          "marker y = state.y + initial-marker-y-offset (title-row anchor)"))))

(deftest initial-marker-short-hook-ends-outside-the-edge
  (testing "rf2-d5s7yg — the offset is a SMALL short-hook span and the
            tip-gap is decoupled from it: the arrow tip (drawn at node-local
            x = offset - tip-gap) lands a clean gap OUTSIDE the state's left
            edge (absolute state.x - tip-gap), never on/through it"
    ;; offset is a SHORT hook span (Stately's dot-offset neighbourhood), not
    ;; the old 48. rf2-k7kiiq — upper bound widened 26 → 28 alongside the
    ;; offset bump (22 → 26) that lengthens the visible hook arm; at offset 26
    ;; the dot CENTRE (`dot-x = pseudo-radius + 1`) lands ~19px left of the edge
    ;; (regular: `offset - dot-x = 26 - 7 = 19`).
    (is (<= 12 projection/initial-marker-x-offset 28)
        "offset is a short-hook span (dot centre ~19px left of the edge)")
    ;; the tip-gap is a small positive gap, strictly less than the offset so
    ;; the tip lands OUTSIDE the edge (local x = offset - gap > 0).
    (is (pos? projection/initial-marker-tip-gap))
    (is (<= 4 projection/initial-marker-tip-gap 12)
        "tip-gap is a small visible gap (~6-10px)")
    (is (< projection/initial-marker-tip-gap projection/initial-marker-x-offset)
        "tip-gap < offset ⇒ arrow tip (local x = offset - gap) is OUTSIDE the edge, not at/through it")))

(deftest initial-marker-glyph-hook-flows-forward
  (testing "rf2-wwyx1u — the initial glyph reads as ONE clean unit pointing
            AT the edge: the small Stately-sized arrowhead leaves the hook
            base (`end-x`) RIGHT of the dot (`dot-x`), so the single Q-hook
            flows dot → down-and-RIGHT into the arrowhead (NOT backwards).
            Pins the FORWARD-FLOW invariant the renderer paints across every
            density, so the oversized-arrowhead regression cannot return."
    (doseq [density vc/densities]
      (let [{:keys [pseudo-radius]} (vc/chart-for-density density)
            {:keys [ah tip-x dot-x end-x]}
            (projection/initial-marker-glyph pseudo-radius)]
        ;; the arrowhead is SMALL (Stately ~5×6), never the oversized
        ;; ~10-13px head the regression produced. rf2-k7kiiq — ceiling
        ;; raised 5 → 6 for a slightly larger Stately-aligned head.
        (is (<= 4 ah 6)
            (str density ": arrowhead is Stately-small (4–6px), not oversized"))
        ;; the hook base sits RIGHT of the dot ⇒ the curve flows FORWARD
        ;; (down-and-right) into the arrowhead, not backwards into the dot.
        (is (> end-x dot-x)
            (str density ": end-x > dot-x ⇒ hook flows FORWARD into the arrowhead"))
        ;; the tip still lands a clean positive gap OUTSIDE the edge
        ;; (edge is at local x=offset), pointing AT it.
        (is (< 0 tip-x projection/initial-marker-x-offset)
            (str density ": arrow tip is OUTSIDE the edge, pointing at it"))))))

(deftest xyflow-graph-nested-initial-marker-has-no-extent-clamp
  (testing "rf2-d5s7yg — a NESTED initial-marker (compound/region substate)
            carries `:parentId` for the coordinate frame but NO `:extent
            \"parent\"`. The clamp would shove a marker sitting just outside
            the container's left padding back INSIDE — the root cause of the
            nested-initial overshoot (`red`/`walk` penetrated, top-level
            `door` did not)"
    (let [parsed      (layout/project-definition compound-machine)
          graph       (projection/xyflow-graph parsed {} {})
          browsing-id (layout/node-id [:authenticated :browsing])
          marker      (node-by-id graph (str "initial__" browsing-id))]
      (is (some? marker) "the compound's initial substate gets a marker")
      (is (= (layout/node-id [:authenticated]) (:parentId marker))
          "marker keeps the container coordinate frame via :parentId")
      (is (not (contains? marker :extent))
          "marker carries NO :extent — the clamp that drove the nested overshoot is gone"))))

(deftest xyflow-graph-threads-initial-flag-onto-node-data
  (testing "rf2-54s5a — node :data carries :initial (true for the
            machine's initial state, false otherwise)"
    (let [parsed  (layout/project-definition idle-loading)
          graph   (projection/xyflow-graph parsed {} {})
          idle    (node-by-id graph (layout/node-id [:idle]))
          loading (node-by-id graph (layout/node-id [:loading]))]
      (is (true?  (:initial (:data idle))))
      (is (false? (:initial (:data loading)))))))

(deftest xyflow-graph-emits-compound-substate-initial-marker
  (testing "rf2-54s5a + rf2-xh1lm — a compound parent's :initial substate
            also gets a marker (xstate per-level initial semantics) sharing
            the compound's coordinate frame via xyflow v12's `:parentId`"
    (let [parsed      (layout/project-definition compound-machine)
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
            Pre-rf2-qo5xy a self-loop was a single dedicated-loop edge;
            the events-as-nodes paradigm dissolves that special case
            (the visible loop arc is now the route around the
            event-node)."
    (let [parsed   (layout/project-definition self-loop-machine)
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
    (let [parsed   (layout/project-definition compound-machine)
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
    (let [parsed   (layout/project-definition compound-machine)
          children (root-children (projection/->elk-children parsed))
          by-id    (into {} (map (juxt :id identity) children))
          authed   (get by-id (layout/node-id [:authenticated]))]
      (is (some? authed) "compound parent nests inside the root frame")
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
    (let [parsed   (layout/project-definition same-state-machine)
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
    (let [parsed   (layout/project-definition internal-self-machine)
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

(deftest xyflow-graph-reenter-event-node-carries-reenter-data
  (testing "rf2-9dj21r — a `:reenter? true` (external restart) self-target
            projects its event-node with `:reenter true` (the renderer's
            ↻ marker) AND keeps the outbound edge (it is a TARGETED
            transition); the internal-default counterpart carries neither"
    (let [reenter-spec  {:initial :a :states {:a {:on {:ping {:target :same-state
                                                              :reenter? true}}}}}
          internal-spec {:initial :a :states {:a {:on {:ping {:target :same-state}}}}}
          r-parsed (layout/project-definition reenter-spec)
          i-parsed (layout/project-definition internal-spec)
          r-graph  (projection/xyflow-graph r-parsed {} {})
          i-graph  (projection/xyflow-graph i-parsed {} {})
          r-edge   (first (:edges r-parsed))
          i-edge   (first (:edges i-parsed))
          r-ev     (event-node-for r-graph (:id r-edge))
          i-ev     (event-node-for i-graph (:id i-edge))]
      (is (true? (:reenter (:data r-ev)))
          "the external transition's event-node carries :reenter true")
      (is (false? (:reenter (:data i-ev)))
          "the internal default's event-node carries :reenter false")
      (is (some? (outbound-edge-for r-graph (:id r-edge)))
          "a reentering (targeted) self-transition keeps its outbound edge")
      (is (not= (:id r-edge) (:id i-edge))
          "the two parsed edges have DISTINCT ids (no xyflow duplicate-drop)"))))

(deftest xyflow-graph-wildcard-event-node-not-fireable
  (testing "rf2-ee38b.21 + rf2-qo5xy — the `:*` wildcard transition's
            event-node carries a NIL :eventId (not user-fireable on
            the chart); the real `:start` event-node stays fireable."
    (let [parsed (layout/project-definition wildcard-machine)
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
  (testing "rf2-vcnvj — a machine-level (top-level) :on fallback projects
            as EXACTLY ONE event-node flagged `:machineLevel true`,
            sourced from the synthetic MACHINE-ROOT node — NOT one chip
            per inheriting leaf (the pre-vcnvj per-state repetition)."
    (let [parsed (layout/project-definition machine-level-on-machine)
          graph  (projection/xyflow-graph parsed {} {})
          ev-nodes (filter #(= "rf2-event" (:type %)) (:nodes graph))
          logout (filter #(= :logout (:eventId (:data %))) ev-nodes)
          ml-edge (first (filter :machine-level? (:edges parsed)))
          in-edge (inbound-edge-for graph (:id ml-edge))]
      (is (= 1 (count logout)) "exactly ONE machine-level event-node")
      (is (every? #(true? (:machineLevel (:data %))) logout))
      ;; The chip's inbound edge originates at the MACHINE-ROOT node.
      (is (some? in-edge))
      (is (= layout/machine-root-id (:source in-edge))
          "the fallback's `__in` edge leaves the MACHINE-ROOT node"))))

(deftest xyflow-graph-machine-root-node-projected
  (testing "rf2-vcnvj — the synthetic MACHINE-ROOT node projects as a
            xyflow node of type `machine-root` (the quiet root-context
            chip the fallback routes FROM); it leads the node vector so
            xyflow sees the source node before the edge referencing it."
    (let [parsed (layout/project-definition machine-level-on-machine)
          graph  (projection/xyflow-graph parsed {} {})
          root   (node-by-id graph layout/machine-root-id)
          root-idx (->> (:nodes graph)
                        (map-indexed vector)
                        (filter #(= layout/machine-root-id (:id (second %))))
                        ffirst)
          ev-idx (->> (:nodes graph)
                      (map-indexed vector)
                      (filter #(= "rf2-event" (:type (second %))))
                      ffirst)]
      (is (some? root) "the machine-root node is projected")
      (is (= "machine-root" (:type root)))
      (is (and root-idx ev-idx (< root-idx ev-idx))
          "the root node precedes the event-node that references it"))))

(deftest xyflow-graph-no-machine-level-emits-no-root-node
  (testing "rf2-vcnvj — a machine with no top-level :on projects no
            machine-root node (the chip is the fallback's anchor only)."
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})]
      (is (nil? (node-by-id graph layout/machine-root-id)))
      (is (empty? (filter #(= "machine-root" (:type %)) (:nodes graph)))))))

(deftest xyflow-graph-state-only-transitions-not-machine-level
  (testing "rf2-ee38b.21 + rf2-qo5xy — a normal state-local transition's
            event-node carries `:machineLevel false`."
    (let [parsed  (layout/project-definition idle-loading)
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
    (let [parsed (layout/project-definition entry-exit-machine)
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
    (let [parsed     (layout/project-definition idle-loading)
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
    (let [parsed   (layout/project-definition idle-loading)
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
    (let [parsed (layout/project-definition idle-loading)
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
    (let [parsed (layout/project-definition idle-loading)
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
    (let [parsed (layout/project-definition self-loop-machine)
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
  (testing "rf2-cz8v6 + rf2-qo5xy + rf2-vd3q1i — G1's active-edge styling
            survives routing through the event-node: an outbound edge whose
            SOURCE is active carries both `:active true` and the elk
            route on its `:data`."
    (let [parsed  (layout/project-definition idle-loading)
          ;; rf2-vd3q1i — highlight the SOURCE (:idle); the source→…→loading
          ;; transition is then source-active and lights both halves.
          hi      (layout/node-id [:idle])
          start   (->> (:edges parsed)
                       (filter #(= (:source %) (layout/node-id [:idle])))
                       first)
          route   [{:x 0 :y 0} {:x 0 :y 40} {:x 60 :y 40}]
          graph   (projection/xyflow-graph
                    parsed {} {:highlight-ids #{hi}
                               :edge-points  {(str (:id start) "__out") route}})
          out-edge (outbound-edge-for graph (:id start))]
      (is (true? (:active (:data out-edge))))
      (is (= route (:points (:data out-edge)))))))

;; ---- source-active edge highlight (rf2-vd3q1i) -------------------------
;;
;; `from-active?` (rendered as the edge `:active` flag, the bright-blue
;; stroke) is SOURCE-active only: an edge lights iff its SOURCE state is
;; active. It is NOT incident-to-active — an INCOMING edge whose only
;; active endpoint is its TARGET stays quiet. This pins the live-repro
;; defect (the door machine: focusing the :door/close no-op at :open used
;; to paint the INCOMING push (closed→open) edge blue). The genuinely-
;; traversed edge of a real transition still lights via `:fired` (matched
;; by edge-id, direction-agnostic — see the fired-edge section below), so
;; dropping the incoming-edge highlight loses no "what just happened" cue.

(deftest xyflow-graph-from-active-is-source-active-only
  (testing "rf2-vd3q1i — door machine, active-ids {:open}: the OUTGOING
            fan from :open (close / hold / trip) is `:active`, while the
            INCOMING push (closed→open) edge is NOT — even though :open is
            push's TARGET. `from-active?` is source-active, not incident."
    (let [parsed     (layout/project-definition door-cyclic-machine)
          open-id    (layout/node-id [:open])
          closed-id  (layout/node-id [:closed])
          locked-id  (layout/node-id [:locked])
          graph      (projection/xyflow-graph parsed {} {:highlight-ids #{open-id}})
          ;; A projected transition is split into __in (src→event) and
          ;; __out (event→tgt) halves that share the from-active? flag.
          ;; Identify each parsed transition by its source/target node-ids
          ;; (unique per pair in this fixture), then look up BOTH projected
          ;; halves by the canonical parsed-edge id.
          active-of  (fn [src-node-id tgt-node-id]
                       (let [pe (first
                                  (filter #(and (= (:source %) src-node-id)
                                                (= (:target %) tgt-node-id))
                                          (:edges parsed)))]
                         {:in  (when pe (inbound-edge-for  graph (:id pe)))
                          :out (when pe (outbound-edge-for graph (:id pe)))}))]
      ;; push: closed → open. Source :closed is NOT in active-ids {:open};
      ;; :open is only its TARGET → the incoming edge stays quiet.
      (let [push (active-of closed-id open-id)]
        (is (some? (:in push)) "fixture sanity: push (closed→open) projects")
        (is (= open-id (:target (:out push)))
            "push's outbound half lands on :open (it IS an incoming edge to :open)")
        (is (false? (:active (:data (:in push))))
            "push (closed→open) is NOT from-active? — :open is only its target")
        (is (false? (:active (:data (:out push))))
            "neither half of the incoming push edge lights"))
      ;; close / hold / trip: all sourced from :open → from-active? true.
      ;; close → closed, trip → alarming, hold is internal (self at :open).
      (let [close (active-of open-id closed-id)
            trip  (active-of open-id (layout/node-id [:alarming]))]
        (is (some? (:in close)) "fixture sanity: close (open→closed) projects")
        (is (true? (:active (:data (:in close))))
            "close (open→closed) IS from-active? — sourced from active :open")
        (is (some? (:in trip)) "fixture sanity: trip (open→alarming) projects")
        (is (true? (:active (:data (:in trip))))
            "trip (open→alarming) IS from-active? — sourced from active :open"))
      ;; The :open-sourced outgoing fan all lights; control: an edge with
      ;; neither endpoint active (insert-coin: locked→closed) stays quiet.
      (let [coin (active-of locked-id closed-id)]
        (is (some? (:in coin)) "fixture sanity: insert-coin (locked→closed) projects")
        (is (false? (:active (:data (:in coin))))
            "insert-coin (locked→closed) — neither endpoint active — stays quiet")))))

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
    (let [parsed   (layout/project-definition idle-loading)
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
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})]
      (is (seq (:edges graph)))
      (is (every? #(false? (:fired (:data %))) (:edges graph))
          "no fired set → no fired edges"))))

(deftest xyflow-graph-fired-collects-multiple-event-nodes
  (testing "rf2-qeemm + rf2-qo5xy — a set with N fired parsed-edge ids
            marks N event-nodes + their inbound/outbound edges as
            fired. An epoch with two traversed arms lights two
            event-node forks."
    (let [parsed (layout/project-definition idle-loading)
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
    (let [parsed   (layout/project-definition idle-loading)
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
  (testing "rf2-qeemm + rf2-qo5xy + rf2-vd3q1i — :fired + :active + :points
            coexist on the outbound edge of a fired transition that is
            ALSO sourced from an active node and carries an elk route."
    (let [parsed  (layout/project-definition idle-loading)
          ;; rf2-vd3q1i — highlight the SOURCE (:idle) so the transition is
          ;; source-active (not merely incident to the active target).
          hi      (layout/node-id [:idle])
          start   (->> (:edges parsed)
                       (filter #(= (:source %) (layout/node-id [:idle])))
                       first)
          route   [{:x 0 :y 0} {:x 0 :y 40} {:x 60 :y 40}]
          graph   (projection/xyflow-graph
                    parsed {} {:highlight-ids  #{hi}
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
    (let [parsed  (layout/project-definition idle-loading)
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
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {:fired-edge-ids #{"anything"}})
          entry  (first (filter #(:entry (:data %)) (:edges graph)))]
      (is (some? entry) "fixture has an entry edge")
      (is (false? (:fired (:data entry)))
          "entry edges are never fired"))))

;; ---- guard-blocked no-op edge highlight (rf2-fzrzlw / rf2-4nxgqq) ------
;;
;; The bead's repro: door in `:open`, dispatch `:door/close`, the
;; `:may-close?` guard fails → guard-blocked NO-OP. NO `:rf.machine/
;; transition` is emitted (so `:fired-edge-ids` is empty), and before
;; rf2-fzrzlw the chart painted ALL of `:open`'s exits affordance-blue,
;; giving ZERO signal that `:door/close` was attempted-and-rejected. The
;; Xray inspector resolves the blocked edge-ids
;; (`extract-guard-blocked-edge-ids`) by `(source-path, event, guard)` when
;; trace state is available — reading the active `:state` off the
;; `:rf.machine/guard-evaluated` fail/threw trace and gating each candidate
;; whose `:from-path` is a PREFIX of the active path (rf2-tjm3u2; a
;; no-`:state` trace falls back to the `(event, guard)` match) — and threads
;; them as `:guard-blocked-edge-ids` (a SET) into the projector. The
;; projector marks the EVENT-NODE and its `__in` (source→event-node) half
;; `:guardBlocked`; rf2-4nxgqq — the `__out` (event-node→target) half is NOT
;; marked (a no-op never reached the target), so the highlight STOPS at the
;; guard event-node while the `__out` STATIC topology edge still renders. The
;; renderer then paints the PINK guard-blocked treatment; the CANONICAL DOM
;; pins are the event-node (`data-guard-blocked`) + chart-root
;; (`data-guard-blocked-edge-ids`), NOT an edge-half attribute (rf2-bdwolc).
;; The match is by EDGE-ID so the precise rejected arm lights — including
;; the exact arm of a guarded fork.

(defn- door-close-edge-id
  "The canonical id of the door's guarded `:door/close [may-close?]`
  edge (`:open` → `:closed`) off `door-cyclic-machine`."
  [parsed]
  (->> (:edges parsed)
       (some (fn [e] (when (and (= [:open] (:from-path e))
                                (= :door/close (:event e))
                                (= :may-close? (:guard e)))
                       (:id e))))))

(deftest xyflow-graph-marks-guard-blocked-event-node-and-its-edges
  (testing "rf2-fzrzlw + rf2-4nxgqq — a parsed-edge id in
            :guard-blocked-edge-ids marks the event-node AND its `__in`
            (source→event-node) half `:guardBlocked true`, but NOT its
            `__out` (event-node→target) half — the highlight stops at the
            guard event-node because the no-op never reached the target.
            Other edges / event-nodes stay `:guardBlocked false`."
    (let [parsed   (layout/project-definition door-cyclic-machine)
          close-id (door-close-edge-id parsed)
          graph    (projection/xyflow-graph
                     parsed {} {:guard-blocked-edge-ids #{close-id}})
          ev-node  (event-node-for graph close-id)
          in-edge  (inbound-edge-for  graph close-id)
          out-edge (outbound-edge-for graph close-id)
          other-ev (remove #(= (:id %) (:id ev-node))
                           (filter #(= "rf2-event" (:type %)) (:nodes graph)))
          other-ed (remove #(#{(:id in-edge) (:id out-edge)} (:id %))
                           (:edges graph))]
      (is (string? close-id) "fixture has the guarded :door/close edge")
      (is (true? (:guardBlocked (:data ev-node))) "event-node is blocked")
      (is (true? (:guardBlocked (:data in-edge)))
          "the `__in` source→event-node half is blocked")
      (is (false? (:guardBlocked (:data out-edge)))
          "rf2-4nxgqq — the `__out` event-node→target half is NOT blocked
           (the no-op never reached the target)")
      (is (every? #(false? (:guardBlocked (:data %))) other-ev))
      (is (every? #(false? (:guardBlocked (:data %))) other-ed)))))

(deftest xyflow-graph-guard-blocked-highlight-stops-at-event-node
  (testing "rf2-4nxgqq — a guard-BLOCKED transition is a no-op: the guard
            declined, the machine stayed in the source state, the target was
            never reached. So the live blocked HIGHLIGHT covers
            source→event-node ONLY, never event-node→target. Concretely:
            the `__in` half AND the event-node carry `:guardBlocked true` +
            the PINK arrowhead hue, while the `__out` half carries
            `:guardBlocked false` + the RESTING (non-pink) arrowhead — so the
            onward arrow does NOT falsely imply the transition progressed.
            The `__out` STATIC topology edge still renders (the transition
            exists in the definition); only the live overlay is withheld."
    (let [parsed     (layout/project-definition door-cyclic-machine)
          close-id   (door-close-edge-id parsed)
          ct         (tokens/chart-tokens)
          graph      (projection/xyflow-graph
                       parsed {} {:guard-blocked-edge-ids #{close-id}})
          ev-node    (event-node-for  graph close-id)
          in-edge    (inbound-edge-for  graph close-id)
          out-edge   (outbound-edge-for graph close-id)]
      ;; The `__out` topology edge still EXISTS (static rendering preserved).
      (is (some? out-edge)
          "the event-node→target `__out` edge still renders (static topology)")
      ;; The blocked overlay reaches the event-node + the inbound half.
      (is (true? (:guardBlocked (:data ev-node))) "event-node is blocked")
      (is (true? (:guardBlocked (:data in-edge)))
          "source→event-node half carries the blocked overlay")
      (is (= (:edge-guard-blocked ct) (:color (:markerEnd in-edge)))
          "the `__in` arrowhead is the PINK guard-blocked hue")
      ;; The overlay STOPS at the event-node — the onward half stays resting.
      (is (false? (:guardBlocked (:data out-edge)))
          "event-node→target half is NOT blocked — the highlight stops here")
      (is (not= (:edge-guard-blocked ct) (:color (:markerEnd out-edge)))
          "the `__out` arrowhead is NOT the pink guard-blocked hue — the
           onward arrow must not imply the transition progressed"))))

(deftest xyflow-graph-no-guard-blocked-ids-leaves-all-unblocked
  (testing "rf2-fzrzlw — omitting :guard-blocked-edge-ids leaves EVERY edge
            + event-node `:guardBlocked false`"
    (let [parsed (layout/project-definition door-cyclic-machine)
          graph  (projection/xyflow-graph parsed {} {})]
      (is (seq (:edges graph)))
      (is (every? #(false? (:guardBlocked (:data %))) (:edges graph))
          "no guard-blocked set → no guard-blocked edges")
      (is (every? #(false? (:guardBlocked (:data %)))
                  (filter #(= "rf2-event" (:type %)) (:nodes graph)))
          "no guard-blocked set → no guard-blocked event-nodes"))))

(deftest xyflow-graph-guard-blocked-marker-colour-distinct
  (testing "rf2-fzrzlw — a guard-blocked edge's arrowhead colour is the
            PINK guard-blocked hue, distinct from a non-blocked edge's, so
            the attempted-and-rejected edge stands out"
    (let [parsed     (layout/project-definition door-cyclic-machine)
          close-id   (door-close-edge-id parsed)
          ct         (tokens/chart-tokens)
          graph      (projection/xyflow-graph
                       parsed {} {:guard-blocked-edge-ids #{close-id}})
          blocked-in (inbound-edge-for graph close-id)
          plain-e    (first (remove #(or (= (:id %) (:id blocked-in))
                                         (:guardBlocked (:data %))
                                         (:entry (:data %)))
                                    (:edges graph)))]
      (is (some? plain-e) "fixture has a non-blocked transition edge")
      (is (= (:edge-guard-blocked ct) (:color (:markerEnd blocked-in)))
          "the blocked arrowhead paints the pink guard-blocked hue")
      (is (not= (:color (:markerEnd blocked-in))
                (:color (:markerEnd plain-e)))
          "blocked vs non-blocked arrowheads are distinct colours"))))

(deftest xyflow-graph-guard-blocked-wins-over-active-affordance
  (testing "rf2-fzrzlw design call (2) — when the source state is ACTIVE
            (all-exits affordance-blue) AND the edge is guard-blocked, the
            blocked PINK overrides the affordance-blue on that edge so it
            stands out (does not merely sit under the all-exits-blue)."
    (let [parsed     (layout/project-definition door-cyclic-machine)
          close-id   (door-close-edge-id parsed)
          ct         (tokens/chart-tokens)
          ;; :open is active → its exits are affordance-blue; :door/close is
          ;; ALSO guard-blocked, so its arrowhead must read PINK, not blue.
          graph      (projection/xyflow-graph
                       parsed {} {:highlight-ids #{(layout/node-id [:open])}
                                  :guard-blocked-edge-ids #{close-id}})
          blocked-in (inbound-edge-for graph close-id)]
      (is (true? (:active (:data blocked-in)))
          "the source :open is active, so this exit is affordance-active")
      (is (true? (:guardBlocked (:data blocked-in)))
          "AND it is guard-blocked")
      (is (= (:edge-guard-blocked ct) (:color (:markerEnd blocked-in)))
          "the PINK guard-blocked hue WINS over the affordance-blue"))))

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
    (let [parsed   (layout/project-definition parent-level-transition-machine)
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
    (let [parsed (layout/project-definition parent-level-transition-machine)
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
    (let [parsed (layout/project-definition parent-level-transition-machine)
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
    (let [parsed (layout/project-definition parent-level-transition-machine)
          graph  (projection/xyflow-graph parsed {} {})
          parsed-ids (set (map :id (:edges parsed)))
          ev-node-ids (set (map :id (filter #(= "rf2-event" (:type %))
                                            (:nodes graph))))
          expected (set (map #(str "event__" %) parsed-ids))]
      (is (= expected ev-node-ids)
          "every parsed edge id has a matching event-node"))))

;; ---- multi-event NO-collapse: distinct event-nodes (rf2-o6vh7) ----------
;;
;; HISTORY: rf2-shv82 (Issue 2) introduced a per-source self-loop perimeter
;; fan; rf2-j10sm (Phase 2, B) replaced it with a multi-event sibling-
;; collapse (N self-loops → ONE arc + N stacked labels via `:siblingIndex` /
;; `:siblingCount`). rf2-o6vh7 RETIRED that collapse: under events-as-nodes
;; every event is its OWN first-class node, so N events on one
;; `[source target]` pair stay N DISTINCT event-nodes (no grouping, no
;; leader/follower, no `:siblingIndex`/`:siblingCount`). These tests pin the
;; no-collapse contract: parsed-edge count == event-node count, always.

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
    (let [parsed (layout/project-definition multi-self-loop-machine)
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
    (let [parsed   (layout/project-definition self-loop-machine)
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
          parsed (layout/project-definition m)
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
      (let [parsed   (layout/project-definition m)
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
    (let [parsed (layout/project-definition cross-hierarchy-machine)
          graph  (projection/xyflow-graph parsed {} {})
          escape (first (filter #(= :escape (:event %)) (:edges parsed)))
          out-edge (outbound-edge-for graph (:id escape))]
      (is (some? out-edge))
      (is (true? (:crossHierarchy (:data out-edge)))
          "inner→sibling escapes the :outer container"))))

(deftest xyflow-graph-same-parent-edge-not-cross-hierarchy
  (testing "rf2-shv82 (Issue 3) + rf2-qo5xy — an outbound edge between
            two siblings under the SAME parent is NOT cross-hierarchy."
    (let [parsed (layout/project-definition compound-machine)
          graph  (projection/xyflow-graph parsed {} {})
          checkout (first (filter #(= :checkout (:event %)) (:edges parsed)))
          out-edge (outbound-edge-for graph (:id checkout))]
      (is (some? out-edge))
      (is (false? (:crossHierarchy (:data out-edge)))))))

(deftest xyflow-graph-flat-machine-no-cross-hierarchy
  (testing "rf2-shv82 (Issue 3) + rf2-qo5xy — a flat machine has no
            containers, so no outbound edge is cross-hierarchy."
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          out-edges (filter #(:outbound (:data %)) (:edges graph))]
      (is (seq out-edges))
      (is (every? #(false? (:crossHierarchy (:data %))) out-edges)))))

(deftest xyflow-graph-self-routing-not-cross-hierarchy
  (testing "rf2-shv82 (Issue 3) + rf2-qo5xy — a self-routing transition
            (source == target) is never cross-hierarchy regardless of
            container nesting."
    (let [parsed (layout/project-definition self-loop-machine)
          graph  (projection/xyflow-graph parsed {} {})
          ping   (first (filter #(= (:source %) (:target %)) (:edges parsed)))
          out-edge (outbound-edge-for graph (:id ping))]
      (is (some? out-edge))
      (is (false? (:crossHierarchy (:data out-edge)))))))

(deftest xyflow-graph-entry-edges-carry-cross-hierarchy-false
  (testing "rf2-shv82 — entry edges keep the every-edge :data shape
            whole: they carry :crossHierarchy false"
    (let [parsed (layout/project-definition cross-hierarchy-machine)
          graph  (projection/xyflow-graph parsed {} {})
          entry  (first (filter #(:entry (:data %)) (:edges graph)))]
      (is (some? entry))
      (is (false? (:crossHierarchy (:data entry))))
      ;; rf2-o6vh7 — sibling-collapse retired: no :siblingIndex/:siblingCount
      ;; on any edge :data, entry edges included. rf2-hstzzj — the
      ;; self-loop-fan keys (:selfLoop / :loopIndex) are likewise gone.
      (is (not (contains? (:data entry) :selfLoop)))
      (is (not (contains? (:data entry) :loopIndex)))
      (is (not (contains? (:data entry) :siblingIndex)))
      (is (not (contains? (:data entry) :siblingCount))))))

;; ---- :on-done (XState onDone) projection (rf2-41goo) -------------------
;;
;; The compound / parallel completion transition projects as an
;; events-as-nodes event-node carrying the ✓ done chip + `:onDone` /
;; `:doneState` data hooks (the renderer's completion affordance). The
;; reserved `:rf.machine/done` is engine-RAISED, so the event-node is NOT
;; click-to-send (no `:eventId`).

(def checkout-on-done
  "Spec 005 §The done-state signal: a compound `:flow` whose `:on-done`
  advances to the sibling `:next` when its `:final?` `:paid` is reached."
  {:initial :flow
   :states  {:flow {:initial :collecting
                    :on-done :next
                    :states  {:collecting {:on {:submit :submitting}}
                              :submitting {:on {:ok :paid}}
                              :paid       {:final? true}}}
             :next {:on {:reset [:flow]}}}})

(def ingest-on-done
  "Spec 005 §The done-state signal parallel example: parallel-root
  `:on-done` runs action-only (no :target)."
  {:type    :parallel
   :on-done {:action :announce}
   :regions {:fetch    {:initial :loading :states {:loading {:on {:loaded :done}} :done {:final? true}}}
             :validate {:initial :checking :states {:checking {:on {:ok :done}} :done {:final? true}}}}})

(deftest xyflow-graph-compound-on-done-projects-done-event-node
  (testing "rf2-41goo — a compound `:on-done` projects an event-node with
            the ✓ done chip + :onDone true + the done.state.<id> label;
            the inbound/outbound edges wire compound → done-node → sibling"
    (let [parsed   (layout/project-definition checkout-on-done)
          od-edge  (first (filter :on-done? (:edges parsed)))
          graph    (projection/xyflow-graph parsed {} {})
          ev-node  (event-node-for graph (:id od-edge))]
      (is (some? od-edge) "fixture sanity: the parse emits an :on-done edge")
      (is (some? ev-node) "the :on-done transition projects an event-node")
      (is (= "✓ done" (:eventLabel (:data ev-node))) "the ✓ done completion chip")
      (is (= "on-done" (:variant (:data ev-node))) "bucketed as the :on-done variant")
      (is (true? (:onDone (:data ev-node))) "flagged :onDone for the renderer hook")
      (is (= (str "done.state." (layout/node-id [:flow])) (:doneState (:data ev-node)))
          "carries the SCXML-style done.state.<id> label")
      (is (nil? (:eventId (:data ev-node)))
          "the engine-raised :rf.machine/done is NOT click-to-send")
      ;; the outbound edge lands on the sibling :next
      (let [out (outbound-edge-for graph (:id od-edge))]
        (is (some? out) "a targeted compound :on-done has an outbound segment")
        (is (= (layout/node-id [:next]) (:target out))
            "the completion edge advances to the sibling :next")))))

(deftest xyflow-graph-parallel-root-on-done-is-terminal-event-node
  (testing "rf2-41goo — a parallel-root `:on-done` (action-only, internal)
            projects a TERMINAL event-node (no outbound segment) carrying
            the ✓ done chip + the action; not click-to-send"
    (let [parsed   (layout/project-definition ingest-on-done)
          od-edge  (first (filter :on-done? (:edges parsed)))
          graph    (projection/xyflow-graph parsed {} {})
          ev-node  (event-node-for graph (:id od-edge))]
      (is (some? ev-node) "the parallel-root :on-done projects an event-node")
      (is (true? (:onDone (:data ev-node))))
      (is (true? (:internal (:data ev-node)))
          "a parallel-root :on-done is a terminal (internal) affordance")
      (is (= "announce" (:action (:data ev-node))) "the action surfaces on the chip")
      ;; internal ⇒ NO outbound edge (terminal affordance, no sibling)
      (is (nil? (outbound-edge-for graph (:id od-edge)))
          "a terminal parallel-root :on-done has no outgoing segment"))))

(deftest xyflow-graph-no-on-done-projects-no-done-node
  (testing "rf2-41goo — a machine with no :on-done projects no :onDone
            event-node (no false-positive completion chips)"
    (let [graph (projection/xyflow-graph
                  (layout/project-definition compound-machine) {} {})]
      (is (empty? (filter #(:onDone (:data %)) (:nodes graph)))))))

;; ---- parallel-root completion ANCHOR is inert (rf2-dblqx) ---------------
;;
;; rf2-dblqx — the synthetic PARALLEL-ROOT node (rf2-41goo's anchor for the
;; whole-parallel `:on-done` completion affordance) is NOT a statechart
;; state — it is a rendering sentinel (`:path
;; [:rf.machines-viz.layout/parallel-root]`). Pre-fix it fell through the
;; node-:type cond to `"state"` AND through the `:onClick` guard (which
;; excluded only `:machine-root?` + region), so it projected as a CLICKABLE
;; `parallel` state box — clicking it would dispatch on-state-click against
;; the phantom sentinel path. The SAME inert-synthetic-chip class rf2-34ff3
;; fixed for the machine-root chip + region containers; this pins that the
;; parallel-root anchor is INERT (typed `"machine-root"`, carries NO
;; `:onClick`), mirroring the 34ff3 guard.

(defn- parallel-root-anchor
  "The synthetic parallel-root completion-anchor node in a projected
  parallel-with-:on-done graph. It is the only `\"machine-root\"`-typed
  node in `ingest-on-done` (the regions carry no top-level `:on`, so no
  region mints a machine-root chip)."
  [graph]
  (first (filter #(= "machine-root" (:type %)) (:nodes graph))))

(deftest xyflow-graph-parallel-root-anchor-is-inert
  (testing "rf2-dblqx — the parallel-root :on-done anchor is INERT: it is
            NOT typed `state` and carries NO :onClick (mirroring the
            rf2-34ff3 inert machine-root + region containers), so a user
            cannot click a phantom `parallel` state and fire on-state-click
            against the rendering sentinel path."
    (let [cb     (fn [_path] :clicked)
          parsed (layout/project-definition ingest-on-done)
          graph  (projection/xyflow-graph parsed {} {:on-state-click cb})
          anchor (parallel-root-anchor graph)]
      (is (some? anchor)
          "a parallel machine with a root :on-done projects the anchor node")
      (is (= "machine-root" (:type anchor))
          "the parallel-root anchor is a quiet root-context chip, NOT a state box")
      (is (not= "state" (:type anchor))
          "rf2-dblqx — the anchor must NOT fall through to the `state` type")
      (is (not (contains? (:data anchor) :onClick))
          "rf2-dblqx — the parallel-root anchor carries NO :onClick (not an
           on-state-click target — its path is a rendering sentinel)")
      ;; the real region leaves still carry :onClick (the fix is surgical)
      (let [leaf (node-by-id graph (layout/region-scoped-id :fetch [:loading]))]
        (is (= cb (:onClick (:data leaf)))
            "a real leaf inside a region still carries :onClick")))))

;; ---- parallel-root done-state label matches SCXML (rf2-bs3us) -----------
;;
;; rf2-bs3us — the parallel-root done-path is the engine's root sentinel
;; `[]`, whose `node-id` is the EMPTY string, so the naive
;; `(str "done.state." (node-id done-path))` yielded the degenerate
;; `"done.state."` (trailing dot, no id) — diverging from the SCXML
;; emitter's `"done.state.rf2_parallel_root"`. The chart now uses the
;; shared canonical sentinel id so the two emitters agree.

(deftest xyflow-graph-parallel-root-done-state-matches-scxml
  (testing "rf2-bs3us — the parallel-root :on-done :doneState label is the
            non-degenerate `done.state.<id>` form (matching the SCXML
            emitter), not the empty `done.state.`"
    (let [parsed  (layout/project-definition ingest-on-done)
          od-edge (first (filter :on-done? (:edges parsed)))
          graph   (projection/xyflow-graph parsed {} {})
          ev-node (event-node-for graph (:id od-edge))
          done    (:doneState (:data ev-node))]
      (is (some? ev-node))
      (is (= (str "done.state." layout/parallel-root-done-state-id) done)
          "the chart label uses the shared canonical parallel-root id")
      (is (not= "done.state." done)
          "rf2-bs3us — NOT the degenerate empty-suffix label")
      (is (str/ends-with? done "rf2_parallel_root")
          "matches the SCXML emitter's done.state.rf2_parallel_root form"))))

;; ---- guarded-fork branch-order priority badge (rf2-uw3vmi) -------------
;;
;; Stately renders a guarded multi-branch fork — the gate machine's
;; `:gate/check` 3-way (`[{:guard :gate-high? :target :high}
;; {:guard :gate-low? :target :low} {:target :rejected}]`) — with NUMBERED
;; priority badges (①②③) communicating the deterministic first-pass-wins
;; evaluation order. re-frame2's candidate vector IS ordered and the parse
;; preserves it, so the projector threads each fork branch's 1-based index
;; onto the event-node `:data {:forkOrder}`. These pins guard:
;;   - the gate fork's three branches badge 1 / 2 / 3 in candidate order;
;;   - a SINGLE transition (and ordinary multi-event states) carry NO badge;
;;   - DISTINCT triggers on one source never merge into a fork;
;;   - a guardless same-trigger group is NOT badged (no ordered evaluation).

(def ^:private gate-fork-machine
  "rf2-uw3vmi — the gate testbed shape (machine 10 in the machine-epochs
  testbed): `:gate/check` FORKS from `:idle` by a guarded candidate VECTOR
  — first guard-pass wins (`:gate-high?` → :high, `:gate-low?` → :low, else
  the unguarded fallback → :rejected). `:gate/set` is a SEPARATE internal
  action-only transition on the SAME source — a DIFFERENT trigger, so it is
  never part of the fork."
  {:initial :idle
   :data    {:level 0}
   :states  {:idle     {:on {:gate/set   {:action :set-level}
                             :gate/check [{:guard :gate-high? :target :high}
                                          {:guard :gate-low?  :target :low}
                                          {:target :rejected}]}}
             :low      {:on {:gate/reset :idle}}
             :high     {:on {:gate/reset :idle}}
             :rejected {:on {:gate/reset :idle}}}})

(defn- fork-order-of
  "The `:forkOrder` the projected event-node carries for a parsed-edge id
  (nil when un-badged)."
  [graph parsed-edge-id]
  (:forkOrder (:data (event-node-for graph parsed-edge-id))))

(deftest fork-order-by-edge-id-badges-guarded-fork-in-candidate-order
  (testing "rf2-uw3vmi — the gate `:gate/check` 3-way's branches carry
            1-based priorities in candidate-vector order (gate-high? → 1,
            gate-low? → 2, unguarded fallback → 3); the SEPARATE
            `:gate/set` trigger on the same source is NOT a fork branch."
    (let [parsed (layout/project-definition gate-fork-machine)
          checks (filter #(= :gate/check (:event %)) (:edges parsed))
          fmap   (projection/fork-order-by-edge-id (:edges parsed))
          by-guard (into {} (map (juxt :guard identity) checks))]
      (is (= 3 (count checks)) "fixture forks `:gate/check` three ways")
      (is (= 1 (get fmap (:id (get by-guard :gate-high?))))
          "gate-high? is the first candidate → priority 1")
      (is (= 2 (get fmap (:id (get by-guard :gate-low?))))
          "gate-low? is the second candidate → priority 2")
      (is (= 3 (get fmap (:id (get by-guard nil))))
          "the unguarded fallback is the third candidate → priority 3")
      ;; `:gate/set` (a different trigger on :idle) is never a fork branch.
      (let [set-edge (first (filter #(= :gate/set (:event %)) (:edges parsed)))]
        (is (nil? (get fmap (:id set-edge)))
            "the separate `:gate/set` trigger carries no fork priority"))
      ;; `:gate/reset` leaves three DIFFERENT sources, one each — no fork.
      (doseq [reset (filter #(= :gate/reset (:event %)) (:edges parsed))]
        (is (nil? (get fmap (:id reset)))
            "each single `:gate/reset` transition is un-badged")))))

(deftest xyflow-graph-threads-fork-order-onto-event-nodes
  (testing "rf2-uw3vmi — the projector threads each fork branch's 1-based
            priority onto the event-node `:data {:forkOrder}`; non-fork
            event-nodes carry nil."
    (let [parsed (layout/project-definition gate-fork-machine)
          graph  (projection/xyflow-graph parsed {} {})
          checks (filter #(= :gate/check (:event %)) (:edges parsed))
          by-guard (into {} (map (juxt :guard identity) checks))]
      (is (= 1 (fork-order-of graph (:id (get by-guard :gate-high?)))))
      (is (= 2 (fork-order-of graph (:id (get by-guard :gate-low?)))))
      (is (= 3 (fork-order-of graph (:id (get by-guard nil)))))
      ;; the three branch event-nodes carry the FULL set of priorities
      (is (= #{1 2 3}
             (set (keep #(fork-order-of graph (:id %)) checks)))
          "all three branches badged exactly once each, 1..3"))))

(deftest xyflow-graph-single-transition-has-no-fork-badge
  (testing "rf2-uw3vmi — a single (non-fork) transition carries NO
            :forkOrder. The `self-loop-machine` / `compound-machine`
            fixtures have only one candidate per (source,trigger)."
    (doseq [m [self-loop-machine compound-machine idle-loading]]
      (let [parsed (layout/project-definition m)
            graph  (projection/xyflow-graph parsed {} {})
            ev-nodes (filter #(= "rf2-event" (:type %)) (:nodes graph))]
        (is (every? #(nil? (:forkOrder (:data %))) ev-nodes)
            (str "fixture " m " has no guarded fork → no fork badges"))))))

(deftest fork-order-distinct-triggers-not-merged
  (testing "rf2-uw3vmi — two transitions leaving one source under DIFFERENT
            triggers are independent, never a fork (no shared candidate
            order to communicate)."
    (let [m {:initial :a
             :states {:a {:on {:go-x {:guard :gx? :target :b}
                               :go-y {:guard :gy? :target :c}}}
                      :b {} :c {}}}
          parsed (layout/project-definition m)
          fmap   (projection/fork-order-by-edge-id (:edges parsed))]
      (is (empty? fmap)
          "go-x and go-y are distinct events → two singleton groups, no fork"))))

(deftest fork-order-guardless-multi-target-not-badged
  (testing "rf2-uw3vmi — a guardless same-trigger group (no ordered
            evaluation semantics to surface) is NOT badged; the fork
            affordance keys on a GUARDED fork."
    (let [m {:initial :a
             ;; two candidates, same event, NEITHER guarded
             :states {:a {:on {:go [{:target :b} {:target :c}]}}
                      :b {} :c {}}}
          parsed (layout/project-definition m)
          fmap   (projection/fork-order-by-edge-id (:edges parsed))]
      (is (empty? fmap)
          "a guardless same-event group has no priority badges"))))

(deftest fork-order-partial-guarded-fork-badges-all-branches
  (testing "rf2-uw3vmi — a fork where only SOME branches carry a guard (the
            gate shape: two guarded + an unguarded fallback) badges EVERY
            branch in order — the fallback is the last candidate evaluated."
    (let [m {:initial :a
             :states {:a {:on {:go [{:guard :g1? :target :b}
                                    {:target :c}]}}
                      :b {} :c {}}}
          parsed (layout/project-definition m)
          fmap   (projection/fork-order-by-edge-id (:edges parsed))
          gos    (filter #(= :go (:event %)) (:edges parsed))]
      (is (= 2 (count fmap)) "both branches of the partial-guarded fork badged")
      (is (= #{1 2} (set (vals fmap))) "priorities 1 and 2")
      (is (= 1 (get fmap (:id (first (filter :guard gos)))))
          "the guarded branch leads (candidate 1)")
      (is (= 2 (get fmap (:id (first (remove :guard gos)))))
          "the unguarded fallback is candidate 2"))))

;; ---- guarded-fork dotted evaluation-order connector (rf2-o3rkq1) -------
;;
;; FOLLOW-ON to the numbered badges (rf2-uw3vmi): Stately joins the
;; branches of a guarded multi-branch fork with a DOTTED connector linking
;; the numbered branches IN ORDER (1→2→3), reinforcing the first-pass-wins
;; evaluation order. The projector emits N-1 DECORATIVE connector edges per
;; fork group, linking each branch's EVENT-NODE to the next in priority
;; order. These are RENDER-ONLY: appended to the xyflow `:edges` AFTER the
;; ELK layout pass, never fed to ELK (`->elk-edges` drives ELK off the
;; parsed graph alone), so the connector cannot move a single node. These
;; pins guard:
;;   - the gate fork emits exactly two connector edges (1→2, 2→3) between
;;     the three branch event-nodes, in priority order;
;;   - they carry `:forkConnector true` + the full every-edge `:data` shape
;;     (so the projection invariants hold) with no route `:points`;
;;   - NO connector edge appears in the ELK input (`->elk-edges`), so the
;;     layout is untouched;
;;   - a non-fork machine emits NO connector edges.

(defn- connector-edges-of
  "The decorative fork-connector edges a projected graph emitted
  (`fork-connector__…` ids / `:forkConnector` data flag)."
  [graph]
  (filter #(:forkConnector (:data %)) (:edges graph)))

(deftest fork-connector-edges-link-branches-in-priority-order
  (testing "rf2-o3rkq1 — the gate `:gate/check` 3-way emits a dotted
            evaluation-order connector linking branch 1→2 and 2→3 (N-1
            connectors for an N-branch fork), each from one branch's
            event-node to the next in priority order."
    (let [parsed   (layout/project-definition gate-fork-machine)
          edges    (:edges parsed)
          conns    (projection/fork-connector-edges
                     edges (tokens/chart-tokens) vc/chart-regular)
          checks   (filter #(= :gate/check (:event %)) edges)
          by-guard (into {} (map (juxt :guard identity) checks))
          c1 (:id (get by-guard :gate-high?))   ;; priority 1
          c2 (:id (get by-guard :gate-low?))    ;; priority 2
          c3 (:id (get by-guard nil))           ;; priority 3
          ev #(str "event__" %)]
      (is (= 2 (count conns)) "3-branch fork → 2 connector edges (1→2, 2→3)")
      (let [pairs (set (map (juxt :source :target) conns))]
        (is (contains? pairs [(ev c1) (ev c2)])
            "connector links branch-1 event-node → branch-2 event-node")
        (is (contains? pairs [(ev c2) (ev c3)])
            "connector links branch-2 event-node → branch-3 event-node")
        (is (not (contains? pairs [(ev c1) (ev c3)]))
            "no connector skips a branch (1→3) — the chain is consecutive")))))

(deftest fork-connector-edges-carry-decorative-data-shape
  (testing "rf2-o3rkq1 — each connector edge carries `:forkConnector true`,
            no route `:points` (a straight dotted handle-to-handle line),
            and the full every-edge `:data` shape so the projection
            invariants hold."
    (let [parsed (layout/project-definition gate-fork-machine)
          conns  (projection/fork-connector-edges
                   (:edges parsed) (tokens/chart-tokens) vc/chart-cosy)]
      (is (seq conns) "the gate fork yields connector edges")
      (doseq [e conns]
        (is (true? (:forkConnector (:data e))) "flagged as a fork connector")
        (is (nil? (:points (:data e))) "no ELK route — straight dotted line")
        (is (= "transition" (:type e)) "the canonical transition edge type")
        (is (= vc/chart-cosy (:chart (:data e))) "carries the resolved density")
        (is (false? (:fired (:data e))) "decorative, never a fired arm")
        (is (false? (:active (:data e))) "decorative, never an active arm")
        (is (re-find #"^fork-connector__" (:id e))
            "stable fork-connector id prefix")))))

(deftest fork-connector-edges-anchor-to-explicit-side-handles
  (testing "rf2-4vvywg — every connector edge anchors to EXPLICIT side
            handles (`:sourceHandle \"right\"` + `:targetHandle \"left\"`)
            rather than xyflow's default handle pick. The branch event-nodes
            lay out LEFT-TO-RIGHT in priority order (rf2-p75kbg), so the
            order chain must read side-to-side: it leaves each branch from
            its RIGHT source handle and enters the next from its LEFT target
            handle (the named cardinal handles `four-cardinal-handles`
            emits). Without these, xyflow may attach BOTTOM→TOP off the
            unnamed cardinal handles, making the priority chain awkward."
    (let [parsed (layout/project-definition gate-fork-machine)
          conns  (projection/fork-connector-edges
                   (:edges parsed) (tokens/chart-tokens) vc/chart-regular)]
      (is (seq conns) "the gate fork yields connector edges")
      (doseq [e conns]
        (is (= "right" (:sourceHandle e))
            "leaves the source branch from its RIGHT source handle")
        (is (= "left" (:targetHandle e))
            "enters the next branch from its LEFT target handle")))))

(deftest fork-connector-handles-read-as-clean-order-chain
  (testing "rf2-4vvywg — the connector edges form a clean side-to-side
            ORDER CHAIN: each consecutive pair links one branch's RIGHT
            source handle to the next branch's LEFT target handle, and the
            chain is consecutive (1's right → 2's left, 2's right → 3's
            left — no edge skips a branch and none attaches an unnamed
            top/bottom handle), so the priority line reads left-to-right
            without depending on xyflow's default-handle internals."
    (let [parsed   (layout/project-definition gate-fork-machine)
          conns    (projection/fork-connector-edges
                     (:edges parsed) (tokens/chart-tokens) vc/chart-regular)
          edges    (:edges parsed)
          checks   (filter #(= :gate/check (:event %)) edges)
          by-guard (into {} (map (juxt :guard identity) checks))
          c1 (:id (get by-guard :gate-high?))   ;; priority 1
          c2 (:id (get by-guard :gate-low?))    ;; priority 2
          c3 (:id (get by-guard nil))           ;; priority 3
          ev #(str "event__" %)
          ;; index every connector by its [source target] event-node pair.
          by-pair (into {} (map (juxt (juxt :source :target) identity)) conns)]
      (is (= 2 (count conns)) "3-branch fork → 2 connector edges (1→2, 2→3)")
      (let [link-1->2 (get by-pair [(ev c1) (ev c2)])
            link-2->3 (get by-pair [(ev c2) (ev c3)])]
        (is (some? link-1->2) "the 1→2 link exists")
        (is (some? link-2->3) "the 2→3 link exists")
        (doseq [link [link-1->2 link-2->3]]
          (is (= "right" (:sourceHandle link)) "each link leaves a RIGHT handle")
          (is (= "left" (:targetHandle link)) "each link enters a LEFT handle"))
        ;; The chain is a true order chain: branch 2 is BOTH the target of
        ;; link 1→2 (entered on its LEFT) AND the source of link 2→3 (left
        ;; from its RIGHT) — a clean pass-through, not a fan from one node.
        (is (= (:target link-1->2) (:source link-2->3))
            "branch 2 is the hinge: entered on the left, left on the right")
        ;; No connector attaches an unnamed (top/bottom) cardinal handle.
        (is (not-any? #(contains? #{nil "top" "bottom"} (:sourceHandle %)) conns)
            "no connector leaves a top/bottom source handle")
        (is (not-any? #(contains? #{nil "top" "bottom"} (:targetHandle %)) conns)
            "no connector enters a top/bottom target handle")))))

(deftest xyflow-graph-fork-connector-handles-survive-projection
  (testing "rf2-4vvywg — the explicit side-handle anchoring survives the
            full `xyflow-graph` projection (the connector edges are appended
            post-ELK), so the rendered xyflow edges carry the handle ids."
    (let [parsed (layout/project-definition gate-fork-machine)
          graph  (projection/xyflow-graph parsed {} {})
          conns  (connector-edges-of graph)]
      (is (seq conns) "the projected graph carries the fork connector edges")
      (doseq [e conns]
        (is (= "right" (:sourceHandle e)) "RIGHT source handle survives projection")
        (is (= "left" (:targetHandle e)) "LEFT target handle survives projection")))))

(deftest xyflow-graph-appends-fork-connector-edges
  (testing "rf2-o3rkq1 — `xyflow-graph` appends the decorative connector
            edges to its `:edges` output (alongside the route + entry
            edges) for a guarded fork."
    (let [parsed (layout/project-definition gate-fork-machine)
          graph  (projection/xyflow-graph parsed {} {})
          conns  (connector-edges-of graph)]
      (is (= 2 (count conns))
          "the projected graph carries the gate fork's two connector edges")
      ;; the connector edges reference the SAME branch event-node ids the
      ;; graph emits as `\"rf2-event\"` nodes — so xyflow can wire them.
      (let [ev-node-ids (set (map :id (filter #(= "rf2-event" (:type %))
                                              (:nodes graph))))]
        (doseq [e conns]
          (is (contains? ev-node-ids (:source e))
              "connector source is a real event-node in the graph")
          (is (contains? ev-node-ids (:target e))
              "connector target is a real event-node in the graph"))))))

(deftest fork-connector-edges-never-reach-elk-layout
  (testing "rf2-o3rkq1 — the decorative connector is RENDER-ONLY: it is
            NEVER fed to ELK. The ELK input edges (`->elk-edges`) carry
            only the events-as-nodes `__in` / `__out` route halves, so no
            connector can perturb a node position."
    (let [parsed   (layout/project-definition gate-fork-machine)
          elk-eds  (projection/->elk-edges parsed)
          elk-ids  (map :id elk-eds)]
      (is (seq elk-eds) "the fork machine feeds ELK its route edges")
      (is (not-any? #(re-find #"^fork-connector__" %) elk-ids)
          "NO fork-connector edge appears in the ELK input graph")
      (is (every? #(or (re-find #"__in$" %) (re-find #"__out$" %)) elk-ids)
          "every ELK edge is a route half — the connector is absent"))))

(deftest no-fork-no-connector-edges
  (testing "rf2-o3rkq1 — a machine with no guarded multi-branch fork emits
            NO connector edges (a single transition / distinct triggers /
            a guardless multi-target group are not forks)."
    (doseq [m [self-loop-machine compound-machine idle-loading]]
      (let [parsed (layout/project-definition m)
            graph  (projection/xyflow-graph parsed {} {})]
        (is (empty? (connector-edges-of graph))
            (str "fixture " m " has no guarded fork → no connector edges"))
        (is (empty? (projection/fork-connector-edges
                      (:edges parsed) (tokens/chart-tokens) vc/chart-regular))
            (str "fixture " m " yields no connector edges directly"))))))

;; ---- guarded-fork branch LAYOUT ORDER (rf2-p75kbg) ----------------------

(deftest fork-branch-event-positions-maps-event-nodes-to-priority
  (testing "rf2-p75kbg — each guarded-fork branch EVENT-NODE id maps to its
            1-based priority index (re-keyed from the spec edge-id via
            `event-node-id`), so the `elk.position` within-layer hint stacks
            the gate fork 1,2,3."
    (let [edges    (:edges (layout/project-definition gate-fork-machine))
          pos      (projection/fork-branch-event-positions edges)
          checks   (filter #(= :gate/check (:event %)) edges)
          by-guard (into {} (map (juxt :guard identity) checks))
          ev       #(projection/event-node-id (get by-guard %))]
      (is (= 3 (count pos)) "all three fork branches get a position")
      (is (= 1 (get pos (ev :gate-high?))) "gate-high? → 1 (priority 1)")
      (is (= 2 (get pos (ev :gate-low?)))  "gate-low? → 2 (priority 2)")
      (is (= 3 (get pos (ev nil)))         "rejected fallback → 3 (priority 3)")
      ;; the non-fork `:gate/set` (a different trigger) gets NO position hint.
      (let [set-edge (first (filter #(= :gate/set (:event %)) edges))]
        (is (not (contains? pos (projection/event-node-id set-edge)))
            "the non-fork :gate/set event-node carries no position hint")))))

(deftest fork-branch-container-ids-flags-only-fork-holders
  (testing "rf2-p75kbg — the holding-container set carries the gate fork's
            holding container, and is EMPTY for a machine without a guarded
            fork (so semiInteractive is never enabled where there is nothing
            to order).

            rf2-q129z8 — the gate fork's branches (leaving the top-level
            `:idle`) now lay out under the synthetic ROOT-CONTAINER frame
            rather than at the bare root, so the holding container is the
            frame id (was nil)."
    (is (= #{layout/root-container-id}
           (projection/fork-branch-container-ids
             (layout/project-definition gate-fork-machine)))
        "gate fork's branches lay out inside the root frame")
    (doseq [m [self-loop-machine compound-machine idle-loading]]
      (is (empty? (projection/fork-branch-container-ids
                    (layout/project-definition m)))
          (str "non-fork fixture " m " flags no fork container")))))

(deftest elk-children-pin-fork-branches-in-priority-order
  (testing "rf2-p75kbg — `->elk-children` tags each gate-fork branch
            event-node with the `elk.position` KVector hint == its priority
            index (x = order, y = 0, for the DOWN-direction cross-axis), and
            leaves every non-fork node unpinned."
    (let [parsed   (layout/project-definition gate-fork-machine)
          edges    (:edges parsed)
          kids     (root-children (projection/->elk-children parsed))
          opts-of  (fn [id] (-> (filter #(= id (:id %)) kids) first :layoutOptions))
          checks   (filter #(= :gate/check (:event %)) edges)
          by-guard (into {} (map (juxt :guard identity) checks))
          ev       #(projection/event-node-id (get by-guard %))]
      (is (= {"elk.position" "(1,0)"} (opts-of (ev :gate-high?)))
          "branch 1 pinned at cross-axis x=1")
      (is (= {"elk.position" "(2,0)"} (opts-of (ev :gate-low?)))
          "branch 2 pinned at cross-axis x=2")
      (is (= {"elk.position" "(3,0)"} (opts-of (ev nil)))
          "branch 3 pinned at cross-axis x=3")
      ;; the non-fork :gate/set event-node carries NO position pin.
      (let [set-edge (first (filter #(= :gate/set (:event %)) edges))]
        (is (nil? (opts-of (projection/event-node-id set-edge)))
            "non-fork event-node is unpinned")))))

(deftest elk-children-no-fork-no-position-pins
  (testing "rf2-p75kbg — a machine with no guarded fork carries NO
            `elk.position` pins on any child (the ordering machinery is a
            no-op where there is no fork to straighten)."
    (doseq [m [self-loop-machine compound-machine idle-loading]]
      (let [kids (root-children
                   (projection/->elk-children (layout/project-definition m)))]
        (is (not-any? #(contains? (:layoutOptions %) "elk.position") kids)
            (str "non-fork fixture " m " pins no node position"))))))

;; ---- guarded-fork semiInteractive on the REAL root-container child ------
;; rf2-rcszre — the gate fork's branches lay out UNDER the synthetic
;; ROOT-CONTAINER frame (rf2-q129z8), NOT at the bare ELK root. So the
;; `chart/elk-layout-options`-on-the-bare-root semiInteractive lever (pinned
;; in edges-cljs-test via a SYNTHETIC bare-root fixture) does NOT fire for
;; the real machine — instead the ROOT-CONTAINER child built by
;; `->elk-children` (`container-opts`) carries semiInteractive because
;; `fork-branch-container-ids` returns `#{root-container-id}`. This pin
;; guards that REAL post-root-container path the synthetic test routes
;; around: the root-container ELK child carries the semiInteractive option,
;; and a non-fork machine's root-container child does NOT.

(deftest elk-children-root-container-carries-semi-interactive-for-fork
  (testing "rf2-rcszre — the gate guarded fork's branches lay out under the
            ROOT-CONTAINER frame, so the root-container ELK child (the sole
            top-level `->elk-children` element) carries
            `elk.layered.crossingMinimization.semiInteractive = true` — the
            REAL post-root-container path the synthetic bare-root pin in
            edges-cljs-test routes around. Without this, the branch
            event-nodes' `elk.position` hints would be ignored and the
            dotted connector would weave."
    (let [parsed    (layout/project-definition gate-fork-machine)
          container (first (projection/->elk-children parsed))]
      (is (= layout/root-container-id (:id container))
          "the sole top-level ELK child is the root-container frame")
      (is (= "true"
             (get (:layoutOptions container)
                  "elk.layered.crossingMinimization.semiInteractive"))
          "the root-container child enables semiInteractive for the fork")
      ;; the branch event-children INSIDE it carry the elk.position hints
      ;; the semiInteractive option honours (the pair is what orders 1,2,3).
      (let [edges    (:edges parsed)
            checks   (filter #(= :gate/check (:event %)) edges)
            by-guard (into {} (map (juxt :guard identity) checks))
            ev       #(projection/event-node-id (get by-guard %))
            kids     (root-children (projection/->elk-children parsed))
            opts-of  (fn [id] (-> (filter #(= id (:id %)) kids) first :layoutOptions))]
        (is (= {"elk.position" "(1,0)"} (opts-of (ev :gate-high?)))
            "branch 1 event-node pinned at cross-axis x=1 inside the container")
        (is (= {"elk.position" "(2,0)"} (opts-of (ev :gate-low?)))
            "branch 2 event-node pinned at cross-axis x=2")
        (is (= {"elk.position" "(3,0)"} (opts-of (ev nil)))
            "branch 3 event-node pinned at cross-axis x=3")))))

(deftest elk-children-root-container-no-semi-interactive-without-fork
  (testing "rf2-rcszre — a machine with NO guarded fork does NOT enable
            semiInteractive on its root-container child, so the default
            full crossing-minimisation stands and no non-fork layout is
            perturbed."
    (doseq [m [self-loop-machine compound-machine idle-loading]]
      (let [container (first (projection/->elk-children
                               (layout/project-definition m)))]
        (is (not (contains? (:layoutOptions container)
                            "elk.layered.crossingMinimization.semiInteractive"))
            (str "non-fork fixture " m
                 " root-container child omits semiInteractive"))))))

;; ---- consumer-attachment requirements on :data (rf2-skhlw2.1) ----------
;;
;; EP-0017 / Spec 005 §Consumer attachment — the projection carries a named
;; guard / action / entry / exit consumer's declared `:rf.cofx/requires` onto
;; the event-node + state-node `:data` (camelCase so the JS-interop renderer
;; reads it after xyflow `clj->js`-es the map). IDS only — never the `:fn`.

(def cofx-projection-machine
  {:initial :idle
   :guards  {:within-window? {:rf.cofx/requires [:rf/time-ms]
                              :fn (fn [_] true)}}
   :actions {:schedule-retry {:rf.cofx/requires [:payment/retry-jitter-ms]
                             :fn (fn [_] nil)}
             :stamp-started  {:rf.cofx/requires [:rf/time-ms]
                             :fn (fn [_] nil)}
             :stamp-ended    {:rf.cofx/requires [:rf/uuid]
                             :fn (fn [_] nil)}}
   :states  {:idle {:entry :stamp-started
                    :exit  :stamp-ended
                    :on    {:go {:target :busy
                                 :guard  :within-window?
                                 :action :schedule-retry}}}
             :busy {}}})

(deftest event-node-data-carries-guard-action-requires
  (testing "rf2-skhlw2.1 — the :go event-node's :data carries
            :guardRequires / :actionRequires (the named callbacks' IDS)"
    (let [parsed (layout/project-definition cofx-projection-machine)
          graph  (projection/xyflow-graph parsed {} {})
          ;; the single :go transition's event-node (eventId is the raw kw).
          ev     (first (filter #(and (= "rf2-event" (:type %))
                                      (= :go (:eventId (:data %))))
                                (:nodes graph)))]
      (is (some? ev) "the :go transition projects an event-node")
      (is (= ["rf/time-ms"] (:guardRequires (:data ev))))
      (is (= ["payment/retry-jitter-ms"] (:actionRequires (:data ev)))))))

(deftest state-node-data-carries-entry-exit-requires
  (testing "rf2-skhlw2.1 — the :idle state-node's :data carries
            :entryRequires / :exitRequires (the lifecycle actions' IDS)"
    (let [parsed (layout/project-definition cofx-projection-machine)
          graph  (projection/xyflow-graph parsed {} {})
          idle   (node-by-id graph (layout/node-id [:idle]))]
      (is (= ["rf/time-ms"] (:entryRequires (:data idle))))
      (is (= ["rf/uuid"]    (:exitRequires (:data idle)))))))

(deftest fact-free-machine-omits-requires-data
  (testing "rf2-skhlw2.1 — a machine declaring no :rf.cofx/requires carries
            nil :guardRequires / :entryRequires (visually unchanged)"
    (let [parsed (layout/project-definition idle-loading)
          graph  (projection/xyflow-graph parsed {} {})
          ev     (first (filter #(= "rf2-event" (:type %)) (:nodes graph)))]
      (is (some? ev))
      (is (nil? (:guardRequires (:data ev))))
      (is (nil? (:actionRequires (:data ev)))))))

;; ---- lifecycle requires are region-aware (rf2-6l01c8) ------------------
;;
;; Two parallel regions can share the SAME in-region state path. The
;; raw-node lookup that recovers a node's `:entry`/`:exit` refs (to surface
;; the declared `:rf.cofx/requires`) must key off the node's OWN `:region`,
;; not the first region whose in-region path matches. Pre-fix it scanned
;; regions and returned the FIRST hit, so region B's node was decorated with
;; region A's `:entryRequires`/`:exitRequires` — a lifecycle cofx displayed
;; against the wrong parallel region.

(def cofx-parallel-dup-machine
  "A `:type :parallel` machine whose regions `:a` and `:b` each own a state
  named `:active` (identical in-region path `[:active]`) with DISTINCT entry
  / exit actions declaring DISTINCT `:rf.cofx/requires`. The fix must resolve
  each `:active` node's lifecycle refs within its OWN region."
  {:type    :parallel
   :actions {:a-enter {:rf.cofx/requires [:region-a/enter-fact] :fn (fn [_] nil)}
             :a-exit  {:rf.cofx/requires [:region-a/exit-fact]  :fn (fn [_] nil)}
             :b-enter {:rf.cofx/requires [:region-b/enter-fact] :fn (fn [_] nil)}
             :b-exit  {:rf.cofx/requires [:region-b/exit-fact]  :fn (fn [_] nil)}}
   :regions {:a {:initial :active
                 :states  {:active {:entry :a-enter :exit :a-exit}}}
             :b {:initial :active
                 :states  {:active {:entry :b-enter :exit :b-exit}}}}})

(deftest lifecycle-requires-resolve-per-parallel-region
  (testing "rf2-6l01c8 — duplicate-named parallel-region states carry their
            OWN region's :entryRequires / :exitRequires on the xyflow node
            :data, never a sibling region's"
    (let [parsed  (layout/project-definition cofx-parallel-dup-machine)
          graph   (projection/xyflow-graph parsed {} {})
          a-active (node-by-id graph (layout/region-scoped-id :a [:active]))
          b-active (node-by-id graph (layout/region-scoped-id :b [:active]))]
      (is (some? a-active) "region :a's :active node projects")
      (is (some? b-active) "region :b's :active node projects")
      ;; region :a's node carries ONLY region :a's lifecycle requires
      (is (= ["region-a/enter-fact"] (:entryRequires (:data a-active)))
          "region :a entry requires resolve within region :a")
      (is (= ["region-a/exit-fact"]  (:exitRequires (:data a-active)))
          "region :a exit requires resolve within region :a")
      ;; region :b's node carries ONLY region :b's lifecycle requires — the
      ;; pre-fix cross-region scan would have shown region :a's here
      (is (= ["region-b/enter-fact"] (:entryRequires (:data b-active)))
          "region :b entry requires resolve within region :b, not region :a's")
      (is (= ["region-b/exit-fact"]  (:exitRequires (:data b-active)))
          "region :b exit requires resolve within region :b, not region :a's"))))
