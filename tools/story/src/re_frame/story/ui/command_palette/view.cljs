(ns re-frame.story.ui.command-palette.view
  "Global Cmd-K / Ctrl-K command palette for the Story shell."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.story.config :as config]
            [re-frame.story.ui.command-palette :as palette]
            [re-frame.story.ui.state :as state]
            [re-frame.story.ui.toolbar :as toolbar]
            [re-frame.story.theme.typography :refer [mono-stack]]))

(defn shortcut-event?
  "True when `event` is Story's global command-palette shortcut."
  [event]
  (and (= "keydown" (.-type event))
       (= "k" (str/lower-case (or (.-key event) "")))
       (or (.-metaKey event) (.-ctrlKey event))
       (not (.-altKey event))
       (not (.-shiftKey event))))

(defn- focus-input! [input]
  (when-let [node @input]
    (.setTimeout js/window #(.focus node) 0)))

(defn select-entry!
  "Apply a palette entry to shell state, then return true when handled.

  Variants and workspaces are navigable. Stories jump to their first
  registered child variant when one exists. Modes reuse the toolbar
  toggle so persistence stays in one place. Decorators are registry
  data only in the MVP, so selecting them simply closes the palette."
  [entry]
  (case (:kind entry)
    :variant
    (do
      (state/swap-state!
        (fn [s]
          (-> s
              (state/select-variant (:id entry))
              (state/select-workspace nil))))
      true)

    :workspace
    (do
      (state/swap-state!
        (fn [s]
          (-> s
              (state/select-workspace (:id entry))
              (state/select-variant nil))))
      true)

    :story
    (when-let [variant-id (first (:variant-ids entry))]
      (state/swap-state!
        (fn [s]
          (-> s
              (state/select-variant variant-id)
              (state/select-workspace nil))))
      true)

    :mode
    (do
      (toolbar/toggle-mode! (:id entry))
      true)

    :decorator
    true

    true))

(def ^:private styles
  {:scrim       {:position "fixed"
                 :inset "0"
                 :z-index 100000
                 :background "rgba(0, 0, 0, 0.42)"
                 :display "flex"
                 :justify-content "center"
                 :align-items "flex-start"
                 :padding-top "12vh"}
   :panel       {:width "min(680px, calc(100vw - 32px))"
                 :max-height "68vh"
                 :background "#1f1f23"
                 :border "1px solid #4a4a52"
                 :border-radius "12px"
                 :box-shadow "0 24px 80px rgba(0, 0, 0, 0.5)"
                 :overflow "hidden"
                 :font-family mono-stack
                 :color "#f2f2f2"}
   :input-wrap  {:border-bottom "1px solid #3b3b42"
                 :padding "12px"}
   :input       {:width "100%"
                 :box-sizing "border-box"
                 :background "#2b2b31"
                 :border "1px solid #55555f"
                 :border-radius "8px"
                 :color "#ffffff"
                 :font-size "15px"
                 :outline "none"
                 :padding "10px 12px"}
   :list        {:max-height "calc(68vh - 64px)"
                 :overflow-y "auto"
                 :padding "6px"}
   :row         {:display "grid"
                 :grid-template-columns "92px 1fr"
                 :gap "12px"
                 :padding "9px 10px"
                 :border-radius "8px"
                 :cursor "pointer"}
   :row-active  {:background "#3a3a43"}
   :kind        {:font-size "11px"
                 :text-transform "uppercase"
                 :letter-spacing "0.08em"
                 :color "#aeb4c0"
                 :padding-top "2px"}
   :id          {:font-size "13px"
                 :color "#ffffff"}
   :doc         {:font-size "12px"
                 :color "#b8b8c0"
                 :line-height "1.35"
                 :margin-top "2px"
                 :white-space "nowrap"
                 :overflow "hidden"
                 :text-overflow "ellipsis"}
   :empty       {:padding "24px"
                 :text-align "center"
                 :color "#9a9aa3"
                 :font-size "13px"}})

(defn- result-row [entry active? on-hover on-select]
  ^{:key [(:kind entry) (:id entry)]}
  [:div {:style         (merge (:row styles) (when active? (:row-active styles)))
         :role          "option"
         :aria-selected (str (boolean active?))
         :data-test     "story-command-palette-result"
         :data-kind     (name (:kind entry))
         :data-id       (:id-label entry)
         :on-mouse-enter on-hover
         :on-click      on-select}
   [:div {:style (:kind styles)} (:kind-label entry)]
   [:div
    [:div {:style (:id styles)} (:id-label entry)]
    (when (seq (:doc entry))
      [:div {:style (:doc styles)} (:doc entry)])]])

(defn command-palette-host
  "Install the global shortcut and render the floating palette overlay."
  []
      (let [open?        (r/atom false)
        query        (r/atom "")
        active-index (r/atom 0)
        input        (atom nil)
        listener     (atom nil)
        close!       (fn []
                       (reset! open? false)
                       (reset! query "")
                       (reset! active-index 0))
        open!        (fn []
                       (reset! open? true)
                       (reset! active-index 0)
                       (focus-input! input))]
    (r/create-class
      {:display-name "rf-story-command-palette"
       :component-did-mount
       (fn [_]
         (when config/enabled?
           (let [f (fn [event]
                     (cond
                       (shortcut-event? event)
                       (do
                         (.preventDefault event)
                         (if @open? (close!) (open!)))

                       (and @open? (= "Escape" (.-key event)))
                       (do
                         (.preventDefault event)
                         (close!))))]
             (reset! listener f)
             (.addEventListener js/window "keydown" f true))))
       :component-will-unmount
       (fn [_]
         (when-let [f @listener]
           (.removeEventListener js/window "keydown" f true)
           (reset! listener nil)))
       :reagent-render
       (fn []
         (when @open?
           (let [results (palette/search
                           (palette/entries (state/registry-snapshot))
                           @query
                           30)
                 count   (count results)
                 active  (palette/clamp-active-index @active-index count)]
             (when (not= active @active-index)
               (reset! active-index active))
             [:div {:style     (:scrim styles)
                    :data-test "story-command-palette"
                    :on-click  (fn [_] (close!))}
              [:div {:style    (:panel styles)
                     :role     "dialog"
                     :aria-modal "true"
                     :aria-label "Story command palette"
                     :on-click (fn [event] (.stopPropagation event))}
               [:div {:style (:input-wrap styles)}
                [:input {:ref          #(reset! input %)
                         :style        (:input styles)
                         :value        @query
                         :placeholder  "Search stories, variants, workspaces, modes, decorators..."
                         :data-test    "story-command-palette-input"
                         :on-change    (fn [event]
                                         (reset! query (.. event -target -value))
                                         (reset! active-index 0))
                         :on-key-down  (fn [event]
                                         (case (.-key event)
                                           "ArrowDown"
                                           (do (.preventDefault event)
                                               (swap! active-index
                                                      palette/move-active-index 1 count))
                                           "ArrowUp"
                                           (do (.preventDefault event)
                                               (swap! active-index
                                                      palette/move-active-index -1 count))
                                           "Enter"
                                           (do (.preventDefault event)
                                               (when-let [entry (get results active)]
                                                 (select-entry! entry)
                                                 (close!)))
                                           "Escape"
                                           (do (.preventDefault event)
                                               (close!))
                                           nil))}]]
               [:div {:style (:list styles)
                      :role  "listbox"}
                (if (seq results)
                  (doall
                    (map-indexed
                      (fn [idx entry]
                        (result-row entry
                                    (= idx active)
                                    #(reset! active-index idx)
                                    #(do (select-entry! entry)
                                         (close!))))
                      results))
                  [:div {:style (:empty styles)
                         :data-test "story-command-palette-empty"}
                   "No matching registry entries."])]]])))})))
