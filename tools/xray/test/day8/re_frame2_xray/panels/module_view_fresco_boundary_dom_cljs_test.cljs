(ns day8.re-frame2-xray.panels.module-view-fresco-boundary-dom-cljs-test
  "THE FIRST XRAY PANEL RE-AUTHORED IN THE RE-FRAME-NATIVE VIEW LAYER, read
  off a real React commit (rf2-k97c.3).

  `module-view/Panel` is now an `h/defview` reading through Fresco's
  shipped collector rather than an `rf/reg-view` reading through whatever
  view build the installed substrate adapter supplies. This file is the
  behavioural evidence for that swap, and it is written to be the
  TEMPLATE the remaining panels are migrated against: four rows, one per
  claim, each with the control that makes its answer non-vacuous.

  ## What the epic asked for, and which row answers it

  rf2-k97c's success criteria are behavioural. Four of the six are
  answerable at a panel's own boundary and are answered here:

    1 FIRST DISPLAY               — W1
    2 UPDATES ON A REAL CHANGE    — W2 (with the deaf control that makes
                                    the update mean liveness)
    4 FRAME TARGETING             — W1's second half, read off the frame's
                                    OWN sub-cache rather than off the DOM
    5 TOOL ACTIVITY NEVER
      MASQUERADING AS APPLICATION
      EVIDENCE                    — W3, in BOTH directions
    6 CLEAN TEARDOWN              — W4

  Criterion 3 (Xray's own interactions) has no row because this panel is
  read-only: it dispatches nothing, so a click row here would assert
  about a control the panel does not have. The panels that DO carry
  interactions are migrated after this one and bring their own rows.

  ## The mount is the SHELL's mount, taken from the registry

  `shell/detail-panel` mounts the active tab as the hiccup head
  `[(:panel tab)]` inside the shell's `[rf/frame-provider {:frame …}]`.
  [[mount-panel!]] does exactly that, and it reaches `:panel` THROUGH
  `panel-registry/tab-by-id` rather than naming the var — so the bridge
  the registry actually holds is the thing under test. A bridge that
  regressed to something the shell cannot mount reddens here rather than
  in a browser.

  Nothing below ever calls the panel a second time. Every assertion after
  the mount reads `container.querySelector…` — the DOM React committed on
  its own.

  ## Substrate: the Reagent adapter, deliberately

  A ratom-family adapter, which is the family Xray already supports —
  because the claim being made is that the boundary is INDIFFERENT to it.
  The panel is a real React function component either way; what W3 shows
  is that it contributes nothing to the substrate's view-trace stream
  even when it is rendered inside an application frame, and W1 that its
  read lands in the frame React context named rather than in the ambient
  one. Neither fact is available to a `reg-view`, whose render IS a
  substrate render and whose read is tracked by the substrate's own
  reaction machinery.

  `:ambient-frame nil` is load-bearing, exactly as it is in
  `fresco_live_panel_dom_cljs_test`: the fixture's default ambient
  `:rf/default` scope is still in effect during a synchronous
  `flushSync`, and tier 1 of `re-frame.views.provider/current-frame` is
  the dynamic var — so an ambient frame would SHADOW the React-context
  tier this file is about and W1's frame-targeting row would pass while
  measuring nothing.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium) per
  `implementation/shadow-cljs.edn`, whose `:source-paths` already carry
  `tools/xray/test`. The `:node-test` build's `cljs-test$` regex also
  matches, so it LOADS under Node — where every row short-circuits
  through [[browser?]] and reports the skip rather than passing silently."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.image :as rf.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.image-view-reads :as image-reads]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private app-frame
  "An ORDINARY application frame — the thing Xray inspects. Two rows need
  one that is not `:rf/xray`: W1 reads it as the negative half of the
  frame-targeting claim, and W3 mounts INSIDE it so the evidence claim
  cannot be carried by `:rf/xray`'s trace-disabled gate."
  ::app)

(def ^:private imaged-frame
  "The image-loaded frame W2 creates. NAMESPACED, so it cannot collide with
  a frame any other suite in this one browser page has registered — which
  matters because `rf.frame/frames` is process-global and the runtime
  fixture does not clear it."
  ::imaged)

(def ^:private image-view-q
  "The panel's one read. Named once because three rows key off it — the
  sub-cache is keyed by the query vector itself (`re-frame.subs/cache-key`
  is identity), so this value IS the cache key."
  [:rf.xray/image-view])

;; ---- the application image the deaf control needs -------------------------
;;
;; W2's control creates an image-loaded frame, which is the one thing that
;; moves `image-view-data`'s answer WITHOUT moving any app-db. The pool is
;; explicit so the row does not depend on whatever the live source store
;; happens to carry in this bundle.

(def ^:private target-pool
  [{:kind :event :id :counter/inc   :rf.provenance/ns "app.counter" :impl :inc}
   {:kind :sub   :id :counter/value :rf.provenance/ns "app.counter" :impl :val}])

(def ^:private target-image
  (rf.image/image {:id :app/counter :select-ns {:include ["app.counter"]}}))

;; ---- a probe event, and the `reg-view` W3 measures the panel against ------

(rf/reg-event ::bump
  (fn [{:keys [db]} [_ n]] {:db (assoc db ::n n)}))

(rf/reg-sub ::n (fn [db _] (::n db)))

(rf/reg-view ProbeRegView
  "The POSITIVE CONTROL for W3, and nothing else. An ordinary `reg-view`
  rendered by the installed adapter in the same root, in the same frame,
  in the same commit as the panel — so the only variable between it and
  the panel is which view layer authored the body."
  []
  [:div {:data-testid "rf-xray-probe-reg-view"} (str @(rf/subscribe [::n]))])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     ;; `:async? true` because W4 is an `async` row, and `cljs.test` refuses a
     ;; FUNCTION fixture in any namespace that carries one — "Async tests
     ;; require fixtures to be specified as maps. Testing aborted." The flag is
     ;; what makes `make-reset-runtime-fixture` hand back the `{:before :after}`
     ;; map form.
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about; a
                      ;; neighbour's boundary left in the entry cache would
                      ;; make W4's release row read a residue that is not
                      ;; this panel's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; NO `flush-render!` HELPER HERE, and its absence is a finding rather than an
;; omission. Every neighbouring panel row reaches for the adapter's
;; `:flush-render!` slot to commit a phase synchronously, and for a `reg-view`
;; panel that is exactly right. A Fresco boundary is NOT in Reagent's render
;; queue — its update is scheduled by the collector through React — so draining
;; Reagent's queue commits nothing of this panel's, and a row written that way
;; reads a DOM that has not moved and reports a live panel as dead. Measured
;; here before this comment existed. Mount is committed with `flushSync`
;; (React's own door) and everything after it is polled.

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
  "Register Xray's handlers (which is what registers `:rf.xray/image-view`
  and the L4 tab entry the mount reads) and make the two frames."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- mount-panel!
  "Mount the Frames tab the way `shell/detail-panel` mounts it: the
  registry's `:panel` value as a hiccup head, inside a `frame-provider`
  scoping `frame`. Committed synchronously — React 19's `root.render` is
  otherwise async and phase 1 would assert against an empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)
        tab       (panel-registry/tab-by-id :dynamic :module-view)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [(:panel tab)]])))
    {:container container :root root :tab tab}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — which is where
  the collector releases a boundary's reads — have RUN by the time the
  next line reads the sub-cache. A bare `.unmount` schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- q [container sel] (.querySelector container sel))

(defn- cache-of
  "The frame's live sub-cache map. Not `some->`-guarded: a nil here means
  the frame is not live, which is a defect in the row's own setup and
  should throw rather than read as an empty cache."
  [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of
  "The sub-cache ref-count the frame holds for `query-v`, or 0 when the
  entry is absent. The spike measured Arm A's binding by watching this
  number climb across renders and never fall on unmount (22 → 25 → 32),
  so it is the number the migration is answerable on."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

;; ===========================================================================
;; W1 — first display, and the read lands in the frame the tree named
;; ===========================================================================

(deftest w1-panel-paints-and-its-read-lands-in-the-named-frame
  (testing "rf2-k97c.3 — the migrated Frames panel commits real DOM through the
            registry entry the shell mounts, and its `h/sub` read resolves
            against the frame the enclosing `frame-provider` named rather than
            the ambient one. Epic criteria 1 and 4."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            ;; A live read in the OTHER frame, so the negative half of the
            ;; targeting claim below is measured with an instrument that is
            ;; demonstrably able to see an entry in that frame's cache.
            _probe (rf/subscribe [::n] {:frame app-frame})
            {:keys [container root]} (mount-panel! :rf/xray)]
        (try
          (is (some? (q container "[data-testid=\"rf-xray-module-view\"]"))
              "the panel committed a real DOM root under React — a Fresco
               boundary mounted through Reagent's `:>` from the registry entry")
          (is (some? (q container "[data-testid=\"rf-xray-module-view-frames\"]"))
              "and the Frames section rendered, so the body ran rather than
               short-circuiting to nil")

          ;; ---- criterion 4: the read is where the tree said it would be ----
          (is (pos? (ref-count-of :rf/xray image-view-q))
              (str "the panel's read holds a reference in :rf/xray's sub-cache "
                   "— the frame the enclosing frame-provider named. Cache keys: "
                   (pr-str (keys (cache-of :rf/xray)))))
          (is (zero? (ref-count-of app-frame image-view-q))
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

(deftest w2-panel-updates-on-a-real-dependency-change
  (testing "rf2-k97c.3 — the mounted panel re-renders itself and commits new DOM
            when its read's value really changes, and does NOT when nothing it
            watches moved. Epic criterion 2, with the control that makes the
            update mean liveness rather than a commit that simply had not
            happened yet."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        ;; The claim is RELATIVE — this frame arriving — rather than absolute
        ;; ("the panel is empty"). `rf.frame/frames` is a process-global
        ;; registry the runtime fixture does not clear, and the `:browser-test`
        ;; build runs every `-dom-cljs-test` namespace in ONE page, so whatever
        ;; image-loaded frames a neighbour left are on screen at mount.
        ;; Measured: the first draft asserted the no-image caption and read a
        ;; populated list. A relative claim is also the better one — it is
        ;; about a specific value reaching the DOM.
        ;;
        ;; The row is ASYNCHRONOUS, and that is the second thing measured here.
        ;; A Fresco boundary is not in Reagent's render queue, so the adapter's
        ;; `:flush-render!` — which is exactly the right instrument for the
        ;; `reg-view` panels beside this one — does NOT commit this panel's
        ;; update. The first draft used it and read a DOM that had not moved,
        ;; which would have been reported as the panel being DEAD. The correct
        ;; instrument for "it updates" is a bounded poll of the committed DOM,
        ;; and the control below is given a real settling window so its
        ;; absence is a decision and not a race.
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              section  (q container "[data-testid=\"rf-xray-module-view\"]")
              imaged-q (str "[data-testid=\"rf-xray-module-view-frame-"
                            imaged-frame "\"]")
              row?     (fn [] (some? (q container imaged-q)))]
          (is (not (row?))
              "NON-VACUITY: the frame this row drives in is NOT on screen
               before it exists")

          ;; ---- phase 2: the world moves, and the panel is deaf ------------
          ;; `:rf.xray/image-view` is a db-derived sub over PROCESS-GLOBAL
          ;; state: creating an image-loaded frame changes what it WOULD
          ;; compute while invalidating nothing it watches.
          (rf.live-frame/make-frame {:id imaged-frame :images [target-image]}
                                    target-pool)
          (is (some? (first (filter #(= imaged-frame (:frame-id %))
                                    (:frames (image-reads/image-view-data)))))
              "PRECONDITION: the read's UNDERLYING data now carries the new
               frame — so a missing row below is the panel failing to
               re-render, and not the frame failing to exist")
          (-> (settle)
              (.then
                (fn [_]
                  (is (not (row?))
                      "CONTROL: given a full settling window, the committed DOM
                       still does NOT carry the row. A panel that re-rendered
                       here would make phase 3 pass for a reason that is not
                       liveness")
                  ;; ---- phase 3: app-db moves, the read re-runs, DOM follows
                  (rf/dispatch-sync [::bump 1] {:frame :rf/xray})
                  (rf.test-support/poll-until row?
                    {:label "the panel committed the new frame's row"})))
              (.then
                (fn [_]
                  (is (row?)
                      (str "the panel re-rendered on a real invalidation of its "
                           "own read and committed the new frame's row"))
                  (is (identical? section
                                  (q container "[data-testid=\"rf-xray-module-view\"]"))
                      "and it is the SAME <section> node: React reconciled the
                       live tree in place, so the row did not arrive by the
                       panel being remounted from scratch, which would not be
                       liveness")))
              (.catch (fn [e]
                        (is false (str "W2 never settled: " (.-message e)
                                       " — DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (teardown! root container)
                       (done)))))))))

;; ===========================================================================
;; W3 — the tool's own render is not application view evidence
;; ===========================================================================

(deftest w3-the-boundarys-render-emits-no-view-trace
  (testing "rf2-k97c.3 / rf2-tqlmq — rendering the migrated panel contributes
            NOTHING to the substrate's view-trace stream, even when it is
            mounted INSIDE an application frame. Epic criterion 5, proven
            structurally rather than by the `:rf/xray` frame gate: a Fresco
            boundary is not a substrate view render, so there is no event to
            gate. The control is an ordinary `reg-view` in the same root, the
            same frame and the same commit."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_       (setup!)
            traces  (atom [])
            view-op? #(and (keyword? (:operation %))
                           (= "rf.view" (namespace (:operation %))))]
        (rf/register-listener! :trace ::collect (fn [ev] (swap! traces conj ev)))
        (try
          ;; ---- the subject: the panel, mounted in an APPLICATION frame ----
          (let [{:keys [container root]} (mount-panel! app-frame)
                subject-views (filterv view-op? @traces)]
            (try
              (is (some? (q container "[data-testid=\"rf-xray-module-view\"]"))
                  "precondition: the panel really did render in this commit —
                   an empty container would make the zero below vacuous")
              (is (zero? (count subject-views))
                  (str "the panel's render put NO :rf.view/* op in the trace "
                       "stream while rendering inside an application frame. "
                       "Ops seen: " (pr-str (mapv :operation subject-views))))
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
                    (str "CONTROL FIRES: an ordinary reg-view rendered the same "
                         "way DOES emit a :rf.view/* op, so the subject's zero "
                         "is a property of the boundary and not of a dead "
                         "instrument. Ops seen: "
                         (pr-str (mapv :operation control-views)))))
              (finally (teardown! root container))))
          (finally
            (rf/unregister-listener! :trace ::collect)))))))

;; ===========================================================================
;; W4 — clean teardown: the read is released, and reopening is not growth
;; ===========================================================================

(defn- released?
  "The panel's read is fully released from `:rf/xray`'s sub-cache."
  []
  (zero? (ref-count-of :rf/xray image-view-q)))

(deftest w4-unmount-releases-the-read-and-reopen-does-not-grow-it
  (testing "rf2-k97c.3 — unmounting the panel releases its subscription
            reference completely, and mounting it again returns to the SAME
            count rather than a higher one. Epic criterion 6, and the number
            the spike caught the rejected design on: with a four-call interop
            binding the `:rf/xray` ref-count climbed 22 → 25 → 32 across
            renders and never fell on unmount.

            THE RELEASE IS ASYNCHRONOUS BY DESIGN, and this row polls rather
            than reading once. `impl.collector`'s `cell-reapers` gives a cell
            whose last reader unmounts ONE MACROTASK OF GRACE, so that a keyed
            reorder which unmounts and remounts a row within a single turn
            reuses the reaction instead of rebuilding it. Measured here: a
            synchronous read immediately after `flushSync(root.unmount)`
            returns 1, and the same read after one macrotask returns 0. A
            synchronous assertion would therefore have reported a LEAK against
            a collector that was behaving exactly as documented — which is why
            this note is longer than the row."
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
                (let [{:keys [container root]} (mount-panel! :rf/xray)
                      mounted (ref-count-of :rf/xray image-view-q)]
                  (is (pos? mounted)
                      "the mount took a reference — otherwise the release
                       below is vacuous")
                  (teardown! root container)
                  (-> (rf.test-support/poll-until released?
                        {:label "the first unmount released the read"})
                      (.then
                        (fn [_]
                          (is (released?)
                              (str "the unmount released it COMPLETELY, within "
                                   "the collector's grace macrotask. Cache: "
                                   (pr-str (keys (cache-of :rf/xray)))))
                          ;; ---- reopen: the same count, not a higher one ----
                          (let [{c2 :container r2 :root} (mount-panel! :rf/xray)
                                remounted (ref-count-of :rf/xray image-view-q)]
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
