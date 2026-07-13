(ns re-frame.ui.g13.fixture
  "Shared G-13 push-economics fixture. The dev build retains gate-local
  evidence; the :advanced companion compiles the same view shape with every
  counter and Profiler branch closed. Nothing here instruments production
  re-frame.ui runtime code."
  (:require ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.ui :as ui :refer [defview]]))

(goog-define ^boolean instrumentation? false)

(def hot-count 8)
(def queued-writes 8)
;; `table` + `app` are intentionally sub-free defviews. DEV gives each the
;; fixed HMR-safe ViewCell skeleton; they must stay live but ownership-empty.
(def idle-shell-count 2)
(def counters (atom {}))

(defn reset-counters! []
  (reset! counters {:writes 0
                    :hot-base 0
                    :stable-parent 0
                    :cold-leaf 0
                    :hot-body 0
                    :hot-body-by-index (vec (repeat hot-count 0))
                    :cold-body 0
                    :commits 0}))

(defn counter-snapshot [] @counters)

(defn- count! [k]
  (when instrumentation?
    (swap! counters update k (fnil inc 0))))

(defn register! []
  ;; Literal sentinels are the non-vacuity control for the advanced scan.
  ;; With instrumentation? false Closure must remove this entire branch.
  (when instrumentation?
    (.debug js/console
            "RF2_G13_COUNTER_SENTINEL"
            "RF2_G13_PROFILER_SENTINEL"
            "RF2_G13_DEBUG_EVIDENCE_SENTINEL"))
  (rf/reg-sub ::hot
              (fn [db _]
                (count! :hot-base)
                (:hot db)))
  (rf/reg-sub ::stable-parent
              (fn [db _]
                (count! :stable-parent)
                (:cold db)))
  (rf/reg-sub ::cold
              :<- [::stable-parent]
              (fn [cold [_ i]]
                (count! :cold-leaf)
                (nth cold i)))
  (rf/reg-event ::step
                (fn [{:keys [db]} [_ remaining]]
                  (count! :writes)
                  (cond-> {:db (update db :hot inc)}
                    (> remaining 1)
                    (assoc :fx [[:dispatch [::step (dec remaining)]]]))))
  nil)

(defn seed [v]
  {:hot 0
   :cold (mapv #(str "cold-" %) (range v))})

(defview hot-row [{:keys [index]}]
  (let [_ (count! :hot-body)
        _ (when instrumentation?
            (swap! counters update-in [:hot-body-by-index index] inc))]
    [:output {:data-g13-kind "hot" :data-g13-index index}
     (str (ui/sub [::hot]))]))

(defview cold-row [{:keys [index]}]
  (let [_ (count! :cold-body)]
    [:output {:data-g13-kind "cold" :data-g13-index index}
     (ui/sub [::cold index])]))

(defview table [{:keys [v]}]
  [:section {:data-g13-ready "true" :data-g13-v v}
   (for [i (range hot-count)]
     [hot-row {:key i :index i}])
   (for [i (range hot-count v)]
     [cold-row {:key i :index i}])])

(defn- profiled [^js props]
  (React/createElement
   (.-Profiler React)
   #js {:id "g13-root"
        :onRender (fn [& _]
                    (count! :commits))}
   (React/createElement table #js {:v (.-v props)})))

(defview app [{:keys [v]}]
  (if instrumentation?
    (ui/raw (React/createElement profiled #js {:v v}))
    [table {:v v}]))
