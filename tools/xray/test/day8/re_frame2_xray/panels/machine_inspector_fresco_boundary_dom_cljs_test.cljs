(ns day8.re-frame2-xray.panels.machine-inspector-fresco-boundary-dom-cljs-test
  "The Machine Inspector panel re-authored in the re-frame-native view layer,
  read off a real React commit (rf2-k97c.3).

  `panels.machine-inspector/Panel` is now an `rf.fresco/defview` reading
  through Fresco's shipped collector rather than an `rf/reg-view` reading
  through whatever view build the installed substrate adapter supplies. This
  file is the behavioural evidence for that swap. Nothing in the fast node
  lane can make it: `machine_inspector_view_cljs_test` drives `panel-tree` as
  a pure function, and a boundary's body only runs inside a React render
  window.

  ## What the epic asked for, and which row answers it

    1 FIRST DISPLAY               — W1
    2 UPDATES ON A REAL CHANGE    — W2 (with the deaf control that makes the
                                    update mean liveness)
    4 FRAME TARGETING             — W1's second half, read off the frame's
                                    OWN sub-cache rather than off the DOM
    6 CLEAN TEARDOWN              — W3

  W4 answers no epic criterion. It is the CONTROL under W1's island row: it
  mounts the panel's own tree with the island child removed and shows the
  island assertion failing while the wrapper assertions still pass.

  Criteria 3 and 5 have no row here for the reasons the Epoch panel's sibling
  suite sets out: criterion 5 is structural and identical for every boundary
  in this migration, and `static/flows/panel_fresco_boundary_dom_cljs_test`
  carries it with a positive `reg-view` control; criterion 3's affordances
  here (Prev/Next, the chart's state-click) dispatch through a frame captured
  at render time by `rf/current-frame-id`, which this migration did not touch.

  ## FIVE READS, AND THE ISLAND

  The boundary reads `:rf.xray/machine-inspector-data`,
  `:rf.xray/machine-transitions-for-focused-event`,
  `:rf.xray/machine-focused-epoch-cascade`, `:rf.xray/machine-tab-fit-signal`
  and `:rf.xray/target-frame`. The last two used to be performed by helpers
  deep in the tree; rf2-k97c.3 hoisted them into the body so the helpers stay
  pure functions the node lane can drive. W1 and W3 assert on all five.

  ONE ISLAND SURVIVES and W1 has a row for it. `machine-canvas/Chart` is
  still an `rf/reg-view` — it has a second consumer on the Static surface, so
  migrating it belongs to that slice — and a `reg-view` head grades
  `:invalid` under Fresco's codec down the IDENTICAL arm a plain `defn` does.
  The boundary therefore hands `r/as-element` down as the `as-child`
  spelling. That is not a detail a node-lane row can witness: under
  `identity` the island is a no-op and the tree looks the same either way.
  Here, a missing or wrong island does not paint at all.

  SELECT THE ISLAND BY A NODE THE ISLAND EMITS (rf2-q6n3). That is
  `rf-xray-machine-canvas-host`, `Chart`'s own root. It is NOT
  `rf-xray-machine-focused-event-chart`, which `machine_inspector` emits two
  levels ABOVE its `as-child` call and which therefore survives the whole
  Chart subtree being absent — W1 claimed the island on that marker until
  rf2-q6n3, and W4 is the standing demonstration of why it could not.

  ## The mount is the SHELL's mount, taken from the registry

  `shell.cljs`'s `detail-panel` mounts the active tab as the hiccup head
  `[(:panel tab)]` inside the shell's `[rf/frame-provider {:frame …}]`.
  [[mount-panel!]] does exactly that, reaching `:panel` THROUGH
  `panel-registry/tab-by-id :dynamic :machines` rather than naming the var —
  so the BRIDGE the registry actually holds is the thing under test. `Panel`
  is a React component now and `reg-l4-tab!`'s `:pre` requires `:panel` to be
  CALLABLE, so a registration left pointing at `Panel` rather than
  `Panel-bridge` reddens here rather than in a browser.

  Nothing below ever calls the panel a second time. Every assertion after the
  mount reads `container.querySelector…` — the DOM React committed on its own.

  ## Substrate: the Reagent adapter, deliberately

  A ratom-family adapter, which is the family Xray already supports, because
  the claim being made is that the boundary is INDIFFERENT to it.
  `:ambient-frame nil` is load-bearing: tier 1 of the frame resolver is the
  dynamic var, so an ambient frame would SHADOW the React-context tier W1's
  frame-targeting row is about, and that row would pass while measuring
  nothing.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test` build
  (real DOM + React via Chromium). The `:node-test` build's regex also
  matches, so it LOADS under Node — where every row short-circuits through
  [[browser?]] and reports the skip rather than passing silently."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.machines]
            [re-frame.test-support :as rf.test-support]
            ;; W4 only. `r/as-element` is the island spelling [[Panel]] hands
            ;; down, and W4's control is that same call with the ONE argument
            ;; swapped — so the test needs the production spelling verbatim.
            [reagent.core :as r]
            ;; W4 only, and it is the one place this file names a panel var
            ;; rather than reaching through the registry: `panel-tree` IS the
            ;; seam the island rides on, so the control has to call it.
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private app-frame
  "An ORDINARY application frame — the thing Xray inspects. W1 reads it as
  the negative half of the frame-targeting claim."
  ::app)

(def ^:private data-q     [:rf.xray/machine-inspector-data])
(def ^:private records-q  [:rf.xray/machine-transitions-for-focused-event])
(def ^:private cascade-q  [:rf.xray/machine-focused-epoch-cascade])
(def ^:private fit-q      [:rf.xray/machine-tab-fit-signal])
(def ^:private frame-q    [:rf.xray/target-frame])

(def ^:private boundary-reads
  "Every query the boundary issues, in the order the body issues them. The
  sub-cache is keyed by the query vector itself, so these values ARE the
  cache keys."
  [data-q records-q cascade-q fit-q frame-q])

;; W2's DEAF lever. It writes a key on Xray's own app-db that NO sub in the
;; panel's read set consults, so the world moves and nothing the panel
;; watches is invalidated. Without it, phase 3's repaint would be evidence
;; that a commit happened — not that this boundary is live.
(rf/reg-event ::write-unwatched-slot
  (fn [{:keys [db]} [_ n]]
    {:db (assoc db ::probe n)}))

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle    {:on {:start :authing}}
             :authing {:on {:ok :done :err :failed}}
             :done    {:final? true}
             :failed  {:final? true}}})

(def ^:private fixture-history
  "One epoch whose cascade transitions a machine, so the focused-event
  section has something to render. Kept minimal on purpose: these rows are
  about the boundary, and `machine_inspector_view_cljs_test` owns what the
  section renders."
  [{:epoch-id 1
    :dispatch-id "d-1"
    :trace-events
    [{:id 1 :time 10 :operation :rf.machine/transition
      :tags {:machine-id :auth/login
             :before {:state :idle :data {}}
             :after  {:state :authing :data {}}
             :event [:auth/submit] :rf.trace/dispatch-id "d-1"}}]}])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about; a
                      ;; neighbour's boundary left in the entry cache would
                      ;; make W3's release row read a residue that is not
                      ;; this panel's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; NO `flush-render!` HELPER HERE, and its absence is a finding rather than
;; an omission. A Fresco boundary is NOT in Reagent's render queue — its
;; update is scheduled by the collector through React — so draining Reagent's
;; queue commits nothing of this panel's, and a row written that way reads a
;; DOM that has not moved and reports a live panel as dead.

(defn- settle
  "A promise resolving once every render pipeline on the page has had a real
  chance to commit — two animation frames and a macrotask. It exists for the
  CONTROL in W2: an absence asserted immediately after the world moves is a
  race, and an absence asserted after this is a decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- setup!
  "Register Xray's handlers — which installs the machine sub family and the
  `:machines` L4 tab entry the mount reads — plus the test-override seam the
  fixtures write through, and make the two frames."
  []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- override-machines! [machines]
  (rf/dispatch-sync [:rf.xray/set-registered-machines-override-for-test machines]
                    {:frame :rf/xray}))

(defn- override-definitions! [definitions]
  (rf/dispatch-sync [:rf.xray/set-machine-definitions-override-for-test definitions]
                    {:frame :rf/xray}))

(defn- set-history! [history]
  (rf/dispatch-sync [:rf.xray/set-epoch-history-for-test history]
                    {:frame :rf/xray}))

(defn- focus-epoch! [epoch-id]
  (rf/dispatch-sync [:rf.xray/set-focus-epoch-id-for-test epoch-id]
                    {:frame :rf/xray}))

(defn- seed-machines!
  "The registered machines + definitions every row needs, with an EMPTY
  spine. The HISTORY is W2's lever and is applied separately — and it is the
  lever rather than the focus because the focus-resolver HEAD-TRACKS, so
  loading history with no explicit focus already paints the focused-event
  surface and a focus-as-lever row would fail its own precondition."
  []
  (override-machines!    [:auth/login])
  (override-definitions! {:auth/login fixture-definition}))

(defn- mount-panel!
  "Mount the Machine tab the way `shell.cljs`'s `detail-panel` mounts it: the
  registry's `:panel` value as a hiccup head, inside a `frame-provider`
  scoping `frame`. Committed synchronously — React 19's `root.render` is
  otherwise async and the first assertion would run against an empty
  container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)
        tab       (panel-registry/tab-by-id :dynamic :machines)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [(:panel tab)]])))
    {:container container :root root :tab tab}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — which is where the
  collector releases a boundary's reads — have RUN by the time the next line
  reads the sub-cache. A bare `.unmount` schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- q [container sel] (.querySelector container sel))

(defn- panel-node [container]
  (q container "[data-testid=\"rf-xray-machine-inspector\"]"))

(defn- focused-section? [container]
  (some? (q container "[data-testid=\"rf-xray-machine-focused-event\"]")))

(defn- chart-wrapper-node
  "The chart WRAPPER — a `:div` `machine_inspector` itself emits above the
  island (`panels/machine_inspector.cljs`, ELEMENT 3). Its presence says the
  focused-event section reached element 3 and took the has-definition arm,
  and it says NOTHING WHATEVER about the island: `machine_inspector` emits
  this node and a second styling wrapper BEFORE the `as-child` call, so it is
  committed even when `as-child` answers nil and the entire Chart subtree is
  absent. [[w4-the-island-selector-discriminates-and-the-wrapper-does-not]]
  demonstrates exactly that, which is why this is not a claim about the
  island and must never be written as one again (rf2-q6n3)."
  [container]
  (q container "[data-testid=\"rf-xray-machine-focused-event-chart\"]"))

(defn- island-node
  "The ISLAND — `machine-canvas/Chart`'s OWN root div. `Chart` emits
  `:data-testid` from its `testid` prop, whose `:or` default is this literal
  (`panels/machine_canvas.cljs`), and `machine_inspector`'s `as-child` call
  site passes no `:testid`, so this marker IS Chart's root here. Nothing but
  `Chart` running can put this node in the DOM."
  [container]
  (q container "[data-testid=\"rf-xray-machine-canvas-host\"]"))

(defn- cache-of
  "The frame's live sub-cache map. Not `some->`-guarded: a nil here means the
  frame is not live, which is a defect in the row's own setup and should
  throw rather than read as an empty cache."
  [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of
  "The sub-cache ref-count the frame holds for `query-v`, or 0 when the entry
  is absent."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

(defn- released?
  "True once the frame holds NO reference for any of the boundary's reads."
  []
  (every? #(zero? (ref-count-of :rf/xray %)) boundary-reads))

;; ===========================================================================
;; W1 — first display, the island really mounts, and the reads land in the
;;      frame the tree named
;; ===========================================================================

(deftest w1-panel-paints-through-its-island-and-reads-the-named-frame
  (testing "rf2-k97c.3 — the migrated Machine Inspector commits real DOM
            through the registry entry the Dynamic shell mounts, its
            surviving Reagent island mounts under the boundary, and each of
            its five `rf.fresco/sub` reads resolves against the frame the
            enclosing `frame-provider` named rather than the ambient one.
            Epic criteria 1 and 4."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            ;; A live read in the OTHER frame, so the negative half of the
            ;; targeting claim below is measured with an instrument that is
            ;; demonstrably able to see an entry in that frame's cache.
            _probe (rf/subscribe [:rf.xray/trace-buffer] {:frame app-frame})
            _ (seed-machines!)
            _ (set-history! fixture-history)
            _ (focus-epoch! 1)
            {:keys [container root]} (mount-panel! :rf/xray)]
        (try
          (is (some? (panel-node container))
              "the panel committed a real DOM root under React — a Fresco
               boundary mounted through Reagent's `:>` from the registry entry")
          (is (focused-section? container)
              "and with a machine-transitioning epoch focused it committed the
               focused-event surface, so the body really ran through
               `panel-tree` rather than painting an empty shell")

          ;; ---- the island, which only a real commit can witness -----------
          ;;
          ;; TWO ROWS, AND THE ORDER IS THE POINT (rf2-q6n3). Until this
          ;; commit there was ONE row here, selecting the WRAPPER and
          ;; claiming the island. `machine_inspector` emits that wrapper
          ;; itself, two levels above its `as-child` call, so the row was
          ;; true of a tree with no island in it at all — it restated what
          ;; the `focused-section?` row above already establishes. The
          ;; wrapper row is kept because it IS a real witness of its own
          ;; claim; what changed is the sentence attached to it.
          (is (some? (chart-wrapper-node container))
              "the focused-event section reached ELEMENT 3 and took the
               has-definition arm, so it emitted the chart WRAPPER. This is a
               claim about `machine_inspector`'s own markup and no more: the
               wrapper sits above the `as-child` call and survives the island
               being absent, which is W4's subject.")
          (is (some? (island-node container))
              "THE ISLAND MOUNTED — `machine-canvas/Chart`'s OWN root div,
               below the migration seam. `Chart` is still an `rf/reg-view`,
               which Fresco's codec grades `:invalid` in head position down
               the identical arm a plain `defn` takes, so this node reaches
               the DOM only because the boundary hands `r/as-element` down as
               the `as-child` spelling and Reagent expanded the head. Under
               the node lane's `identity` the island is a no-op and this
               claim cannot be made at all.")

          ;; ---- criterion 4: the reads are where the tree said they'd be ----
          (doseq [query-v boundary-reads]
            (is (pos? (ref-count-of :rf/xray query-v))
                (str "the boundary's read " (pr-str query-v) " holds a "
                     "reference in :rf/xray's sub-cache — the frame the "
                     "enclosing frame-provider named. Cache keys: "
                     (pr-str (keys (cache-of :rf/xray)))))
            (is (zero? (ref-count-of app-frame query-v))
                (str "and NOT in the application frame's — a foreign root "
                     "that inherited the ambient scope instead of reading "
                     "React context would put " (pr-str query-v) " here")))
          (is (pos? (ref-count-of app-frame [:rf.xray/trace-buffer]))
              "NON-VACUITY: the application frame's cache is readable by this
               same instrument and does hold the probe's entry, so the zeros
               above are absences and not a broken reader")
          (finally
            (rf/unsubscribe [:rf.xray/trace-buffer] {:frame app-frame})
            (teardown! root container)))))))

;; ===========================================================================
;; W2 — it updates on a real dependency change, and the control is deaf
;; ===========================================================================

(deftest w2-panel-updates-on-a-real-dependency-change
  (testing "rf2-k97c.3 — the mounted panel re-renders itself and commits new
            DOM when its reads' values really change, and does NOT when
            nothing it watches moved. Epic criterion 2, with the control that
            makes the update mean liveness rather than a commit that simply
            had not happened yet."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        ;; Mount with the machines seeded but an EMPTY spine, so the panel
        ;; starts blank and the focused-event surface's ARRIVAL is the signal.
        (seed-machines!)
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              section (panel-node container)]
          (is (some? section)
              "PRECONDITION: the panel is on screen at all")
          (is (not (focused-section? container))
              "NON-VACUITY: with an empty spine the focused-event surface is
               NOT on screen before this row moves the world")

          ;; ---- phase 2: the world moves, and the panel is deaf ------------
          (rf/dispatch-sync [::write-unwatched-slot 1] {:frame :rf/xray})
          (-> (settle)
              (.then
                (fn [_]
                  (is (not (focused-section? container))
                      "CONTROL: given a full settling window, a write to an
                       app-db slot the panel's read set does not consult
                       commits nothing. A panel that repainted here would make
                       phase 3 pass for a reason that is not liveness")
                  ;; ---- phase 3: the reads' real input moves --------------
                  (set-history! fixture-history)
                  (rf.test-support/poll-until #(focused-section? container)
                    {:label "the panel committed the focused-event surface"})))
              (.then
                (fn [_]
                  (is (focused-section? container)
                      "the panel re-rendered on a real invalidation of its own
                       reads and committed the focused-event surface")
                  (is (identical? section (panel-node container))
                      "and it is the SAME <section> node: React reconciled the
                       live tree in place, so the surface did not arrive by the
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
;; W3 — unmount releases every read, and reopening does not grow the counts
;; ===========================================================================

(deftest w3-unmount-releases-the-reads-and-reopen-does-not-grow-them
  (testing "rf2-k97c.3 — unmounting the panel releases ALL FIVE subscription
            references completely, and mounting it again returns to the SAME
            counts rather than higher ones. Epic criterion 6, and the number
            the spike caught the rejected design on: with a four-call interop
            binding the `:rf/xray` ref-count climbed across renders and never
            fell on unmount.

            THE RELEASE IS ASYNCHRONOUS BY DESIGN, and this row polls rather
            than reading once: the collector gives a cell whose last reader
            unmounts one macrotask of grace, so that a keyed reorder which
            unmounts and remounts a row within a single turn reuses the
            reaction instead of rebuilding it. A synchronous assertion would
            report a LEAK against a collector behaving exactly as documented."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (seed-machines!)
        (set-history! fixture-history)
        (focus-epoch! 1)
        ;; The starting point is polled, not asserted: a neighbouring row's
        ;; teardown grace may still be in flight when this one begins.
        (-> (rf.test-support/poll-until released?
              {:label "no reference held before the first mount"})
            (.then
              (fn [_]
                (let [{:keys [container root]} (mount-panel! :rf/xray)
                      mounted (mapv #(ref-count-of :rf/xray %) boundary-reads)]
                  (is (every? pos? mounted)
                      (str "the mount took a reference for every one of the "
                           "boundary's reads — otherwise the release below is "
                           "vacuous. Got " (pr-str mounted) " for "
                           (pr-str boundary-reads)))
                  (teardown! root container)
                  (-> (rf.test-support/poll-until released?
                        {:label "the first unmount released every read"})
                      (.then
                        (fn [_]
                          (is (released?)
                              (str "the unmount released them COMPLETELY, "
                                   "within the collector's grace macrotask. "
                                   "Cache: " (pr-str (keys (cache-of :rf/xray)))))
                          ;; ---- reopen: the same counts, not higher ones ----
                          (let [{c2 :container r2 :root} (mount-panel! :rf/xray)
                                remounted (mapv #(ref-count-of :rf/xray %)
                                                boundary-reads)]
                            (is (= mounted remounted)
                                (str "reopening returns to the SAME reference "
                                     "counts " (pr-str mounted) " rather than "
                                     "accumulating — accumulation across "
                                     "open/close cycles is the signature of a "
                                     "release the substrate's own reaction "
                                     "lifecycle cannot see. Got "
                                     (pr-str remounted)))
                            (teardown! r2 c2)
                            (rf.test-support/poll-until released?
                              {:label "the second unmount released them too"}))))))))
            (.then (fn [_] (is (released?)
                               "and the second unmount releases them too")))
            (.catch (fn [e] (is false (str "poll timed out: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

;; ===========================================================================
;; W4 — the island selector discriminates, and the wrapper selector does not
;; ===========================================================================

;; THE CONTROL IS `Panel`'s OWN BODY WITH ONE ARGUMENT SWAPPED.
;;
;; `machine-inspector/panel-tree` takes the island spelling as a PARAMETER —
;; `r/as-element` under the boundary, `identity` for the node lane — and
;; threads it to exactly one call site, `focused-event-section`'s
;; `(as-child [machine-canvas/Chart …])`. An island-less tree is therefore
;; that same fn with `(constantly nil)` in that one argument: not a hand-built
;; fixture resembling the panel, but the production tree builder emitting the
;; production wrapper through the production seam. Both halves below come from
;; the same fn over the same seeded state, so `as-child` is the ONLY variable
;; between them.
;;
;; IT IS HOSTED IN A REAL BOUNDARY rather than in a bare Reagent tree, and
;; that is load-bearing rather than ceremony: `panel-tree`'s ELEMENT 2 reaches
;; the shared mini-pipeline, whose EDN-widget heads are Fresco boundaries.
;; Reagent mints its own component for a fn head and CALLS it during Reagent's
;; render, which is not a React function-component render, so the boundary's
;; first hook throws, React swallows the render throw, and that subtree
;; silently commits nothing. `machine_epochs_always_round_dom_cljs_test`
;; records the same fact from the other side and hosts its subject this way.
;;
;; The spelling arrives through an atom rather than through props because only
;; an EMPTY props map survives the `[:>]` crossing intact.

(defonce ^:private !island-spelling
  (atom nil))

(rf.fresco/defview IslandProbe
  "[[Panel]]'s body — the same five queries through the same collector, the
  same `panel-tree` call — with the island spelling read from
  [[!island-spelling]] rather than hard-coded to `r/as-element`. Takes the
  ordinary one-props-map argument every `defview` takes and reads nothing
  from it."
  [_props]
  (machine-inspector/panel-tree
    (rf.fresco/sub data-q)
    (rf.fresco/sub records-q)
    (:cascade (rf.fresco/sub cascade-q))
    @!island-spelling
    (rf.fresco/sub fit-q)
    (rf.fresco/sub frame-q)))

(def ^:private IslandProbe-component
  "The React component [[IslandProbe]] presents as, for the Reagent root
  below. Declared ONCE at top level, as `rf.fresco/as-component` requires."
  (rf.fresco/as-component IslandProbe))

(defn- mount-probe!
  "Mount [[IslandProbe]] with `as-child` as its island spelling, inside the
  `frame-provider` the real shell wraps every panel in. Committed
  synchronously, as [[mount-panel!]] is and for the same reason."
  [as-child]
  (reset! !island-spelling as-child)
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame :rf/xray}
                          [:> IslandProbe-component {}]])))
    {:container container :root root}))

(deftest w4-the-island-selector-discriminates-and-the-wrapper-does-not
  (testing "rf2-q6n3 — with the island child removed, the
            `rf-xray-machine-focused-event-chart` wrapper is STILL COMMITTED
            while the `rf-xray-machine-canvas-host` island is GONE. That
            asymmetry is the whole row. It is what makes W1's island
            assertion a claim about the island, and it is the standing
            demonstration that the assertion W1 used to carry — which
            selected the wrapper — could not have failed on an absent island
            and therefore never made the claim its docstring stated."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (seed-machines!)
        (set-history! fixture-history)
        (focus-epoch! 1)
        (let [!live (atom (mount-probe! r/as-element))
              !dark (atom nil)
              drop! (fn [a]
                      (when-let [{:keys [root container]} @a]
                        (reset! a nil)
                        (teardown! root container)))]
          (-> (rf.test-support/poll-until
                #(some? (island-node (:container @!live)))
                {:label "the probe committed Chart's own canvas host"})
              (.then
                (fn [_]
                  ;; ---- the island is really there when it is there --------
                  (is (= "rf-xray-machine-canvas-host"
                         (some-> (island-node (:container @!live))
                                 (.getAttribute "data-testid")))
                      "NON-VACUITY: handed the production spelling
                       `r/as-element`, the probe commits `machine-canvas/
                       Chart`'s own root. So this harness CAN produce an
                       island, and the absence asserted below is a real
                       absence rather than a harness that never renders one.
                       Compared against the marker written out in full, so a
                       nil node cannot agree with a nil expectation.")
                  (is (some? (chart-wrapper-node (:container @!live)))
                      "and the wrapper is committed here too, exactly as in
                       W1 — which is what makes the pair below a controlled
                       comparison rather than two unrelated trees")
                  (drop! !live)
                  ;; ---- now take the island away, and only the island -----
                  (reset! !dark (mount-probe! (constantly nil)))
                  (settle)))
              (.then
                (fn [_]
                  (let [container (:container @!dark)]
                    (is (nil? (island-node container))
                        "THE ISLAND IS GONE. `as-child` answered nil, so
                         `machine-canvas/Chart` never ran and nothing in the
                         committed DOM carries its root marker. Asserted
                         after a full settling window, so the absence is a
                         decision and not a race.")
                    (is (some? (chart-wrapper-node container))
                        "AND THE WRAPPER IS STILL STANDING. This is the
                         rf2-q6n3 defect stated as a passing assertion:
                         `machine_inspector` emits
                         `rf-xray-machine-focused-event-chart` two levels
                         ABOVE its `as-child` call, so a row selecting that
                         marker reads GREEN over a tree with no island in it
                         at all. The island claim cannot be made with this
                         node, and W1 no longer makes it."))))
              (.catch (fn [e]
                        (is false (str "W4 never settled: " (.-message e)))
                        nil))
              (.then (fn [_]
                       (drop! !live)
                       (drop! !dark)
                       (reset! !island-spelling nil)
                       (done)))))))))
