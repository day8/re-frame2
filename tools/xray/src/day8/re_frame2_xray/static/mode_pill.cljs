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

  ## Why a single boundary

  `(rf.fresco/defview mode-pill …)` is the canonical Xray shape: the
  boundary resolves its frame from REACT CONTEXT — the same context
  `rf/frame-provider` and `rf.fresco/frame-provider` both write — so its
  read lands on `:rf/xray` without either call site threading anything.
  A plain `defn` would not (Spec 000 §Plain Reagent fns do not pick up
  the surrounding frame / Spec 006 §Plain-fn footgun); it is a
  registered view precisely so the read routes. The `<select>` is native
  so keyboard + screen-reader navigation work out of the box.

  It was an `rf/reg-view` until rf2-k97c.3, which is why both ribbons
  used to reach it through an `as-child` Reagent island — see
  [[mode-pill]].

  ## Production posture

  Mounted only by `static/shell.cljs` (Static mode) AND by `shell.cljs`'s
  chrome ribbon (Dynamic mode) — see the chrome-left cluster wiring.
  The component reads `:rf.xray/mode` directly so neither call
  site threads the active mode as a prop."
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
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

(defn mode-pill-tree
  "The mode dropdown's WHOLE hiccup, as a pure function of the
  frame-bound `dispatch` and the resolved `:rf.xray/mode` — a compact
  single-select `<select>` toggling Dynamic ↔ Static. Shares the frame
  picker's control style (`bg-2`, `border-default`, 4px radius) so the
  two chrome-left selectors read as one stratum. The mode SIGNAL is the
  ribbon's left-edge accent stripe, not this control — the dropdown is
  deliberately understated for an occasional-use toggle.

  Selecting an option dispatches `:rf.xray/set-mode <mode>` against the
  `:rf/xray` frame so the slot lands on Xray's app-db (the same
  handler Cmd-Shift-M's `:rf.xray/toggle-mode` ends at).

  SPLIT OUT OF [[mode-pill]] BY rf2-k97c.3, for the reason every
  migrated view in this epic splits: a boundary's body may only run
  inside a React render window, so `(mode-pill)` is no longer a callable
  that answers hiccup. This is `defview`'s OWN documented
  extract-a-helper spelling, not an invention."
  [dispatch active-mode]
  [:select {:data-testid (str "rf-xray-mode-pill")
            :data-active-mode (name active-mode)
            :aria-label  "Xray mode"
            :title       "Switch Xray mode — Dynamic / Static (Cmd-Shift-M)"
            :value       (name active-mode)
            :on-change   (fn [^js e]
                           (let [v    (.. e -target -value)
                                 mode (keyword v)]
                             (when (not= mode active-mode)
                               ;; rf2-k97c.3 — the frame-bound dispatcher
                               ;; [[mode-pill]]'s boundary body captured.
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
     [:option {:key         mode
               :data-testid (str "rf-xray-mode-pill-" (name mode))
               :value       (name mode)}
      label])])

(rf.fresco/defview mode-pill
  "Chrome-ribbon mode dropdown, and a FRESCO BOUNDARY (rf2-k97c.3)
  rather than an `rf/reg-view`. [[mode-pill-tree]] carries the shape and
  the design reasoning; this is the read and the dispatcher, and nothing
  else.

  ## rf2-k97c.3 — the boundary that deleted TWO ISLANDS

  Both shells' L1 ribbons — Dynamic `shell.cljs` and `static/shell.cljs`
  — are Fresco boundaries, and while this was an `rf/reg-view` each of
  them had to reach it across an `as-child` REAGENT ISLAND, because a
  `reg-view` grades `:invalid` as a Fresco head down the same arm a
  plain `defn` does. Migrating it deletes both of those seams: each
  ribbon now heads `[mode-pill {}]` directly, the way it heads any other
  boundary.

  The READ is `rf.fresco/sub`, a plain call the shipped collector
  records an edge for — no deref, no reaction owned by the installed
  adapter, and a re-wire that NOTIFIES when the substrate disposes the
  underlying derived value.

  The DISPATCHER is `(:dispatch (rf/capture-frame))` — core's own door,
  which answers the boundary's DECLARED frame inside a body and replaces
  the `dispatch` the `reg-view` body used to inject lexically.

  The argument is the ordinary one-props-map vector every `defview`
  takes. Both ribbons mount it with none, so it is destructured away."
  [_props]
  (mode-pill-tree (:dispatch (rf/capture-frame))
                  (rf.fresco/sub [:rf.xray/mode])))
