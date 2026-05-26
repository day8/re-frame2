(ns dashboard-uix.core
  "UIx design-led example — 'Analytics Dashboard'. A grid of metric cards
   + sparklines + filter chips. Proves re-frame2 + UIx can build a
   substantive UI.

   Demonstrates:

     - UIx components (`defui`) consuming subs via `use-subscribe`
     - signal-graph subscriptions composing tag + range inputs into one
       re-derived projection (`:dashboard/visible-metrics`)
     - inline SVG sparklines computed in pure CLJS — no chart library
     - two re-deriving controls: filter chips (which cards show) and a
       time-range picker (how many trailing points each sparkline draws,
       plus the header label)

   No HTTP, no state machines — design-led examples exist to prove
   polished visuals + interaction, not to replay platform features
   other examples already cover. Distinct shape from the Reagent
   'Notebook' (3-pane editor) and Helix 'Process Monitor' (terminal
   log viewer) — three different substantive UIs, one per substrate.

   The shared 'Editorial Warm' visual identity comes from
   examples/_shared/css/style.css — one identity across all three
   substrates."
  (:require [uix.core :refer [$ defui]]
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

(def ranges
  ;; Time-range options. `:points` is how many of each metric's 14-point
  ;; series the range windows in to (the last N) — so picking a range
  ;; re-derives both the header label and every sparkline. Capped at the
  ;; 14 points the seed data actually carries; we don't fabricate history
  ;; we don't have.
  [{:id :w7  :label "7 days"  :points 7}
   {:id :w14 :label "14 days" :points 14}])

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

(rf/reg-sub :dashboard/selected-range
  :<- [:dashboard/range]
  (fn [r _]
    (some #(when (= r (:id %)) %) ranges)))

(rf/reg-sub :dashboard/visible-metrics
  ;; Two re-deriving inputs feed the visible card set: the active tag chips
  ;; (which metrics show) and the selected range (how many trailing points
  ;; each sparkline draws). Changing either re-runs this projection.
  :<- [:dashboard/metrics]
  :<- [:dashboard/active-tags]
  :<- [:dashboard/selected-range]
  (fn [[ms tags {:keys [points]}] _]
    (->> ms
         (filter #(contains? tags (:tag %)))
         (map (fn [m] (update m :series #(vec (take-last points %))))))))

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
  ;; The arrow follows the *good/bad* reading, not the raw sign, so a
  ;; green badge never shows a downward arrow (and vice versa). For a
  ;; down-is-good metric (latency, errors) a fall reads as ▲ green.
  (let [good? (if good-when-positive? (pos? delta) (neg? delta))
        pct   (-> delta (* 100) Math/abs (.toFixed 1))]
    ($ :span {:class (str "dash-delta " (if good? "is-good" "is-bad"))}
       (if good? "▲ " "▼ ") pct "%")))

(defn- format-value
  "Render a metric value for display. Integers are grouped with thousands
   separators (`142375` → `142,375`); fractional values get two decimals
   (`3.8` → `3.80`). `.toLocaleString` / `.toFixed` are zero-dependency
   JS interop — no number-formatting library."
  [value]
  (if (integer? value)
    (.toLocaleString value "en-US")
    (.toFixed value 2)))

(defui metric-card [{:keys [m]}]
  (let [{:keys [id label value unit delta tag series]} m
        ;; `$` renders before the value (a prefix unit); `ms` / `%` render
        ;; after. The flag names the placement, decoupled from the tag.
        prefix-unit? (= "$" unit)
        perf?        (= :perf tag)]
    ($ :article.dash-card
       {:data-testid (str "dashboard-card-" (name id))}
       ($ :header.dash-card-head
          ($ :span.dash-eyebrow (name tag))
          ($ delta-badge {:delta delta
                          :good-when-positive? (not perf?)}))
       ($ :div.dash-card-value
          (when prefix-unit? ($ :span.dash-unit unit))
          ($ :span.dash-value
             {:data-testid (str "dashboard-value-" (name id))}
             (format-value value))
          (when (and (not prefix-unit?) (seq unit))
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

(defui range-picker []
  (let [active   (uix-adapter/use-subscribe [:dashboard/range])
        dispatch (rf/dispatcher)]
    ($ :div.dash-chips
       (for [{:keys [id label]} ranges]
         ($ :button {:key id
                     :class (str "dash-chip " (when (= active id) "is-on"))
                     :data-testid (str "dashboard-range-" (name id))
                     :on-click #(dispatch [:dashboard/set-range id])}
            label)))))

(defui dashboard []
  (let [visible    (uix-adapter/use-subscribe [:dashboard/visible-metrics])
        sel-range  (uix-adapter/use-subscribe [:dashboard/selected-range])]
    ($ :div.dash-shell
       ($ :header.dash-shell-head
          ($ :div
             ($ :h1 "Atlas")
             ($ :p.dash-tagline
                {:data-testid "dashboard-range-label"}
                (str "Last " (:label sel-range) " · ")
                ($ :span.dash-substrate-tag "UIx substrate")))
          ($ :div.dash-controls
             ($ range-picker)
             ($ filter-chips)))
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

(defn run []
  (rf/init! uix-adapter/adapter)
  (rf/dispatch-sync [:dashboard/initialise])
  (uix-dom/render-root ($ dashboard) react-root))
