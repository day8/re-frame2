(ns process-monitor-helix.core
  "Helix design-led example — 'Process Monitor'. Terminal-style two-pane
   layout: filterable process list on the left + live log feed on the
   right, with status tiles across the top. Proves re-frame2 + Helix can
   build a substantive UI.

   Demonstrates:

     - Helix components (`defnc`) consuming subs via `use-subscribe`
     - signal-graph subscriptions: two independent inputs (the level-filter
       chips, the selected process) fold into one derived sub, the visible
       log slice — pure derivation, recomputed only when an input changes
     - a recurring `:dispatch-later` tick that appends synthetic log
       lines (`:process-monitor/tick`), owned by the component lifecycle and
       kept idempotent by a generation guard — a real reactive loop, not a
       static screenshot
     - per-row dispatch from inside a `defnc` row component

   No HTTP, no state machines — design-led examples prove polished
   visuals + interaction, not platform features other examples already
   cover. Distinct shape from the Reagent 'Notebook' (3-pane editor)
   and UIx 'Atlas' dashboard (cards + sparklines).

   The shared 'Editorial Warm' visual identity comes from
   examples/_shared/css/style.css — one identity across all three
   substrates."
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
;; The tick chain is driven by `:dispatch-later`, which has no cancel API.
;; If `run` / `:process-monitor/initialise` is invoked more than once in the same JS
;; runtime (a shadow watch reload, a manual re-init, a future browser wrapper,
;; or any harness that calls the entrypoint repeatedly), a naive chain would
;; leave the prior chain's ticks still firing — every stale cascade would keep
;; appending log entries and incrementing `:process-monitor/clock`, corrupting the
;; visible cadence and clock. To stay idempotent — exactly one live tick per
;; interval regardless of re-init churn — each scheduled tick carries the
;; `:process-monitor/tick-gen` it was armed under. `:process-monitor/initialise` bumps the
;; generation, so any in-flight tick from a retired chain no-ops when it
;; eventually fires. This mirrors the 7GUIs timer's `:tick-gen` guard
;; (examples/reagent/seven_guis/timer), the local precedent for the same
;; cancel-less `:dispatch-later` constraint.
;;
;; The generation guard handles *re-init* (a fresh `:process-monitor/initialise`
;; retires the prior chain). It does not, on its own, handle *teardown*:
;; unmounting or replacing the React root without a following initialise
;; would leave the last live chain dispatching into a frame nobody is
;; rendering, silently mutating app-db forever. So the loop is tied to the
;; Helix component lifecycle (see the `monitor` `use-effect` below): mount
;; arms it via `:process-monitor/initialise`, unmount retires it via `:process-monitor/stop`.
;; `:process-monitor/stop` simply bumps `:process-monitor/tick-gen` *without* rescheduling, so
;; the in-flight tick no-ops when it lands and the chain dies. Repeated
;; mount/unmount (e.g. a hot-reload teardown) therefore leaves at most one
;; live tick chain, and a teardown leaves none.

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
       ;; never touches setTimeout; the :dispatch-later effect handler does.
       ;; The tick carries `next-gen` so it can recognise a retired chain.
       :fx [[:dispatch-later {:ms 1800 :event [:process-monitor/tick next-gen]}]]})))

(rf/reg-event :process-monitor/tick
  (fn [{:keys [db]} [_ gen]]
    (if (not= gen (:process-monitor/tick-gen db))
      ;; Stale tick from a retired generation (a later initialise bumped
      ;; :process-monitor/tick-gen). Drop it: no log append, no clock bump, no reschedule.
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
         ;; Reschedule under the same generation, so the chain continues
         ;; while a fresher initialise can still retire it.
         :fx [[:dispatch-later {:ms 1800 :event [:process-monitor/tick gen]}]]}))))

;; Teardown counterpart to `:process-monitor/initialise`. Bumping `:process-monitor/tick-gen`
;; without arming a fresh tick retires the live chain: the next scheduled
;; `:process-monitor/tick` carries a now-stale generation and no-ops. Dispatched from
;; the `monitor` component's `use-effect` cleanup on unmount, so the loop
;; never outlives the rendered root.
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

;; First derived sub: a pure node hanging off the base subs above. `:<-`
;; names its inputs; the runtime recomputes it only when an input changes
;; by `=`, so the tiles don't re-render when an unrelated key moves.
(rf/reg-sub :process-monitor/totals
  :<- [:process-monitor/processes]
  (fn [processes _]
    {:running (count (filter #(= :running (:status %)) processes))
     :warn    (count (filter #(= :warn (:status %)) processes))
     :down    (count (filter #(= :down (:status %)) processes))
     :cpu     (reduce + 0 (map :cpu processes))
     :mem     (reduce + 0 (map :mem processes))}))

;; The signal-graph node: two independent UI inputs (the level filter, the
;; selected process) plus the raw logs and processes fold into the slice the
;; log pane shows. The view does no filtering itself — it reads this.
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
;; Everything above is the substrate-agnostic artefact layer: seed data,
;; events (the tick loop), and the signal-graph subs. None of it mentions
;; Helix. Below this divider is the only substrate-specific code: the `defnc`
;; views + the mount. Unlike the counter/login pair, this design-led example
;; is NOT a cross-substrate parity twin — its dataflow is its own (the Reagent
;; notebook + UIx dashboard are different apps, not the same dataflow through a
;; different substrate). The boundary it teaches is still the same: app-db +
;; events + subs sit above, the substrate's view idiom sits below.

;; ============================================================================
;; VIEWS (Helix — defnc)
;; ============================================================================

(defnc tile [{:keys [label value tone]}]
  (d/div {:class (str "pm-tile pm-tile-" (when tone (name tone)))}
    (d/div {:class "pm-tile-label"} label)
    (d/div {:class "pm-tile-value"} value)))

;; First sub-reading view. `use-subscribe` is the Helix hook form of
;; `subscribe`: it returns a plain value (not a reaction) and re-renders this
;; component when that value changes. Call it at the top of the body.
(defnc tiles []
  (let [totals (helix-adapter/use-subscribe [:process-monitor/totals])]
    (d/div {:class "pm-tiles"}
      ($ tile {:label "Running" :value (:running totals) :tone :good})
      ($ tile {:label "Warning" :value (:warn    totals) :tone :warn})
      ($ tile {:label "Down"    :value (:down    totals) :tone :bad})
      ($ tile {:label "Σ CPU"   :value (str (.toFixed (:cpu totals) 1) "%")})
      ($ tile {:label "Σ MEM"   :value (str (:mem totals) "M")}))))

;; First dispatching view. To dispatch, grab `dispatch` off a frame-handle —
;; `(rf/frame-handle)` captures the current frame as a value, so the click
;; handler dispatches into this app's frame, not some ambient default.
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
  ;; The recurring tick loop is owned by this component's lifecycle, not by
  ;; `run`. `(:dispatch (rf/frame-handle))` is captured at render-time so it
  ;; carries the surrounding frame into the (post-commit) effect body and
  ;; cleanup — see the Helix adapter README §use-effect. Empty deps ⇒ the
  ;; effect runs once on mount and the cleanup runs once on unmount.
  (let [dispatch (:dispatch (rf/frame-handle))]
    (helix-hooks/use-effect
      ;; Empty deps ⇒ run once on mount, clean up once on unmount.
      []
      ;; Mount: (re-)arm the loop. Idempotent — the `:process-monitor/tick-gen`
      ;; guard retires any chain `run` already started, so exactly one
      ;; live chain survives, and a hot-reload re-mount re-arms cleanly.
      (dispatch [:process-monitor/initialise])
      ;; Unmount: retire the live chain so it never dispatches into a frame
      ;; that is no longer rendered.
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

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `createRoot` onto the shared `#app`.
;; This matches the sibling notebook / dashboard_uix mount shape.
(defonce react-root (atom nil))

;; The runtime never synthesises a frame from absence — an app must establish
;; its frame explicitly. `init!` installs the adapter (it does NOT create the
;; frame). The Helix `frame-provider` does the rest in one spot: the `{:id …}`
;; ENSURE form CREATES the app frame on first mount, then runs `:initial-events`
;; ONCE to seed it. On hot reload it REUSES the existing frame without
;; re-seeding. The `use-subscribe` hook and the render-time `(rf/frame-handle)`
;; capture inside `monitor` (the tick-loop arm/stop dispatches) resolve to that
;; frame via React context. There is no `:rf/default` floor.
(def app-frame :rf/default)

(defn run []
  (rf/init! helix-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (react-dom-client/createRoot (js/document.getElementById "app"))))
    ;; One-spot frame setup: `{:id app-frame}` ensures the frame exists (creating
    ;; it on first mount) and `:initial-events` seeds it exactly once. The seed
    ;; arms the first tick; the `monitor` component's mount `use-effect` re-arms
    ;; (idempotently, via the `:process-monitor/tick-gen` guard) so the loop is
    ;; owned by the component lifecycle from then on — unmount stops it (see
    ;; `:process-monitor/stop`). Reuse-no-reseed on reload keeps the running
    ;; clock/logs intact across hot reloads.
    (.render @react-root
             ($ helix-adapter/frame-provider {:id app-frame
                                              :initial-events [[:process-monitor/initialise]]}
                ($ monitor)))))
