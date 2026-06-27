(ns process-monitor-helix.core
  "Helix example — 'Process Monitor'. A terminal-style two-pane layout: a
   filterable process list on the left, a live log feed on the right, and
   status tiles across the top.

   What it shows:

     - Helix components (`defnc`) reading subscriptions via `use-subscribe`
     - a derivation graph: two UI inputs (the level-filter chips, the
       selected process) fold into one derived subscription — the visible
       log slice. It recomputes only when an input changes.
     - a recurring `:dispatch-later` tick that appends synthetic log lines
       (`:process-monitor/tick`), driven by the component lifecycle and kept
       to exactly one live chain by a generation guard
     - per-row dispatch from inside a `defnc` row component

   The 'Editorial Warm' visual identity comes from
   examples/_shared/css/style.css, shared across all three substrates."
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
  [{:tick 0  :pid 13287 :level :info  :msg "GET / 200 14ms"}
   {:tick 1  :pid 13288 :level :info  :msg "POST /v1/users 201 38ms"}
   {:tick 2  :pid 13291 :level :warn  :msg "queue depth high: 412 jobs pending"}
   {:tick 3  :pid 13289 :level :info  :msg "checkpoint complete (2.4G, 184ms)"}
   {:tick 4  :pid 13288 :level :info  :msg "GET /v1/orders 200 22ms"}
   {:tick 5  :pid 13294 :level :error :msg "connection refused: legacy-export → s3"}
   {:tick 6  :pid 13287 :level :info  :msg "GET /static/main.css 304 4ms"}
   {:tick 7  :pid 13292 :level :info  :msg "indexed 144 docs (412ms)"}
   {:tick 8  :pid 13290 :level :info  :msg "cache hit rate 94.2%"}
   {:tick 9  :pid 13288 :level :info  :msg "POST /v1/auth/login 200 51ms"}
   {:tick 10 :pid 13291 :level :warn  :msg "worker pool saturated 8/8"}])

;; ============================================================================
;; EVENTS
;; ============================================================================
;;
;; The tick chain is a self-rescheduling `:dispatch-later`. That effect has no
;; cancel API, so a "generation" counter (`:process-monitor/tick-gen`) keeps the
;; chain to exactly one live loop:
;;
;;   - Each scheduled tick carries the generation it was armed under.
;;   - `:process-monitor/initialise` and `:process-monitor/stop` bump the generation.
;;   - A tick whose generation no longer matches `db` no-ops: no append, no
;;     reschedule. So any chain from a previous arm dies on its next fire.
;;
;; The loop is tied to the `monitor` component lifecycle (see its `use-effect`
;; below): mount arms it via `:process-monitor/initialise`, unmount stops it via
;; `:process-monitor/stop`. A re-mount re-arms cleanly; an unmount leaves no chain
;; dispatching into a frame nobody is rendering.

(rf/reg-event :process-monitor/initialise
  (fn [{:keys [db]} _event]
    (let [next-gen (inc (:process-monitor/tick-gen db 0))]
      {:db {:process-monitor/processes initial-processes
            :process-monitor/logs      initial-logs
            :process-monitor/clock     11
            :process-monitor/level-filter #{:info :warn :error}
            :process-monitor/selected   nil
            :process-monitor/tick-gen   next-gen}
       ;; Arm the loop by describing the first tick as data — the handler
       ;; never touches setTimeout, the :dispatch-later effect does. The tick
       ;; carries `next-gen` so a later arm can retire it.
       :fx [[:dispatch-later {:ms 1800 :event [:process-monitor/tick next-gen]}]]})))

(rf/reg-event :process-monitor/tick
  (fn [{:keys [db]} [_ gen]]
    (if (not= gen (:process-monitor/tick-gen db))
      ;; Generation no longer matches: this tick belongs to a retired chain.
      ;; Drop it — no log append, no clock bump, no reschedule.
      {}
      (let [tick      (:process-monitor/clock db)
            processes (:process-monitor/processes db)
            ;; Pick a process round-robin for the synthetic log line.
            process   (nth processes (mod tick (count processes)))
            level     (cond
                        (= :down (:status process))     :error
                        (and (= :warn (:status process))
                             (zero? (mod tick 3)))      :warn
                        (zero? (mod tick 7))            :warn
                        :else                           :info)
            phrases [(str "GET /v1/" (name (:id process)) " 200 " (+ 8 (mod tick 40)) "ms")
                     (str "POST /v1/" (name (:id process)) "/event 202 " (+ 12 (mod tick 30)) "ms")
                     (str "queue " (:name process) " depth=" (mod tick 50))
                     (str "checkpoint " (:name process) " " (+ 100 (mod tick 200)) "ms")]
            msg       (nth phrases (mod tick (count phrases)))
            new-entry {:tick tick :pid (:pid process) :level level :msg msg}]
        {:db (-> db
                 (update :process-monitor/logs (fn [logs]
                                         (vec (take-last 60 (conj logs new-entry)))))
                 (update :process-monitor/clock inc))
         ;; Reschedule under the same generation, so the chain continues until
         ;; a fresher arm retires it.
         :fx [[:dispatch-later {:ms 1800 :event [:process-monitor/tick gen]}]]}))))

;; Stops the loop. Bumps the generation but arms no new tick, so the live
;; chain's next fire no-ops. Dispatched from the `monitor` component's
;; `use-effect` cleanup on unmount.
(rf/reg-event :process-monitor/stop
  (fn [{:keys [db]} _event]
    {:db (update db :process-monitor/tick-gen (fnil inc 0))}))

(rf/reg-event :process-monitor/toggle-level
  (fn [{:keys [db]} [_ level]]
    {:db (update db :process-monitor/level-filter
            (fn [levels] (if (contains? levels level)
                           (disj levels level)
                           (conj levels level))))}))

(rf/reg-event :process-monitor/select-process
  (fn [{:keys [db]} [_ id]]
    {:db (assoc db :process-monitor/selected
              (if (= id (:process-monitor/selected db)) nil id))}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :process-monitor/processes
  (fn [db _] (:process-monitor/processes db)))

(rf/reg-sub :process-monitor/logs
  (fn [db _] (:process-monitor/logs db)))

(rf/reg-sub :process-monitor/level-filter
  (fn [db _] (:process-monitor/level-filter db)))

(rf/reg-sub :process-monitor/selected
  (fn [db _] (:process-monitor/selected db)))

;; A derived subscription built on the base subs above. `:<-` names its
;; inputs; the runtime recomputes it only when an input changes by `=`, so the
;; tiles don't re-render when an unrelated key moves. See the glossary on the
;; derivation graph: docs/guide/glossary.md#the-derivation-graph
(rf/reg-sub :process-monitor/totals
  :<- [:process-monitor/processes]
  (fn [processes _]
    {:running (count (filter #(= :running (:status %)) processes))
     :warn    (count (filter #(= :warn (:status %)) processes))
     :down    (count (filter #(= :down (:status %)) processes))
     :cpu     (reduce + 0 (map :cpu processes))
     :mem     (reduce + 0 (map :mem processes))}))

;; Four inputs (the level filter, the selected process, the raw logs, the
;; processes) fold into the slice the log pane shows. The view does no
;; filtering itself — it reads this.
(rf/reg-sub :process-monitor/visible-logs
  :<- [:process-monitor/logs]
  :<- [:process-monitor/level-filter]
  :<- [:process-monitor/selected]
  :<- [:process-monitor/processes]
  (fn [[logs levels selected processes] _]
    (let [selected-pid (when selected
                         (some #(when (= (:id %) selected) (:pid %)) processes))]
      (->> logs
           (filter #(contains? levels (:level %)))
           (filter #(or (nil? selected-pid) (= (:pid %) selected-pid)))
           (take-last 40)
           reverse))))

;; ============================================================================
;; ──────────────────────────  SUBSTRATE BOUNDARY  ──────────────────────────
;; ============================================================================
;;
;; Everything above is substrate-agnostic: seed data, events (the tick loop),
;; and the subs. None of it mentions Helix. Below this divider is the only
;; substrate-specific code: the `defnc` views and the mount. That is the
;; boundary the example teaches — app-db + events + subs sit above, the
;; substrate's view idiom sits below.

;; ============================================================================
;; VIEWS (Helix — defnc)
;; ============================================================================

(defnc tile [{:keys [label value tone]}]
  (d/div {:class (str "pm-tile pm-tile-" (when tone (name tone)))}
    (d/div {:class "pm-tile-label"} label)
    (d/div {:class "pm-tile-value"} value)))

;; `use-subscribe` is the Helix hook form of `subscribe`: it returns a plain
;; value (not a reaction) and re-renders this component when that value
;; changes. Call it at the top of the body.
(defnc tiles []
  (let [totals (helix-adapter/use-subscribe [:process-monitor/totals])]
    (d/div {:class "pm-tiles"}
      ($ tile {:label "Running" :value (:running totals) :tone :good})
      ($ tile {:label "Warning" :value (:warn    totals) :tone :warn})
      ($ tile {:label "Down"    :value (:down    totals) :tone :bad})
      ($ tile {:label "Σ CPU"   :value (str (.toFixed (:cpu totals) 1) "%")})
      ($ tile {:label "Σ MEM"   :value (str (:mem totals) "M")}))))

;; To dispatch, grab `dispatch` off a frame-handle: `(rf/frame-handle)`
;; captures the current frame as a value, so the click handler dispatches into
;; this app's frame. See docs/guide/glossary.md#frame-handle
(defnc level-chips []
  (let [active   (helix-adapter/use-subscribe [:process-monitor/level-filter])
        dispatch (:dispatch (rf/frame-handle))]
    (d/div {:class "pm-chips"}
      (for [level [:info :warn :error]]
        (d/button {:key   (name level)
                   :class (str "pm-chip pm-chip-" (name level)
                               (when (contains? active level) " is-on"))
                   :data-testid (str "monitor-chip-" (name level))
                   :on-click #(dispatch [:process-monitor/toggle-level level])}
          (name level))))))

(defnc process-row [{:keys [process selected?]}]
  (let [dispatch (:dispatch (rf/frame-handle))
        {:keys [id status cpu mem pid] process-name :name} process
        cpu-pct (min 100 cpu)]
    (d/li {:class (str "pm-row"
                       " pm-row-" (name status)
                       (when selected? " is-selected"))
           :data-testid (str "monitor-row-" (name id))
           :on-click #(dispatch [:process-monitor/select-process id])}
      (d/span {:class (str "pm-dot pm-dot-" (name status))})
      (d/span {:class "pm-row-name"} process-name)
      (d/span {:class "pm-row-pid"} (str "[" pid "]"))
      (d/div  {:class "pm-row-meter"}
        (d/div {:class "pm-row-bar"
                :style {:width (str cpu-pct "%")}}))
      (d/span {:class "pm-row-cpu"} (str (.toFixed cpu 1) "%"))
      (d/span {:class "pm-row-mem"} (str mem "M")))))

(defnc process-list []
  (let [processes (helix-adapter/use-subscribe [:process-monitor/processes])
        selected  (helix-adapter/use-subscribe [:process-monitor/selected])]
    (d/section {:class "pm-pane pm-pane-processes"}
      (d/header {:class "pm-pane-head"}
        (d/h3 "processes")
        (d/span {:class "pm-pane-hint"} "click to filter logs"))
      (d/ul {:class "pm-list"
             :data-testid "monitor-process-list"}
        (for [process processes]
          ($ process-row {:key       (:id process)
                          :process   process
                          :selected? (= selected (:id process))}))))))

(defnc log-row [{:keys [entry]}]
  (let [{:keys [tick pid level msg]} entry]
    (d/li {:class (str "pm-log pm-log-" (name level))}
      (d/span {:class "pm-log-tick"}  (str "t=" tick))
      (d/span {:class "pm-log-pid"}   (str "[" pid "]"))
      (d/span {:class (str "pm-log-level pm-log-level-" (name level))} (name level))
      (d/span {:class "pm-log-msg"}   msg))))

(defnc log-stream []
  (let [entries (helix-adapter/use-subscribe [:process-monitor/visible-logs])]
    (d/section {:class "pm-pane pm-pane-logs"}
      (d/header {:class "pm-pane-head"}
        (d/h3 "log stream")
        ($ level-chips))
      (d/ul {:class "pm-loglist"
             :data-testid "monitor-log-list"}
        (for [entry entries]
          ($ log-row {:key   (str (:tick entry) "-" (:pid entry))
                      :entry entry}))))))

(defnc monitor []
  ;; This component drives the tick loop: mount arms it, unmount stops it.
  ;; Capture `(:dispatch (rf/frame-handle))` at render-time so it carries the
  ;; surrounding frame into the post-commit effect body and cleanup. See the
  ;; Helix adapter README §use-effect for why the capture goes here.
  (let [dispatch (:dispatch (rf/frame-handle))]
    (helix-hooks/use-effect
      ;; Empty deps ⇒ run once on mount, clean up once on unmount.
      []
      ;; Mount: arm the loop. The generation guard retires any chain already in
      ;; flight, so a re-mount re-arms cleanly and only one chain stays live.
      (dispatch [:process-monitor/initialise])
      ;; Unmount: stop the chain so it never dispatches into an unrendered frame.
      (fn cleanup []
        (dispatch [:process-monitor/stop]))))
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

;; The React root is held in an atom and created lazily inside `run`, not at
;; ns-load. Per examples/TESTING.md §Example mount-isolation, ns-load must
;; produce no DOM side effects so co-required example namespaces don't race
;; `createRoot` onto the shared `#app`.
(defonce react-root (atom nil))

;; The frame id this app runs under. The `frame-provider` in `run` below is the
;; one spot the frame is set up: it creates the frame, seeds app-db, and scopes
;; the frame into React context. `use-subscribe` and the `(rf/frame-handle)`
;; capture in `monitor` resolve to this frame through that context.
(def app-frame :rf/default)

(defn run []
  ;; `init!` installs the Helix adapter so re-frame2 knows how to render.
  (rf/init! helix-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (react-dom-client/createRoot (js/document.getElementById "app"))))
    ;; `frame-provider` creates the app frame on first mount and runs
    ;; `:initial-events` once to seed app-db; a hot reload reuses the frame
    ;; without re-seeding, so the running clock and logs stay intact. From there
    ;; the `monitor` component owns the loop (see its `use-effect`).
    (.render @react-root
             ($ helix-adapter/frame-provider {:id app-frame
                                              :initial-events [[:process-monitor/initialise]]}
                ($ monitor)))))
