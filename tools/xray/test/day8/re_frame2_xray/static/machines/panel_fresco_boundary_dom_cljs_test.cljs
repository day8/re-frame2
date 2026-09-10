(ns day8.re-frame2-xray.static.machines.panel-fresco-boundary-dom-cljs-test
  "The Static Machines sub-tab re-authored in the re-frame-native view
  layer, read off a real React commit (rf2-k97c.3).

  `static.machines.panel/panel`, `…browse-list/browse-list` and
  `…definition-detail/detail` are now `rf.fresco/defview`s reading
  through Fresco's shipped collector rather than `rf/reg-view`s reading
  through whatever view build the installed substrate adapter supplies.
  This file is the behavioural evidence for that swap.

  The rows are the template `static/flows/panel_fresco_boundary_dom
  _cljs_test` established, adapted to this sub-tab's THREE boundaries,
  its own dependency lever, its own key site — and one row no earlier
  slice needed.

  ## What the epic asked for, and which row answers it

    1 FIRST DISPLAY               — W1, and it is a THREE-boundary claim:
                                    the panel's chrome, the left pane and
                                    the right pane each commit their own
                                    DOM, so the two nested boundary heads
                                    really did mount
    2 UPDATES ON A REAL CHANGE    — W2 for the LEFT pane (with the deaf
                                    control that makes the update mean
                                    liveness) and W2b for the RIGHT, off a
                                    read only that pane performs
    4 FRAME TARGETING             — W1's second half, read off the frame's
                                    OWN sub-cache rather than off the DOM
    5 TOOL ACTIVITY NEVER
      MASQUERADING AS APPLICATION
      EVIDENCE                    — W3, in BOTH directions, and SCOPED to
                                    the three boundaries' own renders — see
                                    the last paragraph below
    6 CLEAN TEARDOWN              — W4

  Criterion 3 (Xray's own interactions) has no row of its own here: the
  affordances this sub-tab owns — row select, sort cycle, search
  keystroke, the per-row JUMP, Copy Mermaid — all reach the frame
  through the ONE dispatcher `(:dispatch (rf/capture-frame))` hands the
  tree, and W2's phase 3 fires one of them (`cycle-sort`) through the
  live frame to move the panel. A per-control row would re-assert the
  same dispatcher five times.

  ## W5 — the row key, and why the PIP key gets no row like it

  The migration moved React keys out of Clojure metadata, which Fresco's
  codec reads NOWHERE, at TWO sites in `browse_list.cljs`: the per-row
  seq and each row's live-instance pip seq. Only the FIRST is witnessable
  by a reorder, and the reason generalises (it is #9622's finding, met
  again here): the pip key expression is `i`, POSITIONAL, so removing a
  pip shifts the survivor's key and React correctly reuses the removed
  node — a positional key and no key at all are indistinguishable under
  exactly the operation a reorder-identity row performs. The row key is
  `(str machine-id)`, DOMAIN-shaped, so W5 works for it. The pip site's
  evidence stays in the node lane, where the claim (the key is present IN
  THE ATTRIBUTE MAP) is real — `browse_list_cljs_test`'s
  `row-and-pip-keys-ride-the-attribute-map-not-metadata`.

  ## W6 — the REAGENT ISLAND, and no earlier slice has this row

  This is the first migrated panel whose interior must reach code that
  CANNOT be a Fresco head: `topology/body` bottoms out in
  `machine-canvas/Chart`, an `rf/reg-view`, which grades `:invalid` in
  hiccup head position exactly as a plain `defn` does. `detail-tree`
  therefore crosses it as a React ELEMENT through `reagent.core/
  as-element` — Fresco's documented `:as-child` door, the same one
  `panels/machine_after_rings.cljs` uses. W6 is that crossing measured:
  three nodes that exist ONLY if Reagent rendered fn heads inside the
  boundary's subtree, plus the canvas host, which exists only if the
  `reg-view` inside that island rendered too.

  ## The mount is the SHELL's mount, taken from the registry

  `static/shell.cljs`'s `detail-panel` mounts the active tab as the
  hiccup head `[(:panel tab)]` inside the shell's
  `[rf/frame-provider {:frame …}]`. [[mount-panel!]] does exactly that,
  and it reaches `:panel` THROUGH `panel-registry/tab-by-id :static`
  rather than naming the var — so the bridge the registry actually holds
  is the thing under test. A bridge that regressed to something the
  shell cannot mount reddens here rather than in a browser.

  Nothing below ever calls a panel view a second time. Every assertion
  after the mount reads `container.querySelector…` — the DOM React
  committed on its own.

  ## Substrate: the Reagent adapter, deliberately

  A ratom-family adapter, which is the family Xray already supports —
  because the claim being made is that the boundaries are INDIFFERENT to
  it. It is also the adapter W6's island genuinely needs.
  `:ambient-frame nil` is load-bearing exactly as it is in the template:
  the fixture's default ambient scope is still in effect during a
  synchronous `flushSync`, and tier 1 of the frame resolver is the
  dynamic var, so an ambient frame would SHADOW the React-context tier
  W1's frame-targeting row is about and that row would pass while
  measuring nothing.

  ## W3's ZERO IS SCOPED, AND THE SCOPE WAS MEASURED RATHER THAN CHOSEN

  Criterion 5 is a claim about the BOUNDARIES: a Fresco boundary is not a
  substrate view render, so it emits no `:rf.view/*` op. That holds, and
  W3 shows it. What does NOT hold for this sub-tab, and would be a false
  claim if W3 were written without a fixture, is that the whole rendered
  SUB-TREE is trace-silent: the Reagent island W6 measures contains
  `machine-canvas/Chart`, an `rf/reg-view`, and a reg-view emits view
  trace wherever it renders. Written against the page's ambient machine
  list, W3's first run read `[:rf.view/render :rf.view/rendered]` — the
  island, faithfully doing what a Reagent tree does.

  So W3 pins the machine list EMPTY, which means no machine is selected,
  which means no Topology body and therefore no island; and it ASSERTS
  that absence rather than assuming it, so the zero cannot quietly become
  a zero about a tree that was not there for a different reason. The
  island's trace emission is a KNOWN CONSEQUENCE of the migration
  scaffolding, not a regression, and it goes when `Chart` is itself a
  Fresco body — the same commit that deletes the `as-child` seam.

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
            [re-frame.machines]
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

(def ^:private data-q
  "The read BOTH pane boundaries share — `browse-list` for its rows and
  `detail` for the selected row. Named once because four rows key off
  it; the sub-cache is keyed by the query vector itself, so this value IS
  the cache key. Its ref-count is 2 under a full mount, which is what
  makes W4's `=`-on-reopen a real number rather than a boolean."
  [:rf.xray.static.machines/data])

;; ---- probe registrations --------------------------------------------------

(rf/reg-view ProbeRegView
  "The POSITIVE CONTROL for W3, and nothing else. An ordinary `reg-view`
  rendered by the installed adapter in the same root, in the same frame,
  in the same commit as the panel — so the only variable between it and
  the panel is which view layer authored the body."
  []
  [:div {:data-testid "rf-xray-probe-reg-view"}])

(def ^:private probe-machine
  "W2's late registration, and W6's definition. Two states and one
  transition — enough for `grammar/valid-definition?` to pass so the
  Topology body renders a chart rather than the no-definition hint."
  {:initial :idle
   :states  {:idle   {:on {:go :active}}
             :active {:on {:reset :idle}}}})

;; ---- W5's two-row fixture -------------------------------------------------
;;
;; Sorted by machine-id, so `:aaa/first` really is the HEAD row and
;; removing it is a genuine reorder rather than a tail truncation. A tail
;; removal leaves the survivor untouched under EITHER key spelling and
;; would make the row vacuous — which is why W5 asserts the head position
;; before it removes anything.

(def ^:private two-machines [:aaa/first :bbb/second])
(def ^:private one-machine  [:bbb/second])

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
;; commits nothing of these panes', and a row written that way reads a DOM that
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
  "Register Xray's handlers — which is what registers the static-machines
  sub family and the `:static` L4 tab entry the mount reads — and make
  the two frames."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- setup-with-overrides!
  "[[setup!]] plus the test-only override seam, which re-registers
  `:rf.xray/registered-machines` and the definition map to read injected
  values. W5 and W6 need the row list under their own hand; the rows that
  do not, do not install it."
  []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- override-machines!
  "Pin the registered-machine list the panes project.

  MEASURED NECESSITY, not tidiness: `:rf.xray/registered-machines`
  computes from the PROCESS-GLOBAL registrar, so what a row sees depends
  on what every other suite sharing this page has registered — the first
  run of this file found `:auth.login/flow` on screen where it expected a
  cold start. Any row asserting an ABSENCE, or asserting on the whole row
  set, pins the list first. Rows that assert about ONE named probe do not
  need to, and W2 deliberately does not: its lever is a real registration
  that the override would shadow."
  ([ids] (override-machines! :rf/xray ids))
  ([frame ids]
   (rf/dispatch-sync
     [:rf.xray/set-registered-machines-override-for-test (vec ids)]
     {:frame frame})))

(defn- override-definitions!
  ([defs] (override-definitions! :rf/xray defs))
  ([frame defs]
   (rf/dispatch-sync
     [:rf.xray/set-machine-definitions-override-for-test defs]
     {:frame frame})))

(defn- mount-panel!
  "Mount the Machines tab the way `static/shell.cljs`'s `detail-panel`
  mounts it: the registry's `:panel` value as a hiccup head, inside a
  `frame-provider` scoping `frame`. Committed synchronously — React 19's
  `root.render` is otherwise async and phase 1 would assert against an
  empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)
        tab       (panel-registry/tab-by-id :static :machines)]
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

(defn- row-node
  "The committed row `<button>` for one machine-id's `name`, or nil. The
  row button is the node carrying `data-machine-id`; the per-row jump
  chip shares the testid STEM but not that attribute, so the exact
  selector here cannot collide with it."
  [container row-name]
  (q container (str "[data-testid=\"rf-xray-static-machines-row-"
                    row-name "\"]")))

(defn- row-nodes
  "Every committed row BUTTON — filtered on `data-machine-id`, which the
  row button carries and its per-row children do not."
  [container]
  (vec (.from js/Array
              (.querySelectorAll
                container
                "[data-testid^=\"rf-xray-static-machines-row-\"][data-machine-id]"))))

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
;; W1 — first display of all THREE boundaries, and the reads land in the
;;      frame the tree named
;; ===========================================================================

(deftest w1-three-boundaries-paint-and-their-reads-land-in-the-named-frame
  (testing "rf2-k97c.3 — the migrated Static Machines sub-tab commits real
            DOM through the registry entry the Static shell mounts, its two
            nested pane boundaries mount under it, and their
            `rf.fresco/sub` reads resolve against the frame the enclosing
            `frame-provider` named rather than the ambient one. Epic
            criteria 1 and 4."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup-with-overrides!)
            ;; PIN THE LIST EMPTY. The registered-machine read is computed
            ;; from the process-global registrar, so a cold start is a
            ;; property of the whole page rather than of this row — see
            ;; `override-machines!`. Pinning it makes the two empty-state
            ;; assertions below decisions rather than coincidences.
            _ (override-machines! [])
            ;; A live read in the OTHER frame, so the negative half of the
            ;; targeting claim below is measured with an instrument that is
            ;; demonstrably able to see an entry in that frame's cache.
            _probe (rf/subscribe [:rf.xray/trace-buffer] {:frame app-frame})
            {:keys [container root]} (mount-panel! :rf/xray)]
        (try
          (is (some? (testid container "rf-xray-static-machines-panel"))
              "the panel committed a real DOM root under React — a Fresco
               boundary mounted through Reagent's `:>` from the registry entry")
          ;; ---- the two NESTED boundaries, which is this panel's own claim --
          (is (some? (testid container "rf-xray-static-machines-browse-list"))
              "the LEFT pane boundary mounted — `panel` heads `browse-list`
               directly, and a boundary is the one hiccup head shape that is
               legal inside a Fresco body")
          (is (some? (testid container "rf-xray-static-machines-detail-empty"))
              "the RIGHT pane boundary mounted too, and with no machines
               registered it committed its own empty surface — so the body
               really ran through `detail-tree` rather than painting an
               empty shell")
          (is (some? (testid container "rf-xray-static-machines-empty"))
              "and the left pane committed the cold-start empty state, which
               only `browse-list-tree` emits")

          ;; ---- criterion 4: the reads are where the tree said they were ----
          (is (pos? (ref-count-of :rf/xray data-q))
              (str "the panes' shared read holds a reference in :rf/xray's "
                   "sub-cache — the frame the enclosing frame-provider named. "
                   "Cache keys: " (pr-str (keys (cache-of :rf/xray)))))
          (is (zero? (ref-count-of app-frame data-q))
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
  (testing "rf2-k97c.3 — the mounted panes re-render themselves and commit new
            DOM when a read's value really changes, and do NOT when nothing
            they watch moved. Epic criterion 2, with the control that makes
            the update mean liveness rather than a commit that simply had not
            happened yet."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              pane   (testid container "rf-xray-static-machines-browse-list")
              probe? (fn [] (some? (row-node container "late-machine")))]
          (is (some? pane)
              "PRECONDITION: the left pane is on screen at all")
          (is (not (probe?))
              "NON-VACUITY: the machine this row drives in is NOT on screen
               before it is registered. The claim is about THIS probe row
               rather than about an empty list — the registered-machine read
               computes from the process-global registrar, so what else is on
               screen belongs to the page, not to this row")

          ;; ---- phase 2: the world moves, and the panel is deaf ------------
          ;; `:rf.xray/registered-machines` computes from the PROCESS-GLOBAL
          ;; registrar (`rf/registrations {:source :store :kind :event}`), not
          ;; from Xray's app-db. Registering a real machine therefore changes
          ;; what the read WOULD compute while invalidating nothing it
          ;; watches — the honest "the world moved" pulse.
          (rf/reg-machine :probe/late-machine probe-machine)
          (-> (settle)
              (.then
                (fn [_]
                  (is (not (probe?))
                      "CONTROL: given a full settling window, the committed DOM
                       still does NOT carry the probe's row. A pane that
                       re-rendered here would make phase 3 pass for a reason
                       that is not liveness")
                  ;; ---- phase 3: a real input of the read moves ------------
                  ;; `cycle-sort` is one of the panel's OWN affordances, fired
                  ;; through the live frame: it writes Xray's app-db, which
                  ;; invalidates the db-backed `registered-machines` read and
                  ;; the `sort-key` read the toolbar renders.
                  (rf/dispatch-sync [:rf.xray.static.machines/cycle-sort]
                                    {:frame :rf/xray})
                  (rf.test-support/poll-until probe?
                    {:label "the left pane committed the new machine's row"})))
              (.then
                (fn [_]
                  (is (probe?)
                      "the pane re-rendered on a real invalidation of its own
                       read and committed the new machine's row")
                  (is (identical?
                        pane
                        (testid container "rf-xray-static-machines-browse-list"))
                      "and it is the SAME pane node: React reconciled the live
                       tree in place, so the row did not arrive by the pane
                       being remounted from scratch, which would not be
                       liveness")))
              (.catch (fn [e]
                        (is false (str "W2 never settled: " (.-message e)
                                       " — DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (teardown! root container)
                       (done)))))))))

(deftest w2b-the-right-pane-updates-on-a-read-only-it-performs
  (testing "rf2-k97c.3 — the RIGHT pane is independently live, and this row is
            what makes the three-boundary claim mean something rather than
            being a fact about file layout. Its lever is
            `:rf.xray.static.machines/sub-mode`, a read ONLY `detail`
            performs: the left pane never touches it, so a commit here can
            only have come from the right pane's own boundary re-rendering.

            The reactive granularity is the reason the migration kept three
            boundaries instead of hoisting both panes' reads into `panel` —
            with one boundary, every search keystroke would re-render this
            Topology chart."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup-with-overrides!)
        (override-machines! [:probe/topology])
        (override-definitions! {:probe/topology probe-machine})
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              panel-root (testid container "rf-xray-static-machines-panel")
              left       (testid container "rf-xray-static-machines-browse-list")
              instances? (fn [] (some? (testid container
                                         "rf-xray-static-machines-instances-body")))]
          (is (some? (testid container "rf-xray-static-machines-topology"))
              "PRECONDITION: the default `:topology` sub-mode is on screen")
          (is (not (instances?))
              "NON-VACUITY: the body this row drives in is NOT on screen before
               the sub-mode changes")
          (rf/dispatch-sync [:rf.xray.static.machines/set-sub-mode
                             :probe/topology :instances]
                            {:frame :rf/xray})
          (-> (rf.test-support/poll-until instances?
                {:label "the right pane committed the :instances body"})
              (.then
                (fn [_]
                  (is (nil? (testid container "rf-xray-static-machines-topology"))
                      "the Topology body left the committed DOM, so the body
                       really swapped rather than the new one being appended")
                  (is (identical? panel-root
                                  (testid container "rf-xray-static-machines-panel"))
                      "the panel chrome is the SAME node — `panel` reads
                       nothing, so it had no reason to re-render and React
                       reconciled in place")
                  (is (identical? left
                                  (testid container "rf-xray-static-machines-browse-list"))
                      "and so is the LEFT pane, which reads none of what
                       moved — the update was scoped to the boundary whose
                       read changed, which is the whole point of keeping
                       three")))
              (.catch (fn [e]
                        (is false (str "W2b never settled: " (.-message e)
                                       " — DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (teardown! root container)
                       (done)))))))))

;; ===========================================================================
;; W3 — the tool's own render is not application view evidence
;; ===========================================================================

(deftest w3-the-boundarys-render-emits-no-view-trace
  (testing "rf2-k97c.3 / rf2-tqlmq — rendering the migrated sub-tab
            contributes NOTHING to the substrate's view-trace stream, even
            when it is mounted INSIDE an application frame. Epic criterion 5,
            proven structurally rather than by the `:rf/xray` frame gate: a
            Fresco boundary is not a substrate view render, so there is no
            event to gate. The control is an ordinary `reg-view` in the same
            root, the same frame and the same commit."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_        (setup-with-overrides!)
            ;; PIN THE LIST EMPTY IN THE FRAME THE SUBJECT MOUNTS IN, and
            ;; the scope of the zero below turns on it — see the docstring's
            ;; last paragraph. The override slot is per-frame, so this is
            ;; `app-frame`'s and not `:rf/xray`'s.
            _        (override-machines! app-frame [])
            traces   (atom [])
            view-op? #(and (keyword? (:operation %))
                           (= "rf.view" (namespace (:operation %))))]
        (rf/register-listener! :trace ::collect (fn [ev] (swap! traces conj ev)))
        (try
          ;; ---- the subject: the panel, mounted in an APPLICATION frame ----
          (let [{:keys [container root]} (mount-panel! app-frame)
                subject-views (filterv view-op? @traces)]
            (try
              (is (some? (testid container "rf-xray-static-machines-panel"))
                  "precondition: the panel really did render in this commit —
                   an empty container would make the zero below vacuous")
              (is (some? (testid container "rf-xray-static-machines-browse-list"))
                  "precondition: and so did the nested pane boundaries, so the
                   zero covers all three renders and not just the outermost")
              (is (nil? (testid container "rf-xray-static-machines-topology"))
                  "SCOPE, asserted rather than assumed: no Topology body is on
                   screen, so no Reagent island rendered in this commit and the
                   zero below is about the three BOUNDARIES' own renders")
              (is (zero? (count subject-views))
                  (str "the three boundaries' renders put NO :rf.view/* op in "
                       "the trace stream while rendering inside an application "
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

(defn- released? []
  (zero? (ref-count-of :rf/xray data-q)))

(deftest w4-unmount-releases-the-read-and-reopen-does-not-grow-it
  (testing "rf2-k97c.3 — unmounting the sub-tab releases its subscription
            references completely, and mounting it again returns to the SAME
            count rather than a higher one. Epic criterion 6, and the number
            the spike caught the rejected design on: with a four-call interop
            binding the `:rf/xray` ref-count climbed across renders and never
            fell on unmount.

            THE COUNT IS TWO HERE, not one — both pane boundaries read
            `:rf.xray.static.machines/data` — which is what makes the
            reopen assertion a real number rather than a boolean, and what
            would catch one pane releasing while the other leaked.

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
                      mounted (ref-count-of :rf/xray data-q)]
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
                                remounted (ref-count-of :rf/xray data-q)]
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
            metadata and onto a keyed fragment's ATTRIBUTE MAP is the key
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
        (override-machines! two-machines)
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              head     (row-node container "first")
              survivor (row-node container "second")]
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
          (override-machines! one-machine)
          (-> (rf.test-support/poll-until
                (fn [] (= 1 (count (row-nodes container))))
                {:label "the head row left the committed DOM"})
              (.then
                (fn [_]
                  (is (nil? (row-node container "first"))
                      "the removed row is gone from the committed DOM")
                  (is (identical? survivor (row-node container "second"))
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

;; ===========================================================================
;; W6 — the Reagent island really crosses, and the reg-view inside it renders
;; ===========================================================================

(deftest w6-the-topology-body-crosses-as-a-reagent-island
  (testing "rf2-k97c.3 — `detail-tree` hands the Topology body to
            `reagent.core/as-element`, so it reaches React as a finished
            ELEMENT — a legal child anywhere per Fresco's component ABI —
            and everything inside it renders under REAGENT rather than under
            Fresco's codec.

            The three toolbar nodes below exist ONLY if Reagent expanded fn
            HEADS (`[chart-toolbar …]`, `[popout-affordance …]`, `[chart …]`
            in `topology.cljs`), each of which Fresco's codec would refuse
            outright as HD-016; and the canvas host exists only if
            `machine-canvas/Chart` — an `rf/reg-view`, which grades
            `:invalid` in a Fresco head position exactly as a plain `defn`
            does — rendered too, resolving its own frame from the SAME React
            context the boundary read.

            This is the row no earlier panel slice needed, and the door it
            measures is the one `panels/machine_after_rings.cljs` documents."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup-with-overrides!)]
        (override-machines! [:probe/topology])
        (override-definitions! {:probe/topology probe-machine})
        (let [{:keys [container root]} (mount-panel! :rf/xray)]
          (try
            (is (some? (testid container "rf-xray-static-machines-detail-header"))
                "PRECONDITION: the right pane selected the machine and painted
                 its header — without a selection there is no Topology body to
                 cross at all")
            (is (some? (testid container "rf-xray-static-machines-topology"))
                "the Topology body committed — the island crossed")
            (is (some? (testid container "rf-xray-static-machines-topology-toolbar"))
                "and `[chart-toolbar …]`, a PLAIN FN in hiccup head position,
                 rendered inside it — Fresco's codec would have refused that
                 head, so this node is the crossing itself")
            (is (some? (testid container "rf-xray-static-machines-topology-popout"))
                "as did `[popout-affordance …]` one level deeper, so the island
                 is a whole Reagent subtree rather than one converted node")
            (is (some? (testid container "rf-xray-machine-canvas-host"))
                "and `machine-canvas/Chart` — an `rf/reg-view`, the head shape
                 the codec grades `:invalid` down the same arm as a plain
                 `defn` — rendered its canvas host, so a reg-view survives
                 inside the island and resolves its frame from React context")
            (is (nil? (testid container "rf-xray-static-machines-topology-no-definition"))
                "NON-VACUITY: the no-definition hint is ABSENT, so the chart
                 arm is the arm that ran")
            (finally
              (teardown! root container))))))))
