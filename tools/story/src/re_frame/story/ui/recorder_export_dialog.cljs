(ns re-frame.story.ui.recorder-export-dialog
  "Recorder → :play-script export dialog UI (rf2-x9zsr).

  The recorder's existing save-as-variant dialog
  (`re-frame.story.ui.recorder/save-dialog`) emits the legacy `:play`
  body — a bare vector of event vectors that re-fires on mount. The
  rich `:play-script` DSL landed in rf2-8i2a9; this dialog is its
  twin — same recording, different output shape:

      (story/reg-variant :story.your/recorded
        {:extends     :story.your/source
         :play-script {:name      \"happy path\"
                       :auto-run? true
                       :script    [[:dispatch [:counter/inc]]
                                   [:dispatch-sync [:rf.assert/path-equals
                                                    [:n] 1]]]}})

  ## UX

  Opens off an `[Export as :play-script]` button on the existing
  recorder review dialog. The export dialog shows:

  - A `[Name]` text input — flows into the `:name` field of the
    `:play-script` map.
  - A `[Variant id]` text input — the new variant the form registers.
  - A `[x] Auto-assert app-db at end` checkbox — when on, trailing
    `[:assert-db <path> <expected>]` steps are derived from the
    variant's app-db at export time (capped at the translator's
    `default-max-auto-assertions`).
  - The rendered `(reg-variant ...)` form — pretty-printed, live
    re-rendered on every option change.
  - `[Copy to clipboard]` — primary action.
  - `[Replay in this story]` — drives the runner against the active
    frame so the user can verify the export before pasting.
  - `[Close]`.

  ## Pure / impure split

  All Reagent / DOM lives here. The pure translator
  (`re-frame.story.recorder.play-export`) and the impure runner-
  driver (`re-frame.story.recorder.play-export-events`) own the
  data-shape and the re-frame coupling respectively.

  ## Elision

  Every public fn opens with `(when config/enabled? ...)` so
  production CLJS builds short-circuit before any DOM call."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.story.config                        :as config]
            [re-frame.story.recorder                      :as recorder]
            [re-frame.story.recorder.play-export          :as export]
            [re-frame.story.recorder.play-export-events   :as export-events]
            [re-frame.story.review-dialog                 :as review-dialog]))

;; ---------------------------------------------------------------------------
;; Dialog state — a single Reagent ratom carrying the dialog's
;; configuration. The dialog opens off the existing recorder save-
;; dialog, so it snapshots the *recorded* events + variant-id when
;; opened (mirrors the rf2-8x9nb guard on the parent dialog — a
;; subsequent start-recording! cannot mutate the in-flight export).
;; ---------------------------------------------------------------------------

(def initial-state
  "Idle export-dialog state. Mirrors the shape `review-dialog/initial-
  state` carries plus the export-specific options."
  {:open?               false
   :source-id           nil   ; the recorded variant-id (rides into :extends)
   :events              []    ; captured events snapshot (legacy)
   :entries             []    ; rich :entries snapshot (rf2-d5u89)
   :variant-id          nil   ; the new variant id (user-editable)
   :name                ""    ; optional :name field on the play-script
   :auto-assert?        true
   :final-db            nil
   :replay-status       nil   ; nil | :running | :pass | :fail
   :replay-failure-msg  nil})

(defonce ui-dialog
  (r/atom initial-state))

(defn- close-dialog! []
  (reset! ui-dialog initial-state))

(defn open-dialog!
  "Open the export dialog. `opts`:

    :source-id        — the recorded variant-id (rides into `:extends`).
    :events           — captured events snapshot (legacy bare-vectors).
    :entries          — rich :entries snapshot (rf2-d5u89). When
                        non-empty, the translator consumes this in
                        preference to `:events` so DOM-events + per-
                        event timestamps flow through to the script.
    :variant-id       — default new-variant id (user-editable inline).
    :final-db         — optional app-db snapshot at recording-end;
                        consumed by the auto-assert option.

  Idempotent — opening twice replaces the in-flight state."
  [{:keys [source-id events entries variant-id final-db]}]
  (when config/enabled?
    (reset! ui-dialog
            (assoc initial-state
                   :open?      true
                   :source-id  source-id
                   :events     (vec (or events []))
                   :entries    (vec (or entries []))
                   :variant-id (or variant-id
                                   (when (qualified-keyword? source-id)
                                     (keyword (namespace source-id)
                                              "recorded-script"))
                                   :story.recorded/play-export)
                   :final-db   final-db))))

(defn- set-name! [s]
  (swap! ui-dialog assoc :name (str s)))

(defn- set-variant-id! [s]
  (let [parsed (review-dialog/parse-variant-id-string s)]
    (swap! ui-dialog assoc :variant-id (or parsed s))))

(defn- toggle-auto-assert! []
  (swap! ui-dialog update :auto-assert? not))

;; ---------------------------------------------------------------------------
;; Derived: build the export tuple from the current dialog state.
;;
;; Re-derived on every render so option toggles re-render the
;; preview without touching the dialog state shape.
;; ---------------------------------------------------------------------------

(defn- build-export-from-dialog
  [{:keys [events entries source-id variant-id name auto-assert? final-db]}]
  ;; Prefer the rich :entries snapshot when it carries anything —
  ;; that's where DOM-events + per-event timestamps live (rf2-d5u89).
  ;; Fall back to the legacy :events vector for back-compat.
  (let [src (if (seq entries) entries events)]
    (export-events/build-export
      src
      (cond-> {:variant-id variant-id
               :extends    source-id
               :auto-run?  true}
        (and (string? name) (seq name)) (assoc :name name)
        auto-assert?                     (assoc :auto-assert? true
                                                :final-db     final-db)))))

;; ---------------------------------------------------------------------------
;; Replay
;; ---------------------------------------------------------------------------

(defn- run-replay!
  "Drive the just-exported `:play-script` against the recorded variant's
  frame via the runner. Updates the dialog's `:replay-status` slot so
  the UI can surface the outcome."
  [{:keys [source-id]} spec]
  (when (and config/enabled? source-id spec)
    (swap! ui-dialog assoc :replay-status :running :replay-failure-msg nil)
    (export-events/replay-script!
      source-id spec
      (fn [final-state]
        (let [status (:status final-state)
              msg    (when (= :fail status)
                       (let [first-fail (some (fn [r]
                                                (when (false? (:passed? r)) r))
                                              (:results final-state))]
                         (:message first-fail)))]
          (swap! ui-dialog assoc
                 :replay-status      (case status :pass :pass :fail :fail :running)
                 :replay-failure-msg msg))))))

;; ---------------------------------------------------------------------------
;; Styles — mirrors the existing recorder save-dialog modal aesthetic
;; via the shared review-dialog styling. Local additions for the
;; export-specific affordances (checkbox row, name input, replay
;; status pill).
;; ---------------------------------------------------------------------------

(def ^:private styles
  {:back        {:position    "fixed"
                 :top "0" :left "0" :right "0" :bottom "0"
                 :background  "rgba(0,0,0,0.55)"
                 :z-index     1800
                 :display     "flex"
                 :align-items "center"
                 :justify-content "center"}
   :modal       {:width          "720px"
                 :max-width      "92vw"
                 :max-height     "86vh"
                 :background     "#1e1e1e"
                 :color          "#ddd"
                 :border         "1px solid #444"
                 :border-radius  "6px"
                 :padding        "16px"
                 :font-family    "monospace"
                 :font-size      "12px"
                 :display        "flex"
                 :flex-direction "column"
                 :gap            "12px"
                 :box-shadow     "0 14px 36px rgba(0,0,0,0.75)"
                 :overflow       "hidden"}
   :title       {:font-weight "bold"
                 :color       "#9cdcfe"
                 :font-size   "13px"}
   :hint        {:color "#9a9a9a"
                 :font-style "italic"
                 :font-size "10px"}
   :row         {:display "flex"
                 :gap "8px"
                 :align-items "center"}
   :label       {:color "#9cdcfe"
                 :font-size "11px"
                 :min-width "110px"}
   :input       {:padding "6px 8px"
                 :background "#252526"
                 :color "white"
                 :border "1px solid #444"
                 :border-radius "3px"
                 :font-family "monospace"
                 :font-size "12px"
                 :flex "1 1 auto"
                 :box-sizing "border-box"}
   :snippet     {:background    "#0e0e10"
                 :color         "#dcdcaa"
                 :padding       "10px"
                 :border        "1px solid #333"
                 :border-radius "4px"
                 :white-space   "pre"
                 :overflow      "auto"
                 :max-height    "40vh"
                 :font-family   "monospace"
                 :font-size     "11px"
                 :line-height   "1.45"
                 :flex          "1 1 auto"}
   :checkbox-row {:display "flex"
                  :gap "8px"
                  :align-items "center"}
   :btn-row     {:display "flex"
                 :gap "8px"
                 :justify-content "flex-end"
                 :flex-wrap "wrap"}
   :btn         {:padding "5px 12px"
                 :background "#0e639c"
                 :color "white"
                 :border "none"
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-family "monospace"
                 :font-size "11px"}
   :btn-muted   {:padding "5px 12px"
                 :background "transparent"
                 :color "#cccccc"
                 :border "1px solid #444"
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-family "monospace"
                 :font-size "11px"}
   :status-pill {:padding "2px 8px"
                 :border-radius "10px"
                 :font-size "10px"
                 :font-family "monospace"
                 :margin-left "auto"}
   :pill-pass   {:background "#1f5e2b" :color "#cdf7d4"}
   :pill-fail   {:background "#7a2020" :color "#ffd1d1"}
   :pill-running {:background "#0e639c" :color "white"}})

(defn- replay-pill
  [status msg]
  (case status
    :running  [:span {:style (merge (:status-pill styles) (:pill-running styles))
                      :data-test "story-recorder-export-replay-status"}
               "RUNNING"]
    :pass     [:span {:style (merge (:status-pill styles) (:pill-pass styles))
                      :data-test "story-recorder-export-replay-status"}
               "PASS"]
    :fail     [:span {:style (merge (:status-pill styles) (:pill-fail styles))
                      :data-test "story-recorder-export-replay-status"
                      :title (or msg "replay failed")}
               "FAIL"]
    nil))

;; ---------------------------------------------------------------------------
;; The dialog itself
;; ---------------------------------------------------------------------------

(defn export-dialog
  "Render the export dialog. Returns nil when `:open?` is false on
  `@ui-dialog` so the caller can mount it unconditionally next to the
  other shell modals."
  []
  (let [state  @ui-dialog]
    (when (:open? state)
      (let [{:keys [spec rendered]} (build-export-from-dialog state)
            {:keys [name source-id variant-id auto-assert?
                    replay-status replay-failure-msg]} state]
        [:div
         {:style     (:back styles)
          :data-test "story-recorder-export-dialog"
          :on-click  (fn [e]
                       (when (= (.-target e) (.-currentTarget e))
                         (close-dialog!)))}
         [:div {:style    (:modal styles)
                :on-click (fn [e] (.stopPropagation e))}
          [:div {:style (:title styles)}
           "Recorder → :play-script export"
           (replay-pill replay-status replay-failure-msg)]
          [:div {:style (:hint styles)}
           (str "Generated from " (count (:events state))
                " captured event" (when (not= 1 (count (:events state))) "s")
                " against " (pr-str source-id)
                ". Tweak the options, then copy + paste into your stories ns.")]

          ;; ---- Name ---------------------------------------------------------
          [:div {:style (:row styles)}
           [:label {:style (:label styles)} "Name"]
           [:input
            {:type        "text"
             :style       (:input styles)
             :data-test   "story-recorder-export-name-input"
             :placeholder "happy path"
             :value       name
             :on-change   (fn [e] (set-name! (.. e -target -value)))}]]

          ;; ---- Variant id ---------------------------------------------------
          [:div {:style (:row styles)}
           [:label {:style (:label styles)} "Variant id"]
           [:input
            {:type          "text"
             :style         (:input styles)
             :data-test     "story-recorder-export-variant-id-input"
             :placeholder   ":story.your/recorded-flow"
             :default-value (pr-str variant-id)
             :on-change     (fn [e] (set-variant-id! (.. e -target -value)))}]]

          ;; ---- Auto-assert toggle ------------------------------------------
          [:label
           {:style     (:checkbox-row styles)
            :data-test "story-recorder-export-auto-assert"}
           [:input
            {:type      "checkbox"
             :data-test "story-recorder-export-auto-assert-checkbox"
             :checked   auto-assert?
             :on-change (fn [_] (toggle-auto-assert!))}]
           [:span {:style (:label styles)} "Auto-assert app-db at end"]
           [:span {:style (:hint styles)}
            (str "(top-" export/default-max-auto-assertions
                 " changed paths; trim manually after pasting)")]]

          ;; ---- Snippet preview ---------------------------------------------
          [:pre {:style     (:snippet styles)
                 :data-test "story-recorder-export-snippet"}
           rendered]

          ;; ---- Button row --------------------------------------------------
          [:div {:style (:btn-row styles)}
           [:button
            {:style    (:btn-muted styles)
             :data-test "story-recorder-export-replay"
             :on-click (fn [_] (run-replay! state spec))}
            "replay in this story"]
           [:button
            {:style    (:btn styles)
             :data-test "story-recorder-export-copy"
             :on-click (fn [_] (review-dialog/copy-to-clipboard! rendered))}
            "copy to clipboard"]
           [:button
            {:style    (:btn-muted styles)
             :data-test "story-recorder-export-close"
             :on-click (fn [_] (close-dialog!))}
            "close"]]]]))))

;; ---------------------------------------------------------------------------
;; Public open-from-recorder helper
;;
;; The recorder save-dialog's "Export as :play-script" button invokes
;; this — passes the dialog's captured events + source-id + snapshots
;; the live frame db (when available) so the auto-assert option has
;; data to work with.
;; ---------------------------------------------------------------------------

(defn open-from-recorder-dialog!
  "Open the export dialog using the recorder save-dialog's captured
  snapshot. Reads the live frame db at click time so the auto-assert
  option can derive assertions from real data. Per rf2-d5u89 the
  caller threads the recorder's `:entries` (rich DOM-event + timing
  record) alongside `:events` so the translator emits `:click` /
  `:type` / `:wait` steps when DOM-events were captured."
  [{:keys [events entries source-id]}]
  (when config/enabled?
    (open-dialog!
      {:source-id source-id
       :events    events
       :entries   entries
       :final-db  (when source-id
                    (export-events/snapshot-frame-db source-id))})))
