(ns day8.re-frame2-xray.panels.module-view-fresco-boundary-dom-cljs-test
  "THE MODULE-VIEW PANEL IN THE RE-FRAME-NATIVE VIEW LAYER, read off a
  real React commit.

  `module-view/Panel` is an `h/defview` reading through Fresco's
  shipped collector rather than an `rf/reg-view` reading through whatever
  view build the installed substrate adapter supplies. W1 is the evidence
  this panel's own mount needs: FIRST DISPLAY through the registry entry
  the shell mounts, and FRAME TARGETING read off the frame's OWN sub-cache
  rather than off the DOM, against a probe proving the instrument can see
  an entry in the other frame.

  The rest of the boundary contract — an update on a real dependency
  change, no view trace from the boundary's render, a clean teardown — is
  Fresco's own and identical for every `defview` whose body is pure, so it
  is proven on the panels whose source names a boundary suite as their
  witness, `resources_fresco_boundary_dom_cljs_test` among them.

  ## The mount is the SHELL's mount, taken from the registry

  `shell/detail-panel` mounts the active tab as the hiccup head
  `[(:panel tab)]` inside the shell's `[rf/frame-provider {:frame …}]`.
  [[mount-panel!]] does exactly that, and it reaches `:panel` THROUGH
  `panel-registry/tab-by-id` rather than naming the var — so the bridge
  the registry actually holds is the thing under test. A bridge the
  shell cannot mount reddens here.

  Nothing below ever calls the panel a second time. Every assertion after
  the mount reads `container.querySelector…` — the DOM React committed on
  its own.

  ## `:ambient-frame nil` is load-bearing

  The fixture's default ambient `:rf/default` scope is in effect during a
  synchronous `flushSync`, and tier 1 of
  `re-frame.views.provider/current-frame` is the dynamic var — so an
  ambient frame would SHADOW the React-context tier this file is about and
  W1's frame-targeting row would pass while measuring nothing.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium) per
  `implementation/shadow-cljs.edn`, whose `:source-paths` carry
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
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private app-frame
  "An ORDINARY application frame — the thing Xray inspects. W1 reads it as
  the negative half of the frame-targeting claim."
  ::app)

(def ^:private image-view-q
  "The panel's one read. The sub-cache is keyed by the query vector itself
  (`re-frame.subs/cache-key` is identity), so this value IS the cache key."
  [:rf.xray/image-view])

;; ---- the probe W1 holds in the application frame ----------------------------

(rf/reg-sub ::n (fn [db _] (::n db)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about; a
                      ;; neighbour's boundary left in the entry cache would
                      ;; make W1's ref-counts include a residue that is not
                      ;; this panel's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

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
  the collector releases a boundary's reads — have RUN before the next row
  starts. A bare `.unmount` schedules them."
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
  entry is absent."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

;; ===========================================================================
;; W1 — first display, and the read lands in the frame the tree named
;; ===========================================================================

(deftest w1-panel-paints-and-its-read-lands-in-the-named-frame
  (testing "the Frames panel commits real DOM through the
            registry entry the shell mounts, and its `h/sub` read resolves
            against the frame the enclosing `frame-provider` named rather than
            the ambient one: first display and frame targeting."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            ;; A live read in the OTHER frame, so the negative half of the
            ;; targeting claim below is measured with an instrument that is
            ;; demonstrably able to see an entry in that frame's cache.
            _probe (rf/subscribe [::n] {:frame app-frame})
            {:keys [container root]} (mount-panel! :rf/xray)]
        (try
          (is (some? (q container "[data-testid=\"rf-xray-module-view-frames\"]"))
              "the panel committed a real DOM root under React — a Fresco
               boundary mounted through Reagent's `:>` from the registry entry —
               and the body ran rather than short-circuiting to nil")

          ;; ---- frame targeting: the read is where the tree said it would be ----
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
