(ns day8.re-frame2-xray.static.shell-fresco-boundary-dom-cljs-test
  "Xray's Static SHELL re-authored in the re-frame-native view layer, read
  off a real React commit (rf2-k97c.3).

  `static.shell`'s four regions — `ribbon`, `tab-bar`, `detail-panel` and
  `surface` — are now `rf.fresco/defview` BOUNDARIES rather than
  `rf/reg-view`s. Every earlier increment of this epic migrated a PANEL;
  this is the first to migrate a piece of Xray's own CHROME, and the
  chrome is what the mount path paints. This file is the behavioural
  evidence for that swap.

  ## What the epic asked for, and which row answers it

    1 FIRST DISPLAY               — W1, and here that means the whole
                                    3-layer chrome, because the node lane
                                    can no longer walk past the bridge
    2 UPDATES ON A REAL CHANGE    — W2 (with the deaf control that makes
                                    the update mean liveness)
    3 XRAY'S OWN INTERACTIONS     — W2's phase 3 fires the shell's OWN
                                    affordance, a tab click, through the
                                    committed DOM
    4 FRAME TARGETING             — W1's second half, read off the frame's
                                    OWN sub-cache rather than off the DOM
    5 TOOL ACTIVITY NEVER
      MASQUERADING AS APPLICATION
      EVIDENCE                    — W3, SCOPED — see below
    6 CLEAN TEARDOWN              — W4

  W5 is not one of the six: it witnesses that the tab-button React key
  the migration moved out of `^{:key …}` reader metadata and into the
  button's own ATTRIBUTE MAP is the key React actually reconciles on.
  A lost key does not fail — it degrades into index-based
  reconciliation, which paints identically and corrupts identity only
  once the list changes SHAPE — so no amount of \"the tabs are on
  screen\" can see it. The key expression is the tab `id`, a keyword,
  which is DOMAIN-shaped rather than positional, so a head-removal
  identity row can see it (the positional-key case two earlier
  increments hit has nothing for such a row to witness).

  ## W3'S ZERO IS SCOPED, AND THE SCOPE IS THE POINT

  A Fresco boundary emits no `:rf.view/*` op — that is the structural
  claim, and W3 makes it. But the Static shell's rendered SUB-TREE is
  not trace-silent, and cannot be in this increment: the L1 ribbon
  reaches `frame-switcher/frame-switcher-view` and `mode-pill/mode-pill`
  through an `as-child` REAGENT ISLAND, and both are still `rf/reg-view`s
  that emit view trace wherever they render.

  So W3 asserts on the view-op ids rather than on a bare count: NO op
  names a view in `day8.re-frame2-xray.static.shell`, and the ids that
  DO fire are exactly the two documented islands. That second half is
  what stops the row degrading into \"some ops fired, fine\" — a future
  change that islands a third widget reddens here and has to say so.
  The remaining emissions go when those two widgets are boundaries, in
  the same commit that deletes the ribbon's `as-child` seam.

  ## The mount is the SHELL'S mount

  `shell.cljs`'s `surface-composer` mounts the Static arm as the hiccup
  head `[static-shell/surface-bridge]` inside `shell-view`'s
  `[rf/frame-provider {:frame …}]`. [[mount-shell!]] does exactly that.

  Nothing below ever calls a view a second time. Every assertion after
  the mount reads `container.querySelector…` — the DOM React committed
  on its own.

  ## Substrate: the Reagent adapter, deliberately

  A ratom-family adapter, which is the family Xray already supports —
  because the claim being made is that the chrome is INDIFFERENT to it.
  `:ambient-frame nil` is load-bearing: the fixture's default ambient
  scope is still in effect during a synchronous `flushSync`, and tier 1
  of the frame resolver is the dynamic var, so an ambient frame would
  SHADOW the React-context tier W1's frame-targeting row is about and
  that row would pass while measuring nothing.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium). The `:node-test` build's regex
  also matches, so it LOADS under Node — where every row short-circuits
  through [[browser?]] and reports the skip rather than passing
  silently."
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
            [day8.re-frame2-xray.static.shell :as static-shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private app-frame
  "An ORDINARY application frame — the thing Xray inspects. Two rows need
  one that is not `:rf/xray`: W1 reads it as the negative half of the
  frame-targeting claim, and W3 mounts INSIDE it so the evidence claim
  cannot be carried by `:rf/xray`'s trace-disabled gate."
  ::app)

(def ^:private selected-tab-q
  "The shell's one read, made by TWO of its boundaries (`tab-bar` and
  `detail-panel`, deliberately kept apart so a tab click does not
  re-render the ribbon). Named once because three rows key off it — the
  sub-cache is keyed by the query vector itself, so this value IS the
  cache key."
  [:rf.xray.static/selected-tab])

(def ^:private shell-ns
  "The namespace whose views W3 asserts emit nothing. Compared against
  the `view-id` half of a `:rf.view/render-key`, which is a namespaced
  keyword naming the registered `reg-view`."
  "day8.re-frame2-xray.static.shell")

(def ^:private expected-island-views
  "The view-ids W3 EXPECTS to fire, and the whole of them: the L1
  ribbon's two `as-child` Reagent islands. Stated positively so that
  islanding a third widget cannot slip past a row that only counted
  zeros in one direction.

  W3 PINS THE L4 SLOT TO :flows FOR THIS SET TO BE WELL-DEFINED, and
  the reason is measured rather than tidy. Written against the DEFAULT
  :machines tab the row failed with a third id,
  `panels.machine-canvas/Chart` — the Static Machines panel's own
  Topology island, which `definition-detail` reaches through its
  `as-child` seam once a machine is selected. Whether one IS selected
  is a property of the PAGE rather than of this row: the registrar is
  process-GLOBAL, the `:browser-test` build runs every `-dom-cljs-test`
  namespace in one page, and a neighbouring suite's `rf/reg-machine`
  is enough. So :machines would make this set depend on load order in
  BOTH directions. The Flows tab is fully migrated with no island at
  all, which makes the set a statement about THIS shell."
  #{:day8.re-frame2-xray.frame-switcher/frame-switcher-view
    :day8.re-frame2-xray.static.mode-pill/mode-pill})

(def ^:private island-free-tab
  "The Static tab W3 mounts. See [[expected-island-views]]."
  :flows)

;; ---- probes ---------------------------------------------------------------

(rf/reg-view ProbeRegView
  "The POSITIVE CONTROL for W3, and nothing else. An ordinary `reg-view`
  rendered by the installed adapter in the same root, in the same frame,
  in the same commit shape as the shell — so the only variable between
  it and the shell's chrome is which view layer authored the body."
  []
  [:div {:data-testid "rf-xray-probe-reg-view"}])

(defn- probe-panel
  "W2's deaf lever mounts a real L4 tab, so it needs a real `:panel`
  callable. Plain hiccup: the tab under test is the BUTTON in the L3
  bar, never this body."
  []
  [:div {:data-testid "rf-xray-static-probe-panel"}])

(def ^:private probe-tab-id :probe/deaf)

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
                      ;; this shell's.
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
;; commits nothing of these boundaries', and a row written that way reads a DOM
;; that has not moved and reports a live shell as dead. Mount is committed with
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
  "Register Xray's handlers — which is what registers the Static tab
  slot's sub / event family AND every Static panel's `:static` L4 tab
  entry, so the L3 bar and the L4 mount both read the real registry —
  and make the two frames."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- mount-shell!
  "Mount the Static shell the way `shell.cljs`'s `surface-composer`
  mounts it: `static-shell/surface-bridge` as a hiccup head, inside a
  `frame-provider` scoping `frame`. Committed synchronously — React 19's
  `root.render` is otherwise async and phase 1 would assert against an
  empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [static-shell/surface-bridge]])))
    {:container container :root root}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — which is where
  the collector releases a boundary's reads — have RUN by the time the
  next line reads the sub-cache. A bare `.unmount` schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- q [container sel] (.querySelector container sel))

(defn- testid [container id]
  (q container (str "[data-testid=\"" id "\"]")))

(defn- tab-node
  "The committed `<button>` for one Static tab id, or nil."
  [container tab-id]
  (testid container (str "rf-xray-static-tab-" (name tab-id))))

(defn- tab-nodes [container]
  (vec (.from js/Array
              (.querySelectorAll
                container
                "[data-testid^=\"rf-xray-static-tab-\"][role=\"tab\"]"))))

(defn- click!
  "Click a committed node, or FAIL THIS ROW rather than aborting the lane.

  MEASURED, and it is why this helper exists rather than a bare
  `.click`. Two rows below drive the shell through its own affordances,
  so both hold a node that a regression can make nil. A raw `.click` on
  nil throws a `TypeError` out of the async block, and
  `cljs.test/run-block` has no try/catch — under a sabotage plant that
  emptied the chrome it took the WHOLE browser lane down with NO
  cljs.test summary at all, and every namespace scheduled after this one
  never executed. A row whose subject has vanished should redden; it
  must not silence its neighbours. The subsequent `poll-until` then
  times out and reddens on its own message, which is the honest report."
  [node label]
  (if (some? node)
    (do (.click node) true)
    (do (is false (str "cannot click " label ": it is not in the committed "
                       "DOM, so this row's subject is already gone"))
        false)))

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
;; W1 — first display of the WHOLE chrome, and the read lands in the frame
;;      the tree named
;; ===========================================================================

(deftest w1-shell-paints-three-layers-and-its-read-lands-in-the-named-frame
  (testing "rf2-k97c.3 — the migrated Static shell commits its whole
            3-layer chrome through the bridge `surface-composer` mounts,
            and its `rf.fresco/sub` read resolves against the frame the
            enclosing `frame-provider` named rather than the ambient one.
            Epic criteria 1 and 4.

            THIS ROW CARRIES MORE THAN A PANEL'S W1 DOES, and by
            necessity: the node lane's chrome rows now stop at the
            bridge's `[:>]` interop head, so the assertion that all three
            layers actually paint has nowhere else to live."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            ;; A live read in the OTHER frame, so the negative half of the
            ;; targeting claim below is measured with an instrument that is
            ;; demonstrably able to see an entry in that frame's cache.
            _probe (rf/subscribe [:rf.xray/trace-buffer] {:frame app-frame})
            {:keys [container root]} (mount-shell! :rf/xray)]
        (try
          (is (some? (testid container "rf-xray-static-surface"))
              "the Static surface committed a real DOM root under React — a
               Fresco boundary mounted through Reagent's `:>` from the bridge
               `surface-composer` names")
          (is (some? (testid container "rf-xray-static-ribbon"))
              "L1 ribbon painted")
          (is (some? (testid container "rf-xray-static-tab-bar"))
              "L3 tab bar painted")
          (is (some? (testid container "rf-xray-static-detail-panel-machines"))
              "L4 detail panel painted on the default :machines tab")
          (is (= 5 (count (tab-nodes container)))
              (str "all five Static tabs painted, so the L3 bar really ran "
                   "through `tab-bar-tree` rather than painting an empty "
                   "container. Got: " (count (tab-nodes container))))
          (is (nil? (testid container "rf-xray-event-list"))
              "and NO L2 event list — Static is event-INDEPENDENT, which is
               the one structural claim that distinguishes this surface from
               the Dynamic one")
          ;; The two Reagent islands crossed. `as-child` handing back
          ;; something React drops would leave the chrome above intact and
          ;; only these missing, which is why they are asserted separately.
          (is (some? (testid container "rf-xray-mode-pill"))
              "the mode-pill ISLAND crossed the `as-child` seam and
               committed — a `reg-view` reached through
               `reagent.core/as-element` from inside a Fresco body")
          (is (some? (testid container "rf-xray-static-ribbon-icons"))
              "and the right-icons cluster, which is CALLED rather than
               headed, painted beside it")

          ;; ---- criterion 4: the read is where the tree said it would be ----
          (is (pos? (ref-count-of :rf/xray selected-tab-q))
              (str "the shell's read holds a reference in :rf/xray's sub-cache "
                   "— the frame the enclosing frame-provider named. Cache keys: "
                   (pr-str (keys (cache-of :rf/xray)))))
          (is (zero? (ref-count-of app-frame selected-tab-q))
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

(deftest w2-shell-updates-on-a-real-dependency-change
  (testing "rf2-k97c.3 — the mounted shell re-renders and commits new DOM
            when its read's value really changes, and does NOT when
            nothing it watches moved. Epic criterion 2, with the control
            that makes the update mean liveness rather than a commit that
            simply had not happened yet; and epic criterion 3, because the
            lever in phase 3 is the shell's OWN affordance — a tab button
            clicked in the committed DOM — rather than a dispatch aimed at
            the slot from outside.

            THE DEAF LEVER IS THE L4 TAB REGISTRY, which is a
            process-GLOBAL atom that `tab-bar-tree` reads through `(tabs)`
            and that no subscription watches. Registering a tab therefore
            changes what the bar WOULD compute while invalidating nothing
            it holds — which is exactly the shape a deaf control needs."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount-shell! :rf/xray)
              surface (testid container "rf-xray-static-surface")
              probe?  (fn [] (some? (tab-node container probe-tab-id)))
              flows?  (fn [] (some? (testid container
                                            "rf-xray-static-detail-panel-flows")))]
          (is (some? surface)
              "PRECONDITION: the shell is on screen at all")
          (is (some? (testid container "rf-xray-static-detail-panel-machines"))
              "PRECONDITION: the L4 slot starts on the default :machines tab")
          (is (not (probe?))
              "NON-VACUITY: the tab this row drives in is NOT on screen
               before it is registered")

          ;; ---- phase 2: the world moves, and the shell is deaf ------------
          (panel-registry/reg-l4-tab!
            {:id    probe-tab-id
             :label "Deaf"
             :mnem  "z"
             :modes #{:static}
             :order 99
             :panel probe-panel})
          (is (some? (panel-registry/tab-by-id :static probe-tab-id))
              "PRECONDITION: the read's UNDERLYING data now carries the new
               tab — so a missing button below is the bar failing to
               re-render, and not the tab failing to exist")
          (-> (settle)
              (.then
                (fn [_]
                  (is (not (probe?))
                      "CONTROL: given a full settling window, the committed DOM
                       still does NOT carry the new tab button. A bar that
                       re-rendered here would make phase 3 pass for a reason
                       that is not liveness")
                  ;; ---- phase 3: the shell's OWN affordance, in the DOM ----
                  (click! (tab-node container :flows)
                          "the shell's own :flows tab button")
                  (rf.test-support/poll-until flows?
                    {:label "the shell committed the :flows L4 slot"})))
              (.then
                (fn [_]
                  (is (flows?)
                      "clicking the shell's own :flows tab button re-rendered
                       the L4 boundary on a real invalidation of its read and
                       committed the new slot — criteria 2 and 3 together")
                  (is (nil? (testid container
                                    "rf-xray-static-detail-panel-machines"))
                      "and the old slot is gone, so this is a swap rather than
                       an accretion")
                  (is (probe?)
                      "the L3 bar re-rendered in the same turn and NOW carries
                       the tab it was deaf to — which is what makes the
                       control above an absence of liveness rather than an
                       absence of data")
                  (is (identical? surface (testid container
                                                  "rf-xray-static-surface"))
                      "and it is the SAME surface node: React reconciled the
                       live tree in place, so the swap did not arrive by the
                       shell being remounted from scratch, which would not be
                       liveness")))
              (.catch (fn [e]
                        (is false (str "W2 never settled: " (.-message e)
                                       " — DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (panel-registry/unreg-l4-tab! probe-tab-id)
                       (teardown! root container)
                       (done)))))))))

;; ===========================================================================
;; W3 — the tool's own chrome is not application view evidence
;; ===========================================================================

(defn- view-ids-of
  "The `view-id` half of every `:rf.view/*` op in `traces` — a
  `:rf.view/render-key` is `[view-id instance-token]`."
  [traces]
  (into #{}
        (comp (filter #(and (keyword? (:operation %))
                            (= "rf.view" (namespace (:operation %)))))
              (map #(first (:rf.view/render-key (:tags %))))
              (remove nil?))
        traces))

(deftest w3-the-shell-boundaries-emit-no-view-trace
  (testing "rf2-k97c.3 / rf2-tqlmq — rendering the migrated Static chrome
            contributes NOTHING to the substrate's view-trace stream under
            any of its own view names, even when it is mounted INSIDE an
            application frame. Epic criterion 5, proven structurally
            rather than by the `:rf/xray` frame gate: a Fresco boundary is
            not a substrate view render, so there is no event to gate.

            THE ZERO IS SCOPED, AND THE SCOPE IS ASSERTED. The rendered
            sub-tree is not silent — the ribbon's two `as-child` Reagent
            islands are still `reg-view`s and emit wherever they render —
            so this row states BOTH halves: no id names the shell's own
            namespace, and the ids that fire are exactly those two
            islands. A future change that islands a third widget reddens
            here rather than sliding under a bare count.

            The control is an ordinary `reg-view` in the same root, the
            same frame and the same commit shape."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_      (setup!)
            traces (atom [])]
        ;; The L4 slot is pinned to an island-free tab BEFORE the listener is
        ;; armed, and in the frame the shell will read from — React context,
        ;; not the ambient scope. See `expected-island-views`.
        (rf/dispatch-sync [:rf.xray.static/select-tab island-free-tab]
                          {:frame app-frame})
        (rf/register-listener! :trace ::collect (fn [ev] (swap! traces conj ev)))
        (try
          ;; ---- the subject: the shell, mounted in an APPLICATION frame ----
          (let [{:keys [container root]} (mount-shell! app-frame)
                ids (view-ids-of @traces)]
            (try
              (is (some? (testid container "rf-xray-static-surface"))
                  "precondition: the chrome really did render in this commit —
                   an empty container would make the zero below vacuous")
              (is (some? (testid container
                                 (str "rf-xray-static-detail-panel-"
                                      (name island-free-tab))))
                  (str "precondition: the L4 boundary really did mount the "
                       "island-free tab, so the id set below is scoped by a "
                       "tree that IS there rather than by one that is absent "
                       "for some other reason"))
              (is (empty? (filterv #(= shell-ns (namespace %)) ids))
                  (str "no :rf.view/* op names a view in " shell-ns
                       " — the four chrome regions are boundaries, not "
                       "substrate view renders. Ids seen: " (pr-str ids)))
              (is (= expected-island-views ids)
                  (str "and the ids that DO fire are exactly the two "
                       "documented Reagent islands, so the scope of the zero "
                       "above is pinned rather than assumed. Expected: "
                       (pr-str expected-island-views)
                       " Got: " (pr-str ids)))
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
              (let [ids (view-ids-of @traces)]
                (is (some? (testid container "rf-xray-probe-reg-view"))
                    "precondition: the control really did render")
                (is (pos? (count ids))
                    (str "CONTROL FIRES: an ordinary reg-view rendered the same "
                         "way DOES put an id in the stream, so the shell's "
                         "absence from it is a property of the boundary and "
                         "not of a dead instrument. Ids seen: " (pr-str ids))))
              (finally (teardown! root container))))
          (finally
            (rf/unregister-listener! :trace ::collect)))))))

;; ===========================================================================
;; W4 — clean teardown: the read is released, and reopening is not growth
;; ===========================================================================

(defn- released? []
  (zero? (ref-count-of :rf/xray selected-tab-q)))

(deftest w4-unmount-releases-the-read-and-reopen-does-not-grow-it
  (testing "rf2-k97c.3 — unmounting the Static shell releases its
            subscription reference completely, and mounting it again
            returns to the SAME count rather than a higher one. Epic
            criterion 6, and the number the spike caught the rejected
            design on: with a four-call interop binding the `:rf/xray`
            ref-count climbed across renders and never fell on unmount.

            TWO boundaries read this one query, deliberately, so the
            count is what it is; what the row is about is that the count
            RETURNS, not what it equals.

            THE RELEASE IS ASYNCHRONOUS BY DESIGN, and this row polls
            rather than reading once: the collector gives a cell whose
            last reader unmounts one macrotask of grace, so that a keyed
            reorder which unmounts and remounts within a single turn
            reuses the reaction instead of rebuilding it. A synchronous
            assertion would report a LEAK against a collector behaving
            exactly as documented."
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
                (let [{:keys [container root]} (mount-shell! :rf/xray)
                      mounted (ref-count-of :rf/xray selected-tab-q)]
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
                          (let [{c2 :container r2 :root} (mount-shell! :rf/xray)
                                remounted (ref-count-of :rf/xray selected-tab-q)]
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
;; W5 — the tab keys REACH REACT: the survivor of a head removal is the same
;;      DOM node
;; ===========================================================================

(deftest w5-tab-identity-survives-a-head-removal
  (testing "rf2-k97c.3 (RULING 2) — the tab-button React key the migration
            moved out of `^{:key …}` reader metadata and into the button's
            own ATTRIBUTE MAP is the key React actually reconciles on.
            Removing the HEAD of the tab list leaves the survivor as the
            SAME DOM node; under index-based reconciliation — which is
            what a lost key silently degrades to — React would reuse the
            head's node for the survivor and destroy the one this row is
            holding.

            The head position is ASSERTED rather than assumed: a removal
            from the TAIL leaves the survivor untouched under either
            spelling and would make this row vacuous.

            The removal alone invalidates nothing (the L4 registry is a
            plain atom no sub watches — that is exactly what makes it W2's
            deaf lever), so the re-render is driven by a real tab
            selection, which is also what makes the survivor's props
            change and the identity claim non-trivial."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (let [head-entry (panel-registry/tab-by-id :static :machines)
              {:keys [container root]} (mount-shell! :rf/xray)
              head       (tab-node container :machines)
              survivor   (tab-node container :routes)]
          (is (some? head-entry)
              "PRECONDITION: the head tab is registered, so restoring it in
               the finally below is real")
          (is (some? head)     "PRECONDITION: the head tab is on screen")
          (is (some? survivor) "PRECONDITION: the survivor tab is on screen")
          (is (= 5 (count (tab-nodes container)))
              "PRECONDITION: exactly the five Static tabs are on screen")
          (when (and (some? head) (some? survivor))
            ;; DOCUMENT_POSITION_FOLLOWING = 4. A removal from the tail is
            ;; invisible to this row's claim, so prove we are removing the head.
            (is (pos? (bit-and (.compareDocumentPosition head survivor) 4))
                "NON-VACUITY: the tab being removed really does PRECEDE the
                 survivor in document order, so this is a reorder and not a
                 tail truncation"))
          (panel-registry/unreg-l4-tab! :machines)
          (click! survivor "the survivor tab button")
          (-> (rf.test-support/poll-until
                (fn [] (= 4 (count (tab-nodes container))))
                {:label "the head tab left the committed DOM"})
              (.then
                (fn [_]
                  (is (nil? (tab-node container :machines))
                      "the removed tab is gone from the committed DOM")
                  (is (identical? survivor (tab-node container :routes))
                      "and the survivor is the IDENTICAL DOM node React
                       already had — which is only true if the key reached
                       React. With the key left in metadata the codec cannot
                       read, React reconciles by index and hands the survivor
                       the HEAD's node instead")))
              (.catch (fn [e]
                        (is false (str "W5 never settled: " (.-message e)
                                       " — DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (when head-entry
                         (panel-registry/reg-l4-tab! head-entry))
                       (teardown! root container)
                       (done)))))))))
