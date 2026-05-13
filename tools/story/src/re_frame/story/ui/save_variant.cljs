(ns re-frame.story.ui.save-variant
  "Save-current-canvas-state-as-new-variant UI — the SB9 'Save' affordance
  (rf2-one3t). The pure machinery lives in
  `re-frame.story.save-variant`; this ns wires the Reagent ratom, the
  controls-panel button, and the modal dialog around it.

  ## Surface

  - `(save-variant-button variant-id)` — the controls-panel button.
    Disabled when no variant is focused; on click captures the live args
    snapshot and opens the dialog.
  - `(save-dialog)` — the modal that previews the generated
    `(reg-variant ...)` form with a copy-to-clipboard affordance. The
    user edits the new variant id inline; the snippet re-generates on
    every keystroke. Discard / close drop the snapshot.

  ## Why a separate UI ns

  Mirrors the recorder split (`re-frame.story.recorder` pure +
  `re-frame.story.ui.recorder` CLJS-only). The dialog ratom + DOM
  interactions live here; the pure snippet generator + state-machine
  transitions stay JVM-testable in `re-frame.story.save-variant`.

  ## Elision

  Every public fn opens with `(when config/enabled? ...)` so production
  CLJS builds short-circuit before any DOM call. The UI-callback wiring
  via `install!` is the only impure registration the ns owns; idempotent
  on re-mount."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.story.config       :as config]
            [re-frame.story.save-variant :as save-variant]
            [re-frame.story.ui.state     :as state]))

;; ---------------------------------------------------------------------------
;; Dialog ratom — the impure mirror of `save-variant/initial-dialog-state`.
;;
;; The pure transitions in `save-variant` produce new state maps; the
;; UI swaps the ratom around them so Reagent re-renders the modal on
;; every keystroke.
;; ---------------------------------------------------------------------------

(defonce ui-dialog
  (r/atom save-variant/initial-dialog-state))

(defn- open-dialog!
  "Open the dialog against `source-variant-id` with the captured
  `args-snapshot`. `now-ms` seeds the default id."
  [source-variant-id args-snapshot now-ms]
  (swap! ui-dialog save-variant/open source-variant-id args-snapshot now-ms))

(defn- close-dialog! []
  (swap! ui-dialog save-variant/close))

(defn- set-draft-id! [s]
  (let [k (try
            (let [stripped (cond-> s
                             (and (string? s)
                                  (re-find #"^:" s))
                             (subs 1))]
              (when (and (string? stripped) (seq stripped))
                (if (re-find #"/" stripped)
                  (let [[ns nm] (str/split stripped #"/" 2)]
                    (keyword ns nm))
                  (keyword stripped))))
            (catch :default _ nil))]
    (swap! ui-dialog save-variant/set-draft-id (or k s))))

;; ---------------------------------------------------------------------------
;; Install — register the CLJS open-callback against the pure ns so
;; `save-variant/save-current-as-variant!` can drive the dialog without
;; coupling the .cljc helper to Reagent / DOM. Idempotent.
;; ---------------------------------------------------------------------------

(defn install!
  "Register the dialog-open callback against the pure ns. Called once at
  shell mount; idempotent."
  []
  (when config/enabled?
    (save-variant/set-open-dialog-fn! open-dialog!)
    nil))

;; ---------------------------------------------------------------------------
;; Styling — mirrors the recorder's dialog so the two save-as flows
;; (record-as-:play + snapshot-as-:args) share visual language.
;; ---------------------------------------------------------------------------

(def ^:private styles
  {:button       {:padding         "4px 8px"
                  :background      "#0e639c"
                  :color           "white"
                  :border          "none"
                  :border-radius   "3px"
                  :cursor          "pointer"
                  :font-size       "10px"
                  :margin-top      "8px"
                  :font-family     "monospace"}
   :button-disabled {:padding       "4px 8px"
                     :background    "#2d2d30"
                     :color         "#777"
                     :border        "1px solid #444"
                     :border-radius "3px"
                     :cursor        "not-allowed"
                     :font-size     "10px"
                     :margin-top    "8px"
                     :font-family   "monospace"}
   :modal-back   {:position "fixed"
                  :top "0" :left "0" :right "0" :bottom "0"
                  :background "rgba(0,0,0,0.55)"
                  :z-index 1700
                  :display "flex"
                  :align-items "center"
                  :justify-content "center"}
   :modal        {:width        "640px"
                  :max-width    "90vw"
                  :max-height   "80vh"
                  :background   "#1e1e1e"
                  :color        "#ddd"
                  :border       "1px solid #444"
                  :border-radius "6px"
                  :padding      "16px"
                  :font-family  "monospace"
                  :font-size    "12px"
                  :display      "flex"
                  :flex-direction "column"
                  :gap          "12px"
                  :box-shadow   "0 12px 32px rgba(0,0,0,0.7)"
                  :overflow     "hidden"}
   :modal-title  {:font-weight "bold"
                  :color "#9cdcfe"
                  :font-size "13px"}
   :id-input     {:padding "6px 8px"
                  :background "#252526"
                  :color "white"
                  :border "1px solid #444"
                  :border-radius "3px"
                  :font-family "monospace"
                  :font-size "12px"
                  :width "100%"
                  :box-sizing "border-box"}
   :snippet      {:background "#0e0e10"
                  :color "#dcdcaa"
                  :padding "10px"
                  :border "1px solid #333"
                  :border-radius "4px"
                  :white-space "pre"
                  :overflow "auto"
                  :max-height "44vh"
                  :font-family "monospace"
                  :font-size "11px"
                  :line-height "1.45"
                  :flex "1 1 auto"}
   :btn-row      {:display "flex"
                  :gap "8px"
                  :justify-content "flex-end"}
   :btn          {:padding "5px 12px"
                  :background "#0e639c"
                  :color "white"
                  :border "none"
                  :border-radius "3px"
                  :cursor "pointer"
                  :font-family "monospace"
                  :font-size "11px"}
   :btn-muted    {:padding "5px 12px"
                  :background "transparent"
                  :color "#cccccc"
                  :border "1px solid #444"
                  :border-radius "3px"
                  :cursor "pointer"
                  :font-family "monospace"
                  :font-size "11px"}
   :hint         {:color "#9a9a9a"
                  :font-style "italic"
                  :font-size "10px"}})

;; ---------------------------------------------------------------------------
;; Controls-panel button
;;
;; Disabled when no variant is focused. Click captures the live snapshot
;; and opens the dialog.
;; ---------------------------------------------------------------------------

(defn save-variant-button
  "Render the 'Save current canvas state as new variant' button. Lives
  on the controls panel (per IMPL-SPEC §4 — the controls panel is the
  natural home for args-snapshot affordances; spec/005-SOTA-Features
  §Save-current-canvas-state-as-variant).

  Public so tests can introspect the button-level hiccup without driving
  the full controls panel."
  [variant-id]
  (let [enabled? (some? variant-id)]
    [:button
     {:style     (if enabled?
                   (:button styles)
                   (:button-disabled styles))
      :disabled  (not enabled?)
      :data-test "story-save-variant-button"
      :title     (if enabled?
                   "Capture the current canvas state as a new variant"
                   "Select a variant to capture its current state")
      :on-click  (fn [_]
                   (when enabled?
                     (save-variant/save-current-as-variant!
                       {:variant-id variant-id})))}
     "save as new variant…"]))

;; ---------------------------------------------------------------------------
;; Modal dialog — review-then-commit
;;
;; The user reviews the generated EDN snippet, edits the new variant id
;; inline, copies to clipboard, and pastes into source. Source is never
;; written directly — same as the recorder's save-as-variant dialog.
;; ---------------------------------------------------------------------------

(defn- copy-to-clipboard! [snippet]
  (try
    (when (and (exists? js/navigator) (.-clipboard js/navigator))
      (.writeText (.-clipboard js/navigator) snippet))
    (catch :default _ nil)))

(defn save-dialog
  "Render the save-variant modal. Visible iff `:open?` is true on the
  dialog ratom. The snippet re-generates on every keystroke as the user
  edits the new variant id; copy-to-clipboard surfaces the form for the
  user to paste into source.

  Public so tests can render the dialog hiccup directly after seeding
  the dialog ratom."
  []
  (let [{:keys [open? draft-id source-id args]} @ui-dialog]
    (when open?
      (let [snippet (save-variant/gen-variant-snippet
                      {:variant-id (or draft-id :story.saved/example)
                       :extends    source-id
                       :args       args})]
        [:div {:style (:modal-back styles)
               :data-test "story-save-variant-dialog"
               :on-click  (fn [e]
                            (when (= (.-target e) (.-currentTarget e))
                              (close-dialog!)))}
         [:div {:style (:modal styles)
                :on-click (fn [e] (.stopPropagation e))}
          [:div {:style (:modal-title styles)}
           "Save current canvas state as new variant"]
          [:div {:style (:hint styles)}
           "Args snapshot captured from "
           (pr-str source-id)
           " — edit the new variant id and copy + paste into your "
           "stories namespace. Source is never written directly."]
          [:input
           {:type          "text"
            :style         (:id-input styles)
            :data-test     "story-save-variant-id-input"
            :default-value (pr-str (or draft-id :story.saved/example))
            :on-change     (fn [e] (set-draft-id! (.. e -target -value)))
            :placeholder   ":story.your-story/saved-flow"}]
          [:pre {:style     (:snippet styles)
                 :data-test "story-save-variant-snippet"}
           snippet]
          [:div {:style (:btn-row styles)}
           [:button
            {:style     (:btn styles)
             :data-test "story-save-variant-copy"
             :on-click  (fn [_] (copy-to-clipboard! snippet))}
            "copy to clipboard"]
           [:button
            {:style    (:btn-muted styles)
             :data-test "story-save-variant-close"
             :on-click (fn [_] (close-dialog!))}
            "close"]]]]))))
