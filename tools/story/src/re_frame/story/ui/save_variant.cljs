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
  (:require [reagent.core :as r]
            [re-frame.story.config        :as config]
            [re-frame.story.review-dialog :as review-dialog]
            [re-frame.story.save-variant  :as save-variant]
            [re-frame.story.theme.typography :refer [mono-stack]]))

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
  `args-snapshot`. `now-ms` seeds the default id. `violations` (rf2-
  lancu) is the vector returned by
  `schema-validation/args-violations` against the live component
  schema — used by the dialog to render a non-blocking 'Args do not
  match the variant's Spec 010 schema' hint when non-empty. May be
  nil/empty (no schema registered, or all args conform)."
  ([source-variant-id args-snapshot now-ms]
   (open-dialog! source-variant-id args-snapshot now-ms nil))
  ([source-variant-id args-snapshot now-ms violations]
   (swap! ui-dialog save-variant/open
          source-variant-id args-snapshot now-ms violations)))

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
;; two save-as flows (record-as-:play + snapshot-as-:args) share
;; visual language.
;; ---------------------------------------------------------------------------

(def ^:private button-styles
  {:button          {:padding       "4px 8px"
                     :background    "#0e639c"
                     :color         "white"
                     :border        "none"
                     :border-radius "3px"
                     :cursor        "pointer"
                     :font-size     "10px"
                     :margin-top    "8px"
                     :font-family   mono-stack}
   :button-disabled {:padding       "4px 8px"
                     :background    "#2d2d30"
                     :color         "#777"
                     :border        "1px solid #444"
                     :border-radius "3px"
                     :cursor        "not-allowed"
                     :font-size     "10px"
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
  on the controls panel (per IMPL-SPEC §4 — the controls panel is the
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
  "Render the rf2-lancu non-blocking violations note. Stripe and list
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
                   :font-size "10px"
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

(defn save-dialog
  "Render the save-variant modal. Visible iff `:open?` is true on the
  dialog ratom. The snippet re-generates on every keystroke as the user
  edits the new variant id; copy-to-clipboard surfaces the form for the
  user to paste into source.

  rf2-lancu: when the captured snapshot violates the variant's Spec 010
  schema, a non-blocking hint renders above the snippet listing the
  violating keys. Non-blocking — the user can still paste; the snippet
  carries the violating args as captured (paste at your own risk).

  Public so tests can render the dialog hiccup directly after seeding
  the dialog ratom."
  []
  (let [dialog @ui-dialog]
    (when (:open? dialog)
      (let [{:keys [draft-id source-id args violations]} dialog
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
                               (violations-hint violations)]
           :snippet           snippet
           :placeholder-id    :story.saved/example
           :placeholder-input ":story.your-story/saved-flow"
           :on-edit-id        set-draft-id!
           :on-copy           (fn [] (review-dialog/copy-to-clipboard! snippet))
           :on-close          close-dialog!
           :data-test-prefix  "story-save-variant"})))))
