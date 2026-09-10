(ns day8.re-frame2-xray.static.interceptors.panel-fresco-boundary-dom-cljs-test
  "THE FIRST STATIC-SURFACE PANEL RE-AUTHORED IN THE RE-FRAME-NATIVE VIEW
  LAYER, read off a real React commit (rf2-k97c.3).

  `static.interceptors.panel/Panel` is now an `rf.fresco/defview` reading
  through Fresco's shipped collector rather than an `rf/reg-view` reading
  through whatever view build the installed substrate adapter supplies.
  This file is the behavioural evidence for that swap.

  ## What the epic asked for, and which row answers it

  rf2-k97c's success criteria are behavioural. Five are answerable at this
  panel's own boundary:

    1 FIRST DISPLAY               — W1
    2 UPDATES ON A REAL CHANGE    — W2 (with the deaf control that makes
                                    the update mean liveness)
    4 FRAME TARGETING             — W1's second half, read off the frame's
                                    OWN sub-cache rather than off the DOM
    5 TOOL ACTIVITY NEVER
      MASQUERADING AS APPLICATION
      EVIDENCE                    — W3, in BOTH directions
    6 CLEAN TEARDOWN              — W4

  Criterion 3 (Xray's own interactions) has no row here, and that is a
  measured property of this panel rather than an omission: the Interceptors
  tab is a PURE-BROWSE catalogue whose only affordance is the shared search
  box's keystroke dispatch, and the boundary hands that dispatcher down
  already frame-bound. A click row would assert about a control the panel
  does not have.

  ## W5 IS NOT ONE OF THE SIX, AND IT IS THE ROW THIS PANEL NEEDED MOST

  The migration moved every row's React key out of Clojure metadata, which
  Fresco's codec reads NOWHERE, and onto a keyed fragment's attribute map,
  which it does. A LOST KEY DOES NOT FAIL — it degrades into index-based
  reconciliation, which paints identically and corrupts identity only once
  the list changes SHAPE. So no amount of \"the rows are on screen\" can
  see it, and an assertion on the metadata is a hollow gate that passes
  while React receives nothing. W5 changes the list's shape and asks React
  which node survived.

  ## The mount is the SHELL's mount, taken from the registry

  `static/shell.cljs`'s `detail-panel` mounts the active tab as the hiccup
  head `[(:panel tab)]` inside the shell's `[rf/frame-provider {:frame …}]`.
  [[mount-panel!]] does exactly that, and it reaches `:panel` THROUGH
  `panel-registry/tab-by-id :static` rather than naming the var — so the
  bridge the registry actually holds is the thing under test. A bridge that
  regressed to something the shell cannot mount reddens here rather than in
  a browser.

  Nothing below ever calls the panel a second time. Every assertion after
  the mount reads `container.querySelector…` — the DOM React committed on
  its own.

  ## Substrate: the Reagent adapter, deliberately

  A ratom-family adapter, which is the family Xray already supports —
  because the claim being made is that the boundary is INDIFFERENT to it.
  `:ambient-frame nil` is load-bearing exactly as it is in the template:
  the fixture's default ambient scope is still in effect during a
  synchronous `flushSync`, and tier 1 of the frame resolver is the dynamic
  var, so an ambient frame would SHADOW the React-context tier W1's
  frame-targeting row is about and that row would pass while measuring
  nothing.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium). The `:node-test` build's regex
  also matches, so it LOADS under Node — where every row short-circuits
  through [[browser?]] and reports the skip rather than passing silently."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.interceptors.panel :as panel]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private app-frame
  "An ORDINARY application frame — the thing Xray inspects. Two rows need
  one that is not `:rf/xray`: W1 reads it as the negative half of the
  frame-targeting claim, and W3 mounts INSIDE it so the evidence claim
  cannot be carried by `:rf/xray`'s trace-disabled gate."
  ::app)

(def ^:private tab-data-q
  "The panel's one read. Named once because three rows key off it — the
  sub-cache is keyed by the query vector itself, so this value IS the
  cache key."
  [:rf.xray.static.interceptors/tab-data])

;; ---- probe registrations --------------------------------------------------

;; W2's phase-3 lever, and it is the panel's REAL dependency rather than a
;; side door: `:rf.xray.static.interceptors/registry` declares
;; `:rf.xray/trace-buffer` as an input, and that sub is `(get db
;; :trace-buffer)` on Xray's own app-db. Writing the slot is exactly the
;; "something changed" pulse the live trace collector delivers.
(rf/reg-event ::bump-trace-buffer
  (fn [{:keys [db]} [_ n]]
    {:db (assoc db :trace-buffer [{::probe n}])}))

(rf/reg-view ProbeRegView
  "The POSITIVE CONTROL for W3, and nothing else. An ordinary `reg-view`
  rendered by the installed adapter in the same root, in the same frame,
  in the same commit as the panel — so the only variable between it and
  the panel is which view layer authored the body."
  []
  [:div {:data-testid "rf-xray-probe-reg-view"}])

;; ---- W5's two-row fixture -------------------------------------------------
;;
;; Sorted by `(pr-str id)`, so `:aaa/first` really is the HEAD row and
;; removing it is a genuine reorder rather than a tail truncation. A tail
;; removal leaves the survivor untouched under EITHER key spelling and
;; would make the row vacuous — which is why W5 asserts the head position
;; before it removes anything.

(def ^:private two-rows
  {:evt/one {:interceptors [{:id :aaa/first  :before identity}]}
   :evt/two {:interceptors [{:id :bbb/second :before identity}]}})

(def ^:private one-row
  {:evt/two {:interceptors [{:id :bbb/second :before identity}]}})

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
;; omission. A Fresco boundary is NOT in Reagent's render queue — its update is
;; scheduled by the collector through React — so draining Reagent's queue
;; commits nothing of this panel's, and a row written that way reads a DOM that
;; has not moved and reports a live panel as dead. Mount is committed with
;; `flushSync` (React's own door) and everything after it is polled.

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask. It exists
  for the CONTROL in W2: an absence asserted immediately after the world
  moves is a race, and an absence asserted after this is a decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- setup!
  "Register Xray's handlers — which is what registers the interceptors
  sub family and the `:static` L4 tab entry the mount reads — and make the
  two frames."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- setup-with-overrides!
  "[[setup!]] plus the test-only override seam, which re-registers the
  panel's `registry` sub to read an injected registrations map. W5 needs
  the list's contents under its own hand; the rows that do not, do not
  install it."
  []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- override! [registrations-map]
  (rf/dispatch-sync
    [:rf.xray.static.interceptors/set-registry-override-for-test
     registrations-map]
    {:frame :rf/xray}))

(defn- mount-panel!
  "Mount the Interceptors tab the way `static/shell.cljs`'s `detail-panel`
  mounts it: the registry's `:panel` value as a hiccup head, inside a
  `frame-provider` scoping `frame`. Committed synchronously — React 19's
  `root.render` is otherwise async and phase 1 would assert against an
  empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)
        tab       (panel-registry/tab-by-id :static :interceptors)]
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

(defn- row-node
  "The committed `<li>` for one interceptor id, or nil."
  [container row-id]
  (q container (str "[data-testid=\"rf-xray-static-interceptors-row-"
                    row-id "\"]")))

(defn- row-nodes [container]
  (vec (.from js/Array
              (.querySelectorAll
                container
                "[data-testid^=\"rf-xray-static-interceptors-row-\"]"))))

(defn- cache-of
  "The frame's live sub-cache map. Not `some->`-guarded: a nil here means
  the frame is not live, which is a defect in the row's own setup and
  should throw rather than read as an empty cache."
  [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of
  "The sub-cache ref-count the frame holds for `query-v`, or 0 when the
  entry is absent."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

;; ===========================================================================
;; W1 — first display, and the read lands in the frame the tree named
;; ===========================================================================

(deftest w1-panel-paints-and-its-read-lands-in-the-named-frame
  (testing "rf2-k97c.3 — the migrated Static Interceptors panel commits real
            DOM through the registry entry the Static shell mounts, and its
            `rf.fresco/sub` read resolves against the frame the enclosing
            `frame-provider` named rather than the ambient one. Epic criteria
            1 and 4."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            ;; A live read in the OTHER frame, so the negative half of the
            ;; targeting claim below is measured with an instrument that is
            ;; demonstrably able to see an entry in that frame's cache.
            _probe (rf/subscribe [:rf.xray/trace-buffer] {:frame app-frame})
            {:keys [container root]} (mount-panel! :rf/xray)]
        (try
          (is (some? (q container "[data-testid=\"rf-xray-static-interceptors\"]"))
              "the panel committed a real DOM root under React — a Fresco
               boundary mounted through Reagent's `:>` from the registry entry")
          (is (some? (q container
                        "[data-testid=\"rf-xray-static-interceptors-search\"]"))
              "and the search box rendered, so the body ran through
               `panel-tree` rather than short-circuiting to the empty state.
               `search-box/search-box` is a plain fn and is now CALLED — used
               as a hiccup head it would be a loud error inside a boundary")

          ;; ---- criterion 4: the read is where the tree said it would be ----
          (is (pos? (ref-count-of :rf/xray tab-data-q))
              (str "the panel's read holds a reference in :rf/xray's sub-cache "
                   "— the frame the enclosing frame-provider named. Cache keys: "
                   (pr-str (keys (cache-of :rf/xray)))))
          (is (zero? (ref-count-of app-frame tab-data-q))
              "and NOT in the application frame's — a foreign root that
               inherited the ambient scope instead of reading React context
               would put it here")
          (is (pos? (ref-count-of app-frame [:rf.xray/trace-buffer]))
              "NON-VACUITY: the application frame's cache is readable by this
               same instrument and does hold the probe's entry, so the zero
               above is an absence and not a broken reader")
          (finally
            (rf/unsubscribe [:rf.xray/trace-buffer] {:frame app-frame})
            (teardown! root container)))))))

;; ===========================================================================
;; W2 — it updates on a real dependency change, and the control is deaf
;; ===========================================================================

(deftest w2-panel-updates-on-a-real-dependency-change
  (testing "rf2-k97c.3 — the mounted panel re-renders itself and commits new
            DOM when its read's value really changes, and does NOT when
            nothing it watches moved. Epic criterion 2, with the control that
            makes the update mean liveness rather than a commit that simply
            had not happened yet."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              section (q container "[data-testid=\"rf-xray-static-interceptors\"]")
              probe?  (fn [] (some? (row-node container "probe/late-icpt")))]
          (is (some? section)
              "PRECONDITION: the panel is on screen at all")
          (is (not (probe?))
              "NON-VACUITY: the interceptor this row drives in is NOT on
               screen before it is registered")

          ;; ---- phase 2: the world moves, and the panel is deaf ------------
          ;; The panel's registry sub reads the process-global `:event`
          ;; registrar and is gated on `:rf.xray/trace-buffer`. Registering an
          ;; event carrying a new interceptor changes what the read WOULD
          ;; compute while invalidating nothing it watches.
          (rf/reg-interceptor :probe/late-icpt {:before identity})
          (rf/reg-event ::late-event
            {:interceptors [:probe/late-icpt]}
            (fn [{:keys [db]} _] {:db db}))
          (is (some? (first (filter #(= :probe/late-icpt (:id %))
                                    (panel/collect-interceptors
                                      (rf/registrations {:source :store
                                                         :kind   :event})))))
              "PRECONDITION: the read's UNDERLYING data now carries the new
               interceptor — so a missing row below is the panel failing to
               re-render, and not the interceptor failing to exist")
          (-> (settle)
              (.then
                (fn [_]
                  (is (not (probe?))
                      "CONTROL: given a full settling window, the committed DOM
                       still does NOT carry the row. A panel that re-rendered
                       here would make phase 3 pass for a reason that is not
                       liveness")
                  ;; ---- phase 3: the read's real input moves --------------
                  (rf/dispatch-sync [::bump-trace-buffer 1] {:frame :rf/xray})
                  (rf.test-support/poll-until probe?
                    {:label "the panel committed the new interceptor's row"})))
              (.then
                (fn [_]
                  (is (probe?)
                      "the panel re-rendered on a real invalidation of its own
                       read and committed the new interceptor's row")
                  (is (identical?
                        section
                        (q container "[data-testid=\"rf-xray-static-interceptors\"]"))
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
      (let [_        (setup!)
            traces   (atom [])
            view-op? #(and (keyword? (:operation %))
                           (= "rf.view" (namespace (:operation %))))]
        (rf/register-listener! :trace ::collect (fn [ev] (swap! traces conj ev)))
        (try
          ;; ---- the subject: the panel, mounted in an APPLICATION frame ----
          (let [{:keys [container root]} (mount-panel! app-frame)
                subject-views (filterv view-op? @traces)]
            (try
              (is (some? (q container
                            "[data-testid=\"rf-xray-static-interceptors\"]"))
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

(defn- released? []
  (zero? (ref-count-of :rf/xray tab-data-q)))

(deftest w4-unmount-releases-the-read-and-reopen-does-not-grow-it
  (testing "rf2-k97c.3 — unmounting the panel releases its subscription
            reference completely, and mounting it again returns to the SAME
            count rather than a higher one. Epic criterion 6, and the number
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
        ;; The starting point is polled, not asserted: a neighbouring row's
        ;; teardown grace may still be in flight when this one begins.
        (-> (rf.test-support/poll-until released?
              {:label "no reference held before the first mount"})
            (.then
              (fn [_]
                (let [{:keys [container root]} (mount-panel! :rf/xray)
                      mounted (ref-count-of :rf/xray tab-data-q)]
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
                                remounted (ref-count-of :rf/xray tab-data-q)]
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
;; W5 — the row keys REACH REACT: the survivor of a head removal is the same
;;      DOM node
;; ===========================================================================

(deftest w5-row-identity-survives-a-head-removal
  (testing "rf2-k97c.3 — the row React key the migration moved out of Clojure
            metadata and into a keyed fragment's ATTRIBUTE MAP is the key
            React actually reconciles on. Removing the HEAD of a two-row list
            leaves the survivor as the SAME DOM node; under index-based
            reconciliation — which is what a lost key silently degrades to —
            React would reuse the head's node for the survivor and destroy the
            one this row is holding.

            The head position is ASSERTED rather than assumed: a removal from
            the TAIL leaves the survivor untouched under either spelling and
            would make this row vacuous."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup-with-overrides!)
        (override! two-rows)
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              head     (row-node container "aaa/first")
              survivor (row-node container "bbb/second")]
          (is (some? head)     "PRECONDITION: the head row is on screen")
          (is (some? survivor) "PRECONDITION: the survivor row is on screen")
          (is (= 2 (count (row-nodes container)))
              "PRECONDITION: exactly the two fixture rows are on screen")
          (when (and (some? head) (some? survivor))
            ;; DOCUMENT_POSITION_FOLLOWING = 4. A removal from the tail is
            ;; invisible to this row's claim, so prove we are removing the head.
            (is (pos? (bit-and (.compareDocumentPosition head survivor) 4))
                "NON-VACUITY: the row being removed really does PRECEDE the
                 survivor in document order, so this is a reorder and not a
                 tail truncation"))
          (override! one-row)
          (-> (rf.test-support/poll-until
                (fn [] (= 1 (count (row-nodes container))))
                {:label "the head row left the committed DOM"})
              (.then
                (fn [_]
                  (is (nil? (row-node container "aaa/first"))
                      "the removed row is gone from the committed DOM")
                  (is (identical? survivor (row-node container "bbb/second"))
                      "and the survivor is the IDENTICAL DOM node React
                       already had — which is only true if the key reached
                       React. With the key lost to metadata the codec cannot
                       read, React reconciles by index and hands the survivor
                       the HEAD's node instead")))
              (.catch (fn [e]
                        (is false (str "W5 never settled: " (.-message e)
                                       " — DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (teardown! root container)
                       (done)))))))))
