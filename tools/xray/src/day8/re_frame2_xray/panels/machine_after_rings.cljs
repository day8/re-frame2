(ns day8.re-frame2-xray.panels.machine-after-rings
  "Machine Inspector `:after` timer countdown rings — mount + tick driver
  (rf2-7hwwe, parent rf2-2tkza).

  Closes the wall-clock-time visualisation gap on the chart per
  `spec/019-Cross-Cutting-Insight.md` §Wall-clock time is under-served:
  every armed `:after` timer draws a countdown ring AROUND its bearing
  state-node. In live mode the ring sweeps from full → empty in real
  time; in retrospective mode (scrubber not at `:present`) it freezes
  at the position it occupied when the scrubber was paused.

  ## Architecture (rf2-uv1on — xyflow Phase 2)

  The rf2-gpzb4 xyflow migration moved POSITIONING out of this ns:
  xyflow owns node positions in the rendered DOM, so the overlay must
  WALK the DOM rather than read a positioned graph. The split is now:

    1. **Helpers (`machine_after_rings_helpers.cljc`)** — pure
       projection from the trace buffer into a vector of timer
       records + the ring-fraction maths + the
       `timers->ring-specs` projection into machines-viz overlay
       specs. JVM-runnable.
    2. **This ns** — installs the sub/event family, drives the single
       per-chart rAF tick loop (Lock #8), and is the DATA owner: it
       projects the trace buffer into presentation-ready ring-specs
       and delegates positioning + paint to (3).
    3. **`day8.re-frame2-machines-viz.chart.overlays.after-rings`** —
       the machines-viz xyflow overlay. Walks the rendered node DOM
       (`[data-testid=rf-mv-chart-node-...]`) to position each ring +
       paints the `countdown-ring` glyph. Owns no clock — it
       re-measures whenever this ns re-renders it (tick bump).

  ## Subs/events surface

    `:rf.xray/active-timers-for-focused-machine` — composite sub over
                                                     trace buffer +
                                                     selected machine.
    `:rf.xray/timer-tick`                         — bumps a tick-
                                                     counter on app-db
                                                     to drive a re-
                                                     render.
    `:rf.xray/timer-hover`                        — set the hovered
                                                     timer for tooltip
                                                     state (v1 uses
                                                     native SVG <title>,
                                                     so the slot is
                                                     plumbed for
                                                     follow-on
                                                     rich-tooltip
                                                     beads).
    `:rf.xray/now-ms`                             — current wall-clock,
                                                     bumped per tick so
                                                     subscribers re-fire
                                                     reactively.
    `:rf.xray/set-now-ms-override-for-test`       — test hook to pin
                                                     `now-ms` to a
                                                     deterministic
                                                     value.

  ## rAF tick driver

  A single `requestAnimationFrame` loop ticks at the browser's natural
  frame rate (~60fps). On each tick we bump `:rf.xray/now-ms` —
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

  Same contract as every other Xray panel — the view is pure hiccup,
  no Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider-existing {:frame :rf/xray}]` in `shell.cljs`."
  (:require [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-machines-viz.chart.overlays.after-rings
             :as mv-after-rings]
            [day8.re-frame2-xray.panels.machine-after-rings-helpers
             :as rings-h]))

;; ---- wall-clock reader (overridable for tests) -------------------------

(defn- now-ms
  "Wall-clock ms. Tests rebind via `with-redefs` or the
  `:rf.xray/set-now-ms-override-for-test` event slot."
  []
  (.now js/Date))

;; ---- subs ---------------------------------------------------------------

(defn install-subs!
  "Register the rings sub family. Idempotent."
  []
  ;; ---- now-ms surface --------------------------------------------------
  ;;
  ;; The wall-clock `now-ms` is the reactive driver for the ring
  ;; animation. The rAF tick loop dispatches `:rf.xray/timer-tick`
  ;; per frame; the handler writes the fresh timestamp into the
  ;; `:rings/now-ms` slot; every consumer (ring-fraction / tooltip)
  ;; re-fires on the standard reactive path.
  ;;
  ;; Tests pin a deterministic `now-ms` via the override slot — the
  ;; override read lives behind `install-test-overrides!` (rf2-e8330v),
  ;; so production registration carries no `-for-test` ids and the
  ;; production sub reads the live tick slot directly.
  (rf/reg-sub :rf.xray/now-ms
    (fn [db _query]
      (get db :rings/now-ms)))

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
  (rf/reg-sub :rf.xray/active-timers-for-focused-machine
    :<- [:rf.xray/trace-buffer]
    :<- [:rf.xray/machine-inspector-data]
    :<- [:rf.xray/now-ms]
    (fn [[buffer mi-data now] _query]
      (let [machine-id (:selected-id mi-data)]
        (rings-h/active-timers-for-machine buffer machine-id now))))

  ;; ---- timer-hover slot ----------------------------------------------
  ;;
  ;; Used by the view to surface the hovered timer's full tooltip in
  ;; the side-rail (follow-on; v1 uses native SVG <title>). The slot
  ;; is plumbed today so a follow-on bead lifts the hover into a rich
  ;; tooltip without touching subscriber wiring.
  (rf/reg-sub :rf.xray/timer-hover
    (fn [db _query]
      (get db :rings/hover))))

;; ---- events -------------------------------------------------------------

(defn install-events!
  "Register the rings event family. Idempotent."
  []
  (rf/reg-event :rf.xray/timer-tick
    ;; Per rf2-qsjda — `:rf.trace/no-emit?` keeps the rAF tick from
    ;; bombing Xray's own trace buffer 60 times per second. The tick
    ;; is an internal animation pulse, not user-visible signal.
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ t]]
      {:db (assoc db :rings/now-ms (or t (.now js/Date)))}))

  (rf/reg-event :rf.xray/timer-hover
    (fn [{:keys [db]} [_ payload]]
      {:db (if (nil? payload)
        (dissoc db :rings/hover)
        (assoc db :rings/hover payload))})))

;; ---- test-only override seam (rf2-e8330v / xxo3zz F3) -------------------

(defn install-test-overrides!
  "Install the after-rings test-only override seam — the
  `:rf.xray/set-now-ms-override-for-test` event, then RE-register
  `:rf.xray/now-ms` to read the override slot ahead of the live tick.
  Tests opt in by calling this AFTER `register-xray-handlers!`
  (typically via `test-support/install-test-overrides!`). The JVM
  helpers tests don't need it (pure fn); the CLJS test surface uses it
  to pin a deterministic timestamp into the projection. **Test-only —
  never call from production.**"
  []
  (rf/reg-event :rf.xray/set-now-ms-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov)
        (dissoc db :rings/now-ms-override)
        (assoc db :rings/now-ms-override ov))}))
  (rf/reg-sub :rf.xray/now-ms
    (fn [db _query]
      (or (get db :rings/now-ms-override)
          (get db :rings/now-ms))))
  nil)

;; ---- rAF tick driver ----------------------------------------------------
;;
;; A single rAF loop that bumps `:rf.xray/timer-tick` per frame when
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
  ;; `{:running? bool :last-now-ms long :frame <frame-id>}`
  ;;   :running? — true while a rAF is queued and undrained.
  ;;   :last-now-ms — last bumped value; the next tick can skip-frame
  ;;     if (- now last) < *tick-min-delta-ms*.
  ;;   :frame — rf2-nesy9: the surrounding instance frame captured by
  ;;     the overlay reg-view at `kick-tick!` time, so the off-render
  ;;     rAF tick dispatch lands on it (defaults to default-frame-id).
  (atom {:running?    false
         :last-now-ms 0
         :frame       defaults/default-frame-id}))

(def ^:dynamic *tick-min-delta-ms*
  "Minimum gap (ms) between consecutive `:rf.xray/timer-tick`
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
  Xray's own frame from outside the view's reactive context, which is
  the same self-noise hazard the trace collector avoids. We reach through
  `rf/frame-app-db-value` (the JVM-runnable read; CLJS impl reads off
  the frame's app-db atom) directly."
  []
  (try
    (let [timers   @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])
          scrub    @(rf/subscribe [:rf.xray/machine-scrubber-position])
          now      (now-ms)
          last-now (:last-now-ms @tick-state)
          due?     (or (zero? *tick-min-delta-ms*)
                       (>= (- now last-now) *tick-min-delta-ms*))]
      (when due?
        ;; rf2-nesy9 — the rAF tick loop runs outside the render scope;
        ;; dispatch into the frame captured at `kick-tick!` (the
        ;; surrounding instance frame at the time the overlay armed the
        ;; clock), not a `{:frame :rf/xray}` literal.
        (rf/dispatch [:rf.xray/timer-tick now]
                     {:frame (:frame @tick-state)})
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
  on every render; the `:running?` sentinel prevents duplicate loops.

  `frame` (rf2-nesy9) is the surrounding instance frame the overlay
  reg-view captured at render; stashed so the off-render tick dispatch
  lands on it. Defaults to `defaults/default-frame-id` for the test
  seam / direct callers."
  ([] (kick-tick! defaults/default-frame-id))
  ([frame]
   ;; Record the captured frame before arming so `tick-loop!` reads it.
   (swap! tick-state assoc :frame frame)
   (when (compare-and-set!
           tick-state
           (assoc @tick-state :running? false)
           (assoc @tick-state :running? true))
     (raf! tick-loop!))))

(defn stop-tick!
  "Force-stop the rAF tick loop. Tests use this to reset between
  fixtures; production code never calls this directly (the loop
  self-gates on `needs-ticking?`)."
  []
  (swap! tick-state assoc :running? false))

;; ---- view: xyflow ring overlay -----------------------------------------
;;
;; rf2-uv1on (xyflow Phase 2) — positioning moved to the machines-viz
;; `chart.overlays.after-rings/AfterRingsOverlay`, which WALKS the
;; rendered xyflow node DOM (`[data-testid=rf-mv-chart-node-...]`) to
;; find each bearing node's bounding box. xyflow owns node positions
;; post-migration, so the old positioned-graph SVG model (resolving
;; `{:cx :cy :r}` from elk coordinates + a viewport-transform) is
;; gone. This ns stays the DATA owner: it projects Xray's trace
;; buffer into presentation-ready ring-specs and drives the rAF tick
;; clock; the machines-viz overlay owns DOM measurement + paint.

(defn- timer-hovered!
  "Wire a ring's hover into the Xray timer-hover slot. The overlay
  passes the bearing node-id back; we re-resolve the timer identity
  from the live spec list so the slot carries the full
  `(machine-id, state, epoch)` tuple a follow-on rich tooltip wants.

  `dispatch` (rf2-nesy9) is the frame-aware dispatcher captured by the
  `AfterRingsOverlay` reg-view body."
  [dispatch specs node-id]
  (when-let [spec (some (fn [s] (when (= node-id (:node-id s)) s)) specs)]
    (dispatch [:rf.xray/timer-hover
               {:machine-id (:machine-id spec)
                :state      (:state spec)
                :epoch      (:epoch spec)}])))

(rf/reg-view AfterRingsOverlay
  "Mounts the focused machine's `:after` countdown rings over the
  xyflow chart. Subscribes to the active timers + now-ms + scrubber
  internally; projects each timer into a presentation-ready ring-spec
  (scrubber-aware fraction baked in — retrospective mode freezes the
  ring at the scrubber's anchor instant); kicks the single per-chart
  rAF clock in live mode; then delegates positioning + paint to the
  machines-viz `AfterRingsOverlay`, which walks the xyflow node DOM.

  rf2-m4xz1 — registered via `reg-view` (was a plain `defn`) so the
  React-context frame tier carries the enclosing `:rf/xray` frame
  through to the four subscribes inside. As a plain fn rendered under
  `[rf/frame-provider-existing {:frame :rf/xray}]`, the subscribes routed to
  `:rf/default` (the host app's frame), which is a real frame-leak —
  xray-internal slots must be read via xray's own frame. Same surgery
  PR #2110 (rf2-uu3lp) applied to the rest of xray chrome.

  Accepts an optional opts map (currently unused — reserved for a
  future per-call testid override). Variadic to preserve the prior
  0-arg + 1-arg call shapes (the hiccup mount carries one arg, tests
  call with zero or one). The legacy positioned-graph / viewport-
  transform args are GONE (xyflow owns positions; the overlay reads
  them off the DOM).

  Returns nil when the projection has no active timers (the overlay
  layer drops out so unrelated chart hover handlers aren't shadowed).

  ## rf2-fkpuv — DOM-rooted via `display: contents`

  The body delegates to the machines-viz overlay component, whose
  React-element head is a function (not a DOM tag). A bare component
  head as the reg-view root would skip the source-coord DOM
  annotation (Spec 006 §Source-coord annotation: pair tools fall back
  to `:rf/id` for non-DOM roots) and emit a one-shot warning per id.
  A wrapper `<div>` with `display: contents` participates in the DOM
  tree (so `data-rf2-source-coord` + `data-rf-view` have a home and
  click-to-source works) while being neutralised for layout — the
  inner machines-viz overlay's `position: absolute` still anchors to
  the chart's `position: relative` wrapper (the offsetParent the
  overlay queries to measure node rects is unchanged because a
  `display: contents` element is not a positioning context). Same
  pattern as `shell.cljs` (rf2-uu3lp)."
  [& _opts]
  (let [timers @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])
        now    @(rf/subscribe [:rf.xray/now-ms])
        scrub  @(rf/subscribe [:rf.xray/machine-scrubber-position])
        ;; rf2-nesy9 — capture the surrounding instance frame so the
        ;; off-render rAF clock + the hover/leave callbacks dispatch
        ;; into it (not a `:rf/xray` literal). `frame` arms the rAF loop
        ;; via kick-tick!; `dispatch` is the reg-view-injected dispatcher.
        frame  (rf/current-frame-id)
        ;; Kick the rAF loop iff ticking is needed (live mode + at
        ;; least one armed timer). Cheap to call per render — the
        ;; `:running?` sentinel collapses duplicate kicks. Per Lock #8
        ;; this is the SINGLE per-chart clock (O(charts), not
        ;; O(rings × charts)); the machines-viz overlay runs no clock
        ;; of its own — it just re-measures the DOM when `:tick` bumps.
        _      (when (rings-h/needs-ticking? timers scrub)
                 (kick-tick! frame))
        specs  (rings-h/timers->ring-specs
                 timers chart-layout/highlight-id now)]
    (when (seq specs)
      [:div {:data-rf-xray-after-rings-host ""
             :style {:display "contents"}}
       [mv-after-rings/AfterRingsOverlay
        {:ring-specs specs
         ;; `now` is the rAF-bumped (or scrubber-pinned) instant —
         ;; bumping it on every frame forces the overlay to re-measure
         ;; the DOM + repaint the swept arcs (Lock #8 60Hz-when-visible
         ;; cadence, driven by THIS ns's clock, not the overlay's).
         :tick       now
         :testid     "rf-xray-machine-inspector-after-rings-overlay"
         :on-hover   (fn [node-id] (timer-hovered! dispatch specs node-id))
         :on-leave   (fn [_node-id]
                       (dispatch [:rf.xray/timer-hover nil]))}]])))

;; ---- public install entry -----------------------------------------------

(defn install!
  "Idempotent install — called by `machine_inspector/install!`."
  []
  (install-subs!)
  (install-events!))
