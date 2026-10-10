(ns day8.re-frame2-xray.static.routes.panel-fresco-boundary-dom-cljs-test
  "The Static Routes tab, read off a real React commit.

  `static.routes.panel/Panel` is an `rf.fresco/defview` boundary that
  hands its browse list and its Simulate-URL header to React through an
  `as-child` seam: both bottom out in plain fns in hiccup head position,
  which Fresco's codec grades `:invalid`, so the boundary passes
  `substrate/as-element`. The node lane passes `identity`, which makes
  wrapping and not wrapping the same value there BY CONSTRUCTION — only a
  real commit sees the seam, so every row asserts on nodes that exist
  only if an island crossed.

  The mount is the Static shell's own: the registry's `:panel` value as a
  hiccup head under `rf/frame-provider`, reached through
  `panel-registry/tab-by-id`, so the private bridge the registry holds is
  what is under test.

  `:ambient-frame nil` is load-bearing: an ambient frame would shadow the
  React-context tier the frame-targeting assertions are about.

  The ns ends `-dom-cljs-test`, so the `:browser-test` build runs it; the
  `:node-test` build loads it too, where every row reports a skip."
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
  "An ordinary application frame — the negative half of W1's
  frame-targeting claim and W5's cross-frame control."
  ::app)

(def ^:private tab-data-q [:rf.xray.static.routes/tab-data])

(def ^:private expanded-q [:rf.xray.static.routes/expanded])

(def ^:private base-routes
  {:route/cart     {:path "/cart"     :doc "cart"}
   :route/checkout {:path "/checkout" :doc "checkout"}})

(rf/reg-sub ::n (fn [db _] (::n db)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     ;; `cljs.test` refuses a FUNCTION fixture in a namespace carrying an
     ;; `async` row, and `:async?` hands back the `{:before :after}` form.
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture does not reset.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- setup!
  "Register Xray's handlers — the static-routes subs and the `:static` L4
  tab entry the mount reads — open the test-only route override, and make
  the two frames."
  []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- set-routes! [routes]
  (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test routes]
                    {:frame :rf/xray}))

(defn- mount-panel!
  "Mount the tab the way `static/shell.cljs`'s `detail-panel` does — the
  registry's `:panel` as a hiccup head inside a `frame-provider` scoping
  `frame` — committed synchronously so the first assertions see it."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)
        tab       (panel-registry/tab-by-id :static :routes)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [(:panel tab)]])))
    {:container container :root root}))

(defn- teardown! [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- testid [container id]
  (.querySelector container (str "[data-testid=\"" id "\"]")))

(defn- route-suffix [route-id]
  (subs (pr-str route-id) 1))

(defn- row-node [container route-id]
  (testid container (str "rf-xray-static-routes-row-" (route-suffix route-id))))

(defn- expand-node
  "The committed inline expand surface — emitted by `row-expand/render`, a
  plain fn head two levels inside the island."
  [container route-id]
  (testid container (str "rf-xray-static-routes-expand-" (route-suffix route-id))))

(defn- ref-count-of [frame-id query-v]
  (or (:ref-count (get @(:sub-cache (rf.frame/frame frame-id)) query-v)) 0))

;; ===========================================================================
;; W1 — first display through both islands, and the read lands in the frame
;;      the tree named
;; ===========================================================================

(deftest w1-panel-paints-through-the-island-and-reads-the-named-frame
  (testing "both Reagent islands cross, and the boundary's read lands in the
            frame the enclosing `frame-provider` names rather than the
            ambient one"
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            _ (set-routes! base-routes)
            probe (rf/subscribe [::n] {:frame app-frame})
            {:keys [container root]} (mount-panel! :rf/xray)]
        (try
          (is (some? (testid container "rf-xray-static-routes-sim"))
              "the Simulate-URL header island crossed")
          (is (some? (row-node container :route/cart))
              "the browse-list island crossed and painted a real route row")
          (is (pos? (ref-count-of :rf/xray tab-data-q)))
          (is (zero? (ref-count-of app-frame tab-data-q)))
          (is (pos? (ref-count-of app-frame [::n]))
              "CONTROL: the same instrument does see the application frame's
               own entry, so the zero above is an absence")
          (finally
            (rf/unsubscribe probe)
            (teardown! root container)))))))

;; ===========================================================================
;; W5 — a real click inside the island dispatches into the frame the tree
;;      named, through the dispatcher the boundary threads into the island
;; ===========================================================================

(deftest w5-a-click-inside-the-island-dispatches-into-the-named-frame
  (testing "clicking a catalogue row inside the island dispatches
            `toggle-row` into the frame the enclosing `frame-provider`
            names, and the inline expand surface commits"
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (set-routes! base-routes)
        (let [{:keys [container root]} (mount-panel! :rf/xray)
              ;; The row's role=button body carries the toggle handler.
              button (some-> (row-node container :route/cart)
                             (.querySelector "[role=\"button\"]"))]
          (is (nil? (expand-node container :route/cart))
              "PRECONDITION: the expand surface starts closed")
          (when button (.click button))
          (-> (rf.test-support/poll-until
                #(some? (expand-node container :route/cart))
                {:label "the panel committed the inline expand surface"})
              (.then
                (fn [_]
                  (is (contains? (rf/subscribe-once expanded-q {:frame :rf/xray})
                                 :route/cart)
                      "the toggle landed in :rf/xray")
                  (is (empty? (rf/subscribe-once expanded-q {:frame app-frame}))
                      "CROSS-FRAME CONTROL: and not in the application frame")))
              (.catch (fn [e]
                        (is false (str "W5 never settled: " (.-message e)
                                       " — DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (teardown! root container)
                       (done)))))))))
