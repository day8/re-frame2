(ns realworld-resources.ui-dashboard
  "A small metrics dashboard as an S3 coherence fixture — the counterpart to the
   counter, one step up in composition. It exists to show that the new language
   stays coherent as a page grows: several `defview`s compose, a keyed list calls
   an internal view, a controlled filter narrows the set, and navigation is an
   ordinary compiled `route-link`.

   The S3 surface it exercises, beyond the counter's:

   - **Composed views + a keyed list.** `dashboard` calls `dashboard-nav` and maps
     an internal `metric-card` view over the visible metrics with a stable `:key`.

   - **A justified `local`.** Whether the cards are shown \"compact\" (their +1
     control hidden) is VIEW-ONLY presentation state — no product meaning, not
     shared, never restored across an epoch. That is exactly what `local` is for,
     so the toggle lives in `(local false)`, not in app-db. Everything with
     product meaning (the metrics, the filter) stays in app-db behind events.

   - **`route-link` as an ordinary view.** `dashboard-nav` renders real
     `<a href>` navigation anchors that dispatch through the committed frame on a
     plain left click. The `:to` route ids are registered by the host/app.

   - **`effect` + `dispatch-fn`, the textbook way.** A window `keydown` listener
     lets Escape clear the filter. It is host-world wiring, so an `effect`
     attaches it and — crucially — the effect's cleanup removes it, so the
     `dispatch-fn` it fires can never outlive the view (the leaked-listener law).
     `dispatch-fn` is exactly for this: a stable committed-frame dispatcher handed
     to an external listener, retired in the same cleanup that created it.

   Dataflow is plain re-frame2 (`.cljc`, so it renders on the JVM structural host
   too); the views are pure functions of subs."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.ui :as ui :refer [defview sub local effect dispatch-fn]]))

;; ---- dataflow: plain re-frame2 --------------------------------------------

(rf/reg-event :ui-dash/init
  (fn [_ _]
    {:db {:ui-dash/metrics {:users   {:label "Active users"  :value 1280}
                            :orders  {:label "Orders today"  :value 312}
                            :revenue {:label "Revenue (USD)" :value 48210}
                            :errors  {:label "Error rate (%)" :value 3}}
          :ui-dash/order  [:users :orders :revenue :errors]
          :ui-dash/filter ""}}))

(rf/reg-event :ui-dash/set-filter
  (fn [{:keys [db]} [_ s]] {:db (assoc db :ui-dash/filter (or s ""))}))

(rf/reg-event :ui-dash/clear-filter
  (fn [{:keys [db]} _] {:db (assoc db :ui-dash/filter "")}))

(rf/reg-event :ui-dash/bump
  {:schema [:cat [:= :ui-dash/bump] :keyword :int]}
  (fn [{:keys [db]} [_ id delta]]
    {:db (update-in db [:ui-dash/metrics id :value] + delta)}))

(rf/reg-sub :ui-dash/filter (fn [db _] (:ui-dash/filter db "")))

(rf/reg-sub :ui-dash/visible
  {:doc "The metrics, in declared order, narrowed to those whose label contains
         the filter text — a seq of [id metric] pairs."}
  (fn [db _]
    (let [needle  (str/lower-case (:ui-dash/filter db ""))
          metrics (:ui-dash/metrics db)]
      (into []
            (comp (map (fn [id] [id (get metrics id)]))
                  (filter (fn [[_ m]] (str/includes? (str/lower-case (:label m)) needle))))
            (:ui-dash/order db)))))

(rf/reg-sub :ui-dash/summary
  (fn [db _]
    (let [ms (vals (:ui-dash/metrics db))]
      {:count (count ms)
       :total (reduce + 0 (map :value ms))})))

;; ---- views ----------------------------------------------------------------

(defview metric-card
  "One metric tile. A pure function of its props; the +1 control is hidden when
   the parent asks for a compact layout."
  [{:keys [id metric compact?]}]
  [:div.metric-card {:data-testid (str "metric-" (name id))}
   [:span.metric-label (:label metric)]
   [:span.metric-value {:data-testid (str "metric-" (name id) "-value")}
    (str (:value metric))]
   (when-not compact?
     [:button.btn {:data-testid (str "metric-" (name id) "-bump")
                   :on-click [:ui-dash/bump id 1]}
      "+1"])])

(defview dashboard-nav
  "Navigation as ordinary compiled `route-link`s — real anchors, committed-frame
   dispatch on a plain left click."
  []
  [:nav.dashboard-nav
   [ui/route-link {:to :ui-guide/dashboard :class "nav-link" :data-testid "nav-dashboard"}
    "Dashboard"]
   [ui/route-link {:to :ui-guide/counter :class "nav-link" :data-testid "nav-counter"}
    "Counter"]])

(defview dashboard
  "The dashboard page: nav, a controlled filter, a view-only compact toggle, the
   keyed metric grid, and a derived summary."
  []
  (let [visible  (sub [:ui-dash/visible])
        summary  (sub [:ui-dash/summary])
        filter-v (sub [:ui-dash/filter])
        ;; A VIEW-ONLY toggle — presentation ephemera, so `local`, not app-db.
        [compact? set-compact!] (local false)
        ;; A stable committed-frame dispatcher for the window listener below.
        clear    (dispatch-fn)]
    ;; Escape clears the filter. The listener lives on `window`, so an `effect`
    ;; owns it; the cleanup removes it, so `clear` never fires after the view
    ;; disconnects (the dispatch-fn leaked-listener contract).
    (effect :connect
      (let [on-key (fn [e]
                     (when (= "Escape" #?(:cljs (.-key ^js e) :clj nil))
                       (clear [:ui-dash/clear-filter])))]
        #?(:cljs (.addEventListener js/window "keydown" on-key))
        (fn [] #?(:cljs (.removeEventListener js/window "keydown" on-key) :clj nil))))
    [:div.dashboard {:data-testid "dashboard"}
     [dashboard-nav]
     [:div.dashboard-toolbar
      [:input.dashboard-filter
       {:type "text" :placeholder "Filter metrics…" :data-testid "dashboard-filter"
        :value filter-v
        :on-input [:ui-dash/set-filter :rf.ui/value]}]
      [:button.btn {:data-testid "dashboard-clear" :on-click [:ui-dash/clear-filter]}
       "Clear"]
      [:button.btn {:data-testid "dashboard-compact"
                    :on-click (ui/event [_] (set-compact! (not compact?)) nil)}
       (if compact? "Show controls" "Compact")]]
     [:div.dashboard-grid
      (for [[id metric] visible]
        [metric-card {:key id :id id :metric metric :compact? compact?}])]
     [:p.dashboard-summary {:data-testid "dashboard-summary"}
      (str (:count summary) " metrics · total " (str (:total summary)))]]))
