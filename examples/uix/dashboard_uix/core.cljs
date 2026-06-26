(ns dashboard-uix.core
  "UIx design-led example — 'Analytics Dashboard'. A grid of metric cards
   + sparklines + filter chips. Proves re-frame2 + UIx can build a
   substantive UI.

   The one idea: two independent controls each set a single value in
   app-db, and one `reg-sub` reads both and computes the visible card
   set as a pure function of them. The whole UI renders that one
   projection — there is exactly one place where 'what's on screen' is
   decided.

   Demonstrates:

     - `reg-sub` composing inputs: `:dashboard/visible-metrics` derives
       from three input subscriptions, re-running when either control moves
     - the derivation graph — base subs read app-db, derived subs read
       other subs (the `:<-` syntax), views at the leaves
     - UIx views (`defui`) reading subscriptions via `use-subscribe`
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

;; Each handler is pure: (coeffects, event-vector) -> effect map. Each moves
;; ONE value in app-db and stops. The cards, sparklines, and grid all follow
;; downstream from the subscription — turning "this tag is now off" into
;; "these cards disappear" is the subscription's job, not the handler's.

;; Seed the whole dashboard in one write: the metrics plus the two control
;; values (`:active-tags` a set — chips are multi-select; `:range` one id).
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

;; First derived sub: `:<-` declares this sub's input as ANOTHER sub, not
;; app-db. Here we turn the stored range id into its full record (label,
;; point count). Base subs above read app-db; these read other subs.
(rf/reg-sub :dashboard/selected-range
  :<- [:dashboard/range]
  (fn [range-id _]
    (some #(when (= range-id (:id %)) %) ranges)))

;; The heart of the example. One projection of two independent inputs: the
;; active tag chips decide membership (`filter`); the selected range decides
;; each sparkline's length (`take-last points`). Changing either input
;; re-runs this — and ONLY this, plus anything downstream that moved — so the
;; UI reads "what's on screen" from a single pure function.
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
  "Return an SVG <path> `d` string for the series, normalised into a
   100×30 viewBox. Pure — used by `sparkline` below.

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
  ;; The card's <header> eyebrow, value, and label already carry the
  ;; metric's name + current value as text, so the sparkline is a
  ;; decorative restatement of that information for assistive tech.
  ;; `aria-hidden="true"` (rather than a generic `role="img"`
  ;; `aria-label="sparkline"`) keeps a screen reader from announcing a
  ;; nameless graphic on every card — the accessible name lives on the
  ;; surrounding text, which is where the information actually is.
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

(defui metric-card [{:keys [metric]}]
  (let [{:keys [id label value unit delta tag series]} metric
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
  ;; MULTI-select toggles: any subset of tags can be on at once. Each chip
  ;; is a real <button> carrying `aria-pressed` — the value assistive tech
  ;; reads as the on/off state (the `is-on` CSS class is presentation only).
  ;; The chips share a `role="group"` with an `aria-label` so a screen
  ;; reader announces them as one labelled set of toggles, not three loose
  ;; buttons.
  (let [active-tags (uix-adapter/use-subscribe [:dashboard/active-tags])
        dispatch    (:dispatch (rf/frame-handle))]
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
  "Map a keydown event's key to the radio-group navigation step, or nil
   when the key is not a navigation key. The WAI-ARIA radio-group pattern
   moves selection on the four arrow keys: Right/Down advance, Left/Up
   retreat (both axes, because the group can wrap either way)."
  [k]
  (case k
    ("ArrowRight" "ArrowDown") 1
    ("ArrowLeft"  "ArrowUp")  -1
    nil))

(defui range-picker []
  ;; SINGLE-select mode control: exactly one range is active. This is the
  ;; real WAI-ARIA radio-group idiom — not just the roles, but the
  ;; keyboard contract a radio group promises:
  ;;
  ;;   - `role="radiogroup"` on the row, `role="radio"` + `aria-checked`
  ;;     on each chip, so it announces as a one-of-N choice.
  ;;   - ROVING TABINDEX: exactly the checked radio is in the tab order
  ;;     (`tabIndex 0`); the rest are `tabIndex -1`. Tab lands on the
  ;;     selection, not on each chip in turn.
  ;;   - ARROW-KEY NAVIGATION: Left/Up and Right/Down move the selection
  ;;     (with wrap). Per the pattern, selection FOLLOWS focus in a radio
  ;;     group, so an arrow both dispatches the range change and moves
  ;;     DOM focus to the newly-selected chip.
  ;;
  ;; The `is-on` class stays presentation-only — `aria-checked` is the
  ;; state assistive tech reads.
  (let [active-range-id (uix-adapter/use-subscribe [:dashboard/range])
        dispatch        (:dispatch (rf/frame-handle))
        n               (count ranges)
        active-idx      (or (some (fn [[i {:keys [id]}]]
                                    (when (= active-range-id id) i))
                                  (map-indexed vector ranges))
                            0)
        select!         (fn [idx]
                          (let [idx (mod idx n)
                                {:keys [id]} (nth ranges idx)]
                            (dispatch [:dashboard/set-range id])
                            ;; Selection follows focus: move DOM focus to
                            ;; the chip we just selected so the visible
                            ;; focus ring tracks the radio state.
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
                         ;; Roving tabindex: only the checked radio is
                         ;; tabbable; arrows move within the group.
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
          ($ :span "re-frame2 · examples/uix/dashboard_uix")))))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
;; This matches the sibling notebook / process_monitor_helix mount shape.
(defonce react-root (atom nil))

;; The frame id this app runs under. The `frame-provider` at the render root
;; (in `run` below) does the frame work: it creates this frame, seeds app-db,
;; and scopes the frame into React context for `use-subscribe` and
;; `(rf/frame-handle)`.
(def app-frame :rf/default)

(defn run []
  ;; `init!` installs the UIx adapter so re-frame2 knows how to render.
  (rf/init! uix-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (uix-dom/create-root (js/document.getElementById "app"))))
    ;; The `frame-provider` is the one spot the frame is set up. `{:id …}`
    ;; creates the app frame on first mount and runs `:initial-events` once
    ;; to seed app-db; a hot reload reuses the same frame without re-seeding.
    ;; Everything under it reads from this frame.
    (uix-dom/render-root
      ($ uix-adapter/frame-provider {:id app-frame
                                     :initial-events [[:dashboard/initialise]]}
         ($ dashboard))
      @react-root)))
