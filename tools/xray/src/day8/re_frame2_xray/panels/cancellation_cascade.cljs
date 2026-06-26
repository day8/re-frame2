(ns day8.re-frame2-xray.panels.cancellation-cascade
  "Cancellation-cascade visualiser (rf2-59e7k, parent rf2-5aw5v).

  Per `tools/xray/spec/019-Cross-Cutting-Insight.md` §M.3 / the
  cancellation-cascade section: when a parent machine decision triggers
  a destroy, every in-flight effect the child held aborts (per Spec
  014 §Abort on actor destroy / rf2-wvkn). Today those traces scatter
  through the Trace tab as a flurry; this visualiser folds them into
  ONE vertical waterfall:

      PARENT DECISION
        └─ [destroy-child] dispatched at 1234ms by :auth/logout
      CHILD TEARDOWN
        └─ child:user-session destroying at 1240ms (5 in-flight fxs)
      EFFECT ABORTS (in order)
        ├─ HTTP GET /api/profile  aborted at 1242ms (:actor-destroyed)
        ├─ HTTP POST /api/log     aborted at 1242ms (:actor-destroyed)
        ├─ WS send :heartbeat     aborted at 1243ms (:actor-destroyed)
        ├─ :after timer fire      aborted at 1243ms (:actor-destroyed)
        └─ machine-invoke fetch   aborted at 1244ms (:actor-destroyed)

  Each row is clickable → jumps to the underlying trace entry via the
  spine shim (`:rf.xray/select-dispatch-id`).

  ## Two mounts

    1. **Machines tab side-panel** — when the focused machine had a
       cancellation-anchor in the trace window, the visualiser mounts
       inline beside the chart. Reads
       `:rf.xray/cancellation-cascade-for-focused-machine`.

    2. **Popover** — opened from anywhere via
       `:rf.xray/cancellation-cascade-open` (e.g. a Trace row's
       'Show cancellation cascade' affordance). Reads
       `:rf.xray/cancellation-cascade-for-focused-event` (composes
       popover-focus → spine focus).

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Xray panel — no Reagent / UIx /
  Helix references. Frame isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/xray}]` in `shell.cljs`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panels.cancellation-cascade-events :as events]
            [day8.re-frame2-xray.panels.cancellation-cascade-helpers :as h]
            [day8.re-frame2-xray.panels.cancellation-cascade-subs :as subs]
            [day8.re-frame2-xray.theme.modal-chrome :as modal-chrome]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack type-scale]]))

;; ---- row styling primitives ----------------------------------------------
;;
;; All inline `:style {...}` maps in the row renderers below are hoisted to
;; ns-level defs (rf2-qx414, audit F2 of rf2-qa75r). The cascade body can
;; render up to ~50 abort rows × ~5 cells each — without hoisting, each
;; re-render allocated ~250 fresh JS objects to feed the React reconciler.
;; The stable bits live as plain maps; per-row variation (cursor on the
;; outer container, kind-colour on the glyph + category label) is layered
;; in via `assoc`. Tokens resolve to `var(--rf-xray-*)` strings at ns load,
;; so the hoisted maps follow the active theme without re-evaluation.

(def ^:private row-glyph
  {:parent-decision "●"
   :child-teardown  "└─"
   :effect-abort    "├─"
   :effect-abort-last "└─"})

;; Kind-colour resolution per the bead's contract: decision = blue,
;; teardown = orange, abort = red. Pre-resolved at ns load (`tokens`
;; values are CSS-var strings — the active theme class picks the hex
;; at paint time, so resolution-once is correct across light/dark).
(def ^:private decision-colour (:info tokens))
(def ^:private teardown-colour (or (:accent-orange tokens)
                                   (:warning-amber tokens)
                                   (:yellow tokens)))
(def ^:private abort-colour    (or (:red tokens)
                                   (:error-red tokens)
                                   (:danger tokens)))

;; Outer flex container — shared chrome, padding diverges per row type.
(def ^:private row-container-chrome
  {:display       "flex"
   :align-items   "center"
   :gap           "8px"
   :font-family   mono-stack
   :font-size     "12px"
   :border-bottom (str "1px solid " (:border-subtle tokens))})

(def ^:private decision-row-style
  (assoc row-container-chrome :padding "8px 14px"))

(def ^:private teardown-row-style
  (assoc row-container-chrome :padding "6px 14px 6px 28px"))

(def ^:private abort-row-style
  (assoc row-container-chrome :padding "6px 14px 6px 42px"))

;; Per-row cells — stable across all three row types.
(def ^:private time-chip-style
  {:color       (:text-tertiary tokens)
   :font-size   "10px"
   :min-width   "70px"
   :white-space "nowrap"})

(def ^:private primary-cell-style
  {:color         (:text-primary tokens)
   :flex          1
   :overflow      "hidden"
   :text-overflow "ellipsis"
   :white-space   "nowrap"})

(def ^:private tertiary-trailing-style
  {:color       (:text-tertiary tokens)
   :font-size   "10px"
   :white-space "nowrap"})

;; Category label (DECISION / TEARDOWN / ABORT) — shape stable, colour
;; varies per kind. Decision uses `:text-secondary` (the parent line is
;; presentationally muted, not category-tinted); teardown + abort reuse
;; their kind colour.
(def ^:private category-label-base
  {:text-transform "uppercase"
   :font-size      "9px"
   :letter-spacing "0.5px"})

(def ^:private decision-category-label-style
  (assoc category-label-base :color (:text-secondary tokens)))

(def ^:private teardown-category-label-style
  (assoc category-label-base :color teardown-colour))

(def ^:private abort-category-label-style
  (assoc category-label-base :color abort-colour))

;; Leading glyph cell — bold mark coloured per kind.
(def ^:private decision-glyph-style {:color decision-colour :font-weight 700})
(def ^:private teardown-glyph-style {:color teardown-colour :font-weight 700})
(def ^:private abort-glyph-style    {:color abort-colour    :font-weight 700})

;; rf2-wuwu3 — pre-computed row-style × cursor variants. The three
;; row types × {clickable, static} fan out to exactly 6 final
;; container maps; per-render `(merge … cursor-overlay)` allocations
;; are gone. With ~50 abort rows that's ~50 fewer fresh objects per
;; cascade re-render (on top of the rf2-qx414 hoists). Each pair
;; folds `:cursor` into the row's chrome at ns load.
(def ^:private decision-row-style-clickable
  (assoc decision-row-style :cursor "pointer"))
(def ^:private decision-row-style-static
  (assoc decision-row-style :cursor "default"))
(def ^:private teardown-row-style-clickable
  (assoc teardown-row-style :cursor "pointer"))
(def ^:private teardown-row-style-static
  (assoc teardown-row-style :cursor "default"))
(def ^:private abort-row-style-clickable
  (assoc abort-row-style :cursor "pointer"))
(def ^:private abort-row-style-static
  (assoc abort-row-style :cursor "default"))

;; ---- header --------------------------------------------------------------

(def ^:private header-style
  {:display         "flex"
   :align-items     "center"
   :justify-content "space-between"
   :gap             "12px"
   :padding         "10px 14px"
   :background      (:bg-2 tokens)
   :border-bottom   (str "1px solid " (:border-subtle tokens))})

(def ^:private header-title-style
  {:font-family sans-stack
   :font-size   (:body type-scale)
   :font-weight 600
   :color       (:text-primary tokens)})

(def ^:private header-summary-style
  {:font-family sans-stack
   :font-size   "11px"
   :color       (:text-tertiary tokens)
   :margin-top  "2px"})

(def ^:private header-close-button-style
  {:background  "transparent"
   :border      "none"
   :color       (:text-secondary tokens)
   :cursor      "pointer"
   :font-family mono-stack
   :font-size   "14px"})

(defn- header
  "Top strip — title + the cascade summary + close button (popover
  mount only). `close-fn` is the on-click for the close button; nil
  in the side-panel mount."
  [cascade close-fn]
  [:div {:data-testid "rf-xray-cancellation-cascade-header"
         :style       header-style}
   [:div
    [:div {:style header-title-style} "Cancellation cascade"]
    [:div {:data-testid "rf-xray-cancellation-cascade-summary"
           :style       header-summary-style}
     (h/cascade-summary cascade)]]
   (when close-fn
     [:button {:data-testid "rf-xray-cancellation-cascade-close"
               :aria-label  "Close cancellation cascade"
               :title       "Close"
               :on-click    close-fn
               :style       header-close-button-style}
      "×"])])

;; ---- parent-decision row ------------------------------------------------

(defn- parent-decision-row
  "Render the parent-decision row at the top of the waterfall."
  [decision]
  ;; rf2-nesy9 — render-time frame capture so the deferred row click
  ;; dispatches into the surrounding instance frame, not a `:rf/xray`
  ;; literal. The row renders inside the SidePanel / Popover reg-views.
  (let [frame      (rf/current-frame-id)
        clickable? (boolean (:dispatch-id decision))]
    [:div {:data-testid "rf-xray-cancellation-cascade-decision-row"
           :on-click    (when clickable?
                          (fn [_]
                            (rf/dispatch
                              [:rf.xray/focus-trace-entry
                               {:dispatch-id (:dispatch-id decision)
                                :trace-id    (:trace-id decision)}]
                              {:frame frame})))
           :style       (if clickable?
                          decision-row-style-clickable
                          decision-row-style-static)}
     [:span {:style decision-glyph-style}
      (:parent-decision row-glyph)]
     [:span {:style time-chip-style}
      (h/format-time-ms (:t decision))]
     [:span {:style decision-category-label-style}
      "DECISION"]
     [:span {:style primary-cell-style}
      (h/format-event-vec (:event-vec decision))]
     (when-let [m (:machine-id decision)]
       [:span {:style tertiary-trailing-style}
        (str " by " m)])]))

;; ---- teardown row --------------------------------------------------------

(defn- teardown-row
  [{:keys [child-id t inflight-count reason dispatch-id trace-id]}]
  (let [frame      (rf/current-frame-id)
        clickable? (boolean dispatch-id)]
    [:div {:data-testid (str "rf-xray-cancellation-cascade-teardown-row-"
                             (str child-id))
           :on-click    (when clickable?
                          (fn [_]
                            (rf/dispatch
                              [:rf.xray/focus-trace-entry
                               {:dispatch-id dispatch-id
                                :trace-id    trace-id}]
                              {:frame frame})))
           :style       (if clickable?
                          teardown-row-style-clickable
                          teardown-row-style-static)}
     [:span {:style teardown-glyph-style}
      (:child-teardown row-glyph)]
     [:span {:style time-chip-style}
      (h/format-time-ms t)]
     [:span {:style teardown-category-label-style}
      "TEARDOWN"]
     [:span {:style primary-cell-style}
      (str child-id)]
     [:span {:style tertiary-trailing-style}
      (str inflight-count
           (if (= 1 inflight-count)
             " in-flight fx"
             " in-flight fxs"))]
     (when reason
       [:span {:style tertiary-trailing-style}
        (str " · " reason)])]))

;; ---- abort row -----------------------------------------------------------

(defn- abort-row
  [{:keys [fx t cancel-cause url correlation-id
           dispatch-id trace-id]
    :as row}
   last?]
  (let [frame      (rf/current-frame-id)
        clickable? (boolean dispatch-id)]
    [:div {:data-testid (str "rf-xray-cancellation-cascade-abort-row-"
                             (str (or trace-id correlation-id)))
           :data-cancel-cause (str cancel-cause)
           :data-fx           (name (or fx :unknown))
           :on-click    (when clickable?
                          (fn [_]
                            (rf/dispatch
                              [:rf.xray/focus-trace-entry
                               {:dispatch-id dispatch-id
                                :trace-id    trace-id}]
                              {:frame frame})))
           :style       (if clickable?
                          abort-row-style-clickable
                          abort-row-style-static)}
     [:span {:style abort-glyph-style}
      (if last?
        (:effect-abort-last row-glyph)
        (:effect-abort row-glyph))]
     [:span {:style time-chip-style}
      (h/format-time-ms t)]
     [:span {:style abort-category-label-style}
      "ABORT"]
     [:span {:style primary-cell-style}
      (h/format-fx-label row)]
     [:span {:style tertiary-trailing-style}
      (str cancel-cause)]]))

;; ---- collapsed expander row ---------------------------------------------

(def ^:private expander-container-style
  {:padding       "8px 14px"
   :text-align    "center"
   :border-bottom (str "1px solid " (:border-subtle tokens))})

(def ^:private expander-button-style
  {:background    "transparent"
   :border        (str "1px solid " (:border-default tokens))
   :color         (:accent tokens)
   :font-family   sans-stack
   :font-size     "11px"
   :padding       "3px 10px"
   :border-radius "10px"
   :cursor        "pointer"})

(defn- expand-button
  "Renders the 'Show all N' / 'Collapse' affordance under the abort
  list when collapse is active."
  [collapsed-count total-count expanded?]
  ;; rf2-nesy9 — render-time frame capture for the deferred toggle click.
  (let [frame (rf/current-frame-id)]
   [:div {:data-testid "rf-xray-cancellation-cascade-expander"
         :style       expander-container-style}
   [:button {:data-testid "rf-xray-cancellation-cascade-expand-toggle"
             :on-click    (fn [_]
                            (rf/dispatch
                              [:rf.xray/cancellation-cascade-toggle-expand]
                              {:frame frame}))
             :style       expander-button-style}
    (if expanded?
      (str "Collapse · showing " total-count)
      (str "Show all " total-count " · "
           collapsed-count " hidden"))]]))

;; ---- empty states --------------------------------------------------------

(def ^:private empty-state-style
  {:padding     "16px"
   :color       (:text-tertiary tokens)
   :font-family sans-stack
   :font-size   "13px"
   :text-align  "center"})

(def ^:private empty-state-paragraph-style {:margin 0})

(defn- empty-state
  "Empty-state body — branches on the cascade's `:empty-kind`."
  [cascade]
  (let [kind (:empty-kind cascade)]
    [:div {:data-testid (str "rf-xray-cancellation-cascade-empty-"
                             (name (or kind :no-trigger)))
           :style       empty-state-style}
     (case kind
       :no-trigger
       [:p {:style empty-state-paragraph-style}
        "No cancellation cascade in the trace window."]

       :no-aborts
       [:p {:style empty-state-paragraph-style}
        "Destroy fired — no in-flight effects to abort."]

       [:p {:style empty-state-paragraph-style} "No cascade data."])]))

;; ---- the body (waterfall list) -----------------------------------------

(def ^:private body-style
  {:flex       1
   :overflow   "auto"
   :background (:bg-2 tokens)})

(defn- body
  "Render the waterfall body — decision + teardown rows + abort rows.
  Reads `:rf.xray/cancellation-cascade-expanded?` to decide whether
  the abort list is collapsed."
  [cascade]
  (let [{:keys [parent-decision child-teardowns effect-aborts]} cascade
        expanded? @(rf/subscribe [:rf.xray/cancellation-cascade-expanded?])
        collapse? (h/should-collapse? cascade)
        visible-aborts (cond
                         (not collapse?) effect-aborts
                         expanded?       effect-aborts
                         :else           (vec (take 5 effect-aborts)))
        hidden-count   (- (count effect-aborts) (count visible-aborts))]
    [:div {:data-testid "rf-xray-cancellation-cascade-body"
           :data-collapsed (str (and collapse? (not expanded?)))
           :data-aborts-shown (str (count visible-aborts))
           :data-aborts-total (str (count effect-aborts))
           :style          body-style}
     (when parent-decision
       (parent-decision-row parent-decision))
     (when (seq child-teardowns)
       [:div {:data-testid "rf-xray-cancellation-cascade-teardowns"}
        ;; `^{:key …}` reader meta on the `(teardown-row t)` /
        ;; `(abort-row …)` call below would be attached to the source
        ;; list and lost when the call returns its fresh vector —
        ;; Reagent's `get-react-key` only reads `:key` meta from
        ;; vectors (see reagent2.impl.template). `teardown-row` and
        ;; `abort-row` always return a `[:div …]` vector, so apply
        ;; the key directly via `with-meta`. (rf2-ppzid)
        (doall
          (for [t child-teardowns]
            (with-meta (teardown-row t)
              {:key (str "teardown-" (or (:trace-id t)
                                         (:child-id t)))})))])
     (when (seq visible-aborts)
       [:div {:data-testid "rf-xray-cancellation-cascade-aborts"}
        (doall
          (map-indexed
            (fn [idx row]
              (let [last? (= idx (dec (count visible-aborts)))]
                (with-meta (abort-row row last?)
                  {:key (str "abort-" (or (:trace-id row)
                                          (:correlation-id row)
                                          idx))})))
            visible-aborts))])
     (when (and collapse? (pos? hidden-count) (not expanded?))
       (expand-button hidden-count (count effect-aborts) expanded?))
     (when (and collapse? expanded?)
       (expand-button 0 (count effect-aborts) expanded?))]))

;; ---- public views --------------------------------------------------------

(def ^:private cascade-section-style
  {:display        "flex"
   :flex-direction "column"
   :height         "100%"
   :background     (:bg-2 tokens)
   :color          (:text-primary tokens)
   :font-family    sans-stack
   :border         (str "1px solid " (:border-subtle tokens))
   :border-radius  "4px"
   :overflow       "hidden"})

(defn render-cascade
  "Render the cascade as a self-contained block. `cascade` is the
  helpers/extract-cascade record; `close-fn` is optional (popover
  mount passes a close handler; side-panel mount passes nil).

  Always renders SOMETHING — the empty-state branches still render
  so the mount point can place this unconditionally."
  [cascade close-fn]
  [:section {:data-testid    "rf-xray-cancellation-cascade"
             :data-empty-kind (when (:empty-kind cascade)
                                (name (:empty-kind cascade)))
             :style          cascade-section-style}
   (header cascade close-fn)
   (if (:empty-kind cascade)
     (empty-state cascade)
     (body cascade))])

(rf/reg-view SidePanel
  "Machines-tab side-panel mount. Reads
  `:rf.xray/cancellation-cascade-for-focused-machine` and renders
  only when the cascade is non-empty (i.e. the focused machine had a
  destroy in the trace window). Closed-state cost is one subscribe
  + a when-gate."
  []
  (let [cascade @(rf/subscribe [:rf.xray/cancellation-cascade-for-focused-machine])]
    ;; The bead's contract: mount in the side-rail WHEN a destroy
    ;; lands. Empty `:no-trigger` cascades render nothing so the
    ;; mount stays dormant most of the time.
    (when-not (= :no-trigger (:empty-kind cascade))
      (render-cascade cascade nil))))

;; ---- popover (overlay) ---------------------------------------------------
;;
;; Backdrop honours `:rf.xray/modal-positioning` (rf2-om6fa).
;; `:fixed` (production default) — full-viewport overlay. `:absolute`
;; (Story testbeds) — backdrop confined to the shell cell with a
;; sane in-cell z-index.

(defn- backdrop-style [positioning]
  (let [absolute? (= positioning :absolute)]
    {:position         (if absolute? "absolute" "fixed")
     :top              0
     :left             0
     :right            0
     :bottom           0
     :background       "rgba(0,0,0,0.18)"
     :display          "flex"
     :align-items      "center"
     :justify-content  "center"
     :z-index          (if absolute? 97 2147483644)}))

(defn- dialog-style []
  {:width            "720px"
   :max-width        "92vw"
   :height           "520px"
   :max-height       "82vh"
   :display          "flex"
   :flex-direction   "column"
   :background       (:bg-1 tokens)
   :border           (str "1px solid " (:border-default tokens))
   :border-radius    "8px"
   :box-shadow       "rgba(0,0,0,0.6) 0 24px 64px"
   :overflow         "hidden"
   :font-family      sans-stack
   :color            (:text-primary tokens)})

(defn- handle-popover-keydown
  "Build the popover Esc-closes keydown handler, closing over the
  captured frame-aware `dispatch` (rf2-nesy9)."
  [dispatch]
  (fn [^js e]
    (when (= "Escape" (.-key e))
      (.preventDefault e)
      (.stopPropagation e)
      (dispatch [:rf.xray/cancellation-cascade-close]))))

(rf/reg-view Popover
  "Overlay popover mount. Reads
  `:rf.xray/cancellation-cascade-popover-open?` and short-circuits to
  nil when closed (one subscribe + when-gate). When open, reads
  `:rf.xray/cancellation-cascade-for-focused-event` and renders the
  cascade body inside the dialog."
  []
  (when @(rf/subscribe [:rf.xray/cancellation-cascade-popover-open?])
    (let [cascade     @(rf/subscribe [:rf.xray/cancellation-cascade-for-focused-event])
          positioning @(rf/subscribe [:rf.xray/modal-positioning])
          close       (fn [_]
                        (dispatch [:rf.xray/cancellation-cascade-close]))]
      ;; rf2-7oxvd — shared backdrop + dialog scaffold. This popover keeps
      ;; its own `backdrop-style` / `dialog-style`, the backdrop -1 /
      ;; dialog 0 tab-index split, and its `:label` accessible name (no
      ;; visible title id — unlike the labelled-by modals). `modal-chrome`
      ;; owns the positioning attribute, the click-outside dismiss,
      ;; `a11y/dialog-attrs` + the `a11y/dialog-ref` focus trap.
      ;;
      ;; Both the backdrop and the dialog get the BUILT keydown handler
      ;; `(handle-popover-keydown dispatch)` (rf2-op8c7). The dialog
      ;; previously received the bare 1-arity builder, so React called the
      ;; builder with the keydown event and discarded the handler fn it
      ;; returned — making the dialog-level Esc a no-op. With the focus
      ;; trap keeping focus inside the dialog, a dialog-focused Esc never
      ;; reached the backdrop's handler, so Esc-on-dialog did not close.
      (modal-chrome/modal-chrome
        {:positioning          positioning
         :backdrop-style       (backdrop-style positioning)
         :dialog-style         (dialog-style)
         :on-dismiss           #(dispatch [:rf.xray/cancellation-cascade-close])
         :label                "Cancellation cascade"
         :backdrop-testid      "rf-xray-cancellation-cascade-popover-backdrop"
         :dialog-testid        "rf-xray-cancellation-cascade-popover-dialog"
         :on-backdrop-key-down (handle-popover-keydown dispatch)
         :on-dialog-key-down   (handle-popover-keydown dispatch)
         :backdrop-tab-index   -1
         :dialog-tab-index     0}
        (render-cascade cascade close)))))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the visualiser's sub/event registrations.
  Called from `registry.cljs`'s `register-xray-handlers!` fan-out.

  The view-side `reg-view`s above are picked up at ns-load (the
  `reg-view` macro registers eagerly); this `install!` only registers
  the subs + events under the orchestrator's idempotency sentinel."
  []
  (subs/install!)
  (events/install!)
  nil)
