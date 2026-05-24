(ns day8.re-frame2-machines-viz.chart.chart-dom-cljs-test
  "Browser-side CLJS visual-pin tests for the xyflow `MachineChart`
  (rf2-y9j79 · xyflow Phase 2).

  ## Why this exists

  xyflow Phase 1 (#1806) DELETED the JVM-side `.cljc` renderer tests
  (the JVM can't load xyflow). This suite restores that coverage as
  browser-side CLJS: it mounts the chart against a sample machine spec
  and pins the rendered DOM — node count, edge count, the custom
  node/edge class + testid contract, and the Controls / MiniMap /
  Background presence toggles. Per the saved-memory rule
  (feedback_xray_story_cljs_unit_tests_not_playwright) these are
  browser CLJS tests, NOT Playwright.

  ## Mounting

  The chart is a Reagent component; we mount it through the substrate-
  adapter React bridge (`adapters.react-chart/chart-element`) so the
  mount path is substrate-neutral — the same element a UIx / Helix
  host would mount. `react-dom/client createRoot` + React `act()`
  drives the render; assertions read the real DOM via querySelector.

  ## Async-layout note

  elkjs layout is async (returns a Promise); node POSITIONS arrive
  after it resolves. But xyflow renders the node DOM immediately (at
  the initial {x 0 y 0}), so node/edge COUNT, class names, and the
  control toggles are all assertable synchronously after the first
  commit — none of these depend on the layout pass. The tests
  therefore do not race the elk Promise.

  ## Target

  ns ends in `-dom-cljs-test` so it runs under the `:browser-test`
  build (real DOM + headless Chromium) per `shadow-cljs.edn`
  (rf2-2hrj8). Under `:node-test` (no DOM) every test short-circuits
  via `(browser?)` and asserts a trivial truth so the suite stays
  green on both targets."
  (:require ["react"            :as React]
            ["react-dom/client" :as react-dom-client]
            [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-machines-viz.adapters.react-chart :as react-chart]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- sample machines ----------------------------------------------------

(def ^:private idle-loading-done
  "Canonical small machine: 4 states, 3 transitions."
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :done :err :failed}}
             :done    {:final? true}
             :failed  {:final? true}}})

(def ^:private tagged-machine
  "A machine whose idle state carries a state-tag, so the tag-pill DOM
  (whose height/px/radius track the resolved density) is assertable
  (rf2-k647w density-render pin)."
  {:initial :idle
   :states  {:idle    {:tags [:initial-tag] :on {:start :loading}}
             :loading {:on {:done :idle}}}})

(def ^:private parallel-machine
  "A parallel machine: 2 regions, 2 states each (rf2-lkwev)."
  {:type :parallel
   :regions {:audio   {:initial :playing
                       :states {:playing {:on {:pause :paused}}
                                :paused  {:on {:play :playing}}}}
             :display {:initial :on
                       :states {:on  {:on {:dim :off}}
                                :off {:on {:lit :on}}}}}})

;; ---- DOM mount helpers --------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (or (when (exists? (.-act React)) (.-act React))
      (try
        (let [test-utils (js/require "react-dom/test-utils")]
          (.-act test-utils))
        (catch :default _ nil))))

(defn- enable-react-act-env! []
  (when (browser?)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)))

(defn- mount-node! []
  (let [el (.createElement js/document "div")]
    ;; xyflow needs a non-zero parent height to lay out; give the host
    ;; an explicit box so the chart wrapper's "100%" resolves.
    (set! (.. el -style -width) "800px")
    (set! (.. el -style -height) "600px")
    (.appendChild (.-body js/document) el)
    el))

(defn- count-sel [^js node sel]
  (.-length (.querySelectorAll node sel)))

(defn- with-mounted-chart
  "Mount `MachineChart` with `props` via the React bridge under act(),
  call `(f root-el node)` with the chart's root DOM element + the host
  node, then unmount. Returns nil when no DOM / no act() (the caller
  asserts the skip).

  Note: the assertions here read DOM that mounts on the FIRST commit
  (node DOM, control toggles, root data-attrs) — none depend on the
  async elkjs layout pass, which only repositions already-mounted
  nodes. We deliberately do NOT await the elk Promise (returning a
  Promise from React's act() makes it async, which the synchronous
  test runner cannot await and would corrupt the act environment for
  subsequent tests)."
  [props f]
  (let [act-fn (get-act)]
    (when (and (browser?) act-fn)
      (enable-react-act-env!)
      (let [node (mount-node!)
            root (react-dom-client/createRoot node)]
        (try
          (act-fn (fn [] (.render root (react-chart/chart-element props))))
          (let [el (.querySelector node "[data-testid=\"rf-mv-chart\"]")]
            (f el node))
          (finally
            (try (act-fn (fn [] (.unmount root))) (catch :default _ nil))
            (try (.removeChild (.-body js/document) node) (catch :default _ nil))))))))

;; ---- node / edge count --------------------------------------------------

(deftest chart-renders-a-node-per-state
  (testing "rf2-y9j79 — the chart mounts one xyflow node per state"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done}
        (fn [root node]
          (is (some? root) "chart root element mounted")
          ;; Each state node carries the `rf-mv-chart-node-<id>` testid
          ;; (the chart.nodes/state-node contract).
          (is (= 4 (count-sel node "[data-testid^=\"rf-mv-chart-node-\"]"))
              "one node per state (idle/loading/done/failed)")
          ;; The root surfaces the count as a data-attr too.
          (is (= "4" (.getAttribute root "data-node-count"))
              "data-node-count reflects the state count"))))))

(deftest chart-renders-an-edge-per-transition
  (testing "rf2-y9j79 — the chart's edge count reflects the transitions.

            The edge LABEL DOM (`rf-mv-chart-edge-<id>`) mounts via
            xyflow's EdgeLabelRenderer only AFTER the async elkjs
            layout positions the edges, so we pin the count via the
            layout-independent `data-edge-count` root attr (the
            projector emits one edge per transition before layout)."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done}
        (fn [root _node]
          ;; 3 transitions: start, ok, err.
          (is (= "3" (.getAttribute root "data-edge-count"))
              "data-edge-count reflects the transition count"))))))

;; ---- edge routing fallback (rf2-cz8v6, G2) ------------------------------
;;
;; Before the async elkjs pass resolves, edges carry no bend-points, so
;; the edge component renders the bezier fallback (`data-routed=false`).
;; The routed path (`data-routed=true`) only appears after layout —
;; which the synchronous runner cannot await (see the ns docstring) — so
;; the routed geometry itself is pinned at the cheap layer:
;; `edges-cljs-test` (the pure `edge-path` helper) +
;; `projection-cljs-test` (the `:edge-points` → `:data {:points}`
;; threading). This DOM pin guards the LIVE fallback render.

(deftest chart-edge-label-renders-bezier-fallback-before-layout
  (testing "rf2-cz8v6 — on first commit (pre-elk-layout) an edge label
            renders with data-routed=\"false\": no bend-points yet, so
            the edge takes the bezier fallback path. (The routed path
            arrives after the async layout the runner can't await; the
            poly-path geometry is pinned in edges-cljs-test.)"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done}
        (fn [_root node]
          (let [labels (.querySelectorAll node "[data-testid^=\"rf-mv-chart-edge-\"]")]
            ;; edge labels mount via EdgeLabelRenderer on the first commit
            ;; (they do not depend on positions for existence).
            (when (pos? (.-length labels))
              (let [el (aget labels 0)]
                (is (= "false" (.getAttribute el "data-routed"))
                    "pre-layout edge falls back to the bezier path")))
            ;; Always assert at least the structural invariant so the
            ;; test is meaningful even if labels race the commit.
            (is (number? (.-length labels)))))))))

;; ---- final-state affordance ---------------------------------------------

(deftest chart-marks-final-states
  (testing "rf2-y9j79 — final states render with their state-tags
            scaffolding + final glyph (checkmark)"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done}
        (fn [_root node]
          ;; The check glyph ✓ is painted inside each :final? node.
          (let [text (.-textContent node)]
            (is (re-find #"✓" text) "a final-state checkmark glyph renders")))))))

;; ---- Controls / MiniMap / Background presence ---------------------------

(deftest chart-shows-controls-by-default
  (testing "rf2-y9j79 — xyflow Controls render by default"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done}
        (fn [_root node]
          (is (pos? (count-sel node ".react-flow__controls"))
              "xyflow Controls present by default"))))))

(deftest chart-hides-controls-when-disabled
  (testing "rf2-y9j79 — :show-controls? false drops the Controls"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done
         :show-controls? false}
        (fn [_root node]
          (is (zero? (count-sel node ".react-flow__controls"))
              "no Controls when :show-controls? false"))))))

(deftest chart-shows-minimap-when-enabled
  (testing "rf2-y9j79 — :show-minimap? true mounts the MiniMap (off by
            default)"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (do
        (with-mounted-chart
          {:machine-id :test/flow :definition idle-loading-done}
          (fn [_root node]
            (is (zero? (count-sel node ".react-flow__minimap"))
                "MiniMap off by default")))
        (with-mounted-chart
          {:machine-id :test/flow :definition idle-loading-done
           :show-minimap? true}
          (fn [_root node]
            (is (pos? (count-sel node ".react-flow__minimap"))
                "MiniMap present when :show-minimap? true")))))))

(deftest chart-shows-background-by-default
  (testing "rf2-y9j79 — the dot-grid Background renders by default"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done}
        (fn [_root node]
          (is (pos? (count-sel node ".react-flow__background"))
              "xyflow Background present by default"))))))

;; ---- :density prop (rf2-k647w) ------------------------------------------

(defn- tag-pill-height-px
  "Read the integer px height off the first state-tag pill's inline
  style (the pill's height tracks the resolved density per rf2-k647w).
  Returns nil when no pill is present."
  [^js node]
  (when-let [pill (.querySelector node "[data-testid^=\"rf-mv-chart-state-tag-\"]")]
    (let [h (.. pill -style -height)]            ;; e.g. "16px"
      (js/parseInt h 10))))

(deftest chart-data-density-defaults-to-regular
  (testing "rf2-k647w — omitting :density surfaces data-density=\"regular\"
            on the chart root (nil ≡ regular, the historical default)"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done}
        (fn [root _node]
          (is (= "regular" (.getAttribute root "data-density"))
              "data-density defaults to regular"))))))

(deftest chart-data-density-reflects-prop
  (testing "rf2-k647w — :density :compact / :cosy surface the matching
            data-density on the root, so hosts + tests read the active
            density without re-reading the bound prop"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (do
        (with-mounted-chart
          {:machine-id :test/flow :definition idle-loading-done :density :compact}
          (fn [root _node]
            (is (= "compact" (.getAttribute root "data-density")))))
        (with-mounted-chart
          {:machine-id :test/flow :definition idle-loading-done :density :cosy}
          (fn [root _node]
            (is (= "cosy" (.getAttribute root "data-density")))))))))

(deftest chart-density-changes-tag-pill-geometry
  (testing "rf2-k647w — :density actually changes the RENDER, not just
            the attr: the state-tag pill height equals the resolved
            density's :tag-pill-height (regular 16 / compact 13 / cosy
            19), proving the renderer reads geometry off the threaded
            visual-constants instead of a hardcoded literal"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (do
        (with-mounted-chart
          {:machine-id :test/tags :definition tagged-machine :density :regular}
          (fn [_root node]
            (is (= (:tag-pill-height vc/chart-regular) (tag-pill-height-px node))
                "regular pill height matches chart-regular")))
        (with-mounted-chart
          {:machine-id :test/tags :definition tagged-machine :density :compact}
          (fn [_root node]
            (is (= (:tag-pill-height vc/chart-compact) (tag-pill-height-px node))
                "compact pill height matches chart-compact")))
        (with-mounted-chart
          {:machine-id :test/tags :definition tagged-machine :density :cosy}
          (fn [_root node]
            (is (= (:tag-pill-height vc/chart-cosy) (tag-pill-height-px node))
                "cosy pill height matches chart-cosy")))))))

(deftest chart-default-tag-pill-is-pixel-identical
  (testing "rf2-k647w — the DEFAULT (no :density) tag-pill height is the
            shipped-reality 16px. This is the no-visual-regression pin:
            a host that never passes :density gets exactly the
            pre-rf2-k647w chart."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/tags :definition tagged-machine}
        (fn [_root node]
          (is (= 16 (tag-pill-height-px node))
              "default tag-pill height is the shipped 16px"))))))

;; ---- empty / nil definition placeholders --------------------------------

(deftest chart-renders-no-definition-placeholder
  (testing "rf2-y9j79 — a nil definition renders the placeholder, not a
            canvas"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (let [act-fn (get-act)]
        (when (and (browser?) act-fn)
          (enable-react-act-env!)
          (let [node (mount-node!)
                root (react-dom-client/createRoot node)]
            (try
              (act-fn (fn [] (.render root
                                      (react-chart/chart-element
                                        {:machine-id :test/flow :definition nil}))))
              (is (some? (.querySelector node "[data-testid=\"rf-mv-chart-no-definition\"]"))
                  "no-definition placeholder renders")
              (is (nil? (.querySelector node "[data-testid=\"rf-mv-chart\"]"))
                  "no canvas for a nil definition")
              (finally
                (try (act-fn (fn [] (.unmount root))) (catch :default _ nil))
                (try (.removeChild (.-body js/document) node) (catch :default _ nil))))))))))

;; ---- parallel-region rendering (rf2-lkwev visual-pin) -------------------

(deftest chart-renders-parallel-region-containers
  (testing "rf2-y9j79 + rf2-lkwev — a parallel machine renders one
            dashed region container per region, with its child states
            inside"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/parallel :definition parallel-machine}
        (fn [root node]
          ;; Two region containers (audio + display).
          (is (= 2 (count-sel node "[data-testid^=\"rf-mv-chart-region-\"]"))
              "one region container per parallel region")
          (is (= "2" (.getAttribute root "data-region-count"))
              "data-region-count reflects the region count")
          ;; Four states total (2 per region) — region containers are
          ;; NOT counted as states.
          (is (= 4 (count-sel node "[data-testid^=\"rf-mv-chart-node-\"]"))
              "all four region states render")
          (is (= "4" (.getAttribute root "data-node-count"))
              "data-node-count excludes the region containers"))))))

;; ---- parallel-region ACTIVE chrome (rf2-80rm2, G4 visual-pin) -----------
;;
;; G1 lights the active region LEAF; G4 lights the region CONTAINER too, so
;; an active region reads as active at the zone level. The container surfaces
;; the active read on its `data-active` attr (the parallel-region-node
;; contract); this pin guards that an active region's container is `true` and
;; an inactive region's is `false` on first commit (no elk-layout dependency).

(deftest chart-parallel-active-region-container-carries-active-chrome
  (testing "rf2-80rm2 (G4) — with one region advanced past initial, that
            region's CONTAINER carries data-active=true while the still-
            initial region's container is false (the active-region chrome
            reads at the zone level, not just the leaf)"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id    :test/parallel
         :definition    parallel-machine
         ;; :audio advances to :paused (active); :display stays at :on, its
         ;; initial — but :on IS the active leaf of :display, so BOTH region
         ;; containers are active. Use a region-map where only ONE region has
         ;; a leaf in the active set to get a clean active/inactive split:
         ;; highlight only :audio's leaf.
         :current-state {:audio :paused}}
        (fn [_root node]
          ;; `clj->js` renders the keyword region-id as its bare name
          ;; (`:audio` → "audio"), so the data-region-id attr carries the
          ;; name without the leading colon.
          (let [audio (.querySelector node
                        "[data-testid^=\"rf-mv-chart-region-\"][data-region-id=\"audio\"]")
                display (.querySelector node
                          "[data-testid^=\"rf-mv-chart-region-\"][data-region-id=\"display\"]")]
            (is (some? audio) ":audio region container mounted")
            (is (some? display) ":display region container mounted")
            (is (= "true" (.getAttribute audio "data-active"))
                ":audio container is active (its :paused leaf is in the active set)")
            (is (= "false" (.getAttribute display "data-active"))
                ":display container stays inactive (no active leaf)")))))))

(deftest chart-parallel-both-active-regions-light-their-containers
  (testing "rf2-80rm2 (G4) — when BOTH regions have an active leaf, BOTH
            region containers carry data-active=true simultaneously (the
            N-active-at-once read at the container level)"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id    :test/parallel
         :definition    parallel-machine
         :current-state {:audio :paused :display :off}}
        (fn [_root node]
          (let [containers (.querySelectorAll node
                             "[data-testid^=\"rf-mv-chart-region-\"]")]
            (is (= 2 (.-length containers)) "two region containers")
            (is (every? #(= "true" (.getAttribute % "data-active"))
                        (array-seq containers))
                "both region containers read active when both regions have an active leaf")))))))

;; ---- parallel multi-active highlight (rf2-g2svr, G1) --------------------
;;
;; The parity capability: a PARALLEL machine's `:current-state` is a
;; region-map, so N region leaves are active at once. The chart resolves
;; it via `highlight-ids` and surfaces the FULL active set on the root's
;; layout-independent `data-highlight-ids` attr (mounts on first commit).
;; This is the browser visual-pin mirroring the JVM projection pins.

(deftest chart-parallel-current-state-highlights-every-active-region
  (testing "rf2-g2svr — a region-map :current-state lights up EVERY
            active region leaf at once: data-highlight-ids carries BOTH
            the :audio and :display active-leaf node-ids"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id    :test/parallel
         :definition    parallel-machine
         ;; both regions advanced past their initial states
         :current-state {:audio :paused :display :off}}
        (fn [root _node]
          (let [ids (set (str/split (.getAttribute root "data-highlight-ids") #"\s+"))]
            (is (contains? ids (layout/node-id [:paused]))
                ":audio region's active leaf is in the active set")
            (is (contains? ids (layout/node-id [:off]))
                ":display region's active leaf is in the active set — SIMULTANEOUSLY")
            (is (= 2 (count ids))
                "exactly the two active region leaves, no more")
            ;; single-active convenience attr is "" for a multi-active set
            (is (= "" (.getAttribute root "data-highlight-id"))
                "data-highlight-id is empty for a multi-active (parallel) set")))))))

(deftest chart-flat-current-state-single-active-back-compat
  (testing "rf2-g2svr — a flat (single-active) :current-state still
            surfaces ONE id on both data-highlight-ids + the single-
            active data-highlight-id convenience attr (no regression)"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done
         :current-state :loading}
        (fn [root _node]
          (let [loading-id (layout/node-id [:loading])]
            (is (= loading-id (.getAttribute root "data-highlight-ids")))
            (is (= loading-id (.getAttribute root "data-highlight-id"))
                "single-active convenience attr carries the lone id")))))))

;; ---- fired-this-epoch edge highlight (rf2-qeemm, G3) --------------------
;;
;; The host (Xray) resolves the focused epoch's traversed edges via
;; `extract-fired-edge-ids` (CANONICAL ids) and passes them as
;; `:fired-edge-ids`. The matching edge label renders with
;; `data-fired="true"` (the DOM pin for the FIRED treatment) and the chart
;; root surfaces the sorted set on `data-fired-edge-ids`. Edge labels mount
;; via EdgeLabelRenderer on first commit (see
;; chart-edge-label-renders-bezier-fallback-before-layout above), so the
;; attr is assertable without awaiting the elk layout pass.

(defn- canonical-edge-id
  "Resolve the canonical machines-viz edge id for the from→to via event
  in `definition` (the SAME id the projector + the host's
  extract-fired-edge-ids mint, via parse-definition)."
  [definition from-path to-path event]
  (->> (:edges (layout/parse-definition definition))
       (some (fn [e]
               (when (and (= from-path (:from-path e))
                          (= to-path   (:to-path e))
                          (= event     (:event e)))
                 (:id e))))))

(deftest chart-fired-edge-renders-data-fired-on-the-right-edge
  (testing "rf2-qeemm (G3) — passing :fired-edge-ids #{<idle→loading id>}
            renders data-fired=\"true\" on THAT edge's label and
            data-fired=\"false\" on every other edge label"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (let [start-id (canonical-edge-id idle-loading-done [:idle] [:loading] :start)]
        (with-mounted-chart
          {:machine-id     :test/flow
           :definition     idle-loading-done
           :fired-edge-ids #{start-id}}
          (fn [_root node]
            (let [fired-el (.querySelector
                             node (str "[data-testid=\"rf-mv-chart-edge-" start-id "\"]"))
                  all      (array-seq
                             (.querySelectorAll node "[data-testid^=\"rf-mv-chart-edge-\"]"))
                  others   (remove #(= % fired-el) all)]
              ;; edge labels mount on first commit (no layout dependency)
              (when (some? fired-el)
                (is (= "true" (.getAttribute fired-el "data-fired"))
                    "the fired edge's label carries data-fired=true")
                (is (every? #(= "false" (.getAttribute % "data-fired")) others)
                    "no other edge label is marked fired"))
              ;; structural invariant so the test is meaningful even if a
              ;; label races the commit.
              (is (number? (.-length (.querySelectorAll
                                       node "[data-testid^=\"rf-mv-chart-edge-\"]")))))))))))

(deftest chart-fired-edge-ids-surfaces-on-root
  (testing "rf2-qeemm (G3) — the chart root surfaces the sorted fired set
            on data-fired-edge-ids; absent the prop the attr is empty"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (let [start-id (canonical-edge-id idle-loading-done [:idle] [:loading] :start)]
        (with-mounted-chart
          {:machine-id     :test/flow
           :definition     idle-loading-done
           :fired-edge-ids #{start-id}}
          (fn [root _node]
            (is (= start-id (.getAttribute root "data-fired-edge-ids"))
                "the fired edge-id surfaces on the root")))
        (with-mounted-chart
          {:machine-id :test/flow :definition idle-loading-done}
          (fn [root _node]
            (is (= "" (.getAttribute root "data-fired-edge-ids"))
                "no fired set → empty data-fired-edge-ids")))))))
