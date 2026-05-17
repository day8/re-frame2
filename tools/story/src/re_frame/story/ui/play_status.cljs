(ns re-frame.story.ui.play-status
  "Toolbar chip + failure-banner UI for Story's rich `:play-script`
  runner (rf2-8i2a9).

  ## What this renders

  Two surfaces:

  1. `(chip variant-id)`   — small toolbar widget showing the run
     status (`IDLE` / `RUNNING (step 3/8)` / `PASS` / `FAIL (2/8)`)
     + a `[Re-run]` button. Lives next to the dispatch-console chip
     in `toolbar/toolbar-strip`.
  2. `(banner variant-id)` — red strip across the top of the variant
     canvas when the most recent run is in `:fail` state. Lists the
     first failed assertion + a click-to-highlight affordance for
     `:assert-dom` failures.

  Both components deref `runner-events/run-state` so Reagent re-renders
  observe every step transition. Production CLJS builds DCE the file
  entirely via `re-frame.story.config/enabled?`."
  (:require [re-frame.story.config           :as config]
            [re-frame.story.play.dom         :as dom]
            [re-frame.story.play.runner      :as runner]
            [re-frame.story.play.runner-events :as runner-events]))

;; ---- styles ---------------------------------------------------------------

(def ^:private styles
  {:chip-base   {:padding         "3px 8px"
                 :background      "#37373d"
                 :color           "#cccccc"
                 :border          "none"
                 :border-radius   "10px"
                 :cursor          "pointer"
                 :font-family     "monospace"
                 :font-size       "11px"
                 :user-select     "none"
                 :display         "inline-flex"
                 :align-items     "center"
                 :gap             "6px"}
   :chip-idle    {}
   :chip-running {:background "#3a3a00"
                  :color      "#fae766"}
   :chip-pass    {:background "#1c4a1c"
                  :color      "#7be07b"}
   :chip-fail    {:background "#5a1d1d"
                  :color      "#fda3a3"}
   :chip-icon    {:font-size "10px"}
   :re-run-btn   {:padding         "2px 6px"
                  :background      "#264f78"
                  :color           "white"
                  :border          "1px solid #264f78"
                  :border-radius   "3px"
                  :cursor          "pointer"
                  :font-family     "monospace"
                  :font-size       "10px"
                  :margin-left     "4px"}
   :banner       {:background     "#5a1d1d"
                  :color          "#fde0e0"
                  :padding        "8px 14px"
                  :font-family    "monospace"
                  :font-size      "12px"
                  :border-bottom  "2px solid #be4040"
                  :display        "flex"
                  :align-items    "flex-start"
                  :gap            "12px"}
   :banner-title {:font-weight "bold"
                  :flex        "0 0 auto"}
   :banner-text  {:flex "1 1 auto"
                  :overflow "hidden"
                  :text-overflow "ellipsis"
                  :white-space "nowrap"}
   :banner-btn   {:padding         "2px 8px"
                  :background      "transparent"
                  :color           "#fde0e0"
                  :border          "1px solid #fde0e0"
                  :border-radius   "3px"
                  :cursor          "pointer"
                  :font-family     "monospace"
                  :font-size       "10px"}})

;; ---- chip helpers ---------------------------------------------------------

(defn- chip-style-for
  [status]
  (merge (:chip-base styles)
         (case status
           :idle    (:chip-idle    styles)
           :running (:chip-running styles)
           :pass    (:chip-pass    styles)
           :fail    (:chip-fail    styles)
           {})))

(defn- chip-icon
  [status]
  (case status
    :running "Running"
    :pass    "Pass"
    :fail    "Fail"
    :idle    "Idle"
    (str status)))

(defn chip-label
  "Pure helper — render the chip's text content for a given run state.
  Exposed for unit tests."
  [state]
  (if (nil? state)
    "Play: IDLE"
    (str "Play: " (runner/progress-str state))))

;; ---- click-to-highlight ---------------------------------------------------

(defn- highlight-failure-node!
  "Best-effort: locate the selector from the first DOM-class failure
  and scroll-into-view + flash a temporary outline. CLJS-only."
  [selector]
  (when (and selector (dom/dom-available?))
    (when-let [node (dom/query selector)]
      (try
        (.scrollIntoView node #js {:behavior "smooth" :block "center"})
        (let [orig (.. node -style -outline)]
          (set! (.. node -style -outline) "3px solid #be4040")
          (js/setTimeout
            (fn []
              (try
                (set! (.. node -style -outline) (or orig ""))
                (catch :default _ nil)))
            1500))
        (catch :default _ nil)))))

;; ---- chip component -------------------------------------------------------

(defn chip
  "Render the play-status chip for `variant-id`. Form-1 component; the
  outer `runner-events/run-state` deref drives re-renders. Tagged with
  `data-test=\"story-play-status\"` for browser tests."
  [variant-id]
  (let [state  (get @runner-events/run-state variant-id)
        status (or (:status state) :idle)
        label  (chip-label state)]
    [:span {:style      (chip-style-for status)
            :data-test  "story-play-status"
            :data-variant (pr-str variant-id)
            :data-status  (name status)
            :title      (str "Play status — " label)
            :role       "status"
            :aria-live  "polite"}
     [:span {:style (:chip-icon styles)} (chip-icon status)]
     [:span label]
     [:button
      {:style     (:re-run-btn styles)
       :data-test "story-play-status-re-run"
       :title     "Re-run the play script"
       :on-click  (fn [e]
                    (.stopPropagation e)
                    (runner-events/re-run! variant-id))}
      "Re-run"]]))

;; ---- failure banner -------------------------------------------------------

(defn banner-text
  "Pure helper — render the failure-banner message string. Returns nil
  when the run is not in `:fail` state. Exposed for unit tests."
  [state]
  (when (and state (= :fail (:status state)))
    (let [{:keys [count first]} (runner/fail-summary state)
          summary (runner/step-summary (:step first))
          msg     (:message first)]
      (str count " failure" (when (not= 1 count) "s") " — step "
           (inc (:idx first)) ": " summary
           (when msg (str " — " msg))))))

(defn banner
  "Render the failure banner for `variant-id`. Returns nil when the
  most recent run did not fail. Tagged with
  `data-test=\"story-play-banner\"`."
  [variant-id]
  (let [state (get @runner-events/run-state variant-id)]
    (when (banner-text state)
      (let [{:keys [first]} (runner/fail-summary state)
            sel (runner/step-selector (:step first))]
        [:div {:style     (:banner styles)
               :role      "alert"
               :data-test "story-play-banner"}
         [:span {:style (:banner-title styles)} "PLAY FAIL"]
         [:span {:style (:banner-text styles)} (banner-text state)]
         (when sel
           [:button {:style    (:banner-btn styles)
                     :data-test "story-play-banner-highlight"
                     :title    (str "Highlight " sel)
                     :on-click (fn [_] (highlight-failure-node! sel))}
            "Highlight"])
         [:button {:style    (:banner-btn styles)
                   :data-test "story-play-banner-re-run"
                   :title    "Re-run the play script"
                   :on-click (fn [_] (runner-events/re-run! variant-id))}
          "Re-run"]]))))

;; ---- production-elision wrapper ------------------------------------------

(defn chip-when-enabled
  "Render `chip` only when the Story config is enabled AND the variant
  has a `:play-script` body. Used by the toolbar so production builds
  + variants without a script don't render the chip at all."
  [variant-id]
  (when (and config/enabled? variant-id)
    (let [spec (runner-events/variant-play-script variant-id)]
      (when (seq (:script spec))
        [chip variant-id]))))

(defn banner-when-enabled
  "Render `banner` only when the Story config is enabled AND the
  variant carries a play-script. The banner self-hides when the run
  is not in `:fail` — `chip-when-enabled` mirrors that check at the
  spec-presence layer."
  [variant-id]
  (when (and config/enabled? variant-id)
    (let [spec (runner-events/variant-play-script variant-id)]
      (when (seq (:script spec))
        [banner variant-id]))))
