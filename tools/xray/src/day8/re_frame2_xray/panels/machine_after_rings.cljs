(ns day8.re-frame2-xray.panels.machine-after-rings
  "Machine Inspector `:after` timer countdown rings — mount + tick driver.

  Closes the wall-clock-time visualisation gap on the chart per
  `spec/019-Cross-Cutting-Insight.md` §Wall-clock time is under-served:
  every armed `:after` timer draws a countdown ring AROUND its bearing
  state-node. In live mode the ring sweeps from full → empty in real
  time; in retrospective mode (scrubber not at `:present`) it freezes
  at the elapsed-fraction the timer had reached at the FOCUSED
  CASCADE's timestamp (xray/003 §M.2) — `AfterRingsOverlay` resolves
  the `now-ms` anchor via `machine-after-rings-helpers/resolve-now-ms`:
  the rAF-bumped live clock in `:present`/LIVE mode, the focused
  event-bundle's dispatched time otherwise. Feeding the live clock in
  unconditionally would freeze a retro ring wherever the clock happened
  to last sit when the loop stopped, not at the cascade the operator is
  actually looking at.

  ## Architecture

  POSITIONING is not this ns's job: xyflow owns node positions in the
  rendered DOM, so the overlay must WALK the DOM rather than read a
  positioned graph. The split is:

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
    `:rf.xray/timer-hover`                        — set the hovered
                                                     timer for tooltip
                                                     state (the rings
                                                     use native SVG
                                                     <title>, so the
                                                     slot is plumbed
                                                     for a richer
                                                     tooltip).
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
  reactive path. The loop self-gates: when nothing on screen has a
  DEADLINE left — no armed timer counting down and no `:cancelled`
  ring still inside its retention window — OR the
  scrubber is in retrospective mode, the next rAF is not scheduled;
  the loop resumes when a fresh `:scheduled` event arrives (the panel
  re-evaluates `needs-ticking?` on every render and kicks the loop
  when truthy).

  If rAF creates perf issues under many concurrent timers, the throttle
  knob lives in `*tick-min-delta-ms*` — bump it to skip-frame when
  fractional delta is below threshold. The default is ~60fps with no
  skip-frame because the typical-app `:after`-timer count is small (1-3).

  ## Substrate

  [[AfterRingsOverlay]] is an `rf.fresco/defview` — a real React
  function component whose four reads are `rf.fresco/sub`, recorded by
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
            [day8.re-frame2-xray.defaults :as defaults]
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
  ;;      so those three cannot drift. Reading `(:selected-id mi-data)`
  ;;      off `:rf.xray/machine-inspector-data` instead would take
  ;;      `pick-selected`'s answer: the PICKER slot when one is set, else
  ;;      the first row of a list `project-machine-rows` sorts
  ;;      ALPHABETICALLY. The Dynamic panel has no picker — it binds to
  ;;      the focused event's first transition record — so with two
  ;;      machines registered the rings would fold for whichever id
  ;;      sorted first while the chart drew the other one, and the
  ;;      operator would read a countdown that belongs to a machine not
  ;;      on screen.
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
  ;; against the anchor `resolve-now-ms` picks, which also keeps retro
  ;; mode honest — evicting against the LIVE clock would age rings even
  ;; while the chart is frozen at the focused cascade's instant.
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
        (rings-h/timers-for-machine buffer machine-id display-frame))))

  ;; ---- timer-hover slot ----------------------------------------------
  ;;
  ;; The hovered timer, so a rich side-rail tooltip can surface it
  ;; without touching subscriber wiring; the rings themselves use native
  ;; SVG <title>.
  (rf/reg-sub :rf.xray/timer-hover
    (fn [db _query]
      (get db :rings/hover))))

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
;; A single rAF loop that bumps `:rf.xray/timer-tick` per frame when
;; `needs-ticking?` says so. The loop self-gates so it stops when:
;;
;;   - every timer has fired, and every cancelled ring has aged past its
;;     retention window (a `:cancelled` ring is NOT static:
;;     it has a deadline, so the clock outlives the last armed timer by
;;     at most `cancelled-retention-ms`, then stops)
;;   - the scrubber leaves `:present`
;;   - the `AfterRingsOverlay` UNMOUNTS — the loop is
;;     MOUNT-gated, not only DATA-gated. Gated on data alone, an
;;     ALREADY-RUNNING loop would be self-sustaining on app-db data:
;;     switching away from the Machine Inspector while an `:after` timer
;;     stays armed + the scrubber sits at `:present` would leave the loop
;;     dispatching `:rf.xray/timer-tick` ~60×/s in the background,
;;     outliving its panel (unbounded CPU / battery drain). The overlay
;;     owns a `:mounted?` liveness flag (set true on
;;     render via `kick-tick!`, cleared on unmount via the `overlay-ref!`
;;     React ref callback); `tick-loop!` requires it before dispatching /
;;     re-scheduling, so unmount stops the loop within one frame. Remount
;;     re-arms via `kick-tick!`.
;;
;; The throttle knob lives in `*tick-min-delta-ms*`. It is 0 (no
;; skip-frame); raise it if many-timer scenarios show jank.

(defonce ^:private tick-state
  ;; `{:running? bool :last-now-ms long :frame <frame-id> :mounted? bool}`
  ;;   :running? — true while a rAF is queued and undrained.
  ;;   :last-now-ms — last bumped value; the next tick can skip-frame
  ;;     if (- now last) < *tick-min-delta-ms*.
  ;;   :frame — the surrounding instance frame captured by
  ;;     the overlay at `kick-tick!` time, so the off-render
  ;;     rAF tick dispatch lands on it (defaults to default-frame-id).
  ;;   :mounted? — the overlay's liveness. `kick-tick!` sets it
  ;;     true (called only from the mounted overlay's render); the
  ;;     `overlay-ref!` React ref callback clears it on unmount. `tick-loop!`
  ;;     requires it, so the loop can never outlive the panel.
  (atom {:running?    false
         :last-now-ms 0
         :frame       defaults/default-frame-id
         :mounted?    false}))

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
  "One iteration of the rAF tick loop. Reads the active-timers sub +
  scrubber sub; bumps `now-ms` when ticking is warranted; re-schedules
  itself iff still needed AND the overlay is still mounted.

  MOUNT gate. The loop is gated on the overlay's
  `:mounted?` liveness, not only on app-db data (`needs-ticking?`). An
  UNMOUNTED overlay stops the loop IMMEDIATELY — no dispatch, no reschedule
  — even if an `:after` timer stays armed + the scrubber sits at `:present`.
  A loop gated on data alone would be self-sustaining on app-db state and
  keep dispatching `:rf.xray/timer-tick` ~60×/s in the background after the
  panel unmounted (switch to another L3 tab). The overlay owns `:mounted?` via
  `kick-tick!` (sets true on render) + `overlay-ref!` (clears on unmount).

  The loop runs as a rAF/next-tick callback, OFF-render:
  there is no established frame scope (no `with-frame`, no enclosing
  Provider, no carried `*current-frame*` stamp), so the ambient
  1-arity `rf/subscribe` has NO `:rf/default` floor and raises
  `:rf.error/no-frame-context` (subs.cljc, frame.cljc) — which the `catch`
  below would swallow every tick, silently killing the loop
  after its first iteration. So the two reads below use the PUBLIC
  explicit-frame opts form `(rf/subscribe query-v {:frame frame})`
  (subs.cljc §Lookup algorithm), targeting the frame captured in
  `tick-state` at `kick-tick!` time — the same frame the
  dispatch below targets."
  []
  (try
    (if-not (:mounted? @tick-state)
      ;; The overlay unmounted (e.g. the user switched L3 tab).
      ;; Stop the loop with NO dispatch + NO reschedule even while an :after
      ;; timer stays armed + the scrubber sits at :present, so the loop can
      ;; never outlive its panel. A remount re-arms via `kick-tick!`.
      (swap! tick-state assoc :running? false)
      (let [frame    (:frame @tick-state)
            scrub    @(rf/subscribe [:rf.xray/machine-scrubber-position]
                                    {:frame frame})
            now      (now-ms)
            ;; The sub is buffer-keyed, so the
            ;; now-keyed eviction runs here, exactly as it does in
            ;; `overlay-tree`. Without it the loop would keep ticking on
            ;; a zombie `:armed` record the overlay has already dropped.
            timers   (rings-h/prune-timers
                       @(rf/subscribe
                          [:rf.xray/active-timers-for-focused-machine]
                          {:frame frame})
                       now)
            last-now (:last-now-ms @tick-state)
            due?     (or (zero? *tick-min-delta-ms*)
                         (>= (- now last-now) *tick-min-delta-ms*))]
        (when due?
          ;; The rAF tick loop runs outside the render scope;
          ;; dispatch into the frame captured at `kick-tick!` (the
          ;; surrounding instance frame at the time the overlay armed the
          ;; clock), not a `{:frame :rf/xray}` literal.
          (rf/dispatch [:rf.xray/timer-tick now]
                       {:frame frame})
          (swap! tick-state assoc :last-now-ms now))
        ;; Re-schedule only while STILL mounted AND still data-warranted.
        ;; `now` is the same instant `timers` was pruned against
        ;; above, so a `:cancelled` ring still in the vector is still inside
        ;; its window and the loop keeps running until it ages out.
        (if (and (:mounted? @tick-state)
                 (rings-h/needs-ticking? timers scrub now))
          (raf! tick-loop!)
          (swap! tick-state assoc :running? false))))
    (catch :default _e
      ;; Defensive: a frame error must not strand the loop in
      ;; `:running? true` (would block future kicks).
      (swap! tick-state assoc :running? false))))

(defn kick-tick!
  "Start the rAF tick loop iff not already running. Idempotent —
  callers (the rings overlay component, mounted per render) call this
  on every render; the `:running?` sentinel prevents duplicate loops.

  `kick-tick!` is only ever called from the MOUNTED overlay's
  active render, so it stamps `:mounted? true` (the overlay is live). This
  also closes the boot race: on the first render the `overlay-ref!` callback
  fires only after React commits, so `:mounted? true` is set here BEFORE the
  first off-render `tick-loop!` iteration runs. The `overlay-ref!` callback
  clears `:mounted?` on unmount.

  `frame` is the surrounding instance frame the overlay
  captured at render; stashed so the off-render tick dispatch
  lands on it. Defaults to `defaults/default-frame-id` for the test
  seam / direct callers."
  ([] (kick-tick! defaults/default-frame-id))
  ([frame]
   ;; Record the captured frame + live liveness before arming so `tick-loop!`
   ;; reads them (a render IS a mount signal).
   (swap! tick-state assoc :frame frame :mounted? true)
   (when (compare-and-set!
           tick-state
           (assoc @tick-state :running? false)
           (assoc @tick-state :running? true))
     (raf! tick-loop!))))

(defn- overlay-ref!
  "React ref callback owning the overlay's `:mounted?` liveness in
  `tick-state`. React invokes it with the DOM node on MOUNT and
  `nil` on UNMOUNT. A stable top-level fn identity means React calls it ONLY on
  mount / unmount (never on a re-render), so it is a clean lifecycle signal.
  Clearing `:mounted?` on unmount is what lets `tick-loop!` stop the rAF loop
  within one frame even while an `:after` timer stays armed — the loop can
  never outlive the panel."
  [el]
  (swap! tick-state assoc :mounted? (some? el)))

(defn stop-tick!
  "Force-stop the rAF tick loop. Tests use this to reset between
  fixtures; production code never calls this directly (the loop
  self-gates on `needs-ticking?` + the overlay's `:mounted?` liveness)."
  []
  (swap! tick-state assoc :running? false))

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
    :scrub          — `:rf.xray/machine-scrubber-position`.
    :focused-detail — `:rf.xray/focused-event-bundle-detail`; the
                      retro-mode anchor source.
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

  Returns nil when the projection has no active timers (the overlay
  layer drops out so unrelated chart hover handlers aren't shadowed).

  NOT side-effect free, deliberately: it kicks the single per-chart rAF
  clock during render (Lock #8). Moving that out would change WHEN the
  clock arms, which is a behaviour change wearing a tidy-up's clothes."
  [{:keys [timers live-now scrub focused-detail frame as-child]
    :or   {as-child identity}}]
  (let [focused-ms (rings-h/focused-cascade-time-ms focused-detail)
        now        (rings-h/resolve-now-ms scrub live-now focused-ms)
        ;; The NOW-KEYED half of the rings projection. The
        ;; sub hands over the buffer-keyed fold; the eviction runs HERE,
        ;; against the anchor `resolve-now-ms` just resolved, so a
        ;; retrospective chart ages its rings at the instant it is frozen
        ;; at rather than at the live clock. It is also what bounds a
        ;; `:cancelled` ring's life to `cancelled-retention-ms` and keeps
        ;; one crossed ring per node, instead of one per visit stacked
        ;; under the overlay's single `:node-id` React key.
        timers     (rings-h/prune-timers timers now)
        ;; `defview` binds NO name inside a body, so the frame-aware
        ;; dispatcher is built here from the carried frame — the
        ;; surrounding instance frame, never a `{:frame :rf/xray}` literal.
        dispatch   (fn [event-v] (rf/dispatch event-v {:frame frame}))
        ;; Kick the rAF loop iff ticking is needed (live mode + at
        ;; least one armed timer). Cheap to call per render — the
        ;; `:running?` sentinel collapses duplicate kicks. Per Lock #8
        ;; this is the SINGLE per-chart clock (O(charts), not
        ;; O(rings × charts)); the machines-viz overlay runs no clock
        ;; of its own — it just re-measures the DOM when `:tick` bumps.
        ;; `now` is the anchor `timers` was just pruned against,
        ;; so a crossed ring that is still on screen still gets a clock.
        _          (when (rings-h/needs-ticking? timers scrub now)
                     (kick-tick! frame))
        specs      (rings-h/timers->ring-specs
                     timers chart-layout/highlight-id now)]
    (when (seq specs)
      [:div {:data-rf-xray-after-rings-host ""
             ;; The React ref that clears `:mounted?` on unmount
             ;; so the off-render rAF tick loop stops when this overlay leaves
             ;; the screen (switch away from the Machine Inspector) even while
             ;; an `:after` timer stays armed. Stable fn identity ⇒ React fires
             ;; it only on mount (node) / unmount (nil), never per re-render.
             ;; Fresco's codec passes `:ref` through untouched, so the
             ;; lifecycle is the same one under either renderer.
             :ref overlay-ref!
             :style {:display "contents"}}
       (as-child
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
                         (dispatch [:rf.xray/timer-hover nil]))}])])))

(rf.fresco/defview AfterRingsOverlay
  "Mounts the focused machine's `:after` countdown rings over the
  xyflow chart. Subscribes to the active timers + now-ms + scrubber
  internally; projects each timer into a presentation-ready ring-spec
  (scrubber-aware fraction baked in — retrospective mode freezes the
  ring at the scrubber's anchor instant); kicks the single per-chart
  rAF clock in live mode; then delegates positioning + paint to the
  machines-viz `AfterRingsOverlay`, which walks the xyflow node DOM.

  It is a component rather than a plain fn because of the frame:
  rendered as a plain fn under `[rf/frame-provider {:frame :rf/xray}]`,
  the four subscribes would route to `:rf/default` (the host app's
  frame), which is a real frame-leak — xray-internal slots must be read
  via xray's own frame. A boundary reads its frame from the same
  `re-frame.adapter.context` React context that `rf/frame-provider`
  writes, so the four reads resolve through `:rf/xray`. The reads are
  `rf.fresco/sub`, recorded by Fresco's collector rather than by the
  installed adapter's.

  Two consequences of `defview`'s contract, both load-bearing here:

    * It binds NO name inside the body, so there is no injected
      `dispatch`; [[overlay-tree]] builds one from the carried frame.
    * It takes ONE props map, which it does not read. Node-lane rows
      drive [[overlay-tree]] directly.

  It takes no positioned-graph / viewport-transform args
  (xyflow owns positions; the overlay reads them off the DOM).

  Returns nil when the projection has no active timers (the overlay
  layer drops out so unrelated chart hover handlers aren't shadowed).

  ## DOM-rooted via `display: contents`

  The body delegates to the machines-viz overlay component, whose
  React-element head is a function (not a DOM tag). A bare component
  head as the view root would skip the source-coord DOM
  annotation (Spec 006 §Source-coord annotation: pair tools fall back
  to `:rf/id` for non-DOM roots) and emit a one-shot warning per id.
  A wrapper `<div>` with `display: contents` participates in the DOM
  tree (so `data-rf2-source-coord` + `data-rf-view` have a home and
  click-to-source works) while being neutralised for layout — the
  inner machines-viz overlay's `position: absolute` still anchors to
  the chart's `position: relative` wrapper (the offsetParent the
  overlay queries to measure node rects is unaffected because a
  `display: contents` element is not a positioning context). Same
  pattern as `shell.cljs`."
  [_props]
  ;; The four reads, all unconditional. `rf.fresco/sub` records its edge
  ;; WHERE THE READ HAPPENS, so all four live here in the body and the
  ;; dependency set is exactly these four.
  ;;
  ;; xray/003 §M.2: in RETRO mode ('scrubber-driven') the
  ;; ring must freeze at the elapsed-fraction the timer had reached at
  ;; the FOCUSED CASCADE's timestamp, not wherever the live clock last
  ;; sat when the tick loop stopped. `:rf.xray/focused-event-bundle-detail`
  ;; is the same cross-panel primitive the Epoch / App-db-diff panels
  ;; read to find "the event-bundle the spine is pointing at"; referenced
  ;; by keyword (no ns require — re-frame's registrar resolves subs at
  ;; runtime, not compile-time, keeping this ns out of the
  ;; `registry.cljs` require cycle).
  ;;
  ;; `rf/current-frame-id` is one of core's PURE frame doors,
  ;; which `impl.intent/with-frame` answers with the boundary's DECLARED
  ;; frame precisely because it neither reads nor dispatches. So the
  ;; surrounding instance frame reaches the off-render rAF clock
  ;; and the hover / leave dispatches, and it is not a `:rf/xray`
  ;; literal.
  (overlay-tree
    {:timers         (rf.fresco/sub [:rf.xray/active-timers-for-focused-machine])
     :live-now       (rf.fresco/sub [:rf.xray/now-ms])
     :scrub          (rf.fresco/sub [:rf.xray/machine-scrubber-position])
     :focused-detail (rf.fresco/sub [:rf.xray/focused-event-bundle-detail])
     :frame          (rf/current-frame-id)
     ;; The machines-viz overlay is a Reagent class, so it reaches React
     ;; as a finished React ELEMENT — a legal child anywhere per Fresco's
     ;; component ABI — rather than as a hiccup head. `substrate/as-element`
     ;; carries the hiccup vector itself, so the CLJS props map crosses
     ;; BY IDENTITY; see the ns docstring.
     :as-child       substrate/as-element}))

;; ---- the Reagent bridge -------------------------------------------------
;;
;; SCAFFOLDING WITH A DEFINED END. `Chart` in
;; `panels/machine_canvas.cljs` is a `reg-view`, so it mounts this
;; overlay from a REAGENT tree, and a boundary is not a Reagent render
;; fn. `rf.fresco/as-component` is Fresco's own outward door for exactly
;; that; the crossing is an EMPTY props map, which is the only shape that
;; survives a Reagent parent's `convert-prop-value` (a payload does not).
;; The overlay has no payload to cross, so nothing is given up here.
;;
;; Once `Chart`'s callers are themselves a Fresco tree, `Chart` can mount
;; `AfterRingsOverlay` directly and neither of these is needed.
;;
;; The boundary keeps the NATURAL name and the caller is handed a PUBLIC
;; bridge. Nothing mounts the boundary var by name from a file that cannot
;; take the bridge, so nothing forces the opposite spelling: the sole
;; caller is `machine_canvas/Chart`.

(def ^:private AfterRingsOverlay-component
  "The React component [[AfterRingsOverlay]] presents as, for a
  non-Fresco parent. Declared ONCE at top level, as
  `rf.fresco/as-component`'s own docstring requires — a fresh one per
  render is a fresh element type and would remount the subtree on every
  pass, taking the machines-viz overlay's measured state with it."
  (rf.fresco/as-component AfterRingsOverlay))

(defn AfterRingsOverlay-bridge
  "Mount [[AfterRingsOverlay]] from a Reagent hiccup tree. Public
  because its one consumer is `panels/machine_canvas.cljs`'s `Chart`,
  a different namespace."
  []
  [:> AfterRingsOverlay-component {}])

;; ---- public install entry -----------------------------------------------

(defn install!
  "Idempotent install — called by `machine_inspector/install!`."
  []
  (install-subs!)
  (install-events!))
