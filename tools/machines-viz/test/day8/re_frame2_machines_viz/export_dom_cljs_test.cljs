(ns day8.re-frame2-machines-viz.export-dom-cljs-test
  "Browser-side pins for the chart IMAGE exporters.

  The chart's visual grammar — boxed state nodes, containers, event chips,
  labels and the active-state border / glow — renders as inline-styled DOM
  inside `.react-flow__viewport`, AROUND xyflow's edge `<svg>`. So the
  exporters capture the whole viewport in a `<foreignObject>`, and resolve
  the export root off the `_rfMvChartState` seam rather than a default
  `data-testid`. An edge-only capture carries no state-box text and no
  active affordance; a testid-keyed lookup cannot find a custom-`:testid`
  chart from a wrapper. The share-url + Mermaid lanes' node-runtime tests
  are in `export-cljs-test`.

  ns ends in `-dom-cljs-test`, so it runs under `:browser-test` (real DOM,
  headless Chromium, real canvas / Image / SVG rasterisation); under
  `:node-test` every test short-circuits via `(browser?)`. The node DOM, the
  seam and the inline node styles land on the FIRST commit, so the SVG
  capture runs synchronously; only the PNG raster awaits the async elkjs
  layout."
  (:require ["react"            :as React]
            ["react-dom/client" :as react-dom-client]
            [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing async]]
            [day8.re-frame2-machines-viz.adapters.react-chart :as react-chart]
            [day8.re-frame2-machines-viz.export :as export]
            [day8.re-frame2-machines-viz.share :as share]))

(def ^:private test-host "https://x/viewer.html")

(def ^:private idle-loading-done
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
  (when (exists? (.-act React)) (.-act React)))

(defn- enable-react-act-env! []
  (when (browser?)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)))

(defn- mount-node!
  "A sized host div (xyflow needs a non-zero parent box to lay out). The
  chart mounts INTO it, so the host is itself a WRAPPER around the chart
  root."
  []
  (let [el (.createElement js/document "div")]
    (set! (.. el -style -width) "800px")
    (set! (.. el -style -height) "600px")
    (.appendChild (.-body js/document) el)
    el))

(defn- with-mounted-chart
  "Mount `MachineChart` with `props` under act(), call `(f host-node)` with
  the HOST node (not the chart root), then unmount. Returns nil when no DOM /
  no act()."
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
  "The chart root element (carries the seam) under host `node`."
  [^js node testid]
  (.querySelector node (str "[data-testid=\"" testid "\"]")))

(defn- layout-settled?
  "True once xyflow has laid the chart out: at least one node wrapper exists
  and none is still `visibility:hidden` (xyflow hides every node until the
  async elkjs pass delivers positions, so an earlier raster is BLANK)."
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
  "Mount `MachineChart`, poll until the elkjs layout settles (or 8 s pass),
  then call `(f host-node finish)`; `finish` unmounts and removes the host,
  and the caller MUST call it. Calls `(skip)` when no DOM / no act()."
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
          ;; Each tick flushes React under act() so an elkjs-settle re-render
          ;; commits; on timeout `f` still runs, to fail rather than hang.
          (letfn [(tick []
                    (act-fn (fn [] nil))
                    (if (or (layout-settled? node) (> (js/Date.now) deadline))
                      (f node finish)
                      (js/setTimeout tick 50)))]
            (js/setTimeout tick 50)))))))

;; ---- SVG carries the node grammar + active-state affordance -------------

(deftest svg-includes-state-boxes-and-active-affordance
  (testing "chart-as-svg captures the FULL viewport in a foreignObject: the
            state-box text, the active `:loading` node's affordance attr and
            box-shadow glow, and the <title>/<desc> summary"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done
         :current-state :loading}
        (fn [node]
          (let [svg (export/chart-as-svg (chart-root-of node "rf-mv-chart"))]
            (is (str/includes? svg "foreignObject"))
            (is (str/includes? svg "loading"))
            (is (str/includes? svg "idle"))
            (is (str/includes? svg "data-active-affordance=\"true\""))
            (is (str/includes? svg "box-shadow"))
            (is (str/includes? svg "<desc>"))))))))

;; ---- PNG rasterises the full chart --------------------------------------

(defn- png-blob->image-data
  "Decode a PNG Blob into its raster; resolves to `{:non-transparent-px n}`,
  the count of pixels with a non-zero alpha."
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
                            non-transparent
                            (loop [i 3 acc 0]
                              (if (< i n)
                                (recur (+ i 4) (if (pos? (aget data i)) (inc acc) acc))
                                acc))]
                        (.revokeObjectURL js/URL url)
                        (resolve {:non-transparent-px non-transparent}))))
                  (catch :default e
                    (.revokeObjectURL js/URL url)
                    (reject e)))))
        (set! (.-onerror img)
              (fn [_]
                (.revokeObjectURL js/URL url)
                (reject (js/Error. "decode of exported PNG failed"))))
        (set! (.-src img) url)))))

(deftest png-rasterises-full-chart-with-content
  (testing "once the layout settles, chart-as-png! resolves to an image/png
            Blob whose raster has drawn content — the node boxes and glow,
            not a blank transparent edge layer"
    (async done
      (with-mounted-chart-after-layout
        {:machine-id :test/flow :definition idle-loading-done
         :current-state :loading}
        (fn [node finish]
          (is (layout-settled? node) "elkjs layout settled before capture")
          (-> (export/chart-as-png! (chart-root-of node "rf-mv-chart"))
              (.then
                (fn [blob]
                  (is (= "image/png" (.-type blob)))
                  (png-blob->image-data blob)))
              (.then
                (fn [{:keys [non-transparent-px]}]
                  (is (pos? non-transparent-px) "the raster drew content")))
              (.catch
                (fn [e]
                  (is false (str "chart-as-png! rejected: " e))
                  nil))
              ;; `finish` runs once on either path, ahead of the single `done`.
              (.then
                (fn [_]
                  (finish)
                  (done)))))
        (fn []
          (is true ":node-test: no DOM — browser-test runner exercises this")
          (done))))))

;; ---- export-root resolution is seam-keyed, not testid-keyed -------------

(deftest export-resolves-root-from-wrapper-and-custom-testid
  (testing "for a CUSTOM `:testid`, which a hard-coded default-testid selector
            would never find, export resolves the chart from the wrapper
            (descendant search), a descendant node (ancestor walk) and the
            root itself"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done
         :current-state :loading
         :testid "my-custom-chart"}
        (fn [node]
          (doseq [el [node
                      (.querySelector node "[data-testid^=\"rf-mv-chart-node-\"]")
                      (chart-root-of node "my-custom-chart")]]
            (is (str/includes? (export/chart-as-svg el) "loading"))))))))

;; ---- share-url off the live chart's seam --------------------------------

(deftest share-url-from-live-chart-seam
  (testing "the seam a mounted chart stamps carries its machine-id, definition
            and active state, so share-url off the live root round-trips them
            (the node-runtime tests drive a stub seam)"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/flow :definition idle-loading-done
         :current-state :loading}
        (fn [node]
          (is (= {:machine-id :test/flow
                  :definition idle-loading-done
                  :snapshot   {:state :loading}}
                 (:rf.machines-viz.share/chart
                   (share/decode-share-url
                     (export/share-url (chart-root-of node "rf-mv-chart")
                                       {:host test-host}))))))))))

;; ---- EP-0015 — the live Context band redacts before export --------------

(deftest svg-export-redacts-sensitive-live-context
  (testing "chart-as-svg of a chart fed a LIVE :context-band with a slot
            classified sensitive carries the :rf/redacted sentinel, never the
            raw secret, while a non-sensitive slot still renders"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (let [secret "card-4111-1111-1111-1111"]
        (with-mounted-chart
          {:machine-id :test/flow :definition idle-loading-done
           :current-state :loading
           :context-band {:card secret :count 7}
           :context-band-inferred? false
           :context-band-sensitive #{:card}}
          (fn [node]
            (let [svg (export/chart-as-svg (chart-root-of node "rf-mv-chart"))]
              (is (not (str/includes? svg secret)))
              (is (str/includes? svg ":rf/redacted"))
              (is (str/includes? svg "7")))))))))

(deftest svg-export-passes-live-context-when-raw-opted-in
  (testing ":context-band-raw? true is the explicit trusted-local opt-in: the
            raw value IS serialised"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (let [value "visible-debug-value"]
        (with-mounted-chart
          {:machine-id :test/flow :definition idle-loading-done
           :current-state :loading
           :context-band {:dbg value}
           :context-band-inferred? false
           :context-band-sensitive #{:dbg}
           :context-band-raw? true}
          (fn [node]
            (is (str/includes? (export/chart-as-svg (chart-root-of node "rf-mv-chart"))
                               value))))))))
