(ns helix.process-monitor.core
  "A terminal-style process monitor, built on Helix. Two panes — a
   selectable process list on the left (click a process to filter the logs
   to it), a live log feed on the right — with status tiles across the top.
   The log pane gains a fresh line every couple of seconds with nobody
   touching it, which is the fun part: it proves this is a real reactive
   loop, not a screenshot.

   Four things worth watching:

     - Helix views (`defnc`) read their slice of state through the
       `use-subscribe` hook — the React-hooks idiom, no `reg-view` in sight.
     - A derivation graph at work. Two UI inputs — the level-filter chips and
       the selected process — fold into one derived subscription, the visible
       log slice. It recomputes only when an input actually changes.
     - A self-advancing tick that appends synthetic log lines
       (`:process-monitor/tick`), driven by the component lifecycle. The
       trick is keeping it to exactly one live chain; see the EVENTS block.
     - Per-row dispatch from inside a `defnc` row component.

   The 'Editorial Warm' visual identity is shared across all three design-led
   examples; it lives in examples/_shared/css/style.css."
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
;; The tick is a `:dispatch-later` that reschedules itself — a loop with no
;; off switch, because that effect has no cancel API. Left alone, a hot reload
;; or a re-mount would just spawn a second chain alongside the first, and now
;; two loops append lines forever. Not ideal.
;;
;; The fix is a generation guard, and it's pleasingly small. A counter,
;; `:process-monitor/tick-gen`, records which "arming" each chain belongs to:
;;
;;   - Every scheduled tick carries the generation it was armed under.
;;   - `:process-monitor/initialise` and `:process-monitor/stop` bump the counter.
;;   - A tick whose generation no longer matches `db` just declines to act —
;;     no append, no reschedule. So a chain from an earlier arming quietly
;;     dies the next time it fires. No cancellation required.
;;
;; The loop hangs off the `monitor` component's lifecycle (see its `use-effect`
;; below): mount arms it via `:process-monitor/initialise`, unmount retires it
;; via `:process-monitor/stop`. Re-mount and it re-arms cleanly. Unmount and no
;; chain is left dispatching into a frame nobody is looking at.
;;
;; This is the ONE arming site. The `frame-provider` call in `run` (see MOUNT,
;; below) deliberately carries no `:initial-events` — if it also dispatched
;; `:process-monitor/initialise`, boot would arm two overlapping chains (the
;; generation guard above would mask it, quietly dropping the older one, but
;; that's two full `:process-monitor/initialise` runs for no reason). One
;; site owns arming; the other stays out of it.

(rf/reg-event :process-monitor/initialise
  (fn [{:keys [db]} _event]
    (let [next-gen (inc (:process-monitor/tick-gen db 0))]
      {:db {:process-monitor/processes initial-processes
            :process-monitor/logs      initial-logs
            :process-monitor/clock     11
            :process-monitor/level-filter #{:info :warn :error}
            :process-monitor/selected   nil
            :process-monitor/tick-gen   next-gen}
       ;; Arm the loop by *describing* the first tick as data. The handler
       ;; stays pure — it never calls setTimeout; the :dispatch-later effect
       ;; does the scheduling. The tick carries `next-gen` so a later arming
       ;; can retire this one.
       :fx [[:dispatch-later {:ms 1800 :event [:process-monitor/tick next-gen]}]]})))

(rf/reg-event :process-monitor/tick
  (fn [{:keys [db]} [_ gen]]
    (if (not= gen (:process-monitor/tick-gen db))
      ;; This tick belongs to a retired generation — a chain a newer arming
      ;; has already superseded. Drop it: no append, no clock bump, no
      ;; reschedule. Declining to act is how the old chain dies.
      {}
      (let [tick      (:process-monitor/clock db)
            processes (:process-monitor/processes db)
            ;; Round-robin through the processes so the synthetic feed looks
            ;; like every service is chattering, not just one.
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
         ;; Reschedule under the *same* generation, so this chain keeps going
         ;; until a fresher arming comes along and retires it.
         :fx [[:dispatch-later {:ms 1800 :event [:process-monitor/tick gen]}]]}))))

;; Stops the loop. Bumps the generation but arms nothing new, so the live
;; chain finds itself stale on its next fire and quietly steps off. The
;; `monitor` component dispatches this from its `use-effect` cleanup on unmount.
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

;; A derived subscription: it reads other subs rather than app-db directly.
;; `:<-` names its inputs, and the runtime only recomputes when one of them
;; changes by `=`. So the tiles sit still while unrelated keys move underneath
;; — caching you get for free by saying what you depend on. The glossary has
;; the full picture: docs/core/glossary.md#the-derivation-graph
(rf/reg-sub :process-monitor/totals
  :<- [:process-monitor/processes]
  (fn [processes _]
    {:running (count (filter #(= :running (:status %)) processes))
     :warn    (count (filter #(= :warn (:status %)) processes))
     :down    (count (filter #(= :down (:status %)) processes))
     :cpu     (reduce + 0 (map :cpu processes))
     :mem     (reduce + 0 (map :mem processes))}))

;; The star of the show. Four inputs fold into the exact slice the log pane
;; renders: the raw logs, the active levels, the selected process, and the
;; processes (to map a selection to its pid). Click a chip or a row and this
;; re-derives; the view never filters anything itself, it just reads the
;; answer. This is the derivation graph earning its keep.
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
;; Everything above this line is substrate-agnostic: seed data, events (tick
;; loop and all), and subs. Scan it and you won't find the word Helix once —
;; it would run unchanged on Reagent or UIx. Below the line is the only
;; Helix-specific code: the `defnc` views and the mount. That split is the
;; lesson. The interesting machinery lives in the portable core; the substrate
;; is just the thin rendering choice you make at the edge.

;; ============================================================================
;; VIEWS (Helix — defnc)
;; ============================================================================

(defnc tile [{:keys [label value tone]}]
  (d/div {:class (str "pm-tile pm-tile-" (when tone (name tone)))}
    (d/div {:class "pm-tile-label"} label)
    (d/div {:class "pm-tile-value"} value)))

;; `use-subscribe` is `subscribe` in hook clothing. It hands back a plain value
;; — not a reaction to deref — and re-renders this component whenever that
;; value changes. Like any React hook, call it at the top of the body.
(defnc tiles []
  (let [totals (helix-adapter/use-subscribe [:process-monitor/totals])]
    (d/div {:class "pm-tiles"}
      ($ tile {:label "Running" :value (:running totals) :tone :good})
      ($ tile {:label "Warning" :value (:warn    totals) :tone :warn})
      ($ tile {:label "Down"    :value (:down    totals) :tone :bad})
      ($ tile {:label "Σ CPU"   :value (str (.toFixed (:cpu totals) 1) "%")})
      ($ tile {:label "Σ MEM"   :value (str (:mem totals) "M")}))))

;; To dispatch from a view, grab `dispatch` off a frame api.
;; `(rf/capture-frame)` captures the current frame as a value, so the click
;; handler we close over below still lands in this app's frame when it fires.
;; See docs/core/glossary.md#capture-frame
(defnc level-chips []
  (let [active   (helix-adapter/use-subscribe [:process-monitor/level-filter])
        dispatch (:dispatch (rf/capture-frame))]
    (d/div {:class "pm-chips"}
      (for [level [:info :warn :error]]
        (d/button {:key   (name level)
                   :class (str "pm-chip pm-chip-" (name level)
                               (when (contains? active level) " is-on"))
                   :data-testid (str "monitor-chip-" (name level))
                   :on-click #(dispatch [:process-monitor/toggle-level level])}
          (name level))))))

(defnc process-row [{:keys [process selected?]}]
  (let [dispatch (:dispatch (rf/capture-frame))
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
  ;; This component owns the tick loop's lifecycle: mount arms it, unmount
  ;; retires it. We capture `(:dispatch (rf/capture-frame))` here in the `let`,
  ;; at render-time, while the frame is still in scope. By the time the effect
  ;; and its cleanup actually run — after commit — that scope is gone, so a
  ;; dispatch fetched in there would have no frame to land in. Grab it now,
  ;; close over it, and you're safe. The Helix adapter README §use-effect has
  ;; the long version.
  (let [dispatch (:dispatch (rf/capture-frame))]
    (helix-hooks/use-effect
      ;; Empty deps ⇒ run once on mount, clean up once on unmount.
      []
      ;; Mount: arm the loop. The generation guard retires anything already in
      ;; flight, so a re-mount re-arms cleanly and only one chain stays live.
      (dispatch [:process-monitor/initialise])
      ;; Unmount: stop the chain so it never dispatches into a frame that's no
      ;; longer on screen.
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

;; The React root lives in an atom and is created lazily inside `run`, never at
;; ns-load. The rule (examples/TESTING.md §Example mount-isolation): loading a
;; namespace must touch no DOM, so that when several examples are required side
;; by side they don't all race to call `createRoot` on the shared `#app`.
(defonce react-root (atom nil))

;; The frame this app runs under. The `frame-provider` in `run` is the single
;; place it's set up — it creates the frame and threads it into React context.
;; Everything downstream (`use-subscribe`, the `(rf/capture-frame)` capture in
;; `monitor`) finds this frame through that context. It carries no
;; `:initial-events`: db-seeding and loop-arming are both `monitor`'s job (see
;; the EVENTS block above for why that's the one arming site).
(def app-frame :rf/default)

(defn run []
  ;; `init!` tells re-frame2 which substrate to render through — here, Helix.
  ;; It installs the adapter and nothing more; it does not create a frame.
  (rf/init! helix-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (react-dom-client/createRoot (js/document.getElementById "app"))))
    ;; `frame-provider` creates the app frame on first mount; a hot reload
    ;; reuses the existing frame. From here on the `monitor` component owns
    ;; both the seed and the loop (see its `use-effect`) — mount dispatches
    ;; `:process-monitor/initialise`, which seeds app-db AND arms the tick
    ;; chain in one step, so your running clock and accumulated logs survive
    ;; a reload that doesn't remount `monitor`.
    (.render @react-root
             ($ helix-adapter/frame-provider {:id app-frame}
                ($ monitor)))))
