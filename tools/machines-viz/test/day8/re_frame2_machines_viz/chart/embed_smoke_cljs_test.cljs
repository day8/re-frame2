(ns day8.re-frame2-machines-viz.chart.embed-smoke-cljs-test
  "Non-Causa MachineChart embed smoke (rf2-hq5vk).

  Per audit `ai/findings/2026-05-20-tools-testing-posture-audit.md`
  Gap-3, machines-viz positions `MachineChart` as embeddable in
  non-Causa hosts (Story workshop, the read-only viewer page, direct
  user-app embeds). The bundle-isolation contract holds (the
  reagent-slim-bundle-isolation test pins THAT), but no test
  exercises the SHAPE contract — does a host that registers a real
  machine via `rf/reg-machine`, resolves it via `rf/machine-meta`,
  and renders the chart get a non-trivial SVG hiccup tree?

  This file is that smoke. It:

    1. Installs the plain-atom substrate adapter — no Reagent / UIx /
       Helix dependency. Demonstrates the chart's substrate-agnostic
       contract.
    2. Registers a real machine via `rf/reg-machine` (NOT Causa's
       preload; NOT a hand-crafted definition map — the real
       registrar path that consumers will use).
    3. Resolves the definition via `rf/machine-meta` — the
       documented `MachineChart` data source per
       `tools/machines-viz/spec/API.md` §Data sources.
    4. Renders the chart via `chart.svg/render-from-definition` (the
       current pure-hiccup `MachineChart` entry; the future
       `MachineChart` component wraps this).
    5. Asserts the SVG hiccup tree shape resolves end-to-end — root
       `<svg>` present, a node-per-state, an edge-per-transition,
       initial-state marker, viewport wrap.

  The bundle-isolation contract is proved elsewhere
  (`implementation/scripts/check-reagent-slim-bundle-isolation.cjs`);
  this test proves the embed SHAPE — that a non-Causa host can mount
  the chart end-to-end and the registry round-trip produces a
  rendered SVG.

  Cites audit Gap-3."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.svg :as chart-svg]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; ---- fixture machine ---------------------------------------------------

(def ^:private embed-machine-id
  "A namespaced keyword the embed test uses to register + look up the
  fixture machine. Distinct from Causa / Story machine-ids so a
  future cross-tool test ns load order cannot collide."
  :rf2-hq5vk.embed-smoke/login-flow)

(def ^:private fixture-machine
  "A small but non-trivial four-state machine — initial, two
  intermediate, one final. Exercises the chart's node + edge +
  initial-marker + final-glyph + viewport pipeline in one pass.

  Deliberately kept small (four states) so the hiccup walk is fast
  and the assertions are exact. Richer grammar (compound, parallel,
  :after, :spawn-all) is exercised by `svg_cljs_test.cljc` already;
  this smoke is the EMBED-CONTRACT pin, not a grammar inventory."
  {:initial :idle
   :states
   {:idle      {:on {:submit :verifying}}
    :verifying {:on {:ok      :authenticated
                     :reject  :failed}}
    :authenticated {:final? true}
    :failed        {:final? true}}})

;; ---- hiccup walk helpers (mirrors svg_cljs_test.cljc shape) ------------

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (when-let [tid (:data-testid (second node))]
                   (.startsWith tid prefix))))
          (hiccup-seq tree)))

;; ---- the contract --------------------------------------------------------

(deftest embed-smoke-machine-meta-roundtrip
  (testing "rf/reg-machine -> rf/machine-meta resolves a non-nil
            definition with the expected shape — proves the embed's
            data-source contract (`tools/machines-viz/spec/API.md`
            §Data sources)"
    (rf/reg-machine embed-machine-id fixture-machine)
    (let [meta (rf/machine-meta embed-machine-id)]
      (is (some? meta)
          "rf/machine-meta returns a non-nil map for a registered machine")
      (is (= :idle (:initial meta))
          "the registered :initial state survives the registrar roundtrip")
      (is (= #{:idle :verifying :authenticated :failed}
             (set (keys (:states meta))))
          "all four registered states are present in the resolved meta"))))

(deftest embed-smoke-renders-non-empty-svg
  (testing "Mounting the chart against a real `rf/machine-meta` source
            (no Causa preload, no panel chrome) produces a non-empty
            SVG hiccup tree — proves the end-to-end SHAPE contract"
    (rf/reg-machine embed-machine-id fixture-machine)
    (let [definition (rf/machine-meta embed-machine-id)
          tree       (chart-svg/render-from-definition definition)]
      (is (vector? tree)
          "render returns a vector (hiccup form)")
      (is (= :svg (first tree))
          "the embed mount produces an SVG root (NOT the empty-state
           div fallback — the registered machine HAS states)")
      (is (some? (find-by-testid tree "rf-mv-chart-svg"))
          "the canonical root data-testid is present"))))

(deftest embed-smoke-non-trivial-node-and-edge-count
  (testing "The rendered tree carries one node per registered state and
            one edge per transition — the structural invariant a host
            embedding the chart relies on"
    (rf/reg-machine embed-machine-id fixture-machine)
    (let [definition (rf/machine-meta embed-machine-id)
          tree       (chart-svg/render-from-definition definition)
          nodes      (find-all-by-testid-prefix tree "rf-mv-chart-node-")
          edges      (find-all-by-testid-prefix tree "rf-mv-chart-edge-")]
      (is (= 4 (count nodes))
          "four registered states -> four chart nodes")
      (is (= 3 (count edges))
          "three transitions (:submit, :ok, :reject) -> three chart edges"))))

(deftest embed-smoke-initial-and-final-markers
  (testing "Initial-state marker + final-state double-ring are emitted
            from the resolved definition — these are the static
            decorations a non-Causa host (read-only viewer page in
            particular) expects to render without any runtime data"
    (rf/reg-machine embed-machine-id fixture-machine)
    (let [definition (rf/machine-meta embed-machine-id)
          tree       (chart-svg/render-from-definition definition)]
      (is (some? (find-by-testid tree "rf-mv-chart-initial-marker"))
          "initial-state marker is rendered for the :initial slot")
      ;; The final glyph "✓" sits inside the node group; we assert
      ;; both final states emit their node groups (the doubled-ring
      ;; rect is wrapped by the node <g>).
      (let [final-a (find-by-testid tree
                      (str "rf-mv-chart-node-"
                           (layout/highlight-id :authenticated)))
            final-b (find-by-testid tree
                      (str "rf-mv-chart-node-"
                           (layout/highlight-id :failed)))]
        (is (some? final-a)
            ":authenticated final state node is rendered")
        (is (some? final-b)
            ":failed final state node is rendered")))))

(deftest embed-smoke-viewport-and-dotgrid-chrome
  (testing "Chart chrome (viewport wrap + dot-grid backdrop) renders
            without any caller-side configuration — non-Causa hosts get
            the same visual identity as Causa"
    (rf/reg-machine embed-machine-id fixture-machine)
    (let [definition (rf/machine-meta embed-machine-id)
          tree       (chart-svg/render-from-definition definition)]
      (is (some? (find-by-testid tree "rf-mv-chart-viewport"))
          "viewport-transform wrap exists (sized to render at 1:1
           when no transform is supplied — back-compat for non-Causa
           hosts)")
      (is (some? (find-by-testid tree "rf-mv-chart-dot-grid-backdrop"))
          "the state-space dot-grid backdrop renders without any
           host-side opt-in")
      (is (some? (find-by-testid tree "rf-mv-chart-body"))
          "chart-body group is present"))))

(deftest embed-smoke-host-supplied-on-state-click-wires-through
  (testing "A non-Causa host's `:on-state-click` callback wires through
            to the chart's node groups — proves the embed contract for
            click-handling (Story workshop / direct user-app embeds
            wire their own click semantics)"
    (rf/reg-machine embed-machine-id fixture-machine)
    (let [seen       (atom [])
          definition (rf/machine-meta embed-machine-id)
          tree       (chart-svg/render-from-definition
                       definition
                       {:on-state-click (fn [path] (swap! seen conj path))})
          node       (find-by-testid
                       tree
                       (str "rf-mv-chart-node-"
                            (layout/highlight-id :verifying)))
          click-fn   (:on-click (second node))]
      (is (some? click-fn)
          "node's <g> carries an on-click when the host supplies one")
      (when click-fn (click-fn nil))
      (is (= [[:verifying]] @seen)
          "the host's callback receives the state's :path tuple"))))
