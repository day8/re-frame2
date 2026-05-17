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

(defn- sub-header
  "Per-sub header — sub-id, args, and a one-line summary."
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
       unchanged?
       [:div {:data-testid (str "rf-causa-views-sub-diff-unchanged-"
                                (pr-str sub-id))
              :style {:padding "8px 4px"
                      :color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size "11px"
                      :font-style "italic"}}
        "Value identical between db-before and db-after."]

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
