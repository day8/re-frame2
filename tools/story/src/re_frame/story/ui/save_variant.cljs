(ns re-frame.story.ui.save-variant
  "Save-current-canvas-state-as-new-variant UI — the SB9 'Save' affordance.
  The pure machinery lives in
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
  (:require [reagent.core :as r]
            [re-frame.story.config        :as config]
            [re-frame.story.review-dialog :as review-dialog]
            [re-frame.story.save-variant  :as save-variant]
            [re-frame.story.theme.typography :as typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as colors]))

;; ---------------------------------------------------------------------------
;; Dialog ratom — the impure mirror of `review-dialog/initial-state`.
;;
;; The pure transitions in `re-frame.story.review-dialog` +
;; `re-frame.story.save-variant` produce new state maps; the UI swaps
;; the ratom around them so Reagent re-renders the modal on every
;; keystroke.
;; ---------------------------------------------------------------------------

(defonce ui-dialog
  (r/atom review-dialog/initial-state))

(defn- open-dialog!
  "Open the dialog against `source-variant-id` with the captured
  `args-snapshot`. `now-ms` seeds the default id. `violations` is the vector returned by
  `schema-validation/args-violations` against the live component
  schema — used by the dialog to render a non-blocking 'Args do not
  match the variant's Spec 010 schema' hint when non-empty. May be
  nil/empty (no schema registered, or all args conform).

  `slices` is the eight-slice capture report from
  `save-variant/capture-slices` — the dialog renders its honesty-floor
  warnings (which slices are captured-as-declared / not-yet-projectable)
  so a saved variant is honest about what it does and does NOT capture.
  May be nil (legacy 3/4-arity callers)."
  ([source-variant-id args-snapshot now-ms]
   (open-dialog! source-variant-id args-snapshot now-ms nil nil))
  ([source-variant-id args-snapshot now-ms violations]
   (open-dialog! source-variant-id args-snapshot now-ms violations nil))
  ([source-variant-id args-snapshot now-ms violations slices]
   (swap! ui-dialog save-variant/open
          source-variant-id args-snapshot now-ms violations slices)))

(defn- close-dialog! []
  (swap! ui-dialog save-variant/close))

(defn- set-draft-id! [s]
  (review-dialog/swap-parse-and-set-draft-id! ui-dialog s))

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
;; Button styling — the controls-panel-local affordance. The modal
;; itself reuses `re-frame.story.review-dialog`'s shared styles so the
;; two save-as flows (record-as-:play-script + snapshot-as-:args) share
;; visual language.
;; ---------------------------------------------------------------------------

(def ^:private button-styles
  {:button          {:padding       "4px 8px"
                     :background    (:accent-amber colors/tokens)
                     :color         "white"
                     :border        "none"
                     :border-radius "3px"
                     :cursor        "pointer"
                     :font-size     (:micro typography/type-scale)
                     :margin-top    "8px"
                     :font-family   mono-stack}
   :button-disabled {:padding       "4px 8px"
                     :background    (:bg-2 colors/tokens)
                     :color         "#777"
                     :border        "1px solid #444"
                     :border-radius "3px"
                     :cursor        "not-allowed"
                     :font-size     (:micro typography/type-scale)
                     :margin-top    "8px"
                     :font-family   mono-stack}})

;; ---------------------------------------------------------------------------
;; Controls-panel button
;;
;; Disabled when no variant is focused. Click captures the live snapshot
;; and opens the dialog.
;; ---------------------------------------------------------------------------

(defn save-variant-button
  "Render the 'Save current canvas state as new variant' button. Lives
  on the controls panel (per `003-Render-Shell.md` §Shell lifecycle — the controls panel is the
  natural home for args-snapshot affordances; spec/005-SOTA-Features
  §Save-current-canvas-state-as-variant).

  Public so tests can introspect the button-level hiccup without driving
  the full controls panel."
  [variant-id]
  (let [enabled? (some? variant-id)]
    [:button
     {:style     (if enabled?
                   (:button button-styles)
                   (:button-disabled button-styles))
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
;; Modal dialog — delegated to `re-frame.story.review-dialog`
;;
;; The user reviews the generated EDN snippet, edits the new variant id
;; inline, copies to clipboard, and pastes into source. Source is never
;; written directly — same as the recorder's save-as-variant dialog.
;; ---------------------------------------------------------------------------

(defn- violations-hint
  "Render the non-blocking violations note. Stripe and list
  of violating keys, so the user catches a drifted-args paste before
  it lands in source. nil when no violations."
  [violations]
  (when (seq violations)
    [:div {:data-test "story-save-variant-violations-hint"
           :data-violation-count (count violations)
           :style {:padding "8px 10px"
                   :margin "0 0 8px 0"
                   :background "#3a2a1a"
                   :color "#e0a060"
                   :border "1px solid #a06030"
                   :border-radius "3px"
                   :font-family mono-stack
                   :font-size (:micro typography/type-scale)
                   :line-height "1.5"}}
     [:div {:style {:font-weight "bold" :margin-bottom "4px"}}
      "Args do not match the variant's Spec 010 schema — preview below; "
      "paste at your own risk"]
     [:ul {:style {:margin "4px 0 0 16px" :padding 0}}
      (for [v violations]
        ^{:key (str (:key v))}
        [:li {:data-test "story-save-variant-violation-row"
              :data-key  (str (:key v))}
         (str (pr-str (:key v))
              " = "
              (pr-str (:value v)))])]]))

;; ---------------------------------------------------------------------------
;; The eight-slice capture report.
;;
;; spec/019 §3 requires the save flow to be HONEST about which canvas
;; slices it can represent losslessly and which it cannot: "if the
;; current state contains data that cannot be represented losslessly yet,
;; the UI MUST say what will be omitted". The pure
;; `save-variant/capture-slices` classifies every slice; this component
;; renders the warnings (every slice that is NOT a clean live projection)
;; so the user knows, BEFORE pasting, that a saved variant captures args
;; live, carries declared slices forward via `:extends`, and does NOT yet
;; project sub-overrides / db-seed / route / network / fx-overrides, nor
;; the chrome-wide viewport. The honesty floor is load-bearing — we warn,
;; never silently drop or fabricate.
;; ---------------------------------------------------------------------------

(def ^:private slice-status-styles
  "Per-status stripe colours for the slice-report rows."
  {:captured-as-declared {:background "#2a2f3a" :color "#9fb4d0" :border "#3a5070"}
   :not-wired            {:background "#332a2a" :color "#d09f9f" :border "#704040"}})

(defn slice-report
  "Render the non-blocking eight-slice capture report. Lists
  every slice that is NOT a clean live projection — `:captured-as-declared`
  (carried forward via `:extends`) and `:not-wired` (not yet projectable)
  — with its honest note. Returns nil when every slice projects cleanly
  (nothing to warn about). Public so tests can render it directly."
  [slices]
  (let [warnings (save-variant/slice-warnings slices)]
    (when (seq warnings)
      [:div {:data-test "story-save-variant-slice-report"
             :data-warning-count (count warnings)
             :style {:padding "8px 10px"
                     :margin "8px 0 0 0"
                     :background "#22242c"
                     :color "#c8c8d0"
                     :border "1px solid #3a3a44"
                     :border-radius "3px"
                     :font-family mono-stack
                     :font-size (:micro typography/type-scale)
                     :line-height "1.5"}}
       [:div {:style {:font-weight "bold" :margin-bottom "6px"}}
        "What this save captures — and what it does not"]
       (for [{:keys [slice label status note]} warnings]
         (let [sty (slice-status-styles status)]
           ^{:key (str slice)}
           [:div {:data-test "story-save-variant-slice-row"
                  :data-slice (name slice)
                  :data-slice-status (name status)
                  :style {:display "flex"
                          :align-items "baseline"
                          :gap "8px"
                          :padding "3px 6px"
                          :margin "3px 0"
                          :background (:background sty)
                          :border (str "1px solid " (:border sty))
                          :border-radius "3px"}}
            [:span {:style {:font-weight "bold"
                            :color (:color sty)
                            :white-space "nowrap"}}
             label]
            [:span {:style {:color "#a8a8b0"}}
             (if (= status :captured-as-declared)
               "captured as declared"
               "not yet projectable")]
            [:span {:style {:flex 1}} note]]))])))

(defn save-dialog
  "Render the save-variant modal. Visible iff `:open?` is true on the
  dialog ratom. The snippet re-generates on every keystroke as the user
  edits the new variant id; copy-to-clipboard surfaces the form for the
  user to paste into source.

  When the captured snapshot violates the variant's Spec 010
  schema, a non-blocking hint renders above the snippet listing the
  violating keys. Non-blocking — the user can still paste; the snippet
  carries the violating args as captured (paste at your own risk).

  The eight-slice capture report renders below the snippet,
  warning which slices are captured-as-declared (carried via `:extends`)
  and which are not yet projectable — the honesty floor for a saved
  variant (spec/019 §3).

  Public so tests can render the dialog hiccup directly after seeding
  the dialog ratom."
  []
  (let [dialog @ui-dialog]
    (when (:open? dialog)
      (let [{:keys [draft-id source-id args violations slices]} dialog
            snippet (save-variant/gen-variant-snippet
                      {:variant-id (or draft-id :story.saved/example)
                       :extends    source-id
                       :args       args})]
        (review-dialog/review-dialog dialog
          {:title             "Save current canvas state as new variant"
           :hint              [:div
                               [:div (str "Args snapshot captured from "
                                          (pr-str source-id)
                                          " — edit the new variant id and copy + paste into your "
                                          "stories namespace. Source is never written directly.")]
                               (violations-hint violations)
                               (slice-report slices)]
           :snippet           snippet
           :placeholder-id    :story.saved/example
           :placeholder-input ":story.your-story/saved-flow"
           :on-edit-id        set-draft-id!
           :on-copy           (fn [] (review-dialog/copy-to-clipboard! snippet))
           :on-close          close-dialog!
           :data-test-prefix  "story-save-variant"})))))
