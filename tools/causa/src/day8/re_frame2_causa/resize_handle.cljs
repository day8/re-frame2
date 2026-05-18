(ns day8.re-frame2-causa.resize-handle
  "Left-edge horizontal resize handle for the Causa shell (rf2-x8h9y).

  ## What this is

  A 6px-wide vertical strip pinned to the LEFT edge of the shell in
  `:right-rail` (`:inline`) mode. The user drags it to widen / narrow
  the panel; the live width persists through the Settings round-trip
  and survives reload via localStorage. Double-click resets to
  `config/default-panel-width-px` (560px).

  ## Drag mechanics (global mouse capture)

  `onMouseDown` records the starting mouse-X + starting panel width,
  then attaches `mousemove` + `mouseup` listeners on
  `js/document` (NOT the handle element). Document-level capture is
  the standard devtool pattern — without it, drags faster than the
  pointer-event cadence escape the handle's hit-test box and the
  drag stalls. The handle's own `mousemove` only fires while the
  cursor is over the 6px strip; the document's fires everywhere.

  The same pattern is used by every browser's split-pane resizer,
  Chrome devtools, VS Code's panel boundary, etc. — it's the
  reference UX.

  ## Width derivation

  The panel currently lives to the RIGHT of the host app (per
  `config/default-layout-host-snippet`'s flex layout — `<main>`
  first, `<aside data-rf-causa-host>` second). Dragging the handle
  to the LEFT widens the panel; dragging RIGHT narrows it. So the
  delta is `(start-x - current-x)`:

      width = start-width + (start-x - current-x)

  Clamp at write-time via the registry's
  `:rf.causa/set-panel-width-px` handler (which reaches
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
            [day8.re-frame2-causa.theme.tokens :refer [tokens]]))

;; ---- drag state ---------------------------------------------------------

(defonce ^:private drag-state
  ;; Holds the active drag's snapshot:
  ;;   {:start-x       <Number>   ; pageX at mousedown
  ;;    :start-width   <Number>   ; px at mousedown (from sub)
  ;;    :on-move       <fn>       ; bound document handler (for cleanup)
  ;;    :on-up         <fn>       ; bound document handler (for cleanup)
  ;;    :prev-cursor   <string>}  ; body cursor pre-drag (for restore)
  ;; nil when no drag is in progress. defonce so a shadow-cljs
  ;; :after-load mid-drag doesn't strand a half-installed listener
  ;; (the next mousedown re-installs cleanly).
  (atom nil))

(defn dragging?
  "Test seam — true iff a drag is in progress. Pure read of the
  drag-state atom. Used by `shell_resize_handle_cljs_test` to assert
  the start/stop lifecycle."
  []
  (some? @drag-state))

(defn- detach-document-listeners! []
  (when-let [{:keys [on-move on-up prev-cursor]} @drag-state]
    (when (and (exists? js/document) (.-removeEventListener js/document))
      (try (.removeEventListener js/document "mousemove" on-move)
           (catch :default _ nil))
      (try (.removeEventListener js/document "mouseup" on-up)
           (catch :default _ nil)))
    ;; Restore the body cursor so the col-resize override doesn't
    ;; linger after mouseup (the document drag overrides whatever
    ;; the cursor was hovering, so we need to restore explicitly).
    (when (and (exists? js/document) (.-body js/document))
      (set! (-> js/document .-body .-style .-cursor)
            (or prev-cursor "")))
    (reset! drag-state nil)))

(defn- on-document-move [^js e]
  (when-let [{:keys [start-x start-width]} @drag-state]
    ;; Page-relative X so a drag that moves through a parent with
    ;; CSS transforms still tracks the pointer.
    (let [dx        (- start-x (.-pageX e))
          new-width (+ start-width dx)]
      (rf/dispatch [:rf.causa/set-panel-width-px new-width]
                   {:frame :rf/causa}))))

(defn- on-document-up [^js _e]
  (detach-document-listeners!))

(defn start-drag!
  "Capture the drag start position + width snapshot, then attach the
  document-level move + up listeners that drive the live update.
  Pre-existing drag state is torn down first so a stuck listener
  from a failed prior drag doesn't double-fire.

  Exposed for the shell view's `:on-mouse-down` handler AND for the
  test suite, which drives the drag lifecycle without a real DOM."
  [^js e current-width]
  ;; Defensive: clear any stale state from a prior aborted drag.
  (detach-document-listeners!)
  (let [start-x     (.-pageX e)
        prev-cursor (when (and (exists? js/document)
                               (.-body js/document))
                      (-> js/document .-body .-style .-cursor))
        on-move     on-document-move
        on-up       on-document-up]
    (reset! drag-state {:start-x     start-x
                        :start-width (or current-width 560)
                        :on-move     on-move
                        :on-up       on-up
                        :prev-cursor prev-cursor})
    ;; Override the body cursor so the col-resize indicator persists
    ;; through the drag even when the cursor moves off the 6px handle
    ;; (which it will the moment the panel resizes wider than the
    ;; original pointer position).
    (when (and (exists? js/document) (.-body js/document))
      (set! (-> js/document .-body .-style .-cursor) "col-resize"))
    (when (and (exists? js/document) (.-addEventListener js/document))
      (try (.addEventListener js/document "mousemove" on-move)
           (catch :default _ nil))
      (try (.addEventListener js/document "mouseup" on-up)
           (catch :default _ nil)))
    ;; preventDefault swallows the native text-selection that begins
    ;; on mousedown over a non-input element — without it a drag
    ;; visually selects every piece of text the cursor crosses.
    (try (.preventDefault e) (catch :default _ nil))))

;; Test seam — directly drive the document-level handlers without
;; needing a real DOM event listener attached. The view's
;; `start-drag!` installs the handlers AND captures the snapshot;
;; tests can replicate that by calling start-drag! with a stub event
;; and then driving simulate-move! / simulate-up! to step the
;; mouse without touching `js/document.dispatchEvent`.
(defn simulate-move!
  "Test-only: drive the document-level mousemove handler with a
  page-X coordinate. No-op when no drag is in progress."
  [page-x]
  (when @drag-state
    (on-document-move #js {:pageX page-x})))

(defn simulate-up!
  "Test-only: drive the document-level mouseup handler. No-op when no
  drag is in progress."
  []
  (when @drag-state
    (on-document-up nil)))

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
  ;; continuous even when the pointer leaves the strip.
  {:position         "absolute"
   :left             0
   :top              0
   :bottom           0
   :width            (str base-width-px "px")
   :cursor           "col-resize"
   :background       "transparent"
   ;; Hairline guide on the leftmost pixel so the affordance is
   ;; visible against any host-app background.
   :box-shadow       (str "inset 1px 0 0 0 " (:accent-violet tokens) "55")
   ;; Above the chrome's panels but below the modals; modals use
   ;; max-int z-index so we won't fight them.
   :z-index          1000
   ;; Touch / pointer hints — disable selection so a drag does not
   ;; lasso text in adjacent panels.
   :touch-action     "none"
   :user-select      "none"})

(rf/reg-view Handle
  "Render the resize handle when `mode` is `:inline` (the right-rail
  default). Other modes render nil — `:popout` is a separate window
  the user resizes via the OS, `:fullscreen` is full-viewport and
  has no width to drag.

  `reg-view`-wrapped per rf2-in6l2 so the inner subscribe routes
  through React-context to `:rf/causa` (the shell wraps the whole
  chrome in `frame-provider {:frame :rf/causa}`)."
  [mode]
  (when (= mode :inline)
    (let [current-width @(rf/subscribe [:rf.causa/panel-width-px])]
      [:div {:data-testid     "rf-causa-resize-handle"
             :role            "separator"
             :aria-orientation "vertical"
             :aria-label      "Resize Causa panel"
             :title           "Drag to resize · double-click to reset"
             :on-mouse-down   (fn [^js e]
                                (start-drag! e current-width))
             :on-double-click (fn [^js _e]
                                (rf/dispatch
                                  [:rf.causa/reset-panel-width]
                                  {:frame :rf/causa}))
             :style           (handle-style)}])))
