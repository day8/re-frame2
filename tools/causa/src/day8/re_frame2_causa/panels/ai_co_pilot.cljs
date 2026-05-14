(ns day8.re-frame2-causa.panels.ai-co-pilot
  "AI Co-Pilot rail panel (Phase 5, rf2-rccf3).

  Per `tools/causa/spec/009-AI-CoPilot.md` the co-pilot is a pull-only
  Q&A + slash-command rail. It lives in the right margin of the Causa
  shell, **collapsed by default** (Lock 8). On expand it occupies a
  320px rail; on collapse it shows a single `◇` cue glyph that pulses
  every 8 seconds until the user has used the co-pilot once (per spec
  §The AI co-pilot collapsed cue).

  ## What this panel does — and what it doesn't

  - **Does**: render an empty conversation rail, accept user input,
    parse slash commands via `ai-co-pilot-helpers`, dispatch
    `:rf.causa/copilot-submit-question` which drives the per-provider
    LLM call (when wired). Streams tokens back via
    `:rf.causa/copilot-stream-token` and ends streams via
    `:rf.causa/copilot-stream-end`. Renders chips for `{:rf.copilot/chip}`
    fragments parsed off the streamed answer.

  - **Does not** (Lock 10, 12, 13):
    - **No background narration** — the model never sends a token
      unless the user submits a question. The panel makes no fetch on
      mount; the only outbound surface is the submit handler.
    - **No persistence** — the conversation vector lives in Causa's
      app-db only, never localStorage, never an export.
    - **No voice / STT** — no `🎙` button, no mic permission request.

  ## Pure hiccup

  Same contract as the other Causa panels (`event_detail.cljs`,
  `time_travel.cljs`, `causality_graph.cljs`). The view is pure
  hiccup; the substrate adapter installed via `rf/init!` handles the
  render. Frame isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`.

  ## Where the LLM call lives

  `:rf.causa/copilot-submit-question` is an event-fx that routes
  through the `:rf.causa.fx/llm-stream` effect. The effect's handler
  reads the user-configured provider out of the panel's settings,
  reads the API key out of localStorage, and streams tokens back via
  the `:rf.causa/copilot-stream-token` event. This Phase 5 ships the
  panel shell + the dispatch surface; the provider integrations
  (Claude / OpenAI / Gemini / Local / Custom) are stubbed for now —
  the effect handler is a no-op when the provider config is absent
  so the panel renders cleanly in the empty-state demo. The wire is
  in place; the per-provider fetch lands as follow-on work."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.ai-co-pilot-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- view atoms ---------------------------------------------------------
;;
;; The input box is local UI state — kept in a defonce atom so a
;; hot-reload doesn't drop the user's in-progress question. The
;; dispatch only fires on submit.

(defonce ^:private input-atom (atom ""))

(defn- read-input [] @input-atom)
(defn- set-input! [v] (reset! input-atom (or v "")))

;; ---- sub-views ----------------------------------------------------------

(defn- cue-glyph
  "The `◇` cue glyph rendered when the rail is collapsed. Per spec
  §The AI co-pilot collapsed cue + §Default state — the glyph pulses
  every 8 seconds in co-pilot magenta until the user has opened the
  rail once, after which it stays static.

  Per spec §Performance — the cue consumes zero CPU/network when
  collapsed and idle. The pulse is a CSS animation on the glyph; the
  view ns leaves the @keyframes definition to the shell's stylesheet
  pass. Until that lands the glyph renders static (a future CSS
  injection swaps `opacity: 1` for the once-every-8s expand-fade
  cycle).

  Clicking the cue toggles the rail open."
  [_cue-active?]
  [:button {:data-testid "rf-causa-copilot-cue"
            :on-click    #(rf/dispatch [:rf.causa/copilot-toggle] {:frame :rf/causa})
            :title       "Ask Causa (Ctrl+Shift+/)"
            :style       {:background  "transparent"
                          :border      "none"
                          :cursor      "pointer"
                          :padding     "8px"
                          :color       (:magenta tokens)
                          :font-size   "16px"
                          :line-height 1}}
   "◇"])

(defn- chip-view
  "Render one parsed chip segment as a clickable handle. Per spec
  §Causa data chips §Visual treatment — the chip uses the same chrome
  as source-coord chips (`bg-3`, `radius-sm`, 12px caption type) so
  the eye reads them as first-class data, not decoration.

  Glyph encodes type; click dispatches the chip's target with the
  chip's value conj'd as the final positional arg. Per spec §Failure
  modes the renderer never invents chips from free text — only
  fragments the model emitted in the structured form get this
  treatment. Resolution failure is visible (the click target may
  resolve to a no-longer-present runtime id) but not predicted: the
  runtime is the truth."
  [{:keys [chip-key value glyph target] :as _resolved}]
  [:button {:data-testid (str "rf-causa-copilot-chip-" (name chip-key))
            :on-click    #(rf/dispatch [:rf.causa/copilot-chip-clicked
                                        {:chip-key chip-key
                                         :value    value
                                         :target   target}] {:frame :rf/causa})
            :title       (str chip-key " " (pr-str value))
            :style       {:display       "inline-flex"
                          :align-items   "center"
                          :gap           "4px"
                          :margin        "0 2px"
                          :padding       "1px 6px"
                          :background    (:bg-3 tokens)
                          :color         (:text-primary tokens)
                          :border        (str "1px solid " (:border-default tokens))
                          :border-radius "4px"
                          :cursor        "pointer"
                          :font-family   mono-stack
                          :font-size     "12px"}}
   [:span {:style {:color (:magenta tokens)}} glyph]
   [:span (pr-str value)]])

(defn- segment-view
  "Render one parsed segment from `parse-streamed-answer` — either
  `:text` (plain prose) or `:chip` (interactive handle). Malformed
  fragments come through as `:text` segments containing the literal
  edn — per spec §Why structured citations 'malformed edn renders as
  the literal fragment, surfacing the model's confusion'."
  [segment]
  (case (:kind segment)
    :text
    [:span (:text segment)]

    :chip
    (if-let [resolved (h/resolve-chip segment)]
      [chip-view resolved]
      ;; Unknown chip-key — fall back to literal raw render so the
      ;; user sees what the model emitted.
      [:span (:raw segment)])

    [:span (str segment)]))

(defn- turn-view
  "Render one conversation turn — a user question or a streaming /
  finalised answer. Questions use the violet `▸` marker per spec
  §Panel layout. Answers stream through `parse-streamed-answer` so
  chips render as the model emits them."
  [{:keys [role text streaming?] :as _turn}]
  (case role
    :question
    [:div {:data-testid "rf-causa-copilot-turn-question"
           :style       {:padding "8px 12px"
                         :font-family sans-stack
                         :font-size "13px"
                         :color (:text-primary tokens)
                         :border-bottom (str "1px solid " (:border-subtle tokens))}}
     [:span {:style {:color (:accent-violet tokens) :margin-right "6px"}} "▸"]
     [:span text]]

    :answer
    [:div {:data-testid "rf-causa-copilot-turn-answer"
           :style       {:padding "8px 12px"
                         :font-family sans-stack
                         :font-size "13px"
                         :color (:text-secondary tokens)
                         :border-bottom (str "1px solid " (:border-subtle tokens))
                         :line-height 1.5}}
     (into [:div]
           (map-indexed
             (fn [i seg] ^{:key i} [segment-view seg])
             (h/parse-streamed-answer text)))
     (when streaming?
       [:span {:data-testid "rf-causa-copilot-stream-cursor"
               :style       {:color (:accent-violet tokens)
                             :margin-left "4px"
                             :font-family mono-stack}} "▍"])]

    [:div (pr-str _turn)]))

(defn- empty-state
  "Rendered when the conversation buffer is empty. Per spec §Empty
  state — sample questions inline; the final line ('Verify before
  trusting') is the deliberate navigator-not-oracle reminder."
  []
  [:div {:data-testid "rf-causa-copilot-empty"
         :style       {:padding "16px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :color       (:text-secondary tokens)
                       :line-height 1.5}}
   [:p {:style {:margin "0 0 12px 0" :color (:text-primary tokens)}}
    "Ask Causa anything about this runtime."]
   [:p {:style {:margin "0 0 4px 0" :color (:text-tertiary tokens) :font-size "12px"}}
    "Try:"]
   [:ul {:style {:margin "0 0 12px 16px"
                 :padding 0
                 :font-family mono-stack
                 :font-size "12px"
                 :color (:text-secondary tokens)}}
    [:li "Why did :checkout/submit fire?"]
    [:li "What changed in the last 10 epochs?"]
    [:li "Why is :cart/total returning 0?"]
    [:li "/state :auth/login-flow"]]
   [:p {:style {:margin 0
                :color (:text-tertiary tokens)
                :font-size "12px"
                :font-style "italic"}}
    "The co-pilot reads the same data you see — it cites every claim "
    "with a source coord or epoch id. Verify before trusting."]])

(defn- conversation-view
  "Render the conversation buffer — newest at the bottom per spec
  §Panel layout. Empty conversation renders the empty-state inline
  prompts."
  [conversation]
  (if (empty? conversation)
    [empty-state]
    (into [:div {:data-testid "rf-causa-copilot-conversation"
                 :style       {:display "flex"
                               :flex-direction "column"}}]
          (map-indexed
            (fn [i turn] ^{:key i} [turn-view turn])
            conversation))))

(defn- slash-popover
  "When `input` starts with `/`, render the dropdown of matching
  commands. Click on a row substitutes the command into the input.
  Per spec §Slash commands — typed shortcuts for common questions."
  [input]
  (let [matches (h/slash-popover-matches input)]
    (when (seq matches)
      [:div {:data-testid "rf-causa-copilot-slash-popover"
             :style       {:position "absolute"
                           :bottom "100%"
                           :left 0
                           :right 0
                           :margin-bottom "4px"
                           :background (:bg-3 tokens)
                           :border (str "1px solid " (:border-default tokens))
                           :border-radius "4px"
                           :max-height "180px"
                           :overflow-y "auto"
                           :font-family mono-stack
                           :font-size "12px"
                           :z-index 10}}
       (into [:ul {:style {:list-style "none" :margin 0 :padding 0}}]
             (for [{:keys [command usage doc]} matches]
               ^{:key command}
               [:li {:data-testid (str "rf-causa-copilot-slash-" (name command))
                     :on-click    #(set-input! usage)
                     :style       {:padding "6px 10px"
                                   :cursor "pointer"
                                   :color (:text-primary tokens)
                                   :border-bottom (str "1px solid " (:border-subtle tokens))}}
                [:div {:style {:color (:accent-violet tokens)}} usage]
                [:div {:style {:color (:text-tertiary tokens)
                               :font-family sans-stack
                               :font-size "11px"
                               :margin-top "2px"}}
                 doc]]))])))

(defn- input-row
  "The question-input row. Per spec §Pull-only model the model never
  sends a token unless the user submits. Submission fires
  `:rf.causa/copilot-submit-question` with the resolved text. The
  `/clear` command is intercepted client-side and dispatched as
  `:rf.causa/copilot-clear-conversation` — never sent to the model."
  []
  (let [input (read-input)
        parsed (h/parse-slash-command input)
        submit (fn []
                 (let [text (read-input)]
                   (when (seq (str/trim text))
                     (set-input! "")
                     (cond
                       (= :clear (:command parsed))
                       (rf/dispatch [:rf.causa/copilot-clear-conversation] {:frame :rf/causa})

                       :else
                       (rf/dispatch
                         [:rf.causa/copilot-submit-question
                          {:text text :parsed parsed}] {:frame :rf/causa})))))]
    [:div {:style {:position "relative"
                   :padding "8px 12px"
                   :border-top (str "1px solid " (:border-subtle tokens))
                   :background (:bg-1 tokens)}}
     (slash-popover input)
     [:div {:style {:display "flex" :gap "6px" :align-items "stretch"}}
      [:input {:data-testid "rf-causa-copilot-input"
               :type        "text"
               :value       input
               :placeholder "Ask anything…"
               :on-change   #(set-input! (.. % -target -value))
               :on-key-down (fn [e]
                              (when (= "Enter" (.-key e))
                                (.preventDefault e)
                                (submit)))
               :style       {:flex 1
                             :background (:bg-3 tokens)
                             :color (:text-primary tokens)
                             :border (str "1px solid " (:border-default tokens))
                             :border-radius "4px"
                             :padding "6px 10px"
                             :font-family sans-stack
                             :font-size "12px"}}]
      [:button {:data-testid "rf-causa-copilot-submit"
                :on-click    submit
                :title       "Submit (Enter)"
                :style       {:background (:accent-violet tokens)
                              :color "#fff"
                              :border "none"
                              :padding "0 12px"
                              :border-radius "4px"
                              :cursor "pointer"
                              :font-family sans-stack
                              :font-size "12px"
                              :font-weight 600}}
       "↑"]]
     [:div {:style {:margin-top "4px"
                    :font-family sans-stack
                    :font-size "10px"
                    :color (:text-tertiary tokens)}}
      "/slash for commands"]]))

(defn- title-bar
  "The rail's title bar — name, provider picker hook, expand, close.
  Per spec §Panel layout. The provider picker `⌗` and the expand `⛶`
  are presentation hooks for the next sub-bead (per-provider
  fetch + full-screen mode); the close `✕` fires the toggle event so
  the rail collapses back to the cue glyph."
  []
  [:header {:style {:display "flex"
                    :align-items "center"
                    :justify-content "space-between"
                    :padding "8px 12px"
                    :background (:bg-1 tokens)
                    :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [:div {:style {:display "flex" :align-items "center" :gap "6px"
                  :font-family sans-stack :font-size "13px"
                  :font-weight 600 :color (:text-primary tokens)}}
    [:span {:style {:color (:magenta tokens)}} "◇"]
    [:span "AI Co-pilot"]]
   [:div {:style {:display "flex" :align-items "center" :gap "8px"
                  :color (:text-secondary tokens)
                  :font-family mono-stack :font-size "12px"}}
    [:button {:data-testid "rf-causa-copilot-provider-picker"
              :on-click    #(rf/dispatch [:rf.causa/copilot-cycle-provider] {:frame :rf/causa})
              :title       "Provider"
              :style       {:background "transparent"
                            :border "none"
                            :color (:text-secondary tokens)
                            :cursor "pointer"
                            :padding "2px 4px"}}
     "⌗"]
    [:button {:data-testid "rf-causa-copilot-close"
              :on-click    #(rf/dispatch [:rf.causa/copilot-toggle] {:frame :rf/causa})
              :title       "Close"
              :style       {:background "transparent"
                            :border "none"
                            :color (:text-secondary tokens)
                            :cursor "pointer"
                            :padding "2px 4px"}}
     "✕"]]])

;; ---- public views -------------------------------------------------------

(rf/reg-view ai-co-pilot-rail
  "The rail-shaped view — title bar + scrollable conversation + input
  row. This is the open form (320px right-rail per spec §Panel
  layout). Per rf2-in6l2 `reg-view`-registered so the subscribe
  routes through React context to `:rf/causa`."
  []
  (let [conversation (or @(rf/subscribe [:rf.causa/copilot-conversation]) [])]
    [:aside {:data-testid "rf-causa-copilot-rail"
             :style       {:width "320px"
                           :flex-shrink 0
                           :display "flex"
                           :flex-direction "column"
                           :background (:bg-2 tokens)
                           :border-left (str "1px solid " (:border-subtle tokens))
                           :color (:text-primary tokens)
                           :font-family sans-stack
                           :font-size "13px"}}
     [title-bar]
     [:div {:style {:flex 1 :overflow-y "auto"}}
      [conversation-view conversation]]
     [input-row]]))

(rf/reg-view ai-co-pilot-cue
  "The collapsed form — a single `◇` cue glyph. Per spec §Default
  state the rail is collapsed by default; the glyph is the
  affordance for opening it.

  Rendered into the shell's right margin when
  `:rf.causa/copilot-open?` is false. The cue glyph + the sidebar
  `Co-pilot` row + the `Ctrl+Shift+/` keybinding all dispatch the
  same `:rf.causa/copilot-toggle` event.

  Per rf2-in6l2 `reg-view`-registered so the subscribe routes
  through React context to `:rf/causa`."
  []
  (let [cue-active? (boolean @(rf/subscribe [:rf.causa/copilot-cue-active?]))]
    [cue-glyph cue-active?]))

(rf/reg-view ai-co-pilot-view
  "The panel-style view used when the sidebar's Co-pilot row is
  selected as the active canvas panel. Mirrors the rail view but
  drops the explicit width (the canvas owns its layout). This is the
  form the sidebar's Co-pilot click lands on — distinct from the
  always-on-the-right rail which lives in the shell's margin chrome.

  Rationale: the rail UX is the primary surface (Lock 8), but the
  shell's sidebar still carries a Co-pilot row (per spec/007-UX-IA.md
  §Sidebar groups). Clicking the row should not be a dead link;
  rendering the same conversation surface in the canvas gives the
  user a full-width form when they want it, while preserving the
  rail's collapsed-by-default chrome."
  []
  (let [conversation (or @(rf/subscribe [:rf.causa/copilot-conversation]) [])]
    [:section {:data-testid "rf-causa-copilot-panel"
               :style       {:height "100%"
                             :display "flex"
                             :flex-direction "column"
                             :background (:bg-2 tokens)
                             :color (:text-primary tokens)
                             :font-family sans-stack
                             :font-size "14px"}}
     [title-bar]
     [:div {:style {:flex 1 :overflow-y "auto"}}
      [conversation-view conversation]]
     [input-row]]))

;; ---- registration entry -------------------------------------------------

(defn install!
  "Idempotent install for the AI Co-Pilot panel's Causa-side
  registrations. The registry's central `register-causa-handlers!`
  calls into this fn so panels can own their own subs / events /
  fxs without each panel re-doing its idempotency dance.

  Per spec §Pull-only model the registrations here do NOT fire any
  outbound LLM call on install — only the user-submit handler does
  that. Defaults render the empty-state on first open."
  []
  ;; ── ai-co-pilot panel begin ──
  (let [register-sub!   rf/reg-sub
        register-evdb!  rf/reg-event-db
        register-evfx!  rf/reg-event-fx
        register-fx!    rf/reg-fx]

    ;; ---- subscriptions ----------------------------------------------------

    ;; Boolean — drives the collapsed / open state of the rail. Default
    ;; false per Lock 8 (collapsed by default).
    (register-sub! :rf.causa/copilot-open?
      (fn [db _q] (boolean (get db :copilot-open? false))))

    ;; The conversation vector — per-session, in-memory only (Lock 12).
    ;; Each turn is `{:role :question/:answer :text <str> :streaming? <bool>}`.
    (register-sub! :rf.causa/copilot-conversation
      (fn [db _q] (get db :copilot-conversation [])))

    ;; The current provider — one of `:claude :openai :gemini :local :custom`.
    ;; Default `:claude` per spec §Provider abstraction.
    (register-sub! :rf.causa/copilot-provider
      (fn [db _q] (get db :copilot-provider :claude)))

    ;; Boolean — true until the user has opened the co-pilot once. Per
    ;; spec §The AI co-pilot collapsed cue the pulse stops after first
    ;; use; Causa remembers across the session.
    (register-sub! :rf.causa/copilot-cue-active?
      (fn [db _q] (not (true? (get db :copilot-first-used?)))))

    ;; Per-category redaction toggles. Per spec §Redaction defaults the
    ;; defaults are privacy-by-default — values are `<redacted>`.
    (register-sub! :rf.causa/copilot-redaction-settings
      (fn [db _q] (get db :copilot-redaction-settings
                       h/default-redaction-settings)))

    ;; The number of tokens streamed into the in-flight answer turn.
    ;; Used for the streaming progress indicator in the chrome.
    (register-sub! :rf.causa/copilot-streaming-token-count
      (fn [db _q] (get db :copilot-streaming-token-count 0)))

    ;; ---- events --------------------------------------------------------

    ;; Toggle the rail open / closed. Per spec §Default state the toggle
    ;; is shared by `Ctrl+Shift+/`, the cue glyph click, and the
    ;; sidebar's Co-pilot row.
    (register-evdb! :rf.causa/copilot-toggle
      (fn [db _ev]
        (-> db
            (update :copilot-open? not)
            ;; The first toggle counts as first-use — the pulse stops.
            (assoc :copilot-first-used? true))))

    ;; Mark first-use without flipping open / closed. Useful for the
    ;; sidebar entry's hover-stop affordance per spec §The AI co-pilot
    ;; collapsed cue.
    (register-evdb! :rf.causa/copilot-mark-first-use
      (fn [db _ev] (assoc db :copilot-first-used? true)))

    ;; Cycle providers for the picker. The full provider picker (with
    ;; settings UI) lands as follow-on work; this event lets the title
    ;; bar's `⌗` click round-robin through the 5 providers as a
    ;; lightweight stand-in.
    (register-evdb! :rf.causa/copilot-set-provider
      (fn [db [_ provider]]
        (assoc db :copilot-provider provider)))

    (register-evdb! :rf.causa/copilot-cycle-provider
      (fn [db _ev]
        (let [providers [:claude :openai :gemini :local :custom]
              current   (get db :copilot-provider :claude)
              idx       (.indexOf providers current)
              next      (nth providers (mod (inc idx) (count providers)))]
          (assoc db :copilot-provider next))))

    ;; Set the per-category redaction toggles. The settings UI surfaces
    ;; these per spec §Settings UI; for now any caller can write the
    ;; full settings map at once.
    (register-evdb! :rf.causa/copilot-set-redaction
      (fn [db [_ settings]]
        (assoc db :copilot-redaction-settings
               (merge h/default-redaction-settings settings))))

    ;; Append the user's question, start a streaming-answer turn, then
    ;; route the redacted payload to the provider via the
    ;; `:rf.causa.fx/llm-stream` effect. Per spec §Pull-only model this
    ;; is the ONLY surface that initiates an outbound LLM call; no
    ;; background work fires anywhere else in the panel.
    (register-evfx! :rf.causa/copilot-submit-question
      (fn [{:keys [db]} [_ {:keys [text parsed]}]]
        (let [settings   (get db :copilot-redaction-settings
                              h/default-redaction-settings)
              provider   (get db :copilot-provider :claude)
              conv       (-> (get db :copilot-conversation [])
                             (h/append-question text)
                             (h/start-answer))]
          {:db (-> db
                   (assoc :copilot-conversation conv)
                   ;; Streaming starts at 0 tokens.
                   (assoc :copilot-streaming-token-count 0)
                   ;; First submit counts as first-use, even if the user
                   ;; opened the rail by clicking it directly (the cue
                   ;; should stop pulsing once they've engaged).
                   (assoc :copilot-first-used? true))
           :fx [[:rf.causa.fx/llm-stream
                 {:provider          provider
                  :text              text
                  :parsed            parsed
                  :redaction-settings settings}]]})))

    ;; Append one streamed token to the in-flight answer turn. The
    ;; provider's streaming fetch (or its stub) calls this per token.
    (register-evdb! :rf.causa/copilot-stream-token
      (fn [db [_ token]]
        (-> db
            (update :copilot-conversation h/append-token token)
            (update :copilot-streaming-token-count (fnil inc 0)))))

    ;; Mark the in-flight answer turn as no-longer streaming.
    (register-evdb! :rf.causa/copilot-stream-end
      (fn [db _ev]
        (-> db
            (update :copilot-conversation h/end-answer)
            (assoc :copilot-streaming-token-count 0))))

    ;; Clear the conversation buffer. Triggered by the `/clear` slash
    ;; command + `Ctrl+L`. Per spec §Ephemeral conversation no
    ;; persistence side-effect — the buffer is in-memory only.
    (register-evdb! :rf.causa/copilot-clear-conversation
      (fn [db _ev]
        (-> db
            (assoc :copilot-conversation (h/empty-conversation))
            (assoc :copilot-streaming-token-count 0))))

    ;; Handle a chip click. Per spec §Chip types each chip's click
    ;; target is the panel-jump event for that chip kind. The handler
    ;; dispatches the resolved target with the chip's value as the
    ;; argument. Unknown chips are no-ops (defensive — the renderer
    ;; should not produce chips with unknown targets).
    (register-evfx! :rf.causa/copilot-chip-clicked
      (fn [_cofx [_ {:keys [target value]}]]
        (when target
          {:fx [[:dispatch [target value]]]})))

    ;; ---- effects -----------------------------------------------------

    ;; The provider streaming surface. Per spec §Provider abstraction
    ;; this routes to Claude / OpenAI / Gemini / Local Ollama / Custom
    ;; URL. The API key lives in localStorage; the fetch is direct
    ;; from the browser to the chosen provider — Day8 never proxies.
    ;;
    ;; This Phase 5 ships the wire — the effect registers as a no-op
    ;; when the per-provider implementation isn't on the classpath.
    ;; The follow-on bead wires the four provider fetchers; this
    ;; effect's contract (args: `{:provider :text :parsed
    ;; :redaction-settings}`; outputs: stream tokens via
    ;; `:rf.causa/copilot-stream-token`, end via
    ;; `:rf.causa/copilot-stream-end`) is the integration point.
    (register-fx! :rf.causa.fx/llm-stream
      (fn [_ctx _args]
        ;; Phase 5: no-op stub. The conversation surface still records
        ;; the question + opens the streaming turn so the UI is
        ;; testable end-to-end without a live provider. The follow-on
        ;; wiring replaces this stub with the per-provider fetch +
        ;; SSE stream parser.
        nil)))
  ;; ── ai-co-pilot panel end ──
  nil)
