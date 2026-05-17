(ns re-frame.story.ui.recorder
  "Test Codegen UI surface — toolbar REC chip + recording overlay.

  Per bead rf2-5fc15. Wires the pure recorder
  (`re-frame.story.recorder`) to:

  - The chrome-level toolbar — a REC chip lives at the right of the
    toolbar (just left of the `[reset]` button) so the affordance is
    chrome-wide and visually unmistakable.
  - The trace bus — installs a per-process listener that filters
    `:event/dispatched` events targeting the currently-focused variant
    frame and feeds them into `recorder/record-event!`.
  - A save-as-variant dialog — when the user stops recording, a modal
    surfaces the captured EDN snippet (the `(reg-variant ...)` form
    they paste into source).

  ## State source

  The recorder UI reads `re-frame.story.recorder/state` directly — it's
  a `clojure.core/atom` (NOT a `r/atom`), so component re-renders are
  driven via `r/track!`-style polling against the recorder state
  combined with the shell's existing `:hot-reload-tick` poll. v1 keeps
  it simple: the toolbar's REC chip and the recording overlay both
  consume the recorder state through Reagent's auto-tracking by
  reading the recorder's CLJS-side mirror ratom (`ui-state`) — a thin
  `r/atom` we keep in sync with the pure atom via `add-watch`. Tests
  drive the pure atom directly without going through the mirror.

  ## Listener integration

  The listener lives behind a single `install-trace-listener!` call
  the shell makes once at mount. Events with `:op-type :event` +
  `:operation :event/dispatched` whose `:frame` tag matches the
  recorder's `:variant-id` slot pipe through `record-event!`. The
  pure predicate (`recordable-event?`) drops `:rf.assert/*` and
  internal Story events.

  ## UX

  Toolbar REC chip:

      [● REC]   while recording — red dot, white text on `#b91c1c`
      [REC]     idle — neutral chip styling

  Click toggles. When idle, clicking starts a recording targeting the
  currently-focused variant; when active, clicking stops + opens the
  snippet dialog.

  Recording overlay — a fixed-position banner at the top-right of the
  shell, visible while recording, naming the target variant and the
  current event count.

  Save-as-variant dialog — full-screen modal with:
    - editable text field for the new variant id;
    - the generated EDN snippet, copy-to-clipboard affordance;
    - 'discard' / 'close' buttons.

  ## Elision

  Every public fn opens with `(when config/enabled? ...)` so production
  CLJS builds short-circuit before any DOM call. The listener install
  is also gated."
  (:require [cljs.reader]
            [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.story.config                       :as config]
            [re-frame.story.recorder                     :as recorder]
            [re-frame.story.recorder.dom-capture         :as recorder-dom]
            [re-frame.story.review-dialog                :as review-dialog]
            [re-frame.story.ui.recorder-export-dialog    :as export-dialog]
            [re-frame.story.ui.state                     :as state]))

;; ---------------------------------------------------------------------------
;; Reagent mirror of the recorder state
;;
;; `recorder/state` is a plain `atom` so JVM tests can exercise the state
;; machine. CLJS-side we mirror it into a `r/atom` so the toolbar chip /
;; overlay auto-re-render on every transition. A `add-watch` on the
;; pure atom keeps the mirror in sync.
;; ---------------------------------------------------------------------------

(defonce ui-state
  (r/atom (recorder/current-state)))

(defonce ^:private mirror-installed?
  (atom false))

(defn- install-mirror! []
  (when (and config/enabled? (not @mirror-installed?))
    (reset! mirror-installed? true)
    (add-watch recorder/state ::ui-mirror
               (fn [_ _ _ new-state]
                 (reset! ui-state new-state)))
    ;; Seed once at install time so the mirror starts in sync.
    (reset! ui-state (recorder/current-state))))

;; ---------------------------------------------------------------------------
;; Trace-bus listener wiring
;;
;; The actual listener lives in `re-frame.story.recorder` (cljc) so JVM
;; integration tests can exercise the full record-from-trace path. The
;; UI surface just delegates here and seeds the Reagent mirror so chip
;; / overlay re-render on every transition.
;; ---------------------------------------------------------------------------

(defn install-trace-listener!
  "Install the recorder's trace-bus listener + seed the Reagent state
  mirror. Idempotent."
  []
  (when config/enabled?
    (install-mirror!)
    (recorder/install-trace-listener!)
    nil))

(defn remove-trace-listener!
  "Tear down the recorder's trace-bus listener. Idempotent."
  []
  (when config/enabled?
    (recorder/remove-trace-listener!)
    nil))

;; ---------------------------------------------------------------------------
;; Toolbar REC chip
;; ---------------------------------------------------------------------------

(def ^:private styles
  {:chip-idle   {:display         "inline-flex"
                 :align-items     "center"
                 :gap             "5px"
                 :padding         "3px 10px"
                 :background      "#37373d"
                 :color           "#cccccc"
                 :border          "none"
                 :border-radius   "10px"
                 :cursor          "pointer"
                 :font-family     "monospace"
                 :font-size       "11px"
                 :user-select     "none"
                 :letter-spacing  "0.4px"}
   :chip-active {:display         "inline-flex"
                 :align-items     "center"
                 :gap             "5px"
                 :padding         "3px 10px"
                 :background      "#b91c1c"
                 :color           "white"
                 :border          "none"
                 :border-radius   "10px"
                 :cursor          "pointer"
                 :font-family     "monospace"
                 :font-size       "11px"
                 :user-select     "none"
                 :font-weight     "bold"
                 :letter-spacing  "0.4px"
                 :animation       "rf-story-rec-pulse 1.4s ease-in-out infinite"}
   :chip-disabled {:display       "inline-flex"
                 :align-items     "center"
                 :gap             "5px"
                 :padding         "3px 10px"
                 :background      "#2d2d30"
                 :color           "#777"
                 :border          "none"
                 :border-radius   "10px"
                 :cursor          "not-allowed"
                 :font-family     "monospace"
                 :font-size       "11px"
                 :user-select     "none"
                 :letter-spacing  "0.4px"}
   :dot         {:width        "8px"
                 :height       "8px"
                 :border-radius "50%"
                 :background    "#ef4444"
                 :box-shadow    "0 0 6px #ef4444"}
   :overlay     {:position     "fixed"
                 :top          "44px"
                 :right        "12px"
                 :z-index      1600
                 :background   "#1f1f1f"
                 :border       "1px solid #b91c1c"
                 :color        "#fdd"
                 :padding      "6px 10px"
                 :border-radius "4px"
                 :font-family  "monospace"
                 :font-size    "11px"
                 :box-shadow   "0 4px 10px rgba(0,0,0,0.6)"
                 :display      "flex"
                 :align-items  "center"
                 :gap          "8px"}
   ;; Modal styling for the save-as-variant dialog moved to
   ;; `re-frame.story.review-dialog` (rf2-7jpky); only the chip /
   ;; overlay / picker styles remain here.
   :btn-row     {:display "flex"
                 :gap "8px"
                 :justify-content "flex-end"}
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
   :hint        {:color "#9a9a9a"
                 :font-style "italic"
                 :font-size "10px"}
   ;; Mid-recording assertion picker (rf2-39u9e)
   :assert-btn  {:padding "3px 9px"
                 :background "#0e639c"
                 :color "white"
                 :border "none"
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-family "monospace"
                 :font-size "11px"}
   :picker-back {:position "fixed"
                 :top "0" :left "0" :right "0" :bottom "0"
                 :background "rgba(0,0,0,0.55)"
                 :z-index 1750
                 :display "flex"
                 :align-items "center"
                 :justify-content "center"}
   :picker      {:width "520px"
                 :max-width "90vw"
                 :max-height "80vh"
                 :background "#1e1e1e"
                 :color "#ddd"
                 :border "1px solid #444"
                 :border-radius "6px"
                 :padding "14px"
                 :font-family "monospace"
                 :font-size "12px"
                 :display "flex"
                 :flex-direction "column"
                 :gap "10px"
                 :overflow "auto"
                 :box-shadow "0 12px 32px rgba(0,0,0,0.7)"}
   :picker-title {:font-weight "bold"
                  :color "#9cdcfe"
                  :font-size "13px"}
   :picker-grid {:display "grid"
                 :grid-template-columns "1fr 1fr"
                 :gap "6px"}
   :picker-row  {:padding "6px 8px"
                 :background "#252526"
                 :color "#ddd"
                 :border "1px solid #333"
                 :border-radius "3px"
                 :cursor "pointer"
                 :text-align "left"
                 :font-family "monospace"
                 :font-size "11px"
                 :display "flex"
                 :flex-direction "column"
                 :gap "2px"}
   :picker-row-id    {:color "#9cdcfe"
                      :font-weight "bold"}
   :picker-row-hint  {:color "#9a9a9a"
                      :font-size "10px"
                      :font-style "italic"}
   :field-row   {:display "flex"
                 :flex-direction "column"
                 :gap "3px"}
   :field-label {:color "#9cdcfe"
                 :font-size "11px"}
   :field-input {:padding "5px 7px"
                 :background "#252526"
                 :color "white"
                 :border "1px solid #444"
                 :border-radius "3px"
                 :font-family "monospace"
                 :font-size "12px"
                 :width "100%"
                 :box-sizing "border-box"}
   :field-error {:color "#f08080"
                 :font-size "10px"
                 :font-style "italic"}
   :preview     {:background "#0e0e10"
                 :color "#dcdcaa"
                 :padding "8px"
                 :border "1px solid #333"
                 :border-radius "3px"
                 :white-space "pre"
                 :overflow "auto"
                 :max-height "30vh"
                 :font-family "monospace"
                 :font-size "11px"
                 :line-height "1.4"}})

;; ---------------------------------------------------------------------------
;; Modal state — driven by `re-frame.story.review-dialog`. The shared
;; dialog state map carries `:open?` / `:draft-id` / `:source-id` /
;; `:context`; the recorder's flow stashes the recorded source variant
;; id in `:source-id` and the captured `:events` snapshot in
;; `:context` (with a top-level `:events` mirror for ergonomics).
;;
;; **Snapshot at open time** (rf2-8x9nb): the captured events ride on
;; the dialog state itself — NOT read live off the recorder atom — so
;; clicking REC again to start a fresh recording (which resets the
;; recorder atom) cannot mutate the in-flight dialog's snippet. The
;; user's 10-event capture stays intact until they close / discard.
;;
;; The dialog opens automatically when the user STOPS recording and
;; there are captured events; it can also be reopened from the toolbar
;; chip's secondary "snippet" affordance (v1 ships the auto-open path
;; only — re-open is a v1.1 polish).
;; ---------------------------------------------------------------------------

(defonce ui-dialog
  (r/atom recorder/initial-dialog-state))

(defn- open-dialog!
  "Open the save-as-variant dialog against `source-variant-id` with
  the captured `events` snapshot. `now-ms` defaults to the current
  wall-clock; tests pass an explicit stamp. Per rf2-d5u89 the
  captured `:entries` are snapshotted alongside `:events` so the
  export dialog drives the `:play-script` translator with the rich
  DOM-event + timing record."
  ([source-variant-id events]
   (open-dialog! source-variant-id events (recorder/recorded-entries) (.now js/Date)))
  ([source-variant-id events now-ms]
   (open-dialog! source-variant-id events (recorder/recorded-entries) now-ms))
  ([source-variant-id events entries now-ms]
   (swap! ui-dialog recorder/open-dialog source-variant-id events entries now-ms)))

(defn- close-dialog! []
  (swap! ui-dialog recorder/close-dialog))

(defn- set-draft-id! [s]
  (review-dialog/swap-parse-and-set-draft-id! ui-dialog s))

;; ---------------------------------------------------------------------------
;; Toolbar chip + overlay components
;; ---------------------------------------------------------------------------

(defn- can-record?
  "True iff a recording can be started. Requires a selected variant —
  the recorder targets exactly one frame."
  [shell]
  (some? (:selected-variant shell)))

(defn rec-chip
  "Toolbar chip rendered by the toolbar strip. Click toggles recording.

  States:
    - no variant selected → disabled (the recorder needs a target).
    - idle               → [REC]   click to start.
    - recording          → [● REC] click to stop + open snippet
                            dialog.

  Public so tests can introspect the chip-level hiccup without
  driving the full toolbar."
  []
  (let [shell      @state/shell-state-atom
        rec        @ui-state
        rec?       (:recording? rec)
        target     (:selected-variant shell)
        enabled?   (or rec? (can-record? shell))
        on-click   (fn [_]
                     (cond
                       (and (not rec?) target)
                       (do
                         ;; rf2-d5u89: re-attach DOM-capture listeners to
                         ;; the current canvas root. The shell's mount-
                         ;; time install handles the common case, but mode
                         ;; switches (Docs / Test → Canvas) re-mount the
                         ;; canvas DOM and we want capture wired to the
                         ;; live node before recording starts. Idempotent.
                         (recorder-dom/install!)
                         (recorder/start-recording! target))

                       rec?
                       (let [_     (recorder-dom/flush-type-buffer!)
                             {:keys [variant-id events]} (recorder/stop-recording!)]
                         (when (seq events)
                           (open-dialog! variant-id events)))))]
    [:button
     {:style        (cond
                      (not enabled?) (:chip-disabled styles)
                      rec?           (:chip-active styles)
                      :else          (:chip-idle styles))
      :disabled     (not enabled?)
      :data-test    "story-toolbar-rec"
      :data-recording (if rec? "true" "false")
      :aria-pressed (if rec? "true" "false")
      :title        (cond
                      (not enabled?)
                      "Select a variant to record canvas interactions"
                      rec?
                      (str "Recording " (count (:events rec))
                           " events — click to stop and save as variant")
                      :else
                      "Record canvas dispatches as a :play body (Test Codegen)")
      :on-click     on-click}
     (when rec? [:span {:style (:dot styles)}])
     "REC"
     (when rec?
       [:span {:style {:opacity "0.85"}}
        (str "  " (count (:events rec)))])]))

;; ---------------------------------------------------------------------------
;; Mid-recording assertion picker (rf2-39u9e)
;;
;; The recording overlay carries a `+ assert` button next to `stop`.
;; Click → `ui-picker` flips `:open?` true and the modal renders.
;; The picker has two phases:
;;
;;   1. Vocabulary list — seven canonical `:rf.assert/*` ids as
;;      buttons. Click selects one + advances to phase 2.
;;   2. Field entry — one EDN input per payload field declared in the
;;      vocabulary entry. A live preview shows the event vector that
;;      will land in the captured `:play` body. 'Insert' calls
;;      `recorder/insert-assertion!`; 'cancel' returns to phase 1.
;;
;; The picker is overlay-style modal so it stays visible while the
;; user clicks back over the canvas — but the recording stays in
;; flight underneath. The point is fast iteration; the picker doesn't
;; pause recording (the user can keep dispatching after inserting).
;; ---------------------------------------------------------------------------

(defonce ui-picker
  (r/atom {:open?       false
           :assertion   nil      ; the picked id, or nil while on phase 1
           :field-text  {}       ; field-key → raw input string
           :error       nil}))

(defn- open-picker! []
  (reset! ui-picker {:open? true :assertion nil :field-text {} :error nil}))

(defn- close-picker! []
  (swap! ui-picker assoc :open? false))

(defn- pick-assertion! [assertion-id]
  (swap! ui-picker assoc :assertion assertion-id :field-text {} :error nil))

(defn- set-field-text! [field-key s]
  (swap! ui-picker assoc-in [:field-text field-key] s))

(defn- parse-edn
  "Read `s` as EDN; on parse failure return `::parse-error`. Used by
  the picker to translate field inputs into payload values."
  [s]
  (try
    (if (and (string? s) (seq (str/trim s)))
      (cljs.reader/read-string s)
      nil)
    (catch :default _ ::parse-error)))

(defn- build-payload
  "Walk the selected assertion's `:fields` and build a payload map by
  parsing each input. Returns `[:ok payload]` on success or
  `[:err {:field <k> :raw <s>}]` on first parse error."
  [{:keys [assertion field-text]}]
  (let [{:keys [fields]} (recorder/vocabulary-entry assertion)]
    (loop [fs fields payload {}]
      (if-let [{:keys [key type]} (first fs)]
        (let [raw (get field-text key "")]
          (case type
            :string
            (recur (rest fs) (assoc payload key raw))

            :edn
            (let [v (parse-edn raw)]
              (if (= v ::parse-error)
                [:err {:field key :raw raw}]
                (recur (rest fs) (assoc payload key v))))))
        [:ok payload]))))

(defn- preview-event
  "Build the event vector preview from the picker's current state.
  Returns the event vec or `nil` if the payload doesn't parse."
  [picker]
  (let [[outcome payload] (build-payload picker)]
    (when (= outcome :ok)
      (recorder/make-assertion (:assertion picker) payload))))

(defn- insert!
  "Commit the picker's current state by inserting the assertion into
  the active recording. No-ops if the payload doesn't parse — the
  picker surfaces a `:error` message instead."
  []
  (let [picker @ui-picker
        [outcome detail] (build-payload picker)]
    (if (= outcome :ok)
      (do
        (recorder/insert-assertion! (:assertion picker) detail)
        (close-picker!))
      (swap! ui-picker assoc :error detail))))

(defn assertion-picker
  "Modal picker for mid-recording assertion insertion. Public so the
  shell can mount it alongside the recorder overlay (and so tests can
  introspect the hiccup)."
  []
  (let [{:keys [open? assertion field-text error] :as picker} @ui-picker]
    (when open?
      [:div
       {:style    (:picker-back styles)
        :data-test "story-recorder-picker"
        :on-click (fn [e]
                    (when (= (.-target e) (.-currentTarget e))
                      (close-picker!)))}
       [:div {:style (:picker styles)
              :on-click (fn [e] (.stopPropagation e))}
        [:div {:style (:picker-title styles)}
         (if assertion
           (str "Add assertion — " (pr-str assertion))
           "Add assertion — pick from the canonical vocabulary")]

        (if (nil? assertion)
          ;; Phase 1: vocabulary list.
          [:div {:style (:picker-grid styles)
                 :data-test "story-recorder-picker-vocab"}
           (for [{:keys [id label hint]} recorder/assertion-vocabulary]
             ^{:key id}
             [:button
              {:style      (:picker-row styles)
               :data-test  (str "story-recorder-picker-id-"
                                (namespace id) "-" (name id))
               :on-click   (fn [_] (pick-assertion! id))}
              [:span {:style (:picker-row-id styles)} (pr-str id)]
              [:span {:style (:picker-row-hint styles)} hint]])]

          ;; Phase 2: field entry + preview.
          (let [{:keys [fields hint]} (recorder/vocabulary-entry assertion)
                preview (preview-event picker)]
            [:div {:style {:display "flex" :flex-direction "column" :gap "10px"}
                   :data-test "story-recorder-picker-fields"}
             [:div {:style (:hint styles)} hint]
             (for [{:keys [key prompt placeholder]} fields]
               ^{:key key}
               [:label {:style (:field-row styles)}
                [:span {:style (:field-label styles)} prompt]
                [:input
                 {:type        "text"
                  :style       (:field-input styles)
                  :data-test   (str "story-recorder-picker-field-" (name key))
                  :placeholder placeholder
                  :value       (get field-text key "")
                  :on-change   (fn [e] (set-field-text! key (.. e -target -value)))}]
                (when (and error (= (:field error) key))
                  [:span {:style (:field-error styles)
                          :data-test "story-recorder-picker-error"}
                   "EDN didn't parse — " (pr-str (:raw error))])])
             (when (seq fields)
               [:div {:style {:font-size "10px" :color "#9a9a9a"}}
                "preview:"])
             (when preview
               [:pre {:style     (:preview styles)
                      :data-test "story-recorder-picker-preview"}
                (pr-str preview)])
             [:div {:style (:btn-row styles)}
              [:button
               {:style    (:btn-muted styles)
                :data-test "story-recorder-picker-back"
                :on-click (fn [_] (swap! ui-picker assoc
                                         :assertion nil
                                         :field-text {}
                                         :error nil))}
               "← back"]
              [:button
               {:style    (:btn-muted styles)
                :data-test "story-recorder-picker-cancel"
                :on-click (fn [_] (close-picker!))}
               "cancel"]
              [:button
               {:style     (:btn styles)
                :data-test "story-recorder-picker-insert"
                :disabled  (some? error)
                :on-click  (fn [_] (insert!))}
               "insert"]]]))]])))

;; rf2-d5u89: Reagent-mirror of the DOM-capture enabled flag so the
;; overlay chip re-renders when the user toggles capture. The flag
;; lives in the dom-capture ns; here we just mirror it.

(defonce ui-dom-capture-enabled? (r/atom (recorder-dom/enabled?)))

(defn- toggle-dom-capture! []
  (let [new-val (not @ui-dom-capture-enabled?)]
    (recorder-dom/set-enabled! new-val)
    (reset! ui-dom-capture-enabled? new-val)))

(defn recording-overlay
  "Fixed-position banner that floats at the top-right of the shell
  while a recording is in flight. Surfaces the target variant + the
  running event count + a `+ assert` button for mid-recording
  assertion insertion (rf2-39u9e). Per rf2-d5u89 the overlay also
  exposes a `DOM` toggle for opting in/out of DOM-event capture."
  []
  (let [{:keys [recording? variant-id events]} @ui-state
        dom-on? @ui-dom-capture-enabled?]
    (when recording?
      [:div
       {:style       (:overlay styles)
        :role        "status"
        :aria-live   "polite"
        :data-test   "story-recorder-overlay"}
       [:span {:style (:dot styles)}]
       [:span "REC"]
       [:span {:style {:color "#fff"}}
        (pr-str variant-id)]
       [:span {:style {:color "#9a9a9a"}}
        (str (count events) " event" (when (not= 1 (count events)) "s"))]
       [:button
        {:style       (if dom-on?
                        (assoc (:assert-btn styles) :background "#0e639c")
                        (assoc (:btn-muted styles)  :opacity     "0.6"))
         :data-test   "story-recorder-toggle-dom"
         :data-enabled (if dom-on? "true" "false")
         :title       (if dom-on?
                        "DOM-event capture ON — click + type are recorded. Click to disable."
                        "DOM-event capture OFF — only dispatched events are recorded. Click to enable.")
         :aria-pressed (if dom-on? "true" "false")
         :on-click    (fn [_] (toggle-dom-capture!))}
        (if dom-on? "DOM on" "DOM off")]
       [:button
        {:style     (:assert-btn styles)
         :data-test "story-recorder-add-assertion"
         :title     "Insert a :rf.assert/* assertion into the captured :play body"
         :on-click  (fn [_] (open-picker!))}
        "+ assert"]
       [:button
        {:style    (:btn-muted styles)
         :data-test "story-recorder-stop"
         :on-click (fn [_]
                     ;; rf2-d5u89: drain pending typed-input buffer
                     ;; before sealing the recording so the final
                     ;; :dom/type entry lands in the script.
                     (recorder-dom/flush-type-buffer!)
                     (let [{:keys [variant-id events]} (recorder/stop-recording!)]
                       (when (seq events)
                         (open-dialog! variant-id events))))}
        "stop"]])))

;; ---------------------------------------------------------------------------
;; Save-as-variant dialog — delegated to `re-frame.story.review-dialog`
;; ---------------------------------------------------------------------------

(defn save-dialog
  "Modal dialog rendered after the user stops a non-empty recording.
  Shows the EDN snippet — `(reg-variant <id> {... :play [...]})` —
  and a 'copy to clipboard' affordance.

  The user edits the variant id inline; the snippet re-generates on
  every keystroke. Discard / close drop the captured events.

  Reads `:events` + `:source-id` from the dialog state itself — the
  snapshot was taken at `open-dialog!` time so a subsequent
  `start-recording!` cannot mutate the visible snippet (rf2-8x9nb)."
  []
  (let [dialog                                  @ui-dialog
        {:keys [events entries source-id]}      dialog]
    (when (:open? dialog)
      (let [draft-id (:draft-id dialog)
            snippet  (recorder/gen-play-snippet
                       events
                       {:variant-id (or draft-id :story.recorded/example)
                        :extends    source-id})]
        (review-dialog/review-dialog dialog
          {:title             "Test Codegen — save recording as variant"
           :hint              (str "EDN snippet generated from "
                                   (count events) " captured event"
                                   (when (not= 1 (count events)) "s")
                                   " against " (pr-str source-id)
                                   ". Edit the variant id then "
                                   "copy + paste into your stories namespace.")
           :snippet           snippet
           :placeholder-id    :story.recorded/example
           :placeholder-input ":story.your-story/recorded-flow"
           :on-edit-id        set-draft-id!
           :on-copy           (fn [] (review-dialog/copy-to-clipboard! snippet))
           :on-discard        (fn [] (recorder/clear!) (close-dialog!))
           ;; rf2-x9zsr — open the :play-script export dialog with the
           ;; captured snapshot. We DON'T close the parent dialog
           ;; (user may want to copy the :play form too); the export
           ;; dialog stacks on top via a higher z-index.
           :on-export         (fn []
                                (export-dialog/open-from-recorder-dialog!
                                  {:events    events
                                   :entries   entries
                                   :source-id source-id}))
           :on-close          close-dialog!
           :data-test-prefix  "story-recorder"})))))
