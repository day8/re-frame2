(ns day8.re-frame2-xray.panels.machine-after-rings
  "Machine Inspector `:after` timer countdown rings — mount + tick driver.

  Closes the wall-clock-time visualisation gap on the chart per
  `spec/019-Cross-Cutting-Insight.md` §Wall-clock time is under-served:
  every armed `:after` timer draws a countdown ring AROUND its bearing
  state-node, sweeping from full → empty in real time.

  The rings are a LIVE-mode surface: while the spine focus is pinned to a
  past event (`:mode :retro`) the overlay draws nothing and the clock
  stops. The timer fold reads the whole trace buffer, which already holds
  every timer's later fate, so drawing it over a past epoch's chart would
  show the present's timers on the past's states (xray/003 §M.2).

  ## Architecture

  POSITIONING is not this ns's job: xyflow owns node positions in the
  rendered DOM, so the overlay must WALK the DOM rather than read a
  positioned graph. The split is:

    1. **Helpers (`machine_after_rings_helpers.cljc`)** — pure
       projection from the trace buffer into a vector of timer
       records + the ring-fraction maths + the
       `timers->ring-specs` projection into machines-viz overlay
       specs. JVM-runnable.
    2. **This ns** — installs the sub/event family, drives one rAF tick
       loop per frame (never one per ring), and is the DATA owner: it
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
                                                     the focused-event
                                                     record's machine +
                                                     the target frame.
                                                     BUFFER-keyed: the
                                                     now-keyed eviction
                                                     is [[overlay-tree]]'s.
    `:rf.xray/timer-tick`                         — bumps a tick-
                                                     counter on app-db
                                                     to drive a re-
                                                     render.
    `:rf.xray/timer-hover`                        — EVENT: write the
                                                     hovered timer to
                                                     `:rings/hover` (the
                                                     rings use native
                                                     SVG <title>; no sub
                                                     reads the slot).
    `:rf.xray/now-ms`                             — current wall-clock,
                                                     bumped per tick so
                                                     subscribers re-fire
                                                     reactively.
    `:rf.xray/set-now-ms-override-for-test`       — test hook to pin
                                                     `now-ms` to a
                                                     deterministic
                                                     value.

  ## rAF tick driver

  One `requestAnimationFrame` loop per frame ticks at the browser's
  natural frame rate (~60fps). On each tick we bump `:rf.xray/now-ms` —
  every consumer of the ring-fraction sub re-fires on the standard
  reactive path. The loop self-gates: when nothing on screen has a
  DEADLINE left — no armed timer counting down and no `:cancelled`
  ring still inside its retention window — OR the focus is pinned to a
  past event, OR no overlay is mounted in its frame, the next rAF is
  not scheduled; the loop resumes when an overlay mounts or a mounted
  overlay re-renders over a ring that has a deadline.

  If rAF creates perf issues under many concurrent timers, the throttle
  knob lives in `*tick-min-delta-ms*` — bump it to skip-frame when
  fractional delta is below threshold. The default is ~60fps with no
  skip-frame because the typical-app `:after`-timer count is small (1-3).

  ## Substrate

  [[AfterRingsOverlay]] is an `rf.fresco/defview` — a real React
  function component whose three reads are `rf.fresco/sub`, recorded by
  Fresco's own collector rather than by the installed adapter's
  observer, so the overlay's OBSERVATION is not coupled to the adapter.
  Frame isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/xray}]` in `shell.cljs`, which the
  boundary reads out of React context.

  The overlay's markup is NOT pure hiccup, and here that matters. Its
  whole job is to delegate to the
  machines-viz `AfterRingsOverlay`, which is a **Reagent form-3 class**
  (`chart/scaffold/make-resizing-overlay-class`) in a bundle-isolated
  sibling artefact that knows nothing of Fresco. A plain function in
  head position is a loud error under Fresco (HD-016), and CALLING it
  is no repair either — it answers a Reagent CLASS, not
  hiccup. The door is Fresco's own ABI: *\"A React element is a legal
  child anywhere\"* (`re-frame.fresco.impl.codec`'s component-ABI
  table; `child-kind` classifies `react/isValidElement` as
  `:react-element` and `as-element` passes it through untouched). So
  [[overlay-tree]] takes an `:as-child` function — `identity` for a
  hiccup caller, `substrate/as-element` for the boundary — and the
  CLJS props map crosses to machines-viz BY IDENTITY, because
  `as-element` carries the hiccup vector itself rather than converting
  a props object. See [[overlay-tree]]."
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.interop :as rf.interop]
            [day8.re-frame2-xray.substrate :as substrate]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-machines-viz.chart.overlays.after-rings
             :as mv-after-rings]
            ;; The Dynamic-mode single-instance rule
            ;; (`pick-focused-transition`, spec/003 §Dynamic mode) is what
            ;; names the machine the CHART is showing. The rings sub reads
            ;; it so the ring projection and the chart under it can never
            ;; disagree about which machine they are describing.
            ;; That rule takes the operator's explicit
            ;; selection as an ARGUMENT, so this sub must feed it the same
            ;; `:rf.xray/selected-machine-id` the panel feeds it; calling
            ;; the shared fn with different arguments is not sharing the
            ;; rule. See the sub below. Pure-data
            ;; helper ns with no framework require, so no cycle: it is the
            ;; panel's `.cljs` that requires THIS ns, never its helpers.
            [day8.re-frame2-xray.panels.machine-inspector-helpers
             :as mi-h]
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
  ;; override read lives behind `install-test-overrides!`,
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
  ;;   - the FOCUSED-EVENT records, from which the Dynamic-mode
  ;;     single-instance rule names the machine the chart is showing
  ;;   - the inspected TARGET FRAME, so two frames' instances of one
  ;;     machine definition keep their timers apart
  ;;
  ;; Returns a vector of timer records — `:armed` for live rings +
  ;; `:cancelled` for the fading-out + crossed-out rings.
  ;;
  ;; THE INPUTS DECIDE WHICH MACHINE AND WHICH FRAME, and each guards a
  ;; countdown drawn for the wrong thing.
  ;;
  ;;   1. THE MACHINE. `pick-focused-transition` is the SAME rule the
  ;;      Machine Inspector picks its record by — `panel-tree` resolves
  ;;      it once and hands it to both the chart and the Prev/Next nav —
  ;;      so those three cannot drift. Any rule that fell back to a
  ;;      default machine — the first registered id, say — would not: the
  ;;      Dynamic panel has no picker and binds to the focused event's
  ;;      first transition record, so with two machines registered the
  ;;      rings would fold for the default while the chart drew the other
  ;;      one, and the operator would read a countdown that belongs to a
  ;;      machine not on screen.
  ;;
  ;;      An explicit `:rf.xray/select-machine-id` outranks trace order
  ;;      when the focused cascade touched the selected machine, so
  ;;      `:rf.xray/selected-machine-id` is an input here and is passed to
  ;;      the helper's second argument, where it does nothing else.
  ;;      SHARING THE FN IS NOT BY ITSELF THE GUARANTEE — it is sharing
  ;;      the fn AND its arguments. Were the slot fed to the panel's call
  ;;      and not to this one, both would call `pick-focused-transition`
  ;;      and still disagree: with A and B in one cascade and the
  ;;      operator having JUMPed to B, the chart would draw B while the
  ;;      rings counted down A's timers.
  ;;
  ;;   2. THE FRAME. See `rings-h/project-timers` — a definition
  ;;      instantiated in two frames would collide on one fold key. THE
  ;;      DISPLAY SCOPE IS NOT THE COLLECTOR'S TARGET: `:rf.xray/target-frame`
  ;;      DEFAULTS TO NIL (UNSELECTED, EP-0002), so in the posture the
  ;;      panel opens in, passing it straight through would leave the
  ;;      narrowing off, and one machine DEFINITION instantiated in two
  ;;      frames would fold back together: frame A's `/cancelled` would
  ;;      close the record frame B's `/scheduled` opened, and B's LIVE
  ;;      countdown would draw as a grey crossed CANCELLED ring.
  ;;
  ;;      The helper's nil branch is DELIBERATE. Dropping every event
  ;;      when there is genuinely nothing to disambiguate against would
  ;;      blank the rings, which is the worse failure. The frame to give
  ;;      it comes from the focused record:
  ;;      `lifecycle_fx/registration.cljc` stamps `:frame` on every
  ;;      `:rf.machine/transition` emit, so the record that names WHICH
  ;;      MACHINE the chart is drawing names WHICH INSTANCE of it too.
  ;;      That is the same record `pick-focused-transition` answers with,
  ;;      so the machine and its frame cannot drift apart.
  ;;
  ;;      `:rf.xray/target-frame` is an input as the FALLBACK. It is a
  ;;      legitimate COLLECTOR target — which host frame the operator
  ;;      asked to observe — and it answers the DISPLAY question only
  ;;      when the record cannot (a replay whose traces carry no `:frame`
  ;;      stamp). Both nil means NO FILTER.
  ;;
  ;; `:rf.xray/now-ms` is NOT AN INPUT. The fold walks the whole trace
  ;; buffer; with the clock as an input it would re-run on every rAF tick
  ;; — ~60 Hz for as long as ANY timer stays armed — to answer a question
  ;; only the final now-keyed filter needs. The filter runs where the
  ;; clock already is: `overlay-tree` applies `rings-h/prune-timers`
  ;; against the clock it renders with.
  (rf/reg-sub :rf.xray/active-timers-for-focused-machine
    {:inputs [[:rf.xray/trace-buffer]
              [:rf.xray/machine-transitions-for-focused-event]
              [:rf.xray/target-frame]
              [:rf.xray/selected-machine-id]]}
    (fn [[buffer records target-frame selected-machine-id] _query]
      (let [record        (mi-h/pick-focused-transition records
                                                        selected-machine-id)
            machine-id    (:machine-id record)
            display-frame (or (:frame-id record) target-frame)]
        (rings-h/timers-for-machine buffer machine-id display-frame)))))

;; ---- events -------------------------------------------------------------

(defn install-events!
  "Register the rings event family. Idempotent."
  []
  (rf/reg-event :rf.xray/timer-tick
    ;; `:rf.trace/no-emit?` keeps the rAF tick from
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

;; ---- test-only override seam --------------------------------------------

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
;; One rAF loop PER FRAME bumps `:rf.xray/timer-tick` in that frame while
;; `needs-ticking?` says so. Each loop self-gates, stopping when:
;;
;;   - every timer has fired, and every cancelled ring has aged past its
;;     retention window (a `:cancelled` ring is NOT static:
;;     it has a deadline, so the clock outlives the last armed timer by
;;     at most `cancelled-retention-ms`, then stops)
;;   - the frame's spine focus is pinned to a past event (`:mode :retro`)
;;   - the frame's LAST mounted overlay unmounts. The loop is MOUNT-gated,
;;     not only DATA-gated: gated on data alone, a running loop would be
;;     self-sustaining on app-db data, and switching away from the Machine
;;     Inspector while an `:after` timer stays armed would leave it
;;     dispatching `:rf.xray/timer-tick` ~60×/s in the background.
;;
;; The mount gate is a COUNT per frame, kept by the overlay's React ref
;; ([[overlay-ref]]): two overlays in one frame share its clock, so the
;; first to unmount must not freeze the other, and two frames each keep
;; their own clock and their own dispatch target. The ref is also what
;; ARMS the loop on mount, because only a mounted overlay has a ref to
;; disarm it again — an overlay that renders no ring draws no element, so
;; it can never arm a loop nothing would stop.
;;
;; The throttle knob lives in `*tick-min-delta-ms*`. It is 0 (no
;; skip-frame); raise it if many-timer scenarios show jank.

(defonce ^:private tick-state
  ;; `{frame-id {:mounts int :running? bool :last-now-ms long}}`
  ;;   :mounts — how many overlays are mounted in the frame; the
  ;;     [[overlay-ref]] callback counts them in and out.
  ;;   :running? — true while the frame's rAF is queued and undrained.
  ;;   :last-now-ms — last bumped value; the next tick can skip-frame
  ;;     if (- now last) < *tick-min-delta-ms*.
  (atom {}))

(defn- mounted?
  "True while at least one overlay is mounted in `frame`."
  [frame]
  (pos? (get-in @tick-state [frame :mounts] 0)))

(defn- retro?
  "True when `frame`'s spine focus is pinned to a past event, where the
  rings are not drawn (see the ns docstring)."
  [frame]
  (= :retro (:mode @(rf/subscribe [:rf.xray/focus] {:frame frame}))))

(def ^:dynamic *tick-min-delta-ms*
  "Minimum gap (ms) between consecutive `:rf.xray/timer-tick`
  dispatches. 0 = un-throttled (~60fps). Bump to 16/33 to skip-frame
  if many-timer scenarios show jank. Dynamic so tests can rebind."
  0)

(defn- raf!
  "Schedule a callback at the next animation frame. `js/requestAnimationFrame`
  in CLJS; the test surface stubs it via `set!`. Falls back to
  `rf.interop/next-tick` when rAF is unavailable (jsdom under node-test
  where `requestAnimationFrame` may be a no-op or absent — the test
  fixture pins `now-ms` via the override slot anyway, so the fallback
  doesn't drive any real animation, only keeps the dispatch ladder
  alive)."
  [f]
  (cond
    (exists? js/requestAnimationFrame) (js/requestAnimationFrame f)
    :else                              (rf.interop/next-tick f)))

(defn- tick-loop!
  "One iteration of `frame`'s rAF tick loop. Bumps `now-ms` in `frame`
  when ticking is warranted; re-schedules itself iff still needed AND an
  overlay is still mounted there. A frame with no mounted overlay, or a
  focus pinned to a past event, stops the loop with NO dispatch and NO
  reschedule.

  The loop runs as a rAF/next-tick callback, OFF-render, so there is no
  frame scope and an ambient 1-arity `rf/subscribe` raises
  `:rf.error/no-frame-context`, which the `catch` below would swallow
  every tick. Every read and the dispatch therefore name `frame`
  explicitly (Spec 002 §Frame target resolution — the carried invariant)."
  [frame]
  (try
    (if (or (not (mounted? frame)) (retro? frame))
      (swap! tick-state assoc-in [frame :running?] false)
      (let [now      (now-ms)
            ;; The sub is buffer-keyed, so the
            ;; now-keyed eviction runs here, exactly as it does in
            ;; `overlay-tree`. Without it the loop would keep ticking on
            ;; a zombie `:armed` record the overlay has already dropped.
            timers   (rings-h/prune-timers
                       @(rf/subscribe
                          [:rf.xray/active-timers-for-focused-machine]
                          {:frame frame})
                       now)
            last-now (get-in @tick-state [frame :last-now-ms] 0)
            due?     (or (zero? *tick-min-delta-ms*)
                         (>= (- now last-now) *tick-min-delta-ms*))]
        (when due?
          (rf/dispatch [:rf.xray/timer-tick now] {:frame frame})
          (swap! tick-state assoc-in [frame :last-now-ms] now))
        ;; Re-schedule only while STILL mounted AND still data-warranted.
        ;; `now` is the same instant `timers` was pruned against
        ;; above, so a `:cancelled` ring still in the vector is still inside
        ;; its window and the loop keeps running until it ages out.
        (if (and (mounted? frame) (rings-h/needs-ticking? timers now))
          (raf! #(tick-loop! frame))
          (swap! tick-state assoc-in [frame :running?] false))))
    (catch :default _e
      ;; A frame error must not strand the loop in `:running? true`, which
      ;; would block every later kick.
      (swap! tick-state assoc-in [frame :running?] false))))

(defn kick-tick!
  "Start `frame`'s rAF tick loop iff an overlay is mounted there and the
  loop is not already running. Idempotent — the mounted overlay calls it
  on every render that has a deadline on screen, and the `:running?`
  sentinel collapses the duplicates. With nothing mounted it arms
  nothing: the mount itself arms the loop, from [[overlay-ref]]."
  [frame]
  (let [[before after]
        (swap-vals! tick-state
                    (fn [s]
                      (if (and (pos? (get-in s [frame :mounts] 0))
                               (not (get-in s [frame :running?])))
                        (assoc-in s [frame :running?] true)
                        s)))]
    (when (and (get-in after [frame :running?])
               (not (get-in before [frame :running?])))
      (raf! #(tick-loop! frame)))))

(def ^:private overlay-ref
  "The React ref callback for an overlay rendered in `frame`: React calls
  it with the element on MOUNT and with nil on UNMOUNT, and each call
  counts the overlay in or out of `frame`'s mount gate. A mount also arms
  the frame's loop, whose first iteration decides whether it is needed.

  Memoised so each frame gets ONE stable fn: React re-invokes a ref
  callback whose identity changes on every render, which would read as an
  unmount and a mount per render. Returns nil, because React treats a
  returned FUNCTION as the cleanup to run instead of the nil call."
  (memoize
    (fn [frame]
      (fn [el]
        (if (some? el)
          (do (swap! tick-state update-in [frame :mounts] (fnil inc 0))
              (kick-tick! frame))
          (swap! tick-state update-in [frame :mounts]
                 (fn [n] (max 0 (dec (or n 0))))))
        nil))))

(defn stop-tick!
  "Force-stop every frame's rAF tick loop and forget every mount. Tests
  use this to reset between fixtures; production code never calls it (each
  loop self-gates on `needs-ticking?`, the focus mode and its frame's mount
  count)."
  []
  (reset! tick-state {}))

;; ---- view: xyflow ring overlay -----------------------------------------
;;
;; Positioning belongs to the machines-viz
;; `chart.overlays.after-rings/AfterRingsOverlay`, which WALKS the
;; rendered xyflow node DOM (`[data-testid=rf-mv-chart-node-...]`) to
;; find each bearing node's bounding box. xyflow owns node positions,
;; so there is no positioned-graph SVG model here (resolving
;; `{:cx :cy :r}` from elk coordinates + a viewport-transform). This
;; ns is the DATA owner: it projects Xray's trace
;; buffer into presentation-ready ring-specs and drives the rAF tick
;; clock; the machines-viz overlay owns DOM measurement + paint.

(defn- ring-node-id
  "The chart node-id a ring sits on. A timer record names its declaring
  node as a state keyword or path, or as `{<region> <in-region-path>}` for
  a timer declared inside a parallel region — forms `highlight-ids`
  resolves, region-scoped exactly as the chart mints a region's node ids.
  The empty path outside any region is a parallel root's own `:after`, and
  the chart draws that root as its machine-root node."
  [node]
  (if (= [] node)
    chart-layout/machine-root-id
    (first (chart-layout/highlight-ids node))))

(defn- timer-hovered!
  "Wire a ring's hover into the Xray timer-hover slot. The overlay
  passes the bearing node-id back; we re-resolve the timer identity
  from the live spec list so the slot carries the full
  `(machine-id, state, epoch)` tuple a rich tooltip wants.

  `dispatch` is the frame-aware dispatcher [[overlay-tree]] builds from
  the carried frame."
  [dispatch specs node-id]
  (when-let [spec (some (fn [s] (when (= node-id (:node-id s)) s)) specs)]
    (dispatch [:rf.xray/timer-hover
               {:machine-id (:machine-id spec)
                :state      (:state spec)
                :epoch      (:epoch spec)}])))

(defn overlay-tree
  "The overlay's markup, as a plain function of the values the boundary
  READS. It is `defview`'s own documented extract-a-helper spelling and
  the same split `routing/panel-tree` and `resources/panel-tree` make.
  A `defview` is a real React component and cannot be CALLED, so this
  split is what lets the hiccup-walking rows in
  `machine_after_rings_cljs_test` assert on the hiccup directly.

  Args (one map):

    :timers         — `:rf.xray/active-timers-for-focused-machine`, the
                      BUFFER-keyed fold. This fn applies the now-keyed
                      eviction itself.
    :live-now       — `:rf.xray/now-ms`, the rAF-bumped live clock.
    :mode           — the spine focus's `:mode` (`:rf.xray/focus`);
                      `:retro` draws nothing (see the ns docstring).
    :frame          — the frame this tree renders in
                      (`rf/current-frame-id`). It arms the off-render
                      rAF clock through `kick-tick!` and is the frame
                      the hover / leave dispatches CARRY.
    :as-child       — how to spell the delegated machines-viz child for
                      the calling renderer. `identity` (the default)
                      leaves it as hiccup, which is what a Reagent
                      parent and the node-lane rows want;
                      `substrate/as-element` answers a React element,
                      which is what a Fresco body needs. The ns
                      docstring records why neither of the
                      usual repairs — mount it as a head, or CALL it —
                      is available for a Reagent class.

  Returns nil when the focus is pinned to a past event, or when the
  projection has no active timers (the overlay layer drops out so
  unrelated chart hover handlers aren't shadowed).

  NOT side-effect free, deliberately: a re-render over a ring that still
  has a deadline re-kicks the frame's rAF clock, which a mounted overlay
  needs once its loop has stopped and a new timer arms."
  [{:keys [timers live-now mode frame as-child]
    :or   {as-child identity}}]
  (when-not (= :retro mode)
    (let [now      live-now
          ;; The NOW-KEYED half of the rings projection. The sub hands
          ;; over the buffer-keyed fold; the eviction runs HERE. It is
          ;; what bounds a `:cancelled` ring's life to
          ;; `cancelled-retention-ms` and keeps one crossed ring per node,
          ;; instead of one per visit stacked under the overlay's single
          ;; `:node-id` React key.
          timers   (rings-h/prune-timers timers now)
          ;; `defview` binds NO name inside a body, so the frame-aware
          ;; dispatcher is built here from the carried frame — the
          ;; surrounding instance frame, never a `{:frame :rf/xray}` literal.
          dispatch (fn [event-v] (rf/dispatch event-v {:frame frame}))
          specs    (rings-h/timers->ring-specs timers ring-node-id now)]
      (when (seq specs)
        ;; One clock per frame, never one per ring; the machines-viz
        ;; overlay runs none of its own and only re-measures the DOM when
        ;; `:tick` bumps. `now` is the instant `timers` was just pruned
        ;; against, so a crossed ring still on screen still gets a clock.
        (when (rings-h/needs-ticking? timers now)
          (kick-tick! frame))
        [:div {:data-rf-xray-after-rings-host ""
               ;; Counts this overlay in and out of the frame's tick-loop
               ;; mount gate. Stable per frame, so React fires it only on
               ;; mount (node) / unmount (nil), never per re-render; Fresco's
               ;; codec passes `:ref` through untouched.
               :ref   (overlay-ref frame)
               :style {:display "contents"}}
         (as-child
           [mv-after-rings/AfterRingsOverlay
            {:ring-specs specs
             ;; Bumping `:tick` every frame makes the overlay re-measure
             ;; the DOM and repaint the swept arcs.
             :tick       now
             :testid     "rf-xray-machine-inspector-after-rings-overlay"
             :on-hover   (fn [node-id] (timer-hovered! dispatch specs node-id))
             :on-leave   (fn [_node-id]
                           (dispatch [:rf.xray/timer-hover nil]))}])]))))

(rf.fresco/defview AfterRingsOverlay
  "Mounts the focused machine's `:after` countdown rings over the
  xyflow chart. Subscribes to the active timers, the live clock and the
  spine focus; projects each timer into a presentation-ready ring-spec;
  keeps the frame's rAF clock running while a ring has a deadline; then
  delegates positioning + paint to the machines-viz `AfterRingsOverlay`,
  which walks the xyflow node DOM. Draws nothing while the focus is
  pinned to a past event.

  It is a boundary rather than a plain fn because `machine_canvas`'s
  `Chart-view` heads it inside a Fresco tree, where a plain fn in head
  position raises `:rf.error/fresco-bad-head`. A boundary reads its
  frame from the same `re-frame.adapter.context` React context that
  `rf/frame-provider` writes, so the three reads resolve through
  `:rf/xray`. The reads are `rf.fresco/sub`, recorded by Fresco's
  collector rather than by the installed adapter's.

  Two consequences of `defview`'s contract, both load-bearing here:

    * It binds NO name inside the body, so there is no injected
      `dispatch`; [[overlay-tree]] builds one from the carried frame.
    * It takes ONE props map, which it does not read. Node-lane rows
      drive [[overlay-tree]] directly.

  It takes no positioned-graph / viewport-transform args
  (xyflow owns positions; the overlay reads them off the DOM).

  Returns nil when the focus is pinned to a past event or the projection
  has no active timers (the overlay layer drops out so unrelated chart
  hover handlers aren't shadowed).

  ## DOM-rooted via `display: contents`

  The body delegates to the machines-viz overlay component, whose
  React-element head is a function (not a DOM tag). A bare component
  head as the view root would skip the source-coord DOM
  annotation (Spec 006 §Source-coord annotation: pair tools fall back
  to `:rf/id` for non-DOM roots).
  A wrapper `<div>` with `display: contents` participates in the DOM
  tree (so `data-rf2-source-coord` + `data-rf-view` have a home and
  click-to-source works) while being neutralised for layout — the
  inner machines-viz overlay's `position: absolute` still anchors to
  the chart's `position: relative` wrapper (the offsetParent the
  overlay queries to measure node rects is unaffected because a
  `display: contents` element is not a positioning context). Same
  pattern as `shell.cljs`."
  [_props]
  ;; The three reads, all unconditional. `rf.fresco/sub` records its edge
  ;; WHERE THE READ HAPPENS, so all three live here in the body and the
  ;; dependency set is exactly these three. `:rf.xray/focus` is read by
  ;; keyword (the registrar resolves subs at runtime), keeping this ns out
  ;; of the `registry.cljs` require cycle.
  ;;
  ;; `rf/current-frame-id` is one of core's PURE frame doors,
  ;; which `impl.intent/with-frame` answers with the boundary's DECLARED
  ;; frame precisely because it neither reads nor dispatches. So the
  ;; surrounding instance frame reaches the off-render rAF clock
  ;; and the hover / leave dispatches, and it is not a `:rf/xray`
  ;; literal.
  (overlay-tree
    {:timers   (rf.fresco/sub [:rf.xray/active-timers-for-focused-machine])
     :live-now (rf.fresco/sub [:rf.xray/now-ms])
     :mode     (:mode (rf.fresco/sub [:rf.xray/focus]))
     :frame    (rf/current-frame-id)
     ;; The machines-viz overlay is a Reagent class, so it reaches React
     ;; as a finished React ELEMENT — a legal child anywhere per Fresco's
     ;; component ABI — rather than as a hiccup head. `substrate/as-element`
     ;; carries the hiccup vector itself, so the CLJS props map crosses
     ;; BY IDENTITY; see the ns docstring.
     :as-child substrate/as-element}))

;; ---- public install entry -----------------------------------------------

(defn install!
  "Idempotent install — called by `machine_inspector/install!`."
  []
  (install-subs!)
  (install-events!))
