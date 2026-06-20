(ns day8.re-frame2-xray.palette.view
  "View for the Xray command palette.

  Per `tools/xray/spec/007-UX-IA.md` §Command palette:
  - 560px centred modal
  - 40px row layout (compact 32, comfy 48 — Phase 1 ships 40)
  - 16px type icon · label · right-aligned hint (epoch / coord / shortcut)
  - Arrows navigate · Enter invokes · Ctrl+Enter pops out

  The component is a plain Reagent fn — the facade
  `palette.cljs` wraps it in `reg-view` so its subscribes route to
  the surrounding `:rf/xray` frame via React context.

  ## Modal layer

  The palette layers over the Xray shell's `shell-view`
  (mounted there so subscribes resolve through the shell's frame
  provider). The backdrop + dialog scaffold is the shared
  `theme/modal-chrome` contract. The palette supplies its own `backdrop-style` /
  `dialog-style`, the `:label \"Command palette\"` accessible name, the
  `data-rf-xray-mode` marker (via `:dialog-extra`) and the
  click-outside dismiss (`:on-dismiss`); `modal-chrome` owns the
  click-outside wiring, the `a11y/dialog-attrs` (role / aria-modal /
  accessible name) and the `a11y/dialog-ref` focus trap. The palette
  is always `:fixed` (it has no Story testbed cell, so no
  `:rf.xray/modal-positioning` subscribe — it passes the literal
  `:fixed`).

  ## Keyboard handling

  The text input owns key handling: ArrowDown / ArrowUp moves the
  cursor, Enter invokes, Ctrl+Enter (or Cmd+Enter on mac) invokes
  in a pop-out, ESC closes. The shell-level Cmd/Ctrl+K listener
  handles open; ESC inside the input also closes so the user does
  not need to drop modifier hands. Because the input owns Esc, the
  palette passes NO chrome keydown handler (the edit-popup pattern).

  ## Focus trap + the combobox cursor

  `modal-chrome` provides the `a11y/dialog-ref` focus trap. The trap
  intercepts only `Tab` /
  `Shift+Tab`; the palette's sole real focusable is the search input
  (the result `<li>`s carry no tabindex — the cursor is a VIRTUAL
  selection tracked via `aria-activedescendant`, focus never leaves the
  input). So `Tab` simply wraps back to the input and the arrow-key
  navigation is untouched. `dialog-ref` also respects the input's
  `:auto-focus` — it skips focus-on-open when a descendant already
  holds focus — so there is no double-focus on mount.

  ## Why every deferred dispatch captures the surrounding frame

  Subscribes resolve through the React-context tier at RENDER time —
  React's `_currentValue` for the `frame-context` is set to the
  instance frame while the body of the `frame-provider`'s children is
  rendering, so `(rf/subscribe …)` from inside the palette picks up
  the right frame with no explicit opt.

  Dispatches from `:on-click` / `:on-change` / `:on-key-down` /
  `:on-mouse-enter` fire LATER — after render commits and React has
  POPPED `_currentValue` back to the context's default (`:rf/default`).
  At click time the 3-tier frame resolution chain (dynamic var →
  React-context tier → `:rf/default`) falls all the way through, the
  dispatch lands on `:rf/default`'s router, and the `:rf.xray/palette-*`
  handler reduces `:rf/default`'s db — leaving Xray's `:palette-*`
  slots untouched. Symptom: palette appears frozen — arrow keys,
  Enter, click on a row, ESC, backdrop click all no-op.

  So each deferred handler captures the SURROUNDING instance frame:
  `palette.cljs`'s `Modal` `reg-view` body has a frame-aware `dispatch`
  injected by the macro and threads it into `palette-view`, which fans
  it out to every row + key handler. Each deferred handler calls that
  captured `dispatch` (never the global `rf/dispatch`, never a
  `{:frame :rf/xray}` literal), so N isolated instances each route to
  their own frame."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.palette.sources :as sources]
            [day8.re-frame2-xray.theme.modal-chrome :as modal-chrome]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack mono-stack type-scale]]))

;; ---- per-source visual style --------------------------------------------

;; Per-source categorical legend. `:command` is the
;; primary action category → the single `:accent` (GitHub blue);
;; `:panel` keeps a fixed cool blue (`:info`) so it stays a distinct
;; category against the command accent. yellow/green/magenta are
;; functional categorical hues, untouched.
(def ^:private source-colour
  {:command          (:accent tokens)
   :panel            (:info tokens)
   :setting          (:yellow tokens)
   :recent-event     (:green tokens)
   :frame            (:magenta tokens)
   :handler          (:text-secondary tokens)})

(defn- backdrop-style []
  {:position         "fixed"
   :top              0
   :left             0
   :right            0
   :bottom           0
   :background       "rgba(0,0,0,0.55)"
   :backdrop-filter  "blur(2px)"
   :display          "flex"
   :align-items      "flex-start"
   :justify-content  "center"
   :padding-top      "12vh"
   :z-index          2147483646})

(defn- dialog-style []
  {:width            "560px"
   :max-width        "92vw"
   :max-height       "60vh"
   :display          "flex"
   :flex-direction   "column"
   :background       (:bg-1 tokens)
   :border           (str "1px solid " (:border-default tokens))
   :border-radius    "8px"
   :box-shadow       "rgba(0,0,0,0.6) 0 24px 64px"
   :overflow         "hidden"
   :font-family      sans-stack
   :color            (:text-primary tokens)})

(defn- input-row-style []
  {:display          "flex"
   :align-items      "center"
   :gap              "10px"
   :padding          "12px 16px"
   :border-bottom    (str "1px solid " (:border-subtle tokens))
   :background       (:bg-2 tokens)})

(defn- input-style []
  {:flex             1
   :background       "transparent"
   :border           "none"
   :outline          "none"
   :color            (:text-primary tokens)
   :font-family      sans-stack
   :font-size        "14px"})

(defn- list-style []
  {:flex             1
   :overflow-y       "auto"
   :overflow-x       "hidden"
   :margin           0
   :padding          "4px 0"
   :list-style       "none"})

(defn- row-style [active?]
  {:display          "flex"
   :align-items      "center"
   :gap              "12px"
   :padding          "0 16px"
   :height           "40px"
   :cursor           "pointer"
   :background       (if active? (:bg-active tokens) "transparent")
   :color            (:text-primary tokens)
   :font-family      sans-stack
   :font-size        (:body type-scale)})

(defn- icon-style [source]
  {:width            "16px"
   :flex-shrink      0
   :text-align       "center"
   :color            (or (source-colour source) (:text-secondary tokens))
   :font-family      mono-stack
   :font-size        "14px"})

(defn- hint-style []
  {:flex-shrink      0
   :max-width        "220px"
   :overflow         "hidden"
   :text-overflow    "ellipsis"
   :white-space      "nowrap"
   :color            (:text-tertiary tokens)
   :font-family      mono-stack
   :font-size        (:caption type-scale)})

(defn- footer-style []
  {:display          "flex"
   :align-items      "center"
   :justify-content  "space-between"
   :padding          "8px 16px"
   :border-top       (str "1px solid " (:border-subtle tokens))
   :background       (:bg-2 tokens)
   :color            (:text-tertiary tokens)
   :font-family      sans-stack
   :font-size        (:caption type-scale)})

(defn- footer-key-style []
  {:padding          "1px 6px"
   :border           (str "1px solid " (:border-default tokens))
   :border-radius    "3px"
   :background       (:bg-3 tokens)
   :color            (:text-secondary tokens)
   :font-family      mono-stack
   :font-size        "10px"
   :margin           "0 2px"})

;; ---- empty state --------------------------------------------------------

(defn- empty-message [query]
  (if (seq query)
    (str "No matches for " (pr-str query) ".")
    "Type to search. ↑↓ navigate · Enter invokes · Ctrl+Enter pops out · ESC closes."))

(defn- empty-row []
  [:li {:data-testid "rf-xray-palette-empty"
        :style       (merge (row-style false)
                            {:cursor "default"
                             :color  (:text-tertiary tokens)})}
   [:span {:style (icon-style :handler)} "·"]
   [:span {:style {:flex 1}}
    (empty-message @(rf/subscribe [:rf.xray/palette-query]))]])

;; ---- row ----------------------------------------------------------------

(def ^:private listbox-id
  "DOM `id` of the palette result list. Stable per-modal (only one
  palette mounts at a time), so the input's `aria-controls` +
  `aria-activedescendant` references resolve without per-render id
  churn."
  "rf-xray-palette-listbox")

(defn- row-id
  "DOM `id` of result row `idx` — referenced by the input's
  `aria-activedescendant` so screen readers track the cursor highlight
  as the user arrows up/down."
  [idx]
  (str "rf-xray-palette-option-" idx))

(defn- result-row
  [dispatch {:keys [source label hint icon] :as item} active? idx]
  [:li {:key          (str "row-" idx)
        :id           (row-id idx)
        :role         "option"
        :aria-selected (if active? "true" "false")
        :data-testid  (str "rf-xray-palette-row-" idx)
        :data-source  (name source)
        :data-active  (str active?)
        :on-click     #(dispatch
                         [:rf.xray/palette-invoke item
                          (boolean (or (.-ctrlKey %) (.-metaKey %)))])
        :on-mouse-enter #(dispatch [:rf.xray/palette-cursor-set idx])
        :style        (row-style active?)}
   [:span {:style (icon-style source)} icon]
   [:span {:style {:flex             1
                   :overflow         "hidden"
                   :text-overflow    "ellipsis"
                   :white-space      "nowrap"}}
    label]
   (when hint
     [:span {:style (hint-style)} hint])
   (when (sources/popoutable? item)
     [:span {:style (merge (hint-style)
                           {:margin-left "8px"
                            :color (:accent tokens)})}
      "⇱"])])

;; ---- key handling -------------------------------------------------------

(defn- handle-input-keydown
  ;; EP-0002 — a deferred key handler fires at CLICK time,
  ;; after render has committed and the frame context has unwound, so it
  ;; carries NO ambient frame stamp. It must therefore not `rf/subscribe`
  ;; the cursor itself (that ambient read raises `:rf.error/no-frame-context`
  ;; under no scope rather than falling through to a synthesised
  ;; `:rf/default`). The `cursor` is read at RENDER time by `palette-view`
  ;; (where the surrounding instance frame's React context is live) and
  ;; threaded in alongside the frame-aware `dispatch`, mirroring how the
  ;; dispatch itself is injected rather than resolved at click time.
  [dispatch cursor ^js e results]
  (let [k         (.-key e)
        ctrl?     (or (.-ctrlKey e) (.-metaKey e))
        count     (count results)
        active    (when (and (seq results) (< cursor count))
                    (nth results cursor))]
    (case k
      "ArrowDown" (do (.preventDefault e)
                      (dispatch
                        [:rf.xray/palette-cursor-down (dec count)]))
      "ArrowUp"   (do (.preventDefault e)
                      (dispatch [:rf.xray/palette-cursor-up]))
      "Enter"     (when active
                    (.preventDefault e)
                    (dispatch
                      [:rf.xray/palette-invoke active (boolean ctrl?)]))
      "Escape"    (do (.preventDefault e)
                      (dispatch [:rf.xray/palette-close]))
      "Home"      (do (.preventDefault e)
                      (dispatch [:rf.xray/palette-cursor-set 0]))
      "End"       (do (.preventDefault e)
                      (dispatch [:rf.xray/palette-cursor-set (dec count)]))
      nil)))

;; ---- main view ---------------------------------------------------------

(defn palette-view
  "The hiccup for the open palette. Caller (`palette/Modal`) gates
  the mount on `:rf.xray/palette-open?` — this fn assumes it's open
  and always renders.

  `dispatch` is the frame-aware dispatcher injected by the
  `palette/Modal` `reg-view` body — threaded into every row + key
  handler so deferred handlers land on the surrounding instance frame,
  not a `{:frame :rf/xray}` literal."
  [dispatch]
  (let [query   (or @(rf/subscribe [:rf.xray/palette-query]) "")
        results @(rf/subscribe [:rf.xray/palette-results])
        cursor  @(rf/subscribe [:rf.xray/palette-cursor])]
    ;; Shared backdrop + dialog scaffold. The palette keeps
    ;; its own `backdrop-style` / `dialog-style` and its `:auto-focus`
    ;; input owning Esc (handled in `handle-input-keydown`, exactly the
    ;; edit-popup pattern), so it passes NO chrome keydown handler.
    ;; `modal-chrome` owns the click-outside dismiss, the
    ;; `a11y/dialog-attrs` (role/aria-modal + the `:label`
    ;; accessible name — no visible title bar) and the `a11y/dialog-ref`
    ;; focus trap. The trap intercepts only Tab/Shift+Tab; the palette's
    ;; sole focusable is the input (rows drive the combobox cursor via
    ;; `aria-activedescendant`, not real focus), so Tab wraps back to the
    ;; input and the activedescendant navigation is untouched. The
    ;; `data-rf-xray-mode` marker rides in via `:dialog-extra`. The
    ;; palette is always `:fixed` (no `:rf.xray/modal-positioning` —
    ;; it has no Story testbed cell), so the positioning is the literal
    ;; `:fixed`.
    (modal-chrome/modal-chrome
      {:positioning      :fixed
       :backdrop-style   (backdrop-style)
       :dialog-style     (dialog-style)
       :on-dismiss       #(dispatch [:rf.xray/palette-close])
       :label            "Command palette"
       :backdrop-testid  "rf-xray-palette-backdrop"
       :dialog-testid    "rf-xray-palette-dialog"
       :dialog-tab-index "-1"
       :dialog-extra     {:data-rf-xray-mode "palette"}}
      [:div {:style (input-row-style)}
       [:span {:aria-hidden "true"
               :style (icon-style :command)} "⌘"]
       [:input {:data-testid  "rf-xray-palette-input"
                :type         "text"
                :auto-focus   true
                :value        query
                :placeholder  "Search panels, events, frames, commands…"
                ;; Combobox/listbox wiring: `aria-label`
                ;; names the input (no visible <label>); `aria-controls`
                ;; + `aria-activedescendant` point at the listbox + the
                ;; active row id so screen readers track the highlight
                ;; without focus actually moving off the input.
                ;; `aria-autocomplete="list"` + `role="combobox"` +
                ;; `aria-expanded` complete the WAI-ARIA APG combobox
                ;; pattern.
                :role         "combobox"
                :aria-label   "Search palette"
                :aria-controls listbox-id
                :aria-autocomplete "list"
                :aria-expanded (if (seq results) "true" "false")
                :aria-activedescendant
                              (when (and (seq results)
                                         (< cursor (count results)))
                                (row-id cursor))
                :on-change    #(dispatch
                                 [:rf.xray/palette-set-query
                                  (.. % -target -value)])
                :on-key-down  #(handle-input-keydown dispatch cursor % results)
                :style        (input-style)}]
       [:span {:data-testid "rf-xray-palette-result-count"
               :style {:color (:text-tertiary tokens)
                       :font-family mono-stack
                       :font-size "11px"}}
        (str (count results)
             (when (seq query) (str " / " (count results))))]]
      (if (empty? results)
        [:ul {:data-testid "rf-xray-palette-list"
              ;; Empty-state list is presentational — no options to
              ;; surface as listbox children. Keeping the ul role-less
              ;; avoids a "listbox, 0 items" announcement.
              :id          listbox-id
              :style       (list-style)}
         [empty-row]]
        (into [:ul {:data-testid "rf-xray-palette-list"
                    :id          listbox-id
                    :role        "listbox"
                    :aria-label  "Palette results"
                    :style       (list-style)}]
              (map-indexed
                (fn [idx item]
                  (result-row dispatch item (= idx cursor) idx))
                results)))
      [:div {:style (footer-style)}
       [:div
        [:span {:style (footer-key-style)} "↑↓"]
        [:span "navigate"]
        [:span {:style {:margin "0 8px"}} "·"]
        [:span {:style (footer-key-style)} "Enter"]
        [:span "open"]
        [:span {:style {:margin "0 8px"}} "·"]
        [:span {:style (footer-key-style)} "Ctrl"]
        [:span "+"]
        [:span {:style (footer-key-style)} "Enter"]
        [:span "pop-out"]]
       [:div
        [:span {:style (footer-key-style)} "ESC"]
        [:span "close"]]])))
