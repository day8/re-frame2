(ns day8.re-frame2-xray.panels.epoch.view
  "View layer for the Epoch panel (rf2-sc3r1) — the numbered cascade
  rendering of a single epoch's pipeline steps as a delightful,
  detailed visual schematic.

  ## Visual contract

  Renders the pure-data projection from `panels.epoch.projection` as a
  vertical, numbered cascade. Per the bead body's §Visual Structure:

      ┌── ① DISPATCH      from ui ↗                    0.1ms
      │      [:counter-inc]
      │
      ├── ② COEFFECT      :session ↗
      │      + [:session] {:user-id 42 …}
      │
      ├── ③ HANDLER       reg-event-db ↗               0.5ms
      │      (fn [db [_ amount]]
      │        (update db :total + amount))
      │      ↳ :db diff
      │        ~ [:total]  100 → 110
      │
      ├── ④ FX           side effects
      │      ✓ :db → app-db
      │      ✓ :http/post {url ...}
      │
      ├── ⑤ SUBSCRIPTIONS
      │      ┌─ sub                ─ inputs ─ changed
      │      ├─ :total-sub ↗       app-db    ✓ 100 → 110
      │
      └── ⑥ VIEWS
             ┌─ view              ─ subs
             ├─ ::counter-view ↗   :total-sub

  Steps appear ONLY when the corresponding trace events surfaced —
  absence is conveyed by omission, not an empty-state line. The
  vertical rail + numbered circles are positioned absolutely so they
  read as one continuous timeline regardless of which steps render.

  ## Expansion state

  Per-row EDN expansion (clicking a row's header opens the
  edn-inspector for the row's payload) is stored in the Xray app-db
  under `:epoch-panel-expanded-rows` (a set of `[step-kw row-id]`
  pairs). The view subscribes to the expanded-set sub and dispatches
  toggle events; the edn-inspector widget composes naturally with
  `:zoomable? true` (rf2-h71e0) + `:header` (rf2-okq7p) per the
  bead body's §edn-inspector composition.

  ## Pure hiccup

  The panel emits hiccup; the substrate adapter installed via
  `rf/init!` handles rendering. Each step body is a body-returning
  helper composed into the numbered cascade by `pipeline-view`."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.panels.epoch.badge :as badge]
            [day8.re-frame2-xray.panels.epoch.icons :as icons]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [day8.re-frame2-xray.panels.shared.coord-chip :as coord-chip]
            [day8.re-frame2-xray.views.diff-mode-toggle :as diff-mode]
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [day8.re-frame2-xray.views.edn-widget.widget :as edn]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- style hoists (rf2-zlk6h) -------------------------------------------
;;
;; Every literal `:style {...}` map in the renderers below is hoisted to
;; ns-top defs (rf2-zlk6h, follow-on to rf2-qx414 / rf2-xjgdk / rf2-gjiog
;; / rf2-alsnz). The Epoch panel renders ~10 step renderers, each
;; emitting ~5-20 `:style {...}` maps; a typical focused-epoch render
;; mints ~200 fresh JS objects to feed the React reconciler before the
;; hoist. `tokens` values resolve to `var(--rf-xray-*)` CSS strings at
;; ns load — theme switching rides the CSS-variable seam (spec/007
;; §UX-IA), so the hoisted maps follow light/dark without re-evaluation.
;;
;; Per-call variation rides:
;;   - small `assoc` / `cond->` overlays on a shared base map
;;     (cursor flips, kind-colour swaps), and
;;   - hoisted named variants selected by key (active/inactive buttons,
;;     long-step vs default duration chip).

;; -- pre-resolved palette pieces -------------------------------------------

(def ^:private accent-colour       (:accent          tokens))
(def ^:private text-primary-colour (:text-primary    tokens))
(def ^:private text-secondary-colour (:text-secondary tokens))
(def ^:private text-tertiary-colour  (:text-tertiary  tokens))
(def ^:private success-colour      (:success         tokens))
(def ^:private warning-colour      (:warning         tokens))
(def ^:private error-colour        (:error           tokens))
(def ^:private white-colour        (:white           tokens))
(def ^:private bg-1-colour         (:bg-1            tokens))
(def ^:private bg-2-colour         (:bg-2            tokens))
(def ^:private bg-3-colour         (:bg-3            tokens))
(def ^:private bg-violation-colour (:bg-violation    tokens))
(def ^:private border-subtle-colour  (:border-subtle  tokens))
(def ^:private border-default-colour (:border-default tokens))

(def ^:private border-subtle-1px  (str "1px solid " border-subtle-colour))
(def ^:private border-default-1px (str "1px solid " border-default-colour))

;; -- badge-pill ------------------------------------------------------------

(def ^:private badge-pill-base-style
  {:display       "inline-flex"
   :align-items   "center"
   :color         white-colour
   :font-size     "10px"
   :font-weight   700
   :padding       "3px 5px"
   :border-radius "3px"
   :line-height   1
   :white-space   "nowrap"})

(def ^:private badge-pill-edn-overlay
  {:font-family mono-stack})

(def ^:private badge-pill-text-overlay
  {:font-family    sans-stack
   :letter-spacing "0.5px"
   :text-transform "uppercase"})

;; -- numbered-circle -------------------------------------------------------

(def ^:private numbered-circle-base-style
  {:position        "absolute"
   :left            "-44px"
   :top             0
   :width           "21px"
   :height          "21px"
   :border-radius   "50%"
   :color           white-colour
   :display         "inline-flex"
   :align-items     "center"
   :justify-content "center"
   :font-family     mono-stack
   :font-size       "11px"
   :font-weight     700
   :line-height     1
   :z-index         1})

;; -- duration-chip ---------------------------------------------------------

(def ^:private duration-chip-base-style
  {:font-family  mono-stack
   :font-size    "11px"
   :white-space  "nowrap"
   :margin-left  "auto"
   :padding-left "8px"
   :display      "inline-flex"
   :align-items  "center"
   :gap          "4px"})

(def ^:private duration-chip-default-style
  (assoc duration-chip-base-style
         :color       text-tertiary-colour
         :font-weight 500))

(def ^:private duration-chip-long-style
  (assoc duration-chip-base-style
         :color       warning-colour
         :font-weight 700))

(def ^:private duration-chip-long-glyph-style
  {:font-size "10px"})

;; -- step-header -----------------------------------------------------------

(def ^:private step-header-base-style
  {:display     "flex"
   :align-items "center"
   :gap         "8px"
   :font-family sans-stack
   :font-size   "12px"
   :color       text-primary-colour
   :user-select "none"})

(def ^:private step-header-pointer-style
  (assoc step-header-base-style :cursor "pointer"))

(def ^:private step-header-default-cursor-style
  (assoc step-header-base-style :cursor "default"))

(def ^:private step-header-verb-style
  {:display       "inline-flex"
   :align-items   "center"
   :gap           "4px"
   :font-family   mono-stack
   :font-size     "12px"
   :color         text-primary-colour
   :min-width     0
   :flex          "1 1 auto"
   :overflow      "hidden"
   :text-overflow "ellipsis"
   :white-space   "nowrap"})

(def ^:private step-header-expand-glyph-style
  {:color       text-tertiary-colour
   :font-family mono-stack
   :font-size   "10px"
   :margin-left "4px"})

;; -- sub-header -----------------------------------------------------------

(def ^:private sub-header-style
  ;; Lowercase by design — sub-headers render `:db` / `:fx` /
  ;; literal labels like "cascade" as-typed so EDN-keyword identity
  ;; reads accurately (the prior `:text-transform "uppercase"` was
  ;; rendering `:DB` / `:FX` which read as if the framework had
  ;; uppercase keyword names).
  {:display        "flex"
   :align-items    "center"
   :gap            "6px"
   :margin         "8px 0 5px 0"
   :font-family    sans-stack
   :font-size      "11px"
   :font-weight    600
   :color          text-tertiary-colour
   :letter-spacing "0.5px"})

(def ^:private sub-header-glyph-style
  {:display "inline-flex"
   :color   text-tertiary-colour})

(def ^:private sub-header-trailing-style
  {:color          text-tertiary-colour
   :font-weight    400
   :font-family    mono-stack
   :text-transform "none"})

;; -- DISPATCH --------------------------------------------------------------

(def ^:private dispatch-body-style
  {:background    bg-1-colour
   :border        border-default-1px
   :border-radius "3px"
   :padding       "5px 8px"
   :margin-top    "5px"
   :overflow-x    "auto"})

(def ^:private dispatch-verb-style
  {:display "inline-flex" :align-items "center" :gap "4px"})

(def ^:private link-button-style
  {:background            "transparent"
   :border                "none"
   :padding               0
   :margin                0
   :color                 accent-colour
   :cursor                "pointer"
   :font-family           mono-stack
   :font-size             "12px"
   :text-decoration       "underline"
   :text-decoration-style "dotted"
   :text-underline-offset "2px"
   :display               "inline-flex"
   :align-items           "center"
   :gap                   "4px"})

(def ^:private link-button-nowrap-style
  (assoc link-button-style :white-space "nowrap"))

(def ^:private link-button-inherit-base-style
  {:background            "transparent"
   :border                "none"
   :padding               0
   :margin                0
   :color                 accent-colour
   :cursor                "pointer"
   :font-family           "inherit"
   :font-size             "inherit"
   :font-weight           "inherit"
   :text-decoration       "underline"
   :text-decoration-style "dotted"
   :text-underline-offset "2px"
   :display               "inline-flex"
   :align-items           "center"
   :gap                   "4px"})

(def ^:private link-button-inherit-style
  (assoc link-button-inherit-base-style :white-space "nowrap"))

(def ^:private dispatch-source-plain-style
  {:color       accent-colour
   :display     "inline-flex"
   :align-items "center"})

;; -- DISPATCH source enrichment (rf2-5qp4g) -------------------------------
;;
;; Each closed-set substrate-internal `:source` value (rf2-ejtpd:
;; `:after-timer`, `:machine-spawn`, `:fx-dispatch`, `:fx-dispatch-later`)
;; renders a richer label than the prior `from <source>` chrome — the
;; specific delay / state-path / spawned-actor / parent-epoch becomes
;; visible chrome on the DISPATCH header.

(def ^:private dispatch-source-detail-style
  "Trailing detail chip following the kind label (e.g. ` · 250ms`)."
  {:color       text-tertiary-colour
   :font-family mono-stack
   :font-size   "12px"
   :white-space "nowrap"})

(def ^:private dispatch-source-state-path-button-style
  "State-path click-to-source button — accent-coloured, dotted-
  underlined, mono. Mirrors `link-button-style` but rides the smaller
  detail-chip font sizing."
  (assoc link-button-style :font-size "12px"))

(def ^:private dispatch-source-state-path-plain-style
  "Plain (non-clickable) state-path rendering when no coord is
  available — accent-coloured monospace span."
  {:color       accent-colour
   :font-family mono-stack
   :font-size   "12px"
   :white-space "nowrap"})

(def ^:private dispatch-source-parent-epoch-button-style
  "Parent-epoch navigation button — accent-coloured, dotted-underlined,
  mono. Clicking dispatches `:rf.xray/focus-epoch` to navigate the
  Epoch panel to the parent cascade."
  (assoc link-button-style :font-size "12px"))

(def ^:private dispatch-source-parent-epoch-plain-style
  "Plain (non-clickable) parent-epoch rendering when no parent-epoch
  could be resolved (root cascade or evicted from the buffer)."
  {:color       text-tertiary-colour
   :font-family mono-stack
   :font-size   "12px"
   :white-space "nowrap"})

;; -- COEFFECT --------------------------------------------------------------

(def ^:private coeffect-row-style
  {:padding     "3px 0"
   :display     "flex"
   :align-items "flex-start"
   :gap         "8px"
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private coeffect-row-id-plain-style
  {:color       accent-colour
   :white-space "nowrap"})

(def ^:private coeffect-row-value-style
  {:color      text-primary-colour
   :min-width  0
   :flex       1
   :word-break "break-word"})

(def ^:private coeffect-verb-link-button-style
  (assoc link-button-style
         :font-weight "inherit"
         :white-space "nowrap"))

(def ^:private coeffect-verb-plain-style
  {:color       accent-colour
   :font-family mono-stack
   :font-size   "12px"
   :white-space "nowrap"})

(def ^:private coeffect-body-style
  {:margin-top  "5px"
   :display     "flex"
   :align-items "flex-start"
   :gap         "8px"
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private coeffect-body-plus-style
  {:color success-colour :font-weight 700})

(def ^:private coeffect-body-path-style
  {:color text-tertiary-colour :white-space "nowrap"})

(def ^:private coeffect-body-value-style
  {:color      text-primary-colour
   :min-width  0
   :flex       1
   :word-break "break-word"})

;; -- db-diff / fx-entry ----------------------------------------------------

(def ^:private diff-row-style
  {:display     "flex"
   :align-items "flex-start"
   :gap         "8px"
   :padding     "2px 0"
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private diff-path-style
  {:color text-tertiary-colour :white-space "nowrap"})

(def ^:private diff-arrow-style
  {:color text-tertiary-colour})

(def ^:private diff-before-style
  {:color error-colour})

(def ^:private diff-after-success-style
  {:color success-colour})

(def ^:private diff-added-flex-style
  {:color success-colour :min-width 0 :flex 1 :word-break "break-word"})

(def ^:private diff-glyph-bold-style
  {:font-weight 700})

;; -- cascade outcome / phase / kind / ordinal / verb ----------------------

(def ^:private cascade-outcome-chip-base-style
  {:display     "inline-flex"
   :align-items "center"
   :gap         "4px"
   :font-family mono-stack
   :font-size   "11px"
   :font-weight 600
   :white-space "nowrap"})

(def ^:private cascade-phase-style
  {:display        "inline-flex"
   :align-items    "center"
   :background     bg-3-colour
   :color          text-tertiary-colour
   :border         (str "1px solid " border-subtle-colour)
   :border-radius  "2px"
   :padding        "1px 5px"
   :font-family    mono-stack
   :font-size      "10px"
   :font-weight    600
   :letter-spacing "0.3px"
   :white-space    "nowrap"})

(def ^:private cascade-kind-pill-base-style
  {:display        "inline-flex"
   :align-items    "center"
   :color          white-colour
   :font-family    sans-stack
   :font-size      "9px"
   :font-weight    700
   :padding        "2px 5px"
   :border-radius  "2px"
   :letter-spacing "0.5px"
   :text-transform "uppercase"
   :line-height    1
   :white-space    "nowrap"})

(def ^:private cascade-ordinal-style
  {:display         "inline-flex"
   :align-items     "center"
   :justify-content "center"
   :min-width       "21px"
   :height          "16px"
   :padding         "0 4px"
   :background      bg-3-colour
   :color           text-tertiary-colour
   :font-family     mono-stack
   :font-size       "10px"
   :font-weight     700
   :border-radius   "2px"
   :white-space     "nowrap"})

(def ^:private cascade-verb-link-button-style
  {:background            "transparent"
   :border                "none"
   :padding               0
   :margin                0
   :color                 accent-colour
   :cursor                "pointer"
   :font-family           mono-stack
   :font-size             "12px"
   :font-weight           600
   :text-decoration       "underline"
   :text-decoration-style "dotted"
   :text-underline-offset "2px"
   :display               "inline-flex"
   :align-items           "center"
   :gap                   "4px"
   :white-space           "nowrap"})

(def ^:private cascade-verb-plain-style
  {:color       text-primary-colour
   :font-family mono-stack
   :font-size   "12px"
   :font-weight 600
   :white-space "nowrap"})

(def ^:private cascade-row-source-style
  {:margin       "5px 0 3px 0"
   :padding-left "8px"
   :border-left  (str "2px solid " border-subtle-colour)
   :min-width    0})

(def ^:private cascade-row-source-missing-style
  {:font-style  "italic"
   :font-family mono-stack
   :font-size   "11px"
   :color       text-tertiary-colour})

(def ^:private cascade-outcome-details-style
  {:padding        "2px 0 4px 21px"
   :display        "flex"
   :flex-direction "column"
   :gap            "2px"
   :font-family    mono-stack
   :font-size      "11px"})

(def ^:private cascade-detail-row-style
  {:display     "flex"
   :gap         "6px"
   :align-items "flex-start"
   :color       text-secondary-colour})

(def ^:private cascade-detail-fx-row-style
  {:display     "flex"
   :gap         "6px"
   :align-items "flex-start"
   :flex-wrap   "wrap"})

(def ^:private cascade-detail-success-arrow-style
  {:color success-colour :font-weight 700})

(def ^:private cascade-detail-accent-arrow-style
  {:color accent-colour :font-weight 700})

(def ^:private cascade-detail-label-style
  {:color text-tertiary-colour})

(def ^:private cascade-detail-value-style
  {:color      text-primary-colour
   :min-width  0
   :flex       1
   :word-break "break-word"})

(def ^:private cascade-detail-fx-chip-style
  {:color        accent-colour
   :margin-right "6px"})

(def ^:private cascade-detail-threw-row-style
  {:display     "flex"
   :gap         "6px"
   :align-items "flex-start"
   :color       error-colour})

(def ^:private cascade-threw-glyph-style {:font-weight 700})
(def ^:private cascade-threw-label-style {:font-weight 600})

(def ^:private cascade-threw-message-style
  {:color      text-secondary-colour
   :font-style "italic"})

(def ^:private cascade-transition-details-style
  {:padding        "3px 0 4px 21px"
   :display        "flex"
   :flex-direction "column"
   :gap            "2px"
   :font-family    mono-stack
   :font-size      "11px"})

(def ^:private cascade-from-to-row-style
  {:display     "inline-flex"
   :align-items "center"
   :gap         "6px"
   :flex-wrap   "wrap"})

(def ^:private cascade-trigger-row-style
  {:display     "inline-flex"
   :align-items "center"
   :gap         "6px"
   :color       text-secondary-colour})

(def ^:private cascade-row-style
  {:display        "flex"
   :flex-direction "column"
   :padding        "5px 0 5px 0"
   :border-bottom  border-subtle-1px})

(def ^:private cascade-row-header-style
  {:display     "flex"
   :align-items "center"
   :gap         "6px"
   :flex-wrap   "wrap"})

(def ^:private cascade-row-right-style
  {:margin-left "auto"
   :display     "inline-flex"
   :align-items "center"
   :gap         "8px"
   :flex-wrap   "nowrap"})

(def ^:private machine-cascade-root-style
  {:margin-top     "8px"
   :display        "flex"
   :flex-direction "column"})

(def ^:private machine-cascade-summary-style
  {:display     "inline-flex"
   :align-items "center"
   :gap         "8px"
   :font-family mono-stack
   :font-size   "11px"})

(def ^:private machine-cascade-total-style
  {:color text-tertiary-colour})

(def ^:private machine-cascade-empty-style
  {:padding     "5px 0 5px 21px"
   :font-family mono-stack
   :font-size   "11px"
   :font-style  "italic"
   :color       text-tertiary-colour})

(def ^:private machine-cascade-rows-style
  {:display        "flex"
   :flex-direction "column"
   :border-top     border-subtle-1px
   :margin-top     "3px"})

;; -- handler verb-link / source ------------------------------------------

(def ^:private handler-verb-link-button-style
  link-button-inherit-base-style)

(def ^:private handler-verb-plain-style
  {:color accent-colour})

(def ^:private handler-source-root-style
  {:margin-top "8px"
   :min-width  "0"})

(def ^:private handler-source-spec-style
  {:padding-left "16px"})

(def ^:private handler-source-placeholder-style
  {:font-style   "italic"
   :font-family  mono-stack
   :font-size    "11px"
   :color        text-tertiary-colour
   :padding-left "16px"})

;; -- db-diff-mode toggle / db-diff ----------------------------------------

(def ^:private mode-toggle-bar-style
  {:display       "inline-flex"
   :align-items   "center"
   :gap           0
   :border        border-subtle-1px
   :border-radius "3px"
   :overflow      "hidden"
   :margin-left   "8px"
   :line-height   1})

(def ^:private mode-toggle-button-base-style
  {:border         "none"
   :padding        "2px 8px"
   :font-family    sans-stack
   :font-size      "10px"
   :font-weight    700
   :text-transform "uppercase"
   :letter-spacing "0.5px"
   :cursor         "pointer"
   :line-height    1})

(def ^:private mode-toggle-button-active-style
  (assoc mode-toggle-button-base-style
         :background accent-colour
         :color      white-colour))

(def ^:private mode-toggle-button-inactive-style
  (assoc mode-toggle-button-base-style
         :background "transparent"
         :color      text-secondary-colour))

(def ^:private inline-flex-center-style
  {:display "inline-flex" :align-items "center"})

(def ^:private handler-db-all-style
  {:padding-left "16px"})

(def ^:private handler-db-all-missing-style
  {:font-style   "italic"
   :font-family  mono-stack
   :font-size    "11px"
   :color        text-tertiary-colour
   :padding-left "16px"})

;; -- FLOW ------------------------------------------------------------------
;;
;; rf2-xnb1x — FLOW steps reuse the COEFFECT body styles (coeffect-body-*).
;; The legacy per-row flow styles retired with the aggregate step shape.

;; -- FX --------------------------------------------------------------------

(def ^:private fx-row-style
  {:display     "flex"
   :align-items "flex-start"
   :gap         "8px"
   :padding     "2px 0"
   :font-family mono-stack
   :font-size   "12px"
   :flex-wrap   "wrap"})

(def ^:private fx-row-id-style
  {:color accent-colour})

(def ^:private fx-row-args-style
  {:color      text-primary-colour
   :min-width  0
   :flex       1
   :word-break "break-word"})

(def ^:private fx-row-duration-style
  {:color       text-tertiary-colour
   :margin-left "8px"
   :white-space "nowrap"})

(def ^:private fx-row-attribution-style
  {:color       text-tertiary-colour
   :font-size   "10px"
   :margin-left "auto"
   :white-space "nowrap"
   :display     "inline-flex"
   :align-items "center"
   :gap         "4px"
   :font-style  "italic"})

(def ^:private fx-row-attribution-phase-style
  {:color text-tertiary-colour})

(def ^:private fx-verb-style
  {:display     "inline-flex"
   :align-items "center"
   :gap         "8px"})

(def ^:private fx-caption-style
  {:color       text-tertiary-colour
   :font-family sans-stack
   :font-size   "11px"
   :font-style  "italic"})

(def ^:private fx-threw-style
  {:color error-colour :font-weight 700})

(def ^:private margin-top-5-style
  {:margin-top "5px"})

;; -- SUBSCRIPTIONS table ---------------------------------------------------

(def ^:private subscriptions-table-style
  {:margin-top    "5px"
   :border        border-subtle-1px
   :border-radius "3px"
   :overflow      "hidden"})

(def ^:private table-header-row-style
  {:display        "flex"
   :align-items    "stretch"
   :background     bg-3-colour
   :border-bottom  border-subtle-1px
   :font-family    sans-stack
   :font-size      "10px"
   :font-weight    700
   :color          text-tertiary-colour
   :text-transform "uppercase"
   :letter-spacing "0.5px"})

(def ^:private subs-th-35-style
  {:flex "1 1 35%" :padding "5px 8px"})

(def ^:private subs-th-30-style
  {:flex "1 1 30%" :padding "5px 8px"})

(def ^:private subs-row-style
  {:display     "flex"
   :align-items "stretch"})

(def ^:private subs-row-style-with-border
  (assoc subs-row-style :border-bottom border-subtle-1px))

(def ^:private subs-cell-id-style
  {:flex        "1 1 35%"
   :padding     "5px 8px"
   :min-width   0
   :font-family mono-stack
   :font-size   "12px"
   :word-break  "break-word"})

(def ^:private subs-cell-inputs-style
  {:flex        "1 1 35%"
   :padding     "5px 8px"
   :min-width   0
   :font-family mono-stack
   :font-size   "12px"
   :color       text-tertiary-colour
   :word-break  "break-word"})

(def ^:private subs-cell-changed-style
  {:flex        "1 1 30%"
   :padding     "5px 8px"
   :min-width   0
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private subs-cell-id-span-style
  {:color       accent-colour
   :display     "inline-flex"
   :align-items "center"
   :gap         "4px"})

(def ^:private subs-inputs-list-style
  {:display "flex" :flex-direction "column" :gap "2px"})

(def ^:private subs-changed-row-style
  {:display     "flex"
   :gap         "6px"
   :flex-wrap   "wrap"
   :align-items "center"})

(def ^:private subs-changed-tick-style
  {:color success-colour :font-weight 700})

(def ^:private subs-unchanged-tick-style
  {:color text-tertiary-colour :font-weight 700})

;; -- SUBSCRIPTIONS filter button bar --------------------------------------

(def ^:private subs-filter-bar-style
  mode-toggle-bar-style)

(def ^:private subs-filter-button-active-style
  mode-toggle-button-active-style)

(def ^:private subs-filter-button-inactive-style
  mode-toggle-button-inactive-style)

(def ^:private subs-verb-style
  {:display     "inline-flex"
   :align-items "center"
   :gap         "8px"
   :flex-wrap   "wrap"})

(def ^:private subs-disposed-count-style
  {:color       text-tertiary-colour
   :font-family mono-stack
   :font-size   "11px"})

;; -- DISPOSED subs table --------------------------------------------------

(def ^:private disposed-subs-table-style
  {:margin-top    "8px"
   :border        border-subtle-1px
   :border-radius "3px"
   :overflow      "hidden"})

(def ^:private table-glyph-cell-header-style
  {:flex "0 0 24px" :padding "5px 8px"})

(def ^:private disposed-th-60-style
  {:flex "1 1 60%" :padding "5px 8px"})

(def ^:private disposed-th-40-style
  {:flex "1 1 40%" :padding "5px 8px"})

(def ^:private disposed-glyph-cell-style
  {:flex        "0 0 24px"
   :padding     "5px 8px"
   :color       error-colour
   :font-family mono-stack
   :font-size   "12px"
   :font-weight 700
   :text-align  "center"})

(def ^:private disposed-id-cell-style
  {:flex        "1 1 60%"
   :padding     "5px 8px"
   :min-width   0
   :font-family mono-stack
   :font-size   "12px"
   :word-break  "break-word"})

(def ^:private disposed-id-span-style
  {:color       text-secondary-colour
   :display     "inline-flex"
   :align-items "center"
   :gap         "4px"})

(def ^:private disposed-anonymous-style
  {:color text-tertiary-colour :font-style "italic"})

(def ^:private disposed-reason-cell-style
  {:flex        "1 1 40%"
   :padding     "5px 8px"
   :min-width   0
   :font-family mono-stack
   :font-size   "11px"
   :color       text-tertiary-colour
   :word-break  "break-word"})

;; -- VIEWS table ----------------------------------------------------------

(def ^:private views-table-style
  {:margin-top    "5px"
   :border        border-subtle-1px
   :border-radius "3px"
   :overflow      "hidden"})

(def ^:private views-th-50-style
  {:flex "1 1 50%" :padding "5px 8px"})

(def ^:private views-cell-view-style
  {:flex        "1 1 50%"
   :padding     "5px 8px"
   :min-width   0
   :font-family mono-stack
   :font-size   "12px"
   :word-break  "break-word"})

(def ^:private views-cell-subs-style
  {:flex        "1 1 50%"
   :padding     "5px 8px"
   :min-width   0
   :font-family mono-stack
   :font-size   "12px"
   :color       text-tertiary-colour
   :word-break  "break-word"})

(def ^:private views-cell-id-span-style
  {:color       accent-colour
   :display     "inline-flex"
   :align-items "center"
   :gap         "4px"})

(def ^:private views-cell-id-clickable-style
  (assoc views-cell-id-span-style :cursor "pointer"))

(def ^:private views-row-duration-style
  {:color       text-tertiary-colour
   :margin-left "8px"
   :font-size   "10px"})

(def ^:private views-anonymous-style
  {:color text-tertiary-colour :font-style "italic"})

(def ^:private views-subs-list-style
  {:display "flex" :flex-direction "column" :gap "2px"})

(def ^:private italic-style {:font-style "italic"})

;; -- UNMOUNTED views table -----------------------------------------------

(def ^:private unmounted-views-table-style
  disposed-subs-table-style)

(def ^:private unmounted-th-auto-style
  {:flex "1 1 auto" :padding "5px 8px"})

(def ^:private unmounted-glyph-cell-style
  disposed-glyph-cell-style)

(def ^:private unmounted-id-cell-style
  {:flex        "1 1 auto"
   :padding     "5px 8px"
   :min-width   0
   :font-family mono-stack
   :font-size   "12px"
   :word-break  "break-word"})

(def ^:private unmounted-id-span-style
  disposed-id-span-style)

;; -- SCHEMA VIOLATION sub-block (rf2-xgeag) -------------------------------
;;
;; Pink-wash sub-block that rides INSIDE its owning pipeline step's
;; body. The aggregate trailing SCHEMA-VIOLATIONS step retired with
;; rf2-xgeag in favour of this attached shape — the operator reads
;; the failing boundary inline with the work it failed on. Hot-reload
;; drift still rides a standalone tail step (no owning cascade step
;; exists); see `render-schema-hot-reload-step`.

(def ^:private schema-violation-block-style
  {:display        "flex"
   :flex-direction "column"
   :gap            "5px"
   :padding        "8px 10px"
   :margin         "5px 0"
   :background     bg-violation-colour
   :border         (str "1px solid " warning-colour)
   :border-radius  "3px"
   :font-family    mono-stack
   :font-size      "12px"})

(def ^:private schema-violation-title-style
  ;; Mixed case per rf2-2ek7t — the title reads "Schema Violation
  ;; Error" rather than "SCHEMA VIOLATION". The recovery chip
  ;; (rollback variant) carries the alert tone; uppercase here
  ;; would compound visual noise without aiding legibility.
  {:display     "flex"
   :align-items "center"
   :gap         "8px"
   :color       warning-colour
   :font-weight 700
   :font-size   "12px"
   :letter-spacing "0.2px"})

(def ^:private schema-violation-title-spacer-style
  {:flex 1})

(def ^:private schema-violation-recovery-chip-style
  {:padding        "2px 6px"
   :border-radius  "3px"
   :background     bg-3-colour
   :color          text-secondary-colour
   :font-size      "10px"
   :font-weight    600
   :text-transform "lowercase"
   :letter-spacing "0.3px"})

(def ^:private schema-violation-rollback-chip-style
  ;; Red bg + white text already conveys severity at a glance; the
  ;; prior `:text-transform "uppercase"` SHOUTED the longer
  ;; "Commit to app-db aborted" string and read as noisy. Mixed
  ;; case (`:text-transform "none"`) preserves source casing for
  ;; legibility while the red/white chrome carries the alert tone.
  (assoc schema-violation-recovery-chip-style
         :background     error-colour
         :color          white-colour
         :text-transform "none"))

(def ^:private schema-violation-headline-style
  {:color       text-primary-colour
   :font-weight 600})

(def ^:private schema-violation-headline-id-style
  {:color accent-colour})

(def ^:private schema-violation-line-style
  {:display "flex" :gap "8px" :align-items "baseline"})

(def ^:private schema-violation-line-label-style
  {:color       text-tertiary-colour
   :min-width   "65px"
   :font-size   "11px"})

(def ^:private schema-violation-line-value-style
  {:color      text-primary-colour
   :word-break "break-word"})

(def ^:private schema-violation-sensitive-style
  {:color      text-tertiary-colour
   :font-style "italic"
   :font-size  "10px"})

(def ^:private schema-violation-action-link-style
  {:background    "transparent"
   :border        "none"
   :padding       0
   :margin        0
   :color         accent-colour
   :cursor        "pointer"
   :font-family   mono-stack
   :font-size     "11px"
   :display       "inline-flex"
   :align-items   "center"
   :gap           "4px"
   :text-align    "left"})

(def ^:private schema-violation-actions-style
  {:display     "flex"
   :gap         "16px"
   :flex-wrap   "wrap"})

(def ^:private schema-violation-explain-toggle-style
  {:background  "transparent"
   :border      "none"
   :padding     0
   :margin      0
   :color       text-tertiary-colour
   :cursor      "pointer"
   :font-family mono-stack
   :font-size   "11px"
   :text-align  "left"})

(def ^:private schema-violation-explain-body-style
  {:color text-secondary-colour
   :font-size "11px"
   :background bg-2-colour
   :border border-subtle-1px
   :border-radius "3px"
   :padding "6px 8px"})

(def ^:private rolled-back-mute-style
  {:opacity 0.55})

(def ^:private rolled-back-banner-style
  {:display     "flex"
   :align-items "center"
   :gap         "8px"
   :margin-top  "5px"
   :padding     "4px 8px"
   :color       error-colour
   :font-family sans-stack
   :font-size   "11px"
   :font-style  "italic"})

;; -- CHILD DISPATCHES -----------------------------------------------------

(def ^:private child-dispatch-row-style
  {:display     "flex"
   :align-items "center"
   :gap         "8px"
   :padding     "3px 0"
   :font-family mono-stack
   :font-size   "12px"
   :flex-wrap   "wrap"})

(def ^:private child-dispatch-via-style
  {:color          text-tertiary-colour
   :font-size      "10px"
   :text-transform "uppercase"
   :letter-spacing "0.5px"
   :font-weight    600})

(def ^:private child-dispatch-event-style
  {:color      text-primary-colour
   :min-width  0
   :flex       1
   :word-break "break-word"})

(def ^:private child-dispatch-delay-style
  {:color text-tertiary-colour :font-size "10px"})

(def ^:private child-dispatch-jump-style
  {:background     "transparent"
   :border         border-default-1px
   :border-radius  "3px"
   :color          accent-colour
   :cursor         "pointer"
   :font-family    sans-stack
   :font-size      "10px"
   :padding        "2px 8px"
   :display        "inline-flex"
   :align-items    "center"
   :gap            "4px"
   :text-transform "uppercase"
   :letter-spacing "0.5px"})

(def ^:private child-dispatch-missing-style
  {:color      text-tertiary-colour
   :font-size  "10px"
   :font-style "italic"})

;; -- pipeline -------------------------------------------------------------

(def ^:private pipeline-host-style
  {:position     "relative"
   :padding-left "55px"
   :padding-top  "0"})

(def ^:private pipeline-step-style
  {:position      "relative"
   :margin-bottom "23px"
   :min-height    "21px"})

;; Rail position depends on `badge/line-left-offset-px` +
;; `badge/vertical-line-offset-px`. Resolved at ns load so the rail
;; map is allocated once.
(def ^:private pipeline-rail-style
  {:position       "absolute"
   :left           (str (+ 55 badge/line-left-offset-px) "px")
   :top            (str badge/vertical-line-offset-px "px")
   :bottom         "13px"
   :width          "1px"
   :background     border-default-colour
   :pointer-events "none"})

;; -- empty-state + Panel root --------------------------------------------

(def ^:private empty-state-style
  {:padding     "21px"
   :color       text-tertiary-colour
   :font-family sans-stack
   :font-size   "13px"})

(def ^:private panel-root-style
  {:height         "100%"
   :display        "flex"
   :flex-direction "column"
   :background     bg-2-colour
   :color          text-primary-colour
   :font-family    sans-stack
   :font-size      "13px"})

(def ^:private panel-scroll-style
  {:flex 1 :overflow "auto" :padding "21px"})

;; ---- expansion state helpers ---------------------------------------------
;;
;; The Epoch panel's row-expansion surface (`:rf.xray.epoch/toggle-
;; row-expand` event + `:rf.xray.epoch/expanded-rows` sub) is
;; registered by the orchestrator's `install!`. The current view
;; renders default-visible content for every step (the cascade's
;; punch is its always-visible rhythm); the toggle infrastructure
;; stays in place for the follow-on rich-expansion pass where
;; clicking a row's header mounts the edn-inspector widget under
;; the body via `:zoomable? true` + `:header "<step>"` (rf2-h71e0 /
;; rf2-okq7p) per the bead body's §edn-inspector composition.

;; ---- view-name hover-highlight (rf2-2f962) ------------------------------
;;
;; Hovering a view-id in the VIEWS step toggles the
;; `.rf-xray-view-highlight` class on the rendered view's root DOM node
;; (matched by Spec 006's `data-rf-view` attribute) — the same pink
;; diagonal-stripe affordance the Reactive panel's view-node carries
;; (rf2-e33ad / rf2-8l03l). The class lives in
;; `theme/global-styles` and is intentionally UNSCOPED so it reaches
;; the host app's frame outside the Xray shell. Pure DOM side-effect;
;; cleared on mouseleave; no layout perturbation.

(def ^:private view-highlight-class "rf-xray-view-highlight")

(defn- view-highlight-selector
  "DOM selector for a view-id (Spec 006 stamps `data-rf-view (str id)`)."
  [view-id]
  (str "[data-rf-view='" view-id "']"))

(defn- apply-view-highlight!
  [view-id]
  (when (and (exists? js/document) (some? view-id))
    (let [nodes (.querySelectorAll js/document
                                   (view-highlight-selector view-id))]
      (.forEach nodes
                (fn [^js node]
                  (.add (.-classList node) view-highlight-class)))
      nil)))

(defn- clear-view-highlight!
  [view-id]
  (when (and (exists? js/document) (some? view-id))
    (let [nodes (.querySelectorAll js/document
                                   (view-highlight-selector view-id))]
      (.forEach nodes
                (fn [^js node]
                  (.remove (.-classList node) view-highlight-class)))
      nil)))

;; ---- chrome helpers ------------------------------------------------------

(defn- badge-pill
  "Render a step's badge pill — uppercase 10px label inside a
  rounded-corners chip painted in the badge's colour.

  Per the bead body's §Numbered Cascade Pattern step 2:

      Badge pill: uppercase text, 10px font (devtools-micro),
                  rounded, padding 5px horizontal, 3px vertical

  Pair-debug 2026-05-26: when the badge's display label starts
  with `:` (e.g. `:fx`) the CSS uppercase + letter-spacing is
  skipped — the EDN-key-style label is shown as authored, distinct
  from the conventional step-name labels (DISPATCH, HANDLER, etc.)."
  [step-badge]
  (let [label (badge/label step-badge)
        edn-style? (and (string? label) (str/starts-with? label ":"))]
    [:span {:data-testid (str "rf-xray-epoch-badge-"
                              (str/lower-case (name step-badge)))
            :style (merge badge-pill-base-style
                          {:background (badge/colour step-badge)}
                          (if edn-style?
                            badge-pill-edn-overlay
                            badge-pill-text-overlay))}
     label]))

;; rf2-xgeag — violation sub-block + rolled-back banner are defined
;; further down the file (in the §SCHEMA VIOLATION sub-block section)
;; but each step renderer above attaches a `(violation-blocks ...)`
;; sub-block to its body. Forward-declared here so the namespace
;; compiles in source order without warnings.
(declare violation-blocks)
(declare violation-block)
(declare rolled-back-banner)

(defn- numbered-circle
  "Render the numbered circle — 21px diameter, painted in the step's
  badge colour with white numerals. Positioned absolutely at -44px
  from the content column's left edge per the bead body's §Numbered
  Cascade Pattern step 1."
  [step-number step-badge]
  [:span {:data-testid (str "rf-xray-epoch-circle-" step-number)
          :aria-label  (str "step " step-number " (" (name step-badge) ")")
          :style (assoc numbered-circle-base-style
                        :background (badge/colour step-badge))}
   (str step-number)])

(defn- duration-chip
  "Right-aligned duration chip rendered alongside a step's header.
  Returns nil for non-number durations so the view can elide the
  slot when the substrate didn't stamp one.

  Per rf2-nqt3d the chip carries a subtle long-step warning when
  the duration exceeds 16ms (one 60Hz frame). The warning is
  conveyed by a warning-tone colour + a small `▲` marker —
  alarmist `✗` chrome would crowd the cascade with noise on the
  common case where one step is naturally heavy."
  [duration-ms]
  (when (number? duration-ms)
    (let [long? (> duration-ms proj/long-step-threshold-ms)]
      [:span {:data-testid (if long?
                             "rf-xray-epoch-duration-long"
                             "rf-xray-epoch-duration")
              :data-long-step (str long?)
              :title (when long?
                       (str "step exceeded "
                            proj/long-step-threshold-ms
                            "ms (one 60Hz frame)"))
              :style (if long?
                       duration-chip-long-style
                       duration-chip-default-style)}
       (when long?
         [:span {:aria-hidden true
                 :style duration-chip-long-glyph-style}
          "▲"])
       (proj/format-duration-ms duration-ms)])))

;; coord-chip moved to `panels.shared.coord-chip/coord-chip` (rf2-xjgdk
;; audit L2 — the icon-only chip was duplicated across panels; one
;; canonical home + per-site overlays now). The Epoch panel renders
;; with the default `:color "inherit"` + `:margin-left "4px"` knobs,
;; which match this panel's prior shape exactly.

(defn- step-header
  "Render a step's header row — badge pill + verb/label + optional
  duration. The flex layout keeps the duration right-aligned via
  `margin-left: auto`. The whole header is wrapped in an interactive
  `<div>` so clicking anywhere on the row toggles `expanded?` when
  the step carries expandable content (`expandable?` true)."
  [{:keys [badge verb expandable? expanded? testid duration-ms]} on-toggle]
  [:div {:data-testid (str testid "-header")
         :on-click    (when (and expandable? on-toggle)
                        (fn [e]
                          (.stopPropagation e)
                          (on-toggle)))
         :style (if expandable?
                  step-header-pointer-style
                  step-header-default-cursor-style)}
   (badge-pill badge)
   [:span {:data-testid (str testid "-verb")
           :style step-header-verb-style}
    verb]
   (when expandable?
     [:span {:data-testid (str testid "-expand-glyph")
             :aria-hidden true
             :style step-header-expand-glyph-style}
      (if expanded? "▾" "▸")])
   (duration-chip duration-ms)])

(defn- sub-header
  "Render a sub-section header (`↳ :db diff` / `↳ :fx` / `↳ guards`)
  under a step's body — corner-down-right glyph + label + optional
  trailing count."
  ([label]
   (sub-header label nil))
  ([label trailing]
   [:div {:style sub-header-style}
    [:span {:style sub-header-glyph-style}
     (icons/corner-down-right)]
    [:span label]
    (when trailing
      [:span {:style sub-header-trailing-style}
       trailing])]))

;; ---- DISPATCH step -------------------------------------------------------

(defn dispatch-body
  "Render the DISPATCH step's expanded body — the event vector via the
  canonical edn-inspector widget. Per the bead body's §DISPATCH
  (Step 1).

  Per rf2-9jvx1 the body no longer repeats the `from <source>` line —
  the header already carries that descriptor; the body is detail-only.
  The click-to-source affordance rides on the header (rf2-93a7s).

  Per rf2-8w8er (subsumes rf2-nszcv) the event vector renders through
  the first-class `edn-inspector` widget so keywords paint magenta,
  numbers orange, strings green (rf2-79ojx palette), with
  width-aware inline/tree behaviour, sticky expansion, click-to-zoom,
  and sentinel chrome (`:rf/redacted`, `:rf.size/large-elided`). Pre-
  fix the body was plain text — the operator saw the dispatch event
  styled DIFFERENTLY in the Event panel (inspector-styled) vs the
  Epoch panel (plain) for the same value."
  [{:keys [event]}]
  (when (vector? event)
    [:div {:data-testid "rf-xray-epoch-dispatch-event"
           :style dispatch-body-style}
     [ei/edn-inspector event {:site-id "epoch-dispatch-event"
                              :card?   false
                              :zoomable? true}]]))

(defn- dispatch-source-label
  "Render the dispatch source label — `<source>` text. When the
  envelope carried a `:rf.trace/call-site` coord (rf2-80u5a), the
  label renders as a clickable button that opens the editor at the
  dispatch call-site (the React onClick / handler line that called
  `rf/dispatch`); the external-link icon rides alongside as a
  secondary cue. When no coord is available (fn-form dispatch,
  production builds with `goog.DEBUG=false`), the label renders as
  plain text — no broken / clickable-but-dead affordance.

  Click-through pattern mirrors the cross-panel
  `:rf.xray/open-in-editor` event (the same fx-id every other
  open-in-editor surface dispatches via)."
  [source coord]
  (let [label (if source (name source) "unknown")
        clickable? (and (map? coord) (seq (:file coord)))]
    (if clickable?
      [:button {:data-testid "rf-xray-epoch-dispatch-source-label"
                :aria-label  (str "open dispatch call-site for " label)
                :title       (str "open " (:file coord)
                                  (when (:line coord)
                                    (str ":" (:line coord)))
                                  " in editor")
                :on-click    (fn [e]
                               (.stopPropagation e)
                               (rf/dispatch
                                 [:rf.xray/open-in-editor
                                  {:source-coord coord}]
                                 {:frame :rf/xray}))
                :style link-button-style}
       label
       (icons/external-link)]
      [:span {:data-testid "rf-xray-epoch-dispatch-source-label"
              :style dispatch-source-plain-style}
       label])))

;; ---- rf2-5qp4g — DISPATCH source enrichment per source kind --------------
;;
;; The closed-set substrate-internal `:source` values (rf2-ejtpd:
;; `:after-timer`, `:machine-spawn`, `:fx-dispatch`,
;; `:fx-dispatch-later`) carry richer per-kind detail than the prior
;; bare `from <source>` chrome. Each kind renders a specific label +
;; click-affordance:
;;
;;   :after-timer       → 'from :after timer · 250ms on [:active :auth]'
;;                        (state-path → click-to-source on machine spec)
;;   :machine-spawn     → 'from machine spawn · :child-actor-id'
;;   :fx-dispatch       → 'from fx :dispatch · parent epoch #142'
;;                        (parent-epoch link → focus-epoch dispatch)
;;   :fx-dispatch-later → 'from fx :dispatch-later · 500ms ·
;;                         parent epoch #142'
;;
;; Vanilla source kinds (`:ui`, `:frame-init`, `:test-harness`,
;; `:unknown`) fall through to the pre-rf2-5qp4g `dispatch-source-label`
;; chrome unchanged (call-site click-to-source on the source word).

(defn- machine-state-path-coord
  "Resolve a `{:file :line}` coord for a machine state-path via the
  registered machine's `:rf.machine/source-coords` index (rf2-8bp3).

  `machine-id` is the machine event-id; `state-path` is a vector like
  `[:active :authenticating]`. We look up the index under
  `[:states :active :states :authenticating]` first (the spec-path
  shape produced by `state-spec-path-prefix`); the source-coords map
  may or may not have a coord for this specific state, so the lookup
  degrades gracefully.

  Returns nil when no coord was captured (production builds, fn-form
  machines, unregistered machine-id)."
  [machine-id state-path]
  (when (and (keyword? machine-id) (vector? state-path) (seq state-path))
    (let [machine-meta (try (rf/handler-meta :machine machine-id)
                            (catch :default _ nil))
          idx          (or (get-in machine-meta [:rf/machine :rf.machine/source-coords])
                           (:rf.machine/source-coords machine-meta))
          spec-path    (proj/state-spec-path-prefix state-path)
          c            (or (get idx spec-path)
                           ;; Fallback: lookup by the raw state-path tuple
                           ;; (some fixtures key by state-path directly).
                           (get idx state-path))]
      (when (and (map? c) (string? (:file c)) (seq (:file c)))
        {:file (:file c) :line (:line c)}))))

(defn- state-path-affordance
  "Render a state-path with click-to-source affordance when a coord is
  available; plain accent-coloured monospace span otherwise.

  `path-str` is the rendered vector text (e.g. `[:active :auth]`);
  `coord` is `{:file <string> :line <int>}` or nil; `testid` is the
  data-testid suffix."
  [path-str coord testid]
  (if (and (map? coord) (seq (:file coord)))
    [:button {:data-testid testid
              :aria-label  (str "open " (:file coord)
                                (when (:line coord) (str ":" (:line coord)))
                                " in editor")
              :title       (str "open " (:file coord)
                                (when (:line coord) (str ":" (:line coord)))
                                " in editor")
              :on-click    (fn [e]
                             (.stopPropagation e)
                             (rf/dispatch
                               [:rf.xray/open-in-editor
                                {:source-coord coord}]
                               {:frame :rf/xray}))
              :style dispatch-source-state-path-button-style}
     path-str
     (icons/external-link)]
    [:span {:data-testid testid
            :style dispatch-source-state-path-plain-style}
     path-str]))

(defn- parent-epoch-affordance
  "Render the `parent epoch #N` chrome for `:fx-dispatch` /
  `:fx-dispatch-later`. When `parent-epoch-id` is resolved against
  the supplied epoch-history, the chip renders as a clickable button
  that dispatches `[:rf.xray/focus-epoch <epoch-id>]` to navigate the
  Epoch panel to the parent cascade. When unresolved (root cascade
  or aged out of the buffer) the chip renders as a muted plain span
  with the parent-dispatch-id labelled to give the operator something
  to orient on."
  [parent-epoch-id parent-dispatch-id]
  (cond
    (some? parent-epoch-id)
    [:button {:data-testid "rf-xray-epoch-dispatch-parent-epoch-link"
              :aria-label  (str "focus parent epoch #" parent-epoch-id)
              :title       (str "focus parent epoch #" parent-epoch-id)
              :on-click    (fn [e]
                             (.stopPropagation e)
                             (rf/dispatch
                               [:rf.xray/focus-epoch parent-epoch-id]
                               {:frame :rf/xray}))
              :style dispatch-source-parent-epoch-button-style}
     (str "parent epoch #" parent-epoch-id)]

    (some? parent-dispatch-id)
    [:span {:data-testid "rf-xray-epoch-dispatch-parent-epoch-unresolved"
            :style dispatch-source-parent-epoch-plain-style}
     (str "parent dispatch #" parent-dispatch-id " (not in buffer)")]

    :else nil))

(defn- dispatch-after-timer-label
  "Render the `:after-timer` rich label:

      from :after timer · 250ms on [:active :authenticating]

  The state-path is a click-to-source affordance via the machine's
  `:rf.machine/source-coords` index (rf2-8bp3) when a coord was
  captured; plain accent-coloured monospace span otherwise."
  [{:keys [machine-id delay-ms source-state-path]}]
  (let [path-str (pr-str source-state-path)
        coord    (machine-state-path-coord machine-id source-state-path)]
    [:span {:data-testid "rf-xray-epoch-dispatch-source-label"
            :style dispatch-verb-style}
     [:span {:style dispatch-source-plain-style}
      "from :after timer"]
     (when delay-ms
       [:span {:data-testid "rf-xray-epoch-dispatch-after-timer-delay"
               :style dispatch-source-detail-style}
        (str " · " delay-ms "ms on ")])
     (when (and (vector? source-state-path) (seq source-state-path))
       (state-path-affordance
         path-str coord "rf-xray-epoch-dispatch-after-timer-state-path"))]))

(defn- dispatch-machine-spawn-label
  "Render the `:machine-spawn` rich label:

      from machine spawn · :child-actor-id

  No click-to-source affordance — the actor-id is the gensym'd
  identity of the spawned actor; resolving its spec source is a
  follow-on enrichment (rf2-5qp4g scope is the actor-id label)."
  [{:keys [spawned-actor-id]}]
  [:span {:data-testid "rf-xray-epoch-dispatch-source-label"
          :style dispatch-verb-style}
   [:span {:style dispatch-source-plain-style}
    "from machine spawn"]
   (when spawned-actor-id
     [:span {:data-testid "rf-xray-epoch-dispatch-machine-spawn-actor"
             :style dispatch-source-detail-style}
      (str " · " (pr-str spawned-actor-id))])])

(defn- dispatch-fx-label
  "Render the `:fx-dispatch` / `:fx-dispatch-later` rich label:

      from fx :dispatch · parent epoch #142
      from fx :dispatch-later · 500ms · parent epoch #142

  The parent-epoch chip is click-to-navigate via
  `:rf.xray/focus-epoch`. The delay-ms chip rides only for
  `:dispatch-later` when the original scheduled delay was stamped
  on the dispatched trace (`:rf.event/source-detail :ms`)."
  [source {:keys [parent-dispatch-id delay-ms]} epoch-history]
  (let [kind-label (case source
                     :fx-dispatch       ":dispatch"
                     :fx-dispatch-later ":dispatch-later"
                     (name source))
        parent-epoch-id (when (and parent-dispatch-id (seq epoch-history))
                          (proj/find-parent-epoch epoch-history parent-dispatch-id))]
    [:span {:data-testid "rf-xray-epoch-dispatch-source-label"
            :style dispatch-verb-style}
     [:span {:style dispatch-source-plain-style}
      (str "from fx " kind-label)]
     (when (and (= source :fx-dispatch-later) delay-ms)
       [:span {:data-testid "rf-xray-epoch-dispatch-fx-later-delay"
               :style dispatch-source-detail-style}
        (str " · " delay-ms "ms")])
     (when parent-dispatch-id
       [:span {:style dispatch-source-detail-style} " · "])
     (parent-epoch-affordance parent-epoch-id parent-dispatch-id)]))

(defn- dispatch-always-label
  "Render the `:always` defensive label.

  Per rf2-ejtpd, `:source :always` is stamped on the
  `:rf.machine.microstep/transition` trace — `:always` microsteps
  do not produce their own dispatch envelope. So the DISPATCH step
  renderer normally never sees `:source :always` on
  `:rf.event/dispatched`. This branch exists for completeness across
  the closed set; a future runtime emitting `:always` on a dispatch
  trace would render the bare kind label without enrichment."
  [_step]
  [:span {:data-testid "rf-xray-epoch-dispatch-source-label"
          :style dispatch-verb-style}
   [:span {:style dispatch-source-plain-style}
    "from :always"]])

(defn- dispatch-source-enriched-label
  "Dispatch the source-label rendering to the per-kind label fn based
  on `:source` + `:source-enrichment`. Falls back to the vanilla
  `dispatch-source-label` (call-site click-to-source) for all other
  source kinds (`:ui`, `:frame-init`, `:test-harness`, `:unknown`,
  plus any source value that lacks per-kind enrichment data).

  Closed-set dispatch table (rf2-ejtpd + rf2-5qp4g):

    :after-timer       → dispatch-after-timer-label
    :machine-spawn     → dispatch-machine-spawn-label
    :fx-dispatch       → dispatch-fx-label
    :fx-dispatch-later → dispatch-fx-label
    :always            → dispatch-always-label
    other / nil        → dispatch-source-label (call-site link)"
  [{:keys [source coord source-enrichment] :as step} epoch-history]
  (case source
    :after-timer       (if source-enrichment
                         (dispatch-after-timer-label source-enrichment)
                         (dispatch-source-label source coord))
    :machine-spawn     (if source-enrichment
                         (dispatch-machine-spawn-label source-enrichment)
                         (dispatch-source-label source coord))
    :fx-dispatch       (dispatch-fx-label source (or source-enrichment {})
                                          epoch-history)
    :fx-dispatch-later (dispatch-fx-label source (or source-enrichment {})
                                          epoch-history)
    :always            (dispatch-always-label step)
    (dispatch-source-label source coord)))

(defn render-dispatch-step
  "Render the DISPATCH step (always present). Header summarises `from
  <source>` with the call-site chip when a coord was captured;
  body renders the dispatched event vector as a boxed monospace
  block (rf2-93a7s · rf2-9jvx1).

  Per rf2-80u5a the `<source>` label itself is the goto-source
  affordance — clickable button when `:rf.trace/call-site` was
  captured by the macro form (`rf/dispatch [...] [opts]`); plain
  text otherwise. The external-link icon rides INSIDE the button
  as a secondary cue so the affordance reads as a single labelled
  link rather than a label-with-trailing-icon.

  Per rf2-5qp4g, when `:source` is one of the substrate-internal
  closed-set values (rf2-ejtpd: `:after-timer`, `:machine-spawn`,
  `:fx-dispatch`, `:fx-dispatch-later`), the label gains rich chrome
  for the kind (delay-ms + state-path / spawned-actor-id /
  parent-epoch navigation). Vanilla sources fall through to the
  pre-rf2-5qp4g call-site chrome unchanged.

  `epoch-history` is the optional Xray epoch buffer slice the
  `:fx-dispatch` / `:fx-dispatch-later` enrichments use to resolve
  the parent-dispatch-id → parent-epoch-id link (rendered as a
  click-to-navigate `:rf.xray/focus-epoch` button). When omitted
  (direct test calls of the renderer) the parent-epoch chip falls
  back to the unresolved variant."
  ([step] (render-dispatch-step step nil))
  ([{:keys [source coord duration-ms step-number violations] :as step}
    epoch-history]
   [:div {:data-testid "rf-xray-epoch-step-dispatch"
          :data-step-kw "dispatch"
          :data-source (when source (name source))}
    (numbered-circle step-number :DISPATCH)
    (step-header
      {:step :dispatch
       :badge :DISPATCH
       :verb (let [enriched (dispatch-source-enriched-label step epoch-history)]
               ;; Vanilla sources fall through to a wrapper that
               ;; carries the prefix "from " — the per-kind labels
               ;; carry their own "from <kind>" prefix already, so the
               ;; wrapper differs by source.
               (case source
                 (:after-timer :machine-spawn :fx-dispatch
                  :fx-dispatch-later :always)
                 enriched
                 [:span {:style dispatch-verb-style}
                  "from "
                  (dispatch-source-label source coord)]))
       :expandable? false
       :testid "rf-xray-epoch-dispatch"
       :duration-ms duration-ms}
      nil)
    (dispatch-body step)
    ;; rf2-xgeag — `:event` boundary violations attach to DISPATCH.
    (violation-blocks :dispatch violations)]))

;; ---- COEFFECT step -------------------------------------------------------

(defn- coeffect-row-view
  "Render one COEFFECT row (id link + labelled value via edn-inspector)
  per the bead body's §COEFFECT shape (rf2-cq0ch).

  The injected value renders via the canonical edn-inspector widget —
  scalars one-line through `edn/inspect-inline`; nested structures get
  the labelled cofx-id header so the row reads `:rf/now <inst>` /
  `:session {:user-id 42}` rather than the legacy cryptic
  `+[]<value>` diff-row.

  Pair-debug 2026-05-26: the cofx-id is a CLICKABLE button when the
  registered cofx carries `:file`/`:line` meta — clicking opens the
  editor at the `reg-cofx` source. The external-link icon rides
  INSIDE the button so the affordance reads as a single labelled
  link. When no coord is captured the label renders as a plain
  coloured span (no broken / dead-link affordance).

  Argument order matches `map-indexed`'s `(f idx item)` calling
  convention; the pre-rf2-cq0ch shape transposed these and silently
  destructured a number as the row map (`_row` was the index, `idx`
  the row map — hence the legacy `+[]nil` symptom)."
  [idx {:keys [id value] :as _row}]
  (let [cofx-meta  (when (keyword? id)
                     (try (rf/handler-meta :cofx id)
                          (catch :default _ nil)))
        coord      (when (and cofx-meta (string? (:file cofx-meta)))
                     {:file (:file cofx-meta) :line (:line cofx-meta)})
        clickable? (and (map? coord) (seq (:file coord)))
        label      (proj/ns-keyword id)]
    [:div {:key (str "cofx-" idx)
           :data-testid (str "rf-xray-epoch-coeffect-row-" idx)
           :style coeffect-row-style}
     ;; id — clickable button when coord captured; plain span otherwise
     (if clickable?
       [:button {:data-testid (str "rf-xray-epoch-coeffect-row-id-" idx)
                 :aria-label  (str "open " (:file coord)
                                   (when (:line coord) (str ":" (:line coord)))
                                   " in editor")
                 :title       (str "open " (:file coord)
                                   (when (:line coord) (str ":" (:line coord)))
                                   " in editor")
                 :on-click    (fn [e]
                                (.stopPropagation e)
                                (rf/dispatch [:rf.xray/open-in-editor
                                              {:source-coord coord}]
                                             {:frame :rf/xray}))
                 :style link-button-inherit-style}
        label
        (icons/external-link)]
       [:span {:data-testid (str "rf-xray-epoch-coeffect-row-id-" idx)
               :style coeffect-row-id-plain-style}
        label])
     ;; injected value (labelled — no cryptic `+[]nil` line)
     [:span {:data-testid (str "rf-xray-epoch-coeffect-row-value-" idx)
             :style coeffect-row-value-style}
      (edn/inspect-inline value)]]))

(defn render-coeffect-step
  "Render one COEFFECT step — one PER injected coeffect (pair-debug
  2026-05-26). Each coeffect installation gets its own numbered
  pipeline entry with the cofx-id + value rendered as the verb
  (cofx-id is a click-to-source button when the registered cofx
  carries `:file`/`:line` meta).

  The projection emits N coeffect step maps for a cascade
  injecting N user-defined cofx; system-injected cofx (e.g.
  framework-auto `:db`, `:event`) are filtered at projection time
  (rf2-cq0ch + the `system-cofx-ids` set)."
  [{:keys [id value step-number violations]}]
  (let [cofx-meta  (when (keyword? id)
                     (try (rf/handler-meta :cofx id)
                          (catch :default _ nil)))
        coord      (when (and cofx-meta (string? (:file cofx-meta)))
                     {:file (:file cofx-meta) :line (:line cofx-meta)})
        clickable? (and (map? coord) (seq (:file coord)))
        label      (proj/ns-keyword id)]
    [:div {:data-testid (str "rf-xray-epoch-step-coeffect-" (name id))
           :data-step-kw "coeffect"
           :data-cofx-id (name id)}
     (numbered-circle step-number :COEFFECT)
     (step-header
       {:step :coeffect
        :badge :COEFFECT
        ;; Verb = cofx-id (clickable when coord captured), nothing
        ;; else. The injected value renders in the BODY below the
        ;; badge per pair-debug 2026-05-26.
        :verb (if clickable?
                [:button {:data-testid (str "rf-xray-epoch-coeffect-id-" (name id))
                          :aria-label  (str "open " (:file coord)
                                            (when (:line coord)
                                              (str ":" (:line coord)))
                                            " in editor")
                          :title       (str "open " (:file coord)
                                            (when (:line coord)
                                              (str ":" (:line coord)))
                                            " in editor")
                          :on-click    (fn [e]
                                         (.stopPropagation e)
                                         (rf/dispatch
                                           [:rf.xray/open-in-editor
                                            {:source-coord coord}]
                                           {:frame :rf/xray}))
                          :style coeffect-verb-link-button-style}
                 label
                 (icons/external-link)]
                [:span {:data-testid (str "rf-xray-epoch-coeffect-id-" (name id))
                        :style coeffect-verb-plain-style}
                 label])
        :expandable? false
        :testid (str "rf-xray-epoch-coeffect-" (name id))}
       nil)
     ;; Body — `+ [:cofx-id] <value>` diff-style line. Per pair-debug
     ;; 2026-05-26 the body sits left-aligned with the badge (no
     ;; indent) so the diff-line reads at the same column as the
     ;; header's badge pill.
     [:div {:data-testid (str "rf-xray-epoch-coeffect-value-" (name id))
            :style coeffect-body-style}
      [:span {:style coeffect-body-plus-style} "+"]
      [:span {:style coeffect-body-path-style}
       (str "[" (proj/ns-keyword id) "]")]
      [:span {:style coeffect-body-value-style}
       [ei/mini value 80]]]
     ;; rf2-xgeag — `:cofx` boundary violations attach to the matching
     ;; COEFFECT step by cofx-id.
     (violation-blocks :coeffect violations)]))

;; ---- HANDLER step --------------------------------------------------------

(defn- db-diff-line
  "Render one db-diff entry (`~ [path] before → after` /
  `+ [path] value` / `- [path]`).

  Per rf2-8w8er the before / after values render through the
  edn-inspector `mini` widget so keywords, numbers, strings, and
  sentinels paint with their canonical syntax-token chrome rather
  than as plain `pr-str` text. The diff-glyph + path label stay
  plain — they are signalling cues, not CLJS values."
  [[path before after change-kind] idx]
  (let [glyph (case change-kind
                :added    "+"
                :removed  "-"
                :modified "~"
                (cond
                  (and (some? before) (some? after)) "~"
                  (some? after)                       "+"
                  :else                                "-"))
        glyph-colour (case glyph
                       "+" success-colour
                       "-" error-colour
                       warning-colour)]
    [:div {:key (str "diff-" idx)
           :data-testid (str "rf-xray-epoch-handler-diff-row-" idx)
           :style diff-row-style}
     [:span {:style (assoc diff-glyph-bold-style :color glyph-colour)} glyph]
     [:span {:style diff-path-style}
      (proj/path-display path)]
     (when (= "~" glyph)
       [:<>
        [:span {:style diff-before-style}
         [ei/mini before 40]]
        [:span {:style diff-arrow-style} "→"]
        [:span {:style diff-after-success-style}
         [ei/mini after 40]]])
     (when (= "+" glyph)
       [:span {:style diff-added-flex-style}
        [ei/mini after 80]])]))

;; ---- Machine cascade view (rf2-u69j7) -----------------------------------
;;
;; Pre-rf2-u69j7 the machine-handler-section rendered 7 categories
;; (TRANSITION / GUARDS / LIFECYCLE / AFTER-TIMERS / DATA REDUCTION /
;; SNAPSHOT DIFF / FX). The operator had to read top-to-bottom + cross-
;; reference categories to reconstruct what fired in what order.
;;
;; The redesign (rf2-u69j7) replaces the category-grouped layout with a
;; single TIME-ORDERED CASCADE. One row per substrate emit, ordered by
;; trace-event INSERTION ORDER — the substrate already emits guards →
;; exit actions → transition → entry actions → always → after-action
;; → timer-cancels in cascade order, so no re-sort is needed.
;;
;; Each row renders:
;;   - Step ordinal (1..N) in a compact left-rail.
;;   - Kind chip (`GUARD / ACTION / TRANSITION / TIMER`) — colour-coded.
;;   - Phase chip (action rows only — `:exit / :transition / :entry /
;;     :always / :after-action / :initial-entry / :destroy-exit`).
;;   - Verb (action-id / guard-id / transition labels / timer state),
;;     a click-to-source button via shared `coord-chip` style when
;;     the machine spec carries the per-element source-coord
;;     (rf2-8bp3 / rf2-80u5a / rf2-ehd8v).
;;   - Duration chip (right-aligned, with long-step warning when
;;     `:duration-ms > 16ms` per rf2-nqt3d).
;;   - Outcome chip — `✓ pass / ▲ fail / ✗ threw` for guards;
;;     `✓ ok / ✗ threw` for actions; `→ N microstep(s)` for the
;;     transition; `· cancelled (<reason>)` for timers.
;;   - Body: source code (ALWAYS VISIBLE per the bead body's
;;     "interleaved source code" requirement) + outcome detail
;;     (per-action fx attribution + data-write delta for actions;
;;     before→after snapshot for the transition).

(defn- cascade-row-coord
  "Lift a `{:file :line}` source-coord from the registered machine's
  `:rf.machine/source-coords` index (rf2-8bp3) for a cascade row. The
  index key is `[:actions <id>]` / `[:guards <id>]` per rf2-8bp3.
  Returns nil when no coord was captured (production builds, fixture
  fn-form machines)."
  [machine-meta row]
  (when-let [k (proj/cascade-row-source-key row)]
    (let [idx (or (get-in machine-meta [:rf/machine :rf.machine/source-coords])
                  (:rf.machine/source-coords machine-meta))
          c   (get idx k)]
      (when (and (map? c) (string? (:file c)) (seq (:file c)))
        {:file (:file c) :line (:line c)}))))

(defn- machine-spec-from-meta
  "Lift the machine spec map from a `handler-meta` lookup return. The
  registrar stores the spec under `:rf/machine` (the wrapper shape); the
  legacy `:machine-spec` / `:rf.machine/spec` keys are fallbacks for
  fixture-emitted shapes used in unit tests."
  [machine-meta]
  (or (:rf/machine machine-meta)
      (:machine-spec machine-meta)
      (:rf.machine/spec machine-meta)
      machine-meta))

(defn- cascade-row-source-form
  "Lift the source form for a cascade row from the registered machine
  spec (rf2-u69j7 baseline + rf2-wwc3j inline-fn extensions). The form
  resolves at `cascade-row-source-key`'s spec-path tuple:

  - Named guard/action rows: `[:guards <id>]` / `[:actions <id>]`.
    Prefer the captured PR-STR source string under
    `:rf.machine/handler-source` (rf2-ypu5i; macro stamps source strings
    at compile time). Falls back to the runtime value at the spec path
    (a compiled fn object) when no source-string was captured (production
    builds, fixture fn-form machines).
  - Inline-fn `:entry` / `:exit` / `:guard` / `:action` rows: the spec
    path returns the runtime value at that slot — a compiled fn object
    or, when the user wrote a keyword reference (`:entry :enter-a`),
    that keyword (the caller's render path will dispatch on shape).
  - Transition rows: the spec path returns the transition map literal
    (a renderable EDN map).
  - Timer rows: the spec path returns the entire state-node map (the
    `:after`-bearing node); too verbose to render verbatim, so the
    caller elides the body and renders only the click-to-source chip.

  Returns nil for rows whose source-key is nil (no spec-path could be
  derived — e.g. transition rows with no `:event-id`)."
  [machine-meta row]
  (when-let [k (proj/cascade-row-source-key row)]
    (let [spec        (machine-spec-from-meta machine-meta)
          ;; rf2-ypu5i — named-handler source-string preferred for
          ;; `[:actions <id>]` / `[:guards <id>]`. The macro stamps
          ;; pr-str strings under `:rf.machine/handler-source` so the
          ;; render is real source text, not a fn-object pr-str.
          named-src   (case (and (= 2 (count k)) (first k))
                        :actions (get-in spec [:rf.machine/handler-source :actions (second k)])
                        :guards  (get-in spec [:rf.machine/handler-source :guards  (second k)])
                        nil)]
      (or named-src (get-in spec k)))))

(defn- cascade-outcome-chip
  "Render the outcome chip for a cascade row (rf2-u69j7). Pulls glyph
  + label from the `panels.epoch.badge` table; colour from
  `cascade-outcome-token-key`."
  [outcome label-override]
  (when (or (some? outcome) label-override)
    (let [glyph        (badge/cascade-outcome-glyph outcome)
          token-key    (badge/cascade-outcome-token-key outcome)
          colour       (get tokens token-key (:text-tertiary tokens))
          label-string (or label-override
                           (when (keyword? outcome) (name outcome)))]
      [:span {:data-testid (str "rf-xray-epoch-machine-cascade-outcome-"
                                (when (keyword? outcome) (name outcome)))
              :style (assoc cascade-outcome-chip-base-style :color colour)}
       [:span {:aria-hidden true :style diff-glyph-bold-style} glyph]
       (when label-string label-string)])))

(defn- cascade-phase-chip
  "Render the phase pill for an `:action` cascade row (rf2-u69j7).
  Renders nothing for non-action rows. The pill is intentionally
  muted (`:text-tertiary` + 10px) — the action verb is the headline;
  the phase is refinement."
  [phase]
  (when (badge/cascade-phase? phase)
    [:span {:data-testid (str "rf-xray-epoch-machine-cascade-phase-"
                              (name phase))
            :style cascade-phase-style}
     (badge/cascade-phase-label phase)]))

(defn- cascade-kind-pill
  "Render the kind pill (`GUARD / ACTION / TRANSITION / TIMER`) on a
  cascade row's header (rf2-u69j7). Smaller than the top-level badge
  pill — the cascade is rendered INSIDE the HANDLER step's body and
  the per-row chip is a refinement on the HANDLER badge above it."
  [kind]
  [:span {:data-testid (str "rf-xray-epoch-machine-cascade-kind-"
                            (when (keyword? kind) (name kind)))
          :style (assoc cascade-kind-pill-base-style
                        :background (badge/cascade-kind-colour kind))}
   (badge/cascade-kind-label kind)])

(defn- cascade-row-ordinal
  "Render the row's 1..N step ordinal on the left rail (rf2-u69j7).
  Compact monospace chip — keeps the cascade scannable across many
  rows without clipping into the rest of the row's chrome."
  [step]
  [:span {:data-testid (str "rf-xray-epoch-machine-cascade-ordinal-" step)
          :aria-label  (str "cascade step " step)
          :style cascade-ordinal-style}
   (str step)])

(defn- cascade-row-verb-link
  "Render a cascade row's verb (action-id / guard-id / transition
  label / timer state) as a click-to-source button when the machine
  spec carries the per-element source-coord (rf2-8bp3); falls back to
  a plain coloured span otherwise.

  rf2-80u5a / rf2-ehd8v — the affordance reads the same `<label> + ↗`
  shape as the HANDLER step's verb so the operator's eye trains on
  ONE source-link grammar across the panel."
  [row coord verb-string]
  (let [clickable? (and (map? coord) (seq (:file coord)))]
    (if clickable?
      [:button {:data-testid (str "rf-xray-epoch-machine-cascade-verb-link-"
                                  (:step row))
                :aria-label  (str "open " (:file coord)
                                  (when (:line coord)
                                    (str ":" (:line coord)))
                                  " in editor")
                :title       (str "open " (:file coord)
                                  (when (:line coord)
                                    (str ":" (:line coord)))
                                  " in editor")
                :on-click    (fn [e]
                               (.stopPropagation e)
                               (rf/dispatch [:rf.xray/open-in-editor
                                             {:source-coord coord}]
                                            {:frame :rf/xray}))
                :style cascade-verb-link-button-style}
       verb-string
       (icons/external-link)]
      [:span {:data-testid (str "rf-xray-epoch-machine-cascade-verb-"
                                (:step row))
              :style cascade-verb-plain-style}
       verb-string])))

(defn- source-form->string
  "Coerce a cascade-row source-form value into a printable Clojure
  source string for `edn/code-block`. Per rf2-wwc3j the source-form may
  arrive in one of several shapes:

  - String — already a captured pr-str (rf2-ypu5i `:rf.machine/handler-
    source` value). Return as-is.
  - Keyword — the user wrote a named-ref slot (`:entry :enter-a`).
    Render the keyword form; downstream the named-id slot's own row
    carries the body proper.
  - Map (the transition map literal or a state-node) — pr-str renders
    the EDN.
  - Anything else (a compiled fn object) — fall back to pr-str. In
    production CLJS builds this surfaces a `#object[...]` token; the
    operator can still pivot to the click-to-source affordance.

  Returns nil for nil input so the caller can render the source-missing
  placeholder."
  [source-form]
  (cond
    (nil? source-form) nil
    (string? source-form) source-form
    :else (pr-str source-form)))

(defn- cascade-row-source-body
  "Render the source code body for a cascade row (rf2-u69j7 baseline +
  rf2-wwc3j inline-fn extensions). Always visible per the bead body's
  'interleaved source code' requirement — the operator reads what ran
  AND its code at the same vertical position without scrolling.

  - `:action` / `:guard` rows: render the captured source form (named-
    handler pr-str string OR inline-fn slot value) through the
    canonical `edn/code-block` widget.
  - `:transition` rows (rf2-wwc3j): render the transition map literal
    (renderable EDN). This is the bead's 'delight shape' for the
    transition cascade — the operator reads the `{:target :guard
    :action}` form inline.
  - `:timer` rows: no body (the spec value at the parent state path is
    a verbose state-node map; the click-to-source chip on the verb is
    the primary affordance).
  - When no source form is captured (production builds with
    `goog.DEBUG=false`, fixture machines that pre-date the source-
    coord stamping pass), render a muted placeholder so the operator
    sees the slot consistently.

  rf2-66wis / rf2-93jp0 — `edn/code-block` paints clojure-syntax
  tokens with the same per-token palette as the Figma authority's
  `.syntax-*` classes, so the cascade code body matches the HANDLER
  step's source body."
  [row source-form]
  (when (contains? #{:action :guard :transition} (:kind row))
    (let [src-str (source-form->string source-form)]
      [:div {:data-testid (str "rf-xray-epoch-machine-cascade-source-"
                               (:step row))
             :style cascade-row-source-style}
       (if (some? src-str)
         (edn/code-block
           {:source src-str
            :lang   :clojure
            :testid (str "rf-xray-epoch-machine-cascade-source-body-"
                         (:step row))})
         [:span {:data-testid (str "rf-xray-epoch-machine-cascade-source-missing-"
                                   (:step row))
                 :style cascade-row-source-missing-style}
          "<source not yet captured>"])])))

(defn- cascade-row-action-outcome-details
  "Render the per-action outcome details for an `:action` cascade row
  (rf2-u69j7). Three slots ride below the row's source body:

  - DATA Δ — when the action returned a `:data` write, surface the
    delta the action contributed (rf2-9c27r-style per-action
    attribution, now inline rather than buried in a category roll-up).
  - FX — when the action returned a `:fx` list, surface each emitted
    fx-id (per-action attribution; same data as the FX step's
    `:attributed-to` chip, now visible IN the action's row).
  - EXCEPTION — when the action threw, surface the exception message
    (the cascade halts on the first throw — Spec 005 §Errors).

  Each slot elides cleanly when the underlying data is absent so the
  row stays minimal for actions that ran without side-effects."
  [row]
  (let [{:keys [data-write fx threw? exception]} row]
    (when (or data-write (seq fx) threw?)
      [:div {:data-testid (str "rf-xray-epoch-machine-cascade-outcome-"
                               (:step row))
             :style cascade-outcome-details-style}
       ;; Per-action DATA Δ — the data the action wrote into the
       ;; snapshot. Surface as `↳ data Δ <map>` so the cascade
       ;; row tells the operator 'this action wrote …' without
       ;; reading the post-cascade snapshot diff.
       (when (some? data-write)
         [:div {:data-testid (str "rf-xray-epoch-machine-cascade-data-write-"
                                  (:step row))
                :style cascade-detail-row-style}
          [:span {:style cascade-detail-success-arrow-style} "↳"]
          [:span {:style cascade-detail-label-style} "data Δ"]
          [:span {:style cascade-detail-value-style}
           [ei/mini data-write 80]]])
       ;; Per-action FX attribution — each fx-id the action emitted
       ;; in its outcome's `:fx` slot. The view layer already
       ;; surfaces these via the FX step's `:attributed-to` chip;
       ;; this row carries the SAME data inline so the operator can
       ;; read 'action X emitted fx Y' in one place without crossing
       ;; the cascade.
       (when (seq fx)
         [:div {:data-testid (str "rf-xray-epoch-machine-cascade-fx-"
                                  (:step row))
                :style cascade-detail-fx-row-style}
          [:span {:style cascade-detail-accent-arrow-style} "↳"]
          [:span {:style cascade-detail-label-style} "fx"]
          (for [[j entry] (map-indexed vector fx)
                :let [[fx-id _args] (if (vector? entry) entry [entry nil])]]
            ^{:key (str "cascade-fx-" (:step row) "-" j)}
            [:span {:data-testid (str "rf-xray-epoch-machine-cascade-fx-"
                                      (:step row) "-" j)
                    :style cascade-detail-fx-chip-style}
             (proj/ns-keyword fx-id)])])
       ;; Threw path — the action halted the cascade. Surface a
       ;; compact error chip so the operator can pivot to the
       ;; exception body via the Issues panel.
       (when threw?
         [:div {:data-testid (str "rf-xray-epoch-machine-cascade-threw-"
                                  (:step row))
                :style cascade-detail-threw-row-style}
          [:span {:style cascade-threw-glyph-style} "✗"]
          [:span {:style cascade-threw-label-style} "threw"]
          (when exception
            [:span {:style cascade-threw-message-style}
             (str " — "
                  (or (when (some? exception)
                        (.-message ^js exception))
                      (str exception)))])])])))

(defn- cascade-row-transition-details
  "Render the transition's `from → to` chrome under a `:transition`
  cascade row (rf2-u69j7). Pulls the state vectors off the
  `:from-state` / `:to-state` slots the projection hoisted from the
  trace's `:before` / `:after` snapshots."
  [row]
  (let [{:keys [from-state to-state event microsteps]} row]
    (when (or from-state to-state event microsteps)
      [:div {:data-testid (str "rf-xray-epoch-machine-cascade-transition-"
                               (:step row))
             :style cascade-transition-details-style}
       (when (or from-state to-state)
         [:div {:data-testid (str "rf-xray-epoch-machine-cascade-from-to-"
                                  (:step row))
                :style cascade-from-to-row-style}
          [:span {:style cascade-detail-label-style} "state"]
          [:span {:style diff-before-style} [ei/mini from-state 30]]
          [:span {:style cascade-detail-label-style} "→"]
          [:span {:style diff-after-success-style} [ei/mini to-state 30]]])
       (when (vector? event)
         [:div {:data-testid (str "rf-xray-epoch-machine-cascade-trigger-"
                                  (:step row))
                :style cascade-trigger-row-style}
          [:span {:style cascade-detail-label-style} "event"]
          [ei/mini event 40]])])))

(defn- cascade-row-view
  "Render one cascade row (rf2-u69j7). Layout:

      [#step] [KIND] [phase?] verb (↗ source)    duration · outcome
      ─┐ source code (always visible, monospace, syntax-highlighted)
        ↳ data Δ  …
        ↳ fx …

  The row's `:kind` keys all the chrome variants: `:action` rides the
  full layout (phase chip + source body + outcome details);
  `:guard` rides a thinner layout (source body, no phase chip);
  `:transition` rides the state-change layout (no source body,
  state-vector before/after); `:timer` rides a minimal layout (no
  source body, no phase chip, just the kind + state + reason)."
  [machine-meta row]
  (let [{:keys [kind step phase duration-ms outcome threw?]} row
        coord       (cascade-row-coord machine-meta row)
        source-form (cascade-row-source-form machine-meta row)
        verb        (proj/cascade-row-label row)
        outcome-lbl (proj/cascade-outcome-label row)
        long?       (and (number? duration-ms)
                         (> duration-ms proj/long-step-threshold-ms))]
    [:div {:key (str "cascade-row-" step)
           :data-testid (str "rf-xray-epoch-machine-cascade-row-" step)
           :data-cascade-kind (when (keyword? kind) (name kind))
           :data-cascade-phase (when (keyword? phase) (name phase))
           :data-cascade-long-step (str (boolean long?))
           :style cascade-row-style}
     ;; Header row: ordinal + kind pill + phase chip + verb + duration + outcome
     [:div {:style cascade-row-header-style}
      (cascade-row-ordinal step)
      (cascade-kind-pill kind)
      (when (= :action kind)
        (cascade-phase-chip phase))
      (cascade-row-verb-link row coord verb)
      ;; Right-aligned: duration + outcome chip
      [:span {:style cascade-row-right-style}
       (duration-chip duration-ms)
       (cascade-outcome-chip
         (cond
           (= :guard kind)      outcome
           (and (= :action kind) threw?)  :threw
           (= :action kind)     :ok
           (= :timer kind)      :cancelled
           :else                nil)
         (when (or (= :transition kind) (and (= :guard kind) outcome-lbl))
           outcome-lbl))]]
     ;; Source code body (always visible per rf2-u69j7) — actions + guards only
     (cascade-row-source-body row source-form)
     ;; Per-row outcome details — kind-specific
     (case kind
       :action     (cascade-row-action-outcome-details row)
       :transition (cascade-row-transition-details row)
       nil)]))

(defn- machine-cascade-view
  "Render the time-ordered machine-handler cascade (rf2-u69j7). Replaces
  the pre-rf2-u69j7 category-grouped layout (TRANSITION / GUARDS /
  LIFECYCLE / AFTER-TIMERS / DATA REDUCTION / SNAPSHOT DIFF / FX) with
  a single time-ordered row stream.

  The cascade is REQUIRED non-empty for machine handlers — the
  substrate emits at least one `:rf.machine/transition` per macrostep
  (`make-machine-handler`'s emit shape). The empty-state branch is
  defensive only (fixtures that synthesise a machine handler without
  any cascade events).

  Header carries:
  - `N step(s)` count (compact summary at a glance).
  - Cascade total ms (sum of per-row `:duration-ms`); elides when no
    row carries a duration.

  Each row is rendered via `cascade-row-view` — see its docstring
  for the per-row layout grammar."
  [machine-meta cascade-rows]
  (let [n     (count cascade-rows)
        total (proj/machine-cascade-total-ms cascade-rows)]
    [:div {:data-testid "rf-xray-epoch-handler-machine-cascade"
           :data-cascade-row-count (str n)
           :style machine-cascade-root-style}
     (sub-header "cascade"
                 [:span {:style machine-cascade-summary-style}
                  (str n " step"
                       (when (not= 1 n) "s"))
                  (when (number? total)
                    [:span {:data-testid "rf-xray-epoch-machine-cascade-total"
                            :style machine-cascade-total-style}
                     (str "· " (proj/format-duration-ms total))])])
     (if (zero? n)
       [:div {:data-testid "rf-xray-epoch-handler-machine-cascade-empty"
              :style machine-cascade-empty-style}
        "— (no machine cascade events fired)"]
       (into [:div {:data-testid "rf-xray-epoch-handler-machine-cascade-rows"
                    :style machine-cascade-rows-style}]
             (for [row cascade-rows]
               (cascade-row-view machine-meta row))))]))

(defn- machine-block
  "Render the machine-handler section as a SINGLE TIME-ORDERED CASCADE
  (rf2-u69j7). Replaces the pre-rf2-u69j7 category-grouped layout
  (TRANSITION / GUARDS / LIFECYCLE / AFTER-TIMERS / DATA REDUCTION /
  SNAPSHOT DIFF / FX) with one cascade view: each row interleaves
  source code with the row's phase + duration + outcome (per Mike's
  authority — Bead rf2-u69j7).

  Order comes from the substrate's `:rf.machine/action-ran` /
  `:rf.machine/guard-evaluated` / `:rf.machine/transition` /
  `:rf.machine.timer/cancelled` trace events' INSERTION ORDER in the
  epoch buffer — the substrate already emits them in cascade order
  (Spec 005 §Trace events + rf2-82a0u). The projection's
  `machine-cascade-rows` is a pure-data walk over the events; this
  view layer is a faithful render of that vector.

  The legacy category-grouped sub-sections (TRANSITION / GUARDS /
  LIFECYCLE / AFTER-TIMERS / DATA REDUCTION / SNAPSHOT DIFF / FX) are
  REPLACED, not augmented (per Mike: 'pre-alpha; no back-compat
  shim'). The full state-change story is now told inline by the
  cascade — transitions render their `from → to` state vectors;
  actions render their data-write + fx attribution + source code
  body."
  [{:keys [cascade] :as _machine-row} event-id]
  (let [machine-meta (when (some? event-id)
                       (try (rf/handler-meta :machine event-id)
                            (catch :default _ nil)))]
    [:div {:data-testid "rf-xray-epoch-handler-machine"}
     (machine-cascade-view machine-meta (or cascade []))]))

;; ---- handler source --------------------------------------------------
;;
;; Per rf2-66wis the HANDLER body carries the registered handler's
;; source code as a syntax-highlighted block under the header — same
;; widget as the Event panel uses (rf2-n4ad0 routed to `edn/code-block`
;; with the same per-token palette as the Figma authority's
;; `.syntax-*` classes, rf2-93jp0). The substrate stamps source under
;; the `:rf.handler/source` meta key (Spec 009 / rf2-xgfuy) via a
;; DEBUG-gated macro; production goog.DEBUG=false builds carry no
;; source, so the slot renders a clear placeholder rather than
;; collapsing silently.
;;
;; For machine handlers the "source" is the machine spec — read via
;; `rf/handler-meta :machine event-id`. The spec renders through the
;; same `edn/inspect` widget every other top-level EDN map uses.

(defn- handler-source-string
  "Return the registered event-handler's source string from the
  `:rf.handler/source` meta key, or nil when the substrate hasn't
  captured one (production builds, registrations that pre-date the
  coord-annotation pass)."
  [meta]
  (let [s (:rf.handler/source meta)]
    (when (and (string? s) (seq s))
      s)))

(defn- machine-spec-value
  "Return the registered machine handler's spec data. Read off the
  `:machine-spec` slot (the substrate stashes the original
  `(reg-machine id spec ...)` argument here) so the panel can render
  it via the canonical edn-inspector."
  [meta]
  (or (:machine-spec meta)
      (:spec meta)
      (:rf.machine/spec meta)))

(defn- coord-from-handler-meta
  "Lift a `{:file :line}` source-coord off a registered handler's meta
  map (`rf/handler-meta` return shape). Returns nil when the meta
  carries no `:file` (production builds, registrations that pre-date
  the coord-annotation pass).

  rf2-ehd8v — shared by the HANDLER source-block (event + machine
  handlers) so the `file:line + [open]` affordance reads the same
  shape both render-paths use."
  [m]
  (when (and m (string? (:file m)) (seq (:file m)))
    {:file (:file m) :line (:line m)}))

(defn- source-coord-display
  "Render a structured source-coord `{:file :line}` as the display
  string `\"file:line\"` (or just `\"file\"`). nil when the coord
  lacks `:file`. Mirrors `event_detail/format-coord-display` so the
  HANDLER source row reads the same chrome the Event panel ships."
  [{:keys [file line]}]
  (when (and (string? file) (seq file))
    (cond-> file
      line (str ":" line))))

(defn- handler-verb-link
  "Render the HANDLER step's verb (e.g. `reg-event-fx`) as a
  clickable hyperlink + external-link glyph when the handler's
  registered meta carries `:file` / `:line` (clicks dispatch
  `:rf.xray/open-in-editor`). Falls back to a plain coloured span
  when no coord was captured (production builds, fn-form
  registrations).

  rf2-ehd8v / Mike pair-debug 2026-05-26 — the verb itself IS the
  goto-source affordance; the legacy SOURCE sub-header that
  carried the file:line + [open] chrome is gone (handler-source-
  block now leads with the code body directly)."
  [flavour event-id]
  (let [machine? (= :reg-machine flavour)
        meta     (when (some? event-id)
                   (try (rf/handler-meta
                          (if machine? :machine :event) event-id)
                        (catch :default _ nil)))
        coord    (coord-from-handler-meta meta)
        label    (proj/handler-flavour-label flavour)
        clickable? (and (map? coord) (seq (:file coord)))]
    (if clickable?
      [:button {:data-testid "rf-xray-epoch-handler-verb-link"
                :aria-label  (str "open " (:file coord)
                                  (when (:line coord)
                                    (str ":" (:line coord)))
                                  " in editor")
                :title       (str "open " (:file coord)
                                  (when (:line coord)
                                    (str ":" (:line coord)))
                                  " in editor")
                :on-click    (fn [e]
                               (.stopPropagation e)
                               (rf/dispatch
                                 [:rf.xray/open-in-editor
                                  {:source-coord coord}]
                                 {:frame :rf/xray}))
                :style handler-verb-link-button-style}
       label
       (icons/external-link)]
      [:span {:data-testid "rf-xray-epoch-handler-verb-plain"
              :style handler-verb-plain-style}
       label])))

(defn- handler-source-block
  "Render the source-code block under the HANDLER header. Three
  cases:

    1. Machine handler — render the machine spec via the canonical
       `edn/inspect` widget.
    2. Event handler with a captured source string — render via
       `edn/code-block` (clojure-syntax highlight).
    3. Otherwise — render a clear `<source not yet captured>`
       placeholder so the slot is always present (operator learns
       where to look + when the substrate didn't stamp).

  rf2-ehd8v / pair-debug 2026-05-26 — the SOURCE / MACHINE SPEC
  sub-header is gone; the verb in the HANDLER step header IS the
  click-to-source affordance now (see `handler-verb-link`). This
  fn renders only the code body."
  [flavour event-id]
  (let [machine? (= :reg-machine flavour)
        meta     (when (some? event-id)
                   (try (rf/handler-meta (if machine? :machine :event) event-id)
                        (catch :default _ nil)))
        spec     (when machine? (machine-spec-value meta))
        src      (when-not machine? (handler-source-string meta))]
    [:div {:data-testid "rf-xray-epoch-handler-source"
           :style handler-source-root-style}
     (cond
       (and machine? (some? spec))
       [:div {:data-testid "rf-xray-epoch-handler-source-spec"
              :style handler-source-spec-style}
        (edn/inspect spec)]

       src
       (edn/code-block
         {:source src
          :lang   :clojure
          :testid "rf-xray-epoch-handler-source-body"})

       :else
       [:span {:data-testid "rf-xray-epoch-handler-source-placeholder"
               :style handler-source-placeholder-style}
        "<source not yet captured>"])]))

(defn- db-diff-mode-toggle
  "Thin wrapper around the shared `views.diff-mode-toggle/diff-mode-toggle`
  widget (rf2-yqjrd extraction of the rf2-n2jig toggle). The button-bar
  chrome + per-mode labels + R1-R8 grammar default mode (`:full+diff`)
  all live in the shared widget; this wrapper supplies the surface-
  specific testid prefix + the `:rf/xray` frame-anchored dispatch
  (matches the rf2-p56sk / rf2-7sdja / rf2-kcaiz frame-leak fix
  pattern — toggle's home app-db is Xray regardless of host frame).

  Per the universalisation bead, every Xray surface that surfaces a
  diff-mode toggle does so through this same shared widget so the
  operator sees ONE button-bar shape across App-DB / Machine
  Inspector / SUBSCRIPTIONS / HANDLER `:db`."
  [mode]
  [diff-mode/diff-mode-toggle
   {:mode      mode
    ;; rf2-7vv8f — testid prefix normalised to `rf-xray-<surface>-diff-mode`.
    :testid    "rf-xray-epoch-handler-db-diff-mode"
    :on-change (fn [m]
                 (rf/with-frame :rf/xray
                   (rf/dispatch [:rf.xray.epoch/set-db-diff-mode m])))}])

(defn- handler-db-diff-block
  "Render the HANDLER step's `:db` sub-section (rf2-93436 / design
  doc §Section 1 + §Section 2). Always renders for non-machine
  handlers.

  rf2-n2jig — the sub-section carries a three-button toggle
  `[diff][full][full+diff]`:

  - `:diff` — flat path-changes this handler produced. When empty,
    reads `— (no changes)` per the design's §Empty edge-case
    rendering table (reg-event-db returning identical db / reg-
    event-fx returning nil — explicit empty state, not an omitted
    slot).
  - `:full` — full post-cascade `:db-after` via the edn-inspector
    widget, no diff chrome (renamed from `:all` for clarity).
    Operator sees the entire app-db value without diff annotations.
  - `:full+diff` — mode-3 default (per pair-debug 2026-05-27): the
    full data tree WITH inline diff annotations. Operator sees the
    shape AND the delta in one read. Implements the R1-R8 grammar
    rules per the findings doc `diff-mode-3-key-and-triangle-
    grammar-2026-05-27.md` §5.1 (revised per §7).

  Mode persists via `:rf.xray.epoch/db-diff-mode` so the operator's
  preference survives focus shifts.

  Distinct from the (retired) top-level APP-DB DIFF step (rf2-rrykz)
  — same source data, different lens. HANDLER's `:db` attributes the
  change to THIS handler's return value.

  Suppressed for machine handlers — per design §Section 3 §DB DIFF
  the snapshot IS the db change (at `[:rf/machines <id>]`) so the
  slot folds into SNAPSHOT DIFF rather than carrying a redundant
  standalone slot."
  [db-diff]
  (let [mode      @(rf/subscribe [:rf.xray.epoch/db-diff-mode])
        empty?    (not (seq db-diff))
        ;; "— (no changes)" fires ONLY in :diff mode when there are
        ;; no path-changes (the empty-state read). Non-empty :diff
        ;; mode renders the diff-lines themselves; the prior "N paths"
        ;; count chip was redundant with that and added noise.
        diff-summary (when (and (= :diff mode) empty?)
                       "— (no changes)")]
    [:div {:data-testid "rf-xray-epoch-handler-db-diff"
           :data-empty (str empty?)
           ;; rf2-xvu24 — canonical `data-rf-xray-diff-mode` axis (was
           ;; the drifted `data-db-diff-mode`).
           :data-rf-xray-diff-mode (name mode)}
     (sub-header ":db"
                 [:span {:style inline-flex-center-style}
                  (when diff-summary diff-summary)
                  (db-diff-mode-toggle mode)])
     (case mode
       :diff
       (when-not empty?
         ;; rf2-9ec65 — eager realisation via `mapv` so any future
         ;; subscribe deref inside `db-diff-line` registers in this
         ;; render's reactive scope (spec/006 §Lazy-seq deref tracking,
         ;; rf2-atqkg). Was `(for [[i row] (map-indexed vector …)] …)`
         ;; which returns a LazySeq.
         (into [:<>]
               (map-indexed (fn [i row] (db-diff-line row i)) db-diff)))

       :full
       (let [record  @(rf/subscribe [:rf.xray/selected-epoch-record])
             db-after (:db-after record)]
         (if (some? db-after)
           [:div {:data-testid "rf-xray-epoch-handler-db-full"
                  :style handler-db-all-style}
            [ei/edn-inspector db-after
             {:site-id [:rf.xray.epoch/handler-db-full (:epoch-id record)]
              :default-expanded-depth 2}]]
           [:span {:data-testid "rf-xray-epoch-handler-db-full-missing"
                   :style handler-db-all-missing-style}
            "— db-after not available in epoch record"]))

       :full+diff
       ;; Mode-3 (rf2-n2jig): render the full :db-after tree with
       ;; inline diff annotations driven off `:db-before`. The
       ;; edn-inspector picks up the Editscript-backed projection
       ;; via `:before`; `:full-with-diff?` opts the renderer into
       ;; R3 chip + R4 vertical-rail chrome (R1/R2/R5/R6/R7/R8 fire
       ;; off the projection alone). Default-expanded-depth 3 per
       ;; Mike's pair-debug Q4 answer (between browse's 1 and diff's
       ;; 2 — deep enough to surface most app-db top-level shards).
       (let [record    @(rf/subscribe [:rf.xray/selected-epoch-record])
             db-before (:db-before record)
             db-after  (:db-after record)]
         (cond
           (some? db-after)
           [:div {:data-testid "rf-xray-epoch-handler-db-full-with-diff"
                  :style handler-db-all-style}
            [ei/edn-inspector db-after
             {:site-id [:rf.xray.epoch/handler-db-full-with-diff (:epoch-id record)]
              :before db-before
              :full-with-diff? true
              :default-expanded-depth 3}]]
           :else
           [:span {:data-testid "rf-xray-epoch-handler-db-full-with-diff-missing"
                   :style handler-db-all-missing-style}
            "— db-after not available in epoch record"])))]))

(defn handler-body
  "Render the HANDLER step's body — source block + db-diff + fx + the
  machine block when the handler is a machine-event-handler.

  Per rf2-9jvx1 the flavour + event-id row is dropped from the body —
  the header already carries that descriptor. Per rf2-66wis the body
  now leads with the handler's source code (or machine spec) so the
  operator can answer 'why did this handler do X' without leaving the
  panel.

  Per rf2-93436 the `:db diff` sub-section is ALWAYS present for
  non-machine handlers (design doc §Section 1 + §Section 2) — empty
  diff renders `— (no changes)` rather than collapsing the slot. For
  machine handlers the standalone `:db diff` is suppressed (folded
  into SNAPSHOT DIFF per design §Section 3).

  Per rf2-p2zy0 (Mike pair-debug 2026-05-27) the legacy per-fx-row
  list (one `fx-entry-line` per entry) is REPLACED by two
  decomposed sections matching how a reg-event-fx author thinks
  about the return map:

    - `:fx` — the canonical `:fx` vector-of-vectors (when present)
      rendered fully expanded via the edn-inspector widget.
    - `other` — the return map MINUS `:db` and `:fx`, rendered
      fully expanded via the edn-inspector widget. Carries legacy
      top-level fx-ids (`:dispatch`, `:http/get`, `:navigate`, …)
      when used directly on the return map rather than under `:fx`.

  Either, both, or neither may render — sections are
  `seq`-conditioned. The `:db` part stays in its own dedicated
  block (the [diff][full][full+diff] toggle) above."
  [{:keys [flavour event-id db-diff fx-vec other-effects machine] :as _row}]
  (let [machine? (= :reg-machine flavour)]
    [:div {:data-testid "rf-xray-epoch-handler-body"}
     ;; Source / machine spec block — rf2-66wis
     (handler-source-block flavour event-id)
     ;; Machine cascade BEFORE db diff (the cascade IS the story for
     ;; machines — rf2-u69j7 redesign).
     (when machine
       (machine-block machine event-id))
     ;; :db diff — always present for non-machine handlers (rf2-93436);
     ;; folded into SNAPSHOT DIFF for machines
     (when-not machine?
       (handler-db-diff-block db-diff))
     ;; :fx — the canonical vector-of-vectors, FULL via edn-inspector
     (when (seq fx-vec)
       [:div {:data-testid "rf-xray-epoch-handler-fx"}
        (sub-header ":fx")
        [ei/edn-inspector fx-vec
         {:site-id                [:rf.xray.epoch/handler-fx event-id]
          :card?                  false
          :zoomable?              true
          :default-expanded-depth 16}]])
     ;; other — return map minus :db and :fx, FULL via edn-inspector
     (when (seq other-effects)
       [:div {:data-testid "rf-xray-epoch-handler-other"}
        (sub-header "other")
        [ei/edn-inspector other-effects
         {:site-id                [:rf.xray.epoch/handler-other event-id]
          :card?                  false
          :zoomable?              true
          :default-expanded-depth 16}]])]))

(defn render-handler-step
  "Render the HANDLER step (always present). Per Mike pair-debug
  2026-05-26: the verb (reg-event-db / reg-event-fx / reg-event-ctx
  / reg-machine flavour label) is the click-to-source hyperlink;
  the event-id is NOT repeated in the HANDLER line because the
  DISPATCH step's header already names it.

  Per rf2-8resu (supersedes rf2-xgeag's :app-db attachment): the
  HANDLER step describes what the handler RETURNED (its effects
  map). The :where :app-db violation + rollback story moves to the
  FX step's :db row (the implicit commit fx). HANDLER step's
  `:violations` slot still renders generically — currently empty
  for HANDLER in practice — but the call site stays in case future
  violation kinds attach here."
  [{:keys [flavour event-id duration-ms step-number violations]
    :as step}]
  [:div {:data-testid "rf-xray-epoch-step-handler"
         :data-step-kw "handler"
         :data-handler-flavour (when flavour (name flavour))}
   (numbered-circle step-number :HANDLER)
   (step-header
     {:step :handler
      :badge :HANDLER
      :verb (handler-verb-link flavour event-id)
      :expandable? false
      :testid "rf-xray-epoch-handler"
      :duration-ms duration-ms}
     nil)
   (handler-body step)
   (violation-blocks :handler violations)])

;; ---- FLOW step -----------------------------------------------------------

(defn render-flow-step
  "Render one FLOW step — one PER flow that fired (rf2-xnb1x — mirror
  of the COEFFECT per-cofx restructure from pair-debug 2026-05-26).
  Each flow recompute gets its own numbered pipeline entry with the
  flow-id rendered as the verb (clickable to source when the
  registered flow carries `:file`/`:line` meta from `reg-flow`).

  The projection emits N flow step maps for a cascade with N flow
  recomputes; the body row renders the diff (path · before → after)
  beneath the badge, left-aligned with no extra indent — same body
  layout as the COEFFECT step."
  [{:keys [flow-id path before after duration-ms step-number]}]
  (let [flow-meta  (when (keyword? flow-id)
                     (try (rf/handler-meta :flow flow-id)
                          (catch :default _ nil)))
        coord      (when (and flow-meta (string? (:file flow-meta)))
                     {:file (:file flow-meta) :line (:line flow-meta)})
        clickable? (and (map? coord) (seq (:file coord)))
        label      (proj/ns-keyword flow-id)]
    [:div {:data-testid (str "rf-xray-epoch-step-flow-" (name flow-id))
           :data-step-kw "flow"
           :data-flow-id (name flow-id)}
     (numbered-circle step-number :FLOW)
     (step-header
       {:step :flow
        :badge :FLOW
        ;; Verb = flow-id (clickable when coord captured). Same
        ;; affordance shape as the COEFFECT step's cofx-id hyperlink.
        :verb (if clickable?
                [:button {:data-testid (str "rf-xray-epoch-flow-id-" (name flow-id))
                          :aria-label  (str "open " (:file coord)
                                            (when (:line coord)
                                              (str ":" (:line coord)))
                                            " in editor")
                          :title       (str "open " (:file coord)
                                            (when (:line coord)
                                              (str ":" (:line coord)))
                                            " in editor")
                          :on-click    (fn [e]
                                         (.stopPropagation e)
                                         (rf/dispatch
                                           [:rf.xray/open-in-editor
                                            {:source-coord coord}]
                                           {:frame :rf/xray}))
                          :style coeffect-verb-link-button-style}
                 label
                 (icons/external-link)]
                [:span {:data-testid (str "rf-xray-epoch-flow-id-" (name flow-id))
                        :style coeffect-verb-plain-style}
                 label])
        :expandable? false
        :testid (str "rf-xray-epoch-flow-" (name flow-id))
        :duration-ms duration-ms}
       nil)
     ;; Body — `[path] before → after` diff line, left-aligned with
     ;; the badge (no extra indent). Mirrors the COEFFECT step's
     ;; body layout (pair-debug 2026-05-26).
     (when (sequential? path)
       [:div {:data-testid (str "rf-xray-epoch-flow-value-" (name flow-id))
              :style coeffect-body-style}
        [:span {:style coeffect-body-plus-style}
         (if (some? before) "~" "+")]
        [:span {:style coeffect-body-path-style}
         (proj/path-display path)]
        (when (some? before)
          [:span {:style diff-before-style} [ei/mini before 30]])
        (when (and (some? before) (some? after))
          [:span {:style coeffect-body-path-style} "→"])
        (when (some? after)
          [:span {:style coeffect-body-value-style} [ei/mini after 30]])])]))

;; ---- FX step -------------------------------------------------------------

(defn- fx-row-view
  "Render one fx-handler row inside the FX step — green check + fx-id
  + truncated args.

  Argument order matches `map-indexed`'s `(f idx item)` convention
  (rf2-cq0ch — companion swap with `coeffect-row-view`).

  Per rf2-uffov: when the row carries `:attributed-to`, a muted
  `← <action-id>` attribution chip rides alongside so the operator
  reads `fx X emitted by action Y` in one line."
  [idx {:keys [fx-id status args duration-ms attributed-to]}]
  (let [glyph    (case status
                   :ok          "✓"
                   :overridden  "↺"
                   :skipped     "·"
                   :error       "✗"
                   ;; rf2-8resu — :rollback applies to the synthesised
                   ;; :db row when the commit's schema check failed +
                   ;; the cascade was rolled back. Visually red ✗ same
                   ;; as :error; the row's :violations sub-block
                   ;; carries the "rolled back" detail.
                   :rollback    "✗"
                   "·")
        colour   (case status
                   :ok          success-colour
                   :overridden  accent-colour
                   :skipped     text-tertiary-colour
                   :error       error-colour
                   :rollback    error-colour
                   text-secondary-colour)]
    [:div {:key (str "fx-" idx)
           :data-testid (str "rf-xray-epoch-fx-row-" idx)
           :data-fx-status (when (keyword? status) (name status))
           :data-fx-attributed (str (some? attributed-to))
           :style fx-row-style}
     [:span {:style (assoc diff-glyph-bold-style :color colour)} glyph]
     [:span {:style fx-row-id-style}
      (proj/ns-keyword fx-id)]
     ;; rf2-8w8er — args render through `mini` so the fx-call surface
     ;; lights up with syntax-token colour rather than plain text.
     (when (some? args)
       [:span {:style fx-row-args-style}
        [ei/mini args 80]])
     (when (number? duration-ms)
       [:span {:style fx-row-duration-style}
        (proj/format-duration-ms duration-ms)])
     ;; rf2-uffov — per-action attribution chip (for machine cascades)
     (when-let [{:keys [action-id phase]} attributed-to]
       [:span {:data-testid (str "rf-xray-epoch-fx-row-attribution-" idx)
               :title (str "emitted by " (proj/ns-keyword action-id)
                           (when phase (str " (" (name phase) " action)")))
               :style fx-row-attribution-style}
        [:span {:aria-hidden true} "←"]
        (proj/ns-keyword action-id)
        (when phase
          [:span {:style fx-row-attribution-phase-style}
           (str "(" (name phase) ")")])])]))

(defn- fx-row-with-violations
  "Render one fx row + any violations attached to that row (rf2-xgeag).
  Per-row attachment matches when the projection's `attach-to-fx-row`
  resolved the violation's `:failing-id` against an `fx-id` in the
  FX step's `:rows`."
  [idx row]
  [:div {:key (str "fx-row-" idx)
         :data-testid (str "rf-xray-epoch-fx-row-wrapper-" idx)}
   (fx-row-view idx row)
   (violation-blocks (keyword (str "fx-row-" idx)) (:violations row))])

(defn render-fx-step
  "Render the FX step (only present when fx-handlers fired).

  Pair-debug 2026-05-26: the prior verb that read `N fired (M
  succeeded, K threw)` is dropped — the row glyphs (✓/✗) already
  convey per-fx outcome; the count summary is noise in the
  step header. Threw-count surfaces as a chip only if non-zero so
  the operator still sees errors at-a-glance.

  rf2-xgeag — `:fx-args` boundary violations attach per-row when
  `:failing-id` matches an fx-id; otherwise they attach to the
  step-level `:violations` slot (rendered at the foot of the
  step)."
  [{:keys [rows step-number threw violations]}]
  (let [k (or threw 0)]
    [:div {:data-testid "rf-xray-epoch-step-fx"
           :data-step-kw "fx"
           :data-fx-threw (str k)}
     (numbered-circle step-number :FX)
     (step-header
       {:step :fx
        :badge :FX
        :verb [:span {:style fx-verb-style}
               [:span {:data-testid "rf-xray-epoch-fx-caption"
                       :style fx-caption-style}
                "(side effects)"]
               (when (pos? k)
                 [:span {:style fx-threw-style}
                  (str k " threw")])]
        :expandable? false
        :testid "rf-xray-epoch-fx"}
       nil)
     [:div {:style margin-top-5-style}
      (map-indexed fx-row-with-violations rows)]
     (violation-blocks :fx violations)]))

;; ---- SUBSCRIPTIONS step --------------------------------------------------

(defn- subs-filter-button-bar
  "Three-button filter bar `[all][changed][unchanged]` for the
  SUBSCRIPTIONS step (rf2-tzmmf). Mirrors the HANDLER step's
  `[diff][all]` toggle shape — same chrome vocabulary.

  Active button paints in `:accent`; inactive buttons are
  transparent with muted text. Click dispatches
  `:rf.xray.epoch/set-subs-filter-mode` with the chosen keyword.

  SUPERSEDES the prior rf2-kfh1v `Show unchanged` boolean toggle
  AND the badge-adjacent `N recomputed (M changed, K unchanged)`
  text — Mike pair-debug 2026-05-26: the button-bar IS the new
  right-of-badge chrome. Pre-alpha masterpiece posture; no
  back-compat shim retained."
  [mode]
  [:span {:data-testid "rf-xray-epoch-subscriptions-filter-mode"
          :data-mode (when (keyword? mode) (name mode))
          :style subs-filter-bar-style}
   (for [m [:all :changed :unchanged]
         :let [active? (= mode m)]]
     ^{:key (name m)}
     [:button {:data-testid (str "rf-xray-epoch-subscriptions-filter-" (name m))
               :aria-pressed (str active?)
               :on-click (fn [e]
                           (.stopPropagation e)
                           ;; `with-frame :rf/xray` pins the dispatch
                           ;; target so the sub running in :rf/xray
                           ;; sees the write (matches the rf2-p56sk +
                           ;; HANDLER `[diff][all]` frame-anchor
                           ;; pattern — Xray app-db is the toggle's
                           ;; home regardless of host frame).
                           (rf/with-frame :rf/xray
                             (rf/dispatch
                               [:rf.xray.epoch/set-subs-filter-mode m])))
               :style (if active?
                        subs-filter-button-active-style
                        subs-filter-button-inactive-style)}
      (name m)])])

(defn- subs-value-cell
  "Render the `changed` cell for one sub recomputation row using the
  universal three-mode toggle (rf2-yqjrd).

  `mode` is the panel-wide `:rf.xray.epoch/subs-value-diff-mode` keyword:

  - `:diff`      — `✓ before → after` row (the prior shape; surfaces
                   only the value pair via `mini`).
  - `:full`      — `✓` + AFTER value via the edn-inspector widget
                   (no diff chrome). Used when the operator wants to
                   see the freshly-computed value alone.
  - `:full+diff` — `✓` + AFTER value via the edn-inspector with
                   BEFORE threaded as the `:before` pre-image so the
                   R1-R8 grammar paints inline `← changed from X`
                   annotations. Default per pair-debug 2026-05-27.

  Unchanged rows (`:changed? false`) render the muted `✗` tick
  regardless of mode (no value, nothing to diff)."
  [{:keys [sub-id changed? before after]} mode idx]
  (if changed?
    (case mode
      :full
      [:div {:style subs-changed-row-style}
       [:span {:style subs-changed-tick-style} "✓"]
       [:div {:style {:flex 1 :min-width 0}}
        [ei/edn-inspector after
         {:panel-id :rf.xray.epoch/subs-value
          :site-id  [:rf.xray.epoch/subs-value sub-id idx :full]
          :default-expanded-depth 2}]]]

      :full+diff
      [:div {:style subs-changed-row-style}
       [:span {:style subs-changed-tick-style} "✓"]
       [:div {:style {:flex 1 :min-width 0}}
        [ei/edn-inspector after
         (cond-> {:panel-id :rf.xray.epoch/subs-value
                  :site-id  [:rf.xray.epoch/subs-value sub-id idx :full+diff]
                  :default-expanded-depth 3
                  :full-with-diff? true}
           (some? before) (assoc :before before))]]]

      ;; :diff (default fallback) — preserve the prior shape.
      [:div {:style subs-changed-row-style}
       [:span {:style subs-changed-tick-style} "✓"]
       (when (some? before)
         [:span {:style diff-before-style} [ei/mini before 24]])
       (when (and (some? before) (some? after))
         [:span {:style cascade-detail-label-style} "→"])
       (when (some? after)
         [:span {:style diff-after-success-style} [ei/mini after 24]])])
    [:span {:style subs-unchanged-tick-style} "✗"]))

(defn- subscriptions-table
  "Render the SUBSCRIPTIONS table — 3 columns (sub / inputs / changed).
  Per the bead body's §SUBSCRIPTIONS (Step 7) shape (rf2-kfh1v).

  rf2-yqjrd — the `changed` cell now routes through `subs-value-cell`
  so the universal three-mode toggle drives value rendering. `mode`
  carries the resolved `:rf.xray.epoch/subs-value-diff-mode` keyword."
  [rows mode]
  [:div {:data-testid "rf-xray-epoch-subscriptions-table"
         ;; rf2-xvu24 — canonical `data-rf-xray-diff-mode` axis (was
         ;; the drifted `data-subs-value-diff-mode`).
         :data-rf-xray-diff-mode (when (keyword? mode) (name mode))
         :style subscriptions-table-style}
   ;; header
   [:div {:style table-header-row-style}
    [:div {:style subs-th-35-style} "sub"]
    [:div {:style subs-th-35-style} "inputs"]
    [:div {:style subs-th-30-style} "changed"]]
   ;; rows
   (for [[i {:keys [sub-id sub-vec inputs] :as row}] (map-indexed vector rows)]
     [:div {:key (str "sub-" i)
            :data-testid (str "rf-xray-epoch-sub-row-" i)
            :data-sub-changed (str (boolean (:changed? row)))
            :style (if (< i (dec (count rows)))
                     subs-row-style-with-border
                     subs-row-style)}
      [:div {:style subs-cell-id-style}
       ;; rf2-8w8er — sub-vec renders through `mini` so the vector's
       ;; keywords paint magenta, scalars orange, etc. Sub-id-only
       ;; fallback keeps the keyword-token chrome via `mini` too.
       [:span {:style subs-cell-id-span-style}
        (if (vector? sub-vec)
          [ei/mini sub-vec 40]
          [ei/mini sub-id 40])
        (icons/external-link)]]
      [:div {:style subs-cell-inputs-style}
       ;; rf2-8w8er — each input keyword routes through `mini` so
       ;; the input column lights up as keywords, not plain text.
       ;; "app-db" stays as a label (it's a source descriptor, not
       ;; a CLJS value).
       (cond
         (vector? inputs)
         (into [:div {:style subs-inputs-list-style}]
               (map (fn [i] [:div [ei/mini i 40]]) inputs))
         (some? inputs) [ei/mini inputs 40]
         :else          "app-db")]
      [:div {:style subs-cell-changed-style}
       (subs-value-cell row mode i)]])])

(defn- dispose-reason-label
  "Render a `:rf.sub/dispose` `:reason` keyword as a UI label
  (rf2-wpfjo). Closed set per rf2-mrnur — `:no-more-derefers /
  :hot-reload / :cache-clear`. Falls through `name` for unknown
  reasons so future extensions still paint text."
  [reason]
  (case reason
    :no-more-derefers "no-more-derefers"
    :hot-reload       "hot-reload"
    :cache-clear      "cache-clear"
    (when (keyword? reason) (name reason))))

(defn- disposed-subs-table
  "Render the DISPOSED sub-section (rf2-wpfjo) — one row per
  `:rf.sub/dispose` trace event. Visually distinct from the
  recompute rows: a red/error glyph conveys eviction; a muted
  reason chip carries the dispose path."
  [rows]
  [:div {:data-testid "rf-xray-epoch-subscriptions-disposed-table"
         :style disposed-subs-table-style}
   [:div {:style table-header-row-style}
    [:div {:style table-glyph-cell-header-style} ""]
    [:div {:style disposed-th-60-style} "disposed sub"]
    [:div {:style disposed-th-40-style} "reason"]]
   (for [[i {:keys [sub-id query reason]}] (map-indexed vector rows)]
     [:div {:key (str "disposed-" i)
            :data-testid (str "rf-xray-epoch-sub-disposed-row-" i)
            :data-sub-reason (when reason (pr-str reason))
            :style (if (< i (dec (count rows)))
                     subs-row-style-with-border
                     subs-row-style)}
      [:div {:style disposed-glyph-cell-style}
       ;; Eviction glyph — `✗` red/error tone conveys "removed from
       ;; the reactive graph" (rf2-wpfjo).
       "✗"]
      [:div {:style disposed-id-cell-style}
       [:span {:data-testid (str "rf-xray-epoch-sub-disposed-row-id-" i)
               :style disposed-id-span-style}
        (cond
          (vector? query) [ei/mini query 40]
          (some? sub-id)  [ei/mini sub-id 40]
          :else           [:span {:style disposed-anonymous-style}
                           "<anonymous sub>"])]]
      [:div {:data-testid (str "rf-xray-epoch-sub-disposed-row-reason-" i)
             :style disposed-reason-cell-style}
       (dispose-reason-label reason)]])])

(defn render-subscriptions-step
  "Render the SUBSCRIPTIONS step (present when subs recomputed OR
  when sub-cache entries were disposed — rf2-wpfjo).

  Per rf2-tzmmf the chrome to the right of the SUBSCRIPTIONS badge
  is a 3-button filter bar `[all][changed][unchanged]` — mirrors the
  HANDLER step's `[diff][all]` toggle shape. SUPERSEDES the prior
  rf2-kfh1v `Show unchanged` boolean toggle AND the badge-adjacent
  `N recomputed (M changed, K unchanged)` summary text — Mike
  pair-debug 2026-05-26: the button-bar IS the new right-of-badge
  chrome (no coexistence; pre-alpha masterpiece posture).

  Filter mode lives in `:rf.xray.epoch/subs-filter-mode` on the Xray
  app-db. Default is `:changed` — the rf2-kfh1v hide-unchanged-by-
  default rationale (most subs recompute but report no value change;
  unchanged rows crowd out signal) is preserved as the default mode.

  Frame-anchor pattern per rf2-p56sk: the 2-arity `subscribe` pins
  the read to `:rf/xray`, and the button-bar's click dispatches with
  `with-frame :rf/xray` envelope (matches the HANDLER `[diff][all]`
  toggle pattern). Both halves are anchored so toggle writes + reads
  hit the same app-db regardless of host frame-provider."
  [{:keys [rows disposed-rows step-number violations]}]
  (let [mode          @(rf/subscribe :rf/xray
                                     [:rf.xray.epoch/subs-filter-mode])
        value-mode    @(rf/subscribe :rf/xray
                                     [:rf.xray.epoch/subs-value-diff-mode])
        visible-rows  (case mode
                        :all       rows
                        :unchanged (filterv (complement :changed?) rows)
                        ;; :changed (default) — also the fallback for
                        ;; an unknown mode keyword so the panel never
                        ;; renders an empty filter.
                        (filterv :changed? rows))
        n             (count rows)
        l             (count disposed-rows)
        per-row-vio   (filter (fn [r] (seq (:violations r))) rows)]
    [:div {:data-testid "rf-xray-epoch-step-subscriptions"
           :data-step-kw "subscriptions"}
     (numbered-circle step-number :SUBSCRIPTIONS)
     (step-header
       {:step :subscriptions
        :badge :SUBSCRIPTIONS
        ;; Per rf2-tzmmf the badge-adjacent recompute summary text
        ;; is REMOVED — the button-bar is the new chrome. The
        ;; disposed-clause stays because there's no button-bar
        ;; affordance for that surface (and the count is small
        ;; enough that "L disposed" still reads at a glance).
        :verb [:span {:style subs-verb-style}
               (when (pos? n)
                 (subs-filter-button-bar mode))
               ;; rf2-yqjrd — universal three-mode toggle drives how each
               ;; `:changed?` row's value cell renders. Orthogonal to the
               ;; row-filter button bar above. Same shared widget every
               ;; other Xray diff surface uses (Epoch HANDLER `:db`,
               ;; App-DB, Machine Inspector snapshot).
               (when (pos? n)
                 [diff-mode/diff-mode-toggle
                  {:mode      value-mode
                   ;; rf2-7vv8f — testid prefix normalised to
                   ;; `rf-xray-<surface>-diff-mode`.
                   :testid    "rf-xray-epoch-subs-value-diff-mode"
                   ;; rf2-fytu4 — uniform "View" discoverability label.
                   :label     "View"
                   :on-change (fn [m]
                                (rf/with-frame :rf/xray
                                  (rf/dispatch
                                    [:rf.xray.epoch/set-subs-value-diff-mode m])))}])
               (when (pos? l)
                 [:span {:style subs-disposed-count-style}
                  (str l " disposed")])]
        :expandable? false
        :testid "rf-xray-epoch-subscriptions"}
       nil)
     (when (pos? n)
       (subscriptions-table visible-rows value-mode))
     (when (pos? l)
       (disposed-subs-table disposed-rows))
     ;; rf2-xgeag — `:sub-return` boundary violations. Per-row
     ;; attachments are rendered below their matching sub row;
     ;; step-level violations (indirect recomputes that don't
     ;; surface a row) ride at the foot.
     (for [row per-row-vio]
       (violation-blocks
         (keyword (str "sub-row-" (some-> row :sub-id name)))
         (:violations row)))
     (violation-blocks :subscriptions violations)]))

;; ---- VIEWS step ----------------------------------------------------------

(defn- view-coord
  "Pull the registered view's source coord off
  `(rf/handler-meta :view view-id)`. Returns nil when no meta is
  captured. Matches the reactive panel's resolver shape."
  [view-id]
  (when (some? view-id)
    (let [m (try (rf/handler-meta :view view-id) (catch :default _ nil))]
      (when (and m (string? (:file m)))
        {:file (:file m) :line (:line m) :ns (:ns m)}))))

(defn- views-table
  "Render the VIEWS table — 2 columns (views / subs). Per the bead
  body's §VIEWS (Step 8) shape (rf2-6djth).

  Each row carries:
    - view-id (hyperlinked via the registrar's `:view` meta coord)
    - duration (when stamped, rendered as a muted chip below the id)
    - the subs the view dereffed during this render (one per line —
      vectors via `pr-str`, scalars via `ns-keyword`)."
  [rows]
  [:div {:data-testid "rf-xray-epoch-views-table"
         :style views-table-style}
   [:div {:style table-header-row-style}
    [:div {:style views-th-50-style} "view"]
    [:div {:style views-th-50-style} "subs"]]
   (for [[i {:keys [view-id subs-read duration-ms]}] (map-indexed vector rows)]
     [:div {:key (str "view-" i)
            :data-testid (str "rf-xray-epoch-view-row-" i)
            :data-view-id (when view-id (pr-str view-id))
            ;; rf2-2f962 — pink-stripe view-name hover affordance. The
            ;; row-level mouse-enter/leave stamps the
            ;; `.rf-xray-view-highlight` class onto the live DOM node
            ;; tagged by Spec 006's `data-rf-view`, mirroring the
            ;; Reactive panel's view-node treatment (rf2-e33ad /
            ;; rf2-8l03l). Pure DOM side-effect; no layout perturbation.
            :on-mouse-enter (fn [_e] (apply-view-highlight! view-id))
            :on-mouse-leave (fn [_e] (clear-view-highlight! view-id))
            :style (if (< i (dec (count rows)))
                     subs-row-style-with-border
                     subs-row-style)}
      [:div {:style views-cell-view-style}
       [:span {:data-testid (str "rf-xray-epoch-view-row-id-" i)
               :style (if view-id
                        views-cell-id-clickable-style
                        views-cell-id-span-style)}
        (if (some? view-id)
          (proj/ns-keyword view-id)
          [:span {:style views-anonymous-style}
           "<anonymous view>"])
        (coord-chip/coord-chip (view-coord view-id)
                               (str "rf-xray-epoch-view-row-coord-" i))]
       (when (number? duration-ms)
         [:span {:style views-row-duration-style}
          (proj/format-duration-ms duration-ms)])]
      [:div {:data-testid (str "rf-xray-epoch-view-row-subs-" i)
             :style views-cell-subs-style}
       ;; rf2-8w8er — each sub-id (keyword or sub-vector) renders
       ;; through `mini` so the column reads as syntax-highlighted
       ;; tokens (keywords magenta, vectors with their bracket
       ;; chrome) rather than plain pr-str / `:foo` text.
       (cond
         (and (sequential? subs-read) (seq subs-read))
         (into [:div {:style views-subs-list-style}]
               (for [s subs-read]
                 [:div [ei/mini s 60]]))
         (some? subs-read)
         [ei/mini subs-read 60]
         :else
         [:span {:style italic-style} "(none)"])]])])

(defn- unmounted-views-table
  "Render the UNMOUNTED VIEWS sub-section (rf2-gmw1i) — one row per
  `:rf.view/unmounted` trace event. Visually distinct from the
  re-render rows: a red/error glyph conveys the teardown semantic.
  Click-to-source uses the same `(rf/handler-meta :view view-id)`
  resolver the re-render rows use, so the operator can jump to the
  view's definition even when the instance is gone."
  [rows]
  [:div {:data-testid "rf-xray-epoch-views-unmounted-table"
         :style unmounted-views-table-style}
   [:div {:style table-header-row-style}
    [:div {:style table-glyph-cell-header-style} ""]
    [:div {:style unmounted-th-auto-style} "unmounted view"]]
   (for [[i {:keys [view-id]}] (map-indexed vector rows)]
     [:div {:key (str "unmounted-" i)
            :data-testid (str "rf-xray-epoch-view-unmounted-row-" i)
            :data-view-id (when view-id (pr-str view-id))
            :style (if (< i (dec (count rows)))
                     subs-row-style-with-border
                     subs-row-style)}
      [:div {:style unmounted-glyph-cell-style}
       ;; Teardown glyph — `✗` red/error tone conveys the view came
       ;; off the reactive graph (rf2-gmw1i).
       "✗"]
      [:div {:style unmounted-id-cell-style}
       [:span {:data-testid (str "rf-xray-epoch-view-unmounted-row-id-" i)
               :style unmounted-id-span-style}
        (if (some? view-id)
          (proj/ns-keyword view-id)
          [:span {:style disposed-anonymous-style}
           "<anonymous view>"])
        (coord-chip/coord-chip (view-coord view-id)
                               (str "rf-xray-epoch-view-unmounted-row-coord-" i))]]])])

(defn render-views-step
  "Render the VIEWS step (present when views re-rendered OR when
  views unmounted during the cascade — rf2-gmw1i).

  Header counter reads `N re-rendered; M unmounted` when both
  surfaces are non-empty; collapses to `N re-rendered` or `M
  unmounted` when one half is absent. The unmounted sub-section
  is omitted entirely when no unmount-trace events fired."
  [{:keys [rows unmounted-rows step-number]}]
  (let [n (count rows)
        m (count unmounted-rows)
        verb (cond
               (and (pos? n) (pos? m))
               (str n " re-rendered; " m " unmounted")
               (pos? m)
               (str m " unmounted")
               :else
               (str n " view" (when (not= 1 n) "s") " re-rendered"))]
    [:div {:data-testid "rf-xray-epoch-step-views"
           :data-step-kw "views"}
     (numbered-circle step-number :VIEWS)
     (step-header
       {:step :views
        :badge :VIEWS
        :verb verb
        :expandable? false
        :testid "rf-xray-epoch-views"}
       nil)
     (when (pos? n)
       (views-table rows))
     (when (pos? m)
       (unmounted-views-table unmounted-rows))]))

;; ---- SCHEMA VIOLATION sub-block (rf2-xgeag) -----------------------------
;;
;; The violation sub-block rides INSIDE its owning pipeline step's
;; body. Each step renderer (DISPATCH / COEFFECT / HANDLER / FX /
;; SUBSCRIPTIONS) injects `(violation-blocks (:violations step))`
;; under its primary body; FX / SUBSCRIPTIONS additionally inject
;; per-row blocks via the row's own `:violations` slot.

(defn- schema-violation-where-label
  "Render a violation's `:where` slot as a UI label. Closed set per
  Spec 008 / 010; defaults to the keyword name."
  [where]
  (case where
    :app-db      "app-db commit"
    :cofx        "coeffect"
    :sub-return  "sub return"
    :fx-args     "fx args"
    :event       "event payload"
    :hot-reload  "schema hot-reload"
    (when (keyword? where) (name where))))

(defn- violation-recovery-label
  "Render the recovery posture chip on a violation's title bar.
  Per rf2-2ek7t — short chip text; the prose sentence below carries
  the full explanation ('commit to app-db', 'this sub returned
  nil', etc.) so the chip can be tight."
  [where rollback? recovery]
  (cond
    rollback?              "Aborted"
    (= where :fx-args)     "Skipped"
    (= where :sub-return)  "Returned nil"
    (= where :event)       "Rejected"
    (= where :cofx)        "Skipped"
    (= where :hot-reload)  "logged + skipped"
    (keyword? recovery)    (name recovery)
    :else                  nil))

(defn- violation-open-source-action
  "Click-to-source button. `coord` is `{:file :line}` or nil; when
  nil renders a muted plain-text fallback (graceful degrade — the
  framework may not yet stamp coords for some surfaces)."
  [{:keys [label coord testid]}]
  (if (and (map? coord) (string? (:file coord)) (seq (:file coord)))
    [:button {:data-testid testid
              :aria-label  (str "open " (:file coord)
                                (when (:line coord)
                                  (str ":" (:line coord)))
                                " in editor")
              :title       (str "open " (:file coord)
                                (when (:line coord)
                                  (str ":" (:line coord)))
                                " in editor")
              :on-click    (fn [e]
                             (.stopPropagation e)
                             (rf/dispatch [:rf.xray/open-in-editor
                                           {:source-coord coord}]
                                          {:frame :rf/xray}))
              :style schema-violation-action-link-style}
     (icons/external-link)
     label]
    [:span {:data-testid testid
            :style (assoc schema-violation-action-link-style
                          :color text-tertiary-colour
                          :cursor "default")}
     label]))

(defn- violation-kind-coord
  "Resolve a `(rf/handler-meta <kind> <id>)` coord, returning
  `{:file :line}` or nil. Catches CLJS errors so missing kinds /
  ids never bubble; rendering must degrade gracefully."
  [kind id]
  (when (keyword? id)
    (let [m (try (rf/handler-meta kind id)
                 (catch :default _ nil))]
      (when (and m (string? (:file m)))
        {:file (:file m) :line (:line m)}))))

(defn- where->handler-kind
  "Map a violation's `:where` slot to the registrar kind whose
  source-coord we want to open. nil for slots with no canonical kind."
  [where]
  (case where
    :event       :event
    :cofx        :cofx
    :app-db      :event   ;; the event handler that wrote the bad app-db
    :fx-args     :fx
    :sub-return  :sub
    nil))

(def ^:private violation-prose-style
  ;; sans-stack overrides the outer block's monospace inheritance —
  ;; the prose is natural-language ('This value failed a schema
  ;; check…'), not code/data. Monospace was reading as if the
  ;; framework was quoting a literal expression. Mono stays for the
  ;; humanized explain map below (which IS data).
  {:color       (:text-primary tokens)
   :font-family sans-stack
   :font-size   "12px"
   :line-height 1.5
   :margin      "4px 0"})

(def ^:private violation-inline-link-style
  {:color           (:accent tokens)
   :background      "transparent"
   :border          "none"
   :padding         0
   :font            "inherit"
   :cursor          "pointer"
   :text-decoration "underline"})

(defn- violation-inline-link
  "Render the inline `[schema check]` link inside a violation prose
  sentence (rf2-2ek7t). When `coord` resolves, the text is a clickable
  button that dispatches `:rf.xray/open-in-editor`; absent a coord,
  it degrades to plain inline text so the sentence stays readable."
  [{:keys [label coord testid]}]
  (if (and (map? coord) (string? (:file coord)) (seq (:file coord)))
    [:button {:data-testid testid
              :type        "button"
              :on-click    (fn [^js e]
                             (.stopPropagation e)
                             (rf/dispatch [:rf.xray/open-in-editor
                                           {:source-coord coord}]
                                          {:frame :rf/xray}))
              :style       violation-inline-link-style}
     label]
    [:span {:data-testid testid} label]))

(defn- violation-prose
  "Per-`:where` natural-language sentence with an inline `schema check`
  link (rf2-2ek7t). Each `:where` value has its own canned prose so
  the operator reads a one-sentence explanation of what happened +
  why, with the schema source-coord one click away."
  [where schema-coord testid-base]
  (let [link (violation-inline-link
               {:label  "schema check"
                :coord  schema-coord
                :testid (str testid-base "-schema-link")})]
    [:p {:data-testid (str testid-base "-prose")
         :style       violation-prose-style}
     (case where
       :app-db     [:<> "This value failed a " link
                    " and can't be committed to app-db."]
       :fx-args    [:<> "fx aborted because args failed the " link "."]
       :sub-return [:<> "This sub returned nil because its value failed the "
                    link "."]
       :event      [:<> "This event was rejected because its payload failed the "
                    link "."]
       :cofx       [:<> "This handler was skipped because the coeffect failed the "
                    link "."]
       :hot-reload [:<> "A schema re-registration invalidated existing app-db state. "
                    "See " link " for the new shape."]
       [:<> "Schema violation. " link " for details."])]))

(defn violation-block
  "Render one schema-violation sub-block (rf2-2ek7t redesign,
  supersedes rf2-xgeag).

  Three pieces of content:

    1. Title bar: ⚠ + 'Schema Violation Error' + right-aligned
       recovery chip (per-`:where` text: 'Aborted' / 'Skipped' /
       'Returned nil' / 'Rejected').
    2. Prose sentence: per-`:where` canned natural-language
       explanation, with an inline `schema check` link to the
       schema's source registration.
    3. Humanized explain map: rendered inline via `ei/mini`. Reads
       `:explain-humanized` from the row (Malli adapter populates
       via `malli.error/humanize` per rf2-2ek7t framework piece);
       falls back to raw `:explain` when humanized isn't there
       (non-Malli validators, or framework predating rf2-2ek7t).

  Previously-discrete fields (headline `where · failing-id`, path,
  value, separate handler + schema 'open' buttons) all retired —
  subsumed by the prose + humanized explain."
  [step-key idx {:keys [where failing-id path rollback? recovery
                        explain explain-humanized kind sensitive?]
                 :as   _row}]
  (let [recovery-label  (violation-recovery-label where rollback? recovery)
        ;; The schema source-coord resolution varies by :where. For
        ;; `:app-db`, the schema is registered at a PATH (not
        ;; keyword-id), so we read through `:schemas/app-schema-meta-at`
        ;; — the same hook the framework's schema-introspection surface
        ;; uses (rf2-mg6ya). For other `:where` values, the schema
        ;; rides on the registration's `:schema` metadata, reachable
        ;; via `handler-meta`. Both paths catch + return nil so missing
        ;; coords degrade the inline link to plain text.
        schema-coord    (or (when (and (= :app-db where) (sequential? path))
                              (try (let [m (rf/app-schema-meta-at path)]
                                     (when (and m (string? (:file m)))
                                       {:file (:file m) :line (:line m)}))
                                   (catch :default _ nil)))
                            (when failing-id
                              (violation-kind-coord :schema failing-id)))
        humanized-shown (or explain-humanized explain)
        testid-base     (str "rf-xray-epoch-violation-"
                             (name (or step-key :unknown)) "-" idx)]
    [:div {:key (str "violation-" step-key "-" idx)
           :data-testid testid-base
           :data-violation-where (when where (name where))
           :data-violation-kind (when kind (name kind))
           :data-rollback (str (boolean rollback?))
           :style schema-violation-block-style}
     ;; 1. Title bar
     [:div {:style schema-violation-title-style}
      [:span {:aria-hidden true} "⚠"]
      [:span {:data-testid (str testid-base "-title")}
       "Schema Violation Error"]
      [:span {:style schema-violation-title-spacer-style}]
      (when recovery-label
        [:span {:data-testid (str testid-base "-recovery")
                :style (if rollback?
                         schema-violation-rollback-chip-style
                         schema-violation-recovery-chip-style)}
         recovery-label])]
     ;; 2. Prose sentence with inline schema link
     (violation-prose where schema-coord testid-base)
     ;; 3. Humanized explain map (or raw fallback) — render via
     ;; edn-inspector fully expanded ("FULL" per Mike pair-debug
     ;; 2026-05-27). `:default-expanded-depth 16` matches the
     ;; widget's `:max-depth` ceiling so every nested level of the
     ;; explain tree is visible on first paint. The operator needs
     ;; to SEE the failure detail, not click to discover it.
     ;; (`mini`'s one-line truncated rendering collapsed to
     ;; `{:errors […1 items]}`, hiding the actual content.)
     (when (some? humanized-shown)
       [:div {:data-testid (str testid-base "-explain")
              :style schema-violation-explain-body-style}
        [ei/edn-inspector humanized-shown
         {:site-id [:rf.xray.epoch/violation-explain step-key idx]
          :default-expanded-depth 16}]])
     ;; Sensitive marker — keep as compact tail when applicable so
     ;; operators reading the humanized output know the value was
     ;; redacted at the substrate emit site (not a humanizer artifact).
     (when sensitive?
       [:div {:style schema-violation-sensitive-style}
        "(value redacted — slot declared :sensitive?)"])]))

(defn violation-blocks
  "Render every violation in `violations` as a sub-block inside the
  current step's body. `step-key` is the owning step keyword (used
  for stable test ids). nil-safe."
  [step-key violations]
  (when (seq violations)
    [:div {:data-testid (str "rf-xray-epoch-violations-" (name step-key))}
     (map-indexed (fn [i v] (violation-block step-key i v))
                  violations)]))

(defn- rolled-back-banner
  "One-line banner shown immediately under the HANDLER step body
  when the cascade is rolled-back, signposting the downstream-mute
  treatment to the operator."
  []
  [:div {:data-testid "rf-xray-epoch-rolled-back-banner"
         :style rolled-back-banner-style}
   [:span {:aria-hidden true} "↓"]
   "cascade rolled back — downstream effects skipped"])

;; SCHEMA HOT-RELOAD step retired per rf2-7gf7v (Mike pair-debug
;; 2026-05-27). The standalone tail step was rendering opaque
;; content (`schema hot-reload · :rf/default · path [:user/profile
;; :age] · value -3`) lacking the rich context the operator
;; needed. Hot-reload drift moves to the Issues panel where
;; explanatory chrome (pre/post schema, re-registration file:line,
;; live value, etc.) has room to breathe. The
;; `render-schema-hot-reload-step` fn + the `:schema-hot-reload`
;; case in `render-step` are removed; the projection no longer
;; emits the step (rf2-7gf7v / projection.cljc).

;; ---- APP-DB DIFF step — REMOVED pair-debug 2026-05-26 -------------------
;;
;; Replaced by the HANDLER step's `:db` `[diff][all]` toggle which
;; surfaces the same data in-context. `render-app-db-diff-step` +
;; `app-db-diff-row-view` deleted along with the step.


;; ---- CHILD DISPATCHES step (rf2-yx1ae) ----------------------------------

(defn- child-dispatch-via-label
  "Render the `:via` slot (the fx-id that emitted the row) as a UI
  chip label (rf2-yx1ae)."
  [via]
  (case via
    :dispatch        "dispatch"
    :dispatch-n      "dispatch-n"
    :dispatch-later  "dispatch-later"
    (when (keyword? via) (name via))))

(defn- child-dispatch-row-view
  "Render one child-dispatch row. Carries:

  - child event vector (the operator's primary read)
  - `:via` chip (which fx-id emitted the row)
  - `:delay-ms` chip (for `:dispatch-later`)
  - jump-to button when the child epoch is in the buffer; otherwise
    a muted `not in buffer` marker

  rf2-yx1ae. The jump-to dispatches `:rf.xray/select-epoch` against
  the resolved child `:epoch-id`."
  [{:keys [dispatch-id epoch-history]} idx {:keys [event delay-ms via]}]
  (let [child-epoch-id (proj/find-child-epoch epoch-history dispatch-id event)]
    [:div {:key (str "child-dispatch-" idx)
           :data-testid (str "rf-xray-epoch-child-dispatch-row-" idx)
           :data-child-resolved (str (some? child-epoch-id))
           :style child-dispatch-row-style}
     ;; via fx-id chip (muted)
     [:span {:data-testid (str "rf-xray-epoch-child-dispatch-via-" idx)
             :style child-dispatch-via-style}
      (child-dispatch-via-label via)]
     ;; event vector (primary) — rf2-8w8er routes through `mini` so
     ;; the dispatched event vector carries syntax-token chrome.
     [:span {:data-testid (str "rf-xray-epoch-child-dispatch-event-" idx)
             :style child-dispatch-event-style}
      [ei/mini event 80]]
     ;; delay chip (for :dispatch-later)
     (when (number? delay-ms)
       [:span {:data-testid (str "rf-xray-epoch-child-dispatch-delay-" idx)
               :style child-dispatch-delay-style}
        (str "+" delay-ms "ms")])
     ;; jump-to or "not in buffer"
     (if child-epoch-id
       [:button {:data-testid (str "rf-xray-epoch-child-dispatch-jump-" idx)
                 :data-child-epoch-id (str child-epoch-id)
                 :aria-label "jump to child cascade"
                 :on-click (fn [e]
                             (.stopPropagation e)
                             (rf/dispatch [:rf.xray/select-epoch child-epoch-id]
                                          {:frame :rf/xray}))
                 :style child-dispatch-jump-style}
        (icons/arrow-right)
        "jump"]
       [:span {:data-testid (str "rf-xray-epoch-child-dispatch-missing-" idx)
               :title "the child cascade has aged out of the epoch buffer (or has not yet completed)"
               :style child-dispatch-missing-style}
        "not in buffer"])]))

(defn render-child-dispatches-step
  "Render the CHILD-DISPATCHES step (rf2-yx1ae — only present when
  the handler returned dispatch-family fx).

  Header: `N events dispatched` (per the bead's acceptance §4).
  Per-row: event vector + via-fx chip + delay chip + jump-to
  affordance (resolves child epoch via `proj/find-child-epoch`).

  `ctx` carries this cascade's `:dispatch-id` + the
  `:epoch-history` slice; the row-view uses both to find the
  child's `:epoch-id`."
  [{:keys [rows step-number]} ctx]
  [:div {:data-testid "rf-xray-epoch-step-child-dispatches"
         :data-step-kw "child-dispatches"}
   (numbered-circle step-number :CHILD-DISPATCHES)
   (step-header
     {:step :child-dispatches
      :badge :CHILD-DISPATCHES
      :verb (str (count rows) " event"
                 (when (not= 1 (count rows)) "s")
                 " dispatched")
      :expandable? false
      :testid "rf-xray-epoch-child-dispatches"}
     nil)
   [:div {:style margin-top-5-style}
    (map-indexed (fn [idx row]
                   (child-dispatch-row-view ctx idx row))
                 rows)]])

;; ---- step dispatcher -----------------------------------------------------

(declare render-child-dispatches-step)

(defn- render-step
  "Dispatch a step row to its renderer. Returns hiccup; nil for
  unknown step kinds (defensive — every step the projection produces
  is in the canonical inventory; rf2-17vxj added SCHEMA-VIOLATIONS
  later retired by rf2-xgeag in favour of inline attachment + a
  hot-reload-only tail step; rf2-yx1ae added CHILD-DISPATCHES;
  rf2-rrykz added APP-DB-DIFF).

  `ctx` carries the cascade-level pieces a row may need (e.g. the
  parent `:dispatch-id` + the `:epoch-history` slice for the
  CHILD-DISPATCHES section's child-epoch resolution + rf2-5qp4g
  DISPATCH `:fx-dispatch` parent-epoch link resolution). Most steps
  ignore it."
  [step ctx]
  (case (:step step)
    :dispatch          (render-dispatch-step step (:epoch-history ctx))
    :coeffect          (render-coeffect-step step)
    :handler           (render-handler-step step)
    :flow              (render-flow-step step)
    :fx                (render-fx-step step)
    :subscriptions     (render-subscriptions-step step)
    :views             (render-views-step step)
    ;; :schema-hot-reload case retired per rf2-7gf7v — the
    ;; projection no longer emits the step.
    :child-dispatches  (render-child-dispatches-step step ctx)
    ;; :app-db-diff removed pair-debug 2026-05-26 — see comment above.
    nil))

;; ---- pipeline view -------------------------------------------------------

(defn pipeline-view
  "Render the numbered pipeline cascade for `steps` (already
  numbered via `project-numbered`). Each step renders as a
  position-relative wrapper so the per-step numbered circle anchors
  off the wrapper's left edge — the rail is a single absolutely-
  positioned line spanning the whole cascade.

  Per the bead body's §Layout:

      Container: padding 21px, overflow auto, full height
      Pipeline: left margin 55px to accommodate numbered circles
      Vertical line: absolute positioned, 0.5px width, starts at
                     13px from top, positioned at -34px from left edge
      Row spacing: 13px vertical gap between entries

  `ctx` is an optional map carrying cascade-level pieces individual
  step renderers may need (`:dispatch-id`, `:epoch-history` — used
  by the CHILD-DISPATCHES section to resolve child epochs via
  `proj/find-child-epoch`). Defaulted to `{}` for back-compat with
  direct test callers."
  ([steps]
   (pipeline-view steps {}))
  ([steps ctx]
   [:div {:data-testid "rf-xray-epoch-pipeline-container"}
    [:div {:data-testid "rf-xray-epoch-pipeline"
           :style pipeline-host-style}
     ;; The vertical rail — absolute-positioned line behind the
     ;; numbered circles. Top = numbered-circle radius (so the line
     ;; starts at the centre of step-1's circle); bottom = the foot
     ;; of the last step's circle.
     [:div {:data-testid "rf-xray-epoch-rail"
            :aria-hidden true
            :style pipeline-rail-style}]
     ;; Steps — `doall` forces the lazy `for` to realise INSIDE the
     ;; reg-view's render scope (rf2-atqkg). `render-step` returns
     ;; hiccup whose descendants (e.g. `handler-db-diff-block`,
     ;; `render-subscriptions-step`) deref subs directly via
     ;; `@(rf/subscribe …)`. Reagent only tracks derefs that fire
     ;; while the parent reg-view's reactive context is live; a
     ;; lazy seq realised AFTER the render pass leaves those derefs
     ;; outside the scope, so sub-value changes don't trigger a
     ;; re-render (symptom: the operator clicks `[diff][all]`, the
     ;; sub flips in app-db, the cascade reads the new value, but
     ;; the panel hiccup stays stale). `doall` plus the `(for …)`
     ;; preserves the original code shape; the realised seq lets
     ;; Reagent see every nested deref at render time. See spec/006
     ;; §Lazy-seq deref tracking.
     (doall
       (for [[i step] (map-indexed vector steps)]
         ^{:key (str "step-" (:step step) "-" i)}
         [:div {:data-testid (str "rf-xray-epoch-pipeline-step-" (:step-number step))
                :data-step (when (:step step) (name (:step step)))
                :data-rolled-back (str (boolean (:rolled-back? step)))
                ;; rf2-xgeag — when an `:app-db` violation rolled back
                ;; the cascade, every step downstream of HANDLER paints
                ;; with mute chrome so the operator sees the blast
                ;; radius at-a-glance.
                :style (if (:rolled-back? step)
                         (merge pipeline-step-style rolled-back-mute-style)
                         pipeline-step-style)}
          (render-step step ctx)]))]]))

;; ---- empty states --------------------------------------------------------

(defn- empty-state-view
  "Render the empty-state copy for a given focus status. Per the
  shared focus-resolver contract — three statuses, three messages."
  [status]
  (let [msg (case status
              :no-focus      "No event focused. Click an event in the list to inspect its pipeline."
              :epoch-evicted "The selected epoch was evicted from the history buffer. Pick a more recent event."
              :no-events     "The focused epoch has no recorded trace events."
              "No data available.")]
    [:div {:data-testid (str "rf-xray-epoch-empty-" (name status))
           :style empty-state-style}
     msg]))

;; ---- public Panel --------------------------------------------------------

(rf/reg-view Panel
  "Epoch panel root view. Subscribes to `:rf.xray/epoch-pipeline` —
  a composite that resolves the focused epoch off the spine and
  projects its `:trace-events` into the pipeline-step rows. Renders
  the numbered cascade when steps are present; an empty-state when
  the focus carries no record or the record carries no trace events."
  []
  (let [{:keys [status steps dispatch-id epoch-history]}
        @(rf/subscribe [:rf.xray/epoch-pipeline])]
    [:section {:data-testid "rf-xray-epoch-panel"
               :style panel-root-style}
     [:div {:style panel-scroll-style}
      (cond
        (= :focused status)
        (if (seq steps)
          (pipeline-view steps
                         {:dispatch-id   dispatch-id
                          :epoch-history epoch-history})
          (empty-state-view :no-events))

        :else
        (empty-state-view (or status :no-focus)))]]))
