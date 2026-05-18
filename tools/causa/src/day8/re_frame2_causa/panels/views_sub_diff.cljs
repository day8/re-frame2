(ns day8.re-frame2-causa.panels.views-sub-diff
  "Sub-output structural-diff drilldown for the Views panel
  (rf2-xjhhp — Phase 2 of rf2-abts7).

  Renders the records vector produced by
  `:rf.causa/views-sub-diff-for-focused-event` (see
  `views_sub_diff_subs.cljs`) using the Phase 1 sections-per-cluster
  renderer (`diff.render/render-sections`).

  ## What this ships

    - One stacked block per recomputed sub.
    - Per-block header: `:sub-id` + `:query-v` (formatted via
      `app-db-diff-format/format-edn`).
    - Per-block body: either the `render-sections` output (value
      changed) or a single 'unchanged' chip (value identical pre/post —
      framework cache-hit fast path means `:sub-runs` rarely contains
      these, but it's defensive).

  ## Surface key

  The Phase 1 renderer takes a `surface` string used to namespace
  per-node expand state. We compose
  `view-sub-diff/<sub-id>/<query-v>` so each sub's expand state is
  independent across drilldowns AND distinct from the app-db diff
  surface."
  (:require [day8.re-frame2-causa.diff.render :as diff-render]
            [day8.re-frame2-causa.panels.app-db-diff-format :as f]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; rf2-87lkf — Delta 2 / Delta 3 polish helpers.
;;
;; The header now leads with the `·` marker for unchanged-after-recompute
;; subs so the drilldown's block-level treatment matches the Re-rendered
;; row's marker column. Hover tooltips on the marker mirror the chrome
;; in `views_view.cljs` so the same explanation is one mouse-hover away
;; in either location.

(def ^:private non-trigger-glyph-style
  {:color         (:text-tertiary tokens)
   :font-weight   400
   :font-size     "14px"
   :line-height   1
   :display       "inline-block"
   :width         "12px"
   :text-align    "center"
   :margin-right  "6px"})

(def ^:private non-trigger-glyph-tooltip
  "Sub recomputed, value unchanged — the substrate re-ran this sub during the cascade but the new value structurally equals the previous one. (React skipped re-render of any view reading only this sub.)")

(defn- sub-header
  "Per-sub header — sub-id, args, and a one-line summary. Per rf2-87lkf
  Delta 3, unchanged-after-recompute subs lead with a hoverable `·`
  marker so the drilldown block ships the same marker chrome the
  Re-rendered row's column carries."
  [{:keys [sub-id query-v unchanged? diff-sections]}]
  (let [args        (vec (rest query-v))
        section-cnt (count diff-sections)]
    [:header {:data-testid (str "rf-causa-views-sub-diff-header-"
                                (pr-str sub-id))
              :style {:display        "flex"
                      :align-items    "baseline"
                      :gap            "8px"
                      :padding        "6px 0"
                      :font-family    mono-stack
                      :font-size      "12px"
                      :color          (:text-primary tokens)
                      :border-bottom  (str "1px solid "
                                           (:border-subtle tokens))}}
     (when unchanged?
       [:span {:style       non-trigger-glyph-style
               :title       non-trigger-glyph-tooltip
               :aria-label  "value unchanged"
               :data-marker "non-trigger"
               :data-testid (str "rf-causa-views-sub-diff-marker-"
                                 (pr-str sub-id))}
        "·"])
     [:span {:style {:color (:accent-violet tokens) :font-weight 600}}
      (pr-str sub-id)]
     (when (seq args)
       [:span {:style {:color (:text-tertiary tokens)}}
        (f/truncate (f/format-edn args) 48)])
     [:span {:style {:flex 1}}]
     [:span {:style {:color (:text-tertiary tokens)
                     :font-family sans-stack
                     :font-size "11px"}}
      (cond
        unchanged?           "value unchanged"
        (zero? section-cnt)  "no structural change"
        (= 1 section-cnt)    "1 change cluster"
        :else                (str section-cnt " change clusters"))]]))

(defn sub-block
  "Render one sub's diff block — header + sections (or 'unchanged'
  chip)."
  [{:keys [sub-id query-v unchanged? diff-sections] :as record}]
  (let [surface (str "views-sub-diff/" (pr-str sub-id) "/" (pr-str query-v))]
    [:section {:data-testid (str "rf-causa-views-sub-diff-block-"
                                 (pr-str sub-id))
               :style {:margin "8px 0"
                       :padding "6px 8px"
                       :background (:bg-2 tokens)
                       :border (str "1px solid " (:border-subtle tokens))
                       :border-radius "3px"}}
     (sub-header record)
     (cond
       ;; rf2-87lkf Delta 3 — recomputed-but-equal subs (the `·`
       ;; marker case). Pre-bead the drilldown rendered a single
       ;; muted-italic chip ("Value identical between db-before and
       ;; db-after.") which read as substrate-internal language. The
       ;; replacement is developer-framed in two beats: a green ✓
       ;; one-liner that mirrors the row-level marker, plus a Why?
       ;; expander with the reaction-lifecycle explanation. The
       ;; expander is inline-only (no event dispatch) so the drilldown
       ;; stays self-contained.
       unchanged?
       [:div {:data-testid (str "rf-causa-views-sub-diff-unchanged-"
                                (pr-str sub-id))
              :style {:padding "8px 4px"
                      :color (:text-secondary tokens)
                      :font-family sans-stack
                      :font-size "12px"
                      :display "flex"
                      :flex-direction "column"
                      :gap "4px"}}
        [:div {:style {:display "flex" :align-items "baseline" :gap "6px"}}
         [:span {:style {:color (:green tokens) :font-weight 700}} "✓"]
         [:span {:style {:color (:text-primary tokens)}} "No change."]
         [:span {:style {:color (:text-tertiary tokens)
                         :font-family mono-stack
                         :font-size "11px"}}
          "Sub recomputed; value = previous."]
         [:span {:style {:color (:text-tertiary tokens)
                         :font-style "italic"}}
          "(React skipped re-render.)"]]
        [:details {:data-testid (str "rf-causa-views-sub-diff-unchanged-why-"
                                     (pr-str sub-id))
                   :style {:font-size "11px"
                           :color (:text-tertiary tokens)}}
         [:summary {:style {:cursor "pointer"
                            :color (:accent-violet tokens)
                            :font-family sans-stack}}
          "Why?"]
         [:p {:style {:margin "4px 0 0 0"
                      :padding-left "8px"
                      :border-left (str "2px solid "
                                        (:border-subtle tokens))}}
          (str "Reaction lifecycle — the sub's input ratoms changed "
               "(reactivated), so the substrate re-ran the sub's "
               "compute. The new return value structurally equals "
               "the previous one, so any view subscribed only to "
               "this sub was NOT re-rendered: re-frame's reactive "
               "cache compares value-by-value before propagating to "
               "watchers. The `·` marker exists to make this case "
               "visible — recompute happened, but it was free of "
               "downstream render cost.")]]]

       (empty? diff-sections)
       [:div {:data-testid (str "rf-causa-views-sub-diff-empty-"
                                (pr-str sub-id))
              :style {:padding "8px 4px"
                      :color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size "11px"
                      :font-style "italic"}}
        "No structural changes in the recomputed value."]

       :else
       (diff-render/render-sections diff-sections surface))]))

(defn drilldown
  "Top-level renderer — a stacked block per record."
  [records]
  [:div {:data-testid "rf-causa-views-sub-diff-drilldown"
         :style {:display "flex"
                 :flex-direction "column"
                 :gap "4px"}}
   (if (empty? records)
     [:div {:data-testid "rf-causa-views-sub-diff-empty"
            :style {:padding "8px 4px"
                    :color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "11px"
                    :font-style "italic"}}
      "No subs recomputed in this cascade."]
     (for [record records]
       ^{:key (str (pr-str (:sub-id record)) "/" (pr-str (:query-v record)))}
       (sub-block record)))])
