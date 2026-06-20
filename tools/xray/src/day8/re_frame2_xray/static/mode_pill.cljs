(ns day8.re-frame2-xray.static.mode-pill
  "Ribbon-left mode control — a compact single-select dropdown toggling
  Dynamic ↔ Static.

  ## Purpose

  Xray exposes two modes per `tools/xray/spec/007-UX-IA.md` §Static
  mode + the findings doc `ai/findings/2026-05-19-xray-explorer-
  mode.md`:

    - **Dynamic** — the event-coupled spine + 4-layer chrome. The
      surface the rest of Xray ships today.
    - **Static** — event-independent browse of what's registered
      (Machines / Routes / Schemas / Flows / Interceptors). Same
      design language as Dynamic; differentiation is temperature,
      not vocabulary.

  The control is the user-facing toggle that lives at chrome-ribbon-
  left in BOTH modes. Cmd-Shift-M (the global chord — see
  `keybinding.cljs`) fires the same `:rf.xray/toggle-mode` event so the
  chord and the dropdown are wired to the same handler.

  ## Why a dropdown

  Mode is an OCCASIONAL-use control — most sessions never flip it, so a
  compact `<select>` is the right weight: understated and undominant at
  chrome-left where the Frame picker also lives. It shares the frame
  picker's weight (`bg-2` fill, `border-default` hairline, 4px radius).
  The theme carries a single `:accent` (GitHub blue); the ribbon stripe
  does not change colour by mode. The `<select>`'s `data-active-mode`
  attribute + active option carry the mode state, and the
  Dynamic/Static MODE drives motion/pulse rather than accent colour.

  ## Why a single registered view

  `(rf/reg-view mode-pill …)` is the canonical Xray shape: subscribes
  inside the component body resolve to `:rf/xray` via React-context
  (Spec 004 §Plain Reagent fns do not pick up the surrounding frame).
  The `<select>` is native so keyboard + screen-reader navigation work
  out of the box.

  ## Production posture

  Mounted only by `static/shell.cljs` (Static mode) AND by `shell.cljs`'s
  chrome ribbon (Dynamic mode) — see the chrome-left cluster wiring.
  The component subscribes to `:rf.xray/mode` directly so neither call
  site threads the active mode as a prop."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens type-scale sans-stack]]))

;; ---- options ------------------------------------------------------------

(def ^:private modes
  "Pure inventory of the dropdown's two options. Order is the visible
  option order and ships the label + the mode keyword each option
  selects. Exported (not private) so tests can assert against the
  canonical inventory without hard-coding the strings."
  [{:mode :dynamic :label "Dynamic"}
   {:mode :static  :label "Static"}])

(defn mode-label
  "Pure helper. The display label for `mode` (`Dynamic` / `Static`).
  Falls back to the Dynamic label for an unrecognised keyword so the
  control always renders a stable option string."
  [mode]
  (or (some (fn [{m :mode l :label}] (when (= m mode) l)) modes)
      "Dynamic"))

;; ---- view ---------------------------------------------------------------

(rf/reg-view mode-pill
  "Chrome-ribbon mode dropdown — a compact single-select `<select>`
  toggling Dynamic ↔ Static. Shares the frame
  picker's control style (`bg-2`, `border-default`, 4px radius) so the
  two chrome-left selectors read as one stratum. The mode SIGNAL is the
  ribbon's left-edge accent stripe, not this control — the dropdown is
  deliberately understated for an occasional-use toggle.

  Selecting an option dispatches `:rf.xray/set-mode <mode>` against the
  `:rf/xray` frame so the slot lands on Xray's app-db (the same
  handler Cmd-Shift-M's `:rf.xray/toggle-mode` ends at).

  The component subscribes to `:rf.xray/mode` — no prop threading at
  the call site. `reg-view`-registered so the subscribe resolves to
  `:rf/xray` via React-context."
  []
  (let [active-mode @(rf/subscribe [:rf.xray/mode])]
    [:select {:data-testid (str "rf-xray-mode-pill")
              :data-active-mode (name active-mode)
              :aria-label  "Xray mode"
              :title       "Switch Xray mode — Dynamic / Static (Cmd-Shift-M)"
              :value       (name active-mode)
              :on-change   (fn [^js e]
                             (let [v    (.. e -target -value)
                                   mode (keyword v)]
                               (when (not= mode active-mode)
                                 ;; reg-view-injected frame-aware
                                 ;; dispatch.
                                 (dispatch [:rf.xray/set-mode mode]))))
              :style       {:background    (:bg-2 tokens)
                            :color         (:text-primary tokens)
                            :border        (str "1px solid " (:border-default tokens))
                            :border-radius "4px"
                            :padding       "2px 6px"
                            :font-family   sans-stack
                            :font-size     (:body-tight type-scale)
                            :line-height   "1.4"
                            :cursor        "pointer"
                            :flex-shrink   0}}
     (for [{:keys [mode label]} modes]
       ^{:key mode}
       [:option {:data-testid (str "rf-xray-mode-pill-" (name mode))
                 :value       (name mode)}
        label])]))
