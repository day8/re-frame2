(ns day8.re-frame2-xray.settings.editor-hint
  "Open-in-editor 'pick an editor in Settings' hint toast.

  ## The problem

  Xray's 'Open in editor' chip resolves a source-coord to an editor
  URI (`vscode://…` by default) and fires `Location.assign`. When the
  host application wired only the bare preload — it never called
  `(xray-config/configure! {:rf.xray/editor …})` — the chip targets the
  framework default `:vscode`. If VS Code is not the developer's editor,
  the OS has no protocol handler registered for `vscode:` and the click
  is a SILENT no-op: the URI resolves, `Location.assign` fires, and
  nothing happens. JS cannot observe the OS-level handler miss, so the
  developer gets no feedback at all.

  The fix is for host apps to set `:rf.xray/editor`; this toast
  surfaces that at click-time so the chip itself guides the developer.

  ## The hint

  When `config/editor-configured?` is false (NEITHER the host nor the
  operator has confirmed an editor — the target is the implicit
  framework default), the `:rf.xray/open-in-editor` event-fx routes to
  `:rf.xray/editor-hint-show` instead of `:rf.editor/open`. This toast
  appears in the bottom-right of the shell with a one-line note and an
  'Open Settings' button that lands the operator on the General tab's
  editor picker. Once an editor IS configured (host or operator), the
  click resolves and navigates to the configured editor — the hint
  never fires.

  Conservative by design: a small, non-intrusive
  bottom-corner toast, NOT a redesign of Settings and NOT a modal that
  blocks the chrome. It is dismissable (✕) and self-dismisses the moment
  the operator opens Settings.

  ## State

  One boolean app-db slot, `:editor-hint-open?`, on the `:rf/xray`
  frame. Three events + one sub, all installed by `install!`:

      :rf.xray/editor-hint-show          — show the toast
      :rf.xray/editor-hint-dismiss       — hide the toast (✕ / Esc)
      :rf.xray/editor-hint-open-settings — open Settings (General) + hide
      :rf.xray/editor-hint-open?         — sub: drives the toast mount

  Mounted at the shell-view root (sibling to the other modals) so its
  subscribe resolves through the shell's `:rf/xray` frame-provider."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack mono-stack type-scale]]))

;; ---- events + sub -------------------------------------------------------

(defn install!
  "Idempotent install for the editor-hint toast's events + sub. Called
  from `registry/register-xray-handlers!` (gated by the orchestrator's
  sentinel)."
  []
  (rf/reg-event :rf.xray/editor-hint-show
    (fn [{:keys [db]} _event]
      {:db (assoc db :editor-hint-open? true)}))

  (rf/reg-event :rf.xray/editor-hint-dismiss
    (fn [{:keys [db]} _event]
      {:db (assoc db :editor-hint-open? false)}))

  ;; Open the Settings popup on the General tab (where the editor
  ;; picker lives) and dismiss the toast in the same reduce. The
  ;; `:rf.xray/settings-open` handler already resets the active tab to
  ;; `:general` and seeds the settings snapshot, so opening it lands
  ;; the operator directly on the editor picker.
  (rf/reg-event :rf.xray/editor-hint-open-settings
    (fn [{:keys [db]} _event]
      {:db (assoc db :editor-hint-open? false)
       :fx [[:dispatch [:rf.xray/settings-open]]]}))

  (rf/reg-sub :rf.xray/editor-hint-open?
    (fn [db _query]
      (boolean (get db :editor-hint-open? false))))
  nil)

;; ---- styles -------------------------------------------------------------

(defn- toast-style []
  {:position      "absolute"
   :right         "16px"
   :bottom        "16px"
   :max-width     "300px"
   :z-index       2147483645
   :background    (:bg-2 tokens)
   :border        (str "1px solid " (:warning tokens))
   :border-radius "6px"
   :box-shadow    "rgba(0,0,0,0.5) 0 8px 24px"
   :padding       "12px 14px"
   :font-family   sans-stack
   :color         (:text-primary tokens)})

(defn- title-row-style []
  {:display         "flex"
   :align-items     "center"
   :justify-content "space-between"
   :gap             "8px"
   :margin-bottom   "6px"})

(defn- dismiss-button-style []
  {:background    "transparent"
   :border        "none"
   :color         (:text-secondary tokens)
   :font-size     "16px"
   :line-height   1
   :cursor        "pointer"
   :padding       "0 2px"})

(defn- open-settings-button-style []
  {:background    (:accent tokens)
   :color         (:white tokens)
   :border        "none"
   :padding       "5px 12px"
   :border-radius "4px"
   :cursor        "pointer"
   :font-family   sans-stack
   :font-size     (:body type-scale)
   :font-weight   500})

;; ---- view ---------------------------------------------------------------

(defn toast-view
  "Hiccup for the open editor-hint toast. The caller (`Toast`) gates
  the mount on `:rf.xray/editor-hint-open?`. `dispatch` is
  the frame-aware dispatcher injected by the `reg-view` body so the
  deferred `:on-click` handlers land on the surrounding instance frame."
  [dispatch]
  ;; The PRIMARY, reachable Esc-dismissal path is the
  ;; shell-level global keydown listener (`keybinding/handle-keydown`),
  ;; which dismisses the hint whenever it is open regardless of where
  ;; focus sits. This toast is a non-modal `role=status` surface and
  ;; MUST NOT trap focus (that would steal it from the host app), so in
  ;; the normal click flow focus is never inside the toast and this
  ;; in-DOM handler does not receive Esc. It is retained as
  ;; defense-in-depth for the case where focus DOES land inside the
  ;; toast (e.g. after the operator tabs to / clicks a button in it),
  ;; so Esc still works there too.
  [:div {:data-testid "rf-xray-editor-hint-toast"
         :role        "status"
         :aria-live   "polite"
         :on-key-down (fn [^js e]
                        (when (or (= "Escape" (.-key e)) (= "Esc" (.-key e)))
                          (.stopPropagation e)
                          (dispatch [:rf.xray/editor-hint-dismiss])))
         :style       (toast-style)}
   [:div {:style (title-row-style)}
    [:span {:style {:font-weight 600
                    :font-size   (:body type-scale)
                    :color       (:text-primary tokens)}}
     "No editor configured"]
    [:button {:data-testid "rf-xray-editor-hint-dismiss"
              :aria-label  "Dismiss"
              :title       "Dismiss"
              :on-click    (fn [^js e]
                             (.stopPropagation e)
                             (dispatch [:rf.xray/editor-hint-dismiss]))
              :style       (dismiss-button-style)}
     "✕"]]
   [:p {:style {:margin    "0 0 10px 0"
                :font-size (:caption type-scale)
                :color     (:text-secondary tokens)
                :line-height 1.45}}
    "Open-in-editor uses the default "
    [:code {:style {:font-family mono-stack
                    :color       (:text-tertiary tokens)}}
     ":vscode"]
    " scheme, which does nothing if VS Code is not your editor. "
    "Pick your editor in Settings to make click-to-source work."]
   [:button {:data-testid "rf-xray-editor-hint-open-settings"
             :on-click    (fn [^js e]
                            (.stopPropagation e)
                            (dispatch [:rf.xray/editor-hint-open-settings]))
             :style       (open-settings-button-style)}
    "Open Settings"]])

(rf/reg-view Toast
  "The open-in-editor editor-hint toast. Renders only when
  `:rf.xray/editor-hint-open?` is true; closed-state is a single
  subscribe + a `when` — cheap.

  `reg-view`-registered so the body's subscribes route through the
  React-context tier to `:rf/xray`. The reg-view-injected `dispatch`
  is threaded into `toast-view` so the deferred `:on-click` handlers
  land on the surrounding instance frame."
  []
  (when @(rf/subscribe [:rf.xray/editor-hint-open?])
    (toast-view dispatch)))
