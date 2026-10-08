(ns day8.re-frame2-xray.panels.routing-fresco-boundary-dom-cljs-test
  "THE ROUTES PANEL IN THE RE-FRAME-NATIVE VIEW LAYER, read off a real
  React commit.

  `routing/PanelView` is an `rf.fresco/defview` reading through
  Fresco's shipped collector rather than an `rf/reg-view` reading
  through whatever view build the installed substrate adapter supplies.
  This file is the behavioural evidence for that: the
  shared boundary template applied to this
  panel, trimmed to the rows this panel's boundary can be wrong about,
  each with the control that makes its answer non-vacuous.

  ## The boundary criteria, and which row answers each

    1 FIRST DISPLAY               — W1
    4 FRAME TARGETING             — W1's second half, read off the frame's
                                    OWN sub-cache rather than off the DOM

  Criteria 2 (updates on a real dependency change), 5 (tool activity
  never masquerading as application evidence) and 6 (clean teardown) are
  properties of the mechanism rather than of this panel — an
  `rf.fresco/defview` behind the `as-component` bridge with one
  `rf.fresco/sub` read — and `resources_fresco_boundary_dom_cljs_test`'s
  W2, W3 and W4 prove them for that same mechanism. Criterion 3 (Xray's
  own interactions) has no row because the Routes panel dispatches
  nothing.

  ## The mount is the SHELL's mount, taken from the registry

  `shell/detail-panel` mounts the active tab as the hiccup head
  `[(:panel tab)]` inside the shell's `[rf/frame-provider {:frame …}]`.
  [[mount-panel!]] does exactly that, and it reaches `:panel` THROUGH
  `panel-registry/tab-by-id` rather than naming the var — so the bridge
  the registry actually holds is the thing under test. A bridge the
  shell cannot mount reddens here.

  Nothing below ever calls the panel a second time. Every assertion
  after the mount reads `container.querySelector…` — the DOM React
  committed on its own.

  ## Substrate: the Reagent adapter, deliberately

  A ratom-family adapter, which is a family Xray supports —
  because the claim being made is that the boundary is INDIFFERENT to
  it. W1 shows its read lands in the frame React context named rather
  than in the ambient one.

  `:ambient-frame nil` is load-bearing, exactly as it is in the
  template: the fixture's default ambient `:rf/default` scope is
  in effect during a synchronous `flushSync`, and tier 1 of
  `re-frame.views.provider/current-frame` is the dynamic var — so an
  ambient frame would SHADOW the React-context tier this file is about
  and W1's frame-targeting row would pass while measuring nothing.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium) per
  `implementation/shadow-cljs.edn`, whose `:source-paths` carry
  `tools/xray/test`. The `:node-test` build's `cljs-test$` regex also
  matches, so it LOADS under Node — where every row short-circuits
  through [[browser?]] and reports the skip rather than passing
  silently."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
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

(def ^:private routing-q
  "The panel's one read. The sub-cache is keyed by the query vector itself
  (`re-frame.subs/cache-key` is identity), so this value IS the cache key."
  [:rf.xray/routing-tab-data])

(def ^:private base-routes
  "The route table the panel opens on. W1 finds the cart row on screen,
  which is how it knows the read reached this data."
  {:route/cart     {:path "/cart"     :doc "cart"}
   :route/checkout {:path "/checkout" :doc "checkout"}})

;; ---- a probe sub: W1's foreign-frame read ---------------------------------

(rf/reg-sub ::n (fn [db _] (::n db)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing
                      ;; about; a neighbour's boundary left in the entry
                      ;; cache would be residue W1's ref-count reads see.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- setup!
  "Register Xray's handlers (which is what registers
  `:rf.xray/routing-tab-data` and the L4 tab entry the mount reads),
  open the test-only override seam the route table is driven through,
  and make the two frames."
  []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- set-routes!
  "Drive the panel's route table through the panel's own test-override
  seam (`install-test-overrides!`) — a real app-db write into `:rf/xray`
  that `:rf.xray/registered-routes-override` and the composite above it
  read."
  [routes]
  (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test routes]
                    {:frame :rf/xray}))

(defn- mount-panel!
  "Mount the Routes tab the way `shell/detail-panel` mounts it: the
  registry's `:panel` value as a hiccup head, inside a `frame-provider`
  scoping `frame`. Committed synchronously — React 19's `root.render` is
  otherwise async and W1 would assert against an empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)
        tab       (panel-registry/tab-by-id :dynamic :routing)]
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
  entry is absent. A leaky binding shows as this number climbing across
  renders and never falling on unmount, so it is the number the boundary
  is answerable on."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

;; ===========================================================================
;; W1 — first display, and the read lands in the frame the tree named
;; ===========================================================================

(deftest w1-panel-paints-and-its-read-lands-in-the-named-frame
  (testing "the Routes panel commits real DOM through
            the registry entry the shell mounts, and its `rf.fresco/sub`
            read resolves against the frame the enclosing `frame-provider`
            named rather than the ambient one. Criteria 1 and 4."
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
          (is (some? (q container "[data-testid=\"rf-xray-routing\"]"))
              "the panel committed a real DOM root under React — a Fresco
               boundary mounted through Reagent's `:>` from the registry entry")
          (is (some? (q container "[data-testid=\"rf-xray-routing-table\"]"))
              "and the ROUTE TABLE section rendered, so the body ran rather
               than short-circuiting to the silent state")
          (is (some? (q container "[data-testid=\"rf-xray-routing-table-row-cart\"]"))
              "and a real route row is on screen, so the read reached the
               override seam's data and not an empty topology")

          ;; ---- criterion 4: the read is where the tree said it would be ----
          (is (pos? (ref-count-of :rf/xray routing-q))
              (str "the panel's read holds a reference in :rf/xray's sub-cache "
                   "— the frame the enclosing frame-provider named. Cache keys: "
                   (pr-str (keys (cache-of :rf/xray)))))
          (is (zero? (ref-count-of app-frame routing-q))
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
