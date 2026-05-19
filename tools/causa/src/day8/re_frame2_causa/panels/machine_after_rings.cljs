(ns day8.re-frame2-causa.panels.machine-after-rings
  "Machine Inspector `:after` timer countdown rings — mount + tick driver
  (rf2-7hwwe, parent rf2-2tkza).

  Closes the wall-clock-time visualisation gap on the chart per
  `spec/019-Cross-Cutting-Insight.md` §Wall-clock time is under-served:
  every armed `:after` timer draws a countdown ring AROUND its bearing
  state-node. In live mode the ring sweeps from full → empty in real
  time; in retrospective mode (scrubber not at `:present`) it freezes
  at the position it occupied when the scrubber was paused.

  ## Architecture

    1. **Helpers (`machine_after_rings_helpers.cljc`)** — pure
       projection from the trace buffer into a vector of timer
       records + the ring-fraction maths. JVM-runnable.
    2. **This ns** — installs the sub/event family + mounts the SVG
       overlay + drives the rAF tick loop that re-renders the rings
       in live mode.
    3. **`chart/svg.cljc`** — owns the `countdown-ring` primitive (a
       pure-data SVG hiccup builder). The view here positions one ring
       per active timer over the chart's positioned graph.

  ## Subs/events surface

    `:rf.causa/active-timers-for-focused-machine` — composite sub over
                                                     trace buffer +
                                                     selected machine.
    `:rf.causa/timer-tick`                         — bumps a tick-
                                                     counter on app-db
                                                     to drive a re-
                                                     render.
    `:rf.causa/timer-hover`                        — set the hovered
                                                     timer for tooltip
                                                     state (v1 uses
                                                     native SVG <title>,
                                                     so the slot is
                                                     plumbed for
                                                     follow-on
                                                     rich-tooltip
                                                     beads).
    `:rf.causa/now-ms`                             — current wall-clock,
                                                     bumped per tick so
                                                     subscribers re-fire
                                                     reactively.
    `:rf.causa/set-now-ms-override-for-test`       — test hook to pin
                                                     `now-ms` to a
                                                     deterministic
                                                     value.

  ## rAF tick driver

  A single `requestAnimationFrame` loop ticks at the browser's natural
  frame rate (~60fps). On each tick we bump `:rf.causa/now-ms` —
  every consumer of the ring-fraction sub re-fires on the standard
  reactive path. The loop self-gates: when there are no armed timers
  OR the scrubber is in retrospective mode, the next rAF is not
  scheduled; the loop resumes when a fresh `:scheduled` event arrives
  (the panel re-evaluates `needs-ticking?` on every render and kicks
  the loop when truthy).

  Per the bead's divergence allowances: if rAF creates perf issues
  under many concurrent timers, the throttle knob lives in
  `*tick-min-delta-ms*` — bump it to skip-frame when fractional delta
  is below threshold. v1 ships at ~60fps with no skip-frame because
  the typical-app `:after`-timer count is small (1-3).

  ## Pure hiccup

  Same contract as every other Causa panel — the view is pure hiccup,
  no Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`."
  (:require [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [day8.re-frame2-causa.chart.layout :as chart-layout]
            [day8.re-frame2-causa.chart.svg :as chart-svg]
            [day8.re-frame2-causa.panels.machine-after-rings-helpers
             :as rings-h]))

;; ---- positioned-nodes index --------------------------------------------
;;
;; Build a `{node-id node}` map for cheap ring-overlay lookups. Inlined
;; here (rf2-y9xmf) so the rings overlay does not depend on
;; `machine_inspector_arc_helpers.cljc` (deleted with the arc UI).

(defn- nodes->index
  "Build a `{node-id node}` map for the positioned graph. Pure fn."
  [positioned-nodes]
  (into {}
        (map (fn [n] [(:node-id n) n]))
        (or positioned-nodes [])))

;; ---- wall-clock reader (overridable for tests) -------------------------

(defn- now-ms
  "Wall-clock ms. Tests rebind via `with-redefs` or the
  `:rf.causa/set-now-ms-override-for-test` event slot."
  []
  (.now js/Date))

;; ---- subs ---------------------------------------------------------------

(defn install-subs!
  "Register the rings sub family. Idempotent."
  []
  ;; ---- now-ms surface --------------------------------------------------
  ;;
  ;; The wall-clock `now-ms` is the reactive driver for the ring
  ;; animation. The rAF tick loop dispatches `:rf.causa/timer-tick`
  ;; per frame; the handler writes the fresh timestamp into the
  ;; `:rings/now-ms` slot; every consumer (ring-fraction / tooltip)
  ;; re-fires on the standard reactive path.
  ;;
  ;; Tests pin a deterministic `now-ms` via the override slot so
  ;; ring-fraction calcs don't drift between assertion + capture.
  (rf/reg-sub :rf.causa/now-ms
    (fn [db _query]
      (or (get db :rings/now-ms-override)
          (get db :rings/now-ms))))

  ;; ---- active-timers-for-focused-machine ------------------------------
  ;;
  ;; Composes:
  ;;   - the trace buffer (rings-helpers folds timer events into a
  ;;     timer-table)
  ;;   - the selected machine (resolves via the inspector's composite
  ;;     so we honour first-row default + picker focus)
  ;;   - now-ms (so the projection's zombie-eviction is reactive)
  ;;
  ;; Returns a vector of timer records — `:armed` for live rings +
  ;; `:cancelled` for the fading-out + crossed-out rings.
  (rf/reg-sub :rf.causa/active-timers-for-focused-machine
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa/machine-inspector-data]
    :<- [:rf.causa/now-ms]
    (fn [[buffer mi-data now] _query]
      (let [machine-id (:selected-id mi-data)]
        (rings-h/active-timers-for-machine buffer machine-id now))))

  ;; ---- timer-hover slot ----------------------------------------------
  ;;
  ;; Used by the view to surface the hovered timer's full tooltip in
  ;; the side-rail (follow-on; v1 uses native SVG <title>). The slot
  ;; is plumbed today so a follow-on bead lifts the hover into a rich
  ;; tooltip without touching subscriber wiring.
  (rf/reg-sub :rf.causa/timer-hover
    (fn [db _query]
      (get db :rings/hover))))

;; ---- events -------------------------------------------------------------

(defn install-events!
  "Register the rings event family. Idempotent."
  []
  (rf/reg-event-db :rf.causa/timer-tick
    ;; Per rf2-qsjda — `:rf.trace/no-emit?` keeps the rAF tick from
    ;; bombing Causa's own trace buffer 60 times per second. The tick
    ;; is an internal animation pulse, not user-visible signal.
    {:rf.trace/no-emit? true}
    (fn [db [_ t]]
      (assoc db :rings/now-ms (or t (.now js/Date)))))

  (rf/reg-event-db :rf.causa/timer-hover
    (fn [db [_ payload]]
      (if (nil? payload)
        (dissoc db :rings/hover)
        (assoc db :rings/hover payload))))

  ;; Test-only override for `now-ms` — the JVM helpers tests don't
  ;; need it (pure fn) but the CLJS test surface uses it to pin a
  ;; deterministic timestamp into the projection.
  (rf/reg-event-db :rf.causa/set-now-ms-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :rings/now-ms-override)
        (assoc db :rings/now-ms-override ov)))))

;; ---- rAF tick driver ----------------------------------------------------
;;
;; A single rAF loop that bumps `:rf.causa/timer-tick` per frame when
;; `needs-ticking?` says so. The loop self-gates so it stops when:
;;
;;   - all timers are cancelled / fired
;;   - the scrubber leaves `:present`
;;   - the panel un-mounts (no callers means the next dispatched
;;     `:rings/now-ms` isn't read; the next render's `kick-tick!`
;;     call won't fire because the panel isn't on screen)
;;
;; Per the bead's divergence allowances the throttle knob lives in
;; `*tick-min-delta-ms*`. v1 sets it to 0 (no skip-frame); a follow-on
;; can tune up if many-timer scenarios show jank.

(defonce ^:private tick-state
  ;; `{:running? bool :last-now-ms long}`
  ;;   :running? — true while a rAF is queued and undrained.
  ;;   :last-now-ms — last bumped value; the next tick can skip-frame
  ;;     if (- now last) < *tick-min-delta-ms*.
  (atom {:running?    false
         :last-now-ms 0}))

(def ^:dynamic *tick-min-delta-ms*
  "Minimum gap (ms) between consecutive `:rf.causa/timer-tick`
  dispatches. 0 = un-throttled (~60fps). Bump to 16/33 to skip-frame
  if many-timer scenarios show jank. Dynamic so tests can rebind."
  0)

(defn- raf!
  "Schedule a callback at the next animation frame. `js/requestAnimationFrame`
  in CLJS; the test surface stubs it via `set!`. Falls back to
  `interop/next-tick` when rAF is unavailable (jsdom under node-test
  where `requestAnimationFrame` may be a no-op or absent — the test
  fixture pins `now-ms` via the override slot anyway, so the fallback
  doesn't drive any real animation, only keeps the dispatch ladder
  alive)."
  [f]
  (cond
    (exists? js/requestAnimationFrame) (js/requestAnimationFrame f)
    :else                              (interop/next-tick f)))

(defn- tick-loop!
  "One iteration of the rAF tick loop. Reads the active-timers sub +
  scrubber sub; bumps `now-ms` when ticking is warranted; re-schedules
  itself iff still needed.

  The two app-db reads inside the body are deliberately NOT
  `rf/subscribe`-mediated — the loop is a side-effect driver, not a
  view; using `subscribe` here would create a reactive dependency on
  Causa's own frame from outside the view's reactive context, which is
  the same self-noise hazard the trace-bus avoids. We reach through
  `rf/frame-app-db-value` (the JVM-runnable read; CLJS impl reads off
  the frame's app-db atom) directly."
  []
  (try
    (let [timers   @(rf/subscribe [:rf.causa/active-timers-for-focused-machine])
          scrub    @(rf/subscribe [:rf.causa/machine-scrubber-position])
          now      (now-ms)
          last-now (:last-now-ms @tick-state)
          due?     (or (zero? *tick-min-delta-ms*)
                       (>= (- now last-now) *tick-min-delta-ms*))]
      (when due?
        (rf/dispatch [:rf.causa/timer-tick now] {:frame :rf/causa})
        (swap! tick-state assoc :last-now-ms now))
      (if (rings-h/needs-ticking? timers scrub)
        (raf! tick-loop!)
        (swap! tick-state assoc :running? false)))
    (catch :default _e
      ;; Defensive: a frame error must not strand the loop in
      ;; `:running? true` (would block future kicks).
      (swap! tick-state assoc :running? false))))

(defn kick-tick!
  "Start the rAF tick loop iff not already running. Idempotent —
  callers (the rings overlay component, mounted per render) call this
  on every render; the `:running?` sentinel prevents duplicate loops."
  []
  (when (compare-and-set!
          tick-state
          (assoc @tick-state :running? false)
          (assoc @tick-state :running? true))
    (raf! tick-loop!)))

(defn stop-tick!
  "Force-stop the rAF tick loop. Tests use this to reset between
  fixtures; production code never calls this directly (the loop
  self-gates on `needs-ticking?`)."
  []
  (swap! tick-state assoc :running? false))

;; ---- view: SVG ring overlay --------------------------------------------

(defn AfterRingsOverlay
  "Renders the focused machine's `:after` countdown rings as an SVG
  overlay positioned over the chart's `<svg>` viewport. Takes the
  chart's positioned graph (so it can resolve timer states to node-
  centres + radii); subscribes to the timers + now-ms internally.

  Accepts the positioned graph either as the first positional arg
  (legacy single-arity call site) OR as the `:positioned` key in an
  options map. The map form additionally accepts:

    :viewport-transform — rf2-obp4z. Optional `{:scale s :tx tx :ty ty}`
                          map carrying the chart's current zoom + pan.
                          When supplied (non-identity), the rings group
                          is wrapped in `<g transform=\"translate(tx,ty)
                          scale(s)\">` so the rings track the chart
                          nodes as the user zooms / pans. nil / absent
                          leaves the overlay at 1:1 (back-compat for
                          callers that don't have a viewport — the
                          legacy single-arity Inspector path takes
                          this branch).

  Returns nil when the projection has no active timers (the SVG layer
  drops out of the chart so unrelated chart hover handlers aren't
  shadowed)."
  ([positioned]
   (AfterRingsOverlay positioned nil))
  ([{:keys [nodes width height]} {:keys [viewport-transform]}]
   (let [timers   @(rf/subscribe [:rf.causa/active-timers-for-focused-machine])
         now      @(rf/subscribe [:rf.causa/now-ms])
         scrub    @(rf/subscribe [:rf.causa/machine-scrubber-position])
         node-idx (nodes->index nodes)
         ;; Kick the rAF loop iff ticking is needed (live mode + at
         ;; least one armed timer). Cheap to call per render — the
         ;; `:running?` sentinel collapses duplicate kicks.
         _        (when (rings-h/needs-ticking? timers scrub)
                    (kick-tick!))
         rings    (rings-h/timers->ring-positions
                    timers node-idx chart-layout/highlight-id)
         ;; rf2-obp4z — align the rings with the chart's viewport
         ;; transform. The chart wraps its body in `translate(tx,ty)
         ;; scale(s)`; we mirror that transform on the rings group
         ;; so the rings track their node centres under zoom + pan.
         ;; When the viewport is identity (or absent) the transform
         ;; attr is omitted entirely — back-compat: the legacy
         ;; single-arity call site (Machine Inspector pre-canvas
         ;; path) gets a byte-identical render.
         {:keys [scale tx ty]
          :or   {scale 1 tx 0 ty 0}}
         (or viewport-transform {})
         transform-applied? (or (not= 1 scale) (not= 0 tx) (not= 0 ty))
         rings-transform    (when transform-applied?
                              (str "translate(" tx "," ty ")"
                                   " scale(" scale ")"))]
     (when (seq rings)
       [:svg {:data-testid "rf-causa-machine-inspector-after-rings-overlay"
              :data-timer-count (count rings)
              :data-viewport-scale (str scale)
              :data-viewport-tx (str tx)
              :data-viewport-ty (str ty)
              :viewBox (str "0 0 " (or width 0) " " (or height 0))
              :width "100%"
              :preserveAspectRatio "xMidYMin meet"
              :style {:position "absolute"
                      :top 0
                      :left 0
                      :width "100%"
                      :height "100%"
                      :pointer-events "none"   ;; rings opt in below
                      :overflow "visible"}}
        (into [:g (cond-> {:data-testid "rf-causa-machine-inspector-after-rings"
                           :style {:pointer-events "all"}}
                    rings-transform (assoc :transform rings-transform))]
              (for [{:keys [timer cx cy r]} rings
                    :let [fraction (rings-h/ring-fraction timer now)
                          color    (rings-h/timer-color timer now)
                          cancelled? (= :cancelled (:status timer))
                          tooltip  (rings-h/format-timer-tooltip timer now)
                          state-id (if (keyword? (:state timer))
                                     (name (:state timer))
                                     (pr-str (:state timer)))
                          testid   (str "rf-causa-machine-inspector-after-ring-"
                                        state-id)]]
                ^{:key (str (:state timer) "/" (:epoch timer))}
                [:g {:on-mouse-enter
                     (fn [_]
                       (rf/dispatch [:rf.causa/timer-hover
                                     {:machine-id (:machine-id timer)
                                      :state      (:state timer)
                                      :epoch      (:epoch timer)}]
                                    {:frame :rf/causa}))
                     :on-mouse-leave
                     (fn [_]
                       (rf/dispatch [:rf.causa/timer-hover nil]
                                    {:frame :rf/causa}))}
                 (chart-svg/countdown-ring
                   {:cx cx :cy cy :r r
                    :fraction fraction
                    :color    color
                    :cancelled? cancelled?
                    :testid   testid
                    :tooltip  tooltip})]))]))))

;; ---- public install entry -----------------------------------------------

(defn install!
  "Idempotent install — called by `machine_inspector/install!`."
  []
  (install-subs!)
  (install-events!))
