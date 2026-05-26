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
              :style {:color       (if long?
                                     (:warning tokens)
                                     (:text-tertiary tokens))
                      :font-family mono-stack
                      :font-size   "11px"
                      :font-weight (if long? 700 500)
                      :white-space "nowrap"
                      :margin-left "auto"
                      :padding-left "8px"
                      :display     "inline-flex"
                      :align-items "center"
                      :gap         "4px"}}
       (when long?
         [:span {:aria-hidden true
                 :style {:font-size "10px"}}
          "▲"])
       (proj/format-duration-ms duration-ms)])))

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
  this panel teaches).

  Per rf2-9c27r each row also surfaces per-action fx attribution
  when the action returned a map carrying `:fx` — the operator can
  trace each fx back to its emitting action without spec-walking."
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
          ^{:key (str "lc-" (name phase) "-" i)}
          [:div {:data-testid (str "rf-xray-epoch-handler-phase-" (name phase) "-row-" i)
                 :style {:display "flex"
                         :flex-direction "column"
                         :padding "1px 0"
                         :color (if (:threw? row) (:error tokens)
                                    (:text-primary tokens))}}
           [:div {:style {:display "flex" :gap "8px" :align-items "center"}}
            [:span {:style {:color (:text-tertiary tokens)}} "↓"]
            [:span (proj/ns-keyword (:action-id row))]
            (when (:threw? row)
              [:span {:style {:color (:error tokens) :margin-left "8px"}}
               "(threw)"])]
           ;; rf2-9c27r — per-action fx attribution
           (when (seq (:fx row))
             [:div {:data-testid (str "rf-xray-epoch-handler-phase-" (name phase) "-fx-" i)
                    :style {:padding-left "21px"
                            :color (:text-tertiary tokens)
                            :font-size "11px"}}
              (for [[j entry] (map-indexed vector (:fx row))
                    :let [[fx-id _args] (if (vector? entry) entry [entry nil])]]
                ^{:key (str "lc-fx-" (name phase) "-" i "-" j)}
                [:div {:style {:display "inline-flex"
                               :align-items "center"
                               :gap "4px"
                               :margin-right "8px"}}
                 "→ fx "
                 [:span {:style {:color (:accent tokens)}}
                  (proj/ns-keyword fx-id)]])])])])]))

(defn- machine-data-reduction-block
  "Render the DATA REDUCTION sub-section (rf2-9c27r §5). Renders
  `:data` before / after via `edn/inspect`. Elides when both sides
  are absent / identical."
  [{:keys [data-before data-after]}]
  (when (or (some? data-before) (some? data-after))
    (when (not= data-before data-after)
      [:div {:data-testid "rf-xray-epoch-handler-machine-data-reduction"
             :style {:padding "3px 0 3px 16px"}}
       (sub-header "data reduction")
       [:div {:style {:display "flex"
                      :gap "13px"
                      :padding-left "16px"
                      :font-family mono-stack
                      :font-size "12px"
                      :flex-wrap "wrap"}}
        [:div {:data-testid "rf-xray-epoch-handler-machine-data-before"
               :style {:flex 1 :min-width "120px"}}
         [:div {:style {:color (:text-tertiary tokens)
                        :font-size "10px"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"
                        :margin-bottom "2px"}}
          "before"]
         [:div {:style {:color (:error tokens)}}
          (edn/inspect-inline data-before)]]
        [:div {:data-testid "rf-xray-epoch-handler-machine-data-after"
               :style {:flex 1 :min-width "120px"}}
         [:div {:style {:color (:text-tertiary tokens)
                        :font-size "10px"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"
                        :margin-bottom "2px"}}
          "after"]
         [:div {:style {:color (:success tokens)}}
          (edn/inspect-inline data-after)]]]])))

(defn- machine-snapshot-diff-block
  "Render the SNAPSHOT DIFF sub-section (rf2-9c27r §6). The Spec 005
  snapshot is the full `{:state … :data … …}` map; we render
  `before` + `after` via `edn/inspect` so the operator can drill into
  any slot (data, state, parallel-region tags). Elides when the
  snapshots are identical."
  [{:keys [before after]}]
  (when (and (some? before) (some? after) (not= before after))
    [:div {:data-testid "rf-xray-epoch-handler-machine-snapshot-diff"
           :style {:padding "3px 0 3px 16px"}}
     (sub-header "snapshot diff")
     [:div {:style {:display "flex"
                    :gap "13px"
                    :padding-left "16px"
                    :flex-wrap "wrap"}}
      [:div {:data-testid "rf-xray-epoch-handler-machine-snapshot-before"
             :style {:flex 1 :min-width "120px"
                     :font-family mono-stack
                     :font-size "12px"}}
       [:div {:style {:color (:text-tertiary tokens)
                      :font-size "10px"
                      :text-transform "uppercase"
                      :letter-spacing "0.5px"
                      :margin-bottom "2px"}}
        "before"]
       [:div {:style {:color (:error tokens) :word-break "break-word"}}
        (edn/inspect-inline before)]]
      [:div {:data-testid "rf-xray-epoch-handler-machine-snapshot-after"
             :style {:flex 1 :min-width "120px"
                     :font-family mono-stack
                     :font-size "12px"}}
       [:div {:style {:color (:text-tertiary tokens)
                      :font-size "10px"
                      :text-transform "uppercase"
                      :letter-spacing "0.5px"
                      :margin-bottom "2px"}}
        "after"]
       [:div {:style {:color (:success tokens) :word-break "break-word"}}
        (edn/inspect-inline after)]]]]))

(defn- machine-block
  "Render the machine-handler-specific extras (transition summary,
  guards, lifecycle, timer cancellations, data reduction, snapshot
  diff). Per the bead body §HANDLER (Step 4) machine handler branch —
  uses the rf2-82a0u trace enhancements (phase + outcome + reason).

  Per rf2-9c27r the section now carries all 7 sub-sections per the
  design doc:

    1. TRANSITION — `before-state → after-state`, event, microsteps
    2. GUARDS — per-guard pass/fail/threw outcomes
    3. LIFECYCLE — phase-grouped action rows with per-action fx
       attribution
    4. AFTER TIMERS — armed/cancelled with reasons
    5. DATA REDUCTION — `:data` before / after via edn-inspector
    6. SNAPSHOT DIFF — full snapshot before / after
    7. FX — per-action fx attribution surfaces inline in LIFECYCLE
       (rather than as a sibling sub-section) so the operator reads
       'action X emitted fx Y' in one line."
  [{:keys [transition guards lifecycle timers]}]
  [:div {:data-testid "rf-xray-epoch-handler-machine"}
   ;; Transition summary
   (when transition
     [:div {:data-testid "rf-xray-epoch-handler-machine-transition"
            :style {:padding "3px 0 3px 16px"
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
         (str (pr-str before) " → " (pr-str after)))]
      ;; rf2-9c27r — microsteps + event slots ride alongside the
      ;; before→after pair so the operator can tell at a glance how
      ;; many always-microsteps fired + which event drove the cascade.
      (when (number? (:microsteps transition))
        [:div {:data-testid "rf-xray-epoch-handler-machine-microsteps"
               :style {:padding-left "16px"
                       :color (:text-tertiary tokens)
                       :font-size "10px"}}
         (str (:microsteps transition) " microstep"
              (when (not= 1 (:microsteps transition)) "s"))])])
   ;; Guards
   (when (seq guards)
     [:div {:style {:padding "3px 0 3px 16px"}}
      (sub-header "guards" (str (count guards) " evaluated"))
      (for [[i {:keys [guard-id outcome]}] (map-indexed vector guards)]
        [:div {:key (str "g-" i)
               :data-testid (str "rf-xray-epoch-handler-guard-row-" i)
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
   ;; Lifecycle (with per-action fx attribution — rf2-9c27r)
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
          (proj/timer-reason-label reason)]])])
   ;; rf2-9c27r — DATA REDUCTION + SNAPSHOT DIFF sub-sections, fed
   ;; by the `:data-before / :data-after` + `:before / :after`
   ;; slots the projection hoists off the `:rf.machine/transition`
   ;; trace.
   (machine-data-reduction-block transition)
   (machine-snapshot-diff-block transition)])

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
  `flow-row-view`).

  Per rf2-uffov: when the row carries `:attributed-to`, a muted
  `← <action-id>` attribution chip rides alongside so the operator
  reads `fx X emitted by action Y` in one line."
  [idx {:keys [fx-id status args duration-ms attributed-to]}]
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
           :data-fx-attributed (str (some? attributed-to))
           :style {:display "flex"
                   :align-items "flex-start"
                   :gap "8px"
                   :padding "2px 0"
                   :font-family mono-stack
                   :font-size "12px"
                   :flex-wrap "wrap"}}
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
        (proj/format-duration-ms duration-ms)])
     ;; rf2-uffov — per-action attribution chip (for machine cascades)
     (when-let [{:keys [action-id phase]} attributed-to]
       [:span {:data-testid (str "rf-xray-epoch-fx-row-attribution-" idx)
               :title (str "emitted by " (proj/ns-keyword action-id)
                           (when phase (str " (" (name phase) " action)")))
               :style {:color (:text-tertiary tokens)
                       :font-size "10px"
                       :margin-left "auto"
                       :white-space "nowrap"
                       :display "inline-flex"
                       :align-items "center"
                       :gap "4px"
                       :font-style "italic"}}
        [:span {:aria-hidden true} "←"]
        (proj/ns-keyword action-id)
        (when phase
          [:span {:style {:color (:text-tertiary tokens)}}
           (str "(" (name phase) ")")])])]))

(defn render-fx-step
  "Render the FX step (only present when fx-handlers fired).

  Per rf2-uffov: header carries the outcome split — `N fired (M
  succeeded, K threw)` — so the operator reads at-a-glance
  correctness without scanning every row's glyph. The `:succeeded`
  count rolls `:ok + :overridden`; `:skipped` rides as its own
  chip when non-zero."
  [{:keys [rows step-number succeeded skipped threw]}]
  (let [n (count rows)
        m (or succeeded n)
        k (or threw 0)
        s (or skipped 0)]
    [:div {:data-testid "rf-xray-epoch-step-fx"
           :data-step-kw "fx"
           :data-fx-threw (str k)}
     (numbered-circle step-number :FX)
     (step-header
       {:step :fx
        :badge :FX
        :verb [:span {:style {:display "inline-flex" :align-items "center"
                              :gap "8px" :flex-wrap "wrap"}}
               (str n " fired (" m " succeeded")
               (when (pos? k)
                 [:span {:style {:color (:error tokens)
                                 :font-weight 700}}
                  (str ", " k " threw")])
               (when (pos? s)
                 [:span {:style {:color (:text-tertiary tokens)}}
                  (str ", " s " skipped")])
               ")"]
        :expandable? false
        :testid "rf-xray-epoch-fx"}
       nil)
     [:div {:style {:margin-top "5px"}}
      (map-indexed fx-row-view rows)]]))

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
            ;; rf2-2f962 — pink-stripe view-name hover affordance. The
            ;; row-level mouse-enter/leave stamps the
            ;; `.rf-xray-view-highlight` class onto the live DOM node
            ;; tagged by Spec 006's `data-rf-view`, mirroring the
            ;; Reactive panel's view-node treatment (rf2-e33ad /
            ;; rf2-8l03l). Pure DOM side-effect; no layout perturbation.
            :on-mouse-enter (fn [_e] (apply-view-highlight! view-id))
            :on-mouse-leave (fn [_e] (clear-view-highlight! view-id))
            :style {:display "flex"
                    :align-items "stretch"
                    :border-bottom (when (< i (dec (count rows)))
                                     (str "1px solid " (:border-subtle tokens)))}}
      [:div {:style {:flex "1 1 50%" :padding "5px 8px" :min-width 0
                     :font-family mono-stack :font-size "12px"
                     :word-break "break-word"}}
       [:span {:data-testid (str "rf-xray-epoch-view-row-id-" i)
               :style {:color (:accent tokens) :display "inline-flex"
                       :align-items "center" :gap "4px"
                       :cursor (when view-id "pointer")}}
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

;; ---- SCHEMA-VIOLATIONS step (rf2-17vxj) ----------------------------------

(defn- schema-violation-where-label
  "Render a violation's `:where` slot as a UI label (rf2-17vxj). Closed
  set is per Spec 008 / 010; defaults to the keyword name."
  [where]
  (case where
    :app-db      "app-db commit"
    :cofx        "coeffect"
    :sub-return  "sub return"
    :fx-args     "fx args"
    :event       "event payload"
    :hot-reload  "schema hot-reload"
    (when (keyword? where) (name where))))

(defn- schema-violation-row-view
  "Render one schema-violation row (rf2-17vxj). Per-row fields:

    - `:where` label
    - `:failing-id` / `:frame` (the registered name whose boundary failed)
    - `:path` (the db / payload path, when available)
    - failing value via `edn/inspect-inline` (already redacted at the
      substrate emit site when `:sensitive?`)
    - `:rollback?` chip when the cascade was rejected"
  [idx {:keys [where failing-id path value rollback? recovery sensitive?
               kind explain] :as _row}]
  [:div {:key (str "schema-violation-" idx)
         :data-testid (str "rf-xray-epoch-schema-violation-row-" idx)
         :data-violation-kind (when kind (name kind))
         :data-rollback (str (boolean rollback?))
         :style {:display "flex"
                 :flex-direction "column"
                 :gap "3px"
                 :padding "5px 8px"
                 :background    (:bg-3 tokens)
                 :border-left   (str "2px solid " (:warning tokens))
                 :margin-bottom "5px"
                 :border-radius "0 3px 3px 0"
                 :font-family   mono-stack
                 :font-size     "12px"}}
   ;; head row: where + failing-id + rollback chip
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "8px"
                  :flex-wrap "wrap"}}
    [:span {:data-testid (str "rf-xray-epoch-schema-violation-where-" idx)
            :style {:color       (:warning tokens)
                    :font-weight 700
                    :text-transform "uppercase"
                    :font-size   "10px"
                    :letter-spacing "0.5px"}}
     (schema-violation-where-label where)]
    (when failing-id
      [:span {:data-testid (str "rf-xray-epoch-schema-violation-id-" idx)
              :style {:color (:accent tokens)}}
       (proj/ns-keyword failing-id)])
    (when rollback?
      [:span {:data-testid (str "rf-xray-epoch-schema-violation-rollback-" idx)
              :title "this cascade was rolled back"
              :style {:padding "2px 5px"
                      :border-radius "3px"
                      :background (:error tokens)
                      :color (:white tokens)
                      :font-size "10px"
                      :font-weight 700
                      :text-transform "uppercase"
                      :letter-spacing "0.5px"}}
       "rolled back"])
    (when (and recovery (not rollback?))
      [:span {:style {:color (:text-tertiary tokens)
                      :font-size "10px"
                      :font-style "italic"}}
       (str "recovery: " (name recovery))])]
   ;; path (when present)
   (when (sequential? path)
     [:div {:data-testid (str "rf-xray-epoch-schema-violation-path-" idx)
            :style {:color (:text-tertiary tokens)
                    :padding-left "16px"}}
      (proj/path-display path)])
   ;; failing value
   (when (some? value)
     [:div {:data-testid (str "rf-xray-epoch-schema-violation-value-" idx)
            :style {:color (:text-primary tokens)
                    :padding-left "16px"
                    :word-break "break-word"}}
      (edn/inspect-inline value)])
   ;; sensitive marker (the substrate already redacted; surface that the
   ;; value WAS redacted so the operator doesn't read the placeholder
   ;; as the actual failing value)
   (when sensitive?
     [:div {:style {:color (:text-tertiary tokens)
                    :font-style "italic"
                    :font-size "10px"
                    :padding-left "16px"}}
      "(value redacted — slot declared :sensitive?)"])
   ;; explain detail (Malli explain map, when stamped)
   (when (some? explain)
     [:div {:data-testid (str "rf-xray-epoch-schema-violation-explain-" idx)
            :style {:color (:text-secondary tokens)
                    :padding-left "16px"
                    :font-size "11px"}}
      (proj/truncate (pr-str explain) 120)])])

(defn render-schema-violations-step
  "Render the SCHEMA VIOLATIONS step (rf2-17vxj — only present when
  the cascade carried `:rf.error/schema-validation-failure` or
  `:rf.schema/violation` trace events).

  Header carries the violation count + a per-rollback split + a
  click-affordance that navigates to the Issues panel for full
  triage (the per-row data shows the cascade-local view; Issues
  holds the cross-session list)."
  [{:keys [rows rollbacks step-number]}]
  [:div {:data-testid "rf-xray-epoch-step-schema-violations"
         :data-step-kw "schema-violations"
         :data-rollback-count (str (or rollbacks 0))}
   (numbered-circle step-number :SCHEMA-VIOLATIONS)
   (step-header
     {:step :schema-violations
      :badge :SCHEMA-VIOLATIONS
      :verb [:span {:style {:display "inline-flex" :align-items "center"
                            :gap "8px" :flex-wrap "wrap"}}
             [:span {:style {:display "inline-flex"
                             :align-items "center"
                             :gap "4px"
                             :color (:warning tokens)}}
              (icons/alert-triangle)
              (str (count rows) " violation"
                   (when (not= 1 (count rows)) "s"))]
             (when (pos? (or rollbacks 0))
               [:span {:style {:color (:error tokens)
                               :font-weight 700}}
                (str rollbacks " rollback"
                     (when (not= 1 rollbacks) "s"))])
             [:button {:data-testid "rf-xray-epoch-schema-violations-open-issues"
                       :aria-label "open the Issues panel for full triage"
                       :on-click (fn [e]
                                   (.stopPropagation e)
                                   (rf/dispatch [:rf.xray/select-tab :issues]
                                                {:frame :rf/xray}))
                       :style {:background "transparent"
                               :border (str "1px solid " (:border-default tokens))
                               :border-radius "3px"
                               :color (:text-secondary tokens)
                               :cursor "pointer"
                               :font-family sans-stack
                               :font-size "10px"
                               :padding "2px 8px"
                               :margin-left "8px"
                               :text-transform "uppercase"
                               :letter-spacing "0.5px"}}
              "open issues →"]]
      :expandable? false
      :testid "rf-xray-epoch-schema-violations"}
     nil)
   [:div {:style {:margin-top "5px"}}
    (map-indexed schema-violation-row-view rows)]])

;; ---- APP-DB DIFF step (rf2-rrykz) ---------------------------------------

(defn- app-db-diff-row-view
  "Render one app-db diff row (rf2-rrykz). Per-row chrome mirrors the
  HANDLER step's existing `db-diff-line` posture (+ for added, ~ for
  modified, - for removed)."
  [idx {:keys [path before after change]}]
  (let [glyph (case change
                :added    "+"
                :removed  "-"
                :modified "~"
                "~")
        glyph-colour (case change
                       :added    (:success tokens)
                       :removed  (:error tokens)
                       (:warning tokens))]
    [:div {:key (str "app-db-diff-" idx)
           :data-testid (str "rf-xray-epoch-app-db-diff-row-" idx)
           :data-change (when (keyword? change) (name change))
           :style {:display      "flex"
                   :align-items  "flex-start"
                   :gap          "8px"
                   :padding      "2px 0"
                   :font-family  mono-stack
                   :font-size    "12px"}}
     [:span {:style {:color glyph-colour :font-weight 700}} glyph]
     [:span {:style {:color (:text-tertiary tokens) :white-space "nowrap"}}
      (proj/path-display path)]
     (case change
       :added
       [:span {:style {:color (:success tokens) :min-width 0 :flex 1
                       :word-break "break-word"}}
        (proj/truncate (pr-str after) 80)]

       :removed
       [:span {:style {:color (:error tokens) :min-width 0 :flex 1
                       :word-break "break-word"}}
        (proj/truncate (pr-str before) 80)]

       ;; modified (default)
       [:<>
        [:span {:style {:color (:error tokens)}}
         (proj/truncate (pr-str before) 40)]
        [:span {:style {:color (:text-tertiary tokens)}} "→"]
        [:span {:style {:color (:success tokens)}}
         (proj/truncate (pr-str after) 40)]])]))

(defn render-app-db-diff-step
  "Render the APP-DB DIFF step (rf2-rrykz — only present when the
  cascade mutated app-db).

  Header carries the change-count split `N changes (+M / ~K / -L)`
  so the operator reads structure at a glance. Per-row body is
  the same diff-line posture HANDLER's `:db-diff` uses (same data,
  different lens — HANDLER attributes, APP-DB DIFF surfaces)."
  [{:keys [rows added modified removed step-number]}]
  [:div {:data-testid "rf-xray-epoch-step-app-db-diff"
         :data-step-kw "app-db-diff"}
   (numbered-circle step-number :APP-DB-DIFF)
   (step-header
     {:step :app-db-diff
      :badge :APP-DB-DIFF
      :verb [:span {:style {:display "inline-flex" :align-items "center"
                            :gap "8px" :flex-wrap "wrap"}}
             (str (count rows) " path"
                  (when (not= 1 (count rows)) "s") " changed")
             [:span {:style {:color (:text-tertiary tokens)
                             :font-size "10px"
                             :font-family mono-stack}}
              (str "(+" added " / ~" modified " / -" removed ")")]]
      :expandable? false
      :testid "rf-xray-epoch-app-db-diff"}
     nil)
   [:div {:style {:margin-top "5px"}}
    (map-indexed app-db-diff-row-view rows)]])

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
           :style {:display "flex"
                   :align-items "center"
                   :gap "8px"
                   :padding "3px 0"
                   :font-family mono-stack
                   :font-size "12px"
                   :flex-wrap "wrap"}}
     ;; via fx-id chip (muted)
     [:span {:data-testid (str "rf-xray-epoch-child-dispatch-via-" idx)
             :style {:color (:text-tertiary tokens)
                     :font-size "10px"
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"
                     :font-weight 600}}
      (child-dispatch-via-label via)]
     ;; event vector (primary)
     [:span {:data-testid (str "rf-xray-epoch-child-dispatch-event-" idx)
             :style {:color (:text-primary tokens)
                     :min-width 0
                     :flex 1
                     :word-break "break-word"}}
      (pr-str event)]
     ;; delay chip (for :dispatch-later)
     (when (number? delay-ms)
       [:span {:data-testid (str "rf-xray-epoch-child-dispatch-delay-" idx)
               :style {:color (:text-tertiary tokens)
                       :font-size "10px"}}
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
                 :style {:background "transparent"
                         :border (str "1px solid " (:border-default tokens))
                         :border-radius "3px"
                         :color (:accent tokens)
                         :cursor "pointer"
                         :font-family sans-stack
                         :font-size "10px"
                         :padding "2px 8px"
                         :display "inline-flex"
                         :align-items "center"
                         :gap "4px"
                         :text-transform "uppercase"
                         :letter-spacing "0.5px"}}
        (icons/arrow-right)
        "jump"]
       [:span {:data-testid (str "rf-xray-epoch-child-dispatch-missing-" idx)
               :title "the child cascade has aged out of the epoch buffer (or has not yet completed)"
               :style {:color (:text-tertiary tokens)
                       :font-size "10px"
                       :font-style "italic"}}
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
   [:div {:style {:margin-top "5px"}}
    (map-indexed (fn [idx row]
                   (child-dispatch-row-view ctx idx row))
                 rows)]])

;; ---- step dispatcher -----------------------------------------------------

(declare render-child-dispatches-step)

(defn- render-step
  "Dispatch a step row to its renderer. Returns hiccup; nil for
  unknown step kinds (defensive — every step the projection produces
  is in the canonical inventory; rf2-17vxj added SCHEMA-VIOLATIONS,
  rf2-yx1ae added CHILD-DISPATCHES, rf2-rrykz added APP-DB-DIFF).

  `ctx` carries the cascade-level pieces a row may need (e.g. the
  parent `:dispatch-id` + the `:epoch-history` slice for the
  CHILD-DISPATCHES section's child-epoch resolution). Most steps
  ignore it."
  [step ctx]
  (case (:step step)
    :dispatch          (render-dispatch-step step)
    :coeffect          (render-coeffect-step step)
    :handler           (render-handler-step step)
    :flow              (render-flow-step step)
    :fx                (render-fx-step step)
    :subscriptions     (render-subscriptions-step step)
    :views             (render-views-step step)
    :schema-violations (render-schema-violations-step step)
    :child-dispatches  (render-child-dispatches-step step ctx)
    :app-db-diff       (render-app-db-diff-step step)
    nil))

;; ---- pipeline view -------------------------------------------------------

(defn cascade-summary
  "Render the cascade-summary chip at the top of the pipeline (rf2-nqt3d).

  Two pieces of information:

    1. **Cascade total** — sum of every projected step's `:duration-ms`,
       formatted via `format-duration-ms`. Operator's first read —
       'how heavy was this whole cascade'.
    2. **Long-step count** — number of steps whose `:duration-ms`
       exceeded `proj/long-step-threshold-ms` (16ms — one 60Hz frame).
       Rendered with warning tone when > 0; absent when 0.

  Returns nil when the cascade carries no durations (cold start
  records, fixtures synthesised without timing). The view elides
  the slot rather than rendering `total: —`."
  [steps]
  (let [total       (proj/cascade-total-ms steps)
        long-count  (proj/long-step-count steps)]
    (when (number? total)
      [:div {:data-testid "rf-xray-epoch-cascade-summary"
             :data-long-step-count (str long-count)
             :style {:display       "flex"
                     :align-items   "center"
                     :gap           "13px"
                     :margin-bottom "13px"
                     :padding       "5px 8px"
                     :border        (str "1px solid " (:border-subtle tokens))
                     :border-radius "3px"
                     :background    (:bg-3 tokens)
                     :font-family   sans-stack
                     :font-size     "11px"
                     :color         (:text-secondary tokens)}}
       [:span {:style {:color (:text-tertiary tokens)
                       :text-transform "uppercase"
                       :letter-spacing "0.5px"
                       :font-weight 600
                       :font-size "10px"}}
        "cascade total"]
       [:span {:data-testid "rf-xray-epoch-cascade-summary-total"
               :style {:color       (:text-primary tokens)
                       :font-family mono-stack
                       :font-weight 700}}
        (proj/format-duration-ms total)]
       (when (pos? long-count)
         [:span {:data-testid "rf-xray-epoch-cascade-summary-long-count"
                 :title       (str long-count " step"
                                   (when (not= 1 long-count) "s")
                                   " over " proj/long-step-threshold-ms "ms")
                 :style {:color (:warning tokens)
                         :font-family mono-stack
                         :margin-left "auto"
                         :display "inline-flex"
                         :align-items "center"
                         :gap "4px"}}
          [:span {:aria-hidden true :style {:font-size "10px"}} "▲"]
          (str long-count " over " proj/long-step-threshold-ms "ms")])])))

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
    ;; rf2-nqt3d — cascade-summary chip rides above the numbered
    ;; cascade. When the projection carries no durations the chip
    ;; elides; the cascade still renders normally.
    (cascade-summary steps)
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
        (render-step step ctx)])]]))

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
  (let [{:keys [status steps dispatch-id epoch-history]}
        @(rf/subscribe [:rf.xray/epoch-pipeline])]
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
          (pipeline-view steps
                         {:dispatch-id   dispatch-id
                          :epoch-history epoch-history})
          (empty-state-view :no-events))

        :else
        (empty-state-view (or status :no-focus)))]]))
