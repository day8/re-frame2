(ns day8.re-frame2-causa.panels.reactive-panel-view
  "Root view for the Reactive panel (rf2-wyvf2 · spec/021 §3).

  Renders the canonical sub-cascade + view-re-render visualisation:

    [7] SUBS RECOMPUTED   — :rf.sub/computed rows + :rf.sub/skipped
                            (disclosure, default collapsed per §3.4)
    [8] VIEWS RE-RENDERED — :rf.view/rendered rows

  The cascade is a DAG over db-paths → subs → views (§3.2); v1
  ships a depth-1 flat listing — sub-of-sub hierarchy lights up
  when the substrate carries the parent chain on the trace payload.

  Pure hiccup — frame isolation via the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in the shell. Subs read
  on `:rf.causa/*` (panel state) and the dynamically-bound focus
  via the spine."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack display-stack]]))

;; ---- styling primitives -------------------------------------------------

(def ^:private group-section-style
  {:padding "12px 16px 4px 16px"
   :font-size "11px"
   :font-weight 600
   :text-transform "uppercase"
   :letter-spacing "0.05em"
   :color (:text-tertiary tokens)
   :border-bottom (str "1px solid " (:border-subtle tokens))})

(def ^:private row-style
  {:padding "6px 16px 6px 24px"
   :border-bottom (str "1px solid " (:border-subtle tokens))
   :font-family mono-stack
   :font-size "12px"
   :color (:text-primary tokens)
   :display "flex"
   :gap "8px"
   :align-items "baseline"})

(def ^:private dim-row-style
  (assoc row-style :color (:text-tertiary tokens)))

(def ^:private glyph-style
  {:display "inline-block"
   :width "12px"
   :text-align "center"
   :font-size "12px"})

(def ^:private mono-style
  {:font-family mono-stack
   :font-size "12px"
   :color (:text-secondary tokens)})

;; ---- pure formatters ---------------------------------------------------

(defn- ms->str
  [ms]
  (cond
    (nil? ms)       "—"
    (< ms 1)        (str (.toFixed (* ms 1000) 0) "µs")
    (< ms 10)       (str (.toFixed ms 2) "ms")
    :else           (str (.toFixed ms 1) "ms")))

(defn- format-id
  [id]
  (cond
    (nil? id)        ""
    (keyword? id)    (str id)
    :else            (pr-str id)))

;; ---- header ------------------------------------------------------------

(defn- header-block
  [data]
  [:header {:style {:padding "16px 16px 8px 16px"}}
   [:h1 {:style (merge {:font-size "20px" :font-family display-stack
                        :font-weight 600 :letter-spacing "-0.01em"
                        :margin 0
                        :color (:text-primary tokens)}
                       (t/accent-stripe-style :views))}
    "Reactive"]
   (when (:has-cascade? data)
     [:p {:data-testid "rf-causa-reactive-header-meta"
          :style {:font-size "11px" :color (:text-tertiary tokens)
                  :margin "2px 0 0 0"}}
      (let [counts (:counts data)]
        (str "Frame: " (:frame data)
             (when-let [did (:dispatch-id data)]
               (str " · cascade " (pr-str did)))
             " · " (or (:subs-ran counts) 0) " subs ran · "
             (or (:subs-skipped counts) 0) " skipped · "
             (or (:views-rendered counts) 0) " views rendered"))])])

;; ---- header summary fragments ------------------------------------------

(defn- triggered-by-row
  [data]
  (when (:triggered-by data)
    [:div {:data-testid "rf-causa-reactive-triggered-by"
           :style {:padding "8px 16px"
                   :font-family mono-stack
                   :font-size "12px"
                   :color (:text-secondary tokens)
                   :border-bottom (str "1px solid " (:border-subtle tokens))}}
     [:span {:style {:color (:text-tertiary tokens) :margin-right "8px"}}
      "Triggered by"]
     [:span (pr-str (:triggered-by data))]]))

(defn- seed-paths-row
  [data]
  (when (seq (:seed-paths data))
    [:div {:data-testid "rf-causa-reactive-seed-paths"
           :style {:padding "6px 16px"
                   :font-family mono-stack
                   :font-size "12px"
                   :color (:text-secondary tokens)
                   :border-bottom (str "1px solid " (:border-subtle tokens))}}
     [:span {:style {:color (:text-tertiary tokens) :margin-right "8px"}}
      "Seed paths"]
     (for [[i p] (map-indexed vector (:seed-paths data))]
       ^{:key i}
       [:span {:style {:margin-right "12px"}} (pr-str p)])]))

;; ---- step 7 — subs recomputed ------------------------------------------

(defn- sub-ran-row
  [payload]
  (let [sub-id (or (:sub-id payload) (:id payload))]
    [:div {:data-testid "rf-causa-reactive-sub-ran"
           :style row-style}
     [:span {:style (assoc glyph-style :color (:accent-violet tokens)
                           :font-weight 700)}
      "◆"]
     [:span {:style {:font-weight 600}} (format-id sub-id)]
     (when-let [reason (:reason payload)]
       [:span {:style {:color (:text-tertiary tokens) :font-size "11px"}}
        (str "(" (name reason) ")")])]))

(defn- sub-skipped-row
  [payload]
  (let [sub-id (or (:sub-id payload) (:id payload))
        reason (or (:reason payload) :input-unchanged)]
    [:div {:data-testid "rf-causa-reactive-sub-skipped"
           :style dim-row-style}
     [:span {:style (assoc glyph-style :color (:text-tertiary tokens))}
      "○"]
     [:span (format-id sub-id)]
     [:span {:style {:color (:text-tertiary tokens) :font-size "11px"}}
      (str "(input unchanged · " (name reason) ")")]]))

(defn- subs-recomputed-section
  [data show-unchanged?]
  (let [subs-ran      (:subs-ran data)
        subs-skipped  (:subs-skipped data)
        ran-count     (count subs-ran)
        skip-count    (count subs-skipped)]
    [:section {:data-testid "rf-causa-reactive-step-7"
               :style {:display "flex" :flex-direction "column"}}
     [:header {:style group-section-style}
      (str "[7] subs recomputed (" ran-count " ran · "
           skip-count " skipped)")]
     (if (seq subs-ran)
       (for [[i p] (map-indexed vector subs-ran)]
         ^{:key i} (sub-ran-row p))
       [:div {:data-testid "rf-causa-reactive-subs-empty"
              :style (assoc row-style :color (:text-tertiary tokens))}
        [:span "No subs subscribed to changed paths."]])
     (when (pos? skip-count)
       [:div {:data-testid "rf-causa-reactive-unchanged-block"}
        (if show-unchanged?
          [:<>
           [:button {:data-testid "rf-causa-reactive-toggle-unchanged"
                     :on-click #(rf/dispatch [:rf.causa/reactive-toggle-unchanged]
                                             {:frame :rf/causa})
                     :style {:padding "4px 16px"
                             :background "transparent"
                             :border 0
                             :border-bottom (str "1px solid "
                                                 (:border-subtle tokens))
                             :color (:text-secondary tokens)
                             :font-family sans-stack
                             :font-size "11px"
                             :cursor "pointer"
                             :text-align "left"
                             :width "100%"}}
            (str "▴ Hide " skip-count " unchanged subs")]
           (for [[i p] (map-indexed vector subs-skipped)]
             ^{:key i} (sub-skipped-row p))]
          [:button {:data-testid "rf-causa-reactive-toggle-unchanged"
                    :on-click #(rf/dispatch [:rf.causa/reactive-toggle-unchanged]
                                            {:frame :rf/causa})
                    :style {:padding "4px 16px"
                            :background "transparent"
                            :border 0
                            :border-bottom (str "1px solid "
                                                (:border-subtle tokens))
                            :color (:text-secondary tokens)
                            :font-family sans-stack
                            :font-size "11px"
                            :cursor "pointer"
                            :text-align "left"
                            :width "100%"}}
           (str "▾ Show " skip-count " unchanged subs")])])]))

;; ---- step 8 — views re-rendered ----------------------------------------

(defn- view-rendered-row
  [payload]
  (let [view-id   (or (:view-id payload) (:id payload))
        file      (:file payload)
        line      (:line payload)
        caused-by (or (:caused-by-sub payload) (:caused-by payload))
        paths     (or (:caused-by-paths payload) [])]
    [:div {:data-testid "rf-causa-reactive-view-rendered"
           :style (assoc row-style :flex-direction "column"
                         :align-items "stretch" :gap "2px")}
     [:div {:style {:display "flex" :gap "8px" :align-items "baseline"}}
      [:span {:style (assoc glyph-style :color (:accent-violet tokens))}
       "▢"]
      [:span {:style {:font-weight 600}} (format-id view-id)]
      (when (and file line)
        [:span {:style {:color (:text-tertiary tokens) :font-size "11px"}}
         (str file ":" line)])
      (when-let [ms (:elapsed-ms payload)]
        [:span {:style {:color (:text-tertiary tokens) :font-size "11px"}}
         (ms->str ms)])]
     (when (or caused-by (seq paths))
       [:div {:data-testid "rf-causa-reactive-caused-by"
              :style {:padding-left "20px"
                      :color (:text-tertiary tokens)
                      :font-size "11px"
                      :font-family mono-stack}}
        (str "caused-by"
             (when caused-by (str " ← " (format-id caused-by)))
             (when (seq paths)
               (str " ← "
                    (clojure.string/join ", " (map pr-str paths)))))])]))

(defn- views-rendered-section
  [data]
  (let [views (:views-rendered data)
        n     (count views)]
    [:section {:data-testid "rf-causa-reactive-step-8"
               :style {:display "flex" :flex-direction "column"
                       :border-top (str "1px solid " (:border-subtle tokens))}}
     [:header {:style group-section-style}
      (str "[8] views re-rendered (" n ")")]
     (if (seq views)
       (for [[i v] (map-indexed vector views)]
         ^{:key i} (view-rendered-row v))
       [:div {:data-testid "rf-causa-reactive-views-empty"
              :style (assoc row-style :color (:text-tertiary tokens))}
        [:span "No views re-rendered."]])]))

;; ---- empty state ------------------------------------------------------

(defn- empty-state
  [data]
  [:div {:data-testid "rf-causa-reactive-empty"
         :style {:padding "16px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size "13px"}}
   (if (nil? (:current (:focus data)))
     [:p "No event focused."]
     [:p "Focused cascade has no reactive activity captured yet."])])

;; ---- panel root --------------------------------------------------------

(defn reactive-panel
  "Plain Reagent fn — invoked from `reactive-panel/Panel` (the public
  facade reg-view) via a function call so the React-context frame tier
  resolves to `:rf/causa` inside the leaf's subscribes."
  []
  (let [data            @(rf/subscribe [:rf.causa/reactive-data])
        local-toggle    @(rf/subscribe [:rf.causa/reactive-show-unchanged?])
        settings-pin    @(rf/subscribe [:rf.causa/setting :general :show-unchanged-subs?])
        show-unchanged? (or local-toggle (boolean settings-pin))]
    [:section {:data-testid "rf-causa-reactive"
               :style {:height "100%"
                       :display "flex"
                       :flex-direction "column"
                       :background (:bg-2 tokens)
                       :color (:text-primary tokens)
                       :font-family sans-stack
                       :font-size "14px"}}
     (header-block data)
     [:div {:style {:flex 1 :overflow "auto"}}
      (cond
        (not (:has-cascade? data))
        (empty-state data)

        :else
        [:<>
         (triggered-by-row data)
         (seed-paths-row data)
         (subs-recomputed-section data show-unchanged?)
         (views-rendered-section data)])]]))
