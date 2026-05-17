(ns day8.re-frame2-causa.popover.causality
  "Facade for the Causality popover (rf2-dqnuu).

  Per `tools/causa/spec/018-Event-Spine.md` §10 the popover replaces
  the dropped Causality tab. It is triggered by the `c` key from any
  tab and renders the focused-event's causal graph (ancestor chain +
  descendants tree) on a centred floating overlay with a dimmed
  backdrop.

  ## Modal layer

  Mounted at `shell-view` root (alongside `palette/Modal`) so its
  subscribes route through the shell's `frame-provider` to `:rf/causa`.
  The `Popover` reg-view short-circuits to nil when
  `:rf.causa/causality-popover-open?` is false; closed-state cost is
  one subscribe + a `when` — cheap.

  ## Close affordances

  Three ways to close per spec §10 §Interaction:

    1. `Esc` — handled by the popover's keydown listener.
    2. Click outside (backdrop) — backdrop's on-click dispatches close.
    3. `c` again — handled by the global keybinding's `causality-
       popover-toggle` route.

  ## Layout toggle (Q12)

  Per spec §10 §Layout direction the v1 ships TB as the default
  (descendants tree dominates the visual weight). The footer carries
  a toggle button that flips between LR and TB. The chosen direction
  persists per session in Causa's app-db at `:causality-popover-layout`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.popover.causality-events :as events]
            [day8.re-frame2-causa.popover.causality-subs   :as subs]
            [day8.re-frame2-causa.popover.causality-graph  :as graph]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens sans-stack mono-stack type-scale]]))

;; ---- styles --------------------------------------------------------------

(defn- backdrop-style []
  {:position         "fixed"
   :top              0
   :left             0
   :right            0
   :bottom           0
   :background       "rgba(0,0,0,0.15)"     ; spec §10 — 15% dim
   :display          "flex"
   :align-items      "center"
   :justify-content  "center"
   :z-index          2147483645})

(defn- dialog-style []
  {:width            "640px"      ; spec §10 — default 640×480
   :max-width        "92vw"
   :height           "480px"
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

(defn- header-style []
  {:display          "flex"
   :align-items      "center"
   :justify-content  "space-between"
   :padding          "10px 16px"
   :background       (:bg-2 tokens)
   :border-bottom    (str "1px solid " (:border-subtle tokens))
   :font-family      sans-stack
   :font-size        (:body type-scale)
   :font-weight      600})

(defn- body-style []
  {:flex             1
   :overflow         "auto"
   :background       (:bg-2 tokens)})

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

(defn- toggle-button-style [active?]
  {:padding       "2px 8px"
   :border        (str "1px solid " (:border-default tokens))
   :border-radius "4px"
   :background    (if active? (:bg-active tokens) "transparent")
   :color         (if active? (:text-primary tokens) (:text-secondary tokens))
   :cursor        "pointer"
   :font-family   mono-stack
   :font-size     "11px"
   :margin        "0 4px"})

(defn- close-button-style []
  {:width         "24px"
   :height        "24px"
   :display       "inline-flex"
   :align-items   "center"
   :justify-content "center"
   :border        "none"
   :background    "transparent"
   :color         (:text-secondary tokens)
   :cursor        "pointer"
   :font-family   mono-stack
   :font-size     "14px"})

;; ---- key handling --------------------------------------------------------

(defn- handle-popover-keydown
  "Esc closes the popover. Other keys bubble — the global keybinding
  catches `c` (toggle) and other spine keys."
  [^js e]
  (when (= "Escape" (.-key e))
    (.preventDefault e)
    (.stopPropagation e)
    (rf/dispatch [:rf.causa/causality-popover-close])))

;; ---- view ----------------------------------------------------------------

(defn- header-title
  "Render the popover header title. Includes the focused event's
  pretty-printed event vector + the dispatch-id."
  [payload]
  (let [{:keys [focused]} payload]
    (if focused
      (let [{:keys [event dispatch-id]} focused
            label (try (pr-str event) (catch :default _ (str event)))]
        (str "Causality — " label " (#" dispatch-id ")"))
      "Causality — (no focused event)")))

(defn- layout-toggle
  "Footer LR ↔ TB toggle. Two pill buttons; the active layout
  highlights. Dispatch flips through the events ns."
  [layout]
  [:div {:data-testid "rf-causa-popover-layout-toggle"
         :style {:display "flex" :align-items "center" :gap "4px"}}
   [:span {:style {:color (:text-tertiary tokens)
                   :margin-right "4px"
                   :font-size "11px"}}
    "Layout:"]
   [:button {:data-testid "rf-causa-popover-layout-lr"
             :on-click    #(when (not= :lr layout)
                             (rf/dispatch [:rf.causa/causality-popover-toggle-layout]))
             :style       (toggle-button-style (= :lr layout))}
    "LR"]
   [:button {:data-testid "rf-causa-popover-layout-tb"
             :on-click    #(when (not= :tb layout)
                             (rf/dispatch [:rf.causa/causality-popover-toggle-layout]))
             :style       (toggle-button-style (= :tb layout))}
    "TB"]])

(defn- popover-view
  "The hiccup for the open popover. Caller has gated on
  `:rf.causa/causality-popover-open?` already."
  []
  (let [payload @(rf/subscribe [:rf.causa/causality-popover-payload])
        layout  @(rf/subscribe [:rf.causa/causality-popover-layout])]
    [:div {:data-testid "rf-causa-popover-backdrop"
           :on-click    #(rf/dispatch [:rf.causa/causality-popover-close])
           :on-key-down handle-popover-keydown
           :tab-index   -1
           :style       (backdrop-style)}
     [:div {:data-testid "rf-causa-popover-dialog"
            :data-rf-causa-mode "popover"
            :on-click    #(.stopPropagation %)
            :on-key-down handle-popover-keydown
            :tab-index   0
            :style       (dialog-style)}
      ;; Header
      [:div {:style (header-style)}
       [:span {:data-testid "rf-causa-popover-title"
               :style {:color (:text-primary tokens)}}
        (header-title payload)]
       [:button {:data-testid "rf-causa-popover-close"
                 :on-click    #(rf/dispatch [:rf.causa/causality-popover-close])
                 :style       (close-button-style)}
        "×"]]
      ;; Body
      [:div {:style (body-style)
             :data-testid "rf-causa-popover-body"}
       (graph/body payload layout)]
      ;; Footer
      [:div {:style (footer-style)}
       (layout-toggle layout)
       [:div {:style {:color (:text-tertiary tokens)}}
        "Click a node to focus · Esc · c · click-outside to close"]]]]))

(rf/reg-view Popover
  "The Causality popover. Renders only when `:rf.causa/causality-
  popover-open?` is true; closed-state is a single subscribe + a
  `when` — cheap.

  Per rf2-in6l2 `reg-view`-registered so subscribes route through the
  React-context tier to `:rf/causa`."
  []
  (when @(rf/subscribe [:rf.causa/causality-popover-open?])
    (popover-view)))

(defn install!
  "Idempotent install for the popover's Causa-side registrations.
  Subs + events wire through the framework registrar (which is itself
  idempotent on re-register); the orchestrator's
  `registered?` sentinel gates the whole sequence so re-loads do not
  re-install."
  []
  (subs/install!)
  (events/install!)
  nil)
