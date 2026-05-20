(ns dashboard-uix.core
  "UIx design-led example — 'Analytics Dashboard'. A grid of metric cards
   + sparklines + filter chips. Proves re-frame2 + UIx can build a
   substantive UI (rf2-t7t6f).

   Demonstrates:

     - UIx components (`defui`) consuming subs via `use-subscribe`
     - signal-graph subscriptions (per-metric, per-range projections)
     - inline SVG sparklines computed in pure CLJS — no chart library
     - filter chips that re-derive the visible card set

   No HTTP, no state machines — the design-led examples per rf2-t7t6f
   exist to prove polished visuals + interaction, not to replay platform
   features other examples already cover. Distinct shape from the
   Reagent 'Notebook' (3-pane editor) and Helix 'Process Monitor'
   (terminal log viewer) per the cluster prompt — three different
   substantive UIs, one per substrate.

   The shared 'Editorial Warm' visual identity comes from
   examples/_shared/css/style.css (rf2-v4fpe Option 2 — one identity
   across all three substrates)."
  (:require [uix.core :as uix :refer [$ defui]]
            [uix.dom  :as uix-dom]
            [re-frame.core            :as rf]
            [re-frame.adapter.uix     :as uix-adapter]))

;; ============================================================================
;; SEED DATA
;; ============================================================================

(def initial-metrics
  ;; Each metric carries a 14-point sparkline series. Numbers are
  ;; hand-tuned for visual variety; nothing here is computed from a real
  ;; source — the example proves dataflow + render, not analytics
  ;; correctness.
  ;;
  ;; CLJS doesn't support Clojure's underscore-grouping (1_000) literal
  ;; syntax; values are bare integers.
  [{:id :revenue   :label "Revenue"       :value 142375 :unit "$" :delta  0.084 :tag :money
    :series [108 112 117 121 119 124 128 132 130 135 138 140 142 142]}
   {:id :signups   :label "New signups"   :value 1286   :unit ""  :delta  0.121 :tag :money
    :series [820 855 870 905 920 940 985 1010 1040 1090 1140 1180 1230 1286]}
   {:id :latency   :label "P50 latency"   :value 24     :unit "ms" :delta -0.045 :tag :perf
    :series [31 30 29 28 28 27 27 26 26 25 25 25 24 24]}
   {:id :errors    :label "Error rate"    :value 0.42   :unit "%"  :delta -0.073 :tag :perf
    :series [0.62 0.60 0.58 0.55 0.55 0.52 0.50 0.49 0.48 0.46 0.45 0.44 0.43 0.42]}
   {:id :dau       :label "DAU"           :value 24180  :unit ""  :delta 0.038 :tag :usage
    :series [22000 22300 22600 22900 23100 23400 23600 23700 23800 23900 24000 24100 24150 24180]}
   {:id :sessions  :label "Sessions / DAU" :value 3.8   :unit ""  :delta 0.012 :tag :usage
    :series [3.4 3.5 3.6 3.6 3.7 3.7 3.7 3.7 3.8 3.8 3.8 3.8 3.8 3.8]}])

(def all-tags
  [{:id :money :label "Revenue"}
   {:id :perf  :label "Performance"}
   {:id :usage :label "Usage"}])

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event-db :dashboard/initialise
  (fn [_db _event]
    {:dashboard/metrics      initial-metrics
     :dashboard/active-tags  #{:money :perf :usage}
     :dashboard/range         :w14}))

(rf/reg-event-db :dashboard/toggle-tag
  (fn [db [_ tag]]
    (update db :dashboard/active-tags
            (fn [s] (if (contains? s tag) (disj s tag) (conj s tag))))))

(rf/reg-event-db :dashboard/set-range
  (fn [db [_ r]]
    (assoc db :dashboard/range r)))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :dashboard/metrics
  (fn [db _] (:dashboard/metrics db)))

(rf/reg-sub :dashboard/active-tags
  (fn [db _] (:dashboard/active-tags db)))

(rf/reg-sub :dashboard/range
  (fn [db _] (:dashboard/range db)))

(rf/reg-sub :dashboard/visible-metrics
  :<- [:dashboard/metrics]
  :<- [:dashboard/active-tags]
  (fn [[ms tags] _]
    (filter #(contains? tags (:tag %)) ms)))

;; ============================================================================
;; SPARKLINE PATH
;; ============================================================================

(defn- sparkline-path
  "Return an SVG <path> `d` string for the series, normalised into a
   100×30 viewBox. Pure — used by `Sparkline` below.

   Why polyline-via-path: a single <path d=\"M…L…L…\"> renders crisper
   than <polyline> when the viewBox is small and the stroke is hair-thin
   (the renderer doesn't anti-alias every vertex twice)."
  [series]
  (let [n     (count series)
        lo    (apply min series)
        hi    (apply max series)
        span  (max 0.0001 (- hi lo))
        ->x   (fn [i] (* 100.0 (/ i (dec (max 2 n)))))
        ->y   (fn [v] (- 30 (* 30 (/ (- v lo) span))))
        head  (str "M" (->x 0) "," (->y (first series)))
        tail  (apply str
                (for [i (range 1 n)]
                  (str " L" (->x i) "," (->y (nth series i)))))]
    (str head tail)))

;; ============================================================================
;; VIEWS  (UIx — defui)
;; ============================================================================

(defui sparkline [{:keys [series id]}]
  ($ :svg.dash-sparkline
     {:viewBox "0 0 100 30"
      :preserveAspectRatio "none"
      :role "img"
      :aria-label "sparkline"
      :data-testid (str "dashboard-sparkline-" (name id))}
     ($ :path {:d (sparkline-path series)
               :fill "none"
               :stroke "currentColor"
               :stroke-width 1.5
               :stroke-linejoin "round"
               :stroke-linecap "round"
               :vector-effect "non-scaling-stroke"})))

(defui delta-badge [{:keys [delta good-when-positive?]}]
  (let [pos?      (pos? delta)
        positive? (if good-when-positive? pos? (not pos?))
        pct       (-> delta (* 100) Math/abs (.toFixed 1))]
    ($ :span {:class (str "dash-delta " (if positive? "is-good" "is-bad"))}
       (if pos? "▲ " "▼ ") pct "%")))

(defui metric-card [{:keys [m]}]
  (let [{:keys [id label value unit delta tag series]} m
        money? (= :money tag)
        perf?  (= :perf tag)]
    ($ :article.dash-card
       {:data-testid (str "dashboard-card-" (name id))}
       ($ :header.dash-card-head
          ($ :span.dash-eyebrow (name tag))
          ($ delta-badge {:delta delta
                          :good-when-positive? (not perf?)}))
       ($ :div.dash-card-value
          (when money? ($ :span.dash-unit unit))
          ($ :span.dash-value
             {:data-testid (str "dashboard-value-" (name id))}
             (cond
               (integer? value)            (str value)
               (and (number? value)
                    (< value 100))         (.toFixed value 2)
               :else                       (str value)))
          (when (and (not money?) (seq unit))
            ($ :span.dash-unit unit)))
       ($ :div.dash-card-label label)
       ($ sparkline {:series series :id id}))))

(defui filter-chips []
  (let [active   (uix-adapter/use-subscribe [:dashboard/active-tags])
        dispatch (rf/dispatcher)]
    ($ :div.dash-chips
       (for [{:keys [id label]} all-tags]
         ($ :button {:key id
                     :class (str "dash-chip " (when (contains? active id) "is-on"))
                     :data-testid (str "dashboard-chip-" (name id))
                     :on-click #(dispatch [:dashboard/toggle-tag id])}
            ($ :span.dash-chip-dot {:class (str "tag-" (name id))})
            label)))))

(defui dashboard []
  (let [visible (uix-adapter/use-subscribe [:dashboard/visible-metrics])]
    ($ :div.dash-shell
       ($ :header.dash-shell-head
          ($ :div
             ($ :h1 "Atlas")
             ($ :p.dash-tagline
                "Last 14 days · "
                ($ :span.dash-substrate-tag "UIx substrate")))
          ($ filter-chips))
       ($ :section.dash-grid
          {:data-testid "dashboard-grid"}
          (for [m visible]
            ($ metric-card {:key (:id m) :m m})))
       ($ :footer.dash-shell-foot
          ($ :span "re-frame2 · examples/uix/dashboard_uix")))))

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (uix-dom/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! uix-adapter/adapter)
  (rf/dispatch-sync [:dashboard/initialise])
  (uix-dom/render-root ($ dashboard) react-root))
