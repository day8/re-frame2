(ns day8.re-frame2-causa.panels.causality-graph-cljs-test
  "Pure-data tests for Causa's Causality Graph helpers
  (Phase 4, rf2-4rqs1).

  ## Why the `.cljc` + `_cljs_test` naming

  The file ends in `_cljs_test.cljc` so:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  Same dual-target pattern as `time_travel_helpers_cljs_test.cljc`.

  ## What's under test

    1. **project-cascades-to-graph** — turns cascade records into a
       `{:nodes :arrows :roots}` map. Roots, child edges, orphan
       handling are all asserted.
    2. **compute-layout** — produces deterministic pixel positions
       for every node; the bounding-box width / height accommodate
       every node.
    3. **filter-to-cascade** — slices the graph down to one cascade
       family; passing nil returns the graph unchanged.
    4. **enrich-cascades** — stitches the dispatched-event trace back
       onto each cascade record so :origin / :parent-dispatch-id are
       reachable from a cascade map alone."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-causa.panels.causality-graph-helpers :as h]))

;; ---- fixture builders ----------------------------------------------------

(defn- dispatched-ev
  "Build a `:event/dispatched` trace event for `dispatch-id`."
  ([id dispatch-id event-vec]
   (dispatched-ev id dispatch-id event-vec nil :app))
  ([id dispatch-id event-vec parent-dispatch-id]
   (dispatched-ev id dispatch-id event-vec parent-dispatch-id :app))
  ([id dispatch-id event-vec parent-dispatch-id origin]
   {:id        id
    :op-type   :event
    :operation :event/dispatched
    :tags      (cond-> {:dispatch-id dispatch-id
                        :event       event-vec
                        :origin      origin}
                 parent-dispatch-id (assoc :parent-dispatch-id parent-dispatch-id))}))

(defn- handler-ev
  [id dispatch-id]
  {:id id :op-type :event :operation :event
   :tags {:dispatch-id dispatch-id :phase :run-end}})

(defn- fx-ev
  [id dispatch-id fx-id]
  {:id id :op-type :fx :operation :rf.fx/handled
   :tags {:dispatch-id dispatch-id :fx-id fx-id}})

(defn- render-ev
  [id dispatch-id render-key]
  {:id id :op-type :view :operation :view/render
   :tags {:dispatch-id dispatch-id :render-key render-key}})

(defn- error-ev
  [id dispatch-id]
  {:id id :op-type :error :operation :rf.error/schema-validation-failure
   :tags {:dispatch-id dispatch-id}})

(defn- warning-ev
  [id dispatch-id]
  {:id id :op-type :warning :operation :rf.warning/handler-replaced
   :tags {:dispatch-id dispatch-id}})

(defn- cascade-events
  "Produce a representative one-cascade trace stream. `parent` is the
  parent-dispatch-id (nil for a root)."
  ([id-base dispatch-id event-vec]
   (cascade-events id-base dispatch-id event-vec nil :app))
  ([id-base dispatch-id event-vec parent]
   (cascade-events id-base dispatch-id event-vec parent :app))
  ([id-base dispatch-id event-vec parent origin]
   [(dispatched-ev (+ id-base 1) dispatch-id event-vec parent origin)
    (handler-ev    (+ id-base 2) dispatch-id)
    (fx-ev         (+ id-base 3) dispatch-id :db)
    (render-ev     (+ id-base 4) dispatch-id [:app/root nil])]))

(defn- pipeline
  "Run trace events through group-cascades + enrich-cascades + project-
  cascades-to-graph so each test asserts against the same shape the
  panel's composite sub produces."
  [trace-events]
  (-> (projection/group-cascades trace-events)
      (h/enrich-cascades trace-events)
      h/project-cascades-to-graph))

;; ---- (1) project-cascades-to-graph --------------------------------------

(deftest single-root-cascade-projects-to-one-node
  (testing "one root cascade → one node, no arrows, dispatch-id is the root"
    (let [graph (pipeline (cascade-events 0 100 [:user/login]))]
      (is (= 1 (count (:nodes graph))))
      (is (= [] (:arrows graph)))
      (is (= #{100} (:roots graph)))
      (let [node (first (:nodes graph))]
        (is (= 100 (:dispatch-id node)))
        (is (= [:user/login] (:event node)))
        (is (true? (:root? node)))
        (is (= :app (:origin node)))
        (is (false? (:error? node)))
        (is (false? (:warning? node)))
        (is (= 1 (:effect-count node)) "one :rf.fx/handled in fixture")
        (is (= 1 (:render-count node)))))))

(deftest parent-child-cascade-produces-arrow
  (testing "two cascades with parent→child :parent-dispatch-id produce one arrow"
    (let [trace  (concat (cascade-events 0   100 [:user/login])
                         (cascade-events 100 200 [:user/redirect] 100))
          graph  (pipeline trace)
          arrows (:arrows graph)]
      (is (= 2 (count (:nodes graph))))
      (is (= [[100 200]] arrows))
      (is (= #{100} (:roots graph)))
      (let [parent (some #(when (= 100 (:dispatch-id %)) %) (:nodes graph))
            child  (some #(when (= 200 (:dispatch-id %)) %) (:nodes graph))]
        (is (true?  (:root? parent)))
        (is (false? (:root? child)))
        (is (= 100  (:parent child)))))))

(deftest orphan-child-renders-as-root
  (testing "a child cascade whose parent isn't in the buffer (aged out)
            renders as a root; no arrow into it (per spec §What this doesn't
            do — 'no retroactive correlation')"
    (let [trace (cascade-events 0 200 [:user/redirect] 999)  ; parent 999 absent
          graph (pipeline trace)]
      (is (= 1 (count (:nodes graph))))
      (is (= [] (:arrows graph)) "no arrow when parent isn't in the graph")
      (is (= #{200} (:roots graph)))
      (is (true? (:root? (first (:nodes graph))))))))

(deftest three-level-cascade-arrows-form-chain
  (testing "root → child → grandchild produces two arrows in chain order"
    (let [trace  (concat (cascade-events 0   100 [:a])
                         (cascade-events 100 200 [:b] 100)
                         (cascade-events 200 300 [:c] 200))
          graph  (pipeline trace)]
      (is (= 3 (count (:nodes graph))))
      (is (= #{[100 200] [200 300]} (set (:arrows graph))))
      (is (= #{100} (:roots graph))))))

(deftest fan-out-multiple-children
  (testing "one parent with two children produces two arrows from the same id"
    (let [trace  (concat (cascade-events 0   100 [:a])
                         (cascade-events 100 200 [:b] 100)
                         (cascade-events 200 300 [:c] 100))
          graph  (pipeline trace)]
      (is (= 3 (count (:nodes graph))))
      (is (= #{[100 200] [100 300]} (set (:arrows graph))))
      (is (= #{100} (:roots graph))))))

(deftest ungrouped-cascade-is-skipped
  (testing "events without a :dispatch-id collect under :ungrouped in
            group-cascades; the graph drops that bucket (no nil node)"
    (let [trace  [{:id 1 :op-type :event :operation :event/dispatched
                   :tags {:event [:boot] :origin :app}}] ; no :dispatch-id
          graph  (pipeline trace)]
      (is (= 0 (count (:nodes graph)))
          ":ungrouped cascade does not produce a graph node")
      (is (= [] (:arrows graph))))))

(deftest origin-axis-reflected-on-node
  (testing ":origin per-cascade lands on the node — drives the fill colour"
    (let [trace [(dispatched-ev 1 100 [:foo] nil :app)
                 (dispatched-ev 2 200 [:bar] nil :pair)
                 (dispatched-ev 3 300 [:baz] nil :story)
                 (dispatched-ev 4 400 [:qux] nil :causa)]
          graph (pipeline trace)
          by-id (into {} (map (juxt :dispatch-id :origin)) (:nodes graph))]
      (is (= :app   (get by-id 100)))
      (is (= :pair  (get by-id 200)))
      (is (= :story (get by-id 300)))
      (is (= :causa (get by-id 400))))))

(deftest error-and-warning-flags-set-on-node
  (testing "errors / warnings in the cascade's :other bucket set :error?
            / :warning? on the node (drives the red / amber border treatment)"
    (let [trace (concat (cascade-events 0 100 [:bad])
                        [(error-ev 99 100)]
                        (cascade-events 100 200 [:ok])
                        [(warning-ev 199 200)])
          graph  (pipeline trace)
          by-id  (into {} (map (juxt :dispatch-id identity)) (:nodes graph))]
      (is (true?  (:error?   (get by-id 100))))
      (is (false? (:warning? (get by-id 100))))
      (is (false? (:error?   (get by-id 200))))
      (is (true?  (:warning? (get by-id 200)))))))

;; ---- (2) compute-layout -------------------------------------------------

(deftest layout-produces-position-for-every-node
  (testing "compute-layout returns one {:x :y} entry per node"
    (let [trace  (concat (cascade-events 0   100 [:a])
                         (cascade-events 100 200 [:b] 100)
                         (cascade-events 200 300 [:c] 100))
          graph  (pipeline trace)
          layout (h/compute-layout graph)]
      (is (= 3 (count (:positions layout))))
      (doseq [id [100 200 300]]
        (is (some? (get-in layout [:positions id]))
            (str "position present for " id))))))

(deftest layout-stacks-children-below-parent
  (testing "child y > parent y (top-down) — vertical axis is time per spec"
    (let [trace  (concat (cascade-events 0   100 [:a])
                         (cascade-events 100 200 [:b] 100))
          graph  (pipeline trace)
          layout (h/compute-layout graph)
          py     (get-in layout [:positions 100 :y])
          cy     (get-in layout [:positions 200 :y])]
      (is (< py cy) "child sits below parent in pixel space"))))

(deftest layout-spreads-siblings-horizontally
  (testing "two siblings of the same parent get distinct x coordinates"
    (let [trace  (concat (cascade-events 0   100 [:a])
                         (cascade-events 100 200 [:b] 100)
                         (cascade-events 200 300 [:c] 100))
          graph  (pipeline trace)
          layout (h/compute-layout graph)
          x2     (get-in layout [:positions 200 :x])
          x3     (get-in layout [:positions 300 :x])]
      (is (not= x2 x3) "siblings occupy distinct columns"))))

(deftest layout-deterministic-over-the-same-input
  (testing "two runs of compute-layout over identical input yield identical
            positions — required so the panel can diff layouts for the
            animation hook"
    (let [trace  (concat (cascade-events 0   100 [:a])
                         (cascade-events 100 200 [:b] 100)
                         (cascade-events 200 300 [:c] 200))
          graph  (pipeline trace)
          l1     (h/compute-layout graph)
          l2     (h/compute-layout graph)]
      (is (= (:positions l1) (:positions l2)))
      (is (= (:width l1) (:width l2)))
      (is (= (:height l1) (:height l2))))))

(deftest layout-width-and-height-bound-every-node
  (testing "the layout's :width / :height are at least node-width /
            node-height past the rightmost / bottommost node"
    (let [trace  (concat (cascade-events 0   100 [:a])
                         (cascade-events 100 200 [:b] 100))
          graph  (pipeline trace)
          layout (h/compute-layout graph)
          max-x  (apply max (map :x (vals (:positions layout))))
          max-y  (apply max (map :y (vals (:positions layout))))]
      (is (>= (:width  layout) (+ max-x h/default-node-width))
          "width spans every node's right edge")
      (is (>= (:height layout) (+ max-y h/default-node-height))
          "height spans every node's bottom edge"))))

(deftest layout-empty-graph-degenerate
  (testing "compute-layout on an empty graph returns sensible defaults"
    (let [layout (h/compute-layout {:nodes [] :arrows [] :roots #{} :index {}})]
      (is (= {} (:positions layout)))
      (is (pos? (:width  layout))  "non-zero canvas even when empty")
      (is (pos? (:height layout))))))

;; ---- (3) filter-to-cascade ----------------------------------------------

(deftest filter-to-cascade-keeps-family-only
  (testing "filter-to-cascade keeps the cascade family (ancestors + self
            + descendants) and drops the rest"
    (let [trace  (concat
                   ;; family A: 100 → 200 → 300
                   (cascade-events 0   100 [:a])
                   (cascade-events 100 200 [:b] 100)
                   (cascade-events 200 300 [:c] 200)
                   ;; unrelated root: 400
                   (cascade-events 300 400 [:other]))
          graph  (pipeline trace)
          ;; Filter from the middle of family A — both ancestor (100)
          ;; and descendant (300) must survive.
          filt   (h/filter-to-cascade graph 200)
          ids    (set (map :dispatch-id (:nodes filt)))]
      (is (= #{100 200 300} ids))
      (is (not (contains? ids 400)))
      (is (= #{[100 200] [200 300]} (set (:arrows filt)))))))

(deftest filter-to-cascade-nil-returns-graph-unchanged
  (testing "filter-to-cascade nil is the identity"
    (let [trace (concat (cascade-events 0   100 [:a])
                        (cascade-events 100 200 [:b]))
          graph (pipeline trace)
          filt  (h/filter-to-cascade graph nil)]
      (is (= (set (map :dispatch-id (:nodes graph)))
             (set (map :dispatch-id (:nodes filt))))))))

(deftest filter-to-cascade-on-missing-id-yields-empty-graph
  (testing "filtering on a dispatch-id not in the graph yields an empty
            graph (no nodes, no arrows) — caller should fall back to
            the unfiltered graph; the composite sub does so already"
    (let [trace  (cascade-events 0 100 [:a])
          graph  (pipeline trace)
          filt   (h/filter-to-cascade graph 999)]
      (is (= [] (:nodes filt)))
      (is (= [] (:arrows filt))))))

;; ---- (4) enrich-cascades ------------------------------------------------

(deftest enrich-cascades-attaches-event-trace
  (testing "enrich-cascades attaches the :event/dispatched trace event
            onto each cascade record under :event-trace"
    (let [trace    (cascade-events 0 100 [:user/login] nil :pair)
          cascades (projection/group-cascades trace)
          enriched (h/enrich-cascades cascades trace)
          c        (first enriched)]
      (is (some? (:event-trace c)))
      (is (= :pair (get-in c [:event-trace :tags :origin]))))))

(deftest enrich-cascades-handles-missing-dispatched
  (testing "a cascade with no :event/dispatched trace event is left
            unchanged — :event-trace stays nil"
    (let [trace    [(handler-ev 1 100)] ; no :event/dispatched
          cascades (projection/group-cascades trace)
          enriched (h/enrich-cascades cascades trace)]
      (is (every? #(nil? (:event-trace %)) enriched)))))

;; ---- (5) dispatch-id-of-epoch -------------------------------------------

(deftest dispatch-id-of-epoch-walks-trace-events
  (testing "dispatch-id-of-epoch picks the first :dispatch-id-bearing event"
    (let [rec1 {:trace-events [(dispatched-ev 1 42 [:foo])]}
          rec2 {:trace-events []} ; synthetic epoch — no dispatch-id
          rec3 {:trace-events [{:id 1 :op-type :event :operation :event
                                :tags {:parent-dispatch-id 99}}]}]
      (is (= 42 (h/dispatch-id-of-epoch rec1)))
      (is (nil? (h/dispatch-id-of-epoch rec2)))
      (is (= 99 (h/dispatch-id-of-epoch rec3))
          "falls back to :parent-dispatch-id when :dispatch-id absent"))))

;; ---- (6) visual token helpers -------------------------------------------

(deftest node-border-token-precedence
  (testing "errors win over warnings; default otherwise"
    (is (= :error   (h/node-border-token {:error? true  :warning? false})))
    (is (= :error   (h/node-border-token {:error? true  :warning? true})))
    (is (= :warning (h/node-border-token {:error? false :warning? true})))
    (is (= :default (h/node-border-token {:error? false :warning? false})))))

(deftest node-glyph-selection
  (testing "selected wins; root next; default child"
    (is (= "◉" (h/node-glyph {:root? true}  true)))
    (is (= "◉" (h/node-glyph {:root? false} true)))
    (is (= "◆" (h/node-glyph {:root? true}  false)))
    (is (= "○" (h/node-glyph {:root? false} false)))))

(deftest origin-fill-covers-spec-axis
  (testing "every :origin from spec/002 §Dispatch origin tagging has a fill"
    (doseq [o [:app :pair :story :test :causa]]
      (is (some? (get h/origin->fill o))
          (str "fill present for :" (name o))))))
