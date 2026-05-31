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
            [day8.re-frame2-xray.panels.shared.coord-link :as coord-link]
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [day8.re-frame2-xray.views.edn-widget.widget :as edn]
            [day8.re-frame2-xray.views.resizable-table :as rt]
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
;;
;; rf2-vv3m6 (2026-05-29) — `diff-row-style`, `diff-path-style`,
;; `diff-arrow-style`, `diff-added-flex-style` retired with the
;; HANDLER `:db` `[diff]` mode branch (db-diff-line). The before /
;; after / glyph defs survive because the machine-cascade row + the
;; coeffect body still consume them.

(def ^:private diff-before-style
  {:color error-colour})

(def ^:private diff-after-success-style
  {:color success-colour})

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

;; -- db-diff rendering ----------------------------------------------------
;;
;; rf2-vv3m6 (2026-05-29) — the `[diff][full][full+diff]` mode-toggle bar
;; styles retired alongside the toggle. FULL+DIFF is the single rendering;
;; the HANDLER `:db` sub-section paints unconditionally via
;; `handler-db-diff-block`.

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

(def ^:private db-destination-style
  "The `:db` ledger row's args slot — the clickable '→ app-db'
  DESTINATION marker (rf2-j630b). A bare-button affordance styled as an
  inline link in the accent hue (the db-mutation lens colour) — clicking
  it jumps to the App-db panel for this epoch (the actual db diff lives
  there; no duplication in the ledger)."
  {:appearance       "none"
   :background       "none"
   :border           "none"
   :padding          0
   :margin           0
   :font             "inherit"
   :color            accent-colour
   :cursor           "pointer"
   :text-decoration  "none"
   :white-space      "nowrap"})

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

;; The flat SIDE EFFECTS ledger (rf2-j630b) carries NO header verb /
;; caption — the per-row glyphs (`fx-row-status-glyph`) are the whole
;; signal (the per-stage badge glyph retired in rf2-9wq0v). The
;; pre-rf2-j630b `(post-commit)` caption + `N threw` chip styles
;; (`fx-verb-style` / `fx-caption-style` / `fx-threw-style`) retired with
;; the 3-tier presentation.

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

;; rf2-1cc03 — `caused by <event-id>` chrome.
;;
;; Renders in the `sub` cell directly below the sub-id when the row
;; carries `:cause-event-id`. The event-id (head keyword of the
;; dispatching cascade's trigger event vector) names WHICH event
;; invalidated this sub's reactive input — surfacing the rf2-okz1u
;; attribution at the operator's first glance of the SUBSCRIPTIONS
;; table.
;;
;; Subdued italic chip (parity with the leaf-was `← was X` annotation
;; chip's `text-tertiary` + `font-style: italic` shape) so the chrome
;; reads as a secondary annotation, NOT a primary identity line.
;; Keyword routes through `ei/mini` for syntax-token chrome (keyword
;; magenta) — matches the sibling sub-id rendering above.
(def ^:private subs-cell-cause-event-style
  {:display     "inline-flex"
   :align-items "baseline"
   :gap         "4px"
   :margin-top  "2px"
   :color       text-tertiary-colour
   :font-family sans-stack
   :font-size   "11px"
   :font-style  "italic"})

(def ^:private subs-inputs-list-style
  {:display "flex" :flex-direction "column" :gap "2px"})

(def ^:private subs-value-cell-fill-style
  "Wrapper for the SUBSCRIPTIONS value cell's `:full` / `:full+diff`
  edn-inspector mounts — fills the grid track + permits the
  inspector's tree to wrap without overflow. Hoisted per rf2-zmkqi
  (the 2 residual literals that escaped rf2-zlk6h's 189-style hoist)."
  {:flex      1
   :min-width 0})

;; -- SUBSCRIPTIONS leaf-scalar FULL+DIFF chrome (rf2-fyd8u) ----------------
;;
;; The `subs-value-cell`'s `:full+diff` branch threads `:before` into
;; `ei/edn-inspector`, which paints the R1-R8 grammar on container
;; descendants. A leaf-scalar sub return (e.g. `:counter/value` returning
;; `1`) has no children for the inspector to paint on — the change signal
;; would otherwise drop silently. The branches here surface that signal
;; at the SUBSCRIPTIONS row level:
;;
;;   :first-run? true  → `:added` chrome (green stripe + `+` glyph +
;;                       low-alpha wash) — parity with the inspector's
;;                       mode-3 R1 `:added` shape (per `edn-inspector`
;;                       `:added` op).
;;   :first-run? false → value + inline `← was <prev>` annotation (prev
;;                       routes through `ei/mini` for syntax-token
;;                       chrome; prose stays muted text-tertiary).

(def ^:private subs-leaf-row-style
  "Outer wrapper for the leaf-scalar FULL+DIFF branch. `inline-flex`
  (parity with `gutter-row`'s inline-flex shape per rf2-1bra5) so the
  value composes inline with the optional `← was X` annotation without
  forcing a block-level row break inside the table cell."
  {:display       "inline-flex"
   :align-items   "baseline"
   :flex-wrap     "wrap"
   :gap           "4px"
   :max-width     "100%"})

(def ^:private subs-leaf-was-style
  "Inline `← was <prev>` annotation chip. Per the bead body the prose
  stays muted (text-tertiary) and the prev value picks up syntax-token
  chrome from `ei/mini` (rendered inline as a child span). Mirrors the
  shape of `edn-inspector`'s `change-annotation` chip (sans-stack,
  italic, 11px) but anchored at this row level — the inspector's leaf-
  scalar return has no per-leaf annotation surface."
  {:margin-left "8px"
   :color       text-tertiary-colour
   :font-family sans-stack
   :font-size   "11px"
   :font-style  "italic"
   :display     "inline-flex"
   :align-items "baseline"
   :gap         "4px"})

(def ^:private subs-leaf-added-row-style
  "Outer style for the `:first-run?` `:added` chrome — green left-edge
  stripe + low-alpha wash + leading `+` glyph. Parity with the
  edn-inspector's `gutter-row` `:added` shape (rf2-fyd8u). The stripe /
  wash colours come from the inspector's reserved diff token family
  (`:diff-added-stripe` / `:diff-added-wash`) so the SUBSCRIPTIONS row's
  chrome reads visually identical to the inspector's R1 `:added` shape."
  {:display       "inline-flex"
   :align-items   "baseline"
   :flex-wrap     "wrap"
   :gap           "4px"
   :padding-left  "6px"
   :border-left   (str "2px solid " (:diff-added-stripe tokens))
   :background    (:diff-added-wash tokens)
   :max-width     "100%"})

(def ^:private subs-leaf-added-glyph-style
  "Leading `+` glyph for the `:first-run?` `:added` chrome. Bold green
  (`:diff-gutter`), parity with the inspector's `gutter-row` glyph cell
  shape."
  {:flex        "0 0 12px"
   :color       (:diff-gutter tokens)
   :font-size   "11px"
   :font-weight 700
   :text-align  "center"
   :user-select "none"})

;; -- SUBSCRIPTIONS filter button bar --------------------------------------
;;
;; rf2-x8aqd (2026-05-29) — these literals were previously hoisted as
;; `mode-toggle-{bar,button-active,button-inactive}-style` and aliased
;; via `(def subs-filter-… mode-toggle-…)`. rf2-vv3m6 retired the
;; mode-toggle (FULL+DIFF is the single HANDLER rendering) which deleted
;; the upstream defs and left the aliases dangling (3 unresolved-symbol
;; kondo warnings). The SUBSCRIPTIONS [all][changed][unchanged] filter
;; bar is a separate live feature, so we inline the original literals
;; here as the sole owner.

(def ^:private subs-filter-bar-style
  {:display       "inline-flex"
   :align-items   "center"
   :gap           0
   :border        border-subtle-1px
   :border-radius "3px"
   :overflow      "hidden"
   :margin-left   "8px"
   :line-height   1})

(def ^:private subs-filter-button-base-style
  {:border         "none"
   :padding        "2px 8px"
   :font-family    sans-stack
   :font-size      "10px"
   :font-weight    700
   :text-transform "uppercase"
   :letter-spacing "0.5px"
   :cursor         "pointer"
   :line-height    1})

(def ^:private subs-filter-button-active-style
  ;; rf2-pjze8 — the selected segment was `:accent` (GitHub blue), which
  ;; collides with the higher-priority blue signals on the page (changed/
  ;; recompute, active L4 tab, mode stripe, focus ring). Repaint it a
  ;; DARKER NEUTRAL GREY (`:bg-3`, raised) with a `:text-primary` label
  ;; and a `:border-default` edge for definition, so the selection reads
  ;; clearly without competing with the reserved accent. Token keywords
  ;; (not raw hex) so both themes resolve through the CSS-var map.
  (assoc subs-filter-button-base-style
         :background bg-3-colour
         :color      text-primary-colour
         :box-shadow (str "inset 0 0 0 1px " border-default-colour)))

(def ^:private subs-filter-button-inactive-style
  (assoc subs-filter-button-base-style
         :background "transparent"
         :color      text-secondary-colour))

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

;; rf2-bhi3t — the per-row render-cause chip ("← props" / "← :sub-id").
;; Sub-driven re-renders tint accent (the reactive cause — same family
;; as the sub column); props-driven re-renders tint tertiary (the muted
;; orthogonal `:rf/props` channel). Mounts render no chip — the cause
;; question is about RE-renders, and the (mounted) signal lives elsewhere.
(def ^:private views-row-cause-base-style
  {:margin-left "8px"
   :font-size   "10px"
   :font-family mono-stack})

(def ^:private views-row-cause-sub-style
  (assoc views-row-cause-base-style :color accent-colour))

(def ^:private views-row-cause-props-style
  (assoc views-row-cause-base-style :color text-tertiary-colour))

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
;; drift now surfaces via the Issues panel exclusively (rf2-7gf7v).

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

;; `schema-violation-action-link-style` + `schema-violation-actions-style`
;; retired (rf2-wnvid) — they backed the error-card's jump-to-source
;; action row, which rf2-wnvid dropped as redundant with the HANDLER
;; step's verb link.

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

;; -- inline EXCEPTION card (rf2-ahhgn) ------------------------------------
;;
;; A handler / interceptor / coeffect / fx / flow EXCEPTION renders as a
;; red-edged card under its owning step (sibling to the amber schema-
;; violation card). Distinct chrome from violations: a violation is an
;; expected-ish boundary rejection (amber, "Aborted"/"Skipped"); an
;; exception is a genuine bug (red `✗`, the exception MESSAGE verbatim).
;; The card reuses the violation block's flex/padding skeleton so the two
;; failure surfaces read as a family, with the error tone swapped in.

(def ^:private error-block-style
  (assoc schema-violation-block-style
         :border (str "1px solid " error-colour)))

(def ^:private error-block-title-style
  (assoc schema-violation-title-style
         :color error-colour))

(def ^:private error-block-message-style
  ;; The exception message is verbatim text the developer wrote in their
  ;; `ex-info` — render it monospace (it often quotes code / ids) and in
  ;; the primary text colour so it reads as the punchline of the card.
  {:color       text-primary-colour
   :font-family mono-stack
   :font-size   "12px"
   :line-height 1.5
   :word-break  "break-word"
   :margin      "2px 0"})

(def ^:private error-block-recovery-chip-style
  (assoc schema-violation-rollback-chip-style
         :text-transform "none"))

;; rf2-wnvid — collapsible exception details (`<details>`/`<summary>`).
;; The stack trace + ex-data are diagnostic depth the operator wants on
;; demand, not always-expanded clutter under every failed step. A native
;; `<details>` element gives a zero-app-db-state, accessible disclosure;
;; the summary row is the clickable affordance.
(def ^:private error-block-details-style
  {:margin "4px 0 0 0"})

(def ^:private error-block-summary-style
  {:cursor      "pointer"
   :color       text-secondary-colour
   :font-family sans-stack
   :font-size   "11px"
   :user-select "none"
   :outline     "none"})

(def ^:private error-block-stack-style
  ;; The stack trace is verbatim multi-line text — render it monospace
  ;; in a scrollable, pre-wrapped block so long stacks don't blow out the
  ;; panel width.
  {:color          text-tertiary-colour
   :font-family    mono-stack
   :font-size      "11px"
   :line-height    1.45
   :white-space    "pre-wrap"
   :word-break     "break-word"
   :max-height     "240px"
   :overflow       "auto"
   :margin         "4px 0 0 0"
   :padding        "6px 8px"
   :background     bg-1-colour
   :border-radius  "3px"})

(def ^:private error-block-data-label-style
  {:color          text-tertiary-colour
   :font-family    sans-stack
   :font-size      "10px"
   :text-transform "uppercase"
   :letter-spacing "0.5px"
   :margin         "6px 0 2px 0"})

(def ^:private rolled-back-mute-style
  {:opacity 0.55})

;; rf2-yz57h — SKIPPED-step body. When an upstream `:before`-chain throw
;; (coeffect injector / `:before` interceptor) aborts the cascade, the
;; HANDLER + SIDE EFFECTS steps never run. The view renders a SKIPPED
;; placeholder body — a muted italic line stating the step did not run —
;; instead of the normal body (whose `:db` sub-section would otherwise read
;; the misleading "— no :db (handler returned no :db)" even though the
;; handler's body DOES return a :db; it simply never executed). The
;; SKIPPED placeholder body itself carries the "did not run" signal
;; (the per-stage header glyph retired in rf2-9wq0v).
(def ^:private skipped-body-style
  {:margin-top  "5px"
   :padding     "6px 8px"
   :background  bg-1-colour
   :border      border-subtle-1px
   :border-radius "3px"
   :font-family sans-stack
   :font-size   "12px"
   :font-style  "italic"
   :color       text-tertiary-colour})

(defn- skipped-body
  "Render a SKIPPED step's body (rf2-yz57h) — a muted italic line stating
  the step did not run because an upstream step threw. `what` names the
  step for the operator (e.g. \"The handler\" / \"Side effects\")."
  [testid what]
  [:div {:data-testid (str testid "-skipped")
         :data-rf-xray-step-skipped "true"
         :style skipped-body-style}
   (str what " did not run — an upstream step threw before this step could execute.")])

;; `rolled-back-banner-style` + the `rolled-back-banner` render fn
;; were retired (rf2-w8evg) — the rf2-7gf7v / rf2-8resu redesign moves
;; the :app-db violation to the FX step's :db row (the implicit
;; commit fx), so the standalone HANDLER-level "cascade rolled back"
;; banner has no caller. Downstream-mute treatment lives on via
;; `rolled-back-mute-style` (applied in `render-pipeline-steps`).

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

;; rf2-ahhgn epoch outcome banner — RETIRED (rf2-wnvid). The top-of-
;; pipeline "This event failed — see the ✗ step below." banner restated
;; what the cascade now shows inline (the failing step's 'Exception
;; Thrown' card; the per-stage ✗ glyph itself retired in rf2-9wq0v).
;; `outcome-banner-error-style` + `outcome-banner` are gone; the panel
;; root keeps `data-rf-xray-outcome` for tools / e2e.

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

;; rf2-xgeag — violation sub-block defined further down the file (in
;; the §SCHEMA VIOLATION sub-block section), but each step renderer
;; above attaches a `(violation-blocks ...)` sub-block to its body.
;; Forward-declared here so the namespace compiles in source order
;; without warnings. (`rolled-back-banner` forward declare retired
;; alongside its defn — rf2-w8evg.)
(declare violation-blocks)
(declare violation-block)
(declare error-blocks)
(declare error-block)

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
  `<div>` so clicking anywhere on the row toggles `expanded?` when the
  step carries expandable content (`expandable?` true).

  rf2-9wq0v retired the per-stage ✓/✗/⊘ status glyph that used to ride
  immediately after the badge pill — a clean run painted a tick on every
  stage (no information), and a failure is already shown by the inline
  exception card UNDER the failing stage (rf2-yz57h / rf2-wnvid). The
  overall cascade-outcome banner + the per-EFFECT SIDE-EFFECTS ledger
  glyphs carry the surviving signals."
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
  (let [label (if source (name source) "unknown")]
    ;; rf2-vw5pi — routes through the shared `coord-link` (label-as-link
    ;; companion to `coord-chip`); the per-site link / plain styles stay
    ;; here, the button + dispatch + nil-coord fallback are shared.
    (coord-link/coord-link coord label "rf-xray-epoch-dispatch-source-label"
                           {:style       link-button-style
                            :plain-style dispatch-source-plain-style})))

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
  ;; rf2-vw5pi — shared `coord-link`; per-site styles preserved.
  (coord-link/coord-link coord path-str testid
                         {:style       dispatch-source-state-path-button-style
                          :plain-style dispatch-source-state-path-plain-style}))

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
  ;; rf2-nesy9 — render-time frame capture so the deferred parent-epoch
  ;; focus click dispatches into the surrounding instance frame (the
  ;; affordance renders inside the Epoch Panel reg-view), not a
  ;; `:rf/xray` literal.
  (let [frame (rf/current-frame)]
   (cond
    (some? parent-epoch-id)
    [:button {:data-testid "rf-xray-epoch-dispatch-parent-epoch-link"
              :aria-label  (str "focus parent epoch #" parent-epoch-id)
              :title       (str "focus parent epoch #" parent-epoch-id)
              :on-click    (fn [e]
                             (.stopPropagation e)
                             (rf/dispatch
                               [:rf.xray/focus-epoch parent-epoch-id]
                               {:frame frame}))
              :style dispatch-source-parent-epoch-button-style}
     (str "parent epoch #" parent-epoch-id)]

    (some? parent-dispatch-id)
    [:span {:data-testid "rf-xray-epoch-dispatch-parent-epoch-unresolved"
            :style dispatch-source-parent-epoch-plain-style}
     (str "parent dispatch #" parent-dispatch-id " (not in buffer)")]

    :else nil)))

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
  on the dispatched trace (`:rf.event/source-detail :ms`).

  rf2-x25e0 — `dispatch-id->epoch-id` is the precomputed lookup map
  built once per panel render in `Panel` and threaded through `ctx`.
  Replaces the prior O(N) `(some … epoch-history)` scan with an O(1)
  map `get`."
  [source {:keys [parent-dispatch-id delay-ms]} dispatch-id->epoch-id]
  (let [kind-label (case source
                     :fx-dispatch       ":dispatch"
                     :fx-dispatch-later ":dispatch-later"
                     (name source))
        parent-epoch-id (when parent-dispatch-id
                          (proj/find-parent-epoch dispatch-id->epoch-id
                                                  parent-dispatch-id))]
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
  [{:keys [source coord source-enrichment] :as step} dispatch-id->epoch-id]
  (case source
    :after-timer       (if source-enrichment
                         (dispatch-after-timer-label source-enrichment)
                         (dispatch-source-label source coord))
    :machine-spawn     (if source-enrichment
                         (dispatch-machine-spawn-label source-enrichment)
                         (dispatch-source-label source coord))
    :fx-dispatch       (dispatch-fx-label source (or source-enrichment {})
                                          dispatch-id->epoch-id)
    :fx-dispatch-later (dispatch-fx-label source (or source-enrichment {})
                                          dispatch-id->epoch-id)
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

  `dispatch-id->epoch-id` is the optional precomputed
  `{dispatch-id → epoch-id}` index (rf2-x25e0) the `:fx-dispatch` /
  `:fx-dispatch-later` enrichments use to resolve the
  parent-dispatch-id → parent-epoch-id link in O(1) (rendered as a
  click-to-navigate `:rf.xray/focus-epoch` button). When omitted
  (direct test calls of the renderer) the parent-epoch chip falls
  back to the unresolved variant. The index is built once per panel
  render in `Panel` and threaded down via `ctx`."
  ([step] (render-dispatch-step step nil))
  ([{:keys [source coord duration-ms step-number violations] :as step}
    dispatch-id->epoch-id]
   [:div {:data-testid "rf-xray-epoch-step-dispatch"
          :data-step-kw "dispatch"
          :data-source (when source (name source))}
    (numbered-circle step-number :DISPATCH)
    (step-header
      {:step :dispatch
       :badge :DISPATCH
       :verb (let [enriched (dispatch-source-enriched-label step dispatch-id->epoch-id)]
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
        label      (proj/ns-keyword id)]
    [:div {:key (str "cofx-" idx)
           :data-testid (str "rf-xray-epoch-coeffect-row-" idx)
           :style coeffect-row-style}
     ;; rf2-vw5pi — id via shared `coord-link` (clickable when coord
     ;; captured, plain span otherwise); per-site styles preserved.
     (coord-link/coord-link coord label (str "rf-xray-epoch-coeffect-row-id-" idx)
                            {:style       link-button-inherit-style
                             :plain-style coeffect-row-id-plain-style})
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
  [{:keys [id value no-value? step-number violations errors]}]
  (let [cofx-meta  (when (keyword? id)
                     (try (rf/handler-meta :cofx id)
                          (catch :default _ nil)))
        coord      (when (and cofx-meta (string? (:file cofx-meta)))
                     {:file (:file cofx-meta) :line (:line cofx-meta)})
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
        ;; badge per pair-debug 2026-05-26. rf2-vw5pi — via shared
        ;; `coord-link`; per-site verb styles preserved.
        :verb (coord-link/coord-link coord label
                                     (str "rf-xray-epoch-coeffect-id-" (name id))
                                     {:style       coeffect-verb-link-button-style
                                      :plain-style coeffect-verb-plain-style})
        :expandable? false
        :testid (str "rf-xray-epoch-coeffect-" (name id))}
       nil)
     ;; Body — `+ [:cofx-id] <value>` diff-style line. Per pair-debug
     ;; 2026-05-26 the body sits left-aligned with the badge (no
     ;; indent) so the diff-line reads at the same column as the
     ;; header's badge pill.
     ;;
     ;; rf2-yz57h — a coeffect that THREW on injection
     ;; (`:rf.error/coeffect-exception`, button-19) produced no resolved
     ;; value, so the projection stamps `:no-value?` + omits `:value`. The
     ;; diff-line is replaced by a muted "injection failed" line; the shared
     ;; 'Exception Thrown' card below carries the message + details.
     (if no-value?
       [:div {:data-testid (str "rf-xray-epoch-coeffect-failed-" (name id))
              :style coeffect-body-style}
        [:span {:style coeffect-body-path-style}
         (str "injection of [" (proj/ns-keyword id) "] threw")]]
       [:div {:data-testid (str "rf-xray-epoch-coeffect-value-" (name id))
              :style coeffect-body-style}
        [:span {:style coeffect-body-plus-style} "+"]
        [:span {:style coeffect-body-path-style}
         (str "[" (proj/ns-keyword id) "]")]
        [:span {:style coeffect-body-value-style}
         [ei/mini value 80]]])
     ;; rf2-yz57h — a coeffect-injection EXCEPTION attaches here as the
     ;; shared inline 'Exception Thrown' card (button-19), under the
     ;; COEFFECT step where it occurred (no longer collapsed onto HANDLER).
     (error-blocks :coeffect errors)
     ;; rf2-xgeag — `:cofx` boundary violations attach to the matching
     ;; COEFFECT step by cofx-id.
     (violation-blocks :coeffect violations)]))

;; ---- INTERCEPTOR step (rf2-yz57h) ---------------------------------------
;;
;; The pipeline had no distinct interceptor step before rf2-yz57h —
;; interceptors WRAP the handler chain rather than appearing as their own
;; cascade entry. A user-interceptor `:before` / `:after` throw
;; (rf2-mszrz `:rf.error/interceptor-exception`) therefore had no home and
;; collapsed onto HANDLER. The INTERCEPTOR step gives those exceptions a
;; home (between COEFFECTS and HANDLER — the cascade position of the chain)
;; and makes the throwing interceptor + its phase visible.
;;
;; CONDITIONAL — the projection emits the step only when an interceptor
;; threw this cascade (the substrate emits no per-interceptor "ran" trace,
;; so a clean chain leaves nothing to show). One row per throwing
;; interceptor: the interceptor id (click-to-source via `handler-meta`,
;; degrading to plain text) + a `:before` / `:after` phase chip + the
;; shared 'Exception Thrown' card (rf2-wnvid).

(def ^:private interceptor-row-style
  {:display     "flex"
   :align-items "center"
   :gap         "8px"
   :padding     "3px 0"
   :font-family mono-stack
   :font-size   "12px"
   :flex-wrap   "wrap"})

(def ^:private interceptor-phase-chip-style
  {:display        "inline-flex"
   :align-items    "center"
   :background     bg-3-colour
   :color          text-tertiary-colour
   :border         border-subtle-1px
   :border-radius  "2px"
   :padding        "1px 5px"
   :font-family    mono-stack
   :font-size      "10px"
   :font-weight    600
   :letter-spacing "0.3px"
   :white-space    "nowrap"})

(defn- interceptor-phase-label
  "Render an interceptor exception row's `:phase` as a UI chip label
  (rf2-yz57h). `:before` (threw on the way IN — handler skipped) /
  `:after` (threw on the way OUT — handler ran first). nil → no chip."
  [phase]
  (case phase
    :before "before"
    :after  "after"
    (when (keyword? phase) (name phase))))

(defn- interceptor-row-view
  "Render one INTERCEPTOR-step row (rf2-yz57h) — the throwing interceptor's
  id + a `:before` / `:after` phase chip + the shared inline 'Exception
  Thrown' card.

  rf2-siheh — the jump-to-source coord rides the projection row's
  `:coord` slot (captured by the `->interceptor` macro from `(meta &form)`
  and threaded onto the trace by the router). The label hyperlinks via
  `coord-link` when a coord is present AND a shared `coord-chip` glyph
  is appended (exact parity with the EVENT HANDLER / SUBSCRIPTIONS / VIEWS
  rows); both drop out cleanly to plain text + no chip when the
  interceptor was built via the `->interceptor*` fn, is a framework
  interceptor, or the bundle elided the coord in production."
  [idx {:keys [interceptor-id phase errors coord]}]
  (let [label (proj/ns-keyword interceptor-id)]
    [:div {:key (str "interceptor-row-" idx)
           :data-testid (str "rf-xray-epoch-interceptor-row-" idx)
           :data-interceptor-phase (when phase (name phase))}
     [:div {:style interceptor-row-style}
      (coord-link/coord-link coord label
                             (str "rf-xray-epoch-interceptor-id-" idx)
                             {:style       coeffect-verb-link-button-style
                              :plain-style coeffect-verb-plain-style})
      ;; rf2-siheh — shared open-in-editor glyph (parity with the
      ;; EVENT HANDLER / SUBSCRIPTIONS / VIEWS rows). Drops out when
      ;; `coord` is nil (no `:file`).
      (coord-chip/coord-chip coord
                             (str "rf-xray-epoch-interceptor-row-coord-" idx))
      (when-let [pl (interceptor-phase-label phase)]
        [:span {:data-testid (str "rf-xray-epoch-interceptor-phase-" idx)
                :style interceptor-phase-chip-style}
         pl])]
     ;; rf2-yz57h — the interceptor EXCEPTION attaches here as the shared
     ;; inline 'Exception Thrown' card (button-17 :before / button-18
     ;; :after), under the INTERCEPTOR step where it occurred.
     (error-blocks (keyword (str "interceptor-row-" idx)) errors)]))

(defn render-interceptor-step
  "Render the INTERCEPTOR step (rf2-yz57h — present ONLY when a user
  interceptor threw this cascade). One row per throwing interceptor; each
  carries the interceptor id (click-to-source) + a `:before` / `:after`
  phase chip + the shared 'Exception Thrown' card. The step's `:errors`
  slot (attached by `attach-exceptions`) is rendered per-row by matching
  the exception's `:failing-id` to the row's `:interceptor-id`."
  [{:keys [rows step-number errors]}]
  ;; The step-level `:errors` (from `attach-exceptions`) carry the exception
  ;; records; thread each onto its matching row by `:failing-id`.
  (let [rows* (mapv (fn [row]
                      (assoc row :errors
                             (filterv #(= (:interceptor-id row) (:failing-id %))
                                      errors)))
                    rows)]
    [:div {:data-testid "rf-xray-epoch-step-interceptor"
           :data-step-kw "interceptor"}
     (numbered-circle step-number :INTERCEPTOR)
     ;; rf2-oqi0c — the "N interceptor(s) threw" summary verb is DROPPED:
     ;; redundant with the per-interceptor row(s) + the inline 'Exception
     ;; Thrown' card(s) below, which already carry which interceptor threw
     ;; and on which phase. The badge stands alone.
     (step-header
       {:step :interceptor
        :badge :INTERCEPTOR
        :expandable? false
        :testid "rf-xray-epoch-interceptor"}
       nil)
     [:div {:style margin-top-5-style}
      (map-indexed (fn [i row] (interceptor-row-view i row)) rows*)]]))

;; ---- HANDLER step --------------------------------------------------------

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
  ;; rf2-vw5pi — shared `coord-link`; per-site styles preserved. The
  ;; testid is now a single stem (`…-verb-link-<step>`) across both the
  ;; clickable + plain branches — the prior `…-verb-<step>` plain-only
  ;; variant was a hand-rolled artefact, not pinned by any selector.
  (coord-link/coord-link coord verb-string
                         (str "rf-xray-epoch-machine-cascade-verb-link-" (:step row))
                         {:style       cascade-verb-link-button-style
                          :plain-style cascade-verb-plain-style}))

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
        label    (proj/handler-flavour-label flavour)]
    ;; rf2-vw5pi — the HANDLER verb-as-link routes through the shared
    ;; `coord-link`. Single testid across both branches (the prior
    ;; `…-verb-plain` variant was a hand-rolled artefact, not pinned);
    ;; per-site link / plain styles preserved.
    (coord-link/coord-link coord label "rf-xray-epoch-handler-verb-link"
                           {:style       handler-verb-link-button-style
                            :plain-style handler-verb-plain-style})))

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

(defn- handler-db-diff-block
  "Render the HANDLER step's `:db` sub-section (rf2-93436 / design
  doc §Section 1 + §Section 2). Always renders for non-machine
  handlers.

  rf2-vv3m6 (2026-05-29) — the prior `[diff][full][full+diff]` mode
  toggle (rf2-n2jig / rf2-yqjrd) is retired. FULL+DIFF is the single
  rendering: the full post-handler `:db` tree with inline diff
  annotations driven off `:db-before`. The R1-R8 grammar paints
  gutter glyphs + row washes + leaf-scalar `← was X` suffixes;
  auto-collapse keeps unchanged subtrees folded so the density matches
  what the prior `:diff` lens used to provide.

  rf2-4wywy / rf2-48oc4 — the rendered `:db` is the EFFECTIVE
  POST-HANDLER db (the projection's `:db-post-handler`), NOT the epoch
  record's `:db-after`. `:db-after` is the FINAL post-flow / post-commit
  state — reading it conflated the handler's change with any flow
  recompute that followed (flows write app-db AFTER the handler). The
  HANDLER step shows ONLY what the handler returned; the FLOW step shows
  the flow's OWN `:db` diff (the pre→post reshape).

  The projection resolves `:db-post-handler` without assuming the
  handler returned a `:db` (`projection/effective-post-handler-db`):
  the t1 snapshot (`:rf.event/db-pending`) when the handler returned
  `:db`; `db-before` when the handler returned NO `:db` yet a flow
  fired (rf2-48oc4 — the HANDLER step then shows NO `:db` change, since
  the post-handler db equals db-before); nil otherwise. Graceful
  fallback: when the projection left the slot nil (no flow + no `:db`,
  or a pre-rf2-ta0y7 runtime) the block falls back to the record's
  `:db-after` so older epochs still render.

  The `:db-diff` arg is unused under the single rendering but stays in
  the parent's `handler-body` signature for projection compatibility.

  rf2-wnvid — PHANTOM-`:db` fix. When the handler wrote NO `:db` effect
  (`db-write?` false — e.g. it returned only `:fx`), the block renders a
  `— no :db (handler returned no :db)` placeholder instead of falling
  back to the record's full post-cascade `:db-after`. The pre-rf2-wnvid
  fallback painted the ENTIRE app-db tree under the HANDLER step as if
  the handler had returned it — misleading on a handler that mutated
  nothing.

  rf2-oqi0c — the THREW case no longer reaches this block at all: the
  caller (`handler-body`) OMITS the whole `:db` sub-section when the
  handler threw (the inline exception card is the signal), so the
  placeholder is only ever the clean 'returned no :db' wording.

  Suppressed for machine handlers — per design §Section 3 §DB DIFF
  the snapshot IS the db change (at `[:rf/runtime :machines :snapshots <id>]`) so the
  slot folds into SNAPSHOT DIFF rather than carrying a redundant
  standalone slot."
  [_db-diff db-post-handler db-write?]
  (let [record    @(rf/subscribe [:rf.xray/selected-epoch-record])
        db-before (:db-before record)
        ;; rf2-4wywy — t1 (post-handler, pre-flow) is the authoritative
        ;; HANDLER `:db`; fall back to the record's post-flow `:db-after`
        ;; only when the runtime stamped no t1 — but ONLY when the handler
        ;; actually wrote a `:db` (rf2-wnvid). A handler that wrote no
        ;; `:db` resolves the no-write placeholder below, never the
        ;; phantom full-app-db fallback.
        db-after  (if (some? db-post-handler)
                    db-post-handler
                    (:db-after record))]
    [:div {:data-testid "rf-xray-epoch-handler-db-diff"
           ;; rf2-xvu24 — canonical `data-rf-xray-diff-mode` axis. Now
           ;; a constant post-rf2-vv3m6; kept for selector compatibility
           ;; (tools / e2e can still pin the FULL+DIFF rendering).
           :data-rf-xray-diff-mode "full+diff"
           :data-rf-xray-db-write (str (boolean db-write?))}
     (sub-header ":db" nil)
     (cond
       ;; rf2-wnvid — the handler wrote no `:db` → no phantom app-db.
       ;; rf2-oqi0c — the threw case is omitted upstream (`handler-body`),
       ;; so this placeholder is only ever the clean 'returned no :db'.
       (not db-write?)
       [:span {:data-testid "rf-xray-epoch-handler-db-no-write"
               :style handler-db-all-missing-style}
        "— no :db (handler returned no :db)"]

       (some? db-after)
       [:div {:data-testid "rf-xray-epoch-handler-db-full-with-diff"
              :style handler-db-all-style}
        [ei/edn-inspector db-after
         {:site-id [:rf.xray.epoch/handler-db-full-with-diff (:epoch-id record)]
          :before db-before
          :default-expanded-depth 3}]]

       :else
       [:span {:data-testid "rf-xray-epoch-handler-db-full-with-diff-missing"
               :style handler-db-all-missing-style}
        "— db-after not available in epoch record"])]))

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
  [{:keys [flavour event-id db-diff db-post-handler db-write? fx-vec other-effects
           machine errors] :as _row}]
  (let [machine? (= :reg-machine flavour)
        ;; rf2-wnvid — the handler threw iff a `:rf.error/handler-exception`
        ;; attached to this step (`:errors`). Tunes the no-`:db` placeholder
        ;; wording ('handler threw' vs 'returned no :db').
        threw?   (boolean (seq errors))]
    [:div {:data-testid "rf-xray-epoch-handler-body"}
     ;; Source / machine spec block — rf2-66wis
     (handler-source-block flavour event-id)
     ;; Machine cascade BEFORE db diff (the cascade IS the story for
     ;; machines — rf2-u69j7 redesign).
     (when machine
       (machine-block machine event-id))
     ;; :db diff — always present for non-machine handlers (rf2-93436);
     ;; folded into SNAPSHOT DIFF for machines. rf2-4wywy — the
     ;; post-handler (t1) db is threaded so the diff shows ONLY the
     ;; handler's contribution, not the post-flow state.
     ;; rf2-oqi0c — OMIT the `:db` sub-section entirely when the handler
     ;; THREW: the redundant "— no :db (handler threw)" line was noise (the
     ;; inline 'Exception Thrown' card below is the signal). The slot
     ;; stays present for a clean handler that simply returned no `:db`.
     (when (and (not machine?) (not threw?))
       (handler-db-diff-block db-diff db-post-handler db-write?))
     ;; :fx — the canonical vector-of-vectors, FULL via edn-inspector.
     ;; rf2-5t8y8 — sub-header carries a trailing entry-count chip ("N
     ;; entr{y,ies}") that the edn-inspector vector-header chrome alone
     ;; doesn't surface at the same at-a-glance density.
     (when (seq fx-vec)
       (let [n (count fx-vec)]
         [:div {:data-testid "rf-xray-epoch-handler-fx"}
          (sub-header ":fx" (str n " entr" (if (= 1 n) "y" "ies")))
          [ei/edn-inspector fx-vec
           {:site-id                [:rf.xray.epoch/handler-fx event-id]
            :card?                  false
            :zoomable?              true
            :default-expanded-depth 16}]]))
     ;; other — return map minus :db and :fx, FULL via edn-inspector.
     ;; rf2-5t8y8 — entry-count chip on the sub-header (parallel to :fx).
     (when (seq other-effects)
       (let [n (count other-effects)]
         [:div {:data-testid "rf-xray-epoch-handler-other"}
          (sub-header "other" (str n " entr" (if (= 1 n) "y" "ies")))
          [ei/edn-inspector other-effects
           {:site-id                [:rf.xray.epoch/handler-other event-id]
            :card?                  false
            :zoomable?              true
            :default-expanded-depth 16}]]))]))

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
  [{:keys [flavour event-id duration-ms step-number violations errors]
    :as step}]
  (let [status   (proj/step-status step)
        skipped? (= :skipped status)]
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
        ;; rf2-yz57h — a skipped handler has no real duration (it never
        ;; ran); elide the chip.
        :duration-ms (when-not skipped? duration-ms)}
       nil)
     (if skipped?
       ;; rf2-yz57h — the handler was skipped (a `:before` interceptor /
       ;; coeffect threw upstream). Render the SKIPPED placeholder instead
       ;; of the normal body — the prior body's `:db` sub-section read
       ;; "— no :db (handler returned no :db)" which was WRONG: the handler
       ;; body returns a :db (via `bump`), it just never executed.
       (skipped-body "rf-xray-epoch-handler" "The handler")
       (handler-body step))
     ;; rf2-ahhgn — a handler EXCEPTION attaches here as an inline error
     ;; card (button-16). Rendered BELOW the handler body so the operator
     ;; reads what the handler tried to do, then the failure that aborted
     ;; it. (Coeffect / interceptor exceptions now land under their OWN
     ;; steps per rf2-yz57h, so this slot carries only genuine handler
     ;; throws.)
     (error-blocks :handler errors)
     (violation-blocks :handler violations)]))

;; ---- FLOW step -----------------------------------------------------------

(defn render-flow-step
  "Render one FLOW step — one PER flow that fired (rf2-xnb1x — mirror
  of the COEFFECT per-cofx restructure from pair-debug 2026-05-26).
  Each flow recompute gets its own numbered pipeline entry with the
  flow-id rendered as the verb (clickable to source when the
  registered flow carries `:file`/`:line` meta from `reg-flow`).

  The projection emits N flow step maps for a cascade with N flow
  recomputes.

  rf2-4wywy / rf2-48oc4 — the body renders the flow's OWN contribution
  as a `:db` DIFF rather than the prior `[path] before → after` scalar
  line. A flow's contribution IS an app-db mutation (it writes
  `:output` into `:path` AFTER the handler returned); rendering it as a
  `:db` diff via the shared edn-inspector diff renderer (parity with the
  HANDLER step's `:db` sub-section + the App-DB Diff panel) reads as
  'what the flow changed in app-db', and — crucially — keeps it SEPARATE
  from the HANDLER step's `:db` (which shows only the post-handler
  state). The diff endpoints are the projection's `:db-pre-flow` (the
  EFFECTIVE post-handler db) and `:db-post-flow` (t2 · what the flow
  returned). Scoped to the flow's `:path` so each FLOW step shows only
  its own slot's reshape (the projection threads the shared snapshots
  onto every flow step; `assoc-in` at the flow's path against pre / post
  isolates this flow's contribution).

  rf2-48oc4 — `:db-pre-flow` is the effective post-handler db, so the
  diff renders correctly EVEN WHEN the handler returned no `:db`: in
  that case the projection threads `db-before` (the actual post-handler
  db) as `:db-pre-flow`, NOT nil, so this branch renders a real diff
  rather than the scalar fallback.

  Graceful fallback: when the projection carried no pre/post snapshots
  (a pre-rf2-ta0y7 runtime, or neither t1 nor t2 on the stream) the
  body falls back to the per-path `[path] before → after` scalar line."
  [{:keys [flow-id path before after duration-ms step-number
           db-pre-flow db-post-flow errors]}]
  (let [flow-meta  (when (keyword? flow-id)
                     (try (rf/handler-meta :flow flow-id)
                          (catch :default _ nil)))
        coord      (when (and flow-meta (string? (:file flow-meta)))
                     {:file (:file flow-meta) :line (:line flow-meta)})
        label      (proj/ns-keyword flow-id)
        ;; rf2-4wywy / rf2-48oc4 — render a `:db` diff scoped to this
        ;; flow's path. db-pre-flow (effective post-handler db) lacks this
        ;; flow's write; db-post-flow (t2) carries it. Scoping to the path
        ;; isolates THIS flow's slot even when several flows rode the same
        ;; pre→post transition. When either endpoint is absent (pre-
        ;; rf2-ta0y7 / no snapshots) we render the scalar fallback.
        db-diff?   (boolean
                     (and (some? db-pre-flow) (some? db-post-flow)
                          (sequential? path) (seq path)))
        diff-before (when db-diff? (assoc-in db-pre-flow path before))
        diff-after  (when db-diff? (assoc-in db-post-flow path after))]
    [:div {:data-testid (str "rf-xray-epoch-step-flow-" (name flow-id))
           :data-step-kw "flow"
           :data-flow-id (name flow-id)
           :data-rf-xray-flow-db-diff (str db-diff?)}
     (numbered-circle step-number :FLOW)
     (step-header
       {:step :flow
        :badge :FLOW
        ;; Verb = flow-id (clickable when coord captured). Same
        ;; affordance shape as the COEFFECT step's cofx-id hyperlink.
        ;; rf2-vw5pi — via shared `coord-link`; per-site styles kept.
        :verb (coord-link/coord-link coord label
                                     (str "rf-xray-epoch-flow-id-" (name flow-id))
                                     {:style       coeffect-verb-link-button-style
                                      :plain-style coeffect-verb-plain-style})
        :expandable? false
        :testid (str "rf-xray-epoch-flow-" (name flow-id))
        :duration-ms duration-ms}
       nil)
     (if db-diff?
       ;; rf2-4wywy — the flow's own `:db` diff via the shared
       ;; edn-inspector diff renderer (FULL+DIFF, parity with HANDLER
       ;; `:db`). `:before` = t1-scoped, value = t2-scoped at this
       ;; flow's path → the inspector paints the flow's slot reshape.
       [:div {:data-testid (str "rf-xray-epoch-flow-db-diff-" (name flow-id))
              :style handler-db-all-style}
        (sub-header ":db" (proj/path-display path))
        [ei/edn-inspector diff-after
         {:site-id [:rf.xray.epoch/flow-db-diff flow-id]
          :before  diff-before
          :default-expanded-depth 3}]]
       ;; Fallback — `[path] before → after` scalar line, left-aligned
       ;; with the badge (no extra indent). Mirrors the COEFFECT step's
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
            [:span {:style coeffect-body-value-style} [ei/mini after 30]])]))
     ;; rf2-ahhgn — a flow-eval exception (the flow's compute fn threw,
     ;; aborting the cascade pre-commit) attaches here as an inline card.
     (error-blocks :flow errors)]))

;; ---- SIDE EFFECTS step (rf2-kt6js — the pre-rf2-kt6js FX step) ----------

(defn- fx-coord
  "Pull the registered fx-handler's source coord off
  `(rf/handler-meta :fx fx-id)`. Returns nil when no meta is captured.
  Mirrors the sibling `sub-coord` shape (rf2-g1mfc — bring the
  click-to-source affordance to the SIDE EFFECTS step's :fx rows on
  parity with the HANDLER verb, the SUBSCRIPTIONS rows, and the VIEWS
  rows).

  The `:file` here is ABSOLUTE: `reg-fx` registers through the same
  `core-reg-macros/defreg-macro` → `with-coords-form` → `coords-form`
  path that `reg-sub` / `reg-event-*` use, and that path runs the
  picked `:file` through `source-coords/absolutise-file` at macro-
  expansion time (rf2-wvsxg). So the chip's coord ships the right
  on-disk path with no error-coords fallback — unlike the VIEW case
  (rf2-quir9), where `reg-view` skips the absolutisation."
  [fx-id]
  (when (some? fx-id)
    (let [m (try (rf/handler-meta :fx fx-id) (catch :default _ nil))]
      (when (and m (string? (:file m)) (seq (:file m)))
        {:file (:file m) :line (:line m) :ns (:ns m)}))))

(defn- db-destination-marker
  "Render the `:db` ledger row's args slot — the clickable '→ app-db'
  DESTINATION marker (rf2-j630b). NOT the db diff: the actual change
  lives in the App-db panel; this marker jumps there for the focused
  epoch (the panel reads the same shared focus, so flipping the L3 tab
  to `:app-db` is the whole navigation). Render-time frame capture
  mirrors the CHILD-DISPATCHES jump affordance."
  [idx]
  (let [frame (rf/current-frame)]
    [:button {:data-testid (str "rf-xray-epoch-fx-row-db-destination-" idx)
              :aria-label "jump to the App-db panel for this epoch"
              :title "the committed db change is shown in the App-db panel"
              :on-click (fn [e]
                          (.stopPropagation e)
                          (rf/dispatch [:rf.xray/select-tab :app-db]
                                       {:frame frame}))
              :style db-destination-style}
     "→ app-db"]))

(defn- fx-row-view
  "Render one row of the flat SIDE EFFECTS ledger (rf2-j630b) — a leading
  per-effect status glyph + the effect-id + the effect args. One row per
  effect, in execution order; the `:db` row leads (when present), then
  the `:fx` entries, then `other` rows.

  Argument order matches `map-indexed`'s `(f idx item)` convention
  (rf2-cq0ch — companion swap with `coeffect-row-view`).

  The leading glyph + colour resolve off the shared
  `badge/fx-row-status-*` primitive (rf2-j630b): ✓ :ok / ✗ :error /
  ✗ :rollback / ↺ :overridden / – :skipped (the muted en-dash 'n/a',
  NEUTRAL — `:skipped-on-platform` carries the hover explainer).

  The `:db` row's args slot renders the clickable '→ app-db' DESTINATION
  marker (NOT the db diff — that lives in the App-db panel; no
  duplication). Every other row renders its args through the
  edn-inspector.

  Per rf2-uffov: when the row carries `:attributed-to`, a muted
  `← <action-id>` attribution chip rides alongside so the operator
  reads `fx X emitted by action Y` in one line.

  Per rf2-g1mfc: each fx-id carries the shared `coord-chip` open-in-
  editor affordance (exact parity with the SUBSCRIPTIONS / VIEWS rows
  + the HANDLER verb), sourcing the fx registration coord off
  `(rf/handler-meta :fx fx-id)` via `fx-coord`. The chip drops out
  cleanly when no coord was captured (framework-shipped fx with no
  user source, production builds without coords; the synthesised `:db`
  row has no reg-site)."
  [idx {:keys [fx-id status args value duration-ms attributed-to]}]
  (let [db-row?  (= :db fx-id)
        skipped? (= :skipped status)
        ;; :fx rows carry `:args`; `other` (dropped top-level) rows carry
        ;; `:value` — both render through the same edn-inspector slot.
        payload  (if (some? args) args value)]
    [:div {:key (str "fx-" idx)
           :data-testid (str "rf-xray-epoch-fx-row-" idx)
           :data-fx-status (when (keyword? status) (name status))
           :data-fx-attributed (str (some? attributed-to))
           :style fx-row-style}
     [:span {:style (assoc diff-glyph-bold-style
                           :color (badge/fx-row-status-colour status))
             :title (when skipped? badge/skipped-hover)}
      (badge/fx-row-status-glyph status)]
     [:span {:style fx-row-id-style}
      (proj/ns-keyword fx-id)
      ;; rf2-g1mfc — click-to-source via the shared `coord-chip`, exact
      ;; parity with the SUBSCRIPTIONS (~3000) + VIEWS rows + the HANDLER
      ;; verb. The coord lookup keys off `fx-id` and resolves the
      ;; `reg-fx` REGISTRATION site (absolute `:file`, rf2-wvsxg). Chip
      ;; drops out cleanly when no coord was captured (incl. the
      ;; synthesised :db row, which has no reg-site).
      (coord-chip/coord-chip (fx-coord fx-id)
                             (str "rf-xray-epoch-fx-row-coord-" idx))]
     ;; The :db row's args slot is the '→ app-db' DESTINATION marker, NOT
     ;; the db diff (rf2-j630b). Every other row renders its args through
     ;; the edn-inspector with `:default-expanded-depth 1` (rf2-ef2hy) so
     ;; the top-level fx-call surface is visible inline and nested maps
     ;; collapse to clickable chevrons. The operator scans the dense
     ;; ledger, then drills into a row's args via the chevron or the
     ;; popup-overlay (`:zoomable?`). Sibling rendering for the HANDLER
     ;; step's `:fx` section (rf2-p2zy0) uses `:default-expanded-depth 16`
     ;; (full-expand) — HANDLER reads INTENT, the ledger reads EXECUTION.
     (cond
       db-row?         (db-destination-marker idx)
       (some? payload) [:span {:style fx-row-args-style}
                        [ei/edn-inspector payload
                         {:site-id [:rf.xray.epoch/fx-row-args fx-id idx]
                          :card? false
                          :zoomable? true
                          :default-expanded-depth 1}]])
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
  "Render one fx row + any violations / exceptions attached to that row
  (rf2-xgeag / rf2-ahhgn). Per-row attachment matches when the
  projection's `attach-to-fx-row` (schema) / `attach-to-fx-error-row`
  (exception) resolved the `:failing-id` against an `fx-id` in the FX
  step's `:rows`. A throwing fx (button-18) surfaces its message + coord
  inline on its own row."
  [idx row]
  [:div {:key (str "fx-row-" idx)
         :data-testid (str "rf-xray-epoch-fx-row-wrapper-" idx)}
   (fx-row-view idx row)
   (error-blocks (keyword (str "fx-row-" idx)) (:errors row))
   (violation-blocks (keyword (str "fx-row-" idx)) (:violations row))])

;; ---- SIDE EFFECTS flat ledger (rf2-j630b — supersedes kt6js 3-tier) -----

(defn render-side-effects-step
  "Render the SIDE EFFECTS step as a FLAT per-effect ledger (rf2-j630b —
  supersedes the rf2-kt6js 3-tier `:db` / `:fx` / other sub-step
  presentation). ALWAYS present when ANY side effect occurred — including
  a bare reg-event-db that returns only `:db` (`db-commit?` keys off
  `:rf.event/db-changed`).

  The SIDE EFFECTS badge carries NO overall stage glyph (the per-stage
  ✓/✗ retired in rf2-9wq0v — a clean run was an all-tick row of no
  information, and a failure already shows on its own row + exception
  card). The per-EFFECT row glyphs are the whole signal. `:rows`-level
  outcome is still queryable via `proj/side-effects-badge-status` for the
  cascade-outcome banner + tests.
  There are NO post-commit / best-effort labels and NO group headers: the
  body is one row per effect, in EXECUTION order, each via
  `fx-row-with-violations`:

    1. the `:db` row (when a `:db` commit was attempted) — leading glyph
       ✓ committed / ✗ schema-fail rollback (the `:where :app-db`
       violation reason box rides the row via `attach-to-fx-db-row`), its
       args slot the clickable '→ app-db' DESTINATION marker;
    2. the `:fx`-vector entries in order — each with the rf2-g1mfc
       open-code chip + a per-effect glyph (✓ ran / ✗ threw / ↺ overridden
       / – skipped-on-platform). For async / deferred fx the ✓ means
       ACTIONED (handler invoked ok), not awaited;
    3. any top-level non-`:db`/`:fx` effects the runtime DROPPED — the
       muted – not-run diagnostic.

  A throwing row's expand is wnvid's shared 'Exception Thrown' card
  (`fx-row-with-violations` → `error-blocks`), compatible with yz57h's
  exception-under-step rendering. `:fx-args` / fx exception attachments
  that didn't match a row attach to the step level (rf2-xgeag /
  rf2-ahhgn) and render at the foot. `:db` schema-fail (pre-commit) →
  just the `:db` CROSS row, no fx rows (atomicity)."
  [{:keys [rows step-number threw violations errors] :as step}]
  (let [skipped? (= :skipped (proj/step-status step))]
    [:div {:data-testid "rf-xray-epoch-step-side-effects"
           :data-step-kw "side-effects"
           :data-fx-threw (str (or threw 0))}
     (numbered-circle step-number :SIDE-EFFECTS)
     (step-header
       {:step :side-effects
        :badge :SIDE-EFFECTS
        :expandable? false
        :testid "rf-xray-epoch-side-effects"}
       nil)
     (if skipped?
       ;; rf2-yz57h — side effects never ran (upstream `:before`-chain throw).
       (skipped-body "rf-xray-epoch-side-effects" "Side effects")
       [:div {:style margin-top-5-style}
        (map-indexed (fn [i row] (fx-row-with-violations i row)) rows)])
     ;; rf2-ahhgn — fx exceptions that didn't match a row (no-such-fx,
     ;; or an fx-id absent from `:rows`) attach to the step level.
     (error-blocks :side-effects errors)
     (violation-blocks :side-effects violations)]))

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
  ;; rf2-nesy9 — capture the surrounding instance frame at render time
  ;; (the bar renders inside the Epoch Panel reg-view) so the deferred
  ;; filter-mode click writes to THIS instance's Xray app-db, not the
  ;; `:rf/xray` singleton. Supersedes the prior `with-frame :rf/xray`
  ;; pin (the rf2-p56sk frame-anchor reasoning held only while Xray was
  ;; a single global frame).
  (let [frame (rf/current-frame)]
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
                           (rf/dispatch
                             [:rf.xray.epoch/set-subs-filter-mode m]
                             {:frame frame}))
               :style (if active?
                        subs-filter-button-active-style
                        subs-filter-button-inactive-style)}
      (name m)])]))

(defn- subs-leaf-scalar?
  "True iff `value` is a leaf-scalar — NOT a map, vector, set, or
  sequential — so the `:full+diff` edn-inspector has no children to
  paint R1-R8 diff chrome on. Mirrors the same predicate the
  edn-inspector uses internally to decide between container-recurse
  and `render-leaf-with-diff`. nil counts as a leaf (the
  `nil → <new-value>` transition is a leaf-scalar value change, per
  the bead body's `:counter/last-clicked` example).

  Per rf2-fyd8u — this is the discriminator the SUBSCRIPTIONS
  FULL+DIFF cell uses to route between the inspector mount (containers
  paint their own chrome) and the leaf-scalar branch (the row-level
  `← was X` annotation / `:added` chrome lives here, NOT inside the
  inspector — the inspector's leaf-scalar surface has no annotation
  hook to paint on)."
  [value]
  (not (or (map? value)
           (vector? value)
           (set? value)
           (sequential? value))))

(defn- subs-value-cell
  "Render the `changed` cell for one sub recomputation row under the
  single FULL+DIFF rendering.

  rf2-vv3m6 (2026-05-29) — the prior `[diff][full][full+diff]` mode
  toggle (rf2-yqjrd) is retired. The 3-arity is collapsed: only the
  FULL+DIFF branch survives.

  rf2-fyd8u — for FULL+DIFF the leaf-scalar branch (a sub that returns
  a scalar — number, string, keyword, nil) has its OWN rendering path:
  the edn-inspector's R1-R8 grammar paints diff chrome on container
  CHILDREN, but a scalar root has no children, so a value-change of
  e.g. `0 → 1` would otherwise drop silently. Two sub-branches at the
  row level:

    `:first-run?` true  → `:added` chrome (green stripe / leading `+`
                          glyph / low-alpha wash, parity with the
                          inspector's R1 `:added` shape). NO `← was`
                          annotation (no prior value existed — the row
                          tells a 'this sub is now alive' story).
    `:first-run?` false → value + inline `← was <prev>` annotation.
                          The prose stays muted (text-tertiary); the
                          prev value routes through `ei/mini` for the
                          syntax-token chrome (keyword magenta, number
                          orange, etc.).

  Containers (map / vector / set return values) keep the existing
  mount: the inspector paints child-level annotations via the R1-R8
  grammar — no per-row chrome change.

  Unchanged rows (`:changed? false`) render an empty cell — the
  value column is reserved for diff signal; absence of a row entry
  in this column is itself the unchanged indicator. Per Mike
  pair-debug 2026-05-27 (rf2-fqcdd follow-up) the leading ✓/✗
  ticks were dropped — redundant once the cell is value-only."
  [{:keys [sub-id changed? first-run? before after]} idx]
  (when changed?
    ;; rf2-fyd8u — leaf-scalar branch: paint the change signal at this
    ;; row level (the inspector has no leaf-scalar annotation surface).
    ;; Containers fall through to the inspector mount. Testid naming
    ;; note: the leaf-* testids deliberately use a prefix DISTINCT from
    ;; `rf-xray-epoch-sub-row-` (the parent row's testid) so prefix
    ;; counters like the sibling rf2-tzmmf filter tests don't pick the
    ;; leaf wrapper up as an additional "row".
    (if (subs-leaf-scalar? after)
      (if first-run?
        ;; first-cache-entry → :added chrome, no "was" annotation.
        [:div {:data-rf-xray-subs-leaf  "added"
               :data-rf-diff-op         "added"
               :data-testid             (str "rf-xray-epoch-subs-leaf-added-" idx)
               :style                   subs-leaf-added-row-style}
         [:span {:style subs-leaf-added-glyph-style} "+"]
         [:span [ei/mini after 40]]]
        ;; value change → value + inline ← was <prev>.
        [:div {:data-rf-xray-subs-leaf  "changed"
               :data-rf-diff-op         "modified"
               :data-testid             (str "rf-xray-epoch-subs-leaf-changed-" idx)
               :style                   subs-leaf-row-style}
         [:span [ei/mini after 40]]
         [:span {:data-rf-diff-annotation "subs-was"
                 :style                   subs-leaf-was-style}
          "← was "
          [:span {:data-rf-xray-subs-leaf-was "1"}
           [ei/mini before 40]]]])
      ;; rf2-kp7bw — a CONTAINER-valued sub return. On a first run
      ;; (`first-run?`, no prior cache entry) the inspector renders the
      ;; whole subtree as `:added` via the `:added?` opt (edn-inspector
      ;; §10.0.13) — parity with the scalar branch's row-level `:added`
      ;; chrome above. Pre-fix the container branch consulted ONLY
      ;; `:before`, which is nil on a first run, so the inspector
      ;; mounted plain (no diff mode, no added signal) while every
      ;; scalar sibling painted `:added`. Canonical case: the
      ;; `[:rf/route]` map sub on a /counter view-mount epoch. An
      ;; explicit prior value (`some? before`) is a genuine diff and
      ;; takes precedence over the first-run signal.
      [:div {:style subs-value-cell-fill-style}
       [ei/edn-inspector after
        (cond-> {:panel-id :rf.xray.epoch/subs-value
                 :site-id  [:rf.xray.epoch/subs-value sub-id idx :full+diff]
                 :default-expanded-depth 3}
          (some? before)                 (assoc :before before)
          (and first-run? (nil? before)) (assoc :added? true))]])))

(defn- sub-coord
  "Pull the registered sub's source coord off
  `(rf/handler-meta :sub sub-id)`. Returns nil when no meta is
  captured. Matches the sibling `view-coord` shape (rf2-d2akf —
  bring click-to-source affordance to disposed-sub rows on parity
  with the unmounted-views rows)."
  [sub-id]
  (when (some? sub-id)
    (let [m (try (rf/handler-meta :sub sub-id) (catch :default _ nil))]
      (when (and m (string? (:file m)))
        {:file (:file m) :line (:line m) :ns (:ns m)}))))

(defn- sub-input-signals
  "The sub's STATIC input topology — the sub-ids of its registered
  `:input-signals`, resolved by the SUB-ID (first element of the
  query-v) off `(rf/handler-meta :sub sub-id)` (rf2-87c8a).

  `:input-signals` is registered on the SUB-ID, not the full instance
  query-v: the `:<-` chain a `reg-sub` declares is the same for every
  parameterized instance (`[:sub-id arg…]` all share one registration).
  Keying the lookup by the sub-id is therefore the correct, deterministic
  source for the `inputs` column — present whether or not the sub re-ran
  inside a cascade this epoch.

  Returns a vector of input SUB-IDs (each input-signal's first element,
  e.g. `[[:chain-root]] → [:chain-root]`) so the inputs cell paints them
  as `mini` keywords. Returns nil when:
    - the sub-id can't be resolved (anonymous sub / no meta captured), or
    - `:input-signals` is empty — a genuine Level-1 app-db reader, where
      the cell falls back to the `app-db` source label.

  Pre-rf2-87c8a the inputs cell read the row's `:inputs` slot, which the
  projection sources purely from `:rf.sub/cause-sub` (the cascade
  attribution — which upstream sub's value-change drove THIS re-run).
  That tag is OMITTED outside an in-flight cascade, so any derived sub
  that ran fresh (e.g. the parameterized `[:standard-epochs/greater-than? 5]`
  on first mount) fell through to the `app-db` fallback and was mislabeled
  a Level-1 reader. The cascade attribution still surfaces — via the
  `caused by <event-id>` chrome (rf2-1cc03) — it is just no longer the
  source for the static-topology `inputs` column."
  [sub-id]
  (when (some? sub-id)
    (let [m (try (rf/handler-meta :sub sub-id) (catch :default _ nil))
          signals (:input-signals m)]
      (when (seq signals)
        (mapv (fn [sig] (if (vector? sig) (first sig) sig)) signals)))))

(defn- subscriptions-table
  "Render the SUBSCRIPTIONS table — 3 columns (sub / inputs / changed).
  Per the bead body's §SUBSCRIPTIONS (Step 7) shape (rf2-kfh1v).

  rf2-vv3m6 (2026-05-29) — the prior `[diff][full][full+diff]` mode
  toggle (rf2-yqjrd) is retired. The `changed` cell always renders
  under the single FULL+DIFF posture via `subs-value-cell`.

  rf2-uji72 — table mounts through the shared `rt/resizable-table`
  view; columns are user-draggable via the gutters between adjacent
  headers.

  rf2-zuh3p — when a row carries `:violations` (a `:sub-return`
  boundary failure attributed to that sub-id by the projection), the
  per-row violation sub-block renders INLINE via the resizable-table's
  `:row-extras` slot — directly below the row, before the next row
  begins. Parity with the sibling FX step's `fx-row-with-violations`."
  [rows]
  (let [columns [{:id :sub    :label "sub"    :default-flex "1fr"}
                 {:id :inputs :label "inputs" :default-flex "1fr"}
                 {:id :value  :label "value"  :default-flex "1fr"}]]
    [rt/resizable-table
     {:table-id        :rf.xray.epoch/subscriptions
      :container-attrs {:data-testid              "rf-xray-epoch-subscriptions-table"
                        :data-rf-xray-diff-mode   "full+diff"
                        :style                    subscriptions-table-style}
      :header-attrs    {:style table-header-row-style}
      :columns         columns
      :rows            rows
      :row-key         (fn [_ i] (str "sub-" i))
      :row-attrs       (fn [_row i] {:data-testid       (str "rf-xray-epoch-sub-row-" i)
                                     :data-sub-changed  (str (boolean (:changed? (nth rows i))))
                                     :style (if (< i (dec (count rows)))
                                              subs-row-style-with-border
                                              subs-row-style)})
      :row-cells
      (fn [{:keys [sub-id sub-vec inputs cause-event-id] :as row} i]
        [;; sub cell
         [:div {:data-rf-xray-resizable-col "sub"
                :style subs-cell-id-style}
          ;; rf2-8w8er — sub-vec renders through `mini` so the vector's
          ;; keywords paint magenta, scalars orange, etc. Sub-id-only
          ;; fallback keeps the keyword-token chrome via `mini` too.
          [:span {:style subs-cell-id-span-style}
           (if (vector? sub-vec)
             [ei/mini sub-vec 40]
             [ei/mini sub-id 40])
           ;; rf2-aesni — functional click-to-source via the shared
           ;; `coord-chip`, exact parity with the disposed-subs (~3167)
           ;; + views (~3311 / ~3380) rows. Pre-fix this was a bare
           ;; decorative `(icons/external-link)` glyph with no coord
           ;; resolution + no click handler — it never dispatched
           ;; `:rf.xray/open-in-editor`. The coord lookup keys off
           ;; `sub-id` (the keyword) even when `sub-vec` drives the
           ;; label, so a parameterized sub (`[:counter/greater-than? 5]`)
           ;; resolves its REGISTRATION coord. Chip drops out cleanly
           ;; when no coord was captured (anonymous sub / production
           ;; build without coords).
           (coord-chip/coord-chip (sub-coord sub-id)
                                  (str "rf-xray-epoch-sub-row-coord-" i))]
          ;; rf2-1cc03 — `caused by <event-id>` chrome surfaces the
          ;; dispatching cascade's event-id (the cascade whose handler-
          ;; body invalidated this sub's reactive input). OMITTED when
          ;; `:cause-event-id` is absent — a sub that ran outside any
          ;; in-flight cascade has no event attribution. Event-id
          ;; routes through `ei/mini` so the keyword picks up the same
          ;; magenta syntax-token chrome the sub-id above carries.
          (when (some? cause-event-id)
            [:div {:data-rf-xray-subs-cause-event-id (str cause-event-id)
                   :data-testid (str "rf-xray-epoch-sub-row-cause-event-id-" i)
                   :style subs-cell-cause-event-style}
             [:span "caused by"]
             [ei/mini cause-event-id 40]])]
         ;; inputs cell
         [:div {:data-rf-xray-resizable-col "inputs"
                :style subs-cell-inputs-style}
          ;; rf2-87c8a — the inputs column shows the sub's STATIC input
          ;; topology, resolved by the SUB-ID off `:input-signals`
          ;; (`sub-input-signals`), NOT the cascade attribution the row's
          ;; `:inputs` slot carries (that was nil outside a cascade →
          ;; "app-db" fallback, which mislabeled fresh-run derived /
          ;; parameterized subs as Level-1 readers).
          ;;
          ;; rf2-8w8er — each input keyword routes through `mini` so
          ;; the input column lights up as keywords, not plain text.
          ;; "app-db" stays as a label (it's a source descriptor, not
          ;; a CLJS value) — rendered only for a genuine Level-1 reader
          ;; whose `:input-signals` is empty.
          (let [input-ids (sub-input-signals sub-id)]
            (cond
              (seq input-ids)
              (into [:div {:style subs-inputs-list-style}]
                    (map (fn [i] [:div [ei/mini i 40]]) input-ids))
              ;; rf2-87c8a fallback: a runtime with no captured meta but
              ;; a cascade-attributed `:inputs` slot still paints that
              ;; upstream sub (preserves the pre-fix shape for traces
              ;; replayed against a frame where the sub isn't registered).
              (vector? inputs)
              (into [:div {:style subs-inputs-list-style}]
                    (map (fn [i] [:div [ei/mini i 40]]) inputs))
              (some? inputs) [ei/mini inputs 40]
              :else          "app-db"))]
         ;; value cell
         [:div {:data-rf-xray-resizable-col "value"
                :style subs-cell-changed-style}
          (subs-value-cell row i)]])
      ;; rf2-zuh3p — per-row violations attach inline as `:row-extras`
      ;; so the schema-violation sub-block renders directly below its
      ;; owning row (mirrors the FX step's `fx-row-with-violations`
      ;; shape). The step-level violations (non-row attributed) still
      ;; ride at the foot via the call-site below.
      :row-extras
      (fn [row _i]
        (when (seq (:violations row))
          (violation-blocks
            (keyword (str "sub-row-" (some-> row :sub-id name)))
            (:violations row))))}]))

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
  reason chip carries the dispose path.

  rf2-jnxfj — mounts through the shared `rt/resizable-table` so
  column widths are user-draggable + persist across reloads via
  the `:rf.xray.epoch/subscriptions-disposed` table-id slot."
  [rows]
  (let [columns [{:id :glyph    :label ""             :default-flex "24px"}
                 {:id :disposed :label "disposed sub" :default-flex "1.5fr"}
                 {:id :reason   :label "reason"       :default-flex "1fr"}]]
    [rt/resizable-table
     {:table-id        :rf.xray.epoch/subscriptions-disposed
      :container-attrs {:data-testid "rf-xray-epoch-subscriptions-disposed-table"
                        :style       disposed-subs-table-style}
      :header-attrs    {:style table-header-row-style}
      :columns         columns
      :rows            rows
      :row-key         (fn [_ i] (str "disposed-" i))
      :row-attrs       (fn [{:keys [reason]} i]
                         {:data-testid     (str "rf-xray-epoch-sub-disposed-row-" i)
                          :data-sub-reason (when reason (pr-str reason))
                          :style (if (< i (dec (count rows)))
                                   subs-row-style-with-border
                                   subs-row-style)})
      :row-cells
      (fn [{:keys [sub-id query reason]} i]
        [;; glyph cell
         [:div {:data-rf-xray-resizable-col "glyph"
                :style disposed-glyph-cell-style}
          ;; Eviction glyph — `✗` red/error tone conveys "removed from
          ;; the reactive graph" (rf2-wpfjo).
          "✗"]
         ;; disposed-sub cell
         [:div {:data-rf-xray-resizable-col "disposed"
                :style disposed-id-cell-style}
          [:span {:data-testid (str "rf-xray-epoch-sub-disposed-row-id-" i)
                  :style disposed-id-span-style}
           (cond
             (vector? query) [ei/mini query 40]
             (some? sub-id)  [ei/mini sub-id 40]
             :else           [:span {:style disposed-anonymous-style}
                              "<anonymous sub>"])
           ;; rf2-d2akf — click-to-source affordance for the reg-sub,
           ;; parity with the sibling unmounted-views row. Resolves
           ;; `(rf/handler-meta :sub sub-id)` → coord; chip drops out
           ;; cleanly when meta is absent (anonymous sub / production
           ;; build without coords).
           (coord-chip/coord-chip (sub-coord sub-id)
                                  (str "rf-xray-epoch-sub-disposed-row-coord-" i))]]
         ;; reason cell
         [:div {:data-rf-xray-resizable-col "reason"
                :data-testid (str "rf-xray-epoch-sub-disposed-row-reason-" i)
                :style disposed-reason-cell-style}
          (dispose-reason-label reason)]])}]))

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

  Frame-anchor pattern (rf2-p56sk / rf2-nesy9): the read pins to the
  SURROUNDING instance frame captured at render time, and the button-
  bar's click dispatches into that same captured frame — so toggle
  writes + reads hit THIS instance's Xray app-db (N isolated shells
  stay independent), not the `:rf/xray` singleton."
  [{:keys [rows disposed-rows step-number violations]}]
  (let [frame         (rf/current-frame)
        mode          @(rf/subscribe frame
                                     [:rf.xray.epoch/subs-filter-mode])
        visible-rows  (case mode
                        :all       rows
                        :unchanged (filterv (complement :changed?) rows)
                        ;; :changed (default) — also the fallback for
                        ;; an unknown mode keyword so the panel never
                        ;; renders an empty filter.
                        (filterv :changed? rows))
        n             (count rows)
        l             (count disposed-rows)]
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
        ;;
        ;; rf2-vv3m6 (2026-05-29) — the prior value-mode toggle
        ;; `[diff][full][full+diff]` (rf2-yqjrd) is retired; each
        ;; changed row's value cell renders unconditionally under
        ;; FULL+DIFF.
        :verb [:span {:style subs-verb-style}
               (when (pos? n)
                 (subs-filter-button-bar mode))
               (when (pos? l)
                 [:span {:style subs-disposed-count-style}
                  (str l " disposed")])]
        :expandable? false
        :testid "rf-xray-epoch-subscriptions"}
       nil)
     (when (pos? n)
       (subscriptions-table visible-rows))
     (when (pos? l)
       (disposed-subs-table disposed-rows))
     ;; rf2-xgeag · rf2-zuh3p — `:sub-return` boundary violations.
     ;; Per-row attachments now render INLINE with their matching sub
     ;; row via `subscriptions-table`'s `:row-extras` slot (no longer
     ;; pooled at the foot of the step). Step-level violations
     ;; (indirect recomputes that don't surface a row) continue to
     ;; ride at the foot.
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

(defn- render-cause-chip
  "Render the per-row render-cause attribution chip (rf2-bhi3t).

  A view re-renders for exactly one of two reasons — a SUBSCRIPTION it
  derefs changed value, or its PROPS changed (the orthogonal `:rf/props`
  channel). This chip answers that first debugging question inline:

    `{:kind :sub :sub-id <id>}`  →  `← :sub-id` (accent-tinted; the sub
                                    routes through `ei/mini` so it carries
                                    the same syntax-token chrome the subs
                                    column uses).
    `:props`                     →  `← props` (tertiary-tinted; muted).
    `:mount`                     →  nil (the cause question is about
                                    RE-renders; a first render has no
                                    re-render cause).

  `idx` keys the `data-testid` per row."
  [cause idx]
  (cond
    (and (map? cause) (= :sub (:kind cause)))
    [:span {:data-testid (str "rf-xray-epoch-view-row-cause-" idx)
            :data-rf-render-cause "sub"
            :style views-row-cause-sub-style}
     "← " [ei/mini (:sub-id cause) 60]]

    (= :props cause)
    [:span {:data-testid (str "rf-xray-epoch-view-row-cause-" idx)
            :data-rf-render-cause "props"
            :style views-row-cause-props-style}
     "← props"]

    :else nil))

(defn- views-table
  "Render the VIEWS table — 2 columns (views / subs). Per the bead
  body's §VIEWS (Step 8) shape (rf2-6djth).

  Each row carries:
    - view-id (hyperlinked via the registrar's `:view` meta coord)
    - the render cause (rf2-bhi3t — `← :sub-id` when a deref'd sub
      changed value, `← props` when a re-render had no own sub change;
      the orthogonal `:rf/props` channel)
    - duration (when stamped, rendered as a muted chip below the id)
    - the subs the view dereffed during this render (one per line —
      vectors via `pr-str`, scalars via `ns-keyword`).

  rf2-jnxfj — mounts through the shared `rt/resizable-table` so
  column widths are user-draggable + persist across reloads via
  the `:rf.xray.epoch/views` table-id slot."
  [rows]
  (let [columns [{:id :view :label "view" :default-flex "1fr"}
                 {:id :subs :label "subs" :default-flex "1fr"}]]
    [rt/resizable-table
     {:table-id        :rf.xray.epoch/views
      :container-attrs {:data-testid "rf-xray-epoch-views-table"
                        :style       views-table-style}
      :header-attrs    {:style table-header-row-style}
      :columns         columns
      :rows            rows
      :row-key         (fn [_ i] (str "view-" i))
      :row-attrs       (fn [{:keys [view-id]} i]
                         {:data-testid (str "rf-xray-epoch-view-row-" i)
                          :data-view-id (when view-id (pr-str view-id))
                          ;; rf2-2f962 — pink-stripe view-name hover
                          ;; affordance. Pure DOM side-effect on the
                          ;; row wrapper; no layout perturbation.
                          :on-mouse-enter (fn [_e] (apply-view-highlight! view-id))
                          :on-mouse-leave (fn [_e] (clear-view-highlight! view-id))
                          :style (if (< i (dec (count rows)))
                                   subs-row-style-with-border
                                   subs-row-style)})
      :row-cells
      (fn [{:keys [view-id subs-read duration-ms cause]} i]
        [;; view cell
         [:div {:data-rf-xray-resizable-col "view"
                :style views-cell-view-style}
          [:span {:data-testid (str "rf-xray-epoch-view-row-id-" i)
                  :style (if view-id
                           views-cell-id-clickable-style
                           views-cell-id-span-style)}
           ;; rf2-309cy — view-id keyword routes through `ei/mini` so
           ;; it carries the same syntax-token chrome the sibling subs-
           ;; read cell uses (rf2-8w8er intent). Pre-fix the cell rendered
           ;; via `proj/ns-keyword` → plain coloured text, an inconsistency
           ;; against the syntax-highlighted subs-read column to its right.
           (if (some? view-id)
             [ei/mini view-id 60]
             [:span {:style views-anonymous-style}
              "<anonymous view>"])
           (coord-chip/coord-chip (view-coord view-id)
                                  (str "rf-xray-epoch-view-row-coord-" i))]
          (render-cause-chip cause i)
          (when (number? duration-ms)
            [:span {:style views-row-duration-style}
             (proj/format-duration-ms duration-ms)])]
         ;; subs cell
         [:div {:data-rf-xray-resizable-col "subs"
                :data-testid (str "rf-xray-epoch-view-row-subs-" i)
                :style views-cell-subs-style}
          ;; rf2-8w8er — each sub-id renders through `mini` so the
          ;; column reads as syntax-highlighted tokens.
          (cond
            (and (sequential? subs-read) (seq subs-read))
            (into [:div {:style views-subs-list-style}]
                  (for [s subs-read]
                    [:div [ei/mini s 60]]))
            (some? subs-read)
            [ei/mini subs-read 60]
            :else
            [:span {:style italic-style} "(none)"])]])}]))

(defn- unmounted-views-table
  "Render the UNMOUNTED VIEWS sub-section (rf2-gmw1i) — one row per
  `:rf.view/unmounted` trace event. Visually distinct from the
  re-render rows: a red/error glyph conveys the teardown semantic.
  Click-to-source uses the same `(rf/handler-meta :view view-id)`
  resolver the re-render rows use, so the operator can jump to the
  view's definition even when the instance is gone.

  rf2-jnxfj — mounts through the shared `rt/resizable-table` so
  column widths are user-draggable + persist across reloads via
  the `:rf.xray.epoch/views-unmounted` table-id slot."
  [rows]
  (let [columns [{:id :glyph     :label ""               :default-flex "24px"}
                 {:id :unmounted :label "unmounted view" :default-flex "1fr"}]]
    [rt/resizable-table
     {:table-id        :rf.xray.epoch/views-unmounted
      :container-attrs {:data-testid "rf-xray-epoch-views-unmounted-table"
                        :style       unmounted-views-table-style}
      :header-attrs    {:style table-header-row-style}
      :columns         columns
      :rows            rows
      :row-key         (fn [_ i] (str "unmounted-" i))
      :row-attrs       (fn [{:keys [view-id]} i]
                         {:data-testid  (str "rf-xray-epoch-view-unmounted-row-" i)
                          :data-view-id (when view-id (pr-str view-id))
                          :style (if (< i (dec (count rows)))
                                   subs-row-style-with-border
                                   subs-row-style)})
      :row-cells
      (fn [{:keys [view-id]} i]
        [;; glyph cell
         [:div {:data-rf-xray-resizable-col "glyph"
                :style unmounted-glyph-cell-style}
          ;; Teardown glyph — `✗` red/error tone conveys the view
          ;; came off the reactive graph (rf2-gmw1i).
          "✗"]
         ;; unmounted-view cell
         [:div {:data-rf-xray-resizable-col "unmounted"
                :style unmounted-id-cell-style}
          [:span {:data-testid (str "rf-xray-epoch-view-unmounted-row-id-" i)
                  :style unmounted-id-span-style}
           ;; rf2-309cy — view-id keyword routes through `ei/mini` so
           ;; the UNMOUNTED row matches the re-render row's chrome (same
           ;; data-shape, same syntax-highlighted token rendering).
           (if (some? view-id)
             [ei/mini view-id 60]
             [:span {:style disposed-anonymous-style}
              "<anonymous view>"])
           (coord-chip/coord-chip (view-coord view-id)
                                  (str "rf-xray-epoch-view-unmounted-row-coord-" i))]]])}]))

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

;; `violation-open-source-action` retired (rf2-wnvid) — its only caller
;; was the error-card's jump-to-source link, which rf2-wnvid dropped as
;; redundant with the HANDLER step's verb link. The schema-violation
;; block's `schema check` link routes through `coord-link` directly.

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
  it degrades to plain inline text so the sentence stays readable.

  rf2-vw5pi — routes through the shared `coord-link` with `:glyph?
  false` (this is a pure inline TEXT link inside a sentence — no
  trailing `external-link` glyph)."
  [{:keys [label coord testid]}]
  (coord-link/coord-link coord label testid
                         {:style  violation-inline-link-style
                          :glyph? false}))

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

(defn decode-malli-explain
  "Decompose a Malli `explain` map into the at-a-glance summary the
  bead body's §SCHEMA VIOLATION section asks for (rf2-zn6u5).

  Returns `{:expected <schema-form> :got <value> :more-errors <int>}`
  when `explain` carries the canonical Malli shape — `{:errors [{...}
  ...] :value <root>}`. Returns `nil` otherwise (non-Malli validators,
  pre-rf2-2ek7t framework, malformed input) so the caller can drop
  the decomposition row cleanly.

  - `:expected` reads the FIRST error's `:schema` slot (the schema
    form that failed at the deepest path Malli reached).
  - `:got` reads the first error's `:value` when present; falls back
    to the explain map's root `:value`.
  - `:more-errors` is `(count errors) - 1` so the call-site can paint
    a `(+N more)` chip when more than one error rode in.

  Pure data; JVM-testable."
  [explain]
  (when (map? explain)
    (let [errors (:errors explain)]
      (when (and (sequential? errors) (seq errors))
        (let [first-err (first errors)]
          {:expected    (:schema first-err)
           :got         (if (contains? first-err :value)
                          (:value first-err)
                          (:value explain))
           :more-errors (max 0 (dec (count errors)))})))))

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
        decoded         (decode-malli-explain explain)
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
     ;; 3. Expected / Got decomposition (rf2-zn6u5) — when the row's
     ;; `:explain` carries the canonical Malli shape, surface the
     ;; first error's `:schema` (expected) + `:value` (got) as
     ;; programmer-friendly summary lines ABOVE the full humanized
     ;; explain map. Multi-error explain maps gain a `(+N more)` chip
     ;; so the operator sees the first-error-prominent summary the
     ;; rf2-xgeag bead body designed. Drops out cleanly when
     ;; `decode-malli-explain` returns nil (non-Malli validator).
     (when decoded
       [:div {:data-testid (str testid-base "-decoded")
              :style schema-violation-explain-body-style}
        [:div {:style schema-violation-line-style}
         [:span {:style schema-violation-line-label-style}
          "expected:"]
         [:span {:data-testid (str testid-base "-expected")
                 :style schema-violation-line-value-style}
          [ei/mini (:expected decoded) 80]]]
        [:div {:style schema-violation-line-style}
         [:span {:style schema-violation-line-label-style}
          "got:"]
         [:span {:data-testid (str testid-base "-got")
                 :style schema-violation-line-value-style}
          [ei/mini (:got decoded) 80]]]
        (when (pos? (:more-errors decoded))
          [:div {:data-testid (str testid-base "-more-errors")
                 :style schema-violation-sensitive-style}
           (str "(+" (:more-errors decoded) " more error"
                (when (> (:more-errors decoded) 1) "s") ")")])])
     ;; 4. Humanized explain map (or raw fallback) — render via
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

;; ---- inline EXCEPTION card (rf2-ahhgn) ----------------------------------

(defn- error-recovery-label
  "Short recovery chip text for an exception card (rf2-ahhgn / rf2-wnvid /
  rf2-s6oqd).

  rf2-s6oqd — `db-rolled-back?` gates the `Rolled back` chip: it paints
  ONLY when the cascade ACTUALLY rolled back (a `:where :app-db`
  schema-validation failure reverted the commit). The substrate stamps
  `:recovery :no-recovery` on EVERY `:rf.error/*`, so keying off recovery
  alone painted a SPURIOUS `Rolled back`. The earlier rf2-wnvid gate
  (`db-committed?`) fixed the pre-commit handler throw (button-16 — no
  commit) but still mis-fired on a POST-COMMIT fx throw (button-20
  `:standard-epochs/boom`): fx are best-effort post-commit, so the `:db`
  stays committed yet nothing reverted. Gating on actual rollback paints
  the chip on a :db schema-fail rollback (correct) and omits it on a
  post-commit fx throw and a pre-commit handler throw (the headline
  already names the failure; there is no rollback to surface). Other
  recovery keywords render name-d; nil recovery omits the chip."
  [recovery db-rolled-back?]
  (case recovery
    :no-recovery (when db-rolled-back? "Rolled back")
    (when (keyword? recovery) (name recovery))))

;; rf2-oqi0c — `error-block-label` (the one-line category-reason headline
;; 'The event handler threw.' / '…interceptor threw.') was REMOVED. The
;; card's position (under the failing step) + its 'Exception Thrown'
;; heading already attribute the failure; the headline merely restated
;; the category as boilerplate chrome. The card now leads with the real
;; `.getMessage` (when present) + the collapsible stack / ex-data.

(defn- exception-stack
  "Lift the stack trace string off a thrown exception object (rf2-wnvid).
  CLJS errors carry `.-stack` (the panel runs in the browser). nil-safe —
  returns nil for a non-error / stack-less exception."
  [exception]
  (when (some? exception)
    (let [s (try (.-stack ^js exception)
                 (catch :default _ nil))]
      (when (and (string? s) (not (str/blank? s))) s))))

(defn- exception-ex-data
  "Lift the `ex-data` map off a thrown exception object (rf2-wnvid).
  nil-safe — returns nil when the exception carries no ex-data (a bare
  `(throw (js/Error. …))` rather than an `ex-info`)."
  [exception]
  (when (some? exception)
    (let [d (try (ex-data exception) (catch :default _ nil))]
      (when (seq d) d))))

(defn- error-block-details
  "Render the collapsible EXCEPTION details (rf2-wnvid) — a native
  `<details>` disclosure carrying the stack trace + any `ex-data`,
  collapsed by default. Replaces the pre-rf2-wnvid always-expanded
  source link (which was REDUNDANT — the HANDLER step's verb already
  jumps to the handler's reg-site, and on a handler throw the error
  card's own coord was usually nil → a useless 'source unavailable'
  link). The depth lives behind one click; the common read is the
  headline + message above. Returns nil when neither a stack nor
  ex-data is available (nothing to disclose)."
  [testid-base {:keys [exception]}]
  (let [stack   (exception-stack exception)
        ex-data* (exception-ex-data exception)]
    (when (or stack (seq ex-data*))
      [:details {:data-testid (str testid-base "-details")
                 :style error-block-details-style}
       [:summary {:data-testid (str testid-base "-details-summary")
                  :style error-block-summary-style}
        "Details"]
       (when (seq ex-data*)
         [:div {:data-testid (str testid-base "-ex-data")}
          [:div {:style error-block-data-label-style} "ex-data"]
          [ei/edn-inspector ex-data*
           {:site-id [:rf.xray.epoch/error-ex-data testid-base]
            :card?   false
            :default-expanded-depth 1}]])
       (when stack
         [:div {:data-testid (str testid-base "-stack")}
          [:div {:style error-block-data-label-style} "stack"]
          [:pre {:style error-block-stack-style} stack]])])))

(defn error-block
  "Render one inline EXCEPTION card (rf2-ahhgn / rf2-wnvid) for a
  projected `exception-row`. Top to bottom:

    1. Title bar: `✗` + 'Exception Thrown' + right-aligned recovery chip
       (`Rolled back`, painted ONLY when the cascade ACTUALLY rolled back
       — `db-rolled-back?`; rf2-s6oqd drops the spurious chip on a
       post-commit fx throw, rf2-wnvid on a pre-commit handler throw).
    2. Exception message: the verbatim `ex-info` message (monospace).
       rf2-oqi0c — the category-reason boilerplate headline ('The event
       handler threw.' / '…interceptor threw.') was DROPPED as redundant
       with the card position + 'Exception Thrown' heading.
    3. Collapsible details (`error-block-details`): the stack trace +
       any `ex-data`, collapsed behind a `<details>` disclosure.

  rf2-wnvid — the pre-existing always-expanded jump-to-source link is
  DROPPED: it duplicated the HANDLER step's verb link (the canonical
  jump-to-source) and, on a handler throw where the trace carried no
  coord, degraded to a useless 'source unavailable'. The depth that
  matters (stack / ex-data) now rides the collapsible.

  `step-key` + `idx` give stable test ids. The card paints the failing
  step's blast radius right where the work happened — the inline half of
  rf2-ahhgn, polished by rf2-wnvid for ALL exception kinds."
  [step-key idx {:keys [message recovery db-rolled-back?] :as row}]
  (let [recovery-label (error-recovery-label recovery db-rolled-back?)
        testid-base    (str "rf-xray-epoch-error-"
                            (name (or step-key :unknown)) "-" idx)]
    [:div {:key (str "error-" step-key "-" idx)
           :data-testid testid-base
           :data-error-op (when (:operation row) (name (:operation row)))
           :style error-block-style}
     ;; 1. Title bar — ✗ + 'Exception Thrown' + recovery chip
     [:div {:style error-block-title-style}
      [:span {:aria-hidden true} "✗"]
      [:span {:data-testid (str testid-base "-title")} "Exception Thrown"]
      [:span {:style schema-violation-title-spacer-style}]
      (when recovery-label
        [:span {:data-testid (str testid-base "-recovery")
                :style error-block-recovery-chip-style}
         recovery-label])]
     ;; rf2-oqi0c — the category-reason boilerplate headline ('The event
     ;; handler threw.' / '…interceptor threw.') is DROPPED: redundant with
     ;; the card's position (under the failing step) + the 'Exception
     ;; Thrown' heading. The real `.getMessage` + ex-data carry the signal.
     ;; 2. Exception message (verbatim, monospace) — the punchline
     (when (and (string? message) (not (str/blank? message)))
       [:div {:data-testid (str testid-base "-message")
              :style error-block-message-style}
        message])
     ;; 3. Collapsible details — stack + ex-data behind a disclosure
     (error-block-details testid-base row)]))

(defn error-blocks
  "Render every exception in `errors` as an inline card inside the
  current step's body (rf2-ahhgn). `step-key` is the owning step keyword
  (stable test ids). nil-safe — a clean step passes nil/empty and renders
  nothing."
  [step-key errors]
  (when (seq errors)
    [:div {:data-testid (str "rf-xray-epoch-errors-" (name step-key))}
     (map-indexed (fn [i e] (error-block step-key i e))
                  errors)]))

;; `rolled-back-banner` retired per rf2-w8evg — the rf2-8resu
;; redesign moved the `:where :app-db` violation from HANDLER to the
;; FX step's `:db` row, so the standalone HANDLER-level "cascade
;; rolled back — downstream effects skipped" banner has no caller.
;; The downstream-mute chrome (`rolled-back-mute-style`) still
;; applies in `render-pipeline-steps`.

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
  ;; rf2-nesy9 — render-time frame capture for the deferred jump click.
  (let [frame          (rf/current-frame)
        child-epoch-id (proj/find-child-epoch epoch-history dispatch-id event)]
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
                                          {:frame frame}))
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
    :dispatch          (render-dispatch-step step (:dispatch-id->epoch-id ctx))
    :coeffect          (render-coeffect-step step)
    :interceptor       (render-interceptor-step step)
    :handler           (render-handler-step step)
    :flow              (render-flow-step step)
    :side-effects      (render-side-effects-step step)
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

;; rf2-wnvid — the top-of-cascade outcome banner ("This event failed —
;; see the ✗ step below.") is RETIRED (Mike pair-debug 2026-05-31). It
;; was redundant: the failure surfaces inline in the cascade — the
;; failing step's inline 'Exception Thrown' card sits right under the
;; step (the per-stage ✗ glyph itself retired in rf2-9wq0v). The banner
;; restated what the cascade already shows, pushing the actual content
;; down. The panel root still stamps `data-rf-xray-outcome` (tools / e2e
;; read the tool-side outcome there); the banner element + its style are
;; gone.

;; ---- public Panel --------------------------------------------------------

(rf/reg-view Panel
  "Epoch panel root view. Subscribes to `:rf.xray/epoch-pipeline` —
  a composite that resolves the focused epoch off the spine and
  projects its `:trace-events` into the pipeline-step rows. Renders
  the numbered cascade when steps are present; an empty-state when
  the focus carries no record or the record carries no trace events.

  rf2-ahhgn / rf2-wnvid / rf2-9wq0v — when the cascade failed
  (`:outcome :error`) the failure surfaces INLINE: the failing step's
  'Exception Thrown' card sits under it (the per-stage ✗ glyph retired
  in rf2-9wq0v). The panel root stamps `data-rf-xray-outcome` for tools /
  e2e; the pre-rf2-wnvid top banner is retired (it merely restated the
  inline signal)."
  []
  (let [{:keys [status steps dispatch-id epoch-history outcome]}
        @(rf/subscribe [:rf.xray/epoch-pipeline])]
    [:section {:data-testid "rf-xray-epoch-panel"
               :data-rf-xray-outcome (when outcome (name outcome))
               :style panel-root-style}
     [:div {:style panel-scroll-style}
      (cond
        (= :focused status)
        (if (seq steps)
          [:<>
           ;; rf2-x25e0 — build the `{dispatch-id → epoch-id}` index
           ;; once per panel render. Threaded through `ctx` to the
           ;; DISPATCH step's `:fx-dispatch` / `:fx-dispatch-later`
           ;; parent-epoch resolver (O(1) lookup instead of an O(N)
           ;; epoch-history scan per render).
           (pipeline-view steps
                          {:dispatch-id           dispatch-id
                           :epoch-history         epoch-history
                           :dispatch-id->epoch-id (proj/dispatch-id->epoch-id-index
                                                    epoch-history)})]
          (empty-state-view :no-events))

        :else
        (empty-state-view (or status :no-focus)))]]))
