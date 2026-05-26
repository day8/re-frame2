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
            [day8.re-frame2-xray.views.edn-widget.widget :as edn]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

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

;; ---- chrome helpers ------------------------------------------------------

(defn- badge-pill
  "Render a step's badge pill — uppercase 10px label inside a
  rounded-corners chip painted in the badge's colour.

  Per the bead body's §Numbered Cascade Pattern step 2:

      Badge pill: uppercase text, 10px font (devtools-micro),
                  rounded, padding 5px horizontal, 3px vertical"
  [step-badge]
  [:span {:data-testid (str "rf-xray-epoch-badge-"
                            (str/lower-case (name step-badge)))
          :style {:display          "inline-flex"
                  :align-items      "center"
                  :background       (badge/colour step-badge)
                  :color            (:white tokens)
                  :font-family      sans-stack
                  :font-size        "10px"
                  :font-weight      700
                  :letter-spacing   "0.5px"
                  :padding          "3px 5px"
                  :border-radius    "3px"
                  :line-height      1
                  :text-transform   "uppercase"
                  :white-space      "nowrap"}}
   (badge/label step-badge)])

(defn- numbered-circle
  "Render the numbered circle — 21px diameter, painted in the step's
  badge colour with white numerals. Positioned absolutely at -44px
  from the content column's left edge per the bead body's §Numbered
  Cascade Pattern step 1."
  [step-number step-badge]
  [:span {:data-testid (str "rf-xray-epoch-circle-" step-number)
          :aria-label  (str "step " step-number " (" (name step-badge) ")")
          :style {:position         "absolute"
                  :left             "-44px"
                  :top              0
                  :width            "21px"
                  :height           "21px"
                  :border-radius    "50%"
                  :background       (badge/colour step-badge)
                  :color            (:white tokens)
                  :display          "inline-flex"
                  :align-items      "center"
                  :justify-content  "center"
                  :font-family      mono-stack
                  :font-size        "11px"
                  :font-weight      700
                  :line-height      1
                  :z-index          1}}
   (str step-number)])

(defn- duration-chip
  "Right-aligned duration chip rendered alongside a step's header.
  Returns nil for non-number durations so the view can elide the
  slot when the substrate didn't stamp one."
  [duration-ms]
  (when (number? duration-ms)
    [:span {:data-testid "rf-xray-epoch-duration"
            :style {:color       (:text-tertiary tokens)
                    :font-family mono-stack
                    :font-size   "11px"
                    :font-weight 500
                    :white-space "nowrap"
                    :margin-left "auto"
                    :padding-left "8px"}}
     (proj/format-duration-ms duration-ms)]))

(defn- coord-chip
  "External-link affordance — opens the editor at `coord` via the
  cross-panel `:rf.xray/open-in-editor` event. Returns nil when
  `coord` has no `:file`."
  [coord testid]
  (when (and (map? coord) (seq (:file coord)))
    [:button {:data-testid testid
              :aria-label  "open in editor"
              :title       "open in editor"
              :on-click    (fn [e]
                             (.stopPropagation e)
                             (rf/dispatch [:rf.xray/open-in-editor
                                           {:source-coord coord}]
                                          {:frame :rf/xray}))
              :style       {:background      "transparent"
                            :color           "inherit"
                            :border          "none"
                            :padding         "0 4px"
                            :margin-left     "4px"
                            :cursor          "pointer"
                            :display         "inline-flex"
                            :align-items     "center"
                            :line-height     1}}
     (icons/external-link)]))

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
         :style {:display      "flex"
                 :align-items  "center"
                 :gap          "8px"
                 :cursor       (if expandable? "pointer" "default")
                 :font-family  sans-stack
                 :font-size    "12px"
                 :color        (:text-primary tokens)
                 :user-select  "none"}}
   (badge-pill badge)
   [:span {:data-testid (str testid "-verb")
           :style {:display      "inline-flex"
                   :align-items  "center"
                   :gap          "4px"
                   :font-family  mono-stack
                   :font-size    "12px"
                   :color        (:text-primary tokens)
                   :min-width    0
                   :flex         "1 1 auto"
                   :overflow     "hidden"
                   :text-overflow "ellipsis"
                   :white-space  "nowrap"}}
    verb]
   (when expandable?
     [:span {:data-testid (str testid "-expand-glyph")
             :aria-hidden true
             :style {:color       (:text-tertiary tokens)
                     :font-family mono-stack
                     :font-size   "10px"
                     :margin-left "4px"}}
      (if expanded? "▾" "▸")])
   (duration-chip duration-ms)])

(defn- sub-header
  "Render a sub-section header (`↳ :db diff` / `↳ :fx` / `↳ guards`)
  under a step's body — corner-down-right glyph + label + optional
  trailing count."
  ([label]
   (sub-header label nil))
  ([label trailing]
   [:div {:style {:display      "flex"
                  :align-items  "center"
                  :gap          "6px"
                  :margin       "8px 0 5px 0"
                  :font-family  sans-stack
                  :font-size    "11px"
                  :font-weight  600
                  :color        (:text-tertiary tokens)
                  :text-transform "uppercase"
                  :letter-spacing "0.5px"}}
    [:span {:style {:display "inline-flex" :color (:text-tertiary tokens)}}
     (icons/corner-down-right)]
    [:span label]
    (when trailing
      [:span {:style {:color (:text-tertiary tokens)
                      :font-weight 400
                      :font-family mono-stack
                      :text-transform "none"}}
       trailing])]))

;; ---- DISPATCH step -------------------------------------------------------

(defn dispatch-body
  "Render the DISPATCH step's expanded body — the event vector as a
  boxed monospace block. Per the bead body's §DISPATCH (Step 1).

  Per rf2-9jvx1 the body no longer repeats the `from <source>` line —
  the header already carries that descriptor; the body is detail-only.
  The click-to-source affordance rides on the header (rf2-93a7s)."
  [{:keys [event]}]
  (when (vector? event)
    [:div {:data-testid "rf-xray-epoch-dispatch-event"
           :style {:font-family   mono-stack
                   :font-size     "12px"
                   :color         (:text-primary tokens)
                   :background    (:bg-3 tokens)
                   :border        (str "1px solid " (:border-subtle tokens))
                   :border-radius "3px"
                   :padding       "5px 8px"
                   :margin-top    "5px"
                   :overflow-x    "auto"}}
     (proj/event-display event)]))

(defn render-dispatch-step
  "Render the DISPATCH step (always present). Header summarises `from
  <source>` with the call-site chip when a coord was captured;
  body renders the dispatched event vector as a boxed monospace
  block (rf2-93a7s · rf2-9jvx1)."
  [{:keys [source coord duration-ms step-number] :as step}]
  [:div {:data-testid "rf-xray-epoch-step-dispatch"
         :data-step-kw "dispatch"}
   (numbered-circle step-number :DISPATCH)
   (step-header
     {:step :dispatch
      :badge :DISPATCH
      :verb [:span {:style {:display "inline-flex" :align-items "center" :gap "4px"}}
             "from "
             [:span {:style {:color (:accent tokens)}}
              (if source (name source) "unknown")]
             (coord-chip coord "rf-xray-epoch-dispatch-coord")]
      :expandable? false
      :testid "rf-xray-epoch-dispatch"
      :duration-ms duration-ms}
     nil)
   (dispatch-body step)])

;; ---- COEFFECT step -------------------------------------------------------

(defn- coeffect-row-view
  "Render one COEFFECT row (id link + labelled value via edn-inspector)
  per the bead body's §COEFFECT shape (rf2-cq0ch).

  The injected value renders via the canonical edn-inspector widget —
  scalars one-line through `edn/inspect-inline`; nested structures get
  the labelled cofx-id header so the row reads `:rf/now <inst>` /
  `:session {:user-id 42}` rather than the legacy cryptic
  `+[]<value>` diff-row.

  Argument order matches `map-indexed`'s `(f idx item)` calling
  convention; the pre-rf2-cq0ch shape transposed these and silently
  destructured a number as the row map (`_row` was the index, `idx`
  the row map — hence the legacy `+[]nil` symptom)."
  [idx {:keys [id value] :as _row}]
  [:div {:key (str "cofx-" idx)
         :data-testid (str "rf-xray-epoch-coeffect-row-" idx)
         :style {:padding "3px 0"
                 :display "flex"
                 :align-items "flex-start"
                 :gap "8px"
                 :font-family mono-stack
                 :font-size "12px"}}
   ;; id label
   [:span {:data-testid (str "rf-xray-epoch-coeffect-row-id-" idx)
           :style {:color (:accent tokens)
                   :white-space "nowrap"
                   :display "inline-flex"
                   :align-items "center"
                   :gap "4px"}}
    (proj/ns-keyword id)
    (icons/external-link)]
   ;; injected value (labelled — no cryptic `+[]nil` line)
   [:span {:data-testid (str "rf-xray-epoch-coeffect-row-value-" idx)
           :style {:color (:text-primary tokens)
                   :min-width 0
                   :flex 1
                   :word-break "break-word"}}
    (edn/inspect-inline value)]])

(defn render-coeffect-step
  "Render the COEFFECT step (single step with N rows — one per
  user-injected coeffect). Always conditional — present only when the
  projection emitted the step."
  [{:keys [rows step-number]}]
  [:div {:data-testid "rf-xray-epoch-step-coeffect"
         :data-step-kw "coeffect"}
   (numbered-circle step-number :COEFFECT)
   (step-header
     {:step :coeffect
      :badge :COEFFECT
      :verb (str (count rows) " coeffect"
                 (when (not= 1 (count rows)) "s") " injected")
      :expandable? false
      :testid "rf-xray-epoch-coeffect"}
     nil)
   [:div {:style {:margin-top "5px"}}
    (map-indexed coeffect-row-view rows)]])

;; ---- HANDLER step --------------------------------------------------------

(defn- db-diff-line
  "Render one db-diff entry (`~ [path] before → after` /
  `+ [path] value` / `- [path]`)."
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
                       "+" (:success tokens)
                       "-" (:error tokens)
                       (:warning tokens))]
    [:div {:key (str "diff-" idx)
           :data-testid (str "rf-xray-epoch-handler-diff-row-" idx)
           :style {:display      "flex"
                   :align-items  "flex-start"
                   :gap          "8px"
                   :padding      "2px 0"
                   :font-family  mono-stack
                   :font-size    "12px"}}
     [:span {:style {:color glyph-colour :font-weight 700}} glyph]
     [:span {:style {:color (:text-tertiary tokens) :white-space "nowrap"}}
      (proj/path-display path)]
     (when (= "~" glyph)
       [:<>
        [:span {:style {:color (:error tokens)}}
         (proj/truncate (pr-str before) 40)]
        [:span {:style {:color (:text-tertiary tokens)}} "→"]
        [:span {:style {:color (:success tokens)}}
         (proj/truncate (pr-str after) 40)]])
     (when (= "+" glyph)
       [:span {:style {:color (:success tokens) :min-width 0 :flex 1
                       :word-break "break-word"}}
        (proj/truncate (pr-str after) 80)])]))

(defn- fx-entry-line
  "Render one fx entry inside the HANDLER step's :fx sub-block."
  [{:keys [fx-id value]} idx]
  [:div {:key (str "fx-entry-" idx)
         :data-testid (str "rf-xray-epoch-handler-fx-row-" idx)
         :style {:display      "flex"
                 :align-items  "flex-start"
                 :gap          "8px"
                 :padding      "2px 0 2px 21px"
                 :font-family  mono-stack
                 :font-size    "12px"}}
   [:span {:style {:color (:accent tokens) :white-space "nowrap"}}
    (proj/ns-keyword fx-id)]
   [:span {:style {:color (:text-primary tokens) :min-width 0 :flex 1
                   :word-break "break-word"}}
    (proj/truncate (pr-str value) 80)]])

(defn- machine-lifecycle-block
  "Render the per-phase lifecycle rows for a machine handler. Empty
  phases render dimmed `(none)` so the model reads as the full
  exit → transition → entry shape even when one phase carries no
  actions (the panel doubles as a teaching surface — bead body §What
  this panel teaches)."
  [lifecycle-rows]
  (let [grouped (proj/group-lifecycle-by-phase lifecycle-rows)
        phases  [:exit :transition :entry :always
                 :after-action :initial-entry :destroy-exit]]
    [:div {:data-testid "rf-xray-epoch-handler-machine-lifecycle"
           :style {:margin-top "5px"}}
     (sub-header "lifecycle"
                 (str (count lifecycle-rows) " action"
                      (when (not= 1 (count lifecycle-rows)) "s")))
     (for [phase phases
           :let [rows (get grouped phase)]
           :when (seq rows)]
       [:div {:key (str "phase-" (name phase))
              :data-testid (str "rf-xray-epoch-handler-phase-" (name phase))
              :style {:padding "2px 0 2px 21px"
                      :font-family mono-stack
                      :font-size "12px"}}
        [:div {:style {:color (:text-tertiary tokens)
                       :font-size "10px"
                       :text-transform "uppercase"
                       :letter-spacing "0.5px"
                       :margin-bottom "2px"}}
         (proj/phase-label phase)]
        (for [[i row] (map-indexed vector rows)]
          [:div {:key (str "lc-" i)
                 :style {:display "flex"
                         :gap "8px"
                         :padding "1px 0"
                         :color (if (:threw? row) (:error tokens)
                                    (:text-primary tokens))}}
           [:span {:style {:color (:text-tertiary tokens)}} "↓"]
           [:span (proj/ns-keyword (:action-id row))]
           (when (:threw? row)
             [:span {:style {:color (:error tokens) :margin-left "8px"}}
              "(threw)"])])])]))

(defn- machine-block
  "Render the machine-handler-specific extras (transition summary,
  guards, lifecycle, timer cancellations). Per the bead body §HANDLER
  (Step 4) machine handler branch — uses the rf2-82a0u trace
  enhancements (phase + outcome + reason)."
  [{:keys [transition guards lifecycle timers]}]
  [:div {:data-testid "rf-xray-epoch-handler-machine"}
   ;; Transition summary
   (when transition
     [:div {:style {:padding "3px 0 3px 16px"
                    :font-family mono-stack
                    :font-size "12px"
                    :color (:text-primary tokens)}}
      [:div {:style {:color (:text-tertiary tokens)
                     :font-size "10px"
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"}}
       (str "transition · " (proj/ns-keyword (:machine-id transition)))]
      [:div {:style {:padding-left "16px"}}
       (let [before (or (some-> (:before transition) :state) (:before transition))
             after  (or (some-> (:after transition) :state) (:after transition))]
         (str (pr-str before) " → " (pr-str after)))]])
   ;; Guards
   (when (seq guards)
     [:div {:style {:padding "3px 0 3px 16px"}}
      (sub-header "guards" (str (count guards) " evaluated"))
      (for [[i {:keys [guard-id outcome]}] (map-indexed vector guards)]
        [:div {:key (str "g-" i)
               :style {:display "flex"
                       :gap "8px"
                       :padding "1px 0 1px 21px"
                       :font-family mono-stack
                       :font-size "12px"}}
         [:span {:style {:color (case outcome
                                  :pass (:success tokens)
                                  :fail (:warning tokens)
                                  :threw (:error tokens)
                                  (:text-tertiary tokens))
                         :font-weight 700}}
          (case outcome
            :pass  "✓"
            :fail  "✗"
            :threw "!"
            "·")]
         [:span (proj/ns-keyword guard-id)]
         [:span {:style {:color (:text-tertiary tokens) :margin-left "8px"}}
          (when (keyword? outcome) (name outcome))]])])
   ;; Lifecycle
   (when (seq lifecycle)
     (machine-lifecycle-block lifecycle))
   ;; Timers
   (when (seq timers)
     [:div {:style {:padding "3px 0 3px 16px"}}
      (sub-header "after-timers" (str (count timers) " cancelled"))
      (for [[i {:keys [state delay reason]}] (map-indexed vector timers)]
        [:div {:key (str "t-" i)
               :style {:display "flex"
                       :gap "8px"
                       :padding "1px 0 1px 21px"
                       :font-family mono-stack
                       :font-size "12px"}}
         [:span {:style {:color (:warning tokens)}} "✗"]
         [:span (str (pr-str state))]
         (when (number? delay)
           [:span {:style {:color (:text-tertiary tokens)}}
            (str delay "ms")])
         [:span {:style {:color (:text-tertiary tokens) :font-style "italic"
                         :margin-left "8px"}}
          (proj/timer-reason-label reason)]])])])

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

(defn- handler-source-block
  "Render the source-code block under the HANDLER header. Three
  cases:

    1. Machine handler — render the machine spec via the canonical
       `edn/inspect` widget.
    2. Event handler with a captured source string — render via
       `edn/code-block` (clojure-syntax highlight).
    3. Otherwise — render a clear `<source not yet captured>`
       placeholder so the slot is always present (operator learns
       where to look + when the substrate didn't stamp)."
  [flavour event-id]
  (let [machine? (= :reg-machine flavour)
        meta     (when (some? event-id)
                   (try (rf/handler-meta (if machine? :machine :event) event-id)
                        (catch :default _ nil)))
        spec     (when machine? (machine-spec-value meta))
        src      (when-not machine? (handler-source-string meta))]
    [:div {:data-testid "rf-xray-epoch-handler-source"
           :style {:margin-top "8px"
                   :min-width  "0"}}
     (sub-header (if machine? "machine spec" "source"))
     (cond
       (and machine? (some? spec))
       [:div {:data-testid "rf-xray-epoch-handler-source-spec"
              :style {:padding-left "16px"}}
        (edn/inspect spec)]

       src
       (edn/code-block
         {:source src
          :lang   :clojure
          :testid "rf-xray-epoch-handler-source-body"})

       :else
       [:span {:data-testid "rf-xray-epoch-handler-source-placeholder"
               :style {:font-style "italic"
                       :font-family mono-stack
                       :font-size   "11px"
                       :color       (:text-tertiary tokens)
                       :padding-left "16px"}}
        "<source not yet captured>"])]))

(defn handler-body
  "Render the HANDLER step's body — source block + db-diff + fx + the
  machine block when the handler is a machine-event-handler.

  Per rf2-9jvx1 the flavour + event-id row is dropped from the body —
  the header already carries that descriptor. Per rf2-66wis the body
  now leads with the handler's source code (or machine spec) so the
  operator can answer 'why did this handler do X' without leaving the
  panel."
  [{:keys [flavour event-id db-diff fx machine] :as _row}]
  [:div {:data-testid "rf-xray-epoch-handler-body"}
   ;; Source / machine spec block — rf2-66wis
   (handler-source-block flavour event-id)
   ;; Machine extras BEFORE db diff (lifecycle is the story for machines)
   (when machine
     (machine-block machine))
   ;; :db diff
   (when (seq db-diff)
     [:div {:data-testid "rf-xray-epoch-handler-db-diff"}
      (sub-header ":db diff"
                  (str (count db-diff) " path"
                       (when (not= 1 (count db-diff)) "s")))
      (for [[i row] (map-indexed vector db-diff)]
        (db-diff-line row i))])
   ;; :fx entries
   (when (seq fx)
     [:div {:data-testid "rf-xray-epoch-handler-fx"}
      (sub-header ":fx"
                  (str (count fx) " entr"
                       (if (= 1 (count fx)) "y" "ies")))
      (for [[i entry] (map-indexed vector fx)]
        (fx-entry-line entry i))])])

(defn render-handler-step
  "Render the HANDLER step (always present)."
  [{:keys [flavour event-id duration-ms step-number] :as step}]
  [:div {:data-testid "rf-xray-epoch-step-handler"
         :data-step-kw "handler"
         :data-handler-flavour (when flavour (name flavour))}
   (numbered-circle step-number :HANDLER)
   (step-header
     {:step :handler
      :badge :HANDLER
      :verb [:span {:style {:display "inline-flex" :gap "4px"}}
             [:span {:style {:color (:accent tokens)}}
              (proj/handler-flavour-label flavour)]
             (when event-id
               [:span {:style {:color (:text-tertiary tokens)}}
                (proj/ns-keyword event-id)])]
      :expandable? false
      :testid "rf-xray-epoch-handler"
      :duration-ms duration-ms}
     nil)
   (handler-body step)])

;; ---- FLOW step -----------------------------------------------------------

(defn- flow-row-view
  "Render one flow recompute row — id link + diff-style line.

  Argument order matches `map-indexed`'s `(f idx item)` convention
  (rf2-cq0ch — companion swap with `coeffect-row-view` / `fx-row-view`)."
  [idx {:keys [flow-id path before after duration-ms]}]
  [:div {:key (str "flow-" idx)
         :data-testid (str "rf-xray-epoch-flow-row-" idx)
         :style {:padding "3px 0"}}
   [:div {:style {:display "inline-flex"
                  :gap "4px"
                  :font-family mono-stack
                  :font-size "12px"
                  :color (:accent tokens)}}
    (proj/ns-keyword flow-id)
    (icons/external-link)
    (when (number? duration-ms)
      [:span {:style {:color (:text-tertiary tokens) :margin-left "8px"}}
       (proj/format-duration-ms duration-ms)])]
   (when (sequential? path)
     [:div {:style {:display "flex"
                    :align-items "flex-start"
                    :gap "8px"
                    :padding "2px 0 2px 21px"
                    :font-family mono-stack
                    :font-size "12px"}}
      [:span {:style {:color (:warning tokens) :font-weight 700}} "~"]
      [:span {:style {:color (:text-tertiary tokens)}}
       (proj/path-display path)]
      (when (some? before)
        [:span {:style {:color (:error tokens)}}
         (proj/truncate (pr-str before) 30)])
      (when (and (some? before) (some? after))
        [:span {:style {:color (:text-tertiary tokens)}} "→"])
      (when (some? after)
        [:span {:style {:color (:success tokens)}}
         (proj/truncate (pr-str after) 30)])])])

(defn render-flow-step
  "Render the FLOW step (only present when flows fired)."
  [{:keys [rows step-number]}]
  [:div {:data-testid "rf-xray-epoch-step-flow"
         :data-step-kw "flow"}
   (numbered-circle step-number :FLOW)
   (step-header
     {:step :flow
      :badge :FLOW
      :verb (str (count rows) " flow"
                 (when (not= 1 (count rows)) "s") " recomputed")
      :expandable? false
      :testid "rf-xray-epoch-flow"}
     nil)
   [:div {:style {:margin-top "5px"}}
    (map-indexed flow-row-view rows)]])

;; ---- FX step -------------------------------------------------------------

(defn- fx-row-view
  "Render one fx-handler row inside the FX step — green check + fx-id
  + truncated args.

  Argument order matches `map-indexed`'s `(f idx item)` convention
  (rf2-cq0ch — companion swap with `coeffect-row-view` /
  `flow-row-view`)."
  [idx {:keys [fx-id status args duration-ms]}]
  (let [glyph    (case status
                   :ok          "✓"
                   :overridden  "↺"
                   :skipped     "·"
                   :error       "✗"
                   "·")
        colour   (case status
                   :ok          (:success tokens)
                   :overridden  (:accent tokens)
                   :skipped     (:text-tertiary tokens)
                   :error       (:error tokens)
                   (:text-secondary tokens))]
    [:div {:key (str "fx-" idx)
           :data-testid (str "rf-xray-epoch-fx-row-" idx)
           :data-fx-status (when (keyword? status) (name status))
           :style {:display "flex"
                   :align-items "flex-start"
                   :gap "8px"
                   :padding "2px 0"
                   :font-family mono-stack
                   :font-size "12px"}}
     [:span {:style {:color colour :font-weight 700}} glyph]
     [:span {:style {:color (:accent tokens)}}
      (proj/ns-keyword fx-id)]
     (when (some? args)
       [:span {:style {:color (:text-primary tokens) :min-width 0 :flex 1
                       :word-break "break-word"}}
        (proj/truncate (pr-str args) 80)])
     (when (number? duration-ms)
       [:span {:style {:color (:text-tertiary tokens)
                       :margin-left "8px"
                       :white-space "nowrap"}}
        (proj/format-duration-ms duration-ms)])]))

(defn render-fx-step
  "Render the FX step (only present when fx-handlers fired)."
  [{:keys [rows step-number]}]
  [:div {:data-testid "rf-xray-epoch-step-fx"
         :data-step-kw "fx"}
   (numbered-circle step-number :FX)
   (step-header
     {:step :fx
      :badge :FX
      :verb (str (count rows) " side-effect"
                 (when (not= 1 (count rows)) "s"))
      :expandable? false
      :testid "rf-xray-epoch-fx"}
     nil)
   [:div {:style {:margin-top "5px"}}
    (map-indexed fx-row-view rows)]])

;; ---- SUBSCRIPTIONS step --------------------------------------------------

(defn- subscriptions-toggle-button
  "Render the `Show unchanged` toggle button — flips the
  `:rf.xray.epoch/subs-show-unchanged?` slot on the Xray app-db.
  When `show?` is true the label reads `Hide unchanged`."
  [show? unchanged]
  (when (pos? unchanged)
    [:button {:data-testid "rf-xray-epoch-subscriptions-toggle"
              :aria-pressed (str (boolean show?))
              :on-click (fn [e]
                          (.stopPropagation e)
                          (rf/dispatch
                            [:rf.xray.epoch/toggle-subs-show-unchanged]
                            {:frame :rf/xray}))
              :style {:background  "transparent"
                      :border      (str "1px solid " (:border-default tokens))
                      :border-radius "3px"
                      :color       (:text-secondary tokens)
                      :cursor      "pointer"
                      :font-family sans-stack
                      :font-size   "11px"
                      :padding     "2px 8px"
                      :margin-left "8px"}}
     (if show? "Hide unchanged" "Show unchanged")]))

(defn- subscriptions-table
  "Render the SUBSCRIPTIONS table — 3 columns (sub / inputs / changed).
  Per the bead body's §SUBSCRIPTIONS (Step 7) shape (rf2-kfh1v)."
  [rows]
  [:div {:data-testid "rf-xray-epoch-subscriptions-table"
         :style {:margin-top "5px"
                 :border (str "1px solid " (:border-subtle tokens))
                 :border-radius "3px"
                 :overflow "hidden"}}
   ;; header
   [:div {:style {:display "flex"
                  :align-items "stretch"
                  :background (:bg-3 tokens)
                  :border-bottom (str "1px solid " (:border-subtle tokens))
                  :font-family sans-stack
                  :font-size "10px"
                  :font-weight 700
                  :color (:text-tertiary tokens)
                  :text-transform "uppercase"
                  :letter-spacing "0.5px"}}
    [:div {:style {:flex "1 1 35%" :padding "5px 8px"}} "sub"]
    [:div {:style {:flex "1 1 35%" :padding "5px 8px"}} "inputs"]
    [:div {:style {:flex "1 1 30%" :padding "5px 8px"}} "changed"]]
   ;; rows
   (for [[i {:keys [sub-id sub-vec inputs changed? before after]}] (map-indexed vector rows)]
     [:div {:key (str "sub-" i)
            :data-testid (str "rf-xray-epoch-sub-row-" i)
            :data-sub-changed (str (boolean changed?))
            :style {:display "flex"
                    :align-items "stretch"
                    :border-bottom (when (< i (dec (count rows)))
                                     (str "1px solid " (:border-subtle tokens)))}}
      [:div {:style {:flex "1 1 35%" :padding "5px 8px" :min-width 0
                     :font-family mono-stack :font-size "12px"
                     :word-break "break-word"}}
       [:span {:style {:color (:accent tokens) :display "inline-flex"
                       :align-items "center" :gap "4px"}}
        (or (when (vector? sub-vec) (pr-str sub-vec))
            (proj/ns-keyword sub-id))
        (icons/external-link)]]
      [:div {:style {:flex "1 1 35%" :padding "5px 8px" :min-width 0
                     :font-family mono-stack :font-size "12px"
                     :color (:text-tertiary tokens)
                     :word-break "break-word"}}
       (cond
         (vector? inputs)
         (into [:div {:style {:display "flex" :flex-direction "column" :gap "2px"}}]
               (map (fn [i] [:div (proj/ns-keyword i)]) inputs))
         inputs (proj/ns-keyword inputs)
         :else  "app-db")]
      [:div {:style {:flex "1 1 30%" :padding "5px 8px" :min-width 0
                     :font-family mono-stack :font-size "12px"}}
       (if changed?
         [:div {:style {:display "flex" :gap "6px" :flex-wrap "wrap"
                        :align-items "center"}}
          [:span {:style {:color (:success tokens) :font-weight 700}} "✓"]
          (when (some? before)
            [:span {:style {:color (:error tokens)}}
             (proj/truncate (pr-str before) 24)])
          (when (and (some? before) (some? after))
            [:span {:style {:color (:text-tertiary tokens)}} "→"])
          (when (some? after)
            [:span {:style {:color (:success tokens)}}
             (proj/truncate (pr-str after) 24)])]
         [:span {:style {:color (:text-tertiary tokens) :font-weight 700}} "✗"])]])])

(defn render-subscriptions-step
  "Render the SUBSCRIPTIONS step (only present when subs recomputed).

  Per rf2-kfh1v unchanged-input rows are HIDDEN BY DEFAULT — most
  subs recompute on a cascade but report no value change, so the
  noisy `N rows of ✗` legacy display was crowding out the rows the
  operator actually cares about. A `Show unchanged` toggle reveals
  the full list; the toggle's state lives in
  `:rf.xray.epoch/subs-show-unchanged?` on the Xray app-db. Step
  header shows the split count `N recomputed (M changed, K unchanged)`.

  Mirrors the filter-toggle posture Chrome devtools' network panel
  uses (the precedent the bead body cites)."
  [{:keys [rows changed unchanged step-number]}]
  (let [show-unchanged? @(rf/subscribe [:rf.xray.epoch/subs-show-unchanged?])
        visible-rows    (if show-unchanged?
                          rows
                          (filterv :changed? rows))
        n               (count rows)
        m               (or changed (count (filter :changed? rows)))
        k               (or unchanged (- n m))]
    [:div {:data-testid "rf-xray-epoch-step-subscriptions"
           :data-step-kw "subscriptions"}
     (numbered-circle step-number :SUBSCRIPTIONS)
     (step-header
       {:step :subscriptions
        :badge :SUBSCRIPTIONS
        :verb [:span {:style {:display "inline-flex" :align-items "center"
                              :gap "8px" :flex-wrap "wrap"}}
               (str n " recomputed (" m " changed, " k " unchanged)")
               (subscriptions-toggle-button show-unchanged? k)]
        :expandable? false
        :testid "rf-xray-epoch-subscriptions"}
       nil)
     (subscriptions-table visible-rows)]))

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
         :style {:margin-top "5px"
                 :border (str "1px solid " (:border-subtle tokens))
                 :border-radius "3px"
                 :overflow "hidden"}}
   [:div {:style {:display "flex"
                  :align-items "stretch"
                  :background (:bg-3 tokens)
                  :border-bottom (str "1px solid " (:border-subtle tokens))
                  :font-family sans-stack
                  :font-size "10px"
                  :font-weight 700
                  :color (:text-tertiary tokens)
                  :text-transform "uppercase"
                  :letter-spacing "0.5px"}}
    [:div {:style {:flex "1 1 50%" :padding "5px 8px"}} "view"]
    [:div {:style {:flex "1 1 50%" :padding "5px 8px"}} "subs"]]
   (for [[i {:keys [view-id subs-read duration-ms]}] (map-indexed vector rows)]
     [:div {:key (str "view-" i)
            :data-testid (str "rf-xray-epoch-view-row-" i)
            :data-view-id (when view-id (pr-str view-id))
            :style {:display "flex"
                    :align-items "stretch"
                    :border-bottom (when (< i (dec (count rows)))
                                     (str "1px solid " (:border-subtle tokens)))}}
      [:div {:style {:flex "1 1 50%" :padding "5px 8px" :min-width 0
                     :font-family mono-stack :font-size "12px"
                     :word-break "break-word"}}
       [:span {:data-testid (str "rf-xray-epoch-view-row-id-" i)
               :style {:color (:accent tokens) :display "inline-flex"
                       :align-items "center" :gap "4px"}}
        (if (some? view-id)
          (proj/ns-keyword view-id)
          [:span {:style {:color (:text-tertiary tokens)
                          :font-style "italic"}}
           "<anonymous view>"])
        (coord-chip (view-coord view-id)
                    (str "rf-xray-epoch-view-row-coord-" i))]
       (when (number? duration-ms)
         [:span {:style {:color (:text-tertiary tokens)
                         :margin-left "8px"
                         :font-size "10px"}}
          (proj/format-duration-ms duration-ms)])]
      [:div {:data-testid (str "rf-xray-epoch-view-row-subs-" i)
             :style {:flex "1 1 50%" :padding "5px 8px" :min-width 0
                     :font-family mono-stack :font-size "12px"
                     :color (:text-tertiary tokens)
                     :word-break "break-word"}}
       (cond
         (and (sequential? subs-read) (seq subs-read))
         (into [:div {:style {:display "flex" :flex-direction "column" :gap "2px"}}]
               (for [s subs-read]
                 [:div (if (vector? s) (pr-str s) (proj/ns-keyword s))]))
         (some? subs-read)
         (proj/ns-keyword subs-read)
         :else
         [:span {:style {:font-style "italic"}} "(none)"])]])])

(defn render-views-step
  "Render the VIEWS step (only present when views re-rendered)."
  [{:keys [rows step-number]}]
  [:div {:data-testid "rf-xray-epoch-step-views"
         :data-step-kw "views"}
   (numbered-circle step-number :VIEWS)
   (step-header
     {:step :views
      :badge :VIEWS
      :verb (str (count rows) " view"
                 (when (not= 1 (count rows)) "s") " re-rendered")
      :expandable? false
      :testid "rf-xray-epoch-views"}
     nil)
   (views-table rows)])

;; ---- step dispatcher -----------------------------------------------------

(defn- render-step
  "Dispatch a step row to its renderer. Returns hiccup; nil for
  unknown step kinds (defensive — every step the projection produces
  is in the seven-step inventory)."
  [step]
  (case (:step step)
    :dispatch       (render-dispatch-step step)
    :coeffect       (render-coeffect-step step)
    :handler        (render-handler-step step)
    :flow           (render-flow-step step)
    :fx             (render-fx-step step)
    :subscriptions  (render-subscriptions-step step)
    :views          (render-views-step step)
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
      Row spacing: 13px vertical gap between entries"
  [steps]
  [:div {:data-testid "rf-xray-epoch-pipeline"
         :style {:position    "relative"
                 :padding-left "55px"
                 :padding-top  "0"}}
   ;; The vertical rail — absolute-positioned line behind the
   ;; numbered circles. Top = numbered-circle radius (so the line
   ;; starts at the centre of step-1's circle); bottom = the foot
   ;; of the last step's circle.
   [:div {:data-testid "rf-xray-epoch-rail"
          :aria-hidden true
          :style {:position    "absolute"
                  :left        (str (+ 55 badge/line-left-offset-px) "px")
                  :top         (str badge/vertical-line-offset-px "px")
                  :bottom      "13px"
                  :width       "1px"
                  :background  (:border-default tokens)
                  :pointer-events "none"}}]
   ;; Steps
   (for [[i step] (map-indexed vector steps)]
     ^{:key (str "step-" (:step step) "-" i)}
     [:div {:data-testid (str "rf-xray-epoch-pipeline-step-" (:step-number step))
            :data-step (when (:step step) (name (:step step)))
            :style {:position     "relative"
                    :margin-bottom "13px"
                    :min-height   "21px"}}
      (render-step step)])])

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
           :style {:padding     "21px"
                   :color       (:text-tertiary tokens)
                   :font-family sans-stack
                   :font-size   "13px"}}
     msg]))

;; ---- public Panel --------------------------------------------------------

(rf/reg-view Panel
  "Epoch panel root view. Subscribes to `:rf.xray/epoch-pipeline` —
  a composite that resolves the focused epoch off the spine and
  projects its `:trace-events` into the pipeline-step rows. Renders
  the numbered cascade when steps are present; an empty-state when
  the focus carries no record or the record carries no trace events."
  []
  (let [{:keys [status steps]} @(rf/subscribe [:rf.xray/epoch-pipeline])]
    [:section {:data-testid "rf-xray-epoch-panel"
               :style {:height          "100%"
                       :display         "flex"
                       :flex-direction  "column"
                       :background      (:bg-2 tokens)
                       :color           (:text-primary tokens)
                       :font-family     sans-stack
                       :font-size       "13px"}}
     [:div {:style {:flex 1 :overflow "auto" :padding "21px"}}
      [:div {:data-testid "rf-xray-epoch-panel-header"
             :style {:color       (:text-tertiary tokens)
                     :font-family sans-stack
                     :font-size   "11px"
                     :margin-bottom "21px"}}
       "The computational timeline for the event"]
      (cond
        (= :focused status)
        (if (seq steps)
          (pipeline-view steps)
          (empty-state-view :no-events))

        :else
        (empty-state-view (or status :no-focus)))]]))
