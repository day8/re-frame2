(ns day8.re-frame2-causa.static.mode-pill
  "Ribbon-left mode pill — two-segment radio toggling Runtime ↔ Static
  (rf2-o5f5f.1).

  ## Purpose

  Causa exposes two modes per `tools/causa/spec/007-UX-IA.md` §Static
  mode + the findings doc `ai/findings/2026-05-19-causa-explorer-
  mode.md`:

    - **Runtime** — the event-coupled spine + 4-layer chrome. The
      surface the rest of Causa ships today.
    - **Static** — event-independent browse of what's registered
      (Machines / Routes / Schemas / Views / Events). Same design
      language as Runtime; differentiation is temperature, not
      vocabulary.

  The pill is the user-facing toggle that lives at ribbon-left in
  BOTH modes. Cmd-Shift-M (the global chord — see `keybinding.cljs`)
  fires the same `:rf.causa/toggle-mode` event so the chord and the
  pill are wired to the same handler.

  ## Geometry

  Per the parent epic's mode-signal mechanism (4 stacked signals;
  signal #1 is the pill):

    - 160px total width (28px tall, two 76px halves + a 2px divider —
      the rounded corners + 1px borders fold into the half widths).
    - Accent-violet fill + white glyph on the active segment.
    - 200ms cross-fade on the active-state swap, threaded through the
      `--rf-causa-motion-scale` seam so `prefers-reduced-motion: reduce`
      collapses the fade to a single frame.

  ## Why a single registered view, not two leaf fns

  `(rf/reg-view mode-pill …)` is the canonical Causa shape: subscribes
  inside the component body resolve to `:rf/causa` via React-context
  (rf2-in6l2 / Spec 004 §Plain Reagent fns do not pick up the
  surrounding frame). The two segments are inline so the keyed
  cross-fade has one DOM container to interpolate against.

  ## Production posture

  Mounted only by `static/shell.cljs` (Static mode) AND by `shell.cljs`'s
  L1 ribbon (Runtime mode) — see the ribbon-left cluster wiring. Both
  call sites pass the current mode in as a prop so the pill doesn't
  re-subscribe per render."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens type-scale sans-stack]]))

;; ---- segments + glyphs --------------------------------------------------

(def ^:private segments
  "Pure inventory of the pill's two segments. Order is left → right and
  ships the visible label, glyph, and the mode keyword each segment
  selects when clicked. Exported (not private) so tests can assert
  against the canonical inventory without hard-coding the strings.

  Glyphs match Causa's existing `●` (active) / `○` (inactive) language
  (cf. `shell.cljs/tab-button`). The selected segment is rendered with
  `●`; the other with `○`."
  [{:mode :runtime :label "Runtime" :title "Runtime mode — event-coupled spine (Cmd-Shift-M)"}
   {:mode :static  :label "Static"  :title "Static mode — registry browse (Cmd-Shift-M)"}])

(defn segment-glyph
  "Pure helper. `●` for the active segment, `○` for the inactive one.
  Mirrors the chrome's `tab-button` convention."
  [{:keys [mode]} active-mode]
  (if (= mode active-mode) "●" "○"))

;; ---- view ---------------------------------------------------------------

(defn- segment-style
  "Inline style for one segment. Active segments paint accent-violet on
  the canonical chrome surface (`bg-2`) so the pill sits cleanly on
  the ribbon's `bg-1`. Inactive segments are flat — the pill reads as
  a radio, not a dual-button toolbar.

  Per parent-epic signal #1 the cross-fade rides
  `--rf-causa-motion-scale` so `prefers-reduced-motion: reduce`
  collapses the transition to a single frame (see `theme/global-
  styles/motion-css`)."
  [active?]
  (let [duration (t/duration-css 200)]
    (cond-> {:background      (if active? (:accent-violet tokens) "transparent")
             :color           (if active? (:white tokens) (:text-secondary tokens))
             :border          "none"
             :border-radius   "12px"
             :cursor          "pointer"
             :display         "inline-flex"
             :align-items     "center"
             :gap             "4px"
             :flex            "1 1 50%"
             :height          "24px"
             :justify-content "center"
             :font-family     sans-stack
             :font-size       (:caption type-scale)
             :font-weight     (if active? 600 500)
             :padding         "0"
             :transition      (str "background-color " duration " ease-out, "
                                   "color " duration " ease-out")}
      active? (assoc :box-shadow "0 0 0 1px rgba(124, 92, 255, 0.35)"))))

(defn- segment-button
  "One pill segment. Click dispatches `:rf.causa/set-mode <mode>` against
  the `:rf/causa` frame so the slot lands on Causa's app-db. The
  segment is a `<button>` so screen-readers + keyboard navigation
  work out of the box; `role='radio'` + `aria-checked` exposes the
  proper ARIA radio-group pattern (a paired radio-pill, not two
  independent toggles)."
  [{:keys [mode label title] :as seg} active-mode]
  (let [active? (= mode active-mode)]
    [:button {:data-testid     (str "rf-causa-mode-pill-" (name mode))
              :role            "radio"
              :aria-checked    (if active? "true" "false")
              :aria-label      (str "Switch to " label " mode")
              :title           title
              :on-click        (when-not active?
                                 #(rf/dispatch [:rf.causa/set-mode mode]
                                               {:frame :rf/causa}))
              :style           (segment-style active?)}
     [:span {:style {:font-size (:micro type-scale)}}
      (segment-glyph seg active-mode)]
     label]))

(rf/reg-view mode-pill
  "Ribbon-left mode pill (rf2-o5f5f.1) — two-segment radio toggling
  Runtime ↔ Static. The active segment paints accent-violet; the
  inactive segment is flat. 200ms cross-fade via CSS transitions
  (no JS animation), threaded through the `--rf-causa-motion-scale`
  seam so reduced-motion users see an instant swap.

  Per spec/007-UX-IA.md §Static mode + parent-epic rf2-o5f5f mode-
  signal mechanism (signal #1).

  The component itself subscribes to `:rf.causa/mode` — no prop
  threading required at the call site. `reg-view`-registered so
  the subscribe resolves to `:rf/causa` via React-context."
  []
  (let [active-mode @(rf/subscribe [:rf.causa/mode])]
    [:div {:data-testid "rf-causa-mode-pill"
           :role        "radiogroup"
           :aria-label  "Causa mode"
           :data-active-mode (name active-mode)
           :style       {:display         "inline-flex"
                         :align-items     "center"
                         :gap             "2px"
                         :width           "160px"
                         :height          "28px"
                         :padding         "2px"
                         :background      (:bg-2 tokens)
                         :border          (str "1px solid " (:border-default tokens))
                         :border-radius   "14px"
                         :flex-shrink     0}}
     (for [seg segments]
       ^{:key (:mode seg)}
       [segment-button seg active-mode])]))
