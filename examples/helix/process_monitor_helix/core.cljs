(ns process-monitor-helix.core
  "Helix design-led example — 'Process Monitor'. Terminal-style two-pane
   layout: filterable process list on the left + live log feed on the
   right, with status tiles across the top. Proves re-frame2 + Helix can
   build a substantive UI (rf2-t7t6f).

   Demonstrates:

     - Helix components (`defnc`) consuming subs via `use-subscribe`
     - signal-graph subscriptions (filter chips → visible process list,
       process selection → relevant log slice)
     - a recurring `:dispatch-later` tick that appends synthetic log
       lines (`:monitor/tick`) — proves a real reactive loop, not a
       static screenshot
     - per-row dispatch from inside a `defnc` row component

   No HTTP, no state machines — design-led examples per rf2-t7t6f
   prove polished visuals + interaction, not platform features other
   examples already cover. Distinct shape from the Reagent 'Notebook'
   (3-pane editor) and UIx 'Atlas' dashboard (cards + sparklines).

   The shared 'Editorial Warm' visual identity comes from
   examples/_shared/css/style.css (rf2-v4fpe Option 2 — one identity
   across all three substrates)."
  (:require ["react-dom/client" :as react-dom-client]
            [helix.core         :refer [$ defnc]]
            [helix.dom          :as d]
            [helix.hooks        :as helix-hooks]
            [re-frame.core      :as rf]
            [re-frame.adapter.helix :as helix-adapter]))

;; ============================================================================
;; SEED DATA
;; ============================================================================

(def initial-processes
  [{:id :web        :name "web-frontend"   :status :running :cpu 12.4 :mem 184  :pid 13287}
   {:id :api        :name "api-gateway"    :status :running :cpu 38.2 :mem 412  :pid 13288}
   {:id :db         :name "postgres"       :status :running :cpu  6.1 :mem 856  :pid 13289}
   {:id :cache      :name "redis"          :status :running :cpu  2.0 :mem  64  :pid 13290}
   {:id :worker     :name "task-worker"    :status :warn    :cpu 71.5 :mem 312  :pid 13291}
   {:id :search     :name "search-indexer" :status :running :cpu 22.7 :mem 248  :pid 13292}
   {:id :scheduler  :name "scheduler"      :status :running :cpu  1.2 :mem  48  :pid 13293}
   {:id :legacy     :name "legacy-export"  :status :down    :cpu  0.0 :mem   0  :pid 13294}])

(def initial-logs
  [{:t 0  :pid 13287 :lvl :info  :msg "GET / 200 14ms"}
   {:t 1  :pid 13288 :lvl :info  :msg "POST /v1/users 201 38ms"}
   {:t 2  :pid 13291 :lvl :warn  :msg "queue depth high: 412 jobs pending"}
   {:t 3  :pid 13289 :lvl :info  :msg "checkpoint complete (2.4G, 184ms)"}
   {:t 4  :pid 13288 :lvl :info  :msg "GET /v1/orders 200 22ms"}
   {:t 5  :pid 13294 :lvl :error :msg "connection refused: legacy-export → s3"}
   {:t 6  :pid 13287 :lvl :info  :msg "GET /static/main.css 304 4ms"}
   {:t 7  :pid 13292 :lvl :info  :msg "indexed 144 docs (412ms)"}
   {:t 8  :pid 13290 :lvl :info  :msg "cache hit rate 94.2%"}
   {:t 9  :pid 13288 :lvl :info  :msg "POST /v1/auth/login 200 51ms"}
   {:t 10 :pid 13291 :lvl :warn  :msg "worker pool saturated 8/8"}])

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event-fx :monitor/initialise
  (fn [_ctx _event]
    {:db {:monitor/processes initial-processes
          :monitor/logs      initial-logs
          :monitor/clock     11
          :monitor/level-filter #{:info :warn :error}
          :monitor/selected   nil}
     :fx [[:dispatch-later {:ms 1800 :event [:monitor/tick]}]]}))

(rf/reg-event-fx :monitor/tick
  (fn [{:keys [db]} _event]
    (let [t   (:monitor/clock db)
          procs (:monitor/processes db)
          ;; Pick a random process for the synthetic log line.
          proc  (nth procs (mod t (count procs)))
          lvl   (cond
                  (= :down (:status proc))    :error
                  (and (= :warn (:status proc))
                       (zero? (mod t 3)))      :warn
                  (zero? (mod t 7))            :warn
                  :else                        :info)
          phrases [(str "GET /v1/" (name (:id proc)) " 200 " (+ 8 (mod t 40)) "ms")
                   (str "POST /v1/" (name (:id proc)) "/event 202 " (+ 12 (mod t 30)) "ms")
                   (str "queue " (:name proc) " depth=" (mod t 50))
                   (str "checkpoint " (:name proc) " " (+ 100 (mod t 200)) "ms")]
          msg   (nth phrases (mod t (count phrases)))
          new   {:t t :pid (:pid proc) :lvl lvl :msg msg}]
      {:db (-> db
               (update :monitor/logs (fn [logs]
                                       (vec (take-last 60 (conj logs new)))))
               (update :monitor/clock inc))
       :fx [[:dispatch-later {:ms 1800 :event [:monitor/tick]}]]})))

(rf/reg-event-db :monitor/toggle-level
  (fn [db [_ lvl]]
    (update db :monitor/level-filter
            (fn [s] (if (contains? s lvl) (disj s lvl) (conj s lvl))))))

(rf/reg-event-db :monitor/select-process
  (fn [db [_ id]]
    (assoc db :monitor/selected
              (if (= id (:monitor/selected db)) nil id))))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :monitor/processes
  (fn [db _] (:monitor/processes db)))

(rf/reg-sub :monitor/logs
  (fn [db _] (:monitor/logs db)))

(rf/reg-sub :monitor/level-filter
  (fn [db _] (:monitor/level-filter db)))

(rf/reg-sub :monitor/selected
  (fn [db _] (:monitor/selected db)))

(rf/reg-sub :monitor/totals
  :<- [:monitor/processes]
  (fn [procs _]
    {:running (count (filter #(= :running (:status %)) procs))
     :warn    (count (filter #(= :warn (:status %)) procs))
     :down    (count (filter #(= :down (:status %)) procs))
     :cpu     (reduce + 0 (map :cpu procs))
     :mem     (reduce + 0 (map :mem procs))}))

(rf/reg-sub :monitor/visible-logs
  :<- [:monitor/logs]
  :<- [:monitor/level-filter]
  :<- [:monitor/selected]
  :<- [:monitor/processes]
  (fn [[logs lvls sel procs] _]
    (let [sel-pid (when sel
                    (some #(when (= (:id %) sel) (:pid %)) procs))]
      (->> logs
           (filter #(contains? lvls (:lvl %)))
           (filter #(or (nil? sel-pid) (= (:pid %) sel-pid)))
           (take-last 40)
           reverse))))

;; ============================================================================
;; VIEWS (Helix — defnc)
;; ============================================================================

(defnc tile [{:keys [label value tone]}]
  (d/div {:class (str "pm-tile pm-tile-" (when tone (name tone)))}
    (d/div {:class "pm-tile-label"} label)
    (d/div {:class "pm-tile-value"} value)))

(defnc tiles []
  (let [t (helix-adapter/use-subscribe [:monitor/totals])]
    (d/div {:class "pm-tiles"}
      ($ tile {:label "Running" :value (:running t) :tone :good})
      ($ tile {:label "Warning" :value (:warn t)    :tone :warn})
      ($ tile {:label "Down"    :value (:down t)    :tone :bad})
      ($ tile {:label "Σ CPU"   :value (str (.toFixed (:cpu t) 1) "%")})
      ($ tile {:label "Σ MEM"   :value (str (:mem t) "M")}))))

(defnc level-chips []
  (let [active   (helix-adapter/use-subscribe [:monitor/level-filter])
        dispatch (rf/dispatcher)]
    (d/div {:class "pm-chips"}
      (for [lvl [:info :warn :error]]
        (d/button {:key   (name lvl)
                   :class (str "pm-chip pm-chip-" (name lvl)
                               (when (contains? active lvl) " is-on"))
                   :data-testid (str "monitor-chip-" (name lvl))
                   :on-click #(dispatch [:monitor/toggle-level lvl])}
          (name lvl))))))

(defnc process-row [{:keys [p selected?]}]
  (let [dispatch (rf/dispatcher)
        {:keys [id name status cpu mem pid]} p
        cpu-pct (min 100 cpu)]
    (d/li {:class (str "pm-row"
                       " pm-row-" (clojure.core/name status)
                       (when selected? " is-selected"))
           :data-testid (str "monitor-row-" (clojure.core/name id))
           :on-click #(dispatch [:monitor/select-process id])}
      (d/span {:class (str "pm-dot pm-dot-" (clojure.core/name status))})
      (d/span {:class "pm-row-name"} name)
      (d/span {:class "pm-row-pid"} (str "[" pid "]"))
      (d/div  {:class "pm-row-meter"}
        (d/div {:class "pm-row-bar"
                :style {:width (str cpu-pct "%")}}))
      (d/span {:class "pm-row-cpu"} (str (.toFixed cpu 1) "%"))
      (d/span {:class "pm-row-mem"} (str mem "M")))))

(defnc process-list []
  (let [procs (helix-adapter/use-subscribe [:monitor/processes])
        sel   (helix-adapter/use-subscribe [:monitor/selected])]
    (d/section {:class "pm-pane pm-pane-procs"}
      (d/header {:class "pm-pane-head"}
        (d/h3 "processes")
        (d/span {:class "pm-pane-hint"} "click to filter logs"))
      (d/ul {:class "pm-list"
             :data-testid "monitor-process-list"}
        (for [p procs]
          ($ process-row {:key (:id p) :p p :selected? (= sel (:id p))}))))))

(defnc log-row [{:keys [e]}]
  (let [{:keys [t pid lvl msg]} e]
    (d/li {:class (str "pm-log pm-log-" (name lvl))}
      (d/span {:class "pm-log-t"}   (str "t=" t))
      (d/span {:class "pm-log-pid"} (str "[" pid "]"))
      (d/span {:class (str "pm-log-lvl pm-log-lvl-" (name lvl))} (name lvl))
      (d/span {:class "pm-log-msg"} msg))))

(defnc log-stream []
  (let [entries (helix-adapter/use-subscribe [:monitor/visible-logs])]
    (d/section {:class "pm-pane pm-pane-logs"}
      (d/header {:class "pm-pane-head"}
        (d/h3 "log stream")
        ($ level-chips))
      (d/ul {:class "pm-loglist"
             :data-testid "monitor-log-list"}
        (for [e entries]
          ($ log-row {:key (str (:t e) "-" (:pid e)) :e e}))))))

(defnc monitor []
  (d/div {:class "pm-shell"}
    (d/header {:class "pm-shell-head"}
      (d/div {:class "pm-brand"}
        (d/span {:class "pm-prompt"} "$ ")
        (d/h1 "process-monitor")
        (d/span {:class "pm-substrate"} " — helix substrate"))
      ($ tiles))
    (d/main {:class "pm-grid"}
      ($ process-list)
      ($ log-stream))))

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce root
  (react-dom-client/createRoot (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! helix-adapter/adapter)
  (rf/dispatch-sync [:monitor/initialise])
  (.render root ($ monitor)))
