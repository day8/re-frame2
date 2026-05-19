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
  entirely via `re-frame.story.config/enabled?`.

  ## Multi-play (rf2-tl7zk)

  Variants that declare `:plays` (a vector of named plays) get a
  dropdown affordance inside the chip:

      [Play: happy path - PASS  v]  [Re-run]
                                 ^^^ click to open the dropdown
        +--------------------------+
        | happy path     - PASS   |
        | error path     - FAIL   |
        | edge case: 0   - IDLE   |
        | -------------------- |
        | Run all plays           |
        +--------------------------+

  Clicking a play row selects + runs it. Clicking 'Run all plays'
  runs every play sequentially, updating the chip as each one
  completes."
  (:require [reagent.core                    :as r]
            [re-frame.story.config           :as config]
            [re-frame.story.play.dom         :as dom]
            [re-frame.story.play.runner      :as runner]
            [re-frame.story.play.runner-events :as runner-events]
            [re-frame.story.theme.typography :refer [mono-stack]]))

;; ---- styles ---------------------------------------------------------------

(def ^:private styles
  {:chip-base   {:padding         "3px 8px"
                 :background      "#37373d"
                 :color           "#cccccc"
                 :border          "none"
                 :border-radius   "10px"
                 :cursor          "pointer"
                 :font-family     mono-stack
                 :font-size       "11px"
                 :user-select     "none"
                 :display         "inline-flex"
                 :align-items     "center"
                 :gap             "6px"
                 :position        "relative"}
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
                  :font-family     mono-stack
                  :font-size       "10px"
                  :margin-left     "4px"}
   :dropdown-btn {:padding         "0 4px"
                  :background      "transparent"
                  :color           "inherit"
                  :border          "none"
                  :cursor          "pointer"
                  :font-family     mono-stack
                  :font-size       "10px"
                  :line-height     "1"}
   :dropdown-panel {:position       "absolute"
                    :top            "100%"
                    :left           "0"
                    :margin-top     "4px"
                    :background     "#1e1e1e"
                    :color          "#cccccc"
                    :border         "1px solid #3c3c3c"
                    :border-radius  "4px"
                    :font-family    mono-stack
                    :font-size      "11px"
                    :min-width      "220px"
                    :max-width      "320px"
                    :z-index        "2147483646"
                    :box-shadow     "0 4px 12px rgba(0,0,0,0.5)"
                    :overflow       "hidden"}
   :dropdown-row {:padding         "6px 10px"
                  :cursor          "pointer"
                  :display         "flex"
                  :justify-content "space-between"
                  :align-items     "center"
                  :gap             "10px"
                  :border          "none"
                  :background      "transparent"
                  :color           "inherit"
                  :width           "100%"
                  :text-align      "left"
                  :font-family     mono-stack
                  :font-size       "11px"}
   :dropdown-row-active {:background "#264f78"
                         :color      "white"}
   :dropdown-row-name   {:overflow      "hidden"
                         :text-overflow "ellipsis"
                         :white-space   "nowrap"
                         :flex          "1 1 auto"}
   :dropdown-row-status {:flex          "0 0 auto"
                         :font-size     "9px"
                         :text-transform "uppercase"
                         :opacity       "0.85"}
   :dropdown-divider    {:height "1px" :background "#3c3c3c"}
   :dropdown-run-all    {:padding         "6px 10px"
                         :cursor          "pointer"
                         :display         "block"
                         :width           "100%"
                         :text-align      "left"
                         :border          "none"
                         :background      "transparent"
                         :color           "#7bbcff"
                         :font-family     mono-stack
                         :font-size       "11px"}
   :banner       {:background     "#5a1d1d"
                  :color          "#fde0e0"
                  :padding        "8px 14px"
                  :font-family    mono-stack
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
                  :font-family     mono-stack
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

(defn chip-label-multi
  "Pure helper — render the chip's text for a multi-play variant. The
  active play's name is included so the chip says which play the
  status reflects.

  Format: `Play <name> | IDLE|RUNNING|PASS|FAIL`.

  Exposed for unit tests."
  [state play-name]
  (let [status-str (if (nil? state)
                     "IDLE"
                     (runner/progress-str state))
        nm         (or play-name "(default)")]
    (str "Play " nm " | " status-str)))

(defn dropdown-row-status
  "Pure helper — render the per-row status badge text in the dropdown.
  Used by both the dropdown row and the unit-tests."
  [state]
  (if (nil? state)
    "IDLE"
    (case (:status state)
      :idle     "IDLE"
      :running  "RUN"
      :pass     "PASS"
      :fail     "FAIL"
      (str (:status state)))))

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

(defn- dropdown-panel
  "Render the dropdown listing each play + a 'Run all' option. CLJS-only;
  caller passes a fn `close!` to dismiss the panel + the active play
  key + the plays vector."
  [variant-id plays active-key close!]
  [:div {:style     (:dropdown-panel styles)
         :data-test "story-play-status-dropdown"
         :role      "menu"
         :on-click  (fn [e] (.stopPropagation e))}
   (for [[idx spec] (map-indexed vector plays)
         :let [pk     (:name spec)
               state  (runner-events/current-state-for-play variant-id pk)
               status (or (:status state) :idle)
               active? (= active-key pk)]]
     ^{:key (str pk "-" idx)}
     [:button
      {:style       (merge (:dropdown-row styles)
                           (chip-style-for status)
                           (when active? (:dropdown-row-active styles)))
       :data-test   "story-play-status-dropdown-row"
       :data-play   (str pk)
       :data-status (name status)
       :role        "menuitem"
       :title       (str "Run play: " (or pk "(default)"))
       :on-click    (fn [e]
                      (.stopPropagation e)
                      (close!)
                      (runner-events/run-play! variant-id pk))}
      [:span {:style (:dropdown-row-name styles)} (or pk "(unnamed)")]
      [:span {:style (:dropdown-row-status styles)} (dropdown-row-status state)]])
   [:div {:style (:dropdown-divider styles)}]
   [:button
    {:style     (:dropdown-run-all styles)
     :data-test "story-play-status-run-all"
     :role      "menuitem"
     :title     "Run every play in order"
     :on-click  (fn [e]
                  (.stopPropagation e)
                  (close!)
                  (runner-events/run-all-plays! variant-id))}
    (str "Run all (" (count plays) ")")]])

(defn chip
  "Render the play-status chip for `variant-id`. Form-2 component; the
  outer `runner-events/run-state` deref drives re-renders. Tagged with
  `data-test=\"story-play-status\"` for browser tests.

  rf2-tl7zk multi-play: when the variant declares `:plays` (more than
  one), the chip grows a `[v]` dropdown affordance that opens a panel
  listing each play with its per-play status + a 'Run all' option."
  [variant-id]
  (let [open? (r/atom false)]
    (fn [variant-id]
      (let [plays      (runner-events/variant-plays variant-id)
            multi?     (runner/multi? plays)
            active-key (or (runner-events/active-play-key variant-id)
                           (runner/default-play-key plays))
            ;; In multi-play mode, the chip's state reflects the
            ;; ACTIVE play's per-(variant, play) row; in single-play
            ;; mode it reflects the legacy single-script state.
            state      (if multi?
                         (runner-events/current-state-for-play variant-id active-key)
                         (get @runner-events/run-state variant-id))
            status     (or (:status state) :idle)
            active-spec (runner/find-play plays active-key)
            active-name (when active-spec (:name active-spec))
            label      (if multi?
                         (chip-label-multi state active-name)
                         (chip-label state))
            close!     (fn [] (reset! open? false))]
        [:span {:style      (chip-style-for status)
                :data-test  "story-play-status"
                :data-variant (pr-str variant-id)
                :data-status  (name status)
                :data-multi   (str multi?)
                :data-play    (str active-key)
                :title      (str "Play status — " label)
                :role       "status"
                :aria-live  "polite"}
         [:span {:style (:chip-icon styles)} (chip-icon status)]
         [:span label]
         (when multi?
           [:button
            {:style    (:dropdown-btn styles)
             :data-test "story-play-status-dropdown-toggle"
             :title    "Pick a play"
             :aria-haspopup "menu"
             :aria-expanded (str @open?)
             :on-click (fn [e]
                         (.stopPropagation e)
                         (swap! open? not))}
            (if @open? "^" "v")])
         (when (and multi? @open?)
           [dropdown-panel variant-id plays active-key close!])
         [:button
          {:style     (:re-run-btn styles)
           :data-test "story-play-status-re-run"
           :title     (str "Re-run "
                           (if multi?
                             (str "play: " (or active-name "(default)"))
                             "the play script"))
           :on-click  (fn [e]
                        (.stopPropagation e)
                        (runner-events/re-run! variant-id))}
          "Re-run"]]))))

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
  has a `:play-script` OR `:plays` body. Used by the toolbar so
  production builds + variants without a play surface don't render
  the chip at all.

  rf2-tl7zk: checks both single-script + multi-play surfaces via
  `variant-plays` (which resolves both)."
  [variant-id]
  (when (and config/enabled? variant-id)
    (let [plays (runner-events/variant-plays variant-id)]
      (when (some (fn [p] (seq (:script p))) plays)
        [chip variant-id]))))

(defn banner-when-enabled
  "Render `banner` only when the Story config is enabled AND the
  variant carries a play-script. The banner self-hides when the run
  is not in `:fail` — `chip-when-enabled` mirrors that check at the
  spec-presence layer."
  [variant-id]
  (when (and config/enabled? variant-id)
    (let [plays (runner-events/variant-plays variant-id)]
      (when (some (fn [p] (seq (:script p))) plays)
        [banner variant-id]))))
