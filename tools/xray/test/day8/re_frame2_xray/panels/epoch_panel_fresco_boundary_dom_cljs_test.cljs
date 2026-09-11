(ns day8.re-frame2-xray.panels.epoch-panel-fresco-boundary-dom-cljs-test
  "The Epoch panel re-authored in the re-frame-native view layer, read off a
  real React commit (rf2-k97c.3).

  `panels.epoch.view/Panel` is now an `rf.fresco/defview` reading through
  Fresco's shipped collector rather than an `rf/reg-view` reading through
  whatever view build the installed substrate adapter supplies. This file is
  the behavioural evidence for that swap. Nothing in the fast node lane can
  make it: `view_cljs_test` drives the step renderers as pure functions, and
  a boundary's body only runs inside a React render window.

  ## What the epic asked for, and which row answers it

    1 FIRST DISPLAY               — W1
    2 UPDATES ON A REAL CHANGE    — W2 (with the deaf control that makes the
                                    update mean liveness rather than a
                                    commit that had not happened yet)
    4 FRAME TARGETING             — W1's second half, read off the frame's
                                    OWN sub-cache rather than off the DOM
    6 CLEAN TEARDOWN              — W3

  Criterion 3 (Xray's own interactions) and criterion 5 (tool activity never
  masquerading as application evidence) have no row HERE, and in both cases
  that is a division of labour rather than a gap. Criterion 5 is structural
  and identical for every boundary in this migration — a Fresco boundary is
  not a substrate view render, so there is no `:rf.view/*` op to gate — and
  `static/flows/panel_fresco_boundary_dom_cljs_test` already carries it with
  its positive `reg-view` control. Criterion 3's affordances here (the
  subscriptions filter bar, the parent-epoch and app-db jump links) dispatch
  through a frame captured at render time by `rf/current-frame-id`, which
  this migration did not touch and which `view_cljs_test` grades directly.

  ## THREE READS, AND WHY THE COUNT IS THE POINT

  The boundary reads `:rf.xray/epoch-pipeline`, `:rf.xray/selected-epoch-
  record` and `:rf.xray.epoch/subs-filter-mode`. The last two used to be
  performed by helpers deep in the cascade; rf2-k97c.3 hoisted them into the
  body so the helpers stay pure functions the node lane can drive. W1 and W3
  therefore assert on ALL THREE cache entries rather than on one: a hoist
  that half-landed would leave a read stranded in a helper, where it would
  raise `:rf.error/fresco-sub-outside-render` on the first render — loudly,
  which is why W1 asserting the panel painted at all is already most of that
  claim.

  ## The mount is the SHELL's mount, taken from the registry

  `shell.cljs`'s `detail-panel` mounts the active tab as the hiccup head
  `[(:panel tab)]` inside the shell's `[rf/frame-provider {:frame …}]`.
  [[mount-panel!]] does exactly that, and it reaches `:panel` THROUGH
  `panel-registry/tab-by-id :dynamic :epoch` rather than naming the var — so
  the BRIDGE the registry actually holds is the thing under test. That
  matters more here than the phrasing suggests: `Panel` is a React component
  now, `reg-l4-tab!`'s `:pre` requires `:panel` to be CALLABLE, and a
  registration left pointing at `Panel` rather than `Panel-bridge` reddens
  here rather than in a browser.

  Nothing below ever calls the panel a second time. Every assertion after the
  mount reads `container.querySelector…` — the DOM React committed on its own.

  ## Substrate: the Reagent adapter, deliberately

  A ratom-family adapter, which is the family Xray already supports, because
  the claim being made is that the boundary is INDIFFERENT to it.
  `:ambient-frame nil` is load-bearing: the fixture's default ambient scope
  is still in effect during a synchronous `flushSync`, and tier 1 of the
  frame resolver is the dynamic var, so an ambient frame would SHADOW the
  React-context tier W1's frame-targeting row is about — and that row would
  pass while measuring nothing.

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
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private app-frame
  "An ORDINARY application frame — the thing Xray inspects. W1 reads it as
  the negative half of the frame-targeting claim."
  ::app)

(def ^:private pipeline-q
  "The boundary's primary read. Named once because three rows key off it —
  the sub-cache is keyed by the query vector itself, so this value IS the
  cache key."
  [:rf.xray/epoch-pipeline])

(def ^:private record-q  [:rf.xray/selected-epoch-record])
(def ^:private filter-q  [:rf.xray.epoch/subs-filter-mode])

(def ^:private boundary-reads
  "Every query the boundary issues, in the order the body issues them."
  [pipeline-q record-q filter-q])

;; W2's DEAF lever. It writes a key on Xray's own app-db that NO sub in the
;; panel's read set consults, so the world moves and nothing the panel
;; watches is invalidated. Without it, phase 3's repaint would be evidence
;; that a commit happened — not that this boundary is live.
(rf/reg-event ::write-unwatched-slot
  (fn [{:keys [db]} [_ n]]
    {:db (assoc db ::probe n)}))

(def ^:private fixture-history
  "One epoch carrying a dispatch the projection turns into a cascade. Kept
  minimal on purpose: these rows are about the boundary, and `view_cljs_test`
  owns what each step renders."
  [{:epoch-id 1
    :dispatch-id "d-1"
    :event [:counter/inc 7]
    :trace-events
    [{:id 1 :time 10 :operation :rf.event/dispatch
      :tags {:event [:counter/inc 7] :rf.trace/dispatch-id "d-1"}}]}])

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
;; DOM that has not moved and reports a live panel as dead. Mount is
;; committed with `flushSync` (React's own door) and everything after it is
;; polled.

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
  "Register Xray's handlers — which installs the epoch sub family and the
  `:epoch` L4 tab entry the mount reads — plus the test-override seam the
  liveness lever writes through, and make the two frames."
  []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- set-history! [history]
  (rf/dispatch-sync [:rf.xray/set-epoch-history-for-test history]
                    {:frame :rf/xray}))

(defn- focus-epoch! [epoch-id]
  (rf/dispatch-sync [:rf.xray/set-focus-epoch-id-for-test epoch-id]
                    {:frame :rf/xray}))

(defn- mount-panel!
  "Mount the Epoch tab the way `shell.cljs`'s `detail-panel` mounts it: the
  registry's `:panel` value as a hiccup head, inside a `frame-provider`
  scoping `frame`. Committed synchronously — React 19's `root.render` is
  otherwise async and the first assertion would run against an empty
  container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)
        tab       (panel-registry/tab-by-id :dynamic :epoch)]
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
  (q container "[data-testid=\"rf-xray-epoch-panel\"]"))

(defn- cascade? [container]
  (some? (q container "[data-testid=\"rf-xray-epoch-pipeline\"]")))

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
;; W1 — first display, and every read lands in the frame the tree named
;; ===========================================================================

(deftest w1-panel-paints-and-its-reads-land-in-the-named-frame
  (testing "rf2-k97c.3 — the migrated Epoch panel commits real DOM through
            the registry entry the Dynamic shell mounts, and each of its
            three `rf.fresco/sub` reads resolves against the frame the
            enclosing `frame-provider` named rather than the ambient one.
            Epic criteria 1 and 4."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            ;; A live read in the OTHER frame, so the negative half of the
            ;; targeting claim below is measured with an instrument that is
            ;; demonstrably able to see an entry in that frame's cache.
            _probe (rf/subscribe [:rf.xray/trace-buffer] {:frame app-frame})
            _ (set-history! fixture-history)
            _ (focus-epoch! 1)
            {:keys [container root]} (mount-panel! :rf/xray)]
        (try
          (is (some? (panel-node container))
              "the panel committed a real DOM root under React — a Fresco
               boundary mounted through Reagent's `:>` from the registry entry")
          (is (cascade? container)
              "and with a focused epoch in the spine it committed the numbered
               cascade, so the body really ran through `pipeline-view` rather
               than painting an empty shell")

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
            DOM when its read's value really changes, and does NOT when
            nothing it watches moved. Epic criterion 2, with the control that
            makes the update mean liveness rather than a commit that simply
            had not happened yet."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        ;; Mount with an EMPTY spine, so the panel starts on its empty state
        ;; and the cascade's ARRIVAL is the signal. The history is the lever
        ;; rather than the focus, and that is measured rather than chosen:
        ;; the focus-resolver HEAD-TRACKS, so loading history with no explicit
        ;; focus already paints the cascade and a focus-as-lever row would
        ;; fail its own precondition.
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              section (panel-node container)]
          (is (some? section)
              "PRECONDITION: the panel is on screen at all")
          (is (not (cascade? container))
              "NON-VACUITY: with an empty spine the cascade is NOT on screen
               before this row moves the world")

          ;; ---- phase 2: the world moves, and the panel is deaf ------------
          (rf/dispatch-sync [::write-unwatched-slot 1] {:frame :rf/xray})
          (-> (settle)
              (.then
                (fn [_]
                  (is (not (cascade? container))
                      "CONTROL: given a full settling window, a write to an
                       app-db slot the panel's read set does not consult
                       commits nothing. A panel that repainted here would make
                       phase 3 pass for a reason that is not liveness")
                  ;; ---- phase 3: the read's real input moves --------------
                  (set-history! fixture-history)
                  (rf.test-support/poll-until #(cascade? container)
                    {:label "the panel committed the focused epoch's cascade"})))
              (.then
                (fn [_]
                  (is (cascade? container)
                      "the panel re-rendered on a real invalidation of its own
                       read and committed the cascade")
                  (is (identical? section (panel-node container))
                      "and it is the SAME <section> node: React reconciled the
                       live tree in place, so the cascade did not arrive by the
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
;; W3 — unmount releases every read, and reopening does not grow the count
;; ===========================================================================

(deftest w3-unmount-releases-the-reads-and-reopen-does-not-grow-them
  (testing "rf2-k97c.3 — unmounting the panel releases ALL THREE subscription
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
