(ns day8.re-frame2-machines-viz.chart.controls
  "Interactive viewport controls for the MachineChart (rf2-y3l8z).

  ## What this owns

  The maths + the hiccup for the chart's zoom / pan / fit affordance.
  Three orthogonal surfaces ride this ns:

    1. **Reducer** — `apply-action` takes the current viewport
       `{:scale :tx :ty}` and an action map (`:zoom-in`,
       `:zoom-out`, `:zoom-at`, `:pan-by`, `:fit`, `:reset`) and
       returns the next viewport. Pure-data, JVM-runnable so the
       contract is testable from `clojure -M:test`.
    2. **Toolbar** — `toolbar` returns a hiccup `[:div ...]` with
       Zoom-in / Zoom-out / Fit / Reset buttons + a 'NN%' chip. The
       caller wires `:on-action` into a re-frame dispatch (or any
       handler).
    3. **Event-handler helpers** — `wheel->action`,
       `drag-state-zero`, `drag-step`, `key->action`. These take
       browser events / drag state and return the action map the
       reducer consumes — keeps the panel-side glue thin (the panel
       attaches `:on-wheel`, `:on-mouse-down`, `:on-key-down` and
       dispatches the returned action through re-frame).

  ## Why a reducer

  Putting the maths in a pure fn means tests assert behaviour on a
  data structure — `is (= {:scale 2.0 :tx 0 :ty 0}
  (apply-action {:scale 1 :tx 0 :ty 0} {:type :zoom-in}))` — no
  DOM, no rAF, no Reagent. The panel layer is a thin shell that
  reads the viewport slot, attaches event handlers that build
  action maps, and dispatches them.

  ## Stately / XState UX parity

    - Mouse-wheel zoom — `Cmd/Ctrl` not required; the wheel zooms
      towards the cursor point. Trackpad pinch arrives as a wheel
      with `ctrlKey=true` in browsers; we accept both.
    - Click-drag pan — anywhere on empty canvas. State nodes
      retain their own `:on-click` (the panel filters drag-start
      to events whose target is the SVG root, not a state node).
    - `Fit` resets to fit-all-nodes-in-viewport with reasonable
      padding.
    - `Reset zoom` returns to 100% at the chart's origin.
    - Keyboard shortcuts: `+` / `-` zoom about viewport centre;
      arrow keys pan; `0` reset; `f` fit.

  ## Reduced motion

  The toolbar buttons honour Causa's `--rf-causa-motion-scale` seam
  through the existing `tokens/duration-css` helper — the only
  motion here is the hover-fade on buttons + the 120ms ease on
  programmatic transform application (the chart `<g transform>` is
  written by SVG-native means; CSS transitions on the wrapper
  group express the smoothing)."
  (:require [day8.re-frame2-machines-viz.theme.tokens
             :as tokens
             :refer [tokens sans-stack]]))

;; ---- constants ----------------------------------------------------------

(def zoom-min
  "Lower bound on the zoom factor. 0.2 = 20% — the chart shrinks far
  enough to fit a 12-state machine in a tiny pane, but not so far
  that nodes vanish into noise."
  0.2)

(def zoom-max
  "Upper bound on the zoom factor. 4.0 = 400% — far enough to
  inspect a single state's tag-pills without the dot-grid pattern
  reading as a hatchwork."
  4.0)

(def zoom-step
  "Multiplicative step for the +/- buttons and keyboard shortcuts.
  1.2 = 20% increase per click — feels lively without overshooting."
  1.2)

(def wheel-zoom-step
  "Multiplicative step per wheel notch (deltaY = ±100 ≈ one notch).
  Smaller than the button step so wheel scrolling is finer-grained."
  1.1)

(def keyboard-pan-step
  "Pixels panned per arrow-key press. Matches Stately's 20px nudge."
  20)

(def fit-padding-px
  "Padding (in chart-world pixels) around the fit-to-viewport bbox so
  the outer nodes don't kiss the viewport edges."
  24)

;; ---- math helpers -------------------------------------------------------

(defn clamp-scale
  "Constrain `s` to the [zoom-min, zoom-max] range. Pure fn."
  [s]
  (cond
    (nil? s)           1
    (< s zoom-min)     zoom-min
    (> s zoom-max)     zoom-max
    :else              (double s)))

(defn identity-viewport
  "The unit viewport — scale 1, no pan. Equivalent to 'no transform
  applied'. Used as the default initial state and as the `:reset`
  target."
  []
  {:scale 1 :tx 0 :ty 0})

(defn zoom-about
  "Return a viewport that scales `current` by `factor` about the
  given (x,y) point in viewport / screen coordinates. The
  invariant: the point under (x,y) before the zoom remains under
  (x,y) after the zoom.

  Maths: the chart-world coord under the cursor is
    wx = (x - tx) / s
    wy = (y - ty) / s
  After the zoom the new transform must satisfy:
    x = wx * s' + tx'
    y = wy * s' + ty'
  Solve for tx', ty':
    tx' = x - wx * s'
    ty' = y - wy * s'"
  [{:keys [scale tx ty] :as _current} factor x y]
  (let [s    (or scale 1)
        s'   (clamp-scale (* s factor))
        ;; If clamping collapsed the change to a no-op skip the math
        ;; — keeps `:scale` exactly at the bound rather than drifting
        ;; from floating-point noise on repeated clamps.
        wx   (/ (- x (or tx 0)) s)
        wy   (/ (- y (or ty 0)) s)
        tx'  (- x (* wx s'))
        ty'  (- y (* wy s'))]
    {:scale s' :tx tx' :ty ty'}))

(defn fit-viewport
  "Compute the viewport that fits a graph of `content-width` ×
  `content-height` chart-world pixels into a viewport of
  `viewport-width` × `viewport-height` viewport pixels with
  `fit-padding-px` of padding on every side. Centres the content.

  Pure fn — no DOM, no React. Returns `{:scale :tx :ty}`.

  Edge cases:
    - Zero / negative content size → identity viewport.
    - Zero / negative viewport size → identity viewport (caller is
      mid-mount; render again next frame).
    - When the content fits naturally at 1.0 we clamp the scale to
      1.0 so the fit affordance also reads as 'recentre' — a
      Stately-parity behaviour."
  [{:keys [content-width content-height viewport-width viewport-height]}]
  (if (or (nil? content-width) (nil? content-height)
          (nil? viewport-width) (nil? viewport-height)
          (<= content-width 0) (<= content-height 0)
          (<= viewport-width 0) (<= viewport-height 0))
    (identity-viewport)
    (let [pad    (* 2 fit-padding-px)
          sx     (/ (max 1 (- viewport-width pad))
                    (double content-width))
          sy     (/ (max 1 (- viewport-height pad))
                    (double content-height))
          raw    (min sx sy)
          s      (clamp-scale raw)
          ;; Centre the content in the viewport at the chosen scale.
          tx     (/ (- viewport-width (* content-width s)) 2.0)
          ty     (/ (- viewport-height (* content-height s)) 2.0)]
      {:scale s :tx tx :ty ty})))

;; ---- reducer ------------------------------------------------------------

(defn apply-action
  "Reduce one action against the current viewport. Returns the next
  viewport. Pure fn — no side effects.

  Action shape:

    {:type :zoom-in}                  ; about viewport centre
    {:type :zoom-out}                 ; about viewport centre
    {:type :zoom-at :factor F         ; multiplicative factor
                    :x X :y Y}        ; viewport pixel
    {:type :pan-by :dx DX :dy DY}     ; viewport pixels
    {:type :fit
     :content-width CW :content-height CH
     :viewport-width VW :viewport-height VH}
    {:type :reset}

  When `current` is nil the identity viewport is used."
  [current {:keys [type x y dx dy factor
                   content-width content-height
                   viewport-width viewport-height]}]
  (let [cur (or current (identity-viewport))]
    (case type
      :zoom-in   (zoom-about cur zoom-step
                             (or x (/ (or viewport-width 600) 2.0))
                             (or y (/ (or viewport-height 400) 2.0)))
      :zoom-out  (zoom-about cur (/ 1.0 zoom-step)
                             (or x (/ (or viewport-width 600) 2.0))
                             (or y (/ (or viewport-height 400) 2.0)))
      :zoom-at   (zoom-about cur (or factor 1) x y)
      :pan-by    (-> cur
                     (update :tx (fnil + 0) (or dx 0))
                     (update :ty (fnil + 0) (or dy 0)))
      :fit       (fit-viewport {:content-width   content-width
                                :content-height  content-height
                                :viewport-width  viewport-width
                                :viewport-height viewport-height})
      :reset     (identity-viewport)
      cur)))

;; ---- event-handler helpers ----------------------------------------------

(defn wheel->action
  "Map a wheel event (deltaY) + cursor position to an action map.
  Returns nil when the wheel delta is too small to register (avoids
  hyperactive zoom on trackpads).

  Caller is responsible for `(.preventDefault e)` on the wheel
  event before invoking this — the wheel handler must opt out of
  page-scroll for the chart to feel native."
  [{:keys [delta-y x y]}]
  (when (and delta-y (not (zero? delta-y)))
    (let [factor (if (neg? delta-y)
                   wheel-zoom-step
                   (/ 1.0 wheel-zoom-step))]
      {:type   :zoom-at
       :factor factor
       :x      (or x 0)
       :y      (or y 0)})))

(defn drag-state-zero
  "Initial drag state. The panel layer mutates a slot (atom or app-db
  cell) with this on `:on-mouse-down` and clears it on
  `:on-mouse-up`. Carrying `:origin-x/:origin-y` + `:origin-viewport`
  lets `drag-step` compute the panned viewport in one fn rather than
  accumulating deltas across mousemove events (avoids drift)."
  [x y viewport]
  {:dragging?       true
   :origin-x        x
   :origin-y        y
   :origin-viewport (or viewport (identity-viewport))})

(defn drag-step
  "Given a `drag-state` produced by `drag-state-zero` + the current
  cursor (x, y), return the next viewport. When `drag-state` is nil
  or not in `:dragging?` state, returns nil (caller should ignore
  the mousemove)."
  [{:keys [dragging? origin-x origin-y origin-viewport]
    :as _drag-state} x y]
  (when (and dragging? origin-x origin-y origin-viewport)
    (let [dx (- x origin-x)
          dy (- y origin-y)]
      (-> origin-viewport
          (update :tx (fnil + 0) dx)
          (update :ty (fnil + 0) dy)))))

(defn key->action
  "Map a keyboard event's `:key` + `:viewport-width/:height` (for
  `:fit`) to an action map. Returns nil for keys we don't handle so
  the caller can `(when action ...)` it.

  Recognised keys:
    `+` / `=`        — zoom in
    `-` / `_`        — zoom out
    `0`              — reset
    `f` / `F`        — fit
    arrow keys       — pan by `keyboard-pan-step` pixels"
  [{:keys [key viewport-width viewport-height
           content-width content-height]}]
  (case key
    ("+" "=")             {:type :zoom-in
                           :viewport-width viewport-width
                           :viewport-height viewport-height}
    ("-" "_")             {:type :zoom-out
                           :viewport-width viewport-width
                           :viewport-height viewport-height}
    "0"                   {:type :reset}
    ("f" "F")             {:type            :fit
                           :viewport-width  viewport-width
                           :viewport-height viewport-height
                           :content-width   content-width
                           :content-height  content-height}
    "ArrowLeft"           {:type :pan-by :dx keyboard-pan-step :dy 0}
    "ArrowRight"          {:type :pan-by :dx (- keyboard-pan-step) :dy 0}
    "ArrowUp"             {:type :pan-by :dx 0 :dy keyboard-pan-step}
    "ArrowDown"           {:type :pan-by :dx 0 :dy (- keyboard-pan-step)}
    nil))

;; ---- toolbar hiccup -----------------------------------------------------

(defn- button-style
  []
  {:background    "transparent"
   :border        (str "1px solid " (:border-default tokens))
   :color         (:text-secondary tokens)
   :font-family   sans-stack
   :font-size     "11px"
   :font-weight   600
   :padding       "3px 8px"
   :border-radius "10px"
   :cursor        "pointer"
   :line-height   "1"
   :min-width     "26px"})

(defn- zoom-percent
  "Render a viewport scale as an integer percentage string. 1 → `100%`."
  [scale]
  (let [n (* (double (or scale 1)) 100.0)]
    (str (long #?(:clj  (Math/round n)
                  :cljs (.round js/Math n)))
         "%")))

(defn toolbar
  "Top-right toolbar with Zoom-out, Zoom-in, Fit and Reset buttons +
  a small NN% scale chip. Returns hiccup.

  Options:

    :viewport       — `{:scale :tx :ty}` current viewport state.
                      Drives the NN% chip + the disabled-state on
                      the buttons (a button at the zoom bound stays
                      clickable but the bound clamps the change to
                      a no-op — visual feedback is the chip).
    :on-action      — `(fn [action-map] ...)` invoked with the
                      action the user just requested. Caller maps
                      the action to a re-frame dispatch.
    :testid-prefix  — overrides the root `data-testid` and seeds
                      child testids (default `rf-mv-chart-controls`).
    :compact?       — when true the buttons shrink to icon-only
                      (the Fit/Reset glyphs alone). For the panel
                      header where horizontal real estate is tight.

  Pure hiccup, no Reagent / UIx / Helix — same contract as the
  rest of the chart."
  [{:keys [viewport on-action testid-prefix compact?]
    :or   {testid-prefix "rf-mv-chart-controls"}}]
  (let [vp     (or viewport (identity-viewport))
        scale  (:scale vp)
        emit   (fn [action]
                 (fn [e]
                   #?(:cljs (when e (.preventDefault ^js e)))
                   (when on-action (on-action action))))]
    [:div {:data-testid testid-prefix
           :data-viewport-scale (str scale)
           :style {:display       "inline-flex"
                   :align-items   "center"
                   :gap           "6px"
                   :padding       "4px 6px"
                   :background    (:bg-3 tokens)
                   :border        (str "1px solid " (:border-subtle tokens))
                   :border-radius "12px"}}
     [:button
      {:data-testid (str testid-prefix "-zoom-out")
       :aria-label  "Zoom out"
       :title       "Zoom out (−)"
       :on-click    (emit {:type :zoom-out})
       :style       (button-style)}
      "−"]
     [:span {:data-testid (str testid-prefix "-scale-chip")
             :title       "Current zoom level"
             :style {:font-family sans-stack
                     :font-size   "10px"
                     :color       (:text-tertiary tokens)
                     :min-width   "34px"
                     :text-align  "center"
                     :user-select "none"}}
      (zoom-percent scale)]
     [:button
      {:data-testid (str testid-prefix "-zoom-in")
       :aria-label  "Zoom in"
       :title       "Zoom in (+)"
       :on-click    (emit {:type :zoom-in})
       :style       (button-style)}
      "+"]
     [:button
      {:data-testid (str testid-prefix "-fit")
       :aria-label  "Fit to viewport"
       :title       "Fit to viewport (f)"
       :on-click    (emit {:type :fit-request})
       :style       (assoc (button-style)
                      :padding (if compact? "3px 8px" "3px 10px"))}
      (if compact? "⛶" "Fit")]
     [:button
      {:data-testid (str testid-prefix "-reset")
       :aria-label  "Reset zoom"
       :title       "Reset zoom (0)"
       :on-click    (emit {:type :reset})
       :style       (assoc (button-style)
                      :padding (if compact? "3px 8px" "3px 10px"))}
      (if compact? "⌖" "Reset")]]))
