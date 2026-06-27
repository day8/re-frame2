(ns uix.dashboard.core
  "A UIx analytics dashboard — a grid of metric cards with sparklines,
   filter chips, and a time-range picker. Looks busy; it isn't.

   The whole app turns on one idea. Two controls each write a single
   value into app-db, and one `reg-sub` reads both and computes the
   visible card set as a pure function of them. So there's exactly one
   place where 'what's on screen' gets decided, and everything you see
   is just that one answer, rendered.

   What to look at:

     - `:dashboard/visible-metrics` — one derived sub fed by three
       inputs, re-running whenever a control moves
     - the derivation graph — base subs read app-db, derived subs read
       other subs (that's the `:<-` syntax), views sit at the leaves
     - UIx views (`defui`) pulling subscriptions in through `use-subscribe`
     - sparklines drawn as inline SVG from plain CLJS arithmetic — no
       chart library
     - two controls that re-derive the view: the filter chips (which
       cards show) and the range picker (how many trailing points each
       sparkline draws, plus the header label)

   The derivation graph is the concept worth slowing down for; the
   guide glossary has the full picture
   (../../../docs/guide/glossary.md#the-derivation-graph).

   The shared visual identity comes from examples/_shared/css/style.css."
  (:require [uix.core :refer [$ defui]]
            [uix.dom  :as uix-dom]
            [re-frame.core            :as rf]
            [re-frame.adapter.uix     :as uix-adapter]))

;; ============================================================================
;; SEED DATA
;; ============================================================================

(def initial-metrics
  ;; Six metrics, each carrying a 14-point sparkline series. The numbers
  ;; are hand-tuned to look interesting, not pulled from anything real.
  ;;
  ;; They're written as bare integers because CLJS has no
  ;; underscore-grouping (1_000) literal — you read 142375 the hard way.
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
  ;; The two time-range options. `:points` is the window: how many of the
  ;; 14 points each sparkline keeps (the last N). Pick a range and you
  ;; re-derive both the header label and every sparkline at once. Capped
  ;; at 14, since that's all the seed data carries.
  [{:id :w7  :label "7 days"  :points 7}
   {:id :w14 :label "14 days" :points 14}])

;; ============================================================================
;; EVENTS
;; ============================================================================

;; Three handlers, all pure: (coeffects, event-vector) -> effect map. Each
;; one moves a SINGLE value in app-db and then stops. Notice what they don't
;; do: turning "this tag is now off" into "these cards disappear" is the
;; subscription's job, not the handler's. The handler just records the fact.

;; Seed the whole dashboard in one write — the metrics, plus the two control
;; values: `:active-tags` is a set (chips are multi-select) and `:range` is a
;; single id.
(rf/reg-event :dashboard/initialise
  (fn [{:keys [db]} _event]
    {:db {:dashboard/metrics      initial-metrics
     :dashboard/active-tags  #{:money :perf :usage}
     :dashboard/range         :w14}}))

(rf/reg-event :dashboard/toggle-tag
  (fn [{:keys [db]} [_ tag]]
    {:db (update db :dashboard/active-tags
            (fn [s] (if (contains? s tag) (disj s tag) (conj s tag))))}))

(rf/reg-event :dashboard/set-range
  (fn [{:keys [db]} [_ range-id]]
    {:db (assoc db :dashboard/range range-id)}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :dashboard/metrics
  (fn [db _] (:dashboard/metrics db)))

(rf/reg-sub :dashboard/active-tags
  (fn [db _] (:dashboard/active-tags db)))

(rf/reg-sub :dashboard/range
  (fn [db _] (:dashboard/range db)))

;; Our first derived sub, and the place to meet `:<-`. It declares this
;; sub's input as ANOTHER sub rather than app-db. The base subs above read
;; app-db directly; from here on, subs read other subs. This one's job is
;; small: take the stored range id and look up its full record (label,
;; point count).
(rf/reg-sub :dashboard/selected-range
  :<- [:dashboard/range]
  (fn [range-id _]
    (some #(when (= range-id (:id %)) %) ranges)))

;; This is the heart of the example — the one function the whole UI reads to
;; learn what's on screen. It folds two unrelated inputs into one answer: the
;; active chips decide which cards belong (`filter`), and the selected range
;; decides how long each sparkline is (`take-last points`). Move either
;; control and the framework re-runs this — and only this, plus whatever
;; downstream actually changed. One pure function, one source of truth.
(rf/reg-sub :dashboard/visible-metrics
  :<- [:dashboard/metrics]
  :<- [:dashboard/active-tags]
  :<- [:dashboard/selected-range]
  (fn [[metrics active-tags {:keys [points]}] _]
    (->> metrics
         (filter #(contains? active-tags (:tag %)))
         (map (fn [metric] (update metric :series #(vec (take-last points %))))))))

;; ============================================================================
;; SPARKLINE PATH
;; ============================================================================

(defn- sparkline-path
  "Turn a series of numbers into an SVG <path> `d` string, scaled to fit a
   100×30 viewBox. Pure in, pure out; `sparkline` below draws it.

   Why a <path> and not a <polyline>: at this tiny size with a hair-thin
   stroke, one `M…L…L…` path renders noticeably crisper. The renderer
   isn't anti-aliasing every vertex twice, and your eye can tell."
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
  ;; This graphic is decorative, so we hide it from assistive tech with
  ;; `aria-hidden="true"`. The card's eyebrow, value, and label already say
  ;; the metric's name and number in text; the sparkline only restates that
  ;; visually. Left visible, a screen reader would announce a nameless
  ;; graphic on every card — noise. The accessible name lives in the text.
  ($ :svg.dash-sparkline
     {:viewBox "0 0 100 30"
      :preserveAspectRatio "none"
      :aria-hidden "true"
      :data-testid (str "dashboard-sparkline-" (name id))}
     ($ :path {:d (sparkline-path series)
               :fill "none"
               :stroke "currentColor"
               :stroke-width 1.5
               :stroke-linejoin "round"
               :stroke-linecap "round"
               :vector-effect "non-scaling-stroke"})))

(defui delta-badge [{:keys [delta good-when-positive?]}]
  ;; The arrow points the way the news points, not the way the number does.
  ;; So a green badge never shows a downward arrow, and vice versa. For a
  ;; metric where down is good (latency, errors), a drop reads as ▲ green —
  ;; which is exactly how a human would want to read it.
  (let [good? (if good-when-positive? (pos? delta) (neg? delta))
        pct   (-> delta (* 100) Math/abs (.toFixed 1))]
    ($ :span {:class (str "dash-delta " (if good? "is-good" "is-bad"))}
       (if good? "▲ " "▼ ") pct "%")))

(defn- format-value
  "Make a metric value presentable. Whole numbers get thousands separators
   (`142375` → `142,375`); fractional ones get two decimals (`3.8` →
   `3.80`). Both come free from JS interop — `.toLocaleString` and
   `.toFixed` — so there's no number-formatting library to pull in."
  [value]
  (if (integer? value)
    (.toLocaleString value "en-US")
    (.toFixed value 2)))

(defui metric-card [{:keys [metric]}]
  (let [{:keys [id label value unit delta tag series]} metric
        ;; Currency sits before the number ($142,375); everything else sits
        ;; after (24 ms, 0.42 %). The flag just names where the unit goes.
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
  ;; These are MULTI-select toggles — any subset of tags can be on at once.
  ;; Each chip is a real <button> carrying `aria-pressed`, which is the
  ;; state assistive tech actually reads; the `is-on` CSS class is just
  ;; paint. Wrapping the row in a `role="group"` with an `aria-label` lets a
  ;; screen reader announce one labelled set of toggles instead of three
  ;; loose buttons.
  (let [active-tags (uix-adapter/use-subscribe [:dashboard/active-tags])
        dispatch    (:dispatch (rf/capture-frame))]
    ($ :div.dash-chips {:role "group" :aria-label "Filter metrics by category"}
       (for [{:keys [id label]} all-tags]
         (let [on? (contains? active-tags id)]
           ($ :button {:key id
                       :type "button"
                       :class (str "dash-chip " (when on? "is-on"))
                       :aria-pressed (if on? "true" "false")
                       :data-testid (str "dashboard-chip-" (name id))
                       :on-click #(dispatch [:dashboard/toggle-tag id])}
              ($ :span.dash-chip-dot {:class (str "tag-" (name id))})
              label))))))

(defn- radio-key->step
  "Read a keydown into a navigation step (+1, -1) for the radio group, or
   nil if the key isn't one we steer with. The WAI-ARIA radio-group pattern
   moves selection on all four arrows: Right/Down go forward, Left/Up go
   back. Both axes, because the group wraps either way."
  [k]
  (case k
    ("ArrowRight" "ArrowDown") 1
    ("ArrowLeft"  "ArrowUp")  -1
    nil))

(defui range-picker []
  ;; Where the chips were multi-select, this is SINGLE-select: exactly one
  ;; range is active. So it's a radio group — and we do the real WAI-ARIA
  ;; idiom, not just the roles but the keyboard contract a radio group
  ;; quietly promises:
  ;;
  ;;   - `role="radiogroup"` on the row, plus `role="radio"` + `aria-checked`
  ;;     on each chip, so the whole thing announces as a one-of-N choice.
  ;;   - ROVING TABINDEX: only the checked radio is in the tab order
  ;;     (`tabIndex 0`), the rest are `tabIndex -1`. Tab lands on the current
  ;;     selection, not on every chip in turn.
  ;;   - ARROW-KEY NAVIGATION: Left/Up and Right/Down move the selection,
  ;;     with wrap. The catch is that in a radio group selection FOLLOWS
  ;;     focus, so an arrow has to do two things at once — dispatch the range
  ;;     change AND move DOM focus to the chip it just picked.
  ;;
  ;; As with the chips, `is-on` is paint only; `aria-checked` is the state
  ;; assistive tech reads.
  (let [active-range-id (uix-adapter/use-subscribe [:dashboard/range])
        dispatch        (:dispatch (rf/capture-frame))
        n               (count ranges)
        active-idx      (or (some (fn [[i {:keys [id]}]]
                                    (when (= active-range-id id) i))
                                  (map-indexed vector ranges))
                            0)
        select!         (fn [idx]
                          (let [idx (mod idx n)
                                {:keys [id]} (nth ranges idx)]
                            (dispatch [:dashboard/set-range id])
                            ;; Selection follows focus, so move the DOM focus
                            ;; onto the chip we just picked — that keeps the
                            ;; visible focus ring sitting on the live radio.
                            (when (exists? js/document)
                              (some-> (js/document.querySelector
                                        (str "[data-testid=\"dashboard-range-"
                                             (name id) "\"]"))
                                      (.focus)))))]
    ($ :div.dash-chips {:role "radiogroup" :aria-label "Time range"}
       (map-indexed
         (fn [idx {:keys [id label]}]
           (let [on? (= active-range-id id)]
             ($ :button {:key id
                         :type "button"
                         :role "radio"
                         ;; Roving tabindex in one line: only the checked
                         ;; radio is tabbable; arrows do the rest.
                         :tabIndex (if (= idx active-idx) 0 -1)
                         :class (str "dash-chip " (when on? "is-on"))
                         :aria-checked (if on? "true" "false")
                         :data-testid (str "dashboard-range-" (name id))
                         :on-click #(dispatch [:dashboard/set-range id])
                         :on-key-down (fn [e]
                                        (when-let [step (radio-key->step (.-key e))]
                                          (.preventDefault e)
                                          (select! (+ active-idx step))))}
                label)))
         ranges))))

(defui dashboard []
  (let [visible-metrics (uix-adapter/use-subscribe [:dashboard/visible-metrics])
        selected-range  (uix-adapter/use-subscribe [:dashboard/selected-range])]
    ($ :div.dash-shell
       ($ :header.dash-shell-head
          ($ :div
             ($ :h1 "Atlas")
             ($ :p.dash-tagline
                {:data-testid "dashboard-range-label"}
                (str "Last " (:label selected-range) " · ")
                ($ :span.dash-substrate-tag "UIx substrate")))
          ($ :div.dash-controls
             ($ range-picker)
             ($ filter-chips)))
       ($ :section.dash-grid
          {:data-testid "dashboard-grid"}
          (for [metric visible-metrics]
            ($ metric-card {:key (:id metric) :metric metric})))
       ($ :footer.dash-shell-foot
          ($ :span "re-frame2 · examples/substrates/uix/dashboard")))))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; We stash the React root in an atom and create it lazily inside `run`,
;; never at ns-load. The rule: loading this namespace must touch no DOM, so
;; that other example namespaces loaded alongside it don't race each other
;; to `create-root` onto the shared `#app` node (examples/TESTING.md,
;; Example mount-isolation convention).
(defonce react-root (atom nil))

;; The id of the frame this app lives in. The `frame-provider` down in `run`
;; creates it, seeds its app-db, and scopes it into React context — which is
;; how `use-subscribe` and `(rf/capture-frame)` find it. The guide glossary
;; covers frame-provider (../../../docs/guide/glossary.md#frame-provider).
(def app-frame :rf/default)

(defn run []
  ;; `init!` installs the UIx adapter — this is how re-frame2 learns which
  ;; substrate to render through.
  (rf/init! uix-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (uix-dom/create-root (js/document.getElementById "app"))))
    ;; Passing `{:id …}` creates the app frame on the first mount and fires
    ;; `:initial-events` once to seed app-db. Save and hot-reload, and it
    ;; reuses the same frame untouched — no re-seeding, so your state sticks.
    (uix-dom/render-root
      ($ uix-adapter/frame-provider {:id app-frame
                                     :initial-events [[:dashboard/initialise]]}
         ($ dashboard))
      @react-root)))
