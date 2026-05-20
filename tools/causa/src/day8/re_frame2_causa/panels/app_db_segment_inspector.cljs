(ns day8.re-frame2-causa.panels.app-db-segment-inspector
  "App-DB segment inspector popup (rf2-e9tb0).

  Per `tools/causa/spec/004-App-DB-Diff.md` §Clickable path segments,
  each segment in an App-DB Diff section breadcrumb is independently
  clickable. A click opens this transient overlay showing the app-db
  value at the clicked path-prefix — `:cart` shows app-db at `[:cart]`,
  `:items` shows app-db at `[:cart :items]`, and so on.

  Replaces the dropped pinned-watches strip (Mike 2026-05-19 Q13). The
  diff already identifies changes surgically; on-demand inspection at
  any prefix removes the need to pin paths up-front.

  ## Modal-light overlay

  Mounted at the shell-view root alongside the other modals so its
  subscribes resolve through the shell's `frame-provider` to
  `:rf/causa` (the same mount discipline as `settings.popup`,
  `palette`, and `popover.causality` — see those files' docstrings for
  the rationale). Closed-state cost is one subscribe + a `when` — the
  body short-circuits to nil when `:rf.causa/segment-inspector-open?`
  is false.

  Visually the popup is lighter than the Settings modal — a smaller
  centred dialog with a 15% dim backdrop, mirroring the causality
  popover's chrome (transient overlay, not a full-window modal). The
  body renders the value at the inspected path via
  `theme/data-inspector/inspect` — the same primitive every L4 detail
  panel uses, so the renderer is uniform with the diff body.

  ## Three close affordances

  Per the bead's scope:

    1. `Esc` — handled by the popup's keydown listener.
    2. Click outside (backdrop) — backdrop's `:on-click` dispatches
       close; the dialog stops propagation so click-throughs on its
       body don't close.
    3. ✕ button in the header.

  ## Why every dispatch carries `{:frame :rf/causa}`

  Sister fix to rf2-smvvz (Settings popup) + rf2-w8lxg (Causality
  popover). Subscribes resolve through the React-context tier at
  render time; dispatches from `:on-click` / `:on-key-down` fire AFTER
  React pops the context, so the dispatch would otherwise land on
  `:rf/default` and the close handler would silently no-op. Carrying
  the `{:frame :rf/causa}` opt at call time pins the envelope to
  Causa's frame regardless of click-time React context."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.app-db-diff-format :as f]
            [day8.re-frame2-causa.theme.a11y :as a11y]
            [day8.re-frame2-causa.theme.data-inspector :as inspector]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens sans-stack mono-stack type-scale]]))

;; ---- subs ----------------------------------------------------------------

(defn install-subs! []
  ;; The slot shape: nil = closed; `{:path <vec>}` = open at that path.
  ;; Storing the path in app-db lets the popup survive shadow-cljs
  ;; `:after-load` re-renders without losing the user's context.
  (rf/reg-sub :rf.causa/segment-inspector-slot
    (fn [db _]
      (get db :segment-inspector)))

  (rf/reg-sub :rf.causa/segment-inspector-open?
    :<- [:rf.causa/segment-inspector-slot]
    (fn [slot _]
      (some? slot)))

  (rf/reg-sub :rf.causa/segment-inspector-path
    :<- [:rf.causa/segment-inspector-slot]
    (fn [slot _]
      (:path slot)))

  ;; Resolve the value at the inspected path against the host frame's
  ;; current db. `:rf.causa/target-frame-db` (installed by app-db-diff-
  ;; subs) is the live deref against the picker-selected frame; we read
  ;; through the same seam so the popup follows the picker without any
  ;; bespoke wiring. Empty path returns the whole db (the inspector
  ;; renders the root map).
  (rf/reg-sub :rf.causa/segment-inspector-value
    :<- [:rf.causa/segment-inspector-path]
    :<- [:rf.causa/target-frame-db]
    (fn [[path db] _]
      (if (seq path)
        (get-in db path)
        db))))

;; ---- events --------------------------------------------------------------

(defn install-events! []
  (rf/reg-event-db :rf.causa/open-segment-inspector
    (fn [db [_ path]]
      (assoc db :segment-inspector {:path (vec path)})))

  (rf/reg-event-db :rf.causa/close-segment-inspector
    (fn [db _]
      (dissoc db :segment-inspector))))

;; ---- styles --------------------------------------------------------------
;;
;; Backdrop honours `:rf.causa/modal-positioning` (rf2-om6fa) so
;; Story testbeds get an in-cell overlay rather than a viewport-spanning
;; one. Same shape as the causality popover.

(defn- backdrop-style [positioning]
  (let [absolute? (= positioning :absolute)]
    {:position         (if absolute? "absolute" "fixed")
     :top              0
     :left             0
     :right            0
     :bottom           0
     :background       "rgba(0,0,0,0.20)"
     :display          "flex"
     :align-items      "flex-start"
     :justify-content  "center"
     :padding-top      (if absolute? "6%" "10vh")
     :z-index          (if absolute? 101 2147483645)}))

(defn- dialog-style []
  {:width            "560px"
   :max-width        "92vw"
   :max-height       "72vh"
   :display          "flex"
   :flex-direction   "column"
   :background       (:bg-1 tokens)
   :border           (str "1px solid " (:border-default tokens))
   :border-radius    "8px"
   :box-shadow       "rgba(0,0,0,0.6) 0 20px 56px"
   :overflow         "hidden"
   :font-family      sans-stack
   :color            (:text-primary tokens)})

(defn- header-style []
  {:display          "flex"
   :align-items      "center"
   :justify-content  "space-between"
   :padding          "10px 14px"
   :background       (:bg-2 tokens)
   :border-bottom    (str "1px solid " (:border-subtle tokens))})

(defn- body-style []
  {:flex             1
   :overflow         "auto"
   :padding          "12px 14px"
   :background       (:bg-2 tokens)
   :font-family      mono-stack
   :font-size        (:body type-scale)
   :color            (:text-primary tokens)})

(defn- close-button-style []
  {:background       "transparent"
   :border           "none"
   :color            (:text-secondary tokens)
   :font-size        "16px"
   :line-height      1
   :cursor           "pointer"
   :padding          "2px 8px"
   :border-radius    "3px"})

;; ---- key handling --------------------------------------------------------

(defn- handle-keydown
  "Esc closes the popup. Other keys bubble — the global keybindings
  (palette, causality) still resolve when the popup is open."
  [^js e]
  (when (= "Escape" (.-key e))
    (.preventDefault e)
    (.stopPropagation e)
    (rf/dispatch [:rf.causa/close-segment-inspector]
                 {:frame :rf/causa})))

;; ---- view ----------------------------------------------------------------

(defn- header-title
  "Render the dialog header: a label + the inspected path. Empty path
  inspects the root db; the title says 'app-db (root)' so the user
  isn't left wondering what they're looking at."
  [path]
  [:span {:id          "rf-causa-segment-inspector-title"
          :data-testid "rf-causa-segment-inspector-title"
          :style {:color       (:text-primary tokens)
                  :font-weight 600
                  :font-size   (:body type-scale)
                  :font-family sans-stack
                  :display     "inline-flex"
                  :align-items "baseline"
                  :gap         "8px"}}
   [:span {:style {:color (:text-secondary tokens)}} "app-db at"]
   [:code {:style {:color       (:accent-violet tokens)
                   :font-family mono-stack
                   :font-size   "12px"}}
    (if (seq path)
      (f/format-edn (vec path))
      "(root)")]])

(defn- popup-view
  "Hiccup for the open popup. Caller (`Popup` reg-view) has gated on
  `:rf.causa/segment-inspector-open?` already."
  []
  (let [path        @(rf/subscribe [:rf.causa/segment-inspector-path])
        value       @(rf/subscribe [:rf.causa/segment-inspector-value])
        positioning @(rf/subscribe [:rf.causa/modal-positioning])]
    [:div {:data-testid "rf-causa-segment-inspector-backdrop"
           :data-rf-causa-modal-positioning (name (or positioning :fixed))
           :on-click    #(rf/dispatch [:rf.causa/close-segment-inspector]
                                      {:frame :rf/causa})
           :on-key-down handle-keydown
           :tab-index   -1
           :style       (backdrop-style positioning)}
     [:div (merge
             ;; rf2-7389r — WAI-ARIA dialog contract on the inspector
             ;; popover. The previous tab-index 0 marker hinted at
             ;; focus-trap intent but lacked role/aria-modal; this
             ;; finishes the contract + adds focus-on-mount so keyboard
             ;; users start inside the dialog (audit findings #3 + #19).
             (a11y/dialog-attrs {:labelled-by "rf-causa-segment-inspector-title"})
             {:data-testid "rf-causa-segment-inspector-dialog"
              :data-rf-causa-mode "segment-inspector"
              :ref         (a11y/focus-on-mount-ref)
              :on-click    #(.stopPropagation %)
              :on-key-down handle-keydown
              :tab-index   0
              :style       (dialog-style)})
      ;; Header
      [:div {:style (header-style)}
       (header-title path)
       [:button {:data-testid "rf-causa-segment-inspector-close"
                 :aria-label  "Close segment inspector"
                 :title       "Close (Esc)"
                 :on-click    (fn [^js e]
                                (.stopPropagation e)
                                (rf/dispatch [:rf.causa/close-segment-inspector]
                                             {:frame :rf/causa}))
                 :style       (close-button-style)}
        "✕"]]
      ;; Body — the cljs-devtools-shaped expandable tree at the path.
      ;; A unique `node-key` per (open) keeps the inspector's per-node
      ;; expand-state from colliding with the App-db Diff panel's
      ;; renders of the same value. The path's pr-str is stable across
      ;; renders so toggle state survives shadow-cljs reloads.
      [:div {:data-testid "rf-causa-segment-inspector-body"
             :style       (body-style)}
       (inspector/inspect (f/display-value value)
                          (str "segment-inspector/" (pr-str (vec path))))]]]))

(rf/reg-view Popup
  "The App-DB segment inspector popup. Renders only when
  `:rf.causa/segment-inspector-open?` is true; closed-state is a
  single subscribe + a `when` — cheap.

  Per rf2-in6l2 `reg-view`-registered so the body's subscribes route
  through the React-context tier to `:rf/causa`."
  []
  (when @(rf/subscribe [:rf.causa/segment-inspector-open?])
    (popup-view)))

(defn install!
  "Idempotent install for the segment-inspector's Causa-side
  registrations. Returns nil per the facade convention."
  []
  (install-subs!)
  (install-events!)
  nil)
