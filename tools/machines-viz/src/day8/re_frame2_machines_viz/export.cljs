(ns day8.re-frame2-machines-viz.export
  "Client-side exporters for a rendered `MachineChart` (rf2-8d7w1 ·
  v1.0). PNG / SVG rasterisers, the Mermaid markdown wrapper, and the
  share-URL convenience — plus the four `copy-*-to-clipboard!` fns.

  ## How the payload is derived

  Every fn takes the in-DOM `chart-element` rendered by `MachineChart`
  (the root `<div data-testid=\"rf-mv-chart\">`, or any element inside
  it — the fns walk up to the chart root). `MachineChart` stashes a
  share-relevant `chart-state` on that root node as the JS property
  `._rfMvChartState` (set via the component's root `:ref`): topology +
  the active-state CONFIGURATION (its name/address) + summary counts.
  The export fns read that seam
  rather than scraping the SVG for structure — so the share-URL +
  alt-text summaries are derived from the SAME definition the chart
  rendered, with no runtime `:data` ever in scope (per
  `Principles.md` §No session data in shares).

  ## What is and isn't lossy

  - **Mermaid** is the markdown-paste lane — lossy by design (`:after`
    rings + `:spawn-all` rows omitted; flagged inline). Delegates to
    `day8.re-frame2-machines-viz.mermaid/emit`.
  - **SVG** serialises the live rendered xyflow `<svg>` with embedded
    `<title>` / `<desc>` summarising the machine, so a screen-reader
    pasting the artefact gets the same overview a sighted user has.
  - **PNG** rasterises that SVG to a 2x-DPR `image/png` Blob on a
    transparent background; a `text/plain` alt-text sidecar rides the
    clipboard alongside the image.
  - **Share URL** is the full-topology lossless lane — see
    `day8.re-frame2-machines-viz.share`.

  Per [`API.md`](../../spec/API.md) §Exporters."
  (:require [clojure.string :as str]
            [day8.re-frame2-machines-viz.mermaid :as mermaid]
            [day8.re-frame2-machines-viz.share :as share]))

;; ---------------------------------------------------------------------------
;; Reading the chart-state seam off the DOM

(def ^:private chart-testid "rf-mv-chart")

(defn- chart-root
  "Resolve the `MachineChart` root element from `el` — `el` itself when
  it carries the `_rfMvChartState` seam, otherwise the nearest
  ancestor / descendant that does. Returns nil when no chart root is
  reachable."
  [^js el]
  (when el
    (cond
      (some? (.-_rfMvChartState el)) el
      ;; ancestor walk (caller passed a child node)
      (and (.-closest el)
           (.closest el (str "[data-testid='" chart-testid "']"))
           (some? (.-_rfMvChartState
                    ^js (.closest el (str "[data-testid='" chart-testid "']")))))
      (.closest el (str "[data-testid='" chart-testid "']"))
      ;; descendant lookup (caller passed a wrapper)
      (and (.-querySelector el)
           (.querySelector el (str "[data-testid='" chart-testid "']")))
      (.querySelector el (str "[data-testid='" chart-testid "']"))
      :else nil)))

(defn- chart-state-of
  "Read the `chart-state` seam off `chart-element` as a CLJS map:
  `{:machine-id :definition :current-state :node-count :edge-count
  :region-count}`. The chart's root `:ref` stores the CLJS map directly
  as the `_rfMvChartState` JS property (not a #js object — dashed
  keyword keys would munge). Throws when the element carries no chart
  state (a programmer error — the export was called on a non-chart
  element)."
  [chart-element]
  (let [^js root (chart-root chart-element)
        seam (some-> root .-_rfMvChartState)]
    (when-not (map? seam)
      (throw (ex-info ":rf.machines-viz.export/no-chart-state"
                      {:rf.error/id :rf.machines-viz.export/no-chart-state
                       :where       'machines-viz.export
                       :recovery    :no-recovery
                       :reason      "chart-element carries no MachineChart state seam"
                       :element     chart-element})))
    seam))

(defn- state-label
  "Render a `:current-state` value — any of the three Spec 005
  §Snapshot-shape arms — to a human-readable label for alt-text:

  - flat keyword `:loading`           → `\"loading\"`
  - compound path `[:auth :authing]`  → `\"auth.authing\"`
  - parallel region-map               → `\"data=loading, form=neutral\"`

  Guards against `(name …)` blowing up on the vector/map arms (the seam
  now legitimately carries compound + parallel configurations)."
  [state]
  (cond
    (keyword? state) (name state)
    (vector? state)  (str/join "." (map #(if (keyword? %) (name %) (str %)) state))
    (map? state)     (str/join ", "
                               (map (fn [[region region-state]]
                                      (str (name region) "=" (state-label region-state)))
                                    state))
    :else            (str state)))

(defn- alt-text
  "A one-line machine summary used as the SVG `<desc>`, the PNG
  clipboard alt-text sidecar, and the share preview. Same content
  across PNG + SVG so the accessible overview matches (per
  `API.md` §Accessibility). `:current-state` may be any of the three
  Spec 005 §Snapshot-shape arms — `state-label` renders all three."
  [{:keys [machine-id current-state node-count edge-count region-count]}]
  (str "State machine"
       (when machine-id (str " " (name machine-id)))
       (when current-state (str " — currently " (state-label current-state)))
       ": " node-count " " (if (= 1 node-count) "state" "states")
       ", " edge-count " " (if (= 1 edge-count) "transition" "transitions")
       (when (and region-count (pos? region-count))
         (str " across " region-count " parallel "
              (if (= 1 region-count) "region" "regions")))
       "."))

;; ---------------------------------------------------------------------------
;; SVG

(def ^:private svg-font-face
  "Embedded font-face so the SVG renders with the same mono typography
  when pasted into a doc or Figma frame (per API.md §SVG — fonts are
  embedded). Uses a `local()` src so the artefact stays small; falls
  back to the platform mono stack."
  "<style>text{font-family:'JetBrains Mono','SF Mono',Menlo,Consolas,monospace;}</style>")

(defn- find-svg
  "Locate the rendered xyflow `<svg>` inside `root`. xyflow renders the
  edges into an `svg.react-flow__edges`; we serialise the full chart by
  cloning the chart root's first `<svg>`. Returns the SVG element or
  nil."
  [root]
  (when (and root (.-querySelector root))
    (.querySelector root "svg")))

(defn chart-as-svg
  "Serialise the rendered chart to an `image/svg+xml` string with
  embedded fonts + a `<title>` / `<desc>` machine summary.

  `chart-element` is the in-DOM element rendered by `MachineChart`.
  Returns the SVG string, or throws `:no-svg` when the chart has not
  rendered an `<svg>` yet (empty-state placeholders render no SVG)."
  [chart-element]
  (let [root (chart-root chart-element)
        cs   (chart-state-of chart-element)
        svg  (find-svg root)]
    (when-not svg
      (throw (ex-info ":rf.machines-viz.export/no-svg"
                      {:rf.error/id :rf.machines-viz.export/no-svg
                       :where       'machines-viz.export/chart-as-svg
                       :recovery    :no-recovery
                       :reason      "chart has no rendered <svg> to serialise"})))
    (let [clone   (.cloneNode svg true)
          summary (alt-text cs)
          title   (str "<title>" (name (or (:machine-id cs) :machine)) "</title>")
          desc    (str "<desc>" summary "</desc>")
          ;; Ensure the xmlns is present so the standalone string renders.
          _       (.setAttribute clone "xmlns" "http://www.w3.org/2000/svg")
          markup  (.-outerHTML clone)
          ;; Inject <title>/<desc>/<style> right after the opening <svg ...>.
          insert-at (inc (or (str/index-of markup ">") 0))]
      (str (subs markup 0 insert-at)
           title desc svg-font-face
           (subs markup insert-at)))))

(defn copy-svg-to-clipboard!
  "Write the chart's SVG to the clipboard as `image/svg+xml`. Returns a
  Promise resolving when the write completes (or rejecting on failure /
  when the clipboard API is unavailable)."
  [chart-element]
  (let [svg-str (chart-as-svg chart-element)]
    (if (and js/navigator (.-clipboard js/navigator) js/ClipboardItem)
      (let [blob (js/Blob. #js [svg-str] #js {:type "image/svg+xml"})
            item (js/ClipboardItem. #js {"image/svg+xml" blob})]
        (.write (.-clipboard js/navigator) #js [item]))
      (js/Promise.reject
        (ex-info "clipboard API unavailable"
                 {:rf.error/id :rf.machines-viz.export/no-clipboard})))))

;; ---------------------------------------------------------------------------
;; PNG

(defn chart-as-png!
  "Rasterise the chart to an `image/png` Blob at 2x DPR on a
  transparent background. Returns a Promise resolving to the Blob.

  Pipeline: serialise the chart's SVG → load it into an `<img>` via a
  data-URL → draw onto a 2x-DPR `<canvas>` → `canvas.toBlob`. The
  current-state affordance lives on the node-box BORDER + box-shadow
  ring (xyflow div-nodes), not on the SVG; the serialised SVG carries
  the rendered edges (incl. fired/focused edge styling), so the PNG
  frames the topology + edge highlights as rendered."
  [chart-element]
  (let [svg-str (chart-as-svg chart-element)
        root    (chart-root chart-element)
        svg     (find-svg root)
        bbox    (when (.-getBoundingClientRect svg)
                  (.getBoundingClientRect svg))
        w       (max 1 (js/Math.ceil (or (some-> bbox .-width) 800)))
        h       (max 1 (js/Math.ceil (or (some-> bbox .-height) 600)))
        dpr     2
        data-url (str "data:image/svg+xml;charset=utf-8,"
                      (js/encodeURIComponent svg-str))]
    (js/Promise.
      (fn [resolve reject]
        (let [img (js/Image.)]
          (set! (.-onload img)
                (fn []
                  (try
                    (let [canvas (.createElement js/document "canvas")]
                      (set! (.-width canvas) (* w dpr))
                      (set! (.-height canvas) (* h dpr))
                      (let [ctx (.getContext canvas "2d")]
                        (.scale ctx dpr dpr)
                        (.drawImage ctx img 0 0 w h)
                        (.toBlob canvas
                                 (fn [blob]
                                   (if blob
                                     (resolve blob)
                                     (reject (ex-info "canvas.toBlob returned nil"
                                                      {:rf.error/id :rf.machines-viz.export/png-failed}))))
                                 "image/png")))
                    (catch :default e (reject e)))))
          (set! (.-onerror img)
                (fn [_] (reject (ex-info "SVG image failed to load"
                                         {:rf.error/id :rf.machines-viz.export/png-failed}))))
          (set! (.-src img) data-url))))))

(defn copy-png-to-clipboard!
  "Write the chart's PNG to the clipboard, with a `text/plain`
  alt-text sidecar carrying the machine summary (per API.md
  §Accessibility). Returns a Promise."
  [chart-element]
  (let [cs  (chart-state-of chart-element)
        alt (alt-text cs)]
    (-> (chart-as-png! chart-element)
        (.then
          (fn [png-blob]
            (if (and js/navigator (.-clipboard js/navigator) js/ClipboardItem)
              (let [alt-blob (js/Blob. #js [alt] #js {:type "text/plain"})
                    item (js/ClipboardItem. #js {"image/png"  png-blob
                                                 "text/plain" alt-blob})]
                (.write (.-clipboard js/navigator) #js [item]))
              (js/Promise.reject
                (ex-info "clipboard API unavailable"
                         {:rf.error/id :rf.machines-viz.export/no-clipboard}))))))))

;; ---------------------------------------------------------------------------
;; Mermaid

(defn chart-as-mermaid
  "Pull the `definition` off `chart-element` and emit a fenced Mermaid
  `stateDiagram-v2` markdown block (per API.md §Mermaid). Convenience
  wrapper over `day8.re-frame2-machines-viz.mermaid/emit`; pass `opts` straight
  through (`{:fenced? false :header-comment? false}` etc.)."
  ([chart-element] (chart-as-mermaid chart-element nil))
  ([chart-element opts]
   (let [{:keys [definition]} (chart-state-of chart-element)]
     (if opts
       (mermaid/emit definition opts)
       (mermaid/emit definition)))))

(defn copy-mermaid-to-clipboard!
  "Write the chart's fenced Mermaid markdown block to the clipboard as
  `text/plain` — paste into a GitHub README / PR / Notion and it
  renders inline. Returns a Promise."
  [chart-element]
  (let [md (chart-as-mermaid chart-element)]
    (if (and js/navigator (.-clipboard js/navigator))
      (.writeText (.-clipboard js/navigator) md)
      (js/Promise.reject
        (ex-info "clipboard API unavailable"
                 {:rf.error/id :rf.machines-viz.export/no-clipboard})))))

;; ---------------------------------------------------------------------------
;; Share URL

(defn- element->chart-state
  "Project the DOM seam into the `ChartState` shape `share/encode-share-url`
  accepts. The `:frame-id` is not on the seam (the chart is
  presentation-only and doesn't know its frame); when absent we pass
  `:frame-id` as the machine-id so the encoder's schema is satisfied.
  Hosts that know the frame should call `share/encode-share-url` directly
  with the full ChartState.

  `:current-state` off the seam is the chart's whole `:state` value — a
  flat keyword, a compound vector-path, or a parallel region-map (the
  three Spec 005 §Snapshot-shape arms). It is threaded verbatim into
  `:snapshot {:state …}`; the share schema accepts all three arms (they
  are state names/addresses, not runtime data), so a compound or
  parallel chart shares + round-trips cleanly."
  [chart-element {:keys [frame-id]}]
  (let [{:keys [machine-id definition current-state]} (chart-state-of chart-element)]
    (cond-> {:machine-id machine-id
             :frame-id   (or frame-id machine-id)
             :definition definition}
      current-state (assoc :snapshot {:state current-state}))))

(defn share-url
  "Derive a share-URL from the rendered `chart-element`. Reads the
  topology + active-state configuration off the DOM seam, projects them
  into a `ChartState`, and delegates to `share/encode-share-url`.

  `opts` is passed to `share/encode-share-url` (`{:host ...}`), and may
  also carry `:frame-id` (the chart element doesn't know its frame; a
  host that does can supply it for payload provenance). Returns the URL
  string."
  ([chart-element] (share-url chart-element nil))
  ([chart-element opts]
   (let [chart-state (element->chart-state chart-element opts)]
     (share/encode-share-url chart-state (select-keys opts [:host])))))

(defn copy-share-url-to-clipboard!
  "Write the chart's share-URL to the clipboard as `text/plain`.
  Returns a Promise."
  ([chart-element] (copy-share-url-to-clipboard! chart-element nil))
  ([chart-element opts]
   (let [url (share-url chart-element opts)]
     (if (and js/navigator (.-clipboard js/navigator))
       (.writeText (.-clipboard js/navigator) url)
       (js/Promise.reject
         (ex-info "clipboard API unavailable"
                  {:rf.error/id :rf.machines-viz.export/no-clipboard}))))))
