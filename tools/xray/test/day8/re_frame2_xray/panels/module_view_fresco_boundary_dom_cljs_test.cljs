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
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
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
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private app-frame
  "An ORDINARY application frame — the thing Xray inspects. Two rows need
  one that is not `:rf/xray`: W1 reads it as the negative half of the
  frame-targeting claim, and W3 mounts INSIDE it so the evidence claim
  cannot be carried by `:rf/xray`'s trace-disabled gate."
  ::app)

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

(defn- flush-render!
  "Run `thunk`, then SYNCHRONOUSLY commit whatever re-render it scheduled —
  the adapter's own `:flush-render!` contract slot (Spec 006), not a
  test-only mechanism. Without it the committed DOM lags each phase by an
  animation frame and every assertion below would be about timing."
  [thunk]
  ((:flush-render! rf.adapter.reagent/adapter) thunk))

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
      (let [_ (setup!)
            {:keys [container root]} (mount-panel! :rf/xray)]
        (try
          ;; ---- phase 1: mounted, and rendering its empty arm --------------
          (let [section (q container "[data-testid=\"rf-xray-module-view\"]")]
            (is (some? (q container "[data-testid=\"rf-xray-module-view-frames-empty\"]"))
                "the panel rendered the honest no-image caption — no
                 image-loaded frame exists yet, so the list this row drives in
                 cannot already be on screen")
            (is (nil? (q container "[data-testid=\"rf-xray-module-view-frames-list\"]"))
                "NON-VACUITY: no frames list is in the DOM before one arrives")

            ;; ---- phase 2: the world moves, and the panel is deaf ----------
            ;; `:rf.xray/image-view` is a db-derived sub over PROCESS-GLOBAL
            ;; state: creating an image-loaded frame changes what it WOULD
            ;; compute while invalidating nothing it watches. The render queue
            ;; is drained here too, so what phase 3 proves is a reaction that
            ;; re-ran — not a commit that had merely been pending.
            (flush-render!
              (fn []
                (rf.live-frame/make-frame {:id :app/main :images [target-image]}
                                          target-pool)))
            (is (some? (q container "[data-testid=\"rf-xray-module-view-frames-empty\"]"))
                "CONTROL: the committed DOM still shows the empty caption. A
                 panel that re-rendered here would make phase 3 pass for a
                 reason that is not liveness")

            ;; ---- phase 3: app-db moves, the reaction re-runs, DOM follows --
            (flush-render! (fn [] (rf/dispatch-sync [::bump 1] {:frame :rf/xray})))
            (is (some? (q container "[data-testid=\"rf-xray-module-view-frames-list\"]"))
                (str "the panel re-rendered on a real invalidation of its own "
                     "read and committed the frames list. DOM: "
                     (.-textContent container)))
            (is (some? (q container "[data-testid=\"rf-xray-module-view-frame-:app/main\"]"))
                "and the row names the frame that actually arrived, so the
                 assertion above cannot pass on a list projected from nothing")
            (is (nil? (q container "[data-testid=\"rf-xray-module-view-frames-empty\"]"))
                "the empty caption is gone — the list REPLACED it rather than
                 rendering beside it")
            (is (identical? section (q container "[data-testid=\"rf-xray-module-view\"]"))
                "and it is the SAME <section> node: React reconciled the live
                 tree in place, so the list did not arrive by the panel being
                 remounted from scratch, which would not be liveness"))
          (finally
            (teardown! root container)))))))

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

(deftest w4-unmount-releases-the-read-and-reopen-does-not-grow-it
  (testing "rf2-k97c.3 — unmounting the panel releases its subscription
            reference completely, and mounting it again returns to the SAME
            count rather than a higher one. Epic criterion 6, and the number
            the spike caught Arm A on: with a four-call interop binding the
            `:rf/xray` ref-count climbed 22 → 25 → 32 across renders and never
            fell on unmount."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)]
        (is (zero? (ref-count-of :rf/xray image-view-q))
            "precondition: nothing holds the panel's read before the first mount")
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              mounted (ref-count-of :rf/xray image-view-q)]
          (is (pos? mounted)
              "the mount took a reference — otherwise the release below is
               vacuous")
          (teardown! root container)
          (is (zero? (ref-count-of :rf/xray image-view-q))
              (str "the unmount released it COMPLETELY. Cache after unmount: "
                   (pr-str (keys (cache-of :rf/xray)))))

          ;; ---- reopen: the same count, not a higher one -------------------
          (let [{c2 :container r2 :root} (mount-panel! :rf/xray)
                remounted (ref-count-of :rf/xray image-view-q)]
            (is (= mounted remounted)
                (str "reopening returns to the same reference count (" mounted
                     ") rather than accumulating — the growth-per-cycle
                     signature of a binding whose release the substrate's
                     reaction lifecycle cannot see. Got: " remounted))
            (teardown! r2 c2)
            (is (zero? (ref-count-of :rf/xray image-view-q))
                "and the second unmount releases it too")))))))
