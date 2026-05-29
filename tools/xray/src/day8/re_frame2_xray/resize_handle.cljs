(ns day8.re-frame2-xray.resize-handle
  "Resize affordances for the Xray shell: the LEFT-edge panel-width
  `Handle` (rf2-x8h9y + rf2-70u8q auto-inject contract) and the
  L2/L3 seam `SeamHandle` (rf2-t2dsh) that drives the event-list
  height.

  ## Two handles, one mental model

  Both handles mirror the same drag mechanics (document-level
  pointer capture, unified mouse / touch / pen via pointer events,
  body-cursor override during drag), the same UX (drag + double-
  click reset + keyboard arrows + Home/End clamps), and the same
  persistence (dispatch through a `:rf.xray/set-*` event that
  clamps at write-time + round-trips through the Settings map +
  localStorage). The only axis difference is the cursor (`col-resize`
  vs `row-resize`) and which CSS variable / inline style consumes
  the persisted value.

  ## What `Handle` is

  A 6px-wide vertical strip pinned to the LEFT edge of the shell in
  `:right-rail` (`:inline`) mode. The user drags it (mouse, touch, or
  pen — all unified through pointer events) to widen / narrow the
  panel; the live width persists through the Settings round-trip and
  survives reload via localStorage. Double-click resets to
  `config/default-panel-width-px` (560px). Keyboard-navigable via
  arrow keys (8px step; 32px with Shift) and Home / End (clamp ends).

  ## What `SeamHandle` is

  A full-width 8px-tall horizontal strip mounted between the L2 event
  list and the L3 tab bar in `dynamic-chrome`. Drag DOWN grows the
  list; drag UP shrinks it. Live height persists through the Settings
  map under `:general :events-list-height-px`. Double-click resets to
  `config/default-events-list-height-px` (200px). Keyboard surface
  mirrors the panel-width handle except for axis (`ArrowDown` /
  `ArrowUp` instead of left/right). The handle REPLACES the previous
  browser-native `:resize \"vertical\"` corner-grip on the L2 list,
  which had no persistence, no keyboard surface, and a tiny corner
  hit-target.

  ## Auto-inject contract (rf2-70u8q)

  Per spec/007-UX-IA.md §Resize affordance, Xray ships the handle as
  a zero-config affordance: the consumer drops in
  `<aside data-rf-xray-host></aside>` and the handle appears
  automatically as a child of the auto-mounted shell. Consumers MUST
  NOT need to wire `resize: horizontal` / `overflow: auto` on the
  host — those properties create a duplicate browser-native handle
  that conflicts with Xray's clamp + persist + reset semantics.

  ### Yield-to-consumer

  If the layout host carries an explicit `resize: horizontal` (or
  `:both`) in its *computed* style, Xray interprets that as the
  consumer asserting their own handle and renders nil from `Handle`
  — no double-handle, the consumer wins. Detection happens at render
  time via `getComputedStyle` on the host element.

  ## Drag mechanics (global pointer capture)

  `onPointerDown` records the starting pointer-X + starting panel
  width, then attaches `pointermove` + `pointerup` + `pointercancel`
  listeners on `js/document` (NOT the handle element). Document-level
  capture is the standard devtool pattern — without it, drags faster
  than the pointer-event cadence escape the handle's hit-test box
  and the drag stalls. The handle's own pointer events only fire
  while the cursor / finger is over the 6px strip; the document's
  fire everywhere.

  The same pattern is used by every browser's split-pane resizer,
  Chrome devtools, VS Code's panel boundary, etc. — it's the
  reference UX. Pointer events unify mouse + touch + pen so the
  same handler covers laptop trackpads, desktop mice, and tablet
  / phone touch screens with no branching.

  ## Width derivation

  The panel currently lives to the RIGHT of the host app (per
  `config/default-layout-host-snippet`'s flex layout — `<main>`
  first, `<aside data-rf-xray-host>` second). Dragging the handle
  to the LEFT widens the panel; dragging RIGHT narrows it. So the
  delta is `(start-x - current-x)`:

      width = start-width + (start-x - current-x)

  Clamp at write-time via the registry's
  `:rf.xray/set-panel-width-px` handler (which reaches
  `config/clamp-panel-width-px`); the handle's UI math is naive
  delta — the source of truth for the clamp lives in one place.

  ## Coexistence with `:panel-position`

  - `:right-rail` (default) — handle on LEFT edge of panel.
    Rendered.
  - `:popout` — separate window; browser-native window resize.
    NOT rendered (the inline shell is hidden anyway).
  - `:fullscreen` — full viewport overlay; resize is N/A.
    NOT rendered.

  The shell-view passes `mode` through; `Handle` renders nil for
  any non-`:inline` mode.

  ## Pure hiccup discipline (rf2-tijr)

  Per shell.cljs §Pure hiccup the view code is plain hiccup with
  no Reagent imports. Hover-state visual feedback would require a
  Reagent ratom or a re-frame dispatch on every mouse-enter/leave
  (cheap, but pollutes the trace); we ship without hover-state
  highlight — the always-visible 1px accent stripe + cursor change
  on hover (via inline `:cursor`) carry the affordance. While
  dragging the body's cursor is overridden to `col-resize`
  globally so the user reads the operation as continuous."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.theme.tokens :refer [with-alpha]]))

;; ---- keyboard step constants -------------------------------------------

(def ^:private keyboard-step-px
  "Fine-step width in pixels for a single arrow-key press. 8px is the
  conventional Chrome-devtools / VS-Code increment — small enough to
  land on a target column, large enough that users don't need to hold
  the key for minutes to traverse the panel range. The Shift modifier
  multiplies this by `keyboard-coarse-multiplier` for faster traversal."
  8)

(def ^:private keyboard-coarse-multiplier
  "Multiplier for Shift+arrow on the handle: 8px × 4 = 32px per press.
  Matches the coarse step in spec/007-UX-IA.md §Keyboard."
  4)

;; ---- drag state ---------------------------------------------------------

(defonce ^:private drag-state
  ;; Holds the active drag's snapshot:
  ;;   {:start-x       <Number>   ; pageX at pointerdown
  ;;    :start-width   <Number>   ; px at pointerdown (from sub)
  ;;    :pointer-id    <Number>   ; pointerdown's pointerId (for capture release)
  ;;    :on-move       <fn>       ; bound document handler (for cleanup)
  ;;    :on-up         <fn>       ; bound document handler (for cleanup)
  ;;    :on-cancel     <fn>       ; bound document handler (for cleanup)
  ;;    :prev-cursor   <string>}  ; body cursor pre-drag (for restore)
  ;; nil when no drag is in progress. defonce so a shadow-cljs
  ;; :after-load mid-drag doesn't strand a half-installed listener
  ;; (the next pointerdown re-installs cleanly).
  (atom nil))

(defn dragging?
  "Test seam — true iff a drag is in progress. Pure read of the
  drag-state atom. Used by `shell_resize_handle_cljs_test` to assert
  the start/stop lifecycle."
  []
  (some? @drag-state))

(defn- detach-document-listeners! []
  (when-let [{:keys [on-move on-up on-cancel prev-cursor]} @drag-state]
    (when (and (exists? js/document) (.-removeEventListener js/document))
      (try (.removeEventListener js/document "pointermove" on-move)
           (catch :default _ nil))
      (try (.removeEventListener js/document "pointerup" on-up)
           (catch :default _ nil))
      (try (.removeEventListener js/document "pointercancel" on-cancel)
           (catch :default _ nil)))
    ;; Restore the body cursor so the col-resize override doesn't
    ;; linger after pointerup (the document drag overrides whatever
    ;; the cursor was hovering, so we need to restore explicitly).
    (when (and (exists? js/document) (.-body js/document))
      (set! (-> js/document .-body .-style .-cursor)
            (or prev-cursor "")))
    (reset! drag-state nil)))

(defn- on-document-move [^js e]
  (when-let [{:keys [start-x start-width dispatch-fn]} @drag-state]
    ;; Page-relative X so a drag that moves through a parent with
    ;; CSS transforms still tracks the pointer.
    (let [dx        (- start-x (.-pageX e))
          new-width (+ start-width dx)]
      ;; rf2-nesy9 — the document-level pointer-move listener fires
      ;; OUTSIDE the React render scope; dispatch through the frame-aware
      ;; `dispatch-fn` captured at `start-drag!` so the resize lands on
      ;; the surrounding instance frame, not a `{:frame :rf/xray}` literal.
      ((or dispatch-fn rf/dispatch) [:rf.xray/set-panel-width-px new-width]))))

(defn- on-document-up [^js _e]
  (detach-document-listeners!))

(defn- on-document-cancel [^js _e]
  ;; pointercancel fires when the browser preempts the gesture
  ;; (e.g. system gesture overrides on mobile, OS dragging UX).
  ;; Tear down the same way as pointerup so we don't strand listeners.
  (detach-document-listeners!))

(defn start-drag!
  "Capture the drag start position + width snapshot, then attach the
  document-level move + up + cancel listeners that drive the live
  update. Pre-existing drag state is torn down first so a stuck
  listener from a failed prior drag doesn't double-fire.

  Accepts mouse, touch, or pen pointer events uniformly — the pointer
  events spec defines `pageX` on every type, so the same handler
  works without branching.

  `dispatch-fn` (rf2-nesy9) is the frame-aware dispatcher captured by
  the `Handle` `reg-view` body; it is stashed in the drag-state so the
  document-level pointer-move listener (which fires after render
  unwinds) dispatches on the surrounding instance frame. Defaults to
  `rf/dispatch` for the test seam, which drives the lifecycle without a
  real DOM.

  Exposed for the shell view's `:on-pointer-down` handler AND for the
  test suite, which drives the drag lifecycle without a real DOM."
  ([^js e current-width] (start-drag! e current-width rf/dispatch))
  ([^js e current-width dispatch-fn]
  ;; Defensive: clear any stale state from a prior aborted drag.
  (detach-document-listeners!)
  (let [start-x     (.-pageX e)
        pointer-id  (.-pointerId e)
        prev-cursor (when (and (exists? js/document)
                               (.-body js/document))
                      (-> js/document .-body .-style .-cursor))
        on-move     on-document-move
        on-up       on-document-up
        on-cancel   on-document-cancel]
    (reset! drag-state {:start-x     start-x
                        :start-width (or current-width 560)
                        :pointer-id  pointer-id
                        :dispatch-fn dispatch-fn
                        :on-move     on-move
                        :on-up       on-up
                        :on-cancel   on-cancel
                        :prev-cursor prev-cursor})
    ;; Override the body cursor so the col-resize indicator persists
    ;; through the drag even when the cursor moves off the 6px handle
    ;; (which it will the moment the panel resizes wider than the
    ;; original pointer position).
    (when (and (exists? js/document) (.-body js/document))
      (set! (-> js/document .-body .-style .-cursor) "col-resize"))
    (when (and (exists? js/document) (.-addEventListener js/document))
      (try (.addEventListener js/document "pointermove" on-move)
           (catch :default _ nil))
      (try (.addEventListener js/document "pointerup" on-up)
           (catch :default _ nil))
      (try (.addEventListener js/document "pointercancel" on-cancel)
           (catch :default _ nil)))
    ;; preventDefault swallows the native text-selection / scroll
    ;; gesture that begins on pointerdown over a non-input element
    ;; — without it a drag visually selects every piece of text the
    ;; cursor crosses (mouse) or scrolls the page (touch).
    (try (.preventDefault e) (catch :default _ nil)))))

;; Test seam — directly drive the document-level handlers without
;; needing a real DOM event listener attached. The view's
;; `start-drag!` installs the handlers AND captures the snapshot;
;; tests can replicate that by calling start-drag! with a stub event
;; and then driving simulate-move! / simulate-up! to step the
;; pointer without touching `js/document.dispatchEvent`.
(defn simulate-move!
  "Test-only: drive the document-level pointermove handler with a
  page-X coordinate. No-op when no drag is in progress."
  [page-x]
  (when @drag-state
    (on-document-move #js {:pageX page-x})))

(defn simulate-up!
  "Test-only: drive the document-level pointerup handler. No-op when no
  drag is in progress."
  []
  (when @drag-state
    (on-document-up nil)))

(defn simulate-cancel!
  "Test-only: drive the document-level pointercancel handler. No-op
  when no drag is in progress. Covers the system-preempt path
  (e.g. browser-level gesture override on mobile)."
  []
  (when @drag-state
    (on-document-cancel nil)))

;; ---- keyboard navigation (rf2-70u8q a11y) -------------------------------

(defn handle-keydown!
  "Keyboard-navigable resize. Per spec/007-UX-IA.md §Resize affordance
  the handle MUST be operable without a pointer device. Bindings:

    ArrowLeft           +8px  (widen — drag-left semantics)
    ArrowRight          -8px  (narrow)
    Shift+ArrowLeft     +32px (coarse widen)
    Shift+ArrowRight    -32px (coarse narrow)
    Home                +very-large step (clamp to upper bound)
    End                 -very-large step (clamp to lower bound)
    Enter / Space       reset to default (matches double-click)

  The clamp lives in the registry handler — we dispatch the desired
  width and let `:rf.xray/set-panel-width-px` apply the [320px, 90vw]
  bounds. Out-of-range dispatches simply snap to the edge, so Home/End
  use the viewport width as a generous overshoot rather than reading
  the live viewport.

  Returns true iff the keypress was handled (so the caller can
  `preventDefault` to suppress the default behavior — page scroll on
  arrow keys, button activation on space). Other keypresses return
  false and bubble normally.

  `dispatch-fn` (rf2-nesy9) is the frame-aware dispatcher captured by
  the `Handle` `reg-view` body so the keyboard resize lands on the
  surrounding instance frame, not a `{:frame :rf/xray}` literal.
  Defaults to `rf/dispatch` for the test seam."
  ([^js e current-width] (handle-keydown! e current-width rf/dispatch))
  ([^js e current-width dispatch-fn]
  (let [key      (.-key e)
        shift?   (.-shiftKey e)
        step     (if shift?
                   (* keyboard-step-px keyboard-coarse-multiplier)
                   keyboard-step-px)
        dispatch (fn [px]
                   (dispatch-fn [:rf.xray/set-panel-width-px px]))]
    (case key
      "ArrowLeft"   (do (dispatch (+ current-width step)) true)
      "ArrowRight"  (do (dispatch (- current-width step)) true)
      ;; Home / End use a generous overshoot — the registry clamp
      ;; will snap to the actual bounds. 10000px is larger than any
      ;; sensible viewport, ensuring "Home" reaches the upper clamp
      ;; without needing a fresh viewport read here.
      "Home"        (do (dispatch 10000) true)
      "End"         (do (dispatch 0) true)
      ("Enter" " ") (do (dispatch-fn [:rf.xray/reset-panel-width])
                        true)
      false))))

;; ---- yield-to-consumer detection (rf2-70u8q) ----------------------------

(defn host-asserts-own-handle?
  "Probe the layout-host element's computed style — return true iff the
  consumer has explicitly set `resize: horizontal` (or `resize: both`),
  signalling they want the browser-native handle and Xray should
  yield. Per spec/007-UX-IA.md §Resize affordance the auto-inject
  contract is: silent yield when the consumer asserts their own
  affordance.

  Returns false:
    - in non-browser runtimes (no `js/window` / `js/document`)
    - when the host element is not on the page yet
    - when the host's computed `resize` value is `:none` (the
      default) or `:vertical`

  The probe queries `getComputedStyle` so author CSS, inline styles,
  inherited values, and `:where(...)`-wrapped rules all surface
  through one consistent read. This is the canonical 'is the consumer
  opting out of our default?' detection — no configuration knob, no
  preload-time inspection, no caching: re-evaluated on every render
  so a runtime CSS swap (devtools edit, theme switch) updates the
  yield decision on the next paint."
  []
  (boolean
    (when (and (exists? js/window)
               (exists? js/document)
               (.-querySelector js/document)
               (.-getComputedStyle js/window))
      (when-let [host (.querySelector js/document
                                      (config/get-layout-host-selector))]
        (let [cs     (.getComputedStyle js/window host)
              resize (when cs (.getPropertyValue cs "resize"))]
          (contains? #{"horizontal" "both"} resize))))))

;; ---- view ---------------------------------------------------------------

(def ^:private base-width-px 6)

(defn- handle-style []
  ;; Left-edge stripe. `position: absolute; left: 0; top: 0;
  ;; bottom: 0` pins the handle to the left edge of the shell-view's
  ;; outer flex container (which is `position: relative` in `:inline`
  ;; mode per shell.cljs §inline). The strip is 6px wide; a 1px
  ;; accent stripe inset on the left edge sits at all times so the
  ;; affordance is always visible (per pure-hiccup discipline we
  ;; can't pseudo-class :hover; the always-visible accent + the
  ;; col-resize cursor are the affordance). During drag the body
  ;; cursor flips to col-resize globally so the operation reads as
  ;; continuous.
  {:position         "absolute"
   :left             0
   :top              0
   :bottom           0
   :width            (str base-width-px "px")
   :cursor           "col-resize"
   :background       "transparent"
   ;; Hairline guide on the leftmost pixel so the affordance is
   ;; visible against any host-app background. `~33% alpha` of the
   ;; single `:accent` (GitHub blue; rf2-ad7zx.13). Built via
   ;; `tokens/with-alpha` so the alpha composites with the ACTIVE-theme
   ;; variable (rf2-on4cm) — light/dark both land on the live accent at
   ;; 33% alpha.
   :box-shadow       (str "inset 1px 0 0 0 "
                          (with-alpha :accent 33))
   ;; Above the chrome's panels but below the modals; modals use
   ;; max-int z-index so we won't fight them.
   :z-index          1000
   ;; Touch / pointer hints — disable native gestures (page scroll on
   ;; touch, text selection on mouse) so a drag does not pan the page
   ;; or lasso text in adjacent panels.
   :touch-action     "none"
   :user-select      "none"})

(rf/reg-view Handle
  "Render the resize handle when `mode` is `:inline` (the right-rail
  default). Other modes render nil — `:popout` is a separate window
  the user resizes via the OS, `:fullscreen` is full-viewport and
  has no width to drag.

  Per rf2-70u8q yields silently to consumer-asserted browser-native
  resize: if the layout host's computed style declares
  `resize: horizontal` (or `:both`), the consumer has chosen to wire
  their own handle — render nil so the page does not carry two.

  `reg-view`-wrapped per rf2-in6l2 so the inner subscribe routes
  through React-context to `:rf/xray` (the shell wraps the whole
  chrome in `frame-provider {:frame :rf/xray}`)."
  [mode]
  (when (and (= mode :inline)
             (not (host-asserts-own-handle?)))
    (let [current-width @(rf/subscribe [:rf.xray/panel-width-px])
          ;; rf2-vxpq1 — WAI-ARIA "separator" with `aria-valuenow`
          ;; requires both `aria-valuemin` AND `aria-valuemax`. The
          ;; clamp ceiling is viewport-fraction-based at write time
          ;; (config/max-panel-width-fraction); for the announced
          ;; ARIA max we use a stable pixel approximation derived
          ;; from the window's inner width when available, falling
          ;; back to a 2000px assumed viewport (the same default the
          ;; clamp uses when no viewport is supplied). Computing once
          ;; per render is fine — the value is read on focus, not
          ;; every paint.
          viewport-w     (when (exists? js/window)
                           (.-innerWidth js/window))
          aria-max       (long (* (or viewport-w 2000)
                                  config/max-panel-width-fraction))]
      [:div {:data-testid      "rf-xray-resize-handle"
             :role             "separator"
             :aria-orientation "vertical"
             :aria-label       "Resize Xray panel"
             :aria-valuemin    config/min-panel-width-px
             :aria-valuemax    aria-max
             :aria-valuenow    current-width
             :title            (str "Drag to resize · double-click to reset · "
                                    "arrow keys for fine resize (Shift = coarse) · "
                                    "Home / End for ends · Enter to reset")
             :tab-index        0
             :on-pointer-down  (fn [^js e]
                                 (start-drag! e current-width dispatch))
             :on-key-down      (fn [^js e]
                                 (when (handle-keydown! e current-width dispatch)
                                   (try (.preventDefault e)
                                        (catch :default _ nil))))
             :on-double-click  (fn [^js _e]
                                 (dispatch
                                   [:rf.xray/reset-panel-width]))
             :style            (handle-style)}])))

;; ==========================================================================
;; L2/L3 seam handle (rf2-t2dsh)
;; ==========================================================================
;;
;; The horizontal seam between the L2 event list and the L3 tab bar is a
;; row-resize affordance: hover anywhere along the seam and the cursor
;; flips to `row-resize`; click-and-drag changes the event-list height.
;; Mirrors the LEFT-edge panel-width handle's interaction model (drag,
;; double-click to reset, keyboard arrows for fine step) so all resize
;; surfaces in Xray read as one mental model.
;;
;; Per rf2-t2dsh this REPLACES the previous browser-native
;; `:resize \"vertical\"` corner-grip on the L2 list — that affordance
;; had no persistence, no keyboard surface, and a tiny corner hit-target.
;; The seam runs the full width of the panel so the click-area is the
;; whole horizontal seam, not a single 16px corner.
;;
;; Mechanics mirror `start-drag!` above (document-level pointer capture,
;; pointer-events unification of mouse/touch/pen, body cursor override
;; while dragging). The dispatch surface is
;; `:rf.xray/set-events-list-height-px` which clamps at write-time per
;; `config/clamp-events-list-height-px`.

(defonce ^:private seam-drag-state
  ;; Holds the active seam-drag snapshot. Same shape as `drag-state`
  ;; above but with `:start-y` + `:start-height` for the vertical axis.
  ;; defonce so a shadow-cljs :after-load mid-drag doesn't strand a
  ;; half-installed listener.
  (atom nil))

(defn seam-dragging?
  "Test seam — true iff an L2/L3 seam drag is in progress. Pure read
  of the seam-drag-state atom. Used by the test suite to assert the
  start/stop lifecycle."
  []
  (some? @seam-drag-state))

(defn- seam-detach-document-listeners! []
  (when-let [{:keys [on-move on-up on-cancel prev-cursor]} @seam-drag-state]
    (when (and (exists? js/document) (.-removeEventListener js/document))
      (try (.removeEventListener js/document "pointermove" on-move)
           (catch :default _ nil))
      (try (.removeEventListener js/document "pointerup" on-up)
           (catch :default _ nil))
      (try (.removeEventListener js/document "pointercancel" on-cancel)
           (catch :default _ nil)))
    (when (and (exists? js/document) (.-body js/document))
      (set! (-> js/document .-body .-style .-cursor)
            (or prev-cursor "")))
    (reset! seam-drag-state nil)))

(defn- seam-on-document-move [^js e]
  (when-let [{:keys [start-y start-height dispatch-fn]} @seam-drag-state]
    ;; The seam sits BELOW the event-list and ABOVE the tab-bar. Drag
    ;; DOWN (now-y > start-y) grows the list; drag UP shrinks it.
    ;; dy = (now-y - start-y).
    (let [dy         (- (.-pageY e) start-y)
          new-height (+ start-height dy)]
      ;; rf2-nesy9 — dispatch through the frame-aware `dispatch-fn`
      ;; captured at `start-seam-drag!` (the document-level listener fires
      ;; outside the React render scope), not a `{:frame :rf/xray}` literal.
      ((or dispatch-fn rf/dispatch) [:rf.xray/set-events-list-height-px new-height]))))

(defn- seam-on-document-up [^js _e]
  (seam-detach-document-listeners!))

(defn- seam-on-document-cancel [^js _e]
  (seam-detach-document-listeners!))

(defn start-seam-drag!
  "Begin an L2/L3 seam drag. Records the start-y + start-height
  snapshot, then attaches the document-level move / up / cancel
  listeners that drive the live update. Mirrors `start-drag!` above
  for the vertical axis.

  `dispatch-fn` (rf2-nesy9) is the frame-aware dispatcher captured by
  the `SeamHandle` `reg-view` body; stashed in the seam-drag-state so
  the document-level pointer-move listener dispatches on the
  surrounding instance frame. Defaults to `rf/dispatch` for the test
  seam.

  Exposed for the seam view's `:on-pointer-down` handler AND for the
  test suite, which drives the lifecycle without a real DOM."
  ([^js e current-height] (start-seam-drag! e current-height rf/dispatch))
  ([^js e current-height dispatch-fn]
  (seam-detach-document-listeners!)
  (let [start-y     (.-pageY e)
        pointer-id  (.-pointerId e)
        prev-cursor (when (and (exists? js/document)
                               (.-body js/document))
                      (-> js/document .-body .-style .-cursor))
        on-move     seam-on-document-move
        on-up       seam-on-document-up
        on-cancel   seam-on-document-cancel]
    (reset! seam-drag-state {:start-y      start-y
                             :start-height (or current-height
                                               config/default-events-list-height-px)
                             :pointer-id   pointer-id
                             :dispatch-fn  dispatch-fn
                             :on-move      on-move
                             :on-up        on-up
                             :on-cancel    on-cancel
                             :prev-cursor  prev-cursor})
    (when (and (exists? js/document) (.-body js/document))
      (set! (-> js/document .-body .-style .-cursor) "row-resize"))
    (when (and (exists? js/document) (.-addEventListener js/document))
      (try (.addEventListener js/document "pointermove" on-move)
           (catch :default _ nil))
      (try (.addEventListener js/document "pointerup" on-up)
           (catch :default _ nil))
      (try (.addEventListener js/document "pointercancel" on-cancel)
           (catch :default _ nil)))
    (try (.preventDefault e) (catch :default _ nil)))))

(defn seam-simulate-move!
  "Test-only: drive the document-level pointermove handler for the
  seam drag with a page-Y coordinate. No-op when no drag is in progress."
  [page-y]
  (when @seam-drag-state
    (seam-on-document-move #js {:pageY page-y})))

(defn seam-simulate-up!
  "Test-only: drive the document-level pointerup handler for the seam
  drag. No-op when no drag is in progress."
  []
  (when @seam-drag-state
    (seam-on-document-up nil)))

(defn seam-simulate-cancel!
  "Test-only: drive the document-level pointercancel handler for the
  seam drag. Covers the system-preempt path."
  []
  (when @seam-drag-state
    (seam-on-document-cancel nil)))

(defn handle-seam-keydown!
  "Keyboard-navigable seam resize. Per spec/007-UX-IA.md §Splitter
  affordance the seam handle MUST be operable without a pointer
  device. Bindings:

    ArrowDown          +8px  (grow list — drag-down semantics)
    ArrowUp            -8px  (shrink)
    Shift+ArrowDown    +32px (coarse grow)
    Shift+ArrowUp      -32px (coarse shrink)
    Home               +very-large step (clamp to upper bound)
    End                -very-large step (clamp to lower bound)
    Enter / Space      reset to default (matches double-click)

  Mirrors the panel resize handle's keyboard contract — clamp lives in
  the registry handler so we dispatch the desired height and let
  `:rf.xray/set-events-list-height-px` apply the [min, viewport×0.7]
  bounds.

  Returns true iff the keypress was handled (so the caller can
  `preventDefault` to suppress the default behavior — page scroll on
  arrow keys, button activation on space). Other keypresses return
  false and bubble normally.

  `dispatch-fn` (rf2-nesy9) is the frame-aware dispatcher captured by
  the `SeamHandle` `reg-view` body. Defaults to `rf/dispatch` for the
  test seam."
  ([^js e current-height] (handle-seam-keydown! e current-height rf/dispatch))
  ([^js e current-height dispatch-fn]
  (let [key      (.-key e)
        shift?   (.-shiftKey e)
        step     (if shift?
                   (* config/events-list-height-keyboard-step-px
                      config/events-list-height-keyboard-coarse-multiplier)
                   config/events-list-height-keyboard-step-px)
        dispatch (fn [px]
                   (dispatch-fn [:rf.xray/set-events-list-height-px px]))]
    (case key
      "ArrowDown"   (do (dispatch (+ current-height step)) true)
      "ArrowUp"     (do (dispatch (- current-height step)) true)
      "Home"        (do (dispatch 10000) true)
      "End"         (do (dispatch 0) true)
      ("Enter" " ") (do (dispatch-fn [:rf.xray/reset-events-list-height])
                        true)
      false))))

(def ^:private seam-base-height-px
  "Click-area height for the L2/L3 seam handle (rf2-t2dsh). 8px is the
  conventional comfortable hit-target for a horizontal splitter while
  remaining a thin visual line. The always-visible hairline accent is
  1px at 33% alpha; the click-area covers ±3px on either side so the
  pointer doesn't need pixel-perfect aim. Mirrors the panel-width
  handle's 6px strip — slightly taller because the surrounding chrome
  is denser horizontally than vertically."
  8)

(defn- seam-handle-style
  "Inline style for the L2/L3 seam-handle node. The handle is a
  full-width 8px horizontal strip carrying `row-resize` cursor + an
  always-visible 1px accent stripe at 33% alpha along the seam line.
  The strip's hover treatment lives in `theme/global-styles/motion-css`
  (the rule intensifies the stripe on hover; inline styles cannot
  carry `:hover` pseudo-classes). Mirrors `handle-style` above for the
  vertical axis."
  []
  {:flex            "0 0 auto"
   :width           "100%"
   :height          (str seam-base-height-px "px")
   :margin          "0"
   :padding         "0"
   :cursor          "row-resize"
   :background      "transparent"
   ;; Hairline on the seam centre so the affordance is visible against
   ;; either theme. 33% alpha of the single `:accent` (GitHub blue) —
   ;; same intensity as the panel-width handle's hairline so the two
   ;; affordances read as one family.
   :box-shadow      (str "inset 0 4px 0 -3px " (with-alpha :accent 33))
   ;; Disable native gestures during drag (text-select on mouse,
   ;; page-pan on touch).
   :touch-action    "none"
   :user-select     "none"
   ;; The seam is part of the L2 surface — give it a hairline footer
   ;; that matches the list's border-bottom so the visual rhythm is
   ;; preserved (the L2 list lost its own border-bottom when the seam
   ;; was extracted; the seam IS the boundary).
   :position        "relative"})

(rf/reg-view SeamHandle
  "Render the horizontal seam between the L2 event list and the L3 tab
  bar as a draggable resize affordance (rf2-t2dsh). Always renders —
  every mode that mounts the L2 list (Dynamic chrome only; Static mode
  doesn't carry L2) renders the seam.

  Per spec/007-UX-IA.md §Splitter affordance:
    - hover anywhere along the seam → `row-resize` cursor
    - click-and-drag from anywhere along the seam → resizes the list
    - 8px click-area, hairline accent that intensifies on hover
    - keyboard-navigable (arrow keys for fine step, Enter/Space to
      reset)
    - double-click resets to default

  `reg-view`-wrapped per rf2-in6l2 so the inner subscribe routes
  through React-context to `:rf/xray`."
  []
  (let [current-height @(rf/subscribe [:rf.xray/events-list-height-px])
        viewport-h     (when (exists? js/window)
                         (.-innerHeight js/window))
        aria-max       (long (* (or viewport-h 1000)
                                config/max-events-list-height-fraction))]
    [:div {:data-testid      "rf-xray-event-list-seam"
           :role             "separator"
           :aria-orientation "horizontal"
           :aria-label       "Resize events list"
           :aria-valuemin    config/min-events-list-height-px
           :aria-valuemax    aria-max
           :aria-valuenow    current-height
           :title            (str "Drag to resize events list · "
                                  "double-click to reset · "
                                  "arrow keys for fine resize (Shift = coarse) · "
                                  "Enter to reset")
           :tab-index        0
           :on-pointer-down  (fn [^js e]
                               (start-seam-drag! e current-height dispatch))
           :on-key-down      (fn [^js e]
                               (when (handle-seam-keydown! e current-height dispatch)
                                 (try (.preventDefault e)
                                      (catch :default _ nil))))
           :on-double-click  (fn [^js _e]
                               (dispatch
                                 [:rf.xray/reset-events-list-height]))
           :style            (seam-handle-style)}]))
