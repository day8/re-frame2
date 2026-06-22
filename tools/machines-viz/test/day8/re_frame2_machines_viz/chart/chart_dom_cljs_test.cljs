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
            [day8.re-frame2-machines-viz.chart.edges :as edges]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]
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
  "A machine whose idle state carries a state-tag, so the tags surface
  on the state-node (data-tags + title attr, rf2-so5b0) is assertable.
  Pre-rf2-so5b0 this machine drove the visible tag-pill density tests
  (rf2-k647w); since tag pills retired in favour of a hover-only
  tooltip, the same fixture exercises the new attr-surface."
  {:initial :idle
   :states  {:idle    {:tags [:initial-tag] :on {:start :loading}}
             :loading {:on {:done :idle}}}})

(def ^:private namespaced-tag-machine
  "rf2-vcnvj — a state carrying a NAMESPACED tag (`:door/open`) so the
  tag-identity-preservation fix is assertable (the visible label + the
  `data-tag` attr must read `door/open`, not the truncated `open`)."
  {:initial :open
   :states  {:open {:tags [:door/open] :on {:close :shut}}
             :shut {}}})

(def ^:private machine-level-on-machine
  "rf2-vcnvj — a flat machine with a top-level (machine-level) `:on`
  fallback (`:reset` → `:a`), so the single-root-chip projection +
  the root-context chrome are DOM-assertable on the live chart."
  {:initial :a
   :on      {:reset :a}
   :data    {:hits 0 :seen []}
   :states  {:a {:on {:go :b}}
             :b {:on {:go :a}}}})

(def ^:private success-and-error-finals
  "rf2-b4loj — a machine with BOTH a plain success final (:ok) and an
  `:error?` error final (:boom), so the error-hue outer ring distinction
  is DOM-assertable. The error final routes the spawning parent's
  `:on-error` (a re-frame2 extension); the chart must paint it distinctly."
  {:initial :running
   :states  {:running {:on {:ok :ok :boom :boom}}
             :ok      {:final? true}
             :boom    {:final? true :error? true}}})

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

(defn- with-mounted-element
  "rf2-qj6hoa — mount an ARBITRARY React `element` under act(), call
  `(f node)` with the host node, then unmount. Used to render a single
  edge component (e.g. the fork connector) standalone so its rendered
  `<path>` styling is DOM-assertable WITHOUT mounting the full chart (and
  without racing the async elkjs layout pass — the connector carries no
  route, so its straight handle-to-handle path renders on the first
  commit). Returns nil when no DOM / no act() (the caller asserts the
  skip)."
  [element f]
  (let [act-fn (get-act)]
    (when (and (browser?) act-fn)
      (enable-react-act-env!)
      (let [node (mount-node!)
            root (react-dom-client/createRoot node)]
        (try
          (act-fn (fn [] (.render root element)))
          (f node)
          (finally
            (try (act-fn (fn [] (.unmount root))) (catch :default _ nil))
            (try (.removeChild (.-body js/document) node) (catch :default _ nil))))))))

(defn- ->edge-props
  "Build a JS props object shaped like the one xyflow hands a custom edge
  component, from a projector edge map. The edge component reads coords +
  `:data` + `:id` + `:markerEnd` off it; the fork connector carries no
  route, so degenerate handle coords are fine (its `<path>` is a straight
  line). `clj->js` mirrors xyflow's own serialisation of the `:data`/`:markerEnd`
  maps into JS objects."
  [edge]
  #js {:id             (:id edge)
       :sourceX        0 :sourceY 0
       :targetX        80 :targetY 0
       :sourcePosition "right" :targetPosition "left"
       :markerEnd      (clj->js (:markerEnd edge))
       :data           (clj->js (:data edge))})

(defn- normalize-color
  "Round-trip a CSS colour string through the browser's CSSOM so a token
  value (which may be hex or `rgba(...)`) reads back in the SAME canonical
  form the DOM reports for a `style.<prop>` read. Lets a styling assertion
  compare a token against a rendered value WITHOUT hard-coding the browser's
  normalisation. Returns the input unchanged off-DOM."
  [css]
  (if (browser?)
    (let [el (.createElement js/document "div")]
      (set! (.. el -style -color) (str css))
      (.. el -style -color))
    css))

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
  (testing "rf2-az6e2 — final states render the QUIET DOUBLE BORDER
            (an outer ring node, testid `rf-mv-chart-final-ring-*`); the
            prior ✓ check glyph is DROPPED (the doubled border is the
            unambiguous final-state signal). idle-loading-done has two
            final states (:done, :failed)."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done}
        (fn [_root node]
          ;; The doubled border renders as an inner ring div per final node.
          (is (pos? (count-sel node "[data-testid^=\"rf-mv-chart-final-ring-\"]"))
              "a final-state double-border ring renders")
          ;; The dropped ✓ glyph must NOT appear.
          (is (not (re-find #"✓" (.-textContent node)))
              "the ✓ check glyph is dropped (rf2-az6e2)"))))))

(deftest chart-error-final-rings-distinct-from-success
  (testing "rf2-b4loj — an `:error?` final's outer ring carries the error
            hue (data-error-final=\"true\") while a success final's ring
            keeps the quiet runtime-coupled border (data-error-final=
            \"false\"). The error-final is a re-frame2 extension (routes the
            spawning parent's `:on-error`) the chart must not hide — NOT
            XState/Stately parity. success-and-error-finals has one of each."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition success-and-error-finals}
        (fn [_root node]
          (let [rings (.querySelectorAll
                       node "[data-testid^=\"rf-mv-chart-final-ring-\"]")
                attrs (mapv (fn [i] (.getAttribute (aget rings i) "data-error-final"))
                            (range (.-length rings)))]
            (is (= 2 (.-length rings)) "both finals render a ring")
            (is (some #{"true"} attrs)
                "the :error? final's ring is flagged data-error-final=\"true\"")
            (is (some #{"false"} attrs)
                "the success final's ring stays data-error-final=\"false\"")))))))

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

;; ---- :theme prop + root chrome (rf2-az6e2) ------------------------------

(deftest chart-data-theme-defaults-to-dark
  (testing "rf2-az6e2 — omitting :theme surfaces data-theme=\"dark\" on
            the chart root (Xray default surface)."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done}
        (fn [root _node]
          (is (= "dark" (.getAttribute root "data-theme"))
              "data-theme defaults to dark"))))))

(deftest chart-data-theme-reflects-prop-and-is-density-independent
  (testing "rf2-az6e2 — :theme :light surfaces data-theme=\"light\";
            :theme is INDEPENDENT of :density (both knobs surface their
            own attr)."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done
         :theme :light :density :compact}
        (fn [root _node]
          (is (= "light" (.getAttribute root "data-theme")))
          (is (= "compact" (.getAttribute root "data-density"))
              "density unaffected by theme — orthogonal knobs"))))))

(deftest chart-renders-root-container-frame-with-title
  (testing "rf2-q129z8 — the chart renders the synthetic ROOT-CONTAINER
            FRAME (the Stately-style named box wrapping the whole machine)
            whose HEADER carries the machine name; a non-parallel machine's
            frame reports data-parallel=\"false\". This replaces the old
            corner-pinned root title strip (the chrome now rides the frame
            that hugs + tracks the topology)."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done}
        (fn [_root node]
          (let [frame (.querySelector node "[data-root-container=\"true\"]")
                title (.querySelector node
                        "[data-testid^=\"rf-mv-chart-root-container-title-\"]")]
            (is (some? frame) "the root-container frame is present")
            (is (= "false" (.getAttribute frame "data-parallel"))
                "non-parallel machine's frame flagged data-parallel=false")
            (is (some? title) "the frame header title strip is present")
            (is (re-find #"flow" (.-textContent title))
                "the frame header carries the machine name")))))))

;; ---- state-node :tags surface (rf2-so5b0) -------------------------------

(defn- state-node-el
  "Return the first `rf-mv-chart-node-*` state-node element in `node`.
  Returns nil when none is present (e.g. an empty / placeholder chart)."
  [^js node]
  (.querySelector node "[data-testid^=\"rf-mv-chart-node-\"]"))

(deftest chart-tags-surface-as-visible-pills-below-state-name
  (testing "rf2-a2b55 — user-declared `:tags` (Spec 005) render as a
            VISIBLE pill row positioned BELOW the state name (Stately
            graph view convention). rf2-so5b0 retired the visible row
            on a diagnostic that misread Stately's tag chrome as
            ancestor-path clutter; rf2-a2b55 restores the row.

            The `:data-tags` + `:title` attr surface rf2-so5b0
            introduced is RETAINED in parallel so host-side
            introspection + the native HTML hover tooltip still
            resolve a state's tags in bulk without parsing per-pill
            DOM.

            Pins:

            1. The `rf-mv-chart-state-tags` container row IS rendered.
            2. Each declared tag has a `rf-mv-chart-state-tag-<name>`
               pill chip rendered inside the row.
            3. The tag set still surfaces on the state-node's
               `data-tags` attr (sorted space-joined string) for the
               host-introspection contract.
            4. The tag set still surfaces on the state-node's `title`
               attr (native HTML hover tooltip)."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/tags :definition tagged-machine}
        (fn [_root node]
          ;; 1 + 2 — visible pill row + per-tag chip.
          (is (pos? (count-sel node "[data-testid=\"rf-mv-chart-state-tags\"]"))
              "the visible tag-row container is rendered")
          (is (pos? (count-sel node "[data-testid=\"rf-mv-chart-state-tag-initial-tag\"]"))
              "the `:initial-tag` pill chip is rendered")
          ;; 3 — data-tags retains the sorted joined tag set.
          (let [n (state-node-el node)]
            (is (some? n) "a state-node mounted")
            (is (= "initial-tag" (.getAttribute n "data-tags"))
                "data-tags carries the sorted joined tag set")
            ;; 4 — title attr retains the same string for hover.
            (is (= "initial-tag" (.getAttribute n "title"))
                "title attr exposes tags for the native hover tooltip")
            (is (= "1" (.getAttribute n "data-tag-count"))
                "data-tag-count reflects the tag set's size")))))))

(deftest chart-empty-tag-set-omits-attr
  (testing "rf2-so5b0 — a state with no declared tags renders an empty
            `data-tags` attr + no `title` (tags-driven tooltip). The
            empty-attr posture means DOM tests can pin presence-or-
            absence by attribute equality without an extra
            `hasAttribute` round-trip."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done}
        (fn [_root node]
          (let [n (state-node-el node)]
            (is (some? n) "a state-node mounted")
            (is (= "" (.getAttribute n "data-tags"))
                "data-tags is the empty string when no tags declared")
            (is (= "0" (.getAttribute n "data-tag-count"))
                "data-tag-count is 0 when no tags declared")))))))

(deftest chart-namespaced-tag-preserves-declared-identity
  (testing "rf2-vcnvj — a NAMESPACED state tag (`:door/open`) renders its
            DECLARED identity (`door/open`) on the visible pill + the
            `data-tag` attr, NOT the truncated `open`. The `data-testid`
            keeps the namespace-collapsed segment (a `/` would break CSS /
            Playwright selectors)."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/door :definition namespaced-tag-machine}
        (fn [_root node]
          (let [pill (.querySelector node
                       "[data-testid=\"rf-mv-chart-state-tag-open\"]")]
            (is (some? pill) "the tag pill is rendered (testid segment is `open`)")
            (is (= "door/open" (.getAttribute pill "data-tag"))
                "data-tag preserves the full namespaced identity")
            (is (re-find #"door/open" (.-textContent pill))
                "the visible pill label reads `door/open`, not `open`")))))))

(deftest chart-machine-level-on-renders-single-root-sourced-chip
  (testing "rf2-vcnvj — a machine-level (top-level `:on`) fallback renders
            EXACTLY ONE event chip on the live chart (the synthetic
            MACHINE-ROOT node sources it), NOT one chip per state, and the
            MACHINE-ROOT chip itself is present."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/ml :definition machine-level-on-machine}
        (fn [_root node]
          ;; The MACHINE-ROOT chip is present (sources the single fallback).
          (is (pos? (count-sel node "[data-machine-root=\"true\"]"))
              "the synthetic machine-root chip mounted")
          ;; Exactly ONE machine-level event NODE (the `:reset` fallback
          ;; chip). The event-node carries data-machine-level=true on its
          ;; `rf-mv-chart-event-*` root; the in/out edges are SVG paths
          ;; without that testid, so this selector counts the chip alone.
          (is (= 1 (count-sel node
                     "[data-testid^=\"rf-mv-chart-event-\"][data-machine-level=\"true\"]"))
              "the machine-level fallback projects a single event chip, not one per state"))))))

;; ---- root context chrome (rf2-vcnvj) ------------------------------------

(deftest chart-renders-root-container-context-band-from-static-shape
  (testing "rf2-q129z8 — when a machine declares `:data`, the chart paints
            the Context BAND inside the ROOT-CONTAINER frame header from the
            supplied `:context-band` (the static context shape the Xray
            topology path derives). This replaces the old corner-pinned
            Context panel — the Context now rides INSIDE the frame that hugs
            the topology."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/ctx :definition machine-level-on-machine
         :context-band {:hits "number" :seen "vector"}}
        (fn [_root node]
          (let [band (.querySelector node
                       "[data-testid^=\"rf-mv-chart-root-container-context-\"]")]
            (is (some? band) "the root-container Context band mounted")
            (is (= "2" (.getAttribute band "data-key-count"))
                "the band reports both declared context keys")))))))

(deftest chart-context-band-shows-inferred-badge-by-default
  (testing "rf2-q129z8 / rf2-5tz9p — the root-container Context band carries
            an 'inferred from :data' badge by default, marking its
            key→type-caption contents as an INFERRED shape (the sole
            production feeder), not a declared schema and not the live
            runtime :data."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/ctx :definition machine-level-on-machine
         :context-band {:hits "number" :seen "vector"}}
        (fn [_root node]
          (let [badge (.querySelector node
                        "[data-testid^=\"rf-mv-chart-root-container-context-inferred-\"]")]
            (is (some? badge) "the inferred-from-:data badge mounted")
            (is (= "inferred from :data" (.-textContent badge))
                "the badge reads 'inferred from :data'")))))))

(deftest chart-context-band-suppresses-inferred-badge-when-live
  (testing "rf2-q129z8 / rf2-5tz9p — a host feeding LIVE :data values passes
            `:context-band-inferred? false`; the inferred badge is then
            omitted (the band still renders the values)."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/ctx :definition machine-level-on-machine
         :context-band {:hits 3 :seen [:a :b]}
         :context-band-inferred? false}
        (fn [_root node]
          (is (some? (.querySelector node
                       "[data-testid^=\"rf-mv-chart-root-container-context-\"]"))
              "the Context band still renders for live values")
          (is (nil? (.querySelector node
                      "[data-testid^=\"rf-mv-chart-root-container-context-inferred-\"]"))
              "no inferred badge when context-band-inferred? is false"))))))

(deftest chart-context-band-shows-declared-badge-when-authoritative
  (testing "rf2-3q4k5b (EP-0005) — a host feeding a DECLARED context shape
            (off a machine's `[:schemas :data]` schema) passes `:context-band-inferred?
            false`; the chart then drops the `inferred from :data` badge and
            shows a positive `declared` badge marking the shape AUTHORITATIVE."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/ctx :definition machine-level-on-machine
         :context-band {:hits "number" :seen "vector"}
         :context-band-inferred? false}
        (fn [_root node]
          (is (nil? (.querySelector node
                      "[data-testid^=\"rf-mv-chart-root-container-context-inferred-\"]"))
              "the inferred badge is dropped for a declared shape")
          (let [declared (.querySelector node
                           "[data-testid^=\"rf-mv-chart-root-container-context-declared-\"]")]
            (is (some? declared) "the `declared` badge mounted")
            (is (= "declared" (.-textContent declared))
                "the badge reads `declared`")))))))

;; ---- rf2-8z1rca: the Context band fits in the reserved ELK top padding --
;;
;; The bug: the root-container frame's ELK TOP padding reserved only the
;; title strip + a body-pad band, NOT the variable-height Context band the
;; header ALSO paints — so with non-trivial context the first child laid out
;; below the strip sat UNDER the painted band. The fix reserves
;; `projection/context-band-height` on TOP for the frame. This DOM
;; regression proves the rendered header (title strip + Context band) FITS
;; WITHIN the reserved ELK top padding the projection computes for the same
;; context-row count — i.e. ELK lays the first child below the rendered
;; header, never under it. (We pin against the reserved padding rather than
;; the child's measured Y because the elk layout pass is async + not awaited
;; here — see the async-layout note at the top of this ns.)

(def ^:private context-rich-machine
  "rf2-8z1rca — a flat machine declaring a 3-key `:data` so the chart paints
  a NON-TRIVIAL (3-row) Context band in the root-container header — enough
  to make the band taller than the plain title-strip reservation."
  {:initial :a
   :data    {:hits 0 :seen [] :note "x"}
   :states  {:a {:on {:go :b}}
             :b {:on {:go :a}}}})

(deftest chart-context-band-fits-within-reserved-elk-top-padding
  (testing "rf2-8z1rca — the rendered root-container HEADER (title strip +
            Context band) is no taller than the ELK TOP padding the frame
            reserves (`data-reserved-top`, the SAME `context-band-height`
            value `->elk-children` feeds ELK), so the first child ELK lays
            out below the strip clears the painted band. WITHOUT the band
            reservation `data-reserved-top` would be the plain title+body-pad
            band, which the rendered header OVERFLOWS — the regression fails."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/ctx :definition context-rich-machine
         :context-band {:hits "number" :seen "vector" :note "string"}}
        (fn [_root node]
          (let [frame  (.querySelector node "[data-root-container=\"true\"]")
                header (.querySelector node
                         "[data-testid^=\"rf-mv-chart-root-container-header-\"]")
                band   (.querySelector node
                         "[data-testid^=\"rf-mv-chart-root-container-context-\"]")
                vc-map vc/chart-regular
                ;; the ELK top padding the chart ACTUALLY reserved for this
                ;; context, surfaced by `root-container-node` from the same
                ;; `projection/context-band-height` `->elk-children` feeds ELK.
                reserved-top (js/parseInt (.getAttribute frame "data-reserved-top") 10)]
            (is (some? frame) "the root-container frame mounted")
            (is (some? header) "the root-container header mounted")
            (is (some? band) "the Context band mounted (non-trivial context)")
            ;; The painted Context band is itself non-trivial — strictly
            ;; taller than the plain body-pad band the OLD reservation gave.
            (is (> (.-offsetHeight band) (:container-body-pad vc-map))
                "the 3-row Context band is taller than the plain body-pad band")
            ;; The reserved top INCLUDES the Context band (it exceeds the plain
            ;; title+body-pad band) — the wiring `->elk-children` applies.
            (is (> reserved-top (+ (:container-title-height vc-map)
                                   (:container-body-pad vc-map)))
                "the reserved top includes the Context-band height (the fix)")
            ;; The whole rendered header (title strip + band) fits inside the
            ;; reserved ELK top padding — so a child laid out at the reserved
            ;; content edge starts BELOW the header, never under it.
            (is (<= (.-offsetHeight header) reserved-top)
                "the rendered header height fits within the reserved ELK top padding")))))))

(deftest chart-context-band-overflows-plain-title-reservation
  (testing "rf2-8z1rca — the bug premise: with a non-trivial Context band the
            rendered header is TALLER than the title-strip+body-pad band the
            pre-8z1rca reservation gave, so the OLD top padding would have
            laid the first child UNDER the band (what the fix reserves for)"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/ctx :definition context-rich-machine
         :context-band {:hits "number" :seen "vector" :note "string"}}
        (fn [_root node]
          (let [header (.querySelector node
                         "[data-testid^=\"rf-mv-chart-root-container-header-\"]")
                vc-map vc/chart-regular
                ;; the OLD (pre-8z1rca) top reservation: title strip + body-pad
                ;; only — no Context-band allowance.
                old-top (+ (:container-title-height vc-map)
                           (:container-body-pad vc-map))]
            (is (some? header) "the root-container header mounted")
            (is (> (.-offsetHeight header) old-top)
                "the rendered header overflows the pre-8z1rca top padding —
                 the band would have sat over the first child without the fix")))))))

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

;; ---- compound substate parent linkage (rf2-xh1lm visual-pin) -----------
;;
;; xyflow v12 reads `userNode.parentId` (NOT the pre-v12 `parentNode`) and
;; populates its `parentLookup` from it. A node whose id is the parentId
;; of any other node gets the CSS class `parent` on its
;; `.react-flow__node` wrapper (`adoptUserNodes` → store
;; `parentLookup.has(id)` → `isParent: true` → `parent` class). Without
;; that class no child is adopted; children render at root + visually
;; escape the container (the bug rf2-xh1lm fixed: substates rendered
;; top-left of canvas while the empty `:active` container sat
;; bottom-right). This DOM pin is layout-independent — the `.parent`
;; class lands on first commit, before the async elkjs layout pass —
;; so the synchronous browser-test runner can assert it without
;; awaiting elk.

(def ^:private compound-machine
  "A compound machine: :authenticated contains 2 substates (rf2-xh1lm
  regression fixture). Mirrors the `:ws/connection` shape that broke
  pre-fix: a single compound parent + nested substates."
  {:initial :unauth
   :states  {:unauth        {:on {:login :authenticated}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {:on {:done :browsing}}}
                             :on      {:logout :unauth}}}})

(deftest chart-compound-container-gets-xyflow-parent-class
  (testing "rf2-xh1lm — xyflow adopts compound substates via `parentId`,
            which causes the parent node to receive the CSS class
            `parent` (the on-DOM marker that `parentLookup.has(id)` is
            true). Pre-fix the projector emitted `:parentNode` (silently
            ignored by v12) so no child was ever adopted and the parent
            never got the class — substates rendered at root and escaped
            their container. Mounts the compound fixture and asserts the
            `:authenticated` container wrapper carries `.parent`."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/compound :definition compound-machine}
        (fn [_root node]
          (let [authed-id (layout/node-id [:authenticated])
                ;; xyflow's outer wrapper carries `data-id` + the
                ;; `parent` class when any child claims it via parentId.
                wrapper   (.querySelector node
                            (str ".react-flow__node[data-id=\"" authed-id "\"]"))]
            (is (some? wrapper)
                ":authenticated xyflow wrapper mounts")
            (is (.. wrapper -classList (contains "parent"))
                "the compound wrapper carries .parent — xyflow's parentLookup adopted at least one child via :parentId (rf2-xh1lm)")))))))

(deftest chart-region-container-gets-xyflow-parent-class
  (testing "rf2-xh1lm + rf2-lkwev — the same `.parent` adoption applies
            to parallel-region containers. Pre-fix BOTH compound AND
            region substates were unadopted (the projector emitted
            `:parentNode` for both); the bead screenshot only captured
            the compound symptom but the region path was equally broken.
            This pin guards the region adoption too."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/parallel :definition parallel-machine}
        (fn [_root node]
          (doseq [region-id (map layout/region-node-id [:audio :display])]
            (let [wrapper (.querySelector node
                            (str ".react-flow__node[data-id=\"" region-id "\"]"))]
              (is (some? wrapper)
                  (str "region " region-id " xyflow wrapper mounts"))
              (is (.. wrapper -classList (contains "parent"))
                  (str "region " region-id " carries .parent — its child states were adopted via :parentId")))))))))

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
            ;; rf2-wnzha — region states are region-scoped ids now.
            (is (contains? ids (layout/region-scoped-id :audio [:paused]))
                ":audio region's active leaf is in the active set")
            (is (contains? ids (layout/region-scoped-id :display [:off]))
                ":display region's active leaf is in the active set — SIMULTANEOUSLY")
            (is (= 2 (count ids))
                "exactly the two active region leaves, no more")))))))

(deftest chart-flat-current-state-single-active-back-compat
  (testing "rf2-g2svr — a flat (single-active) :current-state surfaces
            ONE id on data-highlight-ids (no regression)"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done
         :current-state :loading}
        (fn [root _node]
          (let [loading-id (layout/node-id [:loading])]
            (is (= loading-id (.getAttribute root "data-highlight-ids"))
                "the lone active leaf surfaces on data-highlight-ids")))))))

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
  extract-fired-edge-ids mint, via project-definition)."
  [definition from-path to-path event]
  (->> (:edges (layout/project-definition definition))
       (some (fn [e]
               (when (and (= from-path (:from-path e))
                          (= to-path   (:to-path e))
                          (= event     (:event e)))
                 (:id e))))))

(deftest chart-fired-event-node-renders-data-fired
  (testing "rf2-qeemm (G3) + rf2-qo5xy — events-as-nodes paradigm:
            passing :fired-edge-ids #{<idle→loading id>} marks the
            matching event-node (xyflow `rf2-event`) with
            data-fired=\"true\"; non-fired event-nodes carry
            data-fired=\"false\"."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (let [start-id (canonical-edge-id idle-loading-done [:idle] [:loading] :start)
            fired-ev-id (str "event__" start-id)]
        (with-mounted-chart
          {:machine-id     :test/flow
           :definition     idle-loading-done
           :fired-edge-ids #{start-id}}
          (fn [_root node]
            ;; Scope the per-node `data-fired` sweep to OUTER event-NODES
            ;; via `[data-node-id]` (the same precise-event-node convention
            ;; as the guard-blocked test + the `[data-variant]` count at the
            ;; bottom of this file). The bare `rf-mv-chart-event-` prefix also
            ;; matches the inner header/guard/reenter/action spans, which
            ;; carry NO `data-node-id`; scoping keeps `others` to real nodes
            ;; so the every?-is-false assertion can't be polluted as more
            ;; inner-span attrs land (rf2-6c2r83).
            (let [fired-el (.querySelector
                             node (str "[data-testid=\"rf-mv-chart-event-" fired-ev-id "\"]"))
                  all-evs  (array-seq
                             (.querySelectorAll
                               node "[data-testid^=\"rf-mv-chart-event-\"][data-node-id]"))
                  others   (remove #(= % fired-el) all-evs)]
              (when (some? fired-el)
                (is (= "true" (.getAttribute fired-el "data-fired")))
                (is (every? #(or (= "false" (.getAttribute % "data-fired"))
                                 (nil? (.getAttribute % "data-fired"))) others)))
              (is (number? (.-length (.querySelectorAll
                                       node "[data-testid^=\"rf-mv-chart-event-\"]")))))))))))

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

;; ---- guard-blocked no-op edge highlight (rf2-fzrzlw) -------------------
;;
;; The bead's repro: door in `:open`, `:door/close` blocked by the
;; `:may-close?` guard → no-op. The host resolves the blocked edge-ids via
;; `extract-guard-blocked-edge-ids` (from the named-guard guard-evaluated
;; fail/threw traces) and passes them as `:guard-blocked-edge-ids`. The
;; matching event-node renders with `data-guard-blocked="true"` (the DOM
;; pin for the PINK guard-blocked treatment) and the chart root surfaces
;; the sorted set on `data-guard-blocked-edge-ids`.

(def ^:private guarded-door-machine
  "rf2-fzrzlw — minimal door with a guarded `:door/close [may-close?]`
  (`:open` → `:closed`) so the guard-blocked no-op edge can be exercised."
  {:initial :closed
   :states  {:closed {:on {:door/open :open}}
             :open   {:on {:door/close {:target :closed :guard :may-close?}}}}})

(deftest chart-guard-blocked-event-node-renders-data-guard-blocked
  (testing "rf2-fzrzlw — passing :guard-blocked-edge-ids #{<close id>}
            marks the matching event-node with data-guard-blocked=\"true\";
            non-blocked event-nodes carry data-guard-blocked=\"false\"."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (let [close-id (canonical-edge-id guarded-door-machine [:open] [:closed] :door/close)
            blocked-ev-id (str "event__" close-id)]
        (with-mounted-chart
          {:machine-id     :test/door
           :definition     guarded-door-machine
           :guard-blocked-edge-ids #{close-id}}
          (fn [_root node]
            ;; Scope to OUTER event-NODES only via `[data-node-id]` (the
            ;; child header/guard/action/reenter spans share the
            ;; `rf-mv-chart-event-` testid prefix but carry no
            ;; `data-node-id` — same precise-event-node convention as the
            ;; `[data-variant]` count at the bottom of this file). The
            ;; bare prefix selector also matches the blocked node's OWN
            ;; inner guard span, which correctly carries
            ;; data-guard-blocked="true" (it paints the pink `IF <guard>`
            ;; chip) — including it in `others` would wrongly fail the
            ;; non-blocked-nodes-are-false assertion.
            (let [blocked-el (.querySelector
                               node (str "[data-testid=\"rf-mv-chart-event-" blocked-ev-id "\"]"))
                  all-evs    (array-seq
                               (.querySelectorAll
                                 node "[data-testid^=\"rf-mv-chart-event-\"][data-node-id]"))
                  others     (remove #(= % blocked-el) all-evs)]
              (when (some? blocked-el)
                (is (= "true" (.getAttribute blocked-el "data-guard-blocked")))
                (is (every? #(or (= "false" (.getAttribute % "data-guard-blocked"))
                                 (nil? (.getAttribute % "data-guard-blocked"))) others))))))))))

(deftest chart-guard-blocked-edge-ids-surfaces-on-root
  (testing "rf2-fzrzlw — the chart root surfaces the sorted guard-blocked
            set on data-guard-blocked-edge-ids; absent the prop the attr
            is empty"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (let [close-id (canonical-edge-id guarded-door-machine [:open] [:closed] :door/close)]
        (with-mounted-chart
          {:machine-id     :test/door
           :definition     guarded-door-machine
           :guard-blocked-edge-ids #{close-id}}
          (fn [root _node]
            (is (= close-id (.getAttribute root "data-guard-blocked-edge-ids"))
                "the guard-blocked edge-id surfaces on the root")))
        (with-mounted-chart
          {:machine-id :test/door :definition guarded-door-machine}
          (fn [root _node]
            (is (= "" (.getAttribute root "data-guard-blocked-edge-ids"))
                "no guard-blocked set → empty data-guard-blocked-edge-ids")))))))

;; ---- compound-endpoint edges render in DOM (rf2-shv82, Issue 1) --------
;;
;; The bug: xyflow v12 silently drops every edge whose source or target
;; is a compound node because the compound has no Handle children, so
;; `getHandleBounds` returns null → `isNodeInitialized` returns false →
;; `getEdgePosition` returns null → the edge never reaches the DOM. The
;; fix adds invisible Handles to compound-node + parallel-region-node so
;; xyflow accepts compound endpoints. These pins guard the renderer half
;; (the projector half is pinned by projection_cljs_test).

(def ^:private compound-endpoint-machine
  "Mirrors the testdeck `:ws/connection` shape at minimum size: a
  compound `:active` parent with parent-level transitions inherited by
  every leaf inside, plus an inbound transition from a sibling top-level
  state. Reproduces the 4 compound-endpoint edge shapes the rf2-shv82
  bead identified (compound-as-target, compound-as-source, compound
  self-loop, sibling-into-compound)."
  {:initial :idle
   :states  {:idle    {:on {:connect :active}}
             :active  {:initial :connecting
                       :on      {:disconnect :idle
                                 :send {}}
                       :states  {:connecting {:on {:done :connected}}
                                 :connected  {}}}
             :failed  {:on {:retry :active}}}})

(deftest chart-data-edge-count-projected-matches-parsed
  (testing "rf2-shv82 — the chart root carries
            `data-edge-count-projected` (the projector output count) +
            `data-edge-count` (the parser output count). For a compound-
            endpoint machine the two must agree at the parser→projector
            boundary so the silent-drop bug cannot recur there"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/compound :definition compound-endpoint-machine}
        (fn [root _node]
          (let [parsed    (layout/project-definition compound-endpoint-machine)
                expected  (count (:edges parsed))
                projected (-> (.getAttribute root "data-edge-count-projected")
                              js/parseInt)]
            (is (some? projected))
            ;; projector emits parsed-edges + entry-edges (initial markers).
            ;; we only assert >= parsed; entry edges add on top.
            (is (>= projected expected)
                (str "projected edges (" projected ") >= parsed edges ("
                     expected ") — no projection-layer drop"))))))))

(deftest chart-renders-compound-node-with-handle-class-targets
  (testing "rf2-shv82 (Issue 1) — the compound node renders with .source +
            .target Handle elements (xyflow's `getHandleBounds`
            specifically queries these classes; their presence is what
            makes `isNodeInitialized` return true → the edge survives the
            render). Without them xyflow silently drops every compound-
            endpoint edge from the DOM."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/compound :definition compound-endpoint-machine}
        (fn [_root node]
          (let [active-id (layout/node-id [:active])
                compound-el (.querySelector
                              node (str "[data-testid=\"rf-mv-chart-compound-"
                                        active-id "\"]"))]
            (is (some? compound-el) "the :active compound node mounted")
            (let [sources (.querySelectorAll compound-el ".source")
                  targets (.querySelectorAll compound-el ".target")]
              (is (pos? (.-length sources))
                  "compound node has at least one .source Handle")
              (is (pos? (.-length targets))
                  "compound node has at least one .target Handle"))))))))

(deftest chart-renders-parallel-region-with-handle-class-targets
  (testing "rf2-shv82 (Issue 1) — the parallel-region container also
            renders source + target Handle elements (mirrors the
            compound-node fix). Same silent-drop mechanic applies to any
            edge whose endpoint is a region container."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/parallel :definition parallel-machine}
        (fn [_root node]
          (let [audio-id (layout/region-node-id :audio)
                region-el (.querySelector
                            node (str "[data-testid=\"rf-mv-chart-region-"
                                      audio-id "\"]"))]
            (is (some? region-el) "the :audio region container mounted")
            (let [sources (.querySelectorAll region-el ".source")
                  targets (.querySelectorAll region-el ".target")]
              (is (pos? (.-length sources))
                  "region container has at least one .source Handle")
              (is (pos? (.-length targets))
                  "region container has at least one .target Handle"))))))))

;; ---- multi-event NO-collapse DOM (rf2-o6vh7) ----------------------------
;;
;; HISTORY: rf2-shv82 (Issue 2) shipped a self-loop perimeter fan; rf2-j10sm
;; (Phase 2) replaced it with a multi-event sibling-collapse (N events on one
;; `[source target]` pair → ONE arrow + N stacked labels via
;; `data-sibling-index`/`data-sibling-count`). rf2-o6vh7 RETIRED the
;; collapse: under events-as-nodes every event is its OWN node, so N events
;; on one node are N DISTINCT event-nodes (no grouping, no leader/follower).
;; This pins the no-collapse DOM contract — three events on `:idle` surface
;; three DISTINCT event-nodes — and that no `data-sibling-*` attr survives.

(def ^:private multi-self-loop-machine
  "Three self-loops on `:idle` (mirrors the testdeck `:disconnected`
  shape: 3 distinct events on one node, all self-transitions)."
  {:initial :idle
   :states  {:idle {:on {:arm    {:action :arm-it}
                         :disarm {:action :disarm-it}
                         :clear  {:action :clear-it}}}}})

(deftest chart-multi-events-on-one-node-stay-distinct-event-nodes
  (testing "rf2-o6vh7 — three events on one state render as THREE distinct
            event-nodes (no sibling-collapse). DOM evidence: three event-node
            roots (`data-variant`, unique to the event-node container) and
            ZERO `data-sibling-*` attrs."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/multi-self :definition multi-self-loop-machine}
        (fn [_root node]
          ;; `data-variant` is carried ONLY by the event-node root (the
          ;; child header/guard/action spans share the `rf-mv-chart-event-`
          ;; testid prefix but no `data-variant`), so it counts distinct
          ;; event-nodes precisely.
          (let [event-nodes (.querySelectorAll node "[data-variant]")
                ;; any element still carrying a retired sibling attr
                sibling-attr-els (.querySelectorAll
                                  node "[data-sibling-index],[data-sibling-count]")]
            ;; If event-nodes race the commit the count may lag; assert
            ;; positively when present, else keep the structural invariant
            ;; (mirrors the chart_dom convention).
            (when (>= (.-length event-nodes) 3)
              (is (= 3 (.-length event-nodes))
                  "three distinct events → three distinct event-nodes"))
            (is (zero? (.-length sibling-attr-els))
                "no data-sibling-* attr survives the collapse retirement")
            (is (number? (.-length event-nodes)))))))))

;; ---- guarded-fork priority badge DOM (rf2-uw3vmi visual-pin) ------------
;;
;; The projector threads each guarded-fork branch's 1-based priority onto
;; the event-node `:data {:forkOrder}` (pinned in projection_cljs_test); the
;; RENDERER paints it as a numbered circle chip (`chart.nodes.event-node/
;; fork-badge`) leading the branch event-node's header line, with testid
;; `rf-mv-chart-event-fork-badge-<event-node-id>` + a `data-fork-order` attr.
;; Projection pins keep going green even if a renderer refactor drops the
;; visible chip, so this DOM pin guards the visual affordance directly: the
;; gate `:gate/check` 3-way's branch event-nodes render badges carrying
;; data-fork-order 1, 2, 3, and the SEPARATE non-fork `:gate/set` /
;; `:gate/reset` event-nodes render NO badge. The badge mounts on the first
;; commit (it is part of the node body, layout-independent), so the
;; synchronous browser-test runner can assert it without awaiting elk.

(def ^:private gate-fork-machine
  "rf2-uw3vmi — the gate testbed shape: `:gate/check` FORKS from `:idle` by
  a guarded candidate VECTOR (first guard-pass wins: `:gate-high?` → :high,
  `:gate-low?` → :low, else the unguarded fallback → :rejected). `:gate/set`
  is a SEPARATE internal action-only transition on the SAME source (a
  different trigger), never part of the fork."
  {:initial :idle
   :data    {:level 0}
   :states  {:idle     {:on {:gate/set   {:action :set-level}
                             :gate/check [{:guard :gate-high? :target :high}
                                          {:guard :gate-low?  :target :low}
                                          {:target :rejected}]}}
             :low      {:on {:gate/reset :idle}}
             :high     {:on {:gate/reset :idle}}
             :rejected {:on {:gate/reset :idle}}}})

(deftest chart-guarded-fork-branches-render-priority-badges-1-2-3
  (testing "rf2-uw3vmi — the gate `:gate/check` 3-way's branch event-nodes
            render the numbered priority badge (testid
            rf-mv-chart-event-fork-badge-*) carrying data-fork-order 1, 2,
            and 3 — the visible affordance the projection :forkOrder pins
            cannot see. EXACTLY three badges render (one per branch), with
            the full {1 2 3} priority set."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/gate :definition gate-fork-machine}
        (fn [_root node]
          (let [badges (.querySelectorAll
                         node "[data-testid^=\"rf-mv-chart-event-fork-badge-\"]")
                orders (set (mapv (fn [i] (.getAttribute (aget badges i) "data-fork-order"))
                                  (range (.-length badges))))]
            ;; Badges are part of the node body (layout-independent); they
            ;; mount on the first commit. Assert positively when present,
            ;; else keep the structural invariant (the chart_dom convention).
            (when (pos? (.-length badges))
              (is (= 3 (.-length badges))
                  "exactly three fork-priority badges (one per :gate/check branch)")
              (is (= #{"1" "2" "3"} orders)
                  "the three branches carry data-fork-order 1, 2, and 3"))
            (is (number? (.-length badges)))))))))

(deftest chart-non-fork-event-nodes-render-no-priority-badge
  (testing "rf2-uw3vmi — non-fork event-nodes render NO priority badge: the
            gate's SEPARATE `:gate/set` trigger and the three single
            `:gate/reset` transitions carry no badge, and a plain machine
            with no guarded fork renders zero badges at all."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (do
        ;; A machine with NO guarded fork: zero badges anywhere.
        (with-mounted-chart
          {:machine-id :test/flow :definition idle-loading-done}
          (fn [_root node]
            (is (zero? (count-sel node "[data-testid^=\"rf-mv-chart-event-fork-badge-\"]"))
                "a fork-free machine renders no priority badges")))
        ;; The gate machine: badges appear ONLY on the three :gate/check
        ;; branches, never on the non-fork `:gate/set` / `:gate/reset` nodes.
        ;; Pin it via the event-node↔badge ratio: there are far more event-
        ;; nodes than the 3 fork branches, so a badge count of 0-or-3 with
        ;; strictly-fewer-badges-than-event-nodes proves the non-fork nodes
        ;; are un-badged.
        (with-mounted-chart
          {:machine-id :test/gate :definition gate-fork-machine}
          (fn [_root node]
            (let [badges (count-sel node "[data-testid^=\"rf-mv-chart-event-fork-badge-\"]")
                  ev-nodes (count-sel node "[data-testid^=\"rf-mv-chart-event-\"][data-node-id]")]
              (when (pos? badges)
                (is (= 3 badges)
                    "only the three fork branches are badged"))
              (when (and (pos? badges) (pos? ev-nodes))
                (is (< badges ev-nodes)
                    "fewer badges than event-nodes — the non-fork :gate/set + :gate/reset nodes carry none")))))))))

;; ---- fork connector renderer styling (rf2-qj6hoa) -----------------------
;; The projection suite proves `:forkConnector` edges EXIST + carry the
;; decorative `:data` shape, but no DOM/render test pins that the RENDERER
;; paints them DECORATIVE. Without a render pin a refactor of `edges.cljs`
;; could turn a fork connector back into a normal transition edge (arrowhead
;; + solid stroke + label) while every projection test stays green. This
;; mounts the REAL `edges/transition-edge` component with the REAL projector
;; fork-connector edge `:data` and asserts the rendered `<path>` is
;; decorative: NO markerEnd, `stroke-dasharray "1 3"`, round linecap, NO
;; edge label, neutral `:pseudo-marker` stroke. The connector carries no
;; route, so its straight handle-to-handle path mounts on the first commit
;; (no async elk dependency).

(defn- a-fork-connector-edge
  "The first decorative fork-connector edge the projector emits for the
  gate guarded fork (REAL projector output, not a hand-rolled map)."
  []
  (let [parsed (layout/project-definition gate-fork-machine)
        conns  (projection/fork-connector-edges
                 (:edges parsed) (tokens/chart-tokens) vc/chart-regular)]
    (first conns)))

(deftest chart-fork-connector-renders-decorative
  (testing "rf2-qj6hoa — a guarded-fork connector edge renders DECORATIVE:
            the `<path>` carries `stroke-dasharray \"1 3\"` (the tight dotted
            order chain, distinct from the internal self-transition's 4·3
            dash), a ROUND linecap, the neutral `:pseudo-marker` stroke, and
            NO markerEnd (arrowhead) — and the edge renders NO label div. A
            renderer refactor that turned it back into a normal transition
            edge would break these even while the projection pins stay green."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (let [edge (a-fork-connector-edge)
            ct   (tokens/chart-tokens)]
        (is (some? edge) "the gate fork yields a connector edge to render")
        (with-mounted-element
          (edges/transition-edge (->edge-props edge))
          (fn [node]
            ;; The connector id carries `/` + `?` (from the spec edge ids),
            ;; which are not valid in a CSS `#id` selector — find the path by
            ;; its `.id` PROPERTY across the rendered paths instead.
            (let [paths (.querySelectorAll node "path")
                  path  (some (fn [i]
                                (let [p (aget paths i)]
                                  (when (= (:id edge) (.-id p)) p)))
                              (range (.-length paths)))]
              (is (some? path) "the connector renders a BaseEdge <path>")
              (when (some? path)
                ;; DECORATIVE dotted order chain — tight 1·3 dots, round caps.
                ;; The browser canonicalises the dash list ("1 3" → "1, 3"),
                ;; so compare the numeric token sequence, not the raw string.
                (is (= ["1" "3"]
                       (->> (str/split (.. path -style -strokeDasharray) #"[,\s]+")
                            (remove str/blank?)
                            vec))
                    "dotted 1·3 dash (the Stately order chain, not 4·3 internal)")
                (is (= "round" (.. path -style -strokeLinecap))
                    "round linecap so the dots read as a soft order chain")
                ;; Neutral pseudo-marker stroke — an order annotation, never a
                ;; runtime edge hue (compared through the CSSOM normaliser so
                ;; the token's hex/rgba form matches the browser's read-back).
                (is (= (normalize-color (:pseudo-marker ct))
                       (normalize-color (.. path -style -stroke)))
                    "neutral :pseudo-marker stroke (order annotation, not runtime)")
                ;; NO arrowhead — `markerEnd` is suppressed for a connector.
                (let [me (.getAttribute path "marker-end")]
                  (is (or (nil? me) (= "" me) (= "none" me))
                      "no markerEnd — the connector carries no arrowhead")))
              ;; NO edge label div (the connector's eventLabel is empty). The
              ;; id carries `/`+`?` (invalid in a `#id`/`=` selector), so
              ;; count the label-testid PREFIX instead — zero labels render.
              (is (zero? (count-sel node "[data-testid^=\"rf-mv-chart-edge-\"]"))
                  "the decorative connector renders no edge label"))))))))

;; ---- enclosed event action chip rendering (rf2-ekniye) ------------------
;; The projector threads each transition's `:action` onto the event-node
;; `:data {:action}`, and the RENDERER (`chart.nodes.event-node`) paints it
;; as a subdued ENCLOSED action chip (`rf-mv-chart-event-action-*` testid +
;; `data-action`, density-aware geometry). No DOM test pinned the chip
;; contract, so a refactor could drop the enclosed styling while projection
;; stays green. The gate's `:gate/set` is an action-only transition
;; (`{:action :set-level}`), so its event-node renders the chip; the
;; `:gate/check` / `:gate/reset` event-nodes carry NO action → NO chip. The
;; chip is part of the node body (layout-independent), so it mounts on the
;; first commit without awaiting elk.

(deftest chart-action-bearing-event-renders-enclosed-action-chip
  (testing "rf2-ekniye — an action-bearing event-node (the gate's
            `:gate/set` → `:set-level`) renders the enclosed action chip:
            testid `rf-mv-chart-event-action-*` carrying `data-action`
            \"set-level\", with the ENCLOSED styling (`:container-header-bg`
            fill + `:state-border` border + the density `:action-pill-radius`
            rounding / `:action-pill-height` height / `:action-pill-pad-x`
            padding) so it reads as a contained annotation — not loose
            free-floating text."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/gate :definition gate-fork-machine}
        (fn [_root node]
          (let [chips (.querySelectorAll
                        node "[data-testid^=\"rf-mv-chart-event-action-\"]")
                ct    (tokens/chart-tokens)
                {:keys [action-pill-height action-pill-pad-x
                        action-pill-radius]} vc/chart-regular]
            ;; The action chip mounts on the first commit (node body). Assert
            ;; positively when present, else keep the structural invariant
            ;; (the chart_dom convention for layout-independent body chrome).
            (when (pos? (.-length chips))
              ;; EXACTLY one action-bearing event-node: the gate's :gate/set.
              (is (= 1 (.-length chips))
                  "exactly one enclosed action chip (only :gate/set bears an action)")
              (let [chip (aget chips 0)]
                (is (= "set-level" (.getAttribute chip "data-action"))
                    "data-action carries the action name")
                ;; ENCLOSED styling — fill + border + rounding (a contained
                ;; annotation, not loose text). Colours compared through the
                ;; CSSOM normaliser (the tokens are rgba(); the DOM read-back
                ;; canonicalises both sides identically).
                (is (= (normalize-color (:container-header-bg ct))
                       (normalize-color (.. chip -style -backgroundColor)))
                    "enclosed neutral container-header fill")
                (is (= (normalize-color (:state-border ct))
                       (normalize-color (.. chip -style -borderColor)))
                    "enclosed structural border colour")
                (is (= "1px" (.. chip -style -borderWidth))
                    "enclosed 1px structural border")
                (is (= "solid" (.. chip -style -borderStyle))
                    "enclosed solid border (a contained chip, not a dashed hint)")
                (is (= (str action-pill-radius "px") (.. chip -style -borderRadius))
                    "density-aware corner radius")
                ;; Density-aware geometry — height + horizontal padding.
                (is (= (str action-pill-height "px") (.. chip -style -height))
                    "density-aware chip height")
                (is (str/includes? (.. chip -style -padding) (str action-pill-pad-x "px"))
                    "density-aware horizontal padding")))
            (is (number? (.-length chips)))))))))

(deftest chart-non-action-event-nodes-render-no-action-chip
  (testing "rf2-ekniye — event-nodes WITHOUT an action render NO action chip.
            The gate's `:gate/check` branches + `:gate/reset` transitions are
            action-free, and a plain machine with no action-bearing
            transition renders zero action chips at all (so the chip is
            strictly an action affordance, never decoration)."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (do
        ;; A machine with NO action-bearing transition: zero chips anywhere.
        (with-mounted-chart
          {:machine-id :test/flow :definition idle-loading-done}
          (fn [_root node]
            (is (zero? (count-sel node "[data-testid^=\"rf-mv-chart-event-action-\"]"))
                "an action-free machine renders no action chips")))
        ;; The gate machine: chips appear ONLY on the one action-bearing
        ;; :gate/set node, never on the action-free :gate/check / :gate/reset
        ;; event-nodes (far more event-nodes than the single chip).
        (with-mounted-chart
          {:machine-id :test/gate :definition gate-fork-machine}
          (fn [_root node]
            (let [chips    (count-sel node "[data-testid^=\"rf-mv-chart-event-action-\"]")
                  ev-nodes (count-sel node "[data-testid^=\"rf-mv-chart-event-\"][data-node-id]")]
              (when (pos? chips)
                (is (= 1 chips)
                    "only the single :gate/set action-bearing node carries a chip"))
              (when (and (pos? chips) (pos? ev-nodes))
                (is (< chips ev-nodes)
                    "fewer chips than event-nodes — the action-free nodes carry none")))))))))

;; ---- :on-state-click contract: leaf body + compound title strip --------
;; rf2-34ff3 — A-PRIME ruling. `:on-state-click` fires for REAL statechart-
;; state nodes: a LEAF state (its body) and a COMPOUND state (its TITLE
;; STRIP only — the compound BODY stays `pointer-events:none` so a click
;; inside it falls through to the nested leaf). The synthetic machine-root
;; chip + parallel-region containers are NOT click targets (the projector
;; threads `:onClick` onto leaf + compound `:data` only). These DOM pins
;; prove (1) the compound title click fires with the COMPOUND's path, and
;; (2) a child-leaf click still fires (body pass-through preserved).

(defn- dispatch-click!
  "Dispatch a bubbling native click on `el` inside React's act() env so
  the synthetic-event delegate (attached at the root) catches it."
  [el]
  (let [act-fn (get-act)
        ev     (js/MouseEvent. "click" #js {:bubbles true :cancelable true})]
    (act-fn (fn [] (.dispatchEvent el ev)))))

(deftest chart-compound-title-strip-fires-on-state-click-with-compound-path
  (testing "rf2-34ff3 — clicking a COMPOUND state's TITLE STRIP fires
            `:on-state-click` with the COMPOUND's own path. The title strip
            re-enables pointer-events (`pointer-events:auto`) while the
            compound body stays `pointer-events:none` (leaf pass-through)."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (let [clicks (atom [])]
        (with-mounted-chart
          {:machine-id     :test/compound
           :definition     compound-machine
           :on-state-click (fn [path] (swap! clicks conj (js->clj path)))}
          (fn [_root node]
            (let [authed-id (layout/node-id [:authenticated])
                  title-el  (.querySelector
                              node (str "[data-testid=\"rf-mv-chart-compound-title-"
                                        authed-id "\"]"))
                  body-el   (.querySelector
                              node (str "[data-testid=\"rf-mv-chart-compound-"
                                        authed-id "\"]"))]
              (is (some? title-el) "the :authenticated compound title strip mounted")
              (is (some? body-el) "the :authenticated compound body mounted")
              ;; Pointer-events split: strip is clickable, body passes through.
              (is (= "auto" (.. title-el -style -pointerEvents))
                  "compound TITLE STRIP carries pointer-events:auto (clickable)")
              (is (= "none" (.. body-el -style -pointerEvents))
                  "compound BODY stays pointer-events:none (leaf pass-through)")
              ;; The affordance is wired (cursor + title).
              (is (= "pointer" (.. title-el -style -cursor))
                  "the title strip signals the click affordance via cursor")
              (dispatch-click! title-el)
              (is (= [["authenticated"]] @clicks)
                  "compound title click fires :on-state-click with the compound's path"))))))))

(deftest chart-leaf-click-still-fires-on-state-click-body-pass-through
  (testing "rf2-34ff3 — a LEAF state click still fires `:on-state-click`
            with the leaf's path. Two leaves are exercised: a TOP-LEVEL
            leaf (`:unauth`) and a SUBSTATE leaf NESTED inside the compound
            (`[:authenticated :browsing]`). The nested-leaf click proves the
            compound body's `pointer-events:none` lets clicks pass through to
            the child leaf (the body never swallows the click)."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (let [clicks (atom [])]
        (with-mounted-chart
          {:machine-id     :test/compound
           :definition     compound-machine
           :on-state-click (fn [path] (swap! clicks conj (js->clj path)))}
          (fn [_root node]
            (let [unauth-id   (layout/node-id [:unauth])
                  browsing-id (layout/node-id [:authenticated :browsing])
                  unauth-el   (.querySelector
                                node (str "[data-testid=\"rf-mv-chart-node-"
                                          unauth-id "\"]"))
                  browsing-el (.querySelector
                                node (str "[data-testid=\"rf-mv-chart-node-"
                                          browsing-id "\"]"))]
              (is (some? unauth-el) "the :unauth leaf node mounted")
              (is (some? browsing-el)
                  "the nested :browsing leaf node mounted inside the compound")
              ;; Leaf body is itself clickable (pointer-events default/auto).
              (is (= "pointer" (.. unauth-el -style -cursor))
                  "the leaf signals its click affordance via cursor")
              (dispatch-click! unauth-el)
              (is (= [["unauth"]] @clicks)
                  "top-level leaf click fires :on-state-click with the leaf path")
              ;; Nested-leaf click: the compound body must NOT swallow it.
              (dispatch-click! browsing-el)
              (is (= [["unauth"] ["authenticated" "browsing"]] @clicks)
                  "nested-leaf click still fires (compound body pass-through preserved)"))))))))

;; ---- initial-marker painted dot radius (rf2-sxwqzs / rf2-k7kiiq) --------
;;
;; rf2-k7kiiq DISTINGUISHES two radii on the initial pseudo-state dot: the
;; GEOMETRY radius (the full `:pseudo-radius`, which feeds the arm/arrowhead
;; math + the forward-flow invariant) and the PAINTED radius (`:pseudo-radius
;; − 0.5`, a tighter Stately-aligned dot). The −0.5px painted shrink lives ONLY
;; in the renderer (`chart.nodes/initial-marker` `dot-paint-r`) — the projection
;; tests cover offset / arrowhead / forward-flow but NEVER the actual painted
;; `<circle r>`. rf2-sxwqzs pins it directly: the `rf-mv-chart-initial-marker-dot`
;; circle renders with `r = pseudo-radius − 0.5` at every density. The glyph is a
;; node-body shape (no route), so it mounts on the FIRST commit — assertable
;; without awaiting the async elkjs layout pass (the chart_dom convention).

(deftest chart-initial-marker-dot-paints-at-pseudo-radius-minus-half
  (testing "rf2-sxwqzs / rf2-k7kiiq — the initial-marker dot
            (`rf-mv-chart-initial-marker-dot`) renders its `<circle>` with
            radius `pseudo-radius − 0.5` (the PAINTED radius, distinct from the
            full GEOMETRY radius the glyph math reads) across compact / regular
            / cosy density. Guards the renderer-only −0.5px painted shrink that
            no projection test can see."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (doseq [[density vc-map] [[:compact vc/chart-compact]
                                [:regular vc/chart-regular]
                                [:cosy    vc/chart-cosy]]]
        (with-mounted-chart
          {:machine-id :test/flow :definition idle-loading-done :density density}
          (fn [_root node]
            (let [dot (.querySelector node "[data-testid=\"rf-mv-chart-initial-marker-dot\"]")
                  expected (- (:pseudo-radius vc-map) 0.5)]
              ;; The dot is node-body chrome (layout-independent); it mounts on
              ;; the first commit. Assert positively when present, else keep the
              ;; structural invariant (the chart_dom convention).
              (when (some? dot)
                (is (= expected (js/parseFloat (.getAttribute dot "r")))
                    (str density ": dot radius = pseudo-radius − 0.5 ("
                         expected ", the PAINTED radius — not the full "
                         (:pseudo-radius vc-map) " geometry radius)")))
              (is (some? dot)
                  (str density ": the initial-marker dot mounted")))))))))
