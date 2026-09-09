(ns day8.re-frame2-xray.panels.resources-fresco-boundary-dom-cljs-test
  "THE RESOURCES TAB RE-AUTHORED IN THE RE-FRAME-NATIVE VIEW LAYER, read off
  a real React commit (rf2-k97c.3, step 2).

  `resources/Panel` is now an `rf.fresco/defview` reading through Fresco's
  shipped collector rather than an `rf/reg-view` reading through whatever
  view build the installed substrate adapter supplies. This file is the
  behavioural evidence for that swap. Its first four rows are the merged
  `module_view_fresco_boundary_dom_cljs_test` template, claim for claim;
  the fifth is this panel's own and is described below.

  ## What the epic asked for, and which row answers it

    1 FIRST DISPLAY               — W1
    2 UPDATES ON A REAL CHANGE    — W2 (with the deaf control that makes
                                    the update mean liveness)
    4 FRAME TARGETING             — W1's second half, read off the frame's
                                    OWN sub-cache rather than off the DOM
    5 TOOL ACTIVITY NEVER
      MASQUERADING AS APPLICATION
      EVIDENCE                    — W3, in BOTH directions
    6 CLEAN TEARDOWN              — W4

  Criterion 3 (Xray's own interactions) has no row: this panel is
  READ-ONLY by contract (Spec 016 §Active owners and causes — opening it
  pins nothing, and it registers no `:rf.resource/*` event), so a click
  row would assert about a control the panel does not have. The
  `resources_cljs_test` suite already pins the read-only claim
  structurally, against the registrar.

  ## W5 IS THIS PANEL'S OWN ROW, AND IT IS THE ADVERSARIAL ONE

  The template's four rows all mount ONE subtree and watch it live or
  die. They cannot see the change this migration actually made to the
  panel's interior: all 24 of its `for`-row React keys moved out of
  vector METADATA and into each row's own ATTRIBUTE MAP, because
  metadata is a Reagent reading of `:key` that Fresco's codec does not
  share (`re-frame.fresco.impl.codec`'s head table). Nothing else in the
  tree tests that, and a lost key does not fail — it DEGRADES, silently,
  into index-based reconciliation, which renders correctly on first
  paint and corrupts row identity only once a list changes shape.

  W5 changes a list's shape and reads identity. It removes the FIRST of
  two sorted registry rows and asserts the SURVIVOR'S DOM node is the
  same node it was. Under real keys React deletes the first fiber and
  moves the second; under index keys it keeps fiber 0 and rewrites its
  content, so the survivor would arrive in the node that used to be the
  removed row's. Both spellings paint; only one preserves identity.

  ## The mount is the SHELL's mount, taken from the registry

  `shell/detail-panel` mounts the active tab as the hiccup head
  `[(:panel tab)]` inside the shell's `[rf/frame-provider {:frame …}]`.
  [[mount-panel!]] does exactly that, and it reaches `:panel` THROUGH
  `panel-registry/tab-by-id` rather than naming the var — so the bridge
  the registry actually holds is the thing under test.

  Nothing below ever calls the panel a second time. Every assertion after
  the mount reads `container.querySelector…` — the DOM React committed on
  its own.

  ## Substrate: the Reagent adapter, deliberately

  A ratom-family adapter, which is the family Xray already supports —
  because the claim being made is that the boundary is INDIFFERENT to it.
  `:ambient-frame nil` is load-bearing exactly as it is in the template:
  the fixture's default ambient `:rf/default` scope would otherwise
  SHADOW the React-context tier W1 is about, and the frame-targeting row
  would pass while measuring nothing.

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
            [re-frame.registrar :as rf.registrar]
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

(def ^:private tab-data-q
  "The panel's ONE read. Named once because four rows key off it — the
  sub-cache is keyed by the query vector itself (`re-frame.subs/cache-key`
  is identity), so this value IS the cache key."
  [:rf.xray/resources-tab-data])

;; ---- fixtures --------------------------------------------------------------
;;
;; Shaped after `resources_cljs_test`'s, trimmed to what these rows read. The
;; panel is fed through the `install-test-overrides!` seam rather than through
;; a live resources artefact, which Xray deliberately does not require.

(defn- resource-reg
  "One `:rf/resource` registry entry, minimal but complete enough for
  `resources-helpers/registry-row` to project a row from it."
  [rid]
  {rid {:doc "probe resource"
        :rf/resource {:doc            "probe resource"
                      :params-schema  [:map [:slug :string]]
                      :scope          :rf.scope/global
                      :transport      :rf.http/managed
                      :stale-after-ms 60000
                      :gc-after-ms    300000
                      :tags           (fn [_ _] #{})
                      :request        (fn [_ _] {})}}})

(def ^:private two-resources
  "Two registry entries. `project-registry` sorts by `(str :resource-id)`,
  so `:aaa/one` renders FIRST and `:zzz/two` second — which is what makes
  W5's removal a removal from the HEAD of the list, the only position at
  which keyed and index-based reconciliation disagree."
  (merge (resource-reg :aaa/one) (resource-reg :zzz/two)))

(def ^:private one-resource
  "W5's second state: the head row gone, the survivor untouched."
  (resource-reg :zzz/two))

(def ^:private deaf-route
  "W2's DEAF CONTROL, and its deafness is structural rather than lucky.
  `:rf.xray/resources-tab-data` reads the route registry INSIDE its own
  handler body — `(rf/registrations {:source :store :kind :route})` — and
  that read is NOT one of its declared `:inputs`. So registering a route
  moves what the composite WOULD compute while invalidating nothing it
  watches, which is precisely the shape W2 needs.

  Registration goes through the registrar directly because the routing
  artefact's `reg-route` macro is not on the Xray test classpath —
  the same route `resources_cljs_test/seed-live-route!` takes."
  :route/deaf)

(def ^:private probe-trace-op
  "One well-formed trace op, for W2's phase 3. `:rf.xray/trace-buffer` IS
  a declared input of the composite, so syncing a non-empty buffer over
  an empty one is a REAL invalidation — where the deaf control above is
  not one."
  {:id        7001
   :op-type   :rf.event
   :operation :rf.resource/scope-resolved
   :tags      {:resource-id   :probe/session
               :kind          :resource-scope
               :inputs        [:username]
               :input-values  {:username "probe"}
               :whole-db?     false
               :scope         [:rf.scope/session {:username "probe"}]
               :resolved-nil? false}})

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
  [:div {:data-testid "rf-xray-resources-probe-reg-view"} (str @(rf/subscribe [::n]))])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     ;; `:async? true` because W2, W4 and W5 are `async` rows, and
     ;; `cljs.test` refuses a FUNCTION fixture in any namespace that
     ;; carries one — "Async tests require fixtures to be specified as
     ;; maps. Testing aborted."
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
;; omission — the template records it and it cost that worker a red row. A
;; Fresco boundary is NOT in Reagent's render queue: its update is scheduled by
;; the collector through React, so draining Reagent's queue commits nothing of
;; this panel's and a row written that way reads a DOM that has not moved and
;; reports a LIVE panel as dead. Mount is committed with `flushSync` (React's
;; own door) and everything after it is polled.

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask. It exists
  for the CONTROL in W2: an absence asserted immediately after a change is
  a race, and an absence asserted after this is a decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- setup!
  "Register Xray's handlers (which is what registers
  `:rf.xray/resources-tab-data` and the L4 tab entry the mount reads),
  open the test-override seam, and make the two frames."
  []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- seed-registry!
  "Put `regs` in front of the panel through the test-override seam. A
  dispatch, so it IS a real invalidation of the composite."
  [regs]
  (rf/dispatch-sync [:rf.xray/set-registered-resources-override-for-test regs]
                    {:frame :rf/xray}))

(defn- mount-panel!
  "Mount the Resources tab the way `shell/detail-panel` mounts it: the
  registry's `:panel` value as a hiccup head, inside a `frame-provider`
  scoping `frame`. Committed synchronously — React 19's `root.render` is
  otherwise async and phase 1 would assert against an empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)
        tab       (panel-registry/tab-by-id :dynamic :resources)]
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

(defn- testid-sel [testid] (str "[data-testid=\"" testid "\"]"))

(defn- registry-row-sel
  "The DOM selector for one static-registry row. `registry-row-view`
  builds its testid by dropping the resource-id's leading colon."
  [rid]
  (testid-sel (str "rf-xray-resources-registry-row-" (subs (str rid) 1))))

(defn- cache-of
  "The frame's live sub-cache map. Not `some->`-guarded: a nil here means
  the frame is not live, which is a defect in the row's own setup and
  should throw rather than read as an empty cache."
  [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of
  "The sub-cache ref-count the frame holds for `query-v`, or 0 when the
  entry is absent. The spike measured the rejected design's binding by
  watching this number climb across renders and never fall on unmount
  (22 → 25 → 32), so it is the number the migration is answerable on."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

;; ===========================================================================
;; W1 — first display, and the read lands in the frame the tree named
;; ===========================================================================

(deftest w1-panel-paints-and-its-read-lands-in-the-named-frame
  (testing "rf2-k97c.3 — the migrated Resources panel commits real DOM through
            the registry entry the shell mounts, and its `rf.fresco/sub` read
            resolves against the frame the enclosing `frame-provider` named
            rather than the ambient one. Epic criteria 1 and 4."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            _ (seed-registry! two-resources)
            ;; A live read in the OTHER frame, so the negative half of the
            ;; targeting claim below is measured with an instrument that is
            ;; demonstrably able to see an entry in that frame's cache.
            _probe (rf/subscribe [::n] {:frame app-frame})
            {:keys [container root]} (mount-panel! :rf/xray)]
        (try
          (is (some? (q container (testid-sel "rf-xray-resources")))
              "the panel committed a real DOM root under React — a Fresco
               boundary mounted through Reagent's `:>` from the registry entry")
          (is (some? (q container (testid-sel "rf-xray-resources-registry")))
              "and the static-registry section rendered, so the body ran
               rather than short-circuiting to the silent caption")
          (is (some? (q container (registry-row-sel :aaa/one)))
              "with a real row from the seeded registry — the read reached
               the panel rather than the panel painting an empty shell")

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
  (testing "rf2-k97c.3 — the mounted panel re-renders itself and commits new DOM
            when its read's value really changes, and does NOT when nothing it
            watches moved. Epic criterion 2, with the control that makes the
            update mean liveness rather than a commit that simply had not
            happened yet."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (seed-registry! two-resources)
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              section (q container (testid-sel "rf-xray-resources"))
              route-q (testid-sel (str "rf-xray-resources-route-row-"
                                       (subs (str deaf-route) 1)))
              row?    (fn [] (some? (q container route-q)))]
          (is (not (row?))
              "NON-VACUITY: the route this row drives in is NOT on screen
               before it is registered")

          ;; ---- phase 2: the world moves, and the panel is deaf ------------
          ;; The composite reads the ROUTE registry inside its own handler
          ;; body rather than through a declared input, so registering a
          ;; route changes what it WOULD compute while invalidating nothing.
          (rf.registrar/register! :route deaf-route
                                  {:path      "/deaf"
                                   :resources [{:resource :aaa/one :blocking? true}]})
          (is (contains? (rf/with-frame :rf/xray
                           (rf/registrations {:source :store :kind :route}))
                         deaf-route)
              "PRECONDITION: the read's UNDERLYING data now carries the route
               — so a missing row below is the panel failing to re-render,
               and not the route failing to exist")
          (-> (settle)
              (.then
                (fn [_]
                  (is (not (row?))
                      "CONTROL: given a full settling window, the committed DOM
                       still does NOT carry the row. A panel that re-rendered
                       here would make phase 3 pass for a reason that is not
                       liveness")
                  ;; ---- phase 3: a declared input moves, the read re-runs ---
                  (rf/dispatch-sync [:rf.xray/sync-trace-buffer [probe-trace-op]]
                                    {:frame :rf/xray})
                  (rf.test-support/poll-until row?
                    {:label "the panel committed the new route's row"})))
              (.then
                (fn [_]
                  (is (row?)
                      (str "the panel re-rendered on a real invalidation of its "
                           "own read and committed the new route's row"))
                  (is (identical? section
                                  (q container (testid-sel "rf-xray-resources")))
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
            _        (seed-registry! two-resources)
            traces   (atom [])
            view-op? #(and (keyword? (:operation %))
                           (= "rf.view" (namespace (:operation %))))]
        (rf/register-listener! :trace ::collect (fn [ev] (swap! traces conj ev)))
        (try
          ;; ---- the subject: the panel, mounted in an APPLICATION frame ----
          (let [{:keys [container root]} (mount-panel! app-frame)
                subject-views (filterv view-op? @traces)]
            (try
              (is (some? (q container (testid-sel "rf-xray-resources")))
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
                (is (some? (q container
                              (testid-sel "rf-xray-resources-probe-reg-view")))
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
  (zero? (ref-count-of :rf/xray tab-data-q)))

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
            reuses the reaction instead of rebuilding it. A synchronous read
            straight after `flushSync(root.unmount)` returns 1 and the same
            read one macrotask later returns 0, so a synchronous assertion
            would report a LEAK against a collector behaving exactly as
            documented."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (seed-registry! two-resources)
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
;; W5 — ADVERSARIAL: the rows are really KEYED, not index-reconciled
;; ===========================================================================

(deftest w5-row-keys-survive-a-removal-from-the-head-of-the-list
  (testing "rf2-k97c.3 — removing the FIRST of two sorted registry rows leaves
            the survivor in the SAME DOM node. This is the row that can see the
            interior change this migration made: all 24 of the panel's `for`-row
            keys moved from vector METADATA into each row's own attribute map,
            because Fresco's codec reads only the literal `:key` in the attr map
            (`re-frame.fresco.impl.codec`'s head table) where Reagent also read
            the metadata form.

            A LOST KEY DOES NOT FAIL, IT DEGRADES — into index-based
            reconciliation, which paints identically and corrupts identity only
            once a list changes shape. So the discriminator is node identity
            across a change of shape, and the change is made at the HEAD of the
            list because that is the only position where the two spellings
            disagree: keyed, React deletes fiber 0 and MOVES fiber 1; indexed,
            it keeps fiber 0 and rewrites its content, delivering the survivor
            in the node that used to be the removed row's."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (seed-registry! two-resources)
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              head-sel     (registry-row-sel :aaa/one)
              survivor-sel (registry-row-sel :zzz/two)
              head-before  (q container head-sel)
              surv-before  (q container survivor-sel)]
          (is (some? head-before)  "PRECONDITION: the head row painted")
          (is (some? surv-before)  "PRECONDITION: the survivor row painted")
          (is (not (identical? head-before surv-before))
              "PRECONDITION: they are two distinct DOM nodes, so the identity
               assertion below can distinguish them at all")
          ;; Count on `data-resource-id`, which ONLY the row root carries —
          ;; a `data-testid^=` prefix match would also catch each row's own
          ;; `-id` / `-scope` / `-routes` child spans and read 6 for 2 rows.
          (is (= 2 (.-length (.querySelectorAll container "[data-resource-id]")))
              "PRECONDITION: exactly two rows, so the removal below really is a
               change of the list's SHAPE and not merely of its content")
          ;; `project-registry` sorts by `(str :resource-id)`, so `:aaa/one`
          ;; is DOM-first; assert that rather than trusting it, because the
          ;; whole discriminator rests on which row is removed.
          (is (pos? (bit-and (.compareDocumentPosition head-before surv-before)
                             (.-DOCUMENT_POSITION_FOLLOWING js/Node)))
              "PRECONDITION: the row being removed really is the one that comes
               FIRST — a removal from the TAIL would leave the survivor's node
               untouched under index keys too, and the row would be vacuous")

          ;; ---- the change of shape -----------------------------------------
          (seed-registry! one-resource)
          (-> (rf.test-support/poll-until
                (fn [] (nil? (q container head-sel)))
                {:label "the head row left the committed DOM"})
              (.then
                (fn [_]
                  (let [surv-after (q container survivor-sel)]
                    (is (nil? (q container head-sel))
                        "the removed resource's row is gone from the DOM")
                    (is (some? surv-after)
                        "and the survivor is still on screen")
                    (is (identical? surv-before surv-after)
                        "THE CLAIM: the survivor is the SAME DOM node it was.
                         React matched it by KEY and moved its fiber. Under
                         index-based reconciliation it would have arrived in
                         the node that used to hold the removed row, and this
                         assertion is the only thing in the suite that can
                         tell those two apart."))))
              (.catch (fn [e]
                        (is false (str "W5 never settled: " (.-message e)
                                       " — DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (teardown! root container)
                       (done)))))))))
