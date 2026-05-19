(ns day8.re-frame2-causa.share-modal
  "Causa Share modal view (rf2-nqw0v, Phase 5).

  The modal mounts at the shell-view root (per the palette / settings
  popup pattern); the modal short-circuits to nil when
  `:rf.causa/share-modal-open?` is false, so the dormant cost is one
  subscribe + a `when`.

  ## Affordances

    - **Copyable URL input** — selected by default for keyboard-only
      flows; the Copy button writes via `navigator.clipboard`.
    - **Reset button** — clears the focus / mode / scrubber slots
      via the existing per-slot reducers, then re-renders the URL
      so the user can confirm the reset took.
    - **Open in new tab** — pops the encoded URL into a fresh tab so
      a teammate can confirm the restore path before sharing.

  ## Pure hiccup

  Every subscribe / dispatch resolves against `:rf/causa` via the
  enclosing frame-provider in `shell.cljs`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- view helpers -------------------------------------------------------
;;
;; Backdrop honours `:rf.causa/modal-positioning` (rf2-om6fa).
;; `:fixed` (production default) — full-viewport overlay. `:absolute`
;; (Story testbeds) — backdrop confined to the shell cell with a
;; sane in-cell z-index.

(defn- backdrop-style [positioning]
  (let [absolute? (= positioning :absolute)]
    {:position        (if absolute? "absolute" "fixed")
     :top             0
     :left            0
     :right           0
     :bottom          0
     :background      "rgba(8, 9, 12, 0.65)"
     :backdrop-filter "blur(2px)"
     :display         "flex"
     :align-items     "center"
     :justify-content "center"
     :z-index         (if absolute? 98 2147483100)}))

(defn- dialog-style []
  {:background    (:bg-1 tokens)
   :border        (str "1px solid " (:border-default tokens))
   :border-radius "6px"
   :box-shadow    "rgba(0, 0, 0, 0.5) 0 12px 32px"
   :width         "560px"
   :max-width     "92vw"
   :padding       "20px"
   :display       "flex"
   :flex-direction "column"
   :gap           "14px"
   :font-family   sans-stack
   :color         (:text-primary tokens)})

(defn- header
  []
  [:div {:style {:display "flex"
                 :align-items "center"
                 :justify-content "space-between"}}
   [:h2 {:style {:margin 0
                 :font-size "14px"
                 :font-weight 600
                 :color (:text-primary tokens)
                 :text-transform "uppercase"
                 :letter-spacing "0.5px"}}
    "Share Causa state"]
   [:button
    {:data-testid "rf-causa-share-modal-close"
     :on-click    #(rf/dispatch [:rf.causa/share-modal-close] {:frame :rf/causa})
     :style       {:background "transparent"
                   :border "none"
                   :color (:text-tertiary tokens)
                   :font-size "16px"
                   :cursor "pointer"
                   :padding "0 6px"}
     :title       "Close (Esc)"}
    "✕"]])

(defn- description
  []
  [:p {:style {:margin "0"
               :color (:text-secondary tokens)
               :font-size "12px"
               :line-height 1.5}}
   "Copy this URL to share your current Causa state — focused machine, "
   "view mode, scrubber position, and selected tab will round-trip "
   "when a teammate opens the link."])

(defn- url-input
  [url]
  [:input
   {:data-testid "rf-causa-share-modal-url"
    :type        "text"
    :read-only   true
    :value       (or url "")
    :on-click    (fn [e]
                   ;; Select the contents on click so Cmd+C / Ctrl+C
                   ;; works without an extra keystroke.
                   (when-let [t (.-target e)]
                     (try (.select t) (catch :default _ nil))))
    :style       {:width "100%"
                  :background (:bg-3 tokens)
                  :border (str "1px solid " (:border-default tokens))
                  :color (:text-primary tokens)
                  :padding "8px 10px"
                  :border-radius "4px"
                  :font-family mono-stack
                  :font-size "11px"
                  :box-sizing "border-box"}}])

(defn- copy-button
  [copy-status]
  (let [copied? (= :copied copy-status)
        failed? (= :failed copy-status)
        label   (cond
                  copied? "Copied!"
                  failed? "Copy failed"
                  :else   "Copy URL")
        tone    (cond
                  copied? (:green tokens)
                  failed? (:red tokens)
                  :else   (:accent-violet tokens))]
    [:button
     {:data-testid    "rf-causa-share-modal-copy"
      :data-status    (name (or copy-status :idle))
      :on-click       #(rf/dispatch
                         [:rf.causa/copy-share-url-to-clipboard]
                         {:frame :rf/causa})
      :style          {:background tone
                       :border "none"
                       :color "#101218"
                       :font-family sans-stack
                       :font-size "12px"
                       :font-weight 600
                       :padding "8px 16px"
                       :border-radius "4px"
                       :cursor "pointer"
                       :min-width "120px"}}
     label]))

(defn- open-in-new-tab-button
  []
  [:button
   {:data-testid "rf-causa-share-modal-open-new-tab"
    :on-click    #(rf/dispatch [:rf.causa/open-share-url-in-new-tab]
                               {:frame :rf/causa})
    :style       {:background "transparent"
                  :border (str "1px solid " (:border-default tokens))
                  :color (:text-primary tokens)
                  :font-family sans-stack
                  :font-size "12px"
                  :padding "8px 14px"
                  :border-radius "4px"
                  :cursor "pointer"}}
   "Open in new tab"])

(defn- reset-button
  []
  [:button
   {:data-testid "rf-causa-share-modal-reset"
    :on-click    (fn [_]
                   ;; Reset selection + scrubber so the URL collapses
                   ;; to the bare sentinel. (rf2-y9xmf: forced-mode is
                   ;; gone — the panel is event-driven, no Mode A/B/C.)
                   (rf/dispatch [:rf.causa/clear-machine-selection]
                                {:frame :rf/causa})
                   (rf/dispatch [:rf.causa/set-scrubber-position :present]
                                {:frame :rf/causa}))
    :style       {:background "transparent"
                  :border (str "1px solid " (:border-subtle tokens))
                  :color (:text-tertiary tokens)
                  :font-family sans-stack
                  :font-size "12px"
                  :padding "8px 14px"
                  :border-radius "4px"
                  :cursor "pointer"
                  :margin-left "auto"}}
   "Reset state"])

(defn- state-summary
  [share-state]
  [:div {:data-testid "rf-causa-share-modal-state-summary"
         :style       {:padding "10px 12px"
                       :background (:bg-2 tokens)
                       :border (str "1px solid " (:border-subtle tokens))
                       :border-radius "4px"
                       :font-family mono-stack
                       :font-size "11px"
                       :color (:text-secondary tokens)
                       :line-height 1.7}}
   [:div
    [:span {:style {:color (:text-tertiary tokens)}}
     "machine "]
    [:strong {:style {:color (:text-primary tokens)}}
     (if (:machine-id share-state) (str (:machine-id share-state)) "(none)")]]
   [:div
    [:span {:style {:color (:text-tertiary tokens)}}
     "mode    "]
    [:strong {:style {:color (:text-primary tokens)}}
     (if (:mode share-state) (str (:mode share-state)) "auto")]]
   [:div
    [:span {:style {:color (:text-tertiary tokens)}}
     "tab     "]
    [:strong {:style {:color (:text-primary tokens)}}
     (if (:tab share-state) (str (:tab share-state)) "(default)")]]
   [:div
    [:span {:style {:color (:text-tertiary tokens)}}
     "pos     "]
    [:strong {:style {:color (:text-primary tokens)}}
     (cond
       (= :present (:position share-state)) "present"
       (integer? (:position share-state))   (str "step " (:position share-state))
       :else                                 "present")]]])

;; ---- main view ----------------------------------------------------------

(defn share-dialog
  "The Share dialog body. Pure hiccup."
  []
  (let [share-state @(rf/subscribe [:rf.causa/share-state])
        share-url   @(rf/subscribe [:rf.causa/share-url])
        copy-status @(rf/subscribe [:rf.causa/share-copy-status])]
    [:div {:data-testid "rf-causa-share-modal-dialog"
           :on-click    (fn [e] (.stopPropagation e))
           :on-key-down (fn [^js e]
                          (when (= "Escape" (.-key e))
                            (rf/dispatch [:rf.causa/share-modal-close]
                                         {:frame :rf/causa})))
           :style       (dialog-style)}
     (header)
     (description)
     (state-summary share-state)
     (url-input share-url)
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "8px"
                    :flex-wrap "wrap"}}
      (copy-button copy-status)
      (open-in-new-tab-button)
      (reset-button)]]))

(rf/reg-view Modal
  "The Share modal. Renders only when `:rf.causa/share-modal-open?`
  is true; closed-state is one subscribe + a `when` — cheap.

  Per rf2-in6l2 `reg-view`-registered so the body's subscribes
  resolve through the React-context tier to `:rf/causa`."
  []
  (when @(rf/subscribe [:rf.causa/share-modal-open?])
    (let [positioning @(rf/subscribe [:rf.causa/modal-positioning])]
      [:div {:data-testid "rf-causa-share-modal-backdrop"
             :data-rf-causa-modal-positioning (name (or positioning :fixed))
             :on-click    #(rf/dispatch [:rf.causa/share-modal-close]
                                        {:frame :rf/causa})
             :style       (backdrop-style positioning)}
       (share-dialog)])))
