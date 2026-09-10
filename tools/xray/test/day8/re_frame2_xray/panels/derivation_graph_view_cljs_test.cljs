(ns day8.re-frame2-xray.panels.derivation-graph-view-cljs-test
  "View rows for the Derivation-Graph panel (EP-0014 prop-3), added under
  rf2-k97c.3 when the panel became a Fresco boundary.

  ## Why this file did not exist before, and why it does now

  The panel had NO view coverage at all — `derivation_graph_consumer_cljs_test`
  exercises the sub graph and `derivation_graph_helpers_cljs_test` the pure
  projection algebra, but nothing walked the rendered markup. That gap is
  exactly why the panel accumulated FIVE React keys written as `^{:key …}`
  reader metadata on CALL FORMS, where the metadata is discarded on return
  and no key ever reaches React. The same defect `rf2-ppzid` records and
  #9578 found in `routing.cljs`'s route table; the mayor's 2026-09-10 ruling
  made sweeping it part of every remaining panel dispatch.

  A LOST KEY DOES NOT FAIL, IT DEGRADES — into index-based reconciliation,
  which paints identically and corrupts identity only once a list changes
  shape. So it is invisible to any row that only asks what the markup looks
  like. `every-sequence-row-carries-a-react-key` below asks the one question
  that sees it.

  ## What drives the markup

  `panel-tree` — the pure projection `Panel` calls. `Panel` itself is an
  `rf.fresco/defview` boundary, a real React function component whose body
  may only run inside a React render window, so it is not callable here.
  The value handed to `panel-tree` is built by the SAME expression the
  panel's own `:rf.xray/derivation-graph-tab-data` sub uses, so a drift in
  that projection surfaces here rather than being papered over by a
  hand-written fixture of the composite's output."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.derivation-graph :as derivation-graph]
            [day8.re-frame2-xray.panels.derivation-graph-helpers :as h]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- fixture -------------------------------------------------------------

(def ^:private fixture-graph
  "A small graph carrying at least TWO nodes in one family, TWO edges, and a
  node with TWO value summaries — two of each, because a one-element
  sequence cannot distinguish a real key from a missing one."
  {:mode  :static
   :nodes {[:sub :cart/total] {:kind      :derivation
                               :rf/family :subs
                               :storage   :memo
                               :summaries {:last-value {:type :map :size 2 :preview "{…}"}
                                           :inputs     {:type :vector :size 1 :preview "[…]"}}}
           [:sub :cart/state] {:kind :derivation :rf/family :subs}
           [:machine :auth]   {:kind :process :rf/family :machines}}
   :edges [{:from [:sub :cart/state] :to [:sub :cart/total] :role :input}
           {:from [:machine :auth]   :to [:sub :cart/state] :role :selector}]})

(defn- tab-data
  "The `:rf.xray/derivation-graph-tab-data` projection, spelled exactly as
  `derivation-graph/install!` spells it."
  [graph]
  (let [summarized (h/summarize-graph graph)]
    {:mode      (:mode graph)
     :silent?   (h/empty-graph? graph)
     :summary   (h/graph-summary graph)
     :by-family (h/group-by-family summarized)
     :edges     (:edges graph)}))

(defn- render
  ([]           (render (tab-data fixture-graph)))
  ([data]       (render data (fn [_ev] nil)))
  ([data disp]  (derivation-graph/panel-tree disp data)))

;; ---- hiccup walking ------------------------------------------------------

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- props-of [node]
  (let [p (second node)] (if (map? p) p {})))

(defn- nodes-with-testid-prefix [tree prefix]
  (filter #(and (vector? %)
                (some-> (:data-testid (props-of %)) (.startsWith prefix)))
          (hiccup-seq tree)))

;; A testid PREFIX is not a row selector: `rf-xray-derivation-graph-node-`
;; also matches each row's `-superkind` / `-id` / `-refinement` spans, and
;; `-mode-` also matches the toggle container and the mode badge. Each row
;; kind therefore selects on the attribute that only the row itself carries.

(defn- node-rows [tree]
  (filter #(contains? (props-of %) :data-superkind)
          (nodes-with-testid-prefix tree "rf-xray-derivation-graph-node-")))

(defn- edge-rows [tree]
  (filter #(contains? (props-of %) :data-role)
          (nodes-with-testid-prefix tree "rf-xray-derivation-graph-edge-")))

(defn- mode-buttons [tree]
  (filter #(= :button (first %))
          (nodes-with-testid-prefix tree "rf-xray-derivation-graph-mode-")))

(defn- keyed-fragments
  "Fragments carrying a props map. The OUTER `(into [:<>] …)` wrapper has
  none and needs none — it is not an element of a sequence."
  [tree]
  (filter #(and (vector? %) (= :<> (first %)) (map? (second %)))
          (hiccup-seq tree)))

(defn- keys-of [nodes]
  (mapv #(:key (props-of %)) nodes))

;; ---- (1) the markup renders ----------------------------------------------

(deftest panel-tree-renders-header-families-and-edges
  (testing "the projection renders the header count strip, one section per
            populated family, and the edges section"
    (let [tree (render)]
      (is (seq (nodes-with-testid-prefix tree "rf-xray-derivation-graph-header"))
          "header section renders")
      (is (seq (nodes-with-testid-prefix tree "rf-xray-derivation-graph-counts"))
          "count strip renders")
      (is (seq (nodes-with-testid-prefix tree "rf-xray-derivation-graph-family-subs"))
          "the subs family section renders")
      (is (seq (nodes-with-testid-prefix tree "rf-xray-derivation-graph-family-machines"))
          "the machines family section renders")
      (is (seq (nodes-with-testid-prefix tree "rf-xray-derivation-graph-edges"))
          "the edges section renders")
      (is (empty? (nodes-with-testid-prefix tree "rf-xray-derivation-graph-silent"))
          "a populated graph does not render the silent state"))))

(deftest empty-graph-renders-the-silent-state
  (testing "a graph with no nodes renders the silent state and no family
            sections — the honest no-registrations story"
    (let [tree (render (tab-data {:mode :static :nodes {} :edges []}))]
      (is (seq (nodes-with-testid-prefix tree "rf-xray-derivation-graph-silent"))
          "silent state renders")
      (is (empty? (nodes-with-testid-prefix tree "rf-xray-derivation-graph-family-"))
          "no family section renders"))))

;; ---- (2) THE KEY ROW -----------------------------------------------------
;;
;; This is the row the ns docstring exists for. Read `keys-of` as "what
;; React actually receives": Fresco's codec head table says the literal
;; `:key` in the ATTRIBUTE MAP is the one spelling that reaches React, so a
;; key written as reader metadata — on a vector literal, and far worse on a
;; CALL FORM, where it is discarded outright — is simply absent here.

(deftest every-sequence-row-carries-a-react-key
  (testing "rf2-k97c.3 — every row the panel emits from a sequence carries a
            LITERAL `:key` in its attribute map, distinct within its list.

            Before this bead five of these keys were `^{:key …}` reader
            metadata on CALL FORMS (`(node-row …)`, `(edge-row …)`,
            `(family-section …)`, `(edges-section …)` and a `when-let` in
            the role strip). Metadata on a source list is discarded when the
            form returns a fresh vector, so none of them reached React at
            all — silently, with the panel painting correctly throughout."
    (let [tree      (render)
          node-rows (node-rows tree)
          edge-rows (edge-rows tree)
          buttons   (mode-buttons tree)
          frags     (keyed-fragments tree)]

      ;; Preconditions — a vacuous pass is the failure mode here, so the
      ;; counts are asserted before the keys are.
      (is (= 3 (count node-rows)) "the fixture's three nodes each render a row")
      (is (= 2 (count edge-rows)) "the fixture's two edges each render a row")
      (is (= 2 (count buttons))   "both mode buttons render")
      (is (<= 3 (count frags))    "two family fragments plus the edges fragment")

      (is (every? some? (keys-of node-rows))
          "every node row carries an attribute-map :key")
      (is (= (count node-rows) (count (distinct (keys-of node-rows))))
          "node-row keys are distinct")

      (is (every? some? (keys-of edge-rows))
          "every edge row carries an attribute-map :key")
      (is (= (count edge-rows) (count (distinct (keys-of edge-rows))))
          "edge-row keys are distinct — the two fixture edges differ only in
           their endpoints and role, which is what the key is built from")

      (is (every? some? (keys-of buttons))
          "every mode-toggle button carries an attribute-map :key")

      (is (every? some? (keys-of frags))
          "every keyed fragment carries an attribute-map :key"))))

(deftest role-chips-and-value-summaries-carry-react-keys
  (testing "rf2-k97c.3 — the two remaining sequence positions. The role
            chip strip's key was metadata on a `when-let` form and so was
            discarded; the summary chip's was metadata on a vector literal,
            which Reagent reads and Fresco's codec does not."
    (let [tree     (render)
          counts   (first (nodes-with-testid-prefix tree "rf-xray-derivation-graph-counts"))
          chips    (->> (hiccup-seq counts)
                        (filter #(and (vector? %) (= :span (first %))
                                      (some? (:key (props-of %))))))
          summaries (->> (hiccup-seq tree)
                         (filter #(and (vector? %) (= :span (first %))
                                       (contains? (props-of %) :key)
                                       (= "inline-flex" (:display (:style (props-of %)))))))]
      (is (some? counts) "the count strip renders")
      ;; The fixture's two edges carry roles :input and :selector, so two
      ;; role chips must render, each keyed.
      (is (= 2 (count chips))
          "both role chips render and both carry an attribute-map :key")
      (is (= 2 (count summaries))
          "both of the fixture node's value summaries render keyed"))))

;; ---- (3) the dispatcher contract ----------------------------------------

(deftest mode-toggle-dispatches-through-the-supplied-dispatcher
  (testing "rf2-k97c.3 — the mode toggle calls the dispatcher THREADED IN,
            never a bare global `rf/dispatch`.

            `Panel` takes that dispatcher from `(:dispatch (rf/capture-frame))`
            now that it is a Fresco boundary and `defview` binds no
            `reg-view`-injected name inside a body. The property this row
            pins is the one rf2-1w07r cared about and is unchanged by the
            migration: the deferred click lands on the frame the panel
            rendered under, not on whatever ambient frame survives after
            render scope unwinds."
    (let [seen (atom [])
          tree (render (tab-data fixture-graph) #(swap! seen conj %))
          live (first (nodes-with-testid-prefix tree "rf-xray-derivation-graph-mode-live"))
          on-click (:on-click (props-of live))]
      (is (some? live) "the :live mode button renders")
      (is (fn? on-click) "it carries an :on-click handler")
      (on-click nil)
      (is (= [[:rf.xray/set-derivation-graph-mode :live]] @seen)
          "the handler called the supplied dispatcher with the mode event"))))

;; ---- (4) the boundary / bridge contract ---------------------------------

(deftest l4-tab-registers-the-bridge-not-the-boundary
  (testing "rf2-k97c.3 — `install!` registers the `as-component` BRIDGE, not
            `Panel` itself.

            `reg-l4-tab!`'s `:pre` requires `:panel` to be CALLABLE and the
            shell mounts it as a Reagent hiccup head; a Fresco boundary is a
            React component and `defview`'s contract forbids mounting one
            that way. The bridge is scaffolding with a defined end — it is
            deleted in step 3, when the shell is itself a Fresco tree and
            `reg-l4-tab!` takes `Panel` directly."
    (derivation-graph/install!)
    (let [tab   (panel-registry/tab-by-id :dynamic :derivation-graph)
          panel (:panel tab)]
      (is (some? tab) "the Graph tab is registered")
      (is (fn? panel) "its :panel is callable, as reg-l4-tab! requires")
      (is (not= derivation-graph/Panel panel)
          "and it is NOT the boundary itself")
      (let [mounted (panel)]
        (is (vector? mounted) "the bridge answers hiccup")
        (is (= :> (first mounted))
            "an interop mount — Fresco's outward `as-component` door, which
             takes the frame from the React context the enclosing
             `rf/frame-provider` already wrote")))))
