(ns day8.re-frame2-machines-viz.export-dom-cljs-test
  "Browser-side CLJS regression for the chart IMAGE exporters (rf2-sr6l3).

  ## Why this exists

  The PNG / SVG image exporters were EDGE-ONLY: they cloned the first
  xyflow `<svg>` (the edge layer) and rasterised THAT. But the chart's
  visual grammar — boxed state nodes, compound/region containers, event
  chips, final rings, labels, AND the active-state border/glow — renders
  as inline-styled DOM divs inside `.react-flow__viewport`, AROUND the
  edge svg. So `chart-as-svg` / `chart-as-png!` could not faithfully
  export the topology and in particular MISSED the active-state
  highlight the `API.md` §PNG / §SVG contract promises.

  rf2-sr6l3 reworks the exporters to capture the WHOLE rendered viewport
  via a `<foreignObject>` (the node bodies' inline styles carry the
  grammar + the active-affordance) and to resolve the export root off the
  `_rfMvChartState` SEAM rather than the hard-coded default `data-testid`
  (so export works from the root / a descendant / a wrapper, for the
  default AND a custom `:testid`).

  This suite FAILS against the prior edge-only / testid-keyed
  implementation:

    - the SVG no longer carries any state-box text (edge-only svg has no
      node bodies) → `svg-includes-state-boxes-and-active-affordance`
      fails;
    - the active-affordance attr never appears in the edge-only svg →
      same test fails;
    - a custom-`:testid` chart's export from a wrapper threw
      `:no-chart-state` under the hard-coded selector →
      `export-resolves-root-from-wrapper-and-custom-testid` fails.

  The share-url + Mermaid lanes are unchanged and stay covered by
  `export_cljs_test.cljs` (the node-runtime seam tests).

  ## Target

  ns ends in `-dom-cljs-test` so it runs under the `:browser-test`
  build (real DOM + headless Chromium, real canvas / Image / SVG
  rasterisation) per `shadow-cljs.edn` (rf2-2hrj8). Under `:node-test`
  (no DOM) every test short-circuits via `(browser?)` and asserts a
  trivial truth so the suite stays green on both targets.

  ## Mounting + async note

  Mounts via the substrate-adapter React bridge under React `act()`
  (same path as `chart_dom_cljs_test`). The node DOM + the
  `_rfMvChartState` seam + the inline node styles all land on the FIRST
  commit (they do not depend on the async elkjs layout pass — which only
  repositions already-mounted nodes), so `chart-as-svg`'s viewport
  capture is exercisable synchronously. The PNG raster is async (an
  `<img>` onload), so those tests use `cljs.test`'s `async` form and
  unmount only after the Promise settles."
  (:require ["react"            :as React]
            ["react-dom/client" :as react-dom-client]
            [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing async]]
            [day8.re-frame2-machines-viz.adapters.react-chart :as react-chart]
            [day8.re-frame2-machines-viz.export :as export]
            [day8.re-frame2-machines-viz.share :as share]))

;; ---- sample machine -----------------------------------------------------

(def ^:private idle-loading-done
  "4 states, 3 transitions. `:loading` is the active state in the tests
  that pin the current-state highlight."
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :done :err :failed}}
             :done    {:final? true}
             :failed  {:final? true}}})

;; ---- DOM mount helpers (mirror chart_dom_cljs_test) ---------------------

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

(defn- mount-node!
  "Create a sized host div (xyflow needs a non-zero parent box to lay
  out). The chart mounts INTO this host, so the host is itself a WRAPPER
  around the chart root — which is what the wrapper-path export tests
  hand to the exporters."
  []
  (let [el (.createElement js/document "div")]
    (set! (.. el -style -width) "800px")
    (set! (.. el -style -height) "600px")
    (.appendChild (.-body js/document) el)
    el))

(defn- with-mounted-chart
  "Mount `MachineChart` with `props` via the React bridge under act(),
  call `(f host-node)` with the HOST node (the outer mount div — NOT the
  chart root, so the export-root resolution is genuinely exercised), then
  unmount. Returns nil when no DOM / no act() (the caller asserts the
  skip)."
  [props f]
  (let [act-fn (get-act)]
    (when (and (browser?) act-fn)
      (enable-react-act-env!)
      (let [node (mount-node!)
            root (react-dom-client/createRoot node)]
        (try
          (act-fn (fn [] (.render root (react-chart/chart-element props))))
          (f node)
          (finally
            (try (act-fn (fn [] (.unmount root))) (catch :default _ nil))
            (try (.removeChild (.-body js/document) node) (catch :default _ nil))))))))

(defn- chart-root-of
  "The chart root element (carries the `_rfMvChartState` seam +
  `role=application`) under host `node`, for the given `:testid`."
  [^js node testid]
  (.querySelector node (str "[data-testid=\"" testid "\"]")))

(defn- layout-settled?
  "True once xyflow has laid the chart out: at least one state-node
  wrapper exists AND none are still `visibility:hidden` (xyflow hides
  every node until the async elkjs pass delivers positions + it has
  measured them). Until then a raster of the viewport is BLANK — every
  node is hidden — so the PNG test must wait for this before capturing."
  [^js node]
  (let [wrappers (.querySelectorAll node ".react-flow__node")
        n        (.-length wrappers)]
    (and (pos? n)
         (loop [i 0]
           (if (< i n)
             (let [^js w (aget wrappers i)
                   vis   (.. w -style -visibility)]
               (if (= "hidden" vis) false (recur (inc i))))
             true)))))

(defn- with-mounted-chart-after-layout
  "Mount `MachineChart`, then WAIT (polling) for the async elkjs layout to
  settle so every node is visible + positioned, then call `(f host-node
  finish)`. `finish` unmounts + removes the host. The caller MUST call
  `finish` (then `done`). No-op (calls `(skip)`) when no DOM / no act().

  Unlike `with-mounted-chart` (synchronous, pre-layout), this awaits the
  real rendered chart — required for the PNG raster, whose foreignObject
  capture of a pre-layout chart would be blank (all nodes hidden)."
  [props f skip]
  (let [act-fn (get-act)]
    (if-not (and (browser?) act-fn)
      (skip)
      (do
        (enable-react-act-env!)
        (let [node   (mount-node!)
              root   (react-dom-client/createRoot node)
              finish (fn []
                       (try (act-fn (fn [] (.unmount root))) (catch :default _ nil))
                       (try (.removeChild (.-body js/document) node) (catch :default _ nil)))
              deadline (+ (js/Date.now) 8000)]
          (act-fn (fn [] (.render root (react-chart/chart-element props))))
          ;; Poll for layout settle. Each tick we flush React under act()
          ;; (so any elkjs-settle-driven re-render commits), then check the
          ;; DOM. setTimeout (not a single rAF) so the elkjs Promise +
          ;; measure-relayout passes have real time to resolve.
          (letfn [(tick []
                    (act-fn (fn [] nil)) ;; flush pending React work
                    (cond
                      (layout-settled? node) (f node finish)
                      (> (js/Date.now) deadline)
                      (do
                        ;; Timed out — still call f so the test can report a
                        ;; meaningful failure rather than hang.
                        (f node finish))
                      :else (js/setTimeout tick 50)))]
            (js/setTimeout tick 50)))))))

;; ---- 1. SVG carries the node grammar + active-state affordance ----------

(deftest svg-includes-state-boxes-and-active-affordance
  (testing "rf2-sr6l3 — chart-as-svg captures the FULL viewport: the
            exported SVG carries the state-box text (state labels), is a
            `<foreignObject>`-wrapped capture (not the bare edge svg), and
            includes the active-state affordance (`data-active-affordance`
            on the highlighted `:loading` node). The prior edge-only
            exporter carried NONE of these."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done
         :current-state :loading}
        (fn [node]
          (let [svg (export/chart-as-svg (chart-root-of node "rf-mv-chart"))]
            (is (string? svg) "chart-as-svg returns a string")
            ;; It is a full-viewport capture, NOT the bare edge svg.
            (is (str/includes? svg "foreignObject")
                "the SVG embeds the viewport via foreignObject")
            ;; State BOX text — the node labels render in the captured
            ;; node bodies (edge-only svg had no node text).
            (is (str/includes? svg "loading")
                "the SVG carries state-box text (the :loading label)")
            (is (str/includes? svg "idle")
                "the SVG carries state-box text (the :idle label)")
            ;; The active-state AFFORDANCE rides on the captured node DOM:
            ;; the highlighted leaf carries data-active-affordance=true +
            ;; a box-shadow glow. Pin the attr (deterministic) AND the
            ;; box-shadow (the visible glow ring).
            (is (str/includes? svg "data-active-affordance=\"true\"")
                "the SVG includes the active-state affordance attr")
            (is (str/includes? svg "box-shadow")
                "the active node's inline box-shadow glow survives the capture")
            ;; <title>/<desc> machine summary (accessibility) preserved.
            (is (str/includes? svg "<title>")
                "the SVG carries the <title> machine summary")
            (is (str/includes? svg "<desc>")
                "the SVG carries the <desc> machine summary")))))))

(deftest svg-without-active-state-has-no-affordance
  (testing "rf2-sr6l3 — without :current-state the export carries the
            state boxes but NO active-affordance attr set true (control:
            proves the affordance assertion above is signal, not noise)."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done}
        (fn [node]
          (let [svg (export/chart-as-svg (chart-root-of node "rf-mv-chart"))]
            (is (str/includes? svg "loading")
                "state boxes still captured with no highlight")
            (is (not (str/includes? svg "data-active-affordance=\"true\""))
                "no active node → no true active-affordance in the export")))))))

;; ---- 2. PNG rasterises the full chart (state box + affordance) ----------

(defn- png-blob->image-data
  "Decode a PNG Blob into the canvas `ImageData` of its raster (so the
  test can prove the raster is non-empty — i.e. node content actually
  drew, not just a transparent edge layer). Returns a Promise resolving
  to `{:width :height :non-transparent-px <int>}`."
  [blob]
  (js/Promise.
    (fn [resolve reject]
      (let [url (.createObjectURL js/URL blob)
            img (js/Image.)]
        (set! (.-onload img)
              (fn []
                (try
                  (let [w (.-width img) h (.-height img)
                        canvas (.createElement js/document "canvas")]
                    (set! (.-width canvas) w)
                    (set! (.-height canvas) h)
                    (let [ctx (.getContext canvas "2d")]
                      (.drawImage ctx img 0 0)
                      (let [data (.-data (.getImageData ctx 0 0 w h))
                            n    (.-length data)
                            ;; count pixels with a non-zero alpha channel
                            non-transparent
                            (loop [i 3 acc 0]
                              (if (< i n)
                                (recur (+ i 4) (if (pos? (aget data i)) (inc acc) acc))
                                acc))]
                        (.revokeObjectURL js/URL url)
                        (resolve {:width w :height h
                                  :non-transparent-px non-transparent}))))
                  (catch :default e
                    (.revokeObjectURL js/URL url)
                    (reject e)))))
        (set! (.-onerror img)
              (fn [_]
                (.revokeObjectURL js/URL url)
                (reject (js/Error. "decode of exported PNG failed"))))
        (set! (.-src img) url)))))

(deftest png-rasterises-full-chart-with-content
  (testing "rf2-sr6l3 — chart-as-png! resolves to an image/png Blob whose
            raster is NON-EMPTY (the laid-out node boxes + the
            active-state glow actually drew onto the canvas). The prior
            edge-only PNG on a transparent background carried no node
            boxes. Proves the foreignObject viewport capture rasterises
            end to end — once the elkjs layout has settled (nodes are
            `visibility:hidden` until then, so we await layout)."
    (async done
      (with-mounted-chart-after-layout
        {:machine-id :test/flow :definition idle-loading-done
         :current-state :loading}
        (fn [node finish]
          (is (layout-settled? node)
              "elkjs layout settled — nodes are visible + positioned for capture")
          (-> (export/chart-as-png! (chart-root-of node "rf-mv-chart"))
              (.then
                (fn [blob]
                  (is (instance? js/Blob blob) "resolves to a Blob")
                  (is (= "image/png" (.-type blob)) "the Blob is image/png")
                  (is (pos? (.-size blob)) "the PNG Blob is non-empty bytes")
                  (png-blob->image-data blob)))
              (.then
                (fn [{:keys [width height non-transparent-px]}]
                  (is (pos? width) "the PNG has a positive width")
                  (is (pos? height) "the PNG has a positive height")
                  (is (pos? non-transparent-px)
                      "the PNG raster has drawn (non-transparent) content —
                       the node boxes + the active-state glow, not a blank
                       transparent edge layer")
                  (finish)
                  (done)))
              (.catch
                (fn [e]
                  (is false (str "chart-as-png! rejected: " e))
                  (finish)
                  (done)))))
        ;; skip (no DOM / no act)
        (fn []
          (is true ":node-test: no DOM — browser-test runner exercises this")
          (done))))))

;; ---- 3. export-root resolution: root / descendant / wrapper -------------
;;        for default AND custom :testid (seam-keyed, not testid-keyed)

(deftest export-resolves-root-from-root-descendant-and-wrapper
  (testing "rf2-sr6l3 — chart-as-svg resolves the export root off the
            `_rfMvChartState` seam, so it works when called with the chart
            ROOT, a DESCENDANT node inside it, or a WRAPPER element around
            it. All three must produce the same node-bearing SVG."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done
         :current-state :loading}
        (fn [node]
          (let [root (chart-root-of node "rf-mv-chart")
                ;; a descendant node deep inside the chart (a state node)
                descendant (.querySelector node "[data-testid^=\"rf-mv-chart-node-\"]")
                ;; the host `node` is itself a WRAPPER around the chart root
                wrapper node]
            (is (some? root) "chart root mounted")
            (is (some? descendant) "a descendant state-node mounted")
            ;; 1 — from the ROOT.
            (is (str/includes? (export/chart-as-svg root) "loading")
                "export from the chart root carries node text")
            ;; 2 — from a DESCENDANT (ancestor-seam walk).
            (is (str/includes? (export/chart-as-svg descendant) "loading")
                "export from a descendant resolves the root via the seam ancestor-walk")
            ;; 3 — from a WRAPPER (descendant-seam search).
            (is (str/includes? (export/chart-as-svg wrapper) "loading")
                "export from a wrapper resolves the root via the seam descendant-search")))))))

(deftest export-resolves-root-from-wrapper-and-custom-testid
  (testing "rf2-sr6l3 — the seam-keyed lookup works for a CUSTOM `:testid`
            too. The prior hard-coded `[data-testid='rf-mv-chart']`
            selector found NOTHING for a custom-testid chart, so export
            from a wrapper / descendant threw `:no-chart-state`. With the
            seam-keyed lookup, export from the wrapper (host node) AND from
            a descendant resolves the custom-testid chart and carries the
            node grammar."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done
         :current-state :loading
         :testid "my-custom-chart"}
        (fn [node]
          (let [root (chart-root-of node "my-custom-chart")
                descendant (.querySelector node "[data-testid^=\"rf-mv-chart-node-\"]")]
            (is (some? root) "custom-testid chart root mounted")
            ;; from the wrapper (host node) — descendant-seam search.
            (let [svg (export/chart-as-svg node)]
              (is (str/includes? svg "loading")
                  "export from a wrapper resolves a CUSTOM-testid chart via the seam")
              (is (str/includes? svg "data-active-affordance=\"true\"")
                  "the active affordance survives for a custom-testid chart too"))
            ;; from a descendant — ancestor-seam walk.
            (is (str/includes? (export/chart-as-svg descendant) "loading")
                "export from a descendant of a custom-testid chart resolves the root")
            ;; from the root itself.
            (is (str/includes? (export/chart-as-svg root) "loading")
                "export from the custom-testid root carries node text")))))))

;; ---- 4. share-url + mermaid lanes stay unchanged (regression guard) -----

(deftest share-url-and-mermaid-unchanged-on-live-chart
  (testing "rf2-sr6l3 — reworking the IMAGE exporters leaves the share-url
            + Mermaid lanes intact: both still derive off the live chart's
            seam, from the root AND a wrapper. (The node-runtime seam tests
            in export_cljs_test cover the stub-element path; this guards
            the live-DOM path stays equivalent.)"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done
         :current-state :loading}
        (fn [node]
          (let [root (chart-root-of node "rf-mv-chart")]
            ;; share-url from the live chart root round-trips.
            (let [url (export/share-url root)
                  cs  (:rf.machines-viz.share/chart (share/decode-share-url url))]
              (is (str/includes? url "#machine=") "share-url is a machine fragment")
              (is (= :test/flow (:machine-id cs)) "share-url carries the machine-id")
              (is (= idle-loading-done (:definition cs))
                  "share-url carries the full definition")
              (is (= {:state :loading} (:snapshot cs))
                  "share-url carries the active-state name (no :data)"))
            ;; share-url from the WRAPPER resolves the same root.
            (is (str/includes? (export/share-url node) "#machine=")
                "share-url resolves from a wrapper via the seam too")
            ;; Mermaid lane still emits a fenced stateDiagram from the seam.
            (let [md (export/chart-as-mermaid root)]
              (is (str/includes? md "stateDiagram-v2")
                  "chart-as-mermaid still emits a stateDiagram from the live seam")
              (is (str/includes? md "mermaid")
                  "chart-as-mermaid still fences the block"))))))))

;; ---- 5. EP-0015 — live Context band redacts before export (rf2-27e38h) --

(deftest svg-export-redacts-sensitive-live-context
  (testing "rf2-27e38h — chart-as-svg of a chart fed LIVE :context-band with
            a schema-marked sensitive slot does NOT carry the raw secret in
            the serialised SVG (the band is local-redacted by default); the
            :rf/redacted sentinel appears instead and a non-sensitive slot
            still renders."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (let [secret "card-4111-1111-1111-1111"]
        (with-mounted-chart
          {:machine-id :test/flow :definition idle-loading-done
           :current-state :loading
           ;; A host feeding LIVE values (inferred? false) declares which
           ;; slots are sensitive (derived from the machine's :data-schema).
           :context-band {:card secret :count 7}
           :context-band-inferred? false
           :context-band-sensitive #{:card}}
          (fn [node]
            (let [svg (export/chart-as-svg (chart-root-of node "rf-mv-chart"))]
              (is (string? svg))
              (is (not (str/includes? svg secret))
                  "the raw secret must not appear in the exported SVG")
              (is (str/includes? svg ":rf/redacted")
                  "the redaction sentinel appears in place of the secret")
              (is (str/includes? svg "7")
                  "the non-sensitive :count slot still renders"))))))))

(deftest svg-export-passes-live-context-when-raw-opted-in
  (testing "rf2-27e38h — :context-band-raw? true is the explicit
            trusted-local opt-in: the raw value IS serialised (local-raw)."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (let [value "visible-debug-value"]
        (with-mounted-chart
          {:machine-id :test/flow :definition idle-loading-done
           :current-state :loading
           :context-band {:dbg value}
           :context-band-inferred? false
           :context-band-sensitive #{:dbg}   ;; would redact by default…
           :context-band-raw? true}           ;; …but raw opt-in overrides
          (fn [node]
            (let [svg (export/chart-as-svg (chart-root-of node "rf-mv-chart"))]
              (is (str/includes? svg value)
                  "raw opt-in serialises the value (local-raw trusted path)"))))))))
