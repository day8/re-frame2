(ns day8.re-frame2-xray.panels.machine-after-rings-fresco-boundary-dom-cljs-test
  "THE `:after`-RINGS OVERLAY RE-AUTHORED IN THE RE-FRAME-NATIVE VIEW LAYER,
  read off a real React commit (rf2-k97c.3).

  `machine-after-rings/AfterRingsOverlay` is now an `rf.fresco/defview`
  reading its four slots through Fresco's shipped collector rather than an
  `rf/reg-view` reading through whatever view build the installed adapter
  supplies. This file is the behavioural evidence, and it follows
  `module_view_fresco_boundary_dom_cljs_test`'s four-row template.

  ## What is DIFFERENT about this boundary, and why two rows here are new

  Every panel migrated before this one rendered its own hiccup all the way
  down. This one's whole job is to DELEGATE to the machines-viz
  `AfterRingsOverlay`, which is a **Reagent component** living in a
  bundle-isolated sibling artefact that knows nothing of Fresco. Fresco
  admits neither spelling the migration has used so far:

    * as a hiccup HEAD it is a plain function, which is a loud error
      (HD-016);
    * CALLED — the repair used for `mini`, `frame-row`, `code-block` and
      Resources' 37 section helpers — it answers a Reagent CLASS rather
      than hiccup, so calling it is a different bug rather than a fix.

  The door taken is Fresco's own component ABI: *\"A React element is a
  legal child anywhere\"* (`re-frame.fresco.impl.codec`; `child-kind`
  classifies `react/isValidElement` as `:react-element` and `as-element`
  passes it through untouched). `overlay-tree`'s `:as-child` is
  `reagent.core/as-element` at the boundary, so Reagent's own renderer
  builds the element and the CLJS props map crosses BY IDENTITY.

  Two rows exist for that seam and would not appear in a template panel:

    * W1 asserts the machines-viz overlay's OWN committed DOM
      (`data-ring-count`), which exists only if the React element really
      crossed the substrate seam and Reagent really rendered it. A
      migration that broke the crossing paints nothing there.
    * W5 asserts the rf2-e64drj MOUNT GATE still works — the overlay's
      `:ref` callback clears the rAF tick loop's `:mounted?` liveness on
      unmount. `:ref` is one of the two structural slots Fresco carries
      untouched, and an off-render 60Hz loop that outlives its panel is
      the exact regression that contract was written for.

  ## Criterion 3 is answered in the NODE lane, deliberately

  The overlay's interactions are `:on-hover` / `:on-leave`, which the
  machines-viz overlay fires from ring `<g>` elements it only paints after
  MEASURING a rendered xyflow chart. Standing a real chart up here to
  produce a hover target would make the row about xyflow's layout rather
  than about the boundary. `machine_after_rings_cljs_test`'s
  `hover-dispatch-lands-on-the-render-frame` makes the claim instead, and
  makes it in the sharper place: it fires the handler OUTSIDE any
  `with-frame`, which is what a real mouseenter does, and asserts the
  event landed in the frame the tree named and NOT in `:rf/default`.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium). The `:node-test` build's
  `cljs-test$` regex also matches, so it LOADS under Node — where every
  row short-circuits through [[browser?]] and reports the skip rather
  than passing silently."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(def ^:private app-frame
  "An ORDINARY application frame — the thing Xray inspects. W1 reads it as
  the negative half of the frame-targeting claim, and W3 mounts INSIDE it
  so the evidence claim cannot be carried by `:rf/xray`'s trace-disabled
  gate."
  ::app)

(def ^:private timers-q
  "The overlay's primary read. Named once because three rows key off it —
  the sub-cache is keyed by the query vector itself
  (`re-frame.subs/cache-key` is identity), so this value IS the cache key."
  [:rf.xray/active-timers-for-focused-machine])

(def ^:private overlay-sel
  "The machines-viz overlay's own committed root. Reaching for THIS rather
  than for the Xray wrapper is the point of W1: the wrapper is emitted by
  the boundary, but this node exists only if the React element crossed
  into React and Reagent rendered the foreign component behind it."
  "[data-testid=\"rf-xray-machine-inspector-after-rings-overlay\"]")

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle    {:on    {:start :authing}
                       :after {5000 :timeout}}
             :authing {:on {:ok :done}}
             :timeout {:on {:retry :idle}}
             :done    {:final? true}}})

;; ---- a probe event, and the `reg-view` W3 measures the boundary against ----

(rf/reg-event ::bump
  (fn [{:keys [db]} [_ n]] {:db (assoc db ::n n)}))

(rf/reg-sub ::n (fn [db _] (::n db)))

(rf/reg-view ProbeRegView
  "The POSITIVE CONTROL for W3, and nothing else. An ordinary `reg-view`
  rendered by the installed adapter in the same root, in the same frame,
  in the same commit as the boundary — so the only variable between it and
  the overlay is which view layer authored the body."
  []
  [:div {:data-testid "rf-xray-probe-reg-view"} (str @(rf/subscribe [::n]))])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     ;; `:ambient-frame nil` is load-bearing: the fixture's default ambient
     ;; `:rf/default` scope would SHADOW the React-context tier W1 is about,
     ;; and the frame-targeting row would pass while measuring nothing.
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; The boundary kicks the single per-chart rAF clock
                      ;; from its render, so a row that mounts leaves a loop
                      ;; armed for the next one.
                      (after-rings/stop-tick!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about; a
                      ;; neighbour's boundary left in the entry cache would
                      ;; make W4's release row read a residue that is not
                      ;; this overlay's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; NO `flush-render!` HELPER HERE, and its absence is a finding rather than an
;; omission — increment 1 measured it. A Fresco boundary is NOT in Reagent's
;; render queue, so draining Reagent's queue commits nothing of this overlay's,
;; and a row written that way reads a DOM that has not moved and reports a live
;; panel as dead. Mount is committed with `flushSync` (React's own door) and
;; everything after it is polled.

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask. It exists
  for the CONTROL in W2: an absence asserted immediately after an event is
  a race, and an absence asserted after this is a decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- setup!
  "Register Xray's handlers, install the test-only `now-ms` override seam
  (the rows pin a deterministic clock through it), and make both frames."
  []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- seed-one-armed-timer!
  "Put exactly one ARMED `:after` timer in front of the overlay. Without
  this the projection is empty and the overlay returns nil, so every row
  that asserts DOM depends on it — and W1 checks it worked.

  TARGETS `:rf/xray` AND TAKES NO FRAME ARGUMENT, because it cannot
  honestly offer one. `trace-collector/refresh-trace-rings!`, which
  `seed-trace-for-test!` calls, snapshots the rings into **`:rf/xray`'s**
  `:trace-buffer` slot and its own docstring says the dispatch is a
  silent no-op when `:rf/xray` is not registered. Seeding \"into\" any
  other frame would therefore leave `:rf.xray/trace-buffer` empty there,
  the projection empty, and the overlay rendering nil — while the three
  override dispatches above all appeared to succeed. An earlier draft of
  W3 did exactly that and would have asserted a zero against a container
  with nothing in it."
  []
  (rf/dispatch-sync [:rf.xray/set-registered-machines-override-for-test
                     [:auth/login]] {:frame :rf/xray})
  (rf/dispatch-sync [:rf.xray/set-machine-definitions-override-for-test
                     {:auth/login fixture-definition}] {:frame :rf/xray})
  (rf/dispatch-sync [:rf.xray/set-now-ms-override-for-test 2000]
                    {:frame :rf/xray})
  (trace-collector/seed-trace-for-test!
    {:id 1000 :time 1000
     :operation :rf.machine.timer/scheduled
     :tags {:machine-id :auth/login
            :state :idle
            :delay 5000
            :delay-source :literal
            :epoch 0}})
  nil)

(defn- mount-overlay!
  "Mount the overlay the way `machine-canvas/Chart` mounts it: through
  `AfterRingsOverlay-bridge`, the PUBLIC var the caller actually holds,
  inside a `frame-provider` scoping `frame`. Reaching for the bridge
  rather than for the boundary is deliberate — a bridge that regressed to
  something a Reagent tree cannot mount reddens here rather than in a
  browser. Committed synchronously; React 19's `root.render` is otherwise
  async and W1 would assert against an empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [after-rings/AfterRingsOverlay-bridge]])))
    {:container container :root root}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — which is where
  the collector releases a boundary's reads and where the `:ref` callback
  clears the tick loop's liveness — have RUN by the time the next line
  reads them. A bare `.unmount` schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- q [container sel] (.querySelector container sel))

(defn- tick-attr
  "The `data-tick` the machines-viz overlay committed — the instant this
  ns's clock handed it. Read off the DOM rather than off props, because
  props are what the boundary SENT and this is what React received."
  [container]
  (some-> (q container overlay-sel) (.getAttribute "data-tick")))

(defn- cache-of
  "The frame's live sub-cache map. Not `some->`-guarded: a nil here means
  the frame is not live, which is a defect in the row's own setup and
  should throw rather than read as an empty cache."
  [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of
  "The sub-cache ref-count the frame holds for `query-v`, or 0 when the
  entry is absent. The spike measured the rejected design by watching this
  number climb across renders and never fall on unmount, so it is the
  number the migration is answerable on."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

(defn- mounted-flag
  "The rAF tick loop's `:mounted?` liveness (rf2-e64drj), owned by the
  overlay's `:ref` callback. Private var, reached the same way
  `machine_after_rings_tick_loop_cljs_test` reaches it.

  TWO derefs, and the count is load-bearing: `@#'v` unwraps the Var to
  the ATOM, and the second reads the atom's map. With one deref every
  key answers nil, which reads as `:mounted? false` — so the two
  assertions W5 makes about the gate being CLOSED would both pass while
  measuring nothing."
  []
  (:mounted? @@#'day8.re-frame2-xray.panels.machine-after-rings/tick-state))

;; ===========================================================================
;; W1 — first display, the foreign child really crossed, and the read lands
;;      in the frame the tree named
;; ===========================================================================

(deftest w1-overlay-paints-through-the-seam-and-reads-the-named-frame
  (testing "rf2-k97c.3 — the migrated overlay commits real DOM through the
            bridge its caller holds, the machines-viz Reagent component
            behind the substrate seam paints its own root, and the
            boundary's `rf.fresco/sub` reads resolve against the frame the
            enclosing `frame-provider` named rather than the ambient one.
            Epic criteria 1 and 4, plus the crossing."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            ;; A live read in the OTHER frame, so the negative half of the
            ;; targeting claim below is measured with an instrument that is
            ;; demonstrably able to see an entry in that frame's cache.
            _probe (rf/subscribe [::n] {:frame app-frame})
            _ (seed-one-armed-timer!)
            {:keys [container root]} (mount-overlay! :rf/xray)]
        (try
          (is (some? (q container "[data-rf-xray-after-rings-host]"))
              "the boundary committed its `display: contents` wrapper — the
               body ran rather than short-circuiting to nil")

          ;; ---- the crossing: machines-viz painted its OWN root -------------
          (let [overlay (q container overlay-sel)]
            (is (some? overlay)
                "THE SEAM: the machines-viz overlay — a REAGENT component a
                 Fresco body can neither head nor call — committed its own
                 DOM root. This node exists only if `r/as-element`'s React
                 element crossed as a legal Fresco child and Reagent
                 rendered the component behind it")
            (is (= "1" (.getAttribute overlay "data-ring-count"))
                "and it received the ONE ring-spec the projection produced,
                 so the CLJS props map crossed by identity rather than
                 arriving camelCased or `clj->js`ed into nil")
            (is (= "2000" (.getAttribute overlay "data-tick"))
                "and the pinned clock reached it as :tick"))

          ;; ---- criterion 4: the read is where the tree said it would be ----
          (is (pos? (ref-count-of :rf/xray timers-q))
              (str "the boundary's read holds a reference in :rf/xray's "
                   "sub-cache — the frame the enclosing frame-provider "
                   "named. Cache keys: " (pr-str (keys (cache-of :rf/xray)))))
          (is (zero? (ref-count-of app-frame timers-q))
              "and NOT in the application frame's — a foreign root that
               inherited the ambient scope instead of reading React context
               would put it here")
          (is (pos? (ref-count-of app-frame [::n]))
              "NON-VACUITY: the application frame's cache is readable by this
               same instrument and does hold the probe's entry, so the zero
               above is an absence and not a broken reader")
          (finally
            ;; Release the imperative probe explicitly: `unsubscribe`'s own
            ;; docstring says a Reagent view auto-disposes via the reaction
            ;; lifecycle and an imperative subscriber must not rely on that.
            (rf/unsubscribe [::n] {:frame app-frame})
            (teardown! root container)))))))

;; ===========================================================================
;; W2 — it updates on a real dependency change, and the control is deaf
;; ===========================================================================

(deftest w2-overlay-updates-on-a-real-dependency-change
  (testing "rf2-k97c.3 — the mounted overlay re-renders and commits new DOM
            when its read's value really changes, and does NOT when the
            underlying slot moves without moving the answer. Epic criterion
            2, with the control that makes the update mean liveness rather
            than a commit that simply had not happened yet."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (seed-one-armed-timer!)
        (let [{:keys [container root]} (mount-overlay! :rf/xray)]
          (is (= "2000" (tick-attr container))
              "PRECONDITION: the committed DOM carries the pinned clock, so
               a later change to it is visible")

          ;; ---- phase 2: the world moves, and the overlay is deaf ----------
          ;; `:rf.xray/now-ms` reads `(or override live)`. Bumping the LIVE
          ;; slot while the override is pinned moves a slot the read really
          ;; touches — the sub recomputes — but leaves its ANSWER identical,
          ;; so nothing downstream may move. A boundary that repainted here
          ;; would make phase 3 pass for a reason that is not liveness.
          (rf/dispatch-sync [:rf.xray/timer-tick 8888] {:frame :rf/xray})
          ;; Read the db directly rather than through `rf/subscribe`: an
          ;; imperative subscription here would take a reference nothing
          ;; releases, in the same frame and for a query W4 counts.
          (let [db (rf.frame/frame-app-db-value :rf/xray)]
            (is (= 8888 (:rings/now-ms db))
                "PRECONDITION: the live slot really did move — otherwise the
                 control below is asserting against an event that did nothing")
            (is (= 2000 (:rings/now-ms-override db))
                "PRECONDITION: and the override that shadows it is still in
                 place, so the read RECOMPUTES and answers what it did before"))
          (-> (settle)
              (.then
                (fn [_]
                  (is (= "2000" (tick-attr container))
                      "CONTROL: given a full settling window the committed
                       DOM still carries 2000 — the live-slot write moved
                       data the read touches without moving what it answers")
                  ;; ---- phase 3: the answer really changes, DOM follows ----
                  (rf/dispatch-sync [:rf.xray/set-now-ms-override-for-test 5555]
                                    {:frame :rf/xray})
                  (rf.test-support/poll-until
                    #(= "5555" (tick-attr container))
                    {:label "the overlay committed the new clock as :tick"})))
              (.then
                (fn [_]
                  (is (= "5555" (tick-attr container))
                      "the overlay re-rendered on a real invalidation of its
                       own read and pushed the new value through the
                       substrate seam into the foreign component's DOM")))
              (.catch (fn [e]
                        (is false (str "W2 never settled: " (.-message e)
                                       " — tick: " (pr-str (tick-attr container))))
                        nil))
              (.then (fn [_]
                       (teardown! root container)
                       (done)))))))))

;; ===========================================================================
;; W3 — the tool's own render is not application view evidence
;; ===========================================================================

(deftest w3-the-boundarys-render-emits-no-view-trace
  (testing "rf2-k97c.3 / rf2-tqlmq — rendering the migrated overlay
            contributes NOTHING to the substrate's view-trace stream, even
            when it is mounted INSIDE an application frame. Epic criterion
            5, proven structurally rather than by the `:rf/xray` frame
            gate: a Fresco boundary is not a substrate view render, so
            there is no event to gate. The control is an ordinary
            `reg-view` in the same root, the same frame and the same
            commit."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_        (setup!)
            ;; Give the composite a DEFINED answer in the application
            ;; frame — `active-timers-empty-when-no-selection` in the node
            ;; suite pins that an empty machine override yields `[]` rather
            ;; than throwing. No timer is seeded: the trace snapshot only
            ;; ever lands in `:rf/xray`'s slot (see `seed-one-armed-timer!`),
            ;; so there would be nothing to paint here in any case, and this
            ;; row's precondition is the READ rather than the DOM.
            _        (rf/dispatch-sync
                       [:rf.xray/set-registered-machines-override-for-test []]
                       {:frame app-frame})
            traces   (atom [])
            view-op? #(and (keyword? (:operation %))
                           (= "rf.view" (namespace (:operation %))))]
        (rf/register-listener! :trace ::collect (fn [ev] (swap! traces conj ev)))
        (try
          ;; ---- the subject: the overlay, mounted in an APPLICATION frame --
          (let [{:keys [container root]} (mount-overlay! app-frame)
                subject-views (filterv view-op? @traces)]
            (try
              ;; THE PRECONDITION IS THE READ, NOT THE DOM, and that is
              ;; forced rather than chosen. The projection this overlay
              ;; paints from is fed by `trace-collector`, which snapshots
              ;; into `:rf/xray`'s slot ALONE — so inside an application
              ;; frame there are no timers, and the boundary correctly
              ;; renders nil. A DOM precondition is therefore unavailable
              ;; HERE, and reaching for one would have made the zero below
              ;; vacuous in the quietest possible way.
              ;;
              ;; A reference in this frame's sub-cache is the better
              ;; evidence anyway: it says the BODY RAN in this commit,
              ;; which is precisely the act that would have emitted a
              ;; `:rf.view/*` op had this been a substrate view render.
              ;; The DOM half of the claim is W1's, in the frame that has
              ;; something to paint.
              (is (pos? (ref-count-of app-frame timers-q))
                  (str "precondition: the boundary's body really did RUN in "
                       "this commit, inside an application frame — it took "
                       "a reference in that frame's sub-cache. Without this "
                       "the zero below would be the zero of a body that "
                       "never executed. Cache keys: "
                       (pr-str (keys (cache-of app-frame)))))
              (is (zero? (count subject-views))
                  (str "the boundary's render put NO :rf.view/* op in the "
                       "trace stream while rendering inside an application "
                       "frame. Ops seen: "
                       (pr-str (mapv :operation subject-views))))
              (finally (teardown! root container))))

          ;; ---- the control: a reg-view, same root shape, same frame -------
          (reset! traces [])
          (let [container (.createElement js/document "div")
                root      (rdc/create-root container)]
            (.appendChild (.-body js/document) container)
            (try
              (react-dom/flushSync
                (fn []
                  (rdc/render root [rf/frame-provider {:frame app-frame}
                                    [ProbeRegView]])))
              (let [control-views (filterv view-op? @traces)]
                (is (some? (q container "[data-testid=\"rf-xray-probe-reg-view\"]"))
                    "precondition: the control really did render")
                (is (pos? (count control-views))
                    (str "CONTROL FIRES: an ordinary reg-view rendered the "
                         "same way DOES emit a :rf.view/* op, so the "
                         "subject's zero is a property of the boundary and "
                         "not of a dead instrument. Ops seen: "
                         (pr-str (mapv :operation control-views)))))
              (finally (teardown! root container))))
          (finally
            (rf/unregister-listener! :trace ::collect)))))))

;; ===========================================================================
;; W4 — clean teardown: the read is released, and reopening is not growth
;; ===========================================================================

(defn- released?
  "The overlay's read is fully released from `:rf/xray`'s sub-cache."
  []
  (zero? (ref-count-of :rf/xray timers-q)))

(deftest w4-unmount-releases-the-read-and-reopen-does-not-grow-it
  (testing "rf2-k97c.3 — unmounting the overlay releases its subscription
            reference completely, and mounting it again returns to the SAME
            count rather than a higher one. Epic criterion 6.

            THE RELEASE IS ASYNCHRONOUS BY DESIGN, and this row polls rather
            than reading once. `impl.collector`'s `cell-reapers` gives a cell
            whose last reader unmounts ONE MACROTASK OF GRACE, so a keyed
            reorder that unmounts and remounts within a single turn reuses
            the reaction instead of rebuilding it. A synchronous assertion
            would report a LEAK against a collector behaving exactly as
            documented.

            THIS ROW DELIBERATELY SEEDS NO TIMER, and that is a choice
            rather than an oversight. The boundary reads all four of its
            slots UNCONDITIONALLY, at the top of the body, so the
            reference this row is about is taken whether or not the
            projection has anything to paint — the DOM is nil here and
            the ref-count is not. Seeding one would arm the rAF clock,
            and `tick-loop!` then takes its own reads off-render through
            the imperative `(rf/subscribe q {:frame f})` form. Whatever
            those hold, they are not the boundary's, and the subject of
            this row is precisely whether the BOUNDARY released. Leaving
            the clock disarmed keeps it the only holder."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        ;; The starting point is polled, not asserted: a neighbouring row's
        ;; teardown grace may still be in flight when this one begins.
        (-> (rf.test-support/poll-until released?
              {:label "no reference held before the first mount"})
            (.then
              (fn [_]
                (let [{:keys [container root]} (mount-overlay! :rf/xray)
                      mounted (ref-count-of :rf/xray timers-q)]
                  (is (pos? mounted)
                      "the mount took a reference — otherwise the release
                       below is vacuous")
                  (teardown! root container)
                  (-> (rf.test-support/poll-until released?
                        {:label "the first unmount released the read"})
                      (.then
                        (fn [_]
                          (is (released?)
                              (str "the unmount released it COMPLETELY, "
                                   "within the collector's grace macrotask. "
                                   "Cache: "
                                   (pr-str (keys (cache-of :rf/xray)))))
                          ;; ---- reopen: the same count, not a higher one ---
                          (let [{c2 :container r2 :root} (mount-overlay! :rf/xray)
                                remounted (ref-count-of :rf/xray timers-q)]
                            (is (= mounted remounted)
                                (str "reopening returns to the SAME reference "
                                     "count (" mounted ") rather than "
                                     "accumulating — accumulation across "
                                     "open/close cycles is the signature of a "
                                     "release the substrate's own reaction "
                                     "lifecycle cannot see. Got: " remounted))
                            (teardown! r2 c2)
                            (rf.test-support/poll-until released?
                              {:label "the second unmount released it too"}))))))))
            (.then (fn [_] (is (released?)
                               "and the second unmount releases it too")))
            (.catch (fn [e] (is false (str "poll timed out: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

;; ===========================================================================
;; W5 — the rf2-e64drj MOUNT GATE survives the migration
;; ===========================================================================
;;
;; This row is NOT in the template, and it is the one this panel most needs.
;; The overlay owns the single per-chart rAF tick loop, and `tick-loop!` is
;; gated on a `:mounted?` liveness flag that the overlay's `:ref` callback
;; clears. Pre-rf2-e64drj the loop was gated on app-db data ALONE, so
;; switching away from the Machine Inspector while an `:after` timer stayed
;; armed left it dispatching ~60x/s in the background for ever.
;;
;; `:ref` is one of the two structural slots Fresco carries untouched rather
;; than emitting as an attribute, so the contract SHOULD survive — but
;; "should" is what this file exists to replace. Nothing else in the suite
;; can see it: `machine_after_rings_tick_loop_cljs_test` drives `overlay-ref!`
;; by hand, which proves the flag's algebra and not that React still calls it.

(deftest w5-unmount-clears-the-tick-loop-mount-gate
  (testing "rf2-e64drj / rf2-k97c.3 — mounting the boundary arms the rAF
            clock's `:mounted?` liveness and unmounting CLEARS it, because
            Fresco carried the `:ref` callback through to React untouched.
            Without this the off-render 60Hz loop outlives its panel."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            _ (seed-one-armed-timer!)]
        (after-rings/stop-tick!)
        (is (false? (boolean (mounted-flag)))
            "PRECONDITION: nothing is holding the gate open before the mount")
        (let [{:keys [container root]} (mount-overlay! :rf/xray)]
          (is (some? (q container overlay-sel))
              "precondition: the overlay really did mount, so the flag below
               is about this mount")
          (is (true? (boolean (mounted-flag)))
              "the mounted overlay armed the tick loop's liveness")
          (teardown! root container)
          (is (false? (boolean (mounted-flag)))
              "and the unmount CLEARED it — React invoked the `:ref` callback
               with nil, which it only does if Fresco carried the ref slot
               through to the committed element. A boundary that dropped
               `:ref` would leave this true and strand the rAF loop"))))))
