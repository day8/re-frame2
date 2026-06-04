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
  - **SVG** serialises the FULL rendered React Flow viewport — the
    boxed state nodes, compound/region containers, event chips, final
    rings, labels, AND the active-state border/glow affordance, plus
    the edge layer — by embedding the live viewport DOM in a
    `<foreignObject>` (rf2-sr6l3). The node bodies render with inline
    styles, so the foreignObject paints the same visual grammar a
    sighted user sees, not just the edge `<svg>`. Embedded fonts +
    `<title>` / `<desc>` summarise the machine for a screen-reader.
  - **PNG** rasterises that complete SVG to a 2x-DPR `image/png` Blob
    on a transparent background; a `text/plain` alt-text sidecar rides
    the clipboard alongside the image. Because the SVG now carries the
    whole viewport, the PNG includes the state boxes + the current-state
    highlight (the contract `API.md` §PNG promises).
  - **Share URL** is the full-topology lossless lane — see
    `day8.re-frame2-machines-viz.share`.

  ## Export-root discovery (rf2-sr6l3)

  The export-root lookup is keyed on the `_rfMvChartState` SEAM, not the
  default `data-testid`. `MachineChart` stamps the seam on its root via
  `:ref` regardless of the host's `:testid` prop, so export works from
  the chart root, a descendant node, OR a wrapping element, for the
  default AND any custom `:testid`. (The prior implementation hard-coded
  `[data-testid='rf-mv-chart']`, which broke export from a wrapper /
  descendant of a custom-`:testid` chart.)

  Per [`API.md`](../../spec/API.md) §Exporters."
  (:require [clojure.string :as str]
            [day8.re-frame2-machines-viz.mermaid :as mermaid]
            [day8.re-frame2-machines-viz.share :as share]))

;; ---------------------------------------------------------------------------
;; Reading the chart-state seam off the DOM
;;
;; rf2-sr6l3 — export-root discovery is SEAM-DRIVEN, not testid-driven.
;; `MachineChart` stamps `_rfMvChartState` on its root via `:ref` no
;; matter what `:testid` the host passed, so keying on the seam means
;; export resolves the same root for the default AND a custom `:testid`,
;; and whether the caller hands us the root, a descendant node, or a
;; wrapper. The former hard-coded `[data-testid='rf-mv-chart']` selector
;; missed a custom-testid chart entirely.

(defn- has-seam?
  "True when `el` is an element carrying the `_rfMvChartState` chart-state
  seam (a CLJS map, as the chart root's `:ref` stamps it)."
  [^js el]
  (and (some? el) (map? (.-_rfMvChartState el))))

(defn- ancestor-with-seam
  "Walk `el` and its ancestors (via `.-parentElement`) returning the
  first that carries the seam, or nil. Pure DOM walk — no selector — so
  a custom-`:testid` chart resolves identically to the default."
  [^js el]
  (loop [^js node el]
    (cond
      (nil? node)      nil
      (has-seam? node) node
      :else            (recur (.-parentElement node)))))

(defn- descendant-with-seam
  "Find the first descendant of `el` carrying the seam (the caller passed
  a WRAPPER around the chart). Seam-driven: queries broadly and keeps the
  first match whose `_rfMvChartState` is present, so a custom-`:testid`
  chart inside a wrapper is found. Returns nil when none is reachable."
  [^js el]
  (when (and el (.-querySelectorAll el))
    ;; `[role='application']` is the chart root's stable, testid-
    ;; independent attribute (set unconditionally in chart.cljs); it
    ;; narrows the scan to plausible chart roots before the seam check.
    ;; Falls back to `*` so a future chrome change can't strand the
    ;; lookup.
    (let [scan (fn [sel]
                 (let [^js nodes (.querySelectorAll el sel)
                       n         (.-length nodes)]
                   (loop [i 0]
                     (when (< i n)
                       (let [^js node (aget nodes i)]
                         (if (has-seam? node) node (recur (inc i))))))))]
      (or (scan "[role='application']")
          (scan "*")))))

(defn- chart-root
  "Resolve the `MachineChart` root element from `el` — `el` itself when
  it carries the `_rfMvChartState` seam, otherwise the nearest ancestor
  carrying it (caller passed a descendant), otherwise the first
  descendant carrying it (caller passed a wrapper). Returns nil when no
  chart root is reachable.

  rf2-sr6l3 — keyed on the SEAM, not the default `data-testid`, so it
  works for the default AND a custom `:testid`, from the root, a
  descendant, or a wrapper."
  [^js el]
  (when el
    (or (ancestor-with-seam el)
        (descendant-with-seam el))))

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
;;
;; rf2-sr6l3 — the SVG export captures the WHOLE rendered React Flow
;; viewport, not just the edge `<svg>`. The chart's visual grammar — the
;; boxed state nodes, compound/region containers, event chips, final
;; rings, labels, AND the active-state border/glow — renders as DOM
;; (div) nodes inside `.react-flow__viewport`, AROUND xyflow's edge
;; `svg.react-flow__edges`. The prior exporter cloned only that edge svg,
;; so every node body + the current-state highlight were lost.
;;
;; The fix embeds the live viewport DOM in a `<foreignObject>` inside a
;; standalone SVG. Two facts make this faithful AND self-contained:
;;
;;   1. Every node component (chart.nodes/*) renders with INLINE styles
;;      (`:style {...}` on each div — box geometry, border, header wash,
;;      box-shadow glow, fonts). Inline styles travel with the cloned
;;      HTML, so the foreignObject paints the same grammar the operator
;;      sees with no external stylesheet to embed.
;;   2. xyflow positions each `.react-flow__node` wrapper with an inline
;;      `transform: translate(Xpx,Ypx)` in FLOW coordinates and renders
;;      edges in the same flow space. We reset the cloned viewport's own
;;      pan/zoom transform to identity and size the SVG to the union of
;;      the node flow-boxes, so the export frames the WHOLE topology at
;;      1:1 regardless of the live zoom/pan.

(def ^:private viewport-css
  "The minimal xyflow positioning rules the cloned viewport needs, plus
  the embedded font + edge-path defaults — embedded as a `<style>` inside
  the export SVG so the `<foreignObject>` lays the chart out correctly.

  rf2-sr6l3 — xyflow positions the `.react-flow__node` wrappers via its
  EXTERNAL stylesheet (`@xyflow/react/dist/base.css` —
  `position:absolute; transform-origin:0 0`), NOT inline styles. The node
  BODIES carry inline styles (box, border, header wash, box-shadow glow),
  but without the wrapper positioning rules a cloned viewport collapses
  every node to the origin — and a foreignObject has no access to the
  page stylesheet, so the rules MUST travel inside the SVG. Mirrors the
  load-bearing subset of base.css (kept narrow + commented so a future
  xyflow bump is auditable):

    - `.react-flow__node` / `.react-flow__nodes` — absolute positioning +
      `transform-origin:0 0` so each wrapper's inline
      `transform:translate(x,y)` lands the node at its flow position.
    - `.react-flow__edges` (+ its `svg`) — absolute overflow-visible so
      the edge layer overlays the nodes in the same flow space.
    - `.react-flow__edge-path` / `text` — stroke/fill + font defaults so
      edges + edge labels paint (the node-body fonts are inline; the
      `text,foreignObject *` rule is a safe default for anything that
      isn't)."
  (str "<style>"
       "text,foreignObject *{"
       "font-family:'JetBrains Mono','SF Mono',Menlo,Consolas,monospace;}"
       ;; `.react-flow__container` (the class the viewport ALSO carries):
       ;; position:absolute + full-size. WITHOUT this the cloned viewport
       ;; has no box, so `.react-flow__nodes`/`.react-flow__edges`
       ;; (width/height:100%) collapse to 0×0 and the whole capture
       ;; rasterises BLANK (rf2-sr6l3 — the silent-blank root cause).
       ".react-flow__container{position:absolute;width:100%;height:100%;"
       "top:0;left:0;}"
       ".react-flow__node{position:absolute;transform-origin:0 0;"
       "box-sizing:border-box;}"
       ".react-flow__nodes{position:absolute;width:100%;height:100%;"
       "top:0;left:0;transform-origin:0 0;}"
       ".react-flow__edges{position:absolute;overflow:visible;}"
       ".react-flow__edges svg{overflow:visible;position:absolute;}"
       ".react-flow__edge-path{fill:none;}"
       ".react-flow__handle{position:absolute;}"
       "</style>"))

(def ^:private svg-padding
  "Margin (flow px) reserved around the union node-box so node borders /
  glow rings + edge arrowheads aren't clipped at the SVG edge."
  24)

(defn- find-edge-svg
  "Locate the rendered xyflow edge `<svg>` inside `root` (the
  `svg.react-flow__edges`, or the first `<svg>` as a fallback). Returns
  the SVG element or nil. Used only to detect that the chart has
  rendered (the EXPORT no longer clones this svg alone — see
  `chart-as-svg`)."
  [^js root]
  (when (and root (.-querySelector root))
    (or (.querySelector root "svg.react-flow__edges")
        (.querySelector root "svg"))))

(defn- find-viewport
  "Locate the `.react-flow__viewport` inside the chart root — the element
  xyflow pans/zooms, holding both the node-wrapper divs and the edge
  `<svg>` in flow coordinates. Returns nil when absent (e.g. the chart
  has not committed yet)."
  [^js root]
  (when (and root (.-querySelector root))
    (.querySelector root ".react-flow__viewport")))

(defn- node-flow-box
  "Read a `.react-flow__node` wrapper's FLOW-space box `{:x :y :w :h}`
  from its inline `transform: translate(Xpx,Ypx)` (xyflow's positioning)
  + its rendered `offsetWidth`/`offsetHeight`. Returns nil when the
  transform can't be parsed (degenerate / not yet positioned)."
  [^js wrapper]
  (let [transform (some-> wrapper .-style .-transform)
        m         (when (string? transform)
                    (re-find #"translate\(\s*(-?[\d.]+)px\s*,\s*(-?[\d.]+)px\s*\)"
                             transform))]
    (when m
      {:x (js/parseFloat (nth m 1))
       :y (js/parseFloat (nth m 2))
       :w (or (.-offsetWidth wrapper) 0)
       :h (or (.-offsetHeight wrapper) 0)})))

(defn- viewport-content-bounds
  "Union the flow-space boxes of every `.react-flow__node` wrapper under
  `viewport` into `{:min-x :min-y :width :height}` (flow px), padded by
  `svg-padding`. Returns a sensible default box when no node wrapper is
  measurable yet (so the export still frames the edge svg + degrades
  gracefully)."
  [^js viewport]
  (let [^js wrappers (when (.-querySelectorAll viewport)
                       (.querySelectorAll viewport ".react-flow__node"))
        n            (if wrappers (.-length wrappers) 0)
        boxes        (->> (range n)
                          (keep (fn [i] (node-flow-box (aget wrappers i))))
                          (filterv (fn [{:keys [w h]}] (and (pos? w) (pos? h)))))]
    (if (seq boxes)
      (let [min-x (reduce min (map :x boxes))
            min-y (reduce min (map :y boxes))
            max-x (reduce max (map (fn [{:keys [x w]}] (+ x w)) boxes))
            max-y (reduce max (map (fn [{:keys [y h]}] (+ y h)) boxes))]
        {:min-x  (- min-x svg-padding)
         :min-y  (- min-y svg-padding)
         :width  (+ (- max-x min-x) (* 2 svg-padding))
         :height (+ (- max-y min-y) (* 2 svg-padding))})
      {:min-x 0 :min-y 0 :width 800 :height 600})))

(defn- clone-viewport-html
  "Clone `viewport` deep, neutralise its pan/zoom transform (re-based to
  the content origin so the union box starts at (0,0) inside the
  foreignObject), and serialise it to a WELL-FORMED XML string via
  `XMLSerializer`. The node-wrapper transforms INSIDE the viewport stay
  intact (they carry each node's flow position); only the viewport's OWN
  transform is re-based. The clone's inline styles carry the full visual
  grammar.

  rf2-sr6l3 — serialised with `XMLSerializer`, NOT `.-outerHTML`. A
  `<foreignObject>` parses its contents as XML, and Chromium SILENTLY
  RENDERS THE FOREIGNOBJECT BLANK when that subtree isn't well-formed XML
  (HTML's `.outerHTML` can emit void elements unclosed + leave some
  attributes/entities un-escaped — invalid as XML). `XMLSerializer`
  emits conformant, self-closing, entity-escaped XML, and stamping the
  clone with the XHTML namespace makes the browser parse the embedded
  markup as HTML-equivalent content rather than opaque foreign XML.

  (The OTHER precondition for a non-blank raster is that the chart has
  actually LAID OUT: xyflow keeps every node `visibility:hidden` until
  the async elkjs pass delivers positions, so a capture taken before
  layout settles rasterises blank — call the exporters on a settled
  chart, which is the normal host path.)"
  [^js viewport {:keys [min-x min-y]}]
  (let [^js clone (.cloneNode viewport true)]
    (set! (.. clone -style -transform)
          (str "translate(" (- min-x) "px," (- min-y) "px) scale(1)"))
    (set! (.. clone -style -transformOrigin) "0 0")
    ;; Stamp the XHTML namespace so the foreignObject subtree is parsed as
    ;; HTML content (not opaque XML), and serialise to well-formed XML.
    (.setAttribute clone "xmlns" "http://www.w3.org/1999/xhtml")
    (.serializeToString (js/XMLSerializer.) clone)))

(defn chart-as-svg
  "Serialise the FULL rendered chart to an `image/svg+xml` string —
  boxed state nodes, compound/region containers, event chips, final
  rings, labels, the active-state border/glow, AND the edge layer — with
  embedded fonts + a `<title>` / `<desc>` machine summary.

  rf2-sr6l3 — captures the live `.react-flow__viewport` DOM inside a
  `<foreignObject>` (the visual grammar renders as inline-styled divs
  AROUND the edge `<svg>`, so cloning only that edge svg — the prior
  behaviour — lost every node body + the current-state highlight). The
  viewport's own pan/zoom is neutralised and the SVG is sized to the
  union node-box, so the export frames the whole topology at 1:1
  regardless of the live zoom.

  `chart-element` is the in-DOM element rendered by `MachineChart` (the
  root, a descendant, or a wrapper — the root is resolved off the
  `_rfMvChartState` seam). Returns the SVG string, or throws `:no-svg`
  when the chart has not rendered a viewport yet (empty-state
  placeholders render no chart)."
  [chart-element]
  (let [^js root (chart-root chart-element)
        cs       (chart-state-of chart-element)
        viewport (find-viewport root)]
    (when-not (and viewport (find-edge-svg root))
      (throw (ex-info ":rf.machines-viz.export/no-svg"
                      {:rf.error/id :rf.machines-viz.export/no-svg
                       :where       'machines-viz.export/chart-as-svg
                       :recovery    :no-recovery
                       :reason      "chart has no rendered viewport to serialise"})))
    (let [{:keys [width height] :as bounds} (viewport-content-bounds viewport)
          inner   (clone-viewport-html viewport bounds)
          summary (alt-text cs)
          title   (str "<title>" (name (or (:machine-id cs) :machine)) "</title>")
          desc    (str "<desc>" summary "</desc>")
          w       (max 1 (js/Math.ceil width))
          h       (max 1 (js/Math.ceil height))]
      ;; A standalone SVG framing the whole topology. The viewport HTML
      ;; lives in a `<foreignObject>` (xhtml namespace required so the
      ;; embedded markup renders as HTML, not opaque XML); inline styles
      ;; carry the node grammar + active-state affordance.
      (str "<svg xmlns=\"http://www.w3.org/2000/svg\" "
           "xmlns:xhtml=\"http://www.w3.org/1999/xhtml\" "
           "width=\"" w "\" height=\"" h "\" "
           "viewBox=\"0 0 " w " " h "\">"
           title desc viewport-css
           "<foreignObject x=\"0\" y=\"0\" width=\"" w "\" height=\"" h "\">"
           "<div xmlns=\"http://www.w3.org/1999/xhtml\" "
           "style=\"width:" w "px;height:" h "px;position:relative;overflow:hidden;\">"
           inner
           "</div>"
           "</foreignObject>"
           "</svg>"))))

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
  "Rasterise the FULL chart to an `image/png` Blob at 2x DPR on a
  transparent background. Returns a Promise resolving to the Blob.

  Pipeline: serialise the chart's complete SVG (the foreignObject-
  embedded viewport — `chart-as-svg`) → load it into an `<img>` via a
  data-URL → draw onto a 2x-DPR `<canvas>` → `canvas.toBlob`.

  rf2-sr6l3 — the SVG now carries the WHOLE rendered viewport (boxed
  state nodes, event chips, containers, labels) INCLUDING the
  current-state affordance (the active node's accent BORDER + box-shadow
  GLOW ring, rendered as inline-styled DOM), so the PNG includes the
  state boxes + the current-state highlight the API.md §PNG contract
  promises — not just the edge layer the prior implementation captured.
  The canvas size is the SVG's framed-topology box (the union node-box
  from the viewport, NOT the edge-svg bbox)."
  [chart-element]
  (let [svg-str  (chart-as-svg chart-element)
        ^js root (chart-root chart-element)
        viewport (find-viewport root)
        {:keys [width height]} (viewport-content-bounds viewport)
        w        (max 1 (js/Math.ceil width))
        h        (max 1 (js/Math.ceil height))
        dpr      2
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
