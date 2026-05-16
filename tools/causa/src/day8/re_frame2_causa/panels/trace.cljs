(ns day8.re-frame2-causa.panels.trace
  "Trace panel — Phase 5 (rf2-argrj, parent rf2-5aw5v).

  Per `tools/causa/spec/000-Vision.md` L89 (panel-inventory row) the
  Trace panel is the raw-event ribbon — every trace event in the
  buffer renders as one timestamped row, filterable along the 13-axis
  vocabulary documented in `spec/009-Instrumentation.md` §Filter
  vocabulary. Where the Issues ribbon collapses the stream to
  issues only, this panel surfaces every op-type so a programmer
  can grep across the full vocabulary.

  ## What this panel shows

  A scrollable, timestamped ribbon. Each row carries:

      timestamp · op-type-dot · operation · description · source-coord

  Clicking a row → the parent dispatch-id is selected
  (`:rf.causa/select-dispatch-id`) and the user pivots to the
  event-detail panel for the cascade. Empty `:dispatch-id` events
  (registry-time / lifecycle) stay non-clickable.

  Per-row chip-filter affordance: clicking any axis value in the
  row narrows the filter on that axis to the clicked value (the
  bead's 'per-row chip-filter UI' contract). The header's clear-all
  affordance drops every filter at once.

  ## Filter axes (Spec 009 §Filter vocabulary)

  All 9 named axes the vocabulary enumerates are surfaced:

      :op-type      :severity     :source      :origin       :frame
      :operation    :event-id     :handler-id  :dispatch-id

  Each axis is set via `:rf.causa/set-trace-filter` (axis, value);
  `nil` clears the axis. The numeric `:since` / `:since-ms` /
  `:between` and `:pred` axes are accepted by the filter algebra but
  ride a follow-on bead for the UI (drag-zoom on the time-axis,
  expression input).

  ## Empty states

  Per the bead's contract:

    :no-events   → 'No events observed in this session.' Once the
                   trace bus starts flowing this clears immediately.
    :no-matches  → events exist but the active filters hide them
                   all. 'No events match current filters' + Clear.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — pure hiccup, no
  Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in shell.cljs.

  ## Helpers

  All pure-data logic — filter application, row projection, axis
  enumeration, empty-state classification — lives in
  `trace_helpers.cljc` so the algebra runs under the JVM unit-test
  target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.overflow-indicator :as overflow]
            [day8.re-frame2-causa.panels.trace-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- axis labelling -----------------------------------------------------

(def ^:private axis-labels
  "Human-readable label for each filter axis. The view uses these
  for the chip-row headers and for the 'active filter' badges in the
  header strip."
  {:op-type     "op-type"
   :severity    "severity"
   :source      "source"
   :origin      "origin"
   :frame       "frame"
   :operation   "operation"
   :event-id    "event-id"
   :handler-id  "handler-id"
   :dispatch-id "dispatch-id"})

(defn- format-axis-value
  "Render an axis value for display. Keywords render with their
  namespace; numbers / strings render as-is; nils as '(none)'."
  [v]
  (cond
    (nil? v)     "(none)"
    (keyword? v) (str v)
    :else        (pr-str v)))

;; ---- chip helpers -------------------------------------------------------

(defn- chip
  "One filter chip. `active?` drives the highlighted styling.
  `on-click` fires the relevant axis toggle. `orphan?` (rf2-vu0mp)
  marks chips whose value is no longer represented in the current
  buffer — the chip still renders so the user always sees what's
  narrowing the ribbon, but is styled dashed-border + italic to
  signal 'no buffered matches'."
  [{:keys [label active? on-click test-id colour orphan? title]}]
  [:button {:data-testid test-id
            :on-click    on-click
            :title       title
            :style       {:background    (if active?
                                           (:bg-active tokens)
                                           "transparent")
                          :color         (if active?
                                           (:text-primary tokens)
                                           (or colour (:text-secondary tokens)))
                          :border        (str "1px "
                                              (if orphan? "dashed" "solid")
                                              " "
                                              (if active?
                                                (or colour (:border-default tokens))
                                                (:border-subtle tokens)))
                          :border-radius "999px"
                          :padding       "2px 10px"
                          :cursor        "pointer"
                          :font-family   sans-stack
                          :font-size     "11px"
                          :font-weight   (if active? 600 400)
                          :font-style    (if orphan? "italic" "normal")
                          :letter-spacing "0.2px"
                          :margin-right  "6px"
                          :margin-bottom "4px"}}
   label])

(defn- axis-chip-row
  "Render a chip-row for one filter axis. Each chip toggles the axis
  to its value; the currently-active value (if any) renders highlit.
  Renders when at least two distinct values are present OR when an
  active filter selection is present (so the user can always see /
  toggle off their narrowing — even when the selected value has
  aged out of the buffer per rf2-vu0mp).

  Per rf2-vu0mp the helper's `effective-distinct` ALREADY unions the
  active value into the per-axis distinct vector, so an orphaned
  active value renders as one of the chips. We mark it visually with
  the orphan-styling discipline on the chip (dashed border + italic +
  count `0`) so the user can tell `:source = :timer` is selected but
  the buffer no longer carries any `:timer`-sourced events."
  [{:keys [axis active-value distinct counts axis-colour]}]
  (when (and (sequential? distinct)
             (or (>= (count distinct) 2)
                 (some? active-value)))
    (into [:div {:data-testid (str "rf-causa-trace-axis-row-" (name axis))
                 :style       {:display    "flex"
                               :flex-wrap  "wrap"
                               :align-items "baseline"
                               :margin-top "4px"}}
           [:span {:style {:font-family   sans-stack
                           :font-size     "10px"
                           :color         (:text-tertiary tokens)
                           :text-transform "uppercase"
                           :letter-spacing "0.5px"
                           :margin-right  "8px"
                           :min-width     "70px"}}
            (get axis-labels axis (name axis))]]
          (for [value distinct
                :let [active? (= active-value value)
                      n       (get counts value 0)
                      orphan? (and active? (zero? n))
                      colour  (cond
                                (and (= axis :op-type) (some? value))
                                (h/op-type-colour value)
                                (= axis :severity)
                                (h/op-type-colour value)
                                :else axis-colour)]]
            (chip {:label    (str (format-axis-value value) " · " n)
                   :active?  active?
                   :colour   colour
                   :orphan?  orphan?
                   :title    (when orphan?
                               (str "no buffered events match — "
                                    (name axis) " = "
                                    (format-axis-value value)
                                    " (aged out of the ring buffer)"))
                   :test-id  (str "rf-causa-trace-axis-chip-"
                                  (name axis) "-"
                                  (if (keyword? value)
                                    (str (some-> value namespace (str "_"))
                                         (name value))
                                    (str value)))
                   :on-click #(rf/dispatch
                                [:rf.causa/set-trace-filter
                                 axis
                                 (when-not active? value)] {:frame :rf/causa})})))))

;; ---- header strip -------------------------------------------------------

(defn- header
  "Panel header — title + counts + chip rows + clear-all."
  [{:keys [total rendered distinct counts filters any-filter?]}]
  [:header {:style {:padding       "12px 16px 6px 16px"
                    :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [:div {:style {:display     "flex"
                  :align-items "baseline"
                  :gap         "12px"}}
    [:h1 {:style {:font-size   "16px"
                  :font-weight 600
                  :margin      0
                  :color       (:text-primary tokens)}}
     "Trace"]
    [:span {:data-testid "rf-causa-trace-counts"
            :style {:font-size   "11px"
                    :color       (:text-tertiary tokens)
                    :font-family mono-stack}}
     (str rendered " / " total " in view")]
    (when any-filter?
      [:button {:data-testid "rf-causa-trace-clear-filters"
                :on-click    #(rf/dispatch [:rf.causa/clear-trace-filters] {:frame :rf/causa})
                :style       {:margin-left "auto"
                              :background  "transparent"
                              :color       (:cyan tokens)
                              :border      (str "1px solid " (:border-default tokens))
                              :padding     "2px 8px"
                              :border-radius "3px"
                              :cursor      "pointer"
                              :font-family sans-stack
                              :font-size   "11px"}}
       "Clear filters"])]
   [:div {:style {:margin-top "8px"}}
    (for [axis h/filter-axes]
      ^{:key axis}
      [axis-chip-row {:axis         axis
                      :active-value (get filters axis)
                      :distinct     (get distinct axis)
                      :counts       (get counts axis)
                      :axis-colour  (:accent-violet tokens)}])]])

;; ---- per-row -------------------------------------------------------------

(defn- row-chip
  "A minimal in-row chip — clicking it sets the panel's filter on
  the named axis to the clicked value, per the bead's per-row chip-
  filter UI contract."
  [{:keys [axis value test-id]}]
  (when (some? value)
    [:button {:data-testid test-id
              :on-click    (fn [e]
                             (.stopPropagation e)
                             (rf/dispatch [:rf.causa/set-trace-filter
                                           axis value] {:frame :rf/causa}))
              :title       (str "filter " (name axis) " = "
                                (format-axis-value value))
              :style       {:background    "transparent"
                            :color         (:text-tertiary tokens)
                            :border        (str "1px solid "
                                                (:border-subtle tokens))
                            :border-radius "3px"
                            :padding       "1px 6px"
                            :cursor        "pointer"
                            :font-family   mono-stack
                            :font-size     "10px"
                            :margin-right  "4px"}}
     (format-axis-value value)]))

(defn- trace-row
  "One row in the trace ribbon.

  Per rf2-z4fza: the React `:key` is the row's stable trace `:id`
  (via `h/row-key`). The earlier shape keyed on a tuple that mixed
  in the row's positional index inside the visible viewport — every
  new trace push shifted every visible row's index, so every key
  changed, and React unmounted+remounted the entire viewport on
  EVERY push. Same discipline class as rf2-kgn0c's `v:<variant-id>`
  cell-keying in the story workspace."
  [{:keys [id time op-type operation source origin frame description
           source-coord dispatch-id]
    :as row}]
  (let [row-test-id (str "rf-causa-trace-row-" id)
        dot-colour  (h/op-type-colour op-type)]
    [:li {:key         (h/row-key row)
          :data-testid row-test-id
          :on-click    (fn []
                         (when dispatch-id
                           (rf/dispatch [:rf.causa/select-dispatch-id
                                         dispatch-id frame] {:frame :rf/causa})
                           (rf/dispatch [:rf.causa/select-panel
                                         :event-detail] {:frame :rf/causa})))
          :style       {:display       "grid"
                        :grid-template-columns
                        "84px 14px minmax(140px, 1fr) 2fr auto auto"
                        :gap           "10px"
                        :align-items   "center"
                        :padding       "6px 16px"
                        :border-bottom (str "1px solid " (:border-subtle tokens))
                        :cursor        (if dispatch-id "pointer" "default")
                        :color         (:text-primary tokens)
                        :font-family   mono-stack
                        :font-size     "12px"
                        :line-height   1.35}}
     ;; Timestamp
     [:span {:data-testid (str row-test-id "-time")
             :style {:color (:text-tertiary tokens)
                     :font-size "11px"
                     :white-space "nowrap"}}
      (or (h/format-time time) "—")]
     ;; op-type dot
     [:span {:data-testid (str row-test-id "-op-type-dot")
             :title       (str op-type)
             :style       {:color dot-colour
                           :font-weight 700
                           :text-align "center"}}
      "●"]
     ;; Operation
     [:span {:data-testid (str row-test-id "-operation")
             :style       {:color         (:accent-violet tokens)
                           :overflow      "hidden"
                           :text-overflow "ellipsis"
                           :white-space   "nowrap"}
             :title       (str operation)}
      (or (some-> operation str) "—")]
     ;; Description
     [:span {:data-testid (str row-test-id "-description")
             :style       {:color         (:text-secondary tokens)
                           :overflow      "hidden"
                           :text-overflow "ellipsis"
                           :white-space   "nowrap"}
             :title       description}
      description]
     ;; Per-row chip-filters — the bead's contract. We surface the four
     ;; commonly-grepped axes that ride on the row directly (source,
     ;; origin, frame). The op-type / severity chips ride the dot;
     ;; clicking the dot itself is a future affordance — for v1 the
     ;; chip-row in the header is the op-type entry point.
     [:span {:data-testid (str row-test-id "-row-chips")
             :style       {:display "flex"
                           :align-items "center"
                           :white-space "nowrap"}}
      (row-chip {:axis :source :value source
                 :test-id (str row-test-id "-source-chip")})
      (row-chip {:axis :origin :value origin
                 :test-id (str row-test-id "-origin-chip")})
      (row-chip {:axis :frame :value frame
                 :test-id (str row-test-id "-frame-chip")})]
     ;; Source-coord (when present)
     (if source-coord
       [:button {:data-testid (str row-test-id "-source-coord")
                 :on-click    (fn [e]
                                (.stopPropagation e)
                                (rf/dispatch [:rf.causa/open-in-editor
                                              {:source-coord source-coord}] {:frame :rf/causa}))
                 :style       {:background  "transparent"
                               :color       (:cyan tokens)
                               :border      (str "1px solid " (:border-subtle tokens))
                               :padding     "1px 6px"
                               :border-radius "3px"
                               :cursor      "pointer"
                               :font-family mono-stack
                               :font-size   "10px"}}
        source-coord]
       [:span {:style {:color (:text-tertiary tokens)
                       :font-size "10px"}}
        "—"])]))

;; ---- empty states -------------------------------------------------------

(defn- empty-state-no-events
  "Per the bead's contract — the buffer is empty (no traces observed
  yet)."
  []
  [:div {:data-testid "rf-causa-trace-empty-no-events"
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-secondary tokens)}}
   [:p {:style {:margin 0
                :color  (:text-tertiary tokens)}}
    "No events observed in this session. "
    "Once the host app dispatches anything the ribbon will fill."]])

(defn- active-filter-pill
  "Per rf2-vu0mp — one pill in the no-matches empty state's 'narrowing
  on' strip. Clicking removes the axis from the filter map. Orphan
  pills (value aged out of the buffer) carry an additional '(orphaned)'
  marker so the user knows the active value will not match anything
  in the current buffer until either the filter is cleared OR a new
  event with that value arrives."
  [{:keys [axis value present?]}]
  (let [axis-name (name axis)
        value-str (cond
                    (nil? value)     "(none)"
                    (keyword? value) (str value)
                    :else            (pr-str value))
        test-id   (str "rf-causa-trace-empty-active-" axis-name)
        label     (if present?
                    (str axis-name " = " value-str)
                    (str axis-name " = " value-str " (orphaned)"))]
    [:button {:data-testid test-id
              :on-click    #(rf/dispatch [:rf.causa/set-trace-filter
                                          axis nil] {:frame :rf/causa})
              :title       (if present?
                             (str "click to drop the " axis-name " filter")
                             (str axis-name " = " value-str
                                  " — value aged out of the buffer; click to drop"))
              :style       {:background    (if present?
                                             (:bg-active tokens)
                                             "transparent")
                            :color         (if present?
                                             (:text-primary tokens)
                                             (:text-secondary tokens))
                            :border        (str "1px "
                                                (if present? "solid" "dashed")
                                                " "
                                                (:border-default tokens))
                            :border-radius "999px"
                            :padding       "3px 10px"
                            :cursor        "pointer"
                            :font-family   sans-stack
                            :font-size     "11px"
                            :font-style    (if present? "normal" "italic")
                            :margin-right  "6px"
                            :margin-bottom "4px"}}
     label]))

(defn- empty-state-no-matches
  "Per the bead's contract — events exist but the active filters
  hide them all. Carries a Clear filters affordance.

  Per rf2-vu0mp: surfaces an 'narrowing on:' strip listing every
  active axis=value pair with an orphan marker. Without this strip
  the user has no in-panel cue WHICH filter is responsible when the
  selected value has aged out of the buffer (the chip in the header
  may not be obvious; with multiple axes active the user has to
  scan)."
  [{:keys [active-filters]}]
  [:div {:data-testid "rf-causa-trace-empty-no-matches"
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-secondary tokens)}}
   [:p {:style {:margin "0 0 12px 0"
                :color (:text-primary tokens)
                :font-weight 600}}
    "No events match current filters."]
   (when (seq active-filters)
     [:div {:data-testid "rf-causa-trace-empty-active-filters"
            :style       {:margin "0 0 12px 0"}}
      [:span {:style {:font-size      "10px"
                      :color          (:text-tertiary tokens)
                      :text-transform "uppercase"
                      :letter-spacing "0.5px"
                      :margin-right   "8px"}}
       "narrowing on:"]
      (into [:span {:style {:display "inline-flex" :flex-wrap "wrap"
                            :align-items "baseline"}}]
            (for [{:keys [axis] :as pill} active-filters]
              ^{:key axis} [active-filter-pill pill]))])
   [:p {:style {:margin "0 0 12px 0"
                :color (:text-tertiary tokens)}}
    "Click a pill above to drop that axis, or use Clear filters to widen the ribbon."]
   [:button {:data-testid "rf-causa-trace-empty-clear-filters"
             :on-click    #(rf/dispatch [:rf.causa/clear-trace-filters] {:frame :rf/causa})
             :style       {:background "transparent"
                           :color      (:cyan tokens)
                           :border     (str "1px solid " (:border-default tokens))
                           :padding    "4px 10px"
                           :border-radius "3px"
                           :cursor     "pointer"
                           :font-family sans-stack
                           :font-size  "12px"}}
    "Clear filters"]])

;; ---- public view --------------------------------------------------------

(rf/reg-view trace-view
  "The Trace panel's root view. Subscribes to `:rf.causa/trace-feed`
  and renders either the chip-filterable ribbon or the empty-state."
  []
  (let [{:keys [rows total rendered distinct counts filters
                any-filter? empty-kind active-filters]
         :as _data}
        @(rf/subscribe [:rf.causa/trace-feed])]
    [:section {:data-testid "rf-causa-trace"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     (header {:total       total
              :rendered    rendered
              :distinct    distinct
              :counts      counts
              :filters     filters
              :any-filter? any-filter?})
     [:div {:style {:flex 1 :overflow "auto"}}
      (case empty-kind
        :no-events  (empty-state-no-events)
        :no-matches (empty-state-no-matches {:active-filters active-filters})
        nil         (overflow/capped-list
                      rows
                      {:panel-id "trace"
                       :ul-attrs {:data-testid "rf-causa-trace-feed"
                                  :style       {:list-style "none"
                                                :margin     0
                                                :padding    0}}
                       :row-fn   trace-row}))]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Trace panel's Causa-side registrations
  (Phase 5, rf2-argrj)."
  []
  ;; ---- Phase 5 (rf2-argrj) — Trace panel ------------------------------
  ;;
  ;; The Trace panel is the UI consumer of the canonical 13-axis filter
  ;; vocabulary documented in spec/009-Instrumentation.md §Filter
  ;; vocabulary. Where the Issues ribbon collapses the stream to issues
  ;; only, this panel surfaces the raw stream so a programmer can grep
  ;; across every op-type / operation / source / origin / frame / etc.
  ;;
  ;; Shape of `:rf.causa/trace-feed`:
  ;;
  ;;     {:rows         [<row> ...]      ;; post-filter, newest first
  ;;      :total        <int>            ;; pre-filter count
  ;;      :rendered     <int>            ;; post-filter count
  ;;      :distinct     {<axis> [...]}   ;; per-axis chip values
  ;;      :counts       {<axis> {<value> <int>}}
  ;;      :filters      <pass-through normalised>
  ;;      :any-filter?  <bool>
  ;;      :empty-kind   <:no-events / :no-matches / nil>}

  ;; The current 13-axis filter map. Each axis is independent; the
  ;; helper's `normalise-filters` drops nil / empty values before
  ;; applying — the view sets axis = nil to clear an axis.
  (rf/reg-sub :rf.causa/trace-filters
    (fn [db _query]
      (get db :trace-filters {})))

  ;; Per rf2-44vzy: the trace-feed projection now reads a pre-
  ;; computed snapshot maintained incrementally by the
  ;; `:rf.causa/note-trace-event` handler in `registry.cljs`. The
  ;; snapshot carries the projected rows plus per-axis distinct /
  ;; counts / seen state and is updated in O(axes) on every push
  ;; (and O(axes) on every cap eviction). Pre-rf2-44vzy the sub re-
  ;; walked the entire buffer on every push — at 60Hz × 1000-event
  ;; buffer that was ~60k row-touches/sec on the main thread before
  ;; any render work happened.
  ;;
  ;; Fallback path: when `:trace-feed-state` is absent (a consumer
  ;; that bypasses the mirror — e.g. a headless test driving
  ;; `collect-trace!` without the `mount.cljs/open!` seed) the sub
  ;; rebuilds the snapshot from the raw buffer on read. The rebuild
  ;; is the same cost shape as the pre-rf2-44vzy sub, so the worst
  ;; case under the fallback path is no slower than the prior shape.
  ;;
  ;; The fallback chain mirrors `:rf.causa/trace-buffer`'s contract
  ;; (registry.cljs L100-102): app-db slot → trace-bus atom. The
  ;; atom path keeps headless tests (`collect-trace!` without
  ;; `mount.cljs/open!`) functional — the prior `project-feed` sub
  ;; consumed `:rf.causa/trace-buffer` which already chained through
  ;; this fallback, so preserving the chain keeps headless behaviour
  ;; identical.
  (rf/reg-sub :rf.causa/trace-feed-state
    (fn [db _query]
      (or (get db :trace-feed-state)
          (h/rebuild-feed-state
            (or (get db :trace-buffer)
                (trace-bus/buffer))))))

  ;; Composite — produces every slot the view consumes. Reactive
  ;; surface: trace-feed-state (incrementally maintained) + filter
  ;; state. The helper's `project-feed-from-state` does the read-
  ;; side work: a single reverse-then-filter walk over already-
  ;; projected rows. Filter-only changes still O(rows) but with no
  ;; per-event `project-row` cost.
  (rf/reg-sub :rf.causa/trace-feed
    :<- [:rf.causa/trace-feed-state]
    :<- [:rf.causa/trace-filters]
    (fn [[state filters] _query]
      (h/project-feed-from-state state filters)))

  ;; Set or clear one axis. Passing nil-value clears that axis;
  ;; setting a value replaces any existing value on that axis (the
  ;; chip-filter UI is single-value per axis for v1; multi-value
  ;; rides a follow-on bead).
  (rf/reg-event-db :rf.causa/set-trace-filter
    (fn [db [_ axis value]]
      (let [current (get db :trace-filters {})]
        (assoc db :trace-filters
               (if (nil? value)
                 (dissoc current axis)
                 (assoc current axis value))))))

  ;; Clear every filter axis in one shot. Wired from the header's
  ;; 'Clear filters' button + the no-matches empty state.
  (rf/reg-event-db :rf.causa/clear-trace-filters
    (fn [db _event]
      (dissoc db :trace-filters))))
