(ns day8.re-frame2-xray.static.routes.panel-fresco-boundary-dom-cljs-test
  "The Static Routes tab re-authored in the re-frame-native view layer,
  read off a real React commit (rf2-k97c.3).

  `static.routes.panel/Panel` is now an `rf.fresco/defview` reading
  through Fresco's shipped collector rather than an `rf/reg-view`
  reading through whatever view build the installed substrate adapter
  supplies. PR #9648 made that swap and shipped no DOM witness for it —
  the only one of the five Static panels without one. This file closes
  that asymmetry.

  ## What the epic asked for, and which row answers it

    1 FIRST DISPLAY               — W1
    2 UPDATES ON A REAL CHANGE    — W2 (with the deaf control that makes
                                    the update mean liveness), over BOTH
                                    route state and simulation state
    3 XRAY'S OWN INTERACTIONS     — W5
    4 FRAME TARGETING             — W1's second half, read off the frame's
                                    OWN sub-cache rather than off the DOM
    5 TOOL ACTIVITY NEVER
      MASQUERADING AS APPLICATION
      EVIDENCE                    — W3, in BOTH directions
    6 CLEAN TEARDOWN              — W4

  Unlike its four Static siblings this panel HAS criterion-3 affordances
  — the row toggle, the Simulate-navigation toggle and the jump chip —
  and they are reached through the Reagent island below, which is why
  W5 is worth more here than a click row was on a pure-browse tab.

  ## THE FOUR-POINT STANDARD (rf2-0lcg)

  The merged-PR audit of #9648 states the bar for this witness as four
  points rather than one, and they map onto the rows above: render under
  an EXPLICIT FRAME (W1), change real route / simulation state and see
  the mounted output UPDATE (W2), an interaction DISPATCH reaching that
  frame (W5), and UNMOUNT with boundary subscriptions returning to
  BASELINE (W4). The last is the bead's own acceptance clause — `the
  teardown check (no retained watches or listeners after unmount)
  actually measured rather than assumed` — and W4 reads it off the
  collector's own tables through the Fresco test kit's runtime door
  rather than inferring it from a ref-count alone.

  IT IS A VERIFICATION GAP BEING CLOSED, NOT AN ALLEGATION OF A LEAK.
  The numbers come back at baseline; saying so with the measurement is
  the deliverable.

  ## THE ISLAND IS THE WHOLE POINT, AND THE NODE LANE CANNOT SEE IT

  `panel-tree` hands the browse list and the Simulate-URL header to
  React through an `as-child` seam, because both bottom out in PLAIN FNS
  IN HICCUP HEAD POSITION (`[search-box/search-box …]`,
  `[route-row …]`, `[row-expand/render …]`, `[candidate-row …]`, …)
  which Fresco's codec grades `:invalid`. The boundary passes
  `reagent.core/as-element`; the node lane passes `identity`.

  SO WRAPPING AND NOT WRAPPING ARE THE SAME VALUE UNDER
  `npm run test:cljs`, BY CONSTRUCTION. The worker that migrated this
  panel planted the naive migration — no `as-child` — and measured it:
  the node lane exited 0 at IDENTICAL totals while the browser gate
  failed with `expected Static sub-tab :routes real panel root
  rf-xray-static-routes mounted … (last=null)`, i.e. a tab that renders
  nothing at all. This file is the cheap standing witness for that seam;
  it runs under the `:browser-test` build, where `as-child` is real.

  Every row below therefore asserts on nodes that exist ONLY if the
  island crossed — the search box, the list, a row — and not merely on
  the `<section>` the boundary itself emits.

  ## The mount is the SHELL's mount, taken from the registry

  `static/shell.cljs`'s `detail-panel` mounts the active tab as the
  hiccup head `[(:panel tab)]` inside the shell's
  `[rf/frame-provider {:frame …}]`. [[mount-panel!]] does exactly that,
  and it reaches `:panel` THROUGH `panel-registry/tab-by-id :static`
  rather than naming the var — so the bridge the registry actually holds
  (`Panel-bridge`, which is private) is the thing under test. A bridge
  that regressed to something the shell cannot mount reddens here rather
  than in a browser.

  Nothing below ever calls the panel a second time. Every assertion
  after the mount reads `container.querySelector…` — the DOM React
  committed on its own.

  ## Substrate: the Reagent adapter, deliberately

  A ratom-family adapter, which is the family Xray already supports —
  because the claim being made is that the boundary is INDIFFERENT to
  it. It is also the adapter the island genuinely needs:
  `reagent.core/as-element` renders its subtree under Reagent.

  `:ambient-frame nil` is load-bearing exactly as it is in the four
  sibling suites: the fixture's default ambient scope is still in effect
  during a synchronous `flushSync`, and tier 1 of the frame resolver is
  the dynamic var, so an ambient frame would SHADOW the React-context
  tier W1's frame-targeting row is about and that row would pass while
  measuring nothing.

  ## W3's ZERO IS HONEST HERE, AND THAT WAS MEASURED RATHER THAN ASSUMED

  `static/machines`' W3 had to pin its list empty, because ITS island
  contains `machine-canvas/Chart`, an `rf/reg-view`, and a reg-view
  emits view trace wherever it renders. This island does not: the four
  `static/routes/*.cljs` files and the shared search box register no
  view at all (measured — zero `rf/reg-view` and zero `rf.fresco/
  defview` forms across them), so the whole rendered subtree is
  trace-silent and W3 can assert its zero over a FULLY PAINTED panel,
  island included. The one reg-view reachable from this subtree is
  `edn-inspector`, behind `row_expand`'s schema blocks, and no row here
  seeds schema meta.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium) — `npm run test:browser`. The
  `:node-test` build's `cljs-test$` regex also matches, so it LOADS
  under Node — where every row short-circuits through [[browser?]] and
  reports the skip rather than passing silently."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private app-frame
  "An ORDINARY application frame — the thing Xray inspects. Three rows
  need one that is not `:rf/xray`: W1 reads it as the negative half of
  the frame-targeting claim, W3 mounts INSIDE it so the evidence claim
  cannot be carried by `:rf/xray`'s trace-disabled gate, and W5 reads it
  as the cross-frame control on the dispatch."
  ::app)

(def ^:private tab-data-q
  "The panel's headline read. Named once because four rows key off it —
  the sub-cache is keyed by the query vector itself, so this value IS
  the cache key."
  [:rf.xray.static.routes/tab-data])

(def ^:private expanded-q
  "The per-row expand set. W5's lever, and the read that makes the click
  round-trip observable in the committed DOM."
  [:rf.xray.static.routes/expanded])

(def ^:private panel-reads
  "EVERY query the boundary reads, in the order `Panel`'s body reads
  them. W4's teardown claim is over ALL FOUR rather than over the
  headline one: a release that freed three cells and retained the fourth
  would answer clean to a single-query probe, which is exactly the shape
  of leak the acceptance criterion is about."
  [[:rf.xray.static.routes/tab-data]
   [:rf.xray.static.routes/expanded]
   [:rf.xray.static.routes/sim-nav-open]
   [:rf.xray/registered-routes]])

(def ^:private base-routes
  "The route table the panel opens on. Two routes so the catalogue is
  non-empty at mount — the island's own plain-fn heads only run on the
  non-silent arm — and so W2's claim can be RELATIVE (a third route
  arriving) rather than absolute. No `:params` / `:query` schema meta:
  those are what would drag `edn-inspector`, a reg-view, into the
  subtree and put a `:rf.view/*` op in W3's stream."
  {:route/cart     {:path "/cart"     :doc "cart"}
   :route/checkout {:path "/checkout" :doc "checkout"}})

(def ^:private late-route
  "The route W2 makes arrive. Its row is the DOM movement the liveness
  claim is about."
  :route/receipt)

(def ^:private late-routes
  (assoc base-routes late-route {:path "/receipt" :doc "receipt"}))

(def ^:private clicked-route
  "The row W5 presses. `/cart` sorts first, so it is the head row and the
  one `row-nodes` finds without ordering assumptions."
  :route/cart)

;; ---- probe registrations --------------------------------------------------

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
     ;; `:async? true` because W2, W4 and W5 are `async` rows, and
     ;; `cljs.test` refuses a FUNCTION fixture in any namespace carrying
     ;; one. The flag is what makes `make-reset-runtime-fixture` hand
     ;; back the `{:before :after}` map form.
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about;
                      ;; a neighbour's boundary left in the entry cache
                      ;; would make W4's release row read a residue that
                      ;; is not this panel's.
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
  real chance to commit — two animation frames and a macrotask. It
  exists for the CONTROL in W2: an absence asserted immediately after
  the world moves is a race, and an absence asserted after this is a
  decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- setup!
  "Register Xray's handlers — which is what registers the static-routes
  sub family and the `:static` L4 tab entry the mount reads — open the
  test-only override seam the route table is driven through, and make
  the two frames.

  The route override lives on the DYNAMIC Routing panel
  (`panels/routing/install-test-overrides!`, reached through the
  orchestrator below) because `:rf.xray/registered-routes` is its
  registration; this panel reads it as a shared input."
  []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- set-routes!
  "Drive the panel's route table through the test-override seam. A real
  app-db write into `:rf/xray`, so it invalidates
  `:rf.xray/registered-routes-override` and the composite above it —
  which is what makes W2's phase 3 a REAL dependency change rather than
  a poke at the view."
  [routes]
  (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test routes]
                    {:frame :rf/xray}))

(defn- mount-panel!
  "Mount the Static Routes tab the way `static/shell.cljs`'s
  `detail-panel` mounts it: the registry's `:panel` value as a hiccup
  head, inside a `frame-provider` scoping `frame`. Committed
  synchronously — React 19's `root.render` is otherwise async and phase
  1 would assert against an empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)
        tab       (panel-registry/tab-by-id :static :routes)]
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

(defn- testid [container id]
  (q container (str "[data-testid=\"" id "\"]")))

(defn- route-suffix
  "The `data-testid` suffix the routes subtree derives from a route-id —
  `(subs (pr-str id) 1)`, i.e. the keyword without its leading colon."
  [route-id]
  (subs (pr-str route-id) 1))

(defn- row-node
  "The committed `<li>` for one route-id, or nil."
  [container route-id]
  (testid container (str "rf-xray-static-routes-row-" (route-suffix route-id))))

(defn- expand-node
  "The committed inline expand surface for one route-id, or nil. It
  exists only if `row-expand/render` — a plain fn in hiccup head
  position, two levels inside the island — rendered."
  [container route-id]
  (testid container (str "rf-xray-static-routes-expand-" (route-suffix route-id))))

(defn- cache-of
  "The frame's live sub-cache map. Not `some->`-guarded: a nil here means
  the frame is not live, which is a defect in the row's own setup and
  should throw rather than read as an empty cache."
  [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of
  "The sub-cache ref-count the frame holds for `query-v`, or 0 when the
  entry is absent. The spike measured the rejected design's binding by
  watching this number climb across renders and never fall on unmount,
  so it is the number the migration is answerable on."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

;; ===========================================================================
;; W1 — first display, the island crossed, and the read lands in the frame
;;      the tree named
;; ===========================================================================

(deftest w1-panel-paints-through-the-island-and-reads-the-named-frame
  (testing "rf2-k97c.3 — the migrated Static Routes panel commits real DOM
            through the registry entry the Static shell mounts, its two
            Reagent islands really cross, and its `rf.fresco/sub` reads
            resolve against the frame the enclosing `frame-provider` named
            rather than the ambient one. Epic criteria 1 and 4."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            _ (set-routes! base-routes)
            ;; A live read in the OTHER frame, so the negative half of the
            ;; targeting claim below is measured with an instrument that is
            ;; demonstrably able to see an entry in that frame's cache.
            _probe (rf/subscribe [::n] {:frame app-frame})
            {:keys [container root]} (mount-panel! :rf/xray)]
        (try
          (is (some? (testid container "rf-xray-static-routes"))
              "the panel committed a real DOM root under React — a Fresco
               boundary mounted through Reagent's `:>` from the registry entry")

          ;; ---- the seam: both islands, and a plain-fn head inside one -----
          (is (some? (testid container "rf-xray-static-routes-sim"))
              "the Simulate-URL header crossed as an island — `panel-tree`
               hands `(simulate-url/header …)`'s hiccup through `as-child`,
               and that hiccup contains `[candidate-row …]`")
          (is (some? (testid container "rf-xray-static-routes-search"))
              "and the browse-list island crossed too: this node is emitted
               by `[search-box/search-box …]`, a PLAIN FN IN HICCUP HEAD
               POSITION inside the island. Fresco's codec grades that head
               `:invalid` and `vec->element` raises
               `:rf.error/fresco-bad-head`, so the node exists only because
               Reagent rendered the subtree")
          (is (some? (testid container "rf-xray-static-routes-list"))
              "the catalogue list rendered, so the non-silent arm is the arm
               that ran")
          (is (some? (row-node container :route/cart))
              "and a real route row is on screen, so the read reached the
               override seam's data and not an empty catalogue")
          (is (nil? (testid container "rf-xray-static-routes-empty"))
              "NON-VACUITY: the silent empty-state is ABSENT, so the
               assertions above are about the painted catalogue rather than
               about a panel that short-circuited")

          ;; ---- criterion 4: the read is where the tree said it would be ----
          (is (pos? (ref-count-of :rf/xray tab-data-q))
              (str "the panel's read holds a reference in :rf/xray's sub-cache "
                   "— the frame the enclosing frame-provider named. Cache keys: "
                   (pr-str (keys (cache-of :rf/xray)))))
          (is (zero? (ref-count-of app-frame tab-data-q))
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
  (testing "rf2-k97c.3 — the mounted panel re-renders itself and commits new
            DOM when its read's value really changes, and does NOT when
            nothing it watches moved. Epic criterion 2, with the control that
            makes the update mean liveness rather than a commit that simply
            had not happened yet."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (set-routes! base-routes)
        ;; The claim is RELATIVE — this route arriving — rather than absolute
        ;; ("the catalogue is empty"). The `:browser-test` build runs every
        ;; `-dom-cljs-test` namespace in ONE page over process-global
        ;; registries, so an absolute claim about what is on screen at mount
        ;; is a claim about whatever the neighbours left.
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              section (testid container "rf-xray-static-routes")
              row?    (fn [] (some? (row-node container late-route)))]
          (is (some? section)
              "PRECONDITION: the panel is on screen, so the absence below is
               an absence in a rendered catalogue and not an unrendered panel")
          (is (not (row?))
              "NON-VACUITY: the route this row drives in is NOT on screen
               before it is registered")

          ;; ---- phase 2: the world moves, and the panel is deaf ------------
          ;; A real app-db write into the SAME frame the panel reads, under a
          ;; key no input of the four reads computes over. Every db-derived
          ;; sub in the composite recomputes and answers the value it answered
          ;; before, so nothing the boundary watches is invalidated. A binding
          ;; that repainted on db MOVEMENT rather than on VALUE CHANGE — which
          ;; is the failure mode a hand-rolled interop binding has — fires here.
          (rf/dispatch-sync [::bump 1] {:frame :rf/xray})
          (-> (settle)
              (.then
                (fn [_]
                  (is (not (row?))
                      "CONTROL: given a full settling window, the committed DOM
                       still does NOT carry the row. A panel that re-rendered
                       its way to a new catalogue here would make phase 3 pass
                       for a reason that is not liveness")
                  (is (identical? section (testid container "rf-xray-static-routes"))
                      "and the panel was not remounted by the deaf write
                       either — same <section> node")
                  ;; ---- phase 3: the read's real input moves --------------
                  (set-routes! late-routes)
                  (is (contains? (set (keys late-routes)) late-route)
                      "PRECONDITION: the read's UNDERLYING data now carries the
                       new route — so a missing row below is the panel failing
                       to re-render, and not the route failing to exist")
                  (rf.test-support/poll-until row?
                    {:label "the panel committed the new route's row"})))
              (.then
                (fn [_]
                  (is (row?)
                      "the panel re-rendered on a real invalidation of its own
                       read and committed the new route's row — through the
                       island, which had to be re-crossed for the row to exist")
                  (is (identical? section (testid container "rf-xray-static-routes"))
                      "and it is the SAME <section> node: React reconciled the
                       live tree in place, so the row did not arrive by the
                       panel being remounted from scratch, which would not be
                       liveness")
                  ;; ---- phase 4: SIMULATION state, the panel's other input --
                  ;; Route state and simulation state are two different reads
                  ;; reaching two different islands, and only the first has
                  ;; moved so far. `sim-url` feeds the composite the boundary
                  ;; reads, and its output paints in the Simulate-URL header
                  ;; island rather than in the browse list.
                  (is (nil? (testid container "rf-xray-static-routes-sim-result"))
                      "NON-VACUITY: no Simulate-URL result is on screen while
                       the input is blank")
                  (rf/dispatch-sync [:rf.xray.static.routes/set-sim-url "/cart"]
                                    {:frame :rf/xray})
                  (rf.test-support/poll-until
                    #(some? (testid container "rf-xray-static-routes-sim-result"))
                    {:label "the panel committed the Simulate-URL result"})))
              (.then
                (fn [_]
                  (is (some? (testid container "rf-xray-static-routes-sim-result"))
                      "simulation state moved and the mounted output followed —
                       the OTHER island repainted, so liveness is not a property
                       of the browse list alone")
                  (is (some? (testid container
                                     (str "rf-xray-static-routes-sim-candidate-"
                                          (route-suffix :route/cart))))
                      "and `[candidate-row …]`, a plain fn in hiccup head
                       position inside the Simulate-URL island, rendered the
                       matching candidate — the seam crossed on a repaint and
                       not only at first mount")))
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
            same frame and the same commit.

            The zero is asserted over a FULLY PAINTED panel, island included
            — see the ns docstring on why this subtree, unlike
            `static/machines`', is trace-silent all the way down."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_        (setup!)
            _        (set-routes! base-routes)
            traces   (atom [])
            view-op? #(and (keyword? (:operation %))
                           (= "rf.view" (namespace (:operation %))))]
        (rf/register-listener! :trace ::collect (fn [ev] (swap! traces conj ev)))
        (try
          ;; ---- the subject: the panel, mounted in an APPLICATION frame ----
          (let [{:keys [container root]} (mount-panel! app-frame)
                subject-views (filterv view-op? @traces)]
            (try
              (is (some? (testid container "rf-xray-static-routes"))
                  "precondition: the panel really did render in this commit —
                   an empty container would make the zero below vacuous")
              (is (some? (testid container "rf-xray-static-routes-search"))
                  "precondition: and the ISLAND rendered in it too, so the
                   zero is about the whole painted subtree rather than about
                   a boundary that emitted a bare section")
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
                (is (some? (testid container "rf-xray-probe-reg-view"))
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

(defn- sub-key
  "The collector's own key for one of this panel's reads, under the frame
  the tree names. `[frame-kw query-v]` — finer than the frame's sub-cache
  key, and the address `!cells` is keyed by."
  [query-v]
  [:rf/xray query-v])

(defn- ref-counts
  "The frame's sub-cache ref-count for each of the four reads."
  []
  (mapv #(ref-count-of :rf/xray %) panel-reads))

(defn- live-reactions
  "The reads whose collector CELL still holds a live reaction. A cell
  holds an `add-watch` on that reaction for as long as it lives, so this
  is the `no retained watches` half of the acceptance criterion read
  directly off the runtime rather than inferred from a ref-count."
  []
  (filterv #(some? (rf.fresco.test.runtime/cell-reaction (sub-key %))) panel-reads))

(defn- reader-edges
  "Total reader slots the four cells hold. One slot per boundary
  registration reading that cell — the fused reference AND dependency
  edge — so this is the `no retained listeners` half."
  []
  (reduce + 0 (map #(count (rf.fresco.test.runtime/cell-readers (sub-key %)))
                   panel-reads)))

(defn- residue
  "Everything that must be zero once the panel is gone, as one map, so a
  failure message names WHICH of the three instruments still sees
  something."
  []
  {:ref-counts   (ref-counts)
   :live-cells   (live-reactions)
   :reader-edges (reader-edges)})

(defn- released? []
  (and (every? zero? (ref-counts))
       (empty? (live-reactions))
       (zero? (reader-edges))))

(deftest w4-unmount-returns-every-boundary-subscription-to-baseline
  (testing "rf2-k97c.3 — unmounting the panel releases ALL FOUR of its
            subscriptions completely — no sub-cache reference, no live
            reaction, no reader edge — and mounting it again returns to the
            SAME numbers rather than higher ones. Epic criterion 6 and the
            bead's own acceptance clause, `no retained watches or listeners
            after unmount`, measured rather than assumed.

            THREE INSTRUMENTS, NOT ONE, and they answer different questions.
            The frame's sub-cache ref-count is the number the spike caught
            the rejected design on: with a four-call interop binding the
            `:rf/xray` count climbed across renders and never fell on
            unmount. `cell-reaction` is the WATCH — a live collector cell
            holds `add-watch` on its reaction for as long as it exists — and
            `cell-readers` is the LISTENER edge, one slot per boundary
            reading that cell. A release that dropped the reference and left
            the cell wired answers clean to the first and dirty to the other
            two, which is why all three are read.

            AND ALL FOUR READS, NOT THE HEADLINE ONE. A teardown that freed
            three cells and retained the fourth is exactly the leak this
            clause is about, and a single-query probe cannot see it.

            THE RELEASE IS ASYNCHRONOUS BY DESIGN, so the settling point is
            the kit's own `quiesced!` rather than a bare macrotask: the
            collector gives a cell whose last reader unmounts one macrotask
            of grace (so a keyed reorder that unmounts and remounts within a
            turn reuses the reaction), and the entry reaper's horizon sits
            deliberately outside a `setTimeout 0`. A residue read before
            that point reports a LEAK against a runtime behaving exactly as
            documented.

            THIS IS A VERIFICATION GAP BEING CLOSED, NOT AN ALLEGATION. If
            the numbers come back at baseline — and they do — that IS the
            deliverable."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (set-routes! base-routes)
        ;; The BASELINE is taken after the runtime settles, not at the top of
        ;; the row: a neighbouring suite's teardown grace may still be in
        ;; flight when this one begins, and a baseline read early is a
        ;; baseline that is not zero for reasons that are not this panel's.
        (-> (rf.fresco.test.runtime/quiesced!)
            (.then
              (fn [_]
                (is (released?)
                    (str "BASELINE: nothing retained for this panel's reads "
                         "before the first mount. " (pr-str (residue))))
                (let [{:keys [container root]} (mount-panel! :rf/xray)
                      mounted-refs  (ref-counts)
                      mounted-cells (live-reactions)
                      mounted-edges (reader-edges)]
                  (is (every? pos? mounted-refs)
                      (str "NON-VACUITY: the mount took a reference for EVERY "
                           "one of the four reads — otherwise the release "
                           "below is a claim about nothing. Got: "
                           (pr-str mounted-refs)))
                  (is (= (count panel-reads) (count mounted-cells))
                      (str "NON-VACUITY: and every read has a live collector "
                           "cell, so there are four watches to lose. Got: "
                           (pr-str mounted-cells)))
                  (is (pos? mounted-edges)
                      (str "NON-VACUITY: and the cells carry reader edges, so "
                           "there are listeners to lose. Got: " mounted-edges))
                  (teardown! root container)
                  (-> (rf.fresco.test.runtime/quiesced!)
                      (.then
                        (fn [_]
                          (is (every? zero? (ref-counts))
                              (str "the unmount released every sub-cache "
                                   "reference COMPLETELY. Residue: "
                                   (pr-str (residue))
                                   " — :rf/xray cache keys: "
                                   (pr-str (keys (cache-of :rf/xray)))))
                          (is (empty? (live-reactions))
                              (str "NO RETAINED WATCHES: every collector cell "
                                   "for this panel's reads is gone, so no "
                                   "`add-watch` on a derived reaction survives "
                                   "the unmount. Residue: " (pr-str (residue))))
                          (is (zero? (reader-edges))
                              (str "NO RETAINED LISTENERS: and no reader edge "
                                   "survives either. Residue: "
                                   (pr-str (residue))))
                          ;; ---- reopen: the same numbers, not higher ---------
                          (let [{c2 :container r2 :root} (mount-panel! :rf/xray)
                                remounted-refs  (ref-counts)
                                remounted-edges (reader-edges)]
                            (is (= mounted-refs remounted-refs)
                                (str "reopening returns to the SAME reference "
                                     "counts (" (pr-str mounted-refs) ") rather "
                                     "than accumulating — accumulation across "
                                     "open/close cycles is the signature of a "
                                     "release the substrate's own reaction "
                                     "lifecycle cannot see. Got: "
                                     (pr-str remounted-refs)))
                            (is (= mounted-edges remounted-edges)
                                (str "and the same reader-edge count ("
                                     mounted-edges ") — an edge left behind by "
                                     "the first teardown would show here as "
                                     "growth. Got: " remounted-edges))
                            (teardown! r2 c2)
                            (rf.fresco.test.runtime/quiesced!))))))))
            (.then (fn [_]
                     (is (released?)
                         (str "and the second unmount returns to baseline too. "
                              (pr-str (residue))))))
            (.catch (fn [e] (is false (str "W4 never settled: " (.-message e)))
                      nil))
            (.then (fn [_] (done))))))))

;; ===========================================================================
;; W5 — CRITERION 3: a real click INSIDE THE ISLAND dispatches into the frame
;;      the tree named, and the DOM follows two levels deeper
;; ===========================================================================
;;
;; This is the row the four sibling Static suites could not carry — they are
;; pure-browse catalogues. It is also the row that exercises the frame-bound
;; dispatcher `(:dispatch (rf/capture-frame))` the boundary threads INTO the
;; island: the handler that fires is a plain Reagent `:on-click` several fn
;; heads deep, with no `reg-view` ancestor to recover a frame from, so if the
;; threading were lost the dispatch would land somewhere else — or nowhere.

(deftest w5-a-click-inside-the-island-dispatches-into-the-named-frame
  (testing "rf2-k97c.3 — clicking a catalogue row inside the migrated panel's
            Reagent island dispatches `:rf.xray.static.routes/toggle-row`, the
            inline expand surface commits, and the event lands in the frame the
            enclosing `frame-provider` NAMED rather than in the ambient one or
            in a neighbouring application frame. Epic criterion 3."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (set-routes! base-routes)
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              row     (row-node container clicked-route)
              ;; The row's clickable body carries the toggle handler; the
              ;; `<li>` itself does not.
              button  (when row (.querySelector row "[role=\"button\"]"))]
          (is (some? row)
              "PRECONDITION: the row rendered, so the island crossed and there
               is a real control to press")
          (is (some? button)
              "PRECONDITION: and its role=button body rendered — that is the
               node carrying `on-toggle`")
          (is (nil? (expand-node container clicked-route))
              "PRECONDITION: the inline expand surface is CLOSED, so its
               arrival below is the click's doing")
          (is (empty? @(rf/subscribe expanded-q {:frame :rf/xray}))
              "PRECONDITION: and the expand set is empty in :rf/xray")
          (is (empty? @(rf/subscribe expanded-q {:frame app-frame}))
              "PRECONDITION: and empty in the application frame, so the
               cross-frame control below starts from a real zero")

          ;; ---- the act: a REAL browser click on a REAL node ---------------
          (when button (.click button))

          (-> (rf.test-support/poll-until
                #(some? (expand-node container clicked-route))
                {:label "the panel committed the inline expand surface"})
              (.then
                (fn [_]
                  (is (some? (expand-node container clicked-route))
                      "the click reached the handler, the frame-bound dispatcher
                       the boundary threaded into the island delivered the
                       event, the read was invalidated and the boundary
                       recommitted with the expand surface open — the whole
                       round trip, in a browser")
                  (is (some? (testid container
                                     (str "rf-xray-static-routes-sim-nav-toggle-"
                                          (route-suffix clicked-route))))
                      "and `[sim-nav-toggle …]`, a plain fn head TWO levels
                       inside the island, rendered with it — so the island is a
                       whole Reagent subtree rather than one converted node")
                  (is (contains? @(rf/subscribe expanded-q {:frame :rf/xray})
                                 clicked-route)
                      "the route is in the expand set of :rf/xray — the frame
                       the enclosing frame-provider named")
                  (is (empty? @(rf/subscribe expanded-q {:frame app-frame}))
                      "CROSS-FRAME CONTROL: and NOT in the application frame's.
                       A handler that had lost its captured frame and fallen
                       back to an ambient or default one would write here")))
              (.catch (fn [e]
                        (is false (str "W5 never settled: " (.-message e)
                                       " — DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (rf/unsubscribe expanded-q {:frame :rf/xray})
                       (rf/unsubscribe expanded-q {:frame app-frame})
                       (teardown! root container)
                       (done)))))))))
