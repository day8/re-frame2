(ns re-frame.story.ui.test-mode.stepper-view
  "Play step-debugger UI for the `:test` mode pane (rf2-ulw5m + spec/009
  §Play step-debugger).

  Storybook's Interactions panel ships a step / pause / rewind /
  breakpoint UI over the variant's `play()` sequence; this is Story's
  parity surface. The runtime substrate (`re-frame.story.play`) was
  already in place — only the UI was missing. Closing this gap is the
  highest-leverage Storybook-parity win in the Phase-2 SOTA refinement
  (see `ai/findings/2026-05-17-story-vs-storybook-deep-dive.md` §8 R1).

  ## Surface

  - **Start / Stop** — begin a step-by-step run; tearing down on Stop.
  - **Step (forward)** — advance one play event; the canvas re-renders
    against the post-dispatch app-db.
  - **Step-back** — restore the previous epoch; cursor decrements.
  - **Pause / Resume** — toggle auto-play (default 600ms between steps).
  - **Rewind** — restore the pre-play app-db and reset the cursor to 0.
  - **Breakpoints** — click any row's BP chip to toggle a breakpoint;
    auto-play pauses BEFORE the next event at that index.

  ## Keyboard equivalents

  When the section is focused (Tab into it or click anywhere inside):

  | Key      | Action               |
  |----------|----------------------|
  | Space    | Toggle pause/resume  |
  | →        | Step forward         |
  | ←        | Step back            |
  | R        | Rewind to step 0     |
  | Esc      | Stop (tear down)     |

  ## Read-only contract

  The stepper mutates **runtime state** (re-allocates + drives the
  variant frame) but not the variant's authoring shape — same posture
  as the Re-run button (spec/009)."
  (:require [reagent.core                                  :as r]
            [re-frame.story.ui.test-mode.stepper-pure      :as pure]
            [re-frame.story.ui.test-mode.stepper-state     :as state]
            [re-frame.story.ui.test-mode.stepper-styles    :refer [styles]]))

;; ---- glyphs -------------------------------------------------------------

(defn- position-glyph
  "Glyph for the position column. Done rows show the outcome glyph;
  pending shows the empty bullet; current shows the play arrow."
  [{:keys [position outcome]}]
  (case position
    :current "▶"
    :pending "○"
    :done    (case outcome
               :pass  "✓"
               :fail  "✗"
               :skip  "⊘"
               :event "•"
               "•")
    "·"))

(defn- outcome-style [{:keys [outcome]}]
  (case outcome
    :pass  (:outcome-pass styles)
    :fail  (:outcome-fail styles)
    :skip  (:outcome-skip styles)
    :event (:outcome-event styles)
    nil))

;; ---- control row --------------------------------------------------------

(defn- ctrl-btn
  "Render one control button as a plain hiccup vector (no component fn
  indirection — keeps the rendered tree directly inspectable by the
  CLJS test corpus without booting a Reagent root)."
  [{:keys [label data-test on-click enabled? style-key armed? title]}]
  [:button
   {:type           "button"
    :data-test      data-test
    :title          (or title label)
    :aria-label     (or title label)
    :disabled       (not enabled?)
    :style          (cond-> (case style-key
                              :primary (:ctrl-btn styles)
                              :soft    (:ctrl-btn-soft styles)
                              (:ctrl-btn-soft styles))
                      armed?         (merge (:ctrl-btn-armed styles))
                      (not enabled?) (merge (:ctrl-btn-disabled styles)))
    :on-click       (fn [e]
                      (.stopPropagation e)
                      (when enabled? (on-click)))}
   label])

(defn- controls-strip
  [variant-id slot]
  (let [{:keys [active? auto-playing?]} slot
        step?       (pure/can-step? slot)
        back?       (pure/can-step-back? slot)
        rewind?     (pure/can-rewind? slot)
        pause?      (pure/can-pause? slot)
        resume?     (pure/can-resume? slot)]
    (into
      [:div {:style     (:ctrl-row styles)
             :data-test "story-stepper-controls"}
       (if-not active?
         (ctrl-btn
           {:label     "Start"
            :data-test "story-stepper-start"
            :title     "Start a step-by-step play run (re-allocates the frame)"
            :enabled?  true
            :style-key :primary
            :on-click  (fn [] (state/begin! variant-id))})
         (ctrl-btn
           {:label     "Stop"
            :data-test "story-stepper-stop"
            :title     "Stop the stepper (tear down, return to Re-run mode)"
            :enabled?  true
            :style-key :soft
            :on-click  (fn [] (state/end! variant-id))}))]
      (when active?
        [[:div {:style (:ctrl-divider styles)}]
         (ctrl-btn
           {:label     "← Back"
            :data-test "story-stepper-step-back"
            :title     "Step back one event (restores the prior epoch)"
            :enabled?  back?
            :style-key :soft
            :on-click  (fn [] (state/step-back! variant-id))})
         (ctrl-btn
           {:label     "Step →"
            :data-test "story-stepper-step"
            :title     "Dispatch the next play event"
            :enabled?  step?
            :style-key :primary
            :on-click  (fn [] (state/step! variant-id))})
         [:div {:style (:ctrl-divider styles)}]
         (if auto-playing?
           (ctrl-btn
             {:label     "Pause"
              :data-test "story-stepper-pause"
              :title     "Pause auto-play (Space)"
              :enabled?  pause?
              :armed?    true
              :style-key :soft
              :on-click  (fn [] (state/pause! variant-id))})
           (ctrl-btn
             {:label     "▶ Play"
              :data-test "story-stepper-resume"
              :title     "Auto-play (Space)"
              :enabled?  resume?
              :style-key :soft
              :on-click  (fn [] (state/resume! variant-id))}))
         [:div {:style (:ctrl-divider styles)}]
         (ctrl-btn
           {:label     "↺ Rewind"
            :data-test "story-stepper-rewind"
            :title     "Restore the pre-play state (R)"
            :enabled?  rewind?
            :style-key :soft
            :on-click  (fn [] (state/rewind! variant-id))})]))))

;; ---- step row -----------------------------------------------------------

(defn- step-row-hiccup
  "One row in the step list. Clickable BP chip toggles a breakpoint at
  the row's index. Returns a plain hiccup vector so the test corpus
  can inspect the tree without rendering."
  [variant-id {:keys [index label position breakpoint?] :as row}]
  (let [bp?     breakpoint?
        current? (= position :current)
        row-style (cond-> (:step-row styles)
                    (= position :done)    (merge (:step-row-done styles))
                    (= position :pending) (merge (:step-row-pending styles))
                    current?              (merge (:step-row-current styles))
                    bp?                   (merge (:step-row-bp styles))
                    (and bp? current?)    (merge (:step-row-bp-current styles)))]
    [:div {:style       row-style
           :data-test   "story-stepper-row"
           :data-index  (str index)
           :data-position (name position)
           :data-breakpoint (str (boolean bp?))
           :on-click    (fn [_] (state/toggle-breakpoint! variant-id index))
           :title       (str "Click to toggle breakpoint at step " (inc index))}
     [:span {:style (merge (:step-glyph styles) (outcome-style row))}
      (position-glyph row)]
     [:span {:style (:step-index styles)} (str (inc index) ".")]
     [:span {:style (:step-label styles)} label]
     [:button {:type         "button"
               :style        (:step-bp-chip styles)
               :data-test    "story-stepper-bp-toggle"
               :data-index   (str index)
               :aria-pressed (if bp? "true" "false")
               :title        (if bp? "Remove breakpoint" "Set breakpoint")
               :on-click     (fn [e]
                               (.stopPropagation e)
                               (state/toggle-breakpoint! variant-id index))}
      (if bp? "● BP" "◯ BP")]]))

(defn- step-list
  [variant-id slot]
  (let [statuses (or (:statuses slot) [])]
    (when (seq statuses)
      (into [:div {:style     (:step-list styles)
                   :data-test "story-stepper-step-list"}]
            (mapv (fn [row] (step-row-hiccup variant-id row))
                  statuses)))))

;; ---- keyboard handling --------------------------------------------------

(defn- on-key-down
  "Keyboard handler bound to the section element. Translates well-known
  keys into mutator calls. Always preventDefault for the handled keys so
  Space doesn't scroll the page, arrows don't move the slider, etc."
  [variant-id]
  (fn [^js e]
    (let [k (.-key e)]
      (case k
        " "          (do (.preventDefault e)
                         (let [s (get @state/results-atom variant-id)]
                           (if (:auto-playing? s)
                             (state/pause! variant-id)
                             (state/resume! variant-id))))
        "ArrowRight" (do (.preventDefault e) (state/step! variant-id))
        "ArrowLeft"  (do (.preventDefault e) (state/step-back! variant-id))
        ("r" "R")    (do (.preventDefault e) (state/rewind! variant-id))
        "Escape"     (do (.preventDefault e) (state/end! variant-id))
        nil))))

;; ---- top-level component ------------------------------------------------

(defn- header-strip
  [slot]
  (let [{:keys [active? cursor total auto-playing?]} slot]
    [:div {:style (:section-header styles)}
     [:span {:style (:section-title styles)}
      "Step-debugger"
      (when active?
        [:span {:style     (:progress styles)
                :data-test "story-stepper-progress"}
         (str " · " (pure/progress-label cursor total)
              (when auto-playing? " · playing"))])]
     [:span {:style (:kbd-hint styles)}
      "Space pause · → step · ← back · R rewind"]]))

(defn render-section
  "Pure render: build the section hiccup from `variant-id` + the slot
  (a value, not the atom). Extracted from `stepper-section` so the
  CLJS test corpus can pin the hiccup shape without booting Reagent's
  class lifecycle."
  [variant-id slot]
  (let [active? (boolean (:active? slot))]
    [:div {:style       (:section styles)
           :data-test   "story-stepper-section"
           :data-active (str active?)
           :tab-index   "0"
           :on-key-down (on-key-down variant-id)
           :aria-label  "Play step-debugger"}
     (header-strip slot)
     (controls-strip variant-id slot)
     (if active?
       (step-list variant-id slot)
       [:div {:style     (:inactive styles)
              :data-test "story-stepper-inactive"}
        "Click Start to step through the :play sequence one event at a time."])]))

(defn stepper-section
  "Top-level component. Renders the step-debugger section for `variant-id`.

  When no stepper is active for the variant the section shows the Start
  button + a one-line hint; clicking Start re-allocates the variant
  frame and primes the substrate. From there the user can step / pause
  / rewind / step-back / toggle breakpoints.

  The component owns its keyboard handler — Tab into the section to
  drive it from the keyboard, or click any control to focus it.

  Per spec/009 §Read-only contract: the stepper mutates runtime state
  but not the variant's authoring shape."
  [variant-id]
  (when variant-id
    (r/create-class
      {:display-name "rf-story-play-stepper"
       :component-will-unmount
       (fn [_this]
         ;; If the user navigates away while a stepper is in flight,
         ;; tear it down so we don't leak the interval or the
         ;; substrate's per-frame state.
         (when (get @state/results-atom variant-id)
           (state/end! variant-id)))
       :reagent-render
       (fn [variant-id]
         (render-section variant-id (get @state/results-atom variant-id)))})))
